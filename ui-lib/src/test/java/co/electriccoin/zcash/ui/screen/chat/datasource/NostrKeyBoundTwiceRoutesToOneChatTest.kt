package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.UnifiedAddressCheck
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.1 item 4, §1.2, §1.3 step 4, §5 R1-1): a relay key bound to two
 * addresses routes to one chosen chat, never to a broken copy by hash order.
 *
 * At HEAD, findPeerByNostrPubkey returns the first "pubkey:<address>" entry of modePrefs.all, a HashMap copy whose
 * order is fixed by the key strings. On the Fold the HONOR's key was bound to its real address and to a copy cut to 102
 * characters, and every HONOR relay message (every reply, every group message) went to the cut copy, a second, unnamed
 * Shielded chat.
 *
 * Wanted: every match is collected and one is chosen by a fixed rule (NostrPeerBinding.choose): an address that passes
 * the Bech32m check first, then a saved contact, then a stored mode, then the most recent row, then the smallest
 * string. Real ZchatPreferencesImpl over in-memory files ([realPreferences]). Each case first proves its own
 * precondition: that the naive first match over the file (the HEAD rule) is the WRONG address for the chosen seed, so
 * the case exercises the HEAD bug and cannot pass by the luck of the hash. All values are synthetic.
 */
class NostrKeyBoundTwiceRoutesToOneChatTest {
    /** The HEAD rule: the first "pubkey:" entry of the file's getAll() whose value is [key]. */
    private fun naiveFirstMatch(
        modePrefs: MemorySharedPreferences,
        key: String,
    ): String? =
        modePrefs.all.entries
            .firstOrNull { (k, v) -> k.startsWith("pubkey:") && (v as? String)?.lowercase() == key.lowercase() }
            ?.key
            ?.removePrefix("pubkey:")

    private fun bind(
        modePrefs: MemorySharedPreferences,
        address: String,
        key: String,
    ) {
        modePrefs.edit().putString("pubkey:$address", key).commit()
    }

    @Test
    fun `a key on a real address and on its 102-character copy routes to the real address`() {
        var checked = 0
        for (seed in 1..400) {
            val (prefs, files) = realPreferences()
            val modePrefs = files.getValue("modePrefs")
            val real = SyntheticUnifiedAddress.mainnet(50_000 + seed)
            val cut = real.take(102)
            val key = RelayKeyBinding.relayKey(seed)
            bind(modePrefs, cut, key)
            bind(modePrefs, real, key)
            modePrefs.edit().putString("mode:$real", ConversationMode.OPEN.name).commit()
            files
                .getValue("nicknamePrefs")
                .edit()
                .putString(real, "Synthetic HONOR")
                .commit()
            // Precondition: the HEAD rule picks the cut copy for this seed.
            if (naiveFirstMatch(modePrefs, key) != cut) continue
            assertTrue(UnifiedAddressCheck.isMainnetUnifiedAddress(real))
            assertFalse(UnifiedAddressCheck.isMainnetUnifiedAddress(cut))

            assertEquals(real, prefs.findPeerByNostrPubkey(key), "seed $seed: the key routed to the cut copy")
            assertEquals(real, prefs.findPeerByNostrPubkey(key.uppercase()), "seed $seed: the lookup is case sensitive")
            checked++
            if (checked == 3) break
        }
        assertEquals(3, checked, "precondition never held: no seed made the HEAD rule pick the cut copy")
    }

    @Test
    fun `a key on two valid addresses routes to the saved contact`() {
        var checked = 0
        for (seed in 1..400) {
            val (prefs, files) = realPreferences()
            val modePrefs = files.getValue("modePrefs")
            val contact = SyntheticUnifiedAddress.mainnet(60_000 + seed)
            val other = SyntheticUnifiedAddress.mainnet(70_000 + seed)
            val key = RelayKeyBinding.relayKey(1_000 + seed)
            bind(modePrefs, contact, key)
            bind(modePrefs, other, key)
            files
                .getValue("nicknamePrefs")
                .edit()
                .putString(contact, "Synthetic contact")
                .commit()
            if (naiveFirstMatch(modePrefs, key) != other) continue

            assertEquals(contact, prefs.findPeerByNostrPubkey(key), "seed $seed: the key routed to the address that is not a contact")
            checked++
            if (checked == 3) break
        }
        assertEquals(3, checked, "precondition never held: no seed made the HEAD rule pick the non-contact")
    }

    @Test
    fun `a key on two valid non-contact addresses routes to the one with a stored mode, then the smaller string`() {
        var checked = 0
        for (seed in 1..400) {
            val (prefs, files) = realPreferences()
            val modePrefs = files.getValue("modePrefs")
            val withMode = SyntheticUnifiedAddress.mainnet(80_000 + seed)
            val other = SyntheticUnifiedAddress.mainnet(90_000 + seed)
            val key = RelayKeyBinding.relayKey(2_000 + seed)
            bind(modePrefs, withMode, key)
            bind(modePrefs, other, key)
            modePrefs.edit().putString("mode:$withMode", ConversationMode.TUNNEL.name).commit()
            if (naiveFirstMatch(modePrefs, key) != other) continue

            assertEquals(withMode, prefs.findPeerByNostrPubkey(key), "seed $seed: the key routed to the address with no mode")
            modePrefs.edit().remove("mode:$withMode").commit()
            val smaller = minOf(withMode, other)
            assertEquals(smaller, prefs.findPeerByNostrPubkey(key), "seed $seed: with nothing else to go on, not the smaller string")
            checked++
            if (checked == 3) break
        }
        assertEquals(3, checked, "precondition never held")
    }

    @Test
    fun `a key on one address, and an unknown key, are unchanged (guard)`() {
        val (prefs, files) = realPreferences()
        val only = SyntheticUnifiedAddress.mainnet(99_001)
        bind(files.getValue("modePrefs"), only, RelayKeyBinding.relayKey(9))
        assertEquals(only, prefs.findPeerByNostrPubkey(RelayKeyBinding.relayKey(9)))
        assertEquals(null, prefs.findPeerByNostrPubkey(RelayKeyBinding.relayKey(10)))
        assertNotNull(prefs.getPeerNostrPubkey(only))
    }
}
