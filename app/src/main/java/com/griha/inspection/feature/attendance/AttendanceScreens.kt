package com.griha.inspection.feature.attendance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.griha.inspection.core.admin.AdminRepository
import com.griha.inspection.core.attendance.AttendanceRecord
import com.griha.inspection.core.attendance.AttendanceRepository
import com.griha.inspection.core.attendance.FaceEnrollment
import com.griha.inspection.core.database.AttendanceDao
import com.griha.inspection.core.face.FaceResult
import com.griha.inspection.feature.admin.AdminScaffold
import com.griha.inspection.feature.admin.InfoCard
import com.griha.inspection.feature.admin.LoadingViewModel
import com.griha.inspection.feature.admin.MessageLine
import com.griha.inspection.feature.admin.Secondary
import com.griha.inspection.feature.admin.Tag
import com.griha.inspection.feature.admin.loadItems
import com.griha.inspection.feature.admin.relativeTime
import com.griha.inspection.ui.components.InitialsAvatar
import com.griha.inspection.ui.grihaViewModel
import com.griha.inspection.ui.theme.GrihaWarn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

// ---- Shared ---------------------------------------------------------------------------------------

/** A photo fetched from the server on demand (selfie, enrolment photo); initials while loading. */
@Composable
fun RemotePhoto(cacheKey: String, size: Dp, fallbackName: String, shape: Shape = CircleShape, load: suspend () -> File?) {
    val file by produceState<File?>(null, cacheKey) { value = load() }
    Box(Modifier.size(size).clip(shape)) {
        val f = file
        if (f == null) {
            InitialsAvatar(fallbackName, size = size.value.toInt())
        } else {
            AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** What the admin should look at: face result, liveness, fake GPS, clock, no location. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordBadges(r: AttendanceRecord) {
    val warn = MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    val amber = GrihaWarn.copy(alpha = 0.15f) to GrihaWarn
    val ok = MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    val result = FaceResult.entries.firstOrNull { it.name == r.faceResult }
    val tags = buildList {
        when (result) {
            FaceResult.MATCH -> add(result.label to ok)
            FaceResult.NO_MATCH -> add(result.label to warn)
            null -> add(r.faceResult to amber)
            else -> add(result.label to amber)
        }
        if (!r.livenessPassed) add("No head-turn check" to amber)
        if (r.isMock) add("Fake GPS app" to warn)
        if (r.lat == null) add("No location" to amber)
        if (r.clockSuspect) add("Phone clock off" to warn)
        if (r.selfie == "LOCAL") add("Waiting to upload" to (MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { (text, colors) -> Tag(text, colors.first, colors.second) }
    }
}

/** Opens the place in the phone's map app (Google Maps etc.); nothing is sent anywhere by us. */
fun openMap(context: Context, lat: Double, lng: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private val timeFormat get() = DateFormat.getTimeInstance(DateFormat.SHORT)
private fun dayTitle(ms: Long) = DateFormat.getDateInstance(DateFormat.FULL).format(Date(ms))

/** Worked time: each IN paired with the next OUT. An IN with no OUT counts up to [openUntil] if given. */
fun workedMs(records: List<AttendanceRecord>, openUntil: Long?): Long {
    var total = 0L
    var since: Long? = null
    for (r in records.sortedBy { it.clientAt }) {
        if (r.isIn) { if (since == null) since = r.clientAt } else since?.let { total += r.clientAt - it; since = null }
    }
    since?.let { s -> openUntil?.let { total += it - s } }
    return total
}

/** One check-in/out line: time, kind, place (tap → map), badges; selfie on the left. */
@Composable
private fun RecordRow(r: AttendanceRecord, repo: AttendanceRepository, name: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (r.selfie == "AVAILABLE") {
            RemotePhoto("selfie-${r.id}", 56.dp, name, RoundedCornerShape(10.dp)) { repo.selfieFile(r.id) }
        } else {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(if (r.selfie == "DELETED") "Selfie removed\n(90 days)" else "Uploading", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${if (r.isIn) "Checked in" else "Checked out"} · ${timeFormat.format(Date(r.clientAt))}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (r.lat != null && r.lng != null) {
                Row(
                    Modifier.clickable { openMap(context, r.lat, r.lng, "$name ${if (r.isIn) "check-in" else "check-out"}") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(
                        "Open in Maps · ±${r.accuracyM?.toInt() ?: "?"} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            RecordBadges(r)
            r.faceScore?.let { Secondary("Face similarity ${"%.2f".format(it)}") }
        }
    }
}

// ---- My attendance (engineer) -------------------------------------------------------------------

class MyAttendanceViewModel(private val repo: AttendanceRepository, private val dao: AttendanceDao, private val userId: String?) :
    LoadingViewModel<Pair<List<AttendanceRecord>, Boolean>>() {

    /** Last 31 days: the server's copy merged with records still only on this phone. Offline = this phone only. */
    override suspend fun fetch(): Pair<List<AttendanceRecord>, Boolean> {
        val uid = userId ?: return emptyList<AttendanceRecord>() to false
        val to = System.currentTimeMillis() + 60_000
        val from = dayBounds(to).first - 30 * DAY
        val local = dao.observeRange(uid, from, to).first().map(AttendanceRepository::fromLocal)
        return try {
            val server = repo.ownHistory(from, to)
            val ids = server.map { it.id }.toSet()
            (server + local.filter { it.id !in ids }).sortedByDescending { it.clientAt } to false
        } catch (e: IOException) {
            local to true
        }
    }

    private companion object { const val DAY = 24 * 60 * 60 * 1000L }
}

@Composable
fun MyAttendanceScreen(onBack: () -> Unit) {
    val vm = grihaViewModel {
        val c = it.container
        MyAttendanceViewModel(c.attendanceRepository, c.database.attendanceDao(), c.authRepository.currentUser?.id)
    }
    val repo = (LocalContext.current.applicationContext as com.griha.inspection.GrihaApp).container.attendanceRepository
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    AdminScaffold("My attendance", onBack, onRefresh = { vm.refresh() }) {
        loadItems(state, retry = { vm.refresh() }) { (records, offline) ->
            if (offline) item(key = "offline") { MessageLine("Offline: showing check-ins saved on this phone only.") }
            if (records.isEmpty()) item(key = "empty") { InfoCard { Text("No check-ins in the last 31 days.") } }
            records.groupBy { dayBounds(it.clientAt).first }.forEach { (day, list) ->
                item(key = "day-$day") {
                    InfoCard {
                        val open = if (dayBounds(System.currentTimeMillis()).first == day) System.currentTimeMillis() else null
                        Text(dayTitle(day), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Secondary("Worked ${duration(workedMs(list, open))}")
                        list.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            RecordRow(r, repo, "")
                        }
                    }
                }
            }
        }
    }
}

// ---- Admin: who worked when ------------------------------------------------------------------------

/** One employee's day for the admin list. [records] oldest first. */
data class EmployeeDay(
    val userId: String,
    val name: String,
    val code: String,
    val records: List<AttendanceRecord>,
) {
    val firstIn get() = records.firstOrNull { it.isIn }
    val lastOut get() = records.lastOrNull { !it.isIn }
    val onDuty get() = records.lastOrNull()?.isIn == true
    /** Anything on this day the admin should look at. */
    val flagged get() = records.any {
        it.faceResult != FaceResult.MATCH.name || it.isMock || it.lat == null || it.clockSuspect || !it.livenessPassed
    }
}

class AdminAttendanceViewModel(private val repo: AttendanceRepository, private val admin: AdminRepository) :
    LoadingViewModel<List<EmployeeDay>>() {
    val day = MutableStateFlow(dayBounds(System.currentTimeMillis()).first)

    fun shiftDay(days: Int) {
        day.value = dayBounds(day.value + days * 24 * 60 * 60 * 1000L + 12 * 60 * 60 * 1000L).first
        refresh()
    }

    /** Everyone active, with their records of the day; people with none are listed as "not checked in". */
    override suspend fun fetch(): List<EmployeeDay> {
        val (from, to) = dayBounds(day.value)
        val records = repo.allRecords(from, to).groupBy { it.userId }
        val users = admin.users().filter { it.active }
        val known = users.map { u ->
            EmployeeDay(u.id, u.displayName, u.employeeCode, records[u.id].orEmpty().sortedBy { it.clientAt })
        }
        val others = records.filterKeys { id -> users.none { it.id == id } }.map { (id, list) ->
            EmployeeDay(id, list.first().displayName, list.first().employeeCode, list.sortedBy { it.clientAt })
        }
        // Flagged first, then on duty, then the rest by name; nobody-checked-in last.
        return (known + others).sortedWith(
            compareBy<EmployeeDay>({ it.records.isEmpty() }, { !it.flagged }, { !it.onDuty }, { it.name.lowercase() })
        )
    }
}

@Composable
fun AdminAttendanceScreen(onBack: () -> Unit, onEnrollments: () -> Unit) {
    val vm = grihaViewModel { AdminAttendanceViewModel(it.container.attendanceRepository, it.container.adminRepository) }
    val repo = (LocalContext.current.applicationContext as com.griha.inspection.GrihaApp).container.attendanceRepository
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val today = dayBounds(System.currentTimeMillis()).first

    AdminScaffold("Attendance", onBack, onRefresh = { vm.refresh() }) {
        item(key = "day") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shiftDay(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
                Text(if (day == today) "Today" else dayTitle(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.shiftDay(1) }, enabled = day < today) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
            }
        }
        item(key = "enrol") { TextButton(onClick = onEnrollments) { Text("Face set-ups to approve →") } }
        loadItems(state, retry = { vm.refresh() }) { people ->
            item(key = "summary") {
                val came = people.count { it.records.isNotEmpty() }
                Secondary("$came of ${people.size} checked in · ${people.count { it.onDuty }} on duty now · ${people.count { it.flagged }} to review")
            }
            people.forEach { p ->
                item(key = "emp-${p.userId}") {
                    EmployeeDayCard(p, repo, isToday = day == today, expanded = expanded == p.userId) {
                        expanded = if (expanded == p.userId) null else p.userId
                    }
                }
            }
        }
    }
}

@Composable
private fun EmployeeDayCard(p: EmployeeDay, repo: AttendanceRepository, isToday: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        onClick = onToggle,
        enabled = p.records.isNotEmpty(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val face = p.firstIn
                if (face != null && face.selfie == "AVAILABLE") {
                    RemotePhoto("selfie-${face.id}", 48.dp, p.name) { repo.selfieFile(face.id) }
                } else {
                    InitialsAvatar(p.name, size = 48)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val line = when {
                        p.records.isEmpty() -> "Not checked in"
                        else -> buildString {
                            append(p.firstIn?.let { "In ${timeFormat.format(Date(it.clientAt))}" } ?: "No check-in")
                            append(" · ")
                            append(if (p.onDuty) "on duty" else p.lastOut?.let { "Out ${timeFormat.format(Date(it.clientAt))}" } ?: "")
                            append(" · ")
                            append(duration(workedMs(p.records, if (isToday) System.currentTimeMillis() else null)))
                        }
                    }
                    Text("${p.code} · $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    p.flagged -> Tag("Review", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    p.onDuty -> Tag("On duty", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (expanded) {
                val enrolment = p.records.firstNotNullOfOrNull { it.enrollmentId }
                if (enrolment != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemotePhoto("enrol-$enrolment", 72.dp, p.name, RoundedCornerShape(10.dp)) { repo.enrollmentPhotoFile(enrolment) }
                        Secondary("Approved face photo — compare with each selfie below.")
                    }
                } else {
                    Secondary("No approved face for these check-ins: compare the selfies yourself.")
                }
                p.records.forEach { r ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecordRow(r, repo, p.name)
                }
            }
        }
    }
}

// ---- Admin: face set-ups --------------------------------------------------------------------------

class FaceEnrollmentsViewModel(private val repo: AttendanceRepository) : LoadingViewModel<List<FaceEnrollment>>() {
    override suspend fun fetch() = repo.enrollments()
    fun approve(e: FaceEnrollment) = act("${e.displayName}'s face approved.") { repo.decide(e.id, true) }
    fun reject(e: FaceEnrollment) = act("${e.displayName} will be asked to set up their face again.") { repo.decide(e.id, false) }
}

@Composable
fun FaceEnrollmentsScreen(onBack: () -> Unit) {
    val vm = grihaViewModel { FaceEnrollmentsViewModel(it.container.attendanceRepository) }
    val repo = (LocalContext.current.applicationContext as com.griha.inspection.GrihaApp).container.attendanceRepository
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf<FaceEnrollment?>(null) }

    AdminScaffold("Face set-ups", onBack, onRefresh = { vm.refresh() }) {
        item(key = "message") { MessageLine(message) }
        item(key = "help") {
            Secondary("Approve a face only if the photo clearly shows that employee. Their phone then checks every check-in against it.")
        }
        loadItems(state, retry = { vm.refresh() }) { list ->
            if (list.isEmpty()) item(key = "empty") { InfoCard { Text("Nobody has set up face check-in yet.") } }
            list.forEach { e ->
                item(key = e.id) {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (e.hasPhoto) {
                                RemotePhoto("enrol-${e.id}", 96.dp, e.displayName, RoundedCornerShape(12.dp)) { repo.enrollmentPhotoFile(e.id) }
                            } else {
                                InitialsAvatar(e.displayName, size = 96)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(e.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Secondary("${e.employeeCode} · ${e.shots} shots · sent ${relativeTime(e.createdAt)}")
                                if (e.status == "APPROVED") Tag("Approved", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                                else if (!e.hasPhoto) Secondary("Photo still uploading…")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (e.status == "PENDING") {
                                Button(onClick = { vm.approve(e) }, enabled = e.hasPhoto) { Text("Approve") }
                                OutlinedButton(onClick = { vm.reject(e) }) { Text("Reject") }
                            } else {
                                TextButton(onClick = { confirmReset = e }) { Text("Reset face", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmReset?.let { e ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text("Reset ${e.displayName}'s face?") },
            text = { Text("Their check-ins are marked \"not set up\" until they set up their face again and you approve it.") },
            confirmButton = { TextButton(onClick = { confirmReset = null; vm.reject(e) }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = null }) { Text("Cancel") } },
        )
    }
}
