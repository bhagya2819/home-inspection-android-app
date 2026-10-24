package com.homeinspection.app.feature.findings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.core.ai.AiException
import com.homeinspection.app.core.ai.AiRepository
import com.homeinspection.app.core.data.InspectionRepository
import com.homeinspection.app.core.data.mergeFindingEdits
import com.homeinspection.app.core.data.PhotoRepository
import com.homeinspection.app.core.database.FindingEntity
import com.homeinspection.app.core.model.FindingCategories
import com.homeinspection.app.core.model.FindingStatus
import com.homeinspection.app.core.model.Severity
import com.homeinspection.app.ui.components.AiBar
import com.homeinspection.app.ui.components.AutoSaver
import com.homeinspection.app.ui.components.SuggestionCard
import com.homeinspection.app.ui.components.PhotoThumb
import com.homeinspection.app.ui.components.offPhoneLabel
import com.homeinspection.app.ui.components.SaveStateLabel
import com.homeinspection.app.ui.theme.severityColors
import com.homeinspection.app.ui.components.FormSection
import com.homeinspection.app.ui.components.pressScale
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import com.homeinspection.app.ui.homeInspectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Report fields the AI can suggest text for. */
enum class AiField { TITLE, OBSERVATION, IMPLICATION, RECOMMENDATION, SEVERITY, CATEGORY }

/** The field's current value as the AI sees it (SEVERITY as its enum name). */
fun FindingEntity.valueOf(field: AiField): String? = when (field) {
    AiField.TITLE -> title
    AiField.OBSERVATION -> detailedObservation
    AiField.IMPLICATION -> implication
    AiField.RECOMMENDATION -> recommendation
    AiField.SEVERITY -> severity?.name
    AiField.CATEGORY -> category
}

/** [basedOn]: the field's value when the AI was asked. Once the inspector changes it, the suggestion is stale. */
data class AiSuggestion(val value: String, val basedOn: String?)

