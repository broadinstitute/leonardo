package org.broadinstitute.dsde.workbench.leonardo
package db

import java.sql.SQLIntegrityConstraintViolationException
import java.time.Instant

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import slick.lifted.Tag
import LeoProfile.api._
import LeoProfile.mappedColumnImplicits._
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.ServiceAccountName
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId.AppSamResourceId
import org.broadinstitute.dsde.workbench.leonardo.db.LeoProfile.{dummyDate, unmarshalDestroyedDate}
import org.broadinstitute.dsde.workbench.leonardo.model.LeoException
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsp.Release
import org.http4s.Uri

import scala.concurrent.ExecutionContext

final case class AppRecord(id: AppId,
                           nodepoolId: NodepoolLeoId,
                           appType: AppType,
                           appName: AppName,
                           status: AppStatus,
                           chart: Chart,
                           release: Release,
                           samResourceId: AppSamResourceId,
                           googleServiceAccount: WorkbenchEmail,
                           kubernetesServiceAccount: Option[ServiceAccountName],
                           creator: WorkbenchEmail,
                           createdDate: Instant,
                           destroyedDate: Instant,
                           dateAccessed: Instant,
                           namespaceId: NamespaceId,
                           diskId: Option[DiskId],
                           customEnvironmentVariables: Option[Map[String, String]],
                           descriptorPath: Option[Uri],
                           extraArgs: Option[List[String]])

class AppTable(tag: Tag) extends Table[AppRecord](tag, "APP") {
  //unique (appName, destroyedDate)
  def id = column[AppId]("id", O.PrimaryKey, O.AutoInc)
  def nodepoolId = column[NodepoolLeoId]("nodepoolId")
  def appType = column[AppType]("appType", O.Length(254))
  def appName = column[AppName]("appName", O.Length(254))
  def status = column[AppStatus]("status", O.Length(254))
  def chart = column[Chart]("chart", O.Length(254))
  def release = column[Release]("release", O.Length(254))
  def samResourceId = column[AppSamResourceId]("samResourceId", O.Length(254))
  def googleServiceAccount = column[WorkbenchEmail]("googleServiceAccount", O.Length(254))
  def kubernetesServiceAccount = column[Option[ServiceAccountName]]("kubernetesServiceAccount", O.Length(254))
  def creator = column[WorkbenchEmail]("creator", O.Length(254))
  def createdDate = column[Instant]("createdDate", O.SqlType("TIMESTAMP(6)"))
  def destroyedDate = column[Instant]("destroyedDate", O.SqlType("TIMESTAMP(6)"))
  def dateAccessed = column[Instant]("dateAccessed", O.SqlType("TIMESTAMP(6)"))
  def namespaceId = column[NamespaceId]("namespaceId", O.Length(254))
  def diskId = column[Option[DiskId]]("diskId", O.Length(254))
  def customEnvironmentVariables = column[Option[Map[String, String]]]("customEnvironmentVariables")
  def descriptorPath = column[Option[Uri]]("descriptorPath", O.Length(1024))
  def extraArgs = column[Option[List[String]]]("extraArgs")

  def * =
    (
      id,
      nodepoolId,
      appType,
      appName,
      status,
      chart,
      release,
      samResourceId,
      googleServiceAccount,
      kubernetesServiceAccount,
      creator,
      createdDate,
      destroyedDate,
      dateAccessed,
      namespaceId,
      diskId,
      customEnvironmentVariables,
      descriptorPath,
      extraArgs
    ) <> (AppRecord.tupled, AppRecord.unapply)
}

object appQuery extends TableQuery(new AppTable(_)) {
  def unmarshalApp(app: AppRecord,
                   services: List[KubernetesService],
                   labels: LabelMap,
                   namespace: Namespace,
                   disk: Option[PersistentDisk],
                   errors: List[AppError]): App =
    App(
      app.id,
      app.nodepoolId,
      app.appType,
      app.appName,
      app.status,
      app.chart,
      app.release,
      app.samResourceId,
      app.googleServiceAccount,
      AuditInfo(
        app.creator,
        app.createdDate,
        unmarshalDestroyedDate(app.destroyedDate),
        app.dateAccessed
      ),
      labels,
      AppResources(
        namespace,
        disk,
        services,
        app.kubernetesServiceAccount
      ),
      errors,
      app.customEnvironmentVariables.getOrElse(Map.empty),
      app.descriptorPath,
      app.extraArgs.getOrElse(List.empty)
    )

