package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.START_HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 8 D4 (REVIEW_7C F13) — the consent question still counts a group join the user has left.
 *
 * `automaticHandshakeSpendAllowed` counts `groupJoins` (and quotes one transaction each) from
 * `queuedAutomaticSetups`, which is process-wide. A GROUP_ACCEPT leaves it only when it is sent, dropped at a claim,
 * on a phrase change or on Allow. Without consent, only the FIRST chain tip after a start claims the held accepts
 * (and drops a left group's). So when that first tip has already run, then an invite arrives, the user answers
 * "Not now" and leaves the group, no later tip drops it, and the next question (a ZBOOT, say) still says a group
 * join is waiting and quotes its transaction, although Allow can never pay it.
 *
 * Wanted: the question counts a GROUP_ACCEPT only while its reply is held for a group that is still active.
 * Round 8 P1 (PHONE_TRIAGE F3): the question counts no GROUP_ACCEPT at all; a paid group reply waits for Join.
 * All addresses, keys and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentQuestionSkipsLeftJoinAfterFirstBlockTest {
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

    private fun question(vm: ChatViewModel): SendMessageState.NeedsHandshakeSpendConsent {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("the consent question must be raised, but the screen shows $state", state is SendMessageState.NeedsHandshakeSpendConsent)
        return state as SendMessageState.NeedsHandshakeSpendConsent
    }

    /** "Leave group": the group info stays, marked inactive (GroupViewModel.leaveGroup). */
    private fun leave(
        prefs: AutoSpendPrefs,
        groupId: String,
    ) {
        val info = ZMSGGroupProtocol.deserializeGroupInfo(prefs.groupInfo.getValue(groupId))
        assertTrue("fixture sanity: the joined group reads back", info != null)
        prefs.groupInfo[groupId] = ZMSGGroupProtocol.serializeGroupInfo(info!!.copy(isActive = false))
    }

    @Test
    fun `a join left after the first block and a Not now is not counted by a later question`() =
        runBlocking {
            val inviter = "usyntheticd4f13inviter01"
            val peer = "usyntheticd4f13zbootpeer1"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            // The first tip after the start has run: later tips without consent do not claim held accepts.
            chainTip(vm, START_HEIGHT + 1)
            processInvite(vm, "GD4F13LEFT", plaintextInvite(inviter))
            awaitIdle(vm)
            // Round 8 P1: the join is asked about by Join (it waits for Join), not by the consent question.
            assertTrue("fixture sanity: the join waits for Join", prefs.needsJoin.containsKey("GD4F13LEFT"))
            vm.dismissOrchardShieldingWarning() // "Not now"
            leave(prefs, "GD4F13LEFT")
            chainTip(vm, START_HEIGHT + 2)
            assertTrue(
                "fixture sanity: a later tip without consent leaves the waiting reply alone",
                prefs.needsJoin.containsKey("GD4F13LEFT"),
            )

            prefs.session(peer, complete = true, mode = ConversationMode.TUNNEL)
            AutoSpendVm.setHistoryRead(true)
            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)
            assertEquals("nothing may be paid without consent", 0, seam.proposalRequests.get())
            val asked = question(vm)
            assertEquals("the question says a group join is waiting, but that group was left", 0, asked.groupJoins)
            assertEquals("the question quotes the left group's reply", 1, asked.pendingPeers)
        }

    /**
     * Control: the same join, still active. Round 8 P1: it is not counted or quoted either (it was: 1 group join, 2
     * transactions); it waits for Join.
     */
    @Test
    fun `control - a join still active after the first block waits for Join and is not counted by a later question`() =
        runBlocking {
            val inviter = "usyntheticd4f13inviter02"
            val peer = "usyntheticd4f13zbootpeer2"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            chainTip(vm, START_HEIGHT + 1)
            processInvite(vm, "GD4F13HELD", plaintextInvite(inviter))
            awaitIdle(vm)
            vm.dismissOrchardShieldingWarning()
            chainTip(vm, START_HEIGHT + 2)

            prefs.session(peer, complete = true, mode = ConversationMode.TUNNEL)
            AutoSpendVm.setHistoryRead(true)
            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)
            val asked = question(vm)
            assertEquals("the question counts a group join, which only Join may pay", 0, asked.groupJoins)
            assertEquals("the question quotes a group join, which only Join may pay", 1, asked.pendingPeers)
            assertTrue("the join must wait for Join", prefs.needsJoin.containsKey("GD4F13HELD"))
        }
}
