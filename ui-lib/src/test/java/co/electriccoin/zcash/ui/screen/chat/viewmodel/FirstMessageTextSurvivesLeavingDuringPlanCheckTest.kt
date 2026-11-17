package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.restored
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T5b cross-review X-02 (M2; found by GPT, train/T5b 469416b07): a typed first message is lost.
 *
 * Since M2 the first-message question is asked only after the plan check answered, and the check runs in the chat
 * screen's own scope. sendMessage has already returned "accepted" by then, so the screen cleared the input box and the
 * saved draft. Leaving the chat while the check is still running cancels it: no question, no row, and the text is in
 * neither the box nor the draft. Before M2 the question was on screen at once.
 *
 * Wanted: a first message whose plan check is cancelled before it answered is the chat's draft again (and handed back to
 * the input, for a screen that is still open); nothing is paid. A check that answers is unchanged (control). All
 * addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageTextSurvivesLeavingDuringPlanCheckTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val TYPED = "synthetic first words typed just before leaving the chat"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
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
    }

    /** What the chat screen does on Send, without waiting for the plan check: an accepted send clears box and draft. */
    private fun typeAndSend(
        chat: FirstMessagePlanChat,
        vm: ChatViewModel,
        text: String,
    ): Boolean {
        vm.saveDraft(chat.peer, text)
        val accepted = vm.sendMessage(chat.peer, text)
        if (accepted) vm.clearDraft(chat.peer)
        scheduler.advanceUntilIdle()
        return accepted
    }

    @Test
    fun `leaving the chat while the plan is still being checked keeps the typed text as the chat's draft`() =
        runBlocking {
            val peer = "usyntheticx02leftduringcheck"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(null)
            val check = GatedFirstMessagePlanCheck().also { it.install(vm) }

            assertTrue(typeAndSend(chat, vm, TYPED), "fixture sanity: the send behind the plan check is accepted")
            assertEquals(1, check.calls, "fixture sanity: the plan check was not asked")
            assertFalse(chat.asked(vm), "fixture sanity: the question is on screen before the plan check answered")
            assertNull(chat.drafts[peer], "fixture sanity: the accepted send did not clear the draft")

            // The user leaves the chat: the screen's scope is cancelled with the check still running.
            vm.viewModelScope.cancel()
            scheduler.advanceUntilIdle()

            assertEquals(0, chat.proposals(), "leaving the chat paid something")
            assertFalse(chat.asked(vm), "a question was raised for a chat the user left")
            assertEquals(TYPED, chat.drafts[peer], "the typed first message is gone: not sent, not asked about and not the draft")
            assertEquals(peer to TYPED, restored(vm), "the typed first message was not handed back to the input")
        }

    /** Control, green before and after: a check that answers raises the question and hands nothing back. */
    @Test
    fun `control - a plan check that answers Fundable raises the question and keeps the text with it`() =
        runBlocking {
            val peer = "usyntheticx02checkanswers001"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(null)
            val check = GatedFirstMessagePlanCheck().also { it.install(vm) }

            assertTrue(typeAndSend(chat, vm, TYPED))
            check.answer(0, "Fundable")
            scheduler.advanceUntilIdle()

            assertTrue(chat.asked(vm), "a fundable first message is not asked about")
            assertNull(chat.drafts[peer], "the text of a message waiting behind its question was put back as the draft")
            assertNull(restored(vm), "the text of a message waiting behind its question was handed back")
            assertEquals(0, chat.proposals(), "something was paid before the question was answered")
        }
}
