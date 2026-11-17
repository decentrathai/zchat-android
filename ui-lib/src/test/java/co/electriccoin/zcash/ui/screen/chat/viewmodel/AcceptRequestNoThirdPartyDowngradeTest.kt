package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Finding [6] (trust-downgrade) — RED regression pin for the key-change branch of
 * ChatViewModel.acceptMessageRequest (ChatViewModel.kt ~line 500).
 *
 * A gift-wrapped INIT's `senderAddress` is an UNAUTHENTICATED, attacker-controlled claim: any NOSTR key
 * can claim any victim's public Zcash address. When that claimed address is ALREADY bound to a DIFFERENT
 * (genuine) NOSTR key, accepting must still REFUSE (return CONFLICT_KEY_CHANGED, leave the request in the
 * inbox) — but it must NOT mutate the GENUINE peer's trust state. The buggy code called
 * setE2EKeyChanged(addr, true) + setE2EVerified(addr, false), so a remote attacker who merely knows
 * Alice's public address string could weaponise one Accept tap into a "security key changed" banner +
 * verification wipe + send-lockout on Bob's unrelated, unchanged channel with the REAL Alice.
 * NostrChatBridge's own #187 ABSOLUTE RULE (NostrChatBridge.kt ~424) forbids mutating trust from this
 * exact unauthenticated field; accept must match it.
 *
 * Fixture mirrors RetestGap05AcceptRequestKeyChangeGuardTest: Unsafe-allocate ChatViewModel (skip the
 * heavy ctor/init), inject only the recording ZchatPreferences Proxy the key-change branch reads. The
 * branch touches ONLY the prefs seam (isSelfAddress -> getPeerNostrPubkey -> [buggy: setE2EKeyChanged ->
 * setE2EVerified] -> return) and returns before any StateFlow/viewModelScope field, so no other injection
 * is needed. Pure JVM — no Main dispatcher, no Android Context.
 *
 * RED before the fix: the buggy code records setE2EKeyChanged:PEER:true + setE2EVerified:PEER:false, so
 * the two assertFalse below fail. After deleting those two lines the recording is empty of them -> GREEN.
 */
class AcceptRequestNoThirdPartyDowngradeTest {

    private companion object {
        const val PEER = "zs1genuine_peer_address"
        val EXISTING_KEY = "a".repeat(64) // the genuine peer's key, already bound to PEER
        val INCOMING_KEY = "b".repeat(64) // the attacker's authenticated key — DIFFERENT from EXISTING_KEY
    }

    /**
     * Records every ZchatPreferences call the accept path makes and answers the reads the key-change
     * branch performs; every other method gets a type-correct default so a (reverted) fall-through path is
     * still recorded up to the point the init-bypassed instance would NPE on a null field.
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
    fun `key-change conflict must NOT downgrade the genuine peer's trust from an unauthenticated claim`() {
        val calls = mutableListOf<String>()
        val vm = chatViewModelWith(recordingPrefs(calls))

        val request =
            ZchatPreferences.MessageRequest(
                senderNostrPubkeyHex = INCOMING_KEY, // attacker key, != EXISTING_KEY bound to PEER
                senderAddress = PEER,                // claims the genuine peer's address (unauthenticated)
                relayUrl = "wss://relay.example",
                firstMessage = "Hey it's Alice, phone reset",
                timestampMillis = 1_000L,
                eventId = "evt-1",
            )

        val result = runBlocking { vm.acceptMessageRequest(request) }

        // (1) still refused — the accept does not happen and the request stays in the inbox.
        assertEquals(ChatViewModel.AcceptRequestResult.CONFLICT_KEY_CHANGED, result)
        // (2) THE FIX: the genuine peer's trust state is NOT mutated from the unauthenticated claim.
        assertFalse(
            "must NOT flag key-change on the genuine peer from an unauthenticated claimed address",
            calls.contains("setE2EKeyChanged:$PEER:true"),
        )
        assertFalse(
            "must NOT clear the genuine peer's verification from an unauthenticated claimed address",
            calls.contains("setE2EVerified:$PEER:false"),
        )
        // (3) still refused: no rebind / no OPEN flip / no conversation / request not consumed.
        assertFalse("must NOT rebind the NOSTR key on a key-change conflict", calls.contains("setPeerNostrPubkey"))
        assertFalse("must NOT flip the conversation to OPEN on a key-change conflict", calls.contains("setConversationMode"))
        assertFalse("must NOT create a conversation on a key-change conflict", calls.contains("getOrCreateConversationId"))
        assertFalse("must NOT drop the request on a key-change conflict", calls.contains("removeMessageRequest"))
    }
}
