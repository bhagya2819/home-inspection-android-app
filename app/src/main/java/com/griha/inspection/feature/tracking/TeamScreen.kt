package com.griha.inspection.feature.tracking

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.griha.inspection.core.tracking.TeamMember
import com.griha.inspection.core.tracking.TrackingRepository
import com.griha.inspection.feature.admin.Load
import com.griha.inspection.feature.admin.LoadingViewModel
import com.griha.inspection.feature.admin.Secondary
import com.griha.inspection.feature.admin.relativeTime
import com.griha.inspection.feature.attendance.RemotePhoto
import com.griha.inspection.feature.attendance.duration
import com.griha.inspection.ui.grihaViewModel
import com.griha.inspection.ui.theme.GrihaFresh
import com.griha.inspection.ui.theme.GrihaWarn
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// ---- How a state looks (same colour in the list, the alert strip and on the map) ----------------

val AlertRed = Color(0xFFC62828)
val OffGrey = Color(0xFF8A8F87)

data class StateLook(val label: String, val color: Color, val alert: Boolean)

fun look(m: TeamMember, now: Long): StateLook = when (m.state) {
    "SHARING" -> StateLook(if (m.onDuty) "Sharing" else "Sharing · not checked in", GrihaFresh, false)
    "STALE" -> StateLook("No location for ${duration(now - (m.lastContact ?: m.stateSince ?: now))}", GrihaWarn, true)
    "PAUSED" -> StateLook("Turned off · ${reasonLabel(m.reason)}", GrihaWarn, true)
    "INTERRUPTED" -> StateLook("Stopped · ${interruptedLabel(m.reason)}", AlertRed, true)
    "LOCATION_OFF" -> StateLook("Location switched off", AlertRed, true)
    else -> if (m.onDuty) StateLook("On duty · not sharing", GrihaWarn, true) else StateLook("Not sharing", OffGrey, false)
}

fun reasonLabel(r: String?) = when (r) {
    "LOW_BATTERY" -> "Low battery"
    "BREAK" -> "Break"
    "OTHER" -> "Other"
    else -> r ?: "no reason"
}

fun interruptedLabel(r: String?) = when (r) {
    "REBOOT" -> "phone restarted"
    "APP_UPDATED" -> "app updated"
    "PERMISSION_REMOVED" -> "location permission removed"
    else -> "app closed by the phone"
}

fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

fun Color.css() = String.format("#%06X", toArgb() and 0xFFFFFF)

// ---- Team ----------------------------------------------------------------------------------------

class TeamViewModel(private val repo: TrackingRepository) : LoadingViewModel<List<TeamMember>>() {
    override suspend fun fetch(): List<TeamMember> = repo.team()
}

