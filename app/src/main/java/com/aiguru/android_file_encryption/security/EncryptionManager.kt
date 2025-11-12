package com.aiguru.android_file_encryption.security

import android.util.Base64
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption manager that handles AES-256 encryption/decryption using JCE
 * Implements client-side encryption for cloud storage security
 */
class EncryptionManager {

    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val SALT_LENGTH = 16
    }

    /**
     * Generate a new AES-256 secret key
     */
    fun generateKey(): SecretKey {
        return try {
            val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
            keyGenerator.init(KEY_SIZE, SecureRandom())
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate encryption key")
            throw SecurityException("Failed to generate encryption key", e)
        }
    }

    /**
     * Convert key to Base64 string for storage
     */
    fun keyToString(key: SecretKey): String {
        return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }

    /**
     * Convert Base64 string back to SecretKey
     */
    fun stringToKey(keyString: String): SecretKey {
        return try {
            val decodedKey = Base64.decode(keyString, Base64.NO_WRAP)
            SecretKeySpec(decodedKey, 0, decodedKey.size, ALGORITHM)
        } catch (e: Exception) {
            Timber.e(e, "Failed to convert string to key")
            throw SecurityException("Invalid key format", e)
        }
    }

    /**
     * Encrypt data using AES-256 GCM mode
     * @param data The data to encrypt
     * @param key The encryption key
     * @return Encrypted data with IV prepended
     */
    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
            
            val encryptedData = cipher.doFinal(data)
            
            // Prepend IV to encrypted data for later decryption
            iv + encryptedData
        } catch (e: Exception) {
            Timber.e(e, "Encryption failed")
            throw SecurityException("Encryption failed", e)
        }
    }

    /**
     * Decrypt data using AES-256 GCM mode
     * @param encryptedData The data to decrypt (with IV prepended)
     * @param key The decryption key
     * @return Decrypted data
     */
    fun decrypt(encryptedData: ByteArray, key: SecretKey): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            
            // Extract IV from the beginning of the data
            val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
            val actualEncryptedData = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)
            
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)
            
            cipher.doFinal(actualEncryptedData)
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed")
            throw SecurityException("Decryption failed", e)
        }
    }

    /**
     * Encrypt file data and return Base64 encoded string
     */
    fun encryptToBase64(data: ByteArray, key: SecretKey): String {
        val encrypted = encrypt(data, key)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * Decrypt Base64 encoded encrypted data
     */
    fun decryptFromBase64(encryptedBase64: String, key: SecretKey): ByteArray {
        val encryptedData = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        return decrypt(encryptedData, key)
    }

    /**
     * Generate a random salt for key derivation
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }
}