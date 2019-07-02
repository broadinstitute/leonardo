package org.broadinstitute.dsde.workbench.leonardo.notebooks


import java.net.URL
import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.HttpResponse
import org.broadinstitute.dsde.workbench.ResourceFile
import org.broadinstitute.dsde.workbench.leonardo.ClusterStatus.ClusterStatus
import org.broadinstitute.dsde.workbench.leonardo.StringValueClass.LabelMap
import org.broadinstitute.dsde.workbench.leonardo._
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsPath, GoogleProject}

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps


class NotebookExtensionSpec extends ClusterFixtureSpec with NotebookTestUtils {
  override def enableWelder: Boolean = true

  debug = true
  mockedCluster = mockCluster("gpalloc-dev-master-2b9yymo","automation-test-atcydjxkz")


  "Leonardo welder and notebooks" - {

//    "Welder should be up" in { clusterFixture =>
//      println("printing cluster Fixture")
//      println(clusterFixture)
//      val resp: HttpResponse = Welder.getWelderStatus(clusterFixture.cluster)
//      resp.status.isSuccess() shouldBe true
//    }

    "storageLinks and localize should work" in { clusterFixture =>
        val sampleNotebook = ResourceFile("bucket-tests/gcsFile.ipynb")
        withResourceFileInBucket(clusterFixture.billingProject, sampleNotebook, "text/plain") { googleCloudDir =>
          println("====================================================")

          withWelderInitialized(clusterFixture.cluster, googleCloudDir, true) { localizedFile =>
            println("sleeping for a minute")
//            Thread.sleep(120000) //don't open browser until cluster is initialized properly
            withWebDriver { implicit driver =>
            println("====================================================")
            println("in welder initialized callback")
            println(localizedFile)

//
            withOpenNotebook(clusterFixture.cluster, localizedFile, 2.minutes) { notebookPage =>
              logger.info("notebook is open")
              notebookPage.hideModal()
              notebookPage.modeExists() shouldBe true
              notebookPage.getModeText() shouldBe "Edit Mode"
//                notebookPage.close()
            }
          }
        }
        }
      }

  }
}
