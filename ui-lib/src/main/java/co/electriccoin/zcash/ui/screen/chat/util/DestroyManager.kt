package co.electriccoin.zcash.ui.screen.chat.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * DESTROY-ADV-C (ZC-722): what a Destroy flow shows, read when it opens: whether the saved choice keeps the names, and
 * (B5) whether a list from an earlier Destroy is on the phone that this Destroy, with the default choice, erases.
 */
data class DestroyFlowChoice(
    val keepsContacts: Boolean = false,
    val erasesEarlierList: Boolean = false
)

/** What a Save of the choice did. */
enum class DestroyKeepSave {
    SAVED,
    KEY_FAILED,
    NOT_SAVED
}

/**
 * Manager for handling app destruction and remote kill functionality.
 *
 * DESTROY ALL: Clears all app data and requests uninstallation.
 * REMOTE KILL: Monitors incoming transactions for kill signal.
 */
class DestroyManager(
    private val context: Context,
    private val zchatPreferences: ZchatPreferences,
    private val walletCoordinator: WalletCoordinator,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val flexaRepository: FlexaRepository,
    // DESTROY-ADV-W (ZC-722): the chat contact book the copy reads, and the kept list it writes. Built without them
    // (or without its constructor) the manager keeps nothing.
    private val contactBook: ContactBook? = null,
    private val keptNames: KeptNames? = KeptNames.of(context)
) {
    companion object {
        private const val TAG = "DestroyManager"
        private const val MIN_KILL_PHRASE_LENGTH = 12

        // The limits of the wipe (ZC-692-W). Nothing after the delete of the recovery-phrase store waits longer
        // than these: a call that never answers cannot hold the wipe or the end of the process.
        private const val LIMIT_INSTALL_TIME_MS = 300L
        private const val LIMIT_KEYS_MS = 2_000L
        private const val LIMIT_SETTINGS_MS = 2_000L
        private const val LIMIT_TRACES_MS = 2_000L
        private const val LIMIT_CHAT_STORES_MS = 5_000L

        // W6 to W9 together: the settings, background work and traces, the chat stores, the synchronizer's close
        // (closeFlow().first() can suspend for ever when the synchronizer is wedged mid-sync) and Flexa.
        private const val LIMIT_MEMORY_STEPS_MS = 10_000L
        private const val LIMIT_UNINSTALL_MS = 2_000L
        private const val LIMIT_TASKS_MS = 1_000L

        // K1, the copy of a Destroy that keeps names: at most 2 s in all, each source at most 1 s.
        private const val LIMIT_COPY_MS = 2_000L
        private const val LIMIT_COPY_SOURCE_MS = 1_000L

        // ZC-1044: how long the wipe waits to close the copy's gate, held by a rename that does not return.
        private const val LIMIT_COPY_CLOSE_MS = 300L
        private const val NANOS_PER_MS = 1_000_000L

        // Upper bound on reading the chain tip when arming remote kill. getSynchronizer() waits for
        // readiness and networkHeight can be null until the first sync tick, so this must be
        // time-boxed — arming is a settings-screen action and must not be able to hang it.
        private const val ARMED_HEIGHT_LOOKUP_TIMEOUT_MS = 5_000L

        // Remote kill memo prefix from ZMSGConstants
        const val KILL_MEMO_PREFIX = ZMSGConstants.REMOTE_KILL_PREFIX
    }

    /**
     * Check if a transaction matches the remote kill criteria.
     *
     * @param amountZatoshi The amount of the transaction
     * @param memo The memo content
     * @return true if this is a valid kill signal
     */
    suspend fun isKillSignal(amountZatoshi: Long, memo: String?): Boolean {
        if (!zchatPreferences.isRemoteKillEnabled()) return false
        if (!zchatPreferences.hasRemoteKillPhrase()) return false

        val killAmount = zchatPreferences.getRemoteKillAmount()

        // Check if amount matches
        if (amountZatoshi != killAmount) return false

        // Check if memo contains the kill prefix and phrase
        if (memo == null) return false
        if (!memo.trim().startsWith(KILL_MEMO_PREFIX)) return false

        // Extract phrase from memo and verify against stored hash
        val phraseFromMemo = memo.trim().removePrefix(KILL_MEMO_PREFIX)
        return zchatPreferences.verifyRemoteKillPhrase(phraseFromMemo)
    }

    // ZC-692-W: destroyAll runs once per DestroyManager object. A plain field, false on an object built without its
    // constructor, read and set under the object's own lock; the app has one object per process.
    private var destroyStarted = false

    // This install's first-install time for the record of the wipe, read in the background when the object is
    // built, so that the record waits on nothing but the phone's own storage. 0 = not known (yet).
    @Volatile
    private var installTimeMs = 0L

    // K0 (DESTROY-ADV-W): the saved choice of what Destroy All keeps, with the wallet-scope generation it was read
    // at. It counts at Destroy only while that generation is the current one; null = not read (yet) = keep nothing.
    internal class KeepChoice(
        val keep: Set<String>,
        val generation: Long
    )

    @Volatile
    private var keepChoice: KeepChoice? = null

    // ZC-1043: every Save of the choice counts here, and keepChoice is set under the object's own lock (as destroyStarted;
    // a plain field, 0 on an object built without its constructor): a reload that began before a Save never puts the
    // older choice back over it.
    @Volatile
    private var keepRevision = 0L

    // After K1: whether the kept list's file exists (then W3, W5, W10 and W11 spare it and its key).
    @Volatile
    private var spareKeptList = false

    init {
        DestroyWipe.startThread("zchat-destroy-install-time") { installTimeMs = DestroyWipe.installTime(context) }
        DestroyWipe.startThread("zchat-destroy-keep-choice") { reloadDestroyKeep() }
    }

    /**
     * K0: reads the saved choice and the wallet-scope generation again (a Destroy flow that opens calls it, off the
     * main thread). A read across a change of wallet, or one that throws, leaves no choice: keep nothing.
     */
    fun reloadDestroyKeep(): Set<String> {
        val revision = keepRevision
        val read: KeepChoice? =
            try {
                val generation = zchatPreferences.walletScopeGeneration()
                val keep = zchatPreferences.getDestroyKeep()
                if (zchatPreferences.walletScopeGeneration() == generation) KeepChoice(keep, generation) else null
            } catch (t: Throwable) {
                Log.w(TAG, "the Destroy choice could not be read: ${t.javaClass.simpleName}")
                null
            }
        synchronized(this) {
            // ZC-1043: a Save since this read began wins; the reload then answers what the Save holds.
            if (keepRevision != revision) return keepChoice?.keep.orEmpty()
            keepChoice = read
        }
        return read?.keep.orEmpty()
    }

    /**
     * DESTROY-ADV-C: a Destroy flow that opens (the chat list's red button, Emergency Destroy, the Destroy PIN screen)
     * reads the choice again from the store, and whether an earlier list is on the phone (a file check; nothing is
     * decrypted). Off the main thread.
     */
    fun readDestroyFlowChoice(): DestroyFlowChoice {
        val keepsContacts = DestroyWipe.KEEP_CONTACTS in reloadDestroyKeep()
        return DestroyFlowChoice(keepsContacts = keepsContacts, erasesEarlierList = !keepsContacts && keptListExists())
    }

    /** Whether the kept list's file is on the phone. A file check. */
    fun keptListExists(): Boolean = DestroyWipe.keptListExists(context)

    /**
     * DESTROY-ADV-C: the one Save of the choice, for both set-up places (the options screen; the Destroy PIN set-up
     * of DESTROY-ADV-P), so that neither names the list's class. A kept choice first makes the list's key (nothing is
     * written when it cannot); [write] stores the choice; "Destroy everything" deletes the key again when no list is
     * on the phone. On success the choice is held in memory with the wallet-scope generation it was saved for.
     */
    suspend fun saveDestroyKeep(
        keep: Set<String>,
        write: suspend (Set<String>) -> Boolean
    ): DestroyKeepSave {
        val keepsContacts = DestroyWipe.KEEP_CONTACTS in keep
        if (keepsContacts && keptNames?.prepareKey() != true) return DestroyKeepSave.KEY_FAILED
        val written =
            try {
                write(keep)
            } catch (t: Throwable) {
                Log.w(TAG, "the Destroy choice was not saved: ${t.javaClass.simpleName}")
                false
            }
        if (!written) return DestroyKeepSave.NOT_SAVED
        if (!keepsContacts && !keptListExists()) keptNames?.eraseKeyOnly()
        val saved =
            try {
                KeepChoice(keep, zchatPreferences.walletScopeGeneration())
            } catch (t: Throwable) {
                null
            }
        synchronized(this) {
            keepRevision += 1
            keepChoice = saved
        }
        return DestroyKeepSave.SAVED
    }

    /** Whether this Destroy tries to keep the names: the choice in memory, read for the wallet that is loaded now. */
    private fun keepsContacts(): Boolean =
        try {
            val choice = keepChoice
            choice != null &&
                DestroyWipe.KEEP_CONTACTS in choice.keep &&
                contactBook != null &&
                keptNames != null &&
                zchatPreferences.walletScopeGeneration() == choice.generation
        } catch (t: Throwable) {
            false
        }

    /**
     * Destroy All (ZC-692-W; register ZC-692, the Destroy half of ZC-090, ZC-726; owner rule W-41: after Destroy All
     * nothing can be got back). The order:
     *  W1 the record of the wipe, W2 the delete of the recovery-phrase store, and only then everything that can wait:
     *  W3 the phone-security keys, W4 the app's other processes, W5 every other file by listing, W6 the wallet out
     *  of memory, W7 background work, notifications and the clipboard, W8 the chat stores in memory, W9 the
     *  synchronizer's close and Flexa; W10 the uninstall request; W11 the final pass; W12 the end of the process.
     *
     * It runs once per object: a second call returns at once. It runs from its first line to its last on a thread of
     * its own (not a shared pool that blocked work could have filled) under [NonCancellable], whatever happens to
     * the caller's scope, and the end of the process sits in a `finally`. While it runs, an uncaught error on any
     * other thread is only logged (a synchronizer whose database is deleted under it may throw); the handler of
     * before is put back after the request to end the process: in the app nothing runs after that request.
     */
    suspend fun destroyAll(requestUninstall: Boolean = true) {
        synchronized(this) {
            if (destroyStarted) return
            destroyStarted = true
        }
        Log.w(TAG, "destroyAll() called - beginning complete app destruction")
        val ended = CompletableDeferred<Unit>()
        Thread(
            {
                var handlerBefore: Thread.UncaughtExceptionHandler? = null
                var handlerSwapped = false
                try {
                    handlerBefore = DestroyWipe.quietUncaughtErrors()
                    handlerSwapped = true
                    runBlocking(NonCancellable) { performFullWipe() }
                    // W10: no uninstall request when the kept list exists (an uninstall would remove it).
                    if (requestUninstall && !spareKeptList) {
                        DestroyWipe.runWithin("zchat-destroy-W10-uninstall", LIMIT_UNINSTALL_MS) { requestUninstall() }
                    }
                    finalSweep()
                    // B10: with the kept list, ZCHAT is started once more to finish (after every cancel of W7, W11).
                    if (spareKeptList) attempt("W10 finishing start") { DestroyWipe.armFinishingStart(context) }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error during destruction: ${t.javaClass.simpleName}")
                } finally {
                    try {
                        forceKillApp()
                    } finally {
                        try {
                            if (handlerSwapped) DestroyWipe.restoreUncaughtHandler(handlerBefore)
                        } finally {
                            ended.complete(Unit)
                        }
                    }
                }
            },
            "zchat-destroy"
        ).start()
        withContext(NonCancellable) { ended.await() }
    }

    /**
     * W7, the app's own part: the sync service, the periodic sync and its alarm, then all work and its rows.
     * stopService and the cancels are asynchronous, so this narrows the window of live writers but does not close
     * it; [finalSweep] and the start pass are what finish it. Every call has its own catch.
     * A new wallet turns background sync on again (MainActivity.monitorForBackgroundSync).
     */
    private fun stopBackgroundWork() {
        // ZC-692-S: the calls live in DestroyWipe, so the start pass replays the same ones.
        DestroyWipe.stopBackgroundWork(context)
    }

    /**
     * W11, the final pass, right before the end of the process: W4, W5, W3 and W7's cancels again. Every store
     * object stays cached in this process with its map, and Android writes a missing file back on any commit or
     * apply, so a writer that ran after W5 (a recomposition, the sync service, the Nostr inbox, the uninstall
     * screen's start) may have brought a file back. The last act is one more listing, and what is still there is
     * logged as a count. The record stays: the next start reads it (batch ZC-692-S).
     */
    private fun finalSweep() {
        attempt("W11 processes") { DestroyWipe.endOtherProcesses(context) }
        attempt("W11 files") { DestroyWipe.sweepFiles(context, spareKeptList) }
        attempt("W11 keys") {
            val keys = DestroyWipe.startThread("zchat-destroy-W11-keys") { DestroyWipe.eraseKeys(spareKeptList) }
            DestroyWipe.waitUpTo(keys, LIMIT_KEYS_MS)
        }
        DestroyWipe.runWithin("zchat-destroy-W11-traces", LIMIT_TRACES_MS) {
            stopBackgroundWork()
            DestroyWipe.stopTraces(context)
        }
        attempt("W11 what is left") {
            val left = DestroyWipe.sweepFiles(context, spareKeptList)
            Log.w(TAG, "Destroy: files left in the app's own storage: ${left.size}")
        }
    }

    /**
     * W1 to W9. Called on the wipe's own thread under [NonCancellable]. Nothing in it waits without a limit, and
     * between its first line and the delete of the recovery-phrase store there is one step, the record, which reads
     * and writes files on the phone's own storage, and (ZC-1066) for a Destroy that keeps the names their copy (K1,
     * at most [LIMIT_COPY_MS]), so a kill at any point leaves either no record, or a record and the phrase store from
     * which the next start makes the copy, or the list. Only when the install time is not in memory yet does it ask
     * the phone for it once, for at most 0.3 s.
     */
    private suspend fun performFullWipe() {
        // K0: whether this Destroy tries to keep the names (decided once, from memory: no store is read for it).
        val keepContacts = keepsContacts()
        // W1: the record. If it cannot be written the wipe goes on (an emergency wipe is never refused).
        attempt("W1 record") {
            var installTime = installTimeMs
            if (installTime == 0L) {
                DestroyWipe.runWithin("zchat-destroy-W1-install-time", LIMIT_INSTALL_TIME_MS) {
                    installTimeMs = DestroyWipe.installTime(context)
                }
                installTime = installTimeMs
            }
            DestroyWipe.writeRecord(context, installTime, if (keepContacts) DestroyWipe.KEEP_CONTACTS else "")
        }

        // K1 (ZC-1066, Fable's decision of 19 Oct: W1 -> K1 -> W2): the copy of the names, after the record and before
        // the phrase store goes, and the one fact everything after it follows: does the kept list's file exist.
        // ZC-1044: a copy whose rename still holds its gate may still land: its file and key are spared then too.
        var copyMayLand = false
        if (keepContacts) attempt("K1 copy") { copyMayLand = copyKeptNames() }
        spareKeptList = keepContacts && (copyMayLand || DestroyWipe.keptListExists(context))

        // W2: the recovery phrase is not on the phone's storage after this line.
        attempt("W2 phrase store") { DestroyWipe.deletePhraseStore(context) }

        // The running synchronizer, from the provider's current value: never a call that waits for one to appear.
        var running: Synchronizer? = null
        attempt("synchronizer reference") { running = synchronizerProvider.synchronizer.value }

        // W3: the phone-security keys, on a thread of their own: a service that does not answer delays nothing.
        var keys: Thread? = null
        attempt("W3 keys") {
            keys = DestroyWipe.startThread("zchat-destroy-W3-keys") { DestroyWipe.eraseKeys(spareKeptList) }
        }

        // W4: no other process of the app takes part or keeps the phrase in its memory.
        attempt("W4 processes") { DestroyWipe.endOtherProcesses(context) }

        // W5: every other file, by listing. No erase call into the SDK: deleting its folder is what removes the data.
        attempt("W5 files") { DestroyWipe.sweepFiles(context, spareKeptList) }

        keys?.let { DestroyWipe.waitUpTo(it, LIMIT_KEYS_MS) }

        // W6 to W9 share one limit. W6: the encrypted settings first, so the wallet flow answers "no wallet" before
        // any other setting changes and the SDK cannot build a synchronizer from the phrase in memory.
        var left = LIMIT_MEMORY_STEPS_MS
        left -=
            step("zchat-destroy-W6-encrypted-settings", LIMIT_SETTINGS_MS, left) {
                runBlocking { encryptedPreferenceProvider().clearPreferences() }
            }
        left -=
            step("zchat-destroy-W6-standard-settings", LIMIT_SETTINGS_MS, left) {
                runBlocking { standardPreferenceProvider().clearPreferences() }
            }
        // W7: the service is stopped before the chat stores are cleared and before its channel is deleted.
        left -=
            step("zchat-destroy-W7-traces", LIMIT_TRACES_MS, left) {
                stopBackgroundWork()
                DestroyWipe.stopTraces(context)
            }
        // W8: empties the chat stores in memory, so a late writer writes nothing of value back.
        left -= step("zchat-destroy-W8-chat-stores", LIMIT_CHAT_STORES_MS, left) { zchatPreferences.clearAll() }
        // W9: the close of the synchronizer that ran at the start, and Flexa.
        left -=
            step("zchat-destroy-W9-synchronizer", left, left) {
                runBlocking { (running as? SdkSynchronizer)?.closeFlow()?.first() }
            }
        step("zchat-destroy-W9-flexa", left, left) { runBlocking { flexaRepository.disconnect() } }
        Log.w(TAG, "The wipe ran to its end")
    }

    /**
     * K1 (DESTROY-ADV-W): copies the names and addresses of the chat contacts into the kept list, at most
     * [LIMIT_COPY_MS] in all. The contact book and the nicknames are read each on its own worker for at most
     * [LIMIT_COPY_SOURCE_MS]; one name per address, the nickname first; a source that does not answer in time is left
     * out. Nothing is read from or written through the recovery-phrase store. When the limit is over the copy is given
     * up: under one lock with the rename, so a late answer writes nothing, then or afterwards.
     *
     * ZC-1044: the two sources run side by side and are waited for against ONE clock ([LIMIT_COPY_SOURCE_MS] from their
     * start), so the write keeps the rest of the limit; and the wipe closes the gate on a thread of its own, waiting at
     * most [LIMIT_COPY_CLOSE_MS]: a rename that does not return never holds the wipe.
     * @return true when the gate could not be closed: the rename holds it and may still land.
     */
    private fun copyKeptNames(): Boolean {
        val store = keptNames ?: return false
        val book = contactBook ?: return false
        val gate = Any()
        var open = true
        val worker =
            DestroyWipe.startThread("zchat-destroy-K1-copy") {
                var contacts: List<Pair<String, String>>? = null
                var nicknames: Map<String, String>? = null
                val sourcesEnd = System.nanoTime() + LIMIT_COPY_SOURCE_MS * NANOS_PER_MS
                val contactsRead =
                    DestroyWipe.startThread("zchat-destroy-K1-contacts") {
                        contacts = book.getAllContacts().map { it.address to it.name }
                    }
                val nicknamesRead =
                    DestroyWipe.startThread("zchat-destroy-K1-nicknames") { nicknames = zchatPreferences.getAllNicknames() }
                DestroyWipe.waitUpTo(contactsRead, msUntil(sourcesEnd))
                DestroyWipe.waitUpTo(nicknamesRead, msUntil(sourcesEnd))
                val names = LinkedHashMap<String, String>()
                if (!contactsRead.isAlive) contacts?.forEach { (address, name) -> if (name.isNotBlank()) names[address] = name }
                if (!nicknamesRead.isAlive) nicknames?.forEach { (address, name) -> if (name.isNotBlank()) names[address] = name }
                if (names.isNotEmpty()) {
                    store.addIf(names.map { KeptName(it.key, it.value) }, gate) { open }
                }
            }
        DestroyWipe.waitUpTo(worker, LIMIT_COPY_MS)
        val close = DestroyWipe.startThread("zchat-destroy-K1-close") { synchronized(gate) { open = false } }
        DestroyWipe.waitUpTo(close, LIMIT_COPY_CLOSE_MS)
        if (close.isAlive) {
            Log.w(TAG, "the copy's rename does not return: the wipe goes on, and its list may still land")
            return true
        }
        if (worker.isAlive) store.deleteTemp()
        return false
    }

    /** ZC-1044: the ms left until [endNanos] (at least 1: a wait of 0 would not look at the thread). */
    private fun msUntil(endNanos: Long): Long = ((endNanos - System.nanoTime()) / NANOS_PER_MS).coerceAtLeast(1L)

    /** Runs [block] in its own catch: a step that throws, whatever it throws, never stops the wipe. */
    private inline fun attempt(
        what: String,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$what: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Runs [block] on a thread of its own and waits for at most [limitMs] and at most [leftMs] (what is left of a
     * shared limit). Returns the ms it waited; with nothing left the step still starts and is not waited for.
     */
    private fun step(
        name: String,
        limitMs: Long,
        leftMs: Long,
        block: () -> Unit
    ): Long {
        val limit = minOf(limitMs, leftMs)
        if (limit <= 0L) {
            attempt(name) { DestroyWipe.startThread(name, block) }
            return 0L
        }
        return DestroyWipe.runWithin(name, limit, block)
    }

    /**
     * Request app uninstallation.
     * This will open a system dialog asking the user to confirm.
     */
    private fun requestUninstall() {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // If uninstall request fails, at least the data is cleared
        }
    }

    /**
     * Validate a kill phrase.
     * Must be at least 12 characters long.
     */
    fun isValidKillPhrase(phrase: String): Boolean {
        return phrase.length >= MIN_KILL_PHRASE_LENGTH
    }

    /**
     * Set up remote kill with validation.
     *
     * NOT REACHABLE: this function has no caller in any commit on any ref, and no build has ever had
     * an arming screen (2026-09-15: all-refs `git log -S` scan; a read-only investigation also found no
     * invoke of it in a dexdump of the shipped v2.9.3 APK). The arm-time anchor, tip-read timeout, stale-anchor drop and disarm/re-arm flows
     * below therefore never run in production, and the commit messages of 93a4a279a and bc68c80f2
     * that describe them as the primary mechanism are inaccurate on that point. The floor that
     * actually runs is ChatViewModel.anchorRemoteKillFloorIfUnset. Kept as-is under the feature
     * freeze; do not wire an arming UI without re-reviewing the floor semantics.
     *
     * @return true if setup was successful
     */
    suspend fun setupRemoteKill(phrase: String, amountZatoshi: Long): Boolean {
        if (!isValidKillPhrase(phrase)) return false
        if (amountZatoshi <= 0) return false

        zchatPreferences.setRemoteKillPhrase(phrase)
        zchatPreferences.setRemoteKillAmount(amountZatoshi)

        // Anchor the replay floor BEFORE enabling: only transactions mined above this height can
        // ever be treated as kill signals. Everything already in the wallet's history predates
        // arming and was therefore never a kill signal — without this, ZChat's full-history rescan
        // would re-offer that backlog to the kill check on every cold start and wipe the wallet.
        // Time-boxed: a wedged synchronizer must not hang the settings screen. On failure the
        // height stays 0 and the kill check fails CLOSED until a scan can anchor it from the tip.
        val armedHeight =
            withTimeoutOrNull(ARMED_HEIGHT_LOOKUP_TIMEOUT_MS) {
                runCatching {
                    synchronizerProvider
                        .getSynchronizer()
                        .networkHeight
                        .first { it != null }
                        ?.value
                }.getOrNull()
            }
        if (armedHeight != null) {
            zchatPreferences.setRemoteKillArmedHeight(armedHeight)
        } else {
            // Drop any anchor rather than leave a STALE one from an earlier arming: this may be a
            // re-arm, and a floor from the previous arming would leave everything received in
            // between eligible to wipe the wallet. 0 = unknown = fail closed until the next scan
            // re-anchors at the current tip.
            zchatPreferences.setRemoteKillArmedHeight(0L)
            Log.w(TAG, "setupRemoteKill: no network height available; floor will anchor on next scan")
        }

        zchatPreferences.setRemoteKillEnabled(true)

        return true
    }

    /**
     * Disable remote kill.
     */
    fun disableRemoteKill() {
        zchatPreferences.setRemoteKillEnabled(false)
    }

    /**
     * Get the kill memo format hint (without the actual phrase).
     * Format: ZCHAT_DESTROY:<your_secret_phrase>
     *
     * NOTE: The actual phrase is stored as a hash and cannot be recovered.
     * User must remember/write down their phrase when setting up remote kill.
     */
    fun getKillMemoFormat(): String {
        return "${KILL_MEMO_PREFIX}<your_secret_phrase>"
    }

    /**
     * Check if remote kill is configured (enabled and has phrase).
     */
    fun isRemoteKillConfigured(): Boolean {
        return zchatPreferences.isRemoteKillEnabled() && zchatPreferences.hasRemoteKillPhrase()
    }

    /**
     * W12: ends the app's process, so nothing of the wallet stays in memory. The recent-apps card goes first, within
     * a limit; the request to end the process is never skipped.
     */
    private fun forceKillApp() {
        try {
            DestroyWipe.runWithin("zchat-destroy-W12-tasks", LIMIT_TASKS_MS) {
                // Clear all activities from the task stack
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                activityManager?.appTasks?.forEach { task -> task.finishAndRemoveTask() }
            }
        } finally {
            // Kill the process - this is the nuclear option that ensures complete reset
            DestroyWipe.endThisProcess()
        }
    }
}

