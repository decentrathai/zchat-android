package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-23 (ZC-020; SPEC D4, §6) — the count and the daily budget of automatic key answers never touch the creator's own
 * key deliveries. A control: green before and after the fix.
 *
 * With the daily budget used up and the member's paid count at 1, the group-open owed-key pass (under the standing
 * consent) and the creator's quoted "sync group keys" tap still pay once per owed member.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserTappedKeySyncIgnoresGrantBudgetTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private fun world(): KeyGrantWorld =
        KeyGrantWorld(scheduler).also { w ->
            w.store.budgetUsedUp = true
            w.store.counts[w.store.grantKey(MA3.A)] = 1
            w.store.counts[w.store.grantKey(MA3.B)] = 1
        }

    @Test
    fun `control - the owed-key pass at a group open pays once per owed member`() =
        runBlocking {
            val w = world()
            w.world.setRow(MA3.A) { it.copy(pendingKeyEpoch = 1) }
            val s = w.screen()

            w.world.open(s)

            assertEquals(1, w.world.proposalsOf(GroupMessageType.GROUP_KEY, MA3.A))
            assertEquals(1, w.submits())
            assertTrue("the owed-key pass asked the grant budget", w.store.consumeCalls.isEmpty())
        }

    @Test
    fun `control - the creator's quoted sync-keys tap pays once per owed member`() =
        runBlocking {
            val w = world()
            w.world.setRow(MA3.A) { it.copy(pendingKeyEpoch = 1) }
            w.world.setRow(MA3.B) { it.copy(pendingKeyEpoch = 1) }
            w.store.consent = false
            val s = w.screen()

            w.world.run(s) { s.vm.requestGroupKeySync(MA3.GID) }
            assertEquals("GROUP_KEY_SYNC", w.world.question(s))
            assertEquals(0, w.world.seam.proposals.size)
            w.world.confirm(s)

            assertEquals(1, w.world.proposalsOf(GroupMessageType.GROUP_KEY, MA3.A))
            assertEquals(1, w.world.proposalsOf(GroupMessageType.GROUP_KEY, MA3.B))
            assertEquals(2, w.submits())
            assertTrue(w.store.consumeCalls.isEmpty())
        }
}
