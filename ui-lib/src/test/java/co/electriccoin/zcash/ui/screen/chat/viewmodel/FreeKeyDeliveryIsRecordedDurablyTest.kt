package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-289 (Round 8 findings, SPECS §5) — a group key delivered free over the relays is recorded with `apply()`, so a
 * kill can lose the record and the restarted app pays to send a key the member already holds.
 *
 * `retryPendingGroupKeysInternal` clears the member's `pendingKeyEpoch` after a free delivery at two places: the
 * no-consent branch (the relays took the wrapped key) and the sealed branch of an all-Open group (a relay acked the
 * sealed key). Both write with `recordKeyOwed(groupId, canonical) { null }`, which is `saveGroupMembers` (`apply()`).
 * The paid path and the SENT branch beside them use the checked `commit()`. A kill before `apply()` reaches the disk
 * leaves the member owed there: the next automatic pass, once the user has agreed to pay for handshakes and while the
 * relays are down, pays an on-chain re-send of a key that was delivered.
 *
 * Wanted: every delivery recorded as done is written with the checked commit; when that write does not reach the disk
 * the member stays owed (as the SENT branch does).
 *
 * The group-members file is a real [ZchatPreferencesImpl] over a file whose memory and disk are kept apart (the
 * OwedKeyStampSurvivesKillTest idiom): `apply()` changes memory only, `commit()` writes the disk. A "kill" is a fresh
 * process built from the disk alone. All addresses, keys, group ids and txids are synthetic; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeKeyDeliveryIsRecordedDurablyTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    /** Copies the relays were handed (this group publishes nothing but the owed key). */
    private val keyCopies = AtomicInteger()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    /** The relays are up; every copy gets [acks] relay acks. */
    private fun relaysUp(acks: Int = 1) {
        NostrChatBridge.registerPublisher { _, _ ->
            keyCopies.incrementAndGet()
            NostrChatBridge.PublishResult(acks = acks, messageId = "synthetic-zc289-${keyCopies.get()}")
        }
    }

    private fun relaysDown() = NostrChatBridge.unregisterPublisher()

    /** A preferences file with a separate memory and disk (the OwedKeyStampSurvivesKillTest idiom). */
    private class SplitPrefs(
        private val disk: MutableMap<String, String>,
    ) {
        private val memory = ConcurrentHashMap<String, String>(disk)

        val prefs: SharedPreferences by lazy {
            val staged = mutableMapOf<String, String?>()

            fun toMemory() {
                staged.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v }
            }
            lateinit var editor: SharedPreferences.Editor
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "putString" -> {
                            staged[args!![0] as String] = args[1] as String?
                            editor
                        }
                        "remove" -> {
                            staged[args!![0] as String] = null
                            editor
                        }
                        "apply" -> {
                            toMemory()
                            staged.clear()
                            null
                        }
                        "commit" -> {
                            toMemory()
                            staged.forEach { (k, v) -> if (v == null) disk.remove(k) else disk[k] = v }
                            staged.clear()
                            true
                        }
                        else -> editor
                    }
                } as SharedPreferences.Editor
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "edit" -> editor
                    "getString" -> memory[args!![0] as String] ?: args[1]
                    "contains" -> memory.containsKey(args!![0] as String)
                    else -> null
                }
            } as SharedPreferences
        }
    }

    private fun storeOver(store: SplitPrefs): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        ZchatPreferencesImpl::class.java
            .getDeclaredField("groupMembersPrefs")
            .apply { isAccessible = true }
            .set(impl, store.prefs)
        return impl
    }

    /** One app process over [disk]: member R (the fixture's P) is known by a relay key, with or without a session. */
    private fun processOver(
        disk: MutableMap<String, String>,
        consent: Boolean,
        memberHasSession: Boolean = true,
    ): GroupPaidOnceFixture =
        GroupPaidOnceFixture(
            memberPendingKeyEpoch = 1,
            consent = consent,
            nostrKeyForP = { GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY },
            membersStore = storeOver(SplitPrefs(disk)),
            memberHasSession = memberHasSession,
        ).also { it.scheduler = scheduler }

    private fun seededDisk(): MutableMap<String, String> {
        val template = GroupPaidOnceFixture(memberPendingKeyEpoch = 1)
        return ConcurrentHashMap(mapOf(GroupPaidOnceFixture.GROUP_ID to template.initialRosterJson))
    }

    private fun owedOnDisk(disk: Map<String, String>): Int? =
        ZMSGGroupProtocol
            .deserializeGroupMembers(disk.getValue(GroupPaidOnceFixture.GROUP_ID))
            .single { it.address == GroupPaidOnceFixture.MEMBER_P }
            .pendingKeyEpoch

    /** Open the group in [process]: the automatic repair runs once. */
    private suspend fun openGroup(process: GroupPaidOnceFixture): GroupViewModel {
        val vm = process.groupViewModel().also { vms += it }
        process.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
        return vm
    }

    // ── (a) + (b) the no-consent free delivery ───────────────────────────────────────────────────────────────

    @Test
    fun `a key delivered free without spend consent is recorded on disk`() =
        runBlocking {
            val disk = seededDisk()
            relaysUp()
            val run = processOver(disk, consent = false)
            openGroup(run)
            assertTrue("fixture sanity: the relays were handed the key", keyCopies.get() >= 1)
            assertEquals("fixture sanity: nothing was paid", 0, run.submits())
            assertNull("fixture sanity: the running app records the delivery", run.memberRow().pendingKeyEpoch)

            // The process is killed here.
            assertNull(
                "the key was delivered free, but the disk still says the member is owed it: after a kill the next " +
                    "automatic pass may pay to send it again",
                owedOnDisk(disk),
            )
        }

    @Test
    fun `after a kill a key delivered free is not paid for once consent is given and the relays are down`() =
        runBlocking {
            val disk = seededDisk()
            relaysUp()
            val run = processOver(disk, consent = false)
            val vm = openGroup(run)
            assertTrue("fixture sanity: the relays were handed the key", keyCopies.get() >= 1)
            assertEquals("fixture sanity: nothing was paid", 0, run.submits())

            // The process is killed; the user then agrees to pay for handshakes, and the relays are down.
            vm.viewModelScope.cancel()
            relaysDown()
            val restarted = processOver(disk, consent = true)
            openGroup(restarted)

            assertEquals(
                "the restarted app asked the wallet to pay for a key the member already holds",
                0,
                restarted.seam.proposalRequests.get(),
            )
            assertEquals("the restarted app paid on-chain for a key the member already holds", 0, restarted.submits())
        }

    // ── (c) the sealed route of an all-Open group ────────────────────────────────────────────────────────────

    @Test
    fun `a sealed key acked by a relay is recorded on disk`() =
        runBlocking {
            val disk = seededDisk()
            relaysUp()
            val run = processOver(disk, consent = true, memberHasSession = false)
            openGroup(run)
            assertTrue("fixture sanity: the relays were handed the sealed key", keyCopies.get() >= 1)
            assertEquals("fixture sanity: a sealed key is never paid for", 0, run.submits())
            assertNull("fixture sanity: the running app records the delivery", run.memberRow().pendingKeyEpoch)

            // The process is killed here.
            assertNull(
                "the sealed key was acked, but the disk still says the member is owed it",
                owedOnDisk(disk),
            )
        }

    // ── a record that does not reach the disk ────────────────────────────────────────────────────────────────

    @Test
    fun `a free delivery whose record cannot be saved leaves the member owed`() =
        runBlocking {
            relaysUp()
            val run =
                GroupPaidOnceFixture(
                    memberPendingKeyEpoch = 1,
                    consent = false,
                    nostrKeyForP = { GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY },
                    durableWritesSucceed = false,
                ).also { it.scheduler = scheduler }
            openGroup(run)
            assertTrue("fixture sanity: the relays were handed the key", keyCopies.get() >= 1)

            assertEquals(
                "the delivery was recorded in memory only, by a write that never checks the disk",
                1,
                run.memberRow().pendingKeyEpoch,
            )
            assertEquals(0, run.submits())
        }

    @Test
    fun `a sealed delivery whose record cannot be saved leaves the member owed`() =
        runBlocking {
            relaysUp()
            val run =
                GroupPaidOnceFixture(
                    memberPendingKeyEpoch = 1,
                    consent = true,
                    nostrKeyForP = { GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY },
                    durableWritesSucceed = false,
                    memberHasSession = false,
                ).also { it.scheduler = scheduler }
            openGroup(run)
            assertTrue("fixture sanity: the relays were handed the sealed key", keyCopies.get() >= 1)

            assertEquals(
                "the sealed delivery was recorded in memory only, by a write that never checks the disk",
                1,
                run.memberRow().pendingKeyEpoch,
            )
            assertEquals(0, run.submits())
        }

    // ── controls: a relay failure keeps the member owed ──────────────────────────────────────────────────────

    @Test
    fun `control - a key no relay acked keeps the member owed and pays nothing without consent`() =
        runBlocking {
            val disk = seededDisk()
            relaysUp(acks = 0)
            val run = processOver(disk, consent = false)
            openGroup(run)
            assertTrue("fixture sanity: the relays were handed the key", keyCopies.get() >= 1)

            assertEquals("no relay took the key, but the member is no longer owed it", 1, run.memberRow().pendingKeyEpoch)
            assertEquals("no relay took the key, but the disk says it was delivered", 1, owedOnDisk(disk))
            assertEquals("nothing may be paid without the spend consent", 0, run.submits())
        }

    @Test
    fun `control - a sealed key no relay acked keeps the member owed and is never paid for`() =
        runBlocking {
            val disk = seededDisk()
            relaysUp(acks = 0)
            val run = processOver(disk, consent = true, memberHasSession = false)
            openGroup(run)
            assertTrue("fixture sanity: the relays were handed the sealed key", keyCopies.get() >= 1)

            assertEquals("no relay took the sealed key, but the member is no longer owed it", 1, run.memberRow().pendingKeyEpoch)
            assertEquals("no relay took the sealed key, but the disk says it was delivered", 1, owedOnDisk(disk))
            assertEquals("a sealed key is never paid for", 0, run.submits())
        }
}
