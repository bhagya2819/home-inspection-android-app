package com.homeinspection.app.core.face

import android.content.SharedPreferences
import com.homeinspection.app.core.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** The signed-in engineer's face set-up, as last heard from the server. */
data class FaceProfile(
    val userId: String,
    /** Approved enrolment used for matching; null = not set up (or reset by the admin). */
    val approvedId: String? = null,
    val embeddings: List<FloatArray> = emptyList(),
    /** A newer enrolment waiting for the admin. */
    val pendingId: String? = null,
    /** The latest enrolment was rejected (or the face was reset): enrol again. */
    val rejected: Boolean = false,
) {
    val approved get() = approvedId != null && embeddings.isNotEmpty()
}

/**
 * Keeps the engineer's approved face embeddings on the phone (so check-in works offline) and
 * uploads new enrolments. Embeddings are numbers derived from the face, not photos; the reference
 * photo goes to the server for the admin and is not kept here.
 */
class FaceRepository(
    private val api: ApiClient,
    private val prefs: SharedPreferences,
    private val currentUserId: () -> String?,
) {
    private val _profile = MutableStateFlow(load())
    val profile = _profile.asStateFlow()

    /** Cached profile for the signed-in user (another user's cache is ignored). */
    fun current(): FaceProfile? = _profile.value?.takeIf { it.userId == currentUserId() } ?: load()

    /** Asks the server for the latest state. Throws IOException when offline. */
    suspend fun refresh(): FaceProfile? {
        val user = currentUserId() ?: return null
        val o = api.get("/v1/face/me")
        // Embeddings only compare within one model (the server already filters; belt and braces).
        val approved = (o["approved"] as? JsonObject)?.takeIf { it["model"]?.jsonPrimitive?.content == FaceModelStore.MODEL_NAME }
        val profile = FaceProfile(
            userId = user,
            approvedId = approved?.get("id")?.jsonPrimitive?.content,
            embeddings = approved?.get("embeddings")?.jsonArray?.map { v -> v.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }.orEmpty()
                .map { FaceEngine.normalize(it) },
            pendingId = (o["pending"] as? JsonObject)?.get("id")?.jsonPrimitive?.content,
            rejected = approved == null && o["rejected"] is JsonObject && o["pending"] !is JsonObject,
        )
        save(profile)
        return profile
    }

    /** Sends a new enrolment for the admin to approve. Online only; throws on failure. */
    suspend fun enroll(id: String, shots: List<FloatArray>, photo: File, photoSha256: String) {
        api.post("/v1/face/enrollments", buildJsonObject {
            put("id", id)
            put("model", FaceModelStore.MODEL_NAME)
            put("embeddings", buildJsonArray { shots.forEach { v -> add(buildJsonArray { v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) } })
            put("photo_sha256", photoSha256)
            put("photo_size", photo.length())
        })
        val res = api.putFile("/v1/face/enrollments/$id/photo", photo, "image/jpeg")
        check(res["sha256"]?.jsonPrimitive?.content.equals(photoSha256, ignoreCase = true)) { "Server checksum differs" }
        runCatching { refresh() }
    }

    private fun save(p: FaceProfile) {
        prefs.edit().putString(KEY, encode(p)).apply()
        _profile.value = p
    }

    private fun load(): FaceProfile? = prefs.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }
        ?.takeIf { it.userId == currentUserId() }

    private fun encode(p: FaceProfile) = buildJsonObject {
        put("user", p.userId)
        put("model", FaceModelStore.MODEL_NAME)
        p.approvedId?.let { put("approved", it) }
        p.pendingId?.let { put("pending", it) }
        put("rejected", p.rejected)
        put("embeddings", buildJsonArray { p.embeddings.forEach { v -> add(JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it) })) } })
    }.toString()

    private fun decode(s: String): FaceProfile {
        val o = Json.parseToJsonElement(s).jsonObject
        // Cached by an app version that used another face model: unusable, wait for a refresh.
        if (o["model"]?.jsonPrimitive?.content != FaceModelStore.MODEL_NAME) return FaceProfile(userId = o["user"]!!.jsonPrimitive.content)
        return FaceProfile(
            userId = o["user"]!!.jsonPrimitive.content,
            approvedId = o["approved"]?.jsonPrimitive?.content,
            embeddings = o["embeddings"]!!.jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() },
            pendingId = o["pending"]?.jsonPrimitive?.content,
            rejected = o["rejected"]?.jsonPrimitive?.content == "true",
        )
    }

    private companion object {
        const val KEY = "profile"
    }
}
