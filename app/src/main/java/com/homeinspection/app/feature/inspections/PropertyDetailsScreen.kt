package com.homeinspection.app.feature.inspections

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.ui.components.FormSection
import com.homeinspection.app.ui.components.pressScale
import com.homeinspection.app.core.data.InspectionRepository
import com.homeinspection.app.core.data.PropertyDetails
import com.homeinspection.app.core.model.DefaultRoomChoices
import com.homeinspection.app.core.model.PropertyTypes
import com.homeinspection.app.ui.homeInspectionViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A room tile. [order] is its 1-based position in the tick sequence, or null when not ticked. */
data class RoomChoice(val name: String, val order: Int?) {
    val selected get() = order != null
}

/** One screen for both "New inspection" ([inspectionId] null) and "Edit property details". */
class PropertyDetailsViewModel(private val inspectionId: String?, private val repo: InspectionRepository) : ViewModel() {
    val isNew = inspectionId == null
    var details by mutableStateOf(PropertyDetails())
    var loaded by mutableStateOf(isNew)
        private set
    var saving by mutableStateOf(false)
        private set
    /** Tile names in their fixed grid order; ticking never moves a tile. */
    private val roomNames = mutableStateListOf<String>().apply { addAll(DefaultRoomChoices) }

    /** Ticked rooms in the order they were ticked: the order the new inspection's rooms get. */
    private val picked = mutableStateListOf<String>()

    val rooms: List<RoomChoice> get() = roomNames.map { name -> RoomChoice(name, picked.indexOf(name).takeIf { it >= 0 }?.plus(1)) }

    init {
        if (inspectionId != null) viewModelScope.launch {
            repo.observeInspection(inspectionId).first()?.let { details = PropertyDetails.of(it) }
            loaded = true
        }
    }

    /** Unticking a room closes the gap: the rooms ticked after it move up one place. */
    fun toggleRoom(index: Int) {
        val name = roomNames[index]
        if (!picked.remove(name)) picked += name
    }

    fun addCustomRoom(name: String) {
        if (name.isBlank()) return
        // Typing a room that already has a tile ticks that tile instead of adding a duplicate.
        val existing = roomNames.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
        if (existing == null) roomNames += name.trim()
        val room = existing ?: name.trim()
        if (room !in picked) picked += room
    }

    fun save(onSaved: (inspectionId: String) -> Unit) {
        if (!details.isValid || saving) return
        saving = true
        viewModelScope.launch {
            val id = if (inspectionId == null) {
                repo.create(details, picked.toList())
            } else {
                repo.updateDetails(inspectionId, details)
                inspectionId
            }
            onSaved(id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PropertyDetailsScreen(inspectionId: String?, onBack: () -> Unit, onSaved: (inspectionId: String) -> Unit) {
    val vm = homeInspectionViewModel { PropertyDetailsViewModel(inspectionId, it.container.inspectionRepository) }
    val d = vm.details
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    // Leaving a new form that already has typed details asks first, so a stray Back doesn't lose them.
    val dirty = vm.isNew && (d.clientName.isNotBlank() || d.propertyAddress.isNotBlank() || d.clientPhone.isNotBlank())
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val back = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New inspection" else "Property details") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Button(
                    onClick = { vm.save(onSaved) },
                    enabled = d.isValid && !vm.saving && vm.loaded,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).height(52.dp),
                ) { Text(if (vm.isNew) "Start inspection" else "Save details", style = MaterialTheme.typography.titleSmall) }
            }
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormSection("Client") {
                OutlinedTextField(
                    d.clientName, { vm.details = d.copy(clientName = it) },
                    label = { Text("Client name *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    d.clientPhone, { vm.details = d.copy(clientPhone = it) },
                    label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                )
            }

            FormSection("Property") {
                OutlinedTextField(
                    d.propertyAddress, { vm.details = d.copy(propertyAddress = it) },
                    label = { Text("Property address *") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    d.unitNumber, { vm.details = d.copy(unitNumber = it) },
                    label = { Text("Unit / flat number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                )
                Text("Property type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PropertyTypes.forEach { type ->
                        FilterChip(
                            selected = d.propertyType == type,
                            onClick = { vm.details = d.copy(propertyType = if (d.propertyType == type) "" else type) },
                            label = { Text(type) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Inspection date", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = null)
                        Text(d.inspectionDate.format(DateFormat), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    d.accessNotes, { vm.details = d.copy(accessNotes = it) },
                    label = { Text("General notes (optional)") },
                    placeholder = { Text("Access, keys, contact on site…") },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            if (vm.isNew) {
                val rooms = vm.rooms
                val selectedCount = rooms.count { it.selected }
                FormSection(
                    "Rooms to inspect",
                    subtitle = {
                        Row {
                            // The number rolls instead of the whole line redrawing.
                            AnimatedContent(
                                selectedCount,
                                transitionSpec = {
                                    val up = targetState > initialState
                                    (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                                },
                                label = "roomCount",
                            ) { n -> Text("$n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(
                                " selected · tick them in visiting order; you can reorder later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ) {
                    RoomGrid(rooms, onToggle = vm::toggleRoom)
                    var custom by rememberSaveable { mutableStateOf("") }
                    val addCustom = { vm.addCustomRoom(custom); custom = "" }
                    OutlinedTextField(
                        custom, { custom = it },
                        label = { Text("Add another room") },
                        placeholder = { Text("Utility, Parking, Pooja room…") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = FieldShape,
                        trailingIcon = {
                            IconButton(enabled = custom.isNotBlank(), onClick = addCustom) { Icon(Icons.Filled.Add, "Add room") }
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (custom.isNotBlank()) addCustom() }),
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this inspection?") },
            text = { Text("The details you typed haven't been saved yet.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = d.inspectionDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        vm.details = d.copy(inspectionDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

private val FieldShape = RoundedCornerShape(12.dp)

/**
 * Rooms as a fixed two-column grid of equal tiles. The checkbox space is always there, so ticking a
 * room only changes colours — nothing changes size, so nothing reflows or jumps (the old chips grew
 * a tick icon and re-wrapped the whole row).
 */
@Composable
private fun RoomGrid(rooms: List<RoomChoice>, onToggle: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rooms.withIndex().chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (index, room) ->
                    RoomTile(room, Modifier.weight(1f)) {
                        haptics.performHapticFeedback(if (room.selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        onToggle(index)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RoomTile(room: RoomChoice, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tween = tween<Color>(150)
    val container by animateColorAsState(if (room.selected) scheme.primaryContainer else scheme.surfaceContainer, tween, label = "tile")
    val boxFill by animateColorAsState(if (room.selected) scheme.primary else Color.Transparent, tween, label = "box")
    val boxBorder by animateColorAsState(if (room.selected) scheme.primary else scheme.outline, tween, label = "boxBorder")
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        color = container,
        shape = RoundedCornerShape(12.dp),
        interactionSource = interaction,
        modifier = modifier.height(52.dp).pressScale(interaction),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(boxFill).border(1.5.dp, boxBorder, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // The tick shows the room's place in the visit order instead of a check mark.
                androidx.compose.animation.AnimatedVisibility(room.selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Text("${room.order ?: ""}", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
                }
            }
            Text(
                room.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (room.selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

fun formatDate(isoDate: String): String = LocalDate.parse(isoDate).format(DateFormat)
