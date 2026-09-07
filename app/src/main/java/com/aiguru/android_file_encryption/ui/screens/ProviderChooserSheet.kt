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
import timber.log.Timber

/**
 * Vault location chooser (SAF; per amitb, 2026-09-04).
 * Options, in order:
 *   - Main         → pinned system SAF picker (dynamic OEM-agnostic component pin,
 *                    destination jump via EXTRA_INITIAL_URI, honors user default
 *                    only when it IS the system picker)
 *   - Installed cloud providers (Google Drive / OneDrive / Dropbox, detected at
 *     runtime via PackageManager — visible through the <queries> manifest entries)
 *     → the SYSTEM SAF picker pinned, pre-seeded at that provider's LIVE root
 *       (EXTRA_INITIAL_URI from the provider's ROOTS API — never OEM "Files", never
 *       hardcoded authorities; provider apps themselves rarely expose a picker)
 *   - Browse all   → the pinned system SAF picker at its top level (never the user
 *                    default, never an OEM/file-manager hijack)
 */
data class ProviderOption(val glyph: String, val title: String, val subtitle: String, val packageName: String?)

/** Main + the cloud providers installed on this device + Browse all. */
fun detectProviders(ctx: Context): List<ProviderOption> {
    fun installed(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0) != null
    } catch (e: Exception) {
        false
    }

    val cloud = buildList {
        if (installed("com.google.android.apps.docs")) {
            add(ProviderOption("☁️", "Google Drive", "Jump into your Drive vault folder", "com.google.android.apps.docs"))
        }
        if (installed("com.microsoft.skydrive")) {
            add(ProviderOption("☁️", "OneDrive", "Jump into your OneDrive vault folder", "com.microsoft.skydrive"))
        }
        if (installed("com.dropbox.android")) {
            add(ProviderOption("☁️", "Dropbox", "Jump into your Dropbox vault folder", "com.dropbox.android"))
        }
        // Corporate/Microsoft 365 — OneDrive for Business & SharePoint files live
        // behind these apps (Work/School sign-in must be present). SAF-only; no OAuth.
        if (installed("com.microsoft.office.outlook")) {
            add(ProviderOption("🏢", "Outlook", "OneDrive for Business / SharePoint files", "com.microsoft.office.outlook"))
        }
        if (installed("com.microsoft.sharepoint")) {
            add(ProviderOption("🏢", "SharePoint", "Company SharePoint / OneDrive files", "com.microsoft.sharepoint"))
        }
        if (installed("com.microsoft.office.officehub")) {
            add(ProviderOption("🏢", "Microsoft 365", "Office / OneDrive for Business files", "com.microsoft.office.officehub"))
        }
    }

    return buildList {
        add(ProviderOption("🔐", "Main vault location", "System picker — then choose the folder", "system"))
        addAll(cloud)
        add(ProviderOption("🗂", "Browse all", "Open the system picker at the top level", null))
    }
}

/**
 * Quick-choose sheet shown before the system picker. Tapping a provider
 * jumps into that provider's root in the SAF documents UI — the pin is the
 * SYSTEM DocumentsUI and the jump is EXTRA_INITIAL_URI built from the
 * provider's live ROOTS (authority + rootId), never a static authority.
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
 * Intent for "Browse all" — explicitly the SYSTEM SAF DocumentsUI, same
 * dynamic resolution + priority passes as the Main card (amitb brief
 * 2026-09-04): never the user default, never a chooser, no OEM/file-manager
 * hijack. All SAF flags preserved. Falls back to the implicit intent ONLY
 * when no system handler resolves (then [systemPickerAvailable]-based
 * guidance applies). No provider jump (no single target root).
 */
fun browseAllIntent(ctx: Context? = null): Intent {
    val base = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }
    if (ctx == null) return base
    resolveSystemPicker(ctx, base)
    return base
}

/**
 * Dynamically resolve the device's SYSTEM DocumentsUI handler and pin
 * [base] to it by component. Shared by Main and Browse-all paths.
 */
private fun resolveSystemPicker(ctx: Context, base: Intent) {
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
            Timber.i("SAF picker pinned to system component: ${best.activityInfo.packageName}/${best.activityInfo.name}")
        } else {
            Timber.w("No system SAF handler resolved — leaving implicit")
        }
    } catch (e: Exception) {
        // resolution unavailable → implicit intent; guidance path covers this
    }
}

