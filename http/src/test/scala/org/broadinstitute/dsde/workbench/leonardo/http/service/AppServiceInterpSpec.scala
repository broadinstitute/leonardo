package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import java.time.Instant
import cats.effect.IO
import fs2.concurrent.InspectableQueue
import org.broadinstitute.dsde.workbench.google2.DiskName
import org.broadinstitute.dsde.workbench.google2.mock.FakeGooglePublisher
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.KubernetesTestData._
import org.broadinstitute.dsde.workbench.leonardo.db.{
  KubernetesAppCreationException,
  KubernetesServiceDbQueries,
  TestComponent,
  appQuery,
  kubernetesClusterQuery,
  persistentDiskQuery,
  _
}
import org.broadinstitute.dsde.workbench.leonardo.monitor.ClusterNodepoolAction.CreateNodepool
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage.{
  BatchNodepoolCreateMessage,
  CreateAppMessage,
  DeleteAppMessage
}
import org.broadinstitute.dsde.workbench.leonardo.monitor.{
  ClusterNodepoolAction,
  LeoPubsubMessage,
  LeoPubsubMessageType
}
import org.broadinstitute.dsde.workbench.leonardo.util.QueueFactory
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsp.ChartVersion
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.ExecutionContext.Implicits.global

final class AppServiceInterpSpec extends AnyFlatSpec with LeonardoTestSuite with TestComponent {

  //used when we care about queue state
  def makeInterp(queue: InspectableQueue[IO, LeoPubsubMessage]) =
    new LeoAppServiceInterp[IO](whitelistAuthProvider, serviceAccountProvider, leoKubernetesConfig, queue)
  val kubeServiceInterp = new LeoAppServiceInterp[IO](whitelistAuthProvider,
                                                      serviceAccountProvider,
                                                      leoKubernetesConfig,
                                                      QueueFactory.makePublisherQueue())

  it should "determine patch version bump correctly" in isolatedDbTest {
    val first = ChartVersion("0.8.0")
    val second = ChartVersion("0.8.2")
    LeoAppServiceInterp.isPatchVersionDifference(first, second) shouldBe (true)
  }

