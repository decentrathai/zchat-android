package co.electriccoin.zcash.ui.screen.chat.util

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import co.electriccoin.zcash.global.MainProcess
import co.electriccoin.zcash.spackle.getPackageInfoCompat
import co.electriccoin.zcash.spackle.process.ProcessNameCompat
import co.electriccoin.zcash.ui.service.SyncForegroundService
import co.electriccoin.zcash.work.DestroyFinishWorker
import co.electriccoin.zcash.work.SyncAlarmScheduler
import co.electriccoin.zcash.work.WorkIds
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * ZC-692-W (register ZC-692, the Destroy half of ZC-090, ZC-726; owner rule W-41: after Destroy All nothing can be
 * got back): the single steps of the wipe. [DestroyManager] holds their order and their limits.
 *
 * ZC-692-S: [atProcessStart] finishes a Destroy that was cut off, and only its own. It runs as the first statement of
 * the app's start, before anything of the app has opened a store, and acts only on a record that is whole, belongs
 * to this install and names the phrase store that is on the phone (or none is left). A wipe is never inferred: with
 * no record it does nothing, and a record that does not fit deletes nothing.
 *
 * It needs a [Context] and nothing else: no preferences class, no dependency graph, no store of the app. Files are
 * found by LISTING the app's own storage, so a file nobody named is deleted too, and the proof of a wipe is the
 * list of FILES that are left ([sweepFiles]), never a list of cleared keys: a store file can exist with its key
 * material in the clear (ZC-705), so deleting the phone-security keys is not what makes the data unreadable.
 *
 * Everything that talks to Android or to the disk in a way a JVM test cannot follow sits behind a small private
 * function field whose default is the real call. No shipped code can replace one (they are private); the test
 * fixture `FakePhone` does it by reflection and puts every default back when it closes.
 */
object DestroyWipe {
    private const val TAG = "DestroyWipe"

    // What covers a storage place, a key, a process or a trace (DestroyInventoryTest names these lines).
    const val LINE_KEYS = "W3 keys"
    const val LINE_PROCESS = "W4 process"
    const val LINE_OWN_STORAGE = "W5 own storage"
    const val LINE_EXTERNAL = "W5 external"
    const val LINE_NOTIFICATIONS = "W7 notifications"
    const val LINE_CLIPBOARD = "W7 clipboard"
    const val LINE_BACKGROUND_WORK = "W7 background work"

    /** The record of a Destroy: `no_backup/zchat_destroy_pending`. It is written under [RECORD_TEMP_NAME] first. */
    const val RECORD_NAME = "zchat_destroy_pending"
    const val RECORD_TEMP_NAME = "zchat_destroy_pending.tmp"

    /** ZC1014-X1: beside the record, the mark of the offer to run Destroy again (it survives the end of the process). */
    const val RERUN_OFFER_NAME = "zchat_destroy_rerun_offer"

    /** ZC1014-X2: beside the record, how many starts could not check its seal (a number on one line). */
    const val SEAL_TRIES_NAME = "zchat_destroy_seal_tries"

    // ZC1014-X2: after this many starts that could not check the seal, the record is set aside and Destroy offered again.
    const val MAX_SEAL_TRIES = 3
    const val RECORD_HEADER = "zchat-destroy 1"
    const val STORE_NONE = "none"
    private const val RECORD_LINES = 5

    /**
     * DESTROY-ADV-W (ZC-722; owner W-47): the one value the record's keep line may hold besides empty. With it, and
     * only while the kept list's file exists, the wipe and the start pass spare exactly [KEPT_BY_CONTACTS].
     */
    const val KEEP_CONTACTS = "contacts"

    /** What a Destroy with the "keep names" choice spares: the kept list's file and its own key. Nothing else. */
    val KEPT_BY_CONTACTS: List<String> = listOf("no_backup/${KeptNames.FILE_NAME}", KeptNames.KEY_ALIAS)

    /** The unique name of the one-time job a kept Destroy enqueues (B10). */
    const val START_JOB_NAME = "zchat_destroy_finish"

    /** The file that holds the saved wallet (the recovery phrase), in `shared_prefs/`, and its twin during a write. */
    const val PHRASE_STORE_FILE = "co.electriccoin.zcash.encrypted.xml"
    const val PHRASE_STORE_BACKUP_FILE = "co.electriccoin.zcash.encrypted.xml.bak"

    private const val KEY_KEYSET_ENTRY = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_KEYSET_ENTRY = "__androidx_security_crypto_encrypted_prefs_value_keyset__"

    /** The two phone-security keys the app makes by name; every other name comes from the listing. */
    val KNOWN_KEYS: List<String> = listOf("_androidx_security_master_key_", "zchat_ai_master_key")

    private const val FOLDER_PREFS = "shared_prefs"
    private const val FOLDER_NO_BACKUP = "no_backup"
    private const val FOLDER_LIB = "lib"
    private const val FOLDER_CODE_CACHE = "code_cache"
    private const val WORK_TABLE_PREFIX = "androidx.work.workdb"

    /**
     * Everything of the app's own storage that may be left when Destroy hands over to the end of its process (D1):
     * the system's two folders, the job library's own table (the library holds it open; its rows are cancelled and
     * cleared in W7) and the record. No notification channel is on this list: W7 deletes every one.
     */
    val MAY_BE_LEFT: List<String> =
        listOf(
            FOLDER_LIB,
            FOLDER_CODE_CACHE,
            "$FOLDER_NO_BACKUP/$WORK_TABLE_PREFIX*",
            "$FOLDER_NO_BACKUP/$RECORD_NAME",
        )

    const val LIMIT_PROCESS_LIST_MS = 500L
    const val LIMIT_PROCESS_END_MS = 500L
    private const val PROCESS_POLL_MS = 25L
    private const val NANOS_PER_MS = 1_000_000L

    // ZC-1014: the seal of a keep record is made within this limit at W1 (a phone that cannot make it in time writes the
    // record without one: a cut before the phrase-store delete is then not finished, see DestroyRecordSeal).
    const val LIMIT_SEAL_MS = 500L

    // The limits of the start pass (ZC-692-S): a call that never answers cannot hold the app's start.
    const val LIMIT_START_QUESTION_MS = 2_000L
    const val LIMIT_START_KEYS_MS = 2_000L
    const val LIMIT_START_TRACES_MS = 2_000L
    const val LIMIT_START_NOTIFICATIONS_MS = 1_000L

    // ZC-1066: the start pass's copy of the kept names (two reads of at most a second each, then the write).
    const val LIMIT_START_NAMES_MS = 2_500L

    /** The kinds of things the check after a start pass can find still there ([StartOutcome.Unfinished]). */
    const val KIND_FILES = "files"
    const val KIND_KEYS = "keys"
    const val KIND_NOTIFICATIONS = "notifications"
    const val KIND_PROCESS = "process"

    /** ZC-888: background jobs still pending after the pass. */
    const val KIND_JOBS = "jobs"

    // ZC-888: how long a channel delete Android refuses (the channel of a sync service still stopping) is tried again.
    const val LIMIT_CHANNEL_RETRY_MS = 1_000L

    /**
     * What this process's start made of a record ([atProcessStart]); the start-up gate reads it before it opens a
     * store.
     */
    sealed class StartOutcome {
        /** No record: an ordinary start. Also in a process that is not the main one, and when the start cannot judge. */
        object None : StartOutcome()

