package org.broadinstitute.dsde.workbench.leonardo
package apps

import org.broadinstitute.dsde.workbench.DoneCheckable
import org.broadinstitute.dsde.workbench.google2.{streamFUntilDone, streamUntilDoneOrTimeout, Generators}
import org.broadinstitute.dsde.workbench.leonardo.LeonardoApiClient._
import org.broadinstitute.dsde.workbench.leonardo.http.{ListAppResponse, PersistentDiskRequest}
import org.broadinstitute.dsde.workbench.service.util.Tags
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}
import org.scalatest.{DoNotDiscover, ParallelTestExecution}

import scala.concurrent.duration._

@DoNotDiscover
class AppCreationSpec extends GPAllocFixtureSpec with LeonardoTestUtils with GPAllocUtils with ParallelTestExecution {
  implicit val auth: Authorization =
    Authorization(Credentials.Token(AuthScheme.Bearer, ronCreds.makeAuthToken().value))

  "create, delete an app and re-create an app with same disk" taggedAs Tags.SmokeTest in { _ =>
    withNewProject { googleProject =>
      val appName = randomAppName
      val restoreAppName = AppName(s"restore-${appName.value}")
      val diskName = Generators.genDiskName.sample.get

      val createAppRequest = defaultCreateAppRequest.copy(
        diskConfig = Some(
          PersistentDiskRequest(
            diskName,
            Some(DiskSize(300)),
            None,
            Map.empty
          )
        ),
        customEnvironmentVariables = Map("WORKSPACE_NAME" -> "Galaxy-Workshop-ASHG_2020_GWAS_Demo")
      )

      LeonardoApiClient.client.use { implicit client =>
        for {
          _ <- loggerIO.info(s"AppCreationSpec: About to create app ${googleProject.value}/${appName.value}")

          // Create the app
          _ <- LeonardoApiClient.createApp(googleProject, appName, createAppRequest)

          // Verify the initial getApp call
          getApp = LeonardoApiClient.getApp(googleProject, appName)
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Provisioning) or be(AppStatus.Precreating))

          // Verify the app eventually becomes Running
          _ <- testTimer.sleep(120 seconds)
          monitorCreateResult <- streamUntilDoneOrTimeout(
            getApp,
            120,
            10 seconds,
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} did not finish creating after 20 minutes"
          )(implicitly, implicitly, appInStateOrError(AppStatus.Running))
          _ <- loggerIO.info(
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} monitor result: ${monitorCreateResult}"
          )
          _ = monitorCreateResult.status shouldBe AppStatus.Running

          _ <- testTimer.sleep(1 minute)

          // Delete the app
          _ <- LeonardoApiClient.deleteApp(googleProject, appName, false)

