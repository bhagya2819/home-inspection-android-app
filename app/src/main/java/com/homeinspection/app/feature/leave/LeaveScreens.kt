package com.homeinspection.app.feature.leave

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.core.leave.AdminLeave
import com.homeinspection.app.core.leave.CalendarDay
import com.homeinspection.app.core.leave.HalfDay
import com.homeinspection.app.core.leave.Holiday
import com.homeinspection.app.core.leave.LeaveKind
import com.homeinspection.app.core.leave.LeaveRepository
import com.homeinspection.app.core.leave.LeaveRequest
import com.homeinspection.app.core.leave.MonthCalendar
import com.homeinspection.app.core.leave.MyLeave
import com.homeinspection.app.core.leave.TeamMonth
import com.homeinspection.app.feature.admin.AdminScaffold
import com.homeinspection.app.feature.admin.InfoCard
import com.homeinspection.app.feature.admin.LoadingViewModel
import com.homeinspection.app.feature.admin.MessageLine
import com.homeinspection.app.feature.admin.Secondary
import com.homeinspection.app.feature.admin.Tag
import com.homeinspection.app.feature.admin.friendlyError
import com.homeinspection.app.feature.admin.loadItems
import com.homeinspection.app.feature.admin.relativeTime
import com.homeinspection.app.ui.components.InitialsAvatar
import com.homeinspection.app.ui.homeInspectionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone

// Times in India time, like the calendar's days (the server's +05:30), even on a phone set elsewhere.
private val timeFormat get() = DateFormat.getTimeInstance(DateFormat.SHORT).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
private fun clockTime(ms: Long) = timeFormat.format(Date(ms))

// ---- Admin: team month ------------------------------------------------------------------------------

class TeamCalendarViewModel(private val repo: LeaveRepository) : LoadingViewModel<Pair<TeamMonth, Int>>() {
    val month = MutableStateFlow(companyMonth())
    fun setMonth(m: YearMonth) { month.value = m; refresh() }
    override suspend fun fetch() = repo.teamMonth(monthKey(month.value)) to repo.pendingCount()
}

