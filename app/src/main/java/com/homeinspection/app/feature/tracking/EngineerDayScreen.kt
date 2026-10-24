package com.homeinspection.app.feature.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.core.tracking.EngineerDay
import com.homeinspection.app.core.tracking.Stop
import com.homeinspection.app.core.tracking.TimelineItem
import com.homeinspection.app.core.tracking.TrackingRepository
import com.homeinspection.app.core.tracking.buildTimeline
import com.homeinspection.app.core.tracking.routeLengthM
import com.homeinspection.app.core.tracking.RoutePoint
import com.homeinspection.app.feature.admin.Load
import com.homeinspection.app.feature.admin.Secondary
import com.homeinspection.app.feature.admin.friendlyError
import com.homeinspection.app.feature.attendance.dayBounds
import com.homeinspection.app.feature.attendance.duration
import com.homeinspection.app.feature.attendance.openMap
import com.homeinspection.app.ui.homeInspectionViewModel
import com.homeinspection.app.ui.theme.HomeInspectionFresh
import com.homeinspection.app.ui.theme.HomeInspectionWarn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DayView(val day: EngineerDay, val stops: List<Stop>, val timeline: List<TimelineItem>)

class EngineerDayViewModel(private val repo: TrackingRepository, private val userId: String) : ViewModel() {
    val dayStart = MutableStateFlow(dayBounds(System.currentTimeMillis()).first)
    val state = MutableStateFlow<Load<DayView>>(Load.Loading)
    /** The admin's live request for this engineer; the phone joins at its next upload. */
    val liveUntil = MutableStateFlow<Long?>(null)
    val liveMessage = MutableStateFlow<String?>(null)

    val isToday get() = dayStart.value == dayBounds(System.currentTimeMillis()).first

    fun refresh() = viewModelScope.launch {
        if (state.value !is Load.Ready) state.value = Load.Loading
        state.value = try {
            val from = dayStart.value
            val to = dayBounds(from).second
            val d = repo.day(userId, from, to)
            val (stops, items) = buildTimeline(d.points, d.events)
            Load.Ready(DayView(d, stops, items))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(friendlyError(e))
        }
    }

    fun shiftDay(days: Int) {
        val c = Calendar.getInstance().apply { timeInMillis = dayStart.value; add(Calendar.DAY_OF_YEAR, days) }
        val next = dayBounds(c.timeInMillis).first
        if (next > dayBounds(System.currentTimeMillis()).first) return
        dayStart.value = next
        state.value = Load.Loading
        refresh()
    }

    fun watchLive() = viewModelScope.launch {
        liveMessage.value = try {
            liveUntil.value = repo.watchLive(userId)
            "Live view requested. The phone joins at its next upload — within about 10 minutes."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            friendlyError(e)
        }
    }
}

