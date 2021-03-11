package org.broadinstitute.dsde.workbench.leonardo
package auth

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.KubernetesTestData._
import org.broadinstitute.dsde.workbench.leonardo.dao._
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId._
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchUserId}
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class SamAuthProviderSpec extends AnyFlatSpec with LeonardoTestSuite with BeforeAndAfter {
  val samAuthProviderConfigWithoutCache: SamAuthProviderConfig = SamAuthProviderConfig(false)
  val samAuthProviderConfigWithCache: SamAuthProviderConfig = SamAuthProviderConfig(
    true,
    10,
    1.minutes
  )

  val userInfo =
    UserInfo(OAuth2BearerToken(s"TokenFor${userEmail}"), WorkbenchUserId("user1"), userEmail, 0)
  val projectOwnerUserInfo =
    UserInfo(OAuth2BearerToken(s"TokenFor${MockSamDAO.projectOwnerEmail}"),
             WorkbenchUserId("owner"),
             MockSamDAO.projectOwnerEmail,
             0)
  val projectOwnerAuthHeader = MockSamDAO.userEmailToAuthorization(MockSamDAO.projectOwnerEmail)
  val authHeader = MockSamDAO.userEmailToAuthorization(userInfo.userEmail)

  var mockSam: MockSamDAO = _
  var samAuthProvider: SamAuthProvider[IO] = _
  var samAuthProviderWithCache: SamAuthProvider[IO] = _

  before {
    setUpMockSam()
    samAuthProvider = new SamAuthProvider(mockSam, samAuthProviderConfigWithoutCache, serviceAccountProvider, blocker)
    samAuthProviderWithCache =
      new SamAuthProvider(mockSam, samAuthProviderConfigWithCache, serviceAccountProvider, blocker)

  }

  "SamAuthProvider" should "check resource permissions" in {
    // positive tests
    ProjectAction.allActions.foreach { a =>
      samAuthProvider
        .hasPermission(ProjectSamResourceId(project), a, projectOwnerUserInfo)
        .unsafeRunSync() shouldBe true
    }
    RuntimeAction.allActions.foreach { a =>
      samAuthProvider.hasPermission(runtimeSamResource, a, userInfo).unsafeRunSync() shouldBe true
    }
    PersistentDiskAction.allActions.foreach { a =>
      samAuthProvider.hasPermission(diskSamResource, a, userInfo).unsafeRunSync() shouldBe true
    }
    AppAction.allActions.foreach { a =>
      samAuthProvider.hasPermission(appSamId, a, userInfo).unsafeRunSync() shouldBe true
    }
    MockSamDAO.appManagerActions.foreach { a =>
      samAuthProvider.hasPermission(appSamId, a, projectOwnerUserInfo).unsafeRunSync() shouldBe true
    }

    // negative tests
    ProjectAction.allActions.foreach { a =>
      samAuthProvider
        .hasPermission(ProjectSamResourceId(project), a, unauthorizedUserInfo)
        .unsafeRunSync() shouldBe false
    }
    RuntimeAction.allActions.foreach { a =>
      samAuthProvider.hasPermission(runtimeSamResource, a, unauthorizedUserInfo).unsafeRunSync() shouldBe false
      samAuthProvider.hasPermission(runtimeSamResource, a, projectOwnerUserInfo).unsafeRunSync() shouldBe false
    }
    PersistentDiskAction.allActions.foreach { a =>
      samAuthProvider.hasPermission(diskSamResource, a, unauthorizedUserInfo).unsafeRunSync() shouldBe false
      samAuthProvider.hasPermission(diskSamResource, a, projectOwnerUserInfo).unsafeRunSync() shouldBe false
    }
    AppAction.allActions.foreach { a =>
      samAuthProvider.hasPermission(appSamId, a, unauthorizedUserInfo).unsafeRunSync() shouldBe false
    }
  }

  it should "check resource permissions with project fallback" in {
    // positive tests
    List(
      (RuntimeAction.GetRuntimeStatus, ProjectAction.GetRuntimeStatus),
      (RuntimeAction.DeleteRuntime, ProjectAction.DeleteRuntime),
      (RuntimeAction.StopStartRuntime, ProjectAction.StopStartRuntime)
    ).foreach {
      case (runtimeAction, projectAction) =>
        // project fallback should work as the user
        samAuthProvider
          .hasPermissionWithProjectFallback(runtimeSamResource, runtimeAction, projectAction, userInfo, project)
          .unsafeRunSync() shouldBe true

        // project fallback should work as the project owner
        samAuthProvider
          .hasPermissionWithProjectFallback(runtimeSamResource,
                                            runtimeAction,
                                            projectAction,
                                            projectOwnerUserInfo,
                                            project)
          .unsafeRunSync() shouldBe true

    }
    List(
      (PersistentDiskAction.ReadPersistentDisk, ProjectAction.ReadPersistentDisk),
      (PersistentDiskAction.DeletePersistentDisk, ProjectAction.DeletePersistentDisk)
    ).foreach {
      case (diskAction, projectAction) =>
        // project fallback should work as the user
        samAuthProvider
          .hasPermissionWithProjectFallback(diskSamResource, diskAction, projectAction, userInfo, project)
          .unsafeRunSync() shouldBe true

        // project fallback should work as the project owner
        samAuthProvider
          .hasPermissionWithProjectFallback(diskSamResource, diskAction, projectAction, projectOwnerUserInfo, project)
          .unsafeRunSync() shouldBe true
    }

    // negative tests
    List(
      (RuntimeAction.GetRuntimeStatus, ProjectAction.GetRuntimeStatus),
      (RuntimeAction.DeleteRuntime, ProjectAction.DeleteRuntime),
      (RuntimeAction.StopStartRuntime, ProjectAction.StopStartRuntime)
    ).foreach {
      case (runtimeAction, projectAction) =>
        samAuthProvider
          .hasPermissionWithProjectFallback(runtimeSamResource,
                                            runtimeAction,
                                            projectAction,
                                            unauthorizedUserInfo,
                                            project)
          .unsafeRunSync() shouldBe false
    }
    List(
      (PersistentDiskAction.ReadPersistentDisk, ProjectAction.ReadPersistentDisk),
      (PersistentDiskAction.DeletePersistentDisk, ProjectAction.DeletePersistentDisk)
    ).foreach {
      case (diskAction, projectAction) =>
        samAuthProvider
          .hasPermissionWithProjectFallback(diskSamResource, diskAction, projectAction, unauthorizedUserInfo, project)
          .unsafeRunSync() shouldBe false
    }
  }

  it should "get actions" in {
    // positive tests
    samAuthProvider
      .getActions(ProjectSamResourceId(project), projectOwnerUserInfo)
      .unsafeRunSync()
      .toSet shouldBe ProjectAction.allActions
    samAuthProvider.getActions(runtimeSamResource, userInfo).unsafeRunSync().toSet shouldBe RuntimeAction.allActions
    samAuthProvider.getActions(diskSamResource, userInfo).unsafeRunSync().toSet shouldBe PersistentDiskAction.allActions
    samAuthProvider.getActions(appSamId, userInfo).unsafeRunSync().toSet shouldBe AppAction.allActions
    samAuthProvider
      .getActions(appSamId, projectOwnerUserInfo)
      .unsafeRunSync()
      .toSet shouldBe MockSamDAO.appManagerActions

    // negative tests
    samAuthProvider.getActions(ProjectSamResourceId(project), unauthorizedUserInfo).unsafeRunSync() shouldBe List.empty
    samAuthProvider.getActions(runtimeSamResource, unauthorizedUserInfo).unsafeRunSync() shouldBe List.empty
    samAuthProvider.getActions(runtimeSamResource, projectOwnerUserInfo).unsafeRunSync() shouldBe List.empty
    samAuthProvider.getActions(diskSamResource, unauthorizedUserInfo).unsafeRunSync() shouldBe List.empty
    samAuthProvider.getActions(diskSamResource, projectOwnerUserInfo).unsafeRunSync() shouldBe List.empty
    samAuthProvider.getActions(appSamId, unauthorizedUserInfo).unsafeRunSync() shouldBe List.empty
  }

  it should "get actions with project fallback" in {
    // positive tests
    samAuthProvider
      .getActionsWithProjectFallback(runtimeSamResource, project, userInfo)
      .unsafeRunSync()
      .leftMap(_.toSet) shouldBe (RuntimeAction.allActions, List.empty)
    samAuthProvider
      .getActionsWithProjectFallback(runtimeSamResource, project, projectOwnerUserInfo)
      .unsafeRunSync()
      .map(_.toSet) shouldBe (List.empty, ProjectAction.allActions)
    samAuthProvider
      .getActionsWithProjectFallback(diskSamResource, project, userInfo)
      .unsafeRunSync()
      .leftMap(_.toSet) shouldBe (PersistentDiskAction.allActions, List.empty)
    samAuthProvider
      .getActionsWithProjectFallback(diskSamResource, project, projectOwnerUserInfo)
      .unsafeRunSync()
      .map(_.toSet) shouldBe (List.empty, ProjectAction.allActions)

    // negative tests
    samAuthProvider
      .getActionsWithProjectFallback(runtimeSamResource, project, unauthorizedUserInfo)
      .unsafeRunSync() shouldBe (List.empty, List.empty)
    samAuthProvider
      .getActionsWithProjectFallback(diskSamResource, project, unauthorizedUserInfo)
      .unsafeRunSync() shouldBe (List.empty, List.empty)
  }

  it should "cache hasPermission results" in {
    // cache should be empty
    samAuthProviderWithCache.authCache.size shouldBe 0

    // these actions should be cached
    List(ProjectAction.GetRuntimeStatus, ProjectAction.ReadPersistentDisk).foreach { a =>
      samAuthProviderWithCache
        .hasPermission(ProjectSamResourceId(project), a, projectOwnerUserInfo)
        .unsafeRunSync() shouldBe true
    }
    List(RuntimeAction.GetRuntimeStatus, RuntimeAction.ConnectToRuntime).foreach { a =>
      samAuthProviderWithCache
        .hasPermission(runtimeSamResource, a, userInfo)
        .unsafeRunSync() shouldBe true
      samAuthProviderWithCache
        .hasPermission(runtimeSamResource, a, userInfo)
        .unsafeRunSync() shouldBe true
    }
    List(PersistentDiskAction.ReadPersistentDisk).foreach { a =>
      samAuthProviderWithCache
        .hasPermission(diskSamResource, a, userInfo)
        .unsafeRunSync() shouldBe true
    }
    List(AppAction.ConnectToApp, AppAction.GetAppStatus).foreach { a =>
      samAuthProviderWithCache.hasPermission(appSamId, a, userInfo).unsafeRunSync() shouldBe true
    }

    // these actions should not be cached
    List(
      ProjectAction.CreateApp,
      ProjectAction.CreatePersistentDisk,
      ProjectAction.CreateRuntime,
      ProjectAction.DeletePersistentDisk,
      ProjectAction.DeleteRuntime,
      ProjectAction.StopStartRuntime
    ).foreach { a =>
      samAuthProviderWithCache
        .hasPermission(ProjectSamResourceId(project), a, projectOwnerUserInfo)
        .unsafeRunSync() shouldBe true
    }
    List(RuntimeAction.ModifyRuntime, RuntimeAction.DeleteRuntime, RuntimeAction.StopStartRuntime).foreach { a =>
      samAuthProviderWithCache
        .hasPermission(runtimeSamResource, a, userInfo)
        .unsafeRunSync() shouldBe true
    }
    List(PersistentDiskAction.DeletePersistentDisk,
         PersistentDiskAction.ModifyPersistentDisk,
         PersistentDiskAction.AttachPersistentDisk).foreach { a =>
      samAuthProviderWithCache
        .hasPermission(diskSamResource, a, userInfo)
        .unsafeRunSync() shouldBe true
    }
    List(AppAction.DeleteApp, AppAction.UpdateApp).foreach { a =>
      samAuthProviderWithCache
        .hasPermission(appSamId, a, userInfo)
        .unsafeRunSync() shouldBe true
    }

    // check cache
    samAuthProviderWithCache.authCache.size shouldBe 7
    samAuthProviderWithCache.authCache.asMap().asScala.keySet shouldBe
      Set(
        AuthCacheKey(SamResourceType.Project,
                     project.value,
                     projectOwnerAuthHeader,
                     ProjectAction.GetRuntimeStatus.asString),
        AuthCacheKey(SamResourceType.Project,
                     project.value,
                     projectOwnerAuthHeader,
                     ProjectAction.ReadPersistentDisk.asString),
        AuthCacheKey(SamResourceType.Runtime,
                     runtimeSamResource.resourceId,
                     authHeader,
                     RuntimeAction.ConnectToRuntime.asString),
        AuthCacheKey(SamResourceType.Runtime,
                     runtimeSamResource.resourceId,
                     authHeader,
                     RuntimeAction.GetRuntimeStatus.asString),
        AuthCacheKey(SamResourceType.PersistentDisk,
                     diskSamResource.resourceId,
                     authHeader,
                     PersistentDiskAction.ReadPersistentDisk.asString),
        AuthCacheKey(SamResourceType.App, appSamId.resourceId, authHeader, AppAction.GetAppStatus.asString),
        AuthCacheKey(SamResourceType.App, appSamId.resourceId, authHeader, AppAction.ConnectToApp.asString)
      )
    samAuthProviderWithCache.authCache.asMap().asScala.values.toSet shouldBe Set(true)
  }

  it should "create a resource" in {
    val newRuntime = RuntimeSamResourceId("new_runtime")
    samAuthProvider.notifyResourceCreated(newRuntime, userEmail, project).unsafeRunSync()
    mockSam.runtimes.get((newRuntime, authHeader)) shouldBe Some(
      RuntimeAction.allActions
    )

    val newDisk = PersistentDiskSamResourceId("new_disk")
    samAuthProvider.notifyResourceCreated(newDisk, userEmail, project).unsafeRunSync()
    mockSam.persistentDisks.get((newDisk, authHeader)) shouldBe Some(
      PersistentDiskAction.allActions
    )

    val newApp = AppSamResourceId("new_app")
    samAuthProvider.notifyResourceCreated(newApp, userEmail, project).unsafeRunSync()
    mockSam.apps.get((newApp, authHeader)) shouldBe Some(
      AppAction.allActions
    )
    mockSam.apps.get((newApp, projectOwnerAuthHeader)) shouldBe Some(
      MockSamDAO.appManagerActions
    )
  }

  it should "delete a resource" in {
    samAuthProvider.notifyResourceDeleted(runtimeSamResource, userEmail, project).unsafeRunSync()
    mockSam.runtimes.get((runtimeSamResource, authHeader)) shouldBe None

    samAuthProvider.notifyResourceDeleted(diskSamResource, userEmail, project).unsafeRunSync()
    mockSam.persistentDisks.get((diskSamResource, authHeader)) shouldBe None

    samAuthProvider.notifyResourceDeleted(appSamId, userEmail, project).unsafeRunSync()
    mockSam.apps.get((appSamId, authHeader)) shouldBe None
    mockSam.apps.get((appSamId, projectOwnerAuthHeader)) shouldBe None
  }

  it should "filter user visible resources" in {
    // positive tests
    val newRuntime = RuntimeSamResourceId("new_runtime")
    mockSam.createResource(newRuntime, userEmail2, project).unsafeRunSync()
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(runtimeSamResource, newRuntime), userInfo)
      .unsafeRunSync() shouldBe List(
      runtimeSamResource
    )

    val newDisk = PersistentDiskSamResourceId("new_disk")
    mockSam.createResource(newDisk, userEmail2, project).unsafeRunSync()
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(diskSamResource, newDisk), userInfo)
      .unsafeRunSync() shouldBe List(
      diskSamResource
    )

    val newApp = AppSamResourceId("new_app")
    mockSam.createResourceV2(newApp, userEmail2, project).unsafeRunSync()
    println(mockSam.appCreators)
    samAuthProvider.filterUserVisible(NonEmptyList.of(appSamId, newApp), userInfo).unsafeRunSync() shouldBe List(
      appSamId
    )
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(appSamId, newApp), projectOwnerUserInfo)
      .unsafeRunSync() shouldBe List(
      appSamId,
      newApp
    )

    // negative tests
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(runtimeSamResource, newRuntime), unauthorizedUserInfo)
      .unsafeRunSync() shouldBe List.empty
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(runtimeSamResource, newRuntime), projectOwnerUserInfo)
      .unsafeRunSync() shouldBe List.empty
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(diskSamResource, newDisk), unauthorizedUserInfo)
      .unsafeRunSync() shouldBe List.empty
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(diskSamResource, newDisk), projectOwnerUserInfo)
      .unsafeRunSync() shouldBe List.empty
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(appSamId, newApp), unauthorizedUserInfo)
      .unsafeRunSync() shouldBe List.empty
  }

  it should "filter user visible resources with project fallback" in {
    // positive tests
    val newRuntime = RuntimeSamResourceId("new_runtime")
    mockSam.createResource(newRuntime, userEmail2, project).unsafeRunSync()
    samAuthProvider
      .filterUserVisibleWithProjectFallback(NonEmptyList.of((project, runtimeSamResource), (project, newRuntime)),
                                            userInfo)
      .unsafeRunSync() shouldBe List((project, runtimeSamResource))
    samAuthProvider
      .filterUserVisibleWithProjectFallback(NonEmptyList.of((project, runtimeSamResource), (project, newRuntime)),
                                            projectOwnerUserInfo)
      .unsafeRunSync() shouldBe List((project, runtimeSamResource), (project, newRuntime))

    val newDisk = PersistentDiskSamResourceId("new_disk")
    mockSam.createResource(newDisk, userEmail2, project).unsafeRunSync()
    samAuthProvider
      .filterUserVisibleWithProjectFallback(NonEmptyList.of((project, diskSamResource), (project, newDisk)), userInfo)
      .unsafeRunSync() shouldBe List((project, diskSamResource))
    samAuthProvider
      .filterUserVisibleWithProjectFallback(NonEmptyList.of((project, diskSamResource), (project, newDisk)),
                                            projectOwnerUserInfo)
      .unsafeRunSync() shouldBe List((project, diskSamResource), (project, newDisk))

    // negative tests
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(runtimeSamResource, newRuntime), unauthorizedUserInfo)
      .unsafeRunSync() shouldBe List.empty
    samAuthProvider
      .filterUserVisible(NonEmptyList.of(diskSamResource, newDisk), unauthorizedUserInfo)
      .unsafeRunSync() shouldBe List.empty
  }

  private def setUpMockSam(): Unit = {
    mockSam = new MockSamDAO
    // set up mock sam with a project, runtime, disk, and app
    mockSam.createResource(ProjectSamResourceId(project), MockSamDAO.projectOwnerEmail, project).unsafeRunSync()
    mockSam.billingProjects.get(
      (ProjectSamResourceId(project), projectOwnerAuthHeader)
    ) shouldBe Some(
      ProjectAction.allActions
    )
    mockSam.createResource(runtimeSamResource, userEmail, project).unsafeRunSync()
    mockSam.runtimes.get((runtimeSamResource, authHeader)) shouldBe Some(
      RuntimeAction.allActions
    )
    mockSam.createResource(diskSamResource, userEmail, project).unsafeRunSync()
    mockSam.persistentDisks.get((diskSamResource, authHeader)) shouldBe Some(
      PersistentDiskAction.allActions
    )
    mockSam.createResourceV2(KubernetesTestData.appSamId, userEmail, project).unsafeRunSync()
    mockSam.apps.get((appSamId, authHeader)) shouldBe Some(
      AppAction.allActions
    )
    mockSam.apps.get((appSamId, projectOwnerAuthHeader)) shouldBe Some(
      MockSamDAO.appManagerActions
    )
  }
}
