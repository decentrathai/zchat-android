package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * ZC-1390 (high, money-path rule 2; Fable review at c39a0f61c): ZC-1364 gave the ZBOOT leg of New Chat's one question
 * (QuotedChatSetup.zboot) an account gate, and not the allowance every other question writes: recordZbootQuote stores only
 * setZbootQuoted(peer, true), which names no account. Account A confirms a Tunnel switch of a Shielded chat whose key
 * exchange is still waiting for the contact's answer (the switch quoted our ZBOOT "once the contact answers"); the user
 * leaves and switches to account B; the answer lands; the next block's retryPendingTunnelZboots spends that allowance out
 * of B, who never saw the question.
 *
 * Must hold: as B no block pays the ZBOOT (a Tunnel chat whose ZBOOT is due, and a quoted ZBOOT marked owed), also after a
 * restart; A selected again still pays it, once. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchQuotedZbootBelongsToTheAccountThatConfirmedTest {
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

    /**
     * A Shielded chat whose own KEX went out and waits for the contact's answer. As A: Tunnel switch, Pay on its question
     * (the ZBOOT is quoted, held until the answer); then the contact's answer lands. [owed] lists the contact as a quoted
     * ZBOOT marked owed (the attempt was cut off); otherwise it is due as a Tunnel chat. Returns what was paid so far.
     */
    private suspend fun switchConfirmedAsA(owed: Boolean): Pair<OneDecisionWorld, Int> {
        val world = OneDecisionWorld()
        world.accounts.account.value = accountA
        world.fundsConfirmed()
        world.chat.fixture.mode = ConversationMode.VAULT
        val ours = E2EEncryption.generateKeyPair()
        world.store.setE2EOurKeys(world.peer, ours.publicKey, ours.privateKey)
        world.store.setOwnBootSent(world.peer, true)
        val vm = world.chatScreen()

        vm.switchConversationMode(world.peer, ConversationMode.TUNNEL)
        HandshakeHarness.awaitIdle(vm)
        assertNotNull("fixture: the Tunnel switch asked no price question", world.paidQuestion(vm))
        vm.confirmPaidAction()
        HandshakeHarness.awaitIdle(vm)
        world.funds.paymentsSettled()
        assertTrue("fixture: Pay on the switch left no ZBOOT allowance on disk", world.store.isZbootQuoted(world.peer))
        assertNull(
            "fixture: this allowance must come from recordZbootQuote, not New Chat's QuotedChatSetup",
            QuotedChatSetup.decode(world.store.getQuotedChatSetup(world.peer))?.zboot,
        )
        world.funds.leave(vm)

        world.contactAnswered()
        if (owed) {
            world.store.setZbootOwed(world.peer, true)
            world.memory.answers["getZbootOwedPeers"] = setOf(world.peer)
            world.memory.answers["getTunnelModePeers"] = emptyList<String>()
        } else {
            world.memory.answers["getTunnelModePeers"] = listOf(world.peer)
            world.memory.answers["getZbootOwedPeers"] = emptySet<String>()
        }
        return world to world.submits()
    }

    private suspend fun blockOn(
        world: OneDecisionWorld,
        vm: ChatViewModel,
    ) {
        world.funds.block(vm)
        world.funds.paymentsSettled()
    }

    @Test
    fun `control - account A's next block pays the ZBOOT its switch question quoted`() =
        runBlocking {
            val (world, paid) = switchConfirmedAsA(owed = false)
            val vm = world.chatScreen()

            blockOn(world, vm)

            assertEquals("fixture: A's own block did not pay A's quoted ZBOOT", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B a Tunnel chat's block pays no ZBOOT A's question quoted, and A still pays`() =
        runBlocking {
            val (world, paid) = switchConfirmedAsA(owed = false)
            val vm = world.chatScreen()

            world.accounts.account.value = accountB
            repeat(BLOCKS) { blockOn(world, vm) }

            assertEquals("account B paid the ZBOOT account A's Tunnel switch quoted", paid, world.submits())

            world.accounts.account.value = accountA
            blockOn(world, vm)
            assertEquals("A selected again: its quoted ZBOOT was not paid", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B an owed quoted ZBOOT is not paid by a block, and A still pays`() =
        runBlocking {
            val (world, paid) = switchConfirmedAsA(owed = true)
            val vm = world.chatScreen()

            world.accounts.account.value = accountB
            repeat(BLOCKS) { blockOn(world, vm) }

            assertEquals("account B paid the owed ZBOOT account A's Tunnel switch quoted", paid, world.submits())

            world.accounts.account.value = accountA
            blockOn(world, vm)
            assertEquals("A selected again: its owed ZBOOT was not paid", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B and a restart a block pays no ZBOOT A's question quoted, and A still pays`() =
        runBlocking {
            val (world, paid) = switchConfirmedAsA(owed = true)
            world.accounts.account.value = accountB

            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            FirstMessagePlanChat.resetOwedKexState()
            LiveSenders.resetForTest()
            val restarted = world.chatScreen()
            repeat(BLOCKS) { blockOn(world, restarted) }

            assertEquals("after a restart account B paid the ZBOOT account A's Tunnel switch quoted", paid, world.submits())

            world.accounts.account.value = accountA
            blockOn(world, restarted)
            assertEquals("after a restart A selected again: its quoted ZBOOT was not paid", paid + 1, world.submits())
        }

    private companion object {
        const val BLOCKS = 3
        const val OTHER_ACCOUNT = "00000000-0000-4000-8000-000000001390"
    }
}
