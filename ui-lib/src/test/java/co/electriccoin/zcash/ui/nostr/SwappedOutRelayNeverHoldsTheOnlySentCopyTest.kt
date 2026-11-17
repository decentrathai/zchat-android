package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.hex
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.resendKept
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-397 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.1, §2.2, §2.6): a message the sender shows as SENT is never held
 * only by a relay the receiver swapped out.
 *
 * A 2.13.0 receiver reads its own three relays; a relay it swapped out is closed at once and, if it is swapped in again
 * later, starts at the seen list's floor + 1 (RH-X2). At HEAD a sender whose ZChat relay and nos.lol are unreachable gets
 * acks 1 from primal alone and shows SENT; a receiver that swapped primal out (or a 2.12.4 receiver, which never reads
 * it) gets nothing, and after the sender's restart the in-memory outbox is gone, so nothing ever repairs it.
 *
 * Wanted (no receiver change; it keeps exactly three relays, W-15): that send is not delivered (acks 0, FAILED + Retry
 * in the chat); after a restart, the Retry's new wrap reaches ZChat's relay and the receiver gets the text exactly once;
 * in the same process the outbox puts the first wrap on ZChat's relay and a Retry re-sends that same wrap, still once.
 * Sender pool S [own, primal, nos.lol]; receiver pool R started from a saved set [own, fallback, nos.lol] (primal swapped
 * out). Local relays; keys made per run.
 */
class SwappedOutRelayNeverHoldsTheOnlySentCopyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private val inboxes = mutableListOf<SharedRelayCountApi.Inbox>()
    private val clock = AtomicLong(1_790_000_000_000L)
    private val senderIdentity = SharedRelayCountApi.identity()
    private val receiverIdentity = SharedRelayCountApi.identity()
    private val receiverKey = hex(receiverIdentity.publicKey)

    @After
    fun tearDown() {
        inboxes.forEach { it.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(mode: HealthRelay.Mode): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    private val own = relay(HealthRelay.Mode.DOWN_502)
    private val primal = relay(HealthRelay.Mode.SERVES)
    private val nosLol = relay(HealthRelay.Mode.DOWN_502)
    private val fallback = relay(HealthRelay.Mode.SERVES)
    private val defaults = listOf(own.second, primal.second, nosLol.second)
    private val catalog = listOf(primal.second, nosLol.second, fallback.second)

    private suspend fun start(
        identity: NOSTRIdentity,
        saved: Any?,
        live: List<HealthRelay>,
    ): SharedRelayCountApi.Inbox {
        // A scope of its own per phone: NostrRelayPool.stop() cancels its scope's children (a sender's restart must not
        // stop the receiver's connect loops or collector).
        val phoneScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        val pool =
            RelayHealthApi.pool(
                defaults,
                phoneScope,
                null,
                RelayHealthApi.config(catalog, clock),
                RelayHealthApi.CountingSetStore(saved).proxy,
            )
        val before = live.map { it.inboxReqs().size }
        val inbox = SharedRelayCountApi.Inbox(pool, identity, phoneScope).also { inboxes += it }
        assertTrue(HealthRelay.awaitTrue { live.indices.all { live[it].inboxReqs().size > before[it] } }, "fixture: REQs answered")
        delay(500)
        return inbox
    }

    private suspend fun sender(): SharedRelayCountApi.Inbox = start(senderIdentity, null, listOf(primal.first))

    private suspend fun receiver(): SharedRelayCountApi.Inbox {
        // Primal was swapped out of slot 1 for the fallback earlier: the receiver reads ZChat's relay, the fallback, nos.lol.
        val r = start(receiverIdentity, RelayHealthApi.relaySetState(fallback.second, nosLol.second), listOf(fallback.first))
        assertEquals(listOf(own.second, fallback.second, nosLol.second), RelayHealthApi.activeUrls(r.pool), "fixture: R's set")
        return r
    }

    /**
     * Whether R has [text], asking its live relays again while it waits: HealthRelay replays what it stores on a REQ and
     * does not push a new EVENT to an open subscription, so the inbox watchdog's re-read (resubscribeLive) stands in for
     * a relay's live delivery here.
     */
    private suspend fun receives(
        r: SharedRelayCountApi.Inbox,
        text: String,
    ): Boolean {
        var askedAt = 0L
        return HealthRelay.awaitTrue(15_000) {
            val now = System.currentTimeMillis()
            if (r.count(text) == 0 && now - askedAt >= 1_000L) {
                askedAt = now
                r.pool.resubscribeLive()
            }
            r.count(text) >= 1
        }
    }

    /** ZChat's relay serves again; waits until both pools have read there. */
    private suspend fun ownComesBack(reqsAtLeast: Int) {
        own.first.mode = HealthRelay.Mode.SERVES
        assertTrue(HealthRelay.awaitTrue(45_000) { own.first.inboxReqs().size >= reqsAtLeast }, "fixture: ZChat's relay is back")
        delay(500)
    }

    @Test
    fun `a send only the swapped-out relay took is not delivered, and the Retry after a restart arrives once`() =
        runBlocking {
            val r = receiver()
            val s = sender()
            val text = "synthetic zc397 only primal took it"

            val first = s.manager.send(text, receiverKey)
            assertEquals(1, primal.first.publishedIds.size, "fixture: primal took the wrap")
            assertEquals(0, first.acks, "SENT while only a relay the receiver swapped out held the message")
            delay(1_500)
            assertEquals(0, r.count(text), "fixture: R does not read primal")

            // The sender restarts (its outbox is gone), ZChat's relay is back, and the user taps Retry: a new wrap.
            s.stop()
            ownComesBack(reqsAtLeast = 1)
            val s2 = start(senderIdentity, null, listOf(primal.first, own.first))
            assertTrue(HealthRelay.awaitTrue(45_000) { own.first.inboxReqs().size >= 2 }, "fixture: R and S both read ZChat's relay")
            val retry = s2.manager.send(text, receiverKey)

            assertTrue(retry.acks >= 1, "the Retry reached ZChat's relay")
            assertTrue(receives(r, text), "R never got the message")
            delay(1_500)
            assertEquals(1, r.count(text), "R got the message more than once")
        }

    @Test
    fun `in the same process the outbox repairs it and a Retry re-sends the same wrap, still once`() =
        runBlocking {
            val r = receiver()
            val s = sender()
            val text = "synthetic zc397 repaired in process"

            val first = s.manager.send(text, receiverKey)
            val id = primal.first.publishedIds.single()
            assertEquals(0, first.acks, "SENT while only a relay the receiver swapped out held the message")

            ownComesBack(reqsAtLeast = 2)
            assertTrue(HealthRelay.awaitTrue(15_000) { own.first.publishCount(id) == 1 }, "the outbox did not repair it")
            assertTrue(receives(r, text), "R never got the repaired message")

            val retried = resendKept(s.manager, first.rumorId, receiverKey)
            assertNotNull(retried, "the Retry found no kept wrap and would make a second one")
            assertTrue(retried.acks >= 1)
            delay(1_500)
            val everywhere = listOf(own.first, primal.first, nosLol.first, fallback.first).flatMap { it.publishedIds }.toSet()
            assertEquals(setOf(id), everywhere, "a second wrap went out")
            assertEquals(1, r.count(text), "R got the message more than once")
        }
}
