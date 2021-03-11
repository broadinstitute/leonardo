package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.Uri.Host
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Disposition`
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import cats.effect.{Blocker, ContextShift, IO, Timer}
import cats.syntax.all._
import cats.mtl.Ask
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import fs2.concurrent.InspectableQueue
import io.chrisdavenport.log4cats.StructuredLogger
import javax.net.ssl.SSLContext
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.ServiceName
import org.broadinstitute.dsde.workbench.leonardo.config.ProxyConfig
import org.broadinstitute.dsde.workbench.leonardo.dao.HostStatus._
import org.broadinstitute.dsde.workbench.leonardo.dao.google.GoogleOAuth2Service
import org.broadinstitute.dsde.workbench.leonardo.dao.{HostStatus, JupyterDAO, Proxy, TerminalName}
import org.broadinstitute.dsde.workbench.leonardo.db.{clusterQuery, DbReference, KubernetesServiceDbQueries}
import org.broadinstitute.dsde.workbench.leonardo.dns.{KubernetesDnsCache, RuntimeDnsCache}
import org.broadinstitute.dsde.workbench.leonardo.http.service.ProxyService._
import org.broadinstitute.dsde.workbench.leonardo.http.service.SamResourceCacheKey.{AppCacheKey, RuntimeCacheKey}
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.monitor.UpdateDateAccessMessage
import org.broadinstitute.dsde.workbench.leonardo.util.CacheMetrics
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{TraceId, UserInfo}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.util.toScalaDuration
import akka.http.scaladsl.unmarshalling.Unmarshal
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

final case class HostContext(status: HostStatus, description: String)

sealed trait SamResourceCacheKey extends Product with Serializable {
  def googleProject: GoogleProject
}
object SamResourceCacheKey {
  final case class RuntimeCacheKey(googleProject: GoogleProject, name: RuntimeName) extends SamResourceCacheKey
  final case class AppCacheKey(googleProject: GoogleProject, name: AppName) extends SamResourceCacheKey
}

final case class ProxyHostNotReadyException(context: HostContext, traceId: TraceId)
    extends LeoException(
      s"Proxy host ${context.description} is not ready yet. It may be updating, try again later",
      StatusCodes.Locked,
      traceId = Some(traceId)
    )

final case class ProxyHostPausedException(context: HostContext, traceId: TraceId)
    extends LeoException(
      s"Proxy host ${context.description} is stopped. Start your runtime before proceeding.",
      StatusCodes.UnprocessableEntity,
      traceId = Some(traceId)
    )

case class ProxyHostNotFoundException(context: HostContext, traceId: TraceId)
    extends LeoException(s"Proxy host ${context.description} not found", StatusCodes.NotFound, traceId = Some(traceId))

final case class ProxyException(context: HostContext, traceId: TraceId)
    extends LeoException(s"Unable to proxy connection to tool on ${context.description}",
                         StatusCodes.InternalServerError,
                         traceId = Some(traceId))

final case object AccessTokenExpiredException
    extends LeoException(s"Your access token is expired. Try logging in again",
                         StatusCodes.Unauthorized,
                         traceId = None)

class ProxyService(
  sslContext: SSLContext,
  proxyConfig: ProxyConfig,
  jupyterDAO: JupyterDAO[IO],
  runtimeDnsCache: RuntimeDnsCache[IO],
  kubernetesDnsCache: KubernetesDnsCache[IO],
  authProvider: LeoAuthProvider[IO],
  dateAccessUpdaterQueue: InspectableQueue[IO, UpdateDateAccessMessage],
  googleOauth2Service: GoogleOAuth2Service[IO],
  blocker: Blocker
)(implicit val system: ActorSystem,
  executionContext: ExecutionContext,
  timer: Timer[IO],
  cs: ContextShift[IO],
  dbRef: DbReference[IO],
  metrics: OpenTelemetryMetrics[IO],
  loggerIO: StructuredLogger[IO])
    extends LazyLogging {
  val httpsConnectionContext = ConnectionContext.httpsClient(sslContext)

  final val requestTimeout = toScalaDuration(system.settings.config.getDuration("akka.http.server.request-timeout"))
  logger.info(s"Leo proxy request timeout is $requestTimeout")

  /* Cache for the bearer token and corresponding google user email */
  private[leonardo] val googleTokenCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(proxyConfig.tokenCacheExpiryTime.toSeconds, TimeUnit.SECONDS)
    .maximumSize(proxyConfig.tokenCacheMaxSize)
    .recordStats()
    .build(
      new CacheLoader[String, (UserInfo, Instant)] {
        def load(key: String): (UserInfo, Instant) = {
          implicit val traceId = Ask.const[IO, TraceId](TraceId(UUID.randomUUID()))
          // UserInfo only stores a _relative_ expiration time to when the tokeninfo call was made
          // (e.g. tokenExpiresIn). So we also cache the _absolute_ expiration by doing
          // (now + tokenExpiresIn).
          val res = for {
            now <- nowInstant
            userInfo <- googleOauth2Service.getUserInfoFromToken(key)
          } yield (userInfo, now.plusSeconds(userInfo.tokenExpiresIn.toInt))
          res.unsafeRunSync()
        }
      }
    )

  val recordGoogleTokenCacheMetricsProcess: Stream[IO, Unit] =
    CacheMetrics("googleTokenCache")
      .process(() => IO(googleTokenCache.size), () => IO(googleTokenCache.stats))

  /* Ask the cache for the corresponding user info given a token */
  def getCachedUserInfoFromToken(token: String): IO[UserInfo] =
    for {
      cache <- blocker.blockOn(IO(googleTokenCache.get(token))).adaptError {
        case _ =>
          // Rethrow AuthenticationError if unable to look up the token
          AuthenticationError()
      }
      now <- nowInstant
      res <- cache match {
        case (userInfo, expireTime) =>
          if (expireTime.isAfter(now))
            IO.pure(userInfo.copy(tokenExpiresIn = expireTime.getEpochSecond - now.getEpochSecond))
          else
            IO.raiseError(AccessTokenExpiredException)
      }
    } yield res

  /* Cache for the sam resource from the database */
  private[leonardo] val samResourceCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(proxyConfig.internalIdCacheExpiryTime.toSeconds, TimeUnit.SECONDS)
    .maximumSize(proxyConfig.internalIdCacheMaxSize)
    .recordStats()
    .build(
      new CacheLoader[SamResourceCacheKey, Option[String]] {
        def load(key: SamResourceCacheKey): Option[String] = {
          val io = key match {
            case RuntimeCacheKey(googleProject, name) =>
              clusterQuery.getActiveClusterInternalIdByName(googleProject, name).map(_.map(_.resourceId)).transaction
            case AppCacheKey(googleProject, name) =>
              KubernetesServiceDbQueries
                .getActiveFullAppByName(googleProject, name)
                .map(_.map(_.app.samResourceId.resourceId))
                .transaction
          }
          io.unsafeRunSync()
        }
      }
    )

  val recordSamResourceCacheMetricsProcess: Stream[IO, Unit] =
    CacheMetrics("samResourceCache")
      .process(() => IO(samResourceCache.size), () => IO(samResourceCache.stats))

  def getCachedRuntimeSamResource(key: RuntimeCacheKey)(
    implicit ev: Ask[IO, AppContext]
  ): IO[RuntimeSamResourceId] =
    for {
      ctx <- ev.ask[AppContext]
      cacheResult <- blocker.blockOn(IO(samResourceCache.get(key)))
      resourceId = cacheResult.map(RuntimeSamResourceId)
      res <- resourceId match {
        case Some(samResource) => IO.pure(samResource)
        case None =>
          IO(
            logger.error(
              s"${ctx.traceId} | Unable to look up sam resource for ${key.toString}"
            )
          ) >> IO.raiseError(
            RuntimeNotFoundException(
              key.googleProject,
              key.name,
              s"${ctx.traceId} | Unable to look up sam resource for runtime ${key.googleProject.value} / ${key.name.asString}"
            )
          )
      }
    } yield res

  def getCachedAppSamResource(key: AppCacheKey)(
    implicit ev: Ask[IO, AppContext]
  ): IO[AppSamResourceId] =
    for {
      ctx <- ev.ask[AppContext]
      cacheResult <- blocker.blockOn(IO(samResourceCache.get(key)))
      resourceId = cacheResult.map(AppSamResourceId)
      res <- resourceId match {
        case Some(samResource) => IO.pure(samResource)
        case None =>
          IO(
            logger.error(
              s"${ctx.traceId} | Unable to look up sam resource for ${key.toString}"
            )
          ) >> IO.raiseError(
            AppNotFoundException(
              key.googleProject,
              key.name,
              ctx.traceId
            )
          )
      }
    } yield res

  def invalidateAccessToken(token: String): IO[Unit] =
    blocker.blockOn(IO(googleTokenCache.invalidate(token)))

  def proxyRequest(userInfo: UserInfo, googleProject: GoogleProject, runtimeName: RuntimeName, request: HttpRequest)(
    implicit ev: Ask[IO, AppContext]
  ): IO[HttpResponse] =
    for {
      ctx <- ev.ask[AppContext]
      samResource <- getCachedRuntimeSamResource(RuntimeCacheKey(googleProject, runtimeName))
      // Note both these Sam actions are cached so it should be okay to call hasPermission twice
      hasViewPermission <- authProvider.hasPermission(samResource, RuntimeAction.GetRuntimeStatus, userInfo)
      _ <- if (!hasViewPermission) {
        IO.raiseError(RuntimeNotFoundException(googleProject, runtimeName, ctx.traceId.asString))
      } else IO.unit
      hasConnectPermission <- authProvider.hasPermission(samResource, RuntimeAction.ConnectToRuntime, userInfo)
      _ <- if (!hasConnectPermission) {
        IO.raiseError(ForbiddenError(userInfo.userEmail))
      } else IO.unit
      hostStatus <- getRuntimeTargetHost(googleProject, runtimeName)
      _ <- hostStatus match {
        case HostReady(_) =>
          dateAccessUpdaterQueue.enqueue1(UpdateDateAccessMessage(runtimeName, googleProject, ctx.now))
        case _ => IO.unit
      }
      hostContext = HostContext(hostStatus, s"${googleProject.value}/${runtimeName.asString}")
      r <- proxyInternal(hostContext, request)
    } yield r

  def openTerminal(userInfo: UserInfo,
                   googleProject: GoogleProject,
                   runtimeName: RuntimeName,
                   terminalName: TerminalName,
                   request: HttpRequest)(implicit ev: Ask[IO, AppContext]): IO[HttpResponse] =
    for {
      terminalExists <- jupyterDAO.terminalExists(googleProject, runtimeName, terminalName)
      _ <- if (terminalExists)
        IO.unit
      else
        jupyterDAO.createTerminal(googleProject, runtimeName)
      r <- proxyRequest(userInfo, googleProject, runtimeName, request)
    } yield r

  def proxyAppRequest(userInfo: UserInfo,
                      googleProject: GoogleProject,
                      appName: AppName,
                      serviceName: ServiceName,
                      request: HttpRequest)(
    implicit ev: Ask[IO, AppContext]
  ): IO[HttpResponse] =
    for {
      ctx <- ev.ask[AppContext]
      samResource <- getCachedAppSamResource(AppCacheKey(googleProject, appName))
      // Note both these Sam actions are cached so it should be okay to call hasPermission twice
      hasViewPermission <- authProvider.hasPermission(samResource, AppAction.GetAppStatus, userInfo)
      _ <- if (!hasViewPermission) {
        IO.raiseError(AppNotFoundException(googleProject, appName, ctx.traceId))
      } else IO.unit
      hasConnectPermission <- authProvider.hasPermission(samResource, AppAction.ConnectToApp, userInfo)
      _ <- if (!hasConnectPermission) {
        IO.raiseError(ForbiddenError(userInfo.userEmail))
      } else IO.unit
      hostStatus <- getAppTargetHost(googleProject, appName)
      hostContext = HostContext(hostStatus, s"${googleProject.value}/${appName.value}/${serviceName.value}")
      r <- proxyInternal(hostContext, request)
    } yield r

  private[service] def getRuntimeTargetHost(googleProject: GoogleProject, runtimeName: RuntimeName): IO[HostStatus] =
    Proxy.getRuntimeTargetHost[IO](runtimeDnsCache, googleProject, runtimeName)

  private[service] def getAppTargetHost(googleProject: GoogleProject, appName: AppName): IO[HostStatus] =
    Proxy.getAppTargetHost[IO](kubernetesDnsCache, googleProject, appName)

  private def proxyInternal(hostContext: HostContext, request: HttpRequest)(
    implicit ev: Ask[IO, AppContext]
  ): IO[HttpResponse] =
    for {
      ctx <- ev.ask[AppContext]
      _ <- loggerIO.debug(ctx.loggingCtx)(
        s"Received proxy request for ${hostContext.description}: ${runtimeDnsCache.stats} / ${runtimeDnsCache.size}"
      )
      res <- hostContext.status match {
        case HostReady(targetHost) =>
          // If this is a WebSocket request (e.g. wss://leo:8080/...) then akka-http injects a
          // virtual UpgradeToWebSocket header which contains facilities to handle the WebSocket data.
          // The presence of this header distinguishes WebSocket from http requests.
          val res = for {
            response <- request.attribute(AttributeKeys.webSocketUpgrade) match {
              case Some(upgrade) =>
                IO.fromFuture(IO(handleWebSocketRequest(targetHost, request, upgrade)))
              case None =>
                IO.fromFuture(IO(handleHttpRequest(targetHost, request)))
            }
            r <- if (response.status.isFailure())
              loggerIO
                .info(ctx.loggingCtx)(
                  s"Error response for proxied request ${request.uri}: ${response.status}, ${Unmarshal(response.entity.withSizeLimit(1024))
                    .to[String]}"
                )
                .as(response)
            else IO.pure(response)
          } yield r

          res.recoverWith {
            case e =>
              loggerIO.error(ctx.loggingCtx, e)(s"Error occurred in proxy") >> IO.raiseError[HttpResponse](
                ProxyException(hostContext, ctx.traceId)
              )
          }
        case HostNotReady =>
          loggerIO.warn(ctx.loggingCtx)(s"proxy host not ready for ${hostContext.description}") >> IO.raiseError(
            ProxyHostNotReadyException(hostContext, ctx.traceId)
          )
        case HostPaused =>
          loggerIO.warn(ctx.loggingCtx)(s"proxy host paused for ${hostContext.description}") >> IO.raiseError(
            ProxyHostPausedException(hostContext, ctx.traceId)
          )
        case HostNotFound =>
          loggerIO.warn(ctx.loggingCtx)(s"proxy host not found for ${hostContext.description}") >> IO.raiseError(
            ProxyHostNotFoundException(hostContext, ctx.traceId)
          )
      }
    } yield res

  private def handleHttpRequest(targetHost: Host, request: HttpRequest): Future[HttpResponse] = {
    logger.debug(s"Opening https connection to ${targetHost.address}:${proxyConfig.proxyPort}")
    // A note on akka-http philosophy:
    // The Akka HTTP server is implemented on top of Streams and makes heavy use of it. Requests come
    // in as a Source[HttpRequest] and responses are returned as a Sink[HttpResponse]. The transformation
    // from Source to Sink is done via a Flow[HttpRequest, HttpResponse, _].
    // For more information, see: http://doc.akka.io/docs/akka-http/10.0.9/scala/http/introduction.html

    // Initializes a Flow representing a prospective connection to the given endpoint. The connection
    // is not made until a Source and Sink are plugged into the Flow (i.e. it is materialized).
    val flow = Http().outgoingConnectionHttps(targetHost.address, proxyConfig.proxyPort, httpsConnectionContext)

    // Now build a Source[Request] out of the original HttpRequest. We need to make some modifications
    // to the original request in order for the proxy to work:

    // Rewrite the path if it is proxy/*/*/jupyter/, otherwise pass it through as is (see rewriteJupyterPath)
    val rewrittenPath = rewriteJupyterPath(request.uri.path)

    // 1. filter out headers not needed for the backend server
    val newHeaders = filterHeaders(request.headers)
    // 2. strip out Uri.Authority:
    val newUri = Uri(path = rewrittenPath, queryString = request.uri.rawQueryString)
    // 3. build a new HttpRequest
    val newRequest = request.withHeaders(headers = newHeaders).withUri(uri = newUri)

    // Plug a Source and Sink into our Flow. This materializes the Flow and initializes the HTTP connection
    // to the notebook server.
    // - the Source is the modified HttpRequest from above
    // - the Sink just takes the first element because we only expect 1 response

    // Note: we're calling toStrict here which forces the proxy to load the entire response into memory before
    // returning it to the caller. Technically, this should not be required: we should be able to stream all
    // data between client, proxy, and server. However Jupyter is doing something strange and the proxy only
    // works when toStrict is used. Luckily, it's only needed for HTTP requests (which are fairly small) and not
    // WebSocket requests (which could potentially be large).
    Source
      .single(newRequest)
      .via(flow)
      .map(fixContentDisposition)
      .runWith(Sink.head)
      .flatMap(_.toStrict(requestTimeout))
  }

  // This is our current workaround for a bug that causes notebooks to download with "utf-8''" prepended to the file name
  // This is due to an akka-http bug currently being worked on here: https://github.com/playframework/playframework/issues/7719
  // For now, for each response that has a Content-Disposition header with a 'filename' in the header params, we remove any "utf-8''".
  // Should not affect any other responses.
  private def fixContentDisposition(httpResponse: HttpResponse): HttpResponse =
    httpResponse.header[`Content-Disposition`] match {
      case Some(header) => {
        val keyName = "filename"
        header.params.get(keyName) match {
          case Some(fileName) => {
            val newFileName = fileName.replace("utf-8''", "") // a filename that doesn't have utf-8'' shouldn't be affected
            val newParams = header.params + (keyName -> newFileName)
            val newHeader = `Content-Disposition`(header.dispositionType, newParams)
            val newHeaders = httpResponse.headers.filter(header => header.isNot("content-disposition")) ++ Seq(
              newHeader
            )
            httpResponse.withHeaders(headers = newHeaders)
          }
          case None => httpResponse
        }
      }
      case None => httpResponse
    }

  private def handleWebSocketRequest(targetHost: Host,
                                     request: HttpRequest,
                                     upgrade: WebSocketUpgrade): Future[HttpResponse] = {
    logger.info(s"Opening websocket connection to ${targetHost.address}")

    // This is a similar idea to handleHttpRequest(), we're just using WebSocket APIs instead of HTTP ones.
    // The basis for this method was lifted from https://github.com/akka/akka-http/issues/1289#issuecomment-316269886.

    // Initialize a Flow for the WebSocket conversation.
    // `Message` is the root of the ADT for WebSocket messages. A Message may be a TextMessage or a BinaryMessage.
    val flow =
      Flow.fromSinkAndSourceMat(Sink.asPublisher[Message](fanout = false), Source.asSubscriber[Message])(
        Keep.both
      )

    // Make a single WebSocketRequest to the notebook server, passing in our Flow. This returns a Future[WebSocketUpgradeResponse].
    // Keep our publisher/subscriber (e.g. sink/source) for use later. These are returned because we specified Keep.both above.
    // Note that we are rewriting the paths for any requests that are routed to /proxy/*/*/jupyter/
    val (responseFuture, (publisher, subscriber)) = Http().singleWebSocketRequest(
      WebSocketRequest(
        request.uri.copy(path = rewriteJupyterPath(request.uri.path),
                         authority = request.uri.authority.copy(host = targetHost, port = proxyConfig.proxyPort),
                         scheme = "wss"),
        extraHeaders = filterHeaders(request.headers),
        upgrade.requestedProtocols.headOption
      ),
      flow,
      httpsConnectionContext
    )

    // If we got a valid WebSocketUpgradeResponse, call handleMessages with our publisher/subscriber, which are
    // already materialized from the HttpRequest.
    // If we got an invalid WebSocketUpgradeResponse, simply return it without proxying any additional messages.
    responseFuture.map {
      case ValidUpgrade(response, chosenSubprotocol) =>
        val webSocketResponse = upgrade.handleMessages(
          Flow.fromSinkAndSource(Sink.fromSubscriber(subscriber), Source.fromPublisher(publisher)),
          chosenSubprotocol
        )
        webSocketResponse.withHeaders(webSocketResponse.headers ++ filterHeaders(response.headers))

      case InvalidUpgradeResponse(response, cause) =>
        logger.warn("WebSocket upgrade response was invalid: {}", cause)
        response
    }
  }

  private def filterHeaders(headers: immutable.Seq[HttpHeader]) =
    headers.filterNot(header => HeadersToFilter(header.lowercaseName()))

  private val HeadersToFilter = Set(
    "Host",
    "Timeout-Access",
    "Sec-WebSocket-Accept",
    "Sec-WebSocket-Version",
    "Sec-WebSocket-Key",
    "Sec-WebSocket-Extensions",
    "Sec-WebSocket-Protocol",
    "UpgradeToWebSocket",
    "Upgrade",
    "Connection"
  ).map(_.toLowerCase)
}

object ProxyService {
  // From the outside, we are going to support TWO paths to access Jupyter:
  // 1)   /notebooks/{googleProject}/{clusterName}/
  // 2)   /proxy/{googleProject}/{clusterName}/jupyter/
  // 3)   /notebooks/{googleProject}/{clusterName}/jupyter/
  //
  // To greatly simplify things on the backend, we will funnel all requests into path #1. Eventually
  // we will remove that path and #2 will be the sole entry point for users. At that point, we can
  // update the code in here to not rewrite any paths for Jupyter. We will also need to update the
  // paths in related areas like jupyter_notebook_config.py
  def rewriteJupyterPath(path: Uri.Path): Uri.Path = {
    val proxyPattern = "\\/proxy\\/([^\\/]*)\\/([^\\/]*)\\/jupyter\\/?(.*)?".r
    val notebooksPattern = "\\/notebooks\\/([^\\/]*)\\/([^\\/]*)\\/jupyter\\/?(.*)?".r

    path.toString match {
      case proxyPattern(project, cluster, path) => {
        Uri.Path("/notebooks/" + project + "/" + cluster + "/" + path)
      }
      case notebooksPattern(project, cluster, path) => {
        Uri.Path("/notebooks/" + project + "/" + cluster + "/" + path)
      }
      case _ => path
    }
  }
}
