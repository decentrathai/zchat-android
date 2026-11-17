package co.electriccoin.zcash.ui.screen.chat.crypto

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R2-4 (b, c) — while a restored wallet has not read its own history, nothing may act as if it had.
 *
 * (b) An inbound KEX that is not mined yet was acted on as soon as the history scan reported complete, even on
 * a pass that did not yet see the whole transaction list and before restore recovery ran: it generated a new
 * key for the peer (our historical key can then never be reinstalled) and paid a KEXACK. It must wait for the
 * complete, stable pass, like a mined one; it is not dropped (the memo is left untouched, a later pass handles it).
 *
 * (c) A pass in which one of our own memos could not be read still counted as complete, so restore recovery
 * could install an OLDER own key (the latest one was in the unread memo) and a stored key is never replaced.
 */
class RestoreOwnHistoryPassTest {
    @Test
    fun an_unmined_inbound_kex_during_a_restore_waits_for_the_complete_stable_pass() {
        assertFalse(
            RestoreKexPolicy.mayActOnInboundHandshake(
                kexMined = false,
                historyScanComplete = true,
                passStable = false,
            ),
            "during a restore an unmined KEX must create no key and pay no KEXACK before the own history is read",
        )
        // ZC-941 (ZC-599, FIRST-CONTACT-SPEC 5.4 item 7): an unmined KEX is never acted on, not even on the complete,
        // stable pass; the pass that reads it mined handles it.
        assertFalse(
            RestoreKexPolicy.mayActOnInboundHandshake(
                kexMined = false,
                historyScanComplete = true,
                passStable = true,
            ),
            "an unmined KEX is acted on only once it is mined",
        )
        assertTrue(
            RestoreKexPolicy.mayActOnInboundHandshake(
                kexMined = true,
                historyScanComplete = true,
                passStable = true,
            ),
            "on the complete, stable pass (after recovery ran) a mined KEX is processed",
        )
    }

    @Test
    fun once_the_own_history_was_read_a_live_kex_still_waits_for_its_mining_and_a_historical_one_for_the_stable_pass() {
        // ZC-941: a live (unmined) KEX is no longer handled at once; it waits for its mining.
        assertFalse(
            RestoreKexPolicy.mayActOnInboundHandshake(
                kexMined = false,
                historyScanComplete = true,
                passStable = false,
            ),
        )
        assertFalse(
            RestoreKexPolicy.mayActOnInboundHandshake(
                kexMined = true,
                historyScanComplete = true,
                passStable = false,
            ),
        )
        assertFalse(
            RestoreKexPolicy.mayActOnInboundHandshake(
                kexMined = false,
                historyScanComplete = false,
                passStable = false,
            ),
        )
    }

    @Test
    fun a_pass_where_one_of_our_own_memos_could_not_be_read_is_not_complete() {
        assertFalse(
            RestoreKexPolicy.isOwnHistoryPassComplete(historyScanComplete = true, passStable = true, ownMemoReadFailures = 1),
            "the unread memo may hold our latest key: recovering from this pass could install an older one for good",
        )
        assertTrue(
            RestoreKexPolicy.isOwnHistoryPassComplete(historyScanComplete = true, passStable = true, ownMemoReadFailures = 0),
            "the next pass with the memo readable recovers the latest key",
        )
        assertFalse(RestoreKexPolicy.isOwnHistoryPassComplete(historyScanComplete = true, passStable = false, ownMemoReadFailures = 0))
        assertFalse(RestoreKexPolicy.isOwnHistoryPassComplete(historyScanComplete = false, passStable = true, ownMemoReadFailures = 0))
    }
}
