package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * GR-OPEN G2 (fold-specs/groups SPEC §2.2, review F and I) — the receiver rule for a "sealed admin control": an unsigned
 * GROUP_KICK or GROUP_KEY that came over the relays sealed by the group's creator, in a group this phone joined with the
 * creator's plaintext relay invite ([AnnounceState.plainKeyJoin]).
 *
 * [ZMSGGroupProtocol.sealedAdminControlAuthorized] is true only when all of these hold: the control came over the relays
 * (an authenticated sender), it carries no signature, the group was joined with the plaintext relay invite, the seal is
 * the creator's, and the admin the control names (the kick's kicker, the key's signer) is the creator too. ZC-967: the
 * addresses are compared as stored, never through an address alias (an alias is written from what a member declares,
 * so it proves nothing about who created a group); a build that still takes a canonicalizer is handed one that maps
 * [CREATOR_REP] to [CREATOR]. The function is reached by name. All values are synthetic.
 */
class SealedAdminControlAuthorizedTest {
    private fun authorized(
        authenticatedSender: String?,
        signature: String?,
        claimedAdmin: String,
        creatorAddress: String = CREATOR,
        plainKeyJoin: Boolean = true,
        canonicalize: (String) -> String = CANON,
    ): Boolean {
        val m = ZMSGGroupProtocol::class.java.methods.firstOrNull { it.name == "sealedAdminControlAuthorized" }
        assertNotNull("ZMSGGroupProtocol.sealedAdminControlAuthorized does not exist", m)
        return try {
            if (m!!.parameterCount == 6) {
                m.invoke(ZMSGGroupProtocol, authenticatedSender, signature, claimedAdmin, creatorAddress, plainKeyJoin, canonicalize)
                    as Boolean
            } else {
                m.invoke(ZMSGGroupProtocol, authenticatedSender, signature, claimedAdmin, creatorAddress, plainKeyJoin) as Boolean
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `a relay control sealed by the creator, unsigned, naming the creator, in a plain-key group is authorized`() {
        assertEquals(true, authorized(CREATOR, "", CREATOR))
        assertEquals(true, authorized(CREATOR, null, CREATOR))
    }

    @Test
    fun `ZC-967 - an address that only an alias maps to the creator's is not the creator`() {
        assertEquals(false, authorized(CREATOR_REP, "", CREATOR))
        assertEquals(false, authorized(CREATOR, "", CREATOR_REP))
    }

    @Test
    fun `on-chain (no seal) it is never authorized`() {
        assertEquals(false, authorized(null, "", CREATOR))
    }

    @Test
    fun `a signed control is not a sealed control (the signed gate decides it)`() {
        assertEquals(false, authorized(CREATOR, "SIG", CREATOR))
    }

    @Test
    fun `a group joined through a key exchange, or before this batch, has no plain-key join and refuses it`() {
        assertEquals(false, authorized(CREATOR, "", CREATOR, plainKeyJoin = false))
    }

    @Test
    fun `sealed by a member who is not the creator it is refused`() {
        assertEquals(false, authorized(MEMBER, "", CREATOR))
    }

    @Test
    fun `naming an admin that is not the creator it is refused, even sealed by the creator (review I)`() {
        assertEquals(false, authorized(CREATOR, "", MEMBER))
    }

    private companion object {
        const val CREATOR = "usyntheticgropencreator00001"
        const val CREATOR_REP = "usyntheticgropencreator00002"
        const val MEMBER = "usyntheticgropenmember000001"
        val CANON: (String) -> String = { if (it == CREATOR_REP) CREATOR else it }
    }
}
