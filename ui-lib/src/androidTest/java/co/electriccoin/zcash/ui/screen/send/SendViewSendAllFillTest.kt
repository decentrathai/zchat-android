package co.electriccoin.zcash.ui.screen.send

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import androidx.test.filters.MediumTest
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.toZecString
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.PROPOSAL_POOL_IRONWOOD
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxSearch
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.SendAllState
import co.electriccoin.zcash.ui.screen.send.view.SendFormAmountTextField
import co.electriccoin.zcash.ui.test.getStringResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Send-all link reaches its handler and the fill lands in the amount field (review follow-up to the
 * W-1/W-2/W-3 RED pin, whose host passes no handler and so can no longer fail).
 *
 * The host wires the link the way AndroidSend does since the fill moved into SendViewModel: the tap runs
 * the exact-maximum search and the result is written into the field as a ZEC entry. The probe prices like
 * ZIP 317 for one Ironwood note (fee 10,000). Owner state: 443,002 zatoshi of Ironwood, 139,000 of sealed
 * Orchard, so the field must read 0.00433002. The JVM test SendViewModelSendAllTest covers the view model
 * and use case behind it. All addresses are synthetic.
 */
class SendViewSendAllFillTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** How many times the link reached its handler. */
    private var taps = 0

    @Test
    @MediumTest
    fun tappingSendAllFillsTheExactIronwoodMaximum() {
        val account = runBlocking { account(ironwood = WalletBalance(Zatoshi(IRONWOOD), Zatoshi(0), Zatoshi(0))) }
        composeTestRule.setContent { SendAllHost(account, SendAllState.Ready) }

        composeTestRule.onNodeWithText(getStringResource(R.string.send_all)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, taps)
        assertEquals(433_002L, filledZatoshi())
    }

    /** A disabled link does not reach the handler: nothing is filled, not even 0. */
    @Test
    @MediumTest
    fun anUnavailableLinkNeverFills() {
        val account =
            runBlocking { account(ironwood = WalletBalance(Zatoshi(0), Zatoshi(IRONWOOD), Zatoshi(0))) }
        composeTestRule.setContent {
            SendAllHost(account, SendAllState.of(account, SendMaxRecipient.SHIELDED))
        }

        composeTestRule.onNodeWithText(getStringResource(R.string.send_all)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, taps)
        assertNull(filledZatoshi())
    }

    /** The amount field's content in zatoshi, or null when it is empty. */
    private fun filledZatoshi(): Long? {
        val text =
            composeTestRule
                .onNodeWithTag(SendTag.SEND_AMOUNT_FIELD, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
                ?.replace(" ", "")
                ?.replace(',', '.')
        return text?.takeIf { it.isNotEmpty() }?.let { BigDecimal(it).movePointRight(8).toLong() }
    }

    @Composable
    private fun SendAllHost(
        account: WalletAccount,
        sendAllState: SendAllState,
    ) {
        val locale = LocalConfiguration.current.locales[0]
        val scope = rememberCoroutineScope()
        var amountState by remember {
            mutableStateOf(
                AmountState.newFromZec(
                    value = "",
                    fiatValue = "",
                    isTransparentOrTextRecipient = false,
                    exchangeRateState = ExchangeRateState.OptedOut,
                    locale = locale,
                )
            )
        }
        ZcashTheme {
            Column {
                SendFormAmountTextField(
                    amountState = amountState,
                    imeAction = ImeAction.Done,
                    isTransparentOrTextRecipient = false,
                    exchangeRateState = ExchangeRateState.OptedOut,
                    setAmountState = { amountState = it },
                    selectedAccount = account,
                    sendAllState = sendAllState,
                    onSendAllClick = {
                        taps++
                        scope.launch {
                            val result =
                                SendMaxSearch.search(
                                    recipient = SendMaxRecipient.SHIELDED,
                                    readAccount = { account },
                                    probe = { pricedAccount, amount -> oneNoteProbe(pricedAccount, amount) },
                                )
                            if (result is SendMaxResult.Max) {
                                amountState =
                                    AmountState.newFromZec(
                                        value = result.amount.toZecString(locale),
                                        fiatValue = "",
                                        isTransparentOrTextRecipient = false,
                                        exchangeRateState = ExchangeRateState.OptedOut,
                                        locale = locale,
                                    )
                            }
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val IRONWOOD = 443_002L
        const val ONE_NOTE_FEE = 10_000L

        /** ZIP 317 for a wallet holding one Ironwood note: a sweep of it costs two actions. */
        fun oneNoteProbe(
            account: WalletAccount,
            amount: Zatoshi,
        ): FeeProbe =
            if (amount.value + ONE_NOTE_FEE <= account.peerSpendableBalance.value) {
                // F9: the probe reports what it read from the proposal: one Ironwood input and no change.
                FeeProbe.Built(
                    fee = Zatoshi(ONE_NOTE_FEE),
                    usesOrchardInputs = false,
                    transactionCount = 1,
                    change = emptyList(),
                    inputPools = listOf(PROPOSAL_POOL_IRONWOOD),
                )
            } else {
                FeeProbe.Short
            }

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account(ironwood: WalletBalance) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(139_000), ironwood),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
