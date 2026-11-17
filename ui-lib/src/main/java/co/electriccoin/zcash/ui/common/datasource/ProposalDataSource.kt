package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.model.proposeSend
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.OrchardSweepDecision
import co.electriccoin.zcash.ui.common.model.OrchardSweepRefusedException
import co.electriccoin.zcash.ui.common.model.OrchardSweepTooSmallException
import co.electriccoin.zcash.ui.common.model.PROPOSAL_POOL_IRONWOOD
import co.electriccoin.zcash.ui.common.model.PlanProbe
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SaplingCrossingException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SweepShape
import co.electriccoin.zcash.ui.common.model.UnreadableProposalException
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.decideOrchardSweep
import co.electriccoin.zcash.ui.common.model.orchardSealedRefusal
import co.electriccoin.zcash.ui.common.model.orchardSweepShapeRefusal
import co.electriccoin.zcash.ui.common.model.publicCrossingRefusal
import co.electriccoin.zcash.ui.common.model.readProposalInputs
import co.electriccoin.zcash.ui.common.model.readSweepShape
import co.electriccoin.zcash.ui.common.model.saplingCrossingRefusal
import co.electriccoin.zcash.ui.common.model.scannedRequestMismatch
import co.electriccoin.zcash.ui.common.model.unreadableProposalRefusal
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.tex.WalletActivity
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.zecdev.zip321.ZIP321
import java.math.BigDecimal

@Suppress("TooManyFunctions")
interface ProposalDataSource {
    @Throws(
        TransactionProposalNotCreatedException::class,
        InsufficientFundsException::class,
        OrchardSealedException::class,
    )
    suspend fun createProposal(account: WalletAccount, send: ZecSend): RegularTransactionProposal

    @Throws(
        TransactionProposalNotCreatedException::class,
        InsufficientFundsException::class,
        OrchardSealedException::class,
    )
    suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean = false,
    ): Zip321TransactionProposal

    @Throws(
        TransactionProposalNotCreatedException::class,
        InsufficientFundsException::class,
        OrchardSealedException::class,
    )
    suspend fun createExactInputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote,
    ): ExactInputSwapTransactionProposal

    @Throws(
        TransactionProposalNotCreatedException::class,
        InsufficientFundsException::class,
        OrchardSealedException::class,
    )
    suspend fun createExactOutputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createShieldProposal(account: WalletAccount): ShieldTransactionProposal

    /**
     * The one-transaction move of [account]'s whole sealed Orchard balance to its own Ironwood address
     * (sweep spec §2.1). It takes no destination, amount, memo or URI: the SDK pays the wallet itself.
     * The proposal is checked before it is returned (one step, Orchard inputs only, one Ironwood payment,
     * no change, more than the marginal fee arriving). Building it writes nothing to the wallet.
     *
     * Throws [OrchardSweepTooSmallException] when nothing spendable would arrive, and
     * [TransactionProposalNotCreatedException] for a Keystone account, a balance that is not ready, or a
     * proposal that is not exactly the sweep.
     */
    @Throws(
        TransactionProposalNotCreatedException::class,
        InsufficientFundsException::class,
        OrchardSweepTooSmallException::class,
    )
    suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal

    /**
     * Price [send] without keeping it: the proposal is never stored in a repository and never
     * submitted, and building one writes nothing to the wallet. Used by Send all to learn the exact fee.
     * Orchard inputs are reported, not refused. A shortfall is [FeeProbe.Short]; any other failure
     * throws.
     */
    @Throws(TransactionProposalNotCreatedException::class)
    suspend fun probeFee(account: WalletAccount, send: ZecSend): FeeProbe

    /**
     * [probeFee] for a ZIP 321 request: prices [zip321Uri] (every payment in it) without keeping the
     * proposal. Used by the chat Send All, whose send is a multi-output request. A shortfall is
     * [FeeProbe.Short]; any other failure throws.
     */
    @Throws(TransactionProposalNotCreatedException::class)
    suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe

    /**
     * M2 (fix-all 2.13.0) — [probeZip321Fee] that also reads which notes the proposal would spend and the Ironwood
     * change it would make ([PlanProbe.Built]); nothing is stored or submitted. A shortfall is [PlanProbe.Short] with
     * the SDK's "need" when it gave one; any other failure throws. The default throws, so a source that cannot probe
     * fails closed (the first-message plan check then refuses).
     */
    @Throws(TransactionProposalNotCreatedException::class)
    suspend fun probeZip321Plan(account: WalletAccount, zip321Uri: String): PlanProbe =
        throw UnsupportedOperationException("This proposal source cannot probe a send plan")

    @Throws(PcztException.CreatePcztFromProposalException::class)
    suspend fun createPcztFromProposal(account: WalletAccount, proposal: Proposal): Pczt

    @Throws(PcztException.AddProofsToPcztException::class)
    suspend fun addProofsToPczt(pczt: Pczt): Pczt

    suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult

    suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult

    /**
     * F2: [submitTransaction] that hands every stored transaction's id and expiry height to [onStored] BEFORE the first
     * one is submitted (see [OnTransactionsStored]). The default, for a data source that cannot report them, is the
     * plain two-argument call.
     */
    suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: OnTransactionsStored,
    ): SubmitResult = submitTransaction(proposal, usk)

    @Throws(PcztException.RedactPcztForSignerException::class)
    suspend fun redactPcztForSigner(pczt: Pczt): Pczt
}

class TransactionProposalNotCreatedException(
    reason: Exception
) : Exception(reason)

/**
 * The wallet cannot cover amount + fee. [have] and [need] are the SDK's own figures ("Insufficient balance
 * (have A, need R including fee)") when it gave them. The message stays null on the proposal path: the
 * chat send paths key on a blank message to tell an SDK shortfall from their own worded ones.
 *
 * D1 (REVIEW_7C F1): [cause] is the failure the shortfall was found in. When the chat send chokepoint words a
 * shortfall found after its submit started, the cause is the `SubmitRejectedException(nothingStored = true)` that
 * proves nothing was stored, so the callers' proof rule (memoFailureProvesNothingSent) still finds it. It is set
 * with initCause only when given, so a shortfall built without one can still be given a cause later.
 */
class InsufficientFundsException(
    message: String? = null,
    val have: Zatoshi? = null,
    val need: Zatoshi? = null,
    cause: Throwable? = null,
) : Exception(message) {
    init {
        if (cause != null) initCause(cause)
    }
}

