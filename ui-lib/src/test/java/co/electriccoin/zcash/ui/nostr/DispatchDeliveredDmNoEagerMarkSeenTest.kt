package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * #9 permanent-DM-loss RED pin — [NostrChatBridge.dispatch] post-emit branch.
 *
 * Pre-fix, dispatch marked a DM's eventId seen the instant it emitted to the replay=0 inbound SharedFlow while
 * `subscriptionCount` was a SNAPSHOT > 0 — but a collector can cancel before draining the buffered value, which
 * (replay=0) then vanishes yet is now persisted as seen, so the relay's later replay is suppressed downstream =
 * PERMANENT loss. The fix moves receipt-marking to the ChatViewModel consumer (proof the value was actually
 * received). This drives the REAL dispatch with a live collector (exactly the emitted && sub>0 branch the eager
 * mark lived in) and asserts dispatch itself does NOT call markNostrEventSeen on the delivered-DM path.
 *
 * Pure JVM: [NostrChatBridge] is a coroutine-flow/atomic singleton (no Android at class-init), dispatch()'s
 * android.util.Log is neutralised by ui-lib's `unitTests.isReturnDefaultValues = true`, and ZchatPreferences is
 * a dynamic Proxy answering only the reads the known-peer chat path performs (idiom mirrors
 * RetestGap14NostrInitFutureDatedWindowTest).
 */
class DispatchDeliveredDmNoEagerMarkSeenTest {

    private companion object {
        const val SENDER_PUBKEY = "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd"
        const val PEER_ADDRESS = "u1knownpeeraddress"
    }

    /** Records markNostrEventSeen(...) and answers only the reads the delivered known-peer chat path performs. */
    private class RecordingPrefs {
        val markedSeen = mutableListOf<String>()

        fun asProxy(): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "findPeerByNostrPubkey" -> PEER_ADDRESS // known sender → normal chat path
                    "hasSeenNostrEvent" -> false
                    "markNostrEventSeen" -> {
                        markedSeen += args!![0] as String
                        null
                    }
                    "toString" -> "RecordingPrefs"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call in delivered-DM path: ${method.name}",
                    )
                }
            } as ZchatPreferences
    }

    private fun chatDm(createdAtSec: Long, eventId: String): NostrInboxManager.InboundDm =
        NostrInboxManager.InboundDm(
            senderPubkeyHex = SENDER_PUBKEY,
            content = "hello from a known peer",
            createdAtSec = createdAtSec,
            eventId = eventId,
            rumorId = "r",
        )

    @Test
    fun `dispatch does NOT mark a delivered DM seen — receipt-marking is the consumer's job (#9)`() =
        runBlocking {
            val prefs = RecordingPrefs()
            val received = mutableListOf<NostrChatBridge.InboundChat>()

            // A real, live collector on the bridge's inbound SharedFlow. UNDISPATCHED runs the collector
            // synchronously up to its first suspension, so the subscriber is REGISTERED (subscriptionCount > 0)
            // before dispatch runs — i.e. dispatch takes exactly the `emitted && sub>0` hand-off branch the
            // eager mark-seen lived in. (subscriptionCount is a MutableSharedFlow member, not visible on the
            // read-only `inbound`, so delivery of the value below is what proves sub>0 held at emit time.)
            val collector =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    NostrChatBridge.inbound.collect { received += it }
                }

            val nowSec = System.currentTimeMillis() / 1000
            NostrChatBridge.dispatch(chatDm(createdAtSec = nowSec, eventId = "evt-delivered"), prefs.asProxy())

            var guard = 0
            while (received.isEmpty() && guard++ < 10_000) yield()

            // Proof the value took the emitted && sub>0 hand-off path (not overflow / no-collector).
            assertEquals("the DM must be handed to the live collector", 1, received.size)
            assertEquals(PEER_ADDRESS, received.single().peerAddress)

            // RED-ON-REVERT ANCHOR: pre-fix, dispatch calls prefs.markNostrEventSeen(eventId) here from a
            // liveness SNAPSHOT — the #9 permanent-loss bug. The fix removes it (consumer marks on receipt).
            assertEquals(
                "dispatch must NOT mark a delivered DM seen — receipt-marking moved to the ChatViewModel consumer",
                emptyList<String>(),
                prefs.markedSeen,
            )

            collector.cancel()
        }
}
