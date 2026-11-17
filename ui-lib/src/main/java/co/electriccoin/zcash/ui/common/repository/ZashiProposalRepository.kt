package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.NothingBroadcastException
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.OrchardSweepPendingException
import co.electriccoin.zcash.ui.common.model.PlanProbe
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.orchardSweepShapeRefusal
import co.electriccoin.zcash.ui.common.model.readSweepShape
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.util.PaymentDiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

interface ZashiProposalRepository {
    val transactionProposal: StateFlow<TransactionProposal?>

    val submitState: StateFlow<SubmitProposalState?>

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createProposal(zecSend: ZecSend): RegularTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createZip321Proposal(zip321Uri: String, allowMultiPayment: Boolean = false): Zip321TransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactInputSwapProposal(zecSend: ZecSend, quote: SwapQuote): ExactInputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactOutputSwapProposal(zecSend: ZecSend, quote: SwapQuote): ExactOutputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createShieldProposal(): ShieldTransactionProposal

    /**
     * ZC-101: submits the [proposal] PASSED BY THE CALLER — the one created for this send — never
     * the shared [transactionProposal] cell, which a concurrent proposal creation (e.g. an inbound
     * dust auto-ack during the biometric window) can overwrite between create and submit.
     *
     * MONEY-CRITICAL: never reports a broadcast transaction as failed. An exception out of the data
     * source is AMBIGUOUS — the broadcast happens first and the balance refresh runs after it — so
     * it becomes [SubmitResult.SentUnconfirmed], never [SubmitResult.Failure]. Only the SDK's own
     * verdict ("nothing was submitted") is a failure.
     */
    suspend fun submit(proposal: TransactionProposal): SubmitResult

    /**
     * F2: [submit] that hands every stored transaction's id and expiry height to [onStored] before the first one is
     * submitted; if [onStored] throws, nothing is submitted (see [OnTransactionsStored]). The default, for a
     * repository that cannot report them, is the plain [submit].
     */
    suspend fun submit(
        proposal: TransactionProposal,
        onStored: OnTransactionsStored,
    ): SubmitResult = submit(proposal)

    suspend fun getTransactionProposal(): TransactionProposal

    /**
     * Price the ZIP 321 request [zip321Uri] for [account] without keeping it: nothing is stored in
     * [transactionProposal] and nothing can be submitted. See [ProposalDataSource.probeZip321Fee].
     */
    suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe

    /**
     * M2 (fix-all 2.13.0) — [probeZip321Fee] with the notes the proposal would spend and its Ironwood change; nothing
     * is stored or submitted. See [ProposalDataSource.probeZip321Plan]. The default throws (fail closed).
     */
    suspend fun probeZip321Plan(account: WalletAccount, zip321Uri: String): PlanProbe =
        throw UnsupportedOperationException("This proposal repository cannot probe a send plan")

    /**
     * Build the move of the selected account's sealed Orchard balance to its own Ironwood (sweep spec
     * §2.4). Never stored in [transactionProposal]: the caller keeps the instance it shows. Throws
     * [TransactionProposalNotCreatedException] with an [OrchardSweepPendingException] cause while a sweep
     * is being submitted.
     */
    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createOrchardSweepProposal(): OrchardSweepTransactionProposal

    /**
     * Submit exactly [proposal], the sweep the user confirmed. At most one sweep is in flight: a second
     * call returns [SubmitResult.Failure] without reaching the SDK. The proposal is checked again before it
     * is signed. Never touches [transactionProposal] or [submitState]. Settled like [submit]: a
     * [NothingBroadcastException] (the key could not be derived, or the SDK's create/prove/store step threw
     * before anything reached the network) is a [SubmitResult.Failure] that names no transaction; any other
     * exception may follow the broadcast and becomes [SubmitResult.SentUnconfirmed]. The latch and
     * shape refusals are Failures too: they never reach the SDK.
     */
    suspend fun submitOrchardSweep(proposal: OrchardSweepTransactionProposal): SubmitResult

    fun clear()
}

