package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 8 D7 (sweep #8, #10, #12, #13) — the pieces the "a wallet with Orchard left can still send while its own
 * change confirms" tests share.
 *
 * [OwnChangeAccounts] is one phone's selected account, which a test changes between two chain tips. The states are
 * the ones a real wallet goes through: right after a send its only Ironwood note is locked and the change is
 * confirming ([ownChangeConfirming]); a first deposit that has landed but not confirmed ([firstDepositConfirming]);
 * and the change confirmed ([ironwoodSpendable]). The ViewModel and the send chokepoint read the same instance.
 *
 * [ScriptedSeam] is the SDK settlement seam under the real repository: the n-th proposal request (1-based) throws
 * what [failRequest] returns for n, every other one gets a proposal to the address in its ZIP 321 URI, and every
 * submit succeeds. Balances, addresses and txids are synthetic.
 */
internal class OwnChangeAccounts private constructor(
    private val base: ZashiAccount,
) : AccountDataSource {
    private val selected = MutableStateFlow<WalletAccount>(base)

    /** Orchard value left, and the only Ironwood note locked by our own send: its change is confirming. */
    fun ownChangeConfirming() = select(orchard = ORCHARD, ironwood = WalletBalance(ZERO, PENDING, ZERO))

    /** No Orchard at all, and our own change confirming: a send meets the "still confirming" shortfall. */
    fun ownChangeConfirmingNoOrchard() = select(orchard = ZERO, ironwood = WalletBalance(ZERO, PENDING, ZERO))

    /** Orchard value left, and a first Ironwood deposit from someone else that has landed but not confirmed. */
    fun firstDepositConfirming() = select(orchard = ORCHARD, ironwood = WalletBalance(ZERO, ZERO, PENDING))

    /** The change confirmed: a peer-addressed memo can be paid from Ironwood. */
    fun ironwoodSpendable() = select(orchard = ORCHARD, ironwood = WalletBalance(SPENDABLE, ZERO, ZERO))

    fun current(): WalletAccount = selected.value

    private fun select(
        orchard: Zatoshi,
        ironwood: WalletBalance,
    ) {
        selected.value =
            base.copy(unified = base.unified.copy(balance = WalletBalance(orchard, ZERO, ZERO), ironwood = ironwood))
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

    companion object {
        private val ZERO = Zatoshi(0)
        val ORCHARD = Zatoshi(300_000)
        val PENDING = Zatoshi(200_000)
        private val SPENDABLE = Zatoshi(500_000)

        /** Starts with Ironwood spendable; call one of the selectors to change it. */
        suspend fun create(): OwnChangeAccounts = OwnChangeAccounts(syntheticZashiAccount()).apply { ironwoodSpendable() }
    }
}

internal class ScriptedSeam(
    private val failRequest: (n: Int) -> Exception? = { null },
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    val proposalRequests = AtomicInteger(0)
    val submits = AtomicInteger(0)

    /** The address of every proposal handed to the SDK, in submit order. */
    val submittedTo: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val destinations = Collections.synchronizedMap(mutableMapOf<Proposal, String>())

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean
    ): Zip321TransactionProposal {
        val n = proposalRequests.incrementAndGet()
        failRequest(n)?.let { throw it }
        val destination = zip321Uri.removePrefix("zcash:").substringBefore('?')
        return syntheticZip321Proposal(destination, amount = 1_000L, memo = "synthetic-d7-memo").also {
            destinations[it.proposal] = destination
        }
    }

    // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
    // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
    ): SubmitResult = submitTransaction(proposal, usk)

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): SubmitResult {
        val n = submits.incrementAndGet()
        submittedTo += destinations[proposal] ?: "unknown"
        return SubmitResult.Success(txIds = listOf("synthetic-d7-txid-$n"))
    }

    /** The real send chokepoint over this seam and [accounts], owned by the running test. */
    fun useCase(accounts: AccountDataSource): CreateChunkedMessageProposalUseCase =
        CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = this,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(SendHarnessScopes.current()::own),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
}
