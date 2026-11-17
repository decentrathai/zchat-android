package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * RetestGap05 — RED regression pin for the key-change guard in
 * ChatViewModel.acceptMessageRequest (ChatViewModel.kt ~line 500).
 *
 * #224 dead-end regression: when the CLAIMED Zcash address is ALREADY bound to a DIFFERENT NOSTR key,
 * accepting the inbound request must REFUSE — leave the request in the inbox and return
 * CONFLICT_KEY_CHANGED WITHOUT binding the new key or creating a conversation. Reverting the guard
 * (letting the method fall through to setPeerNostrPubkey + setConversationMode(OPEN) +
 * getOrCreateConversationId → ACCEPTED) rebinds the address to an impersonating key and makes the UI
 * navigate to a chat that was silently rebound / never legitimately created — the original dead-end.
 *
 * #6 (trust-downgrade): the claimed address is UNAUTHENTICATED (any NOSTR key can claim any victim's
 * public UA), so the refusal must NOT mutate the genuine peer's trust — no setE2EKeyChanged, no
 * setE2EVerified. See AcceptRequestNoThirdPartyDowngradeTest for the dedicated pin of that property.
 *
 * The key-change branch of acceptMessageRequest touches ONLY the injected `zchatPreferences`
 * (isSelfAddress → getPeerNostrPubkey → return); it never reaches any of the ~dozen other fields the
 * constructor wires. ChatViewModel's constructor runs a very heavy
 * init {} (prefs reads + several coroutine collectors) that is irrelevant to this guard and expensive
 * to stand up, so we allocate the instance WITHOUT invoking its constructor (sun.misc.Unsafe, the
 * standard Objenesis instantiation trick — no init {} runs, all fields default to null) and inject only
 * the one field the guard reads. ZchatPreferences (an interface) is a call-recording dynamic Proxy that
 * mirrors the fixture idiom in GroupReconcileIdentityTest. Pure JVM — no Main dispatcher, no
 * viewModelScope, no Android Context.
 */
class RetestGap05AcceptRequestKeyChangeGuardTest {

    private companion object {
        const val PEER = "zs1peer_synthetic"
        val EXISTING_KEY = "a".repeat(64) // already bound to PEER
        val INCOMING_KEY = "b".repeat(64) // the request's authenticated key — DIFFERENT (even ignoring case)
    }

    /**
     * Records every ZchatPreferences call the accept path makes and answers the four reads the
     * key-change branch performs. Anything the (reverted) fall-through path would call is recorded and
     * given a type-correct default so the recording survives up to the point the init-bypassed instance
     * NPEs on a null field — the recorded calls are what proves the revert.
     */
    private fun recordingPrefs(calls: MutableList<String>): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isSelfAddress" -> false
                "getPeerNostrPubkey" -> { calls += "getPeerNostrPubkey"; EXISTING_KEY }
                "setE2EKeyChanged" -> { calls += "setE2EKeyChanged:${args!![0]}:${args[1]}"; null }
                "setE2EVerified" -> { calls += "setE2EVerified:${args!![0]}:${args[1]}"; null }
                "findPeerByNostrPubkey" -> { calls += "findPeerByNostrPubkey"; null }
                "toString" -> "RecordingZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> {
                    calls += method.name
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }
        } as ZchatPreferences

    /** Allocate a ChatViewModel WITHOUT running its (very heavy) constructor, then inject one field. */
    private fun chatViewModelWith(prefs: ZchatPreferences): ChatViewModel {
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
        return vm
    }

    @Test
    fun `accepting a request for an address already bound to a different NOSTR key is refused, not rebound`() {
        val calls = mutableListOf<String>()
        val vm = chatViewModelWith(recordingPrefs(calls))

        val request =
            ZchatPreferences.MessageRequest(
                senderNostrPubkeyHex = INCOMING_KEY,
                senderAddress = PEER,
                relayUrl = "wss://relay.example",
                firstMessage = "hi",
                timestampMillis = 1_000L,
                eventId = "evt-1",
            )

        // On a key-change conflict the guard returns CONFLICT_KEY_CHANGED before the bind/create block.
        // With the guard REVERTED the method falls through and — on this init-bypassed instance — NPEs at
        // the first null field it needs past the removed guard (pendingMessages), but only AFTER it has
        // already called setPeerNostrPubkey / setConversationMode / getOrCreateConversationId. Capture
        // whichever outcome occurs; the recorded calls + result together pin the refusal.
        val result: ChatViewModel.AcceptRequestResult? =
            try {
                runBlocking { vm.acceptMessageRequest(request) }
            } catch (t: Throwable) {
                null
            }

        // (2) the mismatched key must NOT be rebound and NO conversation created — none of these may run.
        assertFalse("MUST NOT rebind the NOSTR key on a key-change conflict", calls.contains("setPeerNostrPubkey"))
        assertFalse("MUST NOT flip the conversation to OPEN on a key-change conflict", calls.contains("setConversationMode"))
        assertFalse("MUST NOT create a conversation on a key-change conflict", calls.contains("getOrCreateConversationId"))
        // (4) the request must stay in the inbox.
        assertFalse("MUST NOT drop the request on a key-change conflict", calls.contains("removeMessageRequest"))
        // (3) #6: the claimed address is UNAUTHENTICATED (any NOSTR key can claim any victim's public UA),
        // so the genuine peer's trust state must NOT be mutated — no key-change banner, no verification wipe.
        assertFalse("must NOT flag the key-change banner from an unauthenticated claimed address", calls.contains("setE2EKeyChanged:$PEER:true"))
        assertFalse("must NOT clear verification from an unauthenticated claimed address", calls.contains("setE2EVerified:$PEER:false"))
        // (1) load-bearing anchor: the refusal result. Reverting the guard makes this ACCEPTED (or throws),
        // never CONFLICT_KEY_CHANGED.
        assertEquals(
            "a claimed address already bound to a different NOSTR key must be refused",
            ChatViewModel.AcceptRequestResult.CONFLICT_KEY_CHANGED,
            result,
        )
    }
}
