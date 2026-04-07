# ZCHAT Development Update — Week of March 2, 2026

Hey everyone, here's our latest progress update on ZCHAT — the encrypted messenger built on Zcash shielded transactions where every message is a real on-chain transaction. No servers, no metadata.

---

## The Big One: New Visual Identity

ZCHAT finally has its own face. Until now the app looked like a wallet with a chat feature bolted on. This update introduces a complete visual redesign with a dark cypherpunk aesthetic that makes it clear — this is a messenger first.

**What changed:**

The entire UI has been rebuilt around a new design system. Dark navy backgrounds, cyan accents for interactive elements, custom typography (Rajdhani for headings, JetBrains Mono for addresses). Every screen from the chat list to the conversation view to the QR address screen has been redesigned.

**Chat list** now feels like a proper messenger — clean rows with avatars, message previews, timestamps, and a bottom navigation bar preparing for Wallet and Settings tabs down the road.

**Conversations** got the biggest overhaul. Message bubbles have proper styling with subtle borders, date separators between days, a "SHIELDED" banner reminding you of the privacy guarantee, and a cleaner input bar where the send button only appears when you're actually typing.

**My Address / QR screen** now has a clear shielded vs transparent toggle, copy + share buttons, and info cards explaining when to use which address type.

Screenshots:

*(attach: zchat_chatlist.png, zchat_chatdetail.png, zchat_qr.png)*

---

## In-App Updates

Users no longer need to manually check for new versions. The app now checks automatically on launch and shows a prompt when a new version is available. There's also a manual "Check for Updates" option in the menu. Dismissed prompts won't nag you again until a new version drops.

---

## Security Audit

We ran a full 3-cycle security review and fixed every finding:

- Hardened encryption pipeline (proper key derivation, chunk integrity validation)
- Fixed message corruption that could occur with emoji and special characters
- Tightened file access permissions — the app can no longer read outside its own secure directory
- Disabled unencrypted cloud backups
- Fixed battery drain from sync holding a wake lock too long
- Resolved a race condition in the notification service
- Removed all personally identifiable information from logs

29 issues found and resolved total.

---

## Bug Fixes

- **Send All** now correctly sends the intended amount (was sending roughly half due to a math error)
- **Notifications** sound and vibration work reliably on Android 14+
- **Messages** display in the correct order and no longer split into duplicate conversations when contacts use diversified addresses
- **Multi-part messages** (longer than 512 bytes) no longer occasionally lose chunks
- All remaining "Zashi" wallet branding removed from the UI

---

## Current Status

**Version: 2.10.2** — available for download at [zsend.xyz](https://zsend.xyz)

We're still distributing debug builds for testing. Production builds with minification are on the roadmap and will significantly reduce the download size.

---

## What's Next

- Wallet tab with balance and transaction history
- Payment bubbles with structured amount display and transaction links
- First-time user onboarding for the message cost model
- Group chat polish
- Production-ready build pipeline

---

Thanks to the Zcash community for the continued support. If you want to try ZCHAT, grab the APK from [zsend.xyz](https://zsend.xyz) and send a test message — it costs less than a thousandth of a cent.

Feedback welcome here or on our [GitHub](https://github.com/AntR22/zchat-android).
