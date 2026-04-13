package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZFILEMessageTest {

    @Test
    fun serialize_produces_valid_ZFILE_string_under_300_bytes() {
        val msg = ZFILEMessage(
            hash = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            type = ZFILEType.JPEG,
            size = 245760,
            url = "nostr.build/abc123def456",
            wrappedKey = "kE9xY2base64keyWrapDataHere1234567890ABCDEF1234567890abcdef12345678901234==",
            blurhash = "LKO2?U%2"
        )
        val serialized = msg.serialize()
        assertTrue(serialized.startsWith("ZFILE|"))
        assertTrue(serialized.contains("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"))
        assertTrue(serialized.contains("j"))
        assertTrue(serialized.length < 300, "Serialized length ${serialized.length} exceeds 300")
    }

    @Test
    fun parse_valid_ZFILE_string() {
        val raw = "ZFILE|a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6|j|245760|nostr.build/abc123|kE9xbase64key==|LKO2?U%2"
        val msg = ZFILEMessage.parse(raw)
        assertNotNull(msg)
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", msg.hash)
        assertEquals(ZFILEType.JPEG, msg.type)
        assertEquals(245760L, msg.size)
        assertEquals("nostr.build/abc123", msg.url)
        assertEquals("kE9xbase64key==", msg.wrappedKey)
        assertEquals("LKO2?U%2", msg.blurhash)
    }

    @Test
    fun parse_invalid_string_returns_null() {
        assertNull(ZFILEMessage.parse("not a zfile"))
        assertNull(ZFILEMessage.parse("ZFILE|too|few"))
        assertNull(ZFILEMessage.parse(""))
        assertNull(ZFILEMessage.parse("ZFILE|hash|INVALID_TYPE|123|url|key|blur"))
    }

    @Test
    fun serialize_then_parse_roundtrip() {
        val original = ZFILEMessage(
            hash = "deadbeef12345678abcdef0123456789",
            type = ZFILEType.PDF,
            size = 1048576,
            url = "blossom.band/xyz789",
            wrappedKey = "wrappedKeyBase64DataHere==",
            blurhash = "L5H2EC=0"
        )
        val parsed = ZFILEMessage.parse(original.serialize())
        assertNotNull(parsed)
        assertEquals(original.hash, parsed.hash)
        assertEquals(original.type, parsed.type)
        assertEquals(original.size, parsed.size)
        assertEquals(original.url, parsed.url)
        assertEquals(original.wrappedKey, parsed.wrappedKey)
    }

    @Test
    fun all_file_types_have_correct_codes() {
        assertEquals("j", ZFILEType.JPEG.code)
        assertEquals("p", ZFILEType.PNG.code)
        assertEquals("g", ZFILEType.GIF.code)
        assertEquals("w", ZFILEType.WEBP.code)
        assertEquals("d", ZFILEType.PDF.code)
        assertEquals("z", ZFILEType.ZIP.code)
        assertEquals("t", ZFILEType.TXT.code)
    }

    @Test
    fun sha256_hash_is_32_hex_chars() {
        val msg = ZFILEMessage(
            hash = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            type = ZFILEType.JPEG,
            size = 100,
            url = "test.com/f",
            wrappedKey = "key==",
            blurhash = "LKAB"
        )
        assertEquals(32, msg.hash.length)
    }
}
