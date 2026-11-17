package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 D7 (sweep #12, #10) — a 1:1 message sent while the wallet's own change confirms.
 *
 * Right after a send, the wallet's only Ironwood note is locked until its change confirms (3 blocks). A message typed
 * in that window is refused before anything is stored, in one of two ways:
 *
 * - no Orchard left: the "still confirming" shortfall, `InsufficientFundsException` with
 *   CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE ("…confirm on-chain…");
 * - some Orchard left (not moved yet, or dust too small to move): `OrchardSealedException(ironwoodPending > 0)`.
 *
 * (#12) doSendMessage queues a FRESH send for the next block only when the shortfall's text contains "previous
 * message", which no text does. So at 1b98dd127 both refusals make the fresh message FAILED at once, although it
 * only had to wait for a block. Wanted: the row waits (QUEUED_WAITING_FOR_BLOCK, not FAILED) and the queue sends it
 * once after the next block, as it does for a message that was already queued.
 *
 * (#10) With some Orchard left, the send gate in sendMessage stops the message before the queue and shows the
 * Stranded funds dialog with "Your first Ironwood funds have arrived and are still confirming" whenever any
 * Ironwood is pending, also when it is only our own change. Wanted: our own change never gets the first-funds
 * copy, and the message waits for the block like any other send. A first deposit that is still confirming keeps the
 * dialog and its copy (control).
 *
 * All addresses, keys and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendWaitsForOwnChangeTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private class Chat(
        val vm: ChatViewModel,
        val seam: ScriptedSeam,
        val accounts: OwnChangeAccounts,
    ) {
        fun state(): SendMessageState = HandshakeHarness.sendMessageState(vm)

        fun rows() = PaymentHarness.pendingMessages(vm)

        @Suppress("UNCHECKED_CAST")
        fun mineBlock(height: Long) {
            (
                ChatViewModel::class.java
                    .getDeclaredField("_blockHeight")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<Long?>
            ).value = height
        }

        /** The chat list state the send gate reads, with the privacy status the ViewModel derives from [account]. */
        @Suppress("UNCHECKED_CAST")
        fun showWallet(account: WalletAccount) {
            val derive =
                ChatViewModel::class.java.declaredMethods
                    .single { it.name == "computePrivacyStatus" && it.parameterCount == 3 }
                    .apply { isAccessible = true }
            val status = derive.invoke(vm, account, null, emptyList<Any>()) as PrivacyStatus
            (
                ChatViewModel::class.java
                    .getDeclaredField("_chatListState")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<ChatListState>
            ).value =
                ChatListState.Success(
                    conversations = emptyList(),
                    currentUserAddress = HandshakeHarness.SELF,
                    privacyStatus = status,
                )
        }
    }

    /** A Shielded chat with keys on both sides; the first proposal request meets [firstRequest] (null = none). */
    private suspend fun chat(firstRequest: Exception? = null): Chat {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT)
        val vm = fixture.viewModel()
        val accounts = OwnChangeAccounts.create()
        val seam = ScriptedSeam(failRequest = { n -> if (n == 1) firstRequest else null })
        AutoSpendVm.setField(vm, "createChunkedMessageProposal", seam.useCase(accounts))
        AutoSpendVm.setField(vm, "accountDataSource", accounts)
        // The step after a submit (addConversationPartner) reads the address cache; a constructor-free ViewModel has none.
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        return Chat(vm, seam, accounts)
    }

    /** The send is over or waiting: no longer Sending (the queued retry waits for a block, so jobs stay open). */
    private suspend fun settled(c: Chat) = awaitTrue { c.state() !is SendMessageState.Sending && c.state() != SendMessageState.Idle }

    private suspend fun waitsThenSendsOnce(c: Chat) {
        val accepted = c.vm.sendMessage(PEER, TYPED)
        settled(c)

        assertTrue("the message was refused before it was queued: ${c.state()}", accepted)
        val row = c.rows().single()
        assertNotEquals(
            "the message only waits for our own change to confirm, but it was failed at once " +
                "(reason: ${row.failureReason})",
            MessageStatus.FAILED,
            row.status,
        )
        assertEquals(
            "the screen must say it waits for the next block",
            SendMessageState.Success(ChatSendCopy.QUEUED_WAITING_FOR_BLOCK),
            c.state(),
        )
        assertEquals("nothing may reach the SDK while the change confirms", 0, c.seam.submits.get())

        c.accounts.ironwoodSpendable()
        c.mineBlock(1L)
        awaitTrue { c.seam.submits.get() == 1 && c.state() !is SendMessageState.Sending }

        assertEquals("after the block the waiting message is sent once", listOf(PEER), c.seam.submittedTo.toList())
        assertTrue("the row that was sent is not FAILED", c.rows().none { it.status == MessageStatus.FAILED })
        c.mineBlock(2L)
        assertEquals("one message, one payment", 1, c.seam.submits.get())
    }

    // ── (c) no Orchard left: the "still confirming" shortfall ─────────────────────────────────────────

    @Test
    fun `a fresh message that meets the still-confirming shortfall waits for the next block instead of failing`() =
        runBlocking {
            val c = chat(firstRequest = stillConfirming())
            c.accounts.ownChangeConfirmingNoOrchard()
            waitsThenSendsOnce(c)
        }

    // ── (d) some Orchard left: the sealed-pool refusal that says "wait" ───────────────────────────────

    @Test
    fun `a fresh message refused with wait-for-the-confirmation while Orchard is left waits for the next block`() =
        runBlocking {
            val c = chat()
            c.accounts.ownChangeConfirming()
            waitsThenSendsOnce(c)
        }

    // ── (e) the send gate and its copy ───────────────────────────────────────────────────────────────

    @Test
    fun `our own change confirming with Orchard left never shows the first Ironwood funds copy and the message waits`() =
        runBlocking {
            val c = chat()
            c.accounts.ownChangeConfirming()
            c.showWallet(c.accounts.current())

            val accepted = c.vm.sendMessage(PEER, TYPED)
            settled(c)

            val state = c.state()
            assertTrue(
                "our own change is not \"your first Ironwood funds have arrived\": $state",
                (state as? SendMessageState.NeedsIronwoodMigration)?.message != ChatSendCopy.IRONWOOD_FIRST_FUNDS_CONFIRMING,
            )
            assertTrue("a send that only waits for our own change must be accepted, was $state", accepted)
            assertEquals(SendMessageState.Success(ChatSendCopy.QUEUED_WAITING_FOR_BLOCK), state)

            c.accounts.ironwoodSpendable()
            c.mineBlock(1L)
            awaitTrue { c.seam.submits.get() == 1 && c.state() !is SendMessageState.Sending }
            assertEquals(listOf(PEER), c.seam.submittedTo.toList())
        }

    @Test
    fun `control - a first deposit still confirming with Orchard left keeps the first Ironwood funds dialog`() =
        runBlocking {
            val c = chat()
            c.accounts.firstDepositConfirming()
            c.showWallet(c.accounts.current())

            val accepted = c.vm.sendMessage(PEER, TYPED)

            assertEquals("the send is refused before anything is queued", false, accepted)
            val state = c.state()
            assertTrue("the Stranded funds dialog is shown: $state", state is SendMessageState.NeedsIronwoodMigration)
            assertEquals(
                ChatSendCopy.IRONWOOD_FIRST_FUNDS_CONFIRMING,
                (state as SendMessageState.NeedsIronwoodMigration).message,
            )
            assertEquals("nothing was proposed", 0, c.seam.proposalRequests.get())
            assertTrue("no row is left behind", c.rows().isEmpty())
        }

    private companion object {
        const val PEER = "usyntheticd7ownchangepeer0001"
        const val TYPED = "hello while the change confirms"
    }
}
