package com.griha.inspection.feature.attendance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.griha.inspection.core.attendance.AttendanceRepository
import com.griha.inspection.core.attendance.FaceOutcome
import com.griha.inspection.core.attendance.LocationFix
import com.griha.inspection.core.attendance.PlaceFix
import com.griha.inspection.core.database.AttendanceEntity
import com.griha.inspection.core.face.DetectedFace
import com.griha.inspection.core.face.FaceEngine
import com.griha.inspection.core.face.FaceMatcher
import com.griha.inspection.core.face.FaceModelStore
import com.griha.inspection.core.face.FaceProfile
import com.griha.inspection.core.face.FaceRepository
import com.griha.inspection.core.face.FaceResult
import com.griha.inspection.core.storage.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.abs

enum class FaceMode(val title: String) {
    ENROLL("Set up face check-in"),
    CHECK_IN("Check in"),
    CHECK_OUT("Check out"),
}

/** Where the flow is. Each step has one instruction for the engineer. */
sealed interface FaceStep {
    data class Preparing(val text: String) : FaceStep
    /** The face model is downloading (once per phone); the screen shows [FaceCaptureViewModel.download]. */
    data object DownloadingModel : FaceStep
    /** Set-up opened while one is already approved or waiting: say so instead of starting over. */
    data class AlreadySetUp(val approved: Boolean, val pending: Boolean) : FaceStep
    data object FindFace : FaceStep
    data object TurnHead : FaceStep
    data object LookBack : FaceStep
    data class Collecting(val shots: Int, val of: Int) : FaceStep
    data class Working(val text: String) : FaceStep
    /** Check-in/out face check finished without a clean match: the engineer chooses. */
    data class Decide(val result: FaceResult, val score: Float?, val canProceed: Boolean, val setUpPending: Boolean = false) : FaceStep
    data class Done(val message: String) : FaceStep
    data class Failed(val message: String, val canRetry: Boolean = true) : FaceStep
}

data class FaceUi(
    val step: FaceStep = FaceStep.Preparing("Starting the camera…"),
    /** Short correction under the instruction ("Come closer", "Only you in the picture"). */
    val hint: String? = null,
    /** A face is in the oval and usable right now (oval turns green). */
    val faceOk: Boolean = false,
    val place: PlaceFix? = null,
    val locating: Boolean = false,
    val locationNote: String? = null,
    val attempts: Int = 0,
)

/**
 * Face check flow for enrolment and check-in/out.
 *
 * Every attempt proves a live person first (look straight → turn the head → look back; a printed
 * photo can't turn), then:
 * - ENROLL: collects [ENROLL_SHOTS] embeddings a moment apart, checks they agree, uploads them
 *   with a reference photo for the admin to approve.
 * - CHECK_IN/OUT: embeds the final frontal frame, compares it with the approved enrolment and saves
 *   that very frame as the selfie, with the location.
 */
