package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.NothingBroadcastException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The other half of the double-pay fix: a send that provably NEVER LEFT THE WALLET must still be
 * reported as a failure.
 *
 * PostBroadcastSentUnconfirmedTest pins the ambiguous outcome (the settlement call threw at or
 * after the point where the SDK hands an output to the network — money may be gone, so it is
 * reported as sent-but-unconfirmed). Turning EVERY throw into that verdict would break the
 * opposite way: on the chat lane a [SubmitResult.SentUnconfirmed] arms `markRequestPaid`, the
 * `paidRequestIds` backstop and the durable KEX/ZBOOT/KEXACK sent-markers, so a definite non-send
 * would retire the "Pay" affordance and drop a handshake for a payment nothing ever broadcast —
 * a genuine failure hidden, and the message lost.
 *
 * The steps that run BEFORE the first output is attempted are not ambiguous, and only the data
 * source can see where the boundary is. It marks them with [NothingBroadcastException]; the
 * repositories map that one type back to [SubmitResult.Failure]. This file pins both ends:
 *
 *  * deriving the spending key (keystore + mnemonic + DerivationTool — nothing on the network)
 *    still fails the send, and the settlement call is never even reached;
 *  * a [NothingBroadcastException] out of the data source stays a failure on the Zashi AND the
 *    Keystone lane;
 *  * the control: an ordinary throw out of the same call is still the ambiguous
 *    [SubmitResult.SentUnconfirmed], so this is not a revert;
 *  * at the source, an unreachable synchronizer is raised as [NothingBroadcastException] rather
 *    than as a bare failure that the repositories would have to guess about.
 *
 * All addresses, keys and PCZT bytes are SYNTHETIC placeholders — nothing parses or validates.
 */
class PreBroadcastStillFailsTest {
    @Test
    fun `a spending-key failure is a definite failure and never reaches settlement`() =
        runBlocking {
            val dataSource = CountingSeam { SubmitResult.Success(txIds = listOf(TXID)) }
            val repository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = dataSource,
                    zashiSpendingKeyDataSource = RefusingSpendingKeyDataSource
                )

            val result = repository.submit(syntheticProposal())

