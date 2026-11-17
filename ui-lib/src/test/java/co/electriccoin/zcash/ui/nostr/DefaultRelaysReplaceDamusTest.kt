package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R1 (fix-all 2.13.0, train T5; W-11, W-15; SPEC.md §1.4, §3.1).
 *
 * relay.damus.io now CLOSES every kind-1059 read with "auth-required" (relay research of 29 Sep, four rounds), so no
 * ZChat version reads anything from it, while 2.13.0 still publishes every gift-wrap there. Decision W-15:
 * relay.primal.net takes its place, and nothing is published to damus any more.
 *
 * Wanted: DEFAULT_RELAYS = [relay.zsend.xyz (OWN_RELAY, slot 0), relay.primal.net (slot 1), nos.lol (slot 2, the only
 * public relay 2.12.4 reads)]; a compiled-in catalog of public relays, in the researched order, wss:// only, short
 * enough for a ZBOOT or KEX relay field (80 characters), without duplicates. Nothing names damus.
 */
class DefaultRelaysReplaceDamusTest {
    @Test
    fun `the default relays are ZChat's relay, relay primal net and nos lol, in that order`() {
        assertEquals(
            listOf("wss://relay.zsend.xyz", "wss://relay.primal.net", "wss://nos.lol"),
            NostrRelayPool.DEFAULT_RELAYS,
        )
        assertFalse(NostrRelayPool.DEFAULT_RELAYS.any { "damus" in it }, "relay.damus.io is still a default relay")
    }

    @Test
    fun `OWN_RELAY is ZChat's relay and the first default`() {
        assertEquals("wss://relay.zsend.xyz", RelayHealthApi.ownRelay())
        assertEquals(RelayHealthApi.ownRelay(), NostrRelayPool.DEFAULT_RELAYS.first())
    }

    @Test
    fun `the catalog is the researched public relays in order, wss only, short and without duplicates`() {
        val catalog = RelayHealthApi.catalog()
        assertEquals(
            listOf(
                "wss://relay.primal.net",
                "wss://nos.lol",
                "wss://nostr.mom",
                "wss://relay.nos.social",
                "wss://nostr.oxtr.dev",
                "wss://offchain.pub",
                "wss://nostr-pub.wellorder.net",
            ),
            catalog,
        )
        assertEquals(catalog.size, catalog.toSet().size, "the catalog has a duplicate")
        catalog.forEach { url ->
            assertTrue(url.startsWith("wss://"), "$url is not wss://")
            assertTrue(url.length <= RELAY_FIELD_MAX, "$url is longer than a relay field may be")
            assertFalse("damus" in url, "$url is relay.damus.io")
        }
        assertFalse(RelayHealthApi.ownRelay() in catalog, "ZChat's relay is pinned in slot 0, never a catalog relay")
        assertEquals(NostrRelayPool.DEFAULT_RELAYS.drop(1), catalog.take(2), "slots 1 and 2 start as the catalog's first two")
    }

    private companion object {
        /** ZBootMessage and E2EEncryption refuse a relay url longer than this. */
        const val RELAY_FIELD_MAX = 80
    }
}
