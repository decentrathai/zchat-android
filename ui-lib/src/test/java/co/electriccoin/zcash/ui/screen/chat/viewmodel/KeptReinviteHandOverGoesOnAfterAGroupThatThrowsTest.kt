package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-704, KI-25 pair 1b (R19; review of train T7a2, 13 Oct 2026, nit 8) — the hand-over of kept re-invites that a
 * history pass runs before its loop goes on with the next group when one group's read throws.
 *
 * `ChatViewModel.handOverKeptReinvitesBeforeLoop` walks every stored group. At `e224d16ac` one `try` stands around
 * the whole walk, so a store read that throws for one group ends it: the groups after that one keep their invites in
 * the list for this pass, and a removal of this phone that the pass then applies finds no invite to offer Join with.
 * The hook at the end of the pass (`endHistoryPass`) catches per group. Wanted: the same here.
 *
 * The phone has two groups that both read OK and both hold a kept re-invite. The walk's order is the store's, so the
 * case is run twice, each time with the other group's read throwing: at `e224d16ac` the run in which the throwing
 * group comes first leaves the other group's invite in the list.
 *
 * Nothing is sent or paid. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptReinviteHandOverGoesOnAfterAGroupThatThrowsTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    private val created: Instant = Instant.ofEpochMilli(T)
    private val inviteTime: Instant = Instant.ofEpochMilli(T + 120_000)

    /** A phone with two groups of one confirmed admin (both read OK), each holding one kept re-invite of the admin's chat. */
    private fun phoneWithTwoGroups(): AdminCheckPhone =
        AdminCheck.pinnedPhone().also { p ->
            p.base.nostrKeys[Y] = Y_NOSTR
            p.base.putGroup(
                GroupInfo(
                    groupId = GID_B,
                    name = "Second group",
                    creatorAddress = X_OLD,
                    createdAt = created,
                    isActive = true,
                    creatorPublicKey = p.admin.publicKey,
                ),
                listOf(
                    GroupMember(address = X_OLD, joinedAt = created, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = p.self, joinedAt = created, status = MemberStatus.ACTIVE),
                    GroupMember(address = M, joinedAt = created, status = MemberStatus.ACTIVE),
                ),
                1,
                AnnounceDriver.newGroupKey(),
            )
            for (groupId in listOf(GID, GID_B)) {
                val memo = AnnounceDriver.gi(X_OLD, p.base.sessionWith(Y), AnnounceDriver.newGroupKey(), 3, groupId)
                val entry =
                    AdminCheck.keptEntry(
                        "synthetic-ki25-kept-reinvite-$groupId",
                        "GI",
                        3,
                        ZMSGGroupProtocol.parsePayload(memo)!!,
                        Y,
                        inviteTime.toEpochMilli(),
                        System.currentTimeMillis() - 60_000,
                    )
                AdminCheck.putKept(p, listOf(entry), groupId)
            }
        }

    private fun keptInvites(
        p: AdminCheckPhone,
        groupId: String,
    ): List<String> = AdminCheck.keptChanges(p, groupId).filter { it.getString("t") == "GI" }.map { it.getString("id") }

    /**
     * One pass begins on a new phone while every read of [throwing]'s record throws (null: no read throws). Answers the
     * kept invites each group still holds after what the pass runs before its loop.
     */
    private suspend fun invitesLeftAfterThePassBegan(throwing: String?): Map<String, List<String>> {
        val p = phoneWithTwoGroups()
        val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
        assertEquals("fixture: both groups read OK", listOf("OK", "OK"), listOf(GID, GID_B).map { AdminCheck.keyState(vm, it) })
        assertEquals("fixture: each group holds one kept invite", listOf(1, 1), listOf(GID, GID_B).map { keptInvites(p, it).size })

        p.onCall = { name ->
            val arguments = p.callLog.last().second
            if (name == "getGroupInfo" && arguments.firstOrNull() == throwing) {
                throw IllegalStateException("fixture: this group's record cannot be read")
            }
        }
        try {
            AdminCheck.beginPass(vm)
        } finally {
            p.onCall = null
        }
        return listOf(GID, GID_B).associateWith { keptInvites(p, it) }
    }

    @Test
    fun `pin - with no read that throws both groups' kept invites are handed over before the loop`() =
        runBlocking {
            val left = invitesLeftAfterThePassBegan(throwing = null)

            assertEquals(mapOf(GID to emptyList<String>(), GID_B to emptyList()), left)
        }

    @Test
    fun `whichever group's read throws, the other group's kept invite is still handed over`() =
        runBlocking {
            val firstThrows = invitesLeftAfterThePassBegan(throwing = GID)
            val secondThrows = invitesLeftAfterThePassBegan(throwing = GID_B)

            assertEquals("the group whose read threw lost its kept invite", 1, firstThrows.getValue(GID).size)
            assertEquals("the group whose read threw lost its kept invite", 1, secondThrows.getValue(GID_B).size)
            val skipped =
                listOfNotNull(
                    GID_B.takeIf { firstThrows.getValue(GID_B).isNotEmpty() },
                    GID.takeIf { secondThrows.getValue(GID).isNotEmpty() },
                )
            assertTrue(
                "one group's read threw and the walk ended there: the kept invite of $skipped was not handed over in this pass",
                skipped.isEmpty(),
            )
        }

    private companion object {
        const val GID_B = "zgrp_2d3e4f5a6b7c8d9e0f1a2b3c"
    }
}
