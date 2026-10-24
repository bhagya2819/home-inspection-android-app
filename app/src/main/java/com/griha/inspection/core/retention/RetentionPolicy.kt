package com.griha.inspection.core.retention

import com.griha.inspection.core.database.InspectionEntity
import com.griha.inspection.core.database.PhotoEntity
import com.griha.inspection.core.database.ReportEntity
import com.griha.inspection.core.model.InspectionStatus

/** Why an inspection's originals must stay on this phone for now. Shown to the inspector (PRD §5.9). */
enum class BlockReason(val text: String) {
    NOT_FINALIZED("Report not finalized"),
    NOT_SYNCED("Changes still waiting to upload"),
    NOT_BACKED_UP("Some photos are not backed up on the server yet"),
    CHECKSUM_MISMATCH("The server's checksum doesn't match this phone's photo"),
    NOT_EXPORTED("Waiting for an admin to export and verify these photos"),
    IN_USE("A report is being made from these photos"),
    SERVER_CHECK_FAILED("The server could not confirm its copy"),
}

sealed interface Verdict {
    /** All checks pass and the retention interval has passed. */
    data class Eligible(val eligibleAt: Long) : Verdict

    /** All checks pass; the originals may be removed from [eligibleAt]. */
    data class Waiting(val eligibleAt: Long) : Verdict

    data class Blocked(val reasons: Set<BlockReason>) : Verdict
}

/**
 * The complete safety predicate for removing an inspection's original photos from this phone
 * (PRD §5.9, §12). Pure, so it is unit-tested and re-run unchanged right before every deletion.
 *
 * It is all-or-nothing per inspection: if any photo of the inspection (tombstoned ones included —
 * their files are evidence too) fails a check, none is removed.
 *
 * Beyond the PRD list, a photo must also be inside an export an admin downloaded and verified:
 * the server may later archive its own copy, and the server alone is never the only copy we rely on.
 */
object RetentionPolicy {
    const val DAY_MS = 24 * 60 * 60 * 1000L

    fun evaluate(
        inspection: InspectionEntity?,
        photos: List<PhotoEntity>,
        reports: List<ReportEntity>,
        pendingOutbox: Int,
        inUse: Boolean,
        retentionDays: Int,
        now: Long,
    ): Verdict {
        val reasons = LinkedHashSet<BlockReason>()
        if (inspection == null || inspection.status != InspectionStatus.FINALIZED || inspection.finalizedAt == null) {
            reasons += BlockReason.NOT_FINALIZED
        }
        if (inspection == null || inspection.revision <= 0 || pendingOutbox > 0 ||
            photos.any { it.revision <= 0 } || reports.any { it.revision <= 0 }
        ) {
            reasons += BlockReason.NOT_SYNCED
        }
        for (p in photos) {
            when {
                p.backupVerifiedAt == null -> reasons += BlockReason.NOT_BACKED_UP
                !p.sha256.equals(p.backupChecksum, ignoreCase = true) -> reasons += BlockReason.CHECKSUM_MISMATCH
            }
            if (p.exportVerifiedAt == null) reasons += BlockReason.NOT_EXPORTED
        }
        if (inUse) reasons += BlockReason.IN_USE
        if (photos.isEmpty()) reasons += BlockReason.NOT_BACKED_UP // nothing verified to rely on
        if (reasons.isNotEmpty()) return Verdict.Blocked(reasons)

        // The safer PRD option: the clock starts at the later of finalization and backup verification.
        val verified = photos.maxOf { it.backupVerifiedAt!! }
        val eligibleAt = maxOf(inspection!!.finalizedAt!!, verified) + retentionDays * DAY_MS
        return if (now >= eligibleAt) Verdict.Eligible(eligibleAt) else Verdict.Waiting(eligibleAt)
    }
}
