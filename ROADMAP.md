# ROADMAP — Android_File_Encryption

> Living document. Newest items at top of each section. Owners: amitb + Hermes.
> Last updated: 2026-09-02 (v3.1-pq-saf milestone).

---

## 🔴 P0 — Data-loss & Recovery (raised by amitb, 2026-09-02)

### Q1. Phone format orphans the vault — CRITICAL DESIGN FLAW, MUST FIX
**Scenario:** user encrypts files into their Drive vault; phone is later formatted/lost.
**Current behavior:** ciphertexts survive in Drive, but the ML-KEM-768 + X25519 private
keys live only on the device (SharedPreferences + hardware-bound Android Keystore
wrap key, both destroyed on reset). Reinstall → new keys → old `.qvault` files are
**permanently undecryptable**. No recovery path exists today.

**Required capability:** vault must be recoverable on a replacement device.

### Q2. Decryption must involve a user-memorized secret (passphrase)
**Requirement (amitb):** decryption must require the **user's own key** mixed in at
encryption time — not just device-resident material — so the vault is not hostage
to one phone.

**Terminology note:** the memorized value is a *passphrase* (user's head), and the
* salt* is a public random value stored with the vault to harden derivation.
Design uses **Argon2id(passphrase, salt)** — memory-hard KDF, salt stored in the
vault (not secret, fine), passphrase never stored.

### Proposed designs (decision pending — amitb picks)
| Option | Mechanism | Recovery | Trade-off |
|---|---|---|---|
| **A** | Passphrase as third KEM input: `KEK = HKDF(X25519 ‖ MLKEM-768 ‖ Argon2id(pass))` | Only with passphrase + device keys | Device loss still loses files |
| **B** | Passphrase-encrypted key escrow: vault PQ private key exported to the vault folder, wrapped under `Argon2id(pass)` | Full recovery on ANY device (install → passphrase → unwrap → restore) | Private key leaves hardware Keystore (wrapped); strength = passphrase + Argon2id |
| **A+B** | Both: escrow for disaster recovery, passphrase binding per vault | Full + user-key property | More UI + code; **recommended** |

**Implementation sketch (A+B):**
- First-run: user sets vault passphrase (strength meter; no recovery without it)
- `ensureVaultKeys()` additionally derives `escrowWrap = Argon2id(pass, vaultSalt)`
- Export `sealed_priv` blob → encrypt under `escrowWrap` → upload as
  `.vaultkey` alongside the `.qvault` files (SAF write to same folder)
- "Restore on new device" flow: pick existing vault folder → enter passphrase →
  decrypt `.vaultkey` → unseal PQ keys → verify by test-decrypting one file
- Wrong passphrase = clean failure (GCM auth), no partial decrypt
- Passphrase change = re-wrap escrow + optionally re-wrap? (design: re-wrap escrow
  only; per-file KEKs unchanged)
- Wrong-passphrase rate limiting via Argon2id cost (no server needed)

**Acceptance criteria:**
- [ ] Format phone → reinstall → pick same Drive folder → enter passphrase → all old files decrypt
- [ ] Wrong passphrase → clear error, no key material leaked
- [ ] Passphrase never persisted anywhere; salt + escrow blob in vault folder
- [ ] Migration path for existing v3.1 vaults (re-encrypt escrow on upgrade)

---

## 🟡 P1 — Vault UX (next sprint candidates)

- [ ] **Run the 5 instrumented PQ tests on a real device/emulator** (never executed yet)
- [ ] Delete file from vault (long-press → delete → confirm; deletes .qvault in tree)
- [ ] Folder support inside vault view (currently flat file list)
- [ ] Encrypt-in-place: pick a *folder* of files and batch-encrypt
- [ ] Progress indication for large files (currently blocks UI thread per file)
- [ ] Wipe plaintext cache copy after viewer closes (currently lingers until OS GC)
- [ ] Biometric gate before opening the vault (BiometricManager exists, unwired)
- [ ] Search/filter in vault browser; sort by name/date/size
- [ ] Show PQ/key info in Settings (algorithm, key fingerprints, escrow status)

## 🟢 P2 — Hardening & Release

- [ ] Release build: minify + resource shrink + signing config + Play-style AAB
- [ ] ProGuard/R8 keep rules for BouncyCastle PQC classes
- [ ] Replace debug `Timber` planting with no-op in release
- [ ] Decide fate of dormant OAuth managers (GoogleDriveManager/OneDrive/S3/Azure/
      AuthManager) + ViewModels (Home/Cloud/File) — delete or document as optional
- [ ] Update `requirements.md` to describe SAF+PQ architecture (it predates both)
- [ ] Settings screen is a placeholder — real settings (vault location mgmt,
      passphrase change, key export/rotation)
- [ ] Key rotation flow (re-encrypt escrow under new passphrase / new PQ keypair)
- [ ] `pendingFileCallback`/`pendingFolderCallback` are process-death-fragile —
      persist picker intents properly
- [ ] Consider v2 of QVAULT format with explicit file metadata header (orig name,
      timestamp, app version) before format gets widely deployed
- [ ] Backup rules: `allowBackup=false` is set — document that escrow-in-vault is
      THE backup, keep it that way

## 📝 Open questions for amitb (ask before building)
1. Recovery design: A, B, or A+B? (recommendation: A+B)
2. Passphrase UX: required at vault creation? changeable? hint allowed?
3. Should escrow `.vaultkey` live in the same Drive folder as the ciphertexts?
4. Any interest in multi-vault (multiple locations, separate passphrases)?