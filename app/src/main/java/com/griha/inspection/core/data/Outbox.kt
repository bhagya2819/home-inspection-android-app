package com.griha.inspection.core.data

import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.database.SyncOutboxDao
import com.griha.inspection.core.database.SyncOutboxEntity
import com.griha.inspection.core.model.SyncEntity
import com.griha.inspection.core.model.SyncOperation
import java.util.UUID

/**
 * Records that [entityId] needs to reach the server. Must be called inside the same Room
 * transaction as the write it describes, so a record can never exist without its outbox entry.
 */
suspend fun SyncOutboxDao.enqueue(type: SyncEntity, entityId: String, op: SyncOperation, now: Long) {
    val existing = find(type, entityId, op)
    if (existing != null) {
        bump(existing.id, now)
    } else {
        insert(
            SyncOutboxEntity(
                entityType = type,
                entityId = entityId,
                operation = op,
                idempotencyKey = UUID.randomUUID().toString(),
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now,
            )
        )
    }
}

/**
 * Call inside the transaction of any write that changes what the report would show.
 * Bumps updated_at (drafts re-render) and, if the report was already finalized, moves the
 * inspection back to in-progress so the shared version stays untouched and the next
 * finalize creates a new one (PRD §13).
 */
suspend fun GrihaDatabase.reportContentChanged(inspectionId: String, now: Long) {
    val dao = inspectionDao()
    dao.touch(inspectionId, now)
    if (dao.reopenIfFinalized(inspectionId, now) > 0) {
        outboxDao().enqueue(SyncEntity.INSPECTION, inspectionId, SyncOperation.UPSERT, now)
    }
}
