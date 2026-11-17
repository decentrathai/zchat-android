package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEntry
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptLedgerRules
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptState
import co.electriccoin.zcash.ui.screen.chat.model.GroupResendOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-G (ZC-112 part 2) — which member rows of Group Settings show "Key not delivered" or "Message not delivered", and
 * the ledger keys of the group kinds ([GroupSendAttemptRules], pure).
 *
 * A badge and its Resend exist only for a failed (proven not sent) entry: of the CURRENT key number, for a member that
 * still holds the group key (a key), or of an ACTIVE member whose message's key is still held (a message copy). None
 * for a member who left, a key number the group moved past, an attempt that is on its way or mined, or one whose
 * Resend is running. A key message and a key grant are two keys for one delivery: a delivery of one hides the other.
 */
class GroupSettingsResendOffersTest {
    private companion object {
        const val G = "zgrp_synthoffers"
        const val A = "usyntheticoffersmembera0001"
        const val B = "usyntheticoffersmemberb0001"
    }

    private fun entry(
        kind: SendAttemptKind,
        member: String,
        identity: String,
        state: SendAttemptState,
        startedAt: Long = 100L,
        group: String = G,
    ) = SendAttemptEntry(
        kind = kind,
        subject = GroupSendAttemptRules.memberSubject(group, member),
        identity = identity,
        startedAtMillis = startedAt,
        txIds = listOf("synthetic-$startedAt"),
        state = state,
        minedHeight = if (state == SendAttemptState.MINED) 1L else null,
    )

    private fun keyOffers(
        entries: List<SendAttemptEntry>,
        holders: Set<String> = setOf(A, B),
        inFlight: Set<String> = emptySet(),
        epoch: Int = 2,
    ) = GroupSendAttemptRules.keyOffers(entries, G, epoch, holders, inFlight)

    @Test
    fun `a failed key message of the current number shows the badge, with the entry's key and marker`() {
        val failed = entry(SendAttemptKind.KEY_MEMO, A, "epoch:2", SendAttemptState.NOT_SENT, startedAt = 7L)
        assertEquals(mapOf(A to GroupResendOffer(failed.key, 7L)), keyOffers(listOf(failed)))
    }

    @Test
    fun `no key badge for an attempt on its way or mined, an old number, a member who left, another group or a running Resend`() {
        val failed = entry(SendAttemptKind.KEY_MEMO, A, "epoch:2", SendAttemptState.NOT_SENT)
        assertTrue(keyOffers(listOf(entry(SendAttemptKind.KEY_MEMO, A, "epoch:2", SendAttemptState.LIVE))).isEmpty())
        assertTrue(keyOffers(listOf(entry(SendAttemptKind.KEY_MEMO, A, "epoch:2", SendAttemptState.MINED))).isEmpty())
        assertTrue("the group moved past that key number", keyOffers(listOf(failed), epoch = 3).isEmpty())
        assertTrue("the member holds no group key any more", keyOffers(listOf(failed), holders = setOf(B)).isEmpty())
        assertTrue(
            keyOffers(listOf(entry(SendAttemptKind.KEY_MEMO, A, "epoch:2", SendAttemptState.NOT_SENT, group = "zgrp_other"))).isEmpty(),
        )
        assertTrue("its Resend is running", keyOffers(listOf(failed), inFlight = setOf(failed.key)).isEmpty())
        assertTrue(keyOffers(listOf(entry(SendAttemptKind.INVITE, A, "epoch:2", SendAttemptState.NOT_SENT))).isEmpty())
    }

    @Test
    fun `a key grant shows the badge too, and a delivery of the other kind hides it`() {
        val grant = entry(SendAttemptKind.KEY_GRANT, A, "epoch:2", SendAttemptState.NOT_SENT, startedAt = 9L)
        assertEquals(mapOf(A to GroupResendOffer(grant.key, 9L)), keyOffers(listOf(grant)))
        for (state in listOf(SendAttemptState.LIVE, SendAttemptState.MINED)) {
            assertTrue(keyOffers(listOf(grant, entry(SendAttemptKind.KEY_MEMO, A, "epoch:2", state))).isEmpty())
        }
        val memo = entry(SendAttemptKind.KEY_MEMO, A, "epoch:2", SendAttemptState.NOT_SENT, startedAt = 11L)
        assertEquals(
            "with both failed, the key message's entry is offered",
            GroupResendOffer(memo.key, 11L),
            keyOffers(listOf(grant, memo))[A],
        )
        assertTrue("the other kind's Resend is running", keyOffers(listOf(grant), inFlight = setOf(memo.key)).isEmpty())
    }

