# Quantum Vault 🔐

**Post-quantum encrypted file vault for Android.** Files are encrypted on-device with hybrid post-quantum cryptography before they ever leave your phone, stored in *any* folder you choose (Google Drive, OneDrive, local storage) via the Android Storage Access Framework — no OAuth, no cloud SDK, no account wiring.

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

## Build

```bash
git clone https://github.com/bamit99/Quantum-Vault.git
# Android StudioLadybug+ / JDK 21 (Android Studio JBR works)
./gradlew assembleDebug
```

Requirements: Android 8.0+ (SAF), BouncyCastle `bcprov-jdk18on:1.85.2` (already in `app/build.gradle.kts`).

## Status & roadmap

See [ROADMAP.md](ROADMAP.md) and [PROJECT_STATUS.md](PROJECT_STATUS.md).

- **Shipped (v4.2):** SAF vault (any provider, remembered location), hybrid PQ encryption (QVAULT v2 dual-wrap), per-session unlock gate, `.vaultkey` escrow + restore-on-new-device, vault lifecycle (vault-exists guard, two-tier delete: Unlink / typed-DESTROY), **zero-permission manifest**, key-material zeroization, backup-transfer excludes, location intelligence (cloud-vs-local detection + recommendations), provider-aware quick-choose, brand icon, About + license, published security audit.
- **Next:** signed release build (R8), on-device test suite run, offline-handling UX (network-vs-auth error distinction, escrow caching), biometric quick-unlock.
- Security status: [SECURITY_AUDIT.md](SECURITY_AUDIT.md) — P0 fixed (v4.1d), P1/P2 findings tracked in-repo.

## Security disclosure

Found something? Private disclosure please — open a GitHub security advisory or contact [Amit Bhatnagar](https://www.linkedin.com/in/amitxbhatnagar/). Do not open public issues for vulnerabilities.

---

*No warranty. You are the only custodian of your passphrase — lose both factors (device keys AND passphrase) and the data is gone by design. That's not a bug; that's the point.*