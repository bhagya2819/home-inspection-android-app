package com.griha.inspection.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.griha.inspection.core.sync.PhotoArrivals
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.griha.inspection.core.database.PhotoEntity
import com.griha.inspection.core.model.UploadState
import com.griha.inspection.ui.theme.GrihaWarn
import java.io.File

/**
 * Thumbnail of an original. Coil sizes the decode to this composable's bounds, so a 50 MP
 * original is never decoded at full resolution just to show 72 dp (PRD §5.10, §11.4).
 */
/** Shown in place of a thumbnail whose original is not on this phone (PRD §5.9 P1: don't misrepresent it). */
fun PhotoEntity.offPhoneLabel(): String? = when {
    originalDeletedAt != null -> "Cleared from phone · backed up"
    archivedAt != null -> "Archived in export"
    else -> null
}

@Composable
fun PhotoThumb(file: File, state: UploadState, size: Dp = 72.dp, modifier: Modifier = Modifier, offPhone: String? = null) {
    Box(modifier.size(size).clip(RoundedCornerShape(10.dp))) {
        if (offPhone != null) {
            // Drawn under the image: if the file is present after all, the photo covers this label.
            Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), contentAlignment = Alignment.Center) {
                Text(offPhone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        val arrivals by PhotoArrivals.count.collectAsState()
        key(arrivals) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
        val (icon, tint) = when (state) {
            UploadState.SERVER_VERIFIED, UploadState.CLEANUP_ELIGIBLE -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
            UploadState.RETRY_REQUIRED -> Icons.Filled.Warning to GrihaWarn
            else -> Icons.Filled.Refresh to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(
            imageVector = icon,
            contentDescription = state.label,
            tint = tint,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(18.dp)
                .background(Color.White, CircleShape)
                .padding(2.dp),
        )
    }
}
