package org.broadinstitute.dsde.workbench.leonardo
package dao

import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.TimeUnit

import _root_.fs2._
import _root_.io.chrisdavenport.log4cats.Logger
import _root_.io.circe._
import _root_.io.circe.syntax._
import akka.http.scaladsl.model.StatusCode._
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import cats.effect.implicits._
import cats.effect.{Blocker, ContextShift, Effect, Resource, Timer}
import cats.syntax.all._
import cats.mtl.Ask
import com.google.api.services.plus.PlusScopes
import com.google.api.services.storage.StorageScopes
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import org.broadinstitute.dsde.workbench.google2.credentialResource
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.dao.HttpSamDAO._
import org.broadinstitute.dsde.workbench.leonardo.model.SamResource.ProjectSamResource
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.util.CacheMetrics
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{TraceId, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.{`Content-Type`, Authorization}

import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class HttpSamDAO[F[_]: Effect](httpClient: Client[F], config: HttpSamDaoConfig, blocker: Blocker)(
  implicit logger: Logger[F],
  cs: ContextShift[F],
  timer: Timer[F],
  metrics: OpenTelemetryMetrics[F]
) extends SamDAO[F]
    with Http4sClientDsl[F] {
  private val saScopes = Seq(PlusScopes.USERINFO_EMAIL, PlusScopes.USERINFO_PROFILE, StorageScopes.DEVSTORAGE_READ_ONLY)

  private[leonardo] val petTokenCache: LoadingCache[UserEmailAndProject, Option[String]] = CacheBuilder
    .newBuilder()
    .expireAfterWrite(config.petCacheExpiryTime.toMinutes, TimeUnit.MINUTES)
    .maximumSize(config.petCacheMaxSize)
    .recordStats()
    .build(
      new CacheLoader[UserEmailAndProject, Option[String]] {
        def load(userEmailAndProject: UserEmailAndProject): Option[String] = {
          implicit val traceId = Ask.const[F, TraceId](TraceId(UUID.randomUUID()))
          getPetAccessToken(userEmailAndProject.userEmail, userEmailAndProject.googleProject).toIO
            .unsafeRunSync()
        }
      }
    )

  val recordCacheMetricsProcess: Stream[F, Unit] =
    CacheMetrics("petTokenCache")
      .process(() => Effect[F].delay(petTokenCache.size), () => Effect[F].delay(petTokenCache.stats))

  def getStatus(implicit ev: Ask[F, TraceId]): F[StatusCheckResponse] =
    metrics.incrementCounter("sam/status") >>
      httpClient.expectOr[StatusCheckResponse](
        Request[F](
          method = Method.GET,
          uri = config.samUri.withPath(s"/status")
        )
      )(onError)

  def hasResourcePermissionUnchecked(resourceType: SamResourceType,
                                     resource: String,
                                     action: String,
                                     authHeader: Authorization)(
    implicit ev: Ask[F, TraceId]
  ): F[Boolean] =
    for {
      _ <- metrics.incrementCounter(s"sam/hasResourcePermission/${resourceType.asString}/${action}")
      res <- httpClient.expectOr[Boolean](
        Request[F](
          method = Method.GET,
          uri = config.samUri
            .withPath(
              s"/api/resources/v1/${resourceType.asString}/${resource}/action/${action}"
            ),
          headers = Headers.of(authHeader)
        )
      )(onError)
    } yield res

  def getListOfResourcePermissions[R, A](resource: R, authHeader: Authorization)(
    implicit sr: SamResourceAction[R, A],
    ev: Ask[F, TraceId]
  ): F[List[sr.ActionCategory]] = {
    implicit val d = sr.decoder
    metrics.incrementCounter(s"sam/getListOfResourcePermissions/${sr.resourceType.asString}") >>
      httpClient.expectOr[List[sr.ActionCategory]](
        Request[F](
          method = Method.GET,
          uri = config.samUri
            .withPath(s"/api/resources/v1/${sr.resourceType.asString}/${sr.resourceIdAsString(resource)}/actions"),
          headers = Headers.of(authHeader)
        )
      )(onError)
  }

  def getResourcePolicies[R](
    authHeader: Authorization
  )(implicit sr: SamResource[R], decoder: Decoder[R], ev: Ask[F, TraceId]): F[List[(R, SamPolicyName)]] =
    for {
      _ <- metrics.incrementCounter(s"sam/getResourcePolicies/${sr.resourceType.asString}")
      resp <- httpClient.expectOr[List[ListResourceResponse[R]]](
        Request[F](
          method = Method.GET,
          uri = config.samUri.withPath(s"/api/resources/v1/${sr.resourceType.asString}"),
          headers = Headers.of(authHeader)
        )
      )(onError)
    } yield resp.map(r => (r.samResourceId, r.samPolicyName))

  def createResource[R](resource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    ev: Ask[F, TraceId]
  ): F[Unit] =
    for {
      traceId <- ev.ask
      token <- getCachedPetAccessToken(creatorEmail, googleProject).flatMap(
        _.fold(
          Effect[F].raiseError[String](
            AuthProviderException(traceId,
                                  s"No pet SA found for ${creatorEmail} in ${googleProject}",
                                  StatusCodes.Unauthorized)
          )
        )(s => Effect[F].pure(s))
      )
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, token))
      _ <- logger.info(
        s"${traceId} | creating ${sr.resourceType.asString} resource in sam for ${googleProject}/${sr.resourceIdAsString(resource)}"
      )
      _ <- metrics.incrementCounter(s"sam/createResource/${sr.resourceType.asString}")
      _ <- httpClient
        .run(
          Request[F](
            method = Method.POST,
            uri = config.samUri
              .withPath(s"/api/resources/v1/${sr.resourceType.asString}/${sr.resourceIdAsString(resource)}"),
            headers = Headers.of(authHeader)
          )
        )
        .use { resp =>
          if (resp.status.isSuccess)
            Effect[F].unit
          else
            onError(resp).flatMap(Effect[F].raiseError[Unit])
        }
    } yield ()

  def createResourceV2[R](resource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    encoder: Encoder[R],
    ev: Ask[F, TraceId]
  ): F[Unit] =
    for {
      traceId <- ev.ask
      token <- getCachedPetAccessToken(creatorEmail, googleProject).flatMap(
        _.fold(
          Effect[F].raiseError[String](
            AuthProviderException(traceId,
                                  s"No pet SA found for ${creatorEmail} in ${googleProject}",
                                  StatusCodes.Unauthorized)
          )
        )(s => Effect[F].pure(s))
      )
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, token))
      _ <- logger.info(
        s"${traceId} | creating ${sr.resourceType.asString} resource in sam v2 for ${googleProject}/${sr.resourceIdAsString(resource)}"
      )
      _ <- metrics.incrementCounter(s"sam/createResource/${sr.resourceType.asString}")
      policies = Map[SamPolicyName, SamPolicyData](
        SamPolicyName.Creator -> SamPolicyData(List(creatorEmail), List(SamRole.Creator))
      )
      parent = SerializableSamResource(SamResourceType.Project, ProjectSamResourceId(googleProject))
      _ <- httpClient
        .run(
          Request[F](
            method = Method.POST,
            uri = config.samUri
              .withPath(s"/api/resources/v2/${sr.resourceType.asString}"),
            headers = Headers.of(authHeader, `Content-Type`(MediaType.application.json)),
            body = Stream.emits(CreateSamResourceRequest(resource, policies, parent).asJson.noSpaces.getBytes)
          )
        )
        .use { resp =>
          if (resp.status.isSuccess)
            Effect[F].unit
          else
            onError(resp).flatMap(Effect[F].raiseError[Unit])
        }
    } yield ()

  def deleteResource[R](resource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    ev: Ask[F, TraceId]
  ): F[Unit] =
    for {
      traceId <- ev.ask
      token <- getCachedPetAccessToken(creatorEmail, googleProject).flatMap(
        _.fold(
          Effect[F].raiseError[String](
            AuthProviderException(traceId,
                                  s"No pet SA found for ${creatorEmail} in ${googleProject}",
                                  StatusCodes.Unauthorized)
          )
        )(s => Effect[F].pure(s))
      )
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, token))
      _ <- metrics.incrementCounter(s"sam/deleteResource/${sr.resourceType.asString}")
      _ <- httpClient
        .run(
          Request[F](
            method = Method.DELETE,
            uri = config.samUri
              .withPath(s"/api/resources/v1/${sr.resourceType.asString}/${sr.resourceIdAsString(resource)}"),
            headers = Headers.of(authHeader)
          )
        )
        .use { resp =>
          resp.status match {
            case Status.NotFound =>
              logger.info(
                s"Fail to delete ${googleProject}/${sr.resourceIdAsString(resource)} because ${sr.resourceType.asString} doesn't exist in SAM"
              )
            case s if (s.isSuccess) => Effect[F].unit
            case _                  => onError(resp).flatMap(Effect[F].raiseError[Unit])
          }
        }
    } yield ()

  def getPetServiceAccount(authorization: Authorization, googleProject: GoogleProject)(
    implicit ev: Ask[F, TraceId]
  ): F[Option[WorkbenchEmail]] =
    metrics.incrementCounter("sam/getPetServiceAccount") >>
      httpClient.expectOptionOr[WorkbenchEmail](
        Request[F](
          method = Method.GET,
          uri = config.samUri.withPath(s"/api/google/v1/user/petServiceAccount/${googleProject.value}"),
          headers = Headers.of(authorization)
        )
      )(onError)

  def getUserProxy(userEmail: WorkbenchEmail)(implicit ev: Ask[F, TraceId]): F[Option[WorkbenchEmail]] =
    getAccessTokenUsingLeoJson.use { leoToken =>
      val authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, leoToken))
      metrics.incrementCounter("sam/getUserProxy") >>
        httpClient.expectOptionOr[WorkbenchEmail](
          Request[F](
            method = Method.GET,
            uri = config.samUri
              .withPath(s"/api/google/v1/user/proxyGroup/${URLEncoder.encode(userEmail.value, UTF_8.name)}"),
            headers = Headers.of(authHeader)
          )
        )(onError)
    }

  def getCachedPetAccessToken(userEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit ev: Ask[F, TraceId]
  ): F[Option[String]] =
    if (config.petCacheEnabled) {
      blocker.blockOn(Effect[F].delay(petTokenCache.get(UserEmailAndProject(userEmail, googleProject))))
    } else {
      getPetAccessToken(userEmail, googleProject)
    }

  private def getAccessTokenUsingLeoJson: Resource[F, String] =
    for {
      credential <- credentialResource(
        config.serviceAccountProviderConfig.leoServiceAccountJsonFile.toAbsolutePath.toString
      )
      scopedCredential = credential.createScoped(saScopes.asJava)
      _ <- Resource.liftF(Effect[F].delay(scopedCredential.refresh))
    } yield scopedCredential.getAccessToken.getTokenValue

  private def getPetAccessToken(userEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit ev: Ask[F, TraceId]
  ): F[Option[String]] =
    getAccessTokenUsingLeoJson.use { leoToken =>
      val leoAuth = Authorization(Credentials.Token(AuthScheme.Bearer, leoToken))
      for {
        _ <- metrics.incrementCounter("sam/getPetServiceAccount")
        // fetch user's pet SA key with leo's authorization token
        userPetKey <- httpClient.expectOptionOr[Json](
          Request[F](
            method = Method.GET,
            uri = config.samUri.withPath(
              s"/api/google/v1/petServiceAccount/${googleProject.value}/${URLEncoder.encode(userEmail.value, UTF_8.name)}"
            ),
            headers = Headers.of(leoAuth)
          )
        )(onError)
        token <- userPetKey.traverse { key =>
          val keyStream = new ByteArrayInputStream(key.toString().getBytes)
          Effect[F]
            .delay(ServiceAccountCredentials.fromStream(keyStream).createScoped(saScopes.asJava))
            .map(_.refreshAccessToken.getTokenValue)
        }
      } yield token
    }

  private def onError(response: Response[F])(implicit ev: Ask[F, TraceId]): F[Throwable] =
    for {
      traceId <- ev.ask
      body <- response.bodyText.compile.foldMonoid
      _ <- logger.error(s"${traceId} | Sam call failed: $body")
      _ <- metrics.incrementCounter("sam/errorResponse")
    } yield AuthProviderException(traceId, body, response.status.code)

  private def getProjectOwnerPolicyEmail(authorization: Authorization, googleProject: GoogleProject)(
    implicit ev: Ask[F, TraceId]
  ): F[SamPolicyEmail] =
    for {
      _ <- metrics.incrementCounter("sam/getProjectOwnerPolicyEmail")
      resp <- httpClient.expectOr[SyncStatusResponse](
        Request[F](
          method = Method.GET,
          uri = config.samUri.withPath(
            s"/api/google/v1/resource/${SamResourceType.Project.asString}/${googleProject.value}/${SamPolicyName.Owner.toString}/sync"
          ),
          headers = Headers.of(authorization)
        )
      )(onError)
    } yield resp.email

  override def getUserSubjectId(userEmail: WorkbenchEmail,
                                googleProject: GoogleProject)(implicit ev: Ask[F, TraceId]): F[Option[UserSubjectId]] =
    for {
      traceId <- ev.ask
      _ <- metrics.incrementCounter("sam/getSubjectId")
      token <- getCachedPetAccessToken(userEmail, googleProject).flatMap(
        _.fold(
          Effect[F].raiseError[String](
            AuthProviderException(traceId,
                                  s"No pet SA found for ${userEmail} in ${googleProject}",
                                  StatusCodes.Unauthorized)
          )
        )(s => Effect[F].pure(s))
      )
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, token))
      resp <- httpClient.expectOptionOr[GetGoogleSubjectIdResponse](
        Request[F](
          method = Method.GET,
          uri = config.samUri.withPath(
            s"/register/user/v2/self/info"
          ),
          headers = Headers.of(authHeader)
        )
      )(onError)
    } yield resp.map(_.userSubjectId)
}

