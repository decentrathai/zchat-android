package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #187 — the security property that makes GROUP_KICK / GROUP_KEY safe to act on: the admin's signature
 * over the CANONICAL control payload (a) round-trips for the genuine admin key, and (b) fails for any
 * tampered field or wrong signing key. These are the exact checks `verifyGroupAdminControl` relies on,
 * exercised at the pure-crypto level (no Android). Authorization (signer == group admin) and the
 * epoch-monotonicity replay guard are enforced in the receive handlers around this signature check.
 */
class GroupControlSignatureTest {

    @Test
    fun `genuine kick signature verifies against the admin pubkey`() {
        val admin = E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupKickSignedData("gid1", "u1kicked", "u1admin", 3, "wrappedKey")
        val sig = E2EEncryption.sign(admin.privateKey, data)
        assertTrue(E2EEncryption.verify(admin.publicKey, data, sig))
    }

    @Test
    fun `kick signature fails if the kicked address is swapped`() {
        val admin = E2EEncryption.generateKeyPair()
        val signed = ZMSGGroupProtocol.groupKickSignedData("gid1", "u1victimA", "u1admin", 3, "k")
        val sig = E2EEncryption.sign(admin.privateKey, signed)
        // Attacker re-targets the kick at a different victim but reuses the admin's signature.
        val tampered = ZMSGGroupProtocol.groupKickSignedData("gid1", "u1victimB", "u1admin", 3, "k")
        assertFalse(E2EEncryption.verify(admin.publicKey, tampered, sig))
    }

    @Test
    fun `kick signature fails if the epoch is bumped`() {
        val admin = E2EEncryption.generateKeyPair()
        val signed = ZMSGGroupProtocol.groupKickSignedData("gid1", "u1kicked", "u1admin", 3, "k")
        val sig = E2EEncryption.sign(admin.privateKey, signed)
        val tampered = ZMSGGroupProtocol.groupKickSignedData("gid1", "u1kicked", "u1admin", 99, "k")
        assertFalse(E2EEncryption.verify(admin.publicKey, tampered, sig))
    }

    @Test
    fun `kick signature fails when signed by a NON-admin key`() {
        val attacker = E2EEncryption.generateKeyPair()
        val admin = E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupKickSignedData("gid1", "u1kicked", "u1admin", 3, "k")
        // Attacker forges the kick with THEIR key; verification uses the admin's pubkey → must fail.
        val forged = E2EEncryption.sign(attacker.privateKey, data)
        assertFalse(E2EEncryption.verify(admin.publicKey, data, forged))
    }

    @Test
    fun `key-rotation signature round-trips and fails on tampered key`() {
        val admin = E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupKeySignedData("gid1", "u1admin", 5, "encKeyA", "rotation")
        val sig = E2EEncryption.sign(admin.privateKey, data)
        assertTrue(E2EEncryption.verify(admin.publicKey, data, sig))
        val tampered = ZMSGGroupProtocol.groupKeySignedData("gid1", "u1admin", 5, "encKeyB_attacker", "rotation")
        assertFalse(E2EEncryption.verify(admin.publicKey, tampered, sig))
    }

    @Test
    fun `canonical signed-data formats are stable and field-delimited`() {
        assertEquals(
            "GK|gid1|u1kicked|u1admin|3|wk",
            ZMSGGroupProtocol.groupKickSignedData("gid1", "u1kicked", "u1admin", 3, "wk")
        )
        // A null wrapped key collapses to an empty trailing field (still distinct from a present one).
        assertEquals(
            "GK|gid1|u1kicked|u1admin|3|",
            ZMSGGroupProtocol.groupKickSignedData("gid1", "u1kicked", "u1admin", 3, null)
        )
        assertEquals(
            "GY|gid1|u1admin|5|enc|rotation",
            ZMSGGroupProtocol.groupKeySignedData("gid1", "u1admin", 5, "enc", "rotation")
        )
    }
}
