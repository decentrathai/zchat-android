package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.RelayHealthApi.HOUR
import co.electriccoin.zcash.ui.nostr.RelayHealthApi.MIN
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.hex
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.readableAcks
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.resendKept
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-393 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.2, §2.3 items 1-3, §2.6): a Retry of a message only a public relay
 * took re-sends the kept wrap, it does not make a second one.
 *
 * Once a send only primal took shows FAILED with Retry, RELAY-HEALTH's outbox still holds that wrap and puts it on ZChat's
 * relay when it is back. At HEAD a Retry builds a new wrap with a new rumor id, so a 2.13 friend can get the message
 * twice. Wanted: `NostrInboxManager.resendKept(rumorId, recipient)` finds the outbox entry by its tag
 * (recipient:rumorId), re-sends the SAME json (same event id) to the connected relays that lack it, and counts from the
 * relays that hold it (OK true or duplicate:); once the outbox flush has put it on every active relay and dropped the
 * live entry, a completed record still answers, with nothing re-sent; null for a wrap no relay took, an
 * expiration-tagged call signal, another recipient, after 24 h, and on a new pool (a restart). Local relays; fake clock.
 */
class KeptWrapIsResentNotRemadeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private val inboxes = mutableListOf<SharedRelayCountApi.Inbox>()
    private val clock = AtomicLong(1_790_000_000_000L)
    private val sender = SharedRelayCountApi.identity()
    private val recipient = hex(SharedRelayCountApi.identity().publicKey)

    @After
    fun tearDown() {
        inboxes.forEach { it.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
    }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    private inner class Fixture(
        own: HealthRelay.Mode,
        primal: HealthRelay.Mode,
        nosLol: HealthRelay.Mode,
    ) {
        val own: HealthRelay
        val primal: HealthRelay
        val nosLol: HealthRelay
        val urls: List<String>
        private val spareUrl: String

        init {
            val (o, urlOwn) = relay(own)
            val (p, urlPrimal) = relay(primal)
            val (n, urlNosLol) = relay(nosLol)
            val (_, urlSpare) = relay()
            this.own = o
            this.primal = p
            this.nosLol = n
            urls = listOf(urlOwn, urlPrimal, urlNosLol)
            spareUrl = urlSpare
        }

        val all: List<HealthRelay> get() = listOf(own, primal, nosLol)

        /** An inbox (a new pool: a fresh process) as the sender. */
        suspend fun start(live: List<HealthRelay>): SharedRelayCountApi.Inbox {
            val pool =
                RelayHealthApi.pool(
                    urls,
                    scope,
                    null,
                    RelayHealthApi.config(listOf(urls[1], urls[2], spareUrl), clock),
                    RelayHealthApi.CountingSetStore().proxy,
                )
            val reqsBefore = live.map { it.inboxReqs().size }
            val inbox = SharedRelayCountApi.Inbox(pool, sender, scope).also { inboxes += it }
            val answered = HealthRelay.awaitTrue { live.indices.all { live[it].inboxReqs().size > reqsBefore[it] } }
            assertTrue(answered, "fixture: the serving relays got our REQ")
            delay(500)
            return inbox
        }

        /** Every event id published on the three relays, repeats included. */
        fun idsEverywhere(): List<String> = all.flatMap { it.publishedIds }
    }

    private suspend fun comeBack(relay: HealthRelay) {
        val before = relay.inboxReqs().size
        relay.mode = HealthRelay.Mode.SERVES
        assertTrue(HealthRelay.awaitTrue(30_000) { relay.inboxReqs().size > before }, "fixture: the relay came back")
    }

    @Test
    fun `a Retry re-sends the kept wrap with its event id, and answers from the completed record once the outbox is done`() =
        runBlocking {
            val f = Fixture(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES, HealthRelay.Mode.WRITE_BLOCKED)
            val inbox = f.start(live = listOf(f.primal, f.nosLol))
            val sent = inbox.manager.send("synthetic zc393 kept", recipient)
            val id = f.primal.publishedIds.single()
            assertEquals(1, readableAcks(sent), "fixture: only primal took it (nos.lol refused the write)")

            // nos.lol takes writes again: the Retry puts the same wrap there, and nowhere else.
            f.nosLol.mode = HealthRelay.Mode.SERVES
            val retried = resendKept(inbox.manager, sent.rumorId, recipient)
            assertNotNull(retried, "the kept wrap was not found for its Retry")
            assertEquals(2, f.nosLol.publishCount(id), "the kept wrap was not re-sent to nos.lol, which lacked it")
            assertEquals(1, f.primal.publishCount(id), "the kept wrap was re-sent to primal, which held it")
            assertEquals(setOf(id), f.idsEverywhere().toSet(), "a Retry made a new wrap while the first was kept")
            assertEquals(1, retried.acks, "nos.lol now holds it")
            assertEquals(2, readableAcks(retried))
            assertEquals(sent.rumorId, retried.rumorId)

            // ZChat's relay is back: the outbox puts the kept wrap there (R6); a tick then finds it on every active relay.
            comeBack(f.own)
            assertTrue(HealthRelay.awaitTrue { f.own.publishCount(id) == 1 }, "the outbox did not put the kept wrap on ZChat's relay")
            RelayHealthApi.tick(inbox.pool)
            RelayHealthApi.tick(inbox.pool)
            val countsBefore = f.all.map { it.publishCount(id) }

            val done = resendKept(inbox.manager, sent.rumorId, recipient)
            assertNotNull(done, "a Retry after the outbox finished its repair found nothing: it would make a second wrap")
            assertEquals(2, done.acks, "ZChat's relay and nos.lol hold it")
            assertEquals(3, readableAcks(done))
            delay(300)
            assertEquals(countsBefore, f.all.map { it.publishCount(id) }, "the completed record re-sent the wrap")
            assertEquals(setOf(id), f.idsEverywhere().toSet())
        }

    @Test
    fun `a Retry while ZChat's relay and nos lol are still down sends nothing new and stays not delivered`() =
        runBlocking {
            val f = Fixture(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES, HealthRelay.Mode.DOWN_502)
            val inbox = f.start(live = listOf(f.primal))
            val sent = inbox.manager.send("synthetic zc393 still down", recipient)
            val id = f.primal.publishedIds.single()

            val retried = resendKept(inbox.manager, sent.rumorId, recipient)

            assertNotNull(retried)
            assertEquals(0, retried.acks)
            assertEquals(1, readableAcks(retried))
            delay(300)
            assertEquals(listOf(id), f.idsEverywhere(), "a Retry sent something while no relay lacking it was reachable")
        }

    @Test
    fun `the kept wrap duplicate answer counts as held`() =
        runBlocking {
            val f = Fixture(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES, HealthRelay.Mode.DOWN_502)
            val inbox = f.start(live = listOf(f.primal))
            val sent = inbox.manager.send("synthetic zc393 duplicate", recipient)
            val id = f.primal.publishedIds.single()
            // ZChat's relay already has the wrap (another path put it there), then comes back.
            f.own.store(HealthRelay.event(id))
            comeBack(f.own)
            assertTrue(HealthRelay.awaitTrue { f.own.publishCount(id) >= 1 }, "fixture: the outbox offered it to ZChat's relay")

            val retried = resendKept(inbox.manager, sent.rumorId, recipient)

            assertNotNull(retried)
            assertEquals(1, retried.acks, "a duplicate: answer from ZChat's relay means it holds the wrap")
        }

    @Test
    fun `nothing is kept for a wrap no relay took, a call signal or another recipient`() =
        runBlocking {
            val f = Fixture(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.WRITE_BLOCKED, HealthRelay.Mode.DOWN_502)
            val inbox = f.start(live = listOf(f.primal))
            val refused = inbox.manager.send("synthetic zc393 nobody took it", recipient)
            assertEquals(0, readableAcks(refused), "fixture: no relay took it")
            assertNull(resendKept(inbox.manager, refused.rumorId, recipient), "a wrap no relay took was kept")

            f.primal.mode = HealthRelay.Mode.SERVES
            val signal = inbox.manager.send("synthetic zc393 call signal", recipient, ttlSeconds = 60L)
            assertEquals(1, readableAcks(signal), "fixture: primal took the call signal")
            assertNull(resendKept(inbox.manager, signal.rumorId, recipient), "an expiration-tagged call signal was kept")

            val text = inbox.manager.send("synthetic zc393 other recipient", recipient)
            assertNotNull(resendKept(inbox.manager, text.rumorId, recipient), "fixture: the text is kept")
            val other = hex(SharedRelayCountApi.identity().publicKey)
            assertNull(resendKept(inbox.manager, text.rumorId, other), "a kept wrap answered for another recipient")
        }

    @Test
    fun `nothing is kept 24 hours after the first publish, nor after a restart`() =
        runBlocking {
            val f = Fixture(HealthRelay.Mode.DOWN_502, HealthRelay.Mode.SERVES, HealthRelay.Mode.DOWN_502)
            val first = f.start(live = listOf(f.primal))
            val old = first.manager.send("synthetic zc393 a day old", recipient)

            clock.addAndGet(24 * HOUR + MIN)
            RelayHealthApi.tick(first.pool)
            assertNull(resendKept(first.manager, old.rumorId, recipient), "a wrap was kept more than 24 h")

            val fresh = first.manager.send("synthetic zc393 before a restart", recipient)
            assertNotNull(resendKept(first.manager, fresh.rumorId, recipient), "fixture: the fresh wrap is kept")

            first.stop()
            val second = f.start(live = listOf(f.primal))
            assertNull(resendKept(second.manager, fresh.rumorId, recipient), "a new pool (a restart) still answered")
        }
}
