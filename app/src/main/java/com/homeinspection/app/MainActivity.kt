package com.homeinspection.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.homeinspection.app.core.tracking.TrackingNotifications
import com.homeinspection.app.ui.HomeInspectionRoot
import com.homeinspection.app.ui.theme.HomeInspectionTheme
import com.homeinspection.app.ui.theme.ThemeMode
import com.homeinspection.app.ui.theme.windowBackground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // lay out behind the bars from the first frame; icon colours are set below
        val themeSettings = (application as HomeInspectionApp).container.themeSettings
        if (savedInstanceState == null) handleTrackingIntent(intent)
        setContent {
            val mode by themeSettings.mode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // The window shows through during screen transitions and behind the keyboard: keep it the
            // same colour as the theme, and the status/navigation bar icons readable on it.
            LaunchedEffect(dark) {
                window.setBackgroundDrawable(ColorDrawable(windowBackground(dark)))
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            HomeInspectionTheme(dark) {
                HomeInspectionRoot()
            }
        }
    }

    /** Opening the app is a good moment to push pending work and pull others' changes. */
    override fun onStart() {
        super.onStart()
        visible = true
        val app = application as HomeInspectionApp
        val c = app.container
        // Every app start asks the server about the developer lock (locks or unlocks; offline changes nothing).
        app.appScope.launch { c.refreshAppLock() }
        if (c.authRepository.state.value is com.homeinspection.app.core.auth.AuthState.SignedIn) {
            c.syncScheduler.kick()
            // Sharing that should be on but isn't (phone restarted, service killed): resume it now,
            // while the app is visible — the only moment Android 14 allows it.
            app.appScope.launch { c.trackingRepository.onAppVisible() }
        }
    }

    override fun onStop() {
        super.onStop()
        visible = false
    }

    companion object {
        /** A Home Inspection screen is on (started); read by the tracking safety net in HomeInspectionApp. */
        @Volatile var visible = false
            private set
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTrackingIntent(intent)
    }

    private fun handleTrackingIntent(intent: Intent?) {
        if (intent?.action == TrackingNotifications.ACTION_TURN_OFF) {
            (application as HomeInspectionApp).container.trackingRepository.turnOffAsked.value = true
        }
    }
}
