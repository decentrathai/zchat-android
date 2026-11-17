package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.walletstartup.AppRestarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * F10 (fix-all 2.13.0, chat copy #16): the Advanced Settings row "Restore old chats".
 *
 * A kept chat copy that could not be unlocked after its last pause is parked: ZCHAT stops offering it by itself
 * (EncryptedStoreOpener). This row is the way back. It is shown only while this wallet has a held copy
 * ([KeptChatStoreCopies.held]), and its confirmed action lets the held copies be offered again
 * ([KeptChatStoreCopies.retryHeld]) and then restarts ZCHAT, whose start-up screen offers them (Restore my chats /
 * Continue without them). Both read the marks file and every copy file, so both run on [ioDispatcher], never on the
 * main thread (review break 6).
 *
 * ZC-709: the row also brings back a copy the user declined with "Continue without them" (it stays on the phone), while
 * its wallet is the one in ZCHAT: it is shown while this wallet has a held or a declined copy that holds chats
 * ([KeptChatStoreCopies.askable]), and its action offers both again ([KeptChatStoreCopies.reoffer]).
 */
class RestoreOldChats(
    private val keptChatCopies: KeptChatStoreCopies,
    private val appRestarter: AppRestarter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether the row is shown: some kept copy of this wallet's chats is held back (paused or parked) or was declined. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun isAvailable(): Boolean =
        withContext(ioDispatcher) {
            try {
                keptChatCopies.askable().isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error { "CHAT - the held chat copies cannot be read: ${e.javaClass.simpleName}" }
                false
            }
        }

    /**
     * Lets the held and the declined copies be offered again, then restarts ZCHAT (the restart ends the process, as a force stop does,
     * so the row asks to confirm first). Returns false, with nothing changed and no restart, when that did not work.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun retryAndRestart(): Boolean =
        withContext(ioDispatcher + NonCancellable) {
            val done =
                try {
                    keptChatCopies.reoffer()
                } catch (e: Exception) {
                    Twig.error { "CHAT - the held chat copies could not be offered again: ${e.javaClass.simpleName}" }
                    false
                }
            if (done) appRestarter.restart()
            done
        }
}
