# Quantum Vault 🔐

**Your files, sealed before they ever leave your hands.** Quantum Vault encrypts every file on-device with NIST-standardized post-quantum cryptography — ML-KEM-768 wrapped around every byte — and tucks it into any folder you choose: Google Drive, OneDrive, **OneDrive for Business/SharePoint (via Outlook)**, Dropbox, or plain local storage. No OAuth. No cloud SDK. No account. No servers. Just a vault that travels with your files.

> **Platform:** [Android](https://github.com/bamit99/Quantum-Vault) ✅ · **[iOS](https://github.com/bamit99/Quantum-Vault-iOS)** ✅ (iOS 26+; byte-compatible QVAULT containers — vaults move between platforms)

> © 2026 [Amit Bhatnagar](https://www.linkedin.com/in/amitxbhatnagar/) — free for personal use; commercial use requires prior permission. See [LICENSE.md](LICENSE.md).

---

## Why "Quantum"?

Every encrypted file you store in the cloud today can be **harvested now and decrypted later** ("Harvest-now, decrypt-later"): a quantum computer running Shor's algorithm will break today's RSA/ECDH key exchange retroactively. Quantum Vault defends against that *today* by wrapping every file key with **ML-KEM-768 (Kyber, FIPS 203)** — NIST's standardized post-quantum KEM — combined with X25519 in a hybrid construction.

## Crypto architecture

| Layer | Primitive | Role |
|---|---|---|
| Key encapsulation | **ML-KEM-768** (FIPS 203) | Post-quantum wrap of the file key |
| Key encapsulation | **X25519** | Classical wrap of the file key (hybrid belt-and-braces) |
| Key derivation | **HKDF-SHA256** | Combines both shared secrets → KEK |
| KDF (passphrase) | **Argon2id** (t=3, 64 MiB) | Passphrase → wrapping key; GPU-hostile |
| Payload | **AES-256-GCM** (128-bit tag) | File content encryption |
| Device binding | **Android Keystore** (hardware) | PQ private keys sealed on-device |
| Escrow | **`.vaultkey`** in the vault folder | Passphrase-wrapped PQ keys — survives phone format/loss |

**QVAULT container format** — every encrypted file is a `QVAULT`-magic container (v1: device-wrap; v2: *dual-wrap* — the random file key is wrapped once under the device PQ keys and once under `Argon2id(passphrase)`. **Either factor alone unwraps.**)

### Recovery contract (A + B)
- **Forgot passphrase, phone works** → device Keystore leg unwraps files. Fine.
- **Formatted / lost phone, remembers passphrase** → reinstall, pick the same folder, "Restore vault on this device", enter passphrase → `.vaultkey` re-seals keys to the new device → every old file decrypts.
- No single point of failure; the passphrase is never stored anywhere.

### Access control
- Vault opens **only after a per-session passphrase check** — verified by real Argon2id + AES-GCM auth against the `.vaultkey` (no stored flag to spoof).
- Back on the main screen asks before exiting; elsewhere, back returns home.

## Is it *truly* quantum-resistant? (honest answer)

**Post-quantum by design — resistant against currently known attacks, with two scope caveats.** This is an application-layer construction (ML-KEM + X25519 + AES-GCM); absolute claims would require independent review. What we can say precisely:

1. ✅ **What's protected:** every byte we encrypt uses X25519 ‖ ML-KEM-768 hybrid KEM → AES-256-GCM. Breaking it requires defeating *both* classical ECC *and* Kyber under currently known algorithms. AES-256 and SHA-256 are quantum-fine (Grover only halves their effective strength). No RSA/ECDSA anywhere in the crypto path.
2. ⚠️ **Transport:** upload/download rides on the SAF provider's HTTPS (the Drive/OneDrive app's TLS). That TLS is classical-unless-Google-enables-PQC — but TLS only protects data *in flight*; your data at rest is our ciphertext either way, and that's what quantum-vaulting is for.
3. ⚠️ **Metadata:** cloud providers still see *that* files exist, their sizes, timestamps — not contents. (We never see them either: zero permissions, no accounts, no servers.)

## Where does the vault live? (providers & corporate OneDrive)

Storage is **pure SAF (Storage Access Framework)** — zero permissions, zero OAuth:

- **Quick-choose sheet** detects the cloud apps actually installed on the device (Drive, OneDrive, Dropbox) *plus* the **corporate Microsoft apps** — Outlook, SharePoint, Microsoft 365 hub — which host **OneDrive for Business / SharePoint** files. Your users just sign into their Work/School account; we never see their credentials.
- **Central creation step:** when you pick a location for a **new** vault, Quantum Vault auto-creates a dedicated **`QuantumVault/`** folder there via `DocumentsContract.createDocument` (no more "which folder do I put it in?"). If the picked folder already hosts a vault, it's adopted instead (restore path, no duplicates).
- **Provider jump:** the intent pre-seeds `EXTRA_INITIAL_URI` at the provider's **live** root (authority + root document ID resolved from the provider's own ROOTS API). Stock Android/Pixel/Samsung-style pickers drop you straight into Drive.
  - ⚠️ **OEM caveat:** some OEM ROMs ignore that extra, so you land in the picker's top level — reach the cloud via its **menu → your provider** (it *is* listed there). OEM file managers never take over the intent (the system SAF picker is pinned by component).
  - ⚠️ **OneDrive note:** OneDrive files only appear in the picker when the OneDrive app is signed in; some OEM builds won't expose a SAF root for it at all. Enterprise tenants using **Intune app-protection policies** must allow third-party SAF access (or the app must be deployed as a managed app) before Outlook/SharePoint files surface.

## Build

```bash
git clone https://github.com/bamit99/Quantum-Vault.git
# Android Studio Ladybug+ / JDK 21 (Android Studio JBR works)
./gradlew assembleDebug
```

Requirements: Android 7.0+ (`minSdk 24`), BouncyCastle `bcprov-jdk18on:1.85.2` (already in `app/build.gradle.kts`).

## Status & roadmap

See [ROADMAP.md](ROADMAP.md) and [PROJECT_STATUS.md](PROJECT_STATUS.md).

- **Shipped (v1.0):** SAF vault (any provider, remembered location), hybrid PQ encryption (QVAULT v2 dual-wrap), per-session unlock gate, `.vaultkey` escrow + restore-on-new-device, vault lifecycle (vault-exists guard, two-tier delete: Unlink / typed-DESTROY), **zero-permission manifest**, key-material zeroization, backup-transfer excludes, location intelligence (cloud-vs-local detection + recommendations), **auto-create `QuantumVault/` folder on vault creation**, provider-aware quick-choose with **corporate Microsoft** (Outlook/SharePoint/365 — OneDrive for Business ready), live provider-root jump (best-effort; OnePlus ignores it), brand icon, About + license, published security audit.
- **Next:** signed release build (R8), on-device test suite run, offline-handling UX (network-vs-auth error distinction, escrow caching), biometric quick-unlock.
- Security status: [SECURITY_AUDIT.md](SECURITY_AUDIT.md) — P0 fixed, P1/P2 findings tracked in-repo.

## Security disclosure

Found something? Private disclosure please — open a GitHub security advisory or contact [Amit Bhatnagar](https://www.linkedin.com/in/amitxbhatnagar/). Do not open public issues for vulnerabilities.

---

*No warranty. You are the only custodian of your passphrase — lose both factors (device keys AND passphrase) and the data is gone by design. That's not a bug; that's the point.*