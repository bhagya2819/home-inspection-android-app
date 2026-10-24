package com.griha.inspection.core.report

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.griha.inspection.core.data.enqueue
import com.griha.inspection.core.database.AuditEventEntity
import com.griha.inspection.core.database.FindingEntity
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.database.InspectionEntity
import com.griha.inspection.core.database.ReportEntity
import com.griha.inspection.core.model.FindingKind
import com.griha.inspection.core.model.InspectionStatus
import com.griha.inspection.core.model.SyncEntity
import com.griha.inspection.core.model.SyncOperation
import com.griha.inspection.core.retention.RetentionManager
import com.griha.inspection.core.storage.PhotoStore
import com.griha.inspection.core.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class ReportProgress(val stage: Stage, val done: Int, val total: Int) {
    enum class Stage { PHOTOS, PAGES }

    /** 0..1 overall; preparing photos is most of the work the first time. */
    val fraction: Float
        get() = when (stage) {
            Stage.PHOTOS -> if (total == 0) 0f else 0.85f * done / total
            Stage.PAGES -> 0.85f + if (total == 0) 0f else 0.15f * done / total
        }
}

/** A rendered PDF plus what the inspector should know about it. */
data class RenderedReport(
    val file: File,
    val pageCount: Int,
    val skippedPhotos: Int,
    /** A finalized version only: what the PDF shows, as JSON for the server's Word copy (ReportContentQueue). */
    val content: String? = null,
)

/**
 * Builds report PDFs on the device (PRD §5.7, §13) and keeps their versions.
 *
 * - Drafts are rendered into the cache and replaced freely.
 * - [finalize] renders a numbered version into files/reports/, makes it durable, and records it
 *   with its SHA-256 in one transaction together with the inspection's FINALIZED status and the
 *   REPORT outbox entry. Version files are never overwritten: later edits reopen the inspection
 *   (see reportContentChanged) and the next finalize writes version N+1 beside it.
 */
