package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import java.time.Instant
import java.util.UUID
import akka.http.scaladsl.model.StatusCodes
import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.Async
import cats.mtl.Ask
import cats.syntax.all._
import io.chrisdavenport.log4cats.StructuredLogger
import org.apache.commons.lang3.RandomStringUtils
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterName, NodepoolName}
import org.broadinstitute.dsde.workbench.google2.KubernetesName
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.NamespaceName
import org.broadinstitute.dsde.workbench.leonardo.AppType.{Custom, Galaxy}
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.config._
import org.broadinstitute.dsde.workbench.leonardo.db._
import org.broadinstitute.dsde.workbench.leonardo.http.service.LeoAppServiceInterp.{
  isPatchVersionDifference,
  LeoKubernetesConfig
}
import org.broadinstitute.dsde.workbench.leonardo.model.SamResourceAction._
import org.broadinstitute.dsde.workbench.leonardo.model.{LeoAuthProvider, ServiceAccountProviderConfig, _}
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage._
import org.broadinstitute.dsde.workbench.leonardo.monitor.{ClusterNodepoolAction, LeoPubsubMessage}
import org.broadinstitute.dsde.workbench.leonardo.http.service.AppService
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{TraceId, UserInfo, WorkbenchEmail}
import org.broadinstitute.dsp.{ChartVersion, Release}

import scala.concurrent.ExecutionContext