/**
 * One engineer's day for the admin: map (route, numbered stops, check-in/out), then a timeline of
 * stops, sharing events and silent gaps. Opening today's view asks the phone to go live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineerDayScreen(userId: String, name: String, onBack: () -> Unit) {
    val vm = homeInspectionViewModel { EngineerDayViewModel(it.container.trackingRepository, userId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val day by vm.dayStart.collectAsStateWithLifecycle()
    val liveUntil by vm.liveUntil.collectAsStateWithLifecycle()
    val liveMessage by vm.liveMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var focus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (vm.isToday) vm.watchLive() }
    LaunchedEffect(day, liveUntil) {
        // Today: refresh every minute (every 20 s while live) to follow new points.
        while (true) {
            vm.refresh()
            if (!vm.isToday) break
            delay(if ((liveUntil ?: 0) > System.currentTimeMillis()) 20_000 else 60_000)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shiftDay(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
                Text(
                    if (vm.isToday) "Today" else DateFormat.getDateInstance(DateFormat.FULL).format(Date(day)),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { vm.shiftDay(1) }, enabled = !vm.isToday) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
            }
            if (vm.isToday && liveMessage != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(liveMessage!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
            when (val s = state) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.refresh() }) { Text("Try again") }
                }
                is Load.Ready -> {
                    val v = s.value
                    val base = dayScript(v, name)
                    LeafletMap(focus?.let { "$base;$it" } ?: base, Modifier.fillMaxWidth().height(300.dp))
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        item { Summary(v) }
                        if (v.timeline.isEmpty()) item { Secondary("Nothing recorded on this day.") }
                        items(v.timeline) { item ->
                            TimelineRow(
                                item,
                                onFocus = { lat, lng -> focus = "focusOn($lat,$lng)" },
                                onOpenMaps = { lat, lng, label -> openMap(context, lat, lng, label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun dayScript(v: DayView, name: String): String {
    val t = DateFormat.getTimeInstance(DateFormat.SHORT)
    val route = v.day.points.filter { (it.accuracyM ?: 0f) <= 200f }.joinToString(",") { "[${it.lat},${it.lng}]" }
    val stops = v.stops.joinToString(",") { s ->
        "{lat:${s.lat},lng:${s.lng},label:${jsString("${t.format(Date(s.from))}–${t.format(Date(s.to))} · ${duration(s.durationMs)}")}}"
    }
    val checks = v.day.events.filter { (it.kind == "CHECK_IN" || it.kind == "CHECK_OUT") && it.lat != null && it.lng != null }.joinToString(",") { e ->
        val label = (if (e.kind == "CHECK_IN") "Checked in " else "Checked out ") + t.format(Date(e.clientAt))
        "{lat:${e.lat},lng:${e.lng},label:${jsString(label)},color:${jsString((if (e.kind == "CHECK_IN") HomeInspectionFresh else OffGrey).css())}}"
    }
    val last = v.day.points.lastOrNull()?.let {
        "{lat:${it.lat},lng:${it.lng},initials:${jsString(initials(name))},color:${jsString(HomeInspectionFresh.css())}}"
    } ?: "null"
    return "showDay({route:[$route],stops:[$stops],checks:[$checks],last:$last}, true)"
}

@Composable
private fun Summary(v: DayView) {
    val pts = v.day.points
    if (pts.isEmpty()) return
    val t = DateFormat.getTimeInstance(DateFormat.SHORT)
    val km = routeLengthM(pts.map { RoutePoint(it.clientAt, it.lat, it.lng, it.accuracyM) }) / 1000
    val stopped = v.stops.sumOf { it.durationMs }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${String.format(Locale.ROOT, "%.1f", km)} km · ${v.stops.size} stop${if (v.stops.size == 1) "" else "s"} (${duration(stopped)})",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            Secondary("First location ${t.format(Date(pts.first().clientAt))} · last ${t.format(Date(pts.last().clientAt))}" +
                (pts.last().battery?.let { " · battery $it %" } ?: ""))
            if (pts.any { it.isMock }) Text("Some locations came from a fake location app", color = AlertRed, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, onFocus: (Double, Double) -> Unit, onOpenMaps: (Double, Double, String) -> Unit) {
    val t = DateFormat.getTimeInstance(DateFormat.SHORT)
    val (dot, title, sub) = describe(item)
    val place: Pair<Double, Double>? = when (item) {
        is TimelineItem.StopItem -> item.stop.lat to item.stop.lng
        is TimelineItem.Event -> item.event.lat?.let { lat -> item.event.lng?.let { lat to it } }
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().then(if (place != null) Modifier.clickable { onFocus(place.first, place.second) } else Modifier).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(t.format(Date(item.at)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp).size(width = 56.dp, height = 20.dp))
        Box(Modifier.padding(top = 6.dp).size(10.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (sub != null) Secondary(sub)
        }
        if (item is TimelineItem.StopItem) {
            TextButton(onClick = { onOpenMaps(item.stop.lat, item.stop.lng, "Stop ${item.index}") }) { Text("Maps") }
        }
    }
}

private fun describe(item: TimelineItem): Triple<Color, String, String?> {
    val t = DateFormat.getTimeInstance(DateFormat.SHORT)
    return when (item) {
        is TimelineItem.StopItem -> Triple(
            Color(0xFF26552A), "Stop ${item.index} · ${duration(item.stop.durationMs)}",
            "${t.format(Date(item.stop.from))}–${t.format(Date(item.stop.to))} within 100 m",
        )
        is TimelineItem.Gap -> Triple(HomeInspectionWarn, "No location for ${duration(item.durationMs)}", "Phone off, no GPS, or the app was stopped")
        is TimelineItem.Event -> {
            val e = item.event
            when (e.kind) {
                "CHECK_IN" -> Triple(HomeInspectionFresh, "Checked in", if (e.lat == null) "No location at check-in" else null)
                "CHECK_OUT" -> Triple(OffGrey, "Checked out", null)
                "START" -> Triple(HomeInspectionFresh, "Sharing started", when (e.source) {
                    "CHECK_IN" -> "With check-in"
                    "MANUAL" -> "Switched on by the engineer"
                    "RESUME" -> "Resumed after it stopped"
                    "RESTORE" -> "Restarted when the app was opened"
                    else -> null
                })
                "STOP" -> Triple(OffGrey, "Sharing stopped", if (e.source == "CHECK_OUT") "With check-out" else if (e.source == "SIGN_OUT") "Signed out" else null)
                "PAUSE" -> Triple(HomeInspectionWarn, "Turned off · ${reasonLabel(e.reason)}", e.note)
                "INTERRUPTED" -> Triple(AlertRed, "Sharing stopped · ${interruptedLabel(e.reason)}", "Resumes when the engineer opens Home Inspection")
                "LOCATION_OFF" -> Triple(AlertRed, "Location switched off on the phone", null)
                "LOCATION_ON" -> Triple(HomeInspectionFresh, "Location switched back on", null)
                "MOCK" -> Triple(AlertRed, "Fake location app detected", "Locations after this may not be real")
                else -> Triple(OffGrey, e.kind, null)
            }
        }
    }
}
