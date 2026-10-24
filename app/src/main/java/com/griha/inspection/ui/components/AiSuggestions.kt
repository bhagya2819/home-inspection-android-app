package com.griha.inspection.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Marks AI-written text everywhere it appears. */
const val AI_MARK = "✦"

/**
 * One AI suggestion for a field: the text, a cross to discard and a tick to accept. Nothing is
 * written until the tick; the AI can be wrong, and the inspector signs the report.
 */
@Composable
fun SuggestionCard(text: String, onAccept: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$AI_MARK Suggestion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                OutlinedIconButton(onClick = onDiscard, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Discard suggestion", modifier = Modifier.size(18.dp))
                }
                FilledIconButton(
                    onClick = onAccept,
                    modifier = Modifier.padding(start = 4.dp).size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Accept suggestion", modifier = Modifier.size(18.dp))
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/** The "Improve with AI" button, with Accept all / Discard all while suggestions are open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiBar(
    canAsk: Boolean,
    busy: Boolean,
    pending: Int,
    error: String?,
    onAsk: () -> Unit,
    onAcceptAll: () -> Unit,
    onDiscardAll: () -> Unit,
    label: String = "Improve with AI",
    hint: String = "Write what you saw in the site note, in any language. AI drafts the report text below; you choose what to keep. " +
        "The client's name, phone and address are removed before sending.",
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            FilledTonalButton(onClick = onAsk, enabled = canAsk && !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Thinking…", modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text("$AI_MARK  $label")
                }
            }
            if (pending > 1) {
                TextButton(onClick = onAcceptAll) { Text("Accept all") }
                TextButton(onClick = onDiscardAll) { Text("Discard all") }
            }
        }
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            pending == 0 && !busy -> Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
