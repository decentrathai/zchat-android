package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZBootMessageTest {

    private val validPubkey = "a".repeat(64)
    private val validRelay = "wss://relay.damus.io"
    private val validSig = "c2lnbmF0dXJl" // base64 placeholder

    @Test
    fun `serialize then parse round-trip`() {
        val original = ZBootMessage("ABC12345", validPubkey, validRelay, validSig)
        val parsed = ZBootMessage.parse(original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize emits signed v2 wire format`() {
        val msg = ZBootMessage("ABC12345", validPubkey, validRelay, validSig)
        assertEquals("ZBOOT|v2|ABC12345|$validPubkey|$validRelay|$validSig", msg.serialize())
    }

    @Test
    fun `signedData covers convId pubkey relay only (not the signature)`() {
        val msg = ZBootMessage("ABC12345", validPubkey, validRelay, validSig)
        assertEquals("ABC12345|$validPubkey|$validRelay", msg.signedData())
        assertEquals(msg.signedData(), ZBootMessage.signedDataFor("ABC12345", validPubkey, validRelay))
    }

    @Test
    fun `unsigned v1 form is rejected (downgrade defense)`() {
        // The legacy unauthenticated format must never parse — accepting it would reopen the MITM hole.
        assertNull(ZBootMessage.parse("ZBOOT|v1|ABC12345|$validPubkey|$validRelay"))
    }

    @Test
    fun `v2 without a signature is rejected`() {
        assertNull(ZBootMessage.parse("ZBOOT|v2|ABC12345|$validPubkey|$validRelay"))
        assertNull(ZBootMessage.parse("ZBOOT|v2|ABC12345|$validPubkey|$validRelay|"))
    }

    @Test
    fun `non-boot prefix returns null`() {
        assertNull(ZBootMessage.parse("ZMSG|v4|ABC12345|INIT|abc|hi"))
    }

    @Test
    fun `bad pubkey length is rejected`() {
        assertNull(ZBootMessage.parse("ZBOOT|v2|ABC12345|deadbeef|$validRelay|$validSig"))
    }

    @Test
    fun `non-hex pubkey is rejected`() {
        val pubkey = "G".repeat(64) // not hex
        assertNull(ZBootMessage.parse("ZBOOT|v2|ABC12345|$pubkey|$validRelay|$validSig"))
    }

    @Test
    fun `wrong convID length is rejected`() {
        assertNull(ZBootMessage.parse("ZBOOT|v2|SHORT|$validPubkey|$validRelay|$validSig"))
    }

    @Test
    fun `http relay (not wss) is rejected`() {
        assertNull(ZBootMessage.parse("ZBOOT|v2|ABC12345|$validPubkey|http://relay.example.com|$validSig"))
    }

    @Test
    fun `serialized size is well under 512 byte memo cap`() {
        val sample = ZBootMessage(
            convId = "12345678",
            senderNostrPubkeyHex = validPubkey,
            relayUrl = "wss://" + "x".repeat(60),
            signature = "s".repeat(96),
        ).serialize()
        assert(sample.length < 300) { "ZBOOT $sample is ${sample.length} bytes" }
    }

    @Test
    fun `isBootMessage detects any ZBOOT prefix incl legacy`() {
        assert(ZBootMessage.isBootMessage("ZBOOT|v2|whatever"))
        assert(ZBootMessage.isBootMessage("ZBOOT|v1|whatever")) // detected (then rejected by parse)
        assert(!ZBootMessage.isBootMessage("ZMSG|v4|x"))
    }
}
