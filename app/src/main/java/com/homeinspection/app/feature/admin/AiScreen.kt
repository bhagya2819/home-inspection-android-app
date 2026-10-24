package com.homeinspection.app.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeinspection.app.core.admin.AdminRepository
import com.homeinspection.app.core.admin.AiInfo
import com.homeinspection.app.core.ai.AiRepository
import com.homeinspection.app.ui.homeInspectionViewModel

class AiAdminViewModel(private val admin: AdminRepository, private val ai: AiRepository) : LoadingViewModel<AiInfo>() {
    override suspend fun fetch() = admin.aiInfo()

    fun setKey(key: String) = act("Key checked with DeepSeek and saved. Inspectors can use AI suggestions now.") {
        admin.setAiKey(key)
        ai.refresh()
    }

    fun removeKey() = act("Key removed. AI suggestions are off for everyone.") {
        admin.removeAiKey()
        ai.refresh()
    }

    fun setLimit(limit: Int) = act("Daily limit saved.") {
        admin.setAiDailyLimit(limit)
        ai.refresh()
    }
}

private enum class AiDialog { NONE, KEY, REMOVE, LIMIT }

/**
 * The one DeepSeek key for everyone. It goes to the Home Inspection server, which checks it with DeepSeek and
 * stores it encrypted; it is never shown again (only its last four characters) and never reaches
 * inspectors' phones. AI requests go phone → Home Inspection server → DeepSeek.
 */
@Composable
fun AiAdminScreen(onBack: () -> Unit) {
    val vm = homeInspectionViewModel { AiAdminViewModel(it.container.adminRepository, it.container.aiRepository) }
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(AiDialog.NONE) }

    AdminScaffold("AI assistant", onBack, onRefresh = { vm.refresh() }) {
        item(key = "message") { MessageLine(message) }
        loadItems(state, retry = { vm.refresh() }) { info ->
            item(key = "key") {
                InfoCard {
                    Text("DeepSeek API key", style = MaterialTheme.typography.titleSmall)
                    when {
                        !info.available -> Text(
                            "The server isn't set up to store a key yet (HOME_INSPECTION_SECRET_KEY is missing).",
                            color = MaterialTheme.colorScheme.error,
                        )
                        info.keySet -> {
                            Text("Key set  ••••${info.keyLast4}", style = MaterialTheme.typography.bodyLarge)
                            Secondary("Added by ${info.keySetBy?.ifBlank { null } ?: "an admin"} on ${dateOf(info.keySetAt)} · model ${info.model}")
                        }
                        else -> Text("No key yet. AI suggestions are off for everyone.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Secondary("Stored encrypted on the Home Inspection server and never shown again. Phones never receive it.")
                    if (info.available) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { dialog = AiDialog.KEY }) { Text(if (info.keySet) "Replace key" else "Add key") }
                            if (info.keySet) OutlinedButton(onClick = { dialog = AiDialog.REMOVE }) { Text("Remove") }
                        }
                    }
                }
            }
            item(key = "usage") {
                InfoCard {
                    Text("Use this month", style = MaterialTheme.typography.titleSmall)
                    Text("${info.monthRequests} requests · ${info.monthTokens} tokens", style = MaterialTheme.typography.bodyMedium)
                    info.users.forEach { Secondary("${it.name}: ${it.requests}") }
                    Text(
                        if (info.dailyLimit == 0) "AI is turned off (daily limit 0)." else "Each person can make ${info.dailyLimit} AI requests a day.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Secondary("The limit caps the bill if a phone is lost. Failed requests don't count. A finding suggestion costs a few paise at DeepSeek's October 2026 prices.")
                    OutlinedButton(onClick = { dialog = AiDialog.LIMIT }) { Text("Change limit") }
                }
            }
            item(key = "privacy") {
                InfoCard {
                    Text("What is sent to DeepSeek", style = MaterialTheme.typography.titleSmall)
                    Secondary(
                        "Room names, the property type, and the finding text (site note, title, observation, implication, " +
                            "recommendation, photo captions). Never the client's name, phone number or address, and no photos. " +
                            "Ask inspectors not to type client names into notes or captions."
                    )
                }
            }
        }
    }

    when (dialog) {
        AiDialog.LIMIT -> (state as? Load.Ready)?.value?.let { info ->
            LimitDialog(info.dailyLimit, onDismiss = { dialog = AiDialog.NONE }) { dialog = AiDialog.NONE; vm.setLimit(it) }
        }
        AiDialog.KEY -> KeyDialog(onDismiss = { dialog = AiDialog.NONE }) { dialog = AiDialog.NONE; vm.setKey(it) }
        AiDialog.REMOVE -> AlertDialog(
            onDismissRequest = { dialog = AiDialog.NONE },
            title = { Text("Remove the AI key?") },
            text = { Text("AI suggestions stop working for everyone until a new key is added.") },
            confirmButton = { TextButton(onClick = { dialog = AiDialog.NONE; vm.removeKey() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = AiDialog.NONE }) { Text("Cancel") } },
        )
        else -> {}
    }
}

@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DeepSeek API key") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the key from platform.deepseek.com → API keys. After saving it can't be viewed again, only replaced.")
                OutlinedTextField(
                    key, { key = it.trim() },
                    label = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = key.length >= 16, onClick = { onSave(key) }) { Text("Check and save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LimitDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val n = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI requests per person per day") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(4) },
                label = { Text("0–1000 (0 turns AI off)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = n != null && n in 0..1000, onClick = { onSave(n!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
