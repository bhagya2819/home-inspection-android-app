package com.griha.inspection.core.attendance

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import com.griha.inspection.core.database.AttendanceEntity
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.network.ApiClient
import com.griha.inspection.core.network.ApiException
import com.griha.inspection.core.storage.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TimeZone
import kotlin.random.Random

/** Where and when the phone was at a check-in/out. Null fields = no fix in time. */
data class PlaceFix(val lat: Double, val lng: Double, val accuracyM: Float, val at: Long, val isMock: Boolean)

/** A face enrolment as the admin reviews it. */
@Serializable
data class FaceEnrollment(
    val id: String,
    val userId: String,
    val employeeCode: String = "",
    val displayName: String = "",
    val status: String,
    val shots: Int = 0,
    val hasPhoto: Boolean = false,
    val createdAt: Long = 0,
    val decidedAt: Long? = null,
)

/** What the face check decided, to be stored with the record. */
data class FaceOutcome(val result: String, val score: Float?, val enrollmentId: String?, val livenessPassed: Boolean)

/** A record as the server returns it (own history, or the admin's list). */
@Serializable
data class AttendanceRecord(
    val id: String,
    val userId: String = "",
    val employeeCode: String = "",
    val displayName: String = "",
    val kind: String,
    val clientAt: Long,
    val timeZone: String = "",
    val receivedAt: Long = 0,
    val estimatedAt: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val isMock: Boolean = false,
    val faceResult: String,
    val faceScore: Float? = null,
    val enrollmentId: String? = null,
    val livenessPassed: Boolean = false,
    /** AVAILABLE | UPLOADING | DELETED; LOCAL = still only on this phone. */
    val selfie: String = "AVAILABLE",
) {
    val isIn get() = kind == AttendanceEntity.KIND_IN
    /** The phone's clock disagreed with the server's estimate by more than 5 minutes. */
    val clockSuspect get() = estimatedAt != null && kotlin.math.abs(estimatedAt - clientAt) > 5 * 60_000L
}

/**
 * Check-in/out records (feature 1). Saving follows the photo rule: the selfie is written to a temp
 * file, fsynced, hashed and atomically renamed, and only then is the row inserted — the row is also
 * its own upload queue. After the server verifies a selfie, the phone copy is removed (the server
 * keeps it 90 days for the admin).
 */
