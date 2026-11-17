package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy.LegacyJumpStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R3-3 (1) — the scan's rules around an older conversation id's carried send counters (see
 * [co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet.settleLegacyJump]).
 *
 * - A send to the contact waits while they are unsettled: sent now, it would use the carried counter for good,
 *   which may be the old root's and beyond the contact's window.
 * - The pass end settles them only from a pass that read every one of our own messages; before that it waits.
 * - If one of our own messages could not be read to its header (or its conversation), nothing proves the counters
 *   are an older root's, so they are kept (never a reused counter) instead of waiting for ever.
 */
class LegacyJumpSettlementPolicyTest {
    @Test
    fun a_send_waits_while_an_older_ids_counters_are_unsettled() {
        assertFalse(RestoreKexPolicy.maySendBeforeLegacySettlement(setOf("CONVBBBB")))
        assertTrue(RestoreKexPolicy.maySendBeforeLegacySettlement(emptySet()))
    }

    @Test
    fun the_pass_end_settles_only_after_reading_all_of_our_own_messages() {
        for (unknown in listOf(false, true)) {
            assertEquals(
                LegacyJumpStep.WAIT,
                RestoreKexPolicy.legacyJumpStep(ownHistoryPassComplete = false, ownEnvelopeHeaderUnknown = unknown),
                "unknown=$unknown: the pass has not read all of our own messages",
            )
        }
        assertEquals(
            LegacyJumpStep.SETTLE,
            RestoreKexPolicy.legacyJumpStep(ownHistoryPassComplete = true, ownEnvelopeHeaderUnknown = false),
        )
    }

    @Test
    fun an_own_message_whose_header_is_unknown_keeps_the_counters() {
        assertEquals(
            LegacyJumpStep.ADOPT,
            RestoreKexPolicy.legacyJumpStep(ownHistoryPassComplete = true, ownEnvelopeHeaderUnknown = true),
        )
    }
}
