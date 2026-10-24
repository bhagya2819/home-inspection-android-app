package com.homeinspection.app.core.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.homeinspection.app.HomeInspectionApp
import kotlinx.coroutines.launch

/**
 * The phone restarted (or the app was updated) while sharing. Android 14 doesn't let a location
 * service start from here without the "all the time" permission, so this records the interruption
 * for the admin and shows "Tap to resume" (user decision 2026-10-21).
 */
class TrackingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "REBOOT"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "APP_UPDATED"
            else -> return
        }
        val app = context.applicationContext as HomeInspectionApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.container.trackingRepository.onInterrupted(reason)
            } finally {
                pending.finish()
            }
        }
    }
}
