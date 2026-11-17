package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * QA2 Q2-57b (ZC-922, W-137 B-4): every group invitation that carries a key waits for the user's Join, whoever sent it.
 *
 * At 33bb92cdf only a stranger's invite waits (processGroupInviteLocked: `stranger = !keyFromKeyExchange &&
 * authenticatedSender == null && isFirstContact(inviter)`, `waitsForJoin = stranger && groupKeyBase64 != null`). The
 * Seeker, put into a group by a contact, found itself in it with no question, no note and "0 unread": a relay invite
 * sealed by a known contact's relay key joined at once and sent the "I joined" reply on its own, and so did an invite
 * over a finished key exchange. W-137 decided that nobody can put a user into a group without Join.
 *
 * Wanted (FABLE_BEHAVIOUR_DESIGN B-4, purely local): `waitsForJoin = groupKeyBase64 != null` (own-address and invalid
 * inviter stay refused). For a known contact's invite (an authenticated relay sender, a saved contact, an E2E chat, a
 * finished key exchange):
 * - the group is saved and marked as waiting for Join (the list shows the invitation, the group screen the banner);
 * - nothing is sent and nothing is paid: no GROUP_ACCEPT over the relays, no relay-key lookup, nothing built for the
 *   chain, nothing held for the automatic sends;
 * - Join sends the existing GROUP_ACCEPT, free over the relays when they take it; a reply that has to be paid keeps its
 *   own quote (KnownContactPaidGroupInviteWaitsForJoinTest, PaidGroupActionsAskFirstTest);
 * - Ignore (Decline) deletes the group locally and sends nothing, ever; no wire change (compat-2124).
 *
 * All addresses, keys, group ids and event ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EveryGroupInviteWaitsForJoinTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    private suspend fun screen(
        prefs: ZchatPreferences,
        seam: HandshakeSeam,
        saved: Set<String> = emptySet(),
    ): ChatViewModel =
        chatViewModelWith(prefs, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "contactBook", SavedContacts(saved))
        }

    /** The invite handler as NostrChatBridge's inbound collector calls it: the seal-authenticated sender is [sender]. */
    private fun processRelayInvite(
        vm: ChatViewModel,
        groupId: String,
        payload: String,
        sender: String,
    ) {
        try {
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
                .invoke(vm, groupId, payload, null, Instant.EPOCH, sender, "synthetic-wrap-$groupId")
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun relayAcks(relayed: MutableList<Pair<String, String>>) =
        NostrChatBridge.registerPublisher { memo, recipient ->
            relayed += memo to recipient
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-relay-ack-${relayed.size}")
        }

    private fun accepts(relayed: List<Pair<String, String>>) =
        relayed.filter { ZMSGGroupProtocol.parseMessageType(it.first) == GroupMessageType.GROUP_ACCEPT }

    /** The exact predicate the chat list uses for groupsAwaitingJoin (ChatViewModel.groupInviteNeedsJoin). */
    private fun awaitingJoin(
        vm: ChatViewModel,
        groupId: String,
    ): Boolean =
        ChatViewModel::class.java
            .getDeclaredMethod("groupInviteNeedsJoin", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, groupId) != null

    /** [delegate] with a relay key for [peer], as an accepted chat stores it, so a free relay reply could go. */
    private fun withRelayKey(
        delegate: ZchatPreferences,
        peer: String,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when {
                method.name == "getPeerNostrPubkey" && args?.get(0) == peer -> {
                    method.invoke(delegate, *args)
                    "cd".repeat(32)
                }
                method.name == "hashCode" -> System.identityHashCode(p)
                method.name == "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private fun assertWaitsAndSentNothing(
        prefs: StrangerInvitePrefs,
        seam: HandshakeSeam,
        relayed: List<Pair<String, String>>,
        vm: ChatViewModel,
        groupId: String,
        what: String,
    ) {
        assertTrue("$what: the group must be saved", prefs.base.groupInfo.containsKey(groupId))
        assertTrue("$what: the invite must be marked as waiting for Join", prefs.needsJoin.containsKey(groupId))
        assertTrue("$what: the chat list must show the invitation as waiting for Join", awaitingJoin(vm, groupId))
        assertEquals("$what: a GROUP_ACCEPT went over the relays before Join", emptyList<Any>(), accepts(relayed))
        assertEquals("$what: the free relay reply was even tried (relay-key lookup)", 0, prefs.nostrLookups.get())
        assertEquals("$what: a GROUP_ACCEPT was built for the chain", 0, seam.proposalRequests.get())
        assertEquals("$what: a GROUP_ACCEPT was submitted", 0, seam.submits.get())
        assertFalse("$what: the reply is held for the automatic sends", prefs.base.heldGroupAccepts.containsKey(groupId))
    }

    // ---- a relay invite sealed by a known contact's relay key -----------------------------------------------------

    @Test
    fun `a relay invite from an authenticated known contact waits for Join, sends nothing, and Join sends exactly one free reply`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 9_221)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(inviter)
            val vm = screen(withRelayKey(prefs.proxy, inviter), seam)
            val relayed = mutableListOf<Pair<String, String>>()
            relayAcks(relayed)

            processRelayInvite(vm, "GKNOWNR1", plaintextInvite(inviter), sender = inviter)
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertTrue("fixture sanity: the group is saved", prefs.base.groupInfo.containsKey("GKNOWNR1"))
            assertTrue(
                "an invite from a known contact joined at once: no Join record was written",
                prefs.needsJoin.containsKey("GKNOWNR1"),
            )
            assertTrue("the chat list must show the invitation as waiting for Join", awaitingJoin(vm, "GKNOWNR1"))
            assertEquals("a GROUP_ACCEPT went over the relays before Join", emptyList<Any>(), accepts(relayed))
            assertEquals("nothing may be built for the chain before Join", 0, seam.proposalRequests.get())
            assertEquals("nothing may be paid before Join", 0, seam.submits.get())
            assertFalse("the reply is held for the automatic sends", prefs.base.heldGroupAccepts.containsKey("GKNOWNR1"))

            assertTrue("Join must succeed", GroupInviteDriver.join(vm, "GKNOWNR1"))
            assertEquals("Join must send the \"I joined\" reply once over the relays", 1, accepts(relayed).size)
            assertEquals("a relay reply must cost nothing on-chain", 0, seam.submits.get())
            assertFalse("Join must clear the waiting mark", prefs.needsJoin.containsKey("GKNOWNR1"))
        }

    // ---- an invite over a finished key exchange --------------------------------------------------------------------

    @Test
    fun `an invite over a finished key exchange waits for Join and sends nothing`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 9_222)
            val prefs = StrangerInvitePrefs(consent = true)
            val sessionKey = GroupInviteDriver.finishedKeyExchange(prefs, peer)
            val seam = HandshakeSeam(peer)
            val vm = screen(prefs.proxy, seam)
            val relayed = mutableListOf<Pair<String, String>>()
            relayAcks(relayed)

            processInvite(vm, "GKEXKEY1", GroupInviteDriver.sessionInvite(peer, sessionKey))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertWaitsAndSentNothing(prefs, seam, relayed, vm, "GKEXKEY1", "a key-exchange invite")
        }

    // ---- a saved contact's and an E2E chat's on-chain invites -------------------------------------------------------

    @Test
    fun `a saved contact's invite waits for Join and tries no reply`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 9_223)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(contact)
            val vm = screen(prefs.proxy, seam, saved = setOf(contact))
            val relayed = mutableListOf<Pair<String, String>>()
            relayAcks(relayed)

            processInvite(vm, "GSAVED01", plaintextInvite(contact))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertWaitsAndSentNothing(prefs, seam, relayed, vm, "GSAVED01", "a saved contact's invite")
        }

    @Test
    fun `an invite from an address with an E2E chat waits for Join and tries no reply`() =
        runBlocking {
            val friend = SyntheticUnifiedAddress.mainnet(seed = 9_224)
            val prefs = StrangerInvitePrefs(consent = true).apply { e2eEnabled += friend }
            val seam = HandshakeSeam(friend)
            val vm = screen(prefs.proxy, seam)
            val relayed = mutableListOf<Pair<String, String>>()
            relayAcks(relayed)

            processInvite(vm, "GE2EFRI1", plaintextInvite(friend))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertWaitsAndSentNothing(prefs, seam, relayed, vm, "GE2EFRI1", "an E2E contact's invite")
        }

    // ---- Ignore (Decline) is local ------------------------------------------------------------------------------------

    @Test
    fun `Ignore on a known contact's invite deletes the group and sends nothing, after new blocks either`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 9_225)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(inviter)
            val vm = screen(withRelayKey(prefs.proxy, inviter), seam, saved = setOf(inviter))
            val relayed = mutableListOf<Pair<String, String>>()
            relayAcks(relayed)

            processRelayInvite(vm, "GIGNORE1", plaintextInvite(inviter), sender = inviter)
            awaitIdle(vm)
            assertTrue("fixture: the known contact's invite must wait for Join first", prefs.needsJoin.containsKey("GIGNORE1"))

            GroupInviteDriver.ignore(vm, "GIGNORE1")
            chainTip(vm, START_HEIGHT + 1)
            chainTip(vm, START_HEIGHT + 2)

            assertFalse("Ignore must delete the group", prefs.base.groupInfo.containsKey("GIGNORE1"))
            assertFalse("Ignore must drop the waiting mark", prefs.needsJoin.containsKey("GIGNORE1"))
            assertEquals("Ignore must not answer the creator over the relays", emptyList<Any>(), relayed.toList())
            assertEquals("Ignore must not build anything for the chain", 0, seam.proposalRequests.get())
            assertEquals("Ignore must not pay anything", 0, seam.submits.get())
        }

    // ---- guard: the refusal of an own or invalid inviter stays ---------------------------------------------------------

    @Test
    fun `an invite naming our own address is still ignored (guard)`() =
        runBlocking {
            val ours = SyntheticUnifiedAddress.mainnet(seed = 9_226)
            val prefs = StrangerInvitePrefs(consent = true).apply { selfAddresses += ours }
            val seam = HandshakeSeam(ours)
            val vm = screen(prefs.proxy, seam)

            processInvite(vm, "GOWNINV1", plaintextInvite(ours))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertFalse("an invite naming our own address was saved", prefs.base.groupInfo.containsKey("GOWNINV1"))
            assertTrue(prefs.needsJoin.isEmpty())
            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, prefs.nostrLookups.get())
        }
}
