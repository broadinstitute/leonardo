package org.broadinstitute.dsde.workbench.leonardo

import java.net.URL
import java.time.Instant

import cats.syntax.all._
import enumeratum.{Enum, EnumEntry}
import monocle.Prism
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.google2.{MachineTypeName, OperationName}
import org.broadinstitute.dsde.workbench.google2.DataprocRole.SecondaryWorker
import org.broadinstitute.dsde.workbench.leonardo.RuntimeContainerServiceType.JupyterService
import org.broadinstitute.dsde.workbench.leonardo.RuntimeImageType.{Jupyter, RStudio, VM, Welder}
import org.broadinstitute.dsde.workbench.model.google.{parseGcsPath, GcsBucketName, GcsPath, GoogleProject}
import org.broadinstitute.dsde.workbench.model.{IP, ValueObject, WorkbenchEmail}

import scala.collection.immutable

/**
 * This file contains models for Leonardo runtimes.
 */
/** The runtime itself */
final case class Runtime(id: Long,
                         samResource: RuntimeSamResourceId,
                         runtimeName: RuntimeName,
                         googleProject: GoogleProject,
                         serviceAccount: WorkbenchEmail,
                         asyncRuntimeFields: Option[AsyncRuntimeFields],
                         auditInfo: AuditInfo,
                         kernelFoundBusyDate: Option[Instant],
                         proxyUrl: URL,
                         status: RuntimeStatus,
                         labels: LabelMap,
                         jupyterUserScriptUri: Option[UserScriptPath],
                         jupyterStartUserScriptUri: Option[UserScriptPath],
                         errors: List[RuntimeError],
                         dataprocInstances: Set[DataprocInstance],
                         userJupyterExtensionConfig: Option[UserJupyterExtensionConfig],
                         autopauseThreshold: Int,
                         defaultClientId: Option[String],
                         allowStop: Boolean,
                         runtimeImages: Set[RuntimeImage],
                         scopes: Set[String],
                         welderEnabled: Boolean,
                         customEnvironmentVariables: Map[String, String],
                         runtimeConfigId: RuntimeConfigId,
                         patchInProgress: Boolean) {
  def projectNameString: String = s"${googleProject.value}/${runtimeName.asString}"
  def nonPreemptibleInstances: Set[DataprocInstance] = dataprocInstances.filterNot(_.dataprocRole == SecondaryWorker)
}

object Runtime {
  def getProxyUrl(urlBase: String,
                  googleProject: GoogleProject,
                  runtimeName: RuntimeName,
                  runtimeImages: Set[RuntimeImage],
                  labels: Map[String, String]): URL = {
    val tool = runtimeImages
      .map(_.imageType)
      .filterNot(Set(Welder, VM).contains)
      .headOption
      .orElse(labels.get("tool").flatMap(RuntimeImageType.withNameInsensitiveOption))
      .flatMap(t => RuntimeContainerServiceType.imageTypeToRuntimeContainerServiceType.get(t))
      .headOption
      .getOrElse(JupyterService)

    new URL(
      urlBase + googleProject.value + "/" + runtimeName.asString + "/" + tool.proxySegment
    )
  }
}

/** Runtime status enum */
sealed trait RuntimeStatus extends EnumEntry with Product with Serializable
object RuntimeStatus extends Enum[RuntimeStatus] {
  val values = findValues
  // Leonardo defined runtime statuses.

  // These statuses exist when we save a runtime during an API request, but we haven't published the event to back leo
  case object PreCreating extends RuntimeStatus
  case object PreDeleting extends RuntimeStatus
  case object PreStarting extends RuntimeStatus
  case object PreStopping extends RuntimeStatus

  // NOTE: Remember to update the definition of this enum in Swagger when you add new ones
  case object Creating extends RuntimeStatus
  case object Updating extends RuntimeStatus //only for dataproc status
  case object Deleting extends RuntimeStatus
  case object Starting extends RuntimeStatus
  case object Stopping extends RuntimeStatus

  case object Running extends RuntimeStatus
  case object Error extends RuntimeStatus
  case object Unknown extends RuntimeStatus
  case object Stopped extends RuntimeStatus
  case object Deleted extends RuntimeStatus

  def fromDataprocClusterStatus(dataprocClusterStatus: DataprocClusterStatus): RuntimeStatus =
    dataprocClusterStatus match {
      case DataprocClusterStatus.Creating => Creating
      case DataprocClusterStatus.Deleting => Deleting
      case DataprocClusterStatus.Error    => Error
      case DataprocClusterStatus.Running  => Running
      case DataprocClusterStatus.Unknown  => Unknown
      case DataprocClusterStatus.Updating => Updating
    }

