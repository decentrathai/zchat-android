package co.electriccoin.zcash.ui.screen.swap.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitIncreaseEphemeralGapLimitUseCase
import co.electriccoin.zcash.ui.common.util.PaymentDiag
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteInfoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class EphemeralLockVM(
    observeProposal: ObserveProposalUseCase,
    private val navigationRouter: NavigationRouter,
    private val submitIncreaseEphemeralGapLimit: SubmitIncreaseEphemeralGapLimitUseCase,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
) : ViewModel() {
    val state: StateFlow<EphemeralLockState?> =
        observeProposal
            .filterSend()
            .map { proposal ->
                // QA (debug-only): the ZC-101 "displayed" oracle — this exact instance is what the
                // confirm closure below binds.
                PaymentDiag.logProposalDisplayed(proposal)
                EphemeralLockState(
                    items =
                        listOf(
                            SwapQuoteInfoItem(
                                description = stringRes(R.string.send_confirmation_address),
                                title = stringResByAddress(proposal.destination.address),
                            ),
                            SwapQuoteInfoItem(
                                description = stringRes(R.string.send_amount_label),
                                title = stringRes(proposal.amount),
                            ),
                            SwapQuoteInfoItem(
                                description = stringRes(R.string.send_confirmation_fee),
                                title = stringRes(proposal.proposal.totalFeeRequired()),
                            ),
                        ),
                    amount =
                        SwapQuoteInfoItem(
                            description = stringRes(R.string.send_confirmation_amount),
                            title = stringRes(proposal.amount + proposal.proposal.totalFeeRequired()),
                        ),
                    secondaryButton =
                        ButtonState(
                            text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_cancel),
                            onClick = ::onBack
                        ),
                    primaryButton =
                        ButtonState(
                            text = stringRes("Confirm Transaction"),
                            // ZC-101 (money): bind the EXACT proposal instance this state renders
                            // into the confirm action, so the biometric authorizes what the screen
                            // showed — never a later re-read of the shared cell, which an inbound
                            // auto-send can overwrite. The map parameter is named explicitly so
                            // this binding can't silently rebind if ButtonState.onClick ever
                            // gains a parameter of its own.
                            onClick = { onSubmitClick(proposal) }
                        ),
                    onBack = ::onBack
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private var onSubmitClickJob: Job? = null

    private fun onBack() {
        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        navigationRouter.backToRoot()
    }

    // ZC-101 (money): [proposal] is the gap-limit proposal this screen displayed — captured in the
    // button's onClick closure at render time, BEFORE the biometric window opens inside
    // submitIncreaseEphemeralGapLimit — so the submitted proposal is the one the user confirmed.
    private fun onSubmitClick(proposal: SendTransactionProposal) {
        if (onSubmitClickJob?.isActive == true) return
        onSubmitClickJob = viewModelScope.launch { submitIncreaseEphemeralGapLimit(proposal) }
    }
}
