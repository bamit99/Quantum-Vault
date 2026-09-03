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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import com.aiguru.android_file_encryption.storage.SafStorageManager
import com.aiguru.android_file_encryption.security.VaultEscrow
import com.aiguru.android_file_encryption.ui.screens.AboutScreen
import com.aiguru.android_file_encryption.ui.screens.HomeScreen
import com.aiguru.android_file_encryption.ui.screens.PassphraseSetupScreen
import com.aiguru.android_file_encryption.ui.screens.VaultBrowserScreen
import com.aiguru.android_file_encryption.ui.screens.VaultRestoreScreen
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    // Back UX: main screen asks before exit; every other screen returns to main.
    androidx.activity.compose.BackHandler(enabled = currentScreen != "home") {
        currentScreen = "home"
    }
    androidx.activity.compose.BackHandler(enabled = currentScreen == "home") {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit Quantum Vault?") },
            text = { Text("Your vault stays encrypted and your location is remembered. You can pick up right where you left off.") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    (context as? android.app.Activity)?.finish()
                }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Stay") }
            }
        )
    }

    // main-thread dispatcher usable from worker threads inside this composable scope
    val onMain: (Runnable) -> Unit = { r ->
        if (context is android.app.Activity) context.runOnUiThread(r)
        else { val h = android.os.Handler(android.os.Looper.getMainLooper()); h.post(r) }
    }

    when (currentScreen) {
        "home" -> HomeScreen(
            hasLocation = saf.hasLocation(),
            locationName = saf.locationName(),
            onPickLocation = {
                launchFolderPicker { picked ->
                    if (picked != null) {
                        // Fresh location: set a passphrase now, write escrow, then enter vault
                        currentScreen = "passphrase_setup"
                    }
                }
            },
            onRestoreRequested = {
                currentScreen = "restore"
            },
            onAboutRequested = {
                currentScreen = "about"
            },
            onVaultSelected = {
                if (saf.hasLocation()) currentScreen = "vault" else onShowToast("Pick a vault location first")
            }
        )
        "about" -> AboutScreen(onBack = { currentScreen = "home" })
        "passphrase_setup" -> PassphraseSetupScreen(
            title = "Create vault passphrase",
            confirmLabel = "Create vault",
            onBack = { currentScreen = "home" },
            onConfirm = { pass ->
                // write escrow .vaultkey into the picked location, then enter vault
                Thread {
                    try {
                        val blob = com.aiguru.android_file_encryption.security.VaultEscrow.export(pq, pass)
                        saf.save(com.aiguru.android_file_encryption.security.VaultEscrow.FILE_NAME, blob, "application/octet-stream")
                        onMain {
                            onShowToast("Escrow saved to vault location ✓")
                            currentScreen = "vault"
                        }
                    } catch (e: Exception) {
                        onMain { onShowToast("Escrow export failed: ${e.message}") }
                    }
                }.start()
            },
            onShowToast = onShowToast
        )
        "restore" -> VaultRestoreScreen(
            escrowFound = true, // checked at folder pick; refine when we have tree listing here
            locationName = saf.locationName(),
            onBack = { currentScreen = "home" },
            onRestore = { pass, result ->
                Thread {
                    try {
                        val tree = saf.locationUri()
                            ?: throw IllegalStateException("No vault location picked")
                        val escrowUri = saf.findChild(VaultEscrow.FILE_NAME)
                            ?: throw IllegalStateException(".vaultkey not found in vault location")
                        val fp = VaultEscrow.restoreInto(pq, saf.read(escrowUri), pass)
                        onMain { result(fp); currentScreen = "vault" }
                    } catch (e: Exception) {
                        onMain { result(null) }
                    }
                }.start()
            },
            onShowToast = onShowToast
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