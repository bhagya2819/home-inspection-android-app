package com.griha.inspection.core.data

import androidx.room.withTransaction
import com.griha.inspection.core.database.FindingEntity
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.database.InspectionEntity
import com.griha.inspection.core.database.RoomAreaEntity
import com.griha.inspection.core.model.InspectionStatus
import com.griha.inspection.core.model.SyncEntity
import com.griha.inspection.core.model.SyncOperation
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Editable property/client fields, shared by the create and edit screens. */
data class PropertyDetails(
    val clientName: String = "",
    val clientPhone: String = "",
    val propertyAddress: String = "",
    val unitNumber: String = "",
    val propertyType: String = "",
    val inspectionDate: LocalDate = LocalDate.now(),
    val accessNotes: String = "",
) {
    val isValid get() = clientName.isNotBlank() && propertyAddress.isNotBlank()

    companion object {
        fun of(i: InspectionEntity) = PropertyDetails(
            clientName = i.clientName,
            clientPhone = i.clientPhone,
            propertyAddress = i.propertyAddress,
            unitNumber = i.unitNumber.orEmpty(),
            propertyType = i.propertyType.orEmpty(),
            inspectionDate = LocalDate.parse(i.inspectionDate),
            accessNotes = i.accessNotes.orEmpty(),
        )
    }
}

enum class MoveDirection { UP, DOWN }

