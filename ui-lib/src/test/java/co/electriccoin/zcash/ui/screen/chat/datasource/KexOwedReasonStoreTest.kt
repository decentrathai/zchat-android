package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-285 (fix-all 2.13.0, train T5b) — the reason an owed KEX is owed ("kexowedwhy:<peer>" in the conversation-mode file,
 * next to the "kexowed:<peer>" mark). A confirmed Reset encryption writes "reset" before it wipes anything; an absent
 * reason is a first message's KEX (R9-2, and every mark an earlier build or a 2.12.4 install left).
 *
 * Must hold: the reason is written and read back; it goes with the mark when the mark is cleared; it is never listed as
 * an owed contact; clearAll wipes it. Uses the real ZchatPreferencesImpl over in-memory files ([realPreferences]).
 */
class KexOwedReasonStoreTest {
    @Test
    fun `a reason is written, read back and cleared on its own`() {
        val (impl, _) = realPreferences()

        assertNull("no reason is a first message's KEX", impl.getKexOwedReason(PEER))
        assertTrue(impl.setKexOwedReason(PEER, ZchatPreferences.KEX_OWED_REASON_RESET))
        assertEquals(ZchatPreferences.KEX_OWED_REASON_RESET, impl.getKexOwedReason(PEER))
        assertTrue(impl.setKexOwedReason(PEER, null))
        assertNull(impl.getKexOwedReason(PEER))
    }

    @Test
    fun `the reason goes with the owed mark`() {
        val (impl, files) = realPreferences()

        assertTrue(impl.setKexOwedAccount(PEER, "synthetic-account-key"))
        assertTrue(impl.setKexOwedReason(PEER, ZchatPreferences.KEX_OWED_REASON_RESET))
        assertTrue(impl.setKexOwed(PEER, true))
        assertTrue(impl.setKexOwed(PEER, false))

        assertNull("the reason outlived its mark", impl.getKexOwedReason(PEER))
        assertNull("the account outlived its mark", impl.getKexOwedAccount(PEER))
        val left =
            files
                .getValue("modePrefs")
                .all
                .keys
                .filter { it.endsWith(PEER) }
        assertTrue("nothing of the owed KEX may stay: $left", left.isEmpty())
    }

    @Test
    fun `a reason is never listed as an owed contact`() {
        val (impl, _) = realPreferences()

        assertTrue(impl.setKexOwedReason(PEER, ZchatPreferences.KEX_OWED_REASON_RESET))
        assertTrue(impl.setKexOwedReason(OTHER, ZchatPreferences.KEX_OWED_REASON_RESET))
        assertTrue(impl.setKexOwed(OTHER, true))

        assertEquals("only the marks are owed contacts", setOf(OTHER), impl.getKexOwedPeers())
    }

    @Test
    fun `clearAll wipes the reason`() {
        val (impl, _) = realPreferences()

        assertTrue(impl.setKexOwedReason(PEER, ZchatPreferences.KEX_OWED_REASON_RESET))
        assertTrue(impl.setKexOwed(PEER, true))
        impl.clearAll()

        assertNull(impl.getKexOwedReason(PEER))
        assertTrue(impl.getKexOwedPeers().isEmpty())
    }

    private companion object {
        const val PEER = "usyntheticzc285reasonstore01"
        const val OTHER = "usyntheticzc285reasonstore02"
    }
}
