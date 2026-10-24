package com.homeinspection.app.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homeinspection.app.core.retention.RetentionOverview
import com.homeinspection.app.core.retention.RetentionSettings
import com.homeinspection.app.core.retention.Verdict
import com.homeinspection.app.feature.admin.dateOf
import com.homeinspection.app.feature.admin.formatBytes

/**
 * Phone storage and the 30-day cleanup (PRD §5.9): what is on the phone, the policy in plain words,
 * the switch that records the user's consent, and per inspection why originals are still kept.
 */
@Composable
fun PhoneStorageCard(
    overview: RetentionOverview?,
    freeBytes: Long,
    settings: RetentionSettings,
    enabled: Boolean,
    lastResult: String?,
    cleaning: Boolean,
    onEnable: (Boolean) -> Unit,
    onCleanNow: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val policy = "Original photos are removed from this phone ${settings.retentionDays} days after the report is finalized " +
        "and every photo is backed up — and only once your admin has saved them in a verified export. " +
        "The app checks the server's copy again right before removing anything. Removed originals can't be " +
        "brought back on this phone except by downloading them from the server while it still has them."

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Phone storage", style = MaterialTheme.typography.titleMedium)
            if (overview == null) {
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${overview.localPhotos} original photos on this phone (${formatBytes(overview.localBytes)}) · ${formatBytes(freeBytes)} free",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (overview.removedPhotos > 0) Small("${overview.removedPhotos} originals already cleared (backed up on the server or in an export).")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic cleanup", style = MaterialTheme.typography.titleSmall)
                    Small(if (enabled) "On · policy ${settings.policyVersion}" else "Off · nothing is removed from this phone")
                }
                Switch(checked = enabled, onCheckedChange = { if (it) confirm = true else onEnable(false) })
            }
            Small(policy)
            overview?.let { o ->
                val ready = o.eligible
                if (ready.isNotEmpty()) {
                    Text(
                        "Ready to clear: ${ready.sumOf { it.localPhotos }} photos (${formatBytes(ready.sumOf { it.localBytes })})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(onClick = onCleanNow, enabled = enabled && ready.isNotEmpty() && !cleaning) {
                    Text(if (cleaning) "Clearing…" else "Clear now")
                }
                o.items.filter { it.verdict is Verdict.Waiting }.take(3).forEach {
                    Small("${it.clientName}: ${it.localPhotos} photos can be cleared from ${dateOf((it.verdict as Verdict.Waiting).eligibleAt)}")
                }
                val blocked = o.blocked
                if (blocked.isNotEmpty()) {
                    Text("Kept on this phone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    (if (showAll) blocked else blocked.take(4)).forEach { item ->
                        val reasons = (item.verdict as Verdict.Blocked).reasons.joinToString("; ") { it.text }
                        Small("${item.clientName} (${item.localPhotos} photos): $reasons")
                    }
                    if (blocked.size > 4 && !showAll) TextButton(onClick = { showAll = true }) { Text("Show all ${blocked.size}") }
                }
            }
            lastResult?.let { Small("Last cleanup: $it") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Turn on automatic cleanup?") },
            text = { Text(policy) },
            confirmButton = { TextButton(onClick = { confirm = false; onEnable(true) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
