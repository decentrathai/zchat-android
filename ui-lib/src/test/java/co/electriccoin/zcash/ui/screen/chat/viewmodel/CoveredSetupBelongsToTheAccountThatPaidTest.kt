package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * ZC-1348 (money-path rule 2): QuotedChatSetup stores what the question covered and not whose question it was, in the mode
 * store a wallet restore keeps. Pay on New Chat as account A, then switch to B or restore another seed over the kept store:
 * the next block (payCoveredKeyExchange) or chat open (startNostrBootstrap) pays A's key exchange out of B, who never saw
 * the question.
 *
 * Must hold: B pays nothing and A's leg stays on disk, so A still pays when it is selected again. No seam is named: Pay goes
 * through the real New Chat and [OneDecisionWorld.RememberingStore] keeps whatever the fix stores. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoveredSetupBelongsToTheAccountThatPaidTest {
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

    private val accountA = walletWith(orchard = 0, ironwood = OneDecisionWorld.ONE_NOTE)
    private val accountB =
        accountA.copy(sdkAccount = AccountFixture.new(accountUuid = UUID.fromString(OTHER_ACCOUNT)))

    /** New Chat's question answered with Pay as A; returns the world and what the message itself paid. */
    private suspend fun paidAsA(): Pair<OneDecisionWorld, Int> {
        val world = OneDecisionWorld()
        // The store's list of contacts with a record: this world's one contact (the block's pass reads the leg itself).
        world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
        world.accounts.account.value = accountA
        world.fundsConfirmed()
        val form = world.newChatIn(ConversationMode.TUNNEL)
        world.tap(form)
        world.pay(form)
        world.funds.paymentsSettled()
        assertNotNull("fixture: Pay left no covered key exchange on disk", leg(world))
        return world to world.submits()
    }

    private fun leg(world: OneDecisionWorld): Long? = QuotedChatSetup.decode(world.store.getQuotedChatSetup(world.peer))?.kex

    @Test
    fun `control - account A's next block pays its covered key exchange`() =
        runBlocking {
            val (world, paid) = paidAsA()
            val vm = world.chatScreen()

            world.funds.block(vm)
            world.funds.paymentsSettled()

            assertEquals("fixture: A's own block did not pay A's key exchange", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B the next block pays nothing, leaves the leg and A still pays`() =
        runBlocking {
            val (world, paid) = paidAsA()
            val vm = world.chatScreen()

            world.accounts.account.value = accountB
            world.funds.block(vm)
            world.funds.paymentsSettled()

            assertEquals("account B paid the key exchange account A's question covered", paid, world.submits())
            assertNotNull("B's block used up A's leg", leg(world))

            world.accounts.account.value = accountA
            world.funds.block(vm)
            world.funds.paymentsSettled()
            assertEquals("A selected again: its covered key exchange was not paid", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B a chat open pays nothing, and A's block still pays`() =
        runBlocking {
            val (world, paid) = paidAsA()
            val vm = world.chatScreen()

            world.accounts.account.value = accountB
            world.openChat(vm)
            world.funds.paymentsSettled()

            assertEquals("a chat open as B paid the key exchange A's question covered", paid, world.submits())

            world.accounts.account.value = accountA
            world.funds.block(vm)
            world.funds.paymentsSettled()
            assertEquals("B's chat open left A unable to pay its covered key exchange", paid + 1, world.submits())
        }

    @Test
    fun `a different seed restored over the kept store pays nothing on its first block`() =
        runBlocking {
            val (world, paid) = paidAsA()

            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            FirstMessagePlanChat.resetOwedKexState()
            world.accounts.account.value = accountB
            val restored = world.chatScreen()
            world.funds.block(restored)
            world.funds.paymentsSettled()

            assertEquals("a restored wallet paid the key exchange the old seed's question covered", paid, world.submits())
        }

    private companion object {
        const val OTHER_ACCOUNT = "00000000-0000-4000-8000-000000001348"
    }
}
