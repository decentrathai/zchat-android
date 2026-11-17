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
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1945, ZC-1946, ZC-1947 (Fable review of fix/2130-msg 4efd9a54f, 16 Nov 2026): one phone sending the first message
 * of a fresh Shielded chat over the real ChatViewModel send, as KexChangeNoteMessageWaitsThenSendsOnceTest drives it
 * (ZC-1909): the key exchange is paid, then the SDK refuses the message while the KEX's change confirms. [wallet] is
 * what the send's use case reads, [vmWallet] what the ViewModel reads; [funds] refuses each build after the KEX's submit
 * until [GatedFunds.refuse] is off, and runs a test's hook at that moment. Every address, key and txid is synthetic.
 */
internal class ParkedFirstMessageChat private constructor(
    val world: FundsConfirmingChat,
    val vm: ChatViewModel,
    val wallet: Wallet,
    val vmWallet: Wallet,
    val funds: GatedFunds,
    private val heights: HeightFeed,
) {
    fun state(): SendMessageState = HandshakeHarness.sendMessageState(vm)

    fun submits(): Int = world.seam.submits.get()

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
        if (!PaymentHarness.eventually(SETTLE_MS) { submits() >= 1 && funds.refusals.get() >= 1 }) {
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
        const val TYPED = "synthetic first words right after funding"
        const val MESSAGE_COST = 17_000L
        const val SETTLE_MS = 3_000L

        suspend fun open(
            scopes: SendHarnessScopes,
            peer: String,
        ): ParkedFirstMessageChat {
            val world = FundsConfirmingChat(peer)
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
            return ParkedFirstMessageChat(world, vm, wallet, vmWallet, funds, heights)
        }
    }

    /**
     * After the KEX's submit, every build is refused as the SDK refuses a send funded only by confirming notes, and
     * [onRefuse] runs first; once [refuse] is off, [onBuild] runs before each build.
     */
    internal class GatedFunds(
        private val delegate: FundsConfirmingChat.MeteredFunds,
        private val seam: HandshakeSeam,
    ) : ProposalDataSource by delegate {
        @Volatile var refuse = true

        @Volatile var onRefuse: (() -> Unit)? = null

        @Volatile var onBuild: (() -> Unit)? = null
        val refusals = AtomicInteger(0)

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            if (refuse && seam.submits.get() >= 1) {
                onRefuse?.invoke()
                refusals.incrementAndGet()
                throw InsufficientFundsException("Insufficient balance", have = Zatoshi(0), need = Zatoshi(MESSAGE_COST))
            }
            if (seam.submits.get() >= 1) onBuild?.invoke()
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
    internal class Wallet(
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
