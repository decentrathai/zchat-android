package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1251 — every other automatic setup (KEX, KEXACK, ZBOOT) brings a price to sendAutomaticHandshakeMemoOutcome. The
 * answer to a member's key request (AutomaticSetupKind.GROUP_KEY_GRANT, sendGroupControlMemo) brings none, so a build above
 * the figure the consent question quotes is signed and paid, with nobody asked.
 *
 * Wanted: a grant whose build is above [QUOTE] pays nothing and holds: what its stamp wrote is taken back, so the member's
 * next request can still be answered, at the quote. How the screen then asks is the fix's choice. A build at the quote
 * still pays (the control), so refusing everything cannot pass.
 *
 * The proposals are real ones carrying the fee, at ProposalDataSource; the request is the real signed memo, handed to the
 * real processGroupMessage, and the send-attempt ledger is on, as on a phone. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupKeyGrantNeverPaysAboveItsQuoteTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private class Answered(
        val world: KeyGrantWorld,
        val seam: IdsHandshakeSeam,
        val vm: ChatViewModel,
    )

    private suspend fun answered(delta: Long): Answered {
        val world = KeyGrantWorld(scheduler)
        val seam = IdsHandshakeSeam(MA3.A)
        seam.proposalFeeDelta = delta
        val vm = world.chatVm(seam, ledger = true)
        world.chain(vm, MA3.A, tx = 1)
        awaitIdle(vm)
        assertTrue("fixture: the grant was not built", seam.proposals.get() >= 1)
        return Answered(world, seam, vm)
    }

    @Test
    fun `a grant above its quote pays nothing`() =
        runBlocking {
            val a = answered(ABOVE_THE_QUOTE)

            assertEquals("the key grant paid above the $QUOTE zat its question quotes", emptyList<Long>(), a.seam.paidFigures.toList())
            assertEquals("the key grant reached the SDK above its quote", 0, a.seam.submits.get())
        }

    @Test
    fun `a grant above its quote holds - what its stamp wrote is taken back`() =
        runBlocking {
            val a = answered(ABOVE_THE_QUOTE)

            assertEquals("a grant that paid nothing left the paid count", 0, a.world.store.count(MA3.A))
            assertFalse("a grant that paid nothing is recorded as answered", a.world.chainRequestId(1) in a.world.store.granted)
        }

    @Test
    fun `a held grant is paid once at its quote on the member's next request`() =
        runBlocking {
            val a = answered(ABOVE_THE_QUOTE)

            a.seam.proposalFeeDelta = 0L
            a.world.chain(a.vm, MA3.A, tx = 2)
            awaitIdle(a.vm)

            assertEquals(listOf(QUOTE), a.seam.paidFigures.toList())
            assertEquals(1, a.world.store.count(MA3.A))
        }

    @Test
    fun `control - a grant at its quote pays it, and the count says so`() =
        runBlocking {
            val a = answered(0L)

            assertEquals(listOf(QUOTE), a.seam.paidFigures.toList())
            assertEquals(1, a.world.store.count(MA3.A))
            assertTrue(a.world.chainRequestId(1) in a.world.store.granted)
        }

    private companion object {
        const val ABOVE_THE_QUOTE = 5_000L
        const val QUOTE = 17_000L
    }
}
