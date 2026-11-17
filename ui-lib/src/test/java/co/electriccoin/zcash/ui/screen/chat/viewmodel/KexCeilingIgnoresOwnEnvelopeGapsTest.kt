package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.KexCeilingPassFixture.Companion.UNPARSEABLE_OWN_ENVELOPE
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-074 (fix-all 2.13.0, train T5b; high-items SPECS §3.1, §3.4 N1, §3.5 K2): only a real unknown may hold the
 * processed-KEX ceiling.
 *
 * The ceiling rises only on a pass the history pass calls complete, and at HEAD that is the restored-peer send gate's
 * completeness (RestoreKexPolicy.isPassHistoryComplete), which also needs every one of our own encrypted messages tied to
 * a chat. One old own message whose chat mapping is gone and whose body does not parse pins the ceiling for good. Our own
 * message can never be an inbound key offer, so it says nothing about a KEX the floor could wrongly cover; while the
 * ceiling stays pinned, every KEX/KEXACK above it stays in the set until the hard cap drops it uncovered (ZC-074).
 *
 * Wanted: after a complete, stable pass the ceiling rises to the highest mined height the pass read, despite the own
 * envelope it could not tie to a chat. Every key, txid and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexCeilingIgnoresOwnEnvelopeGapsTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()

    private companion object {
        const val OWN_HEIGHT = 3_000_100L
        const val ACK_HEIGHT = 3_000_150L
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

    @Test
    fun `an own envelope the pass cannot tie to a chat does not hold the KEX ceiling`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            val txs = listOf(f.outgoing(1, OWN_HEIGHT), f.incoming(2, ACK_HEIGHT), f.incoming(3, ACK_HEIGHT + 10))
            f.twoCompletePasses(
                transactions = txs,
                memos = mapOf(txId(1) to UNPARSEABLE_OWN_ENVELOPE, txId(2) to kexAckMemo(2), txId(3) to kexAckMemo(3)),
                scheduler = main.scheduler,
            )

            assertTrue("fixture: the pass marked the KEXACKs", f.processed(txId(3), ACK_HEIGHT + 10))
            assertEquals(
                "an own message the pass could not tie to a chat pins the KEX ceiling: it can never be a key offer",
                ACK_HEIGHT + 10,
                f.ceiling(),
            )
        }

    /** Control, GREEN before and after: with every message read and tied, the stable pass raises the ceiling. */
    @Test
    fun `control - with no own envelope gap the stable pass raises the ceiling to the top`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            f.twoCompletePasses(
                transactions = listOf(f.incoming(2, ACK_HEIGHT), f.incoming(3, ACK_HEIGHT + 10)),
                memos = mapOf(txId(2) to kexAckMemo(2), txId(3) to kexAckMemo(3)),
                scheduler = main.scheduler,
            )

            assertEquals(ACK_HEIGHT + 10, f.ceiling())
        }
}
