package com.homeinspection.app.core.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homeinspection.app.R
import com.homeinspection.app.core.data.InspectionRepository
import com.homeinspection.app.core.data.PhotoRepository
import com.homeinspection.app.core.data.PhotoTarget
import com.homeinspection.app.core.data.PropertyDetails
import com.homeinspection.app.core.database.HomeInspectionDatabase
import com.homeinspection.app.core.model.InspectionStatus
import com.homeinspection.app.core.model.Severity
import com.homeinspection.app.core.model.SyncEntity
import com.homeinspection.app.core.model.SyncOperation
import com.homeinspection.app.core.storage.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** End-to-end on a device: real JPEGs, real Room DB (in memory), real PdfRenderer. */
@RunWith(AndroidJUnit4::class)
class ReportRepositoryTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: HomeInspectionDatabase
    private lateinit var store: PhotoStore
    private lateinit var inspections: InspectionRepository
    private lateinit var photos: PhotoRepository
    private lateinit var reports: ReportRepository

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "report-test-${UUID.randomUUID()}")
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, HomeInspectionDatabase::class.java).build()
        store = PhotoStore(files)
        inspections = InspectionRepository(db, { "tester" })
        photos = PhotoRepository(db, store, { "tester" })
        val logo = File(cache, "logo.jpg").apply { ctx.resources.openRawResource(R.raw.home_inspection_logo).use { i -> outputStream().use { i.copyTo(it) } } }
        reports = ReportRepository(
            db, store, files, cache, ReportImages(cache),
            logo = { JpegImage(logo, 192, 192) },
            prefs = { ctx.getSharedPreferences("report-test", Context.MODE_PRIVATE) },
        )
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    /** A 4000x3000 camera-like JPEG; [exifOrientation] as CameraX writes it for a portrait shot. */
    private fun capture(target: PhotoTarget, exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL): String = runBlocking {
        val (photoId, tmp) = photos.newCapture(target)
        val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(200, 190, 170))
            val p = Paint().apply { color = Color.rgb(60, 60, 60); strokeWidth = 3f }
            for (i in 0 until 60) drawLine(i * 70f, 0f, i * 70f + 900f, 3000f, p) // fine "cracks"
            drawRect(0f, 0f, 4000f, 300f, Paint().apply { color = Color.RED }) // marks the top edge
        }
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        ExifInterface(tmp.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            saveAttributes()
        }
        photos.commitCapture(photoId, tmp, target, System.currentTimeMillis())
        photoId
    }

    private fun pageCount(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r ->
            r.openPage(0).use { page ->
                val bmp = Bitmap.createBitmap(600, 848, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pixels = IntArray(600 * 848).also { bmp.getPixels(it, 0, 600, 0, 0, 600, 848) }
                assertTrue("page 1 has content", pixels.count { it != Color.WHITE } > 5_000)
            }
            r.pageCount
        }
    }

    @Test
    fun draftFinalizeEditAndNewVersion() = runBlocking {
        val inspectionId = inspections.create(
            PropertyDetails(clientName = "Priya Reddy", clientPhone = "98480 00000", propertyAddress = "Lakshmi Residency, Guntur", unitNumber = "Flat 302"),
            listOf("Living room", "Kitchen", "Bathroom 1"),
        )
        val rooms = inspections.observeRooms(inspectionId).first()
        val findingId = UUID.randomUUID().toString()
        val target = PhotoTarget(inspectionId, rooms[0].room.id, findingId)
        val photoIds = listOf(
            capture(target),
            capture(target, ExifInterface.ORIENTATION_ROTATE_90),
            capture(target),
        )
        val finding = db.findingDao().get(findingId)!!
        inspections.saveFinding(finding, finding.copy(title = "Hairline crack near window", severity = Severity.MODERATE, category = "Walls & finishes", roughNote = "crack at window corner", recommendation = "Seal and monitor"))
        photos.updateCaption(photoIds[0], "Crack at the top-left window corner")
        val originalHashes = photoIds.associateWith { PhotoStore.sha256Of(store.resolve(db.photoDao().get(it)!!.localOriginalPath)) }

        // Draft.
        val draft = reports.renderDraft(inspectionId) {}
        assertEquals(0, draft.skippedPhotos)
        assertTrue(pageCount(draft.file) >= 3)

        // EXIF rotate-90 original (4000x3000 landscape pixels) becomes a 1200x1600 portrait variant.
        val rotated = db.photoDao().get(photoIds[1])!!
        val variant = ReportImages(File(root, "cache")).variantFor(rotated.id, store.resolve(rotated.localOriginalPath), rotated.sha256)
        assertEquals(1200, variant.widthPx)
        assertEquals(1600, variant.heightPx)

        // Finalize v1.
        val v1 = reports.finalize(inspectionId) {}
        assertEquals(1, v1.version)
        val v1File = reports.fileFor(v1)
        assertEquals(PhotoStore.sha256Of(v1File), v1.contentHash)
        db.inspectionDao().get(inspectionId)!!.let {
            assertEquals(InspectionStatus.FINALIZED, it.status)
            assertEquals(1, it.reportVersion)
            assertNotNull(it.finalizedAt)
        }
        assertNotNull(db.outboxDao().find(SyncEntity.REPORT, v1.id, SyncOperation.UPSERT))

        // Opening the editor without changes must not reopen; a real edit must.
        db.findingDao().get(findingId)!!.let { inspections.saveFinding(it, it) }
        assertEquals(InspectionStatus.FINALIZED, db.inspectionDao().get(inspectionId)!!.status)
        db.findingDao().get(findingId)!!.let { inspections.saveFinding(it, it.copy(recommendation = "Seal with flexible filler; recheck in 6 months")) }
        assertEquals(InspectionStatus.IN_PROGRESS, db.inspectionDao().get(inspectionId)!!.status)

        // Finalize v2 beside v1; v1 bytes untouched.
        val v2 = reports.finalize(inspectionId) {}
        assertEquals(2, v2.version)
        assertNotEquals(v1.localPdfPath, v2.localPdfPath)
        assertNotEquals(v1.contentHash, v2.contentHash)
        assertEquals(v1.contentHash, PhotoStore.sha256Of(v1File))

        // Kept for eyeballing the layout: adb exec-out run-as com.homeinspection.app cat cache/last-report-test.pdf
        v2File(reports.fileFor(v2))
        reports.markShared(v2.id)
        assertNotNull(db.reportDao().get(v2.id)!!.sharedAt)

        // Originals are evidence: byte-identical after all of the above.
        originalHashes.forEach { (id, hash) ->
            val p = db.photoDao().get(id)!!
            assertEquals(hash, PhotoStore.sha256Of(store.resolve(p.localOriginalPath)))
            assertEquals(hash, p.sha256)
        }
    }

    /**
     * The duplicate-version bug: a slow finalize kept running after its screen closed, the reopened
     * screen offered the button again, and the second tap waited on the lock, then made v(N+1) ~100 ms
     * after vN with nothing changed in between. Seen as v8+v9 and v14+v15 on the POCO (2026-10-21).
     */
    @Test
    fun overlappingFinalizesMakeOneVersion() = runBlocking {
        val inspectionId = inspections.create(PropertyDetails(clientName = "Twice", propertyAddress = "Guntur"), listOf("Hall"))
        val room = inspections.observeRooms(inspectionId).first()[0].room.id
        capture(PhotoTarget(inspectionId, room, UUID.randomUUID().toString()))

        val both = listOf(async(Dispatchers.Default) { reports.finalize(inspectionId) {} }, async(Dispatchers.Default) { reports.finalize(inspectionId) {} }).awaitAll()
        assertEquals(1, db.reportDao().maxVersion(inspectionId))
        assertEquals(both[0].id, both[1].id)
        assertTrue(reports.finalizing.value.isEmpty())

        // Already finalized and unchanged: another finalize returns the same version.
        assertEquals(both[0].id, reports.finalize(inspectionId) {}.id)
        assertEquals(1, db.reportDao().maxVersion(inspectionId))

        // A real edit reopens it, and the next finalize is a new version.
        inspections.updateSummary(inspectionId, "Sound structure overall.")
        assertEquals(2, reports.finalize(inspectionId) {}.version)
    }

    private fun v2File(f: File) = f.copyTo(File(ctx.cacheDir, "last-report-test.pdf"), overwrite = true)

    /** Many photos: memory must stay flat and the PDF small (logged for the PRD §16.3 notes). */
    @Test
    fun manyPhotosStayBounded() = runBlocking {
        val inspectionId = inspections.create(PropertyDetails(clientName = "Load test", propertyAddress = "Somewhere"), listOf("A", "B", "C", "D"))
        val rooms = inspections.observeRooms(inspectionId).first()
        val findingFor = HashMap<String, String>()
        repeat(40) { i ->
            val room = rooms[i % rooms.size].room.id
            if (i % 12 < 4) findingFor[room] = UUID.randomUUID().toString() // a few findings per room
            capture(PhotoTarget(inspectionId, room, findingFor.getValue(room)))
        }
        System.gc()
        val before = Debug.getNativeHeapAllocatedSize()
        var peak = before
        val start = System.nanoTime()
        val rendered = reports.renderDraft(inspectionId) { peak = maxOf(peak, Debug.getNativeHeapAllocatedSize()) }
        val firstMs = (System.nanoTime() - start) / 1_000_000
        val start2 = System.nanoTime()
        reports.renderDraft(inspectionId) {}
        val cachedMs = (System.nanoTime() - start2) / 1_000_000
        val pages = pageCount(rendered.file)
        Log.i(
            "ReportTest",
            "40 photos: pdf=${rendered.file.length() / 1024} KB pages=$pages first=${firstMs}ms cached=${cachedMs}ms " +
                "nativePeakGrowth=${(peak - before) / 1_000_000} MB",
        )
        assertTrue("native growth bounded", peak - before < 120_000_000)
        assertTrue("pdf under 1 MB per photo", rendered.file.length() < 40L * 1_000_000)
    }
}
