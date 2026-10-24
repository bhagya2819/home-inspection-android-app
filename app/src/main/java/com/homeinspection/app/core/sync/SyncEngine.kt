package com.homeinspection.app.core.sync

import androidx.room.withTransaction
import com.homeinspection.app.BuildConfig
import com.homeinspection.app.core.auth.AuthRepository
import com.homeinspection.app.core.auth.SessionStore
import com.homeinspection.app.core.database.HomeInspectionDatabase
import com.homeinspection.app.core.database.PhotoEntity
import com.homeinspection.app.core.database.ReportEntity
import com.homeinspection.app.core.database.SyncOutboxEntity
import com.homeinspection.app.core.model.SyncEntity
import com.homeinspection.app.core.model.SyncOperation
import com.homeinspection.app.core.model.UploadState
import com.homeinspection.app.core.network.ApiClient
import com.homeinspection.app.core.network.ApiException
import com.homeinspection.app.core.report.ReportContentQueue
import com.homeinspection.app.core.retention.RetentionManager
import com.homeinspection.app.core.storage.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import kotlin.random.Random

enum class SyncOutcome { DONE, OFFLINE, NEEDS_LOGIN, FAILED, LOCKED }

/**
 * Bumped whenever an original arrives from the server. Image views key on it: Coil doesn't retry a
 * file that was missing when first shown, so without this a downloaded photo only appeared after
 * reopening the screen.
 */
object PhotoArrivals {
    val count = MutableStateFlow(0)
}

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastOutcome: SyncOutcome? = null,
    val message: String? = null,
)

/**
 * Drains the outbox to the server and pulls everyone else's changes (PRD §8.2).
 *
 * The outbox table is the source of truth for pending work; this class only decides when to
 * act on it, so a crash or kill at any point loses nothing — the next run picks up where this
 * one stopped. Order per run:
 *  1. metadata pushes (inspections → rooms → findings → photos → reports), batched;
 *  2. photo originals, one at a time, each verified by the server's SHA-256 before the
 *     photo is shown as "Backed up";
 *  3. finalized report PDFs, verified the same way, then other features' queues (attendance);
 *  4. pull of changes since our cursor, applied in one transaction;
 *  5. best effort: retention policy, phone cleanup (PRD §12) and this phone's status report.
 */
