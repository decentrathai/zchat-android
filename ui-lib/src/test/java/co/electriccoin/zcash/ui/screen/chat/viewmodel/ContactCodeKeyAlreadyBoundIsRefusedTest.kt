package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.code
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.noticeField
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.noticeKind
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.ready
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.1 item 3, §1.3 step 3, §5 R1-3): a contact code whose relay key already
 * belongs to another address binds nothing and does not start a new chat.
 *
 * At HEAD, storePeerNostrFromCode only compares with the key already stored for the SAME address, so a code carrying
 * a valid address B and the relay key already bound to A binds B too. One key then sits on two addresses, and
 * findPeerByNostrPubkey (every relay message's routing) picks one of them by hash order; the recipient is set to B, so
 * the user starts a new chat with B (a contact who used Change Identity becomes a second, possibly paid chat).
 *
 * Wanted: B is not bound, the key still resolves to A, A is not flagged, the recipient is not set to B (Send stays off
 * for this code), and the state carries the "already belongs to" notice naming A's contact, or A shortened to its
 * first 10 and last 6 characters. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactCodeKeyAlreadyBoundIsRefusedTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val a = SyntheticUnifiedAddress.mainnet(4_201)
    private val b = SyntheticUnifiedAddress.mainnet(4_202)
    private val key = relayKey(21)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        vms.forEach(RelayKeyBinding::close)
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun storeWithABound() =
        RelayKeyStore().apply {
            pubkeys[a] = key
            relays[a] = RelayKeyBinding.RELAY
        }

    @Test
    fun `a code with another valid address and a key bound to a contact binds nothing and names the contact`() {
        val store = storeWithABound()
        val vm = composeVm(store, contacts = listOf(Contact(a, "Synthetic Alice", Instant.EPOCH))).also { vms += it }

        vm.ready().onRecipientChange(code(b, key))

        assertNull(store.pubkeys[b], "the key was bound to a second address")
        assertEquals(a, store.proxy.findPeerByNostrPubkey(key), "the key no longer resolves to the first address")
        assertFalse(store.writes.any { it.startsWith("setPeerNostrPubkey") }, "a relay key was written: ${store.writes}")
        assertFalse(store.wrote("setE2EKeyChanged", a), "the first address was flagged: ${store.writes}")
        assertFalse(store.wrote("setE2EKeyChanged", b), "the second address was flagged: ${store.writes}")
        assertNotEquals(b, vm.ready().recipientAddress, "the recipient was set to the second address")
        assertFalse(vm.ready().isValidAddress, "Send is enabled for a code whose key belongs to another chat")
        assertEquals("KeyAlreadyBound", noticeKind(vm.ready()), "no 'already belongs to' notice")
        assertEquals("Synthetic Alice", noticeField(vm.ready(), "displayName"), "the notice does not name the contact")
        assertEquals(a, noticeField(vm.ready(), "existingPeer"), "the notice's action does not open the existing chat")
    }

    @Test
    fun `without a saved contact the notice names the other address shortened`() {
        val store = storeWithABound()
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(code(b, key))

        assertNull(store.pubkeys[b], "the key was bound to a second address")
        assertEquals("KeyAlreadyBound", noticeKind(vm.ready()), "no 'already belongs to' notice")
        assertEquals("${a.take(10)}…${a.takeLast(6)}", noticeField(vm.ready(), "displayName"))
    }

    @Test
    fun `the same code for the address the key already belongs to is unchanged (guard)`() {
        val store = storeWithABound()
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(code(a, key))

        assertEquals(key, store.pubkeys[a])
        assertEquals(a, vm.ready().recipientAddress)
        assertNull(noticeKind(vm.ready()), "the contact's own code shows a notice")
    }
}
