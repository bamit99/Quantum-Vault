package com.aiguru.android_file_encryption.cloud

import android.content.Context
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.blob.CloudBlobClient
import com.microsoft.azure.storage.blob.CloudBlobContainer
import com.microsoft.azure.storage.blob.CloudBlockBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*

/**
 * Manages Azure Blob Storage operations
 * Note: In production, use Azure AD authentication instead of storage account keys
 */
class AzureBlobManager(private val context: Context) {

    companion object {
        private const val DEFAULT_CONTAINER_NAME = "secure-cloud-storage"
        private const val APP_FOLDER_PREFIX = "encrypted-files/"
    }

    private var blobClient: CloudBlobClient? = null
    private var blobContainer: CloudBlobContainer? = null
    private var currentContainer: String = DEFAULT_CONTAINER_NAME

    /**
     * Initialize Azure Blob client with storage account connection string
     * WARNING: In production, use Azure AD authentication instead of account keys
     */
    suspend fun initialize(connectionString: String) {
        try {
            val storageAccount = CloudStorageAccount.parse(connectionString)
            blobClient = storageAccount.createCloudBlobClient()
            
            // Create container if it doesn't exist
            createContainerIfNotExists(DEFAULT_CONTAINER_NAME)
            
            Timber.d("Azure Blob client initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Azure Blob client")
            throw SecurityException("Azure Blob initialization failed", e)
        }
    }

    /**
     * Initialize with Azure AD authentication (recommended for production)
     */
    fun initializeWithAzureAD(tenantId: String, clientId: String, clientSecret: String) {
        try {
            // In a real implementation, you would use Azure AD credentials
            // For now, this is a placeholder that throws an exception
            throw NotImplementedError("Azure AD authentication not implemented. Please provide storage account connection string.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Azure Blob with Azure AD")
            throw SecurityException("Azure AD initialization failed", e)
        }
    }

    /**
     * Check if Azure Blob client is initialized
     */
    fun isInitialized(): Boolean {
        return blobClient != null
    }

    /**
     * Create container if it doesn't exist
     */
    suspend fun createContainerIfNotExists(containerName: String) = withContext(Dispatchers.IO) {
        try {
            val container = blobClient?.getContainerReference(containerName)
            container?.createIfNotExists()
            blobContainer = container
            currentContainer = containerName
            Timber.d("Azure container created/exists: $containerName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create Azure container")
            throw IOException("Container creation failed", e)
        }
    }

