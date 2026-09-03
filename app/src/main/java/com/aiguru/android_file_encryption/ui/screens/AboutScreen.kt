package com.aiguru.android_file_encryption.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * About screen — app identity, author info, license summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text("🔐", style = MaterialTheme.typography.displayLarge)
            Text(
                "Quantum Vault",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                "Post-quantum encrypted file vault",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Version 4.0 (recovery)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Crypto stack
            Text(
                "CRYPTOGRAPHY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "X25519 + ML-KEM-768 (FIPS 203) hybrid post-quantum KEM\n" +
                    "AES-256-GCM payloads · Argon2id passphrase binding\n" +
                    "Android Keystore hardware key sealing",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Author
            Text(
                "AUTHOR",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "AMIT BHATNAGAR",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/amitxbhatnagar/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) {
                Text("linkedin.com/in/amitxbhatnagar")
            }

            HorizontalDivider()

            // License
            Text(
                "LICENSE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Copyright © 2026 Amit Bhatnagar. All rights reserved.\n\n" +
                    "Free for personal, non-commercial use.\n" +
                    "Commercial use requires prior written permission.\n\n" +
                    "NO WARRANTY. You alone are responsible for your passphrase — " +
                    "the author cannot recover your data if the passphrase and device keys are lost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bamit99/Android_File_Encryption"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) {
                Text("View full license on GitHub")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Be the Omarch of your own secrets. 🔒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}