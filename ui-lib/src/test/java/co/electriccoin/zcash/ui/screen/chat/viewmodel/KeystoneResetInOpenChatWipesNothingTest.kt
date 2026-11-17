package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * T1b-1 §2 (fix-all 2.13.0, train T1b): Reset encryption in an Open chat on a Keystone account wipes the contact's keys.
 *
 * resetSecureSession skips the paid question when the reset pays nothing on-chain (an Open chat whose contact's relay
 * key is known, resetEncryptionPaysOnChain), and at 35511a97a that branch wipes the keys and re-runs the handshake
 * BEFORE the Keystone check. On a Keystone account the contact's session is wiped and a relay handshake goes out under
 * the Keystone account's identity. The T1 phone check that passed ("nothing wiped") was a Shielded chat.
 *
 * Wanted: the Keystone check is the reset's first step, whatever the mode. Nothing is wiped (keys, markers, Quantum
 * Shield key, ratchet), nothing is published or built, and the Keystone text is shown.
 *
 * Reuses ResetEncryptionAsksFirstTest's [ResetFixture]; every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneResetInOpenChatWipesNothingTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { plaintext, _ ->
            published += plaintext
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-rumor-${published.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `Reset in an Open chat on a Keystone account wipes nothing and publishes nothing`() =
        runBlocking {
            val accounts = SelectedAccountFeed.create().apply { keystoneSelected() }
            val f = ResetFixture("usynthetict1bresetopenks01", ConversationMode.OPEN).apply { peerRelayKey = RELAY_KEY }
            val vm = f.viewModel(accounts)

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)

            f.assertNothingWiped("in an Open chat with a Keystone account selected")
            assertEquals("nothing may go out over the relays with Keystone selected", emptyList<String>(), published.toList())
            assertEquals("nothing may be built with Keystone selected", 0, f.seam.proposalRequests.get())
            assertEquals(SendMessageState.Error(ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP), HandshakeHarness.sendMessageState(vm))
        }

    /** Control, green before and after: on the ZChat account the same reset runs without a question, as before. */
    @Test
    fun `control - Reset in an Open chat on the ZChat account still runs`() =
        runBlocking {
            val f = ResetFixture("usynthetict1bresetopenzc01", ConversationMode.OPEN).apply { peerRelayKey = RELAY_KEY }
            val vm = f.viewModel()

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)

            f.assertWiped()
            assertEquals("an Open chat never pays a KEX (#224)", 0, f.seam.proposalRequests.get())
        }

    private companion object {
        const val RELAY_KEY = "7e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"
    }
}
