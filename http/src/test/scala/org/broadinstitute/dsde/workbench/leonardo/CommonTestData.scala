package org.broadinstitute.dsde.workbench.leonardo

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Date, UUID}
import org.broadinstitute.dsde.workbench.leonardo
import akka.http.scaladsl.model.headers.{HttpCookiePair, OAuth2BearerToken}
import cats.effect.IO
import cats.mtl.Ask
import com.google.auth.oauth2.{AccessToken, GoogleCredentials}
import com.google.cloud.compute.v1._
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.google2.mock.BaseFakeGoogleStorage
import org.broadinstitute.dsde.workbench.google2.{
  DataprocRole,
  DiskName,
  InstanceName,
  MachineTypeName,
  NetworkName,
  OperationName,
  SubnetworkName,
  ZoneName
}
import org.broadinstitute.dsde.workbench.leonardo.ContainerRegistry.DockerHub
import org.broadinstitute.dsde.workbench.leonardo.RuntimeImageType.{CryptoDetector, Jupyter, Proxy, RStudio, VM, Welder}
import org.broadinstitute.dsde.workbench.leonardo.auth.{MockPetClusterServiceAccountProvider, WhitelistAuthProvider}
import org.broadinstitute.dsde.workbench.leonardo.config._
import org.broadinstitute.dsde.workbench.leonardo.dao.MockSamDAO
import org.broadinstitute.dsde.workbench.leonardo.http.{
  userScriptStartupOutputUriMetadataKey,
  CreateRuntime2Request,
  RuntimeConfigRequest
}
import org.broadinstitute.dsde.workbench.model.google.{
  GoogleProject,
  ServiceAccountKey,
  ServiceAccountKeyId,
  ServiceAccountPrivateKeyData,
  _
}
import org.broadinstitute.dsde.workbench.model.{IP, TraceId, UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.leonardo.db.ClusterRecord

import scala.concurrent.duration._

object CommonTestData {
// values common to multiple tests, to reduce boilerplate
  val name0 = RuntimeName("clustername0")
  val name1 = RuntimeName("clustername1")
  val name2 = RuntimeName("clustername2")
  val name3 = RuntimeName("clustername3")
  val runtimeSamResource = RuntimeSamResourceId("067e2867-5d4a-47f3-a53c-fd711529b287")
  val project = GoogleProject("dsp-leo-test")
  val project2 = GoogleProject("dsp-leo-test-2")
  val userEmail = WorkbenchEmail("user1@example.com")
  val userEmail2 = WorkbenchEmail("user2@example.com")
  val userInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), userEmail, 0)
  val serviceAccountEmail = WorkbenchEmail("pet-1234567890@test-project.iam.gserviceaccount.com")
  val unauthorizedEmail = WorkbenchEmail("somecreep@example.com")
  val unauthorizedUserInfo =
    UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("somecreep"), unauthorizedEmail, 0)
  val credentials =
    GoogleCredentials.create(new AccessToken("accessToken", new Date(1000000000000000L))) // don't refresh this token
  val jupyterExtensionBucket = GcsBucketName("bucket-name")
  val jupyterExtensionObject = GcsObjectName("extension")
  val userJupyterExtensionConfig =
    UserJupyterExtensionConfig(nbExtensions =
      Map("notebookExtension" -> s"gs://${jupyterExtensionBucket.value}/${jupyterExtensionObject.value}")
    )
  val jupyterUserScriptBucketName = GcsBucketName("userscript_bucket")
  val jupyterUserScriptObjectName = GcsObjectName("userscript.sh")
  val jupyterUserScriptUri = UserScriptPath.Gcs(GcsPath(jupyterUserScriptBucketName, jupyterUserScriptObjectName))
  val jupyterStartUserScriptBucketName = GcsBucketName("startscript_bucket")
  val jupyterStartUserScriptObjectName = GcsObjectName("startscript.sh")
  val jupyterStartUserScriptUri =
    UserScriptPath.Gcs(GcsPath(jupyterStartUserScriptBucketName, jupyterStartUserScriptObjectName))
  val serviceAccountKey = ServiceAccountKey(ServiceAccountKeyId("123"),
                                            ServiceAccountPrivateKeyData("abcdefg"),
                                            Some(Instant.now),
                                            Some(Instant.now.plusSeconds(300)))
  val initBucketName = GcsBucketName("init-bucket-path")
  val stagingBucketName = GcsBucketName("staging-bucket-name")
  val autopause = true
  val autopauseThreshold = 30
  val defaultScopes = Set(
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/userinfo.profile",
    "https://www.googleapis.com/auth/bigquery",
    "https://www.googleapis.com/auth/source.read_only"
  )
  val zone = ZoneName("us-central1-a")
  val diskName = DiskName("disk-name")
  val googleId = GoogleId("google-id")
  val diskSamResource = PersistentDiskSamResourceId("disk-resource-id")
  val diskSize = DiskSize(250)
  val diskType = leonardo.DiskType.Standard
  val blockSize = BlockSize(4096)

  val config = ConfigFactory.parseResources("reference.conf").withFallback(ConfigFactory.load()).resolve()
  val applicationConfig = Config.applicationConfig
  val whitelistAuthConfig = config.getConfig("auth.whitelistProviderConfig")
  val whitelist = config.as[Set[String]]("auth.whitelistProviderConfig.whitelist").map(_.toLowerCase)
  // Let's not use this pattern and directly use `Config.???` going forward :)
  // By using Config.xxx, we'll be actually testing our Config.scala code as well
  val dataprocConfig = Config.dataprocConfig
  val vpcConfig = Config.vpcConfig
  val imageConfig = Config.imageConfig
  val welderConfig = Config.welderConfig
  val clusterFilesConfig = Config.securityFilesConfig
  val clusterResourcesConfig = Config.clusterResourcesConfig
  val proxyConfig = Config.proxyConfig
  val swaggerConfig = Config.swaggerConfig
  val autoFreezeConfig = Config.autoFreezeConfig
  val clusterToolConfig = Config.clusterToolMonitorConfig
  val proxyUrlBase = proxyConfig.proxyUrlBase
  val clusterBucketConfig = Config.clusterBucketConfig
  val contentSecurityPolicy = Config.contentSecurityPolicy
  val refererConfig = Config.refererConfig
  val leoKubernetesConfig = Config.leoKubernetesConfig
  val singleNodeDefaultMachineConfig = dataprocConfig.runtimeConfigDefaults
  val singleNodeDefaultMachineConfigRequest = RuntimeConfigRequest.DataprocConfig(
    Some(singleNodeDefaultMachineConfig.numberOfWorkers),
    Some(singleNodeDefaultMachineConfig.masterMachineType),
    Some(singleNodeDefaultMachineConfig.masterDiskSize),
    workerMachineType = None,
    workerDiskSize = None,
    numberOfWorkerLocalSSDs = None,
    numberOfPreemptibleWorkers = None,
    properties = Map.empty
  )

  val mockSamDAO = new MockSamDAO
  val mockGoogle2StorageDAO = new BaseFakeGoogleStorage

  val tokenAge = 500000
  val defaultUserInfo =
    UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), tokenAge)
  val tokenName = "LeoToken"
  val tokenValue = "accessToken"
  val tokenCookie = HttpCookiePair(tokenName, tokenValue)

  val validRefererUri = "http://example.com/this/is/a/test"

  val networkName = NetworkName("default")
  val subNetworkName = SubnetworkName("default")
  val ipRange = IpRange("0.0.0.0/20")
  val networkFields = NetworkFields(networkName, subNetworkName, ipRange)

  val serviceAccount = WorkbenchEmail("testServiceAccount@example.com")

  val auditInfo = AuditInfo(userEmail, Instant.now(), None, Instant.now())
  val olderRuntimeAuditInfo = AuditInfo(userEmail, Instant.now().minus(1, ChronoUnit.DAYS), None, Instant.now())
  val jupyterImage = RuntimeImage(Jupyter, "init-resources/jupyter-base:latest", Instant.now)
  val rstudioImage = RuntimeImage(RStudio, "rocker/tidyverse:latest", Instant.now)
  val welderImage = RuntimeImage(Welder, "welder/welder:latest", Instant.now)
  val proxyImage = RuntimeImage(Proxy, imageConfig.proxyImage.imageUrl, Instant.now)
  val customDataprocImage = RuntimeImage(VM, "custom_dataproc", Instant.now)
  val cryptoDetectorImage = RuntimeImage(CryptoDetector, "crypto/crypto:0.0.1", Instant.now)

  val clusterResourceConstraints = RuntimeResourceConstraints(MemorySize.fromMb(3584))

  def makeAsyncRuntimeFields(index: Int): AsyncRuntimeFields =
    AsyncRuntimeFields(
      GoogleId(UUID.randomUUID().toString),
      OperationName("operationName" + index.toString),
      GcsBucketName("stagingbucketname" + index.toString),
      Some(IP("numbers.and.dots"))
    )
  val defaultMachineType = MachineTypeName("n1-standard-4")
  val defaultDataprocRuntimeConfig =
    RuntimeConfig.DataprocConfig(0, MachineTypeName("n1-standard-4"), DiskSize(500), None, None, None, None, Map.empty)

  val defaultCreateRuntimeRequest = CreateRuntime2Request(
    Map("lbl1" -> "true"),
    None,
    Some(UserScriptPath.Gcs(GcsPath(GcsBucketName("bucket"), GcsObjectName("script.sh")))),
    Some(RuntimeConfigRequest.GceConfig(Some(MachineTypeName("n1-standard-4")), Some(DiskSize(100)))),
    None,
    Some(true),
    Some(30.minutes),
    None,
    Some(ContainerImage("myrepo/myimage", DockerHub)),
    Some(DockerHub),
    Set.empty,
    Map.empty
  )
  val defaultGceRuntimeConfig =
    RuntimeConfig.GceConfig(MachineTypeName("n1-standard-4"), DiskSize(500), bootDiskSize = Some(DiskSize(50)))
  val defaultRuntimeConfigRequest =
    RuntimeConfigRequest.DataprocConfig(Some(0),
                                        Some(MachineTypeName("n1-standard-4")),
                                        Some(DiskSize(500)),
                                        None,
                                        None,
                                        None,
                                        None,
                                        Map.empty[String, String])
  val gceRuntimeConfig =
    RuntimeConfig.GceConfig(MachineTypeName("n1-standard-4"), DiskSize(500), bootDiskSize = Some(DiskSize(50)))
  val gceWithPdRuntimeConfig =
    RuntimeConfig.GceWithPdConfig(MachineTypeName("n1-standard-4"), Some(DiskId(1234)), DiskSize(50))

  def makeCluster(index: Int): Runtime = {
    val clusterName = RuntimeName("clustername" + index.toString)
    Runtime(
      id = -1,
      runtimeName = clusterName,
      samResource = runtimeSamResource,
      googleProject = project,
      serviceAccount = serviceAccount,
      asyncRuntimeFields = Some(makeAsyncRuntimeFields(index)),
      auditInfo = auditInfo,
      kernelFoundBusyDate = None,
      proxyUrl = Runtime.getProxyUrl(proxyUrlBase, project, clusterName, Set(jupyterImage), Map.empty),
      status = RuntimeStatus.Unknown,
      labels = Map(),
      jupyterUserScriptUri = None,
      jupyterStartUserScriptUri = None,
      errors = List.empty,
      dataprocInstances = Set.empty,
      userJupyterExtensionConfig = None,
      autopauseThreshold = 30,
      defaultClientId = Some("defaultClientId"),
      allowStop = false,
      runtimeImages = Set(jupyterImage, welderImage),
      scopes = defaultScopes,
      welderEnabled = false,
      customEnvironmentVariables = Map.empty,
      runtimeConfigId = RuntimeConfigId(-1),
      patchInProgress = false
    )
  }

  val testCluster = Runtime(
    id = -1,
    runtimeName = name1,
    samResource = runtimeSamResource,
    googleProject = project,
    serviceAccount = serviceAccount,
    asyncRuntimeFields = Some(
      AsyncRuntimeFields(GoogleId(UUID.randomUUID().toString), OperationName("op"), stagingBucketName, None)
    ),
    auditInfo = AuditInfo(userEmail, Instant.now(), None, Instant.now()),
    kernelFoundBusyDate = None,
    proxyUrl = Runtime.getProxyUrl(proxyUrlBase, project, name1, Set(jupyterImage), Map.empty),
    status = RuntimeStatus.Unknown,
    labels = Map(),
    jupyterUserScriptUri = Some(UserScriptPath.Gcs(GcsPath(GcsBucketName("bucket-name"), GcsObjectName("userScript")))),
    jupyterStartUserScriptUri =
      Some(UserScriptPath.Gcs(GcsPath(GcsBucketName("bucket-name"), GcsObjectName("startScript")))),
    errors = List.empty,
    dataprocInstances = Set.empty,
    userJupyterExtensionConfig =
      Some(UserJupyterExtensionConfig(nbExtensions = Map("notebookExtension" -> "gs://bucket-name/extension"))),
    autopauseThreshold = if (autopause) autopauseThreshold else 0,
    defaultClientId = Some("clientId"),
    allowStop = false,
    runtimeImages = Set(jupyterImage, welderImage, proxyImage, cryptoDetectorImage),
    scopes = defaultScopes,
    welderEnabled = true,
    customEnvironmentVariables = Map.empty,
    runtimeConfigId = RuntimeConfigId(-1),
    patchInProgress = false
  )

  val testClusterRecord = ClusterRecord(
    id = -1,
    runtimeName = name1,
    internalId = runtimeSamResource.resourceId,
    googleProject = project,
    googleId = testCluster.asyncRuntimeFields.map(_.googleId),
    operationName = testCluster.asyncRuntimeFields.map(_.operationName.value),
    status = testCluster.status,
    auditInfo = testCluster.auditInfo,
    kernelFoundBusyDate = None,
    hostIp = None,
    jupyterUserScriptUri = Some(UserScriptPath.Gcs(GcsPath(GcsBucketName("bucket-name"), GcsObjectName("userScript")))),
    jupyterStartUserScriptUri =
      Some(UserScriptPath.Gcs(GcsPath(GcsBucketName("bucket-name"), GcsObjectName("startScript")))),
    autopauseThreshold = if (autopause) autopauseThreshold else 0,
    defaultClientId = Some("clientId"),
    initBucket = Some(initBucketName.value),
    serviceAccountInfo = serviceAccount,
    stagingBucket = Some(stagingBucketName.value),
    welderEnabled = true,
    customClusterEnvironmentVariables = Map.empty,
    runtimeConfigId = RuntimeConfigId(-1),
    deletedFrom = None
  )

  val readyInstance = Instance
    .newBuilder()
    .setStatus("Running")
    .setMetadata(
      Metadata
        .newBuilder()
        .addItems(
          Items.newBuilder
            .setKey(userScriptStartupOutputUriMetadataKey)
            .setValue("gs://success/object")
            .build()
        )
        .build()
    )
    .addNetworkInterfaces(
      NetworkInterface
        .newBuilder()
        .addAccessConfigs(AccessConfig.newBuilder().setNatIP("fakeIP").build())
        .build()
    )
    .build()

  def makePersistentDisk(diskName: Option[DiskName] = None,
                         formattedBy: Option[FormattedBy] = None,
                         galaxyRestore: Option[GalaxyRestore] = None): PersistentDisk =
    PersistentDisk(
      DiskId(-1),
      project,
      zone,
      diskName.getOrElse(DiskName("disk")),
      Some(googleId),
      serviceAccount,
      diskSamResource,
      DiskStatus.Ready,
      auditInfo,
      diskSize,
      diskType,
      blockSize,
      formattedBy,
      galaxyRestore,
      Map.empty
    )

  // TODO look into parameterized tests so both provider impls can be tested
  // Also remove code duplication with LeonardoServiceSpec, TestLeoRoutes, and CommonTestData
  val serviceAccountProvider = new MockPetClusterServiceAccountProvider
  val whitelistAuthProvider = new WhitelistAuthProvider(whitelistAuthConfig, serviceAccountProvider)

  val userExtConfig = UserJupyterExtensionConfig(Map("nbExt1" -> "abc", "nbExt2" -> "def"),
                                                 Map("serverExt1" -> "pqr"),
                                                 Map("combinedExt1" -> "xyz"))

  val traceId = Ask.const[IO, TraceId](TraceId(UUID.randomUUID())) //we don't care much about traceId in unit tests, hence providing a constant UUID here

  def clusterServiceAccountFromProject(googleProject: GoogleProject): Option[WorkbenchEmail] =
    serviceAccountProvider.getClusterServiceAccount(userInfo, googleProject)(traceId).unsafeRunSync()

  def notebookServiceAccountFromProject(googleProject: GoogleProject): Option[WorkbenchEmail] =
    serviceAccountProvider.getNotebookServiceAccount(userInfo, googleProject)(traceId).unsafeRunSync()

  val masterInstance = DataprocInstance(
    DataprocInstanceKey(project, ZoneName("my-zone"), InstanceName("master-instance")),
    googleId = BigInt(12345),
    status = GceInstanceStatus.Running,
    ip = Some(IP("1.2.3.4")),
    dataprocRole = DataprocRole.Master,
    createdDate = Instant.now()
  )

  val workerInstance1 = DataprocInstance(
    DataprocInstanceKey(project, ZoneName("my-zone"), InstanceName("worker-instance-1")),
    googleId = BigInt(23456),
    status = GceInstanceStatus.Running,
    ip = Some(IP("1.2.3.5")),
    dataprocRole = DataprocRole.Worker,
    createdDate = Instant.now()
  )

  val workerInstance2 = DataprocInstance(
    DataprocInstanceKey(project, ZoneName("my-zone"), InstanceName("worker-instance-2")),
    googleId = BigInt(34567),
    status = GceInstanceStatus.Running,
    ip = Some(IP("1.2.3.6")),
    dataprocRole = DataprocRole.Worker,
    createdDate = Instant.now()
  )

  def modifyInstance(instance: DataprocInstance): DataprocInstance =
    instance.copy(key = modifyInstanceKey(instance.key), googleId = instance.googleId + 1)
  def modifyInstanceKey(instanceKey: DataprocInstanceKey): DataprocInstanceKey =
    instanceKey.copy(name = InstanceName(instanceKey.name.value + "_2"))
}

trait GcsPathUtils {
  def gcsPath(str: String): GcsPath =
    parseGcsPath(str).right.get
}
