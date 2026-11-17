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
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * B1 / GPT-6 Q4 + Q16 (GROUP-OWED-KEY-STAMP-APPLY) — the owed-key repair writes its "repaired" stamp with
 * `apply()` just before it pays, so a kill can make the next group open pay the same key again.
 *
 * `retryPendingGroupKeysInternal` clears the member's `pendingKeyEpoch` BEFORE the paid re-send (it is the
 * only record that the key was paid for). The write goes through `ZchatPreferences.saveGroupMembers`, which
 * uses `SharedPreferences.apply()`: the in-memory copy changes at once, the disk later, and nothing reports a
 * failed write. If the process dies after the transaction is broadcast but before the write reaches the disk,
 * the restarted app reads the member as still owed and the automatic repair pays a second GROUP_KEY. The
 * R5-2 commit() conversion covered five other money markers, not this one.
 *
 * The group-members file here is a real [ZchatPreferencesImpl] over a preferences file whose memory and disk
 * are kept apart: `apply()` changes only memory, `commit()` writes the disk (and reports whether it could).
 * A "restart" builds a fresh store and a fresh GroupViewModel from the disk alone.
 *
 * Wanted: the stamp is written with a checked, synchronous write before any payment, and when that write
 * does not reach the disk nothing is paid for that member (it stays owed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwedKeyStampIsDurableBeforePayTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

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

    /**
     * A preferences file with a separate memory and disk. Opening it (a process start) loads memory from the
     * disk. `apply()` updates memory only, as a write still queued when the process dies; `commit()` writes
     * memory, and the disk when [diskWritable] (Android's commit() also updates memory when the disk write
     * fails, and returns false).
     */
    private class SplitPrefs(
        private val disk: MutableMap<String, String>,
        private val diskWritable: Boolean,
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
                            if (diskWritable) staged.forEach { (k, v) -> if (v == null) disk.remove(k) else disk[k] = v }
                            staged.clear()
                            diskWritable
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

    /** A [ZchatPreferencesImpl] with no Context whose group-members file is [store] (R5-2 test idiom). */
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

    /** One process run: a fixture whose roster is read from [disk]; [seam] counts every on-chain submit. */
    private fun processOver(
        disk: MutableMap<String, String>,
        diskWritable: Boolean = true,
    ): GroupPaidOnceFixture =
        GroupPaidOnceFixture(
            memberPendingKeyEpoch = 1,
            consent = true,
            membersStore = storeOver(SplitPrefs(disk, diskWritable)),
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

    @Test
    fun `a kill after the owed key is paid never makes the next start pay it again`() =
        runBlocking {
            val disk = seededDisk()

            val first = processOver(disk)
            val vm = first.groupViewModel().also { vms += it }
            first.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("fixture: the repair paid the owed key once", 1, first.submits())

            // The process dies here: whatever apply() had not written is gone.
            val restarted = processOver(disk)
            val vm2 = restarted.groupViewModel().also { vms += it }
            restarted.awaitNewJobs(vm2) { vm2.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }

            assertEquals(
                "the stamp that says the key was paid was never on disk, so the restarted app paid it again",
                0,
                restarted.submits(),
            )
        }

    @Test
    fun `when the stamp cannot be written to disk the owed key is not paid`() =
        runBlocking {
            val disk = seededDisk()

            val run = processOver(disk, diskWritable = false)
            val vm = run.groupViewModel().also { vms += it }
            run.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }

            assertEquals(
                "the record that stops a second payment could not be saved, but the key was paid anyway",
                0,
                run.submits(),
            )
            assertEquals("the member stays owed on disk", 1, owedOnDisk(disk))
        }

    /** Control, GREEN before and after: with a working disk the owed key is paid once. */
    @Test
    fun `control - a working disk pays the owed key once`() =
        runBlocking {
            val disk = seededDisk()
            val run = processOver(disk)
            val vm = run.groupViewModel().also { vms += it }
            run.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals(1, run.submits())
        }
}
