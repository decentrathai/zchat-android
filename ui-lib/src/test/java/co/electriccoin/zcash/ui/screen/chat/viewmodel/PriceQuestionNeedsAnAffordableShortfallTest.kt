package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.sendattempt.HandshakeOffer
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.invoke
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.state
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.time.Instant

/**
 * ZC-1822 (QA2 lane k1c; found on the Seeker phone; money-path rule 1, honest offers) — a price question for a payment
 * the ZC-1417 margin rule rejects.
 *
 * K1 added the rule (spendableCovers): a transaction of cost C is affordable only when spendable == C or spendable >=
 * C + Zip317.MARGINAL_FEE_ZATOSHI, because the SDK refuses a non-zero change below 5,000 zatoshi. A Seeker wallet of
 * 18,000 zatoshi sent its first message after the contact had switched the chat to Shielded and was still asked "Pay for
 * this? … at least 0.00017 ZEC" with a Pay button (PaidAction.PEER_MOVED_TO_SHIELDED); Pay could only end in a refused
 * build. The same holds for every question that quotes one on-chain transaction with no affordability check before it:
 * a long message, a long reply, a long message's Retry, the disappearing timer, a mode change, a reaction, a payment
 * request and its Retry, and the handshake Retry.
 *
 * Wanted, at each of those questions, for a wallet the rule rejects (cost + 1,000 here; 18,000 for a one-memo cost):
 * - no question;
 * - a refusal before anything is stored, built or paid, showing the have/need text K1 added
 *   (ChatSendCopy.messageShortfall: chat_send_insufficient_balance_have_need, have = spendable, need = cost + 5,000);
 * - the typed text handed back as the existing refusals do (the send returns false: the screen keeps its input); a
 *   Retry's row stays FAILED.
 * Controls: exactly the cost and cost + 5,000 (17,000 and 22,000 for one memo) still get the question, and so does a
 * wallet whose Ironwood is still confirming (its existing wait-for-funds behaviour).
 *
 * Synthetic wallets, addresses and keys; no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriceQuestionNeedsAnAffordableShortfallTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1822marginpeer001"
        val PEER_RELAY_KEY: String = "c1822".padEnd(64, 'f')
        const val RICH = 1_000_000L
        const val CONFIRMING = 10_000L
        val LONG_TEXT = "synthetic long message for ZC-1822 ".repeat(40)
        const val SHORT_TEXT = "synthetic first message after the switch"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The selected account holds [spendable] confirmed Ironwood and [confirming] Ironwood change still confirming. */
    private suspend fun holdAccount(
        vm: ChatViewModel,
        spendable: Long,
        confirming: Long = 0L,
    ) {
        val base = syntheticZashiAccount()
        val account =
            base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(spendable), Zatoshi(confirming), Zatoshi(0))))
        AutoSpendVm.setField(vm, "accountDataSource", Zc101FakeAccountDataSource(account))
    }

    /** One run of a question site: the phone, its view model, and what the tap returned (null for a tap with no result). */
    private class Run(
        val phone: RealModeStorePhone,
        val vm: ChatViewModel,
        val returned: Boolean?,
        val requestsBefore: Int,
        val outgoingBefore: Int,
    )

    private fun outgoing(phone: RealModeStorePhone): Int =
        phone.real.getPendingMessages().count { it.peerAddress == PEER && it.isOutgoing }

    /** A question site: [prepare] builds the phone up to the tap (any setup paid from a rich wallet), [tap] asks. */
    private class Site(
        val name: String,
        val action: PaidAction,
        val prepare: suspend PriceQuestionNeedsAnAffordableShortfallTest.() -> Pair<RealModeStorePhone, ChatViewModel>,
        val tap: (ChatViewModel) -> Boolean?,
    )

    private suspend fun shieldedPhone(seam: HandshakeSeam = HandshakeSeam(PEER)): Pair<RealModeStorePhone, ChatViewModel> {
        val phone = RealModeStorePhone(PEER, ConversationMode.VAULT, consent = true, exchangeComplete = true, seam = seam)
        val vm = phone.viewModel()
        holdAccount(vm, RICH)
        return phone to vm
    }

    private suspend fun peerMovedPhone(): Pair<RealModeStorePhone, ChatViewModel> {
        val phone = RealModeStorePhone(PEER, ConversationMode.TUNNEL, consent = true, exchangeComplete = true, relayKey = PEER_RELAY_KEY)
        phone.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
        phone.relaysUp()
        val vm = phone.viewModel()
        invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.VAULT, System.currentTimeMillis(), PEER))
        awaitIdle(vm)
        assertEquals("fixture: the contact's switch to Shielded is adopted", ConversationMode.VAULT, phone.mode())
        return phone to vm
    }

    /**
     * A payment request that failed before any submit (an on-chain row, nothing paid) in a Shielded chat: its Retry asks.
     * The FAILED bubble is placed in the screen's list as a failed send leaves it.
     */
    private suspend fun failedRequestPhone(): Pair<RealModeStorePhone, ChatViewModel> {
        val (phone, vm) = shieldedPhone()
        val failed =
            ChatMessage(
                id = "pending_zc1822_request",
                txId = null,
                text = "synthetic request",
                timestamp = Instant.now(),
                isOutgoing = true,
                peerAddress = PEER,
                isPending = false,
                status = MessageStatus.FAILED,
                paymentRequest =
                    PaymentRequestInfo(
                        amountZatoshi = 100_000L,
                        reason = "synthetic request",
                        isPaid = false,
                        paidTxId = null
                    ),
            )
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(listOf(failed)))
        return phone to vm
    }

    /** A long Shielded message paid from a rich wallet whose build failed: a FAILED on-chain row its Retry asks for. */
    private suspend fun failedLongMessagePhone(): Pair<RealModeStorePhone, ChatViewModel> {
        val (phone, vm) = shieldedPhone(HandshakeSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic build failure")))
        vm.sendMessage(PEER, LONG_TEXT)
        awaitIdle(vm)
        assertTrue("fixture: the long message asks first", state(vm) is SendMessageState.NeedsPaidActionConfirmation)
        vm.confirmPaidAction()
        awaitIdle(vm)
        PaymentHarness.eventually { PaymentHarness.pendingMessages(vm).any { it.status == MessageStatus.FAILED } }
        awaitIdle(vm)
        assertEquals("fixture: the failed build paid nothing", 0, phone.seam.submits.get())
        vm.resetSendState()
        return phone to vm
    }

    private fun failedRowId(vm: ChatViewModel): String = PaymentHarness.pendingMessages(vm).first { it.status == MessageStatus.FAILED }.id

    private val sites: List<Site> =
        listOf(
            Site("contact moved the chat to Shielded", PaidAction.PEER_MOVED_TO_SHIELDED, { peerMovedPhone() }) {
                it.sendMessage(PEER, SHORT_TEXT)
            },
            Site("long message", PaidAction.LONG_MESSAGE, { shieldedPhone() }) { it.sendMessage(PEER, LONG_TEXT) },
            Site("long reply", PaidAction.LONG_MESSAGE, { shieldedPhone() }) {
                it.sendReply(PEER, LONG_TEXT, "synthetic-reply-target", "synthetic preview")
            },
            Site("Retry of a long message", PaidAction.LONG_MESSAGE, { failedLongMessagePhone() }) { vm ->
                vm.retryMessage(PEER, failedRowId(vm))
            },
            Site("disappearing timer", PaidAction.DISAPPEARING_TIMER, { shieldedPhone() }) {
                it.setDisappearingTtl(PEER, 3_600L)
                null
            },
            Site("mode change", PaidAction.MODE_CHANGE, { shieldedPhone() }) {
                it.changeConversationMode(PEER, ConversationMode.TUNNEL)
                null
            },
            Site("reaction", PaidAction.REACTION, { shieldedPhone() }) {
                it.sendReaction(PEER, "synthetic-target-message", "👍")
                null
            },
            Site("payment request", PaidAction.PAYMENT_REQUEST, { shieldedPhone() }) {
                it.sendPaymentRequest(PEER, 100_000L, "synthetic request")
            },
            Site("Retry of a payment request", PaidAction.PAYMENT_REQUEST_RETRY, { failedRequestPhone() }) { vm ->
                vm.retryMessage(PEER, failedRowId(vm))
            },
            Site("handshake Retry", PaidAction.HANDSHAKE_RETRY, { shieldedPhone() }) { vm ->
                val ask =
                    ChatViewModel::class.java
                        .getDeclaredMethod("askHandshakeRetry", String::class.java, HandshakeOffer::class.java)
                        .apply { isAccessible = true }
                try {
                    ask.invoke(vm, PEER, HandshakeOffer(SendAttemptKind.KEX, 1L))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
                null
            },
        )

    private suspend fun run(
        site: Site,
        spendable: Long,
        confirming: Long = 0L,
    ): Run {
        val (phone, vm) = site.prepare(this)
        holdAccount(vm, spendable, confirming)
        val requestsBefore = phone.seam.proposalRequests.get()
        val outgoingBefore = outgoing(phone)
        val returned = site.tap(vm)
        awaitIdle(vm)
        return Run(phone, vm, returned, requestsBefore, outgoingBefore)
    }

    /** The figure the site's question quotes, read from the question a rich wallet is asked. */
    private suspend fun quotedCost(site: Site): Long {
        val asked = state(run(site, RICH).vm)
        assertTrue("${site.name}: fixture: a rich wallet is asked first, was $asked", asked is SendMessageState.NeedsPaidActionConfirmation)
        asked as SendMessageState.NeedsPaidActionConfirmation
        assertEquals("${site.name}: fixture: the question", site.action, asked.confirmation.action)
        assertEquals("${site.name}: fixture: one on-chain transaction", 1, asked.confirmation.onChainMessages)
        return asked.confirmation.totalCost.value
    }

    private fun assertAsked(
        site: Site,
        label: String,
        run: Run,
    ) {
        val shown = state(run.vm)
        assertTrue(
            "${site.name}: $label must still be asked the price, was $shown",
            shown is SendMessageState.NeedsPaidActionConfirmation &&
                shown.confirmation.action == site.action,
        )
    }

    private fun assertRefused(
        site: Site,
        spendable: Long,
        cost: Long,
        run: Run,
    ) {
        val shown = state(run.vm)
        assertFalse(
            "${site.name}: a wallet of $spendable zatoshi against a cost of $cost (change ${spendable - cost}, below the SDK's " +
                "${Zip317.MARGINAL_FEE_ZATOSHI}) was asked the price of a payment that cannot happen",
            shown is SendMessageState.NeedsPaidActionConfirmation,
        )
        val expected = ChatSendCopy.messageShortfall(Zatoshi(spendable), Zatoshi(cost + Zip317.MARGINAL_FEE_ZATOSHI))
        assertEquals("${site.name}: the refusal must name what the wallet has and what this needs", SendMessageState.Error(expected), shown)
        assertEquals(
            "${site.name}: nothing may be built",
            run.requestsBefore,
            run.phone.seam.proposalRequests
                .get()
        )
        assertEquals(
            "${site.name}: nothing may be paid",
            0,
            run.phone.seam.submits
                .get()
        )
        assertEquals("${site.name}: nothing may be stored", run.outgoingBefore, outgoing(run.phone))
        if (run.returned != null) assertEquals("${site.name}: the refusal hands the text back (false)", false, run.returned)
    }

    @Test
    fun `the Seeker case - 18,000 zatoshi after the contact moved the chat to Shielded is refused with have and need`() =
        runBlocking {
            val site = sites.first { it.action == PaidAction.PEER_MOVED_TO_SHIELDED }
            assertEquals("fixture: the one-memo floor is 17,000", 17_000L, onChainSendMinimumCost().value)
            val run = run(site, 18_000L)
            assertRefused(site, 18_000L, 17_000L, run)
            assertEquals("the contact's switch stays Shielded", ConversationMode.VAULT, run.phone.mode())
        }

    @Test
    fun `control - 17,000 exactly and 22,000 after the contact moved the chat are still asked`() =
        runBlocking {
            val site = sites.first { it.action == PaidAction.PEER_MOVED_TO_SHIELDED }
            assertAsked(site, "17,000 exactly (no change)", run(site, 17_000L))
            assertAsked(site, "22,000 (change of 5,000)", run(site, 22_000L))
            assertAsked(site, "18,000 with Ironwood still confirming", run(site, 18_000L, CONFIRMING))
        }

    @Test
    fun `every one-transaction price question refuses a wallet the margin rule rejects`() =
        runBlocking {
            val failures = mutableListOf<String>()
            for (site in sites) {
                val cost = quotedCost(site)
                val spendable = cost + 1_000L
                try {
                    assertRefused(site, spendable, cost, run(site, spendable))
                } catch (e: AssertionError) {
                    failures += e.message.orEmpty()
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }

    @Test
    fun `control - every one-transaction price question still asks at the cost, at cost plus the margin and while funds confirm`() =
        runBlocking {
            for (site in sites) {
                val cost = quotedCost(site)
                assertAsked(site, "exactly the cost ($cost)", run(site, cost))
                assertAsked(
                    site,
                    "the cost plus the margin (${cost + Zip317.MARGINAL_FEE_ZATOSHI})",
                    run(site, cost + Zip317.MARGINAL_FEE_ZATOSHI)
                )
                assertAsked(site, "cost + 1,000 with Ironwood still confirming", run(site, cost + 1_000L, CONFIRMING))
            }
        }
}
