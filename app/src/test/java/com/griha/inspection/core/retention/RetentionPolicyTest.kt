package com.griha.inspection.core.retention

import com.griha.inspection.core.database.InspectionEntity
import com.griha.inspection.core.database.PhotoEntity
import com.griha.inspection.core.database.ReportEntity
import com.griha.inspection.core.model.InspectionStatus
import com.griha.inspection.core.model.UploadState
import com.griha.inspection.core.retention.RetentionPolicy.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §16.4: no original is removed early, without a matching server checksum, or with work pending. */
class RetentionPolicyTest {
    private val finalized = 100 * DAY_MS
    private val verified = 101 * DAY_MS

    private val inspection = InspectionEntity(
        id = "i1", clientName = "C", clientPhone = "", propertyAddress = "A", inspectorUserId = "u",
        inspectionDate = "2026-10-20", timeZone = "Asia/Kolkata", status = InspectionStatus.FINALIZED,
        createdAt = 1, updatedAt = 2, finalizedAt = finalized, revision = 3,
    )

    private fun photo(id: String, verifiedAt: Long? = verified, checksum: String? = "abc", exported: Long? = verified + DAY_MS) = PhotoEntity(
        id = id, inspectionId = "i1", roomId = "r", findingId = "f", localOriginalPath = "photos/originals/i1/$id.jpg",
        mimeType = "image/jpeg", width = 1, height = 1, fileSizeBytes = 10, sha256 = "abc", capturedAt = 1,
        uploadState = if (verifiedAt != null) UploadState.SERVER_VERIFIED else UploadState.QUEUED,
        backupVerifiedAt = verifiedAt, backupChecksum = checksum, exportVerifiedAt = exported,
        createdAt = 1, updatedAt = 1, revision = 1,
    )

    private val report = ReportEntity(id = "rep", inspectionId = "i1", version = 1, localPdfPath = "x.pdf", contentHash = "h", generatedAt = 1, revision = 1)

    private fun eval(
        i: InspectionEntity? = inspection,
        photos: List<PhotoEntity> = listOf(photo("p1"), photo("p2")),
        pending: Int = 0,
        inUse: Boolean = false,
        now: Long = verified + 31 * DAY_MS,
    ) = RetentionPolicy.evaluate(i, photos, listOf(report), pending, inUse, retentionDays = 30, now = now)

    private fun reasons(v: Verdict) = (v as Verdict.Blocked).reasons

    @Test
    fun eligibleAfterLaterOfFinalizationAndVerificationPlusInterval() {
        assertEquals(Verdict.Eligible(verified + 30 * DAY_MS), eval())
        // One millisecond before the date: waiting, never removed early.
        assertEquals(Verdict.Waiting(verified + 30 * DAY_MS), eval(now = verified + 30 * DAY_MS - 1))
        // The clock starts at verification when it comes after finalization (not at capture or finalization).
        val late = eval(photos = listOf(photo("p1"), photo("p2", verifiedAt = verified + 10 * DAY_MS)), now = verified + 31 * DAY_MS)
        assertEquals(Verdict.Waiting(verified + 40 * DAY_MS), late)
    }

    @Test
    fun blockedWithoutServerChecksumOrOnMismatch() {
        assertTrue(BlockReason.NOT_BACKED_UP in reasons(eval(photos = listOf(photo("p1"), photo("p2", verifiedAt = null, checksum = null)))))
        assertTrue(BlockReason.CHECKSUM_MISMATCH in reasons(eval(photos = listOf(photo("p1", checksum = "def")))))
    }

    @Test
    fun blockedUntilInVerifiedExport() {
        assertEquals(setOf(BlockReason.NOT_EXPORTED), reasons(eval(photos = listOf(photo("p1"), photo("p2", exported = null)))))
    }

    @Test
    fun blockedWhileWorkIsPendingOrInUse() {
        assertTrue(BlockReason.NOT_SYNCED in reasons(eval(pending = 1)))
        assertTrue(BlockReason.IN_USE in reasons(eval(inUse = true)))
        assertTrue(BlockReason.NOT_SYNCED in reasons(eval(photos = listOf(photo("p1").copy(revision = 0)))))
    }

    @Test
    fun blockedUnlessFinalized() {
        assertTrue(BlockReason.NOT_FINALIZED in reasons(eval(i = inspection.copy(status = InspectionStatus.IN_PROGRESS))))
        assertTrue(BlockReason.NOT_FINALIZED in reasons(eval(i = null)))
        assertTrue(BlockReason.NOT_BACKED_UP in reasons(eval(photos = emptyList())))
    }
}
