package com.homeinspection.app.feature.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.homeinspection.app.ui.components.AI_MARK
import com.homeinspection.app.ui.components.SuggestionCard
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.HomeInspectionApp
import com.homeinspection.app.core.ai.AiException
import com.homeinspection.app.core.ai.ReviewIssue
import com.homeinspection.app.core.database.FindingWithRoom
import com.homeinspection.app.core.database.InspectionEntity
import com.homeinspection.app.core.database.ReportEntity
import com.homeinspection.app.core.model.InspectionStatus
import com.homeinspection.app.core.report.ReportProgress
import com.homeinspection.app.core.report.ReportRepository
import com.homeinspection.app.ui.homeInspectionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders pages of one PDF on demand. PdfRenderer allows one open page at a time, hence the lock. */
class PdfPages(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    private var closed = false
    val count = renderer.pageCount

    /** Null when closed, or when memory is short for this size (the caller keeps what it shows). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed) return null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width).toInt()
                try {
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE) // PdfRenderer leaves the background transparent
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
        }
    }

    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        renderer.close()
        fd.close()
    }
}

sealed interface Preview {
    data class Working(val progress: ReportProgress?) : Preview
    /** [report] null = the current draft. */
    data class Ready(val pages: PdfPages, val report: ReportEntity?, val skippedPhotos: Int) : Preview
    data class Failed(val message: String) : Preview
}

/** Longer summaries crowd the summary page; also well under the server's 20 KB field limit. */
const val SUMMARY_MAX = 3000

