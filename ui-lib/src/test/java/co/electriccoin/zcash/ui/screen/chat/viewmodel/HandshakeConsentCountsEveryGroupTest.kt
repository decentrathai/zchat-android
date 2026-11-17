package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.QueuedAutomaticSetup
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.automaticSetupTransactions
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * GPT-6 F5 (22 Sep) — the handshake-spend consent prompt counts several groups as one.
 *
 * A queued automatic setup is its kind plus its target, and a GROUP_ACCEPT's target is the INVITER. Two
 * invites from the same person to two different groups are two paid "I joined" transactions, but they are
 * the same (kind, target) pair, so they collapse — in the set of queued setups, and again in the `toSet()`
 * inside `automaticSetupTransactions`. The prompt then quotes one transaction and one HANDSHAKE_COST_ZAT
 * while agreeing pays two: exactly the understated bill this consent exists to prevent.
 *
 * Wanted: each group's own action is counted and quoted. The number in the prompt is the number of
 * transactions the user is agreeing to pay for, and agreeing pays that many.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): a group join no longer goes through the consent prompt at all. Each group's paid reply
 * waits for its own Join (its own needs-join record and price), agreeing to the prompt pays none of them, and each
 * Join pays exactly its own one. The counting rule below is unchanged.
 *
 * All addresses, keys and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeConsentCountsEveryGroupTest {
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

    @Test
    fun `two group invites from one person wait for two Joins, raise no prompt, and two Joins pay two transactions`() =
        runBlocking {
            val inviter = "usyntheticf5inviter01"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTHF1", inviter)
            awaitIdle(vm)
            processInvite(vm, "GSYNTHF2", inviter)
            awaitIdle(vm)

            assertTrue("fixture sanity: the first group was joined", prefs.groupInfo.containsKey("GSYNTHF1"))
            assertTrue("fixture sanity: the second group was joined", prefs.groupInfo.containsKey("GSYNTHF2"))
            assertEquals("nothing may be paid without consent", 0, seam.proposalRequests.get())

            noPrompt(vm)
            assertTrue("the first group's reply must wait for its own Join", prefs.needsJoin.containsKey("GSYNTHF1"))
            assertTrue("the second group's reply must wait for its own Join", prefs.needsJoin.containsKey("GSYNTHF2"))

            // Agreeing pays no group join; each Join pays exactly its own one.
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())
            GroupInviteDriver.join(vm, "GSYNTHF1")
            assertEquals(1, seam.proposalRequests.get())
            GroupInviteDriver.join(vm, "GSYNTHF2")
            assertEquals(2, seam.proposalRequests.get())
        }

    /**
     * Control (passes before and after): two invites from two DIFFERENT people already quote two, because
     * their targets differ. It fails if the fixture stops reaching the prompt for a reason of its own.
     */
    @Test
    fun `control - two group invites from two people wait for two Joins and raise no prompt`() =
        runBlocking {
            val first = "usyntheticf5inviter02"
            val second = "usyntheticf5inviter03"
            val prefs =
                AutoSpendPrefs(consent = false).apply {
                    session(first, complete = true)
                    session(second, complete = true)
                }
            val seam = HandshakeSeam(first)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTHF3", first)
            awaitIdle(vm)
            processInvite(vm, "GSYNTHF4", second)
            awaitIdle(vm)

            noPrompt(vm)
            assertEquals(setOf("GSYNTHF3", "GSYNTHF4"), prefs.needsJoin.keys.toSet())
            assertEquals(0, seam.proposalRequests.get())
        }

    /** Control (passes before and after): one invite quotes one transaction and pays one. */
    @Test
    fun `control - one group invite waits for Join and its Join pays one transaction`() =
        runBlocking {
            val inviter = "usyntheticf5inviter04"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTHF5", inviter)
            awaitIdle(vm)

            noPrompt(vm)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())
            GroupInviteDriver.join(vm, "GSYNTHF5")
            assertEquals(1, seam.proposalRequests.get())
        }

    /** The counting rule itself: what separates two paid transactions, and what does not. */
    @Test
    fun `the count separates group actions by their own scope and keeps peer setups per peer`() {
        val inviter = "usyntheticf5inviter05"
        assertEquals(
            "two groups from one inviter are two paid accepts",
            2,
            automaticSetupTransactions(
                listOf(
                    QueuedAutomaticSetup(AutomaticSetupKind.GROUP_ACCEPT, inviter, "GSYNTHF6"),
                    QueuedAutomaticSetup(AutomaticSetupKind.GROUP_ACCEPT, inviter, "GSYNTHF7"),
                ),
                emptyList(),
            ),
        )
        assertEquals(
            "the same group queued twice is still one accept",
            1,
            automaticSetupTransactions(
                listOf(
                    QueuedAutomaticSetup(AutomaticSetupKind.GROUP_ACCEPT, inviter, "GSYNTHF6"),
                    QueuedAutomaticSetup(AutomaticSetupKind.GROUP_ACCEPT, inviter, "GSYNTHF6"),
                ),
                emptyList(),
            ),
        )

        val member = "usyntheticf5member01"
        assertEquals(
            "two group keys owed to one member are two paid grants",
            2,
            automaticSetupTransactions(
                listOf(
                    QueuedAutomaticSetup(AutomaticSetupKind.GROUP_KEY_GRANT, member, "GSYNTHF8:req-a"),
                    QueuedAutomaticSetup(AutomaticSetupKind.GROUP_KEY_GRANT, member, "GSYNTHF9:req-b"),
                ),
                emptyList(),
            ),
        )

        val peer = "usyntheticf5peer01"
        val peerSetups =
            listOf(
                QueuedAutomaticSetup(AutomaticSetupKind.KEX, peer),
                QueuedAutomaticSetup(AutomaticSetupKind.ZBOOT, peer),
            )
        assertEquals("one key leg plus one ZBOOT for the peer", 2, automaticSetupTransactions(peerSetups, emptyList()))
        assertEquals("a due ZBOOT for the same peer is not counted twice", 2, automaticSetupTransactions(peerSetups, listOf(peer)))
    }

    /** Round 8 P1: a group join raises no consent prompt (its paid reply waits for Join). */
    private fun noPrompt(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("a group join raised the consent prompt: $state", state !is SendMessageState.NeedsHandshakeSpendConsent)
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
}
