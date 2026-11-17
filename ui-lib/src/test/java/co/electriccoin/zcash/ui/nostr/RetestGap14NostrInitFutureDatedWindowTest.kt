package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * #224 freshness-window RED pin — [NostrChatBridge.routeUnknownPubkeyAsRequest] (NostrChatBridge.kt:399).
 *
 * A first-contact ZMSG v4 INIT from an UNKNOWN NOSTR pubkey is held as a Message Request only if it is
 * inside a TWO-SIDED freshness window. The load-bearing clause is the FUTURE-dated half:
 *
 *     if (ageSec > CHAT_MSG_MAX_AGE_SEC || ageSec < -CALL_SIGNAL_MAX_AGE_SEC) { ...; return }
 *
 * `ageSec = now - dm.createdAtSec`, so a forged / clock-skewed created_at set far in the FUTURE makes
 * ageSec strongly NEGATIVE. The old one-sided (`ageSec > MAX`) check treated negatives as "fresh" and
 * let a future-dated INIT sail through into a stored request. The `|| ageSec < -CALL_SIGNAL_MAX_AGE_SEC`
 * clause rejects it.
 *
 * This is a pure JVM test: [NostrChatBridge] is a stateless singleton (coroutine flows + atomics only,
 * no Android at class-init), dispatch()'s android.util.Log calls are neutralised by the ui-lib unit-test
 * lane's `unitTests.isReturnDefaultValues = true`, and ZchatPreferences is a dynamic Proxy answering only
 * the reads the unknown-pubkey INIT path performs (fixture idiom mirrors GroupReconcileIdentityTest).
 */
class RetestGap14NostrInitFutureDatedWindowTest {

    private companion object {
        // 8 chars, all within ZMSGConstants.CONV_ID_CHARS — a well-formed v4 convId.
        const val CONV_ID = "ABCD1234"

        // A NOSTR pubkey the fake prefs does NOT recognise → the unknown-sender request path.
        const val UNKNOWN_PUBKEY = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"

        // The CLAIMED (unverified) Zcash address inside the INIT body — synthetic, not "ours".
        const val CLAIMED_ADDRESS = "usyntheticpeeraddressnotours"
    }

    /**
     * Records every addMessageRequest(...) — the load-bearing side effect the freshness guard gates —
     * and answers exactly the reads routeUnknownPubkeyAsRequest performs; anything else throws loudly.
     */
    private class RecordingPrefs {
        val stored = mutableListOf<ZchatPreferences.MessageRequest>()

        fun asProxy(): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "findPeerByNostrPubkey" -> null // unknown sender → routeUnknownPubkeyAsRequest
                    "isNostrPubkeyBlocked" -> false
                    "hasSeenNostrEvent" -> false
                    "isSelfAddress" -> false
                    "getConversationId" -> null
                    "markNostrEventSeen" -> null
                    "addMessageRequest" -> {
                        stored += args!![0] as ZchatPreferences.MessageRequest
                        null
                    }
                    "toString" -> "RecordingPrefs"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call in unknown-pubkey INIT path: ${method.name}",
                    )
                }
            } as ZchatPreferences
    }

    private fun initDm(createdAtSec: Long, eventId: String): NostrInboxManager.InboundDm =
        NostrInboxManager.InboundDm(
            senderPubkeyHex = UNKNOWN_PUBKEY,
            content = ZMSGProtocol.createV4InitMessage(CONV_ID, CLAIMED_ADDRESS, "hi"),
            createdAtSec = createdAtSec,
            eventId = eventId,
            rumorId = "r",
        )

    @Test
    fun `future-dated INIT from an unknown pubkey is REJECTED, not stored as a contact request`() {
        val prefs = RecordingPrefs()
        val nowSec = System.currentTimeMillis() / 1000
        // created_at one hour in the FUTURE → ageSec ≈ -3600 s, i.e. < -CALL_SIGNAL_MAX_AGE_SEC (-120 s).
        NostrChatBridge.dispatch(initDm(createdAtSec = nowSec + 3600, eventId = "e-future"), prefs.asProxy())

        // RED-ON-REVERT ANCHOR: delete `|| ageSec < -CALL_SIGNAL_MAX_AGE_SEC` at NostrChatBridge.kt:399 and
        // the future-dated INIT sails past the one-sided (age > MAX) check into addMessageRequest → size == 1.
        assertEquals(
            "future-dated INIT must be dropped, never surfaced as a stored contact request",
            0,
            prefs.stored.size,
        )
    }

    @Test
    fun `positive control - a FRESH in-window INIT from an unknown pubkey IS stored as a request`() {
        val prefs = RecordingPrefs()
        val nowSec = System.currentTimeMillis() / 1000
        // created_at == now → ageSec ≈ 0, inside the ±window on BOTH sides.
        NostrChatBridge.dispatch(initDm(createdAtSec = nowSec, eventId = "e-fresh"), prefs.asProxy())

        assertEquals("a fresh in-window INIT must become exactly one contact request", 1, prefs.stored.size)
        assertEquals(
            "the stored request must carry the INIT's claimed address",
            CLAIMED_ADDRESS,
            prefs.stored.single().senderAddress,
        )
    }
}
