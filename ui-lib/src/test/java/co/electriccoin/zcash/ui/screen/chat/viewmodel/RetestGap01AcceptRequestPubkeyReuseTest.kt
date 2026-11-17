package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Retest coverage gap #01 — RED regression pin for the reverse-uniqueness (pubkey-reuse) guard in
 * ChatViewModel.acceptMessageRequest (ChatViewModel.kt:512-519).
 *
 * The guard refuses to accept an inbound OPEN contact request whose authenticated NOSTR key is
 * ALREADY bound to a DIFFERENT Zcash address — the "one attacker key fanned out across many victim
 * addresses" identity-confusion case. Without it, findPeerByNostrPubkey (inbound dispatch + reply
 * routing) would resolve one NOSTR key to an arbitrary one of several bound addresses.
 *
 * Fixture idiom mirrors GroupReconcileIdentityTest: a dynamic Proxy fake for the ~200-method
 * ZchatPreferences seam that answers ONLY the accept-path calls and RECORDS every write, so the test
 * can prove no binding/open/consume happened. ChatViewModel's constructor + init { } fan out into a
 * dozen coroutine collectors and heavy pref reads that are irrelevant to (and would only destabilise)
 * this single guard; per the "shrink rather than over-wire" rule we allocate the VM WITHOUT running
 * its constructor (sun.misc.Unsafe.allocateInstance — verified working on the JDK-17 test JVM) and
 * inject only the one seam the guard reads. This drives the REAL production acceptMessageRequest.
 *
 * RED-on-revert: deleting the guard at ChatViewModel.kt:512-519 lets execution fall through to bind
 * KEY -> ADDR_NEW and open the conversation; the return flips to ACCEPTED (assertion 1) AND, before
 * returning, it dereferences the (allocateInstance-uninitialised) `pendingMessages` field and throws
 * — either outcome fails this test. All addresses/keys are synthetic.
 */
class RetestGap01AcceptRequestPubkeyReuseTest {

    private companion object {
        const val ADDR_OTHER = "usynthetic-other-victim-address"
        const val ADDR_NEW = "usynthetic-new-claimed-address"
        // NOSTR pubkey hex already bound (in the store) to ADDR_OTHER; the incoming request reuses it.
        const val KEY = "aabbccddeeff00112233445566778899"
    }

    /**
     * Records writes and answers the exact ZchatPreferences calls acceptMessageRequest makes;
     * anything unexpected throws loudly. Seeded so KEY is already bound to ADDR_OTHER.
     */
    private class RecordingPrefs {
        val bindings: MutableMap<String, String?> = linkedMapOf<String, String?>(ADDR_OTHER to KEY)
        val modes: MutableMap<String, ConversationMode?> = linkedMapOf<String, ConversationMode?>()
        val removedRequestKeys: MutableList<String> = mutableListOf()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "isSelfAddress" -> false
                    "getPeerNostrPubkey" -> bindings[args!![0] as String]
                    "findPeerByNostrPubkey" -> {
                        val key = args!![0] as String
                        bindings.entries.firstOrNull { it.value?.equals(key, ignoreCase = true) == true }?.key
                    }
                    "setPeerNostrPubkey" -> { bindings[args!![0] as String] = args[1] as String?; null }
                    "setPeerNostrRelay" -> null
                    "setConversationMode" -> { modes[args!![0] as String] = args[1] as ConversationMode?; null }
                    "getConversationModeOrNull" -> modes[args!![0] as String]
                    "getConversationMode" -> modes[args!![0] as String] ?: ConversationMode.VAULT
                    "getPeerKnownOurRotationIndex" -> -1
                    "setPeerKnownOurRotationIndex" -> null
                    "getNostrRotationIndex" -> 0
                    "getOrCreateConversationId" -> Pair("conv-" + (args!![0] as String), true)
                    "getConversationId" -> null
                    "removeMessageRequest" -> { removedRequestKeys.add(args!![0] as String); null }
                    "getMessageRequests" -> emptyList<ZchatPreferences.MessageRequest>()
                    "toString" -> "RecordingZchatPreferences"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call on accept path: ${method.name}"
                    )
                }
            } as ZchatPreferences
    }

    /** Allocate a ChatViewModel WITHOUT running its (init-heavy) constructor. */
    private fun allocateChatViewModel(): ChatViewModel {
        val theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val instance =
            unsafe.javaClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ChatViewModel::class.java)
        return instance as ChatViewModel
    }

    /** Inject the one seam the guard reads (the private final `zchatPreferences` field). */
    private fun injectPrefs(vm: ChatViewModel, prefs: ZchatPreferences) {
        val field = ChatViewModel::class.java.declaredFields.first { it.type == ZchatPreferences::class.java }
        field.isAccessible = true
        field.set(vm, prefs)
    }

    @Test
    fun `reused NOSTR key already bound to another address is REFUSED - no rebind, no open, request kept`() {
        val rec = RecordingPrefs()
        val vm = allocateChatViewModel()
        injectPrefs(vm, rec.proxy)

        val request =
            ZchatPreferences.MessageRequest(
                senderNostrPubkeyHex = KEY,           // authenticated key — already bound to ADDR_OTHER
                senderAddress = ADDR_NEW,             // CLAIMED (untrusted) address, != ADDR_OTHER, not self
                relayUrl = "wss://relay.example",     // non-blank: the revert path never touches NostrRelayPool
                firstMessage = "hi let me in",
                timestampMillis = 1_000L,
                eventId = "evt-1",
            )

        val result = runBlocking { vm.acceptMessageRequest(request) }

        // (1) LOAD-BEARING ANCHOR — the reverse-uniqueness guard returns CONFLICT_PUBKEY_REUSED.
        // Reverting ChatViewModel.kt:512-519 removes this early return: execution falls through to
        // bind KEY -> ADDR_NEW and set OPEN, then returns ACCEPTED (flipping this equality) after
        // NPEing on the uninitialised `pendingMessages` — either way the test goes RED.
        assertEquals(
            ChatViewModel.AcceptRequestResult.CONFLICT_PUBKEY_REUSED,
            result,
        )
        // (2) no new binding was written for the newly-claimed address.
        assertNull("must NOT bind the reused key to the new address", rec.bindings[ADDR_NEW])
        // (3) the conversation was NOT opened for the refused address.
        assertTrue(
            "must NOT open a conversation for the refused address",
            rec.modes[ADDR_NEW] != ConversationMode.OPEN,
        )
        // (4) the pending request is left in place (not consumed) on refusal.
        assertTrue(
            "must NOT remove the pending request on refusal",
            !rec.removedRequestKeys.contains(KEY),
        )
    }
}
