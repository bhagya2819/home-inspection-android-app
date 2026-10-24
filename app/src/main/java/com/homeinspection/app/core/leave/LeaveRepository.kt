package com.homeinspection.app.core.leave

import android.content.SharedPreferences
import com.homeinspection.app.core.database.HomeInspectionDatabase
import com.homeinspection.app.core.database.LeaveRequestEntity
import com.homeinspection.app.core.network.ApiClient
import com.homeinspection.app.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

// ---- Server models (snake_case JSON, see server/API.md "Attendance calendar and leave") ----------

enum class LeaveKind(val label: String) { CASUAL("Casual"), SICK("Sick"), COMP_OFF("Comp-off") }

enum class HalfDay(val label: String) { FIRST("First half"), SECOND("Second half") }

@Serializable
data class DayLeave(val id: String, val kind: String, val half: String? = null, val status: String, val reason: String = "") {
    val label get() = (LeaveKind.entries.firstOrNull { it.name == kind }?.label ?: kind) +
        (half?.let { h -> " (" + (HalfDay.entries.firstOrNull { it.name == h }?.label?.lowercase() ?: h) + ")" } ?: "")
}

/** One day of a month as the server colours it (server/internal/api/calendar.go). */
@Serializable
data class CalendarDay(
    val day: String,
    val status: String,
    /** HALF_LEAVE only: what the other half was (WORKED | MISSED | MISSED_CHECKOUT), "" if not known yet. */
    val halfWork: String = "",
    val offDay: Boolean = false,
    val holiday: String? = null,
    val firstIn: Long? = null,
    val lastOut: Long? = null,
    val workedMs: Long = 0,
    val leave: DayLeave? = null,
    val pendingLeave: DayLeave? = null,
    val compOff: String? = null,
    /** EARNED comp-offs the admin may want to check: SHORT (under an hour) | CLOCK (phone clock off). */
    val compOffCheck: String? = null,
    /** Part of a shift that began the day before (counted from midnight). */
    val overnight: Boolean = false,
    /** The phone's clock was off; the times are the server's estimate. */
    val clockSkew: Boolean = false,
) {
    /** The day's picture, as the server's team strip names it (calendar.go calDay.mark). */
    val mark get() = when {
        status == "LEAVE" && leave?.kind == "COMP_OFF" -> "COMP_OFF"
        status == "HALF_LEAVE" && halfWork.isNotEmpty() -> "HALF_LEAVE_$halfWork"
        else -> status
    }
    val compOffEarned get() = compOff == "EARNED" || compOff == "GRANTED"
    val worked get() = firstIn != null || workedMs > 0
}

@Serializable
data class CalendarTotals(
    val daysWorked: Int = 0,
    val workedMs: Long = 0,
    val workingDays: Int = 0,
    val missed: Int = 0,
    val missedCheckouts: Int = 0,
    val leaveDays: Double = 0.0,
    val leaveByKind: Map<String, Double> = emptyMap(),
    val workedOnLeave: Int = 0,
    val compOffsEarned: Int = 0,
)

@Serializable
data class CompOffBalance(val earned: Int = 0, val used: Double = 0.0, val pending: Double = 0.0, val balance: Double = 0.0)

@Serializable
data class CalendarUser(
    val id: String,
    val employeeCode: String,
    val displayName: String,
    val role: String = "",
    val status: String = "",
    val trackAttendance: Boolean = true,
    val attendanceFrom: String = "",
    val leftOn: String? = null,
)

@Serializable
data class MonthCalendar(
    val user: CalendarUser,
    val month: String,
    val today: String,
    val days: List<CalendarDay>,
    val totals: CalendarTotals,
    val compOff: CompOffBalance,
)

@Serializable
data class TeamRow(val user: CalendarUser, val days: List<String>, val totals: CalendarTotals, val pending: List<String> = emptyList())

/** A comp-off earned or granted this month (team view), so the admin notices new ones. */
@Serializable
data class TeamCompOff(val userId: String, val displayName: String, val day: String, val kind: String, val check: String = "", val workedMs: Long = 0)

@Serializable
data class Holiday(val day: String, val name: String)

@Serializable
data class TeamMonth(
    val month: String,
    val today: String,
    val users: List<TeamRow>,
    val holidays: List<Holiday> = emptyList(),
    val compOffs: List<TeamCompOff> = emptyList(),
)

