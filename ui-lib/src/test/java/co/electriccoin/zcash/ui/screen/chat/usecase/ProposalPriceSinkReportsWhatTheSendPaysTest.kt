package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.zc538FundedAccount
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1472 (money-path rule 4) — what a chat note may claim a send paid comes from that send's own proposal: the use
 * case reports, through [ProposalPriceSink], the outputs, the platform fee and the proposal's network fee, right before
 * the hand-over, and nothing for a send that never got there. Real use case and repository; the SDK seam builds a
 * proposal with a chosen network fee. Every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProposalPriceSinkReportsWhatTheSendPaysTest {
    private val scopes = SendHarnessScopes()

    private companion object {
        val PEER: String = SyntheticUnifiedAddress.mainnet(14_721)
        val SELF: String = SyntheticUnifiedAddress.mainnet(14_722)
        const val TIMEOUT_MS = 30_000L
    }

    @Before
    fun setUp() {
        NetworkUpgradeTestApi.resetIfPresent()
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scopes.close()
        Dispatchers.resetMain()
        NetworkUpgradeTestApi.resetIfPresent()
    }

    private class FeeData(
        private val fee: Long,
        private val refuse: Boolean = false,
    ) : ProposalDataSource by Zc101RecordingProposalDataSource() {
        val submits = AtomicInteger(0)

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            if (refuse) throw IllegalStateException("synthetic: no proposal")
            return Zip321TransactionProposal(
                destination = WalletAddress.Unified.new(PEER),
                amount = Zatoshi(1_000L),
                memo = Memo("synthetic"),
                proposal = SweepProposalBytes.proposal(listOf(SweepProposalBytes.ironwood(1_000_000L)), fee),
            )
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            submits.incrementAndGet()
            return SubmitResult.Success(txIds = listOf("synthetic-txid-${submits.get()}"))
        }
    }

    private suspend fun useCase(data: FeeData): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(zc538FundedAccount())
        val repository = ZashiProposalRepositoryImpl(accounts, data, Zc101FakeZashiSpendingKeyDataSource()).also(scopes::own)
        return CreateChunkedMessageProposalUseCase(
            Zc101FakeKeystoneProposalRepository(),
            repository,
            accounts,
            Zc101RecordingNavigationRouter(),
            Zc101SuspendingBiometricRepository(),
        )
    }

    private suspend fun send(use: CreateChunkedMessageProposalUseCase) {
        use(PEER, SELF, "synthetic handshake memo", false, directSubmit = true, skipNavigation = true, rawMemo = true)
    }

    @Test
    fun `a handshake memo reports its output, the platform fee and the proposal's own network fee`() =
        runBlocking {
            val data = FeeData(fee = 15_000L)
            val use = useCase(data)
            val reported = Collections.synchronizedList(mutableListOf<Zatoshi?>())

            withTimeout(TIMEOUT_MS) { withContext(ProposalPriceSink { reported += it }) { send(use) } }

            assertEquals("the send went out once", 1, data.submits.get())
            assertEquals("1,000 to the contact + 1,000 platform fee + the proposal's 15,000 fee", listOf(Zatoshi(17_000L)), reported)
        }

    @Test
    fun `a larger network fee is reported as it is, never the quoted floor`() =
        runBlocking {
            val data = FeeData(fee = 25_000L)
            val use = useCase(data)
            val reported = Collections.synchronizedList(mutableListOf<Zatoshi?>())

            withTimeout(TIMEOUT_MS) { withContext(ProposalPriceSink { reported += it }) { send(use) } }

            assertEquals(listOf(Zatoshi(27_000L)), reported)
        }

    @Test
    fun `a send that never reaches the hand-over reports nothing`() =
        runBlocking {
            val data = FeeData(fee = 15_000L, refuse = true)
            val use = useCase(data)
            val reported = Collections.synchronizedList(mutableListOf<Zatoshi?>())

            withTimeout(TIMEOUT_MS) {
                withContext(ProposalPriceSink { reported += it }) { runCatching { send(use) } }
            }

            assertEquals(0, data.submits.get())
            assertTrue("nothing was handed over, so no price is reported: $reported", reported.isEmpty())
        }

    @Test
    fun `control - a send without a sink is unchanged`() =
        runBlocking {
            val data = FeeData(fee = 15_000L)
            val use = useCase(data)

            withTimeout(TIMEOUT_MS) { send(use) }

            assertEquals(1, data.submits.get())
        }
}