  def fromGceInstanceStatus(gceInstanceStatus: GceInstanceStatus): RuntimeStatus =
    gceInstanceStatus match {
      case GceInstanceStatus.Provisioning => Creating
      case GceInstanceStatus.Staging      => Creating
      case GceInstanceStatus.Running      => Running
      case GceInstanceStatus.Stopping     => Stopping
      case GceInstanceStatus.Stopped      => Stopped
      case GceInstanceStatus.Suspending   => Stopping
      case GceInstanceStatus.Suspended    => Stopped
      case GceInstanceStatus.Terminated   => Stopped
    }

  // A user might need to connect to this notebook in the future. Keep it warm in the DNS cache.
  val activeStatuses: Set[RuntimeStatus] =
    Set(Unknown, Creating, Running, Updating, Stopping, Stopped, Starting)

  // Can a user delete this runtime? Contains everything except Creating, Deleting, Deleted.
  val deletableStatuses: Set[RuntimeStatus] =
    Set(Unknown, Running, Updating, Error, Stopping, Stopped, Starting)

  // Non-terminal statuses. Requires monitoring via ClusterMonitorActor.
  val monitoredStatuses: Set[RuntimeStatus] = Set(Unknown, Creating, Updating, Deleting, Stopping, Starting)

  // Can a user stop this runtime?
  val stoppableStatuses: Set[RuntimeStatus] = Set(Unknown, Running, Updating, Starting)

  // Can a user start this runtime?
  val startableStatuses: Set[RuntimeStatus] = Set(Stopped, Stopping)

  // Can a user update (i.e. resize) this runtime?
  val updatableStatuses: Set[RuntimeStatus] = Set(Running, Stopped)

  implicit class EnrichedRuntimeStatus(status: RuntimeStatus) {
    def isActive: Boolean = activeStatuses contains status
    def isDeletable: Boolean = deletableStatuses contains status
    def isMonitored: Boolean = monitoredStatuses contains status
    def isStoppable: Boolean = stoppableStatuses contains status
    def isStartable: Boolean = startableStatuses contains status
    def isUpdatable: Boolean = updatableStatuses contains status
  }
}

/** Fields that are populated asynchronous to the runtime's creation */
case class AsyncRuntimeFields(googleId: GoogleId,
                              operationName: OperationName,
                              stagingBucket: GcsBucketName,
                              hostIp: Option[IP])

/** The cloud environment of the runtime, e.g. Dataproc, GCE. */
sealed trait CloudService extends EnumEntry with Product with Serializable {
  def asString: String

  override def toString: String = asString //Enumeratum's withName function uses `toString` as key for lookup
}
object CloudService extends Enum[CloudService] {
  case object Dataproc extends CloudService {
    val asString: String = "DATAPROC"
  }
  case object GCE extends CloudService {
    val asString: String = "GCE"
  }

  override def values: immutable.IndexedSeq[CloudService] = findValues
}

sealed trait CustomImage extends Product with Serializable {
  def asString: String
  def cloudService: CloudService

  override val toString = asString
}
object CustomImage {
  final case class DataprocCustomImage(asString: String) extends CustomImage {
    val cloudService = CloudService.Dataproc
  }
  final case class GceCustomImage(asString: String) extends CustomImage {
    val cloudService = CloudService.GCE
  }
}

/** Configuration of the runtime such as machine types, disk size, etc */
final case class RuntimeConfigId(id: Long) extends AnyVal
sealed trait RuntimeConfig extends Product with Serializable {
  def cloudService: CloudService
  def machineType: MachineTypeName
}
object RuntimeConfig {
  final case class GceConfig(
    machineType: MachineTypeName,
    diskSize: DiskSize,
    bootDiskSize: Option[
      DiskSize
    ] //This is optional for supporting old runtimes which only have 1 disk. All new runtime will have a boot disk
  ) extends RuntimeConfig {
    val cloudService: CloudService = CloudService.GCE
  }

  // When persistentDiskId is None, then we don't have any disk attached to the runtime
  final case class GceWithPdConfig(machineType: MachineTypeName,
                                   persistentDiskId: Option[DiskId],
                                   bootDiskSize: DiskSize)
      extends RuntimeConfig {
    val cloudService: CloudService = CloudService.GCE
  }