/**
 * MONEY-CRITICAL: thrown out of a `submitTransaction` that provably put NOTHING on the wire.
 *
 * An exception out of settlement is normally ambiguous — money may already have left the wallet, so
 * "it threw" cannot be read as "nothing was sent" (see [SubmitResult.SentUnconfirmed]). But the steps
 * that run BEFORE invoking SDK transaction creation are known not to store a transaction:
 * acquiring the synchronizer and deriving the spending key. A throw out of SDK creation itself is
 * marked with this type only when it is proven not to have stored anything: a shortfall (found while
 * the payment is built, before anything is stored), or a check of the wallet's own transaction list
 * that finds no new spend (see [settleSubmitFlow]). The proposal repositories map it back to a plain
 * [SubmitResult.Failure].
 *
 * Never widen this to anything thrown while the submit flow is being COLLECTED. SDK 3.0.0 sends each
 * transaction inside that collect and emits its result only after `client.dispose()` and an
 * encrypted-prefs write have run in `finally` blocks, so a throw there — even with nothing emitted —
 * can arrive after lightwalletd accepted the transaction. Reporting a broadcast payment as failed is
 * what re-armed the automatic chat senders and paid twice.
 *
 * It carries the cause's own message, because coroutine stack-trace recovery re-creates the
 * exception across a `withContext`/`await` boundary and re-nests the original under it — so
 * `cause` is not reliably the root, but `message` is stable.
 */
class NothingBroadcastException(
    cause: Throwable
) : Exception(cause.message, cause)

/**
 * F2 (fix-all 2.13.0): one transaction the SDK stored for a send, reported before anything is submitted. [txId] is in
 * the order `txIdString()` prints (the one [SubmitResult] ids use); [expiryHeight] is the last height it can be mined
 * at, or null when the SDK gave none.
 */
data class StoredTransaction(
    val txId: String,
    val expiryHeight: Long?,
)

/**
 * F2: called once the SDK has stored EVERY transaction of a send and before the first one is submitted, so a caller
 * can put the ids and expiry heights on durable record first. It is a checked commit: if it throws, nothing is
 * submitted and the send is [SubmitResult.SentUnconfirmed] with every id, because the transactions stay stored in the
 * wallet (see [createStoreAndSubmit]).
 */
typealias OnTransactionsStored = suspend (List<StoredTransaction>) -> Unit

/**
 * A throw out of SDK creation, settled from facts ("check, don't guess", 22 Sep).
 *
 * The backend builds every transaction of the proposal first and stores them all in ONE call at the end
 * (`create_proposed_transactions` -> `store_transactions_to_be_sent`); the SDK then writes its submit plan.
 * So a shortfall, which is only raised while building, proves nothing was stored. For anything else the
 * wallet's own local transaction list is read at once: a new own spend is this attempt's stored payment
 * (sent, tracked by its id); none proves nothing was stored (not sent, retryable). If the list cannot be
 * read, before or now, the attempt stays "maybe sent".
 *
 * R4-3 (1), report 4 item 4: "new" is only this attempt's when this attempt OWNED the window it appeared in
 * ([exclusiveWindow], see [SendAttemptRegistry]). A window shared with another send cannot tell the two
 * apart, so no id is claimed at all and the attempt stays "maybe sent" — matched by attempt, never by the
 * moment a transaction happened to land. NO new spend at all still proves nothing was stored, shared or not:
 * this attempt cannot have stored a transaction that is not in the list.
 *
 * B5 (GPT Q1, M4-3.2): [exclusiveWindow] is asked AFTER the second read, not when the window opened. A send that
 * gave up waiting for the lock and went in without it, at any moment up to that read, makes this window shared.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun settleCreateFailure(
    failure: Exception,
    ownSpends: (suspend () -> List<String>)?,
    spendsBefore: List<String>?,
    exclusiveWindow: () -> Boolean,
): SubmitResult {
    if (failure.isFoundWhileBuilding()) throw NothingBroadcastException(failure)
    val maybeSent = SubmitResult.SentUnconfirmed(txIds = emptyList(), description = failure.message)
    if (ownSpends == null || spendsBefore == null) return maybeSent
    val spendsAfter = readOwnSpends(ownSpends) ?: return maybeSent
    val known = spendsBefore.toSet()
    val newSpends = spendsAfter.filterNot { it in known }.distinct()
    if (newSpends.isEmpty()) {
        Twig.info { "Create step failed and the wallet holds no new spend: nothing was stored" }
        throw NothingBroadcastException(failure)
    }
    if (!exclusiveWindow()) {
        Twig.warn {
            "Create step failed while another send was creating: ${newSpends.size} new transaction(s) " +
                "cannot be attributed to this attempt, so none is claimed"
        }
        return maybeSent
    }
    Twig.info { "Create step failed after storing ${newSpends.size} transaction(s): tracked as sent" }
    return SubmitResult.SentUnconfirmed(txIds = newSpends, description = failure.message)
}

/**
 * R4-3 (1): whose transaction is a new one in the wallet, when two sends fail at the same moment.
 *
 * [settleCreateFailure] settles a create-step failure from what is NEW in the wallet's own transaction list
 * between a read taken right before the attempt and one taken right after the failure. That is a TIME window:
 * a SECOND send that stores its transaction inside it looks exactly like this attempt's, so one send can be
 * credited with the other's transaction — a message that never went out is marked sent, and two chat rows are
 * reconciled against one transaction.
 *
 * This registry makes the window belong to ONE attempt: the read-create-read of every send runs under one lock,
 * so a new spend seen inside it was stored by this attempt. An attempt that cannot take the lock within
 * [lockWaitMs] still runs — a send is never blocked on bookkeeping — but is told its window was shared, and the
 * settlement then claims NO id rather than one that may be another send's.
 *
 * B5 (GPT Q1, M4-3.2, review question 18): the send that HOLDS the lock is not alone either once a waiter went in
 * without it, and a window that opens while such an unlocked send is still creating is shared from its start. So a
 * window's exclusivity is a question asked when the failure is settled ([Window.exclusive]), not an answer fixed
 * when the window opened: the registry counts the unlocked sends inside and marks the holder's window shared the
 * moment one goes in. Marks only ever go from exclusive to shared.
 *
 * Process-wide: [globalSendAttempts]. Only the create step is held, never the network collect that follows it,
 * so a slow broadcast cannot hold another send's create.
 */
