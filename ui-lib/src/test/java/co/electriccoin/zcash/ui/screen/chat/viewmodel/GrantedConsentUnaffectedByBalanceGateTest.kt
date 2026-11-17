package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.autoSpendScreen
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.automaticKex
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.responderPrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.responderScreen
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
import org.junit.Before
import org.junit.Test

/**
 * M3 (ZC-110) control, green before and after the fix — the orchestrator's default for owner question 9 (M3-SPEC §3):
 * an Allow already given stays a standing consent whatever the balance. The funds gate only decides when the question
 * is ASKED; it never reads a given consent as withdrawn, and it never stops an attempt the consent covers. (The send
 * itself then meets the shortfall and re-arms as before; here the SDK seam builds it, so the count shows the attempt
 * passed the gate.) The way out of the consent is the Settings switch of KI-23, not the balance.
 *
 * All keys are generated per run and every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrantedConsentUnaffectedByBalanceGateTest {
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

    @Test
    fun `a given consent lets the KEXACK through on an unfunded wallet and asks nothing`() =
        runBlocking {
            val peer = "usyntheticm3consentack01"
            val prefs = responderPrefs(peer).apply { consent = true }
            val seam = HandshakeSeam(peer)
            val accounts = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
            val vm = responderScreen(prefs, seam, accounts)
            val recorder = FundsGateDriver.record(vm)

            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)
            recorder.stop()

            assertFalse("a given consent was asked again: ${recorder.states.toList()}", recorder.asked())
            assertEquals("the attempt the consent covers was stopped by the balance", 1, seam.proposalRequests.get())
            assertEquals("the consent was read as withdrawn", true, prefs.consent)
            assertFalse(
                "an attempt under the consent was left in the question's queue",
                FundsGateDriver.setup(AutomaticSetupKind.KEXACK, peer) in FundsGateDriver.queued(),
            )
        }

    @Test
    fun `a given consent lets our KEX through on a dust wallet and asks nothing`() =
        runBlocking {
            val peer = "usyntheticm3consentkex01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            val seam = HandshakeSeam(peer)
            val accounts = SelectedAccountFeed.create().apply { ironwoodDust() }
            val vm = autoSpendScreen(prefs, seam, accounts)
            val recorder = FundsGateDriver.record(vm)

            automaticKex(vm, prefs, peer)
            recorder.stop()

            assertFalse("a given consent was asked again: ${recorder.states.toList()}", recorder.asked())
            assertEquals("the attempt the consent covers was stopped by the balance", 1, seam.proposalRequests.get())
            assertEquals("the consent was read as withdrawn", true, prefs.consent)
        }
}
