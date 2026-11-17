package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

interface PersistableWalletProvider {
    val persistableWallet: Flow<PersistableWallet?>

    suspend fun store(persistableWallet: PersistableWallet)

    suspend fun getPersistableWallet(): PersistableWallet?

    suspend fun requirePersistableWallet(): PersistableWallet
}

/**
 * Heal a wallet pinned to a DEPRECATED lightwalletd host onto the current default, applied at every
 * read. A wallet's endpoint is frozen at creation time (there is no migration), so a wallet minted on
 * the pre-2.10.4 builds — when lwdv3.zecwallet.co was the sole mainnet default — is stranded on it
 * forever. lwdv3's TLS cert has lapsed repeatedly, so those wallets showed "sync failed" with no in-app
 * recovery (the historical workaround was a DESTRUCTIVE reinstall that loses an unbacked seed). Healing
 * on READ means a plain app update repairs them with zero write to the sensitive wallet store; the user
 * can still pick any server in Choose Server, which overwrites this.
 */
internal val DEPRECATED_ENDPOINT_HOSTS = setOf("lwdv3.zecwallet.co")
internal val HEALED_ENDPOINT = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true)

/** Map a deprecated/dead lightwalletd host onto the current default; reference-identity for any other host. */
internal fun healDeprecatedEndpoint(endpoint: LightWalletEndpoint): LightWalletEndpoint =
    if (endpoint.host in DEPRECATED_ENDPOINT_HOSTS) HEALED_ENDPOINT else endpoint

private fun PersistableWallet.withHealedEndpoint(): PersistableWallet {
    val healed = healDeprecatedEndpoint(endpoint)
    return if (healed === endpoint) this else copy(endpoint = healed)
}

class PersistableWalletProviderImpl(
    preferenceHolder: EncryptedPreferenceProvider
) : PersistableWalletProvider {
    private val persistableWalletStorageProvider = PersistableWalletStorageProviderImpl(preferenceHolder)

    override val persistableWallet: Flow<PersistableWallet?> =
        persistableWalletStorageProvider
            .observe()
            .map { wallet ->
                wallet?.copy(
                    seedPhrase = wallet.seedPhrase.copy(split = wallet.seedPhrase.split.map { it.trim() })
                )?.withHealedEndpoint()
            }

    override suspend fun store(persistableWallet: PersistableWallet) {
        persistableWalletStorageProvider.store(persistableWallet)
    }

    override suspend fun getPersistableWallet() = persistableWalletStorageProvider.get()?.withHealedEndpoint()

    override suspend fun requirePersistableWallet() =
        checkNotNull(persistableWalletStorageProvider.get()).withHealedEndpoint()
}

private interface PersistableWalletStorageProvider : NullableStorageProvider<PersistableWallet>

private class PersistableWalletStorageProviderImpl(
    override val preferenceHolder: EncryptedPreferenceProvider,
) : BaseNullableStorageProvider<PersistableWallet>(),
    PersistableWalletStorageProvider {
    override val default = PersistableWalletPreferenceDefault(PreferenceKey("persistable_wallet"))
}

private class PersistableWalletPreferenceDefault(
    override val key: PreferenceKey
) : PreferenceDefault<PersistableWallet?> {
    override suspend fun getValue(preferenceProvider: PreferenceProvider) =
        preferenceProvider.getString(key)?.let { PersistableWallet.from(JSONObject(it)) }

    override suspend fun putValue(
        preferenceProvider: PreferenceProvider,
        newValue: PersistableWallet?
    ) = preferenceProvider.putString(key, newValue?.toJson()?.toString())
}
