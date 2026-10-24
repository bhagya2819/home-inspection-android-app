package com.griha.inspection.core.tracking

import androidx.core.location.LocationRequestCompat

/**
 * How often the phone records and uploads (user decision 2026-10-21). The radio costs more battery
 * than the location fix, so points are recorded often but uploaded in batches.
 *
 * - The main request only fires after [minDistanceM] of movement: standing still at a site costs
 *   almost nothing.
 * - A separate heartbeat request (every [HEARTBEAT_MS], no distance filter) proves "still here",
 *   and gives the admin a fresh "last seen" even when nothing moves.
 */
enum class Sampling(val intervalMs: Long, val minDistanceM: Float, val quality: Int, val uploadEveryMs: Long) {
    NORMAL(60_000, 50f, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY, 10 * MINUTE),
    LOW_BATTERY(5 * MINUTE, 0f, LocationRequestCompat.QUALITY_LOW_POWER, 15 * MINUTE),
    LIVE(15_000, 0f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 30_000),
}

const val MINUTE = 60_000L
const val HEARTBEAT_MS = 10 * MINUTE
const val LOW_BATTERY_PERCENT = 20

/** Low battery wins over a live request: a dead phone shows nothing at all. Charging lifts it. */
fun samplingFor(battery: Int?, charging: Boolean, liveUntil: Long, now: Long): Sampling {
    val low = !charging && battery != null && battery <= LOW_BATTERY_PERCENT
    return when {
        low -> Sampling.LOW_BATTERY
        liveUntil > now -> Sampling.LIVE
        else -> Sampling.NORMAL
    }
}

/**
 * Time to upload? A small slack (30 s) keeps a 10-minute heartbeat from just missing the
 * 10-minute mark and stretching uploads to 20 minutes.
 */
fun uploadDue(sampling: Sampling, lastUploadAt: Long, now: Long): Boolean =
    now - lastUploadAt >= sampling.uploadEveryMs - minOf(30_000L, sampling.uploadEveryMs / 4)
