package com.homeinspection.app.core.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * One-shot location for a check-in/out, on the platform LocationManager (no Google Play services
 * library). Android 12+ has a built-in fused provider (GPS + Wi-Fi + cell); older phones listen to
 * GPS and network at once. The screen keeps the most accurate fix it sees for a few seconds.
 */
class LocationFix(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    /** Location switched on in the phone's quick settings. */
    fun isEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)

    /** Fixes as they arrive, until the collector stops. Requires [hasPermission]. */
    @SuppressLint("MissingPermission")
    fun updates(): Flow<PlaceFix> = callbackFlow {
        val listener = LocationListenerCompat { loc -> trySend(loc.toFix()) }
        val providers = if (Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        }
        val request = LocationRequestCompat.Builder(1_000L).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
        // A recent fix (under a minute old) shows something at once; fresh ones replace it.
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 60_000 }
            .minByOrNull { it.accuracy }
            ?.let { trySend(it.toFix()) }
        providers.forEach { LocationManagerCompat.requestLocationUpdates(manager, it, request, listener, Looper.getMainLooper()) }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    private fun Location.toFix() = PlaceFix(
        lat = latitude, lng = longitude, accuracyM = if (hasAccuracy()) accuracy else 9_999f, at = time,
        isMock = LocationCompat.isMock(this),
    )
}
