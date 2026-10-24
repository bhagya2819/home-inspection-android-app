package com.homeinspection.app.core.tracking

/** One line of an engineer's day, in time order. */
sealed interface TimelineItem {
    val at: Long

    data class Event(val event: DayEvent) : TimelineItem { override val at get() = event.clientAt }

    /** [index] = the stop's number on the map (1-based). */
    data class StopItem(val stop: Stop, val index: Int) : TimelineItem { override val at get() = stop.from }

    /** Sharing was on, but no point arrived for [durationMs] (dead battery, no GPS, app killed…). */
    data class Gap(override val at: Long, val durationMs: Long) : TimelineItem
}

/** Longer than the 10-min heartbeat plus slack: the phone should have recorded something. */
const val GAP_MS = 25 * MINUTE

/** Events, stops and silent gaps merged into one time-ordered list. [points] must be in time order. */
fun buildTimeline(points: List<DayPoint>, events: List<DayEvent>): Pair<List<Stop>, List<TimelineItem>> {
    val stops = findStops(points.map { RoutePoint(it.clientAt, it.lat, it.lng, it.accuracyM) })
    val items = mutableListOf<TimelineItem>()
    events.forEach { items += TimelineItem.Event(it) }
    stops.forEachIndexed { i, s -> items += TimelineItem.StopItem(s, i + 1) }

    // Gaps: walk events and points together; only while sharing counts.
    var sharing: Boolean? = null // unknown until the first event: points imply sharing (START was before this day)
    var last: Long? = null
    val marks = events.map { it.clientAt to it } + points.map { it.clientAt to null }
    for ((at, e) in marks.sortedWith(compareBy({ it.first }, { if (it.second == null) 1 else 0 }))) {
        when (e?.kind) {
            "START" -> { sharing = true; last = at }
            "STOP", "PAUSE", "INTERRUPTED" -> { sharing = false; last = null }
            null -> {
                val prev = last
                if (sharing != false && prev != null && at - prev > GAP_MS) items += TimelineItem.Gap(prev, at - prev)
                if (sharing != false) last = at
            }
        }
    }
    return stops to items.sortedBy { it.at }
}
