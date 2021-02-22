package org.broadinstitute.dsde.workbench.leonardo
package apps

import java.nio.file.Paths

import cats.effect.IO
import cats.syntax.all._
import com.google.container.v1.{Cluster, NodePool}
import org.broadinstitute.dsde.workbench.DoneCheckable
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterId, KubernetesClusterName}
import org.broadinstitute.dsde.workbench.google2.{streamFUntilDone, streamUntilDoneOrTimeout, GKEService}
import org.broadinstitute.dsde.workbench.leonardo.LeonardoApiClient._
import org.broadinstitute.dsde.workbench.leonardo.http.{ListAppResponse, PersistentDiskRequest}
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}
import org.scalatest.{DoNotDiscover, ParallelTestExecution}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

@DoNotDiscover
class BatchNodepoolCreationSpec
    extends GPAllocFixtureSpec
    with LeonardoTestUtils
    with GPAllocUtils
    with ParallelTestExecution {

  implicit val auth: Authorization = Authorization(Credentials.Token(AuthScheme.Bearer, ronCreds.makeAuthToken().value))
  val gkeServiceResource = GKEService.resource(Paths.get(LeonardoConfig.GCS.pathToQAJson), blocker, semaphore)
  implicit def clusterRunning: DoneCheckable[Option[Cluster]] =
    x =>
      x.map(_.getStatus()) == Some(Cluster.Status.RUNNING) &&
        x.map(_.getNodePoolsList().asScala.toList.map(_.getStatus()).distinct) == Some(List(NodePool.Status.RUNNING))

  "create apps in batch created nodepools" in { _ =>
    // Note: requesting a new project from gpalloc to ensure there is no pre-existing cluster
    withNewProject { googleProject =>
      LeonardoApiClient.client.use { implicit c =>
        val appName1 = randomAppName
        val appName2 = randomAppName

        for {
          // Invoke batchNodepoolCreate
          clusterName <- IO.fromEither(KubernetesNameUtils.getUniqueName(KubernetesClusterName.apply))
          request = defaultBatchNodepoolRequest.copy(clusterName = Some(clusterName))
          _ <- LeonardoApiClient.batchNodepoolCreate(googleProject, request)

          // Verify the cluster gets created in GKE with 3 nodepools
          clusterId = KubernetesClusterId(googleProject, LeonardoConfig.Leonardo.location, clusterName)
          getCluster = gkeServiceResource.use(_.getCluster(clusterId))
          _ <- testTimer.sleep(30 seconds)
          monitorBatchCreationResult <- streamUntilDoneOrTimeout(
            getCluster,
            60,
            10 seconds,
            s"Cluster ${clusterId} did not finish creating in Google after 10 minutes"
          )
          _ = monitorBatchCreationResult.map(_.getNodePoolsList().size()) shouldBe Some(3)

          // Here we sleep, because the above verifies Google state and we need to wait until Leo has polled
          // and updated its internal state to proceed.
          _ <- testTimer.sleep(5 minutes)

          // Create 2 apps in the pre-created nodepools
          createAppRequest1 = defaultCreateAppRequest.copy(diskConfig =
            Some(PersistentDiskRequest(randomDiskName(), None, None, Map.empty))
          )
          createAppRequest2 = defaultCreateAppRequest.copy(diskConfig =
            Some(PersistentDiskRequest(randomDiskName(), None, None, Map.empty))
          )
          _ <- loggerIO.info(s"BatchNodepoolCreationSpec: About to create app ${googleProject.value}/${appName1.value}")
          _ <- LeonardoApiClient.createApp(googleProject, appName1, createAppRequest1)

          _ <- loggerIO.info(s"BatchNodepoolCreationSpec: About to create app ${googleProject.value}/${appName2.value}")
          _ <- LeonardoApiClient.createApp(googleProject, appName2, createAppRequest2)

          // Verify the initial getApp calls
          getApp1 = LeonardoApiClient.getApp(googleProject, appName1)
          getApp2 = LeonardoApiClient.getApp(googleProject, appName2)
          getAppResponse1 <- getApp1
          _ = getAppResponse1.status should (be(AppStatus.Provisioning) or be(AppStatus.Precreating))
          getAppResponse2 <- getApp2
          _ = getAppResponse2.status should (be(AppStatus.Provisioning) or be(AppStatus.Precreating))

          // Wait until they both become Running
          _ <- testTimer.sleep(60 seconds)
          pollCreate1 = streamUntilDoneOrTimeout(
            getApp1,
            120,
            10 seconds,
            s"BatchNodepoolCreationSpec: app1 ${googleProject.value}/${appName1.value} did not finish creating after 20 minutes"
          )(implicitly, implicitly, appInStateOrError(AppStatus.Running))
          pollCreate2 = streamUntilDoneOrTimeout(
            getApp2,
            120,
            10 seconds,
            s"BatchNodepoolCreationSpec: app2 ${googleProject.value}/${appName2.value} did not finish creating after 20 minutes"
          )(implicitly, implicitly, appInStateOrError(AppStatus.Running))
          res <- List(pollCreate1, pollCreate2).parSequence
          _ = res.foreach(_.status shouldBe AppStatus.Running)

          _ <- testTimer.sleep(1 minute)

          // Delete both apps
          _ <- LeonardoApiClient.deleteApp(googleProject, appName1)
          _ <- LeonardoApiClient.deleteApp(googleProject, appName2)

          // Verify getApp again
          getApp1 = LeonardoApiClient.getApp(googleProject, appName1)
          getApp2 = LeonardoApiClient.getApp(googleProject, appName2)
          getAppResponse1 <- getApp1
          _ = getAppResponse1.status should (be(AppStatus.Deleting) or be(AppStatus.Predeleting))
          getAppResponse2 <- getApp2
          _ = getAppResponse2.status should (be(AppStatus.Deleting) or be(AppStatus.Predeleting))

          // Wait until both are deleted
          // Don't fail the test if the deletion times out because the Galaxy pre-delete job can sporadically fail.
          // See https://broadworkbench.atlassian.net/browse/IA-2471
          listApps = LeonardoApiClient.listApps(googleProject, true)
          implicit0(deletedDoneCheckable: DoneCheckable[List[ListAppResponse]]) = appsDeleted(Set(appName1, appName2))
          monitorDeleteResult <- streamFUntilDone(
            listApps,
            120,
            10 seconds
          ).compile.lastOrError
          _ <- if (!deletedDoneCheckable.isDone(monitorDeleteResult)) {
            loggerIO.warn(
              s"AppCreationSpec: apps ${googleProject.value}/${appName1.value} and ${googleProject.value}/${appName2.value} did not finish deleting after 20 minutes. Result: $monitorDeleteResult"
            )
          } else {
            loggerIO.info(
              s"AppCreationSpec: apps ${googleProject.value}/${appName1.value} and ${googleProject.value}/${appName2.value} delete result: $monitorDeleteResult"
            )
          }
        } yield ()
      }
    }
  }

}
