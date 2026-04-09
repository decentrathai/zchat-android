# File Sharing Phase 1: Foundation + Quantum Shield — FINAL Plan (v4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Build the crypto + upload + protocol foundation for decentralized file sharing with optional quantum-safe protection.

**Architecture:** Files encrypted with AES-256-GCM (quantum-safe), uploaded to NIP-96/Blossom via Ktor HttpClient. AES key wrapped with E2E shared secret (ECDH + optional PSK for quantum resistance). NOSTR secp256k1 identity for NIP-98 upload auth. ZFILE protocol in 512-byte memos. Content-addressed by SHA-256 hash (URL is just a hint).

**Tech Stack:** Kotlin, javax.crypto (AES-256-GCM), Ktor HttpClient (existing), HKDF (existing in E2EEncryption.kt). New dependencies: `fr.acinq.secp256k1:secp256k1-kmp:0.16.0` + `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.16.0` (~1.5MB total, includes native .so for secp256k1 Schnorr signing).

**Test strategy:** All tests go to `androidTest/` (not `src/test/`) because E2EEncryption.kt imports `android.util.Base64` which is unavailable on JVM. Instrumented tests are slower (~2 min vs ~2 sec) but correct.

**Spec:** `docs/superpowers/specs/2026-04-07-file-sharing-design.md`

---

## Pre-Implementation: Test directories

All tests go to `androidTest/` because E2EEncryption.kt uses `android.util.Base64` (unavailable on JVM).
Existing `androidTest/` directories already exist — create new subdirs as needed:

```bash
mkdir -p ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/nostr
mkdir -p ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/screen/chat/crypto
```

---

## Task Dependency Graph

```
Task 1 (Crypto) ──→ Task 3 (QuantumShield) ──→ Task 4 (E2E+PSK upgrade)
                                                        ↓
Task 2 (Protocol) ──────────────────────────→ Task 7 (Verify)
                                                        ↑
Task 5 (NOSTR Identity) ──→ Task 6 (Upload) ──────────┘
```

Tasks 1, 2, 5 can run in parallel. Task 3 depends on 1. Task 4 depends on 3. Task 6 depends on 5. Task 7 depends on all.

---

## 7 Tasks, ~24 hours, 30 tests

### Task 1: File Encryption — extend E2EEncryption.kt (4h, 5 tests)

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/E2EEncryption.kt`
- Create: `ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/screen/chat/crypto/FileEncryptionTest.kt`

NO new FileEncryption class — extend the existing `E2EEncryption` object with file-specific methods to avoid crypto code duplication.

**TDD Tests:**
```kotlin
class FileEncryptionTest {
    @Test fun `encryptFile then decryptFile returns original data`()
    @Test fun `different encryptions produce different ciphertexts (random IV)`()
    @Test fun `wrapFileKey and unwrapFileKey roundtrip`()
    @Test fun `wrapFileKey with PSK produces different output than without`()
    @Test fun `generated file key is 32 bytes`()
}
```

**Add to E2EEncryption object:**
```kotlin
fun generateFileKey(): ByteArray  // 32-byte random AES key
fun encryptFile(plaintext: ByteArray, key: ByteArray): ByteArray  // IV + ciphertext
fun decryptFile(ciphertext: ByteArray, key: ByteArray): ByteArray
fun wrapFileKey(fileKey: ByteArray, sharedSecret: ByteArray, psk: ByteArray? = null): ByteArray
fun unwrapFileKey(wrapped: ByteArray, sharedSecret: ByteArray, psk: ByteArray? = null): ByteArray
```

`wrapFileKey` uses: `HKDF(sharedSecret || psk?, "ZCHAT_FILE_KEY_WRAP")` → AES-256-GCM encrypt the file key.

**Commit:** `feat: file encryption methods in E2EEncryption (TDD)`

---

### Task 2: ZFILEMessage — Protocol Type (3h, 6 tests)

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZFILEMessage.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZMSGConstants.kt`
- Create: `ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/screen/chat/model/ZFILEMessageTest.kt`

**TDD Tests:**
```kotlin
class ZFILEMessageTest {
    @Test fun `serialize produces valid ZFILE string under 300 bytes`()
    @Test fun `parse valid ZFILE string`()
    @Test fun `parse invalid string returns null`()
    @Test fun `serialize then parse roundtrip`()
    @Test fun `all file types have correct single-char codes`()
    @Test fun `sha256 hash is used as canonical identifier (not URL)`()
}
```

**Format:** `ZFILE|<sha256_32hex>|<type_1char>|<size_bytes>|<url_hint>|<wrappedKey_b64>|<blurhash_8>`

SHA-256 hash is 32 hex chars (128-bit collision resistance — safe for content-addressed lookup).
URL is a download hint only. If URL breaks, recipient can try other servers with the hash.
Wrapped key is ~80 base64 chars (AES-GCM: 12 IV + 32 ciphertext + 16 tag = 60 bytes → 80 b64).

