package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import kotlin.test.Test

/**
 * DOUBLE-PAY (money loss) — RED regression, chat/automatic-send half.
 *
 * Every ZCHAT on-chain send goes through [CreateChunkedMessageProposalUseCase]. On the
 * `directSubmit = true, skipNavigation = true` path — the automatic one: KEX / KEXACK / ZBOOT
 * handshakes, group invites and control memos, read receipts, chat messages, and (on a Zashi
 * account) `fulfillPaymentRequest` — an exception thrown by the repository is deliberately
 * RE-THROWN to the caller (CreateChunkedMessageProposalUseCase.kt:656-663), because the user never
 * sees the TransactionProgress screen.
 *
 * The post-broadcast balance refresh at ProposalDataSource.kt:451-452 throws INTO that path (see
 * PostBroadcastNeverReportedFailedTest for the repository half), so a transaction the SDK has
 * already broadcast arrives at each caller as a plain failure — and every one of those callers
 * reacts by re-arming and paying again:
 *
 *   * `ChatViewModel.sendHandshakeMemoWithRetry` (2264-2277): the throw is not a transient
 *     insufficient-funds, so it logs "failed permanently" and returns false. Callers read false as
 *     "nothing was sent" and re-arm their durable sent-marker — ZBOOT at 2200
 *     (`setSentNostrBootPubkey(peer, null)`, re-fired by the block-driven `retryPendingTunnelZboots`),
 *     KEX at 5761 (`setOwnBootSent(peer, false)`), KEXACK at 5809, whose marker is only written on
 *     true, so a falsely-failed ack is re-sent and re-paid on EVERY chat open. Each leg costs a
 *     ~1,000-zatoshi carrier output plus a ZIP-317 fee; 17,000 zatoshi went out this way on
 *     2026-09-07 (ChatViewModel.kt:2229-2233).
 *   * `ChatViewModel.fulfillPaymentRequest` (7692-7723): the throw skips
 *     `zchatPreferences.markRequestPaid(...)` and the `paidRequestIds` idempotency backstop
 *     (7709-7710) and leaves the request bubble's `isPaid` false, so the "Pay" affordance stays
 *     live — the next tap re-sends the FULL requested amount.
 *   * `GroupViewModel.sendGroupMemoWithRetry` (1153-1167): identical shape for invites and group
 *     control memos.
 *
 * INVARIANT ENCODED HERE: once the SDK has broadcast, the use case must not report failure to these
 * automatic callers. A definite rejection (the SDK says nothing was submitted) must still be
 * reported as failure — that is what the last test pins, so the fix cannot be "swallow everything".
 *
 * The use case is driven for real, over the real [ZashiProposalRepositoryImpl], with a hand-written
 * [ProposalDataSource] at the settlement seam that RECORDS the broadcast and only then throws.
 *
 * All addresses/memos/keys/URIs are SYNTHETIC placeholders — nothing parses or validates.
 */
class PostBroadcastNeverReportedFailedChatSendTest {
    /**
     * RED at HEAD. The automatic handshake leg (dust carrier output, rawMemo, no user gesture). The
     * SDK broadcast it; the refresh afterwards threw; at HEAD the use case rethrows, so
     * `sendHandshakeMemoWithRetry` returns false, the caller re-arms its sent-marker and the same
     * handshake is broadcast and paid for again on the next block / next chat open.
     */
    @Test
    fun `a broadcast handshake memo is never reported as failed to the automatic retry`() =
        runBlocking {
            val harness = harness(SubmitBehavior.THROW_AFTER_BROADCAST)

            val outcome =
                runCatching {
                    harness.useCase(
                        destinationAddress = CHAT_PEER,
                        senderAddress = CHAT_SELF,
                        message = "synthetic-handshake-memo",
                        isFirstMessage = false,
                        amountPerOutput = Zatoshi(HANDSHAKE_CARRIER_ZAT),
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true
                    )
                }

            assertEquals(
                "fixture sanity: the SDK must have broadcast before the refresh threw",
                1,
                harness.dataSource.broadcasts.size
            )
            assertEquals(
                "the handshake memo WAS broadcast and paid for, but the use case reported failure. " +
                    "sendHandshakeMemoWithRetry then returns false, every caller reads false as " +
                    "\"nothing was sent\" and re-arms its durable sent-marker (ZBOOT ChatViewModel.kt:2200, " +
                    "KEX 5761, KEXACK 5809), so the same leg is broadcast and charged again — unbounded for " +
                    "KEXACK, which re-fires on every chat open. Once the SDK has broadcast, nothing " +
                    "afterwards (the balance refresh at ProposalDataSource.kt:451-452) may turn the send " +
                    "into a failure.",
                REPORTED_SENT,
                describe(outcome)
            )
        }

