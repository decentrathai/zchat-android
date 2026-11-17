package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
import java.util.UUID

/**
 * ZC-1946 (Fable review of fix/2130-msg 4efd9a54f, 16 Nov 2026; register high; W-101 wrong-wallet class) — after the key
 * exchange of a first message was paid, the live note wait (R9-2, ZC-1909) builds the message again at each block with
 * whichever account is selected THEN. The user switches to another, funded account during the wait: the next block pays
 * the message from that account, which never accepted its price.
 *
 * Wanted: before each rebuild the selected account is compared with the account that accepted the price; on a mismatch
 * nothing is built or paid, and the row stays with its consent (it waits for the accepting account, as resumeFundsWaitRows
 * does), so selecting the accepting account again sends it once. Real ChatViewModel send over [ParkedFirstMessageChat];
 * every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveNoteWaitPaysOnlyFromTheConsentingAccountTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1946liveaccount01"
        const val OTHER_ACCOUNT = "19461946-0000-4000-8000-000000001946"
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
    fun `an account switched to during the live note wait never pays the message`() =
        runBlocking {
            val c = ParkedFirstMessageChat.open(sendScopes, PEER)
            // Both views: the KEX's change confirms (a wait: the live note wait holds the message).
            c.wallet.select(available = 0, pending = CHANGE)
            c.vmWallet.select(available = 0, pending = CHANGE)
            c.payKexAndAttemptMessage()
            assertEquals("fixture: the key exchange was paid", KEX_ONLY, c.submits())

            // The user selects another, funded account while the message waits; the SDK would now build it.
            c.funds.refuse = false
            c.wallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = 500_000)
            c.vmWallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = 500_000)
            repeat(3) { c.block() }
            assertTrue(
                "the live note wait paid the message from an account that never accepted its price: ${c.submits()} submits",
                c.world.staysAt(KEX_ONLY),
            )
        }

    @Test
    fun `after such a switch the accepting account selected again sends the message once`() =
        runBlocking {
            val c = ParkedFirstMessageChat.open(sendScopes, PEER)
            c.wallet.select(available = 0, pending = CHANGE)
            c.vmWallet.select(available = 0, pending = CHANGE)
            c.payKexAndAttemptMessage()

            c.funds.refuse = false
            c.wallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = 500_000)
            c.vmWallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = 500_000)
            repeat(2) { c.block() }
            assertTrue("another account paid the message", c.world.staysAt(KEX_ONLY))

            c.wallet.selectBase()
            c.vmWallet.selectBase()
            c.changeConfirms(CHANGE)
            c.block()
            assertTrue(
                "the accepting account did not send the waiting message: ${c.submits()} submits",
                PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { c.submits() >= KEX_ONLY + 1 },
            )
            repeat(2) { c.block() }
            assertTrue("the message was paid more than once: ${c.submits()} submits", c.world.staysAt(KEX_ONLY + 1))
        }
}
