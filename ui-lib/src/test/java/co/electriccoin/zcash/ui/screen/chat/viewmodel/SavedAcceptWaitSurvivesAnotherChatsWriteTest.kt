package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ZC-1391 (medium; Fable review at c39a0f61c): ZC-1375 keeps an Accept's wait for the funds in one store slot that holds
 * every contact's wait. A new screen reads that slot back only on its first block tick (resumeAcceptsWaitingForFunds ->
 * restoreAcceptWaits); every other writer (tellAcceptWaitsForFunds, hideChat, the end of an Accept run) writes the slot from
 * the process memory alone (storeAcceptWaits). After a restart, such a write before the first tick replaces the slot with
 * what this process knows, and the kept wait of another chat is gone: its Accept never goes by itself.
 *
 * Must hold: after a restart within the hour, Delete chat on another contact before any block leaves chat C's wait, and the
 * funded block pays C's one answer. Everything is synthetic.
 */
class SavedAcceptWaitSurvivesAnotherChatsWriteTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        LedgerTests.setUp(sendScopes)
        AcceptWaitPhone.clearProcessMemory()
    }

    @After
    fun tearDown() {
        AcceptWaitPhone.clearProcessMemory()
        LedgerTests.tearDown(sendScopes)
    }

    @Test
    fun `control - after a restart the kept Accept wait pays one answer on the funded block`() =
        runBlocking {
            val armed = AcceptWaitPhone.acceptedWhileConfirming()

            val restarted = armed.restart()
            armed.fundsSpendable()
            armed.blocks(restarted, AcceptWaitPhone.START_TIP, AcceptWaitPhone.START_TIP + 1)

            assertEquals("fixture: the kept Accept wait did not pay after a restart", 1, armed.submits())
        }

    @Test
    fun `after a restart Delete chat on another contact before the first block keeps chat C's Accept wait`() =
        runBlocking {
            val armed = AcceptWaitPhone.acceptedWhileConfirming()

            val restarted = armed.restart()
            restarted.hideChat(OTHER_PEER)
            armed.fundsSpendable()
            armed.blocks(restarted, AcceptWaitPhone.START_TIP, AcceptWaitPhone.START_TIP + 1)

            assertEquals(
                "Delete chat on another contact after a restart wiped chat C's saved Accept wait; answers paid",
                1,
                armed.submits(),
            )
        }

    private companion object {
        val OTHER_PEER: String = SyntheticUnifiedAddress.mainnet(13_910)
    }
}
