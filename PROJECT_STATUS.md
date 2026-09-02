# PROJECT STATUS — Android_File_Encryption (dev handoff)

> Last updated: 2026-09-02 by Hermes (session with amitb on RAZOR, Windows 11).
> Read this file first — it is the single source of truth for where the project stands.

## Current milestone: v3.1-SAF-UX — BUILDING GREEN ✅

**APKs (all debug-signed, installable):**
- `D:\Projects\Android_File_Encryption\app-debug-v3.1-saf-ux.apk` ← latest (SHA256 `de94df29...add733`)
- `app-debug-v3-saf.apk` (SHA256 `7412d0d2...57001`) — pre-UX-fix build
- `app-debug-v2-pq.apk` (SHA256 `98b74978...415b2`) — PQ layer only, pre-SAF

## Architecture (v3.x) — SAF replaced ALL cloud OAuth

User's architectural decision (2026-09-02): **no per-provider OAuth integrations**.
The Android system file picker (SAF) already exposes Google Drive / OneDrive / SD /
local — already authenticated by the user's own app sign-ins. The app:

1. `SafStorageManager` — picks a vault folder via `ACTION_OPEN_DOCUMENT_TREE`,
   persists permission (`takePersistableUriPermission`, survives reboots),
   remembers location name; save/read/list/delete documents in that tree.
2. `DocumentHelper` — create/overwrite/find documents via `DocumentsContract`
   (no DocumentFile dependency).
3. Flow: Home → "Choose location" → system picker → **auto-navigate into vault** →
   `+` → pick any file → `HybridPQCrypto.hybridEncrypt()` → saved as `<name>.qvault`
   in the remembered location → tap file → `hybridDecrypt()` → opened via FileProvider.
4. Home screen reflects real SAF state (setup card vs "Main Vault — Location: X").
5. Legacy OAuth cloud managers (GoogleDriveManager, OneDriveManager, S3, Azure,
   AuthManager) are DORMANT in repo — kept for optional power-user path. Do not wire
   them back in without user request.

## Post-quantum crypto layer (v2 milestone, kept intact)

- `security/HybridPQCrypto.kt` — hybrid KEM: **X25519 ECDH ‖ ML-KEM-768 (FIPS 203)**
  → HKDF-SHA256 → AES-256-GCM key wrap. "QVAULT" v1 wire format:
  `magic(6B) | ver(1B) | ephX25519Pub(32B) | MLKEM768encaps(1088B) | salt(32B) | wrappedFileKey(iv+ct+tag) | iv+ct+tag`
- PQ private key sealed inside Android Keystore (hardware AES-GCM wrap) — Keystore
  has no PQ primitives yet (documented trade-off).
- Legacy AES-only blobs auto-detected (magic check) and decrypted via old path.
- `GoogleDriveManager.uploadFile(pqCrypto=)` / `downloadAndDecrypt()` accept optional
  PQ — currently unused since SAF path handles crypto directly.
- Instrumented tests: `app/src/androidTest/.../HybridPQCryptoTest.kt` (5 tests —
  key stability, small/5MiB round-trips, ciphertext uniqueness, legacy fallback).
  NOT yet run on device/emulator.

## Build environment (RAZOR)

```
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # JDK 21
cd /d/Projects/Android_File_Encryption && ./gradlew assembleDebug
```
- `local.properties` → `sdk.dir=C\:\\Users\\amitb\\AppData\\Local\\Android\\Sdk`
  (double-backslash escaping; gitignored)
- SDK: build-tools 35/36/37, platform android-36.1; Gradle 8.13 via wrapper
- google-services plugin REMOVED from root build.gradle.kts (Firebase unused) —
  re-add + google-services.json only if Firebase ever needed.

## BC (BouncyCastle 1.85.2) API gotchas (learned the hard way — verified via javap)

- Accessors are `getEncoded()` — NOT `.encode`/`.publicKey`/`.privateKey`
- `KeyGenerationParameters` lives in `org.bouncycastle.crypto.` (base pkg, not `.params`)
- Math class `org.bouncycastle.math.ec.rfc7748.X25519` → `SCALAR_SIZE`
  (params classes have `KEY_SIZE`/`SECRET_SIZE`); agreement via
  `xPrivKey.generateSecret(xPubKey, out, 0)` (params-level API)
- KEM classes: `org.bouncycastle.crypto.kems.MLKEMGenerator/MLKEMExtractor`
- `HKDFParameters(ikm, salt, info)` — info must be ByteArray
- Kotlin: no `ByteArray.startsWith` (write manual magic check); `ByteArray + ByteArray`
  operator is ambiguous → use explicit `.plus()`; activity fields are NOT visible inside
  top-level `@Composable` — pass lambdas from `setContent`

## Known gaps / candidate TODOs (user has "suggestions for tomorrow" — ask first)

- [ ] Instrumented PQ tests never run on a device/emulator
- [ ] No delete / rename / folder creation in vault browser
- [ ] `ShareDecrypted` writes plaintext to cacheDir (cleaned by OS, but consider
      in-memory view or explicit wipe after open)
- [ ] No biometric gate before opening the vault (BiometricManager exists, unwired)
- [ ] Release build (minify, signing config) — currently debug only
- [ ] Dormant OAuth managers + HomeViewModel/CloudViewModel/FileViewModel are dead
      code — decide: delete or keep
- [ ] requirements.md still describes the old OAuth architecture
- [ ] Settings screen is placeholder
- [ ] `pendingFileCallback`/`pendingFolderCallback` are process-death-fragile
      (fine for now)

## Repo logistics

- Private GitHub repo `bamit99/Android_File_Encryption` (Kotlin, pkg
  `com.aiguru.android_file_encryption`); clone at `D:\Projects\Android_File_Encryption`
- `gh` CLI authed as bamit99 on this machine
- Commit history before this milestone: 2 commits (Nov 2025: setup + vault UI)
- v3.1 milestone: uncommitted working tree changes (commit pending — user asked to
  "save the status", do commit+push as part of handoff)

## Contact/context

- Owner: amitb (bamit99 on GitHub). Prefers concise answers, versioned files,
  never overwrite directly — make .bak or versioned copies.
- Session context lives in Hermes session DB (search: "Android_File_Encryption").