package com.griha.inspection.core.tracking

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.griha.inspection.GrihaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Foreground service (type `location`) that records points while sharing is on. Started only from
 * a tap in the app (Android 14 forbids starting it from the background without the "all the time"
 * permission, which we deliberately don't ask for). Platform LocationManager, no Play services.
 *
 * Recording and uploading run in the app scope, so stopping the service never cuts a save short.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: LocationManager
    private val app get() = application as GrihaApp
    private val repo get() = app.container.trackingRepository

    private var sampling: Sampling? = null
    private var battery: Int? = null
    private var charging = false
    private var locationOn = true
    private var mockReported = false
    private var started = false

    private val main = LocationListenerCompat { onFix(it) }
    private val heartbeat = LocationListenerCompat { onFix(it) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readBattery(intent)
            applySampling()
        }
    }

    private val providersReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = LocationManagerCompat.isLocationEnabled(manager)
            if (on == locationOn) return
            locationOn = on
            app.appScope.launch {
                repo.recordState(if (on) "LOCATION_ON" else "LOCATION_OFF")
                repo.pushQuietly() // the admin should hear about it at the next chance, not in 10 min
            }
            if (on) applySampling(force = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        manager = getSystemService(LocationManager::class.java)
        // startForeground must come first (within 5 s of startForegroundService), even when we stop right after.
        try {
            ServiceCompat.startForeground(
                this, TrackingNotifications.SHARING_ID, TrackingNotifications.sharing(this, live = false),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // No location permission, or the system restarted us from the background (Android 14:
            // a location service needs a visible app). The engineer resumes it with a tap.
            Log.w(TAG, "foreground start refused", e)
            app.appScope.launch { repo.onServiceRefused() }
            stopSelf()
            return START_NOT_STICKY
        }
        if (repo.status.value.mode != SharingMode.SHARING) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) begin()
        return START_STICKY
    }

    private fun begin() {
        started = true
        running = true
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let(::readBattery)
        ContextCompat.registerReceiver(this, providersReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        locationOn = LocationManagerCompat.isLocationEnabled(manager)
        if (!locationOn) app.appScope.launch { repo.recordState("LOCATION_OFF"); repo.pushQuietly() }
        scope.launch { repo.liveUntil.drop(1).collect { applySampling() } } // the first value is applied just below
        applySampling(force = true)
        // Something to show at once (a fix under 2 min old), then the first upload: the admin
        // sees the engineer straight after check-in rather than 10 minutes later.
        app.appScope.launch {
            runCatching { lastKnown() }.getOrNull()?.takeIf { System.currentTimeMillis() - it.time < 2 * MINUTE }?.let { repo.record(it, battery) }
            repo.pushQuietly()
        }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        battery = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** (Re-)registers the location requests when the sampling mode changes. */
    @SuppressLint("MissingPermission")
    private fun applySampling(force: Boolean = false) {
        if (!started) return
        val next = samplingFor(battery, charging, repo.liveUntil.value, System.currentTimeMillis())
        if (next == sampling && !force) return
        val wasLive = sampling == Sampling.LIVE
        sampling = next
        Log.i(TAG, "sampling $next (battery=$battery charging=$charging)")
        try {
            LocationManagerCompat.removeUpdates(manager, main)
            LocationManagerCompat.removeUpdates(manager, heartbeat)
            val provider = provider(next.quality)
            if (provider == null) {
                Log.w(TAG, "no location provider enabled")
                return
            }
            val request = LocationRequestCompat.Builder(next.intervalMs)
                .setQuality(next.quality)
                .setMinUpdateDistanceMeters(next.minDistanceM)
                .setMinUpdateIntervalMillis(next.intervalMs / 2)
                .build()
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, main, Looper.getMainLooper())
            if (next.minDistanceM > 0f) {
                val hb = LocationRequestCompat.Builder(HEARTBEAT_MS)
                    .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(HEARTBEAT_MS - MINUTE)
                    .build()
                LocationManagerCompat.requestLocationUpdates(manager, provider, hb, heartbeat, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission gone", e)
            app.appScope.launch { repo.onServiceRefused() }
            stopSelf()
            return
        }
        val live = next == Sampling.LIVE
        if (live != wasLive) {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(TrackingNotifications.SHARING_ID, TrackingNotifications.sharing(this, live))
        }
    }

    /** Android 12+: the built-in fused provider. Older: GPS for live, else network if it is on. */
    private fun provider(quality: Int): String? = when {
        Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        quality == LocationRequestCompat.QUALITY_HIGH_ACCURACY && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? = provider(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)?.let(manager::getLastKnownLocation)

    private fun onFix(location: Location) {
        val b = battery
        val s = sampling ?: Sampling.NORMAL
        app.appScope.launch {
            val row = repo.record(location, b) ?: return@launch
            if (row.isMock && !mockReported) {
                mockReported = true
                repo.recordState("MOCK")
            }
            if (uploadDue(s, repo.lastUploadAt, System.currentTimeMillis())) repo.pushQuietly()
        }
        // A live request expires on its own; re-check the mode on each fix.
        if (s == Sampling.LIVE) applySampling()
    }

    override fun onDestroy() {
        running = false
        if (started) {
            runCatching { unregisterReceiver(batteryReceiver) }
            runCatching { unregisterReceiver(providersReceiver) }
            LocationManagerCompat.removeUpdates(manager, main)
            LocationManagerCompat.removeUpdates(manager, heartbeat)
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GrihaTracking"

        /** True while this process's service records points. */
        @Volatile var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
