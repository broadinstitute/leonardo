package org.broadinstitute.dsde.workbench.leonardo

import java.net.URL
import java.time.Instant

import cats.syntax.all._
import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder}
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterName, NodepoolName}
import org.broadinstitute.dsde.workbench.google2.KubernetesModels.KubernetesApiServerIp
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.{NamespaceName, ServiceName}
import org.broadinstitute.dsde.workbench.google2.{
  DataprocRole,
  DiskName,
  InstanceName,
  KubernetesName,
  Location,
  MachineTypeName,
  NetworkName,
  OperationName,
  SubnetworkName,
  ZoneName
}
import org.broadinstitute.dsde.workbench.leonardo.http.PersistentDiskRequest
import org.broadinstitute.dsde.workbench.model.{IP, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.model.google.{
  parseGcsPath,
  GcsBucketName,
  GcsObjectName,
  GcsPath,
  GoogleProject
}
import org.http4s.Uri

object JsonCodec {
  // Errors
  val negativeNumberDecodingFailure = DecodingFailure("Negative number is not allowed", List.empty)
  val oneWorkerSpecifiedDecodingFailure = DecodingFailure(
    "Google Dataproc does not support clusters with 1 non-preemptible worker. Must be 0, 2 or more.",
    List.empty
  )
  val numNodesDecodingFailure = DecodingFailure(
    "num nodes must be >= 1",
    List.empty
  )
  val deleteDefaultLabelsDecodingFailure = DecodingFailure("Default labels are not allowed to be deleted", List.empty)
  val updateDefaultLabelDecodingFailure = DecodingFailure("Default labels are not allowed to be altered", List.empty)
  val upsertEmptyLabelDecodingFailure = DecodingFailure("Label values are not allowed to be empty", List.empty)

  implicit val operationNameEncoder: Encoder[OperationName] = Encoder.encodeString.contramap(_.value)
  implicit val googleIdEncoder: Encoder[GoogleId] = Encoder.encodeString.contramap(_.value)
  implicit val ipEncoder: Encoder[IP] = Encoder.encodeString.contramap(_.asString)
  implicit val gcsBucketNameEncoder: Encoder[GcsBucketName] = Encoder.encodeString.contramap(_.value)
  implicit val workbenchEmailEncoder: Encoder[WorkbenchEmail] = Encoder.encodeString.contramap(_.value)
  implicit val gcsObjectNameEncoder: Encoder[GcsObjectName] = Encoder.encodeString.contramap(_.value)
  implicit val googleProjectEncoder: Encoder[GoogleProject] = Encoder.encodeString.contramap(_.value)
  implicit val gcsPathEncoder: Encoder[GcsPath] = Encoder.encodeString.contramap(_.toUri)
  implicit val userScriptPathEncoder: Encoder[UserScriptPath] = Encoder.encodeString.contramap(_.asString)
  implicit val machineTypeEncoder: Encoder[MachineTypeName] = Encoder.encodeString.contramap(_.value)
  implicit val cloudServiceEncoder: Encoder[CloudService] = Encoder.encodeString.contramap(_.asString)
  implicit val runtimeNameEncoder: Encoder[RuntimeName] = Encoder.encodeString.contramap(_.asString)
  implicit val runtimeSamResourceIdEncoder: Encoder[RuntimeSamResourceId] = Encoder.encodeString.contramap(_.resourceId)
  implicit val urlEncoder: Encoder[URL] = Encoder.encodeString.contramap(_.toString)
  implicit val zoneNameEncoder: Encoder[ZoneName] = Encoder.encodeString.contramap(_.value)
  implicit val diskNameEncoder: Encoder[DiskName] = Encoder.encodeString.contramap(_.value)
  implicit val diskSamResourceIdEncoder: Encoder[PersistentDiskSamResourceId] =
    Encoder.encodeString.contramap(_.resourceId)
  implicit val diskSizeEncoder: Encoder[DiskSize] = Encoder.encodeInt.contramap(_.gb)
  implicit val blockSizeEncoder: Encoder[BlockSize] = Encoder.encodeInt.contramap(_.bytes)
  implicit val diskIdEncoder: Encoder[DiskId] = Encoder.encodeLong.contramap(_.value)
  implicit val diskStatusEncoder: Encoder[DiskStatus] = Encoder.encodeString.contramap(_.entryName)
  implicit val diskTypeEncoder: Encoder[DiskType] = Encoder.encodeString.contramap(_.asString)

  implicit val dataprocConfigEncoder: Encoder[RuntimeConfig.DataprocConfig] = Encoder.forProduct8(
    "numberOfWorkers",
    "masterMachineType",
    "masterDiskSize",
    // worker settings are None when numberOfWorkers is 0
    "workerMachineType",
    "workerDiskSize",
    "numberOfWorkerLocalSSDs",
    "numberOfPreemptibleWorkers",
    "cloudService"
  )(x =>
    (x.numberOfWorkers,
     x.machineType,
     x.diskSize,
     x.workerMachineType,
     x.workerDiskSize,
     x.numberOfWorkerLocalSSDs,
     x.numberOfPreemptibleWorkers,
     x.cloudService)
  )
  implicit val gceRuntimeConfigEncoder: Encoder[RuntimeConfig.GceConfig] = Encoder.forProduct4(
    "machineType",
    "diskSize",
    "cloudService",
    "bootDiskSize"
  )(x => (x.machineType, x.diskSize, x.cloudService, x.bootDiskSize))
  implicit val userJupyterExtensionConfigEncoder: Encoder[UserJupyterExtensionConfig] = Encoder.forProduct4(
    "nbExtensions",
    "serverExtensions",
    "combinedExtensions",
    "labExtensions"
  )(x => UserJupyterExtensionConfig.unapply(x).get)
  implicit val auditInfoEncoder: Encoder[AuditInfo] = Encoder.forProduct4(
    "creator",
    "createdDate",
    "destroyedDate",
    "dateAccessed"
  )(x => AuditInfo.unapply(x).get)
  implicit val runtimeImageTypeEncoder: Encoder[RuntimeImageType] = Encoder.encodeString.contramap(_.toString)
  implicit val containerImageEncoder: Encoder[ContainerImage] = Encoder.encodeString.contramap(_.imageUrl)
  implicit val containerRegistryEncoder: Encoder[ContainerRegistry] = Encoder.encodeString.contramap(_.entryName)
  implicit val runtimeImageEncoder: Encoder[RuntimeImage] = Encoder.forProduct3(
    "imageType",
    "imageUrl",
    "timestamp"
  )(x => RuntimeImage.unapply(x).get)
  implicit val gceWithPdConfigEncoder: Encoder[RuntimeConfig.GceWithPdConfig] = Encoder.forProduct4(
    "machineType",
    "persistentDiskId",
    "cloudService",
    "bootDiskSize"
  )(x => (x.machineType, x.persistentDiskId, x.cloudService, x.bootDiskSize))

  implicit val runtimeConfigEncoder: Encoder[RuntimeConfig] = Encoder.instance(x =>
    x match {
      case x: RuntimeConfig.DataprocConfig  => x.asJson
      case x: RuntimeConfig.GceConfig       => x.asJson
      case x: RuntimeConfig.GceWithPdConfig => x.asJson
    }
  )
  implicit val defaultRuntimeLabelsEncoder: Encoder[DefaultRuntimeLabels] = Encoder.forProduct7(
    "clusterName",
    "googleProject",
    "creator",
    "clusterServiceAccount",
    "notebookUserScript",
    "notebookStartUserScript",
    "tool"
  )(x => DefaultRuntimeLabels.unapply(x).get)
  implicit val asyncRuntimeFieldsEncoder: Encoder[AsyncRuntimeFields] =
    Encoder.forProduct4("googleId", "operationName", "stagingBucket", "hostIp")(x => AsyncRuntimeFields.unapply(x).get)
  implicit val clusterProjectAndNameEncoder: Encoder[RuntimeProjectAndName] = Encoder.forProduct2(
    "googleProject",
    "clusterName"
  )(x => RuntimeProjectAndName.unapply(x).get)
  implicit val runtimeErrorEncoder: Encoder[RuntimeError] = Encoder.forProduct3(
    "errorMessage",
    "errorCode",
    "timestamp"
  )(x => RuntimeError.unapply(x).get)

  implicit val errorSourceEncoder: Encoder[ErrorSource] = Encoder.encodeString.contramap(_.toString)
  implicit val errorActionEncoder: Encoder[ErrorAction] = Encoder.encodeString.contramap(_.toString)
  implicit val kubernetesErrorEncoder: Encoder[AppError] =
    Encoder.forProduct5("errorMessage", "timestamp", "action", "source", "googleErrorCode")(x =>
      AppError.unapply(x).get
    )
  implicit val nodepoolIdEncoder: Encoder[NodepoolLeoId] = Encoder.encodeLong.contramap(_.id)
  implicit val nodepoolNameEncoder: Encoder[NodepoolName] = Encoder.encodeString.contramap(_.value)
  implicit val nodepoolStatusEncoder: Encoder[NodepoolStatus] = Encoder.encodeString.contramap(status =>
    status match {
      case NodepoolStatus.Precreating => NodepoolStatus.Provisioning.toString
      case _                          => status.toString
    }
  )
  implicit val numNodesEncoder: Encoder[NumNodes] = Encoder.encodeInt.contramap(_.amount)
  implicit val autoscalingMinEncoder: Encoder[AutoscalingMin] = Encoder.encodeInt.contramap(_.amount)
  implicit val autoscalingMaxEncoder: Encoder[AutoscalingMax] = Encoder.encodeInt.contramap(_.amount)
  implicit val autoscalingConfigEncoder: Encoder[AutoscalingConfig] =
    Encoder.forProduct2("autoscalingMin", "autoscalingMax")(x => AutoscalingConfig.unapply(x).get)
  implicit val kubernetesRuntimeConfigEncoder: Encoder[KubernetesRuntimeConfig] =
    Encoder.forProduct3("numNodes", "machineType", "autoscalingEnabled")(x => KubernetesRuntimeConfig.unapply(x).get)

  implicit val locationEncoder: Encoder[Location] = Encoder.encodeString.contramap(_.value)
  implicit val kubeClusterIdEncoder: Encoder[KubernetesClusterLeoId] = Encoder.encodeLong.contramap(_.id)
  implicit val kubeClusterNameEncoder: Encoder[KubernetesClusterName] = Encoder.encodeString.contramap(_.value)
  implicit val kubeClusterStatusEncoder: Encoder[KubernetesClusterStatus] = Encoder.encodeString.contramap(status =>
    status match {
      case KubernetesClusterStatus.Precreating => KubernetesClusterStatus.Provisioning.toString
      case _                                   => status.toString
    }
  )
  implicit val kubeSamIdEncoder: Encoder[AppSamResourceId] = Encoder.encodeString.contramap(_.resourceId)
  implicit val namespaceEncoder: Encoder[NamespaceName] = Encoder.encodeString.contramap(_.value)
  implicit val appNameEncoder: Encoder[AppName] = Encoder.encodeString.contramap(_.value)
  implicit val appStatusEncoder: Encoder[AppStatus] = Encoder.encodeString.contramap(_.toString)
  implicit val appTypeEncoder: Encoder[AppType] = Encoder.encodeString.contramap(_.toString)
  implicit val serviceNameEncoder: Encoder[ServiceName] = Encoder.encodeString.contramap(_.value)
  implicit val serviceNameDecoder: Decoder[ServiceName] = Decoder.decodeString.map(s => ServiceName(s))

  implicit val apiServerIpEncoder: Encoder[KubernetesApiServerIp] = Encoder.encodeString.contramap(_.value)
  implicit val networkNameEncoder: Encoder[NetworkName] = Encoder.encodeString.contramap(_.value)
  implicit val subNetworkNameEncoder: Encoder[SubnetworkName] = Encoder.encodeString.contramap(_.value)
  implicit val ipRangeEncoder: Encoder[IpRange] = Encoder.encodeString.contramap(_.value)
  implicit val networkFieldsEncoder: Encoder[NetworkFields] =
    Encoder.forProduct3("networkName", "subNetworkName", "subNetworkIpRange")(x => NetworkFields.unapply(x).get)
  implicit val kubeAsyncFieldEncoder: Encoder[KubernetesClusterAsyncFields] =
    Encoder.forProduct3("loadBalancerIp", "apiServerIp", "networkInfo")(x =>
      KubernetesClusterAsyncFields.unapply(x).get
    )

  implicit val instanceNameEncoder: Encoder[InstanceName] = Encoder.encodeString.contramap(_.value)
  implicit val dataprocRoleEncoder: Encoder[DataprocRole] =
    Encoder.encodeString.contramap(_.toString) // We've been using `.toString` in db
  implicit val gceInstanceStatusEncoder: Encoder[GceInstanceStatus] =
    Encoder.encodeString.contramap(_.toString) // We've been using `.toString` in db

  implicit val instanceNameDecoder: Decoder[InstanceName] = Decoder.decodeString.map(InstanceName)
  implicit val dataprocRoleDecoder: Decoder[DataprocRole] =
    Decoder.decodeString.emap(s => DataprocRole.stringToDataprocRole.get(s).toRight(s"invalid dataproc role ${s}"))
  implicit val gceInstanceStatusDecoder: Decoder[GceInstanceStatus] =
    Decoder.decodeString.emap(s => GceInstanceStatus.withNameInsensitiveOption(s).toRight(s"invalid gce status ${s}"))
  implicit val operationNameDecoder: Decoder[OperationName] = Decoder.decodeString.map(OperationName)
  implicit val googleIdDecoder: Decoder[GoogleId] = Decoder.decodeString.map(GoogleId)
  implicit val ipDecoder: Decoder[IP] = Decoder.decodeString.map(IP)
  implicit val containerImageDecoder: Decoder[ContainerImage] =
    Decoder.decodeString.emap(s => ContainerImage.fromImageUrl(s).toRight(s"invalid container image ${s}"))
  implicit val containerRegistryDecoder: Decoder[ContainerRegistry] =
    Decoder.decodeString.emap(s =>
      ContainerRegistry.withNameInsensitiveOption(s).toRight(s"Unsupported container registry ${s}")
    )
  implicit val cloudServiceDecoder: Decoder[CloudService] =
    Decoder.decodeString.emap(s => CloudService.withNameInsensitiveOption(s).toRight(s"Unsupported cloud service ${s}"))
  implicit val runtimeNameDecoder: Decoder[RuntimeName] = Decoder.decodeString.map(RuntimeName)
  implicit val runtimeStatusDecoder: Decoder[RuntimeStatus] = Decoder.decodeString.map(s => RuntimeStatus.withName(s))
  implicit val machineTypeDecoder: Decoder[MachineTypeName] = Decoder.decodeString.emap(s =>
    if (s.isEmpty) Left("machine type cannot be an empty string") else Right(MachineTypeName(s))
  )
  implicit val instantDecoder: Decoder[Instant] =
    Decoder.decodeString.emap(s => Either.catchNonFatal(Instant.parse(s)).leftMap(_.getMessage))
  implicit val gcsBucketNameDecoder: Decoder[GcsBucketName] = Decoder.decodeString.map(GcsBucketName)
  implicit val googleProjectDecoder: Decoder[GoogleProject] = Decoder.decodeString.map(GoogleProject)
  implicit val urlDecoder: Decoder[URL] =
    Decoder.decodeString.emap(s => Either.catchNonFatal(new URL(s)).leftMap(_.getMessage))
  implicit val diskSizeDecoder: Decoder[DiskSize] =
    Decoder.decodeInt.emap(d => if (d < 5) Left("Minimum required disk size is 5GB") else Right(DiskSize(d)))
  implicit val blockSizeDecoder: Decoder[BlockSize] =
    Decoder.decodeInt.emap(d => if (d < 0) Left("Negative number is not allowed") else Right(BlockSize(d)))
  implicit val workbenchEmailDecoder: Decoder[WorkbenchEmail] = Decoder.decodeString.map(WorkbenchEmail)
  implicit val runtimeImageTypeDecoder: Decoder[RuntimeImageType] = Decoder.decodeString.emap(s =>
    RuntimeImageType.stringToRuntimeImageType.get(s).toRight(s"invalid RuntimeImageType ${s}")
  )

  implicit val runtimeImageDecoder: Decoder[RuntimeImage] = Decoder.forProduct3(
    "imageType",
    "imageUrl",
    "timestamp"
  )(RuntimeImage.apply)
  implicit val auditInfoDecoder: Decoder[AuditInfo] = Decoder.forProduct4(
    "creator",
    "createdDate",
    "destroyedDate",
    "dateAccessed"
  )(AuditInfo.apply)
  implicit val rtDataprocConfigDecoder: Decoder[RuntimeConfig.DataprocConfig] = Decoder.instance { c =>
    for {
      numberOfWorkers <- c.downField("numberOfWorkers").as[Int]
      masterMachineType <- c.downField("masterMachineType").as[MachineTypeName]
      masterDiskSize <- c.downField("masterDiskSize").as[DiskSize]
      workerMachineType <- c.downField("workerMachineType").as[Option[MachineTypeName]]
      workerDiskSize <- c.downField("workerDiskSize").as[Option[DiskSize]]
      numberOfWorkerLocalSSDs <- c.downField("numberOfWorkerLocalSSDs").as[Option[Int]]
      numberOfPreemptibleWorkers <- c.downField("numberOfPreemptibleWorkers").as[Option[Int]]
      propertiesOpt <- c.downField("properties").as[Option[LabelMap]]
      properties = propertiesOpt.getOrElse(Map.empty)
    } yield RuntimeConfig.DataprocConfig(numberOfWorkers,
                                         masterMachineType,
                                         masterDiskSize,
                                         workerMachineType,
                                         workerDiskSize,
                                         numberOfWorkerLocalSSDs,
                                         numberOfPreemptibleWorkers,
                                         properties)
  }

  implicit val runtimeConfigDecoder: Decoder[RuntimeConfig] = Decoder.instance { x =>
    for {
      cloudService <- x.downField("cloudService").as[CloudService]
      r <- cloudService match {
        case CloudService.Dataproc =>
          x.as[RuntimeConfig.DataprocConfig]
        case CloudService.GCE =>
          x.as[RuntimeConfig.GceConfig] orElse x.as[RuntimeConfig.GceWithPdConfig]
      }
    } yield r
  }

  implicit val runtimeErrorDecoder: Decoder[RuntimeError] = Decoder.forProduct3(
    "errorMessage",
    "errorCode",
    "timestamp"
  )(RuntimeError.apply)
  implicit val gcsPathDecoder: Decoder[GcsPath] = Decoder.decodeString.emap(s => parseGcsPath(s).leftMap(_.value))
  implicit val userScriptPathDecoder: Decoder[UserScriptPath] = Decoder.decodeString.emap { s =>
    UserScriptPath.stringToUserScriptPath(s).leftMap(_.getMessage)
  }
  implicit val userJupyterExtensionConfigDecoder: Decoder[UserJupyterExtensionConfig] = Decoder.instance { c =>
    for {
      ne <- c.downField("nbExtensions").as[Option[Map[String, String]]]
      se <- c.downField("serverExtensions").as[Option[Map[String, String]]]
      ce <- c.downField("combinedExtensions").as[Option[Map[String, String]]]
      le <- c.downField("labExtensions").as[Option[Map[String, String]]]
    } yield UserJupyterExtensionConfig(ne.getOrElse(Map.empty),
                                       se.getOrElse(Map.empty),
                                       ce.getOrElse(Map.empty),
                                       le.getOrElse(Map.empty))
  }

  implicit val clusterProjectAndNameDecoder: Decoder[RuntimeProjectAndName] =
    Decoder.forProduct2("googleProject", "clusterName")(RuntimeProjectAndName.apply)

  implicit val asyncRuntimeFieldsDecoder: Decoder[AsyncRuntimeFields] =
    Decoder.forProduct4("googleId", "operationName", "stagingBucket", "hostIp")(AsyncRuntimeFields.apply)

  implicit val zoneDecoder: Decoder[ZoneName] = Decoder.decodeString.map(ZoneName(_))
  implicit val diskNameDecoder: Decoder[DiskName] = Decoder.decodeString.emap(s => validateName(s).map(DiskName))
  implicit val diskIdDecoder: Decoder[DiskId] = Decoder.decodeLong.map(DiskId)
  implicit val diskStatusDecoder: Decoder[DiskStatus] =
    Decoder.decodeString.emap(x => DiskStatus.withNameOption(x).toRight(s"Invalid disk status: $x"))
  implicit val diskTypeDecoder: Decoder[DiskType] =
    Decoder.decodeString.emap(x => DiskType.stringToObject.get(x).toRight(s"Invalid disk type: $x"))

  implicit val gceWithPdConfigDecoder: Decoder[RuntimeConfig.GceWithPdConfig] = Decoder.forProduct3(
    "machineType",
    "persistentDiskId",
    "bootDiskSize"
  )(RuntimeConfig.GceWithPdConfig.apply)
  implicit val gceConfigDecoder: Decoder[RuntimeConfig.GceConfig] = Decoder.forProduct3(
    "machineType",
    "diskSize",
    "bootDiskSize"
  )((mt, ds, bds) => RuntimeConfig.GceConfig(mt, ds, bds))

  implicit val persistentDiskRequestDecoder: Decoder[PersistentDiskRequest] = Decoder.instance { x =>
    for {
      n <- x.downField("name").as[DiskName]
      s <- x.downField("size").as[Option[DiskSize]]
      t <- x.downField("diskType").as[Option[DiskType]]
      l <- x.downField("labels").as[Option[LabelMap]]
    } yield PersistentDiskRequest(n, s, t, l.getOrElse(Map.empty))
  }

  implicit val persistentDiskRequestEncoder: Encoder[PersistentDiskRequest] = Encoder.forProduct4(
    "name",
    "size",
    "diskType",
    "labels"
  )(x => (x.name, x.size, x.diskType, x.labels))

  implicit val runtimeSamResourceDecoder: Decoder[RuntimeSamResourceId] =
    Decoder.decodeString.map(RuntimeSamResourceId)
  implicit val persistentDiskSamResourceDecoder: Decoder[PersistentDiskSamResourceId] =
    Decoder.decodeString.map(PersistentDiskSamResourceId)
  implicit val projectSamResourceDecoder: Decoder[ProjectSamResourceId] =
    Decoder.decodeString.map(x => ProjectSamResourceId(GoogleProject(x)))

  implicit val errorSourceDecoder: Decoder[ErrorSource] =
    Decoder.decodeString.emap(s => ErrorSource.stringToObject.get(s).toRight(s"Invalid error source ${s}"))
  implicit val errorActionDecoder: Decoder[ErrorAction] =
    Decoder.decodeString.emap(s => ErrorAction.stringToObject.get(s).toRight(s"Invalid error action ${s}"))
  implicit val kubernetesErrorDecoder: Decoder[AppError] =
    Decoder.forProduct5("errorMessage", "timestamp", "action", "source", "googleErrorCode")(AppError.apply)

  implicit val nodepoolIdDecoder: Decoder[NodepoolLeoId] = Decoder.decodeLong.map(NodepoolLeoId)
  implicit val nodepoolNameDecoder: Decoder[NodepoolName] =
    Decoder.decodeString.emap(s => KubernetesName.withValidation(s, NodepoolName).leftMap(_.getMessage))
  implicit val nodepoolStatusDecoder: Decoder[NodepoolStatus] =
    Decoder.decodeString.emap(s => NodepoolStatus.stringToObject.get(s).toRight(s"Invalid nodepool status ${s}"))
  implicit val numNodesDecoder: Decoder[NumNodes] =
    Decoder.decodeInt.emap(n => if (n < 1) Left("Minimum number of nodes is 1") else Right(NumNodes.apply(n)))
  implicit val autoscalingMinDecoder: Decoder[AutoscalingMin] = Decoder.decodeInt.map(AutoscalingMin)
  implicit val autoscalingMaxDecoder: Decoder[AutoscalingMax] = Decoder.decodeInt.map(AutoscalingMax)
  implicit val autoscalingConfigDecoder: Decoder[AutoscalingConfig] =
    Decoder.forProduct2("autoscalingMin", "autoscalingMax")(AutoscalingConfig.apply)
  implicit val kubernetesRuntimeConfigDecoder: Decoder[KubernetesRuntimeConfig] =
    Decoder.forProduct3("numNodes", "machineType", "autoscalingEnabled")(KubernetesRuntimeConfig.apply)

  implicit val locationDecoder: Decoder[Location] = Decoder.decodeString.map(Location)
  implicit val kubeClusterIdDecoder: Decoder[KubernetesClusterLeoId] = Decoder.decodeLong.map(KubernetesClusterLeoId)
  implicit val kubeClusterNameDecoder: Decoder[KubernetesClusterName] =
    Decoder.decodeString.emap(s => KubernetesName.withValidation(s, KubernetesClusterName).leftMap(_.getMessage))
  implicit val kubeClusterStatusDecoder: Decoder[KubernetesClusterStatus] = Decoder.decodeString.emap(s =>
    KubernetesClusterStatus.stringToObject.get(s).toRight(s"Invalid cluster status ${s}")
  )
  implicit val appSamIdDecoder: Decoder[AppSamResourceId] = Decoder.decodeString.map(AppSamResourceId)
  implicit val namespaceNameDecoder: Decoder[NamespaceName] =
    Decoder.decodeString.emap(s => KubernetesName.withValidation(s, NamespaceName).leftMap(_.getMessage))
  implicit val namespaceIdDecoder: Decoder[NamespaceId] = Decoder.decodeLong.map(NamespaceId)
  implicit val namespaceDecoder: Decoder[Namespace] = Decoder.forProduct2("id", "name")(Namespace.apply)
  implicit val appNameDecoder: Decoder[AppName] = Decoder.decodeString.map(AppName)
  implicit val appStatusDecoder: Decoder[AppStatus] =
    Decoder.decodeString.emap(s => AppStatus.stringToObject.get(s).toRight(s"Invalid app status ${s}"))
  implicit val appTypeDecoder: Decoder[AppType] =
    Decoder.decodeString.emap(s => AppType.stringToObject.get(s).toRight(s"Invalid app type ${s}"))

  implicit val apiServerIpDecoder: Decoder[KubernetesApiServerIp] = Decoder.decodeString.map(KubernetesApiServerIp)
  implicit val networkNameDecoder: Decoder[NetworkName] = Decoder.decodeString.map(NetworkName)
  implicit val subNetworkNameDecoder: Decoder[SubnetworkName] = Decoder.decodeString.map(SubnetworkName)
  implicit val ipRangeDecoder: Decoder[IpRange] = Decoder.decodeString.map(IpRange)
  implicit val networkFieldsDecoder: Decoder[NetworkFields] =
    Decoder.forProduct3("networkName", "subNetworkName", "subNetworkIpRange")(NetworkFields)
  implicit val kubeAsyncFieldDecoder: Decoder[KubernetesClusterAsyncFields] =
    Decoder.forProduct3("loadBalancerIp", "apiServerIp", "networkInfo")(KubernetesClusterAsyncFields)

  implicit val dataprocInstanceKeyDecoder: Decoder[DataprocInstanceKey] = Decoder.forProduct3(
    "project",
    "zone",
    "name"
  )(DataprocInstanceKey.apply)

  implicit val dataprocInstanceKeyEncoder: Encoder[DataprocInstanceKey] = Encoder.forProduct3(
    "project",
    "zone",
    "name"
  )(x => DataprocInstanceKey.unapply(x).get)

  implicit val dataprocInstanceDecoder: Decoder[DataprocInstance] =
    Decoder.forProduct6(
      "key",
      "googleId",
      "status",
      "ip",
      "dataprocRole",
      "createdDate"
    )(DataprocInstance.apply)

  implicit val dataprocInstanceEncoder: Encoder[DataprocInstance] =
    Encoder.forProduct6(
      "key",
      "googleId",
      "status",
      "ip",
      "dataprocRole",
      "createdDate"
    )(x => DataprocInstance.unapply(x).get)

  implicit val uriDecoder: Decoder[Uri] =
    Decoder.decodeString.emap(s => Uri.fromString(s).leftMap(_.getMessage()))
}