/** Everyone's month at a glance: one row per engineer with a strip of day marks and totals. */
@Composable
fun TeamCalendarScreen(onBack: () -> Unit, onPerson: (userId: String, name: String) -> Unit, onLeave: () -> Unit, onHolidays: () -> Unit) {
    val vm = homeInspectionViewModel { TeamCalendarViewModel(it.container.leaveRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    AdminScaffold("Attendance calendar", onBack, onRefresh = { vm.refresh() }) {
        item(key = "month") { MonthBar(month, vm::setMonth) }
        loadItems(state, retry = { vm.refresh() }) { (team, pending) ->
            item(key = "links") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLeave) { Text(if (pending > 0) "Leave requests ($pending)" else "Leave requests") }
                    OutlinedButton(onClick = onHolidays) { Text("Holidays") }
                }
            }
            item(key = "legend") { InfoCard { CalendarLegend() } }
            if (team.users.isEmpty()) item(key = "empty") { InfoCard { Text("No engineers yet.") } }
            team.users.forEach { row ->
                item(key = row.user.id) {
                    InfoCard(Modifier.clickable { onPerson(row.user.id, row.user.displayName) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InitialsAvatar(row.user.displayName, size = 36)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(row.user.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                val t = row.totals
                                Secondary(
                                    "${t.daysWorked} days · ${hoursShort(t.workedMs)} · leave ${leaveDaysLabel(t.leaveDays).substringBefore(' ')}" +
                                        " · missed ${t.missed + t.missedCheckouts}"
                                )
                            }
                        }
                        MonthStrip(row.days, team.today, month, row.pending)
                    }
                }
            }
            if (team.compOffs.isNotEmpty()) item(key = "compoffs") {
                InfoCard {
                    Text("Comp-offs this month", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Secondary("Working on a Sunday or holiday earns one automatically. Tap to open the day and take one back.")
                    team.compOffs.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { onPerson(c.userId, c.displayName) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${c.displayName} · ${LocalDate.parse(c.day).format(DayTitle)}", style = MaterialTheme.typography.bodyMedium)
                                Secondary(if (c.kind == "GRANTED") "Granted by an admin" else "Worked ${hoursShort(c.workedMs)}")
                            }
                            when (c.check) {
                                "SHORT" -> Tag("Under 1 h", MissedColor.copy(alpha = 0.2f), MaterialTheme.colorScheme.onSurface)
                                "CLOCK" -> Tag("Clock off", MissedColor.copy(alpha = 0.2f), MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Admin: one engineer's month ----------------------------------------------------------------------

class PersonCalendarViewModel(private val repo: LeaveRepository, private val userId: String) : LoadingViewModel<MonthCalendar>() {
    val month = MutableStateFlow(companyMonth())
    val selected = MutableStateFlow<String?>(null)
    fun setMonth(m: YearMonth) { month.value = m; selected.value = null; refresh() }
    override suspend fun fetch() = repo.personMonth(userId, monthKey(month.value))
    fun compOff(day: String, action: String, done: String) = act(done) { repo.setCompOff(userId, day, action) }
    fun record(kind: LeaveKind, start: String, end: String, half: HalfDay?, reason: String) =
        act("Leave recorded (approved).") { repo.record(userId, kind, start, end, half, reason) }
}

@Composable
fun PersonCalendarScreen(userId: String, name: String, onBack: () -> Unit) {
    val vm = homeInspectionViewModel { PersonCalendarViewModel(it.container.leaveRepository, userId) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var recordFor by remember { mutableStateOf<String?>(null) }

    AdminScaffold(name, onBack, onRefresh = { vm.refresh() }) {
        item(key = "month") { MonthBar(month, vm::setMonth) }
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { cal ->
            item(key = "totals") {
                InfoCard {
                    TotalsCard(cal.totals, cal.compOff)
                    val kinds = cal.totals.leaveByKind.entries.joinToString(" · ") { (k, v) ->
                        (LeaveKind.entries.firstOrNull { it.name == k }?.label ?: k) + " " + leaveDaysLabel(v)
                    }
                    if (kinds.isNotEmpty()) Secondary("Leave this month: $kinds")
                    Secondary("Comp-offs all time: ${cal.compOff.earned} earned, ${leaveDaysLabel(cal.compOff.used)} used" +
                        if (cal.compOff.pending > 0) ", ${leaveDaysLabel(cal.compOff.pending)} requested" else "")
                }
            }
            item(key = "grid") {
                InfoCard {
                    MonthGrid(cal.days, cal.today, selected) { vm.selected.value = it.day }
                    Spacer(Modifier.padding(top = 4.dp))
                    CalendarLegend()
                }
            }
            val day = cal.days.firstOrNull { it.day == selected }
            if (day == null) item(key = "hint") { Secondary("Tap a day for check-in times, leave and comp-off.") }
            else item(key = "day-${day.day}") {
                AdminDayCard(day,
                    onCompOff = { action, done -> vm.compOff(day.day, action, done) },
                    onRecordLeave = { recordFor = day.day })
            }
        }
    }
    recordFor?.let { day ->
        LeaveFormDialog(
            title = "Record leave for $name",
            confirm = "Record (approved)",
            initialDay = LocalDate.parse(day),
            onDismiss = { recordFor = null },
            onSubmit = { kind, start, end, half, reason -> recordFor = null; vm.record(kind, start, end, half, reason) },
        )
    }
}

@Composable
private fun AdminDayCard(d: CalendarDay, onCompOff: (action: String, done: String) -> Unit, onRecordLeave: () -> Unit) {
    InfoCard {
        Text(LocalDate.parse(d.day).format(DayTitle), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        dayFacts(d, ::clockTime).forEach { (k, v) ->
            Row { Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp)); Text(v, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            when (d.compOff) {
                "EARNED" -> OutlinedButton(onClick = { onCompOff("REVOKE", "Comp-off taken back.") }) { Text("Take back comp-off") }
                "GRANTED" -> OutlinedButton(onClick = { onCompOff("CLEAR", "Comp-off removed.") }) { Text("Remove comp-off") }
                "REVOKED" -> OutlinedButton(onClick = { onCompOff("CLEAR", "Comp-off restored.") }) { Text("Restore comp-off") }
                else -> OutlinedButton(onClick = { onCompOff("GRANT", "Comp-off granted.") }) { Text("Grant comp-off") }
            }
            if (d.leave == null && !d.offDay) TextButton(onClick = onRecordLeave) { Text("Record leave") }
        }
    }
}

// ---- Admin: leave requests ------------------------------------------------------------------------------

class AdminLeaveViewModel(private val repo: LeaveRepository) : LoadingViewModel<AdminLeave>() {
    val filter = MutableStateFlow<String?>("PENDING")
    fun setFilter(f: String?) { filter.value = f; refresh() }
    override suspend fun fetch() = repo.adminLeave(filter.value)
    fun decide(r: LeaveRequest, approve: Boolean, note: String) =
        act("${r.displayName}: ${if (approve) "approved" else if (r.status == "APPROVED") "taken back" else "rejected"}.") { repo.decide(r.id, approve, note) }
}

/** What the admin should know before deciding: days the engineer worked anyway, and their comp-off balance. */
fun leaveWarnings(r: LeaveRequest): List<String> = buildList {
    r.workedDays?.takeIf { it > 0 }?.let { add("Checked in on $it of these days.") }
    val b = r.compOff
    if (r.kind == "COMP_OFF" && b != null) {
        if (r.status == "APPROVED") add("Comp-offs available: ${leaveDaysLabel(b.balance)}.")
        else {
            val after = b.balance - r.days
            add("Comp-offs available: ${leaveDaysLabel(b.balance)} → ${leaveDaysLabel(after)} after approval" + if (after < 0) " (more than earned)." else ".")
        }
    }
}

@Composable
fun AdminLeaveScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { AdminLeaveViewModel(it.container.leaveRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var deciding by remember { mutableStateOf<Pair<LeaveRequest, Boolean>?>(null) }

    AdminScaffold("Leave requests", onBack, onRefresh = { vm.refresh() }) {
        item(key = "filter") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PENDING" to "Waiting", null to "All", "APPROVED" to "Approved", "REJECTED" to "Rejected", "REVOKED" to "Taken back").forEach { (f, label) ->
                    FilterChip(selected = filter == f, onClick = { vm.setFilter(f) }, label = { Text(label) })
                }
            }
        }
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { data ->
            if (data.requests.isEmpty()) item(key = "empty") { InfoCard { Text(if (filter == "PENDING") "Nothing waiting for you." else "No requests.") } }
            data.requests.forEach { r ->
                item(key = r.id) {
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Secondary("${r.employeeCode} · asked ${relativeTime(r.createdAt)}")
                            }
                            StatusTag(r.status)
                        }
                        Text("${r.kindLabel} · ${spanLabel(r.startDay, r.endDay)} · ${leaveDaysLabel(r.days)}", style = MaterialTheme.typography.bodyMedium)
                        if (r.reason.isNotBlank()) Text("“${r.reason}”", style = MaterialTheme.typography.bodyMedium)
                        if (r.byAdmin) Secondary("Recorded by an admin")
                        leaveWarnings(r).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MissedTextColor) }
                        r.decidedBy?.let { Secondary("By $it" + (r.decisionNote.takeIf(String::isNotBlank)?.let { n -> ": $n" } ?: "")) }
                        when (r.status) {
                            "PENDING" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { deciding = r to true }) { Text("Approve") }
                                OutlinedButton(onClick = { deciding = r to false }) { Text("Reject") }
                            }
                            "APPROVED" -> TextButton(onClick = { deciding = r to false }) { Text("Take back", color = MaterialTheme.colorScheme.error) }
                            "REJECTED", "REVOKED" -> TextButton(onClick = { deciding = r to true }) { Text("Approve after all") }
                        }
                    }
                }
            }
        }
    }
    deciding?.let { (r, approve) ->
        var note by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deciding = null },
            title = { Text(if (approve) "Approve ${r.displayName}'s leave?" else if (r.status == "APPROVED") "Take back approved leave?" else "Reject this request?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${r.kindLabel} · ${spanLabel(r.startDay, r.endDay)}")
                    leaveWarnings(r).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MissedTextColor) }
                    OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note to ${r.displayName} (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { deciding = null; vm.decide(r, approve, note) }) { Text(if (approve) "Approve" else if (r.status == "APPROVED") "Take back" else "Reject") } },
            dismissButton = { TextButton(onClick = { deciding = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun StatusTag(status: String) {
    val scheme = MaterialTheme.colorScheme
    when (status) {
        "PENDING" -> Tag("Waiting", MissedColor.copy(alpha = 0.18f), scheme.onSurface)
        "APPROVED" -> Tag("Approved", WorkedColor.copy(alpha = 0.18f), scheme.onSurface)
        "REJECTED" -> Tag("Rejected", LeaveColor.copy(alpha = 0.15f), scheme.onSurface)
        "CANCELLED" -> Tag("Cancelled")
        "REVOKED" -> Tag("Taken back", LeaveColor.copy(alpha = 0.15f), scheme.onSurface)
        "NOT_SENT" -> Tag("Not sent yet")
        "REFUSED" -> Tag("Not accepted", scheme.errorContainer, scheme.onErrorContainer)
        else -> Tag(status)
    }
}

// ---- Admin: holidays ------------------------------------------------------------------------------------

class HolidaysViewModel(private val repo: LeaveRepository) : LoadingViewModel<List<Holiday>>() {
    val year = MutableStateFlow(companyToday().year)
    fun setYear(y: Int) { year.value = y; refresh() }
    override suspend fun fetch() = repo.holidays(year.value)
    fun add(day: String, name: String) = viewModelScope.launch {
        message.value = try {
            "Holiday saved." + affectsText(repo.setHoliday(day, name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            friendlyError(e)
        }
        refresh()
    }

    fun remove(h: Holiday) = viewModelScope.launch {
        message.value = try {
            "${h.name} removed." + affectsText(repo.removeHoliday(h.day))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            friendlyError(e)
        }
        refresh()
    }

    private fun affectsText(n: Int) = if (n == 0) "" else " It changes the comp-offs or leave days of $n ${if (n == 1) "person" else "people"}."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolidaysScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { HolidaysViewModel(it.container.leaveRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Holiday?>(null) }

    AdminScaffold("Holidays", onBack, onRefresh = { vm.refresh() }, floating = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add holiday") })
    }) {
        item(key = "year") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.setYear(year - 1) }) { Text("‹ ${year - 1}") }
                Text("$year", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { vm.setYear(year + 1) }) { Text("${year + 1} ›") }
            }
        }
        item(key = "help") { Secondary("Sunday is the weekly off. Holidays are off days too: no missed check-in, and working on one earns a comp-off.") }
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { list ->
            if (list.isEmpty()) item(key = "empty") { InfoCard { Text("No holidays for $year yet.") } }
            if (list.isNotEmpty()) item(key = "list") {
                InfoCard {
                    list.forEachIndexed { i, h ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.name, style = MaterialTheme.typography.bodyLarge)
                                Secondary(LocalDate.parse(h.day).format(DayTitle))
                            }
                            TextButton(onClick = { removing = h }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = utcMillis(if (year == companyToday().year) companyToday() else LocalDate.of(year, 1, 1)))
        var name by rememberSaveable { mutableStateOf("") }
        var pickDate by remember { mutableStateOf(false) }
        val day = picker.selectedDateMillis?.let(::utcDate)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add holiday") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) { Text(day?.format(DayTitle) ?: "Pick a date") }
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name, e.g. Diwali") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = day != null && name.isNotBlank(), onClick = { adding = false; vm.add(day.toString(), name) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
        if (pickDate) {
            DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton(onClick = { pickDate = false }) { Text("OK") } }) {
                DatePicker(picker)
            }
        }
    }
    removing?.let { h ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${h.name}?") },
            text = { Text("${LocalDate.parse(h.day).format(DayTitle)} becomes a normal working day again.") },
            confirmButton = { TextButton(onClick = { removing = null; vm.remove(h) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

// ---- Engineer: my leave ---------------------------------------------------------------------------------

class MyLeaveViewModel(private val repo: LeaveRepository) : LoadingViewModel<Pair<MyLeave, MonthCalendar?>>() {
    val month = MutableStateFlow(companyMonth())
    val selected = MutableStateFlow<String?>(null)
    val unsent = repo.observeUnsent()
    /** Holidays of this year and next, for counting working days in the form (Sundays only while offline). */
    val holidays = MutableStateFlow<Set<String>>(emptySet())
    fun setMonth(m: YearMonth) { month.value = m; selected.value = null; refresh() }
    override suspend fun fetch(): Pair<MyLeave, MonthCalendar?> {
        val leave = repo.myLeave()
        repo.markDecisionsSeen(leave.requests)
        if (holidays.value.isEmpty()) holidays.value = runCatching {
            val y = companyToday().year
            (repo.holidays(y) + repo.holidays(y + 1)).map { it.day }.toSet()
        }.getOrDefault(emptySet())
        return leave to repo.myCalendar(monthKey(month.value))
    }

    fun submit(kind: LeaveKind, start: String, end: String, half: HalfDay?, reason: String) = viewModelScope.launch {
        message.value = try {
            when (val r = repo.request(kind, start, end, half, reason)) {
                LeaveRepository.Sent.Delivered -> "Leave requested. Your admin will approve or reject it."
                LeaveRepository.Sent.Queued -> "Saved on this phone. It goes to your admin when you're back online."
                is LeaveRepository.Sent.Refused -> r.message
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            friendlyError(e)
        }
        refresh()
    }

    fun cancel(r: LeaveRequest) = act("Request cancelled.") { repo.cancel(r.id) }
    fun dismiss(r: LeaveRequest) = viewModelScope.launch { repo.dismissRefused(r.id) }
}

@Composable
fun MyLeaveScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { MyLeaveViewModel(it.container.leaveRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val unsent by vm.unsent.collectAsStateWithLifecycle(emptyList())
    val holidays by vm.holidays.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<LeaveRequest?>(null) }

    AdminScaffold("Leave", onBack, onRefresh = { vm.refresh() }, floating = {
        ExtendedFloatingActionButton(onClick = { asking = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Request leave") })
    }) {
        item(key = "message") { MessageLine(message) }
        unsent.map(LeaveRepository::fromLocal).forEach { r ->
            item(key = "local-${r.id}") { RequestCard(r, onCancel = null, onDismiss = if (r.status == "REFUSED") ({ vm.dismiss(r) }) else null) }
        }
        loadItems(state, retry = { vm.refresh() }) { (leave, cal) ->
            item(key = "balance") {
                InfoCard {
                    Text("Comp-offs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${leaveDaysLabel(leave.compOff.balance)} available", style = MaterialTheme.typography.headlineSmall, color = CompOffColor)
                    Secondary("${leave.compOff.earned} earned by working on Sundays or holidays · ${leaveDaysLabel(leave.compOff.used)} used" +
                        if (leave.compOff.pending > 0) " · ${leaveDaysLabel(leave.compOff.pending)} requested" else "")
                }
            }
            if (cal != null) {
                item(key = "month") { MonthBar(month, vm::setMonth) }
                item(key = "grid") {
                    InfoCard {
                        MonthGrid(cal.days, cal.today, selected) { vm.selected.value = it.day }
                        CalendarLegend()
                    }
                }
                cal.days.firstOrNull { it.day == selected }?.let { d ->
                    item(key = "day-${d.day}") {
                        InfoCard {
                            Text(LocalDate.parse(d.day).format(DayTitle), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            dayFacts(d, ::clockTime).forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            item(key = "requests-label") { Text("My requests", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            if (leave.requests.isEmpty()) item(key = "none") { Secondary("No leave requests yet.") }
            leave.requests.forEach { r ->
                val cancellable = r.status == "PENDING" || (r.status == "APPROVED" && r.startDay > leave.today)
                item(key = r.id) { RequestCard(r, onCancel = if (cancellable) ({ cancelling = r }) else null, onDismiss = null) }
            }
        }
    }
    if (asking) {
        LeaveFormDialog(
            title = "Request leave",
            confirm = "Send request",
            initialDay = companyToday().plusDays(1),
            holidays = holidays,
            onDismiss = { asking = false },
            onSubmit = { kind, start, end, half, reason -> asking = false; vm.submit(kind, start, end, half, reason) },
        )
    }
    cancelling?.let { r ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("Cancel this request?") },
            text = { Text("${r.kindLabel} · ${spanLabel(r.startDay, r.endDay)}") },
            confirmButton = { TextButton(onClick = { cancelling = null; vm.cancel(r) }) { Text("Cancel request") } },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun RequestCard(r: LeaveRequest, onCancel: (() -> Unit)?, onDismiss: (() -> Unit)?) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.kindLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusTag(r.status)
        }
        Text(spanLabel(r.startDay, r.endDay) + if (r.days > 0) " · ${leaveDaysLabel(r.days)}" else "", style = MaterialTheme.typography.bodyMedium)
        if (r.reason.isNotBlank()) Secondary("“${r.reason}”")
        r.localError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        r.decidedBy?.let {
            val verb = when {
                r.byAdmin -> "Recorded"
                r.status == "REVOKED" -> "Taken back"
                else -> "Decided"
            }
            Secondary("$verb by $it" + (r.decisionNote.takeIf(String::isNotBlank)?.let { n -> ": $n" } ?: ""))
        }
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel request") }
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// ---- Shared form ----------------------------------------------------------------------------------------

/** The server's limit for one request (leave.go maxLeaveSpanDays). */
private const val MAX_LEAVE_SPAN_DAYS = 62

private fun utcMillis(d: LocalDate) = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

/** Type, dates, full or half day, reason. Used by the engineer (request) and the admin (record). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaveFormDialog(
    title: String,
    confirm: String,
    initialDay: LocalDate,
    /** Holidays (yyyy-MM-dd) known on this phone; Sundays are always off. */
    holidays: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSubmit: (LeaveKind, start: String, end: String, HalfDay?, reason: String) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(LeaveKind.CASUAL) }
    var start by rememberSaveable { mutableStateOf(initialDay.toString()) }
    var end by rememberSaveable { mutableStateOf(initialDay.toString()) }
    var half by rememberSaveable { mutableStateOf<HalfDay?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end"
    val startDate = LocalDate.parse(start)
    val endDate = LocalDate.parse(end)
    val singleDay = startDate == endDate
    // Same rule as the server (leaveDays): Sundays and holidays inside the range don't count.
    val workingDays = if (endDate.isBefore(startDate)) 0.0 else generateSequence(startDate) { it.plusDays(1) }
        .takeWhile { !it.isAfter(endDate) }
        .count { it.dayOfWeek != java.time.DayOfWeek.SUNDAY && it.toString() !in holidays } * if (half != null && singleDay) 0.5 else 1.0
    val tooLong = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) >= MAX_LEAVE_SPAN_DAYS
    val valid = !endDate.isBefore(startDate) && (half == null || singleDay) && workingDays > 0 && !tooLong

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LeaveKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, LeaveKind.entries.size),
                            label = { Text(k.label, maxLines = 1) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Secondary("From")
                        OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.fillMaxWidth()) { Text(startDate.format(ShortDay) + " " + startDate.year) }
                    }
                    Column(Modifier.weight(1f)) {
                        Secondary("To")
                        OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.fillMaxWidth()) { Text(endDate.format(ShortDay) + " " + endDate.year) }
                    }
                }
                if (singleDay) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val options = listOf<HalfDay?>(null, HalfDay.FIRST, HalfDay.SECOND)
                        options.forEachIndexed { i, h ->
                            SegmentedButton(selected = half == h, onClick = { half = h }, shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                label = { Text(h?.label ?: "Full day", maxLines = 1) })
                        }
                    }
                }
                if (kind == LeaveKind.COMP_OFF) Secondary("Uses a comp-off earned by working on a Sunday or holiday.")
                OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
                when {
                    endDate.isBefore(startDate) -> Text("The end date is before the start date.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    tooLong -> Text("At most $MAX_LEAVE_SPAN_DAYS days in one request.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    workingDays == 0.0 -> Text("These days are already off (Sunday or holiday).", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    else -> Secondary(leaveDaysLabel(workingDays) + " of leave")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSubmit(kind, start, end, if (singleDay) half else null, reason) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    picking?.let { which ->
        val state = rememberDatePickerState(initialSelectedDateMillis = utcMillis(if (which == "start") startDate else endDate))
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(::utcDate)?.let { d ->
                        if (which == "start") {
                            start = d.toString()
                            if (LocalDate.parse(end).isBefore(d)) end = d.toString() // keep the range valid
                        } else end = d.toString()
                        if (start != end) half = null
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
