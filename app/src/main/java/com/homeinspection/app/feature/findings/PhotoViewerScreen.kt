package com.homeinspection.app.feature.findings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.homeinspection.app.core.data.InspectionRepository
import com.homeinspection.app.core.data.PhotoRepository
import com.homeinspection.app.core.database.FindingWithRoom
import com.homeinspection.app.core.database.PhotoEntity
import com.homeinspection.app.ui.components.AutoSaver
import com.homeinspection.app.ui.homeInspectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerViewModel(
    private val findingId: String,
    repo: InspectionRepository,
    private val photoRepo: PhotoRepository,
    appScope: CoroutineScope,
) : ViewModel() {
    val photos = photoRepo.observeForFinding(findingId)
        .map { list -> list.map { it to photoRepo.resolve(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Other findings in the same inspection a photo can be moved to. */
    val moveTargets = repo.observeFinding(findingId).filterNotNull()
        .flatMapLatest { repo.observeFindingsForInspection(it.inspectionId) }
        .map { list -> list.filter { it.finding.id != findingId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Captions being typed, ahead of the debounced write. */
    val captionDrafts = mutableStateMapOf<String, String>()
    private val saver = AutoSaver(appScope)

    fun editCaption(photoId: String, caption: String) {
        captionDrafts[photoId] = caption
        saver.schedule(photoId) { photoRepo.updateCaption(photoId, caption) }
    }

    fun move(photoId: String, toFindingId: String) = viewModelScope.launch { photoRepo.moveToFinding(photoId, toFindingId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(findingId: String, startIndex: Int, onBack: () -> Unit) {
    val vm = homeInspectionViewModel {
        PhotoViewerViewModel(findingId, it.container.inspectionRepository, it.container.photoRepository, it.appScope)
    }
    val photos by vm.photos.collectAsStateWithLifecycle()
    val targets by vm.moveTargets.collectAsStateWithLifecycle()
    var moving by remember { mutableStateOf<PhotoEntity?>(null) }

    val list = photos ?: return
    // Last photo moved away: nothing left to show here.
    LaunchedEffect(list.isEmpty()) { if (list.isEmpty()) onBack() }
    if (list.isEmpty()) return

    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, list.lastIndex)) { list.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val current = list.getOrNull(pager.currentPage.coerceAtMost(list.lastIndex))?.first

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (chromeVisible) TopAppBar(
                title = { Text("${pager.currentPage + 1} of ${list.size}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (current != null && targets.isNotEmpty()) TextButton(onClick = { moving = current }) {
                        Text("Move", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0x99000000),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            var zoomed by remember { mutableStateOf(false) }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                key = { list[it].first.id },
                userScrollEnabled = !zoomed, // zoomed in: drags pan the photo, never swipe away
            ) { page ->
                ZoomableImage(
                    list[page].second,
                    isCurrent = page == pager.currentPage,
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomedChange = { if (page == pager.currentPage) zoomed = it },
                )
            }
            if (chromeVisible && current != null) {
                CaptionPanel(
                    photo = current,
                    caption = vm.captionDrafts[current.id] ?: current.caption.orEmpty(),
                    onCaptionChange = { vm.editCaption(current.id, it) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    moving?.let { photo ->
        MoveDialog(
            targets = targets,
            onDismiss = { moving = null },
            onPick = { vm.move(photo.id, it.finding.id); moving = null },
        )
    }
}

/**
 * Pinch to zoom around the fingers, drag to pan while zoomed (never past the photo's edges),
 * double-tap to zoom in at that spot or back out. While zoomed, the page swiper is locked
 * ([onZoomedChange]) so panning never flips to the next photo. The original file is never modified.
 */
@Composable
private fun ZoomableImage(file: File, isCurrent: Boolean, onTap: () -> Unit, onZoomedChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var intrinsic by remember { mutableStateOf(Size.Unspecified) }

    // How far the scaled photo may move: half of how much it overflows the screen, per axis.
    fun clamp(o: Offset, s: Float): Offset {
        if (box == IntSize.Zero) return Offset.Zero
        val fit = if (intrinsic.isSpecified && intrinsic.width > 0 && intrinsic.height > 0) {
            val k = minOf(box.width / intrinsic.width, box.height / intrinsic.height)
            Size(intrinsic.width * k, intrinsic.height * k)
        } else {
            Size(box.width.toFloat(), box.height.toFloat())
        }
        val maxX = ((fit.width * s - box.width) / 2).coerceAtLeast(0f)
        val maxY = ((fit.height * s - box.height) / 2).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun zoomTo(newScale: Float, focus: Offset) {
        val s = newScale.coerceIn(1f, MAX_ZOOM)
        // Keep the photo point under [focus] (relative to the centre) where it is.
        val c = focus - Offset(box.width / 2f, box.height / 2f)
        offset = if (s == 1f) Offset.Zero else clamp(c - (c - offset) * (s / scale), s)
        scale = s
        onZoomedChange(s > 1f)
    }

    // Swiped away: come back at normal size.
    LaunchedEffect(isCurrent) { if (!isCurrent && scale != 1f) zoomTo(1f, Offset.Zero) }

    val arrivals by com.homeinspection.app.core.sync.PhotoArrivals.count.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at -> if (scale > 1f) zoomTo(1f, at) else zoomTo(DOUBLE_TAP_ZOOM, at) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // One finger at normal size belongs to the page swiper; anything else is ours.
                        if (fingers >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f) zoomTo(scale * zoom, event.calculateCentroid(useCurrent = true))
                            if (scale > 1f) offset = clamp(offset + pan, scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        key(arrivals) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { intrinsic = it.painter.intrinsicSize },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        }
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

private val TimeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
private fun CaptionPanel(photo: PhotoEntity, caption: String, onCaptionChange: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Color(0xB3000000)).navigationBarsPadding().imePadding().padding(16.dp),
    ) {
        OutlinedTextField(
            caption, onCaptionChange,
            placeholder = { Text("Add a caption for the report", color = Color(0xFFBDBDBD)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color(0x80FFFFFF),
                cursorColor = Color.White,
            ),
        )
        val captured = Instant.ofEpochMilli(photo.capturedAt).atZone(ZoneId.systemDefault()).format(TimeFormat)
        Text(
            "${photo.width}×${photo.height} · ${"%.1f".format(photo.fileSizeBytes / 1_048_576.0)} MB · $captured · ${photo.uploadState.label}",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFDDDDDD),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MoveDialog(targets: List<FindingWithRoom>, onDismiss: () -> Unit, onPick: (FindingWithRoom) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move photo to…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(targets, key = { it.finding.id }) { t ->
                    ListItem(
                        headlineContent = { Text(t.finding.title ?: t.finding.roughNote.ifBlank { "Untitled finding" }, maxLines = 1) },
                        supportingContent = { Text(t.roomName) },
                        modifier = Modifier.clickable { onPick(t) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
