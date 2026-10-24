package com.homeinspection.app.core.face

import com.homeinspection.app.core.attendance.AttendanceRecord
import com.homeinspection.app.feature.attendance.workedMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMatcherTest {
    private fun unit(vararg v: Float) = FaceEngine.normalize(v.copyOf())

    @Test
    fun similarityIsCosine() {
        assertEquals(1f, FaceEngine.similarity(unit(1f, 2f, 3f), unit(2f, 4f, 6f)), 1e-5f)
        assertEquals(0f, FaceEngine.similarity(unit(1f, 0f), unit(0f, 5f)), 1e-5f)
        assertEquals(-1f, FaceEngine.similarity(unit(1f, 1f), unit(-1f, -1f)), 1e-5f)
    }

    @Test
    fun bestScoreTakesTheClosestShot() {
        val shots = listOf(unit(1f, 0f, 0f), unit(0f, 1f, 0f))
        assertEquals(1f, FaceMatcher.bestScore(unit(0f, 1f, 0f), shots), 1e-5f)
    }

    @Test
    fun classifyIsMonotonic() {
        // Whatever the thresholds are, a higher score must never give a worse result.
        val order = listOf(FaceResult.NO_MATCH, FaceResult.WEAK, FaceResult.MATCH)
        var previous = 0
        for (i in -10..10) {
            val rank = order.indexOf(FaceMatcher.classify(i / 10f))
            assertTrue("classify(${i / 10f}) not one of NO_MATCH/WEAK/MATCH", rank >= 0)
            assertTrue("classify(${i / 10f}) got worse", rank >= previous)
            previous = rank
        }
        assertEquals(FaceResult.MATCH, FaceMatcher.classify(0.9f))
        assertEquals(FaceResult.NO_MATCH, FaceMatcher.classify(0.05f))
    }

    @Test
    fun enrolmentShotsMustAgree() {
        assertTrue(FaceMatcher.consistent(listOf(unit(1f, 0.1f), unit(1f, 0.2f), unit(0.9f, 0.1f))))
        assertFalse(FaceMatcher.consistent(listOf(unit(1f, 0f), unit(1f, 0.1f), unit(0f, 1f))))
    }

    private fun rec(kind: String, at: Long) = AttendanceRecord(id = "$kind$at", kind = kind, clientAt = at, faceResult = "MATCH")

    @Test
    fun workedTimePairsInWithNextOut() {
        val h = 3_600_000L
        val day = listOf(rec("IN", 10 * h), rec("OUT", 13 * h), rec("IN", 14 * h), rec("OUT", 18 * h))
        assertEquals(7 * h, workedMs(day, openUntil = null))
        // Still on duty: counts up to now; without "now" (a past day) the open stretch isn't guessed.
        assertEquals(3 * h, workedMs(listOf(rec("IN", 10 * h)), openUntil = 13 * h))
        assertEquals(0L, workedMs(listOf(rec("IN", 10 * h)), openUntil = null))
        // A double check-in keeps the first start.
        assertEquals(8 * h, workedMs(listOf(rec("IN", 10 * h), rec("IN", 11 * h), rec("OUT", 18 * h)), null))
    }
}
