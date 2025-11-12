package com.aiguru.android_file_encryption.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Manages biometric authentication (fingerprint, face recognition, iris scanning)
 * Provides secure user authentication for sensitive operations
 */
class BiometricManager(private val context: Context) {

    companion object {
        private const val TAG = "BiometricManager"
    }

    /**
     * Check if biometric authentication is available and ready to use
     */
    fun isBiometricAvailable(): Boolean {
        return try {
            val biometricManager = BiometricManager.from(context)
            when (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )) {
                BiometricManager.BIOMETRIC_SUCCESS -> true
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                    Timber.w("No biometric hardware available")
                    false
                }
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                    Timber.w("Biometric hardware unavailable")
                    false
                }
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    Timber.w("No biometrics enrolled")
                    false
                }
                else -> false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking biometric availability")
            false
        }
    }

    /**
     * Check if device has biometric hardware (even if not enrolled)
     */
    fun hasBiometricHardware(): Boolean {
        return try {
            val biometricManager = BiometricManager.from(context)
            val result = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            result != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE &&
            result != BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        } catch (e: Exception) {
            Timber.e(e, "Error checking biometric hardware")
            false
        }
    }

    /**
     * Check if biometrics are enrolled
     */
    fun isBiometricEnrolled(): Boolean {
        return try {
            val biometricManager = BiometricManager.from(context)
            biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            Timber.e(e, "Error checking biometric enrollment")
            false
        }
    }

    /**
     * Authenticate user using biometrics
     * @param activity The calling activity
     * @param title Title for the biometric prompt
     * @param subtitle Subtitle for the biometric prompt
     * @param description Description for the biometric prompt
     * @return Result of authentication
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Biometric Authentication",
        subtitle: String = "Confirm your identity to continue",
        description: String = "Use your biometric credential to authenticate"
    ): BiometricResult = suspendCancellableCoroutine { continuation ->
        try {
            if (!isBiometricAvailable()) {
                continuation.resume(BiometricResult.Error("Biometric authentication not available"))
                return@suspendCancellableCoroutine
            }

            val executor = ContextCompat.getMainExecutor(context)
            
            val biometricPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Timber.d("Biometric authentication succeeded")
                        continuation.resume(BiometricResult.Success)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Timber.w("Biometric authentication failed")
                        continuation.resume(BiometricResult.Failed)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Timber.e("Biometric authentication error: $errorCode - $errString")
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                                continuation.resume(BiometricResult.Cancelled)
                            }
                            else -> {
                                continuation.resume(BiometricResult.Error(errString.toString()))
                            }
                        }
                    }
                }
            )

            val promptInfo = PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setDescription(description)
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            biometricPrompt.authenticate(promptInfo)
            
            // Handle cancellation
            continuation.invokeOnCancellation {
                Timber.d("Biometric authentication cancelled by coroutine")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error during biometric authentication")
            continuation.resume(BiometricResult.Error(e.message ?: "Authentication failed"))
        }
    }

    /**
     * Authenticate with custom prompt info
     */
    suspend fun authenticateWithPromptInfo(
        activity: FragmentActivity,
        promptInfo: PromptInfo
    ): BiometricResult = suspendCancellableCoroutine { continuation ->
        try {
            if (!isBiometricAvailable()) {
                continuation.resume(BiometricResult.Error("Biometric authentication not available"))
                return@suspendCancellableCoroutine
            }

            val executor = ContextCompat.getMainExecutor(context)
            
            val biometricPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Timber.d("Biometric authentication succeeded")
                        continuation.resume(BiometricResult.Success)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Timber.w("Biometric authentication failed")
                        continuation.resume(BiometricResult.Failed)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Timber.e("Biometric authentication error: $errorCode - $errString")
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                                continuation.resume(BiometricResult.Cancelled)
                            }
                            else -> {
                                continuation.resume(BiometricResult.Error(errString.toString()))
                            }
                        }
                    }
                }
            )

            biometricPrompt.authenticate(promptInfo)
            
            continuation.invokeOnCancellation {
                Timber.d("Biometric authentication cancelled by coroutine")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error during biometric authentication")
            continuation.resume(BiometricResult.Error(e.message ?: "Authentication failed"))
        }
    }

    /**
     * Get available biometric types
     */
    fun getAvailableBiometricTypes(): List<BiometricType> {
        val types = mutableListOf<BiometricType>()
        
        try {
            val biometricManager = BiometricManager.from(context)
            
            // Check for strong biometrics (fingerprint, face, iris)
            if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == 
                BiometricManager.BIOMETRIC_SUCCESS) {
                types.add(BiometricType.STRONG)
            }
            
            // Check for weak biometrics
            if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == 
                BiometricManager.BIOMETRIC_SUCCESS) {
                types.add(BiometricType.WEAK)
            }
            
            // Check for device credentials (PIN, pattern, password)
            if (biometricManager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 
                BiometricManager.BIOMETRIC_SUCCESS) {
                types.add(BiometricType.DEVICE_CREDENTIAL)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error checking biometric types")
        }
        
        return types
    }

    /**
     * Get detailed biometric info
     */
    fun getBiometricInfo(): BiometricInfo {
        return BiometricInfo(
            isAvailable = isBiometricAvailable(),
            isEnrolled = isBiometricEnrolled(),
            hasHardware = hasBiometricHardware(),
            availableTypes = getAvailableBiometricTypes(),
            canAuthenticateWithDeviceCredential = canAuthenticateWithDeviceCredential()
        )
    }

    /**
     * Check if device credential (PIN/pattern/password) can be used
     */
    fun canAuthenticateWithDeviceCredential(): Boolean {
        return try {
            val biometricManager = BiometricManager.from(context)
            biometricManager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            Timber.e(e, "Error checking device credential availability")
            false
        }
    }

    /**
     * Create a basic prompt info builder
     */
    fun createPromptInfoBuilder(
        title: String,
        subtitle: String? = null,
        description: String? = null
    ): PromptInfo.Builder {
        val builder = PromptInfo.Builder()
            .setTitle(title)
        
        subtitle?.let { builder.setSubtitle(it) }
        description?.let { builder.setDescription(it) }
        
        return builder
    }

    /**
     * Encrypt data using biometric-protected key (requires Android 9+)
     */
    fun encryptWithBiometricKey(data: ByteArray): Result<ByteArray> {
        return try {
            // This would integrate with Android Keystore and require biometric authentication
            // for key usage. Implementation depends on specific security requirements.
            throw NotImplementedError("Biometric key encryption requires Android 9+ and specific implementation")
        } catch (e: Exception) {
            Timber.e(e, "Failed to encrypt with biometric key")
            Result.failure(e)
        }
    }

    /**
     * Decrypt data using biometric-protected key (requires Android 9+)
     */
    fun decryptWithBiometricKey(encryptedData: ByteArray): Result<ByteArray> {
        return try {
            // This would integrate with Android Keystore and require biometric authentication
            // for key usage. Implementation depends on specific security requirements.
            throw NotImplementedError("Biometric key decryption requires Android 9+ and specific implementation")
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt with biometric key")
            Result.failure(e)
        }
    }
}

/**
 * Result of biometric authentication
 */
sealed class BiometricResult {
    object Success : BiometricResult()
    object Failed : BiometricResult()
    object Cancelled : BiometricResult()
    data class Error(val message: String) : BiometricResult()
}

/**
 * Available biometric types
 */
enum class BiometricType {
    STRONG,    // Fingerprint, strong face/iris recognition
    WEAK,      // Weak face recognition
    DEVICE_CREDENTIAL  // PIN, pattern, password
}

/**
 * Detailed biometric information
 */
data class BiometricInfo(
    val isAvailable: Boolean,
    val isEnrolled: Boolean,
    val hasHardware: Boolean,
    val availableTypes: List<BiometricType>,
    val canAuthenticateWithDeviceCredential: Boolean
) {
    val canAuthenticate: Boolean
        get() = isAvailable && isEnrolled
}