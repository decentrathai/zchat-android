package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.KeptAdminChange
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-598 (train-T7a2 review of 14 Oct, finding 6; the ZC-952 bounds) — the kept changes held in memory after the
 * store refused their write (`ChatViewModel.keptChangesNotWritten`) keep the kept list's bounds
 * ([co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck.withKeptChange]) while the store goes on refusing.
 *
 * One chat sends twelve signed removals at rising epochs to a group that cannot confirm its admin, and the store
 * refuses every write: the list keeps a chat's eight highest removals, and the held list must hold the same eight,
 * not all twelve. Before: the held list is the earlier one plus each new entry, with no bound.
 *
 * All addresses, keys, ids and times are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldKeptChangesKeepTheListBoundsTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    /** The entries held in memory for [GID] after refused writes (empty when none). */
    private fun heldIds(): List<String> {
        val field =
            ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "keptChangesNotWritten" }
                ?: throw AssertionError("fixture: ChatViewModel has no keptChangesNotWritten")
        field.isAccessible = true
        val held = (field.get(null) as Map<*, *>)[GID] as Pair<*, *>? ?: return emptyList()
        return (held.second as List<*>).map { (it as KeptAdminChange).id }
    }

    @Test
    fun `the held list keeps the bounds of the kept list while the store refuses every write`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.base.nostrKeys[Y] = "5e".repeat(32)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            assertEquals("fixture: the group does not read UNKNOWN", "UNKNOWN", AdminCheck.keyState(vm))
            p.announceWritesRefused = true

            for (epoch in 2..13) {
                val removal = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = epoch, newKey = null)
                val time = Instant.ofEpochMilli(T + epoch * 1_000L)
                AdminCheck.relayDeliver(vm, removal, Y, "synthetic-nit6-kick-$epoch", time)
            }

            val listed = AdminCheck.keptChanges(p).map { it.getString("id") }
            assertEquals("fixture: the kept list does not hold the chat's eight highest removals", 8, listed.size)
            assertEquals("the held list does not keep the kept list's bounds", listed.sorted(), heldIds().sorted())
        }
}