          // Verify getApp again
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Deleting) or be(AppStatus.Predeleting))

          // Verify the app eventually becomes Deleted
          // Don't fail the test if the deletion times out because the Galaxy pre-delete job can sporadically fail.
          // See https://broadworkbench.atlassian.net/browse/IA-2471
          listApps = LeonardoApiClient.listApps(googleProject, true)
          implicit0(deletedDoneCheckable: DoneCheckable[List[ListAppResponse]]) = appDeleted(appName)
          monitorDeleteResult <- streamFUntilDone(
            listApps,
            120,
            10 seconds
          ).compile.lastOrError
          _ <- if (!deletedDoneCheckable.isDone(monitorDeleteResult)) {
            loggerIO.warn(
              s"AppCreationSpec: app ${googleProject.value}/${appName.value} did not finish deleting after 20 minutes. Result: $monitorDeleteResult"
            )
          } else {
            loggerIO.info(
              s"AppCreationSpec: app ${googleProject.value}/${appName.value} delete result: $monitorDeleteResult"
            )
          }
          // Verify creating another app with the same disk doesn't error out
          _ <- LeonardoApiClient.createAppWithWait(googleProject, restoreAppName, createAppRequest)
        } yield ()
      }
    }
  }

  "stop and start an app" in { _ =>
    withNewProject { googleProject =>
      val appName = randomAppName
      val diskName = Generators.genDiskName.sample.get

      val createAppRequest = defaultCreateAppRequest.copy(
        diskConfig = Some(
          PersistentDiskRequest(
            diskName,
            Some(DiskSize(500)),
            None,
            Map.empty
          )
        )
      )

      LeonardoApiClient.client.use { implicit client =>
        for {
          _ <- loggerIO.info(s"AppCreationSpec: About to create app ${googleProject.value}/${appName.value}")

          // Create the app
          _ <- LeonardoApiClient.createApp(googleProject, appName, createAppRequest)

          // Verify the initial getApp call
          getApp = LeonardoApiClient.getApp(googleProject, appName)
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Provisioning) or be(AppStatus.Precreating))

          // Verify the app eventually becomes Running
          _ <- testTimer.sleep(90 seconds)
          monitorCreateResult <- streamUntilDoneOrTimeout(
            getApp,
            120,
            10 seconds,
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} did not finish creating after 20 minutes"
          )(implicitly, implicitly, appInStateOrError(AppStatus.Running))
          _ <- loggerIO.info(
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} monitor result: ${monitorCreateResult}"
          )
          _ = monitorCreateResult.status shouldBe AppStatus.Running

          // Stop the app
          _ <- LeonardoApiClient.stopApp(googleProject, appName)

          // Verify getApp again
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Stopping) or be(AppStatus.PreStopping))

          // Verify the app eventually becomes Stopped
          _ <- testTimer.sleep(60 seconds)
          monitorStopResult <- streamUntilDoneOrTimeout(
            getApp,
            180,
            10 seconds,
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} did not finish stopping after 30 minutes"
          )(implicitly, implicitly, appInStateOrError(AppStatus.Stopped))
          _ <- loggerIO.info(
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} stop result: $monitorStopResult"
          )
          _ = monitorStopResult.status shouldBe AppStatus.Stopped

          // Start the app
          _ <- LeonardoApiClient.startApp(googleProject, appName)

          // Verify getApp again
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Starting) or be(AppStatus.PreStarting))

          // Verify the app eventually becomes Running
          _ <- testTimer.sleep(30 seconds)
          monitorStartResult <- streamUntilDoneOrTimeout(
            getApp,
            120,
            10 seconds,
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} did not finish starting after 20 minutes"
          )(implicitly, implicitly, appInStateOrError(AppStatus.Running))
          _ <- loggerIO.info(
            s"AppCreationSpec: app ${googleProject.value}/${appName.value} start result: $monitorStartResult"
          )
          _ = monitorStartResult.status shouldBe AppStatus.Running

          _ <- testTimer.sleep(1 minute)

          // Delete the app
          _ <- LeonardoApiClient.deleteApp(googleProject, appName, true)
          _ <- testTimer.sleep(30 seconds)

          // Verify getApp again
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Deleting) or be(AppStatus.Predeleting))

          // Verify the app eventually becomes Deleted
          // Don't fail the test if the deletion times out because the Galaxy pre-delete job can sporadically fail.
          // See https://broadworkbench.atlassian.net/browse/IA-2471
          listApps = LeonardoApiClient.listApps(googleProject, true)
          implicit0(doneCheckable: DoneCheckable[List[ListAppResponse]]) = appDeleted(appName)
          monitorDeleteResult <- streamFUntilDone(listApps, 120, 10 seconds).compile.lastOrError
          _ <- if (!doneCheckable.isDone(monitorDeleteResult)) {
            loggerIO.warn(
              s"AppCreationSpec: app ${googleProject.value}/${appName.value} did not finish deleting after 20 minutes. Result: $monitorDeleteResult"
            )
          } else {
            loggerIO.info(
              s"AppCreationSpec: app ${googleProject.value}/${appName.value} delete result: $monitorDeleteResult"
            )
          }
          // verify disk is also deleted
          getDiskResp <- LeonardoApiClient.getDisk(googleProject, diskName).attempt
        } yield {
          getDiskResp.toOption shouldBe (None)
        }
      }
    }
  }

}
