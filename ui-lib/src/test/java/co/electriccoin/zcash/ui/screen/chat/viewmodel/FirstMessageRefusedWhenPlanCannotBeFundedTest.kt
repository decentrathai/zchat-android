package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.copy
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.error
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.restored
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.1-2.3, t5b SPECS §2 C-M2-6, C-M2-7): the first-message question accepted
 * a plan the wallet could not fund. On the Seeker "2 on-chain transactions: at least 0.00034 ZEC" was asked, Pay sent
 * the key exchange (17,000 zat), and the message then failed for funds: half a send paid for.
 *
 * Must hold: when the plan check answers NotFundable, the user is not asked to pay (the courtesy check before the
 * question), and nothing is paid or written: no proposal, no owed-KEX mark, no key of ours for the contact; the send
 * shows "can't pay for both transactions" (ChatSendCopy.FIRST_MESSAGE_PLAN_UNFUNDABLE) and hands the typed text back to
 * the input and the draft. At HEAD the question is asked and Pay sends the KEX (the test answers it as the user would).
 *
 * Real ChatViewModel send path through the real use case and repository ([FirstMessagePlanChat]); the plan check is a
 * scripted fake set by name. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageRefusedWhenPlanCannotBeFundedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplanrefusedpeer001"
        const val TYPED = "synthetic first words to a new contact"
    }

    @Before
    fun setUp() {
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
    }

    @Test
    fun `a first message whose plan cannot be funded is refused before the question and pays nothing`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val check = FirstMessagePlanApi.ScriptedCheck("NotFundable")
            val vm = chat.viewModel(check)

            chat.typeAndSend(vm, TYPED)
            val asked = chat.asked(vm)
            if (asked) {
                // What the user did on the Seeker: Pay.
                vm.confirmPaidAction()
                awaitIdle(vm)
            }

            assertEquals(0, chat.proposals(), "a first message the wallet cannot fund paid something (the KEX goes first)")
            assertFalse(asked, "the user was asked to pay for a plan the wallet cannot fund")
            assertFalse(chat.owedMarkWritten(), "a refused first message wrote the owed-KEX mark")
            assertFalse(chat.keyCreated(), "a refused first message created our key for the contact")
            assertEquals(copy("FIRST_MESSAGE_PLAN_UNFUNDABLE"), error(vm))
            assertEquals(PEER to TYPED, restored(vm), "the typed text was not handed back to the input")
            assertEquals(TYPED, chat.drafts[PEER], "the typed text is not the chat's draft again")
            assertEquals(listOf(Triple(PEER, 1, 1_000L)), check.calls, "the check was not asked about this message")
        }

    @Test
    fun `the refusal text says nothing was paid, the text is kept and ZEC is needed`() {
        val text = copy("FIRST_MESSAGE_PLAN_UNFUNDABLE")
        assertTrue("Nothing was paid" in text, text)
        assertTrue("Add ZEC" in text, text)
        assertTrue("still in the box" in text, text)
        val row = copy("FIRST_MESSAGE_PLAN_UNFUNDABLE_ROW")
        assertTrue("Retry" in row, row)
        assertFalse("box" in row, "a row's reason must not speak of the input box: $row")
    }
}
