package co.electriccoin.zcash.ui.common.provider

import android.app.Application
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

// TODO [#1273]: Add ChooseServer Tests #1273
// TODO [#1273]: https://github.com/Electric-Coin-Company/zashi-android/issues/1273
class LightWalletEndpointProvider(
    private val application: Application
) {
    fun getEndpoints(): List<LightWalletEndpoint> =
        if (ZcashNetwork.fromResources(application) == ZcashNetwork.Mainnet) {
            listOf(
                LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
                // na.zec.rocks (same operator, regional) replaces lwdv3.zecwallet.co: lwdv3's TLS cert
                // has lapsed repeatedly, and because a wallet's endpoint is frozen at creation time,
                // wallets minted on the pre-2.10.4 builds (when lwdv3 was the sole default) were stranded
                // on "sync failed" whenever it was down. Deprecated-host healing lives in
                // PersistableWalletProvider so those old wallets recover on a plain update.
                LightWalletEndpoint(host = "na.zec.rocks", port = 443, isSecure = true),
            )
        } else {
            listOf(
                LightWalletEndpoint(host = "testnet.zec.rocks", port = 443, isSecure = true)
            )
        }

    fun getDefaultEndpoint() = getEndpoints().first()
}
