package com.aiguru.android_file_encryption.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import com.aiguru.android_file_encryption.security.VaultEscrow
import timber.log.Timber

/**
 * SAF (Storage Access Framework) storage manager.
 *
 * Replaces all per-provider OAuth cloud integrations:
 *  - The system picker exposes Google Drive, OneDrive, Dropbox, SD cards, local
 *    folders — anything that registers a DocumentsProvider — already authenticated
 *    by the user's own sign-in to those apps.
 *  - The picked tree URI is persisted (takePersistableUriPermission) so the vault
 *    location survives reboots: "remember the location".
 *
 * Crypto-agnostic: operates on raw bytes. The caller runs HybridPQCrypto before
 * save() and after read().
 */
class SafStorageManager(private val context: Context) {

    companion object {
        private const val PREFS = "saf_storage"
        private const val KEY_TREE_URI = "vault_tree_uri"
        private const val KEY_TREE_NAME = "vault_tree_name"

        /** Dedicated folder auto-created inside the picked parent for a NEW vault. */
        const val VAULT_FOLDER = "QuantumVault"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Expose the app context for helpers (cache sharing, contentResolver). */
    fun context(): Context = context

    /**
     * Intent for "Browse all…" — wrapped in a system CHOOSER so the user
     * explicitly picks the handler EVERY time (createChooser never silently
     * resolves to a default). Android's implicit resolution prefers the system
     * documentsui even when a file manager is installed, which hid explorer
     * integrations (OneDrive via Solid Explorer on OnePlus 12R, 2026-09-04).
     * The chooser exposes every handler — file managers included — letting
     * users route through an explorer that integrates their cloud.
     */
    fun openFolderPickerIntent(): Intent {
        val tree = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        return android.content.Intent.createChooser(tree, "Choose a vault folder")
    }

    /**
     * Call from the picker result. Persists the tree permission across reboots
     * and remembers the location + display name.
     */
    fun onFolderPicked(treeUri: Uri): String {
        // Release any previous persisted permission so we don't accumulate them
        val old = prefs.getString(KEY_TREE_URI, null)
        if (old != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Timber.w(e, "Could not release old tree permission")
            }
        }

        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val name = queryDisplayName(treeUri) ?: "Vault"
        prefs.edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .putString(KEY_TREE_NAME, name)
            .apply()
        Timber.i("Vault location persisted: $name ($treeUri)")
        return name
    }

    /**
     * Pick-time vault-folder prep for the "create a vault" step.
     *
     * There is no SAF "create-folder" intent — RFC: a folder is picked as a tree
     * (ACTION_OPEN_DOCUMENT_TREE), and account/devices either create it inside the
     * picker UI or programmatically afterwards. We do the latter:
     *
     *   1. Picked folder already hosts this vault (.vaultkey) → return it unchanged
     *      (hostedVault = true). Restore/adopt path — never mint a duplicate folder.
     *   2. Picked folder already contains a "QuantumVault/" child → reuse its subtree
     *      (idempotent re-entry after a mid-flight retry).
     *   3. Else auto-create "QuantumVault/" via DocumentsContract.createDocument
     *      (MIME_TYPE_DIR) inside the picked folder and return THAT subtree, so the
     *      vault has a dedicated, named home instead of living in whatever parent
     *      the user happened to select.
     *   4. Provider refuses (no dir-creation support, e.g. some cloud providers) or
     *      anything fails → fall back to the picked folder itself, so vault creation
     *      always completes — the vault just lives at the pick site.
     */
    fun prepareVaultLocation(pickedTree: Uri): PreparedLocation {
        val resolver = context.contentResolver

        val hostedVault = try {
            DocumentHelper.findExisting(resolver, pickedTree, VaultEscrow.FILE_NAME) != null
        } catch (e: Exception) {
            Timber.w(e, "Escrow probe failed — assuming fresh location")
            false
        }
        if (hostedVault) return PreparedLocation(pickedTree, hostedVault = true)

        val existingChild = try {
            DocumentHelper.findExisting(resolver, pickedTree, VAULT_FOLDER)
        } catch (e: Exception) {
            Timber.w(e, "Existing '$VAULT_FOLDER' probe failed")
            null
        }
        if (existingChild != null) {
            return PreparedLocation(
                DocumentsContract.buildTreeDocumentUri(pickedTree.authority, DocumentsContract.getDocumentId(existingChild)),
                hostedVault = false
            )
        }

        val created = try {
            DocumentHelper.createDocument(resolver, pickedTree, VAULT_FOLDER, DocumentsContract.Document.MIME_TYPE_DIR)
        } catch (e: Exception) {
            Timber.w(e, "Auto-create of '$VAULT_FOLDER' failed — using picked folder directly")
            null
        }
        return if (created != null) {
            PreparedLocation(
                DocumentsContract.buildTreeDocumentUri(pickedTree.authority, DocumentsContract.getDocumentId(created)),
                hostedVault = false,
                createdFolder = true
            )
        } else {
            PreparedLocation(pickedTree, hostedVault = false)
        }
    }

