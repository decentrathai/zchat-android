package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1230, ZC-1233 round 2 (review findings 1, 2, 3, 5).
 *
 * 1. An Accept whose build still waits for confirmations when the block waits end is a refusal: kexAnswerRefusalOf keeps
 *    that shortfall a "wait", so nothing undoes the Accept and the screen says nothing.
 * 2. A refusal undoes the Accept only while its quote stands: a mode the user picked, or a new key that arrived, during the
 *    build is the later fact and undoTunnelOfferAccept must not write over it.
 * 3. The notice is shared with the notice's Retry, where "tap Accept" names a button that is not there (EN and ES).
 * 5. Pay on a true-price question pays the request it asked about: the Pay of an older key must not pay a new key's answer.
 *
 * The refusals enter at ProposalDataSource; the send path above it is the real one. No wire or stored format is involved.
 * Every address, key and id is synthetic.
 */
class AcceptRefusalsStayBoundToTheirRequestTest {
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

    private fun shortfall() = InsufficientFundsException(have = Zatoshi(20_000L), need = Zatoshi(22_000L))

    /** The SDK's refusal as a wallet that holds the need only with its change still confirming reads it: a wait for blocks. */
    private fun confirmingWallet() =
        Zc101FakeAccountDataSource(
            runBlocking {
                syntheticZashiAccount().let {
                    // ZC-1417 (QA2 k1): 22,000 spendable passes the pre-question funds gate (cost + marginal fee), so the SDK's
                    // refusal is what this phone meets, as before; 20,000 is now refused before any build.
                    it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(22_000L), Zatoshi(100_000L), Zatoshi(0))))
                }
            },
        )

    private fun confirmingPhone(mode: ConversationMode) =
        OpenChatTunnelOfferPhone(LedgerWorld(mode = mode, consent = mode != ConversationMode.OPEN, accountSource = confirmingWallet()))

    private suspend fun OpenChatTunnelOfferPhone.tap(
        vm: ChatViewModel,
        entry: String,
    ) {
        call(vm, entry)
        HandshakeHarness.awaitIdle(vm)
    }

    /** The tap, with a block landing at each wait until the send gives up: the first build and its four retries. */
    private suspend fun OpenChatTunnelOfferPhone.tapThroughEveryBlockWait(
        vm: ChatViewModel,
        entry: String,
    ) {
        val heights = HeightFeed()
        AutoSpendVm.setField(vm, "synchronizerProvider", heights.provider)
        call(vm, entry)
        withTimeout(WAITS_TIMEOUT_MS) {
            while (seam.proposals.get() < BUILDS_UNTIL_GIVING_UP || heights.isWaiting()) {
                if (heights.isWaiting()) heights.advance()
                delay(POLL_MS)
            }
        }
        HandshakeHarness.awaitIdle(vm)
        assertEquals("fixture: the send did not wait for blocks", BUILDS_UNTIL_GIVING_UP, seam.proposals.get())
    }

    /** The contact's next key exchange, as the store holds it for Accept. */
    private fun OpenChatTunnelOfferPhone.newKeyArrives(): String {
        val newKey = E2EEncryption.generateKeyPair().publicKey
        world.base.peerKeys[peer] = newKey
        world.prefs.receivedKex[peer] = newKey
        hold[peer] = newKey
        return newKey
    }

    private fun assertToldSomething(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("the Accept ran out of block waits and the screen says nothing: $state", state is SendMessageState.Error)
    }

    // ── 1. Out of block waits ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Tunnel offer's Accept out of block waits undoes and tells`() =
        runBlocking {
            val phone = confirmingPhone(ConversationMode.OPEN)
            val key = phone.heldKex()
            val vm = phone.screen()
            phone.seam.failEveryProposalWith = shortfall()

            phone.tapThroughEveryBlockWait(vm, "acceptOpenChatTunnelOffer")

            assertEquals("a transaction was submitted", 0, phone.seam.submits.get())
            assertEquals("nothing was paid and the chat left Open", ConversationMode.OPEN, phone.modes[phone.peer])
            assertEquals("nothing was paid and the hold is gone", key, phone.hold[phone.peer])
            assertEquals("the offer is not shown again", "OFFERED", phone.stage(vm))
            assertToldSomething(vm)
        }

    @Test
    fun `the Accept banner out of block waits tells and keeps the hold`() =
        runBlocking {
            val phone = confirmingPhone(ConversationMode.TUNNEL)
            val key = phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()
            phone.seam.failEveryProposalWith = shortfall()

            phone.tapThroughEveryBlockWait(vm, "acceptInboundKexRequest")

            assertEquals("a transaction was submitted", 0, phone.seam.submits.get())
            assertEquals("nothing was paid and the hold is gone", key, phone.hold[phone.peer])
            assertToldSomething(vm)
        }

    // ── 2. Undo only while the quote stands ─────────────────────────────────────────────────────────────────────────

    /** Accept on the offer, [meanwhile] while its build is in the SDK, then the build is refused. */
    private suspend fun refusedAfter(
        priceRefusal: Boolean,
        meanwhile: (OpenChatTunnelOfferPhone) -> Unit,
    ): Pair<OpenChatTunnelOfferPhone, ChatViewModel> {
        val phone = phone(ConversationMode.OPEN, consent = false)
        phone.heldKex()
        val vm = phone.screen()
        val hold = CompletableDeferred<Unit>()
        phone.seam.holdProposals = hold
        if (priceRefusal) phone.seam.proposalFeeDelta = 5_000L else phone.seam.failNextProposalWith = shortfall()

        phone.call(vm, "acceptOpenChatTunnelOffer")
        phone.seam.proposalEntered.await()
        meanwhile(phone)
        hold.complete(Unit)
        HandshakeHarness.awaitIdle(vm)

        assertEquals("a refused build paid", 0, phone.seam.submits.get())
        return phone to vm
    }

    private fun aModePickedDuringTheBuild(priceRefusal: Boolean) =
        runBlocking {
            val (phone, _) = refusedAfter(priceRefusal) { it.modes[it.peer] = ConversationMode.VAULT }

            assertEquals("the undo wrote over the mode picked during the build", ConversationMode.VAULT, phone.modes[phone.peer])
            assertEquals("the undo put the hold back over the mode picked during the build", null, phone.hold[phone.peer])
        }

    private fun aNewKeyDuringTheBuild(priceRefusal: Boolean) =
        runBlocking {
            var newKey = ""
            val (phone, _) = refusedAfter(priceRefusal) { newKey = it.newKeyArrives() }

            assertEquals("the undo wrote the old key over the new key's hold", newKey, phone.hold[phone.peer])
            assertEquals("the undo changed the mode though a new key had arrived", ConversationMode.TUNNEL, phone.modes[phone.peer])
        }

    @Test
    fun `a shortfall does not write over a mode picked during the build`() = aModePickedDuringTheBuild(priceRefusal = false)

    @Test
    fun `a price refusal does not write over a mode picked during the build`() = aModePickedDuringTheBuild(priceRefusal = true)

    @Test
    fun `a shortfall does not write over a new key that arrived during the build`() = aNewKeyDuringTheBuild(priceRefusal = false)

    @Test
    fun `a price refusal does not write over a new key that arrived during the build`() = aNewKeyDuringTheBuild(priceRefusal = true)

    // ── 3. No "tap Accept" on Retry ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the notice's Retry names the need and no Accept button`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            val vm = phone.screen()
            val marker = phone.world.kexAckProvenNotSent(vm)

            vm.retryHandshake(phone.peer, marker)
            HandshakeHarness.awaitIdle(vm)
            phone.seam.failNextProposalWith = shortfall()
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue("fixture: Retry's refusal shows no notice: $state", state is SendMessageState.Error)
            val message = (state as SendMessageState.Error).message
            assertTrue("the notice does not name the need: $message", message.contains("0.00022"))
            assertFalse("Retry's notice names an Accept button: $message", message.contains("Accept", ignoreCase = true))
        }

    @Test
    fun `the shortfall line names no Accept button, in English`() {
        val line = ChatResourceText.strings("values").getValue("chat_send_kex_answer_shortfall")
        assertFalse("the shared line says: $line", line.contains("Accept", ignoreCase = true))
    }

    @Test
    fun `the shortfall line names no Accept button, in Spanish`() {
        val line = ChatResourceText.strings("values-es").getValue("chat_send_kex_answer_shortfall")
        assertFalse("the shared line says: $line", line.contains("Aceptar", ignoreCase = true))
    }

    // ── 5. Pay is bound to the key it asked about ───────────────────────────────────────────────────────────────────

    private fun assertOldPayPaysNothing(phone: OpenChatTunnelOfferPhone) {
        assertEquals("Pay on the old key's question paid the new key's answer", 0, phone.seam.submits.get())
    }

    @Test
    fun `Pay on the next-open question does not pay a new key's answer`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            phone.world.asResponder()
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = 5_000L
            phone.tap(vm, "retryKexAckIfResponder")
            phone.tap(vm, "retryKexAckIfResponder")
            assertTrue("fixture: not asked", HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation)

            phone.newKeyArrives()
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertOldPayPaysNothing(phone)
        }

    @Test
    fun `Pay on the banner's question does not pay a new key's answer`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = 5_000L
            phone.tap(vm, "acceptInboundKexRequest")
            assertTrue("fixture: not asked", HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation)

            phone.newKeyArrives()
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertOldPayPaysNothing(phone)
        }

    @Test
    fun `Pay on the Tunnel offer's question does not pay a new key's answer`() =
        runBlocking {
            val phone = phone(ConversationMode.OPEN, consent = false)
            phone.heldKex()
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = 5_000L
            phone.tap(vm, "acceptOpenChatTunnelOffer")
            assertTrue("fixture: not asked", HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation)

            val newKey = phone.newKeyArrives()
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertOldPayPaysNothing(phone)
            assertEquals("the new key's request is gone though nothing was paid", newKey, phone.hold[phone.peer])
        }

    private companion object {
        const val BUILDS_UNTIL_GIVING_UP = 5
        const val WAITS_TIMEOUT_MS = 8_000L
        const val POLL_MS = 5L
    }
}
