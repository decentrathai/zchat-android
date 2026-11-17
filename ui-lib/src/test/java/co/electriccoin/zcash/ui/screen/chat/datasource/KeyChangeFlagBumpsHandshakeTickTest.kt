package co.electriccoin.zcash.ui.screen.chat.datasource

import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-300 (fix-all 2.13.0, train T5b) — the chat screen reads the key-changed banner (isE2EKeyChanged) while it draws, and
 * redraws on the shared handshake tick (e2eHandshakeTicks). A confirmed key-change reconnect now clears the flag on the
 * process send scope, after the price question has closed and the screen has redrawn, so the flag change itself must
 * move the tick: otherwise the banner stays up after Pay until something else redraws the chat.
 *
 * Must hold: clearing and setting the flag each move the tick. Uses the real ZchatPreferencesImpl over in-memory files
 * ([realPreferences]).
 */
class KeyChangeFlagBumpsHandshakeTickTest {
    @Test
    fun `clearing the key-change flag moves the handshake tick`() {
        val (impl, _) = realPreferences()
        impl.setE2EKeyChanged(PEER, true)
        val before = ticks(impl)

        impl.setE2EKeyChanged(PEER, false)

        assertEquals("the flag was not cleared", false, impl.isE2EKeyChanged(PEER))
        assertTrue("an open chat is not told to re-read its banner", ticks(impl) > before)
    }

    @Test
    fun `setting the key-change flag moves the handshake tick`() {
        val (impl, _) = realPreferences()
        val before = ticks(impl)

        impl.setE2EKeyChanged(PEER, true)

        assertEquals(true, impl.isE2EKeyChanged(PEER))
        assertTrue("an open chat is not told to show its banner", ticks(impl) > before)
    }

    /** The tick behind e2eHandshakeTicks ([realPreferences] fills the backing MutableStateFlow, not the public view of it). */
    @Suppress("UNCHECKED_CAST")
    private fun ticks(impl: ZchatPreferencesImpl): Long {
        val field = ZchatPreferencesImpl::class.java.getDeclaredField("_e2eHandshakeTicks").apply { isAccessible = true }
        return (field.get(impl) as StateFlow<Long>).value
    }

    private companion object {
        const val PEER = "usyntheticzc300flagtick0001"
    }
}
