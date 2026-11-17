package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RED pins for [derivePrivacyStatus] after Ironwood.
 *
 * NU6.3 introduced a second shielded pool of the same Orchard protocol, and two of the wallet states
 * it creates are ones the pre-Ironwood classifier could not describe:
 *
 *  - SPLIT (Orchard + Ironwood, no transparent): the normal state of a wallet for hours during a
 *    ZIP 318 scheduled crossing, and the state both test devices enter on their first inbound
 *    payment. Every satoshi is shielded, so the wallet is fully shielded. The classifier derived
 *    "fully shielded" from `poolType`, and a split lands in MIXED, so the user got an amber
 *    "Funds need shielding for full privacy" bar with nothing to shield.
 *
 *  - STRANDED (Orchard only, no Ironwood): shielded, and unable to reach a peer at all, because an
 *    Orchard Action may only pay its own address since NU6.3 (ZIP 258). This needs its own flag.
 */
class PrivacyStatusDerivationTest {
    private fun z(v: Long) = Zatoshi(v)

    private val zero = z(0)

    @Test
    fun split_orchard_and_ironwood_wallet_is_fully_shielded() {
        val s =
            derivePrivacyStatus(
                orchardBalance = z(135_000),
                ironwoodBalance = z(676_000),
                saplingBalance = zero,
                transparentBalance = zero,
            )

        assertEquals(PoolType.MIXED, s.poolType)
        assertTrue(
            s.isFullyShielded,
            "Every pool held is shielded and transparent is zero; 'needs shielding' is false here",
        )
        assertFalse(s.isOrchardStranded, "Ironwood value exists, so the wallet can reach a peer")
    }

    @Test
    fun fully_shielded_means_no_transparent_value_and_nothing_more() {
        // Sapling + Ironwood, no transparent: still fully shielded even though it is MIXED.
        val s =
            derivePrivacyStatus(
                orchardBalance = zero,
                ironwoodBalance = z(10),
                saplingBalance = z(10),
                transparentBalance = zero,
            )
        assertEquals(PoolType.MIXED, s.poolType)
        assertTrue(s.isFullyShielded)
    }

    @Test
    fun any_transparent_value_is_not_fully_shielded() {
        val s =
            derivePrivacyStatus(
                orchardBalance = zero,
                ironwoodBalance = z(100),
                saplingBalance = zero,
                transparentBalance = z(1),
            )
        assertEquals(PoolType.MIXED, s.poolType)
        assertFalse(s.isFullyShielded)
    }

    @Test
    fun orchard_only_wallet_is_stranded() {
        // 135_000 is the real HONOR test-device balance measured on 2026-09-04.
        val s =
            derivePrivacyStatus(
                orchardBalance = z(135_000),
                ironwoodBalance = zero,
                saplingBalance = zero,
                transparentBalance = zero,
            )

        assertEquals(PoolType.ORCHARD, s.poolType)
        assertTrue(s.isFullyShielded, "It IS shielded — that is exactly what makes the state confusing")
        assertTrue(s.isOrchardStranded, "…but it cannot send to a peer, and the UI must say so")
    }

    @Test
    fun ironwood_only_wallet_is_the_good_state() {
        val s =
            derivePrivacyStatus(
                orchardBalance = zero,
                ironwoodBalance = z(811_000),
                saplingBalance = zero,
                transparentBalance = zero,
            )
        assertEquals(PoolType.IRONWOOD, s.poolType)
        assertTrue(s.isFullyShielded)
        assertFalse(s.isOrchardStranded)
    }

    @Test
    fun ironwood_is_preferred_over_orchard_when_both_are_sole_candidates() {
        // Order of the `when` matters: an Ironwood-only wallet must not be mislabelled Orchard.
        assertEquals(PoolType.IRONWOOD, derivePrivacyStatus(zero, z(1), zero, zero).poolType)
        assertEquals(PoolType.ORCHARD, derivePrivacyStatus(z(1), zero, zero, zero).poolType)
    }

    @Test
    fun empty_wallet_defaults_to_ironwood_and_counts_as_shielded() {
        val s = derivePrivacyStatus(zero, zero, zero, zero)
        assertEquals(PoolType.IRONWOOD, s.poolType)
        assertTrue(s.isFullyShielded)
        assertFalse(s.isOrchardStranded)
    }

    @Test
    fun transparent_only_and_sapling_only_still_classify_as_before() {
        assertEquals(PoolType.TRANSPARENT, derivePrivacyStatus(zero, zero, zero, z(5)).poolType)
        assertEquals(PoolType.SAPLING, derivePrivacyStatus(zero, zero, z(5), zero).poolType)
        assertTrue(derivePrivacyStatus(zero, zero, z(5), zero).isFullyShielded)
    }

    /** Pin: stranded is about SPENDABLE Ironwood. Pending-only Ironwood still strands, and says so. */
    @Test
    fun pending_ironwood_with_spendable_orchard_is_stranded_and_confirming() {
        val status =
            derivePrivacyStatus(
                orchardBalance = Zatoshi(135_000),
                ironwoodBalance = Zatoshi(676_000),
                saplingBalance = Zatoshi(0),
                transparentBalance = Zatoshi(0),
                orchardAvailable = Zatoshi(135_000),
                ironwoodAvailable = Zatoshi(0),
            )
        assertTrue(status.isOrchardStranded, "no spendable Ironwood → still stranded")
        assertTrue(status.isIronwoodConfirming, "…but the crossing is under way, so the advice is to wait")
        assertTrue(status.isFullyShielded)
    }

    /** Pin: once any Ironwood value is spendable the gate steps aside, whatever Orchard still holds. */
    @Test
    fun spendable_ironwood_clears_stranded_even_with_spendable_orchard() {
        val status =
            derivePrivacyStatus(
                orchardBalance = Zatoshi(135_000),
                ironwoodBalance = Zatoshi(676_000),
                saplingBalance = Zatoshi(0),
                transparentBalance = Zatoshi(0),
                orchardAvailable = Zatoshi(135_000),
                ironwoodAvailable = Zatoshi(676_000),
            )
        assertFalse(status.isOrchardStranded)
        assertFalse(status.isIronwoodConfirming)
    }
}
