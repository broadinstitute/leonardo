package org.broadinstitute.dsde.workbench.leonardo.http
package api

import akka.actor.ActorSystem
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.{HttpCharsets, HttpEntity, HttpRequest, HttpResponse, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, RejectionHandler, Route, ValidationRejection}
import akka.stream.scaladsl.Sink
import cats.effect.{ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Encoder
import org.broadinstitute.dsde.workbench.leonardo.config.{RefererConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.leonardo.http.service.{
  AppService,
  DiskService,
  ProxyService,
  RuntimeService,
  StatusService
}
import org.broadinstitute.dsde.workbench.leonardo.model.LeoException
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import org.broadinstitute.dsde.workbench.leonardo.http.api.HttpRoutes.errorReportEncoder
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.google2.JsonCodec.traceIdEncoder

import scala.concurrent.{ExecutionContext, Future}

class HttpRoutes(
  swaggerConfig: SwaggerConfig,
  statusService: StatusService,
  proxyService: ProxyService,
  runtimeService: RuntimeService[IO],
  diskService: DiskService[IO],
  kubernetesService: AppService[IO],
  userInfoDirectives: UserInfoDirectives,
  contentSecurityPolicy: String,
  refererConfig: RefererConfig
)(implicit ec: ExecutionContext, ac: ActorSystem, cs: ContextShift[IO], metrics: OpenTelemetryMetrics[IO])
    extends LazyLogging {
  private val swaggerRoutes = new SwaggerRoutes(swaggerConfig)
  private val statusRoutes = new StatusRoutes(statusService)
  private val corsSupport = new CorsSupport(contentSecurityPolicy)
  private val proxyRoutes = new ProxyRoutes(proxyService, corsSupport, refererConfig)
  private val runtimeRoutes = new RuntimeRoutes(runtimeService, userInfoDirectives)
  private val diskRoutes = new DiskRoutes(diskService, userInfoDirectives)
  private val kubernetesRoutes = new AppRoutes(kubernetesService, userInfoDirectives)

  // basis for logRequestResult lifted from http://stackoverflow.com/questions/32475471/how-does-one-log-akka-http-client-requests
  private val logRequestResult: Directive0 = {
    def entityAsString(entity: HttpEntity): Future[String] =
      entity.dataBytes
        .map(_.decodeString(entity.contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).value))
        .runWith(Sink.head)

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val logLevel: LogLevel = resp.status.intValue / 100 match {
            case 5 => Logging.ErrorLevel
            case _ => Logging.DebugLevel
          }
          entityAsString(resp.entity)
            .map(data => LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $data", logLevel))
        case other =>
          Future.successful(LogEntry(s"$other", Logging.ErrorLevel)) // I don't really know when this case happens
      }
      entry.map(_.logTo(logger))
    }

    DebuggingDirectives.logRequestResult(LoggingMagnet(myLoggingFunction))
  }

  implicit val myExceptionHandler = {
    ExceptionHandler {
      case leoException: LeoException =>
        logger.error(s"request failed due to: ${leoException.getMessage}", leoException)
        complete(leoException.statusCode, leoException.toErrorReport)
      case e: Throwable =>
        logger.error(s"Unexpected error occurred processing route: ${e.getMessage}", e)
        complete(
          StatusCodes.InternalServerError -> ErrorReport(e.getMessage,
                                                         Some(StatusCodes.InternalServerError),
                                                         Seq(),
                                                         Seq(),
                                                         Some(e.getClass),
                                                         None)
        )
    }
  }

  implicit val myRejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle {
        case ValidationRejection(msg, cause) =>
          complete(HttpResponse(StatusCodes.BadRequest, entity = s"${cause.map(_.getMessage).getOrElse(msg)}"))
      }
      .handleNotFound(
        complete(
          (StatusCodes.NotFound, "API not found. Make sure you're calling the correct endpoint with correct method")
        )
      )
      .result()

  val route: Route = {
    logRequestResult {
      Route.seal(
        swaggerRoutes.routes ~ proxyRoutes.route ~ statusRoutes.route ~
          pathPrefix("api") {
            runtimeRoutes.routes ~ diskRoutes.routes ~ kubernetesRoutes.routes
          }
      )
    }
  }
}

object HttpRoutes {
  implicit val statusCodeEncoder: Encoder[StatusCode] = Encoder.encodeInt.contramap(_.intValue())
  implicit val classEncoder: Encoder[Class[_]] = Encoder.encodeString.contramap(_.toString)
  implicit val errorReportEncoder: Encoder[ErrorReport] = Encoder.forProduct5(
    "source",
    "message",
    "statusCode",
    "exceptionClass",
    "traceId"
  )(x => (x.source, x.message, x.statusCode, x.exceptionClass, x.traceId))
}
