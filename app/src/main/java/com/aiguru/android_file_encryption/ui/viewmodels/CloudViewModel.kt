package com.aiguru.android_file_encryption.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiguru.android_file_encryption.cloud.CloudFileInfo
import com.aiguru.android_file_encryption.cloud.GoogleDriveManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class CloudUiState(
    val isLoading: Boolean = false,
    val files: List<CloudFileInfo> = emptyList(),
    val searchQuery: String = "",
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val currentUser: String? = "Demo User"
)

class CloudViewModel(application: Application) : AndroidViewModel(application) {

    private val googleDriveManager = GoogleDriveManager(application)

    private val _uiState = MutableStateFlow(CloudUiState())
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        // For demo purposes, we'll simulate some files
        loadDemoFiles()
    }

    private fun loadDemoFiles() {
        _uiState.update {
            it.copy(
                files = listOf(
                    CloudFileInfo(
                        id = "demo1",
                        name = "encrypted_document.pdf",
                        size = 1024000,
                        createdTime = System.currentTimeMillis() - 86400000,
                        modifiedTime = System.currentTimeMillis() - 3600000,
                        mimeType = "application/pdf",
                        isFolder = false
                    ),
                    CloudFileInfo(
                        id = "demo2",
                        name = "secure_images",
                        size = 0,
                        createdTime = System.currentTimeMillis() - 172800000,
                        modifiedTime = System.currentTimeMillis() - 7200000,
                        mimeType = "application/vnd.google-apps.folder",
                        isFolder = true
                    ),
                    CloudFileInfo(
                        id = "demo3",
                        name = "private_notes.txt",
                        size = 512000,
                        createdTime = System.currentTimeMillis() - 259200000,
                        modifiedTime = System.currentTimeMillis() - 1800000,
                        mimeType = "text/plain",
                        isFolder = false
                    )
                )
            )
        }
    }

    fun loadCloudFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // For demo, just reload the demo files
                loadDemoFiles()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Demo files loaded successfully"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load cloud files")
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to load files: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        if (query.isNotBlank()) {
            searchFiles(query)
        } else {
            loadDemoFiles()
        }
    }

    private fun searchFiles(query: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // For demo, filter the demo files
                val allFiles = listOf(
                    CloudFileInfo("demo1", "encrypted_document.pdf", 1024000, System.currentTimeMillis() - 86400000, System.currentTimeMillis() - 3600000, "application/pdf", false),
                    CloudFileInfo("demo2", "secure_images", 0, System.currentTimeMillis() - 172800000, System.currentTimeMillis() - 7200000, "application/vnd.google-apps.folder", true),
                    CloudFileInfo("demo3", "private_notes.txt", 512000, System.currentTimeMillis() - 259200000, System.currentTimeMillis() - 1800000, "text/plain", false)
                )

                val searchResults = allFiles.filter {
                    it.name.contains(query, ignoreCase = true)
                }

                _uiState.update {
                    it.copy(
                        files = searchResults,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to search cloud files")
                _uiState.update {
                    it.copy(
                        errorMessage = "Search failed: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun createFolder(folderName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // For demo, just add a new folder to the list
                val newFolder = CloudFileInfo(
                    id = "folder_${System.currentTimeMillis()}",
                    name = folderName,
                    size = 0,
                    createdTime = System.currentTimeMillis(),
                    modifiedTime = System.currentTimeMillis(),
                    mimeType = "application/vnd.google-apps.folder",
                    isFolder = true
                )

                _uiState.update {
                    it.copy(
                        files = it.files + newFolder,
                        successMessage = "Folder created: $folderName",
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to create folder")
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to create folder: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun deleteFile(fileId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // For demo, remove the file from the list
                _uiState.update {
                    it.copy(
                        files = it.files.filter { it.id != fileId },
                        successMessage = "File deleted successfully",
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete file")
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to delete file: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun downloadFile(fileId: String, onSuccess: (ByteArray) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // For demo, return some dummy encrypted data
                val dummyData = "This is encrypted demo data for file $fileId".toByteArray()
                _uiState.update { it.copy(isLoading = false) }
                onSuccess(dummyData)
            } catch (e: Exception) {
                Timber.e(e, "Failed to download file")
                _uiState.update {
                    it.copy(
                        errorMessage = "Download failed: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun uploadEncryptedFile(fileName: String, encryptedData: ByteArray) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // For demo, add the file to the list
                val newFile = CloudFileInfo(
                    id = "file_${System.currentTimeMillis()}",
                    name = fileName,
                    size = encryptedData.size.toLong(),
                    createdTime = System.currentTimeMillis(),
                    modifiedTime = System.currentTimeMillis(),
                    mimeType = "application/octet-stream",
                    isFolder = false
                )

                _uiState.update {
                    it.copy(
                        files = it.files + newFile,
                        successMessage = "File uploaded successfully",
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to upload file")
                _uiState.update {
                    it.copy(
                        errorMessage = "Upload failed: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update {
            it.copy(errorMessage = null, successMessage = null)
        }
    }

    fun signOut() {
        // For demo, just clear the files
        _uiState.update {
            it.copy(
                files = emptyList(),
                successMessage = "Demo sign out successful"
            )
        }
    }
}