  final case class DataprocConfig(numberOfWorkers: Int,
                                  masterMachineType: MachineTypeName,
                                  masterDiskSize: DiskSize, //min 10
                                  // worker settings are None when numberOfWorkers is 0
                                  workerMachineType: Option[MachineTypeName] = None,
                                  workerDiskSize: Option[DiskSize] = None, //min 10
                                  numberOfWorkerLocalSSDs: Option[Int] = None, //min 0 max 8
                                  numberOfPreemptibleWorkers: Option[Int] = None,
                                  properties: Map[String, String])
      extends RuntimeConfig {
    val cloudService: CloudService = CloudService.Dataproc
    val machineType: MachineTypeName = masterMachineType
    val diskSize: DiskSize = masterDiskSize
  }
}

/** Runtime user script */
sealed trait UserScriptPath extends Product with Serializable {
  def asString: String
}
object UserScriptPath {
  final case class Http(url: URL) extends UserScriptPath {
    val asString: String = url.toString
  }
  final case class Gcs(gcsPath: GcsPath) extends UserScriptPath {
    val asString: String = gcsPath.toUri
  }

  val gcsPrism = Prism[UserScriptPath, UserScriptPath.Gcs] {
    case x: UserScriptPath.Gcs  => Some(x)
    case UserScriptPath.Http(_) => None
  }(identity)

  def stringToUserScriptPath(string: String): Either[Throwable, UserScriptPath] =
    parseGcsPath(string) match {
      case Right(value) => Right(Gcs(value))
      case Left(_)      => Either.catchNonFatal(new URL(string)).map(url => Http(url))
    }
}

/** Jupyter extension configuration */
final case class UserJupyterExtensionConfig(nbExtensions: Map[String, String] = Map.empty,
                                            serverExtensions: Map[String, String] = Map.empty,
                                            combinedExtensions: Map[String, String] = Map.empty,
                                            labExtensions: Map[String, String] = Map.empty) {

  def asLabels: Map[String, String] =
    nbExtensions ++ serverExtensions ++ combinedExtensions ++ labExtensions
}

/** Types of Jupyter extensions */
sealed trait ExtensionType extends EnumEntry
object ExtensionType extends Enum[ExtensionType] {
  val values = findValues

  case object NBExtension extends ExtensionType
  case object ServerExtension extends ExtensionType
  case object CombinedExtension extends ExtensionType
  case object LabExtension extends ExtensionType
}

/** Types of images that can be deployed to a runtime */
sealed trait RuntimeImageType extends EnumEntry with Serializable with Product
object RuntimeImageType extends Enum[RuntimeImageType] {
  val values = findValues

  case object Jupyter extends RuntimeImageType
  case object RStudio extends RuntimeImageType
  case object Welder extends RuntimeImageType
  case object VM extends RuntimeImageType
  case object Proxy extends RuntimeImageType
  case object CryptoDetector extends RuntimeImageType

  def stringToRuntimeImageType: Map[String, RuntimeImageType] = values.map(c => c.toString -> c).toMap
}

/** Types of container services that can be deployed to a runtime */
sealed trait RuntimeContainerServiceType extends EnumEntry with Serializable with Product {
  def imageType: RuntimeImageType
  def proxySegment: String
}
object RuntimeContainerServiceType extends Enum[RuntimeContainerServiceType] {
  val values = findValues
  val imageTypeToRuntimeContainerServiceType: Map[RuntimeImageType, RuntimeContainerServiceType] =
    values.toList.map(v => v.imageType -> v).toMap
  case object JupyterService extends RuntimeContainerServiceType {
    override def imageType: RuntimeImageType = Jupyter
    override def proxySegment: String = "jupyter"
  }
  case object RStudioService extends RuntimeContainerServiceType {
    override def imageType: RuntimeImageType = RStudio
    override def proxySegment: String = "rstudio"
  }
  case object WelderService extends RuntimeContainerServiceType {
    override def imageType: RuntimeImageType = Welder
    override def proxySegment: String = "welder"
  }
}

/** Information about an image running on a runtime */
final case class RuntimeImage(imageType: RuntimeImageType, imageUrl: String, timestamp: Instant) {
  def hash: Option[String] = {
    val splitUrl = imageUrl.split(":")
    if (splitUrl isDefinedAt 1) Some(splitUrl(1)) else None
  }
  def registry: Option[ContainerRegistry] = ContainerRegistry.inferRegistry(imageUrl)
}

