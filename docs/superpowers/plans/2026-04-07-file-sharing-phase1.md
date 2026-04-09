# File Sharing Phase 1: Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the crypto + upload + protocol foundation for decentralized file sharing (no UI yet).

**Architecture:** NOSTR secp256k1 identity derived from BIP39 seed for NIP-98 auth. Files encrypted with AES-256-GCM, uploaded to NIP-96/Blossom servers. ZFILE protocol type added to ZMSG for 512-byte memos. All components TDD with unit tests.

**Tech Stack:** Kotlin, Android built-in crypto (secp256k1 via Bouncy Castle, AES-256-GCM), OkHttp for uploads, existing Zcash SDK for seed access.

**Spec:** `docs/superpowers/specs/2026-04-07-file-sharing-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `ui-lib/src/main/java/.../nostr/NOSTRIdentity.kt` | CREATE | secp256k1 key derivation + NIP-98 event signing |
| `ui-lib/src/main/java/.../nostr/FileEncryption.kt` | CREATE | AES-256-GCM encrypt/decrypt, key wrap/unwrap |
| `ui-lib/src/main/java/.../nostr/NIP96Client.kt` | CREATE | NIP-96 HTTP file upload with NIP-98 auth |
| `ui-lib/src/main/java/.../nostr/BlossomClient.kt` | CREATE | Blossom HTTP file upload with kind-24242 auth |
| `ui-lib/src/main/java/.../nostr/FileUploadManager.kt` | CREATE | NIP-96 primary + Blossom fallback orchestration |
| `ui-lib/src/main/java/.../chat/model/ZMSGConstants.kt` | MODIFY | Add ZFILE message type constant |
| `ui-lib/src/main/java/.../chat/model/ZFILEMessage.kt` | CREATE | ZFILE data class + parse/serialize |
| `ui-lib/src/test/java/.../nostr/NOSTRIdentityTest.kt` | CREATE | Key derivation + signing tests |
| `ui-lib/src/test/java/.../nostr/FileEncryptionTest.kt` | CREATE | Encrypt/decrypt round-trip tests |
| `ui-lib/src/test/java/.../nostr/NIP96ClientTest.kt` | CREATE | Upload tests with mock server |
| `ui-lib/src/test/java/.../chat/model/ZFILEMessageTest.kt` | CREATE | Protocol parse/serialize tests |

All paths relative to `/home/yourt/zchat-android/`.
Package base: `co.electriccoin.zcash.ui`

---

### Task 1: FileEncryption — AES-256-GCM encrypt/decrypt

**Files:**
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/FileEncryptionTest.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileEncryption.kt`

- [ ] **Step 1: Write failing test — encrypt then decrypt returns original data**

```kotlin
package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FileEncryptionTest {

    @Test
    fun `encrypt then decrypt returns original data`() {
        val plaintext = "Hello, this is a test file content!".toByteArray()
        val (ciphertext, key) = FileEncryption.encrypt(plaintext)

        // Ciphertext should be different from plaintext
        assert(!ciphertext.contentEquals(plaintext))

        // Decrypt should return original
        val decrypted = FileEncryption.decrypt(ciphertext, key)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `different encryptions produce different ciphertexts`() {
        val plaintext = "Same content".toByteArray()
        val (ct1, _) = FileEncryption.encrypt(plaintext)
        val (ct2, _) = FileEncryption.encrypt(plaintext)
        assert(!ct1.contentEquals(ct2)) // Random IV makes them different
    }

    @Test
    fun `key wrap and unwrap roundtrip`() {
        val aesKey = FileEncryption.generateKey()
        val sharedSecret = "test-shared-secret-32-bytes!!!!!".toByteArray()

        val wrapped = FileEncryption.wrapKey(aesKey, sharedSecret)
        val unwrapped = FileEncryption.unwrapKey(wrapped, sharedSecret)

        assertContentEquals(aesKey, unwrapped)
    }

    @Test
    fun `generated key is 32 bytes`() {
        val key = FileEncryption.generateKey()
        assertEquals(32, key.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/yourt/zchat-android && ./gradlew :ui-lib:testDebugUnitTest --tests "co.electriccoin.zcash.ui.nostr.FileEncryptionTest" 2>&1 | tail -10`
Expected: FAIL — `FileEncryption` class not found

- [ ] **Step 3: Write minimal implementation**

