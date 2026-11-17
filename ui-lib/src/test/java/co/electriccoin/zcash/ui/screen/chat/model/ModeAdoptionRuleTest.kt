package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.model.ModeAdoption.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-1478 (W-122; FABLE_PLAN_CRITIQUE M2, M3) — the pure rule [ModeAdoption.decide] that handleModeControl follows.
 * The view-model behaviour (no prompt, no spend, the price on the first send) is PeerModeSwitchIsAdoptedOnBothPhonesTest;
 * that a free-mode switch is still adopted while the contact's new key waits is TimerOrModeChangeIsRefusedWhileANewKeyWaitsTest.
 */
class ModeAdoptionRuleTest {
    private val t0 = 1_790_000_000_000L

    private fun decide(
        current: ConversationMode,
        new: ConversationMode,
        ownSince: Long = 0L,
        peerSince: Long = t0,
        cap: Boolean? = true,
        relayKey: Boolean = true,
        keyChanged: Boolean = false,
    ): Decision = ModeAdoption.decide(current, new, ownSince, peerSince, cap, relayKey, keyChanged)

    @Test
    fun `a newer switch to Shielded from a 2_13 contact is adopted`() {
        assertEquals(Decision.Adopt(ConversationMode.VAULT), decide(ConversationMode.TUNNEL, ConversationMode.VAULT))
        assertEquals(Decision.Adopt(ConversationMode.VAULT), decide(ConversationMode.OPEN, ConversationMode.VAULT, relayKey = false))
    }

    @Test
    fun `Shielded is never adopted from a 2_12_4 contact or one with no capability on record (M2)`() {
        assertEquals(Decision.KeepNeeds213, decide(ConversationMode.TUNNEL, ConversationMode.VAULT, cap = false))
        assertEquals(Decision.KeepNeeds213, decide(ConversationMode.TUNNEL, ConversationMode.VAULT, cap = null))
        assertEquals(Decision.KeepNeeds213, decide(ConversationMode.OPEN, ConversationMode.VAULT, cap = false))
    }

    @Test
    fun `a newer switch to a free mode is adopted while we hold the contact's relay key, whatever version they run`() {
        for (cap in listOf(true, false, null)) {
            assertEquals(Decision.Adopt(ConversationMode.TUNNEL), decide(ConversationMode.VAULT, ConversationMode.TUNNEL, cap = cap))
            assertEquals(Decision.Adopt(ConversationMode.OPEN), decide(ConversationMode.TUNNEL, ConversationMode.OPEN, cap = cap))
        }
        assertEquals(Decision.KeepUnchanged, decide(ConversationMode.VAULT, ConversationMode.OPEN, relayKey = false))
    }

    @Test
    fun `while the contact's new key waits for the user Shielded is not adopted, a free mode still is`() {
        assertEquals(Decision.KeepUnchanged, decide(ConversationMode.TUNNEL, ConversationMode.VAULT, keyChanged = true))
        assertEquals(Decision.Adopt(ConversationMode.OPEN), decide(ConversationMode.TUNNEL, ConversationMode.OPEN, keyChanged = true))
    }

    @Test
    fun `a switch older than our own last switch is answered with ours, never adopted (M3)`() {
        val ours = t0 + 5_000L
        for (new in ConversationMode.entries.filter { it != ConversationMode.OPEN }) {
            assertEquals(Decision.SendOursAgain, decide(ConversationMode.OPEN, new, ownSince = ours, peerSince = t0))
        }
        // Also from a contact we could not follow anyway: ours goes out again so their phone converges.
        assertEquals(
            Decision.SendOursAgain,
            decide(ConversationMode.TUNNEL, ConversationMode.VAULT, ownSince = ours, peerSince = t0, cap = false),
        )
    }

    @Test
    fun `the same mode needs nothing`() {
        for (mode in ConversationMode.entries) {
            assertEquals(Decision.AlreadyInMode, decide(mode, mode, ownSince = t0 + 1, peerSince = t0))
        }
    }

    @Test
    fun `two crossed switches end in one mode on both phones (M3)`() {
        // (A's pick, B's pick) at (a, b); each phone holds its own pick and receives the other's.
        val cases =
            listOf(
                Triple(ConversationMode.TUNNEL to ConversationMode.VAULT, t0, t0 + 3_000L),
                Triple(ConversationMode.TUNNEL to ConversationMode.VAULT, t0 + 3_000L, t0),
                Triple(ConversationMode.OPEN to ConversationMode.TUNNEL, t0, t0),
                Triple(ConversationMode.VAULT to ConversationMode.OPEN, t0, t0),
                Triple(ConversationMode.OPEN to ConversationMode.VAULT, t0 + 1, t0),
            )
        for ((picks, a, b) in cases) {
            val (modeA, modeB) = picks
            val onA = decide(modeA, modeB, ownSince = a, peerSince = b)
            val onB = decide(modeB, modeA, ownSince = b, peerSince = a)
            val endA = (onA as? Decision.Adopt)?.mode ?: modeA
            val endB = (onB as? Decision.Adopt)?.mode ?: modeB
            assertEquals("crossed $picks at $a / $b: A=$onA B=$onB", endA, endB)
            assertTrue(
                "exactly one phone sends its mode again: A=$onA B=$onB",
                (onA == Decision.SendOursAgain) != (onB == Decision.SendOursAgain),
            )
        }
    }

    @Test
    fun `a chat moved to Shielded, or to a Tunnel still being set up, can pay, Open and a set-up Tunnel cannot (M1)`() {
        assertTrue(ModeAdoption.movesToPaid(ConversationMode.VAULT, exchangeComplete = true))
        assertTrue(ModeAdoption.movesToPaid(ConversationMode.VAULT, exchangeComplete = false))
        assertTrue(ModeAdoption.movesToPaid(ConversationMode.TUNNEL, exchangeComplete = false))
        assertFalse(ModeAdoption.movesToPaid(ConversationMode.TUNNEL, exchangeComplete = true))
        assertFalse(ModeAdoption.movesToPaid(ConversationMode.OPEN, exchangeComplete = false))
    }
}
