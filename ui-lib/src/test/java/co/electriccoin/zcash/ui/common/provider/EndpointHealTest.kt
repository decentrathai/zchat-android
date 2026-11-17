package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Guards the lightwalletd endpoint heal (D-sync): a wallet pinned to the deprecated, cert-flapping
 * lwdv3.zecwallet.co must recover onto the current default on read, and NOTHING else may be touched.
 */
class EndpointHealTest {
    private fun ep(host: String) = LightWalletEndpoint(host = host, port = 443, isSecure = true)

    @Test
    fun `heals the deprecated lwdv3 host onto the default zec rocks`() {
        val healed = healDeprecatedEndpoint(ep("lwdv3.zecwallet.co"))
        assertEquals("zec.rocks", healed.host, "deprecated lwdv3 must heal to zec.rocks")
        assertEquals(443, healed.port)
        assertEquals(true, healed.isSecure)
        assertSame(HEALED_ENDPOINT, healed, "heal must return the canonical default endpoint")
    }

    @Test
    fun `leaves every non-deprecated host untouched by reference identity`() {
        listOf(
            "zec.rocks",
            "na.zec.rocks",
            "eu.zec.rocks",
            "testnet.zec.rocks",
            "my.custom.lightwalletd.example",
            "lwdv3.zecwallet.co.evil.example", // must match the host EXACTLY, not a suffix/substring
        ).forEach { host ->
            val e = ep(host)
            assertSame(e, healDeprecatedEndpoint(e), "host $host must pass through unchanged")
        }
    }

    @Test
    fun `the deprecated set and healed default are the expected constants`() {
        assertEquals(setOf("lwdv3.zecwallet.co"), DEPRECATED_ENDPOINT_HOSTS)
        assertEquals("zec.rocks", HEALED_ENDPOINT.host)
        assertEquals(443, HEALED_ENDPOINT.port)
    }
}
