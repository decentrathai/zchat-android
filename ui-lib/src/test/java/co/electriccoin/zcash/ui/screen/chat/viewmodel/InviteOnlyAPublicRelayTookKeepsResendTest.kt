package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopySlots
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.D
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-420-G (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.6 items 3 to 5, §4.3; register ZC-420, high) — an invite that only
 * a public relay took shows "Invited, not confirmed" with a Resend that goes over the relays and asks no price.
 *
 * C adds D to the group while D's relays answer (shared 0, readable 1). Today the row is "Invited" (status SENT, which
 * stays), the Resend button is hidden, nothing remembers the copy, and a D on ZChat 2.12.4 is never invited.
 *
 * Wanted: one "gi" mark; Group Settings lists the row in `inviteUnconfirmed`, shows the new badge and the Resend button.
 * Settings opened while a shared relay holds the kept wrap: the mark goes. After a restart (no kept wrap) settings open
 * sends nothing (never an automatic re-invite). Resend on that row asks no price and publishes one invite at the current
 * key over the relays: the mark goes, the status stays SENT, nothing is proposed. With no relay to publish to: no price
 * question, nothing proposed, status and mark as they were. With a Keystone account selected: nothing published, the
 * Keystone sentence, status and mark as they were. The member joined: the mark goes. A later invite (after a removal)
 * forgets the earlier invitation's mark before its relay step, so a failed re-invite keeps today's quoted Resend; an
 * invite that ends on chain leaves no mark. Control: a FAILED row with no mark still raises today's quoted Resend. All
 * addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteOnlyAPublicRelayTookKeepsResendTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val worlds = mutableListOf<GroupCopyWorld>()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        worlds.forEach { it.close() }
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val slot = RelayCopySlots.groupInvite(GID, D)

    private fun world(): GroupCopyWorld =
        GroupCopyWorld(listOf(A to MemberStatus.ACTIVE)).also {
            it.contact(D)
            worlds += it
        }

    private fun fixture(selectedAccount: (suspend () -> WalletAccount)? = null) =
        GroupPaidOnceFixture(selectedAccount = selectedAccount).apply {
            scheduler = this@InviteOnlyAPublicRelayTookKeepsResendTest.scheduler
        }

    private suspend fun keystoneAccount(): WalletAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-g420-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-g420-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }

    /** C adds D while D's relays answer [d]; the outbox of this process keeps the wrap. */
    private suspend fun add(
        w: GroupCopyWorld,
        d: Pair<Int, Int> = 0 to 1,
    ): Pair<GroupViewModel, GroupPaidOnceFixture> {
        w.answer(D, d.first, d.second)
        w.net.keptNotShared()
        val f = fixture()
        val vm = w.groupVm(f)
        f.awaitNewJobs(vm) { vm.addMemberToGroup(GID, D) }
        assertNull("fixture: adding a member reachable over the relays asked a price", vm.paidActionConfirmation.value)
        return vm to f
    }

    private suspend fun settings(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
    ): GroupSettingsState.Success {
        f.awaitNewJobs(vm) { vm.loadGroupSettings(GID) }
        return vm.groupSettingsState.value as GroupSettingsState.Success
    }

    @Suppress("UNCHECKED_CAST")
    private fun inviteUnconfirmed(state: GroupSettingsState.Success): Set<String> {
        val getter =
            GroupSettingsState.Success::class.java.methods.firstOrNull { it.name == "getInviteUnconfirmed" }
                ?: throw AssertionError("GroupSettingsState.Success has no inviteUnconfirmed (ZC-420-G)")
        return getter.invoke(state) as Set<String>
    }

    private fun assertNothingProposed(w: GroupCopyWorld) =
        assertEquals("an invite only a public relay took made an on-chain proposal", emptyList<PaidMemo>(), w.seam.proposals.toList())

    @Test
    fun `an invite only a public relay took stays SENT, is marked, and settings show the badge and Resend`() =
        runBlocking {
            val w = world()
            val (vm, f) = add(w)

            assertEquals("the invite's status is not today's SENT", InviteStatus.SENT, w.row(D).inviteStatus)
            assertTrue("no gi mark for the invite only a public relay took", slot in w.marks.map)
            assertEquals("settings do not list the row as not confirmed", setOf(D), inviteUnconfirmed(settings(vm, f)))
            assertNothingProposed(w)

            val view = ChatResourceText.source(SETTINGS_VIEW)
            val resend = view.substringAfter("canResendInvite =").substringBefore("onResendInvite =")
            assertTrue("the Resend predicate does not include a not-confirmed invite: $resend", "inviteUnconfirmed" in resend)
            assertTrue("the badge does not read the not-confirmed text", "R.string.group_invite_not_confirmed_status" in view)
        }

    @Test
    fun `settings opened while a shared relay holds the kept wrap drop the mark`() =
        runBlocking {
            val w = world()
            val (vm, f) = add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.net.keptShared()

            val state = settings(vm, f)

            assertFalse("the mark stayed", slot in w.marks.map)
            val after = vm.groupSettingsState.value as GroupSettingsState.Success
            assertTrue("the row is still listed as not confirmed", D !in inviteUnconfirmed(after))
            assertEquals(MemberStatus.INVITED, state.members.single { it.address == D }.status)
        }

    @Test
    fun `after a restart settings open sends nothing, never an automatic re-invite`() =
        runBlocking {
            val w = world()
            add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.net.kept = null
            w.answer(D, 1, 1)
            w.net.clear()
            val f = fixture()
            val vm = w.groupVm(f)

            settings(vm, f)
            w.open(vm, f)

            assertTrue("an invite was rebuilt by itself", w.copies(GroupMessageType.GROUP_INVITE, D).isEmpty())
            assertTrue("the mark went with nothing confirmed", slot in w.marks.map)
        }

    @Test
    fun `Resend on a not-confirmed row asks no price and publishes one invite at the current key over the relays`() =
        runBlocking {
            val w = world()
            add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.net.kept = null
            w.answer(D, 1, 1)
            w.net.clear()
            val f = fixture()
            val vm = w.groupVm(f)

            f.awaitNewJobs(vm) { vm.resendInvite(GID, D) }

            assertNull("the Resend of a not-confirmed invite asked a price", vm.paidActionConfirmation.value)
            val invites = w.copies(GroupMessageType.GROUP_INVITE, D)
            assertEquals("not exactly one invite published to D", 1, invites.size)
            assertEquals(1, JSONObject(ZMSGGroupProtocol.parsePayload(invites.single())!!).getInt("key_epoch"))
            assertFalse("the mark stayed after a shared relay took the invite", slot in w.marks.map)
            assertEquals(InviteStatus.SENT, w.row(D).inviteStatus)
            assertNothingProposed(w)
        }

    @Test
    fun `Resend with no relay to publish to asks no price and changes nothing`() =
        runBlocking {
            val w = world()
            add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.net.kept = null
            val f = fixture()
            val vm = w.groupVm(f)
            NostrChatBridge.unregisterPublisher()

            f.awaitNewJobs(vm) { vm.resendInvite(GID, D) }

            assertNull("the Resend of a not-confirmed invite asked a price", vm.paidActionConfirmation.value)
            assertNothingProposed(w)
            assertEquals(InviteStatus.SENT, w.row(D).inviteStatus)
            assertTrue("the mark went with nothing sent", slot in w.marks.map)
        }

    @Test
    fun `Resend on a not-confirmed row with a Keystone account selected publishes nothing and says so`() =
        runBlocking {
            val w = world()
            add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.net.clear()
            val f = fixture(selectedAccount = ::keystoneAccount)
            val vm = w.groupVm(f)

            f.awaitNewJobs(vm) { vm.resendInvite(GID, D) }

            assertTrue("an invite went out on a Keystone account", w.copies(GroupMessageType.GROUP_INVITE, D).isEmpty())
            assertTrue("the kept wrap was re-sent on a Keystone account", w.net.keptAsks.isEmpty())
            val event = vm.groupSendEvent.value
            assertTrue("no Keystone refusal: $event", event is GroupSendResult.InviteFailed)
            assertEquals(ChatSendCopy.GROUP_KEYSTONE_REFUSED, (event as GroupSendResult.InviteFailed).refusal)
            assertEquals("the status changed", InviteStatus.SENT, w.row(D).inviteStatus)
            assertTrue("the mark changed", slot in w.marks.map)
            assertNothingProposed(w)
        }

    @Test
    fun `the member joined drops the mark`() =
        runBlocking {
            val w = world()
            val (vm, f) = add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.setRow(D) { it.copy(status = MemberStatus.ACTIVE) }

            w.open(vm, f)

            assertFalse("the mark of a member who joined stayed", slot in w.marks.map)
        }

    @Test
    fun `removed and added again with no relay answering leaves no mark of the first invitation, and Resend is today's quoted one`() =
        runBlocking {
            val w = world()
            val (vm, f) = add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.answer(D, 1, 1)
            f.awaitNewJobs(vm) { vm.kickMember(GID, D) }
            vm.paidActionConfirmation.value?.let { MA3.confirm(vm, f) }
            assertEquals("fixture: D was not removed", MemberStatus.LEFT, w.row(D).status)

            w.answer(D, 0, 0)
            f.awaitNewJobs(vm) { vm.addMemberToGroup(GID, D) }
            assertEquals("fixture: the second invite did not fail", InviteStatus.FAILED, w.row(D).inviteStatus)
            assertFalse("the first invitation's mark sits on the new invitation", slot in w.marks.map)

            // Today's quoted Resend: with no free route to D its price is asked.
            w.admin.base.nostrKeys
                .remove(D)
            f.awaitNewJobs(vm) { vm.resendInvite(GID, D) }
            assertEquals(PaidAction.GROUP_RESEND_INVITE, vm.paidActionConfirmation.value?.action)
            vm.dismissPaidAction()
            assertNothingProposed(w)
        }

    @Test
    fun `an invite that ends SENT on chain leaves no mark`() =
        runBlocking {
            val w = world()
            val (vm, f) = add(w)
            assertTrue("fixture: the invite left no mark", slot in w.marks.map)
            w.answer(D, 1, 1)
            f.awaitNewJobs(vm) { vm.kickMember(GID, D) }
            vm.paidActionConfirmation.value?.let { MA3.confirm(vm, f) }
            assertEquals("fixture: D was not removed", MemberStatus.LEFT, w.row(D).status)

            // A later add with no free route to D: quoted, confirmed, paid on chain.
            w.admin.base.nostrKeys
                .remove(D)
            f.awaitNewJobs(vm) { vm.addMemberToGroup(GID, D) }
            assertNotNull("fixture: the paid add asked no price", vm.paidActionConfirmation.value)
            MA3.confirm(vm, f)

            assertEquals("fixture: the paid invite is not SENT", InviteStatus.SENT, w.row(D).inviteStatus)
            assertEquals("fixture: the invite was not paid once", 1, w.seam.proposals.count { it.recipient == D })
            assertFalse("the first invitation's mark sits on an invite that went on chain", slot in w.marks.map)
        }

    @Test
    fun `control - a FAILED row with no mark still raises today's quoted Resend`() =
        runBlocking {
            val w = world()
            val (vm, f) = add(w, d = 0 to 0)
            assertEquals("fixture: the invite did not fail", InviteStatus.FAILED, w.row(D).inviteStatus)
            assertFalse("a failed invite left a mark", slot in w.marks.map)
            w.admin.base.nostrKeys
                .remove(D)

            f.awaitNewJobs(vm) { vm.resendInvite(GID, D) }

            assertEquals(PaidAction.GROUP_RESEND_INVITE, vm.paidActionConfirmation.value?.action)
            vm.dismissPaidAction()
            assertNothingProposed(w)
        }

    private companion object {
        const val SETTINGS_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSettingsView.kt"
    }
}