    /**
     * RED at HEAD, and the worst case: a chat payment. `fulfillPaymentRequest` passes
     * `skipNavigation = !isKeystone`, so on Zashi the throw unwinds past `markRequestPaid` and past
     * the `paidRequestIds` idempotency backstop — the guard that exists to prevent a double payment
     * is disarmed by the very failure it guards against, and the live "Pay" button re-sends the FULL
     * amount.
     */
    @Test
    fun `a broadcast chat payment is never reported as failed to the caller that arms idempotency`() =
        runBlocking {
            val harness = harness(SubmitBehavior.THROW_AFTER_BROADCAST)

            val outcome =
                runCatching {
                    harness.useCase(
                        destinationAddress = CHAT_PEER,
                        senderAddress = CHAT_SELF,
                        message = "synthetic-payment-memo",
                        isFirstMessage = false,
                        amountPerOutput = Zatoshi(PAYMENT_ZAT),
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true
                    )
                }

            assertEquals(
                "fixture sanity: the SDK must have broadcast before the refresh threw",
                1,
                harness.dataSource.broadcasts.size
            )
            assertEquals(
                "the payment WAS broadcast, but the use case reported failure. fulfillPaymentRequest's " +
                    "catch (ChatViewModel.kt:7722) then skips markRequestPaid and the paidRequestIds " +
                    "backstop (7709-7710) and leaves the request bubble unpaid, so the \"Pay\" affordance " +
                    "stays live and one more tap sends the FULL requested amount a second time.",
                REPORTED_SENT,
                describe(outcome)
            )
        }

    /**
     * RED at bc68c80f2. The automatic handshake leg hits a network failure during submit: the SDK
     * returns [SubmitResult.GrpcFailure]. SDK 3.0.0 has already stored that transaction and
     * re-broadcasts it on every sync until mined or expired, without ever asking the server whether
     * it landed, so money may still move. At HEAD the use case throws "retryable",
     * sendHandshakeMemoWithRetry returns false, the caller clears its sent-marker and the same
     * handshake is built and paid for a second time once the network is back.
     */
    @Test
    fun `a handshake memo the SDK stored but could not deliver yet is never reported as failed`() =
        runBlocking {
            val harness = harness(SubmitBehavior.GRPC_FAILURE)

            val outcome =
                runCatching {
                    harness.useCase(
                        destinationAddress = CHAT_PEER,
                        senderAddress = CHAT_SELF,
                        message = "synthetic-handshake-memo",
                        isFirstMessage = false,
                        amountPerOutput = Zatoshi(HANDSHAKE_CARRIER_ZAT),
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true
                    )
                }

            assertEquals(
                "fixture sanity: the SDK must have stored the transaction for re-broadcast",
                1,
                harness.dataSource.stored.size
            )
            assertEquals(
                "a GrpcFailure transaction is stored and re-broadcast by the SDK until mined or expired. " +
                    "Reporting it as failed makes sendHandshakeMemoWithRetry return false, every caller " +
                    "re-arms its sent-marker (ZBOOT, KEX, KEXACK, group memos) and the same leg is paid twice.",
                REPORTED_SENT,
                describe(outcome)
            )
        }

    /**
     * Control, GREEN at HEAD and after the fix: a clean automatic send completes and reaches the SDK
     * exactly once. Proves the harness (real use case, real Zashi repository, ZIP-321 build,
     * spend-auth gate) is sound, so the two tests above fail only because of the post-broadcast
     * throw.
     */
    @Test
    fun `control - a clean automatic send completes and broadcasts exactly once`() =
        runBlocking {
            val harness = harness(SubmitBehavior.SUCCEED)

            val outcome =
                runCatching {
                    harness.useCase(
                        destinationAddress = CHAT_PEER,
                        senderAddress = CHAT_SELF,
                        message = "synthetic-handshake-memo",
                        isFirstMessage = false,
                        amountPerOutput = Zatoshi(HANDSHAKE_CARRIER_ZAT),
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true
                    )
                }

            assertEquals(REPORTED_SENT, describe(outcome))
            assertEquals(1, harness.dataSource.broadcasts.size)
        }