**Updated byte budget:** ~40 (header) + 6 (ZFILE) + 32 (hash) + 1 (type) + 7 (size) + 60 (url) + 80 (key) + 8 (blur) + 10 (separators) = **~244 bytes** — fits in 512 with margin.

**Commit:** `feat: ZFILE protocol type with content-addressed file reference (TDD)`

---

### Task 3: QuantumShield PSK (4h, 5 tests)

**Depends on:** Task 1 (uses HKDF from E2EEncryption)

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/QuantumShield.kt`
- Create: `ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/screen/chat/crypto/QuantumShieldTest.kt`

**TDD Tests:**
```kotlin
class QuantumShieldTest {
    @Test fun `generateRandom produces 32 bytes`()
    @Test fun `derivePSK is deterministic`()
    @Test fun `derivePSK is order-independent (a,b) == (b,a)`()
    @Test fun `derivePSK with different inputs produces different PSK`()
    @Test fun `toQRPayload and fromQRPayload roundtrip`()
}
```

**Implementation:**
```kotlin
object QuantumShield {
    fun generateRandom(): ByteArray  // SecureRandom 32 bytes

    fun derivePSK(myRandom: ByteArray, theirRandom: ByteArray): ByteArray {
        // Lexicographic sort for deterministic order
        val sorted = listOf(myRandom, theirRandom).sortedWith(
            Comparator { a, b ->
                for (i in a.indices) {
                    val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
                    if (cmp != 0) return@Comparator cmp
                }
                0
            }
        )
        return HKDF.deriveKey(
            ikm = sorted[0] + sorted[1],
            salt = "ZCHAT_QS_V1".toByteArray(),
            info = "PSK".toByteArray(),
            length = 32
        )
    }

    fun toQRPayload(random: ByteArray): String = "ZCPSK:${Base64.encodeToString(random, Base64.NO_WRAP)}"
    fun fromQRPayload(payload: String): ByteArray? {
        if (!payload.startsWith("ZCPSK:")) return null
        return try { Base64.decode(payload.removePrefix("ZCPSK:"), Base64.NO_WRAP) } catch (e: Exception) { null }
    }
}
```

**Storage:** `ZchatPreferences.getQuantumShieldPSK(address): ByteArray?` / `setQuantumShieldPSK(address, psk)`

**Commit:** `feat: QuantumShield PSK with mutual derivation (TDD)`

---

### Task 4: E2E Upgrade — HKDF with Optional PSK (3h, 4 tests)

**Depends on:** Task 3

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/crypto/E2EEncryption.kt`
- Create: `ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/screen/chat/crypto/E2EPSKTest.kt`

**TDD Tests:**
```kotlin
class E2EPSKTest {
    @Test fun `deriveKeyV2 without PSK matches existing behavior exactly`()
    @Test fun `deriveKeyV2 with PSK produces different key`()
    @Test fun `deriveKeyV2 with PSK is deterministic`()
    @Test fun `full encrypt-decrypt roundtrip with PSK`()
}
```

**Implementation change (3 lines) — exact variable names from E2EEncryption.kt line 211:**
```kotlin
// BEFORE (line 211-217):
private fun deriveKeyV2(sharedSecret: ByteArray): ByteArray {
    return HKDF.deriveKey(
        ikm = sharedSecret,
        salt = HKDF_SALT_V2,
        info = HKDF_INFO,
        length = DERIVED_KEY_LENGTH
    )
}

// AFTER:
private fun deriveKeyV2(sharedSecret: ByteArray, psk: ByteArray? = null): ByteArray {
    val ikm = if (psk != null) sharedSecret + psk else sharedSecret
    return HKDF.deriveKey(
        ikm = ikm,
        salt = HKDF_SALT_V2,
        info = HKDF_INFO,
        length = DERIVED_KEY_LENGTH
    )
}
```

**CRITICAL TEST:** `deriveKeyV2(secret, null)` MUST produce byte-identical output to the old `deriveKeyV2(secret)`. This is the backward compatibility guarantee.

**Commit:** `feat: E2E key derivation with optional Quantum Shield PSK — backward compatible (TDD)`

---

### Task 5: NOSTRIdentity — secp256k1 + NIP-98 (4h, 5 tests)

**Independent — can run in parallel with Tasks 1-4.**

**Files:**
- Add dependencies to `settings.gradle.kts` + `ui-lib/build.gradle.kts`:
  - `fr.acinq.secp256k1:secp256k1-kmp:0.16.0` (API module)
  - `fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.16.0` (native .so for Android)
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/NOSTRIdentity.kt`
- Create: `ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/nostr/NOSTRIdentityTest.kt`

**Why secp256k1-kmp:** Android API 27's bundled Bouncy Castle does NOT support secp256k1. The `secp256k1-kmp` by ACINQ (Lightning/Phoenix wallet team) wraps Bitcoin Core's `libsecp256k1` via JNI. ~1.5MB total (native .so files for arm64/armeabi-v7a), Apache 2.0. Supports **Schnorr signing (BIP-340)** via `Secp256k1.signSchnorr()` which is REQUIRED for NIP-98 NOSTR auth events.

**TDD Tests:**
```kotlin
class NOSTRIdentityTest {
    @Test fun `derive produces 32-byte private key`()
    @Test fun `same seed produces same keys (deterministic)`()
    @Test fun `different seeds produce different keys`()
    @Test fun `npub starts with npub1`()
    @Test fun `signNIP98Event produces valid base64 JSON`()
}
```

**Commit:** `feat: NOSTRIdentity with secp256k1-kmp for NIP-98 auth (TDD)`

---

### Task 6: Upload Clients — Ktor-based NIP-96 + Blossom (6h, 5 tests)

**Depends on:** Task 5 (needs NOSTRIdentity for NIP-98 auth)

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileUploadClient.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/NIP96Client.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/BlossomClient.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileUploadManager.kt`
- Create: `ui-lib/src/androidTest/java/co/electriccoin/zcash/ui/nostr/FileUploadTest.kt`

