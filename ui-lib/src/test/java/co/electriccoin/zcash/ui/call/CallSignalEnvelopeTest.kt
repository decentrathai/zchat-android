package co.electriccoin.zcash.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire format for WebRTC signalling delivered over NIP-17 DMs.
 *
 *     ZCALL|v1|<callId>|<type>|<payload>
 *
 *   callId  : 16-hex-char nonce minted by the caller. Lets both sides correlate
 *             multiple in-flight calls (rare but possible) and reject stray ICE
 *             candidates from earlier calls.
 *   type    : OFFER | ANSWER | ICE | HANGUP | RING
 *   payload : type-specific — SDP for OFFER/ANSWER, candidate string for ICE,
 *             reason code for HANGUP, empty for RING.
 *
 * RING is broadcast at the start of a call so the recipient app can wake the
 * incoming-call UI before the (much larger) OFFER arrives.
 */
class CallSignalEnvelopeTest {

    private val callId = "abcdef0123456789fedcba9876543210"
    private val sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"
    private val ice = "candidate:842163049 1 udp 1677729535 192.0.2.1 56112 typ srflx raddr 0.0.0.0 rport 0"

    @Test
    fun `OFFER serialize then parse round-trip`() {
        val original = CallSignalEnvelope(callId, CallSignalType.OFFER, sdp)
        val parsed = CallSignalEnvelope.parse(original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `ANSWER round-trip`() {
        val original = CallSignalEnvelope(callId, CallSignalType.ANSWER, sdp)
        assertEquals(original, CallSignalEnvelope.parse(original.serialize()))
    }

    @Test
    fun `ICE candidate round-trip`() {
        val original = CallSignalEnvelope(callId, CallSignalType.ICE, ice)
        assertEquals(original, CallSignalEnvelope.parse(original.serialize()))
    }

    @Test
    fun `RING has empty payload`() {
        val ring = CallSignalEnvelope(callId, CallSignalType.RING, "")
        val parsed = CallSignalEnvelope.parse(ring.serialize())
        assertEquals(ring, parsed)
        assertEquals("", parsed?.payload)
    }

    @Test
    fun `HANGUP carries a reason string`() {
        val hangup = CallSignalEnvelope(callId, CallSignalType.HANGUP, "user_ended")
        assertEquals(hangup, CallSignalEnvelope.parse(hangup.serialize()))
    }

    @Test
    fun `isSignal detects the prefix`() {
        assertTrue(CallSignalEnvelope.isSignal("ZCALL|v1|whatever"))
        assertTrue(!CallSignalEnvelope.isSignal("ZMSG|v4|x"))
        assertTrue(!CallSignalEnvelope.isSignal(""))
    }

    @Test
    fun `bad type returns null`() {
        assertNull(CallSignalEnvelope.parse("ZCALL|v1|$callId|HAXOR|x"))
    }

    @Test
    fun `wrong version returns null`() {
        assertNull(CallSignalEnvelope.parse("ZCALL|v9|$callId|OFFER|x"))
    }

    @Test
    fun `too-short callId returns null`() {
        assertNull(CallSignalEnvelope.parse("ZCALL|v1|short|OFFER|x"))
    }

    @Test
    fun `payload may contain pipes (do not split it)`() {
        // SDP contains `\r\n` lines but not pipes normally; ICE candidates can contain
        // arbitrary text. The parser must treat everything after the 4th pipe as the
        // payload (raw, no splitting).
        val payload = "weird|payload|with|many|pipes"
        val env = CallSignalEnvelope(callId, CallSignalType.HANGUP, payload)
        val parsed = CallSignalEnvelope.parse(env.serialize())
        assertEquals(payload, parsed?.payload)
    }

    @Test
    fun `callId generator produces 32 lowercase hex chars`() {
        val id = CallSignalEnvelope.newCallId()
        assertEquals(32, id.length)
        assertTrue("only lowercase hex", id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `uppercase callId is rejected by parse and by ctor`() {
        val upper = "ABCDEF0123456789FEDCBA9876543210"
        assertNull(CallSignalEnvelope.parse("ZCALL|v1|$upper|RING|"))
        var threw = false
        try { CallSignalEnvelope(upper, CallSignalType.RING, "") } catch (_: IllegalArgumentException) { threw = true }
        assertTrue("ctor must reject uppercase callId", threw)
    }
}
