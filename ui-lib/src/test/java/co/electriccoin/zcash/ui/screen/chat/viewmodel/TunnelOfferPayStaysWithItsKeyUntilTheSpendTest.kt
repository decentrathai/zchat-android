package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1233 round 3 follow-up — Pay on the Tunnel offer's true-price question, while its proposal is being built (the gate has
 * passed, nothing is submitted), and the contact's next key exchange arrives. Nothing is paid, and K2's request stays. A build
 * that nothing disturbs still pays (control). No wire or stored format is involved. Every address, key and id is synthetic.
 */
class TunnelOfferPayStaysWithItsKeyUntilTheSpendTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private val OpenChatTunnelOfferPhone.seam get() = world.seam

    private fun payDuringTheBuild(meanwhile: (OpenChatTunnelOfferPhone) -> Unit) =
        runBlocking {
            val phone =
                OpenChatTunnelOfferPhone(
                    LedgerWorld(mode = ConversationMode.OPEN, consent = false, accountSource = OpenChatTunnelOfferPhone.funded()),
                )
            phone.heldKex()
            val vm = phone.screen()
            val seam = phone.world.seam
            seam.proposalFeeDelta = ABOVE
            phone.call(vm, "acceptOpenChatTunnelOffer")
            HandshakeHarness.awaitIdle(vm)
            assertTrue("fixture: not asked", HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation)

            val built = seam.proposals.get()
            val held = CompletableDeferred<Unit>()
            seam.holdProposals = held
            vm.confirmPaidAction()
            withTimeout(WAIT_MS) {
                while (seam.proposals.get() <= built) delay(POLL_MS)
            }
            meanwhile(phone)
            seam.holdProposals = null
            held.complete(Unit)
            HandshakeHarness.awaitIdle(vm)
            phone
        }

    @Test
    fun `the flag rising during the build pays nothing`() {
        val phone = payDuringTheBuild { it.keyChanged += it.world.peer }
        assertEquals("Pay was spent while the contact's new key waits for the user", 0, phone.seam.submits.get())
        assertTrue("the new key's flag is gone", phone.world.peer in phone.keyChanged)
    }

    @Test
    fun `both moving to K2 during the build pays nothing and K2's request stays`() {
        var newKey = ""
        val phone =
            payDuringTheBuild {
                newKey = E2EEncryption.generateKeyPair().publicKey
                it.world.base.peerKeys[it.world.peer] = newKey
                it.world.prefs.receivedKex[it.world.peer] = newKey
                it.hold[it.world.peer] = newKey
            }
        assertEquals("Pay on K's question paid after K2 arrived", 0, phone.seam.submits.get())
        assertTrue("K2's request is not on hold any more", phone.hold[phone.world.peer] == newKey)
    }

    @Test
    fun `control - a build nothing disturbs pays the figure once`() {
        val phone = payDuringTheBuild { }
        assertEquals("what was paid", listOf(TRUE_FIGURE), phone.seam.paidFigures.toList())
    }

    private companion object {
        const val ABOVE = 5_000L
        const val TRUE_FIGURE = 22_000L
        const val WAIT_MS = 8_000L
        const val POLL_MS = 5L
    }
}
