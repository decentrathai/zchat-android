package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
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
 * P2b — automatic spends are consented, counted and paid once.
 *
 * Every test drives a real ChatViewModel call site (sendKEXMessage, sendNostrBootHandshake,
 * processGroupInvite, reSendOurIdentity) through the real CreateChunkedMessageProposalUseCase and the real
 * ZashiProposalRepositoryImpl; only the preferences store, the settlement seam and the chain tip are fake
 * (see [AutoSpendPrefs] and HandshakeChokepointHarness). One chat setup transaction costs 17,000 zatoshi.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSpendPaidOnceTest {
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

    /** A submit refused before the SDK stored anything (empty transaction IDs): provably nothing was sent. */
    private fun rejected(): SubmitResult = SubmitResult.Failure(txIds = emptyList(), code = 18, description = "rejected")

    /** A submit refused after the SDK stored the transaction: it may still be broadcast (P2a). */
    private fun rejectedButStored(): SubmitResult =
        SubmitResult.Failure(txIds = listOf("synthetic-stored"), code = 18, description = "rejected")

    private suspend fun vmFor(
        prefs: AutoSpendPrefs,
        seam: HandshakeSeam,
        heights: HeightFeed = HeightFeed(),
        ironwoodPending: Long = 0L,
    ): ChatViewModel =
        chatViewModelWith(prefs.proxy, seam, heights, ironwoodPending).also(AutoSpendVm::wireBootstrapFields)

    // ---- Item 2: KEX and ZBOOT retries re-read their durable state before each paid attempt ----------------

    /**
     * A KEX that hit "funds still confirming" waits for the next block and then tries again. If the peer's
     * reply lands during that wait, the key exchange is complete and the KEX is no longer needed. At
     * 60b1d12c0 the retry re-reads only consent, so it builds and pays a second, useless KEX.
     */
    @Test
    fun `a KEX waiting for a block pays nothing once the peer's reply completes the key exchange`() =
        runBlocking {
            val peer = "usyntheticautospendkexwait01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            prefs.ownBootSent[peer] = true
            val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = vmFor(prefs, seam, heights, ironwoodPending = 200_000L)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the first attempt hit the shortfall", 1, seam.proposalRequests.get())

            // The peer's KEXACK arrives while we wait: we now hold their key and ours.
            prefs.peerKeys[peer] = "synthetic-peer-key"
            prefs.complete += peer
            heights.advance()
            awaitIdle(vm)

            assertEquals(
                "the key exchange completed during the block wait, but the retry still built a second KEX",
                1,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertEquals("a completed exchange must not re-arm the KEX", true, prefs.ownBootSent[peer])
        }

    /**
     * A KEX refused before anything was stored sent nothing and is re-armed, so the next trigger must be
     * able to pay it. At 6214907df the paid-setup record written before that attempt stays, so the next
     * attempt reads it as "already paid within the hour", sends nothing and reports it sent, and the KEX is
     * stuck until a forced bootstrap.
     */
    @Test
    fun `a KEX refused before anything was stored is re-armed and the next trigger pays it`() =
        runBlocking {
            val peer = "usyntheticautospendrejectk1"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            prefs.ownBootSent[peer] = true
            val seam =
                HandshakeSeam(
                    peer,
                    firstSubmitOutcome = rejected(),
                )
            val vm = vmFor(prefs, seam)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals("fixture sanity: the rejected KEX re-armed", false, prefs.ownBootSent[peer])
            assertEquals("fixture sanity: nothing reached the network", 0, seam.broadcasts.size)

            prefs.ownBootSent[peer] = true // the next bootstrap trigger
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)

            assertEquals("the re-armed KEX was never paid again", 1, seam.broadcasts.size)
        }

    /** Same for a ZBOOT: an on-chain ZBOOT refused before anything was stored re-arms and the next trigger pays it. */
    @Test
    fun `a ZBOOT refused before anything was stored is re-armed and the next trigger pays it`() =
        runBlocking {
            val peer = "usyntheticautospendrejectb1"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam =
                HandshakeSeam(
                    peer,
                    firstSubmitOutcome = rejected(),
                )
            val vm = vmFor(prefs, seam)
            AutoSpendVm.setHistoryRead(true)

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)
            assertEquals("fixture sanity: the rejected ZBOOT re-armed", null, prefs.sentNostrBoot[peer])

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)

            assertEquals("the re-armed ZBOOT was never paid again", 1, seam.broadcasts.size)
            assertTrue("the delivered identity is recorded", prefs.sentNostrBoot[peer] != null)
        }

    /**
     * Pin: a KEX whose refused transaction the SDK stored may still be broadcast, so within the cooldown the
     * next trigger does not pay a second one for the same key.
     */
    @Test
    fun `a KEX whose refused transaction was stored is not paid again within the cooldown`() =
        runBlocking {
            val peer = "usyntheticautospendstored01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            prefs.ownBootSent[peer] = true
            val seam = HandshakeSeam(peer, firstSubmitOutcome = rejectedButStored())
            val vm = vmFor(prefs, seam)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            prefs.ownBootSent[peer] = true // the next bootstrap trigger
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)

            assertEquals("a second KEX was built while the first may still be broadcast", 1, seam.proposalRequests.get())
        }

    /**
     * A ZBOOT that hit "funds still confirming" waits for a block. While it waits, a newer identity is
     * recorded as delivered to this peer (a key rotation announced over the free relay). At 60b1d12c0 the
     * waiting ZBOOT pays anyway and carries the older identity.
     */
    @Test
    fun `a ZBOOT waiting for a block pays nothing once a newer identity is recorded as delivered`() =
        runBlocking {
            val peer = "usyntheticautospendbootwait1"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = vmFor(prefs, seam, heights, ironwoodPending = 200_000L)
            AutoSpendVm.setHistoryRead(true)

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the first attempt hit the shortfall", 1, seam.proposalRequests.get())

            val newer = "ab".repeat(32)
            prefs.sentNostrBoot[peer] = newer
            heights.advance()
            awaitIdle(vm)

            assertEquals(
                "a newer identity was recorded as delivered during the wait, but the stale ZBOOT still paid",
                1,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertEquals("the newer delivery record must survive", newer, prefs.sentNostrBoot[peer])
        }

    /** Control: with nothing recorded during the wait, the ZBOOT retries after the block and pays once. */
    @Test
    fun `control - a ZBOOT waiting for a block with nothing recorded pays once`() =
        runBlocking {
            val peer = "usyntheticautospendbootwait2"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = vmFor(prefs, seam, heights, ironwoodPending = 200_000L)
            AutoSpendVm.setHistoryRead(true)

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitTrue { heights.isWaiting() }
            heights.advance()
            awaitIdle(vm)

            assertEquals(2, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertTrue("the delivered identity is recorded", prefs.sentNostrBoot[peer] != null)
        }

    /**
     * After a restore every local marker is empty, so any trigger (the per-block Tunnel retry, an old KEXACK
     * read from history, a chat open) would pay a second on-chain ZBOOT per peer before the scan has read the
     * one the old phone already paid for. No automatic ZBOOT may pay until the history scan has been read.
     */
    @Test
    fun `before the history scan has been read an automatic ZBOOT pays nothing`() =
        runBlocking {
            val peer = "usyntheticautospendrestore01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer)
            val vm = vmFor(prefs, seam)
            AutoSpendVm.setHistoryRead(false)

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)

            assertEquals(
                "an automatic ZBOOT was paid before the restored history was read (the old phone may have paid it)",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertEquals("the ZBOOT must stay armed for after the scan", null, prefs.sentNostrBoot[peer])
            assertFalse(
                "no consent prompt for a spend that is only deferred",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent,
            )
        }

    // ---- Item 1: GROUP_ACCEPT is an automatic spend and needs consent ----------------------------------------

    private fun invitePayload(inviter: String): String {
        val groupKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val members = JSONArray()
        members.put(inviter)
        members.put(HandshakeHarness.SELF)
        val invite = JSONObject()
        invite.put("name", "Synthetic group")
        invite.put("inviter", inviter)
        invite.put("group_key", groupKey)
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

    /** Pin (holds at 60b1d12c0): an invite that arrives while consent is off sends no paid GROUP_ACCEPT. */
    @Test
    fun `a group invite arriving without handshake consent sends no GROUP_ACCEPT`() =
        runBlocking {
            val inviter = "usyntheticautospendinviter01"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = vmFor(prefs, seam)

            processInvite(vm, "GSYNTH01", inviter)
            awaitIdle(vm)

            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTH01"))
            assertEquals("GROUP_ACCEPT built a proposal without consent", 0, seam.proposalRequests.get())
            assertEquals(0, seam.broadcasts.size)
            // Round 8 P1 (PHONE_TRIAGE F3): the user is asked by the group screen's Join, not by the standing consent.
            assertTrue("the user must be asked (the reply waits for Join)", prefs.needsJoin.containsKey("GSYNTH01"))
            assertFalse(
                "a group join raised the standing consent question",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent,
            )
        }

    /**
     * Control: the same invite pays exactly one GROUP_ACCEPT once the user agrees. Round 8 P1: with consent given the
     * invite pays nothing on its own (it paid one at once); Join pays exactly one.
     */
    @Test
    fun `control - with consent a group invite pays one GROUP_ACCEPT after Join`() =
        runBlocking {
            val inviter = "usyntheticautospendinviter02"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = vmFor(prefs, seam)

            processInvite(vm, "GSYNTH02", inviter)
            awaitIdle(vm)
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())
            GroupInviteDriver.join(vm, "GSYNTH02")

            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
        }

    // ---- Item 3 (HS-6): the prompt quotes the real total and is not re-shown for the same set ---------------

    private fun consentAsk(vm: ChatViewModel): SendMessageState.NeedsHandshakeSpendConsent =
        HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsHandshakeSpendConsent
            ?: throw AssertionError("expected the consent prompt, was ${HandshakeHarness.sendMessageState(vm)}")

    /**
     * A Tunnel chat setup is two paid transactions: our KEX now and our ZBOOT once the reply lands. At
     * 60b1d12c0 the prompt counts only peers whose ZBOOT is due, so a blocked KEX is quoted as one
     * 17,000-zatoshi handshake, and a second blocked setup does not raise the total.
     */
    @Test
    fun `the consent prompt quotes every transaction the queued chat setups can spend`() =
        runBlocking {
            val peerA = "usyntheticautospendquoteaa1"
            val peerB = "usyntheticautospendquotebb1"
            val prefs =
                AutoSpendPrefs(consent = false).apply {
                    session(peerA, complete = false, mode = ConversationMode.TUNNEL)
                    session(peerB, complete = false, mode = ConversationMode.TUNNEL)
                }
            val seam = HandshakeSeam(peerA)
            val vm = vmFor(prefs, seam)

            vm.sendKEXMessage(peerA, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals("fixture sanity: nothing paid without consent", 0, seam.proposalRequests.get())
            assertEquals(
                "one queued Tunnel setup = KEX + ZBOOT = 2 x 17,000 zatoshi",
                34_000L,
                consentAsk(vm).estimatedCost.value,
            )

            vm.sendKEXMessage(peerB, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals(
                "two queued Tunnel setups = 4 x 17,000 zatoshi",
                68_000L,
                consentAsk(vm).estimatedCost.value,
            )
            assertEquals(4, consentAsk(vm).pendingPeers)
        }

    /**
     * After "Not now", the automatic retries for the same queued setups must not raise the prompt again on
     * every block or scan. At 60b1d12c0 every attempt re-raises it.
     */
    @Test
    fun `after Not now the same queued setup does not raise the prompt again`() =
        runBlocking {
            val peer = "usyntheticautospendnotnow01"
            val prefs = AutoSpendPrefs(consent = false).apply { session(peer, complete = false, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer)
            val vm = vmFor(prefs, seam)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            consentAsk(vm)
            vm.dismissOrchardShieldingWarning() // the dialog's "Not now"

            vm.sendKEXMessage(peer, HandshakeHarness.SELF) // the next automatic trigger for the same setup
            awaitIdle(vm)

            assertEquals(
                "the prompt came back for the same queued setup the user just declined",
                SendMessageState.Idle,
                HandshakeHarness.sendMessageState(vm),
            )
            assertEquals(0, seam.proposalRequests.get())
        }

    /** Control: a setup that was not in the declined set raises the prompt again, with the new total. */
    @Test
    fun `control - after Not now a new queued setup asks again`() =
        runBlocking {
            val peerA = "usyntheticautospendnotnowa2"
            val peerB = "usyntheticautospendnotnowb2"
            val prefs =
                AutoSpendPrefs(consent = false).apply {
                    session(peerA, complete = false, mode = ConversationMode.TUNNEL)
                    session(peerB, complete = false, mode = ConversationMode.TUNNEL)
                }
            val seam = HandshakeSeam(peerA)
            val vm = vmFor(prefs, seam)

            vm.sendKEXMessage(peerA, HandshakeHarness.SELF)
            awaitIdle(vm)
            vm.dismissOrchardShieldingWarning()
            vm.sendKEXMessage(peerB, HandshakeHarness.SELF)
            awaitIdle(vm)

            consentAsk(vm)
            assertEquals(0, seam.proposalRequests.get())
        }

    // ---- Item 4: Reconnect pays for at most one chat setup per contact key until a reply or the cooldown ----

    /**
     * On a torn session Reconnect runs a forced bootstrap, and a forced bootstrap pays a KEX on every tap.
     * At 60b1d12c0 two taps pay two KEXes for the same key although the first has not been answered.
     */
    @Test
    fun `tapping Reconnect twice on a torn session pays for one KEX`() =
        runBlocking {
            val peer = "usyntheticautospendreconn01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            val seam = HandshakeSeam(peer)
            val vm = vmFor(prefs, seam)

            vm.reSendOurIdentity(peer)
            awaitIdle(vm)
            assertEquals("fixture sanity: the first tap pays one KEX", 1, seam.broadcasts.size)

            vm.reSendOurIdentity(peer)
            awaitIdle(vm)

            assertEquals("the second tap paid another KEX for the same unanswered key", 1, seam.broadcasts.size)
            assertEquals(1, seam.proposalRequests.get())
            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue(
                "the second tap must say nothing was sent and when it can be tried again, was $state",
                state is SendMessageState.Success && state.label.contains("hour"),
            )
        }

    /**
     * On a complete session Reconnect clears the ZBOOT marker and re-sends our identity. When the peer's
     * relay key is unknown that re-send is an on-chain ZBOOT, paid on every tap at 60b1d12c0.
     */
    @Test
    fun `tapping Reconnect twice on a complete session pays for at most one on-chain ZBOOT`() =
        runBlocking {
            val peer = "usyntheticautospendreconn02"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer)
            val vm = vmFor(prefs, seam)
            AutoSpendVm.setHistoryRead(true)

            vm.reSendOurIdentity(peer)
            awaitIdle(vm)
            assertEquals("fixture sanity: the first tap pays one ZBOOT", 1, seam.broadcasts.size)

            vm.reSendOurIdentity(peer)
            awaitIdle(vm)

            assertEquals("the second tap paid another ZBOOT for the same identity", 1, seam.broadcasts.size)
        }

    /** Control: once the stated cooldown has passed, Reconnect may pay for the setup again. */
    @Test
    fun `control - after the cooldown Reconnect pays again`() =
        runBlocking {
            val peer = "usyntheticautospendreconn03"
            val prefs = AutoSpendPrefs(consent = true)
            val ours = prefs.session(peer, complete = false)
            val twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
            prefs.paidRecords["KEX:$peer"] = "${ours.publicKey}|$twoHoursAgo"
            val seam = HandshakeSeam(peer)
            val vm = vmFor(prefs, seam)

            vm.reSendOurIdentity(peer)
            awaitIdle(vm)

            assertEquals(1, seam.broadcasts.size)
        }
}
