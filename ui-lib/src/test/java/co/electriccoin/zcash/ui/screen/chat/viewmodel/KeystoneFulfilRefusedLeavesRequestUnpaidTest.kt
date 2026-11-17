package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * K0-2 (fix-all 2.13.0, train T1; K0 design finding 4, B5) — Pay on a payment request with a Keystone account
 * selected.
 *
 * At HEAD fulfillPaymentRequest passes skipNavigation = !isKeystone (false on Keystone), so the ZC-102 guard lets it
 * through: a Keystone proposal and PCZT are built and the screen goes to TransactionProgress, which hangs; the chat
 * state is set to Idle with no message.
 *
 * Wanted: the refusal comes before anything is built and before the paid marker is written. The request stays unpaid
 * (not on disk, not in the screen's paid set, no started record, the in-flight claim released), so Pay is still
 * offered, and the chat says why (ChatSendCopy.KEYSTONE_CHATS_CLOSED). After switching to the ZChat account the same
 * request is paid exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneFulfilRefusedLeavesRequestUnpaidTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a payment request paid from a Keystone account is refused, stays unpaid and can be paid after switching`() =
        runBlocking {
            val screen = KeystoneChatScreen.open(PEER)

            screen.vm.fulfillPaymentRequest(PEER, AMOUNT, REQUEST_ID)
            awaitIdle(screen.vm)

            assertEquals("a Keystone proposal or PCZT was built for a fulfil", emptyList<String>(), screen.keystone.calls)
            assertTrue(
                "the fulfil opened a screen (TransactionProgress hangs on Keystone): ${screen.router.commands}",
                screen.router.commands.isEmpty(),
            )
            assertEquals("nothing may reach the SDK", 0, screen.seam.submits.get())
            assertFalse("the refused request was marked paid on disk", REQUEST_ID in screen.prefs.paid)
            assertTrue("a started record was written for a refused fulfil", screen.prefs.started.isEmpty())
            assertFalse("the screen hides Pay for a request nothing paid", REQUEST_ID in paidOnScreen(screen.vm))
            assertFalse("the in-flight claim was kept, so no screen can pay it again", REQUEST_ID in inFlightClaims())
            assertEquals(
                "the chat must say that Keystone can't sign chat payments and where to switch",
                SendMessageState.Error(KeystoneChatScreen.closedText()),
                PaymentHarness.sendMessageState(screen.vm),
            )

            // The user switches to the ZChat account (the Wallet-tab chip) and taps Pay again.
            screen.accounts.select(screen.accounts.zashiAccount())
            screen.vm.fulfillPaymentRequest(PEER, AMOUNT, REQUEST_ID)
            awaitIdle(screen.vm)

            assertEquals("fixture sanity", emptyList<String>(), screen.prefs.unexpectedCalls)
            assertEquals("the request must be paid exactly once after the switch", 1, screen.seam.submits.get())
            assertTrue(REQUEST_ID in screen.prefs.paid)
        }

    @Suppress("UNCHECKED_CAST")
    private fun paidOnScreen(vm: ChatViewModel): Set<String> =
        (
            ChatViewModel::class.java
                .getDeclaredField("paidRequestIds")
                .apply { isAccessible = true }
                .get(vm) as StateFlow<Set<String>>
        ).value

    @Suppress("UNCHECKED_CAST")
    private fun inFlightClaims(): Set<String> =
        ChatViewModel::class.java
            .getDeclaredField("fulfilInFlight")
            .apply { isAccessible = true }
            .get(null) as Set<String>

    private companion object {
        const val PEER = "usynthetick0fulfilpeer000001"
        const val REQUEST_ID = "synthetic-k0-request-1"
        const val AMOUNT = 5_000_000L
    }
}
