package com.griha.inspection.core.ai

import android.content.SharedPreferences
import com.griha.inspection.core.database.FindingEntity
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.database.InspectionEntity
import com.griha.inspection.core.model.FindingCategories
import com.griha.inspection.core.model.FindingKind
import com.griha.inspection.core.model.Severity
import com.griha.inspection.core.network.ApiClient
import com.griha.inspection.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.net.SocketTimeoutException

/** What the AI proposes for one finding. Every field is optional; the inspector accepts or discards each. */
data class FindingSuggestion(
    val title: String?,
    val observation: String?,
    val implication: String?,
    val recommendation: String?,
    val severity: Severity?,
    val severityReason: String?,
    val category: String?,
)

data class ReviewIssue(val findingId: String, val kind: Kind, val message: String) {
    enum class Kind(val label: String) {
        MISSING_DETAIL("Missing detail"),
        SEVERITY("Severity"),
        DUPLICATE("Possible duplicate"),
        UNCLEAR("Unclear wording"),
    }
}

/** A failed AI request, with a message the inspector can act on. */
class AiException(message: String) : Exception(message)

/**
 * AI writing help through the Griha server (which holds the DeepSeek key; phones never do).
 * Online only, unlike the rest of the app. Nothing here writes data: callers apply what the
 * inspector accepts through the normal repositories, so every accepted text syncs as usual.
 * Sent: room names, the property type, and finding text. Never client name, phone or address.
 */
class AiRepository(private val api: ApiClient, private val db: GrihaDatabase, private val prefs: SharedPreferences) {

    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, false))
    /** The admin has set a key (from the last GET /v1/settings, cached for offline starts). */
    val enabled = _enabled.asStateFlow()

    fun applyServerSettings(o: JsonObject) {
        val on = (o["ai_enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (on != _enabled.value) {
            prefs.edit().putBoolean(K_ENABLED, on).apply()
            _enabled.value = on
        }
    }

    /** Re-reads the on/off flag now (after the admin changed the key), instead of at the next sync. */
    suspend fun refresh() {
        try {
            applyServerSettings(api.get("/v1/settings"))
        } catch (_: IOException) {
        }
    }

    suspend fun suggestFinding(finding: FindingEntity): FindingSuggestion {
        val room = db.roomAreaDao().get(finding.roomId)?.name.orEmpty()
        val inspection = db.inspectionDao().get(finding.inspectionId)
        val clean = scrubberFor(inspection)::scrub
        val captions = db.photoDao().observeForFinding(finding.id).first().mapNotNull { it.caption?.ifBlank { null } }
        val body = buildJsonObject {
            put("room", clean(room))
            put("property_type", inspection?.propertyType.orEmpty())
            put("category", finding.category.orEmpty())
            put("severity", finding.severity?.name.orEmpty())
            put("title", clean(finding.title.orEmpty()))
            put("site_note", clean(finding.roughNote))
            put("observation", clean(finding.detailedObservation.orEmpty()))
            put("implication", clean(finding.implication.orEmpty()))
            put("recommendation", clean(finding.recommendation.orEmpty()))
            putJsonArray("photo_captions") { captions.forEach { add(JsonPrimitive(clean(it))) } }
            putJsonArray("categories") { FindingCategories.forEach { add(JsonPrimitive(it)) } }
        }
        val o = call("/v1/ai/finding", body)
        return parse {
            FindingSuggestion(
            title = o.text("title"),
            observation = o.text("observation"),
            implication = o.text("implication"),
            recommendation = o.text("recommendation"),
            severity = o.text("severity")?.let { s -> Severity.entries.firstOrNull { it.name == s } },
            severityReason = o.text("severity_reason"),
            category = o.text("category")?.takeIf { it in FindingCategories },
            )
        }
    }

    suspend fun draftSummary(inspectionId: String): String =
        parse { call("/v1/ai/summary", inspectionBody(inspectionId, withNotes = false)).text("summary") }
            ?: throw AiException("The AI didn't write a summary. Try again.")

    suspend fun review(inspectionId: String): List<ReviewIssue> {
        val o = call("/v1/ai/review", inspectionBody(inspectionId, withNotes = true), readTimeoutMs = 180_000)
        return parse { o["issues"]?.jsonArray.orEmpty() }.mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            val kind = ReviewIssue.Kind.entries.firstOrNull { it.name == i.text("kind") } ?: return@mapNotNull null
            ReviewIssue(i.text("finding_id") ?: return@mapNotNull null, kind, i.text("message") ?: return@mapNotNull null)
        }
    }

    /** The report's content, room by room, as the summary and review endpoints expect it. */
    private suspend fun inspectionBody(inspectionId: String, withNotes: Boolean): JsonObject {
        val inspection = db.inspectionDao().get(inspectionId) ?: throw AiException("Inspection not found.")
        val rooms = db.roomAreaDao().activeRooms(inspectionId)
        // Room common photos have no text to write about or check.
        val findings = db.findingDao().activeForInspection(inspectionId).filter { it.kind == FindingKind.ISSUE }.groupBy { it.roomId }
        if (findings.isEmpty()) throw AiException("Record some findings first.")
        val clean = scrubberFor(inspection)::scrub
        return buildJsonObject {
            put("property_type", inspection.propertyType.orEmpty())
            put("rooms", buildJsonArray {
                rooms.forEach { room ->
                    add(buildJsonObject {
                        put("name", clean(room.name))
                        put("findings", buildJsonArray {
                            findings[room.id].orEmpty().forEach { f ->
                                add(buildJsonObject {
                                    put("id", f.id)
                                    put("title", clean(f.title?.ifBlank { null } ?: f.roughNote.lineSequence().firstOrNull().orEmpty().take(80)))
                                    put("severity", f.severity?.name.orEmpty())
                                    put("category", f.category.orEmpty())
                                    if (withNotes) put("site_note", clean(f.roughNote))
                                    put("observation", clean(f.detailedObservation.orEmpty()))
                                    put("implication", clean(f.implication.orEmpty()))
                                    put("recommendation", clean(f.recommendation.orEmpty()))
                                })
                            }
                        })
                    })
                }
            })
        }
    }

    /** The server gives up after 60 s (150 s for the review); the phone waits a little longer. */
    private suspend fun call(path: String, body: JsonObject, readTimeoutMs: Int = 90_000): JsonObject = try {
        api.post(path, body, readTimeoutMs = readTimeoutMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code == "AI_OFF") applyServerSettings(JsonObject(emptyMap()))
        throw AiException(
            when {
                e.isAuthFailure -> "Sign in again to use AI suggestions."
                e.code.startsWith("AI_") || e.code == "INVALID" -> e.message ?: "AI suggestions failed."
                else -> "AI suggestions failed (${e.message}). Try again."
            }
        )
    } catch (e: SocketTimeoutException) {
        throw AiException("The AI took too long to answer. Try again in a minute.")
    } catch (e: IOException) {
        throw AiException("No internet connection. AI suggestions need the internet; everything else works offline.")
    } catch (e: Exception) { // e.g. a body that isn't JSON
        throw AiException("The AI answer couldn't be read. Try again.")
    }

    /** An answer of an unexpected shape becomes a message, never a crash. */
    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: AiException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AiException("The AI answer couldn't be read. Try again.")
    }

    private fun scrubberFor(i: InspectionEntity?) =
        PiiScrubber(i?.clientName.orEmpty(), i?.clientPhone.orEmpty(), i?.propertyAddress.orEmpty(), i?.unitNumber)

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

    private companion object {
        const val K_ENABLED = "ai_enabled"
    }
}
