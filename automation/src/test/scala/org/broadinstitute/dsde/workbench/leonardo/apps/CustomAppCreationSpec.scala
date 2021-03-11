package org.broadinstitute.dsde.workbench.leonardo
package apps

import org.broadinstitute.dsde.workbench.DoneCheckable
import org.broadinstitute.dsde.workbench.google2.{streamFUntilDone, streamUntilDoneOrTimeout}
import org.broadinstitute.dsde.workbench.leonardo.LeonardoApiClient.defaultCreateAppRequest
import org.broadinstitute.dsde.workbench.leonardo.http.{ListAppResponse, PersistentDiskRequest}
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Uri}
import org.scalatest.{DoNotDiscover, ParallelTestExecution}

import scala.concurrent.duration._

@DoNotDiscover
class CustomAppCreationSpec extends GPAllocFixtureSpec with LeonardoTestUtils with ParallelTestExecution {
  implicit val auth: Authorization =
    Authorization(Credentials.Token(AuthScheme.Bearer, ronCreds.makeAuthToken().value))

  "create and delete a custom app" in { googleProject =>
    val appName = randomAppName

    val createAppRequest = defaultCreateAppRequest.copy(
      diskConfig = Some(
        PersistentDiskRequest(
          randomDiskName,
          Some(DiskSize(500)),
          None,
          Map.empty
        )
      ),
      descriptorPath = Some(
        Uri.uri("https://raw.githubusercontent.com/DataBiosphere/terra-app/main/apps/ucsc_genome_browser/app.yaml")
      ),
      appType = AppType.Custom
    )

    LeonardoApiClient.client
      .use { implicit client =>
        for {
          _ <- loggerIO.info(s"CustomAppCreationSpec: About to create app ${googleProject.value}/${appName.value}")

          // Create the app
          _ <- LeonardoApiClient.createAppWithRetry(googleProject, appName, createAppRequest)

          // Verify the initial getApp call
          getApp = LeonardoApiClient.getApp(googleProject, appName)
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Provisioning) or be(AppStatus.Precreating))

          // Verify the app eventually becomes Running
          _ <- testTimer.sleep(60 seconds)
          monitorCreateResult <- streamUntilDoneOrTimeout(
            getApp,
            120,
            10 seconds,
            s"CustomAppCreationSpec: app ${googleProject.value}/${appName.value} did not finish creating after 20 minutes"
          )(implicitly, implicitly, appInStateOrError(AppStatus.Running))
          _ <- loggerIO.info(
            s"CustomAppCreationSpec: app ${googleProject.value}/${appName.value} monitor result: ${monitorCreateResult}"
          )
          _ = monitorCreateResult.status shouldBe AppStatus.Running

          _ <- testTimer.sleep(1 minute)

          // Delete the app
          _ <- LeonardoApiClient.deleteApp(googleProject, appName)

          // Verify getApp again
          getAppResponse <- getApp
          _ = getAppResponse.status should (be(AppStatus.Deleting) or be(AppStatus.Predeleting))

          // Verify the app eventually becomes Deleted
          listApps = LeonardoApiClient.listApps(googleProject, true)
          implicit0(deletedDoneCheckable: DoneCheckable[List[ListAppResponse]]) = appDeleted(appName)
          monitorDeleteResult <- streamFUntilDone(
            listApps,
            120,
            10 seconds
          ).compile.lastOrError

          _ <- loggerIO.info(
            s"CustomAppCreationSpec: app ${googleProject.value}/${appName.value} delete result: $monitorDeleteResult"
          )

          _ = monitorDeleteResult.map(_.status) shouldBe List(AppStatus.Deleted)
        } yield ()
      }
      .unsafeRunSync()
  }
}
