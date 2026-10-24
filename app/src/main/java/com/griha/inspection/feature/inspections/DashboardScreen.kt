package com.griha.inspection.feature.inspections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.griha.inspection.GrihaApp
import com.griha.inspection.core.auth.AuthState
import com.griha.inspection.core.data.InspectionRepository
import com.griha.inspection.core.data.PhotoRepository
import com.griha.inspection.core.database.InspectionSummary
import com.griha.inspection.core.admin.StorageInfo
import com.griha.inspection.core.model.InspectionStatus
import com.griha.inspection.feature.admin.ExportWarning
import com.griha.inspection.feature.attendance.FaceMode
import com.griha.inspection.feature.sync.SyncLine
import com.griha.inspection.feature.sync.SyncSummary
import com.griha.inspection.feature.sync.syncSummaryFlow
import com.griha.inspection.ui.components.InitialsAvatar
import com.griha.inspection.ui.components.MainBottomBar
import com.griha.inspection.ui.components.MainTab
import com.griha.inspection.ui.grihaViewModel
import com.griha.inspection.ui.theme.GrihaTints
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalTime
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.griha.inspection.ui.components.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** "N photos in <room> were interrupted before they saved" — one per affected room. */
data class InterruptedNotice(val inspectionId: String, val roomId: String, val roomName: String, val count: Int)