data class SummaryEdit(
    val text: String,
    val suggestion: String? = null,
    val undo: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The AI pre-finalize check. */
sealed interface Review {
    data object Idle : Review
    data object Running : Review
    /** [basedOn]: the inspection's updatedAt when the check started; later edits make it out of date. */
    data class Done(val issues: List<ReviewIssue>, val basedOn: Long) : Review
    data class Failed(val message: String) : Review
}

class ReportViewModel(private val inspectionId: String, private val app: HomeInspectionApp) : ViewModel() {
    private val repo: ReportRepository = app.container.reportRepository
    private val ai = app.container.aiRepository
    val aiAvailable = ai.enabled

    /** Findings by id, to name them in the AI check's list. */
    val findings = app.container.inspectionRepository.observeFindingsForInspection(inspectionId)
        .map { list -> list.associateBy { it.finding.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _review = MutableStateFlow<Review>(Review.Idle)
    val review = _review.asStateFlow()

    fun runReview() {
        if (_review.value == Review.Running) return
        _review.value = Review.Running
        val basedOn = inspection.value?.updatedAt ?: 0
        viewModelScope.launch {
            _review.value = try {
                Review.Done(ai.review(inspectionId), basedOn)
            } catch (e: AiException) {
                Review.Failed(e.message ?: "The AI check failed.")
            }
        }
    }

    fun clearReview() { _review.value = Review.Idle }

    // ---- Summary editor: held here so rotation or a theme change never loses the text ------

    var summary by mutableStateOf<SummaryEdit?>(null)
        private set

    fun editSummary() { summary = SummaryEdit(text = inspection.value?.summary.orEmpty()) }

    fun typeSummary(text: String) { summary = summary?.copy(text = text.take(SUMMARY_MAX)) }

    fun draftSummary() {
        val edit = summary ?: return
        if (edit.busy) return
        summary = edit.copy(busy = true, error = null)
        viewModelScope.launch {
            summary = try {
                summary?.copy(busy = false, suggestion = ai.draftSummary(inspectionId).take(SUMMARY_MAX))
            } catch (e: AiException) {
                summary?.copy(busy = false, error = e.message)
            }
        }
    }

    /** Replaces the text with the AI draft; [undoSummary] brings back what was there. */
    fun acceptSummary() {
        val edit = summary ?: return
        summary = edit.copy(text = edit.suggestion ?: return, suggestion = null, undo = edit.text)
    }

    fun discardSummary() { summary = summary?.copy(suggestion = null) }

    fun undoSummary() { summary = summary?.let { it.copy(text = it.undo ?: it.text, undo = null) } }

    fun closeSummary() { summary = null }

    /** In the app scope: closing the screen right after Save must still save. */
    fun saveSummary() {
        val text = summary?.text ?: return
        summary = null
        app.appScope.launch { app.container.inspectionRepository.updateSummary(inspectionId, text) }
    }

    val inspection = app.container.inspectionRepository.observeInspection(inspectionId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val reports = repo.observeReports(inspectionId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Version the inspector picked from the chips; null = whatever is current. */
    private val selected = MutableStateFlow<String?>(null)
    val selectedId = selected.asStateFlow()

    private val _inspector = MutableStateFlow<String?>(null)
    val inspector = _inspector.asStateFlow()

    /** False once the inspector is a signed-in account: their name comes from the server. */
    private val _inspectorEditable = MutableStateFlow(true)
    val inspectorEditable = _inspectorEditable.asStateFlow()

    private val _preview = MutableStateFlow<Preview>(Preview.Working(null))
    val preview = _preview.asStateFlow()

    /**
     * Non-null while a finalize of this inspection is running. It continues if the screen closes, so
     * this comes from the repository: a reopened screen shows it too, not a second Finalize button.
     */
    val finalizing = repo.finalizing.map { it[inspectionId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.finalizing.value[inspectionId])

    /** Covers a double tap before the launched finalize has registered with the repository. */
    @Volatile private var finalizeStarted = false

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val retry = MutableStateFlow(0)
    private var openPages: PdfPages? = null

    private data class Key(val updatedAt: Long, val status: InspectionStatus, val selected: String?, val versions: List<String>, val inspector: String, val retry: Int)

    init {
        viewModelScope.launch {
            _inspectorEditable.value = repo.serverInspectorName(inspectionId) == null
            _inspector.value = repo.inspectorNameFor(inspectionId)
        }
        viewModelScope.launch {
            combine(
                inspection.filterNotNull(), selected, reports.map { list -> list.map { it.id } }, _inspector.filterNotNull(), retry,
            ) { i, sel, versions, name, r -> Key(i.updatedAt, i.status, sel, versions, name, r) }
                .distinctUntilChanged()
                // A newer edit cancels a render that is still running.
                .collectLatest { key -> load(key) }
        }
    }

    private suspend fun load(key: Key) {
        val all = reports.value
        val report = when {
            key.selected != null -> all.firstOrNull { it.id == key.selected }
            key.status == InspectionStatus.FINALIZED -> all.firstOrNull()
            else -> null
        }
        try {
            if (report != null) {
                _preview.value = Preview.Working(null)
                val file = repo.ensureFile(report)
                    ?: throw java.io.IOException("This version was finalized on another phone and isn't on this one yet. Connect to the internet and try again.")
                show(PdfPages(file), report, 0)
            } else {
                _preview.value = Preview.Working(null)
                val rendered = repo.renderDraft(inspectionId) { _preview.value = Preview.Working(it) }
                show(PdfPages(rendered.file), null, rendered.skippedPhotos)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _preview.value = Preview.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun show(pages: PdfPages, report: ReportEntity?, skipped: Int) {
        val old = openPages
        openPages = pages
        _preview.value = Preview.Ready(pages, report, skipped)
        old?.let { app.appScope.launch { it.close() } }
    }

    fun select(reportId: String?) { selected.value = reportId }
    fun retry() { retry.value++ }
    fun dismissError() { _error.value = null }

    fun setInspector(name: String) = viewModelScope.launch {
        repo.setInspectorName(name)
        _inspector.value = name.trim()
    }

    /** In the app scope: leaving the screen must not leave a half-recorded version. */
    fun finalize() {
        if (finalizeStarted || finalizing.value != null) return
        finalizeStarted = true
        app.appScope.launch {
            try {
                repo.finalize(inspectionId) {}
                selected.value = null
            } catch (e: Exception) {
                _error.value = "Could not finalize the report: ${e.message}"
            } finally {
                finalizeStarted = false
            }
        }
    }

    fun share(context: Context, report: ReportEntity) {
        val file = repo.fileFor(report)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val i = inspection.value
        val subject = "Home Inspection inspection report – ${i?.let { listOfNotNull(it.unitNumber, it.propertyAddress).joinToString(", ") }.orEmpty()} (v${report.version})"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, subject)
            clipData = ClipData.newRawUri(file.name, uri) // carries the read grant to the chosen app
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report"))
        app.appScope.launch { repo.markShared(report.id) }
    }

    override fun onCleared() {
        openPages?.let { app.appScope.launch { it.close() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(inspectionId: String, onBack: () -> Unit, onOpenFinding: (String) -> Unit) {
    val vm = homeInspectionViewModel { ReportViewModel(inspectionId, it) }
    val inspection by vm.inspection.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val inspector by vm.inspector.collectAsStateWithLifecycle()
    val inspectorEditable by vm.inspectorEditable.collectAsStateWithLifecycle()
    val finalizing by vm.finalizing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val aiAvailable by vm.aiAvailable.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val findings by vm.findings.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf(false) }
    var showReview by rememberSaveable { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmFinalize by remember { mutableStateOf(false) }
    // Site notes aren't printed, so these findings would show only a title in the report.
    val withoutObservation = findings.values.filter { it.finding.detailedObservation.isNullOrBlank() }
    val context = LocalContext.current

    val ready = preview as? Preview.Ready
    val viewingDraft = selectedId == null && inspection?.status != InspectionStatus.FINALIZED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready?.report?.let { "Report v${it.version}" } ?: "Review report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 12.dp)) {
                    val f = finalizing
                    when {
                        f != null -> ProgressLine("Finalizing", f)
                        ready?.report != null -> VersionActions(ready.report, latest = reports.firstOrNull()) { vm.share(context, ready.report) }
                        viewingDraft -> {
                            if (aiAvailable) {
                                ReviewLine(
                                    review,
                                    outdated = (review as? Review.Done)?.let { (inspection?.updatedAt ?: 0) > it.basedOn } == true,
                                    onRun = { vm.runReview(); showReview = true },
                                    onShow = { showReview = true },
                                )
                            }
                            FinalizeActions(
                                nextVersion = (reports.firstOrNull()?.version ?: 0) + 1,
                                enabled = ready != null,
                                onFinalize = {
                                    when {
                                        inspector.isNullOrBlank() -> editName = true
                                        withoutObservation.isNotEmpty() -> confirmEmpty = true
                                        else -> confirmFinalize = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (reports.isNotEmpty()) {
                VersionChips(inspection, reports, ready?.report?.id ?: if (viewingDraft) null else selectedId, vm::select)
            }
            if (viewingDraft) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Inspector: ${inspector?.ifBlank { null } ?: "not set"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (inspectorEditable) TextButton(onClick = { editName = true }) { Text("Change") }
                }
            }
            if (selectedId == null) {
                // The current state (draft, or the latest finalized one): saving reopens a finalized
                // inspection like any other edit, and the next finalize makes a new version.
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        inspection?.summary?.let { "Summary: $it" } ?: "Summary: not written (optional)",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::editSummary) { Text(if (inspection?.summary == null) "Write" else "Edit") }
                }
            }
            if (ready != null && ready.skippedPhotos > 0) {
                Text(
                    "${ready.skippedPhotos} photo(s) could not be read on this phone and are left out of the report.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                when (val p = preview) {
                    is Preview.Working -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (p.progress == null) CircularProgressIndicator() else ProgressLine("Preparing report", p.progress)
                    }
                    is Preview.Failed -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not build the report: ${p.message}", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::retry, modifier = Modifier.padding(top = 12.dp)) { Text("Try again") }
                    }
                    is Preview.Ready -> PageList(p.pages)
                }
            }
        }
    }

    vm.summary?.let { edit -> SummaryDialog(edit, aiAvailable, vm) }
    if (confirmEmpty) {
        EmptyObservationDialog(
            findings = withoutObservation,
            onOpen = { id -> confirmEmpty = false; onOpenFinding(id) },
            onDismiss = { confirmEmpty = false },
            onFinalizeAnyway = { confirmEmpty = false; vm.finalize() },
        )
    }
    if (confirmFinalize) {
        // The checkbox and button sit where the thumb rests while scrolling the pages: two stray taps
        // there made versions nobody meant to (v16, v17 on the POCO). A numbered client report needs
        // a deliberate tap in the middle of the screen.
        val next = (reports.firstOrNull()?.version ?: 0) + 1
        AlertDialog(
            onDismissRequest = { confirmFinalize = false },
            title = { Text("Finalize as version $next?") },
            text = { Text("This makes report version $next, ready to share with the client. It can't be changed afterwards; later edits make version ${next + 1}.") },
            confirmButton = { TextButton(onClick = { confirmFinalize = false; vm.finalize() }) { Text("Finalize v$next") } },
            dismissButton = { TextButton(onClick = { confirmFinalize = false }) { Text("Cancel") } },
        )
    }
    if (showReview && review != Review.Idle) {
        ReviewDialog(
            review = review,
            findings = findings,
            outdated = (review as? Review.Done)?.let { (inspection?.updatedAt ?: 0) > it.basedOn } == true,
            onDismiss = { showReview = false },
            onRetry = vm::runReview,
            onOpen = { id -> showReview = false; onOpenFinding(id) },
        )
    }
    if (editName) {
        InspectorDialog(inspector.orEmpty(), onDismiss = { editName = false }) { vm.setInspector(it); editName = false }
    }
    error?.let {
        AlertDialog(
            onDismissRequest = vm::dismissError,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
        )
    }
}

/**
 * The pages, zoomable where they are: pinch anywhere to zoom about the fingers, drag (one or two
 * fingers) to move, double-tap to zoom in at that spot or back out. The zoom is a graphics layer
 * over the whole list, so touches reach the list in its own (unscaled) coordinates: a vertical
 * drag scrolls 1:1 with the finger and flings natively at any zoom. Moving the layer is clamped
 * to the zoomed list's edges, so a page can never be dragged off-screen and left there.
 */
@Composable
private fun PageList(pages: PdfPages) {
    val list = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1) / 2
        val maxY = box.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Vertical movement the layer can't take (it's at its edge) scrolls the list instead. */
    fun moveTo(target: Offset, s: Float) {
        val clamped = clamp(target, s)
        val excessY = target.y - clamped.y
        if (excessY != 0f) list.dispatchRawDelta(-excessY / s)
        offset = clamped
    }

    fun zoomTo(newScale: Float, focus: Offset) {
        val s = newScale.coerceIn(1f, MAX_PAGE_ZOOM)
        // Keep the page point under [focus] (relative to the centre) where it is.
        val c = focus - Offset(box.width / 2f, box.height / 2f)
        val target = c - (c - offset) * (s / scale)
        scale = s
        moveTo(target, s)
    }

    // A list scroll that hits the top or bottom continues as movement of the zoomed layer,
    // so the very top of page 1 and the bottom of the last page are reachable while zoomed.
    val edgeScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (scale == 1f || available.y == 0f) return Offset.Zero
                val before = offset
                offset = clamp(offset + Offset(0f, available.y * scale), scale)
                return Offset(0f, (offset.y - before.y) / scale)
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at -> zoomTo(if (scale > 1f) 1f else DOUBLE_TAP_PAGE_ZOOM, at) })
            }
            .pointerInput(Unit) {
                // Initial pass: we see each touch before the list does, in screen coordinates.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // After a pinch the list has given up this gesture, so a finger left down is ours too.
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || (pinched && fingers == 1)) {
                            pinched = true
                            // Two fingers are ours alone: zoom and move, the list doesn't scroll on its own.
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) zoomTo(scale * zoom, event.calculateCentroid(useCurrent = true))
                            moveTo(offset + event.calculatePan(), scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (fingers == 1 && scale > 1f) {
                            // One finger while zoomed: the list scrolls vertically, we add the sideways part.
                            val dx = event.calculatePan().x
                            if (dx != 0f) offset = clamp(offset + Offset(dx, 0f), scale)
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // Screen width is plenty at normal size; zoomed in, visible pages re-render sharper
        // (the old bitmap stays up until the new one is ready). ~23 MB per page at 2000 px.
        val baseWidth = constraints.maxWidth.coerceAtMost(1440)
        val widthPx = if (scale > SHARP_ABOVE_ZOOM) (constraints.maxWidth * 2).coerceAtMost(2000) else baseWidth
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                .nestedScroll(edgeScroll),
        ) {
            items(pages.count, key = { it }) { index ->
                PageImage(pages, index, widthPx, Modifier.fillMaxWidth())
            }
        }
    }
}

private const val MAX_PAGE_ZOOM = 5f
private const val DOUBLE_TAP_PAGE_ZOOM = 2.5f
private const val SHARP_ABOVE_ZOOM = 1.3f

@Composable
private fun PageImage(pages: PdfPages, index: Int, widthPx: Int, modifier: Modifier = Modifier) {
    // Keeps the current bitmap until a new one is ready (and if a sharper one can't be made).
    val bitmap by produceState<Bitmap?>(null, pages, index, widthPx) { pages.render(index, widthPx)?.let { value = it } }
    Box(modifier.aspectRatio(595.28f / 841.89f).background(androidx.compose.ui.graphics.Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ReviewLine(review: Review, outdated: Boolean, onRun: () -> Unit, onShow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val status = when (review) {
            is Review.Done -> (if (review.issues.isEmpty()) "AI check: nothing to fix" else "AI check: ${review.issues.size} to look at") +
                if (outdated) " (before your latest edits)" else ""
            is Review.Failed -> "AI check failed"
            Review.Running -> "AI is checking the report…"
            Review.Idle -> "Let AI check the findings before you finalize"
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        when (review) {
            Review.Idle -> TextButton(onClick = onRun) { Text("$AI_MARK Check") }
            Review.Running -> CircularProgressIndicator(Modifier.padding(8.dp).width(20.dp), strokeWidth = 2.dp)
            else -> {
                if (outdated || review is Review.Failed) TextButton(onClick = onRun) { Text("Check again") }
                TextButton(onClick = onShow) { Text("Show") }
            }
        }
    }
}

@Composable
private fun ReviewDialog(
    review: Review,
    findings: Map<String, FindingWithRoom>,
    outdated: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$AI_MARK AI check") },
        text = {
            when (review) {
                Review.Running, Review.Idle -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Text("Reading every finding. This can take up to two minutes.", modifier = Modifier.padding(top = 12.dp))
                }
                is Review.Failed -> Text(review.message, color = MaterialTheme.colorScheme.error)
                is Review.Done -> if (review.issues.none { it.findingId in findings }) {
                    Text("Nothing to fix. The AI can miss things, so read the report once more before you finalize.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        item {
                            Text(
                                (if (outdated) "Checked before your latest edits; tap Check again for a fresh look. " else "") +
                                    "Tap one to open the finding. These are suggestions; you decide what to change.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Findings deleted since the check have nothing left to fix.
                        items(review.issues.filter { it.findingId in findings }) { issue ->
                            val f = findings[issue.findingId]
                            val edited = (f?.finding?.updatedAt ?: 0) > review.basedOn
                            ListItem(
                                overlineContent = { Text(issue.kind.label + if (edited) " · edited since" else "") },
                                headlineContent = {
                                    Text(
                                        listOfNotNull(f?.finding?.title?.ifBlank { null } ?: "Untitled finding", f?.roomName).joinToString(" · "),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = { Text(issue.message) },
                                modifier = Modifier.clickable(enabled = f != null) { onOpen(issue.findingId) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (review is Review.Done || review is Review.Failed) TextButton(onClick = onRetry) { Text("Check again") }
        },
    )
}

@Composable
private fun SummaryDialog(edit: SummaryEdit, aiAvailable: Boolean, vm: ReportViewModel) {
    AlertDialog(
        onDismissRequest = vm::closeSummary,
        // A stray tap beside the box must not throw away a written (or paid-for) summary.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Summary for the client") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Shown first on the report's summary page: the overall condition and what to fix first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (aiAvailable) {
                    OutlinedButton(enabled = !edit.busy, onClick = vm::draftSummary) {
                        if (edit.busy) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                        Text(if (edit.busy) "  Writing…" else "$AI_MARK Draft with AI")
                    }
                    edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    edit.suggestion?.let { s -> SuggestionCard(s, onAccept = vm::acceptSummary, onDiscard = vm::discardSummary) }
                    if (edit.undo != null) TextButton(onClick = vm::undoSummary) { Text("Undo: bring back my text") }
                }
                OutlinedTextField(
                    edit.text, vm::typeSummary,
                    label = { Text("Summary") },
                    supportingText = { Text("${edit.text.length} / $SUMMARY_MAX") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        },
        confirmButton = { TextButton(onClick = vm::saveSummary) { Text("Save") } },
        dismissButton = { TextButton(onClick = vm::closeSummary) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyObservationDialog(
    findings: List<FindingWithRoom>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    onFinalizeAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${findings.size} finding${if (findings.size == 1) "" else "s"} without an observation") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Text(
                        "Site notes aren't printed, so the report shows only the title for these. Tap one to fill it in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(findings, key = { it.finding.id }) { f ->
                    ListItem(
                        headlineContent = { Text(f.finding.title?.ifBlank { null } ?: "Untitled finding", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(f.roomName) },
                        modifier = Modifier.clickable { onOpen(f.finding.id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Go back") } },
        dismissButton = { TextButton(onClick = onFinalizeAnyway) { Text("Finalize anyway") } },
    )
}

@Composable
private fun VersionChips(inspection: InspectionEntity?, reports: List<ReportEntity>, current: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (inspection?.status != InspectionStatus.FINALIZED) {
            FilterChip(selected = current == null, onClick = { onSelect(null) }, label = { Text("Current draft") })
        }
        reports.forEach { r ->
            FilterChip(selected = current == r.id, onClick = { onSelect(r.id) }, label = { Text("v${r.version}") })
        }
    }
}

@Composable
private fun FinalizeActions(nextVersion: Int, enabled: Boolean, onFinalize: () -> Unit) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { confirmed = !confirmed }) {
        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
        Text("I have reviewed the inspection details and findings.", style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = onFinalize, enabled = enabled && confirmed, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Finalize as version $nextVersion")
    }
}

@Composable
private fun VersionActions(report: ReportEntity, latest: ReportEntity?, onShare: () -> Unit) {
    val lines = buildList {
        add("Version ${report.version} · finalized ${formatTime(report.finalizedAt ?: report.generatedAt)}")
        add(report.sharedAt?.let { "Share sheet last opened ${formatTime(it)}" } ?: "Not shared yet")
        if (latest != null && latest.id != report.id) add("Superseded by version ${latest.version}")
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Button(onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Text("Share version ${report.version}", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ProgressLine(label: String, p: ReportProgress) {
    val detail = when {
        p.total == 0 -> ""
        p.stage == ReportProgress.Stage.PHOTOS -> " – photo ${p.done} of ${p.total}"
        else -> " – page ${p.done} of ${p.total}"
    }
    Column(Modifier.width(320.dp)) {
        Text("$label$detail", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun InspectorDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inspector name") },
        text = {
            OutlinedTextField(
                name, { name = it },
                label = { Text("Shown on the report") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val TimeFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

private fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TimeFormat)