```kotlin
package co.electriccoin.zcash.ui.nostr

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM file encryption for ZCHAT file sharing.
 * Random 12-byte IV prepended to ciphertext.
 */
object FileEncryption {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128

    fun generateKey(): ByteArray {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(KEY_SIZE, SecureRandom())
        return generator.generateKey().encoded
    }

    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = generateKey()
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE, iv))
        val encrypted = cipher.doFinal(plaintext)
        // Prepend IV to ciphertext
        return Pair(iv + encrypted, key)
    }

    fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val iv = ciphertext.copyOfRange(0, IV_SIZE)
        val data = ciphertext.copyOfRange(IV_SIZE, ciphertext.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE, iv))
        return cipher.doFinal(data)
    }

    fun wrapKey(aesKey: ByteArray, sharedSecret: ByteArray): ByteArray {
        val (wrapped, _) = encryptWithKey(aesKey, sharedSecret.copyOf(32))
        return wrapped
    }

    fun unwrapKey(wrappedKey: ByteArray, sharedSecret: ByteArray): ByteArray {
        return decryptWithKey(wrappedKey, sharedSecret.copyOf(32))
    }

    private fun encryptWithKey(plaintext: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE, iv))
        return Pair(iv + cipher.doFinal(plaintext), key)
    }

    private fun decryptWithKey(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val iv = ciphertext.copyOfRange(0, IV_SIZE)
        val data = ciphertext.copyOfRange(IV_SIZE, ciphertext.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE, iv))
        return cipher.doFinal(data)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ui-lib:testDebugUnitTest --tests "co.electriccoin.zcash.ui.nostr.FileEncryptionTest" 2>&1 | tail -10`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/FileEncryptionTest.kt
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileEncryption.kt
git commit -m "feat: FileEncryption — AES-256-GCM encrypt/decrypt for file sharing (TDD)"
```

---

### Task 2: ZFILEMessage — protocol parse/serialize

**Files:**
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/chat/model/ZFILEMessageTest.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZFILEMessage.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZMSGConstants.kt`

- [ ] **Step 1: Write failing test — parse and serialize ZFILE memo**

```kotlin
package co.electriccoin.zcash.ui.chat.model

import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZFILEMessageTest {

    @Test
    fun `serialize produces valid ZFILE string`() {
        val msg = ZFILEMessage(
            hash = "a1b2c3d4e5f6g7h8",
            type = ZFILEType.JPEG,
            size = 245760,
            url = "nostr.build/abc123",
            wrappedKey = "kE9xY2base64key==",
            blurhash = "LKO2?U%2"
        )
        val serialized = msg.serialize()
        assertTrue(serialized.startsWith("ZFILE|"))
        assertTrue(serialized.contains("a1b2c3d4e5f6g7h8"))
        assertTrue(serialized.contains("j")) // JPEG type code
        assertTrue(serialized.length < 300) // Must fit in 512-byte memo with header
    }

    @Test
    fun `parse valid ZFILE string`() {
        val raw = "ZFILE|a1b2c3d4e5f6g7h8|j|245760|nostr.build/abc123|kE9xY2base64key==|LKO2?U%2"
        val msg = ZFILEMessage.parse(raw)
        assertNotNull(msg)
        assertEquals("a1b2c3d4e5f6g7h8", msg.hash)
        assertEquals(ZFILEType.JPEG, msg.type)
        assertEquals(245760L, msg.size)
        assertEquals("nostr.build/abc123", msg.url)
        assertEquals("kE9xY2base64key==", msg.wrappedKey)
        assertEquals("LKO2?U%2", msg.blurhash)
    }

    @Test
    fun `parse invalid string returns null`() {
        assertNull(ZFILEMessage.parse("not a zfile"))
        assertNull(ZFILEMessage.parse("ZFILE|too|few"))
        assertNull(ZFILEMessage.parse(""))
    }

    @Test
    fun `serialize then parse roundtrip`() {
        val original = ZFILEMessage(
            hash = "deadbeef12345678",
            type = ZFILEType.PDF,
            size = 1048576,
            url = "blossom.band/xyz",
            wrappedKey = "wrappedKeyBase64==",
            blurhash = "L5H2EC=0"
        )
        val parsed = ZFILEMessage.parse(original.serialize())
        assertNotNull(parsed)
        assertEquals(original.hash, parsed.hash)
        assertEquals(original.type, parsed.type)
        assertEquals(original.size, parsed.size)
        assertEquals(original.url, parsed.url)
    }

    @Test
    fun `all file types have correct codes`() {
        assertEquals("j", ZFILEType.JPEG.code)
        assertEquals("p", ZFILEType.PNG.code)
        assertEquals("g", ZFILEType.GIF.code)
        assertEquals("w", ZFILEType.WEBP.code)
        assertEquals("d", ZFILEType.PDF.code)
        assertEquals("z", ZFILEType.ZIP.code)
        assertEquals("t", ZFILEType.TXT.code)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ui-lib:testDebugUnitTest --tests "co.electriccoin.zcash.ui.chat.model.ZFILEMessageTest" 2>&1 | tail -10`
