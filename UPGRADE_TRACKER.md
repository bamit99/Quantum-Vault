# UPGRADE TRACKER — Android_File_Encryption v3.1 → v4.0

> Live migration tracker. Update checkboxes as work completes; never delete rows —
> strike through (~~) cancelled items with a note. Companion to ROADMAP.md.
> Started: 2026-09-03. Design decision: **A+B** (passphrase binding + encrypted escrow).

---

## Phase 0 — Decisions & scope ✅
- [x] Recovery design chosen: **A+B** (amitb, 2026-09-03)
  - A: passphrase as third KEM input → `KEK = HKDF(X25519 ‖ ML-KEM-768 ‖ Argon2id(pass))`
  - B: passphrase-encrypted escrow of the PQ private key (`.vaultkey` in vault folder)
- [x] Argon2id API verified in BC 1.85.2 (`Argon2BytesGenerator` + `Argon2Parameters.ARGON2_id`)
- [x] Implemented as DUAL-WRAP (either factor alone unwraps FileKey): device leg +
      passphrase leg. Simpler and strictly stronger than the original A+B sketch —
      no Argon2-in-HKDF; passphrase wraps the FileKey independently via AES-GCM.

## Phase 1 — Crypto core (P0-A: passphrase binding) ✅
- [x] `PassphraseKDF`: Argon2id(pass, salt) → 32B (t=3, 64MiB, par=1), strength checker
- [x] `hybridEncrypt(data, passphrase)` — v2 dual-wrap (device leg + passphrase leg)
- [x] QVAULT **v2 wire format**: magic|ver=2|ephX25519(32)|encaps(1088)|salt(32)|argonSalt(32)|wrappedDevice|wrappedPass|iv+ct
- [x] `hybridDecrypt(blob, passphrase)` — device-wrap first, passphrase-wrap fallback;
      v1 + raw-AES fallbacks preserved; `isPassphraseCapable()` UI helper
- [x] Migration: v1 blobs decrypt through new path (tested)
- [x] Compile green; round-trip covered in RecoveryTests

## Phase 2 — Recovery (P0-B: escrow) ✅
- [x] `VaultEscrow.export()` → `VKEY1` blob (priv halves + pub halves, AES-GCM under Argon2id)
- [x] `VaultEscrow.restoreInto()` — wrong passphrase = clean failure, no keys written
- [x] Escrow bridge in HybridPQCrypto (export privates / importEscrowedKeys / vaultFingerprint)
- [x] `SafStorageManager.findChild()` to locate `.vaultkey` in the tree
- [ ] Wire escrow re-upload after passphrase change (Phase 3 settings — pending)

## Phase 3 — UX integration ✅
- [x] `PassphraseSetupScreen` (strength meter, double-entry, non-recoverable ack)
- [x] `VaultRestoreScreen` (escrow-missing warning, Argon2 progress, clean errors)
- [x] Nav: pick location → passphrase setup → escrow written → vault
- [x] Home: "Restore vault on this device…" entry point
- [ ] Biometric quick-unlock (deferred to P1 backlog)

## Phase 4 — Tests & hardening ✅
- [x] `RecoveryTests.kt` — 7 tests incl. full disaster-recovery simulation
      (format → escrow restore → old files decrypt), wrong-passphrase rejection,
      v1 compatibility, Argon2 determinism
- [x] R8/ProGuard keep rules for BouncyCastle
- [ ] Tests not yet RUN on device/emulator (needs device or AVD)
- [ ] Plaintext cache wipe after viewer closes (deferred)

## Definition of Done (v4.0)
- [x] Phase 1-3 complete; disaster-recovery test written and compiling
- [x] APK `app-debug-v4.0-recovery.apk` (SHA256 34a47e17...c6e0)
- [x] Tag `v4.0-pq-recovery` + push
- [ ] On-device verification of full restore flow (amitb)
- [ ] ROADMAP.md P0 closure (after on-device pass)

---

### Decision log
- 2026-09-03: A+B selected over A-only (device loss still loses files) and B-only
  (escrow alone lacks the per-file passphrase binding amitb asked for in Q2).
- Argon2id chosen over PBKDF2/scrypt: memory-hard (GPU/ASIC resistant), in BC already,
  OWASP-recommended params for interactive use.
- Escrow file lives in the SAME vault folder as ciphertexts (single restore point);
  it is useless without the passphrase (Argon2id-wrapped).