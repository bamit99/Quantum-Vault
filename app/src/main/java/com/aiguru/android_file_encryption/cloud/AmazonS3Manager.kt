package com.aiguru.android_file_encryption.cloud

import android.content.Context
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Manages Amazon S3 operations for cloud storage
 * Note: In production, use AWS Cognito for authentication instead of hardcoded credentials
 */
class AmazonS3Manager(private val context: Context) {

    companion object {
        private const val DEFAULT_BUCKET_NAME = "secure-cloud-storage-android"
        private const val APP_FOLDER_PREFIX = "encrypted-files/"
    }

    private var s3Client: AmazonS3Client? = null
    private var currentBucket: String = DEFAULT_BUCKET_NAME

    /**
     * Initialize S3 client with credentials
     * WARNING: In production, use AWS Cognito or IAM roles instead of hardcoded credentials
     */
    suspend fun initialize(accessKey: String, secretKey: String, region: String = "us-east-1") {
        try {
            val credentials = BasicAWSCredentials(accessKey, secretKey)
            s3Client = AmazonS3Client(credentials)
            s3Client?.setRegion(Region.getRegion(Regions.fromName(region)))
            
            // Create bucket if it doesn't exist
            createBucketIfNotExists(DEFAULT_BUCKET_NAME)
            
            Timber.d("Amazon S3 client initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Amazon S3 client")
            throw SecurityException("S3 initialization failed", e)
        }
    }

    /**
     * Initialize S3 client with Cognito (recommended for production)
     */
    fun initializeWithCognito(identityPoolId: String, region: Regions = Regions.US_EAST_1) {
        try {
            // In a real implementation, you would use AWS Cognito credentials provider
            // For now, this is a placeholder that throws an exception
            throw NotImplementedError("Cognito authentication not implemented. Please provide AWS credentials.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Amazon S3 with Cognito")
            throw SecurityException("S3 Cognito initialization failed", e)
        }
    }

    /**
     * Check if S3 client is initialized
     */
    fun isInitialized(): Boolean {
        return s3Client != null
    }

    /**
     * Create bucket if it doesn't exist
     */
    suspend fun createBucketIfNotExists(bucketName: String) = withContext(Dispatchers.IO) {
        try {
            if (s3Client?.doesBucketExist(bucketName) == false) {
                s3Client?.createBucket(bucketName)
                Timber.d("S3 bucket created: $bucketName")
            }
            currentBucket = bucketName
        } catch (e: Exception) {
            Timber.e(e, "Failed to create S3 bucket")
            throw IOException("Bucket creation failed", e)
        }
    }

    /**
     * Upload encrypted file to S3
     */
    suspend fun uploadFile(
        fileName: String,
        data: ByteArray,
        bucketName: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            val key = "$APP_FOLDER_PREFIX$fileName"
            
            val metadata = ObjectMetadata().apply {
                contentLength = data.size.toLong()
                contentType = "application/octet-stream"
                addUserMetadata("x-amz-meta-encrypted", "true")
                addUserMetadata("x-amz-meta-app", "SecureCloudStorage")
            }

            val inputStream = ByteArrayInputStream(data)
            s3Client?.putObject(PutObjectRequest(bucket, key, inputStream, metadata))
            
            Timber.d("File uploaded to S3: $key (${data.size} bytes)")
            key
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload file to S3")
            throw IOException("S3 upload failed", e)
        }
    }

    /**
     * Download file from S3
     */
    suspend fun downloadFile(
        key: String,
        bucketName: String? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            
            val s3Object = s3Client?.getObject(GetObjectRequest(bucket, key))
                ?: throw IOException("Failed to get S3 object")
            
            val outputStream = ByteArrayOutputStream()
            s3Object.objectContent.use { inputStream ->
                inputStream.copyTo(outputStream)
            }
            
            val data = outputStream.toByteArray()
            Timber.d("File downloaded from S3: $key (${data.size} bytes)")
            data
        } catch (e: Exception) {
            Timber.e(e, "Failed to download file from S3")
            throw IOException("S3 download failed", e)
        }
    }

    /**
     * List files in the app folder
     */
    suspend fun listFiles(bucketName: String? = null): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            
            val listObjectsRequest = ListObjectsRequest().apply {
                this.bucketName = bucket
                prefix = APP_FOLDER_PREFIX
            }

            val objectListing = s3Client?.listObjects(listObjectsRequest)
                ?: throw IOException("Failed to list S3 objects")
            
            objectListing.objectSummaries.map { summary ->
                CloudFileInfo(
                    id = summary.key,
                    name = summary.key.removePrefix(APP_FOLDER_PREFIX),
                    size = summary.size,
                    createdTime = summary.lastModified.time,
                    modifiedTime = summary.lastModified.time,
                    mimeType = "application/octet-stream",
                    isFolder = summary.key.endsWith("/")
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to list files from S3")
            throw IOException("S3 listing failed", e)
        }
    }

    /**
     * Delete file from S3
     */
    suspend fun deleteFile(
        key: String,
        bucketName: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            s3Client?.deleteObject(DeleteObjectRequest(bucket, key))
            Timber.d("File deleted from S3: $key")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete file from S3")
            throw IOException("S3 deletion failed", e)
        }
    }

    /**
     * Get file metadata
     */
    suspend fun getFileMetadata(
        key: String,
        bucketName: String? = null
    ): CloudFileInfo? = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            
            val metadata = s3Client?.getObjectMetadata(bucket, key)
                ?: return@withContext null
            
            CloudFileInfo(
                id = key,
                name = key.removePrefix(APP_FOLDER_PREFIX),
                size = metadata.contentLength,
                createdTime = metadata.lastModified.time,
                modifiedTime = metadata.lastModified.time,
                mimeType = metadata.contentType ?: "application/octet-stream",
                isFolder = key.endsWith("/")
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get S3 file metadata")
            null
        }
    }

    /**
     * Create a folder in S3 (S3 doesn't have real folders, but we can simulate them)
     */
    suspend fun createFolder(
        folderName: String,
        bucketName: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            val key = "$APP_FOLDER_PREFIX$folderName/"
            
            val metadata = ObjectMetadata().apply {
                contentLength = 0
            }

            val emptyContent = ByteArrayInputStream(ByteArray(0))
            s3Client?.putObject(PutObjectRequest(bucket, key, emptyContent, metadata))
            
            Timber.d("Folder created in S3: $key")
            key
        } catch (e: Exception) {
            Timber.e(e, "Failed to create folder in S3")
            throw IOException("S3 folder creation failed", e)
        }
    }

    /**
     * Search for files (S3 doesn't have native search, so we filter locally)
     */
    suspend fun searchFiles(
        query: String,
        bucketName: String? = null
    ): List<CloudFileInfo> = withContext(Dispatchers.IO) {
        try {
            val allFiles = listFiles(bucketName)
            allFiles.filter { file ->
                file.name.contains(query, ignoreCase = true)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to search files in S3")
            throw IOException("S3 search failed", e)
        }
    }

    /**
     * Get storage statistics
     */
    suspend fun getStorageStats(bucketName: String? = null): StorageStats = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            
            val listObjectsRequest = ListObjectsRequest().apply {
                this.bucketName = bucket
                prefix = APP_FOLDER_PREFIX
            }

            val objectListing = s3Client?.listObjects(listObjectsRequest)
                ?: throw IOException("Failed to get S3 storage stats")
            
            var totalSize = 0L
            var fileCount = 0
            
            objectListing.objectSummaries.forEach { summary ->
                if (!summary.key.endsWith("/")) { // Skip folder markers
                    totalSize += summary.size
                    fileCount++
                }
            }
            
            StorageStats(
                totalSpace = totalSize,
                fileCount = fileCount,
                bucketName = bucket
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get S3 storage stats")
            StorageStats()
        }
    }

    /**
     * Copy file within S3
     */
    suspend fun copyFile(
        sourceKey: String,
        destinationKey: String,
        bucketName: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val bucket = bucketName ?: currentBucket
            
            val copyRequest = CopyObjectRequest(bucket, sourceKey, bucket, destinationKey)
            s3Client?.copyObject(copyRequest)
            
            Timber.d("File copied in S3: $sourceKey to $destinationKey")
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy file in S3")
            throw IOException("S3 copy failed", e)
        }
    }

    /**
     * Move file within S3 (copy + delete)
     */
    suspend fun moveFile(
        sourceKey: String,
        destinationKey: String,
        bucketName: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            copyFile(sourceKey, destinationKey, bucketName)
            deleteFile(sourceKey, bucketName)
            Timber.d("File moved in S3: $sourceKey to $destinationKey")
        } catch (e: Exception) {
            Timber.e(e, "Failed to move file in S3")
            throw IOException("S3 move failed", e)
        }
    }
}

/**
 * Data class for S3 storage statistics
 */
data class StorageStats(
    val totalSpace: Long = 0,
    val fileCount: Int = 0,
    val bucketName: String = ""
)