package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CREATOR_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Collections

/**
 * KI-25 R17 (pair 3), review of 14 Oct finding 3 — the sync-keys tap of a group's chat reaches the relay-only key
 * request of an Open group that cannot confirm its admin.
 *
 * Before: the tap (AndroidChat's `onSyncKeys`) opened Group settings whenever the group needed the confirmation, before
 * `requestGroupKeySync` was called, so `requestKeyOverRelaysOnly` never ran from the screen. Wanted: the tap's decision
 * is `GroupViewModel.syncKeysTapOpensSettings(groupId)` (true: open Group settings, nothing asked or sent), and
 * `onSyncKeys` does what it answers.
 *
 * - (a) an Open group (plaintext-key join, no creator key) that reads UNKNOWN: Group settings do not open, the key
 *   request goes once over the relays to the creator's relay key, no price question, no proposal;
 * - (b) control: a group joined through a key exchange that reads UNKNOWN still opens Group settings and sends nothing;
 * - (c) the screen's `onSyncKeys` calls the decision and opens Group settings only on its answer.
 *
 * At the base all three fail: the operation does not exist and the tap decides by itself. Nothing is paid by any case;
 * all values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncKeysTapReachesTheRelayOnlyRequestTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

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

    /** `GroupViewModel.syncKeysTapOpensSettings(groupId)`, the sync-keys tap's decision. */
    private fun tap(vm: GroupViewModel): Boolean {
        val method =
            GroupViewModel::class.java.declaredMethods.firstOrNull { it.name == "syncKeysTapOpensSettings" && it.parameterCount == 1 }
                ?: throw AssertionError("no such operation: GroupViewModel.syncKeysTapOpensSettings(groupId: String): Boolean")
        return try {
            method.invoke(vm, GID) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `a - an Open group that cannot confirm its admin asks over the relays only and opens nothing`() =
        runBlocking {
            val p = AdminCheck.plainKeyPhone()
            p.base.link(X_OLD)
            p.base.states[GID] = p.base.state(GID).copy(rosterQueryAtMs = T)
            AdminCheck.relayRecorder(published)
            val f = GroupPaidOnceFixture().also { it.scheduler = scheduler }
            val screen = AdminCheck.groupVm(p, f)
            assertEquals("fixture: the Open group does not read UNKNOWN", "UNKNOWN", AdminCheck.keyState(screen))

            var opens = true
            f.awaitNewJobs(screen) { opens = tap(screen) }

            assertFalse("the tap opened Group settings instead of asking over the relays", opens)
            assertNull("the key request asked a price", screen.paidActionConfirmation.value)
            val relayKeys = published.map { it.second }
            assertEquals("the key request did not go once over the relays to the creator", listOf(CREATOR_NOSTR), relayKeys)
            assertEquals("a payment was proposed", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `b - control - a group joined through a key exchange that cannot confirm its admin opens Group settings`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            AdminCheck.relayRecorder(published)
            val f = GroupPaidOnceFixture().also { it.scheduler = scheduler }
            val screen = AdminCheck.groupVm(p, f)
            assertEquals("fixture: the group does not read UNKNOWN", "UNKNOWN", AdminCheck.keyState(screen))

            var opens = false
            f.awaitNewJobs(screen) { opens = tap(screen) }

            assertTrue("the tap did not open Group settings", opens)
            assertNull(screen.paidActionConfirmation.value)
            assertEquals("something was published", emptyList<String>(), published.map { it.second })
            assertEquals("a payment was proposed", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `c - the screen's sync-keys tap does what the decision answers`() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val start = src.indexOf("onSyncKeys = {")
        assertTrue("fixture: onSyncKeys in AndroidChat.kt", start >= 0)
        val end = src.indexOf("onJoinInvite = {", start)
        assertTrue("fixture: onJoinInvite after onSyncKeys in AndroidChat.kt", end > start)
        val tap = src.substring(start, end)
        assertTrue("the tap does not ask GroupViewModel.syncKeysTapOpensSettings", tap.contains("syncKeysTapOpensSettings(groupId)"))
        assertFalse("the tap still decides by itself from the admin's key state", tap.contains("needsConfirmation"))
    }
}
