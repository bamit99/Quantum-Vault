package com.aiguru.android_file_encryption.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiguru.android_file_encryption.security.EncryptionManager
import com.aiguru.android_file_encryption.security.KeyStoreManager
import com.aiguru.android_file_encryption.storage.FileInfo
import com.aiguru.android_file_encryption.storage.FileManager
import com.aiguru.android_file_encryption.storage.StorageStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class FileUiState(
    val isLoading: Boolean = false,
    val recentFiles: List<FileInfo> = emptyList(),
    val storageStats: StorageStats = StorageStats(),
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class FileViewModel(application: Application) : AndroidViewModel(application) {
    
    private val fileManager = FileManager(application)
    private val encryptionManager = EncryptionManager()
    private val keyStoreManager = KeyStoreManager(application)
    
    private val _uiState = MutableStateFlow(FileUiState())
    val uiState: StateFlow<FileUiState> = _uiState.asStateFlow()
    
    init {
        loadRecentFiles()
        loadStorageStats()
    }
    
    fun loadRecentFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val files = fileManager.getEncryptedFiles()
                _uiState.update { 
                    it.copy(
                        recentFiles = files,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load recent files")
                _uiState.update { 
                    it.copy(
                        errorMessage = "Failed to load files: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }
    
    fun loadStorageStats() {
        viewModelScope.launch {
            try {
                val stats = fileManager.getStorageStats()
                _uiState.update { it.copy(storageStats = stats) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load storage stats")
            }
        }
    }
    
    fun encryptAndUploadFile(uri: Uri, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // Read file data
                val fileData = fileManager.readFile(uri)
                val fileName = fileManager.getFileName(uri) ?: "encrypted_file"
                
                // Generate encryption key from password
                val key = generateKeyFromPassword(password)
                
                // Encrypt file data
                val encryptedData = encryptionManager.encrypt(fileData, key)
                
                // Save encrypted file locally
                val encryptedFile = fileManager.saveEncryptedFile(fileName, encryptedData)
                
                // Store encryption key securely
                val keyString = encryptionManager.keyToString(key)
                keyStoreManager.storeKeyInPreferences("key_$fileName", keyString)
                
                _uiState.update { 
                    it.copy(
                        successMessage = "File encrypted successfully: ${encryptedFile.name}",
                        isLoading = false
                    )
                }
                
                // Refresh file list
                loadRecentFiles()
                loadStorageStats()
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to encrypt and upload file")
                _uiState.update { 
                    it.copy(
                        errorMessage = "Encryption failed: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }
    
    fun decryptFile(fileName: String, password: String, onSuccess: (ByteArray) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // Get encrypted file
                val encryptedFile = fileManager.getEncryptedFile(fileName)
                    ?: throw Exception("File not found: $fileName")
                
                // Read encrypted data
                val encryptedData = encryptedFile.readBytes()
                
                // Retrieve encryption key
                val keyString = keyStoreManager.getKeyFromPreferences("key_$fileName")
                    ?: throw Exception("Encryption key not found")
                
                val key = encryptionManager.stringToKey(keyString)
                
                // Decrypt data
                val decryptedData = encryptionManager.decrypt(encryptedData, key)
                
                _uiState.update { it.copy(isLoading = false) }
                onSuccess(decryptedData)
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to decrypt file")
                _uiState.update { 
                    it.copy(
                        errorMessage = "Decryption failed: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }
    
    fun deleteFile(fileName: String) {
        viewModelScope.launch {
            try {
                val file = fileManager.getEncryptedFile(fileName)
                if (file != null) {
                    fileManager.secureDelete(file)
                    keyStoreManager.deleteKey("key_$fileName")
                    
                    _uiState.update { 
                        it.copy(successMessage = "File deleted securely: $fileName") 
                    }
                    
                    loadRecentFiles()
                    loadStorageStats()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete file")
                _uiState.update { 
                    it.copy(errorMessage = "Delete failed: ${e.message}") 
                }
            }
        }
    }
    
    fun cleanupTempFiles() {
        viewModelScope.launch {
            try {
                fileManager.cleanupOldTempFiles()
                _uiState.update { 
                    it.copy(successMessage = "Temporary files cleaned up") 
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup temp files")
            }
        }
    }
    
    fun clearMessages() {
        _uiState.update { 
            it.copy(errorMessage = null, successMessage = null) 
        }
    }
    
    private fun generateKeyFromPassword(password: String): javax.crypto.SecretKey {
        // In a production app, use a proper key derivation function like PBKDF2
        // For demo purposes, we'll use a simple hash-based approach
        val passwordBytes = password.toByteArray()
        val keyBytes = passwordBytes.copyOf(32) // AES-256 needs 32 bytes
        return javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
    }
    
    override fun onCleared() {
        super.onCleared()
        cleanupTempFiles()
    }
}