            assertEquals(
                0,
                dataSource.submits,
                "the key could not be derived, so nothing may have been handed to the SDK at all"
            )
            assertEquals(
                SubmitResult.Failure(txIds = emptyList(), code = 0, description = KEY_FAILURE),
                result,
                "a send that never left the wallet must not be reported as sent"
            )
            assertEquals(
                SubmitProposalState.Result(SubmitResult.Failure(txIds = emptyList(), code = 0, description = KEY_FAILURE)),
                repository.submitState.value
            )
        }

    @Test
    fun `a pre-broadcast throw out of the data source stays a failure`() =
        runBlocking {
            val dataSource = CountingSeam { throw NothingBroadcastException(RuntimeException(PRE_BROADCAST_FAILURE)) }
            val repository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = dataSource,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
                )

            val result = repository.submit(syntheticProposal())

            assertEquals(
                SubmitResult.Failure(txIds = emptyList(), code = 0, description = PRE_BROADCAST_FAILURE),
                result,
                "the data source proved nothing was attempted — the repository must not soften that"
            )
            assertEquals(
                SubmitProposalState.Result(
                    SubmitResult.Failure(txIds = emptyList(), code = 0, description = PRE_BROADCAST_FAILURE)
                ),
                repository.submitState.value
            )
        }

    @Test
    fun `control - an ordinary throw out of the same call is still the ambiguous outcome`() =
        runBlocking {
            val dataSource = CountingSeam { throw RuntimeException(REFRESH_FAILURE) }
            val repository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = dataSource,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
                )

            val result = repository.submit(syntheticProposal())

            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = emptyList(), description = REFRESH_FAILURE),
                result,
                "narrowing the ambiguity must not undo the double-pay fix for the throws that stay ambiguous"
            )
        }

    @Test
    fun `a pre-broadcast throw stays a failure on the Keystone lane too`() =
        runBlocking {
            val dataSource = CountingSeam { throw NothingBroadcastException(RuntimeException(PRE_BROADCAST_FAILURE)) }
            val repository =
                KeystoneProposalRepositoryImpl(
                    accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = dataSource
                )

            val result = repository.submit(repository.openSigningSession(dataSource))

            assertEquals(
                SubmitResult.Failure(txIds = emptyList(), code = 0, description = PRE_BROADCAST_FAILURE),
                result
            )
            assertEquals(
                SubmitProposalState.Result(
                    SubmitResult.Failure(txIds = emptyList(), code = 0, description = PRE_BROADCAST_FAILURE)
                ),
                repository.submitState.value
            )
        }

    @Test
    fun `an unreachable synchronizer is raised as a proven non-broadcast`() =
        runBlocking {
            val dataSource = ProposalDataSourceImpl(UnreachableSynchronizerProvider)

            val e =
                assertFailsWith<NothingBroadcastException> {
                    dataSource.submitTransaction(pcztWithProofs = PCZT, pcztWithSignatures = PCZT)
                }

            // `message`, not `cause`: coroutine stack-trace recovery re-creates the exception when it
            // crosses the withContext boundary and re-nests the original under the copy.
            assertEquals(SYNCHRONIZER_FAILURE, e.message)
        }

    @Test
    fun `a synchronizer that is not the concrete SDK type is raised as a proven non-broadcast`() =
        runBlocking {
            val dataSource = ProposalDataSourceImpl(ForeignSynchronizerProvider)

            // The cast that used to leave this function as a bare exception, indistinguishable one
            // layer up from a transaction that had already been broadcast.
            assertFailsWith<NothingBroadcastException> {
                dataSource.submitTransaction(pcztWithProofs = PCZT, pcztWithSignatures = PCZT)
            }
            Unit
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Brings the Keystone repository to the state `submit` requires (same technique as
     * PostBroadcastSentUnconfirmedTest): a proposal, its PCZT, and the signed PCZT injected into the
     * field whose only production writer decodes through the Keystone SDK and cannot run on the JVM.
     */
    private suspend fun KeystoneProposalRepositoryImpl.openSigningSession(
        dataSource: CountingSeam
    ): Zip321TransactionProposal {
        val proposal = createZip321Proposal(KEYSTONE_URI, allowMultiPayment = true)
        createPCZTFromProposal()
        dataSource.awaitProofs()
        val field = KeystoneProposalRepositoryImpl::class.java.getDeclaredField("pcztWithSignatures")
        field.isAccessible = true
        field.set(this, PCZT)
        return proposal
    }

    /** The settlement seam: counts the calls that reach it and lets [outcome] decide what happens. */
    private class CountingSeam(
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
        private val outcome: () -> SubmitResult,
    ) : ProposalDataSource by recording {
        var submits = 0
            private set

        private val proofs = CompletableDeferred<Unit>()

        suspend fun awaitProofs() = withTimeout(AWAIT_MS) { proofs.await() }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean
        ): Zip321TransactionProposal = syntheticProposal()

        override suspend fun createPcztFromProposal(account: WalletAccount, proposal: Proposal): Pczt = PCZT

        override suspend fun addProofsToPczt(pczt: Pczt): Pczt {
            proofs.complete(Unit)
            return pczt
        }

        override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult = settle()

        override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult = settle()

        private fun settle(): SubmitResult {
            submits++
            return outcome()
        }
    }

    /** Stands in for a locked keystore / an unusable persisted wallet: nothing can be signed. */
    private object RefusingSpendingKeyDataSource : ZashiSpendingKeyDataSource {
        override suspend fun getZashiSpendingKey(): UnifiedSpendingKey = throw IllegalStateException(KEY_FAILURE)
    }

    /** The synchronizer never becomes available, so nothing can be handed to the network. */
    private object UnreachableSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = throw IllegalStateException(SYNCHRONIZER_FAILURE)

        override fun resetSynchronizer() = Unit
    }

    /** A Synchronizer that is not the concrete `SdkSynchronizer`: the cast fails before any broadcast. */
    private object ForeignSynchronizerProvider : SynchronizerProvider {
        private val foreign: Synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "ForeignSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> throw UnsupportedOperationException("nothing may be called on the foreign synchronizer")
                }
            } as Synchronizer

        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(foreign)

        override suspend fun getSynchronizer(): Synchronizer = foreign

        override fun resetSynchronizer() = Unit
    }

    private companion object {
        const val AWAIT_MS = 10_000L

        /** Bare-alphanumeric and >= 20 chars, so nothing downstream rejects it on shape. */
        const val PEER = "usyntheticprebroadcast00000"

        const val KEYSTONE_URI = "zcash:usynthetickeystonepeer?amount=0.05&memo=c3ludGhldGlj"

        const val TXID = "synthetic-broadcast-txid"

        const val KEY_FAILURE = "synthetic spending-key derivation failure"

        /** Stands in for the pre-broadcast steps inside `submitTransactionInternal`. */
        const val PRE_BROADCAST_FAILURE = "synthetic pre-broadcast failure"

        /** Stands in for `refreshAllBalances()`, which runs AFTER the broadcast. */
        const val REFRESH_FAILURE = "synthetic post-broadcast balance refresh failure"

        const val SYNCHRONIZER_FAILURE = "synthetic unreachable synchronizer"

        val PCZT = Pczt(byteArrayOf(0x11, 0x11, 0x11))

        suspend fun syntheticProposal() = syntheticZip321Proposal(PEER, amount = 5_000_000L, memo = "synthetic-payment")
    }
}
