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

/**
 * T1b-2 (train T1b, finding 6; INVESTIGATION.md §3/6, fix item 3): "2 relay ack" meant one readable copy.
 *
 * relay.damus.io was in DEFAULT_RELAYS until RELAY-HEALTH (2.13.0 replaced it with relay.primal.net, W-15). It closes
 * every one of our subscriptions with "auth-required" (we do not do NIP-42 AUTH), so it never serves our gift-wraps: 0
 * events in the whole T1 capture. Any relay can start doing the same, so the rule below still guards every relay. It still acknowledges our
 * publishes, and NostrRelayPool.publish counts that OK, so with nos.lol down (502) the sender logged "sent FREE (2 relay
 * ack)" for group memos whose only readable copy was on relay.zsend.xyz, and 4 of 5 were lost.
 *
 * Wanted: a relay that closed our read subscription with auth-required is read-blocked for the session, and its OKs
 * are not counted as delivery acks. With no readable ack the send reports 0, which every caller already treats as not
 * delivered. Local MockWebServer relays only; [ScriptedRelay.DOWN_RELAY] plays nos.lol at 502.
 */
class AuthGatedRelayAckIsNotDeliveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<ScriptedRelay>()
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        runCatching { pool?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(mode: ScriptedRelay.Mode): Pair<ScriptedRelay, String> {
        val relay = ScriptedRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    /** Starts a pool on [urls], subscribes as the inbox does, and waits until every live relay has answered the REQ. */
    private suspend fun subscribedPool(
        urls: List<String>,
        live: List<ScriptedRelay>,
    ): NostrRelayPool {
        val p = NostrRelayPool(urls, scope).also { pool = it }
        p.start()
        p.subscribe(FILTER) { }
        assertTrue("fixture sanity: every live relay got our REQ", ScriptedRelay.awaitTrue { live.all { it.reqIds.isNotEmpty() } })
        delay(500) // the relays' answers (EOSE, or AUTH + CLOSED) reach the pool
        return p
    }

    @Test
    fun `a relay that refuses to serve us without AUTH is not counted as a delivery`() =
        runBlocking {
            val (damus, damusUrl) = relay(ScriptedRelay.Mode.AUTH_GATED)
            val p = subscribedPool(listOf(damusUrl, ScriptedRelay.DOWN_RELAY), listOf(damus))

            val acks = p.publish(ScriptedRelay.event("synthetic-memo-1"))

            assertTrue(
                "fixture sanity: the auth-gated relay still took and acknowledged the event",
                "synthetic-memo-1" in damus.publishedIds
            )
            assertEquals(
                "an OK from a relay we can't read from was counted as a delivery (the T1 \"2 relay ack\" with one readable copy)",
                0,
                acks,
            )
        }

    @Test
    fun `with our own relay and an auth-gated one, only our own relay's ack counts`() =
        runBlocking {
            val (own, ownUrl) = relay(ScriptedRelay.Mode.OWN_RELAY)
            val (damus, damusUrl) = relay(ScriptedRelay.Mode.AUTH_GATED)
            val p = subscribedPool(listOf(ownUrl, damusUrl, ScriptedRelay.DOWN_RELAY), listOf(own, damus))

            val acks = p.publish(ScriptedRelay.event("synthetic-memo-2"))

            assertTrue(
                "fixture sanity: both relays took the event",
                "synthetic-memo-2" in own.publishedIds && "synthetic-memo-2" in damus.publishedIds
            )
            assertEquals("one readable copy is one ack", 1, acks)
        }

    @Test
    fun `control - a readable relay's ack still counts`() =
        runBlocking {
            val (own, ownUrl) = relay(ScriptedRelay.Mode.OWN_RELAY)
            val p = subscribedPool(listOf(ownUrl, ScriptedRelay.DOWN_RELAY), listOf(own))

            assertEquals(1, p.publish(ScriptedRelay.event("synthetic-memo-3")))
        }

    private companion object {
        val FILTER: Map<String, Any> = mapOf("kinds" to listOf(1059), "#p" to listOf("feedface"))
    }
}
