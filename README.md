# ZChat — Private Messaging on Zcash

ZChat is a privacy-first messaging app built on the Zcash blockchain. Every message is a shielded transaction — censorship-resistant, end-to-end encrypted, and requiring no phone number, email, or server registration.

Built on top of [Zashi](https://github.com/Electric-Coin-Company/zashi-android) (the official Zcash Android wallet) using the [Zcash Android SDK](https://github.com/Electric-Coin-Company/zcash-android-wallet-sdk).

## Features

- **Private messaging** — Messages stored in Zcash shielded transaction memos (encrypted on-chain)
- **ZMSG Protocol v4** — Custom memo encoding with conversation IDs for reliable threading
- **Message chunking** — Long messages split across multiple outputs in a single atomic transaction via ZIP321
- **End-to-end encryption** — Optional E2E layer: ECDH (P-256) + HKDF + AES-256-GCM with authenticated key exchange
- **Group messaging** — AES-256-GCM group encryption with ECIES per-member key distribution
- **Reactions, read receipts, replies** — Rich messaging features encoded in memo fields
- **Time-locked messages** — Timestamp, block-height, payment-gated, and answer-gated messages
- **Payment requests** — In-chat ZEC payment requests
- **Contact book** — Local contact management with aliases
- **Notifications** — Background sync with custom sound, vibration, and privacy levels
- **Full Zcash wallet** — Send, receive, and manage ZEC (inherited from Zashi)
- **Keystone hardware wallet support**
- **No servers, no accounts** — Your Zcash address is your identity

## Download

Download the signed release APK directly. No email, no account, no waitlist.

**[github.com/decentrathai/zchat-android/releases/latest](https://github.com/decentrathai/zchat-android/releases/latest)**

Verify it before installing:

```
apksigner verify -v --print-certs ZChat.apk
```

The signing certificate SHA-256 is permanent and will not change:

```
F1:7A:F1:28:23:CA:20:8B:63:2E:29:81:38:B7:89:13:74:F6:65:17:C8:9D:BF:BE:12:FC:3A:C3:65:01:C8:06
```

You can also build from source — see [Setup Documentation](docs/Setup.md) below.

## Protocol Specification

ZChat uses the ZMSG Protocol for encoding messages in Zcash transaction memos. See the full spec: [ZMSG Protocol Specification](docs/ZMSG_PROTOCOL_SPEC.md)

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.1.10 |
| UI | Jetpack Compose + Material3 |
| Wallet SDK | Zcash Android SDK 2.4.3 |
| Encryption | Google Tink + custom ECDH/AES-256-GCM |
| DI | Koin 4.0.2 |
| Background sync | WorkManager + ForegroundService |
| Min Android | 8.1 (API 27) |

## Reporting an Issue

Please file issues on this repository: [GitHub Issues](https://github.com/decentrathai/zchat-android/issues)

For general Zcash questions:
- [Zcash Forum](https://forum.zcashcommunity.com/)
- [Zcash Discord Community](https://discord.io/zcash-community)

## Building from Source

See [Setup Documentation](docs/Setup.md) to compile ZChat from source.

## Architecture

ZChat adds a messaging layer on top of Zashi's wallet functionality. The core wallet engine (key derivation, transaction construction, syncing) is unchanged from the Zcash Android SDK. ZChat-specific code lives in:

```
ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/
├── model/          — ZMSG Protocol, data models, address cache
├── viewmodel/      — Chat logic, compose screen, groups
├── view/           — Compose UI screens
├── crypto/         — E2E encryption (ECDH, AES-256-GCM, ECIES)
├── usecase/        — ZIP321 multi-output transaction creation
├── datasource/     — Contact book, preferences, address cache
└── util/           — Utilities
```

## Acknowledgments

ZChat is built on the exceptional work of the [Electric Coin Company](https://electriccoin.co/) and the Zcash ecosystem. Core wallet functionality is provided by the [Zcash Android SDK](https://github.com/Electric-Coin-Company/zcash-android-wallet-sdk) and [librustzcash](https://github.com/zcash/librustzcash).

## License

This project is a fork of [Zashi Android](https://github.com/Electric-Coin-Company/zashi-android) and inherits its license terms. ZChat-specific additions (ZMSG Protocol, chat UI, encryption layer) are developed by Decentrathai.
