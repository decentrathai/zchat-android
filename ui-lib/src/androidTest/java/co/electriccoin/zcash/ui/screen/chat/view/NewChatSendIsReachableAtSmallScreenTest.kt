package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import org.junit.Rule
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ZC-979 / ZC-632 (register; T6b phone step ZC-632 item 1) — at 360 dp wide and font scale 1.3 the New Chat form did not
 * scroll and its Send button lay under the composer card, so a first message could not be sent from New Chat.
 *
 * Rendered here as the phone shows it: 360 x 640 dp, font scale 1.3, a valid recipient, a message, ten contacts, in each
 * of the three modes. The Send button must be reachable by scrolling the form, shown, and a tap on it must reach Send
 * (not the amount dialog). Instrumented: it needs a device or an emulator (the phone check repeats it); the JVM test
 * NewChatFormScrollsToSendTest pins the layout in source form.
 */
class NewChatSendIsReachableAtSmallScreenTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun tunnel_send_is_reachable() = assertSendReachable(ConversationMode.TUNNEL)

    @Test
    @MediumTest
    fun open_send_is_reachable() = assertSendReachable(ConversationMode.OPEN)

    @Test
    @MediumTest
    fun shielded_send_is_reachable() = assertSendReachable(ConversationMode.VAULT)

    private fun assertSendReachable(mode: ConversationMode) {
        val sends = AtomicInteger()
        val amountDialogs = AtomicInteger()
        val contacts = (1..10).map { Contact("usyntheticnewchatcontact${it.toString().padStart(4, '0')}", "Contact $it", Instant.EPOCH) }
        val state =
            ZchatComposeState.Ready(
                contacts = contacts,
                recipientAddress = "usyntheticnewchatrecipient0001",
                message = "Hello from a small screen",
                isValidAddress = true,
                availableBalanceDisplay = "0.01 ZEC",
                spendableBalanceZatoshi = 1_000_000L,
                selectedMode = mode,
                openAvailable = true,
                isFreeOpenSend = mode == ConversationMode.OPEN,
                onRecipientChange = {},
                onMessageChange = {},
                onContactSelect = {},
                onSendClick = { sends.incrementAndGet() },
                onScanQrClick = {},
                onBack = {},
                onAddContact = { _, _ -> },
                onShowAddContactDialog = {},
                onDismissAddContactDialog = {},
                onContactNameChange = {},
                onShowAmountDialog = { amountDialogs.incrementAndGet() },
                onDismissAmountDialog = {},
                onAmountSelect = {},
                onCustomAmountChange = {},
                onModeSelect = {},
            )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2.0f, fontScale = 1.3f)) {
                ZcashTheme {
                    Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                        ZchatComposeView(state)
                    }
                }
            }
        }
        val send = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_chat_send_message)
        composeTestRule
            .onNodeWithText(send)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, sends.get(), "$mode: a tap on Send reached Send")
        assertEquals(0, amountDialogs.get(), "$mode: a tap on Send opened the amount dialog")
    }
}
