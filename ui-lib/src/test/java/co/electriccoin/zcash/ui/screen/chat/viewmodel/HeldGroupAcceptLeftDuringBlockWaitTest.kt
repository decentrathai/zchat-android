package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
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
 * Round 8 D11 (Grok CLI review of rounds 6-7c, G1) — leaving a group while its held GROUP_ACCEPT waits for the next
 * block still pays for it.
 *
 * `sendClaimedGroupAccept` checks once, before the send, that the group is still active. The block-aware retry
 * then re-checks, before every attempt, only the wallet generation, the stranger gate, the consent and the stamp,
 * and `beforeSubmit` only the wallet generation. "Leave group" (GroupViewModel.leaveGroup) marks the group inactive
 * and does not touch the held or claimed reply. So: consent is given, the first attempt meets the "funds still
 * confirming" shortfall, the user leaves while the attempt waits for the next block, and the next block pays the
 * "I joined" reply (about 0.00017 ZEC) for a group the user left; the creator then counts us as a member again.
 * The same holds when the user leaves while the attempt builds its transaction.
 *
 * Wanted: the group is re-checked before every attempt and right before the submit. A group that is gone by then is
 * never paid for: its reply is dropped (not held again), its stored copy is removed, and the consent question's
 * queue no longer counts it.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): the paid reply is attempted only after the group screen's Join (it was attempted under
 * the standing consent, claimed by a block). The same guards hold on that path.
 *
 * All addresses, keys and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldGroupAcceptLeftDuringBlockWaitTest {
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
        return JSONObject()
            .put("name", "Synthetic group")
            .put("inviter", inviter)
            .put("group_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
            .put("members", members)
            .toString()
    }

    private fun processInvite(
        vm: ChatViewModel,
        groupId: String,
        inviter: String,
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

    /** A new chain tip reaching [vm], as observeBlockHeight delivers it (not awaited: an attempt may wait). */
    private fun tip(
        vm: ChatViewModel,
        height: Long,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, height)
    }

    /** Round 8 P1: the group screen's Join, not awaited (its attempt may wait for a block). */
    private fun joinNoWait(
        vm: ChatViewModel,
        groupId: String,
    ) {
        val join =
            ChatViewModel::class.java.methods.firstOrNull {
                it.name == "joinGroupInvite" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
                ?: ChatViewModel::class.java.getMethod("joinStrangerGroupInvite", String::class.java)
        assertTrue("fixture sanity: Join released the reply", join.invoke(vm, groupId) as Boolean)
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

    private fun heldInMemory(groupId: String): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAccepts")
            .apply { isAccessible = true }
            .let { (it.get(null) as Map<*, *>).containsKey(groupId) }

    /** The consent question's queue still names [groupId] (queuedAutomaticSetups). */
    private fun queuedFor(groupId: String): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("queuedAutomaticSetups")
            .apply { isAccessible = true }
            .let { field -> (field.get(null) as Collection<*>).any { it.toString().contains(groupId) } }

    private suspend fun screen(
        prefs: ZchatPreferences,
        seam: HandshakeSeam,
        heights: HeightFeed,
        accounts: SelectedAccountFeed,
    ): ChatViewModel = chatViewModelWith(prefs, seam, heights, accountSource = accounts).also(AutoSpendVm::wireBootstrapFields)

    @Test
    fun `a group left while its accept waits for the next block is never paid for`() =
        runBlocking {
            val inviter = "usyntheticd11g1leftwait01"
            val groupId = "GSYNTHD11G1A"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val accounts = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
            val heights = HeightFeed()
            val seam = HandshakeSeam(peer = inviter, failFirstProposalWith = stillConfirming())
            val vm = screen(prefs.proxy, seam, heights, accounts)

            processInvite(vm, groupId, inviter)
            awaitIdle(vm)
            assertTrue("fixture sanity: the accept waits for Join on disk", prefs.needsJoin.containsKey(groupId))
            assertEquals("fixture sanity: nothing is paid without consent", 0, seam.proposalRequests.get())

            // The user taps Join; the attempt meets the "funds still confirming" shortfall, so it waits for the next
            // block.
            joinNoWait(vm, groupId)
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the Join's attempt met the shortfall", 1, seam.proposalRequests.get())

            // The user leaves the group while the attempt waits; then the deposit confirms and a block lands.
            leave(prefs, groupId)
            accounts.ironwoodSpendable()
            heights.advance()
            awaitIdle(vm)

            assertEquals(
                "the reply for a group the user left while it waited for a block was paid for",
                0,
                seam.submits.get(),
            )
            assertFalse("the stored copy of a left group's reply stays on the phone", prefs.heldGroupAccepts.containsKey(groupId))
            assertFalse("a left group's reply is held again", heldInMemory(groupId))
            assertFalse("the consent question still counts a left group's reply", queuedFor(groupId))
            assertFalse("a left group's reply waits for Join again", prefs.needsJoin.containsKey(groupId))

            // Later blocks pay nothing for it either.
            tip(vm, HandshakeHarness.START_HEIGHT + 2)
            awaitIdle(vm)
            tip(vm, HandshakeHarness.START_HEIGHT + 3)
            awaitIdle(vm)
            assertEquals("a later block paid the left group's reply", 0, seam.submits.get())
        }

    @Test
    fun `a group left while its accept builds its transaction is never paid for`() =
        runBlocking {
            val inviter = "usyntheticd11g1leftbuild2"
            val groupId = "GSYNTHD11G1B"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val accounts = SelectedAccountFeed.create()
            val heights = HeightFeed()
            // The user taps Leave while the attempt builds and proves its transaction (seconds on a phone).
            val seam = HandshakeSeam(peer = inviter, onFirstAttempt = { leave(prefs, "GSYNTHD11G1B") })
            val vm = screen(prefs.proxy, seam, heights, accounts)

            processInvite(vm, groupId, inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, groupId)
            assertEquals("fixture sanity: the attempt built its transaction", 1, seam.proposalRequests.get())

            assertEquals(
                "the reply for a group the user left while its transaction was being built was paid for",
                0,
                seam.submits.get(),
            )
            assertFalse("the stored copy of a left group's reply stays on the phone", prefs.heldGroupAccepts.containsKey(groupId))
            assertFalse("a left group's reply is held again", heldInMemory(groupId))
            assertFalse("the consent question still counts a left group's reply", queuedFor(groupId))

            tip(vm, HandshakeHarness.START_HEIGHT + 1)
            awaitIdle(vm)
            assertEquals("a later block paid the left group's reply", 0, seam.submits.get())
        }

    /** Control: the same wait with the group still active pays the reply once (Round 8 P1: after Join). */
    @Test
    fun `control - a group still active after the block wait is paid once`() =
        runBlocking {
            val inviter = "usyntheticd11g1stayed03"
            val groupId = "GSYNTHD11G1C"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val accounts = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
            val heights = HeightFeed()
            val seam = HandshakeSeam(peer = inviter, failFirstProposalWith = stillConfirming())
            val vm = screen(prefs.proxy, seam, heights, accounts)

            processInvite(vm, groupId, inviter)
            awaitIdle(vm)
            joinNoWait(vm, groupId)
            awaitTrue { heights.isWaiting() }
            accounts.ironwoodSpendable()
            heights.advance()
            awaitIdle(vm)
            tip(vm, HandshakeHarness.START_HEIGHT + 2)
            awaitIdle(vm)

            assertEquals(1, seam.submits.get())
            assertTrue(prefs.heldGroupAccepts.isEmpty())
            assertFalse(heldInMemory(groupId))
        }
}
