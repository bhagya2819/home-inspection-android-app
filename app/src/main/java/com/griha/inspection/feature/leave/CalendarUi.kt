package com.griha.inspection.feature.leave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.griha.inspection.core.leave.CalendarDay
import com.griha.inspection.core.leave.CalendarTotals
import com.griha.inspection.core.leave.CompOffBalance
import com.griha.inspection.core.leave.LeaveKind
import com.griha.inspection.feature.attendance.duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// The calendar's colours (user's choice): green = worked, red ring = leave, yellow = missed
// check-in or check-out, blue ring = comp-off taken, grey = Sunday / holiday. Fixed colours, not
// theme colours, so they mean the same thing on the web dashboard and in dark mode.
val WorkedColor = Color(0xFF2E7D32)
val LeaveColor = Color(0xFFC62828)
val MissedColor = Color(0xFFE0A100)
val CompOffColor = Color(0xFF1565C0)
val OffColor = Color(0xFFB0B6AD)
/** Readable text in the missed colour (warnings on leave requests). */
val MissedTextColor = Color(0xFF8A5A00)

val StatusLabels = mapOf(
    "WORKED" to "Worked", "MISSED_CHECKOUT" to "Missed check-out", "MISSED" to "Missed check-in", "LEAVE" to "Leave",
    "HALF_LEAVE" to "Half-day leave", "OFF" to "Sunday / holiday", "TODAY" to "Today", "COMP_OFF" to "Comp-off taken",
    "FUTURE" to "Upcoming", "NONE" to "Not tracked",
    "WORKED_ON_LEAVE" to "Worked on a leave day", "HALF_LEAVE_WORKED" to "Half-day leave, worked the other half",
    "HALF_LEAVE_MISSED" to "Half-day leave, no check-in for the other half", "HALF_LEAVE_MISSED_CHECKOUT" to "Half-day leave, no check-out",
)

/** Calendar days are India dates (the server uses a fixed +05:30), whatever zone the phone is set to. */
val CompanyZone: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
fun companyToday(): LocalDate = LocalDate.now(CompanyZone)
fun companyMonth(): YearMonth = YearMonth.now(CompanyZone)

private val MonthTitle = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
val DayTitle: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
val ShortDay: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

fun monthKey(m: YearMonth): String = m.toString() // yyyy-MM

fun leaveDaysLabel(days: Double) = (if (days % 1.0 == 0.0) days.toInt().toString() else "%.1f".format(Locale.ROOT, days)) +
    if (days == 1.0 || days == -1.0) " day" else " days"

fun spanLabel(start: String, end: String): String {
    val s = LocalDate.parse(start)
    val e = LocalDate.parse(end)
    return if (s == e) s.format(DayTitle) else s.format(ShortDay) + " – " + e.format(if (s.year == e.year) ShortDay else DayTitle) +
        if (s.year == e.year) " " + e.year else ""
}

/** The coloured mark of one day's status. Nothing for future days, days before joining, or an empty today. */
@Composable
fun DayMark(mark: String, size: Dp = 12.dp, onDutyToday: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.2f)
        val inset = stroke.width / 2
        val ring = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        when (mark) {
            "WORKED" -> drawCircle(WorkedColor)
            "MISSED" -> drawCircle(MissedColor)
            "MISSED_CHECKOUT" -> drawRoundRect(MissedColor, cornerRadius = CornerRadius(this.size.minDimension * 0.25f))
            "LEAVE" -> drawArc(LeaveColor, 0f, 360f, false, Offset(inset, inset), ring, style = stroke)
            "COMP_OFF" -> drawArc(CompOffColor, 0f, 360f, false, Offset(inset, inset), ring, style = stroke)
            "HALF_LEAVE" -> {
                drawArc(LeaveColor, 90f, 180f, true, Offset.Zero, this.size)
                drawArc(LeaveColor, 0f, 360f, false, Offset(inset, inset), ring, style = stroke)
            }
            // Half-day leave: left half red, right half what the other half was.
            "HALF_LEAVE_WORKED" -> {
                drawArc(LeaveColor, 90f, 180f, true, Offset.Zero, this.size)
                drawArc(WorkedColor, 270f, 180f, true, Offset.Zero, this.size)
            }
            "HALF_LEAVE_MISSED", "HALF_LEAVE_MISSED_CHECKOUT" -> {
                drawArc(LeaveColor, 90f, 180f, true, Offset.Zero, this.size)
                drawArc(MissedColor, 270f, 180f, true, Offset.Zero, this.size)
            }
            "WORKED_ON_LEAVE" -> {
                drawCircle(WorkedColor)
                drawArc(LeaveColor, 0f, 360f, false, Offset(inset, inset), ring, style = stroke)
            }
            // A leave request waiting for the admin (team strip).
            "PENDING" -> drawCircle(MissedColor, radius = this.size.minDimension * 0.3f, style = Stroke(width = this.size.minDimension * 0.15f))
            "OFF" -> drawCircle(OffColor, radius = this.size.minDimension * 0.28f)
            "TODAY" -> if (onDutyToday) drawArc(WorkedColor, 0f, 360f, false, Offset(inset, inset), ring, style = stroke)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarLegend() {
    val items = listOf("WORKED" to "Worked", "LEAVE" to "Leave", "HALF_LEAVE_WORKED" to "Half day + worked",
        "HALF_LEAVE_MISSED" to "Half day, other half missed", "WORKED_ON_LEAVE" to "Worked on leave", "COMP_OFF" to "Comp-off taken",
        "MISSED" to "Missed check-in", "MISSED_CHECKOUT" to "Missed check-out", "OFF" to "Sunday / holiday")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (mark, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DayMark(mark, 11.dp)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("★", color = CompOffColor, fontSize = 11.sp)
            Text("Comp-off earned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MonthBar(month: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
        Text(month.format(MonthTitle), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
    }
}

/** A calendar-app month: weeks start on Monday, one cell per day with its number, mark and hours. */
@Composable
fun MonthGrid(days: List<CalendarDay>, today: String, selected: String?, onSelect: (CalendarDay) -> Unit) {
    if (days.isEmpty()) return
    val lead = LocalDate.parse(days.first().day).dayOfWeek.value - 1 // Monday = 0
    val cells: List<CalendarDay?> = List(lead) { null } + days
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = if (it == "Sun") LeaveColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f)) { if (d != null) DayCell(d, d.day == today, d.day == selected) { onSelect(d) } }
                }
            }
        }
    }
}

