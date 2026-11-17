package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.SelfAddressRegistry
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
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
 * T4b2 cross review, finding T4b2-G1 (on the M1 fixup ee53bce5b): a received KEX or KEXACK never takes a relay key
 * from a cut copy of an address. Only the user's own paste or scan of the contact's full code does.
 *
 * At ee53bce5b, bindPeerNostrKey skips the "bound elsewhere" refusal when the only holder of the key K is a copy of an
 * address that fails NostrPeerBinding.isBindable (2.12.4's paste check let a contact code cut to 102 characters bind K
 * to that cut copy T). applyKEXNostr (a KEX or KEXACK that carries the sender's relay key) calls the same bind, and the
 * relay key does not sign the KEX: it is only named in a memo field. So a stranger's first KEX from any valid address
 * B that names K binds K to B, K routes to B at once (the contact's relay messages show in chat B), the one-time
 * repair is re-armed, and at the next start it unbinds T and moves the contact's delivered relay rows into chat B.
 * From then on the contact's real code is refused, because a valid address (B) holds K.
 *
 * Wanted: a KEX from B, or even from the full address T was cut from, binds nothing, flushes nothing, re-arms nothing,
 * and T keeps K and its rows; the paste and the scan of the full code still bind (ContactCodeAfterCutCopyBinds-
 * RealAddressTest, ScannedFullCodeAfterCutCopyBindsRealAddressTest). The ViewModel is allocated without its constructor
 * (the KexNostrKeyAlreadyBoundIsRefusedTest idiom) with the two fields this path reads. All values are synthetic.
 */
class KexDoesNotTakeRelayKeyFromCutCopyTest {
    private val contact = SyntheticUnifiedAddress.mainnet(57_001)
    private val cut = contact.take(102)
    private val stranger = SyntheticUnifiedAddress.mainnet(57_002)
    private val key = relayKey(701)
    private val note = "synthetic rows-moved note"

    private fun placeholder(peer: String) =
        ChatMessage(
            id = "tunnel-wait-synthetic-g1-${peer.takeLast(6)}",
            txId = null,
            text = "waiting for secure connection",
            timestamp = Instant.EPOCH,
            isOutgoing = true,
            peerAddress = peer,
            isPending = true,
            status = MessageStatus.SENDING,
        )

    private fun vmOver(
        prefs: ZchatPreferences,
        peer: String,
    ): Pair<ChatViewModel, MutableStateFlow<List<ChatMessage>>> {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val vm = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
        val pending = MutableStateFlow(listOf(placeholder(peer)))
        for ((name, value) in listOf("zchatPreferences" to prefs, "pendingMessages" to pending)) {
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

    private fun cutCopyStore() =
        RelayKeyStore().apply {
            pubkeys[cut] = key
            relays[cut] = RelayKeyBinding.RELAY
        }

    @Test
    fun `a stranger's KEX naming a key held only by a cut copy binds nothing and flushes nothing`() {
        val store = cutCopyStore()
        val (vm, pending) = vmOver(store.proxy, stranger)

        applyKexNostr(vm, stranger, key)

        assertNull(store.pubkeys[stranger], "a KEX took the contact's relay key from the cut copy for the stranger")
        assertFalse(store.wrote("setPeerNostrPubkey", stranger), "a relay key was written for the KEX sender: ${store.writes}")
        assertFalse(store.wrote("setPeerNostrRelay", stranger), "a relay was written for the KEX sender: ${store.writes}")
        assertEquals(cut, store.proxy.findPeerByNostrPubkey(key), "the contact's relay messages no longer route to the cut copy")
        assertTrue(
            pending.value.any { it.peerAddress == stranger },
            "the KEX sender's queued Tunnel messages were flushed although no relay key was bound",
        )
    }

    @Test
    fun `a KEX from the full address the cut copy was cut from binds nothing either (the paste or scan does it)`() {
        val store = cutCopyStore()
        val (vm, _) = vmOver(store.proxy, contact)

        applyKexNostr(vm, contact, key)

        assertNull(store.pubkeys[contact], "a KEX took a relay key from a cut copy; only the user's paste or scan may")
        assertEquals(cut, store.proxy.findPeerByNostrPubkey(key))
    }

    @Test
    fun `after a stranger's KEX the next start's repair leaves the contact's rows and binding on the cut copy`() {
        val (prefs, files) = realPreferences()
        ZchatPreferencesImpl::class.java
            .getDeclaredField("selfAddresses\$delegate")
            .apply { isAccessible = true }
            .set(prefs, lazyOf(SelfAddressRegistry(MemorySharedPreferences())))
        files
            .getValue("modePrefs")
            .edit()
            .putString("pubkey:$cut", key)
            .commit()
        val delivered =
            ZchatPreferences.PendingMessageData(
                id = "nmsg-g1-cut-in-1",
                text = "synthetic contact reply",
                timestampMillis = 1_000,
                peerAddress = cut,
                isOutgoing = false,
                isPending = false,
                status = MessageStatus.SENT.name,
            )
        prefs.addPendingMessage(delivered)
        assertEquals(0, prefs.repairDoubleBoundNostrKeysOnce { note }, "fixture: M1's first repair moved rows of a lone binding")
        val (vm, _) = vmOver(prefs, stranger)

        applyKexNostr(vm, stranger, key)
        val moved = prefs.repairDoubleBoundNostrKeysOnce { note }

        assertEquals(-1, moved, "a KEX re-armed the relay-key repair (it moved $moved row(s) at the next start)")
        assertNull(prefs.getPeerNostrPubkey(stranger), "the stranger holds the contact's relay key")
        assertEquals(key, prefs.getPeerNostrPubkey(cut), "the cut copy lost the contact's relay key to a KEX")
        assertEquals(cut, prefs.findPeerByNostrPubkey(key))
        assertEquals(
            delivered,
            prefs.getPendingMessages().singleOrNull { it.id == delivered.id },
            "the contact's delivered relay row was moved into the stranger's chat",
        )
        assertFalse(prefs.getPendingMessages().any { it.peerAddress == stranger }, "a row was written in the stranger's chat")
    }
}
