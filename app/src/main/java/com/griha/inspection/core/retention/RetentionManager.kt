package com.griha.inspection.core.retention

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.griha.inspection.core.database.AuditEventEntity
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.database.PhotoEntity
import com.griha.inspection.core.network.ApiClient
import com.griha.inspection.core.storage.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Server-managed retention policy (GET /v1/settings), cached for offline use. */
data class RetentionSettings(
    val retentionDays: Int = 30,
    val policyVersion: String = "retention-30d",
    val exportWarnBytes: Long = 5_000_000_000,
)

data class InspectionRetention(
    val inspectionId: String,
    val clientName: String,
    val localPhotos: Int,
    val localBytes: Long,
    val verdict: Verdict,
)

data class RetentionOverview(
    val localPhotos: Int,
    val localBytes: Long,
    val removedPhotos: Int,
    val items: List<InspectionRetention>,
) {
    val eligible get() = items.filter { it.verdict is Verdict.Eligible }
    val blocked get() = items.filter { it.verdict is Verdict.Blocked }
}

data class CleanupResult(val removed: Int, val freedBytes: Long, val message: String)

/**
 * Phone-side 30-day cleanup of original photos (PRD §5.9, §12, §16.4).
 *
 * Nothing is removed until the user has turned automatic cleanup on after reading the policy.
 * Each run: evaluate every inspection with originals on the phone → ask the server to confirm its
 * copies right now → for each photo, re-run the full predicate inside a DB transaction that also
 * marks the photo removed and writes the audit event → only then delete the file. A crash between
 * commit and delete leaves a file the next run sweeps; never a record that claims a file it lost.
 */
