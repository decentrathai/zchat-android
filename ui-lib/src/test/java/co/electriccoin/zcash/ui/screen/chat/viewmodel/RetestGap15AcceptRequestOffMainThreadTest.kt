package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

/**
 * Finding [15] (main-thread-io) — RED regression pin proving ChatViewModel.acceptMessageRequest runs its
 * EncryptedSharedPreferences AES-GCM scan (findPeerByNostrPubkey over modePrefs.all, getMessageRequests)
 * OFF the caller (main) thread.
 *
 * acceptMessageRequest was a plain `fun` invoked directly from the Compose onAccept lambda
 * (AndroidChat.kt), so the whole per-entry decrypt scan ran on the UI thread -> ANR on a large roster.
 * observeMessageRequests already wraps the IDENTICAL getMessageRequests() call in
 * withContext(Dispatchers.IO) (ChatViewModel.kt ~461) precisely to keep it off-main; accept must do the
 * same.
 *
 * We drive the CONFLICT_PUBKEY_REUSED early return: the request's authenticated KEY is already bound to
 * ADDR_OTHER while the request claims ADDR_NEW, so findPeerByNostrPubkey(KEY) resolves a DIFFERENT
 * address and accept refuses. The Proxy records the thread that scan ran on. As a plain `fun` the scan
 * runs inline on the runBlocking caller thread (== callerThread) -> assertNotEquals FAILS (RED). After the
 * fix (suspend fun ... = withContext(Dispatchers.IO)) the scan hops onto an IO thread != callerThread ->
 * GREEN.
 *
 * Fixture mirrors RetestGap01AcceptRequestPubkeyReuseTest: Unsafe-allocate ChatViewModel, inject a Proxy
 * ZchatPreferences seeded so KEY -> ADDR_OTHER. Pure JVM — Dispatchers.IO is the JVM thread pool; no Main
 * dispatcher is touched because the refusal path returns before loadConversations()/viewModelScope.
 */
class RetestGap15AcceptRequestOffMainThreadTest {

    private companion object {
        const val ADDR_OTHER = "usynthetic-other-address"
        const val ADDR_NEW = "usynthetic-new-claimed-address"
        // NOSTR pubkey hex already bound (in the store) to ADDR_OTHER; the incoming request reuses it.
        const val KEY = "aabbccddeeff00112233445566778899"
    }

    @Test
    fun `acceptMessageRequest runs its EncryptedSharedPreferences scan off the caller thread`() {
        val scanThread = AtomicReference<Thread?>()
        val prefs =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "isSelfAddress" -> false
                    // ADDR_NEW has no prior binding -> execution passes the key-change branch...
                    "getPeerNostrPubkey" -> null
                    // ...and reaches the reverse-uniqueness scan. KEY is already bound to a DIFFERENT
                    // address, so this returns ADDR_OTHER (!= ADDR_NEW) => CONFLICT_PUBKEY_REUSED. Record
                    // the thread it ran on to prove the scan hopped off the caller thread.
                    "findPeerByNostrPubkey" -> {
                        scanThread.set(Thread.currentThread())
                        ADDR_OTHER
                    }
                    "toString" -> "RecordingZchatPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            } as ZchatPreferences

        val unsafe =
            Class.forName("sun.misc.Unsafe")
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        val vm =
            unsafe.javaClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
        ChatViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, prefs)

        val request =
            ZchatPreferences.MessageRequest(
                senderNostrPubkeyHex = KEY,       // authenticated key — already bound to ADDR_OTHER
                senderAddress = ADDR_NEW,         // CLAIMED (untrusted) address, != ADDR_OTHER, not self
                relayUrl = "wss://relay.example",
                firstMessage = "hi let me in",
                timestampMillis = 1_000L,
                eventId = "evt-1",
            )

        val callerThread = Thread.currentThread()
        val result = runBlocking { vm.acceptMessageRequest(request) }

        // The refusal still fires — the off-main dispatch must not change the accept decision.
        assertEquals(
            "reused NOSTR key already bound to another address must be refused",
            ChatViewModel.AcceptRequestResult.CONFLICT_PUBKEY_REUSED,
            result,
        )
        val recorded = scanThread.get()
        assertNotNull("the pref scan (findPeerByNostrPubkey) must have run", recorded)
        // THE FIX: acceptMessageRequest dispatches off-main via withContext(Dispatchers.IO), so the
        // AES-GCM pref scan does NOT run on the caller (UI) thread. As a plain fun it ran inline on it.
        assertNotEquals(
            "acceptMessageRequest's EncryptedSharedPreferences scan must not run on the caller thread (ANR risk)",
            callerThread,
            recorded,
        )
    }
}