object HttpSamDAO {
  def apply[F[_]: Effect](
    httpClient: Client[F],
    config: HttpSamDaoConfig,
    blocker: Blocker
  )(implicit logger: Logger[F],
    contextShift: ContextShift[F],
    timer: Timer[F],
    metrics: OpenTelemetryMetrics[F]): HttpSamDAO[F] =
    new HttpSamDAO[F](httpClient, config, blocker)

  implicit val samRoleEncoder: Encoder[SamRole] = Encoder.encodeString.contramap(_.asString)
  implicit val projectActionEncoder: Encoder[ProjectAction] = Encoder.encodeString.contramap(_.asString)
  implicit val runtimeActionEncoder: Encoder[RuntimeAction] = Encoder.encodeString.contramap(_.asString)
  implicit val persistentDiskActionEncoder: Encoder[PersistentDiskAction] = Encoder.encodeString.contramap(_.asString)
  implicit val appActionEncoder: Encoder[AppAction] = Encoder.encodeString.contramap(_.asString)
  implicit val policyDataEncoder: Encoder[SamPolicyData] =
    Encoder.forProduct3("memberEmails", "actions", "roles")(x => (x.memberEmails, List.empty[String], x.roles))
  implicit val samPolicyNameKeyEncoder: KeyEncoder[SamPolicyName] = new KeyEncoder[SamPolicyName] {
    override def apply(p: SamPolicyName): String = p.toString
  }
  implicit val samResourceTypeEncooder: Encoder[SamResourceType] = Encoder.encodeString.contramap(_.asString)
  implicit val samResourceIdEncooder: Encoder[SamResourceId] = Encoder.encodeString.contramap(_.resourceId)

