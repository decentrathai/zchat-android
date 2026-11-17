package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-425 (fix-all 2.13.0, train T6; RELAY-GROUP-SPEC §4; register ZC-425, low).
 *
 * At 7625226be `NostrRelayPool.wireClient`'s `onClosedSub` answers a relay's CLOSED of one subscription, after the
 * backoff (and, for read-blocked slot 0, after its pause), by asking that relay for every subscription of the pool
 * again. A subscription the relay still serves on that socket is then live twice there (during the 15-minute rotation
 * grace window the pool holds two filters per relay). Events are de-duplicated by id, so nothing shows twice; the cost
 * is the extra REQ, and a relay that limits concurrent subscriptions may CLOSE more because of it.
 *
 * Wanted: after its wait each path asks the socket in use only for the subscriptions that socket does not hold
 * (as the deferred path does since ZC-406 / ZC-501). No path returns because the socket was replaced meanwhile: it
 * acts on the socket in use (ZC-501's rule), whose replay is already held, so nothing doubles and nothing is lost.
 *
 * Cases 1-2: the real pool and local [HealthRelay]s, polling waits, counts only (no wall-clock gap is asserted,
 * ZC-503). Cases 3-4: virtual time, no socket ([ResubscribeVirtualTimeFixture]). Synthetic urls and filters.
 */
class ClosedSubscriptionIsAskedAgainAloneTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null
    private var fixture: ResubscribeVirtualTimeFixture? = null

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
        fixture?.close()
    }

    @Test
    fun `a relay that closes one of two live reads is asked again for that one only`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay()
            val (c, urlC) = relay()
            val p = startPool(listOf(urlA, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE))
            assertTrue(
                HealthRelay.awaitTrue { reqs(a, ONE) == 1 && reqs(b, ONE) == 1 && reqs(c, ONE) == 1 },
                "fixture: every relay serves the first read",
            )

            // B serves the first read; it CLOSEs only the second ("rate-limited:", not a read block), then serves again.
            b.mode = HealthRelay.Mode.RATE_LIMITED
            p.subscribe(FILTER_TWO) { }
            assertTrue(HealthRelay.awaitTrue { reqs(b, TWO) >= 1 }, "fixture: the second read reached B")
            delay(100)
            assertEquals(1, reqs(b, TWO), "fixture: B was asked again before it was switched back to serve")
            b.mode = HealthRelay.Mode.SERVES

            val askedAgain = HealthRelay.awaitTrue(WAIT_MS) { reqs(b, TWO) >= 2 }
            assertTrue(askedAgain, "the read B closed was never asked again (${b.inboxReqs().size} REQs)")
            delay(SETTLE_MS)
            assertEquals(2, reqs(b, TWO), "the read B closed once and serves since was asked more (or less) than once again")
            assertEquals(
                1,
                reqs(b, ONE),
                "B closed one read and was asked again for the other one too, which it serves: that read is live twice on B",
            )
            assertEquals(1, b.upgrades.get(), "fixture: one socket")
            assertEquals(1, reqs(a, ONE) * reqs(a, TWO) * reqs(c, ONE) * reqs(c, TWO), "fixture: A and C got each read once")
        }

    @Test
    fun `control - a relay that closes both reads is asked again for both`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.RATE_LIMITED)
            val (c, urlC) = relay()
            startPool(listOf(urlA, urlB, urlC), shortConfig(listOf(urlB, urlC)), listOf(FILTER_ONE, FILTER_TWO))
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().size >= 2 && c.inboxReqs().size >= 2 }, "fixture: A and C read")

            // The first batch's two CLOSEDs: one re-subscribes after the backoff, the other waits for the window's end.
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().size >= 2 }, "fixture: B got both reads")
            delay(50)
            b.mode = HealthRelay.Mode.SERVES
            assertTrue(
                HealthRelay.awaitTrue(WAIT_MS) { reqs(b, ONE) >= 2 && reqs(b, TWO) >= 2 },
                "B closed both reads and was not asked again for both (${reqs(b, ONE)} and ${reqs(b, TWO)} REQs)",
            )
            delay(SETTLE_MS)
            assertEquals(2, reqs(b, ONE), "B serves the first read again but was asked for it once more")
            assertEquals(2, reqs(b, TWO), "B serves the second read again but was asked for it once more")
        }

    @Test
    fun `a socket rebuilt inside the backoff holds each read once after it`() {
        val f = ResubscribeVirtualTimeFixture(slot = 1, filters = listOf(V_ONE, V_TWO)).also { fixture = it }
        val a = f.client
        f.closeRead(a, filter = V_TWO) // at 0: the 500 ms backoff starts
        f.advanceTo(200)
        val b = f.rebuild()
        assertEquals(1, f.held(b, V_ONE), "fixture: the rebuilt socket's replay holds the first read")
        assertEquals(1, f.held(b, V_TWO), "fixture: the rebuilt socket's replay holds the second read")

        f.advanceTo(ResubscribeVirtualTimeFixture.BACKOFF_MS)
        assertEquals(
            1,
            f.held(b, V_ONE),
            "after the backoff the rebuilt socket was asked again for a read its replay holds (one the relay never closed)",
        )
        assertEquals(1, f.held(b, V_TWO), "after the backoff the rebuilt socket was asked again for a read its replay holds")
        assertEquals(0, f.held(a, V_ONE) + f.held(a, V_TWO), "the replaced socket was asked")
        f.advanceTo(10_000)
        assertEquals(1, f.held(b, V_ONE) * f.held(b, V_TWO), "a read was doubled (or lost) on the rebuilt socket later")
    }

    @Test
    fun `a read closed on a socket that holds two is asked again alone after the backoff`() {
        val f = ResubscribeVirtualTimeFixture(slot = 1, filters = listOf(V_ONE, V_TWO)).also { fixture = it }
        f.advanceTo(100)
        val b = f.rebuild() // a connected socket whose replay holds both reads
        f.closeRead(b, filter = V_TWO)
        assertEquals(0, f.held(b, V_TWO), "fixture: the CLOSED took the second read off the socket")

        f.advanceTo(100 + ResubscribeVirtualTimeFixture.BACKOFF_MS - 1)
        assertEquals(0, f.held(b, V_TWO), "the closed read was asked again before the backoff")
        f.advanceTo(100 + ResubscribeVirtualTimeFixture.BACKOFF_MS)
        assertEquals(1, f.held(b, V_TWO), "the closed read was not asked again, once, after the backoff")
        assertEquals(
            1,
            f.held(b, V_ONE),
            "the relay closed one read and the socket was asked again for the other one too, which it holds: live twice",
        )
    }

    @Test
    fun `control - slot 0's read-block retry asks the socket in use after a rebuild during its pause`() {
        val f = ResubscribeVirtualTimeFixture(slot = 0).also { fixture = it }
        val a = f.client
        f.closeRead(a, reason = AUTH_REQUIRED) // at 0: read-blocked, the RH-X1 pause (2 s) starts
        assertTrue(f.readBlocked(), "fixture: the CLOSED auth-required made slot 0 read-blocked")
        f.advanceTo(500)
        val b = f.rebuild()
        assertEquals(1, f.held(b), "fixture: the rebuilt socket's replay holds the read")
        f.advanceTo(700)
        f.closeRead(b, reason = AUTH_REQUIRED) // dropped: the retry from 0 is still pending
        assertEquals(0, f.held(b), "fixture: the CLOSED took the read off the rebuilt socket")

        f.advanceTo(ResubscribeVirtualTimeFixture.OWN_READ_RETRY_MS - 1)
        assertEquals(0, f.held(b), "slot 0 was asked again before its pause ended")
        f.advanceTo(ResubscribeVirtualTimeFixture.OWN_READ_RETRY_MS)
        assertEquals(
            1,
            f.held(b),
            "ZChat's relay closed the read on the rebuilt socket during the retry's pause and the retry did not ask the " +
                "socket in use, once: slot 0 carries no read of ours (or carries it twice)",
        )
        assertEquals(0, f.held(a), "the replaced socket was asked")
    }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    /** How many inbox REQs [relay] got for the read whose `#p` is [p]. */
    private fun reqs(
        relay: HealthRelay,
        p: String,
    ): Int = relay.inboxReqs().count { p in it }

    /** Subscribes [filters] (before start, so each relay gets exactly one REQ per filter on connect) and starts. */
    private fun startPool(
        urls: List<String>,
        config: Any,
        filters: List<Map<String, Any>>,
    ): NostrRelayPool {
        val p = RelayHealthApi.pool(urls, scope, null, config, RelayHealthApi.CountingSetStore().proxy).also { pool = it }
        filters.forEach { filter -> p.subscribe(filter) { } }
        p.start()
        return p
    }

    /** Window 2 s, cap 8 s, first re-subscribe 1.5 s after the CLOSED; each set only when the config has it. */
    private fun shortConfig(catalog: List<String>): Any {
        val config = RelayHealthApi.config(catalog, AtomicLong(CLOCK0))
        runCatching { RelayHealthApi.setProp(config, "resubDebounceMs", 2_000L) }
        runCatching { RelayHealthApi.setProp(config, "resubMaxMs", 8_000L) }
        runCatching { RelayHealthApi.setProp(config, "resubBackoffMs", 1_500L) }
        return config
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val SETTLE_MS = 5_000L
        const val CLOCK0 = 1_790_000_000_000L
        const val AUTH_REQUIRED = "auth-required: x"
        val ONE = "e".repeat(64)
        val TWO = "f".repeat(64)
        val FILTER_ONE = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf(ONE))
        val FILTER_TWO = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf(TWO))

        // Their own objects: the pool and the clients are asked by identity, and each fixture has its own pool.
        val V_ONE = ResubscribeVirtualTimeFixture.readFilter("e")
        val V_TWO = ResubscribeVirtualTimeFixture.readFilter("f")
    }
}
