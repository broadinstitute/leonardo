package org.broadinstitute.dsde.workbench.leonardo
package http

import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import cats.Parallel
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.mtl.Ask
import cats.syntax.all._
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.container.ContainerScopes
import com.google.auth.oauth2.GoogleCredentials
import com.google.devtools.clouderrorreporting.v1beta1.ProjectName
import fs2.Stream
import fs2.concurrent.InspectableQueue
import io.chrisdavenport.log4cats.StructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import javax.net.ssl.SSLContext
import org.broadinstitute.dsde.workbench.errorReporting.ErrorReporting
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.{Json, Token}
import org.broadinstitute.dsde.workbench.google2.{
  credentialResource,
  ComputePollOperation,
  Event,
  GKEService,
  GoogleComputeService,
  GoogleDataprocService,
  GoogleDiskService,
  GooglePublisher,
  GoogleResourceService,
  GoogleStorageService,
  GoogleSubscriber
}
import org.broadinstitute.dsde.workbench.leonardo.AsyncTaskProcessor.Task
import org.broadinstitute.dsde.workbench.google.{
  GoogleStorageDAO,
  HttpGoogleDirectoryDAO,
  HttpGoogleIamDAO,
  HttpGoogleStorageDAO
}
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.google2.util.RetryPredicates
import org.broadinstitute.dsde.workbench.leonardo.auth.{PetClusterServiceAccountProvider, SamAuthProvider}
import org.broadinstitute.dsde.workbench.leonardo.config.Config._
import org.broadinstitute.dsde.workbench.leonardo.config.LeoExecutionModeConfig
import org.broadinstitute.dsde.workbench.leonardo.dao._
import org.broadinstitute.dsde.workbench.leonardo.dao.google.GoogleOAuth2Service
import org.broadinstitute.dsde.workbench.leonardo.db.DbReference
import org.broadinstitute.dsde.workbench.leonardo.dns.{KubernetesDnsCache, RuntimeDnsCache}
import org.broadinstitute.dsde.workbench.leonardo.http.api.{HttpRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.leonardo.http.service.{LeoAppServiceInterp, DiskServiceInterp, _}
import org.broadinstitute.dsde.workbench.leonardo.model.ServiceAccountProvider
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubCodec._
import org.broadinstitute.dsde.workbench.leonardo.monitor.NonLeoMessageSubscriber.nonLeoMessageDecoder
import org.broadinstitute.dsde.workbench.leonardo.monitor._
import org.broadinstitute.dsde.workbench.leonardo.util._
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.util2.ExecutionContexts
import org.broadinstitute.dsp.{HelmAlgebra, HelmInterpreter}
import org.http4s.client.blaze
import org.http4s.client.middleware.{Retry, RetryPolicy, Logger => Http4sLogger}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object Boot extends IOApp {
  val workbenchMetricsBaseName = "google"

  private def startup(): IO[Unit] = {
    // We need an ActorSystem to host our application in
    implicit val system = ActorSystem(applicationConfig.applicationName)
    import system.dispatcher

    implicit val logger = Slf4jLogger.getLogger[IO]

    createDependencies[IO](applicationConfig.leoServiceAccountJsonFile.toString).use { appDependencies =>
      val googleDependencies = appDependencies.googleDependencies

      implicit val dbRef = appDependencies.dbReference
      implicit val openTelemetry = googleDependencies.openTelemetryMetrics

      val bucketHelperConfig = BucketHelperConfig(
        imageConfig,
        welderConfig,
        proxyConfig,
        securityFilesConfig,
        clusterResourcesConfig
      )
      val bucketHelper = new BucketHelper(bucketHelperConfig,
                                          appDependencies.google2StorageDao,
                                          appDependencies.serviceAccountProvider,
                                          appDependencies.blocker)
      val vpcInterp =
        new VPCInterpreter(vpcInterpreterConfig,
                           googleDependencies.googleResourceService,
                           googleDependencies.googleComputeService,
                           googleDependencies.computePollOperation)

      val dataprocInterp = new DataprocInterpreter(
        dataprocInterpreterConfig,
        bucketHelper,
        vpcInterp,
        googleDependencies.googleDataproc,
        googleDependencies.googleComputeService,
        googleDependencies.googleDiskService,
        googleDependencies.googleDirectoryDAO,
        googleDependencies.googleIamDAO,
        googleDependencies.googleResourceService,
        appDependencies.welderDAO,
        appDependencies.blocker
      )

      val gceInterp = new GceInterpreter(
        gceInterpreterConfig,
        bucketHelper,
        vpcInterp,
        googleDependencies.googleComputeService,
        googleDependencies.googleDiskService,
        appDependencies.welderDAO,
        appDependencies.blocker
      )
      implicit val runtimeInstances = new RuntimeInstances(dataprocInterp, gceInterp)

      val dateAccessedUpdater =
        new DateAccessedUpdater(dateAccessUpdaterConfig, appDependencies.dateAccessedUpdaterQueue)
      val proxyService = new ProxyService(
        appDependencies.sslContext,
        proxyConfig,
        appDependencies.jupyterDAO,
        appDependencies.runtimeDnsCache,
        googleDependencies.kubernetesDnsCache,
        appDependencies.authProvider,
        appDependencies.dateAccessedUpdaterQueue,
        googleDependencies.googleOauth2DAO,
        appDependencies.blocker
      )
      val statusService = new StatusService(appDependencies.samDAO, appDependencies.dbReference, applicationConfig)
      val runtimeServiceConfig = RuntimeServiceConfig(
        proxyConfig.proxyUrlBase,
        imageConfig,
        autoFreezeConfig,
        dataprocConfig,
        gceConfig
      )
      val runtimeService = new RuntimeServiceInterp[IO](
        runtimeServiceConfig,
        persistentDiskConfig,
        appDependencies.authProvider,
        appDependencies.serviceAccountProvider,
        appDependencies.dockerDAO,
        appDependencies.google2StorageDao,
        googleDependencies.googleComputeService,
        googleDependencies.computePollOperation,
        appDependencies.publisherQueue
      )
      val diskService = new DiskServiceInterp[IO](
        persistentDiskConfig,
        appDependencies.authProvider,
        appDependencies.serviceAccountProvider,
        appDependencies.publisherQueue
      )

      val leoKubernetesService: LeoAppServiceInterp[IO] =
        new LeoAppServiceInterp(appDependencies.authProvider,
                                appDependencies.serviceAccountProvider,
                                leoKubernetesConfig,
                                appDependencies.publisherQueue)

      val httpRoutes = new HttpRoutes(
        swaggerConfig,
        statusService,
        proxyService,
        runtimeService,
        diskService,
        leoKubernetesService,
        StandardUserInfoDirectives,
        contentSecurityPolicy,
        refererConfig
      )
      val httpServer = for {
        start <- Timer[IO].clock.realTime(TimeUnit.MILLISECONDS)
        implicit0(ctx: Ask[IO, AppContext]) = Ask.const[IO, AppContext](
          AppContext(TraceId(s"Boot_${start}"), Instant.ofEpochMilli(start))
        )

        _ <- if (leoExecutionModeConfig == LeoExecutionModeConfig.BackLeoOnly) {
          dataprocInterp.setupDataprocImageGoogleGroup
        } else IO.unit
        _ <- IO.fromFuture {
          IO {
            Http()
              .newServerAt("0.0.0.0", 8080)
              .bindFlow(httpRoutes.route)
              .onError {
                case t: Throwable =>
                  logger.error(t)("FATAL - failure starting http server").unsafeToFuture()
              }
          }
        }
      } yield ()

      val allStreams = {
        val backLeoOnlyProcesses = {
          implicit val clusterToolToToolDao =
            ToolDAO.clusterToolToToolDao(appDependencies.jupyterDAO,
                                         appDependencies.welderDAO,
                                         appDependencies.rStudioDAO)

          val gceRuntimeMonitor = new GceRuntimeMonitor[IO](
            gceMonitorConfig,
            googleDependencies.googleComputeService,
            googleDependencies.computePollOperation,
            appDependencies.authProvider,
            appDependencies.google2StorageDao,
            appDependencies.publisherQueue,
            gceInterp
          )

          val dataprocRuntimeMonitor =
            new DataprocRuntimeMonitor[IO](
              dataprocMonitorConfig,
              googleDependencies.googleComputeService,
              appDependencies.authProvider,
              appDependencies.google2StorageDao,
              dataprocInterp,
              googleDependencies.googleDataproc
            )

          implicit val cloudServiceRuntimeMonitor: RuntimeMonitor[IO, CloudService] =
            new CloudServiceRuntimeMonitor(gceRuntimeMonitor, dataprocRuntimeMonitor)

          val monitorAtBoot = new MonitorAtBoot[IO](appDependencies.publisherQueue, googleDependencies.errorReporting)

          val googleDiskService = googleDependencies.googleDiskService

          // only needed for backleo
          val asyncTasks = AsyncTaskProcessor(asyncTaskProcessorConfig, appDependencies.asyncTasksQueue)

          val gkeAlg = new GKEInterpreter[IO](
            gkeInterpConfig,
            vpcInterp,
            googleDependencies.gkeService,
            googleDependencies.kubeService,
            appDependencies.helmClient,
            appDependencies.appDAO,
            googleDependencies.credentials,
            googleDependencies.googleIamDAO,
            appDependencies.appDescriptorDAO,
            appDependencies.blocker,
            appDependencies.nodepoolLock
          )

          val pubsubSubscriber =
            new LeoPubsubMessageSubscriber[IO](
              leoPubsubMessageSubscriberConfig,
              appDependencies.subscriber,
              appDependencies.asyncTasksQueue,
              googleDiskService,
              googleDependencies.computePollOperation,
              appDependencies.authProvider,
              gkeAlg,
              googleDependencies.errorReporting
            )

          val autopauseMonitor = AutopauseMonitor(
            autoFreezeConfig,
            appDependencies.jupyterDAO,
            appDependencies.publisherQueue
          )

          val nonLeoMessageSubscriber =
            new NonLeoMessageSubscriber[IO](gkeAlg,
                                            googleDependencies.googleComputeService,
                                            appDependencies.samDAO,
                                            appDependencies.nonLeoMessageGoogleSubscriber,
                                            googleDependencies.cryptoMiningUserPublisher,
                                            appDependencies.asyncTasksQueue)

          List(
            nonLeoMessageSubscriber.process,
            Stream.eval(appDependencies.nonLeoMessageGoogleSubscriber.start),
            asyncTasks.process,
            pubsubSubscriber.process,
            Stream.eval(appDependencies.subscriber.start),
            monitorAtBoot.process, // checks database to see if there's on-going runtime status transition
            autopauseMonitor.process // check database to autopause runtimes periodically
          )
        }

        val frontLeoOnlyProcesses = List(
          dateAccessedUpdater.process, // We only need to update dateAccessed in front leo
          appDependencies.authProvider.recordCacheMetricsProcess,
          appDependencies.samDAO.recordCacheMetricsProcess,
          proxyService.recordGoogleTokenCacheMetricsProcess,
          proxyService.recordSamResourceCacheMetricsProcess,
          appDependencies.runtimeDnsCache.recordCacheMetricsProcess,
          googleDependencies.kubernetesDnsCache.recordCacheMetricsProcess
        )

        val extraProcesses = leoExecutionModeConfig match {
          case LeoExecutionModeConfig.BackLeoOnly  => backLeoOnlyProcesses
          case LeoExecutionModeConfig.FrontLeoOnly => frontLeoOnlyProcesses
          case LeoExecutionModeConfig.Combined     => backLeoOnlyProcesses ++ frontLeoOnlyProcesses
        }

        List(
          appDependencies.leoPublisher.process, //start the publisher queue .dequeue
          Stream.eval[IO, Unit](httpServer) //start http server
        ) ++ extraProcesses
      }

      val app = Stream.emits(allStreams).covary[IO].parJoin(allStreams.length)

      app
        .handleErrorWith(error => Stream.eval(logger.error(error)("Failed to start leonardo")))
        .compile
        .drain
    }
  }

  private def createDependencies[F[_]: StructuredLogger: Parallel: Concurrent: ContextShift: Timer](
    pathToCredentialJson: String
  )(implicit ec: ExecutionContext, as: ActorSystem, F: ConcurrentEffect[F]): Resource[F, AppDependencies[F]] =
    for {
      blockingEc <- ExecutionContexts.cachedThreadPool[F]
      semaphore <- Resource.liftF(Semaphore[F](255L))
      blocker = Blocker.liftExecutionContext(blockingEc)
      storage <- GoogleStorageService.resource[F](pathToCredentialJson, blocker, Some(semaphore))
      retryPolicy = RetryPolicy[F](RetryPolicy.exponentialBackoff(30 seconds, 5))

      sslContext <- Resource.liftF(SslContextReader.getSSLContext())
      httpClientWithCustomSSL <- blaze.BlazeClientBuilder[F](blockingEc, Some(sslContext)).resource
      clientWithRetryWithCustomSSL = Retry(retryPolicy)(httpClientWithCustomSSL)
      clientWithRetryAndLogging = Http4sLogger[F](logHeaders = true, logBody = false)(clientWithRetryWithCustomSSL)

      // This is for sending custom metrics to stackdriver. all custom metrics starts with `OpenCensus/leonardo/`.
      // Typing in `leonardo` in metrics explorer will show all leonardo custom metrics.
      // As best practice, we should have all related metrics under same prefix separated by `/`
      implicit0(openTelemetry: OpenTelemetryMetrics[F]) <- OpenTelemetryMetrics
        .resource[F](applicationConfig.leoServiceAccountJsonFile, applicationConfig.applicationName, blocker)

      // Note the Sam client intentionally doesn't use clientWithRetryAndLogging because the logs are
      // too verbose. We send OpenTelemetry metrics instead for instrumenting Sam calls.
      samDao = HttpSamDAO[F](clientWithRetryWithCustomSSL, httpSamDaoConfig, blocker)
      concurrentDbAccessPermits <- Resource.liftF(Semaphore[F](dbConcurrency))
      implicit0(dbRef: DbReference[F]) <- DbReference.init(liquibaseConfig, concurrentDbAccessPermits, blocker)
      runtimeDnsCache = new RuntimeDnsCache(proxyConfig, dbRef, runtimeDnsCacheConfig, blocker)
      kubernetesDnsCache = new KubernetesDnsCache(proxyConfig, dbRef, kubernetesDnsCacheConfig, blocker)
      welderDao = new HttpWelderDAO[F](runtimeDnsCache, clientWithRetryAndLogging)
      dockerDao = HttpDockerDAO[F](clientWithRetryAndLogging)
      jupyterDao = new HttpJupyterDAO[F](runtimeDnsCache, clientWithRetryAndLogging)
      rstudioDAO = new HttpRStudioDAO(runtimeDnsCache, clientWithRetryAndLogging)
      serviceAccountProvider = new PetClusterServiceAccountProvider(samDao)
      authProvider = new SamAuthProvider(samDao, samAuthConfig, serviceAccountProvider, blocker)

      credential <- credentialResource(pathToCredentialJson)
      scopedCredential = credential.createScoped(Seq(ComputeScopes.COMPUTE).asJava)
      kubernetesScopedCredential = credential.createScoped(Seq(ContainerScopes.CLOUD_PLATFORM).asJava)
      credentialJson <- Resource.liftF(
        readFileToString(applicationConfig.leoServiceAccountJsonFile, blocker)
      )
      json = Json(credentialJson)
      jsonWithServiceAccountUser = Json(credentialJson, Option(googleGroupsConfig.googleAdminEmail))

      petGoogleStorageDAO = (token: String) =>
        new HttpGoogleStorageDAO(applicationConfig.applicationName, Token(() => token), workbenchMetricsBaseName)
      googleIamDAO = new HttpGoogleIamDAO(applicationConfig.applicationName, json, workbenchMetricsBaseName)
      googleDirectoryDAO = new HttpGoogleDirectoryDAO(applicationConfig.applicationName,
                                                      jsonWithServiceAccountUser,
                                                      workbenchMetricsBaseName)
      googleResourceService <- GoogleResourceService.resource[F](Paths.get(pathToCredentialJson), blocker, semaphore)

      googlePublisher <- GooglePublisher.resource[F](publisherConfig)
      cryptoMiningUserPublisher <- GooglePublisher.resource[F](cryptominingTopicPublisherConfig)

      publisherQueue <- Resource.liftF(InspectableQueue.bounded[F, LeoPubsubMessage](pubsubConfig.queueSize))
      dataAccessedUpdater <- Resource.liftF(
        InspectableQueue.bounded[F, UpdateDateAccessMessage](dateAccessUpdaterConfig.queueSize)
      )

      gkeService <- GKEService.resource(Paths.get(pathToCredentialJson), blocker, semaphore)
      kubeService <- org.broadinstitute.dsde.workbench.google2.KubernetesService
        .resource(Paths.get(pathToCredentialJson), gkeService, blocker, semaphore)
      helmClient = new HelmInterpreter[F](blocker, semaphore)
      appDAO = new HttpAppDAO(kubernetesDnsCache, clientWithRetryAndLogging)
      appDescriptorDAO = new HttpAppDescriptorDAO(clientWithRetryAndLogging)

      leoPublisher = new LeoPublisher(publisherQueue, googlePublisher)

      subscriberQueue <- Resource.liftF(InspectableQueue.bounded[F, Event[LeoPubsubMessage]](pubsubConfig.queueSize))
      subscriber <- GoogleSubscriber.resource(subscriberConfig, subscriberQueue)

      nonLeoMessageSubscriberQueue <- Resource.liftF(
        InspectableQueue.bounded[F, Event[NonLeoMessage]](pubsubConfig.queueSize)
      )
      nonLeoMessageSubscriber <- GoogleSubscriber.resource(nonLeoMessageSubscriberConfig, nonLeoMessageSubscriberQueue)

      // Retry 400 responses from Google, as those can occur when resources aren't ready yet
      // (e.g. if the subnet isn't ready when creating an instance).
      googleComputeRetryPolicy = RetryPredicates.retryConfigWithPredicates(
        RetryPredicates.standardRetryPredicate,
        RetryPredicates.whenStatusCode(400)
      )
      googleComputeService <- GoogleComputeService.fromCredential(scopedCredential,
                                                                  blocker,
                                                                  semaphore,
                                                                  googleComputeRetryPolicy)
      dataprocService <- GoogleDataprocService.resource(
        googleComputeService,
        pathToCredentialJson,
        blocker,
        semaphore,
        dataprocConfig.regionName
      )
      asyncTasksQueue <- Resource.liftF(InspectableQueue.bounded[F, Task[F]](asyncTaskProcessorConfig.queueBound))
      _ <- OpenTelemetryMetrics.registerTracing[F](Paths.get(pathToCredentialJson), blocker)
      googleDiskService <- GoogleDiskService.resource(pathToCredentialJson, blocker, semaphore)
      computePollOperation <- ComputePollOperation.resourceFromCredential(scopedCredential, blocker, semaphore)
      errorReporting <- ErrorReporting.fromCredential(scopedCredential,
                                                      applicationConfig.applicationName,
                                                      ProjectName.of(applicationConfig.leoGoogleProject.value))
      googleOauth2DAO <- GoogleOAuth2Service.resource(blocker, semaphore)
      nodepoolLock <- Resource.liftF(
        KeyLock[F, KubernetesClusterId](gkeClusterConfig.nodepoolLockCacheExpiryTime,
                                        gkeClusterConfig.nodepoolLockCacheMaxSize,
                                        blocker)
      )

      googleDependencies = GoogleDependencies(
        petGoogleStorageDAO,
        googleComputeService,
        computePollOperation,
        googleDiskService,
        googleResourceService,
        googleDirectoryDAO,
        cryptoMiningUserPublisher,
        googleIamDAO,
        dataprocService,
        kubernetesDnsCache,
        gkeService,
        kubeService,
        openTelemetry,
        errorReporting,
        kubernetesScopedCredential,
        googleOauth2DAO
      )
    } yield AppDependencies(
      sslContext,
      storage,
      dbRef,
      runtimeDnsCache,
      googleDependencies,
      samDao,
      welderDao,
      dockerDao,
      jupyterDao,
      rstudioDAO,
      serviceAccountProvider,
      authProvider,
      blocker,
      semaphore,
      leoPublisher,
      publisherQueue,
      dataAccessedUpdater,
      subscriber,
      nonLeoMessageSubscriber,
      asyncTasksQueue,
      helmClient,
      appDAO,
      nodepoolLock,
      appDescriptorDAO
    )

  override def run(args: List[String]): IO[ExitCode] = startup().as(ExitCode.Success)
}

final case class GoogleDependencies[F[_]](
  petGoogleStorageDAO: String => GoogleStorageDAO,
  googleComputeService: GoogleComputeService[F],
  computePollOperation: ComputePollOperation[F],
  googleDiskService: GoogleDiskService[F],
  googleResourceService: GoogleResourceService[F],
  googleDirectoryDAO: HttpGoogleDirectoryDAO,
  cryptoMiningUserPublisher: GooglePublisher[F],
  googleIamDAO: HttpGoogleIamDAO,
  googleDataproc: GoogleDataprocService[F],
  kubernetesDnsCache: KubernetesDnsCache[F],
  gkeService: GKEService[F],
  kubeService: org.broadinstitute.dsde.workbench.google2.KubernetesService[F],
  openTelemetryMetrics: OpenTelemetryMetrics[F],
  errorReporting: ErrorReporting[F],
  credentials: GoogleCredentials,
  googleOauth2DAO: GoogleOAuth2Service[F]
)

final case class AppDependencies[F[_]](
  sslContext: SSLContext,
  google2StorageDao: GoogleStorageService[F],
  dbReference: DbReference[F],
  runtimeDnsCache: RuntimeDnsCache[F],
  googleDependencies: GoogleDependencies[F],
  samDAO: HttpSamDAO[F],
  welderDAO: HttpWelderDAO[F],
  dockerDAO: HttpDockerDAO[F],
  jupyterDAO: HttpJupyterDAO[F],
  rStudioDAO: RStudioDAO[F],
  serviceAccountProvider: ServiceAccountProvider[F],
  authProvider: SamAuthProvider[F],
  blocker: Blocker,
  semaphore: Semaphore[F],
  leoPublisher: LeoPublisher[F],
  publisherQueue: fs2.concurrent.InspectableQueue[F, LeoPubsubMessage],
  dateAccessedUpdaterQueue: fs2.concurrent.InspectableQueue[F, UpdateDateAccessMessage],
  subscriber: GoogleSubscriber[F, LeoPubsubMessage],
  nonLeoMessageGoogleSubscriber: GoogleSubscriber[F, NonLeoMessage],
  asyncTasksQueue: InspectableQueue[F, Task[F]],
  helmClient: HelmAlgebra[F],
  appDAO: AppDAO[F],
  nodepoolLock: KeyLock[F, KubernetesClusterId],
  appDescriptorDAO: AppDescriptorDAO[F]
)
