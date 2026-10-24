package com.homeinspection.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homeinspection.app.ui.theme.HomeInspectionFresh
import com.homeinspection.app.ui.theme.HomeInspectionWarn

/** How a work-status chip reads at a glance. */
enum class ChipTone { OK, WARN, ALERT, OFF, ACTION }

@Composable
private fun toneColors(tone: ChipTone): Pair<Color, Color> = when (tone) {
    ChipTone.OK -> HomeInspectionFresh.copy(alpha = 0.18f) to MaterialTheme.colorScheme.onSurface
    ChipTone.WARN -> HomeInspectionWarn.copy(alpha = 0.16f) to MaterialTheme.colorScheme.onSurface
    ChipTone.ALERT -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    ChipTone.OFF -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    ChipTone.ACTION -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
}

@Composable
fun toneAccent(tone: ChipTone): Color = when (tone) {
    ChipTone.OK -> HomeInspectionFresh
    ChipTone.WARN -> HomeInspectionWarn
    ChipTone.ALERT -> MaterialTheme.colorScheme.error
    ChipTone.OFF -> MaterialTheme.colorScheme.outline
    ChipTone.ACTION -> MaterialTheme.colorScheme.onPrimary
}

/**
 * A small pill on Home (duty, location sharing): icon + a few words, tinted by [tone]. Tapping it
 * opens its [WorkPanel]; the chevron shows which one is open.
 */
@Composable
fun WorkChip(icon: ImageVector, text: String, tone: ChipTone, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    val background by animateColorAsState(bg, label = "chip")
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        onClick = onClick,
        color = background,
        contentColor = fg,
        shape = RoundedCornerShape(50),
        border = if (expanded) BorderStroke(1.5.dp, toneAccent(tone)) else null,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (tone == ChipTone.ACTION) fg else toneAccent(tone), modifier = Modifier.size(18.dp))
            Text(
                text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
            )
            Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(18.dp).rotate(turn))
        }
    }
}

/** The details under an opened [WorkChip]: a title, a line of context, and its buttons. */
@Composable
fun WorkPanel(title: String, subtitle: String?, tone: ChipTone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (tone == ChipTone.WARN || tone == ChipTone.ALERT) toneAccent(tone) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}
