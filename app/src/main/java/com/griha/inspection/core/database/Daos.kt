package com.griha.inspection.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.griha.inspection.core.model.SyncEntity
import com.griha.inspection.core.model.SyncOperation
import kotlinx.coroutines.flow.Flow

data class InspectionSummary(
    @Embedded val inspection: InspectionEntity,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
    @ColumnInfo(name = "pending_photo_count") val pendingPhotoCount: Int,
    @ColumnInfo(name = "finding_count") val findingCount: Int,
)

data class RoomSummary(
    @Embedded val room: RoomAreaEntity,
    @ColumnInfo(name = "finding_count") val findingCount: Int,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
)

/** A finding plus the name of its room, for "move photo to…" pickers. */
data class FindingWithRoom(
    @Embedded val finding: FindingEntity,
    @ColumnInfo(name = "room_name") val roomName: String,
)

@Dao
interface InspectionDao {
    /**
     * Dashboard list. [query] matches client name, phone or address (empty = all);
     * [status] is an InspectionStatus name or null for all.
     */
    @Query(
        """
        SELECT i.*,
          (SELECT COUNT(*) FROM photos p WHERE p.inspection_id = i.id AND p.deleted_at IS NULL) AS photo_count,
          (SELECT COUNT(*) FROM photos p WHERE p.inspection_id = i.id AND p.deleted_at IS NULL
              AND p.upload_state NOT IN ('SERVER_VERIFIED', 'CLEANUP_ELIGIBLE')) AS pending_photo_count,
          (SELECT COUNT(*) FROM findings f WHERE f.inspection_id = i.id AND f.deleted_at IS NULL AND f.kind = 'ISSUE') AS finding_count
        FROM inspections i
        WHERE i.deleted_at IS NULL
          AND (:status IS NULL OR i.status = :status)
          AND (:query = '' OR i.client_name LIKE '%' || :query || '%'
               OR i.property_address LIKE '%' || :query || '%'
               OR i.client_phone LIKE '%' || :query || '%'
               OR i.unit_number LIKE '%' || :query || '%')
        ORDER BY i.updated_at DESC
        """
    )
    fun observeSummaries(query: String, status: String?): Flow<List<InspectionSummary>>

    @Query("SELECT * FROM inspections WHERE id = :id")
    fun observe(id: String): Flow<InspectionEntity?>

    @Query("SELECT * FROM inspections WHERE id = :id")
    suspend fun get(id: String): InspectionEntity?

    @Insert
    suspend fun insert(inspection: InspectionEntity)

    @Update
    suspend fun update(inspection: InspectionEntity)

    @Query("UPDATE inspections SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("UPDATE inspections SET status = :status, updated_at = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: Long)

    /**
     * Content changed after the report was finalized: back to in-progress, so the next finalize
     * creates a new version instead of altering the one already shared (PRD §13). Returns rows changed.
     */
    @Query("UPDATE inspections SET status = 'IN_PROGRESS', updated_at = :now WHERE id = :id AND status = 'FINALIZED'")
    suspend fun reopenIfFinalized(id: String, now: Long): Int

    @Query("UPDATE inspections SET status = 'FINALIZED', report_version = :version, finalized_at = :now, updated_at = :now WHERE id = :id")
    suspend fun markFinalized(id: String, version: Int, now: Long)

    @Upsert
    suspend fun upsert(inspection: InspectionEntity)

    @Query("UPDATE inspections SET revision = :revision WHERE id = :id")
    suspend fun setRevision(id: String, revision: Long)

    /** Records made before the first sign-in carry a placeholder user; hand them to the real one. */
    @Query("UPDATE inspections SET inspector_user_id = :userId WHERE inspector_user_id = :placeholder")
    suspend fun reassignInspector(placeholder: String, userId: String)

    /** First real work on a draft moves it to in-progress; later states are left alone. */
    @Query("UPDATE inspections SET status = 'IN_PROGRESS' WHERE id = :id AND status = 'DRAFT'")
    suspend fun markStarted(id: String)
}

@Dao
interface RoomAreaDao {
    @Query(
        """
        SELECT r.*,
          (SELECT COUNT(*) FROM findings f WHERE f.room_id = r.id AND f.deleted_at IS NULL AND f.kind = 'ISSUE') AS finding_count,
          (SELECT COUNT(*) FROM photos p WHERE p.room_id = r.id AND p.deleted_at IS NULL) AS photo_count
        FROM rooms r
        WHERE r.inspection_id = :inspectionId AND r.deleted_at IS NULL
        ORDER BY r.sort_order
        """
    )
    fun observeSummaries(inspectionId: String): Flow<List<RoomSummary>>

    @Query("SELECT * FROM rooms WHERE id = :id")
    fun observe(id: String): Flow<RoomAreaEntity?>

