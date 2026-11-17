package co.electriccoin.zcash.ui.nostr

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * ZC-393 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.3 item 2): the outbox's tags and completed records, pure.
 *
 * A tagged wrap is found by its tag while it is kept; once every active relay holds it, [RelayOutbox.complete] drops the
 * entry and keeps (tag → the relays that held it) without the json; the records follow the outbox's 24 h and entry
 * limits, the oldest dropped first; an untagged wrap leaves no record; a wrap dropped because no relay took it, or pushed
 * out by the limits, leaves none either (its Retry makes a new wrap, as before).
 */
class RelayOutboxCompletedRecordTest {
    private val config = RelayHealthConfig().apply { outboxMaxEntries = 3 }

    private fun wrap(id: String) = HealthRelay.event(id, 1_790_000_000L)

    @Test
    fun `a tagged wrap is found by its tag, and an id already kept keeps its first tag`() {
        val outbox = RelayOutbox()
        outbox.add(wrap("e1"), 0L, config, "peer:r1")
        outbox.add(wrap("e1"), 5L, config, "peer:other")

        assertEquals("e1", outbox.findByTag("peer:r1")?.id)
        assertNull(outbox.findByTag("peer:other"))
        assertNull(outbox.findByTag("peer:r2"))
    }

    @Test
    fun `complete keeps the relays that held a tagged wrap, without the entry`() {
        val outbox = RelayOutbox()
        outbox.add(wrap("e1"), 0L, config, "peer:r1")
        outbox.acked("e1", "wss://own")
        outbox.acked("e1", "wss://nos")

        outbox.complete("e1", config)

        assertNull(outbox.findByTag("peer:r1"))
        assertEquals(emptyList(), outbox.snapshot())
        assertEquals(setOf("wss://own", "wss://nos"), outbox.completedFor("peer:r1"))
    }

    @Test
    fun `an untagged wrap, a dropped wrap and an evicted wrap leave no record`() {
        val outbox = RelayOutbox()
        outbox.add(wrap("u1"), 0L, config)
        outbox.complete("u1", config)
        outbox.add(wrap("d1"), 0L, config, "peer:dropped")
        outbox.published(wrap("d1"), acks = 0)
        listOf("x1", "x2", "x3", "x4").forEach { outbox.add(wrap(it), 0L, config, "peer:$it") }

        assertNull(outbox.completedFor("peer:dropped"))
        assertNull(outbox.findByTag("peer:dropped"))
        assertNull(outbox.findByTag("peer:x1"), "the cap pushed the oldest out")
        assertNull(outbox.completedFor("peer:x1"))
        assertSame(outbox.findByTag("peer:x4"), outbox.snapshot().last())
    }

    @Test
    fun `completed records follow the entry cap and the 24 hour prune`() {
        val outbox = RelayOutbox()
        listOf("c1", "c2", "c3", "c4").forEachIndexed { i, id ->
            outbox.add(wrap(id), i * 1_000L, config, "peer:$id")
            outbox.complete(id, config)
        }
        assertNull(outbox.completedFor("peer:c1"), "more records than the entry cap")
        assertEquals(emptySet(), outbox.completedFor("peer:c4"))

        outbox.prune(nowMs = 2_000L + config.outboxKeepMs, keepMs = config.outboxKeepMs)

        assertNull(outbox.completedFor("peer:c2"))
        assertNull(outbox.completedFor("peer:c3"))
        assertEquals(emptySet(), outbox.completedFor("peer:c4"), "a record younger than 24 h was pruned")
    }
}