  it should "create an app and a new disk" in isolatedDbTest {
    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val customEnvVars = Map("WORKSPACE_NAME" -> "testWorkspace")
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig), customEnvironmentVariables = customEnvVars)

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    val clusters = dbFutureValue {
      KubernetesServiceDbQueries.listFullApps(Some(project))
    }
    clusters.length shouldEqual 1
    clusters.flatMap(_.nodepools).length shouldEqual 1
    val cluster = clusters.head
    cluster.auditInfo.creator shouldEqual userInfo.userEmail

    val nodepool = clusters.flatMap(_.nodepools).head
    nodepool.machineType shouldEqual appReq.kubernetesRuntimeConfig.get.machineType
    nodepool.numNodes shouldEqual appReq.kubernetesRuntimeConfig.get.numNodes
    nodepool.autoscalingEnabled shouldEqual appReq.kubernetesRuntimeConfig.get.autoscalingEnabled
    nodepool.auditInfo.creator shouldEqual userInfo.userEmail

    clusters.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 1
    val app = clusters.flatMap(_.nodepools).flatMap(_.apps).head
    app.appName shouldEqual appName
    app.chart shouldEqual galaxyChart
    app.auditInfo.creator shouldEqual userInfo.userEmail
    app.customEnvironmentVariables shouldEqual customEnvVars

    val savedDisk = dbFutureValue {
      persistentDiskQuery.getById(app.appResources.disk.get.id)
    }
    savedDisk.map(_.name) shouldEqual Some(diskName)
  }

  it should "create an app in a users existing nodepool" in isolatedDbTest {
    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val customEnvVars = Map("WORKSPACE_NAME" -> "testWorkspace")
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig), customEnvironmentVariables = customEnvVars)

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()
    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }
    dbFutureValue(kubernetesClusterQuery.updateStatus(appResult.get.cluster.id, KubernetesClusterStatus.Running))

    val appName2 = AppName("app2")
    val createDiskConfig2 = PersistentDiskRequest(DiskName("disk2"), None, None, Map.empty)
    val appReq2 =
      createAppRequest.copy(diskConfig = Some(createDiskConfig2), customEnvironmentVariables = customEnvVars)
    kubeServiceInterp.createApp(userInfo, project, appName2, appReq2).unsafeRunSync()

    val clusters = dbFutureValue {
      KubernetesServiceDbQueries.listFullApps(Some(project))
    }

    clusters.flatMap(_.nodepools).length shouldBe 1
    clusters.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 2

    clusters.flatMap(_.nodepools).flatMap(_.apps).map(_.appName).sortBy(_.value) shouldBe List(appName, appName2)
      .sortBy(_.value)
    val app1 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }.get

    val app2 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName2)
    }.get

    app1.nodepool.id shouldBe app2.nodepool.id
  }

  it should "queue the proper message when creating an app and a new disk" in isolatedDbTest {

    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val customEnvVars = Map("WORKSPACE_NAME" -> "testWorkspace")
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig), customEnvironmentVariables = customEnvVars)

    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    val getApp = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }.get

    val getMinimalCluster = dbFutureValue {
      kubernetesClusterQuery.getMinimalClusterById(getApp.cluster.id)
    }.get

    val defaultNodepools = getMinimalCluster.nodepools.filter(_.isDefault)
    defaultNodepools.length shouldBe 1
    val defaultNodepool = defaultNodepools.head

    val message = publisherQueue.dequeue1.unsafeRunSync()
    message.messageType shouldBe LeoPubsubMessageType.CreateApp
    val createAppMessage = message.asInstanceOf[CreateAppMessage]
    createAppMessage.appId shouldBe getApp.app.id
    createAppMessage.project shouldBe project
    createAppMessage.createDisk shouldBe getApp.app.appResources.disk.map(_.id)
    createAppMessage.customEnvironmentVariables shouldBe customEnvVars
    createAppMessage.clusterNodepoolAction shouldBe Some(
      ClusterNodepoolAction.CreateClusterAndNodepool(getMinimalCluster.id, defaultNodepool.id, getApp.nodepool.id)
    )
  }

  it should "not able to create an app with an existing non-used disk" in isolatedDbTest {
    val disk = makePersistentDisk(None)
      .copy(googleProject = project)
      .save()
      .unsafeRunSync()

    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(disk.name, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig))

    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val res = kubeServiceInterp.createApp(userInfo, project, appName, appReq).attempt.unsafeRunSync()

    res.swap.toOption.get.getMessage shouldBe ("Disk is not formatted yet. Only disks previously used by galaxy app can be re-used to create a new galaxy app")
  }

  it should "error creating an app with an existing used disk when WORKSPACE_NAME is not specified" in isolatedDbTest {
    val customEnvVariables = Map(WORKSPACE_NAME_KEY -> "fake_ws")
    val cluster = makeKubeCluster(0).save()
    val nodepool = makeNodepool(1, cluster.id).save()
    val app = makeApp(1, nodepool.id, customEnvVariables).save()
    val disk = makePersistentDisk(None,
                                  formattedBy = Some(FormattedBy.Galaxy),
                                  galaxyRestore = Some(GalaxyRestore(PvcId("pv-id"), PvcId("pv-id2"), app.id)))
      .copy(googleProject = project)
      .save()
      .unsafeRunSync()

    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(disk.name, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig),
                                       customEnvironmentVariables = Map(WORKSPACE_NAME_KEY -> "fake_ws2"))

    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val res = kubeServiceInterp.createApp(userInfo, project, appName, appReq).attempt.unsafeRunSync()
    res.swap.toOption.get.getMessage shouldBe ("workspace name has to be the same as last used app in order to restore data from existing disk")
  }

  it should "create an app with an existing used disk" in isolatedDbTest {
    val customEnvVariables = Map(WORKSPACE_NAME_KEY -> "fake_ws")
    val cluster = makeKubeCluster(0).save()
    val nodepool = makeNodepool(1, cluster.id).save()
    val app = makeApp(1, nodepool.id, customEnvVariables).save()
    val disk = makePersistentDisk(None,
                                  galaxyRestore = Some(GalaxyRestore(PvcId("pv-id"), PvcId("pv-id2"), app.id)),
                                  formattedBy = Some(FormattedBy.Galaxy))
      .copy(googleProject = project)
      .save()
      .unsafeRunSync()

    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(disk.name, None, None, Map.empty)
    val appReq =
      createAppRequest.copy(diskConfig = Some(createDiskConfig), customEnvironmentVariables = customEnvVariables)

    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    val message = publisherQueue.dequeue1.unsafeRunSync()
    message.messageType shouldBe LeoPubsubMessageType.CreateApp
    message.asInstanceOf[CreateAppMessage].createDisk shouldBe None

    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }

    appResult.flatMap(_.app.appResources.disk.map(_.name)) shouldEqual Some(disk.name)
    appResult.map(_.app.appName) shouldEqual Some(appName)
  }

  it should "error creating an app with an existing disk if no restore info found" in isolatedDbTest {
    val disk = makePersistentDisk(None, formattedBy = Some(FormattedBy.Galaxy))
      .copy(googleProject = project)
      .save()
      .unsafeRunSync()

    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(disk.name, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig))

    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val res = kubeServiceInterp.createApp(userInfo, project, appName, appReq).attempt.unsafeRunSync()

    res.swap.toOption.get.getMessage shouldBe ("Existing disk found, but no restore info found in DB")
  }

  it should "error on creation of a galaxy app without a disk" in isolatedDbTest {
    val appName = AppName("app1")
    val appReq = createAppRequest.copy(diskConfig = None, appType = AppType.Galaxy)

    the[AppRequiresDiskException] thrownBy {
      kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()
    }
  }

  it should "error on creation if a disk is attached to another app" in isolatedDbTest {
    val customEnvVariables = Map(WORKSPACE_NAME_KEY -> "fake_ws")
    val cluster = makeKubeCluster(0).save()
    val nodepool = makeNodepool(1, cluster.id).save()
    val app = makeApp(1, nodepool.id, customEnvVariables).save()
    val disk = makePersistentDisk(None,
                                  formattedBy = Some(FormattedBy.Galaxy),
                                  galaxyRestore = Some(GalaxyRestore(PvcId("pv-id"), PvcId("pv-id2"), app.id)))
      .copy(googleProject = project)
      .save()
      .unsafeRunSync()
    val appName1 = AppName("app1")
    val appName2 = AppName("app2")

    val createDiskConfig = PersistentDiskRequest(disk.name, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig))

    kubeServiceInterp.createApp(userInfo, project, appName1, appReq).unsafeRunSync()
    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName1)
    }
    appResult.flatMap(_.app.appResources.disk.map(_.name)) shouldEqual Some(disk.name)
    appResult.map(_.app.appName) shouldEqual Some(appName1)

    //we need to update status from creating because we don't allow creation of apps while cluster is creating
    dbFutureValue(kubernetesClusterQuery.updateStatus(appResult.get.cluster.id, KubernetesClusterStatus.Running))

    the[DiskAlreadyAttachedException] thrownBy {
      kubeServiceInterp.createApp(userInfo, project, appName2, appReq).unsafeRunSync()
    }
  }

  it should "error on creation if an app with that name exists" in isolatedDbTest {
    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig))

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    the[AppAlreadyExistsException] thrownBy {
      kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()
    }
  }

  it should "delete an app and update status appropriately" in isolatedDbTest {
    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig))

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    val appResultPreStatusUpdate = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }

    //we can't delete while its creating, so set it to Running
    dbFutureValue(appQuery.updateStatus(appResultPreStatusUpdate.get.app.id, AppStatus.Running))
    dbFutureValue(nodepoolQuery.updateStatus(appResultPreStatusUpdate.get.nodepool.id, NodepoolStatus.Running))

    val appResultPreDelete = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }
    appResultPreDelete.get.app.status shouldEqual AppStatus.Running
    appResultPreDelete.get.app.auditInfo.destroyedDate shouldBe None

    val params = DeleteAppRequest(userInfo, project, appName, false)
    kubeServiceInterp.deleteApp(params).unsafeRunSync()
    val clusterPostDelete = dbFutureValue {
      KubernetesServiceDbQueries.listFullApps(Some(project), includeDeleted = true)
    }

    clusterPostDelete.length shouldEqual 1
    val nodepool = clusterPostDelete.head.nodepools.head
    nodepool.status shouldEqual NodepoolStatus.Running
    val app = nodepool.apps.head
    app.status shouldEqual AppStatus.Predeleting

    //throw away create message
    publisherQueue.dequeue1.unsafeRunSync()

    val message = publisherQueue.dequeue1.unsafeRunSync()
    message.messageType shouldBe LeoPubsubMessageType.DeleteApp
    val deleteAppMessage = message.asInstanceOf[DeleteAppMessage]
    deleteAppMessage.appId shouldBe app.id
    deleteAppMessage.project shouldBe project
    deleteAppMessage.diskId shouldBe None
  }

  it should "error on delete if app is in a status that cannot be deleted" in isolatedDbTest {
    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig))

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    val appResultPreDelete = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }

    //TODO: update this once create publishes pubsub message
    appResultPreDelete.get.app.status shouldEqual AppStatus.Precreating
    appResultPreDelete.get.app.auditInfo.destroyedDate shouldBe None

    val params = DeleteAppRequest(userInfo, project, appName, false)
    the[AppCannotBeDeletedException] thrownBy {
      kubeServiceInterp.deleteApp(params).unsafeRunSync()
    }
  }

  it should "delete an app in Error status" in isolatedDbTest {
    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig))

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    val appResultPreStatusUpdate = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }

    // Set the app status to Error and the nodepool status to Deleted to simulate an error during
    // app creation.
    dbFutureValue(appQuery.updateStatus(appResultPreStatusUpdate.get.app.id, AppStatus.Error))
    dbFutureValue(nodepoolQuery.markAsDeleted(appResultPreStatusUpdate.get.nodepool.id, Instant.now))

    val appResultPreDelete = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }
    appResultPreDelete.get.app.status shouldEqual AppStatus.Error
    appResultPreDelete.get.app.auditInfo.destroyedDate shouldBe None
    appResultPreDelete.get.nodepool.status shouldBe NodepoolStatus.Deleted
    appResultPreDelete.get.nodepool.auditInfo.destroyedDate shouldBe 'defined

    // Call deleteApp
    val params = DeleteAppRequest(userInfo, project, appName, false)
    kubeServiceInterp.deleteApp(params).unsafeRunSync()

    // Verify database state
    val clusterPostDelete = dbFutureValue {
      KubernetesServiceDbQueries.listFullApps(Some(project), includeDeleted = true)
    }
    clusterPostDelete.length shouldEqual 1
    val nodepool = clusterPostDelete.head.nodepools.head
    nodepool.status shouldEqual NodepoolStatus.Deleted
    nodepool.auditInfo.destroyedDate shouldBe 'defined
    val app = nodepool.apps.head
    app.status shouldEqual AppStatus.Deleted
    app.auditInfo.destroyedDate shouldBe 'defined

    // throw away create message
    publisherQueue.dequeue1.unsafeRunSync() shouldBe a[CreateAppMessage]

    // Verify no DeleteAppMessage message generated
    publisherQueue.tryDequeue1.unsafeRunSync() shouldBe None
  }

  it should "list apps" in isolatedDbTest {
    val appName1 = AppName("app1")
    val appName2 = AppName("app2")
    val appName3 = AppName("app3")
    val createDiskConfig1 = PersistentDiskRequest(diskName, None, None, Map.empty)
    val appReq1 = createAppRequest.copy(diskConfig = Some(createDiskConfig1))
    val diskName2 = DiskName("newDiskName")
    val createDiskConfig2 = PersistentDiskRequest(diskName2, None, None, Map.empty)
    val appReq2 = createAppRequest.copy(diskConfig = Some(createDiskConfig2))

    kubeServiceInterp.createApp(userInfo, project, appName1, appReq1).unsafeRunSync()

    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName1)
    }
    dbFutureValue(kubernetesClusterQuery.updateStatus(appResult.get.cluster.id, KubernetesClusterStatus.Running))

    kubeServiceInterp.createApp(userInfo, project, appName2, appReq2).unsafeRunSync()
    kubeServiceInterp.createApp(userInfo, project2, appName3, appReq1).unsafeRunSync()

    val listAllApps = kubeServiceInterp.listApp(userInfo, None, Map()).unsafeRunSync()
    listAllApps.length shouldEqual 3
    listAllApps.map(_.appName) should contain(appName1)
    listAllApps.map(_.appName) should contain(appName2)
    listAllApps.map(_.appName) should contain(appName3)
    listAllApps.map(_.diskName).sortBy(_.get.value) shouldBe Vector(Some(diskName), Some(diskName), Some(diskName2))
      .sortBy(_.get.value)

    val listProject1Apps = kubeServiceInterp.listApp(userInfo, Some(project), Map()).unsafeRunSync()
    listProject1Apps.length shouldBe 2
    listProject1Apps.map(_.appName) should contain(appName1)
    listProject1Apps.map(_.appName) should contain(appName2)

    val listProject2Apps = kubeServiceInterp.listApp(userInfo, Some(project2), Map()).unsafeRunSync()
    listProject2Apps.length shouldBe 1
    listProject2Apps.map(_.appName) should contain(appName3)

    val listProject3Apps =
      kubeServiceInterp.listApp(userInfo, Some(GoogleProject("fakeProject")), Map()).unsafeRunSync()
    listProject3Apps.length shouldBe 0
  }

  it should "list apps with labels" in isolatedDbTest {
    val appName1 = AppName("app1")
    val appName2 = AppName("app2")
    val appName3 = AppName("app3")
    val createDiskConfig1 = PersistentDiskRequest(diskName, None, None, Map.empty)
    val label1 = "a" -> "b"
    val label2 = "c" -> "d"
    val labels: LabelMap = Map(label1, label2)
    val appReq1 = createAppRequest.copy(diskConfig = Some(createDiskConfig1))
    val diskName2 = DiskName("newDiskName")
    val createDiskConfig2 = PersistentDiskRequest(diskName2, None, None, Map.empty)
    val appReq2 = createAppRequest.copy(diskConfig = Some(createDiskConfig2), labels = labels)

    kubeServiceInterp.createApp(userInfo, project, appName1, appReq1).unsafeRunSync()

    val app1Result = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName1)
    }

    dbFutureValue(kubernetesClusterQuery.updateStatus(app1Result.get.cluster.id, KubernetesClusterStatus.Running))

    kubeServiceInterp.createApp(userInfo, project, appName2, appReq2).unsafeRunSync()

    val app2Result = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName2)
    }
    app2Result.map(_.app.labels).get.toList should contain(label1)
    app2Result.map(_.app.labels).get.toList should contain(label2)

    kubeServiceInterp.createApp(userInfo, project2, appName3, appReq1).unsafeRunSync()

    val listLabelApp = kubeServiceInterp.listApp(userInfo, None, labels).unsafeRunSync()
    listLabelApp.length shouldEqual 1
    listLabelApp.map(_.appName) should contain(appName2)

    val listPartialLabelApp1 = kubeServiceInterp.listApp(userInfo, None, Map(label1)).unsafeRunSync()
    listPartialLabelApp1.length shouldEqual 1
    listPartialLabelApp1.map(_.appName) should contain(appName2)

    val listPartialLabelApp2 = kubeServiceInterp.listApp(userInfo, None, Map(label2)).unsafeRunSync()
    listPartialLabelApp2.length shouldEqual 1
    listPartialLabelApp2.map(_.appName) should contain(appName2)
  }

  it should "list apps belonging to different users" in isolatedDbTest {
    // Make apps belonging to different users than the calling user
    val res = for {
      savedCluster <- IO(makeKubeCluster(1).save())
      savedNodepool1 <- IO(makeNodepool(1, savedCluster.id).save())
      app1 = LeoLenses.appToCreator.set(WorkbenchEmail("a_different_user1@example.com"))(makeApp(1, savedNodepool1.id))
      _ <- IO(app1.save())

      savedNodepool2 <- IO(makeNodepool(2, savedCluster.id).save())
      app2 = LeoLenses.appToCreator.set(WorkbenchEmail("a_different_user2@example.com"))(makeApp(2, savedNodepool2.id))
      _ <- IO(app2.save())

      listResponse <- kubeServiceInterp.listApp(userInfo, None, Map.empty)
    } yield {
      // Since the calling user is whitelisted in the auth provider, it should return
      // the apps belonging to other users.
      listResponse.map(_.appName).toSet shouldBe Set(app1.appName, app2.appName)
    }

    res.unsafeRunSync()
  }

  it should "get app" in isolatedDbTest {
    val appName1 = AppName("app1")
    val appName2 = AppName("app2")
    val appName3 = AppName("app3")
    val createDiskConfig1 = PersistentDiskRequest(diskName, None, None, Map.empty)
    val appReq1 = createAppRequest.copy(diskConfig = Some(createDiskConfig1))
    val diskName2 = DiskName("newDiskName")
    val createDiskConfig2 = PersistentDiskRequest(diskName2, None, None, Map.empty)
    val appReq2 = createAppRequest.copy(diskConfig = Some(createDiskConfig2))

    kubeServiceInterp.createApp(userInfo, project, appName1, appReq1).unsafeRunSync()

    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName1)
    }
    dbFutureValue(kubernetesClusterQuery.updateStatus(appResult.get.cluster.id, KubernetesClusterStatus.Running))

    kubeServiceInterp.createApp(userInfo, project, appName2, appReq2).unsafeRunSync()

    val diskName3 = DiskName("newDiskName2")
    val createDiskConfig3 = PersistentDiskRequest(diskName3, None, None, Map.empty)
    kubeServiceInterp
      .createApp(userInfo, project2, appName3, appReq1.copy(diskConfig = Some(createDiskConfig3)))
      .unsafeRunSync()

    val getApp1 = kubeServiceInterp.getApp(userInfo, project, appName1).unsafeRunSync()
    getApp1.diskName shouldBe Some(diskName)

    val getApp2 = kubeServiceInterp.getApp(userInfo, project, appName2).unsafeRunSync()
    getApp2.diskName shouldBe Some(diskName2)

    val getApp3 = kubeServiceInterp.getApp(userInfo, project2, appName3).unsafeRunSync()
    getApp3.diskName shouldBe Some(diskName3)
  }

  it should "error on get app if an app does not exist" in isolatedDbTest {
    the[AppNotFoundException] thrownBy {
      kubeServiceInterp.getApp(userInfo, project, AppName("schrodingersApp")).unsafeRunSync()
    }
  }

  it should "successfully batch create nodepools" in isolatedDbTest {
    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    kubeServiceInterp.batchNodepoolCreate(userInfo, project, batchNodepoolCreateRequest).unsafeRunSync()

    val message = publisherQueue.dequeue1.unsafeRunSync()
    message.messageType shouldBe LeoPubsubMessageType.BatchNodepoolCreate
    val batchNodepoolCreateMessage = message.asInstanceOf[BatchNodepoolCreateMessage]
    batchNodepoolCreateMessage.project shouldBe project
    //we add 1 for the default nodepool
    batchNodepoolCreateMessage.nodepools.size shouldBe batchNodepoolCreateRequest.numNodepools.value + 1
    val cluster = dbFutureValue(kubernetesClusterQuery.getMinimalActiveClusterByName(project)).get
    cluster.nodepools.size shouldBe batchNodepoolCreateRequest.numNodepools.value + 1
    cluster.nodepools.filter(_.isDefault).size shouldBe 1
    cluster.nodepools.filterNot(_.isDefault).size shouldBe batchNodepoolCreateRequest.numNodepools.value
    cluster.nodepools.map(_.status).distinct.size shouldBe 1
    cluster.nodepools.map(_.status).distinct shouldBe List(NodepoolStatus.Precreating)
  }

  it should "fail to batch create nodepools if cluster exists in project" in isolatedDbTest {
    val appName = AppName("app1")
    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val customEnvVars = Map("WORKSPACE_NAME" -> "testWorkspace")
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig), customEnvironmentVariables = customEnvVars)

    kubeServiceInterp.createApp(userInfo, project, appName, appReq).unsafeRunSync()

    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(project, appName)
    }

    //it should throw app already exists if an app exists and the cluster is in creating
    the[KubernetesAppCreationException] thrownBy {
      kubeServiceInterp.batchNodepoolCreate(userInfo, project, batchNodepoolCreateRequest).unsafeRunSync()
    }

    //we need to update status from creating because we don't allow creation of anything while cluster is creating
    dbFutureValue(kubernetesClusterQuery.updateStatus(appResult.get.cluster.id, KubernetesClusterStatus.Running))

    //it should throw cluster already exists if the cluster exists in a ready state
    the[ClusterExistsException] thrownBy {
      kubeServiceInterp.batchNodepoolCreate(userInfo, project, batchNodepoolCreateRequest).unsafeRunSync()
    }
  }

  it should "claim a nodepool if some are unclaimed" in isolatedDbTest {
    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val savedCluster1 = makeKubeCluster(1).save()
    val savedNodepool1 = makeNodepool(1, savedCluster1.id).copy(status = NodepoolStatus.Unclaimed).save()

    val preAppCluster = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    preAppCluster.nodepools.size shouldBe 2
    preAppCluster.nodepools.map(_.status).sortBy(_.toString) shouldBe List(NodepoolStatus.Unspecified,
                                                                           NodepoolStatus.Unclaimed).sortBy(_.toString)

    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val customEnvVars = Map("WORKSPACE_NAME" -> "testWorkspace")
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig), customEnvironmentVariables = customEnvVars)

    val appName = AppName("app1")
    kubeServiceInterp
      .createApp(userInfo.copy(userEmail = WorkbenchEmail("user100@example.com")),
                 savedCluster1.googleProject,
                 appName,
                 appReq)
      .unsafeRunSync()
    val cluster = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    cluster.nodepools.size shouldBe 2
    cluster.nodepools.map(_.status).sortBy(_.toString) shouldBe List(NodepoolStatus.Running, NodepoolStatus.Unspecified)
      .sortBy(_.toString)
    cluster.nodepools.filter(_.status == NodepoolStatus.Running).size shouldBe 1
    val claimedNodepool = cluster.nodepools.filter(_.id == savedNodepool1.id).head

    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(savedCluster1.googleProject, appName)
    }.get

    appResult.nodepool.copy(apps = List.empty) shouldBe claimedNodepool

    val message = publisherQueue.dequeue1.unsafeRunSync()
    message.messageType shouldBe LeoPubsubMessageType.CreateApp
    val createAppMessage = message.asInstanceOf[CreateAppMessage]
    createAppMessage.appId shouldBe appResult.app.id
    createAppMessage.clusterNodepoolAction shouldBe None
  }

  it should "be able to claim multiple nodepools" in isolatedDbTest {
    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val savedCluster1 = makeKubeCluster(1).save()
    val savedNodepool1 = makeNodepool(1, savedCluster1.id).copy(status = NodepoolStatus.Unclaimed).save()
    val savedNodepool2 = makeNodepool(2, savedCluster1.id).copy(status = NodepoolStatus.Unclaimed).save()

    val preAppCluster = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    preAppCluster.nodepools.size shouldBe 3
    preAppCluster.nodepools.map(_.status).sortBy(_.toString) shouldBe List(NodepoolStatus.Unspecified,
                                                                           NodepoolStatus.Unclaimed,
                                                                           NodepoolStatus.Unclaimed).sortBy(_.toString)

    val createDiskConfig1 = PersistentDiskRequest(diskName, None, None, Map.empty)
    val customEnvVars = Map("WORKSPACE_NAME" -> "testWorkspace")
    val appReq1 =
      createAppRequest.copy(diskConfig = Some(createDiskConfig1), customEnvironmentVariables = customEnvVars)

    val appName1 = AppName("app1")
    kubeServiceInterp
      .createApp(userInfo.copy(userEmail = WorkbenchEmail("user100@example.com")),
                 savedCluster1.googleProject,
                 appName1,
                 appReq1)
      .unsafeRunSync()
    val clusterAfterApp1 = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    clusterAfterApp1.nodepools.size shouldBe 3
    clusterAfterApp1.nodepools.map(_.status).sortBy(_.toString) shouldBe List(
      NodepoolStatus.Running,
      NodepoolStatus.Unclaimed,
      NodepoolStatus.Unspecified
    ).sortBy(_.toString)
    val claimedNodepool1 = clusterAfterApp1.nodepools.filter(_.id == savedNodepool1.id).head
    claimedNodepool1.status shouldBe NodepoolStatus.Running

    val appResult1 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(savedCluster1.googleProject, appName1)
    }.get

    appResult1.nodepool.copy(apps = List.empty) shouldBe claimedNodepool1

    val message1 = publisherQueue.dequeue1.unsafeRunSync()
    message1.messageType shouldBe LeoPubsubMessageType.CreateApp
    val createAppMessage1 = message1.asInstanceOf[CreateAppMessage]
    createAppMessage1.appId shouldBe appResult1.app.id
    createAppMessage1.clusterNodepoolAction shouldBe None

    val appName2 = AppName("app2")
    val appReq2 = appReq1.copy(diskConfig = Some(createDiskConfig1.copy(name = DiskName("newdisk"))))
    kubeServiceInterp
      .createApp(userInfo.copy(userEmail = WorkbenchEmail("user101@example.com")),
                 savedCluster1.googleProject,
                 appName2,
                 appReq2)
      .unsafeRunSync()
    val clusterAfterApp2 = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    clusterAfterApp2.nodepools.size shouldBe 3
    clusterAfterApp2.nodepools.map(_.status).sortBy(_.toString) shouldBe List(
      NodepoolStatus.Running,
      NodepoolStatus.Running,
      NodepoolStatus.Unspecified
    ).sortBy(_.toString)
    val claimedNodepool2 = clusterAfterApp2.nodepools.filter(_.id == savedNodepool2.id).head
    claimedNodepool2.status shouldBe NodepoolStatus.Running

    val appResult2 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(savedCluster1.googleProject, appName2)
    }.get

    appResult2.nodepool.copy(apps = List.empty) shouldBe claimedNodepool2

    val message2 = publisherQueue.dequeue1.unsafeRunSync()
    message2.messageType shouldBe LeoPubsubMessageType.CreateApp
    val createAppMessage2 = message2.asInstanceOf[CreateAppMessage]
    createAppMessage2.appId shouldBe appResult2.app.id
    createAppMessage2.clusterNodepoolAction shouldBe None
  }

  it should "be able to create a nodepool after a pool is completely claimed" in isolatedDbTest {
    val publisherQueue = QueueFactory.makePublisherQueue()
    val kubeServiceInterp = makeInterp(publisherQueue)
    val savedCluster1 = makeKubeCluster(1).copy(status = KubernetesClusterStatus.Running).save()
    val savedNodepool1 = makeNodepool(1, savedCluster1.id).copy(status = NodepoolStatus.Unclaimed).save()

    val preAppCluster = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    preAppCluster.nodepools.size shouldBe 2
    preAppCluster.nodepools.map(_.status).sortBy(_.toString) shouldBe List(NodepoolStatus.Unspecified,
                                                                           NodepoolStatus.Unclaimed).sortBy(_.toString)

    val createDiskConfig = PersistentDiskRequest(diskName, None, None, Map.empty)
    val customEnvVars = Map("WORKSPACE_NAME" -> "testWorkspace")
    val appReq = createAppRequest.copy(diskConfig = Some(createDiskConfig), customEnvironmentVariables = customEnvVars)

    val appName = AppName("app1")
    kubeServiceInterp
      .createApp(userInfo.copy(userEmail = WorkbenchEmail("user100@example.com")),
                 savedCluster1.googleProject,
                 appName,
                 appReq)
      .unsafeRunSync()
    val cluster = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    cluster.nodepools.size shouldBe 2
    cluster.nodepools.map(_.status).sortBy(_.toString) shouldBe List(NodepoolStatus.Running, NodepoolStatus.Unspecified)
      .sortBy(_.toString)
    cluster.nodepools.filter(_.status == NodepoolStatus.Running).size shouldBe 1
    val claimedNodepool = cluster.nodepools.filter(_.id == savedNodepool1.id).head

    val appResult = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(savedCluster1.googleProject, appName)
    }.get

    appResult.nodepool.copy(apps = List.empty) shouldBe claimedNodepool

    val message = publisherQueue.dequeue1.unsafeRunSync()
    message.messageType shouldBe LeoPubsubMessageType.CreateApp
    val createAppMessage = message.asInstanceOf[CreateAppMessage]
    createAppMessage.appId shouldBe appResult.app.id
    createAppMessage.clusterNodepoolAction shouldBe None

    val appName2 = AppName("app2")
    val appReq2 = appReq.copy(diskConfig = Some(createDiskConfig.copy(name = DiskName("newdisk"))))

    kubeServiceInterp
      .createApp(userInfo.copy(userEmail = WorkbenchEmail("user101@example.com")),
                 savedCluster1.googleProject,
                 appName2,
                 appReq2)
      .unsafeRunSync()

    val clusterAfterApp2 = dbFutureValue {
      kubernetesClusterQuery.getMinimalActiveClusterByName(savedCluster1.googleProject)
    }.get

    clusterAfterApp2.nodepools.size shouldBe 3
    clusterAfterApp2.nodepools.map(_.status).sortBy(_.toString) shouldBe List(
      NodepoolStatus.Running,
      NodepoolStatus.Precreating,
      NodepoolStatus.Unspecified
    ).sortBy(_.toString)

    val appResult2 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(savedCluster1.googleProject, appName2)
    }.get

    appResult2.nodepool.status shouldBe NodepoolStatus.Precreating

    val message2 = publisherQueue.dequeue1.unsafeRunSync()
    message2.messageType shouldBe LeoPubsubMessageType.CreateApp
    val createAppMessage2 = message2.asInstanceOf[CreateAppMessage]
    createAppMessage2.appId shouldBe appResult2.app.id
    createAppMessage2.clusterNodepoolAction shouldBe Some(CreateNodepool(appResult2.nodepool.id))
  }

  it should "stop an app" in isolatedDbTest {
    val res = for {
      publisherQueue <- InspectableQueue.bounded[IO, LeoPubsubMessage](10)
      kubeServiceInterp = makeInterp(publisherQueue)

      savedCluster <- IO(makeKubeCluster(1).copy(status = KubernetesClusterStatus.Running).save())
      savedNodepool <- IO(makeNodepool(1, savedCluster.id).copy(status = NodepoolStatus.Running).save())
      savedApp <- IO(makeApp(1, savedNodepool.id).copy(status = AppStatus.Running).save())

      _ <- kubeServiceInterp.stopApp(userInfo, savedCluster.googleProject, savedApp.appName)
      _ <- withLeoPublisher(publisherQueue) {
        for {
          dbAppOpt <- KubernetesServiceDbQueries
            .getActiveFullAppByName(savedCluster.googleProject, savedApp.appName)
            .transaction
          msg <- publisherQueue.tryDequeue1
        } yield {
          dbAppOpt.isDefined shouldBe true
          dbAppOpt.get.app.status shouldBe AppStatus.Stopping
          dbAppOpt.get.nodepool.status shouldBe NodepoolStatus.Running
          dbAppOpt.get.nodepool.numNodes shouldBe NumNodes(2)
          dbAppOpt.get.nodepool.autoscalingEnabled shouldBe true
          dbAppOpt.get.cluster.status shouldBe KubernetesClusterStatus.Running

          msg shouldBe None
        }
      }
    } yield ()

    res.unsafeRunSync()
  }

  it should "start an app" in isolatedDbTest {
    val res = for {
      publisherQueue <- InspectableQueue.bounded[IO, LeoPubsubMessage](10)
      kubeServiceInterp = makeInterp(publisherQueue)

      savedCluster <- IO(makeKubeCluster(1).copy(status = KubernetesClusterStatus.Running).save())
      savedNodepool <- IO(makeNodepool(1, savedCluster.id).copy(status = NodepoolStatus.Running).save())
      savedApp <- IO(makeApp(1, savedNodepool.id).copy(status = AppStatus.Stopped).save())

      _ <- kubeServiceInterp.startApp(userInfo, savedCluster.googleProject, savedApp.appName)
      _ <- withLeoPublisher(publisherQueue) {
        for {
          dbAppOpt <- KubernetesServiceDbQueries
            .getActiveFullAppByName(savedCluster.googleProject, savedApp.appName)
            .transaction
          msg <- publisherQueue.tryDequeue1
        } yield {
          dbAppOpt.isDefined shouldBe true
          dbAppOpt.get.app.status shouldBe AppStatus.Starting
          dbAppOpt.get.nodepool.status shouldBe NodepoolStatus.Running
          dbAppOpt.get.nodepool.numNodes shouldBe NumNodes(2)
          dbAppOpt.get.nodepool.autoscalingEnabled shouldBe true
          dbAppOpt.get.cluster.status shouldBe KubernetesClusterStatus.Running

          msg shouldBe None
        }
      }
    } yield ()

    res.unsafeRunSync()
  }

  private def withLeoPublisher(
    publisherQueue: InspectableQueue[IO, LeoPubsubMessage]
  )(validations: IO[Assertion]): IO[Assertion] = {
    val leoPublisher = new LeoPublisher[IO](publisherQueue, new FakeGooglePublisher)
    withInfiniteStream(leoPublisher.process, validations)
  }
}
