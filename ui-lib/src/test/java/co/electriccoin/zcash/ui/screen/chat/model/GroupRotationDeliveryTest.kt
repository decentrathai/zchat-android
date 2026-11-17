package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 — removal-under-partial-delivery. `rotateAndNotify` commits the new epoch even when some
 * remaining members never received the rotated key; those members keep fanning out old-epoch
 * ciphertext that the kicked member can still read. The pure classification of "who is still OWED
 * the new key" (persisted on the roster as the additive `pendingKeyEpoch` field), the retry-pass
 * selection, and the pull-side authorization gate are extracted onto [ZMSGGroupProtocol] and locked
 * in here (plain JVM, no Android, no mocks — same harness as [GroupControlSignatureTest]).
 */
class GroupRotationDeliveryTest {

    private val identityCanon: (String) -> String = { it }

    private fun member(
        addr: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        pending: Int? = null,
    ) = GroupMember(address = addr, status = status, pendingKeyEpoch = pending)

    // ==========================================
    // rotationDeliveryOutcome — classify a rotation fan-out
    // ==========================================

    @Test
    fun `all sends succeeding is a complete rotation with nobody owed the key`() {
        val recipients = listOf(member("u1a"), member("u1b"))
        val out = ZMSGGroupProtocol.rotationDeliveryOutcome(recipients, newEpoch = 5) { true }
        assertTrue(out.removalComplete)
        assertTrue(out.pending.isEmpty())
        assertEquals(listOf("u1a", "u1b"), out.delivered.map { it.address })
        assertTrue("delivered rows must carry no pending marker", out.delivered.all { it.pendingKeyEpoch == null })
    }

    @Test
    fun `a failed or skipped member is owed the NEW epoch and the rotation is incomplete`() {
        val recipients = listOf(member("u1ok"), member("u1skip"))
        val out = ZMSGGroupProtocol.rotationDeliveryOutcome(recipients, newEpoch = 7) { it.address == "u1ok" }
        assertFalse(out.removalComplete)
        assertEquals(listOf("u1skip"), out.pending.map { it.address })
        assertEquals(7, out.pending.single().pendingKeyEpoch)
        assertEquals(listOf("u1ok"), out.delivered.map { it.address })
    }

    @Test
    fun `a delivered member's stale pending marker from an earlier incomplete rotation is cleared`() {
        // Owed epoch 4 from a previous partial rotation; this rotation (epoch 5) reaches them.
        val recipients = listOf(member("u1lag", pending = 4))
        val out = ZMSGGroupProtocol.rotationDeliveryOutcome(recipients, newEpoch = 5) { true }
        assertNull(out.delivered.single().pendingKeyEpoch)
        assertTrue(out.removalComplete)
    }

    @Test
    fun `outcome is exactly a partition of the given recipients — the kicked member cannot reappear`() {
        // The caller filters the kicked member OUT of recipients before the fan-out; the pure
        // classification must never invent rows, so the leak target stays absent.
        val recipients = listOf(member("u1a"), member("u1b"), member("u1c"))
        val out = ZMSGGroupProtocol.rotationDeliveryOutcome(recipients, newEpoch = 3) { it.address != "u1b" }
        assertEquals(setOf("u1a", "u1c"), out.delivered.map { it.address }.toSet())
        assertEquals(setOf("u1b"), out.pending.map { it.address }.toSet())
        assertEquals(recipients.size, out.delivered.size + out.pending.size)
        assertFalse((out.delivered + out.pending).any { it.address == "u1kicked" })
    }

    // ==========================================
    // membersOwedKey — retry-pass selection
    // ==========================================

    @Test
    fun `membersOwedKey picks exactly the undelivered ACTIVE rows`() {
        val roster = listOf(
            member("u1delivered"),
            member("u1missed"),
            member("u1gone", status = MemberStatus.LEFT),
        )
        val owed = ZMSGGroupProtocol.membersOwedKey(roster, deliveredTo = listOf("u1delivered"), newEpoch = 6, canonicalize = identityCanon)
        assertEquals(listOf("u1missed"), owed.map { it.address })
        assertEquals(6, owed.single().pendingKeyEpoch)
    }

