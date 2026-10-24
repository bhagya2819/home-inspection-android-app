package com.griha.inspection.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.griha.inspection.GrihaApp
import com.griha.inspection.core.admin.DownloadProgress
import com.griha.inspection.core.admin.ExportInfo
import com.griha.inspection.core.admin.StorageInfo
import com.griha.inspection.core.admin.VerifyResult
import com.griha.inspection.ui.grihaViewModel
import com.griha.inspection.ui.theme.GrihaWarn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VerifyState(val exportId: Long, val done: Long, val total: Long)

class StorageViewModel(private val app: GrihaApp) : LoadingViewModel<StorageInfo>() {
    private val admin = app.container.adminRepository
    private val downloads = app.container.exportDownloads
    val progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val verifying = MutableStateFlow<VerifyState?>(null)

    override suspend fun fetch() = admin.storage()

    init {
        // DownloadManager runs in the system; poll its progress while this screen is open.
        viewModelScope.launch {
            while (isActive) {
                (state.value as? Load.Ready)?.value?.exports?.let { list ->
                    progress.value = list.filter { it.status == "CREATED" || it.verifiedAt != null }.associate { it.id to downloads.progress(it.id) }
                }
                delay(1_000)
            }
        }
    }

    fun location(e: ExportInfo) = downloads.location(e)

    fun prepare() = actFor {
        val (export, problems) = admin.createExport()
        buildString {
            append("Export ${export.label} is ready: ${export.entryCount - 2} files, ${formatBytes(export.zipBytes)}.")
            if (problems > 0) append(" $problems file(s) on the server failed their checksum and were left out — see the audit log.")
        }
    }

    fun download(e: ExportInfo) = act("Downloading to ${downloads.location(e)}. You can leave this screen.") { downloads.start(e) }

    /** Runs in the app scope: leaving the screen doesn't stop a verification of a 1.5 GB file. */
    fun verify(e: ExportInfo) {
        if (verifying.value != null) return
        verifying.value = VerifyState(e.id, 0, e.zipBytes)
        app.appScope.launch {
            val result = try {
                downloads.verify(e) { done, total -> verifying.value = VerifyState(e.id, done, total) }
            } catch (ex: Exception) {
                VerifyResult.Failed(friendlyError(ex))
            }
            verifying.value = null
            message.value = when (result) {
                is VerifyResult.Verified -> "Verified: all ${result.export.entryCount} files match. Copy the zip to Google Drive and a laptop, then you can remove it from the server."
                is VerifyResult.Failed -> result.message
            }
            refresh()
        }
    }

    /** Per export: the last "Remove from server" answer, shown inside its card (where the button was tapped). */
    val archiveResults = MutableStateFlow<Map<Long, String>>(emptyMap())
    val archiving = MutableStateFlow<Long?>(null)