    /**
     * Control, GREEN at HEAD and REQUIRED to stay green: a DEFINITE rejection — the SDK classified
     * the batch as [SubmitResult.Failure], meaning no output was submitted — must still be reported
     * as a failure, so the handshake/group retry re-arms and tries again. The fix must distinguish
     * "the SDK says nothing was sent" from "something threw after the broadcast"; it must not be
     * implemented by swallowing every negative outcome, which would silently drop real failures and
     * report phantom sends.
     */
    @Test
    fun `control - a definite SDK rejection is still reported as failed`() =
        runBlocking {
            val harness = harness(SubmitBehavior.REJECT)

            val outcome =
                runCatching {
                    harness.useCase(
                        destinationAddress = CHAT_PEER,
                        senderAddress = CHAT_SELF,
                        message = "synthetic-handshake-memo",
                        isFirstMessage = false,
                        amountPerOutput = Zatoshi(HANDSHAKE_CARRIER_ZAT),
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true
                    )
                }

            assertEquals(
                "a batch the SDK rejected outright (successCount == 0, nothing on the wire) must stay a " +
                    "failure: the automatic retry has to re-arm and send it again, and a user-facing send " +
                    "must not claim money moved when none did",
                true,
                outcome.isFailure
            )
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private class Harness(
        val dataSource: ChatSendSeamDataSource,
        val useCase: CreateChunkedMessageProposalUseCase
    )

    private suspend fun harness(behavior: SubmitBehavior): Harness {
        val accounts = FakeChatZashiAccountDataSource(syntheticChatZashiAccount())
        val dataSource = ChatSendSeamDataSource(behavior)
        val zashiRepository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = dataSource,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
            )
        return Harness(
            dataSource = dataSource,
            useCase =
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository = zashiRepository,
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = ApprovingBiometricRepository()
                )
        )
    }
}

// ── Constants (all synthetic) ────────────────────────────────────────────────────────────────────

/** Bare-alphanumeric and >= 20 chars so the ZIP-321 builder's address-shape guard accepts it. */
private const val CHAT_PEER = "usyntheticchatpeer0000000000"

private const val CHAT_SELF = "usyntheticchatself0000000000"

/** The carrier output every automatic handshake memo pays (see sendHandshakeMemoWithRetry). */
private const val HANDSHAKE_CARRIER_ZAT = 1_000L

/** A value transfer: above dust, so the #248 spend-auth gate is exercised too. */
private const val PAYMENT_ZAT = 5_000_000L

private const val SYNTHETIC_TXID = "synthetic-broadcast-txid"

/**
 * What the post-broadcast step throws. Stands in for `synchronizer.refreshAllBalances()` /
 * `refreshTransactions()` at ProposalDataSource.kt:451-452 — the calls that run AFTER the broadcast
 * and outside any try/catch.
 */
private const val REFRESH_FAILURE = "synthetic post-broadcast balance refresh failure"

private const val REPORTED_SENT = "reported as SENT (no error raised to the caller)"

// Result<*>, not Result<Unit>: the use case now RETURNS the settlement outcome so a caller can word
// an honest confirmation (see AmbiguousSendOutcomeReachesTheCallerTest). What this file pins is
// unchanged — whether the send was reported as sent or as failed, i.e. only the success/failure side.
private fun describe(outcome: Result<*>): String =
    outcome.fold(
        onSuccess = { REPORTED_SENT },
        onFailure = { "reported as FAILED: ${it::class.simpleName}: ${it.message}" }
    )

// ── Fakes (hand-written; no mockk, no Robolectric) ───────────────────────────────────────────────

private enum class SubmitBehavior {
    /** The SDK broadcasts; the balance refresh that runs afterwards throws (the defect). */
    THROW_AFTER_BROADCAST,

    /** The SDK broadcasts and everything after it works. */
    SUCCEED,

    /** The SDK rejected the whole batch: successCount == 0, nothing reached the network. */
    REJECT,

    /**
     * A gRPC/Tor failure during submit. The SDK already stored the transaction and re-broadcasts
     * it every sync until mined or expired, so it may still land.
     */
    GRPC_FAILURE
}

