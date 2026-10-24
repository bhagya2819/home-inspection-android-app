package com.griha.inspection.ui.theme

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stored by name; never rename a constant. */
enum class ThemeMode(val label: String) { LIGHT("Light"), DARK("Dark"), SYSTEM("Phone setting") }

/**
 * The user's light/dark choice (Profile › Appearance). Light by default, whatever the phone uses.
 * Read synchronously at start-up so the very first frame already has the right colours.
 */
class ThemeSettings(private val prefs: SharedPreferences) {
    private val _mode = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
    )
    val mode = _mode.asStateFlow()

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private companion object {
        const val KEY = "theme_mode"
    }
}
