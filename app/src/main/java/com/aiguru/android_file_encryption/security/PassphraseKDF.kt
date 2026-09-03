package com.aiguru.android_file_encryption.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom

/**
 * Argon2id passphrase KDF (memory-hard, GPU/ASIC resistant).
 *
 * Interactive-phone tuning: t=3 passes, 64 MiB memory, parallelism 1.
 * Derivation takes ~0.5-1.5s on a mid-range phone — deliberate friction
 * for offline brute-force attempts against a stolen escrow file.
 *
 * Salt is PUBLIC by design — stored with the vault. The passphrase is the secret.
 */
object PassphraseKDF {

    const val SALT_LEN = 32
    const val KEY_LEN = 32

    // Phone-tuned Argon2id cost (OWASP interactive baseline, adjusted for mobile)
    private const val ITERATIONS = 3
    private const val MEMORY_KIB = 64 * 1024   // 64 MiB
    private const val PARALLELISM = 1

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }

    /** Derive a 32-byte key from [passphrase] and [salt]. ~0.5-1.5s on phone. */
    fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LEN) { "Argon2 salt must be $SALT_LEN bytes" }
        require(passphrase.isNotEmpty()) { "Empty passphrase" }
        val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ITERATIONS)
            .withMemoryAsKB(MEMORY_KIB)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(p)
        val out = ByteArray(KEY_LEN)
        gen.generateBytes(passphrase, out, 0, out.size)
        return out
    }

    /** Basic strength check for UI. Returns empty string if acceptable, else reason. */
    fun strengthProblem(passphrase: String): String? = when {
        passphrase.length < 12 && passphrase.split(' ').filter { it.isNotBlank() }.size < 4 ->
            "Use at least 12 characters or a 4-word passphrase"
        passphrase.lowercase() in setOf("password", "passphrase", "123456789012", "password1234") ->
            "That passphrase is a gift to attackers — pick something unique"
        else -> null
    }
}