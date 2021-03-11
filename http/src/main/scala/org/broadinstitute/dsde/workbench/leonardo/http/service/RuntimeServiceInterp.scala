package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.mtl.Ask
import com.google.auth.oauth2.{AccessToken, GoogleCredentials}
import com.google.cloud.BaseServiceException
import io.chrisdavenport.log4cats.StructuredLogger
import org.broadinstitute.dsde.workbench.google2.util.RetryPredicates
import org.broadinstitute.dsde.workbench.google2.{
  ComputePollOperation,
  DiskName,
  GcsBlobName,
  GoogleComputeService,
  GoogleStorageService,
  InstanceName,
  MachineTypeName,
  OperationName,
  PollError
}
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.RuntimeImageType.{CryptoDetector, Jupyter, Proxy, Welder}
import org.broadinstitute.dsde.workbench.leonardo.config._
import org.broadinstitute.dsde.workbench.leonardo.dao.DockerDAO
import org.broadinstitute.dsde.workbench.leonardo.db._
import org.broadinstitute.dsde.workbench.leonardo.http.api.ListRuntimeResponse2
import org.broadinstitute.dsde.workbench.leonardo.http.service.RuntimeServiceInterp._
import org.broadinstitute.dsde.workbench.leonardo.model._
import com.rms.miu.slickcats.DBIOInstances._
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.leonardo.model.SamResourceAction._
import org.broadinstitute.dsde.workbench.leonardo.monitor.{
  DiskUpdate,
  LeoPubsubMessage,
  RuntimeConfigInCreateRuntimeMessage,
  RuntimePatchDetails
}
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{google, TraceId, UserInfo, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import slick.dbio.DBIOAction

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class RuntimeServiceInterp[F[_]: Parallel](config: RuntimeServiceConfig,
                                           diskConfig: PersistentDiskConfig,
                                           authProvider: LeoAuthProvider[F],
                                           serviceAccountProvider: ServiceAccountProvider[F],
                                           dockerDAO: DockerDAO[F],
                                           googleStorageService: GoogleStorageService[F],
                                           googleComputeService: GoogleComputeService[F],
                                           computePollOperation: ComputePollOperation[F],
                                           publisherQueue: fs2.concurrent.Queue[F, LeoPubsubMessage])(
  implicit F: Async[F],
  log: StructuredLogger[F],
  dbReference: DbReference[F],
  ec: ExecutionContext,
  metrics: OpenTelemetryMetrics[F]
) extends RuntimeService[F] {

  override def createRuntime(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    runtimeName: RuntimeName,
    req: CreateRuntime2Request
  )(implicit as: Ask[F, AppContext]): F[Unit] =
    for {
      context <- as.ask
      hasPermission <- authProvider.hasPermission(ProjectSamResourceId(googleProject),
                                                  ProjectAction.CreateRuntime,
                                                  userInfo)
      _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done Sam call for cluster permission")))
      _ <- if (hasPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))
      // Grab the service accounts from serviceAccountProvider for use later
      runtimeServiceAccountOpt <- serviceAccountProvider
        .getClusterServiceAccount(userInfo, googleProject)
      _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done Sam call for getClusterServiceAccount")))
      petSA <- F.fromEither(
        runtimeServiceAccountOpt.toRight(new Exception(s"user ${userInfo.userEmail.value} doesn't have a PET SA"))
      )

      runtimeOpt <- RuntimeServiceDbQueries.getStatusByName(googleProject, runtimeName).transaction
      _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done DB query for active cluster")))
      _ <- runtimeOpt match {
        case Some(status) => F.raiseError[Unit](RuntimeAlreadyExistsException(googleProject, runtimeName, status))
        case None =>
          for {
            samResource <- F.delay(RuntimeSamResourceId(UUID.randomUUID().toString))
            petToken <- serviceAccountProvider.getAccessToken(userInfo.userEmail, googleProject).recoverWith {
              case e =>
                log.warn(e)(
                  s"Could not acquire pet service account access token for user ${userInfo.userEmail.value} in project $googleProject. " +
                    s"Skipping validation of bucket objects in the runtime request."
                ) as None
            }
            _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done Sam getAccessToken")))
            runtimeImages <- getRuntimeImages(petToken, context.now, req.toolDockerImage, req.welderRegistry)
            _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done get runtime images")))
            // .get here should be okay since this is from config, and it should always be defined; Ideally we probaly should use a different type for reading this config than RuntimeConfig
            bootDiskSize = config.gceConfig.runtimeConfigDefaults.bootDiskSize.get

            defaultRuntimeConfig = RuntimeConfigInCreateRuntimeMessage.GceConfig(
              config.gceConfig.runtimeConfigDefaults.machineType,
              config.gceConfig.runtimeConfigDefaults.diskSize,
              bootDiskSize
            )
            runtimeConfig <- req.runtimeConfig
              .fold[F[RuntimeConfigInCreateRuntimeMessage]](F.pure(defaultRuntimeConfig)) { // default to gce if no runtime specific config is provided
                c =>
                  c match {
                    case gce: RuntimeConfigRequest.GceConfig =>
                      F.pure(
                        RuntimeConfigInCreateRuntimeMessage.GceConfig(
                          gce.machineType.getOrElse(config.gceConfig.runtimeConfigDefaults.machineType),
                          gce.diskSize.getOrElse(config.gceConfig.runtimeConfigDefaults.diskSize),
                          bootDiskSize
                        ): RuntimeConfigInCreateRuntimeMessage
                      )
                    case dataproc: RuntimeConfigRequest.DataprocConfig =>
                      F.pure(
                        RuntimeConfigInCreateRuntimeMessage
                          .fromDataprocInRuntimeConfigRequest(dataproc, config.dataprocConfig.runtimeConfigDefaults): RuntimeConfigInCreateRuntimeMessage
                      )
                    case gce: RuntimeConfigRequest.GceWithPdConfig =>
                      RuntimeServiceInterp
                        .processPersistentDiskRequest(gce.persistentDisk,
                                                      googleProject,
                                                      userInfo,
                                                      petSA,
                                                      FormattedBy.GCE,
                                                      authProvider,
                                                      diskConfig)
                        .map(diskResult =>
                          RuntimeConfigInCreateRuntimeMessage.GceWithPdConfig(
                            gce.machineType.getOrElse(config.gceConfig.runtimeConfigDefaults.machineType),
                            diskResult.disk.id,
                            bootDiskSize
                          ): RuntimeConfigInCreateRuntimeMessage
                        )
                  }
              }
            runtime = convertToRuntime(userInfo,
                                       petSA,
                                       googleProject,
                                       runtimeName,
                                       samResource,
                                       runtimeImages,
                                       config,
                                       req,
                                       context.now)

            userScriptUriToValidate = req.jupyterUserScriptUri
              .flatMap(x => UserScriptPath.gcsPrism.getOption(x).map(_.asString))
            userStartupScriptToValidate = req.jupyterStartUserScriptUri.flatMap(x =>
              UserScriptPath.gcsPrism.getOption(x).map(_.asString)
            )

            gcsObjectUrisToValidate = runtime.userJupyterExtensionConfig
              .map(config =>
                (config.nbExtensions.values ++ config.serverExtensions.values ++ config.combinedExtensions.values)
                  .filter(_.startsWith("gs://"))
                  .toList
              )
              .getOrElse(List.empty[String]) ++ userScriptUriToValidate ++ userStartupScriptToValidate

            _ <- petToken.traverse(t =>
              gcsObjectUrisToValidate
                .parTraverse(s => validateBucketObjectUri(userInfo.userEmail, t, s, context.traceId))
            )
            _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done validating buckets")))
            _ <- authProvider
              .notifyResourceCreated(samResource, userInfo.userEmail, googleProject)
              .handleErrorWith { t =>
                log.error(t)(
                  s"[${context.traceId}] Failed to notify the AuthProvider for creation of runtime ${runtime.projectNameString}"
                ) >> F.raiseError(t)
              }
            _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done Sam notifyClusterCreated")))
            runtimeConfigToSave = LeoLenses.runtimeConfigPrism.reverseGet(runtimeConfig)
            saveRuntime = SaveCluster(cluster = runtime, runtimeConfig = runtimeConfigToSave, now = context.now)
            runtime <- clusterQuery.save(saveRuntime).transaction
            _ <- publisherQueue.enqueue1(
              CreateRuntimeMessage.fromRuntime(runtime, runtimeConfig, Some(context.traceId))
            )
          } yield ()
      }
    } yield ()

  override def getRuntime(userInfo: UserInfo, googleProject: GoogleProject, runtimeName: RuntimeName)(
    implicit as: Ask[F, AppContext]
  ): F[GetRuntimeResponse] =
    for {
      // throws 404 if not existent
      resp <- RuntimeServiceDbQueries.getRuntime(googleProject, runtimeName).transaction
      // throw 404 if no GetClusterStatus permission
      hasPermission <- authProvider.hasPermissionWithProjectFallback(resp.samResource,
                                                                     RuntimeAction.GetRuntimeStatus,
                                                                     ProjectAction.GetRuntimeStatus,
                                                                     userInfo,
                                                                     googleProject)
      _ <- if (hasPermission) F.unit
      else F.raiseError[Unit](RuntimeNotFoundException(googleProject, runtimeName, "permission denied"))
    } yield resp

  override def listRuntimes(userInfo: UserInfo, googleProject: Option[GoogleProject], params: Map[String, String])(
    implicit as: Ask[F, AppContext]
  ): F[Vector[ListRuntimeResponse2]] =
    for {
      ctx <- as.ask
      paramMap <- F.fromEither(processListParameters(params))
      runtimes <- RuntimeServiceDbQueries.listRuntimes(paramMap._1, paramMap._2, googleProject).transaction
      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("DB | Done listRuntime db query")))
      runtimesAndProjects = runtimes.map(r => (r.googleProject, r.samResource))
      samVisibleRuntimesOpt <- NonEmptyList.fromList(runtimesAndProjects).traverse { rs =>
        authProvider
          .filterUserVisibleWithProjectFallback(
            rs,
            userInfo
          )
      }
      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Sam | Done visible runtimes")))
      res = samVisibleRuntimesOpt match {
        case None => Vector.empty
        case Some(samVisibleRuntimes) =>
          val samVisibleRuntimesSet = samVisibleRuntimes.toSet
          // Making the assumption that users will always be able to access runtimes that they create
          // Fix for https://github.com/DataBiosphere/leonardo/issues/821
          runtimes
            .filter(c =>
              c.auditInfo.creator == userInfo.userEmail || samVisibleRuntimesSet
                .contains((c.googleProject, c.samResource))
            )
            .toVector
      }
    } yield res

  override def deleteRuntime(req: DeleteRuntimeRequest)(
    implicit ev: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- ev.ask
      // throw 404 if not existent
      runtimeOpt <- clusterQuery.getActiveClusterByNameMinimal(req.googleProject, req.runtimeName).transaction
      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("DB | Done getActiveClusterByNameMinimal")))
      runtime <- runtimeOpt.fold(
        F.raiseError[Runtime](RuntimeNotFoundException(req.googleProject, req.runtimeName, "no record in database"))
      )(F.pure)
      // throw 404 if no GetClusterStatus permission
      // Note: the general pattern is to 404 (e.g. pretend the runtime doesn't exist) if the caller doesn't have
      // GetClusterStatus permission. We return 403 if the user can view the runtime but can't perform some other action.
      listOfPermissions <- authProvider.getActionsWithProjectFallback(runtime.samResource,
                                                                      runtime.googleProject,
                                                                      req.userInfo)
      hasStatusPermission = listOfPermissions._1.toSet.contains(RuntimeAction.GetRuntimeStatus) ||
        listOfPermissions._2.contains(ProjectAction.GetRuntimeStatus)

      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Sam | Done get list of allowed actions")))

      _ <- if (hasStatusPermission) F.unit
      else
        F.raiseError[Unit](
          RuntimeNotFoundException(req.googleProject, req.runtimeName, "no active runtime record in database")
        )

      // throw 403 if no DeleteCluster permission
      hasDeletePermission = listOfPermissions._1.toSet.contains(RuntimeAction.DeleteRuntime) ||
        listOfPermissions._2.contains(ProjectAction.DeleteRuntime)

      _ <- if (hasDeletePermission) F.unit else F.raiseError[Unit](ForbiddenError(req.userInfo.userEmail))
      // throw 409 if the cluster is not deletable
      _ <- if (runtime.status.isDeletable) F.unit
      else
        F.raiseError[Unit](RuntimeCannotBeDeletedException(runtime.googleProject, runtime.runtimeName, runtime.status))
      // delete the runtime
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(runtime.runtimeConfigId).transaction
      persistentDiskToDelete <- runtimeConfig match {
        case x: RuntimeConfig.GceWithPdConfig =>
          x.persistentDiskId.flatTraverse[F, DiskId] { diskId =>
            for {
              diskOpt <- persistentDiskQuery.getPersistentDiskRecord(diskId).transaction
              disk <- F.fromEither(
                diskOpt.toRight(new RuntimeException(s"Can't find ${diskId} in PERSISTENT_DISK table"))
              )
              detachOp <- googleComputeService.detachDisk(req.googleProject,
                                                          disk.zone,
                                                          InstanceName(runtime.runtimeName.asString),
                                                          config.gceConfig.userDiskDeviceName)
              _ <- detachOp.traverse(op =>
                (
                  computePollOperation
                    .pollZoneOperation(
                      req.googleProject,
                      disk.zone,
                      OperationName(op.getName),
                      3 seconds,
                      5,
                      None
                    )(
                      F.unit,
                      F.raiseError(
                        new RuntimeException(
                          s"Fail to detach ${disk.name} from ${runtime.runtimeName} in a timely manner"
                        )
                      ),
                      F.unit
                    )
                  )
                  .recoverWith {
                    case e: PollError =>
                      if (e.operation.getHttpErrorStatusCode == 400) {
                        log.info(
                          s"Detach Disk ${disk.name} failed with 400 Error. Continuing deleting Runtime ${runtime.runtimeName}"
                        )
                      } else F.raiseError(e)

                  }
              )

              _ <- RuntimeConfigQueries.updatePersistentDiskId(runtime.runtimeConfigId, None, ctx.now).transaction
              res <- if (req.deleteDisk)
                persistentDiskQuery.updateStatus(diskId, DiskStatus.Deleting, ctx.now).transaction.as(Some(diskId))
              else F.pure(none[DiskId])
            } yield res
          }
        case _ => F.pure(none[DiskId])
      }

      // If there are no async runtime fields defined, we can assume that the underlying runtime
      // has already been deleted. So we just transition the runtime to Deleted status without
      // sending a message to Back Leo.
      //
      // Note this has the side effect of not deleting the disk if requested to do so. The
      // caller must manually delete the disk in this situation. We have the same behavior for apps.
      _ <- if (runtime.asyncRuntimeFields.isDefined) {
        clusterQuery.updateClusterStatus(runtime.id, RuntimeStatus.PreDeleting, ctx.now).transaction >> publisherQueue
          .enqueue1(
            DeleteRuntimeMessage(runtime.id, persistentDiskToDelete, Some(ctx.traceId))
          )
      } else {
        clusterQuery.completeDeletion(runtime.id, ctx.now).transaction.void >> authProvider.notifyResourceDeleted(
          runtime.samResource,
          runtime.auditInfo.creator,
          runtime.googleProject
        )
      }
    } yield ()

  def stopRuntime(userInfo: UserInfo, googleProject: GoogleProject, runtimeName: RuntimeName)(
    implicit as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- as.ask
      // throw 404 if not existent
      runtimeOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Finish query for active runtime")))
      runtime <- runtimeOpt.fold(
        F.raiseError[Runtime](
          RuntimeNotFoundException(googleProject, runtimeName, "no active runtime found in database")
        )
      )(F.pure)
      // throw 404 if no GetClusterStatus permission
      // Note: the general pattern is to 404 (e.g. pretend the runtime doesn't exist) if the caller doesn't have
      // GetClusterStatus permission. We return 403 if the user can view the runtime but can't perform some other action.

      listOfPermissions <- authProvider.getActionsWithProjectFallback(runtime.samResource, googleProject, userInfo)

      hasStatusPermission = listOfPermissions._1.toSet.contains(RuntimeAction.GetRuntimeStatus) ||
        listOfPermissions._2.contains(ProjectAction.GetRuntimeStatus)

      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Sam | Done get list of allowed actions")))

      _ <- if (hasStatusPermission) F.unit
      else
        F.raiseError[Unit](
          RuntimeNotFoundException(googleProject,
                                   runtimeName,
                                   "GetRuntimeStatus permission is required for stopRuntime")
        )

      // throw 403 if no StopStartCluster permission
      hasStopPermission = listOfPermissions._1.toSet.contains(RuntimeAction.StopStartRuntime) ||
        listOfPermissions._2.contains(ProjectAction.StopStartRuntime)

      _ <- if (hasStopPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))
      // throw 409 if the cluster is not stoppable
      _ <- if (runtime.status.isStoppable) F.unit
      else
        F.raiseError[Unit](RuntimeCannotBeStoppedException(runtime.googleProject, runtime.runtimeName, runtime.status))
      // stop the runtime
      _ <- clusterQuery.updateClusterStatus(runtime.id, RuntimeStatus.PreStopping, ctx.now).transaction
      _ <- publisherQueue.enqueue1(StopRuntimeMessage(runtime.id, Some(ctx.traceId)))
    } yield ()

  def startRuntime(userInfo: UserInfo, googleProject: GoogleProject, runtimeName: RuntimeName)(
    implicit as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- as.ask
      // throw 404 if not existent
      runtimeOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Done query for active runtime")))
      runtime <- runtimeOpt.fold(
        F.raiseError[Runtime](RuntimeNotFoundException(googleProject, runtimeName, "no record in database"))
      )(F.pure)
      // throw 404 if no GetClusterStatus permission
      // Note: the general pattern is to 404 (e.g. pretend the runtime doesn't exist) if the caller doesn't have
      // GetClusterStatus permission. We return 403 if the user can view the runtime but can't perform some other action.

      listOfPermissions <- authProvider.getActionsWithProjectFallback(runtime.samResource, googleProject, userInfo)

      hasStatusPermission = listOfPermissions._1.toSet.contains(RuntimeAction.GetRuntimeStatus) ||
        listOfPermissions._2.contains(ProjectAction.GetRuntimeStatus)

      _ <- if (hasStatusPermission) F.unit
      else
        F.raiseError[Unit](
          RuntimeNotFoundException(googleProject, runtimeName, "GetRuntimeStatus permission is required")
        )

      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Sam | Done get list of allowed actions")))

      hasStartPermission = listOfPermissions._1.toSet.contains(RuntimeAction.StopStartRuntime) ||
        listOfPermissions._2.toSet.contains(ProjectAction.StopStartRuntime)

      // throw 403 if no StopStartCluster permission
      _ <- if (hasStartPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))

      // throw 409 if the cluster is not startable
      _ <- if (runtime.status.isStartable) F.unit
      else
        F.raiseError[Unit](RuntimeCannotBeStartedException(runtime.googleProject, runtime.runtimeName, runtime.status))
      // start the runtime
      ctx <- as.ask
      _ <- clusterQuery.updateClusterStatus(runtime.id, RuntimeStatus.PreStarting, ctx.now).transaction
      _ <- publisherQueue.enqueue1(StartRuntimeMessage(runtime.id, Some(ctx.traceId)))
    } yield ()

  override def updateRuntime(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    runtimeName: RuntimeName,
    req: UpdateRuntimeRequest
  )(implicit as: Ask[F, AppContext]): F[Unit] =
    for {
      ctx <- as.ask
      // throw 404 if not existent
      runtimeOpt <- clusterQuery.getActiveClusterRecordByName(googleProject, runtimeName).transaction
      runtime <- runtimeOpt.fold(
        F.raiseError[ClusterRecord](RuntimeNotFoundException(googleProject, runtimeName, "no record in database"))
      )(F.pure)
      // throw 404 if no GetClusterStatus permission
      // Note: the general pattern is to 404 (e.g. pretend the runtime doesn't exist) if the caller doesn't have
      // GetClusterStatus permission. We return 403 if the user can view the runtime but can't perform some other action.

      listOfPermissions <- authProvider.getActionsWithProjectFallback(RuntimeSamResourceId(runtime.internalId),
                                                                      googleProject,
                                                                      userInfo)

      hasStatusPermission = listOfPermissions._1.toSet.contains(RuntimeAction.GetRuntimeStatus) ||
        listOfPermissions._2.contains(ProjectAction.GetRuntimeStatus)

      _ <- if (hasStatusPermission) F.unit
      else
        F.raiseError[Unit](
          RuntimeNotFoundException(googleProject,
                                   runtimeName,
                                   "GetRuntimeStatus permission is required for update runtime")
        )

      // throw 403 if no ModifyCluster permission
      hasModifyPermission = listOfPermissions._1.toSet.contains(RuntimeAction.ModifyRuntime)

      _ <- if (hasModifyPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))
      // throw 409 if the cluster is not updatable
      _ <- if (runtime.status.isUpdatable) F.unit
      else
        F.raiseError[Unit](RuntimeCannotBeUpdatedException(runtime.projectNameString, runtime.status))
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(runtime.runtimeConfigId).transaction
      // Updating autopause is just a DB update, so we can do it here instead of sending a PubSub message
      updatedAutopauseThreshold = calculateAutopauseThreshold(req.updateAutopauseEnabled,
                                                              req.updateAutopauseThreshold.map(_.toMinutes.toInt),
                                                              config.autoFreezeConfig)
      _ <- if (updatedAutopauseThreshold != runtime.autopauseThreshold)
        clusterQuery.updateAutopauseThreshold(runtime.id, updatedAutopauseThreshold, ctx.now).transaction.void
      else Async[F].unit

      _ <- DBIOAction
        .seq(
          labelQuery.deleteForResource(runtime.id, LabelResourceType.runtime, req.labelsToDelete),
          req.labelsToUpsert.toList.traverse_ {
            case (k, v) =>
              labelQuery.save(runtime.id, LabelResourceType.runtime, k, v)
          }
        )
        .transaction

      // Updating the runtime config will potentially generate a PubSub message
      _ <- req.updatedRuntimeConfig.traverse_(update =>
        processUpdateRuntimeConfigRequest(update, req.allowStop, runtime, runtimeConfig)
      )
    } yield ()

  private[service] def getRuntimeImages(
    petToken: Option[String],
    now: Instant,
    toolDockerImage: Option[ContainerImage],
    welderRegistry: Option[ContainerRegistry]
  )(implicit ev: Ask[F, TraceId]): F[Set[RuntimeImage]] =
    for {
      // Try to autodetect the image
      autodetectedImageOpt <- toolDockerImage.traverse(image =>
        dockerDAO.detectTool(image, petToken).map(t => RuntimeImage(t, image.imageUrl, now))
      )
      // Figure out the tool image. Rules:
      // - if we were able to autodetect an image, use that
      // - else use the default jupyter image
      defaultJupyterImage = RuntimeImage(Jupyter, config.imageConfig.jupyterImage.imageUrl, now)
      toolImage = autodetectedImageOpt getOrElse defaultJupyterImage
      // Figure out the welder image. Rules:
      // - If present, we will use the client-supplied image.
      // - Otherwise we will pull the latest from the specified welderRegistry.
      // - If welderRegistry is undefined, we take the default GCR image from config.
      // - If the tool is RStudio do not include a welder image
      welderImage = toolImage.imageType match {
        case RuntimeImageType.RStudio => None
        case _ =>
          Some(
            RuntimeImage(
              Welder,
              welderRegistry match {
                case Some(ContainerRegistry.DockerHub) => config.imageConfig.welderDockerHubImage.imageUrl
                case _                                 => config.imageConfig.welderGcrImage.imageUrl
              },
              now
            )
          )
      }

      // Get the proxy image
      proxyImage = RuntimeImage(Proxy, config.imageConfig.proxyImage.imageUrl, now)
      // Crypto detector image - note it's not currently supported on Dockerhub
      cryptoDetectorImageOpt = welderRegistry match {
        case Some(ContainerRegistry.DockerHub) => None
        case _                                 => Some(RuntimeImage(CryptoDetector, config.imageConfig.cryptoDetectorImage.imageUrl, now))
      }
    } yield Set(Some(toolImage), welderImage, Some(proxyImage), cryptoDetectorImageOpt).flatten

  private[service] def validateBucketObjectUri(userEmail: WorkbenchEmail,
                                               userToken: String,
                                               gcsUri: String,
                                               traceId: TraceId): F[Unit] = {
    val gcsUriOpt = google.parseGcsPath(gcsUri)
    gcsUriOpt match {
      case Left(e) => F.raiseError(BucketObjectException(gcsUri, e.value))
      case Right(gcsPath) if gcsPath.toUri.length > bucketPathMaxLength =>
        F.raiseError(BucketObjectException(gcsUri, s"bucket path longer than ${bucketPathMaxLength} is prohibited"))
      case Right(gcsPath) =>
        // Retry 401s from Google here because they can be thrown spuriously with valid credentials.
        // See https://github.com/DataBiosphere/leonardo/issues/460
        // Note GoogleStorageDAO already retries 500 and other errors internally, so we just need to catch 401s here.
        // We might think about moving the retry-on-401 logic inside GoogleStorageDAO.
        val accessToken = new AccessToken(userToken, null) // we currently don't get expiration time from sam
        val credentials = GoogleCredentials.create(accessToken)
        val retryPolicy = RetryPredicates.retryConfigWithPredicates(
          RetryPredicates.standardRetryPredicate,
          RetryPredicates.whenStatusCode(401)
        )

        val res = for {
          blob <- googleStorageService
            .getBlob(gcsPath.bucketName,
                     GcsBlobName(gcsPath.objectName.value),
                     credential = Some(credentials),
                     Some(traceId),
                     retryPolicy)
            .compile
            .last
          _ <- if (blob.isDefined) F.unit
          else F.raiseError[Unit](BucketObjectException(gcsUri, "bucket doesn't exist in Google"))
        } yield ()

        res.recoverWith {
          case e: BaseServiceException if e.getCode == StatusCodes.Forbidden.intValue =>
            log.error(e)(
              s"User ${userEmail.value}'s PET account does not have access to ${gcsPath.bucketName} / ${gcsPath.objectName}"
            ) >> F.raiseError(BucketObjectAccessException(userEmail, gcsPath))
          case e: BaseServiceException if e.getCode == 401 =>
            log.warn(e)(s"Could not validate object [${gcsUri}] as user [${userEmail.value}]")
        }
    }
  }

  /**
   * This method validates an UpdateRuntimeConfigRequest against the current state of the runtime,
   * and potentially sends a PubSub message (or throws an error). The PubSub receiver does not
   * do validation; it is assumed all validation happens here.
   */
  private[service] def processUpdateRuntimeConfigRequest(
    request: UpdateRuntimeConfigRequest,
    allowStop: Boolean,
    runtime: ClusterRecord,
    runtimeConfig: RuntimeConfig
  )(implicit ctx: Ask[F, AppContext]): F[Unit] =
    for {
      context <- ctx.ask
      msg <- (runtimeConfig, request) match {
        case (RuntimeConfig.GceConfig(machineType, existngDiskSize, _),
              UpdateRuntimeConfigRequest.GceConfig(newMachineType, diskSizeInRequest)) =>
          for {
            targetDiskSize <- traverseIfChanged(diskSizeInRequest, existngDiskSize) { d =>
              if (d.gb < existngDiskSize.gb)
                Async[F]
                  .raiseError[DiskUpdate.NoPdSizeUpdate](
                    RuntimeDiskSizeCannotBeDecreasedException(runtime.projectNameString)
                  )
              else
                Async[F].pure(DiskUpdate.NoPdSizeUpdate(d))
            }
            r <- processUpdateGceConfigRequest(newMachineType,
                                               allowStop,
                                               runtime,
                                               machineType,
                                               targetDiskSize,
                                               context.traceId)
          } yield r
        case (RuntimeConfig.GceWithPdConfig(machineType, diskIdOpt, _),
              UpdateRuntimeConfigRequest.GceConfig(newMachineType, diskSizeInRequest)) =>
          for {
            // should disk size be updated?
            diskId <- F.fromEither(
              diskIdOpt.toRight(RuntimeDiskNotFound(runtime.googleProject, runtime.runtimeName, context.traceId))
            )
            diskOpt <- persistentDiskQuery.getById(diskId).transaction
            disk <- F.fromEither(diskOpt.toRight(DiskNotFoundByIdException(diskId, context.traceId)))
            diskUpdate <- traverseIfChanged(diskSizeInRequest, disk.size) { d =>
              if (d.gb < disk.size.gb)
                Async[F].raiseError[DiskUpdate](RuntimeDiskSizeCannotBeDecreasedException(runtime.projectNameString))
              else if (!allowStop)
                Async[F].raiseError[DiskUpdate](RuntimeDiskSizeCannotBeChangedException(runtime.projectNameString))
              else
                Async[F].pure(DiskUpdate.PdSizeUpdate(disk.id, disk.name, d): DiskUpdate)
            }
            r <- processUpdateGceConfigRequest(newMachineType,
                                               allowStop,
                                               runtime,
                                               machineType,
                                               diskUpdate,
                                               context.traceId)
          } yield r
        case (dataprocConfig @ RuntimeConfig.DataprocConfig(_, _, _, _, _, _, _, _),
              req @ UpdateRuntimeConfigRequest.DataprocConfig(_, _, _, _)) =>
          processUpdateDataprocConfigRequest(req, allowStop, runtime, dataprocConfig)

        case _ =>
          Async[F].raiseError[Option[UpdateRuntimeMessage]](
            WrongCloudServiceException(runtimeConfig.cloudService, request.cloudService, context.traceId)
          )
      }
      _ <- msg.traverse_ { m =>
        if (m.stopToUpdateMachineType) {
          val patchDetails = RuntimePatchDetails(runtime.id, RuntimeStatus.Stopped)
          patchQuery.save(patchDetails, m.newMachineType).transaction.void >> metrics.incrementCounter(
            "patchStopToUpdate"
          )
        } else F.unit
      }
    } yield ()

  private[service] def processUpdateGceConfigRequest(newMachineType: Option[MachineTypeName],
                                                     allowStop: Boolean,
                                                     runtime: ClusterRecord,
                                                     existingMachineType: MachineTypeName,
                                                     targetDiskSize: Option[DiskUpdate],
                                                     traceId: TraceId): F[Option[UpdateRuntimeMessage]] =
    for {
      // should machine type be updated?
      targetMachineType <- getTargetMachineType(existingMachineType,
                                                newMachineType,
                                                runtime.projectNameString,
                                                runtime.status,
                                                allowStop)
      // if either of the above is defined, send a PubSub message
      msg <- (targetMachineType orElse targetDiskSize).traverse { _ =>
        val requiresRestart = targetMachineType.exists(x => x._2) || targetDiskSize.isDefined

        val message = UpdateRuntimeMessage(runtime.id,
                                           targetMachineType.map(_._1),
                                           requiresRestart,
                                           targetDiskSize,
                                           None,
                                           None,
                                           Some(traceId))
        publisherQueue.enqueue1(message).as(message)
      }
      // we only need to return the message that might cause a start/stop transition
    } yield msg

  private[service] def processUpdateDataprocConfigRequest(
    req: UpdateRuntimeConfigRequest.DataprocConfig,
    allowStop: Boolean,
    runtime: ClusterRecord,
    dataprocConfig: RuntimeConfig.DataprocConfig
  )(implicit ctx: Ask[F, AppContext]): F[Option[UpdateRuntimeMessage]] =
    for {
      context <- ctx.ask
      // should num workers be updated?
      targetNumWorkers <- traverseIfChanged(req.updatedNumberOfWorkers, dataprocConfig.numberOfWorkers)(Async[F].pure)
      _ <- targetNumWorkers.traverse(_ =>
        runtime.status match {
          case RuntimeStatus.Running =>
            F.unit
          case s =>
            F.raiseError[Unit](
              new LeoException(
                s"Bad request. Number of workers can only be updated if the dataproc cluster is Running. Cluster is in ${s} currently",
                StatusCodes.BadRequest,
                traceId = Some(context.traceId)
              )
            )
        }
      )
      // should num preemptibles be updated?
      targetNumPreemptibles <- traverseIfChanged(req.updatedNumberOfPreemptibleWorkers,
                                                 dataprocConfig.numberOfPreemptibleWorkers.getOrElse(0))(Async[F].pure)
      // should master machine type be updated?
      targetMasterMachineType <- traverseIfChanged(req.updatedMasterMachineType, dataprocConfig.masterMachineType) {
        mt =>
          if (targetNumWorkers.isDefined || targetNumPreemptibles.isDefined)
            Async[F].raiseError[(MachineTypeName, Boolean)](
              RuntimeCannotBeUpdatedException(
                runtime.projectNameString,
                runtime.status,
                "You cannot update the CPUs/Memory and the number of workers at the same time. We recommend you do this one at a time. The number of workers will be updated."
              )
            )
          else if (runtime.status == RuntimeStatus.Stopped)
            Async[F].pure((mt, false))
          else if (!allowStop)
            Async[F].raiseError[(MachineTypeName, Boolean)](
              RuntimeMachineTypeCannotBeChangedException(runtime.projectNameString, runtime.status)
            )
          else Async[F].pure((mt, true))
      }
      masterInstance <- instanceQuery.getMasterForCluster(runtime.id).transaction
      // should master disk size be updated?
      targetMasterDiskSize <- traverseIfChanged(req.updatedMasterDiskSize, dataprocConfig.masterDiskSize) { d =>
        if (d.gb < dataprocConfig.masterDiskSize.gb)
          Async[F].raiseError[DiskUpdate](RuntimeDiskSizeCannotBeDecreasedException(runtime.projectNameString))
        else
          Async[F].pure(
            DiskUpdate.Dataproc(d, masterInstance): DiskUpdate
          )
      }
      // if any of the above is defined, send a PubSub message
      msg <- (targetNumWorkers orElse targetNumPreemptibles orElse targetMasterMachineType orElse targetMasterDiskSize)
        .traverse { _ =>
          val requiresRestart = targetMasterMachineType.exists(x => x._2) || targetMasterDiskSize.isDefined
          val message = UpdateRuntimeMessage(
            runtime.id,
            targetMasterMachineType.map(_._1),
            requiresRestart,
            targetMasterDiskSize,
            targetNumWorkers,
            targetNumPreemptibles,
            Some(context.traceId)
          )
          publisherQueue.enqueue1(message).as(message)
        }
    } yield msg

  private[service] def getTargetMachineType(curMachineType: MachineTypeName,
                                            reqMachineType: Option[MachineTypeName],
                                            projectNameString: String,
                                            status: RuntimeStatus,
                                            allowStop: Boolean) =
    for {
      targetMachineType <- traverseIfChanged(reqMachineType, curMachineType) { mt =>
        if (status == RuntimeStatus.Stopped)
          Async[F].pure((mt, false))
        else if (!allowStop)
          Async[F].raiseError[(MachineTypeName, Boolean)](
            RuntimeMachineTypeCannotBeChangedException(projectNameString, status)
          )
        else Async[F].pure((mt, true))
      }
    } yield targetMachineType
}

