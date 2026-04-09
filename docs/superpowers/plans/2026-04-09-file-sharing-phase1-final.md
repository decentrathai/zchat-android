# File Sharing Phase 1: Foundation + Quantum Shield — Final Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the crypto foundation for decentralized file sharing with optional quantum-safe protection via mutual QR key exchange.

**Architecture:** Files encrypted with AES-256-GCM (quantum-safe symmetric), uploaded to NIP-96/Blossom. File AES key wrapped with E2E shared secret derived from ECDH (classical) + optional PSK (quantum-safe). NOSTR secp256k1 identity for NIP-98 upload auth. ZFILE protocol type for 512-byte Zcash memos.

**Tech Stack:** Kotlin, Android built-in crypto (AES-256-GCM, secp256k1, HKDF), OkHttp, existing Zcash SDK. Zero new library dependencies.

**Spec:** `docs/superpowers/specs/2026-04-07-file-sharing-design.md`

---

## Overview: 6 Tasks, ~24 hours

| Task | Component | Effort | Tests |
|------|-----------|--------|-------|
| 1 | FileEncryption (AES-256-GCM) | 4h | 4 unit tests |
| 2 | ZFILEMessage (protocol) | 3h | 5 unit tests |
| 3 | Quantum Shield PSK (exchange + storage) | 4h | 5 unit tests |
| 4 | E2E upgrade (HKDF with optional PSK) | 3h | 4 unit tests |
| 5 | NOSTRIdentity (secp256k1 + NIP-98) | 4h | 6 unit tests |
| 6 | NIP96Client + BlossomClient (upload) | 6h | 4 unit tests |
| **Total** | | **24h** | **28 tests** |

---

### Task 1: FileEncryption — AES-256-GCM

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileEncryption.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/FileEncryptionTest.kt`

**TDD Tests:**

```kotlin
class FileEncryptionTest {
    @Test fun `encrypt then decrypt returns original data`()
    @Test fun `different encryptions produce different ciphertexts (random IV)`()
    @Test fun `key wrap and unwrap roundtrip`()
    @Test fun `generated key is 32 bytes`()
}
```

**Implementation:** AES-256-GCM with random 12-byte IV prepended. Key wrap uses same AES-GCM with the E2E shared secret as key. All `javax.crypto` — zero external dependencies.

**Compile check:** `./gradlew :ui-lib:testDebugUnitTest --tests "*.FileEncryptionTest"`

**Commit:** `feat: FileEncryption — AES-256-GCM for file sharing (TDD)`

---

### Task 2: ZFILEMessage — Protocol Type

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZFILEMessage.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZMSGConstants.kt` (add ZFILE constant)
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/chat/model/ZFILEMessageTest.kt`

**TDD Tests:**

```kotlin
class ZFILEMessageTest {
    @Test fun `serialize produces valid ZFILE string under 300 bytes`()
    @Test fun `parse valid ZFILE string`()
    @Test fun `parse invalid string returns null`()
    @Test fun `serialize then parse roundtrip`()
    @Test fun `all file types have correct single-char codes`()
}
```

**Format:** `ZFILE|<hash16>|<type1>|<size>|<url>|<wrappedKey44>|<blur8>`

Type codes: j=jpeg, p=png, g=gif, w=webp, d=pdf, z=zip, t=txt

**Commit:** `feat: ZFILE protocol type for file sharing memos (TDD)`

---

### Task 3: Quantum Shield PSK — Mutual QR Exchange

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/QuantumShield.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/chat/crypto/QuantumShieldTest.kt`

**TDD Tests:**

```kotlin
class QuantumShieldTest {
    @Test fun `generateRandom produces 32 bytes`()
    @Test fun `derivePSK from two randoms is deterministic`()
    @Test fun `derivePSK is order-independent (Alice+Bob = Bob+Alice)`()
    @Test fun `derivePSK with different inputs produces different PSK`()
    @Test fun `toQRString and fromQRString roundtrip`()
}
```

**Implementation:**

```kotlin
object QuantumShield {
    fun generateRandom(): ByteArray  // SecureRandom 32 bytes
    
    fun derivePSK(myRandom: ByteArray, theirRandom: ByteArray): ByteArray {
        // Sort to ensure order-independence
        val (first, second) = listOf(myRandom, theirRandom)
            .sortedWith(compareBy { it.toHex() })
        return HKDF.deriveKey(
            ikm = first + second,
            salt = "ZCHAT_QUANTUM_SHIELD_V1".toByteArray(),
            info = "PSK_DERIVATION".toByteArray(),
            length = 32
        )
    }
    
    fun toQRString(random: ByteArray): String  // "ZCPSK:" + base64
    fun fromQRString(qr: String): ByteArray?   // parse and validate
}
```

**Storage:** PSK stored via existing `ZchatPreferences` in EncryptedSharedPreferences: `psk_<address_hash>`.

**Commit:** `feat: QuantumShield PSK generation and derivation (TDD)`

---

### Task 4: E2E Upgrade — HKDF with Optional PSK

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/E2EEncryption.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/chat/crypto/E2EEncryptionPSKTest.kt`

**TDD Tests:**

```kotlin
class E2EEncryptionPSKTest {
    @Test fun `deriveKey without PSK matches existing behavior (backward compat)`()
    @Test fun `deriveKey with PSK produces different key than without`()
    @Test fun `deriveKey with PSK is deterministic`()
    @Test fun `encrypt-decrypt roundtrip with PSK`()
}
```

**Implementation:** Modify `deriveKeyV2` to accept optional PSK:

```kotlin
// BEFORE:
private fun deriveKeyV2(sharedSecret: ByteArray): ByteArray {
    return HKDF.deriveKey(ikm = sharedSecret, salt = SALT, info = INFO, length = 32)
}

