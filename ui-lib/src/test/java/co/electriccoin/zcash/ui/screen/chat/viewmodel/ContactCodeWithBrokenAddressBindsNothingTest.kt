package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.UnifiedAddressCheck
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.code
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.1 items 1-2, §1.2): a contact code whose address is cut or changed binds
 * no relay key.
 *
 * At HEAD, New Chat's onRecipientChange parses a pasted "zchat:" code (the Paste code dialog calls it once, on Use
 * code) and binds the code's relay key to the code's address when isValidZcashAddress holds, and that check is only
 * "starts with u1 and is longer than 100 characters". The Fold bound the HONOR's relay key to the first 102 characters
 * of the HONOR's address that way; the HONOR's replies were then routed to that broken copy, a second, unnamed Shielded
 * chat that quoted 34,000 zatoshi for a free contact.
 *
 * Wanted: before any binding, the code's address must pass the pure Bech32m check (UnifiedAddressCheck). A code whose
 * address fails it binds nothing, the field keeps the text the user gave, and the state carries the "address isn't
 * valid" notice. A good code still binds at once (the free Open default reads the store). All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactCodeWithBrokenAddressBindsNothingTest {
    private val vms = mutableListOf<ZchatComposeVM>()

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

    private fun vm(store: RelayKeyStore) = composeVm(store).also { vms += it }

    @Test
    fun `a code whose address is the first 102 characters of a real one binds nothing`() {
        val real = SyntheticUnifiedAddress.mainnet(4_101)
        val cut = real.take(102)
        assertTrue(UnifiedAddressCheck.isMainnetUnifiedAddress(real), "fixture: the full address passes the check")
        assertFalse(UnifiedAddressCheck.isMainnetUnifiedAddress(cut), "fixture: the cut address fails the check")
        val store = RelayKeyStore()
        val vm = vm(store)
        val pasted = code(cut, relayKey(1))

        vm.ready().onRecipientChange(pasted)

        assertNull(store.pubkeys[cut], "the relay key was bound to a cut address")
        assertNull(store.proxy.findPeerByNostrPubkey(relayKey(1)), "the relay key is bound to some address")
        assertFalse(store.writes.any { it.startsWith("setPeerNostrPubkey") }, "a relay key was written: ${store.writes}")
        assertFalse(vm.ready().openAvailable, "Open is offered for a code that bound nothing")
        assertEquals(pasted, vm.ready().recipientAddress, "the field does not keep the text the user gave")
        assertFalse(vm.ready().isValidAddress, "Send is enabled for a cut address")
        assertEquals("AddressInvalid", noticeKind(vm.ready()), "no 'address isn't valid' notice")
    }

    @Test
    fun `a code whose address has one character changed binds nothing`() {
        val changed = SyntheticUnifiedAddress.mainnetBadChecksum(4_102)
        val store = RelayKeyStore()
        val vm = vm(store)

        vm.ready().onRecipientChange(code(changed, relayKey(2)))

        assertNull(store.pubkeys[changed], "the relay key was bound to an address whose checksum fails")
        assertFalse(store.writes.any { it.startsWith("setPeerNostrPubkey") }, "a relay key was written: ${store.writes}")
        assertEquals("AddressInvalid", noticeKind(vm.ready()), "no 'address isn't valid' notice")
    }

    @Test
    fun `a good code still binds at once and defaults the new chat to free Open (guard)`() {
        val good = SyntheticUnifiedAddress.mainnet(4_103)
        val store = RelayKeyStore()
        val vm = vm(store)

        vm.ready().onRecipientChange(code(good, relayKey(3)))

        assertEquals(relayKey(3), store.pubkeys[good], "a good code did not bind its relay key")
        assertEquals(RelayKeyBinding.RELAY, store.relays[good], "a good code did not store its relay")
        assertEquals(good, vm.ready().recipientAddress)
        assertTrue(vm.ready().isFreeOpenSend, "a good code does not default to the free Open send")
        assertNull(noticeKind(vm.ready()), "a good code shows a notice")
    }
}
