package com.aiguru.android_file_encryption.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aiguru.android_file_encryption.security.HybridPQCrypto
import com.aiguru.android_file_encryption.storage.SafFileInfo
import com.aiguru.android_file_encryption.storage.SafStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real vault browser over SAF: lists the remembered vault location (Drive/OneDrive/
 * local/anything), lets the user add files (PQ-encrypted before writing) and open
 * them (PQ-decrypted after reading).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultBrowserScreen(
    saf: SafStorageManager,
    pq: HybridPQCrypto,
    launchFilePicker: ((android.net.Uri?) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<SafFileInfo>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // reload listing whenever tick changes
    LaunchedEffect(refreshTick) {
        busy = true
        files = withContext(Dispatchers.IO) { saf.list().filter { !it.isFolder && it.name.endsWith(".qvault") } }
        busy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault: ${saf.locationName() ?: "?"}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                launchFilePicker { uri ->
                    if (uri == null) return@launchFilePicker
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            // read → PQ-encrypt → save into vault location
                            val raw = saf.read(uri)
                            val name = queryName(saf, uri) ?: "file_${System.currentTimeMillis()}"
                            saf.save("$name.qvault", pq.hybridEncrypt(raw))
                            withContext(Dispatchers.Main) {
                                onShowToast("Encrypted & stored: $name")
                                refreshTick++
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { onShowToast("Failed: ${e.message}") }
                        } finally {
                            withContext(Dispatchers.Main) { busy = false }
                        }
                    }
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add File")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (files.isEmpty() && !busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No encrypted files yet.\nTap + to add one — it's encrypted before it leaves the device.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(files) { f ->
                        Card(onClick = {
                            busy = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val plain = pq.hybridDecrypt(saf.read(f.uri))
                                    shareDecrypted(saf, f.name.removeSuffix(".qvault"), plain)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { onShowToast("Decrypt failed: ${e.message}") }
                                } finally {
                                    withContext(Dispatchers.Main) { busy = false }
                                }
                            }
                        }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔒", style = MaterialTheme.typography.headlineMedium)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name.removeSuffix(".qvault"), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "QVAULT · ${formatSize(f.size)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Vault metadata (kept for ViewModels / future multi-vault support). */
data class VaultInfo(
    val id: String,
    val name: String,
    val provider: String,
    val isLocked: Boolean = true
)

/** Write decrypted bytes to app cache and fire a share/view intent. */
private fun shareDecrypted(saf: SafStorageManager, name: String, plain: ByteArray) {
    // SAF has no cache sharing; write into cacheDir and share via FileProvider-less stream
    val ctxFile = java.io.File(saf.context().cacheDir, name)
    ctxFile.writeBytes(plain)
    val uri = androidx.core.content.FileProvider.getUriForFile(
        saf.context(), saf.context().packageName + ".fileprovider", ctxFile
    )
    val intent = Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, guessMime(name))
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    saf.context().startActivity(intent)
}

private fun guessMime(name: String): String = when {
    name.endsWith(".pdf") -> "application/pdf"
    name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
    name.endsWith(".png") -> "image/png"
    name.endsWith(".mp4") -> "video/mp4"
    name.endsWith(".txt") || name.endsWith(".md") -> "text/plain"
    else -> "application/octet-stream"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private fun queryName(saf: SafStorageManager, uri: android.net.Uri): String? = try {
    saf.context().contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
} catch (e: Exception) { null }

private typealias Intent = android.content.Intent