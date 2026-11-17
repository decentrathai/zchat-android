package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.code
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.3 step 3, §5 R1-3): a contact code carrying our own relay key, or our
 * own address, binds nothing.
 *
 * At HEAD, storePeerNostrFromCode has no self check: our own code pasted into New Chat binds our own relay key to our
 * own address, and a code pairing another address with our relay key binds our key to that address, so our own
 * messages would route as if they came from that contact. Wanted: nothing is bound in either case. The store answers
 * "our own relay key" by name (isOwnNostrPubkey), so this file compiles before the fix. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactCodeOwnKeyIsRefusedTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val ourKey = relayKey(31)

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

    private fun store() =
        RelayKeyStore().apply {
            selfAddresses += RelayKeyBinding.SELF
            ownRelayKeys += ourKey
        }

    @Test
    fun `a code carrying our own relay key binds nothing`() {
        val other = SyntheticUnifiedAddress.mainnet(4_301)
        val store = store()
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(code(other, ourKey))

        assertNull(store.pubkeys[other], "our own relay key was bound to another address")
        assertFalse(store.writes.any { it.startsWith("setPeerNostrPubkey") }, "a relay key was written: ${store.writes}")
    }

    @Test
    fun `our own code binds nothing`() {
        val store = store()
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(code(RelayKeyBinding.SELF, ourKey))

        assertNull(store.pubkeys[RelayKeyBinding.SELF], "our own relay key was bound to our own address")
        assertFalse(store.writes.any { it.startsWith("setPeerNostrPubkey") }, "a relay key was written: ${store.writes}")
    }

    @Test
    fun `our own address with some other key binds nothing`() {
        val store = store()
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(code(RelayKeyBinding.SELF, relayKey(32)))

        assertNull(store.pubkeys[RelayKeyBinding.SELF], "a relay key was bound to our own address")
        assertFalse(store.writes.any { it.startsWith("setPeerNostrPubkey") }, "a relay key was written: ${store.writes}")
    }
}
