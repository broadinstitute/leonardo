package org.broadinstitute.dsde.workbench.leonardo
package auth

import java.util.UUID
import java.util.concurrent.TimeUnit

import cats.data.NonEmptyList
import cats.effect.implicits._
import cats.effect.{Blocker, ContextShift, Effect, Sync, Timer}
import cats.syntax.all._
import cats.mtl.Ask
import com.google.common.cache.{CacheBuilder, CacheLoader}
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.circe.{Decoder, Encoder}
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.leonardo.dao.SamDAO
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.util.CacheMetrics
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{TraceId, UserInfo, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}

import scala.concurrent.duration._

class SamAuthProvider[F[_]: Effect: Logger: Timer: OpenTelemetryMetrics](samDao: SamDAO[F],
                                                                         config: SamAuthProviderConfig,
                                                                         saProvider: ServiceAccountProvider[F],
                                                                         blocker: Blocker)(implicit cs: ContextShift[F])
    extends LeoAuthProvider[F]
    with Http4sClientDsl[F] {
  override def serviceAccountProvider: ServiceAccountProvider[F] = saProvider

  // We cache some auth results from Sam which are requested very often by the proxy, such as
  // "connect to runtime" and "connect to app".
  private[auth] val authCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(config.authCacheExpiryTime.toSeconds, TimeUnit.SECONDS)
    .maximumSize(config.authCacheMaxSize)
    .recordStats()
    .build(
      new CacheLoader[AuthCacheKey, java.lang.Boolean] {
        override def load(key: AuthCacheKey): java.lang.Boolean = {
          implicit val traceId = Ask.const[F, TraceId](TraceId(UUID.randomUUID()))
          checkPermission(key.samResourceType, key.samResource, key.action, key.authorization).toIO
            .unsafeRunSync()
        }
      }
    )

  val recordCacheMetricsProcess: Stream[F, Unit] =
    CacheMetrics("authCache").process(() => Effect[F].delay(authCache.size), () => Effect[F].delay(authCache.stats))

  override def hasPermission[R, A](samResource: R, action: A, userInfo: UserInfo)(
    implicit sr: SamResourceAction[R, A],
    ev: Ask[F, TraceId]
  ): F[Boolean] = {
    val authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, userInfo.accessToken.token))
    if (config.authCacheEnabled && sr.cacheableActions.contains(action)) {
      blocker.blockOn(
        Effect[F].delay(
          authCache
            .get(
              AuthCacheKey(sr.resourceType, sr.resourceIdAsString(samResource), authHeader, sr.actionAsString(action))
            )
            .booleanValue()
        )
      )
    } else {
      checkPermission(sr.resourceType, sr.resourceIdAsString(samResource), sr.actionAsString(action), authHeader)
    }
  }

  private def checkPermission(samResourceType: SamResourceType,
                              samResource: String,
                              action: String,
                              authHeader: Authorization)(
    implicit ev: Ask[F, TraceId]
  ): F[Boolean] =
    for {
      traceId <- ev.ask
      res <- samDao.hasResourcePermissionUnchecked(samResourceType, samResource, action, authHeader).recoverWith {
        case e =>
          Logger[F]
            .info(e)(s"${traceId} | $action is not allowed for resource $samResource")
            .as(false)
      }
    } yield res

  override def hasPermissionWithProjectFallback[R, A](
    samResource: R,
    action: A,
    projectAction: ProjectAction,
    userInfo: UserInfo,
    googleProject: GoogleProject
  )(implicit sr: SamResourceAction[R, A], ev: Ask[F, TraceId]): F[Boolean] =
    for {
      // First check permission at the resource level
      resourcePermission <- hasPermission(samResource, action, userInfo)
      // Fall back to the project-level check if necessary
      res <- resourcePermission match {
        case true => Sync[F].pure(true)
        case _    => hasPermission(ProjectSamResourceId(googleProject), projectAction, userInfo)
      }
    } yield res

  override def getActions[R, A](
    samResource: R,
    userInfo: UserInfo
  )(implicit sr: SamResourceAction[R, A], ev: Ask[F, TraceId]): F[List[sr.ActionCategory]] = {
    val authorization = Authorization(Credentials.Token(AuthScheme.Bearer, userInfo.accessToken.token))
    for {
      listOfPermissions <- samDao
        .getListOfResourcePermissions[R, A](samResource, authorization)
      setOfPermissions = listOfPermissions.toSet
      callerActions = sr.allActions.collect { case a if setOfPermissions.contains(a) => a }
    } yield callerActions
  }

  def getActionsWithProjectFallback[R, A](samResource: R, googleProject: GoogleProject, userInfo: UserInfo)(
    implicit sr: SamResourceAction[R, A],
    ev: Ask[F, TraceId]
  ): F[(List[sr.ActionCategory], List[ProjectAction])] = {
    val authorization = Authorization(Credentials.Token(AuthScheme.Bearer, userInfo.accessToken.token))
    for {
      listOfPermissions <- samDao
        .getListOfResourcePermissions(samResource, authorization)
      setOfPermissions = listOfPermissions.toSet
      callerActions = sr.allActions.collect { case a if setOfPermissions.contains(a) => a }

      listOfProjectPermissions <- samDao.getListOfResourcePermissions(ProjectSamResourceId(googleProject),
                                                                      authorization)
      setOfProjectPermissions = listOfProjectPermissions.toSet
      projectCallerActions = ProjectAction.allActions.toList.collect {
        case a if setOfProjectPermissions.contains(a) => a
      }
    } yield (callerActions, projectCallerActions)
  }

  override def filterUserVisible[R](resources: NonEmptyList[R], userInfo: UserInfo)(
    implicit sr: SamResource[R],
    decoder: Decoder[R],
    ev: Ask[F, TraceId]
  ): F[List[R]] = {
    val authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, userInfo.accessToken.token))
    for {
      resourcePolicies <- samDao
        .getResourcePolicies[R](authHeader)
      res = resourcePolicies.filter { case (_, pn) => sr.policyNames.contains(pn) }
    } yield resources.filter(r => res.exists(_._1 == r))
  }

  def filterUserVisibleWithProjectFallback[R](
    resources: NonEmptyList[(GoogleProject, R)],
    userInfo: UserInfo
  )(
    implicit sr: SamResource[R],
    decoder: Decoder[R],
    ev: Ask[F, TraceId]
  ): F[List[(GoogleProject, R)]] = {
    val authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, userInfo.accessToken.token))
    for {
      projectPolicies <- samDao.getResourcePolicies[ProjectSamResourceId](authHeader)
      owningProjects = projectPolicies.collect {
        case (r, SamPolicyName.Owner) => r.googleProject
      }
      resourcePolicies <- samDao
        .getResourcePolicies[R](authHeader)
      res = resourcePolicies.filter { case (_, pn) => sr.policyNames.contains(pn) }
    } yield resources.filter {
      case (project, r) =>
        owningProjects.contains(project) || res.exists(_._1 == r)
    }
  }

  override def notifyResourceCreated[R](
    samResource: R,
    creatorEmail: WorkbenchEmail,
    googleProject: GoogleProject
  )(implicit sr: SamResource[R], encoder: Encoder[R], ev: Ask[F, TraceId]): F[Unit] =
    // TODO: consider using v2 for all existing entities if this works out for apps https://broadworkbench.atlassian.net/browse/IA-2569
    // Apps are modeled different in SAM than other leo resources.
    if (sr.resourceType != SamResourceType.App)
      samDao.createResource(samResource, creatorEmail, googleProject)
    else
      samDao.createResourceV2(samResource, creatorEmail, googleProject)

  override def notifyResourceDeleted[R](
    samResource: R,
    creatorEmail: WorkbenchEmail,
    googleProject: GoogleProject
  )(implicit sr: SamResource[R], ev: Ask[F, TraceId]): F[Unit] =
    samDao.deleteResource(samResource, creatorEmail, googleProject)

}

final case class SamAuthProviderConfig(authCacheEnabled: Boolean,
                                       authCacheMaxSize: Int = 1000,
                                       authCacheExpiryTime: FiniteDuration = 15 minutes)

private[auth] case class AuthCacheKey(samResourceType: SamResourceType,
                                      samResource: String,
                                      authorization: Authorization,
                                      action: String)
