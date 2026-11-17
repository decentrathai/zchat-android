package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T1b-2 (train T1b, INVESTIGATION.md §3/6, phone checks): the phone checks (a) and (b) need one relay down on demand
 * (nos.lol at 502 in the T1 run), so the only readable copy is on our own relay. F0's BLOCK_RELAYS blocks all of them.
 *
 * Wanted: a debug-only seam BLOCK_ONE_RELAY whose value is the relay's position in the pool's list (0 = the first):
 * while armed, that relay is treated as down — nothing we publish there counts as an ack and nothing it sends is
 * delivered. The other relays work as before. Like every seam it reads "not armed" in a release build
 * (DebugFaultSeamsReleaseAbsenceTest).
 */
class OneRelayBlockSeamTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val first = ScriptedRelay(ScriptedRelay.Mode.OWN_RELAY)
    private val second = ScriptedRelay(ScriptedRelay.Mode.OWN_RELAY)
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        FaultSeams.disarmAll()
        runCatching { pool?.stop() }
        scope.cancel()
        first.shutdown()
        second.shutdown()
    }

    private fun seam(): FaultSeam =
        FaultSeam.entries.firstOrNull { it.name == "BLOCK_ONE_RELAY" }
            ?: throw AssertionError("FaultSeam.BLOCK_ONE_RELAY is not in this build: the phone checks can't take one relay down")

    @Test
    fun `with the second relay blocked, only the first acks and delivers`() =
        runBlocking {
            second.store(ScriptedRelay.event("synthetic-only-on-the-blocked-relay"))
            first.store(ScriptedRelay.event("synthetic-on-the-open-relay"))
            FaultSeams.arm(seam(), 1)
            val received = CopyOnWriteArrayList<String>()
            val p = NostrRelayPool(listOf(first.start(), second.start()), scope).also { pool = it }
            p.start()
            p.subscribe(mapOf("kinds" to listOf(1059))) { json -> received += ScriptedRelay.eventId(json) }
            assertTrue("fixture sanity: the open relay delivered", ScriptedRelay.awaitTrue { "synthetic-on-the-open-relay" in received })
            delay(500)

            assertFalse("the blocked relay delivered an event", "synthetic-only-on-the-blocked-relay" in received)
            assertEquals("the blocked relay's ack was counted", 1, p.publish(ScriptedRelay.event("synthetic-sent")))
        }

    @Test
    fun `control - with nothing armed both relays ack`() =
        runBlocking {
            val p = NostrRelayPool(listOf(first.start(), second.start()), scope).also { pool = it }
            p.start()
            p.subscribe(mapOf("kinds" to listOf(1059))) { }
            assertTrue(ScriptedRelay.awaitTrue { first.reqIds.isNotEmpty() && second.reqIds.isNotEmpty() })

            assertEquals(2, p.publish(ScriptedRelay.event("synthetic-sent-control")))
        }
}
