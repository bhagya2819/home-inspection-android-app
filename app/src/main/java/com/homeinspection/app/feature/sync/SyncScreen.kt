package com.homeinspection.app.feature.sync

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.HomeInspectionApp
import com.homeinspection.app.core.auth.AuthState
import com.homeinspection.app.core.database.OutboxCount
import com.homeinspection.app.core.database.SyncOutboxEntity
import com.homeinspection.app.core.model.SyncEntity
import com.homeinspection.app.core.model.SyncOperation
import com.homeinspection.app.core.sync.SyncOutcome
import com.homeinspection.app.core.sync.SyncStatus
import com.homeinspection.app.ui.homeInspectionViewModel
import com.homeinspection.app.ui.theme.HomeInspectionWarn
import com.homeinspection.app.core.retention.RetentionOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the dashboard line and the sync screen show. Never "all backed up" while anything is queued. */
data class SyncSummary(
    val status: SyncStatus,
    val records: Int,
    val photos: Int,
    val conflicts: Int,
    val failures: Int,
    val signedInCanSync: Boolean,
) {
    val pending get() = records + photos

    val headline: String
        get() = when {
            !signedInCanSync -> "Sign in online to back up"
            status.running -> "Syncing…"
            conflicts > 0 -> "$conflicts change${if (conflicts == 1) "" else "s"} need your decision"
            status.lastOutcome == SyncOutcome.OFFLINE && pending > 0 -> "Offline · $pending waiting to upload"
            pending > 0 -> "$pending waiting to upload" + if (photos > 0) " ($photos photos)" else ""
            status.lastSyncAt == 0L -> "Not synced yet"
            else -> "All backed up · " + DateUtils.getRelativeTimeSpanString(status.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }

    val healthy get() = signedInCanSync && conflicts == 0 && failures == 0 && pending == 0 && status.lastOutcome != SyncOutcome.FAILED
}

fun syncSummaryFlow(app: HomeInspectionApp) = combine(
    app.container.syncEngine.status,
    app.container.database.outboxDao().observeCounts(),
    app.container.database.outboxDao().observeProblems(),
    app.container.authRepository.state,
) { status, counts, problems, auth ->
    fun count(pred: (OutboxCount) -> Boolean) = counts.filter(pred).sumOf { it.count }
    SyncSummary(
        status = status,
        records = count { it.op == SyncOperation.UPSERT },
        photos = count { it.op == SyncOperation.UPLOAD_ORIGINAL },
        conflicts = problems.count { it.lastErrorCode == "CONFLICT" },
        failures = problems.count { it.lastErrorCode != "CONFLICT" },
        signedInCanSync = (auth as? AuthState.SignedIn)?.canSync == true,
    )
}

class SyncViewModel(private val app: HomeInspectionApp) : ViewModel() {
    private val c = app.container
    val summary = syncSummaryFlow(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val problems = c.database.outboxDao().observeProblems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val auth = c.authRepository.state

    val retentionSettings = c.retention.settings
    val cleanupEnabled = c.retention.enabled
    val cleanupResult = c.retention.lastResult
    val storage = MutableStateFlow<RetentionOverview?>(null)
    val freeBytes = MutableStateFlow(0L)
    val cleaning = MutableStateFlow(false)

    init {
        // Re-evaluate when a sync finishes (new backup or export confirmations change eligibility).
        viewModelScope.launch { c.syncEngine.status.collect { if (!it.running) refreshStorage() } }
    }

    fun refreshStorage() = viewModelScope.launch {
        storage.value = c.retention.overview()
        freeBytes.value = withContext(Dispatchers.IO) { c.photoRepository.freeBytes() }
    }

    fun setCleanup(on: Boolean) = app.appScope.launch { c.retention.setEnabled(on); refreshStorage() }

    /** App scope: a cleanup in progress finishes even if the screen closes. */
    fun cleanNow() = app.appScope.launch {
        cleaning.value = true
        try {
            c.retention.cleanNow()
        } finally {
            cleaning.value = false
            refreshStorage()
        }
    }

    fun syncNow() = app.appScope.launch {
        c.database.outboxDao().retryAllNow(System.currentTimeMillis())
        c.syncScheduler.kick()
    }

    fun keepMine(row: SyncOutboxEntity) = app.appScope.launch { c.syncEngine.keepMine(row); c.syncScheduler.kick() }
    fun useServer(row: SyncOutboxEntity) = app.appScope.launch { c.syncEngine.useServer(row) }
    fun signOut() = app.appScope.launch { c.authRepository.logout() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { SyncViewModel(it) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val problems by vm.problems.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            summary?.let { s ->
                item(key = "status") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SyncLine(s)
                            Text("Records waiting: ${s.records} · Photos waiting: ${s.photos}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (s.status.lastSyncAt == 0L) "Never synced with the server"
                                else "Last successful sync: " + DateUtils.formatDateTime(
                                    context, s.status.lastSyncAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            s.status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (s.signedInCanSync) {
                                Button(onClick = { vm.syncNow() }, enabled = !s.status.running, modifier = Modifier.padding(top = 4.dp)) {
                                    Text("Sync now")
                                }
                            } else {
                                Text(
                                    "You signed in without internet. Uploads start by themselves once the phone is online; " +
                                        "if they don't, sign in again while online.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { vm.syncNow() }, enabled = !s.status.running) { Text("Try now") }
                                    OutlinedButton(onClick = { vm.signOut() }) { Text("Sign in again") }
                                }
                            }
                        }
                    }
                }
            }
            if (problems.isNotEmpty()) {
                item(key = "problems-title") { Text("Needs attention", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(problems, key = { it.id }) { row -> ProblemCard(row, onKeepMine = { vm.keepMine(row) }, onUseServer = { vm.useServer(row) }) }
            }
        }
    }
}

@Composable
private fun ProblemCard(row: SyncOutboxEntity, onKeepMine: () -> Unit, onUseServer: () -> Unit) {
    val what = when (row.entityType) {
        SyncEntity.INSPECTION -> "Property details"
        SyncEntity.ROOM -> "A room"
        SyncEntity.FINDING -> "A finding"
        SyncEntity.PHOTO -> if (row.operation == SyncOperation.UPLOAD_ORIGINAL) "A photo upload" else "A photo's details"
        SyncEntity.REPORT -> "A report"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.lastErrorCode == "CONFLICT") {
                Text("$what was also changed on another phone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Keep your version to overwrite theirs, or use theirs to discard your change on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onKeepMine) { Text("Keep mine") }
                    TextButton(onClick = onUseServer) { Text("Use theirs") }
                }
            } else {
                Text("$what couldn't upload", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${row.lastErrorMessage ?: row.lastErrorCode} · tried ${row.attemptCount}×, will retry automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One-line status with an icon; used on the dashboard (tap → sync screen) and here. */
@Composable
fun SyncLine(s: SyncSummary, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            s.status.running -> 0
            s.healthy -> 1
            s.conflicts > 0 || s.failures > 0 || !s.signedInCanSync -> 2
            else -> 3
        }
        // Spinner → tick (or warning) fades over instead of popping.
        Crossfade(icon, Modifier.size(18.dp), animationSpec = tween(250), label = "syncIcon") { i ->
            Box(contentAlignment = Alignment.Center) {
                when (i) {
                    0 -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    1 -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    2 -> Icon(Icons.Filled.Warning, null, tint = HomeInspectionWarn, modifier = Modifier.size(18.dp))
                    else -> Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(s.headline, style = MaterialTheme.typography.bodyMedium)
    }
}
