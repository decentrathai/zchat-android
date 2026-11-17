package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ZC-1402 (swarm 1 Nov, lens hostile-memo) — accepting a relay message request that CLAIMS an existing contact's address
 * binds the stranger's relay key to that contact and switches the chat to Open.
 *
 * The claimed address in an INIT is unauthenticated: anyone who knows a contact's public address can name it.
 * ChatViewModel.acceptMessageRequest refuses only our own address, an address already bound to a DIFFERENT relay key and
 * a relay key already bound to another address. For an existing Tunnel contact (conversation, E2E key, stored mode
 * TUNNEL) that has no relay key bound yet, it falls through to setPeerNostrPubkey + setPeerNostrRelay +
 * setConversationMode(OPEN): one Accept tap hands that chat's relay transport to the stranger and drops it to Open.
 *
 * Wanted: such a request stays a separate request, or is refused with a clear result; the existing contact's relay key,
 * relay and conversation mode are untouched, and the result is not ACCEPTED.
 *
 * Seam: a ChatViewModel allocated without its constructor (as RetestGap05) with only `zchatPreferences` injected, a
 * recording Proxy. The fall-through past a removed guard NPEs on a null field after the binding calls, which are what is
 * asserted. Every key and address is synthetic.
 */
class AcceptRequestClaimingExistingContactTest {
    private companion object {
        const val CONTACT = "usyntheticzc1402existingtunnelcontact"
        const val CONV_ID = "conv-zc1402"
        val E2E_PEER_KEY = "e2".repeat(32)
        val STRANGER_KEY = "5e".repeat(32)
    }

    @Test
    fun `a request claiming an existing Tunnel contact's address does not bind the stranger's key nor switch to Open`() {
        val calls = mutableListOf<String>()
        val vm = chatViewModelWith(existingContactPrefs(calls))
        val request =
            ZchatPreferences.MessageRequest(
                senderNostrPubkeyHex = STRANGER_KEY,
                senderAddress = CONTACT,
                relayUrl = "wss://relay.synthetic.invalid",
                firstMessage = "hi, it's me on a new phone",
                timestampMillis = 1_000L,
                eventId = "evt-zc1402",
                rumorId = "rumor-zc1402",
            )

        val result: ChatViewModel.AcceptRequestResult? =
            try {
                runBlocking { vm.acceptMessageRequest(request) }
            } catch (t: Throwable) {
                null
            }

        assertFalse(
            "the stranger's relay key was bound to the existing contact: $calls",
            calls.any { it.startsWith("setPeerNostrPubkey:") },
        )
        assertFalse("the existing contact's relay was replaced: $calls", calls.any { it.startsWith("setPeerNostrRelay:") })
        assertFalse(
            "the existing contact's chat was switched to Open: $calls",
            calls.contains("setConversationMode:$CONTACT:${ConversationMode.OPEN}"),
        )
        assertNotEquals(
            "a request claiming an existing contact's address was ACCEPTED onto that contact",
            ChatViewModel.AcceptRequestResult.ACCEPTED,
            result,
        )
    }

    /** An existing Tunnel contact: conversation, E2E key and TUNNEL mode stored; no relay key bound yet. */
    private fun existingContactPrefs(calls: MutableList<String>): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "isSelfAddress" -> false
                "getPeerNostrPubkey" -> null
                "findPeerByNostrPubkey" -> null
                "getConversationId" -> if (a[0] == CONTACT) CONV_ID else null
                "getConversationMode" -> ConversationMode.TUNNEL
                "getE2EPeerPublicKey" -> if (a[0] == CONTACT) E2E_PEER_KEY else null
                "setPeerNostrPubkey", "setPeerNostrRelay", "setConversationMode" -> {
                    calls += "${method.name}:${a[0]}:${a[1]}"
                    null
                }
                "toString" -> "ZC1402Prefs"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a.getOrNull(0)
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

    private fun chatViewModelWith(prefs: ZchatPreferences): ChatViewModel {
        val unsafe =
            Class
                .forName("sun.misc.Unsafe")
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
}
