package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH G3 (guard; fix-all 2.13.0, train T5; SPEC.md §3.2).
 *
 * A relay that gates kind-1059 reads answers a REQ for exactly kinds [1059] with CLOSED "auth-required", which the pool
 * sees and acts on (READ_BLOCKED, then a swap). A REQ that mixes kinds is answered with an EOSE and the gift-wraps are
 * silently left out (divine-mobile #9467/#9468): the pool would think the relay works while it serves nothing. So the
 * inbox REQ stays exactly `{"kinds":[1059],"#p":[…]}` — no other kind, no limit (and a `since` only from the since
 * line). Passes before and after the fix.
 */
class InboxFilterIsKind1059OnlyTest {
    private val relay = HealthRelay()
    private var inbox: NostrInboxManager? = null

    @After
    fun tearDown() {
        runCatching { inbox?.stop() }
        relay.shutdown()
    }

    @Test
    fun `the inbox REQ asks for kind 1059 and our key only`() =
        runBlocking {
            val url = relay.start()
            val identity = NOSTRIdentity.fromSeed(Random.nextBytes(64))
            val manager = NostrInboxManager(NostrRelayPool(listOf(url))).also { inbox = it }
            manager.start(identity)
            assertTrue(HealthRelay.awaitTrue { relay.reqFrames.isNotEmpty() }, "the inbox sent no REQ")

            val frame = JSONArray(relay.reqFrames.first())
            assertEquals("REQ", frame.getString(0))
            assertEquals(3, frame.length(), "one filter per REQ")
            val filter = frame.getJSONObject(2)
            assertEquals(setOf("kinds", "#p"), filter.keys().asSequence().toSet(), "inbox filter: $filter")
            assertEquals(1, filter.getJSONArray("kinds").length())
            assertEquals(1059, filter.getJSONArray("kinds").getInt(0))
            val pubs = filter.getJSONArray("#p")
            assertEquals(1, pubs.length())
            assertEquals(identity.publicKey.joinToString("") { "%02x".format(it) }, pubs.getString(0))
        }
}
