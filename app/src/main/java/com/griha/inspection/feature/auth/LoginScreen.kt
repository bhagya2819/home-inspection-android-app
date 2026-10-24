package com.griha.inspection.feature.auth

import com.griha.inspection.feature.admin.MIN_PASSWORD

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.griha.inspection.GrihaApp
import com.griha.inspection.R
import com.griha.inspection.core.network.ApiException
import kotlinx.coroutines.launch

/** Employee code + password (PRD §5.1). The same login works offline for 7 days (see AuthRepository). */
@Composable
fun LoginScreen(message: String?) {
    val auth = (LocalContext.current.applicationContext as GrihaApp).container.authRepository
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || code.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            auth.login(code, password).onFailure { e ->
                error = when ((e as? ApiException)?.code) {
                    "BAD_CREDENTIALS" -> "Wrong employee code or password."
                    "ACCOUNT_DISABLED" -> "This account is disabled. Contact your administrator."
                    "DEVICE_REVOKED" -> "This phone has been blocked. Contact your administrator."
                    "TOO_MANY_ATTEMPTS" -> "Too many attempts. Wait a minute and try again."
                    else -> e.message ?: "Sign-in failed."
                }
            }
            password = ""
            busy = false
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, modifier = Modifier.padding(top = 24.dp).size(120.dp))
            Text("Griha Home Inspection", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Sign in with your employee code. After that, the app also works without internet for 7 days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(
                code, { code = it },
                label = { Text("Employee code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::submit, enabled = !busy && code.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
        }
    }
}

/** Shown after sign-in while the server requires a new password (first login, admin reset). */
@Composable
fun ChangePasswordScreen(forced: Boolean, onDone: () -> Unit = {}) {
    val auth = (LocalContext.current.applicationContext as GrihaApp).container.authRepository
    val scope = rememberCoroutineScope()
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val valid = current.isNotEmpty() && new.length >= MIN_PASSWORD && new == repeat

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Set a new password", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp))
            Text(
                if (forced) "Your password was set by an administrator. Choose your own before continuing (needs internet)."
                else "At least $MIN_PASSWORD characters. Other phones signed in as you will be signed out.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                Triple("Current password", current) { v: String -> current = v },
                Triple("New password ($MIN_PASSWORD+ characters)", new) { v: String -> new = v },
                Triple("Repeat new password", repeat) { v: String -> repeat = v },
            ).forEach { (label, value, set) ->
                OutlinedTextField(
                    value, set,
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (repeat.isNotEmpty() && new != repeat) Text("The new passwords don't match.", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        auth.changePassword(current, new)
                            .onSuccess { onDone() }
                            .onFailure { e ->
                                error = when ((e as? ApiException)?.code) {
                                    "BAD_CREDENTIALS" -> "The current password is wrong."
                                    "WEAK_PASSWORD" -> "Use at least $MIN_PASSWORD characters."
                                    null -> "Can't reach the server. Connect to the internet and try again."
                                    else -> e.message
                                }
                            }
                        busy = false
                    }
                },
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save password") }
            if (forced) TextButton(onClick = { scope.launch { auth.logout() } }) { Text("Sign out") }
            else TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}