class FindingViewModel(
    private val findingId: String,
    private val repo: InspectionRepository,
    photoRepo: PhotoRepository,
    private val ai: AiRepository,
    appScope: CoroutineScope,
) : ViewModel() {
    /** Local working copy; the DB is updated from it by [saver]. */
    var draft by mutableStateOf<FindingEntity?>(null)
        private set

    /** The DB row the draft is based on; fields equal to it are "untouched" and follow the DB. */
    private var base: FindingEntity? = null
    var deleted by mutableStateOf(false)
        private set

    private val saver = AutoSaver(appScope)
    val saveState = saver.state

    val photos = photoRepo.observeForFinding(findingId)
        .map { list -> list.map { it to photoRepo.resolve(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Follow the DB: a change pulled from another phone shows up in fields the inspector hasn't
        // touched, while fields being typed keep the inspector's text (three-way merge).
        viewModelScope.launch {
            repo.observeFinding(findingId).collect { row ->
                if (row == null) return@collect
                val b = base
                val d = draft
                draft = if (b == null || d == null) row else mergeFindingEdits(b, d, row)
                base = row
            }
        }
    }

    fun edit(change: (FindingEntity) -> FindingEntity) {
        val updated = draft?.let(change) ?: return
        val b = base ?: return
        draft = updated
        saver.schedule(findingId) { repo.saveFinding(b, updated) }
    }

    // ---- AI suggestions: shown beside each field, applied only when the inspector accepts ------

    val aiAvailable = ai.enabled

    /** Suggestions not yet accepted or discarded. SEVERITY holds the enum name. */
    private var suggestions by mutableStateOf<Map<AiField, AiSuggestion>>(emptyMap())

    /** Shown only while the field still holds what the AI was given: accepting never overwrites newer typing. */
    fun suggestion(field: AiField): String? {
        val s = suggestions[field] ?: return null
        return s.value.takeIf { draft?.valueOf(field) == s.basedOn }
    }

    val pendingCount get() = AiField.entries.count { suggestion(it) != null }
    var severityReason by mutableStateOf<String?>(null)
        private set
    var aiBusy by mutableStateOf(false)
        private set
    var aiError by mutableStateOf<String?>(null)
        private set

    fun askAi() {
        val current = draft ?: return
        if (aiBusy) return
        aiBusy = true
        aiError = null
        viewModelScope.launch {
            try {
                val s = ai.suggestFinding(current)
                val proposed = mapOf(
                    AiField.TITLE to s.title, AiField.OBSERVATION to s.observation, AiField.IMPLICATION to s.implication,
                    AiField.RECOMMENDATION to s.recommendation, AiField.SEVERITY to s.severity?.name, AiField.CATEGORY to s.category,
                )
                // Only what would change the finding, based on what the AI was actually given.
                suggestions = proposed.mapNotNull { (field, value) ->
                    val asked = current.valueOf(field)
                    value?.takeIf { it != asked }?.let { field to AiSuggestion(it, asked) }
                }.toMap()
                severityReason = s.severityReason
                aiError = when {
                    suggestions.isEmpty() -> "The AI has nothing to change: the finding already reads well."
                    pendingCount < suggestions.size -> "Fields you edited while the AI was working keep your text."
                    else -> null
                }
            } catch (e: AiException) {
                aiError = e.message
            } finally {
                aiBusy = false
            }
        }
    }

    fun accept(field: AiField) {
        val value = suggestion(field) ?: return discard(field)
        edit {
            when (field) {
                AiField.TITLE -> it.copy(title = value)
                AiField.OBSERVATION -> it.copy(detailedObservation = value)
                AiField.IMPLICATION -> it.copy(implication = value)
                AiField.RECOMMENDATION -> it.copy(recommendation = value)
                AiField.SEVERITY -> it.copy(severity = Severity.valueOf(value))
                AiField.CATEGORY -> it.copy(category = value)
            }
        }
        discard(field)
    }

    fun discard(field: AiField) { suggestions = suggestions - field }

    fun acceptAll() = AiField.entries.filter { suggestion(it) != null }.forEach(::accept)

    fun discardAll() { suggestions = emptyMap(); aiError = null }

    fun delete() {
        viewModelScope.launch {
            repo.deleteFinding(findingId)
            deleted = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FindingScreen(
    findingId: String,
    onBack: () -> Unit,
    onAddPhoto: (inspectionId: String, roomId: String) -> Unit,
    onOpenPhoto: (index: Int) -> Unit,
) {
    val vm = homeInspectionViewModel {
        FindingViewModel(findingId, it.container.inspectionRepository, it.container.photoRepository, it.container.aiRepository, it.appScope)
    }
    val saveState by vm.saveState.collectAsStateWithLifecycle()
    val aiAvailable by vm.aiAvailable.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm.deleted) { if (vm.deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Finding")
                        // Fades between "Saving…" and "Saved on this phone" instead of flickering.
                        Crossfade(saveState, animationSpec = tween(200), label = "saveState") { SaveStateLabel(it) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete finding") } },
            )
        },
    ) { padding ->
        val f = vm.draft ?: return@Scaffold
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Photos first: they're what the inspector recognises the finding by.
            FormSection("Photos · ${photos.size}") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(photos, key = { _, p -> p.first.id }) { index, (photo, file) ->
                        PhotoThumb(
                            file, photo.uploadState, size = 88.dp,
                            modifier = Modifier.animateItem().clip(RoundedCornerShape(10.dp)).clickable { onOpenPhoto(index) },
                            offPhone = photo.offPhoneLabel(),
                        )
                    }
                    item(key = "add") { AddPhotoTile(Modifier.animateItem()) { onAddPhoto(f.inspectionId, f.roomId) } }
                }
            }

            FormSection("What's the issue") {
                OutlinedTextField(
                    f.title.orEmpty(), { v -> vm.edit { it.copy(title = v) } },
                    label = { Text("Title") },
                    placeholder = { Text("Hairline crack near window") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                vm.suggestion(AiField.TITLE)?.let { SuggestionCard(it, { vm.accept(AiField.TITLE) }, { vm.discard(AiField.TITLE) }) }
                FieldLabel("Severity")
                SeverityPicker(f.severity) { s -> vm.edit { it.copy(severity = if (it.severity == s) null else s) } }
                vm.suggestion(AiField.SEVERITY)?.let { s ->
                    val label = Severity.valueOf(s).label
                    SuggestionCard(
                        text = vm.severityReason?.let { "$label - $it" } ?: label,
                        onAccept = { vm.accept(AiField.SEVERITY) }, onDiscard = { vm.discard(AiField.SEVERITY) },
                    )
                }
                FieldLabel("Category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FindingCategories.forEach { c ->
                        FilterChip(
                            selected = f.category == c,
                            onClick = { vm.edit { it.copy(category = if (it.category == c) null else c) } },
                            label = { Text(c) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
                vm.suggestion(AiField.CATEGORY)?.let { SuggestionCard(it, { vm.accept(AiField.CATEGORY) }, { vm.discard(AiField.CATEGORY) }) }
            }

            FormSection(
                "Notes for the report",
                subtitle = {
                    Text(
                        "The site note is only for you and never printed. The three fields below go in the report.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                ReportField("Site note", "Quick note from the site", f.roughNote, minLines = 2) { v -> vm.edit { it.copy(roughNote = v) } }
                if (aiAvailable) {
                    AiBar(
                        canAsk = f.roughNote.isNotBlank() || !f.title.isNullOrBlank() || !f.detailedObservation.isNullOrBlank(),
                        busy = vm.aiBusy,
                        pending = vm.pendingCount,
                        error = vm.aiError,
                        onAsk = vm::askAi,
                        onAcceptAll = vm::acceptAll,
                        onDiscardAll = vm::discardAll,
                    )
                }
                ReportField("Detailed observation", "What exactly was observed, where, and how extensive", f.detailedObservation) { v ->
                    vm.edit { it.copy(detailedObservation = v) }
                }
                vm.suggestion(AiField.OBSERVATION)?.let { SuggestionCard(it, { vm.accept(AiField.OBSERVATION) }, { vm.discard(AiField.OBSERVATION) }) }
                // Site notes aren't printed: an observation left empty would show nothing in the report.
                if (f.detailedObservation.isNullOrBlank() && f.roughNote.isNotBlank()) {
                    TextButton(onClick = { vm.edit { it.copy(detailedObservation = it.roughNote.trim()) } }) {
                        Text("Copy site note into observation")
                    }
                }
                ReportField("Implication", "Why it matters: risk if left unaddressed", f.implication) { v ->
                    vm.edit { it.copy(implication = v) }
                }
                vm.suggestion(AiField.IMPLICATION)?.let { SuggestionCard(it, { vm.accept(AiField.IMPLICATION) }, { vm.discard(AiField.IMPLICATION) }) }
                ReportField("Recommendation", "Suggested action, and by whom", f.recommendation) { v ->
                    vm.edit { it.copy(recommendation = v) }
                }
                vm.suggestion(AiField.RECOMMENDATION)?.let { SuggestionCard(it, { vm.accept(AiField.RECOMMENDATION) }, { vm.discard(AiField.RECOMMENDATION) }) }
            }

            FormSection("Status") {
                val haptics = LocalHapticFeedback.current
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FindingStatus.entries.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = f.status == s,
                            onClick = {
                                if (f.status != s) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                vm.edit { it.copy(status = s) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, FindingStatus.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            label = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this finding?") },
            text = {
                Text(
                    "It will be removed from the inspection and report" +
                        if (photos.isNotEmpty()) ", along with its ${photos.size} photos. Original photo files stay on the phone and still back up." else "."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private val FieldShape = RoundedCornerShape(12.dp)

@Composable
private fun FieldLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ReportField(label: String, hint: String, value: String?, minLines: Int = 3, onChange: (String) -> Unit) {
    OutlinedTextField(
        value.orEmpty(), onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        minLines = minLines, modifier = Modifier.fillMaxWidth(),
        shape = FieldShape,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

/**
 * Severity as fixed tiles, each with its colour dot: the two mild ones on top (wide enough for
 * "Observation" on a narrow phone), then the three serious ones, so it escalates as you read. Selecting only
 * changes colours and the border, so nothing resizes or reflows. Tap the selected one to clear it.
 */
@Composable
private fun SeverityPicker(selected: Severity?, onSelect: (Severity) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Severity.entries.take(2), Severity.entries.drop(2)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    SeverityTile(s, selected == s, Modifier.weight(1f)) {
                        haptics.performHapticFeedback(if (selected == s) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        onSelect(s)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeverityTile(severity: Severity, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = severityColors(severity)
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) tint.container else scheme.surfaceContainer, tween(150), label = "sev")
    val border by animateColorAsState(if (selected) tint.content else Color.Transparent, tween(150), label = "sevBorder")
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        color = container,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.5.dp, border),
        interactionSource = interaction,
        modifier = modifier.height(46.dp).pressScale(interaction),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(tint.content))
            Text(
                severity.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) tint.content else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

/** Same size as a photo, dashed outline: "add one more" sits where the next photo will appear. */
@Composable
private fun AddPhotoTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Column(
        modifier
            .size(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Add photo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
