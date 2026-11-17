package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1047-R1 (P2, money; T7aF review of 6efce1643, finding 1) — the Accept that ZC-1047 holds for Allow is remembered
 * for the whole process, not for the wallet it was tapped in.
 *
 * Wallet A: the user taps Accept on contact P's held key exchange; the handshake question is raised and the request is
 * held for Allow. The phone then shows wallet B (another store), where P's key exchange also waits for Accept, never
 * tapped there. Allow in B answered the Accept remembered from A and paid B's KEXACK without an Accept in B.
 *
 * Wanted: Allow answers only the Accepts tapped in its own wallet; B pays nothing and P's request stays on B's hold.
 * Every address, key and transaction id is synthetic.
 */
class HeldAcceptAnsweredOnlyInItsWalletTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun wallet(): OpenChatTunnelOfferPhone =
        OpenChatTunnelOfferPhone(
            LedgerWorld(mode = ConversationMode.TUNNEL, consent = false, accountSource = OpenChatTunnelOfferPhone.funded()),
        )

    @Test
    fun `Allow in another wallet does not answer an Accept tapped in the first one`() =
        runBlocking {
            val a = wallet()
            a.heldKex(ConversationMode.TUNNEL)
            val vmA = a.screen()
            vmA.acceptInboundKexRequest(a.world.peer)
            HandshakeHarness.awaitIdle(vmA)
            assertTrue(
                "fixture: Accept in wallet A did not ask the handshake question",
                HandshakeHarness.sendMessageState(vmA) is SendMessageState.NeedsHandshakeSpendConsent,
            )
            val paidInA = a.world.seam.proposals
            assertEquals("fixture: wallet A paid before its question", 0, paidInA.get())

            // The same contact's key exchange waits in wallet B, where Accept was never tapped.
            val b = wallet()
            val keyInB = b.heldKex(ConversationMode.TUNNEL)
            val vmB = b.screen()
            vmB.grantHandshakeSpendConsent()
            HandshakeHarness.awaitIdle(vmB)

            val paidInB = b.world.seam.proposals
            assertEquals("Allow in wallet B paid an answer the user never accepted in B", 0, paidInB.get())
            assertEquals("wallet B's request left its hold without an Accept in B", keyInB, b.hold[b.world.peer])
        }
}
