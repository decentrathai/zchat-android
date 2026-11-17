package co.electriccoin.zcash.ui.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.nostr.NostrRelayPool
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetGuard
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenLaunch
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.appStoreOpenEnv
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedFile
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * QA test-infrastructure (DEBUG BUILDS ONLY — this class lives in the `debug` source set, so the
 * bytecode does not exist in release variants, and its manifest registration lives only in
 * `ui-lib/src/debug/AndroidManifest.xml`).
 *
 * Purpose: deterministically reproduce the ZC-101 hazard on-device. Firing
 * [ACTION_INJECT_ACK_PROPOSAL] drives the REAL dust auto-ack proposal-CREATION path — the exact
 * read-receipt/KEX-ack chokepoint ([CreateChunkedMessageProposalUseCase] -> ZIP-321 ->
 * `ZashiProposalRepository.createZip321Proposal`) — so the shared proposal cell receives a genuine
 * production overwrite while a user send is parked on the biometric prompt. PaymentDiag then emits
 * `proposal_cell_overwritten`, and the runner asserts the submitted pid still matches the displayed
 * pid.
 *
 * ZASHI LANE ONLY — KEYSTONE IS REFUSED: on a [KeystoneAccount] the injection is NOT inert. The
 * create path ([CreateChunkedMessageProposalUseCase] -> `createMultiOutputProposal`) routes to
 * `keystoneProposalRepository.createZip321Proposal` + `createPCZTFromProposal()`, which MUTATES the
 * Keystone PCZT singleton and leaves a proposal that could subsequently be hardware-signed. So when
 * the selected account is Keystone the hook fails closed BEFORE touching any create path, logging
 * `inject_ack_proposal {ok=false, reason=keystone_unsupported}`. The harness certifies the Zashi
 * lane only.
 *
 * CREATE-ONLY, NEVER SUBMITS: on Zashi the use case is invoked with `directSubmit = false` +
 * `skipNavigation = true`, which runs the proposal-creation step and then returns without any
 * settlement call — no biometric, no broadcast, no ZEC spent. The message dust outputs are addressed
 * to the wallet's OWN unified address; note, however, that `buildZip321Uri` ALWAYS appends a single
 * minimal 1000-zatoshi platform-fee output (see `CreateChunkedMessageProposalUseCase`), so a
 * hypothetical later submission of this cell would not be strictly zero-third-party — it would pay
 * that 1000-zat platform fee. The real safety guarantee is therefore CREATE-ONLY (the proposal is
 * never signed, submitted, or broadcast), not "cannot pay a third party".
 *
 * Triple gate:
 *  1. debug-only manifest registration + debug-only source set (release-absent, pinned by
 *     ReleaseHookAbsenceTest);
 *  2. runtime [BuildConfig.DEBUG] guard (first statement of [onReceive]);
 *  3. `android:permission="android.permission.DUMP"` on the receiver — a signature-level platform
 *     permission (protectionLevel signature|privileged|development) that the adb shell identity
 *     holds but third-party apps cannot obtain. NOTE: deliberately NOT a custom signature
 *     permission — the shell uid can never hold an app-declared signature permission, which would
 *     make the documented `adb shell am broadcast` trigger impossible on non-rooted devices.
 *
 * Trigger (COMPONENT-TARGETED — implicit `-a`-only broadcasts do not reach manifest-declared
 * receivers on targetSdk 35, so the component must be named explicitly):
 *   adb shell am broadcast -n <debug-appId>/co.electriccoin.zcash.ui.debug.TestHooksReceiver \
 *       -a xyz.zsend.zchat.debug.INJECT_ACK_PROPOSAL
 * (debug-appId e.g. `xyz.zsend.zchat.testnet.foss.debug` for testnet FOSS debug.)
 *
 * Result line (the runner keys on this): `inject_ack_proposal {ok=true}` on success,
 * `inject_ack_proposal {ok=false, reason=<token>}` otherwise (`keystone_unsupported`, or the failing
 * exception's simple class name). Any exception detail is redacted (a raw throwable can embed the
 * wallet's unified address) and logged separately, never inside the machine-keyed result line.
 *
 * FAULT SEAMS (fix-all 2.13.0, F0). Same receiver, same triple gate, four more actions:
 *  - `xyz.zsend.zchat.debug.SET_FAULT` `--es seam <FaultSeam name> [--el value <n>]` arms one seam (value 1 when
 *    left out; milliseconds for GROUP_SEND_DELAY_MS, the cap for SEEN_*_CAP, the relay's position in the pool's list
 *    for BLOCK_ONE_RELAY: 0 relay.zsend.xyz, 1 relay.primal.net, 2 nos.lol; a bit mask for RELAY_UNREACHABLE: bit 0
 *    relay.zsend.xyz, then RelayCatalog.PUBLIC in order; seconds for RELAY_HEALTH_CONFIRM_SEC) and saves it, so the start-up
 *    [FaultSeamsLoader] arms it again after a restart. Result: `set_fault {ok=true, armed=...}`.
 *  - `xyz.zsend.zchat.debug.CLEAR_FAULTS` `[--es seam <name>]` disarms one seam, or all of them, and saves that.
 *  - `xyz.zsend.zchat.debug.IMPORT_KEYSTONE_VIEWONLY` `[--es file <name>] [--ez select false]` (K0): imports a
 *    unified full viewing key as a Keystone account (key source "keystone") through
 *    [AccountDataSource.importKeystoneAccount] — not through the connect flow, which K0-1 closes — and selects it.
 *    The key is read from `<filesDir>/qa/<file>` (default `keystone_ufvk.txt`; line 1 the key, optional line 2 a
 *    64-hex seed fingerprint, optional line 3 the account index), put there with `run-as`, and the file is deleted
 *    as soon as it is read. The key is never logged. With no Keystone device nothing can be signed with it.
 *  - `xyz.zsend.zchat.debug.FORGET_GROUP_CREATOR_KEY` `--es group_id <id> [--es file <name>]` (KI-25): makes a
 *    group's admin "drift" the way #205/#214 did — its captured admin key is removed and its creator address is
 *    replaced by an alias with no chat key on this phone, read from `<filesDir>/qa/<file>` (default
 *    `drift_alias.txt`, deleted once read, never logged). The alias must have no stored key, or nothing would
 *    drift. This rewrites that group's stored info for good: use a test group. KI-25 pair 2 (ZC-572) adds
 *    `--es mode <mode>`: `orphan` (no file) keeps the creator address and stores as the creator key a fresh key no
 *    chat holds (the group reads ORPHANED, the chat at the creator address is the candidate); `confirmed` (the alias
 *    file with a second line, the address of the admin's chat on this phone) makes the alias the creator address,
 *    stores the key held under the second line's address as the creator key and clears the group's "announce
 *    applied" flag (the state of a confirmed drifted group). KI-25 pair 3 (ZC-598): `legacy` (no file) writes the
 *    group's record into 2.12.4's old plain file in the form 2.12.4 wrote (no creator key) and takes it out of the
 *    encrypted store through the store's own list of wallet stores, under the group lock and the wallet-scope lock;
 *    the group is gone from the list until the app is stopped and started, and the store's move then brings it back
 *    without a look. No mode: the drift above.
 *  - `xyz.zsend.zchat.debug.RESET_RELAY_SET` (RELAY-HEALTH): the relay pool goes back to its default relays, its
 *    cooldowns and swap history are cleared and the saved relay set is removed. Result: `reset_relay_set {ok=true}`,
 *    or `{ok=false, reason=no_relay_pool}` while the inbox is not running. Phone checks run it between runs.
 *  - `xyz.zsend.zchat.debug.KEYSET_SCRATCH` `--es mode <new|data|open|limit|remove> [--ei value <n>] [--ei flag <0|1>]`
 *    (ZC-705, phone checks K2 to K4): works on ONE fixed store, `zchat_qa_scratch_encrypted`, and takes no store name.
 *    `new`: a background open of it through the opener whose open turns the key entries the library just wrote into
 *    unencrypted ones the first <value> times ([KeysetScratch]); logs ok, discarded and the final two characters of
 *    each key entry. `data`: makes it, writes three fixed entries, turns its key entries into unencrypted ones.
 *    `open`: a background open through the opener; logs the entries' first two characters before and after, the
 *    number of data entries, whether the file's entries are unchanged and the guard's count. `limit` (flag, default
 *    1): an open as a start the user sees, with keepPlainAfterLimit = (flag == 1), whose every try comes out
 *    unprotected; the only mode that writes the scratch store's line into the file of failed-start counts. It runs on
 *    a launch of its own, so the app's own stores are never kept by it. `remove`: deletes the scratch file and its
 *    count line. Two characters per entry and counts only in the log.
 * The seams are read in production code only through [FaultSeams], whose every function returns at once in a
 * release build; DebugFaultSeamsReleaseAbsenceTest pins both halves.
 */
class TestHooksReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.action) {
            ACTION_INJECT_ACK_PROPOSAL -> runAsync("inject_ack_proposal") { injectAckProposal() }
            ACTION_SET_FAULT -> report("set_fault", setFault(context, intent))
            ACTION_CLEAR_FAULTS -> report("clear_faults", clearFaults(context, intent))
            ACTION_IMPORT_KEYSTONE_VIEWONLY ->
                runAsync("import_keystone_viewonly") { importKeystoneViewOnly(context, intent) }
            ACTION_FORGET_GROUP_CREATOR_KEY ->
                runAsync("forget_group_creator_key") { forgetGroupCreatorKey(context, intent) }
            ACTION_RESET_RELAY_SET -> runAsync("reset_relay_set") { resetRelaySet() }
            ACTION_KEYSET_SCRATCH -> runAsync("keyset_scratch") { keysetScratch(context, intent) }
        }
    }

    /** Runs [block] off the main thread, keeping the process alive, and logs one machine-keyed result line. */
    private fun runAsync(
        event: String,
        block: suspend () -> InjectOutcome,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                report(event, block())
            } catch (t: Throwable) {
                // Never log the raw throwable: its message can embed the wallet UA. Emit a
                // machine-keyed result line carrying only the exception's simple class name, and a
                // separate redacted detail line for humans.
                Log.w(TAG, "$event {ok=false, reason=${t.javaClass.simpleName}}")
                Log.w(TAG, "${event}_detail class=${t.javaClass.simpleName} msg=${redactThrowableMessage(t)}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun report(
        event: String,
        outcome: InjectOutcome,
    ) {
        when (outcome) {
            InjectOutcome.Injected -> Log.i(TAG, "$event {ok=true}")
            is InjectOutcome.Done -> Log.i(TAG, "$event {ok=true, ${outcome.fields}}")
            is InjectOutcome.Skipped -> Log.w(TAG, "$event {ok=false, reason=${outcome.reason}}")
        }
    }

    private fun setFault(
        context: Context,
        intent: Intent,
    ): InjectOutcome {
        val seam =
            FaultSeam.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SEAM) }
                ?: return InjectOutcome.Skipped("unknown_seam")
        // `--el value n`, `--ei value n` or `--es value n`; left out means 1.
        val value =
            if (!intent.hasExtra(EXTRA_VALUE)) {
                1L
            } else {
                intent.getStringExtra(EXTRA_VALUE)?.trim()?.toLongOrNull()
                    ?: intent.getLongExtra(EXTRA_VALUE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
                    ?: intent.getIntExtra(EXTRA_VALUE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.toLong()
                    ?: return InjectOutcome.Skipped("bad_value")
            }
        FaultSeams.arm(seam, value)
        FaultSeamsConfig.write(context.filesDir, FaultSeams.snapshot())
        return InjectOutcome.Done("armed=${armedList()}")
    }

    private fun clearFaults(
        context: Context,
        intent: Intent,
    ): InjectOutcome {
        val name = intent.getStringExtra(EXTRA_SEAM)
        if (name == null) {
            FaultSeams.disarmAll()
        } else {
            val seam = FaultSeam.entries.firstOrNull { it.name == name } ?: return InjectOutcome.Skipped("unknown_seam")
            FaultSeams.disarm(seam)
        }
        FaultSeamsConfig.write(context.filesDir, FaultSeams.snapshot())
        return InjectOutcome.Done("armed=${armedList()}")
    }

    private fun armedList(): String =
        FaultSeams
            .snapshot()
            .entries
            .sortedBy { it.key.ordinal }
            .joinToString(separator = ",", prefix = "[", postfix = "]") { "${it.key.name}=${it.value}" }

    /**
     * Reads a one-shot input file from `<filesDir>/qa/` and deletes it at once, whatever happens next: a viewing
     * key or an admin's address alias must not stay on disk.
     */
    private fun takeInputLines(
        context: Context,
        intent: Intent,
        defaultName: String,
    ): List<String>? {
        val file = FaultSeamsConfig.inputFile(context.filesDir, intent.getStringExtra(EXTRA_FILE) ?: defaultName)
        if (file == null || !file.isFile) return null
        return try {
            file.readLines().map { it.trim() }
        } finally {
            file.delete()
        }
    }

    private suspend fun importKeystoneViewOnly(
        context: Context,
        intent: Intent,
    ): InjectOutcome {
        val lines = takeInputLines(context, intent, DEFAULT_UFVK_FILE) ?: return InjectOutcome.Skipped("no_input_file")
        val viewingKey = lines.getOrNull(0).orEmpty()
        if (!viewingKey.startsWith("uview")) return InjectOutcome.Skipped("not_a_unified_viewing_key")
        val fingerprint =
            lines.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: SYNTHETIC_SEED_FINGERPRINT
        if (!HEX_32_BYTES.matches(fingerprint)) return InjectOutcome.Skipped("bad_seed_fingerprint")
        val index = lines.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L

        val accountDataSource = koin().get<AccountDataSource>()
        val account =
            accountDataSource.importKeystoneAccount(
                ufvk = viewingKey,
                seedFingerprint = fingerprint,
                index = index,
            )
        val select = intent.getBooleanExtra(EXTRA_SELECT, true)
        if (select) accountDataSource.selectAccount(account)
        return InjectOutcome.Done("selected=$select")
    }

    private fun forgetGroupCreatorKey(
        context: Context,
        intent: Intent,
    ): InjectOutcome {
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)?.trim()
        if (groupId.isNullOrEmpty()) return InjectOutcome.Skipped("no_group_id")
        val mode = intent.getStringExtra(EXTRA_MODE)?.trim().orEmpty()
        if (mode == MODE_ORPHAN) return orphanGroupCreatorKey(groupId)
        if (mode == MODE_LEGACY) return legacyGroupRecord(context, groupId)
        if (mode.isNotEmpty() && mode != MODE_CONFIRMED) return InjectOutcome.Skipped("unknown_mode")
        val lines = takeInputLines(context, intent, DEFAULT_ALIAS_FILE) ?: return InjectOutcome.Skipped("no_input_file")
        val driftTo = lines.getOrNull(0).orEmpty()
        if (driftTo.isEmpty()) return InjectOutcome.Skipped("empty_alias")

        val store = koin().get<ZchatPreferences>()
        // The alias must have no chat key on this phone, directly or through the address book, or the Tier-1
        // snapshot would find the admin's key again and nothing would drift.
        if (store.getE2EPeerPublicKey(driftTo) != null ||
            store.getE2EPeerPublicKey(store.resolvePeerAddress(driftTo)) != null
        ) {
            return InjectOutcome.Skipped("alias_has_a_key")
        }
        return store.withGroupLock(groupId) {
            val info =
                store.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                    ?: return@withGroupLock InjectOutcome.Skipped("no_such_group")
            if (info.creatorAddress == driftTo) return@withGroupLock InjectOutcome.Skipped("alias_is_the_creator_address")
            if (mode == MODE_CONFIRMED) return@withGroupLock confirmedDrift(store, groupId, info, driftTo, lines.getOrNull(1))
            store.saveGroupInfo(
                groupId,
                ZMSGGroupProtocol.serializeGroupInfo(info.copy(creatorAddress = driftTo, creatorPublicKey = null)),
            )
            InjectOutcome.Done("group=${groupId.take(GROUP_ID_SHOWN)}, had_key=${info.creatorPublicKey != null}")
        }
    }

    /**
     * KI-25 pair 3, mode `legacy`: the record goes into 2.12.4's old plain file as 2.12.4 wrote it (the eight fields,
     * no creator key) and out of the encrypted group store, exported and imported again without it.
     */
    private fun legacyGroupRecord(
        context: Context,
        groupId: String,
    ): InjectOutcome {
        val store = koin().get<ZchatPreferences>()
        return store.withGroupLock(groupId) {
            synchronized(ZchatPreferences.walletScopeLock) {
                val info =
                    store.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                        ?: return@withGroupLock InjectOutcome.Skipped("no_such_group")
                val groupStore =
                    store.walletScopedStores().firstOrNull { it.storeId == GROUP_STORE_ID }
                        ?: return@withGroupLock InjectOutcome.Skipped("no_group_store")
                val as2124 =
                    ZMSGGroupProtocol.serializeGroupInfo(
                        info.copy(creatorPublicKey = null, removedByAdmin = false, announcerRebuilt = false),
                    )
                val written =
                    context
                        .getSharedPreferences(LEGACY_GROUP_FILE, Context.MODE_PRIVATE)
                        .edit()
                        .putString(groupId, as2124)
                        .commit()
                if (!written) return@withGroupLock InjectOutcome.Skipped("old_file_refused")
                groupStore.importEntries(groupStore.exportEntries() - groupId)
                InjectOutcome.Done("group=${groupId.take(GROUP_ID_SHOWN)}, mode=legacy, had_key=${info.creatorPublicKey != null}")
            }
        }
    }

    /** KI-25 pair 2, mode `orphan`: the stored creator key becomes a fresh key that no chat holds. */
    private fun orphanGroupCreatorKey(groupId: String): InjectOutcome {
        val store = koin().get<ZchatPreferences>()
        return store.withGroupLock(groupId) {
            val info =
                store.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                    ?: return@withGroupLock InjectOutcome.Skipped("no_such_group")
            val orphan = E2EEncryption.generateKeyPair().publicKey
            store.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(info.copy(creatorPublicKey = orphan)))
            InjectOutcome.Done("group=${groupId.take(GROUP_ID_SHOWN)}, mode=orphan, had_key=${info.creatorPublicKey != null}")
        }
    }

    /**
     * KI-25 pair 2, mode `confirmed`: the alias [driftTo] becomes the creator address, the key stored under
     * [adminChat] becomes the creator key, and the "announce applied" flag is cleared. Call under the group lock.
     */
    private fun confirmedDrift(
        store: ZchatPreferences,
        groupId: String,
        info: co.electriccoin.zcash.ui.screen.chat.model.GroupInfo,
        driftTo: String,
        adminChat: String?,
    ): InjectOutcome {
        if (adminChat.isNullOrEmpty()) return InjectOutcome.Skipped("no_admin_chat_line")
        val key = store.getE2EPeerPublicKey(adminChat) ?: return InjectOutcome.Skipped("admin_chat_has_no_key")
        store.saveGroupInfo(
            groupId,
            ZMSGGroupProtocol.serializeGroupInfo(info.copy(creatorAddress = driftTo, creatorPublicKey = key)),
        )
        val cleared = store.saveGroupAnnounceState(groupId, store.getGroupAnnounceState(groupId).copy(rosterAnnounced = false))
        return InjectOutcome.Done("group=${groupId.take(GROUP_ID_SHOWN)}, mode=confirmed, announce_cleared=$cleared")
    }

    private suspend fun resetRelaySet(): InjectOutcome {
        val pool = NostrRelayPool.healthPoolForDebug() ?: return InjectOutcome.Skipped("no_relay_pool")
        pool.resetRelaySetForDebug()?.join() ?: return InjectOutcome.Skipped("no_relay_health")
        return InjectOutcome.Done("relays=${pool.activeUrls().joinToString(",")}")
    }

    /** ZC-705: the KEYSET_SCRATCH action, on the one scratch store (see the class comment). */
    @Suppress("TooGenericExceptionCaught")
    private fun keysetScratch(
        context: Context,
        intent: Intent,
    ): InjectOutcome {
        val mode = intent.getStringExtra(EXTRA_MODE)?.trim().orEmpty()
        val plain: (String) -> android.content.SharedPreferences = { plainPrefs(context, it) }
        val final = { KeysetScratch.prefixes(context, SCRATCH_STORE) { c, n -> plainPrefs(c, n) } }
        return when (mode) {
            "new" -> {
                val times = intent.getIntExtra(EXTRA_VALUE, 1)
                var discarded = 0
                val ok =
                    runCatching {
                        openEncryptedStore(
                            name = SCRATCH_STORE,
                            open = {
                                val store = openEncryptedFile(context, SCRATCH_STORE)
                                if (discarded < times) {
                                    KeysetScratch.writeKeyEntriesInTheClear(context, SCRATCH_STORE)
                                    discarded++
                                }
                                store
                            },
                            plainPrefs = plain,
                            userVisible = false,
                            env = appStoreOpenEnv(context).apply { launch = StoreOpenLaunch() },
                        )
                    }.isSuccess
                InjectOutcome.Done("ok=$ok discarded=$discarded final=${final()}")
            }
            "data" -> {
                val store = openEncryptedFile(context, SCRATCH_STORE)
                val written =
                    store
                        .edit()
                        .putString("qa_one", "1")
                        .putString("qa_two", "2")
                        .putString("qa_three", "3")
                        .commit()
                KeysetScratch.writeKeyEntriesInTheClear(context, SCRATCH_STORE)
                InjectOutcome.Done("written=$written final=${final()}")
            }
            "open" -> {
                val before = final()
                val entriesBefore = plain(SCRATCH_STORE).all
                val env = appStoreOpenEnv(context).apply { launch = StoreOpenLaunch() }
                val ok =
                    runCatching {
                        openEncryptedStore(
                            name = SCRATCH_STORE,
                            open = { openEncryptedFile(context, SCRATCH_STORE) },
                            plainPrefs = plain,
                            userVisible = false,
                            env = env,
                        )
                    }.isSuccess
                val entriesAfter = plain(SCRATCH_STORE).all
                val data = entriesAfter.keys.count { !it.startsWith(KEYSET_ENTRY_PREFIX) }
                val counted = env.launch.plainKeysetCount(KeysetGuard.kindOf(SCRATCH_STORE))
                InjectOutcome.Done(
                    "ok=$ok before=$before after=${final()} entries=$data equal=${entriesBefore == entriesAfter} counted=$counted"
                )
            }
            "limit" -> {
                val keep = intent.getIntExtra(EXTRA_FLAG, 1) == 1
                val env =
                    appStoreOpenEnv(context, keepPlainAfterLimit = keep).apply { launch = StoreOpenLaunch() }
                val ok =
                    runCatching {
                        openEncryptedStore(
                            name = SCRATCH_STORE,
                            open = {
                                val store = openEncryptedFile(context, SCRATCH_STORE)
                                KeysetScratch.writeKeyEntriesInTheClear(context, SCRATCH_STORE)
                                store
                            },
                            plainPrefs = plain,
                            userVisible = true,
                            env = env,
                        )
                    }.isSuccess
                val count = plain(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME).getInt(SCRATCH_STORE, 0)
                val kept = if (ok && env.launch.keptAfterLimit()) 1 else 0
                InjectOutcome.Done("ok=$ok count=$count kept=$kept final=${final()}")
            }
            "remove" -> {
                val removed = context.deleteSharedPreferences(SCRATCH_STORE)
                val line = plain(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME).edit().remove(SCRATCH_STORE).commit()
                InjectOutcome.Done("removed=$removed count_line_removed=$line")
            }
            else -> InjectOutcome.Skipped("unknown_mode")
        }
    }

    /** The raw, plain view of one preferences file (ZC-705's scratch store and the file of failed-start counts). */
    private fun plainPrefs(
        context: Context,
        name: String,
    ): android.content.SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun koin() =
        checkNotNull(GlobalContext.getOrNull()) {
            "Koin not started — launch the app before firing the test hook"
        }

    private suspend fun injectAckProposal(): InjectOutcome {
        val koin = koin()
        val accountDataSource = koin.get<AccountDataSource>()

        val account = accountDataSource.getSelectedAccount()
        if (account is KeystoneAccount) {
            // FAIL CLOSED: on Keystone the create path is NOT inert (it mutates the Keystone PCZT
            // singleton and the proposal could be hardware-signed). Return before any create call.
            return InjectOutcome.Skipped("keystone_unsupported")
        }

        val createChunkedMessageProposal = koin.get<CreateChunkedMessageProposalUseCase>()

        // Self-addressed: always valid for the active network, and worthless to any third party
        // (the message dust outputs pay the wallet's own UA; see the class doc re: the appended
        // 1000-zat platform-fee output).
        val selfAddress = account.unified.address.address

        // A well-formed ZRCPT ack memo, exactly what the production read-receipt auto-send builds.
        val ackMemo = ZMSGProtocol.createReadReceipt(SYNTHETIC_TARGET_TXID, selfAddress)

        // The REAL production create path: rawMemo dust ZIP-321 with the platform-fee output —
        // identical to sendReadReceipt's VAULT branch EXCEPT directSubmit=false, so invoke()
        // performs the proposal-creation step (overwriting the shared cell) and then RETURNS.
        // Nothing is signed, submitted, or broadcast.
        createChunkedMessageProposal(
            destinationAddress = selfAddress,
            senderAddress = selfAddress,
            message = ackMemo,
            isFirstMessage = false,
            directSubmit = false,
            skipNavigation = true,
            rawMemo = true,
        )
        return InjectOutcome.Injected
    }

    /** Outcome of an injection attempt (drives the single structured result line in [onReceive]). */
    private sealed interface InjectOutcome {
        /** The Zashi create path ran and overwrote the shared cell. */
        object Injected : InjectOutcome

        /** The action ran; [fields] are extra `k=v` pairs for the result line (never a key or an address). */
        data class Done(
            val fields: String
        ) : InjectOutcome

        /** The create path was deliberately not run; [reason] is a bare machine-keyable token. */
        data class Skipped(val reason: String) : InjectOutcome
    }

    companion object {
        private const val TAG = "TestHooks"

        const val ACTION_INJECT_ACK_PROPOSAL = "xyz.zsend.zchat.debug.INJECT_ACK_PROPOSAL"
        const val ACTION_SET_FAULT = "xyz.zsend.zchat.debug.SET_FAULT"
        const val ACTION_CLEAR_FAULTS = "xyz.zsend.zchat.debug.CLEAR_FAULTS"
        const val ACTION_IMPORT_KEYSTONE_VIEWONLY = "xyz.zsend.zchat.debug.IMPORT_KEYSTONE_VIEWONLY"
        const val ACTION_FORGET_GROUP_CREATOR_KEY = "xyz.zsend.zchat.debug.FORGET_GROUP_CREATOR_KEY"
        const val ACTION_RESET_RELAY_SET = "xyz.zsend.zchat.debug.RESET_RELAY_SET"
        const val ACTION_KEYSET_SCRATCH = "xyz.zsend.zchat.debug.KEYSET_SCRATCH"

        /** ZC-705: the one store the KEYSET_SCRATCH action works on. It takes no other name. */
        private const val SCRATCH_STORE = "zchat_qa_scratch_encrypted"
        private const val KEYSET_ENTRY_PREFIX = "__androidx_security_crypto_encrypted_prefs_"
        private const val EXTRA_FLAG = "flag"

        private const val EXTRA_SEAM = "seam"
        private const val EXTRA_VALUE = "value"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_SELECT = "select"
        private const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_MODE = "mode"
        private const val MODE_ORPHAN = "orphan"
        private const val MODE_CONFIRMED = "confirmed"
        private const val MODE_LEGACY = "legacy"

        /** 2.12.4's plain group file and the encrypted group store's id in the store's list (ZchatPreferences.kt). */
        private const val LEGACY_GROUP_FILE = "zchat_group_info"
        private const val GROUP_STORE_ID = "zchat_group_info_encrypted"
        private const val DEFAULT_UFVK_FILE = "keystone_ufvk.txt"
        private const val DEFAULT_ALIAS_FILE = "drift_alias.txt"
        private const val GROUP_ID_SHOWN = 12

        /** Marks the view-only test account as not from any real seed (32 bytes, hex). */
        private val SYNTHETIC_SEED_FINGERPRINT = "d0".repeat(32)
        private val HEX_32_BYTES = Regex("[0-9a-fA-F]{64}")

        /** Synthetic 64-hex "txid" the ZRCPT ack points at — never resolves to a real message. */
        private val SYNTHETIC_TARGET_TXID = "0".repeat(64)

        /**
         * Zcash address-shaped tokens (unified/sapling/transparent/TEX, main & testnet prefixes) and viewing keys
         * (F0: the K0 import). An SDK exception message can splice in the wallet's own address or the key it was
         * given; strip it before logging.
         */
        private val ADDRESS_TOKEN =
            Regex(
                """\b(uviewtest|uview|zxviews|zxviewtestsapling|u1|utest1|zs1|ztestsapling1|t1|t3|tex1)[0-9a-z]{6,}""",
                RegexOption.IGNORE_CASE
            )

        private const val MAX_DETAIL_CHARS = 200

        /**
         * A human-readable but address-redacted rendering of a throwable message. Replaces any
         * embedded Zcash address token with `[addr]` and bounds the length. Never returns the raw
         * message.
         */
        private fun redactThrowableMessage(t: Throwable): String {
            val raw = t.message ?: return "(no message)"
            return raw.replace(ADDRESS_TOKEN, "[addr]").take(MAX_DETAIL_CHARS)
        }
    }
}
