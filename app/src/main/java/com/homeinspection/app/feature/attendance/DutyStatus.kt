package com.homeinspection.app.feature.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeinspection.app.HomeInspectionApp
import com.homeinspection.app.core.auth.AuthState
import com.homeinspection.app.ui.components.ChipTone
import com.homeinspection.app.ui.components.WorkChip
import com.homeinspection.app.ui.components.WorkPanel
import com.homeinspection.app.ui.theme.HomeInspectionWarn
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Duty state for Home's pill and panel. */
data class DutyUi(
    val onDuty: Boolean,
    val since: Long?,
    /** Checked in on an earlier day and never checked out. */
    val forgotten: Boolean,
    val lastOutToday: Long?,
    val waitingUpload: Boolean,
    val face: FaceHint,
    val now: Long,
)

enum class FaceHint { OK, SET_UP, REJECTED, PENDING }

@Composable
fun rememberDutyUi(): DutyUi {
    val c = (LocalContext.current.applicationContext as HomeInspectionApp).container
    val auth by c.authRepository.state.collectAsStateWithLifecycle()
    val userId = (auth as? AuthState.SignedIn)?.user?.id
    val latestFlow = remember(userId) { c.attendanceRepository.observeLatest(userId) }
    val latest by latestFlow.collectAsStateWithLifecycle(null)
    val face by c.faceRepository.profile.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val last = latest
    val onDuty = last?.isIn == true
    val f = face
    val hint = when {
        userId == null -> FaceHint.OK
        f == null || f.userId != userId || (!f.approved && f.pendingId == null) -> if (f?.rejected == true) FaceHint.REJECTED else FaceHint.SET_UP
        !f.approved && f.pendingId != null -> FaceHint.PENDING
        else -> FaceHint.OK
    }
    return DutyUi(
        onDuty = onDuty, since = last?.clientAt, forgotten = onDuty && !sameDay(last.clientAt, now),
        lastOutToday = last?.takeIf { !it.isIn && sameDay(it.clientAt, now) }?.clientAt,
        waitingUpload = last != null && !last.uploaded, face = hint, now = now,
    )
}

fun DutyUi.tone() = when {
    forgotten -> ChipTone.WARN
    onDuty -> ChipTone.OK
    face == FaceHint.SET_UP || face == FaceHint.REJECTED -> ChipTone.OFF
    else -> ChipTone.ACTION
}

/**
 * Home: on duty or not, as a small pill. Not checked in is only a reminder — inspections stay fully
 * usable (user decision 2026-10-21). The panel under it holds the button.
 */
@Composable
fun DutyChip(ui: DutyUi, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = when {
        ui.forgotten -> "Still checked in"
        ui.onDuty -> "On duty · ${shortDuration(ui.now - (ui.since ?: ui.now))}"
        ui.face == FaceHint.SET_UP || ui.face == FaceHint.REJECTED -> "Set up check-in"
        else -> "Check in"
    }
    WorkChip(Icons.Filled.Face, text, ui.tone(), expanded, onClick, modifier)
}

@Composable
fun DutyPanel(ui: DutyUi, onCheckIn: () -> Unit, onCheckOut: () -> Unit, onSetUpFace: () -> Unit) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val title = when {
        ui.forgotten -> "Still checked in since ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ui.since!!))}"
        ui.onDuty -> "On duty since ${time.format(Date(ui.since!!))}"
        else -> "You're not checked in"
    }
    val sub = when {
        ui.forgotten -> "Did you forget to check out? Check out now; your admin will see the time."
        ui.onDuty -> duration(ui.now - ui.since!!) + if (ui.waitingUpload) " · waiting to upload" else ""
        ui.lastOutToday != null -> "Checked out at ${time.format(Date(ui.lastOutToday))}" + if (ui.waitingUpload) " · waiting to upload" else ""
        else -> "Check in with your face when you start work."
    }
    WorkPanel(title, sub, ui.tone()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.onDuty) OutlinedButton(onClick = onCheckOut) { Text("Check out") }
            else if (ui.face != FaceHint.SET_UP && ui.face != FaceHint.REJECTED) Button(onClick = onCheckIn) { Text("Check in") }
            when (ui.face) {
                FaceHint.SET_UP -> Button(onClick = onSetUpFace) { Text("Set up face check-in") }
                FaceHint.REJECTED -> Button(onClick = onSetUpFace) { Text("Set up again") }
                else -> {}
            }
        }
        when (ui.face) {
            FaceHint.REJECTED -> Text("Your face set-up was not approved.", style = MaterialTheme.typography.labelMedium, color = HomeInspectionWarn)
            FaceHint.PENDING -> Text("Face set-up waiting for your admin's approval", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {}
        }
    }
}

/** "45m", "1h 4m": fits a pill. */
fun shortDuration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}

fun duration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** Local midnight of the day containing [at], and of the next day. */
fun dayBounds(at: Long): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val start = c.timeInMillis
    c.add(Calendar.DAY_OF_YEAR, 1)
    return start to c.timeInMillis
}
