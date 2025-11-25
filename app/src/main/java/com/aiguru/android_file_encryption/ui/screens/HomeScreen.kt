package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiguru.android_file_encryption.ui.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddAccount: () -> Unit,
    onVaultSelected: (String) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val vaults by viewModel.vaults.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Cloud Storage") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccount) {
                Icon(Icons.Default.Add, contentDescription = "Add Cloud Account")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (vaults.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "No vaults yet",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Tap the + button to add a cloud account and create your first vault",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Vault list
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vaults) { vault ->
                        VaultCard(
                            vault = vault,
                            onClick = { onVaultSelected(vault.id) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultCard(
    vault: VaultInfo,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vault.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = vault.provider,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (vault.isLocked) "🔒" else "🔓",
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

data class VaultInfo(
    val id: String,
    val name: String,
    val provider: String,
    val isLocked: Boolean = true
)