package com.griha.inspection.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Small grey heading above a [ListGroup] ("Your work", "Account"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** A white rounded block of [ListRow]s separated by hairlines — the app's one list style. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

@Composable
fun ListDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))

/**
 * One tappable row: icon, title, optional one-line status, chevron. [contentColor] overrides the
 * title/icon colour (e.g. red for "Sign out"); [iconTint] colours only the icon (status ticks).
 */
@Composable
fun ListRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = contentColor,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Small count/alert bubble for a [ListRow] trailing slot. */
@Composable
fun CountBadge(count: Int, container: Color = MaterialTheme.colorScheme.error, content: Color = MaterialTheme.colorScheme.onError) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text("$count", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

/** Initials in a neutral circle ("Ravi Kumar" → "RK"). */
@Composable
fun InitialsAvatar(name: String, size: Int = 40, modifier: Modifier = Modifier) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
        modifier = modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initials, fontWeight = FontWeight.SemiBold, style = if (size >= 56) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
        }
    }
}

enum class MainTab { Home, Profile }

/** Bottom navigation on the two top-level screens. "New" is an action, not a tab: it opens the form. */
@Composable
fun MainBottomBar(selected: MainTab, onHome: () -> Unit, onNew: () -> Unit, onProfile: () -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = selected == MainTab.Home, onClick = onHome, colors = colors,
            icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") },
        )
        NavigationBarItem(
            selected = false, onClick = onNew, colors = colors,
            icon = { Icon(Icons.Filled.Add, null) }, label = { Text("New inspection") },
        )
        NavigationBarItem(
            selected = selected == MainTab.Profile, onClick = onProfile, colors = colors,
            icon = { Icon(Icons.Filled.AccountCircle, null) }, label = { Text("Profile") },
        )
    }
}

/** A titled white card grouping form fields ("Client", "Property", "Rooms to inspect"). */
@Composable
fun FormSection(title: String, modifier: Modifier = Modifier, subtitle: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                subtitle?.invoke()
            }
            content()
        }
    }
}
