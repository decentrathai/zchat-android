package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * RED regression pin for the self-spoof guard in [ChatViewModel.acceptMessageRequest]
 * (ChatViewModel.kt ~line 493):
 *
 *     if (zchatPreferences.isSelfAddress(addr)) {
 *         zchatPreferences.removeMessageRequest(request.senderNostrPubkeyHex)
 *         ...
 *         return AcceptRequestResult.REJECTED_SELF
 *     }
 *
 * This defense-in-depth guard closes the cold-start TOCTOU where a first-contact INIT arrives
 * claiming OUR OWN Zcash address before the self-address registry is populated. Accepting such a
 * request must NEVER bind a peer NOSTR key to our address nor flip the conversation to OPEN — it
 * must drop the spoof request and return REJECTED_SELF. Reverting the guard lets the flow fall
 * through to setPeerNostrPubkey / setConversationMode(OPEN) and return ACCEPTED, reddening this test.
 *
 * ChatViewModel's constructor runs a heavy init{} (16 injected deps + many collectors) irrelevant to
 * this pure guard, whose self-spoof path touches ONLY the `zchatPreferences` and `_messageRequests`
 * fields. So — mirroring the neighbouring RetestGap06ReplyRefCodecTest idiom — we allocate a bare
 * ChatViewModel via sun.misc.Unsafe reached BY REFLECTION NAME (no compile-time sun.misc dependency,
 * and the ctor / init{} never run), inject just those two fields by reflection, then invoke the
 * public method. ZchatPreferences (~200 methods) is a dynamic Proxy that records every call and
 * answers isSelfAddress(addr)=true. All addresses/ids are synthetic.
 */
class RetestGap11AcceptSelfSpoofTest {

    private data class Call(val name: String, val args: List<Any?>)

    @Test
    fun `acceptMessageRequest rejects a request claiming our OWN address without binding`() {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf<Call>())
        val selfAddress = "usynthetic-self-address"

        val prefs =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                val a: List<Any?> = args?.toList() ?: emptyList()
                calls.add(Call(method.name, a))
                when (method.name) {
                    // The spoof: the claimed address IS our own.
                    "isSelfAddress" -> (a[0] as String) == selfAddress
                    "getMessageRequests" -> emptyList<ZchatPreferences.MessageRequest>()
                    "removeMessageRequest" -> null
                    // Downstream binding reads/writes — must NOT be reached at HEAD. Answered with
                    // benign values only so a REVERTED build fails on the ASSERTIONS below (return
                    // != REJECTED_SELF, binding calls recorded) rather than an incidental exception.
                    "getPeerNostrPubkey" -> null
                    "findPeerByNostrPubkey" -> null
                    "getPeerKnownOurRotationIndex" -> 0
                    "getNostrRotationIndex" -> 0
                    "getOrCreateConversationId" -> "conv-x"
                    "setPeerNostrPubkey",
                    "setPeerNostrRelay",
                    "setConversationMode",
                    "setE2EKeyChanged",
                    "setE2EVerified",
                    "setPeerKnownOurRotationIndex",
                    "addPendingMessage",
                    -> null
                    "toString" -> "FakeZchatPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === a.getOrNull(0)
                    else ->
                        throw UnsupportedOperationException(
                            "unexpected ZchatPreferences call in accept-self-spoof path: ${method.name}",
                        )
                }
            } as ZchatPreferences

        // Bare ChatViewModel: no constructor, no init{}. Unsafe reached by reflection name so there is
        // no compile-time dependency on sun.misc (mirrors RetestGap06ReplyRefCodecTest).
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val vm = allocate.invoke(unsafe, ChatViewModel::class.java) as ChatViewModel

        // Only the two fields the guard reads.
        setField(vm, "zchatPreferences", prefs)
        setField(vm, "_messageRequests", MutableStateFlow(emptyList<ZchatPreferences.MessageRequest>()))

        val request =
            ZchatPreferences.MessageRequest(
                senderNostrPubkeyHex = "aa".repeat(32),
                senderAddress = selfAddress, // spoof: claims OUR OWN address
                relayUrl = "wss://relay.example",
                firstMessage = "hi",
                timestampMillis = 1_000L,
                eventId = "evt-spoof-1",
            )

        val result = runBlocking { vm.acceptMessageRequest(request) }

        // (1) Load-bearing anchor: the guard refuses with REJECTED_SELF. Reverting the guard makes
        //     the flow fall through to a successful bind and return ACCEPTED.
        assertEquals(ChatViewModel.AcceptRequestResult.REJECTED_SELF, result)

        // (2) NO binding side effects: the peer key is never bound and the chat is never opened.
        //     Reverting the guard fires both of these.
        assertFalse(
            "self-spoof must NOT bind a peer NOSTR pubkey to our own address",
            calls.any { it.name == "setPeerNostrPubkey" },
        )
        assertFalse(
            "self-spoof must NOT flip the conversation to OPEN",
            calls.any { it.name == "setConversationMode" },
        )

        // (3) The spoof request is dropped (removed by its authenticated sender pubkey).
        assertTrue(
            "self-spoof request must be removed by senderNostrPubkeyHex",
            calls.any {
                it.name == "removeMessageRequest" && it.args.firstOrNull() == request.senderNostrPubkeyHex
            },
        )
    }

    /** Set an instance (final) reference field by name, bypassing the constructor/init. */
    private fun setField(target: Any, name: String, value: Any?) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