@Suppress("TooManyFunctions")
class ZashiProposalRepositoryImpl(
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
) : ZashiProposalRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val transactionProposal = MutableStateFlow<TransactionProposal?>(null)

    override val submitState = MutableStateFlow<SubmitProposalState?>(null)

    /** Set while an Orchard sweep is being submitted, released once the SDK call has returned. */
    private val sweepInFlight = AtomicBoolean(false)

    override suspend fun createProposal(zecSend: ZecSend): RegularTransactionProposal =
        createProposalInternal(source = "createProposal") {
            proposalDataSource.createProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend
            )
        }

    override suspend fun createZip321Proposal(zip321Uri: String, allowMultiPayment: Boolean): Zip321TransactionProposal =
        createProposalInternal(source = "createZip321Proposal") {
            proposalDataSource.createZip321Proposal(
                account = accountDataSource.getSelectedAccount(),
                zip321Uri = zip321Uri,
                allowMultiPayment = allowMultiPayment,
            )
        }

    override suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactInputSwapTransactionProposal =
        createProposalInternal(source = "createExactInputSwapProposal") {
            proposalDataSource.createExactInputProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend,
                quote = quote
            )
        }

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal =
        createProposalInternal(source = "createExactOutputSwapProposal") {
            proposalDataSource.createExactOutputProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend,
                quote = quote
            )
        }

    override suspend fun createShieldProposal(): ShieldTransactionProposal =
        createProposalInternal(source = "createShieldProposal") {
            proposalDataSource.createShieldProposal(
                account = accountDataSource.getSelectedAccount(),
            )
        }

    override suspend fun probeZip321Fee(account: WalletAccount, zip321Uri: String): FeeProbe =
        proposalDataSource.probeZip321Fee(account = account, zip321Uri = zip321Uri)

    override suspend fun probeZip321Plan(account: WalletAccount, zip321Uri: String): PlanProbe =
        proposalDataSource.probeZip321Plan(account = account, zip321Uri = zip321Uri)

    override suspend fun createOrchardSweepProposal(): OrchardSweepTransactionProposal =
        scope
            .async {
                if (sweepInFlight.get()) {
                    throw TransactionProposalNotCreatedException(OrchardSweepPendingException())
                }
                val proposal =
                    proposalDataSource.createOrchardSweepProposal(account = accountDataSource.getSelectedAccount())
                PaymentDiag.logProposalCreated(PaymentDiag.register(proposal), proposal)
                proposal
            }.await()

    @Suppress("TooGenericExceptionCaught")
    override suspend fun submitOrchardSweep(proposal: OrchardSweepTransactionProposal): SubmitResult =
        scope
            .async {
                if (!sweepInFlight.compareAndSet(false, true)) {
                    return@async SubmitResult.Failure(txIds = emptyList(), code = 0, description = SWEEP_IN_FLIGHT)
                }
                try {
                    val sdkProposal = proposal.proposal
                    val refusal =
                        orchardSweepShapeRefusal(
                            shape = readSweepShape(sdkProposal.toByteArray()),
                            usesOrchardInputs = sdkProposal.usesOrchardInputs(),
                            transactionCount = sdkProposal.transactionCount(),
                            totalFeeRequired = sdkProposal.totalFeeRequired(),
                        )
                    val result =
                        if (refusal != null) {
                            SubmitResult.Failure(txIds = emptyList(), code = 0, description = refusal.message)
                        } else {
                            proposalDataSource.submitTransaction(
                                proposal = sdkProposal,
                                usk = spendingKeyOrNothingBroadcast()
                            )
                        }
                    PaymentDiag.logProposalSubmitted(proposal, result)
                    result
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: NothingBroadcastException) {
                    // NOT ambiguous (same split as submit): nothing reached the network, so the sheet may
                    // say "nothing was sent" and let the user try again now.
                    val result = SubmitResult.Failure(txIds = emptyList(), code = 0, description = e.message)
                    PaymentDiag.logProposalSubmitted(proposal, result)
                    result
                } catch (e: Exception) {
                    // Ambiguous: this may follow the broadcast, so it must never read as "nothing was sent".
                    val result = SubmitResult.SentUnconfirmed(txIds = emptyList(), description = e.message)
                    PaymentDiag.logProposalSubmitted(proposal, result)
                    result
                } finally {
                    sweepInFlight.set(false)
                }
            }.await()

    // ZC-101: settle the proposal the caller created for THIS send. The shared `transactionProposal`
    // cell is observation-only state — a concurrent createProposalInternal (inbound dust auto-send
    // during a biometric await) may have overwritten it, and reading it here would submit the WRONG
    // proposal while reporting success for the user's real payment.
    override suspend fun submit(proposal: TransactionProposal): SubmitResult = submitInternal(proposal, onStored = null)

    override suspend fun submit(
        proposal: TransactionProposal,
        onStored: OnTransactionsStored,
    ): SubmitResult = submitInternal(proposal, onStored)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitInternal(
        proposal: TransactionProposal,
        onStored: OnTransactionsStored?,
    ): SubmitResult =
        scope
            .async {
                submitState.update { SubmitProposalState.Submitting }
                try {
                    val usk = spendingKeyOrNothingBroadcast()
                    // F2: a caller without a record callback keeps the two-argument call it always made.
                    val result =
                        if (onStored == null) {
                            proposalDataSource.submitTransaction(proposal = proposal.proposal, usk = usk)
                        } else {
                            proposalDataSource.submitTransaction(proposal = proposal.proposal, usk = usk, onStored = onStored)
                        }
                    // QA (debug-only): settlement event — pid comes from the PASSED proposal, so an
                    // on-device runner can assert submitted.pid == displayed.pid (ZC-101 oracle).
                    PaymentDiag.logProposalSubmitted(proposal, result)
                    submitState.update { SubmitProposalState.Result(result) }
                    result
                } catch (e: NothingBroadcastException) {
                    // Proven not stored: key derivation or synchronizer acquisition failed, or the SDK's
                    // create step failed and settleSubmitFlow checked (not guessed) that the wallet holds no
                    // new spend of this attempt. Nothing can go out, so this
                    // is the plain failure it always was — calling it "sent" would arm
                    // markRequestPaid and the durable KEX/ZBOOT sent-markers for a payment that never
                    // left the wallet, hiding a genuine failure and losing the message.
                    val result = SubmitResult.Failure(txIds = emptyList(), code = 0, description = e.message)
                    PaymentDiag.logProposalSubmitted(proposal, result)
                    submitState.update { SubmitProposalState.Result(result) }
                    result
                } catch (e: Exception) {
                    // MONEY-CRITICAL: what is left here cannot be told apart pre- from post-broadcast.
                    // The provably-pre-broadcast steps throw NothingBroadcastException (caught above);
                    // everything else may have reached SDK storage or submission, and the balance
                    // refresh runs after that. Publishing a Failure here
                    // showed "Send Failed / Try it again, please" (with the tx link suppressed,
                    // because it also emptied txIds) over money that had already left the wallet, and
                    // one tap on the still-filled Send form paid it twice. Report the ambiguity as
                    // sent-but-unconfirmed and RETURN it: throwing made the automatic chat senders
                    // re-arm and re-pay too.
                    val result = SubmitResult.SentUnconfirmed(txIds = emptyList(), description = e.message)
                    PaymentDiag.logProposalSubmitted(proposal, result)
                    submitState.update { SubmitProposalState.Result(result) }
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    result
                }
            }.await()

    /**
     * PRE-BROADCAST. Deriving the spending key reads the persisted wallet and runs the derivation
     * tool — it touches nothing on the network, so a throw here provably means the transaction was
     * never sent. Marked as such so [submit] reports it as a failure rather than folding it into the
     * post-broadcast ambiguity.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun spendingKeyOrNothingBroadcast(): UnifiedSpendingKey =
        try {
            zashiSpendingKeyDataSource.getZashiSpendingKey()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancellation is not a verdict about the send — it can arrive at any moment, so it
            // keeps the existing handling (published, then propagated) rather than being claimed
            // as proof that nothing was broadcast.
            throw e
        } catch (e: Exception) {
            throw NothingBroadcastException(e)
        }

    override suspend fun getTransactionProposal(): TransactionProposal = transactionProposal.filterNotNull().first()

    override fun clear() {
        transactionProposal.update { null }
        submitState.update { null }
    }

    private inline fun <T : TransactionProposal> createProposalInternal(source: String, block: () -> T): T {
        val proposal =
            try {
                block()
            } catch (e: TransactionProposalNotCreatedException) {
                Twig.error(e) { "Unable to create proposal" }
                transactionProposal.update { null }
                throw e
            } catch (e: InsufficientFundsException) {
                Twig.error(e) { "Insufficient funds" }
                transactionProposal.update { null }
                throw e
            }
        // QA (debug-only): attach the process-monotonic pid to THIS proposal instance and emit the
        // lifecycle event. getAndUpdate keeps the exact same store semantics as update {} (CAS loop
        // setting the cell) while also returning the previous value, so the ZC-101 hazard —
        // replacing a NON-NULL proposal in the shared cell — is observable as
        // proposal_cell_overwritten. No-ops entirely in release builds.
        val pid = PaymentDiag.register(proposal)
        PaymentDiag.logProposalCreated(pid, proposal)
        val previous = transactionProposal.getAndUpdate { proposal }
        if (previous != null) {
            PaymentDiag.logCellOverwritten(
                oldPid = PaymentDiag.pidOf(previous),
                newPid = pid,
                source = source
            )
        }
        return proposal
    }

    private companion object {
        const val SWEEP_IN_FLIGHT = "sweep in flight"
    }
}
