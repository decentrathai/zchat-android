package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ZC-1392 (high, money-path rule 2; Fable review at c39a0f61c): the Accept wait ZC-1375 writes to the store
 * (AcceptFundsWait: contact, key, until) records no account. Account A taps Accept while its funds confirm; the process ends;
 * within the hour the user starts the app with account B selected. The first funded block runs the saved wait
 * (resumeAcceptsWaitingForFunds -> acceptInboundKexRequest) and pays the answer out of B, who never tapped it.
 *
 * Must hold: as B no block pays the answer; A selected again still pays it, once. Everything is synthetic.
 */
class SavedAcceptWaitBelongsToTheAccountThatTappedTest {
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
    fun `after a restart with another account selected the saved Accept wait pays nothing, and A still pays`() =
        runBlocking {
            val armed = AcceptWaitPhone.acceptedWhileConfirming()

            val restarted = armed.restart()
            armed.fundsSpendable(AcceptWaitPhone.accountB)
            armed.blocks(restarted, AcceptWaitPhone.START_TIP, AcceptWaitPhone.START_TIP + 1, AcceptWaitPhone.START_TIP + 2)

            assertEquals("account B paid the answer of account A's Accept after a restart", 0, armed.submits())

            armed.fundsSpendable()
            armed.blocks(restarted, AcceptWaitPhone.START_TIP + 3, AcceptWaitPhone.START_TIP + 4)
            assertEquals("A selected again: its Accept's answer was not paid once", 1, armed.submits())
        }
}
