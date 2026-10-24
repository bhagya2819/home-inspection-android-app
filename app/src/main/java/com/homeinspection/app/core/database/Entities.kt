package com.homeinspection.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homeinspection.app.core.model.FindingKind
import com.homeinspection.app.core.model.FindingStatus
import com.homeinspection.app.core.model.InspectionStatus
import com.homeinspection.app.core.model.Severity
import com.homeinspection.app.core.model.SyncEntity
import com.homeinspection.app.core.model.SyncOperation
import com.homeinspection.app.core.model.UploadState

// All ids are device-generated UUID strings so records can be created offline (PRD §7).
// All timestamps are UTC epoch millis. Deletes are tombstones (deleted_at), never hard deletes.

@Entity(
    tableName = "inspections",
    indices = [Index("status"), Index("updated_at")],
)
data class InspectionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "server_id") val serverId: String? = null,
    @ColumnInfo(name = "client_name") val clientName: String,
    @ColumnInfo(name = "client_phone") val clientPhone: String,
    @ColumnInfo(name = "property_address") val propertyAddress: String,
    @ColumnInfo(name = "property_type") val propertyType: String? = null,
    @ColumnInfo(name = "unit_number") val unitNumber: String? = null,
    @ColumnInfo(name = "access_notes") val accessNotes: String? = null,
    @ColumnInfo(name = "inspector_user_id") val inspectorUserId: String,
    /** ISO local date (yyyy-MM-dd) as the inspector saw it on site. */
    @ColumnInfo(name = "inspection_date") val inspectionDate: String,
    @ColumnInfo(name = "time_zone") val timeZone: String,
    val status: InspectionStatus = InspectionStatus.DRAFT,
    @ColumnInfo(name = "report_version") val reportVersion: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "finalized_at") val finalizedAt: Long? = null,
    /** Last revision acknowledged by the server; used for conflict detection (PRD §8.4). */
    val revision: Long = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** Overview paragraph on the report's summary page (typed, or an accepted AI draft). */
    val summary: String? = null,
)

@Entity(
    tableName = "rooms",
    foreignKeys = [ForeignKey(InspectionEntity::class, ["id"], ["inspection_id"])],
    indices = [Index("inspection_id")],
)
data class RoomAreaEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inspection_id") val inspectionId: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "is_custom") val isCustom: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val revision: Long = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "findings",
    foreignKeys = [
        ForeignKey(InspectionEntity::class, ["id"], ["inspection_id"]),
        ForeignKey(RoomAreaEntity::class, ["id"], ["room_id"]),
    ],
    indices = [Index("inspection_id"), Index("room_id")],
)
data class FindingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inspection_id") val inspectionId: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    val title: String? = null,
    val category: String? = null,
    val severity: Severity? = null,
    /** Kept even after detailed notes are written (PRD §5.4). */
    @ColumnInfo(name = "rough_note") val roughNote: String = "",
    @ColumnInfo(name = "detailed_observation") val detailedObservation: String? = null,
    val implication: String? = null,
    val recommendation: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val revision: Long = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** An issue, or the room's common photos (DB v8). */
    @ColumnInfo(defaultValue = "ISSUE") val kind: FindingKind = FindingKind.ISSUE,
)

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(InspectionEntity::class, ["id"], ["inspection_id"]),
        ForeignKey(RoomAreaEntity::class, ["id"], ["room_id"]),
        ForeignKey(FindingEntity::class, ["id"], ["finding_id"]),
    ],
    indices = [Index("inspection_id"), Index("room_id"), Index("finding_id"), Index("upload_state")],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inspection_id") val inspectionId: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "finding_id") val findingId: String,
    /** Relative to the app's files dir, so a restored backup or new install path still resolves. */
    @ColumnInfo(name = "local_original_path") val localOriginalPath: String,
    @ColumnInfo(name = "server_object_key") val serverObjectKey: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    val caption: String? = null,
    @ColumnInfo(name = "upload_state") val uploadState: UploadState = UploadState.QUEUED,
    @ColumnInfo(name = "backup_verified_at") val backupVerifiedAt: Long? = null,
    @ColumnInfo(name = "backup_checksum") val backupChecksum: String? = null,
    @ColumnInfo(name = "cleanup_eligible_at") val cleanupEligibleAt: Long? = null,
    @ColumnInfo(name = "original_deleted_at") val originalDeletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** Server revision of the metadata (added in DB v2). */
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    /** When the server confirmed this photo is inside an admin-verified export zip (DB v3, M5). */
    @ColumnInfo(name = "export_verified_at") val exportVerifiedAt: Long? = null,
    /** When the server moved its copy to an export archive; downloads answer 410 from then on (DB v3). */
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

/**
 * Durable queue of work for the server (PRD §8). WorkManager only decides *when* this is drained;
 * this table is the source of truth for *what* is pending. One row per entity+operation:
 * re-editing a record bumps [payloadVersion] instead of adding a duplicate row.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["entity_type", "entity_id", "operation"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index("next_attempt_at"),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: SyncEntity,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val operation: SyncOperation,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int = 1,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Human-readable reason for the last failure, shown on the sync screen (DB v2). */
    @ColumnInfo(name = "last_error_message") val lastErrorMessage: String? = null,
    /** Server's copy (JSON) when the last push hit a revision conflict (DB v2). */
    @ColumnInfo(name = "conflict_payload") val conflictPayload: String? = null,
)

@Entity(
    tableName = "reports",
    foreignKeys = [ForeignKey(InspectionEntity::class, ["id"], ["inspection_id"])],
    indices = [Index(value = ["inspection_id", "version"], unique = true)],
)
data class ReportEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inspection_id") val inspectionId: String,
    val version: Int,
    @ColumnInfo(name = "local_pdf_path") val localPdfPath: String,
    @ColumnInfo(name = "server_object_key") val serverObjectKey: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "finalized_at") val finalizedAt: Long? = null,
    @ColumnInfo(name = "shared_at") val sharedAt: Long? = null,
    @ColumnInfo(name = "backup_verified_at") val backupVerifiedAt: Long? = null,
    /** Server revision of the metadata (added in DB v2). */
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(name = "export_verified_at") val exportVerifiedAt: Long? = null,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

/** Accounts known from the server (PRD §7.1), for inspector names and roles. Never holds credentials. */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "employee_code") val employeeCode: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val role: String,
    val status: String,
)

/** Security- and retention-relevant events (PRD §7.9). Never store secrets or tokens here. */
@Entity(tableName = "audit_events", indices = [Index("created_at")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val type: String,
    @ColumnInfo(name = "entity_type") val entityType: String? = null,
    @ColumnInfo(name = "entity_id") val entityId: String? = null,
    val details: String? = null,
    @ColumnInfo(name = "user_id") val userId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** When the server stored this event (DB v3); null = not uploaded yet. */
    @ColumnInfo(name = "uploaded_at") val uploadedAt: Long? = null,
)
