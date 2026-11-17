package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
 * ZC-1945 (Fable review of fix/2130-msg 4efd9a54f, 16 Nov 2026; register high; money-path rule 2) — the ZC-1909 funds-wait
 * park stored the chat's mode at PARK time on the row (acceptedMode), not the mode the user's Pay was given in. A chat
 * switched away from Shielded after the Pay (here to Open, where ZChat never pays on chain) and before the park got a row
 * that "consented" in the new mode: resumeFundsWaitRows saw the mode equal to the row's and paid the message on chain.
 * And a row resumed from its wait (its key exchange paid in an earlier run, so the send's kexFirst is false) met none of
 * the Open checks of the send: a switch to Open during its build still paid it.
 *
 * Wanted: the park keeps the consent's mode (Shielded here) and refuses when the chat's mode now differs, so nothing pays
 * the message by itself; a resumed row is refused when its chat's mode left the consent's mode before its submit, as a
 * KEX-first send is. Real ChatViewModel send over [ParkedFirstMessageChat]; every address and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParkedFirstMessageKeepsItsConsentModeTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1945parkmode00001"
        const val KEX_ONLY = 1
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
    fun `a Pay given in Shielded is not parked after the chat became Open, and no block pays the message`() =
        runBlocking {
            val c = ParkedFirstMessageChat.open(sendScopes, PEER)
            c.wallet.select(available = 0, pending = 0) // the send's classifier: "not enough" (the parking path)
            c.vmWallet.select(available = 0, pending = MESSAGE_COST + 7_000) // the wallet now: the KEX's change confirms
            // The user switches the chat to Open after the Pay, while the message meets the refusal.
            c.funds.onRefuse = { c.world.chat.fixture.mode = ConversationMode.OPEN }
            c.payKexAndAttemptMessage()
            assertEquals("fixture: the key exchange was paid", KEX_ONLY, c.submits())

            c.changeConfirms(MESSAGE_COST + 7_000)
            repeat(3) { c.block() }
            assertTrue(
                "a message whose Pay was given in Shielded was paid on chain in an Open chat: ${c.submits()} submits",
                c.world.staysAt(KEX_ONLY),
            )
            val row = c.world.row()
            assertTrue(
                "the row still says it waits to be paid by itself: $row",
                row == null || row.acceptedMode != ConversationMode.OPEN.name,
            )
        }

    @Test
    fun `a resumed row whose chat became Open during its build is not paid`() =
        runBlocking {
            val c = ParkedFirstMessageChat.open(sendScopes, PEER)
            c.wallet.select(available = 0, pending = 0)
            c.vmWallet.select(available = 0, pending = MESSAGE_COST + 7_000)
            c.payKexAndAttemptMessage()
            val parked = c.world.row()
            assertTrue("fixture: the row was not parked as waiting for its funds: $parked", parked?.waitsForFunds() == true)

            // The change confirms; the block resumes the row (its mode check passes), and the chat becomes Open while the
            // message is built, before its submit.
            c.changeConfirms(MESSAGE_COST + 7_000)
            c.funds.onBuild = { c.world.chat.fixture.mode = ConversationMode.OPEN }
            c.block()
            repeat(2) { c.block() }
            assertTrue(
                "a resumed row was paid on chain after its chat became Open: ${c.submits()} submits",
                c.world.staysAt(KEX_ONLY),
            )
        }

    @Test
    fun `control - a parked row in an unchanged Shielded chat is sent once`() =
        runBlocking {
            val c = ParkedFirstMessageChat.open(sendScopes, PEER)
            c.wallet.select(available = 0, pending = 0)
            c.vmWallet.select(available = 0, pending = MESSAGE_COST + 7_000)
            c.payKexAndAttemptMessage()
            c.changeConfirms(MESSAGE_COST + 7_000)
            c.block()
            assertTrue(
                "the waiting message was not sent: ${c.submits()} submits",
                PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { c.submits() >= KEX_ONLY + 1 },
            )
            repeat(2) { c.block() }
            assertTrue("the message was paid more than once", c.world.staysAt(KEX_ONLY + 1))
        }
}
