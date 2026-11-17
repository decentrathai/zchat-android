package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.RelayHealthApi.HOUR
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
 * RELAY-HEALTH R6 (fix-all 2.13.0, train T5; W-15 item 4; SPEC.md §3.6): our wraps are re-sent until ZChat's relay
 * and the other active relays hold them.
 *
 * At HEAD a wrap that only relay.primal.net accepted (ZChat's relay and nos.lol unreachable at send time) is "sent" (one
 * readable ack) and stays on primal alone: a 2.12.4 friend reads only ZChat's relay and nos.lol, so it never arrives.
 * Decision W-15, item 4: "our sent messages are re-sent for up to 24 h until three relays hold them". Wanted: an
 * in-memory outbox of our wraps with at least one readable ack; on each tick and right after a relay's first EOSE on a
 * new socket, the SAME json (same event id) goes to each active, working relay that has not acknowledged it; never
 * twice to a relay that did; never an expiration-tagged wrap (call signals); nothing 24 h after the first publish,
 * whatever its created_at; never to a relay that is not active. Local relays; a fake clock for the 24 h.
 */
class OutboxRepublishesUntilOurRelayAndThreeCopiesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null
    private val clock = AtomicLong(1_790_000_000_000L)

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

    private class Fixture(
        val own: HealthRelay,
        val primal: HealthRelay,
        val nosLol: HealthRelay,
        val spare: HealthRelay,
        val pool: NostrRelayPool,
    )

    /** ZChat's relay and nos.lol unreachable (502) at start; primal and a spare catalog relay up. */
    private suspend fun ownAndNosLolDown(): Fixture {
        val (own, urlOwn) = relay(HealthRelay.Mode.DOWN_502)
        val (primal, urlPrimal) = relay()
        val (nosLol, urlNosLol) = relay(HealthRelay.Mode.DOWN_502)
        val (spare, urlSpare) = relay()
        val p =
            RelayHealthApi
                .pool(
                    listOf(urlOwn, urlPrimal, urlNosLol),
                    scope,
                    null,
                    RelayHealthApi.config(listOf(urlPrimal, urlNosLol, urlSpare), clock),
                    RelayHealthApi.CountingSetStore().proxy,
                ).also { pool = it }
        p.start()
        p.subscribe(INBOX_FILTER) { }
        assertTrue(HealthRelay.awaitTrue { primal.inboxReqs().isNotEmpty() }, "fixture: primal answered")
        delay(300)
        return Fixture(own, primal, nosLol, spare, p)
    }

    /** Brings [relay] back and waits until the pool has re-subscribed there (its first EOSE on the new socket). */
    private suspend fun comeBack(relay: HealthRelay) {
        relay.mode = HealthRelay.Mode.SERVES
        assertTrue(HealthRelay.awaitTrue(30_000) { relay.inboxReqs().isNotEmpty() }, "fixture: the relay came back")
    }

    @Test
    fun `a wrap only primal accepted reaches ZChat's relay and nos lol once each when they are back`() =
        runBlocking {
            val f = ownAndNosLolDown()
            val wrap = HealthRelay.event("synthetic-r6-primal-only", 1_790_000_000L)

            assertEquals(1, f.pool.publish(wrap), "fixture: only primal acknowledged it")

            comeBack(f.own)
            assertTrue(HealthRelay.awaitTrue { f.own.publishCount(ID_1) == 1 }, "ZChat's relay never got the wrap")
            comeBack(f.nosLol)
            assertTrue(HealthRelay.awaitTrue { f.nosLol.publishCount(ID_1) == 1 }, "nos.lol never got the wrap")
            RelayHealthApi.tick(f.pool)
            RelayHealthApi.tick(f.pool)
            delay(500)
            assertEquals(1, f.own.publishCount(ID_1), "re-sent to ZChat's relay more than once")
            assertEquals(1, f.nosLol.publishCount(ID_1), "re-sent to nos.lol more than once")
            assertEquals(1, f.primal.publishCount(ID_1), "re-sent to primal, which already held it")
            assertEquals(0, f.spare.publishCount(ID_1), "sent to a catalog relay that is not active")
        }

    @Test
    fun `a wrap ZChat's relay and primal hold goes only to nos lol, once it is back`() =
        runBlocking {
            val f = ownAndNosLolDown()
            comeBack(f.own)
            val wrap = HealthRelay.event(ID_2, 1_790_000_000L)
            assertEquals(2, f.pool.publish(wrap), "fixture: ZChat's relay and primal acknowledged it")

            RelayHealthApi.tick(f.pool)
            delay(300)
            assertEquals(1, f.own.publishCount(ID_2), "re-sent to a relay that acknowledged it")
            assertEquals(1, f.primal.publishCount(ID_2), "re-sent to a relay that acknowledged it")

            comeBack(f.nosLol)
            assertTrue(HealthRelay.awaitTrue { f.nosLol.publishCount(ID_2) == 1 }, "nos.lol never got the wrap")
            RelayHealthApi.tick(f.pool)
            delay(300)
            assertEquals(1, f.nosLol.publishCount(ID_2))
            assertEquals(1, f.own.publishCount(ID_2))
        }

    @Test
    fun `an expiration-tagged wrap (a call signal) is never re-sent`() =
        runBlocking {
            val f = ownAndNosLolDown()
            val wrap = HealthRelay.event(ID_3, 1_790_000_000L, "[\"p\",\"${"c".repeat(64)}\"],[\"expiration\",\"1790000060\"]")
            assertEquals(1, f.pool.publish(wrap))

            comeBack(f.own)
            comeBack(f.nosLol)
            RelayHealthApi.tick(f.pool)
            delay(500)
            assertEquals(0, f.own.publishCount(ID_3))
            assertEquals(0, f.nosLol.publishCount(ID_3))
        }

    @Test
    fun `nothing is re-sent 24 hours after the first publish, whatever the wrap's created_at`() =
        runBlocking {
            val f = ownAndNosLolDown()
            // created_at "now" (a wrap made this second), yet published 24 h + 1 min ago by the local clock.
            val wrap = HealthRelay.event(ID_4, System.currentTimeMillis() / 1000)
            assertEquals(1, f.pool.publish(wrap))
            clock.addAndGet(24 * HOUR + MIN)

            comeBack(f.own)
            RelayHealthApi.tick(f.pool)
            delay(500)
            assertEquals(0, f.own.publishCount(ID_4), "a wrap was re-sent more than 24 h after its first publish")
        }

    @Test
    fun `a wrap no relay acknowledged is not kept - the send failed and its Retry makes a new one`() =
        runBlocking {
            val f = ownAndNosLolDown()
            f.primal.mode = HealthRelay.Mode.WRITE_BLOCKED
            assertEquals(0, f.pool.publish(HealthRelay.event(ID_5, 1_790_000_000L)), "fixture: no readable ack")
            f.primal.mode = HealthRelay.Mode.SERVES

            comeBack(f.own)
            RelayHealthApi.tick(f.pool)
            delay(500)
            assertEquals(0, f.own.publishCount(ID_5), "a failed send was re-sent: with its Retry it would arrive twice")
        }

    private companion object {
        const val ID_1 = "synthetic-r6-primal-only"
        const val ID_2 = "synthetic-r6-own-and-primal"
        const val ID_3 = "synthetic-r6-call-signal"
        const val ID_4 = "synthetic-r6-older-than-a-day"
        const val ID_5 = "synthetic-r6-no-ack"
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("d".repeat(64)))
    }
}