final class LeoAppServiceInterp[F[_]: Parallel](
  protected val authProvider: LeoAuthProvider[F],
  protected val serviceAccountProvider: ServiceAccountProvider[F],
  protected val leoKubernetesConfig: LeoKubernetesConfig,
  protected val publisherQueue: fs2.concurrent.Queue[F, LeoPubsubMessage]
)(
  implicit F: Async[F],
  log: StructuredLogger[F],
  dbReference: DbReference[F],
  ec: ExecutionContext
) extends AppService[F] {

  override def createApp(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    appName: AppName,
    req: CreateAppRequest
  )(
    implicit as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- as.ask
      hasPermission <- authProvider.hasPermission(ProjectSamResourceId(googleProject),
                                                  ProjectAction.CreateApp,
                                                  userInfo)
      _ <- if (hasPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))

      appOpt <- KubernetesServiceDbQueries.getActiveFullAppByName(googleProject, appName).transaction
      _ <- appOpt.fold(F.unit)(c =>
        F.raiseError[Unit](AppAlreadyExistsException(googleProject, appName, c.app.status, ctx.traceId))
      )

      samResourceId <- F.delay(AppSamResourceId(UUID.randomUUID().toString))
      _ <- authProvider
        .notifyResourceCreated(samResourceId, userInfo.userEmail, googleProject)
        .handleErrorWith { t =>
          log.error(t)(
            s"[${ctx.traceId}] Failed to notify the AuthProvider for creation of kubernetes app ${googleProject.value} / ${appName.value}"
          ) >> F.raiseError(t)
        }

      saveCluster <- F.fromEither(getSavableCluster(userInfo, googleProject, ctx.now, None))
      saveClusterResult <- KubernetesServiceDbQueries.saveOrGetClusterForApp(saveCluster).transaction
      // TODO Remove the block below to allow app creation on a new cluster when the existing cluster is in Error status
      _ <- if (saveClusterResult.minimalCluster.status == KubernetesClusterStatus.Error)
        F.raiseError[Unit](
          KubernetesAppCreationException(
            s"You cannot create an app while a cluster is in status ${saveClusterResult.minimalCluster.status}"
          )
        )
      else F.unit

      clusterId = saveClusterResult.minimalCluster.id

      //We want to know if the user already has an app to re-use that nodepool
      userNodepoolOpt <- nodepoolQuery.getMinimalByUser(userInfo.userEmail, googleProject).transaction

      //A claimed nodepool is either the user's existing nodepool, or a pre-created one in that order of precedence
      claimedNodepoolOpt <- if (userNodepoolOpt.isDefined) F.pure(userNodepoolOpt)
      else nodepoolQuery.claimNodepool(clusterId, userInfo.userEmail).transaction

      nodepool <- claimedNodepoolOpt match {
        case None =>
          for {
            saveNodepool <- F.fromEither(getUserNodepool(clusterId, userInfo, req.kubernetesRuntimeConfig, ctx.now))
            savedNodepool <- nodepoolQuery.saveForCluster(saveNodepool).transaction
          } yield savedNodepool
        case Some(n) =>
          log.info(s"claimed nodepool ${n.id} in project ${saveClusterResult.minimalCluster.googleProject}") >> F.pure(
            n
          )
      }

      runtimeServiceAccountOpt <- serviceAccountProvider
        .getClusterServiceAccount(userInfo, googleProject)
      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Done Sam call for getClusterServiceAccount")))
      petSA <- F.fromEither(
        runtimeServiceAccountOpt.toRight(new Exception(s"user ${userInfo.userEmail.value} doesn't have a PET SA"))
      )

      diskResultOpt <- req.diskConfig.traverse(diskReq =>
        RuntimeServiceInterp.processPersistentDiskRequest(
          diskReq,
          googleProject,
          userInfo,
          petSA,
          if (req.appType == AppType.Galaxy) FormattedBy.Galaxy else FormattedBy.Custom,
          authProvider,
          leoKubernetesConfig.diskConfig
        )
      )

      lastUsedApp <- diskResultOpt.flatTraverse { diskResult =>
        if (diskResult.creationNeeded) F.pure(none[LastUsedApp])
        else {
          (diskResult.disk.formattedBy, diskResult.disk.galaxyRestore) match {
            case (Some(FormattedBy.Galaxy), Some(galaxyRestore)) =>
              for {
                lastUsedOpt <- appQuery.getLastUsedApp(galaxyRestore.lastUsedBy, Some(ctx.traceId)).transaction
                lastUsed <- F.fromOption(
                  lastUsedOpt,
                  new LeoException(s"last used app(${galaxyRestore.lastUsedBy}) not found", traceId = Some(ctx.traceId))
                )
                _ <- req.customEnvironmentVariables.get(WORKSPACE_NAME_KEY).traverse { s =>
                  if (lastUsed.workspace.asString == s)
                    F.unit
                  else
                    F.raiseError[Unit](
                      BadRequestException(
                        s"workspace name has to be the same as last used app in order to restore data from existing disk",
                        Some(ctx.traceId)
                      )
                    )
                }
              } yield lastUsed.some
            case (Some(FormattedBy.Galaxy), None) =>
              F.raiseError[Option[LastUsedApp]](
                new LeoException("Existing disk found, but no restore info found in DB", traceId = Some(ctx.traceId))
              )
            case (Some(FormattedBy.GCE), _) | (Some(FormattedBy.Custom), _) =>
              F.raiseError[Option[LastUsedApp]](
                new LeoException(
                  s"Disk is formatted by ${diskResult.disk.formattedBy.get} already, cannot be used for galaxy app",
                  traceId = Some(ctx.traceId)
                )
              )
            case (None, _) =>
              F.raiseError[Option[LastUsedApp]](
                new LeoException(
                  "Disk is not formatted yet. Only disks previously used by galaxy app can be re-used to create a new galaxy app",
                  traceId = Some(ctx.traceId)
                )
              )
          }
        }
      }
      saveApp <- F.fromEither(
        getSavableApp(googleProject,
                      appName,
                      userInfo,
                      samResourceId,
                      req,
                      diskResultOpt.map(_.disk),
                      lastUsedApp,
                      petSA,
                      nodepool.id,
                      ctx)
      )
      app <- appQuery.save(saveApp).transaction

      clusterNodepoolAction = saveClusterResult match {
        case ClusterExists(_) =>
          // If we're claiming a pre-existing nodepool then don't specify CreateNodepool in the pubsub message
          if (claimedNodepoolOpt.isDefined) None else Some(ClusterNodepoolAction.CreateNodepool(nodepool.id))
        case ClusterDoesNotExist(c, n) => Some(ClusterNodepoolAction.CreateClusterAndNodepool(c.id, n.id, nodepool.id))
      }
      createAppMessage = CreateAppMessage(
        googleProject,
        clusterNodepoolAction,
        app.id,
        app.appName,
        diskResultOpt.flatMap(d => if (d.creationNeeded) Some(d.disk.id) else None),
        req.customEnvironmentVariables,
        req.appType,
        app.appResources.namespace.name,
        Some(ctx.traceId)
      )
      _ <- publisherQueue.enqueue1(createAppMessage)
    } yield ()

  override def getApp(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    appName: AppName
  )(
    implicit as: Ask[F, AppContext]
  ): F[GetAppResponse] =
    for {
      ctx <- as.ask
      appOpt <- KubernetesServiceDbQueries.getActiveFullAppByName(googleProject, appName).transaction
      app <- F.fromOption(appOpt, AppNotFoundException(googleProject, appName, ctx.traceId))

      hasPermission <- authProvider.hasPermission(app.app.samResourceId, AppAction.GetAppStatus, userInfo)
      _ <- if (hasPermission) F.unit
      else
        log.info(s"User ${userInfo} tried to access app ${appName.value} without proper permissions. Returning 404") >> F
          .raiseError[Unit](AppNotFoundException(googleProject, appName, ctx.traceId))
    } yield GetAppResponse.fromDbResult(app, Config.proxyConfig.proxyUrlBase)

  override def listApp(
    userInfo: UserInfo,
    googleProject: Option[GoogleProject],
    params: Map[String, String]
  )(implicit as: Ask[F, AppContext]): F[Vector[ListAppResponse]] =
    for {
      params <- F.fromEither(processListParameters(params))
      allClusters <- KubernetesServiceDbQueries.listFullApps(googleProject, params._1, params._2).transaction
      samResources = allClusters.flatMap(_.nodepools.flatMap(_.apps.map(_.samResourceId)))
      samVisibleAppsOpt <- NonEmptyList.fromList(samResources).traverse { apps =>
        authProvider.filterUserVisible(apps, userInfo)
      }
      res = samVisibleAppsOpt match {
        case None => Vector.empty
        case Some(samVisibleApps) =>
          val samVisibleAppsSet = samVisibleApps.toSet
          // we construct this list of clusters by first filtering apps the user doesn't have permissions to see
          // then we build back up by filtering nodepools without apps and clusters without nodepools
          allClusters
            .map { c =>
              c.copy(nodepools =
                c.nodepools
                  .map { n =>
                    n.copy(apps = n.apps.filter { a =>
                      // Making the assumption that users will always be able to access apps that they create
                      // Fix for https://github.com/DataBiosphere/leonardo/issues/821
                      samVisibleAppsSet
                        .contains(a.samResourceId) || a.auditInfo.creator == userInfo.userEmail
                    })
                  }
                  .filterNot(_.apps.isEmpty)
              )
            }
            .filterNot(_.nodepools.isEmpty)
            .flatMap(c => ListAppResponse.fromCluster(c, Config.proxyConfig.proxyUrlBase))
            .toVector
      }
    } yield res

  override def deleteApp(request: DeleteAppRequest)(
    implicit as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- as.ask
      appOpt <- KubernetesServiceDbQueries.getActiveFullAppByName(request.googleProject, request.appName).transaction
      appResult <- F.fromOption(appOpt, AppNotFoundException(request.googleProject, request.appName, ctx.traceId))

      listOfPermissions <- authProvider.getActions(appResult.app.samResourceId, request.userInfo)

      // throw 404 if no GetAppStatus permission
      hasReadPermission = listOfPermissions.toSet.contains(AppAction.GetAppStatus)
      _ <- if (hasReadPermission) F.unit
      else F.raiseError[Unit](AppNotFoundException(request.googleProject, request.appName, ctx.traceId))

      // throw 403 if no DeleteApp permission
      hasDeletePermission = listOfPermissions.toSet.contains(AppAction.DeleteApp)
      _ <- if (hasDeletePermission) F.unit else F.raiseError[Unit](ForbiddenError(request.userInfo.userEmail))

      canDelete = AppStatus.deletableStatuses.contains(appResult.app.status)
      _ <- if (canDelete) F.unit
      else
        F.raiseError[Unit](
          AppCannotBeDeletedException(request.googleProject, request.appName, appResult.app.status, ctx.traceId)
        )

      diskOpt <- if (request.deleteDisk)
        appResult.app.appResources.disk.fold(
          F.raiseError[Option[DiskId]](
            NoDiskForAppException(appResult.cluster.googleProject, appResult.app.appName, ctx.traceId)
          )
        )(d => F.pure(Some(d.id)))
      else F.pure[Option[DiskId]](None)

      // If the app status is Error, we can assume that the underlying app/nodepool
      // has already been deleted. So we just transition the app to Deleted status
      // without sending a message to Back Leo.
      //
      // Note this has the side effect of not deleting the disk if requested to do so. The
      // caller must manually delete the disk in this situation. We have the same behavior for
      // runtimes.
      _ <- if (appResult.app.status == AppStatus.Error) {
        for {
          _ <- authProvider.notifyResourceDeleted(appResult.app.samResourceId,
                                                  appResult.app.auditInfo.creator,
                                                  appResult.cluster.googleProject)
          _ <- appQuery.markAsDeleted(appResult.app.id, ctx.now).transaction
        } yield ()
      } else {
        for {
          _ <- KubernetesServiceDbQueries.markPreDeleting(appResult.nodepool.id, appResult.app.id).transaction
          deleteMessage = DeleteAppMessage(
            appResult.app.id,
            appResult.app.appName,
            appResult.cluster.googleProject,
            diskOpt,
            Some(ctx.traceId)
          )
          _ <- publisherQueue.enqueue1(deleteMessage)
        } yield ()
      }
    } yield ()

  override def batchNodepoolCreate(userInfo: UserInfo, googleProject: GoogleProject, req: BatchNodepoolCreateRequest)(
    implicit ev: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- ev.ask
      hasPermission <- authProvider.hasPermission(ProjectSamResourceId(googleProject),
                                                  ProjectAction.CreateApp,
                                                  userInfo)
      _ <- if (hasPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))

      // create default nodepool with size dependant on number of nodes requested
      saveCluster <- F.fromEither(
        getSavableCluster(userInfo, googleProject, ctx.now, Some(req.numNodepools), req.clusterName)
      )
      saveClusterResult <- KubernetesServiceDbQueries.saveOrGetClusterForApp(saveCluster).transaction

      // check if the cluster exists (we reject this request if it does)
      clusterId <- saveClusterResult match {
        case _: ClusterExists         => F.raiseError[KubernetesClusterLeoId](ClusterExistsException(googleProject))
        case res: ClusterDoesNotExist => F.pure(res.minimalCluster.id)
      }
      // create list of Precreating nodepools
      eitherNodepoolsOrError = List.tabulate(req.numNodepools.value) { _ =>
        getUserNodepool(clusterId,
                        userInfo.copy(userEmail = WorkbenchEmail("nouserassigned@gmail.com")),
                        req.kubernetesRuntimeConfig,
                        ctx.now)
      }
      nodepools <- eitherNodepoolsOrError.traverse(n => F.fromEither(n))
      _ <- nodepoolQuery.saveAllForCluster(nodepools).transaction
      dbNodepools <- KubernetesServiceDbQueries.getAllNodepoolsForCluster(clusterId).transaction
      allNodepoolIds = dbNodepools.map(_.id)
      msg = BatchNodepoolCreateMessage(clusterId, allNodepoolIds, googleProject, Some(ctx.traceId))
      _ <- publisherQueue.enqueue1(msg)
    } yield ()

  def stopApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- as.ask
      appOpt <- KubernetesServiceDbQueries.getActiveFullAppByName(googleProject, appName).transaction
      appResult <- F.fromOption(appOpt, AppNotFoundException(googleProject, appName, ctx.traceId))

      listOfPermissions <- authProvider.getActions(appResult.app.samResourceId, userInfo)

      // throw 404 if no StopStartApp permission
      hasReadPermission = listOfPermissions.toSet.contains(AppAction.StopApp)
      _ <- if (hasReadPermission) F.unit
      else F.raiseError[Unit](AppNotFoundException(googleProject, appName, ctx.traceId))

      // throw 403 if no StopStartApp permission
      hasStopStartPermission = listOfPermissions.toSet.contains(AppAction.StopApp)
      _ <- if (hasStopStartPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))

      canStop = AppStatus.stoppableStatuses.contains(appResult.app.status)
      _ <- if (canStop) F.unit
      else
        F.raiseError[Unit](
          AppCannotBeStoppedException(googleProject, appName, appResult.app.status, ctx.traceId)
        )

      _ <- appQuery.updateStatus(appResult.app.id, AppStatus.PreStopping).transaction
      message = StopAppMessage(
        appResult.app.id,
        appResult.app.appName,
        appResult.cluster.googleProject,
        Some(ctx.traceId)
      )
      _ <- publisherQueue.enqueue1(message)
    } yield ()

  def startApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- as.ask
      appOpt <- KubernetesServiceDbQueries.getActiveFullAppByName(googleProject, appName).transaction
      appResult <- F.fromOption(appOpt, AppNotFoundException(googleProject, appName, ctx.traceId))

      listOfPermissions <- authProvider.getActions(appResult.app.samResourceId, userInfo)

      // throw 404 if no StopStartApp permission
      hasReadPermission = listOfPermissions.toSet.contains(AppAction.StartApp)
      _ <- if (hasReadPermission) F.unit
      else F.raiseError[Unit](AppNotFoundException(googleProject, appName, ctx.traceId))

      // throw 403 if no StopStartApp permission
      hasStopStartPermission = listOfPermissions.toSet.contains(AppAction.StartApp)
      _ <- if (hasStopStartPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))

      canStop = AppStatus.startableStatuses.contains(appResult.app.status)
      _ <- if (canStop) F.unit
      else
        F.raiseError[Unit](
          AppCannotBeStartedException(googleProject, appName, appResult.app.status, ctx.traceId)
        )

      _ <- appQuery.updateStatus(appResult.app.id, AppStatus.PreStarting).transaction
      message = StartAppMessage(
        appResult.app.id,
        appResult.app.appName,
        appResult.cluster.googleProject,
        Some(ctx.traceId)
      )
      _ <- publisherQueue.enqueue1(message)
    } yield ()

  private[service] def getSavableCluster(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    now: Instant,
    numNodepools: Option[NumNodepools],
    clusterName: Option[KubernetesClusterName] = None
  ): Either[Throwable, SaveKubernetesCluster] = {
    val auditInfo = AuditInfo(userInfo.userEmail, now, None, now)

    val defaultNodepool = for {
      nodepoolName <- KubernetesNameUtils.getUniqueName(NodepoolName.apply)
    } yield DefaultNodepool(
      NodepoolLeoId(-1),
      clusterId = KubernetesClusterLeoId(-1),
      nodepoolName,
      status = NodepoolStatus.Precreating,
      auditInfo,
      machineType = leoKubernetesConfig.nodepoolConfig.defaultNodepoolConfig.machineType,
      numNodes = numNodepools
        .map(n =>
          NumNodes(
            math
              .ceil(
                n.value.toDouble /
                  leoKubernetesConfig.nodepoolConfig.defaultNodepoolConfig.maxNodepoolsPerDefaultNode.value.toDouble
              )
              .toInt
          )
        )
        .getOrElse(leoKubernetesConfig.nodepoolConfig.defaultNodepoolConfig.numNodes),
      autoscalingEnabled = leoKubernetesConfig.nodepoolConfig.defaultNodepoolConfig.autoscalingEnabled,
      autoscalingConfig = None
    )

    for {
      nodepool <- defaultNodepool
      defaultClusterName <- KubernetesNameUtils.getUniqueName(KubernetesClusterName.apply)
    } yield SaveKubernetesCluster(
      googleProject = googleProject,
      clusterName = clusterName.getOrElse(defaultClusterName),
      location = leoKubernetesConfig.clusterConfig.location,
      region = leoKubernetesConfig.clusterConfig.region,
      status = KubernetesClusterStatus.Precreating,
      ingressChart = leoKubernetesConfig.ingressConfig.chart,
      auditInfo = auditInfo,
      defaultNodepool = nodepool
    )
  }

  private[service] def getUserNodepool(clusterId: KubernetesClusterLeoId,
                                       userInfo: UserInfo,
                                       runtimeConfig: Option[KubernetesRuntimeConfig],
                                       now: Instant): Either[Throwable, Nodepool] = {
    val auditInfo = AuditInfo(userInfo.userEmail, now, None, now)

    val machineConfig = runtimeConfig.getOrElse(
      KubernetesRuntimeConfig(
        leoKubernetesConfig.nodepoolConfig.galaxyNodepoolConfig.numNodes,
        leoKubernetesConfig.nodepoolConfig.galaxyNodepoolConfig.machineType,
        leoKubernetesConfig.nodepoolConfig.galaxyNodepoolConfig.autoscalingEnabled
      )
    )

    for {
      nodepoolName <- KubernetesNameUtils.getUniqueName(NodepoolName.apply)
    } yield Nodepool(
      NodepoolLeoId(-1),
      clusterId = clusterId,
      nodepoolName,
      status = NodepoolStatus.Precreating,
      auditInfo,
      machineType = machineConfig.machineType,
      numNodes = machineConfig.numNodes,
      autoscalingEnabled = machineConfig.autoscalingEnabled,
      autoscalingConfig = Some(leoKubernetesConfig.nodepoolConfig.galaxyNodepoolConfig.autoscalingConfig),
      List.empty,
      false
    )
  }

  private[service] def getSavableApp(googleProject: GoogleProject,
                                     appName: AppName,
                                     userInfo: UserInfo,
                                     samResourceId: AppSamResourceId,
                                     req: CreateAppRequest,
                                     diskOpt: Option[PersistentDisk],
                                     lastUsedApp: Option[LastUsedApp],
                                     googleServiceAccount: WorkbenchEmail,
                                     nodepoolId: NodepoolLeoId,
                                     ctx: AppContext): Either[Throwable, SaveApp] = {
    val now = ctx.now
    val auditInfo = AuditInfo(userInfo.userEmail, now, None, now)
    val galaxyConfig = leoKubernetesConfig.galaxyAppConfig

    val allLabels =
      DefaultKubernetesLabels(googleProject,
                              appName,
                              userInfo.userEmail,
                              leoKubernetesConfig.serviceAccountConfig.leoServiceAccountEmail).toMap ++ req.labels
    for {
      // check the labels do not contain forbidden keys
      labels <- if (allLabels.contains(includeDeletedKey))
        Left(IllegalLabelKeyException(includeDeletedKey))
      else
        Right(allLabels)

      // TODO make this non optional in the request
      // the original thought when developing was that galaxy needs a disk, but some apps may not
      // all apps require a disk
      disk <- diskOpt.toRight(AppRequiresDiskException(googleProject, appName, req.appType, ctx.traceId))

      // Generate namespace and app release names using a random 6-character string prefix.
      //
      // Theoretically release names should only need to be unique within a namespace, but the Galaxy
      // installation requires that release names be unique within a cluster.
      //
      // Note these names must adhere to Kubernetes naming requirements; additionally the release
      // name is used by the Galaxy chart templates to generate longer strings like preload-cache-cvmfs-gxy-main-<release>
      // which also need to be valid Kubernetes names.
      //
      // Previously we used appName as the prefix instead of a 6-character uid, but thought it is better to
      // decouple this string from UI-generated Leo app names because of hidden k8s/Galaxy constraints.
      //
      // There are DB constraints to handle potential name collisions.
      uid = s"${RandomStringUtils.randomAlphabetic(1)}${RandomStringUtils.randomAlphanumeric(5)}".toLowerCase
      namespaceId = lastUsedApp.fold(
        NamespaceId(-1)
      )(app => app.namespaceId)
      namespaceName <- lastUsedApp.fold(
        KubernetesName.withValidation(
          s"${uid}-${galaxyConfig.namespaceNameSuffix}",
          NamespaceName.apply
        )
      )(app => app.namespaceName.asRight[Throwable])

      chart = lastUsedApp
        .map { lastUsed =>
          // if there's a patch version bump, then we use the later version defined in leo config; otherwise, we use lastUsed chart definition
          if (lastUsed.chart.name == galaxyConfig.chartName && isPatchVersionDifference(lastUsed.chart.version,
                                                                                        galaxyConfig.chartVersion))
            galaxyConfig.chart
          else lastUsed.chart
        }
        .getOrElse(galaxyConfig.chart)
      release <- lastUsedApp.fold(
        KubernetesName.withValidation(
          s"${uid}-${galaxyConfig.releaseNameSuffix}",
          Release.apply
        )
      )(app => app.release.asRight[Throwable])
    } yield SaveApp(
      App(
        AppId(-1),
        nodepoolId,
        req.appType,
        appName,
        AppStatus.Precreating,
        chart,
        release,
        samResourceId,
        googleServiceAccount,
        auditInfo,
        labels,
        AppResources(
          Namespace(
            namespaceId,
            namespaceName
          ),
          Some(disk),
          req.appType match {
            case Galaxy =>
              galaxyConfig.services.map(config => KubernetesService(ServiceId(-1), config))
            case Custom =>
              // Back Leo will populate services when it parses the descriptor
              List.empty
          },
          Option.empty
        ),
        List.empty,
        req.customEnvironmentVariables,
        req.descriptorPath,
        req.extraArgs
      )
    )
  }

}

