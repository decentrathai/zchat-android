package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.QuotedChatSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ZC-1352 (money-path rule 2), HONOR release 1 Nov: New Chat, Pay with funds confirming, force-stop, reopen, Delete the waiting
 * row under "It was not sent, so no Zcash transaction was made for it". A block later 17,000 zat went to that address.
 *
 * Must hold: Delete withdraws what the question covered, and no block or chat open pays for the deleted row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedWaitingRowWithdrawsItsCoveredLegsTest {
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

    private suspend fun payWhileConfirming(standingConsent: Boolean = false): OneDecisionWorld {
        val world = OneDecisionWorld()
        world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
        world.memory.consent = standingConsent
        world.fundsStillConfirming()
        val form = world.newChatIn(ConversationMode.TUNNEL)
        world.tap(form)
        world.pay(form)
        assertNotNull("fixture: Pay saved no waiting row", world.funds.row())
        assertEquals("fixture: Pay paid while the funds confirm", 0, world.submits())
        return world
    }

    /** The legs a question left on disk, as Retry's answer and a paid New Chat store them. */
    private fun coverLegs(world: OneDecisionWorld) {
        world.store.setQuotedChatSetup(world.peer, QuotedChatSetup(kex = LEG, zboot = LEG).encode())
        world.store.setZbootQuoted(world.peer, true)
    }

    private suspend fun coldStart(
        world: OneDecisionWorld,
        vm: ChatViewModel,
    ): ChatViewModel {
        val after = world.funds.restart(vm)
        PaymentHarness.setField(after, "zchatPreferences", world.store)
        PaymentHarness.setField(after, "accountDataSource", world.accounts)
        canHideMessages(after)
        return after
    }

    private suspend fun assertNothingPays(
        world: OneDecisionWorld,
        vararg screens: ChatViewModel,
        paidBefore: Int = 0,
    ) {
        assertNull("the deleted row's covered legs stayed on disk", world.store.getQuotedChatSetup(world.peer))
        assertFalse("the deleted row's ZBOOT allowance stands", world.store.isZbootQuoted(world.peer))
        world.fundsConfirmed()
        repeat(BLOCKS) {
            world.funds.block(*screens)
            world.funds.paymentsSettled()
        }
        assertEquals("a block paid for a row the user deleted", paidBefore, world.submits())
        screens.forEach { world.openChat(it) }
        world.funds.paymentsSettled()
        assertEquals("a chat open paid for a row the user deleted", paidBefore, world.submits())
        world.contactAnswered()
        world.funds.block(*screens)
        screens.forEach { world.openChat(it) }
        world.funds.paymentsSettled()
        assertEquals("the contact's answer paid for a row the user deleted", paidBefore, world.submits())
    }

    @Test
    fun `control - kept, the waiting row goes out on the block that can pay it`() =
        runBlocking {
            val world = payWhileConfirming()
            val vm = world.chatScreen()

            world.fundsConfirmed()
            world.funds.block(vm)
            world.funds.paymentsSettled()

            assertEquals("fixture: the kept row paid nothing", true, world.submits() > 0)
        }

    @Test
    fun `delete, no cold start - nothing pays`() =
        runBlocking {
            val world = payWhileConfirming()
            val vm = world.chatScreen()

            vm.hideMessage(world.funds.row()!!.id)

            assertNull("the deleted row is still on disk", world.funds.row())
            assertNothingPays(world, vm)
        }

    @Test
    fun `cold start, then delete - nothing pays`() =
        runBlocking {
            val world = payWhileConfirming()
            val vm = coldStart(world, world.chatScreen())
            world.funds.block(vm)

            vm.hideMessage(world.funds.row()!!.id)

            assertNothingPays(world, vm)
        }

    @Test
    fun `delete, then cold start - nothing pays`() =
        runBlocking {
            val world = payWhileConfirming()
            val vm = world.chatScreen()
            vm.hideMessage(world.funds.row()!!.id)

            assertNothingPays(world, coldStart(world, vm))
        }

    @Test
    fun `delete on the chat screen, with the list's screen beside it - nothing pays`() =
        runBlocking {
            val world = payWhileConfirming()
            val list = coldStart(world, world.chatScreen())
            val chat = world.chatScreen()
            world.funds.block(list, chat)

            chat.hideMessage(world.funds.row()!!.id)

            assertNothingPays(world, list, chat)
        }

    @Test
    fun `the phone's route with the standing consent given - nothing pays`() =
        runBlocking {
            val world = payWhileConfirming(standingConsent = true)
            val vm = coldStart(world, world.chatScreen())
            world.funds.block(vm)

            vm.hideMessage(world.funds.row()!!.id)

            assertNothingPays(world, vm)
        }

    @Test
    fun `a waiting row owning covered legs - delete withdraws them, nothing pays`() =
        runBlocking {
            val world = payWhileConfirming()
            coverLegs(world)
            val vm = coldStart(world, world.chatScreen())

            vm.hideMessage(world.funds.row()!!.id)

            assertNothingPays(world, vm)
        }

    @Test
    fun `a Retry-stopped row owning covered legs, nothing paid - delete withdraws them, nothing pays`() = stoppedRowDeleted(false)

    @Test
    fun `the same with the standing consent given`() = stoppedRowDeleted(true)

    private fun stoppedRowDeleted(standingConsent: Boolean) =
        runBlocking {
            val world = payWhileConfirming(standingConsent)
            coverLegs(world)
            val vm = world.chatScreen()
            val waiting = world.funds.row()!!
            world.chat.prefs.commitPendingMessage(waiting.copy(isPending = false, status = MessageStatus.FAILED.name))
            PaymentHarness.loadPendingMessagesFromPrefs(vm)

            vm.hideMessage(waiting.id)

            assertNothingPays(world, vm)
        }

    @Test
    fun `its key exchange already paid, the stopped row is deleted - the later ZBOOT leg is not paid`() = paidStepThenDeleted(false)

    @Test
    fun `the same with the standing consent given, the later ZBOOT leg is not paid`() = paidStepThenDeleted(true)

    private fun paidStepThenDeleted(standingConsent: Boolean) =
        runBlocking {
            val world = OneDecisionWorld()
            world.memory.consent = standingConsent
            world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
            val vm = world.chatScreen()
            val row = world.funds.acceptWhileConfirming(vm, OneDecisionWorld.FIRST_WORDS)
            world.wallet.feeFor = {
                if (world.submits() >= 1) throw InsufficientFundsException(have = Zatoshi(0), need = Zatoshi(LEG))
                FundsConfirmingChat.NORMAL_FEE
            }
            world.funds.plan = "Fundable"
            world.funds.block(vm)
            world.funds.paymentsSettled()
            assertEquals("fixture: the key exchange was not the one payment", 1, world.submits())
            var blocks = 0
            while (world.funds.row()?.status != MessageStatus.FAILED.name && blocks++ < BLOCKS_UNTIL_THE_QUEUE_GIVES_UP) {
                world.funds.block(vm)
                PaymentHarness.eventually(FundsConfirmingChat.QUIET_MS) { world.funds.row()?.status == MessageStatus.FAILED.name }
            }
            assertEquals("fixture: the row did not stop", MessageStatus.FAILED.name, world.funds.row()?.status)
            world.store.setQuotedChatSetup(world.peer, QuotedChatSetup(zboot = LEG).encode())
            world.store.setZbootQuoted(world.peer, true)

            vm.hideMessage(row.id)

            assertNothingPays(world, vm, paidBefore = 1)
        }

    private companion object {
        const val BLOCKS = 3
        const val BLOCKS_UNTIL_THE_QUEUE_GIVES_UP = 10
        const val LEG = 17_000L
    }
}
