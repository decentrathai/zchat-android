package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-1474 as a feature (OT213n B1c; owner W-123; FIX_PLAN REVISION 2): the `ZATT|v1|…` rumor that carries an
 * attachment's file key inside an authenticated NIP-17 message (never on chain, never the file itself).
 *
 * At 669cf19e1 there is no such message: ZATTMessage does not exist.
 *
 * Wanted (wire in [B1cPlanned]): build writes exactly the planned fields, parse reads them back (the 32-byte file key
 * byte for byte, a blurhash containing '|'), the text is no ZFILE and no other known control (HEAD's readers never
 * mistake it), carries no address and no wrapped key, and parse refuses, without throwing: another version, missing or
 * extra-short fields, a hash that is not 32 lowercase hex (no path), an unknown type, a bad or negative size, a declared
 * size above the 25 MiB download cap, a url that is not https on an upload host, a key that is not 32 bytes of base64, a
 * text over 4,096 characters. Every value is synthetic.
 */
class ZattWireRoundTripTest {
    @Test
    fun `build writes the planned wire text`() {
        assertEquals(B1cPlanned.wire(), B1cPlanned.build())
        assertEquals(
            B1cPlanned.wire(typeCode = ZFILEType.M4A.code, size = 12_345L, viewOnce = true, blurhash = "LEHV6n"),
            B1cPlanned.build(type = ZFILEType.M4A, size = 12_345L, viewOnce = true, blurhash = "LEHV6n"),
        )
        assertTrue(B1cPlanned.build().startsWith("ZATT|v1|"))
        val prefix =
            B1cPlanned
                .klass(B1cPlanned.ZATT)
                .getField("PREFIX")
                .get(null)
        assertEquals("ZATT|v1|", prefix)
    }

    @Test
    fun `parse reads back every field, the key byte for byte, and a blurhash with a bar`() {
        for (type in ZFILEType.entries) {
            for (viewOnce in listOf(false, true)) {
                val blurhash = "LEHV6n|WB2yk8pyo0adR*.7kCMdnj"
                val text = B1cPlanned.build(type = type, size = 77_777L, blurhash = blurhash, viewOnce = viewOnce)
                val parsed = assertNotNull(B1cPlanned.parse(text), "parse refused its own $type text")
                assertEquals(B1cPlanned.HASH, B1cPlanned.prop(parsed, "hash"))
                assertEquals(type, B1cPlanned.prop(parsed, "type"))
                assertEquals(77_777L, B1cPlanned.prop(parsed, "size"))
                assertEquals(B1cPlanned.URL, B1cPlanned.prop(parsed, "url"))
                assertContentEquals(B1cPlanned.FILE_KEY, B1cPlanned.prop(parsed, "fileKey") as ByteArray)
                assertEquals(blurhash, B1cPlanned.prop(parsed, "blurhash"))
                assertEquals(viewOnce, B1cPlanned.prop(parsed, "viewOnce"))
            }
        }
    }

    @Test
    fun `the text is no ZFILE and no known control, and carries no address`() {
        val text = B1cPlanned.build()
        assertFalse(ZFILEMessage.isFileMessage(text), "a ZATT reads as a ZFILE (its key is no wrapped key)")
        assertNull(ZFILEMessage.parse(text))
        assertFalse(ZBootMessage.isBootMessage(text))
        assertFalse(ZPROFMessage.isProfileMessage(text))
        assertFalse(ZMSGGroupProtocol.isGroupMessage(text))
        for (control in listOf(
            ZMSGProtocol::isStatus,
            ZMSGProtocol::isReadReceipt,
            ZMSGProtocol::isReaction,
            ZMSGProtocol::isPaymentRequest,
            ZMSGProtocol::isDisappearSetting,
            ZMSGProtocol::isModeChange,
        )) {
            assertFalse(control(text), "a ZATT reads as another control")
        }
        assertEquals(null, ZMSGProtocol.parseV4Init(text))
        assertEquals(9, text.split("|").size, "the planned nine fields, nothing else (no address, no sender field)")
    }

    @Test
    fun `parse refuses malformed texts without throwing`() {
        val good = B1cPlanned.wire()
        val shortKey =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(16) { 1 })
        val bad =
            mapOf(
                "another version" to good.replace("ZATT|v1|", "ZATT|v2|"),
                "no version" to good.replace("ZATT|v1|", "ZATT|"),
                "a ZFILE" to good.replace("ZATT|v1|", "ZFILE|"),
                "missing fields" to "ZATT|v1|${B1cPlanned.HASH}|j|4096",
                "empty" to "ZATT|v1|",
                "short hash" to B1cPlanned.wire(hash = "abab"),
                "upper-case hash" to B1cPlanned.wire(hash = B1cPlanned.HASH.uppercase()),
                "path in the hash" to B1cPlanned.wire(hash = "../../../../../../../../etc/pass"),
                "unknown type" to B1cPlanned.wire(typeCode = "x"),
                "size not a number" to B1cPlanned.wire().replace("|4096|", "|four|"),
                "negative size" to B1cPlanned.wire(size = -1L),
                "size above the download cap" to B1cPlanned.wire(size = 25L * 1024 * 1024 + 1),
                "viewOnce not 0 or 1" to B1cPlanned.wire().replace("|4096|0|", "|4096|7|"),
                "http url" to B1cPlanned.wire(url = "http://nostr.download/synthetic-zatt-blob"),
                "url off the upload hosts" to B1cPlanned.wire(url = "https://synthetic.example/blob"),
                "local url" to B1cPlanned.wire(url = "https://127.0.0.1/blob"),
                "key not base64" to B1cPlanned.wire(keyB64 = "!!not-base64!!"),
                "key of 16 bytes" to B1cPlanned.wire(keyB64 = shortKey),
                "empty key" to B1cPlanned.wire(keyB64 = ""),
                "oversize text" to B1cPlanned.wire(blurhash = "L".repeat(4_200)),
            )
        assertNotNull(B1cPlanned.parse(good), "control: the planned wire text parses")
        for ((why, text) in bad) {
            val parsed = runCatching { B1cPlanned.parse(text) }
            assertTrue(parsed.isSuccess, "parse threw on $why: ${parsed.exceptionOrNull()}")
            assertNull(parsed.getOrNull(), "parse accepted $why")
        }
    }

    @Test
    fun `the cap is the download cap - 25 MiB exactly still parses`() {
        assertNotNull(B1cPlanned.parse(B1cPlanned.wire(size = 25L * 1024 * 1024)))
    }
}
