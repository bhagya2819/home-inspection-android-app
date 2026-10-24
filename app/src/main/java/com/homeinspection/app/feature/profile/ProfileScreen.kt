package com.homeinspection.app.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.LocationOn
import com.homeinspection.app.feature.tracking.BatteryGuideDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeinspection.app.HomeInspectionApp
import com.homeinspection.app.core.auth.AuthState
import com.homeinspection.app.ui.theme.ThemeMode
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.homeinspection.app.feature.admin.formatBytes
import com.homeinspection.app.feature.sync.PhoneStorageCard
import com.homeinspection.app.feature.sync.SyncSummary
import com.homeinspection.app.feature.sync.SyncViewModel
import com.homeinspection.app.ui.components.CountBadge
import com.homeinspection.app.ui.components.InitialsAvatar
import com.homeinspection.app.ui.components.ListDivider
import com.homeinspection.app.ui.components.ListGroup
import com.homeinspection.app.ui.components.ListRow
import com.homeinspection.app.ui.components.MainBottomBar
import com.homeinspection.app.ui.components.MainTab
import com.homeinspection.app.ui.components.SectionLabel
import com.homeinspection.app.ui.homeInspectionViewModel
import com.homeinspection.app.ui.theme.HomeInspectionWarn

/** Where each admin row goes; null when the user is not an admin. */
class AdminLinks(
    val employees: () -> Unit,
    val inspections: () -> Unit,
    val devices: () -> Unit,
    val audit: () -> Unit,
    val storage: () -> Unit,
    val policy: () -> Unit,
    val ai: () -> Unit,
    val attendance: () -> Unit,
    val faces: () -> Unit,
    val team: () -> Unit,
    val calendar: () -> Unit,
    val leave: () -> Unit,
)

/**
 * Profile hub: who you are, then three labelled groups — your work (backup, phone storage),
 * admin tools (admins only), account. Every row says its current state, so most visits end here.
 */
