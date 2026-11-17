package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.OpenChatTunnelOffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ZC-1040 (medium, money; T7aF cross-family review, GPT F1) — Accept then Keep Open while Accept waits for the account
 * lookup: the later tap must win.
 *
 * "Accept and pay" checks the offer, then waits for the selected account (whether it can pay one answer). Keep Open,
 * tapped meanwhile, records kept|<key> and keeps the chat Open. When the lookup returns, Accept overwrites that decision
 * with accepted|<key>, switches the chat to Tunnel and pays the answer the user has just declined.
 *
 * Wanted: Accept reads the choice again after the lookup; a Keep Open made meanwhile wins: nothing is paid, the chat
 * stays Open and the Keep Open record stands. Every address, key and transaction id is synthetic.
 */
class TunnelOfferKeepOpenDuringAcceptLookupWinsTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    /** The funded account, whose selected-account read waits for [gate] once [armed] (the lookup Accept awaits). */
    private class SlowLookup(
        private val funded: AccountDataSource,
    ) : AccountDataSource by funded {
        @Volatile var armed = false
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun getSelectedAccount(): WalletAccount {
            if (armed) {
                entered.complete(Unit)
                gate.await()
            }
            return funded.getSelectedAccount()
        }
    }

    @Test
    fun `Keep Open tapped while Accept waits for the account lookup wins and nothing is paid`() =
        runBlocking {
            val lookup = SlowLookup(OpenChatTunnelOfferPhone.funded())
            val world = LedgerWorld(mode = ConversationMode.OPEN, accountSource = lookup)
            val phone = OpenChatTunnelOfferPhone(world)
            val contactKey = phone.heldKex()
            val vm = phone.screen()
            assertEquals("fixture: the offer is not shown", "OFFERED", phone.stage(vm))

            lookup.armed = true
            phone.call(vm, "acceptOpenChatTunnelOffer")
            withTimeout(HandshakeHarness.TIMEOUT_MS) { lookup.entered.await() }
            lookup.armed = false
            phone.call(vm, "keepChatOpen")
            assertEquals("fixture: Keep Open was not recorded", OpenChatTunnelOffer.keptOpen(contactKey), phone.offer[world.peer])
            lookup.gate.complete(Unit)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("Accept paid an answer after the later Keep Open", 0, world.seam.proposals.get())
            assertEquals("Accept switched the chat to Tunnel after the later Keep Open", ConversationMode.OPEN, phone.modes[world.peer])
            assertEquals(
                "Accept overwrote the later Keep Open",
                OpenChatTunnelOffer.keptOpen(contactKey),
                phone.offer[world.peer],
            )
            assertEquals("the hold that stops any automatic answer is gone", contactKey, phone.hold[world.peer])
        }
}
