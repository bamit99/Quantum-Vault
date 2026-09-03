package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Vault unlock gate — shown when the app opens with a configured vault.
 * Verification itself runs in MainActivity (Argon2id off-main against .vaultkey);
 * this composable is the UI: passphrase entry, progress, error display.
 */
@Composable
fun VaultUnlockScreen(
    locationName: String?,
    busy: Boolean,
    error: String?,
    onPassphraseSubmit: (CharArray) -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔒", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text("Vault locked", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Location: ${locationName ?: "?"}\nEnter your vault passphrase to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Vault passphrase") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(
                        if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (show) "Hide" else "Show"
                    )
                }
            },
            isError = error != null,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Button(
            onClick = { onPassphraseSubmit(pass.toCharArray()) },
            enabled = pass.length >= 8 && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Unlock")
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Deriving key (Argon2id) — takes a moment…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Your passphrase is verified against the vault escrow each session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}