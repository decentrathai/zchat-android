package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
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
import org.junit.Before
import org.junit.Test

/**
 * ZC-792 (T7B-HANDSHAKE-SPEC rev 2, section 2.16) — a Reconnect tap shows "details re-sent" and starts our ZBOOT; when
 * that ZBOOT waits for a block (funds still confirming) and a complete history pass then reads our CURRENT relay
 * identity as mined, `finishZbootHistoryPass` marks the tap's sender as mined and it stops: the identity the user asked
 * to re-send is never sent, after the screen said it was.
 *
 * Must hold: the tap's ZBOOT is sent exactly once (one submit) after such a pass. Control: a second tap within the hour
 * still sends nothing more (the hour rule).
 *
 * [ResetOwedChat] (a Shielded chat after a restore, standing consent given; its seam refuses the first ZBOOT as "still
 * confirming", so that attempt waits for the next block). Every key and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AReconnectTapIsNotSwallowedByAHistoryPassTest {
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

    @Test
    fun `a Reconnect tap waiting for a block still sends its ZBOOT once after a pass that read the identity mined`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = true, failFirstZbootWithStillConfirming = true)
            chat.store.setSentNostrBootPubkey(PEER, null)
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)

            vm.reSendOurIdentity(PEER)
            awaitTrue { chat.heights.isWaiting() }
            assertEquals("fixture: the tap's ZBOOT did not meet the still-confirming funds first", 1, chat.seam.zbootProposals.get())

            ZbootSetup.historyPass(vm, PEER, listOf(ZbootSetup.boot(current)))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
            repeat(3) {
                chat.tick(vm)
                awaitIdle(vm)
            }

            assertEquals("the Reconnect tap's ZBOOT was cancelled by a history pass", 1, chat.seam.submittedOf("ZBOOT"))

            vm.reSendOurIdentity(PEER)
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("a second tap within the hour paid again", 1, chat.seam.submittedOf("ZBOOT"))
            ZbootSetup.kill(vm)
        }

    private companion object {
        const val PEER = "usyntheticzc792reconnecttap1"
    }
}
