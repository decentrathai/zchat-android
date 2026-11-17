package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1958 (Fable review of fix/2130-msg, 16 Nov 2026; register high; W-101 wrong-wallet class) — the ZC-1946 account guard
 * runs only on the rebuild after a block wait. The user pays a first message under account A; while A's key exchange is
 * submitted the selected account becomes another, funded account B; the KEX returns Done and the message's first build
 * pays from B, which never accepted its price (payingAccountKey is read after the KEX; with no funds-wait consent it is
 * simply the account selected then).
 *
 * Wanted: after the KEX, the message is built and paid only while the account that paid the KEX (and accepted the price)
 * is still selected; otherwise nothing is proposed from B and nothing more is paid. Both a Pay that may wait for funds
 * and one that may not (no funds-wait consent: restoreTextOnCancel = false, as a file's or a declinable send's) are
 * covered; the control shows the same fixture with no switch pays the KEX and then the message from A. Real ChatViewModel
 * send over [ParkedFirstMessageChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnAccountSwitchDuringTheKexNeverPaysTheFirstMessageTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1958kexaccount001"
        const val OTHER_ACCOUNT = "19581958-0000-4000-8000-000000001958"
        const val FUNDED = 500_000L
        const val KEX_ONLY = 1
        const val KEX_AND_MESSAGE = 2
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

    @Test
    fun `control - with no switch the accepting account pays the KEX and then the message`() =
        runBlocking {
            val (c, wire) = fundedChat(switchAtKex = false)
            payFirstMessage(c, waitConsent = true)
            assertTrue(
                "fixture: the KEX and the message were not both paid: ${c.submits()} submits",
                PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { c.submits() >= KEX_AND_MESSAGE },
            )
            assertEquals("fixture: a build came from another account", 0, wire.buildsFromOtherAccounts())
        }

    @Test
    fun `an account switched to while the KEX is submitted never pays the first message`() =
        runBlocking {
            val (c, wire) = fundedChat(switchAtKex = true)
            payFirstMessage(c, waitConsent = true)
            assertNoMessageFromTheOtherAccount(c, wire)
        }

    @Test
    fun `the same for a Pay with no funds-wait consent - the message is never paid from the switched-to account`() =
        runBlocking {
            val (c, wire) = fundedChat(switchAtKex = true)
            payFirstMessage(c, waitConsent = false)
            assertNoMessageFromTheOtherAccount(c, wire)
        }

    private suspend fun assertNoMessageFromTheOtherAccount(
        c: ParkedFirstMessageChat,
        wire: SwitchAtKexSubmit,
    ) {
        assertTrue(
            "fixture: the key exchange was not paid (${c.state()})",
            PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { c.submits() >= KEX_ONLY },
        )
        // ZC-1989: the submit count moves inside the delegate, before afterSubmit runs the switch; wait for the switch too.
        assertTrue(
            "fixture: the switch did not happen at the KEX's submit",
            PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { wire.switched },
        )
        val stayed = c.world.staysAt(KEX_ONLY)
        assertTrue("the first message was paid from an account that never accepted its price: ${c.submits()} submits", stayed)
        assertEquals(
            "the first message was proposed from an account that never accepted its price",
            0,
            wire.buildsFromOtherAccounts(),
        )
        repeat(2) { c.block() }
        val stayedAfterBlocks = c.world.staysAt(KEX_ONLY)
        assertTrue("a later block paid the message from the other account: ${c.submits()} submits", stayedAfterBlocks)
    }

    /** Account A is selected and funded in both views; the SDK builds every leg; optionally B is selected at the KEX's submit. */
    private suspend fun fundedChat(switchAtKex: Boolean): Pair<ParkedFirstMessageChat, SwitchAtKexSubmit> {
        val c = ParkedFirstMessageChat.open(sendScopes, PEER)
        c.funds.refuse = false
        c.wallet.select(available = FUNDED, pending = 0)
        c.vmWallet.select(available = FUNDED, pending = 0)
        val wire = SwitchAtKexSubmit(c.funds, GetDefaultUnifiedAddressUseCase.accountKeyOf(syntheticZashiAccount()))
        if (switchAtKex) {
            wire.onKexSubmitted = {
                c.wallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = FUNDED)
                c.vmWallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = FUNDED)
            }
        }
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = c.wallet,
                        proposalDataSource = wire,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also { sendScopes.own(it) },
                accountDataSource = c.wallet,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )
        PaymentHarness.setField(c.vm, "createChunkedMessageProposal", useCase)
        return c to wire
    }

    /** Sends the first message under A and answers Pay; [waitConsent] false sends it as a Pay that may not wait for funds. */
    private suspend fun payFirstMessage(
        c: ParkedFirstMessageChat,
        waitConsent: Boolean,
    ) {
        c.world.plan = "Fundable"
        if (waitConsent) {
            c.world.chat.typeAndSend(c.vm, ParkedFirstMessageChat.TYPED)
        } else {
            c.vm.sendMessage(PEER, ParkedFirstMessageChat.TYPED, restoreTextOnCancel = false)
            PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) {
                c.state() is SendMessageState.NeedsPaidActionConfirmation
            }
        }
        c.world.question(c.vm)
        c.vm.confirmPaidAction()
    }

    /**
     * The wallet under the use case: records the account of every build, and runs [onKexSubmitted] once the first submit
     * (the key exchange) has reached the SDK, before the send hears it returned.
     */
    internal class SwitchAtKexSubmit(
        private val delegate: ProposalDataSource,
        private val acceptingKey: String,
    ) : ProposalDataSource by delegate {
        @Volatile var onKexSubmitted: (() -> Unit)? = null

        @Volatile var switched = false
        private val submits = AtomicInteger(0)
        private val buildKeys: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun buildsFromOtherAccounts(): Int = buildKeys.toList().count { it != acceptingKey }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            buildKeys += GetDefaultUnifiedAddressUseCase.accountKeyOf(account)
            return delegate.createZip321Proposal(account, zip321Uri, allowMultiPayment)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult = delegate.submitTransaction(proposal, usk).also { afterSubmit() }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = delegate.submitTransaction(proposal, usk, onStored).also { afterSubmit() }

        private fun afterSubmit() {
            if (submits.incrementAndGet() == 1) {
                onKexSubmitted?.let {
                    it()
                    switched = true
                }
            }
        }
    }
}
