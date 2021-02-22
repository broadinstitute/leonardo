package org.broadinstitute.dsde.workbench.leonardo
package http
package api

import java.io.ByteArrayInputStream
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.{`Set-Cookie`, HttpCookiePair}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.GoogleStorageDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.MockGoogleDiskService
import org.broadinstitute.dsde.workbench.google2.mock.{
  FakeGoogleComputeService,
  FakeGoogleDataprocService,
  FakeGoogleResourceService,
  FakeGoogleStorageInterpreter,
  MockComputePollOperation
}
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.config.Config
import org.broadinstitute.dsde.workbench.leonardo.dao.google.MockGoogleOAuth2Service
import org.broadinstitute.dsde.workbench.leonardo.dao.{MockDockerDAO, MockJupyterDAO, MockWelderDAO}
import org.broadinstitute.dsde.workbench.leonardo.db.TestComponent
import org.broadinstitute.dsde.workbench.leonardo.dns.{KubernetesDnsCache, RuntimeDnsCache}
import org.broadinstitute.dsde.workbench.leonardo.http.service._
import org.broadinstitute.dsde.workbench.leonardo.http.service.MockDiskServiceInterp
import org.broadinstitute.dsde.workbench.leonardo.util._
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.scalactic.source.Position
import org.scalatest.concurrent.PatienceConfiguration.Interval
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.duration._
import scala.util.matching.Regex

trait TestLeoRoutes {
  this: ScalatestRouteTest with Matchers with ScalaFutures with LeonardoTestSuite with TestComponent =>
  // Set up the mock directoryDAO to have the Google group used to grant permission to users
  // to pull the custom dataproc image
  val mockGoogleDirectoryDAO = {
    val mockGoogleDirectoryDAOPatience = PatienceConfig(timeout = scaled(Span(30, Seconds)))
    val dao = new MockGoogleDirectoryDAO()
    dao
      .createGroup(Config.googleGroupsConfig.dataprocImageProjectGroupName,
                   Config.googleGroupsConfig.dataprocImageProjectGroupEmail,
                   Option(dao.lockedDownGroupSettings))
      .futureValue(Interval(Span(30, Seconds)))(mockGoogleDirectoryDAOPatience, Position.here)
    dao
  }

  val mockGoogleIamDAO = new MockGoogleIamDAO
  val mockPetGoogleStorageDAO: String => GoogleStorageDAO = _ => {
    val petMock = new MockGoogleStorageDAO
    petMock.buckets += jupyterUserScriptBucketName -> Set(
      (jupyterUserScriptObjectName, new ByteArrayInputStream("foo".getBytes()))
    )
    petMock.buckets += jupyterStartUserScriptBucketName -> Set(
      (jupyterStartUserScriptObjectName, new ByteArrayInputStream("foo".getBytes()))
    )
    petMock.buckets += jupyterExtensionBucket -> Set(
      (jupyterExtensionObject, new ByteArrayInputStream("foo".getBytes()))
    )
    petMock
  }
  // Route tests don't currently do cluster monitoring, so use NoopActor
  val bucketHelperConfig =
    BucketHelperConfig(imageConfig, welderConfig, proxyConfig, clusterFilesConfig, clusterResourcesConfig)
  val bucketHelper =
    new BucketHelper[IO](bucketHelperConfig, mockGoogle2StorageDAO, serviceAccountProvider, blocker)
  val vpcInterp = new VPCInterpreter[IO](Config.vpcInterpreterConfig,
                                         FakeGoogleResourceService,
                                         FakeGoogleComputeService,
                                         new MockComputePollOperation)
  val dataprocInterp =
    new DataprocInterpreter[IO](Config.dataprocInterpreterConfig,
                                bucketHelper,
                                vpcInterp,
                                FakeGoogleDataprocService,
                                FakeGoogleComputeService,
                                MockGoogleDiskService,
                                mockGoogleDirectoryDAO,
                                mockGoogleIamDAO,
                                FakeGoogleResourceService,
                                MockWelderDAO,
                                blocker)
  val gceInterp =
    new GceInterpreter[IO](Config.gceInterpreterConfig,
                           bucketHelper,
                           vpcInterp,
                           FakeGoogleComputeService,
                           MockGoogleDiskService,
                           MockWelderDAO,
                           blocker)
  val runtimeInstances = new RuntimeInstances[IO](dataprocInterp, gceInterp)

