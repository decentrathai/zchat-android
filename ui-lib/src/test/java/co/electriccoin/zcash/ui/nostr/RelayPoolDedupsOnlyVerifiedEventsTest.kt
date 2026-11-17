package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ZC-1414 (swarm 1 Nov, lens relay-abuse) — the relay pool dedups on the event id it reads from the UNVERIFIED JSON.
 *
 * NostrRelayPool.subscribe wraps every relay callback: parseEventId (a substring search for the first `"id":"`) and
 * seenEventIds.add(id) run before anything checks the event's id hash or Schnorr signature. A hostile relay in our set
 * that sends a junk copy carrying a real gift-wrap's id first makes the genuine, correctly signed copy from an honest
 * relay be dropped as a duplicate. NostrInboxManager then fails to decrypt the junk, logs and returns: the real message
 * is lost (the since line can settle past it).
 *
 * Wanted: an event id counts as seen only once an event with that id has passed NostrEvent.verify (or the id is given
 * back on a failure); the valid copy from the second relay reaches the consumer exactly once.
 *
 * Seam: the pool's per-subscription callback, taken from its private `subscriptions` map (no relay connects; the
 * pool's two URLs are never dialled). Every key, id and URL is synthetic.
 */
class RelayPoolDedupsOnlyVerifiedEventsTest {
    @Test
    fun `a junk copy with a real wrap's id from one relay does not suppress the valid copy from another`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val pool = NostrRelayPool(listOf(HOSTILE_RELAY, HONEST_RELAY), scope)
            val delivered = mutableListOf<String>()
            val poolId = pool.subscribe(mapOf("kinds" to listOf(1059))) { delivered += it }
            val onRelayEvent = callbackOf(pool, poolId)

            val genuine = signedWrap()
            val id = NostrEvent.parseId(genuine)
            NostrEvent.verify(genuine) // sanity: the honest copy is a valid NIP-01 event
            // The hostile relay's copy: the real id, a different body, a garbage signature.
            val junk =
                """{"id":"$id","pubkey":"${"ab".repeat(32)}","created_at":1790000000,"kind":1059,""" +
                    """"tags":[],"content":"junk","sig":"${"00".repeat(64)}"}"""

            onRelayEvent(junk) // hostile relay answers first
            onRelayEvent(genuine) // honest relay delivers the real wrap

            assertEquals(
                "the valid wrap from the honest relay was dropped as a duplicate of an unverified junk copy",
                1,
                delivered.count { it == genuine },
            )
        } finally {
            scope.cancel()
        }
    }

    /** Control, GREEN before and after: the same valid wrap from two relays reaches the consumer once. */
    @Test
    fun `control - the same valid wrap from two relays is delivered once`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val pool = NostrRelayPool(listOf(HOSTILE_RELAY, HONEST_RELAY), scope)
            val delivered = mutableListOf<String>()
            val onRelayEvent = callbackOf(pool, pool.subscribe(mapOf("kinds" to listOf(1059))) { delivered += it })
            val genuine = signedWrap()

            onRelayEvent(genuine)
            onRelayEvent(genuine)

            assertEquals(listOf(genuine), delivered)
        } finally {
            scope.cancel()
        }
    }

    private fun signedWrap(): String {
        val priv = ByteArray(32) { (0x41 + it).toByte() }
        val pub =
            fr.acinq.secp256k1.Secp256k1
                .pubkeyCreate(priv)
                .copyOfRange(1, 33)
                .joinToString("") { "%02x".format(it) }
        return NostrEvent.signAndSerialize(
            pubkeyHex = pub,
            privateKey = priv,
            createdAtSec = 1_790_000_000L,
            kind = 1059,
            tags = listOf(listOf("p", "cd".repeat(32))),
            content = "synthetic-zc1414-wrap",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun callbackOf(
        pool: NostrRelayPool,
        poolId: String,
    ): (String) -> Unit {
        val subs =
            NostrRelayPool::class.java
                .getDeclaredField("subscriptions")
                .apply { isAccessible = true }
                .get(pool) as Map<String, Pair<Map<String, Any>, (String) -> Unit>>
        return checkNotNull(subs[poolId]) { "no pool subscription $poolId" }.second
    }

    private companion object {
        const val HOSTILE_RELAY = "wss://hostile.synthetic.invalid"
        const val HONEST_RELAY = "wss://honest.synthetic.invalid"
    }
}
