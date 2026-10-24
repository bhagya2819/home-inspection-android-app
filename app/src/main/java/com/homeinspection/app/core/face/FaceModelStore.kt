package com.homeinspection.app.core.face

import android.content.Context
import com.homeinspection.app.core.network.ApiClient
import com.homeinspection.app.core.storage.PhotoStore
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The two face models on this phone.
 *
 * - YuNet (detector, MIT, 0.2 MB) ships in the APK as an asset; copied to a file once because
 *   OpenCV reads models by path.
 * - SFace (recognizer, Apache 2.0, compact int8 version, 9.9 MB) is downloaded once from the Home Inspection server after
 *   sign-in, then every face check works offline. Its SHA-256 is pinned here: a file that doesn't
 *   match (corrupt download, swapped on the server) is never used.
 *
 * Both are OpenCV Zoo files, unmodified: opencv_zoo/models/face_detection_yunet/
 * face_detection_yunet_2023mar.onnx and face_recognition_sface/face_recognition_sface_2021dec_int8.onnx. The compact
 * model (99.32 % vs 99.40 % on LFW, a quarter of the download) was the user's choice, 2026-10-21.
 */
class FaceModelStore(private val context: Context, private val api: ApiClient, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "face")
    private val recognizer = File(dir, "face_recognition_sface_2021dec_int8.onnx")

    init {
        // The full-size model used before 0.8.0's switch to the compact one: 39 MB nobody reads any more.
        File(dir, "face_recognition_sface_2021dec.onnx").delete()
    }

    /** Bytes so far, full size and speed while a download runs; null otherwise. */
    data class Progress(val done: Long, val total: Long, val bytesPerSecond: Long) {
        val fraction get() = if (total > 0) done.toFloat() / total else 0f
        val secondsLeft get() = if (bytesPerSecond > 0) (total - done) / bytesPerSecond else -1
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress = _progress.asStateFlow()
    private var running: Deferred<Unit>? = null

    /** Detector model file (copied out of the APK on first use). */
    fun detectorFile(): File {
        val file = File(dir, DETECTOR_ASSET.substringAfterLast('/'))
        synchronized(this) {
            if (!file.isFile || file.length() != DETECTOR_SIZE) {
                dir.mkdirs()
                val tmp = File(dir, file.name + ".part")
                context.assets.open(DETECTOR_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            }
        }
        return file
    }

    /** The recognizer, only if it is fully downloaded and verified. */
    fun recognizerFile(): File? = recognizer.takeIf { it.isFile && it.length() == RECOGNIZER_SIZE }

    val hasRecognizer get() = recognizerFile() != null

    /**
     * Downloads the recognizer if missing (resuming a partial file) and waits for it. Callers that
     * stop waiting don't stop the download. Throws IOException when offline or the file is damaged.
     */
    suspend fun ensureRecognizer(): Boolean {
        if (hasRecognizer) return true
        val job = synchronized(this) {
            running?.takeIf { it.isActive } ?: scope.async(Dispatchers.IO) { download() }.also { running = it }
        }
        job.await()
        return true
    }

    private suspend fun download() {
        try {
            dir.mkdirs()
            val part = File(dir, recognizer.name + ".part")
            if (part.length() > RECOGNIZER_SIZE) part.delete()
            val startBytes = part.length()
            val start = SystemClock.elapsedRealtime()
            api.download("/v1/face/model", part) { done, total ->
                val secs = (SystemClock.elapsedRealtime() - start) / 1000.0
                val speed = if (secs > 0.5) ((done - startBytes) / secs).toLong() else 0L
                _progress.value = Progress(done, if (total > 0) total else RECOGNIZER_SIZE, speed)
            }
            if (part.length() != RECOGNIZER_SIZE || !PhotoStore.sha256Of(part).equals(RECOGNIZER_SHA256, ignoreCase = true)) {
                part.delete()
                throw IOException("The face model download was damaged. Try again.")
            }
            Files.move(part.toPath(), recognizer.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            _progress.value = null
        }
    }

    companion object {
        const val MODEL_NAME = "sface-2021dec-int8"
        const val RECOGNIZER_SIZE = 9_896_933L
        const val RECOGNIZER_SHA256 = "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a"
        private const val DETECTOR_ASSET = "face/face_detection_yunet_2023mar.onnx"
        private const val DETECTOR_SIZE = 232_589L
        const val RECOGNIZER_MB = 10
    }
}
