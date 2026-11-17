package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1038-R1 (P3; T7aF review of 6efce1643, finding 3) — the Tunnel offer's Accept voided an old owed key exchange
 * before writing the acceptance. When that write is refused, nothing is paid and the chat stays Open, but the key
 * exchange the user confirmed earlier (and the ZBOOT allowance of its question) is lost with nothing in its place.
 *
 * Wanted: a refused acceptance changes nothing: the owed mark and the allowance stay, nothing is paid. Every address,
 * key and transaction id is synthetic.
 */
class RefusedTunnelOfferAcceptanceKeepsTheOwedKexTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `an acceptance that is not saved keeps the owed key exchange and its allowance`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.OPEN, accountSource = OpenChatTunnelOfferPhone.funded())
            val phone = OpenChatTunnelOfferPhone(world)
            phone.heldKex()
            world.prefs.kexOwed += world.peer
            world.prefs.kexOwedReason[world.peer] = ZchatPreferences.KEX_OWED_REASON_RECONNECT
            world.prefs.zbootQuoted += world.peer
            val vm = phone.screen()
            assertEquals("fixture: the offer is not shown", "OFFERED", phone.stage(vm))
            phone.refuseOfferWrites = true

            phone.call(vm, "acceptOpenChatTunnelOffer")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("a refused acceptance paid", 0, world.seam.proposals.get())
            assertEquals("a refused acceptance left Open", ConversationMode.OPEN, phone.modes[world.peer])
            assertTrue("a refused acceptance lost the key exchange the user confirmed earlier", world.peer in world.prefs.kexOwed)
            assertTrue("a refused acceptance lost the ZBOOT allowance of that question", world.peer in world.prefs.zbootQuoted)
        }
}
