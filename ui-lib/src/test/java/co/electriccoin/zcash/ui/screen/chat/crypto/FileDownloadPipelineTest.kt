package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * TDD for the file receive pipeline: given a ZFILE message + encrypted blob,
 * unwrap key → decrypt → recover original file.
 *
 * This tests the local crypto — actual HTTP download is mocked.
 */
class FileDownloadPipelineTest {

    private val testSharedSecret = "test-shared-secret-32-bytes!!!!!".toByteArray()

    @Test
    fun full_receive_pipeline_decrypt_from_zfile_message() {
        // SENDER side: create an encrypted file + ZFILE message
        val originalImage = "fake image content for testing".toByteArray()
        val fileKey = E2EEncryption.generateFileKey()
        val encrypted = E2EEncryption.encryptFile(originalImage, fileKey)
        val wrappedKey = E2EEncryption.wrapFileKey(fileKey, testSharedSecret)
        val wrappedKeyB64 = java.util.Base64.getEncoder().encodeToString(wrappedKey)

        val zfileMsg = ZFILEMessage(
            hash = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            type = ZFILEType.JPEG,
            size = encrypted.size.toLong(),
            url = "https://nostr.build/test",
            wrappedKey = wrappedKeyB64,
            blurhash = "",
        )

        // RECEIVER side: parse ZFILE, "download" encrypted bytes, decrypt
        val parsed = ZFILEMessage.parse(zfileMsg.serialize())
        assertNotNull(parsed)

        // Simulate download: in real code this would be HTTP GET to parsed.url
        val downloadedBytes = encrypted // pretend we downloaded these

        // Unwrap the file key using receiver's E2E shared secret
        val unwrappedKey = E2EEncryption.unwrapFileKey(
            java.util.Base64.getDecoder().decode(parsed.wrappedKey),
            testSharedSecret
        )
        assertEquals(32, unwrappedKey.size)

        // Decrypt the file
        val decrypted = E2EEncryption.decryptFile(downloadedBytes, unwrappedKey)
        assertContentEquals(originalImage, decrypted)
    }

    @Test
    fun decrypt_with_aad_binding_succeeds() {
        val originalImage = "image with AAD".toByteArray()
        val fileKey = E2EEncryption.generateFileKey()
        val encrypted = E2EEncryption.encryptFile(originalImage, fileKey)

        val sha256 = co.electriccoin.zcash.ui.nostr.FileUploadManager.sha256Hex(encrypted)
        val aad = (sha256.take(32) + "|" + encrypted.size + "|j").toByteArray()

        val wrappedKey = E2EEncryption.wrapFileKey(fileKey, testSharedSecret, aad = aad)
        val unwrappedKey = E2EEncryption.unwrapFileKey(
            wrappedKey, testSharedSecret, aad = aad
        )
        val decrypted = E2EEncryption.decryptFile(encrypted, unwrappedKey)
        assertContentEquals(originalImage, decrypted)
    }
}
