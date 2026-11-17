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
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RELAY-HEALTH R8 (fix-all 2.13.0, train T5; SPEC.md §3.10, §6): the debug seams the relay phone checks arm.
 *
 * - RELAY_UNREACHABLE is a bit mask over [slot 0's relay] + the catalog (bit 0 ZChat's relay, bit 1 the catalog's
 *   first relay, …): each named relay cannot be reached (its connects fail, a probe of it fails, an open socket to it
 *   is closed at the next tick). It follows the relay, not the slot, so the relay swapped in is never blocked.
 * - RELAY_HEALTH_CONFIRM_SEC replaces DOWN's 10 minutes (and SUSPECT's 30, the swap spacing and the compat re-probe
 *   interval) by that many seconds, so a phone check swaps within minutes.
 * - The debug action RESET_RELAY_SET puts the default set back (NostrRelayPool.resetRelaySetForDebug()).
 * Debug builds only: DebugFaultSeamsReleaseAbsenceTest pins that a release build cannot arm them.
 */
class RelayUnreachableSeamTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private var pool: NostrRelayPool? = null

    @After
    fun tearDown() {
        FaultSeams.disarmAll()
        runCatching { pool?.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(): Pair<HealthRelay, String> {
        val relay = HealthRelay().also { relays += it }
        return relay to relay.start()
    }

    private fun seam(name: String): FaultSeam = FaultSeam.valueOf(name)

    @Test
    fun `a mask naming a catalog relay makes it unreachable, the policy marks it DOWN and the relay swapped in is never blocked`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay()
            val (c, urlC) = relay()
            val (d, urlD) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val store = RelayHealthApi.CountingSetStore()
            // [slot 0] + catalog = [A, B, C, D]: bit 1 is B.
            FaultSeams.arm(seam("RELAY_UNREACHABLE"), 0b0010L)
            FaultSeams.arm(seam("RELAY_HEALTH_CONFIRM_SEC"), 60L)
            val p =
                RelayHealthApi
                    .pool(listOf(urlA, urlB, urlC), scope, null, RelayHealthApi.config(listOf(urlB, urlC, urlD), clock), store.proxy)
                    .also { pool = it }
            p.start()
            p.subscribe(INBOX_FILTER) { }
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().isNotEmpty() && c.inboxReqs().isNotEmpty() })
            delay(1_500)
            assertEquals(0, b.upgrades.get(), "the unreachable relay was contacted")

            clock.addAndGet(61_000L)
            val before = a.inboxReqs().size to c.inboxReqs().size
            p.redeliverBacklog()
            assertTrue(HealthRelay.awaitTrue { a.inboxReqs().size > before.first && c.inboxReqs().size > before.second })
            delay(300)
            assertEquals("DOWN", RelayHealthApi.healthState(p, urlB), "61 s without an open with the confirm at 60 s")

            RelayHealthApi.tick(p)
            assertEquals(listOf(urlA, urlD, urlC), RelayHealthApi.activeUrls(p))
            assertTrue(HealthRelay.awaitTrue { d.inboxReqs().isNotEmpty() }, "D got our subscription")
            assertEquals(3, p.publish(HealthRelay.event("synthetic-r8-after-swap", 1_790_000_000L)), "D is not blocked")
            assertEquals(0, b.upgrades.get(), "the unreachable relay was contacted")

            // The reset action: the defaults are back.
            FaultSeams.disarmAll()
            RelayHealthApi.resetRelaySet(p)
            assertEquals(listOf(urlA, urlB, urlC), RelayHealthApi.activeUrls(p), "RESET_RELAY_SET did not restore the defaults")
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().isNotEmpty() }, "B is read again after the reset")
        }

    @Test
    fun `a mask naming slot 0's relay closes an open socket at the next tick, and slot 0 is never swapped`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay()
            val (c, urlC) = relay()
            val (_, urlD) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val p =
                RelayHealthApi
                    .pool(
                        listOf(urlA, urlB, urlC),
                        scope,
                        null,
                        RelayHealthApi.config(listOf(urlB, urlC, urlD), clock),
                        RelayHealthApi.CountingSetStore().proxy,
                    ).also { pool = it }
            p.start()
            p.subscribe(INBOX_FILTER) { }
            assertTrue(HealthRelay.awaitTrue { listOf(a, b, c).all { it.inboxReqs().isNotEmpty() } })

            FaultSeams.arm(seam("RELAY_UNREACHABLE"), 0b0001L)
            FaultSeams.arm(seam("RELAY_HEALTH_CONFIRM_SEC"), 60L)
            RelayHealthApi.tick(p)
            assertEquals(2, p.publish(HealthRelay.event("synthetic-r8-a-blocked", 1_790_000_000L)), "only B and C ack")
            clock.addAndGet(5 * 61_000L)
            val before = b.inboxReqs().size to c.inboxReqs().size
            p.redeliverBacklog()
            assertTrue(HealthRelay.awaitTrue { b.inboxReqs().size > before.first && c.inboxReqs().size > before.second })
            delay(300)
            assertEquals("DOWN", RelayHealthApi.healthState(p, urlA))
            RelayHealthApi.tick(p)
            assertEquals(listOf(urlA, urlB, urlC), RelayHealthApi.activeUrls(p), "slot 0 was swapped")
        }

    @Test
    fun `disarmed, nothing changes`() =
        runBlocking {
            val (a, urlA) = relay()
            val (b, urlB) = relay()
            val (c, urlC) = relay()
            val (_, urlD) = relay()
            val clock = AtomicLong(1_790_000_000_000L)
            val p =
                RelayHealthApi
                    .pool(
                        listOf(urlA, urlB, urlC),
                        scope,
                        null,
                        RelayHealthApi.config(listOf(urlB, urlC, urlD), clock),
                        RelayHealthApi.CountingSetStore().proxy,
                    ).also { pool = it }
            p.start()
            p.subscribe(INBOX_FILTER) { }
            assertTrue(HealthRelay.awaitTrue { listOf(a, b, c).all { it.inboxReqs().isNotEmpty() } })
            clock.addAndGet(11 * RelayHealthApi.MIN)
            p.redeliverBacklog()
            delay(500)
            RelayHealthApi.tick(p)
            assertEquals(listOf(urlA, urlB, urlC), RelayHealthApi.activeUrls(p))
            assertEquals(3, p.publish(HealthRelay.event("synthetic-r8-disarmed", 1_790_000_000L)))
        }

    private companion object {
        val INBOX_FILTER = mapOf<String, Any>("kinds" to listOf(1059), "#p" to listOf("f".repeat(64)))
    }
}
