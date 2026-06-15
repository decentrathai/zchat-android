package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import java.security.MessageDigest

object WalletPubkey {
    suspend fun deriveOrNull(getDefaultUA: GetDefaultUnifiedAddressUseCase): String? =
        runCatching {
            val ua = getDefaultUA()
            val digest = MessageDigest.getInstance("SHA-256").digest(ua.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { byte -> "%02x".format(byte) }
        }.getOrNull()
}