    @Test
    fun `membersOwedKey excludes LEFT rows even when undelivered — a kicked member is never owed the new key`() {
        val roster = listOf(member("u1kicked", status = MemberStatus.LEFT))
        assertTrue(ZMSGGroupProtocol.membersOwedKey(roster, emptyList(), 6, identityCanon).isEmpty())
    }

    @Test
    fun `membersOwedKey excludes INVITED rows — invitees get the key in their invite, not via rotation retry`() {
        val roster = listOf(member("u1invited", status = MemberStatus.INVITED))
        assertTrue(ZMSGGroupProtocol.membersOwedKey(roster, emptyList(), 6, identityCanon).isEmpty())
    }

    @Test
    fun `membersOwedKey matches deliveredTo canonically — a drifted rep of a delivered peer is up to date`() {
        val canon: (String) -> String = { if (it == "u1repA" || it == "u1repB") "canonPeer" else it }
        val roster = listOf(member("u1repA"))
        // Delivery was recorded under the OTHER representation of the same peer (#205/#214 drift).
        assertTrue(ZMSGGroupProtocol.membersOwedKey(roster, listOf("u1repB"), 6, canon).isEmpty())
    }

    @Test
    fun `membersOwedKey with pendingKeyEpoch-derived deliveredTo selects the persisted owed set`() {
        // The retry pass rebuilds deliveredTo from the persisted roster: rows with NO pending marker
        // are up to date; the rest are still owed. Self (up to date, no marker) is excluded too.
        val roster = listOf(
            member("u1self"),
            member("u1owed", pending = 5),
            member("u1uptodate"),
        )
        val deliveredTo = roster.filter { it.pendingKeyEpoch == null }.map { it.address }
        val owed = ZMSGGroupProtocol.membersOwedKey(roster, deliveredTo, newEpoch = 5, canonicalize = identityCanon)
        assertEquals(listOf("u1owed"), owed.map { it.address })
    }

    // ==========================================
    // mayGrantKeyRequest — pull-side authorization (GROUP_KEY_REQUEST)
    // ==========================================

    @Test
    fun `an ACTIVE member may pull the current key`() {
        val roster = listOf(member("u1member"))
        assertTrue(ZMSGGroupProtocol.mayGrantKeyRequest("u1member", roster, requestedEpoch = 2, currentEpoch = 5, canonicalize = identityCanon))
    }

    @Test
    fun `a LEFT (kicked) requester must NEVER be granted the post-kick key`() {
        val roster = listOf(member("u1kicked", status = MemberStatus.LEFT))
        assertFalse(ZMSGGroupProtocol.mayGrantKeyRequest("u1kicked", roster, 2, 5, identityCanon))
    }

    @Test
    fun `an unknown requester is refused`() {
        val roster = listOf(member("u1member"))
        assertFalse(ZMSGGroupProtocol.mayGrantKeyRequest("u1stranger", roster, 2, 5, identityCanon))
    }

    @Test
    fun `an INVITED (not yet accepted) requester is refused`() {
        val roster = listOf(member("u1invited", status = MemberStatus.INVITED))
        assertFalse(ZMSGGroupProtocol.mayGrantKeyRequest("u1invited", roster, 0, 5, identityCanon))
    }

    @Test
    fun `a drifted-rep ACTIVE requester is recognized via canonicalization`() {
        val canon: (String) -> String = { if (it == "u1repA" || it == "u1repB") "canonPeer" else it }
        val roster = listOf(member("u1repA"))
        assertTrue(ZMSGGroupProtocol.mayGrantKeyRequest("u1repB", roster, 2, 5, canon))
    }

