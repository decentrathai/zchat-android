package co.electriccoin.zcash.ui.screen.walletstartup

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.preference.EncryptedPreferencesUnavailableException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.MasterKeyAbsentException
import co.electriccoin.zcash.ui.screen.chat.datasource.NewStoreNotMadeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What the wallet start-up error screen ([WalletStartupFailedScreen]) can offer after the SDK could not open the
 * wallet (RD-4 / A3), and the actions behind its buttons (P2f).
 */
class WalletStartupRepair(
    private val persistableWalletProvider: PersistableWalletProvider,
    private val walletRestoringStateProvider: WalletRestoringStateProvider,
    private val walletDataDatabase: WalletDataDatabase,
    private val appRestarter: AppRestarter,
    private val keptChatCopies: KeptChatStoreCopies? = null,
) {
    sealed interface Offer {
        /** The A3 safety screen: do not uninstall, back up the recovery phrase, try again. */
        data object SafetyScreen : Offer

        /**
         * The wallet data on the phone belongs to a different recovery phrase than the one saved, and the saved
         * phrase is readable: the old data can be set aside (renamed, never deleted) and the wallet rescanned.
         */
        data object SetAsideAndRescan : Offer

        /**
         * R2-3: the store with the recovery phrase could not be read in this launch (a phone-security error).
         * Nothing in it was changed; Try again restarts ZCHAT.
         */
        data object SeedStoreUnreadable : Offer

        /**
         * ZC-705: the store for the recovery phrase did not exist and could not be made with protected key entries (a
         * first run, or a start after a finished Destroy). There is no wallet yet and nothing was deleted; Try again
         * restarts ZCHAT. Its screen has neither "Do not uninstall" nor the backup button.
         */
        data object StorageNotSetUp : Offer

        /** R2-3: the encrypted chat stores could not be opened in this launch. Their data is still on disk. */
        data object ChatStoresUnavailable : Offer

        /**
         * R3-3: the chat stores could not be read and were recreated after exact copies were kept; the user restores
         * them or continues without them (the copies stay on the phone either way).
         */
        data object RestoreChatCopy : Offer

        /**
         * C6 (R6-4 review finding 14): the same choice as [RestoreChatCopy], offered after the user chatted on during a
         * paused restore. Restoring sets the current chats aside (kept as a copy and offered back), and the screen
         * says so.
         */
        data object RestoreChatCopyAfterUse : Offer

        /**
         * R4-5: a copy the user put back could not be read either — the phone's security key for it is gone for
         * good. Said once, with a single way on, so the same restore is not offered at every launch.
         */
        data object ChatCopyRestoreFailed : Offer

        /**
         * B5: a copy the user put back could not be unlocked because the phone's security system did not answer.
         * Nothing says the key is lost: the copy is kept and offered again once it answers. One way on.
         */
        data object ChatCopyRestorePaused : Offer

        /**
         * F10 (fix-all 2.13.0, chat copy #16): a kept copy could not be unlocked after its last pause, so ZCHAT stopped
         * offering it by itself. Nothing says the key is lost; the copy stays, and Settings offers it again. One way on.
         */
        data object ChatCopyRestoreParked : Offer
    }

    suspend fun offerFor(cause: Throwable?): Offer =
        when {
            // ZC-705: before the rule for the phrase store's error, which wraps this one.
            cause.hasInChain { it is NewStoreNotMadeException } -> Offer.StorageNotSetUp
            // ZC-868: the wallet-start refusal on an absent Keystore key is about the phrase store, not the chats.
            cause.hasInChain { it is MasterKeyAbsentException && it.beforeWalletStart } -> Offer.SeedStoreUnreadable
            cause.hasInChain { it is EncryptedPreferencesUnavailableException } -> Offer.SeedStoreUnreadable
            cause.hasInChain { it is EncryptedStoreUnavailableException } -> Offer.ChatStoresUnavailable
            cause.hasInChain { it is ChatStoreCopyRestoreFailedNotice } -> Offer.ChatCopyRestoreFailed
            cause.hasInChain { it is ChatStoreCopyRestorePausedNotice } -> Offer.ChatCopyRestorePaused
            cause.hasInChain { it is ChatStoreCopyRestoreParkedNotice } -> Offer.ChatCopyRestoreParked
            cause.hasInChain { it is ChatStoreCopyKeptNotice && it.usedAfterPause } -> Offer.RestoreChatCopyAfterUse
            cause.hasInChain { it is ChatStoreCopyKeptNotice } -> Offer.RestoreChatCopy
            isSeedMismatch(cause) && readableSavedWallet() != null -> Offer.SetAsideAndRescan
            else -> Offer.SafetyScreen
        }

    /**
     * Sets the old wallet data aside and restarts ZCHAT, which then rescans for the saved phrase. Returns false
     * (and changes nothing) when that is not possible.
     *
     * The old database is renamed, never deleted: whatever it shows can still be reached with its own phrase.
     * The SDK then finds no data database, creates a new one for the saved phrase at its birthday and scans the
     * chain from there (the same path as a restore).
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun setAsideAndRescan(cause: Throwable?): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            if (!isSeedMismatch(cause)) return@withContext false
            val wallet = readableSavedWallet() ?: return@withContext false
            if (!walletDataDatabase.setAside(wallet.network)) {
                Twig.error { "WALLET - the old wallet data could not be set aside; nothing was changed" }
                return@withContext false
            }
            Twig.warn { "WALLET - wallet data of another recovery phrase set aside; restarting to rescan" }
            try {
                walletRestoringStateProvider.store(WalletRestoringState.RESTORING)
            } catch (e: Exception) {
                // The rescan still happens after the restart; only the "restoring" banner is missing.
                Twig.error(e) { "WALLET - could not mark the wallet as restoring" }
            }
            appRestarter.restart()
            true
        }

    /**
     * R3-3: puts the kept chat copies back ([KeptChatStoreCopies.restore]) and restarts ZCHAT to open them. Returns
     * false, with nothing changed and nothing restarted, when that is not possible.
     */
    suspend fun restoreChatCopy(): Boolean = answerChatCopy { it.restore() }

    /** R3-3: keeps the copies on the phone, stops offering them and restarts ZCHAT. False when that failed. */
    suspend fun continueWithoutChatCopy(): Boolean = answerChatCopy { it.dismiss() }

    /**
     * R4-5: the user has read that the put-back copy cannot be read either. Stops saying it and restarts ZCHAT,
     * which then starts normally. The copies stay on the phone; nothing is deleted.
     */
    suspend fun acknowledgeChatCopyFailure(): Boolean = answerChatCopy { it.acknowledgeRestoreFailed() }

    /**
     * B5: the user has read that the restore is paused. Stops saying it and restarts ZCHAT, which then opens without
     * offering the copy until the phone's security system answers. The copies stay on the phone.
     */
    suspend fun acknowledgeChatCopyPause(): Boolean = answerChatCopy { it.acknowledgeRestorePaused() }

    /**
     * F10: the user has read that the copy is parked. Stops saying it and restarts ZCHAT, which then opens without
     * offering it; More → Advanced Settings → Restore old chats offers it again. The copies stay on the phone.
     */
    suspend fun acknowledgeChatCopyPark(): Boolean = answerChatCopy { it.acknowledgeRestoreParked() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun answerChatCopy(action: (KeptChatStoreCopies) -> Boolean): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            val copies = keptChatCopies ?: return@withContext false
            val done =
                try {
                    action(copies)
                } catch (e: Exception) {
                    Twig.error(e) { "CHAT - the kept copies of the chat stores could not be answered" }
                    false
                }
            if (done) appRestarter.restart()
            done
        }

    /** Restarts ZCHAT so the SDK tries to open the wallet again. Changes no data. */
    fun tryAgain() = appRestarter.restart()

    /** The saved wallet, when its recovery phrase can be read and is a valid phrase; null otherwise. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readableSavedWallet(): PersistableWallet? =
        try {
            persistableWalletProvider.getPersistableWallet()?.takeIf { wallet ->
                Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).validate()
                true
            }
        } catch (e: Exception) {
            Twig.warn { "WALLET - the saved recovery phrase cannot be read or is not valid: ${e.javaClass.simpleName}" }
            null
        }

    companion object {
        private const val MAX_CAUSE_DEPTH = 16

        /** The SDK proved that the wallet database belongs to a different recovery phrase. */
        fun isSeedMismatch(cause: Throwable?): Boolean = cause.hasInChain { it is InitializeException.SeedNotRelevant }

        private fun Throwable?.hasInChain(predicate: (Throwable) -> Boolean): Boolean =
            generateSequence(this) { it.cause?.takeIf { next -> next !== it } }
                .take(MAX_CAUSE_DEPTH)
                .any(predicate)
    }
}

/** The SDK's wallet data database on this phone. */
interface WalletDataDatabase {
    /**
     * Renames the wallet data database of [network] (with its journal files) so the SDK starts a new one. Never
     * deletes anything. Returns false, with every file left where it was, when that is not possible.
     */
    fun setAside(network: ZcashNetwork): Boolean
}

/** Restarts the app process. */
fun interface AppRestarter {
    fun restart()
}
