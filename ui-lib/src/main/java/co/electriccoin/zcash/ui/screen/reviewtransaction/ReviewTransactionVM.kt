package co.electriccoin.zcash.ui.screen.reviewtransaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.DustAmount
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.GetExchangeRateUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.util.PaymentDiag
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.addressbook.ADDRESS_MAX_LENGTH
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.util.Quintuple
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReviewTransactionVM(
    getWalletAccounts: GetWalletAccountsUseCase,
    observeContactByAddress: ObserveContactByAddressUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    observeProposal: ObserveProposalUseCase,
    private val cancelProposalFlow: CancelProposalFlowUseCase,
    private val getExchangeRate: GetExchangeRateUseCase,
    private val navigationRouter: NavigationRouter,
    private val submitProposal: SubmitProposalUseCase,
) : ViewModel() {
    private val isReceiverExpanded = MutableStateFlow(false)

    // QA2 Q2-16 (ZC-1619): one event per cancelled Send Funds prompt; the screen says "Not sent — nothing was paid".
    private val sendCancelledEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sendCancelled: SharedFlow<Unit> = sendCancelledEvents.asSharedFlow()

    private val exchangeRate =
        flow {
            emit(getExchangeRate())
        }.shareIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            replay = 1
        )

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    @OptIn(ExperimentalCoroutinesApi::class)
    val state =
        combine(
            observeSelectedWalletAccount.require(),
            observeProposal.filterSend(),
            isReceiverExpanded,
            exchangeRate,
            getWalletAccounts.observe()
        ) { wallet, zecSend, isReceiverExpanded, exchangeRate, accounts ->
            Quintuple(wallet, zecSend, isReceiverExpanded, exchangeRate, accounts)
        }.flatMapLatest { (selectedWallet, proposal, isReceiverExpanded, exchangeRate, accounts) ->
            observeContactByAddress(
                if (proposal is ExactOutputSwapTransactionProposal) {
                    proposal.quote.destinationAddress.address
                } else {
                    proposal.destination.address
                }
            ).map { addressBookContact ->
                // QA (debug-only): the ZC-101 "displayed" oracle — this exact instance is what the
                // confirm closures below bind, so the runner can assert submitted.pid == this pid.
                PaymentDiag.logProposalDisplayed(proposal)
                when (proposal) {
                    is Zip321TransactionProposal ->
                        createZip321State(
                            transactionProposal = proposal,
                            addressBookContact = addressBookContact,
                            selectedWallet = selectedWallet,
                            isReceiverExpanded = isReceiverExpanded,
                            exchangeRateState = exchangeRate
                        )

                    else ->
                        createState(
                            transactionProposal = proposal,
                            addressBookContact = addressBookContact,
                            selectedWallet = selectedWallet,
                            exchangeRateState = exchangeRate,
                            accounts = accounts
                        )
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private var onConfirmClickJob: Job? = null

    private fun createState(
        selectedWallet: WalletAccount,
        transactionProposal: SendTransactionProposal,
        addressBookContact: EnhancedABContact?,
        exchangeRateState: ExchangeRateState,
        accounts: List<WalletAccount>?
    ) = ReviewTransactionState(
        title =
            when (selectedWallet) {
                is KeystoneAccount -> stringRes(R.string.review_keystone_transaction_title)
                is ZashiAccount -> stringRes(R.string.send_stage_confirmation_title)
            },
        items =
            listOfNotNull(
                AmountState(
                    title = stringRes(R.string.send_confirmation_amount),
                    amount = transactionProposal.amount + transactionProposal.proposal.totalFeeRequired(),
                    exchangeRate = exchangeRateState,
                ),
                ReceiverState(
                    title = stringRes(R.string.send_confirmation_address),
                    name = addressBookContact?.name?.let { stringRes(it) },
                    address = stringRes(transactionProposal.destination.address)
                ),
                SenderState(
                    title = stringRes(R.string.send_confirmation_address_from),
                    icon = selectedWallet.icon,
                    name = selectedWallet.name
                ).takeIf { (accounts?.size ?: 0) > 1 },
                FinancialInfoState(
                    title = stringRes(R.string.send_amount_label),
                    amount = transactionProposal.amount
                ),
                dustWarning(transactionProposal.amount),
                FinancialInfoState(
                    title = stringRes(R.string.send_confirmation_fee),
                    amount = transactionProposal.proposal.totalFeeRequired()
                ),
                transactionProposal.memo
                    .takeIf { it.value.isNotEmpty() }
                    ?.let {
                        MessageState(
                            title = stringRes(R.string.send_memo_label),
                            message = stringRes(it.value)
                        )
                    }?.takeIf { transactionProposal.destination !is WalletAddress.Transparent },
                MessagePlaceholderState(
                    title = stringRes(R.string.send_memo_label),
                    message = stringRes(R.string.send_transparent_memo),
                    icon = R.drawable.ic_confirmation_message_info,
                ).takeIf { transactionProposal.destination is WalletAddress.Transparent },
            ),
        primaryButton =
            ButtonState(
                text =
                    when (selectedWallet) {
                        is KeystoneAccount -> stringRes(R.string.review_keystone_transaction_positive)
                        is ZashiAccount -> stringRes(R.string.send_confirmation_send_button)
                    },
                // ZC-101 (money): bind the EXACT proposal instance this state renders into the
                // confirm action, so the biometric authorizes what the screen showed — never a
                // later re-read of the shared cell, which an inbound auto-send can overwrite.
                onClick = { onConfirmClick(transactionProposal) }
            ),
        onBack = ::onBack,
        isShieldedRecipient = transactionProposal.destination.isShielded(),
    )

    private fun createZip321State(
        transactionProposal: SendTransactionProposal,
        addressBookContact: EnhancedABContact?,
        selectedWallet: WalletAccount,
        isReceiverExpanded: Boolean,
        exchangeRateState: ExchangeRateState
    ) = ReviewTransactionState(
        title = stringRes(R.string.payment_request_title),
        items =
            listOfNotNull(
                AmountState(
                    title = null,
                    amount = transactionProposal.amount,
                    exchangeRate = exchangeRateState,
                ),
                dustWarning(transactionProposal.amount),
                SenderState(
                    title = stringRes(R.string.send_confirmation_address_from),
                    icon = selectedWallet.icon,
                    name = selectedWallet.name
                ),
                ReceiverExpandedState(
                    title = stringRes(R.string.payment_request_requested_by),
                    name = addressBookContact?.name?.let { stringRes(it) },
                    address =
                        if (isReceiverExpanded) {
                            stringRes(transactionProposal.destination.address)
                        } else {
                            stringRes("${transactionProposal.destination.address.take(ADDRESS_MAX_LENGTH)}...")
                        },
                    showButton =
                        ChipButtonState(
                            startIcon =
                                if (isReceiverExpanded) {
                                    co.electriccoin.zcash.ui.design.R.drawable.ic_chevron_up
                                } else {
                                    co.electriccoin.zcash.ui.design.R.drawable.ic_chevron_down
                                },
                            text = stringRes(R.string.payment_request_btn_show_address),
                            onClick = ::onExpandReceiverClick
                        ),
                    saveButton =
                        ChipButtonState(
                            startIcon = R.drawable.ic_user_plus,
                            text = stringRes(R.string.payment_request_btn_save_contact),
                            onClick = { onAddContactClick(transactionProposal.destination.address) }
                        ).takeIf { addressBookContact == null }
                ),
                transactionProposal.memo.takeIf { it.value.isNotEmpty() }?.let {
                    MessageState(
                        title = stringRes(R.string.payment_request_memo),
                        message = stringRes(it.value)
                    )
                },
                FinancialInfoState(
                    title = stringRes(R.string.payment_request_fee),
                    amount = transactionProposal.proposal.totalFeeRequired()
                )
            ),
        primaryButton =
            ButtonState(
                text =
                    when (selectedWallet) {
                        is KeystoneAccount -> stringRes(R.string.review_keystone_transaction_positive)
                        is ZashiAccount -> stringRes(R.string.payment_request_send_btn)
                    },
                // ZC-101 (money): bind the EXACT proposal instance this state renders (see
                // createState above).
                onClick = { onConfirmClick(transactionProposal) }
            ),
        onBack = ::onBack,
        isShieldedRecipient = transactionProposal.destination.isShielded(),
    )

    /**
     * QA2 Q2-18 (ZC-1419, ZC-1427; W-137 B-1): an amount at or below the marginal fee is one the recipient's wallet
     * cannot spend or count. A warning item, never a refusal: the send goes on as the user chooses.
     */
    private fun dustWarning(amount: Zatoshi): MessageState? =
        if (DustAmount.isUnspendable(amount.value)) {
            MessageState(
                title = stringRes(R.string.send_amount_label),
                message = stringRes(R.string.send_amount_dust_warning, stringRes(Zatoshi(Zip317.MARGINAL_FEE_ZATOSHI)))
            )
        } else {
            null
        }

    /**
     * ZC-056 (T7b triage 13 Oct): whether the "This payment is shielded" card may be shown. A TEX (exchange) address is
     * not shielded: its legs are transparent, through an ephemeral transparent address.
     */
    private fun WalletAddress.isShielded(): Boolean = this !is WalletAddress.Transparent && this !is WalletAddress.Tex

    private fun onExpandReceiverClick() = isReceiverExpanded.update { !it }

    private fun onBack() = viewModelScope.launch { cancelProposalFlow(clearSendForm = false) }

    // ZC-101 (money): [proposal] is the instance the on-screen state was built from — captured in
    // the button's onClick closure at render time, BEFORE the biometric window opens inside
    // submitProposal. Passing it through guarantees the submitted proposal is the one the user saw
    // and authorized, even if the shared proposal cell is overwritten while the prompt is up.
    private fun onConfirmClick(proposal: SendTransactionProposal) {
        if (onConfirmClickJob?.isActive == true) return
        onConfirmClickJob =
            viewModelScope.launch {
                if (submitProposal(proposal) == SubmitProposalUseCase.Outcome.CANCELLED) sendCancelledEvents.tryEmit(Unit)
            }
    }

    private fun onAddContactClick(address: String) = navigationRouter.forward(AddZashiABContactArgs(address))
}