    /**
     * Upload encrypted file to Azure Blob Storage
     */
    suspend fun uploadFile(
        fileName: String,
        data: ByteArray,
        containerName: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blobName = "$APP_FOLDER_PREFIX$fileName"
            val blob = container.getBlockBlobReference(blobName)
            
            // Set metadata
            val metadata = hashMapOf(
                "encrypted" to "true",
                "app" to "SecureCloudStorage",
                "upload-time" to Date().toString()
            )
            blob.metadata = metadata
            
            // Upload data
            val inputStream = ByteArrayInputStream(data)
            blob.upload(inputStream, data.size.toLong())
            
            Timber.d("File uploaded to Azure Blob: $blobName (${data.size} bytes)")
            blobName
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload file to Azure Blob")
            throw IOException("Azure upload failed", e)
        }
    }

    /**
     * Download file from Azure Blob Storage
     */
    suspend fun downloadFile(
        blobName: String,
        containerName: String? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blob = container.getBlockBlobReference(blobName)
            
            val outputStream = ByteArrayOutputStream()
            blob.download(outputStream)
            
            val data = outputStream.toByteArray()
            Timber.d("File downloaded from Azure Blob: $blobName (${data.size} bytes)")
            data
        } catch (e: Exception) {
            Timber.e(e, "Failed to download file from Azure Blob")
            throw IOException("Azure download failed", e)
        }
    }

    /**
     * List files in the app folder
     */
    suspend fun listFiles(containerName: String? = null): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blobs = container.listBlobs(APP_FOLDER_PREFIX)
            val fileList = mutableListOf<CloudFileInfo>()
            
            for (item in blobs) {
                val blob = item as? CloudBlockBlob ?: continue
                blob.downloadAttributes()
                
                fileList.add(
                    CloudFileInfo(
                        id = blob.name,
                        name = blob.name.removePrefix(APP_FOLDER_PREFIX),
                        size = blob.properties.length,
                        createdTime = blob.properties.lastModified?.time ?: 0,
                        modifiedTime = blob.properties.lastModified?.time ?: 0,
                        mimeType = blob.properties.contentType ?: "application/octet-stream",
                        isFolder = blob.name.endsWith("/")
                    )
                )
            }
            
            fileList
        } catch (e: Exception) {
            Timber.e(e, "Failed to list files from Azure Blob")
            throw IOException("Azure listing failed", e)
        }
    }

    /**
     * Delete file from Azure Blob Storage
     */
    suspend fun deleteFile(
        blobName: String,
        containerName: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blob = container.getBlockBlobReference(blobName)
            blob.deleteIfExists()
            
            Timber.d("File deleted from Azure Blob: $blobName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete file from Azure Blob")
            throw IOException("Azure deletion failed", e)
        }
    }

    /**
     * Get file metadata
     */
    suspend fun getFileMetadata(
        blobName: String,
        containerName: String? = null
    ): CloudFileInfo? = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blob = container.getBlockBlobReference(blobName)
            blob.downloadAttributes()
            
            CloudFileInfo(
                id = blob.name,
                name = blob.name.removePrefix(APP_FOLDER_PREFIX),
                size = blob.properties.length,
                createdTime = blob.properties.lastModified?.time ?: 0,
                modifiedTime = blob.properties.lastModified?.time ?: 0,
                mimeType = blob.properties.contentType ?: "application/octet-stream",
                isFolder = blob.name.endsWith("/")
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get Azure Blob metadata")
            null
        }
    }

    /**
     * Create a folder in Azure Blob Storage (simulated with empty blob)
     */
    suspend fun createFolder(
        folderName: String,
        containerName: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blobName = "$APP_FOLDER_PREFIX$folderName/"
            val blob = container.getBlockBlobReference(blobName)
            
            // Create empty folder marker
            val emptyData = ByteArray(0)
            val inputStream = ByteArrayInputStream(emptyData)
            blob.upload(inputStream, 0)
            
            // Set metadata
            val metadata = hashMapOf(
                "hdi_isfolder" to "true",
                "folder" to "true"
            )
            blob.metadata = metadata
            blob.uploadMetadata()
            
            Timber.d("Folder created in Azure Blob: $blobName")
            blobName
        } catch (e: Exception) {
            Timber.e(e, "Failed to create folder in Azure Blob")
            throw IOException("Azure folder creation failed", e)
        }
    }

    /**
     * Search for files (filter locally)
     */
    suspend fun searchFiles(
        query: String,
        containerName: String? = null
    ): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val allFiles = listFiles(containerName)
            allFiles.filter { file ->
                file.name.contains(query, ignoreCase = true)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to search files in Azure Blob")
            throw IOException("Azure search failed", e)
        }
    }

    /**
     * Get storage statistics
     */
    suspend fun getStorageStats(containerName: String? = null): AzureStorageStats = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blobs = container.listBlobs(APP_FOLDER_PREFIX)
            var totalSize = 0L
            var fileCount = 0
            
            for (item in blobs) {
                val blob = item as? CloudBlockBlob ?: continue
                if (!blob.name.endsWith("/")) { // Skip folder markers
                    totalSize += blob.properties.length
                    fileCount++
                }
            }
            
            AzureStorageStats(
                totalSpace = totalSize,
                fileCount = fileCount,
                containerName = container.name
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get Azure Blob storage stats")
            AzureStorageStats()
        }
    }

    /**
     * Copy blob within Azure Storage
     */
    suspend fun copyBlob(
        sourceBlobName: String,
        destinationBlobName: String,
        containerName: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val sourceBlob = container.getBlockBlobReference(sourceBlobName)
            val destBlob = container.getBlockBlobReference(destinationBlobName)
            
            // Start copy operation
            val copyResult = destBlob.startCopy(sourceBlob)
            
            // Wait for copy to complete
            while (destBlob.copyState?.status?.name == "PENDING") {
                Thread.sleep(1000)
                destBlob.downloadAttributes()
            }
            
            if (destBlob.copyState?.status?.name != "SUCCESS") {
                throw IOException("Blob copy failed: ${destBlob.copyState?.status}")
            }
            
            Timber.d("Blob copied in Azure: $sourceBlobName to $destinationBlobName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy blob in Azure")
            throw IOException("Azure copy failed", e)
        }
    }

    /**
     * Move blob within Azure Storage (copy + delete)
     */
    suspend fun moveBlob(
        sourceBlobName: String,
        destinationBlobName: String,
        containerName: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            copyBlob(sourceBlobName, destinationBlobName, containerName)
            deleteFile(sourceBlobName, containerName)
            Timber.d("Blob moved in Azure: $sourceBlobName to $destinationBlobName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to move blob in Azure")
            throw IOException("Azure move failed", e)
        }
    }

    /**
     * Generate SAS token for temporary access
     */
    suspend fun generateSasToken(
        blobName: String,
        permissions: String = "r",
        expiryMinutes: Int = 60,
        containerName: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val container = if (containerName != null) {
                blobClient?.getContainerReference(containerName)
            } else {
                blobContainer
            } ?: throw IOException("Blob container not initialized")

            val blob = container.getBlockBlobReference(blobName)
            
            // In a real implementation, you would generate a proper SAS token
            // This is a simplified version for demonstration
            val expiryTime = Date(System.currentTimeMillis() + expiryMinutes * 60 * 1000)
            
            // Note: This is a placeholder - real SAS token generation requires account key
            throw NotImplementedError("SAS token generation requires storage account key access")
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate SAS token")
            throw IOException("SAS token generation failed", e)
        }
    }
}

/**
 * Data class for Azure Blob storage statistics
 */
data class AzureStorageStats(
    val totalSpace: Long = 0,
    val fileCount: Int = 0,
    val containerName: String = ""
)