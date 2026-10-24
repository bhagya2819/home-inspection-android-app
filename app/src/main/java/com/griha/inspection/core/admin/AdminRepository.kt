package com.griha.inspection.core.admin

import com.griha.inspection.core.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Admin API models (server/API.md, "Admin"). Field names map to the server's snake_case JSON.

@Serializable
data class AdminUser(
    val id: String,
    val employeeCode: String,
    val displayName: String,
    val role: String,
    val status: String,
    val mustChangePassword: Boolean = false,
    val createdAt: Long = 0,
    val lastSeenAt: Long? = null,
    /** Attendance calendar: whether missing check-ins count for them, and from which day (yyyy-MM-dd). */
    val trackAttendance: Boolean = true,
    val attendanceFrom: String = "",
) {
    val active get() = status == "ACTIVE"
}

@Serializable
data class AdminInspection(
    val id: String,
    val clientName: String,
    val propertyAddress: String,
    val inspectionDate: String,
    val status: String,
    val finalizedAt: Long? = null,
    val inspector: String = "",
    val serverUpdatedAt: Long = 0,
    val photos: Int = 0,
    val photosVerified: Int = 0,
    val photosExported: Int = 0,
    val photosArchived: Int = 0,
    val reports: Int = 0,
    val reportsVerified: Int = 0,
)

@Serializable
data class SyncProblem(
    val type: String = "",
    val id: String = "",
    val op: String = "",
    val code: String? = null,
    val message: String? = null,
    val attempts: Int = 0,
)

@Serializable
data class DeviceReport(
    val appVersion: String = "",
    val pendingRecords: Int = 0,
    val pendingPhotos: Int = 0,
    val freeBytes: Long = 0,
    val localOriginals: Int = 0,
    val localOriginalsBytes: Long = 0,
    val cleanupBlocked: Int = 0,
    val problems: List<SyncProblem>? = null,
)

@Serializable
data class AdminDevice(
    val id: String,
    val user: String,
    val employeeCode: String,
    val label: String,
    val platform: String = "",
    val appVersion: String = "",
    val lastSeenAt: Long = 0,
    val revokedAt: Long? = null,
    val syncReport: DeviceReport? = null,
    val syncReportedAt: Long? = null,
)

@Serializable
data class AuditEntry(
    val id: Long,
    val type: String,
    val user: String = "",
    val employeeCode: String = "",
    val device: String = "",
    val entity: String = "",
    val details: String = "",
    val createdAt: Long,
    val fromDevice: Boolean = false,
)

@Serializable
data class ExportInfo(
    val id: Long,
    val label: String,
    val status: String,
    val createdAt: Long,
    val entryCount: Int,
    val objectsBytes: Long,
    val zipBytes: Long,
    val manifestSha256: String,
    val verifiedAt: Long? = null,
    val archivedAt: Long? = null,
    val archivedObjects: Int = 0,
    val fileName: String,
)

@Serializable
data class StorageInfo(
    val dataFreeBytes: Long,
    val dataTotalBytes: Long,
    val objectsBytes: Long,
    val unexportedBytes: Long,
    val unexportedCount: Int,
    val archivedBytes: Long = 0,
    val lastExportAt: Long? = null,
    val archiveMinAgeDays: Int = 60,
    val exportWarnBytes: Long = 5_000_000_000,
    val exports: List<ExportInfo> = emptyList(),
) {
    val needsExport get() = unexportedBytes >= exportWarnBytes
}

@Serializable
data class ServerSettings(
    val retentionDays: Int,
    val archiveMinAgeDays: Int,
    val exportWarnBytes: Long = 5_000_000_000,
    val policyVersion: String = "",
)

/** The AI key's status and this month's use. The key itself never comes back from the server. */
@Serializable
data class AiInfo(
    val available: Boolean,
    val keySet: Boolean,
    val keyLast4: String? = null,
    val keySetAt: Long? = null,
    val keySetBy: String? = null,
    val model: String = "",
    val dailyLimit: Int = 100,
    val monthRequests: Long = 0,
    val monthTokens: Long = 0,
    val users: List<AiUserUsage> = emptyList(),
)

@Serializable
data class AiUserUsage(val name: String, val requests: Long)

@Serializable
data class ArchiveResult(
    val export: ExportInfo,
    val archivedNow: Int,
    val alreadyArchived: Int = 0,
    val keptTooRecent: Int = 0,
    val keptNotFinalized: Int = 0,
    val keptChanged: Int = 0,
    val freedBytes: Long = 0,
    val minAgeDays: Int = 60,
)

/**
 * Admin screens' calls (PRD §3.1). The server enforces the ADMIN role on every one of them; the app
 * only hides the entry point from inspectors. All need a connection (no offline admin).
 */
