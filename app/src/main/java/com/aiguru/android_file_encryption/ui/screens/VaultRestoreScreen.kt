package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Restore on a new device" screen: reads the .vaultkey escrow from the picked
 * vault folder and rebuilds the device vault keys from the user's passphrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultRestoreScreen(
    escrowFound: Boolean,
    locationName: String?,
    onBack: () -> Unit,
    onRestore: (CharArray, (String?) -> Unit) -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!escrowFound) {
                Text(
                    "⚠️ No .vaultkey escrow file found in \"${locationName ?: "this location"}\".\n\n" +
                        "Without the escrow file (or the original phone's keys), these encrypted " +
                        "files cannot be decrypted by anyone. Pick the folder that contains it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            } else {
                Text(
                    "Enter the vault passphrase to restore your keys on this device. " +
                        "After restoring, all encrypted files in this location become readable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; error = null },
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
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        if (pass.isEmpty()) return@Button
                        busy = true
                        onRestore(pass.toCharArray()) { fp ->
                            busy = false
                            if (fp != null) {
                                onShowToast("Vault restored ✓")
                            } else {
                                error = "Wrong passphrase or corrupted escrow"
                            }
                        }
                    },
                    enabled = pass.length >= 8 && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restore vault keys")
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Deriving key (Argon2id) — this takes a moment by design…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}