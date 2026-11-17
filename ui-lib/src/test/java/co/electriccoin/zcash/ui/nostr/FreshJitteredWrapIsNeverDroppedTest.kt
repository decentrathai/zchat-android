package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * KI-24 part B (fix-all 2.13.0, train T4; SPECS §2.2 B3), a guard against a false drop, GREEN before and after the fix.
 *
 * NIP-17 back-dates each gift-wrap's created_at by up to 48 hours (Nip17.JITTER_WINDOW_SEC), so a message sent just now
 * can carry a wrap time 47 hours old. The seen-list floor is never above now − 49 h, so such a wrap is never taken for
 * an old one: with the floor at its highest, a new message from a contact reaches the chat, and a new INIT from an
 * unknown key becomes a request.
 *
 * The preferences are a Proxy that answers hasSeenNostrEvent with the floor when it is given the wrap time. The wrap
 * time of an InboundDm built without one is its rumor time. Every key, id and address is synthetic.
 */
class FreshJitteredWrapIsNeverDroppedTest {
    private companion object {
        const val SENDER_PUBKEY = "93c9d47100f8aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112255"
        const val STRANGER_PUBKEY = "0f0e0d0c0b0a09080706050403020100f0e0d0c0b0a090807060504030201110"
        const val PEER = "usyntheticki24jitteredpeer0001"
        const val HOUR_SEC = 3_600L
    }

    @Test
    fun `a new message whose wrap is 47 hours old reaches the chat when the floor is at its highest`() =
        runBlocking {
            val now = System.currentTimeMillis() / 1000
            val prefs = JitterPrefs(floorSec = now - 49 * HOUR_SEC)
            val dm = dm("evt-ki24-jittered", SENDER_PUBKEY, "synthetic jittered message", now - 47 * HOUR_SEC)

            val received = dispatchWithReader(prefs, dm)

            assertEquals(listOf("evt-ki24-jittered"), received)
        }

    @Test
    fun `a new request whose wrap is 47 hours old is stored when the floor is at its highest`() {
        val now = System.currentTimeMillis() / 1000
        val prefs = JitterPrefs(floorSec = now - 49 * HOUR_SEC)
        val init = ZMSGProtocol.createV4InitMessage("KI24JIT1", "usyntheticki24jitterclaim0001", "synthetic hello")

        NostrChatBridge.dispatch(dm("evt-ki24-jittered-request", STRANGER_PUBKEY, init, now - 47 * HOUR_SEC), prefs.proxy)

        assertEquals(listOf("evt-ki24-jittered-request"), prefs.requestsAdded)
    }

    private fun dm(
        eventId: String,
        sender: String,
        content: String,
        createdAtSec: Long,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = sender,
        content = content,
        createdAtSec = createdAtSec,
        eventId = eventId,
        rumorId = "rumor-$eventId",
    )

    private suspend fun dispatchWithReader(
        prefs: JitterPrefs,
        dm: NostrInboxManager.InboundDm,
    ): List<String> =
        coroutineScope {
            val received = Collections.synchronizedList(mutableListOf<String>())
            val reader =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    NostrChatBridge.inbound.collect { received += it.eventId }
                }
            NostrChatBridge.dispatch(dm, prefs.proxy)
            repeat(100) { yield() }
            reader.cancel()
            received.toList()
        }

    private class JitterPrefs(
        private val floorSec: Long,
    ) {
        val requestsAdded: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "findPeerByNostrPubkey" -> if (a[0] == SENDER_PUBKEY) PEER else null
                    "isNostrPubkeyBlocked" -> false
                    "hasSeenNostrEvent" -> {
                        val wrapCreatedAt = a.getOrNull(1) as Long?
                        wrapCreatedAt != null && wrapCreatedAt < floorSec
                    }
                    "markNostrEventSeen" -> null
                    "isSelfAddress" -> false
                    "addMessageRequest" -> {
                        requestsAdded += (a[0] as ZchatPreferences.MessageRequest).eventId
                        null
                    }
                    "getConversationId" -> null
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "KI24JitterPrefs"
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            else -> null
                        }
                }
            } as ZchatPreferences
    }
}
