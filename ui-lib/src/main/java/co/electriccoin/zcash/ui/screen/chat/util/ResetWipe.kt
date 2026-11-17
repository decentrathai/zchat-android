package co.electriccoin.zcash.ui.screen.chat.util

import android.content.Context
import android.util.Log
import co.electriccoin.zcash.global.MainProcess
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import co.electriccoin.zcash.ui.screen.walletstartup.WalletDataDatabaseFiles
import co.electriccoin.zcash.work.ResetWakeWorker
import co.electriccoin.zcash.work.SyncAlarmScheduler
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-090-P (register ZC-090, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.2 RS-6 to RS-8, 4.3, 4.5, 4.7): the
 * start pass for a Reset ZCHAT that was cut off. It is the statement right after `DestroyWipe.atProcessStart(this)`
 * in `ZcashApplication.onCreate`, and like Destroy's pass it reads and deletes RAW FILES only, never throws, and acts
 * in the main process only ([MainProcess.isMain]). Nothing writes its record until batch ZC-090-R: alone, a start
 * with no record does exactly what it did before.
 *
 * The record `no_backup/zchat_reset_pending` ([recordText]) is acted on only when it is whole, of this install, and
 * meets a phrase store that is its own (the hash of its two key entries equals the store line), gone, or empty of
 * everything but key entries (a raw scan; a file the scan cannot parse is not this state): that third state is what
 * every start of the app leaves behind, so Try again really tries again. A Reset is never inferred.
 *
 * Every delete goes through Destroy's own sweep ([DestroyWipe.sweepFiles] with a kept set) and its file layer; the
 * phrase store, the other processes, background work, notifications (not the channels, not the clipboard), the
 * record's removal with its folder syncs and the "background sync held" flag are Destroy's functions too. What is
 * deleted is the table of spec 4.5: with `keep=all` the wallet itself and the cache except the three folders the kept
 * chats point at; with `keep=none` and `keep=ai-key` everything else too, by listing, except [KEPT_WITHOUT_KEEP].
 * While something of that is left the record stays and the outcome is [Outcome.NOT_FINISHED]; a record that alone
 * cannot be removed does not block ([Outcome.FINISHED], logged).
 */
object ResetWipe {
    private const val TAG = "ResetWipe"

    const val RECORD_NAME = "zchat_reset_pending"
    const val RECORD_TEMP_NAME = "zchat_reset_pending.tmp"
    const val RECORD_HEADER = "zchat-reset 1"
    const val KEEP_ALL = "all"
    const val KEEP_NONE = "none"
    const val KEEP_AI_KEY = "ai-key"
    private const val RECORD_LINES = 7

    /** What this process's start made of a Reset record. The start-up check reads it before any store is opened. */
    enum class Outcome {
        /** No record: today's start. */
        NONE,

        /** A valid record was finished (also when only the record itself could not be removed: logged, not shown). */
        FINISHED,

        /** Something of the delete set is left, or a Destroy record lies beside it: the record stays; no wallet opens. */
        NOT_FINISHED,

        /** A record that is not valid: nothing was deleted and the record was removed. */
        IGNORED,
    }

    @Volatile
    var startOutcome: Outcome = Outcome.NONE

    /**
     * One entry of the kept set of `keep=none` and `keep=ai-key`: [path] (a path under the data folder; `*` stands for
     * any name, `<owner>` for the record's owner and `<other>` for any other wallet's tag), [reason], and [told]: a word
     * of the paragraph `delete_wallet_stays_unchecked` (batch ZC-090-R) that names it, `own sentence: <key>`, or
     * `no wallet content: <why>`.
     */
    data class Kept(
        val path: String,
        val reason: String,
        val told: String,
    )

    // ZC-090-R: the words of delete_wallet_stays_unchecked that name these entries (ResetSaysExactlyWhatStaysTest).
    private const val TOLD_OTHERS = "other recovery phrases"
    private const val TOLD_SETTINGS = "settings"

