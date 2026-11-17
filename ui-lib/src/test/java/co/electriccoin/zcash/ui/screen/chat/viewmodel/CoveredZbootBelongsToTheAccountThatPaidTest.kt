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
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * ZC-1364 (money-path rule 2): ZC-1348 gave the covered key exchange an account gate (coveredSetupIsOf), and not the ZBOOT
 * leg of the same question. Account A answers New Chat's Tunnel question with Pay (KEX, ZBOOT, message); the user switches
 * to account B before the contact's answer lands. The next block's retryPendingTunnelZboots spends the ZBOOT allowance
 * (isZbootQuoted, which names no account) out of B, who never saw the question.
 *
 * Must hold: as B no block pays the ZBOOT, also after a restart; A selected again still pays it. Both block candidates are
 * pinned: a Tunnel chat whose ZBOOT is due, and a contact whose quoted ZBOOT is marked owed. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoveredZbootBelongsToTheAccountThatPaidTest {
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
     * Pay on New Chat's Tunnel question as A, then the contact's answer: the key exchange is complete and only our ZBOOT is
     * due. [owed] lists the contact as a quoted ZBOOT marked owed; otherwise it is due as a Tunnel chat. Returns what was paid.
     */
    private suspend fun answeredAsA(owed: Boolean): Pair<OneDecisionWorld, Int> {
        val world = OneDecisionWorld()
        world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
        world.accounts.account.value = accountA
        world.fundsConfirmed()
        val form = world.newChatIn(ConversationMode.TUNNEL)
        world.tap(form)
        world.pay(form)
        world.funds.paymentsSettled()
        assertNotNull(
            "fixture: Pay left no covered ZBOOT on disk",
            QuotedChatSetup.decode(world.store.getQuotedChatSetup(world.peer))?.zboot,
        )
        // Our key for this contact exists (its KEX built it) and the contact's answer reached this phone.
        val ours = E2EEncryption.generateKeyPair()
        world.store.setE2EOurKeys(world.peer, ours.publicKey, ours.privateKey)
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
    fun `control - account A's next block pays the ZBOOT its question covered`() =
        runBlocking {
            val (world, paid) = answeredAsA(owed = false)
            val vm = world.chatScreen()

            blockOn(world, vm)

            assertEquals("fixture: A's own block did not pay A's covered ZBOOT", paid + 1, world.submits())
        }

    @Test
    fun `control - account A's next block pays its owed quoted ZBOOT`() =
        runBlocking {
            val (world, paid) = answeredAsA(owed = true)
            val vm = world.chatScreen()

            blockOn(world, vm)

            assertEquals("fixture: A's own block did not pay A's owed ZBOOT", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B a Tunnel chat's block pays no ZBOOT, and A still pays`() =
        runBlocking {
            val (world, paid) = answeredAsA(owed = false)
            val vm = world.chatScreen()

            world.accounts.account.value = accountB
            repeat(BLOCKS) { blockOn(world, vm) }

            assertEquals("account B paid the ZBOOT account A's question covered", paid, world.submits())

            world.accounts.account.value = accountA
            blockOn(world, vm)
            assertEquals("A selected again: its covered ZBOOT was not paid", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B an owed quoted ZBOOT is not paid by a block, and A still pays`() =
        runBlocking {
            val (world, paid) = answeredAsA(owed = true)
            val vm = world.chatScreen()

            world.accounts.account.value = accountB
            repeat(BLOCKS) { blockOn(world, vm) }

            assertEquals("account B paid the owed ZBOOT account A's question covered", paid, world.submits())

            world.accounts.account.value = accountA
            blockOn(world, vm)
            assertEquals("A selected again: its owed ZBOOT was not paid", paid + 1, world.submits())
        }

    @Test
    fun `after a switch to account B and a restart a block pays no ZBOOT, and A still pays`() =
        runBlocking {
            val (world, paid) = answeredAsA(owed = false)
            world.accounts.account.value = accountB

            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            FirstMessagePlanChat.resetOwedKexState()
            LiveSenders.resetForTest()
            val restarted = world.chatScreen()
            repeat(BLOCKS) { blockOn(world, restarted) }

            assertEquals("after a restart account B paid the ZBOOT account A's question covered", paid, world.submits())

            world.accounts.account.value = accountA
            blockOn(world, restarted)
            assertEquals("after a restart A selected again: its covered ZBOOT was not paid", paid + 1, world.submits())
        }

    private companion object {
        const val BLOCKS = 3
        const val OTHER_ACCOUNT = "00000000-0000-4000-8000-000000001364"
    }
}
