package org.broadinstitute.dsde.workbench.leonardo.notebooks

import java.io.File
import java.time.Instant

import cats.data.NonEmptyList
import cats.effect.IO
import com.google.cloud.Identity
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.google2.StorageRole.ObjectAdmin
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GetMetadataResponse, RemoveObjectResult, StorageRole}
import org.broadinstitute.dsde.workbench.leonardo._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.service.Sam
import org.openqa.selenium.WebDriver
import org.scalatest.Suite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

trait NotebookTestUtils extends LeonardoTestUtils {
  this: Suite =>

  private def whenKernelNotReady(t: Throwable): Boolean = t match {
    case _: KernelNotReadyException => true
    case _                          => false
  }

  def withNotebooksListPage[T](cluster: ClusterCopy)(testCode: NotebooksListPage => T)(implicit webDriver: WebDriver,
                                                                                       token: AuthToken): T = {
    val bindingFuture = ProxyRedirectClient.startServer
    val testResult = Try {
      val proxyRedirectPage = ProxyRedirectClient.get(cluster.googleProject, cluster.clusterName, "jupyter")
      proxyRedirectPage.open
      val notebooksListPage = Notebook.get(cluster.googleProject, cluster.clusterName)
      testCode(notebooksListPage)
    }
    ProxyRedirectClient.stopServer(bindingFuture)
    testResult.get
  }

  def withFileUpload[T](cluster: ClusterCopy, file: File)(
    testCode: NotebooksListPage => T
  )(implicit webDriver: WebDriver, token: AuthToken): T =
    withNotebooksListPage(cluster) { notebooksListPage =>
      notebooksListPage.upload(file)
      testCode(notebooksListPage)
    }

  def withNotebookUpload[T](cluster: ClusterCopy, file: File, timeout: FiniteDuration = 2.minutes)(
    testCode: NotebookPage => T
  )(implicit webDriver: WebDriver, token: AuthToken): T =
    withFileUpload(cluster, file) { notebooksListPage =>
      logger.info(
        s"Opening notebook ${file.getAbsolutePath} notebook on cluster ${cluster.googleProject.value} / ${cluster.clusterName.asString}..."
      )
      notebooksListPage.withOpenNotebook(file, timeout)(notebookPage => testCode(notebookPage))
    }

  def withNewNotebook[T](cluster: ClusterCopy, kernel: NotebookKernel = Python3, timeout: FiniteDuration = 2.minutes)(
    testCode: NotebookPage => T
  )(implicit webDriver: WebDriver, token: AuthToken): T =
    withNotebooksListPage(cluster) { notebooksListPage =>
      logger.info(
        s"Creating new ${kernel.string} notebook on cluster ${cluster.googleProject.value} / ${cluster.clusterName.asString}..."
      )
      val result: Future[T] = retryUntilSuccessOrTimeout(
        whenKernelNotReady,
        failureLogMessage =
          s"Cannot make new notebook on ${cluster.googleProject.value} / ${cluster.clusterName.asString} for ${kernel}"
      )(30 seconds, 2 minutes) { () =>
        Future(
          notebooksListPage.open.withNewNotebook(kernel, timeout) { notebookPage =>
            val res = testCode(notebookPage)
            notebookPage.saveAndCheckpoint()
            res
          }
        )
      }
      Await.result(result, 10 minutes)
    }