object RuntimeServiceInterp {
  private def convertToRuntime(userInfo: UserInfo,
                               serviceAccountInfo: WorkbenchEmail,
                               googleProject: GoogleProject,
                               runtimeName: RuntimeName,
                               clusterInternalId: RuntimeSamResourceId,
                               clusterImages: Set[RuntimeImage],
                               config: RuntimeServiceConfig,
                               req: CreateRuntime2Request,
                               now: Instant): Runtime = {
    // create a LabelMap of default labels
    val defaultLabels = DefaultRuntimeLabels(
      runtimeName,
      googleProject,
      userInfo.userEmail,
      serviceAccountInfo,
      req.jupyterUserScriptUri,
      req.jupyterStartUserScriptUri,
      clusterImages.map(_.imageType).filterNot(_ == Welder).headOption
    ).toMap

    // combine default and given labels and add labels for extensions
    val allLabels = req.labels ++ defaultLabels ++ req.userJupyterExtensionConfig.map(_.asLabels).getOrElse(Map.empty)

    val autopauseThreshold = calculateAutopauseThreshold(
      req.autopause,
      req.autopauseThreshold.map(_.toMinutes.toInt),
      config.autoFreezeConfig
    ) //TODO: use FiniteDuration for autopauseThreshold field in Cluster
    val clusterScopes = req.runtimeConfig match {
      case Some(rq) =>
        rq.cloudService match {
          case CloudService.GCE =>
            if (req.scopes.isEmpty) config.gceConfig.defaultScopes else req.scopes
          case CloudService.Dataproc =>
            if (req.scopes.isEmpty) config.dataprocConfig.defaultScopes else req.scopes
        }
      case None =>
        if (req.scopes.isEmpty) config.gceConfig.defaultScopes
        else req.scopes //default to create gce runtime if runtimeConfig is not specified
    }

    val welderEnabled = clusterImages.map(_.imageType).contains(Welder)

    Runtime(
      0,
      samResource = clusterInternalId,
      runtimeName = runtimeName,
      googleProject = googleProject,
      serviceAccount = serviceAccountInfo,
      asyncRuntimeFields = None,
      auditInfo = AuditInfo(userInfo.userEmail, now, None, now),
      kernelFoundBusyDate = None,
      proxyUrl = Runtime.getProxyUrl(config.proxyUrlBase, googleProject, runtimeName, clusterImages, allLabels),
      status = RuntimeStatus.PreCreating,
      labels = allLabels,
      jupyterUserScriptUri = req.jupyterUserScriptUri,
      jupyterStartUserScriptUri = req.jupyterStartUserScriptUri,
      errors = List.empty,
      dataprocInstances = Set.empty,
      userJupyterExtensionConfig = req.userJupyterExtensionConfig,
      autopauseThreshold = autopauseThreshold,
      defaultClientId = req.defaultClientId,
      runtimeImages = clusterImages,
      scopes = clusterScopes,
      welderEnabled = welderEnabled,
      customEnvironmentVariables = req.customEnvironmentVariables,
      allowStop = false, //TODO: double check this should be false when cluster is created
      runtimeConfigId = RuntimeConfigId(-1),
      patchInProgress = false
    )
  }

