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
- [ ] amitb signs off on passphrase UX (creation flow, change flow, no-hint policy)

## Phase 1 — Crypto core (P0-A: passphrase binding)
- [ ] `PassphraseKDF` helper: Argon2id(pass, salt) → 32B key
      (params: t=3, m=64 MiB, parallelism=1 — phone-tuned; salt 32B random, stored in vault)
- [ ] `HybridPQCrypto.hybridEncrypt(data, passphrase)` — derive `passKEK = Argon2id(...)`,
      wrap FileKey twice: device-KEK leg (existing) **and** passKEK leg
- [ ] QVAULT **v2 wire format**:
      `magic|ver=2|ephX25519|mlkemEncaps|salt32|argonSalt32|wrappedKey_device|wrappedKey_pass|iv+ct`
      (dual-wrap: either factor alone can unwrap the FileKey)
- [ ] `hybridDecrypt(blob, passphrase)` — try pass-wrap first (cheap GCM auth check),
      then device-key wrap; legacy v1 + raw-AES fallbacks preserved
- [ ] Migration: v1 blobs (device-wrap only) still decrypt; re-encrypt to v2 on next write
- [ ] Unit round-trip tests: v2 encrypt/decrypt, wrong-passphrase rejection, v1 fallback

## Phase 2 — Recovery (P0-B: escrow)
- [ ] On vault creation: generate `vaultSalt`, require passphrase (strength check ≥ 12 chars or 4-word passphrase)
- [ ] Export flow: seal PQ private halves → encrypt with `Argon2id(pass, escrowSalt)`
      → write `.vaultkey` (versioned blob) into the SAF vault folder
- [ ] Restore flow (new device / post-format): pick existing folder → find `.vaultkey`
      → enter passphrase → unwrap PQ keys → verify by test-decrypting one `.qvault` file
- [ ] Passphrase change: re-derive escrow wrap → rewrite `.vaultkey` (per-file wraps unchanged)
- [ ] Escrow re-upload after every key operation; escrow-freshness indicator in UI
- [ ] Acceptance: **format phone → reinstall → pick same folder → passphrase → files decrypt**

## Phase 3 — UX integration
- [ ] First-run: "Create vault passphrase" screen (strength meter, confirm field,
      explicit "cannot be recovered — memorize it" warning)
- [ ] Unlock prompt on vault open (passphrase; biometric quick-unlock after first unlock)
- [ ] "Restore existing vault" entry point beside "Choose location"
- [ ] Wrong-passphrase error UX (attempt counter, progressive Argon2 cost on retries)
- [ ] Settings: change passphrase, view key fingerprints, escrow status + re-upload button

## Phase 4 — Tests & hardening
- [ ] Instrumented tests: full disaster-recovery simulation (keys wiped → escrow restore)
- [ ] Wrong-passphrase brute-force cost check (Argon2 timing on low-end device)
- [ ] Run all 5 existing PQ tests on device (P1 carry-over)
- [ ] Plaintext cache wipe after viewer closes
- [ ] R8 keep-rules for BC PQC + Argon2 classes; release build with signing config

## Definition of Done (v4.0)
- [ ] All Phase 1-3 boxes checked; disaster-recovery test passes on real device
- [ ] New APK tagged `v4.0-pq-recovery`, pushed with release notes
- [ ] ROADMAP.md P0 items closed; PROJECT_STATUS.md updated to v4.0 architecture
- [ ] requirements.md rewritten to SAF+PQ+passphrase architecture

---

### Decision log
- 2026-09-03: A+B selected over A-only (device loss still loses files) and B-only
  (escrow alone lacks the per-file passphrase binding amitb asked for in Q2).
- Argon2id chosen over PBKDF2/scrypt: memory-hard (GPU/ASIC resistant), in BC already,
  OWASP-recommended params for interactive use.
- Escrow file lives in the SAME vault folder as ciphertexts (single restore point);
  it is useless without the passphrase (Argon2id-wrapped).