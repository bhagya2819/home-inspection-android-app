package com.griha.inspection.core.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One recorded position, as the admin's day view uses it. */
data class RoutePoint(val at: Long, val lat: Double, val lng: Double, val accuracyM: Float?)

/** A place the engineer stayed: [from]..[to] within [STOP_RADIUS_M] of ([lat], [lng]). */
data class Stop(val from: Long, val to: Long, val lat: Double, val lng: Double, val points: Int) {
    val durationMs get() = to - from
}

const val STOP_RADIUS_M = 100.0
const val STOP_MIN_MS = 5 * 60_000L

/** Fixes this vague say nothing about a 100 m stop: they neither start nor end one. */
private const val MAX_ACCURACY_M = 200f

/**
 * Where the engineer stayed ≥ [STOP_MIN_MS] within [STOP_RADIUS_M] — the "how long were they there"
 * answer without any geofence (user decision). [points] must be in time order. A cluster grows while
 * each new fix lies within the radius of the cluster's centre; the first fix outside ends it.
 * A stop ends at its last fix inside, so it never claims time the engineer may already have left.
 */
fun findStops(points: List<RoutePoint>): List<Stop> {
    val stops = mutableListOf<Stop>()
    var cluster = mutableListOf<RoutePoint>()
    var cLat = 0.0
    var cLng = 0.0

    fun close() {
        if (cluster.size >= 2 && cluster.last().at - cluster.first().at >= STOP_MIN_MS) {
            stops += Stop(cluster.first().at, cluster.last().at, cLat, cLng, cluster.size)
        }
    }

    for (p in points) {
        if ((p.accuracyM ?: 0f) > MAX_ACCURACY_M) continue
        if (cluster.isNotEmpty() && distanceM(cLat, cLng, p.lat, p.lng) <= STOP_RADIUS_M) {
            cluster += p
            // Running mean: the centre settles on where the engineer actually stood.
            cLat += (p.lat - cLat) / cluster.size
            cLng += (p.lng - cLng) / cluster.size
            continue
        }
        close()
        cluster = mutableListOf(p)
        cLat = p.lat
        cLng = p.lng
    }
    close()
    return stops
}

/** Great-circle distance in metres (haversine). */
fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Total distance along the route, ignoring vague fixes (they zig-zag and inflate it). */
fun routeLengthM(points: List<RoutePoint>): Double =
    points.filter { (it.accuracyM ?: 0f) <= MAX_ACCURACY_M }.zipWithNext { a, b -> distanceM(a.lat, a.lng, b.lat, b.lng) }.sum()
