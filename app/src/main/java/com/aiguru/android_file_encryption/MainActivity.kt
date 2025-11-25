package com.aiguru.android_file_encryption

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import com.aiguru.android_file_encryption.auth.AuthManager
import com.aiguru.android_file_encryption.ui.screens.HomeScreen
import com.aiguru.android_file_encryption.ui.screens.AddAccountScreen
import com.aiguru.android_file_encryption.ui.screens.FolderPickerScreen
import com.aiguru.android_file_encryption.ui.screens.CloudProvider
import com.aiguru.android_file_encryption.ui.theme.Android_File_EncryptionTheme

class MainActivity : ComponentActivity() {
    private lateinit var authManager: AuthManager
    private lateinit var googleDriveManager: com.aiguru.android_file_encryption.cloud.GoogleDriveManager
    private lateinit var googleSignInLauncher: ActivityResultLauncher<android.content.Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize AuthManager and GoogleDriveManager
        authManager = AuthManager(this)
        googleDriveManager = com.aiguru.android_file_encryption.cloud.GoogleDriveManager(this)

        // Set up Google Sign-In result handler
        googleSignInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    // Handle authentication result - this will be called from the composable
                    handleSignInResult(data)
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            Android_File_EncryptionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        modifier = Modifier.padding(innerPadding),
                        authManager = authManager,
                        googleDriveManager = googleDriveManager,
                        onStartGoogleSignIn = { startGoogleSignIn() },
                        onShowToast = { message ->
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    private fun startGoogleSignIn() {
        try {
            val signInIntent = authManager.getSignInIntent()
            googleSignInLauncher.launch(signInIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start Google Sign-In", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSignInResult(data: android.content.Intent) {
        val success = authManager.handleSignInResult(data)
        if (success) {
            // Navigate to folder picker instead of just showing toast
            // This will be handled by the composable navigation
            Toast.makeText(this@MainActivity, "Authentication successful! Select a folder for your vault.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this@MainActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    authManager: com.aiguru.android_file_encryption.auth.AuthManager,
    googleDriveManager: com.aiguru.android_file_encryption.cloud.GoogleDriveManager,
    onStartGoogleSignIn: () -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    var currentScreen by remember { mutableStateOf("home") }
    var selectedProvider by remember { mutableStateOf<CloudProvider?>(null) }

    when (currentScreen) {
        "home" -> HomeScreen(
            onAddAccount = { currentScreen = "add_account" },
            onVaultSelected = { vaultId -> /* TODO: Navigate to vault */ }
        )
        "add_account" -> AddAccountScreen(
            onProviderSelected = { provider ->
                selectedProvider = provider
                when (provider) {
                    CloudProvider.GoogleDrive -> {
                        onStartGoogleSignIn()
                        // After successful auth, navigate to folder picker
                        currentScreen = "folder_picker"
                    }
                    CloudProvider.OneDrive -> {
                        // TODO: Implement OneDrive authentication
                        onShowToast("OneDrive authentication coming soon!")
                        currentScreen = "home"
                    }
                    else -> {
                        onShowToast("${provider.displayName} coming soon!")
                        currentScreen = "home"
                    }
                }
            },
            onBack = { currentScreen = "home" }
        )
        "folder_picker" -> FolderPickerScreen(
            onFolderSelected = { folderId, folderName ->
                // TODO: Create vault in selected folder
                onShowToast("Vault created in: $folderName")
                currentScreen = "home"
            },
            onBack = { currentScreen = "add_account" }
        )
    }
}