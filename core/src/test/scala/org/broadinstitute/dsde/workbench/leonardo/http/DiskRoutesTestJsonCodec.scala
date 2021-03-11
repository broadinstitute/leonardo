package org.broadinstitute.dsde.workbench.leonardo
package http

import io.circe.{Decoder, Encoder}
import org.broadinstitute.dsde.workbench.google2.{DiskName, ZoneName}
import org.broadinstitute.dsde.workbench.leonardo.JsonCodec._
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId.PersistentDiskSamResourceId
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

object DiskRoutesTestJsonCodec {
  implicit val createDiskRequestEncoder: Encoder[CreateDiskRequest] = Encoder.forProduct4(
    "labels",
    "size",
    "diskType",
    "blockSize"
  )(x =>
    (
      x.labels,
      x.size,
      x.diskType,
      x.blockSize
    )
  )

  implicit val getDiskResponseDecoder: Decoder[GetPersistentDiskResponse] = Decoder.instance { x =>
    for {
      id <- x.downField("id").as[DiskId]
      googleProject <- x.downField("googleProject").as[GoogleProject]
      zone <- x.downField("zone").as[ZoneName]
      name <- x.downField("name").as[DiskName]
      googleId <- x.downField("googleId").as[Option[GoogleId]]
      serviceAccount <- x.downField("serviceAccount").as[WorkbenchEmail]
      status <- x.downField("status").as[DiskStatus]
      auditInfo <- x.downField("auditInfo").as[AuditInfo]
      size <- x.downField("size").as[DiskSize]
      diskType <- x.downField("diskType").as[DiskType]
      blockSize <- x.downField("blockSize").as[BlockSize]
      labels <- x.downField("labels").as[LabelMap]
    } yield GetPersistentDiskResponse(
      id,
      googleProject,
      zone,
      name,
      googleId,
      serviceAccount,
      // TODO samResource probably shouldn't be in the GetPersistentDiskResponse
      // if it's not in the encoder
      PersistentDiskSamResourceId("test"),
      status,
      auditInfo,
      size,
      diskType,
      blockSize,
      labels
    )
  }

  implicit val listDiskResponseDecoder: Decoder[ListPersistentDiskResponse] = Decoder.instance { x =>
    for {
      id <- x.downField("id").as[DiskId]
      googleProject <- x.downField("googleProject").as[GoogleProject]
      zone <- x.downField("zone").as[ZoneName]
      name <- x.downField("name").as[DiskName]
      status <- x.downField("status").as[DiskStatus]
      auditInfo <- x.downField("auditInfo").as[AuditInfo]
      size <- x.downField("size").as[DiskSize]
      diskType <- x.downField("diskType").as[DiskType]
      blockSize <- x.downField("blockSize").as[BlockSize]
    } yield ListPersistentDiskResponse(
      id,
      googleProject,
      zone,
      name,
      status,
      auditInfo,
      size,
      diskType,
      blockSize
    )
  }
}
