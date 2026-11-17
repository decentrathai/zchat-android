package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy.LegacyJumpStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R4-3 (2), round 3 report 4 item 9: A SEND MUST NOT WAIT FOR EVER.
 *
 * While a contact's ratchet carries an older conversation id's unsettled send counters, every send to that
 * contact is refused. The pass end only settles them on a pass that read every one of our own messages
 * (`isOwnHistoryPassComplete`: the chain scan finished, the pass was stable, and no own memo failed to read).
 * One own memo this device can never read holds all three down for good, so the contact could never be
 * written to again — while the user is told "try again in a moment", and on two of the four send paths that
 * they should "finish the encrypted key exchange", which is not what is wrong.
 *
 * The rules pinned here:
 *  - the wait is bounded: after [RestoreKexPolicy.MAX_LEGACY_SETTLEMENT_WAITS] pass-ends whose chain scan had
 *    FINISHED, the counters are adopted — kept for good, so no counter is ever reused — and sends run again;
 *  - a wallet still scanning its chain is not on that budget at all;
 *  - what the user is told while the send waits is the truth, not a missing key exchange.
 */
class LegacySettlementBoundedWaitTest {
    @Test
    fun a_send_stops_waiting_once_the_budget_of_finished_scans_is_used_up() {
        val budget = RestoreKexPolicy.MAX_LEGACY_SETTLEMENT_WAITS
        assertTrue(budget > 0, "a bound of zero would never let the pass end settle anything")

        for (waited in 0 until budget) {
            assertEquals(
                LegacyJumpStep.WAIT,
                RestoreKexPolicy.legacyJumpStep(
                    ownHistoryPassComplete = false,
                    ownEnvelopeHeaderUnknown = false,
                    waitedCompletedScans = waited,
                ),
                "waited=$waited: inside the budget the pass end still waits for a complete pass",
            )
        }

        assertEquals(
            LegacyJumpStep.ADOPT,
            RestoreKexPolicy.legacyJumpStep(
                ownHistoryPassComplete = false,
                ownEnvelopeHeaderUnknown = false,
                waitedCompletedScans = budget,
            ),
            "the budget is used up: keep the counters (never reused) instead of refusing every send for ever",
        )
    }

    @Test
    fun the_bounded_wait_never_settles_on_incomplete_evidence() {
        for (unknown in listOf(false, true)) {
            assertNotEquals(
                LegacyJumpStep.SETTLE,
                RestoreKexPolicy.legacyJumpStep(
                    ownHistoryPassComplete = false,
                    ownEnvelopeHeaderUnknown = unknown,
                    waitedCompletedScans = RestoreKexPolicy.MAX_LEGACY_SETTLEMENT_WAITS * 10,
                ),
                "unknown=$unknown: running out of patience must never settle counters from unread history",
            )
        }
    }

    @Test
    fun a_send_that_waits_is_told_what_is_actually_happening() {
        val keyExchange = "Finish the encrypted key exchange first, then set the timer."

        assertEquals(
            keyExchange,
            RestoreKexPolicy.sendRefusedReason(awaitingLegacySettlement = false, otherwise = keyExchange),
            "every other refusal on that path keeps its own reason",
        )

        val held = RestoreKexPolicy.sendRefusedReason(awaitingLegacySettlement = true, otherwise = keyExchange)
        assertNotEquals(keyExchange, held, "the keys are there; the history is what the send is waiting for")
        assertEquals(RestoreKexPolicy.STILL_READING_HISTORY, held)
        assertTrue(held.contains("history"), "the user is told what is happening: $held")
        assertTrue(held.contains("nothing was paid"), "the user is told no money moved: $held")
    }

    @Test
    fun control_a_complete_pass_still_settles_and_an_unknown_header_still_adopts() {
        assertEquals(
            LegacyJumpStep.SETTLE,
            RestoreKexPolicy.legacyJumpStep(
                ownHistoryPassComplete = true,
                ownEnvelopeHeaderUnknown = false,
                waitedCompletedScans = 0,
            ),
        )
        assertEquals(
            LegacyJumpStep.ADOPT,
            RestoreKexPolicy.legacyJumpStep(
                ownHistoryPassComplete = true,
                ownEnvelopeHeaderUnknown = true,
                waitedCompletedScans = 0,
            ),
        )
    }
}
