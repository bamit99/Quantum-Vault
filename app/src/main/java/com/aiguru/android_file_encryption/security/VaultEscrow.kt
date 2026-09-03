package com.aiguru.android_file_encryption.security

import android.content.Context
import android.util.Base64
import androidx.annotation.RequiresApi
import android.os.Build
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted key escrow (the "format the phone" recovery path).
 *
 * A .vaultkey blob contains the vault's PQ private halves (X25519 + ML-KEM-768),
 * AES-GCM-encrypted under Argon2id(passphrase, escrowSalt). It lives IN the vault
 * folder (next to the .qvault files) so a fresh install can:
 *
 *   pick folder → find .vaultkey → enter passphrase → restore keys → decrypt files
 *
 * The escrow is useless without the passphrase; the passphrase is useless without
 * the escrow file (or the original device). Together they are the recovery kit.
 *
 * .vaultkey wire format:
 *   magic "VKEY1" (6B)
 *   argonSalt        (32B)
 *   AES-GCM(passKek, payload)  [iv(12B) || ct || tag]
 *     payload := xPriv(32B) || mlkemPriv(...) || pubMlkem(1184B) || pubX25519(32B)
 *     (public halves included so restore can validate the recovered keys)
 */
@RequiresApi(Build.VERSION_CODES.M)
object VaultEscrow {

    private val MAGIC = byteArrayOf(0x56, 0x4B, 0x45, 0x59, 0x31, 0x00) // "VKEY1\0"
    const val FILE_NAME = ".vaultkey"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val X25519_PRIV_LEN = 32
    private const val X25519_PUB_LEN = 32
    private const val MLKEM768_PUB_LEN = 1184

    /**
     * Build the escrow blob from the CURRENT device vault keys under [passphrase].
     * Returns bytes ready to be saved as ".vaultkey" in the vault folder.
     */
    fun export(pq: HybridPQCrypto, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Empty passphrase" }
        val xPriv = pq.exportX25519PrivateForEscrow()
        val mlkemPriv = pq.exportMlkemPrivateForEscrow()
        val pubMlkem = pq.vaultMlkemPublicKey()
        val pubX25519 = pq.vaultX25519PublicKey()
        val payload = xPriv + mlkemPriv + pubMlkem + pubX25519

        val escrowSalt = PassphraseKDF.newSalt()
        val kek = PassphraseKDF.derive(passphrase, escrowSalt)

        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmIv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), javax.crypto.spec.GCMParameterSpec(TAG_BITS, gcmIv))
        val iv = gcmIv
        val ct = c.doFinal(payload)

        return MAGIC + escrowSalt + iv + ct
    }

    /**
     * Restore vault keys from an escrow blob using [passphrase].
     * Returns the public fingerprint on success; wipes nothing — caller decides
     * whether to overwrite an existing (conflicting) device vault.
     */
    fun restoreInto(pq: HybridPQCrypto, blob: ByteArray, passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "Empty passphrase" }
        require(blob.size > MAGIC.size + PassphraseKDF.SALT_LEN + IV_LEN + 32) { ".vaultkey blob too small / corrupted" }
        for (i in MAGIC.indices) {
            require(blob[i] == MAGIC[i]) { "Not a VKEY escrow blob" }
        }
        var off = MAGIC.size
        val escrowSalt = blob.copyOfRange(off, off + PassphraseKDF.SALT_LEN); off += PassphraseKDF.SALT_LEN
        val iv = blob.copyOfRange(off, off + IV_LEN); off += IV_LEN
        val ct = blob.copyOfRange(off, blob.size)

        val kek = PassphraseKDF.derive(passphrase, escrowSalt)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv))
        val payload = c.doFinal(ct) // wrong passphrase throws AEADBadTagException here — clean failure

        val xPriv = payload.copyOfRange(0, X25519_PRIV_LEN)
        val mlkemPriv = payload.copyOfRange(X25519_PRIV_LEN, payload.size - X25519_PUB_LEN - MLKEM768_PUB_LEN)
        val pubMlkem = payload.copyOfRange(payload.size - X25519_PUB_LEN - MLKEM768_PUB_LEN, payload.size - X25519_PUB_LEN)
        val pubX25519 = payload.copyOfRange(payload.size - X25519_PUB_LEN, payload.size)

        val fp = pq.importEscrowedKeys(xPriv, mlkemPriv = mlkemPriv, pubMlkem = pubMlkem, pubX25519 = pubX25519)
        Timber.i("Vault keys restored from escrow (fingerprint ${fp.take(16)}…)")
        return fp
    }

    /** Quick sanity: is this blob a VKEY escrow? */
    fun isEscrowBlob(blob: ByteArray): Boolean =
        blob.size > MAGIC.size && (0 until MAGIC.size).all { blob[it] == MAGIC[it] }

    /**
     * Verify [passphrase] against an escrow blob WITHOUT importing anything.
     * Costs one Argon2id derivation (~0.5-1.5s) — the unlock gate.
     * Returns false on wrong passphrase (GCM auth failure) or corrupt blob.
     */
    fun verifyPassphrase(blob: ByteArray, passphrase: CharArray): Boolean {
        return try {
            require(blob.size > MAGIC.size + PassphraseKDF.SALT_LEN + IV_LEN + 16) { "blob too small" }
            for (i in MAGIC.indices) {
                if (blob[i] != MAGIC[i]) return false
            }
            var off = MAGIC.size
            val escrowSalt = blob.copyOfRange(off, off + PassphraseKDF.SALT_LEN); off += PassphraseKDF.SALT_LEN
            val iv = blob.copyOfRange(off, off + IV_LEN); off += IV_LEN
            val ct = blob.copyOfRange(off, blob.size)

            val kek = PassphraseKDF.derive(passphrase, escrowSalt)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv))
            c.doFinal(ct)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** SHA-256 fingerprint (base64) of the vault public halves — identity check after restore. */
    fun fingerprint(pubMlkem: ByteArray, pubX25519: ByteArray): String {
        val digest = org.bouncycastle.crypto.digests.SHA256Digest()
        val all = pubX25519 + pubMlkem
        digest.update(all, 0, all.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }
}