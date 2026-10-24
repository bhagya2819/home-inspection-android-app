package com.homeinspection.app.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopsTest {
    private val min = 60_000L

    // ~0.0009° latitude ≈ 100 m.
    private fun p(minute: Int, lat: Double, lng: Double = 78.40, acc: Float = 15f) = RoutePoint(minute * min, lat, lng, acc)

    @Test
    fun stationaryHeartbeatsMakeOneStop() {
        val pts = listOf(p(0, 17.4000), p(10, 17.4001), p(20, 17.3999), p(30, 17.4000), p(34, 17.4100), p(35, 17.4200))
        val stops = findStops(pts)
        assertEquals(1, stops.size)
        assertEquals(0L, stops[0].from)
        assertEquals(30 * min, stops[0].to) // ends at the last fix inside, not when the next one arrives
        assertEquals(4, stops[0].points)
    }

    @Test
    fun drivingPastMakesNoStop() {
        val pts = (0..20).map { p(it, 17.40 + it * 0.003) } // ~330 m per minute
        assertTrue(findStops(pts).isEmpty())
    }

    @Test
    fun shortHaltIsNotAStop() {
        val pts = listOf(p(0, 17.40), p(1, 17.4001), p(4, 17.4001), p(5, 17.41))
        assertTrue(findStops(pts).isEmpty()) // 4 minutes < 5
    }

    @Test
    fun vagueFixesNeitherBreakNorJoinAStop() {
        val pts = listOf(p(0, 17.40), p(5, 17.45, acc = 900f), p(10, 17.4001), p(20, 17.4002), p(21, 17.43))
        val stops = findStops(pts)
        assertEquals(1, stops.size)
        assertEquals(20 * min, stops[0].durationMs)
    }

    @Test
    fun twoSitesTwoStops() {
        val pts = listOf(p(0, 17.40), p(10, 17.4001), p(15, 17.42), p(20, 17.44), p(30, 17.4401), p(40, 17.4400))
        val stops = findStops(pts)
        assertEquals(2, stops.size)
        assertEquals(10 * min, stops[0].durationMs)
        assertEquals(20 * min, stops[1].durationMs)
    }

    @Test
    fun distanceIsHaversine() {
        // 0.001° latitude ≈ 111 m everywhere.
        assertEquals(111.2, distanceM(17.400, 78.4, 17.401, 78.4), 0.5)
        assertEquals(0.0, routeLengthM(listOf(p(0, 17.4))), 0.0)
    }
}
