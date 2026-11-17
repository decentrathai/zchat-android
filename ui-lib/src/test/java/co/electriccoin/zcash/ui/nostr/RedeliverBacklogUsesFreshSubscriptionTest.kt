package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T1b-2 (train T1b, findings 3 and 6; INVESTIGATION.md §3/6, fix item 2): a redelivery must really replay.
 *
 * At 00089d8d9 NostrRelayPool.redeliverBacklog and resubscribeLive call RelayClient.resubscribeAll, which re-sends the
 * REQ under the SAME subscription id. relay.zsend.xyz, our own relay and in the T1 run the only readable copy, ignores
 * a repeated REQ on a live id: in the whole T1 capture it answered its 13 fresh connections and none of the repeats. So
 * the watchdog's redelivery of a message no chat screen took (HONOR debug, 23:25:40) got nothing back, and the message
 * stayed lost until a new process opened a fresh socket.
 *
 * Wanted: the redelivery (and the liveness re-issue) closes the old subscription and opens one under a new id, so the
 * relay replays what it holds. [ScriptedRelay] in OWN_RELAY mode plays relay.zsend.xyz. Local MockWebServer only.
 */
class RedeliverBacklogUsesFreshSubscriptionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relay = ScriptedRelay(ScriptedRelay.Mode.OWN_RELAY)
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        relay.shutdown()
    }

    private fun startPool(received: MutableList<String>): NostrRelayPool {
        val url = relay.start()
        return NostrRelayPool(listOf(url), scope).also { p ->
            pool = p
            p.start()
            p.subscribe(FILTER) { json -> received += ScriptedRelay.eventId(json) }
        }
    }

    @Test
    fun `a backlog redelivery replays an event our own relay holds, which a repeated subscription never returns`() =
        runBlocking {
            relay.store(ScriptedRelay.event("synthetic-before"))
            val received = CopyOnWriteArrayList<String>()
            startPool(received)
            assertTrue(
                "fixture sanity: the first subscription got the stored event",
                ScriptedRelay.awaitTrue {
                    "synthetic-before" in
                        received
                }
            )

            // The message that reached the relay while no chat screen took it (T1: 23:20:28 and 23:23:06).
            relay.store(ScriptedRelay.event("synthetic-missed"))
            pool!!.redeliverBacklog()

            assertTrue(
                "the backlog redelivery got nothing back from our own relay: it re-sent the REQ under the live " +
                    "subscription id ${relay.reqIds}, which relay.zsend.xyz ignores",
                ScriptedRelay.awaitTrue(3_000) { "synthetic-missed" in received },
            )
        }

    @Test
    fun `the redelivery closes the old subscription, so subscriptions do not pile up on the relay`() =
        runBlocking {
            val received = CopyOnWriteArrayList<String>()
            startPool(received)
            assertTrue("fixture sanity: the subscription reached the relay", ScriptedRelay.awaitTrue { relay.reqIds.isNotEmpty() })
            delay(300)
            val before = relay.reqIds.toSet()

            pool!!.redeliverBacklog()

            assertTrue(
                "the redelivery must open a subscription under a new id: ${relay.reqIds}",
                ScriptedRelay.awaitTrue(3_000) { (relay.reqIds.toSet() - before).isNotEmpty() },
            )
            assertTrue(
                "the old subscription(s) $before must be closed: ${relay.closedIds}",
                ScriptedRelay.awaitTrue(3_000) { relay.closedIds.containsAll(before) },
            )
            assertEquals(
                "as many live subscriptions on the relay as before",
                before.size,
                (relay.reqIds.toSet() - relay.closedIds.toSet()).size,
            )
        }

    @Test
    fun `the liveness re-issue also opens a fresh subscription, so a quiet one is really revived`() =
        runBlocking {
            val received = CopyOnWriteArrayList<String>()
            startPool(received)
            assertTrue("fixture sanity: the subscription reached the relay", ScriptedRelay.awaitTrue { relay.reqIds.isNotEmpty() })
            delay(300)

            relay.store(ScriptedRelay.event("synthetic-while-quiet"))
            pool!!.resubscribeLive()

            assertTrue(
                "the liveness re-issue got nothing back from our own relay (same subscription id ${relay.reqIds})",
                ScriptedRelay.awaitTrue(3_000) { "synthetic-while-quiet" in received },
            )
        }

    private companion object {
        val FILTER: Map<String, Any> = mapOf("kinds" to listOf(1059), "#p" to listOf("feedface"))
    }
}