@Serializable
data class LeaveRequest(
    val id: String,
    val userId: String = "",
    val employeeCode: String = "",
    val displayName: String = "",
    val kind: String,
    val startDay: String,
    val endDay: String,
    val half: String? = null,
    val days: Double = 0.0,
    val reason: String = "",
    /** PENDING | APPROVED | REJECTED | REVOKED (taken back) | CANCELLED; NOT_SENT / REFUSED = still only on this phone. */
    val status: String,
    val createdAt: Long = 0,
    val byAdmin: Boolean = false,
    val decidedAt: Long? = null,
    val decidedBy: String? = null,
    val decisionNote: String = "",
    /** Why the server refused a request made on this phone. */
    val localError: String? = null,
    /** Admin list only: days in the range the engineer checked in anyway. */
    val workedDays: Int? = null,
    /** Admin list, comp-off requests only: the engineer's comp-off balance. */
    val compOff: CompOffBalance? = null,
) {
    val kindLabel get() = (LeaveKind.entries.firstOrNull { it.name == kind }?.label ?: kind) +
        (half?.let { h -> " · " + (HalfDay.entries.firstOrNull { it.name == h }?.label?.lowercase() ?: h) } ?: "")
}

@Serializable
data class MyLeave(val requests: List<LeaveRequest>, val compOff: CompOffBalance, val today: String)

@Serializable
data class AdminLeave(val requests: List<LeaveRequest>, val pending: Int = 0, val compOff: CompOffBalance? = null)

/**
 * Attendance calendar, leave requests and comp-offs. A leave request is saved on the phone first and
 * sent with the next sync (works offline); everything else is an online view of the server, like
 * the other admin screens.
 */
