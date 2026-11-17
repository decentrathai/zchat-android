package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C1 (round 7 adversarial review of B1, items 1 and 2) — a group memo refused BEFORE anything was stored
 * is reported as "may have been sent".
 *
 * B1 made every failure that is not proven "nothing sent" a MAYBE_SENT. The proof it accepts is only
 * `SubmitRejectedException(nothingStored = true)` or an `InsufficientFundsException` in the cause chain. The
 * send chokepoint (CreateChunkedMessageProposalUseCase) also refuses, before any transaction exists, with
 * other types:
 *
 * - `OrchardSealedException`, when the wallet's spendable value is all in sealed Orchard (the state after an
 *   upgrade from 2.12.4, and while the first Ironwood deposit is still confirming);
 * - the ZC-102 `UnsupportedOperationException`, on EVERY background group send from a Keystone account;
 * - `PublicCrossingException` (and its Sapling / unreadable-proposal siblings), when the built proposal
 *   would publish the amount.
 *
 * All of these are thrown before `onSubmitStarting`, so nothing was stored and nothing can be broadcast. As
 * MAYBE_SENT they:
 *
 * - clear a group message's draft although nobody received it (the optimistic bubble is gone after a restart);
 * - mark an invite SENT (no Resend), so later rotations also pay GROUP_KEYs to a member who never got the key;
 * - hide the member from the kick / rotation warning and write them as holding the new key;
 * - delete the owed-key marker in the automatic repair for good, although nothing was sent.
 *
 * Also (item 2): a real MAYBE_SENT (the transaction was stored, then rejected) must still count in the admin's
 * RemovalIncomplete warning, without being written as owed (the follow-up repair pays nothing for it), and its
 * stored transaction id must be kept on the roster row for a later reconciliation.
 *
 * And (item 1, "do not trust InsufficientFundsException after the submit started"): a submit that stored T
 * and was then rejected with a shortfall-looking text must stay MAYBE_SENT.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMemoPreSubmitRefusalIsNothingSentTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private suspend fun viewModel(f: GroupPaidOnceFixture): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also {
            vms += it
            it.consumeGroupSendEvent()
        }
    }

    /** Where the refusal comes from: the selected account (sealed Orchard, Keystone) or the built proposal. */
    private enum class Refusal { SEALED_ORCHARD, KEYSTONE, PUBLIC_CROSSING }

    private fun fixture(
        refusal: Refusal,
        memberStatus: MemberStatus = MemberStatus.ACTIVE,
        memberInviteStatus: InviteStatus? = InviteStatus.SENT,
        memberPendingKeyEpoch: Int? = null,
    ): GroupPaidOnceFixture =
        GroupPaidOnceFixture(
            memberStatus = memberStatus,
            memberInviteStatus = memberInviteStatus,
            memberPendingKeyEpoch = memberPendingKeyEpoch,
            consent = true,
            failFirstProposalWith =
                if (refusal == Refusal.PUBLIC_CROSSING) PublicCrossingException(Zatoshi(90_000), Zatoshi(0)) else null,
            selectedAccount =
                when (refusal) {
                    Refusal.SEALED_ORCHARD -> ::sealedOrchardAccount
                    Refusal.KEYSTONE -> ::keystoneAccount
                    Refusal.PUBLIC_CROSSING -> null
                },
        )

    /** Spendable value only in sealed Orchard, none in Ironwood: the chokepoint refuses every peer send. */
    private suspend fun sealedOrchardAccount(): WalletAccount {
        val base = syntheticZashiAccount()
        return base.copy(unified = base.unified.copy(balance = WalletBalance(Zatoshi(90_000), Zatoshi(0), Zatoshi(0))))
    }

    private suspend fun keystoneAccount(): WalletAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-c1-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-c1-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingBubbles(vm: GroupViewModel): List<GroupMessage> =
        (
            GroupViewModel::class.java
                .getDeclaredField("pendingGroupMessages")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<Map<String, List<GroupMessage>>>
        ).value[GroupPaidOnceFixture.GROUP_ID].orEmpty()

    /** Every Int the RemovalIncomplete event carries, summed: owed members plus unconfirmed ones. */
    private fun removalIncompleteMembers(event: GroupSendResult?): Int =
        event!!
            .javaClass.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .sumOf { f ->
                f.isAccessible = true
                f.getInt(event)
            }

    // ── (a) a group message refused before anything was stored ──────────────────────────────────────

    private fun messageRefusedKeepsDraft(refusal: Refusal) =
        runBlocking {
            val f = fixture(refusal)
            f.proxy.setGroupDraft(GroupPaidOnceFixture.GROUP_ID, "hello group")
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
            assertEquals("fixture: the message quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("$refusal: nothing may reach the submit", 0, f.submits())
            val event = vm.groupSendEvent.value
            assertTrue(
                "$refusal: the send was refused before anything was stored, but the screen says it may have been " +
                    "sent (and clears the composer): $event",
                event is GroupSendResult.AllFailed,
            )
            assertEquals("$refusal: the typed text was deleted although nobody received it", "hello group", f.draft())
            assertTrue("$refusal: a bubble for a message nobody received stays", pendingBubbles(vm).isEmpty())
        }

    @Test
    fun `a group message refused because the funds are in sealed Orchard keeps its draft`() =
        messageRefusedKeepsDraft(Refusal.SEALED_ORCHARD)

    @Test
    fun `a group message refused on a Keystone account keeps its draft`() = messageRefusedKeepsDraft(Refusal.KEYSTONE)

    @Test
    fun `a group message refused because it would publish its amount keeps its draft`() = messageRefusedKeepsDraft(Refusal.PUBLIC_CROSSING)

    // ── (b) invites refused before anything was stored ──────────────────────────────────────────────

    private fun resentInviteRefusedIsFailed(refusal: Refusal) =
        runBlocking {
            val f = fixture(refusal, memberStatus = MemberStatus.INVITED, memberInviteStatus = InviteStatus.FAILED)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.resendInvite(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
            assertEquals("fixture: the resend quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals(0, f.submits())
            assertEquals(
                "$refusal: an invite refused before anything was stored is shown as sent (no Resend, and later key " +
                    "rotations pay for a key the member never had)",
                InviteStatus.FAILED,
                f.memberRow().inviteStatus,
            )
            assertTrue(
                "$refusal: the user must be told the invite was not sent: ${vm.groupSendEvent.value}",
                vm.groupSendEvent.value is GroupSendResult.InviteFailed,
            )
        }

    @Test
    fun `a resent invite refused because the funds are in sealed Orchard stays failed`() =
        resentInviteRefusedIsFailed(Refusal.SEALED_ORCHARD)

    @Test
    fun `a resent invite refused on a Keystone account stays failed`() = resentInviteRefusedIsFailed(Refusal.KEYSTONE)

    @Test
    fun `a resent invite refused because it would publish its amount stays failed`() =
        resentInviteRefusedIsFailed(Refusal.PUBLIC_CROSSING)

    @Test
    fun `a re-added member whose invite was refused before anything was stored is shown as failed`() =
        runBlocking {
            val f = fixture(Refusal.SEALED_ORCHARD, memberStatus = MemberStatus.LEFT, memberInviteStatus = null)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.addMemberToGroup(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
            assertEquals("fixture: the add quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals(0, f.submits())
            assertEquals("the refused invite is shown as sent", InviteStatus.FAILED, f.memberRow().inviteStatus)
            assertTrue(
                "the user must be told the invite was not sent: ${vm.groupSendEvent.value}",
                vm.groupSendEvent.value is GroupSendResult.InviteFailed,
            )
        }

    // ── (c) a rotation refused before anything was stored ───────────────────────────────────────────

    private fun rotationRefusedWarnsAndOwes(refusal: Refusal) =
        runBlocking {
            val f = fixture(refusal)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("fixture: the rotation quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: the rotation committed epoch 2", 2, f.epoch())
            assertEquals(0, f.submits())
            val event = vm.groupSendEvent.value
            assertTrue(
                "$refusal: the new key reached nobody, but the admin is not warned that the group is not clean: $event",
                event is GroupSendResult.RemovalIncomplete,
            )
            assertEquals(
                "$refusal: the member never received the new key but is written as holding it",
                2,
                f.memberRow().pendingKeyEpoch,
            )
        }

    @Test
    fun `a rotation refused because the funds are in sealed Orchard warns and leaves the member owed`() =
        rotationRefusedWarnsAndOwes(Refusal.SEALED_ORCHARD)

    @Test
    fun `a rotation refused on a Keystone account warns and leaves the member owed`() = rotationRefusedWarnsAndOwes(Refusal.KEYSTONE)

    // ── (d) the automatic owed-key repair refused before anything was stored ────────────────────────

    private fun repairRefusedStaysOwed(refusal: Refusal) =
        runBlocking {
            val f = fixture(refusal, memberPendingKeyEpoch = 1)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals(0, f.submits())
            assertEquals(
                "$refusal: the repair sent nothing, but the member's owed-key marker was deleted for good",
                1,
                f.memberRow().pendingKeyEpoch,
            )
        }

    @Test
    fun `the automatic key repair refused because the funds are in sealed Orchard keeps the member owed`() =
        repairRefusedStaysOwed(Refusal.SEALED_ORCHARD)

    @Test
    fun `the automatic key repair refused on a Keystone account keeps the member owed`() = repairRefusedStaysOwed(Refusal.KEYSTONE)

    @Test
    fun `the automatic key repair refused once for a public crossing pays the key once on the next open`() =
        runBlocking {
            val f = fixture(Refusal.PUBLIC_CROSSING, memberPendingKeyEpoch = 1)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals(0, f.submits())
            assertEquals("the refused repair must leave the member owed", 1, f.memberRow().pendingKeyEpoch)

            val reopened = viewModel(f)
            f.awaitNewJobs(reopened) { reopened.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("the next open repairs the key exactly once", 1, f.submits())
            assertNull(f.memberRow().pendingKeyEpoch)
        }

    // ── item 2: a real "may have been sent" counts in the warning and keeps its transaction id ─────

    @Test
    fun `a rotation whose key memo was stored then rejected warns the admin, pays nothing more and keeps the txid`() =
        runBlocking {
            val f = GroupPaidOnceFixture(firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected(), consent = true)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("fixture: the rotation committed epoch 2", 2, f.epoch())
            assertEquals("the follow-up repair paid again for a key memo that may still be broadcast", 1, f.submits())
            assertNull("a stored transaction must not be written as owed", f.memberRow().pendingKeyEpoch)
            val event = vm.groupSendEvent.value
            assertTrue(
                "the member may never get the new key (the network rejected it), but the admin is not warned: $event",
                event is GroupSendResult.RemovalIncomplete,
            )
            assertTrue("the warning must count the unconfirmed member", removalIncompleteMembers(event) >= 1)
            assertTrue(
                "the stored transaction id must be kept on the member's row for reconciliation: ${f.rosterJson()}",
                f.rosterJson()!!.contains("synthetic-stored-T"),
            )
        }

    @Test
    fun `an owed-key repair whose memo was stored then rejected keeps the txid on the member's row`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    memberPendingKeyEpoch = 1,
                    firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected(),
                    consent = true,
                )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals(1, f.submits())
            assertNull(f.memberRow().pendingKeyEpoch)
            assertTrue(
                "the stored transaction id must be kept on the member's row for reconciliation: ${f.rosterJson()}",
                f.rosterJson()!!.contains("synthetic-stored-T"),
            )
        }

    // ── item 1: an insufficient-funds text AFTER the submit started proves nothing ──────────────────

    @Test
    fun `a group message stored then rejected with a shortfall text is may-have-been-sent and is not retried`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    firstSubmitOutcome =
                        SubmitResult.Failure(
                            txIds = listOf("synthetic-stored-T"),
                            code = -26,
                            description = "Insufficient balance (synthetic rejection text)",
                        ),
                )
            f.proxy.setGroupDraft(GroupPaidOnceFixture.GROUP_ID, "hello group")
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
            f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("a stored transaction must never be paid again by a retry", 1, f.submits())
            val event = vm.groupSendEvent.value
            assertTrue(
                "the wallet stored the transaction, so a shortfall text after the submit proves nothing; the screen " +
                    "says nothing was sent and keeps the text for a second payment: $event",
                event is GroupSendResult.MaybeSent,
            )
            assertNull("the text was kept, so the next Send pays for the same message again", f.draft())
        }
}
