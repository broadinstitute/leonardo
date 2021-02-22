package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import cats.effect.IO
import cats.mtl.Ask
import KubernetesTestData._
import org.broadinstitute.dsde.workbench.leonardo.http.{
  BatchNodepoolCreateRequest,
  CreateAppRequest,
  DeleteAppRequest,
  GetAppResponse,
  ListAppResponse
}
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

class MockAppService extends AppService[IO] {
  override def createApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName, req: CreateAppRequest)(
    implicit as: Ask[IO, AppContext]
  ): IO[Unit] =
    IO.unit

  override def getApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: Ask[IO, AppContext]
  ): IO[GetAppResponse] =
    IO.pure(getAppResponse)

  override def listApp(userInfo: UserInfo, googleProject: Option[GoogleProject], params: Map[String, String])(
    implicit as: Ask[IO, AppContext]
  ): IO[Vector[ListAppResponse]] =
    IO.pure(listAppResponse)

  override def deleteApp(request: DeleteAppRequest)(
    implicit as: Ask[IO, AppContext]
  ): IO[Unit] =
    IO.unit

  override def batchNodepoolCreate(userInfo: UserInfo, googleProject: GoogleProject, req: BatchNodepoolCreateRequest)(
    implicit ev: Ask[IO, AppContext]
  ): IO[Unit] = IO.unit

  override def stopApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: Ask[IO, AppContext]
  ): IO[Unit] = IO.unit

  override def startApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: Ask[IO, AppContext]
  ): IO[Unit] = IO.unit
}

object MockAppService extends MockAppService