/** Dashboard filters; null status = all. */
private val StatusFilters = listOf(
    null, InspectionStatus.DRAFT, InspectionStatus.IN_PROGRESS, InspectionStatus.READY_FOR_REPORT, InspectionStatus.FINALIZED,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DashboardViewModel(
    private val repo: InspectionRepository,
    private val photos: PhotoRepository,
    syncSummary: Flow<SyncSummary>,
    /** Pull-to-refresh: retry everything waiting and sync now (same as Backup › Sync now). */
    val syncNow: () -> Unit,
    /** Admins only: set when ≥ 5 GB were backed up since the last verified export. */
    val storageWarning: StateFlow<StorageInfo?>,
) : ViewModel() {
    val sync = syncSummary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val query = MutableStateFlow("")
    val status = MutableStateFlow<InspectionStatus?>(null)

    val inspections = combine(query.debounce(150), status) { q, s -> q to s }
        .flatMapLatest { (q, s) -> repo.observeSummaries(q, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Latest unfinished inspection (list is ordered by updated_at DESC), for "Continue where you left off". */
    private val all = repo.observeSummaries("", null).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Inspections per status, for the counts on the filter chips. */
    val counts = all.map { list -> list.groupingBy { it.inspection.status }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val resume = all
        .map { list -> list.firstOrNull { it.inspection.status != InspectionStatus.FINALIZED && it.inspection.status != InspectionStatus.ARCHIVED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val interrupted = photos.interruptedCaptures
        .map { captures ->
            val rooms = repo.roomsById(captures.map { it.roomId }.distinct())
            captures.groupBy { it.roomId }.map { (roomId, items) ->
                InterruptedNotice(items.first().inspectionId, roomId, rooms[roomId]?.name ?: "a room", items.size)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismissInterrupted() = photos.dismissInterrupted()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNewInspection: () -> Unit,
    onOpenInspection: (String) -> Unit,
    onOpenRoom: (inspectionId: String, roomId: String) -> Unit,
    onOpenSync: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenStorage: () -> Unit,
    onFace: (FaceMode) -> Unit,
    onOpenLeave: () -> Unit,
) {
    val vm = grihaViewModel {
        val c = it.container
        DashboardViewModel(
            c.inspectionRepository, c.photoRepository, syncSummaryFlow(it),
            syncNow = {
                it.appScope.launch {
                    c.database.outboxDao().retryAllNow(System.currentTimeMillis())
                    c.syncScheduler.kick()
                }
            },
            storageWarning = c.adminRepository.storageWarning,
        )
    }
    val auth by (LocalContext.current.applicationContext as GrihaApp).container.authRepository.state.collectAsStateWithLifecycle()
    // Leave the admin decided since the engineer last opened Leave (checked after each sync).
    val leaveDecisions by (LocalContext.current.applicationContext as GrihaApp).container.leaveRepository.newDecisions.collectAsStateWithLifecycle()
    val userName = (auth as? AuthState.SignedIn)?.user?.displayName.orEmpty()
    val storageWarning by vm.storageWarning.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val inspections by vm.inspections.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    val interrupted by vm.interrupted.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val filtering = query.isNotBlank() || status != null
    val focus = LocalFocusManager.current

    // Pull down to sync. The spinner stays at least a moment, then until the sync run has finished.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(pulled) {
        if (!pulled) return@LaunchedEffect
        vm.syncNow()
        delay(800)
        withTimeoutOrNull(20_000) { snapshotFlow { vm.sync.value?.status?.running == true }.first { !it } }
        pulled = false
    }

    Scaffold(
        bottomBar = { MainBottomBar(MainTab.Home, onHome = {}, onNew = onNewInspection, onProfile = onOpenProfile) },
    ) { padding ->
      PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = { pulled = true },
        modifier = Modifier.padding(top = padding.calculateTopPadding()),
      ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { Header(userName, sync, onOpenSync, onOpenProfile) }
            item(key = "work") {
                WorkStatus(
                    onCheckIn = { onFace(FaceMode.CHECK_IN) },
                    onCheckOut = { onFace(FaceMode.CHECK_OUT) },
                    onSetUpFace = { onFace(FaceMode.ENROLL) },
                )
            }
            items(interrupted, key = { "interrupted-" + it.roomId }) { notice ->
                InterruptedCard(
                    notice,
                    onRetake = {
                        vm.dismissInterrupted()
                        onOpenRoom(notice.inspectionId, notice.roomId)
                    },
                    onDismiss = vm::dismissInterrupted,
                )
            }
            storageWarning?.let { w -> item(key = "export-warning") { ExportWarning(w, onOpenStorage) } }
            if (leaveDecisions.isNotEmpty()) item(key = "leave-decisions") {
                Card(
                    onClick = onOpenLeave,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Leave", style = MaterialTheme.typography.titleSmall)
                        leaveDecisions.forEach { r ->
                            val word = when (r.status) { "APPROVED" -> "approved"; "REJECTED" -> "rejected"; else -> "taken back" }
                            Text("${r.kindLabel} · ${com.griha.inspection.feature.leave.spanLabel(r.startDay, r.endDay)}: $word", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (!filtering) resume?.let { r -> item(key = "resume") { ResumeCard(r, Modifier.animateItem(), onClick = { onOpenInspection(r.inspection.id) }) } }
            item(key = "search") {
                TextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = { Text("Search client, phone or address") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, "Clear search") }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            item(key = "filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(StatusFilters) { s ->
                        FilterChip(
                            selected = status == s,
                            onClick = { vm.status.value = s },
                            label = {
                                val n = if (s == null) counts.values.sum() else counts[s] ?: 0
                                Text(if (n > 0) "${s?.label ?: "All"} · $n" else s?.label ?: "All")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                                selectedLabelColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    }
                }
            }
            val list = inspections
            when {
                list == null -> Unit // first DB emission is only a few ms away; avoid a flash of empty state
                list.isEmpty() -> item(key = "empty") { EmptyState(filtering, onNewInspection) }
                else -> items(list, key = { it.inspection.id }) { summary ->
                    InspectionCard(summary, Modifier.animateItem(), onClick = { onOpenInspection(summary.inspection.id) })
                }
            }
        }
      }
    }
}

/** Greeting + avatar (→ profile), then the one-line backup status (→ backup screen). */
@Composable
private fun Header(userName: String, sync: SyncSummary?, onOpenSync: () -> Unit, onOpenProfile: () -> Unit) {
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (userName.isBlank()) greeting else "$greeting,",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (userName.isNotBlank()) {
                    Text(userName.substringBefore(' '), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = onOpenProfile, modifier = Modifier.size(48.dp)) { InitialsAvatar(userName, size = 40) }
        }
        sync?.let { SyncLine(it, Modifier.fillMaxWidth().padding(top = 8.dp), onClick = onOpenSync) }
    }
}

/** The most recently touched unfinished inspection: one tap back into the work. */
@Composable
private fun ResumeCard(summary: InspectionSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val i = summary.inspection
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Continue where you left off", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(i.clientName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text(
                listOfNotNull(i.unitNumber, i.propertyAddress).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                StatusPill(i.status)
                Text(
                    "${summary.findingCount} findings · ${summary.photoCount} photos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Button(onClick = onClick) { Text("Continue") }
            }
        }
    }
}

@Composable
fun StatusPill(status: InspectionStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        InspectionStatus.DRAFT, InspectionStatus.ARCHIVED -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        InspectionStatus.IN_PROGRESS -> GrihaTints.warning.let { it.container to it.content }
        InspectionStatus.READY_FOR_REPORT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        InspectionStatus.FINALIZED -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(status.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun InspectionCard(summary: InspectionSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val i = summary.inspection
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    i.clientName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(i.status, Modifier.padding(start = 8.dp))
            }
            Text(
                listOfNotNull(i.unitNumber, i.propertyAddress).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${friendlyDate(i.inspectionDate)} · ${summary.findingCount} findings · ${summary.photoCount} photos",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (summary.pendingPhotoCount > 0) {
                Text(
                    "${summary.pendingPhotoCount} photos not backed up yet",
                    style = MaterialTheme.typography.labelMedium,
                    color = GrihaTints.warning.content,
                )
            }
        }
    }
}

@Composable
private fun InterruptedCard(notice: InterruptedNotice, onRetake: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    if (notice.count == 1) "1 photo was not saved" else "${notice.count} photos were not saved",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "The app closed while saving a photo in ${notice.roomName}. Please retake it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val buttonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onDismiss, colors = buttonColors) { Text("Dismiss") }
                TextButton(onClick = onRetake, colors = buttonColors) {
                    Text("Go to ${notice.roomName}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(filtering: Boolean, onNewInspection: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (filtering) "No matching inspections" else "Start your first inspection", style = MaterialTheme.typography.titleMedium)
            Text(
                if (filtering) "Try a different search or filter."
                else "Everything is saved on this phone, even offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            if (!filtering) Button(onClick = onNewInspection) { Text("New inspection") }
        }
    }
}

/** "Today", "Yesterday", "Tomorrow", else "20 Oct 2026". */
private fun friendlyDate(isoDate: String): String {
    val date = LocalDate.parse(isoDate)
    return when (date.toEpochDay() - LocalDate.now().toEpochDay()) {
        0L -> "Today"
        -1L -> "Yesterday"
        1L -> "Tomorrow"
        else -> formatDate(isoDate)
    }
}
