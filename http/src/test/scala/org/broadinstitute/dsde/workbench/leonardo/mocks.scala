package org.broadinstitute.dsde.workbench.leonardo

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import cats.mtl.Ask
import com.google.auth.Credentials
import com.google.cloud.compute.v1.Operation
import com.google.cloud.storage.Blob
import com.google.cloud.storage.Storage.BucketSourceOption
import fs2.Stream
import io.circe.{Decoder, Encoder}
import io.kubernetes.client.openapi.models.{V1ObjectMeta, V1PersistentVolumeClaim}
import org.broadinstitute.dsde.workbench.RetryConfig
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.google2.KubernetesModels.{KubernetesNamespace, KubernetesPodStatus, PodStatus}
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.PodName
import org.broadinstitute.dsde.workbench.google2.mock.BaseFakeGoogleStorage
import org.broadinstitute.dsde.workbench.google2.{
  Event,
  GKEModels,
  GcsBlobName,
  GetMetadataResponse,
  GoogleSubscriber,
  KubernetesModels,
  PvName
}
import org.broadinstitute.dsde.workbench.leonardo.model.{
  LeoAuthProvider,
  SamResource,
  SamResourceAction,
  ServiceAccountProvider
}
import org.broadinstitute.dsde.workbench.leonardo.util._
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}
import org.broadinstitute.dsde.workbench.model.{TraceId, UserInfo, WorkbenchEmail}
import org.broadinstitute.dsp.Release
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock

object FakeGoogleStorageService extends BaseFakeGoogleStorage {
  override def getObjectMetadata(bucketName: GcsBucketName,
                                 blobName: GcsBlobName,
                                 traceId: Option[TraceId],
                                 retryConfig: RetryConfig): fs2.Stream[IO, GetMetadataResponse] =
    fs2.Stream.empty.covary[IO]

  override def getBlob(bucketName: GcsBucketName,
                       blobName: GcsBlobName,
                       credentials: scala.Option[Credentials],
                       traceId: Option[TraceId],
                       retryConfig: RetryConfig): fs2.Stream[IO, Blob] =
    bucketName match {
      case GcsBucketName("nonExistent") => fs2.Stream.empty
      case GcsBucketName("failure") =>
        super.createBlob(bucketName, blobName, Array.empty[Byte], "text/plain", Map("passed" -> "false")) >> super
          .getBlob(bucketName, blobName)
      case GcsBucketName("success") =>
        super.createBlob(bucketName, blobName, Array.empty[Byte], "text/plain", Map("passed" -> "true")) >> super
          .getBlob(bucketName, blobName)
      case GcsBucketName("staging_bucket") =>
        if (blobName.value == "failed_userstartupscript_output.txt")
          super.createBlob(bucketName, blobName, Array.empty[Byte], "text/plain", Map("passed" -> "false")) >> super
            .getBlob(bucketName, blobName)
        else
          super.createBlob(bucketName, blobName, Array.empty[Byte], "text/plain", Map("passed" -> "true")) >> super
            .getBlob(bucketName, blobName)
      case _ => super.getBlob(bucketName, blobName, None, traceId)
    }
}

object NoDeleteGoogleStorage extends BaseFakeGoogleStorage {
  override def deleteBucket(googleProject: GoogleProject,
                            bucketName: GcsBucketName,
                            isRecursive: Boolean,
                            bucketSourceOptions: List[BucketSourceOption],
                            traceId: Option[TraceId],
                            retryConfig: RetryConfig): fs2.Stream[IO, Boolean] =
    throw new Exception("this shouldn't get called")
}

object MockAuthProvider extends LeoAuthProvider[IO] {
  override def serviceAccountProvider: ServiceAccountProvider[IO] = ???

  override def hasPermission[R, A](samResource: R, action: A, userInfo: UserInfo)(
    implicit sr: SamResourceAction[R, A],
    ev: Ask[IO, TraceId]
  ): IO[Boolean] = ???

  override def hasPermissionWithProjectFallback[R, A](
    samResource: R,
    action: A,
    projectAction: ProjectAction,
    userInfo: UserInfo,
    googleProject: GoogleProject
  )(implicit sr: SamResourceAction[R, A], ev: Ask[IO, TraceId]): IO[Boolean] = ???

  override def getActions[R, A](samResource: R, userInfo: UserInfo)(
    implicit sr: SamResourceAction[R, A],
    ev: Ask[IO, TraceId]
  ): IO[List[sr.ActionCategory]] = ???

  override def getActionsWithProjectFallback[R, A](samResource: R, googleProject: GoogleProject, userInfo: UserInfo)(
    implicit sr: SamResourceAction[R, A],
    ev: Ask[IO, TraceId]
  ): IO[(List[sr.ActionCategory], List[ProjectAction])] = ???

  override def filterUserVisible[R](
    resources: NonEmptyList[R],
    userInfo: UserInfo
  )(implicit sr: SamResource[R], decoder: Decoder[R], ev: Ask[IO, TraceId]): IO[List[R]] = ???

  override def filterUserVisibleWithProjectFallback[R](
    resources: NonEmptyList[(GoogleProject, R)],
    userInfo: UserInfo
  )(implicit sr: SamResource[R], decoder: Decoder[R], ev: Ask[IO, TraceId]): IO[List[(GoogleProject, R)]] =
    ???

