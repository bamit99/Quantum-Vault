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
 * Vault location chooser (simplified per amitb, 2026-09-04).
 * Two options only:
 *   - Main   → pinned system SAF picker (dynamic OEM-agnostic component pin,
 *              destination jump via EXTRA_INITIAL_URI, honors user default
 *              only when it IS the system picker)
 *   - Browse all → the USER'S DEFAULT handler, whatever they set ("Files"
 *              is the system SAF default; their file explorer can be set
 *              as default like Gmail's picker). Never pinned, never forced
 *              into a chooser — resolution is Android's own, driven by the
 *              user's explicit 'Always' choices.
 */
data class ProviderOption(val glyph: String, val title: String, val subtitle: String, val packageName: String?)

/** Two fixed options: Main (system picker) + Browse all (user default). */
fun detectProviders(ctx: Context): List<ProviderOption> = listOf(
    ProviderOption("🔐", "Main vault location", "System picker — jumps into Drive/Dropbox when installed", "system"),
    ProviderOption("🗂", "Browse all", "Open with your default file explorer (like Gmail does)", null)
)

/**
 * Quick-choose sheet shown before the system picker. Tapping a provider
 * jumps into that provider's root in the SAF documents UI
 * (EXTRA_INITIAL_URI is a hint honored by the provider app).
 */
@Composable
fun ProviderChooserSheet(
    options: List<ProviderOption>,
    onPick: (ProviderOption) -> Unit,
    onDismiss: () -> Unit,
    systemPickerAvailable: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where should your vault live?") },
        text = {
            LazyColumn {
                if (!systemPickerAvailable) {
                    item {
                        Text(
                            "Note: this device has no system file picker — your default file explorer will open instead. Make sure it integrates the cloud drive you pick, or it won't appear.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
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

/**
 * Intent that opens the SAF picker pre-seeded at a provider root (best-effort).
 *
 * Anti-hijack: third-party file explorers (Solid Explorer, OEM My Files, …)
 * register as handlers for ACTION_OPEN_DOCUMENT_TREE, show their own browser,
 * ignore EXTRA_INITIAL_URI, and may not expose all cloud roots (Solid Explorer
 * integrates Drive but not OneDrive). We therefore resolve the device's SYSTEM
 * picker dynamically (queryIntentActivities + FLAG_SYSTEM) and pin the intent
 * to it BY COMPONENT — OEM-agnostic, works on Pixel/OnePlus/Samsung/Xiaomi
 * without hardcoding package names. When no system handler resolves
 * (or resolution fails) we fall back to the implicit intent; the caller can
 * detect that case via [systemPickerAvailable] and show guidance.
 *
 * Returns null never — worst case is the implicit intent unchanged.
 */
fun providerPickerIntent(option: ProviderOption, ctx: Context? = null): Intent {
    val base = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }
    val pkg = option.packageName
    // "Browse all" (packageName == null) → user's DEFAULT handler, exactly like
    // Gmail's attachment picker: whatever the user set 'Always' for. Never
    // pinned, never chooser-wrapped — Android's own resolution, user-driven.
    if (pkg == null) return base
    // "Main" → best-effort initial-URI hints; provider may ignore them.
    // NOTE: these point at the provider's SAF DocumentsProvider authority —
    // NOT the vendor app's own package-internal provider. Drive's storage
    // authority is com.google.android.apps.docs.storage (NOT externalstorage,
    // which is local flash and was the old wrong hint).
    val hint = when {
        pkg == "system" -> null // no single provider hint; land on the root list
        pkg == "com.google.android.apps.docs" -> "content://com.google.android.apps.docs.storage/document/root"
        pkg == "com.microsoft.skydrive" -> "content://com.microsoft.skydrive.content.storage_access_provider/document/root"
        pkg == "com.dropbox.android" -> "content://com.dropbox.android.provider/document/root"
        else -> null
    }
    if (hint != null) {
        try { base.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(hint)) } catch (e: Exception) { /* hint only */ }
    }
    if (ctx == null) return base

    // Resolve the SYSTEM handler for this intent dynamically (no hardcoded
    // package names — every OEM ships its own DocumentsUI fork under a
    // different package). Query with MATCH_SYSTEM_ONLY so Play-installed
    // file managers (Solid Explorer etc.) never enter the candidate list.
    // Package visibility: these system packages are visible without <queries>
    // (system apps with intent filters are always visible for resolution).
    try {
        val resolved = ctx.packageManager.queryIntentActivities(base, PackageManager.MATCH_SYSTEM_ONLY)
        val candidates = resolved.filter {
            val flags = it.activityInfo.applicationInfo.flags
            (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        }
        val best = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates[0]
            else ->
                // OnePlus 12R reality: BOTH com.google.android.documentsui AND
                // com.oneplus.filemanager are system handlers for this intent.
                // Order of the resolver list is not guaranteed → scan by
                // PRIORITY PASSES, not a per-item OR chain (which let a
                // list-earlier "filemanager" beat a later "documentsui").
                // True SAF pickers (documentsui family) honor the destination
                // hint and expose all cloud roots; OEM file managers that
                // merely register the intent are exactly the hijackers.
                candidates.firstOrNull { it.activityInfo.packageName.contains("documentsui") }
                    ?: candidates.firstOrNull { it.activityInfo.packageName.contains("documents") }
                    ?: candidates.firstOrNull { it.isDefault }
                    ?: candidates.first()
        }
        if (best != null) {
            base.setClassName(best.activityInfo.packageName, best.activityInfo.name)
        }
    } catch (e: Exception) {
        // resolution unavailable → implicit intent; guidance path covers this
    }
    return base
}

/**
 * True when the ONLY resolvable handler for ACTION_OPEN_DOCUMENT_TREE is a
 * non-system app (user's file manager is default and no system picker
 * exists) — caller should surface guidance that cloud roots may be missing
 * in that browser.
 */
fun systemPickerAvailable(ctx: Context): Boolean = try {
    val pm = ctx.packageManager
    val resolved = pm.queryIntentActivities(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ),
        PackageManager.MATCH_SYSTEM_ONLY
    )
    resolved.isNotEmpty()
} catch (e: Exception) { true }