/**
 * The settlement seam under the REAL [ZashiProposalRepositoryImpl]. Returns one prepared proposal
 * for whatever ZIP-321 URI the use case builds, and RECORDS every transaction the SDK puts on the
 * wire before [SubmitBehavior] decides what happens next.
 */
private class ChatSendSeamDataSource(
    private val behavior: SubmitBehavior
) : ProposalDataSource {
    /** Every transaction the SDK actually put on the wire: money that has left the wallet. */
    val broadcasts = mutableListOf<String>()

    /** Transactions the SDK stored and will keep re-broadcasting even though this submit failed. */
    val stored = mutableListOf<String>()

    private val prepared =
        runBlocking { syntheticZip321Proposal(CHAT_PEER, amount = PAYMENT_ZAT, memo = "synthetic-chat-memo") }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean
    ): Zip321TransactionProposal = prepared

    override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult =
        when (behavior) {
            SubmitBehavior.REJECT ->
                SubmitResult.Failure(txIds = listOf(SYNTHETIC_TXID), code = 1, description = "rejected by the network")

            SubmitBehavior.SUCCEED -> {
                broadcasts += SYNTHETIC_TXID
                SubmitResult.Success(txIds = listOf(SYNTHETIC_TXID))
            }

            SubmitBehavior.GRPC_FAILURE -> {
                stored += SYNTHETIC_TXID
                SubmitResult.GrpcFailure(txIds = listOf(SYNTHETIC_TXID))
            }

            SubmitBehavior.THROW_AFTER_BROADCAST -> {
                broadcasts += SYNTHETIC_TXID
                throw RuntimeException(REFRESH_FAILURE)
            }
        }

    // Unused in these scenarios.
    override suspend fun createProposal(account: WalletAccount, send: ZecSend): RegularTransactionProposal =
        error("not used in this test")

    override suspend fun createExactInputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote
    ): ExactInputSwapTransactionProposal = error("not used in this test")

    override suspend fun createExactOutputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote
    ): ExactOutputSwapTransactionProposal = error("not used in this test")

    override suspend fun createShieldProposal(account: WalletAccount): ShieldTransactionProposal =
        error("not used in this test")

    override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
        error("not used in this test")

    override suspend fun probeFee(account: WalletAccount, send: ZecSend): FeeProbe = error("not used in this test")

    override suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe =
        error("not used in this test")

    override suspend fun createPcztFromProposal(account: WalletAccount, proposal: Proposal): Pczt =
        error("the ZASHI account is selected in this test")

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt = error("the ZASHI account is selected in this test")

    override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult =
        error("the ZASHI account is selected in this test")

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt = error("not used in this test")
}

/** The user authenticates: the #248 value-transfer spend-auth gate is not what these tests pin. */
private class ApprovingBiometricRepository : BiometricRepository {
    override val allowedAuthenticators: Int = 0

    override fun onBiometricResult(result: BiometricResult) = Unit

    override suspend fun requestBiometrics(request: BiometricRequest) = Unit
}

/**
 * Zero balances on purpose: `orchardSealedRefusal` refuses only when sealed Orchard holds value and
 * spendable Ironwood is empty, so an all-zero account passes the pool gate and the send reaches the
 * settlement seam under test.
 */
private suspend fun syntheticChatZashiAccount(): ZashiAccount {
    val zeroBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
    return ZashiAccount(
        // The SDK's own public test fixture — all values are the fixture's synthetic defaults.
        sdkAccount = AccountFixture.new(),
        unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-chat-self"), zeroBalance),
        sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-chat-self"), zeroBalance),
        transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-chat-self"), Zatoshi(0)),
        isSelected = true
    )
}

private class FakeChatZashiAccountDataSource(
    private val account: ZashiAccount
) : AccountDataSource {
    override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account))
    override val selectedAccount: Flow<WalletAccount?> = flowOf(account)
    override val zashiAccount: Flow<ZashiAccount?> = flowOf(account)

    override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account)

    override suspend fun getSelectedAccount(): WalletAccount = account

    override suspend fun getZashiAccount(): ZashiAccount = account

    override suspend fun selectAccount(account: Account) = Unit

    override suspend fun selectAccount(account: WalletAccount) = Unit

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit
}
