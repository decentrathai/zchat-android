package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ZC-291 (r8-findings SPECS §8) — on the announcer (the group's creator): a member who was removed and then invited
 * again is not sent its own removal again.
 *
 * The creator's group screen re-sends every current R over the relays, and one copy goes to the removed member itself
 * (its other device). While the member's row is INVITED again (re-invited, its "I joined" reply not processed yet) the
 * member's current op is still R, so that copy reaches a phone that has just tapped Join and takes it out of the group
 * again; the invite it used cannot offer Join a second time.
 *
 * Wanted: the copy to the removed member itself is owed only while every roster row of that member is LEFT or KICKED.
 * A member invited again (INVITED) or back (ACTIVE, its A not numbered yet) is not sent its removal. The other
 * recipients keep the R until an A replaces it.
 *
 * All addresses, keys and group ids are synthetic.
 */
class ReinvitedMemberIsNotResentItsRemovalTest {
    private val api = AnnounceApi

    private val keys = mapOf(ADMIN to "key-admin", M1 to "key-m1", M1_OTHER_FORM to "key-m1", M2 to "key-m2")
    private val canon: (String) -> String = { if (it == M1_OTHER_FORM) M1 else it }
    private val isSelf: (String) -> Boolean = { it == ADMIN }
    private val keyOf: (String) -> String? = { keys[it] }
    private val secure: (String) -> Boolean = { true }

    private fun row(
        address: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        isAdmin: Boolean = false
    ) = GroupMember(address = address, joinedAt = Instant.ofEpochMilli(1_000L), status = status, isAdmin = isAdmin)

    /** M1 was announced, then removed: its current op is R, numbered [R_N], in the form [M1]. M2 is an announced member. */
    private val state =
        MaState(
            hw = R_N,
            rosterAnnounced = true,
            members =
                mapOf(
                    M1 to
                        MaEntry(
                            n = R_N,
                            op = MaOp.R,
                            epoch = EPOCH,
                            activatedAtMs = 10,
                            activatedBy = MaBy.AUTH,
                            leftAtMs = R_N,
                            removedAtEpoch = EPOCH,
                            annForm = M1,
                        ),
                    M2 to MaEntry(n = 20, op = MaOp.A, epoch = EPOCH, activatedAtMs = 10, activatedBy = MaBy.AUTH, annForm = M2),
                ),
        )

    private fun rosterWith(vararg m1Rows: GroupMember) = listOf(row(ADMIN, isAdmin = true), row(M2)) + m1Rows

    /** The recipients owed M1's removal with this roster. */
    private fun owedRemovalOfM1(roster: List<GroupMember>): Set<String> {
        val owed = api.owedMemberAnnounces(state, roster, emptyList(), canon, isSelf, keyOf, secure).filter { it.sAddr == M1 }
        assertTrue("M1's current announce is not its removal: $owed", owed.all { it.op == MaOp.R && it.n == R_N })
        return owed.map { it.recipient }.toSet()
    }

    @Test
    fun `a removed member whose row is INVITED again is not sent its own removal, and the others still are`() {
        val owed = owedRemovalOfM1(rosterWith(row(M1, MemberStatus.INVITED)))
        assertFalse("the member invited again is still owed its own removal", M1 in owed)
        assertEquals("the other members keep the removal until an A replaces it", setOf(M2), owed)
    }

    @Test
    fun `a removed member whose row is ACTIVE again is not sent its own removal`() {
        // Its "I joined" reply was processed (the row is ACTIVE), the numbering pass has not replaced the R yet.
        val owed = owedRemovalOfM1(rosterWith(row(M1)))
        assertFalse("the member who is back is still owed its own removal", M1 in owed)
    }

    @Test
    fun `a member invited again under another address form is not sent its own removal`() {
        val owed = owedRemovalOfM1(rosterWith(row(M1, MemberStatus.LEFT), row(M1_OTHER_FORM, MemberStatus.INVITED)))
        assertFalse("one row of the member is INVITED again and it is still owed its own removal", M1 in owed)
        assertEquals(setOf(M2), owed)
    }

    @Test
    fun `control - a removed member whose rows are all LEFT or KICKED is still sent its own removal`() {
        assertEquals(setOf(M2, M1), owedRemovalOfM1(rosterWith(row(M1, MemberStatus.LEFT))))
        assertEquals(setOf(M2, M1), owedRemovalOfM1(rosterWith(row(M1, MemberStatus.KICKED))))
        assertEquals(
            setOf(M2, M1),
            owedRemovalOfM1(rosterWith(row(M1, MemberStatus.LEFT), row(M1_OTHER_FORM, MemberStatus.KICKED))),
        )
    }

    private companion object {
        const val EPOCH = 3
        const val R_N = 1_790_000_000_000L
        val ADMIN = "u1" + "a".repeat(60)
        val M1 = "u1" + "m".repeat(60)
        val M1_OTHER_FORM = "u1" + "r".repeat(60)
        val M2 = "u1" + "n".repeat(60)
    }
}