internal class SendAttemptRegistry(
    private val lockWaitMs: Long = CREATE_WINDOW_LOCK_WAIT_MS,
    private val pollMs: Long = CREATE_WINDOW_POLL_MS,
) {
    private val createWindow = Mutex()

    /** Guards [holder] and [unlockedInside]; held only for a few field writes, never across a suspension. */
    private val bookkeeping = Any()

    /** The window of the send that holds the lock now, or null. */
    private var holder: Window? = null

    /** The sends creating right now without the lock. */
    private var unlockedInside = 0

    /** One send attempt's create window. */
    private class Window(
        val locked: Boolean
    ) {
        @Volatile
        var shared: Boolean = !locked

        /** True while no other send could have stored a transaction inside this window. */
        fun exclusive(): Boolean = locked && !shared
    }

    /**
     * Runs [block] as one send attempt's create window. [block] is passed a question, asked when the attempt is
     * settled: true when the window was this attempt's alone up to that moment (only it could have stored a
     * transaction inside it), false once another attempt's create ran inside it at any time.
     */
    suspend fun <T> inCreateWindow(block: suspend (exclusive: () -> Boolean) -> T): T {
        val window = takeWindow()
        return try {
            block(window::exclusive)
        } finally {
            leave(window)
        }
    }

    /** tryLock/[delay] rather than a cancellable `lock()`, so no path can leave the window locked. */
    private suspend fun takeWindow(): Window {
        var waited = 0L
        while (true) {
            if (createWindow.tryLock()) {
                val window = Window(locked = true)
                synchronized(bookkeeping) {
                    // An unlocked send still creating can store its transaction inside this window.
                    if (unlockedInside > 0) window.shared = true
                    holder = window
                }
                return window
            }
            if (waited >= lockWaitMs) {
                Twig.warn { "Another send is still creating; this attempt's window is shared" }
                synchronized(bookkeeping) {
                    unlockedInside++
                    // The send holding the lock is no longer alone in its window either.
                    holder?.shared = true
                }
                return Window(locked = false)
            }
            delay(minOf(pollMs, lockWaitMs - waited))
            waited += pollMs
        }
    }

    private fun leave(window: Window) {
        if (window.locked) {
            synchronized(bookkeeping) { if (holder === window) holder = null }
            createWindow.unlock()
        } else {
            synchronized(bookkeeping) { unlockedInside-- }
        }
    }
}

/** The one create window of this process: every send settles against its own, exclusive read-create-read. */
internal val globalSendAttempts = SendAttemptRegistry()

/**
 * How long a send waits for the create window. Creating is CPU-bound proving plus a single-writer wallet
 * database, so two sends do not run it in parallel anyway; exceeding this is only the (safe) older behaviour.
 */
private const val CREATE_WINDOW_LOCK_WAIT_MS = 60_000L

private const val CREATE_WINDOW_POLL_MS = 25L

/** The wallet's own spends, or null when they cannot be read (the check cannot run). */
@Suppress("TooGenericExceptionCaught")
private suspend fun readOwnSpends(ownSpends: suspend () -> List<String>): List<String>? =
    try {
        ownSpends()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Twig.error(e) { "The wallet's own transaction list could not be read" }
        null
    }

