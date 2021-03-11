package org.broadinstitute.dsde.workbench.leonardo
package dao

import java.util.UUID

import cats.effect.IO
import cats.syntax.all._
import cats.mtl.Ask
import io.circe.{Decoder, Encoder}
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.leonardo.dao.MockSamDAO._
import org.broadinstitute.dsde.workbench.leonardo.model.{SamResource, SamResourceAction}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{TraceId, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.util.health.StatusCheckResponse
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

// TODO this class is full of .asInstanceOf calls to make our generic methods work with the mutable Maps.
// There may be a better way to structure this mock.
class MockSamDAO extends SamDAO[IO] {
  val billingProjects: mutable.Map[(ProjectSamResourceId, Authorization), Set[ProjectAction]] = new TrieMap()
  val runtimes: mutable.Map[(RuntimeSamResourceId, Authorization), Set[RuntimeAction]] = new TrieMap()
  val persistentDisks: mutable.Map[(PersistentDiskSamResourceId, Authorization), Set[PersistentDiskAction]] =
    new TrieMap()
  val apps: mutable.Map[(AppSamResourceId, Authorization), Set[AppAction]] = new TrieMap()

  var projectOwners: Map[Authorization, Set[(ProjectSamResourceId, SamPolicyName)]] = Map.empty
  var runtimeCreators: Map[Authorization, Set[(RuntimeSamResourceId, SamPolicyName)]] = Map.empty
  var diskCreators: Map[Authorization, Set[(PersistentDiskSamResourceId, SamPolicyName)]] = Map.empty
  var appCreators: Map[Authorization, Set[(AppSamResourceId, SamPolicyName)]] = Map.empty

  //we don't care much about traceId in unit tests, hence providing a constant UUID here
  implicit val traceId = Ask.const[IO, TraceId](TraceId(UUID.randomUUID()))

  override def hasResourcePermissionUnchecked(resourceType: SamResourceType,
                                              resource: String,
                                              action: String,
                                              authHeader: Authorization)(
    implicit ev: Ask[IO, TraceId]
  ): IO[Boolean] =
    resourceType match {
      case SamResourceType.Project =>
        val res = billingProjects
          .get((ProjectSamResourceId(GoogleProject(resource)), authHeader))
          .map(_.map(_.asString).contains(action))
          .getOrElse(false)
        IO.pure(res)
      case SamResourceType.Runtime =>
        val res = runtimes
          .get((RuntimeSamResourceId(resource), authHeader))
          .map(_.map(_.asString).contains(action))
          .getOrElse(false)
        IO.pure(res)
      case SamResourceType.PersistentDisk =>
        val res = persistentDisks
          .get((PersistentDiskSamResourceId(resource), authHeader))
          .map(_.map(_.asString).contains(action))
          .getOrElse(false)
        IO.pure(res)
      case SamResourceType.App =>
        val res = apps
          .get((AppSamResourceId(resource), authHeader))
          .map(_.map(_.asString).contains(action))
          .getOrElse(false)
        IO.pure(res)
    }

  override def getResourcePolicies[R](authHeader: Authorization)(
    implicit sr: SamResource[R],
    decoder: Decoder[R],
    ev: Ask[IO, TraceId]
  ): IO[List[(R, SamPolicyName)]] =
    sr.resourceType match {
      case SamResourceType.Runtime =>
        IO.pure(
          runtimeCreators.get(authHeader).map(_.toList).getOrElse(List.empty).asInstanceOf[List[(R, SamPolicyName)]]
        )
      case SamResourceType.Project =>
        IO.pure(
          projectOwners.get(authHeader).map(_.toList).getOrElse(List.empty).asInstanceOf[List[(R, SamPolicyName)]]
        )
      case SamResourceType.PersistentDisk =>
        IO.pure(diskCreators.get(authHeader).map(_.toList).getOrElse(List.empty).asInstanceOf[List[(R, SamPolicyName)]])
      case SamResourceType.App =>
        IO.pure(appCreators.get(authHeader).map(_.toList).getOrElse(List.empty).asInstanceOf[List[(R, SamPolicyName)]])
    }

  override def createResource[R](resource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    ev: Ask[IO, TraceId]
  ): IO[Unit] = {
    val authHeader = userEmailToAuthorization(creatorEmail)
    resource match {
      case r: RuntimeSamResourceId =>
        IO(runtimes += (r, authHeader) -> RuntimeAction.allActions) >>
          IO {
            runtimeCreators =
              runtimeCreators |+| Map(authHeader -> Set((r, SamPolicyName.Creator)))
          }.void
      case r: PersistentDiskSamResourceId =>
        IO(persistentDisks += (r, authHeader) -> PersistentDiskAction.allActions) >>
          IO {
            diskCreators = diskCreators |+| Map(
              authHeader -> Set(
                (r, SamPolicyName.Creator)
              )
            )
          }.void
      case r: ProjectSamResourceId =>
        IO(billingProjects += (r, authHeader) -> ProjectAction.allActions) >>
          IO {
            projectOwners = projectOwners |+| Map(
              authHeader -> Set(
                (r, SamPolicyName.Owner)
              )
            )
          }.void
      case r: AppSamResourceId =>
        IO(apps += (r, authHeader) -> AppAction.allActions) >>
          IO {
            appCreators = appCreators |+| Map(
              authHeader -> Set(
                (r, SamPolicyName.Creator)
              )
            )
          }.void
    }
  }

  override def createResourceV2[R](resource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    encoder: Encoder[R],
    ev: Ask[IO, TraceId]
  ): IO[Unit] = {
    val authHeader = userEmailToAuthorization(creatorEmail)
    val ownerAuthHeader = userEmailToAuthorization(projectOwnerEmail)
    resource match {
      case r: RuntimeSamResourceId =>
        IO(runtimes += (r, authHeader) -> RuntimeAction.allActions) >>
          IO {
            runtimeCreators = runtimeCreators |+| Map(
              authHeader -> Set(
                (r, SamPolicyName.Creator)
              )
            )
          }.void
      case r: PersistentDiskSamResourceId =>
        IO(persistentDisks += (r, authHeader) -> PersistentDiskAction.allActions) >>
          IO {
            diskCreators = diskCreators |+| Map(
              authHeader -> Set(
                (r, SamPolicyName.Creator)
              )
            )
          }.void
      case r: ProjectSamResourceId =>
        IO(billingProjects += (r, authHeader) -> ProjectAction.allActions) >> IO {
          projectOwners = projectOwners |+| Map(
            authHeader -> Set(
              (r, SamPolicyName.Owner)
            )
          )
        }.void
      case r: AppSamResourceId =>
        IO(
          apps ++=
            Map((r, authHeader) -> AppAction.allActions, (r, ownerAuthHeader) -> appManagerActions)
        ) >>
          IO {
            appCreators = appCreators |+| Map(
              authHeader -> Set((r, SamPolicyName.Creator)),
              ownerAuthHeader -> Set((r, SamPolicyName.Manager))
            )
          }.void
    }
  }

  def deleteResource[R](resource: R, creatorEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit sr: SamResource[R],
    ev: Ask[IO, TraceId]
  ): IO[Unit] =
    resource match {
      case r: RuntimeSamResourceId =>
        IO(runtimes.remove((r, userEmailToAuthorization(creatorEmail))))
      case r: PersistentDiskSamResourceId =>
        IO(persistentDisks.remove((r, userEmailToAuthorization(creatorEmail))))
      case r: ProjectSamResourceId =>
        IO(billingProjects.remove((r, userEmailToAuthorization(creatorEmail))))
      case r: AppSamResourceId =>
        IO(apps.remove((r, userEmailToAuthorization(creatorEmail)))) >>
          IO(apps.remove((r, userEmailToAuthorization(projectOwnerEmail))))
    }

  override def getPetServiceAccount(authorization: Authorization, googleProject: GoogleProject)(
    implicit ev: Ask[IO, TraceId]
  ): IO[Option[WorkbenchEmail]] =
    IO.pure(Some(petSA))

  override def getUserProxy(
    userEmail: WorkbenchEmail
  )(implicit ev: Ask[IO, TraceId]): IO[Option[WorkbenchEmail]] =
    IO.pure(Some(WorkbenchEmail("PROXY_1234567890@dev.test.firecloud.org")))

  override def getCachedPetAccessToken(userEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit ev: Ask[IO, TraceId]
  ): IO[Option[String]] = IO.pure(Some("token"))

  override def getStatus(implicit ev: Ask[IO, TraceId]): IO[StatusCheckResponse] =
    IO.pure(StatusCheckResponse(true, Map.empty))

  override def getListOfResourcePermissions[R, A](resource: R, authHeader: Authorization)(
    implicit sr: SamResourceAction[R, A],
    ev: Ask[IO, TraceId]
  ): IO[List[sr.ActionCategory]] =
    sr.resourceType match {
      case SamResourceType.Project =>
        val res = billingProjects
          .get((resource.asInstanceOf[ProjectSamResourceId], authHeader))
          .map(_.toList)
          .getOrElse(List.empty)
          .asInstanceOf[List[sr.ActionCategory]]
        IO.pure(res)
      case SamResourceType.Runtime =>
        val res = runtimes
          .get((resource.asInstanceOf[RuntimeSamResourceId], authHeader))
          .map(_.toList)
          .getOrElse(List.empty)
          .asInstanceOf[List[sr.ActionCategory]]
        IO.pure(res)
      case SamResourceType.PersistentDisk =>
        val res = persistentDisks
          .get((resource.asInstanceOf[PersistentDiskSamResourceId], authHeader))
          .map(_.toList)
          .getOrElse(List.empty)
          .asInstanceOf[List[sr.ActionCategory]]
        IO.pure(res)
      case SamResourceType.App =>
        val res = apps
          .get((resource.asInstanceOf[AppSamResourceId], authHeader))
          .map(_.toList)
          .getOrElse(List.empty)
          .asInstanceOf[List[sr.ActionCategory]]
        IO.pure(res)
    }

  def getUserSubjectId(userEmail: WorkbenchEmail, googleProject: GoogleProject)(
    implicit ev: Ask[IO, TraceId]
  ): IO[Option[UserSubjectId]] = IO.pure(None)
}

object MockSamDAO {
  val petSA = WorkbenchEmail("pet-1234567890@test-project.iam.gserviceaccount.com")
  val projectOwnerEmail = WorkbenchEmail("project-owner@test.org")
  val appManagerActions = Set(AppAction.GetAppStatus, AppAction.DeleteApp)
  def userEmailToAuthorization(workbenchEmail: WorkbenchEmail): Authorization =
    Authorization(Credentials.Token(AuthScheme.Bearer, s"TokenFor${workbenchEmail}"))
}
