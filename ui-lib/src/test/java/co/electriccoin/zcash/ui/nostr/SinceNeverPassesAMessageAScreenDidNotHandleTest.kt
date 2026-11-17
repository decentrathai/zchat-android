package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * KI-24 fixup, G-KI24-4 (train T4 cross review): the relay `since` line never passes a message that reached a chat
 * screen's reader but was never marked seen.
 *
 * NostrChatBridge hands a relay message to the chat screen's reader ([NostrChatBridge.inbound], replay 0), and the
 * reader marks it seen only when it receives it. If the reader is cancelled (the chat screen closes, a rotation) after
 * the message was put in its buffer and before it reads it, the message is gone from memory and was never marked seen:
 * the relay's replay is its only copy. [NostrChatBridge.isRelayBacklogSettled] looks only at held messages and pending
 * redelivery, so at 1ca5f2e3c the line moves on the next two settled EOSEs to EOSE − 49 h. A gift-wrap's created_at is
 * back-dated by up to 48 h (NIP-59), so a wrap sent at T with the full jitter carries T − 48 h and the line passes it
 * about one hour after it was sent (about 25 h on average): no relay serves it again and a restart never recovers it.
 * In 2.12.4 a restart replayed it for 30 days. The same holds for an unattributed rotation ZBOOT, which the reader
 * handles but never marks, so a later pass can still adopt it.
 *
 * Wanted: the bridge keeps each message it handed to a reader until a reader has handled it to the end and it is marked
 * seen, and the `since` line the service builds stays at or below the lowest wrap time among them.
 *
 * The line is built as SyncForegroundService builds it (with the bridge's list of unhandled messages; at 1ca5f2e3c
 * there is none, so the line is built without it). Each test runs the line's clock its own multiple of 60 days ahead
 * of the wall clock, so no message another test left in the singleton bridge is still inside the 30-day relay window
 * for it; each rumor is dated now, because dispatch's 30-day window reads the wall clock. Relay urls, keys, ids and
 * addresses are synthetic.
 */
class SinceNeverPassesAMessageAScreenDidNotHandleTest {
    private companion object {
        const val SENDER = "6f1e4a3b2c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f"
        const val STRANGER = "7a2f5b4c3d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a"
        const val PEER = "usyntheticki24fixupsincepeer01"
        const val RELAY = "wss://relay-fixup-since.synthetic.example"
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        const val JITTER = 48 * HOUR
    }

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tag = "synthetic-ki24-fixup-${UUID.randomUUID()}"
    private val wallNow = System.currentTimeMillis() / 1000
    private val clock = AtomicLong(wallNow)
    private val stored = ConcurrentHashMap<String, Long>()
    private val markedSeen = ConcurrentHashMap.newKeySet<String>()

    @After
    fun tearDown() {
        readerScope.cancel()
    }

