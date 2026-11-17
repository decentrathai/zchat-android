package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.QueuedAutomaticSetup
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.autoSpendScreen
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.automaticKex
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.openResponderChat
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.responderPrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.setup
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.tunnelZboot
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
 * M3 (ZC-110, KI-96) — "Allow handshake transactions?" is asked on a wallet that cannot pay one handshake.
 *
 * On the Fold (28 Sep, release 2.13.0, wallet 0 ZEC with two dust receipts) a contact's KEX raised "Allow handshake
 * transactions? Up to 2 … at least 0.00034 ZEC". Nothing could be paid, but the answer is a standing consent ("ZChat
 * remembers this answer and pays later chat handshakes without asking again"): agreed while nothing could be paid, the
 * queued setups are paid the moment ZEC arrives, with no second question.
 *
 * Must hold: while the selected account's Ironwood spendable is below one handshake (17,000), an automatic setup (our
 * KEXACK to a known contact, our bootstrap KEX, our Tunnel ZBOOT) raises no question at any point, pays nothing, and
 * stays in the process queue of the question, NOT in the set the user declined (so the question can come when funds are
 * spendable, and Allow later still releases it). Checked for three unfunded wallets: only a confirming Ironwood
 * deposit, every value in the sealed Orchard pool, and Ironwood dust.
 *
 * All keys are generated per run and every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeQuestionWaitsForSpendableFundsTest {
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

    /** The three wallets that cannot pay one handshake, by what the user would see. */
    private val unfunded: List<Pair<String, (SelectedAccountFeed) -> Unit>> =
        listOf(
            "only a confirming Ironwood deposit" to { it.onlyConfirmingIronwood() },
            "every value in the sealed Orchard pool" to { it.sealedOrchard() },
            "Ironwood dust of 2,111 zatoshi" to { it.ironwoodDust() },
        )

    private fun assertWaitsSilently(
        wallet: String,
        recorder: FundsGateDriver.StateRecorder,
        seam: HandshakeSeam,
        expected: QueuedAutomaticSetup,
    ) {
        recorder.stop()
        assertFalse(
            "$wallet: the question was raised on a wallet that cannot pay one handshake: ${recorder.states.toList()}",
            recorder.asked(),
        )
        assertEquals("$wallet: nothing may be built", 0, seam.proposalRequests.get())
        assertEquals("$wallet: nothing may be submitted", 0, seam.submits.get())
        assertTrue(
            "$wallet: the setup must stay queued for the question that comes with funds",
            expected in FundsGateDriver.queued(),
        )
        assertFalse(
            "$wallet: the setup was put in the declined set, so the question would never come for it",
            expected in FundsGateDriver.dismissed(),
        )
    }

    @Test
    fun `a known contact's KEXACK raises no question while the wallet cannot pay one`() =
        runBlocking {
            unfunded.forEachIndexed { i, (wallet, select) ->
                AutoSpendVm.resetProcessState()
                val peer = "usyntheticm3waitkexack0$i"
                val prefs = responderPrefs(peer)
                val seam = HandshakeSeam(peer)
                val accounts = SelectedAccountFeed.create().apply { select(this) }
                val vm = FundsGateDriver.responderScreen(prefs, seam, accounts)
                val recorder = FundsGateDriver.record(vm)

                vm.retryKexAckIfResponder(peer)
                HandshakeHarness.awaitIdle(vm)

                assertWaitsSilently(wallet, recorder, seam, setup(AutomaticSetupKind.KEXACK, peer))
                assertEquals("$wallet: the consent must not be written", false, prefs.consent)
            }
        }

    @Test
    fun `our bootstrap KEX raises no question while the wallet cannot pay one`() =
        runBlocking {
            unfunded.forEachIndexed { i, (wallet, select) ->
                AutoSpendVm.resetProcessState()
                val peer = "usyntheticm3waitkex000$i"
                val prefs = AutoSpendPrefs(consent = false).apply { session(peer, complete = false) }
                val seam = HandshakeSeam(peer)
                val accounts = SelectedAccountFeed.create().apply { select(this) }
                val vm = autoSpendScreen(prefs, seam, accounts)
                val recorder = FundsGateDriver.record(vm)

                automaticKex(vm, prefs, peer)

                assertWaitsSilently(wallet, recorder, seam, setup(AutomaticSetupKind.KEX, peer))
            }
        }

    @Test
    fun `our Tunnel ZBOOT raises no question while the wallet cannot pay one`() =
        runBlocking {
            unfunded.forEachIndexed { i, (wallet, select) ->
                AutoSpendVm.resetProcessState()
                val peer = "usyntheticm3waitzboot0$i"
                val prefs =
                    AutoSpendPrefs(consent = false).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
                val seam = HandshakeSeam(peer)
                val accounts = SelectedAccountFeed.create().apply { select(this) }
                val vm = autoSpendScreen(prefs, seam, accounts)
                val recorder = FundsGateDriver.record(vm)

                tunnelZboot(vm, peer)

                assertWaitsSilently(wallet, recorder, seam, setup(AutomaticSetupKind.ZBOOT, peer))
            }
        }

    /** Control (passes before and after): the same KEXACK on a wallet that can pay raises the question, pays nothing. */
    @Test
    fun `control - on a funded wallet the KEXACK raises the question`() =
        runBlocking {
            val peer = "usyntheticm3waitfunded01"
            val prefs = responderPrefs(peer)
            val seam = HandshakeSeam(peer)
            val vm = openResponderChat(prefs, seam, SelectedAccountFeed.create(), peer)

            assertTrue(FundsGateDriver.state(vm) is SendMessageState.NeedsHandshakeSpendConsent)
            assertEquals(0, seam.proposalRequests.get())
        }
}
