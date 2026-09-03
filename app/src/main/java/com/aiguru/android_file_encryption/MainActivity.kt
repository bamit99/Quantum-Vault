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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import com.aiguru.android_file_encryption.storage.SafStorageManager
import com.aiguru.android_file_encryption.security.VaultEscrow
import com.aiguru.android_file_encryption.ui.screens.AboutScreen
import com.aiguru.android_file_encryption.ui.screens.DeleteVaultScreen
import com.aiguru.android_file_encryption.ui.screens.HomeScreen
import com.aiguru.android_file_encryption.ui.screens.PassphraseSetupScreen
import com.aiguru.android_file_encryption.ui.screens.VaultBrowserScreen
import com.aiguru.android_file_encryption.ui.screens.VaultRestoreScreen
import com.aiguru.android_file_encryption.ui.screens.VaultUnlockScreen
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
    // session unlock state: vault stays locked until passphrase verified (once per app open)
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var unlockBusy by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf<String?>(null) }

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
                        // Vault-exists guard: does this folder already contain a vault?
                        Thread {
                            val existing = try { saf.findChild(VaultEscrow.FILE_NAME) } catch (e: Exception) { null }
                            onMain {
                                if (existing != null) {
                                    onShowToast("This folder already contains a Quantum Vault (.vaultkey). Use 'Restore vault on this device' to adopt it, or pick a different folder.")
                                    // Do NOT proceed to passphrase setup — vault untouched
                                } else {
                                    // Fresh location: set a passphrase now, write escrow, then enter vault
                                    currentScreen = "passphrase_setup"
                                }
                            }
                        }.start()
                    }
                }
            },
            onDeleteVaultRequested = {
                currentScreen = "delete_vault"
            },
            onRestoreRequested = {
                currentScreen = "restore"
            },
            onAboutRequested = {
                currentScreen = "about"
            },
            onVaultSelected = {
                if (!saf.hasLocation()) {
                    onShowToast("Pick a vault location first")
                } else if (!unlocked) {
                    unlockError = null
                    currentScreen = "unlock"
                } else {
                    currentScreen = "vault"
                }
            }
        )
        "unlock" -> VaultUnlockScreen(
            locationName = saf.locationName(),
            busy = unlockBusy,
            error = unlockError,
            onPassphraseSubmit = { pass ->
                unlockBusy = true
                unlockError = null
                Thread {
                    try {
                        val escrowUri = saf.findChild(VaultEscrow.FILE_NAME)
                            ?: throw IllegalStateException("No .vaultkey found in vault location")
                        val ok = VaultEscrow.verifyPassphrase(saf.read(escrowUri), pass)
                        onMain {
                            unlockBusy = false
                            if (ok) {
                                unlocked = true
                                currentScreen = "vault"
                            } else {
                                unlockError = "Wrong passphrase"
                            }
                        }
                    } catch (e: Exception) {
                        onMain {
                            unlockBusy = false
                            unlockError = e.message ?: "Verification failed"
                        }
                    }
                }.start()
            }
        )
        "about" -> AboutScreen(onBack = { currentScreen = "home" })
        "delete_vault" -> DeleteVaultScreen(
            locationName = saf.locationName(),
            onBack = { currentScreen = "home" },
            onUnlinked = {
                Thread {
                    // Wipe local key material + forget location (cloud untouched)
                    try { pq.wipeLocalKeys() } catch (e: Exception) { /* already clean */ }
                    saf.forgetLocation()
                    onMain {
                        unlocked = false
                        onShowToast("Vault unlinked from this device. Files remain in the cloud folder — restorable anytime.")
                        currentScreen = "home"
                    }
                }.start()
            },
            onDestroyed = {
                Thread {
                    var deleted = 0; var failed = 0
                    try {
                        // 1. Delete escrow first (removes the recovery path)
                        saf.findChild(VaultEscrow.FILE_NAME)?.let {
                            if (saf.deleteDocument(it)) deleted++ else failed++
                        }
                        // 2. Delete every encrypted file
                        for ((uri, _) in saf.listQvaultDocs()) {
                            if (saf.deleteDocument(uri)) deleted++ else failed++
                        }
                        // 3. Wipe local keys + forget location
                        try { pq.wipeLocalKeys() } catch (e: Exception) { }
                        saf.forgetLocation()
                    } catch (e: Exception) { failed++ }
                    onMain {
                        unlocked = false
                        if (failed > 0) {
                            onShowToast("Vault destroy finished with $failed error(s) — $deleted items deleted. CHECK THE FOLDER for leftovers (.vaultkey is the critical one).")
                        } else {
                            onShowToast("Vault destroyed — $deleted items deleted. Nothing recoverable remains.")
                        }
                        currentScreen = "home"
                    }
                }.start()
            },
            onShowToast = onShowToast
        )
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
                        onMain { result(fp); unlocked = true; currentScreen = "vault" }
                    } catch (e: Exception) {
                        onMain { result(null) }
                    }
                }.start()
            },
            onShowToast = onShowToast
        )
        "vault" -> if (unlocked) {
            VaultBrowserScreen(
                saf = saf,
                pq = pq,
                launchFilePicker = launchFilePicker,
                onNavigateBack = { currentScreen = "home" },
                onShowToast = onShowToast
            )
        } else {
            // direct navigation into locked vault (e.g. after process death) → gate
            currentScreen = "unlock"
            VaultUnlockScreen(
                locationName = saf.locationName(),
                busy = unlockBusy,
                error = unlockError,
                onPassphraseSubmit = { pass ->
                    unlockBusy = true
                    unlockError = null
                    Thread {
                        try {
                            val escrowUri = saf.findChild(VaultEscrow.FILE_NAME)
                                ?: throw IllegalStateException("No .vaultkey found in vault location")
                            val ok = VaultEscrow.verifyPassphrase(saf.read(escrowUri), pass)
                            onMain {
                                unlockBusy = false
                                if (ok) { unlocked = true; currentScreen = "vault" }
                                else unlockError = "Wrong passphrase"
                            }
                        } catch (e: Exception) {
                            onMain { unlockBusy = false; unlockError = e.message ?: "Verification failed" }
                        }
                    }.start()
                }
            )
        }
    }
}