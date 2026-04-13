package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.nostr.FileUploadManager
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD for the file sharing pipeline: encrypt file → create ZFILE message → parse back.
 * Tests the unit-level crypto flow without Android-specific URI/ContentResolver.
 */
class FileSharePipelineTest {

    private val testSharedSecret = "test-shared-secret-32-bytes!!!!!".toByteArray()

    @Test
    fun encrypt_file_create_zfile_message_roundtrip() {
        val originalBytes = "This is a test image file content".toByteArray()

        // Step 1: Generate file key and encrypt
        val fileKey = E2EEncryption.generateFileKey()
        val encryptedBytes = E2EEncryption.encryptFile(originalBytes, fileKey)

        // Step 2: Wrap the file key with the conversation's shared secret
        val wrappedKey = E2EEncryption.wrapFileKey(fileKey, testSharedSecret)
        val wrappedKeyB64 = java.util.Base64.getEncoder().encodeToString(wrappedKey)

        // Step 3: Compute SHA-256 of the encrypted file (for integrity check)
        val sha256 = FileUploadManager.sha256Hex(encryptedBytes)

        // Step 4: Create ZFILE message
        val zfileMsg = ZFILEMessage(
            hash = sha256.take(32),
            type = ZFILEType.JPEG,
            size = encryptedBytes.size.toLong(),
            url = "https://nostr.build/test123",
            wrappedKey = wrappedKeyB64,
            blurhash = "LEHV6n",
        )
        val serialized = zfileMsg.serialize()
        assertTrue(serialized.startsWith("ZFILE|"))

        // Step 5: Parse back
        val parsed = ZFILEMessage.parse(serialized)
        assertNotNull(parsed)
        assertEquals(sha256.take(32), parsed.hash)
        assertEquals(ZFILEType.JPEG, parsed.type)

        // Step 6: Unwrap file key and decrypt
        val unwrappedWrapped = java.util.Base64.getDecoder().decode(parsed.wrappedKey)
        val recoveredFileKey = E2EEncryption.unwrapFileKey(unwrappedWrapped, testSharedSecret)
        val decryptedBytes = E2EEncryption.decryptFile(encryptedBytes, recoveredFileKey)
        assertContentEquals(originalBytes, decryptedBytes)
    }

    @Test
    fun zfile_message_fits_in_512_byte_memo() {
        val fileKey = E2EEncryption.generateFileKey()
        val wrappedKey = E2EEncryption.wrapFileKey(fileKey, testSharedSecret)
        val wrappedKeyB64 = java.util.Base64.getEncoder().encodeToString(wrappedKey)

        val zfileMsg = ZFILEMessage(
            hash = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            type = ZFILEType.JPEG,
            size = 524288,
            url = "https://nostr.build/p/abc123def456",
            wrappedKey = wrappedKeyB64,
            blurhash = "LEHV6nWB",
        )
        val serialized = zfileMsg.serialize()

        // ZMSG v4 reply format overhead: ~38 bytes for header
        // Total must fit in 512 bytes
        assertTrue(
            serialized.length + 38 <= 512,
            "ZFILE message (${serialized.length} bytes) + ZMSG header (38) = ${serialized.length + 38} exceeds 512"
        )
    }
}
