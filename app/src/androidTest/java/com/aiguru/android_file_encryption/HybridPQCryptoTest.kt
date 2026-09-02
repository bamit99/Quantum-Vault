package com.aiguru.android_file_encryption.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trip tests for the hybrid post-quantum crypto layer.
 * Runs on device/emulator (Robolectric not configured in this project).
 */
@RunWith(AndroidJUnit4::class)
class HybridPQCryptoTest {

    private lateinit var pq: HybridPQCrypto

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // fresh vault per test run
        ctx.getSharedPreferences("pq_vault", Context.MODE_PRIVATE).edit().clear().commit()
        pq = HybridPQCrypto(ctx)
        pq.ensureVaultKeys()
    }

    @Test
    fun vaultKeys_generateOnce_andAreStable() {
        val pub1 = pq.vaultMlkemPublicKey()
        val pubX1 = pq.vaultX25519PublicKey()
        pq.ensureVaultKeys() // idempotent
        assertArrayEquals(pub1, pq.vaultMlkemPublicKey())
        assertArrayEquals(pubX1, pq.vaultX25519PublicKey())
        assertTrue(pub1.size == 1184) // ML-KEM-768 public key: 1184 bytes
        assertTrue(pubX1.size == 32)  // X25519
    }

    @Test
    fun hybridRoundTrip_smallPayload() {
        val msg = "harvest now, decrypt NEVER".toByteArray()
        val blob = pq.hybridEncrypt(msg)
        // QVAULT magic check
        assertEquals('Q'.code.toByte(), blob[0])
        assertEquals('V'.code.toByte(), blob[1])
        // decrypt
        val out = pq.hybridDecrypt(blob)
        assertArrayEquals(msg, out)
    }

    @Test
    fun hybridRoundTrip_largePayload() {
        val rnd = java.security.SecureRandom()
        val data = ByteArray(5 * 1024 * 1024 + 17) // 5 MiB + odd size
        rnd.nextBytes(data)
        val blob = pq.hybridEncrypt(data)
        assertArrayEquals(data, pq.hybridDecrypt(blob))
    }

    @Test
    fun differentCiphertexts_forSamePlaintext() {
        val msg = "same plaintext".toByteArray()
        val a = pq.hybridEncrypt(msg)
        val b = pq.hybridEncrypt(msg)
        assertFalse(a.contentEquals(b)) // fresh file key + fresh KEM encapsulation each time
        assertArrayEquals(msg, pq.hybridDecrypt(a))
        assertArrayEquals(msg, pq.hybridDecrypt(b))
    }

    @Test
    fun legacyBlob_fallsBackToLegacyKey() {
        val em = EncryptionManager()
        val key = em.generateKey()
        val secret = "old-school aes only".toByteArray()
        val legacyBlob = em.encrypt(secret, key)
        // not a QVAULT blob → legacy path
        assertArrayEquals(secret, pq.hybridDecrypt(legacyBlob, key))
    }
}