package com.homeinspection.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class PiiScrubberTest {
    private val s = PiiScrubber("Priya Rao", "+91 98480 22338", "12-4-56, Brodipet, Guntur", "B-302")

    @Test
    fun removesNameAddressUnitAndPhone() {
        assertEquals(
            "the client said leak in [unit] since May; call [phone]. Address: the property",
            s.scrub("Priya Rao said leak in B-302 since May; call 98480-22338. Address: 12-4-56, Brodipet, Guntur"),
        )
        assertEquals("Mrs. the client's kitchen", s.scrub("Mrs. rao's kitchen"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val text = "Hairline crack 302 mm long near the Raoof window; priyanka plants"
        assertEquals(text, s.scrub(text))
    }

    @Test
    fun noDetailsMeansNoChange() {
        assertEquals("crack near window", PiiScrubber("", "", "", null).scrub("crack near window"))
    }
}
