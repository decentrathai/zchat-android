package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.HANDSHAKE_RESEND_COOLDOWN_MS
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
 * ZC-785 (b) (review of 12 Oct, finding 3b) — a PIN, kept on purpose; it passes before and after the fix of ZC-785.
 *
 * A ZBOOT whose submit the SDK stored and then reported as refused has an unknown outcome: the stored transaction may
 * still be broadcast. Since 7d1d183ab the sender no longer re-arms its delivered-identity marker after such a submit
 * (before, it cleared the marker and the next block could pay the same ZBOOT again). That is the money rule: no
 * automatic re-send after an unknown outcome. The price is that a ZBOOT that in the end is never mined is not retried
 * by itself; the user's Reconnect tap sends it again, at most once an hour.
 *
 * Pinned here: after the unknown outcome the marker and the paid record stay, and no later block, chat open or Call tap
 * builds or submits a second ZBOOT; Reconnect inside the hour sends nothing, and after the hour exactly one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AZbootWithAnUnknownSubmitOutcomeIsNotSentAgainByItselfTest {
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
    fun `after a submit with an unknown outcome no block, chat open or Call tap sends the ZBOOT again`() =
        runBlocking {
            val chat = tunnelChat()
            val vm = chat.viewModel()
            val seam = unknownFirstZbootSubmit()
            ZbootSetup.installSeam(vm, seam)
            val current = ZbootSetup.currentIdentity(vm)

            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("fixture sanity: the ZBOOT reached the SDK once", 1, seam.submittedOf("ZBOOT"))
            assertEquals(
                "the marker of a ZBOOT that may still be broadcast was re-armed",
                current,
                chat.store.getSentNostrBootPubkey(PEER),
            )
            assertTrue(
                "the paid record of a ZBOOT that may still be broadcast was withdrawn",
                HandshakePaidRecord.names(chat.paidRecords[SLOT], current),
            )

            repeat(3) {
                chat.tick(vm)
                awaitIdle(vm)
            }
            vm.ensureNostrBootstrapSent(PEER)
            awaitIdle(vm)
            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)

            assertEquals("a second ZBOOT was built after an unknown outcome", 1, seam.proposalsOf("ZBOOT"))
            assertEquals("a second ZBOOT was submitted after an unknown outcome", 1, seam.submittedOf("ZBOOT"))
            assertEquals("nothing else may be paid here", 1, seam.submitted.size)
            assertEquals(current, chat.store.getSentNostrBootPubkey(PEER))
            ZbootSetup.kill(vm)
        }

    @Test
    fun `the Reconnect tap sends nothing inside the hour and exactly one ZBOOT after it`() =
        runBlocking {
            val chat = tunnelChat()
            val vm = chat.viewModel()
            val seam = unknownFirstZbootSubmit()
            ZbootSetup.installSeam(vm, seam)
            val current = ZbootSetup.currentIdentity(vm)
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("fixture sanity: the ZBOOT reached the SDK once", 1, seam.submittedOf("ZBOOT"))

            vm.reSendOurIdentity(PEER)
            awaitIdle(vm)
            assertEquals("Reconnect inside the hour paid a second ZBOOT", 1, seam.proposalsOf("ZBOOT"))

            // The hour has passed (the record is two hours old) and the first ZBOOT never arrived.
            chat.paidRecords[SLOT] = HandshakePaidRecord.encode(current, System.currentTimeMillis() - 2 * HANDSHAKE_RESEND_COOLDOWN_MS)
            vm.reSendOurIdentity(PEER)
            awaitIdle(vm)
            assertEquals("Reconnect after the hour must send our identity again", 2, seam.submittedOf("ZBOOT"))

            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("a later block paid a third ZBOOT", 2, seam.submittedOf("ZBOOT"))
            assertEquals(2, seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    private fun unknownFirstZbootSubmit(): HookedSetupSeam =
        HookedSetupSeam(PEER).apply {
            submitOutcome = { kind, n -> if (kind == "ZBOOT" && n == 1) HookedSetupSeam.storedThenRejected() else null }
        }

    /** A Tunnel chat with keys both ways, no relay key, the standing consent given, our relay identity never delivered. */
    private fun tunnelChat(): ResetOwedChat =
        ResetOwedChat(PEER, consent = true, mode = ConversationMode.TUNNEL).also { chat ->
            chat.store.setSentNostrBootPubkey(PEER, null)
            chat.tunnelPeers += PEER
        }

    private companion object {
        const val PEER = "usyntheticzc785unknownsent01"
        val SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)
    }
}
