package com.homeinspection.app.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.core.admin.AdminDevice
import com.homeinspection.app.core.admin.AdminInspection
import com.homeinspection.app.core.admin.AdminRepository
import com.homeinspection.app.core.admin.AdminUser
import com.homeinspection.app.core.admin.AuditEntry
import com.homeinspection.app.core.admin.ServerSettings
import com.homeinspection.app.core.admin.StorageInfo
import com.homeinspection.app.ui.homeInspectionViewModel
import com.homeinspection.app.ui.theme.HomeInspectionWarn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

// ---- Hub ---------------------------------------------------------------------------------------

class AdminHubViewModel(private val admin: AdminRepository) : LoadingViewModel<Pair<ServerSettings, StorageInfo>>() {
    override suspend fun fetch() = admin.settings() to admin.storage()

    fun saveSettings(retentionDays: Int?, archiveDays: Int?) =
        act("Retention policy saved. Phones pick it up at their next sync.") { admin.updateSettings(retentionDays, archiveDays) }
}

@Composable
fun RetentionPolicyScreen(onBack: () -> Unit, onStorage: () -> Unit) {
    val vm = homeInspectionViewModel { AdminHubViewModel(it.container.adminRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var editPolicy by remember { mutableStateOf(false) }

    AdminScaffold("Retention policy", onBack, onRefresh = { vm.refresh() }) {
        val storage = (state as? Load.Ready)?.value?.second
        if (storage?.needsExport == true) {
            item(key = "warn") { ExportWarning(storage, onStorage) }
        }
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { (settings, _) ->
            item(key = "policy") {
                InfoCard {
                    Text("Retention policy", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Phones remove a photo's original ${settings.retentionDays} days after the report is finalized and " +
                            "the photo is backed up, once it is in a verified export.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "The server keeps files of finalized inspections at least ${settings.archiveMinAgeDays} days before an " +
                            "export may remove them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { editPolicy = true }) { Text("Change") }
                }
            }
            if (editPolicy) {
                item(key = "policy-dialog") {
                    PolicyDialog(settings, onDismiss = { editPolicy = false }) { r, a ->
                        editPolicy = false
                        vm.saveSettings(r, a)
                    }
                }
            }
        }
    }
}

@Composable
fun ExportWarning(storage: StorageInfo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${formatBytes(storage.unexportedBytes)} backed up since the last export", style = MaterialTheme.typography.titleSmall)
            Text("Download an export and keep it on Google Drive and a laptop, so the server can be cleared.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PolicyDialog(settings: ServerSettings, onDismiss: () -> Unit, onSave: (Int?, Int?) -> Unit) {
    var retention by remember { mutableStateOf(settings.retentionDays.toString()) }
    var archive by remember { mutableStateOf(settings.archiveMinAgeDays.toString()) }
    val r = retention.toIntOrNull()
    val a = archive.toIntOrNull()
    val valid = r != null && r in 7..365 && a != null && a in 30..3650
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retention policy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    retention, { retention = it.filter(Char::isDigit).take(3) }, label = { Text("Phone keeps originals (days, 7–365)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                OutlinedTextField(
                    archive, { archive = it.filter(Char::isDigit).take(4) }, label = { Text("Server keeps files (days, 30–3650)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                Secondary("Shorter phone retention frees phone storage sooner; originals are only removed once they are in a verified export.")
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(r.takeIf { it != settings.retentionDays }, a.takeIf { it != settings.archiveMinAgeDays })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Employees ---------------------------------------------------------------------------------

class EmployeesViewModel(private val admin: AdminRepository) : LoadingViewModel<List<AdminUser>>() {
    override suspend fun fetch() = admin.users()

    fun create(code: String, name: String, role: String, password: String) =
        act("Added ${code.uppercase()}. They must change the password at first sign-in.") { admin.createUser(code, name, role, password) }

    fun save(user: AdminUser, name: String, role: String, track: Boolean, from: String) =
        act("Saved ${user.employeeCode}.") {
            admin.updateUser(
                user.id, displayName = name.takeIf { it != user.displayName }, role = role.takeIf { it != user.role },
                trackAttendance = track.takeIf { it != user.trackAttendance }, attendanceFrom = from.takeIf { it != user.attendanceFrom && it.isNotEmpty() },
            )
        }

    fun setActive(user: AdminUser, active: Boolean) =
        act(if (active) "${user.employeeCode} can sign in again." else "${user.employeeCode} is disabled and signed out everywhere.") {
            admin.updateUser(user.id, active = active)
        }

    fun resetPassword(user: AdminUser, password: String) =
        act("New temporary password set for ${user.employeeCode}; they were signed out everywhere.") { admin.updateUser(user.id, password = password) }
}

/** Easy to read out over the phone; the employee must replace it at first sign-in. */
/** The server's minimum for new passwords (existing shorter ones keep working until changed). */
const val MIN_PASSWORD = 10

fun temporaryPassword(): String {
    val letters = "abcdefghjkmnpqrstuvwxyz"
    val rnd = SecureRandom()
    return (1..5).map { letters[rnd.nextInt(letters.length)] }.joinToString("") + "-" + (1000 + rnd.nextInt(9000)) // 10 chars
}

private enum class EmployeeDialog { NONE, ADD, EDIT, RESET, DISABLE }

@Composable
fun EmployeesScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { EmployeesViewModel(it.container.adminRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(EmployeeDialog.NONE) }
    var selected by remember { mutableStateOf<AdminUser?>(null) }

    AdminScaffold(
        "Employees", onBack, onRefresh = { vm.refresh() },
        floating = {
            ExtendedFloatingActionButton(onClick = { dialog = EmployeeDialog.ADD }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add employee") })
        },
    ) {
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { users ->
            items(users, key = { it.id }) { u ->
                Card(
                    onClick = { selected = u; dialog = EmployeeDialog.EDIT },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(u.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (!u.active) Tag("Disabled", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                            if (u.role == "ADMIN") Tag("Admin", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Secondary(u.employeeCode + " · last seen " + relativeTime(u.lastSeenAt) + if (u.mustChangePassword) " · must change password" else "")
                        Secondary(if (u.trackAttendance) "Attendance tracked from ${u.attendanceFrom}" else "Attendance not tracked")
                    }
                }
            }
        }
    }

    val user = selected
    when (dialog) {
        EmployeeDialog.NONE -> Unit
        EmployeeDialog.ADD -> AddEmployeeDialog(onDismiss = { dialog = EmployeeDialog.NONE }) { code, name, role, password ->
            dialog = EmployeeDialog.NONE
            vm.create(code, name, role, password)
        }
        EmployeeDialog.EDIT -> if (user != null) EditEmployeeDialog(
            user,
            onDismiss = { dialog = EmployeeDialog.NONE },
            onSave = { name, role, track, from -> dialog = EmployeeDialog.NONE; vm.save(user, name, role, track, from) },
            onReset = { dialog = EmployeeDialog.RESET },
            onToggleActive = { if (user.active) dialog = EmployeeDialog.DISABLE else { dialog = EmployeeDialog.NONE; vm.setActive(user, true) } },
        )
        EmployeeDialog.RESET -> if (user != null) ResetPasswordDialog(user, onDismiss = { dialog = EmployeeDialog.NONE }) { password ->
            dialog = EmployeeDialog.NONE
            vm.resetPassword(user, password)
        }
        EmployeeDialog.DISABLE -> if (user != null) AlertDialog(
            onDismissRequest = { dialog = EmployeeDialog.NONE },
            title = { Text("Disable ${user.displayName}?") },
            text = {
                Text(
                    "They are signed out on every phone at its next contact with the server and can't sign in again. " +
                        "Inspections they made stay, and anything still on their phone uploads if you reactivate them.",
                )
            },
            confirmButton = { TextButton(onClick = { dialog = EmployeeDialog.NONE; vm.setActive(user, false) }) { Text("Disable") } },
            dismissButton = { TextButton(onClick = { dialog = EmployeeDialog.NONE }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RolePicker(role: String, onRole: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = role == "INSPECTOR", onClick = { onRole("INSPECTOR") }, label = { Text("Inspector") })
        FilterChip(selected = role == "ADMIN", onClick = { onRole("ADMIN") }, label = { Text("Administrator") })
    }
}

@Composable
private fun AddEmployeeDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("INSPECTOR") }
    var password by remember { mutableStateOf(temporaryPassword()) }
    val valid = code.isNotBlank() && name.isNotBlank() && password.length >= MIN_PASSWORD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add employee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.uppercase().filter { c -> !c.isWhitespace() }.take(40) }, label = { Text("Employee code") }, singleLine = true)
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true)
                RolePicker(role) { role = it }
                OutlinedTextField(password, { password = it.take(200) }, label = { Text("Temporary password") }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                Secondary("Tell them this password in person or by phone. They must choose their own at first sign-in.")
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(code, name, role, password) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditEmployeeDialog(
    user: AdminUser,
    onDismiss: () -> Unit,
    onSave: (name: String, role: String, track: Boolean, from: String) -> Unit,
    onReset: () -> Unit,
    onToggleActive: () -> Unit,
) {
    var name by remember { mutableStateOf(user.displayName) }
    var role by remember { mutableStateOf(user.role) }
    var track by remember { mutableStateOf(user.trackAttendance) }
    var from by remember { mutableStateOf(user.attendanceFrom) }
    var pickFrom by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(user.employeeCode) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true)
                RolePicker(role) { role = it }
                // Attendance calendar: who counts as missing a check-in, and since when (their joining day).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Track attendance", style = MaterialTheme.typography.bodyLarge)
                        Secondary(if (track) "Missing check-ins show on the calendar" else "Never marked missed (e.g. an office admin)")
                    }
                    Switch(checked = track, onCheckedChange = { track = it })
                }
                if (track) OutlinedButton(onClick = { pickFrom = true }) { Text(if (from.isEmpty()) "Tracked from: pick a day" else "Tracked from $from") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReset) { Text("Reset password") }
                    OutlinedButton(onClick = onToggleActive) { Text(if (user.active) "Disable" else "Reactivate") }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, role, track, from) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (pickFrom) {
        val initial = runCatching { java.time.LocalDate.parse(from) }.getOrDefault(java.time.LocalDate.now())
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { from = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
                    pickFrom = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickFrom = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun ResetPasswordDialog(user: AdminUser, onDismiss: () -> Unit, onReset: (String) -> Unit) {
    var password by remember { mutableStateOf(temporaryPassword()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset ${user.displayName}'s password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it.take(200) }, label = { Text("Temporary password") }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                Secondary(
                    "They are signed out on every phone and must choose a new password at the next sign-in, which needs the " +
                        "internet. Work on their phone is kept and uploads after they sign in.",
                )
            }
        },
        confirmButton = { TextButton(enabled = password.length >= MIN_PASSWORD, onClick = { onReset(password) }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Inspections' backup status ---------------------------------------------------------------

class AdminInspectionsViewModel(private val admin: AdminRepository) : LoadingViewModel<List<AdminInspection>>() {
    val query = MutableStateFlow("")
    override suspend fun fetch() = admin.inspections(query.value)
}

@Composable
fun AdminInspectionsScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { AdminInspectionsViewModel(it.container.adminRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    AdminScaffold("Inspections", onBack, onRefresh = { vm.refresh() }) {
        item(key = "search") {
            OutlinedTextField(
                query, { vm.query.value = it }, placeholder = { Text("Search client or address, then refresh") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        loadItems(state, retry = { vm.refresh() }) { list ->
            val missing = list.sumOf { it.photos - it.photosVerified }
            item(key = "summary") {
                InfoCard {
                    Text("${list.size} inspections on the server", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (missing == 0) "Every photo the server knows about is backed up."
                        else "$missing photo${if (missing == 1) " is" else "s are"} recorded but not backed up yet (still uploading from a phone).",
                        color = if (missing == 0) MaterialTheme.colorScheme.onSurface else HomeInspectionWarn,
                    )
                }
            }
            items(list, key = { it.id }) { i -> AdminInspectionCard(i) }
        }
    }
}

@Composable
private fun AdminInspectionCard(i: AdminInspection) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i.clientName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(i.status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
        }
        Secondary("${i.propertyAddress} · ${i.inspectionDate} · ${i.inspector.ifBlank { "unknown inspector" }}")
        val photosOk = i.photosVerified == i.photos
        Text(
            "Photos: ${i.photosVerified}/${i.photos} backed up · ${i.photosExported} exported" +
                if (i.photosArchived > 0) " · ${i.photosArchived} archived" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (photosOk) MaterialTheme.colorScheme.onSurface else HomeInspectionWarn,
        )
        if (i.reports > 0) Text("Reports: ${i.reportsVerified}/${i.reports} backed up", style = MaterialTheme.typography.bodyMedium)
        Secondary("Last change reached the server ${relativeTime(i.serverUpdatedAt)}")
    }
}

// ---- Phones and failed sync items -------------------------------------------------------------

class DevicesViewModel(private val admin: AdminRepository) : LoadingViewModel<List<AdminDevice>>() {
    override suspend fun fetch() = admin.devices()

    fun setBlocked(d: AdminDevice, blocked: Boolean) =
        act(if (blocked) "${d.label} is blocked and signed out." else "${d.label} can sign in again.") { admin.setDeviceBlocked(d.id, blocked) }
}

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { DevicesViewModel(it.container.adminRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<AdminDevice?>(null) }
    AdminScaffold("Phones & sync problems", onBack, onRefresh = { vm.refresh() }) {
        item(key = "message") { MessageLine(message) }
        item(key = "note") { Secondary("Each phone reports after every sync. A phone that is offline shows its last report.") }
        loadItems(state, retry = { vm.refresh() }) { list ->
            items(list, key = { it.id }) { d -> DeviceCard(d, onBlock = { confirm = d }) }
        }
    }
    confirm?.let { d ->
        val blocking = d.revokedAt == null
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (blocking) "Block ${d.label}?" else "Unblock ${d.label}?") },
            text = {
                Text(
                    if (blocking) "The phone is signed out at its next contact and can't sign in again until unblocked. " +
                        "Unsynced work stays on it and uploads after it is unblocked. To stop a person (who could sign in " +
                        "on another phone), disable their account under Employees instead."
                    else "${d.user} can sign in on this phone again.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; vm.setBlocked(d, blocking) }) { Text(if (blocking) "Block" else "Unblock") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceCard(d: AdminDevice, onBlock: () -> Unit) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.label.ifBlank { "Phone" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (d.revokedAt != null) Tag("Blocked", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        Secondary("${d.user} (${d.employeeCode}) · app ${d.appVersion.ifBlank { "?" }} · last seen ${relativeTime(d.lastSeenAt)}")
        val r = d.syncReport
        if (r == null) {
            Secondary("No sync report yet (needs app 0.5 or later).")
        } else {
            val waiting = r.pendingRecords + r.pendingPhotos
            Text(
                if (waiting == 0) "Nothing waiting to upload" else "Waiting: ${r.pendingRecords} changes · ${r.pendingPhotos} photos",
                style = MaterialTheme.typography.bodyMedium,
                color = if (waiting == 0) MaterialTheme.colorScheme.onSurface else HomeInspectionWarn,
            )
            Secondary(
                "Phone: ${formatBytes(r.freeBytes)} free · ${r.localOriginals} originals (${formatBytes(r.localOriginalsBytes)})" +
                    (if (r.cleanupBlocked > 0) " · ${r.cleanupBlocked} kept by cleanup rules" else "") +
                    " · reported ${relativeTime(d.syncReportedAt)}",
            )
            r.problems.orEmpty().take(10).forEach { p ->
                Text(
                    "• ${p.type.lowercase()} ${p.op.lowercase().replace('_', ' ')}: ${p.message ?: p.code ?: "failed"} (tried ${p.attempts}×)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = onBlock) { Text(if (d.revokedAt == null) "Block this phone" else "Unblock") }
    }
}

// ---- Audit log --------------------------------------------------------------------------------

/** Filter chips: label → event-type prefixes (client-side over the loaded pages). */
private val AuditFilters = listOf(
    "All" to emptyList(),
    "Accounts" to listOf("USER_", "PASSWORD_", "DEVICE_", "SETTINGS_"),
    "Sign-ins" to listOf("LOGIN"),
    "Backups" to listOf("PHOTO_BACKED_UP", "REPORT_BACKED_UP", "UPLOAD_", "BACKUP_"),
    "Exports" to listOf("EXPORT_"),
    "Cleanup" to listOf("PHOTO_ORIGINAL_DELETED", "RETENTION_"),
    "Reports" to listOf("REPORT_FINALIZED", "REPORT_SHARED"),
)

class AuditViewModel(private val admin: AdminRepository) : LoadingViewModel<List<AuditEntry>>() {
    private var pages = emptyList<AuditEntry>()
    private var keepPages = false
    val loadingMore = MutableStateFlow(false)

    override suspend fun fetch(): List<AuditEntry> {
        if (keepPages) { // after "load older": show the accumulated pages, don't refetch page one
            keepPages = false
            return pages
        }
        return admin.audit().also { pages = it }
    }

    fun loadMore() = viewModelScope.launch {
        val last = pages.lastOrNull()?.id ?: return@launch
        loadingMore.value = true
        try {
            pages = pages + admin.audit(before = last)
            keepPages = true
            refresh()
        } catch (e: Exception) {
            message.value = friendlyError(e)
        } finally {
            loadingMore.value = false
        }
    }
}

@Composable
fun AuditScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { AuditViewModel(it.container.adminRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(AuditFilters.first()) }
    AdminScaffold("Audit log", onBack, onRefresh = { vm.refresh() }) {
        item(key = "filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AuditFilters) { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.first) }) }
            }
        }
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { list ->
            val shown = if (filter.second.isEmpty()) list else list.filter { e -> filter.second.any { e.type.startsWith(it) } }
            if (shown.isEmpty()) item(key = "empty") { Secondary("No events of this kind in the loaded range.") }
            items(shown, key = { it.id }) { e -> AuditRow(e) }
            if (list.size >= 100) {
                item(key = "more") {
                    OutlinedButton(onClick = { vm.loadMore() }, enabled = !loadingMore, modifier = Modifier.fillMaxWidth()) {
                        Text(if (loadingMore) "Loading…" else "Load older events")
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(e: AuditEntry) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Secondary(relativeTime(e.createdAt))
        }
        val who = listOf(e.user.takeIf { it.isNotBlank() }?.let { "$it (${e.employeeCode})" }, e.device.takeIf { it.isNotBlank() })
            .filterNotNull().joinToString(" · ")
        if (who.isNotEmpty()) Secondary(who + if (e.fromDevice) " · recorded on the phone" else "")
        if (e.entity.isNotBlank()) Secondary(e.entity)
        if (e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodySmall)
    }
}
