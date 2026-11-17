package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupQueue
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupWaiting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * KI-23 (ZC-020): the Advanced Settings row "Automatic setup payments".
 *
 * ZCHAT pays some chat setup transactions by itself (key exchanges, the relay-identity message, the answer to a group
 * member's key request) once the user chose Allow in the question "Allow handshake transactions?". This row shows
 * whether that standing permission is on, turns it OFF, and (ZC-1013) turns it back ON behind a confirm that shows the
 * same standing consent (what it allows, the price per setup message, that it can be turned off again). Turning it on
 * pays nothing by itself: it only writes the permission. A payment the user taps and is quoted for (a message, Join,
 * Sync keys, Resend key, Retry) does not depend on it.
 *
 * ZC1013-X1: the turn-on confirm also quotes what waits now ([waitingNow], the in-chat question's own count and price),
 * and the setups the user answered "Not now" to are not covered by turning it on: they keep asking (memory only, as the
 * "Not now" answer itself; see AutomaticSetupQueue).
 *
 * Every call reaches the preferences store, so each runs on [ioDispatcher], never on the main thread.
 */
class AutomaticSetupPayments(
    private val zchatPreferences: ZchatPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether the standing permission is on. A store that cannot be read says null: the row then shows neither. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun isOn(): Boolean? =
        withContext(ioDispatcher) {
            try {
                zchatPreferences.hasConsentedToHandshakeSpend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error { "SETTINGS - automatic setup payments: the permission cannot be read: ${e.javaClass.simpleName}" }
                null
            }
        }

    /**
     * ZC1013-X1: what turning the permission on would let ZChat pay by itself now (the queued setups and due ZBOOTs, with
     * the in-chat question's count and price), and apart the setups answered "Not now", which keep asking. Null when the
     * store cannot be read: the confirm then names only the price per setup message.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun waitingNow(): AutomaticSetupWaiting? =
        withContext(ioDispatcher) {
            try {
                AutomaticSetupQueue.waiting(zchatPreferences)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error { "SETTINGS - automatic setup payments: what waits cannot be read: ${e.javaClass.simpleName}" }
                null
            }
        }

    /**
     * ZC-1013: grants the standing permission with a checked write, as Allow in the question does, and nothing else (no
     * release of anything in a chat). Returns false, with nothing changed, when the write did not reach the disk:
     * automatic setup payments are then still off, and the caller says so. ZC1013-X1: the setups answered "Not now" are
     * marked to keep asking BEFORE the write, so no retry between the two pays one of them.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun turnOn(): Boolean =
        withContext(ioDispatcher + NonCancellable) {
            val keptAsking = AutomaticSetupQueue.keepDeclinedAsking()
            val saved =
                try {
                    zchatPreferences.setConsentedToHandshakeSpend()
                } catch (e: Exception) {
                    Twig.error { "SETTINGS - automatic setup payments: could not be turned on: ${e.javaClass.simpleName}" }
                    false
                }
            if (!saved) AutomaticSetupQueue.undoKeepAsking(keptAsking)
            saved
        }

    /**
     * Withdraws the standing permission with a checked write. Returns false, with nothing changed, when the write did
     * not reach the disk: automatic setup payments are then still on, and the caller says so.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun turnOff(): Boolean =
        withContext(ioDispatcher + NonCancellable) {
            try {
                zchatPreferences.clearConsentedToHandshakeSpend()
            } catch (e: Exception) {
                Twig.error { "SETTINGS - automatic setup payments: could not be turned off: ${e.javaClass.simpleName}" }
                false
            }
        }
}