class FaceCaptureViewModel(
    val mode: FaceMode,
    private val engine: FaceEngine,
    private val models: FaceModelStore,
    private val faces: FaceRepository,
    private val attendance: AttendanceRepository,
    private val location: LocationFix,
    private val cacheDir: File,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _ui = MutableStateFlow(FaceUi())
    val ui = _ui.asStateFlow()

    /** Face model download progress (shared by every screen; null when not downloading). */
    val download = models.progress

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = _surfaceRequest.asStateFlow()

    private val preview = Preview.Builder().build().apply { setSurfaceProvider { _surfaceRequest.value = it } }
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
        )
        .build()

    private var profile: FaceProfile? = null
    @Volatile private var busy = false
    private var stableFrames = 0
    private var lastShotAt = 0L
    private val shots = ArrayList<FloatArray>()
    private var referenceFrame: Bitmap? = null
    private var locationJob: Job? = null

    /** elapsedRealtime of the last camera frame; a watchdog says so if frames stop coming. */
    @Volatile private var lastFrameAt = SystemClock.elapsedRealtime()

    init {
        viewModelScope.launch { prepare() }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val step = _ui.value.step
                val waitingForFace = step is FaceStep.FindFace || step is FaceStep.TurnHead || step is FaceStep.LookBack || step is FaceStep.Collecting
                if (waitingForFace && SystemClock.elapsedRealtime() - lastFrameAt > NO_FRAMES_MS) {
                    _ui.update { it.copy(hint = "The camera isn't sending pictures. Close this screen and open it again.", faceOk = false) }
                }
            }
        }
    }

    // ---- Setup -----------------------------------------------------------------------------------

    private suspend fun prepare() {
        if (mode == FaceMode.ENROLL) {
            profile = faces.current()
            runCatching { faces.refresh() }.getOrNull()?.let { profile = it }
            val p = profile
            if (p != null && (p.approved || p.pendingId != null)) {
                _ui.update { it.copy(step = FaceStep.AlreadySetUp(approved = p.approved, pending = p.pendingId != null)) }
                return
            }
            startEnrollment()
        } else {
            startLocation()
            profile = faces.current()
            // Fresh state when online (approval may have just happened); the cached one otherwise.
            runCatching { faces.refresh() }.getOrNull()?.let { profile = it }
            if (profile?.approved == true && !models.hasRecognizer) {
                _ui.update { it.copy(step = FaceStep.DownloadingModel) }
                runCatching { models.ensureRecognizer() } // offline: check in without a match, flagged
            }
            restart()
        }
    }

    /** "Set up again" from the status page, or a first set-up: model first, then the camera steps. */
    fun startEnrollment() {
        viewModelScope.launch {
            if (!models.hasRecognizer) {
                _ui.update { it.copy(step = FaceStep.DownloadingModel) }
                try {
                    models.ensureRecognizer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail("Couldn't download the face model. Check the internet and try again. (${e.message ?: "error"})")
                    return@launch
                }
            }
            restart()
        }
    }

    fun startLocation() {
        if (mode == FaceMode.ENROLL || locationJob?.isActive == true) return
        if (!location.hasPermission()) {
            _ui.update { it.copy(locationNote = "Location not allowed: the check-in is saved without a place.") }
            return
        }
        if (!location.isEnabled()) {
            _ui.update { it.copy(locationNote = "Location is off. Turn it on so your admin sees where you checked in.") }
        }
        _ui.update { it.copy(locating = true) }
        locationJob = viewModelScope.launch {
            // Keep the most accurate fix; stop early once it is good enough.
            withTimeoutOrNull(LOCATION_WINDOW_MS) {
                location.updates().first { fix ->
                    val best = _ui.value.place
                    if (best == null || fix.accuracyM < best.accuracyM) _ui.update { it.copy(place = fix, locationNote = null) }
                    fix.accuracyM <= GOOD_ACCURACY_M
                }
            }
        }.also { job -> job.invokeOnCompletion { _ui.update { it.copy(locating = false) } } }
    }

    suspend fun bindToCamera(context: Context, owner: LifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        analysis.setAnalyzer(Dispatchers.Default.asExecutor(), ::onFrame)
        // Only ever our own use cases: the provider is app-wide, and during a screen transition the
        // next face screen may already have bound its camera (unbindAll here once blacked it out).
        provider.unbind(preview, analysis)
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        try {
            awaitCancellation()
        } finally {
            analysis.clearAnalyzer()
            provider.unbind(preview, analysis)
        }
    }

    /** Back to the start of a face check (after a mismatch, or "Try again"). */
    fun restart() {
        lastFrameAt = SystemClock.elapsedRealtime()
        stableFrames = 0
        shots.clear()
        referenceFrame = null
        _ui.update { it.copy(step = FaceStep.FindFace, hint = null, faceOk = false) }
    }

    // ---- Frames ----------------------------------------------------------------------------------

    private fun onFrame(image: ImageProxy) {
        lastFrameAt = SystemClock.elapsedRealtime()
        val step = _ui.value.step
        val live = step is FaceStep.FindFace || step is FaceStep.TurnHead || step is FaceStep.LookBack || step is FaceStep.Collecting
        if (busy || !live) {
            image.close()
            return
        }
        busy = true
        val frame = try {
            upright(image)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "camera frame unusable", e)
            busy = false
            return
        } finally {
            image.close()
        }
        val at = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                handle(frame, at, elapsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "face check failed", e)
                fail("Face check error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun handle(frame: Bitmap, at: Long, elapsed: Long) {
        val found = engine.detect(frame)
        val face = found.firstOrNull()
        val hint = when {
            face == null -> "Look at the camera"
            found.size > 1 && found[1].width * found[1].height > 0.3f * face.width * face.height -> "Only you in the picture, please"
            face.width < frame.width * MIN_FACE_FRACTION -> "Come a little closer"
            else -> null
        }
        _ui.update { it.copy(hint = hint, faceOk = hint == null) }
        if (hint != null || face == null) {
            stableFrames = 0
            return
        }
        when (val step = _ui.value.step) {
            FaceStep.FindFace -> advanceWhen(face.isFrontal, need = 3) { _ui.update { it.copy(step = FaceStep.TurnHead) } }
            FaceStep.TurnHead -> advanceWhen(abs(face.yaw) >= DetectedFace.TURNED_YAW, need = 2) { _ui.update { it.copy(step = FaceStep.LookBack) } }
            FaceStep.LookBack -> advanceWhen(face.isFrontal, need = 3) {
                if (mode == FaceMode.ENROLL) {
                    _ui.update { it.copy(step = FaceStep.Collecting(0, ENROLL_SHOTS)) }
                } else {
                    checkFace(frame, face, at, elapsed)
                }
            }
            is FaceStep.Collecting -> collect(frame, face, step)
            else -> Unit
        }
    }

    private inline fun advanceWhen(condition: Boolean, need: Int, next: () -> Unit) {
        stableFrames = if (condition) stableFrames + 1 else 0
        if (stableFrames >= need) {
            stableFrames = 0
            next()
        }
    }

    // ---- Enrolment -------------------------------------------------------------------------------

    private suspend fun collect(frame: Bitmap, face: DetectedFace, step: FaceStep.Collecting) {
        val now = SystemClock.elapsedRealtime()
        if (abs(face.yaw) > ENROLL_MAX_YAW || now - lastShotAt < ENROLL_SPACING_MS) return
        val v = engine.embed(frame, face) ?: return fail("The face model is missing. Connect to the internet and try again.")
        lastShotAt = now
        if (shots.isEmpty()) referenceFrame = frame
        shots += v
        if (shots.size < step.of) {
            _ui.update { it.copy(step = FaceStep.Collecting(shots.size, step.of)) }
            return
        }
        if (!FaceMatcher.consistent(shots)) {
            restart()
            _ui.update { it.copy(hint = "Those shots didn't look alike. Keep only your face in the oval and try again.") }
            return
        }
        sendEnrollment(shots.toList(), referenceFrame ?: frame)
    }

    private fun sendEnrollment(embeddings: List<FloatArray>, photo: Bitmap) {
        _ui.update { it.copy(step = FaceStep.Working("Sending to your admin…")) }
        // App scope: an upload in flight finishes even if the screen closes.
        appScope.launch {
            val id = UUID.randomUUID().toString()
            val file = File(cacheDir, "enrol-$id.jpg")
            try {
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { photo.compress(Bitmap.CompressFormat.JPEG, AttendanceRepository.SELFIE_QUALITY, it) }
                }
                faces.enroll(id, embeddings, file, withContext(Dispatchers.IO) { PhotoStore.sha256Of(file) })
                _ui.update {
                    it.copy(step = FaceStep.Done("Sent to your admin for approval. Until it's approved, you can still check in; it's marked \"face not set up\"."))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                fail("No connection. Nothing was sent; try again with internet.")
            } catch (e: Exception) {
                fail("Could not send: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                file.delete()
            }
        }
    }

    // ---- Check-in / check-out --------------------------------------------------------------------

    private var pending: Pending? = null

    private class Pending(val frame: Bitmap, val outcome: FaceOutcome, val at: Long, val elapsed: Long)

    private suspend fun checkFace(frame: Bitmap, face: DetectedFace, at: Long, elapsed: Long) {
        _ui.update { it.copy(step = FaceStep.Working("Checking your face…")) }
        val p = profile
        val probe = if (p?.approved == true) engine.embed(frame, face) else null
        val (result, score) = when {
            p?.approved != true -> FaceResult.NOT_ENROLLED to null
            probe == null -> FaceResult.UNAVAILABLE to null
            else -> FaceMatcher.bestScore(probe, p.embeddings).let { FaceMatcher.classify(it) to it }
        }
        val outcome = FaceOutcome(result.name, score, p?.approvedId, livenessPassed = true)
        val attempts = _ui.value.attempts + 1
        _ui.update { it.copy(attempts = attempts) }
        if (result == FaceResult.MATCH) {
            save(Pending(frame, outcome, at, elapsed))
            return
        }
        pending = Pending(frame, outcome, at, elapsed)
        val canProceed = when (result) {
            FaceResult.NO_MATCH -> attempts >= MAX_ATTEMPTS_BEFORE_OVERRIDE
            else -> true // weak match, not set up, or model unavailable: never block work
        }
        _ui.update { it.copy(step = FaceStep.Decide(result, score, canProceed, setUpPending = p?.pendingId != null)) }
    }

    /** "Check in anyway": saves the last attempt as it is; the admin sees its badge. */
    fun proceedAnyway() {
        pending?.let { save(it) }
    }

    private fun save(p: Pending) {
        _ui.update { it.copy(step = FaceStep.Working("Saving…")) }
        val kind = if (mode == FaceMode.CHECK_IN) AttendanceEntity.KIND_IN else AttendanceEntity.KIND_OUT
        appScope.launch {
            // Give a still-running location fix a few seconds before saving without one.
            if (_ui.value.place == null && _ui.value.locating) {
                withTimeoutOrNull(SAVE_WAIT_FOR_LOCATION_MS) { _ui.first { it.place != null || !it.locating } }
            }
            try {
                val row = attendance.save(kind, p.frame, _ui.value.place, p.outcome, p.at, p.elapsed)
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.clientAt))
                _ui.update { it.copy(step = FaceStep.Done(if (row.isIn) "Checked in at $time" else "Checked out at $time")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("NOT saved: ${e.message ?: e.javaClass.simpleName}. Please try again.")
            }
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(step = FaceStep.Failed(message), faceOk = false) }
    }

    /** Camera frame turned upright and scaled so its long side is at most [FRAME_LONG_SIDE] px. */
    private fun upright(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val scale = minOf(1f, FRAME_LONG_SIDE.toFloat() / maxOf(src.width, src.height))
        val m = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(scale, scale)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private companion object {
        const val TAG = "GrihaFace"
        const val NO_FRAMES_MS = 4_000L
        const val FRAME_LONG_SIDE = 960
        const val MIN_FACE_FRACTION = 0.22f
        const val ENROLL_SHOTS = 5
        const val ENROLL_SPACING_MS = 400L
        const val ENROLL_MAX_YAW = 0.25f
        const val MAX_ATTEMPTS_BEFORE_OVERRIDE = 3
        const val LOCATION_WINDOW_MS = 45_000L
        const val GOOD_ACCURACY_M = 20f
        const val SAVE_WAIT_FOR_LOCATION_MS = 8_000L
    }
}
