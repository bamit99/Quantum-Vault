package com.aiguru.android_file_encryption.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import timber.log.Timber

/**
 * Thin helpers over DocumentsContract for creating/overwriting documents inside a
 * persisted tree URI. DocumentFile is convenient but heavy; we talk to the
 * provider directly for predictable behavior across providers (Drive included).
 */
internal object DocumentHelper {

    fun createDocument(resolver: android.content.ContentResolver, tree: Uri, fileName: String, mimeType: String): Uri {
        val parentDocId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        // DocumentsContract.createDocument inserts into the tree
        val newDoc = DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId),
            mimeType,
            fileName
        ) ?: throw java.io.IOException("Provider refused to create $fileName")
        return newDoc
    }

    /**
     * Finds an existing child with [fileName] in [tree] and returns its URI, or null.
     */
    fun findExisting(resolver: android.content.ContentResolver, tree: Uri, fileName: String): Uri? {
        val parentDocId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == fileName) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    /**
     * Returns a writable document URI for [fileName]: reuse existing (delete+create
     * keeps provider metadata clean) or create fresh.
     */
    fun overwriteOrCreate(resolver: android.content.ContentResolver, tree: Uri, fileName: String, mimeType: String): Uri? {
        val existing = findExisting(resolver, tree, fileName)
        if (existing != null) {
            try {
                DocumentsContract.deleteDocument(resolver, existing)
            } catch (e: Exception) {
                Timber.w(e, "delete-before-overwrite failed for $fileName; will attempt createDocument rename path")
            }
        }
        return try {
            createDocument(resolver, tree, fileName, mimeType)
        } catch (e: Exception) {
            Timber.e(e, "overwriteOrCreate failed for $fileName")
            null
        }
    }
}