// AFTER (backward compatible):
private fun deriveKeyV2(sharedSecret: ByteArray, psk: ByteArray? = null): ByteArray {
    val ikm = if (psk != null) sharedSecret + psk else sharedSecret
    return HKDF.deriveKey(ikm = ikm, salt = SALT, info = INFO, length = 32)
}
```

**Critical:** When PSK is null, output is IDENTICAL to current behavior. Zero breaking changes for existing conversations.

**Commit:** `feat: E2E key derivation supports optional Quantum Shield PSK (TDD)`

---

### Task 5: NOSTRIdentity — secp256k1 Key Derivation

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/NOSTRIdentity.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/NOSTRIdentityTest.kt`

**TDD Tests:**

```kotlin
class NOSTRIdentityTest {
    @Test fun `derive produces 32-byte private key`()
    @Test fun `derive produces 32-byte public key`()
    @Test fun `same seed produces same keys (deterministic)`()
    @Test fun `different seeds produce different keys`()
    @Test fun `npub starts with npub1`()
    @Test fun `signNIP98Event produces non-empty base64`()
}
```

**Implementation:** BIP32 derivation `m/44'/1237'/0'/0/0` using HMAC-SHA512 chain. Android's built-in `java.security` with Bouncy Castle (bundled in Android) for secp256k1. NIP-98 event: JSON kind 27235 with url+method tags, Schnorr-signed.

**NOTE:** If Android's bundled Bouncy Castle doesn't support secp256k1 Schnorr signing on the target API level, use ECDSA signing for NIP-98 (some NIP-96 servers accept it) or add `fr.acinq.secp256k1:secp256k1-kmp:0.16.0` (~200KB, pure Kotlin). Test on Honor device first.

**Commit:** `feat: NOSTRIdentity — secp256k1 key derivation + NIP-98 signing (TDD)`

---

### Task 6: NIP96Client + BlossomClient — File Upload

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileUploadClient.kt` (interface)
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/NIP96Client.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/BlossomClient.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileUploadManager.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/FileUploadTest.kt`

**TDD Tests:**

```kotlin
class FileUploadTest {
    @Test fun `NIP96Client implements FileUploadClient`()
    @Test fun `BlossomClient implements FileUploadClient`()
    @Test fun `FileUploadManager tries NIP96 first`()
    @Test fun `UploadResult contains url and sha256`()
}
```

**Implementation:**

```kotlin
interface FileUploadClient {
    suspend fun upload(data: ByteArray, mimeType: String): UploadResult
}
data class UploadResult(val url: String, val sha256: String)
```

- `NIP96Client`: POST multipart to `nostr.build/api/v2/media` with NIP-98 auth header
- `BlossomClient`: PUT to `blossom.band/upload` with kind-24242 auth header
- `FileUploadManager`: tries NIP-96 servers first, falls back to Blossom

Uses OkHttp (already in dependencies via Zcash SDK).

**Manual integration test:** Upload a small encrypted test file to nostr.build, verify URL works.

**Commit:** `feat: NIP96 + Blossom upload clients with auth (TDD)`

---

### Task 7: Verification — Compile + All Tests + APK

- [ ] `./gradlew :ui-lib:testDebugUnitTest` — all 28 new tests pass
- [ ] `./gradlew :ui-lib:compileZcashmainnetFossDebugSources` — zero errors
- [ ] `./gradlew :app:assembleZcashmainnetFossDebug` — APK builds
- [ ] Install on Honor, verify app launches and existing features work
- [ ] Final commit + push to GitHub

---

## Phase 2: File Sharing UI (defined now, built after Phase 1)

| Task | Component | Effort |
|------|-----------|--------|
| 8 | Image compression (2048px, JPEG 80%) + blurhash | 4h |
| 9 | Attachment bottom sheet (Camera/Gallery/Document) | 6h |
| 10 | Upload progress overlay in chat bubble | 4h |
| 11 | Inline image display + document download card | 8h |
| 12 | Fullscreen image viewer | 4h |
| 13 | MemoParser integration (detect ZFILE, trigger download) | 6h |
| 14 | End-to-end integration test on device | 4h |
| **Phase 2 total** | | **36h** |

## Phase 3: Quantum Shield UI (built after Phase 2)

| Task | Component | Effort |
|------|-----------|--------|
| 15 | Shield icon in conversation header (grey/cyan) | 3h |
| 16 | QR exchange screen (2-step mutual scan) | 8h |
| 17 | Exchange state machine (pending/active/none) | 4h |
| 18 | "Quantum Shield active" banner + details dialog | 3h |
| **Phase 3 total** | | **18h** |

## Grand Total: ~78h across 3 phases

Phase 1 (crypto foundation): 24h → Phase 2 (file sharing UI): 36h → Phase 3 (Quantum Shield UI): 18h

---

## Security Properties After Phase 1

| Threat | Protection | Status |
|--------|-----------|--------|
| Classical eavesdropping | ECDH + AES-256-GCM | Protected |
| Classical MITM | KEX signatures (existing) | Protected |
| Quantum HNDL (with PSK) | PSK makes shared secret quantum-safe | **Protected** |
| Quantum HNDL (without PSK) | Only ECDH — vulnerable | Classical only |
| Server compromise (NIP-96) | Files E2E encrypted before upload | Protected |
| File integrity | SHA-256 hash in memo | Protected |
