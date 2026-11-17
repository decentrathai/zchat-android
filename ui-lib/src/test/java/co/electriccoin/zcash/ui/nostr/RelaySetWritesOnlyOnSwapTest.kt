package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.RelayHealthApi.MIN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R9 (fix-all 2.13.0, train T5; SPEC.md §3.5; FD-5): the relay set is written only on a swap.
 *
 * Destroy deletes every file in shared_prefs after it stops background sync (FD-5); anything that writes preferences
 * on a timer could write a file back after that sweep. So the pool writes the relay set (zchat_relay_set) only when it
 * swaps a relay (commit()), never on a tick or a probe; and a saved url that is not in the catalog puts the default
 * back (a set is never taken on trust from disk). A counting RelaySetStore; local relays; fake clock.
 */
class RelaySetWritesOnlyOnSwapTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null
    private var rounds = 0

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    /**
     * ZC-482: one health round on the fake clock. The clock moves 31 min, each relay in [serving] gets a new synthetic
     * wrap, the backlog redelivery asks every relay again, and the tick runs only once the pool has handed over the new
     * wrap of each of them (the pool records a relay's answer at the round's time before it hands a wrap over). A fixed
     * 150 ms wait stood here: an answer that came later let the tick judge a network with no answer in 5 min offline, or
     * left a REQ superseded before its EOSE, after which the health rules matched every later EOSE with the REQ of the
     * round before (late, so SUSPECT, then unavailable, and no compat probe of nos.lol again).
     */
    private suspend fun answeredRound(
        p: NostrRelayPool,
        clock: AtomicLong,
        serving: List<HealthRelay>,
        got: List<String>,
    ) {
        clock.addAndGet(31 * MIN)
        val round = ++rounds
        val ids = serving.indices.map { "synthetic-zc482-r$round-$it" }
        serving.zip(ids).forEach { (server, id) -> server.store(HealthRelay.event(id)) }
        p.redeliverBacklog()
        assertTrue(
            HealthRelay.awaitTrue { ids.all { id -> got.any { "\"id\":\"$id\"" in it } } },
            "fixture: round $round was not answered by every relay that serves us",
        )
        RelayHealthApi.tick(p)
    }

    @Test
    fun `30 ticks and probes without a swap write nothing, the swap writes once`() =
        runBlocking {
            val (own, urlOwn) = relay()
            val (primal, urlPrimal) = relay()
            val (nosLol, urlNosLol) = relay(HealthRelay.Mode.DOWN_502)
            val (mom, urlMom) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            // Saved: a fallback holds slot 2 since nos.lol left it just now, so every tick past 30 min probes nos.lol.
            val store = CountingSetStoreWith(RelayHealthApi.relaySetState(urlPrimal, urlMom, compatOutSinceMs = clock.get()))
            val p =
                RelayHealthApi
                    .pool(
                        listOf(urlOwn, urlPrimal, urlNosLol),
                        scope,
                        null,
                        RelayHealthApi.config(listOf(urlPrimal, urlNosLol, urlMom), clock),
                        store.proxy,
                    ).also { pool = it }
            p.start()
            val got = CopyOnWriteArrayList<String>()
            p.subscribe(INBOX_FILTER) { json -> got += json }
            assertEquals(listOf(urlOwn, urlPrimal, urlMom), RelayHealthApi.activeUrls(p))
            assertTrue(HealthRelay.awaitTrue { listOf(own, primal, mom).all { it.inboxReqs().isNotEmpty() } })

            repeat(30) { answeredRound(p, clock, listOf(own, primal, mom), got) }
            assertEquals(listOf(urlOwn, urlPrimal, urlMom), RelayHealthApi.activeUrls(p), "fixture: no swap")
            assertEquals(0, store.saves.get(), "a tick or a probe wrote the relay set")

            // nos.lol answers: two OK probes bring it back, which is a swap: one write.
            nosLol.mode = HealthRelay.Mode.SERVES
            repeat(2) { answeredRound(p, clock, listOf(own, primal, mom), got) }
            assertEquals(listOf(urlOwn, urlPrimal, urlNosLol), RelayHealthApi.activeUrls(p), "fixture: nos.lol is back")
            assertEquals(1, store.saves.get(), "the swap is written exactly once")
            assertEquals(urlNosLol, RelayHealthApi.prop(checkNotNull(store.stored), "slot2"))
        }

    @Test
    fun `a saved url that is not in the catalog puts the default back, and nothing is written`() =
        runBlocking {
            val (_, urlOwn) = relay()
            val (_, urlPrimal) = relay()
            val (_, urlNosLol) = relay()
            val (_, urlMom) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val catalog = listOf(urlPrimal, urlNosLol, urlMom)
            for ((slot1, slot2) in listOf(
                "wss://relay.example.invalid" to urlNosLol,
                urlPrimal to "wss://relay.example.invalid",
                urlOwn to urlNosLol,
                urlMom to urlMom,
            )) {
                val store = CountingSetStoreWith(RelayHealthApi.relaySetState(slot1, slot2))
                val p =
                    RelayHealthApi
                        .pool(listOf(urlOwn, urlPrimal, urlNosLol), scope, null, RelayHealthApi.config(catalog, clock), store.proxy)
                p.start()
                try {
                    val active = RelayHealthApi.activeUrls(p)
                    assertEquals(urlOwn, active[0], "slot 0 is always ZChat's relay")
                    assertTrue(active.drop(1).all { it in catalog }, "a saved url outside the catalog was used: $active")
                    assertEquals(active.size, active.toSet().size, "a relay holds two slots: $active")
                    if (slot1 !in catalog || slot1 == slot2) assertEquals(urlPrimal, active[1], "saved ($slot1, $slot2)")
                    if (slot2 !in catalog) assertEquals(urlNosLol, active[2], "saved ($slot1, $slot2)")
                    assertEquals(0, store.saves.get(), "reading the set wrote it")
                } finally {
                    p.stop()
                }
            }
        }

    /** RelayHealthApi.CountingSetStore that starts with a saved set. */
    private class CountingSetStoreWith(
        initial: Any,
    ) {
        private val inner = RelayHealthApi.CountingSetStore(initial)
        val proxy: Any get() = inner.proxy
        val saves get() = inner.saves
        val stored: Any? get() = inner.stored
    }

    private companion object {
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("9".repeat(64)))
    }
}
