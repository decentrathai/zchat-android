package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.openResponderChat
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.responderPrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.responderScreen
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.state
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M3 (ZC-110, KI-96) — once the wallet cannot be asked while it holds less than one handshake, something must ask when
 * funds become spendable: a queued KEXACK is retried only on a chat open or on Allow, and no block tick re-asks for it.
 *
 * Must hold: a KEXACK queued silently on an unfunded wallet raises "Allow handshake transactions?" at the first block
 * after the Ironwood funds are spendable, on any screen, with the same count and quote as the question asked directly
 * on a funded wallet; the tick pays nothing and never writes the consent; Allow then pays exactly that one KEXACK and a
 * later block raises nothing. "Not now" is remembered: a later block does not ask again for the same queue.
 *
 * All keys are generated per run and every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeQuestionAppearsWhenFundsArriveTest {
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

    /** The question a funded wallet is asked at once for [peer]'s KEXACK (the figure the block's question must match). */
    private suspend fun directQuestion(peer: String): SendMessageState.NeedsHandshakeSpendConsent {
        val prefs = responderPrefs(peer)
        val seam = HandshakeSeam(peer)
        val vm = openResponderChat(prefs, seam, SelectedAccountFeed.create(), peer)
        val asked = state(vm)
        assertTrue("fixture sanity: a funded wallet is asked at once: $asked", asked is SendMessageState.NeedsHandshakeSpendConsent)
        AutoSpendVm.resetProcessState()
        return asked as SendMessageState.NeedsHandshakeSpendConsent
    }

    /** [peer]'s KEXACK, queued on a wallet that holds only a confirming deposit; the chat screen asked nothing. */
    private suspend fun queuedWhileUnfunded(
        prefs: HandshakePrefs,
        seam: HandshakeSeam,
        accounts: SelectedAccountFeed,
        peer: String,
    ) {
        accounts.onlyConfirmingIronwood()
        val chat = openResponderChat(prefs, seam, accounts, peer)
        assertFalse(
            "a wallet that cannot pay one handshake was asked to allow them: ${state(chat)}",
            state(chat) is SendMessageState.NeedsHandshakeSpendConsent,
        )
        assertEquals("nothing may be paid without consent", 0, seam.proposalRequests.get())
    }

    @Test
    fun `the question comes at the first block after funds are spendable and Allow pays only the KEXACK`() =
        runBlocking {
            val peer = "usyntheticm3fundsarrive01"
            val expected = directQuestion(peer)
            val prefs = responderPrefs(peer)
            val seam = HandshakeSeam(peer)
            val accounts = SelectedAccountFeed.create()
            queuedWhileUnfunded(prefs, seam, accounts, peer)

            // The chat list: a block while the deposit still confirms asks nothing.
            val list = responderScreen(prefs, seam, accounts)
            chainTip(list)
            assertEquals("a block asked while the wallet still cannot pay", SendMessageState.Idle, state(list))

            accounts.ironwoodSpendable()
            chainTip(list)
            assertEquals(
                "the first block after the funds are spendable must ask, with the quote of the question asked directly",
                expected,
                state(list),
            )
            assertEquals("the block paid something before Allow", 0, seam.proposalRequests.get())
            assertEquals("the block wrote the consent", false, prefs.consent)

            list.grantHandshakeSpendConsent()
            awaitIdle(list)
            assertEquals("Allow must pay exactly the queued KEXACK", 1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(prefs.e2ePeerKey[peer], prefs.sentKexAck[peer])

            chainTip(list)
            assertFalse("a block after Allow asked again", state(list) is SendMessageState.NeedsHandshakeSpendConsent)
            assertEquals("a block after Allow paid again", 1, seam.proposalRequests.get())
        }

    @Test
    fun `Not now to the block's question is remembered and nothing is paid`() =
        runBlocking {
            val peer = "usyntheticm3fundsarrive02"
            val prefs = responderPrefs(peer)
            val seam = HandshakeSeam(peer)
            val accounts = SelectedAccountFeed.create()
            queuedWhileUnfunded(prefs, seam, accounts, peer)

            val list = responderScreen(prefs, seam, accounts)
            accounts.ironwoodSpendable()
            chainTip(list)
            assertTrue(
                "the first block after the funds are spendable must ask: ${state(list)}",
                state(list) is SendMessageState.NeedsHandshakeSpendConsent,
            )

            list.dismissOrchardShieldingWarning()
            chainTip(list)
            chainTip(list)
            assertEquals("a block asked again after Not now", SendMessageState.Idle, state(list))
            assertEquals("Not now paid something", 0, seam.proposalRequests.get())
            assertEquals("Not now wrote the consent", false, prefs.consent)
        }
}