class AttendanceRepository(
    private val context: Context,
    private val db: GrihaDatabase,
    private val api: ApiClient,
    private val currentUserId: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.attendanceDao()
    private val filesDir = context.filesDir
    private val dir = File(filesDir, "attendance")
    private val tmpDir = File(dir, "tmp")

    fun observeLatest(userId: String?): Flow<AttendanceEntity?> = userId?.let(dao::observeLatest) ?: flowOf(null)

    fun observeRange(userId: String, from: Long, to: Long) = dao.observeRange(userId, from, to)

    val pendingCount get() = dao.observePendingCount()

    /**
     * Saves a check-in/out. [selfie] is the exact frame the face check ran on. Returns the saved row;
     * only after this returns may the screen say "Checked in".
     */
    suspend fun save(kind: String, selfie: Bitmap, place: PlaceFix?, face: FaceOutcome, at: Long, elapsedMs: Long): AttendanceEntity =
        withContext(Dispatchers.IO) {
            val userId = checkNotNull(currentUserId()) { "Sign in to check in" }
            val id = java.util.UUID.randomUUID().toString()
            tmpDir.mkdirs()
            val tmp = File(tmpDir, "$id.jpg.part")
            FileOutputStream(tmp).use { out ->
                check(selfie.compress(Bitmap.CompressFormat.JPEG, SELFIE_QUALITY, out)) { "Could not encode the selfie" }
                out.flush()
                out.fd.sync()
            }
            val sha = PhotoStore.sha256Of(tmp)
            val target = File(dir, "$id.jpg")
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val row = AttendanceEntity(
                id = id, userId = userId, kind = kind, clientAt = at, timeZone = TimeZone.getDefault().id,
                elapsedMs = elapsedMs, bootCount = bootCount(),
                lat = place?.lat, lng = place?.lng, accuracyM = place?.accuracyM, locationAt = place?.at, isMock = place?.isMock == true,
                faceResult = face.result, faceScore = face.score, enrollmentId = face.enrollmentId, livenessPassed = face.livenessPassed,
                selfiePath = target.relativeTo(filesDir).path, selfieSha256 = sha, selfieSize = target.length(), createdAt = clock(),
            )
            try {
                dao.insert(row)
            } catch (e: Exception) {
                target.delete() // never shown as saved; don't leave an orphan selfie behind
                throw e
            }
            row
        }

    /**
     * Startup: temp files from a save the app died in (never shown as saved) and selfie files no
     * row points at are removed. Only files older than this process are touched.
     */
    suspend fun cleanupLeftovers(processStart: Long) = withContext(Dispatchers.IO) {
        tmpDir.listFiles()?.filter { it.lastModified() < processStart }?.forEach { it.delete() }
        dir.listFiles()?.filter { it.isFile && it.lastModified() < processStart }?.forEach { f ->
            val row = dao.get(f.nameWithoutExtension)
            if (row == null || row.selfiePath == null) f.delete()
        }
    }

    // ---- Upload (one step of the sync engine) ----------------------------------------------------

    /** When the signed-in engineer's next record may be sent, so the sync job wakes for it (null: nothing waits). */
    suspend fun nextDueAt(): Long? = currentUserId()?.let { dao.nextDueAt(it) }

    /**
     * Sends the signed-in engineer's due records and then their selfies. Lost connection stops the run (IOException); a lost
     * session or the developer lock is rethrown untouched, so nothing is marked failed for it.
     */
    suspend fun push() {
        val userId = currentUserId() ?: return
        for (row in dao.due(userId, clock(), 50)) {
            try {
                if (row.sentAt == null) {
                    api.post("/v1/attendance", body(row))
                    dao.markSent(row.id, clock())
                }
                if (row.selfieVerifiedAt == null) uploadSelfie(row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isAppLocked || e.isAuthFailure || e.needsPasswordChange) throw e
                val next = if (e.code == "METADATA_MISSING") 30_000L else backoff(row.attemptCount)
                dao.fail(row.id, clock() + next, e.message)
            } catch (e: IOException) {
                dao.fail(row.id, clock() + backoff(row.attemptCount), "No connection")
                throw e
            }
        }
    }

    private suspend fun uploadSelfie(row: AttendanceEntity) {
        val file = row.selfiePath?.let { File(filesDir, it) }
        if (file == null || !file.isFile) {
            dao.fail(row.id, clock() + 6 * HOUR, "The selfie file is missing on this phone.")
            return
        }
        val res = api.putFile("/v1/attendance/${row.id}/selfie", file, "image/jpeg")
        if (!res["sha256"]?.jsonPrimitive?.content.equals(row.selfieSha256, ignoreCase = true)) throw IOException("Server checksum differs")
        dao.markSelfieVerified(row.id, res["verified_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: clock())
        file.delete() // the server holds it now; the row no longer points at the file
    }

    private fun body(r: AttendanceEntity) = buildJsonObject {
        put("id", r.id)
        put("user_id", r.userId) // the server refuses a record that isn't the session's own
        put("kind", r.kind)
        put("client_at", r.clientAt)
        put("time_zone", r.timeZone)
        put("elapsed_ms", r.elapsedMs)
        put("boot_count", r.bootCount?.let(::JsonPrimitive) ?: JsonNull)
        put("now_elapsed_ms", SystemClock.elapsedRealtime())
        put("now_boot_count", bootCount()?.let(::JsonPrimitive) ?: JsonNull)
        put("lat", r.lat?.let(::JsonPrimitive) ?: JsonNull)
        put("lng", r.lng?.let(::JsonPrimitive) ?: JsonNull)
        put("accuracy_m", r.accuracyM?.let(::JsonPrimitive) ?: JsonNull)
        put("location_at", r.locationAt?.let(::JsonPrimitive) ?: JsonNull)
        put("is_mock", r.isMock)
        put("face_result", r.faceResult)
        put("face_score", r.faceScore?.let(::JsonPrimitive) ?: JsonNull)
        put("enrollment_id", r.enrollmentId?.let(::JsonPrimitive) ?: JsonNull)
        put("liveness_passed", r.livenessPassed)
        put("selfie_sha256", r.selfieSha256)
        put("selfie_size", r.selfieSize)
    }

    /** Android's boot counter; with elapsedRealtime it tells the server whether the phone rebooted. */
    private fun bootCount(): Long? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong() }.getOrNull()

    // ---- History -----------------------------------------------------------------------------

    /** The signed-in engineer's records from the server ([from], [to] epoch ms). Throws when offline. */
    suspend fun ownHistory(from: Long, to: Long): List<AttendanceRecord> = records(api.get("/v1/attendance?from=$from&to=$to"))

    /** Admin: everyone's records. */
    suspend fun allRecords(from: Long, to: Long, userId: String? = null): List<AttendanceRecord> =
        records(api.get("/v1/admin/attendance?from=$from&to=$to" + (userId?.let { "&user=$it" } ?: "")))

    /** Admin: enrolments waiting for approval first, then everyone's current approved face. */
    suspend fun enrollments(): List<FaceEnrollment> =
        api.get("/v1/admin/face/enrollments")["enrollments"]?.let { json.decodeFromJsonElement<List<FaceEnrollment>>(it) }.orEmpty()

    /** Admin: approve or reject a pending enrolment; reject on an approved one resets that face. */
    suspend fun decide(enrollmentId: String, approve: Boolean) {
        api.post("/v1/admin/face/enrollments/$enrollmentId/decision", buildJsonObject { put("approve", approve) })
    }

    /** A record's selfie (owner or admin), fetched once into the cache. Null if gone or offline. */
    suspend fun selfieFile(id: String): File? = cached("/v1/attendance/$id/selfie", "selfie-$id.jpg")

    /** An enrolment's reference photo (owner or admin), fetched once into the cache. */
    suspend fun enrollmentPhotoFile(id: String): File? = cached("/v1/face/enrollments/$id/photo", "enrol-$id.jpg")

    private suspend fun cached(path: String, name: String): File? = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "attendance-photos").apply { mkdirs() }
        val file = File(cache, name)
        if (file.isFile && file.length() > 0) return@withContext file
        val part = File(cache, "$name.part")
        try {
            part.delete() // small files: no resume, start clean
            api.download(path, part)
            part.renameTo(file)
            file
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            part.delete()
            null
        }
    }

    private fun records(o: kotlinx.serialization.json.JsonObject): List<AttendanceRecord> =
        o["records"]?.let { json.decodeFromJsonElement<List<AttendanceRecord>>(it) }.orEmpty()

    private fun backoff(attempts: Int): Long {
        val base = minOf(6 * HOUR, 30_000L shl minOf(attempts, 12))
        return (base * Random.nextDouble(0.5, 1.5)).toLong()
    }

    companion object {
        const val SELFIE_QUALITY = 85
        private const val HOUR = 60 * 60 * 1000L

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
        }

        fun fromLocal(e: AttendanceEntity) = AttendanceRecord(
            id = e.id, userId = e.userId, kind = e.kind, clientAt = e.clientAt, timeZone = e.timeZone,
            lat = e.lat, lng = e.lng, accuracyM = e.accuracyM, isMock = e.isMock, faceResult = e.faceResult,
            faceScore = e.faceScore, enrollmentId = e.enrollmentId, livenessPassed = e.livenessPassed,
            selfie = if (e.selfieVerifiedAt != null) "AVAILABLE" else "LOCAL",
        )
    }
}
