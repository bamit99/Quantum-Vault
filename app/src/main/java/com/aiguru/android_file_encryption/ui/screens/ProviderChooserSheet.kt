package com.aiguru.android_file_encryption.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Provider-aware vault location chooser (v4.2).
 * Lists cloud apps actually installed on this device + local storage +
 * "Browse all…" fallback. No dead options for uninstalled providers.
 */
data class ProviderOption(val glyph: String, val title: String, val subtitle: String, val packageName: String?)

/** Detect installed cloud provider apps (PackageManager, no permissions needed for these queries). */
fun detectProviders(ctx: Context): List<ProviderOption> {
    val pm = ctx.packageManager
    fun installed(pkg: String) = try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    val opts = mutableListOf<ProviderOption>()
    if (installed("com.google.android.apps.docs"))
        opts.add(ProviderOption("🟡", "Google Drive", "Files survive phone loss; syncs automatically", "com.google.android.apps.docs"))
    if (installed("com.microsoft.skydrive") || installed("com.microsoft.office.onenote"))
        opts.add(ProviderOption("🔵", "OneDrive", "Files survive phone loss; syncs automatically", "com.microsoft.skydrive"))
    if (installed("com.dropbox.android"))
        opts.add(ProviderOption("📦", "Dropbox", "Files survive phone loss; syncs automatically", "com.dropbox.android"))
    opts.add(ProviderOption("📱", "This device", "Fastest + fully offline; no off-device backup", null))
    opts.add(ProviderOption("🗂", "Browse all…", "Any folder via the system picker", null))
    return opts
}

/**
 * Quick-choose sheet shown before the system picker. Tapping a provider
 * jumps into that provider's root in the SAF documents UI
 * (EXTRA_INITIAL_URI is a hint honored by the provider app).
 */
@Composable
fun ProviderChooserSheet(
    options: List<ProviderOption>,
    onPick: (ProviderOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where should your vault live?") },
        text = {
            LazyColumn {
                items(options) { opt ->
                    Card(
                        onClick = { onPick(opt) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Text(opt.glyph, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(opt.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    opt.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Intent that opens the SAF picker pre-seeded at a provider root (best-effort). */
fun providerPickerIntent(option: ProviderOption): Intent {
    val base = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }
    val pkg = option.packageName ?: return base
    // Best-effort initial-URI hints; provider may ignore them.
    // NOTE: these point at the provider's SAF DocumentsProvider authority —
    // NOT the vendor app's own package-internal provider. Drive's storage
    // authority is com.google.android.apps.docs.storage (NOT externalstorage,
    // which is local flash and was the old wrong hint).
    val hint = when {
        pkg == "com.google.android.apps.docs" -> "content://com.google.android.apps.docs.storage/document/root"
        pkg == "com.microsoft.skydrive" -> "content://com.microsoft.skydrive.content.storage_access_provider/document/root"
        pkg == "com.dropbox.android" -> "content://com.dropbox.android.provider/document/root"
        else -> null
    }
    if (hint != null) {
        try { base.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(hint)) } catch (e: Exception) { /* hint only */ }
    }
    return base
}