package com.griha.inspection.core.data

import com.griha.inspection.core.database.FindingEntity
import com.griha.inspection.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

/** An open finding editor must not overwrite a newer edit pulled from another phone. */
class FindingMergeTest {
    private val base = FindingEntity(
        id = "f1", inspectionId = "i1", roomId = "r1", title = "Crack", roughNote = "crack", createdBy = "u",
        createdAt = 1, updatedAt = 1, revision = 1,
    )

    @Test
    fun otherPhonesFieldSurvivesMyEdit() {
        val mine = base.copy(recommendation = "Seal it")             // I typed a recommendation
        val theirs = base.copy(severity = Severity.MAJOR, revision = 2) // meanwhile another phone set severity
        val merged = mergeFindingEdits(base, mine, theirs)
        assertEquals("Seal it", merged.recommendation)
        assertEquals(Severity.MAJOR, merged.severity)
        assertEquals(2L, merged.revision) // non-editor fields always come from the DB row
    }

    @Test
    fun fieldIChangedWinsOverTheirs() {
        val mine = base.copy(title = "Hairline crack")
        val theirs = base.copy(title = "Big crack")
        assertEquals("Hairline crack", mergeFindingEdits(base, mine, theirs).title)
    }

    @Test
    fun untouchedEditorShowsTheirChange() {
        val theirs = base.copy(implication = "Water may enter")
        assertEquals(theirs, mergeFindingEdits(base, base, theirs))
    }
}