    /** Whether a vault location is already remembered. */
    fun hasLocation(): Boolean = prefs.getString(KEY_TREE_URI, null) != null

    /** Remembered location display name (e.g. "Drive", folder label). */
    fun locationName(): String? = prefs.getString(KEY_TREE_NAME, null)

    /** Remembered tree URI (null if none). */
    fun locationUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** Forget the remembered location. */
    fun clearLocation() {
        prefs.edit().clear().apply()
    }

    /**
     * Save [data] as [fileName] inside the remembered folder (creates the file,
     * overwrites delete+create if one with that name already exists). Returns the
     * document URI.
     */
    fun save(fileName: String, data: ByteArray, mimeType: String = "application/octet-stream"): Uri {
        val tree = requireNotNull(locationUri()) { "No vault location picked yet" }
        val resolver = context.contentResolver

        val docUri = try {
            DocumentHelper.createDocument(resolver, tree, fileName, mimeType)
        } catch (e: Exception) {
            DocumentHelper.overwriteOrCreate(resolver, tree, fileName, mimeType)
                ?: throw java.io.IOException("Could not create $fileName in vault location", e)
        }

        resolver.openOutputStream(docUri)!!.use { out ->
            out.write(data)
        }
        Timber.i("Saved $fileName (${data.size} B) to ${locationName()}")
        return docUri
    }

