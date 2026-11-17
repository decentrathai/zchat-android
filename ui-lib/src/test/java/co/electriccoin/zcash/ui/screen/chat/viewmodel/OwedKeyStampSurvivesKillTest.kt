package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 D11 (Grok CLI review of rounds 6-7c, G4) — the owed-key stamp can stay cleared on disk after nothing was
 * sent, so the member is never pushed the group key again automatically.
 *
 * `retryPendingGroupKeysInternal` clears the member's `pendingKeyEpoch` durably (commit) BEFORE the send, and puts it
 * back after a proven non-send with `apply()`. So:
 * - (a) a send that provably sent nothing (refused before the submit, or a submit rejected with nothing stored)
 *   leaves the member owed in memory only: a kill before `apply()` reaches the disk, and the restarted app reads the
 *   member as up to date;
 * - (b) a kill while the send waits for the next block (the "funds still confirming" shortfall, nothing built or
 *   sent yet) leaves the disk saying the key was delivered.
 * Either way the admin's automatic repair never re-sends it; the member can only pull it ("Tap to sync group keys").
 *
 * Wanted: the stamp goes to disk right before the attempt that is really submitted, and a proven non-send puts the
 * member back as owed with a checked write. A second payment stays impossible (OwedKeyStampIsDurableBeforePayTest).
 *
 * The group-members file is a real [ZchatPreferencesImpl] over a file whose memory and disk are kept apart:
 * `apply()` changes memory only, `commit()` writes the disk. A "kill" is a fresh process built from the disk alone.
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwedKeyStampSurvivesKillTest {
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

    /** A preferences file with a separate memory and disk (the OwedKeyStampIsDurableBeforePayTest idiom). */
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

    private fun processOver(
        disk: MutableMap<String, String>,
        firstSubmitOutcome: SubmitResult = SubmitResult.Success(txIds = listOf("synthetic-first-txid")),
        failFirstProposalWith: Exception? = null,
        selectedAccount: (suspend () -> WalletAccount)? = null,
    ): GroupPaidOnceFixture =
        GroupPaidOnceFixture(
            memberPendingKeyEpoch = 1,
            consent = true,
            membersStore = storeOver(SplitPrefs(disk)),
            firstSubmitOutcome = firstSubmitOutcome,
            failFirstProposalWith = failFirstProposalWith,
            selectedAccount = selectedAccount,
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

    /** After a kill: a fresh process over [disk] opens the group, and the automatic repair runs once. */
    private suspend fun submitsAfterRestart(disk: MutableMap<String, String>): Int {
        val restarted = processOver(disk)
        val vm = restarted.groupViewModel().also { vms += it }
        restarted.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
        return restarted.submits()
    }

    // ── (a) a proven non-send ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a repair refused before its submit leaves the member owed on disk`() =
        runBlocking {
            val disk = seededDisk()
            val run = processOver(disk, failFirstProposalWith = PublicCrossingException(Zatoshi(90_000), Zatoshi(0)))
            val vm = run.groupViewModel().also { vms += it }
            run.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("fixture sanity: nothing reached the submit", 0, run.submits())

            assertEquals(
                "nothing was sent, but the disk says the key was delivered: after a kill the member is never " +
                    "pushed the key again",
                1,
                owedOnDisk(disk),
            )
            assertEquals("after a kill the next group open re-sends the owed key once", 1, submitsAfterRestart(disk))
        }

    @Test
    fun `a repair whose submit was rejected with nothing stored leaves the member owed on disk`() =
        runBlocking {
            val disk = seededDisk()
            val run =
                processOver(
                    disk,
                    firstSubmitOutcome = SubmitResult.Failure(txIds = emptyList(), code = -1, description = "synthetic nothing stored"),
                )
            val vm = run.groupViewModel().also { vms += it }
            run.awaitNewJobs(vm) { vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID) }
            assertEquals("fixture sanity: the one submit was rejected", 1, run.submits())

            assertEquals(
                "the submit stored nothing, but the disk says the key was delivered: after a kill the member is " +
                    "never pushed the key again",
                1,
                owedOnDisk(disk),
            )
            assertEquals("after a kill the next group open re-sends the owed key once", 1, submitsAfterRestart(disk))
        }

    // ── (b) a kill while the send waits for the next block ───────────────────────────────────────────────────

    @Test
    fun `a kill while the repair waits for the next block leaves the member owed on disk`() =
        runBlocking {
            val disk = seededDisk()
            val confirming = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
            val run =
                processOver(
                    disk,
                    failFirstProposalWith = HandshakeHarness.stillConfirming(),
                    selectedAccount = { confirming.getSelectedAccount() },
                )
            val vm = run.groupViewModel().also { vms += it }
            val before: Set<Job> =
                vm.viewModelScope.coroutineContext.job.children
                    .toSet()
            vm.loadGroupDetail(GroupPaidOnceFixture.GROUP_ID)
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                while (run.seam.proposalRequests.get() < 1) {
                    scheduler.advanceUntilIdle()
                    delay(5)
                }
            }
            // The attempt met the shortfall and now waits for a block that never comes in this process.
            delay(200)
            assertTrue(
                "fixture sanity: the repair is still waiting for the next block",
                vm.viewModelScope.coroutineContext.job.children
                    .any { it !in before && it.isActive },
            )
            assertEquals("fixture sanity: nothing reached the submit", 0, run.submits())

            // The process is killed here.
            assertEquals(
                "nothing was built or sent, but the disk already says the key was delivered: after a kill the " +
                    "member is never pushed the key again",
                1,
                owedOnDisk(disk),
            )
            vm.viewModelScope.cancel()
            assertEquals("after a kill the next group open re-sends the owed key once", 1, submitsAfterRestart(disk))
        }
}
