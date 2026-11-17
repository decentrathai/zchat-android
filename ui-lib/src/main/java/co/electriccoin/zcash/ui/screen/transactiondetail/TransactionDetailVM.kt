package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.DustAmount
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapStatus.EXPIRED
import co.electriccoin.zcash.ui.common.model.SwapStatus.FAILED
import co.electriccoin.zcash.ui.common.model.SwapStatus.INCOMPLETE_DEPOSIT
import co.electriccoin.zcash.ui.common.model.SwapStatus.PENDING
import co.electriccoin.zcash.ui.common.model.SwapStatus.PROCESSING
import co.electriccoin.zcash.ui.common.model.SwapStatus.REFUNDED
import co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.tex.PlainSendVerdict
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.TexState
import co.electriccoin.zcash.ui.common.tex.TexStatus
import co.electriccoin.zcash.ui.common.tex.forTransaction
import co.electriccoin.zcash.ui.common.tex.texSendAgainAllowed
import co.electriccoin.zcash.ui.common.tex.texTextsOf
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.DetailedTransactionData
import co.electriccoin.zcash.ui.common.usecase.FlipTransactionBookmarkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionDetailByIdUseCase
import co.electriccoin.zcash.ui.common.usecase.MarkTxMemoAsReadUseCase
import co.electriccoin.zcash.ui.common.usecase.SendTransactionAgainUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.chat.model.memoDisplayText
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveShieldedState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveTransparentState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendShieldedState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendSwapState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendTransparentState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ShieldingState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailInfoState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailMemoState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailMemosState
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Suppress("TooManyFunctions")
class TransactionDetailVM(
    getTransactionDetailById: GetTransactionDetailByIdUseCase,
    private val markTxMemoAsRead: MarkTxMemoAsReadUseCase,
    private val transactionDetailArgs: TransactionDetailArgs,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val sendTransactionAgain: SendTransactionAgainUseCase,
    private val flipTransactionBookmark: FlipTransactionBookmarkUseCase,
    private val mapper: CommonTransactionDetailMapper,
    // F3 (fix-all 2.13.0): the TEX checker. A leg of a TEX send shows its state, and "Send again" only when the money
    // is provably where the user thinks it is (texSendAgainAllowed).
    private val texSendMonitor: TexSendMonitor,
) : ViewModel() {
    private val transaction =
        getTransactionDetailById
            .observe(transactionDetailArgs.transactionId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    /** F3: the TEX send this transaction is a leg of, if any. */
    private val texStatus =
        texSendMonitor.statuses.map { it.forTransaction(transactionDetailArgs.transactionId) }.distinctUntilChanged()

    /**
     * F3: the TEX send this transaction is a leg of, if any, and (T3-fixup, G-F3-1) the checker's last completed pass,
     * which decides "Send again": null until a pass has read this wallet.
     */
    private val texCheck =
        texSendMonitor.check
            .map { check -> check?.statuses?.forTransaction(transactionDetailArgs.transactionId) to check }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state =
        combine(transaction.filterNotNull(), texCheck) { transaction, (tex, check) -> Triple(transaction, tex, check) }
            .mapLatest { (transaction, tex, check) ->
                var info = createTransactionInfoState(transaction)
                TransactionDetailState(
                    onBack = ::onBack,
                    info = info,
                    header = createTransactionHeaderState(transaction, info),
                    primaryButton = createPrimaryButtonState(transaction, check),
                    secondaryButton = createSecondaryButtonState(transaction),
                    bookmarkButton =
                        IconButtonState(
                            icon =
                                if (transaction.metadata.isBookmarked) {
                                    R.drawable.ic_transaction_detail_bookmark
                                } else {
                                    R.drawable.ic_transaction_detail_no_bookmark
                                },
                            // QA2 Q2-05 (ZC-1599): the label says what a tap does now.
                            contentDescription =
                                if (transaction.metadata.isBookmarked) {
                                    stringRes(R.string.transaction_detail_bookmark_remove)
                                } else {
                                    stringRes(R.string.transaction_detail_bookmark_add)
                                },
                            onClick = ::onBookmarkClick,
                            hapticFeedbackType =
                                if (transaction.metadata.isBookmarked) {
                                    HapticFeedbackType.ToggleOff
                                } else {
                                    HapticFeedbackType.ToggleOn
                                }
                        ),
                    errorFooter = createTexFooter(tex) ?: createPlainSendFooter(transaction, check) ?: createErrorFooter(transaction)
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createEmptyState()
            )

    init {
        // F3: reading the detail of a finished recovery reads its notice (Home stops showing it).
        viewModelScope.launch {
            val tex = texStatus.filterNotNull().first()
            if (tex.state == TexState.SHIELDED && !tex.noticeShown) texSendMonitor.acknowledgeNotice(tex.key)
        }
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val transaction = transaction.filterNotNull().first()
                if (transaction.transaction.memoCount > 0) {
                    markTxMemoAsRead(transactionDetailArgs.transactionId)
                }
            }
        }
    }

    private fun createEmptyState(): TransactionDetailState =
        TransactionDetailState(
            onBack = ::onBack,
            bookmarkButton = null,
            header =
                TransactionDetailHeaderState(
                    title = null,
                    amount = null,
                    icons =
                        listOf(
                            loadingImageRes(),
                            loadingImageRes(),
                            loadingImageRes(),
                        )
                ),
            info = null,
            errorFooter = null,
            primaryButton = null,
            secondaryButton = null,
        )

    private fun onAddOrEditNoteClick() {
        navigationRouter.forward(TransactionNote(transactionDetailArgs.transactionId))
    }

    @Suppress("CyclomaticComplexMethod")
    private fun createTransactionInfoState(transaction: DetailedTransactionData): TransactionDetailInfoState =
        when (transaction.transaction) {
            is SendTransaction -> {
                when {
                    transaction.swap != null -> {
                        val recipient =
                            transaction.swap.status
                                ?.quote
                                ?.destinationAddress
                                ?.address
                        SendSwapState(
                            status = transaction.swap.status?.status,
                            quoteHeader =
                                mapper.createTransactionDetailQuoteHeaderState(
                                    swap = transaction.swap.status,
                                    originAsset = transaction.swap.originAsset,
                                    destinationAsset = transaction.swap.destinationAsset
                                ),
                            depositAddress =
                                stringResByAddress(
                                    value = transaction.recipient?.address.orEmpty()
                                ),
                            totalFees =
                                transaction.metadata.swapMetadata
                                    ?.totalFees
                                    ?.let { stringRes(it) },
                            recipientAddress = recipient?.let { stringResByAddress(it) },
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            refundedAmount =
                                transaction.swap.status
                                    ?.refundedFormatted
                                    ?.let {
                                        stringResByCurrencyNumber(amount = it, ticker = CURRENCY_TICKER)
                                    }?.takeIf {
                                        transaction.swap.status.status == REFUNDED
                                    },
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onDepositAddressClick = {
                                onCopyToClipboard(transaction.recipient?.address.orEmpty())
                            },
                            onRecipientAddressClick =
                                if (recipient == null) {
                                    null
                                } else {
                                    { onCopyToClipboard(recipient) }
                                },
                            maxSlippage =
                                transaction.swap.status?.maxSlippage?.let {
                                    stringResByNumber(it, 0) + stringRes("%")
                                },
                            note = transaction.metadata.note?.let { stringRes(it) },
                            isSlippageRealized = transaction.swap.status?.isSlippageRealized == true,
                            isPending = isPending(transaction),
                            completedTimestamp = createTimestampStringRes(transaction),
                        )
                    }

                    transaction.recipient is WalletAddress.Transparent ->
                        SendTransparentState(
                            contact = transaction.contact?.let { stringRes(it.name) },
                            address = stringRes(transaction.recipient.address),
                            addressAbbreviated = stringResByAddress(transaction.recipient.address),
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onTransactionAddressClick = { onCopyToClipboard(transaction.recipient.address) },
                            fee = createFeeStringRes(transaction),
                            completedTimestamp = createTimestampStringRes(transaction),
                            isPending = isPending(transaction),
                            note = transaction.metadata.note?.let { stringRes(it) },
                        )

                    else ->
                        SendShieldedState(
                            contact = transaction.contact?.let { stringRes(it.name) },
                            address =
                                stringResByAddress(
                                    value = transaction.recipient?.address.orEmpty()
                                ),
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onTransactionAddressClick = {
                                onCopyToClipboard(transaction.recipient?.address.orEmpty())
                            },
                            fee = createFeeStringRes(transaction),
                            completedTimestamp = createTimestampStringRes(transaction),
                            memo =
                                TransactionDetailMemosState(
                                    transaction.memos?.map { memo ->
                                        val display = memoDisplayText(memo)
                                        TransactionDetailMemoState(
                                            content = display,
                                            onClick = { copyToClipboard(display) }
                                        )
                                    }
                                ).takeIf { transaction.transaction.memoCount > 0 },
                            note = transaction.metadata.note?.let { stringRes(it) },
                            isPending = isPending(transaction)
                        )
                }
            }

            is ReceiveTransaction -> {
                if (transaction.transaction.transactionOutputs.all { it.pool == TransactionPool.TRANSPARENT }) {
                    ReceiveTransparentState(
                        transactionId =
                            stringResByTransactionId(
                                value = transaction.transaction.id.txIdString(),
                                abbreviated = true
                            ),
                        onTransactionIdClick = {
                            onCopyToClipboard(transaction.transaction.id.txIdString())
                        },
                        completedTimestamp = createTimestampStringRes(transaction),
                        note = transaction.metadata.note?.let { stringRes(it) },
                        isPending = isPending(transaction)
                    )
                } else {
                    ReceiveShieldedState(
                        transactionId =
                            stringResByTransactionId(
                                value = transaction.transaction.id.txIdString(),
                                abbreviated = true
                            ),
                        onTransactionIdClick = {
                            onCopyToClipboard(transaction.transaction.id.txIdString())
                        },
                        completedTimestamp = createTimestampStringRes(transaction),
                        memo =
                            TransactionDetailMemosState(
                                transaction.memos?.map { memo ->
                                    val display = memoDisplayText(memo)
                                    TransactionDetailMemoState(
                                        content = display,
                                        onClick = { copyToClipboard(display) }
                                    )
                                }
                            ).takeIf { transaction.transaction.memoCount > 0 },
                        note = transaction.metadata.note?.let { stringRes(it) },
                        isPending = isPending(transaction),
                        tooSmallNote = createTooSmallNote(transaction),
                    )
                }
            }

            is ShieldTransaction -> {
                ShieldingState(
                    transactionId =
                        stringResByTransactionId(
                            value = transaction.transaction.id.txIdString(),
                            abbreviated = true
                        ),
                    onTransactionIdClick = {
                        onCopyToClipboard(transaction.transaction.id.txIdString())
                    },
                    completedTimestamp = createTimestampStringRes(transaction),
                    fee = createFeeStringRes(transaction),
                    note = transaction.metadata.note?.let { stringRes(it) },
                    isPending = isPending(transaction)
                )
            }
        }

    // QA2 Q2-09 (ZC-1600, ZC-731; W-137): the exact fee the transaction paid; without one, "fee not known yet",
    // never a rounded or typed figure.
    private fun createFeeStringRes(data: DetailedTransactionData): StringResource {
        val feePaid =
            data.transaction.fee.takeIf { data.transaction !is ReceiveTransaction }
                ?: return if (isPending(data) && !isExpired(data)) {
                    // Only a transaction still waiting for the chain may learn its fee later.
                    stringRes(R.string.transaction_detail_fee_unknown)
                } else {
                    stringRes(R.string.transaction_detail_fee_not_known)
                }
        return stringRes(R.string.transaction_detail_fee, stringRes(feePaid, HIDDEN), CURRENCY_TICKER)
    }

    // QA2 Q2-18 (ZC-1419, ZC-1427): a receipt worth the marginal fee or less is never counted in the balance; say so.
    private fun createTooSmallNote(data: DetailedTransactionData): StringResource? =
        if (DustAmount.isUnspendable(data.transaction.amount.value)) {
            stringRes(R.string.transaction_detail_received_too_small, stringRes(Zatoshi(Zip317.MARGINAL_FEE_ZATOSHI)))
        } else {
            null
        }

    // T3b-2: the time row follows the transaction's state (an expired one never says "Completed").
    private fun createTimestampStringRes(data: DetailedTransactionData) = transactionTimeRowOf(data.transaction, mapper).message

    private fun isPending(data: DetailedTransactionData) = transactionTimeRowOf(data.transaction, mapper).isStatus

    private fun isExpired(data: DetailedTransactionData) =
        data.transaction.let {
            it is SendTransaction.Failed || it is ReceiveTransaction.Failed || it is ShieldTransaction.Failed
        }

    private fun onCopyToClipboard(text: String) {
        copyToClipboard(
            value = text
        )
    }

    /** F3: what a leg of a TEX send says about the send (its state, in full). */
    private fun createTexFooter(tex: TexStatus?): ErrorFooter? {
        val texts = tex?.let { texTextsOf(it, Locale.getDefault()) } ?: return null
        return ErrorFooter(
            title = stringRes(texts.title.resId, *texts.title.args.toTypedArray()),
            subtitle = stringRes(texts.body.resId, *texts.body.args.toTypedArray()),
        )
    }

    /** F5: a plain send's standing by the checker's last pass (waiting, checking, or proven never mined). */
    private fun createPlainSendFooter(
        data: DetailedTransactionData,
        check: TexCheck?,
    ): ErrorFooter? =
        if (data.transaction is SendTransaction && data.swap == null) {
            plainSendFooter(transactionDetailArgs.transactionId, check, expired = data.transaction is SendTransaction.Failed)
        } else {
            null
        }

    private fun createErrorFooter(data: DetailedTransactionData): ErrorFooter? =
        mapper.createTransactionDetailErrorFooter(data.swap?.error)

    // F3: "Send again" is withheld while a TEX send's money may still arrive, is checked or moved, or needs support;
    // T3-fixup (G-F3-1): and while the checker has not classified this transaction (no pass read it yet).
    // F5: a plain send only when it is mined (Zashi's repeat payment) or proven never mined and not used yet, and never
    // for a proven never-mined chat transaction (the chat's Retry or Pay again is its one new attempt).
    private fun createPrimaryButtonState(
        data: DetailedTransactionData,
        check: TexCheck?,
    ): ButtonState? =
        when {
            data.swap?.error != null && data.swap.status == null ->
                mapper.createTransactionDetailErrorButtonState(
                    error = data.swap.error,
                    reloadHandle = data.reloadHandle
                )

            data.swap != null -> null

            data.contact == null ->
                if (data.transaction is SendTransaction) {
                    ButtonState(
                        text = stringRes(R.string.transaction_detail_save_address),
                        onClick = { onSaveAddressClick(data) }
                    )
                } else {
                    null
                }

            else ->
                if (data.transaction is SendTransaction &&
                    !chatOwnsTheNextAttempt(transactionDetailArgs.transactionId, check, data.memos, data.transaction.memoCount) &&
                    texSendAgainAllowed(transactionDetailArgs.transactionId, check)
                ) {
                    ButtonState(
                        text = stringRes(R.string.transaction_detail_send_again),
                        onClick = { onSendAgainClick(data, check) }
                    )
                } else {
                    null
                }
        }

    private fun createSecondaryButtonState(transaction: DetailedTransactionData): ButtonState? {
        fun createAddNoteButtonState() =
            ButtonState(
                text =
                    if (transaction.metadata.note != null) {
                        stringRes(R.string.transaction_detail_edit_note)
                    } else {
                        stringRes(R.string.transaction_detail_add_a_note)
                    },
                onClick = ::onAddOrEditNoteClick
            )

        return when {
            transaction.swap != null && transaction.swap.error == null -> createAddNoteButtonState()
            transaction.swap != null -> null
            else -> createAddNoteButtonState()
        }
    }

    private fun onSaveAddressClick(transaction: DetailedTransactionData) {
        transaction.recipient?.let {
            navigationRouter.forward(AddZashiABContactArgs(it.address))
        }
    }

    /**
     * F5: on a send proven never mined, "Send again" is marked used (hashed, durable) BEFORE the pre-fill, so the detail
     * never offers it a second time; the pre-fill sends nothing (the user confirms the new send with its own quote).
     * ZC-400: only the tap the checker answers first pre-fills, so two quick taps open one Send form. A mined send's
     * repeat "Send again" is unchanged.
     */
    private fun onSendAgainClick(
        transaction: DetailedTransactionData,
        check: TexCheck?,
    ) {
        val txId = transactionDetailArgs.transactionId
        val provenNotSent = check?.plainSends?.get(txId) == PlainSendVerdict.NotSent && txId !in check.minedSends
        viewModelScope.launch {
            if (provenNotSent && !texSendMonitor.markSendAgainUsed(txId)) return@launch
            sendTransactionAgain(transaction)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun createTransactionHeaderState(
        data: DetailedTransactionData,
        info: TransactionDetailInfoState
    ): TransactionDetailHeaderState =
        TransactionDetailHeaderState(
            title =
                when (val transaction = data.transaction) {
                    is ReceiveTransaction.Success -> stringRes(R.string.transaction_history_received)
                    is ReceiveTransaction.Pending -> stringRes(R.string.transaction_detail_receiving)
                    is ReceiveTransaction.Failed -> stringRes(R.string.transaction_history_receiving_failed)
                    is ShieldTransaction.Success -> stringRes(R.string.transaction_history_shielded)
                    is ShieldTransaction.Pending -> stringRes(R.string.transaction_detail_shielding)
                    is ShieldTransaction.Failed -> stringRes(R.string.transaction_history_shielding_failed)
                    is SendTransaction -> {
                        if (data.metadata.swapMetadata == null) {
                            when (transaction) {
                                is SendTransaction.Success -> stringRes(R.string.transaction_history_sent)
                                is SendTransaction.Pending -> stringRes(R.string.transaction_detail_sending)
                                is SendTransaction.Failed -> stringRes(R.string.transaction_history_sending_failed)
                            }
                        } else {
                            if (transaction is SendTransaction.Failed) {
                                when (data.metadata.swapMetadata.mode) {
                                    EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.transaction_history_swap_failed)
                                    EXACT_OUTPUT -> stringRes(R.string.transaction_history_payment_failed)
                                }
                            } else {
                                when (data.metadata.swapMetadata.mode) {
                                    EXACT_INPUT, FLEX_INPUT ->
                                        when (data.metadata.swapMetadata.status) {
                                            INCOMPLETE_DEPOSIT,
                                            PROCESSING,
                                            PENDING -> stringRes(R.string.transaction_detail_swapping)

                                            SUCCESS -> stringRes(R.string.transaction_history_swapped)
                                            REFUNDED -> stringRes(R.string.transaction_history_swap_refunded)
                                            FAILED -> stringRes(R.string.transaction_history_swap_failed)
                                            EXPIRED -> stringRes(R.string.transaction_history_swap_expired)
                                        }

                                    EXACT_OUTPUT ->
                                        when (data.metadata.swapMetadata.status) {
                                            INCOMPLETE_DEPOSIT,
                                            PROCESSING,
                                            PENDING -> stringRes(R.string.transaction_detail_paying)

                                            SUCCESS -> stringRes(R.string.transaction_history_paid)
                                            REFUNDED -> stringRes(R.string.transaction_history_payment_refunded)
                                            FAILED -> stringRes(R.string.transaction_history_payment_failed)
                                            EXPIRED -> stringRes(R.string.transaction_history_payment_expired)
                                        }
                                }
                            }
                        }
                    }
                },
            amount =
                stringRes(data.transaction.amount, HIDDEN),
            icons =
                when (info) {
                    is ReceiveShieldedState,
                    is ReceiveTransparentState -> {
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(R.drawable.ic_transaction_received)
                        )
                    }

                    is SendSwapState ->
                        listOf(
                            data.metadata.swapMetadata
                                ?.origin
                                ?.tokenIcon ?: loadingImageRes(),
                            when (data.metadata.swapMetadata?.mode) {
                                SwapMode.EXACT_INPUT, SwapMode.FLEX_INPUT -> imageRes(R.drawable.ic_transaction_sent)
                                SwapMode.EXACT_OUTPUT -> imageRes(R.drawable.ic_transaction_paid)
                                null -> imageRes(R.drawable.ic_transaction_sent)
                            },
                            data.metadata.swapMetadata
                                ?.destination
                                ?.tokenIcon ?: loadingImageRes()
                        )

                    is SendShieldedState,
                    is SendTransparentState ->
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(R.drawable.ic_transaction_sent)
                        )

                    is ShieldingState ->
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(R.drawable.ic_transaction_shielded),
                            imageRes(R.drawable.ic_transaction_detail_shielded),
                        )
                },
        )

    private fun onBack() = navigationRouter.back()

    private fun onBookmarkClick() =
        viewModelScope.launch {
            flipTransactionBookmark(transactionDetailArgs.transactionId)
        }
}
