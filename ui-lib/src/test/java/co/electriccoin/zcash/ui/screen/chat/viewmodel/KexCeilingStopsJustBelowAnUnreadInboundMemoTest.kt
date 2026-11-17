package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.KexCeilingPassFixture.Companion.kexAckMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.KexCeilingPassFixture.Companion.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ZC-074 (fix-all 2.13.0, train T5b; high-items SPECS §3.4 N1, §3.5 K3): a memo the pass could not read holds the
 * processed-KEX ceiling only where it could hide a key offer, and only from its own height up.
 *
 * At HEAD any getMemos failure, sent or received, mined or not, makes the pass incomplete for the KEX ceiling, so the
 * ceiling never rises while one transaction keeps failing to read, and every KEX/KEXACK above it waits in the set until
 * the hard cap drops it uncovered (ZC-074). Only an inbound transaction can carry a key offer, and the floor only ever
 * covers a mined height.
 *
 * Wanted: an unread inbound memo mined at Hf holds the ceiling at Hf - 1; an unread own one, or an unmined inbound one,
 * does not hold it (the ceiling rises to the highest mined height the pass read). Every key, txid and address is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexCeilingStopsJustBelowAnUnreadInboundMemoTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()

    private companion object {
        const val UNREAD_HEIGHT = 3_000_120L
        const val ACK_HEIGHT = 3_000_100L
        const val TOP = 3_000_180L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private val acks = mapOf(txId(2) to kexAckMemo(2), txId(3) to kexAckMemo(3))

    @Test
    fun `an unread inbound memo mined at Hf holds the ceiling just below Hf`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            f.twoCompletePasses(
                transactions = listOf(f.incoming(2, ACK_HEIGHT), f.incoming(1, UNREAD_HEIGHT), f.incoming(3, TOP)),
                memos = acks,
                scheduler = main.scheduler,
                unreadable = setOf(txId(1)),
            )

            assertEquals("the ceiling stops just below the inbound memo that could hide a key offer", UNREAD_HEIGHT - 1, f.ceiling())
        }

    @Test
    fun `an unread own memo does not hold the ceiling`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            f.twoCompletePasses(
                transactions = listOf(f.incoming(2, ACK_HEIGHT), f.outgoing(1, UNREAD_HEIGHT), f.incoming(3, TOP)),
                memos = acks,
                scheduler = main.scheduler,
                unreadable = setOf(txId(1)),
            )

            assertEquals("our own message is never an inbound key offer", TOP, f.ceiling())
        }

    @Test
    fun `an unread unmined inbound memo does not hold the ceiling`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            f.twoCompletePasses(
                transactions = listOf(f.incoming(2, ACK_HEIGHT), f.incoming(1, null), f.incoming(3, TOP)),
                memos = acks,
                scheduler = main.scheduler,
                unreadable = setOf(txId(1)),
            )

            assertEquals("the floor never covers a transaction with no mined height", TOP, f.ceiling())
        }
}
