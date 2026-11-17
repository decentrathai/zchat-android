@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.send.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.model.ZecSendExt
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.sdk.fixture.ZatoshiFixture
import cash.z.ecc.sdk.type.ZcashCurrency
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppbar
import co.electriccoin.zcash.ui.common.model.DustAmount
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.AmountReadingChooser
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiTextField
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.amountErrorText
import co.electriccoin.zcash.ui.design.component.rememberAmountTextFieldSelection
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.dimensions.ZashiDimensions
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orHidden
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.fixture.BalanceStateFixture
import co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture
import co.electriccoin.zcash.ui.screen.balances.BalanceWidget
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.send.SendTag
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendAddressBookState
import co.electriccoin.zcash.ui.screen.send.model.SendAllState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import co.electriccoin.zcash.ui.screen.send.model.toWalletAddressOrNull
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Locale

@Composable
@Preview("SendForm")
private fun PreviewSendForm() {
    ZcashTheme(forceDarkMode = false) {
        Send(
            balanceWidgetState = BalanceStateFixture.new(),
            sendStage = SendStage.Form,
            onCreateZecSend = {},
            onBack = {},
            onQrScannerOpen = {},
            hasCameraFeature = true,
            recipientAddressState = RecipientAddressState("invalid_address", AddressType.Invalid()),
            onRecipientAddressChange = {},
            setAmountState = {},
            amountState =
                AmountState.Valid(
                    value = ZatoshiFixture.ZATOSHI_LONG.toString(),
                    fiatValue = "",
                    zatoshi = ZatoshiFixture.new(),
                    lastFieldChangedByUser = AmountField.FIAT
                ),
            setMemoState = {},
            memoState = MemoState.new("Test message "),
            selectedAccount = null,
            exchangeRateState = ExchangeRateState.OptedOut,
            sendAddressBookState =
                SendAddressBookState(
                    mode = SendAddressBookState.Mode.ADD_TO_ADDRESS_BOOK,
                    isHintVisible = true,
                    onButtonClick = {}
                ),
            zashiMainTopAppBarState = ZashiMainTopAppBarStateFixture.new()
        )
    }
}

@Composable
@Preview
private fun SendFormTransparentAddressPreview() {
    ZcashTheme(forceDarkMode = false) {
        Send(
            balanceWidgetState = BalanceStateFixture.new(),
            sendStage = SendStage.Form,
            onCreateZecSend = {},
            onBack = {},
            onQrScannerOpen = {},
            hasCameraFeature = true,
            recipientAddressState =
                RecipientAddressState(
                    address = "tmCxJG72RWN66xwPtNgu4iKHpyysGrc7rEg",
                    type = AddressType.Transparent
                ),
            onRecipientAddressChange = {},
            setAmountState = {},
            amountState =
                AmountState.Valid(
                    value = ZatoshiFixture.ZATOSHI_LONG.toString(),
                    fiatValue = "",
                    zatoshi = ZatoshiFixture.new(),
                    lastFieldChangedByUser = AmountField.FIAT
                ),
            setMemoState = {},
            memoState = MemoState.new("Test message"),
            selectedAccount = null,
            exchangeRateState = ExchangeRateState.OptedOut,
            sendAddressBookState =
                SendAddressBookState(
                    mode = SendAddressBookState.Mode.ADD_TO_ADDRESS_BOOK,
                    isHintVisible = true,
                    onButtonClick = {}
                ),
            zashiMainTopAppBarState = ZashiMainTopAppBarStateFixture.new()
        )
    }
}

