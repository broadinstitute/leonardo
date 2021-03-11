package org.broadinstitute.dsde.workbench.leonardo.http

import org.broadinstitute.dsde.workbench.google2.MachineTypeName
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.leonardo.{
  AsyncRuntimeFields,
  AuditInfo,
  CloudService,
  ContainerImage,
  ContainerRegistry,
  DataprocInstance,
  DiskSize,
  LabelMap,
  Runtime,
  RuntimeConfig,
  RuntimeError,
  RuntimeImage,
  RuntimeName,
  RuntimeStatus,
  UserJupyterExtensionConfig,
  UserScriptPath
}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import java.net.URL
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

sealed trait RuntimeConfigRequest extends Product with Serializable {
  def cloudService: CloudService
}
object RuntimeConfigRequest {
  final case class GceConfig(
    machineType: Option[MachineTypeName],
    diskSize: Option[DiskSize]
  ) extends RuntimeConfigRequest {
    val cloudService: CloudService = CloudService.GCE
  }

  final case class GceWithPdConfig(
    machineType: Option[MachineTypeName],
    persistentDisk: PersistentDiskRequest
  ) extends RuntimeConfigRequest {
    val cloudService: CloudService = CloudService.GCE
  }

  final case class DataprocConfig(numberOfWorkers: Option[Int],
                                  masterMachineType: Option[MachineTypeName],
                                  masterDiskSize: Option[DiskSize], //min 10
                                  // worker settings are None when numberOfWorkers is 0
                                  workerMachineType: Option[MachineTypeName] = None,
                                  workerDiskSize: Option[DiskSize] = None, //min 10
                                  numberOfWorkerLocalSSDs: Option[Int] = None, //min 0 max 8
                                  numberOfPreemptibleWorkers: Option[Int] = None,
                                  properties: Map[String, String])
      extends RuntimeConfigRequest {
    val cloudService: CloudService = CloudService.Dataproc
  }
}

final case class CreateRuntime2Request(
  labels: LabelMap,
  jupyterUserScriptUri: Option[UserScriptPath],
  jupyterStartUserScriptUri: Option[UserScriptPath],
  runtimeConfig: Option[RuntimeConfigRequest],
  userJupyterExtensionConfig: Option[UserJupyterExtensionConfig],
  autopause: Option[Boolean],
  autopauseThreshold: Option[FiniteDuration],
  defaultClientId: Option[String],
  toolDockerImage: Option[ContainerImage],
  welderRegistry: Option[ContainerRegistry],
  scopes: Set[String],
  customEnvironmentVariables: Map[String, String]
)

sealed trait UpdateRuntimeConfigRequest extends Product with Serializable {
  def cloudService: CloudService
}
object UpdateRuntimeConfigRequest {
  final case class GceConfig(updatedMachineType: Option[MachineTypeName], updatedDiskSize: Option[DiskSize])
      extends UpdateRuntimeConfigRequest {
    val cloudService: CloudService = CloudService.GCE
  }
  final case class DataprocConfig(updatedMasterMachineType: Option[MachineTypeName],
                                  updatedMasterDiskSize: Option[DiskSize],
                                  updatedNumberOfWorkers: Option[Int],
                                  updatedNumberOfPreemptibleWorkers: Option[Int])
      extends UpdateRuntimeConfigRequest {
    val cloudService: CloudService = CloudService.Dataproc
  }
}

final case class UpdateRuntimeRequest(updatedRuntimeConfig: Option[UpdateRuntimeConfigRequest],
                                      allowStop: Boolean,
                                      updateAutopauseEnabled: Option[Boolean],
                                      updateAutopauseThreshold: Option[FiniteDuration],
                                      labelsToUpsert: LabelMap,
                                      labelsToDelete: Set[String])

final case class GetRuntimeResponse(id: Long,
                                    samResource: RuntimeSamResourceId,
                                    clusterName: RuntimeName,
                                    googleProject: GoogleProject,
                                    serviceAccountInfo: WorkbenchEmail,
                                    asyncRuntimeFields: Option[AsyncRuntimeFields],
                                    auditInfo: AuditInfo,
                                    kernelFoundBusyDate: Option[Instant],
                                    runtimeConfig: RuntimeConfig,
                                    clusterUrl: URL,
                                    status: RuntimeStatus,
                                    labels: LabelMap,
                                    jupyterUserScriptUri: Option[UserScriptPath],
                                    jupyterStartUserScriptUri: Option[UserScriptPath],
                                    errors: List[RuntimeError],
                                    dataprocInstances: Set[DataprocInstance],
                                    userJupyterExtensionConfig: Option[UserJupyterExtensionConfig],
                                    autopauseThreshold: Int,
                                    defaultClientId: Option[String],
                                    clusterImages: Set[RuntimeImage],
                                    scopes: Set[String],
                                    welderEnabled: Boolean,
                                    patchInProgress: Boolean,
                                    customClusterEnvironmentVariables: Map[String, String],
                                    diskConfig: Option[DiskConfig])

object GetRuntimeResponse {
  def fromRuntime(runtime: Runtime, runtimeConfig: RuntimeConfig, diskConfig: Option[DiskConfig]) = GetRuntimeResponse(
    runtime.id,
    runtime.samResource,
    runtime.runtimeName,
    runtime.googleProject,
    runtime.serviceAccount,
    runtime.asyncRuntimeFields,
    runtime.auditInfo,
    runtime.kernelFoundBusyDate,
    runtimeConfig,
    runtime.proxyUrl,
    runtime.status,
    runtime.labels,
    runtime.jupyterUserScriptUri,
    runtime.jupyterStartUserScriptUri,
    runtime.errors,
    runtime.dataprocInstances,
    runtime.userJupyterExtensionConfig,
    runtime.autopauseThreshold,
    runtime.defaultClientId,
    runtime.runtimeImages,
    runtime.scopes,
    runtime.welderEnabled,
    runtime.patchInProgress,
    runtime.customEnvironmentVariables,
    diskConfig
  )
}
