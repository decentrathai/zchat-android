package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * K0-2 (fix-all 2.13.0, train T1; K0 design finding 4, B4) — Pay in a chat with a Keystone account selected.
 *
 * At HEAD sendPayment passes skipNavigation = false, so the send chokepoint's ZC-102 guard (directSubmit AND
 * skipNavigation) lets it through: a Keystone proposal and PCZT are built in the process-wide signing session and the
 * screen goes to TransactionProgress, which never shows the signing QR and says "Sending" for ever. The chat screen
 * is set back to Idle, so the user is told nothing.
 *
 * 2.13.0 closes Keystone chat signing: the chokepoint refuses every chat send on a Keystone account before anything
 * is built, and the chat says why and where to switch (ChatSendCopy.KEYSTONE_CHATS_CLOSED). Nothing is built, nothing
 * is shown, nothing is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneChatPaymentRefusedBeforeProposalTest {
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
    fun `Pay on a Keystone account builds nothing, opens nothing and says why`() =
        runBlocking {
            val screen = KeystoneChatScreen.open(PEER)

            screen.vm.sendPayment(PEER, 0.5)
            awaitIdle(screen.vm)

            assertEquals("a Keystone proposal or PCZT was built for a chat payment", emptyList<String>(), screen.keystone.calls)
            assertTrue(
                "the chat payment opened a screen (TransactionProgress hangs on Keystone): ${screen.router.commands}",
                screen.router.commands.isEmpty(),
            )
            assertEquals("nothing may reach the SDK", 0, screen.seam.submits.get())
            assertEquals(
                "the chat must say that Keystone can't sign chat payments and where to switch",
                SendMessageState.Error(KeystoneChatScreen.closedText()),
                PaymentHarness.sendMessageState(screen.vm),
            )
        }

    /** Control, green before and after: with the ZChat account selected the same Pay is sent once. */
    @Test
    fun `control - Pay on the ZChat account is sent`() =
        runBlocking {
            val screen = KeystoneChatScreen.open(PEER)
            screen.accounts.select(screen.accounts.zashiAccount())

            screen.vm.sendPayment(PEER, 0.5)
            awaitIdle(screen.vm)

            assertEquals(1, screen.seam.submits.get())
            assertEquals(emptyList<String>(), screen.keystone.calls)
        }

    private companion object {
        const val PEER = "usynthetick0paypeer000000001"
    }
}
