package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-090-P (register ZC-090, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.5 and 6.2): the inventory behind
 * Reset ZCHAT. It fails when something new that stores or keeps something is added without a decision about the Reset.
 *
 * Table 1 is the scan of DestroyInventoryTest (every storage call in every source set that ships, counted in the
 * text) with what a Reset does about it: [WALLET] (deleted with every keep value), [DATA] (deleted without keep),
 * `kept: <path>` (a path of `ResetWipe.KEPT_WITHOUT_KEEP`), or `none: <why>` for a call that keeps nothing in the
 * app's data folder (a notification, a job, the clipboard, a process, the external folders, the phone-security keys,
 * the user's own saves). A kept path of the table must be an entry of the constant, and every entry of the constant
 * must be named by a row or by [KEPT_WITH_NO_CALL] (what Android, the build or the record itself puts there).
 *
 * Table 2 is a guard that passes at RED: WHO WRITES THE PHRASE STORE. The pass's third valid state (a phrase store
 * that holds key entries only) rests on one fact: nothing writes an entry into a phrase store that holds no wallet,
 * except from a screen the start-up gate does not show while a Reset is not finished (residual R-R7). One row for each
 * shipped file that names `EncryptedPreferenceProvider`, and for each provider the files that call its write
 * functions, each with why. Traced at RED (16 Oct 2026); see the batch's RED.md.
 */
class ResetInventoryTest {
    private data class Row(
        val file: String,
        val call: String,
        val count: Int,
        val reset: String,
    )

    private val calls =
        listOf(
            "getSharedPreferences(",
            "createEncryptedPrefs(",
            "EncryptedSharedPreferences.create(",
            "MasterKey.Builder(",
            "KeyGenParameterSpec",
            "KeyStore.getInstance(",
            "noBackupFilesDir",
            "filesDir",
            "cacheDir",
            "getDir(",
            "codeCacheDir",
            "getExternalFilesDir",
            "externalCacheDir",
            "externalMediaDirs",
            "obbDir",
            "createDeviceProtectedStorageContext",
            "openFileOutput",
            "getDatabasePath(",
            "SQLiteOpenHelper",
            "databaseBuilder",
            "openOrCreateDatabase",
            "DataStore",
            "WebView",
            "MediaStore",
            "Environment.",
            "DownloadManager",
            "NotificationChannel(",
            ".notify(",
            "setPrimaryClip",
            "AlarmManager",
            "PendingIntent.get",
            "AccountManager",
            "ShortcutManager",
            "android:process=",
            "<provider",
            "WorkRequest",
            "setInputData(",
        )

