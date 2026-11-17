package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
 * M1 fixup (train T4b cross review, finding G-M1-2), on New Chat: the contact's full code is accepted when the only
 * holder of its relay key is a cut copy of the address.
 *
 * A 2.12.4 paste of a code cut to 102 characters left the relay key bound to that cut copy alone. At the M1 fix, New
 * Chat then refuses the contact's full code with "this code's key already belongs to" the cut copy, and "Open that chat"
 * opens the broken chat, so the real address can never get the key.
 *
 * Wanted: the full code binds the real address, the recipient is set to it, Send is on, and no notice is shown. All
 * values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactCodeAfterCutCopyBindsRealAddressTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val real = SyntheticUnifiedAddress.mainnet(4_301)
    private val cut = real.take(102)
    private val key = relayKey(31)

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

    @Test
    fun `the full code binds the real address when the key's only holder is the cut copy`() {
        val store =
            RelayKeyStore().apply {
                pubkeys[cut] = key
                relays[cut] = RelayKeyBinding.RELAY
            }
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(code(real, key))

        assertNull(noticeKind(vm.ready()), "the full code was refused with a notice naming the cut copy")
        assertEquals(key, store.pubkeys[real], "the real address did not get the relay key: ${store.writes}")
        assertEquals(real, vm.ready().recipientAddress, "the recipient is not the real address")
        assertTrue(vm.ready().isValidAddress, "Send is off for the contact's full code")
        assertFalse(store.wrote("setE2EKeyChanged", real), "the real address was flagged: ${store.writes}")
    }
}
