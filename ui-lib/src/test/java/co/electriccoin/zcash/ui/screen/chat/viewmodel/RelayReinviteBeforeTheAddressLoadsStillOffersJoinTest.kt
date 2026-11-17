package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.NostrSeenLedger
import co.electriccoin.zcash.ui.screen.chat.datasource.OwnChatIdentityStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.PendingReinvite
import co.electriccoin.zcash.ui.screen.chat.model.WalletSyncStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** ZC-668 door 1: relay delivery before product identity loading must have a durable retry route. */
internal class RelayReinviteBeforeTheAddressLoadsStillOffersJoinTest : Zc668Fixture() {
    @Test
    fun `inactive relay invite is offered by product address load`() =
        runBlocking {
            inactive()
            val screen = screen(ready = false)
            screen.startLoad()
            assertTrue("fixture identity load did not enter account gate", screen.accounts.entered.isCompleted)
            assertNull("fixture must deliver before product loads the address", screen.address())
            receive(screen.vm, invite(), I2)
            assertTrue("reader mark must remain while invite can be stored", store.proxy.hasSeenNostrEvent(I2))
            screen.finishLoad()
            assertOffered(I2)
        }

    @Test
    fun `inactive relay invite survives a kill before address load with empty memory queue`() =
        runBlocking {
            inactive()
            val first = screen(ready = false)
            first.startLoad()
            receive(first.vm, invite(), I2)
            assertEquals("before kill the undecided invite must be durable", I2, diskState().pendingReinvite?.inviteId)
            assertFalse("before kill the undecided invite must be unused", I2 in diskState().usedInviteIds)
            assertNull("before kill Join must still wait for address readiness", diskJoin())
            first.close()
            reopenStore()
            val fresh = screen(ready = false)
            assertTrue("fresh screen must have no in-memory deferred invites", fresh.pendingQueueEmpty())
            fresh.startLoad()
            fresh.finishLoad()
            assertOffered(I2)
        }

    @Test
    fun `refused Join commit keeps inactive invite unused until next product load`() =
        runBlocking {
            inactive()
            val first = screen()
            store.joins.refuseNextCommit = true
            receive(first.vm, invite(), I2)
            assertEquals("fixture Join commit fault must fire once", 1, store.joins.refusals)
            assertNull("failed commit must not reach disk", diskJoin())
            assertTrue(
                "fault seam must mutate memory before refusing disk",
                store.joins.failedMemory
                    ?.keys
                    ?.any { it.startsWith("invitejoin:") } == true,
            )
            assertTrue(
                "fault seam must leave Join absent from disk",
                store.joins.failedDisk
                    ?.keys
                    ?.none { it.startsWith("invitejoin:") } == true,
            )
            val durable = diskState()
            assertFalse("an undecided invite must not be used", I2 in durable.usedInviteIds)
            assertEquals("unwritten Join must keep the relay invite durably", I2, durable.pendingReinvite?.inviteId)
            first.close()
            reopenStore()
            val fresh = screen(ready = false)
            fresh.startLoad()
            fresh.finishLoad()
            assertOffered(I2)
        }
}

