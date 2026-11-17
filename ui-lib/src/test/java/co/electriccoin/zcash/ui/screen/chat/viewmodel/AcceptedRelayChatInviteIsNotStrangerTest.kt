package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
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
 * G3 8b (fix-all 2.13.0, train T4b; groups SPEC §3.2, §3.5 J3, J3b, J4) — "You haven't chatted with this address" after
 * an accepted free chat.
 *
 * At HEAD the stranger rule of processGroupInviteLocked is `!keyFromKeyExchange && isFirstContact(inviter)`, and
 * isFirstContact counts only an E2E key, E2E turned on and a saved contact. An accepted Open (relay-only) chat has none of
 * them, so on the Seeker the Fold's plaintext invite over the relays, sealed by the Fold's relay key that the Seeker had
 * accepted, waited for Join under "You haven't chatted with this address before".
 *
 * A relay invite has an authenticated sender only when NostrChatBridge.dispatch mapped its seal key to a peer, which
 * happens only for a peer the user added (a pasted or scanned code) or whose request the user accepted; an unknown key
 * goes to Message Requests. So the fix treats an invite with an authenticated sender as not a stranger's:
 * `!keyFromKeyExchange && authenticatedSender == null && isFirstContact(inviter)`. isFirstContact itself is unchanged
 * (it is the KEX money gate, KexFromStrangerWaitsForAcceptTest).
 *
 * - J3: such an invite was joined at once, with no needs-join record, and its "I joined" reply went free over the relays.
 *   Nothing was paid. W-137 (ZC-922) changed this: every invite with a key waits for the user's Join, so the premise
 *   below is history; J3 now pins that the invite waits and that Join answers it free over the relays.
 * - J3b (passes, must stay): the same invite while the relays are not ready still waits for Join (Round 8 P1); nothing
 *   is paid.
 * - J4 (passes, must stay): the same plaintext invite on-chain (no seal) still waits for Join, and no relay lookup is
 *   made for it.
 *
 * All addresses, keys, group ids and event ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptedRelayChatInviteIsNotStrangerTest {
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

    /** A phone with an accepted Open chat with [inviter]: its relay key only (no E2E key, E2E off, not saved). */
    private suspend fun acceptedOpenChatPhone(
        inviter: String,
        seam: HandshakeSeam,
    ): Pair<StrangerInvitePrefs, ChatViewModel> {
        val prefs = StrangerInvitePrefs(consent = true)
        val vm =
            chatViewModelWith(withRelayKey(prefs.proxy, inviter), seam).also { vm ->
                AutoSpendVm.wireBootstrapFields(vm)
                AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
            }
        return prefs to vm
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

    // ---- J3: a relay invite from an accepted Open chat waits for Join; Join then answers free over the relays ----------
    // [test-change-ok: W-137 ZC-922 every invite waits for Join] (was: joined at once, answered free without a tap)

    @Test
    fun `a relay invite from an accepted Open chat waits for Join, and Join answers it free over the relays`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 811)
            val seam = HandshakeSeam(inviter)
            val (prefs, vm) = acceptedOpenChatPhone(inviter, seam)
            val relayed = mutableListOf<Pair<String, String>>()
            relayAcks(relayed)

            processRelayInvite(vm, "GOPEN811", plaintextInvite(inviter), sender = inviter)
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertTrue("fixture sanity: the group is saved", prefs.base.groupInfo.containsKey("GOPEN811"))
            assertTrue(
                "W-137: an invite sealed by an accepted Open chat's relay key waits for the user's Join",
                prefs.needsJoin.containsKey("GOPEN811"),
            )
            assertEquals(
                "nothing may go to the creator before Join",
                emptyList<Any>(),
                relayed.filter { ZMSGGroupProtocol.parseMessageType(it.first) == GroupMessageType.GROUP_ACCEPT },
            )
            assertEquals("nothing may be paid before Join", 0, seam.submits.get())

            assertTrue("Join must succeed", GroupInviteDriver.join(vm, "GOPEN811"))
            val accepts = relayed.filter { ZMSGGroupProtocol.parseMessageType(it.first) == GroupMessageType.GROUP_ACCEPT }
            assertEquals("the \"I joined\" reply must go free over the relays once, on Join", 1, accepts.size)
            assertEquals("a relay reply must cost nothing on-chain", 0, seam.submits.get())
            assertEquals("nothing may be built for the chain", 0, seam.proposalRequests.get())
            assertFalse("Join must clear the waiting mark", prefs.needsJoin.containsKey("GOPEN811"))
        }

    // ---- J3b: relays not ready — still waits for Join, nothing paid (Round 8 P1) ------------------------------------

    @Test
    fun `control - the same relay invite while the relays are not ready waits for Join and pays nothing`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 812)
            val seam = HandshakeSeam(inviter)
            val (prefs, vm) = acceptedOpenChatPhone(inviter, seam)
            // No relay publisher: NostrChatBridge.isOutboundReady() is false, so the free reply cannot go.

            processRelayInvite(vm, "GOPEN812", plaintextInvite(inviter), sender = inviter)
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertTrue("fixture sanity: the group is saved", prefs.base.groupInfo.containsKey("GOPEN812"))
            assertTrue("a reply that cannot go free must wait for Join", prefs.needsJoin.containsKey("GOPEN812"))
            assertEquals("nothing may be paid before Join", 0, seam.submits.get())
            assertEquals("nothing may be built for the chain before Join", 0, seam.proposalRequests.get())
        }

    // ---- J4: the same invite on-chain (no seal) still waits for Join -----------------------------------------------

    @Test
    fun `control - the same plaintext invite on-chain still waits for Join, with no relay lookup`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 813)
            val seam = HandshakeSeam(inviter)
            val (prefs, vm) = acceptedOpenChatPhone(inviter, seam)
            val relayed = mutableListOf<Pair<String, String>>()
            relayAcks(relayed)

            GroupInviteDriver.processInvite(vm, "GOPEN813", plaintextInvite(inviter))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertTrue("an unsealed invite from an address with no chat must wait for Join", prefs.needsJoin.containsKey("GOPEN813"))
            assertEquals("no relay lookup may be made for a stranger's on-chain invite", 0, prefs.nostrLookups.get())
            assertTrue("nothing may go over the relays before Join", relayed.isEmpty())
            assertEquals("nothing may be paid before Join", 0, seam.submits.get())
        }

    /**
     * [delegate] with a relay key for [peer], as an accepted Open chat stores it. The lookup is counted by the delegate
     * too, so [StrangerInvitePrefs.nostrLookups] still counts every relay lookup.
     */
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
}
