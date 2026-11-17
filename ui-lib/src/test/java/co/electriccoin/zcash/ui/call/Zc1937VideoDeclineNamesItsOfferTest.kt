package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1937 (GPT cross-family review of 3fc413a7c): VIDEO_DECLINE names no OFFER. A late decline of an earlier upgrade
 * (timed out on the caller, then declined on the other phone) cancels a newer pending upgrade
 * (VoiceCallManager.onVideoDecline, CallRenegotiation.onRemoteDecline).
 *
 * Wanted: the decline carries a tag of the upgrade OFFER it answers (`CallRenegotiation.declinePayload(why, offerSdp)`,
 * a short hash of the OFFER's SDP, which both phones hold byte for byte); the caller applies a decline only when
 * `CallRenegotiation.declineMatchesOffer(payload, ownPendingOfferSdp)` (an untagged or other-offer decline is ignored),
 * and reads the reason with `CallRenegotiation.declineReason(payload)`.
 *
 * compat-2124: 2.12.4 never sends an in-call upgrade OFFER nor VIDEO_DECLINE, and its frozen parser rejects the
 * VIDEO_DECLINE type whatever the payload (Zc1904VideoUpgradeNeedsConsentTest `a 2 12 4 phone cannot parse
 * VIDEO_DECLINE` stays). No 2.13 build with the untagged decline was published.
 */
class Zc1937VideoDeclineNamesItsOfferTest {
    private val sdpA = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
    private val sdpB = sdpA.replace(" 2 IN ", " 3 IN ")

    private fun call(
        method: String,
        vararg args: Any?,
    ): Any? = CallSourceBlocks.objectCall(RENEG, method, *args)

    private fun payload(
        why: String,
        sdp: String,
    ): String = call("declinePayload", why, sdp) as String

    private fun matches(
        payload: String,
        sdp: String?,
    ): Boolean = call("declineMatchesOffer", payload, sdp) as Boolean

    @Test
    fun `a decline matches only the OFFER it answers`() {
        val declineA = payload(CallRenegotiation.DECLINE_TIMEOUT, sdpA)
        assertEquals(true, matches(declineA, sdpA), "ZC-1937: the decline of offer A ends offer A")
        assertEquals(false, matches(declineA, sdpB), "ZC-1937: a late decline of offer A must not end offer B")
        assertEquals(false, matches(declineA, null), "no own offer pending")
    }

    @Test
    fun `an untagged decline is ignored`() {
        assertEquals(false, matches(CallRenegotiation.DECLINE_BY_USER, sdpA), "ZC-1937: no tag, no match")
        assertEquals(false, matches(CallRenegotiation.DECLINE_TIMEOUT, sdpA), "ZC-1937: no tag, no match")
        assertEquals(false, matches("", sdpA))
    }

    @Test
    fun `the reason survives the tag and the payload stays short`() {
        val byUser = payload(CallRenegotiation.DECLINE_BY_USER, sdpA)
        assertEquals(CallRenegotiation.DECLINE_BY_USER, call("declineReason", byUser), "ZC-1937: declined by the user")
        assertEquals(
            CallRenegotiation.DECLINE_TIMEOUT,
            call("declineReason", payload(CallRenegotiation.DECLINE_TIMEOUT, sdpA)),
            "ZC-1937: the question timed out",
        )
        assertTrue(byUser.length <= 64, "ZC-1937: the tag is a short hash, not the SDP ($byUser)")
        assertTrue("|" !in byUser, "the payload has no envelope separator")
        val env = CallSignalEnvelope("1f".repeat(16), CallSignalType.VIDEO_DECLINE, byUser)
        assertEquals(env, CallSignalEnvelope.parse(env.serialize()), "the tagged decline round-trips")
    }

    @Test
    fun `the phones send and check the tag`() {
        val manager = ChatResourceText.source(MANAGER)
        val decline = CallSourceBlocks.body(manager, "fun declineHeldUpgrade(")
        assertTrue("CallRenegotiation.declinePayload(" in decline, "ZC-1937: the decline names the held OFFER")
        val onDecline = CallSourceBlocks.body(manager, "fun onVideoDecline(")
        assertTrue("CallRenegotiation.declineMatchesOffer(" in onDecline, "ZC-1937: the caller checks the tag")
        assertTrue("CallRenegotiation.declineReason(" in onDecline, "ZC-1937: the reason is read past the tag")
        assertTrue(
            "env.payload == CallRenegotiation.DECLINE_BY_USER" !in onDecline,
            "ZC-1937: the raw payload is no longer the bare reason",
        )
    }

    private companion object {
        const val RENEG = "co.electriccoin.zcash.ui.call.CallRenegotiation"
        const val MANAGER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/VoiceCallManager.kt"
    }
}
