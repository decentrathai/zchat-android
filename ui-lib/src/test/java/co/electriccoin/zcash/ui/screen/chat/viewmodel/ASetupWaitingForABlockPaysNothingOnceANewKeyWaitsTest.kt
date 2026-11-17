package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-739-R1 (review of T7bH, 19 Oct 2026, finding 1, P1) — the key-changed test of ZC-739 sits at each sender's claim;
 * a set-up message that already passed it and waits for a block (its proposal found the funds still confirming) re-read
 * nothing about the flag before its next attempt. So when the contact's new key arrives while it waits, the next block
 * pays it (17,000 zat) while the banner says no payment goes out.
 *
 * Must hold: before every attempt the flag is read again; while it is up the attempt stops and pays nothing, and the
 * marks stay (the owed key exchange stays owed). After the decision the held set-up is paid once, by its own path.
 * - our ZBOOT of a Tunnel chat, waiting for a block (the per-block tick started it);
 * - an owed reconnect key exchange, waiting for a block (a chat open started it).
 *
 * Built on [KeyChangeReconnectChat] (its seam refuses the first proposal of a kind as "still confirming", so that attempt
 * waits for the next block; [KeyChangeReconnectChat.keyChanged] is the flag). Every key and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ASetupWaitingForABlockPaysNothingOnceANewKeyWaitsTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(ownHistoryReadBefore)
        Dispatchers.resetMain()
        ZbootSetup.resetProcess()
    }

    private suspend fun blocks(
        chat: KeyChangeReconnectChat,
        vm: ChatViewModel,
        n: Int,
    ) {
        repeat(n) {
            chat.owed.tick(vm)
            awaitIdle(vm)
        }
    }

    @Test
    fun `a ZBOOT waiting for a block pays nothing once the contact's new key waits, and once after the decision`() =
        runBlocking {
            val chat =
                KeyChangeReconnectChat(
                    PEER_ZBOOT,
                    consent = true,
                    mode = ConversationMode.TUNNEL,
                    failFirstZbootWithStillConfirming = true,
                    exchangeComplete = true,
                )
            chat.owed.store.setSentNostrBootPubkey(PEER_ZBOOT, null)
            chat.owed.tunnelPeers += PEER_ZBOOT
            chat.keyChanged = false
            val vm = chat.viewModel()

            chat.owed.tick(vm)
            awaitTrue { chat.owed.heights.isWaiting() }
            assertEquals("fixture: the ZBOOT did not meet the still-confirming funds first", 1, chat.seam.zbootProposals.get())

            chat.keyChanged = true
            blocks(chat, vm, 3)
            assertEquals("a ZBOOT waiting for a block was paid while the contact's new key waits", 0, chat.seam.submittedOf("ZBOOT"))

            chat.keyChanged = false
            blocks(chat, vm, 3)
            assertEquals("after the decision the held ZBOOT was not paid exactly once", 1, chat.seam.submittedOf("ZBOOT"))
            ZbootSetup.kill(vm)
        }

    @Test
    fun `an owed key exchange waiting for a block pays nothing once the contact's new key waits and stays owed`() =
        runBlocking {
            val chat =
                KeyChangeReconnectChat(
                    PEER_KEX,
                    consent = true,
                    mode = ConversationMode.TUNNEL,
                    failFirstKexWithStillConfirming = true,
                )
            chat.owed.kexOwed += PEER_KEX
            chat.owed.kexOwedReason[PEER_KEX] = ZchatPreferences.KEX_OWED_REASON_RECONNECT
            chat.keyChanged = false
            val vm = chat.viewModel()

            chat.owed.openChat(vm)
            awaitTrue { chat.owed.heights.isWaiting() }
            assertEquals("fixture: the key exchange did not meet the still-confirming funds first", 1, chat.seam.kexProposals.get())

            chat.keyChanged = true
            blocks(chat, vm, 3)
            assertEquals("an owed key exchange waiting for a block was paid while the new key waits", 0, chat.seam.submittedOf("KEX"))
            assertTrue("the owed mark was dropped while it was held", PEER_KEX in chat.owed.kexOwed)

            chat.keyChanged = false
            blocks(chat, vm, 3)
            assertEquals("after the decision the owed key exchange was not paid exactly once", 1, chat.seam.submittedOf("KEX"))
            ZbootSetup.kill(vm)
        }

    private companion object {
        const val PEER_ZBOOT = "usyntheticzc739r1zbootwait01"
        const val PEER_KEX = "usyntheticzc739r1kexwait0001"
    }
}
