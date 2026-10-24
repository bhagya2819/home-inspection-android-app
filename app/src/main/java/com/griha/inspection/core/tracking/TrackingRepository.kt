package com.griha.inspection.core.tracking

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationCompat
import androidx.room.withTransaction
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.database.LocationPointEntity
import com.griha.inspection.core.database.TrackingEventEntity
import com.griha.inspection.core.network.ApiClient
import com.griha.inspection.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

/** What the engineer's phone is doing about location sharing. */
enum class SharingMode {
    OFF,

    /** The service runs (or should: it is restarted when the app opens). */
    SHARING,

    /** Turned off by the engineer with a reason; the admin sees it until they turn it on or check out. */
    PAUSED,

    /** Should be sharing, but the phone restarted or the permission went away: needs a tap to resume. */
    INTERRUPTED,
}

data class SharingStatus(
    val mode: SharingMode = SharingMode.OFF,
    val since: Long = 0,
    val source: String? = null,
    val reason: String? = null,
    val note: String? = null,
    /** Whose sharing this is (events are filed under them even after a sign-out). */
    val userId: String? = null,
    /** A start that waits for the consent notice or the location permission (e.g. from a check-in). */
    val pendingStart: String? = null,
)

enum class StartResult { STARTED, NEEDS_CONSENT, NEEDS_PERMISSION, SIGNED_OUT }

/** Reasons offered when the engineer turns sharing off. */
enum class PauseReason(val label: String) { LOW_BATTERY("Low battery"), BREAK("Break"), OTHER("Other") }

/** Team view row (admin), see server/API.md. */
@Serializable
data class TeamMember(
    val userId: String,
    val employeeCode: String = "",
    val displayName: String = "",
    val role: String = "",
    val faceId: String? = null,
    val onDuty: Boolean = false,
    val dutySince: Long? = null,
    val state: String = "OFF",
    val stateSince: Long? = null,
    val source: String? = null,
    val reason: String? = null,
    val note: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val pointAt: Long? = null,
    val battery: Int? = null,
    val lastContact: Long? = null,
    val mockToday: Boolean = false,
    val liveUntil: Long? = null,
)

@Serializable
data class DayPoint(val clientAt: Long, val lat: Double, val lng: Double, val accuracyM: Float? = null, val battery: Int? = null, val isMock: Boolean = false)

@Serializable
data class DayEvent(
    val kind: String,
    val source: String? = null,
    val reason: String? = null,
    val note: String? = null,
    val clientAt: Long,
    val lat: Double? = null,
    val lng: Double? = null,
)

data class EngineerDay(val points: List<DayPoint>, val events: List<DayEvent>)

/**
 * Live location (feature 2). Its own module: attendance never depends on it; the only links are
 * that a check-in starts sharing and a check-out stops it (wired in GrihaApp). Every change is an
 * event row, saved before anything else happens, so the admin's timeline survives being offline.
 */