class SyncEngine(
    private val db: HomeInspectionDatabase,
    private val api: ApiClient,
    private val auth: AuthRepository,
    private val store: SessionStore,
    private val photoStore: PhotoStore,
    private val filesDir: File,
    private val retention: RetentionManager,
    /** Developer lock still on after asking the server: sync does nothing at all (see AppLock). */
    private val isAppLocked: suspend () -> Boolean = { false },
    /** Extra best-effort work after a successful sync (admin storage warning). */
    private val onSynced: suspend () -> Unit = {},
    /** Server settings fetched after a sync, for other features (AI on/off). */
    private val onSettings: (JsonObject) -> Unit = {},
    /** Other features' own upload queues (attendance), run after reports. Same error rules as uploads. */
    private val extraPush: suspend () -> Unit = {},
    /** When those queues next have work, so a retry job wakes for it even with an empty outbox. */
    private val extraNextDueAt: suspend () -> Long? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val outbox = db.outboxDao()
    private val mutex = Mutex()
    @Volatile private var again = false

    private val _status = MutableStateFlow(SyncStatus(lastSyncAt = store.lastSyncAt))
    val status = _status.asStateFlow()

    /** Runs a full sync. If one is already running, it runs once more afterwards instead. */
    suspend fun sync(): SyncOutcome {
        if (mutex.isLocked) again = true
        return mutex.withLock {
            var outcome: SyncOutcome
            do {
                again = false
                outcome = runOnce()
            } while (again && outcome == SyncOutcome.DONE)
            outcome
        }
    }

    /** Earliest time queued work becomes due, or null if nothing waits (used to schedule retries). */
    suspend fun nextDueAt(): Long? = listOfNotNull(outbox.nextDueAt(), extraNextDueAt(), contentDueAt()).minOrNull()

    /**
     * Report contents only count once their version's PDF is on the server: before that the report's
     * own upload wakes the job, and afterwards the server always knows the version (no endless 404s).
     */
    private suspend fun contentDueAt(): Long? = ReportContentQueue.pending(filesDir)
        .filter { db.reportDao().get(ReportContentQueue.reportId(it))?.backupVerifiedAt != null }
        .minOfOrNull { it.lastModified() + ReportContentQueue.RETRY_MS }

    private suspend fun runOnce(): SyncOutcome {
        if (isAppLocked()) return finish(SyncOutcome.LOCKED, null)
        if (auth.token() == null) {
            val online = try { auth.completeOnlineSignIn() } catch (_: IOException) { false }
            if (!online) return finish(SyncOutcome.NEEDS_LOGIN, "Sign in online to back up and sync.")
        }
        _status.value = _status.value.copy(running = true, message = null)
        return try {
            pushMetadata()
            pushNewPhotoMetadata()
            uploadPhotos()
            uploadReports()
            extraPush()
            pull()
            auth.onServerContact()
            store.lastSyncAt = clock()
            afterSync()
            finish(SyncOutcome.DONE, null)
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(running = false)
            throw e
        } catch (e: ApiException) {
            if (e.isAppLocked) {
                finish(SyncOutcome.LOCKED, null) // queue untouched; it all goes up after an unlock
            } else if (e.isAuthFailure) {
                auth.onSessionInvalid()
                finish(SyncOutcome.NEEDS_LOGIN, "Your session ended. Sign in again to sync.")
            } else if (e.needsPasswordChange) {
                finish(SyncOutcome.NEEDS_LOGIN, "Choose your own password to start syncing. Your work is saved on this phone.")
            } else {
                finish(SyncOutcome.FAILED, e.message)
            }
        } catch (e: IOException) {
            finish(SyncOutcome.OFFLINE, "No connection to the Home Inspection server. Everything is saved on this phone and will upload later.")
        } catch (e: Exception) {
            finish(SyncOutcome.FAILED, "Sync error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun finish(outcome: SyncOutcome, message: String?): SyncOutcome {
        _status.value = SyncStatus(running = false, lastSyncAt = store.lastSyncAt, lastOutcome = outcome, message = message)
        return outcome
    }

    // ---- 1. Metadata -------------------------------------------------------------------------

    private suspend fun pushMetadata() {
        repeat(50) { // bounded: each round either completes rows or pushes their next attempt later
            val rows = outbox.due(SyncOperation.UPSERT, clock(), BATCH).sortedBy { RANK.indexOf(it.entityType) }
            if (rows.isEmpty()) return
            val sent = ArrayList<SyncOutboxEntity>()
            val payloads = ArrayList<JsonObject>()
            for (row in rows) {
                val (revision, data) = payload(row) ?: run {
                    outbox.complete(row.id, row.payloadVersion) // record vanished: nothing to send
                    null
                } ?: continue
                sent += row
                payloads += buildJsonObject {
                    put("type", row.entityType.name)
                    put("id", row.entityId)
                    put("base_revision", revision)
                    put("mutation_id", "${row.idempotencyKey}:${row.payloadVersion}")
                    put("data", data)
                }
            }
            if (sent.isEmpty()) return@repeat
            val items = buildJsonArray { payloads.forEach { add(it) } }
            val results = api.post("/v1/sync/push", buildJsonObject { put("items", items) })["results"]!!.jsonArray
            val now = clock()
            sent.forEachIndexed { i, row ->
                val r = results[i].jsonObject
                val code = r["code"]?.jsonPrimitive?.content
                val message = r["message"]?.jsonPrimitive?.content
                when (r["status"]!!.jsonPrimitive.content) {
                    "OK" -> db.withTransaction {
                        setRevision(row.entityType, row.entityId, r["revision"]!!.jsonPrimitive.long)
                        outbox.complete(row.id, row.payloadVersion)
                    }
                    "CONFLICT" -> outbox.fail(row.id, now, "CONFLICT", message, r["server"]?.toString(), now)
                    // Validation errors don't fix themselves; a later edit bumps the row and retries sooner.
                    "REJECTED" -> outbox.fail(row.id, now + 6 * HOUR, code ?: "REJECTED", message, null, now)
                    else -> outbox.fail(row.id, now + backoff(row.attemptCount), code ?: "RETRY", message, null, now)
                }
            }
            if (rows.size < BATCH) return
        }
    }

    /**
     * A new photo is queued only as UPLOAD_ORIGINAL (one row per capture, M1 invariant), but the
     * server needs its metadata before it accepts the bytes. Send it here for photos the server
     * has never acknowledged (revision 0). The mutation id is derived from the upload row, so a
     * retry after a lost response is recognised by the server and not applied twice.
     */
    private suspend fun pushNewPhotoMetadata() {
        val rows = outbox.due(SyncOperation.UPLOAD_ORIGINAL, clock(), 500)
        val pending = rows.mapNotNull { row ->
            val photo = db.photoDao().get(row.entityId) ?: return@mapNotNull null
            if (photo.revision > 0 || outbox.hasPendingUpsert(SyncEntity.PHOTO, photo.id)) null else row to photo
        }
        for (chunk in pending.chunked(BATCH)) {
            val items = buildJsonArray {
                chunk.forEach { (row, photo) ->
                    add(buildJsonObject {
                        put("type", SyncEntity.PHOTO.name)
                        put("id", photo.id)
                        put("base_revision", 0L)
                        put("mutation_id", "${row.idempotencyKey}:meta")
                        put("data", SyncCodec.photo(photo))
                    })
                }
            }
            val results = api.post("/v1/sync/push", buildJsonObject { put("items", items) })["results"]!!.jsonArray
            val now = clock()
            chunk.forEachIndexed { i, (row, photo) ->
                val r = results[i].jsonObject
                when (r["status"]!!.jsonPrimitive.content) {
                    "OK" -> db.photoDao().setRevision(photo.id, r["revision"]!!.jsonPrimitive.long)
                    // Already on the server from an earlier attempt: adopt its revision.
                    "CONFLICT" -> r["revision"]?.jsonPrimitive?.long?.let { db.photoDao().setRevision(photo.id, it) }
                    else -> outbox.fail(
                        row.id, now + backoff(row.attemptCount), r["code"]?.jsonPrimitive?.content ?: "RETRY",
                        r["message"]?.jsonPrimitive?.content, null, now,
                    )
                }
            }
        }
    }

    /** Current row as (base revision, JSON data), or null if it no longer exists locally. */
    private suspend fun payload(row: SyncOutboxEntity): Pair<Long, JsonObject>? = when (row.entityType) {
        SyncEntity.INSPECTION -> db.inspectionDao().get(row.entityId)?.let { it.revision to SyncCodec.inspection(it) }
        SyncEntity.ROOM -> db.roomAreaDao().get(row.entityId)?.let { it.revision to SyncCodec.room(it) }
        SyncEntity.FINDING -> db.findingDao().get(row.entityId)?.let { it.revision to SyncCodec.finding(it) }
        SyncEntity.PHOTO -> db.photoDao().get(row.entityId)?.let { it.revision to SyncCodec.photo(it) }
        SyncEntity.REPORT -> db.reportDao().get(row.entityId)?.let { it.revision to SyncCodec.report(it, File(filesDir, it.localPdfPath).length()) }
    }

    private suspend fun setRevision(type: SyncEntity, id: String, revision: Long) = when (type) {
        SyncEntity.INSPECTION -> db.inspectionDao().setRevision(id, revision)
        SyncEntity.ROOM -> db.roomAreaDao().setRevision(id, revision)
        SyncEntity.FINDING -> db.findingDao().setRevision(id, revision)
        SyncEntity.PHOTO -> db.photoDao().setRevision(id, revision)
        SyncEntity.REPORT -> db.reportDao().setRevision(id, revision)
    }

    // ---- 2. Photo originals ------------------------------------------------------------------

    private suspend fun uploadPhotos() {
        val photos = db.photoDao()
        for (row in outbox.due(SyncOperation.UPLOAD_ORIGINAL, clock(), 500)) {
            val photo = photos.get(row.entityId)
            if (photo == null) {
                outbox.complete(row.id, row.payloadVersion)
                continue
            }
            // The server only accepts bytes for a photo whose metadata it already has.
            if (photo.revision == 0L || outbox.hasPendingUpsert(SyncEntity.PHOTO, photo.id)) continue
            val file = photoStore.resolve(photo.localOriginalPath)
            val now = clock()
            if (!file.isFile) {
                outbox.fail(row.id, now + 6 * HOUR, "FILE_MISSING", "The original photo file is missing on this phone.", null, now)
                photos.setUploadState(photo.id, UploadState.RETRY_REQUIRED.name)
                continue
            }
            photos.setUploadState(photo.id, UploadState.UPLOADING.name)
            try {
                val res = api.putFile("/v1/photos/${photo.id}/original", file, photo.mimeType)
                val checksum = res["sha256"]!!.jsonPrimitive.content
                // Belt and braces: only call it backed up if the server hashed the same bytes we hold.
                if (!checksum.equals(photo.sha256, ignoreCase = true)) throw IOException("Server checksum differs")
                db.withTransaction {
                    photos.markBackedUp(photo.id, res["object_key"]!!.jsonPrimitive.content, res["verified_at"]!!.jsonPrimitive.long, checksum)
                    outbox.complete(row.id, row.payloadVersion)
                }
            } catch (e: ApiException) {
                // Developer lock: not this photo's fault; it goes back to waiting, untouched.
                if (e.isAppLocked) { photos.setUploadState(photo.id, photo.uploadState.name); throw e }
                photos.setUploadState(photo.id, UploadState.RETRY_REQUIRED.name)
                if (e.isAuthFailure || e.needsPasswordChange) throw e
                val next = if (e.code == "METADATA_MISSING") 30_000L else backoff(row.attemptCount)
                outbox.fail(row.id, clock() + next, e.code, e.message, null, clock())
            } catch (e: IOException) {
                photos.setUploadState(photo.id, UploadState.RETRY_REQUIRED.name)
                outbox.fail(row.id, clock() + backoff(row.attemptCount), "NETWORK", e.message, null, clock())
                throw e // connection lost: stop this run; the scheduler retries when online
            }
        }
    }

    // ---- 3. Report PDFs ----------------------------------------------------------------------

    private suspend fun uploadReports() {
        for (report in db.reportDao().awaitingUpload()) {
            val file = File(filesDir, report.localPdfPath)
            if (!file.isFile) continue // finalized on another phone; that phone uploads it
            val res = api.putFile("/v1/reports/${report.id}/pdf", file, "application/pdf")
            if (res["sha256"]!!.jsonPrimitive.content.equals(report.contentHash, ignoreCase = true)) {
                db.reportDao().markBackedUp(report.id, res["object_key"]!!.jsonPrimitive.content, res["verified_at"]!!.jsonPrimitive.long)
            }
        }
        // What each version showed, for its Word copy on the server (ReportContentQueue).
        for (file in ReportContentQueue.pending(filesDir)) {
            if (db.reportDao().get(ReportContentQueue.reportId(file)) == null) {
                file.delete() // its version was never recorded (crash between the two)
                continue
            }
            try {
                api.putFile("/v1/reports/${ReportContentQueue.reportId(file)}/content", file, "application/json")
                file.delete()
            } catch (e: ApiException) {
                if (e.isAppLocked || e.isAuthFailure || e.needsPasswordChange) throw e
                // 404: the version's metadata isn't on the server yet. 409: it already holds this version's
                // content. Other 4xx can't succeed; the Word copy then uses the server's live data.
                if (e.status == 404 || (!e.isPermanent && e.status != 409)) ReportContentQueue.postpone(file) else file.delete()
            } catch (e: IOException) {
                ReportContentQueue.postpone(file)
                throw e
            }
        }
    }

    // ---- 4. Pull -----------------------------------------------------------------------------

    private suspend fun pull() {
        var cursor = store.syncCursor
        val changes = ArrayList<Pair<String, JsonObject>>()
        for (pageNo in 0 until 200) { // up to 100k records per run; the rest comes next run
            val page = api.get("/v1/sync/changes?cursor=$cursor&limit=500")
            page["changes"]!!.jsonArray.forEach { c ->
                val o = c.jsonObject
                changes += o["type"]!!.jsonPrimitive.content to o["data"]!!.jsonObject
            }
            cursor = page["cursor"]!!.jsonPrimitive.long
            if (page["has_more"]?.jsonPrimitive?.content != "true") break
        }
        // Pages are applied together, parents first, so a room never arrives before its
        // inspection (foreign keys) even when the inspection's latest edit is on a later page.
        db.withTransaction { changes.sortedBy { PULL_RANK.indexOf(it.first) }.forEach { (t, d) -> applyRemote(t, d) } }
        store.syncCursor = cursor
    }

    private suspend fun applyRemote(type: String, o: JsonObject) {
        val id = o["id"]!!.jsonPrimitive.content
        if (type == "USER") return db.userDao().upsert(SyncCodec.userFrom(o))
        val entity = SyncEntity.entries.firstOrNull { it.name == type } ?: return
        // A local edit not yet pushed wins for now; its push will surface a conflict if needed.
        if (outbox.hasPendingUpsert(entity, id)) return
        when (entity) {
            SyncEntity.INSPECTION -> db.inspectionDao().upsert(SyncCodec.inspectionFrom(o))
            SyncEntity.ROOM -> db.roomAreaDao().upsert(SyncCodec.roomFrom(o))
            SyncEntity.FINDING -> db.findingDao().upsert(SyncCodec.findingFrom(o))
            SyncEntity.PHOTO -> {
                val existing = db.photoDao().get(id)
                val merged = SyncCodec.photoFrom(o, existing, "photos/originals/${o["inspection_id"]!!.jsonPrimitive.content}/$id.jpg")
                if (existing == null) db.photoDao().insert(merged) else db.photoDao().update(merged)
            }
            SyncEntity.REPORT -> {
                val existing = db.reportDao().get(id)
                val path = "reports/${o["inspection_id"]!!.jsonPrimitive.content}/report-$id.pdf"
                db.reportDao().upsert(SyncCodec.reportFrom(o, existing, path))
            }
        }
    }

    // ---- 5. After a successful sync ---------------------------------------------------------

    /** None of this may fail a sync whose uploads already succeeded; only a lost session stops it. */
    private suspend fun afterSync() {
        bestEffort { auth.revokePendingSessions() }
        bestEffort {
            val settings = api.get("/v1/settings")
            retention.applyServerSettings(settings)
            onSettings(settings)
        }
        bestEffort { retention.runIfDue() }
        bestEffort { reportDevice() }
        bestEffort { onSynced() }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isAuthFailure || e.needsPasswordChange || e.isAppLocked) throw e
        } catch (_: Exception) {
            // Offline, an older server without the endpoint, or a local hiccup: next sync retries.
        }
    }

    /**
     * Tells the server what this phone still has queued and what keeps failing (the admin's "failed
     * sync items" view), and uploads local audit events (cleanup, finalization) once each.
     */
    private suspend fun reportDevice() {
        val problems = outbox.problems(50)
        val events = db.auditDao().notUploaded(200)
        val storage = retention.overview()
        val body = buildJsonObject {
            put("app_version", BuildConfig.VERSION_NAME)
            put("pending_records", outbox.countFor(SyncOperation.UPSERT))
            put("pending_photos", outbox.countFor(SyncOperation.UPLOAD_ORIGINAL))
            put("free_bytes", photoStore.freeBytes())
            put("local_originals", storage.localPhotos)
            put("local_originals_bytes", storage.localBytes)
            put("cleanup_blocked", storage.blocked.sumOf { it.localPhotos })
            put("problems", buildJsonArray {
                problems.forEach { p ->
                    add(buildJsonObject {
                        put("type", p.entityType.name); put("id", p.entityId); put("op", p.operation.name)
                        put("code", p.lastErrorCode); put("message", p.lastErrorMessage); put("attempts", p.attemptCount)
                    })
                }
            })
            put("audit", buildJsonArray {
                events.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id); put("type", e.type); put("entity_type", e.entityType.orEmpty())
                        put("entity_id", e.entityId.orEmpty()); put("details", e.details.orEmpty()); put("created_at", e.createdAt)
                    })
                }
            })
        }
        val accepted = api.post("/v1/devices/report", body)["accepted"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (accepted.isNotEmpty()) db.auditDao().markUploaded(accepted, clock())
    }

    // ---- Conflicts ---------------------------------------------------------------------------

    /** "Keep mine": overwrite the server copy with this phone's version on the next push. */
    suspend fun keepMine(row: SyncOutboxEntity) {
        val server = row.conflictPayload?.let { Json.parseToJsonElement(it).jsonObject } ?: return
        db.withTransaction {
            setRevision(row.entityType, row.entityId, server["revision"]!!.jsonPrimitive.long)
            outbox.clearError(row.id, clock())
        }
    }

    /** "Use theirs": replace this phone's version with the server copy and drop the local change. */
    suspend fun useServer(row: SyncOutboxEntity) {
        val server = row.conflictPayload?.let { Json.parseToJsonElement(it).jsonObject } ?: return
        db.withTransaction {
            outbox.complete(row.id, row.payloadVersion)
            applyRemote(row.entityType.name, server)
        }
    }

    // ---- Downloads for records made on other phones ------------------------------------------

    /**
     * Fetches originals of [photos] that were taken on another phone (resumable, SHA-256 checked,
     * atomic rename — same durability rule as a capture). Returns how many are now present.
     *
     * Originals removed by retention cleanup are fetched back only when [restoreRemoved] (a report
     * has to be rendered from them); browsing an inspection must not undo the cleanup. Archived
     * photos are not on the server any more and are never requested.
     */
    suspend fun ensurePhotosLocal(photos: List<PhotoEntity>, restoreRemoved: Boolean = false): Int {
        var present = 0
        for (p in photos) {
            val target = photoStore.resolve(p.localOriginalPath)
            if (target.isFile) { present++; continue }
            if (p.backupVerifiedAt == null || p.archivedAt != null || auth.token() == null) continue
            if (p.originalDeletedAt != null && !restoreRemoved) continue
            when (download("/v1/photos/${p.id}/original", target, p.sha256)) {
                Fetch.OK -> {
                    present++
                    PhotoArrivals.count.value++
                    if (p.originalDeletedAt != null) db.photoDao().markOriginalRestored(p.id)
                }
                Fetch.ARCHIVED -> db.photoDao().markArchived(p.id, clock())
                Fetch.FAILED -> Unit
            }
        }
        return present
    }

    suspend fun ensureReportLocal(r: ReportEntity): Boolean {
        val target = File(filesDir, r.localPdfPath)
        if (target.isFile) return true
        if (r.backupVerifiedAt == null || r.archivedAt != null || auth.token() == null) return false
        return when (download("/v1/reports/${r.id}/pdf", target, r.contentHash)) {
            Fetch.OK -> true
            Fetch.ARCHIVED -> { db.reportDao().markArchived(r.id, clock()); false }
            Fetch.FAILED -> false
        }
    }

    private enum class Fetch { OK, ARCHIVED, FAILED }

    /**
     * One download at a time: two screens asking for the same PDF used to append to the same
     * partial file at once, leaving it longer than the real file and every later resume failing.
     */
    private val downloadLock = Mutex()

    private suspend fun download(path: String, target: File, sha256: String): Fetch = downloadLock.withLock {
        if (target.isFile) return@withLock Fetch.OK // fetched by the caller that held the lock before us
        target.parentFile?.mkdirs()
        // Partials live outside photos/originals so startup crash recovery never mistakes them for evidence.
        val part = File(filesDir, "downloads/${target.name}.part").apply { parentFile?.mkdirs() }
        for (attempt in 0..1) {
            try {
                api.download(path, part)
                return@withLock if (PhotoStore.sha256Of(part).equals(sha256, ignoreCase = true)) {
                    java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    Fetch.OK
                } else {
                    part.delete() // corrupt or mixed resume: start over next time
                    Fetch.FAILED
                }
            } catch (e: ApiException) {
                if (e.isAuthFailure) auth.onSessionInvalid()
                when {
                    e.isAppLocked -> return@withLock Fetch.FAILED // keep the partial; resumes after an unlock
                    e.code == "ARCHIVED" -> { part.delete(); return@withLock Fetch.ARCHIVED }
                    // 416: our partial is already as long as (or longer than) the file. Discard, start again.
                    e.status == 416 && attempt == 0 -> part.delete()
                    else -> { if (e.status == 416) part.delete(); return@withLock Fetch.FAILED }
                }
            } catch (_: IOException) {
                return@withLock Fetch.FAILED // keep the partial file; the next attempt resumes it
            }
        }
        Fetch.FAILED
    }

    private fun backoff(attempts: Int): Long {
        val base = minOf(6 * HOUR, 30_000L shl minOf(attempts, 12))
        return (base * Random.nextDouble(0.5, 1.5)).toLong() // jitter, so phones don't retry in lockstep
    }

    private companion object {
        const val BATCH = 200
        const val HOUR = 60 * 60 * 1000L
        val RANK = listOf(SyncEntity.INSPECTION, SyncEntity.ROOM, SyncEntity.FINDING, SyncEntity.PHOTO, SyncEntity.REPORT)
        val PULL_RANK = listOf("USER", "INSPECTION", "ROOM", "FINDING", "PHOTO", "REPORT")
    }
}
