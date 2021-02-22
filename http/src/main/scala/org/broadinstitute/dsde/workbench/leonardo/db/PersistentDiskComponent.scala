package org.broadinstitute.dsde.workbench.leonardo
package db

import java.time.Instant
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.google2.{DiskName, ZoneName}
import org.broadinstitute.dsde.workbench.leonardo.db.LeoProfile.api._
import org.broadinstitute.dsde.workbench.leonardo.db.LeoProfile.mappedColumnImplicits._
import org.broadinstitute.dsde.workbench.leonardo.db.LeoProfile.{dummyDate, unmarshalDestroyedDate}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import scala.concurrent.ExecutionContext

final case class PersistentDiskRecord(id: DiskId,
                                      googleProject: GoogleProject,
                                      zone: ZoneName,
                                      name: DiskName,
                                      googleId: Option[GoogleId],
                                      serviceAccount: WorkbenchEmail,
                                      samResource: PersistentDiskSamResourceId,
                                      status: DiskStatus,
                                      creator: WorkbenchEmail,
                                      createdDate: Instant,
                                      destroyedDate: Instant,
                                      dateAccessed: Instant,
                                      size: DiskSize,
                                      diskType: DiskType,
                                      blockSize: BlockSize,
                                      formattedBy: Option[FormattedBy],
                                      galaxyRestore: Option[GalaxyRestore])

class PersistentDiskTable(tag: Tag) extends Table[PersistentDiskRecord](tag, "PERSISTENT_DISK") {
  def id = column[DiskId]("id", O.PrimaryKey, O.AutoInc)
  def googleProject = column[GoogleProject]("googleProject", O.Length(255))
  def zone = column[ZoneName]("zone", O.Length(255))
  def name = column[DiskName]("name", O.Length(255))
  def googleId = column[Option[GoogleId]]("googleId", O.Length(255))
  def serviceAccount = column[WorkbenchEmail]("serviceAccount", O.Length(255))
  def samResourceId = column[PersistentDiskSamResourceId]("samResourceId", O.Length(255))
  def status = column[DiskStatus]("status", O.Length(255))
  def creator = column[WorkbenchEmail]("creator", O.Length(255))
  def createdDate = column[Instant]("createdDate", O.SqlType("TIMESTAMP(6)"))
  def destroyedDate = column[Instant]("destroyedDate", O.SqlType("TIMESTAMP(6)"))
  def dateAccessed = column[Instant]("dateAccessed", O.SqlType("TIMESTAMP(6)"))
  def size = column[DiskSize]("sizeGb")
  def diskType = column[DiskType]("type", O.Length(255))
  def blockSize = column[BlockSize]("blockSizeBytes")
  def formattedBy = column[Option[FormattedBy]]("formattedBy", O.Length(255))
  def galaxyPvcId = column[Option[PvcId]]("galaxyPvcId", O.Length(254))
  def cvmfsPvcId = column[Option[PvcId]]("cvmfsPvcId", O.Length(254))
  def lastUsedBy = column[Option[AppId]]("lastUsedBy")

  override def * =
    (id,
     googleProject,
     zone,
     name,
     googleId,
     serviceAccount,
     samResourceId,
     status,
     creator,
     createdDate,
     destroyedDate,
     dateAccessed,
     size,
     diskType,
     blockSize,
     formattedBy,
     (galaxyPvcId, cvmfsPvcId, lastUsedBy)) <> ({
      case (id,
            googleProject,
            zone,
            name,
            googleId,
            serviceAccount,
            samResourceId,
            status,
            creator,
            createdDate,
            destroyedDate,
            dateAccessed,
            size,
            diskType,
            blockSize,
            formattedBy,
            (galaxyPvcId, cvmfsPvcId, lastUsedBy)) =>
        PersistentDiskRecord(
          id,
          googleProject,
          zone,
          name,
          googleId,
          serviceAccount,
          samResourceId,
          status,
          creator,
          createdDate,
          destroyedDate,
          dateAccessed,
          size,
          diskType,
          blockSize,
          formattedBy,
          (galaxyPvcId, cvmfsPvcId, lastUsedBy).mapN((gp, cp, l) => GalaxyRestore(gp, cp, l))
        )
    }, { record: PersistentDiskRecord =>
      Some(
        record.id,
        record.googleProject,
        record.zone,
        record.name,
        record.googleId,
        record.serviceAccount,
        record.samResource,
        record.status,
        record.creator,
        record.createdDate,
        record.destroyedDate,
        record.dateAccessed,
        record.size,
        record.diskType,
        record.blockSize,
        record.formattedBy,
        (record.galaxyRestore.map(_.galaxyPvcId),
         record.galaxyRestore.map(_.cvmfsPvcId),
         record.galaxyRestore.map(_.lastUsedBy))
      )
    })
}

