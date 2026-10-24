package com.griha.inspection.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import com.griha.inspection.core.model.Severity
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Griha brand palette (brand guidelines, "Colour theory").
val GrihaGreen = Color(0xFF26552A)
val GrihaFresh = Color(0xFF69AA61)
val GrihaDeep = Color(0xFF1B392B)
val GrihaCream = Color(0xFFF8F6EA)
val GrihaSage = Color(0xFFDDE7D8)
val GrihaWarn = Color(0xFFB26A00)

/*
 * Light: the brand cream page with white cards; brand green only as the accent (primary buttons,
 * selection, "backed up"). Dark: neutral charcoal. Every slot a Material component reads is set,
 * so nothing falls back to Material's default purple. The window background (MainActivity) must
 * equal `background`, or screen changes flash.
 */
private val LightColors = lightColorScheme(
    primary = GrihaGreen,
    onPrimary = Color.White,
    primaryContainer = GrihaSage,
    onPrimaryContainer = GrihaDeep,
    inversePrimary = Color(0xFF9CD293),
    secondary = Color(0xFF4B4C42),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE9DA),
    onSecondaryContainer = Color(0xFF1F2620),
    tertiary = Color(0xFF3F6B8C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1ECF5),
    onTertiaryContainer = Color(0xFF13344D),
    background = GrihaCream,
    onBackground = Color(0xFF1F2620),
    surface = GrihaCream,
    onSurface = Color(0xFF1F2620),
    surfaceVariant = Color(0xFFECE9DA),
    onSurfaceVariant = Color(0xFF65675B),
    surfaceTint = Color.Transparent,
    surfaceBright = GrihaCream,
    surfaceDim = Color(0xFFE0DDCC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF2EFE1),
    surfaceContainerHigh = Color(0xFFECE9DA),
    surfaceContainerHighest = Color(0xFFE5E2D2),
    outline = Color(0xFFCFCBB8),
    outlineVariant = Color(0xFFE6E2D2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CC783),
    onPrimary = Color(0xFF0E2A12),
    primaryContainer = Color(0xFF243B27),
    onPrimaryContainer = Color(0xFFCDE8C6),
    inversePrimary = GrihaGreen,
    secondary = Color(0xFFC6C6C1),
    onSecondary = Color(0xFF2E2E2C),
    secondaryContainer = Color(0xFF34342F),
    onSecondaryContainer = Color(0xFFE6E6E3),
    tertiary = Color(0xFF9CC4E4),
    onTertiary = Color(0xFF0F2F47),
    tertiaryContainer = Color(0xFF223B4F),
    onTertiaryContainer = Color(0xFFD3E6F5),
    background = Color(0xFF141414),
    onBackground = Color(0xFFE6E6E3),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFE6E6E3),
    surfaceVariant = Color(0xFF2B2B29),
    onSurfaceVariant = Color(0xFFA6A6A0),
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF3A3A38),
    surfaceDim = Color(0xFF141414),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF1E1E1D),
    surfaceContainer = Color(0xFF232322),
    surfaceContainerHigh = Color(0xFF2B2B29),
    surfaceContainerHighest = Color(0xFF353533),
    outline = Color(0xFF55554F),
    outlineVariant = Color(0xFF30302E),
)

/** True when the app is drawing its dark theme (the user's choice, not necessarily the phone's). */
val LocalGrihaDark = staticCompositionLocalOf { false }

/** Window/page background for a mode; MainActivity paints the window with it. */
fun windowBackground(dark: Boolean): Int = (if (dark) DarkColors.background else LightColors.background).toArgb()

/** Status/role tints that Material's scheme has no slot for; one light and one dark pair each. */
data class Tint(val container: Color, val content: Color)

object GrihaTints {
    val warning: Tint
        @Composable @ReadOnlyComposable
        get() = if (LocalGrihaDark.current) Tint(Color(0xFF3B2F14), Color(0xFFF2CF85)) else Tint(Color(0xFFFFF1D6), Color(0xFF7A4B00))
}

/** Background/foreground pair per severity, light and dark. The foreground doubles as the dot colour. */
@Composable
@ReadOnlyComposable
fun severityColors(severity: Severity): Tint = if (LocalGrihaDark.current) {
    when (severity) {
        Severity.INFO -> Tint(Color(0xFF1E3247), Color(0xFFB8D4F0))
        Severity.MINOR -> Tint(Color(0xFF22372A), Color(0xFFB9DDB3))
        Severity.MODERATE -> Tint(Color(0xFF3B2F14), Color(0xFFF2CF85))
        Severity.MAJOR -> Tint(Color(0xFF43281A), Color(0xFFF5B98E))
        Severity.URGENT -> Tint(Color(0xFF4A1F1C), Color(0xFFFFB4AB))
    }
} else {
    when (severity) {
        Severity.INFO -> Tint(Color(0xFFE3ECF7), Color(0xFF1F4E79))
        Severity.MINOR -> Tint(Color(0xFFE2EEDD), Color(0xFF26552A))
        Severity.MODERATE -> Tint(Color(0xFFFFF1D6), Color(0xFF7A4B00))
        Severity.MAJOR -> Tint(Color(0xFFFFE3D1), Color(0xFF8A3300))
        Severity.URGENT -> Tint(Color(0xFFFFDAD6), Color(0xFF93000A))
    }
}

@Composable
fun GrihaTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGrihaDark provides dark) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
