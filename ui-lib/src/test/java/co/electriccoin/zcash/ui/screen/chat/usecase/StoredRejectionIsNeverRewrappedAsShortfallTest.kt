package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C4 (round 7 adversarial review of B1, item 4) — a submit the SDK stored and the server then rejected is
 * turned into a cause-less [InsufficientFundsException] when the rejection text looks like a shortfall.
 *
 * The rejection reaches the use case's catch as `SubmitRejectedException(nothingStored = false, txIds = [T])`.
 * Its message embeds the server's description. `isInsufficientFundsError` matches "Insufficient balance",
 * "InsufficientFunds", "Insufficient amount of ZEC" or "additional change output" anywhere in the chain, and
 * with skipNavigation the catch then throws a NEW `InsufficientFundsException(notice)` with no cause:
 *
 * - `nothingStored = false` and the stored txids are lost, so no caller can link T to its row;
 * - a caller that trusts an insufficient-funds error as "nothing was sent" re-arms or retries a transaction
 *   the SDK may still broadcast, and the same memo or payment is paid twice.
 *
 * Wanted: once the submit has started, the shortfall classification is skipped unless the failure proves
 * nothing was stored. The rejection reaches the caller as it is, with its txids.
 *
 * All addresses, memos and txids are synthetic.
 */
class StoredRejectionIsNeverRewrappedAsShortfallTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
    }

    @After
    fun tearDown() {
        sendScopes.close()
    }

    @Test
    fun `a stored then rejected submit whose text looks like a shortfall reaches the caller as the rejection with its txids`() =
        runBlocking<Unit> {
            val seam =
                Seam(
                    submitOutcome = {
                        SubmitResult.Failure(txIds = listOf(STORED_T), code = -26, description = SHORTFALL_LIKE_REJECTION)
                    },
                )

            val error = runCatching { send(seam) }.exceptionOrNull()

            assertEquals(1, seam.submits, "fixture: one submit reached the SDK")
            assertFalse(
                error is InsufficientFundsException,
                "the SDK stored T and may still broadcast it, but the caller is told the wallet fell short (nothing " +
                    "sent, safe to retry): $error",
            )
            val rejection = assertIs<SubmitRejectedException>(error, "the stored-then-rejected submit was rewrapped: $error")
            assertFalse(rejection.nothingStored, "the rejection must still say a transaction was stored")
            assertEquals(listOf(STORED_T), rejection.txIds, "the stored transaction id must reach the caller")
        }

    /** Control, GREEN before and after: a rejection that stored nothing may still be read as a shortfall. */
    @Test
    fun `control - a rejection that stored nothing is still reported as a shortfall`() =
        runBlocking<Unit> {
            val seam =
                Seam(
                    submitOutcome = {
                        SubmitResult.Failure(txIds = emptyList(), code = -26, description = SHORTFALL_LIKE_REJECTION)
                    },
                )

            val error = runCatching { send(seam) }.exceptionOrNull()

            assertIs<InsufficientFundsException>(error, "nothing was stored, so the shortfall notice is still right: $error")
        }

    /** Control, GREEN before and after: a shortfall found while the proposal is built is still a shortfall. */
    @Test
    fun `control - a shortfall found before the submit is still an InsufficientFundsException`() =
        runBlocking<Unit> {
            val seam = Seam(failProposalWith = HandshakeHarness.stillConfirming())

            val error = runCatching { send(seam) }.exceptionOrNull()

            assertEquals(0, seam.submits, "fixture: nothing reached the submit")
            assertIs<InsufficientFundsException>(error, "a shortfall before the submit must keep its notice: $error")
            assertTrue(error.message.orEmpty().isNotBlank())
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    /** One dust group-style memo on the automatic path (directSubmit + skipNavigation), as a group memo is sent. */
    private suspend fun send(dataSource: ProposalDataSource): SubmitResult? {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = dataSource,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(sendScopes::own),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )

        return useCase(
            destinationAddress = PEER,
            senderAddress = SELF,
            message = "GROUP_KEY|synthetic",
            isFirstMessage = false,
            directSubmit = true,
            skipNavigation = true,
            rawMemo = true,
        )
    }

    private class Seam(
        private val failProposalWith: Exception? = null,
        private val submitOutcome: () -> SubmitResult = { SubmitResult.Success(txIds = listOf("synthetic-ok")) },
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        @Volatile var submits = 0

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            failProposalWith?.let { throw it }
            return syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic-group-memo")
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            submits++
            return submitOutcome()
        }

        override suspend fun submitTransaction(
            pcztWithProofs: Pczt,
            pcztWithSignatures: Pczt,
        ): SubmitResult = error("the ZASHI account is selected in this test")
    }

    private companion object {
        const val PEER = "usyntheticstoredrejectpeer01"
        const val SELF = "usyntheticstoredrejectself01"
        const val STORED_T = "synthetic-stored-T"
        const val SHORTFALL_LIKE_REJECTION = "Insufficient balance (synthetic rejection text)"
    }
}