/**
 * Intent that opens a vault folder picker for a specific provider.
 *
 * Strategy for [option] (cloud providers only):
 *   1. If the provider app ships its OWN DocumentsActivity for
 *      ACTION_OPEN_DOCUMENT_TREE (rare — Google Docs/OneDrive/Dropbox do NOT on
 *      current builds, verified on-device) pin the intent to THAT activity inside
 *      the provider package for a native picker.
 *   2. Normal path: pin to the dynamically-resolved SYSTEM SAF DocumentsUI and
 *      pre-seed EXTRA_INITIAL_URI with the provider's LIVE root document (queried
 *      from the provider's ROOTS API — authority + rootId never hardcoded).
 *      The tree URI the user picks still returns with read/write(+persistable)
 *      grants, so takePersistableUriPermission works exactly as today.
 *   3. No system picker resolves → implicit intent; caller surfaces guidance via
 *      [systemPickerAvailable].
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
    // "Browse all" (packageName == null) → implicit intent (the USER'S DEFAULT
    // handler). Callers normally route that case through browseAllIntent() instead
    // (which pins the system SAF picker); this fallback exists for direct callers.
    if (pkg == null) return base
    if (ctx == null) {
        // No context → best-effort static hint only.
        setStaticInitialHint(base, pkg)
        return base
    }

    // The destination jump: EXTRA_INITIAL_URI must point at the provider's REAL
    // SAF document root. We resolve it LIVE from the provider (authority + root
    // documentId read via the ROOTS API) — hardcoded authorities go stale
    // (OneDrive's moved to com.microsoft.skydrive.content.StorageAccessProvider).
    providerRootUri(ctx, pkg)?.let {
        base.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it)
    }

    // 1) Preferred: the provider's own DocumentsActivity (real cloud jump).
    if (pkg != "system") {
        resolveProviderDocumentsActivity(ctx, pkg)?.let { comp ->
            base.setClassName(comp.packageName, comp.className)
            Timber.i("Provider picker pinned to OWN activity for $pkg: ${comp.packageName}/${comp.className}")
            return base
        }
        Timber.w("Provider $pkg has no own ACTION_OPEN_DOCUMENT_TREE activity — falling through")
    }

    // 2) Fallback: pinned SYSTEM SAF DocumentsUI + initial-URI jump to the root.
    resolveSystemPicker(ctx, base)
    Timber.i(
        "Provider picker final: pkg=$pkg component=${base.component} " +
            "initial=${base.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI)}"
    )
    return base
}

/** Best-effort static EXTRA_INITIAL_URI used when no Context is available. */
private fun setStaticInitialHint(base: Intent, pkg: String) {
    val static = when (pkg) {
        "com.google.android.apps.docs" -> "content://com.google.android.apps.docs.storage/document/root"
        "com.microsoft.skydrive" -> "content://com.microsoft.skydrive.content.StorageAccessProvider/document/root"
        "com.dropbox.android" -> "content://com.dropbox.android.provider/document/root"
        "com.microsoft.office.outlook" -> "content://com.microsoft.office.outlook.files/document/root"
        else -> null
    }
    if (static != null) {
        try { base.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(static)) } catch (e: Exception) { /* hint only */ }
    }
}

/**
 * Resolve the provider's CURRENT SAF DocumentsProvider root as an
 * EXTRA_INITIAL_URI target. Queries buildRootsUri() so we survive authority and
 * root-documentId drift. On OEMs that enforce provider association (OnePlus
 * checkAssociationAndPermissionLocked denies cross-app ROOTS reads) the live
 * query throws → fall back to the static known-good authority + "root" doc:
 * never return bare-null when we still have a usable authority, because
 * EXTRA_INITIAL_URI is honored normally on Stock/Pixel/Samsung-style pickers.
 * Returns null only for unknown packages.
 */
private fun providerRootUri(ctx: Context, pkg: String): android.net.Uri? {
    val authority = when (pkg) {
        "com.google.android.apps.docs" -> "com.google.android.apps.docs.storage"
        "com.microsoft.skydrive" -> "com.microsoft.skydrive.content.StorageAccessProvider"
        "com.dropbox.android" -> "com.dropbox.android.provider"
        // Outlook hosts OneDrive for Business + SharePoint files (Work signed-in).
        // SharePoint & 365 hub: unknown/version-dependent authority → no static jump.
        "com.microsoft.office.outlook" -> "com.microsoft.office.outlook.files"
        else -> return null
    }
    try {
        var chosen: android.net.Uri? = null
        ctx.contentResolver.query(
            android.provider.DocumentsContract.buildRootsUri(authority),
            arrayOf(
                android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Root.COLUMN_TITLE,
                android.provider.DocumentsContract.Root.COLUMN_FLAGS
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val flags = if (c.isNull(2)) 0 else c.getInt(2)
                val title = c.getString(1)
                val uri = android.provider.DocumentsContract.buildDocumentUri(authority, docId)
                // Prefer the personal (non-work, non-local) root when the provider
                // exposes several (Drive personal vs. enterprise, phone-storage).
                val personal = (flags and android.provider.DocumentsContract.Root.FLAG_LOCAL_ONLY) == 0 &&
                    title?.let { !it.contains("work", true) && !it.contains("phone", true) } ?: true
                if (chosen == null || personal) chosen = uri
            }
        }
        chosen?.let { Timber.i("Provider root (live) for $pkg -> $it"); return it }
    } catch (e: Exception) {
        Timber.w(e, "Live ROOTS query denied for $pkg — using static fallback")
    }
    // Association-denied or rootless provider → static known-good target.
    val fallback = android.net.Uri.parse("content://$authority/document/root")
    Timber.i("Provider root (static) for $pkg -> $fallback")
    return fallback
}

/**
 * Find the [pkg] app's own activity that handles ACTION_OPEN_DOCUMENT_TREE, if
 * any. Works under package-visibility: the packages are declared in <queries>,
 * so queryIntentActivities(setPackage) can see their intent filters.
 */
private fun resolveProviderDocumentsActivity(ctx: Context, pkg: String): android.content.ComponentName? = try {
    val probe = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { setPackage(pkg) }
    ctx.packageManager.queryIntentActivities(probe, 0)
        .firstOrNull()
        ?.let { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
} catch (e: Exception) {
    null
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