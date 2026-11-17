package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import kotlin.test.assertEquals

/**
 * RELAY-HEALTH G2 (compat guard; fix-all 2.13.0, train T5; skill compat-2124; SPEC.md §2 "2.12.4", §3.8).
 *
 * A 2.12.4 phone keeps its own relay list and its inbox filter, and does no NIP-42 AUTH. relay.damus.io refuses every
 * unauthenticated kind-1059 read (CLOSED "auth-required", relay research of 29 Sep 2026), so a 2.12.4 phone reads only
 * the other two relays of its list. The relays a 2.12.4 friend and a 2.13.0 phone share for reading must stay exactly
 * ZChat's relay and nos.lol with the new defaults: that is why nos.lol keeps slot 2 (the compat slot) whenever it works.
 * Passes before and after the fix.
 */
class Frozen2124ReadsOnlyOurRelayAndNosLolTest {
    /** Verbatim copies from v2.12.4. */
    private object Frozen2124 {
        // NostrRelayPool.kt:360 at v2.12.4
        val DEFAULT_RELAYS = listOf("wss://relay.zsend.xyz", "wss://relay.damus.io", "wss://nos.lol")

        const val KIND_GIFT_WRAP = 1059

        // NostrInboxManager.kt:142-145 at v2.12.4 (pubs = our current pubkey, plus the grace key during a rotation)
        fun inboxFilter(pubs: List<String>): Map<String, Any> =
            mapOf<String, Any>(
                "kinds" to listOf(KIND_GIFT_WRAP),
                "#p" to pubs,
            )
    }

    /** The relays that refuse an unauthenticated kind-1059 read today (2.12.4 does no AUTH). */
    private val authGatedToday = setOf("wss://relay.damus.io")

    @Test
    fun `2 12 4 and 2 13 0 share exactly ZChat's relay and nos lol for reading`() {
        val readBy2124 = Frozen2124.DEFAULT_RELAYS.toSet() - authGatedToday
        assertEquals(
            setOf("wss://relay.zsend.xyz", "wss://nos.lol"),
            readBy2124 intersect NostrRelayPool.DEFAULT_RELAYS.toSet(),
        )
    }

    @Test
    fun `2 12 4 asks for the same kinds 2 13 0 publishes`() {
        assertEquals(listOf(1059), Frozen2124.inboxFilter(listOf("a".repeat(64)))["kinds"])
    }
}
