package com.griha.inspection.core.storage

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A captured original that is fully written, synced to disk and in its final location. */
data class FinalizedOriginal(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

/**
 * Owns original photo files in app-private storage (PRD §5.5, §11.1).
 *
 * Layout under filesDir:
 *   photos/tmp/<inspectionId>/<roomId>/<photoId>.jpg.part   capture in progress / not yet finalized
 *   photos/originals/<inspectionId>/<photoId>.jpg   finalized, immutable
 *   photos/recovered/                    finalized files whose DB row never committed
 *
 * Originals are never modified after finalize(); report-size copies live elsewhere (M3).
 * All functions block on I/O — call them from Dispatchers.IO.
 */
class PhotoStore(
    private val filesDir: File,
    /** Debug builds only: lets testers fake low storage without filling the phone. */
    private val allowFreeSpaceOverride: Boolean = false,
) {

    private val root = File(filesDir, "photos")
    private val tmpDir = File(root, "tmp")
    private val originalsDir = File(root, "originals")
    private val recoveredDir = File(root, "recovered")

    /**
     * Where the camera should write. Nothing here counts as saved. The (empty) file is created
     * immediately at shutter press, so a capture interrupted at any point leaves a trace that
     * startup recovery can report to the inspector — the path records which room it was for.
     */
    @Throws(IOException::class)
    fun newTempFile(inspectionId: String, roomId: String, photoId: String): File {
        val dir = File(File(tmpDir, inspectionId), roomId).apply { mkdirs() }
        return File(dir, "$photoId.jpg.part").apply { createNewFile() }
    }

    /** Removes a temp file for a capture whose failure the inspector has already been shown. */
    fun discardTemp(tempFile: File) {
        tempFile.delete()
    }

    /**
     * Makes a camera temp file durable and moves it into place.
     *
     * Order matters: fsync first (data is on flash, not in a kernel cache), checksum second
     * (we hash exactly the bytes that are durable), atomic rename last (the file appears at its
     * final path either complete or not at all). Only after this returns may the caller commit
     * the DB row — and only after *that* commit may the UI say "saved".
     */
    @Throws(IOException::class)
    fun finalize(tempFile: File, inspectionId: String, photoId: String): FinalizedOriginal {
        if (!tempFile.isFile || tempFile.length() == 0L) {
            throw IOException("Camera produced no data for $photoId")
        }
        FileOutputStream(tempFile, true).use { it.fd.sync() }

        val sha256 = sha256Of(tempFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Captured file for $photoId is not a readable image")
        }

        val target = File(File(originalsDir, inspectionId).apply { mkdirs() }, "$photoId.jpg")
        Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)

        return FinalizedOriginal(
            relativePath = target.relativeTo(filesDir).path,
            sizeBytes = target.length(),
            sha256 = sha256,
            width = bounds.outWidth,
            height = bounds.outHeight,
            mimeType = bounds.outMimeType ?: "image/jpeg",
        )
    }

    fun resolve(relativePath: String): File = File(filesDir, relativePath)

    /** A capture that was in progress when the app died. It was never shown as saved. */
    data class InterruptedCapture(val inspectionId: String, val roomId: String)

    data class RecoveryResult(val interrupted: List<InterruptedCapture>, val recoveredOriginals: Int)

    /**
     * Startup crash recovery. Two leftovers are possible after a process kill:
     * - *.part temp files: capture never finished, the user was never told "saved" → delete,
     *   but report them so the inspector knows to retake.
     * - originals with no DB row: the file is complete but the commit was lost → never delete
     *   evidence; move it to recovered/ so it can be re-attached or exported.
     * Only files last modified before [processStartMillis] are touched, so a capture already
     * running in this process is never mistaken for a leftover.
     */
    fun recover(knownRelativePaths: Set<String>, processStartMillis: Long): RecoveryResult {
        val interrupted = mutableListOf<InterruptedCapture>()
        tmpDir.walkTopDown()
            .filter { it.isFile && it.lastModified() < processStartMillis }
            .forEach { part ->
                val room = part.parentFile
                val inspection = room?.parentFile
                if (inspection?.parentFile == tmpDir) interrupted += InterruptedCapture(inspection.name, room.name)
                part.delete()
            }

        var recovered = 0
        originalsDir.walkTopDown().filter { it.isFile && it.lastModified() < processStartMillis }.forEach { file ->
            if (file.relativeTo(filesDir).path !in knownRelativePaths) {
                recoveredDir.mkdirs()
                if (file.renameTo(File(recoveredDir, file.name))) recovered++
            }
        }
        return RecoveryResult(interrupted, recovered)
    }

    /**
     * Free space for new photos. In debug builds, a number in files/debug_free_bytes (inside
     * this app's private storage only) overrides the real value so the low-storage UI can be
     * tested; release builds never read it.
     */
    fun freeBytes(): Long {
        if (allowFreeSpaceOverride) {
            File(filesDir, "debug_free_bytes").takeIf { it.isFile }
                ?.readText()?.trim()?.toLongOrNull()
                ?.let { return it }
        }
        return filesDir.usableSpace
    }

    companion object {
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