    @Test
    fun `the message badge is the member's oldest failed copy, only for an ACTIVE member whose message key is held`() {
        val older = entry(SendAttemptKind.GROUP_MESSAGE, A, "1:4", SendAttemptState.NOT_SENT, startedAt = 5L)
        val newer = entry(SendAttemptKind.GROUP_MESSAGE, A, "1:5", SendAttemptState.NOT_SENT, startedAt = 6L)
        val alive = entry(SendAttemptKind.GROUP_MESSAGE, B, "1:4", SendAttemptState.LIVE, startedAt = 8L)
        val all = listOf(newer, older, alive)

        fun offers(
            active: Set<String> = setOf(A, B),
            held: (Int) -> Boolean = { true },
            inFlight: Set<String> = emptySet(),
        ) = GroupSendAttemptRules.messageOffers(all, G, active, held, inFlight)

        assertEquals(mapOf(A to GroupResendOffer(older.key, 5L)), offers())
        assertEquals(
            "after the older copy's Resend runs, the next one",
            GroupResendOffer(newer.key, 6L),
            offers(inFlight = setOf(older.key))[A],
        )
        assertTrue("the member is not ACTIVE", offers(active = setOf(B)).isEmpty())
        assertTrue("the key of that message is gone", offers(held = { false }).isEmpty())
    }

    @Test
    fun `the keys of the group kinds`() {
        assertEquals("INVITE|$G|$A|epoch:3", GroupSendAttemptRules.invite(G, A, 3).key)
        assertEquals("KEY_MEMO|$G|$A|epoch:3", GroupSendAttemptRules.key(SendAttemptKind.KEY_MEMO, G, A, 3).key)
        assertEquals("GROUP_MESSAGE|$G|$A|3:12", GroupSendAttemptRules.message(G, A, 3, 12L).key)
        assertEquals("ANNOUNCE|$G|r|s|A", GroupSendAttemptRules.announce(G, "r|s|A").key)
        assertEquals(3 to 12L, GroupSendAttemptRules.messageOf("3:12"))
        assertNull(GroupSendAttemptRules.messageOf("epoch:3"))
        assertEquals(3, GroupSendAttemptRules.epochOf("epoch:3"))
        assertNull(GroupSendAttemptRules.epochOf("3:12"))
        val memo = entry(SendAttemptKind.KEY_MEMO, A, "epoch:3", SendAttemptState.LIVE)
        assertEquals(G, GroupSendAttemptRules.groupOf(memo))
        assertEquals(A, GroupSendAttemptRules.memberOf(memo))
        assertEquals(
            SendAttemptLedgerRules.key(SendAttemptKind.KEY_GRANT, memo.subject, memo.identity),
            GroupSendAttemptRules.siblingKey(memo.kind, memo.subject, memo.identity),
        )
        assertEquals(memo.key, GroupSendAttemptRules.siblingKey(SendAttemptKind.KEY_GRANT, memo.subject, memo.identity))
        assertNull(GroupSendAttemptRules.siblingKey(SendAttemptKind.INVITE, memo.subject, memo.identity))
        assertEquals(1234L, GroupSendAttemptRules.invite(G, A, 3, retryOf = 1234L).retryOf)
    }

    @Test
    fun `every group kind is fail closed and the rotation record is its own kind`() {
        for (kind in listOf(
            SendAttemptKind.INVITE,
            SendAttemptKind.GROUP_MESSAGE,
            SendAttemptKind.KEY_MEMO,
            SendAttemptKind.ANNOUNCE,
            SendAttemptKind.KEY_GRANT,
            SendAttemptKind.ROTATION,
        )) {
            assertTrue("$kind", kind.failClosed)
        }
        assertEquals("ROTATION|$G|epoch:4", GroupSendAttemptRules.rotationKey(G, 4))
    }
}
