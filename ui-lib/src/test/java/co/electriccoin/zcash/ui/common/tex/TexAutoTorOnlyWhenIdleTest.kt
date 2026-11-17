package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F3 (PLAN_V2 §2 F3 "Auto Tor"; owner 25 Sep): the lookup needs Tor. When Tor is off, the checker turns it on by
 * itself, but only while nothing else is in progress (turning it on restarts the wallet): no send between the start
 * of its create and its settle, no private-move step, no live call, no Keystone PCZT open. A failed start is retried
 * with backoff and said honestly ("still checking"), never "send again"; Tor the user turned off again is not
 * overruled. The debug seam TOR_BOOTSTRAP_FAILS makes the start fail.
 * Fails at 15e2b1c39: there is no TEX checker, no automatic Tor and no idle rule.
 */
class TexAutoTorOnlyWhenIdleTest {
    @After
    fun disarm() = FaultSeams.disarmAll()

    @Test
    fun tor_is_not_turned_on_while_something_else_is_in_progress() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val tor = FakeTexTor()
            var idle = false
            val monitor = TexApi.monitor(wallet, tor, idle = { idle })

            val busy = TexApi.statusOf(TexApi.pass(monitor), LEG2)
            assertEquals("NEEDS_TOR", TexApi.state(busy))
            assertEquals(0, tor.turnOns)
            assertEquals(emptyList(), wallet.lookups)

            idle = true
            TexApi.pass(monitor)
            assertEquals(1, tor.turnOns, "Tor is turned on once nothing else is in progress")

            val after = TexApi.statusOf(TexApi.pass(monitor), LEG2)
            assertEquals(1, tor.turnOns, "Tor stays on; it is not turned on again")
            assertEquals(1, wallet.lookups.size, "with Tor on, the lookup runs")
            assertEquals("CHECKING", TexApi.state(after))
        }

    @Test
    fun zchat_turning_tor_on_is_remembered_for_the_notice() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val store = TexApi.memoryStore()
            val monitor = TexApi.monitor(wallet, FakeTexTor(), idle = { true }, store = store)

            TexApi.pass(monitor)

            assertEquals(true, TexApi.call(store, "torTurnedOnByZchat"))
        }

    @Test
    fun the_bootstrap_fail_seam_keeps_checking_with_backoff_and_never_offers_send_again() =
        runBlocking {
            FaultSeams.arm(FaultSeam.TOR_BOOTSTRAP_FAILS, 1)
            val wallet = TexFixture.stuckWallet()
            val tor = FakeTexTor()
            var now = 0L
            val monitor = TexApi.monitor(wallet, tor, idle = { true }, clock = { now })

            val first = TexApi.statusOf(TexApi.pass(monitor), LEG2)
            assertEquals("CHECKING", TexApi.state(first))
            assertEquals(true, TexApi.prop(first, "torFailing"))
            assertFalse(TexApi.sendAgainAllowed(TexApi.state(first)))
            assertEquals(0, tor.turnOns, "the seam fails the start before the switch")
            assertEquals(false, tor.on)

            FaultSeams.disarm(FaultSeam.TOR_BOOTSTRAP_FAILS)
            TexApi.pass(monitor)
            assertEquals(0, tor.turnOns, "the next start waits for its backoff")

            now += 2L * 60 * 1000
            TexApi.pass(monitor)
            assertEquals(1, tor.turnOns)
        }

    @Test
    fun a_start_that_throws_is_retried_with_backoff() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val tor = FakeTexTor().apply { turnOnFails = true }
            var now = 0L
            val monitor = TexApi.monitor(wallet, tor, idle = { true }, clock = { now })

            val first = TexApi.statusOf(TexApi.pass(monitor), LEG2)
            assertEquals("CHECKING", TexApi.state(first))
            assertEquals(true, TexApi.prop(first, "torFailing"))
            TexApi.pass(monitor)
            assertEquals(1, tor.turnOns)

            now += 2L * 60 * 1000
            TexApi.pass(monitor)
            assertEquals(2, tor.turnOns)
            now += 3L * 60 * 1000
            TexApi.pass(monitor)
            assertEquals(2, tor.turnOns, "the wait doubles")
        }

    @Test
    fun tor_on_but_not_started_looks_nothing_up_and_says_still_checking() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val tor =
                FakeTexTor().apply {
                    on = true
                    failing = true
                }
            val monitor = TexApi.monitor(wallet, tor, idle = { true })

            val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)

            assertEquals("CHECKING", TexApi.state(status))
            assertEquals(true, TexApi.prop(status, "torFailing"))
            assertEquals(emptyList(), wallet.lookups)
        }

    @Test
    fun tor_the_user_turned_off_after_zchat_turned_it_on_is_not_overruled() =
        runBlocking {
            val wallet = TexFixture.stuckWallet()
            val store = TexApi.memoryStore()
            TexApi.call(store, "markTorTurnedOnByZchat")
            val tor = FakeTexTor().apply { explicitlyOff = true }
            val monitor = TexApi.monitor(wallet, tor, idle = { true }, store = store)

            val status = TexApi.statusOf(TexApi.pass(monitor), LEG2)

            assertEquals("NEEDS_TOR", TexApi.state(status))
            assertEquals(true, TexApi.prop(status, "torOffByUser"))
            assertEquals(0, tor.turnOns)
        }

    @Test
    fun the_idle_rule_waits_for_sends_private_moves_calls_and_keystone() {
        var call = false
        var pczt = false
        val rule = TexApi.construct("TexIdleRule", { call }, { pczt })
        val activity: Any = TexApi.cls("WalletActivity").getField("INSTANCE").get(null)!!
        val kinds = TexApi.cls("WalletActivity\$Kind")

        fun kind(name: String): Any = kinds.enumConstants!!.first { (it as Enum<*>).name == name }!!

        fun idle() = TexApi.call(rule, "isIdle") as Boolean

        assertTrue(idle())
        for (name in listOf("SEND", "PRIVATE_MOVE")) {
            TexApi.call(activity, "enter", kind(name))
            assertFalse(idle(), "$name in progress")
            TexApi.call(activity, "leave", kind(name))
            assertTrue(idle())
        }
        call = true
        assertFalse(idle(), "a live call")
        call = false
        pczt = true
        assertFalse(idle(), "a Keystone PCZT open")
    }

    @Test
    fun every_send_of_the_one_submit_path_counts_as_in_progress() {
        val source =
            File(uiLibDir(), "src/main/java/co/electriccoin/zcash/ui/common/datasource/ProposalDataSource.kt").readText()
        val body =
            source.substringAfter("private suspend fun submitTransactionInternal(").substringBefore("\n    }\n")
        assertTrue(
            "WalletActivity.track(WalletActivity.Kind.SEND)" in body,
            "ProposalDataSourceImpl.submitTransactionInternal must run as WalletActivity SEND",
        )
    }

    @Test
    fun a_live_call_and_an_open_keystone_pczt_are_what_the_phone_s_idle_rule_reads() {
        val module = File(uiLibDir(), "src/main/java/co/electriccoin/zcash/di/RepositoryModule.kt").readText()
        assertTrue("TexIdleRule(" in module, "the monitor is given the idle rule")
        assertTrue("CallController.current" in module, "the idle rule reads the live call")
        assertTrue("getProposalPCZT() != null" in module, "the idle rule reads the open Keystone PCZT")
    }

    private fun uiLibDir(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
}
