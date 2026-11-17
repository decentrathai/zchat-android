package co.electriccoin.zcash.ui.screen.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.ComputeSendMaxUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendAddressBookState
import co.electriccoin.zcash.ui.screen.send.model.SendAllState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import co.electriccoin.zcash.ui.screen.send.model.toWalletAddressOrNull
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class SendViewModel(
    exchangeRateRepository: ExchangeRateRepository,
    private val observeContactByAddress: ObserveContactByAddressUseCase,
    private val observeContactPicked: ObserveABContactPickedUseCase,
    private val createProposal: CreateProposalUseCase,
    private val observeWalletAccounts: GetWalletAccountsUseCase,
    private val navigateToSelectRecipient: NavigateToSelectRecipientUseCase,
    private val navigationRouter: NavigationRouter,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val computeSendMax: ComputeSendMaxUseCase,
) : ViewModel() {
    val recipientAddressState = MutableStateFlow(RecipientAddressState.new("", null))

    private var onCreateZecSendClickJob: Job? = null

    /** The last Send-all tap's outcome while it matters: Calculating, or Unavailable until an edit. */
    private val sendAllSearch = MutableStateFlow<SendAllState?>(null)

    private var sendAllJob: Job? = null

    /** The last Send-all fill and the recipient kind it was priced for; null once the user edits. */
    private var sendAllFill: Pair<SendMaxRecipient, Zatoshi>? = null

    val sendAllState: StateFlow<SendAllState> =
        combine(
            getSelectedWalletAccount.observe(),
            recipientAddressState,
            sendAllSearch,
        ) { account, recipient, search ->
            SendAllState.of(account = account, recipient = SendMaxRecipient.of(recipient.type), search = search)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            SendAllState.Hidden
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val sendAddressBookState =
        recipientAddressState
            .flatMapLatest { recipientAddressState ->
                combine(
                    observeWalletAccounts.require(),
                    observeContactByAddress(recipientAddressState.address)
                ) { accounts, contact -> accounts to contact }
                    .flatMapLatest { (accounts, contact) ->
                        flow {
                            val exists =
                                contact != null ||
                                    accounts.any { it.unified.address.address == recipientAddressState.address }
                            val isValid = recipientAddressState.type?.isNotValid == false
                            val mode =
                                if (isValid) {
                                    if (exists) {
                                        SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK
                                    } else {
                                        SendAddressBookState.Mode.ADD_TO_ADDRESS_BOOK
                                    }
                                } else {
                                    SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK
                                }
                            val isHintVisible = !exists && isValid

                            emit(
                                SendAddressBookState(
                                    mode = mode,
                                    isHintVisible = isHintVisible,
                                    onButtonClick = { onAddressBookButtonClicked(mode, recipientAddressState) }
                                )
                            )

                            if (isHintVisible) {
                                delay(3.seconds)
                                emit(
                                    SendAddressBookState(
                                        mode = mode,
                                        isHintVisible = false,
                                        onButtonClick = { onAddressBookButtonClicked(mode, recipientAddressState) }
                                    )
                                )
                            }
                        }
                    }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                SendAddressBookState(
                    mode = SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK,
                    isHintVisible = false,
                    onButtonClick = {
                        onAddressBookButtonClicked(
                            mode = SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK,
                            recipient = recipientAddressState.value
                        )
                    }
                )
            )

    init {
        viewModelScope.launch {
            observeContactPicked().collect {
                onRecipientAddressChanged(it)
            }
        }
        exchangeRateRepository.refreshExchangeRateUsd()
    }

    private fun onAddressBookButtonClicked(mode: SendAddressBookState.Mode, recipient: RecipientAddressState) {
        when (mode) {
            SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK -> viewModelScope.launch { navigateToSelectRecipient() }
            SendAddressBookState.Mode.ADD_TO_ADDRESS_BOOK ->
                navigationRouter.forward(AddZashiABContactArgs(recipient.address))
        }
    }

    fun onRecipientAddressChanged(state: RecipientAddressState) {
        if (state != recipientAddressState.value) cancelSendAll()
        recipientAddressState.update { state }
    }

    /**
     * Price the exact maximum for the current recipient and hand it to [onFilled]. Off the main thread;
     * any edit to the amount, the memo or the recipient cancels it. It never fills 0: when nothing can
     * be sent the link shows why instead.
     */
    fun onSendAllClick(onFilled: (Zatoshi) -> Unit) {
        val recipient = recipientAddressState.value
        val kind = SendMaxRecipient.of(recipient.type) ?: return
        if (sendAllState.value != SendAllState.Ready) return
        sendAllJob?.cancel()
        sendAllSearch.value = SendAllState.Calculating
        sendAllJob =
            viewModelScope.launch {
                val destination = recipient.toWalletAddressOrNull()
                val result =
                    if (destination == null) {
                        SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
                    } else {
                        computeSendMax(destination = destination, recipient = kind)
                    }
                when (result) {
                    is SendMaxResult.Max -> {
                        sendAllSearch.value = null
                        sendAllFill = kind to result.amount
                        onFilled(result.amount)
                    }
                    is SendMaxResult.Unavailable -> sendAllSearch.value = SendAllState.Unavailable(result.reason)
                }
            }
    }

    /** The user changed the amount or the memo: stop any pricing and forget the last outcome. */
    fun onFormEdited() {
        sendAllFill = null
        cancelSendAll()
    }

    /**
     * True, once, when [amount] is still the Send-all fill but [recipient] is now a different kind than
     * the one it was priced for: its fee no longer applies, so the caller clears the field. An amount
     * that has since been replaced (typed, prefilled, cleared) is left alone.
     */
    fun consumeStaleSendAllFill(recipient: RecipientAddressState, amount: AmountState): Boolean {
        val fill = sendAllFill ?: return false
        val isRepriced = SendMaxRecipient.of(recipient.type) != fill.first
        if (isRepriced) sendAllFill = null
        return isRepriced && (amount as? AmountState.Valid)?.zatoshi == fill.second
    }

    private fun cancelSendAll() {
        sendAllJob?.cancel()
        sendAllJob = null
        sendAllSearch.value = null
    }

    @Suppress("TooGenericExceptionCaught")
    fun onCreateZecSendClick(
        newZecSend: ZecSend,
        amountState: AmountState,
        setSendStage: (SendStage) -> Unit
    ) {
        if (onCreateZecSendClickJob?.isActive == true) return
        onCreateZecSendClickJob =
            viewModelScope.launch {
                val currentAccount = getSelectedWalletAccount()
                val address = newZecSend.destination
                // destination is a WalletAddress; comparing it to the unrelated AddressType.Tex was
                // always false, so Keystone+TEX never reached the TEXUnsupported explainer.
                if (currentAccount is KeystoneAccount && address is WalletAddress.Tex) {
                    navigationRouter.forward(TEXUnsupportedArgs)
                } else {
                    try {
                        createProposal(newZecSend, amountState.lastFieldChangedByUser == AmountField.FIAT)
                    } catch (e: Exception) {
                        // Emit a stable, locale-independent reason key instead of the raw (English-only)
                        // exception text; the view maps it to a localized message. See #1276.
                        val reasonKey = SendStage.reasonFor(e)
                        setSendStage(SendStage.SendFailure(reasonKey))
                        Twig.error(e) { "Error creating proposal" }
                    }
                }
            }
    }
}
