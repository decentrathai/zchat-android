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

    // ---- malformed inputs must return null (NEVER throw): a bad wire is a decrypt failure, not a crash ----

    @Test
    fun parse_wrong_prefix_returns_null() {
        // A prefix that only LOOKS like E2E1: (extra char) must not be accepted.
        assertNull(CiphertextWireFormat.parse("E2E1X:00:0000000000000000:data"))
        assertNull(CiphertextWireFormat.parse("XE2E1:00:0000000000000000:data"))
    }

    @Test
    fun parse_out_of_range_direction_value_returns_null() {
        // "02" is valid 2-char hex but not a valid direction (only 00 / 01 exist). Must be rejected —
        // an unknown direction byte can't map to either chain.
        assertNull(CiphertextWireFormat.parse("E2E1:02:0000000000000000:aGVsbG8="))
        // "ff" likewise.
        assertNull(CiphertextWireFormat.parse("E2E1:ff:0000000000000000:aGVsbG8="))
    }

    @Test
    fun parse_wrong_length_dir_or_counter_hex_returns_null() {
        // direction hex must be EXACTLY 2 chars, counter hex EXACTLY 16.
        assertNull(CiphertextWireFormat.parse("E2E1:0:0000000000000000:aGVsbG8="))   // dir too short
        assertNull(CiphertextWireFormat.parse("E2E1:000:0000000000000000:aGVsbG8=")) // dir too long
        assertNull(CiphertextWireFormat.parse("E2E1:00:00000000000000:aGVsbG8="))    // counter too short
    }

    @Test
    fun parse_non_base64_body_returns_null_not_throw() {
        // A structurally-valid header with a non-Base64 body must return null (the parser catches the
        // IllegalArgumentException from the decoder) — it must NOT propagate an exception to the caller.
        val result = runCatching {
            CiphertextWireFormat.parse("E2E1:00:0000000000000000:not base64 !!!")
        }
        assertTrue(result.isSuccess, "malformed body must not throw")
        assertNull(result.getOrThrow())
    }

    @Test
    fun valid_wire_string_round_trips_all_fields() {
        // Pin the canonical E2E1:<dir>:<counter>:<b64> shape end-to-end for a non-trivial payload.
        val original = Ciphertext(
            direction = 0x01,
            counter = 0x0102030405060708L,
            bytes = ByteArray(48) { (it * 7).toByte() }, // GCM-sized-ish binary body incl. '+' '/' bytes
        )
        val wire = CiphertextWireFormat.serialize(original)
        // Header is exact: prefix + 2-hex dir + 16-hex big-endian counter. (Base64 has no ':', so the
        // body is unambiguously the final segment.)
        assertTrue(wire.startsWith("E2E1:01:0102030405060708:"), "unexpected header in: $wire")

        val parsed = CiphertextWireFormat.parse(wire)
        assertNotNull(parsed)
        assertEquals(original.direction, parsed.direction)
        assertEquals(original.counter, parsed.counter)
        assertContentEquals(original.bytes, parsed.bytes)
    }
}