    /** Spec 4.5, the kept set (one constant; a `.bak` twin of a kept preferences file is kept with it). */
    val KEPT_WITHOUT_KEEP: List<Kept> =
        listOf(
            Kept("shared_prefs/zchat_set_aside_<other>.xml", "another wallet's set-aside chats (R11)", TOLD_OTHERS),
            Kept("files/address_book/*", "another wallet's Contacts list (a name not on the record's files line; R10)", TOLD_OTHERS),
            Kept("files/metadata/*", "another wallet's notes (a name not on the record's files line; R10)", TOLD_OTHERS),
            Kept("files/ai_images/<other>/", "another wallet's AI pictures (ZC-718)", TOLD_OTHERS),
            Kept(
                "shared_prefs/*__unreadable_*.xml",
                "another wallet's kept copy of a store that could not be read, by its owner mark or by the tag in a " +
                    "set-aside bundle's name (rule S14)",
                TOLD_OTHERS,
            ),
            Kept(
                "shared_prefs/${AiPreferences.PREFS_NAME}__unreadable_*.xml",
                "a kept copy of the AI store may hold the only access key (spec 3.4 item 6)",
                "own sentence: delete_wallet_checkbox_warning_unchecked",
            ),
            Kept(
                "shared_prefs/zchat_encrypted_store_open_failures.xml",
                "the marks of the kept copies above, kept whole while one of them is (R-R11)",
                "no wallet content: hashes and counts",
            ),
            Kept("shared_prefs/zchat_chat_owner.xml", "says none since the Reset's step S4 (R13)", "no wallet content: it says none"),
            Kept("shared_prefs/zchat_ratchet_counter_floor.xml", "a counter is never used twice (R14)", "message counters"),
            Kept("shared_prefs/zchat_tex_recovery.xml", "pay-once memory of exchange-address sends (R16)", "record of payments"),
            Kept("shared_prefs/zchat_theme_prefs.xml", "app setting (R17)", TOLD_SETTINGS),
            Kept("shared_prefs/zchat_update_check.xml", "app setting (R17)", TOLD_SETTINGS),
            Kept("shared_prefs/zchat_post_upgrade.xml", "app setting (R17)", TOLD_SETTINGS),
            Kept(
                "shared_prefs/zchat_set_aside_<owner>.xml",
                "for keep=ai-key only: the key to AI credit (spec 3.7)",
                "own sentence: delete_wallet_checkbox_warning_unchecked",
            ),
            Kept("no_backup/co.electricoin.zcash/sapling-spend.params", "public, large (R4)", "no wallet content: public file"),
            Kept("no_backup/co.electricoin.zcash/sapling-output.params", "public, large (R4)", "no wallet content: public file"),
            Kept("no_backup/androidx.work.workdb*", "the job library's own table (R21)", "no wallet content: Android's own"),
            Kept("no_backup/zchat_reset_pending", "the record itself", "no wallet content: the record"),
            Kept("no_backup/${DestroyWipe.RECORD_NAME}", "Destroy's record", "no wallet content: the record"),
            Kept("lib/", "the app's code", "no wallet content: the build's own"),
            Kept("code_cache/", "Android's code cache", "no wallet content: Android's own"),
            Kept("files/qa/", "debug builds' fault seams", "no wallet content: the build's own"),
            Kept("files/profileInstalled", "Android's profile mark", "no wallet content: Android's own"),
        )

    private const val PREFS = "shared_prefs"
    private const val NO_BACKUP = "no_backup"
    private const val BAK = ".bak"
    private const val XML = ".xml"
    private const val SDK_FOLDER = "$NO_BACKUP/${WalletDataDatabaseFiles.SDK_NO_BACKUP_SUBDIRECTORY}"
    private const val PHRASE_STORE = "co.electriccoin.zcash.encrypted"
    private const val SET_ASIDE_PREFIX = "zchat_set_aside_"
    private const val AI_STORE = AiPreferences.PREFS_NAME
    private const val MARKS = "zchat_encrypted_store_open_failures"
    private const val UNREADABLE = "__unreadable_"
    private const val COPY_OWNER = "copy_owner:"
    private const val OWNER_FILE = "zchat_chat_owner"
    private const val OWNER_KEY = "owner"
    private const val KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val CONTACTS_NAME = Regex("zashi-address-book-[0-9a-f]+")
    private val NOTES_NAME = Regex("zashi-metadata-[0-9a-f]+")

    /** The wallet's own files (spec 4.5, first row): deleted with every keep value. */
    private val WALLET_PREFS = listOf("co.electriccoin.zcash", "cash.z.ecc.android.sdk", "cash.z.ecc.android.sdk.encrypted")
    private val SAPLING_PARAMS = setOf("sapling-spend.params", "sapling-output.params")
    private val KEPT_CACHE = setOf("zchat_files", "zchat_audio", "zchat_attachment_retry")
    private val KEPT_PREFS =
        setOf(
            OWNER_FILE,
            "zchat_ratchet_counter_floor",
            "zchat_tex_recovery",
            "zchat_theme_prefs",
            "zchat_update_check",
            "zchat_post_upgrade",
        )

