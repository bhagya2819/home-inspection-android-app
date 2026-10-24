package com.griha.inspection.feature.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.griha.inspection.GrihaApp
import com.griha.inspection.R
import kotlinx.coroutines.launch

/**
 * The developer lock (see AppLock): replaces the whole app, signed in or not. [message] is the
 * server's text from `griha-api lock "..."`. Nothing behind it is deleted.
 */
@Composable
fun LockedScreen(message: String) {
    val app = LocalContext.current.applicationContext as GrihaApp
    val scope = rememberCoroutineScope()
    var checking by rememberSaveable { mutableStateOf(false) }
    var stillLocked by rememberSaveable { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, modifier = Modifier.padding(top = 48.dp).size(120.dp))
            Text("App locked", style = MaterialTheme.typography.headlineSmall)
            Text(
                message.ifBlank { "This app has been locked. Please contact your developer." },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                "Your inspections are kept safely on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = {
                    checking = true
                    scope.launch {
                        stillLocked = app.container.refreshAppLock()
                        checking = false
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Check again")
            }
            if (stillLocked && !checking) {
                Text("Still locked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
