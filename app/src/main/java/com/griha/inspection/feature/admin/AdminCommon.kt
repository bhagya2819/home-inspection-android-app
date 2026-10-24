package com.griha.inspection.feature.admin

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.griha.inspection.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/** Admin screens are online-only views of server data. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> if (e.status == 403) "Only administrators can do this." else e.message ?: e.code
    is IOException -> "No connection to the Griha server. Admin screens need the internet."
    else -> e.message ?: e.javaClass.simpleName
}

/** A screen's server data plus a one-line message for the last action (saved, failed, …). */
abstract class LoadingViewModel<T> : ViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state = _state.asStateFlow()
    val message = MutableStateFlow<String?>(null)

    protected abstract suspend fun fetch(): T

    fun refresh() = viewModelScope.launch {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        _state.value = try {
            Load.Ready(fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(friendlyError(e))
        }
    }

    /** Runs a server action, then reloads. [done] is shown on success. */
    protected fun act(done: String?, action: suspend () -> Unit) = actFor { action(); done }

    /** Like [act], showing the message the action returns. */
    protected fun actFor(action: suspend () -> String?) = viewModelScope.launch {
        message.value = try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            friendlyError(e)
        }
        refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScaffold(
    title: String,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    floating: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
        floatingActionButton = floating,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Loading spinner / error with retry / the content. */
fun <T> LazyListScope.loadItems(state: Load<T>, retry: () -> Unit, content: LazyListScope.(T) -> Unit) {
    when (state) {
        Load.Loading -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        is Load.Failed -> item(key = "failed") {
            InfoCard {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = retry) { Text("Try again") }
            }
        }
        is Load.Ready -> content(state.value)
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun MessageLine(message: String?) {
    if (message != null) InfoCard { Text(message, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun Tag(text: String, background: Color = MaterialTheme.colorScheme.surfaceVariant, foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Decimal units, like the phone's own storage settings. */
fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "unknown"
    bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.0f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

fun relativeTime(millis: Long?): String =
    if (millis == null || millis <= 0) "never"
    else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun dateOf(millis: Long?): String =
    if (millis == null || millis <= 0) "—"
    else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))
