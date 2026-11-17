package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ParkedFirstMessageChat.Companion.MESSAGE_COST
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
 * ZC-1953 (Fable re-review of fix/2130-msg f58e91ef2, 16 Nov 2026; register high; money-path rule 2) — after the key
 * exchange of a first message was paid, the live note wait (R9-2, ZC-1909) builds the message again at each block. Its
 * only mode check was the Open one (kexFirst); the ZC-1945 consent-mode check read the mode only from a row stored as
 * waiting BEFORE the run. A switch to Tunnel during the live wait let the same run pay the message on chain in a Tunnel
 * chat, whose Pay was given in Shielded.
 *
 * Wanted: the run's consent mode is the Pay's; on any mode change before a rebuild or its submit, nothing is built or
 * paid and the row stops with an existing honest status. Real ChatViewModel send over [ParkedFirstMessageChat]; every
 * address and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveNoteWaitStopsWhenTheChatModeChangesTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1953livetunnel001"
        const val KEX_ONLY = 1
        const val CHANGE = MESSAGE_COST + 7_000
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `a switch to Tunnel during the live note wait never pays the message on chain`() =
        runBlocking {
            val c = ParkedFirstMessageChat.open(sendScopes, PEER)
            c.wallet.select(available = 0, pending = CHANGE)
            c.vmWallet.select(available = 0, pending = CHANGE)
            c.payKexAndAttemptMessage()
            assertEquals("fixture: the key exchange was paid", KEX_ONLY, c.submits())

            // No standing consent for automatic handshakes, so a Tunnel chat pays nothing of its own on a block.
            c.world.consent = false
            c.world.chat.fixture.mode = ConversationMode.TUNNEL
            c.changeConfirms(CHANGE)
            repeat(3) { c.block() }
            assertTrue(
                "the live note wait paid a Shielded-consented message on chain in a Tunnel chat: ${c.submits()} submits",
                c.world.staysAt(KEX_ONLY),
            )
            assertTrue(
                "the row was neither stopped nor left unpaid: ${c.world.row()}",
                PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) {
                    c.world.row()?.status == MessageStatus.FAILED.name
                },
            )
        }
}
