package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1234 (medium) — with less than the 17,000 zat one handshake costs, the Tunnel offer's Accept stops at the wallet gate
 * ([ChatViewModel] acceptOpenChatTunnelOffer, "the wallet cannot pay the answer yet") with a log line only: the button reads
 * as dead. ZC-1230 tells the need only when the SDK refuses a build, which this wallet never reaches.
 *
 * Wanted: the same notice as ZC-1230, naming 0.00017 ZEC, nothing built or paid, the chat Open with its offer and hold.
 * At exactly 17,000 the gate lets the build through (control, so a notice on every tap cannot pass). No wire or stored
 * format is involved. Every address, key and id is synthetic.
 */
class AcceptBelowTheHandshakeFloorTellsTheNeedTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun phoneWith(zatoshi: Long): OpenChatTunnelOfferPhone {
        val wallet =
            Zc101FakeAccountDataSource(
                runBlocking {
                    syntheticZashiAccount().let {
                        it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(zatoshi), Zatoshi(0), Zatoshi(0))))
                    }
                },
            )
        return OpenChatTunnelOfferPhone(LedgerWorld(mode = ConversationMode.OPEN, consent = false, accountSource = wallet))
    }

    private fun belowTheFloor(zatoshi: Long) =
        runBlocking {
            val phone = phoneWith(zatoshi)
            val seam = phone.world.seam
            val contactKey = phone.heldKex()
            val vm = phone.screen()
            assertEquals("fixture: the offer is not shown", "OFFERED", phone.stage(vm))

            phone.call(vm, "acceptOpenChatTunnelOffer")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("a wallet below the floor built an answer", 0, seam.proposals.get())
            assertEquals("a wallet below the floor paid", 0, seam.submits.get())
            assertEquals(ConversationMode.OPEN, phone.modes[phone.world.peer])
            assertEquals(contactKey, phone.hold[phone.world.peer])
            assertEquals("OFFERED", phone.stage(vm))

            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue("Accept stopped below the floor and the screen says nothing: $state", state is SendMessageState.Error)
            val message = (state as SendMessageState.Error).message
            assertTrue("the notice does not name the 0.00017 ZEC one answer needs: $message", FLOOR_ZEC.containsMatchIn(message))
        }

    @Test
    fun `the Tunnel offer's Accept tells the need with an almost empty wallet`() = belowTheFloor(1_000L)

    @Test
    fun `the Tunnel offer's Accept tells the need one zatoshi below the floor`() = belowTheFloor(16_999L)

    @Test
    fun `control - exactly the floor goes on to the build`() =
        runBlocking {
            val phone = phoneWith(17_000L)
            val seam = phone.world.seam
            phone.heldKex()
            val vm = phone.screen()

            phone.call(vm, "acceptOpenChatTunnelOffer")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("a wallet at the floor did not build", 1, seam.proposals.get())
        }

    private companion object {
        val FLOOR_ZEC = Regex("""(?<![\d.,])0[.,]00017(?!\d)""")
    }
}
