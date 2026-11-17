package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ZC-130 (fix-all 2.13.0, train T5b; high-items SPECS §2) — the group-message spend consent only grows.
 *
 * R5-1 remembers, per group, the members the user agreed to pay an on-chain memo for when messaging the group, and a
 * message pays without a question while that set covers everyone it would pay for. Nothing ever takes a member out of
 * it: a member who leaves or is removed, and is later added back, is paid for by the next message with no question —
 * also when no message was sent while the member was out, so a cut made only when a message is sent cannot catch it.
 *
 * Wanted: a member's consent lasts only while the member stays in the group. Every roster write (all of them pass
 * through [ZchatPreferences.saveGroupMembers] or [ZchatPreferences.saveGroupMembersDurably]) keeps only the consented
 * members that still have an ACTIVE row in the new roster that is not ours; when our own row is no longer ACTIVE, or the
 * roster cannot be read, the whole consent goes (the next message asks again: the safe side).
 *
 * The store under test is the real [ZchatPreferencesImpl] ([realPreferences]); every address and id is synthetic.
 */
class GroupSpendConsentEndsWithMembershipTest {
    @Test
    fun `a member who left is no longer covered by the consent`() {
        val (store, _) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))
        assertTrue(store.setGroupMessageSpendConsent(OTHER_GROUP, setOf(A, B)))

        store.saveGroupMembers(GROUP, roster(b = MemberStatus.LEFT))

        assertEquals(
            "B left the group, but the consent to pay for B's group messages is still stored",
            setOf(A),
            store.getGroupMessageSpendConsent(GROUP),
        )
        assertEquals("another group's consent is not touched", setOf(A, B), store.getGroupMessageSpendConsent(OTHER_GROUP))
    }

    @Test
    fun `a member removed through the durable roster write is no longer covered`() {
        val (store, _) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))

        assertTrue(store.saveGroupMembersDurably(GROUP, roster(b = MemberStatus.KICKED)))

        assertEquals(
            "B was removed, but the consent to pay for B's group messages is still stored",
            setOf(A),
            store.getGroupMessageSpendConsent(GROUP),
        )
    }

    @Test
    fun `a member re-added after leaving is not covered again until the user agrees again`() {
        val (store, _) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))

        store.saveGroupMembers(GROUP, roster(b = MemberStatus.LEFT))
        // The admin's phone re-adds B as INVITED; B then accepts and the row is ACTIVE again.
        store.saveGroupMembers(GROUP, roster(b = MemberStatus.INVITED))
        assertEquals("a re-invited member is not covered", setOf(A), store.getGroupMessageSpendConsent(GROUP))
        store.saveGroupMembers(GROUP, roster())

        assertEquals(
            "B came back after leaving and is covered again with no new question",
            setOf(A),
            store.getGroupMessageSpendConsent(GROUP),
        )
    }

    @Test
    fun `a member re-invited without a LEFT row in between is not covered`() {
        val (store, _) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))

        store.saveGroupMembers(GROUP, roster(b = MemberStatus.INVITED))

        assertEquals("a member whose row is INVITED is not covered", setOf(A), store.getGroupMessageSpendConsent(GROUP))
    }

    @Test
    fun `leaving the group ourselves removes the whole consent`() {
        val (store, files) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))

        store.saveGroupMembers(GROUP, roster(self = MemberStatus.LEFT))

        assertTrue(
            "we left the group, but the consent for its members is still stored",
            store.getGroupMessageSpendConsent(GROUP).isEmpty(),
        )
        assertFalse(
            "the consent key must be removed, not kept as an empty set",
            files.getValue(MEMBERS_FILE).snapshot().containsKey(CONSENT_KEY),
        )
    }

    @Test
    fun `a roster that cannot be read removes the consent`() {
        val (store, files) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))

        store.saveGroupMembers(GROUP, "{not a roster")

        assertTrue(
            "a roster nobody can read still lets the next message pay for the old consent",
            store.getGroupMessageSpendConsent(GROUP).isEmpty(),
        )
        assertFalse(files.getValue(MEMBERS_FILE).snapshot().containsKey(CONSENT_KEY))
    }

    // ---- controls (green before and after) ----------------------------------------------------------------------

    @Test
    fun `control - a roster write with everyone still ACTIVE keeps the consent`() {
        val (store, _) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))

        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.saveGroupMembersDurably(GROUP, roster()))

        assertEquals(setOf(A, B), store.getGroupMessageSpendConsent(GROUP))
    }

    @Test
    fun `control - a member listed under an alias of the consented address stays covered`() {
        val (store, files) = store()
        files
            .getValue("convMappingPrefs")
            .edit()
            .putString("alias:$A_INVITED", A)
            .commit()
        store.saveGroupMembers(GROUP, roster(aAddress = A_INVITED))
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))

        store.saveGroupMembers(GROUP, roster(aAddress = A_INVITED))

        assertEquals(setOf(A, B), store.getGroupMessageSpendConsent(GROUP))
    }

    @Test
    fun `control - a roster write never creates a consent`() {
        val (store, files) = store()

        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.saveGroupMembersDurably(GROUP, roster(b = MemberStatus.LEFT)))

        assertTrue(store.getGroupMessageSpendConsent(GROUP).isEmpty())
        assertFalse(files.getValue(MEMBERS_FILE).snapshot().containsKey(CONSENT_KEY))
    }

    @Test
    fun `control - Reset and Delete Group still clear the consent`() {
        val (store, _) = store()
        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))
        store.deleteGroup(GROUP)
        assertTrue(store.getGroupMessageSpendConsent(GROUP).isEmpty())

        store.saveGroupMembers(GROUP, roster())
        assertTrue(store.setGroupMessageSpendConsent(GROUP, setOf(A, B)))
        store.clearAll()
        assertTrue(store.getGroupMessageSpendConsent(GROUP).isEmpty())
    }

    private companion object {
        const val GROUP = "zgrp_00000000000000000000c130"
        const val OTHER_GROUP = "zgrp_00000000000000000000c131"
        const val SELF = "usyntheticzc130self000000001"
        const val A = "usyntheticzc130membera000001"
        const val A_INVITED = "usyntheticzc130memberainvite"
        const val B = "usyntheticzc130memberb000001"
        const val MEMBERS_FILE = "groupMembersPrefs"
        const val CONSENT_KEY = "group_msg_spend_consent_$GROUP"

        /** [realPreferences] with this phone's own address known, as the chat screen registers it. */
        fun store(): Pair<ZchatPreferencesImpl, Map<String, MemorySharedPreferences>> {
            val (store, files) = realPreferences()
            ZchatPreferencesImpl::class.java
                .getDeclaredField("selfAddresses\$delegate")
                .apply { isAccessible = true }
                .set(store, lazyOf(SelfAddressRegistry(files.getValue("prefs"))))
            store.registerSelfAddress(SELF)
            return store to files
        }

        fun roster(
            self: MemberStatus = MemberStatus.ACTIVE,
            b: MemberStatus = MemberStatus.ACTIVE,
            aAddress: String = A,
        ): String =
            ZMSGGroupProtocol.serializeGroupMembers(
                listOf(
                    GroupMember(address = SELF, joinedAt = Instant.ofEpochMilli(1_000L), status = self, isAdmin = true),
                    GroupMember(address = aAddress, joinedAt = Instant.ofEpochMilli(1_000L), status = MemberStatus.ACTIVE),
                    GroupMember(address = B, joinedAt = Instant.ofEpochMilli(1_000L), status = b),
                ),
            )
    }
}
