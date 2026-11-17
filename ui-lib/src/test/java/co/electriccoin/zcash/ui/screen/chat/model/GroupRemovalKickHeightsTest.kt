package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 9 R9-3 — the removal record's pure rules (ZMSGGroupProtocol.minedBeforeRemoval, recordRemovalKick and the
 * stored form). The behaviour through the real ViewModels is pinned by GroupFloorHonoursKickHeightTest.
 */
class GroupRemovalKickHeightsTest {
    @Test
    fun `a message mined below the only removal's height is before it, at or above it is not`() {
        val kicks = mapOf(2 to 1_000L)
        assertTrue(ZMSGGroupProtocol.minedBeforeRemoval(999L, decryptedEpoch = 1, removalFloor = 2, kickHeights = kicks))
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(1_000L, decryptedEpoch = 1, removalFloor = 2, kickHeights = kicks))
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(1_001L, decryptedEpoch = 1, removalFloor = 2, kickHeights = kicks))
    }

    @Test
    fun `unmined, not below the floor, or no record is never before`() {
        val kicks = mapOf(2 to 1_000L)
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(null, decryptedEpoch = 1, removalFloor = 2, kickHeights = kicks))
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(10L, decryptedEpoch = 2, removalFloor = 2, kickHeights = kicks))
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(10L, decryptedEpoch = 1, removalFloor = 2, kickHeights = emptyMap()))
    }

    @Test
    fun `every removal that rotated past the key counts, the earliest bounds it, and an unknown one blocks it`() {
        val kicks = mapOf(2 to 1_000L, 3 to 2_000L)
        // Key 1: retired by both removals; the first one (1,000) bounds it.
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(1_500L, decryptedEpoch = 1, removalFloor = 3, kickHeights = kicks))
        assertTrue(ZMSGGroupProtocol.minedBeforeRemoval(999L, decryptedEpoch = 1, removalFloor = 3, kickHeights = kicks))
        // Key 2: only the second removal retired it.
        assertTrue(ZMSGGroupProtocol.minedBeforeRemoval(1_500L, decryptedEpoch = 2, removalFloor = 3, kickHeights = kicks))
        // An unknown height among the removals that count: never before.
        val unknown = mapOf(2 to null, 3 to 2_000L)
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(10L, decryptedEpoch = 1, removalFloor = 3, kickHeights = unknown))
        assertTrue(ZMSGGroupProtocol.minedBeforeRemoval(1_500L, decryptedEpoch = 2, removalFloor = 3, kickHeights = unknown))
    }

    @Test
    fun `a floor above the record's highest removal (an older build's removal) makes every key below it unknown`() {
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(10L, decryptedEpoch = 2, removalFloor = 4, kickHeights = mapOf(3 to 1_000L)))
    }

    @Test
    fun `recording keeps an older build's floor as a removal of unknown height`() {
        val record = ZMSGGroupProtocol.recordRemovalKick(emptyMap(), floorBefore = 2, epoch = 4, kickHeight = 5_000L)
        assertEquals(mapOf(2 to null, 4 to 5_000L), record)
        // Key 1 (under the old floor) stays unknown, key 3 (retired only by the new removal) is bounded by it.
        assertFalse(ZMSGGroupProtocol.minedBeforeRemoval(10L, decryptedEpoch = 1, removalFloor = 4, kickHeights = record))
        assertTrue(ZMSGGroupProtocol.minedBeforeRemoval(4_999L, decryptedEpoch = 3, removalFloor = 4, kickHeights = record))
    }

    @Test
    fun `recording the same removal twice keeps the lower height, and an unknown height stays unknown`() {
        val first = ZMSGGroupProtocol.recordRemovalKick(emptyMap(), floorBefore = 0, epoch = 2, kickHeight = 1_000L)
        assertEquals(mapOf(2 to 1_000L), first)
        assertEquals(mapOf(2 to 900L), ZMSGGroupProtocol.recordRemovalKick(first, floorBefore = 2, epoch = 2, kickHeight = 900L))
        assertEquals(mapOf(2 to 1_000L), ZMSGGroupProtocol.recordRemovalKick(first, floorBefore = 2, epoch = 2, kickHeight = 1_100L))
        assertEquals(mapOf(2 to null), ZMSGGroupProtocol.recordRemovalKick(first, floorBefore = 2, epoch = 2, kickHeight = null))
        val unknown = ZMSGGroupProtocol.recordRemovalKick(emptyMap(), floorBefore = 0, epoch = 2, kickHeight = null)
        assertEquals(mapOf(2 to null), ZMSGGroupProtocol.recordRemovalKick(unknown, floorBefore = 2, epoch = 2, kickHeight = 800L))
    }

    @Test
    fun `the stored form reads back, and an absent or unreadable one reads as no record`() {
        val record = mapOf(2 to null, 4 to 3_000_100L)
        assertEquals(record, ZMSGGroupProtocol.deserializeRemovalKicks(ZMSGGroupProtocol.serializeRemovalKicks(record)))
        assertNull(ZMSGGroupProtocol.deserializeRemovalKicks(null))
        assertNull(ZMSGGroupProtocol.deserializeRemovalKicks(""))
        assertNull(ZMSGGroupProtocol.deserializeRemovalKicks("not json"))
        assertEquals(mapOf(3 to null), ZMSGGroupProtocol.deserializeRemovalKicks("{\"3\":\"x\"}"))
    }

    @Test
    fun `the injection gate lets a message mined before the removal through the epoch clause only`() {
        assertFalse(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1member",
                decryptedEpoch = 1,
                removalFloor = 2,
                authorVerified = false,
                isRemovedMember = { false },
                minedBeforeRemoval = true,
            ),
        )
        assertTrue(
            "a removed member's own message is still dropped",
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1removed",
                decryptedEpoch = 1,
                removalFloor = 2,
                authorVerified = false,
                isRemovedMember = { true },
                minedBeforeRemoval = true,
            ),
        )
        assertTrue(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1member",
                decryptedEpoch = 1,
                removalFloor = 2,
                authorVerified = false,
                isRemovedMember = { false },
            ),
        )
    }
}
