package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.QuotedChatSetup
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
 * ZC-1334 (phone matrix 1 Nov, S1): New Chat's one question covered a Tunnel chat's message and setup steps. The message was
 * mined at 00:13; the key exchange it covered went only when the chat was left and opened again at 00:24. A covered leg is
 * on disk as QuotedChatSetup.kex, and New Chat writes no owed mark, so retryOwedKeyExchanges (the block's half) never sees it:
 * only the chat-open LaunchedEffect (ensureNostrBootstrapSent) pays it.
 *
 * Must hold: the block that makes it payable pays it, once, with the chat left open and no question.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoveredSetupStepGoesOnTheBlockTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1334coveredkex01"
        const val LEG = 17_000L
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    private val legs = QuotedLegsOnDisk()

    private fun covered(): FundsConfirmingChat =
        FundsConfirmingChat(PEER).also {
            it.plan = "Fundable"
            it.consent = false
            it.chat.prefs.setConversationMode(PEER, ConversationMode.TUNNEL)
            legs.setups[PEER] = QuotedChatSetup(kex = LEG).encode()
        }

    @Test
    fun `control - opening the chat pays the covered key exchange`() =
        runBlocking {
            val world = covered()
            val vm = world.screen().also(legs::over)

            vm.ensureNostrBootstrapSent(PEER)

            assertTrue(
                "fixture: the chat open did not pay",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() == 1 },
            )
        }

    @Test
    fun `a block pays the covered key exchange with the chat left open`() =
        runBlocking {
            val world = covered()
            val vm = world.screen().also(legs::over)

            world.block(vm)
            world.paymentsSettled()

            assertEquals("the covered key exchange waits for the chat to be opened again", 1, world.seam.submits.get())
        }

    @Test
    fun `a second block pays nothing more for the covered key exchange`() =
        runBlocking {
            val world = covered()
            val vm = world.screen().also(legs::over)

            world.block(vm)
            world.paymentsSettled()
            assertEquals("fixture: the first block did not pay the covered key exchange", 1, world.seam.submits.get())

            world.block(vm)
            world.paymentsSettled()

            assertEquals("a second block paid the covered key exchange again", 1, world.seam.submits.get())
        }
}
