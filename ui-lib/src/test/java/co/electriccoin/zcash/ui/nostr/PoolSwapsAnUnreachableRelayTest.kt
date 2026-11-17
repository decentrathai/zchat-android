package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.RelayHealthApi.MIN
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
 * RELAY-HEALTH R3 (fix-all 2.13.0, train T5; W-11; SPEC.md §3.3, §5.1): the pool swaps a relay it cannot reach.
 *
 * At HEAD the pool is fixed at construction: a relay that answers every upgrade with 502 (nos.lol in the T1 run) is
 * retried for ever and the set stays at two working relays. Wanted: with a health config, once the relay has had no
 * successful open for the confirm time while the others answer (fresh EOSEs at the new time, else the network gate
 * holds), a tick probes the first catalog relay that is not active and swaps it in: the active set is [A, D, C], D gets
 * our subscription, a publish gets three readable acks, and the old relay gets nothing more, even once it is back.
 * Local MockWebServer relays; a fake clock for the health rules.
 */
class PoolSwapsAnUnreachableRelayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null

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

    @Test
    fun `a relay with no successful open for 10 minutes while the others answer is swapped for the first catalog relay`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay(HealthRelay.Mode.DOWN_502)
            val (c, urlC) = relay()
            val (d, urlD) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val store = RelayHealthApi.CountingSetStore()
            val p =
                RelayHealthApi
                    .pool(
                        listOf(urlA, urlB, urlC),
                        scope,
                        null,
                        RelayHealthApi.config(listOf(urlB, urlC, urlD), clock),
                        store.proxy,
                    ).also { pool = it }
            p.start()
            p.subscribe(INBOX_FILTER) { }
            val answered = HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() }
            assertTrue(answered, "fixture: A and C answered our subscription")
            delay(1_500) // B's upgrade was refused at least once

            RelayHealthApi.tick(p)
            assertEquals(listOf(urlA, urlB, urlC), RelayHealthApi.activeUrls(p), "no swap before the confirm time")

            clock.addAndGet(11 * MIN)
            val reqsBefore = a.inboxReqs().size to c.inboxReqs().size
            p.redeliverBacklog() // the watchdog's fresh REQs: A and C answer at the new time
            val fresh =
                HealthRelay.awaitTrue {
                    a.inboxReqs().size > reqsBefore.first && c.inboxReqs().size > reqsBefore.second
                }
            assertTrue(fresh, "fixture: A and C gave fresh EOSEs")
            delay(300)

            RelayHealthApi.tick(p)
            assertEquals(listOf(urlA, urlD, urlC), RelayHealthApi.activeUrls(p), "slot 1 was not swapped to D")
            assertTrue(d.probeReqs().isNotEmpty(), "D was probed before it was used")
            assertTrue(HealthRelay.awaitTrue { d.inboxReqs().isNotEmpty() }, "D got our subscription")
            assertEquals(1, store.saves.get(), "the swap is written once")

            // The old relay is back: it gets nothing, it is closed for good.
            b.mode = HealthRelay.Mode.SERVES
            val acks = p.publish(HealthRelay.event("synthetic-r3-after-swap", 1_790_000_000L))
            assertEquals(3, acks, "A, D and C each acknowledged it")
            delay(2_000)
            assertEquals(0, b.upgrades.get(), "the swapped-out relay was contacted again")
            assertTrue(b.reqFrames.isEmpty() && b.publishedIds.isEmpty())
        }

    private companion object {
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("a".repeat(64)))
    }
}
