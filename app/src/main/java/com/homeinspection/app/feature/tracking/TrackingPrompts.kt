package com.homeinspection.app.feature.tracking

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeinspection.app.HomeInspectionApp
import com.homeinspection.app.core.auth.AuthState
import com.homeinspection.app.core.tracking.PauseReason
import com.homeinspection.app.core.tracking.SharingMode
import kotlinx.coroutines.launch

/**
 * App-wide prompts for location sharing, over whatever screen is open: the one-time consent
 * notice, the location permission, the notification permission, and "why are you turning it off?".
 * A start from a check-in waits here (pendingStart) until consent and permission are given.
 */
@Composable
fun TrackingPrompts() {
    val context = LocalContext.current
    val c = (context.applicationContext as HomeInspectionApp).container
    val repo = c.trackingRepository
    val status by repo.status.collectAsStateWithLifecycle()
    val turnOff by repo.turnOffAsked.collectAsStateWithLifecycle()
    val auth by c.authRepository.state.collectAsStateWithLifecycle()
    val userId = (auth as? AuthState.SignedIn)?.user?.id ?: return
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) } // consent/permission aren't flows: re-read after each step

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        scope.launch {
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                repo.startPending()
            } else {
                repo.dropPending()
                android.widget.Toast.makeText(context, "Allow location for Home Inspection (precise) to share your location.", android.widget.Toast.LENGTH_LONG).show()
            }
            tick++
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val pending = status.pendingStart
    val needsConsent = remember(tick, pending, userId) { pending != null && !repo.hasConsent(userId) }
    val needsPermission = remember(tick, pending, needsConsent) { pending != null && !needsConsent && !repo.hasPermission() }

    if (needsConsent) {
        ConsentDialog(
            fromCheckIn = pending == "CHECK_IN",
            onAccept = { scope.launch { repo.acceptConsent(); repo.startPending(); tick++ } },
            onDecline = { repo.dropPending(); tick++ },
        )
    }
    LaunchedEffect(needsPermission, pending) {
        if (needsPermission) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Android 13+: without it the "sharing" notification (and its Turn off button) is hidden. Asked once per app run.
    var askedNotifications by remember { mutableStateOf(false) }
    LaunchedEffect(status.mode) {
        if (status.mode == SharingMode.SHARING && Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (turnOff) {
        if (status.mode != SharingMode.SHARING) {
            LaunchedEffect(Unit) { repo.turnOffAsked.value = false }
        } else {
            TurnOffDialog(
                onConfirm = { reason, note -> scope.launch { repo.pause(reason, note); repo.turnOffAsked.value = false } },
                onCancel = { repo.turnOffAsked.value = false },
            )
        }
    }
}

/** The one-time notice (India DPDP Act 2023: say what is collected, why, for how long). */
@Composable
private fun ConsentDialog(fromCheckIn: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Location sharing") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromCheckIn) Text("You're checked in. Home Inspection now shares your location with your admin while you work.")
                Text("• Your location is shared only while sharing is on: it starts when you check in and stops when you check out. " +
                    "You can also switch it on yourself.")
                Text("• Your admin sees where you are, the route you took and how long you stayed at each place.")
                Text("• You can turn it off at any time with a reason (for example low battery). Your admin is told.")
                Text("• Locations are kept on the Home Inspection server for 90 days, then deleted.")
                Text("• Nothing is recorded while sharing is off.")
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("I understand") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Not now") } },
    )
}

@Composable
private fun TurnOffDialog(onConfirm: (PauseReason, String?) -> Unit, onCancel: () -> Unit) {
    var reason by remember { mutableStateOf<PauseReason?>(null) }
    var note by remember { mutableStateOf("") }
    val canConfirm = reason != null && (reason != PauseReason.OTHER || note.isNotBlank())
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Turn off location sharing?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your admin will see that you turned it off, and why. It stays off until you turn it on or check out.",
                    style = MaterialTheme.typography.bodyMedium)
                PauseReason.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = reason == r, onClick = { reason = r }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r.label)
                    }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(300) },
                    label = { Text(if (reason == PauseReason.OTHER) "Reason (required)" else "Note (optional)") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
            }
        },
        confirmButton = { TextButton(enabled = canConfirm, onClick = { onConfirm(reason!!, note) }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep sharing") } },
    )
}

/**
 * MIUI (and other aggressive battery savers) stop background apps unless told not to. Each step
 * opens the right settings page; the engineer changes it there themselves.
 */
@Composable
fun BatteryGuideDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val xiaomi = isXiaomi()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ } // back from the settings page
    val exempt = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep location sharing working") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Phones stop apps in the background to save battery. Sharing uses little battery, but these settings stop " +
                    "the phone from switching it off without telling you.", style = MaterialTheme.typography.bodyMedium)
                GuideStep(
                    1, "Allow Home Inspection to run in the background",
                    if (exempt) "Done" else "Tap Allow on the next screen.", done = exempt,
                ) { requestIgnoreBatteryOptimisation(context) }
                if (xiaomi) {
                    GuideStep(2, "Autostart: on", "Find Home Inspection in the list and switch it on.") { openAutostart(context) }
                    GuideStep(3, "Battery saver: No restrictions", "Battery saver → choose \"No restrictions\".") { openMiuiBatterySaver(context) }
                    GuideStep(4, "Lock Home Inspection in recent apps", "Open recent apps, hold Home Inspection's card and tap the lock.", button = null) {}
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

@Composable
private fun GuideStep(n: Int, title: String, hint: String, done: Boolean = false, button: String? = "Open", onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$n. $title", fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (button != null && !done) OutlinedButton(onClick = onOpen) { Text(button) }
    }
}

fun isXiaomi() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

private fun requestIgnoreBatteryOptimisation(context: Context) {
    @Suppress("BatteryLife") // sideloaded field app whose job is background location: the documented exception
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    if (!tryStart(context, ask)) openAppDetails(context)
}

private fun openAutostart(context: Context) {
    val i = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    if (!tryStart(context, i)) openAppDetails(context)
}

private fun openMiuiBatterySaver(context: Context) {
    val i = Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", "Home Inspection")
    if (!tryStart(context, i)) openAppDetails(context)
}

private fun openAppDetails(context: Context) {
    tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
