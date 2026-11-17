package co.electriccoin.zcash.ui.screen.moveoldbalance

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.model.MIN_ORCHARD_SWEEP_FEE
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.OrchardSweepDecision
import co.electriccoin.zcash.ui.common.model.OrchardSweepFailure
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.classifyOrchardSweepFailure
import co.electriccoin.zcash.ui.common.model.decideOrchardSweep
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.handshakeSpendQuote
import co.electriccoin.zcash.ui.screen.chat.model.pendingHandshakePeers
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Queued automatic handshakes the moved funds would pay for: how many, and what they cost together. */
@Immutable
data class PendingHandshakes(
    val count: Int,
    val total: Zatoshi
)

/** Why the sheet cannot offer a move. Each has its own title and body. */
sealed interface MoveOldBalanceBlock {
    /** [orchard] is too small to move after [fee]. */
    data class TooSmall(
        val orchard: Zatoshi,
        val fee: Zatoshi
    ) : MoveOldBalanceBlock

    data object WaitForSync : MoveOldBalanceBlock

    data object Pending : MoveOldBalanceBlock

    data object Keystone : MoveOldBalanceBlock

    data object NothingToMove : MoveOldBalanceBlock

    /** ZC-782: this version cannot send on the upgraded network; the sheet shows the update sentence. */
    data object UpdateRequired : MoveOldBalanceBlock

    data object Error : MoveOldBalanceBlock
}

/**
 * The sheet's states (sweep spec §1.2): Preparing, Consent, Review, Sending, then Success or Failed, or
 * a Blocked state that explains why nothing can be moved. Figures come from the proposal itself.
 */
sealed interface MoveOldBalanceState : ModalBottomSheetState {
    @Immutable
    data class Preparing(
        override val onBack: () -> Unit
    ) : MoveOldBalanceState

    /**
     * What the user agrees to. [isTorOff] adds the IP line (inform only, owner decision 1).
     *
     * There is deliberately no "left behind" figure. In SDK 3.0.0 `available` already excludes every
     * note of 5,000 zat or less (booked as uneconomic value, never exported by the JNI) and the sweep
     * takes every note above 5,000 or fails, so on a consistent wallet the sweep's inputs equal
     * `available`. A gap between them is a balance race, not stuck money, and must never be shown as a
     * permanent loss. The dust that does stay cannot be read from Kotlin, so the generic threshold line
     * is the only true disclosure.
     */
    @Immutable
    data class Consent(
        val amount: Zatoshi,
        val fee: Zatoshi,
        val arrives: Zatoshi,
        val isTorOff: Boolean,
        val handshakes: PendingHandshakes?,
        val onContinue: () -> Unit,
        val onNotNow: () -> Unit,
        override val onBack: () -> Unit,
    ) : MoveOldBalanceState

    /** [isFeeHigh]: the fee is more than a quarter of the amount moved (owner decision 5). */
    @Immutable
    data class Review(
        val amount: Zatoshi,
        val fee: Zatoshi,
        val arrives: Zatoshi,
        val isFeeHigh: Boolean,
        val onConfirm: () -> Unit,
        val onBackToConsent: () -> Unit,
        override val onBack: () -> Unit,
    ) : MoveOldBalanceState

    /** Back does nothing while the transaction is being built and broadcast. */
    @Immutable
    data class Sending(
        val amount: Zatoshi,
    ) : MoveOldBalanceState {
        override val onBack: () -> Unit = {}
    }

    @Immutable
    data class Success(
        val handshakes: PendingHandshakes?,
        val onViewTransaction: (() -> Unit)?,
        val onDone: () -> Unit,
        override val onBack: () -> Unit,
    ) : MoveOldBalanceState

    /**
     * [isNetworkFailure]: the move was created but may not have been broadcast.
     * [isOutcomeUnknown]: the submit ended in [SubmitResult.SentUnconfirmed] — an exception that can
     * come after the broadcast — so this must never say that nothing was sent. A [SubmitResult.Failure]
     * that names no transaction is a proven non-send (nothing reached the network) and is not unknown.
     */
    @Immutable
    data class Failed(
        val isNetworkFailure: Boolean,
        val isOutcomeUnknown: Boolean,
        val onViewTransaction: (() -> Unit)?,
        val onDone: () -> Unit,
        override val onBack: () -> Unit,
    ) : MoveOldBalanceState

    @Immutable
    data class Blocked(
        val reason: MoveOldBalanceBlock,
        val onOk: () -> Unit,
        override val onBack: () -> Unit,
    ) : MoveOldBalanceState
}

