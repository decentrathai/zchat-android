package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/** The fingerprint of an address: lower-case hex of its SHA-256. AI-KEY-S: it is compared and signed, and sent only by the tap of AI-KEY-P. */
object WalletPubkey {
    /** AI-KEY-P: the selected account's chat identity, read on the tap on "Look for AI credit"; null when it cannot be read. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun deriveOrNull(getDefaultUA: GetDefaultUnifiedAddressUseCase): String? =
        try {
            fingerprintOf(getDefaultUA())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    fun fingerprintOf(address: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(address.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
