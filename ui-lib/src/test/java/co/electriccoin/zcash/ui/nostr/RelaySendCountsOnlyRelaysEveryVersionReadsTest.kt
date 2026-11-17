package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.hex
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.readableAcks
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
 * ZC-393 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.1-2.3, §2.6): a relay send counts as delivered only when a relay
 * every ZChat version reads holds it.
 *
 * At HEAD NostrInboxManager.send returns `SendResult(acks = pool.publish(…))`, and publish counts every readable OK,
 * whichever relay gave it. With RELAY-HEALTH the set is [ZChat's relay, relay.primal.net, nos.lol] (a catalog fallback
 * can hold slot 1 or 2), and 2.12.4 reads only ZChat's relay and nos.lol (G2). So a send only primal (or a fallback)
 * took returns acks 1 and the chat shows SENT, while a 2.12.4 friend never gets it once the in-memory outbox is gone.
 *
 * Wanted: `send().acks` counts readable OKs from ZChat's relay (`relayUrls[0]`) or nos.lol (`relayUrls[2]`, in whichever
 * slot it sits); a new `readableAcks` keeps today's count (any readable relay); `pool.publish()` keeps its meaning; a pool
 * without relay health counts both the same. Local MockWebServer relays (HealthRelay), the real NostrInboxManager and
 * Nip17 wraps; keys made per run.
 */
