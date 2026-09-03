package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * Vault passphrase setup screen (first run / key operations).
 * Enforces strength, asks twice, and warns that the passphrase is UNRECOVERABLE.
 * The escrow file is the only backup — and it's protected BY this passphrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphraseSetupScreen(
    title: String,
    confirmLabel: String = "Set passphrase",
    onBack: () -> Unit,
    onConfirm: (CharArray) -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var acked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val strengthProblem = com.aiguru.android_file_encryption.security.PassphraseKDF.strengthProblem(pass)
    val matchOk = pass.isNotEmpty() && pass == confirm
    val canSubmit = strengthProblem == null && matchOk && acked && pass.length >= 8 && !busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Your files are wrapped by two keys: this device and this passphrase. " +
                    "Keep the passphrase — it is your recovery key if this phone is lost or formatted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                isError = strengthProblem != null && pass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            )
            if (strengthProblem != null && pass.isNotEmpty()) {
                Text(
                    strengthProblem,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("Confirm passphrase") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                isError = confirm.isNotEmpty() && !matchOk,
                modifier = Modifier.fillMaxWidth()
            )
            if (confirm.isNotEmpty() && !matchOk) {
                Text("Passphrases don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = acked, onCheckedChange = { acked = it })
                Text(
                    "I understand this passphrase is NOT recoverable. " +
                        "Without it, the escrow backup is useless if this device is lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    busy = true
                    try {
                        onConfirm(pass.toCharArray())
                    } finally {
                        busy = false
                    }
                },
                enabled = strengthProblem == null && matchOk && acked && !busy && pass.length >= 8,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(confirmLabel)
            }
        }
    }
}