# Decentralized File Sharing for ZCHAT — Design Spec (Fixed)

**Date:** 2026-04-07
**Status:** Final
**Estimated effort:** 60 hours (3 phases over 3-4 weeks)

---

## Problem

ZCHAT messages are 512-byte Zcash memos — text only. Users can't share images or documents. This is the #1 missing feature vs Signal/Telegram.

## Solution

Files E2E encrypted on device, uploaded to decentralized NOSTR file servers (NIP-96 primary, Blossom fallback), references sent via 512-byte Zcash memos using new ZFILE protocol type.

## Key Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| NOSTR SDK | **None** — use Android's built-in secp256k1 + manual NIP-98 event construction | Avoids 15MB APK bloat from rust-nostr. Android includes Bouncy Castle with secp256k1. |
| File storage | NIP-96 primary + Blossom fallback | NIP-96 is more mature; Blossom is simpler. Both use NIP-98 auth. |
| Encryption | AES-256-GCM with random per-file key, key wrapped with existing E2E shared secret | Reuses existing crypto infrastructure (ECDH + HKDF). |
| Blossom client | Custom HTTP client (50 lines) | nostr-blossom crate is ALPHA — don't depend on it. |
| Thumbnails | 8-byte blurhash in ZFILE memo | Recipients see a colored placeholder immediately, not blank. |
| KEX requirement | **Required** before file send | E2E shared secret needed for AES key wrapping. Show "Set up encryption first" if not ready. |
| File deletion | **Not supported in v1** | NIP-96/Blossom servers don't guarantee deletion. Documented as known limitation. |

---

## Architecture

```
Sender                                                           Recipient
  │                                                                  │
  ├─ Select file (camera/gallery/file picker)                        │
  ├─ Compress if image (2048px max, JPEG 80%)                        │
  ├─ Generate blurhash (8 bytes)                                     │
  ├─ Generate random AES-256-GCM key (32 bytes)                      │
  ├─ Encrypt file: AES-256-GCM(key, plaintext) → ciphertext          │
  ├─ Upload ciphertext to NIP-96 server (NIP-98 auth)                │
  │  └─ Fallback: upload to Blossom server                           │
  ├─ Get URL + compute SHA-256 of ciphertext                         │
  ├─ Wrap AES key: encrypt with E2E shared secret                    │
  ├─ Send Zcash memo: ZFILE|hash|type|size|url|wrappedKey|blurhash   │
  │                                                                  │
  │  ~~~~~~~~~ Zcash blockchain (~75 seconds) ~~~~~~~~~              │
  │                                                                  │
  │                  ├─ Parse ZFILE from memo                        │
  │                  ├─ Show blurhash placeholder immediately        │
  │                  ├─ Unwrap AES key with E2E shared secret        │
  │                  ├─ Download ciphertext from URL                  │
  │                  ├─ Verify SHA-256 hash                          │
  │                  ├─ Decrypt: AES-256-GCM(key, ciphertext)        │
  │                  └─ Display image inline / show doc download      │
```

## Components (6 units)

| # | Component | Files | Effort |
|---|-----------|-------|--------|
| 1 | **NOSTRIdentity** | `nostr/NOSTRIdentity.kt` | 4h |
| 2 | **FileUploadClient** | `nostr/NIP96Client.kt`, `nostr/BlossomClient.kt` | 12h |
| 3 | **FileEncryptionService** | `nostr/FileEncryption.kt` | 4h |
| 4 | **ZFILEProtocol** | Modify `ZMSGConstants.kt`, `MemoParser.kt` | 4h |
| 5 | **FileAttachmentUI** | `chat/view/FileAttachmentView.kt` | 16h |
| 6 | **InlineMediaDisplay** | Modify `ChatDetailView.kt` | 12h |
| | **Integration + testing** | | 8h |
| | **Total** | | **60h** |

### Component 1: NOSTRIdentity (no external dependencies)

```kotlin
// Key derivation from existing BIP39 seed
// BIP32 path: m/44'/1237'/0'/0/0 (NOSTR standard)
// Uses Android's built-in java.security with secp256k1 (Bouncy Castle)

class NOSTRIdentity(seed: ByteArray) {
    val privateKey: ByteArray  // 32 bytes, secp256k1
    val publicKey: ByteArray   // 32 bytes, x-only (Schnorr)
    val npub: String           // Bech32 encoded

    fun signNIP98Event(url: String, method: String): String  // base64 event
}
```

### Component 2: FileUploadClient

```kotlin
interface FileUploadClient {
    suspend fun upload(data: ByteArray, mimeType: String): UploadResult
}

data class UploadResult(val url: String, val sha256: String)

// NIP96Client: POST multipart with NIP-98 auth header
// BlossomClient: PUT with kind-24242 auth header
// FileUploadManager: tries NIP-96 first, falls back to Blossom
```

