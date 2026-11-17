package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-extra (a) (fix-all 2.13.0, train T2; carry-over from train T1b): a "ZBOOT quoted" allowance left by an earlier
 * quote that was refused can still pay a ZBOOT later, with no new question.
 *
 * The allowance ("zbootquoted:<peer>" in the conversation-mode file) is written when the user confirms a first message
 * whose question counted our on-chain ZBOOT, and a Shielded chat's ZBOOT may later be paid against it. Before T1b-1 it
 * was also written when that question was confirmed on a Keystone account and the send was then refused ("nothing was
 * sent or paid"), and nothing ever removed it: the HONOR debug app still holds one from the T1 L11 quote. T1b-1 stops new
 * ones (the Keystone check comes first; a refusal drops the allowance), but an allowance an earlier build left is still
 * read as the user's answer. The key does not exist in 2.12.4 or earlier, so every such entry was written by a 2.13.0
 * test build.
 *
 * Must hold: an allowance stored by an earlier build is not an allowance (nothing is paid against it; the ZBOOT waits for
 * its free relay delivery, as for any unquoted ZBOOT), and it is removed once read; an allowance this build records
 * stands until it is used. Uses the real ZchatPreferencesImpl over in-memory files ([realPreferences]).
 */
class EarlierZbootAllowanceIsVoidTest {
    @Test
    fun `an allowance an earlier build left is not an allowance and is removed`() {
        val (impl, files) = realPreferences()
        val mode = files.getValue("modePrefs")
        mode
            .edit()
            .putBoolean(LEGACY_KEY, true)
            .commit()

        assertFalse(
            "a ZBOOT allowance stored by an earlier build (a refused T1 L11 quote) still counts as the user's answer",
            impl.isZbootQuoted(PEER),
        )
        assertFalse("the earlier build's allowance must be removed once read", mode.contains(LEGACY_KEY))
    }

    @Test
    fun `an allowance this build records stands until it is used`() {
        val (impl, _) = realPreferences()

        assertTrue(impl.setZbootQuoted(PEER, true))
        assertTrue("a confirmed question's allowance must stand", impl.isZbootQuoted(PEER))
        assertTrue(impl.setZbootQuoted(PEER, false))
        assertFalse("a used allowance must be gone", impl.isZbootQuoted(PEER))
    }

    @Test
    fun `recording a new allowance also removes one an earlier build left`() {
        val (impl, files) = realPreferences()
        val mode = files.getValue("modePrefs")
        mode
            .edit()
            .putBoolean(LEGACY_KEY, true)
            .commit()

        assertTrue(impl.setZbootQuoted(PEER, true))
        assertTrue(impl.setZbootQuoted(PEER, false))

        assertFalse("a used allowance must leave no earlier copy that reads as an answer", impl.isZbootQuoted(PEER))
        assertFalse(mode.contains(LEGACY_KEY))
    }

    private companion object {
        const val PEER = "usynthetict2extrazbootpeer01"
        const val LEGACY_KEY = "zbootquoted:$PEER"
    }
}