    fun archive(e: ExportInfo) = viewModelScope.launch {
        archiving.value = e.id
        val text = try {
            val r = admin.archive(e.id)
            buildString {
                append(
                    if (r.archivedNow == 0) "Nothing was removed from the server."
                    else "Removed ${r.archivedNow} file(s) from the server (${formatBytes(r.freedBytes)} freed).",
                )
                if (r.keptTooRecent > 0) {
                    append(" ${r.keptTooRecent} file(s) kept because their inspection was finalized less than ${r.minAgeDays} days ago")
                    append(" — the server keeps recent work in case a report is edited. Try again after that.")
                }
                if (r.keptNotFinalized > 0) append(" ${r.keptNotFinalized} kept: their inspection isn't finalized.")
                if (r.keptChanged > 0) append(" ${r.keptChanged} kept: changed since the export.")
            }
        } catch (ex: Exception) {
            "Couldn't remove: ${friendlyError(ex)}"
        }
        archiveResults.value = archiveResults.value + (e.id to text)
        archiving.value = null
        refresh()
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit) {
    val vm = grihaViewModel { StorageViewModel(it) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val verifying by vm.verifying.collectAsStateWithLifecycle()
    val archiveResults by vm.archiveResults.collectAsStateWithLifecycle()
    val archiving by vm.archiving.collectAsStateWithLifecycle()
    var confirmArchive by remember { mutableStateOf<ExportInfo?>(null) }
    val minAgeDays = (state as? Load.Ready)?.value?.archiveMinAgeDays ?: 60

    AdminScaffold("Server storage & exports", onBack, onRefresh = { vm.refresh() }) {
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { s ->
            item(key = "disk") { DiskCard(s, onPrepare = { vm.prepare() }, canPrepare = s.exports.none { it.status == "CREATED" }) }
            items(s.exports, key = { it.id }) { e ->
                ExportCard(
                    e,
                    progress = progress[e.id],
                    verifying = verifying?.takeIf { it.exportId == e.id },
                    location = vm.location(e),
                    onDownload = { vm.download(e) },
                    onVerify = { vm.verify(e) },
                    onArchive = { confirmArchive = e },
                    archiving = archiving == e.id,
                    archiveResult = archiveResults[e.id],
                )
            }
            item(key = "how") {
                InfoCard {
                    Text("How it works", style = MaterialTheme.typography.titleSmall)
                    Secondary(
                        "1. Prepare an export: the server bundles one month of backed-up photos and reports (about 1.5 GB at most) " +
                            "with manifest.csv (a SHA-256 for every file) and data.json (all inspection details, readable without the app).\n" +
                            "2. Download it, then Verify: this phone re-checks every file against the manifest.\n" +
                            "3. Copy the zip to Google Drive and a laptop — two copies.\n" +
                            "4. Remove from server: files of inspections finalized at least ${s.archiveMinAgeDays} days ago are deleted " +
                            "from the server. Records stay, and those photos show as archived. Phones remove their own copies only " +
                            "once a photo is in a verified export.",
                    )
                }
            }
        }
    }

    confirmArchive?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmArchive = null },
            title = { Text("Remove export ${e.label} from the server?") },
            text = {
                Text(
                    "Only files of inspections finalized at least $minAgeDays days ago are deleted from the server; newer ones " +
                        "are kept for now (you can run this again later). " +
                        "After that, your copies of ${e.fileName} on Google Drive and the laptop are the only full copies " +
                        "(phones may still hold some originals for a while). This can't be undone on the server.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmArchive = null; vm.archive(e) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmArchive = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DiskCard(s: StorageInfo, canPrepare: Boolean, onPrepare: () -> Unit) {
    InfoCard {
        Text("Server disk", style = MaterialTheme.typography.titleSmall)
        Text("${formatBytes(s.dataFreeBytes)} free of ${formatBytes(s.dataTotalBytes)} (shared with other apps on the server)")
        Secondary("Griha files on the server: ${formatBytes(s.objectsBytes)} · removed after export so far: ${formatBytes(s.archivedBytes)}")
        Text(
            "Backed up since the last export: ${formatBytes(s.unexportedBytes)} (${s.unexportedCount} files)",
            color = if (s.needsExport) GrihaWarn else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Secondary("Last verified export: ${dateOf(s.lastExportAt)}. Export at least every ${formatBytes(s.exportWarnBytes)}.")
        if (s.unexportedCount > 0) {
            Button(onClick = onPrepare, enabled = canPrepare) { Text("Prepare next export") }
            if (!canPrepare) Secondary("Finish verifying the open export first.")
        }
    }
}

@Composable
private fun ExportCard(
    e: ExportInfo,
    progress: DownloadProgress?,
    verifying: VerifyState?,
    location: String,
    onDownload: () -> Unit,
    onVerify: () -> Unit,
    onArchive: () -> Unit,
    archiving: Boolean,
    archiveResult: String?,
) {
    var copied by rememberSaveable(e.id) { mutableStateOf(false) }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Export ${e.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(
                when (e.status) {
                    "CREATED" -> "Not verified"
                    "VERIFIED" -> "Verified"
                    else -> "Removed from server"
                },
            )
        }
        Secondary("${e.entryCount - 2} files · ${formatBytes(e.zipBytes)} · prepared ${dateOf(e.createdAt)}")
        when {
            verifying != null -> {
                Text("Checking every file…")
                LinearProgressIndicator(progress = { if (verifying.total > 0) verifying.done.toFloat() / verifying.total else 0f }, modifier = Modifier.fillMaxWidth())
            }
            e.status == "CREATED" -> when (progress?.state ?: DownloadProgress.State.NONE) {
                DownloadProgress.State.NONE -> Button(onClick = onDownload) { Text("Download (${formatBytes(e.zipBytes)})") }
                DownloadProgress.State.RUNNING, DownloadProgress.State.PAUSED -> {
                    val p = progress!!
                    Text(if (p.state == DownloadProgress.State.PAUSED) "Paused — waiting for a connection" else "Downloading ${formatBytes(p.downloaded)} of ${formatBytes(e.zipBytes)}")
                    LinearProgressIndicator(progress = { if (e.zipBytes > 0) p.downloaded.toFloat() / e.zipBytes else 0f }, modifier = Modifier.fillMaxWidth())
                }
                DownloadProgress.State.DONE -> {
                    Secondary("Downloaded to $location")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onVerify) { Text("Verify") }
                        OutlinedButton(onClick = onDownload) { Text("Download again") }
                    }
                }
                DownloadProgress.State.FAILED -> {
                    Text("Download failed (reason ${progress?.reason}).", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDownload) { Text("Download again") }
                }
            }
            e.status == "VERIFIED" -> {
                Text("Verified ${dateOf(e.verifiedAt)}. Saved at $location", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = copied, onCheckedChange = { copied = it })
                    Text("I have copied this zip to Google Drive and to a laptop", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onArchive, enabled = copied && !archiving) { Text(if (archiving) "Removing…" else "Remove from server") }
                ArchiveResultLine(archiveResult)
            }
            else -> {
                Text(
                    "${e.archivedObjects} file(s) removed from the server on ${dateOf(e.archivedAt)}. Keep both copies of ${e.fileName}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (e.archivedObjects < e.entryCount - 2) {
                    Secondary("Some files were kept because their inspection was too recent or not finalized.")
                    OutlinedButton(onClick = onArchive, enabled = !archiving) { Text(if (archiving) "Removing…" else "Remove newly eligible files") }
                }
                ArchiveResultLine(archiveResult)
            }
        }
    }
}

@Composable
private fun ArchiveResultLine(text: String?) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (text.startsWith("Removed")) MaterialTheme.colorScheme.primary else GrihaWarn,
    )
}
