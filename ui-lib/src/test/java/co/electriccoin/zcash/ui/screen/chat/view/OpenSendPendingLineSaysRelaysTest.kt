package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.PendingSendEstimate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1073 (device QA on HONOR, train/T7aP c7481873f) — a free Open-chat send said «Enviando por la red Zcash; suele
 * tardar unos minutos» while it waited.
 *
 * PendingSendEstimate.rowLabelRes gives the relay wording only to a "nostr-out-" row. A relay row keyed by its shared
 * rumor id ("nmsg-<rumorId>", #202; "nmsg-req-…" for a payment request sent over the relays) fell into the on-chain
 * branch: a Retry of a relay message resends under its "nmsg-" id (retryPendingId), and the optimistic bubble of
 * ZC-946 part 1 draws a settled "nmsg-" row as pending. Neither is on the Zcash network: an "nmsg-" row is a relay
 * row only. Wanted: a pending "nmsg-" row with no transaction says chat_pending_relay and has no block-time bar.
 */
class OpenSendPendingLineSaysRelaysTest {
    private fun id(name: String): Int = ChatResourceText.stringId(name) ?: fail("no string resource $name")

    @Test
    fun a_relay_row_keyed_by_its_rumor_id_says_it_is_going_over_the_relays() {
        val relay = id("chat_pending_relay")
        for (rowId in listOf("nmsg-0a1b2c", "nmsg-req-0a1b2c")) {
            for (elapsed in listOf(0L, 5L, 400L)) {
                val got = PendingSendEstimate.rowLabelRes(rowId, null, elapsed)
                assertTrue(
                    got != R.string.chat_pending_onchain && got != R.string.chat_pending_onchain_slow,
                    "$rowId after ${elapsed}s says it is on the Zcash network (${ChatResourceText.stringName(got)})",
                )
                assertEquals(relay, got, "$rowId after ${elapsed}s: ${ChatResourceText.stringName(got)}")
            }
        }
    }

    @Test
    fun a_relay_row_keyed_by_its_rumor_id_has_no_block_time_bar() {
        assertTrue(PendingSendEstimate.isOffChainRow("nmsg-0a1b2c", null), "an nmsg- relay row gets a block-time bar")
        assertTrue(PendingSendEstimate.isOffChainRow("nmsg-req-0a1b2c", null), "an nmsg-req- relay row gets a block-time bar")
    }

    @Test
    fun on_chain_rows_keep_the_zcash_network_wording() {
        assertEquals(R.string.chat_pending_onchain, PendingSendEstimate.rowLabelRes("pending_1", null, 5L))
        assertEquals(R.string.chat_pending_onchain_slow, PendingSendEstimate.rowLabelRes("pending_1", "tx1", 400L))
        assertFalse(PendingSendEstimate.isOffChainRow("pending_1", null), "an on-chain row lost its block-time bar")
    }
}