/** A shortfall anywhere in the cause chain: the backend raises it while building, before storing anything. */
private fun Throwable.isFoundWhileBuilding(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .any { it.isShortfall() }

private const val MAX_CAUSE_DEPTH = 8

/** How long the wallet's own list may take to read before the check counts as unable to run. */
private const val OWN_SPENDS_READ_TIMEOUT_MS = 10_000L

/**
 * The ids of the transactions in the wallet's own local list that spend from it. Every payment this
 * wallet builds spends its notes, so it is a sent transaction or spends a non-zero amount; a plain
 * receipt is neither, and is left out so an incoming payment is never taken for this attempt. Read
 * fresh from the wallet database on each call (the SDK's list re-queries on collection).
 */
private suspend fun Synchronizer.ownSpendIds(): List<String> =
    withTimeoutOrNull(OWN_SPENDS_READ_TIMEOUT_MS) {
        allTransactions.first().filter { it.spendsFromThisWallet() }.map { it.txId.txIdString() }
    } ?: throw IllegalStateException("The wallet's transaction list could not be read in time")

private fun TransactionOverview.spendsFromThisWallet(): Boolean = isSentTransaction || totalSpent.value > 0L

/**
 * The settlement step of [ProposalDataSourceImpl.submitTransactionInternal], without the synchronizer:
 * [createAndSubmit] is the SDK call that builds the transactions and returns their submit flow. This
 * runs it, collects the flow and classifies what came back. It is kept apart from the `final`
 * `SdkSynchronizer` so the line between "provably nothing was sent" and "money may have moved" can be
 * pinned on the JVM.
 *
 * MONEY-CRITICAL: both eager creation and collection can throw after wallet storage has changed.
 * A throw during collection is returned as [SubmitResult.SentUnconfirmed], retaining any IDs already
 * collected. A throw out of eager creation is checked, not guessed (see [settleCreateFailure]):
 *  - a shortfall is found while the payment is built, before anything is stored: [NothingBroadcastException];
 *  - otherwise the wallet's own spends ([ownSpends]) are read at once and compared with a read taken right
 *    before the attempt: new spends mean it was stored, so [SubmitResult.SentUnconfirmed] carrying exactly
 *    those ids (tracked and reconciled by id); none means nothing was stored: [NothingBroadcastException];
 *  - only when either read fails (or no reader is given), or another send shared the window, does it stay
 *    [SubmitResult.SentUnconfirmed] with no ids ("maybe sent", Retry blocked).
 *
 * The read-create-read runs inside ONE create window ([attempts]), so what is new in it is this attempt's and
 * not a simultaneous send's. Only the create step is held: the network collect below runs outside the window.
 *
 * @param ownSpends reads the ids of the transactions in the wallet's own local list that spend from it.
 * @param attempts the create window this attempt runs in (see [SendAttemptRegistry]).
 */
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal suspend fun settleSubmitFlow(
    ownSpends: (suspend () -> List<String>)? = null,
    attempts: SendAttemptRegistry = globalSendAttempts,
    createAndSubmit: suspend () -> Flow<TransactionSubmitResult>,
): SubmitResult {
    var settledInWindow: SubmitResult? = null
    val createdFlow =
        attempts.inCreateWindow { exclusive ->
            // Read right before the attempt, so a failure can be settled by what is NEW in the wallet's own list.
            val spendsBefore = ownSpends?.let { readOwnSpends(it) }
            // SDK creation is eager: it can store transactions before returning the lazy submit flow.
            try {
                createAndSubmit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Throws NothingBroadcastException when nothing was provably stored; the window closes either way.
                settledInWindow = settleCreateFailure(e, ownSpends, spendsBefore, exclusiveWindow = exclusive)
                null
            }
        }
    settledInWindow?.let { return it }
    val submitFlow = checkNotNull(createdFlow) { "the create window returned neither a flow nor a settlement" }

    // AMBIGUOUS from here on. The network send happens inside this collect, and SDK 3.0.0 emits each
    // result only AFTER `client.dispose()` and the encrypted-prefs write of the submit endpoint, both
    // in unguarded `finally` blocks that run after lightwalletd has accepted the transaction. So a throw
    // here — with or without anything emitted — cannot mean "nothing was sent". Collected one output at
    // a time rather than with toList(), so the ids already in hand survive the throw.
    // Trade-off: a wallet-client/Tor bootstrap failure before the send also lands here and reads as
    // "sent, not confirmed" instead of "Send Failed" — a message regression, never a double payment.
    val submitResults = mutableListOf<TransactionSubmitResult>()
    try {
        submitFlow.collect { submitResults += it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Twig.error(e) { "Submit flow threw while collecting after ${submitResults.size} result(s)" }
        return SubmitResult.SentUnconfirmed(
            txIds = submitResults.map { it.txIdString() },
            description = e.message,
        )
    }
    Twig.debug { "Internal transaction submit results: $submitResults" }

    val successCount =
        submitResults
            .count { it is TransactionSubmitResult.Success }
    val txIds =
        submitResults
            .map { it.txIdString() }
    val statuses =
        submitResults
            .map {
                when (it) {
                    is TransactionSubmitResult.Success -> "success"
                    is TransactionSubmitResult.Failure ->
                        if (it.grpcError) {
                            it.description.orEmpty()
                        } else {
                            "code: ${it.code} desc: ${it.description}"
                        }

                    is TransactionSubmitResult.NotAttempted -> "notAttempted"
                }
            }
    // A step that was never attempted is NOT resubmittable (round 8 D3, sweep #27): it never got a submit endpoint,
    // so it stays AwaitingPlan, and the SDK's resubmission on sync skips AwaitingPlan transactions. It is a Failure
    // that keeps every stored id. F2: the SDK's own create-and-submit flow stopped after the first failed step and
    // left every later one unattempted (a TEX leg 2 was never sent). ZChat's sends now go through
    // [createStoreAndSubmit], which submits every leg, so a step is left unattempted only by the debug fault seam
    // KEEP_LEG2_AWAITING_PLAN.
    val resubmittableFailures =
        submitResults
            .mapNotNull {
                when (it) {
                    is TransactionSubmitResult.Failure -> it.grpcError
                    is TransactionSubmitResult.NotAttempted -> false
                    is TransactionSubmitResult.Success -> null
                }
            }

    val (errCode, errDesc) =
        submitResults
            .filterIsInstance<TransactionSubmitResult.Failure>()
            .lastOrNull { !it.grpcError }
            ?.let { it.code to it.description } ?: (0 to "")

    val result =
        SubmitResult.classify(
            successCount = successCount,
            totalCount = txIds.size,
            resubmittableFailures = resubmittableFailures,
            txIds = txIds,
            statuses = statuses,
            errCode = errCode,
            errDesc = errDesc,
        )
    return result
}

/**
 * F2 (fix-all 2.13.0): the one submit path. Every send (a wallet send, a chat send, a Keystone-signed PCZT) is
 * created, recorded and submitted here, through the SDK's public [Broadcaster]:
 *
 *  1. [create] builds, proves and stores every transaction of the send (SDK 3.0.0 keeps each one AwaitingPlan, so
 *     the sync loop does not rebroadcast it yet). It runs inside [settleSubmitFlow]'s create window, so a create
 *     that throws is still settled from the wallet's own list, exactly as before;
 *  2. [onStored] is handed every id and expiry height. If it throws, NOTHING is submitted; the send is
 *     [SubmitResult.SentUnconfirmed] with every id, never a Failure, because the stored transactions stay in the
 *     wallet and must not be paid a second time;
 *  3. EVERY leg is submitted to [endpoint], in order, even after an earlier leg failed (Zodl's
 *     MultiEndpointTransactionSubmitter does the same). Only a submitted leg gets a submit endpoint, which is what
 *     the SDK needs to keep rebroadcasting it until it is mined or expires; a leg left unattempted was stranded
 *     (TEX leg 2 never sent).
 *
 * How the legs' answers are read:
 *  - a submit that THROWS may still have reached the server: the leg reads as a gRPC failure (code
 *    [SUBMIT_THREW_CODE], as in Zodl) so the next legs are still submitted, and the whole send is
 *    [SubmitResult.SentUnconfirmed] with every id: the same "maybe sent, never retried" class as a throw while the
 *    SDK's flow was collected;
 *  - a leg refused by the server after an EARLIER leg was not accepted is counted as not delivered yet (a gRPC-class
 *    failure): leg 2 of a TEX send spends leg 1's output, so the server refuses it while leg 1 is not in its mempool,
 *    and the SDK rebroadcasts both. A refusal of a leg whose earlier legs were all accepted, or of leg 1 itself, is
 *    read exactly as before;
 *  - a CancellationException is never swallowed: the legs after it are not submitted.
 *
 * Debug builds only ([FaultSeams]): SUBMIT_LEG1_GRPC_FAILURE, KEEP_LEG2_AWAITING_PLAN and
 * KILL_AFTER_CREATE_BEFORE_ON_STORED act here; a release build reads every one as not armed.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal suspend fun createStoreAndSubmit(
    broadcaster: Broadcaster,
    endpoint: LightWalletEndpoint,
    onStored: OnTransactionsStored?,
    ownSpends: (suspend () -> List<String>)?,
    attempts: SendAttemptRegistry,
    create: suspend (Broadcaster) -> List<CreatedTransaction>,
): SubmitResult {
    var created: List<CreatedTransaction> = emptyList()
    var recordFailure: Exception? = null
    var submitThrew: Exception? = null
    val settled =
        settleSubmitFlow(ownSpends = ownSpends, attempts = attempts) {
            created = create(broadcaster)
            // ZC-149, ZC-149-R1: an empty answer is settled exactly as a create that throws: nothing is recorded or
            // submitted, and the wallet's own spends decide (no new spend: nothing stored; a new one: maybe sent, by id).
            if (created.isEmpty()) throw NothingCreatedException()
            FaultSeams.killHereIfArmed(FaultSeam.KILL_AFTER_CREATE_BEFORE_ON_STORED)
            try {
                onStored?.invoke(created.map { StoredTransaction(it.txIdString(), it.expiryHeight?.value) })
                submitEveryLeg(broadcaster, endpoint, created) { submitThrew = it }
            } catch (e: CancellationException) {
                // F4 (T2 review G-F2-2): a cancellation out of the record step while this send is still active (a
                // timeout inside it) is a record that failed: nothing is submitted and every id is kept (below). A
                // real cancellation of the send is still rethrown.
                if (!currentCoroutineContext().isActive) throw e
                recordFailure = e
                emptyFlow()
            } catch (e: Exception) {
                recordFailure = e
                emptyFlow()
            }
        }
    val ids = created.map { it.txIdString() }
    recordFailure?.let {
        Twig.error(it) { "The send's ${ids.size} stored transaction(s) could not be recorded; none was submitted" }
        return SubmitResult.SentUnconfirmed(txIds = ids, description = "$NOT_RECORDED_DESCRIPTION ${it.message}")
    }
    submitThrew?.let {
        return SubmitResult.SentUnconfirmed(txIds = ids, description = it.message)
    }
    return settled
}

/** Submits every leg in order; [onThrow] is told about a submit that threw (its leg reads as a gRPC failure). */
private fun submitEveryLeg(
    broadcaster: Broadcaster,
    endpoint: LightWalletEndpoint,
    legs: List<CreatedTransaction>,
    onThrow: (Exception) -> Unit,
): Flow<TransactionSubmitResult> =
    flow {
        var earlierLegNotAccepted = false
        legs.forEachIndexed { index, leg ->
            val answer = submitLeg(broadcaster, endpoint, leg, index, onThrow)
            emit(if (earlierLegNotAccepted) answer.asLegAfterAnUnacceptedOne() else answer)
            if (answer !is TransactionSubmitResult.Success) earlierLegNotAccepted = true
        }
    }

@Suppress("TooGenericExceptionCaught")
private suspend fun submitLeg(
    broadcaster: Broadcaster,
    endpoint: LightWalletEndpoint,
    leg: CreatedTransaction,
    index: Int,
    onThrow: (Exception) -> Unit,
): TransactionSubmitResult {
    if (index > 0 && FaultSeams.isArmed(FaultSeam.KEEP_LEG2_AWAITING_PLAN)) {
        Twig.warn { "Debug fault seam KEEP_LEG2_AWAITING_PLAN: leg ${index + 1} is not submitted" }
        return TransactionSubmitResult.NotAttempted(leg.txId)
    }
    return try {
        if (index == 0 && FaultSeams.isArmed(FaultSeam.SUBMIT_LEG1_GRPC_FAILURE)) {
            submitUnreadableCopy(broadcaster, endpoint, leg)
        } else {
            broadcaster.submit(leg, endpoint)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Twig.error(e) { "The submit of leg ${index + 1} threw; it may still have reached the server" }
        onThrow(e)
        TransactionSubmitResult.Failure(leg.txId, true, SUBMIT_THREW_CODE, e.message, false)
    }
}

/**
 * Debug fault seam SUBMIT_LEG1_GRPC_FAILURE: the server is handed a cut copy of leg 1 that it cannot parse, so the leg
 * does not reach the network now, while the SDK still records its submit endpoint (by txid) and rebroadcasts the
 * real, stored bytes on its next sync. The attempt reads the leg as a gRPC failure.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun submitUnreadableCopy(
    broadcaster: Broadcaster,
    endpoint: LightWalletEndpoint,
    leg: CreatedTransaction,
): TransactionSubmitResult {
    val bytes = leg.raw.byteArray
    val cut = CreatedTransaction(leg.txId, FirstClassByteArray(bytes.copyOf(bytes.size / 2)), leg.expiryHeight)
    try {
        broadcaster.submit(cut, endpoint)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Twig.warn { "Debug fault seam SUBMIT_LEG1_GRPC_FAILURE: the cut copy's submit threw ${e.javaClass.simpleName}" }
    }
    Twig.warn { "Debug fault seam SUBMIT_LEG1_GRPC_FAILURE: leg 1's first submit reads as a gRPC failure" }
    return TransactionSubmitResult.Failure(leg.txId, true, SUBMIT_THREW_CODE, FAULT_SEAM_DESCRIPTION, false)
}

/** A refusal of a leg that came after an unaccepted one is counted as not delivered yet (see [createStoreAndSubmit]). */
private fun TransactionSubmitResult.asLegAfterAnUnacceptedOne(): TransactionSubmitResult =
    if (this is TransactionSubmitResult.Failure && !grpcError) {
        TransactionSubmitResult.Failure(txId, true, code, "$AFTER_UNACCEPTED_DESCRIPTION $description", isTorFailure)
    } else {
        this
    }

/** The code a submit that threw is read with (Zodl's MULTI_SUBMIT_GRPC_FAILURE_CODE). */
private const val SUBMIT_THREW_CODE = -1

/** ZC-149: the create step returned no transaction; settled like any create that failed ([settleCreateFailure]). */
private class NothingCreatedException : IllegalStateException("the create step returned no transaction")

private const val NOT_RECORDED_DESCRIPTION = "Stored but not submitted: the send could not be recorded."

private const val AFTER_UNACCEPTED_DESCRIPTION = "Refused while an earlier step was not accepted:"

private const val FAULT_SEAM_DESCRIPTION = "debug fault seam SUBMIT_LEG1_GRPC_FAILURE"

@Suppress("TooManyFunctions")
class ProposalDataSourceImpl(
    private val synchronizerProvider: SynchronizerProvider,
    // F2: the wallet's own server, which every leg is submitted to. Null only in JVM tests that never submit.
    private val persistableWalletProvider: PersistableWalletProvider? = null,
) : ProposalDataSource {
    /**
     * Reads a ZIP 321 URI into its payments; null when it is not a payment request. A seam for the unit
     * tests only: org.zecdev.zip321 is Java 21 bytecode and cannot load on the Java 17 test JVM.
     */
    internal var readZip321: (String) -> List<Zip321Payment>? = ::readZip321Payments

    override suspend fun createProposal(
        account: WalletAccount,
        send: ZecSend
    ): RegularTransactionProposal =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            // Sealed Orchard (ZIP 258): a peer-addressed spend cannot be funded from Orchard alone,
            // and letting the SDK try would either fail or cross the turnstile with a public amount.
            orchardSealedRefusal(account.unified)?.let { throw it }
            getOrThrow {
                RegularTransactionProposal(
                    destination = send.destination,
                    amount = send.amount,
                    memo = send.memo,
                    proposal =
                        synchronizerProvider
                            .getSynchronizer()
                            .proposeSend(
                                account = account.sdkAccount,
                                send = send
                            ).refusePublicCrossing(account),
                )
            }
        }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            // Sealed Orchard (ZIP 258): a peer-addressed spend cannot be funded from Orchard alone,
            // and letting the SDK try would either fail or cross the turnstile with a public amount.
            orchardSealedRefusal(account.unified)?.let { throw it }
            getOrThrow {
                val synchronizer = synchronizerProvider.getSynchronizer()

                val payments =
                    getOrThrow {
                        readZip321(zip321Uri)
                            ?: throw TransactionProposalNotCreatedException(
                                IllegalArgumentException("Invalid ZIP321 URI"),
                            )
                    }
                // Reject multi-payment ONLY for untrusted (scanned) URIs — a scanned ZIP321 with
                // hidden extra outputs could pay recipients the review screen doesn't show. The chat
                // memo path generates its OWN trusted URI that ALWAYS carries a minimal platform-fee
                // output (>=2 payments by design), so it passes allowMultiPayment=true; the proposal
                // below (proposeFulfillingPaymentUri) settles every payment in the URI. Without this,
                // every on-chain chat memo (KEX/KEXACK/ZBOOT/group/paid) fails to propose.
                if (!allowMultiPayment && payments.size != 1) {
                    throw TransactionProposalNotCreatedException(
                        IllegalArgumentException("Multi-payment ZIP321 URI not supported"),
                    )
                }
                val payment = payments.first()

                Zip321TransactionProposal(
                    destination =
                        synchronizer
                            .validateAddress(payment.recipient)
                            .toWalletAddress(payment.recipient),
                    amount = payment.amount,
                    memo = Memo(payment.memo),
                    proposal =
                        synchronizer
                            .proposeFulfillingPaymentUri(account = account.sdkAccount, uri = zip321Uri)
                            .refusePublicCrossing(account)
                            .also { if (!allowMultiPayment) it.refuseUnshownPayments(payment.amount, payment.recipient) },
                )
            }
        }

    override suspend fun createExactInputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote,
    ): ExactInputSwapTransactionProposal =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            // Sealed Orchard (ZIP 258): a peer-addressed spend cannot be funded from Orchard alone,
            // and letting the SDK try would either fail or cross the turnstile with a public amount.
            orchardSealedRefusal(account.unified)?.let { throw it }
            getOrThrow {
                ExactInputSwapTransactionProposal(
                    destination = send.destination,
                    amount = send.amount,
                    memo = send.memo,
                    proposal =
                        synchronizerProvider
                            .getSynchronizer()
                            .proposeSend(account.sdkAccount, send)
                            .refusePublicCrossing(account),
                    quote = quote,
                )
            }
        }

    override suspend fun createExactOutputProposal(
        account: WalletAccount,
        send: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            // Sealed Orchard (ZIP 258): a peer-addressed spend cannot be funded from Orchard alone,
            // and letting the SDK try would either fail or cross the turnstile with a public amount.
            orchardSealedRefusal(account.unified)?.let { throw it }
            getOrThrow {
                ExactOutputSwapTransactionProposal(
                    destination = send.destination,
                    amount = send.amount,
                    memo = send.memo,
                    proposal =
                        synchronizerProvider
                            .getSynchronizer()
                            .proposeSend(account.sdkAccount, send)
                            .refusePublicCrossing(account),
                    quote = quote,
                )
            }
        }

    override suspend fun createShieldProposal(account: WalletAccount): ShieldTransactionProposal =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            getOrThrow {
                val newProposal =
                    synchronizerProvider.getSynchronizer().proposeShielding(
                        account = account.sdkAccount,
                        shieldingThreshold = Zatoshi(DEFAULT_SHIELDING_THRESHOLD),
                        // Using empty string for memo to clear the default memo prefix value defined in the SDK
                        memo = "",
                        // Using null will select whichever of the account's trans. receivers has funds to shield
                        transparentReceiver = null
                    )

                newProposal
                    ?.let { ShieldTransactionProposal(proposal = it) }
                    ?: throw NullPointerException("transparent balance  is zero or below `shieldingThreshold`")
            }
        }

    // The sweep funnel skips the peer pool gate, as createShieldProposal does, and inverts its rule: it
    // REQUIRES Orchard-only inputs and a single Ironwood payment to the wallet itself.
    override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            when (val decision = decideOrchardSweep(account)) {
                is OrchardSweepDecision.Available -> Unit
                is OrchardSweepDecision.TooSmall -> throw OrchardSweepTooSmallException(decision.orchard, fee = null)
                else -> throw TransactionProposalNotCreatedException(OrchardSweepRefusedException(decision.toString()))
            }
            val proposal =
                getOrThrow {
                    synchronizerProvider.getSynchronizer().proposeOrchardToIronwoodMigration(account.sdkAccount)
                }
            // Validated outside getOrThrow, so a too-small refusal keeps its own type.
            OrchardSweepTransactionProposal.fromProposal(proposal).also {
                Twig.debug { "orchard_sweep_shape ${it.shape}" }
                // Never on a consistent wallet: SDK 3.0.0 leaves notes <= 5,000 zat out of BOTH the balance
                // and the sweep. A mismatch is a stale balance snapshot (a race), not stranded dust.
                if (it.amount != account.unified.balance.available) {
                    Twig.warn { "orchard_sweep inputs ${it.amount} != orchard available" }
                }
            }
        }

    override suspend fun probeFee(account: WalletAccount, send: ZecSend): FeeProbe =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            try {
                val proposal = synchronizerProvider.getSynchronizer().proposeSend(account.sdkAccount, send)
                val shape = readSweepShape(proposal.toByteArray())
                FeeProbe.Built(
                    fee = proposal.totalFeeRequired(),
                    usesOrchardInputs = proposal.usesOrchardInputs(),
                    transactionCount = proposal.transactionCount(),
                    change = shape?.change,
                    // F9: Send all accepts only Ironwood inputs; null (unreadable) is never accepted.
                    inputPools = shape?.inputPools,
                )
            } catch (e: TransactionEncoderException.ProposalFromParametersException) {
                // T1b-3a (F9 diagnostic): the SDK's own reason, amounts only; debug builds only.
                Twig.debug { "send_all_probe refused: ${sdkRefusalLogText(e)}" }
                if (e.rootCause.isShortfall()) FeeProbe.Short else throw TransactionProposalNotCreatedException(e)
            }
        }

    override suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            try {
                val proposal =
                    synchronizerProvider
                        .getSynchronizer()
                        .proposeFulfillingPaymentUri(account = account.sdkAccount, uri = zip321Uri)
                val shape = readSweepShape(proposal.toByteArray())
                FeeProbe.Built(
                    fee = proposal.totalFeeRequired(),
                    usesOrchardInputs = proposal.usesOrchardInputs(),
                    transactionCount = proposal.transactionCount(),
                    // C-8: a Send All that keeps the handshake reserve must leave it as one note.
                    change = shape?.change,
                    // F9: Send all accepts only Ironwood inputs; null (unreadable) is never accepted.
                    inputPools = shape?.inputPools,
                )
            } catch (e: TransactionEncoderException.ProposalFromUriException) {
                // T1b-3a (F9 diagnostic): the SDK's own reason, amounts only; debug builds only.
                Twig.debug { "send_all_probe refused: ${sdkRefusalLogText(e)}" }
                if (e.rootCause.isShortfall()) FeeProbe.Short else throw TransactionProposalNotCreatedException(e)
            }
        }

    override suspend fun probeZip321Plan(account: WalletAccount, zip321Uri: String): PlanProbe =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw it }
            try {
                val proposal =
                    synchronizerProvider
                        .getSynchronizer()
                        .proposeFulfillingPaymentUri(account = account.sdkAccount, uri = zip321Uri)
                val bytes = proposal.toByteArray()
                PlanProbe.Built(
                    fee = proposal.totalFeeRequired(),
                    transactionCount = proposal.transactionCount(),
                    usesOrchardInputs = proposal.usesOrchardInputs(),
                    // Null (unreadable) is never read as "spends nothing": the plan check refuses on it.
                    inputs = readProposalInputs(bytes)?.inputs,
                    // Only Ironwood change can pay the next message; other change is left out (never counted).
                    change =
                        readSweepShape(bytes)
                            ?.change
                            ?.filter { it.pool == PROPOSAL_POOL_IRONWOOD }
                            ?.map { it.value },
                )
            } catch (e: TransactionEncoderException.ProposalFromUriException) {
                Twig.debug { "first_message_plan_probe refused: ${sdkRefusalLogText(e)}" }
                if (e.rootCause.isShortfall()) {
                    PlanProbe.Short(need = e.rootCause.toShortfallOrNull()?.need)
                } else {
                    throw TransactionProposalNotCreatedException(e)
                }
            }
        }

    override suspend fun createPcztFromProposal(account: WalletAccount, proposal: Proposal): Pczt =
        withContext(Dispatchers.IO) {
            val synchronizer = synchronizerProvider.getSynchronizer()
            synchronizer.createPcztFromProposal(
                accountUuid = account.sdkAccount.accountUuid,
                proposal = proposal
            )
        }

    override suspend fun addProofsToPczt(pczt: Pczt): Pczt =
        withContext(Dispatchers.IO) {
            synchronizerProvider
                .getSynchronizer()
                .addProofsToPczt(pczt)
        }

    override suspend fun submitTransaction(pcztWithProofs: Pczt, pcztWithSignatures: Pczt): SubmitResult =
        submitTransactionInternal(onStored = null) {
            it.createTransactionFromPczt(
                pcztWithProofs = pcztWithProofs,
                pcztWithSignatures = pcztWithSignatures,
            )
        }

    override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult =
        submitProposal(proposal, usk, onStored = null)

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: OnTransactionsStored,
    ): SubmitResult = submitProposal(proposal, usk, onStored)

    private suspend fun submitProposal(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: OnTransactionsStored?,
    ): SubmitResult =
        submitTransactionInternal(onStored) {
            it.createProposedTransactions(
                proposal = proposal,
                usk = usk,
            )
        }

    override suspend fun redactPcztForSigner(pczt: Pczt): Pczt =
        withContext(Dispatchers.IO) {
            synchronizerProvider
                .getSynchronizer()
                .redactPcztForSigner(pczt)
        }

    // F3 (fix-all 2.13.0): the whole create-record-submit is counted as a send in progress (WalletActivity), so the
    // automatic TEX recovery neither turns Tor on (a wallet restart) nor shields while a send is under way.
    private suspend fun submitTransactionInternal(
        onStored: OnTransactionsStored?,
        create: suspend (Broadcaster) -> List<CreatedTransaction>,
    ): SubmitResult =
        WalletActivity.track(WalletActivity.Kind.SEND) {
            // RESET-PAY: counted first, then the gate: once a Reset ZCHAT has begun no new payment is created.
            if (WalletActivity.closing) throw NothingBroadcastException(IllegalStateException("a reset of ZCHAT has begun"))
            submitTransactionCounted(onStored, create)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitTransactionCounted(
        onStored: OnTransactionsStored?,
        create: suspend (Broadcaster) -> List<CreatedTransaction>,
    ): SubmitResult =
        withContext(Dispatchers.IO) {
            NetworkUpgradeGuard.refusal()?.let { throw NothingBroadcastException(it) }
            // PRE-BROADCAST (1/2): getting the synchronizer, and the cast to the concrete SDK type.
            // Nothing has been handed to the network yet, so a throw here is a FACT ("nothing was
            // sent"), not the post-broadcast ambiguity. Marked so the repositories keep reporting it
            // as a failure instead of "sent — not confirmed".
            val synchronizer =
                try {
                    synchronizerProvider.getSynchronizer() as SdkSynchronizer
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw NothingBroadcastException(e)
                }

            // PRE-BROADCAST (2/2): the server every leg is submitted to. It is the wallet's own, read (and healed off
            // a retired host) exactly as the synchronizer was built from it, so it is the endpoint the SDK's own
            // create-and-submit used. Nothing has been created yet, so a failure here is still "nothing was sent".
            val endpoint =
                try {
                    checkNotNull(persistableWalletProvider) { "no wallet to read the server from" }
                        .requirePersistableWallet()
                        .endpoint
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw NothingBroadcastException(e)
                }

            val result =
                createStoreAndSubmit(
                    broadcaster = synchronizer.broadcaster,
                    endpoint = endpoint,
                    onStored = onStored,
                    ownSpends = { synchronizer.ownSpendIds() },
                    attempts = globalSendAttempts,
                    create = create,
                )
            NetworkUpgradeGuard.onSubmitResult(result)

            // MONEY-CRITICAL: the broadcast already happened above and `result` is the authoritative
            // answer. These refreshes are bookkeeping — a derived-data DB lock/IO error, a closed
            // wallet or a JNI error out of refreshWalletSummary must NOT discard `result` and leave
            // this function by the exception path: one layer up that is indistinguishable from a
            // transaction that never left the wallet, so the user is told "Send Failed — try again"
            // over money that is already gone, and the automatic chat retries re-send and re-pay.
            try {
                synchronizer.refreshTransactions()
                synchronizer.refreshAllBalances()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error(e) { "Post-broadcast refresh failed; the submit result stands" }
            }
            Twig.debug { "Transaction submit result: $result" }
            result
        }

    /**
     * Refuse to settle a proposal the SDK funded from the sealed Orchard pool: that crossing's amount is
     * public (ZIP 318). Checked on the BUILT proposal because no balance arithmetic can predict the
     * SDK's pool choice. Shielding is not routed through here — it is the way out of the sealed pool.
     */
    private fun Proposal.refusePublicCrossing(account: WalletAccount): Proposal {
        publicCrossingRefusal(usesOrchardInputs(), account.unified)?.let { throw it }
        // R2-2 (c): a Sapling-funded payment into another pool publishes its amount too. R3-1: a proposal
        // the wire reader cannot read may be such a payment, so it is refused (fail closed) rather than
        // carrying a Sapling claim nobody could check. The reason it is refused with is one function
        // (unreadableProposalRefusal) so the words the user reads are decided in one place.
        val shape =
            readSweepShape(toByteArray())
                ?: throw TransactionProposalNotCreatedException(unreadableProposalRefusal())
        saplingCrossingRefusal(shape, account.sapling?.balance?.available ?: Zatoshi(0))?.let { throw it }
        return this
    }

    /**
     * A scanned request must pay exactly the one payment the review screen shows (R2-1 b), to the
     * recipient it shows (R3-1). The SDK re-parses the URI with its own ZIP 321 parser; its proposal's
     * transaction requests are what will be paid, so they are compared with [shown] and [shownRecipient]
     * before anything can be signed.
     */
    private fun Proposal.refuseUnshownPayments(
        shown: Zatoshi,
        shownRecipient: String
    ) {
        scannedRequestMismatch(readSweepShape(toByteArray()), shown.value, shownRecipient)?.let {
            throw TransactionProposalNotCreatedException(IllegalStateException("Scanned request refused: $it"))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T : Any> getOrThrow(block: () -> T): T =
        try {
            block()
        } catch (e: TransactionEncoderException.ProposalFromParametersException) {
            throw e.rootCause.toShortfallOrNull() ?: TransactionProposalNotCreatedException(e)
        } catch (e: TransactionEncoderException.ProposalFromUriException) {
            // The ZIP 321 entry point (a scanned request, every chat send) wraps the SAME shortfall text
            // in this type; without it a plain shortfall read as "couldn't be prepared, try again".
            throw e.rootCause.toShortfallOrNull() ?: TransactionProposalNotCreatedException(e)
        } catch (e: InsufficientFundsException) {
            throw e
        } catch (e: PublicCrossingException) {
            // Typed money/privacy refusal — rethrow AS-IS so the presentation layer can name the reason
            // instead of showing the generic "couldn't be prepared" copy.
            throw e
        } catch (e: SaplingCrossingException) {
            throw e
        } catch (e: UnreadableProposalException) {
            // R4-6 (11): typed fail-closed refusal with its own user sentence; rethrow AS-IS so it is not
            // double-wrapped into the generic "couldn't be prepared" copy.
            throw e
        } catch (e: TransactionProposalNotCreatedException) {
            // Already the typed, presentation-mappable exception — rethrow AS-IS. Without this,
            // nested getOrThrow calls (createZip321Proposal wraps an inner getOrThrow) and the
            // explicit `throw TransactionProposalNotCreatedException(…)` sites below would fall into
            // the generic catch and get DOUBLE-wrapped, stringifying the cause into the outer
            // message (e.g. "…TransactionProposalNotCreatedException: AmountTooSmall(value=0)").
            throw e
        } catch (e: Exception) {
            throw TransactionProposalNotCreatedException(e)
        }

    private suspend fun AddressType.toWalletAddress(value: String) =
        when (this) {
            AddressType.Unified -> WalletAddress.Unified.new(value)
            AddressType.Shielded -> WalletAddress.Sapling.new(value)
            AddressType.Transparent -> WalletAddress.Transparent.new(value)
            else -> error("Invalid address type")
        }
}

/** One payment of a ZIP 321 request: what the review screen shows for it. */
internal data class Zip321Payment(
    val recipient: String,
    val amount: Zatoshi,
    val memo: String,
)

/** The payments of [uri] as org.zecdev.zip321 reads them, or null when it is not a payment request. */
private fun readZip321Payments(uri: String): List<Zip321Payment>? =
    when (val request = ZIP321.request(uriString = uri, validatingRecipients = null)) {
        is ZIP321.ParserResult.Request ->
            request.paymentRequest.payments.map { payment ->
                Zip321Payment(
                    recipient = payment.recipientAddress.value,
                    amount = payment.nonNegativeAmount.value.convertZecToZatoshi(),
                    memo = payment.memo?.data?.decodeToString() ?: "",
                )
            }

        else -> null
    }

sealed interface TransactionProposal {
    val proposal: Proposal
}

sealed interface SendTransactionProposal : TransactionProposal {
    val destination: WalletAddress
    val amount: Zatoshi
    val memo: Memo
}

sealed interface SwapTransactionProposal : SendTransactionProposal {
    val quote: SwapQuote

    val totalFees: Zatoshi
        get() = quote.getTotalFeesZatoshi(proposal)

    val totalFeesUsd: BigDecimal
        get() = quote.getTotalFeesUsd(proposal)
}

data class ShieldTransactionProposal(
    override val proposal: Proposal,
) : TransactionProposal

/**
 * The Orchard sweep the SDK built, checked (sweep spec §2.1). Built only through [fromProposal], which
 * reads the proposal itself and refuses anything but the sweep, so an instance is always a sweep. A plain
 * class, not a data class, so there is no public `copy`.
 *
 * [amount] is the sum of the inputs: the figure that becomes public. [fee] is the proposal's fee, and
 * [arrives] what lands in Ironwood.
 */
class OrchardSweepTransactionProposal private constructor(
    val amount: Zatoshi,
    internal val shape: SweepShape,
    override val proposal: Proposal,
) : TransactionProposal {
    val fee: Zatoshi
        get() = Zatoshi(shape.fee)

    val arrives: Zatoshi
        get() = amount - fee

    companion object {
        /** Throws the refusal of [orchardSweepShapeRefusal] for anything but the sweep. */
        fun fromProposal(proposal: Proposal): OrchardSweepTransactionProposal {
            val shape = readSweepShape(proposal.toByteArray())
            orchardSweepShapeRefusal(
                shape = shape,
                usesOrchardInputs = proposal.usesOrchardInputs(),
                transactionCount = proposal.transactionCount(),
                totalFeeRequired = proposal.totalFeeRequired(),
            )?.let { throw it }
            return OrchardSweepTransactionProposal(Zatoshi(checkNotNull(shape).inputTotal), shape, proposal)
        }
    }
}

data class RegularTransactionProposal(
    override val destination: WalletAddress,
    override val amount: Zatoshi,
    override val memo: Memo,
    override val proposal: Proposal
) : SendTransactionProposal

data class Zip321TransactionProposal(
    override val destination: WalletAddress,
    override val amount: Zatoshi,
    override val memo: Memo,
    override val proposal: Proposal
) : SendTransactionProposal

data class ExactInputSwapTransactionProposal(
    override val destination: WalletAddress,
    override val amount: Zatoshi,
    override val memo: Memo,
    override val proposal: Proposal,
    override val quote: SwapQuote,
) : SwapTransactionProposal

data class ExactOutputSwapTransactionProposal(
    override val destination: WalletAddress,
    override val amount: Zatoshi,
    override val memo: Memo,
    override val proposal: Proposal,
    override val quote: SwapQuote,
) : SwapTransactionProposal

private const val DEFAULT_SHIELDING_THRESHOLD = 100000L

/** The rust backend's shortfall figures: "Insufficient balance (have 582002, need 587002 including fee)". */
private val SHORTFALL_FIGURES = Regex("""have (\d+), need (\d+)""")

private fun Throwable.isShortfall(): Boolean {
    val message = message.orEmpty()
    return message.contains("Insufficient balance", true) ||
        message.contains("The transaction requires an additional change output of", true)
}

/** The typed shortfall for an SDK refusal, with its have/need figures when it gave them; else null. */
private fun Throwable.toShortfallOrNull(): InsufficientFundsException? {
    if (!isShortfall()) return null
    val figures = SHORTFALL_FIGURES.find(message.orEmpty())?.groupValues
    return InsufficientFundsException(
        have = figures?.get(1)?.toLongOrNull()?.let(::Zatoshi),
        need = figures?.get(2)?.toLongOrNull()?.let(::Zatoshi),
    )
}