    /** Folders that can hold both kept files and deleted ones: entered, never kept or deleted whole. */
    private val ENTERED_FOLDERS = setOf(PREFS, NO_BACKUP, SDK_FOLDER, "cache", "files")
    private val ENTERED_WITHOUT_KEEP = setOf("files/address_book", "files/metadata", "files/ai_images")

    /** A record that is whole. */
    private data class Record(
        val install: Long,
        val store: String,
        val keep: String,
        val owner: String,
        val files: List<String>,
    )

    /**
     * The seven lines of a record (spec 4.3). It holds no phrase, no address and no name: an install time, a hash of
     * wrapped key material, the keep value, the owner tag (a one-way hash), file names that are already on the phone,
     * and a hash of those lines.
     */
    fun recordText(
        installTimeMs: Long,
        storeHash: String,
        keep: String,
        owner: String,
        files: List<String>,
    ): String {
        val body =
            "$RECORD_HEADER\ninstall=$installTimeMs\nstore=$storeHash\nkeep=$keep\nowner=$owner\nfiles=${files.joinToString(",")}\n"
        return body + "end=" + sha256Hex(body) + "\n"
    }

    // ------------------------------------------------------------------ ZC-090-R: the Reset itself

    /**
     * True from step S5 of a Reset of this process (its record is on disk): no start of the sync service is let
     * through ([syncStartRefused]), and the alarm's receiver and the app's own turn-on of background sync do nothing.
     * The process ends at S7; nothing clears it.
     */
    @Volatile
    var resetRunning: Boolean = false

    /** The Reset's calls to Android for its wake (replaceable; the defaults are the real ones). */
    private var armSyncAlarm: (Context) -> Unit = { context -> SyncAlarmScheduler.schedule(context) }
    private var enqueueWakeJob: (Context) -> Unit = { context -> ResetWakeWorker.enqueue(context) }
    private var cancelWake: (Context) -> Unit = { context ->
        try {
            ResetWakeWorker.cancel(context)
        } finally {
            SyncAlarmScheduler.cancel(context)
        }
    }

    /**
     * The sync service's rule (onStartCommand, before anything): a start is refused while a Reset of this process
     * runs, with an intent or without one; and, as for Destroy, a start with no intent (Android restarting the sticky
     * service) is refused while background sync is held, which the start pass sets on a valid record before any
     * service of the new process can act.
     */
    fun syncStartRefused(hasIntent: Boolean): Boolean = resetRunning || (!hasIntent && DestroyWipe.backgroundSyncHeld)

    /** Step S5 begins: from here nothing of this process starts the sync service again. */
    fun holdForReset() {
        resetRunning = true
        DestroyWipe.backgroundSyncHeld = true
    }

    /**
     * Step S5 ends: one alarm and one job, so that the next start (which runs the pass) comes within about 15 minutes
     * even when Android does not reopen ZCHAT, and after a reboot (the job library keeps its requests). Each call in
     * its own catch.
     */
    fun armWake(context: Context) {
        quietly("alarm") { armSyncAlarm(context) }
        quietly("job") { enqueueWakeJob(context) }
    }

    /**
     * At the start of the main process, after the pass: once a record was finished or found not valid, the Reset's
     * alarm and job are cancelled (the app sets its own when a wallet turns sync on). A start with no record does
     * nothing here: every start of an install that never reset is as it was.
     */
    fun cancelWakeAfterThePass(context: Context) {
        if (startOutcome != Outcome.FINISHED && startOutcome != Outcome.IGNORED) return
        quietly("wake") { cancelWake(context) }
    }