// TODO [#1260]: Cover Send screens UI with tests
// TODO [#1260]: https://github.com/Electric-Coin-Company/zashi-android/issues/1260
@Suppress("LongParameterList")
@Composable
fun Send(
    balanceWidgetState: BalanceWidgetState,
    sendStage: SendStage,
    onCreateZecSend: (ZecSend) -> Unit,
    onBack: () -> Unit,
    onQrScannerOpen: () -> Unit,
    hasCameraFeature: Boolean,
    recipientAddressState: RecipientAddressState,
    onRecipientAddressChange: (String) -> Unit,
    setAmountState: (AmountState) -> Unit,
    amountState: AmountState,
    setMemoState: (MemoState) -> Unit,
    memoState: MemoState,
    selectedAccount: WalletAccount?,
    exchangeRateState: ExchangeRateState,
    sendAddressBookState: SendAddressBookState,
    zashiMainTopAppBarState: ZashiMainTopAppBarState?,
    sendAllState: SendAllState = SendAllState.Hidden,
    onSendAllClick: () -> Unit = {},
    // QA2 Q2-97 (ZC-1669): opened from More > Support ZCHAT with the donation address prefilled, and whether the
    // recipient field still holds that address.
    isSupportSend: Boolean = false,
    isDonationAddressPrefilled: Boolean = false,
) {
    if (selectedAccount == null) {
        return
    }

    BlankBgScaffold(topBar = {
        ZashiTopAppbar(
            title = stringRes(R.string.settings_support_zchat).takeIf { isSupportSend && isDonationAddressPrefilled },
            state = zashiMainTopAppBarState,
            onBack = onBack
        )
    }) { paddingValues ->
        SendMainContent(
            balanceWidgetState = balanceWidgetState,
            selectedAccount = selectedAccount,
            exchangeRateState = exchangeRateState,
            onBack = onBack,
            onCreateZecSend = onCreateZecSend,
            sendStage = sendStage,
            onQrScannerOpen = onQrScannerOpen,
            recipientAddressState = recipientAddressState,
            onRecipientAddressChange = onRecipientAddressChange,
            hasCameraFeature = hasCameraFeature,
            amountState = amountState,
            setAmountState = setAmountState,
            memoState = memoState,
            setMemoState = setMemoState,
            sendState = sendAddressBookState,
            sendAllState = sendAllState,
            onSendAllClick = onSendAllClick,
            isDonationAddressPrefilled = isSupportSend && isDonationAddressPrefilled,
            modifier = Modifier.scaffoldPadding(paddingValues)
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun SendMainContent(
    balanceWidgetState: BalanceWidgetState,
    selectedAccount: WalletAccount,
    exchangeRateState: ExchangeRateState,
    onBack: () -> Unit,
    onCreateZecSend: (ZecSend) -> Unit,
    sendStage: SendStage,
    onQrScannerOpen: () -> Unit,
    recipientAddressState: RecipientAddressState,
    onRecipientAddressChange: (String) -> Unit,
    hasCameraFeature: Boolean,
    amountState: AmountState,
    setAmountState: (AmountState) -> Unit,
    memoState: MemoState,
    setMemoState: (MemoState) -> Unit,
    sendState: SendAddressBookState,
    sendAllState: SendAllState,
    onSendAllClick: () -> Unit,
    isDonationAddressPrefilled: Boolean,
    modifier: Modifier = Modifier,
) {
    // For now, we merge [SendStage.Form] and [SendStage.Proposing] into one stage. We could eventually display a
    // loader if calling the Proposal API takes longer than expected

    SendForm(
        balanceWidgetState = balanceWidgetState,
        selectedAccount = selectedAccount,
        recipientAddressState = recipientAddressState,
        exchangeRateState = exchangeRateState,
        onRecipientAddressChange = onRecipientAddressChange,
        amountState = amountState,
        setAmountState = setAmountState,
        memoState = memoState,
        setMemoState = setMemoState,
        onCreateZecSend = onCreateZecSend,
        onQrScannerOpen = onQrScannerOpen,
        hasCameraFeature = hasCameraFeature,
        sendState = sendState,
        sendAllState = sendAllState,
        onSendAllClick = onSendAllClick,
        isDonationAddressPrefilled = isDonationAddressPrefilled,
        modifier = modifier
    )

    if (sendStage is SendStage.SendFailure && sendStage.error == SendStage.REASON_UPDATE_REQUIRED) {
        AppAlertDialog(
            title = stringResource(R.string.update_required_title),
            text = { Text(text = stringResource(R.string.update_required_refusal)) },
            confirmButtonText = stringResource(R.string.send_dialog_error_btn),
            onConfirmButtonClick = onBack,
        )
    } else if (sendStage is SendStage.SendFailure) {
        // Map the VM's locale-independent reason key to a localized message; unknown keys fall back
        // to a generic localized message rather than leaking raw English exception text. See #1276.
        val reason =
            when (sendStage.error) {
                SendStage.REASON_INSUFFICIENT_FUNDS ->
                    stringResource(R.string.send_dialog_error_reason_insufficient_funds)
                SendStage.REASON_PROPOSAL_NOT_CREATED ->
                    stringResource(R.string.send_dialog_error_reason_proposal)
                SendStage.REASON_ORCHARD_SEALED ->
                    stringResource(R.string.send_dialog_error_reason_orchard_sealed)
                SendStage.REASON_IRONWOOD_CONFIRMING ->
                    stringResource(R.string.send_dialog_error_reason_ironwood_confirming)
                SendStage.REASON_PUBLIC_CROSSING ->
                    stringResource(R.string.send_dialog_error_reason_public_crossing)
                SendStage.REASON_SAPLING_CROSSING ->
                    stringResource(R.string.send_dialog_error_reason_sapling_crossing)
                else ->
                    stringResource(R.string.send_dialog_error_reason_generic)
            }
        SendFailure(
            reason = reason,
            onConfirm = onBack
        )
    }
}

// TODO [#217]: Need to handle changing of Locale after user input, but before submitting the button.
// TODO [#217]: https://github.com/Electric-Coin-Company/zashi-android/issues/217

// TODO [#1257]: Send.Form TextFields not persisted on a configuration change when the underlying ViewPager is on the
//  Balances page
// TODO [#1257]: https://github.com/Electric-Coin-Company/zashi-android/issues/1257
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun SendForm(
    balanceWidgetState: BalanceWidgetState,
    selectedAccount: WalletAccount,
    recipientAddressState: RecipientAddressState,
    exchangeRateState: ExchangeRateState,
    onRecipientAddressChange: (String) -> Unit,
    amountState: AmountState,
    setAmountState: (AmountState) -> Unit,
    memoState: MemoState,
    setMemoState: (MemoState) -> Unit,
    onCreateZecSend: (ZecSend) -> Unit,
    onQrScannerOpen: () -> Unit,
    hasCameraFeature: Boolean,
    sendState: SendAddressBookState,
    sendAllState: SendAllState,
    onSendAllClick: () -> Unit,
    isDonationAddressPrefilled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(8.dp)

        BalanceWidget(
            state = balanceWidgetState
        )

        Spacer(24.dp)

        // TODO [#1256]: Consider Send.Form TextFields scrolling
        // TODO [#1256]: https://github.com/Electric-Coin-Company/zashi-android/issues/1256

        SendFormAddressTextField(
            hasCameraFeature = hasCameraFeature,
            onQrScannerOpen = onQrScannerOpen,
            recipientAddressState = recipientAddressState,
            setRecipientAddress = onRecipientAddressChange,
            sendAddressBookState = sendState
        )

        // QA2 Q2-97 (ZC-1669): name the prefilled address of the Support ZCHAT send.
        if (isDonationAddressPrefilled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.send_support_donation_address_label),
                color = ZashiColors.Text.textSecondary,
                style = ZashiTypography.textSm,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(visible = sendState.isHintVisible) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                SendAddressBookHint(Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.size(ZcashTheme.dimens.spacingDefault))

        val isMemoFieldAvailable =
            recipientAddressState.address.isEmpty() ||
                recipientAddressState.type is AddressType.Invalid ||
                (
                    recipientAddressState.type is AddressType.Valid &&
                        recipientAddressState.type !is AddressType.Transparent &&
                        recipientAddressState.type !is AddressType.Tex
                )

        SendFormAmountTextField(
            amountState = amountState,
            imeAction =
                if (recipientAddressState.type == AddressType.Transparent || !isMemoFieldAvailable) {
                    ImeAction.Done
                } else {
                    ImeAction.Next
                },
            isTransparentOrTextRecipient =
                recipientAddressState.type?.let {
                    it == AddressType.Transparent || it == AddressType.Tex
                } ?: false,
            exchangeRateState = exchangeRateState,
            setAmountState = setAmountState,
            selectedAccount = selectedAccount,
            sendAllState = sendAllState,
            onSendAllClick = onSendAllClick,
        )

        Spacer(Modifier.size(ZcashTheme.dimens.spacingDefault))

        SendFormMemoTextField(
            memoState = memoState,
            setMemoState = setMemoState,
            isMemoFieldAvailable = isMemoFieldAvailable,
        )

        // Non-blocking heads-up for the intentional 0-ZEC memo-only shielded send (see AmountState):
        // a zero amount + a message creates an on-chain message-only transaction. The Send button
        // intentionally stays enabled — this only informs the user.
        val isZeroAmountMemoOnly =
            amountState is AmountState.Valid &&
                amountState.zatoshi.value == 0L &&
                isMemoFieldAvailable &&
                memoState.text.isNotEmpty()

        AnimatedVisibility(visible = isZeroAmountMemoOnly) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.send_zero_amount_memo_warning),
                    color = ZashiColors.Inputs.Filled.required,
                    style = ZashiTypography.textSm,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // QA2 Q2-18 (ZC-1419, ZC-1427; W-137 B-1): an amount at or below the marginal fee is one the recipient's
        // wallet cannot spend or count. A warning only: the amount stays valid and Review stays enabled.
        val isDustAmount =
            amountState is AmountState.Valid && DustAmount.isUnspendable(amountState.zatoshi.value)

        AnimatedVisibility(visible = isDustAmount) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        stringRes(
                            R.string.send_amount_dust_warning,
                            stringRes(Zatoshi(Zip317.MARGINAL_FEE_ZATOSHI))
                        ).getValue(),
                    color = ZashiColors.Inputs.Filled.required,
                    style = ZashiTypography.textSm,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(
            modifier =
                Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.height(ZashiDimensions.Spacing.spacing3xl))

        SendButton(
            amountState = amountState,
            memoState = memoState,
            onCreateZecSend = onCreateZecSend,
            recipientAddressState = recipientAddressState,
            selectedAccount = selectedAccount,
        )
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun SendButton(
    amountState: AmountState,
    memoState: MemoState,
    onCreateZecSend: (ZecSend) -> Unit,
    recipientAddressState: RecipientAddressState,
    selectedAccount: WalletAccount,
) {
    val scope = rememberCoroutineScope()

    // Memo-less recipients (Transparent and Tex) hide the memo field entirely.
    val isMemolessRecipient =
        recipientAddressState.type == AddressType.Transparent ||
            recipientAddressState.type == AddressType.Tex

    // Common conditions continuously checked for validity
    val sendButtonEnabled =
        recipientAddressState.type !is AddressType.Invalid &&
            recipientAddressState.address.isNotEmpty() &&
            amountState is AmountState.Valid &&
            amountState.value.isNotBlank() &&
            // Only Ironwood can pay another person; the proposal decides the real fee.
            selectedAccount.peerSpendableBalance.value >=
            amountState.zatoshi.value + MINIMUM_FEE_ZATOSHI &&
            // A valid memo is necessary only for memo-capable recipients
            (isMemolessRecipient || memoState is MemoState.Correct)

    ZashiButton(
        onClick = {
            scope.launch {
                // F1: the amount paid is the state's zatoshi (what the field and the review show), handed to the SDK's
                // check as plain '.' text; the field text is never parsed a second time with the phone's rules.
                val zatoshi = (amountState as? AmountState.Valid)?.zatoshi ?: return@launch
                // SDK side validations
                val zecSendValidation =
                    ZecSendExt.new(
                        locale = Locale.US,
                        destinationString = recipientAddressState.address,
                        zecString = BigDecimal.valueOf(zatoshi.value, ZEC_DECIMALS).toPlainString(),
                        // Take memo for a valid non-transparent receiver only
                        memoString =
                            if (isMemolessRecipient) {
                                ""
                            } else {
                                memoState.text
                            },
                    )

                when (zecSendValidation) {
                    is ZecSendExt.ZecSendValidation.Valid -> {
                        // The button is disabled for Invalid recipients, so this should be unreachable;
                        // the helper returns null rather than throw, so a state race can't crash the flow.
                        val destination = recipientAddressState.toWalletAddressOrNull()

                        if (destination == null) {
                            Twig.warn { "Send aborted: recipient address is invalid or could not be parsed" }
                        } else {
                            onCreateZecSend(zecSendValidation.zecSend.copy(destination = destination, amount = zatoshi))
                        }
                    }

                    is ZecSendExt.ZecSendValidation.Invalid -> {
                        // We do not expect this validation to fail, so logging is enough here
                        // An error popup could be reasonable here as well
                        Twig.warn { "Send failed with: ${zecSendValidation.validationErrors}" }
                    }
                }
            }
        },
        text = stringResource(id = R.string.send_create),
        enabled = sendButtonEnabled,
        modifier =
            Modifier
                .testTag(SendTag.SEND_FORM_BUTTON)
                .fillMaxWidth()
    )
}

@Suppress("LongMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SendFormAddressTextField(
    sendAddressBookState: SendAddressBookState,
    hasCameraFeature: Boolean,
    onQrScannerOpen: () -> Unit,
    recipientAddressState: RecipientAddressState,
    setRecipientAddress: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Column(
        modifier =
            Modifier
                // Animate error show/hide
                .animateContentSize()
                // Scroll TextField above ime keyboard
                .bringIntoViewRequester(bringIntoViewRequester)
    ) {
        Text(
            text = stringResource(id = R.string.send_address_label),
            color = ZashiColors.Inputs.Default.label,
            style = ZashiTypography.textMd
        )

        Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingSmall))

        val recipientAddressValue = recipientAddressState.address
        val recipientAddressError =
            if (
                recipientAddressValue.isNotEmpty() &&
                recipientAddressState.type is AddressType.Invalid
            ) {
                stringResource(id = R.string.send_address_invalid)
            } else {
                null
            }

        ZashiTextField(
            singleLine = true,
            maxLines = 1,
            value = recipientAddressValue,
            onValueChange = {
                setRecipientAddress(it)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusEvent { focusState ->
                        if (focusState.isFocused) {
                            scope.launch {
                                bringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
            error = recipientAddressError,
            placeholder = {
                Text(
                    text = stringResource(id = R.string.send_address_hint),
                    style = ZashiTypography.textMd,
                    color = ZashiColors.Inputs.Default.text
                )
            },
            suffix =
                if (hasCameraFeature) {
                    {
                        Row(
                            verticalAlignment = Alignment.Top
                        ) {
                            Image(
                                modifier =
                                    Modifier
                                        .minimumInteractiveComponentSize() // 48dp hit target
                                        .clickable(
                                            onClick = sendAddressBookState.onButtonClick,
                                            role = Role.Button,
                                            indication = ripple(radius = 4.dp),
                                            interactionSource = remember { MutableInteractionSource() }
                                        ),
                                painter = painterResource(sendAddressBookState.mode.icon),
                                // QA2 Q2-05 (ZC-1599)
                                contentDescription =
                                    when (sendAddressBookState.mode) {
                                        SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK ->
                                            stringResource(R.string.address_book_title)
                                        SendAddressBookState.Mode.ADD_TO_ADDRESS_BOOK ->
                                            stringResource(R.string.transaction_detail_save_address)
                                    },
                            )

                            Spacer(modifier = Modifier.width(4.dp))

                            Image(
                                modifier =
                                    Modifier
                                        .minimumInteractiveComponentSize() // 48dp hit target
                                        .clickable(
                                            onClick = onQrScannerOpen,
                                            role = Role.Button,
                                            indication = ripple(radius = 4.dp),
                                            interactionSource = remember { MutableInteractionSource() }
                                        ),
                                painter = painterResource(R.drawable.qr_code_icon),
                                contentDescription = stringResource(R.string.send_scan_content_description),
                            )

                            // Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                } else {
                    null
                },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = {
                        focusManager.moveFocus(FocusDirection.Next)
                    }
                ),
        )
    }
}

// ZIP-317 minimum network fee (0.0001 ZEC), used only as a floor pre-check: an amount that does not
// leave even this much of the Ironwood balance can never be sent. The real fee grows with the note and
// output count and is decided by the proposal; Send all asks the SDK for it (ComputeSendMaxUseCase).
private val MINIMUM_FEE_ZATOSHI = Zip317.conventionalFeeZatoshi(Zip317.GRACE_ACTIONS)

/** ZEC has 8 decimals (one zatoshi). */
private const val ZEC_DECIMALS = 8

@Suppress("LongParameterList", "LongMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SendFormAmountTextField(
    amountState: AmountState,
    imeAction: ImeAction,
    isTransparentOrTextRecipient: Boolean,
    exchangeRateState: ExchangeRateState,
    setAmountState: (AmountState) -> Unit,
    selectedAccount: WalletAccount,
    sendAllState: SendAllState =
        SendAllState.of(
            account = selectedAccount,
            recipient =
                if (isTransparentOrTextRecipient) SendMaxRecipient.TRANSPARENT else SendMaxRecipient.SHIELDED,
        ),
    onSendAllClick: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current

    val context = LocalContext.current

    val zcashCurrency = ZcashCurrency.getLocalizedName(context)

    val locale = rememberDesiredFormatLocale()

    val amountError =
        when (amountState) {
            // F1: the question is shown under the fields (AmountReadingChooser), not as an error.
            is AmountState.Ask -> null
            is AmountState.Invalid -> {
                val reason = amountState.reason
                when {
                    reason != null ->
                        amountErrorText(
                            reason,
                            if (amountState.lastFieldChangedByUser == AmountField.FIAT) AmountUnit.USD else AmountUnit.ZEC,
                            locale
                        )
                    amountState.value.isEmpty() -> null
                    else -> stringResource(id = R.string.send_amount_invalid)
                }
            }

            is AmountState.Valid -> {
                // #bug-feeless-check: the old check (balance < amount) ignored the network fee, so
                // entering the FULL balance passed the UI but the send failed at proposal time (no room
                // for the fee). Require Ironwood ≥ amount + the ZIP-317 minimum fee: sealed Orchard and
                // Sapling cannot pay another person, so counting them let impossible amounts through.
                if (selectedAccount.peerSpendableBalance.value <
                    amountState.zatoshi.value + MINIMUM_FEE_ZATOSHI
                ) {
                    stringResource(id = R.string.send_amount_insufficient_balance)
                } else {
                    null
                }
            }
        }

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    // QA2 m4 (ZC-1759): the selection each amount field had before an edit.
    // QA2 m8: each holder edits the state it is handed (the last taken one of the frame), never this composition's.
    val zecSelection = rememberAmountTextFieldSelection(amountState) { it.value }
    val fiatSelection = rememberAmountTextFieldSelection(amountState) { it.fiatValue }

    Column(
        modifier =
            Modifier
                // Animate error show/hide
                .animateContentSize()
                // Scroll TextField above ime keyboard
                .bringIntoViewRequester(bringIntoViewRequester)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.send_amount_label),
                color = ZashiColors.Inputs.Default.label,
                style = ZashiTypography.textMd,
                modifier = Modifier.weight(1f),
            )
            // "Send all" fills the exact maximum the SDK prices for this recipient (SendViewModel runs the
            // search); the composable only shows the state. Transparent funds are not swept: they must
            // be shielded first (the Shield banner handles that).
            SendAllLink(state = sendAllState, onClick = onSendAllClick)
        }

        Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingSmall))

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            ZashiTextField(
                singleLine = true,
                maxLines = 1,
                // F1, QA2 m3: the one amount state machine (a typed mark is the decimal point, a key that cannot be taken
                // is not taken; a paste that reads two ways asks, and a key on the question empties it). QA2 m4 (ZC-1759):
                // the field reports the selection before each edit, so a select-all paste is read as a paste.
                state =
                    zecSelection.textFieldState(
                        text = amountState.value,
                        error = amountError,
                        edit = { state, newValue, selectionBefore ->
                            AmountState.editResult(
                                current = state,
                                field = AmountField.ZEC,
                                newText = newValue,
                                selectionBefore = selectionBefore,
                                locale = locale,
                                isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                                exchangeRateState = exchangeRateState,
                            )
                        },
                        onState = setAmountState
                    ),
                modifier = Modifier.weight(1f),
                innerModifier =
                    ZashiTextFieldDefaults
                        .innerModifier
                        .testTag(SendTag.SEND_AMOUNT_FIELD),
                placeholder = {
                    Text(
                        text =
                            stringResource(
                                id = R.string.send_amount_hint,
                                zcashCurrency
                            ),
                        style = ZashiTypography.textMd,
                        color = ZashiColors.Inputs.Default.text
                    )
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = imeAction
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            focusManager.clearFocus(true)
                        },
                        onNext = {
                            focusManager.moveFocus(FocusDirection.Down)
                        }
                    ),
                prefix = {
                    Image(
                        painter = painterResource(R.drawable.ic_send_zashi),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(color = ZashiColors.Inputs.Default.text),
                    )
                }
            )

            if (exchangeRateState is ExchangeRateState.Data) {
                Spacer(modifier = Modifier.width(12.dp))
                Image(
                    modifier = Modifier.padding(top = 12.dp),
                    painter = painterResource(id = R.drawable.ic_send_convert),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(color = ZcashTheme.colors.secondaryColor),
                )
                Spacer(modifier = Modifier.width(12.dp))
                ZashiTextField(
                    singleLine = true,
                    maxLines = 1,
                    // QA2 m4 (ZC-1759): the USD field reports the selection before each edit too.
                    state =
                        fiatSelection.textFieldState(
                            text = amountState.fiatValue,
                            error = null,
                            isEnabled = !exchangeRateState.isStale && exchangeRateState.currencyConversion != null,
                            edit = { state, newValue, selectionBefore ->
                                AmountState.editResult(
                                    current = state,
                                    field = AmountField.FIAT,
                                    newText = newValue,
                                    selectionBefore = selectionBefore,
                                    locale = locale,
                                    isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                                    exchangeRateState = exchangeRateState,
                                )
                            },
                            onState = setAmountState
                        ),
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text =
                                stringResource(
                                    id = R.string.send_usd_amount_hint
                                ),
                            style = ZashiTypography.textMd,
                            color = LocalContentColor.current
                        )
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = imeAction
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                focusManager.clearFocus(true)
                            },
                            onNext = {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        ),
                    prefix = {
                        Image(
                            painter = painterResource(R.drawable.ic_send_usd),
                            contentDescription = null,
                            colorFilter =
                                if (!exchangeRateState.isStale) {
                                    ColorFilter.tint(color = ZashiColors.Inputs.Default.text)
                                } else {
                                    ColorFilter.tint(color = ZashiColors.Inputs.Disabled.text)
                                }
                        )
                    }
                )
            }
        }

        // F1: a paste that reads two ways asks here, smaller reading first; a tap only fills the field (the amount is
        // still reviewed before anything is sent).
        if (amountState is AmountState.Ask) {
            AmountReadingChooser(
                raw = amountState.raw,
                options = amountState.options,
                notUsable = amountState.notUsable,
                error = null,
                unit = if (amountState.field == AmountField.FIAT) AmountUnit.USD else AmountUnit.ZEC,
                locale = locale,
                onChoose = { option ->
                    setAmountState(
                        AmountState.choose(
                            ask = amountState,
                            option = option,
                            locale = locale,
                            isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                            exchangeRateState = exchangeRateState,
                        )
                    )
                },
            )
        }

        SendAllReason(sendAllState)
    }
}

