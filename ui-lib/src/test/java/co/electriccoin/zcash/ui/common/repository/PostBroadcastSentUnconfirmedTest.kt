package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The GREEN half of the double-pay fix: what an ambiguous settlement outcome MUST become.
 *
 * PostBroadcastNeverReportedFailedTest pins the prohibition — a transaction the SDK has already
 * broadcast may never reach the user as [SubmitResult.Failure] ("Send Failed — Try it again,
 * please"). This file pins the positive shape the repositories now produce instead, and the two
 * controls that keep the fix from being a blanket swallow:
 *
 *  * an exception out of `submitTransaction` becomes [SubmitResult.SentUnconfirmed] — RETURNED, not
 *    thrown, so the automatic chat senders (sendHandshakeMemoWithRetry, sendGroupMemoWithRetry,
 *    fulfillPaymentRequest) never read it as "nothing was sent" and re-arm their durable
 *    sent-markers — and published to `submitState`, where TransactionProgressVM renders it as
 *    "Sent — Not Confirmed" with Back/Close routed to the transaction history rather than to a
 *    still-populated Send form;
 *  * the SDK's OWN verdict is untouched: a [SubmitResult.Failure] it returns is still published as a
 *    failure, because there "nothing was submitted" is a fact, not an inference from an exception;
 *  * a cancellation still propagates (structured concurrency) but is published first, so the
 *    progress screen cannot be left stuck on "Sending".
 *
 * All addresses, keys and PCZT bytes are SYNTHETIC placeholders — nothing parses or validates.
 */
class PostBroadcastSentUnconfirmedTest {
    @Test
    fun `a post-broadcast throw is returned and published as SentUnconfirmed, never as a failure`() =
        runBlocking {
            val dataSource = SettlementSeam { throw RuntimeException(REFRESH_FAILURE) }
            val repository = zashiRepository(dataSource)
            val proposal = syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic-payment")

            val result = repository.submit(proposal)

            assertEquals(1, dataSource.broadcasts, "fixture sanity: the SDK must have broadcast")
            assertEquals(SubmitResult.SentUnconfirmed(txIds = emptyList(), description = REFRESH_FAILURE), result)
            assertEquals(
                SubmitProposalState.Result(
                    SubmitResult.SentUnconfirmed(txIds = emptyList(), description = REFRESH_FAILURE)
                ),
                repository.submitState.value
            )
        }

    @Test
    fun `control - the SDK's own rejection is still published as a failure`() =
        runBlocking {
            val rejection = SubmitResult.Failure(txIds = listOf(TXID), code = 17, description = "rejected")
            val dataSource = SettlementSeam { rejection }
            val repository = zashiRepository(dataSource)
            val proposal = syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic-payment")

            val result = repository.submit(proposal)

            assertEquals(rejection, result, "a verdict the SDK returned is a fact — it must not be softened")
            assertEquals(SubmitProposalState.Result(rejection), repository.submitState.value)
        }

    @Test
    fun `a post-broadcast cancellation is published as SentUnconfirmed and still propagates`() =
        runBlocking {
            val dataSource = SettlementSeam { throw CancellationException(CANCELLED) }
            val repository = zashiRepository(dataSource)
            val proposal = syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic-payment")

            assertFailsWith<CancellationException> { repository.submit(proposal) }

            assertEquals(1, dataSource.broadcasts, "fixture sanity: the SDK must have broadcast")
            assertEquals(
                SubmitProposalState.Result(SubmitResult.SentUnconfirmed(txIds = emptyList(), description = CANCELLED)),
                repository.submitState.value,
                "cancellation after the broadcast must not leave the progress screen stuck on \"Sending\""
            )
        }

    @Test
    fun `a post-broadcast throw on the Keystone lane is published as SentUnconfirmed too`() =
        runBlocking {
            val dataSource = SettlementSeam { throw RuntimeException(REFRESH_FAILURE) }
            val repository =
                KeystoneProposalRepositoryImpl(
                    accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = dataSource
                )

            val result = repository.submit(repository.openSigningSession(dataSource))

            assertEquals(1, dataSource.broadcasts, "fixture sanity: the SDK must have broadcast")
            assertEquals(SubmitResult.SentUnconfirmed(txIds = emptyList(), description = REFRESH_FAILURE), result)
            assertEquals(
                SubmitProposalState.Result(
                    SubmitResult.SentUnconfirmed(txIds = emptyList(), description = REFRESH_FAILURE)
                ),
                repository.submitState.value
            )
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun zashiRepository(dataSource: ProposalDataSource) =
        ZashiProposalRepositoryImpl(
            accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
            proposalDataSource = dataSource,
            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
        )

    /**
     * Brings the Keystone repository to the state `submit` requires: a proposal, its PCZT, the
     * background proofs job finished, and the signed PCZT scanned back. The only production writer
     * of `pcztWithSignatures` is `parsePCZT(ur)`, which decodes through the Keystone SDK and cannot
     * run on the JVM, so the scan result is injected into that exact field (the same technique as
     * KeystonePcztBindingToctouTest).
     */
    private suspend fun KeystoneProposalRepositoryImpl.openSigningSession(
        dataSource: SettlementSeam
    ): Zip321TransactionProposal {
        val proposal = createZip321Proposal(KEYSTONE_URI, allowMultiPayment = true)
        createPCZTFromProposal()
        withTimeout(AWAIT_MS) { dataSource.proofsReady.await() }
        val field = KeystoneProposalRepositoryImpl::class.java.getDeclaredField("pcztWithSignatures")
        field.isAccessible = true
        field.set(this, dataSource.signedPczt)
        return proposal
    }

    /**
     * The settlement seam. Both `submitTransaction` overloads RECORD the broadcast — the SDK puts
     * the transaction on the wire FIRST in `submitTransactionInternal` — and only then let
     * [afterBroadcast] decide the outcome, which is the ordering that makes an exception here
     * ambiguous rather than a failure.
     */
    private class SettlementSeam(
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
        private val afterBroadcast: () -> SubmitResult,
    ) : ProposalDataSource by recording {
        var broadcasts = 0
            private set

        val pczt = Pczt(byteArrayOf(0x11, 0x11, 0x11))
        val signedPczt = Pczt(byteArrayOf(0x1A, 0x1A, 0x1A))

        /** Completed when the repository's background proofs job has proofed the PCZT. */
        val proofsReady = CompletableDeferred<Unit>()

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean
        ): Zip321TransactionProposal = syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic-payment")

        override suspend fun createPcztFromProposal(account: WalletAccount, proposal: Proposal): Pczt = pczt

        override suspend fun addProofsToPczt(pczt: Pczt): Pczt {
            // The repository proofs a CLONE — match by content, not identity.
            if (pczt.toByteArray().contentEquals(this.pczt.toByteArray())) proofsReady.complete(Unit)
            return pczt
        }

        override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult =
            broadcastThen()

        override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult =
            broadcastThen()

        private fun broadcastThen(): SubmitResult {
            broadcasts++
            return afterBroadcast()
        }
    }

    private companion object {
        const val AWAIT_MS = 10_000L

        /** Bare-alphanumeric and >= 20 chars, so nothing downstream rejects it on shape. */
        const val PEER = "usyntheticpaymentpeer0000000"

        const val KEYSTONE_URI = "zcash:usynthetickeystonepeer?amount=0.05&memo=c3ludGhldGlj"

        const val TXID = "synthetic-broadcast-txid"

        /** Stands in for `refreshAllBalances()` / `refreshTransactions()`, which run AFTER the broadcast. */
        const val REFRESH_FAILURE = "synthetic post-broadcast balance refresh failure"

        const val CANCELLED = "synthetic post-broadcast cancellation"
    }
}
