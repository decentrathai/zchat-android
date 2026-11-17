package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-406 (fix-all 2.13.0, train T5b; t5b-add SPECS §3; register ZC-406, medium, also in 2.12.4).
 *
 * At be0d2e0de `NostrRelayPool.wireClient`'s `onClosedSub` re-subscribes after a CLOSED that is not a read block only when
 * the last re-subscribe on that relay is 15 s old or more; a CLOSED inside those 15 s is dropped. `RelayClient` forgets a
 * subscription the relay CLOSED, so neither `resubscribeLive` nor `redeliverBacklog` asks for it again: a relay that
 * CLOSEs our read twice in a row ("rate-limited: …") keeps a socket that carries no read of ours until the socket is
 * rebuilt. Slot 0 (ZChat's relay, read by every version, never swapped) stays that way.
 *
 * Wanted: such a CLOSED schedules one deferred re-subscribe at the end of the window (last re-subscribe + window), only
 * while the relay is still connected, not retired and on the same socket; at most one waits per relay; the window doubles
 * for each deferral before the relay answers a read again (cap `resubMaxMs`, 5 min by default) and is back to its base
 * (`resubDebounceMs`, 15 s) after an EOSE or EVENT from it. The first CLOSED's re-subscribe after the backoff (3 s) is
 * unchanged. The numbers are set by name only when the config has them: at be0d2e0de the defaults apply and each case
 * fails on the missing REQ within its wait, not on a setter. Local MockWebServer relays; synthetic urls and events.
 */
class RelayClosingTwiceQuicklyIsAskedAgainTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    @Test
    fun `a relay that closes our read twice quickly is asked again on the same socket and its stored wrap arrives`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            b.store(HealthRelay.event(WRAP_B, 1_790_000_000L))
            val got = CopyOnWriteArrayList<String>()
            val p = startPool(listOf(urlA, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE), got)
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() }, "fixture: A and C read")

            servesAfterReqs(b, 2)
            val askedAgain = HealthRelay.awaitTrue(WAIT_MS) { b.inboxReqs().size >= 3 }
            assertTrue(
                askedAgain,
                "a relay that closed our read twice within the window was never asked again (${b.inboxReqs().size} REQs)",
            )
            assertTrue(HealthRelay.awaitTrue { got.any { WRAP_B in it } }, "B's stored wrap never reached the subscriber")
            assertEquals(1, b.upgrades.get(), "B was asked again on a new socket, not on the one that closed our reads")
            assertTrue(p.activeUrls().contains(urlB), "fixture: B is still in use")
        }

    @Test
    fun `ZChat's relay in slot 0 that closes our read twice quickly is asked again`() =
        runBlocking {
            val (own, urlOwn) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (b, urlB) = relay()
            val (c, urlC) = relay()
            own.store(HealthRelay.event(WRAP_OWN, 1_790_000_000L))
            val got = CopyOnWriteArrayList<String>()
            startPool(listOf(urlOwn, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE), got)
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() }, "fixture: B and C read")

            servesAfterReqs(own, 2)
            val askedAgain = HealthRelay.awaitTrue(WAIT_MS) { own.inboxReqs().size >= 3 }
            assertTrue(
                askedAgain,
                "ZChat's relay closed our read twice within the window and was never asked again (${own.inboxReqs().size} REQs)",
            )
            assertTrue(HealthRelay.awaitTrue { got.any { WRAP_OWN in it } }, "ZChat's relay's stored wrap never arrived")
            assertEquals(1, own.upgrades.get(), "ZChat's relay was asked again on a new socket")
        }

    @Test
    fun `with the production numbers the third read comes at the end of the 15 s window`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            b.store(HealthRelay.event(WRAP_B, 1_790_000_000L))
            val got = CopyOnWriteArrayList<String>()
            val config = RelayHealthApi.config(listOf(urlB, urlC), AtomicLong(CLOCK0))
            startPool(listOf(urlA, urlB, urlC), config, listOf(FILTER_ONE), got)
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() }, "fixture: A and C read")

            servesAfterReqs(b, 2)
            val askedAgain = HealthRelay.awaitTrue(WAIT_MS) { b.inboxReqs().size >= 3 }
            assertTrue(askedAgain, "with the default numbers B was never asked again (${b.inboxReqs().size} REQs in 20 s)")
            val t = b.inboxReqTimesMs()
            val gap = t[2] - t[0]
            assertTrue(gap in 13_000L..19_000L, "the third read came $gap ms after the first, not at the end of the 15 s window")
            assertTrue(HealthRelay.awaitTrue { got.any { WRAP_B in it } }, "B's stored wrap never reached the subscriber")
            assertEquals(1, b.upgrades.get(), "B was asked again on a new socket")
        }

    @Test
    fun `two CLOSEDs inside one window give one deferred REQ batch`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            val got = CopyOnWriteArrayList<String>()
            startPool(listOf(urlA, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE, FILTER_TWO), got)
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().size >= 2 && c.inboxReqs().size >= 2 }, "fixture: A and C read")

            // Two subscriptions: the first REQ batch's two CLOSEDs, then the backoff batch's two, all inside the window.
            val deferred = HealthRelay.awaitTrue(WAIT_MS) { b.inboxReqs().size >= 6 }
            assertTrue(deferred, "B closed two batches inside the window and got no deferred batch (${b.inboxReqs().size} REQs)")
            delay(2_000) // the next deferral is due 4 s (the doubled window) after this batch
            val t = b.inboxReqTimesMs()
            assertEquals(6, t.size, "more than one deferred batch inside one window (one REQ per subscription expected)")
            assertTrue(t[4] - t[3] >= 1_000L, "the deferred batch came ${t[4] - t[3]} ms after the backoff batch, not at the window's end")
        }

    @Test
    fun `a relay that closes every read is asked again at growing gaps up to the cap`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            val got = CopyOnWriteArrayList<String>()
            startPool(listOf(urlA, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE), got)
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() }, "fixture: A and C read")

            // Window 2 s doubling to the 8 s cap: REQs at about 0, 0.5, 2, 6, 14, 22 s.
            val sixth = HealthRelay.awaitTrue(30_000) { b.inboxReqs().size >= 6 }
            val t = b.inboxReqTimesMs()
            assertTrue(sixth, "a relay that closes every read was not asked again at growing gaps (${t.size} REQs in 30 s)")
            val gaps = t.zipWithNext { x, y -> y - x }
            assertTrue(gaps[2] > gaps[1] * 3 / 2 && gaps[3] > gaps[2] * 3 / 2, "the gaps do not grow: $gaps")
            assertTrue(gaps[4] in 6_500L..10_000L, "the gap past the cap is not the cap (8 s): $gaps")
            assertTrue(t.count { it - t[0] < 20_000L } <= 5, "more than 5 REQs in 20 s to a relay that closes every read: $gaps")
            assertEquals(1, b.upgrades.get(), "fixture: one socket")
        }

    @Test
    fun `the window is back to its base once the relay answered a read`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            val got = CopyOnWriteArrayList<String>()
            val p = startPool(listOf(urlA, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE), got)
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() }, "fixture: A and C read")

            // REQs 1-3 closed (the window doubles to 8 s), REQ 4 served (EOSE).
            servesAfterReqs(b, 3)
            val served = HealthRelay.awaitTrue(WAIT_MS) { b.inboxReqs().size >= 4 }
            assertTrue(served, "B closed three reads and was not asked a fourth time (${b.inboxReqs().size} REQs)")
            delay(300)
            b.mode = HealthRelay.Mode.RATE_LIMITED
            p.redeliverBacklog() // a fresh REQ, closed at once, inside the window
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().size >= 5 }, "fixture: the fresh REQ reached B")
            val again = HealthRelay.awaitTrue(WAIT_MS) { b.inboxReqs().size >= 6 }
            val t = b.inboxReqTimesMs()
            assertTrue(again, "the fresh read B closed was never asked again (${t.size} REQs)")
            assertTrue(
                t[5] - t[3] in 1_000L..3_500L,
                "after B answered, the next deferral came ${t[5] - t[3]} ms after the last re-subscribe, not at the base window (2 s)",
            )
        }

    @Test
    fun `a relay swapped out while a re-subscribe waits gets no REQ`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            val (d, urlD) = relay()
            val clock = AtomicLong(CLOCK0)
            val config = shortConfig(listOf(urlB, urlC, urlD), clock)
            // The saved set puts B in slot 1; the debug reset brings back the defaults (D there) and retires B.
            val store = RelayHealthApi.CountingSetStore(RelayHealthApi.relaySetState(urlB, urlC))
            val got = CopyOnWriteArrayList<String>()
            val p = startPool(listOf(urlA, urlD, urlC), config, listOf(FILTER_ONE), got, store.proxy)
            assertEquals(listOf(urlA, urlB, urlC), RelayHealthApi.activeUrls(p), "fixture: B from the saved set")
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().size >= 2 }, "fixture: B closed our read and was asked once more")
            delay(300)

            RelayHealthApi.resetRelaySet(p)
            assertEquals(listOf(urlA, urlD, urlC), RelayHealthApi.activeUrls(p), "fixture: B swapped out")
            assertTrue(HealthRelay.awaitTrue { d.inboxReqs().isNotEmpty() }, "fixture: D got our read")
            delay(4_000)
            assertEquals(2, b.inboxReqs().size, "a relay swapped out got a deferred REQ")
            assertTrue(a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty(), "fixture: A and C read")
        }

    @Test
    fun `control - a single CLOSED is re-subscribed after the backoff as today`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            b.store(HealthRelay.event(WRAP_B, 1_790_000_000L))
            val got = CopyOnWriteArrayList<String>()
            val config = RelayHealthApi.config(listOf(urlB, urlC), AtomicLong(CLOCK0))
            startPool(listOf(urlA, urlB, urlC), config, listOf(FILTER_ONE), got)
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() }, "fixture: A and C read")

            servesAfterReqs(b, 1)
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().size >= 2 }, "one CLOSED was not re-subscribed")
            val t = b.inboxReqTimesMs()
            assertTrue(t[1] - t[0] in 2_500L..6_000L, "the re-subscribe came ${t[1] - t[0]} ms after the CLOSED, not after 3 s")
            assertTrue(HealthRelay.awaitTrue { got.any { WRAP_B in it } }, "B's stored wrap never arrived")
            delay(4_000)
            assertEquals(2, b.inboxReqs().size, "a relay that serves our read again was asked once more")
        }

    @Test
    fun `control - a deferred re-subscribe does not repeat a read the relay already serves`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            val got = CopyOnWriteArrayList<String>()
            startPool(listOf(urlA, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE, FILTER_TWO), got)
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().size >= 2 && c.inboxReqs().size >= 2 }, "fixture: A and C read")

            // The first batch's two CLOSEDs: one re-subscribes after the backoff, which B serves; the other was inside
            // the window. Both subscriptions are live on B again, so nothing more is sent.
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().size >= 2 }, "fixture: B got both reads")
            delay(50)
            b.mode = HealthRelay.Mode.SERVES
            assertTrue(HealthRelay.awaitTrue(WAIT_MS) { b.inboxReqs().size >= 4 }, "fixture: the backoff batch")
            delay(4_000)
            assertEquals(4, b.inboxReqs().size, "B serves both reads but got them again (a doubled live subscription)")
        }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    /** The relay closes its first [n] inbox REQs and serves the ones after (switched once the n-th CLOSED is out). */
    private suspend fun servesAfterReqs(
        relay: HealthRelay,
        n: Int,
    ) {
        assertTrue(HealthRelay.awaitTrue(WAIT_MS) { relay.inboxReqs().size >= n }, "fixture: $n REQ(s) reached the relay")
        delay(100)
        assertEquals(n, relay.inboxReqs().size, "fixture: the relay was asked again before it was switched to serve")
        relay.mode = HealthRelay.Mode.SERVES
    }

    /** Subscribes [filters] (before start, so each relay gets exactly one REQ per filter on connect) and starts. */
    private fun startPool(
        urls: List<String>,
        config: Any,
        filters: List<Map<String, Any>>,
        got: MutableList<String>,
        store: Any = RelayHealthApi.CountingSetStore().proxy,
    ): NostrRelayPool {
        val p = RelayHealthApi.pool(urls, scope, null, config, store).also { pool = it }
        filters.forEach { filter -> p.subscribe(filter) { json -> got += json } }
        p.start()
        return p
    }

    /** Window 2 s, cap 8 s, first re-subscribe 500 ms after the CLOSED; each set only when the config has it. */
    private fun shortConfig(
        catalog: List<String>,
        clock: AtomicLong = AtomicLong(CLOCK0),
    ): Any {
        val config = RelayHealthApi.config(catalog, clock)
        runCatching { RelayHealthApi.setProp(config, "resubDebounceMs", 2_000L) }
        runCatching { RelayHealthApi.setProp(config, "resubMaxMs", 8_000L) }
        runCatching { RelayHealthApi.setProp(config, "resubBackoffMs", 500L) }
        return config
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val CLOCK0 = 1_790_000_000_000L
        const val WRAP_B = "synthetic-zc406-b"
        const val WRAP_OWN = "synthetic-zc406-own"
        val FILTER_ONE = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("e".repeat(64)))
        val FILTER_TWO = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("f".repeat(64)))
    }
}