        /** A valid record's pass ran, nothing is left, the record is gone. Nothing is shown. */
        object Finished : StartOutcome()

        /** A valid record's pass ran and the check still found [kinds]; the record is gone. Shown once. */
        data class Unfinished(
            val kinds: Set<String>
        ) : StartOutcome()

        /** A record that did not fit: nothing was deleted and the record was removed. Nothing is shown. */
        object Ignored : StartOutcome()

        /**
         * A valid record's pass ran and the record could not be removed, or (ZC692S-X1) a record that did not fit could
         * not be removed while no phrase store is on the phone: no wallet may be made in this process.
         */
        object RecordStuck : StartOutcome()
    }

    // ------------------------------------------------------------------ state of this process (three fields)

    /**
     * True after a start pass on a valid record: this process starts no background sync until the app itself turns
     * it on for a wallet. Read by the sync alarm's receiver and by the sync service; cleared by
     * `WorkIds.enableBackgroundSynchronization` only.
     */
    @Volatile
    var backgroundSyncHeld: Boolean = false

    /** Set once by [atProcessStart]. */
    @Volatile
    var startOutcome: StartOutcome = StartOutcome.None

    /** The path of the record file this process wrote, or null. Such a file is never written again. */
    @Volatile
    private var recordWrittenByThisProcess: String? = null

    // ------------------------------------------------------------------ replaceable calls (defaults: the real ones)

    private var keystoreAliases: () -> List<String> = { androidKeyStore().aliases().toList() }
    private var keystoreDelete: (String) -> Unit = { name -> androidKeyStore().deleteEntry(name) }
    private var keystoreContains: (String) -> Boolean = { name -> androidKeyStore().containsAlias(name) }

    /** Every process the phone's own list shows: pid, uid, name. */
    private var procProcesses: () -> List<Triple<Int, Int, String>> = { readProc() }

    /** Every process Android's list shows: pid, uid, name. */
    private var runningProcesses: (Context) -> List<Triple<Int, Int, String>> = { context ->
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses
            ?.map { Triple(it.pid, it.uid, it.processName.orEmpty()) }
            .orEmpty()
    }
    private var killProcess: (Int) -> Unit = { pid -> android.os.Process.killProcess(pid) }
    private var ownPid: () -> Int = { android.os.Process.myPid() }
    private var ownUid: () -> Int = { android.os.Process.myUid() }

    /** The name of this process, as the app's own provider stored it before the app's start; it can throw. */
    private var currentProcessName: (Context) -> String = { context -> ProcessNameCompat.getProcessName(context) }

    private var cancelNotifications: (Context) -> Unit = { context -> notificationManager(context)?.cancelAll() }