  def save(saveApp: SaveApp)(implicit ec: ExecutionContext): DBIO[App] = {
    val namespaceName = saveApp.app.appResources.namespace.name
    for {
      nodepool <- nodepoolQuery
        .getMinimalById(saveApp.app.nodepoolId)
        .flatMap(nodepoolOpt =>
          nodepoolOpt.fold[DBIO[Nodepool]](
            DBIO.failed(
              new SQLIntegrityConstraintViolationException(
                "Apps must be saved with a nodepool ID that exists in the DB. FK_APP_NODEPOOL_ID"
              )
            )
          )(DBIO.successful(_))
        )

      cluster <- kubernetesClusterQuery
        .getMinimalClusterById(nodepool.clusterId)
        .flatMap(clusterOpt =>
          clusterOpt.fold[DBIO[KubernetesCluster]](
            DBIO.failed(
              new SQLIntegrityConstraintViolationException(
                "Apps must be saved with a nodepool that has a cluster ID that exists in the DB. FK_NODEPOOL_CLUSTER_ID"
              )
            )
          )(DBIO.successful(_))
        )

      //here, we enforce uniqueness on (AppName, GoogleProject) for active apps
      getAppResult <- KubernetesServiceDbQueries.getActiveFullAppByName(cluster.googleProject, saveApp.app.appName)
      _ <- getAppResult.fold[DBIO[Unit]](DBIO.successful(()))(appResult =>
        DBIO.failed(AppExistsForProjectException(appResult.app.appName, cluster.googleProject))
      )

      namespaceId <- namespaceQuery.save(nodepool.clusterId, namespaceName)
      namespace = saveApp.app.appResources.namespace.copy(id = namespaceId)

      diskOpt = saveApp.app.appResources.disk

      ksaOpt = saveApp.app.appResources.kubernetesServiceAccountName

      record = AppRecord(
        AppId(-1),
        saveApp.app.nodepoolId,
        saveApp.app.appType,
        saveApp.app.appName,
        saveApp.app.status,
        saveApp.app.chart,
        saveApp.app.release,
        saveApp.app.samResourceId,
        saveApp.app.googleServiceAccount,
        ksaOpt,
        saveApp.app.auditInfo.creator,
        saveApp.app.auditInfo.createdDate,
        saveApp.app.auditInfo.destroyedDate.getOrElse(dummyDate),
        saveApp.app.auditInfo.dateAccessed,
        namespaceId,
        diskOpt.map(_.id),
        if (saveApp.app.customEnvironmentVariables.isEmpty) None else Some(saveApp.app.customEnvironmentVariables),
        saveApp.app.descriptorPath,
        if (saveApp.app.extraArgs.isEmpty) None else Some(saveApp.app.extraArgs)
      )
      appId <- appQuery returning appQuery.map(_.id) += record
      _ <- labelQuery.saveAllForResource(appId.id, LabelResourceType.App, saveApp.app.labels)
      services <- serviceQuery.saveAllForApp(appId, saveApp.app.appResources.services)
    } yield saveApp.app.copy(id = appId, appResources = AppResources(namespace, diskOpt, services, ksaOpt))
  }

  def updateStatus(id: AppId, status: AppStatus): DBIO[Int] =
    getByIdQuery(id)
      .map(_.status)
      .update(status)

  def updateChart(id: AppId, chart: Chart): DBIO[Int] =
    getByIdQuery(id)
      .map(_.chart)
      .update(chart)

  def markPendingDeletion(id: AppId): DBIO[Int] =
    updateStatus(id, AppStatus.Deleting)

  def markAsDeleted(id: AppId, now: Instant): DBIO[Int] =
    getByIdQuery(id)
      .map(a => (a.status, a.destroyedDate, a.diskId))
      .update((AppStatus.Deleted, now, None))

  def isDiskAttached(diskId: DiskId)(implicit ec: ExecutionContext): DBIO[Boolean] =
    appQuery.filter(x => x.diskId.isDefined && x.diskId === diskId).length.result.map(_ > 0)

  def detachDisk(id: AppId): DBIO[Int] =
    getByIdQuery(id)
      .map(_.diskId)
      .update(None)

  def getDiskId(id: AppId)(implicit ec: ExecutionContext): DBIO[Option[DiskId]] =
    getByIdQuery(id).result.map(_.headOption.flatMap(_.diskId))

  def updateKubernetesServiceAccount(id: AppId, ksa: ServiceAccountName): DBIO[Int] =
    getByIdQuery(id)
      .map(_.kubernetesServiceAccount)
      .update(Option(ksa))

  private[db] def getByIdQuery(id: AppId) =
    appQuery.filter(_.id === id)

  private[db] def findActiveByNameQuery(
    appName: AppName
  ): Query[AppTable, AppRecord, Seq] =
    nonDeletedAppQuery.filter(_.appName === appName)

  private[db] def nonDeletedAppQuery: Query[AppTable, AppRecord, Seq] =
    appQuery
      .filter(_.status =!= (AppStatus.Deleted: AppStatus))
      .filter(_.destroyedDate === dummyDate)
}

case class SaveApp(app: App)
case class AppExistsForProjectException(appName: AppName, googleProject: GoogleProject)
    extends LeoException(
      s"An app with name ${appName} already exists for the project ${googleProject}.",
      StatusCodes.Conflict,
      traceId = None
    )
