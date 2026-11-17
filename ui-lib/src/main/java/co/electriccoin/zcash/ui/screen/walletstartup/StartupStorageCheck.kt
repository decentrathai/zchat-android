package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.preference.EncryptedPreferencesUnavailableException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.PausedChatRestores
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.util.ResetWipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Opens the wallet's storage before ZCHAT shows anything that needs it (R2-3): the encrypted store that holds
 * the recovery phrase ([openSeedStore]) and the encrypted chat stores ([openChatStores]).
 *
 * Both are protected by the phone's security system (Android Keystore), which can fail for a while (right
 * after an update or a reboot). Opened lazily from a screen, such a failure stopped ZCHAT with no message on
 * every launch. Here it becomes a [State] the start-up screen explains, with Try again (a restart). Nothing is
 * cleared: the seed store is only read, and the chat stores keep their own KR-1 rules (openEncryptedStore).
 * Until the check says [State.Ready], the app stays on its start-up state ([gatedSecretState]), so no screen
 * that needs these stores is opened.
 *
 * ZC-692-S: before any store is opened it reads what this process's start made of a Destroy All record
 * ([destroyStartOutcome]). Two outcomes become states of their own, with no way on in this process:
 * [State.DestroyUnfinished] and [State.DestroyRecordStuck]. Every other outcome changes nothing here.
 *
 * ZC-733-A: in the app [openChatStores] is [openStoresAtStart]: the chat storage, then the contact book, the picture
 * index and the AI store (the last only when its file exists), each in its own catch, so none of them ever changes the
 * state; a store one of these asks set aside is offered in that same start by the next step (the kept copies).
 */
