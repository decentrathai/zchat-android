package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.SelfAddressRegistry
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.announceStateFromJson
import co.electriccoin.zcash.ui.screen.chat.model.announceStateToJson
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * ZC-690: drive actual confirmed removal and authenticated leave through the VMs and the real roster/announce store.
 * No production API added for RED. The class-local wrapper also forwards future overloaded store methods by reflection.
 * Keys, epochs, floors and identity remain MA3's stores; only membership, announce records and spend consent use the
 * real group-members preferences. Fresh-store assertions concern those records, not atomicity of all rotation stores.
 * All identities and cryptographic material are synthetic. No real wallet, phone or funds are used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovalCutShortAfterItsCommitStillHasALeaveTimeTest {
    private val scopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        scopes.close()
        MA3.resetRuntime()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a - leaving the screen during the removal notice keeps LEFT and its removal record live and on disk`() =
        runBlocking {
            val w = world()
            val window = cancelAtNotice(w)
            val problems = mutableListOf<String>()
            for ((name, store) in listOf("live" to w.store.real, "fresh disk" to w.store.restart())) {
                assertRemoval(store, window, problems, name)
                assertEquals("$name: membership consent must end in the roster edit", setOf(A), store.getGroupMessageSpendConsent(GID))
                assertKeptFields(w, store, name)
            }
            assertTrue(problems.joinToString("; "), problems.isEmpty())
        }

    @Test
    fun `b - real reinvite after cancelled removal refuses the genuinely signed accept for epoch one`() =
        runBlocking {
            val w = world()
            // Generated before removal with the member's actual paired signing key; only the unsigned epoch is added.
            val old =
                MA3.signedAccept(w.x).let { memo ->
                    val payload = ZMSGGroupProtocol.parsePayload(memo)!!
                    memo.removeSuffix(payload) + JSONObject(payload).put("key_epoch", 1).toString()
                }
            val window = cancelAtNotice(w)
            w.net.register()
            val replacement = groupVm(w)
            MA3.load(replacement, w.fixture)
            w.fixture.awaitNewJobs(replacement) { replacement.addMemberToGroup(GID, X) }
            replacement.paidActionConfirmation.value?.let { MA3.confirm(replacement, w.fixture) }
            assertEquals("setup: real reinvite did not reach INVITED", MemberStatus.INVITED, status(w.store.real))
            val invites = w.net.ofType(GroupMessageType.GROUP_INVITE, NPUB.getValue(X))
            assertTrue("setup: real reinvite sent no invite", invites.isNotEmpty())
            assertTrue(
                "setup: reinvite did not carry the rotated epoch",
                invites.all {
                    JSONObject(ZMSGGroupProtocol.parsePayload(it)!!).getInt("key_epoch") >= 2
                }
            )
            w.net.clear()
            val seam = HandshakeSeam(C)
            val receiver = chatVm(w, seam)
            val activatedAt =
                w.store.real
                    .getGroupAnnounceState(GID)
                    .members
                    .getValue(X)
                    .activatedAtMs
            repeat(3) { pass ->
                MA3.chain(receiver, old, tx = 47, timeMs = window.last + 300_000)
                assertEquals("pass ${pass + 1}: old accept activated X without Join", MemberStatus.INVITED, status(w.store.real))
                assertEquals(
                    activatedAt,
                    w.store.real
                        .getGroupAnnounceState(GID)
                        .members
                        .getValue(X)
                        .activatedAtMs
                )
            }
            assertFalse("old accept announced X back", w.net.gps().any { it.op == AnnounceOp.A && it.subject == X })
            assertEquals("replayed accept must not propose any payment", 0, seam.proposalRequests.get())
        }

    @Test
    fun `c - signed leave with a false announce commit never leaves a partial membership record`() =
        runBlocking {
            refusedLeave(Fault.FALSE)
        }

    @Test
    fun `c exception - signed leave with a throwing announce commit never leaves a partial membership record`() =
        runBlocking {
            refusedLeave(Fault.THROW)
        }

    @Test
    fun `control - completed paid removal records exact epoch AUTH time and keeps that time on a later invite`() =
        runBlocking {
            val w = world(relayX = false)
            w.net.register()
            val vm = groupVm(w)
            MA3.load(vm, w.fixture)
            val before = System.currentTimeMillis()
            w.fixture.awaitNewJobs(vm) { vm.kickMember(GID, X) }
            val quote = vm.paidActionConfirmation.value
            assertNotNull("setup: removal must be quoted", quote)
            assertEquals(PaidAction.GROUP_REMOVE_MEMBER, quote!!.action)
            assertEquals("key to A, notice to X, R X to A", 3, quote.onChainMessages)
            assertEquals("no spend before Confirm", 0, w.seam.proposals.size)
            MA3.confirm(vm, w.fixture)
            val window = before..System.currentTimeMillis()
            assertEquals(2, w.admin.base.epochs[GID])
            val problems = mutableListOf<String>()
            assertRemoval(w.store.real, window, problems, "live")
            // Flush queued successful apply writes, as Android's asynchronous writer may have completed them.
            w.store.members.flushApplies()
            assertRemoval(w.store.restart(), window, problems, "fresh disk")
            assertTrue(problems.joinToString("; "), problems.isEmpty())
            assertEquals("exactly the quoted three proposals", 3, w.seam.proposals.size)
            assertEquals("each proposal submitted once", w.seam.proposals, w.seam.submitted)
            val toA = w.seam.proposals.filter { it.recipient == A }
            assertEquals(2, toA.size)
            assertEquals("key memo precedes R pair", GroupMessageType.GROUP_KICK, ZMSGGroupProtocol.parseMessageType(toA.first().memo))
            assertTrue("A's first memo carries the key", JSONObject(ZMSGGroupProtocol.parsePayload(toA.first().memo)!!).has("enc_key"))
            assertEquals(AnnounceOp.R, MA3.parseGp(toA.last().memo)?.op)
            assertEquals(X, MA3.parseGp(toA.last().memo)?.subject)
            val notices = w.seam.proposals.filter { it.recipient == X }
            assertEquals("one notice and no other payment to X", 1, notices.size)
            assertNotice(w, notices.single().memo)
            for (store in listOf(w.store.real, w.store.restart())) {
                assertEquals(setOf(A), store.getGroupMessageSpendConsent(GID))
                assertKeptFields(w, store, "completed removal")
            }
            val removed =
                w.store.real
                    .getGroupAnnounceState(GID)
                    .members
                    .getValue(X)
            w.admin.base.nostrKeys[X] = NPUB.getValue(X)
            w.fixture.awaitNewJobs(vm) { vm.addMemberToGroup(GID, X) }
            vm.paidActionConfirmation.value?.let { MA3.confirm(vm, w.fixture) }
            assertEquals(MemberStatus.INVITED, status(w.store.real))
            val later =
                w.store.real
                    .getGroupAnnounceState(GID)
                    .members
                    .getValue(X)
            assertEquals("invite moved removal time", removed.leftAtMs, later.leftAtMs)
            assertEquals("invite moved removal epoch", removed.removedAtEpoch, later.removedAtEpoch)
            assertEquals("invite restored old message-spend consent", setOf(A), w.store.real.getGroupMessageSpendConsent(GID))
        }

    private suspend fun refusedLeave(fault: Fault) {
        val w = world()
        w.net.register()
        val seam = HandshakeSeam(C)
        val vm = chatVm(w, seam)
        val source = System.currentTimeMillis() + 5_000
        val seconds = source / 1_000
        val sig = E2EEncryption.sign(MA3.keyFor(w.x, C).privateKey, ZMSGGroupProtocol.groupLeaveSignedData(GID, X, seconds))
        val leave = ZMSGGroupProtocol.createGroupLeaveMessage(GID, X, sig, seconds)
        assertTrue(
            "setup: leave signature is genuine",
            E2EEncryption.verify(
                w.admin.base.peerKeys
                    .getValue(X),
                ZMSGGroupProtocol.groupLeaveSignedData(GID, X, seconds),
                sig,
            )
        )
        val counterBefore = w.store.real.getGroupAnnounceCounter(GID)
        w.store.members.fault = fault
        MA3.chain(vm, leave, tx = 48, timeMs = source)
        assertEquals("the exact staged announce-state commit must fault once", 1, w.store.members.refusals)
        val failedMemory = checkNotNull(w.store.members.failedMemory)
        val failedDisk = checkNotNull(w.store.members.failedDisk)
        assertTrue(
            "fault must see actual metadata, not an unimplemented fixture method",
            announceStateFromJson(
                failedMemory[STATE_KEY] as String?,
            ).members.getValue(X).leftAtMs == source
        )
        val problems = mutableListOf<String>()
        // Capture at the failed commit too: a subsequent announce pass must not conceal a partial disk write.
        allOrNeither(w.store.fromSnapshot(failedMemory), source, "failed commit memory", problems)
        allOrNeither(w.store.fromSnapshot(failedDisk), source, "failed commit disk", problems)
        allOrNeither(w.store.real, source, "settled memory", problems)
        allOrNeither(w.store.restart(), source, "settled disk", problems)
        for ((view, store) in listOf(
            "failed commit memory" to w.store.fromSnapshot(failedMemory),
            "failed commit disk" to w.store.fromSnapshot(failedDisk),
            "settled memory" to w.store.real,
            "settled disk" to w.store.restart(),
        )) {
            assertKeptFields(w, store, view)
        }
        if (w.store.real.getGroupAnnounceCounter(GID) != counterBefore) problems += "refused leave numbered an announce"
        if (w.net.gps().isNotEmpty()) problems += "refused leave sent a member announce"
        if (seam.proposalRequests.get() != 0) problems += "refused leave proposed a payment"
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    private fun allOrNeither(
        store: ZchatPreferences,
        source: Long,
        view: String,
        problems: MutableList<String>
    ) {
        val row = status(store)
        val entry = store.getGroupAnnounceState(GID).members.getValue(X)
        val changed = entry.leftAtMs == source && entry.removedAtEpoch == 1 && entry.leftBy == ActivatedBy.AUTH
        val unchanged = entry.leftAtMs == 0L && entry.removedAtEpoch == 0
        if (!((row == MemberStatus.LEFT && changed) || (row == MemberStatus.ACTIVE && unchanged))) {
            problems += "$view: row and authenticated leave record are not both present or both absent"
        }
        val expectedConsent = if (row == MemberStatus.LEFT) setOf(A) else setOf(A, X)
        if (store.getGroupMessageSpendConsent(GID) != expectedConsent) problems += "$view: consent did not follow membership"
    }

    private suspend fun cancelAtNotice(w: World): LongRange {
        val reached = CompletableDeferred<String>()
        val neverRelease = CompletableDeferred<Unit>()
        NostrChatBridge.registerPublisher { memo, recipient ->
            w.net.published += recipient to memo
            if (recipient == NPUB.getValue(X) && ZMSGGroupProtocol.parseMessageType(memo) == GroupMessageType.GROUP_KICK) {
                assertNotice(w, memo)
                reached.complete(memo)
                neverRelease.await()
            }
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-zc690-${w.net.published.size}")
        }
        val vm = groupVm(w)
        MA3.load(vm, w.fixture)
        val before = System.currentTimeMillis()
        w.fixture.awaitNewJobs(vm) { vm.kickMember(GID, X) }
        val quote = vm.paidActionConfirmation.value
        assertNotNull("setup: removal with an on-chain key and R pair must be quoted", quote)
        assertEquals(PaidAction.GROUP_REMOVE_MEMBER, quote!!.action)
        assertEquals("key to A plus R X to A; X's notice is free", 2, quote.onChainMessages)
        assertEquals("setup: payment before confirmation", 0, w.seam.proposals.size)
        try {
            // Do not await all VM jobs while intentionally holding one in the notice publisher.
            vm.confirmPaidAction()
            scheduler.runCurrent()
            withTimeout(HandshakeHarness.TIMEOUT_MS) { reached.await() }
            assertEquals("setup: hold is after the row write", MemberStatus.LEFT, status(w.store.real))
            assertEquals("setup: hold is after epoch rotation", 2, w.admin.base.epochs[GID])
            assertEquals("setup: floor already rotated", 2, w.admin.base.floors[GID])
            assertNotNull("setup: rotated key already stored", w.admin.base.keys["$GID|2"])
            assertEquals("setup: only A's key paid before notice", 1, w.seam.submitted.size)
            assertEquals(
                A,
                w.seam.submitted
                    .single()
                    .recipient
            )
            assertTrue(
                "setup: preceding paid kick carried key",
                JSONObject(
                    ZMSGGroupProtocol.parsePayload(
                        w.seam.submitted
                            .single()
                            .memo
                    )!!,
                ).has("enc_key")
            )
            assertEquals("setup: notice publisher entered once", 1, w.net.ofType(GroupMessageType.GROUP_KICK, NPUB.getValue(X)).size)
            // A completed asynchronous roster apply is legal before/during this suspended network operation.
            w.store.members.flushApplies()
            assertEquals("setup: row reached disk before cancellation", MemberStatus.LEFT, status(w.store.restart()))
        } finally {
            vm.viewModelScope.cancel()
            try {
                withTimeout(HandshakeHarness.TIMEOUT_MS) {
                    vm.viewModelScope.coroutineContext.job
                        .join()
                }
            } finally {
                neverRelease.cancel()
            }
        }
        assertEquals("cancellation sent or paid another memo", 1, w.seam.submitted.size)
        return before..System.currentTimeMillis()
    }

    private fun assertNotice(w: World, memo: String) {
        val payload = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!)
        assertEquals(X, payload.getString("kicked"))
        assertEquals(2, payload.getInt("new_epoch"))
        assertFalse("notice leaked rotated key", payload.has("enc_key") || payload.has("gk"))
        assertTrue(
            "notice must be signed by creator's paired key",
            E2EEncryption.verify(
                MA3.keyFor(w.admin, X).publicKey,
                ZMSGGroupProtocol.groupKickSignedData(GID, X, C, 2, null),
                payload.getString("sig"),
            )
        )
    }

    private fun assertRemoval(
        store: ZchatPreferences,
        window: LongRange,
        problems: MutableList<String>,
        view: String
    ) {
        if (status(store) != MemberStatus.LEFT) problems += "$view: removed row is not LEFT"
        val entry = store.getGroupAnnounceState(GID).members[X]
        if (entry == null || entry.leftAtMs !in window) problems += "$view: removal time is absent or outside call"
        if (entry?.removedAtEpoch != 2) problems += "$view: removal epoch is not the rotated epoch"
        if (entry?.leftBy != ActivatedBy.AUTH) problems += "$view: removal is not authenticated"
    }

    private fun assertKeptFields(
        w: World,
        store: ZchatPreferences,
        view: String
    ) {
        val state = store.getGroupAnnounceState(GID)
        assertEquals("$view: seen invite ids lost", w.kept.seenActiveInviteIds, state.seenActiveInviteIds)
        assertEquals("$view: pending reinvite lost", w.kept.pendingReinvite, state.pendingReinvite)
        assertEquals("$view: used invite ids lost", w.kept.usedInviteIds, state.usedInviteIds)
    }

    private fun status(store: ZchatPreferences): MemberStatus? =
        ZMSGGroupProtocol.deserializeGroupMembers(store.getGroupMembers(GID)!!).single { it.address == X }.status

    private class World(
        val admin: AnnouncerPhone,
        val x: AnnouncerPhone,
        val store: MembershipStore,
        val proxy: ZchatPreferences,
        val fixture: GroupPaidOnceFixture,
        val seam: AnnounceSeam,
        val net: RelayNet,
        val kept: AnnounceState,
    )

    private fun world(relayX: Boolean = true): World {
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE))
        MA3.memberPhone(A, admin)
        val x = MA3.memberPhone(X, admin)
        if (relayX) admin.base.nostrKeys[X] = NPUB.getValue(X)
        MA3.alreadyAnnounced(admin, listOf(A, X))
        val pending =
            JSONObject()
                .put("id", "synthetic-zc690-kept-invite")
                .put("source_ms", 1_790_000_000_000L)
                .put("sender", C)
                .put("invite", JSONObject().put("name", "Synthetic kept group").put("inviter", C).toString())
        val kept =
            announceStateFromJson(
                JSONObject(announceStateToJson(admin.state()))
                    .put("pending_reinvite", pending)
                    .put("seen_active_invite_ids", org.json.JSONArray().put("synthetic-zc690-kept-invite"))
                    .put("used_invite_ids", org.json.JSONArray().put("synthetic-zc690-used-invite"))
                    .toString()
            )
        assertNotNull("setup: kept invite must really parse", kept.pendingReinvite)
        val store = MembershipStore()
        assertTrue(store.real.saveGroupMembersDurably(GID, admin.base.rosters.getValue(GID)))
        assertTrue(store.real.saveGroupAnnounceState(GID, kept))
        assertTrue(store.real.raiseGroupAnnounceCounter(GID, admin.counters.getValue(GID)))
        admin.ledger().forEach { entry ->
            val pair =
                co.electriccoin.zcash.ui.screen.chat.model.AnnouncePair(
                    entry.rAddr,
                    entry.rAddr,
                    entry.rKey,
                    entry.sAddr,
                    entry.sAddr,
                    entry.sKey,
                    entry.op,
                    0L,
                    1,
                )
            assertTrue(store.real.casGroupAnnounce(GID, pair, null, entry))
        }
        assertTrue(store.real.setGroupMessageSpendConsent(GID, setOf(A, X)))
        assertEquals("setup: initial roster durable", admin.base.rosters[GID], store.restart().getGroupMembers(GID))
        assertEquals("setup: initial state durable", kept, store.restart().getGroupAnnounceState(GID))
        val proxy =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
                val real =
                    method.name.contains("GroupMembers") ||
                        method.name.contains("GroupAnnounce") ||
                        method.name in
                        setOf(
                            "withGroupLock",
                            "isGp1Capable",
                            "markGp1Capable",
                            "getGroupMessageSpendConsent",
                            "setGroupMessageSpendConsent",
                        )
                try {
                    method.invoke(if (real) store.real else admin.proxy, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as ZchatPreferences
        return World(admin, x, store, proxy, GroupPaidOnceFixture().also { it.scheduler = scheduler }, AnnounceSeam(), RelayNet(), kept)
    }

    private suspend fun groupVm(w: World): GroupViewModel =
        MA3.groupVm(w.admin, w.fixture, w.seam).also {
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(it, w.proxy)
        }

    private suspend fun chatVm(
        w: World,
        seam: HandshakeSeam
    ): ChatViewModel =
        MA3.chatVm(w.admin, seam).also {
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(it, w.proxy)
            // ChatViewModel's cache is not keyed by preferences identity, unlike GroupViewModel's cache.
            ChatViewModel::class.java
                .getDeclaredField("cachedGroupAnnouncer")
                .apply { isAccessible = true }
                .set(it, null)
        }

    private enum class Fault { FALSE, THROW }

    /** Real group-members methods, with Android-style memory/disk separation and a staged-key fault. */
    private class MembershipStore {
        val disk = linkedMapOf<String, Any>()
        val members = SplitPrefs(disk)
        val real = process(members)

        fun restart(): ZchatPreferencesImpl = process(SplitPrefs(LinkedHashMap(disk)))

        fun fromSnapshot(snapshot: Map<String, Any>): ZchatPreferencesImpl = process(SplitPrefs(LinkedHashMap(snapshot)))

        private fun process(groupMembers: SharedPreferences): ZchatPreferencesImpl {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val impl =
                unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ZchatPreferencesImpl::class.java)
                    as ZchatPreferencesImpl
            val files = linkedMapOf<String, SharedPreferences>()
            ZchatPreferencesImpl::class.java.declaredFields
                .filter {
                    !Modifier.isStatic(it.modifiers) && SharedPreferences::class.java.isAssignableFrom(it.type)
                }.forEach {
                    val prefs = if (it.name == "groupMembersPrefs") groupMembers else SplitPrefs(linkedMapOf())
                    files[it.name] = prefs
                    it.apply { isAccessible = true }.set(impl, prefs)
                }
            ZchatPreferencesImpl::class.java
                .getDeclaredField("selfAddresses\$delegate")
                .apply { isAccessible = true }
                .set(impl, lazyOf(SelfAddressRegistry(files.getValue("prefs"))))
            impl.registerSelfAddress(C)
            return impl
        }
    }

    /**
     * A serial SharedPreferences schedule: apply changes live memory and queues its full snapshot for disk; successful
     * commit writes the full current snapshot. Before the selected commit is refused, earlier applies are drained:
     * a valid schedule where the roster's asynchronous write already completed. False and exception update memory but
     * leave that commit's disk snapshot absent. The oracle is per view and does not require memory to equal disk on
     * refusal. This stand-in proves the selected JVM storage schedule, not device encrypted-preferences behavior.
     */
    private class SplitPrefs(
        val disk: MutableMap<String, Any>
    ) : SharedPreferences {
        private val memory = LinkedHashMap(disk)
        private val pending = mutableListOf<Map<String, Any>>()
        var fault: Fault? = null
        var refusals = 0
        var failedMemory: Map<String, Any>? = null
        var failedDisk: Map<String, Any>? = null

        fun flushApplies() {
            pending.forEach { snapshot ->
                disk.clear()
                disk.putAll(snapshot)
            }
            pending.clear()
        }

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(memory)

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
            private val changes = linkedMapOf<String, Any?>()
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
                changes.forEach { (k, v) -> if (v == null) target.remove(k) else target[k] = v }
            }

            override fun apply() {
                check(!clear) { "No clear is needed in this bounded membership test" }
                into(memory)
                pending += LinkedHashMap(memory)
            }

            override fun commit(): Boolean {
                flushApplies()
                into(memory)
                val armed = fault
                val staged = changes[STATE_KEY] as? String
                if (armed != null && staged != null && announceStateFromJson(staged).members[X]?.leftAtMs?.let { it > 0 } == true) {
                    fault = null
                    refusals++
                    failedMemory = LinkedHashMap(memory)
                    failedDisk = LinkedHashMap(disk)
                    if (armed == Fault.THROW) throw IllegalStateException("synthetic announce-state disk fault")
                    return false
                }
                disk.clear()
                disk.putAll(memory)
                return true
            }
        }
    }

    private companion object {
        const val STATE_KEY = "group_ann_state:$GID"
    }
}