    @Test
    fun `a stale ACTIVE duplicate row cannot override the LEFT tombstone of the same peer`() {
        // Both rows canonicalize to the same peer; the kick tombstoned one rep but a stale duplicate
        // stayed ACTIVE. Removal wins — the kicked peer must not pull the new key via the stale row.
        val canon: (String) -> String = { if (it == "u1repA" || it == "u1repB") "canonPeer" else it }
        val roster = listOf(
            member("u1repA", status = MemberStatus.LEFT),
            member("u1repB", status = MemberStatus.ACTIVE),
        )
        assertFalse(ZMSGGroupProtocol.mayGrantKeyRequest("u1repB", roster, 2, 5, canon))
    }

    @Test
    fun `the requested epoch is bounded — negative or ahead-of-current is refused`() {
        val roster = listOf(member("u1member"))
        assertFalse("negative epoch", ZMSGGroupProtocol.mayGrantKeyRequest("u1member", roster, -1, 5, identityCanon))
        assertFalse("epoch ahead of current", ZMSGGroupProtocol.mayGrantKeyRequest("u1member", roster, 6, 5, identityCanon))
        assertTrue("holding the current epoch is a valid (idempotent) request", ZMSGGroupProtocol.mayGrantKeyRequest("u1member", roster, 5, 5, identityCanon))
        assertTrue("epoch 0 (fresh/never-rotated device)", ZMSGGroupProtocol.mayGrantKeyRequest("u1member", roster, 0, 5, identityCanon))
    }

    // ==========================================
    // GROUP_KEY_REQUEST ("GR") — wire format (the pull that replaces the inert #197 sync)
    // ==========================================

    @Test
    fun `key-request message round-trips through build and parse`() {
        val memo = ZMSGGroupProtocol.createGroupKeyRequestMessage("gid1", "u1member", haveEpoch = 3, signature = "SIGB64")
        assertEquals(GroupMessageType.GROUP_KEY_REQUEST, ZMSGGroupProtocol.parseMessageType(memo))
        assertEquals("gid1", ZMSGGroupProtocol.parseGroupId(memo))
        val parsed = ZMSGGroupProtocol.parseGroupKeyRequestPayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals("u1member", parsed.requester)
        assertEquals(3, parsed.haveEpoch)
        assertEquals("SIGB64", parsed.signature)
    }

    @Test
    fun `unsigned key-request omits the sig field and parses with null signature`() {
        val memo = ZMSGGroupProtocol.createGroupKeyRequestMessage("gid1", "u1member", 0, signature = "")
        assertFalse(memo.contains("\"sig\""))
        val parsed = ZMSGGroupProtocol.parseGroupKeyRequestPayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertNull(parsed.signature)
    }

    @Test
    fun `malformed key-request payload returns null instead of throwing`() {
        assertNull(ZMSGGroupProtocol.parseGroupKeyRequestPayload("{ not json"))
        // Missing required 'requester'.
        assertNull(ZMSGGroupProtocol.parseGroupKeyRequestPayload("""{"have_epoch":2}"""))
        // Missing required 'have_epoch'.
        assertNull(ZMSGGroupProtocol.parseGroupKeyRequestPayload("""{"requester":"u1x"}"""))
    }

    @Test
    fun `key-request canonical signed data is stable and field-delimited`() {
        assertEquals("GR|gid1|u1member|3", ZMSGGroupProtocol.groupKeyRequestSignedData("gid1", "u1member", 3))
    }

    @Test
    fun `key-request signature round-trips and breaks on a tampered epoch or group`() {
        val requester = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupKeyRequestSignedData("gid1", "u1member", 3)
        val sig = co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.sign(requester.privateKey, data)
        assertTrue(co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.verify(requester.publicKey, data, sig))
        val epochTamper = ZMSGGroupProtocol.groupKeyRequestSignedData("gid1", "u1member", 9)
        assertFalse(co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.verify(requester.publicKey, epochTamper, sig))
        val groupTamper = ZMSGGroupProtocol.groupKeyRequestSignedData("gidOTHER", "u1member", 3)
        assertFalse(co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption.verify(requester.publicKey, groupTamper, sig))
    }
}