@Composable
fun ProfileScreen(
    onHome: () -> Unit,
    onNewInspection: () -> Unit,
    onBackup: () -> Unit,
    onPhoneStorage: () -> Unit,
    onChangePassword: () -> Unit,
    onMyAttendance: () -> Unit,
    onMyLeave: () -> Unit,
    onSetUpFace: () -> Unit,
    admin: AdminLinks,
) {
    val vm = homeInspectionViewModel { SyncViewModel(it) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val auth by vm.auth.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val freeBytes by vm.freeBytes.collectAsStateWithLifecycle()
    val cleanupEnabled by vm.cleanupEnabled.collectAsStateWithLifecycle()
    val user = (auth as? AuthState.SignedIn)?.user
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var pendingLeave by remember { mutableIntStateOf(0) }
    val leaveRepo = (LocalContext.current.applicationContext as HomeInspectionApp).container.leaveRepository
    // The admin decided leave since the engineer last opened Leave (fetched after each sync).
    val newDecisions by leaveRepo.newDecisions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { leaveRepo.refreshDecisions() }

    Scaffold(
        bottomBar = { MainBottomBar(MainTab.Profile, onHome = onHome, onNew = onNewInspection, onProfile = {}) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 24.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            user?.let { u ->
                item(key = "who") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                        InitialsAvatar(u.displayName, size = 56)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(u.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${u.employeeCode} · ${if (u.isAdmin) "Administrator" else "Inspector"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "work-label") { SectionLabel("Your work") }
            item(key = "work") {
                ListGroup {
                    val s = summary
                    val problems = (s?.conflicts ?: 0) + (s?.failures ?: 0)
                    ListRow(
                        title = "Backup",
                        subtitle = s?.headline ?: "Checking…",
                        icon = backupIcon(s),
                        iconTint = backupTint(s),
                        onClick = onBackup,
                        trailing = if (problems > 0) ({ CountBadge(problems) }) else null,
                    )
                    ListDivider()
                    ListRow(
                        title = "Phone storage",
                        subtitle = storage?.let {
                            "${formatBytes(freeBytes)} free · ${it.localPhotos} photos · cleanup ${if (cleanupEnabled) "on" else "off"}"
                        } ?: "Checking…",
                        icon = Icons.Filled.Phone,
                        onClick = onPhoneStorage,
                    )
                    ListDivider()
                    ListRow("My attendance", Icons.Filled.DateRange, onMyAttendance, subtitle = "Your check-ins and check-outs")
                    ListDivider()
                    ListRow(
                        "Leave", Icons.Filled.DateRange, onMyLeave,
                        subtitle = if (newDecisions.isEmpty()) "Request leave or a comp-off; your month at a glance"
                        else newDecisions.joinToString(" · ") { "${it.kindLabel}: " + decisionWord(it.status) },
                        trailing = if (newDecisions.isNotEmpty()) ({ CountBadge(newDecisions.size) }) else null,
                    )
                    ListDivider()
                    val face by (LocalContext.current.applicationContext as HomeInspectionApp).container.faceRepository.profile.collectAsStateWithLifecycle()
                    ListRow(
                        "Face check-in",
                        Icons.Filled.Face,
                        onSetUpFace,
                        subtitle = when {
                            face?.approved == true && face?.pendingId != null -> "Set up · new photos waiting for approval"
                            face?.approved == true -> "Set up and approved · tap to redo"
                            face?.pendingId != null -> "Waiting for your admin's approval"
                            face?.rejected == true -> "Not approved · set it up again"
                            else -> "Not set up yet"
                        },
                    )
                    ListDivider()
                    var guide by remember { mutableStateOf(false) }
                    ListRow("Location sharing on this phone", Icons.Filled.LocationOn, { guide = true },
                        subtitle = "Battery settings that keep sharing working")
                    if (guide) {
                        val tracking = (LocalContext.current.applicationContext as HomeInspectionApp).container.trackingRepository
                        BatteryGuideDialog(onDone = { tracking.batteryGuideDone = true; guide = false }, onDismiss = { guide = false })
                    }
                }
            }

            if (user?.isAdmin == true) {
                item(key = "admin-label") { SectionLabel("Admin") }
                item(key = "admin-poll") {
                    // Leave requests waiting for the admin: an in-app alert, fetched when Profile opens (no push yet).
                    LaunchedEffect(Unit) { pendingLeave = runCatching { leaveRepo.pendingCount() }.getOrDefault(0) }
                }
                item(key = "admin") {
                    ListGroup {
                        ListRow("Employees", Icons.Filled.Face, admin.employees, subtitle = "Add, disable, reset passwords")
                        ListDivider()
                        ListRow("Team locations", Icons.Filled.LocationOn, admin.team, subtitle = "Where everyone is, routes and alerts")
                        ListDivider()
                        ListRow("Attendance calendar", Icons.Filled.DateRange, admin.calendar, subtitle = "Who worked which day, leave, comp-offs, holidays")
                        ListDivider()
                        ListRow(
                            "Leave requests", Icons.Filled.CheckCircle, admin.leave, subtitle = "Approve or reject engineers' leave",
                            trailing = if (pendingLeave > 0) ({ CountBadge(pendingLeave) }) else null,
                        )
                        ListDivider()
                        ListRow("Attendance by day", Icons.Filled.DateRange, admin.attendance, subtitle = "Who checked in, when and where")
                        ListDivider()
                        ListRow("Face set-ups", Icons.Filled.CheckCircle, admin.faces, subtitle = "Approve engineers' faces for check-in")
                        ListDivider()
                        ListRow("Inspections backup", Icons.AutoMirrored.Filled.List, admin.inspections, subtitle = "Is every inspection on the server?")
                        ListDivider()
                        ListRow("Phones", Icons.Filled.Phone, admin.devices, subtitle = "What each phone still has to upload")
                        ListDivider()
                        ListRow("Exports and server storage", Icons.Filled.DateRange, admin.storage, subtitle = "Monthly zips, free server space")
                        ListDivider()
                        ListRow("Retention policy", Icons.Filled.Delete, admin.policy, subtitle = "When phones remove original photos")
                        ListDivider()
                        ListRow("AI assistant", Icons.Filled.Star, admin.ai, subtitle = "DeepSeek key, daily limit, monthly use")
                        ListDivider()
                        ListRow("Audit log", Icons.Filled.Info, admin.audit, subtitle = "Sign-ins, changes, exports")
                    }
                }
            }

            item(key = "appearance-label") { SectionLabel("Appearance") }
            item(key = "appearance") { AppearancePicker() }

            item(key = "account-label") { SectionLabel("Account") }
            item(key = "account") {
                ListGroup {
                    ListRow("Change password", Icons.Filled.Lock, onChangePassword)
                    ListDivider()
                    ListRow(
                        "Sign out",
                        Icons.AutoMirrored.Filled.ExitToApp,
                        onClick = { confirmSignOut = true },
                        contentColor = MaterialTheme.colorScheme.error,
                        showChevron = false,
                    )
                }
            }
            item(key = "version") {
                val context = LocalContext.current
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                Text(
                    "Home Inspection${version?.let { " · v$it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }

    if (confirmSignOut) {
        val waiting = summary?.pending ?: 0
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "Everything stays on this phone." +
                        if (waiting > 0) " $waiting items still waiting to upload will go up after the next sign-in." else "",
                )
            },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/** Light / Dark / Phone setting. Remembered on this phone; Light is the default. */
@Composable
private fun AppearancePicker() {
    val settings = (LocalContext.current.applicationContext as HomeInspectionApp).container.themeSettings
    val mode by settings.mode.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    ListGroup {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
            ThemeMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = {
                        if (mode != m) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        settings.set(m)
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    label = { Text(m.label, maxLines = 1) },
                )
            }
        }
    }
}

private fun backupIcon(s: SyncSummary?) = when {
    s == null || s.status.running -> Icons.Filled.Refresh
    s.healthy -> Icons.Filled.CheckCircle
    s.conflicts > 0 || s.failures > 0 || !s.signedInCanSync -> Icons.Filled.Warning
    else -> Icons.Filled.Refresh
}

@Composable
private fun backupTint(s: SyncSummary?) = when {
    s == null || s.status.running -> MaterialTheme.colorScheme.onSurfaceVariant
    s.healthy -> MaterialTheme.colorScheme.primary
    s.conflicts > 0 || s.failures > 0 || !s.signedInCanSync -> HomeInspectionWarn
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Phone storage and the opt-in 30-day cleanup, on its own screen (was buried in "Backup & account"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneStorageScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { SyncViewModel(it) }
    val storage by vm.storage.collectAsStateWithLifecycle()
    val freeBytes by vm.freeBytes.collectAsStateWithLifecycle()
    val retentionSettings by vm.retentionSettings.collectAsStateWithLifecycle()
    val cleanupEnabled by vm.cleanupEnabled.collectAsStateWithLifecycle()
    val cleanupResult by vm.cleanupResult.collectAsStateWithLifecycle()
    val cleaning by vm.cleaning.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStorage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone storage") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp)) {
            item {
                PhoneStorageCard(
                    overview = storage,
                    freeBytes = freeBytes,
                    settings = retentionSettings,
                    enabled = cleanupEnabled,
                    lastResult = cleanupResult,
                    cleaning = cleaning,
                    onEnable = vm::setCleanup,
                    onCleanNow = { vm.cleanNow() },
                )
            }
        }
    }
}

private fun decisionWord(status: String) = when (status) {
    "APPROVED" -> "approved"
    "REJECTED" -> "rejected"
    "REVOKED" -> "taken back"
    else -> status.lowercase()
}
