package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import co.electriccoin.zcash.ui.common.tex.TexFixture.SHIELD_EXPIRY
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/**
 * F3 (PLAN_V2 §2 F3; owner 25 Sep: "a second try needs proof the first expired and the funds are still there"):
 * a second shield is built only when the first is proven not sent (the scanned tip 10 blocks past its expiry in two
 * non-decreasing passes, as correction 2 asks of every "not sent") AND a fresh lookup finds the ZEC still unspent.
 * A mined shield ends the recovery; after three attempts, or ZEC too little to move, a person must look.
 * Fails at 15e2b1c39: there is no TEX recovery.
 */
class TexAutoShieldAfterProvenExpiryOnlyTest {
    private val proven = SHIELD_EXPIRY + TexRulesView.REORG_MARGIN

    private suspend fun shieldedOnce(
        wallet: FakeTexWallet,
        store: Any,
        clock: () -> Long = { 0L },
    ): Any {
        wallet.lookupAnswer = { true }
        wallet.shieldPlan = { TexFixture.planSpendingLeg1() }
        val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store, clock = clock)
        TexApi.pass(monitor)
        assertEquals(1, wallet.submitted.size)
        wallet.transactions = listOf(TexFixture.leg1(), TexFixture.leg2(), TexFixture.shieldTx())
        return monitor
    }

    @Test
    fun one_pass_past_the_bound_is_not_a_proof() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val monitor = shieldedOnce(wallet, TexApi.memoryStore())

            wallet.tip = proven + 5
            assertEquals("SHIELDING", TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2)))
            assertEquals(1, wallet.submitted.size)
        }

    @Test
    fun a_rewind_between_the_passes_is_not_a_proof() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val monitor = shieldedOnce(wallet, TexApi.memoryStore())

            wallet.tip = proven + 5
            TexApi.pass(monitor)
            wallet.tip = proven + 1
            TexApi.pass(monitor)
            assertEquals(1, wallet.submitted.size, "a lower second pass proved the shield expired")
        }

    @Test
    fun proven_expired_but_the_zec_not_found_again_builds_nothing() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            var now = 0L
            val monitor = shieldedOnce(wallet, TexApi.memoryStore()) { now }

            wallet.lookupAnswer = { false }
            wallet.tip = proven
            TexApi.pass(monitor)
            now += 3L * 60 * 1000
            TexApi.pass(monitor)
            now += 5L * 60 * 1000
            val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)

            assertEquals(1, wallet.submitted.size)
            assertEquals("RETURNED", TexApi.state(status), "found before, not found now: still being moved back")
        }

    @Test
    fun proven_expired_and_found_again_is_the_one_case_for_a_second_shield() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val store = TexApi.memoryStore()
            var now = 0L
            val monitor = shieldedOnce(wallet, store) { now }

            wallet.tip = proven
            TexApi.pass(monitor)
            now += 3L * 60 * 1000
            TexApi.pass(monitor)

            assertEquals(2, wallet.submitted.size)
            val record = TexApi.storedRecord(store, TexApi.recordKey(TexFixture.EPHEMERAL))!!
            assertEquals(2, TexApi.prop(record, "attempt"))
        }

    @Test
    fun a_mined_shield_ends_the_recovery_and_is_never_repeated() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val monitor = shieldedOnce(wallet, TexApi.memoryStore())

            wallet.transactions = listOf(TexFixture.leg1(), TexFixture.leg2(), TexFixture.shieldTx(mined = SHIELD_EXPIRY - 30))
            repeat(3) {
                wallet.tip = proven + it
                val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)
                assertEquals("SHIELDED", TexApi.state(status))
                assertEquals(true, TexApi.sendAgainAllowed(TexApi.state(status)), "the money is provably back")
            }
            assertEquals(1, wallet.submitted.size)
            assertEquals(1, wallet.lookups.size)
        }

    @Test
    fun after_three_attempts_a_person_must_look() =
        runBlocking {
            val store = TexApi.memoryStore()
            val key = TexApi.recordKey(TexFixture.EPHEMERAL)
            TexApi.call(
                store,
                "put",
                TexApi.construct(
                    "TexRecoveryRecord",
                    key,
                    TexRulesView.MAX_SHIELD_ATTEMPTS,
                    1_005L,
                    listOf(TexFixture.SHIELD),
                    SHIELD_EXPIRY,
                    150_000L,
                    10_000L,
                    false,
                    false,
                    false,
                ),
            )
            val wallet =
                TexFixture.stuckWallet(tip = proven).apply {
                    lookupAnswer = { true }
                    shieldPlan = { TexFixture.planSpendingLeg1() }
                }
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store)

            TexApi.pass(monitor)
            val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)

            assertEquals("NEEDS_SUPPORT", TexApi.state(status))
            assertEquals(emptyList(), wallet.submitted)
            assertEquals(false, TexApi.sendAgainAllowed(TexApi.state(status)))
        }

    @Test
    fun zec_too_little_to_move_is_said_after_two_passes_and_kept() =
        runBlocking {
            val store = TexApi.memoryStore()
            val wallet = TexFixture.stuckWallet().apply { lookupAnswer = { true } }
            var now = 0L
            val monitor = TexApi.monitor(wallet, FakeTexTor().apply { on = true }, idle = { true }, store = store, clock = { now })

            assertEquals("RETURNED", TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2)))
            now += 3L * 60 * 1000
            assertEquals("NEEDS_SUPPORT", TexApi.state(TexApi.statusOf(TexApi.pass(monitor), LEG2)))

            val restarted =
                TexApi.monitor(TexFixture.stuckWallet(), FakeTexTor().apply { on = true }, idle = { true }, store = store)
            assertEquals("NEEDS_SUPPORT", TexApi.state(TexApi.statusOf(TexApi.pass(restarted), LEG2)))
            assertEquals(emptyList(), wallet.submitted)
        }
}
