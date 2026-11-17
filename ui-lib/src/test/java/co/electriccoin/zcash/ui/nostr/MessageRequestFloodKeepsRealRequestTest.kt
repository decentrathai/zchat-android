package co.electriccoin.zcash.ui.nostr

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * ZC-1404 (swarm 1 Nov, lens relay-abuse) — 100 first-contact requests from throwaway relay keys push out a real
 * pending request, which is already marked seen and never comes back; each one also raises a notification.
 *
 * NostrChatBridge.routeUnknownPubkeyAsRequest has no per-sender or rate limit. ZchatPreferencesImpl.addMessageRequest
 * keeps at most MAX_MESSAGE_REQUESTS (100) and evicts the OLDEST by timestamp, skipping only the sender being added;
 * markNostrEventSeen runs after the add. So a real request that came first is evicted by the 100th junk one, and its
 * replay is dropped by hasSeenNostrEvent. requestNotifier is invoked once per stored request.
 *
 * Wanted: an older pending request is never evicted for a newer one (overflow is dropped or parked); the real request
 * stays listed; the request notifications of one burst are bounded.
 *
 * Seams: NostrChatBridge.dispatch with a Proxy ZchatPreferences whose request store is the REAL
 * ZchatPreferencesImpl.addMessageRequest / getMessageRequests over an in-memory SharedPreferences (the impl is
 * allocated without its constructor; only messageRequestPrefs and messageRequestLock are set), and the bridge's
 * registerRequestNotifier hook. Every key, id and address is synthetic.
 */
class MessageRequestFloodKeepsRealRequestTest {
    @After
    fun tearDown() {
        NostrChatBridge.unregisterRequestNotifier()
    }

    @Test
    fun `a flood of 100 requests from throwaway keys does not evict a real pending request`() {
        val prefs = FloodPrefs()
        val now = System.currentTimeMillis() / 1000

        NostrChatBridge.dispatch(initDm(REAL_PUBKEY, "evt-zc1404-real", now - 3_600), prefs.proxy)
        assertTrue("setup: the real request was not stored", prefs.storedPubkeys().contains(REAL_PUBKEY))

        repeat(FLOOD) { i -> NostrChatBridge.dispatch(initDm(junkKey(i), "evt-zc1404-junk-$i", now - 60 + i % 50), prefs.proxy) }

        assertTrue(
            "the real pending request was evicted by $FLOOD throwaway requests " +
                "(and its id is marked seen: ${"evt-zc1404-real" in prefs.seenIds})",
            prefs.storedPubkeys().contains(REAL_PUBKEY),
        )
    }

    @Test
    fun `a flood of 100 requests from throwaway keys raises a bounded number of notifications`() {
        val prefs = FloodPrefs()
        val now = System.currentTimeMillis() / 1000
        val notified = Collections.synchronizedList(mutableListOf<String>())
        NostrChatBridge.registerRequestNotifier { notified += it.senderNostrPubkeyHex }

        repeat(FLOOD) { i -> NostrChatBridge.dispatch(initDm(junkKey(i), "evt-zc1404-n-$i", now - 60 + i % 50), prefs.proxy) }

        assertTrue(
            "one burst of $FLOOD stranger requests raised ${notified.size} notifications (bound $NOTIFY_BOUND)",
            notified.size <= NOTIFY_BOUND,
        )
    }

    private fun junkKey(i: Int): String = "%064x".format(0x1404_0000L + i)

    private fun initDm(
        pubkey: String,
        eventId: String,
        createdAtSec: Long,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = pubkey,
        content = ZMSGProtocol.createV4InitMessage("ZC1404RQ", "usyntheticzc1404claimed-$eventId", "synthetic hello"),
        createdAtSec = createdAtSec,
        eventId = eventId,
        rumorId = "rumor-$eventId",
    )

    private class FloodPrefs {
        val seenIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private val store: ZchatPreferencesImpl = realRequestStore()

        fun storedPubkeys(): List<String> = store.getMessageRequests().map { it.senderNostrPubkeyHex }

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "findPeerByNostrPubkey" -> null
                    "isNostrPubkeyBlocked" -> false
                    "hasSeenNostrEvent" -> a[0] in seenIds
                    "markNostrEventSeen" -> {
                        seenIds += a[0] as String
                        null
                    }
                    "isSelfAddress" -> false
                    "addMessageRequest" -> {
                        store.addMessageRequest(a[0] as ZchatPreferences.MessageRequest)
                        null
                    }
                    "getMessageRequests" -> store.getMessageRequests()
                    "getConversationId" -> null
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "ZC1404FloodPrefs"
                    else -> throw UnsupportedOperationException("unexpected ZchatPreferences.${method.name}")
                }
            } as ZchatPreferences

        private fun realRequestStore(): ZchatPreferencesImpl {
            val unsafe =
                Class
                    .forName("sun.misc.Unsafe")
                    .getDeclaredField("theUnsafe")
                    .apply { isAccessible = true }
                    .get(null)
            val impl =
                unsafe.javaClass
                    .getMethod("allocateInstance", Class::class.java)
                    .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
            set(impl, "messageRequestPrefs", MemoryPrefs())
            set(impl, "messageRequestLock", Any())
            return impl
        }

        private fun set(
            target: Any,
            name: String,
            value: Any,
        ) {
            ZchatPreferencesImpl::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(target, value)
        }
    }

    /** A plain in-memory SharedPreferences: edits apply at once on apply()/commit(). */
    private class MemoryPrefs : SharedPreferences {
        private val map = Collections.synchronizedMap(LinkedHashMap<String, Any?>())

        override fun getAll(): MutableMap<String, *> = synchronized(map) { LinkedHashMap(map) }

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = map[key] as String? ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as MutableSet<String>? ?: defValues
        }

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = map[key] as Int? ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = map[key] as Long? ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = map[key] as Float? ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = map[key] as Boolean? ?: defValue

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = MemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class MemoryEditor : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?,
            ) = apply { puts[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { puts[key] = values }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { puts[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { puts[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { puts[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { puts[key] = value }

            override fun remove(key: String) = apply { removes += key }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(map) {
                    if (clear) map.clear()
                    removes.forEach { map.remove(it) }
                    map.putAll(puts)
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    private companion object {
        val REAL_PUBKEY = "%064x".format(0x1404_ABCDL)
        const val FLOOD = 100
        const val NOTIFY_BOUND = 10
    }
}
