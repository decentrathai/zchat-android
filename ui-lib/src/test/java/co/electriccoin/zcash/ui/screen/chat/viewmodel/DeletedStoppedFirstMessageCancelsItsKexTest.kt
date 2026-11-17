package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1309 (money-path rule 2): the user deleted a first message that never went out. The key exchange was owed only for that
 * message: deleting it cancels the owed mark, and no later block (or process) pays the key exchange alone. A key exchange a
 * Reset owes is not the message's and stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedStoppedFirstMessageCancelsItsKexTest {
    private val sendScopes = SendHarnessScopes()

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

    private fun FundsConfirmingChat.owesKex(): Boolean = peer in chat.kexOwed

    private suspend fun assertNoKexAlone(
        world: OneDecisionWorld,
        vm: ChatViewModel,
    ) {
        world.fee(FundsConfirmingChat.NORMAL_FEE)
        world.funds.plan = "Fundable"
        repeat(BLOCKS) { world.funds.block(vm) }
        // Read in this order: an assert's message is built before its condition runs.
        val nothingPaid = world.funds.staysAt(0)
        assertTrue("a block paid the key exchange of a message the user deleted: ${world.submits()} submits", nothingPaid)
        assertFalse("the deleted message's key exchange is still owed", world.funds.owesKex())
    }

    @Test
    fun `deleting a row stopped above its price cancels its owed key exchange`() =
        runBlocking {
            val world = OneDecisionWorld()
            val vm = world.chatScreen()
            val row = world.funds.acceptWhileConfirming(vm, OneDecisionWorld.FIRST_WORDS)
            world.fee(FEE_ABOVE_THE_TOTAL)
            world.funds.plan = "Fundable"
            world.funds.block(vm)
            assertTrue(
                "fixture sanity: the row did not stop",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.funds.row()?.status == MessageStatus.FAILED.name },
            )
            assertTrue("fixture sanity: the stopped row's key exchange is owed", world.funds.owesKex())

            vm.hideMessage(row.id)

            assertNoKexAlone(world, vm)
        }

    /** ZC-1272: a row still waiting for its funds is taken out by Delete, so no later block can send it. */
    @Test
    fun `deleting a row waiting for funds removes it and no block pays it`() =
        runBlocking {
            val world = OneDecisionWorld()
            val vm = world.chatScreen()
            val row = world.funds.acceptWhileConfirming(vm, OneDecisionWorld.FIRST_WORDS)
            assertTrue("fixture sanity: the accepted row does not wait for its funds", world.funds.isWaiting(row.id))

            vm.hideMessage(row.id)

            assertNull("the deleted row is still on disk, waiting for funds", world.funds.row())
            assertNoKexAlone(world, vm)
        }

    @Test
    fun `deleting a stopped first message with no accepted price left cancels its owed key exchange`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.funds.plan = "Fundable"
            val vm = world.chatScreen()
            val id = "pending_${System.nanoTime()}"
            world.chat.kexOwed += world.peer
            world.chat.seedFailedFirstMessage(vm, id, OneDecisionWorld.FIRST_WORDS)

            vm.hideMessage(id)

            assertNoKexAlone(world, vm)
        }

    @Test
    fun `after a restart the deleted message's key exchange is still not paid`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.funds.plan = "Fundable"
            val vm = world.chatScreen()
            val id = "pending_${System.nanoTime()}"
            world.chat.kexOwed += world.peer
            world.chat.seedFailedFirstMessage(vm, id, OneDecisionWorld.FIRST_WORDS)
            vm.hideMessage(id)

            val after = world.funds.restart(vm)
            PaymentHarness.setField(after, "zchatPreferences", world.store)
            PaymentHarness.setField(after, "accountDataSource", world.accounts)

            assertNoKexAlone(world, after)
        }

    /** Control, green before and after: a key exchange a Reset owes stands alone; deleting a message does not touch it. */
    @Test
    fun `control - a Reset's owed key exchange survives deleting a message`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.funds.plan = "Fundable"
            val vm = world.chatScreen()
            val id = "pending_${System.nanoTime()}"
            world.chat.kexOwed += world.peer
            world.memory.answers["getKexOwedReason"] = ZchatPreferences.KEX_OWED_REASON_RESET
            world.chat.seedFailedFirstMessage(vm, id, OneDecisionWorld.FIRST_WORDS)

            vm.hideMessage(id)
            world.funds.block(vm)

            assertTrue(
                "a Reset's key exchange was cancelled with a message",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
                    world.funds.owesKex() || world.submits() == 1
                },
            )
            assertEquals("a Reset's key exchange was paid more than once", true, world.submits() <= 1)
        }

    private companion object {
        const val BLOCKS = 3
        const val FEE_ABOVE_THE_TOTAL = 100_000L
    }
}
