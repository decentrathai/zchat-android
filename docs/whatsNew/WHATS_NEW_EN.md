# Changelog
All notable changes to this application will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this application adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Supported section titles:
- Added, Changed, Fixed, Removed

**Please be aware that this changelog primarily focuses on user-related modifications, emphasizing changes that can
directly impact users rather than highlighting other key architectural updates.**

## [Unreleased]

## [2.11.0 (62)] - 2026-04-13

### Added:
- **End-to-end symmetric ratchet encryption** — messages now use a deterministic, forward-secret ratchet layer on top of Zcash memo transport. Restore-from-seed preserved: the ratchet root is re-derivable from the seed plus on-chain KEX/KEXACK transaction IDs.
- **Encrypted file sharing** — share images directly in chat. Files are AES-256-GCM encrypted with a per-file random key wrapped with the E2E shared secret, uploaded via NIP-96 / Blossom relays, and surfaced inline in the chat view. Includes fullscreen viewer and Blurhash low-res placeholders while downloads are in progress.
- **Quantum Shield** — optional 32-byte pre-shared key exchanged via QR code and mixed into the ratchet root. Adds a post-quantum hedge against a harvest-now-decrypt-later adversary.
- **Safety Number verification** — 32-hex fingerprint derived from SHA-256 of the sorted raw peer public keys. Shield icon in the chat header opens a dialog for out-of-band comparison with your contact.
- **Key-Changed banner** — magenta warning appears when a peer's public key changes during key exchange.
- **Security info dialog** — accessible from More → Security. Lists the protections ZCHAT provides and current known limitations.
- **Image upload progress indicator** — progress bar with stage labels (Preparing → Compressing → Encrypting & uploading → Finalizing) during image send.
- **Delete Message confirmation dialog** — prevents accidental message hiding with a clear explanation of what deletion does.

### Fixed:
- InputStream leak in image picker (`.use {}` for guaranteed close).
- Cancellation handling in image upload — scope cancellation no longer surfaces as a spurious 'Upload failed' toast.
- SendMessageState no longer gets stuck in 'Sending' after upload cancellation.
- Concurrent-upload guard rejects a second image tap while an upload is in progress.
- Bitmap memory bounded per decoded image via `inSampleSize` downsampling to prevent OOM in long image-heavy chats.
- Malformed `E2E1:` wire payloads now surface as 'Encrypted message (unable to decrypt)' instead of displaying encrypted bytes as message text.
- First-ever JVM unit test pipeline (`ui-lib:src/test/java`) with 103 passing tests covering the ratchet, wire format, file sharing, sampling, and Quantum Shield state machine.

## [2.8.8 (3)] - 2026-02-20

### Fixed:
- Fixed FileProvider crashes on Store and Testnet build variants.
- Fixed crash on Keystone hardware wallet accounts.
- Fixed race condition in notification service.
- Fixed message corruption with emoji and multi-byte characters.
- Fixed SharedPreferences data persistence on app termination.
- Fixed battery drain from WakeLock held too long after sync.
- Fixed notification service crashes from unhandled exceptions.

### Added:
- ProGuard rules for Tink crypto and Ktor HTTP libraries.
- Comprehensive 3-cycle code audit with all findings resolved.

## [2.8.7 (2)] - 2026-02-19

### Fixed:
- Fixed Send All math — recipient now receives ~96% of balance instead of ~46%.
- Fixed custom amount text field glitching in the amount dialog.
- Fixed notification sound not playing (channel immutability issue).
- Fixed sync notification appearing on lock screen.

### Changed:
- Replaced Medium Tip with Send All option in amount selection.
- After sending first message, navigate directly to the conversation.
- Show available balance on compose screen and amount dialog.
- Updated README and About screen to show ZChat info instead of Zashi.

## [2.8.6 (1)] - 2026-02-18

### Fixed:
- Fixed message deduplication for multi-chunk messages.
- Fixed pending change balance detection for insufficient funds errors.
- Fixed conversation ID thread-safety for concurrent sends.
- Fixed zero-amount display inconsistency.
- Fixed address cache collision protection for validated sources.

## [2.8.5 (1)] - 2026-02-17

### Added:
- Full notification system with custom sound and vibration.
- Background sync via WorkManager (15-minute interval).
- In-app notification banners when app is in foreground.
- Lock screen notification privacy (hides message content).
- Notification settings screen with sound, vibration, and privacy controls.
- Per-conversation mute support.
- Deep linking from notifications to specific conversations.

## [2.8.3 (1)] - 2026-02-15

### Added:
- ZMSG Protocol v4 with conversation IDs for reliable message threading.
- Message chunking for messages longer than 512 bytes.
- End-to-end encryption with authenticated key exchange (ECDH + HKDF + AES-256-GCM).
- Group messaging with per-member ECIES key distribution.
- Reactions, read receipts, and reply threading.
- Time-locked messages (timestamp, block-height, payment-gated, answer-gated).
- Payment requests in chat.
- Contact book with aliases.
- QR code scanning for adding contacts.
- Cyberpunk UI theme with custom fonts and neon effects.
- Dead Man's Switch remote wipe capability.

### Changed:
- Forked from Zashi Android wallet.
- Added chat-centric navigation alongside standard wallet features.
- Custom lightwalletd server configuration.