  // Creates a notebook with the directory structure:
  //   ~jupyter-user/notebooks/Untitled Folder/Untitled Folder/Untitled.ipynb
  // This roughly simulates an real-life directory structure like:
  //   ~jupyter-user/notebooks/Workspace Name/edit/notebook.ipynb
  // Ideally we would name the directories similarly but the Jupyter UI doesn't make that easy.
  def withNewNotebookInSubfolder[T](
    cluster: ClusterCopy,
    kernel: NotebookKernel = Python3,
    timeout: FiniteDuration = 2.minutes
  )(testCode: NotebookPage => T)(implicit webDriver: WebDriver, token: AuthToken): T =
    withNotebooksListPage(cluster) { notebooksListPage =>
      notebooksListPage.withSubFolder(timeout) { notebooksListPage =>
        notebooksListPage.withSubFolder(timeout) { notebooksListPage =>
          logger.info(
            s"Creating new ${kernel.string} notebook on cluster ${cluster.googleProject.value} / ${cluster.clusterName.asString}..."
          )
          val result: Future[T] =
            retryUntilSuccessOrTimeout(whenKernelNotReady, failureLogMessage = s"Cannot make new notebook")(30 seconds,
                                                                                                            2 minutes) {
              () =>
                Future(
                  notebooksListPage.withNewNotebook(kernel, timeout)(notebookPage => testCode(notebookPage))
                )
            }
          Await.result(result, 10 minutes)
        }
      }
    }

  def withOpenNotebook[T](cluster: ClusterCopy, notebookPath: File, timeout: FiniteDuration = 2.minutes)(
    testCode: NotebookPage => T
  )(implicit webDriver: WebDriver, token: AuthToken): T =
    withNotebooksListPage(cluster) { notebooksListPage =>
      logger.info(
        s"Opening notebook ${notebookPath.getAbsolutePath} notebook on cluster ${cluster.googleProject.value} / ${cluster.clusterName.asString}..."
      )
      notebooksListPage.withOpenNotebook(notebookPath, timeout)(notebookPage => testCode(notebookPage))
    }

//  def withDummyClientPage[T](cluster: ClusterCopy)(testCode: DummyClientPage => T)(implicit webDriver: WebDriver,
//                                                                                   token: AuthToken): T = {
//    // start a server to load the dummy client page
//    val bindingFuture = DummyClient.startServer
//    val testResult = Try {
//      val dummyClientPage = DummyClient.get(cluster.googleProject, cluster.clusterName)
//      testCode(dummyClientPage)
//    }
//    // stop the server
//    DummyClient.stopServer(bindingFuture)
//    testResult.get
//  }

  def uploadDownloadTest(cluster: ClusterCopy, uploadFile: File, timeout: FiniteDuration, fileDownloadDir: String)(
    assertion: (File, File) => Any
  )(implicit webDriver: WebDriver, token: AuthToken): Any = {
    cluster.status shouldBe ClusterStatus.Running
    uploadFile.exists() shouldBe true

    withNotebookUpload(cluster, uploadFile) { notebook =>
      notebook.runAllCells(timeout)
      notebook.downloadAsIpynb()
    }

    // sanity check the file downloaded correctly
    val downloadFile = new File(fileDownloadDir, uploadFile.getName)
    downloadFile.exists() shouldBe true
    downloadFile.isFile() shouldBe true
    downloadFile.deleteOnExit()
  }

  def pipInstall(notebookPage: NotebookPage, kernel: NotebookKernel, packageName: String): Unit = {
    val pip = kernel match {
      case Python2 | PySpark2 => "pip2"
      case Python3 | PySpark3 => "pip3"
      case _                  => throw new IllegalArgumentException(s"Can't pip install in a ${kernel.string} kernel")
    }

    val installOutput = notebookPage.executeCell(s"!$pip install $packageName")
    installOutput shouldBe 'defined
    installOutput.get should include(s"Collecting $packageName")
    installOutput.get should include("Installing collected packages:")
    installOutput.get should include("Successfully installed")
    installOutput.get should not include ("Exception:")
  }

  // https://github.com/aymericdamien/TensorFlow-Examples/blob/master/notebooks/1_Introduction/helloworld.ipynb
  def verifyTensorFlow(notebookPage: NotebookPage, kernel: NotebookKernel): Unit = {
    notebookPage.executeCell("import tensorflow as tf")
    notebookPage.executeCell("hello = tf.constant('Hello, TensorFlow!')") shouldBe None
    notebookPage.executeCell("sess = tf.Session()") shouldBe None
    val helloOutput = notebookPage.executeCell("print(sess.run(hello))")
    kernel match {
      case Python2 => helloOutput shouldBe Some("Hello, TensorFlow!")
      case Python3 => helloOutput shouldBe Some("b'Hello, TensorFlow!'")
      case other   => fail(s"Unexpected kernel: $other")
    }
  }