object persistentDiskQuery {
  val tableQuery = TableQuery[PersistentDiskTable]

  private[db] def findByIdQuery(id: DiskId) = tableQuery.filter(_.id === id)

  private[db] def findActiveByNameQuery(googleProject: GoogleProject, name: DiskName) =
    tableQuery
      .filter(_.googleProject === googleProject)
      .filter(_.name === name)
      .filter(_.destroyedDate === dummyDate)

  private[db] def findByNameQuery(googleProject: GoogleProject, name: DiskName) =
    tableQuery
      .filter(_.googleProject === googleProject)
      .filter(_.name === name)

  private[db] def joinLabelQuery(baseQuery: Query[PersistentDiskTable, PersistentDiskRecord, Seq]) =
    for {
      (disk, label) <- baseQuery joinLeft labelQuery on {
        case (d, lbl) =>
          lbl.resourceId.mapTo[DiskId] === d.id && lbl.resourceType === LabelResourceType.persistentDisk
      }
    } yield (disk, label)

  def updateGalaxyDiskRestore(id: DiskId, galaxyDiskRestore: GalaxyRestore): DBIO[Int] =
    findByIdQuery(id)
      .map(x => (x.galaxyPvcId, x.cvmfsPvcId, x.lastUsedBy))
      .update(
        (Some(galaxyDiskRestore.galaxyPvcId), Some(galaxyDiskRestore.cvmfsPvcId), Some(galaxyDiskRestore.lastUsedBy))
      )

  def updateLastUsedBy(id: DiskId, lastUsedBy: AppId): DBIO[Int] =
    findByIdQuery(id)
      .map(x => (x.lastUsedBy))
      .update(
        (Some(lastUsedBy))
      )

  def getGalaxyDiskRestore(id: DiskId)(implicit ec: ExecutionContext): DBIO[Option[GalaxyRestore]] =
    findByIdQuery(id).result
      .map(_.headOption.flatMap(_.galaxyRestore))

  def save(disk: PersistentDisk)(implicit ec: ExecutionContext): DBIO[PersistentDisk] =
    for {
      diskId <- (tableQuery returning tableQuery.map(_.id)) += marshalPersistentDisk(disk)
      _ <- labelQuery.saveAllForResource(diskId.value, LabelResourceType.PersistentDisk, disk.labels)
    } yield disk.copy(diskId)

  def getById(id: DiskId)(implicit ec: ExecutionContext): DBIO[Option[PersistentDisk]] =
    joinLabelQuery(findByIdQuery(id)).result.map(aggregateLabels).map(_.headOption)

  def getStatus(id: DiskId)(implicit ec: ExecutionContext): DBIO[Option[DiskStatus]] =
    getPersistentDiskRecord(id).map(_.map(_.status))

  def getPersistentDiskRecord(id: DiskId): DBIO[Option[PersistentDiskRecord]] =
    findByIdQuery(id).result.headOption

  def getActiveByName(googleProject: GoogleProject,
                      name: DiskName)(implicit ec: ExecutionContext): DBIO[Option[PersistentDisk]] =
    joinLabelQuery(findActiveByNameQuery(googleProject, name)).result.map(aggregateLabels).map(_.headOption)

  def updateStatus(id: DiskId, newStatus: DiskStatus, dateAccessed: Instant) =
    findByIdQuery(id).map(d => (d.status, d.dateAccessed)).update((newStatus, dateAccessed))

