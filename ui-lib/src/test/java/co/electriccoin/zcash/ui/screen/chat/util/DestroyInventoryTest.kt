package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * ZC-692-W (register ZC-692, high; owner rule W-41, W-57): the inventory behind Destroy All. It fails when something
 * new that stores, keeps or sends is added without a decision about Destroy.
 *
 * A source scan of every source set that ships (each module's `src/<set>` except `test` and `androidTest`) for the
 * calls that put something on the phone or keep something alive: preference and database files, folders, keys,
 * shared storage, notifications, the clipboard, alarms and jobs, other processes, providers. Two tables:
 *
 *  1. file, call, how many: the line of the wipe that covers it ([KEYS], [PROCESS], [OWN], [EXTERNAL], [NOTICES],
 *     [CLIPBOARD], [JOBS], each a constant of the product object `DestroyWipe`), or "stays: <why>" for what is not
 *     ZChat's to delete (the user's own saves in the phone's shared storage).
 *  2. every `https://` and `wss://` host the code names: the row of the Destroy spec's table "what leaves the phone"
 *     (section 3.2) that says who keeps what there, or why nothing of the user is kept there.
 *
 * A new file, another count or a new host is not a failure of the product: it is the question "what does Destroy
 * All do about this?", answered by adding the row (and, when no wipe line covers it, by changing the wipe first).
 * The wipe deletes files by listing, so a new store inside the app's own folders needs a row and nothing else; a
 * new place outside them, a new key store, a new process or a new server needs a decision.
 */
class DestroyInventoryTest {
    // DESTROY-ADV-W (ZC-722): the column "kept by": what may keep the row's thing through a Destroy All, NEVER or
    // CONTACTS (the "keep names" choice). Exactly the kept list's file and its key say CONTACTS.
    private data class Row(
        val file: String,
        val call: String,
        val count: Int,
        val line: String,
        val keptBy: String = NEVER
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

    /** Table 1. The text of each file is searched as it is, comments included: a count is a count of the text. */
    private val table =
        listOf(
            Row("app/src/main/AndroidManifest.xml", "<provider", 1, OWN),
            Row("app/src/zcashmainnetFossDebug/AndroidManifest.xml", "<provider", 1, OWN),
            Row("app/src/zcashmainnetFossRelease/AndroidManifest.xml", "<provider", 1, OWN),
            Row("app/src/zcashmainnetStoreDebug/AndroidManifest.xml", "<provider", 1, OWN),
            Row("app/src/zcashtestnetFossDebug/AndroidManifest.xml", "<provider", 1, OWN),
            Row("app/src/zcashtestnetFossRelease/AndroidManifest.xml", "<provider", 1, OWN),
            Row("app/src/zcashtestnetStoreDebug/AndroidManifest.xml", "<provider", 1, OWN),
            Row("app/src/zcashtestnetStoreRelease/AndroidManifest.xml", "<provider", 1, OWN),
            Row("crash-android-lib/src/main/AndroidManifest.xml", "<provider", 1, PROCESS),
            Row("crash-android-lib/src/main/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row(
                "crash-android-lib/src/main/kotlin/co/electriccoin/zcash/crash/android/AndroidExceptionPath.kt",
                "getExternalFilesDir",
                2,
                EXTERNAL
            ),
            Row("crash-android-lib/src/zcashmainnetStoreDebug/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashmainnetStoreDebug/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row("crash-android-lib/src/zcashmainnetStoreRelease/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashmainnetStoreRelease/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreDebug/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreDebug/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreRelease/AndroidManifest.xml", "<provider", 2, PROCESS),
            Row("crash-android-lib/src/zcashtestnetStoreRelease/AndroidManifest.xml", "android:process=", 2, PROCESS),
            Row(
                "preference-impl-android-lib/src/main/java/co/electriccoin/zcash/preference/AndroidPreferenceProvider.kt",
                "EncryptedSharedPreferences.create(",
                1,
                OWN
            ),
            Row(
                "preference-impl-android-lib/src/main/java/co/electriccoin/zcash/preference/AndroidPreferenceProvider.kt",
                "getSharedPreferences(",
                1,
                OWN
            ),
            Row("spackle-android-lib/src/main/AndroidManifest.xml", "<provider", 1, PROCESS),
            Row(
                "spackle-android-lib/src/main/kotlin/co/electriccoin/zcash/spackle/ClipboardManagerUtil.kt",
                "setPrimaryClip",
                2,
                CLIPBOARD
            ),
            Row("spackle-android-lib/src/main/kotlin/co/electriccoin/zcash/spackle/ContextExt.kt", "cacheDir", 2, OWN),
            Row(
                "spackle-android-lib/src/main/kotlin/co/electriccoin/zcash/spackle/ContextExt.kt",
                "getExternalFilesDir",
                2,
                EXTERNAL
            ),
            Row("ui-lib/src/debug/AndroidManifest.xml", "<provider", 1, OWN),
            Row("ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/FaultSeamsConfig.kt", "filesDir", 10, OWN),
            Row("ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/FaultSeamsLoader.kt", "filesDir", 3, OWN),
            // The file name in two parts: ReleaseHookAbsenceTest fails on any non-debug source that names the class.
            Row("ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/TestHooks" + "Receiver.kt", "filesDir", 6, OWN),
            // KI-25 pairs 2 and 3 (train T7a2): the debug hook that writes a group back as ZChat 2.12.4 kept it, into the
            // old group file "zchat_group_info" (ZchatPreferences.GROUP_INFO_PREFS_NAME), inside the app's own storage.
            // ZC-705: one more, the plain view the KEYSET_SCRATCH hook reads its scratch store and the count file through.
            Row(
                "ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/TestHooks" + "Receiver.kt",
                "getSharedPreferences(",
                2,
                OWN
            ),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/di/DataSourceModule.kt", "getSharedPreferences(", 1, OWN),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/global/StorageChecker.kt", "Environment.", 1, FREE_SPACE),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallNotificationController.kt",
                ".notify(",
                1,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallNotificationController.kt",
                "NotificationChannel(",
                4,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallNotificationController.kt",
                "PendingIntent.get",
                3,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/AddressBookStorageProvider.kt",
                "filesDir",
                3,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/AddressBookStorageProvider.kt",
                "noBackupFilesDir",
                1,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/MetadataStorageProvider.kt",
                "filesDir",
                3,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/tex/TexRecoveryNotifier.kt",
                ".notify(",
                1,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/tex/TexRecoveryNotifier.kt",
                "NotificationChannel(",
                2,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/tex/TexRecoveryNotifier.kt",
                "PendingIntent.get",
                1,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/tex/TexRecoveryStore.kt",
                "getSharedPreferences(",
                1,
                OWN
            ),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ExportTaxUseCase.kt", "cacheDir", 1, OWN),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ShareImageUseCase.kt",
                "cacheDir",
                2,
                OWN
            ),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/SharePCZTUseCase.kt", "filesDir", 1, OWN),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiHistory.kt", "cacheDir", 1, OWN),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiHistory.kt", "filesDir", 6, OWN),
            // ZC-718: the picture store reads the plain owner file at each call (one folder per wallet).
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiHistory.kt", "getSharedPreferences(", 1, OWN),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiPreferences.kt", "filesDir", 1, OWN),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTabView.kt", "Environment.", 1, SAVED),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTabView.kt", "MediaStore", 6, SAVED),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTabView.kt", "cacheDir", 4, OWN),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTabView.kt", "filesDir", 1, OWN),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTabView.kt", "setPrimaryClip", 1, CLIPBOARD),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/IdentityManager.kt",
                "getSharedPreferences(",
                1,
                OWN
            ),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt", "cacheDir", 2, OWN),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt",
                "setPrimaryClip",
                2,
                CLIPBOARD
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/AddressCacheImpl.kt",
                "getSharedPreferences(",
                1,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/AvatarStore.kt",
                "filesDir",
                3,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ChatWalletOwnershipStores.kt",
                "getSharedPreferences(",
                2,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ContactBookImpl.kt",
                "getSharedPreferences(",
                2,
                OWN
            ),
            // ZC-705: the library's create and the master-key call moved here, behind openEncryptedFile (one call with the
            // key's name, one with a master key; the master-key call once in the code and once in its comment), and
            // openEncryptedFile reads the file's plain view.
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt",
                "EncryptedSharedPreferences.create(",
                2,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt",
                "MasterKey.Builder(",
                2,
                KEYS
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt",
                "getSharedPreferences(",
                2,
                OWN
            ),
            // ZC-733-A: the holder of the contact book, the picture index and the AI store reads their plain view (their
            // library calls moved into openEncryptedFile). ZC-733-B: and the owner record, at a change of wallet.
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/LazyEncryptedStore.kt",
                "getSharedPreferences(",
                2,
                OWN
            ),
            // ZC-705: the recovery-phrase store's guarded open reads the file's plain view.
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/KeysetGuard.kt",
                "getSharedPreferences(",
                1,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt",
                "createEncryptedPrefs(",
                15,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt",
                "getSharedPreferences(",
                25,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/filesharing/AudioRecorder.kt",
                "cacheDir",
                1,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/filesharing/FileDownloadCache.kt",
                "cacheDir",
                7,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/share/PendingShareStore.kt",
                "cacheDir",
                3,
                OWN
            ),
            // ZC-868: the guard asks whether the shared key is there (a key W3 deletes by listing).
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/MasterKeyGuard.kt",
                "KeyStore.getInstance(",
                1,
                KEYS
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt",
                "KeyStore.getInstance(",
                1,
                KEYS
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt",
                "NotificationChannel(",
                1,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt",
                "createDeviceProtectedStorageContext",
                1,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt",
                "externalCacheDir",
                1,
                EXTERNAL
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt",
                "getExternalFilesDir",
                1,
                EXTERNAL
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt",
                "setPrimaryClip",
                1,
                CLIPBOARD
            ),
            // ZC-1014: the record of a Destroy that keeps names is sealed with the wallet's own Keystore key (W3 deletes it).
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyRecordSeal.kt", "KeyStore.getInstance(", 1, KEYS),
            // DESTROY-ADV-W (B10): the one-time job a kept Destroy enqueues so that ZCHAT starts once more.
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt", "WorkRequest", 2, JOBS),
            // DESTROY-ADV-N (ZC-722): the kept-names list, the file no_backup/zchat_kept_names and its own Keystore key
            // zchat_kept_names_key. The default wipe deletes both by listing (W5, W3).
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/KeptNames.kt",
                "KeyGenParameterSpec",
                2,
                KEYS,
                CONTACTS
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/KeptNames.kt",
                "KeyStore.getInstance(",
                1,
                KEYS,
                CONTACTS
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/KeptNames.kt",
                "noBackupFilesDir",
                1,
                OWN,
                CONTACTS
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt",
                "Environment.",
                3,
                SAVED
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt",
                "MediaStore",
                9,
                SAVED
            ),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt", "cacheDir", 6, OWN),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt",
                "cacheDir",
                11,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/settings/datasource/ThemePreferenceDataSource.kt",
                "getSharedPreferences(",
                1,
                OWN
            ),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/update/UpdateChecker.kt", "cacheDir", 2, OWN),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/update/UpdateChecker.kt",
                "externalCacheDir",
                1,
                EXTERNAL
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/update/UpdateChecker.kt",
                "getSharedPreferences(",
                2,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/viewingkeyexport/ViewingKeyExportVM.kt",
                "setPrimaryClip",
                1,
                CLIPBOARD
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/walletstartup/WalletDataDatabaseFiles.kt",
                "noBackupFilesDir",
                2,
                OWN
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt",
                ".notify(",
                4,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt",
                "NotificationChannel(",
                7,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt",
                "PendingIntent.get",
                5,
                NOTICES
            ),
            Row(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt",
                "getSharedPreferences(",
                1,
                OWN
            ),
            // ZC-090-R: the Reset's wake job (cancelled with all work by W7).
            Row("ui-lib/src/main/java/co/electriccoin/zcash/work/ResetWakeWorker.kt", "WorkRequest", 2, JOBS),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/work/SyncAlarmReceiver.kt", "AlarmManager", 1, JOBS),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/work/SyncAlarmScheduler.kt", "AlarmManager", 6, JOBS),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/work/SyncAlarmScheduler.kt", "PendingIntent.get", 1, JOBS),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/work/SyncWorker.kt", "WorkRequest", 5, JOBS),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/work/WorkIds.kt", "AlarmManager", 1, JOBS),
            Row("ui-lib/src/main/java/co/electriccoin/zcash/work/WorkIds.kt", "WorkRequest", 1, JOBS),
        )

    /** Table 2: host to the row of spec section 3.2, or why nothing of the user is kept there. */
    private val hosts =
        mapOf(
            "relay.zsend.xyz" to B3_RELAYS,
            "nos.lol" to B3_RELAYS,
            "nostr-pub.wellorder.net" to B3_RELAYS,
            "nostr.mom" to B3_RELAYS,
            "nostr.oxtr.dev" to B3_RELAYS,
            "offchain.pub" to B3_RELAYS,
            "relay.nos.social" to B3_RELAYS,
            "relay.primal.net" to B3_RELAYS,
            "blossom.band" to B4_FILE_HOSTS,
            "blossom.nostr.build" to B4_FILE_HOSTS,
            "blossom.primal.net" to B4_FILE_HOSTS,
            "blossom.yakihonne.com" to B4_FILE_HOSTS,
            "cdn.hzrd149.com" to B4_FILE_HOSTS,
            "files.sovbit.host" to B4_FILE_HOSTS,
            "nostr.download" to B4_FILE_HOSTS,
            "api.zsend.xyz" to B5_B8_ZCHAT_SERVER,
            "1click.chaindefuser.com" to B9_SWAP,
            "pro-api.coinmarketcap.com" to PRICE_REQUEST,
            "flexa.network" to PICTURE,
            "z.cash" to PICTURE,
            "zsend.xyz" to LINK,
            "keyst.one" to LINK,
            "faucet.zecpages.com" to LINK,
            "blurha.sh" to COMMENT,
            "developer.android.com" to COMMENT,
            "firebase.google.com" to COMMENT,
            "github.com" to COMMENT,
            "medium.com" to COMMENT,
            "source.android.com" to COMMENT,
            "npub1" to COMMENT,
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
                val row = table.firstOrNull { it.file == file && it.call == call }
                Row(file, call, count, row?.line ?: "NOT IN THE TABLE", row?.keptBy ?: NEVER)
            }

    @Test
    fun `every call that stores, keeps or shows something has its line of the wipe`() {
        val found = scan()
        val expected = table.sortedWith(compareBy({ it.file }, { it.call }))
        val actual = found.sortedWith(compareBy({ it.file }, { it.call }))
        val unknown = actual.filter { row -> expected.none { it.file == row.file && it.call == row.call } }
        val gone = expected.filter { row -> actual.none { it.file == row.file && it.call == row.call } }
        val changed =
            actual.mapNotNull { row ->
                expected
                    .firstOrNull { it.file == row.file && it.call == row.call && it.count != row.count }
                    ?.let { "${row.file}: ${row.call} ${it.count} -> ${row.count}" }
            }
        assertEquals("a file or a call that is not in the table (what does Destroy All do about it?)", emptyList<Row>(), unknown)
        assertEquals("a count differs from the table", emptyList<String>(), changed)
        assertEquals("the table names a file or a call the code no longer has", emptyList<Row>(), gone)
        assertEquals(expected, actual)
    }

    @Test
    fun `kept by - exactly the kept list's file and its key may be kept, and only by the keep-names choice`() {
        assertEquals("every row says never or contacts", emptyList<Row>(), table.filterNot { it.keptBy == NEVER || it.keptBy == CONTACTS })
        assertEquals(
            "the rows a Destroy with the keep-names choice may keep",
            listOf("KeyGenParameterSpec", "KeyStore.getInstance(", "noBackupFilesDir"),
            table.filter { it.keptBy == CONTACTS }.map { it.call }.sorted()
        )
        assertTrue(
            "only the kept list's own file says contacts",
            table.filter { it.keptBy == CONTACTS }.all { it.file.endsWith("/screen/chat/util/KeptNames.kt") }
        )
        val spared = FakePhone().use { phone -> phone.wipeField("KEPT_BY_CONTACTS") as List<*> }
        assertEquals(
            "what the wipe and the start pass spare with keep=contacts: the list's file and its key, nothing else",
            listOf("no_backup/zchat_kept_names", "zchat_kept_names_key"),
            spared
        )
    }

    @Test
    fun `every wipe line the table names is a constant of DestroyWipe`() {
        val constants =
            runCatching { Class.forName(FakePhone.WIPE_CLASS) }
                .getOrNull()
                ?.declaredFields
                .orEmpty()
                .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type == String::class.java }
                .filter { it.name.startsWith("LINE_") }
                .map { field -> field.apply { isAccessible = true }.get(null) as String }
                .toSet()
        val named = table.map { it.line }.filterNot { it.startsWith("stays: ") }.toSet()
        assertEquals(
            "the wipe lines of the table and the LINE_ constants of DestroyWipe",
            setOf(KEYS, PROCESS, OWN, EXTERNAL, NOTICES, CLIPBOARD, JOBS),
            named
        )
        assertEquals("every wipe line the table names is a constant of DestroyWipe, and the other way round", named, constants)
    }

    @Test
    fun `the notification channels are covered by the wipe and may not stay`() {
        val channelRows = table.filter { it.call == "NotificationChannel(" }
        assertTrue("fixture: the table has the files that make channels", channelRows.size >= 3)
        assertEquals(
            "every file that makes or deletes a channel is covered by W7 notifications, none stays",
            emptyList<Row>(),
            channelRows.filterNot { it.line == NOTICES }
        )
        val mayBeLeft = FakePhone().use { phone -> phone.wipeField("MAY_BE_LEFT") as List<*> }
        assertEquals("what may be left after the wipe holds no channel", 4, mayBeLeft.size)
        assertFalse("what may be left after the wipe holds no channel", mayBeLeft.any { "channel" in it.toString() })
    }

    @Test
    fun `every host the code names has its row of what stays there`() {
        val found =
            DestroySources
                .shipped(setOf("kt", "java", "xml"))
                .flatMap { file ->
                    Regex("""(?:https|wss)://([A-Za-z0-9.\-]+)""")
                        .findAll(file.readText())
                        .map { it.groupValues[1].trimEnd('.') to DestroySources.relative(file) }
                        .toList()
                }
        val unknown = found.filterNot { it.first in hosts }.distinct()
        assertEquals("a host that is not in the table (who keeps what there after a Destroy?)", emptyList<Pair<String, String>>(), unknown)
        assertEquals("the table names a host the code no longer has", emptySet<String>(), hosts.keys - found.map { it.first }.toSet())
    }

    @Test
    fun `the periodic sync job carries no input data`() {
        val worker = DestroySources.code("ui-lib/src/main/java/co/electriccoin/zcash/work/SyncWorker.kt")
        val request = DestroySources.block(worker, "fun newWorkRequest()")
        assertTrue("fixture: SyncWorker.newWorkRequest", request != null)
        assertFalse("the job table would then hold data of the user after a Destroy", "setInputData" in request!!)
        assertFalse("SyncWorker must not read input data", "inputData" in worker)
        assertEquals(
            "no shipped source gives a job input data",
            emptyList<String>(),
            DestroySources
                .shipped(setOf("kt", "java"))
                .filter { "setInputData(" in SourceScan.stripComments(it.readText()) }
                .map { DestroySources.relative(it) }
        )
    }

    @Test
    fun `the phrase store's file name is the one the settings provider opens`() {
        val provider =
            DestroySources.code(
                "preference-impl-android-lib/src/main/java/co/electriccoin/zcash/preference/EncryptedPreferenceProvider.kt"
            )
        assertTrue(
            "EncryptedPreferenceProvider still opens co.electriccoin.zcash.encrypted",
            "ENCRYPTED_PREFERENCES_FILENAME = \"co.electriccoin.zcash.encrypted\"" in provider
        )
        FakePhone().use { phone ->
            assertEquals("co.electriccoin.zcash.encrypted.xml", phone.wipeField("PHRASE_STORE_FILE"))
            assertEquals("co.electriccoin.zcash.encrypted.xml.bak", phone.wipeField("PHRASE_STORE_BACKUP_FILE"))
        }
        val opener =
            DestroySources.code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/EncryptedStoreOpener.kt")
        for (entry in listOf(FakePhone.KEY_KEYSET, FakePhone.VALUE_KEYSET)) {
            assertTrue("the encryption library's entry $entry is still what the app's own opener names", entry in opener)
            assertTrue("DestroyWipe.kt names the entry $entry", entry in DestroySources.code(DestroySources.WIPE))
        }
    }

    @Test
    fun `DestroyWipe opens no store and no shipped source replaces one of its calls`() {
        val wipe = DestroySources.code(DestroySources.WIPE)
        assertTrue("DestroyWipe.kt exists", wipe.isNotEmpty())
        for (name in listOf("getSharedPreferences", "EncryptedSharedPreferences", "MasterKey", "PreferenceProvider", "koin")) {
            assertFalse("DestroyWipe.kt must not name $name: it reads raw files only", name in wipe)
        }
        for (seam in FakePhone.SEAMS) {
            assertTrue(
                "the replaceable call $seam is a private field of DestroyWipe",
                Regex("""private var $seam\b""").containsMatchIn(wipe)
            )
        }
    }

    private companion object {
        const val NEVER = "never"
        const val CONTACTS = "contacts"
        const val KEYS = "W3 keys"
        const val PROCESS = "W4 process"
        const val OWN = "W5 own storage"
        const val EXTERNAL = "W5 external"
        const val NOTICES = "W7 notifications"
        const val CLIPBOARD = "W7 clipboard"
        const val JOBS = "W7 background work"
        const val SAVED = "stays: what the user saved into the phone's shared storage (gallery, Music, Downloads) is the user's"
        const val FREE_SPACE = "stays: reads the free space of the phone, stores nothing"

        const val B3_RELAYS = "B3: relays keep wrapped messages for as long as each relay keeps them"
        const val B4_FILE_HOSTS = "B4: public file hosts keep encrypted photos, files and voice notes"
        const val B5_B8_ZCHAT_SERVER = "B5, B8: ZChat's server keeps the AI account; it sees call and update requests"
        const val B9_SWAP = "B9: the swap service keeps each swap"
        const val PRICE_REQUEST = "none of 3.2: a price request with ZChat's key; the service sees the IP, nothing of the wallet"
        const val PICTURE = "none of 3.2: the address of a logo picture handed to the Flexa library"
        const val LINK = "none of 3.2: a link the user opens or shares; the app sends nothing there"
        const val COMMENT = "none of 3.2: named in a comment or as an example; the app sends nothing there"
    }
}