  val leoKubernetesService: LeoAppServiceInterp[IO] = new LeoAppServiceInterp[IO](
    whitelistAuthProvider,
    serviceAccountProvider,
    Config.leoKubernetesConfig,
    QueueFactory.makePublisherQueue()
  )

  val runtimeDnsCache = new RuntimeDnsCache[IO](proxyConfig, testDbRef, Config.runtimeDnsCacheConfig, blocker)
  val kubernetesDnsCache = new KubernetesDnsCache[IO](proxyConfig, testDbRef, Config.kubernetesDnsCacheConfig, blocker)

  val proxyService =
    new MockProxyService(proxyConfig,
                         MockJupyterDAO,
                         whitelistAuthProvider,
                         runtimeDnsCache,
                         kubernetesDnsCache,
                         MockGoogleOAuth2Service)
  val statusService =
    new StatusService(mockSamDAO, testDbRef, applicationConfig, pollInterval = 1.second)
  val timedUserInfo = defaultUserInfo.copy(tokenExpiresIn = tokenAge)
  val corsSupport = new CorsSupport(contentSecurityPolicy)
  val statusRoutes = new StatusRoutes(statusService)
  val userInfoDirectives = new MockUserInfoDirectives {
    override val userInfo: UserInfo = defaultUserInfo
  }
  val timedUserInfoDirectives = new MockUserInfoDirectives {
    override val userInfo: UserInfo = timedUserInfo
  }

  val runtimeService = new RuntimeServiceInterp(
    RuntimeServiceConfig(Config.proxyConfig.proxyUrlBase,
                         imageConfig,
                         autoFreezeConfig,
                         dataprocConfig,
                         Config.gceConfig),
    Config.persistentDiskConfig,
    whitelistAuthProvider,
    serviceAccountProvider,
    new MockDockerDAO,
    FakeGoogleStorageInterpreter,
    FakeGoogleComputeService,
    new MockComputePollOperation,
    QueueFactory.makePublisherQueue()
  )

  val httpRoutes = new HttpRoutes(
    swaggerConfig,
    statusService,
    proxyService,
    runtimeService,
    MockDiskServiceInterp,
    leoKubernetesService,
    userInfoDirectives,
    contentSecurityPolicy,
    refererConfig
  )
  val timedHttpRoutes = new HttpRoutes(swaggerConfig,
                                       statusService,
                                       proxyService,
                                       runtimeService,
                                       MockDiskServiceInterp,
                                       leoKubernetesService,
                                       timedUserInfoDirectives,
                                       contentSecurityPolicy,
                                       refererConfig)

  def roundUpToNearestTen(d: Long): Long = (Math.ceil(d / 10.0) * 10).toLong
  val cookieMaxAgeRegex: Regex = "Max-Age=(\\d+);".r

  protected def validateCookie(setCookie: Option[`Set-Cookie`],
                               expectedCookie: HttpCookiePair = tokenCookie,
                               age: Long = tokenAge): Unit = {
    setCookie shouldBe 'defined
    val cookie = setCookie.get.cookie
    cookie.name shouldBe expectedCookie.name
    cookie.value shouldBe expectedCookie.value
    cookie.secure shouldBe true
    cookie.maxAge.map(roundUpToNearestTen) shouldBe Some(age) // test execution loses some milliseconds
    cookie.domain shouldBe None
    cookie.path shouldBe Some("/")
  }

  // TODO: remove when we upgrade to akka-http 10.2.0.
  // See comment in CookieHelper.setTokenCookie.
  protected def validateRawCookie(setCookie: Option[HttpHeader],
                                  expectedCookie: HttpCookiePair = tokenCookie,
                                  age: Long = tokenAge): Unit = {
    setCookie shouldBe Symbol("defined")
    setCookie.get.name shouldBe "Set-Cookie"

    // test execution loses some milliseconds, so round Max-Age before validation
    val replaced =
      cookieMaxAgeRegex.replaceAllIn(setCookie.get.value, m => s"Max-Age=${roundUpToNearestTen(m.group(1).toLong)};")

    replaced shouldBe s"${expectedCookie.name}=${expectedCookie.value}; Max-Age=${age.toString}; Path=/; Secure; SameSite=None"
  }

  protected def validateUnsetRawCookie(setCookie: Option[HttpHeader],
                                       expectedCookie: HttpCookiePair = tokenCookie): Unit = {
    setCookie shouldBe Symbol("defined")
    setCookie.get.name shouldBe "Set-Cookie"
    setCookie.get.value shouldBe s"${tokenName}=unset; expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; SameSite=None"
  }
}