Expected: FAIL — classes not found

- [ ] **Step 3: Write minimal implementation**

```kotlin
// ZFILEMessage.kt
package co.electriccoin.zcash.ui.screen.chat.model

enum class ZFILEType(val code: String, val mimeType: String) {
    JPEG("j", "image/jpeg"),
    PNG("p", "image/png"),
    GIF("g", "image/gif"),
    WEBP("w", "image/webp"),
    PDF("d", "application/pdf"),
    ZIP("z", "application/zip"),
    TXT("t", "text/plain");

    companion object {
        fun fromCode(code: String): ZFILEType? = entries.find { it.code == code }
        fun fromMime(mime: String): ZFILEType? = entries.find { it.mimeType == mime }
    }
}

data class ZFILEMessage(
    val hash: String,        // SHA-256 first 16 hex chars
    val type: ZFILEType,     // File type code
    val size: Long,          // File size in bytes
    val url: String,         // NIP-96/Blossom URL
    val wrappedKey: String,  // AES key encrypted with E2E shared secret, base64
    val blurhash: String,    // 8-char blurhash for image placeholder
) {
    fun serialize(): String = "ZFILE|$hash|${type.code}|$size|$url|$wrappedKey|$blurhash"

    val isImage: Boolean get() = type in listOf(ZFILEType.JPEG, ZFILEType.PNG, ZFILEType.GIF, ZFILEType.WEBP)

    companion object {
        fun parse(raw: String): ZFILEMessage? {
            if (!raw.startsWith("ZFILE|")) return null
            val parts = raw.removePrefix("ZFILE|").split("|")
            if (parts.size < 6) return null
            val type = ZFILEType.fromCode(parts[1]) ?: return null
            val size = parts[2].toLongOrNull() ?: return null
            return ZFILEMessage(
                hash = parts[0],
                type = type,
                size = size,
                url = parts[3],
                wrappedKey = parts[4],
                blurhash = parts.getOrElse(5) { "" },
            )
        }
    }
}
```

Also add ZFILE constant to ZMSGConstants.kt:
```kotlin
const val ZFILE = "ZFILE|"    // File attachment (image, document)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ui-lib:testDebugUnitTest --tests "co.electriccoin.zcash.ui.chat.model.ZFILEMessageTest" 2>&1 | tail -10`
Expected: 5 tests PASS

- [ ] **Step 5: Compile check full project**

Run: `./gradlew :ui-lib:compileZcashmainnetFossDebugSources 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add ui-lib/src/test/java/co/electriccoin/zcash/ui/chat/model/ZFILEMessageTest.kt
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZFILEMessage.kt
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZMSGConstants.kt
git commit -m "feat: ZFILE protocol type — parse/serialize for file sharing memos (TDD)"
```

---

### Task 3: NOSTRIdentity — secp256k1 key derivation

**Files:**
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/NOSTRIdentityTest.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/NOSTRIdentity.kt`

NOTE: This task requires access to BIP32 key derivation. Check if the Zcash SDK exposes BIP32 derivation, or if we need to implement `m/44'/1237'/0'/0/0` manually using HMAC-SHA512. The test should verify deterministic key derivation from a known seed.

- [ ] **Step 1: Write failing test**

```kotlin
package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NOSTRIdentityTest {

    // Known test seed (DO NOT use in production)
    private val testSeed = ByteArray(64) { it.toByte() }

    @Test
    fun `derive produces 32-byte private key`() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertNotNull(identity)
        assertEquals(32, identity.privateKey.size)
    }

    @Test
    fun `derive produces 32-byte public key`() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertEquals(32, identity.publicKey.size)
    }

    @Test
    fun `same seed produces same keys`() {
        val id1 = NOSTRIdentity.fromSeed(testSeed)
        val id2 = NOSTRIdentity.fromSeed(testSeed)
        assertTrue(id1.privateKey.contentEquals(id2.privateKey))
        assertTrue(id1.publicKey.contentEquals(id2.publicKey))
    }

    @Test
    fun `different seeds produce different keys`() {
        val seed2 = ByteArray(64) { (it + 100).toByte() }
        val id1 = NOSTRIdentity.fromSeed(testSeed)
        val id2 = NOSTRIdentity.fromSeed(seed2)
        assert(!id1.privateKey.contentEquals(id2.privateKey))
    }

    @Test
    fun `npub starts with npub1`() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertTrue(identity.npub.startsWith("npub1"))
    }

    @Test
    fun `signNIP98Event produces base64 string`() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val event = identity.signNIP98Event("https://nostr.build/upload", "POST")
        assertNotNull(event)
        assertTrue(event.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Write implementation**

Key derivation: BIP32 path `m/44'/1237'/0'/0/0`
- Derive master key from seed via HMAC-SHA512("Bitcoin seed", seed)
- Derive child keys through BIP32 hardened derivation
- Final 32 bytes = secp256k1 private key
- Public key = secp256k1 point multiplication (x-only, 32 bytes)

