package org.broadinstitute.dsde.workbench.leonardo
package util

import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleIamDAO
import org.broadinstitute.dsde.workbench.google2.DiskName
import org.broadinstitute.dsde.workbench.google2.GKEModels.NodepoolName
import org.broadinstitute.dsde.workbench.google2.KubernetesModels.{KubernetesPodStatus, PodStatus}
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName._
import org.broadinstitute.dsde.workbench.google2.mock._
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.KubernetesTestData.{makeKubeCluster, makeNodepool}
import org.broadinstitute.dsde.workbench.leonardo.config.Config
import org.broadinstitute.dsde.workbench.leonardo.dao.{MockAppDAO, MockAppDescriptorDAO}
import org.broadinstitute.dsde.workbench.leonardo.db.{kubernetesClusterQuery, nodepoolQuery, TestComponent}
import org.broadinstitute.dsde.workbench.leonardo.http.dbioToIO
import org.broadinstitute.dsp.Release
import org.broadinstitute.dsp.mocks._
import org.scalatest.flatspec.AnyFlatSpecLike

import java.nio.file.Files
import java.util.Base64
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._

class GKEInterpreterSpec extends AnyFlatSpecLike with TestComponent with LeonardoTestSuite {
  val googleIamDao = new MockGoogleIamDAO

  val vpcInterp =
    new VPCInterpreter[IO](Config.vpcInterpreterConfig,
                           FakeGoogleResourceService,
                           FakeGoogleComputeService,
                           new MockComputePollOperation)

  val gkeInterp =
    new GKEInterpreter[IO](Config.gkeInterpConfig,
                           vpcInterp,
                           MockGKEService,
                           MockKubernetesService,
                           MockHelm,
                           MockAppDAO,
                           credentials,
                           googleIamDao,
                           MockAppDescriptorDAO,
                           blocker,
                           nodepoolLock.unsafeRunSync())

  "GKEInterpreter" should "create a nodepool with autoscaling" in isolatedDbTest {
    val savedCluster1 = makeKubeCluster(1).save()
    val minNodes = 0
    val maxNodes = 2
    val savedNodepool1 = makeNodepool(1, savedCluster1.id)
      .copy(autoscalingEnabled = true,
            autoscalingConfig = Some(AutoscalingConfig(AutoscalingMin(minNodes), AutoscalingMax(maxNodes))))
      .save()

    val googleNodepool = gkeInterp.buildGoogleNodepool(savedNodepool1)
    googleNodepool.getAutoscaling.getEnabled shouldBe true
    googleNodepool.getAutoscaling.getMinNodeCount shouldBe minNodes
    googleNodepool.getAutoscaling.getMaxNodeCount shouldBe maxNodes
  }

  it should "get a helm auth context" in {
    val googleCluster = com.google.container.v1.Cluster
      .newBuilder()
      .setEndpoint("1.2.3.4")
      .setMasterAuth(
        com.google.container.v1.MasterAuth
          .newBuilder()
          .setClusterCaCertificate(Base64.getEncoder.encodeToString("ca_cert".getBytes()))
      )
      .build

    val authContext =
      gkeInterp.getHelmAuthContext(googleCluster, makeKubeCluster(1), NamespaceName("ns")).unsafeRunSync()

    authContext.namespace.asString shouldBe "ns"
    authContext.kubeApiServer.asString shouldBe "https://1.2.3.4"
    authContext.kubeToken.asString shouldBe "accessToken"
    Files.exists(authContext.caCertFile.path) shouldBe true
    Files.readAllLines(authContext.caCertFile.path).asScala.mkString shouldBe "ca_cert"
  }

