package com.griha.inspection.core.admin

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class DownloadProgress(val state: State, val downloaded: Long = 0, val total: Long = 0, val reason: Int = 0) {
    enum class State { NONE, RUNNING, PAUSED, DONE, FAILED }
}

sealed interface VerifyResult {
    data class Verified(val export: ExportInfo) : VerifyResult
    data class Failed(val message: String) : VerifyResult
}

/**
 * Downloads export zips with the system DownloadManager (resumes after network loss or an app kill,
 * shows a notification, costs no APK size) into Downloads/Griha, then verifies them in place:
 * every entry is re-hashed and compared with manifest.csv, and the manifest's own SHA-256 with the
 * server's. Only a verified export may be removed from the server.
 */
class ExportDownloads(
    private val context: Context,
    private val admin: AdminRepository,
    private val prefs: SharedPreferences,
) {
    private val dm get() = context.getSystemService(DownloadManager::class.java)

    private fun key(exportId: Long) = "download_$exportId"

    private fun downloadId(exportId: Long): Long? = prefs.getLong(key(exportId), -1).takeIf { it >= 0 }

    /** Where the user finds the file (to copy it to Google Drive and a laptop). */
    fun location(export: ExportInfo): String =
        if (Build.VERSION.SDK_INT >= 29) "Downloads/Griha/${export.fileName}"
        else "Android/data/${context.packageName}/files/Download/${export.fileName}"

    suspend fun start(export: ExportInfo) {
        val url = admin.exportLink(export.id)
        withContext(Dispatchers.IO) {
            // A failed or corrupt earlier attempt is replaced. A verified download is never removed here.
            downloadId(export.id)?.let { old -> if (export.verifiedAt == null) dm.remove(old) }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Griha export ${export.label}")
                .setDescription(export.fileName)
                .setMimeType("application/zip")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Griha/${export.fileName}")
            } else {
                // Public Downloads needs a storage permission before Android 10; app storage doesn't.
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, export.fileName)
            }
            prefs.edit().putLong(key(export.id), dm.enqueue(request)).apply()
        }
    }

    fun progress(exportId: Long): DownloadProgress {
        val id = downloadId(exportId) ?: return DownloadProgress(DownloadProgress.State.NONE)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return DownloadProgress(DownloadProgress.State.NONE)
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val state = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress.State.DONE
                DownloadManager.STATUS_FAILED -> DownloadProgress.State.FAILED
                DownloadManager.STATUS_PAUSED -> DownloadProgress.State.PAUSED
                else -> DownloadProgress.State.RUNNING
            }
            return DownloadProgress(state, done, total, reason)
        }
        return DownloadProgress(DownloadProgress.State.NONE)
    }

    /**
     * Re-hashes every entry of the downloaded zip. On success tells the server, which from then on
     * lets phones clean up these photos and lets an admin remove them from the server.
     */
    suspend fun verify(export: ExportInfo, onProgress: (Long, Long) -> Unit): VerifyResult = withContext(Dispatchers.IO) {
        val id = downloadId(export.id) ?: return@withContext VerifyResult.Failed("Download this export first.")
        val pfd = try {
            dm.openDownloadedFile(id)
        } catch (_: IOException) {
            return@withContext VerifyResult.Failed("The downloaded file is gone. Download it again.")
        }
        val fileSize = pfd.statSize
        if (fileSize != export.zipBytes) {
            pfd.close()
            return@withContext VerifyResult.Failed("The file has $fileSize bytes instead of ${export.zipBytes}. Download it again.")
        }
        val entries = HashMap<String, Pair<String, Long>>() // name → (sha256, size)
        var manifest: ByteArray? = null
        try {
            val counting = CountingStream(ParcelFileDescriptor.AutoCloseInputStream(pfd).buffered(256 * 1024)) { onProgress(it, export.zipBytes) }
            ZipInputStream(counting).use { zip ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    ensureActive()
                    val md = MessageDigest.getInstance("SHA-256")
                    val keep = if (entry.name == "manifest.csv") java.io.ByteArrayOutputStream() else null
                    var size = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        md.update(buffer, 0, n)
                        keep?.write(buffer, 0, n)
                        size += n
                    }
                    if (entries.put(entry.name, hex(md.digest()) to size) != null) {
                        return@withContext VerifyResult.Failed("The zip lists ${entry.name} twice. Download it again.")
                    }
                    keep?.let { manifest = it.toByteArray() }
                }
            }
        } catch (e: ZipException) {
            // Includes a CRC-32 mismatch detected while reading an entry.
            return@withContext VerifyResult.Failed("The zip is damaged (${e.message}). Download it again.")
        } catch (e: IOException) {
            return@withContext VerifyResult.Failed("Couldn't read the zip: ${e.message}")
        }
        val manifestBytes = manifest ?: return@withContext VerifyResult.Failed("manifest.csv is missing from the zip.")
        val manifestSha = hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes))
        if (!manifestSha.equals(export.manifestSha256, ignoreCase = true)) {
            return@withContext VerifyResult.Failed("manifest.csv differs from the server's. Download it again.")
        }
        val rows = manifestBytes.decodeToString().lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',') }.toList()
        val listed = HashSet<String>()
        for (row in rows) {
            if (row.size < 3) return@withContext VerifyResult.Failed("manifest.csv is malformed.")
            val (path, sha, size) = row
            listed += path
            val got = entries[path] ?: return@withContext VerifyResult.Failed("$path is missing from the zip.")
            if (!got.first.equals(sha, ignoreCase = true) || got.second != size.toLongOrNull()) {
                return@withContext VerifyResult.Failed("$path doesn't match its checksum. Download it again.")
            }
        }
        val unexpected = entries.keys - listed - "manifest.csv"
        if (unexpected.isNotEmpty()) return@withContext VerifyResult.Failed("Unexpected file in the zip: ${unexpected.first()}")
        if (entries.size != export.entryCount) {
            return@withContext VerifyResult.Failed("The zip has ${entries.size} files, expected ${export.entryCount}.")
        }
        VerifyResult.Verified(admin.markVerified(export.id, manifestSha, entries.size))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Reports bytes read, at most every 4 MB, for the verify progress bar. */
    private class CountingStream(input: InputStream, private val report: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            count += n
            if (count - lastReport >= 4 shl 20) {
                lastReport = count
                report(count)
            }
        }
    }
}
