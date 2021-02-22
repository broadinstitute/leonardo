package org.broadinstitute.dsde.workbench.leonardo.http
package service

import cats.mtl.Ask
import org.broadinstitute.dsde.workbench.leonardo.{AppContext, AppName}
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

trait AppService[F[_]] {
  def batchNodepoolCreate(userInfo: UserInfo, googleProject: GoogleProject, req: BatchNodepoolCreateRequest)(
    implicit ev: Ask[F, AppContext]
  ): F[Unit]

  def createApp(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    appName: AppName,
    req: CreateAppRequest
  )(implicit as: Ask[F, AppContext]): F[Unit]

  def getApp(
    userInfo: UserInfo,
    googleProject: GoogleProject,
    appName: AppName
  )(implicit as: Ask[F, AppContext]): F[GetAppResponse]

  def listApp(
    userInfo: UserInfo,
    googleProject: Option[GoogleProject],
    params: Map[String, String]
  )(implicit as: Ask[F, AppContext]): F[Vector[ListAppResponse]]

  def deleteApp(request: DeleteAppRequest)(
    implicit as: Ask[F, AppContext]
  ): F[Unit]

  def stopApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: Ask[F, AppContext]
  ): F[Unit]

  def startApp(userInfo: UserInfo, googleProject: GoogleProject, appName: AppName)(
    implicit as: Ask[F, AppContext]
  ): F[Unit]
}
