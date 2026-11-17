package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Money-safety guard: [ZMSGConstants.isBareAddressShaped].
 *
 * buildZip321Uri interpolates the destination address RAW into a ZIP-321 URI that is then submitted
 * with allowMultiPayment=true and NO review screen. Addresses reach it from attacker-influenceable
 * sources (the unauthenticated GROUP_INVITE "inviter" field, scanned QRs, pasted contact codes), so a
 * metacharacter payload could append a hidden payment output and redirect funds. Every real Zcash
 * address is bare alphanumeric — this guard fails closed on anything else.
 */
class AddressShapeGuardTest {
    // A unified-address-shaped string (213 chars, bech32 charset). Synthetic: not a real address.
    private val unifiedShaped =
        "u1tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9" +
            "x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54khce6mua7lq" +
            "pzry9x8gf2tvdw0s3jn54khce6mua"

    @Test
    fun `accepts real zcash address forms`() {
        assertTrue(ZMSGConstants.isBareAddressShaped(unifiedShaped), "unified u1 address must be accepted")
        assertTrue(
            ZMSGConstants.isBareAddressShaped("zs1" + "a7b3c9d2e4f6g8h1j3k5m7n9p2q4r6s8t1u3v5w7x9y2z4a6b8c1d3e5f7g9h2j4"),
            "sapling zs1 address must be accepted",
        )
        assertTrue(ZMSGConstants.isBareAddressShaped("t1KsRA9M2q1cD3nB4vE5fG6hJ7kL8mN9pQr"), "transparent t1 must be accepted")
        assertTrue(ZMSGConstants.isBareAddressShaped(ZMSGConstants.PLATFORM_FEE_ADDRESS), "our own platform-fee address must be accepted")
    }

    @Test
    fun `rejects the ZIP321 URI-injection payload that would redirect funds`() {
        // The attack: a "u1…"-prefixed, long-enough string that the prefix+length isValidZcashAddress
        // helpers accept, carrying URI metacharacters that append an attacker payment output.
        val injected = unifiedShaped.take(110) + "&address.5=u1attackerpayoutaddress&amount.5=5.0"
        assertFalse(ZMSGConstants.isBareAddressShaped(injected), "URI-injection payload must be rejected")

        // Individual metacharacters that could break out of the address position.
        listOf("&", "?", "=", ".", "%", "#", "/", ":", " ", "\n").forEach { meta ->
            assertFalse(
                ZMSGConstants.isBareAddressShaped(unifiedShaped.take(60) + meta + unifiedShaped.take(60)),
                "address containing '$meta' must be rejected",
            )
        }
    }

    @Test
    fun `rejects degenerate lengths and empties`() {
        assertFalse(ZMSGConstants.isBareAddressShaped(""), "empty must be rejected")
        assertFalse(ZMSGConstants.isBareAddressShaped("u1short"), "under-length must be rejected")
        assertFalse(ZMSGConstants.isBareAddressShaped("a".repeat(19)), "19 chars is under the floor")
        assertTrue(ZMSGConstants.isBareAddressShaped("a".repeat(20)), "20 chars is the floor")
        assertTrue(ZMSGConstants.isBareAddressShaped("a".repeat(512)), "512 chars is the ceiling")
        assertFalse(ZMSGConstants.isBareAddressShaped("a".repeat(513)), "over-length must be rejected")
    }
}
