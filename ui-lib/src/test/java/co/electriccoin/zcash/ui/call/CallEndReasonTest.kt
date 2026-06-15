package co.electriccoin.zcash.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HANGUP envelope carries [CallEndReason.wireString]; a remote peer parses it back as
 * [CallEndReason.RemoteHangup]. These strings are therefore a wire contract — pin them so
 * a rename can't silently change the protocol.
 */
class CallEndReasonTest {

    @Test
    fun `wire strings are stable and lowercase_snake`() {
        assertEquals("user_ended", CallEndReason.UserEnded.wireString)
        assertEquals("declined", CallEndReason.Declined.wireString)
        assertEquals("busy", CallEndReason.Busy.wireString)
        assertEquals("permission_denied", CallEndReason.PermissionDenied.wireString)
        assertEquals("ice_failed", CallEndReason.IceFailed.wireString)
        assertEquals("timeout", CallEndReason.Timeout.wireString)
        assertEquals("glare", CallEndReason.Glare.wireString)
        assertEquals("shutdown", CallEndReason.Shutdown.wireString)
        assertEquals("back_pressed", CallEndReason.BackPressed.wireString)
        assertEquals("setup_failed", CallEndReason.SetupFailed("x").wireString)
    }

    @Test
    fun `RemoteHangup passes the peer payload through verbatim`() {
        assertEquals("user_ended", CallEndReason.RemoteHangup("user_ended").wireString)
        assertEquals("anything", CallEndReason.RemoteHangup("anything").wireString)
    }

    @Test
    fun `every reason has a non-blank display label`() {
        val reasons = listOf(
            CallEndReason.UserEnded, CallEndReason.Declined, CallEndReason.Busy,
            CallEndReason.PermissionDenied, CallEndReason.IceFailed, CallEndReason.Timeout,
            CallEndReason.Glare, CallEndReason.Shutdown, CallEndReason.BackPressed,
            CallEndReason.RemoteHangup("x"), CallEndReason.SetupFailed("y"),
        )
        reasons.forEach { assertTrue("${it.wireString} has a label", it.displayLabel.isNotBlank()) }
    }

    @Test
    fun `receiver action constants are namespaced`() {
        assertTrue(CallActionReceiver.ACTION_ACCEPT.startsWith("co.electriccoin.zcash.call."))
        assertTrue(CallActionReceiver.ACTION_DECLINE.startsWith("co.electriccoin.zcash.call."))
    }
}
