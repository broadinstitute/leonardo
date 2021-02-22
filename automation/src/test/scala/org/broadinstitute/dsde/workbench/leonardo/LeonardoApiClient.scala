package org.broadinstitute.dsde.workbench.leonardo

import java.util.UUID
import java.util.concurrent.TimeoutException
import cats.effect.{IO, Resource, Timer}
import cats.syntax.all._
import io.chrisdavenport.log4cats.StructuredLogger
import org.broadinstitute.dsde.workbench.DoneCheckable
import org.broadinstitute.dsde.workbench.DoneCheckableSyntax._
import org.broadinstitute.dsde.workbench.google2.{streamFUntilDone, streamUntilDoneOrTimeout, DiskName, MachineTypeName}
import org.broadinstitute.dsde.workbench.leonardo.ApiJsonDecoder._
import org.broadinstitute.dsde.workbench.leonardo.http.AppRoutesTestJsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.http.DiskRoutesTestJsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.http.RuntimeRoutesTestJsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.http._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.util2.ExecutionContexts
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.middleware.Logger
import org.http4s.client.{blaze, Client}
import org.http4s.headers._

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object LeonardoApiClient {
  val defaultMediaType = `Content-Type`(MediaType.application.json)

  implicit def http4sBody[A](body: A)(implicit encoder: EntityEncoder[IO, A]): EntityBody[IO] =
    encoder.toEntity(body).body
  implicit val cs = IO.contextShift(global)
  // Once a runtime is deleted, leonardo returns 404 for getRuntime API call
  implicit def eitherDoneCheckable[A]: DoneCheckable[Either[Throwable, A]] = (op: Either[Throwable, A]) => op.isLeft

  implicit def getDiskDoneCheckable[A]: DoneCheckable[GetPersistentDiskResponse] =
    (op: GetPersistentDiskResponse) => op.status == DiskStatus.Ready

  implicit def getRuntimeDoneCheckable[A]: DoneCheckable[GetRuntimeResponseCopy] =
    (op: GetRuntimeResponseCopy) => op.status == ClusterStatus.Running || op.status == ClusterStatus.Error

  val client: Resource[IO, Client[IO]] = for {
    blockingEc <- ExecutionContexts.cachedThreadPool[IO]
    client <- blaze.BlazeClientBuilder[IO](blockingEc).resource
  } yield Logger[IO](logHeaders = true, logBody = true)(client)

  val rootUri = Uri.unsafeFromString(LeonardoConfig.Leonardo.apiUrl)
  val defaultCreateDiskRequest = CreateDiskRequest(
    Map.empty,
    None,
    None,
    None
  )

  val defaultCreateRuntime2Request = CreateRuntime2Request(
    Map("foo" -> UUID.randomUUID().toString),
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

  val defaultCreateDataprocRuntimeRequest = CreateRuntime2Request(
    Map("foo" -> UUID.randomUUID().toString),
    None,
    None,
    Some(
      RuntimeConfigRequest.DataprocConfig(
        Some(0),
        Some(MachineTypeName("n1-standard-4")),
        Some(DiskSize(100)),
        None,
        None,
        None,
        None,
        Map.empty
      )
    ),
    None,
    None,
    None,
    None,
    None,
    None,
    Set.empty,
    Map.empty
  )

  val defaultCreateAppRequest = CreateAppRequest(
    None,
    AppType.Galaxy,
    None,
    Map.empty,
    Map.empty,
    None,
    List.empty
  )

  val defaultBatchNodepoolRequest = BatchNodepoolCreateRequest(
    NumNodepools(2),
    None,
    None
  )

  def createRuntime(
    googleProject: GoogleProject,
    runtimeName: RuntimeName,
    createRuntime2Request: CreateRuntime2Request = defaultCreateRuntime2Request
  )(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.POST,
            headers = Headers.of(authHeader, defaultMediaType, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/runtimes/${googleProject.value}/${runtimeName.asString}"),
            body = createRuntime2Request
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to create runtime ${googleProject.value}/${runtimeName.asString}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def createRuntimeWithWait(googleProject: GoogleProject,
                            runtimeName: RuntimeName,
                            createRuntime2Request: CreateRuntime2Request)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[GetRuntimeResponseCopy] =
    for {
      _ <- createRuntime(googleProject, runtimeName, createRuntime2Request)
      res <- waitUntilRunning(googleProject, runtimeName)
    } yield res

  def startRuntime(
    googleProject: GoogleProject,
    runtimeName: RuntimeName
  )(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.POST,
            headers = Headers.of(authHeader, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/runtimes/${googleProject.value}/${runtimeName.asString}/start")
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to start runtime ${googleProject.value}/${runtimeName.asString}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def startRuntimeWithWait(
    googleProject: GoogleProject,
    runtimeName: RuntimeName
  )(implicit client: Client[IO], authHeader: Authorization, timer: Timer[IO]): IO[GetRuntimeResponseCopy] =
    for {
      _ <- startRuntime(googleProject, runtimeName)
      res <- waitUntilRunning(googleProject, runtimeName)
    } yield res

  def updateRuntime(
    googleProject: GoogleProject,
    runtimeName: RuntimeName,
    req: UpdateRuntimeRequest
  )(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.PATCH,
            headers = Headers.of(authHeader, defaultMediaType, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/runtimes/${googleProject.value}/${runtimeName.asString}"),
            body = req
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to update runtime ${googleProject.value}/${runtimeName.asString}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  //This line causes the body to be decoded as JSON, which will prevent error messagges from being seen
  //If you care about the error message, place the function before this line
  import org.http4s.circe.CirceEntityDecoder._

  def waitUntilRunning(googleProject: GoogleProject, runtimeName: RuntimeName, shouldError: Boolean = true)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[GetRuntimeResponseCopy] = {
    val ioa = getRuntime(googleProject, runtimeName)
    for {
      res <- timer.sleep(80 seconds) >> streamFUntilDone(ioa, 60, 10 seconds).compile.lastOrError
      _ <- res.status match {
        case ClusterStatus.Error =>
          if (shouldError)
            IO.raiseError(
              new RuntimeException(s"${googleProject.value}/${runtimeName.asString} errored due to ${res.errors}")
            )
          else IO.pure(res)
        case ClusterStatus.Running => IO.unit
        case other =>
          IO.raiseError(
            new TimeoutException(
              s"create runtime ${googleProject.value}/${runtimeName.asString}. Runtime is still in ${other}"
            )
          )
      }
    } yield res
  }

  def waitUntilAppRunning(googleProject: GoogleProject, appName: AppName, shouldError: Boolean = true)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization,
    logger: StructuredLogger[IO]
  ): IO[GetAppResponse] = {
    val ioa = getApp(googleProject, appName)
    implicit val doneCheckeable: DoneCheckable[GetAppResponse] = x =>
      x.status == AppStatus.Running || x.status == AppStatus.Error
    for {
      res <- timer.sleep(80 seconds) >> streamUntilDoneOrTimeout(
        ioa,
        120,
        10 seconds,
        s"app ${googleProject.value}/${appName.value} did not finish app creation after 20 minutes."
      )
      _ <- res.status match {
        case AppStatus.Error =>
          if (shouldError)
            IO.raiseError(
              new RuntimeException(s"${googleProject.value}/${appName.value} errored due to ${res.errors}")
            )
          else logger.info(s"${googleProject.value}/${appName.value} errored due to ${res.errors}")
        case _ => IO.unit
      }
    } yield res
  }

  def getRuntime(
    googleProject: GoogleProject,
    runtimeName: RuntimeName
  )(implicit client: Client[IO], authHeader: Authorization): IO[GetRuntimeResponseCopy] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client.expectOr[GetRuntimeResponseCopy](
        Request[IO](
          method = Method.GET,
          headers = Headers.of(authHeader, traceIdHeader),
          uri = rootUri.withPath(s"/api/google/v1/runtimes/${googleProject.value}/${runtimeName.asString}")
        )
      )(onError(s"Failed to get runtime ${googleProject.value}/${runtimeName.asString}"))
    } yield r

  def deleteRuntime(googleProject: GoogleProject,
                    runtimeName: RuntimeName,
                    deleteDisk: Boolean = true)(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.DELETE,
            headers = Headers.of(authHeader, traceIdHeader),
            uri = rootUri
              .withPath(s"/api/google/v1/runtimes/${googleProject.value}/${runtimeName.asString}")
              .withQueryParam("deleteDisk", deleteDisk)
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to delete runtime ${googleProject.value}/${runtimeName.asString}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def deleteRuntimeWithWait(googleProject: GoogleProject, runtimeName: RuntimeName, deleteDisk: Boolean = true)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[Unit] =
    for {
      _ <- deleteRuntime(googleProject, runtimeName, deleteDisk)
      ioa = getRuntime(googleProject, runtimeName).attempt
      res <- timer.sleep(20 seconds) >> streamFUntilDone(ioa, 50, 5 seconds).compile.lastOrError
      _ <- if (res.isDone) IO.unit
      else IO.raiseError(new TimeoutException(s"delete runtime ${googleProject.value}/${runtimeName.asString}"))
    } yield ()

  def createDisk(
    googleProject: GoogleProject,
    diskName: DiskName,
    createDiskRequest: CreateDiskRequest = defaultCreateDiskRequest
  )(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.POST,
            headers = Headers.of(authHeader, defaultMediaType, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/disks/${googleProject.value}/${diskName.value}"),
            body = createDiskRequest
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to create disk ${googleProject.value}/${diskName.value}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def createDiskWithWait(googleProject: GoogleProject, diskName: DiskName, createDiskRequest: CreateDiskRequest)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[Unit] =
    for {
      _ <- createDisk(googleProject, diskName, createDiskRequest)
      ioa = getDisk(googleProject, diskName)
      _ <- streamFUntilDone(ioa, 5, 5 seconds).compile.lastOrError
    } yield ()

  def getDisk(
    googleProject: GoogleProject,
    diskName: DiskName
  )(implicit client: Client[IO], authHeader: Authorization): IO[GetPersistentDiskResponse] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client.expectOr[GetPersistentDiskResponse](
        Request[IO](
          method = Method.GET,
          headers = Headers.of(authHeader, traceIdHeader),
          uri = rootUri
            .withPath(s"/api/google/v1/disks/${googleProject.value}/${diskName.value}")
        )
      )(onError(s"Failed to get disk ${googleProject.value}/${diskName.value}"))
    } yield r

  def listDisk(
    googleProject: GoogleProject,
    includeDeleted: Boolean = false
  )(implicit client: Client[IO], authHeader: Authorization): IO[List[ListPersistentDiskResponse]] = {
    val uriWithoutQueryParam = rootUri
      .withPath(s"/api/google/v1/disks/${googleProject.value}")

    val uri =
      if (includeDeleted) uriWithoutQueryParam.withQueryParam("includeDeleted", "true")
      else uriWithoutQueryParam

    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client.expectOr[List[ListPersistentDiskResponse]](
        Request[IO](
          method = Method.GET,
          headers = Headers.of(authHeader, traceIdHeader),
          uri = uri
        )
      )(onError(s"Failed to list disks in project ${googleProject.value}"))
    } yield r
  }

  def deleteDisk(googleProject: GoogleProject, diskName: DiskName)(implicit client: Client[IO],
                                                                   authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.DELETE,
            headers = Headers.of(authHeader, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/disks/${googleProject.value}/${diskName.value}")
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to delete disk ${googleProject.value}/${diskName.value}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def deleteDiskWithWait(googleProject: GoogleProject, diskName: DiskName)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[Unit] =
    for {
      _ <- deleteDisk(googleProject, diskName)
      ioa = getDisk(googleProject, diskName).attempt
      res <- timer.sleep(3 seconds) >> streamFUntilDone(ioa, 5, 5 seconds).compile.lastOrError
      _ <- if (res.isDone) IO.unit
      else IO.raiseError(new TimeoutException(s"delete disk ${googleProject.value}/${diskName.value}"))
    } yield ()

  private def onError(message: String)(response: Response[IO]): IO[Throwable] =
    for {
      body <- response.bodyText.compile.foldMonoid
    } yield RestError(message, response.status, Some(body))

  def createApp(
    googleProject: GoogleProject,
    appName: AppName,
    createAppRequest: CreateAppRequest = defaultCreateAppRequest
  )(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.POST,
            headers = Headers.of(authHeader, defaultMediaType, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/apps/${googleProject.value}/${appName.value}"),
            body = createAppRequest
          )
        )
        .use { resp =>
          if (resp.status.isSuccess)
            IO.unit
          else
            onError(s"Failed to create app ${googleProject.value}/${appName.value}")(resp).flatMap(IO.raiseError)
        }
    } yield r

  def createAppWithWait(
    googleProject: GoogleProject,
    appName: AppName,
    createAppRequest: CreateAppRequest = defaultCreateAppRequest
  )(implicit client: Client[IO], authHeader: Authorization, logger: StructuredLogger[IO], timer: Timer[IO]): IO[Unit] =
    for {
      _ <- createApp(googleProject, appName, createAppRequest)
      _ <- waitUntilAppRunning(googleProject, appName, true)
    } yield ()

  def deleteApp(googleProject: GoogleProject,
                appName: AppName,
                deleteDisk: Boolean = true)(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.DELETE,
            headers = Headers.of(authHeader, traceIdHeader),
            uri = rootUri
              .withPath(s"/api/google/v1/apps/${googleProject.value}/${appName.value}")
              .withQueryParam("deleteDisk", deleteDisk)
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to delete app ${googleProject.value}/${appName.value}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def deleteAppWithWait(googleProject: GoogleProject, appName: AppName, deleteDisk: Boolean = true)(
    implicit timer: Timer[IO],
    client: Client[IO],
    authHeader: Authorization
  ): IO[Unit] =
    for {
      _ <- deleteApp(googleProject, appName, deleteDisk)
      ioa = getApp(googleProject, appName).attempt
      res <- timer.sleep(120 seconds) >> streamFUntilDone(ioa, 30, 30 seconds).compile.lastOrError
      _ <- if (res.isDone) IO.unit
      else IO.raiseError(new TimeoutException(s"delete app ${googleProject.value}/${appName.value}"))
    } yield ()

  def getApp(googleProject: GoogleProject, appName: AppName)(implicit client: Client[IO],
                                                             authHeader: Authorization): IO[GetAppResponse] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client.expectOr[GetAppResponse](
        Request[IO](
          method = Method.GET,
          headers = Headers.of(authHeader, traceIdHeader),
          uri = rootUri
            .withPath(s"/api/google/v1/apps/${googleProject.value}/${appName.value}")
        )
      )(onError(s"Failed to get app ${googleProject.value}/${appName.value}"))
    } yield r

  def listApps(
    googleProject: GoogleProject,
    includeDeleted: Boolean = false
  )(implicit client: Client[IO], authHeader: Authorization): IO[List[ListAppResponse]] = {
    val uriWithoutQueryParam = rootUri
      .withPath(s"/api/google/v1/apps/${googleProject.value}")

    val uri =
      if (includeDeleted) uriWithoutQueryParam.withQueryParam("includeDeleted", "true")
      else uriWithoutQueryParam

    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client.expectOr[List[ListAppResponse]](
        Request[IO](
          method = Method.GET,
          headers = Headers.of(authHeader, traceIdHeader),
          uri = uri
        )
      )(onError(s"Failed to list apps in project ${googleProject.value}"))
    } yield r
  }

  def batchNodepoolCreate(
    googleProject: GoogleProject,
    req: BatchNodepoolCreateRequest = defaultBatchNodepoolRequest
  )(implicit client: Client[IO], authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.POST,
            headers = Headers.of(authHeader, defaultMediaType, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/apps/${googleProject.value}/batchNodepoolCreate"),
            body = req
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to batch create node pools in project ${googleProject.value}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def stopApp(googleProject: GoogleProject, appName: AppName)(implicit client: Client[IO],
                                                              authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.POST,
            headers = Headers.of(authHeader, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/apps/${googleProject.value}/${appName.value}/stop")
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to stop app ${googleProject.value}/${appName.value}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  def startApp(googleProject: GoogleProject, appName: AppName)(implicit client: Client[IO],
                                                               authHeader: Authorization): IO[Unit] =
    for {
      traceIdHeader <- genTraceIdHeader()
      r <- client
        .run(
          Request[IO](
            method = Method.POST,
            headers = Headers.of(authHeader, traceIdHeader),
            uri = rootUri.withPath(s"/api/google/v1/apps/${googleProject.value}/${appName.value}/start")
          )
        )
        .use { resp =>
          if (!resp.status.isSuccess) {
            onError(s"Failed to start app ${googleProject.value}/${appName.value}")(resp)
              .flatMap(IO.raiseError)
          } else
            IO.unit
        }
    } yield r

  private def genTraceIdHeader(): IO[Header] =
    IO(UUID.randomUUID().toString).map(uuid => Header(traceIdHeaderString, uuid))
}

final case class RestError(message: String, statusCode: Status, body: Option[String]) extends NoStackTrace {
  override def getMessage: String = s"message: ${message}, status: ${statusCode} body: ${body.getOrElse("")}"
}
