package com.homeinspection.app.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {
    @Test
    fun modes() {
        val now = 1_000_000L
        assertEquals(Sampling.NORMAL, samplingFor(80, false, 0, now))
        assertEquals(Sampling.NORMAL, samplingFor(null, false, 0, now))
        assertEquals(Sampling.LIVE, samplingFor(80, false, now + 1, now))
        assertEquals(Sampling.NORMAL, samplingFor(80, false, now - 1, now)) // live expired
        assertEquals(Sampling.LOW_BATTERY, samplingFor(20, false, now + 1, now)) // battery beats live
        assertEquals(Sampling.LIVE, samplingFor(10, true, now + 1, now)) // charging: no saving needed
    }

    @Test
    fun heartbeatJustUnderTenMinutesStillUploads() {
        val t = 100 * MINUTE
        assertTrue(uploadDue(Sampling.NORMAL, t, t + 10 * MINUTE - 20_000))
        assertFalse(uploadDue(Sampling.NORMAL, t, t + 5 * MINUTE))
        assertTrue(uploadDue(Sampling.LIVE, t, t + 25_000))
        assertFalse(uploadDue(Sampling.LIVE, t, t + 15_000))
    }
}