  override def notifyResourceCreated[R](samResource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    encoder: Encoder[R],
    ev: Ask[IO, TraceId]
  ): IO[Unit] = IO.unit

  override def notifyResourceDeleted[R](samResource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    ev: Ask[IO, TraceId]
  ): IO[Unit] = IO.unit
}

class FakeGoogleSubcriber[A] extends GoogleSubscriber[IO, A] {
  def messages: Stream[IO, Event[A]] = Stream.empty
  // If you use `start`, make sure to hook up `messages` somewhere as well on the same instance for consuming the messages; Otherwise, messages will be left nacked
  def start: IO[Unit] = IO.unit
  def stop: IO[Unit] = IO.unit
}

object MockRuntimeAlgebra extends RuntimeAlgebra[IO] {
  override def createRuntime(params: CreateRuntimeParams)(
    implicit ev: Ask[IO, AppContext]
  ): IO[CreateGoogleRuntimeResponse] = ???

  override def getRuntimeStatus(params: GetRuntimeStatusParams)(
    implicit ev: Ask[IO, AppContext]
  ): IO[RuntimeStatus] = ???

  override def deleteRuntime(params: DeleteRuntimeParams)(
    implicit ev: Ask[IO, AppContext]
  ): IO[Option[Operation]] = IO.pure(None)

  override def finalizeDelete(params: FinalizeDeleteParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = ???

  override def stopRuntime(params: StopRuntimeParams)(
    implicit ev: Ask[IO, AppContext]
  ): IO[Option[Operation]] = ???

  override def startRuntime(params: StartRuntimeParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = ???

  override def updateMachineType(params: UpdateMachineTypeParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] =
    ???

  override def updateDiskSize(params: UpdateDiskSizeParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = ???

  override def resizeCluster(params: ResizeClusterParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = ???
}

class MockKubernetesService(podStatus: PodStatus = PodStatus.Running, appRelease: List[Release] = List.empty)
    extends org.broadinstitute.dsde.workbench.google2.mock.MockKubernetesService {
  override def listPodStatus(clusterId: GKEModels.KubernetesClusterId, namespace: KubernetesModels.KubernetesNamespace)(
    implicit ev: Ask[IO, TraceId]
  ): IO[List[KubernetesModels.KubernetesPodStatus]] =
    IO(List(KubernetesPodStatus.apply(PodName("test"), podStatus)))

  override def listPersistentVolumeClaims(clusterId: KubernetesClusterId, namespace: KubernetesNamespace)(
    implicit
    ev: Ask[IO, TraceId]
  ): IO[List[V1PersistentVolumeClaim]] =
    appRelease.flatTraverse { r =>
      val nfsPvc = mock[io.kubernetes.client.openapi.models.V1PersistentVolumeClaim]
      val nfcMetadata = mock[V1ObjectMeta]
      when(nfcMetadata.getName).thenReturn(s"${r.asString}-galaxy-pvc")
      when(nfcMetadata.getUid).thenReturn(s"nfs-pvc-id1")
      when(nfsPvc.getMetadata()).thenReturn(nfcMetadata)

      val cvmfsPvc = mock[io.kubernetes.client.openapi.models.V1PersistentVolumeClaim]
      val cvmfsMetadata = mock[V1ObjectMeta]
      when(cvmfsMetadata.getName).thenReturn(s"${r.asString}-cvmfs-alien-cache-pvc")
      when(cvmfsMetadata.getUid).thenReturn(s"cvmfs-pvc-id1")
      when(cvmfsPvc.getMetadata()).thenReturn(cvmfsMetadata)

      IO.pure(
        List(nfsPvc, cvmfsPvc)
      )
    }

  override def deletePv(clusterId: KubernetesClusterId, pv: PvName)(implicit ev: Ask[IO, TraceId]): IO[Unit] =
    IO.unit
}

class MockGKEService extends GKEAlgebra[IO] {

  /** Creates a GKE cluster but doesn't wait for its completion. */
  override def createCluster(params: CreateClusterParams)(
    implicit ev: Ask[IO, AppContext]
  ): IO[Option[CreateClusterResult]] = IO.pure(None)

  /**
   * Polls a creating GKE cluster for its completion and also does other cluster-wide set-up like
   * install nginx ingress controller.
   */
  override def pollCluster(params: PollClusterParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit

  /** Creates a GKE nodepool but doesn't wait for its completion. */
  override def createAndPollNodepool(params: CreateNodepoolParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit

  /** Creates an app and polls it for completion. */
  override def createAndPollApp(params: CreateAppParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit

  /** Deletes a cluster and polls for completion */
  override def deleteAndPollCluster(params: DeleteClusterParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit

  /** Deletes a nodepool and polls for completion */
  override def deleteAndPollNodepool(params: DeleteNodepoolParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit

  /** Deletes an app and polls for completion */
  override def deleteAndPollApp(params: DeleteAppParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit

  /** Stops an app and polls for completion */
  override def stopAndPollApp(params: StopAppParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit

  /** Starts an app and polls for completion */
  override def startAndPollApp(params: StartAppParams)(implicit ev: Ask[IO, AppContext]): IO[Unit] = IO.unit
}
