package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.nostr.RelayHealthApi.HOUR
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.hex
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.readableAcks
import co.electriccoin.zcash.ui.nostr.SharedRelayCountApi.resendKept
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-588 (fix-all 2.13.0, train T6b; register T5b-X-29 and X-30; ZC-420-SPEC.md §2.1, §3.7, §4.1): a Retry's answer
 * for a kept wrap counts only the relays that serve our reads now, and an OK the Retry won is never lost.
 *
 * At HEAD `NostrRelayPool.republishKept` (behind `NostrInboxManager.resendKept`) counts the relays that hold the kept
 * wrap by their address alone (`countHeld`): ZChat's relay counts as shared, and every active relay as readable, even
 * after it CLOSED our read with "auth-required:" (read-blocked). So a Retry answers "delivered" for a copy no ZChat phone
 * can read (X-29). And the OKs a Retry wins are recorded only through the outbox by id, which does nothing once a newer
 * wrap pushed the entry out while the Retry waited, so the answer misses the copy it just made (X-30).
 *
 * Wanted: a held relay counts only while it serves us: an active relay whose read block is not set, or a relay that is
 * no longer active (nos.lol swapped out) unless the health rules call it READ_BLOCKED; readable = the active relays that
 * pass, shared = ZChat's relay or nos.lol when they pass; for the live entry and the completed record alike. A Retry
 * adds each readable OK it wins to the entry it holds. Local MockWebServer relays, a fake clock for the health rules,
 * the real NostrInboxManager and Nip17 wraps; keys made per run.
 */
