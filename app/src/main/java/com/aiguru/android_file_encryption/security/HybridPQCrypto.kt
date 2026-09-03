package com.aiguru.android_file_encryption.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Bouncy Castle (PQC) — see app/build.gradle.kts
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.SecretWithEncapsulation
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * Post-quantum hybrid encryption layer (QVAULT format v1).
 *
 * DESIGN (NIST/Google-recommended hybrid KEM):
 *
 *   payload ← AES-256-GCM(FileKey, iv)                       — confidentiality
 *   FileKey ← AES-256-GCM( KEK )                            — key wrapping
 *   KEK     ← HKDF-SHA256( X25519_Secret || MLKEM768_Secret , salt , info )
 *
 *  - Classical leg: X25519 ECDH — broken by a CRQC, but fast and universal.
 *  - PQ leg:        ML-KEM-768  (FIPS 203) — believed quantum-hard.
 *  - An attacker must break BOTH to recover the FileKey →
 *    "harvest now, decrypt later" is defeated.
 *  - Payload AES-256-GCM is unchanged (Grover halves it to 128-bit — still fine).
 *
 * The vault's ML-KEM-768 keypair is generated on-device once; its private key is
 * sealed with an Android-Keystore hardware-backed AES-GCM key (Keystore has no PQ
 * primitives yet, so we do software-Kyber + hardware-wrap; documented trade-off).
 * The X25519 static secret lives in the same sealed blob.
 *
 * Wire format (single blob):
 *   bytes 0..5   : MAGIC "QVAULT"
 *   byte  6      : format version (0x01)
 *   bytes 7..38  : X25519 ephemeral public key      (32 B)
 *   bytes 39..   : ML-KEM-768 encapsulation         (1088 B)
 *   bytes ...    : salt                             (32 B)
 *   bytes ...    : AES-GCM(kek, FileKey)  [iv||ct||tag]
 *   bytes ...    : AES-GCM(fileKey, payload)[iv||ct||tag]
 *
 * Legacy blobs (raw EncryptionManager output: iv||ct||tag, no MAGIC) are detected
 * and routed to the legacy AES path — no migration cliff.
 */
