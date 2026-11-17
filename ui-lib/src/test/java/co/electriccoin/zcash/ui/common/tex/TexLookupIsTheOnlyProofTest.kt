package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.EPHEMERAL
import co.electriccoin.zcash.ui.common.tex.TexFixture.EXPIRY
import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F3 (PLAN.md §3 F3 TexLookupIsTheOnlyProofTest): past leg 2's expiry, only the Tor lookup of the ephemeral address
 * proves the exchange was not paid and the ZEC is still there. SDK 3.0.0 answers "false" both for "nothing there" and
 * for a failed lookup, so "false" proves nothing: the send stays CHECKING and nothing is built. No lookup runs before
 * the expiry, and at most 20 per process, with backoff.
 * Fails at 15e2b1c39: there is no TEX checker.
 */
class TexLookupIsTheOnlyProofTest {
    @Test
    fun before_the_expiry_nothing_is_looked_up() =
        runBlocking {
            val wallet = TexFixture.stuckWallet(tip = EXPIRY + 1)
            val tor = FakeTexTor().apply { on = true }
            val monitor = TexApi.monitor(wallet, tor, idle = { true })

            val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)

            assertEquals("SENDING_STEP2", TexApi.state(status))
            assertEquals(emptyList(), wallet.lookups)
            assertEquals(0, wallet.proposeCount)
        }

    @Test
    fun a_lookup_that_finds_nothing_proves_nothing_and_builds_nothing() =
        runBlocking {
            val wallet = TexFixture.stuckWallet().apply { shieldPlan = { TexFixture.planSpendingLeg1() } }
            val tor = FakeTexTor().apply { on = true }
            var now = 0L
            val monitor = TexApi.monitor(wallet, tor, idle = { true }, clock = { now })

            repeat(40) {
                val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)
                assertEquals("CHECKING", TexApi.state(status))
                assertEquals(false, TexApi.sendAgainAllowed(TexApi.state(status)))
                now += 61L * 60 * 1000
            }

            assertEquals(0, wallet.proposeCount, "a shield was proposed without a lookup that found the ZEC")
            assertEquals(emptyList(), wallet.submitted)
            assertEquals(TexRulesView.MAX_LOOKUPS_PER_PROCESS, wallet.lookups.size, "lookups per process")
            assertTrue(wallet.lookups.all { it == EPHEMERAL }, "only the ephemeral address is looked up")
        }

    @Test
    fun lookups_back_off_between_passes() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val tor = FakeTexTor().apply { on = true }
            var now = 0L
            val monitor = TexApi.monitor(wallet, tor, idle = { true }, clock = { now })

            TexApi.pass(monitor)
            TexApi.pass(monitor)
            now += 60_000
            TexApi.pass(monitor)
            assertEquals(1, wallet.lookups.size, "the second lookup waits at least two minutes")

            now += 2L * 60 * 1000
            TexApi.pass(monitor)
            assertEquals(2, wallet.lookups.size)
        }

    @Test
    fun a_lookup_that_finds_the_zec_is_what_the_shield_is_built_on() =
        runBlocking {
            val wallet =
                TexFixture.stuckWallet().apply {
                    lookupAnswer = { true }
                    shieldPlan = { TexFixture.planSpendingLeg1() }
                }
            val tor = FakeTexTor().apply { on = true }
            val monitor = TexApi.monitor(wallet, tor, idle = { true })

            val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)

            assertEquals(listOf("lookup", "proposeShield", "submitShield"), wallet.calls.filter { it in MOVES })
            assertEquals("SHIELDING", TexApi.state(status))
            assertEquals(TexFixture.FOUND_ZAT, TexApi.prop(status, "foundZat"))
            assertEquals(TexFixture.SHIELD_FEE_ZAT, TexApi.prop(status, "feeZat"))
        }

    @Test
    fun a_shield_that_would_spend_other_zec_is_never_built() =
        runBlocking {
            val wallet =
                TexFixture.stuckWallet().apply {
                    lookupAnswer = { true }
                    shieldPlan = { TexFixture.planSpendingOtherFunds() }
                }
            val tor = FakeTexTor().apply { on = true }
            val store = TexApi.memoryStore()
            val monitor = TexApi.monitor(wallet, tor, idle = { true }, store = store)

            val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)

            assertEquals(emptyList(), wallet.submitted)
            assertEquals(emptyList(), TexApi.storeWrites(store), "nothing is recorded for a shield that was not built")
            assertEquals("RETURNED", TexApi.state(status))
            assertEquals(true, TexApi.prop(status, "waitingForOtherFunds"))
        }

    private companion object {
        val MOVES = setOf("lookup", "proposeShield", "submitShield")
    }
}