@Composable
private fun SendAllLink(
    state: SendAllState,
    onClick: () -> Unit,
) {
    when (state) {
        SendAllState.Hidden -> Unit
        SendAllState.Ready ->
            Text(
                text = stringResource(R.string.send_all),
                color = ZcashTheme.colors.secondaryColor,
                style = ZashiTypography.textSm,
                modifier = Modifier.clickable(onClick = onClick)
            )
        SendAllState.Calculating ->
            Text(
                text = stringResource(R.string.send_all_calculating),
                color = ZashiColors.Text.textTertiary,
                style = ZashiTypography.textSm,
            )
        SendAllState.NeedsRecipient,
        is SendAllState.Unavailable ->
            Text(
                text = stringResource(R.string.send_all),
                color = ZashiColors.Text.textDisabled,
                style = ZashiTypography.textSm,
            )
    }
}

/**
 * Why Send all is off, with the numbers. Nothing when it is available. Balance figures honour the
 * hide-balances setting like the balance widget above them; the fee is not a balance and stays.
 */
@Composable
private fun SendAllReason(state: SendAllState) {
    val hiddenAmount = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)
    val reason =
        when (state) {
            SendAllState.NeedsRecipient -> stringRes(R.string.send_all_reason_needs_recipient)
            is SendAllState.Unavailable ->
                sendAllReasonText(state.reason) { stringRes(it) } orHidden
                    sendAllReasonText(state.reason) { hiddenAmount }
            SendAllState.Hidden, SendAllState.Ready, SendAllState.Calculating -> null
        } ?: return
    Text(
        text = reason.getValue(),
        color = ZashiColors.Text.textTertiary,
        style = ZashiTypography.textSm,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = ZcashTheme.dimens.spacingTiny)
    )
}