@RequiresApi(Build.VERSION_CODES.M)
class HybridPQCrypto(private val context: Context) {

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x51, 0x56, 0x41, 0x55, 0x4C, 0x54) // "QVAULT"
        const val FORMAT_VERSION: Int = 1   // v1: device-wrap only
        const val FORMAT_V2: Int = 2        // v2: device-wrap + passphrase-wrap (dual)
        const val X25519_PUB_LEN = 32
        const val MLKEM768_ENCAP_LEN = 1088          // FIPS 203, k=3 (n=768): 32 + 3*352
        const val SALT_LEN = 32
        private const val PQ_PREFS = "pq_vault"
        private const val PQ_KEYSTORE_ALIAS = "pq_mlkem768_wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val HKDF_INFO = "aiguru-pq-hybrid-v1"
        private const val IV_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val FILE_KEY_LEN = 32
        private const val KEK_LEN = 32
        private const val PREF_PUB = "pub_mlkem_b64"
        private const val PREF_PUB_X = "pub_x25519_b64"
        private const val PREF_SEALED = "sealed_priv_b64"
    }

    private val prefs by lazy { context.getSharedPreferences(PQ_PREFS, Context.MODE_PRIVATE) }

    // ─────────────────────────── vault keys ───────────────────────────

    /** True if the on-device PQ vault keypair exists. */
    fun hasVaultKeys(): Boolean = prefs.getString(PREF_PUB, null) != null

    /** ML-KEM-768 public key, raw bytes (for backup/export). */
    fun vaultMlkemPublicKey(): ByteArray =
        Base64.decode(requireNotNull(prefs.getString(PREF_PUB, null)) { "Vault keys not initialized" }, Base64.NO_WRAP)

    /** X25519 public key, raw bytes (paired with the ML-KEM key in the same sealed blob). */
    fun vaultX25519PublicKey(): ByteArray =
        Base64.decode(prefs.getString(PREF_PUB_X, null)!!, Base64.NO_WRAP)

    /**
     * Generate the vault keypair once (ML-KEM-768 + X25519), seal private halves with a
     * Keystore AES-GCM key, and store base64 blobs in prefs. Idempotent.
     */
    fun ensureVaultKeys() {
        if (hasVaultKeys()) return
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // 1. ML-KEM-768 keypair
        val kemGen = MLKEMKeyPairGenerator()
        kemGen.init(MLKEMKeyGenerationParameters(SecureRandom(), MLKEMParameters.ml_kem_768))
        val kemKp = kemGen.generateKeyPair()
        val kemPub = (kemKp.public as MLKEMPublicKeyParameters).getEncoded()
        val kemPriv = (kemKp.private as MLKEMPrivateKeyParameters).getEncoded()

        // 2. X25519 keypair
        val xGen = X25519KeyPairGenerator()
        xGen.init(KeyGenerationParameters(SecureRandom(), 255))
        val xKp = xGen.generateKeyPair()
        val xPub = (xKp.public as X25519PublicKeyParameters).getEncoded()
        val xPriv = (xKp.private as X25519PrivateKeyParameters).getEncoded()

        // 3. Seal both private halves in ONE AES-GCM envelope
        val wrapKey = getOrCreateWrapKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        val sealedIv = cipher.iv
        val sealedPriv = cipher.doFinal(xPriv.plus(kemPriv))

        prefs.edit()
            .putString(PREF_PUB, Base64.encodeToString(kemPub, Base64.NO_WRAP))
            .putString(PREF_PUB_X, Base64.encodeToString(xPub, Base64.NO_WRAP))
            .putString(PREF_SEALED, Base64.encodeToString(sealedIv.plus(sealedPriv), Base64.NO_WRAP))
            .apply()
        Timber.i("PQ vault keys generated: ML-KEM-768 + X25519, private halves sealed in Keystore")
    }

    // ─────────────────────────── encrypt / decrypt ───────────────────────────

    /**
     * Hybrid-encrypt [data]. The payload key is fresh per call; both KEM legs wrap it.
     * Output = QVAULT blob (self-describing, versioned).
     */
    fun hybridEncrypt(data: ByteArray): ByteArray =
        hybridEncryptInternal(data, passphrase = null)

    /**
     * QVAULT v2: dual-wrapped FileKey — device-key wrap AND passphrase wrap.
     * Either factor alone can unwrap the payload (device loss → passphrase path
     * via escrow restore; passphrase forgotten → device-key leg still works).
     */
    fun hybridEncrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Empty passphrase" }
        return hybridEncryptInternal(data, passphrase = passphrase)
    }

    private fun hybridEncryptInternal(data: ByteArray, passphrase: CharArray?): ByteArray {
        ensureVaultKeys()
        val mlkemPub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, vaultMlkemPublicKey())
        val xPub = X25519PublicKeyParameters(vaultX25519PublicKey(), 0)

        // 1. Fresh per-file AES-256 key
        val fileKey = ByteArray(FILE_KEY_LEN).also { SecureRandom().nextBytes(it) }

        // 2. AES-GCM the payload (EncryptionManager convention: iv||ct||tag)
        val payloadIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val pc = Cipher.getInstance("AES/GCM/NoPadding")
        pc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, payloadIv))
        val payloadCt = pc.doFinal(data)

        // 3. Classical leg: ephemeral X25519 ECDH → 32 B shared secret
        val eph = X25519PrivateKeyParameters(SecureRandom())
        val ephPub = eph.generatePublicKey().getEncoded()
        val sharedClassical = ByteArray(X25519.SCALAR_SIZE)
        eph.generateSecret(xPub, sharedClassical, 0)

        // 4. PQ leg: ML-KEM-768 encapsulate
        val kem: SecretWithEncapsulation = MLKEMGenerator(SecureRandom()).generateEncapsulated(mlkemPub)
        val sharedPQ = kem.secret
        val encapsulation = kem.encapsulation

        // 5. Device KEK = HKDF(classical || PQ, salt) → wrap FileKey
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val kek = hkdf(sharedClassical.plus(sharedPQ), salt)
        val wrappedDevice = aesGcm(encrypt = true, key = kek, data = fileKey)

        if (passphrase == null) {
            // QVAULT v1 (device-wrap only)
            var out = MAGIC
            out = out.plus(FORMAT_VERSION.toByte())
            out = out.plus(ephPub)
            out = out.plus(encapsulation)
            out = out.plus(salt)
            out = out.plus(wrappedDevice)
            out = out.plus(payloadIv)
            out = out.plus(payloadCt)
            return out
        }

        // QVAULT v2 (device-wrap + passphrase-wrap)
        val argonSalt = PassphraseKDF.newSalt()
        val passKek = PassphraseKDF.derive(passphrase, argonSalt)
        val wrappedPass = aesGcm(encrypt = true, key = passKek, data = fileKey)

        var out = MAGIC
        out = out.plus(FORMAT_V2.toByte())
        out = out.plus(ephPub)
        out = out.plus(encapsulation)
        out = out.plus(salt)
        out = out.plus(argonSalt)
        out = out.plus(wrappedDevice)
        out = out.plus(wrappedPass)
        out = out.plus(payloadIv)
        out = out.plus(payloadCt)
        return out
    }

    /**
     * Hybrid-decrypt. Detects legacy (non-QVAULT) blobs and falls back to plain AES.
     * Handles QVAULT v1 (device-wrap) and v2 (device + passphrase dual-wrap).
     */
    fun hybridDecrypt(blob: ByteArray, legacyKey: SecretKey? = null): ByteArray =
        hybridDecryptInternal(blob, passphrase = null, legacyKey = legacyKey)

    /**
     * QVAULT v2 decrypt with passphrase. Tries the passphrase wrap first (cheap GCM
     * auth check); falls back to the device-key wrap if that fails (e.g. escrow-restored
     * keys not needed / passphrase changed after encryption).
     */
    fun hybridDecrypt(blob: ByteArray, passphrase: CharArray, legacyKey: SecretKey? = null): ByteArray =
        hybridDecryptInternal(blob, passphrase = passphrase, legacyKey = legacyKey)

    private fun hybridDecryptInternal(
        blob: ByteArray,
        passphrase: CharArray?,
        legacyKey: SecretKey?
    ): ByteArray {
        if (!hasMagic(blob)) {
            requireNotNull(legacyKey) { "Not a QVAULT blob and no legacy key supplied" }
            return EncryptionManager().decrypt(blob, legacyKey)
        }
        var off = MAGIC.size
        val version = blob[off].toInt(); off += 1

        val ephPub = blob.copyOfRange(off, off + X25519_PUB_LEN); off += X25519_PUB_LEN
        val encapsulation = blob.copyOfRange(off, off + MLKEM768_ENCAP_LEN); off += MLKEM768_ENCAP_LEN
        val salt = blob.copyOfRange(off, off + SALT_LEN); off += SALT_LEN

        // v2 adds argonSalt + passphrase-wrapped FileKey before the payload
        val argonSalt: ByteArray?
        val wrappedPass: ByteArray?
        if (version == FORMAT_V2) {
            argonSalt = blob.copyOfRange(off, off + PassphraseKDF.SALT_LEN); off += PassphraseKDF.SALT_LEN
            wrappedPass = blob.copyOfRange(off, off + (IV_LEN + FILE_KEY_LEN + GCM_TAG_BITS / 8)); off += (IV_LEN + FILE_KEY_LEN + GCM_TAG_BITS / 8)
        } else {
            argonSalt = null
            wrappedPass = null
        }
        require(version == FORMAT_VERSION || version == FORMAT_V2) { "Unsupported QVAULT version $version" }

        val wrappedDevice = blob.copyOfRange(off, off + (IV_LEN + FILE_KEY_LEN + GCM_TAG_BITS / 8)); off += (IV_LEN + FILE_KEY_LEN + GCM_TAG_BITS / 8)
        val payload = blob.copyOfRange(off, blob.size)

        // ── Leg 1: device keys (fast path; may be absent on a fresh device) ──
        val fileKey: ByteArray = try {
            val (xPriv, kemPrivParams) = unsealVaultPrivateKey()
            val xPrivKey = X25519PrivateKeyParameters(xPriv, 0)
            val ephPubKey = X25519PublicKeyParameters(ephPub, 0)
            val sharedClassical = ByteArray(X25519.SCALAR_SIZE)
            xPrivKey.generateSecret(ephPubKey, sharedClassical, 0)

            // PQ leg
            val sharedPQ = MLKEMExtractor(kemPrivParams).extractSecret(encapsulation)

            // Device KEK → unwrap FileKey
            val kek = hkdf(sharedClassical.plus(sharedPQ), salt)
            aesGcm(encrypt = false, key = kek, data = wrappedDevice)
        } catch (e: Exception) {
            // ── Leg 2: passphrase — fully independent of device private keys.
            // Works on a fresh device (post-format, pre-restore) for v2 blobs.
            if (version == FORMAT_V2 && wrappedPass != null && argonSalt != null && passphrase != null) {
                try {
                    val passKek = PassphraseKDF.derive(passphrase, argonSalt)
                    aesGcm(encrypt = false, key = passKek, data = wrappedPass)
                } catch (pEx: Exception) {
                    throw SecurityException("File key unwrap failed (device keys unavailable and passphrase incorrect)", pEx)
                }
            } else {
                throw SecurityException("File key unwrap failed (device key + passphrase both unavailable/incorrect)", e)
            }
        }
        return aesGcm(encrypt = false, key = fileKey, data = payload)
    }

    /** True if [blob] is a QVAULT v2 (passphrase-capable) file. */
    fun isPassphraseCapable(blob: ByteArray): Boolean =
        hasMagic(blob) && blob.size > MAGIC.size && blob[MAGIC.size].toInt() == FORMAT_V2

    // ─────────────────────────── escrow bridge ───────────────────────────

    /** Export the X25519 private scalar for escrow (called by VaultEscrow only). */
    fun exportX25519PrivateForEscrow(): ByteArray =
        unsealVaultPrivateKey().first

    /** Export the ML-KEM-768 private key (encoded) for escrow (called by VaultEscrow only). */
    fun exportMlkemPrivateForEscrow(): ByteArray =
        unsealVaultPrivateKey().second.getEncoded()

    /**
     * Import keys recovered from an escrow blob — overwrites the local vault identity.
     * Re-seals under the SAME Keystore wrap key. Returns the new fingerprint.
     */
    fun importEscrowedKeys(
        xPriv: ByteArray,
        mlkemPriv: ByteArray,
        pubMlkem: ByteArray,
        pubX25519: ByteArray
    ): String {
        val wrapKey = getOrCreateWrapKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        val sealedIv = cipher.iv
        val sealedPriv = cipher.doFinal(xPriv.plus(mlkemPriv))

        prefs.edit()
            .putString(PREF_PUB, Base64.encodeToString(pubMlkem, Base64.NO_WRAP))
            .putString(PREF_PUB_X, Base64.encodeToString(pubX25519, Base64.NO_WRAP))
            .putString(PREF_SEALED, Base64.encodeToString(sealedIv.plus(sealedPriv), Base64.NO_WRAP))
            .apply()
        Timber.i("Escrowed vault keys imported into device vault")
        return VaultEscrow.fingerprint(pubMlkem, pubX25519)
    }

    /** Current vault identity fingerprint (public halves only — safe to display). */
    fun vaultFingerprint(): String? =
        if (hasVaultKeys()) VaultEscrow.fingerprint(vaultMlkemPublicKey(), vaultX25519PublicKey()) else null

    // ─────────────────────────── internals ───────────────────────────

    private fun hkdf(secret: ByteArray, salt: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(secret, salt, HKDF_INFO.toByteArray()))
        val out = ByteArray(KEK_LEN)
        hkdf.generateBytes(out, 0, out.size)
        return out
    }

    private fun hasMagic(blob: ByteArray): Boolean =
        blob.size > MAGIC.size && (0 until MAGIC.size).all { blob[it] == MAGIC[it] }

    /** Single-shot AES-GCM helper. Returns iv||ct||tag when encrypting; expects iv||ct||tag when decrypting. */
    private fun aesGcm(encrypt: Boolean, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (encrypt) {
            val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            return iv + c.doFinal(data)
        } else {
            val iv = data.copyOfRange(0, IV_LEN)
            val ct = data.copyOfRange(IV_LEN, data.size)
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            return c.doFinal(ct)
        }
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(PQ_KEYSTORE_ALIAS)) {
            return ks.getKey(PQ_KEYSTORE_ALIAS, null) as SecretKey
        }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                PQ_KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun unsealVaultPrivateKey(): Pair<ByteArray, MLKEMPrivateKeyParameters> {
        val sealed = Base64.decode(prefs.getString(PREF_SEALED, null)!!, Base64.NO_WRAP)
        val wrapKey = getOrCreateWrapKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, sealed.copyOfRange(0, IV_LEN)))
        val both = c.doFinal(sealed.copyOfRange(IV_LEN, sealed.size))
        val xPriv = both.copyOfRange(0, X25519PrivateKeyParameters.KEY_SIZE)
        val kemPriv = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, both.copyOfRange(X25519PrivateKeyParameters.KEY_SIZE, both.size))
        return xPriv to kemPriv
    }
}