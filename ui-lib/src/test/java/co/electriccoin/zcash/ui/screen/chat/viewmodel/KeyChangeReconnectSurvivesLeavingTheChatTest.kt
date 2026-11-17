package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ZC-300 (fix-all 2.13.0, train T5b; r8-findings SPECS §16.1, NEW-KCR) — a confirmed key-change reconnect loses its KEX
 * when the user leaves the chat.
 *
 * ChatViewModel.dismissE2EKeyChanged clears the flag and drops the contact's relay key, then sendKEXMessage launches the
 * paid KEX on the chat screen's viewModelScope. With a single spendable note the KEX waits for a block; leaving the chat
 * (or the end of the process) cancels it there, and nothing records that a KEX is still owed: the chat is left with no
 * relay key and no KEX.
 *
 * Must hold: the reconnect's KEX is recorded as owed (reason "reconnect", the ZBOOT allowance with it) before the flag
 * clears and the relay key drops, and is paid on the process-wide send scope; leaving the chat or a kill while it waits
 * for a block does not lose it, and it is paid exactly once (also when M2's first-message plan check says a message could
 * not follow: no message is in the reconnect's quote).
 *
 * Drives the real ViewModel through the real use case and repository ([KeyChangeReconnectChat]). Every address and key is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyChangeReconnectSurvivesLeavingTheChatTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc300reconnleave1"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `a confirmed reconnect's KEX waiting for a block is still paid once after the chat is left`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()
            confirmReconnectAndWaitForABlock(chat, vm)

            // The user leaves the chat: its screen's ViewModel is cleared while the KEX waits for a block.
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            chat.owed.heights.advance()
            awaitIdle(vm)

            assertEquals(
                "the KEX of a confirmed reconnect was lost when the chat screen closed: the relay key is dropped and nothing was sent",
                1,
                chat.seam.kexAnswered.get(),
            )
            assertEquals("the KEX was submitted once", 1, chat.seam.submittedOf("KEX"))
            assertFalse("the paid KEX is still marked owed", PEER in chat.owed.kexOwed)
        }

    @Test
    fun `the reconnect's KEX is recorded as owed before the flag clears and the relay key drops`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, failFirstKexWithStillConfirming = true)
            val vm = chat.viewModel()
            confirmReconnectAndWaitForABlock(chat, vm)

            assertEquals("the reconnect's KEX is owed while it waits for a block", true, PEER in chat.owed.kexOwed)
            assertEquals("the owed KEX names the reconnect as its reason", "reconnect", chat.owed.kexOwedReason[PEER])
            assertEquals("the reconnect quoted our ZBOOT: its allowance is written", true, chat.owed.zbootQuoted[PEER])
            assertEquals("the flag was cleared before the owed KEX was recorded", true, chat.owedWhenTheFlagCleared)
            assertEquals("the relay key was dropped before the owed KEX was recorded", true, chat.owedWhenTheRelayKeyDropped)
        }

    @Test
    fun `a reconnect's KEX interrupted by the end of the process is paid once by the next process`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, failFirstKexWithStillConfirming = true)
            val first = chat.viewModel()
            confirmReconnectAndWaitForABlock(chat, first)

            ResetOwedChat.kill(first)

            val next = chat.viewModel()
            chat.owed.tick(next)
            awaitIdle(next)

            assertEquals("the KEX of a confirmed reconnect was never sent after the process ended", 1, chat.seam.kexAnswered.get())
            assertNoQuestion(next)
            assertFalse("the paid KEX is still marked owed", PEER in chat.owed.kexOwed)

            chat.owed.tick(next)
            awaitIdle(next)
            chat.owed.openChat(next)
            awaitIdle(next)
            assertEquals("the reconnect's KEX was paid a second time", 1, chat.seam.kexAnswered.get())
            assertEquals(1, chat.seam.submittedOf("KEX"))
        }

    @Test
    fun `a reconnect's owed KEX is paid after a kill even when a first message could not follow it`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, failFirstKexWithStillConfirming = true)
            val notFundable = FirstMessagePlanApi.ScriptedCheck("NotFundable")
            val first = chat.viewModel(planCheck = notFundable)
            confirmReconnectAndWaitForABlock(chat, first)

            ResetOwedChat.kill(first)

            val next = chat.viewModel(planCheck = notFundable)
            chat.owed.tick(next)
            awaitIdle(next)

            assertEquals(
                "M2's first-message plan gate held the reconnect's lone KEX (its quote has no message)",
                1,
                chat.seam.kexAnswered.get(),
            )
            assertFalse("the paid KEX is still marked owed", PEER in chat.owed.kexOwed)
        }

    private suspend fun confirmReconnectAndWaitForABlock(
        chat: KeyChangeReconnectChat,
        vm: ChatViewModel,
    ) {
        vm.dismissE2EKeyChanged(PEER)
        awaitIdle(vm)
        chat.assertAskedFirst(vm)
        vm.confirmPaidAction()
        awaitTrue { chat.owed.heights.isWaiting() }
        assertEquals("fixture sanity: the KEX met the still-confirming wait", 1, chat.seam.kexProposals.get())
        assertEquals("fixture sanity: no KEX went yet", 0, chat.seam.kexAnswered.get())
        assertFalse("Pay accepts the new key", chat.keyChanged)
        assertNull("Pay drops the old relay key", chat.relayKey)
    }

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse(
            "the user was asked again to pay for the KEX the reconnect already quoted: $state",
            state.javaClass.simpleName == "NeedsPaidActionConfirmation" || state is SendMessageState.NeedsHandshakeSpendConsent,
        )
    }
}