    private val prefs: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "findPeerByNostrPubkey" -> if (args!![0] == SENDER) PEER else null
                "hasSeenNostrEvent" -> args!![0] in markedSeen
                "markNostrEventSeen" -> {
                    markedSeen += args!![0] as String
                    null
                }
                "toString" -> "KI24FixupSincePrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args!![0]
                else ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
            }
        } as ZchatPreferences

    private val persistence =
        object : RelaySinceLine.Persistence {
            override fun get(relayUrl: String): Long? = stored[relayUrl]

            override fun advance(
                relayUrl: String,
                sinceSec: Long,
            ): Boolean {
                val next = RelaySinceLine.nextStored(stored[relayUrl], sinceSec, clock.get()) ?: return false
                stored[relayUrl] = next
                return true
            }

            override fun scope(): Long = 0L
        }

    /** The line as SyncForegroundService builds it (see the class comment). */
    private val line: RelaySinceLine = sinceLineAsTheServiceBuildsIt()

    private fun sinceLineAsTheServiceBuildsIt(): RelaySinceLine {
        val settled: () -> Boolean = { NostrChatBridge.isRelayBacklogSettled() }
        val now: () -> Long = { clock.get() }
        val lowestUnhandled =
            NostrChatBridge::class.java.methods.firstOrNull {
                it.name == "lowestUnhandledWrapSec" && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
            }
        val withUnhandled =
            RelaySinceLine::class.java.constructors.firstOrNull {
                it.parameterCount == 4 && it.parameterTypes[3] == Function1::class.java
            }
        if (lowestUnhandled == null || withUnhandled == null) return RelaySinceLine(persistence, settled, now)
        val unhandled: (Long) -> Long? = { t -> invoke { lowestUnhandled.invoke(NostrChatBridge, t) } as Long? }
        return invoke { withUnhandled.newInstance(persistence, settled, now, unhandled) } as RelaySinceLine
    }

    /** What the chat screen's reader does at the end of an event it handled (not cancelled midway), where it exists. */
    private fun readerFinished(eventId: String) {
        NostrChatBridge::class.java.methods
            .firstOrNull { it.name == "onChatHandled" && it.parameterCount == 1 }
            ?.let { m -> invoke { m.invoke(NostrChatBridge, eventId) } }
    }

    private fun invoke(block: () -> Any?): Any? =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun dm(
        name: String,
        wrapCreatedAtSec: Long,
        sender: String = SENDER,
        content: String = "synthetic fixup message $name",
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = sender,
        content = content,
        createdAtSec = wallNow,
        eventId = "$tag-$name",
        rumorId = "rumor-$tag-$name",
        wrapCreatedAtSec = wrapCreatedAtSec,
    )

    /** Whatever an earlier test left in the singleton bridge is handed over and its redelivery asked for. */
    private suspend fun settleBridge() {
        val reader = readerScope.launch { NostrChatBridge.inbound.collect { } }
        ScriptedRelay.awaitTrue(3_000) { NostrChatBridge.consumePendingRedelivery() || NostrChatBridge.isRelayBacklogSettled() }
        ScriptedRelay.awaitTrue(3_000) { NostrChatBridge.isRelayBacklogSettled() }
        reader.cancelAndJoin()
        assertTrue("fixture sanity: the bridge starts with nothing waiting", NostrChatBridge.isRelayBacklogSettled())
    }

    /** A reader that takes the first event named [blockerId] and then never finishes it, so later ones stay buffered. */
    private fun startStuckReader(
        blockerId: String,
        received: CompletableDeferred<Unit>,
    ): Job =
        readerScope.launch {
            NostrChatBridge.inbound.collect { chat ->
                if (chat.eventId == blockerId) {
                    received.complete(Unit)
                    awaitCancellation()
                }
            }
        }

    private fun eoses(n: Int) = repeat(n) { line.onEose(RELAY, clock.get()) }

    @Test
    fun `a fully jittered DM left in a cancelled reader's buffer keeps the since line at or below its wrap time`() =
        runBlocking {
            settleBridge()
            clock.set(wallNow + 60 * DAY)
            val sentAt = clock.get()
            val received = CompletableDeferred<Unit>()
            val reader = startStuckReader("$tag-blocker", received)
            NostrChatBridge.dispatch(dm("blocker", wrapCreatedAtSec = sentAt), prefs)
            withTimeout(3_000) { received.await() }
            // If the blocker came before the reader subscribed, it was held and a redelivery asked for; the reader took it.
            assertTrue(
                ScriptedRelay.awaitTrue(3_000) {
                    NostrChatBridge.consumePendingRedelivery() || NostrChatBridge.isRelayBacklogSettled()
                },
            )

            val jittered = dm("jittered", wrapCreatedAtSec = sentAt - JITTER)
            NostrChatBridge.dispatch(jittered, prefs)
            reader.cancelAndJoin()
            assertTrue(
                "fixture: nothing is held and no redelivery is pending, so the gate is open",
                NostrChatBridge.isRelayBacklogSettled(),
            )

            clock.set(sentAt + 2 * HOUR)
            eoses(2)
            val since = line.sinceFor(RELAY)
            assertTrue(
                "the since line moved to $since, past the wrap time ${jittered.wrapCreatedAtSec} of a DM no reader ever " +
                    "read or marked seen, two hours after it was sent: no relay serves it again and a restart cannot recover it",
                since == null || since <= jittered.wrapCreatedAtSec,
            )
        }

    @Test
    fun `an unattributed ZBOOT the reader handled but never marked keeps the since line at or below its wrap time`() =
        runBlocking {
            settleBridge()
            clock.set(wallNow + 120 * DAY)
            val sentAt = clock.get()
            val handled = CopyOnWriteArrayList<String>()
            val reader =
                readerScope.launch {
                    NostrChatBridge.inbound.collect { chat ->
                        // As ChatViewModel: an unattributed ZBOOT is attributed and routed, never marked seen.
                        handled += chat.eventId
                        readerFinished(chat.eventId)
                    }
                }
            val boot = dm("boot", wrapCreatedAtSec = sentAt - JITTER, sender = STRANGER, content = "ZBOOT|synthetic-fixup")
            NostrChatBridge.dispatch(boot, prefs)
            assertTrue(ScriptedRelay.awaitTrue(3_000) { boot.eventId in handled })
            reader.cancelAndJoin()

            clock.set(sentAt + 2 * HOUR)
            eoses(2)
            val since = line.sinceFor(RELAY)
            assertTrue(
                "the since line moved to $since, past an unattributed ZBOOT never marked seen (wrap ${boot.wrapCreatedAtSec})",
                since == null || since <= boot.wrapCreatedAtSec,
            )
        }

    /** Control, GREEN before and after: a message the reader received, marked seen and finished does not hold the line. */
    @Test
    fun `control - a DM the reader marked seen and finished lets the line move`() =
        runBlocking {
            settleBridge()
            clock.set(wallNow + 180 * DAY)
            val sentAt = clock.get()
            val reader =
                readerScope.launch {
                    NostrChatBridge.inbound.collect { chat ->
                        prefs.markNostrEventSeen(chat.eventId)
                        readerFinished(chat.eventId)
                    }
                }
            val handledDm = dm("handled", wrapCreatedAtSec = sentAt - JITTER)
            NostrChatBridge.dispatch(handledDm, prefs)
            assertTrue(ScriptedRelay.awaitTrue(3_000) { handledDm.eventId in markedSeen })
            // If the DM came before the reader subscribed, it was held and a redelivery asked for; the reader took it.
            assertTrue(
                ScriptedRelay.awaitTrue(3_000) {
                    NostrChatBridge.consumePendingRedelivery() || NostrChatBridge.isRelayBacklogSettled()
                },
            )
            reader.cancelAndJoin()

            clock.set(sentAt + 2 * HOUR)
            eoses(2)
            assertEquals(sentAt + 2 * HOUR - RelaySinceLine.MARGIN_SEC, line.sinceFor(RELAY))
        }
}