  implicit val samResourceEncoder: Encoder[SerializableSamResource] =
    Encoder.forProduct2("resourceTypeName", "resourceId")(x => (x.resourceTypeName, x.resourceId))
  implicit def createSamResourceRequestEncoder[R: Encoder]: Encoder[CreateSamResourceRequest[R]] =
    Encoder.forProduct5("resourceId", "policies", "authDomain", "returnResource", "parent")(x =>
      (x.samResourceId, x.policies, List.empty[String], x.returnResource, x.parent)
    )

  implicit val samPolicyNameDecoder: Decoder[SamPolicyName] =
    Decoder.decodeString.map(s => SamPolicyName.stringToSamPolicyName.getOrElse(s, SamPolicyName.Other(s)))
  implicit val userSubjectIdDecoder: Decoder[UserSubjectId] =
    Decoder.decodeString.map(UserSubjectId.apply)
  implicit val samPolicyEmailDecoder: Decoder[SamPolicyEmail] = Decoder[WorkbenchEmail].map(SamPolicyEmail)
  implicit val projectActionDecoder: Decoder[ProjectAction] =
    Decoder.decodeString.map(x => ProjectAction.stringToAction.getOrElse(x, ProjectAction.Other(x)))
  implicit val runtimeActionDecoder: Decoder[RuntimeAction] =
    Decoder.decodeString.emap(x => RuntimeAction.stringToAction.get(x).toRight(s"Unknown runtime action: $x"))
  implicit val persistentDiskActionDecoder: Decoder[PersistentDiskAction] =
    Decoder.decodeString.emap(x =>
      PersistentDiskAction.stringToAction.get(x).toRight(s"Unknown persistent disk action: $x")
    )
  implicit val appActionDecoder: Decoder[AppAction] =
    Decoder.decodeString.emap(x => AppAction.stringToAction.get(x).toRight(s"Unknown app action: $x"))
  implicit val samRoleDecoder: Decoder[SamRole] =
    Decoder.decodeString.map(x => SamRole.stringToRole.getOrElse(x, SamRole.Other(x)))
  implicit val samPolicyDataDecoder: Decoder[SamPolicyData] = Decoder.instance { x =>
    for {
      memberEmails <- x.downField("memberEmails").as[List[WorkbenchEmail]]
      roles <- x.downField("roles").as[List[SamRole]]
    } yield SamPolicyData(memberEmails, roles)
  }
  implicit val syncStatusDecoder: Decoder[SyncStatusResponse] = Decoder.instance { x =>
    for {
      lastSyncDate <- x.downField("lastSyncDate").as[String]
      email <- x.downField("email").as[SamPolicyEmail]
    } yield SyncStatusResponse(lastSyncDate, email)
  }
  implicit def listResourceResponseDecoder[R: Decoder]: Decoder[ListResourceResponse[R]] = Decoder.instance { x =>
    for {
      resourceId <- x.downField("resourceId").as[R]
      policyName <- x.downField("accessPolicyName").as[SamPolicyName]
    } yield ListResourceResponse(resourceId, policyName)
  }
  val subsystemStatusDecoder: Decoder[SubsystemStatus] = Decoder.instance { c =>
    for {
      ok <- c.downField("ok").as[Boolean]
      messages <- c.downField("messages").as[Option[List[String]]]
    } yield SubsystemStatus(ok, messages)
  }
  implicit val systemsDecoder: Decoder[Map[Subsystem, SubsystemStatus]] = Decoder
    .decodeMap[Subsystem, SubsystemStatus](KeyDecoder.decodeKeyString.map(Subsystems.withName), subsystemStatusDecoder)
  implicit val statusCheckResponseDecoder: Decoder[StatusCheckResponse] = Decoder.instance { c =>
    for {
      ok <- c.downField("ok").as[Boolean]
      systems <- c.downField("systems").as[Map[Subsystem, SubsystemStatus]]
    } yield StatusCheckResponse(ok, systems)
  }
  implicit val getGoogleSubjectIdResponseDecoder: Decoder[GetGoogleSubjectIdResponse] =
    Decoder.forProduct1("userSubjectId")(GetGoogleSubjectIdResponse.apply)
}

