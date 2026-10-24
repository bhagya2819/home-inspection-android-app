package com.homeinspection.app.core.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Report-size copies of original photos (PRD §11.2, §11.3).
 *
 * Originals are only ever opened for reading. Variants live under the app cache dir in a folder
 * named after the settings ([SPEC]), and each file name carries the original's checksum, so a
 * change of settings or a different original can never pick up a stale copy. Android may clear
 * the cache when storage runs low; variants are simply rebuilt on the next report.
 *
 * One call decodes one image; callers bound how many run at once.
 */
class ReportImages(cacheDir: File) {
    private val dir = File(cacheDir, "report-images/$SPEC")

    /** Returns the report-size JPEG for an original, creating it if needed. Blocking I/O. */
    @Throws(IOException::class)
    fun variantFor(photoId: String, original: File, originalSha256: String): JpegImage {
        val target = File(dir, "${photoId}_${originalSha256.take(16)}.jpg")
        if (target.isFile && target.length() > 0) return describe(target)

        dir.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unreadable photo $photoId")

        // Let the JPEG decoder skip detail we would throw away anyway: the largest power-of-two
        // reduction that still leaves at least LONG_EDGE px. Much faster and smaller than a full decode.
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= LONG_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            original.path,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: throw IOException("Could not decode photo $photoId")

        // Scale to the exact size and apply EXIF orientation in one pass, so the report shows the
        // photo the way the inspector held the phone (the PDF ignores EXIF).
        val matrix = orientationMatrix(original)
        val scale = minOf(1f, LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        matrix.preScale(scale, scale)
        val oriented = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded) decoded.recycle()

        // Unique temp name: a draft render and a finalize may build the same variant at once.
        val tmp = File(dir, "${target.name}.${java.util.UUID.randomUUID()}.part")
        try {
            FileOutputStream(tmp).use { out ->
                if (!oriented.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw IOException("JPEG encode failed")
            }
        } finally {
            oriented.recycle()
        }
        if (!tmp.renameTo(target)) throw IOException("Could not store report image for $photoId")
        return describe(target)
    }

    /** Deletes variants made with older settings and temp files from interrupted runs. */
    fun cleanup() {
        dir.parentFile?.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
        dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun describe(file: File): JpegImage {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) throw IOException("Corrupt report image ${file.name}")
        return JpegImage(file, o.outWidth, o.outHeight)
    }

    private fun orientationMatrix(file: File): Matrix {
        val m = Matrix()
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(270f); m.postScale(-1f, 1f) }
        }
        return m
    }

    companion object {
        /** PRD §11.3 starting values: longest edge 1,600–2,000 px, JPEG quality ~80. */
        const val LONG_EDGE = 1600
        const val QUALITY = 80

        /** Cache key for the settings above; change it whenever they or the pipeline change. */
        const val SPEC = "v1-${LONG_EDGE}px-q$QUALITY"
    }
}
