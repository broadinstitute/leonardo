package org.broadinstitute.dsde.workbench.leonardo

import java.net.URL
import java.time.Instant
import java.util.UUID

import ca.mrvisser.sealerate
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterId, KubernetesClusterName, NodepoolName}
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.{
  NamespaceName,
  ServiceAccountName,
  ServiceName
}
import org.broadinstitute.dsde.workbench.google2.{
  KubernetesName,
  Location,
  MachineTypeName,
  NetworkName,
  RegionName,
  SubnetworkName
}
import org.broadinstitute.dsde.workbench.model.{IP, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsp.{ChartName, ChartVersion, Release}
import org.http4s.Uri

case class KubernetesCluster(id: KubernetesClusterLeoId,
                             googleProject: GoogleProject,
                             clusterName: KubernetesClusterName,
                             // the GKE API supports a location (e.g. us-central1 (a 'region') or us-central1-a (a 'zone))
                             // If a zone is specified, it will be a single-zone cluster, otherwise it will span multiple zones in the region
                             // Leo currently specifies a zone, e.g. "us-central1-a" and makes all clusters single-zone
                             // Location is exposed here in case we ever want to leverage the flexibility GKE provides
                             location: Location,
                             region: RegionName,
                             status: KubernetesClusterStatus,
                             ingressChart: Chart,
                             auditInfo: AuditInfo,
                             asyncFields: Option[KubernetesClusterAsyncFields],
                             namespaces: List[Namespace],
                             nodepools: List[Nodepool]) {

  // TODO consider renaming this method and the KubernetesClusterId class
  // to disambiguate a bit with KubernetesClusterLeoId which is a Leo-specific ID
  def getGkeClusterId: KubernetesClusterId = KubernetesClusterId(googleProject, location, clusterName)
}

final case class KubernetesClusterAsyncFields(loadBalancerIp: IP, apiServerIp: IP, networkInfo: NetworkFields)

final case class NetworkFields(networkName: NetworkName, subNetworkName: SubnetworkName, subNetworkIpRange: IpRange)

//should be in the format 0.0.0.0/0
final case class CidrIP(value: String) extends AnyVal

final case class KubernetesClusterVersion(value: String) extends AnyVal

/** Google Container Cluster statuses
 *  see: https://cloud.google.com/kubernetes-engine/docs/reference/rest/v1/projects.locations.clusters#Cluster.Status
 */
sealed abstract class KubernetesClusterStatus
object KubernetesClusterStatus {
  final case object Unspecified extends KubernetesClusterStatus {
    override def toString: String = "STATUS_UNSPECIFIED"
  }

  final case object Provisioning extends KubernetesClusterStatus {
    override def toString: String = "PROVISIONING"
  }

  final case object Running extends KubernetesClusterStatus {
    override def toString: String = "RUNNING"
  }

  final case object Reconciling extends KubernetesClusterStatus {
    override def toString: String = "RECONCILING"
  }

  //in kubernetes statuses, they label 'deleting' resources as 'stopping'
  final case object Deleting extends KubernetesClusterStatus {
    override def toString: String = "STOPPING"
  }

  final case object Error extends KubernetesClusterStatus {
    override def toString: String = "ERROR"
  }

  final case object Degraded extends KubernetesClusterStatus {
    override def toString: String = "DEGRADED"
  }

  //this is a custom status, and will not be returned from google
  final case object Deleted extends KubernetesClusterStatus {
    override def toString: String = "DELETED"
  }

  final case object Precreating extends KubernetesClusterStatus {
    override def toString: String = "PRECREATING"
  }

  val deleted: KubernetesClusterStatus = Deleted
  def values: Set[KubernetesClusterStatus] = sealerate.values[KubernetesClusterStatus]
  def stringToObject: Map[String, KubernetesClusterStatus] = values.map(v => v.toString -> v).toMap

  val deletableStatuses: Set[KubernetesClusterStatus] =
    Set(Unspecified, Running, Reconciling, Error, Degraded)

  val creatingStatuses: Set[KubernetesClusterStatus] =
    Set(Precreating, Provisioning)
}

/** Google Container Nodepool statuses
 * See https://googleapis.github.io/googleapis/java/all/latest/apidocs/com/google/container/v1/NodePool.Status.html
 */
sealed abstract class NodepoolStatus
object NodepoolStatus {
  case object Unspecified extends NodepoolStatus {
    override def toString: String = "STATUS_UNSPECIFIED"
  }

  case object Provisioning extends NodepoolStatus {
    override def toString: String = "PROVISIONING"
  }

  case object Running extends NodepoolStatus {
    override def toString: String = "RUNNING"
  }

  case object Reconciling extends NodepoolStatus {
    override def toString: String = "RECONCILING"
  }

  //in kubernetes statuses, they label "deleting' resources as 'stopping'
  case object Deleting extends NodepoolStatus {
    override def toString: String = "STOPPING"
  }

  case object Error extends NodepoolStatus {
    override def toString: String = "ERROR"
  }

  //this is a custom status, and will not be returned from google
  case object Deleted extends NodepoolStatus {
    override def toString: String = "DELETED"
  }

  case object RunningWithError extends NodepoolStatus {
    override def toString: String = "RUNNING_WITH_ERROR"
  }

  final case object Precreating extends NodepoolStatus {
    override def toString: String = "PRECREATING"
  }

  final case object Unclaimed extends NodepoolStatus {
    override def toString: String = "UNCLAIMED"
  }

  def values: Set[NodepoolStatus] = sealerate.values[NodepoolStatus]
  def stringToObject: Map[String, NodepoolStatus] = values.map(v => v.toString -> v).toMap

  val deletableStatuses: Set[NodepoolStatus] =
    Set(Unspecified, Running, Reconciling, Unclaimed, Error, RunningWithError)

  val nonParellizableStatuses: Set[NodepoolStatus] =
    Set(Deleting, Provisioning)
}

final case class KubernetesClusterLeoId(id: Long) extends AnyVal
final case class NamespaceId(id: Long) extends AnyVal
final case class Namespace(id: NamespaceId, name: NamespaceName)

final case class Nodepool(id: NodepoolLeoId,
                          clusterId: KubernetesClusterLeoId,
                          nodepoolName: NodepoolName,
                          status: NodepoolStatus,
                          auditInfo: AuditInfo,
                          machineType: MachineTypeName,
                          numNodes: NumNodes,
                          autoscalingEnabled: Boolean,
                          autoscalingConfig: Option[AutoscalingConfig],
                          apps: List[App],
                          isDefault: Boolean)
object KubernetesNameUtils {
  //UUID almost works for this use case, but kubernetes names must start with a-z
  def getUniqueName[A](apply: String => A): Either[Throwable, A] =
    KubernetesName.withValidation(s"k${UUID.randomUUID().toString.toLowerCase.substring(1)}", apply)
}

case class DefaultNodepool(id: NodepoolLeoId,
                           clusterId: KubernetesClusterLeoId,
                           nodepoolName: NodepoolName,
                           status: NodepoolStatus,
                           auditInfo: AuditInfo,
                           machineType: MachineTypeName,
                           numNodes: NumNodes,
                           autoscalingEnabled: Boolean,
                           autoscalingConfig: Option[AutoscalingConfig]) {
  def toNodepool(): Nodepool =
    Nodepool(id,
             clusterId,
             nodepoolName,
             status,
             auditInfo,
             machineType,
             numNodes,
             autoscalingEnabled,
             autoscalingConfig,
             List.empty,
             true)
}
object DefaultNodepool {
  def fromNodepool(n: Nodepool) =
    DefaultNodepool(n.id,
                    n.clusterId,
                    n.nodepoolName,
                    n.status,
                    n.auditInfo,
                    n.machineType,
                    n.numNodes,
                    n.autoscalingEnabled,
                    n.autoscalingConfig)
}

final case class AutoscalingConfig(autoscalingMin: AutoscalingMin, autoscalingMax: AutoscalingMax)

final case class NodepoolLeoId(id: Long) extends AnyVal
final case class NumNodes(amount: Int) extends AnyVal
final case class AutoscalingMin(amount: Int) extends AnyVal
final case class AutoscalingMax(amount: Int) extends AnyVal

final case class DefaultKubernetesLabels(googleProject: GoogleProject,
                                         appName: AppName,
                                         creator: WorkbenchEmail,
                                         serviceAccount: WorkbenchEmail) {
  val toMap: LabelMap =
    Map(
      "appName" -> appName.value,
      "googleProject" -> googleProject.value,
      "creator" -> creator.value,
      "clusterServiceAccount" -> serviceAccount.value
    )
}

sealed abstract class ErrorAction
object ErrorAction {
  case object CreateApp extends ErrorAction {
    override def toString: String = "createGalaxyApp"
  }

  case object DeleteApp extends ErrorAction {
    override def toString: String = "deleteGalaxyApp"
  }

  case object StopApp extends ErrorAction {
    override def toString: String = "stopGalaxyApp"
  }

  case object StartApp extends ErrorAction {
    override def toString: String = "startGalaxyApp"
  }

  case object DeleteNodepool extends ErrorAction {
    override def toString: String = "deleteNodepool"
  }

  def values: Set[ErrorAction] = sealerate.values[ErrorAction]
  def stringToObject: Map[String, ErrorAction] = values.map(v => v.toString -> v).toMap
}
final case class AppError(errorMessage: String,
                          timestamp: Instant,
                          action: ErrorAction,
                          source: ErrorSource,
                          googleErrorCode: Option[Int])

final case class KubernetesErrorId(value: Long) extends AnyVal

sealed abstract class ErrorSource
object ErrorSource {
  case object Cluster extends ErrorSource {
    override def toString: String = "cluster"
  }

  case object Nodepool extends ErrorSource {
    override def toString: String = "nodepool"
  }

  case object App extends ErrorSource {
    override def toString: String = "app"
  }

  case object Disk extends ErrorSource {
    override def toString: String = "disk"
  }
  case object PostgresDisk extends ErrorSource {
    override def toString: String = "postgres-disk"
  }

  def values: Set[ErrorSource] = sealerate.values[ErrorSource]
  def stringToObject: Map[String, ErrorSource] = values.map(v => v.toString -> v).toMap
}

sealed abstract class AppType
object AppType {
  case object Galaxy extends AppType {
    override def toString: String = "GALAXY"
  }
  case object Custom extends AppType {
    override def toString: String = "CUSTOM"
  }

  def values: Set[AppType] = sealerate.values[AppType]
  def stringToObject: Map[String, AppType] = values.map(v => v.toString -> v).toMap
}

final case class AppId(id: Long) extends AnyVal
final case class AppName(value: String) extends AnyVal
//These are async from the perspective of Front Leo saving the app record, but both must exist before the helm command is executed
final case class AppResources(namespace: Namespace,
                              disk: Option[PersistentDisk],
                              services: List[KubernetesService],
                              kubernetesServiceAccountName: Option[ServiceAccountName])

final case class Chart(name: ChartName, version: ChartVersion) {
  override def toString: String = s"${name.asString}${Chart.nameVersionSeparator}${version.asString}"
}
object Chart {
  val nameVersionSeparator: Char = '-'

  def fromString(s: String): Option[Chart] = {
    val separatorIndex = s.lastIndexOf(nameVersionSeparator)
    if (separatorIndex > 0) {
      val name = s.substring(0, separatorIndex)
      val version = s.substring(separatorIndex + 1)
      if (!name.isEmpty && !version.isEmpty) {
        Some(Chart(ChartName(name), ChartVersion(version)))
      } else None
    } else None
  }
}

final case class App(id: AppId,
                     nodepoolId: NodepoolLeoId,
                     appType: AppType,
                     appName: AppName,
                     status: AppStatus,
                     chart: Chart,
                     release: Release,
                     samResourceId: AppSamResourceId,
                     googleServiceAccount: WorkbenchEmail,
                     auditInfo: AuditInfo,
                     labels: LabelMap,
                     //this is populated async to app creation
                     appResources: AppResources,
                     errors: List[AppError],
                     customEnvironmentVariables: Map[String, String],
                     descriptorPath: Option[Uri],
                     extraArgs: List[String]) {
  def getProxyUrls(project: GoogleProject, proxyUrlBase: String): Map[ServiceName, URL] =
    appResources.services.map { service =>
      (service.config.name,
       new URL(s"${proxyUrlBase}google/v1/apps/${project.value}/${appName.value}/${service.config.name.value}"))
    }.toMap
}

sealed abstract class AppStatus
object AppStatus {
  case object Unspecified extends AppStatus {
    override def toString: String = "STATUS_UNSPECIFIED"
  }

  case object Running extends AppStatus {
    override def toString: String = "RUNNING"
  }

  case object Error extends AppStatus {
    override def toString: String = "ERROR"
  }

  case object Deleting extends AppStatus {
    override def toString: String = "DELETING"
  }

  case object Deleted extends AppStatus {
    override def toString: String = "DELETED"
  }

  final case object Precreating extends AppStatus {
    override def toString: String = "PRECREATING"
  }

  final case object Predeleting extends AppStatus {
    override def toString: String = "PREDELETING"
  }

  final case object Provisioning extends AppStatus {
    override def toString: String = "PROVISIONING"
  }

  final case object PreStopping extends AppStatus {
    override def toString: String = "PRESTOPPING"
  }

  final case object Stopping extends AppStatus {
    override def toString: String = "STOPPING"
  }

  final case object Stopped extends AppStatus {
    override def toString: String = "STOPPED"
  }

  final case object PreStarting extends AppStatus {
    override def toString: String = "PRESTARTING"
  }

  final case object Starting extends AppStatus {
    override def toString: String = "STARTING"
  }

  def values: Set[AppStatus] = sealerate.values[AppStatus]
  def stringToObject: Map[String, AppStatus] = values.map(v => v.toString -> v).toMap

  val deletableStatuses: Set[AppStatus] =
    Set(Unspecified, Running, Error)

  val stoppableStatuses: Set[AppStatus] =
    Set(Running, Starting)

  val startableStatuses: Set[AppStatus] =
    Set(Stopped, Stopping)

  val monitoredStatuses: Set[AppStatus] =
    Set(Deleting, Provisioning)
}

final case class KubernetesService(id: ServiceId, config: ServiceConfig)
final case class ServiceId(id: Long) extends AnyVal
final case class ServiceConfig(name: ServiceName, kind: KubernetesServiceKindName)
final case class KubernetesServiceKindName(value: String) extends AnyVal

final case class KubernetesRuntimeConfig(numNodes: NumNodes, machineType: MachineTypeName, autoscalingEnabled: Boolean)
final case class NumNodepools(value: Int) extends AnyVal

final case class NodepoolNotFoundException(nodepoolLeoId: NodepoolLeoId) extends Exception {
  override def getMessage: String = s"nodepool with id ${nodepoolLeoId} not found"
}

final case class DefaultNodepoolNotFoundException(clusterId: KubernetesClusterLeoId) extends Exception {
  override def getMessage: String = s"Unable to find default nodepool for cluster with id ${clusterId}"
}
