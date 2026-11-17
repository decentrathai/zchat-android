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
 * R2-2 (d), sub-item of p2-e357106bb0b6 — a GROUP_ACCEPT held for consent is never sent.
 *
 * P2b put the GROUP_ACCEPT (our "I joined" answer to a group invite, one paid transaction) behind the
 * handshake-spend consent. When the invite arrives before the user has agreed, the accept is not sent and
 * the prompt is shown. At b1f45ab32 it is also never sent AFTER the user agrees: AutomaticSetupKind.
 * GROUP_ACCEPT is "not retried", nothing keeps the memo, and the invite is not processed again (the group
 * already exists). The inviter keeps us INVITED and fans no group message out to us.
 *
 * Wanted: consent refused ("Not now"), then granted, sends the held GROUP_ACCEPT exactly once, never twice,
 * however many times consent is granted and from whichever chat screen.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): the standing consent no longer pays a group join at all. The reply waits for the group
 * screen's Join, which sends it exactly once, never twice, however many times Join or the consent is given and from
 * whichever screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAcceptResentAfterConsentTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        resetProcessState()
    }

    private fun resetProcessState() {
        AutoSpendVm.resetProcessState()
        val held = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "heldGroupAccepts" } ?: return
        held.isAccessible = true
        (held.get(null) as? MutableMap<*, *>)?.clear()
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

    @Test
    fun `a GROUP_ACCEPT waiting for Join is sent exactly once after Join, and never by the consent`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptheld01"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val listVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            val detailVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(listVm, "GSYNTH21", inviter)
            awaitIdle(listVm)
            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTH21"))
            assertEquals("no GROUP_ACCEPT may be paid without consent", 0, seam.proposalRequests.get())
            assertTrue("the user must be asked (the reply waits for Join)", prefs.needsJoin.containsKey("GSYNTH21"))
            assertFalse(
                "a group join raised the standing consent question",
                HandshakeHarness.sendMessageState(listVm) is SendMessageState.NeedsHandshakeSpendConsent,
            )

            listVm.grantHandshakeSpendConsent()
            awaitIdle(listVm)
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())

            assertTrue(GroupInviteDriver.join(listVm, "GSYNTH21"))
            assertEquals(
                "the GROUP_ACCEPT was never sent after Join; the inviter keeps us INVITED and sends us no group message",
                1,
                seam.proposalRequests.get(),
            )

            // Joining or granting again, from this screen or another one, must not pay a second accept.
            GroupInviteDriver.join(listVm, "GSYNTH21")
            GroupInviteDriver.join(detailVm, "GSYNTH21")
            listVm.grantHandshakeSpendConsent()
            detailVm.grantHandshakeSpendConsent()
            awaitIdle(listVm, detailVm)
            assertEquals("the GROUP_ACCEPT was paid twice", 1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
        }

    /** Control: with consent already given, Join pays one GROUP_ACCEPT, once (Round 8 P1: the invite paid it). */
    @Test
    fun `control - with consent Join pays one GROUP_ACCEPT and granting again pays nothing more`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptheld02"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH22", inviter)
            awaitIdle(vm)
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())
            GroupInviteDriver.join(vm, "GSYNTH22")
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
        }
}
