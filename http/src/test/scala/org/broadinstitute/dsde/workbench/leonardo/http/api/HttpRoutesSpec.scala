package org.broadinstitute.dsde.workbench.leonardo
package http
package api

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.mtl.Ask
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.broadinstitute.dsde.workbench.google2.MachineTypeName
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.KubernetesTestData._
import org.broadinstitute.dsde.workbench.leonardo.db.TestComponent
import org.broadinstitute.dsde.workbench.leonardo.http.AppRoutesTestJsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.http.DiskRoutesTestJsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.http.RuntimeRoutesTestJsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.http.api.HttpRoutesSpec._
import org.broadinstitute.dsde.workbench.leonardo.http.service.{
  AppService,
  BaseMockRuntimeServiceInterp,
  DeleteRuntimeRequest,
  MockAppService,
  MockDiskServiceInterp,
  MockRuntimeServiceInterp,
  RuntimeService
}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URL
import scala.concurrent.duration._

class HttpRoutesSpec
    extends AnyFlatSpec
    with ScalatestRouteTest
    with LeonardoTestSuite
    with ScalaFutures
    with Matchers
    with TestComponent
    with TestLeoRoutes {
  val clusterName = "test"
  val googleProject = "dsp-leo-test"

  val routes = new HttpRoutes(
    swaggerConfig,
    statusService,
    proxyService,
    MockRuntimeServiceInterp,
    MockDiskServiceInterp,
    MockAppService,
    timedUserInfoDirectives,
    contentSecurityPolicy,
    refererConfig
  )

  "RuntimeRoutes" should "create runtime" in {
    Post("/api/google/v1/runtimes/googleProject1/runtime1")
      .withEntity(ContentTypes.`application/json`, defaultCreateRuntimeRequest.asJson.spaces2) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "create a runtime with default parameters" in {
    Post("/api/google/v1/runtimes/googleProject1/runtime1")
      .withEntity(ContentTypes.`application/json`, "{}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should s"reject create a cluster if cluster name is invalid" in {
    val invalidClusterName = "MyCluster"
    Post(s"/api/google/v1/runtimes/googleProject1/$invalidClusterName", defaultCreateRuntimeRequest.asJson.spaces2) ~> httpRoutes.route ~> check {
      val expectedResponse =
        """Invalid name MyCluster. Only lowercase alphanumeric characters, numbers and dashes are allowed in leo names"""
      responseEntity.toStrict(5 seconds).futureValue.data.utf8String shouldBe expectedResponse

      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "get a runtime" in {
    Get("/api/google/v1/runtimes/googleProject/runtime1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[GetRuntimeResponse].id shouldBe CommonTestData.testCluster.id
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "return 404 when getting a non-existent runtime" in {
    Get("/api/google/v1/runtimes/googleProject/runtime-non-existent") ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "list runtimes with a project" in {
    Get("/api/google/v1/runtimes/googleProject") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Vector[ListRuntimeResponse2]].map(_.id) shouldBe Vector(CommonTestData.testCluster.id)
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "list runtimes without a project" in {
    Get("/api/google/v1/runtimes") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[Vector[ListRuntimeResponse2]]
      response.map(_.id) shouldBe Vector(CommonTestData.testCluster.id)
      response.map(_.runtimeConfig.asInstanceOf[RuntimeConfig.GceConfig]) shouldBe Vector(
        CommonTestData.defaultGceRuntimeConfig
      )
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "list runtimes with labels" in isolatedDbTest {
    def runtimesWithLabels(i: Int) =
      defaultCreateRuntimeRequest
        .copy(jupyterStartUserScriptUri = None)
        .copy(labels = Map(s"label$i" -> s"value$i"))

    def serviceAccountLabels: Map[String, String] =
      (
        clusterServiceAccountFromProject(GoogleProject(googleProject)).map { sa =>
          Map("clusterServiceAccount" -> sa.value)
        } getOrElse Map.empty
      ) ++ (
        notebookServiceAccountFromProject(GoogleProject(googleProject)).map { sa =>
          Map("notebookServiceAccount" -> sa.value)
        } getOrElse Map.empty
      )

    for (i <- 1 to 10) {
      Post(s"/api/google/v1/runtimes/${googleProject}/${clusterName}-$i", runtimesWithLabels(i).asJson) ~> httpRoutes.route ~> check {
        status shouldEqual StatusCodes.Accepted
      }
    }

    Get("/api/google/v1/runtimes?label6=value6") ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.OK

      val responseClusters = responseAs[List[ListRuntimeResponse2]]
      responseClusters should have size 1

      val cluster = responseClusters.head
      cluster.googleProject shouldEqual GoogleProject(googleProject)
      cluster.clusterName shouldEqual RuntimeName(s"${clusterName}-6")
      cluster.labels shouldEqual Map(
        "clusterName" -> s"${clusterName}-6",
        "runtimeName" -> s"${clusterName}-6",
        "creator" -> "user1@example.com",
        "googleProject" -> googleProject,
        "tool" -> "Jupyter",
        "label6" -> "value6"
      ) ++ serviceAccountLabels

      //validateCookie { header[`Set-Cookie`] }
      validateRawCookie(header("Set-Cookie"))
    }

    Get("/api/google/v1/runtimes?_labels=label4%3Dvalue4") ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.OK

      val responseClusters = responseAs[List[ListRuntimeResponse2]]
      responseClusters should have size 1

      val cluster = responseClusters.head
      cluster.googleProject.value shouldEqual googleProject
      cluster.clusterName shouldEqual RuntimeName(s"${clusterName}-4")
      cluster.labels shouldEqual Map(
        "clusterName" -> s"${clusterName}-4",
        "runtimeName" -> s"${clusterName}-4",
        "creator" -> "user1@example.com",
        "googleProject" -> googleProject,
        "tool" -> "Jupyter",
        "label4" -> "value4"
      ) ++ serviceAccountLabels

      //validateCookie { header[`Set-Cookie`] }
      validateRawCookie(header("Set-Cookie"))
    }

    Get("/api/google/v1/runtimes?_labels=bad") ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "list runtimes with parameters" in {
    Get("/api/google/v1/runtimes?project=foo&creator=bar") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Vector[ListRuntimeResponse2]].map(_.id) shouldBe Vector(CommonTestData.testCluster.id)
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "delete a runtime" in {
    Delete("/api/google/v1/runtimes/googleProject1/runtime1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "delete a runtime and disk if deleteDisk is true" in {
    val runtimeService = new BaseMockRuntimeServiceInterp {
      override def deleteRuntime(deleteRuntimeRequest: DeleteRuntimeRequest)(
        implicit as: Ask[IO, AppContext]
      ): IO[Unit] = IO {
        val expectedDeleteRuntime =
          DeleteRuntimeRequest(timedUserInfo, GoogleProject("googleProject1"), RuntimeName("runtime1"), true)
        deleteRuntimeRequest shouldBe expectedDeleteRuntime
      }
    }
    val routes = fakeRoutes(runtimeService)
    Delete("/api/google/v1/runtimes/googleProject1/runtime1?deleteDisk=true") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "return 404 when deleting a non-existent runtime" in {
    Delete("/api/google/v1/runtimes/googleProject/runtime-non-existent") ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "keep disk when deleting runtime if deleteDisk is false" in {
    val runtimeService = new BaseMockRuntimeServiceInterp {
      override def deleteRuntime(deleteRuntimeRequest: DeleteRuntimeRequest)(
        implicit as: Ask[IO, AppContext]
      ): IO[Unit] = IO {
        val expectedDeleteRuntime =
          DeleteRuntimeRequest(timedUserInfo, GoogleProject("googleProject1"), RuntimeName("runtime1"), false)
        deleteRuntimeRequest shouldBe expectedDeleteRuntime
      }
    }
    val routes = fakeRoutes(runtimeService)
    Delete("/api/google/v1/runtimes/googleProject1/runtime1?deleteDisk=false") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "not delete disk when deleting a runtime with PD enabled if deleteDisk is not set" in {
    val runtimeService = new BaseMockRuntimeServiceInterp {
      override def deleteRuntime(deleteRuntimeRequest: DeleteRuntimeRequest)(
        implicit as: Ask[IO, AppContext]
      ): IO[Unit] = IO {
        val expectedDeleteRuntime =
          DeleteRuntimeRequest(timedUserInfo, GoogleProject("googleProject1"), RuntimeName("runtime1"), false)
        deleteRuntimeRequest shouldBe expectedDeleteRuntime
      }
    }
    val routes = fakeRoutes(runtimeService)
    Delete("/api/google/v1/runtimes/googleProject1/runtime1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "not delete disk when deleting a kubernetes app with PD enabled if deleteDisk is not set" in {
    val kubernetesService = new MockAppService {
      override def deleteApp(request: DeleteAppRequest)(
        implicit as: Ask[IO, AppContext]
      ): IO[Unit] = IO {
        val expectedDeleteApp =
          DeleteAppRequest(timedUserInfo, GoogleProject("googleProject1"), AppName("app1"), false)
        request shouldBe expectedDeleteApp
      }
    }
    val routes = fakeRoutes(kubernetesService)
    Delete("/api/google/v1/apps/googleProject1/app1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "stop a runtime" in {
    Post("/api/google/v1/runtimes/googleProject1/runtime1/stop") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "return 404 when stopping a non-existent runtime" in {
    Post("/api/google/v1/runtimes/googleProject1/runtime-non-existent/stop") ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "start a runtime" in {
    Post("/api/google/v1/runtimes/googleProject1/runtime1/start") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "return 404 when startting a non-existent runtime" in {
    Post("/api/google/v1/runtimes/googleProject1/runtime-non-existent/start") ~> httpRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "update a runtime" in {
    val request = UpdateRuntimeRequest(
      Some(UpdateRuntimeConfigRequest.GceConfig(Some(MachineTypeName("n1-micro-2")), Some(DiskSize(50)))),
      true,
      Some(true),
      Some(5.minutes),
      Map.empty,
      Set.empty
    )
    Patch("/api/google/v1/runtimes/googleProject1/runtime1")
      .withEntity(ContentTypes.`application/json`, request.asJson.spaces2) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "update a runtime with default parameters" in {
    Patch("/api/google/v1/runtimes/googleProject1/runtime1")
      .withEntity(ContentTypes.`application/json`, "{}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "not handle patch with invalid runtime config" in {
    val negative =
      UpdateRuntimeRequest(Some(UpdateRuntimeConfigRequest.GceConfig(None, Some(DiskSize(-100)))),
                           false,
                           None,
                           None,
                           Map.empty,
                           Set.empty)
    Patch("/api/google/v1/runtimes/googleProject1/runtime1")
      .withEntity(ContentTypes.`application/json`, negative.asJson.spaces2) ~> routes.route ~> check {
      status shouldBe StatusCodes.BadRequest
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "The request content was malformed:\nDecodingFailure at .runtimeConfig.diskSize: Minimum required disk size is 5GB"
    }
    val oneWorker =
      UpdateRuntimeRequest(Some(UpdateRuntimeConfigRequest.DataprocConfig(None, None, Some(1), None)),
                           false,
                           None,
                           None,
                           Map.empty,
                           Set.empty)
    Patch("/api/google/v1/runtimes/googleProject1/runtime1")
      .withEntity(ContentTypes.`application/json`, oneWorker.asJson.spaces2) ~> routes.route ~> check {
      status shouldBe StatusCodes.BadRequest
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "The request content was malformed:\nDecodingFailure at : Google Dataproc does not support clusters with 1 non-preemptible worker. Must be 0, 2 or more."
    }
  }

  "DiskRoutes" should "create a disk" in {
    val diskCreateRequest = CreateDiskRequest(
      Map("foo" -> "bar"),
      Some(DiskSize(500)),
      Some(DiskType.Standard),
      Some(BlockSize(65536))
    )
    Post("/api/google/v1/disks/googleProject1/disk1")
      .withEntity(ContentTypes.`application/json`, diskCreateRequest.asJson.spaces2) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "create a disk with default parameters" in {
    Post("/api/google/v1/disks/googleProject1/disk1")
      .withEntity(ContentTypes.`application/json`, "{}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "get a disk" in {
    Get("/api/google/v1/disks/googleProject/disk1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[GetPersistentDiskResponse].name shouldBe CommonTestData.diskName
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "list all disks" in {
    Get("/api/google/v1/disks") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Vector[ListPersistentDiskResponse]].map(_.name) shouldBe Vector(CommonTestData.diskName)
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "list all disks within a project" in {
    Get("/api/google/v1/disks/googleProject") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Vector[ListPersistentDiskResponse]].map(_.name) shouldBe Vector(CommonTestData.diskName)
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "list disks with parameters" in {
    Get("/api/google/v1/disks?project=foo&creator=bar") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Vector[ListPersistentDiskResponse]].map(_.name) shouldBe Vector(CommonTestData.diskName)
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "delete a disk" in {
    Delete("/api/google/v1/disks/googleProject1/disk1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "update a disk" in {
    val request = UpdateDiskRequest(
      Map("foo" -> "bar"),
      DiskSize(1024)
    )
    Patch("/api/google/v1/disks/googleProject1/disk1", request.asJson) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  "HttpRoutes" should "not handle unrecognized routes" in {
    Post("/api/google/v1/runtime/googleProject1/runtime1/unhandled") ~> routes.route ~> check {
      status shouldBe StatusCodes.NotFound
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "\"API not found. Make sure you're calling the correct endpoint with correct method\""
    }
    Get("/api/google/v1/badruntime/googleProject1/runtime1") ~> routes.route ~> check {
      status shouldBe StatusCodes.NotFound
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "\"API not found. Make sure you're calling the correct endpoint with correct method\""
    }
    Get("/api/google/v2/runtime/googleProject1/runtime1") ~> routes.route ~> check {
      status shouldBe StatusCodes.NotFound
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "\"API not found. Make sure you're calling the correct endpoint with correct method\""
    }
    Post("/api/google/v1/runtime/googleProject1/runtime1") ~> routes.route ~> check {
      status shouldBe StatusCodes.NotFound
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "\"API not found. Make sure you're calling the correct endpoint with correct method\""
    }
    Post("/api/google/v1/disk/googleProject1/disk1") ~> routes.route ~> check {
      status shouldBe StatusCodes.NotFound
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "\"API not found. Make sure you're calling the correct endpoint with correct method\""
    }
    Get("/api/google/v1/disks/googleProject1/disk1/foo") ~> routes.route ~> check {
      status shouldBe StatusCodes.NotFound
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "\"API not found. Make sure you're calling the correct endpoint with correct method\""
    }
  }

  "Kubernetes Routes" should "create an app" in {
    Post("/api/google/v1/apps/googleProject1/app1")
      .withEntity(ContentTypes.`application/json`, createAppRequest.asJson.spaces2) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "list apps with project" in {
    Get("/api/google/v1/apps/googleProject1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      validateRawCookie(header("Set-Cookie"))
      responseAs[Vector[ListAppResponse]] shouldBe listAppResponse
    }
  }

  it should "list apps with no project" in {
    Get("/api/google/v1/apps/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "list apps with labels" in {
    Get("/api/google/v1/apps?project=foo&creator=bar&includeDeleted=true") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "get app" in {
    Get("/api/google/v1/apps/googleProject1/app1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      validateRawCookie(header("Set-Cookie"))
      responseAs[GetAppResponse] shouldBe getAppResponse
    }
  }

  it should "batch nodepool create" in {
    Post("/api/google/v1/apps/googleProject1/batchNodepoolCreate")
      .withEntity(ContentTypes.`application/json`, batchNodepoolCreateRequest.asJson.spaces2) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "delete app" in {
    Delete("/api/google/v1/apps/googleProject1/app1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "validate app name" in {
    Get("/api/google/v1/apps/googleProject1/1badApp") ~> routes.route ~> check {
      status.intValue shouldBe 400
    }
  }

  it should "validate create app request" in {
    Post("/api/google/v1/apps/googleProject1/app1")
      .withEntity(
        ContentTypes.`application/json`,
        createAppRequest
          .copy(kubernetesRuntimeConfig =
            createAppRequest.kubernetesRuntimeConfig.map(c => c.copy(numNodes = NumNodes(-1)))
          )
          .asJson
          .spaces2
      ) ~> httpRoutes.route ~> check {
      status shouldBe StatusCodes.BadRequest
      val resp = responseEntity.toStrict(5 seconds).futureValue.data.utf8String
      resp shouldBe "The request content was malformed:\nDecodingFailure at .kubernetesRuntimeConfig.numNodes: Minimum number of nodes is 1"
    }
  }

  it should "stop an app" in {
    Post("/api/google/v1/apps/googleProject1/app1/stop") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  it should "start an app" in {
    Post("/api/google/v1/apps/googleProject1/app1/start") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Accepted
      validateRawCookie(header("Set-Cookie"))
    }
  }

  def fakeRoutes(runtimeService: RuntimeService[IO]): HttpRoutes =
    new HttpRoutes(
      swaggerConfig,
      statusService,
      proxyService,
      runtimeService,
      MockDiskServiceInterp,
      MockAppService,
      timedUserInfoDirectives,
      contentSecurityPolicy,
      refererConfig
    )

  def fakeRoutes(kubernetesService: AppService[IO]): HttpRoutes =
    new HttpRoutes(
      swaggerConfig,
      statusService,
      proxyService,
      runtimeService,
      MockDiskServiceInterp,
      kubernetesService,
      timedUserInfoDirectives,
      contentSecurityPolicy,
      refererConfig
    )
}

object HttpRoutesSpec {
  implicit val updateDiskRequestEncoder: Encoder[UpdateDiskRequest] = Encoder.forProduct2(
    "labels",
    "size"
  )(x =>
    (
      x.labels,
      x.size
    )
  )

  implicit val listClusterResponseDecoder: Decoder[ListRuntimeResponse2] = Decoder.instance { x =>
    for {
      id <- x.downField("id").as[Long]
      clusterName <- x.downField("runtimeName").as[RuntimeName]
      googleProject <- x.downField("googleProject").as[GoogleProject]
      auditInfo <- x.downField("auditInfo").as[AuditInfo]
      machineConfig <- x.downField("runtimeConfig").as[RuntimeConfig]
      clusterUrl <- x.downField("proxyUrl").as[URL]
      status <- x.downField("status").as[RuntimeStatus]
      labels <- x.downField("labels").as[LabelMap]
      patchInProgress <- x.downField("patchInProgress").as[Boolean]
    } yield ListRuntimeResponse2(
      id,
      RuntimeSamResourceId("fakeId"),
      clusterName,
      googleProject,
      auditInfo,
      machineConfig,
      clusterUrl,
      status,
      labels,
      patchInProgress
    )
  }
}
