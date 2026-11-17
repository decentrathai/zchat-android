package co.electriccoin.zcash.ui.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-24 part B (fix-all 2.13.0, train T4; SPECS §2.2 B4, §2.3 steps 7-8): the per-relay `since` line
 * ([RelaySinceLine]). Pure: the persistence is a map that applies [RelaySinceLine.nextStored], the rule
 * ZchatPreferencesImpl.advanceNostrRelaySince applies. Relay urls are synthetic.
 */
class InboxSinceIsPerRelayAndBehindJitterTest {
    private companion object {
        const val NOW = 1_790_000_000L
        const val MARGIN = RelaySinceLine.MARGIN_SEC
        const val RELAY_A = "wss://relay-a.synthetic.example"
        const val RELAY_B = "wss://relay-b.synthetic.example"
    }

    private class MapPersistence(
        val now: () -> Long,
    ) : RelaySinceLine.Persistence {
        val stored = mutableMapOf<String, Long>()
        var scope = 0L

        override fun get(relayUrl: String): Long? = stored[relayUrl]

        override fun advance(
            relayUrl: String,
            sinceSec: Long,
        ): Boolean {
            val next = RelaySinceLine.nextStored(stored[relayUrl], sinceSec, now()) ?: return false
            stored[relayUrl] = next
            return true
        }

        override fun scope(): Long = scope
    }

    private var clock = NOW
    private val persistence = MapPersistence { clock }
    private val line = RelaySinceLine(persistence, isBacklogSettled = { true }, nowSec = { clock })

    @Test
    fun `a relay that never ended its stored events gets no since, as before KI-24`() {
        assertNull(line.sinceFor(RELAY_A))
        line.onEose(RELAY_A, NOW)
        assertNull("one EOSE only records a candidate", line.sinceFor(RELAY_A))
        assertNull(line.sinceFor(RELAY_B))
    }

    @Test
    fun `the second settled EOSE stores the first one's point, 49 h behind it`() {
        line.onEose(RELAY_A, NOW)
        clock = NOW + 120
        line.onEose(RELAY_A, NOW + 120)

        assertEquals(NOW - MARGIN, line.sinceFor(RELAY_A))
        assertTrue("never ahead of EOSE - 49 h", line.sinceFor(RELAY_A)!! <= NOW + 120 - MARGIN)
        assertNull("the line is per relay", line.sinceFor(RELAY_B))
    }

    @Test
    fun `the line never decreases, even when the clock goes back`() {
        line.onEose(RELAY_A, NOW)
        line.onEose(RELAY_A, NOW)
        val first = line.sinceFor(RELAY_A)!!
        clock = NOW - 10 * 24 * 3_600
        line.onEose(RELAY_A, clock)
        line.onEose(RELAY_A, clock)

        assertEquals(first, line.sinceFor(RELAY_A))
    }

    @Test
    fun `an EOSE time in the future never puts the line above now - 49 h`() {
        line.onEose(RELAY_A, NOW + 30 * 24 * 3_600)
        line.onEose(RELAY_A, NOW + 30 * 24 * 3_600)

        assertEquals(NOW - MARGIN, line.sinceFor(RELAY_A))
    }

    @Test
    fun `a candidate from before a wallet change is never stored`() {
        line.onEose(RELAY_A, NOW)
        persistence.scope = 1L
        clock = NOW + 60
        line.onEose(RELAY_A, NOW + 60)
        assertNull(line.sinceFor(RELAY_A))

        clock = NOW + 120
        line.onEose(RELAY_A, NOW + 120)
        assertEquals(NOW + 60 - MARGIN, line.sinceFor(RELAY_A))
    }

    @Test
    fun `the stored rule - only upward, never above now - 49 h, never zero`() {
        assertEquals(NOW - MARGIN, RelaySinceLine.nextStored(null, NOW, NOW))
        assertNull(RelaySinceLine.nextStored(NOW - MARGIN, NOW - MARGIN - 5, NOW))
        assertNull(RelaySinceLine.nextStored(NOW - MARGIN, NOW, NOW))
        assertEquals(NOW - MARGIN - 1, RelaySinceLine.nextStored(NOW - MARGIN - 9, NOW - MARGIN - 1, NOW))
        assertNull(RelaySinceLine.nextStored(null, 0L, NOW))
    }
}