class AdminRepository(private val api: ApiClient) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; namingStrategy = JsonNamingStrategy.SnakeCase }

    private inline fun <reified T> JsonObject.list(key: String): List<T> =
        this[key]!!.jsonArray.map { json.decodeFromJsonElement<T>(it) }

    private inline fun <reified T> JsonObject.obj(key: String? = null): T =
        json.decodeFromJsonElement<T>(if (key == null) this else this[key]!!)

    // ---- Employees ---------------------------------------------------------------------------

    suspend fun users(): List<AdminUser> = api.get("/v1/admin/users").list("users")

    /** The new employee must change [password] at first sign-in. */
    suspend fun createUser(code: String, name: String, role: String, password: String) {
        api.post("/v1/admin/users", buildJsonObject {
            put("employee_code", code.trim()); put("display_name", name.trim()); put("role", role); put("password", password)
        })
    }

    /** Only the given fields change. A password reset or disabling signs that user out everywhere. */
    suspend fun updateUser(
        id: String,
        displayName: String? = null,
        role: String? = null,
        active: Boolean? = null,
        password: String? = null,
        trackAttendance: Boolean? = null,
        attendanceFrom: String? = null,
    ) {
        api.patch("/v1/admin/users/$id", buildJsonObject {
            displayName?.let { put("display_name", it.trim()) }
            role?.let { put("role", it) }
            active?.let { put("status", if (it) "ACTIVE" else "DISABLED") }
            password?.let { put("password", it) }
            trackAttendance?.let { put("track_attendance", it) }
            attendanceFrom?.let { put("attendance_from", it) }
        })
    }

    // ---- Views -------------------------------------------------------------------------------

    suspend fun inspections(query: String = ""): List<AdminInspection> =
        api.get("/v1/admin/inspections?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")).list("inspections")

    suspend fun devices(): List<AdminDevice> = api.get("/v1/admin/devices").list("devices")

    suspend fun setDeviceBlocked(id: String, blocked: Boolean) {
        api.patch("/v1/admin/devices/$id", buildJsonObject { put("revoked", blocked) })
    }

    /** Newest first; pass the last id seen as [before] for the next page. */
    suspend fun audit(before: Long = 0, type: String = ""): List<AuditEntry> =
        api.get("/v1/admin/audit?limit=100&before=$before&type=" + java.net.URLEncoder.encode(type, "UTF-8")).list("events")

    suspend fun settings(): ServerSettings = api.get("/v1/settings").obj()

    suspend fun updateSettings(retentionDays: Int? = null, archiveMinAgeDays: Int? = null): ServerSettings =
        api.patch("/v1/admin/settings", buildJsonObject {
            retentionDays?.let { put("retention_days", it) }
            archiveMinAgeDays?.let { put("archive_min_age_days", it) }
        }).obj()

    // ---- AI assistant ------------------------------------------------------------------------

    suspend fun aiInfo(): AiInfo = api.get("/v1/admin/ai").obj()

    /** The server checks the key with DeepSeek before storing it (encrypted). */
    suspend fun setAiKey(key: String): AiInfo = api.put("/v1/admin/ai/key", buildJsonObject { put("key", key.trim()) }).obj()

    suspend fun removeAiKey(): AiInfo = api.delete("/v1/admin/ai/key").obj()

    suspend fun setAiDailyLimit(limit: Int) {
        api.patch("/v1/admin/settings", buildJsonObject { put("ai_daily_limit", limit) })
    }

    // ---- Server storage, exports, archive ----------------------------------------------------

    private val _storageWarning = MutableStateFlow<StorageInfo?>(null)
    /** Set (for admins) when ≥ 5 GB were backed up since the last verified export: dashboard banner. */
    val storageWarning = _storageWarning.asStateFlow()

    suspend fun storage(): StorageInfo = api.get("/v1/admin/storage").obj<StorageInfo>().also {
        _storageWarning.value = it.takeIf { s -> s.needsExport }
    }

    /** Freezes the next month-sized batch, or returns the export still waiting for verification. */
    suspend fun createExport(): Pair<ExportInfo, Int> {
        val res = api.post("/v1/admin/exports", JsonObject(emptyMap()))
        val problems = (res["problems"] as? JsonArray)?.size ?: 0
        return res.obj<ExportInfo>("export") to problems
    }

    /** A 48-hour link scoped to this one export, for the system DownloadManager. */
    suspend fun exportLink(id: Long): String = api.url(api.post("/v1/admin/exports/$id/link", JsonObject(emptyMap()))["path"]!!.jsonPrimitive.content)

    suspend fun markVerified(id: Long, manifestSha256: String, entries: Int): ExportInfo =
        api.post("/v1/admin/exports/$id/verified", buildJsonObject {
            put("manifest_sha256", manifestSha256); put("entries_verified", entries)
        }).obj("export")

    suspend fun archive(id: Long): ArchiveResult = api.post("/v1/admin/exports/$id/archive", JsonObject(emptyMap())).obj()

    /** After a sync, for admins only: keeps the dashboard's export reminder current. */
    suspend fun refreshWarning() {
        storage()
    }

    fun clearWarning() {
        _storageWarning.value = null
    }
}
