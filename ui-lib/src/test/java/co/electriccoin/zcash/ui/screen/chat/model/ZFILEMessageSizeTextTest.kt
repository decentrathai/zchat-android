package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.nostr.FileUploadManager
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.filesharing.FileIntegrityCheck
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 10b R10B-3 (F-R9C-7, two-phone checks 2026-09-25) — a file's row shows its encrypted size.
 *
 * ZFILEMessage.size is the ciphertext's size by protocol (FileIntegrityCheck.verifySize checks the download against
 * it), and the sender fills it with encrypted.size. sizeText, so displayText and the file rows on both phones, showed
 * that size: encryptFile adds a 12-byte IV and a 16-byte GCM tag, and every pair in the logs differs by exactly 28 bytes
 * (608 for a 580 B file, 1148/1120, 1228/1200, 308900/308872, 7340184/7340156).
 *
 * Wanted: the rows show the file's own size (the wire size less the 28 bytes, never below 0); the wire field and the
 * integrity check are unchanged.
 */
class ZFILEMessageSizeTextTest {
    private var saved: Locale = Locale.getDefault()

    @Before
    fun englishNumbers() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restore() {
        Locale.setDefault(saved)
    }

    private fun zfile(
        size: Long,
        type: ZFILEType = ZFILEType.TXT,
        hash: String = "0123456789abcdef0123456789abcdef",
    ) = ZFILEMessage(
        hash = hash,
        type = type,
        size = size,
        url = "https://blossom.primal.net/x",
        wrappedKey = "k==",
        blurhash = "",
    )

    @Test
    fun a_580_byte_file_reads_580_B_not_its_608_byte_ciphertext() {
        assertEquals("580 B", zfile(608).sizeText)
        assertEquals("Document (580 B)", zfile(608).displayText)
    }

    @Test
    fun the_logged_pairs_read_their_plain_sizes() {
        assertEquals("1.1 KB", zfile(1148).sizeText) // 1,120 B
        assertEquals("301.6 KB", zfile(308_900).sizeText) // 308,872 B
        assertEquals("7.0 MB", zfile(7_340_184).sizeText) // 7,340,156 B
    }

    @Test
    fun a_size_below_the_cipher_overhead_reads_0_B_never_negative() {
        assertEquals("0 B", zfile(20).sizeText)
        assertEquals("0 B", zfile(0).sizeText)
    }

    @Test
    fun the_overhead_is_what_encryptFile_adds_and_the_integrity_check_still_uses_the_wire_size() {
        val plain = ByteArray(580) { it.toByte() }
        val cipher = E2EEncryption.encryptFile(plain, ByteArray(32) { 7 })
        assertEquals(608, cipher.size, "fixture: encryptFile adds a 12-byte IV and a 16-byte tag")
        val hash = FileUploadManager.sha256Hex(cipher).take(FileIntegrityCheck.EXPECTED_HASH_LEN)
        val message = zfile(cipher.size.toLong(), ZFILEType.PDF, hash)
        assertEquals(608L, message.size, "the wire field stays the ciphertext's size")
        assertTrue(FileIntegrityCheck.verify(cipher, message.hash, message.size), "the integrity check must still pass")
        assertEquals("${plain.size} B", message.sizeText)
        assertEquals(message, ZFILEMessage.parse(message.serialize()), "the wire form is unchanged")
    }
}
