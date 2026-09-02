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

import com.aiguru.android_file_encryption.storage.SafStorageManager
import com.aiguru.android_file_encryption.ui.screens.HomeScreen
import com.aiguru.android_file_encryption.ui.screens.VaultBrowserScreen
import com.aiguru.android_file_encryption.ui.theme.Android_File_EncryptionTheme

class MainActivity : ComponentActivity() {
    private lateinit var safStorage: SafStorageManager
    private lateinit var pqCrypto: com.aiguru.android_file_encryption.security.HybridPQCrypto

    private lateinit var folderPickerLauncher: ActivityResultLauncher<android.content.Intent>
    private lateinit var openFileLauncher: ActivityResultLauncher<Array<String>>

    // invoked when the SAF folder picker returns
    private var pendingFolderCallback: ((String?) -> Unit)? = null
    // invoked when the user picks a file to add to the vault
    private var pendingFileCallback: ((android.net.Uri?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        safStorage = SafStorageManager(this)
        pqCrypto = com.aiguru.android_file_encryption.security.HybridPQCrypto(this)
        pqCrypto.ensureVaultKeys()

        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = pendingFolderCallback
            pendingFolderCallback = null
            val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
            if (uri != null) {
                val name = safStorage.onFolderPicked(uri)
                Toast.makeText(this, "Vault location: $name", Toast.LENGTH_SHORT).show()
                cb?.invoke(name)
            } else {
                cb?.invoke(null)
            }
        }

        openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = pendingFileCallback
            pendingFileCallback = null
            cb?.invoke(uri)
        }

        enableEdgeToEdge()
        setContent {
            Android_File_EncryptionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        modifier = Modifier.padding(innerPadding),
                        saf = safStorage,
                        pq = pqCrypto,
                        launchFolderPicker = { onDone ->
                            pendingFolderCallback = onDone
                            folderPickerLauncher.launch(safStorage.openFolderPickerIntent())
                        },
                        launchFilePicker = { onDone ->
                            pendingFileCallback = onDone
                            openFileLauncher.launch(arrayOf("*/*"))
                        },
                        onShowToast = { message ->
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    saf: SafStorageManager,
    pq: com.aiguru.android_file_encryption.security.HybridPQCrypto,
    launchFolderPicker: ((String?) -> Unit) -> Unit,
    launchFilePicker: ((android.net.Uri?) -> Unit) -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    var currentScreen by remember { mutableStateOf("home") }

    when (currentScreen) {
        "home" -> HomeScreen(
            hasLocation = saf.hasLocation(),
            locationName = saf.locationName(),
            onPickLocation = {
                launchFolderPicker { picked ->
                    if (picked != null) {
                        // Location saved — flow straight into the vault
                        currentScreen = "vault"
                    }
                }
            },
            onVaultSelected = {
                if (saf.hasLocation()) currentScreen = "vault" else onShowToast("Pick a vault location first")
            }
        )
        "vault" -> VaultBrowserScreen(
            saf = saf,
            pq = pq,
            launchFilePicker = launchFilePicker,
            onNavigateBack = { currentScreen = "home" },
            onShowToast = onShowToast
        )
    }
}