class RetentionManager(
    private val db: GrihaDatabase,
    private val photoStore: PhotoStore,
    /** Asks the server about photo ids right now; see [serverCheckVia]. Throws IOException when offline. */
    private val serverCheck: suspend (List<String>) -> Map<String, String>,
    private val prefs: SharedPreferences,
    private val deviceId: () -> String,
    private val currentUserId: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val inUse = ConcurrentHashMap<String, AtomicInteger>()

    private val _settings = MutableStateFlow(loadSettings())
    val settings = _settings.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, false))
    /** Automatic cleanup is on: the user has seen the policy and agreed (PRD §5.9 condition 6). */
    val enabled = _enabled.asStateFlow()

    private val _lastResult = MutableStateFlow(prefs.getString(K_LAST_MESSAGE, null))
    val lastResult = _lastResult.asStateFlow()

    val lastRunAt: Long get() = prefs.getLong(K_LAST_RUN, 0)

    fun applyServerSettings(o: JsonObject) {
        val s = RetentionSettings(
            retentionDays = o["retention_days"]?.jsonPrimitive?.int ?: 30,
            policyVersion = o["policy_version"]?.jsonPrimitive?.content ?: "retention-30d",
            exportWarnBytes = o["export_warn_bytes"]?.jsonPrimitive?.long ?: 5_000_000_000,
        )
        if (s != _settings.value) {
            prefs.edit().putInt(K_DAYS, s.retentionDays).putString(K_POLICY, s.policyVersion).putLong(K_WARN, s.exportWarnBytes).apply()
            _settings.value = s
        }
    }

    private fun loadSettings() = RetentionSettings(
        retentionDays = prefs.getInt(K_DAYS, 30),
        policyVersion = prefs.getString(K_POLICY, null) ?: "retention-30d",
        exportWarnBytes = prefs.getLong(K_WARN, 5_000_000_000),
    )

    suspend fun setEnabled(on: Boolean) {
        if (on == _enabled.value) return
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        _enabled.value = on
        audit(if (on) "RETENTION_POLICY_ACKNOWLEDGED" else "RETENTION_CLEANUP_TURNED_OFF", null, null, "policy=${settings.value.policyVersion}")
    }

    /** Marks [inspectionId]'s originals as needed for the duration of [block] (report rendering). */
    suspend fun <T> using(inspectionId: String, block: suspend () -> T): T {
        inUse.computeIfAbsent(inspectionId) { AtomicInteger() }.incrementAndGet()
        try {
            return block()
        } finally {
            inUse[inspectionId]?.decrementAndGet()
        }
    }

    private fun isInUse(inspectionId: String) = (inUse[inspectionId]?.get() ?: 0) > 0

    /** What is on this phone and, per inspection, when it can be cleared or why not. */
    suspend fun overview(): RetentionOverview = withContext(Dispatchers.IO) {
        val local = db.photoDao().withLocalOriginal().filter { photoStore.resolve(it.localOriginalPath).isFile }
        val items = local.groupBy { it.inspectionId }.map { (inspectionId, photos) ->
            InspectionRetention(
                inspectionId = inspectionId,
                clientName = db.inspectionDao().get(inspectionId)?.clientName ?: "Inspection",
                localPhotos = photos.size,
                localBytes = photos.sumOf { it.fileSizeBytes },
                verdict = verdictFor(inspectionId),
            )
        }.sortedWith(compareBy({ order(it.verdict) }, { it.clientName }))
        RetentionOverview(
            localPhotos = local.size,
            localBytes = local.sumOf { it.fileSizeBytes },
            removedPhotos = db.photoDao().withRemovedOriginal().size,
            items = items,
        )
    }

    private fun order(v: Verdict) = when (v) {
        is Verdict.Eligible -> 0
        is Verdict.Waiting -> 1
        is Verdict.Blocked -> 2
    }

    private suspend fun verdictFor(inspectionId: String): Verdict = RetentionPolicy.evaluate(
        inspection = db.inspectionDao().get(inspectionId),
        photos = db.photoDao().everyForInspection(inspectionId),
        reports = db.reportDao().allForInspection(inspectionId),
        pendingOutbox = db.outboxDao().pendingForInspection(inspectionId),
        inUse = isInUse(inspectionId),
        retentionDays = settings.value.retentionDays,
        now = clock(),
    )

    /** Called after each successful sync: runs at most every 12 hours, and only when turned on. */
    suspend fun runIfDue(): CleanupResult? {
        if (!enabled.value || clock() - lastRunAt < RUN_INTERVAL_MS) return null
        return cleanNow()
    }

    /** Needs the server (it confirms its copies first); returns what happened, also kept for the UI. */
    suspend fun cleanNow(): CleanupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val result = if (!enabled.value) {
                CleanupResult(0, 0, "Automatic cleanup is off.")
            } else {
                sweepLeftovers()
                clean()
            }
            prefs.edit().putLong(K_LAST_RUN, clock()).putString(K_LAST_MESSAGE, result.message).apply()
            _lastResult.value = result.message
            result
        }
    }

    private suspend fun clean(): CleanupResult {
        val candidates = overview().eligible
        if (candidates.isEmpty()) return CleanupResult(0, 0, "Nothing to clear yet.")
        var removed = 0
        var freed = 0L
        var serverProblems = 0
        for (item in candidates) {
            val photos = db.photoDao().everyForInspection(item.inspectionId)
            val check = try {
                serverCheck(photos.map { it.id })
            } catch (e: IOException) {
                return CleanupResult(removed, freed, "Couldn't reach the server to confirm backups; nothing more was removed. Will retry.")
            }
            // PRD §16.4: a copy the server once verified but can't confirm now blocks cleanup and is flagged.
            val bad = photos.filter { p ->
                val status = check[p.id]
                !(status == "OK" || (status == "ARCHIVED" && p.exportVerifiedAt != null))
            }
            if (bad.isNotEmpty()) {
                serverProblems += bad.size
                audit("RETENTION_BLOCKED_SERVER_COPY", "INSPECTION", item.inspectionId,
                    bad.joinToString(" ") { "${it.id}=${check[it.id] ?: "UNKNOWN"}" }.take(900))
                continue
            }
            for (photo in photos) {
                val file = photoStore.resolve(photo.localOriginalPath)
                if (photo.originalDeletedAt != null || !file.isFile) continue
                if (removeOne(photo, check[photo.id]!!)) {
                    if (file.delete()) freed += photo.fileSizeBytes
                    removed++
                }
            }
        }
        val mb = freed / 1_000_000
        val message = buildString {
            append(if (removed == 0) "No originals were removed." else "Removed $removed original${if (removed == 1) "" else "s"} ($mb MB) from this phone.")
            if (serverProblems > 0) append(" $serverProblems photo${if (serverProblems == 1) "" else "s"} kept: the server could not confirm its copy — tell your admin.")
        }
        return CleanupResult(removed, freed, message)
    }

    /** Re-checks the complete predicate at deletion time and records the removal atomically. */
    private suspend fun removeOne(photo: PhotoEntity, serverStatus: String): Boolean = db.withTransaction {
        val verdict = verdictFor(photo.inspectionId)
        if (verdict !is Verdict.Eligible) return@withTransaction false
        val now = clock()
        if (db.photoDao().markOriginalRemoved(photo.id, verdict.eligibleAt, now) != 1) return@withTransaction false
        val fresh = db.photoDao().get(photo.id)!!
        db.auditDao().insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                type = "PHOTO_ORIGINAL_DELETED",
                entityType = "PHOTO",
                entityId = photo.id,
                details = "inspection=${photo.inspectionId} policy=${settings.value.policyVersion} device=${deviceId()} " +
                    "bytes=${fresh.fileSizeBytes} sha256=${fresh.sha256} backup_verified_at=${fresh.backupVerifiedAt} " +
                    "object=${fresh.serverObjectKey} export_verified_at=${fresh.exportVerifiedAt} server_check=$serverStatus " +
                    "eligible_at=${verdict.eligibleAt}",
                userId = currentUserId(),
                createdAt = now,
            )
        )
        true
    }

    /** Files of photos already recorded as removed (an earlier run was killed before deleting). */
    private suspend fun sweepLeftovers() {
        db.photoDao().withRemovedOriginal().forEach { photoStore.resolve(it.localOriginalPath).takeIf { f -> f.isFile }?.delete() }
    }

    private suspend fun audit(type: String, entityType: String?, entityId: String?, details: String) {
        db.auditDao().insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(), type = type, entityType = entityType, entityId = entityId,
                details = details, userId = currentUserId(), createdAt = clock(),
            )
        )
    }

    companion object {
        /** Whether the server holds each photo right now: OK, ARCHIVED, MISSING, NOT_VERIFIED, SIZE_MISMATCH. */
        fun serverCheckVia(api: ApiClient): suspend (List<String>) -> Map<String, String> = { ids ->
            val out = HashMap<String, String>()
            for (chunk in ids.chunked(500)) {
                val res = api.post("/v1/photos/check", buildJsonObject { put("ids", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } }) })
                res["results"]?.jsonObject?.forEach { (id, v) -> out[id] = v.jsonObject["status"]!!.jsonPrimitive.content }
            }
            out
        }

        private const val RUN_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val K_ENABLED = "cleanup_enabled"
        private const val K_DAYS = "retention_days"
        private const val K_POLICY = "policy_version"
        private const val K_WARN = "export_warn_bytes"
        private const val K_LAST_RUN = "last_cleanup_at"
        private const val K_LAST_MESSAGE = "last_cleanup_message"
    }
}