class TrackingRepository(
    private val context: Context,
    private val db: GrihaDatabase,
    private val api: ApiClient,
    private val prefs: SharedPreferences,
    private val currentUserId: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.trackingDao()
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<SharingStatus> = _status.asStateFlow()

    private val _liveUntil = MutableStateFlow(prefs.getLong(K_LIVE_UNTIL, 0))
    /** An admin is watching until then (epoch ms): the service samples and uploads faster. */
    val liveUntil: StateFlow<Long> = _liveUntil.asStateFlow()

    val unsentEvents get() = dao.observeUnsentEvents()
    val unsentPoints get() = dao.observeUnsentPoints()
    fun observeLatestPoint(userId: String) = dao.observeLatestPoint(userId)

    /** The notification's "Turn off" was tapped: the app asks for the reason. */
    val turnOffAsked = MutableStateFlow(false)

    private val uploadLock = Mutex()
    @Volatile var lastUploadAt = 0L
        private set

    // ---- Consent and permission --------------------------------------------------------------

    fun hasConsent(userId: String? = currentUserId()): Boolean = userId != null && prefs.getInt(K_CONSENT + userId, 0) >= NOTICE_VERSION

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    suspend fun acceptConsent() {
        val user = currentUserId() ?: return
        event(user, "CONSENT", note = "v$NOTICE_VERSION")
        prefs.edit { putInt(K_CONSENT + user, NOTICE_VERSION) }
    }

    // ---- Turning sharing on and off ------------------------------------------------------------

    /**
     * Starts sharing. Must be called while the app is on screen (Android 14 only lets a location
     * service start from a visible app). Without consent or permission it remembers the request.
     */
    suspend fun start(source: String): StartResult {
        val user = currentUserId() ?: return StartResult.SIGNED_OUT
        if (!hasConsent(user)) {
            save(_status.value.copy(pendingStart = source, userId = _status.value.userId ?: user))
            return StartResult.NEEDS_CONSENT
        }
        if (!hasPermission()) {
            save(_status.value.copy(pendingStart = source, userId = _status.value.userId ?: user))
            return StartResult.NEEDS_PERMISSION
        }
        event(user, "START", source = source)
        save(SharingStatus(SharingMode.SHARING, since = clock(), source = source, userId = user))
        TrackingNotifications.cancelResume(context)
        TrackingService.start(context)
        return StartResult.STARTED
    }

    /** The pending start (from a check-in) once consent and permission are there. */
    suspend fun startPending(): StartResult? = _status.value.pendingStart?.let { start(it) }

    /** Engineer turned sharing off: the reason goes to the admin. Stays off until turned on. */
    suspend fun pause(reason: PauseReason, note: String?) {
        val s = _status.value
        val user = s.userId ?: currentUserId() ?: return
        val trimmed = note?.trim()?.take(300)?.ifEmpty { null }
        event(user, "PAUSE", reason = reason.name, note = trimmed)
        save(SharingStatus(SharingMode.PAUSED, since = clock(), reason = reason.name, note = trimmed, userId = user))
        TrackingService.stop(context)
        TrackingNotifications.cancelResume(context)
    }

    /** Check-out (or sign-out): stops without an alert. Nothing happens when already off. */
    suspend fun stop(source: String) {
        val s = _status.value
        if (s.mode == SharingMode.OFF && s.pendingStart == null) return
        val user = s.userId ?: currentUserId()
        if (user != null && s.mode != SharingMode.OFF) event(user, "STOP", source = source)
        save(SharingStatus(SharingMode.OFF, since = clock(), source = source, userId = user))
        TrackingService.stop(context)
        TrackingNotifications.cancelResume(context)
    }

    /** Drops a pending start the engineer declined (consent or permission refused). */
    fun dropPending() = save(_status.value.copy(pendingStart = null))

    /** Phone restarted / app updated while sharing: Android 14 won't let us restart the service ourselves. */
    suspend fun onInterrupted(reason: String) = interrupt(reason)

    /** The service was refused (no permission, or started from the background by the system). */
    suspend fun onServiceRefused() = interrupt(if (hasPermission()) "KILLED" else "PERMISSION_REMOVED")

    private suspend fun interrupt(reason: String) {
        val s = _status.value
        if (s.mode != SharingMode.SHARING) return
        val user = s.userId ?: return
        event(user, "INTERRUPTED", reason = reason)
        save(s.copy(mode = SharingMode.INTERRUPTED, since = clock(), reason = reason))
        TrackingNotifications.showResume(context)
    }

    /**
     * The app came to the screen: resume sharing that should be running (after a reboot, or after
     * the system killed the service). Opening the app counts as the "tap to resume".
     */
    suspend fun onAppVisible() {
        val s = _status.value
        if (s.mode != SharingMode.SHARING && s.mode != SharingMode.INTERRUPTED) return
        if (TrackingService.running) return
        val user = s.userId ?: return
        if (user != currentUserId()) return // someone else signed in on this phone
        if (!hasPermission()) {
            if (s.mode == SharingMode.SHARING) interrupt("PERMISSION_REMOVED")
            return
        }
        val source = if (s.mode == SharingMode.INTERRUPTED) "RESUME" else "RESTORE"
        event(user, "START", source = source)
        save(SharingStatus(SharingMode.SHARING, since = if (s.mode == SharingMode.SHARING) s.since else clock(), source = s.source ?: source, userId = user))
        TrackingNotifications.cancelResume(context)
        TrackingService.start(context)
    }

    // ---- Recording (from the service) ---------------------------------------------------------

    suspend fun record(location: Location, battery: Int?): LocationPointEntity? {
        val user = _status.value.userId ?: return null
        if (_status.value.mode != SharingMode.SHARING) return null // never record outside sharing
        // A cached fix from before sharing started (providers hand out their last one first) is not
        // a position while sharing; nor is one minutes old on arrival.
        if (location.time < _status.value.since - 30_000 || clock() - location.time > 5 * 60_000) return null
        val row = LocationPointEntity(
            id = UUID.randomUUID().toString(), userId = user, clientAt = location.time,
            lat = location.latitude, lng = location.longitude, accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            battery = battery, isMock = LocationCompat.isMock(location),
        )
        dao.insertPoint(row)
        return row
    }

    /** LOCATION_OFF / LOCATION_ON / MOCK while sharing. */
    suspend fun recordState(kind: String) {
        val user = _status.value.userId ?: return
        if (_status.value.mode == SharingMode.SHARING) event(user, kind)
    }

    private suspend fun event(user: String, kind: String, source: String? = null, reason: String? = null, note: String? = null) {
        dao.insertEvent(TrackingEventEntity(UUID.randomUUID().toString(), user, kind, source, reason, note, clock()))
    }

    // ---- Upload --------------------------------------------------------------------------------

    /**
     * Sends the signed-in engineer's unsent events and points in batches. A lost session or the
     * developer lock is rethrown untouched (the sync engine handles them); nothing is lost either way.
     */
    suspend fun push() = uploadLock.withLock {
        val user = currentUserId() ?: return@withLock
        while (true) {
            val events = dao.unsentEvents(user, 200)
            val points = dao.unsentPoints(user, 1000)
            // Nothing new: still check in now and then while sharing (the admin's "last seen", live requests).
            if (events.isEmpty() && points.isEmpty() && (_status.value.mode != SharingMode.SHARING || clock() - lastUploadAt < 60_000)) return@withLock
            val res = api.post("/v1/tracking/batch", buildJsonObject {
                put("user_id", user)
                put("points", JsonArray(points.map { p ->
                    buildJsonObject {
                        put("id", p.id); put("client_at", p.clientAt); put("lat", p.lat); put("lng", p.lng)
                        put("accuracy_m", p.accuracyM?.let(::JsonPrimitive) ?: JsonNull)
                        put("battery", p.battery?.let(::JsonPrimitive) ?: JsonNull)
                        put("is_mock", p.isMock)
                    }
                }))
                put("events", JsonArray(events.map { e ->
                    buildJsonObject {
                        put("id", e.id); put("kind", e.kind); put("client_at", e.clientAt)
                        put("source", e.source?.let(::JsonPrimitive) ?: JsonNull)
                        put("reason", e.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("note", e.note?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }))
            })
            val now = clock()
            db.withTransaction {
                if (points.isNotEmpty()) dao.markPointsSent(points.map { it.id }, now)
                if (events.isNotEmpty()) dao.markEventsSent(events.map { it.id }, now)
            }
            lastUploadAt = now
            val live = res["live_until"]?.jsonPrimitive?.longOrNull ?: 0L
            if (live != _liveUntil.value) {
                _liveUntil.value = live
                prefs.edit { putLong(K_LIVE_UNTIL, live) }
            }
            if (points.size < 1000 && events.size < 200) return@withLock
        }
    }

    /** From the service: never throws; a failure just waits for the next turn. */
    suspend fun pushQuietly(): Boolean = try {
        push(); true
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        false // includes ApiException; sign-in and lock are the sync engine's business
    } catch (_: Exception) {
        false
    }

    /** Sent points older than a week leave the phone; the server keeps them 90 days. */
    suspend fun cleanup() {
        val before = clock() - 7 * DAY
        dao.deleteSentPointsBefore(before)
        dao.deleteSentEventsBefore(before)
    }

    // ---- Admin ---------------------------------------------------------------------------------

    suspend fun team(): List<TeamMember> =
        api.get("/v1/admin/tracking/team")["engineers"]?.let { json.decodeFromJsonElement<List<TeamMember>>(it) }.orEmpty()

    suspend fun day(userId: String, from: Long, to: Long): EngineerDay {
        val o = api.get("/v1/admin/tracking/day?user=$userId&from=$from&to=$to")
        return EngineerDay(
            o["points"]?.let { json.decodeFromJsonElement<List<DayPoint>>(it) }.orEmpty(),
            o["events"]?.let { json.decodeFromJsonElement<List<DayEvent>>(it) }.orEmpty(),
        )
    }

    /** Ask that engineer's phone to go live for 10 minutes; it hears at its next upload. */
    suspend fun watchLive(userId: String): Long? =
        api.post("/v1/admin/tracking/live", buildJsonObject { put("user_id", userId) })["live_until"]?.jsonPrimitive?.longOrNull

    // ---- State persistence -------------------------------------------------------------------

    private fun save(s: SharingStatus) {
        _status.value = s
        prefs.edit {
            putString(K_MODE, s.mode.name); putLong(K_SINCE, s.since); putString(K_SOURCE, s.source)
            putString(K_REASON, s.reason); putString(K_NOTE, s.note); putString(K_USER, s.userId); putString(K_PENDING, s.pendingStart)
        }
    }

    private fun readStatus() = SharingStatus(
        mode = runCatching { SharingMode.valueOf(prefs.getString(K_MODE, null) ?: "OFF") }.getOrDefault(SharingMode.OFF),
        since = prefs.getLong(K_SINCE, 0), source = prefs.getString(K_SOURCE, null), reason = prefs.getString(K_REASON, null),
        note = prefs.getString(K_NOTE, null), userId = prefs.getString(K_USER, null), pendingStart = prefs.getString(K_PENDING, null),
    )

    /** Attendance link: the last check-in/out id already acted on (so app restarts don't replay it). */
    var handledAttendanceId: String?
        get() = prefs.getString(K_HANDLED_ATTENDANCE, null)
        set(v) = prefs.edit { putString(K_HANDLED_ATTENDANCE, v) }

    var batteryGuideDone: Boolean
        get() = prefs.getBoolean(K_GUIDE, false)
        set(v) = prefs.edit { putBoolean(K_GUIDE, v) }

    companion object {
        /** Bump when the consent notice's wording changes materially: everyone sees it again. */
        const val NOTICE_VERSION = 1
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val K_MODE = "mode"
        private const val K_SINCE = "since"
        private const val K_SOURCE = "source"
        private const val K_REASON = "reason"
        private const val K_NOTE = "note"
        private const val K_USER = "user"
        private const val K_PENDING = "pending_start"
        private const val K_LIVE_UNTIL = "live_until"
        private const val K_CONSENT = "consent_"
        private const val K_HANDLED_ATTENDANCE = "handled_attendance"
        private const val K_GUIDE = "battery_guide_done"

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
        }
    }
}
