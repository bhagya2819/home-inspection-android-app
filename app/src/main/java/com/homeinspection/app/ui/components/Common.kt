package com.homeinspection.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homeinspection.app.core.model.Severity
import com.homeinspection.app.ui.theme.severityColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun SeverityBadge(severity: Severity, modifier: Modifier = Modifier) {
    val tint = severityColors(severity)
    Surface(color = tint.container, contentColor = tint.content, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(severity.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

enum class SaveState { Idle, Saving, Saved }

@Composable
fun SaveStateLabel(state: SaveState, modifier: Modifier = Modifier) {
    val text = when (state) {
        SaveState.Idle -> return
        SaveState.Saving -> "Saving…"
        SaveState.Saved -> "Saved on this phone"
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/**
 * Debounced auto-save (PRD §5.4 "auto-save edits locally; show save state").
 *
 * Runs in a scope that outlives the screen (the app scope), so pressing Back right after typing
 * still saves. One pending write per key; a newer edit replaces the older pending one.
 */
class AutoSaver(private val scope: CoroutineScope, private val delayMs: Long = 400) {
    private val pending = HashMap<String, Job>()
    private val _state = MutableStateFlow(SaveState.Idle)
    val state = _state.asStateFlow()

    fun schedule(key: String, save: suspend () -> Unit) {
        pending[key]?.cancel()
        _state.value = SaveState.Saving
        // Main thread: keeps the pending map single-threaded. Room's suspend writes are main-safe.
        pending[key] = scope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            save()
            pending.remove(key)
            if (pending.isEmpty()) _state.value = SaveState.Saved
        }
    }
}
