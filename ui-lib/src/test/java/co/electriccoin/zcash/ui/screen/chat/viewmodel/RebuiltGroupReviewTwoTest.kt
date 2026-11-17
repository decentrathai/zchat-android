package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-757, T7bG review round 2 (19 Oct, findings 1-4) — what the rebuilt-group handling did beyond the time it was for.
 *
 * 1. Our own on-chain removal and invitation are read back at every history pass for good (the handlers checked only that
 *    the group was rebuilt, which stays true): a member removed on-chain and added again after the new key is set LEFT
 *    again at the next pass; an old mined invitation marks a later failed invitation delivered. Wanted: they run only
 *    while the group still needs its new key.
 * 2. The newest block time is known only after a history pass in this process: before one, with the phone's clock set
 *    back, the first new key is numbered from the clock alone, below what members hold. Wanted: the first key change of
 *    a rebuilt group is refused (nothing made, sent or paid; the banner says why) until a pass has read the block time.
 * 3. The owed-key resend is blocked only on its public entry; the pass on opening the group runs the inner one and sends
 *    the current (old) key in a group that still needs its new key. Wanted: no key goes out before the new key.
 * 4. The rebuilt confirmation shows for good, and "only to members … secure chat" is narrower than the code (an Open
 *    member gets the key over the relays). Wanted: shown only before the new key, worded as the code sends.
 *
 * All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltGroupReviewTwoTest {
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

    private suspend fun gvm(admin: AnnouncerPhone): Pair<GroupViewModel, GroupPaidOnceFixture> {
        val f = GroupPaidOnceFixture().apply { scheduler = this@RebuiltGroupReviewTwoTest.scheduler }
        return MA3.groupVm(admin, f, AnnounceSeam()) to f
    }

    private fun ownRemoval(
        admin: AnnouncerPhone,
        removed: String,
        to: String,
        epoch: Int,
    ): String {
        val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), admin.base.sessionWith(to))
        val signed = ZMSGGroupProtocol.groupKickSignedData(GID, removed, C, epoch, wrapped)
        val sig = E2EEncryption.sign(MA3.keyFor(admin, to).privateKey, signed)
        return ZMSGGroupProtocol.createGroupKickMessage(GID, removed, C, epoch, wrapped, sig)
    }

    @Test
    fun `1a a member removed on-chain and added again after the new key is not set LEFT again by the next pass`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 4)
            RebuiltGroupFixture.member(B, admin, epoch = 3)
            val vm = MA3.chatVm(admin)
            val history =
                listOf(
                    Triple(ownInvite(admin, A), 1, A),
                    Triple(ownInvite(admin, B), 2, B),
                    Triple(ownRemoval(admin, B, A, 4), 3, A),
                )
            history.forEach { (memo, tx, to) -> own(vm, memo, tx, to, 100L + tx) }
            assertEquals("fixture: B is not LEFT after our own removal", MemberStatus.LEFT, admin.status(B))
            val (gvm, f) = gvm(admin)
            RebuiltGroupFixture.rotate(gvm, f)
            f.awaitNewJobs(gvm) { gvm.addMemberToGroup(GID, B) }
            if (gvm.paidActionConfirmation.value != null) MA3.confirm(gvm, f)
            assertEquals("fixture: B was not added again", MemberStatus.INVITED, admin.status(B))

            history.forEach { (memo, tx, to) -> own(vm, memo, tx, to, 100L + tx) }

            assertEquals("the next pass set B LEFT again from our old removal", MemberStatus.INVITED, admin.status(B))
        }

    @Test
    fun `1b an old mined invitation does not mark a later failed invitation delivered`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 3)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), 1, A, 100)
            own(vm, ownKeyChange(admin, A, 3), 2, A, 110)
            val (gvm, f) = gvm(admin)
            RebuiltGroupFixture.rotate(gvm, f)
            // A later invitation of A failed (as a Resend that sent nothing records it).
            admin.base.rosters[GID] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    admin.base.roster(GID).map { if (it.address == A) it.copy(inviteStatus = InviteStatus.FAILED) else it },
                )

            own(vm, ownInvite(admin, A), 1, A, 100)

            assertEquals(
                "an old mined invitation marked a later failed one delivered",
                InviteStatus.FAILED,
                admin.base
                    .roster(GID)
                    .single { it.address == A }
                    .inviteStatus,
            )
        }

    @Test
    fun `2 before a pass read the block time, a clock set back cannot number the first new key - it is refused and says why`() =
        runBlocking {
            RebuiltGroupFixture.setClock(NOW_MS - 365L * 24 * 3_600_000L, 0L)
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 3)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), 1, A, 100)
            own(vm, ownKeyChange(admin, A, 3), 2, A, 110)
            val (gvm, f) = gvm(admin)
            RebuiltGroupFixture.rotate(gvm, f)

            assertEquals("a new key was numbered with no block time known", 3, admin.base.epochs[GID])
            assertTrue("a key change went out", RebuiltGroupFixture.keyChangesTo(net, NPUB.getValue(A)).isEmpty())
            val result = gvm.groupSendEvent.value
            val expected =
                runCatching {
                    Class
                        .forName("co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy")
                        .getField("REBUILT_KEY_WAITS_FOR_HISTORY")
                        .get(null)
                }.getOrNull()
            assertTrue("the refusal is not said: $result", expected != null && result is GroupSendResult.RemovalIncomplete)
            assertEquals("the refusal names another reason", expected, (result as GroupSendResult.RemovalIncomplete).refusal)
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())

            RebuiltGroupFixture.setClock(NOW_MS - 365L * 24 * 3_600_000L, NOW_MS)
            RebuiltGroupFixture.rotate(gvm, f)
            val expectedNumber = (NOW_MS / 60_000L).toInt() + 1
            assertEquals("after the block time is known the number does not follow it", expectedNumber, admin.base.epochs[GID])
        }

    @Test
    fun `3 opening a rebuilt group that needs its new key sends no owed key under the old one`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 0)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), 1, A, 100)
            assertTrue("fixture: the rebuild did not find the key of number 0", admin.base.keys.containsKey("$GID|0"))
            // An older build left A active and owed the current key.
            admin.base.rosters[GID] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    admin.base.roster(GID).map {
                        if (it.address == A) it.copy(status = MemberStatus.ACTIVE, pendingKeyEpoch = 0) else it
                    },
                )
            val (gvm, f) = gvm(admin)
            MA3.load(gvm, f)
            assertTrue("the old key went to A from the pass on open", RebuiltGroupFixture.keyChangesTo(net, NPUB.getValue(A)).isEmpty())
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `4 the rebuilt confirmation shows only before the new key and says what the code sends`() {
        val settings = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSettingsView.kt")
        assertTrue(
            "the rebuilt confirmation is shown for good, not only while the group needs its new key",
            Regex("""RebuiltGroupKey\s*\.\s*stage\(""").containsMatchIn(settings) && "NEEDS_KEY" in settings,
        )
        val en = ChatResourceText.strings("values")["group_rotate_confirm_body_rebuilt"].orEmpty()
        assertFalse("the rebuilt confirmation says 'secure chat', narrower than the relay route", "secure chat" in en)
        val es = ChatResourceText.strings("values-es")["group_rotate_confirm_body_rebuilt"].orEmpty()
        assertFalse("the Spanish rebuilt confirmation says 'chat seguro'", "chat seguro" in es)
        assertNotEquals("fixture: no English text", "", en)
    }
}