    /** Find a child document by name in the remembered tree (null if absent). */
    fun findChild(fileName: String): Uri? {
        val tree = locationUri() ?: return null
        return try {
            val parentDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == fileName) {
                        return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
            }
            null
        } catch (e: Exception) {
            Timber.w(e, "findChild($fileName) failed")
            null
        }
    }

    /** Read a document by URI (from picker or remembered tree). */
    fun read(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot open $uri")

    /** List document names in the remembered tree. */
    fun list(): List<SafFileInfo> {
        val tree = locationUri() ?: return emptyList()
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
        val out = mutableListOf<SafFileInfo>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    SafFileInfo(
                        uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        name = c.getString(1) ?: "?",
                        isFolder = c.getString(2) == "application/vnd.google-apps.folder" ||
                                (c.getString(2) ?: "").endsWith(".directory"),
                        size = if (c.isNull(3)) 0L else c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not resolve display name for $uri")
        null
    }

    /** Delete a document inside the remembered tree (best-effort). */
    fun deleteDocument(uri: Uri): Boolean = try {
        android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        Timber.w(e, "Delete failed for $uri")
        false
    }

    /** List every .qvault document in the tree (for vault destroy). */
    fun listQvaultDocs(): List<Pair<Uri, String>> =
        list().filter { it.name.endsWith(".qvault") }.map { it.uri to it.name }

    /** Forget the remembered vault location (releases persisted permission). */
    fun forgetLocation() {
        val old = prefs.getString(KEY_TREE_URI, null)
        if (old != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { /* already gone */ }
        }
        prefs.edit().clear().commit()
    }

    // ─────────────────────── location intelligence (v4.2) ───────────────────────

    /**
     * Classify the remembered location from its SAF tree-URI authority —
     * zero queries, zero permissions: the provider is IN the URI.
     */
    fun locationInfo(): LocationInfo {
        val tree = locationUri() ?: return LocationInfo(LocType.NONE, "", locationName())
        val auth = tree.authority ?: ""
        val type = when {
            auth == "com.android.externalstorage.documents" -> LocType.LOCAL   // internal + SD via documentId
            auth == "com.android.providers.downloads.documents" -> LocType.LOCAL
            auth == "com.android.providers.media.documents" -> LocType.LOCAL
            auth.contains("google.android.apps.docs") || auth.contains("googledrive") -> LocType.GOOGLE_DRIVE
            auth.contains("skydrive") || auth.contains("microsoft.onedrive") ||
            // corporate Microsoft files roots (Outlook/SharePoint/365 hub)
            auth.contains("com.microsoft.sharepoint") || auth.contains("microsoft.office.outlook") ||
            auth.contains("microsoft.office.officehub") -> LocType.ONEDRIVE
            auth.contains("dropbox") -> LocType.DROPBOX
            else -> LocType.OTHER_CLOUD
        }
        return LocationInfo(type, auth, locationName())
    }
}

enum class LocType { NONE, LOCAL, GOOGLE_DRIVE, ONEDRIVE, DROPBOX, OTHER_CLOUD }

data class LocationInfo(
    val type: LocType,
    val authority: String,
    val displayName: String?
) {
    /** Short label for UI badges. */
    fun label(): String = when (type) {
        LocType.NONE -> "No location"
        LocType.LOCAL -> "This device"
        LocType.GOOGLE_DRIVE -> "Google Drive"
        LocType.ONEDRIVE -> "OneDrive"
        LocType.DROPBOX -> "Dropbox"
        LocType.OTHER_CLOUD -> authority.substringBefore('.').replaceFirstChar { it.uppercase() }
    }

    /** User-facing recommendation for this storage class. */
    fun recommendation(): String = when (type) {
        LocType.NONE -> "Pick a folder to create or restore your vault."
        LocType.LOCAL ->
            "✓ Fastest + fully offline. Files never leave this device.\n" +
            "⚠ No off-device backup — a lost/broken phone with a lost passphrase means loss. Consider a cloud location for the .vaultkey."
        LocType.GOOGLE_DRIVE ->
            "✓ Survives phone loss/formats — files + .vaultkey sync to Drive.\n" +
            "ℹ Slower for big files, needs network; provider sees file names/sizes (never contents)."
        LocType.ONEDRIVE ->
            "✓ Survives phone loss — files + .vaultkey sync to OneDrive.\n" +
            "ℹ Slower for big files, needs network; provider sees file names/sizes (never contents)."
        LocType.DROPBOX ->
            "✓ Survives phone loss — files + .vaultkey sync to Dropbox.\n" +
            "ℹ Slower for big files, needs network; provider sees file names/sizes (never contents)."
        LocType.OTHER_CLOUD ->
            "ℹ Third-party provider — verify it keeps files offline-available and doesn't purge 'unknown' files."
    }
}

data class SafFileInfo(
    val uri: Uri,
    val name: String,
    val isFolder: Boolean,
    val size: Long
)

/** Result of [SafStorageManager.prepareVaultLocation] — where the vault will live. */
data class PreparedLocation(
    /** Tree URI that becomes the vault root (picked parent or auto-created subfolder). */
    val tree: Uri,
    /** true when the picked folder already hosts a vault (.vaultkey) — restore/adopt, not create. */
    val hostedVault: Boolean,
    /** true when a fresh '$VAULT_FOLDER' folder was auto-created at the pick site. */
    val createdFolder: Boolean = false
)