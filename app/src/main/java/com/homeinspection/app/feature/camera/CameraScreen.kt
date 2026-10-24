package com.homeinspection.app.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homeinspection.app.core.data.PhotoRepository
import com.homeinspection.app.core.data.PhotoTarget
import com.homeinspection.app.core.model.FindingKind
import com.homeinspection.app.core.storage.StorageCheck
import com.homeinspection.app.core.storage.StoragePolicy
import com.homeinspection.app.ui.components.PhotoThumb
import com.homeinspection.app.ui.homeInspectionViewModel
import com.homeinspection.app.ui.theme.HomeInspectionWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

data class CaptureUiState(
    /** Shots taken but not yet durably saved + committed. */
    val saving: Int = 0,
    val savedThisSession: Int = 0,
    val error: String? = null,
    val storage: StorageCheck = StorageCheck.Ok,
)

class CameraViewModel(
    inspectionId: String,
    roomId: String,
    private val existingFindingId: String?,
    newKind: FindingKind,
    private val photos: PhotoRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    /** A new finding gets its id now but is only written to the DB with its first saved photo. */
    private val target = PhotoTarget(inspectionId, roomId, existingFindingId ?: UUID.randomUUID().toString(), newKind)

    /** Exists in the DB once [captured] is non-empty, so the photo viewer can open it. */
    val findingId get() = target.findingId

    /** Where "Done" should lead: a brand-new finding that now has photos opens in the editor (room common photos have nothing to describe). */
    fun findingToOpenOnDone(): String? =
        if (existingFindingId == null && target.kind == FindingKind.ISSUE && _ui.value.savedThisSession > 0) target.findingId else null

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = _surfaceRequest.asStateFlow()

    private val _ui = MutableStateFlow(CaptureUiState(storage = StoragePolicy.check(photos.freeBytes())))
    val ui = _ui.asStateFlow()

    val captured = photos.observeForFinding(target.findingId)
        .map { list -> list.map { it to photos.resolve(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val preview = Preview.Builder().build().apply {
        setSurfaceProvider { _surfaceRequest.value = it }
    }

    // Highest resolution the sensor offers, quality over speed (PRD §5.5).
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                // Sizes slower than 20 fps (often the sensor's full resolution) are hidden unless
                // we opt in. Inspection stills don't need burst speed, so we always opt in.
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .build()
        )
        .build()

    suspend fun bindToCamera(context: Context, owner: LifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        // Only our own use cases: the provider is app-wide, and another camera screen may be
        // binding during a navigation transition (unbindAll would switch its camera off).
        provider.unbind(preview, imageCapture)
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        try {
            awaitCancellation()
        } finally {
            provider.unbind(preview, imageCapture)
        }
    }

    fun capture(displayRotation: Int) {
        val storage = StoragePolicy.check(photos.freeBytes())
        _ui.update { it.copy(storage = storage, error = null) }
        if (storage is StorageCheck.Full) return

        val capturedAt = System.currentTimeMillis()
        val (photoId, tempFile) = try {
            photos.newCapture(target)
        } catch (e: IOException) {
            _ui.update { it.copy(error = "Could not start the photo. Check storage and retry.") }
            return
        }
        imageCapture.targetRotation = displayRotation
        _ui.update { it.copy(saving = it.saving + 1) }

        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(tempFile).build(),
            Dispatchers.IO.asExecutor(),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // CameraX finished writing the temp file. It is NOT saved until commitCapture returns.
                    appScope.launch {
                        val result = runCatching { photos.commitCapture(photoId, tempFile, target, capturedAt) }
                        if (result.isFailure) photos.discardCapture(tempFile)
                        _ui.update {
                            it.copy(
                                saving = it.saving - 1,
                                savedThisSession = it.savedThisSession + if (result.isSuccess) 1 else 0,
                                error = result.exceptionOrNull()?.let { "Photo NOT saved. Please retake it." },
                            )
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    photos.discardCapture(tempFile)
                    _ui.update { it.copy(saving = it.saving - 1, error = "Photo NOT saved. Please retake it.") }
                }
            },
        )
    }
}

@Composable
fun CameraScreen(
    inspectionId: String,
    roomId: String,
    existingFindingId: String?,
    /** What a new finding becomes when [existingFindingId] is null. */
    newKind: FindingKind,
    onDone: (openFindingId: String?) -> Unit,
    onOpenPhoto: (findingId: String, index: Int) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera access is needed to photograph findings.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                Text("Allow camera")
            }
            TextButton(onClick = { onDone(null) }) { Text("Back") }
        }
        return
    }

    val vm = homeInspectionViewModel {
        CameraViewModel(inspectionId, roomId, existingFindingId, newKind, it.container.photoRepository, it.appScope)
    }
    val surfaceRequest by vm.surfaceRequest.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val captured by vm.captured.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    LaunchedEffect(lifecycleOwner) { vm.bindToCamera(context.applicationContext, lifecycleOwner) }
    BackHandler { onDone(vm.findingToOpenOnDone()) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }

        StatusBanner(ui, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp))

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(captured, key = { _, it -> it.first.id }) { index, (photo, file) ->
                    // The camera unbinds while the viewer is open and rebinds on return. A shot still being
                    // captured would fail on unbind, so the viewer opens only once every photo is saved.
                    PhotoThumb(
                        file, photo.uploadState, size = 56.dp,
                        modifier = Modifier.clickable(enabled = ui.saving == 0, onClickLabel = "View photo") { onOpenPhoto(vm.findingId, index) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${captured.size} photos", color = Color.White, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .clickable(enabled = ui.storage !is StorageCheck.Full, onClickLabel = "Take photo") {
                            vm.capture(view.display.rotation)
                        }
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .background(if (ui.storage is StorageCheck.Full) Color.Gray else Color.White, CircleShape),
                )
                // Leaving is always safe: in-flight saves finish in appScope.
                TextButton(onClick = { onDone(vm.findingToOpenOnDone()) }, modifier = Modifier.weight(1f)) {
                    Text("Done", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(ui: CaptureUiState, modifier: Modifier) {
    val (text, color) = when {
        ui.error != null -> ui.error to MaterialTheme.colorScheme.error
        ui.storage is StorageCheck.Full -> "Phone storage full. Free space to take more photos." to MaterialTheme.colorScheme.error
        ui.saving > 0 -> "Saving ${ui.saving}…" to Color.DarkGray
        ui.storage is StorageCheck.Low -> "Storage is low. Sync and free space soon." to HomeInspectionWarn
        ui.savedThisSession > 0 -> "Saved on this phone ✓" to MaterialTheme.colorScheme.primary
        else -> return
    }
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}