  def traverseIfChanged[F[_]: Async, A, B](testVal: Option[A], existingVal: A)(fn: A => F[B]): F[Option[B]] =
    testVal.flatTraverse { x =>
      if (x != existingVal)
        fn(x).map(_.some)
      else
        Async[F].pure(none[B])
    }

  def processPersistentDiskRequest[F[_]](
    req: PersistentDiskRequest,
    googleProject: GoogleProject,
    userInfo: UserInfo,
    serviceAccount: WorkbenchEmail,
    willBeUsedBy: FormattedBy,
    authProvider: LeoAuthProvider[F],
    diskConfig: PersistentDiskConfig
  )(implicit as: Ask[F, AppContext],
    F: Async[F],
    dbReference: DbReference[F],
    ec: ExecutionContext,
    log: StructuredLogger[F]): F[PersistentDiskRequestResult] =
    for {
      ctx <- as.ask
      diskOpt <- persistentDiskQuery.getActiveByName(googleProject, req.name).transaction
      disk <- diskOpt match {
        case Some(pd) =>
          for {
            isAttached <- pd.formattedBy match {
              // TODO: it seems like this can be refactored to follow the below format
              //case Some(x) => if x is willBeUsedBy, check if its attached
              //                  else already formatted exception
              // case None => isAttached
              case None =>
                for {
                  isAttachedToRuntime <- RuntimeConfigQueries.isDiskAttached(pd.id).transaction
                  isAttached <- if (isAttachedToRuntime) F.pure(true)
                  else appQuery.isDiskAttached(pd.id).transaction
                } yield isAttached
              case Some(FormattedBy.Galaxy) =>
                if (willBeUsedBy == FormattedBy.Galaxy) //TODO: If we support more apps, we need to update this check
                  appQuery.isDiskAttached(pd.id).transaction
                else
                  F.raiseError[Boolean](
                    DiskAlreadyFormattedByOtherApp(googleProject, req.name, ctx.traceId, FormattedBy.Galaxy)
                  )
              case Some(FormattedBy.GCE) =>
                if (willBeUsedBy == FormattedBy.GCE)
                  RuntimeConfigQueries.isDiskAttached(pd.id).transaction
                else
                  F.raiseError[Boolean](
                    DiskAlreadyFormattedByOtherApp(googleProject, req.name, ctx.traceId, FormattedBy.GCE)
                  )
              case Some(FormattedBy.Custom) =>
                if (willBeUsedBy == FormattedBy.Custom)
                  RuntimeConfigQueries.isDiskAttached(pd.id).transaction
                else
                  F.raiseError[Boolean](
                    DiskAlreadyFormattedByOtherApp(googleProject, req.name, ctx.traceId, FormattedBy.GCE)
                  )
            }
            // throw 409 if the disk is attached to a runtime
            _ <- if (isAttached)
              F.raiseError[Unit](DiskAlreadyAttachedException(googleProject, req.name, ctx.traceId))
            else F.unit
            hasPermission <- authProvider.hasPermission(pd.samResource,
                                                        PersistentDiskAction.AttachPersistentDisk,
                                                        userInfo)

            _ <- if (hasPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))
          } yield PersistentDiskRequestResult(pd, false)

        case None =>
          for {
            hasPermission <- authProvider.hasPermission(ProjectSamResourceId(googleProject),
                                                        ProjectAction.CreatePersistentDisk,
                                                        userInfo)
            _ <- if (hasPermission) F.unit else F.raiseError[Unit](ForbiddenError(userInfo.userEmail))
            samResource <- F.delay(PersistentDiskSamResourceId(UUID.randomUUID().toString))
            diskBeforeSave <- F.fromEither(
              DiskServiceInterp.convertToDisk(
                userInfo,
                serviceAccount,
                googleProject,
                req.name,
                samResource,
                diskConfig,
                CreateDiskRequest.fromDiskConfigRequest(req),
                ctx.now,
                if (willBeUsedBy == FormattedBy.Galaxy) true else false
              )
            )
            _ <- authProvider
              .notifyResourceCreated(samResource, userInfo.userEmail, googleProject)
              .handleErrorWith { t =>
                log.error(t)(
                  s"[${ctx.traceId}] Failed to notify the AuthProvider for creation of persistent disk ${diskBeforeSave.projectNameString}"
                ) >> F.raiseError(t)
              }
            pd <- persistentDiskQuery.save(diskBeforeSave).transaction
          } yield PersistentDiskRequestResult(pd, true)
      }
    } yield disk

  private[service] def calculateAutopauseThreshold(autopause: Option[Boolean],
                                                   autopauseThreshold: Option[Int],
                                                   autoFreezeConfig: AutoFreezeConfig): Int =
    autopause match {
      case None =>
        autoFreezeConfig.autoFreezeAfter.toMinutes.toInt
      case Some(false) =>
        autoPauseOffValue
      case _ =>
        if (autopauseThreshold.isEmpty) autoFreezeConfig.autoFreezeAfter.toMinutes.toInt
        else Math.max(autoPauseOffValue, autopauseThreshold.get)
    }
}