/**
 * Admin → Team locations. Alert strip first, then everyone as a list or on the map. Refreshes every
 * minute while open; phones upload every 10 minutes, so "last seen" is what matters, not the second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(onBack: () -> Unit, onPerson: (userId: String, name: String) -> Unit) {
    val vm = grihaViewModel { TeamViewModel(it.container.trackingRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val repo = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.griha.inspection.GrihaApp).container.attendanceRepository
    var showMap by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { vm.refresh(); delay(60_000) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Team locations") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (val s = state) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.refresh() }) { Text("Try again") }
                }
                is Load.Ready -> {
                    val now = System.currentTimeMillis()
                    val people = s.value.sortedWith(compareBy({ !look(it, now).alert }, { it.state == "OFF" && !it.onDuty }, { it.displayName }))
                    val alerts = people.filter { look(it, now).alert || it.mockToday }
                    if (alerts.isNotEmpty()) AlertStrip(alerts, now) { onPerson(it.userId, it.displayName) }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SegmentedButton(!showMap, { showMap = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("List") }
                        SegmentedButton(showMap, { showMap = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Map") }
                    }
                    if (showMap) {
                        TeamMap(people, now, Modifier.weight(1f).fillMaxWidth()) { id -> people.firstOrNull { it.userId == id }?.let { onPerson(it.userId, it.displayName) } }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (people.isEmpty()) item { Secondary("No engineers yet.") }
                            items(people, key = { it.userId }) { m -> PersonRow(m, now, repo) { onPerson(m.userId, m.displayName) } }
                            item {
                                Secondary("Phones send their location every 10 minutes (every 30 s while you watch one live). " +
                                    "Alerts appear here when you open this screen; there are no push notifications yet.")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertStrip(alerts: List<TeamMember>, now: Long, onOpen: (TeamMember) -> Unit) {
    Surface(color = AlertRed.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            alerts.take(5).forEach { m ->
                val l = look(m, now)
                Row(Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = if (l.alert) l.color else AlertRed, modifier = Modifier.size(18.dp))
                    val what = if (l.alert) l.label else "Fake location app detected today"
                    val since = m.stateSince?.takeIf { m.state == "PAUSED" || m.state == "INTERRUPTED" || m.state == "LOCATION_OFF" }
                        ?.let { " · since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: ""
                    Text(
                        "${m.displayName}: $what$since" + (m.note?.takeIf { m.state == "PAUSED" }?.let { " — \"$it\"" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (alerts.size > 5) Secondary("+${alerts.size - 5} more in the list below")
        }
    }
}

@Composable
private fun PersonRow(m: TeamMember, now: Long, repo: com.griha.inspection.core.attendance.AttendanceRepository, onClick: () -> Unit) {
    val l = look(m, now)
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (l.alert) BorderStroke(1.dp, l.color.copy(alpha = 0.6f)) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The status colour rings the photo: the same colour as the engineer's pin on the map.
            Box(Modifier.size(52.dp).border(3.dp, l.color, CircleShape).padding(4.dp)) {
                val face = m.faceId
                if (face != null) RemotePhoto("enrol-$face", 44.dp, m.displayName) { repo.enrollmentPhotoFile(face) }
                else com.griha.inspection.ui.components.InitialsAvatar(m.displayName, size = 44)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ${m.employeeCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(l)
                val bits = buildList {
                    m.battery?.let { add("Battery $it %") }
                    if (m.pointAt != null) add("seen ${relativeTime(m.pointAt)}")
                    if (m.onDuty && m.dutySince != null) add("on duty since ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.dutySince))}")
                    if (m.liveUntil != null && m.liveUntil > now) add("live")
                }
                if (bits.isNotEmpty()) Secondary(bits.joinToString(" · "))
                if (m.mockToday) Text("Fake location app detected today", style = MaterialTheme.typography.labelSmall, color = AlertRed)
            }
        }
    }
}

@Composable
fun StatusChip(l: StateLook) {
    Row(
        Modifier.background(l.color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(l.color, CircleShape))
        Text(l.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TeamMap(people: List<TeamMember>, now: Long, modifier: Modifier, onPerson: (String) -> Unit) {
    val placed = people.filter { it.lat != null && it.lng != null && (it.state != "OFF" || it.onDuty) }
    val script = "showTeam([" + placed.joinToString(",") { m ->
        "{id:${jsString(m.userId)},name:${jsString(m.displayName + " · " + look(m, now).label)},initials:${jsString(initials(m.displayName))}," +
            "lat:${m.lat},lng:${m.lng},acc:${m.accuracyM ?: 0},color:${jsString(look(m, now).color.css())}}"
    } + "], true)"
    Column(modifier) {
        LeafletMap(script, Modifier.weight(1f).fillMaxWidth(), onPerson = onPerson)
        val hidden = people.size - placed.size
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Sharing" to GrihaFresh, "Needs a look" to GrihaWarn, "Stopped" to AlertRed).forEach { (t, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(c, CircleShape))
                    Text(t, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (hidden > 0) Text("$hidden not on the map", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
