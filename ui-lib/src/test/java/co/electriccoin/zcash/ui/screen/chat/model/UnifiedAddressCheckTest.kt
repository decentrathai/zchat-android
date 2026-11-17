package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R2-2 (a) — the address check a stranger's KEX must pass before a chat request is offered. */
class UnifiedAddressCheckTest {
    /** BIP 350 test vectors: valid Bech32m, and valid Bech32 (the older constant) that must fail. */
    @Test
    fun the_checksum_is_bech32m() {
        assertTrue(UnifiedAddressCheck.hasBech32mChecksum("a1lqfn3a"))
        assertTrue(UnifiedAddressCheck.hasBech32mChecksum("abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx"))
        assertTrue(
            UnifiedAddressCheck.hasBech32mChecksum("split1checkupstagehandshakeupstreamerranterredcaperredlc445v"),
        )
        assertFalse(UnifiedAddressCheck.hasBech32mChecksum("a12uel5l"), "a Bech32 (not Bech32m) checksum")
        assertFalse(
            UnifiedAddressCheck.hasBech32mChecksum("abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw"),
            "a Bech32 (not Bech32m) checksum",
        )
    }

    @Test
    fun only_a_well_formed_mainnet_unified_address_passes() {
        val good =
            co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
                .mainnet(seed = 11)
        assertTrue(UnifiedAddressCheck.isMainnetUnifiedAddress(good))
        assertFalse(
            UnifiedAddressCheck.isMainnetUnifiedAddress(
                co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
                    .mainnetBadChecksum(seed = 11),
            ),
            "broken checksum",
        )
        assertFalse(
            UnifiedAddressCheck.isMainnetUnifiedAddress(
                co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
                    .testnet(seed = 11),
            ),
            "test network",
        )
        assertFalse(UnifiedAddressCheck.isMainnetUnifiedAddress(good.uppercase()), "upper case")
        assertFalse(UnifiedAddressCheck.isMainnetUnifiedAddress("u1" + "q".repeat(100)), "no valid checksum")
        assertFalse(UnifiedAddressCheck.isMainnetUnifiedAddress("zs1" + good.drop(2)), "not a Unified Address")
        assertFalse(UnifiedAddressCheck.isMainnetUnifiedAddress("u1qqqqqqqqqqqq"), "too short")
        assertFalse(UnifiedAddressCheck.isMainnetUnifiedAddress("u1" + good.drop(2) + "&amount=1"), "URI injection")
    }

    /**
     * GPT-6 F4 (22 Sep) — the "u1" prefix test and the checksum disagree about the human-readable part: the
     * checksum splits at the LAST '1', so "u1x1…" is a valid Bech32m string whose human-readable part is "u1x",
     * not "u". It is not a mainnet Unified Address and must not pass.
     */
    @Test
    fun a_checksummed_string_with_another_human_readable_part_does_not_pass() {
        val wrongHrp = "u1x1" + "q".repeat(60) + "sun8nk"
        assertTrue(UnifiedAddressCheck.hasBech32mChecksum(wrongHrp), "fixture: a valid Bech32m string (part \"u1x\")")
        assertFalse(
            UnifiedAddressCheck.isMainnetUnifiedAddress(wrongHrp),
            "a Bech32m string whose human-readable part is \"u1x\" passed as a mainnet Unified Address",
        )
        // Control: a real-shaped mainnet address still passes.
        assertTrue(
            UnifiedAddressCheck.isMainnetUnifiedAddress(
                co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
                    .mainnet(seed = 12),
            ),
        )
    }
}