object LeoAppServiceInterp {
  case class LeoKubernetesConfig(serviceAccountConfig: ServiceAccountProviderConfig,
                                 clusterConfig: KubernetesClusterConfig,
                                 nodepoolConfig: NodepoolConfig,
                                 ingressConfig: KubernetesIngressConfig,
                                 galaxyAppConfig: GalaxyAppConfig,
                                 diskConfig: PersistentDiskConfig)

  private[http] def isPatchVersionDifference(a: ChartVersion, b: ChartVersion): Boolean = {
    val aSplited = a.asString.split("\\.")
    val bSplited = b.asString.split("\\.")
    aSplited(0) == bSplited(0) && aSplited(1) == bSplited(1)
  }
}
case class AppNotFoundException(googleProject: GoogleProject, appName: AppName, traceId: TraceId)
    extends LeoException(
      s"App ${googleProject.value}/${appName.value} not found.",
      StatusCodes.NotFound,
      traceId = Some(traceId)
    )

case class AppAlreadyExistsException(googleProject: GoogleProject,
                                     appName: AppName,
                                     status: AppStatus,
                                     traceId: TraceId)
    extends LeoException(
      s"App ${googleProject.value}/${appName.value} already exists in ${status.toString} status.",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

case class AppCannotBeDeletedException(googleProject: GoogleProject,
                                       appName: AppName,
                                       status: AppStatus,
                                       traceId: TraceId)
    extends LeoException(
      s"App ${googleProject.value}/${appName.value} cannot be deleted in ${status} status." +
        (if (status == AppStatus.Stopped) " Please start the app first." else ""),
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

case class NoDiskForAppException(googleProject: GoogleProject, appName: AppName, traceId: TraceId)
    extends LeoException(
      s"Specified delete disk for app ${googleProject.value}/${appName.value}, but this app does not have a disk.",
      StatusCodes.BadRequest,
      traceId = Some(traceId)
    )

case class AppCannotBeCreatedException(googleProject: GoogleProject,
                                       appName: AppName,
                                       status: AppStatus,
                                       traceId: TraceId)
    extends LeoException(
      s"App ${googleProject.value}/${appName.value} cannot be deleted in ${status} status.",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

case class AppRequiresDiskException(googleProject: GoogleProject, appName: AppName, appType: AppType, traceId: TraceId)
    extends LeoException(
      s"App ${googleProject.value}/${appName.value} cannot be created because the request does not contain a valid disk. Apps of type ${appType} require a disk. Trace ID: ${traceId.asString}",
      StatusCodes.BadRequest,
      traceId = Some(traceId)
    )

case class ClusterExistsException(googleProject: GoogleProject)
    extends LeoException(
      s"Cannot pre-create nodepools for project $googleProject because a cluster already exists",
      StatusCodes.Conflict,
      traceId = None
    )

case class AppCannotBeStoppedException(googleProject: GoogleProject,
                                       appName: AppName,
                                       status: AppStatus,
                                       traceId: TraceId)
    extends LeoException(
      s"App ${googleProject.value}/${appName.value} cannot be stopped in ${status} status. Trace ID: ${traceId.asString}",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

case class AppCannotBeStartedException(googleProject: GoogleProject,
                                       appName: AppName,
                                       status: AppStatus,
                                       traceId: TraceId)
    extends LeoException(
      s"App ${googleProject.value}/${appName.value} cannot be started in ${status} status. Trace ID: ${traceId.asString}",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )
