package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.MediumTest
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.chat.model.PaymentQuote
import org.junit.Rule
import kotlin.test.Test

/**
 * RED pin for C-5: the in-chat Payment dialog lets the user send the whole balance with nothing left for
 * the fees. No JVM seam exists: the check lives inside the `PaymentDialog` composable.
 *
 * `hasEnoughBalance = amountZec <= balanceZec` compares Doubles and leaves out both the 1,000-zatoshi
 * platform-fee output every chat send carries and the ZIP 317 network fee (at least 10,000). Typing the
 * full balance therefore enables Send, and the SDK refuses the proposal afterwards. With 443,002 zatoshi,
 * typing 0.00443002 must leave Send disabled. Which balance the caller passes (ChatViewModel's
 * totalBalance today) is covered in review.
 */
class PaymentDialogAffordabilityTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun typingFullBalanceKeepsSendDisabled() {
        composeTestRule.setContent {
            ZcashTheme {
                PaymentDialog(
                    balance = Zatoshi(BALANCE_ZATOSHI),
                    zecPriceUsd = null,
                    recipientName = "Synthetic peer",
                    // D-6: no exact price here; Send stays off for the full balance either way (the floor already refuses).
                    quotePayment = { _, _ -> PaymentQuote.Unavailable() },
                    onDismiss = {},
                    onSendPayment = { _, _, _ -> },
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction() and hasText("Amount (ZEC)", substring = true)).performTextInput(
            FULL_BALANCE_ZEC
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNode(sendButton).assertIsNotEnabled()
    }

    private companion object {
        const val BALANCE_ZATOSHI = 443_002L
        const val FULL_BALANCE_ZEC = "0.00443002"

        /** The confirm button reads "Send <amount> ZEC" once an amount is typed. */
        val sendButton: SemanticsMatcher = hasClickAction() and hasText("Send ", substring = true)
    }
}
