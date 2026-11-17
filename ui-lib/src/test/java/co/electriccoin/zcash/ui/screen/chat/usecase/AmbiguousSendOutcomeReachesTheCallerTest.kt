package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.NothingBroadcastException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The chat lane must not claim more than it knows.
 *
 * The double-pay fix made an ambiguous settlement outcome ([SubmitResult.SentUnconfirmed]) a
 * NO-THROW result on the direct-submit path, which is right — throwing is what re-armed the
 * automatic senders and paid twice. But the chat callers only ever saw "it didn't throw", so
 * `fulfillPaymentRequest` still told the user a flat "Payment sent" for an outcome the code itself
 * records as UNKNOWN, and the Send screen's honest "Sent — Not Confirmed" had no counterpart in
 * chat.
 *
 * [CreateChunkedMessageProposalUseCase] therefore RETURNS the settlement outcome. The idempotency
 * backstop (`markRequestPaid` + `paidRequestIds`) still runs on every no-throw outcome — money may
 * have moved, and re-arming the "Pay" button would overpay — only the wording changes.
 *
 * Pinned here:
 *  * an ambiguous outcome comes back as [SubmitResult.SentUnconfirmed] (so the caller can say
 *    "submitted, not confirmed yet" instead of "sent");
 *  * a clean send still comes back as [SubmitResult.Success] (the caller's wording is unchanged);
 *  * a proven non-broadcast still THROWS on this path, so no caller marks anything paid.
 *
 * All addresses, memos and keys are SYNTHETIC placeholders — nothing parses or validates.
 */
class AmbiguousSendOutcomeReachesTheCallerTest {
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
    fun `an ambiguous settlement outcome is returned to the caller, not flattened into success`() =
        runBlocking {
            val outcome = send(ChatSeam { throw RuntimeException(REFRESH_FAILURE) })

            assertEquals(
                SubmitResult.SentUnconfirmed(txIds = emptyList(), description = REFRESH_FAILURE),
                outcome,
                "the caller cannot word an honest confirmation if the ambiguity is hidden from it"
            )
        }

    @Test
    fun `a clean send still comes back as a plain success`() =
        runBlocking {
            val outcome = send(ChatSeam { SubmitResult.Success(txIds = listOf(TXID)) })

            assertEquals(SubmitResult.Success(txIds = listOf(TXID)), outcome)
        }

    @Test
    fun `a proven non-broadcast still fails the send`() =
        runBlocking {
            val e =
                assertFailsWith<RuntimeException> {
                    send(ChatSeam { throw NothingBroadcastException(RuntimeException(PRE_BROADCAST_FAILURE)) })
                }

            assertTrue(
                e.message.orEmpty().contains(PRE_BROADCAST_FAILURE),
                "nothing left the wallet, so the caller must not mark the request paid; was: ${e.message}"
            )
        }

    /**
     * DOUBLE-PAY. A gRPC/Tor failure during submit is NOT "nothing was sent". SDK 3.0.0 has already
     * stored the transaction (with its submit plan) and re-broadcasts it on every sync until it is
     * mined or expires, and on that path it never asks the server whether the transaction landed.
     * Throwing here makes the automatic senders clear their already-sent markers and pay the same
     * leg again, and makes fulfillPaymentRequest skip markRequestPaid so the live "Pay" button
     * re-sends the full amount. The outcome must come back to the caller, unthrown, carrying its
     * txids, so the caller can word it as "submitted, not confirmed".
     */
    @Test
    fun `a network failure is returned to the caller as not delivered yet, never thrown`() =
        runBlocking {
            val outcome =
                runCatching { send(ChatSeam { SubmitResult.GrpcFailure(txIds = listOf(TXID)) }) }

            assertEquals(
                Result.success<SubmitResult?>(SubmitResult.GrpcFailure(txIds = listOf(TXID))),
                outcome,
                "the SDK re-broadcasts a GrpcFailure transaction by itself; reporting it as failed " +
                    "re-arms the automatic senders and the Pay button, and the same money is sent twice"
            )
        }

    /**
     * Control: on a non-gRPC [SubmitResult.Failure] the server answered, and the SDK then checked
     * whether it already knew the transaction before reporting failure. That is a genuine
     * "nothing landed", so the send must still fail and no caller may mark anything paid.
     */
    @Test
    fun `a server rejection still fails the send`() =
        runBlocking {
            val e =
                assertFailsWith<RuntimeException> {
                    send(ChatSeam { SubmitResult.Failure(txIds = listOf(TXID), code = 18, description = REJECTION) })
                }

            assertTrue(
                e.message.orEmpty().contains(REJECTION),
                "the server rejected the transaction, so the caller must be told it failed; was: ${e.message}"
            )
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    /** One value-transfer chat send on the automatic path (directSubmit + skipNavigation). */
    private suspend fun send(dataSource: ProposalDataSource): SubmitResult? {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = dataSource,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
                    ).also(sendScopes::own),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = ApprovingBiometricRepository
            )

        return useCase(
            destinationAddress = PEER,
            senderAddress = SELF,
            message = "ZREQ_FULFILL|synthetic-request-id",
            isFirstMessage = false,
            amountPerOutput = Zatoshi(PAYMENT_ZAT),
            directSubmit = true,
            skipNavigation = true,
            rawMemo = true
        )
    }

    /** Returns one prepared proposal for whatever ZIP-321 URI the use case builds, then settles. */
    private class ChatSeam(
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
        private val outcome: () -> SubmitResult,
    ) : ProposalDataSource by recording {
        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean
        ): Zip321TransactionProposal = syntheticZip321Proposal(PEER, amount = PAYMENT_ZAT, memo = "synthetic-chat-memo")

        override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult = outcome()

        override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult =
            error("the ZASHI account is selected in this test")
    }

    /** The user authenticates: the #248 value-transfer spend-auth gate is not what this file pins. */
    private object ApprovingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) = Unit
    }

    private companion object {
        /** Bare-alphanumeric and >= 20 chars so the ZIP-321 builder's address-shape guard accepts it. */
        const val PEER = "usyntheticambiguouspeer00000"

        const val SELF = "usyntheticambiguousself00000"

        /** A value transfer: above dust, so the spend-auth gate is exercised too. */
        const val PAYMENT_ZAT = 5_000_000L

        const val TXID = "synthetic-broadcast-txid"

        /** Stands in for `refreshAllBalances()`, which runs AFTER the broadcast. */
        const val REFRESH_FAILURE = "synthetic post-broadcast balance refresh failure"

        const val PRE_BROADCAST_FAILURE = "synthetic pre-broadcast failure"

        const val REJECTION = "synthetic server rejection"
    }
}