/**
 * Shared ONLY by the five new ZC-668 classes. Group/session/SDK context is synthetic AnnouncePhone; all announce,
 * Join and seen operations delegate to real ZchatPreferencesImpl. A new store reads separate disk maps, never the
 * old VM or AnnouncePhone.states. apply is synchronously flushed in this deterministic stand-in; refused commit
 * changes memory but never disk, matching the failure that a map-only fake would hide. No encryption/device claim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class Zc668Fixture {
    private val scopes = SendHarnessScopes()
    protected val phone = AnnouncePhone()
    protected lateinit var admin: E2EKeyPair
    protected lateinit var store: Zc668Store
    protected val disks = Zc668Disks()
    protected val seam = HandshakeSeam(ADMIN)
    private val screens = mutableListOf<Zc668Screen>()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun openZc668() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        MA3.resetRuntime()
        NostrChatBridge.unregisterPublisher()
        admin = AnnounceDriver.memberPhone(phone)
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        store = Zc668Store(phone, disks)
        AnnounceDriver.relayAcks(published)
    }

    @After
    fun closeZc668() {
        try {
            assertEquals("no payment proposal before Join", 0, seam.proposalRequests.get())
            assertEquals("no submit before Join", 0, seam.submits.get())
            assertTrue("no broadcast before Join", seam.broadcasts.isEmpty())
            assertTrue("no relay publication before Join", published.isEmpty())
        } finally {
            NostrChatBridge.unregisterPublisher()
            scopes.close()
            MA3.resetRuntime()
            AutoSpendVm.resetProcessState()
            Dispatchers.resetMain()
        }
    }

    protected fun inactive() {
        phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(phone.info(GID).copy(isActive = false, removedByAdmin = true))
        assertTrue(store.proxy.saveGroupAnnounceState(GID, AnnounceState(inactiveSince = REMOVED)))
    }

    protected fun reopenStore() {
        store = Zc668Store(phone, disks)
    }

    protected fun diskState(): AnnounceState = Zc668Store(phone, disks).real.getGroupAnnounceState(GID)

    protected fun diskJoin(): JSONObject? = Zc668Store(phone, disks).real.getGroupInviteNeedsJoin(GID)?.let(::JSONObject)

    protected fun assertOffered(id: String, epoch: Int = 2, key: String? = null) {
        val reopen = diskJoin()?.optJSONObject("reopen")
        assertEquals("durable Join must name the chosen invite", id, reopen?.optString("invite_id"))
        assertEquals("durable Join must carry chosen epoch", epoch, reopen?.optInt("key_epoch"))
        if (key != null) assertTrue("durable Join must carry chosen key", key == reopen?.optString("key"))
        assertFalse("offer must wait for explicit Join", phone.info(GID).isActive)
        val before = checkNotNull(store.offerSnapshot) { "fixture must observe actual offer entry" }
        assertEquals("offering must not install epoch", before.first, phone.epochs[GID])
        assertTrue("offering must not install keys", before.second == phone.keys.toMap())
        assertTrue("offering must not alter roster", before.third == phone.rosters[GID])
        assertNull("only a durable offer clears the pending copy", diskState().pendingReinvite)
        assertFalse("merely presenting Join must not use the invite", id in diskState().usedInviteIds)
    }

    protected fun invite(epoch: Int = 2, key: String = AnnounceDriver.newGroupKey()): String =
        AnnounceDriver.gi(ADMIN, phone.sessionWith(ADMIN), key, epoch)

    protected suspend fun screen(ready: Boolean = true): Zc668Screen =
        Zc668Screen(phone, store.proxy, seam, ready).also {
            it.initialize()
            screens += it
        }

    protected fun receive(vm: ChatViewModel, memo: String, id: String, time: Long = INVITED, sender: String = ADMIN) {
        // AnnounceDriver.receive bypasses the real reader. Reproduce its receipt mark explicitly before handling.
        store.proxy.markNostrEventSeen(id)
        assertTrue("fixture reader-equivalent premark must exist before handler", store.proxy.hasSeenNostrEvent(id))
        AnnounceDriver.receive(vm, memo, Instant.ofEpochMilli(time), sender = sender, eventId = id)
    }

    protected fun keep(memo: String = invite(), id: String = I2, time: Long = INVITED, sender: String = ADMIN) {
        val payload = memo.substringAfter("GI:$GID:")
        assertTrue(
            store.proxy.saveGroupAnnounceState(
                GID,
                AnnounceState(
                    inactiveSince = REMOVED,
                    seenActiveInviteIds = setOf(id),
                    pendingReinvite = PendingReinvite(id, time, payload, sender),
                ),
            ),
        )
        assertEquals("fixture kept invite must reach reconstructed disk store", id, diskState().pendingReinvite?.inviteId)
    }

    protected fun offerKept(vm: ChatViewModel) {
        synchronized(ZchatPreferences.groupLock(GID)) {
            invoke(vm, "offerKeptReinvite", GID)
        }
    }

    protected fun kick(vm: ChatViewModel) {
        val memo = AnnounceDriver.kick(phone, admin, phone.self, 2, AnnounceDriver.newGroupKey())
        AnnounceDriver.receive(vm, memo, Instant.ofEpochMilli(REMOVED), AnnounceDriver.txId(668))
        assertFalse("fixture authentic self-kick must remove phone", phone.info(GID).isActive)
        assertEquals("fixture removal source time must be durable", REMOVED, diskState().inactiveSince)
    }

    companion object {
        const val I2 = "synthetic-zc668-relay-i2"
        const val I3 = "synthetic-zc668-relay-i3"
        const val REMOVED = T + 10_000
        const val INVITED = T + 20_000

        fun invoke(vm: ChatViewModel, name: String, vararg args: Any?) {
            try {
                ChatViewModel::class.java.declaredMethods
                    .single { it.name == name && it.parameterCount == args.size }
                    .apply { isAccessible = true }
                    .invoke(vm, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}

internal class Zc668Accounts(
    private val account: co.electriccoin.zcash.ui.common.model.ZashiAccount,
) : AccountDataSource by Zc101FakeAccountDataSource(account) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun getSelectedAccount() =
        account.also {
            entered.complete(Unit)
            release.await()
        }
}

internal class Zc668Screen(
    private val phone: AnnouncePhone,
    private val prefs: ZchatPreferences,
    private val seam: HandshakeSeam,
    private val ready: Boolean,
) {
    lateinit var accounts: Zc668Accounts
        private set
    lateinit var vm: ChatViewModel
    private val addresses = MutableStateFlow<String?>(null)

    @Volatile private var flowSetupReached = false
    private val loadTicks = MutableStateFlow(0L)
    private val listState = MutableStateFlow<ChatListState>(ChatListState.Loading)

    suspend fun initialize() {
        accounts = Zc668Accounts(syntheticZashiAccount())
        val loadPrefs =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getReadMarkers" -> MutableStateFlow(emptyMap<String, Long>())
                    "getE2eHandshakeTicks" -> loadTicks.also { flowSetupReached = true }
                    "getDisappearingTtls" -> MutableStateFlow(emptyMap<String, Long>())
                    else ->
                        try {
                            method.invoke(prefs, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
        vm = HandshakeHarness.chatViewModelWith(loadPrefs, seam, accountSource = accounts)
        AutoSpendVm.wireBootstrapFields(vm)
        if (ready) addresses.value = phone.self
        AutoSpendVm.setField(vm, "_currentUserAddress", addresses)
        val selected = GetSelectedWalletAccountUseCase(accounts)
        val identities =
            object : OwnChatIdentityStore {
                override fun pinnedIdentity(accountKey: String) = phone.self

                override fun pinIdentityIfAbsent(accountKey: String, address: String) = error("pinned fixture must avoid SDK address call")
            }
        AutoSpendVm.setField(
            vm,
            "getDefaultUnifiedAddress",
            GetDefaultUnifiedAddressUseCase(HeightFeed().provider, selected) { identities },
        )
        AutoSpendVm.setField(vm, "getSelectedWalletAccount", selected)
        AutoSpendVm.setField(vm, "repairComplete", CompletableDeferred(Unit))
        AutoSpendVm.setField(vm, "_chatListState", listState)
        AutoSpendVm.setField(
            vm,
            "walletSnapshotDataSource",
            object : WalletSnapshotDataSource {
                override fun observe() = MutableStateFlow<WalletSnapshot?>(null)
            },
        )
        val tx =
            Proxy.newProxyInstance(
                TransactionRepository::class.java.classLoader,
                arrayOf(TransactionRepository::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getTransactions" -> emptyFlow<Any>()
                    else -> error("unexpected transaction fixture call")
                }
            } as TransactionRepository
        AutoSpendVm.setField(vm, "transactionRepository", tx)
        AutoSpendVm.setField(vm, "restoreRecheck", MutableStateFlow(0))
        AutoSpendVm.setField(vm, "hiddenMessages", MutableStateFlow(emptySet<String>()))
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<Any>()))
        AutoSpendVm.setField(vm, "paidRequestIds", MutableStateFlow(emptySet<String>()))
        AutoSpendVm.setField(vm, "_lastSyncTime", MutableStateFlow<Instant?>(null))
        AutoSpendVm.setField(vm, "_isRefreshing", MutableStateFlow(false))
        AutoSpendVm.setField(vm, "_secondsUntilNextSync", MutableStateFlow(0))
        AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        AutoSpendVm.setField(vm, "_zecPriceUsd", MutableStateFlow<Double?>(null))
        AutoSpendVm.setField(vm, "_walletSyncStatus", MutableStateFlow(WalletSyncStatus()))
    }

    fun address(): String? = addresses.value

    fun pendingQueueEmpty(): Boolean = (field("pendingGroupInvites") as Map<*, *>).isEmpty()

    fun startLoad() {
        Zc668Fixture.invoke(vm, "loadConversations")
    }

    suspend fun finishLoad() {
        accounts.release.complete(Unit)
        HandshakeHarness.awaitTrue {
            loadTicks.subscriptionCount.value > 0 || listState.value is ChatListState.Error || loadJob()?.isCompleted == true
        }
        assertFalse("fixture product load must not catch a setup exception", listState.value is ChatListState.Error)
        assertTrue("fixture product combine must actually subscribe", loadTicks.subscriptionCount.value > 0)
        assertTrue("fixture product load must reach flow setup without caught exception", flowSetupReached)
        assertTrue("fixture product load must remain collecting, not fail in setup", loadJob()?.isActive == true)
        assertEquals("product must load the selected pinned identity", phone.self, address())
        // Drain is synchronous before flow setup. No timeout-based absence assertion is needed.
    }

    suspend fun close() {
        vm.viewModelScope.coroutineContext.job
            .cancelAndJoin()
    }

    private fun loadJob(): Job? = field("conversationLoadJob") as Job?

    private fun field(name: String): Any? =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm)
}

internal class Zc668Disks {
    val members = ConcurrentHashMap<String, Any>()
    val joins = ConcurrentHashMap<String, Any>()
    val seen = ConcurrentHashMap<String, Any>()
}

internal class Zc668Store(
    private val phone: AnnouncePhone,
    disks: Zc668Disks,
) {
    val members = Zc668SplitPrefs(disks.members)
    val joins = Zc668SplitPrefs(disks.joins)
    private val seen = Zc668SplitPrefs(disks.seen)
    val real: ZchatPreferencesImpl
    var throwInsideOffer = false
    var offerReadFaults = 0
    var generation = 0L
    var changeWalletOnCreatorRead = false
    var walletChanges = 0
    var writesAfterChange = 0
    var offerSnapshot: Triple<Int?, Map<String, String>, String?>? = null
    var switchAfterStateRead: Zc668Store? = null
    private var replacement: Zc668Store? = null

    init {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        real =
            unsafeType
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl

        fun set(name: String, value: Any) =
            ZchatPreferencesImpl::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(real, value)
        set("groupMembersPrefs", members)
        set("heldGroupAcceptPrefs", joins)
        set("nostrSeenPrefs", seen)
        set("relaySetPrefs", Zc668SplitPrefs(ConcurrentHashMap()))
        set("nostrSeenLock", Any())
        set(
            "nostrSeenLedger\$delegate",
            lazy {
                NostrSeenLedger { 20_000L }.also { ledger ->
                    ZchatPreferencesImpl::class.java
                        .getDeclaredMethod("loadNostrSeen", NostrSeenLedger::class.java)
                        .apply { isAccessible = true }
                        .invoke(real, ledger)
                }
            },
        )
    }

    val proxy =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
            val name = method.name
            if (name == "isGroupSelfCreated" && Thread.currentThread().stackTrace.any { it.methodName == "offerGroupReopen" }) {
                offerSnapshot = Triple(phone.epochs[GID], phone.keys.toMap(), phone.rosters[GID])
            }
            if (throwInsideOffer &&
                name == "getGroupKeyEpoch" &&
                Thread.currentThread().stackTrace.any { it.methodName == "offerGroupReopen" }
            ) {
                throwInsideOffer = false
                offerReadFaults++
                throw IllegalStateException("synthetic inside-offer store read")
            }
            if (changeWalletOnCreatorRead && name == "getE2EPeerPublicKey") {
                changeWalletOnCreatorRead = false
                generation++
                walletChanges++
            }
            val durable =
                name in
                    setOf(
                        "getGroupAnnounceState",
                        "saveGroupAnnounceState",
                        "getGroupInviteNeedsJoin",
                        "setGroupInviteNeedsJoin",
                        "markNostrEventSeen",
                        "unmarkNostrEventSeen",
                        "hasSeenNostrEvent",
                    )
            if (generation > 0 &&
                name in setOf("saveGroupAnnounceState", "setGroupInviteNeedsJoin", "markNostrEventSeen", "unmarkNostrEventSeen")
            ) {
                writesAfterChange++
            }
            when (name) {
                "walletScopeGeneration" -> generation
                else ->
                    try {
                        val live = replacement ?: this
                        val value = method.invoke(if (durable) live.real else live.phone.proxy, *(args ?: emptyArray()))
                        if (name == "getGroupAnnounceState" && switchAfterStateRead != null) {
                            replacement = switchAfterStateRead
                            switchAfterStateRead = null
                            generation++
                            walletChanges++
                        }
                        value
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}

internal class Zc668SplitPrefs(
    private val disk: MutableMap<String, Any>,
) : SharedPreferences {
    private val memory = ConcurrentHashMap<String, Any>(disk)
    var refuseNextCommit = false
    var refusals = 0
    var failedMemory: Map<String, Any>? = null
    var failedDisk: Map<String, Any>? = null

    override fun getAll(): MutableMap<String, *> = HashMap(memory)

    override fun getString(key: String, defValue: String?): String? = memory[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (memory[key] as Set<String>?)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = memory[key] as Int? ?: defValue

    override fun getLong(key: String, defValue: Long): Long = memory[key] as Long? ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = memory[key] as Float? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = memory[key] as Boolean? ?: defValue

    override fun contains(key: String): Boolean = memory.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false

        private fun put(key: String, value: Any?) = apply { changes[key] = value }

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear() = apply { clear = true }

        private fun into(target: MutableMap<String, Any>) {
            if (clear) target.clear()
            changes.forEach { (key, value) -> if (value == null) target.remove(key) else target[key] = value }
        }

        override fun commit(): Boolean {
            into(memory)
            if (refuseNextCommit) {
                refuseNextCommit = false
                refusals++
                failedMemory = memory.toMap()
                failedDisk = disk.toMap()
                return false
            }
            into(disk)
            return true
        }

        override fun apply() {
            into(memory)
            into(disk)
        }
    }
}