final case class CreateSamResourceRequest[R](samResourceId: R,
                                             policies: Map[SamPolicyName, SamPolicyData],
                                             parent: SerializableSamResource,
                                             authDomain: List[String] = List.empty,
                                             returnResource: Boolean = false)
final case class SyncStatusResponse(lastSyncDate: String, email: SamPolicyEmail)
final case class ListResourceResponse[R](samResourceId: R, samPolicyName: SamPolicyName)
final case class HttpSamDaoConfig(samUri: Uri,
                                  petCacheEnabled: Boolean,
                                  petCacheExpiryTime: FiniteDuration,
                                  petCacheMaxSize: Int,
                                  serviceAccountProviderConfig: ServiceAccountProviderConfig)

final case class UserEmailAndProject(userEmail: WorkbenchEmail, googleProject: GoogleProject)
final case class SerializableSamResource(resourceTypeName: SamResourceType, resourceId: SamResourceId)

final case class GetGoogleSubjectIdResponse(userSubjectId: UserSubjectId)
final case object NotFoundException extends NoStackTrace
final case class AuthProviderException(traceId: TraceId, msg: String, code: StatusCode)
    extends LeoException(message = s"AuthProvider error: $msg", statusCode = code, traceId = Some(traceId))
    with NoStackTrace
