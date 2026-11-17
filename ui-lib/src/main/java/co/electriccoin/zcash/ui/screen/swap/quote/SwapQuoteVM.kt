package co.electriccoin.zcash.ui.screen.swap.quote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.QuoteLowAmountException
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.ResponseWithNearErrorException
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.util.PaymentDiag
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationArgs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

internal class SwapQuoteVM(
    observeProposal: ObserveProposalUseCase,
    applicationStateProvider: ApplicationStateProvider,
    private val swapRepository: SwapRepository,
    private val cancelSwapQuote: CancelSwapQuoteUseCase,
    private val cancelSwap: CancelSwapUseCase,
    private val swapQuoteSuccessMapper: SwapQuoteVMMapper,
    private val submitProposal: SubmitProposalUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<SwapQuoteState?> =
        combine(
            swapRepository.quote.filterNotNull(),
            observeProposal.observeNullable(),
        ) { quote, proposal ->
            when (quote) {
                SwapQuoteData.Loading -> null
                is SwapQuoteData.Error -> createErrorState(quote)
                is SwapQuoteData.Success -> createState(proposal, quote)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    init {
        // SWAP-1: the visible state flow filters nulls (swapRepository.quote.filterNotNull()), and the ONLY
        // back handler lives inside the non-null sheet body (ZashiScreenModalBottomSheet renders it inside
        // state?.let). The host dialog is dismissOnBackPress=false. So if this VM is (re)created with no quote
        // in memory — process death, or returning to a stale SwapQuoteArgs entry after the quote was cleared —
        // the sheet renders BLANK and back does nothing: a dead screen. Bail straight back in that case.
        if (swapRepository.quote.value == null) {
            navigationRouter.back()
        }

        applicationStateProvider
            .observeOnForeground()
            .onEach {
                val quote = (swapRepository.quote.value as? SwapQuoteData.Success)?.quote ?: return@onEach

                if ((Clock.System.now() - quote.timestamp) >= QUOTE_MAX_AGE) {
                    cancelSwapQuote()
                }
            }.launchIn(viewModelScope)
    }

    private fun createState(
        proposal: TransactionProposal?,
        quote: SwapQuoteData.Success
    ): SwapQuoteState.Success {
        // QA (debug-only): the ZC-101 "displayed" oracle — log the DISPLAYED cast (what the confirm
        // closure binds). A cross-type overwrite renders/binds null and logs pid=- (fail-closed).
        PaymentDiag.logProposalDisplayed(proposal as? SwapTransactionProposal)
        return swapQuoteSuccessMapper.createState(
            state = SwapQuoteInternalState(proposal as? SwapTransactionProposal, quote.quote),
            onBack = ::onBack,
            // ZC-101 (money): bind the EXACT proposal instance this state renders into the confirm
            // action, so the biometric authorizes what the sheet showed — never a later re-read of
            // the shared cell, which an inbound auto-send can overwrite. The DISPLAYED cast is what
            // gets bound: a non-swap overwrite (e.g. a ZIP-321 dust auto-ack) renders as "no
            // proposal" AND binds null, which fail-closes in onSubmitQuoteClick.
            onSubmitQuoteClick = { onSubmitQuoteClick(proposal as? SwapTransactionProposal) },
            onNavigateToOnRampSwap = ::onNavigateToOnRampSwap
        )
    }

    private fun onNavigateToOnRampSwap() = navigationRouter.forward(ORSwapConfirmationArgs)

    private fun createErrorState(quote: SwapQuoteData.Error): SwapQuoteState.Error {
        val message =
            when {
                quote.exception is QuoteLowAmountException &&
                    quote.exception.amountFormatted != null ->
                    stringRes(
                        R.string.swap_quote_error_too_low_try_at_least,
                        stringResByDynamicCurrencyNumber(
                            amount = quote.exception.amountFormatted,
                            ticker = quote.exception.asset.tokenTicker
                        )
                    )

                quote.exception is QuoteLowAmountException -> stringRes(R.string.swap_quote_error_too_low_try_higher)
                // No solver/route for this pair right now (the upstream 1Click bridge returns
                // "Failed to get quote" — e.g. when the ZEC route is temporarily down). Show an
                // accurate, actionable message instead of a generic "couldn't get a quote".
                quote.exception is ResponseWithNearErrorException &&
                    quote.exception.error.message.contains("failed to get quote", ignoreCase = true) ->
                    stringRes(R.string.swap_quote_error_no_route)

                quote.exception is ResponseWithNearErrorException ->
                    stringRes(quote.exception.error.message)

                else ->
                    when (quote.mode) {
                        EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swap_quote_error_getting_quote_swap)
                        EXACT_OUTPUT -> stringRes(R.string.swap_quote_error_getting_quote)
                    }
            }

        return SwapQuoteState.Error(
            icon = imageRes(R.drawable.ic_swap_quote_error),
            title = stringRes(R.string.swap_quote_unavailable),
            subtitle = message,
            negativeButton =
                ButtonState(
                    text =
                        when (quote.mode) {
                            EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swap_quote_cancel_swap)
                            EXACT_OUTPUT -> stringRes(R.string.swap_quote_cancel_payment)
                        },
                    onClick = ::onCancelPaymentClick
                ),
            positiveButton =
                ButtonState(
                    text =
                        when (quote.mode) {
                            EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swap_quote_edit_swap)
                            EXACT_OUTPUT -> stringRes(R.string.swap_quote_edit_payment)
                        },
                    onClick = ::onEditPaymentClick
                ),
            onBack = ::onBackDuringError
        )
    }

    private fun onEditPaymentClick() = cancelSwapQuote()

    private fun onBack() = cancelSwapQuote()

    // SWAP-1: public back entry for a top-level BackHandler in SwapQuoteScreen that stays registered even when
    // state is null (the inner sheet BackHandler only exists while state != null). cancelSwapQuote() navigates
    // back then clears, so it is safe to invoke regardless of whether a quote is present.
    fun onBackSafe() = cancelSwapQuote()

    private fun onBackDuringError() = cancelSwapQuote()

    // HIGH-008 (money): re-entry latch for Confirm — mirrors ReviewTransactionVM.onConfirmClick.
    // Without it a double-tap (or overlapping biometric callback) launches submitProposal() twice;
    // both read the SAME stored proposal from ZashiProposalRepository and broadcast it twice
    // (double spend). While the job is active every further click is ignored. On success
    // submitProposal navigates away (replace), so the latch never blocks a legitimate confirm;
    // on biometric cancel/failure the job completes and the latch releases for a retry.
    private var onSubmitQuoteJob: Job? = null

    // ZC-101 (money): [proposal] is the instance the on-screen state was built from — captured in
    // the confirm closure at render time, BEFORE the biometric window opens inside submitProposal.
    // If the sheet rendered without a proposal there is nothing the user could have reviewed, so
    // fail closed (no-op) instead of falling back to the shared cell — a cell read here could
    // authorize a proposal the user never saw.
    private fun onSubmitQuoteClick(proposal: SwapTransactionProposal?) {
        if (onSubmitQuoteJob?.isActive == true) return
        onSubmitQuoteJob =
            viewModelScope.launch {
                // MEDIUM-020: quote age was only re-checked on foreground — a sheet left open
                // on-screen past the quote's validity could still be confirmed at a dead price.
                // Re-validate at Confirm and treat a stale (or missing) quote exactly like the
                // foreground check does: cancel out so the user must request a fresh quote.
                val quote = (swapRepository.quote.value as? SwapQuoteData.Success)?.quote
                if (quote == null || (Clock.System.now() - quote.timestamp) >= QUOTE_MAX_AGE) {
                    cancelSwapQuote()
                    return@launch
                }
                if (proposal == null) return@launch
                submitProposal(proposal)
            }
    }

    private fun onCancelPaymentClick() = cancelSwap()

    private companion object {
        // A quote older than this is stale: cancelled on foreground-resume AND blocked at Confirm.
        val QUOTE_MAX_AGE = 3.minutes
    }
}

internal data class SwapQuoteInternalState(
    val proposal: SwapTransactionProposal?,
    val quote: SwapQuote,
) {
    val total: BigDecimal = quote.getTotal(proposal?.proposal)
    val totalUsd: BigDecimal = quote.getTotalUsd(proposal?.proposal)
    val totalFees = quote.affiliateFee
    val totalFeesZatoshi: Zatoshi = quote.getTotalFeesZatoshi(proposal?.proposal)
    val totalFeesUsd: BigDecimal = quote.getTotalFeesUsd(proposal?.proposal)
}
