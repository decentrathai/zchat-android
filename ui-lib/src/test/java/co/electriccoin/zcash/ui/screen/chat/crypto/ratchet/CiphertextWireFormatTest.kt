package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the [CiphertextWireFormat] serializer/parser.
 *
 * Wire format: `E2E1:<dir_hex>:<counter_hex>:<ciphertext_base64>`
 * - dir_hex: 2 hex chars (00 or 01)
 * - counter_hex: 16 hex chars (u64 big-endian)
 * - ciphertext_base64: standard Base64 (may contain + / =)
 */
class CiphertextWireFormatTest {

    @Test
    fun serialize_then_parse_roundtrip() {
        val original = Ciphertext(
            direction = 0x00,
            counter = 42L,
            bytes = "hello encrypted world".toByteArray(),
        )
        val wire = CiphertextWireFormat.serialize(original)
        val parsed = CiphertextWireFormat.parse(wire)
        assertNotNull(parsed)
        assertEquals(original.direction, parsed.direction)
        assertEquals(original.counter, parsed.counter)
        assertContentEquals(original.bytes, parsed.bytes)
    }

    @Test
    fun serialize_format_matches_spec() {
        val ct = Ciphertext(
            direction = 0x01,
            counter = 7L,
            bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
        )
        val wire = CiphertextWireFormat.serialize(ct)
        assertTrue(wire.startsWith("E2E1:"), "Must start with E2E1: prefix, was: $wire")

        val parts = wire.removePrefix("E2E1:").split(":")
        assertEquals(3, parts.size, "Expected 3 colon-separated parts after prefix")
        assertEquals("01", parts[0]) // direction hex
        assertEquals("0000000000000007", parts[1]) // counter hex u64 BE
        assertTrue(parts[2].isNotEmpty()) // base64 body
    }

    @Test
    fun parse_legacy_E2E_prefix_returns_null() {
        // E2E: prefix belongs to the V2 (unratcheted) path. Not our responsibility.
        assertNull(CiphertextWireFormat.parse("E2E:abc123:def456"))
    }

    @Test
    fun parse_garbage_returns_null() {
        assertNull(CiphertextWireFormat.parse(""))
        assertNull(CiphertextWireFormat.parse("not a ciphertext"))
        assertNull(CiphertextWireFormat.parse("E2E1:"))
        assertNull(CiphertextWireFormat.parse("E2E1:00"))
        assertNull(CiphertextWireFormat.parse("E2E1:00:notahexcounter:data"))
        assertNull(CiphertextWireFormat.parse("E2E1:ZZ:0000000000000000:data"))
    }

    @Test
    fun direction_00_and_01_both_round_trip() {
        for (dir in listOf(0x00.toByte(), 0x01.toByte())) {
            val ct = Ciphertext(dir, 100L, "test".toByteArray())
            val parsed = CiphertextWireFormat.parse(CiphertextWireFormat.serialize(ct))
            assertNotNull(parsed, "Round-trip failed for direction=$dir")
            assertEquals(dir, parsed.direction)
        }
    }

    @Test
    fun large_counter_round_trips() {
        val ct = Ciphertext(0x00, Long.MAX_VALUE / 2, "big counter".toByteArray())
        val parsed = CiphertextWireFormat.parse(CiphertextWireFormat.serialize(ct))
        assertNotNull(parsed)
        assertEquals(Long.MAX_VALUE / 2, parsed.counter)
    }
}