@Composable
private fun DayCell(d: CalendarDay, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        d.offDay -> scheme.surfaceVariant.copy(alpha = 0.6f)
        else -> scheme.surfaceContainerLow
    }
    val border = when {
        isSelected -> scheme.primary
        isToday -> scheme.primary.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    Column(
        Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(8.dp)).background(bg)
            .border(if (isSelected) 2.dp else 1.dp, border, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.day.takeLast(2).trimStart('0'), style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
            if (d.compOffEarned) Text("★", color = CompOffColor, fontSize = 9.sp)
            if (d.clockSkew) Text("!", color = MissedColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) { DayMark(d.mark, 13.dp, onDutyToday = d.firstIn != null) }
        val note = when {
            d.worked && d.workedMs > 0 -> hoursShort(d.workedMs)
            d.leave != null -> LeaveKind.entries.firstOrNull { it.name == d.leave.kind }?.label?.take(6) ?: ""
            d.pendingLeave != null -> "asked"
            d.holiday != null -> d.holiday
            else -> ""
        }
        Text(note, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = scheme.onSurfaceVariant)
    }
}

fun hoursShort(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "${m}m" else "${m / 60}h${(m % 60).toString().padStart(2, '0')}"
}

/** A thin strip of one engineer's month for the team list (one mark per day; a ring = leave requested). */
@Composable
fun MonthStrip(statuses: List<String>, today: String, month: YearMonth, pending: List<String> = emptyList()) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
        statuses.forEachIndexed { i, st ->
            val day = month.atDay(i + 1).toString()
            Box(Modifier.weight(1f).height(10.dp), contentAlignment = Alignment.Center) {
                when {
                    day in pending && st in setOf("FUTURE", "TODAY", "MISSED", "NONE") -> DayMark("PENDING", 8.dp)
                    day <= today || st == "LEAVE" || st == "COMP_OFF" || st.startsWith("HALF_LEAVE") -> DayMark(st, 8.dp)
                }
            }
        }
    }
}

@Composable
fun TotalsCard(t: CalendarTotals, compOff: CompOffBalance?) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(t.daysWorked.toString(), if (t.workedOnLeave > 0) "days worked (${t.workedOnLeave} on leave)" else "days worked", WorkedColor)
        Stat(hoursShort(t.workedMs), "hours")
        Stat(leaveDaysLabel(t.leaveDays).substringBefore(' '), "leave days", LeaveColor)
        Stat((t.missed).toString(), "missed check-ins", MissedColor)
        Stat(t.missedCheckouts.toString(), "missed check-outs", MissedColor)
        if (compOff != null) Stat(leaveDaysLabel(compOff.balance).substringBefore(' '), "comp-off balance", if (compOff.balance < 0) LeaveColor else CompOffColor)
    }
}

@Composable
private fun Stat(value: String, label: String, accent: Color? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = accent ?: MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What happened on a day, as label → value lines. */
fun dayFacts(d: CalendarDay, time: (Long) -> String): List<Pair<String, String>> = buildList {
    add("Status" to (StatusLabels[d.mark] ?: d.status))
    d.holiday?.let { add("Holiday" to it) }
    if (d.worked) {
        add("Checked in" to (d.firstIn?.let(time) ?: "the day before"))
        add("Checked out" to (d.lastOut?.let(time) ?: "—"))
        add("Worked" to duration(d.workedMs))
    }
    if (d.overnight) add("Shift" to "Continued from the previous day (counted from midnight)")
    if (d.clockSkew) add("Clock" to "The phone's clock was off; times are the server's estimate")
    if (d.status == "WORKED_ON_LEAVE") add("Note" to "Worked on a leave day: counted as worked, not as leave")
    d.leave?.let { add("Leave" to it.label + (it.reason.takeIf(String::isNotBlank)?.let { r -> " — $r" } ?: "")) }
    d.pendingLeave?.let { add("Requested" to it.label + " · waiting for approval") }
    when (d.compOff) {
        "EARNED" -> add("Comp-off" to "Earned (worked on an off day)" + when (d.compOffCheck) {
            "SHORT" -> " · check: under an hour of work"
            "CLOCK" -> " · check: the phone's clock was off"
            else -> ""
        })
        "GRANTED" -> add("Comp-off" to "Granted by the admin")
        "REVOKED" -> add("Comp-off" to "Taken back by the admin")
    }
}

fun monthName(m: YearMonth): String = m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
