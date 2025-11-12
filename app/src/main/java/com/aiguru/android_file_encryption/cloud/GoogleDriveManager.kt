package com.aiguru.android_file_encryption.cloud

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages Google Drive operations including file upload, download, and listing
 */
class GoogleDriveManager(private val context: Context) {

    companion object {
        private const val APPLICATION_NAME = "Secure Cloud Storage"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val ROOT_FOLDER_NAME = "SecureCloudStorage"
    }

    private var driveService: Drive? = null
    private var rootFolderId: String? = null

    /**
     * Initialize Google Drive service with user credentials
     */
    fun initialize(credential: GoogleAccountCredential) {
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    /**
     * Check if Drive service is initialized
     */
    fun isInitialized(): Boolean {
        return driveService != null
    }

    /**
     * Create or get the root folder for the app
     */
    suspend fun getOrCreateRootFolder(): String = withContext(Dispatchers.IO) {
        try {
            // Check if root folder already exists
            val query = "name = '$ROOT_FOLDER_NAME' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
            val result = driveService?.files()?.list()
                ?.setQ(query)
                ?.setSpaces("drive")
                ?.execute()

            if (!result?.files.isNullOrEmpty()) {
                val folderId = result?.files?.firstOrNull()?.id
                rootFolderId = folderId
                return@withContext folderId ?: throw IOException("Failed to get root folder ID")
            }

            // Create new root folder
            val folderMetadata = File()
                .setName(ROOT_FOLDER_NAME)
                .setMimeType(FOLDER_MIME_TYPE)

            val folder = driveService?.files()?.create(folderMetadata)
                ?.setFields("id")
                ?.execute()

            val folderId = folder?.id
            rootFolderId = folderId
            folderId ?: throw IOException("Failed to create root folder")
        } catch (e: Exception) {
            Timber.e(e, "Failed to get or create root folder")
            throw IOException("Google Drive folder setup failed", e)
        }
    }

    /**
     * Upload encrypted file to Google Drive
     */
    suspend fun uploadFile(
        fileName: String,
        data: ByteArray,
        mimeType: String = "application/octet-stream"
    ): String = withContext(Dispatchers.IO) {
        try {
            val folderId = rootFolderId ?: getOrCreateRootFolder()

            val fileMetadata = File()
                .setName(fileName)
                .setParents(listOf(folderId))

            val inputStream = data.inputStream()
            val mediaContent = com.google.api.client.http.InputStreamContent(mimeType, inputStream)

            val file = driveService?.files()?.create(fileMetadata, mediaContent)
                ?.setFields("id, name, size, createdTime")
                ?.execute()

            Timber.d("File uploaded to Google Drive: ${file?.name} (ID: ${file?.id})")
            file?.id ?: throw IOException("Failed to upload file")
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload file to Google Drive")
            throw IOException("File upload failed", e)
        }
    }

    /**
     * Download file from Google Drive
     */
    suspend fun downloadFile(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            driveService?.files()?.get(fileId)?.executeMediaAndDownloadTo(outputStream)
            
            val data = outputStream.toByteArray()
            Timber.d("File downloaded from Google Drive: $fileId (${data.size} bytes)")
            data
        } catch (e: Exception) {
            Timber.e(e, "Failed to download file from Google Drive")
            throw IOException("File download failed", e)
        }
    }

    /**
     * List files in the app's root folder
     */
    suspend fun listFiles(): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val folderId = rootFolderId ?: getOrCreateRootFolder()
            
            val query = "'$folderId' in parents and trashed = false"
            val result = driveService?.files()?.list()
                ?.setQ(query)
                ?.setSpaces("drive")
                ?.setFields("files(id, name, size, createdTime, modifiedTime, mimeType")
                ?.execute()

            result?.files?.map { file ->
                CloudFileInfo(
                    id = file.id,
                    name = file.name,
                    size = (file.size ?: 0).toLong(),
                    createdTime = file.createdTime?.value ?: 0,
                    modifiedTime = file.modifiedTime?.value ?: 0,
                    mimeType = file.mimeType ?: "application/octet-stream",
                    isFolder = file.mimeType == FOLDER_MIME_TYPE
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to list files from Google Drive")
            throw IOException("File listing failed", e)
        }
    }

    /**
     * Delete file from Google Drive
     */
    suspend fun deleteFile(fileId: String) = withContext(Dispatchers.IO) {
        try {
            driveService?.files()?.delete(fileId)?.execute()
            Timber.d("File deleted from Google Drive: $fileId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete file from Google Drive")
            throw IOException("File deletion failed", e)
        }
    }

    /**
     * Create a folder in Google Drive
     */
    suspend fun createFolder(folderName: String, parentId: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val parent = parentId ?: rootFolderId ?: getOrCreateRootFolder()
            
            val folderMetadata = File()
                .setName(folderName)
                .setMimeType(FOLDER_MIME_TYPE)
                .setParents(listOf(parent))

            val folder = driveService?.files()?.create(folderMetadata)
                ?.setFields("id")
                ?.execute()

            folder?.id ?: throw IOException("Failed to create folder")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create folder in Google Drive")
            throw IOException("Folder creation failed", e)
        }
    }

    /**
     * Search for files by name
     */
    suspend fun searchFiles(query: String): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val folderId = rootFolderId ?: getOrCreateRootFolder()
            val searchQuery = "'$folderId' in parents and name contains '$query' and trashed = false"
            
            val result = driveService?.files()?.list()
                ?.setQ(searchQuery)
                ?.setSpaces("drive")
                ?.setFields("files(id, name, size, createdTime, modifiedTime, mimeType")
                ?.execute()

            result?.files?.map { file ->
                CloudFileInfo(
                    id = file.id,
                    name = file.name,
                    size = (file.size ?: 0).toLong(),
                    createdTime = file.createdTime?.value ?: 0,
                    modifiedTime = file.modifiedTime?.value ?: 0,
                    mimeType = file.mimeType ?: "application/octet-stream",
                    isFolder = file.mimeType == FOLDER_MIME_TYPE
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to search files in Google Drive")
            throw IOException("File search failed", e)
        }
    }

    /**
     * Get file metadata
     */
    suspend fun getFileMetadata(fileId: String): CloudFileInfo? = withContext(Dispatchers.IO) {
        try {
            val file = driveService?.files()?.get(fileId)
                ?.setFields("id, name, size, createdTime, modifiedTime, mimeType")
                ?.execute()

            file?.let {
                CloudFileInfo(
                    id = it.id,
                    name = it.name,
                    size = (it.size ?: 0).toLong(),
                    createdTime = it.createdTime?.value ?: 0,
                    modifiedTime = it.modifiedTime?.value ?: 0,
                    mimeType = it.mimeType ?: "application/octet-stream",
                    isFolder = it.mimeType == FOLDER_MIME_TYPE
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get file metadata from Google Drive")
            null
        }
    }
}

/**
 * Data class for cloud file information
 */
data class CloudFileInfo(
    val id: String,
    val name: String,
    val size: Long,
    val createdTime: Long,
    val modifiedTime: Long,
    val mimeType: String,
    val isFolder: Boolean = false
)