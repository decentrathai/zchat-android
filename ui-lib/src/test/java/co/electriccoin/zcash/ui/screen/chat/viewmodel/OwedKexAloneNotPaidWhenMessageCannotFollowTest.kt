package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.1 "a second half-plan path", §2.4 step 6): a KEX still owed from an
 * earlier confirmed first message is paid alone on a block tick (onChainTip -> retryOwedKeyExchanges ->
 * retryOwedKeyExchange -> payOwedKex(forSend = false)), with no check that its message can follow.
 *
 * Must hold: with the plan (the KEX + the queued FAILED row's message) NotFundable, the tick pays nothing and the mark
 * stays owed (no key created either); with it Fundable, the tick pays the KEX once (control). The check is asked with
 * the FAILED row's own memo count and amount. At HEAD the tick pays the KEX alone. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwedKexAloneNotPaidWhenMessageCannotFollowTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticplanowedkexpeer001"
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
    fun `a block tick does not pay an owed KEX alone when its message cannot follow`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val check = FirstMessagePlanApi.ScriptedCheck("NotFundable")
            val vm = chat.viewModel(check)
            chat.kexOwed += PEER
            chat.seedFailedFirstMessage(vm, "pending_${System.nanoTime()}", TYPED)

            chat.tick(vm, 3_500_001L)
            awaitIdle(vm)
            chat.tick(vm, 3_500_002L)
            awaitIdle(vm)

            assertEquals(0, chat.proposals(), "a block tick paid an owed KEX whose message the wallet cannot fund")
            assertTrue(PEER in chat.kexOwed, "the owed-KEX mark was dropped")
            assertFalse(chat.kexOwedWrites.any { !it.second }, "the owed-KEX mark was cleared")
            assertFalse(chat.keyCreated(), "our key for the contact was created for a KEX not paid")
            assertTrue(check.calls.isNotEmpty(), "the tick did not check the plan")
            assertEquals(Triple(PEER, 1, 1_000L), check.calls.first(), "the check was not asked about the FAILED row's message")
        }

    /** Control, green before and after: a fundable plan still pays the owed KEX on a block tick, once. */
    @Test
    fun `control - a block tick pays an owed KEX once when its message can follow`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
            chat.kexOwed += PEER
            chat.seedFailedFirstMessage(vm, "pending_${System.nanoTime()}", TYPED)

            chat.tick(vm, 3_500_001L)
            awaitIdle(vm)
            chat.tick(vm, 3_500_002L)
            awaitIdle(vm)

            assertEquals(1, chat.proposals(), "the owed KEX was not paid (or paid twice)")
            assertFalse(PEER in chat.kexOwed, "the paid KEX is still owed")
        }
}