class InspectionRepository(
    private val db: GrihaDatabase,
    private val currentUserId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val inspections = db.inspectionDao()
    private val rooms = db.roomAreaDao()
    private val findings = db.findingDao()
    private val photos = db.photoDao()
    private val outbox = db.outboxDao()

    fun observeSummaries(query: String, status: InspectionStatus?) =
        inspections.observeSummaries(query.trim(), status?.name)
    fun observeInspection(id: String) = inspections.observe(id)
    fun observeRooms(inspectionId: String) = rooms.observeSummaries(inspectionId)
    fun observeRoom(roomId: String) = rooms.observe(roomId)
    fun observeFindings(roomId: String) = findings.observeForRoom(roomId)
    fun observeFinding(findingId: String) = findings.observe(findingId)
    fun observeFindingsForInspection(inspectionId: String) = findings.observeForInspection(inspectionId)
    suspend fun roomsById(ids: List<String>) = rooms.getAll(ids).associateBy { it.id }

    // ---- Inspections -------------------------------------------------------------------------

    /** Creates the inspection and its chosen rooms in one transaction. Returns its id. */
    suspend fun create(details: PropertyDetails, roomNames: List<String>): String {
        val now = clock()
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            inspections.insert(
                InspectionEntity(
                    id = id,
                    clientName = details.clientName.trim(),
                    clientPhone = details.clientPhone.trim(),
                    propertyAddress = details.propertyAddress.trim(),
                    unitNumber = details.unitNumber.trim().ifEmpty { null },
                    propertyType = details.propertyType.ifEmpty { null },
                    accessNotes = details.accessNotes.trim().ifEmpty { null },
                    inspectorUserId = currentUserId(),
                    inspectionDate = details.inspectionDate.toString(),
                    timeZone = ZoneId.systemDefault().id,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            outbox.enqueue(SyncEntity.INSPECTION, id, SyncOperation.UPSERT, now)
            val newRooms = roomNames.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { index, name ->
                RoomAreaEntity(UUID.randomUUID().toString(), id, name, index, isCustom = false, now, now)
            }
            rooms.insertAll(newRooms)
            newRooms.forEach { outbox.enqueue(SyncEntity.ROOM, it.id, SyncOperation.UPSERT, now) }
        }
        return id
    }

    suspend fun updateDetails(inspectionId: String, details: PropertyDetails) {
        val now = clock()
        db.withTransaction {
            val current = inspections.get(inspectionId) ?: return@withTransaction
            inspections.update(
                current.copy(
                    clientName = details.clientName.trim(),
                    clientPhone = details.clientPhone.trim(),
                    propertyAddress = details.propertyAddress.trim(),
                    unitNumber = details.unitNumber.trim().ifEmpty { null },
                    propertyType = details.propertyType.ifEmpty { null },
                    accessNotes = details.accessNotes.trim().ifEmpty { null },
                    inspectionDate = details.inspectionDate.toString(),
                    updatedAt = now,
                )
            )
            outbox.enqueue(SyncEntity.INSPECTION, inspectionId, SyncOperation.UPSERT, now)
            db.reportContentChanged(inspectionId, now)
        }
    }

    /** The report's overview paragraph; blank clears it. Changes the report like any other edit. */
    suspend fun updateSummary(inspectionId: String, summary: String) {
        val now = clock()
        db.withTransaction {
            val current = inspections.get(inspectionId) ?: return@withTransaction
            val value = summary.trim().ifEmpty { null }
            if (value == current.summary) return@withTransaction
            inspections.update(current.copy(summary = value, updatedAt = now))
            outbox.enqueue(SyncEntity.INSPECTION, inspectionId, SyncOperation.UPSERT, now)
            db.reportContentChanged(inspectionId, now)
        }
    }

    /** Manual status changes. Finalizing goes through ReportRepository.finalize (it creates a version). */
    suspend fun setStatus(inspectionId: String, status: InspectionStatus) {
        require(status in setOf(InspectionStatus.IN_PROGRESS, InspectionStatus.READY_FOR_REPORT)) { "Unsupported: $status" }
        val now = clock()
        db.withTransaction {
            inspections.setStatus(inspectionId, status.name, now)
            outbox.enqueue(SyncEntity.INSPECTION, inspectionId, SyncOperation.UPSERT, now)
        }
    }

    // ---- Rooms -------------------------------------------------------------------------------

    suspend fun addCustomRoom(inspectionId: String, name: String): String {
        val now = clock()
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            val order = rooms.maxSortOrder(inspectionId) + 1
            rooms.insertAll(listOf(RoomAreaEntity(id, inspectionId, name.trim(), order, true, now, now)))
            outbox.enqueue(SyncEntity.ROOM, id, SyncOperation.UPSERT, now)
            db.reportContentChanged(inspectionId, now)
        }
        return id
    }

    /** Renaming never changes the room id, so its findings and photos stay attached (PRD §5.3). */
    suspend fun renameRoom(roomId: String, name: String) {
        val now = clock()
        db.withTransaction {
            val room = rooms.getAll(listOf(roomId)).firstOrNull() ?: return@withTransaction
            rooms.rename(roomId, name.trim(), now)
            outbox.enqueue(SyncEntity.ROOM, roomId, SyncOperation.UPSERT, now)
            db.reportContentChanged(room.inspectionId, now)
        }
    }

    /** Swaps the room with its neighbour, then renumbers all rooms 0..n so gaps never accumulate. */
    suspend fun moveRoom(inspectionId: String, roomId: String, direction: MoveDirection) {
        val now = clock()
        db.withTransaction {
            val ordered = rooms.activeRooms(inspectionId).toMutableList()
            val from = ordered.indexOfFirst { it.id == roomId }
            val to = if (direction == MoveDirection.UP) from - 1 else from + 1
            if (from < 0 || to !in ordered.indices) return@withTransaction
            ordered.add(to, ordered.removeAt(from))
            ordered.forEachIndexed { index, room ->
                if (room.sortOrder != index) {
                    rooms.setSortOrder(room.id, index, now)
                    outbox.enqueue(SyncEntity.ROOM, room.id, SyncOperation.UPSERT, now)
                }
            }
            db.reportContentChanged(inspectionId, now)
        }
    }

    /** Hides the room with its findings and photos. Nothing is hard-deleted; original files stay. */
    suspend fun removeRoom(roomId: String) {
        val now = clock()
        db.withTransaction {
            val room = rooms.getAll(listOf(roomId)).firstOrNull() ?: return@withTransaction
            val findingIds = findings.activeIdsForRoom(roomId)
            val photoIds = if (findingIds.isEmpty()) emptyList() else photos.activeIdsForFindings(findingIds)
            rooms.tombstone(roomId, now)
            outbox.enqueue(SyncEntity.ROOM, roomId, SyncOperation.UPSERT, now)
            if (findingIds.isNotEmpty()) {
                findings.tombstone(findingIds, now)
                findingIds.forEach { outbox.enqueue(SyncEntity.FINDING, it, SyncOperation.UPSERT, now) }
            }
            if (photoIds.isNotEmpty()) {
                photos.tombstone(photoIds, now)
                photoIds.forEach { outbox.enqueue(SyncEntity.PHOTO, it, SyncOperation.UPSERT, now) }
            }
            db.reportContentChanged(room.inspectionId, now)
        }
    }

    // ---- Findings ----------------------------------------------------------------------------

    suspend fun updateRoughNote(findingId: String, note: String) {
        val now = clock()
        db.withTransaction {
            val finding = findings.get(findingId) ?: return@withTransaction
            if (finding.roughNote == note) return@withTransaction
            findings.updateRoughNote(findingId, note, now)
            outbox.enqueue(SyncEntity.FINDING, findingId, SyncOperation.UPSERT, now)
            db.reportContentChanged(finding.inspectionId, now)
        }
    }

    /**
     * Saves the editor's fields. Only fields the inspector changed relative to [base] (the row the
     * editor last saw) are written; the rest keep the DB's current value, so a newer edit that arrived
     * from another phone while the editor was open is not overwritten. Identity, room, ordering and
     * audit fields always come from the DB row.
     */
    suspend fun saveFinding(base: FindingEntity, edited: FindingEntity) {
        val now = clock()
        db.withTransaction {
            val current = findings.get(edited.id) ?: return@withTransaction
            val merged = mergeFindingEdits(base, edited, current)
            val updated = merged.copy(
                title = merged.title?.trim()?.ifEmpty { null },
                detailedObservation = merged.detailedObservation?.ifBlank { null },
                implication = merged.implication?.ifBlank { null },
                recommendation = merged.recommendation?.ifBlank { null },
            )
            // Opening an editor must not count as an edit (it would reopen a finalized report).
            if (updated == current) return@withTransaction
            findings.update(updated.copy(updatedAt = now))
            outbox.enqueue(SyncEntity.FINDING, edited.id, SyncOperation.UPSERT, now)
            db.reportContentChanged(current.inspectionId, now)
        }
    }

    suspend fun deleteFinding(findingId: String) {
        val now = clock()
        db.withTransaction {
            val finding = findings.get(findingId) ?: return@withTransaction
            val photoIds = photos.activeIdsForFindings(listOf(findingId))
            findings.tombstone(listOf(findingId), now)
            outbox.enqueue(SyncEntity.FINDING, findingId, SyncOperation.UPSERT, now)
            if (photoIds.isNotEmpty()) {
                photos.tombstone(photoIds, now)
                photoIds.forEach { outbox.enqueue(SyncEntity.PHOTO, it, SyncOperation.UPSERT, now) }
            }
            db.reportContentChanged(finding.inspectionId, now)
        }
    }

    /**
     * Tombstones findings that hold nothing (no text, no photos). New findings are only created
     * with their first photo, so these are leftovers; run at startup, never while editing.
     */
    suspend fun removeEmptyFindings() {
        val now = clock()
        db.withTransaction {
            val ids = findings.emptyFindingIds()
            if (ids.isEmpty()) return@withTransaction
            findings.tombstone(ids, now)
            ids.forEach { outbox.enqueue(SyncEntity.FINDING, it, SyncOperation.UPSERT, now) }
        }
    }
}

/**
 * Three-way merge of the finding editor's fields: a field the inspector changed ([edited] differs
 * from [base]) wins; any other field takes [current] (e.g. a newer edit pulled from another phone).
 * Everything that is not an editor field comes from [current].
 */
fun mergeFindingEdits(base: FindingEntity, edited: FindingEntity, current: FindingEntity): FindingEntity {
    fun <T> pick(get: (FindingEntity) -> T): T = if (get(edited) != get(base)) get(edited) else get(current)
    return current.copy(
        title = pick { it.title },
        category = pick { it.category },
        severity = pick { it.severity },
        roughNote = pick { it.roughNote },
        detailedObservation = pick { it.detailedObservation },
        implication = pick { it.implication },
        recommendation = pick { it.recommendation },
        status = pick { it.status },
    )
}