class LeaveRepository(
    db: HomeInspectionDatabase,
    private val api: ApiClient,
    private val currentUserId: () -> String?,
    /** Remembers which leave decisions the engineer has seen (per user). */
    private val prefs: () -> SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.leaveDao()

    /** When this user's next queued request may be sent, so the sync job wakes for it (null: none waits). */
    suspend fun nextDueAt(): Long? = currentUserId()?.let { dao.nextDueAt(it) }

    // ---- Decisions the engineer hasn't seen yet (in-app notice; no push yet) ----

    private val _newDecisions = MutableStateFlow<List<LeaveRequest>>(emptyList())
    /** The admin approved, rejected, took back or recorded leave since the engineer last opened Leave. */
    val newDecisions: StateFlow<List<LeaveRequest>> = _newDecisions.asStateFlow()
    @Volatile private var decisionsCheckedAt = 0L

    /** Called after each sync; asks the server at most every 15 minutes. Best effort. */
    suspend fun refreshDecisions(force: Boolean = false) {
        currentUserId() ?: return
        if (!force && clock() - decisionsCheckedAt < 15 * 60_000L) return
        decisionsCheckedAt = clock()
        try {
            noteDecisions(myLeave().requests)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // offline or signed out: try again after the next sync
        }
    }

    private fun seenKey(userId: String) = "leave_seen_decided_at_$userId"

    private fun noteDecisions(requests: List<LeaveRequest>) {
        val userId = currentUserId() ?: return
        val p = prefs()
        val latest = requests.maxOfOrNull { it.decidedAt ?: 0L } ?: 0L
        if (!p.contains(seenKey(userId))) p.edit().putLong(seenKey(userId), latest).apply() // first run: old decisions aren't news
        val seen = p.getLong(seenKey(userId), latest)
        _newDecisions.value = requests.filter { (it.decidedAt ?: 0L) > seen && it.status in setOf("APPROVED", "REJECTED", "REVOKED") }
    }

    /** The engineer opened Leave: everything shown there counts as seen. */
    fun markDecisionsSeen(requests: List<LeaveRequest>) {
        val userId = currentUserId() ?: return
        val latest = requests.maxOfOrNull { it.decidedAt ?: 0L } ?: 0L
        val p = prefs()
        if (latest > p.getLong(seenKey(userId), 0L)) p.edit().putLong(seenKey(userId), latest).apply()
        _newDecisions.value = emptyList()
    }

    fun observeUnsent(): Flow<List<LeaveRequestEntity>> = currentUserId()?.let(dao::observeUnsent) ?: flowOf(emptyList())

    sealed interface Sent {
        data object Delivered : Sent
        data object Queued : Sent
        data class Refused(val message: String) : Sent
    }

    /** Saves the request on the phone, then tries to send it at once (offline: it goes with the next sync). */
    suspend fun request(kind: LeaveKind, startDay: String, endDay: String, half: HalfDay?, reason: String): Sent {
        val userId = currentUserId() ?: throw IOException("Sign in first")
        val id = UUID.randomUUID().toString()
        dao.insert(
            LeaveRequestEntity(
                id = id, userId = userId, kind = kind.name, startDay = startDay, endDay = endDay,
                half = half?.name, reason = reason.trim(), createdAt = clock(),
            )
        )
        try {
            push()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // stays queued
        }
        val row = dao.get(id)
        return when {
            row == null || row.sentAt != null -> Sent.Delivered
            row.failedAt != null -> Sent.Refused(row.lastError ?: "The server refused this request.")
            else -> Sent.Queued
        }
    }

    /** Sends queued requests. Called by the sync engine; a refusal (overlap, invalid) is kept and shown. */
    suspend fun push() {
        val userId = currentUserId() ?: return
        dao.deleteSentBefore(clock() - 30L * 24 * 60 * 60_000) // the server holds them
        for (row in dao.due(userId, clock())) {
            try {
                api.post("/v1/leave", buildJsonObject {
                    put("id", row.id)
                    put("user_id", row.userId)
                    put("kind", row.kind)
                    put("start_day", row.startDay)
                    put("end_day", row.endDay)
                    put("half", row.half?.let(::JsonPrimitive) ?: JsonNull)
                    put("reason", row.reason)
                })
                dao.markSent(row.id, clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isAppLocked || e.isAuthFailure || e.needsPasswordChange) throw e
                if (e.isPermanent || e.code == "OVERLAP") dao.markRefused(row.id, clock(), e.message ?: e.code)
                else dao.retryLater(row.id, clock() + backoff(row.attemptCount), e.message)
            } catch (e: IOException) {
                dao.retryLater(row.id, clock() + backoff(row.attemptCount), e.message)
                throw e
            }
        }
    }

    suspend fun dismissRefused(id: String) = dao.dismissRefused(id)

    // ---- Engineer ----

    suspend fun myLeave(): MyLeave = json.decodeFromJsonElement(api.get("/v1/leave"))

    suspend fun cancel(id: String) {
        api.post("/v1/leave/$id/cancel", null)
    }

    suspend fun myCalendar(month: String): MonthCalendar = json.decodeFromJsonElement(api.get("/v1/calendar?month=$month"))

    // ---- Admin ----

    suspend fun teamMonth(month: String): TeamMonth = json.decodeFromJsonElement(api.get("/v1/admin/calendar/team?month=$month"))

    suspend fun personMonth(userId: String, month: String): MonthCalendar =
        json.decodeFromJsonElement(api.get("/v1/admin/calendar?month=$month&user=$userId"))

    suspend fun adminLeave(status: String?): AdminLeave =
        json.decodeFromJsonElement(api.get("/v1/admin/leave" + (status?.let { "?status=$it" } ?: "")))

    suspend fun pendingCount(): Int = adminLeave("PENDING").pending

    suspend fun decide(id: String, approve: Boolean, note: String) {
        api.post("/v1/admin/leave/$id/decision", buildJsonObject { put("approve", approve); put("note", note.trim()) })
    }

    /** Leave the admin records for an engineer: approved at once. */
    suspend fun record(userId: String, kind: LeaveKind, startDay: String, endDay: String, half: HalfDay?, reason: String) {
        api.post("/v1/admin/leave", buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("user_id", userId)
            put("kind", kind.name)
            put("start_day", startDay)
            put("end_day", endDay)
            put("half", half?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason.trim())
        })
    }

    /** GRANT | REVOKE | CLEAR a comp-off for one day. */
    suspend fun setCompOff(userId: String, day: String, action: String) {
        api.post("/v1/admin/comp-offs", buildJsonObject { put("user_id", userId); put("day", day); put("action", action) })
    }

    suspend fun holidays(year: Int): List<Holiday> =
        api.get("/v1/holidays?year=$year")["holidays"]?.let { json.decodeFromJsonElement<List<Holiday>>(it) }.orEmpty()

    /** Returns how many people's comp-offs or leave days the change affects (told to the admin). */
    suspend fun setHoliday(day: String, name: String): Int =
        api.put("/v1/admin/holidays/$day", buildJsonObject { put("name", name.trim()) })["affects"]?.jsonPrimitive?.intOrNull ?: 0

    suspend fun removeHoliday(day: String): Int = api.delete("/v1/admin/holidays/$day")["affects"]?.jsonPrimitive?.intOrNull ?: 0

    private fun backoff(attempts: Int): Long {
        val base = minOf(6 * 60 * 60_000L, 30_000L shl minOf(attempts, 10))
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    companion object {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
            coerceInputValues = true
        }

        /** A request still on this phone, shaped like the server's for one list. */
        fun fromLocal(e: LeaveRequestEntity) = LeaveRequest(
            id = e.id, userId = e.userId, kind = e.kind, startDay = e.startDay, endDay = e.endDay, half = e.half, reason = e.reason,
            status = if (e.failedAt != null) "REFUSED" else "NOT_SENT", createdAt = e.createdAt, localError = e.lastError,
        )
    }
}