  it should "build Galaxy override values string" in {
    val savedCluster1 = makeKubeCluster(1)
    val savedDisk1 = makePersistentDisk(Some(DiskName("disk1")), Some(FormattedBy.Galaxy))
    val result = gkeInterp.buildGalaxyChartOverrideValuesString(
      AppName("app1"),
      Release("app1-galaxy-rls"),
      savedCluster1,
      NodepoolName("pool1"),
      userEmail,
      Map("WORKSPACE_NAME" -> "test-workspace", "WORKSPACE_BUCKET" -> "gs://test-bucket"),
      ServiceAccountName("app1-galaxy-ksa"),
      NamespaceName("ns"),
      savedDisk1,
      None
    )

    result.mkString(",") shouldBe """nfs.storageClass.name=nfs-app1-galaxy-rls,cvmfs.repositories.cvmfs-gxy-data-app1-galaxy-rls=data.galaxyproject.org,cvmfs.repositories.cvmfs-gxy-main-app1-galaxy-rls=main.galaxyproject.org,cvmfs.cache.alienCache.storageClass=nfs-app1-galaxy-rls,galaxy.persistence.storageClass=nfs-app1-galaxy-rls,galaxy.cvmfs.data.pvc.storageClassName=cvmfs-gxy-data-app1-galaxy-rls,galaxy.cvmfs.main.pvc.storageClassName=cvmfs-gxy-main-app1-galaxy-rls,galaxy.nodeSelector.cloud\.google\.com/gke-nodepool=pool1,nfs.nodeSelector.cloud\.google\.com/gke-nodepool=pool1,galaxy.configs.job_conf\.yml.runners.k8s.k8s_node_selector=cloud.google.com/gke-nodepool: pool1,galaxy.ingress.path=/proxy/google/v1/apps/dsp-leo-test1/app1/galaxy,galaxy.ingress.annotations.nginx\.ingress\.kubernetes\.io/proxy-redirect-from=https://1455694897.jupyter.firecloud.org,galaxy.ingress.annotations.nginx\.ingress\.kubernetes\.io/proxy-redirect-to=https://leo,galaxy.ingress.hosts[0]=1455694897.jupyter.firecloud.org,galaxy.ingress.tls[0].hosts[0]=1455694897.jupyter.firecloud.org,galaxy.ingress.tls[0].secretName=tls-secret,galaxy.configs.galaxy\.yml.galaxy.single_user=user1@example.com,galaxy.configs.galaxy\.yml.galaxy.admin_users=user1@example.com,galaxy.terra.launch.workspace=test-workspace,galaxy.terra.launch.namespace=dsp-leo-test1,galaxy.configs.file_sources_conf\.yml[0].api_url=https://firecloud-orchestration.dsde-dev.broadinstitute.org/api/,galaxy.configs.file_sources_conf\.yml[0].drs_url=https://us-central1-broad-dsde-dev.cloudfunctions.net/martha_v3,galaxy.configs.file_sources_conf\.yml[0].doc=test-workspace,galaxy.configs.file_sources_conf\.yml[0].id=test-workspace,galaxy.configs.file_sources_conf\.yml[0].workspace=test-workspace,galaxy.configs.file_sources_conf\.yml[0].namespace=dsp-leo-test1,galaxy.configs.file_sources_conf\.yml[0].type=anvil,galaxy.configs.file_sources_conf\.yml[0].on_anvil=True,galaxy.rbac.enabled=false,galaxy.rbac.serviceAccount=app1-galaxy-ksa,rbac.serviceAccount=app1-galaxy-ksa,persistence.nfs.name=ns-nfs-disk,persistence.nfs.persistentVolume.extraSpec.gcePersistentDisk.pdName=disk1,persistence.nfs.size=250Gi,persistence.postgres.name=ns-postgres-disk,galaxy.postgresql.galaxyDatabasePassword=replace-me,persistence.postgres.persistentVolume.extraSpec.gcePersistentDisk.pdName=ns-gxy-postres-disk,persistence.postgres.size=10Gi,nfs.persistence.existingClaim=ns-nfs-disk-pvc,nfs.persistence.size=250Gi,galaxy.postgresql.persistence.existingClaim=ns-postgres-disk-pvc,galaxy.persistence.size=200Gi,configs.WORKSPACE_NAME=test-workspace,extraEnv[0].name=WORKSPACE_NAME,extraEnv[0].valueFrom.configMapKeyRef.name=app1-galaxy-rls-galaxykubeman-configs,extraEnv[0].valueFrom.configMapKeyRef.key=WORKSPACE_NAME,configs.WORKSPACE_BUCKET=gs://test-bucket,extraEnv[1].name=WORKSPACE_BUCKET,extraEnv[1].valueFrom.configMapKeyRef.name=app1-galaxy-rls-galaxykubeman-configs,extraEnv[1].valueFrom.configMapKeyRef.key=WORKSPACE_BUCKET"""
  }