    /**
     * Step S3 of a Reset: the record, written whole (temp name, written and synced, renamed, folder synced) with the
     * Destroy record's own file calls, within [limitMs] on a thread of its own. Not written while a Destroy record is
     * on the phone, when the install time or the phrase store's hash is unknown, or for an owner that is not a tag.
     * The rename and the end of the limit are decided under one lock: once this answered false no record appears
     * later. A rename that was already asked when the limit ran out is whole or not at all and counts as written.
     */
    fun writeResetRecord(
        context: Context,
        keep: String,
        owner: String,
        files: List<String>,
        limitMs: Long,
    ): Boolean {
        val gate = Any()
        val state = intArrayOf(PENDING)
        DestroyWipe.runWithin("zchat-reset-S3-record", limitMs) {
            val data = dataFolder(context) ?: return@runWithin
            if (DestroyWipe.recordFile(context)?.isFile == true) return@runWithin
            val install = DestroyWipe.installTime(context)
            val store = DestroyWipe.storeHash(context)
            if (install <= 0L || !HEX64.matches(store) || !HEX64.matches(owner)) return@runWithin
            val folder = File(data, NO_BACKUP)
            folder.mkdirs()
            val temp = File(folder, RECORD_TEMP_NAME)
            DestroyWipe.writeWhole(temp, recordText(install, store, keep, owner, files).toByteArray(Charsets.UTF_8))
            synchronized(gate) {
                if (state[0] != PENDING) return@runWithin
                state[0] = RENAMING
            }
            val renamed = DestroyWipe.renameWhole(temp, File(folder, RECORD_NAME))
            synchronized(gate) { state[0] = if (renamed) WRITTEN else REFUSED }
            if (renamed) DestroyWipe.syncFolderOf(folder)
        }
        val answer =
            synchronized(gate) {
                if (state[0] == PENDING) state[0] = LATE
                state[0]
            }
        if (answer == RENAMING) Log.w(TAG, "the record's rename was still running when its time ran out")
        return answer == WRITTEN || answer == RENAMING
    }

    private const val PENDING = 0
    private const val RENAMING = 1
    private const val WRITTEN = 2
    private const val REFUSED = 3
    private const val LATE = 4

    /**
     * The first statement after Destroy's pass at the app's start. Never throws; the result is [startOutcome]. A pass
     * that fails while its record is still on the phone (review finding 3: the phrase store may already be gone) is
     * never an ordinary start: [Outcome.NOT_FINISHED], with background sync held, as when files are left.
     */
    fun atProcessStart(context: Context) {
        try {
            startOutcome = startPass(context)
        } catch (t: Throwable) {
            Log.w(TAG, "start pass: ${t.javaClass.simpleName}")
            if (recordStillThere(context)) {
                DestroyWipe.backgroundSyncHeld = true
                startOutcome = Outcome.NOT_FINISHED
            }
        }
    }

    private fun recordStillThere(context: Context): Boolean =
        try {
            dataFolder(context)?.let { File(File(it, NO_BACKUP), RECORD_NAME).isFile } == true
        } catch (t: Throwable) {
            false
        }

    /** The plan of a valid record; replaceable so the JVM tests can make it fail (nothing there makes it throw). */
    @Volatile
    private var makePlan: (File, Record) -> Plan = { data, record -> Plan(data, record) }

    @Suppress("ReturnCount")
    private fun startPass(context: Context): Outcome {
        val data = dataFolder(context) ?: return Outcome.NONE
        val record = File(File(data, NO_BACKUP), RECORD_NAME)
        val destroyRecord = DestroyWipe.recordFile(context)
        // Item 1: a Destroy record that Destroy's pass could not remove: no Reset delete beside it.
        if (destroyRecord != null && destroyRecord.isFile) return if (record.isFile) Outcome.NOT_FINISHED else Outcome.NONE
        // Item 2: no record is today's start (a leftover temp file is removed).
        if (!record.isFile) {
            val temp = File(record.parentFile, RECORD_TEMP_NAME)
            if (temp.isFile) DestroyWipe.removeRecord(temp)
            return Outcome.NONE
        }
        // Item 3: only the main process ever deletes.
        val packageName = DestroyWipe.packageNameOf(context)
        val processName =
            try {
                DestroyWipe.processNameOf(context)
            } catch (t: Throwable) {
                null
            }
        if (packageName.isNullOrEmpty() || processName.isNullOrEmpty()) return cannotJudge("the process name")
        if (!MainProcess.isMain(processName, packageName)) {
            Log.w(TAG, "a record of a Reset in a process that is not the main one: ending it")
            endThisProcessQuietly()
            return Outcome.NONE
        }
        // Items 4 and 5.
        val text =
            try {
                record.readText()
            } catch (t: Throwable) {
                null
            }
        val parsed = parseRecord(text) ?: return ignore(record, "it is not whole")
        val installTime = DestroyWipe.askInstallTime(context) ?: return cannotJudge("the install time")
        if (installTime != parsed.install) return ignore(record, "it is of another install")
        val storeThere = DestroyWipe.phraseStoreState(context) ?: return cannotJudge("whether the phrase store is there")
        if (storeThere && !isOwnPhraseStore(data, context, parsed.store)) {
            return ignore(record, "the phrase store on the phone is not the one it names")
        }
        return finish(context, data, record, parsed)
    }

