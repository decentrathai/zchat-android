package co.electriccoin.zcash.ui.service

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * KI-24 part B (fix-all 2.13.0, train T4; SPECS §2.2 B1) — an old relay message raises a notification again once its
 * event id has left the seen list.
 *
 * The relays keep a gift-wrap for about 30 days and replay it on every fresh subscription (every process start, every
 * reconnect, the liveness watchdog). The persistent seen list ("zchat_nostr_seen", #188) keeps 2,000 event ids and drops
 * the oldest. RelayMessageNotifier's own memory lives only as long as the process. So after a restart, an old message
 * whose id was dropped passes both checks and notifies the user again, at 6e24d549f, both through
 * NostrChatBridge.dispatch and in the notifier itself.
 *
 * Wanted (KI-24 part B): the seen list keeps each event's outer (gift-wrap) created_at and a floor below which
 * everything counts as seen: the floor rises when entries are evicted, never above now − 49 h (the 48 h NIP-59
 * jitter plus one hour), and the relay path asks with the wrap's time. A replayed wrap older than the floor is seen:
 * no notification.
 *
 * The preferences are a Proxy: an evicted id is absent, and hasSeenNostrEvent answers with the floor when it is given
 * the wrap time (the arity the fixed build calls). The wrap time of an InboundDm built without one is its rumor time.
 * Every key, id and address is synthetic.
 */
class ReplayedOldDmAfterEvictionRaisesNoNotificationTest {
    private companion object {
        const val SENDER_PUBKEY = "93c9d47100f8aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112244"
        const val PEER = "usyntheticki24notifypeer00001"
        const val TEXT = "synthetic old relay message"
        const val HOUR_SEC = 3_600L
        const val DAY_SEC = 24 * HOUR_SEC
    }

    private var collector: Job? = null

    @After
    fun tearDown() {
        collector?.cancel()
        NostrChatBridge.unregisterChatNotifier()
    }

    @Test
    fun `after a restart an old message whose id was evicted notifies nothing when the relay replays it`() =
        runBlocking {
            val now = nowSec()
            val prefs = FloorPrefs(floorSec = now - 49 * HOUR_SEC)

            val alerts = dispatchThroughNewNotifier(prefs, dm("evt-ki24-evicted-old", createdAtSec = now - 10 * DAY_SEC))

            assertEquals(
                "a replayed message older than the seen-list floor (its id evicted) notified the user again",
                emptyList<Any>(),
                alerts,
            )
        }

    @Test
    fun `the notifier itself, new after a restart, raises nothing for an evicted message older than the floor`() {
        val now = nowSec()
        val prefs = FloorPrefs(floorSec = now - 49 * HOUR_SEC)
        val alerts = Collections.synchronizedList(mutableListOf<Any>())
        val notifier = RelayMessageNotifier(prefs.proxy, { false }) { alerts += it }

        notifier.onInbound(chat("evt-ki24-evicted-direct", createdAtSec = now - 10 * DAY_SEC))

        assertEquals("the notifier alerted for an evicted message older than the floor", emptyList<Any>(), alerts)
    }

    /** Control, GREEN before and after: a new message (not seen, above the floor) notifies once through each path. */
    @Test
    fun `control - a new message still notifies once`() =
        runBlocking {
            val now = nowSec()
            val prefs = FloorPrefs(floorSec = now - 49 * HOUR_SEC)

            assertEquals(1, dispatchThroughNewNotifier(prefs, dm("evt-ki24-new", createdAtSec = now)).size)

            val direct = Collections.synchronizedList(mutableListOf<Any>())
            RelayMessageNotifier(prefs.proxy, { false }) { direct += it }.onInbound(chat("evt-ki24-new-direct", now))
            assertEquals(1, direct.size)
        }

    // ---------------------------------------------------------------- fixture

    private class FloorPrefs(
        private val floorSec: Long,
    ) {
        val seenIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "findPeerByNostrPubkey" -> if (a[0] == SENDER_PUBKEY) PEER else null
                    "hasSeenNostrEvent" -> {
                        val wrapCreatedAt = a.getOrNull(1) as Long?
                        a[0] in seenIds || (wrapCreatedAt != null && wrapCreatedAt < floorSec)
                    }
                    "markNostrEventSeen" -> {
                        seenIds += a[0] as String
                        null
                    }
                    "getNotificationPrivacy" -> NotificationPrivacy.FULL_PREVIEW
                    "getDisplayName" -> "Synthetic Contact"
                    "isConversationMuted" -> false
                    "getConversationMode" -> ConversationMode.OPEN
                    "isNostrPubkeyBlocked" -> false
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "KI24FloorPrefs"
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

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun dm(
        eventId: String,
        createdAtSec: Long,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = SENDER_PUBKEY,
        content = TEXT,
        createdAtSec = createdAtSec,
        eventId = eventId,
        rumorId = "rumor-$eventId",
    )

    private fun chat(
        eventId: String,
        createdAtSec: Long,
    ) = NostrChatBridge.InboundChat(
        peerAddress = PEER,
        plaintext = TEXT,
        createdAtSec = createdAtSec,
        eventId = eventId,
        rumorId = "rumor-$eventId",
    )

    /** A new notifier (as a service start makes one), registered with the bridge; the DMs go through dispatch. */
    private suspend fun dispatchThroughNewNotifier(
        prefs: FloorPrefs,
        vararg dms: NostrInboxManager.InboundDm,
    ): List<Any> =
        coroutineScope {
            val delivered = Collections.synchronizedList(mutableListOf<Any>())
            val notifier = RelayMessageNotifier(prefs.proxy, { false }) { delivered += it }
            NostrChatBridge.registerChatNotifier { notifier.onInbound(it) }
            collector =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    NostrChatBridge.inbound.collect { }
                }
            for (dm in dms) NostrChatBridge.dispatch(dm, prefs.proxy)
            repeat(100) { yield() }
            collector?.cancel()
            delivered.toList()
        }
}
