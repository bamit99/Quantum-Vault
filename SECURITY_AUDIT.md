# Quantum Vault — Security Audit

**Scope:** commit `ddb7806` (v4.1d), files: `HybridPQCrypto.kt`, `PassphraseKDF.kt`, `VaultEscrow.kt`, `KeyStoreManager.kt`, `EncryptionManager.kt`, `BiometricManager.kt`, `SafStorageManager.kt`, `MainActivity.kt`, manifest, backup rules, gradle. Audit date 2026-09-03. Findings verified against source, not the README.

**Rating:** primitives/hybrid-KEM strong; container format sound; several hygiene findings below. No P0 open.

---

## P0 — none open

**~~P0-1: passphrase leg unreachable on fresh device~~** — FIXED in v4.1d (`ddb7806`).
`hybridDecryptInternal()` unsealed device keys before attempting the passphrase fallback, so "device OR passphrase" was actually "device keys required, then either." Device leg is now fully inside the try; passphrase leg standalone. *(Found by external review; verified and fixed with a regression test due.)*

---

## P1 findings

**P1-1: Sealed PQ private keys stored in `SharedPreferences`, not EncryptedSharedPreferences** (`HybridPQCrypto.kt:93`)
The sealed blob (`PREF_SEALED`) is Keystore-wrapped ciphertext in `getSharedPreferences(MODE_PRIVATE)` — defense is the Keystore wrap key, so exploitation requires root or backup extraction; but with `allowBackup=false` + hardware key non-extractable, moving to `EncryptedSharedPreferences` (already a dependency, used in `KeyStoreManager`) is cheap hardening. *Also a consistency nit: two different secure-prefs mechanisms in one app.*

**P1-2: No key-material zeroization** (`HybridPQCrypto.kt`, `PassphraseKDF.kt`, `VaultEscrow.kt`)
File keys, KEKs, shared secrets, and derived Argon2id keys are never `Arrays.fill(..., 0)` after use. Heap snapshots (root/malware/cold-boot) could recover them. Android reality: GC timing makes this best-effort, not a guarantee — still worth doing for the long-lived items (unsealed PQ privates are re-derived per call, which actually limits exposure). **Fix queued:** zeroize KEKs/fileKey/shared secrets on exit paths, best-effort.

**P1-3: Release build not configured** (`app/build.gradle.kts:24` `isMinifyEnabled = false`, no release signing)
Debug-signed APKs only, no R8, no release keystore. Not exploitable per se, but means the public repo's artifact is a debug build: `android:debuggable=true` by default in debug builds, no shrinker on BC (rules exist but unused). **Fix queued:** release build type with R8 + BC keep rules + your own keystore.

**P1-4: `data_extraction_rules.xml` / `backup_rules.xml` are template stubs**
`allowBackup=false` is set (good), but the rules files are commented-out samples — cloud-backup behavior falls through to platform defaults. On D2D migration (Android 12+), prefs could ride to the new device WITHOUT the hardware Keystore keys, producing sealed blobs the new device can't unseal. **Fix queued:** explicit `<exclude>` of the PQ prefs file in both XMLs.

D2D restore of prefs without Keystore = permanently unusable sealed blob → recovery works (passphrase path now works post-v4.1d), but the user experience is a locked vault that restore fixes — acceptable, but the explicit exclusion makes behavior deterministic.

---

## P2 findings

**P2-1: `Timber` logging in crypto classes** — messages are benign (no plaintext/keys — verified every `Timber.*` call: generation/import/restore events + fingerprint prefix only), but the **Timber plant is never initialized** (no `Application` class, no `Timber.plant()`), so crypto classes call a no-op logger. Dead dependency, zero risk today — but a future careless `Timber.d("key=$x")` would silently ship. Recommend plant-in-debug-only discipline or removal.

**P2-2: Legacy `EncryptionManager` + `KeyStoreManager.generateAndStoreKey` path unused by the vault flow**
`setUserAuthenticationRequired(true)` there is *good* practice, but that whole path (biometric-gated legacy AES) is dormant since the SAF pivot — same situation as the removed cloud managers. Remove or wire deliberately.

**P2-3: StrongBox unconditionally on API≥28** (`KeyStoreManager.kt:82` `setIsStrongBoxBacked(true)` in a catch-less path)
Devices without StrongBox throw `StrongBoxUnavailableException` at key-gen. The vault's real wrap key (`HybridPQCrypto.getOrCreateWrapKey`) does NOT use StrongBox — no current breakage. If P2-2's path is kept, fall back gracefully (`setIsStrongBoxBacked(false)` on exception).

**P2-4: `VaultEscrow` fingerprint logged (first 16 hex chars)** — prefix-only, low risk (128-bit fingerprints → 64-bit prefix), still: drop it.

**P2-5: Compose passphrase fields hold `String` not `Char[]`** — Compose reality; mitigated by Argon2id's memory-hardness (the derived key lives in heap regardless). Document as accepted limitation, standard for Compose apps.

**P2-6: `INTERNET`/`ACCESS_NETWORK_STATE`/media permissions unused by current code**
The OAuth/cloud managers were removed but manifest permissions remained. Reduce to zero-permission app (SAF needs none of these!) → strong privacy signal. **Fix queued.**

---

## Positives (verified in code)
- All randomness via `java.security.SecureRandom` — file keys, IVs, KEM seeds, salts, escrow salts (9 call sites checked)
- AES-GCM IVs: fresh 12-byte random per operation, no counter reuse, no static IVs
- `usesCleartextTraffic=false`, `allowBackup=false`, `exported=false` (MainActivity is the launcher, correctly exported=true as required)
- Argon2id t=3/64MiB, escrow AEAD-authenticated, wrong-passphrase = clean failure, no plaintext/key logging anywhere (grep-verified)
- SAF zero-permission architecture; provider apps handle TLS
- Hybrid KEM: ephemeral X25519 + ML-KEM-768, HKDF-SHA256 combine, dual-wrap v2 container

## Recommended next steps
1. Fix P1-2 (zeroization) + P1-4 (backup rules) + P2-1/2/4 (Timber discipline, dead code, fingerprint log) → v4.1e hygiene release
2. P1-3 release build + signed release APK
3. On-device test run (`RecoveryTests.kt` never executed on hardware)
4. Independent review of the *fixed* code — the external reviewer's offer stands
5. Softer PQ wording in README ("post-quantum designed, subject to independent review")

## Method note
Every finding above was verified by reading the shipped source (grep + targeted reads); none are inferred from the README. The one P0 was found by an external reviewer and confirmed by code inspection before fixing.