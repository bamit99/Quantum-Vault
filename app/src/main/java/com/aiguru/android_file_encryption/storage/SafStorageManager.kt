package com.aiguru.android_file_encryption.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
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
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Expose the app context for helpers (cache sharing, contentResolver). */
    fun context(): Context = context

    /** Intent to let the user pick a vault folder (Drive/OneDrive/local/…). */
    fun openFolderPickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
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

        // SAF sometimes returns a bare tree URI; normalize to the document form we can write into
        val normalized = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                !treeUri.toString().contains("/document/")) {
                treeUri
            } else treeUri
        } catch (e: Exception) { treeUri }

        context.contentResolver.takePersistableUriPermission(
            normalized,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val name = queryDisplayName(normalized) ?: "Vault"
        prefs.edit()
            .putString(KEY_TREE_URI, normalized.toString())
            .putString(KEY_TREE_NAME, name)
            .apply()
        Timber.i("Vault location persisted: $name ($normalized)")
        return name
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
     * overwrites if it exists). Returns the document URI.
     */
    fun save(fileName: String, data: ByteArray, mimeType: String = "application/octet-stream"): Uri {
        val tree = requireNotNull(locationUri()) { "No vault location picked yet" }
        val resolver = context.contentResolver

        val docUri = try {
            DocumentHelper.createDocument(resolver, tree, fileName, mimeType)
        } catch (e: Exception) {
            // File may already exist → try replacing
            DocumentHelper.overwriteOrCreate(resolver, tree, fileName, mimeType)
                ?: throw java.io.IOException("Could not create $fileName in vault location", e)
        }

        resolver.openOutputStream(docUri ?: DocumentHelper.createDocument(resolver, tree, fileName, mimeType))!!.use { out ->
            out.write(data)
        }
        Timber.i("Saved $fileName (${data.size} B) to ${locationName()}")
        return docUri!!
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
}

data class SafFileInfo(
    val uri: Uri,
    val name: String,
    val isFolder: Boolean,
    val size: Long
)