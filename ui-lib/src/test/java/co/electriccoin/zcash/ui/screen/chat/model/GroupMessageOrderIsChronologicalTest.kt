package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Round 9 R9-5 (REVIEW_R8 F5 = device D-F3) — the group screen lists messages in the order they were written.
 *
 * GroupDetailView sorts with GroupMessage.compareForOrdering. At 3bcbc11ab that comparator never looks at the time:
 * two rows with no block height (every relay row, and every on-chain row the live path stores, which writes no height)
 * tie on height and index and are then ordered by SENDER ADDRESS. On the member-announce gate phones a 23:24 message
 * was listed above a 23:12 one. A mined row is also put above every relay row, however much later it was written.
 *
 * Wanted:
 * 1. Three rows with no height, timestamps T3 < T1 < T2 and senders in the opposite order, come out T3, T1, T2.
 * 2. A mined row and a relay row are ordered by time, not by whether they have a height.
 * 3. The comparator is a total order over a generated set: antisymmetric, transitive, and 0 only for the same row.
 *
 * All addresses and ids are synthetic.
 */
class GroupMessageOrderIsChronologicalTest {
    private val t0 = Instant.parse("2026-09-24T23:00:00Z")

    private fun row(
        id: String,
        sender: String,
        minute: Long,
        seq: Long = 1,
        height: Long? = null,
        txIndex: Int? = null,
        second: Long = 0,
    ) = GroupMessage(
        id = id,
        groupId = "zgrp_r95order",
        txId = null,
        seq = seq,
        epoch = 1,
        senderAddress = sender,
        encryptedContent = null,
        decryptedContent = id,
        nonce = null,
        timestamp = t0.plusSeconds(minute * 60 + second),
        blockHeight = height,
        txIndex = txIndex,
    )

    private fun sorted(rows: List<GroupMessage>): List<String> =
        rows
            .sortedWith { a, b ->
                GroupMessage.compareForOrdering(
                    a,
                    b
                )
            }.map { it.id }

    @Test
    fun `rows with no height are ordered by time, not by sender address`() {
        // T3 < T1 < T2; the senders sort the other way round (usynthetic-a < -b < -c), which is what 3bcbc11ab used.
        val t1 = row("t1-23:12", sender = "usyntheticr95sender-b", minute = 12)
        val t2 = row("t2-23:24", sender = "usyntheticr95sender-a", minute = 24)
        val t3 = row("t3-23:05", sender = "usyntheticr95sender-c", minute = 5)

        assertEquals(
            "the group screen does not list messages in the order they were written",
            listOf("t3-23:05", "t1-23:12", "t2-23:24"),
            sorted(listOf(t1, t2, t3)),
        )
        assertEquals(listOf("t3-23:05", "t1-23:12", "t2-23:24"), sorted(listOf(t2, t3, t1)))
    }

    @Test
    fun `a mined row and a relay row are ordered by time`() {
        val relayEarly = row("relay-23:05", sender = "usyntheticr95sender-z", minute = 5)
        val mined = row("mined-23:10", sender = "usyntheticr95sender-a", minute = 10, height = 3_100_000, txIndex = 4)
        val relayLate = row("relay-23:20", sender = "usyntheticr95sender-b", minute = 20)

        assertEquals(
            "a mined row is put above a relay row written earlier",
            listOf("relay-23:05", "mined-23:10", "relay-23:20"),
            sorted(listOf(mined, relayLate, relayEarly)),
        )
    }

    @Test
    fun `rows written in the same second still come out in one fixed order`() {
        // Same second: the chain order (height, then index), then sender, then the sender's own sequence.
        val a = row("h2", sender = "usyntheticr95sender-a", minute = 1, height = 2)
        val b = row("h1-i2", sender = "usyntheticr95sender-a", minute = 1, height = 1, txIndex = 2)
        val c = row("h1-i1", sender = "usyntheticr95sender-b", minute = 1, height = 1, txIndex = 1)
        val d = row("relay-seq2", sender = "usyntheticr95sender-c", minute = 1, seq = 2)
        val e = row("relay-seq1", sender = "usyntheticr95sender-c", minute = 1, seq = 1)

        assertEquals(listOf("h1-i1", "h1-i2", "h2", "relay-seq1", "relay-seq2"), sorted(listOf(e, a, d, c, b)))
    }

    @Test
    fun `the comparator is a total order`() {
        val rows = generated()
        val cmp = { a: GroupMessage, b: GroupMessage -> Integer.signum(GroupMessage.compareForOrdering(a, b)) }
        for (a in rows) {
            assertEquals("a row does not compare equal to itself: ${a.id}", 0, cmp(a, a))
            for (b in rows) {
                if (a === b) continue
                assertEquals("not antisymmetric: ${a.id} vs ${b.id}", -cmp(b, a), cmp(a, b))
                assertTrue("two different rows compare equal: ${a.id} and ${b.id}", cmp(a, b) != 0)
                for (c in rows) {
                    if (cmp(a, b) < 0 && cmp(b, c) < 0) {
                        assertTrue("not transitive: ${a.id} < ${b.id} < ${c.id} but not ${a.id} < ${c.id}", cmp(a, c) < 0)
                    }
                }
            }
        }
    }

    /** Every mix of two times, height (none / 1 / 2), index (none / 0 / 1), two senders and two sequence numbers. */
    private fun generated(): List<GroupMessage> {
        val out = mutableListOf<GroupMessage>()
        for (second in listOf(0L, 1L)) {
            for (height in listOf<Long?>(null, 1L, 2L)) {
                for (index in listOf<Int?>(null, 0, 1)) {
                    for (sender in listOf("usyntheticr95sender-a", "usyntheticr95sender-b")) {
                        for (seq in listOf(1L, 2L)) {
                            out +=
                                row(
                                    id = "s$second-h$height-i$index-${sender.last()}-q$seq",
                                    sender = sender,
                                    minute = 0,
                                    second = second,
                                    seq = seq,
                                    height = height,
                                    txIndex = index,
                                )
                        }
                    }
                }
            }
        }
        // Two rows that differ only by id (one message stored under two ids): still a fixed order.
        out += row("dup-x", sender = "usyntheticr95sender-a", minute = 0)
        out += row("dup-y", sender = "usyntheticr95sender-a", minute = 0)
        return out
    }
}
