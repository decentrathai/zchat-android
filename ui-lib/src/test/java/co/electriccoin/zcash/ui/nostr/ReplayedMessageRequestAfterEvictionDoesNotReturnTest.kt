package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * KI-24 part B (fix-all 2.13.0, train T4; SPECS §2.2 B2) — a contact request the user already handled comes back once
 * its event id has left the seen list.
 *
 * A first message from an unknown relay key that carries an INIT becomes a contact request (#224). The request path marks
 * the gift-wrap seen, and Accept or Reject removes the request. The seen list keeps 2,000 ids and drops the oldest, and
 * the relays replay a wrap for about 30 days. At 6e24d549f, once the id has been dropped, the replay passes the check
 * and the request (and its "claims this contact's address" note, for a known address) is stored again.
 *
 * Wanted (KI-24 part B): the request path asks the seen list with the wrap's outer created_at, and a wrap older than the
 * seen-list floor (raised by eviction, never above now − 49 h) counts as seen: no request comes back.
 *
 * The preferences are a Proxy; an evicted id is absent, and hasSeenNostrEvent answers with the floor when it is given
 * the wrap time. The wrap time of an InboundDm built without one is its rumor time. Every key, id and address is
 * synthetic.
 */
class ReplayedMessageRequestAfterEvictionDoesNotReturnTest {
    private companion object {
        const val STRANGER_PUBKEY = "0f0e0d0c0b0a09080706050403020100f0e0d0c0b0a090807060504030201220"
        const val CLAIMED = "usyntheticki24requestclaimed01"
        const val HOUR_SEC = 3_600L
        const val DAY_SEC = 24 * HOUR_SEC
    }

    @Test
    fun `a handled request whose id was evicted does not come back when the relay replays it`() {
        val now = System.currentTimeMillis() / 1000
        val prefs = RequestPrefs(floorSec = now - 49 * HOUR_SEC)

        NostrChatBridge.dispatch(initDm("evt-ki24-request-old", createdAtSec = now - 10 * DAY_SEC), prefs.proxy)

        assertEquals(
            "a contact request older than the seen-list floor came back after its id was evicted",
            emptyList<String>(),
            prefs.requestsAdded,
        )
    }

    /** Control, GREEN before and after: a new INIT from an unknown key becomes one request. */
    @Test
    fun `control - a new request is stored once`() {
        val now = System.currentTimeMillis() / 1000
        val prefs = RequestPrefs(floorSec = now - 49 * HOUR_SEC)

        NostrChatBridge.dispatch(initDm("evt-ki24-request-new", createdAtSec = now), prefs.proxy)

        assertEquals(listOf("evt-ki24-request-new"), prefs.requestsAdded)
    }

    private fun initDm(
        eventId: String,
        createdAtSec: Long,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = STRANGER_PUBKEY,
        content = ZMSGProtocol.createV4InitMessage("KI24REQ1", CLAIMED, "synthetic first message"),
        createdAtSec = createdAtSec,
        eventId = eventId,
        rumorId = "rumor-$eventId",
    )

    private class RequestPrefs(
        private val floorSec: Long,
    ) {
        val seenIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val requestsAdded: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "findPeerByNostrPubkey" -> null
                    "isNostrPubkeyBlocked" -> false
                    "hasSeenNostrEvent" -> {
                        val wrapCreatedAt = a.getOrNull(1) as Long?
                        a[0] in seenIds || (wrapCreatedAt != null && wrapCreatedAt < floorSec)
                    }
                    "markNostrEventSeen" -> {
                        seenIds += a[0] as String
                        null
                    }
                    "isSelfAddress" -> false
                    "addMessageRequest" -> {
                        requestsAdded += (a[0] as ZchatPreferences.MessageRequest).eventId
                        null
                    }
                    "getConversationId" -> null
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "KI24RequestPrefs"
                    else -> throw UnsupportedOperationException("unexpected ZchatPreferences.${method.name}")
                }
            } as ZchatPreferences
    }
}
