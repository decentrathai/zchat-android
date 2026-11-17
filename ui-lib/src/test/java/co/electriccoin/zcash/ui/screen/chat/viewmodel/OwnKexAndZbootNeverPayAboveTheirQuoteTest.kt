package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1235 — ZC-1233's price limit covers answers only. Our own KEX has one only with a kexQuote, and ZBOOT has none, while
 * every text and question about a handshake quotes 17,000 zat (2,000 in outputs + a 15,000 network fee).
 *
 * Wanted: a build above that figure pays nothing on these paths. How the screen then asks is the fix's choice; this pins only
 * that the 22,000 is not spent. A build at 17,000 still pays (controls), so refusing everything cannot pass.
 *
 * The proposals are real ones carrying the fee, at ProposalDataSource; the send path above them is the real one. No wire or
 * stored format is involved. Every address, key and id is synthetic.
 */
class OwnKexAndZbootNeverPayAboveTheirQuoteTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun world(exchangeComplete: Boolean) =
        LedgerWorld(exchangeComplete = exchangeComplete, consent = true, accountSource = OpenChatTunnelOfferPhone.funded())

    private fun zboot(delta: Long) =
        runBlocking {
            val world = world(exchangeComplete = true)
            val vm = world.openVm()
            world.seam.proposalFeeDelta = delta
            world.sendZboot(vm)
            world
        }

    private fun kexOnAChatOpen(delta: Long) =
        runBlocking {
            val world = world(exchangeComplete = false)
            val vm = world.openVm()
            world.seam.proposalFeeDelta = delta
            world.chatOpen(vm)
            world
        }

    private fun owedKex(delta: Long) =
        runBlocking {
            val world = world(exchangeComplete = false)
            world.prefs.kexOwed += world.peer
            world.prefs.kexOwedReason[world.peer] = ZchatPreferences.KEX_OWED_REASON_RESET
            val vm = world.openVm()
            world.seam.proposalFeeDelta = delta
            vm.retryOwedKeyExchange(world.peer)
            HandshakeHarness.awaitIdle(vm)
            world
        }

    private fun assertNothingAboveTheQuote(
        what: String,
        world: LedgerWorld,
    ) {
        assertTrue("fixture: $what was not built", world.seam.proposals.get() >= 1)
        assertEquals("$what paid above the 17,000 zat the screen quotes", emptyList<Long>(), world.seam.paidFigures.toList())
    }

    @Test
    fun `a ZBOOT above the quote pays nothing`() = assertNothingAboveTheQuote("the ZBOOT", zboot(ABOVE_THE_QUOTE))

    @Test
    fun `our KEX on a chat open above the quote pays nothing`() = assertNothingAboveTheQuote("our KEX", kexOnAChatOpen(ABOVE_THE_QUOTE))

    @Test
    fun `an owed KEX above the quote pays nothing`() = assertNothingAboveTheQuote("the owed KEX", owedKex(ABOVE_THE_QUOTE))

    @Test
    fun `control - a ZBOOT at the quote pays it`() = assertEquals(listOf(QUOTE), zboot(0L).seam.paidFigures.toList())

    @Test
    fun `control - our KEX at the quote pays it`() = assertEquals(listOf(QUOTE), kexOnAChatOpen(0L).seam.paidFigures.toList())

    @Test
    fun `control - an owed KEX at the quote pays it`() = assertEquals(listOf(QUOTE), owedKex(0L).seam.paidFigures.toList())

    private companion object {
        const val ABOVE_THE_QUOTE = 5_000L
        const val QUOTE = 17_000L
    }
}