/** The reason copy, with each balance figure written by [balance]. */
private fun sendAllReasonText(
    reason: SendMaxUnavailable,
    balance: (Zatoshi) -> StringResource,
): StringResource =
    when (reason) {
        is SendMaxUnavailable.StillConfirming ->
            stringRes(R.string.send_all_reason_confirming, balance(reason.pending))
        is SendMaxUnavailable.OrchardSealed ->
            stringRes(R.string.send_all_reason_orchard, balance(reason.orchard))
        is SendMaxUnavailable.BelowFee ->
            stringRes(R.string.send_all_reason_below_fee, balance(reason.have), stringRes(reason.fee))
        is SendMaxUnavailable.SaplingPresent ->
            stringRes(R.string.send_all_reason_sapling, balance(reason.sapling))
        SendMaxUnavailable.TexUnsupported -> stringRes(R.string.send_all_reason_tex)
        SendMaxUnavailable.CouldNotPrice -> stringRes(R.string.send_all_reason_could_not_price)
    }

@Suppress("LongMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SendFormMemoTextField(
    isMemoFieldAvailable: Boolean,
    memoState: MemoState,
    setMemoState: (MemoState) -> Unit,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    Column(
        modifier =
            Modifier
                // Animate error show/hide
                .animateContentSize()
                // Scroll TextField above ime keyboard
                .bringIntoViewRequester(bringIntoViewRequester)
    ) {
        Text(
            text = stringResource(id = R.string.send_memo_label),
            color = ZashiColors.Inputs.Default.label,
            style = ZashiTypography.textMd
        )

        Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingSmall))

        ZashiTextField(
            minLines = if (isMemoFieldAvailable) 4 else 1,
            isEnabled = isMemoFieldAvailable,
            value =
                if (isMemoFieldAvailable) {
                    memoState.text
                } else {
                    ""
                },
            error =
                if (memoState is MemoState.Correct) {
                    null
                } else {
                    stringResource(R.string.send_memo_too_long, Memo.MAX_MEMO_LENGTH_BYTES)
                },
            onValueChange = {
                setMemoState(MemoState.new(it))
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                    capitalization = KeyboardCapitalization.Sentences
                ),
            placeholder = {
                if (isMemoFieldAvailable) {
                    Text(
                        text = stringResource(id = R.string.send_memo_hint),
                        style = ZashiTypography.textMd,
                        color = ZashiColors.Inputs.Default.text
                    )
                } else {
                    Text(
                        text = stringResource(R.string.send_transparent_memo),
                        style = ZashiTypography.textSm,
                        color = ZashiColors.Utility.Gray.utilityGray700
                    )
                }
            },
            leadingIcon =
                if (isMemoFieldAvailable) {
                    null
                } else {
                    {
                        Image(
                            painter = painterResource(id = R.drawable.ic_confirmation_message_info),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(ZashiColors.Utility.Gray.utilityGray500)
                        )
                    }
                },
            colors =
                if (isMemoFieldAvailable) {
                    ZashiTextFieldDefaults.defaultColors()
                } else {
                    ZashiTextFieldDefaults.defaultColors(
                        disabledTextColor = ZashiColors.Inputs.Disabled.text,
                        disabledHintColor = ZashiColors.Inputs.Disabled.hint,
                        disabledBorderColor = Color.Unspecified,
                        disabledContainerColor = ZashiColors.Inputs.Disabled.bg,
                        disabledPlaceholderColor = ZashiColors.Inputs.Disabled.text,
                    )
                },
            modifier = Modifier.fillMaxWidth(),
        )

        if (isMemoFieldAvailable) {
            Text(
                text =
                    stringResource(
                        // QA2 Q2-03 (ZC-1611): typed bytes / maximum, so an empty memo reads 0/512.
                        id = R.string.send_memo_bytes_counter,
                        memoState.byteSize,
                        Memo.MAX_MEMO_LENGTH_BYTES
                    ),
                color =
                    if (memoState is MemoState.Correct) {
                        ZashiColors.Text.textSecondary // hint(#6E7892) was 4.47:1 on BgBase (sub-AA)
                    } else {
                        ZashiColors.Inputs.Filled.required
                    },
                textAlign = TextAlign.End,
                style = ZashiTypography.textSm,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = ZcashTheme.dimens.spacingTiny)
            )
        }
    }
}

@Composable
@Preview("SendFailure")
private fun PreviewSendFailure() {
    ZcashTheme(forceDarkMode = false) {
        BlankSurface {
            SendFailure(
                onConfirm = {},
                reason = "Insufficient balance"
            )
        }
    }
}

@Composable
private fun SendFailure(
    onConfirm: () -> Unit,
    reason: String,
    modifier: Modifier = Modifier
) {
    // TODO [#1276]: Once we ensure that the reason contains a localized message, we can leverage it for the UI prompt
    // TODO [#1276]: Consider adding support for a specific exception in AppAlertDialog
    // TODO [#1276]: https://github.com/Electric-Coin-Company/zashi-android/issues/1276

    AppAlertDialog(
        title = stringResource(id = R.string.send_dialog_error_title),
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(id = R.string.send_dialog_error_text),
                    color = ZcashTheme.colors.textPrimary,
                )

                Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingDefault))

                Text(
                    text = reason,
                    fontStyle = FontStyle.Italic,
                    color = ZcashTheme.colors.textPrimary,
                )
            }
        },
        confirmButtonText = stringResource(id = R.string.send_dialog_error_btn),
        onConfirmButtonClick = onConfirm,
        modifier = modifier
    )
}
