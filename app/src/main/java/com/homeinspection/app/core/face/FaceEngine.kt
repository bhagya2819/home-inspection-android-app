package com.homeinspection.app.core.face

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One face found by YuNet, in the pixel coordinates of the bitmap it was found in.
 * [row] is YuNet's raw 15 numbers (box, 5 landmarks, score); SFace's alignment needs it as-is.
 */
class DetectedFace(internal val row: FloatArray) {
    val x get() = row[0]
    val y get() = row[1]
    val width get() = row[2]
    val height get() = row[3]
    val score get() = row[14]

    // Landmarks: the person's right eye (left in the image), left eye, nose tip, mouth corners.
    private val eyeMidX get() = (row[4] + row[6]) / 2
    private val eyeDistance get() = hypot(row[6] - row[4], row[7] - row[5])

    /**
     * Head turn, roughly: how far the nose sits from the point between the eyes, in eye-distances.
     * About 0 looking straight at the camera; grows past ±0.3 as the head turns to a side.
     * Sign depends on direction and camera mirroring, so callers use the magnitude.
     */
    val yaw: Float get() = if (eyeDistance < 1f) 0f else (row[8] - eyeMidX) / eyeDistance

    val isFrontal get() = abs(yaw) < FRONTAL_YAW

    companion object {
        const val FRONTAL_YAW = 0.12f
        const val TURNED_YAW = 0.30f
    }
}

/**
 * On-device face detection (YuNet, bundled in the APK) and recognition (SFace, downloaded once from
 * the Home Inspection server — see [FaceModelStore]). OpenCV's reference pipeline: detect → align by the 5
 * landmarks → 128-number embedding. Recognition accuracy depends on that exact alignment, which is
 * why we use OpenCV's own instead of re-implementing it.
 *
 * OpenCV objects are not thread-safe, so every call runs on one dedicated thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceEngine(private val detectorModel: File, private val recognizerModel: () -> File?) {
    private val thread = Dispatchers.Default.limitedParallelism(1)
    private var detector: FaceDetectorYN? = null
    private var recognizer: FaceRecognizerSF? = null

    private fun ensureDetector(): FaceDetectorYN {
        detector?.let { return it }
        check(loaded) { "OpenCV native library failed to load" }
        return FaceDetectorYN.create(detectorModel.path, "", Size(320.0, 320.0), MIN_DETECTION_SCORE, 0.3f, 5000)
            .also { detector = it }
    }

    /** Faces in [bitmap], largest first. */
    suspend fun detect(bitmap: Bitmap): List<DetectedFace> = withContext(thread) {
        val det = ensureDetector()
        val bgr = toBgr(bitmap)
        val faces = Mat()
        try {
            det.inputSize = Size(bgr.cols().toDouble(), bgr.rows().toDouble())
            det.detect(bgr, faces)
            (0 until faces.rows()).map { i ->
                FloatArray(15).also { faces.get(i, 0, it) }.let(::DetectedFace)
            }.sortedByDescending { it.width * it.height }
        } finally {
            bgr.release()
            faces.release()
        }
    }

    /** True once the recognition model is on this phone. */
    fun canRecognize(): Boolean = recognizerModel()?.isFile == true

    /**
     * SFace embedding of [face] in [bitmap], scaled to length 1 so that comparing two is a plain dot
     * product ([similarity]). Null if the recognition model isn't on this phone yet.
     */
    suspend fun embed(bitmap: Bitmap, face: DetectedFace): FloatArray? = withContext(thread) {
        val rec = recognizer ?: run {
            val model = recognizerModel()?.takeIf { it.isFile } ?: return@withContext null
            check(loaded) { "OpenCV native library failed to load" }
            FaceRecognizerSF.create(model.path, "").also { recognizer = it }
        }
        val bgr = toBgr(bitmap)
        val box = Mat(1, 15, org.opencv.core.CvType.CV_32F).apply { put(0, 0, face.row) }
        val aligned = Mat()
        val feature = Mat()
        try {
            rec.alignCrop(bgr, box, aligned)
            rec.feature(aligned, feature)
            val v = FloatArray(feature.total().toInt()).also { feature.get(0, 0, it) }
            normalize(v)
        } finally {
            bgr.release(); box.release(); aligned.release(); feature.release()
        }
    }

    private fun toBgr(bitmap: Bitmap): Mat {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    companion object {
        /** YuNet's own confidence; below this a "face" is too often a pattern on a wall. */
        const val MIN_DETECTION_SCORE = 0.85f

        private val loaded: Boolean by lazy { OpenCVLoader.initLocal() }

        fun normalize(v: FloatArray): FloatArray {
            val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
            if (n > 0f) for (i in v.indices) v[i] /= n
            return v
        }

        /** Cosine similarity of two length-1 embeddings: 1 = identical, around 0 = unrelated. */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "embedding sizes differ" }
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }
    }
}
