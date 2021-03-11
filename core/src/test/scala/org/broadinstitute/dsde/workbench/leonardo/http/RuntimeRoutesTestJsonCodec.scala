package org.broadinstitute.dsde.workbench.leonardo.http

import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.{
  AsyncRuntimeFields,
  AuditInfo,
  LabelMap,
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

import org.broadinstitute.dsde.workbench.leonardo.SamResourceId.RuntimeSamResourceId

object RuntimeRoutesTestJsonCodec {
  implicit val dataprocConfigEncoder: Encoder[RuntimeConfigRequest.DataprocConfig] = Encoder.forProduct8(
    "cloudService",
    "numberOfWorkers",
    "masterMachineType",
    "masterDiskSize",
    // worker settings are None when numberOfWorkers is 0
    "workerMachineType",
    "workerDiskSize",
    "numberOfWorkerLocalSSDs",
    "numberOfPreemptibleWorkers"
  )(x =>
    (x.cloudService,
     x.numberOfWorkers,
     x.masterMachineType,
     x.masterDiskSize,
     x.workerMachineType,
     x.workerDiskSize,
     x.numberOfWorkerLocalSSDs,
     x.numberOfPreemptibleWorkers)
  )
  implicit val gceRuntimConfigEncoder: Encoder[RuntimeConfigRequest.GceConfig] = Encoder.forProduct3(
    "cloudService",
    "machineType",
    "diskSize"
  )(x => (x.cloudService, x.machineType, x.diskSize))

  implicit val gceWithPdRuntimConfigEncoder: Encoder[RuntimeConfigRequest.GceWithPdConfig] = Encoder.forProduct3(
    "cloudService",
    "machineType",
    "persistentDisk"
  )(x => (x.cloudService, x.machineType, x.persistentDisk))

  implicit val runtimeConfigRequestEncoder: Encoder[RuntimeConfigRequest] = Encoder.instance { x =>
    x match {
      case x: RuntimeConfigRequest.DataprocConfig  => x.asJson
      case x: RuntimeConfigRequest.GceConfig       => x.asJson
      case x: RuntimeConfigRequest.GceWithPdConfig => x.asJson
    }
  }

  implicit val createRuntime2RequestEncoder: Encoder[CreateRuntime2Request] = Encoder.forProduct11(
    "labels",
    "jupyterUserScriptUri",
    "jupyterStartUserScriptUri",
    "runtimeConfig",
    "userJupyterExtensionConfig",
    "autopause",
    "autopauseThreshold",
    "defaultClientId",
    "toolDockerImage",
    "scopes",
    "customEnvironmentVariables"
  )(x =>
    (
      x.labels,
      x.jupyterUserScriptUri,
      x.jupyterStartUserScriptUri,
      x.runtimeConfig,
      x.userJupyterExtensionConfig,
      x.autopause,
      x.autopauseThreshold.map(_.toMinutes),
      x.defaultClientId,
      x.toolDockerImage,
      x.scopes,
      x.customEnvironmentVariables
    )
  )

  implicit val updateGceConfigRequestEncoder: Encoder[UpdateRuntimeConfigRequest.GceConfig] = Encoder.forProduct3(
    "cloudService",
    "machineType",
    "diskSize"
  )(x => (x.cloudService, x.updatedMachineType, x.updatedDiskSize))

  implicit val updateDataprocConfigRequestEncoder: Encoder[UpdateRuntimeConfigRequest.DataprocConfig] =
    Encoder.forProduct5(
      "cloudService",
      "masterMachineType",
      "masterDiskSize",
      "numberOfWorkers",
      "numberOfPreemptibleWorkers"
    )(x =>
      (x.cloudService,
       x.updatedMasterMachineType,
       x.updatedMasterDiskSize,
       x.updatedNumberOfWorkers,
       x.updatedNumberOfPreemptibleWorkers)
    )

  implicit val updateRuntimeConfigRequestEncoder: Encoder[UpdateRuntimeConfigRequest] = Encoder.instance { x =>
    x match {
      case x: UpdateRuntimeConfigRequest.DataprocConfig => x.asJson
      case x: UpdateRuntimeConfigRequest.GceConfig      => x.asJson
    }
  }

  implicit val updateRuntimeRequestEncoder: Encoder[UpdateRuntimeRequest] = Encoder.forProduct4(
    "runtimeConfig",
    "allowStop",
    "autopause",
    "autopauseThreshold"
  )(x =>
    (
      x.updatedRuntimeConfig,
      x.allowStop,
      x.updateAutopauseEnabled,
      x.updateAutopauseThreshold.map(_.toMinutes)
    )
  )

  implicit val getClusterResponseDecoder: Decoder[GetRuntimeResponse] = Decoder.instance { x =>
    for {
      id <- x.downField("id").as[Long]
      clusterName <- x.downField("runtimeName").as[RuntimeName]
      googleProject <- x.downField("googleProject").as[GoogleProject]
      serviceAccount <- x.downField("serviceAccount").as[WorkbenchEmail]
      asyncRuntimeFields <- x.downField("asyncRuntimeFields").as[Option[AsyncRuntimeFields]]
      auditInfo <- x.downField("auditInfo").as[AuditInfo]
      kernelFoundBusyDate <- x.downField("kernelFoundBusyDate").as[Option[Instant]]
      runtimeConfig <- x.downField("runtimeConfig").as[RuntimeConfig]
      clusterUrl <- x.downField("proxyUrl").as[URL]
      status <- x.downField("status").as[RuntimeStatus]
      labels <- x.downField("labels").as[LabelMap]
      jupyterUserScriptUri <- x.downField("jupyterUserScriptUri").as[Option[UserScriptPath]]
      jupyterStartUserScriptUri <- x.downField("jupyterStartUserScriptUri").as[Option[UserScriptPath]]
      errors <- x.downField("errors").as[List[RuntimeError]]
      userJupyterExtensionConfig <- x.downField("userJupyterExtensionConfig").as[Option[UserJupyterExtensionConfig]]
      autopauseThreshold <- x.downField("autopauseThreshold").as[Int]
      defaultClientId <- x.downField("defaultClientId").as[Option[String]]
      clusterImages <- x.downField("runtimeImages").as[Set[RuntimeImage]]
      scopes <- x.downField("scopes").as[Set[String]]
    } yield GetRuntimeResponse(
      id,
      RuntimeSamResourceId(""),
      clusterName,
      googleProject,
      serviceAccount,
      asyncRuntimeFields,
      auditInfo,
      kernelFoundBusyDate,
      runtimeConfig,
      clusterUrl,
      status,
      labels,
      jupyterUserScriptUri,
      jupyterStartUserScriptUri,
      errors,
      Set.empty, // Dataproc instances
      userJupyterExtensionConfig,
      autopauseThreshold,
      defaultClientId,
      clusterImages,
      scopes,
      true,
      false,
      Map.empty,
      None
    )
  }
}