    private val table =
        listOf(
            Row("app/src/main/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("app/src/zcashmainnetFossDebug/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("app/src/zcashmainnetFossRelease/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("app/src/zcashmainnetStoreDebug/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("app/src/zcashtestnetFossDebug/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("app/src/zcashtestnetFossRelease/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("app/src/zcashtestnetStoreDebug/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("app/src/zcashtestnetStoreRelease/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("crash-android-lib/src/main/AndroidManifest.xml", "<provider", 1, PROCESS),
            Row("crash-android-lib/src/main/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row(
                "crash-android-lib/src/main/kotlin/co/electriccoin/zcash/crash/android/AndroidExceptionPath.kt",
                "getExternalFilesDir",
                2,
                CRASH_REPORTS
            ),
            Row("crash-android-lib/src/zcashmainnetStoreDebug/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashmainnetStoreDebug/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row("crash-android-lib/src/zcashmainnetStoreRelease/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashmainnetStoreRelease/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreDebug/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreDebug/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreRelease/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreRelease/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row("$PREFERENCE_IMPL/AndroidPreferenceProvider.kt", "EncryptedSharedPreferences.create(", 1, WALLET),
            Row("$PREFERENCE_IMPL/AndroidPreferenceProvider.kt", "getSharedPreferences(", 1, WALLET),
            Row("spackle-android-lib/src/main/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("$SPACKLE/ClipboardManagerUtil.kt", "setPrimaryClip", 2, CLIPBOARD),
            Row("$SPACKLE/ContextExt.kt", "cacheDir", 2, WALLET),
            Row("$SPACKLE/ContextExt.kt", "getExternalFilesDir", 2, CRASH_REPORTS),
            Row("ui-lib/src/debug/AndroidManifest.xml", "<provider", 1, NO_FILE),
            Row("ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/FaultSeamsConfig.kt", "filesDir", 10, kept("files/qa/")),
            Row("ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/FaultSeamsLoader.kt", "filesDir", 3, kept("files/qa/")),
            // The file name in two parts, as DestroyInventoryTest has it (ReleaseHookAbsenceTest). Debug builds only: the qa/
            // folder, and the scratch store and the old group file it writes, which go with the rest without keep.
            Row("ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/TestHooks" + "Receiver.kt", "filesDir", 6, kept("files/qa/")),
            Row("ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/TestHooks" + "Receiver.kt", "getSharedPreferences(", 2, DATA),
            Row("$Z/di/DataSourceModule.kt", "getSharedPreferences(", 1, kept("shared_prefs/zchat_post_upgrade.xml")),
            Row("$Z/global/StorageChecker.kt", "Environment.", 1, "none: reads the free space of the phone, stores nothing"),
            Row("$Z/ui/call/CallNotificationController.kt", ".notify(", 1, NOTICES),
            Row("$Z/ui/call/CallNotificationController.kt", "NotificationChannel(", 4, NOTICES),
            Row("$Z/ui/call/CallNotificationController.kt", "PendingIntent.get", 3, NOTICES),
            Row("$U/common/provider/AddressBookStorageProvider.kt", "filesDir", 3, DATA),
            Row("$U/common/provider/AddressBookStorageProvider.kt", "noBackupFilesDir", 1, DATA),
            Row("$U/common/provider/MetadataStorageProvider.kt", "filesDir", 3, DATA),
            Row("$U/common/tex/TexRecoveryNotifier.kt", ".notify(", 1, NOTICES),
            Row("$U/common/tex/TexRecoveryNotifier.kt", "NotificationChannel(", 2, NOTICES),
            Row("$U/common/tex/TexRecoveryNotifier.kt", "PendingIntent.get", 1, NOTICES),
            Row("$U/common/tex/TexRecoveryStore.kt", "getSharedPreferences(", 1, kept("shared_prefs/zchat_tex_recovery.xml")),
            Row("$U/common/usecase/ExportTaxUseCase.kt", "cacheDir", 1, WALLET),
            Row("$U/common/usecase/ShareImageUseCase.kt", "cacheDir", 2, WALLET),
            Row("$U/common/usecase/SharePCZTUseCase.kt", "filesDir", 1, WALLET),
            Row("$U/screen/ai/AiHistory.kt", "cacheDir", 1, WALLET),
            Row("$U/screen/ai/AiHistory.kt", "filesDir", 6, DATA),
            Row("$U/screen/ai/AiHistory.kt", "getSharedPreferences(", 1, kept("shared_prefs/zchat_chat_owner.xml")),
            Row("$U/screen/ai/AiPreferences.kt", "filesDir", 1, DATA),
            Row("$U/screen/ai/AiTabView.kt", "Environment.", 1, SAVED),
            Row("$U/screen/ai/AiTabView.kt", "MediaStore", 6, SAVED),
            Row("$U/screen/ai/AiTabView.kt", "cacheDir", 4, WALLET),
            Row("$U/screen/ai/AiTabView.kt", "filesDir", 1, DATA),
            Row("$U/screen/ai/AiTabView.kt", "setPrimaryClip", 1, CLIPBOARD),
            Row("$U/screen/changeidentity/IdentityManager.kt", "getSharedPreferences(", 1, DATA),
            Row("$U/screen/chat/AndroidChat.kt", "cacheDir", 2, DATA),
            Row("$U/screen/chat/AndroidChat.kt", "setPrimaryClip", 2, CLIPBOARD),
            Row("$U/screen/chat/datasource/AddressCacheImpl.kt", "getSharedPreferences(", 1, DATA),
            Row("$U/screen/chat/datasource/AvatarStore.kt", "filesDir", 3, DATA),
            Row(
                "$U/screen/chat/datasource/ChatWalletOwnershipStores.kt",
                "getSharedPreferences(",
                2,
                kept("shared_prefs/zchat_set_aside_<other>.xml")
            ),
            Row("$U/screen/chat/datasource/ContactBookImpl.kt", "getSharedPreferences(", 2, DATA),
            Row("$U/screen/chat/datasource/EncryptedStoreOpener.kt", "EncryptedSharedPreferences.create(", 2, DATA),
            Row("$U/screen/chat/datasource/EncryptedStoreOpener.kt", "MasterKey.Builder(", 2, KEYS),
            Row("$U/screen/chat/datasource/EncryptedStoreOpener.kt", "getSharedPreferences(", 2, kept("shared_prefs/*__unreadable_*.xml")),
            Row("$U/screen/chat/datasource/LazyEncryptedStore.kt", "getSharedPreferences(", 2, DATA),
            Row("$U/screen/chat/datasource/KeysetGuard.kt", "getSharedPreferences(", 1, WALLET),
            Row("$U/screen/chat/datasource/ZchatPreferences.kt", "createEncryptedPrefs(", 15, DATA),
            Row("$U/screen/chat/datasource/ZchatPreferences.kt", "getSharedPreferences(", 25, DATA),
            Row("$U/screen/chat/filesharing/AudioRecorder.kt", "cacheDir", 1, DATA),
            Row("$U/screen/chat/filesharing/FileDownloadCache.kt", "cacheDir", 7, DATA),
            Row("$U/screen/chat/share/PendingShareStore.kt", "cacheDir", 3, WALLET),
            // ZC-868: the guard only asks whether the shared key is there; it never makes or deletes a key.
            Row("$U/screen/chat/datasource/MasterKeyGuard.kt", "KeyStore.getInstance(", 1, KEYS),
            // Train C3: the Destroy Advanced batches (T7aD) against the Reset's table (RESET-LEFTOVERS-SPEC 10.3: each
            // side adds a row to the other's table). The record's seal reads the wallet's own key; Destroy's finish job.
            Row("$U/screen/chat/util/DestroyRecordSeal.kt", "KeyStore.getInstance(", 1, DESTROY),
            Row("$U/screen/chat/util/DestroyWipe.kt", "KeyStore.getInstance(", 1, DESTROY),
            Row("$U/screen/chat/util/DestroyWipe.kt", "WorkRequest", 2, DESTROY),
            // The kept list's key is a phone-security key (R23). Its file no_backup/zchat_kept_names is deleted by a
            // Reset without keep TODAY; spec 4.5 ("only if the Destroy Advanced batches landed") and its 9.x row 7 say
            // it is in Reset's kept set, with the told clause "list of names". Left to a fix lane (train C3 CONFLICTS.md).
            Row("$U/screen/chat/util/KeptNames.kt", "KeyGenParameterSpec", 2, KEYS),
            Row("$U/screen/chat/util/KeptNames.kt", "KeyStore.getInstance(", 1, KEYS),
            Row("$U/screen/chat/util/KeptNames.kt", "noBackupFilesDir", 1, DATA),
            Row("$U/screen/chat/util/DestroyWipe.kt", "NotificationChannel(", 1, DESTROY),
            Row("$U/screen/chat/util/DestroyWipe.kt", "createDeviceProtectedStorageContext", 1, DESTROY),
            Row("$U/screen/chat/util/DestroyWipe.kt", "externalCacheDir", 1, DESTROY),
            Row("$U/screen/chat/util/DestroyWipe.kt", "getExternalFilesDir", 1, DESTROY),
            Row("$U/screen/chat/util/DestroyWipe.kt", "setPrimaryClip", 1, DESTROY),
            Row("$U/screen/chat/view/ChatDetailView.kt", "Environment.", 3, SAVED),
            Row("$U/screen/chat/view/ChatDetailView.kt", "MediaStore", 9, SAVED),
            Row("$U/screen/chat/view/ChatDetailView.kt", "cacheDir", 6, DATA),
            Row("$U/screen/chat/viewmodel/ChatViewModel.kt", "cacheDir", 11, DATA),
            Row(
                "$U/screen/settings/datasource/ThemePreferenceDataSource.kt",
                "getSharedPreferences(",
                1,
                kept("shared_prefs/zchat_theme_prefs.xml")
            ),
            Row("$U/screen/update/UpdateChecker.kt", "cacheDir", 2, WALLET),
            Row("$U/screen/update/UpdateChecker.kt", "externalCacheDir", 1, "none: the external cache, which no Reset touches (R25)"),
            Row("$U/screen/update/UpdateChecker.kt", "getSharedPreferences(", 2, kept("shared_prefs/zchat_update_check.xml")),
            Row("$U/screen/viewingkeyexport/ViewingKeyExportVM.kt", "setPrimaryClip", 1, CLIPBOARD),
            // ZC-090-O: old wallet data that was set aside goes without keep (W-52).
            Row("$U/screen/walletstartup/WalletDataDatabaseFiles.kt", "noBackupFilesDir", 2, DATA),
            Row("$U/service/SyncForegroundService.kt", ".notify(", 4, NOTICES),
            Row("$U/service/SyncForegroundService.kt", "NotificationChannel(", 7, NOTICES),
            Row("$U/service/SyncForegroundService.kt", "PendingIntent.get", 5, NOTICES),
            Row("$U/service/SyncForegroundService.kt", "getSharedPreferences(", 1, DATA),
            // ZC-090-R: the one job a Reset enqueues so that its pass runs after a reboot; it runs nothing itself.
            Row("$Z/work/ResetWakeWorker.kt", "WorkRequest", 2, JOBS),
            Row("$Z/work/SyncAlarmReceiver.kt", "AlarmManager", 1, JOBS),
            Row("$Z/work/SyncAlarmScheduler.kt", "AlarmManager", 6, JOBS),
            Row("$Z/work/SyncAlarmScheduler.kt", "PendingIntent.get", 1, JOBS),
            Row("$Z/work/SyncWorker.kt", "WorkRequest", 5, JOBS),
            Row("$Z/work/WorkIds.kt", "AlarmManager", 1, JOBS),
            Row("$Z/work/WorkIds.kt", "WorkRequest", 1, JOBS),
        )

    /**
     * Kept paths written by a call that is counted under a row of another class (the file's other calls decide its
     * row) or by no call of the app at all (Android, the build, the record): each with where it comes from.
     */
    private val keptWithNoCall =
        mapOf(
            "files/address_book/*" to "AddressBookStorageProvider.kt (DATA row: this wallet's named file goes)",
            "files/metadata/*" to "MetadataStorageProvider.kt (DATA row: this wallet's named file goes)",
            "files/ai_images/<other>/" to "AiHistory.kt (DATA row: this wallet's folder goes)",
            "shared_prefs/zchat_ai_prefs__unreadable_*.xml" to "EncryptedStoreOpener.kt (kept copies row)",
            "shared_prefs/zchat_encrypted_store_open_failures.xml" to "EncryptedStoreOpener.kt (kept copies row)",
            "shared_prefs/zchat_chat_owner.xml" to "ChatWalletOwnershipStores.kt (owner record)",
            "shared_prefs/zchat_set_aside_<owner>.xml" to "ChatWalletOwnershipStores.kt: the AI access key, for keep=ai-key only",
            "shared_prefs/zchat_ratchet_counter_floor.xml" to "ZchatPreferences.kt (DATA row; the floor is one of its 25)",
            "shared_prefs/zchat_post_upgrade.xml" to "DataSourceModule.kt",
            "no_backup/co.electricoin.zcash/sapling-spend.params" to "the SDK's own download",
            "no_backup/co.electricoin.zcash/sapling-output.params" to "the SDK's own download",
            "no_backup/androidx.work.workdb*" to "the job library",
            "no_backup/zchat_reset_pending" to "the record (ZC-090-R writes it)",
            "no_backup/zchat_destroy_pending" to "Destroy's record",
            "lib/" to "Android",
            "code_cache/" to "Android",
            "files/qa/" to "FaultSeamsConfig.kt (debug builds)",
            "files/profileInstalled" to "Android",
        )

    private fun scan(): List<Row> =
        DestroySources
            .shipped(setOf("kt", "java", "xml"))
            .flatMap { file ->
                val text = file.readText()
                calls.mapNotNull { call ->
                    val count = text.windowed(call.length) { if (it == call) 1 else 0 }.sum()
                    if (count == 0) null else Triple(DestroySources.relative(file), call, count)
                }
            }.map { (file, call, count) ->
                Row(file, call, count, table.firstOrNull { it.file == file && it.call == call }?.reset ?: "NOT IN THE TABLE")
            }

    @Test
    fun `every storage call has what a Reset does with it`() {
        val actual = scan().sortedWith(compareBy({ it.file }, { it.call }))
        val expected = table.sortedWith(compareBy({ it.file }, { it.call }))
        val unknown = actual.filter { row -> expected.none { it.file == row.file && it.call == row.call } }
        val changed =
            actual.mapNotNull { row ->
                expected
                    .firstOrNull { it.file == row.file && it.call == row.call && it.count != row.count }
                    ?.let { "${row.file}: ${row.call} ${it.count} -> ${row.count}" }
            }
        val gone = expected.filter { row -> actual.none { it.file == row.file && it.call == row.call } }
        assertEquals("a file or a call that is not in the table (what does a Reset do about it?)", emptyList<Row>(), unknown)
        assertEquals("a count differs from the table", emptyList<String>(), changed)
        assertEquals("the table names a file or a call the code no longer has", emptyList<Row>(), gone)
    }

    @Test
    fun `every kept path is an entry of ResetWipe's kept set, and the other way round`() {
        val constant = keptSet()
        val inTable =
            table
                .map { it.reset }
                .filter { it.startsWith(KEPT) }
                .map { it.removePrefix(KEPT) }
                .toSet()
        assertEquals("a kept path of the table that is not in ResetWipe.KEPT_WITHOUT_KEEP", emptySet<String>(), inTable - constant.keys)
        val named = inTable + keptWithNoCall.keys
        assertEquals("an entry of ResetWipe.KEPT_WITHOUT_KEEP that no row names", emptySet<String>(), constant.keys - named)
        assertEquals("a kept path with no call that is not in the constant", emptySet<String>(), keptWithNoCall.keys - constant.keys)
        for ((path, told) in constant) assertTrue("the kept entry $path has no told", told.isNotBlank())
        // Revision 2c's rows, as they stand before batch ZC-090-O.
        val reasons = keptEntries().associate { it.first to it.second }
        val ownSetAside = reasons.getValue("shared_prefs/zchat_set_aside_<owner>.xml")
        val aiCopy = reasons.getValue("shared_prefs/zchat_ai_prefs__unreadable_*.xml")
        val marks = reasons.getValue("shared_prefs/zchat_encrypted_store_open_failures.xml")
        assertTrue("the reset wallet's own set-aside file is kept for keep=ai-key only", "ai-key" in ownSetAside)
        assertTrue("a kept copy of the AI store may hold the access key", "access key" in aiCopy)
        assertTrue("the marks file is kept while a kept copy is", "kept" in marks)
    }

    @Test
    fun `who writes the phrase store - every writer runs only with a saved wallet or from a screen the gate does not show`() {
        val naming =
            DestroySources
                .shipped(setOf("kt", "java"))
                .filter { "EncryptedPreferenceProvider" in SourceScan.stripComments(it.readText()) }
                .map { DestroySources.relative(it) }
                .toSet()
        assertEquals("a file that names EncryptedPreferenceProvider is not in the writers table", WRITERS.keys, naming)
        for ((provider, writes) in PROVIDER_WRITES) {
            val callers =
                DestroySources
                    .shipped(setOf("kt", "java"))
                    .filter { file -> writes.any { Regex(it).containsMatchIn(SourceScan.stripComments(file.readText())) } }
                    .map { DestroySources.relative(it) }
                    .filterNot { it == provider }
                    .toSet()
            val traced = CALLERS.getValue(provider).keys
            assertEquals("a caller of a write function of $provider that is not traced", traced, callers)
        }
    }

    // ------------------------------------------------------------------ ResetWipe, by name

    private fun keptEntries(): List<Triple<String, String, String>> {
        val clazz =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.util.ResetWipe") }.getOrNull()
                ?: throw AssertionError("the object ResetWipe does not exist")
        val list = clazz.getMethod("getKEPT_WITHOUT_KEEP").invoke(clazz.getField("INSTANCE").get(null)) as List<*>
        return list.map { entry ->
            fun read(name: String) = entry!!.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(entry) as String
            Triple(read("path"), read("reason"), read("told"))
        }
    }

    private fun keptSet(): Map<String, String> = keptEntries().associate { it.first to it.third }

    private companion object {
        const val Z = "ui-lib/src/main/java/co/electriccoin/zcash"
        const val U = "$Z/ui"
        const val PREFERENCE_IMPL = "preference-impl-android-lib/src/main/java/co/electriccoin/zcash/preference"
        const val SPACKLE = "spackle-android-lib/src/main/kotlin/co/electriccoin/zcash/spackle"
        const val P = "$U/common/provider"

        const val WALLET = "wallet: deleted with every keep value"
        const val DATA = "data: deleted without keep"
        const val KEPT = "kept: "
        const val NO_FILE = "none: a provider declaration, nothing on the phone"
        const val PROCESS = "none: another process of the app, ended by the pass (Destroy's endOtherProcesses)"
        const val CRASH_REPORTS = "none: the crash reports in the external folder stay (R22, told: crash reports)"
        const val CLIPBOARD = "none: the clipboard is never cleared by a Reset"
        const val NOTICES = "none: notifications are cancelled by the pass; channels stay"
        const val JOBS = "none: background work is stopped by the pass; the job table is kept"
        const val KEYS = "none: the phone-security keys are never deleted by a Reset (R23)"
        const val SAVED = "none: what the user saved into the phone's shared storage is the user's"
        const val DESTROY = "none: Destroy All's own wipe"

        fun kept(path: String) = KEPT + path

        /** Table 2: every shipped file that names EncryptedPreferenceProvider, and why. */
        val WRITERS =
            mapOf(
                "preference-impl-android-lib/src/main/java/co/electriccoin/zcash/preference/EncryptedPreferenceProvider.kt" to "the class",
                "$Z/di/CoreModule.kt" to "builds the store (openPhraseStoreGuarded)",
                "$U/screen/deletewallet/ResetZashiUseCase.kt" to "a clearer: clearPreferences()",
                "$U/screen/chat/util/DestroyManager.kt" to "a clearer: clearPreferences()",
                "$P/AddressBookKeyStorageProvider.kt" to "provider",
                "$P/EphemeralKeySetProvider.kt" to "provider",
                "$P/IsTorEnabledStorageProvider.kt" to "provider",
                "$P/MetadataKeyStorageProvider.kt" to "provider",
                "$P/PersistableWalletProvider.kt" to "provider",
                "$P/RestoreTimestampStorageProvider.kt" to "provider",
                "$P/SelectedAccountUUIDProvider.kt" to "provider",
                "$P/ShieldFundsInfoProvider.kt" to "provider",
                "$P/WalletBackupConsentStorageProvider.kt" to "provider",
                "$P/WalletBackupFlagStorageProvider.kt" to "provider",
                "$P/WalletBackupRemindMeCountStorageProvider.kt" to "provider",
                "$P/WalletBackupRemindMeTimestampStorageProvider.kt" to "provider",
            )

        /** Each provider and the regexes of its write calls (the variable names its callers use). */
        val PROVIDER_WRITES =
            mapOf(
                "$P/AddressBookKeyStorageProvider.kt" to listOf("""\.storeAddressBookKey\("""),
                "$P/EphemeralKeySetProvider.kt" to listOf("""ephemeralAddressStorageProvider\.(store|remove)\("""),
                "$P/IsTorEnabledStorageProvider.kt" to listOf("""isTorEnabledStorageProvider\.(store|clear)\("""),
                "$P/MetadataKeyStorageProvider.kt" to listOf("""metadataKeyStorageProvider\.store\("""),
                "$P/PersistableWalletProvider.kt" to listOf("""persistableWalletProvider\.store\("""),
                "$P/RestoreTimestampStorageProvider.kt" to listOf("""restoreTimestampStorageProvider\.(store|clear)\("""),
                "$P/SelectedAccountUUIDProvider.kt" to listOf("""selectedAccountUUIDProvider\.setUUID\("""),
                "$P/ShieldFundsInfoProvider.kt" to listOf("""shieldFundsInfoProvider\.(flip|store|clear)\("""),
                "$P/WalletBackupConsentStorageProvider.kt" to listOf("""walletBackupConsentStorageProvider\.(store|clear)\("""),
                "$P/WalletBackupFlagStorageProvider.kt" to listOf("""walletBackupFlagStorageProvider\.(store|clear)\("""),
                "$P/WalletBackupRemindMeCountStorageProvider.kt" to listOf("""walletBackupRemindMeCountStorageProvider\.(store|clear)\("""),
                "$P/WalletBackupRemindMeTimestampStorageProvider.kt" to
                    listOf("""walletBackupRemindMeTimestampStorageProvider\.(store|clear)\("""),
            )

        private const val SAVED_WALLET = "saved wallet only"
        private const val GATED = "a screen the start-up gate does not show while a Reset is not finished"

        /** The callers of each provider's write functions, traced at RED, and why each cannot write beside no wallet. */
        val CALLERS: Map<String, Map<String, String>> =
            mapOf(
                "$P/AddressBookKeyStorageProvider.kt" to
                    mapOf("$U/common/repository/AddressBookRepository.kt" to "$SAVED_WALLET: requirePersistableWallet() first"),
                "$P/EphemeralKeySetProvider.kt" to
                    mapOf("$U/common/repository/EphemeralAddressRepository.kt" to "$SAVED_WALLET: getSelectedAccount() first"),
                "$P/IsTorEnabledStorageProvider.kt" to
                    mapOf(
                        "$U/common/usecase/RestoreWalletUseCase.kt" to
                            "$GATED: restore onboarding, which writes the Tor choice before the wallet is saved",
                        "$U/common/usecase/OptInExchangeRateAndTorUseCase.kt" to "$GATED: Tor settings, the Tor opt-in, sync errors",
                        "$U/common/tex/SdkTexWallet.kt" to "$SAVED_WALLET: a TEX check of the wallet's transactions",
                    ),
                "$P/MetadataKeyStorageProvider.kt" to
                    mapOf("$U/common/repository/MetadataRepository.kt" to "$SAVED_WALLET: requirePersistableWallet() first"),
                "$P/PersistableWalletProvider.kt" to
                    mapOf("$U/common/repository/WalletRepository.kt" to "the save of the wallet itself (a phrase store with a wallet)"),
                "$P/RestoreTimestampStorageProvider.kt" to
                    mapOf("$U/common/datasource/RestoreTimestampDataSource.kt" to "$SAVED_WALLET: after persistWalletInternal"),
                "$P/SelectedAccountUUIDProvider.kt" to
                    mapOf("$U/common/datasource/AccountDataSource.kt" to "$SAVED_WALLET: selectAccount needs an SDK account"),
                "$P/ShieldFundsInfoProvider.kt" to mapOf("$U/screen/home/shieldfunds/ShieldFundsInfoVM.kt" to "$GATED: Shield Funds info"),
                "$P/WalletBackupConsentStorageProvider.kt" to
                    mapOf("$U/common/usecase/RemindWalletBackupLaterUseCase.kt" to "$GATED: the backup card"),
                "$P/WalletBackupFlagStorageProvider.kt" to
                    mapOf(
                        "$U/common/usecase/OnUserSavedWalletBackupUseCase.kt" to "$GATED: the backup screen",
                        "$U/common/repository/WalletRepository.kt" to "$SAVED_WALLET: after persistWalletInternal in restoreWallet",
                    ),
                "$P/WalletBackupRemindMeCountStorageProvider.kt" to
                    mapOf("$U/common/usecase/RemindWalletBackupLaterUseCase.kt" to "$GATED: the backup screen and card"),
                "$P/WalletBackupRemindMeTimestampStorageProvider.kt" to
                    mapOf("$U/common/usecase/RemindWalletBackupLaterUseCase.kt" to "$GATED: the backup screen and card"),
            )
    }
}
