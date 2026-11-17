package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 D8 (REVIEW_7C F3, smaller issue 1) — a group banner blames the balance for a refusal no balance can fix.
 *
 * The send chokepoint refuses a group memo before anything is stored when the wallet's value is all in sealed
 * Orchard, when a Keystone account is selected (ZC-102: an automatic send can never be signed there), while the
 * wallet's Ironwood is still confirming, or when the built proposal would publish its amount. GroupViewModel has no
 * copy for any of these: the refused message becomes AllFailed ("…check your balance and try again"), a refused
 * invite InviteFailed ("…check your balance and tap Resend…") and a refused key rotation RemovalIncomplete
 * ("…retrying automatically", while every retry meets the same refusal). The user is told to add money that would
 * change nothing.
 *
 * Wanted: the banner says the refusal's own sentence (ChatSendCopy.ORCHARD_POOL_SEALED, the new
 * GROUP_KEYSTONE_REFUSED, OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE, the public-crossing text), never the
 * balance hint, and a rotation is not called "retrying automatically". A genuine shortfall keeps the balance hint
 * (control).
 *
 * The banner text is what the group screen builds from the event with the English resources. The screen's builder
 * is `groupSendBannerText` (GroupSendBanner.kt) when this build has one; before it existed, GroupDetailView built
 * these three banners inline from group_send_all_failed, group_invite_failed and group_removal_incomplete, and
 * [banner] reproduces that. All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupRefusalBannerTest {
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

    private enum class Refusal { SEALED_ORCHARD, KEYSTONE, IRONWOOD_CONFIRMING, PUBLIC_CROSSING, SHORTFALL }

    private fun fixture(
        refusal: Refusal,
        memberStatus: MemberStatus = MemberStatus.ACTIVE,
        memberInviteStatus: InviteStatus? = InviteStatus.SENT,
    ): GroupPaidOnceFixture =
        GroupPaidOnceFixture(
            memberStatus = memberStatus,
            memberInviteStatus = memberInviteStatus,
            consent = true,
            failFirstProposalWith =
                when (refusal) {
                    Refusal.PUBLIC_CROSSING -> PublicCrossingException(Zatoshi(90_000), Zatoshi(0))
                    else -> null
                },
            // D1: the SDK found the shortfall after the submit started and stored nothing (a zero-balance wallet).
            firstSubmitOutcome =
                if (refusal == Refusal.SHORTFALL) {
                    SubmitResult.Failure(
                        txIds = emptyList(),
                        code = 0,
                        description = "Insufficient balance (have 0, need 17000 including fee)",
                    )
                } else {
                    SubmitResult.Success(txIds = listOf("synthetic-first-txid"))
                },
            selectedAccount =
                when (refusal) {
                    Refusal.SEALED_ORCHARD -> ::sealedOrchardAccount
                    Refusal.KEYSTONE -> ::keystoneAccount
                    Refusal.IRONWOOD_CONFIRMING -> ::ironwoodConfirmingAccount
                    Refusal.PUBLIC_CROSSING, Refusal.SHORTFALL -> null
                },
        )

    private suspend fun viewModel(
        f: GroupPaidOnceFixture,
        heights: HeightFeed? = null,
    ): GroupViewModel {
        f.scheduler = scheduler
        return f.groupViewModel().also { vm ->
            vms += vm
            vm.consumeGroupSendEvent()
            if (heights != null) {
                GroupViewModel::class.java
                    .getDeclaredField("synchronizerProvider")
                    .apply { isAccessible = true }
                    .set(vm, heights.provider)
            }
        }
    }

    /** Spendable value only in sealed Orchard, none in Ironwood: the chokepoint refuses every peer send. */
    private suspend fun sealedOrchardAccount(): WalletAccount {
        val base = syntheticZashiAccount()
        return base.copy(unified = base.unified.copy(balance = WalletBalance(Zatoshi(90_000), Zatoshi(0), Zatoshi(0))))
    }

    /** Some Orchard left and Ironwood that never finishes confirming here: "wait for the confirmation", until the cap. */
    private suspend fun ironwoodConfirmingAccount(): WalletAccount {
        val base = syntheticZashiAccount()
        return base.copy(
            unified =
                base.unified.copy(
                    balance = WalletBalance(Zatoshi(90_000), Zatoshi(0), Zatoshi(0)),
                    ironwood = WalletBalance(Zatoshi(0), Zatoshi(50_000), Zatoshi(0)),
                ),
        )
    }

    private suspend fun keystoneAccount(): WalletAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-d8-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-d8-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }

    /**
     * Run [block] and drive the jobs it starts to the end, mining one block each time a send waits for the next one
     * (only when [heights] is given; the fixture's own chain never moves).
     */
    private suspend fun drive(
        vm: GroupViewModel,
        heights: HeightFeed?,
        block: suspend () -> Unit,
    ) {
        val before: Set<Job> =
            vm.viewModelScope.coroutineContext.job.children
                .toSet()
        block()
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            while (vm.viewModelScope.coroutineContext.job.children
                    .any { it !in before && it.isActive }
            ) {
                if (heights != null && heights.isWaiting()) heights.advance()
                scheduler.advanceTimeBy(STEP_MS)
                scheduler.runCurrent()
                delay(5)
            }
        }
    }

    private suspend fun sendMessage(
        f: GroupPaidOnceFixture,
        vm: GroupViewModel,
        heights: HeightFeed? = null,
        submits: Int = 0,
    ): GroupSendResult? {
        f.proxy.setGroupDraft(GroupPaidOnceFixture.GROUP_ID, "hello group")
        drive(vm, heights) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
        assertEquals("fixture: the message quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
        drive(vm, heights) { vm.confirmPaidAction() }
        assertEquals("fixture: submits that reached the SDK", submits, f.submits())
        return vm.groupSendEvent.value
    }

    private suspend fun resendInvite(
        f: GroupPaidOnceFixture,
        vm: GroupViewModel,
    ): GroupSendResult? {
        drive(vm, null) { vm.resendInvite(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
        assertEquals("fixture: the resend quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
        drive(vm, null) { vm.confirmPaidAction() }
        assertEquals("fixture: nothing may reach the submit", 0, f.submits())
        return vm.groupSendEvent.value
    }

    private suspend fun rotate(
        f: GroupPaidOnceFixture,
        vm: GroupViewModel,
    ): GroupSendResult? {
        drive(vm, null) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }
        assertEquals("fixture: the rotation quotes one on-chain message", 1, vm.paidActionConfirmation.value?.onChainMessages)
        drive(vm, null) { vm.confirmPaidAction() }
        assertEquals("fixture: nothing may reach the submit", 0, f.submits())
        return vm.groupSendEvent.value
    }

    // ── a group message ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a group message refused because the funds are in sealed Orchard says so, not check your balance`() =
        runBlocking {
            val f = fixture(Refusal.SEALED_ORCHARD)
            val event = sendMessage(f, viewModel(f))

            assertTrue("fixture: nobody got the message: $event", event is GroupSendResult.AllFailed)
            val text = banner(event!!)
            assertFalse("a sealed-Orchard refusal is blamed on the balance: $text", text.contains("balance"))
            assertEquals(ChatSendCopy.ORCHARD_POOL_SEALED, text)
        }

    @Test
    fun `a group message refused on a Keystone account says Keystone accounts cannot send group messages`() =
        runBlocking {
            val f = fixture(Refusal.KEYSTONE)
            val event = sendMessage(f, viewModel(f))

            assertTrue("fixture: nobody got the message: $event", event is GroupSendResult.AllFailed)
            val text = banner(event!!)
            assertFalse("a Keystone refusal is blamed on the balance: $text", text.contains("balance"))
            assertEquals(GROUP_KEYSTONE_REFUSED, text)
        }

    @Test
    fun `a group message still refused after the wait for Ironwood to confirm says it is confirming`() =
        runBlocking {
            val f = fixture(Refusal.IRONWOOD_CONFIRMING)
            val heights = HeightFeed()
            val event = sendMessage(f, viewModel(f, heights), heights)

            assertTrue("fixture: nobody got the message: $event", event is GroupSendResult.AllFailed)
            val text = banner(event!!)
            assertFalse("funds that are only confirming are blamed on the balance: $text", text.contains("balance"))
            // Not "your first Ironwood funds": the pending Ironwood may be our own change (Round 8 D7, sweep #10).
            assertEquals(OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE, text)
        }

    @Test
    fun `a group message refused because it would publish its amount says so`() =
        runBlocking {
            val f = fixture(Refusal.PUBLIC_CROSSING)
            val event = sendMessage(f, viewModel(f))

            assertTrue("fixture: nobody got the message: $event", event is GroupSendResult.AllFailed)
            val text = banner(event!!)
            // Its own text names "your Ironwood balance"; the balance hint is what must go.
            assertFalse("a public-crossing refusal is blamed on the balance: $text", text.contains("check your balance"))
            assertEquals(PublicCrossingException.PUBLIC_CROSSING_MESSAGE, text)
        }

    @Test
    fun `control - a genuine shortfall keeps the check-your-balance banner`() =
        runBlocking {
            val f = fixture(Refusal.SHORTFALL)
            val event = sendMessage(f, viewModel(f), submits = 1)

            assertTrue("fixture: nobody got the message: $event", event is GroupSendResult.AllFailed)
            assertEquals(ChatResourceText.english(R.string.group_send_all_failed), banner(event!!))
        }

    // ── an invite ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a resent invite refused because the funds are in sealed Orchard names the refusal, not the balance`() =
        runBlocking {
            val f = fixture(Refusal.SEALED_ORCHARD, memberStatus = MemberStatus.INVITED, memberInviteStatus = InviteStatus.FAILED)
            val event = resendInvite(f, viewModel(f))

            assertTrue("fixture: the invite failed: $event", event is GroupSendResult.InviteFailed)
            val text = banner(event!!)
            assertFalse("a sealed-Orchard refusal is blamed on the balance: $text", text.contains("balance"))
            assertTrue("the banner must say why: $text", text.contains(ChatSendCopy.ORCHARD_POOL_SEALED))
            assertTrue("the banner still says the invite was not sent: $text", text.contains("Couldn't invite 1 member"))
        }

    @Test
    fun `a resent invite refused on a Keystone account names the refusal, not the balance`() =
        runBlocking {
            val f = fixture(Refusal.KEYSTONE, memberStatus = MemberStatus.INVITED, memberInviteStatus = InviteStatus.FAILED)
            val event = resendInvite(f, viewModel(f))

            assertTrue("fixture: the invite failed: $event", event is GroupSendResult.InviteFailed)
            val text = banner(event!!)
            assertFalse("a Keystone refusal is blamed on the balance: $text", text.contains("balance"))
            assertTrue("the banner must say why: $text", text.contains(GROUP_KEYSTONE_REFUSED))
        }

    // ── a key rotation ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a rotation refused on a Keystone account is not called retrying automatically`() =
        runBlocking {
            val f = fixture(Refusal.KEYSTONE)
            val event = rotate(f, viewModel(f))

            assertTrue("fixture: the new key reached nobody: $event", event is GroupSendResult.RemovalIncomplete)
            val text = banner(event!!)
            assertFalse(
                "every automatic retry meets the same Keystone refusal, but the banner says it retries: $text",
                text.contains("retrying automatically"),
            )
            assertTrue("the banner must say why: $text", text.contains(GROUP_KEYSTONE_REFUSED))
            assertTrue(
                "the admin must still be told the member lacks the new key: $text",
                text.contains("1 member hasn't received the new group key yet"),
            )
        }

    @Test
    fun `a rotation refused because the funds are in sealed Orchard is not called retrying automatically`() =
        runBlocking {
            val f = fixture(Refusal.SEALED_ORCHARD)
            val event = rotate(f, viewModel(f))

            assertTrue("fixture: the new key reached nobody: $event", event is GroupSendResult.RemovalIncomplete)
            val text = banner(event!!)
            assertFalse(text, text.contains("retrying automatically"))
            assertTrue("the banner must say why: $text", text.contains(ChatSendCopy.ORCHARD_POOL_SEALED))
        }

    // ── the banner as the group screen builds it ─────────────────────────────────────────────────────

    private fun banner(event: GroupSendResult): String {
        val string: (Int) -> String = { id -> ChatResourceText.english(id) }
        val plural: (Int, Int, List<Any>) -> String = { id, quantity, args -> ChatResourceText.englishPlural(id, quantity, args) }
        val builder =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.view.GroupSendBannerKt") }
                .getOrNull()
                ?.declaredMethods
                ?.firstOrNull { it.name == "groupSendBannerText" }
        if (builder != null) {
            builder.isAccessible = true
            return builder.invoke(null, event, string, plural) as String
        }
        // Before GroupSendBanner.kt: GroupDetailView's inline text for these three events.
        return when (event) {
            is GroupSendResult.AllFailed -> string(R.string.group_send_all_failed)
            is GroupSendResult.InviteFailed ->
                plural(R.plurals.group_invite_failed, event.addresses.size, listOf(event.addresses.size))
            is GroupSendResult.RemovalIncomplete ->
                listOfNotNull(
                    event.pendingMembers.takeIf { it > 0 }?.let { plural(R.plurals.group_removal_incomplete, it, listOf(it)) },
                    event.unconfirmedMembers.takeIf { it > 0 }?.let { plural(R.plurals.group_removal_unconfirmed, it, listOf(it)) },
                ).joinToString("\n")
            else -> error("not a banner this test reads: $event")
        }
    }

    private companion object {
        const val STEP_MS = 100L

        /** The new ChatSendCopy.GROUP_KEYSTONE_REFUSED (the parity test pins the constant and its resources to it). */
        const val GROUP_KEYSTONE_REFUSED =
            "Group messages can't be sent from a Keystone account in this version. Nothing was sent or paid."
    }
}
