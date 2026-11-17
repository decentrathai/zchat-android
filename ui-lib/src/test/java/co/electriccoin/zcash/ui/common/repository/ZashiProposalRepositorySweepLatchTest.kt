package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.OrchardSweepPendingException
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The repository's half of "no double sweep" (sweep spec §2.4): while one sweep is being submitted, a new
 * one cannot be prepared and a second submit never reaches the SDK. The sweep never uses the shared
 * proposal cell or submit state, so a chat send and the sweep cannot overwrite each other. All addresses
 * and keys are synthetic.
 */
class ZashiProposalRepositorySweepLatchTest {
    /** Answers the sweep with [sweep]; parks its settlement on [release] once it is [entered]. */
    private class GatedSweepDataSource(
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val sweep = OrchardSweepTransactionProposal.fromProposal(sweepProposal())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var createCalls = 0

        override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal {
            createCalls++
            return sweep
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey
        ): SubmitResult {
            if (proposal === sweep.proposal) {
                entered.complete(Unit)
                release.await()
            }
            return recording.submitTransaction(proposal, usk)
        }
    }

    @Test
    fun a_sweep_in_flight_blocks_a_new_sweep_and_a_second_submit() =
        runBlocking {
            val dataSource = GatedSweepDataSource()
            val repository = repository(dataSource)
            val sweep = repository.createOrchardSweepProposal()
            assertSame(dataSource.sweep, sweep)

            val first = async { repository.submitOrchardSweep(sweep) }
            withTimeout(TIMEOUT_MS) { dataSource.entered.await() }

            val refusal =
                assertFailsWith<TransactionProposalNotCreatedException> { repository.createOrchardSweepProposal() }
            assertTrue(refusal.cause is OrchardSweepPendingException, "${refusal.cause}")
            assertEquals(1, dataSource.createCalls, "a refused create never reaches the funnel")

            val second = repository.submitOrchardSweep(sweep)
            assertTrue(second is SubmitResult.Failure && second.txIds.isEmpty(), "$second")

            dataSource.release.complete(Unit)
            assertEquals(SubmitResult.Success(listOf("synthetic-txid")), withTimeout(TIMEOUT_MS) { first.await() })
            assertEquals(listOf(sweep.proposal), dataSource.recording.submittedProposals, "the SDK saw one submit")

            // Released: the next sweep can be prepared again.
            repository.createOrchardSweepProposal()
            assertEquals(2, dataSource.createCalls)
        }

    @Test
    fun the_sweep_leaves_the_shared_cell_and_submit_state_alone() =
        runBlocking {
            val dataSource = GatedSweepDataSource().apply { release.complete(Unit) }
            val repository = repository(dataSource)

            val sweep = repository.createOrchardSweepProposal()
            assertNull(repository.transactionProposal.value)
            repository.submitOrchardSweep(sweep)

            assertNull(repository.transactionProposal.value)
            assertNull(repository.submitState.value)
        }

    @Test
    fun an_ambiguous_sdk_exception_is_sent_unconfirmed_and_releases_the_latch() =
        runBlocking {
            val dataSource =
                object : ProposalDataSource by Zc101RecordingProposalDataSource() {
                    val sweep = OrchardSweepTransactionProposal.fromProposal(sweepProposal())

                    override suspend fun createOrchardSweepProposal(account: WalletAccount) = sweep

                    override suspend fun submitTransaction(
                        proposal: Proposal,
                        usk: UnifiedSpendingKey
                    ): SubmitResult = error("synthetic broadcast failure")
                }
            val repository = repository(dataSource)
            val sweep = repository.createOrchardSweepProposal()

            val result = repository.submitOrchardSweep(sweep)

            // May follow the broadcast, so never a Failure (audit fable-20260915 A.7). The proven
            // non-send (NothingBroadcastException) stays a Failure: MoveOldBalanceNothingSentTest.
            assertEquals(SubmitResult.SentUnconfirmed(emptyList(), "synthetic broadcast failure"), result)
            assertSame(sweep, repository.createOrchardSweepProposal(), "the latch was released")
        }

    private suspend fun repository(dataSource: ProposalDataSource) =
        ZashiProposalRepositoryImpl(
            accountDataSource = Zc101FakeAccountDataSource(account()),
            proposalDataSource = dataSource,
            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
        )

    private companion object {
        const val TIMEOUT_MS = 5_000L

        fun sweepProposal(): Proposal =
            SweepProposalBytes.proposal(listOf(SweepProposalBytes.orchard(139_000)), fee = 20_000)

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(139_000), balance(443_002)),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
