package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.IO
import cats.mtl.Ask
import fs2.concurrent.InspectableQueue
import org.broadinstitute.dsde.workbench.google2.{DataprocRole, DiskName, InstanceName, MachineTypeName}
import org.broadinstitute.dsde.workbench.google2.mock.{
  FakeGoogleComputeService,
  FakeGooglePublisher,
  FakeGoogleStorageInterpreter,
  MockComputePollOperation
}
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData.{gceRuntimeConfig, userInfo, testCluster, _}
import org.broadinstitute.dsde.workbench.leonardo.RuntimeImageType.{CryptoDetector, Jupyter, Welder}
import org.broadinstitute.dsde.workbench.leonardo.config.Config
import org.broadinstitute.dsde.workbench.leonardo.dao.MockDockerDAO
import org.broadinstitute.dsde.workbench.leonardo.db._
import org.broadinstitute.dsde.workbench.leonardo.model.{
  BucketObjectException,
  ForbiddenError,
  LeoException,
  ParseLabelsException,
  RuntimeAlreadyExistsException,
  RuntimeCannotBeDeletedException,
  RuntimeCannotBeUpdatedException,
  RuntimeDiskSizeCannotBeChangedException,
  RuntimeDiskSizeCannotBeDecreasedException,
  RuntimeMachineTypeCannotBeChangedException,
  RuntimeNotFoundException
}
import org.broadinstitute.dsde.workbench.leonardo.TestUtils.leonardoExceptionEq
import org.broadinstitute.dsde.workbench.leonardo.http.api.ListRuntimeResponse2
import org.broadinstitute.dsde.workbench.leonardo.http.service.RuntimeServiceInterp.calculateAutopauseThreshold
import org.broadinstitute.dsde.workbench.leonardo.monitor.{
  DiskUpdate,
  LeoPubsubMessage,
  RuntimeConfigInCreateRuntimeMessage
}
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage._
import org.broadinstitute.dsde.workbench.leonardo.util.QueueFactory
import org.broadinstitute.dsde.workbench.model
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{IP, UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.scalatest.Assertion

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar
import java.net.URL

import org.broadinstitute.dsde.workbench.leonardo.SamResourceId.RuntimeSamResourceId

class RuntimeServiceInterpSpec extends AnyFlatSpec with LeonardoTestSuite with TestComponent with MockitoSugar {
  val publisherQueue = QueueFactory.makePublisherQueue()
  def makeRuntimeService(publisherQueue: InspectableQueue[IO, LeoPubsubMessage]) =
    new RuntimeServiceInterp(
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
      publisherQueue
    )
  val runtimeService = makeRuntimeService(publisherQueue)
  val emptyCreateRuntimeReq = CreateRuntime2Request(
    Map.empty,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    Set.empty,
    Map.empty
  )

  implicit val ctx: Ask[IO, AppContext] = Ask.const[IO, AppContext](
    AppContext(model.TraceId("traceId"), Instant.now())
  )

  it should "fail with AuthorizationError if user doesn't have project level permission" in {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("email"), 0)
    val googleProject = GoogleProject("googleProject")

    val res = for {
      r <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          RuntimeName("clusterName1"),
          emptyCreateRuntimeReq
        )
        .attempt
    } yield {
      r shouldBe (Left(ForbiddenError(userInfo.userEmail)))
    }
    res.unsafeRunSync()
  }

  it should "calculate autopause threshold properly" in {
    calculateAutopauseThreshold(None, None, autoFreezeConfig) shouldBe autoFreezeConfig.autoFreezeAfter.toMinutes.toInt
    calculateAutopauseThreshold(Some(false), None, autoFreezeConfig) shouldBe autoPauseOffValue
    calculateAutopauseThreshold(Some(true), None, autoFreezeConfig) shouldBe autoFreezeConfig.autoFreezeAfter.toMinutes.toInt
    calculateAutopauseThreshold(Some(true), Some(30), autoFreezeConfig) shouldBe 30
  }

  it should "throw ClusterAlreadyExistsException when creating a cluster with same name and project as an existing cluster" in isolatedDbTest {
    runtimeService.createRuntime(userInfo, project, name0, emptyCreateRuntimeReq).unsafeRunSync()
    val exc = runtimeService
      .createRuntime(userInfo, project, name0, emptyCreateRuntimeReq)
      .attempt
      .unsafeRunSync()
      .swap
      .toOption
      .get
    exc shouldBe a[RuntimeAlreadyExistsException]
  }

  it should "throw a JupyterExtensionException when the extensionUri is too long" in isolatedDbTest {
    // create the cluster
    val clusterRequest = emptyCreateRuntimeReq.copy(userJupyterExtensionConfig =
      Some(
        UserJupyterExtensionConfig(nbExtensions =
          Map("notebookExtension" -> s"gs://bucket/${Stream.continually('a').take(1025).mkString}")
        )
      )
    )
    val response =
      runtimeService.createRuntime(userInfo, project, name0, clusterRequest).attempt.unsafeRunSync().swap.toOption.get

    response shouldBe a[BucketObjectException]
  }

  it should "throw a JupyterExtensionException when the jupyterExtensionUri does not point to a GCS object" in isolatedDbTest {
    // create the cluster
    val response =
      runtimeService
        .createRuntime(
          userInfo,
          project,
          name0,
          emptyCreateRuntimeReq.copy(userJupyterExtensionConfig =
            Some(UserJupyterExtensionConfig(nbExtensions = Map("notebookExtension" -> "gs://bogus/object.tar.gz")))
          )
        )
        .attempt
        .unsafeRunSync()
        .swap
        .toOption
        .get

    response shouldBe a[BucketObjectException]
  }

  it should "successfully create a GCE runtime when no runtime is specified" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName1")

    val res = for {
      _ <- publisherQueue.tryDequeue1 // just to make sure there's no messages in the queue to start with
      context <- ctx.ask[AppContext]
      r <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          emptyCreateRuntimeReq
        )
        .attempt
      clusterOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      cluster = clusterOpt.get
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(cluster.runtimeConfigId).transaction
      message <- publisherQueue.dequeue1
      gceRuntimeConfig = runtimeConfig.asInstanceOf[RuntimeConfig.GceConfig]
      gceRuntimeConfigRequest = LeoLenses.runtimeConfigPrism.getOption(gceRuntimeConfig).get
    } yield {
      r shouldBe Right(())
      runtimeConfig shouldBe (Config.gceConfig.runtimeConfigDefaults)
      cluster.googleProject shouldBe (googleProject)
      cluster.runtimeName shouldBe (runtimeName)
      val expectedMessage = CreateRuntimeMessage
        .fromRuntime(cluster, gceRuntimeConfigRequest, Some(context.traceId))
        .copy(
          runtimeImages = Set(
            RuntimeImage(RuntimeImageType.Jupyter, Config.imageConfig.jupyterImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Welder, Config.imageConfig.welderGcrImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Proxy, Config.imageConfig.proxyImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.CryptoDetector, Config.imageConfig.cryptoDetectorImage.imageUrl, context.now)
          ),
          scopes = Config.gceConfig.defaultScopes
        )
      message shouldBe expectedMessage
    }
    res.unsafeRunSync()
  }

  it should "successfully accept https as user script and user startup script" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName2")
    val request = emptyCreateRuntimeReq.copy(
      jupyterUserScriptUri = Some(
        UserScriptPath.Http(
          new URL("https://api-dot-all-of-us-workbench-test.appspot.com/static/start_notebook_cluster.sh")
        )
      ),
      jupyterStartUserScriptUri = Some(
        UserScriptPath.Http(
          new URL("https://api-dot-all-of-us-workbench-test.appspot.com/static/start_notebook_cluster.sh")
        )
      )
    )

    val res = for {
      r <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          request
        )
        .attempt
      _ <- publisherQueue.dequeue1 //dequeue the message so that it doesn't affect other tests
    } yield {
      r shouldBe Right(())
    }
    res.unsafeRunSync()
  }

  it should "successfully create a cluster with an rstudio image" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName2")
    val rstudioImage = ContainerImage("some-rstudio-image", ContainerRegistry.GCR)
    val request = emptyCreateRuntimeReq.copy(
      toolDockerImage = Some(rstudioImage)
    )

    val res = for {
      r <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          request
        )
        .attempt
      runtime <- clusterQuery.getActiveClusterByName(googleProject, runtimeName).transaction
      _ <- publisherQueue.dequeue1 //dequeue the message so that it doesn't affect other tests
    } yield {
      r shouldBe Right(())
      runtime.get.runtimeImages.map(_.imageType) contains (RuntimeImageType.RStudio)
    }
    res.unsafeRunSync()
  }

  it should "successfully create a dataproc runtime when explicitly told so when numberOfWorkers is 0" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName1")
    val req = emptyCreateRuntimeReq.copy(
      runtimeConfig = Some(
        RuntimeConfigRequest.DataprocConfig(
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          Map.empty
        )
      )
    )

    val res = for {
      _ <- publisherQueue.tryDequeue1
      context <- ctx.ask[AppContext]
      _ <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          req
        )
        .attempt
      clusterOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      cluster = clusterOpt.get
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(cluster.runtimeConfigId).transaction
      runtimeConfigRequest = LeoLenses.runtimeConfigPrism.getOption(runtimeConfig).get
      message <- publisherQueue.dequeue1
    } yield {
      // Default worker is 0, hence all worker configs are None
      val expectedRuntimeConfig = Config.dataprocConfig.runtimeConfigDefaults.copy(
        workerMachineType = None,
        workerDiskSize = None,
        numberOfWorkerLocalSSDs = None,
        numberOfPreemptibleWorkers = None
      )
      runtimeConfig shouldBe expectedRuntimeConfig
      val expectedMessage = CreateRuntimeMessage
        .fromRuntime(cluster, runtimeConfigRequest, Some(context.traceId))
        .copy(
          runtimeImages = Set(
            RuntimeImage(RuntimeImageType.Jupyter, Config.imageConfig.jupyterImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Welder, Config.imageConfig.welderGcrImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Proxy, Config.imageConfig.proxyImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.CryptoDetector, Config.imageConfig.cryptoDetectorImage.imageUrl, context.now)
          ),
          scopes = Config.dataprocConfig.defaultScopes
        )
      message shouldBe expectedMessage
    }
    res.unsafeRunSync()
  }

  it should "successfully create a dataproc runtime when explicitly told so when numberOfWorkers is more than 0" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName1")
    val req = emptyCreateRuntimeReq.copy(
      runtimeConfig = Some(
        RuntimeConfigRequest.DataprocConfig(
          Some(2),
          None,
          None,
          None,
          None,
          None,
          None,
          Map.empty
        )
      )
    )

    val res = for {
      _ <- publisherQueue.tryDequeue1
      context <- ctx.ask[AppContext]
      _ <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          req
        )
        .attempt
      clusterOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      cluster = clusterOpt.get
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(cluster.runtimeConfigId).transaction
      runtimeConfigRequest = LeoLenses.runtimeConfigPrism.getOption(runtimeConfig).get
      message <- publisherQueue.dequeue1
    } yield {
      runtimeConfig shouldBe Config.dataprocConfig.runtimeConfigDefaults.copy(numberOfWorkers = 2)
      val expectedMessage = CreateRuntimeMessage
        .fromRuntime(cluster, runtimeConfigRequest, Some(context.traceId))
        .copy(
          runtimeImages = Set(
            RuntimeImage(RuntimeImageType.Jupyter, Config.imageConfig.jupyterImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Welder, Config.imageConfig.welderGcrImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Proxy, Config.imageConfig.proxyImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.CryptoDetector, Config.imageConfig.cryptoDetectorImage.imageUrl, context.now)
          ),
          scopes = Config.dataprocConfig.defaultScopes
        )
      message shouldBe expectedMessage
    }
    res.unsafeRunSync()
  }

  it should "create a runtime with the latest welder from welderRegistry" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName1 = RuntimeName("runtimeName1")
    val runtimeName2 = RuntimeName("runtimeName2")
    val runtimeName3 = RuntimeName("runtimeName3")

    val res = for {
      r1 <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName1,
          emptyCreateRuntimeReq.copy(welderRegistry = Some(ContainerRegistry.DockerHub))
        )
        .attempt
      r2 <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName2,
          emptyCreateRuntimeReq.copy(welderRegistry = Some(ContainerRegistry.GCR))
        )
        .attempt
      r3 <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName3,
          emptyCreateRuntimeReq
        )
        .attempt

      runtimeOpt1 <- clusterQuery.getActiveClusterByName(googleProject, runtimeName1).transaction
      runtime1 = runtimeOpt1.get
      welder1 = runtime1.runtimeImages.filter(_.imageType == RuntimeImageType.Welder).headOption
      _ <- publisherQueue.dequeue1

      runtimeOpt2 <- clusterQuery.getActiveClusterByName(googleProject, runtimeName2).transaction
      runtime2 = runtimeOpt2.get
      welder2 = runtime2.runtimeImages.filter(_.imageType == RuntimeImageType.Welder).headOption
      _ <- publisherQueue.dequeue1

      runtimeOpt3 <- clusterQuery.getActiveClusterByName(googleProject, runtimeName3).transaction
      runtime3 = runtimeOpt3.get
      welder3 = runtime3.runtimeImages.filter(_.imageType == RuntimeImageType.Welder).headOption
      _ <- publisherQueue.dequeue1
    } yield {
      r1 shouldBe Right(())
      runtime1.runtimeName shouldBe (runtimeName1)
      welder1 shouldBe defined
      welder1.get.imageUrl shouldBe Config.imageConfig.welderDockerHubImage.imageUrl

      r2 shouldBe Right(())
      runtime2.runtimeName shouldBe (runtimeName2)
      welder2 shouldBe defined
      welder2.get.imageUrl shouldBe Config.imageConfig.welderGcrImage.imageUrl

      r3 shouldBe Right(())
      runtime3.runtimeName shouldBe (runtimeName3)
      welder3 shouldBe defined
      welder3.get.imageUrl shouldBe Config.imageConfig.welderGcrImage.imageUrl
    }
    res.unsafeRunSync()
  }

  it should "create a runtime with the crypto-detector image" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName1 = RuntimeName("runtimeName1")
    val runtimeName2 = RuntimeName("runtimeName2")

    val res = for {
      r1 <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName1,
          emptyCreateRuntimeReq.copy(welderRegistry = Some(ContainerRegistry.DockerHub))
        )
        .attempt

      r2 <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName2,
          emptyCreateRuntimeReq.copy(welderRegistry = Some(ContainerRegistry.GCR))
        )
        .attempt

      runtimeOpt1 <- clusterQuery.getActiveClusterByName(googleProject, runtimeName1).transaction
      runtime1 = runtimeOpt1.get
      _ <- publisherQueue.dequeue1

      runtimeOpt2 <- clusterQuery.getActiveClusterByName(googleProject, runtimeName2).transaction
      runtime2 = runtimeOpt2.get
      _ <- publisherQueue.dequeue1
    } yield {
      // Crypto detector not supported on DockerHub
      r1 shouldBe Right(())
      runtime1.runtimeName shouldBe runtimeName1
      runtime1.runtimeImages.map(_.imageType) shouldBe Set(Jupyter, Welder, RuntimeImageType.Proxy)

      r2 shouldBe Right(())
      runtime2.runtimeName shouldBe runtimeName2
      runtime2.runtimeImages.map(_.imageType) shouldBe Set(Jupyter, Welder, RuntimeImageType.Proxy, CryptoDetector)
    }
    res.unsafeRunSync()
  }

  it should "create a runtime with a disk config" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val persistentDisk = PersistentDiskRequest(
      diskName,
      Some(DiskSize(500)),
      None,
      Map.empty
    )
    val req = emptyCreateRuntimeReq.copy(
      runtimeConfig = Some(
        RuntimeConfigRequest.GceWithPdConfig(machineType = Some(MachineTypeName("n1-standard-4")), persistentDisk)
      )
    )

    val res = for {
      context <- ctx.ask[AppContext]
      r <- runtimeService
        .createRuntime(
          userInfo,
          project,
          name0,
          req
        )
        .attempt
      runtimeOpt <- clusterQuery.getActiveClusterByNameMinimal(project, name0).transaction
      runtime = runtimeOpt.get
      diskOpt <- persistentDiskQuery.getActiveByName(project, diskName).transaction
      disk = diskOpt.get
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(runtime.runtimeConfigId).transaction
      runtimeConfigRequest = LeoLenses.runtimeConfigPrism.getOption(runtimeConfig).get
      message <- publisherQueue.dequeue1
    } yield {
      r shouldBe Right(())
      runtime.googleProject shouldBe project
      runtime.runtimeName shouldBe name0
      runtimeConfig.asInstanceOf[RuntimeConfig.GceWithPdConfig].persistentDiskId shouldBe Some(disk.id)
      disk.googleProject shouldBe project
      disk.name shouldBe diskName
      disk.size shouldBe DiskSize(500)
      runtimeConfig shouldBe RuntimeConfig.GceWithPdConfig(
        MachineTypeName("n1-standard-4"),
        Some(disk.id),
        bootDiskSize = DiskSize(50)
      ) //TODO: this is a problem in terms of inconsistency
      val expectedMessage = CreateRuntimeMessage
        .fromRuntime(runtime, runtimeConfigRequest, Some(context.traceId))
        .copy(
          runtimeImages = Set(
            RuntimeImage(RuntimeImageType.Jupyter, Config.imageConfig.jupyterImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Welder, Config.imageConfig.welderGcrImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Proxy, Config.imageConfig.proxyImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.CryptoDetector, Config.imageConfig.cryptoDetectorImage.imageUrl, context.now)
          ),
          scopes = Config.gceConfig.defaultScopes,
          runtimeConfig = RuntimeConfigInCreateRuntimeMessage.GceWithPdConfig(runtimeConfig.machineType,
                                                                              disk.id,
                                                                              bootDiskSize = DiskSize(50))
        )
      message shouldBe expectedMessage
    }

    res.unsafeRunSync()
  }

  it should "fail to delete a runtime while it's still creating" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      _ <- runtimeService
        .createRuntime(
          userInfo,
          project,
          name0,
          emptyCreateRuntimeReq
        )
      r <- runtimeService.deleteRuntime(DeleteRuntimeRequest(userInfo, project, name0, false)).attempt
    } yield {
      r.swap.toOption.get.isInstanceOf[RuntimeCannotBeDeletedException] shouldBe true
    }

    res.unsafeRunSync()
  }

  it should "get a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(samResource = samResource).save())
      getResponse <- runtimeService.getRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName)
    } yield {
      getResponse.samResource shouldBe testRuntime.samResource
    }
    res.unsafeRunSync()
  }

  it should "throw ClusterNotFoundException for nonexistent clusters" in isolatedDbTest {
    val exc = runtimeService
      .getRuntime(userInfo, GoogleProject("nonexistent"), RuntimeName("cluster"))
      .attempt
      .unsafeRunSync()
      .swap
      .toOption
      .get
    exc shouldBe a[RuntimeNotFoundException]
  }

  it should "list runtimes" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      samResource1 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      samResource2 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      _ <- IO(makeCluster(1).copy(samResource = samResource1).save())
      _ <- IO(makeCluster(2).copy(samResource = samResource2).save())
      listResponse <- runtimeService.listRuntimes(userInfo, None, Map.empty)
    } yield {
      listResponse.map(_.samResource).toSet shouldBe Set(samResource1, samResource2)
    }

    res.unsafeRunSync()
  }

  it should "list runtimes with a project" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      samResource1 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      samResource2 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      _ <- IO(makeCluster(1).copy(samResource = samResource1).save())
      _ <- IO(makeCluster(2).copy(samResource = samResource2).save())
      listResponse <- runtimeService.listRuntimes(userInfo, Some(project), Map.empty)
    } yield {
      listResponse.map(_.samResource).toSet shouldBe Set(samResource1, samResource2)
    }

    res.unsafeRunSync()
  }

  it should "list runtimes with parameters" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      samResource1 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      samResource2 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      runtime1 <- IO(makeCluster(1).copy(samResource = samResource1).save())
      _ <- IO(makeCluster(2).copy(samResource = samResource2).save())
      _ <- labelQuery.save(runtime1.id, LabelResourceType.Runtime, "foo", "bar").transaction
      listResponse <- runtimeService.listRuntimes(userInfo, None, Map("foo" -> "bar"))
    } yield {
      listResponse.map(_.samResource).toSet shouldBe Set(samResource1)
    }

    res.unsafeRunSync()
  }

  // See https://broadworkbench.atlassian.net/browse/PROD-440
  // AoU relies on the ability for project owners to list other users' runtimes.
  it should "list runtimes belonging to other users" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    // Make runtimes belonging to different users than the calling user
    val res = for {
      samResource1 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      samResource2 <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      runtime1 = LeoLenses.runtimeToCreator.set(WorkbenchEmail("different_user1@example.com"))(
        makeCluster(1).copy(samResource = samResource1)
      )
      runtime2 = LeoLenses.runtimeToCreator.set(WorkbenchEmail("different_user2@example.com"))(
        makeCluster(2).copy(samResource = samResource2)
      )
      _ <- IO(runtime1.save())
      _ <- IO(runtime2.save())
      listResponse <- runtimeService.listRuntimes(userInfo, None, Map.empty)
    } yield {
      // Since the calling user is whitelisted in the auth provider, it should return
      // the runtimes belonging to other users.
      listResponse.map(_.samResource).toSet shouldBe Set(samResource1, samResource2)
    }

    res.unsafeRunSync()
  }

  it should "list runtimes with labels" in isolatedDbTest {
    // create a couple of clusters
    val clusterName1 = RuntimeName(s"cluster-${UUID.randomUUID.toString}")
    val req = emptyCreateRuntimeReq.copy(
      labels = Map("bam" -> "yes", "vcf" -> "no", "foo" -> "bar"),
      userJupyterExtensionConfig =
        Some(UserJupyterExtensionConfig(Map("abc" -> "def"), Map("pqr" -> "pqr"), Map("xyz" -> "xyz"))),
      defaultClientId = Some("ThisIsADefaultClientID"),
      autopause = Some(true),
      autopauseThreshold = Some(30 minutes)
    )
    runtimeService.createRuntime(userInfo, project, clusterName1, req).unsafeRunSync()
    val runtime1 = runtimeService
      .getRuntime(userInfo, project, clusterName1)
      .unsafeRunSync()
    val listRuntimeResponse1 = ListRuntimeResponse2(
      runtime1.id,
      runtime1.samResource,
      runtime1.clusterName,
      runtime1.googleProject,
      runtime1.auditInfo,
      runtime1.runtimeConfig,
      runtime1.clusterUrl,
      runtime1.status,
      runtime1.labels,
      runtime1.patchInProgress
    )

    val clusterName2 = RuntimeName(s"cluster-${UUID.randomUUID.toString}")
    runtimeService
      .createRuntime(userInfo, project, clusterName2, req.copy(labels = Map("a" -> "b", "foo" -> "bar")))
      .unsafeRunSync()
    val runtime2 = runtimeService
      .getRuntime(userInfo, project, clusterName2)
      .unsafeRunSync()
    val listRuntimeResponse2 = ListRuntimeResponse2(
      runtime2.id,
      runtime2.samResource,
      runtime2.clusterName,
      runtime2.googleProject,
      runtime2.auditInfo,
      runtime2.runtimeConfig,
      runtime2.clusterUrl,
      runtime2.status,
      runtime2.labels,
      runtime2.patchInProgress
    )

    runtimeService.listRuntimes(userInfo, None, Map("_labels" -> "foo=bar")).unsafeRunSync().toSet shouldBe Set(
      listRuntimeResponse1,
      listRuntimeResponse2
    )
    runtimeService.listRuntimes(userInfo, None, Map("_labels" -> "foo=bar,bam=yes")).unsafeRunSync.toSet shouldBe Set(
      listRuntimeResponse1
    )
    runtimeService
      .listRuntimes(userInfo, None, Map("_labels" -> "foo=bar,bam=yes,vcf=no"))
      .unsafeToFuture
      .futureValue
      .toSet shouldBe Set(listRuntimeResponse1)
    runtimeService.listRuntimes(userInfo, None, Map("_labels" -> "a=b")).unsafeRunSync().toSet shouldBe Set(
      listRuntimeResponse2
    )
    runtimeService.listRuntimes(userInfo, None, Map("_labels" -> "baz=biz")).unsafeRunSync().toSet shouldBe Set.empty
    runtimeService.listRuntimes(userInfo, None, Map("_labels" -> "A=B")).unsafeRunSync().toSet shouldBe Set(
      listRuntimeResponse2
    ) // labels are not case sensitive because MySQL
    runtimeService
      .listRuntimes(userInfo, None, Map("_labels" -> "foo%3Dbar"))
      .attempt
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .isInstanceOf[ParseLabelsException] shouldBe true
    runtimeService
      .listRuntimes(userInfo, None, Map("_labels" -> "foo=bar;bam=yes"))
      .attempt
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .isInstanceOf[ParseLabelsException] shouldBe true
    runtimeService
      .listRuntimes(userInfo, None, Map("_labels" -> "foo=bar,bam"))
      .attempt
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .isInstanceOf[ParseLabelsException] shouldBe true

    runtimeService
      .listRuntimes(userInfo, None, Map("_labels" -> "bogus"))
      .attempt
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .isInstanceOf[ParseLabelsException] shouldBe true

    runtimeService
      .listRuntimes(userInfo, None, Map("_labels" -> "a,b"))
      .attempt
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .isInstanceOf[ParseLabelsException] shouldBe true
  }

  it should "delete a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      publisherQueue <- InspectableQueue.bounded[IO, LeoPubsubMessage](10)
      service = makeRuntimeService(publisherQueue)
      samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(samResource = samResource).save())

      _ <- service.deleteRuntime(
        DeleteRuntimeRequest(userInfo, testRuntime.googleProject, testRuntime.runtimeName, false)
      )
      res <- withLeoPublisher(publisherQueue) {
        for {
          dbRuntimeOpt <- clusterQuery
            .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
            .transaction
          message <- publisherQueue.tryDequeue1
        } yield {
          dbRuntimeOpt.get.status shouldBe RuntimeStatus.Deleting
          message shouldBe (None)
        }
      }
    } yield res

    res.unsafeRunSync()
  }

  it should "delete a runtime with disk properly" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      publisherQueue <- InspectableQueue.bounded[IO, LeoPubsubMessage](10)
      service = makeRuntimeService(publisherQueue)
      pd <- makePersistentDisk().save()
      testRuntime <- IO(
        makeCluster(1).saveWithRuntimeConfig(
          RuntimeConfig
            .GceWithPdConfig(MachineTypeName("n1-standard-4"), Some(pd.id), bootDiskSize = DiskSize(50))
        )
      )

      _ <- service.deleteRuntime(
        DeleteRuntimeRequest(userInfo, testRuntime.googleProject, testRuntime.runtimeName, true)
      )
      diskStatus <- persistentDiskQuery.getStatus(pd.id).transaction
      _ = diskStatus shouldBe Some(DiskStatus.Deleting)
      res <- withLeoPublisher(publisherQueue) {
        for {
          dbRuntimeOpt <- clusterQuery
            .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
            .transaction
          message <- publisherQueue.tryDequeue1
        } yield {
          dbRuntimeOpt.get.status shouldBe RuntimeStatus.Deleting
          message shouldBe (None)
        }
      }
    } yield res

    res.unsafeRunSync()
  }

  it should "stop a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      publisherQueue <- InspectableQueue.bounded[IO, LeoPubsubMessage](10)
      service = makeRuntimeService(publisherQueue)
      samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(samResource = samResource).save())

      _ <- service.stopRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName)
      res <- withLeoPublisher(publisherQueue) {
        for {
          dbRuntimeOpt <- clusterQuery
            .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
            .transaction
          message <- publisherQueue.tryDequeue1
        } yield {
          dbRuntimeOpt.get.status shouldBe RuntimeStatus.Stopping
          message shouldBe (None)
        }
      }
    } yield res

    res.unsafeRunSync()
  }

  it should "start a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      publisherQueue <- InspectableQueue.bounded[IO, LeoPubsubMessage](10)
      service = makeRuntimeService(publisherQueue)
      samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(samResource = samResource, status = RuntimeStatus.Stopped).save())

      _ <- service.startRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName)
      res <- withLeoPublisher(publisherQueue) {
        for {
          dbRuntimeOpt <- clusterQuery
            .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
            .transaction
          message <- publisherQueue.tryDequeue1
        } yield {
          dbRuntimeOpt.get.status shouldBe RuntimeStatus.Starting
          message shouldBe (None)
        }
      }
    } yield res

    res.unsafeRunSync()
  }

  it should "update autopause" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      _ <- publisherQueue.tryDequeueChunk1(10)
      samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(samResource = samResource, status = RuntimeStatus.Running).save())
      req = UpdateRuntimeRequest(None, false, Some(true), Some(120.minutes), Map.empty, Set.empty)

      _ <- runtimeService.updateRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName, req)
      dbRuntimeOpt <- clusterQuery
        .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
        .transaction
      dbRuntime = dbRuntimeOpt.get
      messageOpt <- publisherQueue.tryDequeue1
    } yield {
      dbRuntime.autopauseThreshold shouldBe 120
      messageOpt shouldBe None
    }

    res.unsafeRunSync()
  }

  val reqMaps: List[LabelMap] = List(
    Map.empty,
    Map("apples" -> "are_great", "grapes" -> "are_cool"),
    Map("new_entry" -> "i_am_new")
  )

  val startLabelMap: LabelMap = Map("apples" -> "to_oranges", "grapes" -> "make_wine")

  val finalUpsertMaps: List[LabelMap] = List(
    Map("apples" -> "to_oranges", "grapes" -> "make_wine"),
    Map("apples" -> "are_great", "grapes" -> "are_cool"),
    Map("apples" -> "to_oranges", "grapes" -> "make_wine", "new_entry" -> "i_am_new")
  )

  reqMaps.foreach { upsertLabels =>
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    it should s"Process upsert labels correctly for $upsertLabels" in isolatedDbTest {
      val res = for {
        samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
        testRuntime <- IO(
          makeCluster(1)
            .copy(samResource = samResource, status = RuntimeStatus.Running, labels = startLabelMap)
            .save()
        )
        req = UpdateRuntimeRequest(None, false, Some(true), Some(120.minutes), upsertLabels, Set.empty)
        _ <- runtimeService.updateRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName, req)
        dbLabelMap <- labelQuery.getAllForResource(testRuntime.id, LabelResourceType.runtime).transaction
        _ <- publisherQueue.tryDequeue1

      } yield {
        finalUpsertMaps.contains(dbLabelMap) shouldBe true
      }
      res.unsafeRunSync()
    }
  }

  val deleteLists: List[Set[String]] = List(
    Set(""),
    Set("apples"),
    Set("apples", "oranges"),
    Set("apples", "grapes")
  )

  val finalDeleteMaps: List[LabelMap] = List(
    Map("grapes" -> "make_wine"),
    Map("apples" -> "to_oranges", "grapes" -> "make_wine"),
    Map.empty
  )

  deleteLists.foreach { deleteLabelSet =>
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    it should s"Process reqlabels correctly for $deleteLabelSet" in isolatedDbTest {
      val res = for {
        samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
        testRuntime <- IO(
          makeCluster(1)
            .copy(samResource = samResource, status = RuntimeStatus.Running, labels = startLabelMap)
            .save()
        )
        req = UpdateRuntimeRequest(None, false, Some(true), Some(120.minutes), Map.empty, deleteLabelSet)
        _ <- runtimeService.updateRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName, req)
        dbLabelMap <- labelQuery.getAllForResource(testRuntime.id, LabelResourceType.runtime).transaction
        _ <- publisherQueue.tryDequeue1
      } yield {
        finalDeleteMaps.contains(dbLabelMap) shouldBe true
      }
      res.unsafeRunSync()
    }
  }

  List(RuntimeStatus.Creating, RuntimeStatus.Stopping, RuntimeStatus.Deleting, RuntimeStatus.Starting).foreach {
    status =>
      val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
      it should s"fail to update a runtime in $status status" in isolatedDbTest {
        val res = for {
          samResource <- IO(RuntimeSamResourceId(UUID.randomUUID.toString))
          testRuntime <- IO(makeCluster(1).copy(samResource = samResource, status = status).save())
          req = UpdateRuntimeRequest(None, false, Some(true), Some(120.minutes), Map.empty, Set.empty)
          fail <- runtimeService
            .updateRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName, req)
            .attempt
        } yield {
          fail shouldBe Left(RuntimeCannotBeUpdatedException(testRuntime.projectNameString, testRuntime.status))
        }
        res.unsafeRunSync()
      }
  }

  "RuntimeServiceInterp.processUpdateRuntimeConfigRequest" should "fail to update the wrong cloud service type" in {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(None, Some(DiskSize(100)), None, None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      fail <- runtimeService.processUpdateRuntimeConfigRequest(req, false, testClusterRecord, gceRuntimeConfig).attempt
    } yield {
      fail shouldBe Left(
        WrongCloudServiceException(CloudService.GCE, CloudService.Dataproc, ctx.traceId)
      )
    }
    res.unsafeRunSync()
  }

  "RuntimeServiceInterp.processUpdateGceConfigRequest" should "not update a GCE runtime when there are no changes" in {
    val req = UpdateRuntimeConfigRequest.GceConfig(Some(gceRuntimeConfig.machineType), Some(gceRuntimeConfig.diskSize))
    val res = for {
      _ <- runtimeService.processUpdateRuntimeConfigRequest(req, false, testClusterRecord, gceRuntimeConfig)
      messageOpt <- publisherQueue.tryDequeue1
    } yield {
      messageOpt shouldBe None
    }
    res.unsafeRunSync()
  }

  it should "update patchInProgress flag if stopToUpdateMachineType is true" in isolatedDbTest {
    val req =
      UpdateRuntimeConfigRequest.GceConfig(Some(MachineTypeName("n1-standard-8")), Some(gceRuntimeConfig.diskSize))
    val runtime = testCluster.copy(status = RuntimeStatus.Running)
    val res = for {
      ctx <- appContext.ask[AppContext]
      savedRuntime <- IO(runtime.save())
      clusterRecordOpt <- clusterQuery
        .getActiveClusterRecordByName(runtime.googleProject, runtime.runtimeName)
        .transaction
      _ <- runtimeService.processUpdateRuntimeConfigRequest(
        req,
        true,
        clusterRecordOpt.getOrElse(throw new Exception(s"cluster ${savedRuntime.projectNameString} not found")),
        gceRuntimeConfig
      )
      patchInProgress <- patchQuery.isInprogress(savedRuntime.id).transaction
      message <- publisherQueue.dequeue1
    } yield {
      patchInProgress shouldBe (true)
      message shouldBe UpdateRuntimeMessage(savedRuntime.id,
                                            Some(MachineTypeName("n1-standard-8")),
                                            true,
                                            None,
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "update a GCE machine type in Stopped state" in {
    val req = UpdateRuntimeConfigRequest.GceConfig(Some(MachineTypeName("n1-micro-2")), None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      _ <- runtimeService.processUpdateRuntimeConfigRequest(req,
                                                            false,
                                                            testClusterRecord.copy(status = RuntimeStatus.Stopped),
                                                            gceRuntimeConfig)
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(testClusterRecord.id,
                                            Some(MachineTypeName("n1-micro-2")),
                                            false,
                                            None,
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "update a GCE machine type in Running state" in {
    val req = UpdateRuntimeConfigRequest.GceConfig(Some(MachineTypeName("n1-micro-2")), None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      _ <- runtimeService.processUpdateRuntimeConfigRequest(req,
                                                            true,
                                                            testClusterRecord.copy(status = RuntimeStatus.Running),
                                                            gceRuntimeConfig)
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(testClusterRecord.id,
                                            Some(MachineTypeName("n1-micro-2")),
                                            true,
                                            None,
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "fail to update a GCE machine type in Running state with allowStop set to false" in {
    val req = UpdateRuntimeConfigRequest.GceConfig(Some(MachineTypeName("n1-micro-2")), None)
    val res = for {
      _ <- runtimeService.processUpdateRuntimeConfigRequest(req,
                                                            false,
                                                            testClusterRecord.copy(status = RuntimeStatus.Running),
                                                            gceRuntimeConfig)
    } yield ()
    res.attempt.unsafeRunSync() shouldBe Left(
      RuntimeMachineTypeCannotBeChangedException(testClusterRecord.projectNameString, RuntimeStatus.Running)
    )
  }

  it should "increase the disk on a GCE runtime" in {
    val req = UpdateRuntimeConfigRequest.GceConfig(None, Some(DiskSize(1024)))
    val res = for {
      ctx <- appContext.ask[AppContext]
      _ <- runtimeService.processUpdateRuntimeConfigRequest(req, false, testClusterRecord, gceRuntimeConfig)
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(testCluster.id,
                                            None,
                                            true,
                                            Some(DiskUpdate.NoPdSizeUpdate(DiskSize(1024))),
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "increase the persistent disk is attached to a GCE runtime" in isolatedDbTest {
    val disk = makePersistentDisk(None).save().unsafeRunSync()
    val req = UpdateRuntimeConfigRequest.GceConfig(None, Some(DiskSize(1024)))
    val res = for {
      ctx <- appContext.ask[AppContext]
      _ <- runtimeService.processUpdateRuntimeConfigRequest(
        req,
        true,
        testClusterRecord,
        gceWithPdRuntimeConfig.copy(persistentDiskId = Some(disk.id))
      )
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(testCluster.id,
                                            None,
                                            true,
                                            Some(DiskUpdate.PdSizeUpdate(disk.id, disk.name, DiskSize(1024))),
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "fail to increase the disk on a GCE runtime if allowStop is false" in {
    val disk = makePersistentDisk(None).save().unsafeRunSync()
    val req = UpdateRuntimeConfigRequest.GceConfig(None, Some(DiskSize(1024)))
    val res = for {
      _ <- runtimeService.processUpdateRuntimeConfigRequest(
        req,
        false,
        testClusterRecord,
        gceWithPdRuntimeConfig.copy(persistentDiskId = Some(disk.id))
      )
    } yield ()
    res.attempt.unsafeRunSync() shouldBe Left(RuntimeDiskSizeCannotBeChangedException(testCluster.projectNameString))
  }

  it should "fail to decrease the disk on a GCE runtime" in {
    val req = UpdateRuntimeConfigRequest.GceConfig(None, Some(DiskSize(50)))
    val res = for {
      _ <- runtimeService.processUpdateRuntimeConfigRequest(req, false, testClusterRecord, gceRuntimeConfig)
    } yield ()
    res.attempt.unsafeRunSync() shouldBe Left(
      RuntimeDiskSizeCannotBeDecreasedException(testClusterRecord.projectNameString)
    )
  }

  "RuntimeServiceInterp.processUpdateDataprocConfigRequest" should "not update a Dataproc runtime when there are no changes" in isolatedDbTest {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(
      Some(defaultDataprocRuntimeConfig.masterMachineType),
      Some(defaultDataprocRuntimeConfig.masterDiskSize),
      Some(defaultDataprocRuntimeConfig.numberOfWorkers),
      defaultDataprocRuntimeConfig.numberOfPreemptibleWorkers
    )
    val res = for {
      ctx <- appContext.ask[AppContext]
      cluster = testCluster.copy(dataprocInstances =
        Set(
          DataprocInstance(
            DataprocInstanceKey(testCluster.googleProject, Config.gceConfig.zoneName, InstanceName("instance-0")),
            1,
            GceInstanceStatus.Running,
            Some(IP("")),
            DataprocRole.Master,
            ctx.now
          )
        )
      )
      _ <- IO(cluster.saveWithRuntimeConfig(defaultDataprocRuntimeConfig))
      clusterRecord <- clusterQuery
        .getActiveClusterRecordByName(testCluster.googleProject, testCluster.runtimeName)
        .transaction
      _ <- runtimeService.processUpdateDataprocConfigRequest(req,
                                                             false,
                                                             clusterRecord.get,
                                                             defaultDataprocRuntimeConfig)
      messageOpt <- publisherQueue.tryDequeue1
    } yield {
      messageOpt shouldBe None
    }
    res.unsafeRunSync()
  }

  it should "disallow updating dataproc cluster number of workers if runtime is not Running" in {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(None, None, Some(50), None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      res <- runtimeService
        .processUpdateDataprocConfigRequest(req,
                                            false,
                                            testClusterRecord.copy(status = RuntimeStatus.Starting),
                                            defaultDataprocRuntimeConfig)
        .attempt
    } yield {
      val expectedException = new LeoException(
        s"Bad request. Number of workers can only be updated if the dataproc cluster is Running. Cluster is in Starting currently",
        StatusCodes.BadRequest,
        traceId = Option(ctx.traceId)
      )
      res.swap.toOption
        .getOrElse(throw new Exception("this test failed"))
        .asInstanceOf[LeoException] shouldEqual expectedException
    }
    res.unsafeRunSync()
  }

  it should "update Dataproc workers and preemptibles" in isolatedDbTest {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(None, None, Some(50), Some(1000))
    val res = for {
      ctx <- appContext.ask[AppContext]
      cluster = testCluster.copy(
        dataprocInstances = Set(
          DataprocInstance(
            DataprocInstanceKey(testCluster.googleProject, Config.gceConfig.zoneName, InstanceName("instance-0")),
            1,
            GceInstanceStatus.Running,
            Some(IP("")),
            DataprocRole.Master,
            ctx.now
          )
        ),
        status = RuntimeStatus.Running
      )
      _ <- IO(cluster.saveWithRuntimeConfig(defaultDataprocRuntimeConfig))
      clusterRecord <- clusterQuery
        .getActiveClusterRecordByName(cluster.googleProject, cluster.runtimeName)
        .transaction
      _ <- runtimeService.processUpdateDataprocConfigRequest(req,
                                                             false,
                                                             clusterRecord.get,
                                                             defaultDataprocRuntimeConfig)
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(clusterRecord.get.id,
                                            None,
                                            false,
                                            None,
                                            Some(50),
                                            Some(1000),
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "update a Dataproc master machine type in Stopped state" in isolatedDbTest {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(Some(MachineTypeName("n1-micro-2")), None, None, None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      cluster = testCluster.copy(dataprocInstances =
        Set(
          DataprocInstance(
            DataprocInstanceKey(testCluster.googleProject, Config.gceConfig.zoneName, InstanceName("instance-0")),
            1,
            GceInstanceStatus.Running,
            Some(IP("")),
            DataprocRole.Master,
            ctx.now
          )
        )
      )
      _ <- IO(cluster.saveWithRuntimeConfig(defaultDataprocRuntimeConfig))
      clusterRecord <- clusterQuery
        .getActiveClusterRecordByName(testCluster.googleProject, testCluster.runtimeName)
        .transaction
      _ <- runtimeService.processUpdateDataprocConfigRequest(req,
                                                             false,
                                                             clusterRecord.get.copy(status = RuntimeStatus.Stopped),
                                                             defaultDataprocRuntimeConfig)
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(clusterRecord.get.id,
                                            Some(MachineTypeName("n1-micro-2")),
                                            false,
                                            None,
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "update a Dataproc machine type in Running state" in isolatedDbTest {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(Some(MachineTypeName("n1-micro-2")), None, None, None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      cluster = testCluster.copy(dataprocInstances =
        Set(
          DataprocInstance(
            DataprocInstanceKey(testCluster.googleProject, Config.gceConfig.zoneName, InstanceName("instance-0")),
            1,
            GceInstanceStatus.Running,
            Some(IP("")),
            DataprocRole.Master,
            ctx.now
          )
        )
      )
      _ <- IO(cluster.saveWithRuntimeConfig(defaultDataprocRuntimeConfig))
      clusterRecord <- clusterQuery
        .getActiveClusterRecordByName(testCluster.googleProject, testCluster.runtimeName)
        .transaction
      _ <- runtimeService.processUpdateDataprocConfigRequest(req,
                                                             true,
                                                             clusterRecord.get.copy(status = RuntimeStatus.Running),
                                                             defaultDataprocRuntimeConfig)
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(clusterRecord.get.id,
                                            Some(MachineTypeName("n1-micro-2")),
                                            true,
                                            None,
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "fail to update a Dataproc machine type in Running state with allowStop set to false" in {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(Some(MachineTypeName("n1-micro-2")), None, None, None)
    val res = for {
      _ <- runtimeService.processUpdateDataprocConfigRequest(req,
                                                             false,
                                                             testClusterRecord.copy(status = RuntimeStatus.Running),
                                                             defaultDataprocRuntimeConfig)
    } yield ()
    res.attempt.unsafeRunSync() shouldBe Left(
      RuntimeMachineTypeCannotBeChangedException(testClusterRecord.projectNameString, RuntimeStatus.Running)
    )
  }

  it should "fail to update a Dataproc machine type when workers are also updated" in {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(Some(MachineTypeName("n1-micro-2")), None, Some(50), None)
    val res = for {
      _ <- runtimeService.processUpdateDataprocConfigRequest(req,
                                                             false,
                                                             testClusterRecord.copy(status = RuntimeStatus.Running),
                                                             defaultDataprocRuntimeConfig)
    } yield ()
    res.attempt.unsafeRunSync().isLeft shouldBe true
  }

  it should "increase the disk on a Dataproc runtime" in isolatedDbTest {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(None, Some(DiskSize(1024)), None, None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      masterInstance = DataprocInstance(
        DataprocInstanceKey(testCluster.googleProject, Config.gceConfig.zoneName, InstanceName("instance-0")),
        1,
        GceInstanceStatus.Running,
        Some(IP("")),
        DataprocRole.Master,
        ctx.now
      )
      cluster = testCluster.copy(dataprocInstances =
        Set(
          masterInstance
        )
      )
      _ <- IO(cluster.saveWithRuntimeConfig(defaultDataprocRuntimeConfig))
      clusterRecord <- clusterQuery
        .getActiveClusterRecordByName(testCluster.googleProject, testCluster.runtimeName)
        .transaction
      _ <- runtimeService.processUpdateDataprocConfigRequest(req, true, clusterRecord.get, defaultDataprocRuntimeConfig)
      message <- publisherQueue.dequeue1
    } yield {
      message shouldBe UpdateRuntimeMessage(clusterRecord.get.id,
                                            None,
                                            true,
                                            Some(DiskUpdate.Dataproc(DiskSize(1024), masterInstance)),
                                            None,
                                            None,
                                            Some(ctx.traceId))
    }
    res.unsafeRunSync()
  }

  it should "fail to decrease the disk on a Dataproc runtime" in isolatedDbTest {
    val req = UpdateRuntimeConfigRequest.DataprocConfig(None, Some(DiskSize(50)), None, None)
    val res = for {
      ctx <- appContext.ask[AppContext]
      cluster = testCluster.copy(dataprocInstances =
        Set(
          DataprocInstance(
            DataprocInstanceKey(testCluster.googleProject, Config.gceConfig.zoneName, InstanceName("instance-0")),
            1,
            GceInstanceStatus.Running,
            Some(IP("")),
            DataprocRole.Master,
            ctx.now
          )
        )
      )
      _ <- IO(cluster.saveWithRuntimeConfig(defaultDataprocRuntimeConfig))
      clusterRecord <- clusterQuery
        .getActiveClusterRecordByName(testCluster.googleProject, testCluster.runtimeName)
        .transaction
      _ <- runtimeService.processUpdateDataprocConfigRequest(req, true, clusterRecord.get, defaultDataprocRuntimeConfig)
    } yield ()
    res.attempt.unsafeRunSync() shouldBe Left(
      RuntimeDiskSizeCannotBeDecreasedException(testClusterRecord.projectNameString)
    )
  }

  "RuntimeServiceInterp.processDiskConfigRequest" should "process a create disk request" in isolatedDbTest {
    val req = PersistentDiskRequest(diskName, Some(DiskSize(500)), None, Map("foo" -> "bar"))
    val res = for {
      context <- ctx.ask[AppContext]
      diskResult <- RuntimeServiceInterp.processPersistentDiskRequest(req,
                                                                      project,
                                                                      userInfo,
                                                                      serviceAccount,
                                                                      FormattedBy.GCE,
                                                                      whitelistAuthProvider,
                                                                      Config.persistentDiskConfig)
      disk = diskResult.disk
      persistedDisk <- persistentDiskQuery.getById(disk.id).transaction
    } yield {
      diskResult.creationNeeded shouldBe true
      disk.googleProject shouldBe project
      disk.zone shouldBe Config.persistentDiskConfig.zone
      disk.name shouldBe diskName
      disk.googleId shouldBe None
      disk.status shouldBe DiskStatus.Creating
      disk.auditInfo.creator shouldBe userInfo.userEmail
      disk.auditInfo.createdDate shouldBe context.now
      disk.auditInfo.destroyedDate shouldBe None
      disk.auditInfo.dateAccessed shouldBe context.now
      disk.size shouldBe DiskSize(500)
      disk.diskType shouldBe Config.persistentDiskConfig.defaultDiskType
      disk.blockSize shouldBe Config.persistentDiskConfig.defaultBlockSizeBytes
      disk.labels shouldBe DefaultDiskLabels(diskName, project, userInfo.userEmail, serviceAccount).toMap ++ Map(
        "foo" -> "bar"
      )

      persistedDisk shouldBe 'defined
      persistedDisk.get shouldEqual disk
    }

    res.unsafeRunSync()
  }

  it should "return existing disk if a disk with the same name already exists" in isolatedDbTest {
    val res = for {
      t <- ctx.ask[AppContext]
      disk <- makePersistentDisk(None).save()
      req = PersistentDiskRequest(disk.name, Some(DiskSize(50)), None, Map("foo" -> "bar"))
      returnedDisk <- RuntimeServiceInterp
        .processPersistentDiskRequest(req,
                                      project,
                                      userInfo,
                                      serviceAccount,
                                      FormattedBy.GCE,
                                      whitelistAuthProvider,
                                      Config.persistentDiskConfig)
        .attempt
    } yield {
      returnedDisk shouldBe Right(PersistentDiskRequestResult(disk, false))
    }

    res.unsafeRunSync()
  }

  it should "fail to create a disk when caller has no permission" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("badUser"), WorkbenchEmail("badEmail"), 0)
    val req = PersistentDiskRequest(diskName, Some(DiskSize(500)), None, Map("foo" -> "bar"))

    val thrown = the[ForbiddenError] thrownBy {
      RuntimeServiceInterp
        .processPersistentDiskRequest(req,
                                      project,
                                      userInfo,
                                      serviceAccount,
                                      FormattedBy.GCE,
                                      whitelistAuthProvider,
                                      Config.persistentDiskConfig)
        .unsafeRunSync()
    }

    thrown shouldBe ForbiddenError(userInfo.userEmail)
  }

  it should "fail to process a disk reference when the disk is already attached" in isolatedDbTest {
    val res = for {
      t <- ctx.ask[AppContext]
      savedDisk <- makePersistentDisk(None).save()
      _ <- IO(
        makeCluster(1).saveWithRuntimeConfig(
          RuntimeConfig.GceWithPdConfig(defaultMachineType, Some(savedDisk.id), bootDiskSize = DiskSize(50))
        )
      )
      req = PersistentDiskRequest(savedDisk.name, Some(savedDisk.size), Some(savedDisk.diskType), savedDisk.labels)
      err <- RuntimeServiceInterp
        .processPersistentDiskRequest(req,
                                      project,
                                      userInfo,
                                      serviceAccount,
                                      FormattedBy.GCE,
                                      whitelistAuthProvider,
                                      Config.persistentDiskConfig)
        .attempt
    } yield {
      err shouldBe Left(DiskAlreadyAttachedException(project, savedDisk.name, t.traceId))
    }

    res.unsafeRunSync()
  }

  it should "fail to process a disk reference when the disk is already formatted by another app" in isolatedDbTest {
    val res = for {
      t <- ctx.ask[AppContext]
      gceDisk <- makePersistentDisk(Some(DiskName("gceDisk")), Some(FormattedBy.GCE)).save()
      req = PersistentDiskRequest(gceDisk.name, Some(gceDisk.size), Some(gceDisk.diskType), gceDisk.labels)
      formatGceDiskError <- RuntimeServiceInterp
        .processPersistentDiskRequest(req,
                                      project,
                                      userInfo,
                                      serviceAccount,
                                      FormattedBy.Galaxy,
                                      whitelistAuthProvider,
                                      Config.persistentDiskConfig)
        .attempt
      galaxyDisk <- makePersistentDisk(Some(DiskName("galaxyDisk")), Some(FormattedBy.Galaxy)).save()
      req = PersistentDiskRequest(galaxyDisk.name, Some(galaxyDisk.size), Some(galaxyDisk.diskType), galaxyDisk.labels)
      formatGalaxyDiskError <- RuntimeServiceInterp
        .processPersistentDiskRequest(req,
                                      project,
                                      userInfo,
                                      serviceAccount,
                                      FormattedBy.GCE,
                                      whitelistAuthProvider,
                                      Config.persistentDiskConfig)
        .attempt
    } yield {
      formatGceDiskError shouldBe Left(
        DiskAlreadyFormattedByOtherApp(project, gceDisk.name, t.traceId, FormattedBy.GCE)
      )
      formatGalaxyDiskError shouldBe Left(
        DiskAlreadyFormattedByOtherApp(project, galaxyDisk.name, t.traceId, FormattedBy.Galaxy)
      )
    }

    res.unsafeRunSync()
  }

  it should "fail to attach a disk when caller has no attach permission" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("badUser"), WorkbenchEmail("badEmail"), 0)
    val res = for {
      savedDisk <- makePersistentDisk(None).save()
      req = PersistentDiskRequest(savedDisk.name, Some(savedDisk.size), Some(savedDisk.diskType), savedDisk.labels)
      _ <- RuntimeServiceInterp.processPersistentDiskRequest(req,
                                                             project,
                                                             userInfo,
                                                             serviceAccount,
                                                             FormattedBy.GCE,
                                                             whitelistAuthProvider,
                                                             Config.persistentDiskConfig)
    } yield ()

    val thrown = the[ForbiddenError] thrownBy {
      res.unsafeRunSync()
    }

    thrown shouldBe ForbiddenError(userInfo.userEmail)
  }

  private def withLeoPublisher(
    publisherQueue: InspectableQueue[IO, LeoPubsubMessage]
  )(validations: IO[Assertion]): IO[Assertion] = {
    val leoPublisher = new LeoPublisher[IO](publisherQueue, new FakeGooglePublisher)
    withInfiniteStream(leoPublisher.process, validations)
  }
}