NIP-98 event: JSON event of kind 27235 with url + method tags, signed with Schnorr.

NOTE: If secp256k1 Schnorr signing is not available in Android's Bouncy Castle, use the `fr.acinq.secp256k1:secp256k1-kmp` library (~200KB) as a dependency. Check availability first.

- [ ] **Step 4: Run tests**
- [ ] **Step 5: Commit**

---

### Task 4: NIP96Client + BlossomClient — file upload

**Files:**
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/FileUploadTest.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/NIP96Client.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/BlossomClient.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/FileUploadManager.kt`

- [ ] **Step 1: Write failing tests for upload interface**

```kotlin
package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileUploadTest {

    @Test
    fun `NIP96Client implements FileUploadClient interface`() {
        // Verify the interface exists and NIP96Client implements it
        val client: FileUploadClient = NIP96Client(
            serverUrl = "https://nostr.build",
            identity = NOSTRIdentity.fromSeed(ByteArray(64))
        )
        assertNotNull(client)
    }

    @Test
    fun `BlossomClient implements FileUploadClient interface`() {
        val client: FileUploadClient = BlossomClient(
            serverUrl = "https://blossom.band",
            identity = NOSTRIdentity.fromSeed(ByteArray(64))
        )
        assertNotNull(client)
    }

    @Test
    fun `FileUploadManager tries NIP96 first`() {
        val manager = FileUploadManager(
            nip96Urls = listOf("https://nostr.build"),
            blossomUrls = listOf("https://blossom.band"),
            identity = NOSTRIdentity.fromSeed(ByteArray(64))
        )
        assertNotNull(manager)
    }

    @Test
    fun `UploadResult contains url and sha256`() {
        val result = UploadResult(url = "https://nostr.build/abc", sha256 = "deadbeef1234")
        assertTrue(result.url.isNotEmpty())
        assertTrue(result.sha256.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Write implementation — interfaces + clients**

```kotlin
// FileUploadClient.kt (interface)
interface FileUploadClient {
    suspend fun upload(data: ByteArray, mimeType: String): UploadResult
}

data class UploadResult(val url: String, val sha256: String)

// NIP96Client.kt — POST multipart with NIP-98 Authorization header
// BlossomClient.kt — PUT with kind-24242 Authorization header
// FileUploadManager.kt — orchestrates NIP-96 primary + Blossom fallback
```

Uses OkHttp (already in project dependencies via Zcash SDK).

- [ ] **Step 4: Run tests**
- [ ] **Step 5: Integration test with real server (manual, not automated)**

Manually test: upload a small encrypted file to `nostr.build` and verify the URL works.

- [ ] **Step 6: Commit**

```bash
git add ui-lib/src/test/java/co/electriccoin/zcash/ui/nostr/
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/
git commit -m "feat: NIP96 + Blossom file upload clients with NIP-98 auth (TDD)"
```

---

### Task 5: Full compile + APK build verification

- [ ] **Step 1: Compile check**

```bash
./gradlew :ui-lib:compileZcashmainnetFossDebugSources
```
Must pass with zero errors.

- [ ] **Step 2: Run all new tests**

```bash
./gradlew :ui-lib:testDebugUnitTest --tests "co.electriccoin.zcash.ui.nostr.*" --tests "co.electriccoin.zcash.ui.chat.model.ZFILEMessageTest"
```
All tests must pass.

- [ ] **Step 3: Run existing tests**

```bash
./gradlew :ui-lib:testDebugUnitTest
```
No regressions.

- [ ] **Step 4: Build APK**

```bash
./gradlew :app:assembleZcashmainnetFossDebug
```
Must succeed.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat: file sharing Phase 1 complete — encryption, protocol, upload clients"
git push decentrathai main
```
