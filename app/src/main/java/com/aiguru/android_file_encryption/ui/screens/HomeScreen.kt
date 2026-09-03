package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Home screen driven directly by SAF state (no ViewModel needed — the vault is
 * the remembered SAF tree): shows a setup card when no location is picked, or
 * the single "Main Vault" entry once the user has chosen a folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hasLocation: Boolean,
    locationName: String?,
    onPickLocation: () -> Unit,
    onRestoreRequested: () -> Unit = {},
    onVaultSelected: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Aiguru Secure Vault") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onPickLocation) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Choose vault location")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (!hasLocation) {
                // Setup state: guide the user to pick a location
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("🔐", style = MaterialTheme.typography.displayLarge)
                        Text(
                            "Set up your vault",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "Pick a folder — Google Drive, OneDrive, SD card or local.\n" +
                                "Files are encrypted on-device before they leave.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onPickLocation) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose location")
                        }
                    }
                }
            } else {
                // Vault ready
                Card(onClick = onVaultSelected, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Main Vault", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Location: $locationName — tap to open",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onPickLocation, modifier = Modifier.fillMaxWidth()) {
                    Text("Change location")
                }
                TextButton(onClick = onRestoreRequested, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore vault on this device…")
                }
            }
        }
    }
}