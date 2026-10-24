package com.homeinspection.app.core.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.homeinspection.app.MainActivity
import com.homeinspection.app.R

/** The persistent "sharing" notification and the "tap to resume" one after a reboot. */
object TrackingNotifications {
    const val SHARING_ID = 2001
    private const val RESUME_ID = 2002
    private const val CHANNEL_SHARING = "location_sharing"
    private const val CHANNEL_RESUME = "location_resume"

    /** Intent action: open the app on the "Turn off sharing" question. */
    const val ACTION_TURN_OFF = "com.homeinspection.app.TURN_OFF_SHARING"

    /** Intent action: open the app to resume sharing (opening the app resumes it). */
    const val ACTION_RESUME = "com.homeinspection.app.RESUME_SHARING"

    private fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SHARING, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your location is shared with your admin"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESUME, "Location sharing stopped", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When sharing stopped and needs a tap to resume (e.g. after the phone restarted)"
            }
        )
    }

    private fun openApp(context: Context, action: String?, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context, request,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun sharing(context: Context, live: Boolean): Notification {
        channels(context)
        return NotificationCompat.Builder(context, CHANNEL_SHARING)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("Sharing your location")
            .setContentText(if (live) "Your admin is viewing your location live" else "Your admin can see where you are while this is on")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, null, 1))
            .addAction(0, "Turn off", openApp(context, ACTION_TURN_OFF, 2))
            .build()
    }

    fun showResume(context: Context) {
        channels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_RESUME)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("Location sharing stopped")
            .setContentText("Tap to resume sharing your location")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, ACTION_RESUME, 3))
            .build()
        // Without the notification permission (Android 13+) this is silently dropped; the
        // dashboard card shows the same "Resume" button, and the admin sees "Stopped".
        runCatching { context.getSystemService(NotificationManager::class.java).notify(RESUME_ID, n) }
    }

    fun cancelResume(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RESUME_ID)
    }
}
