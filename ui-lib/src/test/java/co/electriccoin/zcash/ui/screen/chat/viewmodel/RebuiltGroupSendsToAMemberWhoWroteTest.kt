package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.NOW_MS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.own
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.ownInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.ownKeyChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-757 follow-up (register, medium: "a rebuilt group cannot send in practice") — a group this phone rebuilt lists the
 * recipients of its own mined invitations as invited (delivered, so they get the new key), and a message goes only to
 * active members. After the new key reached A, a send ends as "nobody has joined yet" until A counts as active.
 *
 * Wanted: before A has written, the send names A as not joined yet (plainly why it goes nowhere; nothing is quoted or
 * sent); once A's authenticated message under the new key has arrived, A's row is active and the next message goes to
 * A (free over the relays here; nothing is proposed for payment).
 *
 * All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltGroupSendsToAMemberWhoWroteTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        net.register()
        RebuiltGroupFixture.setClock(NOW_MS, NOW_MS - 3_600_000L)
    }

    @After
    fun tearDown() {
        RebuiltGroupFixture.setClock(null, 0L)
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a member who wrote under the new key is sent the next message, and before that the send names who has not joined`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            val phoneA = RebuiltGroupFixture.member(A, admin, epoch = 3)
            val adminVm = MA3.chatVm(admin)
            own(adminVm, ownInvite(admin, A), tx = 1, to = A, height = 100)
            own(adminVm, ownKeyChange(admin, A, 3), tx = 2, to = A, height = 110)
            val f = GroupPaidOnceFixture().apply { scheduler = this@RebuiltGroupSendsToAMemberWhoWroteTest.scheduler }
            val gvm = MA3.groupVm(admin, f, AnnounceSeam())
            RebuiltGroupFixture.rotate(gvm, f)
            val n = admin.base.epochs.getValue(GID)
            val vmA = MA3.chatVm(phoneA)
            net.ofType(GroupMessageType.GROUP_KEY, NPUB.getValue(A)).forEach { MA3.relay(vmA, it, sender = C) }
            assertEquals("fixture: A did not take the new key", n, phoneA.base.epochs[GID])

            val before = RebuiltGroupFixture.sendResult(gvm, f)
            assertTrue(
                "before A wrote, a send does not say plainly that A has not joined: $before",
                before is GroupSendResult.NoActiveRecipients && before.notJoined.isNotEmpty(),
            )

            // A writes in the group under the new key, signed with its key for this phone, over the relays.
            val key = ZMSGGroupProtocol.decodeGroupKey(phoneA.base.keys.getValue("$GID|$n"))
            val signer = MA3.keyFor(phoneA, C)
            val memo =
                ZMSGGroupProtocol.createGroupMsgMessage(GID, 1, n, A, "synthetic hello", key) { data ->
                    E2EEncryption.sign(signer.privateKey, data)
                }
            MA3.relay(adminVm, memo, sender = A)

            assertEquals("A's row is not active after A wrote under the new key", MemberStatus.ACTIVE, admin.status(A))
            net.clear()
            val after = RebuiltGroupFixture.sendResult(gvm, f)
            val toA = net.ofType(GroupMessageType.GROUP_MSG, NPUB.getValue(A))
            assertTrue("the message after A wrote did not go to A: $after", toA.isNotEmpty())
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }
}
