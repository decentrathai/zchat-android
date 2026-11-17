package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1230, ZC-1233 follow-up — what the fix left untested: the question an automatic hold raises on the next chat open,
 * what Pay and Cancel do on every question of the true price, undoTunnelOfferAccept, and a build whose price moves after Pay.
 *
 * Money figures: one 2,000 zat output; a 15,000 fee for the one-note build (17,000, the handshake); [DEAR] raises the fee to
 * 20,000 (22,000, as the Fold's SDK priced it), [DEARER] to 25,000 (27,000). `paidFigures` reads the fee from the very
 * proposal handed to submit. The proposals are real ones at ProposalDataSource; the send path above them is the real one.
 * No wire or stored format is involved. Every address, key and id is synthetic.
 */
class KexAnswerTruePriceIsAskedAndPaidOnceTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun phone(
        mode: ConversationMode,
        consent: Boolean,
    ) = OpenChatTunnelOfferPhone(LedgerWorld(mode = mode, consent = consent, accountSource = OpenChatTunnelOfferPhone.funded()))

    private val OpenChatTunnelOfferPhone.seam get() = world.seam
    private val OpenChatTunnelOfferPhone.peer get() = world.peer

    private fun question(vm: ChatViewModel): PaidActionConfirmation {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("no question is on screen: $state", state is SendMessageState.NeedsPaidActionConfirmation)
        return (state as SendMessageState.NeedsPaidActionConfirmation).confirmation
    }

    private fun assertAskedTheTruePrice(
        vm: ChatViewModel,
        figure: Long,
    ) {
        val asked = question(vm)
        assertEquals(PaidAction.KEX_ANSWER_TRUE_PRICE, asked.action)
        assertEquals("the question's figure", Zatoshi(figure), asked.totalCost)
        assertTrue("the true price is not asked as exact", asked.exact)
        assertEquals("the question's network fee", Zatoshi(figure - OUTPUT), asked.networkFee)
        assertEquals("one answer", 1, asked.onChainMessages)
    }

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse("a question was raised: $state", state is SendMessageState.NeedsPaidActionConfirmation)
    }

    private suspend fun OpenChatTunnelOfferPhone.chatOpen(vm: ChatViewModel) {
        call(vm, "retryKexAckIfResponder")
        HandshakeHarness.awaitIdle(vm)
    }

    private suspend fun OpenChatTunnelOfferPhone.tap(
        vm: ChatViewModel,
        entry: String,
    ) {
        call(vm, entry)
        HandshakeHarness.awaitIdle(vm)
    }

    private suspend fun confirm(vm: ChatViewModel) {
        vm.confirmPaidAction()
        HandshakeHarness.awaitIdle(vm)
    }

    private suspend fun cancel(vm: ChatViewModel) {
        vm.dismissPaidAction()
        HandshakeHarness.awaitIdle(vm)
    }

    /** An automatic path built the answer above its price: the request is held, nothing asked yet. */
    private suspend fun heldByAChatOpen(delta: Long = DEAR): Pair<OpenChatTunnelOfferPhone, ChatViewModel> {
        val phone = phone(ConversationMode.TUNNEL, consent = true)
        val key = phone.world.asResponder()
        val vm = phone.screen()
        phone.seam.proposalFeeDelta = delta
        phone.chatOpen(vm)
        assertEquals("fixture: the answer was not built", 1, phone.seam.proposals.get())
        assertEquals("fixture: not held", key, phone.hold[phone.peer])
        return phone to vm
    }

    private suspend fun tunnelOfferAsked(delta: Long = DEAR): Pair<OpenChatTunnelOfferPhone, ChatViewModel> {
        val phone = phone(ConversationMode.OPEN, consent = false)
        phone.heldKex()
        val vm = phone.screen()
        phone.seam.proposalFeeDelta = delta
        phone.tap(vm, "acceptOpenChatTunnelOffer")
        return phone to vm
    }

    private suspend fun bannerAsked(delta: Long = DEAR): Pair<OpenChatTunnelOfferPhone, ChatViewModel> {
        val phone = phone(ConversationMode.TUNNEL, consent = true)
        phone.heldKex(ConversationMode.TUNNEL)
        val vm = phone.screen()
        phone.seam.proposalFeeDelta = delta
        phone.tap(vm, "acceptInboundKexRequest")
        return phone to vm
    }

    // ── The next-open question after an automatic hold ──────────────────────────────────────────────────────────────

    @Test
    fun `the hold asks nothing at once, and the next chat open asks the true price without building again`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            assertNoQuestion(vm)
            assertEquals("the automatic path paid", 0, phone.seam.submits.get())

            phone.chatOpen(vm)

            assertAskedTheTruePrice(vm, DEAR_TOTAL)
            assertEquals("the second open built again", 1, phone.seam.proposals.get())
            assertEquals("the question paid", 0, phone.seam.submits.get())
        }

    @Test
    fun `the held price survives a new screen of the same phone`() =
        runBlocking {
            val (phone, _) = heldByAChatOpen()

            val again = phone.screen()
            phone.chatOpen(again)

            assertAskedTheTruePrice(again, DEAR_TOTAL)
        }

    @Test
    fun `Pay on the next-open question pays exactly the figure once, and no later open pays again`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            phone.chatOpen(vm)

            confirm(vm)
            repeat(3) { phone.chatOpen(vm) }

            assertEquals("what was paid", listOf(DEAR_TOTAL), phone.seam.paidFigures.toList())
            assertNull("the hold outlived the paid answer", phone.hold[phone.peer])
        }

    @Test
    fun `Cancel on the next-open question pays nothing, is not asked again by itself, and the Accept stays`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            val key = phone.hold[phone.peer]
            phone.chatOpen(vm)

            cancel(vm)
            repeat(3) {
                phone.chatOpen(vm)
                assertNoQuestion(vm)
            }

            assertEquals("Cancel paid", 0, phone.seam.submits.get())
            assertEquals("an open built after Cancel", 1, phone.seam.proposals.get())
            assertEquals("Cancel dropped the request", key, phone.hold[phone.peer])

            phone.tap(vm, "acceptInboundKexRequest")
            assertAskedTheTruePrice(vm, DEAR_TOTAL)
            assertEquals("the Accept paid before its question", 0, phone.seam.submits.get())
        }

    @Test
    fun `the question waits while a notice is on screen`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            sendState(vm).value = SendMessageState.Error("synthetic notice")

            phone.chatOpen(vm)
            assertEquals("the notice was replaced", SendMessageState.Error("synthetic notice"), HandshakeHarness.sendMessageState(vm))

            vm.resetSendState()
            phone.chatOpen(vm)
            assertAskedTheTruePrice(vm, DEAR_TOTAL)
        }

    @Test
    fun `the question waits while the chat is Open`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            phone.modes[phone.peer] = ConversationMode.OPEN

            phone.chatOpen(vm)
            assertNoQuestion(vm)

            phone.modes[phone.peer] = ConversationMode.TUNNEL
            phone.chatOpen(vm)
            assertAskedTheTruePrice(vm, DEAR_TOTAL)
        }

    @Test
    fun `a price of a key that was replaced is dropped, not asked`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            val newKey = E2EEncryption.generateKeyPair().publicKey
            phone.world.base.peerKeys[phone.peer] = newKey
            phone.hold[phone.peer] = newKey

            phone.chatOpen(vm)

            assertNoQuestion(vm)
            assertEquals("a replaced key's answer was paid", 0, phone.seam.submits.get())
        }

    @Test
    fun `a request answered meanwhile asks nothing`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            phone.hold.remove(phone.peer)
            phone.seam.proposalFeeDelta = 0L

            phone.call(vm, "retryKexAckIfResponder")
            HandshakeHarness.awaitIdle(vm)

            assertNoQuestion(vm)
        }

    // ── Pay and Cancel on the question of a tap ─────────────────────────────────────────────────────────────────────

    @Test
    fun `Pay on the Tunnel offer pays exactly the figure once and the chat is Tunnel`() =
        runBlocking {
            val (phone, vm) = tunnelOfferAsked()
            assertAskedTheTruePrice(vm, DEAR_TOTAL)

            confirm(vm)

            assertEquals("what was paid", listOf(DEAR_TOTAL), phone.seam.paidFigures.toList())
            assertEquals(ConversationMode.TUNNEL, phone.modes[phone.peer])
            assertNull("the hold outlived the paid answer", phone.hold[phone.peer])
        }

    @Test
    fun `Pay on the Accept banner pays exactly the figure once`() =
        runBlocking {
            val (phone, vm) = bannerAsked()
            assertAskedTheTruePrice(vm, DEAR_TOTAL)

            confirm(vm)

            assertEquals("what was paid", listOf(DEAR_TOTAL), phone.seam.paidFigures.toList())
            assertNull("the hold outlived the paid answer", phone.hold[phone.peer])
        }

    @Test
    fun `a second tap on Pay pays nothing more`() =
        runBlocking {
            val (phone, vm) = tunnelOfferAsked()

            vm.confirmPaidAction()
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals("what was paid", listOf(DEAR_TOTAL), phone.seam.paidFigures.toList())
        }

    @Test
    fun `Cancel on the Tunnel offer pays nothing and the chat stays Open with its offer and hold`() =
        runBlocking {
            val (phone, vm) = tunnelOfferAsked()
            val key = phone.hold[phone.peer]

            cancel(vm)

            assertEquals("Cancel paid", 0, phone.seam.submits.get())
            assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(vm))
            assertEquals(ConversationMode.OPEN, phone.modes[phone.peer])
            assertEquals(key, phone.hold[phone.peer])
            assertEquals("OFFERED", phone.stage(vm))
        }

    @Test
    fun `Cancel on the Accept banner pays nothing, the hold stays and no open builds it`() =
        runBlocking {
            val (phone, vm) = bannerAsked()
            val key = phone.hold[phone.peer]

            cancel(vm)
            repeat(2) { phone.chatOpen(vm) }

            assertEquals("Cancel paid", 0, phone.seam.submits.get())
            assertEquals(key, phone.hold[phone.peer])
            assertEquals("an open built after Cancel", 1, phone.seam.proposals.get())
        }

    // ── undoTunnelOfferAccept ───────────────────────────────────────────────────────────────────────────────────────

    /** A KEX confirmed before this chat became Open: the mark with its reason and account, in the store and in memory. */
    private fun OpenChatTunnelOfferPhone.owes(vm: ChatViewModel) {
        world.prefs.kexOwed += peer
        world.prefs.kexOwedReason[peer] = OWED_REASON
        owedAccount[peer] = OWED_ACCOUNT
        memoryOwed(vm).add(peer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun memoryOwed(vm: ChatViewModel) =
        ChatViewModel::class.java
            .getDeclaredField("kexOwedPeers")
            .apply { isAccessible = true }
            .get(vm) as MutableSet<String>

    private fun OpenChatTunnelOfferPhone.assertOwedBack(vm: ChatViewModel) {
        assertTrue("the owed KEX was not restored", peer in world.prefs.kexOwed)
        assertEquals("the owed KEX's reason", OWED_REASON, world.prefs.kexOwedReason[peer])
        assertEquals("the owed KEX's account", OWED_ACCOUNT, owedAccount[peer])
        assertTrue("the owed KEX is not back in memory", peer in memoryOwed(vm))
    }

    private suspend fun refusedAccept(
        priceRefusal: Boolean,
        pick: Boolean,
        owed: Boolean,
    ): Pair<OpenChatTunnelOfferPhone, ChatViewModel> {
        val phone = phone(ConversationMode.OPEN, consent = false)
        phone.heldKex()
        val vm = phone.screen()
        if (pick) phone.explicit += phone.peer
        if (owed) phone.owes(vm)
        if (priceRefusal) {
            phone.seam.proposalFeeDelta = DEAR
        } else {
            phone.seam.failNextProposalWith = InsufficientFundsException(have = Zatoshi(20_000L), need = Zatoshi(DEAR_TOTAL))
        }
        phone.tap(vm, "acceptOpenChatTunnelOffer")
        assertEquals("fixture: the build was not reached", 1, phone.seam.proposals.get())
        assertEquals("a refused Accept paid", 0, phone.seam.submits.get())
        return phone to vm
    }

    @Test
    fun `a shortfall gives back the owed KEX with its reason and account`() =
        runBlocking {
            val (phone, vm) = refusedAccept(priceRefusal = false, pick = false, owed = true)
            phone.assertOwedBack(vm)
        }

    @Test
    fun `a price above the quote gives back the owed KEX with its reason and account`() =
        runBlocking {
            val (phone, vm) = refusedAccept(priceRefusal = true, pick = false, owed = true)
            phone.assertOwedBack(vm)
        }

    @Test
    fun `no owed KEX before means none after`() =
        runBlocking {
            val (phone, vm) = refusedAccept(priceRefusal = false, pick = false, owed = false)
            assertFalse("a refused Accept invented an owed KEX", phone.peer in phone.world.prefs.kexOwed)
            assertFalse("a refused Accept invented an owed KEX in memory", phone.peer in memoryOwed(vm))
        }

    @Test
    fun `a chat with no explicit pick has none after a refused Accept`() =
        runBlocking {
            for (priceRefusal in listOf(false, true)) {
                val (phone, _) = refusedAccept(priceRefusal, pick = false, owed = false)
                assertFalse("the Accept's pick survived (price refusal: $priceRefusal)", phone.peer in phone.explicit)
                assertEquals(ConversationMode.OPEN, phone.modes[phone.peer])
            }
        }

    @Test
    fun `an explicit pick of Open stays after a refused Accept`() =
        runBlocking {
            for (priceRefusal in listOf(false, true)) {
                val (phone, _) = refusedAccept(priceRefusal, pick = true, owed = false)
                assertTrue("the user's own pick was lost (price refusal: $priceRefusal)", phone.peer in phone.explicit)
                assertEquals(ConversationMode.OPEN, phone.modes[phone.peer])
            }
        }

    @Test
    fun `Pay after the undo pays the one answer and not the restored owed KEX`() =
        runBlocking {
            val (phone, vm) = refusedAccept(priceRefusal = true, pick = false, owed = true)
            assertAskedTheTruePrice(vm, DEAR_TOTAL)

            confirm(vm)
            repeat(2) { phone.chatOpen(vm) }

            assertEquals("what was paid", listOf(DEAR_TOTAL), phone.seam.paidFigures.toList())
            assertFalse("the owed KEX is still owed after the answer that voids it", phone.peer in phone.world.prefs.kexOwed)
        }

    // ── A build whose price changes after Pay ───────────────────────────────────────────────────────────────────────

    private suspend fun assertAsksAgainThenPays(
        phone: OpenChatTunnelOfferPhone,
        vm: ChatViewModel,
        firstFigure: Long,
        newDelta: Long,
        newFigure: Long,
    ) {
        assertAskedTheTruePrice(vm, firstFigure)
        phone.seam.proposalFeeDelta = newDelta

        confirm(vm)

        assertAskedTheTruePrice(vm, newFigure)
        assertEquals("a build not at the confirmed figure was paid", 0, phone.seam.submits.get())

        confirm(vm)

        assertEquals("what was paid", listOf(newFigure), phone.seam.paidFigures.toList())
    }

    @Test
    fun `the Tunnel offer's price rising after Pay asks again and pays only the new figure`() =
        runBlocking {
            val (phone, vm) = tunnelOfferAsked()
            assertAsksAgainThenPays(phone, vm, DEAR_TOTAL, DEARER, DEARER_TOTAL)
        }

    @Test
    fun `the banner's price rising after Pay asks again and pays only the new figure`() =
        runBlocking {
            val (phone, vm) = bannerAsked()
            assertAsksAgainThenPays(phone, vm, DEAR_TOTAL, DEARER, DEARER_TOTAL)
        }

    @Test
    fun `the next-open question's price rising after Pay asks again and pays only the new figure`() =
        runBlocking {
            val (phone, vm) = heldByAChatOpen()
            phone.chatOpen(vm)
            assertAsksAgainThenPays(phone, vm, DEAR_TOTAL, DEARER, DEARER_TOTAL)
        }

    @Test
    fun `a price falling after Pay is not paid under a question that said exactly`() =
        runBlocking {
            val (phone, vm) = tunnelOfferAsked()
            assertAsksAgainThenPays(phone, vm, DEAR_TOTAL, 0L, HANDSHAKE_TOTAL)
        }

    @Suppress("UNCHECKED_CAST")
    private fun sendState(vm: ChatViewModel) =
        ChatViewModel::class.java
            .getDeclaredField("_sendMessageState")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<SendMessageState>

    private companion object {
        const val OUTPUT = 2_000L
        const val DEAR = 5_000L
        const val DEARER = 10_000L
        const val HANDSHAKE_TOTAL = 17_000L
        const val DEAR_TOTAL = 22_000L
        const val DEARER_TOTAL = 27_000L
        const val OWED_REASON = "synthetic-reason"
        const val OWED_ACCOUNT = "synthetic-account"
    }
}
