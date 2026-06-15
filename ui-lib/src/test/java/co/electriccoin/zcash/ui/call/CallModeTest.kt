package co.electriccoin.zcash.ui.call

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The caller announces audio-vs-video intent in the RING payload (which is otherwise
 * empty). The callee parses it before the OFFER arrives so the incoming-call UI can show
 * a video-answer affordance. This is a wire contract — pin the strings.
 */
class CallModeTest {

    @Test
    fun `wire strings are stable`() {
        assertEquals("audio", CallMode.AUDIO.wire)
        assertEquals("video", CallMode.VIDEO.wire)
    }

    @Test
    fun `round-trip through wire`() {
        for (m in CallMode.entries) {
            assertEquals(m, CallMode.fromWire(m.wire))
        }
    }

    @Test
    fun `unknown or empty wire defaults to AUDIO`() {
        // A legacy RING with empty payload (pre-video clients) must be treated as audio,
        // never crash and never silently upgrade to video.
        assertEquals(CallMode.AUDIO, CallMode.fromWire(""))
        assertEquals(CallMode.AUDIO, CallMode.fromWire("garbage"))
        assertEquals(CallMode.AUDIO, CallMode.fromWire("VIDEO")) // case-sensitive wire
    }

    @Test
    fun `isVideo helper`() {
        assertEquals(false, CallMode.AUDIO.isVideo)
        assertEquals(true, CallMode.VIDEO.isVideo)
    }
}