NIP-96 servers: `nostr.build`, `void.cat`
Blossom servers: `blossom.band`, `blossom.nostr.build`

### Component 3: FileEncryptionService

```kotlin
object FileEncryption {
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray>  // (ciphertext, aesKey)
    fun decrypt(ciphertext: ByteArray, aesKey: ByteArray): ByteArray
    fun wrapKey(aesKey: ByteArray, e2eSharedSecret: ByteArray): ByteArray
    fun unwrapKey(wrappedKey: ByteArray, e2eSharedSecret: ByteArray): ByteArray
}
```

Uses existing `javax.crypto.Cipher` with AES/GCM/NoPadding. Random 12-byte IV prepended to ciphertext.

### Component 4: ZFILE Protocol

```
ZMSG|4|DM|<convId>|ZFILE|<hash16>|<type1>|<size>|<url>|<wrappedKey44>|<blur8>
```

Byte budget (512 bytes total):
- ZMSG header: ~40 bytes
- ZFILE prefix: 6 bytes
- Hash (16 hex): 16 bytes
- Type (1 char: j/p/g/w/d/z/t): 1 byte
- Size (bytes): ~7 bytes
- URL (shortened): ~60 bytes
- Wrapped key (base64): ~44 bytes
- Blurhash: ~8 bytes
- Separators: ~10 bytes
- **Total: ~192 bytes** — fits with room to spare

Type codes: j=jpeg, p=png, g=gif, w=webp, d=pdf, z=zip, t=txt

### Component 5: FileAttachmentUI

Chat compose area gets a "+" button (already exists as placeholder):
- Tap → bottom sheet: Camera, Gallery, File
- Camera: capture → compress → encrypt → upload → send ZFILE
- Gallery: pick image → compress → encrypt → upload → send ZFILE
- File: pick document → encrypt → upload → send ZFILE
- Progress indicator during upload
- Cancel button during upload

### Component 6: InlineMediaDisplay

In chat bubbles:
- **Images:** Show blurhash placeholder → download → fade in thumbnail → tap for fullscreen
- **Documents:** Show icon + filename + size + "Download" button
- **Download progress:** Circular indicator on the bubble

---

## File Limits

| Type | Max Raw | After Compress | Display |
|------|---------|---------------|---------|
| JPEG/PNG/WebP | 20MB | ~5MB (2048px, 80%) | Inline thumbnail |
| GIF | 5MB | No compress | Inline (auto-play) |
| PDF | 20MB | None | Download card |
| ZIP/TXT | 20MB | None | Download card |

## Security Model

1. **Confidentiality:** File encrypted with AES-256-GCM before leaving device. Server sees only ciphertext.
2. **Integrity:** SHA-256 hash in memo. Recipient verifies after download.
3. **Authentication:** NIP-98 auth prevents unauthorized uploads. E2E shared secret ensures only intended recipient can decrypt.
4. **Forward secrecy:** Random AES key per file. Compromising one key doesn't reveal other files.
5. **Limitation:** Files persist on NIP-96/Blossom servers indefinitely. Cannot be deleted remotely.

## Prerequisites

- E2E key exchange (KEX) must be complete with recipient
- Wallet must be synced (to send Zcash memo)
- Internet connection (for file upload)

## Implementation Phases

### Phase 1: Foundation — 20h
- NOSTRIdentity: key derivation, NIP-98 signing (TDD)
- FileEncryption: AES-256-GCM encrypt/decrypt/wrap/unwrap (TDD)
- NIP96Client + BlossomClient: upload with auth (TDD with mock server)
- ZFILE protocol: parse/create memo strings (TDD)

### Phase 2: UI + Integration — 24h
- FileAttachmentUI: attachment button, picker, progress
- InlineMediaDisplay: thumbnails, download, fullscreen
- End-to-end flow: pick → compress → encrypt → upload → memo → receive → display

### Phase 3: Polish + Testing — 16h
- Error handling: upload failure retry, offline queue
- Edge cases: large files, slow connection, corrupt download
- Device testing: Honor + Samsung Fold
- Security review: no plaintext leaks in logs/cache

---

## Risks

| Risk | Mitigation |
|------|-----------|
| NIP-96 servers rate-limit new keys | Multiple fallback servers |
| Blossom ALPHA API changes | Our own HTTP client, not the crate |
| 512-byte memo too tight | Byte budget analysis shows ~192 bytes used — plenty of room |
| KEX not complete with recipient | UI prevents file send without KEX, shows "Set up encryption first" |
| APK size increase | Zero new native dependencies — all pure Java/Kotlin crypto |