class StartupStorageCheck(
    private val openSeedStore: suspend () -> Unit,
    private val openChatStores: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val keptChatCopies: () -> List<String> = { emptyList() },
    private val chatCopyRestoreFailed: () -> List<String> = { emptyList() },
    private val chatCopyRestorePaused: () -> PausedChatRestores = { PausedChatRestores.NONE },
    private val destroyStartOutcome: () -> DestroyWipe.StartOutcome = { DestroyWipe.StartOutcome.None },
) {
    sealed interface State {
        /** Not opened yet. */
        data object Checking : State

        /** Both stores opened. */
        data object Ready : State

        /** The store with the recovery phrase could not be read in this launch. Nothing in it was changed. */
        data class SeedStoreUnreadable(
            val cause: Throwable
        ) : State

        /** The encrypted chat stores could not be opened in this launch. Their data is still on disk. */
        data class ChatStoresUnavailable(
            val cause: Throwable
        ) : State

        /**
         * R3-3: the chat stores were recreated after they could not be read, and exact copies of them were kept
         * ([copies]); the user chooses to restore them or continue without them before the app opens.
         *
         * C6 (R6-4 review finding 14): [usedAfterPause] when the user chatted in one of those stores after Continue on
         * the paused screen, so a restore sets the current chats aside (kept as a copy and offered back).
         */
        data class ChatCopyKept(
            val copies: List<String>,
            val usedAfterPause: Boolean = false,
        ) : State {
            val notice: Throwable = ChatStoreCopyKeptNotice(copies, usedAfterPause)
        }

        /**
         * R4-5: the user put a kept copy back and the store still could not be read — the phone's security key for
         * it is gone for good. Said once, so the same restore is not offered at every launch; the copies stay.
         */
        data class ChatCopyRestoreFailed(
            val stores: List<String>
        ) : State {
            val notice: Throwable = ChatStoreCopyRestoreFailedNotice(stores)
        }

        /**
         * B5: the user put a kept copy back and the phone's security system did not answer, again — nothing said
         * the key was lost. The copy is kept, not offered while the outage lasts, and offered again once the
         * phone's security system answers. Said once per pause; the copies stay.
         */
        data class ChatCopyRestorePaused(
            val stores: List<String>
        ) : State {
            val notice: Throwable = ChatStoreCopyRestorePausedNotice(stores)
        }

        /**
         * F10 (fix-all 2.13.0, chat copy #16): a kept copy failed to open again after its last pause, so ZCHAT stopped
         * offering it by itself (parked). Nothing says the key is lost; the copy stays on the phone, unanswered, and
         * More → Advanced Settings → Restore old chats offers it again. Said once per park.
         */
        data class ChatCopyRestoreParked(
            val stores: List<String>
        ) : State {
            val notice: Throwable = ChatStoreCopyRestoreParkedNotice(stores)
        }

        /**
         * ZC-692-S: this start finished a Destroy All that had been cut off, and the check afterwards still found
         * something of [kinds] (`DestroyWipe.KIND_*`): files, keys, notifications, another process. No store was
         * opened. The screen names what is left and has one button, which asks for the uninstall.
         */
        data class DestroyUnfinished(
            val kinds: Set<String>
        ) : State {
            val notice: Throwable = DestroyUnfinishedNotice(kinds)
        }

        /**
         * ZC-692-S: this start finished a Destroy All and could not remove its record. No store was opened, and no
         * wallet can be created or restored in this process: the next start would judge that record again.
         */
        data object DestroyRecordStuck : State {
            val notice: Throwable = DestroyRecordStuckNotice()
        }

        /**
         * ZC-090-P: this start found a Reset ZCHAT that was cut off and could not finish it (something of its delete set
         * is left, or a Destroy record lies beside it). No store was opened and no wallet opens in this process; the
         * screen offers Try again (a restart) and Uninstall.
         */
        data object ResetUnfinished : State {
            val notice: Throwable = ResetUnfinishedNotice()
        }
    }

    /**
     * ZC-090-P: what this process's start made of a Reset record (`ResetWipe.startOutcome`). A property, not a constructor
     * parameter: the constructor keeps its seven parameters.
     */
    var resetStartOutcome: () -> ResetWipe.Outcome = { ResetWipe.startOutcome }

    private val mutableState = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = mutableState

    private val mutex = Mutex()

    /** Opens both stores once per process; later calls return at once. Never throws a storage error. */
    suspend fun run() {
        mutex.withLock {
            if (mutableState.value != State.Checking) return
            mutableState.value = withContext(ioDispatcher) { openStores() }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun openStores(): State {
        // ZC-692-S: first, and before any store is opened. Only these two outcomes stop the start.
        when (val outcome = destroyStartOutcome()) {
            is DestroyWipe.StartOutcome.Unfinished -> return State.DestroyUnfinished(outcome.kinds)
            DestroyWipe.StartOutcome.RecordStuck -> return State.DestroyRecordStuck
            DestroyWipe.StartOutcome.None,
            DestroyWipe.StartOutcome.Finished,
            DestroyWipe.StartOutcome.Ignored -> Unit
        }
        // ZC-090-P: a Reset that could not be finished stops the start here too; every other outcome changes nothing.
        if (resetStartOutcome() == ResetWipe.Outcome.NOT_FINISHED) return State.ResetUnfinished
        try {
            openSeedStore()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.error { "WALLET - the recovery phrase store cannot be read right now: ${startupErrorName(e)}" }
            return State.SeedStoreUnreadable(e)
        }
        try {
            openChatStores()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.error { "CHAT - the encrypted chat stores cannot be opened right now: ${startupErrorName(e)}" }
            return State.ChatStoresUnavailable(e)
        }
        val failedRestore =
            try {
                chatCopyRestoreFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error { "CHAT - the answered restores of the chat stores cannot be read: ${e.javaClass.simpleName}" }
                emptyList()
            }
        if (failedRestore.isNotEmpty()) {
            Twig.warn { "CHAT - ${failedRestore.size} restored chat store(s) could not be read either; telling the user once" }
            return State.ChatCopyRestoreFailed(failedRestore)
        }
        val paused =
            try {
                chatCopyRestorePaused()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error { "CHAT - the paused restores of the chat stores cannot be read: ${e.javaClass.simpleName}" }
                PausedChatRestores.NONE
            }
        if (paused.unexplained.isNotEmpty()) {
            Twig.warn { "CHAT - ${paused.unexplained.size} chat store restore(s) paused by a Keystore outage; telling the user once" }
            return State.ChatCopyRestorePaused(paused.unexplained)
        }
        if (paused.unexplainedParked.isNotEmpty()) {
            Twig.warn { "CHAT - ${paused.unexplainedParked.size} kept chat copies parked after their last pause; telling the user once" }
            return State.ChatCopyRestoreParked(paused.unexplainedParked)
        }
        val copies =
            try {
                // B5: a paused store's copies wait for the phone's security system; they are not offered meanwhile.
                // F10: nor is a copy held by its own pause or park (the hold is the copy's, so chats a new outage
                // set aside meanwhile are offered).
                keptChatCopies().filter {
                    it !in paused.held && it.substringBefore(UNREADABLE_STORE_COPY_MARKER) !in paused.stores
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error { "CHAT - the kept copies of the chat stores cannot be listed: ${e.javaClass.simpleName}" }
                emptyList()
            }
        if (copies.isNotEmpty()) {
            Twig.warn { "CHAT - the chat stores were recreated; ${copies.size} kept copies wait for the user's choice" }
            val usedAfterPause = copies.any { it.substringBefore(UNREADABLE_STORE_COPY_MARKER) in paused.usedAfterPause }
            return State.ChatCopyKept(copies, usedAfterPause)
        }
        return State.Ready
    }
}

/**
 * ZC-733-A: what the start-up check opens in the app: [openChatStorage] (the visible-start flag and the chat storage,
 * whose failure is the check's own state), then the start's ask of the contact book, the picture index and the AI
 * store. Each ask sits in its own catch that logs the error's class name and nothing else: a failure of these three
 * never stops the start and never brings the Try-again screen.
 */
@Suppress("TooGenericExceptionCaught")
fun openStoresAtStart(
    openChatStorage: () -> Unit,
    askContactBook: () -> Unit,
    askPictureIndex: () -> Unit,
    askAiStore: () -> Unit,
) {
    openChatStorage()
    listOf("contact book" to askContactBook, "picture index" to askPictureIndex, "AI store" to askAiStore).forEach { (store, ask) ->
        try {
            ask()
        } catch (e: Exception) {
            Twig.warn { "CHAT - the $store could not be opened at the start: ${e.javaClass.simpleName}" }
        }
    }
}

/**
 * The error the wallet start-up screen is shown for, or null when there is none: a storage error found at
 * start-up first, else a synchronizer that could not start.
 */
fun startupFailureCause(
    synchronizerError: SynchronizerError?,
    storage: StartupStorageCheck.State,
): Throwable? =
    when (storage) {
        is StartupStorageCheck.State.SeedStoreUnreadable -> storage.cause
        is StartupStorageCheck.State.ChatStoresUnavailable -> storage.cause
        is StartupStorageCheck.State.ChatCopyKept -> storage.notice
        is StartupStorageCheck.State.ChatCopyRestoreFailed -> storage.notice
        is StartupStorageCheck.State.ChatCopyRestorePaused -> storage.notice
        is StartupStorageCheck.State.ChatCopyRestoreParked -> storage.notice
        is StartupStorageCheck.State.DestroyUnfinished -> storage.notice
        StartupStorageCheck.State.DestroyRecordStuck -> StartupStorageCheck.State.DestroyRecordStuck.notice
        StartupStorageCheck.State.ResetUnfinished -> StartupStorageCheck.State.ResetUnfinished.notice
        StartupStorageCheck.State.Checking,
        StartupStorageCheck.State.Ready -> (synchronizerError as? SynchronizerError.Startup)?.cause
    }

/** How many links of a cause chain [startupErrorName] reads, as [WalletStartupRepair] does for its offer. */
private const val MAX_ERROR_NAME_DEPTH = 16

/**
 * FD-8b: the error named on the start-up safety screen ("Error: %1$s") for [cause], or null when there is none.
 *
 * The chat stores are opened through Koin, which wraps the storage error in its own exception (and on a release build
 * R8 merges some of Koin's error classes into others), so the class of [cause] itself names Koin, not the problem.
 * Named here instead: the first ZCHAT storage error in the chain ([EncryptedStoreUnavailableException],
 * [EncryptedPreferencesUnavailableException]), else the first error that is not Koin's, plus the chain's root cause
 * when it differs (for example "EncryptedStoreUnavailableException / KeyStoreException"). Only class names: an
 * exception message can hold store names and is not for users. At most [MAX_ERROR_NAME_DEPTH] links are read, and a
 * cause cycle stops at its first repeat.
 */
fun startupErrorName(cause: Throwable?): String? {
    val chain = causeChain(cause)
    if (chain.isEmpty()) return null
    val firstIndex =
        chain
            .indexOfFirst { it is EncryptedStoreUnavailableException || it is EncryptedPreferencesUnavailableException }
            .takeIf { it >= 0 }
            ?: chain.indexOfFirst { !it.isKoinError() }.takeIf { it >= 0 }
            ?: return errorClassName(chain.last())
    val first = errorClassName(chain[firstIndex])
    val root = errorClassName(chain.subList(firstIndex, chain.size).last { !it.isKoinError() })
    return if (root == first) first else "$first / $root"
}

private fun causeChain(cause: Throwable?): List<Throwable> {
    val chain = ArrayList<Throwable>()
    var next = cause
    while (next != null && chain.size < MAX_ERROR_NAME_DEPTH && chain.none { it === next }) {
        chain += next
        next = next.cause
    }
    return chain
}

private fun Throwable.isKoinError(): Boolean = javaClass.name.startsWith("org.koin.")

private fun errorClassName(error: Throwable): String =
    error.javaClass.simpleName.ifEmpty { error.javaClass.name.substringAfterLast('.') }

/**
 * The wallet state the app acts on: [SecretState.LOADING] until the storage check says [StartupStorageCheck.State.Ready],
 * so neither onboarding nor the main app (whose screens need the stores) opens before or over a storage error.
 */
fun gatedSecretState(
    secretState: SecretState,
    storage: StartupStorageCheck.State,
): SecretState = if (storage == StartupStorageCheck.State.Ready) secretState else SecretState.LOADING

/** Whether the splash screen may stay: only while nothing can be shown yet, never over a storage error. */
fun isStartupStillLoading(
    secretState: SecretState,
    storage: StartupStorageCheck.State,
): Boolean =
    when (storage) {
        StartupStorageCheck.State.Checking -> true
        StartupStorageCheck.State.Ready -> secretState == SecretState.LOADING
        is StartupStorageCheck.State.SeedStoreUnreadable,
        is StartupStorageCheck.State.ChatStoresUnavailable,
        is StartupStorageCheck.State.ChatCopyKept,
        is StartupStorageCheck.State.ChatCopyRestoreFailed,
        is StartupStorageCheck.State.ChatCopyRestorePaused,
        is StartupStorageCheck.State.ChatCopyRestoreParked,
        is StartupStorageCheck.State.DestroyUnfinished,
        StartupStorageCheck.State.DestroyRecordStuck,
        StartupStorageCheck.State.ResetUnfinished -> false
    }

/**
 * R3-3: what the start-up screen is shown for when chat stores were recreated and their copies kept. C6: with
 * [usedAfterPause] when a restore would set chats written after a paused restore aside.
 */
class ChatStoreCopyKeptNotice(
    val copies: List<String>,
    val usedAfterPause: Boolean = false,
) : IllegalStateException("The chat stores could not be read; exact copies of them were kept (${copies.size})")

/** R4-5: what the start-up screen is shown for when a copy the user put back could not be read either. */
class ChatStoreCopyRestoreFailedNotice(
    val stores: List<String>
) : IllegalStateException("The chat stores put back from their kept copies still cannot be read (${stores.size})")

/** B5: what the start-up screen is shown for when a put-back copy could not be unlocked during a Keystore outage. */
class ChatStoreCopyRestorePausedNotice(
    val stores: List<String>
) : IllegalStateException(
        "The chat stores put back from their kept copies could not be unlocked right now; the copies are kept (${stores.size})"
    )

/** F10: what the start-up screen is shown for when a kept copy was parked after its last pause. */
class ChatStoreCopyRestoreParkedNotice(
    val stores: List<String>
) : IllegalStateException(
        "A kept copy of the chat stores could not be unlocked after its last pause; it is kept and no longer offered by itself " +
            "(${stores.size})"
    )

/** ZC-692-S: what the start-up screen is shown for when a finished Destroy All left something it could not remove. */
class DestroyUnfinishedNotice(
    val kinds: Set<String>
) : IllegalStateException("Destroy All could not remove everything (${kinds.size} kind(s) left)")

/** ZC-692-S: what the start-up screen is shown for when the record of a finished Destroy All could not be removed. */
class DestroyRecordStuckNotice : IllegalStateException("The record of a Destroy All could not be removed")

/** ZC-090-P: what the start-up screen is shown for when a Reset ZCHAT that was cut off could not be finished. */
class ResetUnfinishedNotice : IllegalStateException("A Reset ZCHAT that was cut off could not be finished")
