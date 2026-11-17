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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D1 (REVIEW_7C F1 + F8, F2) — a shortfall the SDK reports while it builds the transaction, AFTER onSubmitStarting
 * ran, proves that nothing was stored: the settlement (ProposalDataSource.settleCreateFailure) throws
 * NothingBroadcastException, ZashiProposalRepository turns it into `Failure(txIds = [], code = 0)`, and the use case
 * into `SubmitRejectedException(nothingStored = true)`.
 *
 * The use case's catch then throws a NEW `InsufficientFundsException(notice)` with no cause. memoFailureProvesNothingSent
 * with submitStarted = true finds no nothingStored rejection in that chain, so every caller reads the shortfall as
 * "may have been sent".
 *
 * Wanted: the InsufficientFundsException keeps the notice and carries the rejection as its cause, so the proof
 * reaches the caller. And the rule after the submit is tightened to "every rejection in the chain stored nothing"
 * (a chain that also names a stored transaction proves nothing).
 *
 * All addresses, memos and txids are synthetic.
 */
class NothingStoredShortfallCarriesItsProofTest {
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
    fun `a shortfall found after the submit started that stored nothing reaches the caller with its proof`() =
        runBlocking<Unit> {
            val seam =
                Seam(
                    submitOutcome = {
                        SubmitResult.Failure(txIds = emptyList(), code = 0, description = SDK_SHORTFALL)
                    },
                )

            val error = runCatching { send(seam) }.exceptionOrNull()

            assertEquals(1, seam.submits, "fixture: one submit reached the SDK")
            val shortfall = assertIs<InsufficientFundsException>(error, "the shortfall notice must still reach the caller: $error")
            assertTrue(shortfall.message.orEmpty().isNotBlank(), "the shortfall notice must be kept")
            assertTrue(
                memoFailureProvesNothingSent(shortfall, submitStarted = true),
                "the SDK stored nothing, but the shortfall the caller gets carries no proof of it, so every caller " +
                    "reads it as 'may have been sent': ${chainOf(shortfall)}",
            )
        }

    // ── F2: the rule, as a table ─────────────────────────────────────────────────────────────────────

    @Test
    fun `memoFailureProvesNothingSent over before and after the submit and every chain shape`() {
        val nothingStored = { SubmitRejectedException("Transaction submit failed (code=0 synthetic)", nothingStored = true) }
        val stored = {
            SubmitRejectedException("Transaction submit failed (code=-26 synthetic)", nothingStored = false, txIds = listOf("synthetic-T"))
        }
        // Shape → (before the submit, after the submit).
        val table =
            listOf(
                Row("no rejection", IllegalStateException("synthetic refusal"), before = true, after = false),
                Row("a rejection that stored nothing", nothingStored(), before = true, after = true),
                Row("a rejection that stored a transaction", stored(), before = false, after = false),
                Row(
                    "a shortfall wrapping a rejection that stored nothing",
                    shortfallCausedBy(nothingStored()),
                    before = true,
                    after = true,
                ),
                Row("a shortfall with no cause", InsufficientFundsException("synthetic notice"), before = true, after = false),
                Row(
                    "a chain with both a stored and a nothing-stored rejection",
                    shortfallCausedBy(nothingStored().also { it.initCause(stored()) }),
                    before = false,
                    after = false,
                ),
            )

        val wrong =
            table.flatMap { row ->
                listOfNotNull(
                    "before the submit, ${row.shape}: expected ${row.before}"
                        .takeIf { memoFailureProvesNothingSent(row.error, submitStarted = false) != row.before },
                    "after the submit, ${row.shape}: expected ${row.after}"
                        .takeIf { memoFailureProvesNothingSent(row.error, submitStarted = true) != row.after },
                )
            }
        assertEquals(emptyList(), wrong, "memoFailureProvesNothingSent answered wrongly")
    }

    private class Row(
        val shape: String,
        val error: Throwable,
        val before: Boolean,
        val after: Boolean,
    )

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A shortfall notice whose cause is [cause]. Built with initCause so this file compiles against the class with
     * and without its `cause` parameter (the RED commit comes before that parameter).
     */
    private fun shortfallCausedBy(cause: Throwable): InsufficientFundsException =
        InsufficientFundsException("synthetic notice").also { it.initCause(cause) }

    private fun chainOf(error: Throwable): List<String> =
        generateSequence(error) { it.cause }
            .take(10)
            .map { it.javaClass.simpleName }
            .toList()

    /** One dust memo on the automatic path (directSubmit + skipNavigation), with onSubmitStarting like every caller. */
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
            onSubmitStarting = {},
        )
    }

    private class Seam(
        private val submitOutcome: () -> SubmitResult,
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        @Volatile var submits = 0

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal = syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic-memo")

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
        const val PEER = "usyntheticd1shortfallpeer001"
        const val SELF = "usyntheticd1shortfallself001"

        /** What the settlement reports for a shortfall found while the SDK builds the transaction. */
        const val SDK_SHORTFALL = "Insufficient balance (have 0, need 17000 including fee)"
    }
}
