package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SysNotesTest {
    @Test fun idPrefix_detection() {
        assertTrue(SysNotes.isSystemNoteId(SysNotes.rotationNoteId("sig")))
        assertTrue(SysNotes.isSystemNoteId(SysNotes.modeUpgradeNoteId("u1abc", 3)))
        assertTrue(SysNotes.isSystemNoteId(SysNotes.requestNoteId("deadbeef".repeat(8), "u1abc")))
        assertTrue(SysNotes.isSystemNoteId(SysNotes.ttlNoteId("u1abc", 1000L)))
        assertFalse(SysNotes.isSystemNoteId("nmsg-123"))
        assertFalse(SysNotes.isSystemNoteId("calllog-x"))
        assertFalse(SysNotes.isSystemNoteId(""))
    }

    @Test fun rotationId_stablePerPeer_distinctAcrossPeers() {
        // One stable pill per peer (overwritten on each rotation) — bounds the persisted-pill flood.
        assertEquals(SysNotes.rotationNoteId("u1peerA"), SysNotes.rotationNoteId("u1peerA"))
        assertNotEquals(SysNotes.rotationNoteId("u1peerA"), SysNotes.rotationNoteId("u1peerB"))
    }

    @Test fun requestId_distinctPerClaimedAddress_samePubkey() {
        val pk = "ab".repeat(32)
        // Guards the cross-chat overwrite bug: same attacker key claiming two contacts MUST yield two ids.
        assertNotEquals(SysNotes.requestNoteId(pk, "u1contactA"), SysNotes.requestNoteId(pk, "u1contactB"))
        assertEquals(SysNotes.requestNoteId(pk, "u1contactA"), SysNotes.requestNoteId(pk, "u1contactA"))
    }

    @Test fun modeUpgradeId_distinctPerEpoch() {
        assertNotEquals(SysNotes.modeUpgradeNoteId("u1peer", 0), SysNotes.modeUpgradeNoteId("u1peer", 1))
        assertEquals(SysNotes.modeUpgradeNoteId("u1peer", 2), SysNotes.modeUpgradeNoteId("u1peer", 2))
    }

    @Test fun ttlId_hashesAddress_noRawAddressInId() {
        val id = SysNotes.ttlNoteId("u1peer", 5000L)
        assertFalse(id.contains("u1peer"))
        assertEquals(id, SysNotes.ttlNoteId("u1peer", 5000L))
        assertNotEquals(SysNotes.ttlNoteId("u1peer", 5000L), SysNotes.ttlNoteId("u1peer", 6000L))
    }
}