class RelaySendCountsOnlyRelaysEveryVersionReadsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private val inboxes = mutableListOf<SharedRelayCountApi.Inbox>()
    private val pools = mutableListOf<NostrRelayPool>()
    private val clock = AtomicLong(1_790_000_000_000L)
    private val recipient = hex(SharedRelayCountApi.identity().publicKey)

    @After
    fun tearDown() {
        inboxes.forEach { it.stop() }
        pools.forEach { runCatching { it.stop() } }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    private class Relays(
        val own: HealthRelay,
        val primal: HealthRelay,
        val nosLol: HealthRelay,
        val fallback: HealthRelay,
        val urls: List<String>,
        val fallbackUrl: String,
    )

    private fun relays(
        own: HealthRelay.Mode,
        primal: HealthRelay.Mode,
        nosLol: HealthRelay.Mode,
    ): Relays {
        val (o, urlOwn) = relay(own)
        val (p, urlPrimal) = relay(primal)
        val (n, urlNosLol) = relay(nosLol)
        val (f, urlFallback) = relay()
        return Relays(o, p, n, f, listOf(urlOwn, urlPrimal, urlNosLol), urlFallback)
    }

    /** A health pool on [r]'s defaults (catalog: primal, nos.lol, the fallback), started by a real inbox. */
    private suspend fun inbox(
        r: Relays,
        saved: Any? = null,
        live: List<HealthRelay>,
    ): SharedRelayCountApi.Inbox {
        val pool =
            RelayHealthApi.pool(
                r.urls,
                scope,
                null,
                RelayHealthApi.config(listOf(r.urls[1], r.urls[2], r.fallbackUrl), clock),
                RelayHealthApi.CountingSetStore(saved).proxy,
            )
        val inbox = SharedRelayCountApi.Inbox(pool, SharedRelayCountApi.identity(), scope).also { inboxes += it }
        assertTrue(HealthRelay.awaitTrue { live.all { it.inboxReqs().isNotEmpty() } }, "fixture: the serving relays got our REQ")
        delay(500)
        return inbox
    }

    @Test
    fun `a send only primal took is not delivered, and publish keeps counting it`() =
        runBlocking {
            val r = relays(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES, HealthRelay.Mode.DOWN_502)
            val inbox = inbox(r, live = listOf(r.primal))

            val sent = inbox.manager.send("synthetic zc393 primal only", recipient)

            assertEquals(1, r.primal.publishedIds.size, "fixture: primal took the wrap")
            assertEquals(0, sent.acks, "a send only primal holds was counted as delivered; 2.12.4 never reads primal")
            assertEquals(1, readableAcks(sent), "readableAcks keeps today's count (any readable relay)")
            assertEquals(
                1,
                inbox.pool.publish(HealthRelay.event("synthetic-zc393-another-wrap", 1_790_000_000L)),
                "pool.publish() changed its meaning",
            )
        }

    @Test
    fun `ZChat's relay alone counts as delivered`() =
        runBlocking {
            val r = relays(HealthRelay.Mode.SERVES, HealthRelay.Mode.DOWN_502, HealthRelay.Mode.DOWN_502)
            val inbox = inbox(r, live = listOf(r.own))

            val sent = inbox.manager.send("synthetic zc393 own only", recipient)

            assertEquals(1, sent.acks)
            assertEquals(1, readableAcks(sent))
        }

    @Test
    fun `nos lol alone counts as delivered`() =
        runBlocking {
            val r = relays(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES)
            val inbox = inbox(r, live = listOf(r.nosLol))

            val sent = inbox.manager.send("synthetic zc393 nos lol only", recipient)

            assertEquals(1, sent.acks)
            assertEquals(1, readableAcks(sent))
        }

    @Test
    fun `all three relays give two shared and three readable`() =
        runBlocking {
            val r = relays(HealthRelay.Mode.SERVES, HealthRelay.Mode.SERVES, HealthRelay.Mode.SERVES)
            val inbox = inbox(r, live = listOf(r.own, r.primal, r.nosLol))

            val sent = inbox.manager.send("synthetic zc393 all three", recipient)

            assertEquals(2, sent.acks, "ZChat's relay and nos.lol are the relays every ZChat version reads")
            assertEquals(3, readableAcks(sent))
        }

    @Test
    fun `a fallback in the compat slot does not count, nos lol is the compat relay`() =
        runBlocking {
            val r = relays(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES, HealthRelay.Mode.DOWN_502)
            // Slot 2 was swapped to the fallback earlier (nos.lol unreachable); the saved set restores it.
            val saved = RelayHealthApi.relaySetState(r.urls[1], r.fallbackUrl, compatOutSinceMs = clock.get())
            val inbox = inbox(r, saved, live = listOf(r.primal, r.fallback))
            assertEquals(listOf(r.urls[0], r.urls[1], r.fallbackUrl), RelayHealthApi.activeUrls(inbox.pool), "fixture: the set")

            val sent = inbox.manager.send("synthetic zc393 primal and fallback", recipient)

            assertEquals(1, r.fallback.publishedIds.size, "fixture: the fallback took the wrap")
            assertEquals(0, sent.acks, "a fallback in slot 2 was counted as a relay every ZChat version reads")
            assertEquals(2, readableAcks(sent))
        }

    @Test
    fun `an auth-gated ZChat relay that OKs the wrap counts in neither`() =
        runBlocking {
            val r = relays(HealthRelay.Mode.AUTH_GATED, HealthRelay.Mode.SERVES, HealthRelay.Mode.DOWN_502)
            val inbox = inbox(r, live = listOf(r.primal))
            assertTrue(HealthRelay.awaitTrue { r.own.reqFrames.isNotEmpty() }, "fixture: ZChat's relay refused our REQ")
            delay(500)

            val sent = inbox.manager.send("synthetic zc393 auth gated own", recipient)

            assertEquals(1, r.own.publishedIds.size, "fixture: the auth-gated relay got the wrap")
            assertEquals(0, sent.acks, "an OK from a relay that serves us nothing was counted")
            assertEquals(1, readableAcks(sent))
        }

    @Test
    fun `a pool without relay health counts both the same`() =
        runBlocking {
            val r = relays(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES, HealthRelay.Mode.SERVES)
            val pool = NostrRelayPool(r.urls, scope).also { pools += it }
            val inbox = SharedRelayCountApi.Inbox(pool, SharedRelayCountApi.identity(), scope).also { inboxes += it }
            assertTrue(HealthRelay.awaitTrue { r.primal.inboxReqs().isNotEmpty() && r.nosLol.inboxReqs().isNotEmpty() })
            delay(500)

            val sent = inbox.manager.send("synthetic zc393 no health", recipient)

            assertEquals(2, readableAcks(sent))
            assertEquals(readableAcks(sent), sent.acks, "without relay health, acks and readableAcks are one count")
        }

    @Test
    fun `the counted relays of the default list are the ones 2 12 4 and 2 13 0 both read`() {
        // Frozen2124ReadsOnlyOurRelayAndNosLolTest (G2): NostrRelayPool.kt:360 at v2.12.4, less the auth-gated damus.
        val readBy2124 = setOf("wss://relay.zsend.xyz", "wss://relay.damus.io", "wss://nos.lol") - setOf("wss://relay.damus.io")
        assertEquals(
            readBy2124 intersect NostrRelayPool.DEFAULT_RELAYS.toSet(),
            SharedRelayCountApi.sharedReadRelays(NostrRelayPool.DEFAULT_RELAYS),
        )
    }
}