  def updateStatusAndIsFormatted(id: DiskId, newStatus: DiskStatus, formattedBy: FormattedBy, dateAccessed: Instant) =
    findByIdQuery(id)
      .map(d => (d.status, d.formattedBy, d.dateAccessed))
      .update((newStatus, Some(formattedBy), dateAccessed))

  def markPendingDeletion(id: DiskId, dateAccessed: Instant): DBIO[Int] =
    findByIdQuery(id)
      .map(d => (d.status, d.dateAccessed))
      .update((DiskStatus.Deleting, dateAccessed))

  def nullifyDiskIds = persistentDiskQuery.tableQuery.map(x => (x.lastUsedBy)).update(None)

  def delete(id: DiskId, destroyedDate: Instant) =
    findByIdQuery(id)
      .map(d => (d.status, d.destroyedDate, d.dateAccessed))
      .update((DiskStatus.Deleted, destroyedDate, destroyedDate))

  def updateGoogleId(id: DiskId, googleId: GoogleId, dateAccessed: Instant) =
    findByIdQuery(id).map(d => (d.googleId, d.dateAccessed)).update((Some(googleId), dateAccessed))

  def updateSize(id: DiskId, newSize: DiskSize, dateAccessed: Instant) =
    findByIdQuery(id).map(d => (d.size, d.dateAccessed)).update((newSize, dateAccessed))

  def getFormattedBy(id: DiskId)(implicit ec: ExecutionContext): DBIO[Option[FormattedBy]] =
    findByIdQuery(id).map(_.formattedBy).result.headOption.map(_.flatten)

  def isDiskAttached(diskId: DiskId)(implicit ec: ExecutionContext): DBIO[Boolean] =
    for {
      formattedBy <- getFormattedBy(diskId)
      r <- formattedBy match {
        case None =>
          for {
            isAttachedToRuntime <- RuntimeConfigQueries.isDiskAttached(diskId)
            isAttached <- if (isAttachedToRuntime) DBIO.successful(true) else appQuery.isDiskAttached(diskId)
          } yield isAttached
        case Some(FormattedBy.Galaxy | FormattedBy.Custom) =>
          appQuery.isDiskAttached(diskId)
        case Some(FormattedBy.GCE) =>
          RuntimeConfigQueries.isDiskAttached(diskId)
      }
    } yield r

  private[db] def marshalPersistentDisk(disk: PersistentDisk): PersistentDiskRecord =
    PersistentDiskRecord(
      disk.id,
      disk.googleProject,
      disk.zone,
      disk.name,
      disk.googleId,
      disk.serviceAccount,
      disk.samResource,
      disk.status,
      disk.auditInfo.creator,
      disk.auditInfo.createdDate,
      disk.auditInfo.destroyedDate.getOrElse(dummyDate),
      disk.auditInfo.dateAccessed,
      disk.size,
      disk.diskType,
      disk.blockSize,
      disk.formattedBy,
      disk.galaxyRestore
    )

  private[db] def aggregateLabels(
    recs: Seq[(PersistentDiskRecord, Option[LabelRecord])]
  ): Seq[PersistentDisk] = {
    val pdLabelMap: Map[PersistentDiskRecord, Map[String, String]] =
      recs.toList.foldMap {
        case (rec, labelRecOpt) =>
          val labelMap = labelRecOpt.map(lblRec => Map(lblRec.key -> lblRec.value)).getOrElse(Map.empty)
          Map(rec -> labelMap)
      }

    pdLabelMap.toList.map {
      case (rec, labels) =>
        unmarshalPersistentDisk(rec, labels)
    }
  }

  private[db] def unmarshalPersistentDisk(rec: PersistentDiskRecord, labels: LabelMap): PersistentDisk =
    PersistentDisk(
      rec.id,
      rec.googleProject,
      rec.zone,
      rec.name,
      rec.googleId,
      rec.serviceAccount,
      rec.samResource,
      rec.status,
      AuditInfo(
        rec.creator,
        rec.createdDate,
        unmarshalDestroyedDate(rec.destroyedDate),
        rec.dateAccessed
      ),
      rec.size,
      rec.diskType,
      rec.blockSize,
      rec.formattedBy,
      rec.galaxyRestore,
      labels
    )
}
