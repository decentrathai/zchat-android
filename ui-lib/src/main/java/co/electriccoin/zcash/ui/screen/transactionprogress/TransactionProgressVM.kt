package co.electriccoin.zcash.ui.screen.transactionprogress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules
import co.electriccoin.zcash.ui.common.tex.PlainSendsOutcome
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.common.tex.TexRules
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.forTransaction
import co.electriccoin.zcash.ui.common.tex.plainSendsOutcome
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveTransactionSubmitStateUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionDetailAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewTransactionsAfterSuccessfulProposalUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class TransactionProgressVM(
    observeTransactionProposal: ObserveProposalUseCase,
    observeTransactionSubmitState: ObserveTransactionSubmitStateUseCase,
    private val getTransactionProposal: GetProposalUseCase,
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val sendEmailUseCase: SendEmailUseCase,
    private val cancelKeystoneProposalFlow: CancelProposalFlowUseCase,
    private val viewTransactionsAfterSuccessfulProposal: ViewTransactionsAfterSuccessfulProposalUseCase,
    private val viewTransactionDetailAfterSuccessfulProposal: ViewTransactionDetailAfterSuccessfulProposalUseCase
) : ViewModel() {
    private val supportContacted = MutableStateFlow(false)

    /** ZC-417: the result whose wait for a first verdict runs, and when this screen first saw it ([System.nanoTime]). */
    private var waitResult: SubmitResult? = null
    private var waitStartedAtNanos = 0L

    /**
     * ZC-417: the submit state, and whether the longest wait for the checker's first verdict on its result
     * ([pendingMaxMs], counted from the moment this screen first saw that result) is over. On Dispatchers.Default so
     * the wait runs in real time wherever the screen collects.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val submitStateAndWaitOver: Flow<Pair<SubmitProposalState?, Boolean>> =
        observeTransactionSubmitState()
            .transformLatest { submitState ->
                emit(submitState to false)
                if (submitState is SubmitProposalState.Result) {
                    delay(remainingWaitMs(submitState.submitResult))
                    emit(submitState to true)
                }
            }.flowOn(Dispatchers.Default)

    val state: StateFlow<TransactionProgressState?> =
        combine(
            observeTransactionProposal(),
            submitStateAndWaitOver,
            supportContacted,
            // F5: the TEX checker's last pass (process-wide), which also judges every plain send of the ZCHAT account.
            TexSendMonitor.latestCheck
        ) { proposal, (submitState, waitOver), _, check ->
            when (submitState) {
                null, SubmitProposalState.Submitting -> createSendingTransactionState(proposal)
                is SubmitProposalState.Result ->
                    chainOutcomeState(proposal, submitState.submitResult, check)
                        ?: awaitingFirstVerdictState(proposal, submitState.submitResult, check, waitOver)
                        ?: when (val result = submitState.submitResult) {
                            is SubmitResult.Partial ->
                                if (result.unacceptedLegsResent) {
                                    createPartlySentTransactionState(result)
                                } else {
                                    createPartialFailureTransactionState(proposal, result)
                                }

                            is SubmitResult.GrpcFailure ->
                                createGrpcFailureTransactionState(result)

                            is SubmitResult.Failure ->
                                createFailureTransactionState(proposal, result)

                            is SubmitResult.SentUnconfirmed ->
                                createSentUnconfirmedTransactionState(result)

                            is SubmitResult.Success -> createSuccessfulTransactionState(proposal, result)
                        }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    /**
     * MONEY-CRITICAL (F5, fix-all 2.13.0, train T5): a Wallet (or chat Payment / Welcome) send whose result named its
     * stored transactions but did not confirm them (GrpcFailure, SentUnconfirmed, a Failure with ids) follows the chain
     * while the screen is open, by the TEX checker's pass (F4's rules: mined, or proven never mined on two passes at a
     * rising scanned tip 10 blocks past the expiry). All mined: the success screen. All proven never mined: "Not Sent",
     * nothing was paid (View Transaction opens the detail, where Send again is offered once). Otherwise it may still be
     * mined: "Waiting for the Network" with the block and the minutes, and never an invitation to send again. Only for a
     * Regular or ZIP-321 proposal that is not to a TEX address (TEX has its own checker texts); a shielding, a swap, a
     * Partial, a result with no ids, or a check that does not know every id keeps today's screen.
     *
     * Exits (ZC-597, train T6a): while the send may still be mined (Waiting) every exit goes to the history, for every
     * result: the SDK submits a stored transaction again until it expires, so an emptied Send form would invite a
     * second payment. Only a send proven never mined (Not Sent) keeps the result's own exit: a Failure with ids
     * returns to a cleared Send form, the others go to the history.
     */
    private suspend fun chainOutcomeState(
        proposal: TransactionProposal?,
        result: SubmitResult,
        check: TexCheck?,
    ): TransactionProgressState? {
        if (proposal !is RegularTransactionProposal && proposal !is Zip321TransactionProposal) return null
        if (proposal.destination is WalletAddress.Tex) return null
        val txIds = followedTxIds(result) ?: return null
        val outcome = plainSendsOutcome(txIds, check) ?: return null
        val notSentExit: () -> Unit =
            if (result is SubmitResult.Failure) ::onBackToSendFormAndClear else ::onViewTransactions
        val viewTransaction = { onViewTransactionDetailClick(txIds.last()) }
        return when (outcome) {
            PlainSendsOutcome.Sent -> createSuccessfulTransactionState(proposal, SubmitResult.Success(txIds))
            PlainSendsOutcome.NotSent ->
                SentUnconfirmedTransactionState(
                    title = stringRes(R.string.send_confirmation_not_sent_title),
                    text = stringRes(R.string.send_confirmation_not_sent_subtitle),
                    onViewTransactionClick = viewTransaction,
                    onCloseClick = notSentExit,
                    onBack = notSentExit,
                )
            is PlainSendsOutcome.Waiting ->
                SentUnconfirmedTransactionState(
                    title = stringRes(R.string.send_confirmation_waiting_title),
                    text =
                        if (outcome.tip >= SendAttemptRules.deadBlock(outcome.expiryHeight)) {
                            stringRes(R.string.transaction_detail_checking_body)
                        } else {
                            stringRes(
                                R.string.send_confirmation_waiting_subtitle,
                                SendAttemptRules.deadBlock(outcome.expiryHeight).toString(),
                                SendAttemptRules.minutesUntilDead(outcome.expiryHeight, outcome.tip),
                            )
                        },
                    onViewTransactionClick = viewTransaction,
                    onCloseClick = ::onViewTransactions,
                    onBack = ::onViewTransactions,
                )
        }
    }

    /** F5: the ids of a result the screen follows by the checker (one that named its stored transactions), or null. */
    private fun followedTxIds(result: SubmitResult): List<String>? =
        when (result) {
            is SubmitResult.GrpcFailure -> result.txIds
            is SubmitResult.SentUnconfirmed -> result.txIds
            is SubmitResult.Failure -> result.txIds
            else -> null
        }

    /**
     * MONEY-CRITICAL (ZC-417, fix-all 2.13.0, train T5b; t5b-add SPECS §8): an offline Wallet send returns GrpcFailure
     * at once, while the checker's next pass, the first that can judge the new transaction, comes up to
     * [TexRules.PASS_INTERVAL_MS] later. Until then the screen keeps the in-progress Sending state instead of the
     * submit's own screen ("Connection Issue … will retry"), which F5's verdict would replace a moment later. Only while
     * the process checker runs ([TexSendMonitor.checkerRunning]), for the sends F5 follows ([chainOutcomeState]: a
     * Regular or ZIP-321 send not to a TEX address whose result names ids), while some unmined id has no verdict in
     * [check] and is in no TEX status, and for at most [pendingMaxMs]: then today's screen. Back goes to the history
     * for every such result (ZC-596: also for a Failure with ids, whose stored transaction the SDK submits again until
     * it expires; this screen carries no warning, so it never leads to an emptied Send form). It sends, asks and offers
     * nothing.
     */
    private suspend fun awaitingFirstVerdictState(
        proposal: TransactionProposal?,
        result: SubmitResult,
        check: TexCheck?,
        waitOver: Boolean,
    ): TransactionProgressState? {
        if (waitOver || !TexSendMonitor.checkerRunning) return null
        if (proposal !is RegularTransactionProposal && proposal !is Zip321TransactionProposal) return null
        if (proposal.destination is WalletAddress.Tex) return null
        val txIds = followedTxIds(result) ?: return null
        val unjudged =
            txIds.any { txId ->
                check == null ||
                    (txId !in check.minedSends && txId !in check.plainSends && check.statuses.forTransaction(txId) == null)
            }
        if (!unjudged) return null
        return createSendingTransactionState(proposal)
    }

    /** ZC-417: how much of [pendingMaxMs] is left for [result], counted from the first time this screen saw it. */
    @Synchronized
    private fun remainingWaitMs(result: SubmitResult): Long {
        val now = System.nanoTime()
        if (waitResult != result) {
            waitResult = result
            waitStartedAtNanos = now
        }
        return (pendingMaxMs - (now - waitStartedAtNanos) / NANOS_PER_MS).coerceAtLeast(0)
    }

    /**
     * MONEY-CRITICAL (T3-first, train T2 F2-3): part of a multi-transaction send was accepted and every other part
     * failed only on the transport. F2 stored every part in the wallet and handed each to the server, so the SDK keeps
     * rebroadcasting the rest until it is mined or expires; on the phones both TEX legs were mined, exactly as quoted.
     * It is therefore sent and waiting for the network: never "Send Failed" (which invites paying again), never the
     * success screen (not every part is confirmed). Painted as the not-confirmed warning; View Transaction opens the
     * last part (a TEX send's payment to the exchange), and Back and Close go to the transaction history, never to a
     * filled Send form. F3's TEX checker replaces this with the live status of each part.
     */
    private fun createPartlySentTransactionState(result: SubmitResult.Partial) =
        SentUnconfirmedTransactionState(
            title = stringRes(R.string.send_confirmation_partly_sent_title),
            text = stringRes(R.string.send_confirmation_partly_sent_subtitle),
            onViewTransactionClick = {
                val txId = result.txIds.lastOrNull()
                if (txId != null) onViewTransactionDetailClick(txId) else onViewTransactions()
            },
            onCloseClick = ::onViewTransactions,
            onBack = ::onViewTransactions,
        )

    /**
     * A part of a multi-transaction send was refused by the server (or never attempted) after the earlier parts were
     * accepted. Back always works (T3-first, train T2 F2-3: it did nothing until Contact Support was tapped) and goes
     * to the transaction history, never to a filled Send form, since part of the money has moved.
     *
     * T3b-2 (train T3 finding 7): for a send to a TEX address the F3 TEX checker (TexSendMonitor) finishes or undoes it
     * with no question: it hands an unexpired part to the server again, and once the second step can no longer be
     * mined it looks up the ZEC at the one-time address (turning Tor on when nothing else is in progress) and shields
     * it back into the wallet, minus the network fee. So the screen says that, not "Send Failed … recovered with
     * assistance", and View Transaction opens the last part, whose detail shows the checker's live status. Contact
     * Support stays. Any other multi-transaction send is not covered by the checker and keeps the support texts.
     */
    private fun createPartialFailureTransactionState(
        proposal: TransactionProposal?,
        result: SubmitResult.Partial
    ): MultipleFailuresTransactionState {
        val tex = (proposal as? SendTransactionProposal)?.destination is WalletAddress.Tex
        val lastPart = result.txIds.lastOrNull()
        return MultipleFailuresTransactionState(
            showBackButton = true,
            onBack = ::onViewTransactions,
            onCopyClick = {
                copyToClipboardUseCase(
                    value = result.txIds.joinToString()
                )
            },
            onSupportClick = {
                viewModelScope.launch {
                    sendEmailUseCase(result)
                    this@TransactionProgressVM.supportContacted.update { true }
                }
            },
            transactionIds = result.txIds,
            title =
                stringRes(
                    if (tex) R.string.send_confirmation_tex_part_not_sent_title else R.string.send_confirmation_multiple_trx_failure_title
                ),
            text1 =
                stringRes(
                    if (tex) R.string.send_confirmation_tex_part_not_sent_text_1 else R.string.send_confirmation_multiple_trx_failure_text_1
                ),
            text2 =
                stringRes(
                    if (tex) R.string.send_confirmation_tex_part_not_sent_text_2 else R.string.send_confirmation_multiple_trx_failure_text_2
                ),
            onViewTransactionClick = if (tex && lastPart != null) ({ onViewTransactionDetailClick(lastPart) }) else null,
        )
    }

    /**
     * MONEY-CRITICAL: the transaction may already be on the wire (the settlement call threw after the
     * broadcast), so this is NOT the "Send Failed" screen — that one says "Try it again, please" and
     * routes Back/Close to a still-populated Send form, which is how a broadcast payment got paid
     * twice. Nor is it the success screen: nothing confirmed it (R2-6 a). Say it was sent but not
     * confirmed, as a warning, and route every exit to the transaction history where the truth will
     * appear. The same state serves a shielding.
     */
    private fun createSentUnconfirmedTransactionState(result: SubmitResult.SentUnconfirmed) =
        SentUnconfirmedTransactionState(
            title = stringRes(R.string.send_confirmation_unconfirmed_title),
            text = stringRes(R.string.send_confirmation_unconfirmed_subtitle),
            onViewTransactionClick = {
                val txId = result.txIds.lastOrNull()
                if (txId != null) onViewTransactionDetailClick(txId) else onViewTransactions()
            },
            onCloseClick = ::onViewTransactions,
            onBack = ::onViewTransactions,
        )

    private fun createGrpcFailureTransactionState(result: SubmitResult.GrpcFailure) =
        GrpcFailureTransactionState(
            onBack = ::onViewTransactions,
            onCloseClick = ::onViewTransactions,
            onViewTransactionClick = { result.txIds.lastOrNull()?.let { onViewTransactionDetailClick(it) } }
        )

    private suspend fun createSuccessfulTransactionState(
        proposal: TransactionProposal?,
        result: SubmitResult.Success
    ) = SuccessfulTransactionState(
        onBack = ::onViewTransactions,
        middleButton =
            when (proposal) {
                is ExactInputSwapTransactionProposal,
                is ExactOutputSwapTransactionProposal -> null

                else ->
                    ButtonState(
                        text = stringRes(R.string.send_confirmation_success_view_trx),
                        onClick = { onViewTransactionClick(result) }
                    )
            },
        secondaryButton =
            when (proposal) {
                is ExactInputSwapTransactionProposal,
                is ExactOutputSwapTransactionProposal ->
                    ButtonState(
                        text = stringRes(R.string.send_confirmation_success_btn_close),
                        onClick = ::onViewTransactions,
                        style = ButtonStyle.SECONDARY
                    )

                else -> null
            },
        primaryButton =
            when (proposal) {
                is ExactInputSwapTransactionProposal,
                is ExactOutputSwapTransactionProposal ->
                    ButtonState(
                        text = stringRes(R.string.send_confirmation_success_btn_check_status),
                        onClick = { onViewTransactionClick(result) },
                        style = ButtonStyle.PRIMARY
                    )

                else ->
                    ButtonState(
                        text = stringRes(R.string.send_confirmation_success_btn_close),
                        onClick = ::onViewTransactions,
                        style = ButtonStyle.TERTIARY
                    )
            },
        text =
            when (proposal) {
                is ShieldTransactionProposal -> stringRes(R.string.send_confirmation_success_subtitle_transparent)

                is ExactInputSwapTransactionProposal ->
                    stringRes(R.string.send_confirmation_success_swap_subtitle)

                is ExactOutputSwapTransactionProposal ->
                    stringRes(
                        "You successfully initiated a cross-chain payment.\nFollow its status on the " +
                            "transaction screen."
                    )

                else -> stringRes(R.string.send_confirmation_success_subtitle, getAddressAbbreviated())
            },
        title =
            if (proposal is ShieldTransactionProposal) {
                stringRes(R.string.send_confirmation_success_title_transparent)
            } else {
                stringRes(R.string.send_confirmation_success_title)
            }
    )

    private fun onViewTransactionClick(result: SubmitResult.Success) {
        val txId = result.txIds.lastOrNull()
        if (txId == null) onViewTransactions() else onViewTransactionDetailClick(txId)
    }

    /**
     * MONEY-CRITICAL (round 8 D3, REVIEW_7C F30): a Failure that names transaction ids means the SDK STORED
     * them: the transaction is in the wallet and may still reach the network (the SDK re-broadcasts a stored
     * transaction it already tried to send). Returning to a still-filled Send form under "try it again" let one
     * more tap build and pay a second transaction for the same payee and amount. So, when ids are named, the
     * text says the send is not confirmed and may still go out; View Transaction opens the stored one. With no ids
     * nothing was stored, and the form and "try it again" stay. A shielding keeps its own text and exit.
     *
     * ZC-611 (T7b triage 13 Oct; ZC-596 SPEC R1, owner rule W-10): with ids, every exit goes to the history, as on
     * the Waiting screen. An emptied Send form (the exit until then) reads as "cancelled" and invites the payment
     * again while the stored one can still be mined. The same holds for a swap or pay proposal, whose exit returned to
     * its own screen with the quote cleared; the history exit drops the proposal too.
     */
    private fun createFailureTransactionState(
        proposal: TransactionProposal?,
        result: SubmitResult.Failure,
    ): FailureTransactionState {
        val updateRequired = result.txIds.isEmpty() && result.description == NetworkUpgradeRequiredException.MESSAGE
        val stored = result.txIds.isNotEmpty() && proposal !is ShieldTransactionProposal
        val exit: () -> Unit = if (stored) ::onViewTransactions else ::onBackToSendForm
        return FailureTransactionState(
            onBack = exit,
            onCloseClick = exit,
            onViewTransactionClick =
                if (result.txIds.isNotEmpty()) {
                    { onViewTransactionDetailClick(result.txIds.last()) }
                } else {
                    null
                },
            onReportClick = {
                viewModelScope.launch {
                    sendEmailUseCase(result)
                    this@TransactionProgressVM.supportContacted.update { true }
                }
            },
            title =
                when {
                    updateRequired -> stringRes(R.string.update_required_title)

                    proposal is ShieldTransactionProposal ->
                        stringRes(R.string.send_confirmation_failure_title_transparent)

                    stored -> stringRes(R.string.send_confirmation_failure_stored_title)

                    else -> stringRes(R.string.send_confirmation_failure_title)
                },
            text =
                when {
                    updateRequired -> stringRes(R.string.update_required_refusal)

                    stored -> stringRes(R.string.send_confirmation_failure_stored_subtitle)

                    proposal is ExactInputSwapTransactionProposal ->
                        stringRes(R.string.send_confirmation_error_swap_subtitle)

                    proposal is ExactOutputSwapTransactionProposal ->
                        stringRes(
                            "There was an error initiating a cross-chain payment.\nTry it again, please."
                        )

                    proposal is ShieldTransactionProposal ->
                        stringRes(R.string.send_confirmation_failure_subtitle_transparent)

                    else -> stringRes(R.string.send_confirmation_failure_subtitle)
                }
        )
    }

    // Back during an in-flight broadcast must NOT clear the send form: the transaction is
    // already submitting on a detached scope, so dropping the user on an empty Send form
    // reads as "cancelled" and invites a double-send. Route to the transaction list instead
    // (same as the success/gRPC-failure states) so the pending send stays visible.
    // ZC-596: the same while a result waits for the checker's first verdict (ZC-417), whatever the result.
    private suspend fun createSendingTransactionState(proposal: TransactionProposal?) =
        SendingTransactionState(
            onBack = ::onViewTransactions,
            text =
                when (proposal) {
                    is ShieldTransactionProposal -> stringRes(R.string.send_confirmation_sending_subtitle_transparent)
                    is SwapTransactionProposal ->
                        stringRes(R.string.send_confirmation_swapping_subtitle_transparent)

                    else -> stringRes(R.string.send_confirmation_sending_subtitle, getAddressAbbreviated())
                },
            title =
                if (proposal is ShieldTransactionProposal) {
                    stringRes(R.string.send_confirmation_sending_title_transparent)
                } else {
                    stringRes(R.string.send_confirmation_sending_title)
                }
        )

    private suspend fun getAddressAbbreviated(): StringResource {
        val address = (getTransactionProposal() as? SendTransactionProposal)?.destination?.address
        return address?.let { stringResByAddress(it) } ?: stringRes("")
    }

    private fun onBackToSendFormAndClear() = viewModelScope.launch { cancelKeystoneProposalFlow(clearSendForm = true) }

    private fun onBackToSendForm() = viewModelScope.launch { cancelKeystoneProposalFlow(clearSendForm = false) }

    private fun onViewTransactions() = viewTransactionsAfterSuccessfulProposal()

    private fun onViewTransactionDetailClick(txId: String) = viewTransactionDetailAfterSuccessfulProposal(txId)

    companion object {
        /** ZC-417: the longest wait for the checker's first verdict on a new send: one pass interval and 30 s more. */
        const val PENDING_MAX_MS = TexRules.PASS_INTERVAL_MS + 30_000L

        private const val NANOS_PER_MS = 1_000_000L

        /** [PENDING_MAX_MS]; tests shorten it by name, the app never changes it. */
        @Volatile
        private var pendingMaxMs: Long = PENDING_MAX_MS
    }
}