    /** How many notifications of the app are on screen; throws when the phone cannot be asked. */
    private var countActiveNotifications: (Context) -> Int = { context ->
        checkNotNull(notificationManager(context)) { "no notification manager" }.activeNotifications.size
    }
    private var listChannels: (Context) -> List<String> = { context ->
        notificationManager(context)?.notificationChannels?.mapNotNull { it?.id }.orEmpty()
    }
    private var deleteChannel: (Context, String) -> Unit = { context, id ->
        notificationManager(context)?.deleteNotificationChannel(id)
    }
    private var listChannelGroups: (Context) -> List<String> = { context ->
        notificationManager(context)?.notificationChannelGroups?.mapNotNull { it?.id }.orEmpty()
    }
    private var deleteChannelGroup: (Context, String) -> Unit = { context, id ->
        notificationManager(context)?.deleteNotificationChannelGroup(id)
    }
    private var clearClipboard: (Context) -> Unit = { context ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard?.clearPrimaryClip()
        } else {
            clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /** The first-install time of this install in ms; 0 when the phone does not say. */
    private var firstInstallTime: (Context) -> Long = { context ->
        val name = context.packageName
        if (name == null) 0L else context.packageManager?.getPackageInfoCompat(name, 0L)?.firstInstallTime ?: 0L
    }
    private var cancelSyncJob: (Context) -> Unit = { context -> WorkIds.disableBackgroundSynchronization(context) }
    private var cancelSyncAlarm: (Context) -> Unit = { context -> SyncAlarmScheduler.cancel(context) }
    private var cancelAllWork: (Context) -> Unit = { context -> WorkManager.getInstance(context).cancelAllWork() }
    private var pruneWork: (Context) -> Unit = { context -> WorkManager.getInstance(context).pruneWork() }

    /**
     * ZC-1066: the start pass's copy of the kept names, made from the contact book and the nicknames
     * ([KeptNamesStartCopy]); true when the list's file exists afterwards.
     */
    private var copyKeptNamesAtStart: (Context) -> Boolean = { context -> KeptNamesStartCopy.copy(context) }

    /** ZC-888: how many background jobs are pending (enqueued, running or blocked), B10's finishing job left out. */
    private var countPendingWork: (Context) -> Int = { context ->
        WorkManager
            .getInstance(context)
            .getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
            .get()
            .count { info -> DestroyFinishWorker::class.java.name !in info.tags }
    }

    // DESTROY-ADV-W (B10): after a Destroy that kept the list (and asked for no uninstall), one sync alarm and one
    // persisted one-time job start the process once more; its start pass finishes the wipe and cancels both.
    private var armStartAlarm: (Context) -> Unit = { context -> SyncAlarmScheduler.schedule(context) }
    private var enqueueStartJob: (Context) -> Unit = { context ->
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(START_JOB_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DestroyFinishWorker>().build())
    }

    /** Writes the whole file and waits until the phone's storage has it. */
    private var writeFile: (File, ByteArray) -> Unit = { file, bytes ->
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /** A rename inside one folder: whole or not at all. An existing target is replaced. */
    private var renameFile: (File, File) -> Boolean = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    }
    private var deleteFile: (File) -> Boolean = { file -> file.delete() }

    /** Makes a rename, a new file or a delete inside [File] durable. */
    private var syncFolder: (File) -> Unit = { folder ->
        val descriptor = Os.open(folder.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    /** Sets the default handler for uncaught errors and returns the one of before. */
    private var swapUncaughtHandler: (Thread.UncaughtExceptionHandler?) -> Thread.UncaughtExceptionHandler? = { next ->
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(next)
        before
    }

    /** Waits for [Thread] to end, for at most that many ms; returns the ms it waited. */
    private var waitFor: (Thread, Long) -> Long = { thread, limitMs ->
        val start = System.nanoTime()
        thread.join(limitMs.coerceAtLeast(1L))
        (System.nanoTime() - start) / NANOS_PER_MS
    }

    // ------------------------------------------------------------------ limits, threads, the process

    /** A daemon thread that only logs what it throws. */
    fun startThread(
        name: String,
        block: () -> Unit
    ): Thread =
        Thread(
            {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "$name: ${t.javaClass.simpleName}")
                }
            },
            name
        ).apply {
            isDaemon = true
            start()
        }

    /** Waits for [thread] for at most [limitMs]; returns the ms it waited. Never throws. */
    fun waitUpTo(
        thread: Thread,
        limitMs: Long
    ): Long =
        try {
            waitFor(thread, limitMs)
        } catch (t: Throwable) {
            Log.w(TAG, "wait for ${thread.name}: ${t.javaClass.simpleName}")
            0L
        }

    /**
     * Runs [block] on a thread of its own and waits for it for at most [limitMs]: a call that never answers cannot
     * hold the wipe. Returns the ms it waited. Never throws.
     */
    fun runWithin(
        name: String,
        limitMs: Long,
        block: () -> Unit
    ): Long {
        val thread =
            try {
                startThread(name, block)
            } catch (t: Throwable) {
                Log.w(TAG, "$name did not start: ${t.javaClass.simpleName}")
                return 0L
            }
        return waitUpTo(thread, limitMs)
    }

    /**
     * From here an uncaught error on any thread is only logged: it neither ends the process nor starts the crash
     * reporter. Returns the handler of before for [restoreUncaughtHandler].
     */
    fun quietUncaughtErrors(): Thread.UncaughtExceptionHandler? =
        swapUncaughtHandler(
            Thread.UncaughtExceptionHandler { thread, error ->
                Log.w(TAG, "uncaught during the wipe on ${thread.name}: ${error.javaClass.simpleName}")
            }
        )

    fun restoreUncaughtHandler(before: Thread.UncaughtExceptionHandler?) {
        swapUncaughtHandler(before)
    }

    /** ZC-090-P: this process's name as the phone gives it (the replaceable call Destroy's pass asks); may throw. */
    internal fun processNameOf(context: Context): String = currentProcessName(context)

    /** Asks the phone to end this process. */
    fun endThisProcess() {
        killProcess(ownPid())
    }

    // ------------------------------------------------------------------ W1: the record

    /** This install's first-install time in ms, 0 when it cannot be read. Never throws. */
    fun installTime(context: Context): Long =
        try {
            firstInstallTime(context).coerceAtLeast(0L)
        } catch (e: Throwable) {
            Log.w(TAG, "install time: ${e.javaClass.simpleName}")
            0L
        }

    fun recordFile(context: Context): File? = dataFolder(context)?.let { File(File(it, FOLDER_NO_BACKUP), RECORD_NAME) }

    /**
     * What binds a record to one phrase-store file without opening it: the SHA-256 of the two entries under which the
     * encryption library keeps that file's key material, as they sit on disk (the first, a line feed, the second).
     * Read from the `.bak` twin when that exists (Android treats it as the true file). [STORE_NONE] when the file
     * or one of the entries is missing. A plain scan of the file's text; the store is never opened.
     */
    fun storeHash(context: Context): String =
        try {
            val prefs = dataFolder(context)?.let { File(it, FOLDER_PREFS) }
            val backup = prefs?.let { File(it, PHRASE_STORE_BACKUP_FILE) }
            val source = if (backup != null && backup.isFile) backup else prefs?.let { File(it, PHRASE_STORE_FILE) }
            val text = if (source != null && source.isFile) source.readText() else null
            val keyKeyset = text?.let { entry(it, KEY_KEYSET_ENTRY) }
            val valueKeyset = text?.let { entry(it, VALUE_KEYSET_ENTRY) }
            if (keyKeyset == null || valueKeyset == null) STORE_NONE else sha256Hex("$keyKeyset\n$valueKeyset")
        } catch (e: Throwable) {
            Log.w(TAG, "store hash: ${e.javaClass.simpleName}")
            STORE_NONE
        }

    /**
     * The five lines of a record. It holds no name, no address and no phrase: an install time, a hash of wrapped key
     * material, the keep line (empty, or [KEEP_CONTACTS]) and a hash of those lines.
     */
    @JvmOverloads
    fun recordText(
        installTimeMs: Long,
        storeHash: String,
        keep: String = ""
    ): String {
        val body = "$RECORD_HEADER\ninstall=$installTimeMs\nstore=$storeHash\nkeep=$keep\n"
        return body + "end=" + sha256Hex(body) + "\n"
    }

    /**
     * W1. Writes the record whole: under the temp name, synced, renamed, and the folder synced. Only file reads and
     * writes on the phone's own storage. A record this process wrote is never written again; any other file of that
     * name is replaced by the same temp-and-rename step. Returns false when no record could be written: the wipe
     * goes on without one (an emergency wipe is never refused).
     */
    @JvmOverloads
    fun writeRecord(
        context: Context,
        installTimeMs: Long,
        keep: String = ""
    ): Boolean =
        try {
            val record = recordFile(context)
            val folder = record?.parentFile
            if (record == null || folder == null) {
                false
            } else if (recordWrittenByThisProcess == record.absolutePath && record.isFile) {
                true
            } else {
                val body = recordText(installTimeMs, storeHash(context), keep)
                val text = if (keep == KEEP_CONTACTS) body + sealLine(body) else body
                folder.mkdirs()
                val temp = File(folder, RECORD_TEMP_NAME)
                writeFile(temp, text.toByteArray(Charsets.UTF_8))
                if (renameFile(temp, record)) {
                    recordWrittenByThisProcess = record.absolutePath
                    syncFolder(folder)
                    true
                } else {
                    false
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "the record was not written: ${e.javaClass.simpleName}")
            false
        }

    /** ZC-1014: the `mac=` line of a keep record, made within [LIMIT_SEAL_MS]; empty when it could not be made. */
    private fun sealLine(body: String): String {
        val mac = AtomicReference<String?>(null)
        runWithin("zchat-destroy-W1-seal", LIMIT_SEAL_MS) { mac.set(DestroyRecordSeal.seal(body)) }
        return mac.get()?.let { "${DestroyRecordSeal.LINE_PREFIX}$it\n" }.orEmpty()
    }

    /**
     * ZC-1014: whether a keep record's seal verifies, asked within the start's limit. ZC1014-X1: no answer in time, a
     * thread that does not start or a check that ends without an answer is [DestroyRecordSeal.Check.CANNOT_TELL], never
     * "does not verify".
     */
    private fun sealCheck(
        body: String,
        mac: String?,
    ): DestroyRecordSeal.Check {
        val answer = AtomicReference<DestroyRecordSeal.Check?>(null)
        val thread =
            try {
                startThread("zchat-start-seal") { answer.set(DestroyRecordSeal.check(body, mac)) }
            } catch (t: Throwable) {
                return DestroyRecordSeal.Check.CANNOT_TELL
            }
        waitUpTo(thread, LIMIT_START_QUESTION_MS)
        if (thread.isAlive) return DestroyRecordSeal.Check.CANNOT_TELL
        return answer.get() ?: DestroyRecordSeal.Check.CANNOT_TELL
    }

    /** ZC1014-X2: how many starts could not check the record's seal; 0 when none is counted or the count cannot be read. */
    private fun sealTries(record: File): Int =
        try {
            record.parentFile
                ?.let { File(it, SEAL_TRIES_NAME) }
                ?.takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        } catch (t: Throwable) {
            Log.w(TAG, "seal tries: ${t.javaClass.simpleName}")
            0
        }

    /** ZC1014-X2: writes the count and syncs its folder before the process ends; false when it did not reach the disk. */
    private fun writeSealTries(
        record: File,
        tries: Int
    ): Boolean =
        try {
            val folder = record.parentFile ?: throw IllegalStateException("no folder")
            writeFile(File(folder, SEAL_TRIES_NAME), "$tries\n".toByteArray(Charsets.UTF_8))
            syncFolder(folder)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "seal tries not saved: ${t.javaClass.simpleName}")
            false
        }

    private fun deleteSealTries(record: File) {
        quietly("seal tries") {
            val tries = record.parentFile?.let { File(it, SEAL_TRIES_NAME) }
            if (tries != null && tries.exists()) deleteFile(tries)
        }
    }

    /**
     * ZC1014-X1: the offer to run Destroy again, in memory and as a mark beside the record, written before the record is
     * removed (the record's removal syncs the folder first), so the end of the process cannot lose it.
     */
    private fun offerRerun(record: File) {
        DestroyRecordSeal.offerRerun()
        quietly("rerun offer") {
            record.parentFile?.let { folder -> writeFile(File(folder, RERUN_OFFER_NAME), "offer\n".toByteArray(Charsets.UTF_8)) }
        }
    }

    /** ZC-090-R: the file calls of [writeRecord], for the record of a Reset (written whole the same way). */
    internal fun writeWhole(
        file: File,
        bytes: ByteArray
    ) = writeFile(file, bytes)

    internal fun renameWhole(
        from: File,
        to: File
    ): Boolean = renameFile(from, to)

    internal fun syncFolderOf(folder: File) = syncFolder(folder)

    /** The text of the record file, or null when there is none. The temp name is never read. */
    fun readRecord(context: Context): String? =
        try {
            recordFile(context)?.takeIf { it.isFile }?.readText()
        } catch (e: Throwable) {
            Log.w(TAG, "the record was not read: ${e.javaClass.simpleName}")
            null
        }

    /** The kept list's file, `no_backup/zchat_kept_names`, as the wipe and the start pass see it. */
    fun keptListFile(context: Context): File? = dataFolder(context)?.let { File(File(it, FOLDER_NO_BACKUP), KeptNames.FILE_NAME) }

    /** Whether the kept list's file exists: after the copy, the one fact everything of a kept Destroy follows. */
    fun keptListExists(context: Context): Boolean =
        try {
            keptListFile(context)?.isFile == true
        } catch (e: Throwable) {
            Log.w(TAG, "kept list lookup: ${e.javaClass.simpleName}")
            false
        }

    /**
     * B10: after a Destroy that kept the list, one sync alarm and one unique one-time job, so that ZCHAT starts once
     * more and its start pass finishes the wipe. Each call in its own catch.
     */
    fun armFinishingStart(context: Context) {
        try {
            armStartAlarm(context)
        } catch (e: Throwable) {
            Log.w(TAG, "arm the finishing alarm: ${e.javaClass.simpleName}")
        }
        try {
            enqueueStartJob(context)
        } catch (e: Throwable) {
            Log.w(TAG, "enqueue the finishing job: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ W2: the recovery phrase

    /** W2. Deletes the phrase-store file and its `.bak`. Two deletes, nothing else. True when neither is left. */
    fun deletePhraseStore(context: Context): Boolean {
        val prefs = dataFolder(context)?.let { File(it, FOLDER_PREFS) } ?: return false
        var gone = true
        for (name in listOf(PHRASE_STORE_FILE, PHRASE_STORE_BACKUP_FILE)) {
            val file = File(prefs, name)
            try {
                deleteFile(file)
            } catch (e: Throwable) {
                Log.w(TAG, "phrase store delete: ${e.javaClass.simpleName}")
            }
            if (file.exists()) gone = false
        }
        return gone
    }

    // ------------------------------------------------------------------ W3: the phone-security keys

    /**
     * W3. Deletes the two known keys by name, then every name the listing gives. It can wait on the phone-security
     * service, so [DestroyManager] runs it on a thread of its own. Returns the names still listed afterwards, or
     * null when the listing did not answer (an error is not "none").
     */
    @JvmOverloads
    fun eraseKeys(spareKeptListKey: Boolean = false): List<String>? {
        for (name in KNOWN_KEYS) deleteKey(name)
        try {
            keystoreAliases().filterNot { spareKeptListKey && it == KeptNames.KEY_ALIAS }.forEach { deleteKey(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "key listing: ${e.javaClass.simpleName}")
        }
        return try {
            keystoreAliases()
        } catch (e: Throwable) {
            Log.w(TAG, "key listing: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun deleteKey(name: String) {
        try {
            keystoreDelete(name)
        } catch (e: Throwable) {
            Log.w(TAG, "key delete: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ W4: the app's other processes

    /**
     * W4. Ends every other process of this app: the phone's own list first (file reads), then Android's list (waited
     * for at most [LIMIT_PROCESS_LIST_MS]); then up to [LIMIT_PROCESS_END_MS] until neither lists one. A process
     * counts as ours when it has this app's uid and its name is the package name or starts with it and a colon.
     * With no package name no process is ended. Returns how many are still listed.
     */
    fun endOtherProcesses(context: Context): Int {
        val packageName =
            try {
                context.packageName
            } catch (e: Throwable) {
                null
            }
        if (packageName.isNullOrEmpty()) return 0
        val listed = othersInProc(packageName) + othersInAndroidList(context, packageName, LIMIT_PROCESS_LIST_MS)
        val targets = listed.distinct()
        if (targets.isEmpty()) return 0
        for (pid in targets) {
            try {
                killProcess(pid)
            } catch (e: Throwable) {
                Log.w(TAG, "process end: ${e.javaClass.simpleName}")
            }
        }
        val deadline = System.nanoTime() + LIMIT_PROCESS_END_MS * NANOS_PER_MS
        while (true) {
            var left = othersInProc(packageName)
            val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MS
            if (left.isEmpty() && remainingMs > 0) left = othersInAndroidList(context, packageName, remainingMs)
            if (left.isEmpty() || System.nanoTime() >= deadline) return left.size
            try {
                Thread.sleep(PROCESS_POLL_MS)
            } catch (e: InterruptedException) {
                return left.size
            }
        }
    }

    private fun others(
        list: List<Triple<Int, Int, String>>,
        packageName: String
    ): List<Int> {
        val me = ownPid()
        val uid = ownUid()
        return list
            .filter { (pid, itsUid, name) ->
                pid != me && itsUid == uid && (name == packageName || name.startsWith("$packageName:"))
            }.map { it.first }
    }

    private fun othersInProc(packageName: String): List<Int> =
        try {
            others(procProcesses(), packageName)
        } catch (e: Throwable) {
            Log.w(TAG, "process list: ${e.javaClass.simpleName}")
            emptyList()
        }

    private fun othersInAndroidList(
        context: Context,
        packageName: String,
        limitMs: Long
    ): List<Int> {
        var found: List<Int> = emptyList()
        val thread =
            try {
                startThread("zchat-destroy-W4-list") { found = others(runningProcesses(context), packageName) }
            } catch (e: Throwable) {
                return emptyList()
            }
        waitUpTo(thread, limitMs)
        return if (thread.isAlive) emptyList() else found
    }

    private fun readProc(): List<Triple<Int, Int, String>> =
        File("/proc")
            .listFiles()
            .orEmpty()
            .mapNotNull { entry ->
                val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
                try {
                    val uid = Os.stat(entry.path).st_uid
                    val name =
                        File(entry, "cmdline")
                            .readBytes()
                            .takeWhile { it != 0.toByte() }
                            .toByteArray()
                            .toString(Charsets.UTF_8)
                    Triple(pid, uid, name)
                } catch (e: Throwable) {
                    null
                }
            }

    // ------------------------------------------------------------------ W5: every file, by listing

    /**
     * W5. Deletes every file of the app's own storage by LISTING it: the data folder (reached through
     * `applicationInfo.dataDir`; `shared_prefs` first, then `no_backup`, then every other entry), the
     * device-protected folder, and the app's folders on every external volume. It leaves what [MAY_BE_LEFT] names.
     * Returns the FILES that are still there afterwards (none of [MAY_BE_LEFT]); empty folders do not count.
     *
     * ZC-090-P: with [keep] (a Reset's kept set: true for a file, or a folder, that stays whole) only the data folder
     * is swept, and what [keep] names is left; the device-protected folder and the external folders are not touched.
     * Destroy calls it without [keep], as before. [spareKeptList] (DESTROY-ADV-W) spares the kept list's file in the
     * data folder's `no_backup` with or without [keep].
     */
    @JvmOverloads
    fun sweepFiles(
        context: Context,
        spareKeptList: Boolean = false,
        keep: ((File) -> Boolean)? = null
    ): List<String> {
        val left = ArrayList<String>()
        if (keep != null) {
            dataFolder(context)?.let { sweepDataFolder(it, left, spareKeptList, keep) }
            return left
        }
        dataFolder(context)?.let { sweepDataFolder(it, left, spareKeptList) }
        deviceProtectedFolder(context)?.let { sweepDataFolder(it, left, spareKeptList = false) }
        externalFolders(context).forEach { folder -> children(folder).forEach { deleteTree(it, left) } }
        return left
    }

    private fun sweepDataFolder(
        data: File,
        left: MutableList<String>,
        spareKeptList: Boolean = false,
        keep: ((File) -> Boolean)? = null
    ) {
        val first = listOf(FOLDER_PREFS, FOLDER_NO_BACKUP)
        val entries = children(data).sortedBy { entry -> first.indexOf(entry.name).takeIf { it >= 0 } ?: first.size }
        for (entry in entries) {
            when (entry.name) {
                FOLDER_LIB, FOLDER_CODE_CACHE -> Unit
                FOLDER_NO_BACKUP ->
                    children(entry)
                        .filterNot { it.name == RECORD_NAME || it.name.startsWith(WORK_TABLE_PREFIX) }
                        .filterNot { spareKeptList && it.name == KeptNames.FILE_NAME }
                        .forEach { deleteTree(it, left, keep) }
                else -> deleteTree(entry, left, keep)
            }
        }
    }

    private fun deleteTree(
        file: File,
        left: MutableList<String>,
        keep: ((File) -> Boolean)? = null
    ) {
        try {
            if (keep?.invoke(file) == true) return
            val path = file.toPath()
            if (!Files.isSymbolicLink(path) && file.isDirectory) children(file).forEach { deleteTree(it, left, keep) }
            deleteFile(file)
            val stillThere = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            if (stillThere && (Files.isSymbolicLink(path) || !file.isDirectory)) left += file.path
        } catch (e: Throwable) {
            Log.w(TAG, "delete: ${e.javaClass.simpleName}")
            if (!file.isDirectory) left += file.path
        }
    }

    private fun children(folder: File): List<File> =
        try {
            folder.listFiles()?.toList().orEmpty()
        } catch (e: Throwable) {
            emptyList()
        }

    private fun dataFolder(context: Context): File? =
        try {
            context.applicationInfo?.dataDir?.let { File(it) }
        } catch (e: Throwable) {
            null
        }

    private fun deviceProtectedFolder(context: Context): File? =
        try {
            context.createDeviceProtectedStorageContext()?.dataDir
        } catch (e: Throwable) {
            null
        }

    /** The app's folder under `Android/data` on every volume Android lists. */
    private fun externalFolders(context: Context): List<File> {
        val packageName =
            try {
                context.packageName
            } catch (e: Throwable) {
                null
            }
        val listed = ArrayList<File?>()
        try {
            val files: Array<File?>? = context.getExternalFilesDirs(null)
            files?.let { listed += it }
        } catch (e: Throwable) {
            Log.w(TAG, "external files: ${e.javaClass.simpleName}")
        }
        try {
            val caches: Array<File?>? = context.externalCacheDirs
            caches?.let { listed += it }
        } catch (e: Throwable) {
            Log.w(TAG, "external caches: ${e.javaClass.simpleName}")
        }
        return listed
            .filterNotNull()
            .map { folder -> folder.parentFile?.takeIf { it.name == packageName } ?: folder }
            .distinct()
    }

    // ------------------------------------------------------------------ W7: traces and background jobs

    /**
     * W7, the app's own part: the sync service, the periodic sync and its alarm, then all work and its rows.
     * The service is stopped with stopService (a start could be refused from the background), and first: Android
     * keeps the channel of a running foreground service. Every call has its own catch.
     */
    fun stopBackgroundWork(context: Context) {
        try {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        } catch (e: Throwable) {
            Log.w(TAG, "stop the sync service: ${e.javaClass.simpleName}")
        }
        try {
            cancelSyncJob(context)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel the periodic sync: ${e.javaClass.simpleName}")
        }
        // Its own catch: the call above asks for the job library first, and when that throws its alarm cancel is
        // never reached.
        try {
            cancelSyncAlarm(context)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel the sync alarm: ${e.javaClass.simpleName}")
        }
        clearBackgroundJobs(context)
    }

    /**
     * W7, the job library's part: cancels all work, then clears the cancelled and finished rows out of its table
     * (the table file itself stays, the library holds it open). Each call in its own catch.
     */
    fun clearBackgroundJobs(context: Context) {
        try {
            cancelAllWork(context)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel all work: ${e.javaClass.simpleName}")
        }
        try {
            pruneWork(context)
        } catch (e: Throwable) {
            Log.w(TAG, "prune work: ${e.javaClass.simpleName}")
        }
    }

    /**
     * W7, the traces: cancels every notification, deletes every notification channel and channel group the app has
     * (by listing, so one a later version adds goes too; the sync service is stopped before, Android keeps the
     * channel of a running foreground service) and clears the phone's current clipboard entry. Each call in its own
     * catch.
     *
     * ZC-090-P: [onlyNotifications] (a Reset's pass) cancels the notifications and stops there: no channel is deleted and
     * the clipboard is not cleared.
     */
    @JvmOverloads
    fun stopTraces(
        context: Context,
        onlyNotifications: Boolean = false
    ) {
        try {
            cancelNotifications(context)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel notifications: ${e.javaClass.simpleName}")
        }
        if (onlyNotifications) return
        // ZC-888: a channel Android refuses to delete (the channel of a foreground service that is still stopping) is
        // tried again for up to LIMIT_CHANNEL_RETRY_MS.
        deleteListed("channel", { listChannels(context) }, retryRefused = true) { deleteChannel(context, it) }
        deleteListed("channel group", { listChannelGroups(context) }) { deleteChannelGroup(context, it) }
        try {
            clearClipboard(context)
        } catch (e: Throwable) {
            Log.w(TAG, "clipboard: ${e.javaClass.simpleName}")
        }
    }

    private fun deleteListed(
        what: String,
        list: () -> List<String>,
        retryRefused: Boolean = false,
        delete: (String) -> Unit
    ) {
        val listed =
            try {
                list()
            } catch (e: Throwable) {
                Log.w(TAG, "$what listing: ${e.javaClass.simpleName}")
                emptyList()
            }
        var refused = deleteEach(what, listed, delete)
        if (!retryRefused || refused.isEmpty()) return
        val deadline = System.nanoTime() + LIMIT_CHANNEL_RETRY_MS * NANOS_PER_MS
        while (refused.isNotEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(PROCESS_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            refused = deleteEach(what, refused, delete)
        }
        if (refused.isNotEmpty()) Log.w(TAG, "$what delete: ${refused.size} still refused")
    }

    /** Deletes each of [ids], each in its own catch. @return the ids whose delete Android refused (SecurityException). */
    private fun deleteEach(
        what: String,
        ids: List<String>,
        delete: (String) -> Unit
    ): List<String> {
        val refused = ArrayList<String>()
        for (id in ids) {
            try {
                delete(id)
            } catch (e: SecurityException) {
                refused += id
            } catch (e: Throwable) {
                Log.w(TAG, "$what delete: ${e.javaClass.simpleName}")
            }
        }
        return refused
    }

    /**
     * ZC-888: whether a start of the sync service must be refused: while a Destroy record is on the phone (the pass has
     * not finished it, or a Destroy is running), nothing of the sync starts, and the service makes no channel either.
     * A file check; a phone that cannot answer it does not hold the sync.
     */
    fun syncStartRefused(context: Context): Boolean =
        try {
            // ZC-888-X1: not for a record this process's pass set aside beside a live wallet (Ignored: it could not be
            // removed); that wallet's sync runs. Any other record (not judged yet, judged again at the next start, or
            // this process's own Destroy) still refuses.
            // ZC-888-X2: a Destroy this process runs (it wrote its own record) refuses them in any case.
            recordFile(context)?.isFile == true &&
                (startOutcome !is StartOutcome.Ignored || recordWrittenByThisProcess != null)
        } catch (t: Throwable) {
            Log.w(TAG, "the record check for a sync start: ${t.javaClass.simpleName}")
            false
        }

    // ------------------------------------------------------------------ the start pass (ZC-692-S)

    /**
     * The first statement of the app's start (`ZcashApplication.onCreate`, right after `super.onCreate()`): finishes a
     * Destroy that was cut off, and only its own. It reads and deletes RAW FILES only: no settings object, no store
     * and nothing of the dependency graph is touched, because a settings object that was read before its file is
     * deleted is what the app would open afterwards. It never throws. The result is [startOutcome].
     *
     *  - No record: nothing happens (a leftover temp file of a record is deleted). A wipe is never inferred.
     *  - A record, in any process but the main one: nothing is deleted, the process ends.
     *  - The start cannot judge (no process name, the phone does not answer for the install time, or, ZC692S-X1, it
     *    cannot say whether the phrase store is there): nothing is deleted, the record stays, the process ends; the
     *    next start judges again.
     *  - A record that is not whole, is of another install (or says install=0), or names another phrase store than
     *    the one on the phone: nothing is deleted, the record is removed. One that cannot be removed blocks the
     *    making of a wallet (RecordStuck) only while no phrase store is on the phone (ZC692S-X1).
     *  - A valid record: the pass deletes the phrase store, the keys, other processes, every file, background work,
     *    notifications, channels and the clipboard once more, in a process that has opened nothing; then [check]
     *    asks what is left; then the record is removed WHATEVER the check says, and background sync is held.
     */
    fun atProcessStart(context: Context) {
        try {
            startOutcome = startPass(context)
        } catch (t: Throwable) {
            Log.w(TAG, "start pass: ${t.javaClass.simpleName}")
        }
    }

    private fun startPass(context: Context): StartOutcome {
        val record = recordFile(context) ?: return StartOutcome.None
        // ZC1014-X1: the rerun offer's mark of an earlier process is found beside the record's place.
        DestroyRecordSeal.rerunMarkAt(record.parentFile?.let { File(it, RERUN_OFFER_NAME) })
        if (!record.isFile) {
            deleteLeftoverTemp(record)
            return StartOutcome.None
        }
        val packageName = packageNameOf(context)
        val processName =
            try {
                currentProcessName(context)
            } catch (t: Throwable) {
                null
            }
        if (packageName.isNullOrEmpty() || processName.isNullOrEmpty()) return cannotJudge("the process name")
        if (!MainProcess.isMain(processName, packageName)) {
            // Only the main process ever deletes. Any other one that finds a record ends before its own start-up.
            Log.w(TAG, "a record of a Destroy in a process that is not the main one: ending it")
            endThisProcessQuietly()
            return StartOutcome.None
        }
        val parsed = parseRecord(readRecord(context)) ?: return ignore(context, record, "it is not whole")
        val keepContacts = parsed.keepContacts
        if (parsed.install == 0L) return ignore(context, record, "it does not name an install")
        val installTime = askInstallTime(context) ?: return cannotJudge("the install time")
        if (installTime != parsed.install) return ignore(context, record, "it is of another install")
        // ZC692S-X1: a phone that cannot say whether the phrase store is there gives no answer: never "none is left".
        val storeThere = phraseStoreState(context) ?: return cannotJudge("whether the phrase store is there")
        if (storeThere && (parsed.store == STORE_NONE || storeHash(context) != parsed.store)) {
            return ignore(context, record, "the phrase store on the phone is not the one it names")
        }
        // ZC-1014: a record that keeps names, beside a live phrase store (cut before its W2), counts only when its seal
        // verifies under this wallet's Keystore key. Otherwise nothing is deleted and the user is asked once whether to
        // run Destroy again (the first spec pins that such a keep line without a seal deletes nothing). ZC1014-X1: a
        // Keystore that throws or is slow cannot tell: nothing is deleted, the record stays, the next start judges again.
        if (keepContacts && storeThere) {
            when (sealCheck(parsed.sealed, parsed.mac)) {
                DestroyRecordSeal.Check.VERIFIES -> Unit
                DestroyRecordSeal.Check.DOES_NOT_VERIFY -> {
                    offerRerun(record)
                    return ignore(context, record, "it keeps names and its seal does not verify")
                }
                DestroyRecordSeal.Check.CANNOT_TELL -> {
                    // ZC1014-X2: a Keystore that never answers must not end every start for ever. The tries are
                    // counted on disk; at MAX_SEAL_TRIES nothing is deleted, the record goes and Destroy is offered
                    // again (as for a seal that does not verify): the wallet stays whole and the user can run it again.
                    val tries = sealTries(record) + 1
                    if (tries < MAX_SEAL_TRIES && writeSealTries(record, tries)) {
                        return cannotJudge("whether the record's seal verifies ($tries)")
                    }
                    deleteSealTries(record)
                    offerRerun(record)
                    return ignore(context, record, "its seal could not be checked at $tries starts")
                }
            }
        }
        // ZC-1066: a keep Destroy cut after its record and before its copy (the phrase store is still there, no list):
        // the copy is made first, within LIMIT_START_NAMES_MS, then the Destroy is finished. The copy read the contact
        // book's and the nicknames' stores, which the pass then deletes, so this process ends after the pass: nothing it
        // read is what the app would open afterwards (ZC-692-S).
        val copied = keepContacts && storeThere && !keptListExists(context)
        if (copied) {
            Log.w(TAG, "a keep record beside the phrase store and no list: copying the names first")
            runWithin("zchat-start-names", LIMIT_START_NAMES_MS) { quietly("names") { copyKeptNamesAtStart(context) } }
        }
        val outcome = finish(context, record, keepContacts)
        if (copied) endThisProcessQuietly()
        return outcome
    }

    /** A valid record: the pass, the check, the record's removal whatever the check says, the hold on background sync. */
    private fun finish(
        context: Context,
        record: File,
        keepContacts: Boolean
    ): StartOutcome {
        Log.w(TAG, "a valid record of a Destroy: finishing it")
        quietly("phrase store") { deletePhraseStore(context) }
        // DESTROY-ADV-W: with keep=contacts, the kept list's file and its key are spared while the file exists.
        val spare = keepContacts && keptListExists(context)
        var keys: Thread? = null
        quietly("keys") { keys = startThread("zchat-start-keys") { eraseKeys(spare) } }
        quietly("processes") { endOtherProcesses(context) }
        quietly("files") { sweepFiles(context, spare) }
        keys?.let { waitUpTo(it, LIMIT_START_KEYS_MS) }
        runWithin("zchat-start-traces", LIMIT_START_TRACES_MS) {
            stopBackgroundWork(context)
            stopTraces(context)
        }
        val kinds =
            try {
                check(context, spare)
            } catch (t: Throwable) {
                setOf(KIND_FILES, KIND_KEYS, KIND_NOTIFICATIONS, KIND_PROCESS)
            }
        // Every delete of the pass is made durable before the record goes: a power cut can bring the record back,
        // never a deleted file without it.
        quietly("folder sync") { syncSweptFolders(context) }
        val removed = removeRecord(record)
        backgroundSyncHeld = true
        return when {
            !removed -> StartOutcome.RecordStuck
            kinds.isEmpty() -> StartOutcome.Finished
            else -> StartOutcome.Unfinished(kinds)
        }
    }

    /**
     * Nothing is deleted; the record is removed. If it cannot be removed either, the next start judges it again.
     * ZC692S-X1: a record that stays blocks the making of a wallet beside it (RecordStuck) when no phrase store is on
     * the phone; a wallet that is there, or one the phone cannot rule out, is never blocked by it (Ignored).
     */
    private fun ignore(
        context: Context,
        record: File,
        why: String
    ): StartOutcome {
        if (isDebuggable(context)) Log.d(TAG, "the record of a Destroy is not valid ($why): nothing is deleted")
        // ZC1014-X2: a count of seal tries goes with its record.
        deleteSealTries(record)
        if (removeRecord(record)) return StartOutcome.Ignored
        Log.w(TAG, "a record that is not valid could not be removed")
        return if (phraseStoreState(context) == false) StartOutcome.RecordStuck else StartOutcome.Ignored
    }

    /** Nothing is deleted, the record stays, the process ends: the next start judges again. */
    private fun cannotJudge(what: String): StartOutcome {
        Log.w(TAG, "a record of a Destroy, and this start cannot tell $what: ending the process")
        endThisProcessQuietly()
        return StartOutcome.None
    }

    private fun endThisProcessQuietly() {
        try {
            endThisProcess()
        } catch (t: Throwable) {
            Log.w(TAG, "process end: ${t.javaClass.simpleName}")
        }
    }

    private inline fun quietly(
        what: String,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "start pass, $what: ${t.javaClass.simpleName}")
        }
    }

    private fun deleteLeftoverTemp(record: File) {
        try {
            val temp = record.parentFile?.let { File(it, RECORD_TEMP_NAME) }
            if (temp != null && temp.isFile) deleteFile(temp)
        } catch (t: Throwable) {
            Log.w(TAG, "leftover temp: ${t.javaClass.simpleName}")
        }
    }

    /** A record that is whole: its install time, store line, keep line, its five lines, and a keep record's seal. */
    private class ParsedRecord(
        val install: Long,
        val store: String,
        val keepContacts: Boolean,
        val sealed: String,
        val mac: String?
    )

    /**
     * A record that is whole: exactly the five lines [recordText] writes (version 1, a keep line that is empty or
     * [KEEP_CONTACTS], an end line that matches), and for [KEEP_CONTACTS] optionally one more line, its seal (ZC-1014).
     * Null for anything else: any other keep value, or any other sixth line, makes the record not valid.
     */
    private fun parseRecord(text: String?): ParsedRecord? {
        val lines = text?.split("\n") ?: return null
        val sealLine = lines.size == RECORD_LINES + 2 && lines[RECORD_LINES].startsWith(DestroyRecordSeal.LINE_PREFIX)
        if ((lines.size != RECORD_LINES + 1 && !sealLine) || lines.last() != "" || lines[0] != RECORD_HEADER) return null
        val install = lines[1].removePrefix("install=").takeIf { it != lines[1] }?.toLongOrNull() ?: return null
        val store = lines[2].removePrefix("store=").takeIf { it != lines[2] } ?: return null
        if (store != STORE_NONE && !Regex("[0-9a-f]{64}").matches(store)) return null
        val keep = lines[3].removePrefix("keep=").takeIf { it != lines[3] } ?: return null
        if (keep != "" && keep != KEEP_CONTACTS) return null
        if (sealLine && keep != KEEP_CONTACTS) return null
        val sealed = lines.take(RECORD_LINES).joinToString("") { "$it\n" }
        if (install < 0L || recordText(install, store, keep) != sealed) return null
        val mac = if (sealLine) lines[RECORD_LINES].removePrefix(DestroyRecordSeal.LINE_PREFIX) else null
        return ParsedRecord(install, store, keep == KEEP_CONTACTS, sealed, mac)
    }

    /** This install's first-install time as the phone says it now, or null when it gives no answer in time. */
    internal fun askInstallTime(context: Context): Long? {
        var answer = 0L
        val thread =
            try {
                startThread("zchat-start-install-time") { answer = firstInstallTime(context) }
            } catch (t: Throwable) {
                return null
            }
        waitUpTo(thread, LIMIT_START_QUESTION_MS)
        return if (thread.isAlive) null else answer.takeIf { it > 0L }
    }

    /**
     * ZC692S-X1: whether the phrase store or its `.bak` twin is on the phone. False only when the phone says both are
     * absent; null when it cannot say (`File.exists()` answers false on an I/O error or a refused lookup, so it is not
     * used here: `Files.notExists` is true only for a file known to be absent).
     */
    internal fun phraseStoreState(context: Context): Boolean? {
        val prefs = dataFolder(context)?.let { File(it, FOLDER_PREFS) } ?: return null
        var unknown = false
        for (name in listOf(PHRASE_STORE_FILE, PHRASE_STORE_BACKUP_FILE)) {
            try {
                val path = File(prefs, name).toPath()
                if (Files.exists(path)) return true
                if (!Files.notExists(path)) unknown = true
            } catch (t: Throwable) {
                Log.w(TAG, "phrase store lookup: ${t.javaClass.simpleName}")
                unknown = true
            }
        }
        return if (unknown) null else false
    }

    /** Syncs the record's folder, deletes the record, syncs the folder again. True when the record is gone. */
    internal fun removeRecord(record: File): Boolean {
        val folder = record.parentFile
        quietly("record folder sync") { folder?.let { syncFolder(it) } }
        quietly("record delete") { deleteFile(record) }
        quietly("record folder sync") { folder?.let { syncFolder(it) } }
        return try {
            !record.exists()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Makes the deletes of a sweep durable: syncs every folder of the app's own storage that is still there (a
     * folder that was deleted is gone for good once the folder above it is synced).
     */
    internal fun syncSweptFolders(context: Context) {
        val roots = listOfNotNull(dataFolder(context), deviceProtectedFolder(context)) + externalFolders(context)
        for (root in roots) {
            val folders = ArrayList<File>()
            collectFolders(root, folders, isDataRoot = true)
            for (folder in folders) {
                try {
                    syncFolder(folder)
                } catch (t: Throwable) {
                    Log.w(TAG, "folder sync: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun collectFolders(
        folder: File,
        into: MutableList<File>,
        isDataRoot: Boolean
    ) {
        if (Files.isSymbolicLink(folder.toPath()) || !folder.isDirectory) return
        into += folder
        children(folder)
            .filterNot { isDataRoot && (it.name == FOLDER_LIB || it.name == FOLDER_CODE_CACHE) }
            .forEach { collectFolders(it, into, isDataRoot = false) }
    }

    // ------------------------------------------------------------------ what "finished" means (ZC-692-S)

    /**
     * The check after a pass: which kinds of things are still there. FILES by listing (a file besides those of
     * [MAY_BE_LEFT]; empty folders are not leftovers, Android keeps and re-creates some); KEYS by the two known
     * names and by listing; NOTIFICATIONS by asking for the active ones; other PROCESSES by the phone's own list and
     * by Android's. An error or no answer is never read as "nothing left": a listing that fails, a phone-security
     * service or a notification question that does not answer, and two process lists that both fail each name their
     * kind. Empty when nothing is left.
     */
    @JvmOverloads
    fun check(
        context: Context,
        spareKeptList: Boolean = false
    ): Set<String> {
        val kinds = LinkedHashSet<String>()
        if (!noFileIsLeft(context, spareKeptList)) kinds += KIND_FILES
        if (!noKeyIsLeft(spareKeptList)) kinds += KIND_KEYS
        if (!noNotificationIsLeft(context)) kinds += KIND_NOTIFICATIONS
        // ZC-888: a channel or a channel group of the app is a notification trace too; a pending job is counted apart.
        if (!noChannelIsLeft(context)) kinds += KIND_NOTIFICATIONS
        if (!noJobIsLeft(context)) kinds += KIND_JOBS
        if (!noOtherProcessIsLeft(context)) kinds += KIND_PROCESS
        return kinds
    }

    /** ZC-888: no channel and no channel group of the app is left. A listing that throws is never "none". */
    private fun noChannelIsLeft(context: Context): Boolean =
        try {
            listChannels(context).isEmpty() && listChannelGroups(context).isEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "channel listing: ${t.javaClass.simpleName}")
            false
        }

    /** ZC-888: no background job is pending, asked within the start's limit. No answer is never "none". */
    private fun noJobIsLeft(context: Context): Boolean {
        val pending = AtomicReference<Int?>(null)
        val thread =
            try {
                startThread("zchat-start-check-jobs") { pending.set(countPendingWork(context)) }
            } catch (t: Throwable) {
                return false
            }
        waitUpTo(thread, LIMIT_START_QUESTION_MS)
        return !thread.isAlive && pending.get() == 0
    }

    private class Listing {
        var files = 0
        var failed = false
    }

    private fun noFileIsLeft(
        context: Context,
        spareKeptList: Boolean
    ): Boolean =
        try {
            val listing = Listing()
            val data = dataFolder(context)
            if (data == null) listing.failed = true else countDataFolder(data, listing, spareKeptList)
            deviceProtectedFolder(context)?.let { countDataFolder(it, listing, spareKeptList = false) }
            externalFolders(context).forEach { countTree(it, listing, isRoot = true) }
            !listing.failed && listing.files == 0
        } catch (t: Throwable) {
            Log.w(TAG, "file listing: ${t.javaClass.simpleName}")
            false
        }

    private fun countDataFolder(
        data: File,
        listing: Listing,
        spareKeptList: Boolean
    ) {
        for (entry in strictChildren(data, listing, isRoot = true)) {
            when (entry.name) {
                FOLDER_LIB, FOLDER_CODE_CACHE -> Unit
                FOLDER_NO_BACKUP ->
                    strictChildren(entry, listing, isRoot = false)
                        .filterNot { it.name == RECORD_NAME || it.name.startsWith(WORK_TABLE_PREFIX) }
                        .filterNot { spareKeptList && it.name == KeptNames.FILE_NAME }
                        .forEach { countTree(it, listing, isRoot = false) }
                else -> countTree(entry, listing, isRoot = false)
            }
        }
    }

    private fun countTree(
        file: File,
        listing: Listing,
        isRoot: Boolean
    ) {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path) || !file.isDirectory) {
            listing.files++
        } else {
            strictChildren(file, listing, isRoot).forEach { countTree(it, listing, isRoot = false) }
        }
    }

    /** The entries of a folder; a folder that is there and cannot be listed marks the listing as failed. */
    private fun strictChildren(
        folder: File,
        listing: Listing,
        isRoot: Boolean
    ): List<File> {
        val entries = folder.listFiles()
        if (entries == null && (!isRoot || folder.exists())) listing.failed = true
        return entries?.toList().orEmpty()
    }

    private fun noKeyIsLeft(spareKeptListKey: Boolean): Boolean {
        var none = false
        val thread =
            try {
                startThread("zchat-start-check-keys") {
                    none =
                        KNOWN_KEYS.none { keystoreContains(it) } &&
                        keystoreAliases().none { !(spareKeptListKey && it == KeptNames.KEY_ALIAS) }
                }
            } catch (t: Throwable) {
                return false
            }
        waitUpTo(thread, LIMIT_START_KEYS_MS)
        return !thread.isAlive && none
    }

    /** A cancel takes the phone a moment, so the question is asked again for at most a second. */
    private fun noNotificationIsLeft(context: Context): Boolean {
        var none = false
        val thread =
            try {
                startThread("zchat-start-check-notifications") {
                    val deadline = System.nanoTime() + LIMIT_START_NOTIFICATIONS_MS * NANOS_PER_MS
                    while (true) {
                        if (countActiveNotifications(context) == 0) {
                            none = true
                            break
                        }
                        if (System.nanoTime() >= deadline) break
                        Thread.sleep(PROCESS_POLL_MS)
                    }
                }
            } catch (t: Throwable) {
                return false
            }
        waitUpTo(thread, LIMIT_START_QUESTION_MS)
        return !thread.isAlive && none
    }

    private fun noOtherProcessIsLeft(context: Context): Boolean {
        val packageName = packageNameOf(context)
        if (packageName.isNullOrEmpty()) return false
        val inProc: List<Int>? =
            try {
                others(procProcesses(), packageName)
            } catch (t: Throwable) {
                null
            }
        var inAndroidList: List<Int>? = null
        try {
            val thread = startThread("zchat-start-check-list") { inAndroidList = others(runningProcesses(context), packageName) }
            waitUpTo(thread, LIMIT_PROCESS_LIST_MS)
            if (thread.isAlive) inAndroidList = null
        } catch (t: Throwable) {
            inAndroidList = null
        }
        val android = inAndroidList
        if (inProc == null && android == null) return false
        return inProc.orEmpty().isEmpty() && android.orEmpty().isEmpty()
    }

    // ------------------------------------------------------------------ helpers

    internal fun packageNameOf(context: Context): String? =
        try {
            context.packageName
        } catch (t: Throwable) {
            null
        }

    private fun isDebuggable(context: Context): Boolean =
        try {
            ((context.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (t: Throwable) {
            false
        }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun entry(
        text: String,
        name: String
    ): String? {
        val open = "<string name=\"$name\">"
        val start = text.indexOf(open)
        if (start < 0) return null
        val from = start + open.length
        val end = text.indexOf("</string>", from)
        return if (end < 0) null else text.substring(from, end)
    }

    private fun sha256Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