  it should "build Galaxy override values string with restore info" in {
    val savedCluster1 = makeKubeCluster(1)
    val savedDisk1 = makePersistentDisk(Some(DiskName("disk1")), Some(FormattedBy.Galaxy))
    val result = gkeInterp.buildGalaxyChartOverrideValuesString(
      AppName("app1"),
      Release("app1-galaxy-rls"),
      savedCluster1,
      NodepoolName("pool1"),
      userEmail,
      Map("WORKSPACE_NAME" -> "test-workspace", "WORKSPACE_BUCKET" -> "gs://test-bucket"),
      ServiceAccountName("app1-galaxy-ksa"),
      NamespaceName("ns"),
      savedDisk1,
      Some(
        GalaxyRestore(PvcId("galaxy-pvc-id"), PvcId("cvmfs-pvc-id"), AppId(123))
      )
    )

    result.mkString(",") shouldBe """nfs.storageClass.name=nfs-app1-galaxy-rls,cvmfs.repositories.cvmfs-gxy-data-app1-galaxy-rls=data.galaxyproject.org,cvmfs.repositories.cvmfs-gxy-main-app1-galaxy-rls=main.galaxyproject.org,cvmfs.cache.alienCache.storageClass=nfs-app1-galaxy-rls,galaxy.persistence.storageClass=nfs-app1-galaxy-rls,galaxy.cvmfs.data.pvc.storageClassName=cvmfs-gxy-data-app1-galaxy-rls,galaxy.cvmfs.main.pvc.storageClassName=cvmfs-gxy-main-app1-galaxy-rls,galaxy.nodeSelector.cloud\.google\.com/gke-nodepool=pool1,nfs.nodeSelector.cloud\.google\.com/gke-nodepool=pool1,galaxy.configs.job_conf\.yml.runners.k8s.k8s_node_selector=cloud.google.com/gke-nodepool: pool1,galaxy.ingress.path=/proxy/google/v1/apps/dsp-leo-test1/app1/galaxy,galaxy.ingress.annotations.nginx\.ingress\.kubernetes\.io/proxy-redirect-from=https://1455694897.jupyter.firecloud.org,galaxy.ingress.annotations.nginx\.ingress\.kubernetes\.io/proxy-redirect-to=https://leo,galaxy.ingress.hosts[0]=1455694897.jupyter.firecloud.org,galaxy.ingress.tls[0].hosts[0]=1455694897.jupyter.firecloud.org,galaxy.ingress.tls[0].secretName=tls-secret,galaxy.configs.galaxy\.yml.galaxy.single_user=user1@example.com,galaxy.configs.galaxy\.yml.galaxy.admin_users=user1@example.com,galaxy.terra.launch.workspace=test-workspace,galaxy.terra.launch.namespace=dsp-leo-test1,galaxy.configs.file_sources_conf\.yml[0].api_url=https://firecloud-orchestration.dsde-dev.broadinstitute.org/api/,galaxy.configs.file_sources_conf\.yml[0].drs_url=https://us-central1-broad-dsde-dev.cloudfunctions.net/martha_v3,galaxy.configs.file_sources_conf\.yml[0].doc=test-workspace,galaxy.configs.file_sources_conf\.yml[0].id=test-workspace,galaxy.configs.file_sources_conf\.yml[0].workspace=test-workspace,galaxy.configs.file_sources_conf\.yml[0].namespace=dsp-leo-test1,galaxy.configs.file_sources_conf\.yml[0].type=anvil,galaxy.configs.file_sources_conf\.yml[0].on_anvil=True,galaxy.rbac.enabled=false,galaxy.rbac.serviceAccount=app1-galaxy-ksa,rbac.serviceAccount=app1-galaxy-ksa,persistence.nfs.name=ns-nfs-disk,persistence.nfs.persistentVolume.extraSpec.gcePersistentDisk.pdName=disk1,persistence.nfs.size=250Gi,persistence.postgres.name=ns-postgres-disk,galaxy.postgresql.galaxyDatabasePassword=replace-me,persistence.postgres.persistentVolume.extraSpec.gcePersistentDisk.pdName=ns-gxy-postres-disk,persistence.postgres.size=10Gi,nfs.persistence.existingClaim=ns-nfs-disk-pvc,nfs.persistence.size=250Gi,galaxy.postgresql.persistence.existingClaim=ns-postgres-disk-pvc,galaxy.persistence.size=200Gi,configs.WORKSPACE_NAME=test-workspace,extraEnv[0].name=WORKSPACE_NAME,extraEnv[0].valueFrom.configMapKeyRef.name=app1-galaxy-rls-galaxykubeman-configs,extraEnv[0].valueFrom.configMapKeyRef.key=WORKSPACE_NAME,configs.WORKSPACE_BUCKET=gs://test-bucket,extraEnv[1].name=WORKSPACE_BUCKET,extraEnv[1].valueFrom.configMapKeyRef.name=app1-galaxy-rls-galaxykubeman-configs,extraEnv[1].valueFrom.configMapKeyRef.key=WORKSPACE_BUCKET,restore.persistence.nfs.galaxy.pvcID=galaxy-pvc-id,restore.persistence.nfs.cvmfsCache.pvcID=cvmfs-pvc-id,galaxy.persistence.existingClaim=app1-galaxy-rls-galaxy-pvc,cvmfs.cache.alienCache.existingClaim=app1-galaxy-rls-cvmfs-alien-cache-pvc""".stripMargin
  }

