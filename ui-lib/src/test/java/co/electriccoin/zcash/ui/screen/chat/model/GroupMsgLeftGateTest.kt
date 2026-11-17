package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4 — LEFT-gate bypass via spoofed active sender. On-chain, GROUP_MSG attribution FAILS OPEN
 * (deliberate — gating live attribution on signatures breaks mesh delivery), so a removed member
 * holding a retained pre-kick key could stamp an ACTIVE member's address on an old-epoch message
 * and slip past `isRemovedMember`. The epoch-floor authorship gate
 * [ZMSGGroupProtocol.isSuspectedRemovedInjection] threads the needle: a removed member can only
 * produce ciphertext decrypting under a PRE-REMOVAL epoch, and cannot forge the impersonated
 * member's pairwise author signature — so verified authorship is required ONLY below the removal
 * floor, and current-epoch messages stay fully fail-open (mesh preserved). Pure JVM, real
 * [E2EEncryption], no mocks.
 */
class GroupMsgLeftGateTest {

    private val nobodyRemoved: (String) -> Boolean = { false }

    // ==========================================
    // TABLE — the gate predicate
    // ==========================================

    @Test
    fun `current-epoch unsigned message is NOT suspect — mesh relay stays fail-open`() {
        // decryptedEpoch == floor (the post-kick epoch) and beyond: live traffic, never gated.
        assertFalse(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1active", decryptedEpoch = 5, removalFloor = 5,
                authorVerified = false, isRemovedMember = nobodyRemoved
            )
        )
        assertFalse(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1active", decryptedEpoch = 6, removalFloor = 5,
                authorVerified = false, isRemovedMember = nobodyRemoved
            )
        )
    }

    @Test
    fun `pre-removal-epoch unsigned message IS suspect — the removed-member injection window`() {
        assertTrue(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1claimedActive", decryptedEpoch = 4, removalFloor = 5,
                authorVerified = false, isRemovedMember = nobodyRemoved
            )
        )
    }

    @Test
    fun `pre-removal-epoch message with VERIFIED authorship passes — lagging sender and late render survive`() {
        // A genuine ACTIVE member still on the old epoch (D3 lagging peer / #215 late-arriving
        // pre-kick message) carries a valid pairwise author signature → allowed.
        assertFalse(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1lagging", decryptedEpoch = 4, removalFloor = 5,
                authorVerified = true, isRemovedMember = nobodyRemoved
            )
        )
    }

    @Test
    fun `a removed effective sender is always dropped — even verified at the current epoch`() {
        assertTrue(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1kicked", decryptedEpoch = 5, removalFloor = 5,
                authorVerified = true, isRemovedMember = { it == "u1kicked" }
            )
        )
    }

    @Test
    fun `no removal ever (floor 0) leaves the epoch rule inert`() {
        // Groups that never kicked anyone: decryptedEpoch >= 0 always, so nothing is epoch-gated.
        assertFalse(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1anyone", decryptedEpoch = 0, removalFloor = 0,
                authorVerified = false, isRemovedMember = nobodyRemoved
            )
        )
    }

    // ==========================================
    // INTEGRATION-STYLE (real crypto): forged attribution + old key + LEFT status
    // ==========================================

    @Test
    fun `removed member C forging ACTIVE member A on an old epoch is dropped, genuine A is not`() {
        val groupId = "gid1"
        val removalFloor = 5 // the kick rotated INTO epoch 5; C's retained key is epoch 4
        val aKeys = E2EEncryption.generateKeyPair() // ACTIVE member A (impersonated)
        val cKeys = E2EEncryption.generateKeyPair() // removed member C (attacker)
        val roster = listOf(
            GroupMember("u1A", status = MemberStatus.ACTIVE),
            GroupMember("u1C", status = MemberStatus.LEFT),
        )
        val isRemoved: (String) -> Boolean = { sender ->
            ZMSGGroupProtocol.isRemovedMember(sender, roster) { it }
        }
        val ciphertext = "Y2lwaGVydGV4dA==" // decrypts under C's retained epoch-4 key

        // C claims sender=A but can only sign with C's own key (or garble the sig): the receiver
        // verifies against A's held pubkey → authenticated == false.
        val forgedSig = E2EEncryption.sign(
            cKeys.privateKey,
            ZMSGGroupProtocol.groupMsgSignedData(groupId, "u1A", 4, 7L, ciphertext)
        )
        val forged = ZMSGGroupProtocol.resolveGroupMsgSender(
            authenticatedSender = null,
            claimedSender = "u1A", signature = forgedSig,
            groupId = groupId, epoch = 4, seq = 7L, ciphertext = ciphertext,
            heldKey = aKeys.publicKey,
            verify = E2EEncryption::verify,
        )
        assertFalse(forged.authenticated)
        // The old single LEFT check misses it (A is ACTIVE) — that is exactly the D4 bypass…
        assertFalse(isRemoved(forged.effectiveSender))
        // …but the epoch-floor authorship gate catches it: pre-removal epoch + unproven author.
        assertTrue(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = forged.effectiveSender, decryptedEpoch = 4, removalFloor = removalFloor,
                authorVerified = forged.authenticated, isRemovedMember = isRemoved
            )
        )

        // Counterpart: the REAL A signs the same old-epoch message with A's key → verified → passes.
        val genuineSig = E2EEncryption.sign(
            aKeys.privateKey,
            ZMSGGroupProtocol.groupMsgSignedData(groupId, "u1A", 4, 7L, ciphertext)
        )
        val genuine = ZMSGGroupProtocol.resolveGroupMsgSender(
            authenticatedSender = null,
            claimedSender = "u1A", signature = genuineSig,
            groupId = groupId, epoch = 4, seq = 7L, ciphertext = ciphertext,
            heldKey = aKeys.publicKey,
            verify = E2EEncryption::verify,
        )
        assertTrue(genuine.authenticated)
        assertFalse(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = genuine.effectiveSender, decryptedEpoch = 4, removalFloor = removalFloor,
                authorVerified = genuine.authenticated, isRemovedMember = isRemoved
            )
        )

        // And C posting under C's OWN address stays dropped by the LEFT clause, signature or not.
        assertTrue(
            ZMSGGroupProtocol.isSuspectedRemovedInjection(
                effectiveSender = "u1C", decryptedEpoch = 4, removalFloor = removalFloor,
                authorVerified = true, isRemovedMember = isRemoved
            )
        )
    }
}
