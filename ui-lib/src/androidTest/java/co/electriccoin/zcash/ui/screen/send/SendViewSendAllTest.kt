package co.electriccoin.zcash.ui.screen.send

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import androidx.test.filters.MediumTest
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.view.SendButton
import co.electriccoin.zcash.ui.screen.send.view.SendFormAmountTextField
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RED pins for the wallet "Send all" fill (W-1, W-2, W-3). No JVM seam exists: the fill is computed
 * inside the `SendFormAmountTextField` composable.
 *
 * W-1/W-2. The owner's phone held 443,002 zatoshi of Ironwood and 139,000 of sealed Orchard. After ZIP 258
 * only Ironwood can pay another person, but Send all fills `spendableShieldedBalance − 10,000`, which adds
 * the Orchard back in: 572,002. The SDK then refuses (have 582,002, need 587,002) and the user sees
 * "please wait" forever. Whatever the fee turns out to be, ZIP 317 charges at least 10,000, so no correct
 * fill can exceed 443,002 − 10,000 = 433,002.
 *
 * W-3. The link is shown on the TOTAL shielded balance but fills from the SPENDABLE one, so a wallet whose
 * only Ironwood is still confirming gets a fill of 0 with no reason given. Send all must never fill 0,
 * and Send must stay disabled.
 *
 * The field may stay empty after the tap (a fill computed elsewhere arrives later in the real screen);
 * whatever it shows must obey both rules. All addresses are synthetic.
 */
class SendViewSendAllTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun sendAllFillWithinIronwood() {
        val account = runBlocking { account(ironwood = balance(available = 443_002), orchard = balance(139_000)) }
        composeTestRule.setContent { SendAllHost(account) }

        tapSendAllIfOffered()

        val filled = filledZatoshi()
        assertTrue(
            filled == null || filled <= 443_002 - MINIMUM_NETWORK_FEE,
            "Send all filled $filled zat; only the 443,002 zat of Ironwood can pay a peer and the network fee is " +
                "at least $MINIMUM_NETWORK_FEE zat, so the fill can be at most ${443_002 - MINIMUM_NETWORK_FEE}",
        )
    }

    @Test
    @MediumTest
    fun sendAllNeverFillsZero() {
        val account =
            runBlocking {
                account(ironwood = balance(available = 0, changePending = 443_002), orchard = balance(0))
            }
        composeTestRule.setContent { SendAllHost(account) }

        tapSendAllIfOffered()

        assertNotEquals(
            0L,
            filledZatoshi(),
            "with every Ironwood note still confirming, Send all must say so, never fill 0.00000000",
        )
        composeTestRule.onNodeWithTag(SendTag.SEND_FORM_BUTTON).assertIsNotEnabled()
    }

    private fun tapSendAllIfOffered() {
        if (composeTestRule.onAllNodesWithText(SEND_ALL_LABEL).fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithText(SEND_ALL_LABEL).performClick()
        }
        composeTestRule.waitForIdle()
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

    /** The amount field and the Send button of the Send form, over [account], with a shielded recipient. */
    @Composable
    private fun SendAllHost(account: WalletAccount) {
        val locale = LocalConfiguration.current.locales[0]
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
                )
                SendButton(
                    amountState = amountState,
                    memoState = MemoState.new(""),
                    onCreateZecSend = {},
                    recipientAddressState = RecipientAddressState(PEER_ADDRESS, AddressType.Unified),
                    selectedAccount = account,
                )
            }
        }
    }

    private companion object {
        const val SEND_ALL_LABEL = "Send all"

        /** ZIP 317 never charges less than 2 grace actions × 5,000. */
        const val MINIMUM_NETWORK_FEE = 10_000L

        /** Synthetic, never a real address. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)

        fun balance(
            available: Long,
            changePending: Long = 0,
        ) = WalletBalance(Zatoshi(available), Zatoshi(changePending), Zatoshi(0))

        suspend fun account(
            ironwood: WalletBalance,
            orchard: WalletBalance,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), orchard, ironwood),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
