package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T1b-2 (train T1b, findings 3 and 6; INVESTIGATION.md §3/6, fix item 1): relay messages that arrive while no chat
 * screen is reading are lost.
 *
 * At 00089d8d9 the only reader of NostrChatBridge.inbound is a screen-scoped ChatViewModel, and the flow keeps nothing
 * (replay 0). A message that arrives on the Wallet tab, at a cold start or in the background is "left unmarked for
 * redelivery" and depends on a relay replay that our own relay never sends on a live socket. T1: the Open DMs at
 * 23:20:28 and 23:23:06 ("no active chat collector") and the four group memos to the cold-started HONOR release app.
 *
 * Wanted: the bridge holds such a message in a bounded in-memory list (deduplicated by event id, oldest dropped first)
 * and hands the list over, in arrival order, as soon as a chat screen starts reading. It is still marked seen only by
 * the reader, so one already marked seen before the hand-over is not handed on again.
 *
 * Pure JVM: dispatch runs as the foreground service calls it, with a proxy for the preferences it reads. Each test uses
 * its own event ids and reads only those.
 */
class InboundWithoutCollectorIsHeldTest {
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tag = "synthetic-held-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        readerScope.cancel()
        runBlocking {
            val finished =
                withTimeoutOrNull(READER_WAIT_MS) {
                    checkNotNull(readerScope.coroutineContext[Job]).join()
                    true
                } ?: false
            assertTrue("the held-list test reader did not leave NostrChatBridge after cancellation", finished)
        }
    }

    private suspend fun awaitNoReaders() {
        val inbound =
            NostrChatBridge::class.java
                .getDeclaredField("_inbound")
                .apply { isAccessible = true }
                .get(NostrChatBridge)
                as MutableSharedFlow<*>
        val empty =
            withTimeoutOrNull(READER_WAIT_MS) {
                inbound.subscriptionCount.first { it == 0 }
                true
            } ?: false
        assertTrue("another test left ${inbound.subscriptionCount.value} reader(s) on NostrChatBridge", empty)
    }

    /** The preferences the known-peer dispatch path reads; the reader marks seen here, as ChatViewModel does. */
    private class Prefs {
        val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "findPeerByNostrPubkey" -> if (args!![0] == SENDER) PEER else null
                    "hasSeenNostrEvent" -> args!![0] in seen
                    "markNostrEventSeen" -> {
                        seen += args!![0] as String
                        null
                    }
                    "toString" -> "Prefs"
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
    }

    private fun dm(n: Int): NostrInboxManager.InboundDm =
        NostrInboxManager.InboundDm(
            senderPubkeyHex = SENDER,
            content = "message $n",
            createdAtSec = System.currentTimeMillis() / 1000,
            eventId = "$tag-$n",
            rumorId = "rumor-$tag-$n",
        )

    /** A chat screen starts reading: collects this test's events, marking each seen on receipt as ChatViewModel does. */
    private fun startReading(
        prefs: Prefs,
        into: MutableList<String>,
    ): Job =
        readerScope.launch {
            NostrChatBridge.inbound.collect { chat ->
                if (chat.eventId.startsWith(tag)) {
                    prefs.seen += chat.eventId
                    into += chat.eventId
                }
            }
        }

    @Test
    fun `two messages that arrive with no chat screen reach the first one that starts, once each and in order`() =
        runBlocking {
            val prefs = Prefs()
            awaitNoReaders()
            NostrChatBridge.dispatch(dm(1), prefs.proxy)
            NostrChatBridge.dispatch(dm(2), prefs.proxy)

            val read = CopyOnWriteArrayList<String>()
            startReading(prefs, read)
            ScriptedRelay.awaitTrue(3_000) { read.size >= 2 }
            delay(300)

            assertEquals(
                "messages that arrived while no chat screen was reading were lost (T1 finding 3: \"no active chat collector\")",
                listOf("$tag-1", "$tag-2"),
                read.toList(),
            )
        }

    @Test
    fun `the same event held twice is handed on once`() =
        runBlocking {
            val prefs = Prefs()
            awaitNoReaders()
            // A relay replay after the pool's dedup was cleared hands the same gift-wrap to dispatch again.
            NostrChatBridge.dispatch(dm(1), prefs.proxy)
            NostrChatBridge.dispatch(dm(1), prefs.proxy)
            NostrChatBridge.dispatch(dm(2), prefs.proxy)

            val read = CopyOnWriteArrayList<String>()
            startReading(prefs, read)
            ScriptedRelay.awaitTrue(3_000) { "$tag-2" in read }
            delay(300)

            assertEquals(listOf("$tag-1", "$tag-2"), read.toList())
        }

    @Test
    fun `a held event that was marked seen before the hand-over is not handed on again`() =
        runBlocking {
            val prefs = Prefs()
            awaitNoReaders()
            NostrChatBridge.dispatch(dm(1), prefs.proxy)
            NostrChatBridge.dispatch(dm(2), prefs.proxy)
            prefs.seen += "$tag-1" // handled meanwhile (a reader of another copy marked it)

            val read = CopyOnWriteArrayList<String>()
            startReading(prefs, read)
            ScriptedRelay.awaitTrue(3_000) { "$tag-2" in read }
            delay(300)

            assertEquals("only the unhandled message is handed on", listOf("$tag-2"), read.toList())
        }

    @Test
    fun `the held list is bounded and drops the oldest first`() =
        runBlocking {
            val prefs = Prefs()
            awaitNoReaders()
            repeat(BURST) { NostrChatBridge.dispatch(dm(it), prefs.proxy) }

            val read = CopyOnWriteArrayList<String>()
            startReading(prefs, read)
            ScriptedRelay.awaitTrue(5_000) { read.size >= HELD_CAP }
            delay(500)

            assertEquals("the held list keeps the newest $HELD_CAP", HELD_CAP, read.size)
            assertEquals("the oldest are dropped first", "$tag-${BURST - HELD_CAP}", read.first())
            assertEquals("$tag-${BURST - 1}", read.last())
        }

    @Test
    fun `control - a message that arrives while a chat screen is reading is handed on at once`() =
        runBlocking {
            val prefs = Prefs()
            val read = CopyOnWriteArrayList<String>()
            startReading(prefs, read)
            delay(200)

            NostrChatBridge.dispatch(dm(1), prefs.proxy)
            assertTrue(ScriptedRelay.awaitTrue(3_000) { read.size == 1 })
        }

    private companion object {
        const val SENDER = "5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e"
        const val PEER = "usyntheticheldpeer000000001"

        /** INVESTIGATION.md §3/6 fix item 1: 512 entries. */
        const val HELD_CAP = 512
        const val BURST = 600
        const val READER_WAIT_MS = 10_000L
    }
}
