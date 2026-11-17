package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEntry
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * F8-G (ZC-082) — the pure parts of completing an interrupted key rotation ([GroupSendAttemptRules]): the rotation's
 * record, the members a key-message entry already covers, and the roster the completion writes.
 *
 * In that roster every row of the removed member is LEFT and never owed the key; every other key holder is owed the
 * new key, except the members that have a ledger entry for a key message of that number (on its way, mined or failed):
 * theirs is the entry's state, not an owed mark. Our own row, a LEFT row and an invitee whose invite failed are left
 * as they are.
 */
class HealInterruptedRotationTest {
    private companion object {
        const val G = "zgrp_synthheal"
        const val SELF = "usynthetichealself000000001"
        const val A = "usynthetichealmembera000001"
        const val A2 = "usynthetichealmembera000002"
        const val B = "usynthetichealmemberb000001"
        const val D = "usynthetichealmemberd000001"
        const val X = "usynthetichealmemberx000001"
        const val L = "usynthetichealmemberl000001"
    }

    private fun member(
        address: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        invite: InviteStatus? = InviteStatus.SENT,
        pending: Int? = null,
    ) = GroupMember(
        address = address,
        joinedAt = Instant.ofEpochMilli(1L),
        status = status,
        inviteStatus = invite,
        pendingKeyEpoch = pending,
    )

    private val canon: (String) -> String = { if (it == A2) A else it }

    private fun healed(
        kicked: String?,
        covered: Set<String>,
    ) = GroupSendAttemptRules
        .healedRoster(
            listOf(
                member(SELF),
                member(A),
                member(A2),
                member(B),
                member(D, pending = 1),
                member(X, status = MemberStatus.INVITED, invite = InviteStatus.FAILED),
                member(L, status = MemberStatus.LEFT),
            ),
            epoch = 5,
            kickedCanon = kicked,
            covered = covered,
            canon = canon,
            isSelf = { it == SELF },
        ).associateBy { it.address }

    @Test
    fun `key holders are owed the new key, except the covered ones, ourselves, a failed invitee and a LEFT row`() {
        val rows = healed(kicked = null, covered = setOf(B))
        assertEquals(5, rows.getValue(A).pendingKeyEpoch)
        assertEquals("every row of a member is owed", 5, rows.getValue(A2).pendingKeyEpoch)
        assertNull("a member with a key-message entry of that number is not owed", rows.getValue(B).pendingKeyEpoch)
        assertEquals("an older owed mark becomes the new number", 5, rows.getValue(D).pendingKeyEpoch)
        assertNull(rows.getValue(SELF).pendingKeyEpoch)
        assertNull("an invitee whose invite failed holds no key", rows.getValue(X).pendingKeyEpoch)
        assertEquals(MemberStatus.LEFT, rows.getValue(L).status)
        assertNull(rows.getValue(L).pendingKeyEpoch)
    }

    @Test
    fun `every row of the removed member is LEFT and never owed the key, whatever it said`() {
        val rows = healed(kicked = A, covered = emptySet())
        for (address in listOf(A, A2)) {
            assertEquals(MemberStatus.LEFT, rows.getValue(address).status)
            assertNull(rows.getValue(address).pendingKeyEpoch)
        }
        val owedBefore = healed(kicked = D, covered = setOf(D)).getValue(D)
        assertEquals(MemberStatus.LEFT, owedBefore.status)
        assertNull("a removed member that was owed an older key is not owed the new one", owedBefore.pendingKeyEpoch)
        assertEquals(5, rows.getValue(B).pendingKeyEpoch)
    }

    @Test
    fun `a member is covered by a key message or key grant entry of that group and number, in any state`() {
        fun entry(
            kind: SendAttemptKind,
            member: String,
            identity: String,
            state: SendAttemptState,
            group: String = G,
        ) = SendAttemptEntry(
            kind = kind,
            subject = GroupSendAttemptRules.memberSubject(group, member),
            identity = identity,
            startedAtMillis = 1L,
            state = state,
            minedHeight = if (state == SendAttemptState.MINED) 1L else null,
        )
        val covered =
            GroupSendAttemptRules.coveredByKeyEntry(
                listOf(
                    entry(SendAttemptKind.KEY_MEMO, A, "epoch:5", SendAttemptState.LIVE),
                    entry(SendAttemptKind.KEY_MEMO, B, "epoch:5", SendAttemptState.MINED),
                    entry(SendAttemptKind.KEY_GRANT, D, "epoch:5", SendAttemptState.NOT_SENT),
                    entry(SendAttemptKind.KEY_MEMO, X, "epoch:4", SendAttemptState.LIVE),
                    entry(SendAttemptKind.KEY_MEMO, L, "epoch:5", SendAttemptState.LIVE, group = "zgrp_other"),
                    entry(SendAttemptKind.INVITE, SELF, "epoch:5", SendAttemptState.LIVE),
                ),
                G,
                5,
            )
        assertEquals(setOf(A, B, D), covered)
    }

    @Test
    fun `the rotation record carries its key number and the removed member, and nothing else reads as one`() {
        val plain = GroupSendAttemptRules.rotationEntry(G, GroupSendAttemptRules.Rotation(5, null), 77L)
        assertEquals(SendAttemptKind.ROTATION, plain.kind)
        assertEquals(GroupSendAttemptRules.rotationKey(G, 5), plain.key)
        assertEquals(77L, plain.startedAtMillis)
        assertEquals(GroupSendAttemptRules.Rotation(5, null), GroupSendAttemptRules.rotationOf(plain))
        val removal = GroupSendAttemptRules.rotationEntry(G, GroupSendAttemptRules.Rotation(6, A), 78L)
        assertEquals(GroupSendAttemptRules.Rotation(6, A), GroupSendAttemptRules.rotationOf(removal))
        assertNull("an unreadable record", GroupSendAttemptRules.rotationOf(plain.copy(payload = "not json")))
        assertNull(GroupSendAttemptRules.rotationOf(plain.copy(payload = null)))
        assertNull("another kind", GroupSendAttemptRules.rotationOf(plain.copy(kind = SendAttemptKind.KEY_MEMO)))
    }
}