/**
 * ZC-1066 (Fable's decision of 19 Oct 2026, W-57) — the start pass's copy of the kept names: a Destroy that keeps the
 * names was cut after its record and before its copy, so the phrase store, the contact book and the nicknames are still
 * on the phone. The copy is DestroyManager's (K1): the contact book and the nicknames are read side by side for at most
 * [LIMIT_SOURCE_MS] (one name per address, the nickname first; a source that does not answer in time is left out) and
 * written whole into the kept list (a temp file, then a rename). Nothing is read from the recovery-phrase store.
 *
 * It opens the contact book's and the nicknames' stores; the start pass deletes them right after and then ends its
 * process, so nothing read here is what the app opens afterwards.
 */
internal object KeptNamesStartCopy {
    private const val TAG = "KeptNamesStartCopy"
    private const val LIMIT_SOURCE_MS = 1_000L
    private const val NANOS_PER_MS = 1_000_000L

    /** @return true when the kept list's file exists afterwards. */
    fun copy(context: Context): Boolean {
        val store = KeptNames.of(context)
        val contacts = AtomicReference<List<Pair<String, String>>?>(null)
        val nicknames = AtomicReference<Map<String, String>?>(null)
        val end = System.nanoTime() + LIMIT_SOURCE_MS * NANOS_PER_MS
        val contactsRead =
            DestroyWipe.startThread("zchat-start-names-contacts") {
                contacts.set(ContactBookImpl(context).getAllContacts().map { it.address to it.name })
            }
        val nicknamesRead =
            DestroyWipe.startThread("zchat-start-names-nicknames") { nicknames.set(ZchatPreferencesImpl(context).getAllNicknames()) }
        DestroyWipe.waitUpTo(contactsRead, msUntil(end))
        DestroyWipe.waitUpTo(nicknamesRead, msUntil(end))
        val names = LinkedHashMap<String, String>()
        contacts.get()?.forEach { (address, name) -> if (name.isNotBlank()) names[address] = name }
        nicknames.get()?.forEach { (address, name) -> if (name.isNotBlank()) names[address] = name }
        if (names.isEmpty()) {
            Log.w(TAG, "no name was read in time: no list")
            return false
        }
        return store.add(names.map { KeptName(it.key, it.value) }) && store.listFileExists()
    }

    private fun msUntil(endNanos: Long): Long = ((endNanos - System.nanoTime()) / NANOS_PER_MS).coerceAtLeast(1L)
}
