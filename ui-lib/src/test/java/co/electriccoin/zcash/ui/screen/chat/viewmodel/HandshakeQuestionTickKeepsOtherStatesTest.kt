package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.openResponderChat
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.responderPrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.setState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FundsGateDriver.state
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
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
 * M3 (ZC-110, KI-96) — the block tick that asks "Allow handshake transactions?" once funds are spendable must only ever
 * add that question to an idle screen.
 *
 * Must hold: the tick never replaces an open paid-action question or an error notice (and asks once the screen is idle
 * again); it asks nothing while a Keystone account is selected (and asks once the software account is back); with the
 * consent given it does nothing and pays nothing; a group join is never queued or asked for. Control: an account that
 * cannot be read is asked at once, as before (the question is the user's control, so an unreadable balance asks).
 *
 * All keys are generated per run, every address and group id is synthetic; the Keystone account is the SDK's public
 * test fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeQuestionTickKeepsOtherStatesTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    /** One phone whose KEXACK to [peer] was queued while the wallet held only a confirming deposit. */
    private class Phone(
        val prefs: HandshakePrefs,
        val seam: HandshakeSeam,
        val accounts: SelectedAccountFeed,
        val vm: ChatViewModel,
    )

    private suspend fun queuedWhileUnfunded(peer: String): Phone {
        val prefs = responderPrefs(peer)
        val seam = HandshakeSeam(peer)
        val accounts = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
        val vm = openResponderChat(prefs, seam, accounts, peer)
        assertTrue(
            "fixture sanity: the KEXACK waits in the question's queue",
            FundsGateDriver.setup(AutomaticSetupKind.KEXACK, peer) in FundsGateDriver.queued(),
        )
        assertEquals("fixture sanity: nothing paid", 0, seam.proposalRequests.get())
        return Phone(prefs, seam, accounts, vm)
    }

    private fun asked(vm: ChatViewModel): Boolean = state(vm) is SendMessageState.NeedsHandshakeSpendConsent

    @Test
    fun `the tick never replaces an open paid-action question`() =
        runBlocking {
            val phone = queuedWhileUnfunded("usyntheticm3tickpaid0001")
            val open =
                SendMessageState.NeedsPaidActionConfirmation(
                    PaidActionConfirmation(PaidAction.REACTION, onChainMessages = 1, totalCost = Zatoshi(17_000)),
                )
            setState(phone.vm, open)
            phone.accounts.ironwoodSpendable()

            chainTip(phone.vm)
            assertEquals("the block replaced the open paid-action question", open, state(phone.vm))

            setState(phone.vm, SendMessageState.Idle)
            chainTip(phone.vm)
            assertTrue("once the screen is idle the block must ask: ${state(phone.vm)}", asked(phone.vm))
            assertEquals(0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `the tick never replaces an error notice`() =
        runBlocking {
            val phone = queuedWhileUnfunded("usyntheticm3tickerror001")
            val notice = SendMessageState.Error("synthetic notice")
            setState(phone.vm, notice)
            phone.accounts.ironwoodSpendable()

            chainTip(phone.vm)
            assertEquals("the block replaced the notice", notice, state(phone.vm))

            setState(phone.vm, SendMessageState.Idle)
            chainTip(phone.vm)
            assertTrue("once the screen is idle the block must ask: ${state(phone.vm)}", asked(phone.vm))
            assertEquals(0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `the tick asks nothing while a Keystone account is selected`() =
        runBlocking {
            val phone = queuedWhileUnfunded("usyntheticm3tickkeyst001")
            setState(phone.vm, SendMessageState.Idle)
            phone.accounts.keystoneSelected()

            chainTip(phone.vm)
            assertEquals("the block asked a Keystone user a question Keystone can never pay", SendMessageState.Idle, state(phone.vm))

            phone.accounts.ironwoodSpendable()
            chainTip(phone.vm)
            assertTrue("with the funded software account back the block must ask: ${state(phone.vm)}", asked(phone.vm))
            assertEquals(0, phone.seam.proposalRequests.get())
        }

    /** Control (passes before and after): with the consent given, the tick asks nothing and pays nothing itself. */
    @Test
    fun `control - with the consent given the tick asks nothing and pays nothing`() =
        runBlocking {
            val phone = queuedWhileUnfunded("usyntheticm3tickconsent1")
            setState(phone.vm, SendMessageState.Idle)
            phone.prefs.consent = true
            phone.accounts.ironwoodSpendable()

            chainTip(phone.vm)
            assertFalse("the block asked although the consent is given", asked(phone.vm))
            assertEquals("the block paid the queued KEXACK by itself", 0, phone.seam.proposalRequests.get())
        }

    /** Control (passes before and after): a group join is never queued for the question, so no block asks for it. */
    @Test
    fun `control - a group join is never queued or asked for`() =
        runBlocking {
            val inviter = "usyntheticm3tickjoin0001"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val accounts = SelectedAccountFeed.create()
            val vm = chatViewModelWith(prefs.proxy, seam, accountSource = accounts).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTHM3JOIN", plaintextInvite(inviter))
            awaitIdle(vm)
            assertTrue("fixture sanity: the join waits for Join", prefs.needsJoin.containsKey("GSYNTHM3JOIN"))
            assertTrue(
                "a group join was queued for the question",
                FundsGateDriver.queued().none { it.kind == AutomaticSetupKind.GROUP_ACCEPT },
            )
            setState(vm, SendMessageState.Idle)
            chainTip(vm)
            assertFalse("a block asked for a group join", asked(vm))
            assertEquals(0, seam.proposalRequests.get())
        }

    /** Control (passes before and after): an account that cannot be read is asked at once, as before. */
    @Test
    fun `control - with no readable account the KEXACK still asks at once`() =
        runBlocking {
            val peer = "usyntheticm3tickunread01"
            val prefs = responderPrefs(peer)
            val seam = HandshakeSeam(peer)
            val vm = openResponderChat(prefs, seam, accounts = null, peer = peer)

            assertTrue("an unreadable account must be asked as before: ${state(vm)}", asked(vm))
            assertEquals(0, seam.proposalRequests.get())
        }
}
