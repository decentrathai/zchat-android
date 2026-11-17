package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import co.electriccoin.zcash.ui.common.tex.TexFixture.SHIELD
import co.electriccoin.zcash.ui.common.tex.TexFixture.SHIELD_EXPIRY
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F3 (PLAN_V2 §2 F3 "Never pay twice"; owner 25 Sep: "shielded back without asking under a durable record so it is
 * paid only once"): the shield's record is written before its create (attempt and the tip, so a kill between the
 * create and the record still counts) and again through F2's onStored with the shield's ids and expiry before its
 * submit. While a shield is on record and not proven expired, no pass builds another: not the next pass, not a new
 * process, not a process killed between the create and the record.
 * Fails at 15e2b1c39: there is no TEX recovery.
 */
class TexAutoShieldOncePerFindTest {
    private fun foundWallet() =
        TexFixture.stuckWallet().apply {
            lookupAnswer = { true }
            shieldPlan = { TexFixture.planSpendingLeg1() }
        }

    @Test
    fun the_record_is_written_before_the_create_and_with_the_ids_before_the_submit() =
        runBlocking {
            val wallet = foundWallet()
            val store = TexApi.memoryStore()
            val key = TexApi.recordKey(TexFixture.EPHEMERAL)
            var atSubmit: Any? = null
            var afterStored: Any? = null
            wallet.atSubmit = { atSubmit = TexApi.storedRecord(store, key) }
            wallet.afterStored = { afterStored = TexApi.storedRecord(store, key) }
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store)

            TexApi.pass(monitor)

            val intent = assertNotNull(atSubmit, "no record before the shield was created")
            assertEquals(1, TexApi.prop(intent, "attempt"))
            assertEquals(wallet.tip, TexApi.prop(intent, "createStartTip"))
            assertEquals(emptyList<String>(), TexApi.prop(intent, "shieldTxIds"))
            val stored = assertNotNull(afterStored)
            assertEquals(listOf(SHIELD), TexApi.prop(stored, "shieldTxIds"))
            assertEquals(SHIELD_EXPIRY, TexApi.prop(stored, "shieldExpiry"))
            assertEquals(TexFixture.FOUND_ZAT, TexApi.prop(stored, "foundZat"))
            assertEquals(TexFixture.SHIELD_FEE_ZAT, TexApi.prop(stored, "feeZat"))
        }

    @Test
    fun a_second_pass_while_the_shield_is_pending_builds_nothing() =
        runBlocking {
            val wallet = foundWallet()
            var now = 0L
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, clock = { now })

            TexApi.pass(monitor)
            wallet.transactions = listOf(TexFixture.leg1(), TexFixture.leg2(), TexFixture.shieldTx())
            repeat(10) {
                now += 61L * 60 * 1000
                wallet.tip = wallet.tip!! + 1
                val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)
                assertEquals("SHIELDING", TexApi.state(status))
                assertFalse(TexApi.sendAgainAllowed(TexApi.state(status)))
            }

            assertEquals(1, wallet.submitted.size)
            assertEquals(1, wallet.proposeCount)
            assertEquals(1, wallet.lookups.size, "no lookup while the shield on record is pending")
        }

    @Test
    fun a_new_process_with_the_record_builds_nothing_while_the_shield_may_still_be_mined() =
        runBlocking {
            val store = TexApi.memoryStore()
            val first = foundWallet()
            TexApi.pass(TexApi.monitor(first, FakeTexTor().apply { on = true }, idle = { true }, store = store))
            assertEquals(1, first.submitted.size)

            // The app is killed and started again: the state is derived again from the wallet and the record.
            val second = foundWallet()
            val restarted = TexApi.monitor(second, FakeTexTor().apply { on = true }, idle = { true }, store = store)
            repeat(3) {
                second.tip = SHIELD_EXPIRY + TexRulesView.REORG_MARGIN - 1
                assertEquals("SHIELDING", TexApi.state(TexApi.statusOf(TexApi.pass(restarted), LEG2)))
            }

            assertEquals(emptyList(), second.submitted)
            assertEquals(0, second.proposeCount)
        }

    @Test
    fun a_kill_between_the_create_and_the_record_still_counts_as_a_shield() =
        runBlocking {
            // A process ended after the record of the attempt, before onStored: no ids, no expiry on record.
            val store = TexApi.memoryStore()
            val key = TexApi.recordKey(TexFixture.EPHEMERAL)
            val startTip = TexFixture.EXPIRY + 5
            TexApi.call(
                store,
                "put",
                TexApi.construct("TexRecoveryRecord", key, 1, startTip, emptyList<String>(), null, null, null, false, false, false)
            )
            val wallet = foundWallet().apply { tip = startTip + TexRulesView.UNKNOWN_EXPIRY_BOUND + TexRulesView.REORG_MARGIN - 1 }
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store)

            repeat(3) { assertEquals("SHIELDING", TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2))) }
            assertEquals(emptyList(), wallet.submitted, "a shield that may exist was not waited out")

            wallet.tip = startTip + TexRulesView.UNKNOWN_EXPIRY_BOUND + TexRulesView.REORG_MARGIN
            TexApi.pass(monitor)
            TexApi.pass(monitor)
            assertEquals(1, wallet.submitted.size, "past the bound, twice, and found again: the next attempt")
            assertEquals(2, TexApi.prop(TexApi.storedRecord(store, key)!!, "attempt"))
        }

    @Test
    fun the_shield_waits_while_something_else_is_in_progress() =
        runBlocking {
            val wallet = foundWallet()
            var idle = false
            var now = 0L
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { idle }, clock = { now })

            val waiting = TexApi.statusOf(TexApi.pass(monitor), LEG2)
            assertEquals("RETURNED", TexApi.state(waiting))
            assertEquals(0, wallet.proposeCount)
            TexApi.pass(monitor)
            assertEquals("RETURNED", TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2)), "still found, still waiting")
            assertEquals(1, wallet.lookups.size, "no lookup every pass while it waits")

            idle = true
            now += 2L * 60 * 1000
            TexApi.pass(monitor)
            assertEquals(1, wallet.submitted.size)
            assertEquals(2, wallet.lookups.size, "the shield is built on a fresh lookup")
        }

    @Test
    fun a_record_that_cannot_be_read_never_allows_another_shield() {
        val key = TexApi.recordKey(TexFixture.EPHEMERAL)
        val record = TexApi.decode(key, "garbage")

        assertTrue((TexApi.prop(record, "attempt") as Int) >= TexRulesView.MAX_SHIELD_ATTEMPTS)
        assertTrue(TexApi.prop(record, "createStartTip") != null)

        val full =
            TexApi.construct("TexRecoveryRecord", key, 2, 1_005L, listOf(SHIELD, txid(0xB0)), 1_100L, 150_000L, 10_000L, true, true, false)
        assertEquals(full, TexApi.decode(key, TexApi.encode(full)))
        assertFalse(TexFixture.EPHEMERAL in TexApi.encode(full))
    }
}
