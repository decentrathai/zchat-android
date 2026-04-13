package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileEncryptionTest {

    @Test
    fun encryptFile_then_decryptFile_returns_original_data() {
        val plaintext = "Hello, this is test file content for ZCHAT!".toByteArray()
        val key = E2EEncryption.generateFileKey()
        val ciphertext = E2EEncryption.encryptFile(plaintext, key)
        assert(!ciphertext.contentEquals(plaintext))
        val decrypted = E2EEncryption.decryptFile(ciphertext, key)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun different_encryptions_produce_different_ciphertexts() {
        val plaintext = "Same content".toByteArray()
        val key = E2EEncryption.generateFileKey()
        val ct1 = E2EEncryption.encryptFile(plaintext, key)
        val ct2 = E2EEncryption.encryptFile(plaintext, key)
        assert(!ct1.contentEquals(ct2)) // Random IV
    }

    @Test
    fun wrapFileKey_and_unwrapFileKey_roundtrip() {
        val fileKey = E2EEncryption.generateFileKey()
        val sharedSecret = "test-shared-secret-32-bytes!!!!!".toByteArray()
        val wrapped = E2EEncryption.wrapFileKey(fileKey, sharedSecret)
        val unwrapped = E2EEncryption.unwrapFileKey(wrapped, sharedSecret)
        assertContentEquals(fileKey, unwrapped)
    }

    @Test
    fun wrapFileKey_with_PSK_produces_different_output() {
        val fileKey = E2EEncryption.generateFileKey()
        val sharedSecret = "test-shared-secret-32-bytes!!!!!".toByteArray()
        val psk = "quantum-shield-psk-32-bytes!!!!!".toByteArray()
        val withoutPSK = E2EEncryption.wrapFileKey(fileKey, sharedSecret)
        val withPSK = E2EEncryption.wrapFileKey(fileKey, sharedSecret, psk)
        assert(!withoutPSK.contentEquals(withPSK))
    }

    @Test
    fun generated_file_key_is_32_bytes() {
        val key = E2EEncryption.generateFileKey()
        assertEquals(32, key.size)
    }

    @Test
    fun wrapFileKey_with_AAD_roundtrip() {
        val fileKey = E2EEncryption.generateFileKey()
        val sharedSecret = "test-shared-secret-32-bytes!!!!!".toByteArray()
        val aad = "sha256hex||1048576||j".toByteArray() // simulates hash||size||type binding

        val wrapped = E2EEncryption.wrapFileKey(fileKey, sharedSecret, psk = null, aad = aad)
        val unwrapped = E2EEncryption.unwrapFileKey(wrapped, sharedSecret, psk = null, aad = aad)
        assertContentEquals(fileKey, unwrapped)
    }

    @Test
    fun wrapFileKey_with_mismatched_AAD_fails_aead_check() {
        val fileKey = E2EEncryption.generateFileKey()
        val sharedSecret = "test-shared-secret-32-bytes!!!!!".toByteArray()
        val aadSender = "correct_hash||1048576||j".toByteArray()
        val aadAttacker = "tampered_hash||999||z".toByteArray()

        val wrapped = E2EEncryption.wrapFileKey(fileKey, sharedSecret, psk = null, aad = aadSender)

        // Unwrap with DIFFERENT aad must fail the AEAD integrity check
        val failed = runCatching {
            E2EEncryption.unwrapFileKey(wrapped, sharedSecret, psk = null, aad = aadAttacker)
        }
        assertFalse(failed.isSuccess, "Mismatched AAD MUST fail AEAD auth check")
    }
}