    /** Item 4: the store line's hash, or a file that holds key entries only (both, one, or none). */
    private fun isOwnPhraseStore(
        data: File,
        context: Context,
        store: String,
    ): Boolean {
        if (DestroyWipe.storeHash(context) == store) return true
        val prefs = File(data, PREFS)
        val backup = File(prefs, "$PHRASE_STORE$XML$BAK")
        val source = if (backup.isFile) backup else File(prefs, "$PHRASE_STORE$XML")
        val names = entryNames(source) ?: return false
        return names.all { it == KEY_KEYSET || it == VALUE_KEYSET }
    }

    /** Item 6 and 7: the deletes, the check, the record. */
    private fun finish(
        context: Context,
        data: File,
        record: File,
        parsed: Record,
    ): Outcome {
        Log.w(TAG, "a valid record of a Reset: finishing it")
        quietly("processes") { DestroyWipe.endOtherProcesses(context) }
        quietly("phrase store") { DestroyWipe.deletePhraseStore(context) }
        DestroyWipe.runWithin("zchat-reset-traces", DestroyWipe.LIMIT_START_TRACES_MS) {
            DestroyWipe.stopBackgroundWork(context)
            DestroyWipe.stopTraces(context, onlyNotifications = true)
        }
        val plan = makePlan(data, parsed)
        quietly("files") { DestroyWipe.sweepFiles(context) { file -> plan.keeps(file) } }
        DestroyWipe.backgroundSyncHeld = true
        val left =
            try {
                plan.countLeft()
            } catch (t: Throwable) {
                1
            }
        if (left > 0) {
            Log.w(TAG, "the Reset is not finished: $left file(s) of its delete set are left")
            return Outcome.NOT_FINISHED
        }
        quietly("folder sync") { DestroyWipe.syncSweptFolders(context) }
        if (!DestroyWipe.removeRecord(record)) Log.w(TAG, "the Reset is finished; its record could not be removed")
        return Outcome.FINISHED
    }