/** Audit information about a runtime */
final case class AuditInfo(creator: WorkbenchEmail,
                           createdDate: Instant,
                           destroyedDate: Option[Instant],
                           dateAccessed: Instant)

/** UIs that can be used to access a runtime */
sealed trait RuntimeUI extends Product with Serializable {
  def asString: String
}
object RuntimeUI {
  final case object Terra extends RuntimeUI {
    override def asString: String = "Terra"
  }
  final case object AoU extends RuntimeUI {
    override def asString: String = "AoU"
  }
  final case object Other extends RuntimeUI {
    override def asString: String = "Other"
  }
}

/** Default runtime labels */
case class DefaultRuntimeLabels(runtimeName: RuntimeName,
                                googleProject: GoogleProject,
                                creator: WorkbenchEmail,
                                serviceAccount: WorkbenchEmail,
                                notebookUserScript: Option[UserScriptPath],
                                notebookStartUserScript: Option[UserScriptPath],
                                tool: Option[RuntimeImageType]) {
  def toMap: LabelMap =
    Map(
      "runtimeName" -> runtimeName.asString,
      "clusterName" -> runtimeName.asString, //TODO: potentially deprecate this once clients moves away from using this label (deprecated 3/5/2020)
      "googleProject" -> googleProject.value,
      "creator" -> creator.value,
      "clusterServiceAccount" -> serviceAccount.value,
      "notebookUserScript" -> notebookUserScript.map(_.asString).getOrElse(null),
      "notebookStartUserScript" -> notebookStartUserScript.map(_.asString).getOrElse(null),
      "tool" -> tool.map(_.toString).getOrElse(null)
    ).filterNot(_._2 == null)
}

object DefaultRuntimeLabels {
  // Creating a dummy instance to obtain the default label keys
  val defaultLabelKeys = DefaultRuntimeLabels(RuntimeName(""),
                                              GoogleProject(""),
                                              WorkbenchEmail(""),
                                              WorkbenchEmail(""),
                                              None,
                                              None,
                                              None).toMap.keySet
}

/** Welder operations */
sealed trait WelderAction extends EnumEntry
object WelderAction extends Enum[WelderAction] {
  val values = findValues

  case object UpdateWelder extends WelderAction
  case object DisableDelocalization extends WelderAction
}

final case class MemorySize(bytes: Long) extends AnyVal {
  override def toString: String = bytes.toString + "b"
}
object MemorySize {
  def fromKb(kb: Double): MemorySize = MemorySize((kb * 1024).toLong)
  def fromMb(mb: Double): MemorySize = MemorySize((mb * 1048576).toLong)
  def fromGb(gb: Double): MemorySize = MemorySize((gb * 1073741824).toLong)
}

/**
 * Resource constraints for a runtime.
 * See https://docs.docker.com/compose/compose-file/compose-file-v2/#cpu-and-other-resources
 * for other types of resources we may want to add here.
 */
final case class RuntimeResourceConstraints(memoryLimit: MemorySize)

final case class RunningRuntime(googleProject: GoogleProject,
                                runtimeName: RuntimeName,
                                containers: List[RuntimeContainerServiceType])

final case class RuntimeName(asString: String) extends AnyVal
final case class RuntimeError(errorMessage: String, errorCode: Option[Int], timestamp: Instant)
final case class RuntimeErrorDetails(longMessage: String,
                                     code: Option[Int] = None,
                                     shortMessage: Option[String] = None,
                                     labels: Map[String, String] = Map.empty)
final case class RuntimeResource(asString: String) extends AnyVal
final case class RuntimeProjectAndName(googleProject: GoogleProject, runtimeName: RuntimeName) {
  override def toString: String = s"${googleProject.value}/${runtimeName.asString}"
}
final case class RuntimeAndRuntimeConfig(runtime: Runtime, runtimeConfig: RuntimeConfig)
final case class IpRange(value: String) extends AnyVal
final case class NetworkTag(value: String) extends ValueObject
final case class GoogleOperation(name: OperationName, id: GoogleId)
final case class GoogleId(value: String) extends AnyVal

sealed trait RuntimeOperation extends Product with Serializable {
  def asString: String
  final override def toString = asString
}
object RuntimeOperation {
  final case object Creating extends RuntimeOperation {
    val asString = "creating"
  }
  final case object Restarting extends RuntimeOperation {
    val asString = "restarting"
  }
  final case object Stopping extends RuntimeOperation {
    val asString = "stopping"
  }
}
