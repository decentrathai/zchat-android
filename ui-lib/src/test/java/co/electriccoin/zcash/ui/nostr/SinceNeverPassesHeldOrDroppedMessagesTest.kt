package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * KI-24 part B (fix-all 2.13.0, train T4; SPECS §2.2 B5, §2.3 step 8, §2.7 T1b-2): the relay `since` line never passes a
 * message the app received but has not handled.
 *
 * T1b-2 keeps a relay message that no chat screen took in NostrChatBridge's in-memory held list; it is marked seen only
 * when a chat screen reads it, and a full list drops the oldest and asks for a relay redelivery. The relay's replay is
 * then the only other copy. If the `since` line moved past such a message, a restart would never get it back. So while
 * the bridge holds a message, or waits for a redelivery (also asked when the inbox's own buffer overflowed), the line
 * does not move; once a chat screen has taken the held message and the redelivery has been asked for, it may.
 *
 * Drives [RelaySinceLine] with the real bridge state as its gate (the service's gate adds the inbox's in-flight count).
 * Each test uses its own event ids. Relay urls, keys and addresses are synthetic.
 */
class SinceNeverPassesHeldOrDroppedMessagesTest {
    private companion object {
        const val SENDER = "5e0d3f2a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e"
        const val PEER = "usyntheticki24sincepeer000001"
        const val RELAY = "wss://relay-since.synthetic.example"

        // The bridge sheds a chat DM whose created_at is more than 30 days from the wall clock (CHAT_MSG_MAX_AGE_SEC),
        // so a fixed date stopped being "now" on 21 Oct 2026 and every message was dropped before it could be held.
        val NOW = System.currentTimeMillis() / 1000
    }

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tag = "synthetic-ki24-since-${UUID.randomUUID()}"
    private val stored = ConcurrentHashMap<String, Long>()
    private val line =
        RelaySinceLine(
            object : RelaySinceLine.Persistence {
                override fun get(relayUrl: String): Long? = stored[relayUrl]

                override fun advance(
                    relayUrl: String,
                    sinceSec: Long,
                ): Boolean {
                    val next = RelaySinceLine.nextStored(stored[relayUrl], sinceSec, NOW) ?: return false
                    stored[relayUrl] = next
                    return true
                }

                override fun scope(): Long = 0L
            },
            isBacklogSettled = { NostrChatBridge.isRelayBacklogSettled() },
            nowSec = { NOW },
        )

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
                "hasSeenNostrEvent" -> false
                "toString" -> "KI24SincePrefs"
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

    private fun dm(n: Int) =
        NostrInboxManager.InboundDm(
            senderPubkeyHex = SENDER,
            content = "synthetic held message $n",
            createdAtSec = NOW,
            eventId = "$tag-$n",
            rumorId = "rumor-$tag-$n",
        )

    private fun startReading(into: MutableList<String>): Job =
        readerScope.launch {
            NostrChatBridge.inbound.collect { chat -> into += chat.eventId }
        }

    /** Whatever an earlier test left in the singleton bridge is handed over and its redelivery asked for. */
    private suspend fun settleBridge() {
        val reader = startReading(CopyOnWriteArrayList())
        ScriptedRelay.awaitTrue(3_000) { NostrChatBridge.consumePendingRedelivery() || NostrChatBridge.isRelayBacklogSettled() }
        ScriptedRelay.awaitTrue(3_000) { NostrChatBridge.isRelayBacklogSettled() }
        reader.cancelAndJoin()
        assertTrue("fixture sanity: the bridge starts with nothing waiting", NostrChatBridge.isRelayBacklogSettled())
    }

    private fun eoses(n: Int) = repeat(n) { line.onEose(RELAY, NOW) }

    @Test
    fun `while a message is held or its redelivery is pending the line does not move, and afterwards it may`() =
        runBlocking {
            settleBridge()

            NostrChatBridge.dispatch(dm(1), prefs)
            assertFalse("a message no chat screen took is held", NostrChatBridge.isRelayBacklogSettled())
            eoses(3)
            assertNull("the since line moved past a held message", line.sinceFor(RELAY))

            val read = CopyOnWriteArrayList<String>()
            val reader = startReading(read)
            assertTrue(ScriptedRelay.awaitTrue(3_000) { "$tag-1" in read })
            eoses(3)
            assertNull("the redelivery asked for the held message is still pending", line.sinceFor(RELAY))

            assertTrue(NostrChatBridge.consumePendingRedelivery())
            eoses(2)
            assertEquals("handed over and redelivered: the line may move", NOW - RelaySinceLine.MARGIN_SEC, line.sinceFor(RELAY))
            reader.cancelAndJoin()
        }

    @Test
    fun `a message the inbox dropped for a full buffer holds the line until its redelivery is asked for`() =
        runBlocking {
            settleBridge()

            NostrChatBridge.flagRedelivery()
            eoses(3)
            assertNull(line.sinceFor(RELAY))

            val reader = startReading(CopyOnWriteArrayList())
            assertTrue(ScriptedRelay.awaitTrue(3_000) { NostrChatBridge.consumePendingRedelivery() })
            eoses(2)
            assertEquals(NOW - RelaySinceLine.MARGIN_SEC, line.sinceFor(RELAY))
            reader.cancelAndJoin()
        }
}
