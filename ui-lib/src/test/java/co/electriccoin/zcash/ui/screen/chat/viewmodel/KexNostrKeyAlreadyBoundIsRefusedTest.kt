package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.1 item 3, §1.3 step 3): a verified key exchange never binds a relay key
 * that already belongs to another address.
 *
 * At HEAD, applyKEXNostr (a KEX or KEXACK that carries the sender's relay key) compares only with the key stored for
 * the SAME address, then binds it and flushes that chat's queued Tunnel messages. A KEX from address B carrying the
 * relay key already bound to A (another address of the same contact, e.g. after Change Identity) puts one key on two
 * addresses, and every relay message of that key is then routed to one of them by hash order.
 *
 * Wanted (as routeIncomingBoot already does): nothing is bound for B, nothing is flagged on A, and B's queued Tunnel
 * messages are not flushed; the key exchange itself is not touched. The ViewModel is allocated without its
 * constructor (the RouteBootPubkeyReuseTest idiom) with the two fields this path reads. All values are synthetic.
 */
class KexNostrKeyAlreadyBoundIsRefusedTest {
    private val a = SyntheticUnifiedAddress.mainnet(4_401)
    private val b = SyntheticUnifiedAddress.mainnet(4_402)
    private val key = relayKey(41)
    private val placeholderId = "tunnel-wait-synthetic-m1"

    private fun vmOver(store: RelayKeyStore): Pair<ChatViewModel, MutableStateFlow<List<ChatMessage>>> {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val vm = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
        val pending =
            MutableStateFlow(
                listOf(
                    ChatMessage(
                        id = placeholderId,
                        txId = null,
                        text = "waiting for secure connection",
                        timestamp = Instant.EPOCH,
                        isOutgoing = true,
                        peerAddress = b,
                        isPending = true,
                        status = MessageStatus.SENDING,
                    ),
                ),
            )
        for ((name, value) in listOf("zchatPreferences" to store.proxy, "pendingMessages" to pending)) {
            ChatViewModel::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(vm, value)
        }
        return vm to pending
    }

    private fun applyKexNostr(
        vm: ChatViewModel,
        peer: String,
        pubkey: String,
    ) {
        try {
            ChatViewModel::class.java
                .getDeclaredMethod("applyKEXNostr", String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(vm, peer, pubkey, RelayKeyBinding.RELAY)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `a KEX from another address carrying a key bound elsewhere binds nothing and flushes nothing`() {
        val store =
            RelayKeyStore().apply {
                pubkeys[a] = key
                relays[a] = RelayKeyBinding.RELAY
            }
        val (vm, pending) = vmOver(store)

        applyKexNostr(vm, b, key)

        assertNull(store.pubkeys[b], "the KEX bound a relay key that belongs to another address")
        assertEquals(a, store.proxy.findPeerByNostrPubkey(key), "the key no longer resolves to the first address")
        assertFalse(store.wrote("setPeerNostrPubkey", b), "a relay key was written for the KEX sender: ${store.writes}")
        assertFalse(store.wrote("setPeerNostrRelay", b), "a relay was written for the KEX sender: ${store.writes}")
        assertFalse(store.wrote("setE2EKeyChanged", a), "the first address was flagged: ${store.writes}")
        assertFalse(store.wrote("setE2EVerified", a), "the first address lost its verified mark: ${store.writes}")
        assertTrue(
            pending.value.any { it.id == placeholderId },
            "the KEX sender's queued Tunnel messages were flushed although no relay key was bound",
        )
    }

    @Test
    fun `a KEX carrying a key bound to no one binds it and flushes (guard)`() {
        val store = RelayKeyStore()
        val (vm, pending) = vmOver(store)

        applyKexNostr(vm, b, key)

        assertEquals(key, store.pubkeys[b])
        assertEquals(RelayKeyBinding.RELAY, store.relays[b])
        assertFalse(pending.value.any { it.id == placeholderId }, "the queued placeholders were not flushed")
    }
}