    @Query("SELECT * FROM rooms WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<RoomAreaEntity>

    @Query("SELECT * FROM rooms WHERE inspection_id = :inspectionId AND deleted_at IS NULL ORDER BY sort_order")
    suspend fun activeRooms(inspectionId: String): List<RoomAreaEntity>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM rooms WHERE inspection_id = :inspectionId")
    suspend fun maxSortOrder(inspectionId: String): Int

    @Insert
    suspend fun insertAll(rooms: List<RoomAreaEntity>)

    @Query("UPDATE rooms SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE rooms SET sort_order = :order, updated_at = :now WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int, now: Long)

    @Query("UPDATE rooms SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun tombstone(id: String, now: Long)

    @Query("SELECT * FROM rooms WHERE id = :id")
    suspend fun get(id: String): RoomAreaEntity?

    @Upsert
    suspend fun upsert(room: RoomAreaEntity)

    @Query("UPDATE rooms SET revision = :revision WHERE id = :id")
    suspend fun setRevision(id: String, revision: Long)
}

@Dao
interface FindingDao {
    @Query("SELECT * FROM findings WHERE room_id = :roomId AND deleted_at IS NULL ORDER BY sort_order, created_at")
    fun observeForRoom(roomId: String): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE id = :id")
    fun observe(id: String): Flow<FindingEntity?>

    /** Issues only (no room common photos): what reviews, checks and "move photo to" list. */
    @Query(
        """
        SELECT f.*, r.name AS room_name FROM findings f JOIN rooms r ON r.id = f.room_id
        WHERE f.inspection_id = :inspectionId AND f.deleted_at IS NULL AND r.deleted_at IS NULL AND f.kind = 'ISSUE'
        ORDER BY r.sort_order, f.sort_order, f.created_at
        """
    )
    fun observeForInspection(inspectionId: String): Flow<List<FindingWithRoom>>

    @Query("SELECT * FROM findings WHERE id = :id")
    suspend fun get(id: String): FindingEntity?

    /** Report order: rooms as arranged, then findings as arranged within each room. Includes room common photos. */
    @Query(
        """
        SELECT f.* FROM findings f JOIN rooms r ON r.id = f.room_id
        WHERE f.inspection_id = :inspectionId AND f.deleted_at IS NULL AND r.deleted_at IS NULL
        ORDER BY r.sort_order, f.sort_order, f.created_at
        """
    )
    suspend fun activeForInspection(inspectionId: String): List<FindingEntity>

    @Query("SELECT id FROM findings WHERE room_id = :roomId AND deleted_at IS NULL")
    suspend fun activeIdsForRoom(roomId: String): List<String>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM findings WHERE room_id = :roomId")
    suspend fun maxSortOrder(roomId: String): Int

    @Insert
    suspend fun insert(finding: FindingEntity)

    @Update
    suspend fun update(finding: FindingEntity)

    @Query("UPDATE findings SET rough_note = :note, updated_at = :now WHERE id = :id")
    suspend fun updateRoughNote(id: String, note: String, now: Long)

    /** Findings with no text of any kind and no photos — leftovers from abandoned captures. */
    @Query(
        """
        SELECT id FROM findings
        WHERE deleted_at IS NULL AND TRIM(rough_note) = '' AND title IS NULL
          AND detailed_observation IS NULL AND implication IS NULL AND recommendation IS NULL
          AND NOT EXISTS (SELECT 1 FROM photos p WHERE p.finding_id = findings.id AND p.deleted_at IS NULL)
        """
    )
    suspend fun emptyFindingIds(): List<String>

    @Query("UPDATE findings SET deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun tombstone(ids: List<String>, now: Long)

    @Upsert
    suspend fun upsert(finding: FindingEntity)

    @Query("UPDATE findings SET revision = :revision WHERE id = :id")
    suspend fun setRevision(id: String, revision: Long)

    @Query("UPDATE findings SET created_by = :userId WHERE created_by = :placeholder")
    suspend fun reassignCreator(placeholder: String, userId: String)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE room_id = :roomId AND deleted_at IS NULL ORDER BY captured_at")
    fun observeForRoom(roomId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE finding_id = :findingId AND deleted_at IS NULL ORDER BY captured_at")
    fun observeForFinding(findingId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE inspection_id = :inspectionId AND deleted_at IS NULL ORDER BY captured_at")
    suspend fun activeForInspection(inspectionId: String): List<PhotoEntity>

    @Query("SELECT local_original_path FROM photos")
    suspend fun allLocalPaths(): List<String>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun get(id: String): PhotoEntity?

    @Query("SELECT id FROM photos WHERE finding_id IN (:findingIds) AND deleted_at IS NULL")
    suspend fun activeIdsForFindings(findingIds: List<String>): List<String>

    @Insert
    suspend fun insert(photo: PhotoEntity)

    @Query("UPDATE photos SET caption = :caption, updated_at = :now WHERE id = :id")
    suspend fun updateCaption(id: String, caption: String?, now: Long)

    @Query("UPDATE photos SET finding_id = :findingId, room_id = :roomId, updated_at = :now WHERE id = :id")
    suspend fun move(id: String, findingId: String, roomId: String, now: Long)

    @Insert
    suspend fun insertAll(photos: List<PhotoEntity>)

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("UPDATE photos SET revision = :revision WHERE id = :id")
    suspend fun setRevision(id: String, revision: Long)

    @Query("UPDATE photos SET upload_state = :state WHERE id = :id")
    suspend fun setUploadState(id: String, state: String)

    @Query(
        """
        UPDATE photos SET upload_state = 'SERVER_VERIFIED', server_object_key = :objectKey,
          backup_verified_at = :verifiedAt, backup_checksum = :checksum WHERE id = :id
        """
    )
    suspend fun markBackedUp(id: String, objectKey: String, verifiedAt: Long, checksum: String)

    @Query("SELECT * FROM photos WHERE inspection_id = :inspectionId AND deleted_at IS NULL")
    suspend fun allForInspection(inspectionId: String): List<PhotoEntity>

    /** Photos whose original file is (meant to be) on this phone, for retention cleanup and storage stats. */
    @Query("SELECT * FROM photos WHERE original_deleted_at IS NULL ORDER BY inspection_id")
    suspend fun withLocalOriginal(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE original_deleted_at IS NOT NULL")
    suspend fun withRemovedOriginal(): List<PhotoEntity>

    /** Including tombstoned photos: their originals are files on this phone too. */
    @Query("SELECT * FROM photos WHERE inspection_id = :inspectionId")
    suspend fun everyForInspection(inspectionId: String): List<PhotoEntity>

    @Query("UPDATE photos SET original_deleted_at = :now, cleanup_eligible_at = :eligibleAt WHERE id = :id AND original_deleted_at IS NULL")
    suspend fun markOriginalRemoved(id: String, eligibleAt: Long, now: Long): Int

    /** The original came back (downloaded again for a report): a normal local copy again. */
    @Query("UPDATE photos SET original_deleted_at = NULL, cleanup_eligible_at = NULL WHERE id = :id")
    suspend fun markOriginalRestored(id: String)

    @Query("UPDATE photos SET archived_at = :at WHERE id = :id AND archived_at IS NULL")
    suspend fun markArchived(id: String, at: Long)

    /** Hides photos from the inspection/report. The original file is kept (PRD §5.9 owns deletion). */
    @Query("UPDATE photos SET deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun tombstone(ids: List<String>, now: Long)
}

data class OutboxCount(val type: SyncEntity, val op: SyncOperation, val count: Int)

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox WHERE entity_type = :type AND entity_id = :id AND operation = :op")
    suspend fun find(type: SyncEntity, id: String, op: SyncOperation): SyncOutboxEntity?

    @Insert
    suspend fun insert(item: SyncOutboxEntity)

    @Query(
        """
        UPDATE sync_outbox
        SET payload_version = payload_version + 1, attempt_count = 0, next_attempt_at = :now, updated_at = :now
        WHERE id = :rowId
        """
    )
    suspend fun bump(rowId: Long, now: Long)

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    /** Work that is due now, oldest first. Conflicts wait for the inspector's decision. */
    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE operation = :op AND next_attempt_at <= :now AND (last_error_code IS NULL OR last_error_code <> 'CONFLICT')
        ORDER BY id LIMIT :limit
        """
    )
    suspend fun due(op: SyncOperation, now: Long, limit: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE id = :rowId")
    suspend fun get(rowId: Long): SyncOutboxEntity?

    @Query("SELECT MIN(next_attempt_at) FROM sync_outbox WHERE last_error_code IS NULL OR last_error_code <> 'CONFLICT'")
    suspend fun nextDueAt(): Long?

    /** Done, unless the record was edited again while the request was in flight. */
    @Query("DELETE FROM sync_outbox WHERE id = :rowId AND payload_version = :version")
    suspend fun complete(rowId: Long, version: Int): Int

    @Query(
        """
        UPDATE sync_outbox SET attempt_count = attempt_count + 1, next_attempt_at = :next,
          last_error_code = :code, last_error_message = :message, conflict_payload = :conflict, updated_at = :now
        WHERE id = :rowId
        """
    )
    suspend fun fail(rowId: Long, next: Long, code: String, message: String?, conflict: String?, now: Long)

    @Query("UPDATE sync_outbox SET last_error_code = NULL, last_error_message = NULL, conflict_payload = NULL, next_attempt_at = :now WHERE id = :rowId")
    suspend fun clearError(rowId: Long, now: Long)

    /** Lets everything waiting on backoff run now ("Sync now"); conflicts still wait. */
    @Query("UPDATE sync_outbox SET next_attempt_at = :now WHERE last_error_code IS NULL OR last_error_code <> 'CONFLICT'")
    suspend fun retryAllNow(now: Long)

    @Query("SELECT entity_type AS type, operation AS op, COUNT(*) AS count FROM sync_outbox GROUP BY entity_type, operation")
    fun observeCounts(): Flow<List<OutboxCount>>

    @Query("SELECT * FROM sync_outbox WHERE last_error_code IS NOT NULL ORDER BY id")
    fun observeProblems(): Flow<List<SyncOutboxEntity>>

    @Query("SELECT EXISTS (SELECT 1 FROM sync_outbox WHERE entity_type = :type AND entity_id = :id AND operation = 'UPSERT')")
    suspend fun hasPendingUpsert(type: SyncEntity, id: String): Boolean

    /** Any queued work (edit, upload, conflict) for the inspection or anything inside it. */
    @Query(
        """
        SELECT COUNT(*) FROM sync_outbox o WHERE
          (o.entity_type = 'INSPECTION' AND o.entity_id = :inspectionId)
          OR (o.entity_type = 'ROOM' AND o.entity_id IN (SELECT id FROM rooms WHERE inspection_id = :inspectionId))
          OR (o.entity_type = 'FINDING' AND o.entity_id IN (SELECT id FROM findings WHERE inspection_id = :inspectionId))
          OR (o.entity_type = 'PHOTO' AND o.entity_id IN (SELECT id FROM photos WHERE inspection_id = :inspectionId))
          OR (o.entity_type = 'REPORT' AND o.entity_id IN (SELECT id FROM reports WHERE inspection_id = :inspectionId))
        """
    )
    suspend fun pendingForInspection(inspectionId: String): Int

    @Query("SELECT * FROM sync_outbox WHERE last_error_code IS NOT NULL ORDER BY id LIMIT :limit")
    suspend fun problems(limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE operation = :op")
    suspend fun countFor(op: SyncOperation): Int
}

@Dao
interface ReportDao {
    /** Finalized versions, newest first. */
    @Query("SELECT * FROM reports WHERE inspection_id = :inspectionId ORDER BY version DESC")
    fun observeForInspection(inspectionId: String): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE id = :id")
    suspend fun get(id: String): ReportEntity?

    @Query("SELECT * FROM reports WHERE inspection_id = :inspectionId")
    suspend fun allForInspection(inspectionId: String): List<ReportEntity>

    @Query("SELECT COALESCE(MAX(version), 0) FROM reports WHERE inspection_id = :inspectionId")
    suspend fun maxVersion(inspectionId: String): Int

    @Query("SELECT * FROM reports WHERE inspection_id = :inspectionId ORDER BY version DESC LIMIT 1")
    suspend fun latest(inspectionId: String): ReportEntity?

    @Insert
    suspend fun insert(report: ReportEntity)

    @Query("UPDATE reports SET shared_at = :now WHERE id = :id")
    suspend fun markShared(id: String, now: Long)

    @Upsert
    suspend fun upsert(report: ReportEntity)

    @Query("UPDATE reports SET revision = :revision WHERE id = :id")
    suspend fun setRevision(id: String, revision: Long)

    @Query("UPDATE reports SET archived_at = :at WHERE id = :id AND archived_at IS NULL")
    suspend fun markArchived(id: String, at: Long)

    @Query("UPDATE reports SET server_object_key = :objectKey, backup_verified_at = :verifiedAt WHERE id = :id")
    suspend fun markBackedUp(id: String, objectKey: String, verifiedAt: Long)

    /** Finalized PDFs whose metadata reached the server but whose file is not backed up yet. */
    @Query(
        """
        SELECT * FROM reports r WHERE r.backup_verified_at IS NULL AND r.revision > 0
          AND NOT EXISTS (SELECT 1 FROM sync_outbox o WHERE o.entity_type = 'REPORT' AND o.entity_id = r.id)
        """
    )
    suspend fun awaitingUpload(): List<ReportEntity>
}

@Dao
interface UserDao {
    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun get(id: String): UserEntity?

    @Query("SELECT * FROM users ORDER BY display_name")
    fun observeAll(): Flow<List<UserEntity>>
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events WHERE uploaded_at IS NULL ORDER BY created_at LIMIT :limit")
    suspend fun notUploaded(limit: Int): List<AuditEventEntity>

    @Query("UPDATE audit_events SET uploaded_at = :now WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, now: Long)

    @Query("SELECT * FROM audit_events WHERE type = :type ORDER BY created_at DESC LIMIT :limit")
    fun observeByType(type: String, limit: Int): Flow<List<AuditEventEntity>>
}
