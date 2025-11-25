package com.aiguru.android_file_encryption.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiguru.android_file_encryption.auth.AuthManager
import com.aiguru.android_file_encryption.cloud.GoogleDriveManager
import com.aiguru.android_file_encryption.cloud.CloudFileInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

sealed class FolderPickerUiState {
    object Loading : FolderPickerUiState()
    data class Success(val folders: List<CloudFileInfo>) : FolderPickerUiState()
    data class Error(val message: String) : FolderPickerUiState()
}

class FolderPickerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<FolderPickerUiState>(FolderPickerUiState.Loading)
    val uiState: StateFlow<FolderPickerUiState> = _uiState

    private var authManager: AuthManager? = null
    private var googleDriveManager: GoogleDriveManager? = null

    fun initialize(authManager: AuthManager?, googleDriveManager: GoogleDriveManager?) {
        this.authManager = authManager
        this.googleDriveManager = googleDriveManager
        loadFolders()
    }

    fun loadFolders() {
        viewModelScope.launch {
            try {
                _uiState.value = FolderPickerUiState.Loading

                val credential = authManager?.getGoogleAccountCredential()
                if (credential == null) {
                    _uiState.value = FolderPickerUiState.Error("Google Drive not authenticated")
                    return@launch
                }

                googleDriveManager?.initialize(credential)

                // List folders from Google Drive root
                val files = googleDriveManager?.listFiles() ?: emptyList()
                val folders = files.filter { it.isFolder }

                _uiState.value = FolderPickerUiState.Success(folders)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load folders")
                _uiState.value = FolderPickerUiState.Error(e.message ?: "Failed to load folders")
            }
        }
    }

    fun createNewFolder(folderName: String) {
        viewModelScope.launch {
            try {
                googleDriveManager?.createFolder(folderName)
                // Reload folders after creating new one
                loadFolders()
            } catch (e: Exception) {
                Timber.e(e, "Failed to create folder")
                _uiState.value = FolderPickerUiState.Error(e.message ?: "Failed to create folder")
            }
        }
    }
}