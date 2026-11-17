package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * QA2 U9 review follow-up, finding 1 (P1, money-path rule 1: quote first) and finding 3 (the list side of Q2-57a).
 *
 * W-137 B-4 made every group invite with a key wait for Join and stopped the automatic free "I joined" relay try. The
 * record a known contact's invite leaves is therefore fresh (`relay_failed` false), and the group screen shows the
 * known-contact banner `group_invite_join_known`, which names no price ("shows a price only if it has to pay"). Before
 * that change the relay was tried first; when it failed the record carried `relay_failed` and the screen showed
 * `group_invite_join_known_paid` ("Join tries them once more first; if they still can't take it, the reply costs at
 * least %3$s ZEC") before any Join. At HEAD of the first U9 fix (87c8fc291) a known contact WITHOUT a relay route (a
 * Shielded or key-exchange-only contact, a saved contact with no Open chat, relays that refuse) has a Join whose relay
 * try fails and which then pays the 17,000-zatoshi reply at once, through `sendClaimedGroupAccept` (which skips the
 * consent gate: the Join is the consent), without a price ever shown on the screen that was tapped.
 *
 * Wanted: a paid "I joined" always goes through its quote. When the reply of a known contact's FRESH record (nothing
 * tried yet) cannot go over the relays, the first Join pays and submits nothing and puts the reply back to waiting with
 * `relay_failed` set, so the banner is the paid one with the price; the second Join pays it once. A new block, the
 * standing consent or a restart never pay it in between. A reply the relays take stays free on the first Join (see
 * EveryGroupInviteWaitsForJoinTest). Strangers keep their own priced banner and are not pinned here.
 *
 * Finding 3: U5's `ChatListLayout` (not in this tree) counts a listed group that awaits Join as 1 unread and opens the
 * Groups row; its input is `ChatListState.groupsAwaitingJoin`, filled by `groupInviteNeedsJoin`. The tests here pin the
 * ViewModel side for every kind of known contact: the group is stored (listed), `groupInviteNeedsJoin` is set, and the
 * inviter reads as known. The merged tree adds the pure `unreadWithInvitations` check.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnownContactJoinShowsItsPriceBeforePayingTest {
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

    private fun awaitingJoin(
        vm: ChatViewModel,
        groupId: String,
    ): Boolean =
        ChatViewModel::class.java
            .getDeclaredMethod("groupInviteNeedsJoin", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, groupId) != null

    private fun consentQuestionRaised(vm: ChatViewModel): Boolean =
        HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent

    /** [delegate] with a relay key for [peer], so the relay reply is tried (and may be refused). */
    private fun withRelayKey(
        delegate: ZchatPreferences,
        peer: String,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when {
                method.name == "getPeerNostrPubkey" && args?.get(0) == peer -> "ab".repeat(32)
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

    /**
     * The whole path of a known contact whose reply cannot go over the relays: [first] is the first Join, [second] the
     * next. Asserts that nothing is built or paid before the second Join, that the waiting record then carries
     * `relay_failed` (the paid banner with its price), and that the second Join pays exactly one reply.
     */
    private suspend fun assertQuotedBeforePaid(
        what: String,
        vm: ChatViewModel,
        prefs: StrangerInvitePrefs,
        seam: HandshakeSeam,
        groupId: String,
        consent: Boolean,
    ) {
        assertTrue("$what: fixture: the group is stored", prefs.base.groupInfo.containsKey(groupId))
        val fresh = prefs.needsJoin[groupId]
        assertNotNull("$what: the invite must wait for Join", fresh)
        assertFalse("$what: fixture: a fresh record has no failed relay try", GroupInviteWaitRecord.relayFailed(fresh))
        assertEquals("$what: nothing is paid before Join", 0, seam.submits.get())

        assertTrue("$what: the first Join must be accepted", GroupInviteDriver.join(vm, groupId))
        assertEquals(
            "$what: the first Join paid the reply although no price was shown on the screen that was tapped",
            0,
            seam.submits.get(),
        )
        assertEquals("$what: the first Join built a payment for the chain", 0, seam.proposalRequests.get())
        val afterFirst = prefs.needsJoin[groupId]
        assertNotNull("$what: the reply that could not go free must be back to waiting for Join", afterFirst)
        assertTrue(
            "$what: the record must say the relay try failed, so the screen shows group_invite_join_known_paid with the price",
            GroupInviteWaitRecord.relayFailed(afterFirst),
        )
        assertTrue("$what: the chat list must show the group as waiting for Join", awaitingJoin(vm, groupId))
        assertFalse("$what: the reply must not stay held for the automatic sends", prefs.base.heldGroupAccepts.containsKey(groupId))
        assertFalse("$what: a consent question was raised for a group join", consentQuestionRaised(vm))

        // A new block and the standing consent never pay the waiting reply.
        chainTip(vm, START_HEIGHT + 2)
        if (consent) vm.grantHandshakeSpendConsent()
        awaitIdle(vm)
        chainTip(vm, START_HEIGHT + 3)
        assertEquals("$what: a block or the consent paid the waiting reply", 0, seam.submits.get())
        assertNotNull("$what: the waiting record was lost", prefs.needsJoin[groupId])

        assertTrue("$what: the second Join must be accepted", GroupInviteDriver.join(vm, groupId))
        assertEquals("$what: the second Join must pay exactly one reply", 1, seam.submits.get())
        assertFalse("$what: Join must clear the waiting mark", prefs.needsJoin.containsKey(groupId))
        assertFalse("$what: a third Join must send nothing", GroupInviteDriver.join(vm, groupId))
        assertEquals("$what: the reply was paid more than once", 1, seam.submits.get())
    }

    // ---- finding 1: the first Join of a known contact without a relay route shows the price, pays nothing ---------------

    @Test
    fun `a contact with an E2E chat and no relay key - the first Join pays nothing and shows the price, the second Join pays once`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 9_301)
            val prefs = StrangerInvitePrefs(consent = true).apply { e2eEnabled += inviter }
            val seam = HandshakeSeam(inviter)
            val vm = screen(prefs.proxy, seam)

            processInvite(vm, "GQUOTE01", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertQuotedBeforePaid("E2E contact", vm, prefs, seam, "GQUOTE01", consent = true)
        }

    @Test
    fun `a saved contact with no relay key and no standing consent - the first Join pays nothing and shows the price`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 9_302)
            val prefs = StrangerInvitePrefs(consent = false)
            val seam = HandshakeSeam(inviter)
            val vm = screen(prefs.proxy, seam, saved = setOf(inviter))

            processInvite(vm, "GQUOTE02", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertQuotedBeforePaid("saved contact", vm, prefs, seam, "GQUOTE02", consent = false)
        }

    @Test
    fun `a contact over a finished key exchange and no relay key - the first Join pays nothing and shows the price`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 9_303)
            val prefs = StrangerInvitePrefs(consent = true)
            val sessionKey = GroupInviteDriver.finishedKeyExchange(prefs, peer)
            val seam = HandshakeSeam(peer)
            val vm = screen(prefs.proxy, seam)

            processInvite(vm, "GQUOTE03", GroupInviteDriver.sessionInvite(peer, sessionKey))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertQuotedBeforePaid("key-exchange contact", vm, prefs, seam, "GQUOTE03", consent = true)
        }

    @Test
    fun `a known contact whose relays refuse the reply - the first Join pays nothing and shows the price`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 9_304)
            val prefs = StrangerInvitePrefs(consent = true).apply { e2eEnabled += inviter }
            val seam = HandshakeSeam(inviter)
            val tried = mutableListOf<String>()
            NostrChatBridge.registerPublisher { memo, _ ->
                tried += memo
                NostrChatBridge.PublishResult(acks = 0, messageId = null)
            }
            val vm = screen(withRelayKey(prefs.proxy, inviter), seam)

            processInvite(vm, "GQUOTE04", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            assertEquals("nothing may go over the relays before Join", 0, tried.size)

            assertQuotedBeforePaid("relays refuse", vm, prefs, seam, "GQUOTE04", consent = true)
            assertTrue("the first Join must try the relays before it asks for a price", tried.isNotEmpty())
        }

    // ---- finding 3: the ViewModel side of "a known contact's invitation counts as 1 unread, row open" -------------------

    @Test
    fun `every kind of known contact's invite is stored, waits for Join and reads as known, a stranger's reads as not known`() =
        runBlocking {
            val e2e = SyntheticUnifiedAddress.mainnet(seed = 9_311)
            val saved = SyntheticUnifiedAddress.mainnet(seed = 9_312)
            val kex = SyntheticUnifiedAddress.mainnet(seed = 9_313)
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 9_314)
            val prefs = StrangerInvitePrefs(consent = true).apply { e2eEnabled += e2e }
            val sessionKey = GroupInviteDriver.finishedKeyExchange(prefs, kex)
            val seam = HandshakeSeam(e2e)
            val vm = screen(prefs.proxy, seam, saved = setOf(saved))

            processInvite(vm, "GLIST_E2E", plaintextInvite(e2e))
            processInvite(vm, "GLIST_SAVED", plaintextInvite(saved))
            processInvite(vm, "GLIST_KEX", GroupInviteDriver.sessionInvite(kex, sessionKey))
            processInvite(vm, "GLIST_STR", plaintextInvite(stranger))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            for ((groupId, inviter) in listOf("GLIST_E2E" to e2e, "GLIST_SAVED" to saved, "GLIST_KEX" to kex)) {
                assertTrue("$groupId: the group must be stored so the list shows it", prefs.base.groupInfo.containsKey(groupId))
                assertTrue("$groupId: the list must count it as waiting for Join", awaitingJoin(vm, groupId))
                assertTrue("$groupId: its inviter must read as a known contact", vm.isKnownGroupInviter(inviter))
            }
            assertTrue("a stranger's invite waits for Join too", awaitingJoin(vm, "GLIST_STR"))
            assertFalse("a stranger must not read as a known contact", vm.isKnownGroupInviter(stranger))
            assertEquals("nothing is paid for any of the four", 0, seam.submits.get())
        }
}
