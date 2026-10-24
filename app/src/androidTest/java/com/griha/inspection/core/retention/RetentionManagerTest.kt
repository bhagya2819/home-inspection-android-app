package com.griha.inspection.core.retention

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.griha.inspection.core.data.enqueue
import com.griha.inspection.core.database.FindingEntity
import com.griha.inspection.core.database.GrihaDatabase
import com.griha.inspection.core.database.InspectionEntity
import com.griha.inspection.core.database.PhotoEntity
import com.griha.inspection.core.database.RoomAreaEntity
import com.griha.inspection.core.model.InspectionStatus
import com.griha.inspection.core.model.SyncEntity
import com.griha.inspection.core.model.SyncOperation
import com.griha.inspection.core.model.UploadState
import com.griha.inspection.core.retention.RetentionPolicy.DAY_MS
import com.griha.inspection.core.storage.PhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Real deletions on a device (PRD §16.4): files, Room transaction, audit trail, re-check at deletion time. */
@RunWith(AndroidJUnit4::class)
class RetentionManagerTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: GrihaDatabase
    private lateinit var store: PhotoStore
    private var now = 200 * DAY_MS
    private var serverAnswer: (String) -> String = { "OK" }
    private var beforeAnswer: suspend () -> Unit = {}
    private var offline = false

    private val finalizedAt = 100 * DAY_MS
    private val verifiedAt = 101 * DAY_MS

    private fun manager() = RetentionManager(
        db = db,
        photoStore = store,
        serverCheck = { ids ->
            beforeAnswer()
            if (offline) throw IOException("offline")
            ids.associateWith(serverAnswer)
        },
        prefs = ctx.getSharedPreferences("retention-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
        deviceId = { "device-1" },
        currentUserId = { "u1" },
        clock = { now },
    )

    @Before
    fun setUp() = runBlocking {
        root = File(ctx.cacheDir, "retention-test-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, GrihaDatabase::class.java).build()
        store = PhotoStore(root)
        db.inspectionDao().insert(
            InspectionEntity(
                id = "i1", clientName = "Priya", clientPhone = "", propertyAddress = "Guntur", inspectorUserId = "u1",
                inspectionDate = "2026-06-01", timeZone = "Asia/Kolkata", status = InspectionStatus.FINALIZED,
                createdAt = 1, updatedAt = 2, finalizedAt = finalizedAt, revision = 2,
            )
        )
        db.roomAreaDao().insertAll(listOf(RoomAreaEntity("r1", "i1", "Kitchen", 0, false, 1, 1, revision = 1)))
        db.findingDao().insert(FindingEntity(id = "f1", inspectionId = "i1", roomId = "r1", createdBy = "u1", createdAt = 1, updatedAt = 1, revision = 1))
        for (id in listOf("p1", "p2")) {
            val path = "photos/originals/i1/$id.jpg"
            File(root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1000) { it.toByte() }) }
            db.photoDao().insert(
                PhotoEntity(
                    id = id, inspectionId = "i1", roomId = "r1", findingId = "f1", localOriginalPath = path, mimeType = "image/jpeg",
                    width = 1, height = 1, fileSizeBytes = 1000, sha256 = "abc", capturedAt = 1, uploadState = UploadState.SERVER_VERIFIED,
                    backupVerifiedAt = verifiedAt, backupChecksum = "abc", exportVerifiedAt = verifiedAt + DAY_MS,
                    serverObjectKey = "photos/i1/$id.jpg", createdAt = 1, updatedAt = 1, revision = 1,
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun file(id: String) = File(root, "photos/originals/i1/$id.jpg")

    @Test
    fun removesEligibleOriginalsAndRecordsEach() = runBlocking {
        val m = manager()
        m.setEnabled(true)
        val result = m.cleanNow()
        assertEquals(2, result.removed)
        assertEquals(2000L, result.freedBytes)
        assertFalse(file("p1").exists() || file("p2").exists())
        val p1 = db.photoDao().get("p1")!!
        assertNotNull(p1.originalDeletedAt)
        assertEquals(verifiedAt + 30 * DAY_MS, p1.cleanupEligibleAt)
        assertEquals("abc", p1.sha256) // metadata stays understandable
        val events = db.auditDao().notUploaded(50).filter { it.type == "PHOTO_ORIGINAL_DELETED" }
        assertEquals(2, events.size)
        assertTrue(events.all { "policy=retention-30d" in it.details!! && "device=device-1" in it.details!! && "server_check=OK" in it.details!! })
        assertEquals(0, m.overview().localPhotos)
    }

    @Test
    fun nothingBeforeTheDateOrWhileOff() = runBlocking {
        val m = manager()
        assertEquals(0, m.cleanNow().removed) // not turned on by the user
        m.setEnabled(true)
        now = verifiedAt + 30 * DAY_MS - 1
        assertEquals(0, m.cleanNow().removed)
        assertTrue(file("p1").exists() && file("p2").exists())
    }

    @Test
    fun serverCopyGoneBlocksAndIsFlagged() = runBlocking {
        val m = manager()
        m.setEnabled(true)
        serverAnswer = { id -> if (id == "p2") "MISSING" else "OK" }
        val result = m.cleanNow()
        assertEquals(0, result.removed) // all-or-nothing per inspection
        assertTrue(file("p1").exists() && file("p2").exists())
        assertTrue(db.auditDao().notUploaded(50).any { it.type == "RETENTION_BLOCKED_SERVER_COPY" && "p2=MISSING" in it.details!! })
        offline = true
        assertEquals(0, m.cleanNow().removed)
        assertNull(db.photoDao().get("p1")!!.originalDeletedAt)
    }

    @Test
    fun editBetweenCheckAndDeletionWins() = runBlocking {
        val m = manager()
        m.setEnabled(true)
        // An edit lands while the server is being asked: the transaction's re-check must see it.
        beforeAnswer = { db.outboxDao().enqueue(SyncEntity.FINDING, "f1", SyncOperation.UPSERT, now) }
        assertEquals(0, m.cleanNow().removed)
        assertTrue(file("p1").exists() && file("p2").exists())
    }

    @Test
    fun inUseWaitsAndLeftoversAreSwept() = runBlocking {
        val m = manager()
        m.setEnabled(true)
        m.using("i1") { assertEquals(0, m.cleanNow().removed) }
        // Simulate a run killed after its commit but before deleting the file.
        db.photoDao().markOriginalRemoved("p1", 0, now)
        assertTrue(file("p1").exists())
        m.cleanNow()
        assertFalse(file("p1").exists())
        assertFalse(file("p2").exists())
    }

    @Test
    fun archivedCopyCountsOnlyWithVerifiedExport() = runBlocking {
        val m = manager()
        m.setEnabled(true)
        serverAnswer = { "ARCHIVED" }
        assertEquals(2, m.cleanNow().removed) // the export zip, verified by an admin, holds them
    }
}
