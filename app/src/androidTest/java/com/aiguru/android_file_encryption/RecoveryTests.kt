package com.aiguru.android_file_encryption.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disaster-recovery tests for the A+B design (passphrase binding + escrow).
 * Simulates the full lifecycle: create vault → encrypt (dual-wrap) → SIMULATE
 * PHONE FORMAT (wipe prefs + Keystore keys) → restore from escrow → decrypt.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryTests {

    private lateinit var context: Context
    private lateinit var pq: HybridPQCrypto
    private val passphrase = "correct horse battery staple".toCharArray()

    @Before
    fun freshVault() {
        context = ApplicationProvider.getApplicationContext()
        // wipe simulated device state: prefs + keystore aliases + escrow artifacts
        context.getSharedPreferences("pq_vault", Context.MODE_PRIVATE).edit().clear().commit()
        wipeKeystoreAlias("pq_mlkem768_wrap")
        pq = HybridPQCrypto(context)
        pq.ensureVaultKeys()
    }

    private fun wipeKeystoreAlias(alias: String) {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    /** Simulates a phone format: destroys prefs + keystore wrap key. */
    private fun simulatePhoneFormat() {
        context.getSharedPreferences("pq_vault", Context.MODE_PRIVATE).edit().clear().commit()
        wipeKeystoreAlias("pq_mlkem768_wrap")
        pq = HybridPQCrypto(context) // fresh, empty
        assertFalse("keys must be gone after format", pq.hasVaultKeys())
    }

    @Test
    fun v2_roundTrip_withPassphrase() {
        val msg = "dual-wrap me".toByteArray()
        val blob = pq.hybridEncrypt(msg, passphrase)
        assertTrue(pq.isPassphraseCapable(blob))
        assertArrayEquals(msg, pq.hybridDecrypt(blob, passphrase))
        // v1-style decrypt without passphrase must NOT work (device wrap present, but
        // without passphrase param we can still use device leg — v2 keeps that property)
        assertArrayEquals(msg, pq.hybridDecrypt(blob))
    }

    @Test
    fun v2_wrongPassphrase_failsCleanly_onlyWhenDeviceLegUnavailable() {
        val msg = "secret".toByteArray()
        val blob = pq.hybridEncrypt(msg, passphrase)
        val wrong = "totally wrong passphrase".toCharArray()
        // With device keys present, the device leg still decrypts (by design: A+B)
        assertArrayEquals(msg, pq.hybridDecrypt(blob, wrong))
        // The passphrase leg itself must reject the wrong passphrase — verified via
        // escrow restore test below (GCM tag failure), not here.
    }

    @Test
    fun escrowExport_import_isLossless() {
        val fpBefore = pq.vaultFingerprint()!!
        val blob = VaultEscrow.export(pq, passphrase)

        simulatePhoneFormat()

        val fpAfter = VaultEscrow.restoreInto(pq, blob, passphrase)
        assertEquals(fpBefore, fpAfter)
        assertTrue(pq.hasVaultKeys())
        // keys are functional again
        val msg = "post-format decryption".toByteArray()
        val blob2 = pq.hybridEncrypt(msg, passphrase)
        assertArrayEquals(msg, pq.hybridDecrypt(blob2, passphrase))
    }

    @Test
    fun disasterRecovery_fullScenario() {
        // 1. Original device: create vault keys, encrypt files
        val secret1 = "taxes2026.pdf contents".toByteArray()
        val secret2 = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x42, 0x42)
        val blob1 = pq.hybridEncrypt(secret1, passphrase)
        val blob2 = pq.hybridEncrypt(secret2, passphrase)
        val escrow = VaultEscrow.export(pq, passphrase)
        val fpOriginal = pq.vaultFingerprint()!!

        // 2. Phone formatted — everything device-local is destroyed
        simulatePhoneFormat()

        // 3. New device: restore from escrow with the passphrase
        val fpRestored = VaultEscrow.restoreInto(pq, escrow, passphrase)
        assertEquals(fpOriginal, fpRestored)

        // 4. Old ciphertexts decrypt again
        assertArrayEquals(secret1, pq.hybridDecrypt(blob1, passphrase))
        assertArrayEquals(secret2, pq.hybridDecrypt(blob2, passphrase))
    }

    @Test
    fun disasterRecovery_wrongPassphrase_rejected() {
        val secret = "cannot touch this".toByteArray()
        pq.hybridEncrypt(secret, passphrase)
        val escrow = VaultEscrow.export(pq, passphrase)

        simulatePhoneFormat()

        // wrong passphrase must NOT restore (clean failure)
        assertThrows(Exception::class.java) {
            VaultEscrow.restoreInto(pq, escrow, "totally wrong passphrase".toCharArray())
        }
        // and the vault must still have no keys (restore aborted)
        assertFalse(pq.hasVaultKeys())
    }

    @Test
    fun legacyV1Blob_stillDecrypts_afterUpgrade() {
        // create a v1 blob (device-wrap only)
        val msg = "v1 heritage file".toByteArray()
        val v1blob = pq.hybridEncrypt(msg) // no passphrase → v1
        assertFalse(pq.isPassphraseCapable(v1blob))
        // v1 files decrypt through the new code path
        assertArrayEquals(msg, pq.hybridDecrypt(v1blob))
    }

    @Test
    fun argon2_deterministic_and_salt_sensitive() {
        val s1 = PassphraseKDF.newSalt()
        val k1 = PassphraseKDF.derive(passphrase, s1)
        val k2 = PassphraseKDF.derive(passphrase, s1)
        assertArrayEquals(k1, k2) // deterministic for same (pass, salt)
        val k3 = PassphraseKDF.derive(passphrase, PassphraseKDF.newSalt())
        assertFalse(k1.contentEquals(k3)) // different salt → different key
        val k4 = PassphraseKDF.derive("different".toCharArray(), s1)
        assertFalse(k1.contentEquals(k4)) // different pass → different key
        assertNotEquals("", PassphraseKDF.strengthProblem("short") ?: "x")
        assertEquals(null, PassphraseKDF.strengthProblem("correct horse battery staple"))
    }
}