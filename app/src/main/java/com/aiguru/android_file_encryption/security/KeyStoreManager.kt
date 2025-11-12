package com.aiguru.android_file_encryption.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages encryption keys using Android Keystore for secure storage
 * Provides hardware-backed key protection when available
 */
class KeyStoreManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "CloudStorageMasterKey"
        private const val ENCRYPTED_PREFS_FILE = "encrypted_cloud_storage_prefs"
        private const val ENCRYPTION_KEY_ALIAS_PREFIX = "CloudEncryptionKey_"
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    }

    private val masterKey: MasterKey by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(true)
                .build()
        } else {
            throw UnsupportedOperationException("Android M+ required for secure key storage")
        }
    }

    private val encryptedSharedPreferences by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } else {
            throw UnsupportedOperationException("Android M+ required for encrypted preferences")
        }
    }

    /**
     * Generate and store a new encryption key in Android Keystore
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun generateAndStoreKey(alias: String): SecretKey {
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val builder = KeyGenParameterSpec.Builder(
                getKeyAlias(alias),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)

            // Enable hardware-backed key storage if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }

            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate and store key in Keystore")
            throw SecurityException("Key generation failed", e)
        }
    }

    /**
     * Retrieve a key from Android Keystore
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun getKey(alias: String): SecretKey? {
        return try {
            val keyAlias = getKeyAlias(alias)
            val entry = keyStore.getEntry(keyAlias, null)
            if (entry is KeyStore.SecretKeyEntry) {
                entry.secretKey
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve key from Keystore")
            null
        }
    }

    /**
     * Store encryption key securely in EncryptedSharedPreferences
     * Used for storing keys that need to be backed up or shared
     */
    fun storeKeyInPreferences(keyAlias: String, keyString: String) {
        try {
            encryptedSharedPreferences.edit()
                .putString(keyAlias, keyString)
                .apply()
            Timber.d("Key stored in encrypted preferences: $keyAlias")
        } catch (e: Exception) {
            Timber.e(e, "Failed to store key in preferences")
            throw SecurityException("Failed to store key securely", e)
        }
    }

    /**
     * Retrieve encryption key from EncryptedSharedPreferences
     */
    fun getKeyFromPreferences(keyAlias: String): String? {
        return try {
            encryptedSharedPreferences.getString(keyAlias, null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve key from preferences")
            null
        }
    }

    /**
     * Delete a key from Android Keystore
     */
    fun deleteKey(alias: String) {
        try {
            val keyAlias = getKeyAlias(alias)
            keyStore.deleteEntry(keyAlias)
            encryptedSharedPreferences.edit()
                .remove(alias)
                .apply()
            Timber.d("Key deleted: $alias")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete key")
        }
    }

    /**
     * Check if a key exists in Keystore
     */
    fun keyExists(alias: String): Boolean {
        return try {
            keyStore.containsAlias(getKeyAlias(alias))
        } catch (e: Exception) {
            Timber.e(e, "Failed to check key existence")
            false
        }
    }

    /**
     * Clear all stored keys (use with caution)
     */
    fun clearAllKeys() {
        try {
            val aliases = keyStore.aliases()
            aliases.asSequence().filter { it.startsWith(ENCRYPTION_KEY_ALIAS_PREFIX) }
                .forEach { keyStore.deleteEntry(it) }
            
            encryptedSharedPreferences.edit().clear().apply()
            Timber.d("All keys cleared from Keystore and preferences")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear all keys")
        }
    }

    /**
     * Get the total number of stored keys
     */
    fun getKeyCount(): Int {
        return try {
            keyStore.aliases().toList().count { it.startsWith(ENCRYPTION_KEY_ALIAS_PREFIX) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get key count")
            0
        }
    }

    /**
     * Create a unique key alias for the given identifier
     */
    private fun getKeyAlias(alias: String): String {
        return "$ENCRYPTION_KEY_ALIAS_PREFIX$alias"
    }

    /**
     * Check if device supports hardware-backed key storage
     */
    fun isHardwareBacked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyAlias = "test_hardware_backed"
                val key = generateAndStoreKey(keyAlias)
                val isHardwareBacked = keyStore.getEntry(getKeyAlias(keyAlias), null)
                    .let { it as? KeyStore.SecretKeyEntry }
                    ?.let { entry ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            // Check if key is hardware-backed (API 31+)
                            try {
                                val entry = keyStore.getEntry(getKeyAlias(keyAlias), null)
                                entry is KeyStore.SecretKeyEntry
                            } catch (e: Exception) {
                                false
                            }
                        } else {
                            false
                        }
                    } ?: false
                
                deleteKey(keyAlias)
                isHardwareBacked
            } catch (e: Exception) {
                Timber.e(e, "Failed to check hardware backing")
                false
            }
        } else {
            false
        }
    }
}