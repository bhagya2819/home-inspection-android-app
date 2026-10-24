package com.homeinspection.app.core.sync

import com.homeinspection.app.core.database.FindingEntity
import com.homeinspection.app.core.database.InspectionEntity
import com.homeinspection.app.core.database.PhotoEntity
import com.homeinspection.app.core.database.ReportEntity
import com.homeinspection.app.core.database.RoomAreaEntity
import com.homeinspection.app.core.database.UserEntity
import com.homeinspection.app.core.model.FindingKind
import com.homeinspection.app.core.model.FindingStatus
import com.homeinspection.app.core.model.InspectionStatus
import com.homeinspection.app.core.model.Severity
import com.homeinspection.app.core.model.UploadState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Wire format = the Room column names (snake_case), identical to the server tables. Only
 * fields the server stores are sent; device-local ones (file paths, upload state) never leave.
 */
internal object SyncCodec {

    fun inspection(i: InspectionEntity) = buildJsonObject {
        put("client_name", i.clientName); put("client_phone", i.clientPhone); put("property_address", i.propertyAddress)
        put("property_type", i.propertyType); put("unit_number", i.unitNumber); put("access_notes", i.accessNotes)
        put("inspector_user_id", i.inspectorUserId); put("inspection_date", i.inspectionDate); put("time_zone", i.timeZone)
        put("status", i.status.name); put("report_version", i.reportVersion); put("created_at", i.createdAt)
        put("updated_at", i.updatedAt); put("finalized_at", i.finalizedAt); put("deleted_at", i.deletedAt)
        put("summary", i.summary)
    }

    fun room(r: RoomAreaEntity) = buildJsonObject {
        put("inspection_id", r.inspectionId); put("name", r.name); put("sort_order", r.sortOrder); put("is_custom", r.isCustom)
        put("created_at", r.createdAt); put("updated_at", r.updatedAt); put("deleted_at", r.deletedAt)
    }

    fun finding(f: FindingEntity) = buildJsonObject {
        put("inspection_id", f.inspectionId); put("room_id", f.roomId); put("title", f.title); put("category", f.category)
        put("severity", f.severity?.name); put("rough_note", f.roughNote); put("detailed_observation", f.detailedObservation)
        put("implication", f.implication); put("recommendation", f.recommendation); put("status", f.status.name)
        put("sort_order", f.sortOrder); put("created_by", f.createdBy); put("created_at", f.createdAt)
        put("updated_at", f.updatedAt); put("deleted_at", f.deletedAt); put("kind", f.kind.name)
    }

    fun photo(p: PhotoEntity) = buildJsonObject {
        put("inspection_id", p.inspectionId); put("room_id", p.roomId); put("finding_id", p.findingId)
        put("mime_type", p.mimeType); put("width", p.width); put("height", p.height); put("file_size_bytes", p.fileSizeBytes)
        put("sha256", p.sha256); put("captured_at", p.capturedAt); put("caption", p.caption)
        put("created_at", p.createdAt); put("updated_at", p.updatedAt); put("deleted_at", p.deletedAt)
    }

    fun report(r: ReportEntity, fileSize: Long) = buildJsonObject {
        put("inspection_id", r.inspectionId); put("version", r.version); put("content_hash", r.contentHash)
        put("file_size_bytes", fileSize); put("generated_at", r.generatedAt); put("finalized_at", r.finalizedAt)
        put("shared_at", r.sharedAt)
    }

