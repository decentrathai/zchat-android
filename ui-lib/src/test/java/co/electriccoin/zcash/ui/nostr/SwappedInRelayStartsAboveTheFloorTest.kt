package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.RelayHealthApi.MIN
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R4 (fix-all 2.13.0, train T5; SPEC.md §3.7.1): a relay swapped in starts at the seen list's floor + 1.
 *
 * With no `since` a new relay would replay everything it holds for us (about 30 days). Every wrap at or below the seen
 * list's floor counts as seen anyway, and every wrap above it (held or unhandled ones included) is still served from
 * floor + 1. So the swap seeds the new relay's `since` to floor + 1 through RelaySinceStore.seed (stored by
 * RelaySinceLine through Persistence.advance: it only rises and never passes now − 49 h); while the floor is 0 it seeds
 * nothing. The real ZchatPreferencesImpl (in-memory files) keeps the floor and the since values.
 */
class SwappedInRelayStartsAboveTheFloorTest {
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

    /** The seen list's floor, read through the new ZchatPreferences.nostrSeenFloorSec(). */
    private fun floorOf(prefs: ZchatPreferencesImpl): Long = RelayHealthApi.call(prefs, "nostrSeenFloorSec") as Long

    /** Raises the real seen list's floor: every default relay gets a since, then a prune step of old handled wraps. */
    private fun raiseFloor(prefs: ZchatPreferencesImpl): Long {
        val nowSec = System.currentTimeMillis() / 1000
        val since = nowSec - 10 * DAY_SEC
        NostrRelayPool.DEFAULT_RELAYS.forEach { assertTrue(prefs.advanceNostrRelaySince(it, since)) }
        repeat(PRUNE_STEP) { prefs.markNostrEventSeen("synthetic-r4-old-%04d".format(it), since - 3_600 - it) }
        return since - 3_600
    }

    private suspend fun swapSlotOne(prefs: ZchatPreferencesImpl): HealthRelay {
        val (a, urlA) = relay()
        val (_, urlB) = relay(HealthRelay.Mode.DOWN_502)
        val (c, urlC) = relay()
        val (d, urlD) = relay()
        val clock = AtomicLong(1_790_000_000_000L)
        val line =
            RelaySinceLine(
                persistence =
                    object : RelaySinceLine.Persistence {
                        override fun get(relayUrl: String): Long? = prefs.getNostrRelaySince(relayUrl)

                        override fun advance(
                            relayUrl: String,
                            sinceSec: Long,
                        ): Boolean = prefs.advanceNostrRelaySince(relayUrl, sinceSec)

                        override fun scope(): Long = prefs.walletScopeGeneration()
                    },
                isBacklogSettled = { false },
            )
        val p =
            RelayHealthApi
                .pool(
                    listOf(urlA, urlB, urlC),
                    scope,
                    line,
                    RelayHealthApi.config(listOf(urlB, urlC, urlD), clock) { floorOf(prefs) },
                    RelayHealthApi.CountingSetStore().proxy,
                ).also { pool = it }
        p.start()
        p.subscribe(INBOX_FILTER) { }
        assertTrue(HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() })
        delay(1_000)
        clock.addAndGet(11 * MIN)
        val before = a.inboxReqs().size to c.inboxReqs().size
        p.redeliverBacklog()
        assertTrue(HealthRelay.awaitTrue { a.inboxReqs().size > before.first && c.inboxReqs().size > before.second })
        delay(300)
        RelayHealthApi.tick(p)
        assertEquals(listOf(urlA, urlD, urlC), RelayHealthApi.activeUrls(p), "fixture: slot 1 swapped to D")
        assertTrue(HealthRelay.awaitTrue { d.inboxReqs().isNotEmpty() }, "D got our subscription")
        return d
    }

    @Test
    fun `with a seen-list floor F the swapped-in relay's first REQ carries since F + 1`() =
        runBlocking {
            val (prefs, _) = realPreferences()
            val floor = raiseFloor(prefs)
            assertEquals(floor, floorOf(prefs), "fixture: the prune raised the floor to the newest pruned wrap")

            val d = swapSlotOne(prefs)

            assertEquals(floor + 1, HealthRelay.sinceOf(d.inboxReqs().first()), "D's first REQ: ${d.inboxReqs().first()}")
        }

    @Test
    fun `with no floor yet the swapped-in relay's first REQ carries no since`() =
        runBlocking {
            val (prefs, _) = realPreferences()
            assertEquals(0L, floorOf(prefs))

            val d = swapSlotOne(prefs)

            assertNull(HealthRelay.sinceOf(d.inboxReqs().first()), "D's first REQ: ${d.inboxReqs().first()}")
        }

    private companion object {
        const val DAY_SEC = 86_400L
        const val PRUNE_STEP = 256
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("b".repeat(64)))
    }
}
