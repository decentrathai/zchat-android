package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1233 (high, money-path rule 1) — every key-exchange answer (KEXACK) goes out with no price check.
 *
 * "Accept and pay 0.00017 ZEC" quotes 17,000 zat (2,000 in outputs + a 15,000 network fee), but sendKEXAckMessage passes no
 * sendQuote, so the send-time guard of CreateChunkedMessageProposalUseCase does not apply. On the Fold (30 Oct, ZC-1230) the
 * SDK priced such an answer at 22,000 and only a too-small wallet stopped it; a wallet that holds the 22,000 pays them.
 *
 * Wanted: a build above the figure the user was shown is not paid: it is refused, or asked about, with the true figure
 * (0.00022 ZEC). The same for every other path that sends an answer: a chat open and an inbound key exchange under the
 * standing consent, and the notice's Retry. A build at the quote still pays (control), so refusing every answer cannot pass.
 *
 * The proposals are real ones carrying the fee, at [co.electriccoin.zcash.ui.common.datasource.ProposalDataSource]; the
 * send path above it is the real one. No wire or stored format is involved. Every address, key and id is synthetic.
 */
class AcceptNeverPaysAboveItsQuoteTest {
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

    private fun assertNothingPaid(
        phone: OpenChatTunnelOfferPhone,
        submitsBefore: Int = 0,
    ) {
        assertEquals("an answer above its quote was paid", submitsBefore, phone.seam.submits.get())
    }

    private fun assertToldTheTrueFigure(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("nothing on screen names the 22,000 zat this answer costs: $state", TRUE_FIGURE.containsMatchIn(state.toString()))
    }

    @Test
    fun `the Tunnel offer's Accept does not pay a build above its quote`() =
        runBlocking {
            val phone = phone(ConversationMode.OPEN, consent = false)
            phone.heldKex()
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = ABOVE_THE_QUOTE

            phone.call(vm, "acceptOpenChatTunnelOffer")
            HandshakeHarness.awaitIdle(vm)

            assertNothingPaid(phone)
            assertToldTheTrueFigure(vm)
        }

    @Test
    fun `the Accept banner does not pay a build above its quote`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = ABOVE_THE_QUOTE

            phone.call(vm, "acceptInboundKexRequest")
            HandshakeHarness.awaitIdle(vm)

            assertNothingPaid(phone)
            assertToldTheTrueFigure(vm)
        }

    @Test
    fun `a chat open under the standing consent does not pay a build above the handshake price`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            phone.world.asResponder()
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = ABOVE_THE_QUOTE

            phone.call(vm, "retryKexAckIfResponder")
            HandshakeHarness.awaitIdle(vm)

            assertNothingPaid(phone)
        }

    @Test
    fun `an inbound key exchange under the standing consent does not pay a build above the handshake price`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            val keys = E2EEncryption.generateKeyPair()
            phone.world.base.peerKeys[phone.world.peer] = keys.publicKey
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = ABOVE_THE_QUOTE

            val peer = phone.world.peer
            val memo =
                ZMSGProtocol.createV4KEXMessage(
                    phone.world.base.convIdFor(peer),
                    peer,
                    E2EEncryption.createKEXPayload(peer, keys.publicKey, keys.privateKey),
                )
            HandshakeHarness.handleKexMemo(vm, memo, txId = "synthetic-kex")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("fixture: the answer was not built", 1, phone.seam.proposals.get())
            assertNothingPaid(phone)
        }

    @Test
    fun `the notice's Retry does not pay a build above the price it asked`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            val vm = phone.screen()
            val marker = phone.world.kexAckProvenNotSent(vm)
            val submits = phone.seam.submits.get()
            phone.seam.proposalFeeDelta = ABOVE_THE_QUOTE

            vm.retryHandshake(phone.world.peer, marker)
            HandshakeHarness.awaitIdle(vm)
            // A question that already names the true figure is the wanted answer: its Confirm may pay that figure.
            if (!TRUE_FIGURE.containsMatchIn(HandshakeHarness.sendMessageState(vm).toString())) {
                assertTrue("fixture: Retry asked nothing", HandshakeHarness.sendMessageState(vm) !is SendMessageState.Idle)
                vm.confirmPaidAction()
                HandshakeHarness.awaitIdle(vm)
                assertNothingPaid(phone, submits)
                assertToldTheTrueFigure(vm)
            }
        }

    @Test
    fun `control - a build at the quote still pays one answer on Accept`() =
        runBlocking {
            val phone = phone(ConversationMode.OPEN, consent = false)
            phone.heldKex()
            val vm = phone.screen()
            phone.seam.proposalFeeDelta = 0L

            phone.call(vm, "acceptOpenChatTunnelOffer")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("Accept did not pay a build at its quote", 1, phone.seam.submits.get())
        }

    private companion object {
        /** On top of the 15,000 the one-note build costs: a 20,000 fee, 22,000 with the outputs, as the Fold's SDK priced it. */
        const val ABOVE_THE_QUOTE = 5_000L
        val TRUE_FIGURE = Regex("""(?<![\d.,])(0[.,]00022|22000)(?!\d)""")
    }
}
