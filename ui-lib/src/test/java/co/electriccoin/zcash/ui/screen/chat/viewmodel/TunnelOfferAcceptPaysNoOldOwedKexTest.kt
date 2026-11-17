package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ZC-1038 (high, money; T7aF cross-family review, Grok F1) — accepting a Tunnel offer lets an old owed key exchange pay
 * without being quoted.
 *
 * The chat is Open and a KEX is still owed from an action confirmed before the switch to Open (here a key-change
 * reconnect; ZC-880 keeps the mark for a later mode). The contact's paid key exchange is held and offered as a Tunnel
 * chat; its "Accept and pay" quotes ONE on-chain message (the answer). Accept switches the chat to Tunnel, and the owed
 * mark, no longer stopped by Open, is then paid by the next block or chat open ([ChatViewModel.retryOwedKeyExchange]):
 * a second payment the Accept question never counted (money-path rule 1).
 *
 * Wanted: after Accept exactly the one quoted answer is paid; the old owed KEX (and its quoted ZBOOT) pays nothing
 * without a question of its own. Every address, key and transaction id is synthetic.
 */
class TunnelOfferAcceptPaysNoOldOwedKexTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `Accept on the Tunnel offer pays only its one quoted answer, never an old owed key exchange`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.OPEN, accountSource = OpenChatTunnelOfferPhone.funded())
            val phone = OpenChatTunnelOfferPhone(world)
            val contactKey = phone.heldKex()
            // A key-change reconnect confirmed before the chat became Open still owes its KEX (and its ZBOOT allowance).
            world.prefs.kexOwed += world.peer
            world.prefs.kexOwedReason[world.peer] = ZchatPreferences.KEX_OWED_REASON_RECONNECT
            world.prefs.zbootQuoted += world.peer
            val vm = phone.screen()
            assertEquals("fixture: the offer is not shown", "OFFERED", phone.stage(vm))

            phone.call(vm, "acceptOpenChatTunnelOffer")
            HandshakeHarness.awaitIdle(vm)
            assertEquals("fixture: Accept did not pay its answer", contactKey, world.prefs.sentKexAck[world.peer])
            assertEquals("fixture: the chat is not Tunnel after Accept", ConversationMode.TUNNEL, phone.modes[world.peer])

            // The next chat open (and every block) pays what is owed.
            vm.retryOwedKeyExchange(world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertNull(
                "an owed KEX the Tunnel offer's question never counted was paid after Accept: ${world.ledger.all()}",
                world.ledger.one("KEX"),
            )
            assertEquals(
                "Accept quoted one on-chain message and more were paid: ${world.ledger.all()}",
                1,
                world.seam.proposals.get(),
            )
        }
}
