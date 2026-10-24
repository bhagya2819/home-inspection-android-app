package com.griha.inspection.feature.tracking

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.griha.inspection.GrihaApp
import com.griha.inspection.core.auth.AuthState
import com.griha.inspection.core.tracking.PauseReason
import com.griha.inspection.core.tracking.SharingMode
import com.griha.inspection.core.tracking.SharingStatus
import com.griha.inspection.ui.components.ChipTone
import com.griha.inspection.ui.components.WorkChip
import com.griha.inspection.ui.components.WorkPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Location-sharing state for Home's pill and panel. */
data class SharingUi(val mode: SharingMode, val status: SharingStatus, val locationOn: Boolean, val live: Boolean, val now: Long)

@Composable
fun rememberSharingUi(): SharingUi {
    val context = LocalContext.current
    val c = (context.applicationContext as GrihaApp).container
    val repo = c.trackingRepository
    val status by repo.status.collectAsStateWithLifecycle()
    val liveUntil by repo.liveUntil.collectAsStateWithLifecycle()
    val auth by c.authRepository.state.collectAsStateWithLifecycle()
    val userId = (auth as? AuthState.SignedIn)?.user?.id
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var locationOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        while (true) {
            now = System.currentTimeMillis()
            locationOn = LocationManagerCompat.isLocationEnabled(lm)
            delay(15_000)
        }
    }
    // Someone else's sharing on a shared phone isn't this engineer's business.
    val mode = if (status.userId != null && status.userId != userId) SharingMode.OFF else status.mode
    return SharingUi(mode, status, locationOn, liveUntil > now, now)
}

fun SharingUi.tone() = when (mode) {
    SharingMode.SHARING -> if (locationOn) ChipTone.OK else ChipTone.ALERT
    SharingMode.PAUSED, SharingMode.INTERRUPTED -> ChipTone.WARN
    SharingMode.OFF -> ChipTone.OFF
}

/** Home: is my location shared, as a small pill. Separate from duty on purpose (user decision). */
@Composable
fun SharingChip(ui: SharingUi, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (ui.mode) {
        SharingMode.SHARING -> when {
            !ui.locationOn -> "Location is off"
            ui.live -> "Sharing · live"
            else -> "Sharing"
        }
        SharingMode.PAUSED -> "Sharing paused"
        SharingMode.INTERRUPTED -> "Sharing stopped"
        SharingMode.OFF -> "Not sharing"
    }
    WorkChip(Icons.Filled.LocationOn, text, ui.tone(), expanded, onClick, modifier)
}

@Composable
fun SharingPanel(ui: SharingUi) {
    val context = LocalContext.current
    val repo = (context.applicationContext as GrihaApp).container.trackingRepository
    val scope = rememberCoroutineScope()
    var showGuide by remember { mutableStateOf(false) }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val s = ui.status
    val (title, sub) = when (ui.mode) {
        SharingMode.SHARING -> when {
            !ui.locationOn -> "Location is switched off" to "Turn on Location in quick settings. Your admin can see it is off."
            ui.live -> "Sharing your location" to "Your admin is viewing your location live"
            else -> "Sharing your location" to "Since ${time.format(Date(s.since))} · your admin can see where you are"
        }
        SharingMode.PAUSED -> "Location sharing turned off" to
            "${PauseReason.entries.firstOrNull { it.name == s.reason }?.label ?: "Turned off"} · since ${time.format(Date(s.since))}. Your admin can see this."
        SharingMode.INTERRUPTED -> "Location sharing stopped" to interruptedText(s.reason)
        SharingMode.OFF -> "Location sharing is off" to "Starts by itself when you check in. You can also share now."
    }
    WorkPanel(title, sub, ui.tone()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (ui.mode) {
                SharingMode.SHARING -> {
                    if (!ui.locationOn) {
                        Button(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }) {
                            Text("Location settings")
                        }
                    }
                    OutlinedButton(onClick = { repo.turnOffAsked.value = true }) { Text("Turn off") }
                }
                SharingMode.INTERRUPTED -> Button(onClick = { scope.launch { repo.start("RESUME") } }) { Text("Resume") }
                SharingMode.PAUSED -> Button(onClick = { scope.launch { repo.start("MANUAL") } }) { Text("Turn on") }
                SharingMode.OFF -> Button(onClick = { scope.launch { repo.start("MANUAL") } }) { Text("Share location") }
            }
        }
        if (ui.mode == SharingMode.SHARING && !repo.batteryGuideDone) {
            TextButton(onClick = { showGuide = true }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text("Keep sharing working on this phone (1 min)", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (showGuide) BatteryGuideDialog(onDone = { repo.batteryGuideDone = true; showGuide = false }, onDismiss = { showGuide = false })
}

fun interruptedText(reason: String?) = when (reason) {
    "REBOOT" -> "The phone restarted. Tap Resume to share again."
    "APP_UPDATED" -> "Griha was updated. Tap Resume to share again."
    "PERMISSION_REMOVED" -> "Location permission was removed. Tap Resume and allow location."
    else -> "The phone stopped Griha in the background. Tap Resume to share again."
}
