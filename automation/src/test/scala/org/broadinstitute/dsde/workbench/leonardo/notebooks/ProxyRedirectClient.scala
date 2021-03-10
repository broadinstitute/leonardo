package org.broadinstitute.dsde.workbench.leonardo.notebooks

import akka.Done
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.ResourceFile
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.LeoAuthToken
import org.broadinstitute.dsde.workbench.leonardo.{LeonardoConfig, RuntimeName}
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.service.RestClient
import org.openqa.selenium.WebDriver
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{get => httpGet, _}

import scala.concurrent.Future
import scala.io.Source

/**
 * Leonardo API service client.
 */
object ProxyRedirectClient extends RestClient with LazyLogging {

  private val url = LeonardoConfig.Leonardo.apiUrl

  def get(googleProject: GoogleProject, clusterName: RuntimeName, tool: String)(
    implicit webDriver: WebDriver
  ): ProxyRedirectPage = {
    val localhost = java.net.InetAddress.getLocalHost.getHostName
    val serverUrl = s"http://localhost:9090/${googleProject.value}/${clusterName.asString}/${tool}/client"
    new ProxyRedirectPage(serverUrl)
  }

  def startServer: Future[Http.ServerBinding] = {
    logger.info("Starting local server on port 9090")
    Http().bindAndHandle(route, "0.0.0.0", 9090)
  }

  def stopServer(bindingFuture: Future[Http.ServerBinding]): Future[Done] = {
    logger.info("Stopping local server")
    bindingFuture.flatMap(_.unbind())
  }

  val route: Route =
    path(Segment / Segment / Segment / "client") { (googleProject, clusterName, tool) =>
      httpGet {
        complete {
          logger.info(s"Serving proxy redirect client for $googleProject/$clusterName/$tool")
          HttpEntity(ContentTypes.`text/html(UTF-8)`,
                     getContent(GoogleProject(googleProject), RuntimeName(clusterName), tool))
        }
      }
    }

  private def getContent(googleProject: GoogleProject, clusterName: RuntimeName, tool: String) = {
    val resourceFile = ResourceFile("redirect-proxy-page.html")
    val raw = Source.fromFile(resourceFile).mkString
    val replacementMap = Map(
      "proxyUrl" -> s"${url}/proxy/${googleProject.value}/${clusterName.asString}/${tool}"
    )
    replacementMap.foldLeft(raw) {
      case (source, (key, replacement)) =>
        source.replaceAllLiterally("$(" + key + ")", s"""'$replacement'""")
    }
  }
}
