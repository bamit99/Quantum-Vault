package com.aiguru.android_file_encryption.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    onProviderSelected: (CloudProvider) -> Unit,
    onBack: () -> Unit
) {
    val providers = listOf(
        CloudProvider.GoogleDrive,
        CloudProvider.OneDrive,
        CloudProvider.Dropbox,
        CloudProvider.Box,
        CloudProvider.iCloud
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Cloud Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "Choose a cloud storage provider",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(providers) { provider ->
                    ProviderCard(
                        provider = provider,
                        onClick = { onProviderSelected(provider) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    provider: CloudProvider,
    onClick: () -> Unit
) {
    val isEnabled = provider == CloudProvider.GoogleDrive || provider == CloudProvider.OneDrive

    Card(
        onClick = { if (isEnabled) onClick() },
        enabled = isEnabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = provider.icon,
                style = MaterialTheme.typography.displayMedium
            )
            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (!isEnabled) {
                Text(
                    text = "Coming Soon",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

enum class CloudProvider(val displayName: String, val icon: String) {
    GoogleDrive("Google Drive", "📁"),
    OneDrive("OneDrive", "☁️"),
    Dropbox("Dropbox", "📦"),
    Box("Box", "📦"),
    iCloud("iCloud", "☁️")
}