package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ZC-130 — [GroupMessageConsent.keepFor], the rule every roster write applies to the R5-1 group-message spend consent:
 * keep only the consented members that still have an ACTIVE row that is not ours, compared canonically; nothing when
 * our own row is no longer ACTIVE or the roster is empty (unreadable); never add anybody. Every address is synthetic.
 */
class GroupMessageConsentKeepForTest {
    private fun row(
        address: String,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = GroupMember(address = address, joinedAt = Instant.ofEpochMilli(1_000L), status = status)

    private val aliases = mapOf(A_INVITED to A)

    private fun keep(
        consent: Set<String>,
        members: List<GroupMember>,
    ) = GroupMessageConsent.keepFor(consent, members, { aliases[it] ?: it }, { it == SELF })

    @Test
    fun `everyone still ACTIVE keeps the whole consent`() {
        assertEquals(setOf(A, B), keep(setOf(A, B), listOf(row(SELF), row(A), row(B))))
    }

    @Test
    fun `a member whose row is LEFT, KICKED or INVITED is dropped`() {
        for (status in listOf(MemberStatus.LEFT, MemberStatus.KICKED, MemberStatus.INVITED)) {
            assertEquals("B $status", setOf(A), keep(setOf(A, B), listOf(row(SELF), row(A), row(B, status))))
        }
    }

    @Test
    fun `a member with no row at all is dropped`() {
        assertEquals(setOf(A), keep(setOf(A, B, NOT_A_MEMBER), listOf(row(SELF), row(A), row(B, MemberStatus.LEFT))))
    }

    @Test
    fun `our own row not ACTIVE drops the whole consent`() {
        for (status in listOf(MemberStatus.LEFT, MemberStatus.KICKED, MemberStatus.INVITED)) {
            assertTrue("own row $status", keep(setOf(A, B), listOf(row(SELF, status), row(A), row(B))).isEmpty())
        }
    }

    @Test
    fun `a roster with no own row still keeps the ACTIVE members`() {
        assertEquals(setOf(A, B), keep(setOf(A, B), listOf(row(A), row(B))))
    }

    @Test
    fun `an empty roster, as one that does not parse reads, drops everything`() {
        assertTrue(keep(setOf(A, B), emptyList()).isEmpty())
    }

    @Test
    fun `rows are compared in their canonical form`() {
        assertEquals(setOf(A, B), keep(setOf(A, B), listOf(row(SELF), row(A_INVITED), row(B))))
    }

    @Test
    fun `our own address in the consent is never kept`() {
        assertEquals(setOf(A), keep(setOf(A, SELF), listOf(row(SELF), row(A))))
    }

    @Test
    fun `nothing is ever added`() {
        assertEquals(setOf(A), keep(setOf(A), listOf(row(SELF), row(A), row(B))))
        assertTrue(keep(emptySet(), listOf(row(SELF), row(A), row(B))).isEmpty())
    }

    private companion object {
        const val SELF = "usyntheticzc130keepself00001"
        const val A = "usyntheticzc130keepmembera01"
        const val A_INVITED = "usyntheticzc130keepainvited1"
        const val B = "usyntheticzc130keepmemberb01"
        const val NOT_A_MEMBER = "usyntheticzc130keepnotmember"
    }
}
