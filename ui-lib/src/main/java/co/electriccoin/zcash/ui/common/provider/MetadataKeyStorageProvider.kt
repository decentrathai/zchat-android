package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.SecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface MetadataKeyStorageProvider {
    suspend fun get(uuid: AccountUuid): MetadataKey?

    suspend fun store(uuid: AccountUuid, key: MetadataKey)
}

class MetadataKeyStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider
) : MetadataKeyStorageProvider {
    private val default = MetadataKeyPreferenceDefault(encryptedPreferenceProvider)

    override suspend fun get(uuid: AccountUuid): MetadataKey? = default.getValue(uuid)

    override suspend fun store(uuid: AccountUuid, key: MetadataKey) = default.putValue(uuid, key)
}

private class MetadataKeyPreferenceDefault(
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider
) {
    private val secretKeyAccess: SecretKeyAccess?
        get() = InsecureSecretKeyAccess.get()

    suspend fun getValue(uuid: AccountUuid): MetadataKey? =
        encryptedPreferenceProvider()
            .getStringSet(key = getKey(uuid))
            ?.decode(secretKeyAccess)

    suspend fun putValue(uuid: AccountUuid, newValue: MetadataKey?) {
        encryptedPreferenceProvider().putStringSet(
            key = getKey(uuid),
            value = newValue?.encode(secretKeyAccess)
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun getKey(uuid: AccountUuid) = PreferenceKey("metadata_key_${uuid.value.toHexString()}")
}

@OptIn(ExperimentalEncodingApi::class)
private fun MetadataKey?.encode(secretKeyAccess: SecretKeyAccess?): Set<String>? =
    this
        ?.bytes
        ?.mapIndexed { index, secret ->
            // Prefix with the list index. SharedPreferences persists a StringSet UNORDERED, but
            // MetadataKey.bytes is order-significant (bytes.first() derives the metadata file
            // identifier and the encryption key), so a bare set could round-trip the keys swapped.
            // The index lets decode() restore the original order.
            "$index:${Base64.encode(secret.toByteArray(secretKeyAccess))}"
        }?.toSet()

@OptIn(ExperimentalEncodingApi::class)
private fun Set<String>?.decode(secretKeyAccess: SecretKeyAccess?) =
    if (this != null) {
        // New entries are "<index>:<base64>"; legacy entries are bare base64 (the base64 alphabet
        // never contains ':'). Restore insertion order from the index when present; fall back to
        // iteration order for legacy sets (order was only ever ambiguous for multi-key accounts).
        val indexed = all { it.substringBefore(':', "").toIntOrNull() != null }
        val ordered =
            if (indexed) {
                sortedBy { it.substringBefore(':').toInt() }.map { it.substringAfter(':') }
            } else {
                toList()
            }
        MetadataKey(
            ordered.map {
                SecretBytes.copyFrom(Base64.decode(it), secretKeyAccess)
            }
        )
    } else {
        null
    }
