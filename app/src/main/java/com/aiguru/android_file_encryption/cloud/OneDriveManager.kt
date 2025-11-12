package com.aiguru.android_file_encryption.cloud

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.*
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * Manages OneDrive operations using Microsoft Graph API
 */
class OneDriveManager(private val context: Context) {

    companion object {
        private const val GRAPH_API_BASE_URL = "https://graph.microsoft.com/v1.0"
        private const val DRIVE_ROOT = "$GRAPH_API_BASE_URL/me/drive"
        private const val APP_FOLDER_NAME = "SecureCloudStorage"
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${getAccessToken()}")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
        .build()

    private val gson = Gson()

    private fun getAccessToken(): String {
        // In a real implementation, this would retrieve the stored OAuth token
        // For now, we'll throw an exception to indicate it needs to be implemented
        throw NotImplementedError("OneDrive authentication not implemented. Use AuthManager to get Microsoft OAuth token.")
    }

    /**
     * Create or get the app root folder
     */
    suspend fun getOrCreateRootFolder(): String = withContext(Dispatchers.IO) {
        try {
            // Check if folder exists
            val request = Request.Builder()
                .url("$DRIVE_ROOT/root:/$APP_FOLDER_NAME")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val folder = gson.fromJson(response.body?.string(), OneDriveItem::class.java)
                    return@withContext folder.id
                }
            }

            // Create folder if it doesn't exist
            createFolder(APP_FOLDER_NAME)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get or create OneDrive root folder")
            throw IOException("OneDrive folder setup failed", e)
        }
    }

    /**
     * Create a new folder
     */
    suspend fun createFolder(folderName: String, parentId: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val parentPath = if (parentId != null) {
                "/items/$parentId"
            } else {
                "/root:/$APP_FOLDER_NAME"
            }

            val jsonBody = """
                {
                    "name": "$folderName",
                    "folder": {},
                    "@microsoft.graph.conflictBehavior": "rename"
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("$DRIVE_ROOT$parentPath/children")
                .post(RequestBody.create("application/json".toMediaType(), jsonBody))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to create folder: ${response.code}")
                }
                val folder = gson.fromJson(response.body?.string(), OneDriveItem::class.java)
                folder.id
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create OneDrive folder")
            throw IOException("Folder creation failed", e)
        }
    }

    /**
     * Upload file to OneDrive
     */
    suspend fun uploadFile(
        fileName: String,
        data: ByteArray,
        parentId: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val uploadUrl = if (parentId != null) {
                "$DRIVE_ROOT/items/$parentId:/$fileName:/content"
            } else {
                val rootId = getOrCreateRootFolder()
                "$DRIVE_ROOT/items/$rootId:/$fileName:/content"
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .put(RequestBody.create("application/octet-stream".toMediaType(), data))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to upload file: ${response.code}")
                }
                val file = gson.fromJson(response.body?.string(), OneDriveItem::class.java)
                Timber.d("File uploaded to OneDrive: ${file.name} (ID: ${file.id})")
                file.id
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload file to OneDrive")
            throw IOException("File upload failed", e)
        }
    }

    /**
     * Download file from OneDrive
     */
    suspend fun downloadFile(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$DRIVE_ROOT/items/$fileId/content")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to download file: ${response.code}")
                }
                val data = response.body?.bytes() ?: throw IOException("Empty response")
                Timber.d("File downloaded from OneDrive: $fileId (${data.size} bytes)")
                data
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download file from OneDrive")
            throw IOException("File download failed", e)
        }
    }

    /**
     * List files in a folder
     */
    suspend fun listFiles(folderId: String? = null): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val url = if (folderId != null) {
                "$DRIVE_ROOT/items/$folderId/children"
            } else {
                val rootId = getOrCreateRootFolder()
                "$DRIVE_ROOT/items/$rootId/children"
            }

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to list files: ${response.code}")
                }
                val result = gson.fromJson(response.body?.string(), OneDriveItemList::class.java)
                result.value.map { item ->
                    CloudFileInfo(
                        id = item.id,
                        name = item.name,
                        size = item.size ?: 0,
                        createdTime = item.createdDateTime?.time ?: 0,
                        modifiedTime = item.lastModifiedDateTime?.time ?: 0,
                        mimeType = item.file?.mimeType ?: "application/octet-stream",
                        isFolder = item.folder != null
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to list files from OneDrive")
            throw IOException("File listing failed", e)
        }
    }

    /**
     * Delete file from OneDrive
     */
    suspend fun deleteFile(fileId: String) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$DRIVE_ROOT/items/$fileId")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to delete file: ${response.code}")
                }
                Timber.d("File deleted from OneDrive: $fileId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete file from OneDrive")
            throw IOException("File deletion failed", e)
        }
    }

    /**
     * Get file metadata
     */
    suspend fun getFileMetadata(fileId: String): CloudFileInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$DRIVE_ROOT/items/$fileId")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }
                val item = gson.fromJson(response.body?.string(), OneDriveItem::class.java)
                CloudFileInfo(
                    id = item.id,
                    name = item.name,
                    size = item.size ?: 0,
                    createdTime = item.createdDateTime?.time ?: 0,
                    modifiedTime = item.lastModifiedDateTime?.time ?: 0,
                    mimeType = item.file?.mimeType ?: "application/octet-stream",
                    isFolder = item.folder != null
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get file metadata from OneDrive")
            null
        }
    }

    /**
     * Search for files
     */
    suspend fun searchFiles(query: String): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "$GRAPH_API_BASE_URL/me/drive/search(q='$query')"
            
            val request = Request.Builder()
                .url(searchUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to search files: ${response.code}")
                }
                val result = gson.fromJson(response.body?.string(), OneDriveItemList::class.java)
                result.value.map { item ->
                    CloudFileInfo(
                        id = item.id,
                        name = item.name,
                        size = item.size ?: 0,
                        createdTime = item.createdDateTime?.time ?: 0,
                        modifiedTime = item.lastModifiedDateTime?.time ?: 0,
                        mimeType = item.file?.mimeType ?: "application/octet-stream",
                        isFolder = item.folder != null
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to search files in OneDrive")
            throw IOException("File search failed", e)
        }
    }
}

/**
 * Data classes for OneDrive API responses
 */
data class OneDriveItem(
    val id: String,
    val name: String,
    val size: Long? = null,
    val createdDateTime: java.util.Date? = null,
    val lastModifiedDateTime: java.util.Date? = null,
    val file: OneDriveFile? = null,
    val folder: OneDriveFolder? = null
)

data class OneDriveFile(
    val mimeType: String
)

data class OneDriveFolder(
    val childCount: Int? = null
)

data class OneDriveItemList(
    val value: List<OneDriveItem>
)