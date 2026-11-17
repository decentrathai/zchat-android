package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * ZC-501 (fix-all 2.13.0, train T6): NostrRelayPool's re-subscribe rules (ZC-406) on virtual time, for tests that need a
 * socket rebuild or a CLOSED at an exact moment. The helpers of ResubscribeWindowRunsOnTheWaitClockTest made reusable.
 *
 * The pool's coroutines run on a virtual-time scheduler; the pool is not started and has no socket. One relay slot is
 * wired and marked connected by reflection (`relays`, `client`, `connected`, `wireClient`: a rename fails loudly) and
 * its client is handed CLOSED frames. A re-subscribe shows as a client holding the read again: each client has a
 * stand-in socket that takes every REQ (ZC-761: a client no longer keeps a read whose REQ was refused). Window 2 s (cap 8 s), first
 * re-subscribe 500 ms after the CLOSED, slot 0's read-block retry 2 s; the window's clock is the scheduler's.
 * Synthetic urls and filters. Call [close] when the test ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ResubscribeVirtualTimeFixture(
    slot: Int = 1,
    private val filters: List<Map<String, Any>> = listOf(readFilter("d")),
) {
    val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val made = mutableListOf<RelayClient>()
    val pool: NostrRelayPool =
        RelayHealthApi.pool(URLS, scope, null, config(), RelayHealthApi.CountingSetStore().proxy)
    private val state: Any

    /** The pool's first subscription: the read the cases close and count. */
    val filter: Map<String, Any> get() = filters.first()

    /** The slot's socket in use now. */
    val client: RelayClient get() = field(state.javaClass, "client").get(state) as RelayClient

    init {
        filters.forEach { pool.subscribe(it) { } } // no relay is connected yet: the pool keeps the reads, sends nothing
        state = checkNotNull((field(NostrRelayPool::class.java, "relays").get(pool) as List<*>)[slot])
        setConnected(true)
        wire()
        client.withStandInSocket() // ZC-761: a read is held only once its REQ went out
        made += client
    }

    /**
     * As the relay's CLOSED of [filter] on [client]: the client holds the read first (as once its REQ is out), then
     * reads the frame; what the pool does at once has run when this returns.
     */
    fun closeRead(
        client: RelayClient,
        reason: String = RATE_LIMITED,
        filter: Map<String, Any> = this.filter,
    ) {
        if (!client.holds(filter)) runCatching { client.subscribe(filter) { } } // sent to the stand-in socket
        val subId = entries(client).first { it.value.first === filter }.key
        RelayClient::class.java
            .getDeclaredMethod("dispatchFrame", String::class.java)
            .apply { isAccessible = true }
            .invoke(client, "[\"CLOSED\",\"$subId\",\"$reason\"]")
        scheduler.runCurrent()
    }

    /**
     * What launchConnect does when a socket drops: not connected, the old client closed, a new RelayClient wired as the
     * slot's client, then (with [connected]) connected and (with [replay]) every pool subscription asked once on it.
     * @return the new client.
     */
    fun rebuild(
        connected: Boolean = true,
        replay: Boolean = true,
    ): RelayClient {
        setConnected(false)
        runCatching { client.close() }
        val fresh = RelayClient(URLS[field(state.javaClass, "index").getInt(state)]).withStandInSocket()
        made += fresh
        field(state.javaClass, "client").set(state, fresh)
        wire()
        setConnected(connected)
        if (replay) filters.forEach { f -> runCatching { fresh.subscribe(f) { } } } // sent to the stand-in socket
        return fresh
    }

    /** How many subscriptions [client] holds for [filter] (the pool's own filter object). */
    fun held(
        client: RelayClient,
        filter: Map<String, Any> = this.filter,
    ): Int = entries(client).count { it.value.first === filter }

    /** Runs the pool up to and including the absolute virtual time [ms]. */
    fun advanceTo(ms: Long) {
        scheduler.advanceTimeBy(ms - scheduler.currentTime)
        scheduler.runCurrent()
    }

    /** Whether the pool has marked the slot's relay read-blocked. */
    fun readBlocked(): Boolean = field(state.javaClass, "readBlocked").getBoolean(state)

    fun close() {
        runCatching { pool.stop() }
        scope.cancel()
        made.forEach { runCatching { it.close() } }
    }

    private fun setConnected(value: Boolean) {
        field(state.javaClass, "connected").setBoolean(state, value)
    }

    private fun wire() {
        NostrRelayPool::class.java
            .getDeclaredMethod("wireClient", state.javaClass)
            .apply { isAccessible = true }
            .invoke(pool, state)
    }

    @Suppress("UNCHECKED_CAST")
    private fun entries(client: RelayClient): Set<Map.Entry<String, Pair<Map<String, Any>, (String) -> Unit>>> =
        (
            field(RelayClient::class.java, "subscriptions").get(client)
                as Map<String, Pair<Map<String, Any>, (String) -> Unit>>
        ).entries

    private fun field(
        owner: Class<*>,
        name: String,
    ): Field = owner.getDeclaredField(name).apply { isAccessible = true }

    /** The numbers by name; a property the config does not have yet is left out (the case then fails on behaviour). */
    private fun config(): Any {
        val config = RelayHealthApi.config(listOf(URLS[1], URLS[2]), AtomicLong(CLOCK0))
        val clock: () -> Long = { scheduler.currentTime }
        listOf(
            "resubDebounceMs" to WINDOW_MS,
            "resubMaxMs" to 4 * WINDOW_MS,
            "resubBackoffMs" to BACKOFF_MS,
            "ownReadRetryMs" to OWN_READ_RETRY_MS,
            "resubNowMs" to clock,
        ).forEach { (name, value) -> runCatching { RelayHealthApi.setProp(config, name, value) } }
        return config
    }

    companion object {
        const val WINDOW_MS = 2_000L
        const val BACKOFF_MS = 500L
        const val OWN_READ_RETRY_MS = 2_000L
        const val RATE_LIMITED = "rate-limited: slow down there chief"
        private const val CLOCK0 = 1_790_000_000_000L
        private val URLS =
            listOf("ws://127.0.0.1:9/zc501-own", "ws://127.0.0.1:9/zc501-b", "ws://127.0.0.1:9/zc501-c")

        /** A synthetic inbox filter (its own object: the pool and the clients are asked by identity). */
        fun readFilter(hexDigit: String): Map<String, Any> =
            mapOf("kinds" to listOf(1059), "#p" to listOf(hexDigit.repeat(64)))
    }
}