  it should "check if a pod is done" in {
    gkeInterp.isPodDone(KubernetesPodStatus(PodName("pod1"), PodStatus.Succeeded)) shouldBe true
    gkeInterp.isPodDone(KubernetesPodStatus(PodName("pod2"), PodStatus.Pending)) shouldBe false
    gkeInterp.isPodDone(KubernetesPodStatus(PodName("pod3"), PodStatus.Failed)) shouldBe true
    gkeInterp.isPodDone(KubernetesPodStatus(PodName("pod4"), PodStatus.Unknown)) shouldBe false
    gkeInterp.isPodDone(KubernetesPodStatus(PodName("pod5"), PodStatus.Running)) shouldBe false
  }

  it should "deleteAndPollCluster properly" in isolatedDbTest {
    val res = for {
      savedCluster <- IO(makeKubeCluster(1).save())
      m = DeleteClusterParams(
        savedCluster.id,
        savedCluster.googleProject
      )
      _ <- gkeInterp.deleteAndPollCluster(m)
      clusterOpt <- kubernetesClusterQuery.getMinimalClusterById(savedCluster.id).transaction
    } yield {
      clusterOpt.get.status shouldBe KubernetesClusterStatus.Deleted
    }

    res.unsafeRunSync()
  }

  it should "deleteAndPollNodepool properly" in isolatedDbTest {
    val res = for {
      savedCluster <- IO(makeKubeCluster(1).save())
      savedNodepool <- IO(makeNodepool(1, savedCluster.id).save())
      m = DeleteNodepoolParams(savedNodepool.id, savedCluster.googleProject)
      _ <- gkeInterp.deleteAndPollNodepool(m)
      nodepoolOpt <- nodepoolQuery.getMinimalById(savedNodepool.id).transaction
    } yield {
      nodepoolOpt.get.status shouldBe NodepoolStatus.Deleted
    }

    res.unsafeRunSync()
  }
}
