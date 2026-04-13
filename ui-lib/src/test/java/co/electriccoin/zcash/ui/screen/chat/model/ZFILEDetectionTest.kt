package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ZFILE message detection and extraction from chat message content.
 * Phase 2 file sharing: the app must recognize ZFILE-prefixed content in message
 * payloads and extract structured file metadata for display.
 *
 * A ZFILE message is embedded as the "message content" field of a regular ZMSG v4
 * memo. After ZMSG parsing extracts the content, the content itself starts with
 * "ZFILE|" and contains file metadata (hash, type, size, url, wrapped key, blurhash).
 */
class ZFILEDetectionTest {

    @Test
    fun isFileMessage_detects_ZFILE_prefix() {
        assertTrue(ZFILEMessage.isFileMessage("ZFILE|abc|j|1024|url|key|blur"))
        assertFalse(ZFILEMessage.isFileMessage("hello world"))
        assertFalse(ZFILEMessage.isFileMessage(""))
        assertFalse(ZFILEMessage.isFileMessage("ZFILE")) // no pipe
        assertFalse(ZFILEMessage.isFileMessage("zfile|lower")) // case-sensitive
    }

    @Test
    fun extractFileMessage_parses_valid_ZFILE_from_content() {
        val content = "ZFILE|a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6|j|245760|nostr.build/abc|wrappedKey==|LKO2"
        val msg = ZFILEMessage.parse(content)
        assertNotNull(msg)
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", msg.hash)
        assertEquals(ZFILEType.JPEG, msg.type)
        assertEquals(245760L, msg.size)
        assertTrue(msg.isImage)
    }

    @Test
    fun extractFileMessage_returns_null_for_non_ZFILE_content() {
        assertNull(ZFILEMessage.parse("hello world"))
        assertNull(ZFILEMessage.parse("E2E1:00:0000000000000000:base64data"))
    }

    @Test
    fun file_display_text_for_image_types() {
        val jpeg = ZFILEMessage(
            hash = "abc", type = ZFILEType.JPEG, size = 102400,
            url = "nostr.build/x", wrappedKey = "k==", blurhash = "L"
        )
        assertTrue(jpeg.isImage)
        assertEquals("Image (100.0 KB)", jpeg.displayText)

        val pdf = ZFILEMessage(
            hash = "abc", type = ZFILEType.PDF, size = 5242880,
            url = "nostr.build/y", wrappedKey = "k==", blurhash = ""
        )
        assertFalse(pdf.isImage)
        assertEquals("Document (5.0 MB)", pdf.displayText)
    }
}