    // ---- From the server ---------------------------------------------------------------------

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.strOrNull(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.numOrNull(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.rev() = numOrNull("revision") ?: 0

    fun inspectionFrom(o: JsonObject) = InspectionEntity(
        id = o.str("id"), clientName = o.str("client_name"), clientPhone = o.str("client_phone"),
        propertyAddress = o.str("property_address"), propertyType = o.strOrNull("property_type"),
        unitNumber = o.strOrNull("unit_number"), accessNotes = o.strOrNull("access_notes"),
        inspectorUserId = o.str("inspector_user_id"), inspectionDate = o.str("inspection_date"), timeZone = o.str("time_zone"),
        status = enumOr(o.str("status"), InspectionStatus.IN_PROGRESS), reportVersion = o.num("report_version").toInt(),
        createdAt = o.num("created_at"), updatedAt = o.num("updated_at"), finalizedAt = o.numOrNull("finalized_at"),
        revision = o.rev(), deletedAt = o.numOrNull("deleted_at"), summary = o.strOrNull("summary"),
    )

    fun roomFrom(o: JsonObject) = RoomAreaEntity(
        id = o.str("id"), inspectionId = o.str("inspection_id"), name = o.str("name"), sortOrder = o.num("sort_order").toInt(),
        isCustom = o["is_custom"]!!.jsonPrimitive.boolean, createdAt = o.num("created_at"), updatedAt = o.num("updated_at"),
        revision = o.rev(), deletedAt = o.numOrNull("deleted_at"),
    )

    fun findingFrom(o: JsonObject) = FindingEntity(
        id = o.str("id"), inspectionId = o.str("inspection_id"), roomId = o.str("room_id"), title = o.strOrNull("title"),
        category = o.strOrNull("category"), severity = o.strOrNull("severity")?.let { s -> Severity.entries.firstOrNull { it.name == s } },
        roughNote = o.str("rough_note"), detailedObservation = o.strOrNull("detailed_observation"),
        implication = o.strOrNull("implication"), recommendation = o.strOrNull("recommendation"),
        status = enumOr(o.str("status"), FindingStatus.OPEN), sortOrder = o.num("sort_order").toInt(),
        createdBy = o.str("created_by"), createdAt = o.num("created_at"), updatedAt = o.num("updated_at"),
        revision = o.rev(), deletedAt = o.numOrNull("deleted_at"),
        kind = enumOr(o.strOrNull("kind").orEmpty(), FindingKind.ISSUE),
    )

    /**
     * A photo row from the server. For a photo taken on another phone the original is not here:
     * [localPath] is where it will be downloaded to when someone opens the inspection.
     * For our own photo, device-only fields (path, cleanup state) are kept from [existing].
     */
    fun photoFrom(o: JsonObject, existing: PhotoEntity?, localPath: String): PhotoEntity {
        val verifiedAt = o.numOrNull("backup_verified_at")
        val base = existing ?: PhotoEntity(
            id = o.str("id"), inspectionId = o.str("inspection_id"), roomId = o.str("room_id"), findingId = o.str("finding_id"),
            localOriginalPath = localPath, mimeType = o.str("mime_type"), width = o.num("width").toInt(),
            height = o.num("height").toInt(), fileSizeBytes = o.num("file_size_bytes"), sha256 = o.str("sha256"),
            capturedAt = o.num("captured_at"), createdAt = o.num("created_at"), updatedAt = o.num("updated_at"),
        )
        return base.copy(
            roomId = o.str("room_id"), findingId = o.str("finding_id"), caption = o.strOrNull("caption"),
            updatedAt = o.num("updated_at"), deletedAt = o.numOrNull("deleted_at"), revision = o.rev(),
            serverObjectKey = o.strOrNull("server_object_key") ?: base.serverObjectKey,
            backupVerifiedAt = verifiedAt ?: base.backupVerifiedAt,
            backupChecksum = o.strOrNull("backup_checksum") ?: base.backupChecksum,
            // Server-owned (M5): set once an admin verified an export holding this photo / archived it.
            exportVerifiedAt = o.numOrNull("export_verified_at") ?: base.exportVerifiedAt,
            archivedAt = o.numOrNull("archived_at") ?: base.archivedAt,
            uploadState = when {
                verifiedAt != null && base.uploadState != UploadState.CLEANUP_ELIGIBLE -> UploadState.SERVER_VERIFIED
                existing == null -> UploadState.QUEUED // still uploading from the phone that took it
                else -> base.uploadState
            },
        )
    }

    fun reportFrom(o: JsonObject, existing: ReportEntity?, localPath: String) = (existing ?: ReportEntity(
        id = o.str("id"), inspectionId = o.str("inspection_id"), version = o.num("version").toInt(), localPdfPath = localPath,
        contentHash = o.str("content_hash"), generatedAt = o.num("generated_at"),
    )).copy(
        finalizedAt = o.numOrNull("finalized_at"), sharedAt = o.numOrNull("shared_at"), revision = o.rev(),
        serverObjectKey = o.strOrNull("server_object_key") ?: existing?.serverObjectKey,
        backupVerifiedAt = o.numOrNull("backup_verified_at") ?: existing?.backupVerifiedAt,
        exportVerifiedAt = o.numOrNull("export_verified_at") ?: existing?.exportVerifiedAt,
        archivedAt = o.numOrNull("archived_at") ?: existing?.archivedAt,
    )

    fun userFrom(o: JsonObject) = UserEntity(
        id = o.str("id"), employeeCode = o.str("employee_code"), displayName = o.str("display_name"),
        role = o.str("role"), status = o.str("status"),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
