package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.NostrSeenLedger
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.service.ChatAlert
import co.electriccoin.zcash.ui.service.RelayMessageNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * KI24-D-ALT (W-12 part A; KI-24 spec §2.5 (d)): the 50-hour offline receiver, as a JVM test. A GUARD: it passes at the
 * KI-24 code with the KI24-FX2 fixups and must stay green (RELAY-HEALTH changes the pool and the seen list's floor set).
 *
 * A receiver that was online (each relay's `since` stored), then closed for more than 50 hours, then started again,
 * gets every relay message sent in the gap exactly once, including wraps back-dated by up to 48 h (NIP-59), with the
 * KI-24 floors in play: the per-relay `since` and the client's drop below it, NostrSeenLedger's prune and cap floor
 * (G-KI24-1), the bound of handed-over but unhandled wraps (G-KI24-4). Nothing older comes back, as a message or as an
 * alert, at that start or after one more restart.
 *
 * Composition as SyncForegroundService builds it: the real ZchatPreferencesImpl over in-memory files
 * ([realPreferences]); RelaySinceLine with the bridge's settled and unhandled gates; NostrRelayPool with that line as
 * its since store (a thin wrapper only counts the EOSEs it passes on); NostrInboxManager; the collector that calls
 * NostrChatBridge.dispatch and onDispatched; RelayMessageNotifier through registerChatNotifier; a reader that marks
 * each event seen as ChatViewModel.observeNostrInbound does. Each relay's `since` is kept under the key of the
 * DEFAULT_RELAYS entry at the same position, so the seen list's lowest since is read over the same keys as on a phone.
 *
 * Clock: the preferences and the pool read the wall clock, so the scenario sits in the past: T0 = now − 51 h (the
 * receiver goes offline), the reconnect is now. Only the line gets a clock of its own (T0 in session 1, now after), as
 * its constructor allows. Keys, ids, texts and addresses are made at run time; the relays are local MockWebServers.
 */
class OfflineFiftyHoursEveryMessageArrivesOnceTest {
    private companion object {
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val MARGIN = RelaySinceLine.MARGIN_SEC
        const val SKEW_SEC = 59 * 60L
        const val PEER = "usyntheticoffline50hpeer01"
        const val OLD_IDS = 300
        const val SMALL_CAP = 64L
        const val LATE_READER_MS = 2_000L
        const val LATCH_MS = 5_000L
        const val QUIET_MS = 100L
        const val NOTHING_WINDOW_MS = 5_000L
        const val AWAIT_MS = 15_000L
        const val FAR_FUTURE = Long.MAX_VALUE / 4
    }

    private enum class Variant { BASE, FLOORS_ACTIVE, AUTH_GATED, SKEW, ONE_DEAD, READER_CANCELLED, SMALL_CAP }

    /** A gift-wrap made here, with what the test checks it by. */
    private class Wrapped(
        val json: String,
        val eventId: String,
        val rumorId: String,
        val wrapAtSec: Long,
    )

    private val random = SecureRandom()
    private val tag = "synthetic-offline-${UUID.randomUUID()}"
    private val wallStart = nowSec()
    private val t0 = wallStart - 51 * HOUR
    private val lineClock = AtomicLong(t0)
    private val prefs: ZchatPreferencesImpl = realPreferences().first
    private val receiver = identity()
    private val sender = identity()
    private val stranger = identity()
    private val relays = CopyOnWriteArrayList<SinceAwareRelay>()
    private val scripted = CopyOnWriteArrayList<ScriptedRelay>()
    private val sessions = CopyOnWriteArrayList<Session>()
    private val violations = CopyOnWriteArrayList<String>()
    private val answeringSession = ThreadLocal<Session?>()
    private val latchTimeouts = AtomicInteger()

    @Volatile private var current: Session? = null

    @Volatile private var window: List<Wrapped> = emptyList()

    @Volatile private var urlToKey: Map<String, String> = emptyMap()

    @Before
    fun drainTheSingletonBridge() =
        runBlocking {
            // Whatever another class left in NostrChatBridge (held messages, a redelivery flag, handed-over entries).
            val drain =
                CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                    NostrChatBridge.inbound.collect { }
                }
            ScriptedRelay.awaitTrue(3_000) { NostrChatBridge.consumePendingRedelivery() || NostrChatBridge.isRelayBacklogSettled() }
            ScriptedRelay.awaitTrue(3_000) { NostrChatBridge.isRelayBacklogSettled() }
            drain.cancelAndJoin()
            NostrChatBridge.lowestUnhandledWrapSec(FAR_FUTURE)
            assertTrue("fixture: the bridge starts with nothing waiting", NostrChatBridge.isRelayBacklogSettled())
            assertNull("fixture: no handed-over message is left", NostrChatBridge.lowestUnhandledWrapSec(wallStart))
        }

    @After
    fun tearDown() {
        // Diagnostics only (test output): how many EOSEs went out after the latch's bound rather than on a settled state.
        println("KI24-D-ALT: latch timeouts ${latchTimeouts.get()}")
        sessions.forEach { runCatching { it.stop() } }
        current = null
        NostrChatBridge.unregisterChatNotifier()
        relays.forEach { it.shutdown() }
        scripted.forEach { it.shutdown() }
    }

    @Test
    fun `the wrap helper builds what Nip17 send builds, with the chosen wrap time`() {
        val rumorAt = wallStart - 5 * HOUR
        val wrapAt = rumorAt - 30 * HOUR
        val made = wrapAt(sender, receiver.publicKey, "synthetic sanity text", rumorAt, wrapAt)
        val got = Nip17.receive(made.json, receiver.privateKey)
        val sent = Nip17.send(sender.privateKey, sender.publicKey, receiver.publicKey, "synthetic sanity text", createdAtSec = rumorAt)

        assertEquals("synthetic sanity text", got.content)
        assertTrue(sender.publicKey.contentEquals(got.senderPubkey))
        assertEquals(rumorAt, got.createdAtSec)
        assertEquals(wrapAt, got.wrapCreatedAtSec)
        assertEquals(made.eventId, got.eventId)
        assertEquals("the rumor id is the one Nip17.send gives", sent.rumorId, made.rumorId)
        assertEquals(sent.rumorId, got.rumorId)
        assertEquals(Nip17.receive(sent.giftWrapJson, receiver.privateKey).rumorTags, got.rumorTags)
    }

    @Test
    fun `after 50 hours offline every message sent meanwhile arrives once and nothing older comes back`() = scenario(Variant.BASE)

    @Test
    fun `V1 - with the seen list pruned below the since lines every message still arrives once`() = scenario(Variant.FLOORS_ACTIVE)

    @Test
    fun `V2 - with an auth-gated relay the floors stay inert and every message arrives once`() = scenario(Variant.AUTH_GATED)

    @Test
    fun `V3 - with the since line's clock 59 minutes ahead every message arrives once`() = scenario(Variant.SKEW)

    @Test
    fun `V4 - with one relay down all along every message arrives once`() = scenario(Variant.ONE_DEAD)

    @Test
    fun `V5 - a message a closed screen never read holds the since line and arrives once after the restart`() =
        scenario(Variant.READER_CANCELLED)

    @Test
    fun `V6 - with a small seen-list cap every message arrives once and the floor stays below the since lines`() =
        scenario(Variant.SMALL_CAP)

    private fun scenario(v: Variant) =
        runBlocking {
            if (v == Variant.SMALL_CAP) swapSeenLedger(NostrSeenLedger { SMALL_CAP })
            prefs.reloadWalletScopedStores()
            prefs.setPeerNostrPubkey(PEER, sender.publicKey.hex())
            prefs.setConversationMode(PEER, ConversationMode.OPEN)
            val skew = if (v == Variant.SKEW) SKEW_SEC else 0L

            val a = sinceAwareRelay(SinceAwareRelay.Mode.HONOURS_SINCE)
            val b = if (v == Variant.AUTH_GATED) null else sinceAwareRelay(SinceAwareRelay.Mode.IGNORES_SINCE)
            val c = if (v == Variant.ONE_DEAD) null else sinceAwareRelay(SinceAwareRelay.Mode.HONOURS_SINCE)
            val aUrl = a.start()
            val bUrl = b?.start() ?: ScriptedRelay(ScriptedRelay.Mode.AUTH_GATED).also { scripted += it }.start()
            val cUrl = c?.start() ?: ScriptedRelay.DOWN_RELAY
            val urls = listOf(aUrl, bUrl, cUrl)
            urlToKey = urls.zip(NostrRelayPool.DEFAULT_RELAYS).toMap()
            // The relays that answer a REQ with an EOSE (the auth-gated one CLOSEs it, the dead one never opens).
            val eoseUrls = listOfNotNull(aUrl, bUrl.takeIf { b != null }, cUrl.takeIf { c != null })
            val silentUrls = urls - eoseUrls.toSet()
            val holders = listOfNotNull(a, b, c)

            // Before T0: three messages, wraps 0, 24 and 48 h back, on every relay.
            val old =
                listOf(3L, 2L, 1L).mapIndexed { i, hoursBefore ->
                    val rumorAt = t0 - hoursBefore * HOUR
                    wrapAt(sender, receiver.publicKey, "synthetic before the gap ${i + 1}", rumorAt, rumorAt - i * DAY)
                }
            old.forEach { w -> holders.forEach { it.store(w.json) } }

            // Session 1 at T0: the receiver reads everything and each live relay reaches two settled EOSEs.
            lineClock.set(t0 + skew)
            val s1 = Session(urls)
            s1.startReader()
            s1.start()
            assertTrue("session 1: the messages before the gap arrive", awaitTrue { old.all { it.rumorId in s1.delivered } })
            assertTrue("session 1: every live relay answered", awaitEoses(s1, eoseUrls, 1))
            delay(QUIET_MS)
            val bound1 = NostrChatBridge.lowestUnhandledWrapSec(lineClock.get())
            redeliverTwice(s1, eoseUrls)
            old.forEach { w -> assertEquals("session 1 delivers each once", 1, s1.delivered.count { it == w.rumorId }) }
            val expected1 = minOf(t0 + skew - MARGIN, bound1 ?: Long.MAX_VALUE)
            eoseUrls.forEach { url ->
                assertEquals("session 1: the since of relay ${urls.indexOf(url)} is T0 − 49 h", expected1, sinceOf(url))
            }
            silentUrls.forEach { url -> assertNull("a relay that never ended its stored events has no since", sinceOf(url)) }
            s1.stop()

            if (v == Variant.FLOORS_ACTIVE || v == Variant.SMALL_CAP || v == Variant.AUTH_GATED) markOldHandledIds(v)

            // The gap: five messages, back-dated as far as NIP-59 allows, one only on the relay that ignores since
            // (on another one while it is auth-gated), one only on another relay; and two wraps that are not ours.
            val m1 = wrapAt(sender, receiver.publicKey, "synthetic gap m1", t0 + HOUR, t0 + HOUR - 48 * HOUR)
            val m2 = wrapAt(sender, receiver.publicKey, "synthetic gap m2", t0 + HOUR, t0 + HOUR)
            val m3 = wrapAt(sender, receiver.publicKey, "synthetic gap m3", t0 + DAY, t0 + DAY - 23 * HOUR)
            val m4 = wrapAt(sender, receiver.publicKey, "synthetic gap m4", t0 + 49 * HOUR, t0 + 49 * HOUR - 48 * HOUR)
            val m5 = wrapAt(sender, receiver.publicKey, "synthetic gap m5", t0 + 49 * HOUR, t0 + 49 * HOUR)
            val gap = listOf(m1, m2, m3, m4, m5)
            window = gap
            listOf(m1, m4, m5).forEach { w -> holders.forEach { it.store(w.json) } }
            (b ?: a).store(m2.json)
            (c ?: a).store(m3.json)
            val forAnotherKey = wrapAt(sender, stranger.publicKey, "synthetic not ours", t0 + 2 * HOUR, t0 + 2 * HOUR)
            val unreadable =
                wrapAt(sender, receiver.publicKey, "synthetic unreadable", t0 + 2 * HOUR, t0 + 2 * HOUR, encryptTo = stranger.publicKey)
            holders.forEach { r -> listOf(forAnotherKey, unreadable).forEach { r.store(it.json) } }

            // Session 2 at T0 + 51 h: a new process; the chat screen starts reading 2 s after the inbox (held path).
            prefs.reloadWalletScopedStores()
            lineClock.set(wallStart + skew)
            val s2 = Session(urls)
            s2.start()
            delay(LATE_READER_MS)
            if (v == Variant.READER_CANCELLED) {
                readerClosedAtM1(s2, gap, m1, eoseUrls, urls, old)
                return@runBlocking
            }
            s2.startReader()
            assertTrue("session 2: every message of the gap reaches the reader", awaitTrue { gap.all { it.rumorId in s2.delivered } })
            delay(QUIET_MS)
            assertTrue("fixture: the held path asked for a relay replay", NostrChatBridge.consumePendingRedelivery())
            assertTrue("session 2: every live relay answered", awaitEoses(s2, eoseUrls, 1))
            delay(QUIET_MS)
            val bound2 = NostrChatBridge.lowestUnhandledWrapSec(lineClock.get())
            redeliverTwice(s2, eoseUrls)
            gap.forEachIndexed { i, w ->
                assertEquals("session 2: m${i + 1} reaches the reader exactly once", 1, s2.delivered.count { it == w.rumorId })
            }
            assertTrue("session 2: no message from before the gap comes back", old.none { it.rumorId in s2.delivered })
            assertEquals("session 2: one alert per message of the gap", 5, s2.alerts.size)
            assertEquals(gap.map { "nostr:${it.eventId}" }.toSet(), s2.alerts.map { it.key }.toSet())
            assertSinceMovedToNow(eoseUrls, silentUrls, bound2)
            assertTrue("the since line passed an unmarked wrap: $violations", violations.isEmpty())
            s2.stop()

            // Session 3: one more restart at now: nothing is delivered and nothing alerts.
            restartExpectingNothing(urls, eoseUrls, "session 3")
            assertTrue("the since line passed an unmarked wrap: $violations", violations.isEmpty())
        }

    /** V5, from session 2 on: the first reader is cancelled inside its handler when m1 arrives (a screen closed). */
    private suspend fun readerClosedAtM1(
        s2: Session,
        gap: List<Wrapped>,
        m1: Wrapped,
        eoseUrls: List<String>,
        urls: List<String>,
        old: List<Wrapped>,
    ) {
        val first = s2.startReader(cancelAtRumor = m1.rumorId)
        assertTrue("fixture: the first reader was closed at m1", awaitTrue { first.isCompleted })
        assertTrue("session 2: every message of the gap was dispatched", awaitTrue { gap.all { it.eventId in s2.dispatched } })
        s2.startReader()
        delay(QUIET_MS * 3)
        assertTrue("fixture: the held path asked for a relay replay", NostrChatBridge.consumePendingRedelivery())
        assertTrue("session 2: every live relay answered", awaitEoses(s2, eoseUrls, 1))
        assertFalse("fixture: m1 was never marked seen", prefs.hasSeenNostrEvent(m1.eventId))
        val bound = NostrChatBridge.lowestUnhandledWrapSec(lineClock.get())
        assertTrue("m1 counts as handed over and unhandled", bound != null && bound <= m1.wrapAtSec)
        // Two settled EOSEs on every live relay, driven on the line directly while m1 is unmarked.
        repeat(2) { eoseUrls.forEach { s2.line.onEose(it, nowSec()) } }
        eoseUrls.forEach { url ->
            val since = sinceOf(url)
            assertTrue("the since ($since) passed m1's wrap ${m1.wrapAtSec} while m1 is unmarked", since != null && since <= m1.wrapAtSec)
            assertEquals("the since stands at the unhandled bound", minOf(lineClock.get() - MARGIN, bound!!), since)
        }
        assertEquals("session 2: one alert per message of the gap", 5, s2.alerts.size)
        val unmarked = gap.filter { !prefs.hasSeenNostrEvent(it.eventId) }
        assertTrue(m1 in unmarked)
        s2.stop()

        // Session 3: the restart brings back exactly what no reader marked; it alerts again only for those.
        prefs.reloadWalletScopedStores()
        val s3 = Session(urls)
        s3.startReader()
        s3.start()
        assertTrue("session 3: every unmarked message comes back", awaitTrue { unmarked.all { it.rumorId in s3.delivered } })
        assertTrue("session 3: every live relay answered", awaitEoses(s3, eoseUrls, 1))
        delay(QUIET_MS)
        val bound3 = NostrChatBridge.lowestUnhandledWrapSec(lineClock.get())
        redeliverTwice(s3, eoseUrls)
        gap.forEachIndexed { i, w ->
            val times = s2.delivered.count { it == w.rumorId } + s3.delivered.count { it == w.rumorId }
            assertEquals("m${i + 1} reaches a reader exactly once over sessions 2 and 3", 1, times)
        }
        assertTrue("no message from before the gap comes back", old.none { it.rumorId in s2.delivered || it.rumorId in s3.delivered })
        assertEquals(
            "session 3 alerts only for the messages no reader marked (the user never saw them)",
            unmarked.map { "nostr:${it.eventId}" }.toSet(),
            s3.alerts.map { it.key }.toSet(),
        )
        assertEquals(unmarked.size, s3.alerts.size)
        assertSinceMovedToNow(eoseUrls, urls - eoseUrls.toSet(), bound3)
        eoseUrls.forEach { url -> assertTrue("the since moved past m1 once it was marked", sinceOf(url)!! > m1.wrapAtSec) }
        s3.stop()

        restartExpectingNothing(urls, eoseUrls, "session 4")
        assertTrue("the since line passed an unmarked wrap: $violations", violations.isEmpty())
    }

    /** V1, V6 (and V2, where they must stay inert): 300 old handled ids marked after session 1's since was stored. */
    private fun markOldHandledIds(v: Variant) {
        val oldWrap = t0 - 10 * DAY
        val ids = (1..OLD_IDS).map { "$tag-old-handled-$it" }
        ids.forEach { prefs.markNostrEventSeen(it, oldWrap) }
        val ledger = seenLedger()
        if (v == Variant.AUTH_GATED) {
            assertNull("a relay without a since leaves no lowest since", lowestNostrRelaySince())
            assertEquals("the floor never moves while a relay has no since", 0L, ledger.floorSec)
        } else {
            val lowestSince = lowestNostrRelaySince()
            assertTrue("fixture: every relay has a since", lowestSince != null)
            assertTrue(
                "a prune ran (the list grew by ${NostrSeenLedger.PRUNE_STEP}) and dropped old ids",
                ids.any { !prefs.hasSeenNostrEvent(it) },
            )
            assertEquals("the floor rose to the old wrap time", oldWrap, ledger.floorSec)
            assertTrue("the floor stays below the lowest since (G-KI24-1)", ledger.floorSec <= lowestSince!! - 1)
            assertTrue("the floor stays below every wrap of the gap", ledger.floorSec < t0 - 47 * HOUR)
            if (v == Variant.SMALL_CAP) assertTrue("the small cap holds (evictions happened)", ledger.size <= SMALL_CAP)
        }
        ids.forEach { assertTrue("an old handled id still counts as seen", prefs.hasSeenNostrEvent(it, oldWrap)) }
    }

    /** A restart at now: within [NOTHING_WINDOW_MS] of every live relay's EOSE nothing is delivered and nothing alerts. */
    private suspend fun restartExpectingNothing(
        urls: List<String>,
        eoseUrls: List<String>,
        name: String,
    ) {
        prefs.reloadWalletScopedStores()
        val s = Session(urls)
        s.startReader()
        s.start()
        assertTrue("$name: every live relay answered", awaitEoses(s, eoseUrls, 1))
        delay(NOTHING_WINDOW_MS)
        assertTrue("$name: nothing comes back after a restart, got ${s.delivered.size}", s.delivered.isEmpty())
        assertTrue("$name: no alert after a restart, got ${s.alerts.size}", s.alerts.isEmpty())
        s.stop()
    }

    /** After the settled EOSEs at now: every live relay's since = min(now − 49 h, unhandled bound); the others none. */
    private fun assertSinceMovedToNow(
        eoseUrls: List<String>,
        silentUrls: List<String>,
        bound: Long?,
    ) {
        // The line takes min(EOSE wall time, its clock): with its clock ahead (V3) the wall time of the EOSE counts.
        val cap = bound ?: Long.MAX_VALUE
        val lowest = minOf(minOf(lineClock.get(), wallStart) - MARGIN, cap)
        val highest = minOf(minOf(lineClock.get(), nowSec()) - MARGIN, cap)
        eoseUrls.forEach { url ->
            val since = sinceOf(url)
            assertTrue("the since of relay $url is $since, not in $lowest..$highest", since != null && since in lowest..highest)
        }
        silentUrls.forEach { url -> assertNull("a relay that never ended its stored events has no since", sinceOf(url)) }
    }

    private suspend fun redeliverTwice(
        s: Session,
        eoseUrls: List<String>,
    ) {
        repeat(2) {
            val before = eoseUrls.associateWith { s.eoseCount(it) }
            s.pool.redeliverBacklog()
            val replayed = awaitTrue { eoseUrls.all { s.eoseCount(it) > before.getValue(it) } }
            assertTrue("a replay reached its EOSE on every live relay", replayed)
        }
    }

    private suspend fun awaitEoses(
        s: Session,
        eoseUrls: List<String>,
        atLeast: Int,
    ): Boolean = awaitTrue { eoseUrls.all { s.eoseCount(it) >= atLeast } }

    private suspend fun awaitTrue(condition: () -> Boolean): Boolean = ScriptedRelay.awaitTrue(AWAIT_MS, condition)

    /** One process: pool, inbox, collector, notifier and readers, wired as SyncForegroundService wires them. */
    private inner class Session(
        relayUrls: List<String>,
    ) {
        private val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val eoses = ConcurrentHashMap<String, AtomicInteger>()
        val dispatched: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val markers: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val answering = AtomicInteger()
        val delivered: MutableList<String> = CopyOnWriteArrayList()
        val alerts: MutableList<ChatAlert> = CopyOnWriteArrayList()
        private val notifier = RelayMessageNotifier(prefs, { false }, { alerts += it })

        val line: RelaySinceLine =
            RelaySinceLine(
                persistence = sincePersistence,
                isBacklogSettled = { NostrChatBridge.isRelayBacklogSettled() && inbox.isDeliverySettled() },
                nowSec = { lineClock.get() },
                lowestUnhandledWrapSec = { now -> NostrChatBridge.lowestUnhandledWrapSec(now) },
            )

        val pool: NostrRelayPool =
            NostrRelayPool(
                relayUrls,
                poolScope,
                sinceStore =
                    object : RelaySinceStore {
                        override fun sinceFor(relayUrl: String): Long? = line.sinceFor(relayUrl)

                        override fun onEose(
                            relayUrl: String,
                            eoseWallSec: Long,
                        ) {
                            line.onEose(relayUrl, eoseWallSec)
                            eoses.getOrPut(relayUrl) { AtomicInteger() }.incrementAndGet()
                        }
                    },
            )

        val inbox: NostrInboxManager = NostrInboxManager(pool)

        init {
            sessions += this
        }

        fun eoseCount(url: String): Int = eoses[url]?.get() ?: 0

        fun start() {
            current = this
            NostrChatBridge.registerChatNotifier { chat -> notifier.onInbound(chat) }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                inbox.inbound.collect { dm ->
                    try {
                        NostrChatBridge.dispatch(dm, prefs)
                    } finally {
                        inbox.onDispatched()
                        dispatched += dm.eventId
                    }
                }
            }
            inbox.start(receiver)
        }

        /** A chat screen's reader, marking as ChatViewModel.observeNostrInbound does; closed inside its handler at [cancelAtRumor]. */
        fun startReader(cancelAtRumor: String? = null): Job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                NostrChatBridge.inboundReader.collect { chat ->
                    if (chat.rumorId == cancelAtRumor) {
                        currentCoroutineContext().job.cancel()
                        yield()
                    }
                    if (chat.peerAddress != NostrChatBridge.UNATTRIBUTED_BOOT) prefs.markNostrEventSeen(chat.eventId)
                    if (chat.peerAddress == PEER) delivered += chat.rumorId
                }
            }

        fun stop() {
            if (current === this) current = null
            NostrChatBridge.unregisterChatNotifier()
            inbox.stop()
            scope.cancel()
            poolScope.cancel()
        }
    }

    /** RelaySincePrefs of SyncForegroundService, with each test relay mapped onto the DEFAULT_RELAYS key at its position. */
    private val sincePersistence =
        object : RelaySinceLine.Persistence {
            override fun get(relayUrl: String): Long? = urlToKey[relayUrl]?.let { prefs.getNostrRelaySince(it) }

            override fun advance(
                relayUrl: String,
                sinceSec: Long,
            ): Boolean {
                val key = urlToKey[relayUrl] ?: return false
                val moved = prefs.advanceNostrRelaySince(key, sinceSec)
                if (moved) checkNoUnmarkedWrapPassed(relayUrl)
                return moved
            }

            override fun scope(): Long = prefs.walletScopeGeneration()
        }

    private fun checkNoUnmarkedWrapPassed(relayUrl: String) {
        val since = sinceOf(relayUrl) ?: return
        window
            .filter { !prefs.hasSeenNostrEvent(it.eventId) && since > it.wrapAtSec }
            .forEach { violations += "since $since passed the unmarked wrap at ${it.wrapAtSec}" }
    }

    /**
     * The test's latch on every [SinceAwareRelay]: each answer ends with a stranger's wrap (dispatch drops it as not
     * from a contact) and its EOSE waits, bounded, until no relay is still sending, every such marker went through
     * dispatch, and the inbox and the bridge are settled for [QUIET_MS].
     */
    private val latch =
        object : SinceAwareRelay.Latch {
            override fun answering() {
                val s = current
                answeringSession.set(s)
                s?.answering?.incrementAndGet()
            }

            override fun marker(): String? {
                val s = answeringSession.get() ?: return null
                val at = nowSec()
                val marker = wrapAt(stranger, receiver.publicKey, "synthetic relay marker", at, at)
                s.markers += marker.eventId
                return marker.json
            }

            override fun beforeEose(markerId: String?) {
                val s = answeringSession.get() ?: return
                answeringSession.remove()
                s.answering.decrementAndGet()
                val deadline = System.nanoTime() + LATCH_MS * 1_000_000
                var quietFrom = 0L
                while (System.nanoTime() < deadline && current === s) {
                    val settled =
                        s.answering.get() == 0 &&
                            s.dispatched.containsAll(s.markers) &&
                            s.inbox.isDeliverySettled() &&
                            NostrChatBridge.isRelayBacklogSettled()
                    val t = System.nanoTime()
                    if (!settled) {
                        quietFrom = 0L
                    } else if (quietFrom == 0L) {
                        quietFrom = t
                    } else if (t - quietFrom >= QUIET_MS * 1_000_000) {
                        return
                    }
                    Thread.sleep(10)
                }
                if (current === s) latchTimeouts.incrementAndGet()
            }
        }

    private fun sinceAwareRelay(mode: SinceAwareRelay.Mode): SinceAwareRelay =
        SinceAwareRelay(mode).also {
            it.latch = latch
            relays += it
        }

    private fun sinceOf(relayUrl: String): Long? = urlToKey[relayUrl]?.let { prefs.getNostrRelaySince(it) }

    /**
     * What Nip17.send builds (kind-14 rumor with the p tag and both capability tags, kind-13 seal at the rumor time,
     * kind-1059 wrap from a fresh key), with the wrap time chosen instead of drawn. [encryptTo] other than the
     * recipient makes a wrap addressed to the recipient that its key cannot open.
     */
    private fun wrapAt(
        from: NOSTRIdentity,
        recipientPub: ByteArray,
        text: String,
        rumorAtSec: Long,
        wrapAtSec: Long,
        encryptTo: ByteArray = recipientPub,
    ): Wrapped {
        val rumor =
            NostrEvent.unsignedSerialize(
                pubkeyHex = from.publicKey.hex(),
                createdAtSec = rumorAtSec,
                kind = 14,
                tags = listOf(listOf("p", recipientPub.hex()), Nip17.CAPABILITY_TAG, Nip17.GP_CAPABILITY_TAG, Nip17.ZATT_CAPABILITY_TAG),
                content = text,
            )
        val seal =
            NostrEvent.signAndSerialize(
                pubkeyHex = from.publicKey.hex(),
                privateKey = from.privateKey,
                createdAtSec = rumorAtSec,
                kind = 13,
                tags = emptyList(),
                content = Nip44Encryption.encrypt(rumor, from.privateKey, encryptTo),
            )
        val oneTime = identity()
        val wrap =
            NostrEvent.signAndSerialize(
                pubkeyHex = oneTime.publicKey.hex(),
                privateKey = oneTime.privateKey,
                createdAtSec = wrapAtSec,
                kind = 1059,
                tags = listOf(listOf("p", recipientPub.hex())),
                content = Nip44Encryption.encrypt(seal, oneTime.privateKey, encryptTo),
            )
        return Wrapped(wrap, NostrEvent.parseId(wrap), NostrEvent.parseId(rumor), wrapAtSec)
    }

    private fun identity(): NOSTRIdentity = NOSTRIdentity.fromSeed(ByteArray(64).also { random.nextBytes(it) })

    private fun seenLedgerField() =
        ZchatPreferencesImpl::class.java
            .getDeclaredField("nostrSeenLedger\$delegate")
            .apply { isAccessible = true }

    private fun seenLedger(): NostrSeenLedger = (seenLedgerField().get(prefs) as Lazy<*>).value as NostrSeenLedger

    /** V6: realPreferences() installs a fixed 20,000 cap, so the SEEN_NOSTR_CAP seam would change nothing here. */
    private fun swapSeenLedger(ledger: NostrSeenLedger) = seenLedgerField().set(prefs, lazyOf(ledger))

    private fun lowestNostrRelaySince(): Long? =
        ZchatPreferencesImpl::class.java
            .getDeclaredMethod("lowestNostrRelaySince")
            .apply { isAccessible = true }
            .invoke(prefs) as Long?

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
