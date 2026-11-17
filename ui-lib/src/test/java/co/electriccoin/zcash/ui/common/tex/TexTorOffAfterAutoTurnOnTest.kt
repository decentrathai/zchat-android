package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.LEG2
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * T3b-2 item 1 (fix-all 2.13.0, train T3b; train T3 phone finding 3, F3 check 5 FAIL on the HONOR): after a failed
 * automatic Tor start and then a successful one, the user turned Tor off, and the transaction detail said "ZCHAT could
 * not connect over Tor yet … and keeps trying". ZCHAT does not try (it respects the user's choice), and the text that
 * says how to turn Tor back on (tex_status_tor_off_body) was never shown.
 *
 * Cause at f4b0853c7: TexSendMonitor resets its Tor failure count only in a pass that READS Tor on. The pass that
 * turned Tor on had read it off, and the user turned it off before the next pass, so the old failures stayed, and
 * runPass turns every NEEDS_TOR into CHECKING + torFailing while `!torOn && failures > 0`, including the NEEDS_TOR
 * the user chose (torOffByUser).
 *
 * What it must be: a successful start clears the failure count, and a send whose Tor the user turned off says so
 * (NEEDS_TOR, torOffByUser, the Tor-off text), never "keeps trying". A new failure after a successful start waits the
 * first backoff again, not a doubled one. Nothing here spends: the fakes record every Tor start and lookup.
 */
class TexTorOffAfterAutoTurnOnTest {
    @After
    fun disarm() = FaultSeams.disarmAll()

    /** The phone case: seam fails the first start, the next one works, then the user turns Tor off. */
    private class Scene {
        val wallet = TexFixture.stuckWallet()
        val tor = FakeTexTor()
        val store = TexApi.memoryStore()
        var idle = true
        var now = 0L
        val monitor = TexApi.monitor(wallet, tor, idle = { idle }, store = store, clock = { now })

        suspend fun failThenTurnOn() {
            FaultSeams.arm(FaultSeam.TOR_BOOTSTRAP_FAILS, 1)
            val failed = TexApi.statusOf(TexApi.pass(monitor), LEG2)
            assertEquals(true, TexApi.prop(failed, "torFailing"), "setup: the seam makes the first start fail")
            FaultSeams.disarm(FaultSeam.TOR_BOOTSTRAP_FAILS)
            now += FIRST_BACKOFF_MS + SECOND_MS
            TexApi.pass(monitor)
            assertEquals(1, tor.turnOns, "setup: the start after the backoff turns Tor on")
            assertEquals(true, tor.on)
            assertEquals(true, TexApi.call(store, "torTurnedOnByZchat"), "setup: ZCHAT remembers it turned Tor on")
        }

        suspend fun status(): Any = TexApi.statusOf(TexApi.pass(monitor), LEG2)
    }

    @Test
    fun tor_the_user_turned_off_after_a_failed_and_then_a_successful_start_says_tor_is_off_not_keeps_trying() =
        runBlocking {
            val scene = Scene()
            scene.failThenTurnOn()

            // The user turns Tor off in Advanced Settings before the next pass reads it on.
            scene.tor.on = false
            scene.tor.explicitlyOff = true
            scene.now += SECOND_MS
            val status = scene.status()

            assertEquals(
                "NEEDS_TOR",
                TexApi.state(status),
                "the user turned Tor off: the send must say Tor is needed, not that ZCHAT keeps trying",
            )
            assertEquals(true, TexApi.prop(status, "torOffByUser"))
            assertEquals(false, TexApi.prop(status, "torFailing"), "an old failure count still says 'keeps trying'")
            assertEquals(1, scene.tor.turnOns, "ZCHAT does not overrule the user")
            assertEquals(emptyList(), scene.wallet.lookups, "nothing is looked up while Tor is off")
            assertEquals(ChatResourceText.stringId("tex_status_tor_off_body"), bodyRes(status), "the detail text")
            assertFalse(TexApi.sendAgainAllowed(TexApi.state(status)), "Send again stays withheld")
        }

    @Test
    fun a_successful_start_clears_the_old_failure_count() =
        runBlocking {
            val scene = Scene()
            scene.failThenTurnOn()

            // Tor goes off again without a stored "off" choice (not the user's choice), while something is in progress.
            scene.tor.on = false
            scene.idle = false
            scene.now += SECOND_MS
            val waiting = scene.status()
            assertEquals("NEEDS_TOR", TexApi.state(waiting), "no start has failed since Tor came on: not 'keeps trying'")
            assertEquals(false, TexApi.prop(waiting, "torFailing"))
            assertEquals(1, scene.tor.turnOns, "nothing is started while something else is in progress")

            // A new start fails: the next one waits the FIRST backoff again, not a doubled one.
            scene.idle = true
            scene.tor.turnOnFails = true
            val failedAgain = scene.status()
            assertEquals(2, scene.tor.turnOns, "the start is tried once idle")
            assertEquals(true, TexApi.prop(failedAgain, "torFailing"), "a start that failed now is said")
            scene.now += FIRST_BACKOFF_MS + SECOND_MS
            scene.tor.turnOnFails = false
            scene.status()
            assertEquals(3, scene.tor.turnOns, "after a successful start the failure count starts again at one")
        }

    /** Control, green before and after: a start that keeps failing still says 'still checking' with backoff. */
    @Test
    fun control_a_start_that_fails_still_says_still_checking() =
        runBlocking {
            FaultSeams.arm(FaultSeam.TOR_BOOTSTRAP_FAILS, 1)
            val scene = Scene()
            val status = scene.status()
            assertEquals("CHECKING", TexApi.state(status))
            assertEquals(true, TexApi.prop(status, "torFailing"))
            assertEquals(ChatResourceText.stringId("tex_status_tor_failing_body"), bodyRes(status))
            assertEquals(0, scene.tor.turnOns)
        }

    private fun bodyRes(status: Any): Any? {
        val texts = TexApi.static("TexTextsKt", "texTextsOf", status, Locale.US)!!
        return TexApi.prop(TexApi.prop(texts, "body")!!, "resId")
    }

    private companion object {
        const val FIRST_BACKOFF_MS = 2L * 60 * 1000
        const val SECOND_MS = 1_000L
    }
}
