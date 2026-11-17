package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-668 residual (reviewer, 12 Oct, train-T6a-2026/review/CLAUDE_REVIEW_CODEX_GROUPS_20261012.md) — a creator's re-invite
 * over the relays to a group this phone was removed from, handed over before the wallet's address is loaded, is lost
 * when it is too long for the kept slot (`MAX_KEPT_REINVITE_CHARS`).
 *
 * At 1081349f5: offerGroupReopen answers Undecided (no address); the existing-group branch tries to keep the relay
 * copy in the group's kept slot, which refuses a payload over the cap without an error; nothing is kept, nothing is
 * queued for the address load, and the relay event stays marked seen. When the address loads, the sweep finds no
 * kept copy and the relay never delivers the event again: no Join is offered and the member is told nothing.
 *
 * Wanted: an undecided relay re-invite that could not be kept waits for the address like a deferred new invite (the
 * in-memory queue the address load drains) and its relay event is taken out of the seen list, so a restart before the
 * address loads gets it again. Once the address loads Join is offered for it; nothing is sent or paid before Join (the
 * fixture's checks after every case).
 *
 * All addresses, keys, ids and group ids are synthetic.
 */
internal class OversizedRelayReinviteBeforeTheAddressLoadsStillOffersJoinTest : Zc668Fixture() {
    /** The creator's re-invite at key epoch 2, with a field that makes it longer than the kept slot takes. */
    private fun oversizedInvite(): String {
        val memo = invite()
        val head = memo.substringBefore("{")
        val json = org.json.JSONObject(memo.substring(head.length)).put("pad", "x".repeat(OVER_THE_CAP))
        return head + json.toString()
    }

    @Test
    fun `an oversized inactive relay invite handed over before the address loads is offered by the address load`() =
        runBlocking {
            inactive()
            val screen = screen(ready = false)
            screen.startLoad()
            assertNull("fixture must deliver before product loads the address", screen.address())
            receive(screen.vm, oversizedInvite(), I2)
            assertNull("fixture: the oversized invite cannot be in the kept slot", diskState().pendingReinvite)
            assertNull("fixture: Join cannot be offered before the address loads", diskJoin())

            screen.finishLoad()

            assertOffered(I2)
        }

    @Test
    fun `an oversized inactive relay invite that could not be kept is taken out of the seen list`() =
        runBlocking {
            inactive()
            val screen = screen(ready = false)
            screen.startLoad()
            receive(screen.vm, oversizedInvite(), I2)

            assertNull("fixture: the oversized invite cannot be in the kept slot", diskState().pendingReinvite)
            assertFalse(
                "an undecided relay invite with no kept copy stays marked seen: a restart before the address loads loses it",
                store.proxy.hasSeenNostrEvent(I2),
            )
            assertFalse("an undecided invite was recorded as used", I2 in diskState().usedInviteIds)
            screen.finishLoad()
            assertTrue("after the offer the relay event is marked seen again", store.proxy.hasSeenNostrEvent(I2))
        }

    private companion object {
        /** Above ChatViewModel.MAX_KEPT_REINVITE_CHARS (8,192). */
        const val OVER_THE_CAP = 9_000
    }
}
