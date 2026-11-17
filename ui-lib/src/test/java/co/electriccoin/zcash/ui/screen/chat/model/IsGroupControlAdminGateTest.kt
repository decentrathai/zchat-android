package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #187 [7] — control-message AUTHORIZATION under representation drift. `verifyGroupAdminControl`
 * exact-compared the stored `creatorAddress` against the live `signerAddress`, so a rep-drifted
 * admin (#205/#214) whose signer rep differs from the stored creator rep had their own signed
 * kick/rotation silently rejected. The authorize compare is extracted onto
 * [ZMSGGroupProtocol.isGroupControlAdmin] and must canonicalize both sides — locked in here (plain
 * JVM, no Android, no mocks — same harness as [GroupRotationDeliveryTest]).
 */
class IsGroupControlAdminGateTest {

    private val identityCanon: (String) -> String = { it }

    @Test
    fun `a rep-drifted admin authorizes when both reps canonicalize to the same peer`() {
        // creatorAddress stored under rep X, signer under rep Y (#205/#214 drift); both resolve to
        // the same admin. Their signed kick/rotation MUST authorize.
        val canon: (String) -> String = { if (it == "repX" || it == "repY") "ADMIN" else it }
        assertTrue(ZMSGGroupProtocol.isGroupControlAdmin("repX", "repY", canon))
    }

    @Test
    fun `a stranger whose canonical differs from the creator is not an admin`() {
        val canon: (String) -> String = { if (it == "repX") "ADMIN" else "OTHER" }
        assertFalse(ZMSGGroupProtocol.isGroupControlAdmin("repX", "repY", canon))
    }

    @Test
    fun `identity canonicalize authorizes an exact match and rejects a mismatch`() {
        assertTrue(ZMSGGroupProtocol.isGroupControlAdmin("u1admin", "u1admin", identityCanon))
        assertFalse(ZMSGGroupProtocol.isGroupControlAdmin("u1admin", "u1other", identityCanon))
    }
}
