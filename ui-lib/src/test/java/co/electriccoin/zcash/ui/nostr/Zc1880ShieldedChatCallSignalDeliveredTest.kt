package co.electriccoin.zcash.ui.nostr

import co.electriccoin.zcash.ui.call.CallSignalEnvelope
import co.electriccoin.zcash.ui.call.CallSignalType
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ZC-1880 — a voice call in a Shielded (VAULT) chat never rang the callee.
 *
 * The caller offers the call buttons whenever it holds the peer's relay key (ChatDetailView:
 * `conversationMode.supportsCalls || conversation.hasNostrCallChannel`) and publishes RING/OFFER over the
 * relays, as the app text "Calls are still set up over the relays." promises. The receiver,
 * [NostrChatBridge.dispatch], resolved the sender to a known contact by that very relay key and then
 * dropped every ZCALL because `prefs.getConversationMode(peer).supportsCalls` is false for VAULT.
 * The caller rang ~45 s and showed "No answer"; the callee showed nothing.
 *
 * Pure JVM test: ZchatPreferences is a dynamic Proxy answering the reads the known-sender call path does
 * (fixture idiom of [RetestGap14NostrInitFutureDatedWindowTest]).
 *
 * compat-2124: no wire change. [Frozen2124] copies the 2.12.4 receive gate verbatim
 * (`git show v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/ui/nostr/NostrChatBridge.kt`, lines 309-316,
 * `val mode = prefs.getConversationMode(peer); if (mode.supportsCalls) { deliverCallSignal(...) }`) with
 * `supportsCalls` inlined from `git show v2.12.4:.../screen/chat/model/ConversationMode.kt` line 29
 * (`val supportsCalls: Boolean get() = this != VAULT`). It pins the residual: a 2.12.4 callee in a Vault chat
 * still drops the call until it updates.
 */
class Zc1880ShieldedChatCallSignalDeliveredTest {
    private companion object {
        const val PEER_PUBKEY = "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        const val PEER_ADDRESS = "usyntheticpeeraddressnotours"
        const val UNKNOWN_PUBKEY = "1234567812345678123456781234567812345678123456781234567812345678"
        const val CALL_ID = "0123456789abcdef0123456789abcdef"
    }

    private object Frozen2124 {
        // v2.12.4 NostrChatBridge.kt:309-310 (receive gate) with ConversationMode.kt:29 inlined, verbatim.
        fun delivers(mode: ConversationMode): Boolean = mode != ConversationMode.VAULT
    }

    private fun prefs(mode: ConversationMode): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "findPeerByNostrPubkey" -> if ((args!![0] as String) == PEER_PUBKEY) PEER_ADDRESS else null
                "getPeerNostrPubkey" -> if ((args!![0] as String) == PEER_ADDRESS) PEER_PUBKEY else null
                "getConversationMode" -> mode
                "getConversationModeOrNull" -> mode
                "hasSeenNostrEvent" -> false
                "isNostrPubkeyBlocked" -> false
                "isSelfAddress" -> false
                "markNostrEventSeen" -> null
                "resolvePeerAddress" -> args!![0] // no alias: the peer is its own canonical address
                "toString" -> "Zc1880Prefs"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    private fun ringDm(
        eventId: String,
        type: CallSignalType = CallSignalType.RING,
        sender: String = PEER_PUBKEY,
    ): NostrInboxManager.InboundDm =
        NostrInboxManager.InboundDm(
            senderPubkeyHex = sender,
            content = CallSignalEnvelope(CALL_ID, type, "audio").serialize(),
            createdAtSec = System.currentTimeMillis() / 1000,
            eventId = eventId,
            rumorId = "r-$eventId",
        )

    private fun delivered(
        mode: ConversationMode,
        eventId: String,
        type: CallSignalType = CallSignalType.RING,
        sender: String = PEER_PUBKEY,
    ): List<Pair<String, CallSignalEnvelope>> {
        val got = mutableListOf<Pair<String, CallSignalEnvelope>>()
        NostrChatBridge.registerCallSignalHandler { from, env -> got += from to env }
        NostrChatBridge.dispatch(ringDm(eventId, type, sender), prefs(mode))
        return got
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterCallSignalHandler()
    }

    @Test
    fun `a call signal from a known contact in a Shielded chat reaches the call handler`() {
        val got = delivered(ConversationMode.VAULT, "e-zc1880-vault")
        assertEquals("the RING from the contact's relay key must ring in a Shielded chat", 1, got.size)
        assertEquals(PEER_PUBKEY, got.single().first)
        assertEquals(CallSignalType.RING, got.single().second.type)
    }

    @Test
    fun `every call signal type from a known contact in a Shielded chat reaches the call handler`() {
        for (type in CallSignalType.entries) {
            val got = delivered(ConversationMode.VAULT, "e-zc1880-vault-${type.code}", type)
            assertEquals("$type from the contact's relay key must be delivered in a Shielded chat", 1, got.size)
            assertEquals(type, got.single().second.type)
        }
    }

    @Test
    fun `negative control - a call signal from an unknown relay key in a Shielded chat is not delivered`() {
        val got = delivered(ConversationMode.VAULT, "e-zc1880-unknown", sender = UNKNOWN_PUBKEY)
        assertEquals("a ZCALL from a relay key no contact holds must never ring", 0, got.size)
    }

    @Test
    fun `positive control - the same call signal in an Open chat reaches the call handler`() {
        val got = delivered(ConversationMode.OPEN, "e-zc1880-open")
        assertEquals(1, got.size)
    }

    @Test
    fun `residual - a 2_12_4 callee still drops a call in a Vault chat`() {
        assertFalse(Frozen2124.delivers(ConversationMode.VAULT))
    }
}
