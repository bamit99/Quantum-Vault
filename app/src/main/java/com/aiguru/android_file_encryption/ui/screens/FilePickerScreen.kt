package com.aiguru.android_file_encryption.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiguru.android_file_encryption.storage.FileInfo
import com.aiguru.android_file_encryption.ui.viewmodels.FileViewModel
import com.aiguru.android_file_encryption.utils.formatFileSize
import com.aiguru.android_file_encryption.utils.formatTimestamp
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    viewModel: FileViewModel = viewModel(),
    onFileSelected: (Uri) -> Unit,
    onNavigateToEncryptedFiles: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var showEncryptionDialog by remember { mutableStateOf(false) }
    var encryptionPassword by remember { mutableStateOf("") }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFile = it
            onFileSelected(it)
            showEncryptionDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Cloud Storage") },
                actions = {
                    IconButton(onClick = onNavigateToEncryptedFiles) {
                        Icon(Icons.Default.Lock, contentDescription = "Encrypted Files")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch("*/*") }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Select File")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Storage Stats Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Storage Statistics",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Space:")
                        Text(formatFileSize(uiState.storageStats.totalSpace))
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Available:")
                        Text(formatFileSize(uiState.storageStats.usableSpace))
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Encrypted Data:")
                        Text(formatFileSize(uiState.storageStats.encryptedDataSize))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recent Files
            Text(
                text = "Recent Encrypted Files",
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.recentFiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No encrypted files yet.\nTap + to select a file for encryption.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.recentFiles) { file ->
                        FileItem(
                            fileInfo = file,
                            onClick = { /* Handle file click */ }
                        )
                    }
                }
            }
        }
    }

    // Encryption Dialog
    if (showEncryptionDialog) {
        AlertDialog(
            onDismissRequest = { showEncryptionDialog = false },
            title = { Text("Encrypt File") },
            text = {
                Column {
                    Text("Enter password for encryption:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = encryptionPassword,
                        onValueChange = { encryptionPassword = it },
                        label = { Text("Password") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedFile?.let { uri ->
                            viewModel.encryptAndUploadFile(uri, encryptionPassword)
                        }
                        showEncryptionDialog = false
                        encryptionPassword = ""
                    }
                ) {
                    Text("Encrypt")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEncryptionDialog = false
                        encryptionPassword = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error Snackbar
    uiState.errorMessage?.let { error ->
        LaunchedEffect(error) {
            // Show error message
            Timber.e("Error: $error")
        }
    }
}

@Composable
fun FileItem(
    fileInfo: FileInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = "File",
                modifier = Modifier.size(40.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileInfo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = "${formatFileSize(fileInfo.size)} • ${formatTimestamp(fileInfo.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(onClick = { /* Show file options */ }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options"
                )
            }
        }
    }
}

// Utility functions moved to FormatUtils.kt