class KeptWrapOnAReadBlockedRelayIsNotConfirmedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = mutableListOf<HealthRelay>()
    private val gated = mutableListOf<GatedOkRelay>()
    private val inboxes = mutableListOf<SharedRelayCountApi.Inbox>()
    private val clock = AtomicLong(1_790_000_000_000L)
    private val sender = SharedRelayCountApi.identity()
    private val recipient = hex(SharedRelayCountApi.identity().publicKey)

    @After
    fun tearDown() {
        inboxes.forEach { it.stop() }
        scope.cancel()
        relays.forEach { it.shutdown() }
        gated.forEach { it.shutdown() }
    }

    private fun relay(mode: HealthRelay.Mode = HealthRelay.Mode.SERVES): Pair<HealthRelay, String> {
        val relay = HealthRelay(mode).also { relays += it }
        return relay to relay.start()
    }

    /** The fixture's config: [catalog], the fake clock, and no first health check while a test runs. */
    private fun config(catalog: List<String>): Any =
        RelayHealthApi.config(catalog, clock).also { RelayHealthApi.setProp(it, "firstCheckDelayMs", HOUR) }

    /** An inbox on a new pool over [urls] (slot order) as the sender; [awaitReqs] are the relays that must get our REQ. */
    private suspend fun start(
        urls: List<String>,
        config: Any,
        store: RelayHealthApi.CountingSetStore,
        awaitReqs: List<() -> Int>,
    ): SharedRelayCountApi.Inbox {
        val pool = RelayHealthApi.pool(urls, scope, null, config, store.proxy)
        val before = awaitReqs.map { it() }
        val inbox = SharedRelayCountApi.Inbox(pool, sender, scope).also { inboxes += it }
        val answered = HealthRelay.awaitTrue { awaitReqs.indices.all { awaitReqs[it]() > before[it] } }
        assertTrue(answered, "fixture: the serving relays got our REQ")
        delay(500)
        return inbox
    }

    private suspend fun comeBack(relay: HealthRelay) {
        val before = relay.inboxReqs().size
        relay.mode = HealthRelay.Mode.SERVES
        assertTrue(HealthRelay.awaitTrue(30_000) { relay.inboxReqs().size > before }, "fixture: the relay came back")
    }

    /** [relay] turns AUTH_GATED: the pool's fresh read (the watchdog's backlog REQs) gets CLOSED auth-required. */
    private suspend fun closeOurRead(
        relay: HealthRelay,
        url: String,
        pool: NostrRelayPool,
    ) {
        relay.mode = HealthRelay.Mode.AUTH_GATED
        pool.redeliverBacklog()
        val blocked = HealthRelay.awaitTrue(30_000) { RelayHealthApi.healthState(pool, url) == "READ_BLOCKED" }
        assertTrue(blocked, "fixture: the relay closed our read (state ${RelayHealthApi.healthState(pool, url)})")
    }

    /** The scenario of cases 1 and 3: the live entry is on PRIMAL and on ZChat's relay; nos.lol is down. */
    private class LiveEntryOnOwn(
        val own: HealthRelay,
        val urlOwn: String,
        val inbox: SharedRelayCountApi.Inbox,
        val sent: NostrInboxManager.SendResult,
    )

    /** [ownReadRetryMs]: how soon a read-blocked ZChat's relay is asked again (RH-X1; the default 5 minutes when null). */
    private suspend fun liveEntryOnOwn(ownReadRetryMs: Long? = null): LiveEntryOnOwn {
        val (own, urlOwn) = relay(HealthRelay.Mode.DOWN_502)
        val (primal, urlPrimal) = relay()
        val (nosLol, urlNosLol) = relay(HealthRelay.Mode.DOWN_502)
        val (_, urlSpare) = relay()
        val config = config(listOf(urlPrimal, urlNosLol, urlSpare))
        ownReadRetryMs?.let { RelayHealthApi.setProp(config, "ownReadRetryMs", it) }
        val inbox =
            start(
                listOf(urlOwn, urlPrimal, urlNosLol),
                config,
                RelayHealthApi.CountingSetStore(),
                listOf({ primal.inboxReqs().size }),
            )
        val sent = inbox.manager.send("synthetic zc588 live entry", recipient)
        val id = primal.publishedIds.single()
        assertEquals(0, sent.acks, "fixture: ZChat's relay and nos.lol are down at the send")
        assertEquals(1, readableAcks(sent), "fixture: PRIMAL took it")

        comeBack(own)
        assertTrue(HealthRelay.awaitTrue { own.publishCount(id) == 1 }, "fixture: the outbox put the wrap on ZChat's relay")
        delay(300)
        val held = resendKept(inbox.manager, sent.rumorId, recipient)
        assertNotNull(held, "fixture: the wrap is kept (nos.lol still lacks it)")
        assertEquals(1, held.acks, "fixture: ZChat's relay holds it and serves our reads")
        assertTrue(nosLol.publishedIds.isEmpty(), "fixture: nos.lol stayed down")
        return LiveEntryOnOwn(own, urlOwn, inbox, sent)
    }

    @Test
    fun `case 1 - a Retry of the live entry does not count ZChat's relay once it closed our read`() =
        runBlocking {
            val s = liveEntryOnOwn()
            closeOurRead(s.own, s.urlOwn, s.inbox.pool)

            val retried = resendKept(s.inbox.manager, s.sent.rumorId, recipient)

            assertNotNull(retried, "the kept wrap was not found")
            assertEquals(
                0,
                retried.acks,
                "ZChat's relay refuses our reads, yet its copy counts as delivered to every ZChat version",
            )
            assertEquals(1, readableAcks(retried), "only PRIMAL serves the copy now")
        }

    @Test
    fun `case 3 control - ZChat's relay that serves our reads again counts again`() =
        runBlocking {
            val s = liveEntryOnOwn(ownReadRetryMs = 1_000L)
            closeOurRead(s.own, s.urlOwn, s.inbox.pool)

            // RH-X1: ZChat's relay is asked again (here every second); an EOSE from it lifts the block.
            val before = s.own.inboxReqs().size
            s.own.mode = HealthRelay.Mode.SERVES
            val served =
                HealthRelay.awaitTrue(30_000) {
                    s.own.inboxReqs().size > before && RelayHealthApi.healthState(s.inbox.pool, s.urlOwn) != "READ_BLOCKED"
                }
            assertTrue(served, "fixture: ZChat's relay serves our reads again")
            delay(300)

            val retried = resendKept(s.inbox.manager, s.sent.rumorId, recipient)

            assertNotNull(retried)
            assertEquals(1, retried.acks, "ZChat's relay holds it and serves our reads again")
            assertEquals(2, readableAcks(retried))
        }

    @Test
    fun `case 2 - the completed record does not count ZChat's relay once it closed our read, and sends nothing`() =
        runBlocking {
            val (own, urlOwn) = relay(HealthRelay.Mode.DOWN_502)
            val (primal, urlPrimal) = relay()
            val (nosLol, urlNosLol) = relay()
            val (fallback, urlFallback) = relay()
            // Slot 2 holds FALLBACK (nos.lol was swapped out earlier, without a read block).
            val store =
                RelayHealthApi.CountingSetStore(
                    RelayHealthApi.relaySetState(urlPrimal, urlFallback, compatOutSinceMs = clock.get()),
                )
            val inbox =
                start(
                    listOf(urlOwn, urlPrimal, urlNosLol),
                    config(listOf(urlPrimal, urlNosLol, urlFallback)),
                    store,
                    listOf({ primal.inboxReqs().size }, { fallback.inboxReqs().size }),
                )
            val activeAtStart = RelayHealthApi.activeUrls(inbox.pool)
            assertEquals(listOf(urlOwn, urlPrimal, urlFallback), activeAtStart, "fixture: slot 2 holds FALLBACK")
            val sent = inbox.manager.send("synthetic zc588 completed record", recipient)
            val id = primal.publishedIds.single()
            assertEquals(0, sent.acks, "fixture: ZChat's relay is down at the send and nos.lol is not active")
            assertEquals(2, readableAcks(sent), "fixture: PRIMAL and FALLBACK took it")

            comeBack(own)
            assertTrue(HealthRelay.awaitTrue { own.publishCount(id) == 1 }, "fixture: the outbox put the wrap on ZChat's relay")
            delay(300)
            RelayHealthApi.tick(inbox.pool)
            RelayHealthApi.tick(inbox.pool)
            val done = resendKept(inbox.manager, sent.rumorId, recipient)
            assertNotNull(done, "fixture: the completed record answers")
            assertEquals(1, done.acks, "fixture: ZChat's relay holds it and serves our reads")
            assertEquals(3, readableAcks(done), "fixture: every active relay holds it")

            closeOurRead(own, urlOwn, inbox.pool)
            val all = listOf(own, primal, nosLol, fallback)
            val countsBefore = all.map { it.publishCount(id) }

            val retried = resendKept(inbox.manager, sent.rumorId, recipient)

            assertNotNull(retried, "the completed record was not found")
            assertEquals(
                0,
                retried.acks,
                "ZChat's relay refuses our reads, yet the completed record counts its copy as delivered",
            )
            assertEquals(2, readableAcks(retried), "only PRIMAL and FALLBACK serve the copy now")
            delay(300)
            assertEquals(countsBefore, all.map { it.publishCount(id) }, "the completed record re-sent the wrap")
        }

    @Test
    fun `case 4 control - a copy on nos lol still counts as shared after nos lol was swapped out without a read block`() =
        runBlocking {
            val (_, urlOwn) = relay(HealthRelay.Mode.DOWN_502)
            val (primal, urlPrimal) = relay()
            val (nosLol, urlNosLol) = relay()
            val (_, urlSpare) = relay()
            val inbox =
                start(
                    listOf(urlOwn, urlPrimal, urlNosLol),
                    config(listOf(urlPrimal, urlNosLol, urlSpare)),
                    RelayHealthApi.CountingSetStore(),
                    listOf({ primal.inboxReqs().size }, { nosLol.inboxReqs().size }),
                )
            val sent = inbox.manager.send("synthetic zc588 swapped out", recipient)
            assertEquals(1, sent.acks, "fixture: nos.lol took it (ZChat's relay is down)")

            // nos.lol refuses our writes (it still serves our reads): three refusals make it WRITE_REFUSED.
            nosLol.mode = HealthRelay.Mode.WRITE_BLOCKED
            repeat(3) { inbox.manager.send("synthetic zc588 refused $it", recipient) }
            val refused = RelayHealthApi.healthState(inbox.pool, urlNosLol)
            assertEquals("WRITE_REFUSED", refused, "fixture: nos.lol refuses our writes, not our reads")
            RelayHealthApi.tick(inbox.pool)
            val activeNow = RelayHealthApi.activeUrls(inbox.pool)
            assertEquals(listOf(urlOwn, urlPrimal, urlSpare), activeNow, "fixture: nos.lol was swapped out")

            val retried = resendKept(inbox.manager, sent.rumorId, recipient)

            assertNotNull(retried)
            assertEquals(1, retried.acks, "nos.lol holds it and never refused our reads: every version still reads it there")
        }

    @Test
    fun `case 6 - a copy on nos lol does not count once nos lol closed our read, active or swapped out`() =
        runBlocking {
            val (_, urlOwn) = relay(HealthRelay.Mode.DOWN_502)
            val (primal, urlPrimal) = relay()
            val (nosLol, urlNosLol) = relay()
            val (_, urlSpare) = relay()
            val inbox =
                start(
                    listOf(urlOwn, urlPrimal, urlNosLol),
                    config(listOf(urlPrimal, urlNosLol, urlSpare)),
                    RelayHealthApi.CountingSetStore(),
                    listOf({ primal.inboxReqs().size }, { nosLol.inboxReqs().size }),
                )
            val sent = inbox.manager.send("synthetic zc588 nos lol read-blocked", recipient)
            assertEquals(1, sent.acks, "fixture: nos.lol took it (ZChat's relay is down)")

            closeOurRead(nosLol, urlNosLol, inbox.pool)
            val active = resendKept(inbox.manager, sent.rumorId, recipient)
            assertNotNull(active)
            assertEquals(0, active.acks, "nos.lol refuses our reads, yet its copy counts as delivered")
            assertEquals(1, readableAcks(active), "only PRIMAL serves the copy now")

            RelayHealthApi.tick(inbox.pool)
            val activeNow = RelayHealthApi.activeUrls(inbox.pool)
            assertEquals(listOf(urlOwn, urlPrimal, urlSpare), activeNow, "fixture: nos.lol was swapped out")
            val swapped = resendKept(inbox.manager, sent.rumorId, recipient)
            assertNotNull(swapped)
            assertEquals(0, swapped.acks, "nos.lol was swapped out for refusing our reads, yet its copy counts as delivered")
        }

    @Test
    fun `case 5 - an OK the Retry won counts even when a newer wrap pushed the entry out meanwhile`() =
        runBlocking {
            val own = GatedOkRelay().also { gated += it }
            val urlOwn = own.start()
            val (primal, urlPrimal) = relay()
            val (_, urlNosLol) = relay(HealthRelay.Mode.DOWN_502)
            val (_, urlSpare) = relay()
            val config = config(listOf(urlPrimal, urlNosLol, urlSpare)).also { RelayHealthApi.setProp(it, "outboxMaxEntries", 1) }
            val inbox =
                start(
                    listOf(urlOwn, urlPrimal, urlNosLol),
                    config,
                    RelayHealthApi.CountingSetStore(),
                    listOf({ primal.inboxReqs().size }, { own.reqs.get() }),
                )
            // Wrap A: ZChat's relay refuses the write, nos.lol is down: only PRIMAL keeps it.
            val a = inbox.manager.send("synthetic zc588 wrap a", recipient)
            val idA = primal.publishedIds.single()
            assertEquals(0, a.acks, "fixture: only PRIMAL took wrap A")
            assertEquals(1, readableAcks(a))

            // ZChat's relay takes writes again, and holds back its OKs until the test lets them go.
            own.refuseWrites = false
            own.holdOks = true
            val retry = async(Dispatchers.IO) { resendKept(inbox.manager, a.rumorId, recipient) }
            val sentAgain = HealthRelay.awaitTrue { own.publishedIds.count { it == idA } == 2 }
            assertTrue(sentAgain, "fixture: the Retry sent A to ZChat's relay")

            // Wrap B is kept before it goes out: with room for one wrap, A leaves the outbox while its Retry waits.
            val b = async(Dispatchers.IO) { inbox.manager.send("synthetic zc588 wrap b", recipient) }
            assertTrue(HealthRelay.awaitTrue { own.publishedIds.size == 3 }, "fixture: wrap B reached ZChat's relay")
            own.release()

            val retried = withTimeout(20_000) { retry.await() }
            withTimeout(20_000) { b.await() }

            assertNotNull(retried, "fixture: A was still kept when its Retry started")
            assertEquals(1, retried.acks, "ZChat's relay took A during the Retry, but the answer lost that copy")
            assertEquals(2, readableAcks(retried))
        }

    /**
     * Case 5's ZChat's relay: every REQ gets an EOSE; an EVENT is refused ("blocked:") while [refuseWrites], its OK
     * held back while [holdOks] until [release] (in arrival order), else answered OK true.
     */
    private class GatedOkRelay {
        @Volatile var refuseWrites: Boolean = true

        @Volatile var holdOks: Boolean = false

        val reqs = AtomicInteger()
        val publishedIds: MutableList<String> = CopyOnWriteArrayList()
        private val held = CopyOnWriteArrayList<Pair<WebSocket, String>>()
        private val sockets = CopyOnWriteArrayList<WebSocket>()
        private val server = MockWebServer()

        private val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    sockets += webSocket
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    when {
                        text.startsWith("[\"REQ\"") -> {
                            if ("\"limit\"" !in text) reqs.incrementAndGet()
                            webSocket.send("[\"EOSE\",\"${subId(text)}\"]")
                        }
                        text.startsWith("[\"EVENT\"") -> {
                            val id = ScriptedRelay.eventId(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
                            publishedIds += id
                            when {
                                refuseWrites -> webSocket.send("[\"OK\",\"$id\",false,\"blocked: synthetic write refusal\"]")
                                holdOks -> held += webSocket to id
                                else -> webSocket.send("[\"OK\",\"$id\",true,\"\"]")
                            }
                        }
                    }
                }
            }

        fun start(): String {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(listener)
                }
            server.start()
            return "ws://${server.hostName}:${server.port}/"
        }

        /** Answers every held EVENT with OK true, in arrival order, and stops holding. */
        fun release() {
            holdOks = false
            val toSend = held.toList()
            held.clear()
            toSend.forEach { (socket, id) -> socket.send("[\"OK\",\"$id\",true,\"\"]") }
        }

        fun shutdown() {
            sockets.forEach { runCatching { it.cancel() } }
            runCatching { server.shutdown() }
        }

        private fun subId(frame: String): String {
            val afterFirst = frame.indexOf(',') + 1
            val start = frame.indexOf('"', afterFirst) + 1
            return frame.substring(start, frame.indexOf('"', start))
        }
    }
}
