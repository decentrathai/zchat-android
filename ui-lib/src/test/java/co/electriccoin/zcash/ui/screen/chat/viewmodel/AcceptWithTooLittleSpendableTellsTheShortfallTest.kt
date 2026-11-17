package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1230 (with the header half of ZC-1231) — on an incoming key exchange, "Accept and pay" with too little spendable.
 *
 * On the phone (Fold debug, 30 Oct, phone-fold-full-20261030/logs/M3-accept-log.txt): 20,000 zat in several notes passes
 * the 17,000 value gate ([ChatViewModel] handshakeWalletCanPayOne, the known limit R-M3-1), and the SDK refuses the build:
 * "Insufficient balance (have 20000, need 22000 including fee)". The refusal only reaches the log ("Memo send failed
 * permanently"): the screen shows nothing, the button reads as dead, every tap builds again, and the Open chat's header
 * now reads Tunnel though nothing was paid (dump m3-banner.txt reads "open", m3-after-3rd.txt reads "Tunnel"; the Accept's
 * own KDoc and ZC-1017 say a wallet that cannot pay leaves the chat Open).
 *
 * Wanted: the user is told the exact figure the SDK refused on (22,000 zat = 0.00022 ZEC), not the 0.00017 the wallet
 * appears to cover; nothing is stored or paid; the Open chat stays Open with its offer and hold, so no chat open pays or
 * rebuilds the answer by itself; the next tap tells it again and builds at most once.
 *
 * The refusal enters where the real one does: [co.electriccoin.zcash.ui.common.datasource.ProposalDataSource], with the
 * typed figures ProposalDataSource's toShortfallOrNull reads from the SDK's text; everything above it is the real send path.
 * No wire or stored format is involved. Every address, key and transaction id is synthetic.
 *
 * QA2 k1b (ZC-1817, [test-change-ok: ZC-1817]): since ZC-1417 a 20,000 wallet is refused by the funds gate before any build,
 * so the refused-build branch (ZC-1230, ZC-1234) is exercised again with a wallet above the gate: 22,000 spendable that the
 * SDK refuses (have 22,000, need 27,000, e.g. a fee for more notes). The 17,001..21,999 dead zone has its own test.
 */
class AcceptWithTooLittleSpendableTellsTheShortfallTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun shortWallet() =
        Zc101FakeAccountDataSource(
            runBlocking {
                syntheticZashiAccount().let {
                    it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(have), Zatoshi(0), Zatoshi(0))))
                }
            },
        )

    private fun phone(
        mode: ConversationMode,
        consent: Boolean,
    ) = OpenChatTunnelOfferPhone(LedgerWorld(mode = mode, consent = consent, accountSource = shortWallet()))

    private val OpenChatTunnelOfferPhone.seam get() = world.seam

    /** ZC-1817: what the wallet holds, what the SDK's refusal asks for, and the figure the notice must name. */
    private var have = HAVE
    private var need = NEED
    private var needZec = NEED_ZEC

    /** ZC-1817: a wallet above the funds gate whose build the SDK still refuses (the refused-build branch). */
    private fun aboveTheGate() {
        have = ABOVE_HAVE
        need = ABOVE_NEED
        needZec = ABOVE_NEED_ZEC
    }

    private fun OpenChatTunnelOfferPhone.refuseNextBuild() {
        seam.failNextProposalWith = InsufficientFundsException(have = Zatoshi(have), need = Zatoshi(need))
    }

    private suspend fun OpenChatTunnelOfferPhone.tap(
        vm: ChatViewModel,
        entry: String,
    ) {
        refuseNextBuild()
        call(vm, entry)
        HandshakeHarness.awaitIdle(vm)
        seam.failNextProposalWith = null
    }

    private fun assertToldTheShortfall(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("Accept was refused for funds and the screen says nothing: $state", state is SendMessageState.Error)
        val message = (state as SendMessageState.Error).message
        assertTrue("the notice does not name the $need zatoshi the build needs: $message", needZec.containsMatchIn(message))
        assertTrue("the notice names 0.00017, which this wallet appears to cover: $message", !QUOTE_ZEC.containsMatchIn(message))
    }

    private fun assertNothingPaid(phone: OpenChatTunnelOfferPhone) {
        assertEquals("a transaction was stored", emptyList<String>(), phone.seam.stored.toList())
        assertEquals("a transaction was submitted", 0, phone.seam.submits.get())
    }

    @Test
    fun `the Tunnel offer's Accept tells the exact shortfall and pays nothing`() =
        runBlocking {
            aboveTheGate()
            val phone = phone(ConversationMode.OPEN, consent = false)
            phone.heldKex()
            val vm = phone.screen()
            assertEquals("fixture: the offer is not shown", "OFFERED", phone.stage(vm))

            phone.tap(vm, "acceptOpenChatTunnelOffer")

            assertEquals("fixture: the build was not reached", 1, phone.seam.proposals.get())
            assertNothingPaid(phone)
            assertToldTheShortfall(vm)
        }

    @Test
    fun `ZC-1817 - a dead-zone wallet is refused before any build and told the price plus the marginal fee`() =
        runBlocking {
            val phone = phone(ConversationMode.OPEN, consent = false)
            phone.heldKex()
            val vm = phone.screen()
            assertEquals("fixture: the offer is not shown", "OFFERED", phone.stage(vm))

            phone.tap(vm, "acceptOpenChatTunnelOffer")

            // ZC-1417 (QA2 k1): 20,000 is in the 17,001..21,999 dead zone, so the offer's funds gate refuses before any
            // build and names the 0.00022 the SDK would have asked for.
            assertEquals("the dead-zone wallet reached the build", 0, phone.seam.proposals.get())
            assertNothingPaid(phone)
            assertToldTheShortfall(vm)
        }

    @Test
    fun `a refused Accept leaves the Open chat Open, with its offer and its hold`() =
        runBlocking {
            aboveTheGate()
            val phone = phone(ConversationMode.OPEN, consent = false)
            val contactKey = phone.heldKex()
            val vm = phone.screen()

            phone.tap(vm, "acceptOpenChatTunnelOffer")

            assertEquals("fixture: the build was not reached", 1, phone.seam.proposals.get())
            assertNothingPaid(phone)
            assertEquals("nothing was paid and the chat left Open", ConversationMode.OPEN, phone.modes[phone.world.peer])
            assertEquals("nothing was paid and the hold is gone", contactKey, phone.hold[phone.world.peer])
            assertEquals("the offer is not shown again", "OFFERED", phone.stage(vm))
        }

    @Test
    fun `after a refused Accept chat opens build nothing, under the standing consent too`() =
        runBlocking {
            aboveTheGate()
            val phone = phone(ConversationMode.OPEN, consent = true)
            phone.heldKex()
            val vm = phone.screen()
            phone.tap(vm, "acceptOpenChatTunnelOffer")
            assertEquals("fixture: the build was not reached", 1, phone.seam.proposals.get())

            repeat(3) { phone.tap(vm, "retryKexAckIfResponder") }

            assertNothingPaid(phone)
            assertEquals("chat opens rebuilt the refused answer unasked", 1, phone.seam.proposals.get())
        }

    @Test
    fun `each further tap tells it again and builds at most once`() =
        runBlocking {
            aboveTheGate()
            val phone = phone(ConversationMode.OPEN, consent = false)
            phone.heldKex()
            val vm = phone.screen()

            repeat(3) { tap ->
                vm.resetSendState()
                phone.tap(vm, "acceptOpenChatTunnelOffer")
                assertTrue("tap ${tap + 1} built more than once", phone.seam.proposals.get() <= tap + 1)
                assertToldTheShortfall(vm)
            }
            assertNothingPaid(phone)
        }

    @Test
    fun `the Accept banner of a chat that is not Open tells the exact shortfall too`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, consent = true)
            val contactKey = phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()

            phone.tap(vm, "acceptInboundKexRequest")

            assertEquals("fixture: the build was not reached", 1, phone.seam.proposals.get())
            assertNothingPaid(phone)
            assertToldTheShortfall(vm)
            assertEquals("the banner's request is gone though nothing was paid", contactKey, phone.hold[phone.world.peer])
        }

    private companion object {
        const val HAVE = 20_000L
        const val NEED = 22_000L
        val NEED_ZEC = Regex("""(?<![\d.,])0[.,]00022(?!\d)""")
        const val ABOVE_HAVE = 22_000L
        const val ABOVE_NEED = 27_000L
        val ABOVE_NEED_ZEC = Regex("""(?<![\d.,])0[.,]00027(?!\d)""")
        val QUOTE_ZEC = Regex("""(?<![\d.,])0[.,]00017(?!\d)""")
    }
}
