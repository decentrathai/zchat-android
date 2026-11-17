package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
import java.util.UUID

/**
 * ZC-1909 (owner test of release APK 17a900ce0411 at 65f4f1323, 16 Nov 2026; register high; money-path rules 2, 3, 4)
 * — the first message in a fresh Shielded chat: the key exchange (KEX) was paid, the message then showed "sending" for
 * minutes and ended in the red "Insufficient balance for a Shielded message … Add ZEC and try again", although the
 * KEX's own change would pay it once confirmed. Retry then worked.
 *
 * Real ChatViewModel send over [FundsConfirmingChat] (the plan check answers Fundable, as the gate did on the phone).
 * After the KEX's submit, the wallet holds only that KEX's change, still confirming, of cost + 5,000 .. 9,999; the SDK
 * refuses the message ("Insufficient balance", need 17,000) until the change confirms. Each block advances both the
 * synchronizer's network height (the tip the note wait after the KEX listens to, R9-2) and the screen's block tick.
 *
 * Must hold:
 * - the row is never a hard error while have + pending covers the message: it stays in flight, waiting, and sends by
 *   itself once the change can be spent, exactly once (KEX + message = 2 submits, never a third);
 * - also when the send-time shortfall came back as "not enough" (Hypothesis B: a raw or stale shortfall), the row is
 *   parked as waiting for funds (the accepted total and account on the row) and a later block sends it once;
 * - a row that already paid its KEX never shows "Add ZEC" nor "Nothing was paid", also when the wallet truly cannot
 *   pay the message, and nothing pays it by itself then;
 * - rule 2: only the account that accepted the price pays the waiting row; a newly funded wallet whose question was
 *   never answered pays nothing.
 *
 * Every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexChangeNoteMessageWaitsThenSendsOnceTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1909kexchange0001"
        const val TYPED = "synthetic first words right after funding"
        const val MESSAGE_COST = 17_000L
        const val SETTLE_MS = 3_000L
        const val KEX_PLUS_MESSAGE = 2
        const val OTHER_ACCOUNT = "19091909-0000-4000-8000-000000001909"
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    // ── the classifier path: the change of cost + 5,000 .. 9,999 is a wait ────────────────────────────────────

    @Test
    fun `a KEX change of cost plus 5,000 waits for its confirmation, then the message sends once`() =
        runBlocking { changeWaitsThenSendsOnce(extra = 5_000) }

    @Test
    fun `a KEX change of cost plus 7,000 waits for its confirmation, then the message sends once`() =
        runBlocking { changeWaitsThenSendsOnce(extra = 7_000) }

    @Test
    fun `a KEX change of cost plus 9,999 waits for its confirmation, then the message sends once`() =
        runBlocking { changeWaitsThenSendsOnce(extra = 9_999) }

    // ── Hypothesis B: a shortfall that came back as "not enough" while the change still confirms ──────────────

    @Test
    fun `a not-enough shortfall after the paid KEX, with the change confirming, parks the row and a block sends it once`() =
        runBlocking {
            val c = Chat.open(sendScopes)
            c.wallet.select(available = 0, pending = 0) // what the send's classifier read (stale)
            c.vmWallet.select(available = 0, pending = MESSAGE_COST + 7_000) // the wallet now: the KEX's change confirms
            c.payKexAndAttemptMessage()

            val row = c.world.row()!!
            assertNotHardError(c, row.id)
            assertTrue(
                "the row is not stored as waiting for its funds: $row",
                row.isPending && row.status != MessageStatus.FAILED.name && row.acceptedPriceZatoshi != null,
            )
            assertEquals(
                "the message was paid before its change confirmed",
                1,
                c.world.seam.submits
                    .get(),
            )

            c.changeConfirms(MESSAGE_COST + 7_000)
            c.block()
            assertSentOnce(c)
        }

    // ── never "Add ZEC" after a paid KEX ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a message the wallet truly cannot pay after its paid KEX never says Add ZEC or Nothing was paid, and nothing pays it`() =
        runBlocking {
            val c = Chat.open(sendScopes)
            c.wallet.select(available = 0, pending = 3_000)
            c.vmWallet.select(available = 0, pending = 3_000)
            c.payKexAndAttemptMessage()

            assertTrue(
                "the send never settled: ${c.state()}",
                PaymentHarness.eventually(SETTLE_MS) {
                    c.world.row()?.status ==
                        MessageStatus.FAILED.name
                },
            )
            val row = c.world.row()!!
            for (shown in listOfNotNull(row.failureReason, (c.state() as? SendMessageState.Error)?.message)) {
                assertFalse("a row that paid its KEX says 'Add ZEC': $shown", shown.contains("Add ZEC", ignoreCase = true))
                assertFalse("a row that paid its KEX says nothing was paid: $shown", shown.contains("Nothing was paid", ignoreCase = true))
                assertFalse(
                    "a row that paid its KEX shows the generic shortfall",
                    shown == CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE,
                )
            }
            c.block()
            c.block()
            assertTrue("a short wallet paid the message by itself", c.world.staysAt(1))
        }

    // ── rule 2: consent ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a parked row is paid only by the account that accepted its price`() =
        runBlocking {
            val c = Chat.open(sendScopes)
            c.wallet.select(available = 0, pending = 0)
            c.vmWallet.select(available = 0, pending = MESSAGE_COST + 7_000)
            c.payKexAndAttemptMessage()
            assertNotHardError(c, c.world.row()!!.id)

            // Another account is selected and is funded: it never pays the row the first account accepted.
            c.changeConfirms(MESSAGE_COST + 7_000)
            c.vmWallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = 500_000)
            c.block()
            c.block()
            assertTrue("another account paid the waiting row", c.world.staysAt(1))

            c.vmWallet.selectBase()
            c.vmWallet.select(available = MESSAGE_COST + 7_000, pending = 0)
            c.block()
            assertSentOnce(c)
        }

    @Test
    fun `control - a newly funded wallet whose price question was never answered pays nothing`() =
        runBlocking {
            val c = Chat.open(sendScopes)
            c.world.chat.typeAndSend(c.vm, TYPED)
            c.world.question(c.vm) // asked, never answered

            c.wallet.select(available = 500_000, pending = 0)
            c.vmWallet.select(available = 500_000, pending = 0)
            repeat(3) { c.block() }
            assertTrue("a newly funded wallet paid without its Pay", c.world.staysAt(0))
        }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun changeWaitsThenSendsOnce(extra: Long) {
        val c = Chat.open(sendScopes)
        val change = MESSAGE_COST + extra
        c.wallet.select(available = 0, pending = change)
        c.vmWallet.select(available = 0, pending = change)
        c.payKexAndAttemptMessage()

        assertNotHardError(c, c.world.row()!!.id)
        assertEquals(
            "the message was paid before its change confirmed",
            1,
            c.world.seam.submits
                .get(),
        )

        c.changeConfirms(change)
        c.block()
        assertSentOnce(c)
    }

    private fun assertNotHardError(
        c: Chat,
        id: String,
    ) {
        val row = c.world.row()!!
        assertEquals("fixture: the row", id, row.id)
        assertFalse(
            "a message whose KEX change pays it once confirmed was failed (reason: ${row.failureReason})",
            row.status == MessageStatus.FAILED.name,
        )
        val shown = (c.state() as? SendMessageState.Error)?.message
        assertFalse("the screen shows a hard error while the change confirms: $shown", shown != null)
    }

    private suspend fun assertSentOnce(c: Chat) {
        assertTrue(
            "the waiting message did not send by itself: ${c.world.seam.submits.get()} of $KEX_PLUS_MESSAGE paid",
            PaymentHarness.eventually(SETTLE_MS) {
                c.world.seam.submits
                    .get() >= KEX_PLUS_MESSAGE
            },
        )
        c.block()
        c.block()
        assertTrue("one message was paid more than once: ${c.world.seam.submits.get()} submits", c.world.staysAt(KEX_PLUS_MESSAGE))
        assertTrue("a sent row reads as not sent", c.world.row()?.status != MessageStatus.FAILED.name)
    }

    /** One phone: [world] over the real send, [wallet] what the use case reads, [vmWallet] what the ViewModel reads. */
    private class Chat(
        val world: FundsConfirmingChat,
        val vm: ChatViewModel,
        val wallet: Wallet,
        val vmWallet: Wallet,
        val funds: GatedFunds,
        val heights: HeightFeed,
    ) {
        fun state(): SendMessageState = HandshakeHarness.sendMessageState(vm)

        /** A new tip, as the phone sees it: the synchronizer's network height (the note wait) and the screen's tick. */
        fun block() {
            heights.advance()
            world.block(vm)
        }

        /** Sends, answers Pay; the KEX is paid and the message meets the SDK's refusal. */
        suspend fun payKexAndAttemptMessage() {
            world.plan = "Fundable"
            world.chat.typeAndSend(vm, TYPED)
            world.question(vm)
            vm.confirmPaidAction()
            if (!PaymentHarness.eventually(SETTLE_MS) { world.seam.submits.get() >= 1 && funds.refusals.get() >= 1 }) {
                throw AssertionError("fixture: the KEX was not paid or the message never met the refusal (${state()})")
            }
            PaymentHarness.eventually(SETTLE_MS) { state() !is SendMessageState.Sending }
        }

        /** The KEX's change of [change] is confirmed: spendable in both views, and the SDK builds the message. */
        fun changeConfirms(change: Long) {
            funds.refuse = false
            wallet.select(available = change, pending = 0)
            vmWallet.select(available = change, pending = 0)
        }

        companion object {
            suspend fun open(scopes: SendHarnessScopes): Chat {
                val world = FundsConfirmingChat(PEER)
                val vm = world.screen()
                val base = syntheticZashiAccount()
                val wallet = Wallet(base)
                val vmWallet = Wallet(base)
                val funds = GatedFunds(world.funds, world.seam)
                val useCase =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = wallet,
                                proposalDataSource = funds,
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ).also { scopes.own(it) },
                        accountDataSource = wallet,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                    )
                PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
                PaymentHarness.setField(vm, "accountDataSource", vmWallet)
                val heights = HeightFeed()
                PaymentHarness.setField(vm, "synchronizerProvider", heights.provider)
                return Chat(world, vm, wallet, vmWallet, funds, heights)
            }
        }
    }

    /** After the KEX's submit, every build is refused as the SDK refuses a send funded only by confirming notes. */
    private class GatedFunds(
        private val delegate: FundsConfirmingChat.MeteredFunds,
        private val seam: HandshakeSeam,
    ) : ProposalDataSource by delegate {
        @Volatile var refuse = true
        val refusals =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            if (refuse && seam.submits.get() >= 1) {
                refusals.incrementAndGet()
                throw InsufficientFundsException("Insufficient balance", have = Zatoshi(0), need = Zatoshi(MESSAGE_COST))
            }
            return delegate.createZip321Proposal(account, zip321Uri, allowMultiPayment)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult = delegate.submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = delegate.submitTransaction(proposal, usk, onStored)
    }

    /** One selected account whose Ironwood balance a test sets; [selectOther] selects another account. */
    private class Wallet(
        private val base: ZashiAccount,
    ) : AccountDataSource {
        private val selected = MutableStateFlow<WalletAccount>(base)
        private var current: ZashiAccount = base

        fun select(
            available: Long,
            pending: Long,
        ) {
            current =
                current.copy(
                    unified =
                        current.unified
                            .copy(ironwood = WalletBalance(Zatoshi(available), Zatoshi(pending), Zatoshi(0))),
                )
            selected.value = current
        }

        fun selectOther(
            uuid: UUID,
            available: Long,
        ) {
            current = base.copy(sdkAccount = AccountFixture.new(accountUuid = uuid))
            select(available = available, pending = 0)
        }

        fun selectBase() {
            current = base
            selected.value = current
        }

        override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(base))
        override val selectedAccount: Flow<WalletAccount?> = selected
        override val zashiAccount: Flow<ZashiAccount?> = selected.map { it as? ZashiAccount }

        override suspend fun getAllAccounts(): List<WalletAccount> = listOf(selected.value)

        override suspend fun getSelectedAccount(): WalletAccount = selected.value

        override suspend fun getZashiAccount(): ZashiAccount = selected.value as? ZashiAccount ?: base

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used in this test")

        override suspend fun requestNextShieldedAddress() = Unit
    }
}