  //initializes storageLinks/ and localizes the file to the passed gcsPath
  def withWelderInitialized[T](cluster: ClusterCopy, gcsPath: GcsPath, shouldLocalizeFileInEditMode: Boolean)(
    testCode: File => T
  )(implicit token: AuthToken): T = {
    Welder.postStorageLink(cluster, gcsPath)
    Welder.localize(cluster, gcsPath, shouldLocalizeFileInEditMode)

    val localPath: String = Welder.getLocalPath(gcsPath, shouldLocalizeFileInEditMode)
    val localFile: File = new File(localPath)

    logger.info("Initialized welder via /storageLinks and /localize")
    testCode(localFile)
  }

  def getLockedBy(workspaceBucketName: GcsBucketName, notebookName: GcsBlobName): IO[Option[String]] =
    google2StorageResource.use { google2StorageDAO =>
      for {
        metadata <- google2StorageDAO.getObjectMetadata(workspaceBucketName, notebookName, None).compile.last
        lockExpiresAt = metadata match {
          case Some(GetMetadataResponse.Metadata(_, metadataMap, _)) if metadataMap.contains("lockExpiresAt") =>
            Some(metadataMap("lockExpiresAt"))
          case _ => None
        }
        currentlyLocked = lockExpiresAt match {
          case Some(instantStr) => Instant.ofEpochMilli(instantStr.toLong).compareTo(Instant.now()) == 1
          case None             => false
        }
        lastLockedBy = if (currentlyLocked) {
          metadata match {
            case Some(GetMetadataResponse.Metadata(_, metadataMap, _)) if metadataMap.contains("lastLockedBy") =>
              Some(metadataMap("lastLockedBy"))
            case _ => None
          }
        } else None
      } yield lastLockedBy
    }

  def getObjectAsString(workspaceBucketName: GcsBucketName, notebookName: GcsBlobName): IO[Option[String]] =
    google2StorageResource.use { google2StorageDAO =>
      google2StorageDAO.unsafeGetBlobBody(workspaceBucketName, notebookName, None)
    }

  def getObjectSize(workspaceBucketName: GcsBucketName, notebookName: GcsBlobName): IO[Int] =
    google2StorageResource.use { google2StorageDAO =>
      google2StorageDAO.getBlobBody(workspaceBucketName, notebookName).compile.toList.map(_.size)
    }

  def deleteObject(workspaceBucketName: GcsBucketName, notebookName: GcsBlobName): IO[RemoveObjectResult] =
    google2StorageResource.use { google2StorageDAO =>
      google2StorageDAO.removeObject(workspaceBucketName, notebookName).compile.lastOrError
    }

  def setObjectMetadata(workspaceBucketName: GcsBucketName,
                        notebookName: GcsBlobName,
                        metadata: Map[String, String]): IO[Unit] =
    //lockExpiresAt, lastLockedBy
    google2StorageResource.use { google2StorageDAO =>
      google2StorageDAO.setObjectMetadata(workspaceBucketName, notebookName, metadata, None).compile.drain
    }

  def setObjectContents(googleProject: GoogleProject,
                        workspaceBucketName: GcsBucketName,
                        notebookName: GcsBlobName,
                        contents: String)(implicit token: AuthToken): IO[Unit] = {
    val petServiceAccount = Sam.user.petServiceAccountEmail(googleProject.value)
    val userID = Identity.serviceAccount(petServiceAccount.value)

    google2StorageResource.use { google2StorageDAO =>
      for {
        _ <- google2StorageDAO
          .createBlob(workspaceBucketName, notebookName, contents.toCharArray.map(_.toByte))
          .compile
          .drain
        _ <- google2StorageDAO
          .setIamPolicy(workspaceBucketName,
                        Map(ObjectAdmin.asInstanceOf[StorageRole] -> NonEmptyList[Identity](userID, List())))
          .compile
          .drain
      } yield ()
    }
  }

}
