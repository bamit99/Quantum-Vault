package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Delete vault — two-tier, per the recovery contract:
 *
 *  UNLINK (safe): forget this device's copy of the vault. Clears the
 *  remembered location and wipes local key material (sealed PQ privates).
 *  Files + .vaultkey stay in the cloud folder → vault remains fully
 *  recoverable via Restore. Irreversible ONLY on this device.
 *
 *  DESTROY (destructive): additionally deletes .vaultkey and every .qvault
 *  file from the vault folder. Ciphertext without keys is noise forever —
 *  unrecoverable by anyone, including you. Gated by typing DESTROY.
 */
@Composable
fun DeleteVaultScreen(
    locationName: String?,
    onBack: () -> Unit,
    onUnlinked: () -> Unit,
    onDestroyed: () -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    var mode by remember { mutableStateOf("choose") } // choose | unlink_confirm | destroy_confirm
    var confirmText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Delete vault", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Location: ${locationName ?: "?"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        when (mode) {
            "choose" -> {
                OutlinedButton(
                    onClick = { mode = "unlink_confirm" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Unlink from this device") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { mode = "destroy_confirm" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Destroy vault permanently") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }

            "unlink_confirm" -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Unlink this device?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This forgets the vault location and wipes this device's local keys.\n\n" +
                            "Your files and .vaultkey stay in the vault folder — nothing in the cloud is touched. " +
                            "You can restore this vault any time with your passphrase.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { busy = true; onUnlinked() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Yes, unlink") }
                TextButton(onClick = { mode = "choose" }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }

            "destroy_confirm" -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("⚠ Destroy vault", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This deletes .vaultkey and ALL encrypted (.qvault) files from " +
                            "'${locationName ?: "the vault folder"}'.\n\n" +
                            "Encrypted data without keys is unrecoverable — by you, by us, by anyone. " +
                            "There is no undo and no support call that can reverse this.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    label = { Text("Type DESTROY to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { busy = true; onDestroyed() },
                    enabled = confirmText == "DESTROY" && !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Destroy forever") }
                TextButton(onClick = { mode = "choose" }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}