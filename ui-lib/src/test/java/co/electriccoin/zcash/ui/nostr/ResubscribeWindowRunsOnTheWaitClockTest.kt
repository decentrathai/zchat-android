package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Test
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-500 (fix-all 2.13.0, train T6; found in the ZC-482 analysis, its N1): NostrRelayPool's re-subscribe window
 * (ZC-406) is measured on the wall clock and waited out on the coroutine clock. planResubscribe takes since =
 * System.currentTimeMillis() - lastResubAt, and the deferred re-subscribe waits window - since with delay, which runs on
 * the monotonic clock (System.nanoTime). A wall-clock step (NTP, NITZ, the user) between the last re-subscribe and a
 * CLOSED moves the deferral by the step: forward, a rate-limited relay is asked again before the window's end (more
 * CLOSED); back, the deferred re-subscribe, and while it waits every other CLOSED of that relay (resubPending), is held
 * for as long as the step, with no read of ours on that relay meanwhile.
 *
 * Wanted: the window is measured on the clock its wait runs on (RelayHealthConfig.resubNowMs, monotonic by default).
 * The cases run the pool's coroutines on a virtual-time scheduler and hand its clock to resubNowMs when the config has
 * it; at e79fc3d46 it does not, the window reads the wall clock (which barely moves while the virtual clock jumps), and
 * each case fails on the missing or late re-subscribe, not on a setter. No socket: the pool is not started; slot 1's
 * client is wired and marked connected by reflection and is handed a CLOSED frame. A re-subscribe shows as the client
 * holding the read again; the client has a stand-in socket that takes every REQ (ZC-761: a read is held only once its
 * REQ went out).
 * Window 2 s, backoff 500 ms; synthetic urls and filter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResubscribeWindowRunsOnTheWaitClockTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
    }

    @Test
    fun `a CLOSED inside the window is asked again at the window's end, on the clock its wait runs on`() {
        val client = liveSlotOneClient()
        firstCloseAskedAgain(client)

        // A second CLOSED at 1.5 s, inside the 2 s window that began with the first re-subscribe (decided at 0): one
        // deferred re-subscribe, due at 0 + 2 s.
        scheduler.advanceTimeBy(1_500 - BACKOFF_MS)
        closeOurRead(client)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(WINDOW_MS - 1_500 - 1)
        scheduler.runCurrent()
        assertFalse(client.holds(FILTER), "the deferred re-subscribe came before the window's end")
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(
            client.holds(FILTER),
            "the read closed inside the window was not asked again at its end (the last re-subscribe + 2 s on the clock " +
                "its wait runs on): the window is measured on another clock, so a wall-clock step moves it",
        )
    }

    @Test
    fun `a CLOSED after the window is asked again after the backoff, not deferred, on the clock its wait runs on`() {
        val client = liveSlotOneClient()
        firstCloseAskedAgain(client)

        // 2.5 s after the first re-subscribe the 2 s window is over: the next CLOSED is asked again after the backoff.
        scheduler.advanceTimeBy(2_500 - BACKOFF_MS)
        closeOurRead(client)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(BACKOFF_MS)
        scheduler.runCurrent()
        assertTrue(
            client.holds(FILTER),
            "a read closed after the window was not asked again after the backoff: the window is measured on another " +
                "clock than its wait, so it was taken as still open",
        )
    }

    @Test
    fun `the window's clock is monotonic by default, the clock the coroutine delays run on`() {
        val config = RelayHealthApi.config(listOf(URL_B, URL_C), AtomicLong(CLOCK0))

        @Suppress("UNCHECKED_CAST")
        val read = runCatching { RelayHealthApi.prop(config, "resubNowMs") }.getOrNull() as? () -> Long
        val clock = assertNotNull(read, "RelayHealthConfig has no resubNowMs: the re-subscribe window reads the wall clock")
        val before = System.nanoTime() / NANOS_PER_MS
        val at = clock()
        val after = System.nanoTime() / NANOS_PER_MS
        assertTrue(at in before..after, "the window's clock read $at by default, not the monotonic clock ($before..$after)")
    }

    @Test
    fun `control - the first CLOSED is asked again after the backoff, not before`() {
        val client = liveSlotOneClient()
        closeOurRead(client)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(BACKOFF_MS - 1)
        scheduler.runCurrent()
        assertFalse(client.holds(FILTER), "the first CLOSED was asked again before the backoff")
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(client.holds(FILTER), "the first CLOSED was not asked again after the backoff")
    }

    /** The first CLOSED, at 0 on the scheduler's clock: asked again after the backoff (ZC-406, as before). */
    private fun firstCloseAskedAgain(client: RelayClient) {
        closeOurRead(client)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(BACKOFF_MS)
        scheduler.runCurrent()
        assertTrue(client.holds(FILTER), "fixture: the first CLOSED was asked again after the backoff")
    }

    /** A pool (not started) whose slot 1 is wired and marked connected, and that slot's client (no read held yet). */
    private fun liveSlotOneClient(): RelayClient {
        val p =
            RelayHealthApi
                .pool(listOf(URL_OWN, URL_B, URL_C), scope, null, windowConfig(), RelayHealthApi.CountingSetStore().proxy)
                .also { pool = it }
        p.subscribe(FILTER) { } // no relay is connected yet: the pool keeps the read and sends nothing
        val slots = declaredField(NostrRelayPool::class.java, "relays").get(p) as List<*>
        val state = checkNotNull(slots[1])
        declaredField(state.javaClass, "connected").setBoolean(state, true)
        val wire =
            NostrRelayPool::class.java
                .getDeclaredMethod("wireClient", state.javaClass)
                .apply { isAccessible = true }
        wire.invoke(p, state)
        return (declaredField(state.javaClass, "client").get(state) as RelayClient).withStandInSocket()
    }

    /** As the relay's CLOSED of our read: the client holds the read (as once its REQ is out), then reads the frame. */
    private fun closeOurRead(client: RelayClient) {
        if (!client.holds(FILTER)) runCatching { client.subscribe(FILTER) { } } // sent to the stand-in socket
        val subs = declaredField(RelayClient::class.java, "subscriptions").get(client) as Map<*, *>
        val subId = subs.entries.first { (it.value as Pair<*, *>).first === FILTER }.key
        val dispatch =
            RelayClient::class.java
                .getDeclaredMethod("dispatchFrame", String::class.java)
                .apply { isAccessible = true }
        dispatch.invoke(client, "[\"CLOSED\",\"$subId\",\"rate-limited: slow down there chief\"]")
    }

    private fun declaredField(
        owner: Class<*>,
        name: String,
    ): Field = owner.getDeclaredField(name).apply { isAccessible = true }

    /** Window 2 s (cap 8 s), first re-subscribe 500 ms after the CLOSED; the window's clock is the scheduler's when settable. */
    private fun windowConfig(): Any {
        val config = RelayHealthApi.config(listOf(URL_B, URL_C), AtomicLong(CLOCK0))
        RelayHealthApi.setProp(config, "resubDebounceMs", WINDOW_MS)
        RelayHealthApi.setProp(config, "resubMaxMs", 4 * WINDOW_MS)
        RelayHealthApi.setProp(config, "resubBackoffMs", BACKOFF_MS)
        runCatching { RelayHealthApi.setProp(config, "resubNowMs", { scheduler.currentTime }) }
        return config
    }

    private companion object {
        const val WINDOW_MS = 2_000L
        const val BACKOFF_MS = 500L
        const val CLOCK0 = 1_790_000_000_000L
        const val NANOS_PER_MS = 1_000_000L
        const val URL_OWN = "ws://127.0.0.1:9/zc482-own"
        const val URL_B = "ws://127.0.0.1:9/zc482-b"
        const val URL_C = "ws://127.0.0.1:9/zc482-c"
        val FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("d".repeat(64)))
    }
}
