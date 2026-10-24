package com.homeinspection.app.core.report

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What a finalized version's PDF showed, waiting to go to the server (PUT /v1/reports/{id}/content),
 * which builds that version's Word copy from it. One file per version in files/report-content/
 * (outside files/reports/, so never shareable); the file existing *is* the queue entry, and it is
 * deleted once the server has it. A derived copy like the report images: losing one only means the
 * Word copy falls back to the server's live data.
 */
object ReportContentQueue {
    private const val DIR = "report-content"
    /** A file is due again this long after it was written or last tried (see SyncEngine.contentDueAt). */
    const val RETRY_MS = 5 * 60_000L

    private fun dir(filesDir: File) = File(filesDir, DIR)

    /** Written before the version is recorded, durably, under its final name only when complete. */
    fun write(filesDir: File, reportId: String, json: String) {
        val dir = dir(filesDir).apply { mkdirs() }
        val tmp = File(dir, "$reportId.json.part")
        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(tmp.toPath(), File(dir, "$reportId.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun pending(filesDir: File): List<File> =
        dir(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.lastModified() }.orEmpty()

    fun reportId(file: File) = file.name.removeSuffix(".json")

    fun postpone(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** Startup: half-written files left by a crash. */
    fun cleanupPartials(filesDir: File) {
        dir(filesDir).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }
}