**TDD Tests:**
```kotlin
class FileUploadTest {
    @Test fun `UploadResult contains url and sha256`()
    @Test fun `UploadResult with empty url is invalid`()
    @Test fun `FileUploadManager has ordered server list`()
    @Test fun `NIP96 auth header format is correct`()
    @Test fun `Blossom auth header format is correct`()
}
```

**Implementation uses Ktor (existing dependency):**
```kotlin
sealed class UploadOutcome {
    data class Success(val url: String, val sha256: String) : UploadOutcome()
    data class Failure(val error: String, val serverUrl: String) : UploadOutcome()
}

interface FileUploadClient {
    suspend fun upload(data: ByteArray, mimeType: String, identity: NOSTRIdentity): UploadOutcome
}
```

NIP-96: `Ktor HttpClient.submitFormWithBinaryData()` with `Authorization: Nostr <base64>`
Blossom: `Ktor HttpClient.put()` with `Authorization: Nostr <base64_kind24242>`

`FileUploadManager` tries servers in order: `nostr.build` → `void.cat` → `blossom.band` → `blossom.nostr.build`

**Commit:** `feat: NIP-96 + Blossom upload via Ktor with fallback (TDD)`

---

### Task 7: Full Verification (2h)

- [ ] Create test directories if not existing
- [ ] `./gradlew :ui-lib:connectedDebugAndroidTest` — all 30 instrumented tests pass on Honor device
- [ ] `./gradlew :ui-lib:compileZcashmainnetFossDebugSources` — zero errors (`-Werror`)
- [ ] `./gradlew :app:assembleZcashmainnetFossDebug` — APK builds
- [ ] Install on Honor — app launches, existing features work
- [ ] Manual test: upload a small encrypted blob to nostr.build (verify URL returns data)
- [ ] `git push decentrathai main`

---

## Phase 2: File Sharing UI (36h, built after Phase 1)

| # | Task | Effort |
|---|------|--------|
| 8 | Image compression (2048px, JPEG 80%) + blurhash lib | 4h |
| 9 | Attachment bottom sheet (Camera/Gallery/Document) | 6h |
| 10 | Upload progress overlay in chat bubble | 4h |
| 11 | Inline image display + document download card | 8h |
| 12 | Fullscreen image viewer | 4h |
| 13 | MemoParser: detect ZFILE, download, decrypt, display | 6h |
| 14 | E2E integration test on device (send file → receive → view) | 4h |

## Phase 3: Quantum Shield UI (18h, built after Phase 2)

| # | Task | Effort |
|---|------|--------|
| 15 | Shield icon in conversation header (grey=off, cyan=on) | 3h |
| 16 | QR exchange screen (2-step mutual scan) | 8h |
| 17 | Exchange state machine (none → pending_their_scan → active) | 4h |
| 18 | "Quantum Shield active" banner + tap for details | 3h |

## Grand Total: 78h across 3 phases

---

## Security Summary

| Threat | Without QS | With Quantum Shield |
|--------|-----------|-------------------|
| Classical eavesdrop | AES-256-GCM ✓ | AES-256-GCM ✓ |
| Classical MITM | KEX signatures ✓ | KEX signatures ✓ |
| Quantum HNDL | ECDH vulnerable ✗ | PSK + ECDH = **quantum-safe** ✓ |
| Server compromise | E2E encrypted ✓ | E2E encrypted ✓ |
| File tampering | SHA-256 verify ✓ | SHA-256 verify ✓ |
| File permanence | No deletion ⚠ | No deletion ⚠ |

## Assumptions Verified

- [x] E2EEncryption.kt has `deriveKeyV2` at line 211 — confirmed
- [x] HKDF object exists with `deriveKey()` — confirmed at line 35
- [x] ZMSG v4c chunking exists — confirmed
- [x] Ktor HttpClient is the project's HTTP library — confirmed
- [x] EncryptedSharedPreferences in ZchatPreferences — confirmed
- [x] Android min SDK 27 — confirmed (secp256k1-kmp compatible)
- [x] No `src/test/java` — using `androidTest/` instead (android.util.Base64 dependency)
- [x] E2E uses secp256r1 (P-256), NOT secp256k1 — confirmed (need external lib for NOSTR)