    /**
     * What the pass deletes for one record (spec 4.5's table), as a kept set for Destroy's sweep: [keeps] is true for a
     * file or folder that stays whole; a folder that holds both is entered.
     */
    private class Plan(
        private val data: File,
        private val record: Record,
    ) {
        private val prefs = File(data, PREFS)
        private val everything = record.keep != KEEP_ALL && !ownerRuleApplies()
        private val names = record.files.filter { CONTACTS_NAME.matches(it) || NOTES_NAME.matches(it) }.toSet()
        private val keptCopies: Set<String>? = if (everything) keptCopies() else null
        private val aiStoreStays = everything && record.keep == KEEP_AI_KEY && aiGuard()

        /** The owner rule: the plain owner file names another wallet, so only the wallet itself goes. */
        private fun ownerRuleApplies(): Boolean {
            val owner = rawOwner() ?: return false
            return HEX64.matches(owner) && owner != record.owner
        }

        private fun rawOwner(): String? = readPrefs(File(prefs, "$OWNER_FILE$XML"))?.get(OWNER_KEY)

        /** The two guards of keep=ai-key: the kept file is missing, or the owner file still names the reset wallet. */
        private fun aiGuard(): Boolean {
            val kept = File(prefs, "$SET_ASIDE_PREFIX${record.owner}$XML")
            val keptMissing = !kept.exists() && !File(prefs, kept.name + BAK).exists()
            return keptMissing || rawOwner() == record.owner
        }

        /**
         * The kept copies of stores that could not be read that stay (S14): another wallet's by its owner mark, a copy
         * of another wallet's set-aside bundle, and every copy of the AI store. Null when the marks file is there and
         * cannot be parsed: then no copy is deleted.
         */
        private fun keptCopies(): Set<String>? {
            val marksFile = File(prefs, "$MARKS$XML").let { file -> File(prefs, file.name + BAK).takeIf { it.isFile } ?: file }
            val marks = if (marksFile.isFile) readPrefs(marksFile) ?: return null else emptyMap()
            val copies =
                prefs
                    .list()
                    .orEmpty()
                    .filter { UNREADABLE in it }
                    .map { it.removeSuffix(BAK).removeSuffix(XML) }
                    .toSet()
            return copies
                .filter { copy ->
                    val store = copy.substringBefore(UNREADABLE)
                    val tag = marks[COPY_OWNER + copy]
                    when {
                        store == AI_STORE -> true
                        store.startsWith(SET_ASIDE_PREFIX) -> store.removePrefix(SET_ASIDE_PREFIX) != record.owner
                        tag != null && HEX64.matches(tag) -> tag != record.owner
                        else -> false
                    }
                }.toSet()
        }

        /** True for what stays whole: a file the table keeps, or a folder that holds nothing the table deletes. */
        fun keeps(file: File): Boolean {
            val path = file.relativeTo(data).invariantSeparatorsPath
            val folder = file.isDirectory
            return !deletes(path, folder) && !(folder && holdsDeletes(path))
        }

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        private fun deletes(
            path: String,
            folder: Boolean,
        ): Boolean {
            val top = path.substringBefore('/')
            val name = path.substringAfterLast('/')
            // The wallet itself, with every keep value.
            if (top == PREFS && path.count { it == '/' } == 1) {
                val store = name.removeSuffix(BAK).removeSuffix(XML)
                if (store == PHRASE_STORE || store in WALLET_PREFS) return true
            }
            if (path == SDK_FOLDER) return folder
            if (path.startsWith("$SDK_FOLDER/")) {
                val first = path.removePrefix("$SDK_FOLDER/").substringBefore('/')
                // ZC-090-O (W-52): old wallet data that was set aside goes without keep; with keep, and under the owner
                // rule, it stays.
                val oldWalletData = first.contains(WalletDataDatabaseFiles.SET_ASIDE_MARKER)
                return !(first in SAPLING_PARAMS || (oldWalletData && !everything))
            }
            if (top == "databases" || path == "files/pczt" || path.startsWith("files/pczt/")) return true
            if (top == "cache") {
                if (path == "cache") return folder
                val first = path.removePrefix("cache/").substringBefore('/')
                if (first !in KEPT_CACHE) return true
                return everything
            }
            if (!everything) return false
            return deletesWithoutKeep(path, folder, top, name)
        }

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        private fun deletesWithoutKeep(
            path: String,
            folder: Boolean,
            top: String,
            name: String,
        ): Boolean {
            when (top) {
                "lib", "code_cache" -> return false
                NO_BACKUP -> {
                    if (path == NO_BACKUP) return false
                    val first = path.removePrefix("$NO_BACKUP/").substringBefore('/')
                    return !(first == RECORD_NAME || first == RECORD_TEMP_NAME || first == DestroyWipe.RECORD_NAME) &&
                        !first.startsWith("androidx.work.workdb")
                }
                PREFS -> return path != PREFS && !keptPrefs(name)
                "files" -> return deletesInFiles(path, folder)
            }
            return true
        }

        private fun keptPrefs(name: String): Boolean {
            val store = name.removeSuffix(BAK).removeSuffix(XML)
            return when {
                store in KEPT_PREFS -> true
                UNREADABLE in store -> keptCopies?.contains(store) ?: true
                store == MARKS -> keptCopies?.isNotEmpty() ?: true
                store == AI_STORE -> aiStoreStays
                store.startsWith(SET_ASIDE_PREFIX) -> {
                    val tag = store.removePrefix(SET_ASIDE_PREFIX)
                    tag != record.owner || record.keep == KEEP_AI_KEY
                }
                else -> false
            }
        }

        @Suppress("ReturnCount")
        private fun deletesInFiles(
            path: String,
            folder: Boolean,
        ): Boolean {
            if (path == "files") return false
            if (path == "files/qa" || path.startsWith("files/qa/") || path == "files/profileInstalled") return false
            for ((dir, pattern) in listOf("files/address_book" to CONTACTS_NAME, "files/metadata" to NOTES_NAME)) {
                if (path == dir) return false
                if (path.startsWith("$dir/")) {
                    val first = path.removePrefix("$dir/").substringBefore('/')
                    return first in names && pattern.matches(first) && !path.removePrefix("$dir/").contains('/')
                }
            }
            if (path == "files/ai_images") return false
            if (path.startsWith("files/ai_images/")) {
                val first = path.removePrefix("files/ai_images/").substringBefore('/')
                val inFolder = path.removePrefix("files/ai_images/").contains('/')
                return when {
                    first == record.owner -> true
                    !inFolder && !folder -> true
                    else -> false
                }
            }
            return true
        }

        /** Item 7: how many files of the delete set are still there (`.bak` twins included; folders do not count). */
        fun countLeft(): Int = count(data)

        private fun count(dir: File): Int =
            (dir.listFiles() ?: throw java.io.IOException("a folder cannot be listed")).sumOf { file ->
                when {
                    keeps(file) -> 0
                    file.isDirectory -> count(file)
                    else -> 1
                }
            }

        /** A folder that can hold both kept files and deleted ones: it is entered, never kept or deleted whole. */
        private fun holdsDeletes(path: String): Boolean =
            path in ENTERED_FOLDERS ||
                (everything && path in ENTERED_WITHOUT_KEEP)
    }

