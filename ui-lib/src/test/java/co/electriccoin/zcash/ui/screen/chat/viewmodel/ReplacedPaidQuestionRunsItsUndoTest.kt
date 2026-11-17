package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.restored
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T5b cross-review X-05 (SR-A and M2; found by GPT, widened on verification, train/T5b 469416b07): a paid question
 * that another one replaces drops its undo.
 *
 * A first message waits behind its question with its text taken out of the box and the draft; Cancel hands it back
 * (R3-2). askBeforePaying overwrites the waiting tap and its undo when a second question is asked. That happens when
 * another paid action's question arrives while the first is open, and, since M2, when two first messages are sent
 * quickly: each plan check answers later, and the second question replaces the first. The first text is then neither
 * sent nor handed back.
 *
 * Wanted: a question that is replaced reads as Cancel for the tap that was waiting: its undo runs once (the text is the
 * draft again and is handed back), nothing of it is paid, and the new question stays on screen. When the second
 * question is cancelled too, both texts are kept. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplacedPaidQuestionRunsItsUndoTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val FIRST = "synthetic first text, sent a moment before the second"
        const val SECOND = "synthetic second text"
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
    ) {
        vm.saveDraft(chat.peer, text)
        assertTrue(vm.sendMessage(chat.peer, text), "fixture sanity: the send behind the plan check is accepted")
        vm.clearDraft(chat.peer)
        scheduler.advanceUntilIdle()
    }

    private fun askedAction(vm: ChatViewModel): String? =
        (HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation)?.confirmation?.action?.name

    @Test
    fun `the question of another paid action that replaces a first message's question hands its text back`() =
        runBlocking {
            val peer = "usyntheticx05otherquestion01"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            typeAndSend(chat, vm, FIRST)
            assertEquals("FIRST_MESSAGE", askedAction(vm), "fixture sanity: the first message is not asked about")
            assertNull(chat.drafts[peer], "fixture sanity: the accepted send did not clear the draft")

            // Another paid tap asks its own question while the first one is still open.
            vm.setDisappearingTtl(peer, 3_600L)
            scheduler.advanceUntilIdle()

            assertEquals("DISAPPEARING_TIMER", askedAction(vm), "fixture sanity: the timer's question did not replace the first one")
            assertEquals(0, chat.proposals(), "a replaced question paid something")
            assertEquals(FIRST, chat.drafts[peer], "the text of the replaced question is gone: not sent and not the draft")
            assertEquals(peer to FIRST, restored(vm), "the text of the replaced question was not handed back to the input")
        }

    @Test
    fun `two first messages sent quickly - the second question hands the first text back`() =
        runBlocking {
            val peer = "usyntheticx05twoquicksends01"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(null)
            val check = GatedFirstMessagePlanCheck().also { it.install(vm) }

            typeAndSend(chat, vm, FIRST)
            typeAndSend(chat, vm, SECOND)
            assertEquals(2, check.calls, "fixture sanity: each first message asks its own plan check")
            check.answer(0, "Fundable")
            scheduler.advanceUntilIdle()
            assertEquals("FIRST_MESSAGE", askedAction(vm), "fixture sanity: the first text is not asked about")
            check.answer(1, "Fundable")
            scheduler.advanceUntilIdle()
            assertEquals("FIRST_MESSAGE", askedAction(vm), "fixture sanity: the second text is not asked about")

            assertEquals(0, chat.proposals(), "a replaced question paid something")
            assertEquals(FIRST, chat.drafts[peer], "the first text is gone: its question was replaced and nothing handed it back")
            assertEquals(peer to FIRST, restored(vm), "the first text was not handed back to the input")

            // Cancel on the second question: both texts are kept, the first one first.
            vm.dismissPaidAction()
            scheduler.advanceUntilIdle()

            assertEquals(0, chat.proposals(), "cancelling paid something")
            val draft = assertNotNull(chat.drafts[peer], "no draft after both questions were cancelled")
            assertTrue(FIRST in draft && SECOND in draft, "a cancelled text was dropped from the draft: $draft")
            assertTrue(draft.indexOf(FIRST) < draft.indexOf(SECOND), "the two texts are out of order: $draft")
        }

    /** Control, green before and after: Pay on a question that replaced nothing sends that message only. */
    @Test
    fun `control - Pay on the only question sends it and hands nothing back`() =
        runBlocking {
            val peer = "usyntheticx05controlpay00001"
            val chat = FirstMessagePlanChat(peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))

            typeAndSend(chat, vm, FIRST)
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals(2, chat.proposals(), "confirming pays the KEX and the message")
            assertNull(chat.drafts[peer], "a sent message was put back as the draft")
            assertNull(restored(vm), "a sent message was handed back to the input")
        }
}