/**
 * The "Move old balance" sheet (sweep spec §1.2, §2.4). It keeps the very proposal it displayed and
 * settles exactly that one, runs the biometric prompt itself, and awaits its OWN result from
 * [ZashiProposalRepository.submitOrchardSweep]: it never reads or writes the shared proposal cell or
 * submit state, so a chat send or a `clear()` cannot change what it shows. Consent is asked every time
 * and never stored.
 */
@Suppress("TooManyFunctions") // one function per sheet transition
class MoveOldBalanceVM(
    private val accountDataSource: AccountDataSource,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val biometricRepository: BiometricRepository,
    private val navigationRouter: NavigationRouter,
    private val isTorEnabled: IsTorEnabledUseCase,
    private val zchatPreferences: ZchatPreferences,
) : ViewModel() {
    private val mutableState = MutableStateFlow<MoveOldBalanceState>(MoveOldBalanceState.Preparing(::onBack))

    val state: StateFlow<MoveOldBalanceState> = mutableState.asStateFlow()

    /** The confirm latch: one biometric prompt at a time... */
    private var confirmJob: Job? = null

    /** ...and one submit per sheet, ever. Both are only touched on the main thread. */
    private var hasSubmitted = false

    init {
        viewModelScope.launch { prepare() }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepare() {
        val account =
            try {
                accountDataSource.getSelectedAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                block(MoveOldBalanceBlock.Error)
                return
            }
        when (val decision = decideOrchardSweep(account)) {
            OrchardSweepDecision.None -> block(MoveOldBalanceBlock.NothingToMove)
            is OrchardSweepDecision.Keystone -> block(MoveOldBalanceBlock.Keystone)
            is OrchardSweepDecision.WaitForSync -> block(MoveOldBalanceBlock.WaitForSync)
            is OrchardSweepDecision.TooSmall ->
                block(MoveOldBalanceBlock.TooSmall(decision.orchard, Zatoshi(MIN_ORCHARD_SWEEP_FEE)))
            is OrchardSweepDecision.Available -> build(decision.orchard)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun build(orchard: Zatoshi) {
        val proposal =
            try {
                zashiProposalRepository.createOrchardSweepProposal()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                block(e.toBlock(orchard))
                return
            }
        val torOn = withTimeoutOrNull(TOR_READ_TIMEOUT_MS) { isTorEnabled.observe().first() } ?: false
        showConsent(
            proposal,
            Facts(
                isTorOff = !torOn,
                handshakes = pendingHandshakes(),
            ),
        )
    }

    /** What the consent screen discloses beyond the proposal's own figures. */
    private data class Facts(
        val isTorOff: Boolean,
        val handshakes: PendingHandshakes?,
    )

    private fun showConsent(
        proposal: OrchardSweepTransactionProposal,
        facts: Facts
    ) {
        mutableState.value =
            MoveOldBalanceState.Consent(
                amount = proposal.amount,
                fee = proposal.fee,
                arrives = proposal.arrives,
                isTorOff = facts.isTorOff,
                handshakes = facts.handshakes,
                onContinue = { showReview(proposal, facts) },
                onNotNow = ::onBack,
                onBack = ::onBack,
            )
    }

    private fun showReview(
        proposal: OrchardSweepTransactionProposal,
        facts: Facts
    ) {
        mutableState.value =
            MoveOldBalanceState.Review(
                amount = proposal.amount,
                fee = proposal.fee,
                arrives = proposal.arrives,
                isFeeHigh = proposal.fee.value * HIGH_FEE_DIVISOR > proposal.amount.value,
                // ZC-101: the confirm closure holds the instance on screen; nothing else can be settled.
                onConfirm = { onConfirm(proposal, facts) },
                onBackToConsent = { showConsent(proposal, facts) },
                onBack = ::onBack,
            )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onConfirm(
        proposal: OrchardSweepTransactionProposal,
        facts: Facts
    ) {
        if (hasSubmitted || confirmJob?.isActive == true) return
        confirmJob =
            viewModelScope.launch {
                try {
                    biometricRepository.requestBiometrics(
                        BiometricRequest(
                            message =
                                stringRes(
                                    R.string.authentication_system_ui_subtitle,
                                    stringRes(R.string.move_old_balance_auth_use_case)
                                )
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Cancelled or failed: nothing was signed. Back to the same review, same proposal.
                    showReview(proposal, facts)
                    return@launch
                }
                if (hasSubmitted) return@launch
                hasSubmitted = true
                // Owner decision 2: queued automatic handshakes must ask again before they spend the
                // moved funds. Reset BEFORE the submit, not on its result: the submit keeps running in
                // the repository's scope if this sheet goes away, and an exception after the broadcast
                // settles as an unknown outcome, not a Success. Asking again after a move that did not
                // happen is harmless.
                // R4-1: and only once the store has CONFIRMED it. A reset that never reached the disk
                // leaves consent standing, and the queued handshakes spend the moved balance without
                // asking — the 2026-09-07 incident this consent exists to prevent. Nothing is sent, the
                // sheet says exactly that, and the user can confirm again.
                if (!zchatPreferences.clearConsentedToHandshakeSpend()) {
                    hasSubmitted = false
                    mutableState.value =
                        MoveOldBalanceState.Failed(
                            isNetworkFailure = false,
                            isOutcomeUnknown = false,
                            onViewTransaction = null,
                            onDone = ::onBack,
                            onBack = ::onBack,
                        )
                    return@launch
                }
                mutableState.value = MoveOldBalanceState.Sending(proposal.amount)
                settle(zashiProposalRepository.submitOrchardSweep(proposal), facts)
            }
    }

    private fun settle(
        result: SubmitResult,
        facts: Facts
    ) {
        val txIds =
            when (result) {
                is SubmitResult.Success -> result.txIds
                is SubmitResult.Failure -> result.txIds
                is SubmitResult.GrpcFailure -> result.txIds
                is SubmitResult.Partial -> result.txIds
                is SubmitResult.SentUnconfirmed -> result.txIds
            }
        val viewTransaction = txIds.firstOrNull()?.let { txId -> { viewTransaction(txId) } }
        mutableState.value =
            when (result) {
                is SubmitResult.Success ->
                    MoveOldBalanceState.Success(
                        handshakes = facts.handshakes,
                        onViewTransaction = viewTransaction,
                        onDone = ::onBack,
                        onBack = ::onBack,
                    )
                else ->
                    MoveOldBalanceState.Failed(
                        isNetworkFailure = result is SubmitResult.GrpcFailure,
                        isOutcomeUnknown = result is SubmitResult.SentUnconfirmed,
                        onViewTransaction = viewTransaction,
                        onDone = ::onBack,
                        onBack = ::onBack,
                    )
            }
    }

    /** Never the shared "after a successful proposal" use case: that one clears the shared cell. */
    private fun viewTransaction(txId: String) = navigationRouter.replaceAll(TransactionDetailArgs(txId))

    private fun pendingHandshakes(): PendingHandshakes? {
        if (!zchatPreferences.hasConsentedToHandshakeSpend()) return null
        val count = pendingHandshakePeers(zchatPreferences)
        return if (count > 0) PendingHandshakes(count, handshakeSpendQuote(count)) else null
    }

    private fun block(reason: MoveOldBalanceBlock) {
        mutableState.value = MoveOldBalanceState.Blocked(reason = reason, onOk = ::onBack, onBack = ::onBack)
    }

    private fun Throwable.toBlock(orchard: Zatoshi): MoveOldBalanceBlock =
        if (generateSequence(this) { it.cause }.take(CAUSE_DEPTH).any { it is NetworkUpgradeRequiredException }) {
            MoveOldBalanceBlock.UpdateRequired
        } else {
            toSweepBlock(orchard)
        }

    private fun Throwable.toSweepBlock(orchard: Zatoshi): MoveOldBalanceBlock =
        when (val failure = classifyOrchardSweepFailure(this)) {
            is OrchardSweepFailure.TooSmall ->
                MoveOldBalanceBlock.TooSmall(
                    orchard = failure.orchard ?: orchard,
                    fee = failure.fee ?: Zatoshi(MIN_ORCHARD_SWEEP_FEE),
                )
            OrchardSweepFailure.Pending -> MoveOldBalanceBlock.Pending
            OrchardSweepFailure.WaitForSync -> MoveOldBalanceBlock.WaitForSync
            OrchardSweepFailure.Error -> MoveOldBalanceBlock.Error
        }

    private fun onBack() = navigationRouter.back()

    private companion object {
        /** The Tor line is shown unless Tor is known to be on within this time. */
        const val TOR_READ_TIMEOUT_MS = 2_000L

        /** The fee warning shows when the fee is more than 1/4 of the amount moved. */
        const val HIGH_FEE_DIVISOR = 4

        /** How far down an error's causes the update refusal is looked for. */
        const val CAUSE_DEPTH = 5
    }
}
