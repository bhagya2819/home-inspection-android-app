package com.griha.inspection.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private fun pt(minute: Int, lat: Double = 17.40) = DayPoint(minute * MINUTE, lat, 78.40, 15f)
    private fun ev(minute: Int, kind: String) = DayEvent(kind = kind, clientAt = minute * MINUTE)

    @Test
    fun silentStretchWhileSharingIsAGap() {
        val points = listOf(pt(0), pt(10), pt(50)) // nothing for 40 min
        val (_, items) = buildTimeline(points, listOf(ev(0, "START")))
        val gaps = items.filterIsInstance<TimelineItem.Gap>()
        assertEquals(1, gaps.size)
        assertEquals(10 * MINUTE, gaps[0].at)
        assertEquals(40 * MINUTE, gaps[0].durationMs)
    }

    @Test
    fun turnedOffIsNotAGap() {
        val points = listOf(pt(0), pt(10), pt(120))
        val events = listOf(ev(0, "START"), ev(11, "PAUSE"), ev(119, "START"))
        val (_, items) = buildTimeline(points, events)
        assertTrue(items.none { it is TimelineItem.Gap })
    }

    @Test
    fun stopsAndEventsInTimeOrder() {
        val points = listOf(pt(0), pt(10), pt(20), pt(21, 17.43))
        val (stops, items) = buildTimeline(points, listOf(ev(0, "START"), ev(30, "STOP")))
        assertEquals(1, stops.size)
        assertEquals(listOf(0L, 0L, 30 * MINUTE), items.map { it.at })
        assertTrue(items.last() is TimelineItem.Event)
    }
}