class ReportRepository(
    private val db: GrihaDatabase,
    private val photoStore: PhotoStore,
    private val filesDir: File,
    private val cacheDir: File,
    private val images: ReportImages,
    /** The Griha logo as a JPEG file (copied out of the APK resources once). */
    private val logo: () -> JpegImage?,
    private val prefs: () -> SharedPreferences,
    /** Fetches photos/PDFs that were made on another phone; null in tests (offline-only). */
    private val downloads: SyncEngine? = null,
    /** Marks an inspection's originals as in use while rendering, so retention cleanup waits. */
    private val retention: RetentionManager? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reports = db.reportDao()
    private val finalizeLock = Mutex()

    fun observeReports(inspectionId: String) = reports.observeForInspection(inspectionId)

    fun fileFor(report: ReportEntity) = File(filesDir, report.localPdfPath)

    /** The version's PDF on this phone, downloading it first if it was finalized on another phone. */
    suspend fun ensureFile(report: ReportEntity): File? {
        val file = fileFor(report)
        if (file.isFile) return file
        return if (downloads?.ensureReportLocal(report) == true) file else null
    }

    /** The inspection's inspector as the server knows them, else the name typed on this phone. */
    suspend fun inspectorNameFor(inspectionId: String): String = serverInspectorName(inspectionId) ?: inspectorName()

    /** Null until the inspection's inspector is known from the server (then the name isn't editable here). */
    suspend fun serverInspectorName(inspectionId: String): String? = withContext(Dispatchers.IO) {
        db.inspectionDao().get(inspectionId)?.inspectorUserId?.let { db.userDao().get(it)?.displayName }
    }

    // Until login exists (M4) the inspector's name is a device setting shown on the review screen.
    suspend fun inspectorName(): String = withContext(Dispatchers.IO) { prefs().getString(KEY_INSPECTOR, "").orEmpty() }

    suspend fun setInspectorName(name: String) = withContext(Dispatchers.IO) {
        prefs().edit().putString(KEY_INSPECTOR, name.trim()).apply()
    }

    /** Renders the current content as a draft PDF in the cache. Cancellable between photos. */
    suspend fun renderDraft(inspectionId: String, onProgress: (ReportProgress) -> Unit): RenderedReport {
        val target = File(cacheDir, "reports/draft-$inspectionId.pdf")
        return render(inspectionId, version = null, reportId = null, target, durable = false, onProgress)
    }

    /**
     * Finalizes running or waiting on this phone, by inspection. App-wide, not per screen: a finalize
     * outlives its screen, and a report screen reopened meanwhile must show it instead of offering
     * the button again (each extra tap used to queue behind the lock and make a duplicate version).
     */
    private val _finalizing = MutableStateFlow<Map<String, ReportProgress>>(emptyMap())
    val finalizing: StateFlow<Map<String, ReportProgress>> = _finalizing.asStateFlow()

    /**
     * Creates the next numbered version. Run in a scope that outlives the screen (GrihaApp.appScope):
     * the version must either be fully recorded or not exist at all.
     *
     * An inspection that is already FINALIZED gets no new version: every content change reopens it
     * (reportContentChanged), so FINALIZED means the latest version already shows the current content.
     * A second call that waited behind the lock returns that version instead of duplicating it.
     */
    suspend fun finalize(inspectionId: String, onProgress: (ReportProgress) -> Unit): ReportEntity {
        val progress = { p: ReportProgress -> _finalizing.update { it + (inspectionId to p) }; onProgress(p) }
        progress(ReportProgress(ReportProgress.Stage.PHOTOS, 0, 0))
        try {
            return finalizeLock.withLock { finalizeNow(inspectionId, progress) }
        } finally {
            _finalizing.update { it - inspectionId }
        }
    }

    private suspend fun finalizeNow(inspectionId: String, onProgress: (ReportProgress) -> Unit): ReportEntity {
        val inspection = db.inspectionDao().get(inspectionId) ?: throw IOException("Inspection not found")
        if (inspection.status == InspectionStatus.FINALIZED) reports.latest(inspectionId)?.let { return it }
        val version = reports.maxVersion(inspectionId) + 1
        val dir = File(filesDir, "reports/$inspectionId")
        val target = File(dir, "Griha-Report-${reportNumber(inspection)}-v$version.pdf")
        val reportId = UUID.randomUUID().toString()
        val rendered = render(inspectionId, version, reportId, target, durable = true, onProgress)
        val hash = withContext(Dispatchers.IO) { PhotoStore.sha256Of(rendered.file) }
        // Before the version is recorded: a crash in between leaves a file the sync engine discards.
        rendered.content?.let { withContext(Dispatchers.IO) { ReportContentQueue.write(filesDir, reportId, it) } }
        val now = clock()
        val report = ReportEntity(
            id = reportId,
            inspectionId = inspectionId,
            version = version,
            localPdfPath = target.relativeTo(filesDir).path,
            contentHash = hash,
            generatedAt = now,
            finalizedAt = now,
        )
        db.withTransaction {
            reports.insert(report)
            db.inspectionDao().markFinalized(inspectionId, version, now)
            db.outboxDao().enqueue(SyncEntity.REPORT, report.id, SyncOperation.UPSERT, now)
            db.outboxDao().enqueue(SyncEntity.INSPECTION, inspectionId, SyncOperation.UPSERT, now)
            db.auditDao().insert(audit("REPORT_FINALIZED", report, "version=$version sha256=$hash", now))
        }
        return report
    }

    /**
     * Records that the inspector opened the share sheet for this version. Android cannot tell us
     * whether WhatsApp or email actually delivered it, so this is all we claim (PRD §13).
     */
    suspend fun markShared(reportId: String) {
        val now = clock()
        db.withTransaction {
            val report = reports.get(reportId) ?: return@withTransaction
            reports.markShared(reportId, now)
            db.outboxDao().enqueue(SyncEntity.REPORT, reportId, SyncOperation.UPSERT, now)
            db.auditDao().insert(audit("REPORT_SHARED", report, "version=${report.version}", now))
        }
    }

    /** Startup: removes half-written PDFs left by a crash mid-render. Recorded versions are never touched. */
    suspend fun cleanupPartials() = withContext(Dispatchers.IO) {
        listOf(File(filesDir, "reports"), File(cacheDir, "reports")).forEach { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() }
        }
        ReportContentQueue.cleanupPartials(filesDir)
        images.cleanup()
    }

    // ---- Rendering ---------------------------------------------------------------------------

    private suspend fun render(
        inspectionId: String,
        version: Int?,
        reportId: String?,
        target: File,
        durable: Boolean,
        onProgress: (ReportProgress) -> Unit,
    ): RenderedReport {
        val guard = retention ?: return renderNow(inspectionId, version, reportId, target, durable, onProgress)
        return guard.using(inspectionId) { renderNow(inspectionId, version, reportId, target, durable, onProgress) }
    }

    private suspend fun renderNow(
        inspectionId: String,
        version: Int?,
        reportId: String?,
        target: File,
        durable: Boolean,
        onProgress: (ReportProgress) -> Unit,
    ): RenderedReport = withContext(Dispatchers.IO) {
        val inspection = db.inspectionDao().get(inspectionId) ?: throw IOException("Inspection not found")
        val rooms = db.roomAreaDao().activeRooms(inspectionId)
        val findings = db.findingDao().activeForInspection(inspectionId)
        val photos = db.photoDao().activeForInspection(inspectionId)
        val inspector = inspectorNameFor(inspectionId)
        // Photos taken on another phone are fetched from the server first (skipped if offline).
        // Originals cleared by retention come back too: a new report version needs them.
        downloads?.ensurePhotosLocal(photos.filter { !photoStore.resolve(it.localOriginalPath).isFile }, restoreRemoved = true)

        // Report-size images, at most two decoding at once (each peaks at ~20 MB for a 12 MP photo).
        val counter = AtomicInteger()
        onProgress(ReportProgress(ReportProgress.Stage.PHOTOS, 0, photos.size))
        val variants: Map<String, JpegImage?> = coroutineScope {
            photos.map { photo ->
                async(VariantDispatcher) {
                    ensureActive()
                    val image = try {
                        images.variantFor(photo.id, photoStore.resolve(photo.localOriginalPath), photo.sha256)
                    } catch (_: IOException) {
                        null // missing or unreadable original: leave it out and tell the inspector
                    }
                    onProgress(ReportProgress(ReportProgress.Stage.PHOTOS, counter.incrementAndGet(), photos.size))
                    photo.id to image
                }
            }.awaitAll().toMap()
        }
        ensureActive()

        val photosByFinding = photos.groupBy { it.findingId }
        val (issues, roomPhotos) = findings.partition { it.kind == FindingKind.ISSUE }
        val findingsByRoom = issues.groupBy { it.roomId }
        val roomPhotoIds = roomPhotos.groupBy({ it.roomId }, { it.id })
        fun reportPhotos(findingId: String) = photosByFinding[findingId].orEmpty().mapNotNull { p ->
            variants[p.id]?.let { ReportPhoto(it, p.caption) }
        }
        val document = ReportDocument(
            reportNumber = reportNumber(inspection),
            version = version,
            clientName = inspection.clientName,
            clientPhone = inspection.clientPhone,
            propertyAddress = inspection.propertyAddress,
            unitNumber = inspection.unitNumber,
            propertyType = inspection.propertyType,
            inspectionDate = LocalDate.parse(inspection.inspectionDate).format(LongDate),
            inspectorName = inspector,
            summary = inspection.summary,
            rooms = rooms.map { room ->
                ReportRoom(
                    room.name,
                    findingsByRoom[room.id].orEmpty().map { f -> reportFinding(f, reportPhotos(f.id)) },
                    // Normally one per room; two phones may each have started one, so take all.
                    commonPhotos = roomPhotoIds[room.id].orEmpty().flatMap(::reportPhotos),
                )
            },
            logo = logo(),
        )
        val pages = ReportLayout(document).build()

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.part")
        FileOutputStream(tmp).use { fileOut ->
            PdfWriter(fileOut.buffered(256 * 1024), ReportLayout.PAGE_W, ReportLayout.PAGE_H).apply {
                pages.forEachIndexed { i, page ->
                    ensureActive()
                    addPage(page)
                    onProgress(ReportProgress(ReportProgress.Stage.PAGES, i + 1, pages.size))
                }
                finish("Griha inspection report ${document.reportNumber} – ${document.versionLabel}")
            }
            // Same rule as photos: bytes on flash before the file appears under its final name.
            if (durable) fileOut.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // The same rooms, findings and photos the PDF just printed (unreadable photos left out there too).
        val content = if (reportId != null && version != null) buildJsonObject {
            put("format", 1)
            put("report_id", reportId)
            put("inspection_id", inspectionId)
            put("version", version)
            put("report_number", document.reportNumber)
            put("client_name", inspection.clientName)
            put("client_phone", inspection.clientPhone)
            put("property_address", inspection.propertyAddress)
            put("unit_number", inspection.unitNumber.orEmpty())
            put("property_type", inspection.propertyType.orEmpty())
            put("inspection_date", inspection.inspectionDate)
            put("inspector_name", inspector)
            put("summary", inspection.summary.orEmpty())
            putJsonArray("rooms") {
                rooms.forEach { room ->
                    addJsonObject {
                        put("name", room.name)
                        // The room's common photos, printed under its heading before the findings.
                        putJsonArray("photos") {
                            roomPhotoIds[room.id].orEmpty().flatMap { photosByFinding[it].orEmpty() }.filter { variants[it.id] != null }.forEach { p ->
                                addJsonObject { put("id", p.id); put("sha256", p.sha256); put("caption", p.caption.orEmpty()) }
                            }
                        }
                        putJsonArray("findings") {
                            findingsByRoom[room.id].orEmpty().forEach { f ->
                                val printed = reportFinding(f, emptyList())
                                addJsonObject {
                                    put("title", printed.title)
                                    put("category", f.category.orEmpty())
                                    put("severity", f.severity?.name.orEmpty())
                                    put("status", f.status.name)
                                    put("observation", printed.observation.orEmpty())
                                    put("implication", f.implication.orEmpty())
                                    put("recommendation", f.recommendation.orEmpty())
                                    putJsonArray("photos") {
                                        photosByFinding[f.id].orEmpty().filter { variants[it.id] != null }.forEach { p ->
                                            addJsonObject { put("id", p.id); put("sha256", p.sha256); put("caption", p.caption.orEmpty()) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString() else null
        RenderedReport(target, pages.size, skippedPhotos = variants.count { it.value == null }, content = content)
    }

    private fun reportFinding(f: FindingEntity, photos: List<ReportPhoto>) = ReportFinding(
        // The site note is the inspector's scratch pad and never reaches the client (user decision
        // 2026-10-21): an untitled finding is named by its category, an empty observation stays empty.
        title = f.title?.ifBlank { null } ?: f.category ?: "Finding",
        severity = f.severity,
        category = f.category,
        status = f.status,
        observation = f.detailedObservation?.ifBlank { null },
        implication = f.implication,
        recommendation = f.recommendation,
        photos = photos,
    )

    private fun audit(type: String, report: ReportEntity, details: String, now: Long) = AuditEventEntity(
        id = UUID.randomUUID().toString(),
        type = type,
        entityType = "REPORT",
        entityId = report.id,
        details = "inspection=${report.inspectionId} $details",
        createdAt = now,
    )

    companion object {
        private const val KEY_INSPECTOR = "inspector_name"
        private val LongDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
        private val ShortDate = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val VariantDispatcher = Dispatchers.IO.limitedParallelism(2)

        /** Stable per inspection, readable over the phone: GRH-<yyMMdd>-<first 4 of the id>. */
        fun reportNumber(i: InspectionEntity): String =
            "GRH-${LocalDate.parse(i.inspectionDate).format(ShortDate)}-${i.id.take(4).uppercase(Locale.ROOT)}"
    }
}
