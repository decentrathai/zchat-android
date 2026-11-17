package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * R3-4 (round 2 report, section 4 item 13, first bullet) — a held GROUP_ACCEPT is lost when the app is killed.
 *
 * R2-2 (d) holds our paid GROUP_ACCEPT (our "I joined" answer to a group invite, one on-chain transaction) while
 * the user has not agreed to pay for automatic handshakes, and sends it once when they agree. The held accept
 * lives in a process-wide map only. If the app is killed before the user agrees, it is gone: the invite is not
 * processed again (the group already exists), nothing asks again, and agreeing later sends nothing. The inviter
 * keeps us INVITED and fans no group message out to us.
 *
 * Wanted: after a kill, the first chain tip re-offers the held accept (the consent prompt is raised again and
 * nothing is paid); agreeing then sends it exactly once, and a later restart does not pay it again. A held
 * accept for a group that was left before the restart is dropped, never paid.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): a group join is no longer paid under the standing consent. A reply that cannot go free
 * waits for Join (a needs-join record on disk), which survives a kill; Join sends it once; a later restart pays
 * nothing more. A reply an earlier build held for the consent becomes a needs-join record at the first block.
 *
 * The "kill" drops every process-wide map and flag (AutoSpendVm.resetProcessState) and builds a new
 * ChatViewModel over the same preferences, which stand in for the on-disk stores.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAcceptHeldSurvivesKillTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun invitePayload(inviter: String): String {
        val members = JSONArray()
        members.put(inviter)
        members.put(HandshakeHarness.SELF)
        val invite = JSONObject()
        invite.put("name", "Synthetic group")
        invite.put("inviter", inviter)
        invite.put("group_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        invite.put("members", members)
        return invite.toString()
    }

    private fun processInvite(
        vm: ChatViewModel,
        groupId: String,
        inviter: String
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(
                "processGroupInvite",
                String::class.java,
                String::class.java,
                TransactionId::class.java,
                Instant::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            .invoke(vm, groupId, invitePayload(inviter), null, Instant.EPOCH, null, null)
    }

    /** A new chain tip reaching [vm], as observeBlockHeight delivers it. */
    private fun chainTip(
        vm: ChatViewModel,
        height: Long = HandshakeHarness.START_HEIGHT
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, height)
    }

    /** The app is killed and started again: a new ChatViewModel over the same stores, which sees a chain tip. */
    private suspend fun restart(
        prefs: AutoSpendPrefs,
        seam: HandshakeSeam
    ): ChatViewModel {
        AutoSpendVm.resetProcessState()
        val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
        chainTip(vm)
        awaitIdle(vm)
        return vm
    }

    @Test
    fun `a GROUP_ACCEPT waiting for Join survives a kill, still waits for Join and is sent once after Join`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptkill01"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val before = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(before, "GSYNTH41", inviter)
            awaitIdle(before)
            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTH41"))
            assertEquals("no GROUP_ACCEPT may be paid without consent", 0, seam.proposalRequests.get())

            val after = restart(prefs, seam)
            assertEquals("a restart must not pay the waiting GROUP_ACCEPT", 0, seam.proposalRequests.get())
            assertTrue(
                "the GROUP_ACCEPT waiting for Join was lost when the app was killed: nothing asks again",
                prefs.needsJoin.containsKey("GSYNTH41"),
            )

            after.grantHandshakeSpendConsent()
            awaitIdle(after)
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())
            assertTrue(GroupInviteDriver.join(after, "GSYNTH41"))
            assertEquals(
                "the GROUP_ACCEPT was not sent after a restart and Join; the inviter keeps us INVITED",
                1,
                seam.proposalRequests.get(),
            )

            // Granting or joining again, and another restart with consent given, must not pay a second accept.
            after.grantHandshakeSpendConsent()
            awaitIdle(after)
            val again = restart(prefs, seam)
            again.grantHandshakeSpendConsent()
            awaitIdle(again)
            GroupInviteDriver.join(again, "GSYNTH41")
            assertEquals("the GROUP_ACCEPT was paid twice", 1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertTrue("a sent accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
        }

    @Test
    fun `a held GROUP_ACCEPT whose group was left before the restart is dropped and never paid`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptkill02"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val before = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(before, "GSYNTH42", inviter)
            awaitIdle(before)
            prefs.groupInfo.remove("GSYNTH42") // the user left the group (its info is gone) before the kill

            val after = restart(prefs, seam)
            assertFalse(
                "a left group's accept must not be offered",
                HandshakeHarness.sendMessageState(after) is SendMessageState.NeedsHandshakeSpendConsent,
            )
            after.grantHandshakeSpendConsent()
            awaitIdle(after)
            // Round 8 P1: nor answered by Join.
            GroupInviteDriver.join(after, "GSYNTH42")
            assertEquals("a left group's accept must never be paid", 0, seam.proposalRequests.get())
        }

    /** Control: with consent already given, Join pays one accept; a restart pays none (Round 8 P1: the invite paid it). */
    @Test
    fun `control - with consent Join pays one GROUP_ACCEPT and a restart pays nothing more`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptkill03"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH43", inviter)
            awaitIdle(vm)
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())
            GroupInviteDriver.join(vm, "GSYNTH43")
            assertEquals(1, seam.proposalRequests.get())

            val after = restart(prefs, seam)
            after.grantHandshakeSpendConsent()
            awaitIdle(after)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
        }
}
