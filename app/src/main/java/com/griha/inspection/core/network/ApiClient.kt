package com.griha.inspection.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The server answered with an error. [code] is the stable machine code from {"error": {"code"}}. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message) {
    /** Session gone (expired, revoked, account disabled): the user must sign in again. */
    val isAuthFailure get() = status == 401
    /**
     * The developer lock (server `griha-api lock`): both the status and the code must match, so a 423
     * from anything else never locks the app. Not a fault of the request: never mark work failed for it.
     */
    val isAppLocked get() = status == 423 && code == "APP_LOCKED"
    /** The account still has a temporary password: nothing but changing it works (not an item's fault). */
    val needsPasswordChange get() = code == "PASSWORD_CHANGE_REQUIRED"
    /** Will not succeed by retrying the same request. */
    val isPermanent get() = status in 400..499 && status != 408 && status != 429 && status != 409 && !isAppLocked
}

/**
 * Tiny JSON-over-HTTPS client on the platform's HttpURLConnection.
 *
 * Why not OkHttp: it would add ~300–400 KB to a ~2.5 MB APK for features we don't use
 * (interceptors, HTTP/2, caching). We need a handful of endpoints, streamed uploads with a known
 * length, and Range downloads — all of which HttpURLConnection does (on Android it is itself
 * backed by a built-in OkHttp). All calls block: use from Dispatchers.IO (the suspend helpers do).
 */
class ApiClient(
    private val baseUrl: String,
    private val token: () -> String?,
    /** Every response that is the developer lock goes here first, whichever feature made the call. */
    private val onAppLocked: (message: String) -> Unit = {},
) {

    suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) { request("GET", path, null) }

    suspend fun post(path: String, body: JsonElement?, readTimeoutMs: Int = 60_000): JsonObject =
        withContext(Dispatchers.IO) { request("POST", path, body, readTimeoutMs = readTimeoutMs) }

    /** POST authenticated with [bearer] instead of the current session (ending an old session). */
    suspend fun postAs(bearer: String, path: String): JsonObject = withContext(Dispatchers.IO) { request("POST", path, null, bearer) }

    suspend fun put(path: String, body: JsonElement): JsonObject = withContext(Dispatchers.IO) { request("PUT", path, body) }

    suspend fun delete(path: String): JsonObject = withContext(Dispatchers.IO) { request("DELETE", path, null) }

    /** Android's HttpURLConnection (OkHttp inside) accepts PATCH, unlike the desktop JDK's. */
    suspend fun patch(path: String, body: JsonElement): JsonObject = withContext(Dispatchers.IO) { request("PATCH", path, body) }

    /** Absolute URL for [path], e.g. for the system DownloadManager. */
    fun url(path: String): String = baseUrl + path

    private fun request(method: String, path: String, body: JsonElement?, bearer: String? = token(), readTimeoutMs: Int = 60_000): JsonObject {
        val conn = open(path, method, bearer)
        conn.readTimeout = readTimeoutMs
        try {
            if (body != null) {
                val bytes = Json.encodeToString(JsonElement.serializer(), body).toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            return readJson(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams [file] as the request body (never loaded into memory). The server re-hashes the
     * bytes and only answers 200 if size and SHA-256 match what was recorded at capture.
     */
    suspend fun putFile(path: String, file: File, contentType: String): JsonObject = withContext(Dispatchers.IO) {
        val conn = open(path, "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(file.length())
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
            readJson(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads to [target], resuming from its current length with an HTTP Range request.
     * [onProgress] gets bytes on disk so far and the full size (-1 if the server didn't say).
     * Returns the server's SHA-256 header so the caller can verify before using the file.
     */
    suspend fun download(path: String, target: File, onProgress: ((done: Long, total: Long) -> Unit)? = null): String? = withContext(Dispatchers.IO) {
        val conn = open(path, "GET")
        try {
            val have = if (target.isFile) target.length() else 0L
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val status = conn.responseCode
            if (status !in 200..299) throw errorOf(conn, status)
            val append = status == HttpURLConnection.HTTP_PARTIAL
            var done = if (append) have else 0L
            val total = conn.contentLengthLong.let { if (it < 0) -1L else it + done }
            java.io.FileOutputStream(target, append).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // Stop when the screen that asked is gone: downloads share one lock, and an
                        // abandoned PDF used to hold up the finalize waiting behind it. The partial resumes later.
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
                out.fd.sync()
            }
            conn.getHeaderField("X-Content-SHA256")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String, bearer: String? = token()): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return conn
    }

    private fun readJson(conn: HttpURLConnection): JsonObject {
        val status = conn.responseCode
        if (status !in 200..299) throw errorOf(conn, status)
        if (status == HttpURLConnection.HTTP_NO_CONTENT) return JsonObject(emptyMap())
        val text = conn.inputStream.use(InputStream::readBytes).decodeToString()
        return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
    }

    private fun errorOf(conn: HttpURLConnection, status: Int): ApiException {
        val text = try {
            conn.errorStream?.use(InputStream::readBytes)?.decodeToString()
        } catch (_: IOException) {
            null
        }
        val error = try {
            text?.let { Json.parseToJsonElement(it).jsonObject["error"]?.jsonObject }
        } catch (_: Exception) {
            null
        }
        return ApiException(
            status,
            error?.get("code")?.jsonPrimitive?.content ?: "HTTP_$status",
            error?.get("message")?.jsonPrimitive?.content ?: "Server error ($status)",
        ).also { if (it.isAppLocked) onAppLocked(it.message.orEmpty()) }
    }
}
