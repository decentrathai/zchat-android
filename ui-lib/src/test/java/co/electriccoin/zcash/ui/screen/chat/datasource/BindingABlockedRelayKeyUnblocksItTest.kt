package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1466 (OT213n B1b; FIX_PLAN REVISION 1, Fable M7: the unblock lives inside bindPeerNostrKey): binding a contact's
 * code unblocks its relay key.
 *
 * At 669cf19e1 Reject blocks the sender's relay key, and a later scan or paste of that same contact's code binds the
 * key (bindPeerNostrKey) but leaves it blocked: there is no unblock at all. The known-peer path ignores the block, so it
 * looks fine until the binding goes (the chat is deleted), and then the contact the user scanned is dropped silently as
 * blocked, with no request.
 *
 * Wanted: when the user's own act binds the key (scan or paste: mayTakeFromBrokenHolder = true) and the bind succeeds
 * (BOUND or ALREADY_BOUND_SAME), the key is no longer blocked. A refused bind (our own key, a key bound elsewhere) leaves
 * the block as it was (guard). Real ZchatPreferencesImpl over in-memory files ([realPreferences]). Every value is
 * synthetic.
 */
class BindingABlockedRelayKeyUnblocksItTest {
    private val contact = SyntheticUnifiedAddress.mainnet(14_661)
    private val holder = SyntheticUnifiedAddress.mainnet(14_662)
    private val key = RelayKeyBinding.relayKey(1_466)

    private fun wallet(): ZchatPreferencesImpl {
        val (prefs, _) = realPreferences()
        ZchatPreferencesImpl::class.java
            .getDeclaredField("selfAddresses\$delegate")
            .apply { isAccessible = true }
            .set(prefs, lazyOf(SelfAddressRegistry(MemorySharedPreferences())))
        return prefs
    }

    @Test
    fun `scanning or pasting the code of a contact whose key was rejected unblocks the key`() {
        val prefs = wallet()
        prefs.blockNostrPubkey(key)
        assertTrue(prefs.isNostrPubkeyBlocked(key), "fixture: the key is not blocked")

        val outcome = prefs.bindPeerNostrKey(contact, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)

        assertEquals(NostrBindResult.BOUND, outcome.result, "fixture: the code did not bind")
        assertFalse(prefs.isNostrPubkeyBlocked(key), "the scanned contact's relay key is still blocked after its code was bound")
    }

    @Test
    fun `scanning the same code again also unblocks a key blocked after the first bind`() {
        val prefs = wallet()
        prefs.bindPeerNostrKey(contact, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)
        prefs.blockNostrPubkey(key)

        val outcome = prefs.bindPeerNostrKey(contact, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)

        assertEquals(NostrBindResult.ALREADY_BOUND_SAME, outcome.result, "fixture: the second scan did not match the binding")
        assertFalse(prefs.isNostrPubkeyBlocked(key), "the re-scanned contact's relay key is still blocked")
    }

    @Test
    fun `a refused bind leaves the block as it was (guard)`() {
        val prefs = wallet()
        prefs.bindPeerNostrKey(holder, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)
        prefs.blockNostrPubkey(key)

        val outcome = prefs.bindPeerNostrKey(contact, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)

        assertEquals(NostrBindResult.KEY_BOUND_ELSEWHERE, outcome.result, "fixture: the key was not held by another address")
        assertTrue(prefs.isNostrPubkeyBlocked(key), "a refused bind unblocked the key")
    }
}
