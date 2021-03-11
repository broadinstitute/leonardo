package org.broadinstitute.dsde.workbench.leonardo
package http

import org.broadinstitute.dsde.workbench.google2.{DiskName, ZoneName}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId.PersistentDiskSamResourceId

final case class CreateDiskRequest(labels: LabelMap,
                                   size: Option[DiskSize],
                                   diskType: Option[DiskType],
                                   blockSize: Option[BlockSize])

object CreateDiskRequest {
  def fromDiskConfigRequest(create: PersistentDiskRequest): CreateDiskRequest =
    CreateDiskRequest(create.labels, create.size, create.diskType, None)
}

final case class PersistentDiskRequest(name: DiskName,
                                       size: Option[DiskSize],
                                       diskType: Option[DiskType],
                                       labels: LabelMap)

final case class DiskConfig(name: DiskName, size: DiskSize, diskType: DiskType, blockSize: BlockSize)
object DiskConfig {
  def fromPersistentDisk(disk: PersistentDisk): DiskConfig =
    DiskConfig(disk.name, disk.size, disk.diskType, disk.blockSize)
}

final case class ListPersistentDiskResponse(id: DiskId,
                                            googleProject: GoogleProject,
                                            zone: ZoneName,
                                            name: DiskName,
                                            status: DiskStatus,
                                            auditInfo: AuditInfo,
                                            size: DiskSize,
                                            diskType: DiskType,
                                            blockSize: BlockSize)

final case class GetPersistentDiskResponse(id: DiskId,
                                           googleProject: GoogleProject,
                                           zone: ZoneName,
                                           name: DiskName,
                                           googleId: Option[GoogleId],
                                           serviceAccount: WorkbenchEmail,
                                           samResource: PersistentDiskSamResourceId,
                                           status: DiskStatus,
                                           auditInfo: AuditInfo,
                                           size: DiskSize,
                                           diskType: DiskType,
                                           blockSize: BlockSize,
                                           labels: LabelMap)
