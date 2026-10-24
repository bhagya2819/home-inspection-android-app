package com.homeinspection.app.feature.inspections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.core.data.InspectionRepository
import com.homeinspection.app.core.data.MoveDirection
import com.homeinspection.app.core.database.InspectionEntity
import com.homeinspection.app.core.database.RoomSummary
import com.homeinspection.app.core.model.InspectionStatus
import com.homeinspection.app.ui.homeInspectionViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InspectionViewModel(private val inspectionId: String, private val repo: InspectionRepository) : ViewModel() {
    val inspection = repo.observeInspection(inspectionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rooms = repo.observeRooms(inspectionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRoom(name: String) = viewModelScope.launch { repo.addCustomRoom(inspectionId, name) }
    fun renameRoom(roomId: String, name: String) = viewModelScope.launch { repo.renameRoom(roomId, name) }
    fun moveRoom(roomId: String, direction: MoveDirection) = viewModelScope.launch { repo.moveRoom(inspectionId, roomId, direction) }
    fun removeRoom(roomId: String) = viewModelScope.launch { repo.removeRoom(roomId) }
    fun setStatus(status: InspectionStatus) = viewModelScope.launch { repo.setStatus(inspectionId, status) }
}

/** Which room dialog is open, if any. */
private sealed interface RoomDialog {
    data object Add : RoomDialog
    data class Rename(val room: RoomSummary) : RoomDialog
    data class Remove(val room: RoomSummary) : RoomDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionScreen(
    inspectionId: String,
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onEditDetails: () -> Unit,
    onOpenReport: () -> Unit,
) {
    val vm = homeInspectionViewModel { InspectionViewModel(inspectionId, it.container.inspectionRepository) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.homeinspection.app.HomeInspectionApp
    // Inspections started on another phone: fetch their original photos in the background.
    androidx.compose.runtime.LaunchedEffect(inspectionId) {
        app.appScope.launch {
            val photos = app.container.database.photoDao().allForInspection(inspectionId)
            app.container.syncEngine.ensurePhotosLocal(photos)
        }
    }
    val inspection by vm.inspection.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<RoomDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(inspection?.clientName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = { inspection?.let { StatusBar(it, vm::setStatus, onOpenReport) } },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            inspection?.let { item(key = "details") { DetailsCard(it, onEditDetails) } }
            item(key = "rooms-title") {
                Text(
                    "Rooms & areas (${rooms.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(rooms, key = { _, r -> r.room.id }) { index, summary ->
                RoomRow(
                    summary = summary,
                    canMoveUp = index > 0,
                    canMoveDown = index < rooms.lastIndex,
                    onOpen = { onOpenRoom(summary.room.id) },
                    onRename = { dialog = RoomDialog.Rename(summary) },
                    onMove = { vm.moveRoom(summary.room.id, it) },
                    onRemove = { dialog = RoomDialog.Remove(summary) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "add-room") {
                OutlinedButton(onClick = { dialog = RoomDialog.Add }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add custom area", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        RoomDialog.Add -> NameDialog("Add area", "", "Add", onDismiss = { dialog = null }) { vm.addRoom(it); dialog = null }
        is RoomDialog.Rename -> NameDialog("Rename area", d.room.room.name, "Rename", onDismiss = { dialog = null }) {
            vm.renameRoom(d.room.room.id, it); dialog = null
        }
        is RoomDialog.Remove -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove ${d.room.room.name}?") },
            text = {
                Text(
                    if (d.room.findingCount == 0) "This area has no findings."
                    else "Its ${d.room.findingCount} findings and ${d.room.photoCount} photos will be removed from this " +
                        "inspection and its report. Original photo files stay on the phone and still back up."
                )
            },
            confirmButton = { TextButton(onClick = { vm.removeRoom(d.room.room.id); dialog = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailsCard(i: InspectionEntity, onEdit: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(i.status)
                Text(
                    formatDate(i.inspectionDate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit property details") }
            }
            Text(listOfNotNull(i.unitNumber, i.propertyAddress).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            val meta = listOfNotNull(i.propertyType, i.clientPhone.ifBlank { null })
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            i.accessNotes?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RoomRow(
    summary: RoomSummary,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: (MoveDirection) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(summary.room.name) },
        supportingContent = {
            Text(if (summary.findingCount == 0) "No findings yet" else "${summary.findingCount} findings · ${summary.photoCount} photos")
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Room options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp, onClick = { menu = false; onMove(MoveDirection.UP) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown, onClick = { menu = false; onMove(MoveDirection.DOWN) })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
    )
}

/**
 * Status actions (PRD §5.2). Ready → review/finalize the report. Finalized → open the shared
 * version, or go back to editing, which leads to a new version on the next finalize.
 */
@Composable
private fun StatusBar(i: InspectionEntity, onSetStatus: (InspectionStatus) -> Unit, onOpenReport: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (i.status) {
                InspectionStatus.READY_FOR_REPORT -> {
                    OutlinedButton(onClick = { onSetStatus(InspectionStatus.IN_PROGRESS) }, modifier = Modifier.weight(1f)) {
                        Text("Reopen")
                    }
                    Button(onClick = onOpenReport, modifier = Modifier.weight(1f)) { Text("Review report") }
                }
                InspectionStatus.FINALIZED -> {
                    OutlinedButton(onClick = { onSetStatus(InspectionStatus.IN_PROGRESS) }, modifier = Modifier.weight(1f)) {
                        Text("Edit (new version)")
                    }
                    Button(onClick = onOpenReport, modifier = Modifier.weight(1f)) { Text("Report v${i.reportVersion}") }
                }
                else -> Button(onClick = { onSetStatus(InspectionStatus.READY_FOR_REPORT) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mark ready for report")
                }
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Sit-out, Terrace, Utility") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
