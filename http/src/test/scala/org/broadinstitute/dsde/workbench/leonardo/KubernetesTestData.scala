package org.broadinstitute.dsde.workbench.leonardo

import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterName, NodepoolName}
import org.broadinstitute.dsde.workbench.google2.KubernetesModels.Protocol
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.{NamespaceName, ServiceName}
import org.broadinstitute.dsde.workbench.google2.{Location, MachineTypeName, RegionName}
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.model.IP
import org.broadinstitute.dsde.workbench.leonardo.http.{
  BatchNodepoolCreateRequest,
  CreateAppRequest,
  GetAppResponse,
  GetAppResult,
  ListAppResponse
}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsp.{ChartName, ChartVersion, Release}

object KubernetesTestData {
  val kubeName0 = KubernetesClusterName("clustername00")
  val kubeName1 = KubernetesClusterName("clustername01")

  val appSamId = AppSamResourceId("067e2867-5d4a-47f3-a53c-fd711529b289")
  val location = Location("us-central1-a")
  val region = RegionName("us-central1")

  val loadBalancerIp = IP("0.0.0.0")
  val apiServerIp = IP("1.2.3.4")
  val namespace0 = NamespaceName("namespace00")
  val namespace1 = NamespaceName("namespace01")

  val nodepoolName0 = NodepoolName("nodepoolname00")
  val nodepoolName1 = NodepoolName("nodepoolname01")

  val autoscalingConfig = AutoscalingConfig(AutoscalingMin(1), AutoscalingMax(2))

  val galaxyApp = AppType.Galaxy

  val galaxyChartName = ChartName("galaxy/galaxykubeman")
  val galaxyChartVersion = ChartVersion("0.8.0")
  val galaxyChart = Chart(galaxyChartName, galaxyChartVersion)

  val galaxyReleasePrefix = "gxy-release"

  val ingressChartName = ChartName("stable/nginx-ingress")
  val ingressChartVersion = ChartVersion("1.41.3")
  val ingressChart = Chart(ingressChartName, ingressChartVersion)

  val serviceKind = KubernetesServiceKindName("ClusterIP")
  val protocol = Protocol("TCP")

  val kubernetesRuntimeConfig = KubernetesRuntimeConfig(
    NumNodes(2),
    MachineTypeName("n1-standard-4"),
    true
  )

  val createAppRequest = CreateAppRequest(
    Some(kubernetesRuntimeConfig),
    AppType.Galaxy,
    None,
    Map.empty,
    Map.empty,
    None,
    List.empty
  )

  val batchNodepoolCreateRequest = BatchNodepoolCreateRequest(NumNodepools(100), Some(kubernetesRuntimeConfig), None)

  val testCluster = makeKubeCluster(1)
  val testNodepool = makeNodepool(1, testCluster.id)
  val testApp = makeApp(1, testNodepool.id)

  val getAppResponse =
    GetAppResponse.fromDbResult(
      GetAppResult(
        testCluster,
        testNodepool,
        testApp
      ),
      "https://leo/proxy/"
    )

  val listAppResponse =
    ListAppResponse
      .fromCluster(testCluster.copy(nodepools = List(testNodepool.copy(apps = List(testApp)))), "https://leo/proxy/")
      .toVector

  def makeNodepool(index: Int, clusterId: KubernetesClusterLeoId, prefix: String = "", isDefault: Boolean = false) = {
    val name = NodepoolName(prefix + "nodepoolname" + index)
    Nodepool(
      NodepoolLeoId(-1), //will be replaced
      clusterId,
      name,
      NodepoolStatus.Unspecified,
      auditInfo,
      MachineTypeName("n1-standard-4"),
      NumNodes(if (isDefault) 1 else 2),
      !isDefault,
      if (isDefault) None else Some(AutoscalingConfig(AutoscalingMin(0), AutoscalingMax(2))),
      List(),
      isDefault
    )
  }

  def makeKubeCluster(index: Int): KubernetesCluster = {
    val name = KubernetesClusterName("kubecluster" + index)
    val uniqueProject = GoogleProject(project.value + index)
    KubernetesCluster(
      KubernetesClusterLeoId(-1),
      uniqueProject,
      name,
      location,
      region,
      KubernetesClusterStatus.Unspecified,
      ingressChart,
      auditInfo,
      None,
      List(),
      List(makeNodepool(index, KubernetesClusterLeoId(-1), "cluster", true))
    )
  }

  def makeNamespace(index: Int, prefix: String = ""): Namespace = {
    val name = NamespaceName(prefix + "namespace" + index)
    Namespace(NamespaceId(-1), name)
  }

  def makeApp(index: Int,
              nodepoolId: NodepoolLeoId,
              customEnvironmentVariables: Map[String, String] = Map.empty): App = {
    val name = AppName("app" + index)
    val namespace = makeNamespace(index, "app")
    App(
      AppId(-1),
      nodepoolId,
      galaxyApp,
      name,
      AppStatus.Unspecified,
      galaxyChart,
      Release(galaxyReleasePrefix + index),
      appSamId,
      serviceAccountEmail,
      auditInfo,
      Map.empty,
      AppResources(
        namespace,
        None,
        List.empty,
        Option.empty
      ),
      List.empty,
      customEnvironmentVariables,
      None,
      List.empty
    )
  }

  def makeService(index: Int): KubernetesService = {
    val name = ServiceName("service" + index)
    KubernetesService(
      ServiceId(-1),
      ServiceConfig(name, serviceKind)
    )
  }
}
