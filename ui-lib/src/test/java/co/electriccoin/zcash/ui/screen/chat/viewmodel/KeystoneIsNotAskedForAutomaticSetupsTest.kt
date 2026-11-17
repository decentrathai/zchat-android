package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
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
 * Round 8 D4 (round 7c C5 REVIEW item 1a, re-check 1) — a Keystone user is asked a question whose Allow can never pay.
 *
 * The send chokepoint refuses every direct-submit, no-navigation send on a KeystoneAccount (ZC-102,
 * CreateChunkedMessageProposalUseCase), and every automatic chat setup (KEX, KEXACK, ZBOOT, GROUP_ACCEPT) is one.
 * Without consent, ChatViewModel still raises NeedsHandshakeSpendConsent for them while Keystone is selected; after
 * Allow nothing is sent anyway (and a held reply is never sent while Keystone stays selected).
 *
 * Wanted: while the selected account is a Keystone account, no consent question is raised. A one-time explanation
 * (ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP) is shown instead, once per process, and every setup stays queued or held
 * exactly as before. When the software account is selected again, the question comes back as today.
 *
 * The explanation text is read by name, so this file compiles before it exists. All addresses, keys and group ids
 * are synthetic; the Keystone account is the SDK's public test fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneIsNotAskedForAutomaticSetupsTest {
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

    private val keystoneText: String by lazy {
        ChatSendCopy::class.java.getField("KEYSTONE_AUTOMATIC_SETUP").get(null) as String
    }

    private fun state(vm: ChatViewModel) = HandshakeHarness.sendMessageState(vm)

    private suspend fun automaticKex(
        vm: ChatViewModel,
        prefs: AutoSpendPrefs,
        peer: String,
    ) {
        prefs.ownBootSent[peer] = true // the bootstrap trigger that sends our KEX
        vm.sendKEXMessage(peer, HandshakeHarness.SELF)
        awaitIdle(vm)
    }

    @Test
    fun `with Keystone selected no consent question is raised, the explanation shows once, and nothing is paid`() =
        runBlocking {
            val peer = "usyntheticd4keystonekex01"
            val inviter = "usyntheticd4keystonegrp01"
            val prefs =
                AutoSpendPrefs(consent = false).apply {
                    session(peer, complete = false)
                    session(inviter, complete = true)
                }
            val accounts = SelectedAccountFeed.create().apply { keystoneSelected() }
            val seam = HandshakeSeam(peer)
            val vm =
                chatViewModelWith(prefs.proxy, seam, accountSource = accounts)
                    .also(AutoSpendVm::wireBootstrapFields)

            automaticKex(vm, prefs, peer)
            val first = state(vm)
            assertFalse(
                "a Keystone user was asked to allow automatic payments that Keystone can never sign",
                first is SendMessageState.NeedsHandshakeSpendConsent,
            )
            assertEquals("the one-time Keystone explanation must be shown", SendMessageState.Error(keystoneText), first)

            vm.resetSendState()
            processInvite(vm, "GD4KEYSTONE", plaintextInvite(inviter))
            awaitIdle(vm)
            automaticKex(vm, prefs, peer)
            assertEquals(
                "a second automatic setup in the same process must show nothing new (no question, no second explanation)",
                SendMessageState.Idle,
                state(vm),
            )
            assertEquals("nothing may be submitted while Keystone is selected", 0, seam.submits.get())
            assertEquals("nothing may be built while Keystone is selected", 0, seam.proposalRequests.get())
            // Round 8 P1 (PHONE_TRIAGE F3): a group reply that cannot go free waits for Join; it is never held for the
            // standing consent (it was held on disk and in memory).
            assertTrue("the group reply must wait for Join", prefs.needsJoin.containsKey("GD4KEYSTONE"))
            assertFalse("the group reply is held for the automatic sends", prefs.heldGroupAccepts.containsKey("GD4KEYSTONE"))
            assertFalse("the group reply is held in memory for the automatic sends", GroupInviteDriver.heldInMemory("GD4KEYSTONE"))

            accounts.ironwoodSpendable()
            automaticKex(vm, prefs, peer)
            val asked = state(vm)
            assertTrue(
                "with the software account selected again the question must come back: $asked",
                asked is SendMessageState.NeedsHandshakeSpendConsent,
            )
            assertEquals(
                "Round 8 P1: the question never counts a group reply (it waits for Join)",
                0,
                (asked as SendMessageState.NeedsHandshakeSpendConsent).groupJoins,
            )
            assertEquals("nothing may be paid without consent", 0, seam.submits.get())
        }

    /** Control (passes before and after): with the software account selected the question is raised as today. */
    @Test
    fun `control - with the software account selected the automatic KEX raises the question`() =
        runBlocking {
            val peer = "usyntheticd4keystonekex02"
            val prefs = AutoSpendPrefs(consent = false).apply { session(peer, complete = false) }
            val accounts = SelectedAccountFeed.create()
            val seam = HandshakeSeam(peer)
            val vm =
                chatViewModelWith(prefs.proxy, seam, accountSource = accounts)
                    .also(AutoSpendVm::wireBootstrapFields)

            automaticKex(vm, prefs, peer)
            assertTrue(state(vm) is SendMessageState.NeedsHandshakeSpendConsent)
            assertEquals(0, seam.submits.get())
        }
}
