package org.broadinstitute.dsde.workbench.leonardo
package http
package db

import java.time.Instant

import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.KubernetesTestData._
import org.broadinstitute.dsde.workbench.leonardo.TestUtils._
import org.broadinstitute.dsde.workbench.leonardo.db._
import org.scalatest.flatspec.AnyFlatSpecLike

import scala.concurrent.ExecutionContext.Implicits.global

class AppServiceDbQueriesSpec extends AnyFlatSpecLike with TestComponent {

  it should "return None when there is no matching app" in isolatedDbTest {
    val cluster1 = makeKubeCluster(1).save()
    val nodepool1 = makeNodepool(1, cluster1.id).save()
    val app1 = makeApp(1, nodepool1.id).save()

    val getApp = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(cluster1.googleProject, AppName("fakeApp"))
    }
    getApp shouldBe None
  }

  it should "get full app" in isolatedDbTest {
    val cluster1 = makeKubeCluster(1).save()
    val cluster2 = makeKubeCluster(2).save()
    val nodepool1 = makeNodepool(1, cluster1.id).save()
    val nodepool2 = makeNodepool(2, cluster1.id).save()
    val nodepool3 = makeNodepool(3, cluster2.id).save()
    val app1 = makeApp(1, nodepool1.id).save()
    val app2 = makeApp(2, nodepool1.id).save()
    val app3 = makeApp(3, nodepool2.id).save()
    val app4 = makeApp(4, nodepool3.id).save()

    val getApp1 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(cluster1.googleProject, app1.appName)
    }
    val getApp2 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(cluster1.googleProject, app2.appName)
    }
    val getApp3 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(cluster1.googleProject, app3.appName)
    }
    val getApp4 = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(cluster2.googleProject, app4.appName)
    }

    getApp1.get.cluster.googleProject shouldEqual cluster1.googleProject
    getApp1.get.cluster.clusterName shouldEqual cluster1.clusterName
    getApp1.get.nodepool.copy(apps = List()) shouldEqual nodepool1
    getApp1.get.app shouldEqual app1

    getApp2.get.cluster.googleProject shouldEqual cluster1.googleProject
    getApp2.get.cluster.clusterName shouldEqual cluster1.clusterName
    getApp2.get.nodepool.copy(apps = List()) shouldEqual nodepool1
    getApp2.get.app shouldEqual app2

    getApp3.get.cluster.googleProject shouldEqual cluster1.googleProject
    getApp3.get.cluster.clusterName shouldEqual cluster1.clusterName
    getApp3.get.nodepool.copy(apps = List()) shouldEqual nodepool2
    getApp3.get.app shouldEqual app3

    getApp4.get.cluster.googleProject shouldEqual cluster2.googleProject
    getApp4.get.cluster.clusterName shouldEqual cluster2.clusterName
    getApp4.get.nodepool.copy(apps = List()) shouldEqual nodepool3
    getApp4.get.app shouldEqual app4
  }

  it should "get full app with disk and services" in isolatedDbTest {
    val savedCluster1 = makeKubeCluster(1).save()
    val savedNodepool1 = makeNodepool(1, savedCluster1.id).save()

    val disk = makePersistentDisk(None).save().unsafeRunSync()
    val basicApp = makeApp(1, savedNodepool1.id)
    val complexApp = basicApp.copy(appResources =
      basicApp.appResources.copy(
        disk = Some(disk),
        services = List(makeService(1), makeService(2))
      )
    )

    val savedApp = complexApp.save()

    val getApp = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(savedCluster1.googleProject, savedApp.appName)
    }
    getApp.get.app shouldEqual savedApp
    getApp.get.app.appResources.services.size shouldBe 2
  }

  it should "list apps" in isolatedDbTest {
    val cluster1 = makeKubeCluster(1).save()
    val nodepool1 = makeNodepool(1, cluster1.id).save()
    val nodepool2 = makeNodepool(2, cluster1.id).save()

    val cluster2 = makeKubeCluster(2).save()
    val nodepool3 = makeNodepool(3, cluster2.id).save()

    val app1 = makeApp(1, nodepool1.id).save()
    val app2 = makeApp(2, nodepool1.id).save()
    val app3 = makeApp(3, nodepool2.id).save()
    val app4 = makeApp(4, nodepool3.id).save()

    val listWithNoProject = dbFutureValue(KubernetesServiceDbQueries.listFullApps(None))
    listWithNoProject.length shouldEqual 2
    listWithNoProject.flatMap(_.nodepools).length shouldEqual 3
    listWithNoProject.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 4

    val listWithProject1 = dbFutureValue(KubernetesServiceDbQueries.listFullApps(Some(cluster1.googleProject)))
    listWithProject1.length shouldEqual 1
    listWithProject1.flatMap(_.nodepools).length shouldEqual 2
    listWithProject1.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 3

    val listWithProject2 = dbFutureValue(KubernetesServiceDbQueries.listFullApps(Some(cluster2.googleProject)))
    listWithProject2.length shouldEqual 1
    listWithProject2.flatMap(_.nodepools).length shouldEqual 1
    listWithProject2.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 1
  }

  it should "list apps with labels" in isolatedDbTest {
    val cluster1 = makeKubeCluster(1).save()
    val nodepool1 = makeNodepool(1, cluster1.id).save()

    val pair1 = "foo" -> "bar"
    val pair2 = "fizz" -> "buzz"
    val labels = Map(pair1, pair2)
    val app1 = makeApp(1, nodepool1.id).copy(labels = labels).save()
    val app2 = makeApp(2, nodepool1.id).save()

    val listWithLabels1 = dbFutureValue(KubernetesServiceDbQueries.listFullApps(None, Map(pair1)))
    listWithLabels1.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 1
    listWithLabels1.flatMap(_.nodepools).flatMap(_.apps).head shouldEqual app1
  }

  it should "filter clusters and nodepools that have no apps out of list" in isolatedDbTest {
    val cluster1 = makeKubeCluster(1).save()
    val nodepool1 = makeNodepool(1, cluster1.id).save()

    val listApps1 = dbFutureValue(KubernetesServiceDbQueries.listFullApps(Some(cluster1.googleProject)))
    listApps1.length shouldEqual 0

    val cluster2 = makeKubeCluster(2).save()
    val nodepool2 = makeNodepool(2, cluster2.id).save()
    val nodepool3 = makeNodepool(3, cluster2.id).save()
    val app1 = makeApp(1, nodepool2.id).save()

    val listApps2 = dbFutureValue(KubernetesServiceDbQueries.listFullApps(Some(cluster2.googleProject)))
    listApps2.length shouldEqual 1
    listApps2.flatMap(_.nodepools).length shouldEqual 1
  }

  it should "list deleted apps when includeDeleted is true" in isolatedDbTest {
    val cluster1 = makeKubeCluster(1).save()
    val savedNodepool = makeNodepool(1, cluster1.id).save()
    val app1 = makeApp(1, savedNodepool.id).save()

    val destroyedDate = Instant.now()
    //delete app
    dbFutureValue(appQuery.markAsDeleted(app1.id, destroyedDate)) shouldBe 1

    val getApp = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(cluster1.googleProject, app1.appName)
    }
    getApp shouldBe None

    val listAppsWithDeleted = dbFutureValue {
      KubernetesServiceDbQueries.listFullApps(Some(cluster1.googleProject), includeDeleted = true)
    }
    listAppsWithDeleted.length shouldEqual 1
    listAppsWithDeleted.head.nodepools.length shouldEqual 1
    val nodepool = listAppsWithDeleted.head.nodepools.head
    nodepool.apps.length shouldEqual 1
    nodepool.apps.head shouldEqual app1.copy(status = AppStatus.Deleted,
                                             auditInfo = app1.auditInfo.copy(destroyedDate = Some(destroyedDate)))

    //delete nodepool for deleted app
    dbFutureValue(nodepoolQuery.markAsDeleted(savedNodepool.id, destroyedDate)) shouldBe 1
    val listAppsWithDeleted2 = dbFutureValue {
      KubernetesServiceDbQueries.listFullApps(Some(cluster1.googleProject), includeDeleted = true)
    }
    listAppsWithDeleted2.length shouldEqual 1
    listAppsWithDeleted2.head.nodepools.length shouldEqual 1
    val deletedNodepool = listAppsWithDeleted2.head.nodepools.head
    deletedNodepool.copy(apps = List()) shouldEqual savedNodepool.copy(
      status = NodepoolStatus.Deleted,
      auditInfo = savedNodepool.auditInfo.copy(destroyedDate = Some(destroyedDate)),
      apps = List()
    )
    deletedNodepool.apps.length shouldEqual 1
    nodepool.apps.head shouldEqual app1.copy(status = AppStatus.Deleted,
                                             auditInfo = app1.auditInfo.copy(destroyedDate = Some(destroyedDate)))
  }

  it should "not list deleted apps when includeDelete is false" in isolatedDbTest {
    val cluster1 = makeKubeCluster(1).save()
    val nodepool1 = makeNodepool(1, cluster1.id).save()
    val app1 = makeApp(1, nodepool1.id).save()
    val app2 = makeApp(2, nodepool1.id).save()

    val destroyedDate = Instant.now()
    dbFutureValue(appQuery.markAsDeleted(app1.id, destroyedDate)) shouldBe 1

    val listAppsWithoutDeleted = dbFutureValue {
      KubernetesServiceDbQueries.listFullApps(Some(cluster1.googleProject), includeDeleted = false)
    }
    listAppsWithoutDeleted.length shouldEqual 1
    listAppsWithoutDeleted.head.nodepools.length shouldEqual 1
    val nodepool = listAppsWithoutDeleted.head.nodepools.head
    nodepool.apps.length shouldEqual 1
    nodepool.apps.head shouldEqual app2
  }

  it should "save cluster when one doesn't exist for project" in isolatedDbTest {
    val makeCluster1 = makeKubeCluster(1)
    val saveCluster1 = Some(makeCluster1)
      .map(c =>
        SaveKubernetesCluster(c.googleProject,
                              c.clusterName,
                              c.location,
                              c.region,
                              c.status,
                              c.ingressChart,
                              c.auditInfo,
                              DefaultNodepool.fromNodepool(c.nodepools.headOption.get))
      )
      .get

    val saveResult = dbFutureValue(KubernetesServiceDbQueries.saveOrGetClusterForApp(saveCluster1))

    val x = saveResult match {
      case _: ClusterExists       => false
      case _: ClusterDoesNotExist => true
    }
    x shouldEqual true
    saveResult.minimalCluster shouldEqual makeCluster1
  }

  it should "error if cluster exists and is creating on save" in isolatedDbTest {
    val makeCluster1 = makeKubeCluster(1).copy(status = KubernetesClusterStatus.Provisioning)
    val makeCluster2 = makeKubeCluster(2).copy(status = KubernetesClusterStatus.Precreating)

    makeCluster1.save()
    makeCluster2.save()

    val saveCluster1 = Some(makeCluster1)
      .map(c =>
        SaveKubernetesCluster(c.googleProject,
                              c.clusterName,
                              c.location,
                              c.region,
                              c.status,
                              c.ingressChart,
                              c.auditInfo,
                              DefaultNodepool.fromNodepool(c.nodepools.headOption.get))
      )
      .get
    val saveCluster2 = Some(makeCluster2)
      .map(c =>
        SaveKubernetesCluster(c.googleProject,
                              c.clusterName,
                              c.location,
                              c.region,
                              c.status,
                              c.ingressChart,
                              c.auditInfo,
                              DefaultNodepool.fromNodepool(c.nodepools.headOption.get))
      )
      .get

    val saveResult1IO = KubernetesServiceDbQueries.saveOrGetClusterForApp(saveCluster1).transaction
    val saveResult2IO = KubernetesServiceDbQueries.saveOrGetClusterForApp(saveCluster2).transaction

    the[KubernetesAppCreationException] thrownBy {
      saveResult1IO.unsafeRunSync()
    }

    the[KubernetesAppCreationException] thrownBy {
      saveResult2IO.unsafeRunSync()
    }
  }

  it should "allow creation of a cluster if a deleted one exists in the same project" in isolatedDbTest {
    val savedCluster1 = makeKubeCluster(1).copy(status = KubernetesClusterStatus.Deleted).save
    val makeCluster2 = makeKubeCluster(2).copy(status = KubernetesClusterStatus.Precreating)

    val saveCluster2 = Some(makeCluster2)
      .map(c =>
        SaveKubernetesCluster(c.googleProject,
                              c.clusterName,
                              c.location,
                              c.region,
                              c.status,
                              c.ingressChart,
                              c.auditInfo,
                              DefaultNodepool.fromNodepool(c.nodepools.headOption.get))
      )
      .get

    val saveResult = KubernetesServiceDbQueries.saveOrGetClusterForApp(saveCluster2).transaction.unsafeRunSync()
    // We are verifying it saved a new cluster here.
    // We don't know the ID, but the method not returning the original DELETED cluster is sufficient
    saveResult.minimalCluster.id should not be savedCluster1.id
  }

  it should "get cluster if exists for project" in isolatedDbTest {
    val makeCluster1 = makeKubeCluster(1).copy()
    val makeCluster2 = makeCluster1.copy(clusterName = kubeName0)

    makeCluster1.save()
    val saveCluster2 = Some(makeCluster2)
      .map(c =>
        SaveKubernetesCluster(c.googleProject,
                              c.clusterName,
                              c.location,
                              c.region,
                              c.status,
                              c.ingressChart,
                              c.auditInfo,
                              DefaultNodepool.fromNodepool(c.nodepools.headOption.get))
      )
      .get
    val saveClusterResult = dbFutureValue(KubernetesServiceDbQueries.saveOrGetClusterForApp(saveCluster2))

    val x = saveClusterResult match {
      case _: ClusterExists       => true
      case _: ClusterDoesNotExist => false
    }
    x shouldEqual true
    saveClusterResult.minimalCluster shouldEqual makeCluster1
  }

  it should "error when attempting to save an app with the same name for a project" in isolatedDbTest {
    val savedCluster1 = makeKubeCluster(1).save()
    val savedNodepool1 = makeNodepool(1, savedCluster1.id).save()
    val savedNodepool2 = makeNodepool(2, savedCluster1.id).save()

    val appName = AppName("test")
    val savedApp1 = makeApp(1, savedNodepool1.id).copy(appName = appName).save()

    the[AppExistsForProjectException] thrownBy {
      val savedApp2 = makeApp(2, savedNodepool2.id).copy(appName = appName).save()
    }
  }

  it should "save and get errors" in isolatedDbTest {
    val savedCluster1 = makeKubeCluster(1).save()
    val savedNodepool1 = makeNodepool(1, savedCluster1.id).save()

    val savedApp1 = makeApp(1, savedNodepool1.id).save()
    val now = Instant.now()
    val error1 = AppError("error1", now, ErrorAction.CreateApp, ErrorSource.App, Some(1))
    val error2 = AppError("error2", now, ErrorAction.DeleteApp, ErrorSource.Nodepool, Some(2))
    appErrorQuery.save(savedApp1.id, error1).transaction.unsafeRunSync()
    appErrorQuery.save(savedApp1.id, error2).transaction.unsafeRunSync()

    val getApp = dbFutureValue {
      KubernetesServiceDbQueries.getActiveFullAppByName(savedCluster1.googleProject, savedApp1.appName)
    }.get

    getApp.app.errors should contain(error1)
    getApp.app.errors should contain(error2)
  }

  it should "correctly identify if theres an operation in progress" in isolatedDbTest {
    val savedCluster1 = makeKubeCluster(1).copy(status = KubernetesClusterStatus.Running).save()
    val savedNodepool1 = makeNodepool(1, savedCluster1.id).copy(status = NodepoolStatus.Provisioning).save()
    val savedNodepool2 = makeNodepool(2, savedCluster1.id).copy(status = NodepoolStatus.Running).save()
    val app2 = makeApp(1, savedNodepool2.id).copy(status = AppStatus.Running).save()

    KubernetesServiceDbQueries.hasClusterOperationInProgress(savedCluster1.id).transaction.unsafeRunSync() shouldBe true
  }

  it should "list Error'd apps if the underlying cluster is deleted" in {
    val cluster = LeoLenses.kubernetesClusterToDestroyedDate
      .modify(_ => Some(Instant.now))(makeKubeCluster(1).copy(status = KubernetesClusterStatus.Deleted))
      .save()
    val nodepool = LeoLenses.nodepoolToDestroyedDate
      .modify(_ => Some(Instant.now))(makeNodepool(1, cluster.id).copy(status = NodepoolStatus.Deleted))
      .save()

    val app = makeApp(1, nodepool.id).copy(status = AppStatus.Error).save()

    val listWithNoProject = dbFutureValue(KubernetesServiceDbQueries.listFullApps(None))
    listWithNoProject.length shouldEqual 1
    listWithNoProject.flatMap(_.nodepools).length shouldEqual 1
    listWithNoProject.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 1
    listWithNoProject.flatMap(_.nodepools).flatMap(_.apps).head shouldEqual app

    val listWithProject = dbFutureValue(KubernetesServiceDbQueries.listFullApps(Some(cluster.googleProject)))
    listWithProject.length shouldEqual 1
    listWithProject.flatMap(_.nodepools).length shouldEqual 1
    listWithProject.flatMap(_.nodepools).flatMap(_.apps).length shouldEqual 1
    listWithProject.flatMap(_.nodepools).flatMap(_.apps).head shouldEqual app

    val getActiveApp =
      dbFutureValue(KubernetesServiceDbQueries.getActiveFullAppByName(cluster.googleProject, app.appName))
    getActiveApp.isDefined shouldBe true
    getActiveApp.get.cluster.googleProject shouldEqual cluster.googleProject
    getActiveApp.get.cluster.clusterName shouldEqual cluster.clusterName
    getActiveApp.get.nodepool.copy(apps = List()) shouldEqual nodepool
    getActiveApp.get.app shouldEqual app

    val getFullApp = dbFutureValue(KubernetesServiceDbQueries.getFullAppByName(cluster.googleProject, app.id))
    getFullApp.isDefined shouldBe true
    getFullApp.get.cluster.googleProject shouldEqual cluster.googleProject
    getFullApp.get.cluster.clusterName shouldEqual cluster.clusterName
    getFullApp.get.nodepool.copy(apps = List()) shouldEqual nodepool
    getFullApp.get.app shouldEqual app
  }

}