final case class PersistentDiskRequestResult(disk: PersistentDisk, creationNeeded: Boolean)

final case class RuntimeServiceConfig(proxyUrlBase: String,
                                      imageConfig: ImageConfig,
                                      autoFreezeConfig: AutoFreezeConfig,
                                      dataprocConfig: DataprocConfig,
                                      gceConfig: GceConfig)

final case class WrongCloudServiceException(runtimeCloudService: CloudService,
                                            updateCloudService: CloudService,
                                            traceId: TraceId)
    extends LeoException(
      s"Bad request. This runtime is created with ${runtimeCloudService.asString}, and can not be updated to use ${updateCloudService.asString}",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

// thrown when a runtime has a GceWithPdConfig but has no PD attached, which should only happen for deleted runtimes
final case class RuntimeDiskNotFound(googleProject: GoogleProject, runtimeName: RuntimeName, traceId: TraceId)
    extends LeoException(
      s"Persistent disk not found for runtime ${googleProject.value}/${runtimeName.asString}",
      StatusCodes.NotFound,
      traceId = Some(traceId)
    )

final case class DiskNotSupportedException(traceId: TraceId)
    extends LeoException(
      s"Persistent disks are not supported on Google Cloud Dataproc",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

final case class DiskAlreadyAttachedException(googleProject: GoogleProject, name: DiskName, traceId: TraceId)
    extends LeoException(
      s"Persistent disk ${googleProject.value}/${name.value} is already attached to another runtime",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

final case class DiskAlreadyFormattedByOtherApp(googleProject: GoogleProject,
                                                name: DiskName,
                                                traceId: TraceId,
                                                formattedBy: FormattedBy)
    extends LeoException(
      s"Persistent disk ${googleProject.value}/${name.value} is already formatted by ${formattedBy.asString}",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )
