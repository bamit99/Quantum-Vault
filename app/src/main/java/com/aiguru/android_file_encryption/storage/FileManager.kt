package com.aiguru.android_file_encryption.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages local file operations with proper error handling and security considerations
 */
class FileManager(private val context: Context) {

    companion object {
        private const val APP_FOLDER_NAME = "SecureCloudStorage"
        private const val ENCRYPTED_FOLDER_NAME = "encrypted"
        private const val TEMP_FOLDER_NAME = "temp"
    }

    private val appRootDir: File by lazy {
        File(context.filesDir, APP_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val encryptedDir: File by lazy {
        File(appRootDir, ENCRYPTED_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val tempDir: File by lazy {
        File(appRootDir, TEMP_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Get file name from URI
     */
    fun getFileName(uri: Uri): String? {
        return try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex != -1) {
                            cursor.getString(displayNameIndex)
                        } else null
                    } else null
                } as String?
            } else {
                uri.path?.let { path ->
                    File(path).name
                } as String?
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get file name from URI")
            null
        }
    }

    /**
     * Get file size from URI
     */
    fun getFileSize(uri: Uri): Long {
        return try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            cursor.getLong(sizeIndex)
                        } else 0L
                    } else 0L
                } ?: 0L
            } else {
                uri.path?.let { path ->
                    File(path).length()
                } ?: 0L
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get file size")
            0L
        }
    }

    /**
     * Read file content from URI
     */
    suspend fun readFile(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Failed to open input stream")
            
            inputStream.use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read file")
            throw IOException("Failed to read file", e)
        }
    }

    /**
     * Save encrypted file to app-specific directory
     */
    suspend fun saveEncryptedFile(fileName: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        try {
            val file = File(encryptedDir, fileName)
            FileOutputStream(file).use { outputStream ->
                outputStream.write(data)
            }
            Timber.d("Encrypted file saved: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Timber.e(e, "Failed to save encrypted file")
            throw IOException("Failed to save encrypted file", e)
        }
    }

    /**
     * Create temporary file for processing
     */
    suspend fun createTempFile(prefix: String, suffix: String): File = withContext(Dispatchers.IO) {
        try {
            File.createTempFile(prefix, suffix, tempDir).also {
                Timber.d("Temp file created: ${it.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create temp file")
            throw IOException("Failed to create temp file", e)
        }
    }

    /**
     * Delete temporary files older than specified time
     */
    suspend fun cleanupOldTempFiles(maxAgeMillis: Long = 24 * 60 * 60 * 1000) = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()
            tempDir.listFiles()?.forEach { file ->
                if (currentTime - file.lastModified() > maxAgeMillis) {
                    file.delete()
                    Timber.d("Deleted old temp file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup temp files")
        }
    }

    /**
     * Delete file securely by overwriting with random data
     */
    suspend fun secureDelete(file: File, passes: Int = 3) = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                Timber.w("File does not exist for secure deletion: ${file.absolutePath}")
                return@withContext
            }

            val length = file.length()
            val random = java.security.SecureRandom()

            for (i in 0 until passes) {
                val outputStream = FileOutputStream(file)
                outputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    var bytesWritten: Long = 0
                    
                    while (bytesWritten < length) {
                        random.nextBytes(buffer)
                        val bytesToWrite = minOf(buffer.size.toLong(), length - bytesWritten).toInt()
                        stream.write(buffer, 0, bytesToWrite)
                        bytesWritten += bytesToWrite
                    }
                }
            }

            file.delete()
            Timber.d("File securely deleted: ${file.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to securely delete file")
            // Try normal delete as fallback
            try {
                file.delete()
            } catch (deleteException: Exception) {
                Timber.e(deleteException, "Failed to delete file even with fallback")
            }
        }
    }

    /**
     * Get list of encrypted files
     */
    suspend fun getEncryptedFiles(): List<FileInfo> = withContext(Dispatchers.IO) {
        try {
            encryptedDir.listFiles()?.map { file ->
                FileInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isDirectory = file.isDirectory
                )
            }?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get encrypted files list")
            emptyList()
        }
    }

    /**
     * Get file statistics
     */
    fun getStorageStats(): StorageStats {
        return try {
            val totalSpace = appRootDir.totalSpace
            val usableSpace = appRootDir.usableSpace
            val encryptedSize = getDirectorySize(encryptedDir)
            val tempSize = getDirectorySize(tempDir)

            StorageStats(
                totalSpace = totalSpace,
                usableSpace = usableSpace,
                encryptedDataSize = encryptedSize,
                tempDataSize = tempSize
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get storage stats")
            StorageStats()
        }
    }

    /**
     * Calculate directory size recursively
     */
    private fun getDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        if (!dir.isDirectory) return dir.length()

        return dir.listFiles()?.sumOf { file ->
            if (file.isDirectory) {
                getDirectorySize(file)
            } else {
                file.length()
            }
        } ?: 0
    }

    /**
     * Copy file from source to destination
     */
    suspend fun copyFile(source: File, destination: File) = withContext(Dispatchers.IO) {
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.d("File copied: ${source.absolutePath} to ${destination.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy file")
            throw IOException("Failed to copy file", e)
        }
    }

    /**
     * Check if file exists
     */
    fun fileExists(fileName: String): Boolean {
        return File(encryptedDir, fileName).exists()
    }

    /**
     * Get file by name from encrypted directory
     */
    fun getEncryptedFile(fileName: String): File? {
        val file = File(encryptedDir, fileName)
        return if (file.exists()) file else null
    }
}

/**
 * Data class for file information
 */
data class FileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

/**
 * Data class for storage statistics
 */
data class StorageStats(
    val totalSpace: Long = 0,
    val usableSpace: Long = 0,
    val encryptedDataSize: Long = 0,
    val tempDataSize: Long = 0
) {
    val usedSpace: Long
        get() = totalSpace - usableSpace
    
    val encryptedPercentage: Float
        get() = if (totalSpace > 0) (encryptedDataSize * 100f / totalSpace) else 0f
}