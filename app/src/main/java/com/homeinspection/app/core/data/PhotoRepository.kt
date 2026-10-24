package com.homeinspection.app.core.data

import androidx.room.withTransaction
import com.homeinspection.app.core.database.AuditEventEntity
import com.homeinspection.app.core.database.FindingEntity
import com.homeinspection.app.core.database.HomeInspectionDatabase
import com.homeinspection.app.core.database.PhotoEntity
import com.homeinspection.app.core.model.FindingKind
import com.homeinspection.app.core.model.SyncEntity
import com.homeinspection.app.core.model.SyncOperation
import com.homeinspection.app.core.storage.PhotoStore
import com.homeinspection.app.core.storage.PhotoStore.InterruptedCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where a photo belongs. Every photo is attached to exactly one finding (PRD §5.5).
 * The finding may not exist yet: a "new finding" is only created when its first photo saves,
 * so abandoned or failed captures never leave empty findings behind. [kind] is what such a new
 * finding becomes (a room's common photos are a ROOM_PHOTOS finding).
 */
data class PhotoTarget(
    val inspectionId: String,
    val roomId: String,
    val findingId: String,
    val kind: FindingKind = FindingKind.ISSUE,
)

class PhotoRepository(
    private val db: HomeInspectionDatabase,
    private val store: PhotoStore,
    private val currentUserId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val photos = db.photoDao()
    private val findings = db.findingDao()
    private val outbox = db.outboxDao()

    /** Serializes finalize+commit against startup recovery so neither sees the other half-done. */
    private val fileLock = Mutex()

    private val _interrupted = MutableStateFlow<List<InterruptedCapture>>(emptyList())

    /** Captures cut off by the last crash/kill; shown once so the inspector can retake them. */
    val interruptedCaptures = _interrupted.asStateFlow()

    fun dismissInterrupted() {
        _interrupted.value = emptyList()
    }

    fun observeForRoom(roomId: String) = photos.observeForRoom(roomId)
    fun observeForFinding(findingId: String) = photos.observeForFinding(findingId)
    fun resolve(photo: PhotoEntity): File = store.resolve(photo.localOriginalPath)
    fun freeBytes(): Long = store.freeBytes()

    /** Call at shutter press. Returns the new photo id and the temp file the camera writes to. */
    fun newCapture(target: PhotoTarget): Pair<String, File> {
        val photoId = UUID.randomUUID().toString()
        return photoId to store.newTempFile(target.inspectionId, target.roomId, photoId)
    }

    /** For a capture that failed while the app was alive — the inspector already saw the error. */
    fun discardCapture(tempFile: File) = store.discardTemp(tempFile)

    /**
     * Turns a finished camera temp file into a saved photo. When this returns normally the
     * original is durable on disk AND its metadata + upload task are committed — the only point
     * at which the UI may show "saved". Creates the target finding in the same transaction if
     * this is its first photo. Throws if any step fails.
     *
     * Callers must run this in a scope that outlives the screen (see HomeInspectionApp.appScope), so
     * leaving the camera mid-save cannot cancel it.
     */
    suspend fun commitCapture(photoId: String, tempFile: File, target: PhotoTarget, capturedAt: Long): PhotoEntity =
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                val original = store.finalize(tempFile, target.inspectionId, photoId)
                val now = clock()
                val entity = PhotoEntity(
                    id = photoId,
                    inspectionId = target.inspectionId,
                    roomId = target.roomId,
                    findingId = target.findingId,
                    localOriginalPath = original.relativePath,
                    mimeType = original.mimeType,
                    width = original.width,
                    height = original.height,
                    fileSizeBytes = original.sizeBytes,
                    sha256 = original.sha256,
                    capturedAt = capturedAt,
                    createdAt = now,
                    updatedAt = now,
                )
                db.withTransaction {
                    if (findings.get(target.findingId) == null) {
                        findings.insert(
                            FindingEntity(
                                id = target.findingId,
                                inspectionId = target.inspectionId,
                                roomId = target.roomId,
                                sortOrder = findings.maxSortOrder(target.roomId) + 1,
                                createdBy = currentUserId(),
                                createdAt = now,
                                updatedAt = now,
                                kind = target.kind,
                            )
                        )
                        outbox.enqueue(SyncEntity.FINDING, target.findingId, SyncOperation.UPSERT, now)
                    }
                    photos.insert(entity)
                    outbox.enqueue(SyncEntity.PHOTO, photoId, SyncOperation.UPLOAD_ORIGINAL, now)
                    db.inspectionDao().markStarted(target.inspectionId)
                    db.reportContentChanged(target.inspectionId, now)
                }
                entity
            }
        }

    suspend fun updateCaption(photoId: String, caption: String) {
        val now = clock()
        db.withTransaction {
            val photo = photos.get(photoId) ?: return@withTransaction
            val newCaption = caption.trim().ifEmpty { null }
            if (photo.caption == newCaption) return@withTransaction
            photos.updateCaption(photoId, newCaption, now)
            outbox.enqueue(SyncEntity.PHOTO, photoId, SyncOperation.UPSERT, now)
            db.reportContentChanged(photo.inspectionId, now)
        }
    }

    /**
     * Re-files a photo under another finding (and that finding's room). Only metadata changes:
     * the original file keeps its path and checksum, and its upload task is untouched.
     */
    suspend fun moveToFinding(photoId: String, findingId: String) {
        val now = clock()
        db.withTransaction {
            val target = findings.get(findingId) ?: return@withTransaction
            photos.move(photoId, target.id, target.roomId, now)
            outbox.enqueue(SyncEntity.PHOTO, photoId, SyncOperation.UPSERT, now)
            db.reportContentChanged(target.inspectionId, now)
        }
    }

    /** Run once at app start, before the user can reach the camera. */
    suspend fun recoverInterruptedCaptures(processStartMillis: Long) = withContext(Dispatchers.IO) {
        fileLock.withLock {
            val known = photos.allLocalPaths().toSet()
            val result = store.recover(known, processStartMillis)
            val now = clock()
            result.interrupted.forEach {
                db.auditDao().insert(
                    AuditEventEntity(
                        id = UUID.randomUUID().toString(),
                        type = "PHOTO_CAPTURE_INTERRUPTED",
                        entityType = "ROOM",
                        entityId = it.roomId,
                        details = "inspection=${it.inspectionId}",
                        createdAt = now,
                    )
                )
            }
            if (result.recoveredOriginals > 0) {
                db.auditDao().insert(
                    AuditEventEntity(
                        id = UUID.randomUUID().toString(),
                        type = "PHOTO_RECOVERED_ORPHAN",
                        details = "count=${result.recoveredOriginals}",
                        createdAt = now,
                    )
                )
            }
            _interrupted.value = result.interrupted
        }
    }
}
