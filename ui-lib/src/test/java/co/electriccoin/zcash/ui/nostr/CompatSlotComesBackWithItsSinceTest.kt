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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R7 (fix-all 2.13.0, train T5; W-15 item 2; SPEC.md §3.3 rule 7, §3.7.3): nos.lol comes back to the
 * compat slot with its kept `since`, and a message only it holds is read, once.
 *
 * nos.lol is the only public relay 2.12.4 reads, so it keeps slot 2 whenever it works. A message M sits only on nos.lol
 * (a 2.12.4 sender whose copy on ZChat's relay was lost, or a relay outage at the send). nos.lol is DOWN for longer
 * than the confirm time: slot 2 goes to a fallback, and the phone restarts (a new pool reads the saved set). nos.lol
 * answers again: after two probes in a row that succeed (30 minutes apart) it takes slot 2 back, its REQ carries the
 * `since` it had before it left (never a new one), and M reaches the reader exactly once. Local relays; fake clock.
 */
class CompatSlotComesBackWithItsSinceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private val pools = mutableListOf<NostrRelayPool>()

    @After
    fun tearDown() {
        pools.forEach { runCatching { it.stop() } }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    /** Fresh REQ → EOSE on the live relays at the fake clock's time (the watchdog's re-subscribe). */
    private suspend fun freshEoses(
        pool: NostrRelayPool,
        live: List<HealthRelay>,
    ) {
        val before = live.map { it.inboxReqs().size }
        pool.redeliverBacklog()
        val answered =
            HealthRelay.awaitTrue {
                live.indices.all { live[it].inboxReqs().size > before[it] }
            }
        assertTrue(answered, "fixture: the live relays answered")
        delay(300)
    }

    @Test
    fun `nos lol takes slot 2 back after two OK probes, with its kept since, and M arrives once`() =
        runBlocking {
            val (own, urlOwn) = relay()
            val (primal, urlPrimal) = relay()
            val (nosLol, urlNosLol) = relay(HealthRelay.Mode.DOWN_502)
            val (mom, urlMom) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val keptSince = 1_789_000_000L
            val message = HealthRelay.event(M_ID, keptSince + 10)
            nosLol.store(HealthRelay.event("synthetic-r7-before-since", keptSince - 10))
            nosLol.store(message)
            val since = RelayHealthApi.MemorySinceStore().apply { values[urlNosLol] = keptSince }
            val store = RelayHealthApi.CountingSetStore()
            val catalog = listOf(urlPrimal, urlNosLol, urlMom)

            // Session 1: nos.lol is down past the confirm time; slot 2 goes to the fallback.
            val first =
                RelayHealthApi
                    .pool(listOf(urlOwn, urlPrimal, urlNosLol), scope, since, RelayHealthApi.config(catalog, clock), store.proxy)
                    .also { pools += it }
            first.start()
            first.subscribe(INBOX_FILTER) { }
            assertTrue(HealthRelay.awaitTrue { own.inboxReqs().isNotEmpty() && primal.inboxReqs().isNotEmpty() })
            delay(1_000)
            clock.addAndGet(11 * MIN)
            freshEoses(first, listOf(own, primal))
            RelayHealthApi.tick(first)
            assertEquals(listOf(urlOwn, urlPrimal, urlMom), RelayHealthApi.activeUrls(first), "fixture: slot 2 fell back")
            assertTrue(urlNosLol in RelayHealthApi.floorSetUrls(first), "nos.lol stays in the floor set while it is out")
            first.stop()

            // Session 2 (a restart): the saved set, nos.lol reachable again.
            nosLol.mode = HealthRelay.Mode.SERVES
            val delivered = CopyOnWriteArrayList<String>()
            val second =
                RelayHealthApi
                    .pool(listOf(urlOwn, urlPrimal, urlNosLol), scope, since, RelayHealthApi.config(catalog, clock), store.proxy)
                    .also { pools += it }
            second.start()
            assertEquals(listOf(urlOwn, urlPrimal, urlMom), RelayHealthApi.activeUrls(second), "the saved set was not read")
            second.subscribe(INBOX_FILTER) { delivered += ScriptedRelay.eventId(it) }
            assertTrue(HealthRelay.awaitTrue { mom.inboxReqs().isNotEmpty() })
            assertTrue(nosLol.inboxReqs().isEmpty(), "nos.lol is not read while it is out")

            clock.addAndGet(30 * MIN)
            freshEoses(second, listOf(own, primal, mom))
            RelayHealthApi.tick(second)
            assertEquals(1, nosLol.probeReqs().size, "the first probe of nos.lol")
            assertEquals(listOf(urlOwn, urlPrimal, urlMom), RelayHealthApi.activeUrls(second), "one OK probe is not two")

            clock.addAndGet(30 * MIN)
            freshEoses(second, listOf(own, primal, mom))
            RelayHealthApi.tick(second)
            assertEquals(2, nosLol.probeReqs().size, "the second probe of nos.lol")
            assertEquals(listOf(urlOwn, urlPrimal, urlNosLol), RelayHealthApi.activeUrls(second), "nos.lol did not come back")
            val momReqs = mom.inboxReqs().size

            assertTrue(HealthRelay.awaitTrue { nosLol.inboxReqs().isNotEmpty() }, "nos.lol got our subscription")
            assertEquals(keptSince, HealthRelay.sinceOf(nosLol.inboxReqs().first()), "nos.lol came back without its kept since")
            assertTrue(HealthRelay.awaitTrue { M_ID in delivered }, "M never arrived")
            RelayHealthApi.tick(second)
            delay(500)
            assertEquals(1, delivered.count { it == M_ID }, "M arrived more than once")
            assertTrue("synthetic-r7-before-since" !in delivered, "a wrap below the kept since was served")
            assertEquals(momReqs, mom.inboxReqs().size, "the fallback is still read after nos.lol came back")
        }

    private companion object {
        const val M_ID = "synthetic-r7-message-only-on-nos-lol"
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("e".repeat(64)))
    }
}
