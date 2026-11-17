package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Round 8 MA1, design test 5 (MEMBER_ANNOUNCE_DESIGN.md v2 §2.1) — the announce number
 * `n = max(storedN + 1, nowMs, highestOwnSentN + 1)` never decreases, never falls below the wall clock, and always
 * passes the highest number this admin ever sent on-chain (which a restore rebuilds).
 */
class AnnounceNumberMonotonicTest {
    private val api = AnnounceApi

    @Test
    fun `n is the largest of the next stored number, the clock and the next own sent number`() {
        assertEquals(NOW, api.nextAnnounceNumber(0, NOW, 0))
        assertEquals(NOW + 6, api.nextAnnounceNumber(NOW + 5, NOW, 0))
        assertEquals(NOW + 101, api.nextAnnounceNumber(5, NOW, NOW + 100))
        assertEquals("a clock set back never lowers n", NOW + 1_001, api.nextAnnounceNumber(NOW + 1_000, NOW - 10_000, 0))
    }

    @Test
    fun `a long run of numbers never decreases and stays at or above the wall clock`() {
        val rnd = Random(20260924)
        var stored = 0L
        var ownSent = 0L
        var clock = NOW
        repeat(5_000) {
            clock += rnd.nextLong(-5_000, 5_000) // clocks jump both ways
            if (rnd.nextInt(10) == 0) ownSent = maxOf(ownSent, stored + rnd.nextLong(0, 50))
            val n = api.nextAnnounceNumber(stored, clock, ownSent)
            assertTrue("n $n must pass the stored $stored", n > stored)
            assertTrue("n $n must be at least the clock $clock", n >= clock)
            assertTrue("n $n must pass the own sent $ownSent", n > ownSent)
            stored = n
        }
    }

    @Test
    fun `numbering starts above the highest own sent number and returns the new counter`() {
        val subject = "u1" + "m".repeat(60)
        val roster =
            listOf(
                GroupMember("u1" + "a".repeat(60), joinedAt = Instant.EPOCH, status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(subject, joinedAt = Instant.EPOCH, status = MemberStatus.ACTIVE),
            )
        val st = MaState(members = mapOf(subject to MaEntry(activatedAtMs = 1, activatedBy = MaBy.AUTH)))
        val numbered =
            api.assignAnnounceNumbers(
                "zgrp_0123456789abcdef01234567",
                st,
                roster,
                0,
                NOW,
                NOW + 50,
                0,
                { it },
                { it == roster[0].address },
                { "key-$it" },
                { true },
            )
        assertEquals(
            NOW + 51,
            numbered.state.members
                .getValue(subject)
                .n
        )
        assertEquals(NOW + 51, numbered.counter)
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