    /**
     * The record's lines, when it is whole: seven lines, version 1, a 64-hex store line, a keep value of three, a
     * 64-hex owner (it becomes part of a path), and an end line that matches. Null for anything else.
     */
    @Suppress("ReturnCount")
    private fun parseRecord(text: String?): Record? {
        val lines = text?.split("\n") ?: return null
        if (lines.size != RECORD_LINES + 1 || lines[0] != RECORD_HEADER || lines[RECORD_LINES] != "") return null

        fun value(
            index: Int,
            key: String,
        ): String? = lines[index].takeIf { it.startsWith("$key=") }?.removePrefix("$key=")
        val install = value(1, "install")?.toLongOrNull() ?: return null
        val store = value(2, "store")?.takeIf { HEX64.matches(it) } ?: return null
        val keep = value(3, "keep")?.takeIf { it == KEEP_ALL || it == KEEP_NONE || it == KEEP_AI_KEY } ?: return null
        val owner = value(4, "owner")?.takeIf { HEX64.matches(it) } ?: return null
        val files = value(5, "files")?.let { if (it.isEmpty()) emptyList() else it.split(",") } ?: return null
        if (install <= 0L || recordText(install, store, keep, owner, files) != text) return null
        return Record(install, store, keep, owner, files)
    }

    /** Nothing is deleted; the record is removed (a record that cannot be removed is judged again at the next start). */
    private fun ignore(
        record: File,
        why: String,
    ): Outcome {
        Log.w(TAG, "the record of a Reset is not valid ($why): nothing is deleted")
        if (!DestroyWipe.removeRecord(record)) Log.w(TAG, "a record that is not valid could not be removed")
        return Outcome.IGNORED
    }

    /** Nothing is deleted, the record stays, the process ends: the next start judges again. */
    private fun cannotJudge(what: String): Outcome {
        Log.w(TAG, "a record of a Reset, and this start cannot tell $what: ending the process")
        endThisProcessQuietly()
        return Outcome.NONE
    }

    private fun endThisProcessQuietly() = quietly("process end") { DestroyWipe.endThisProcess() }

    private inline fun quietly(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "start pass, $what: ${t.javaClass.simpleName}")
        }
    }

    private fun dataFolder(context: Context): File? =
        try {
            context.applicationInfo?.dataDir?.let { File(it) }
        } catch (t: Throwable) {
            null
        }

    /** The names of a preferences file's entries, by a raw parse; null when the file cannot be read or parsed. */
    private fun entryNames(file: File): List<String>? = readPrefs(file)?.keys?.toList()

    /** A preferences file's entries (name to text value; a non-text value as its value attribute), raw; null if not parsable. */
    private fun readPrefs(file: File): Map<String, String>? =
        try {
            if (!file.isFile) {
                null
            } else {
                val root =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(file)
                        .documentElement
                if (root.tagName != "map") {
                    null
                } else {
                    val nodes = root.childNodes
                    val entries = LinkedHashMap<String, String>()
                    for (i in 0 until nodes.length) {
                        val node = nodes.item(i) as? org.w3c.dom.Element ?: continue
                        val name = node.getAttribute("name")
                        entries[name] = if (node.hasAttribute("value")) node.getAttribute("value") else node.textContent
                    }
                    entries
                }
            }
        } catch (t: Throwable) {
            null
        }

    private fun sha256Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
