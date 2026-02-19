# Changelog
All notable changes to this application will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this application adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Supported section titles:
- Added, Changed, Fixed, Removed

**Please be aware that this changelog primarily focuses on user-related modifications, emphasizing changes that can
directly impact users rather than highlighting other key architectural updates.**

## [Unreleased]

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
