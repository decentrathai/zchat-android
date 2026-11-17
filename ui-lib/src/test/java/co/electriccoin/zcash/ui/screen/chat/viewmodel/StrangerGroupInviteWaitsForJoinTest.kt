package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.QueuedAutomaticSetup
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.automaticSetupTransactions
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.heldInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.ignore
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.join
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

/**
 * Round 8 D4 (DECISIONS_NIGHT §1, p2-97712138bbb8) — a group invite from someone we never chatted with makes us pay.
 *
 * ChatViewModel.processGroupInvite joins any invite it can read. A legacy invite carries the group key in
 * plaintext (`group_key`), so it needs no key exchange: anyone who knows our address can put one on chain. The
 * join then sends our GROUP_ACCEPT ("I joined") to the inviter: free over NOSTR when we hold their relay key,
 * otherwise held and paid on-chain (about 17,000 zatoshi) under the ONE app-wide handshake consent. Every Tunnel
 * user has given it, there is no budget, and each group id is its own paid reply, so N invites cost us N replies
 * with nothing on screen.
 *
 * Wanted (the rule R2-2 (a) already applies to a stranger's KEX): an invite whose key did not come from a key
 * exchange, from an address with no chat, key or contact, waits for the user's Join. Nothing is sent, free or
 * paid, and nothing is queued for the consent question until then. Join sends exactly one reply; Ignore deletes
 * the group and nothing is ever paid. An invite naming our own address, or an address that is not a valid mainnet
 * Unified Address, is ignored. A needs-join mark that could not be saved stores and sends nothing. Invites from a
 * saved contact or over a finished key exchange behave exactly as before.
 *
 * The new entry points and preferences are reached by name, so this file compiles before they exist. All
 * addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrangerGroupInviteWaitsForJoinTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    private suspend fun screen(
        prefs: StrangerInvitePrefs,
        seam: HandshakeSeam,
        saved: Set<String> = emptySet(),
    ): ChatViewModel =
        chatViewModelWith(prefs.proxy, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "contactBook", SavedContacts(saved))
        }

    /** A simulated app kill and start: the process-wide state is gone, the stores are kept. */
    private suspend fun restart(
        prefs: StrangerInvitePrefs,
        seam: HandshakeSeam,
    ): ChatViewModel {
        AutoSpendVm.resetProcessState()
        return screen(prefs, seam)
    }

    private fun queuedGroupJoins(): Int =
        GroupInviteDriver
            .queuedSetups()
            .filterIsInstance<QueuedAutomaticSetup>()
            .count { it.kind == AutomaticSetupKind.GROUP_ACCEPT }

    private fun quotedTransactions(): Int =
        automaticSetupTransactions(GroupInviteDriver.queuedSetups().filterIsInstance<QueuedAutomaticSetup>(), emptyList())

    /** The group is saved, marked as waiting for Join, and nothing about it was sent, paid or queued. */
    private fun assertWaitsForJoin(
        prefs: StrangerInvitePrefs,
        seam: HandshakeSeam,
        groupId: String,
        what: String,
    ) {
        assertEquals("$what: a GROUP_ACCEPT was built and paid on-chain", 0, seam.proposalRequests.get())
        assertEquals("$what: a GROUP_ACCEPT was submitted", 0, seam.submits.get())
        assertEquals("$what: the free NOSTR reply was tried (its relay-key lookup ran)", 0, prefs.nostrLookups.get())
        assertTrue("$what: the group must be saved as today", prefs.base.groupInfo.containsKey(groupId))
        assertTrue("$what: the invite must be marked as waiting for Join", prefs.needsJoin.containsKey(groupId))
        assertFalse("$what: the reply is held for the automatic sends", prefs.base.heldGroupAccepts.containsKey(groupId))
        assertFalse("$what: the reply is held in memory for the automatic sends", heldInMemory(groupId))
        assertEquals("$what: the reply was queued for the consent question", 0, queuedGroupJoins())
        assertEquals("$what: the consent question would quote it", 0, quotedTransactions())
    }

    // ---- 1. a stranger's plaintext-key invite sends nothing -----------------------------------------------------

    @Test
    fun `a plaintext-key invite from a stranger, with consent already given, is saved and sends nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 401)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)

            processInvite(vm, "GSTRANGER01", plaintextInvite(stranger))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertWaitsForJoin(prefs, seam, "GSTRANGER01", "a stranger's invite with consent given")
            assertFalse(
                "no consent question may be raised for it",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent,
            )
        }

    @Test
    fun `a stranger's invite without consent raises no consent question and is not counted in one`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 402)
            val prefs = StrangerInvitePrefs(consent = false)
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)

            processInvite(vm, "GSTRANGER02", plaintextInvite(stranger))
            awaitIdle(vm)

            assertWaitsForJoin(prefs, seam, "GSTRANGER02", "a stranger's invite without consent")
            assertFalse(
                "the consent question was raised for a reply that waits for Join",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent,
            )
        }

    // ---- 2. Join sends exactly one reply ------------------------------------------------------------------------

    @Test
    fun `Join sends exactly one reply, and a second Join, a restart or a new block sends nothing more`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 403)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)
            processInvite(vm, "GSTRANGER03", plaintextInvite(stranger))
            awaitIdle(vm)
            assertEquals("fixture sanity: nothing before Join", 0, seam.submits.get())

            join(vm, "GSTRANGER03")
            assertEquals("Join must send the one reply (no relay key: on-chain, consent given)", 1, seam.submits.get())
            assertFalse("Join must clear the waiting mark", prefs.needsJoin.containsKey("GSTRANGER03"))
            assertTrue("a sent reply must not stay stored", prefs.base.heldGroupAccepts.isEmpty())

            join(vm, "GSTRANGER03")
            chainTip(vm, START_HEIGHT + 1)
            val afterRestart = restart(prefs, seam)
            chainTip(afterRestart, START_HEIGHT + 2)
            chainTip(afterRestart, START_HEIGHT + 3)
            join(afterRestart, "GSTRANGER03")

            assertEquals("the reply was paid more than once", 1, seam.submits.get())
            assertEquals(1, seam.broadcasts.size)
        }

    /**
     * Round 8 P1 (PHONE_TRIAGE F3): Join is the question for its one reply. It no longer hands the reply to the standing
     * handshake consent (which asked, and once given paid every later group join on its own).
     */
    @Test
    fun `Join without consent sends the one reply at once, asks no consent question and gives no standing consent`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 404)
            val prefs = StrangerInvitePrefs(consent = false)
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)
            processInvite(vm, "GSTRANGER04", plaintextInvite(stranger))
            awaitIdle(vm)

            join(vm, "GSTRANGER04")
            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse(
                "Join raised the standing consent question for its own reply: $state",
                state is SendMessageState.NeedsHandshakeSpendConsent,
            )
            assertEquals("Join must send the joined group's reply once", 1, seam.submits.get())
            assertFalse("Join gave the standing handshake consent", prefs.base.consent)

            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            assertEquals("the reply was paid more than once", 1, seam.submits.get())
        }

    // ---- 3. Ignore ----------------------------------------------------------------------------------------------

    @Test
    fun `Ignore deletes the group and nothing is ever paid, after a restart and new blocks either`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 405)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)
            processInvite(vm, "GSTRANGER05", plaintextInvite(stranger))
            awaitIdle(vm)

            ignore(vm, "GSTRANGER05")
            assertFalse("Ignore must delete the group", prefs.base.groupInfo.containsKey("GSTRANGER05"))
            assertFalse("Ignore must drop the waiting reply", prefs.needsJoin.containsKey("GSTRANGER05"))

            chainTip(vm, START_HEIGHT + 1)
            val afterRestart = restart(prefs, seam)
            chainTip(afterRestart, START_HEIGHT + 2)
            chainTip(afterRestart, START_HEIGHT + 3)
            join(afterRestart, "GSTRANGER05")

            assertEquals("an ignored invite was paid for", 0, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
            assertEquals("an ignored invite's reply was tried over NOSTR", 0, prefs.nostrLookups.get())
        }

    // ---- 4. a kill before Join ----------------------------------------------------------------------------------

    @Test
    fun `a kill before Join keeps the invite waiting and sends nothing after the restart`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 406)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)
            processInvite(vm, "GSTRANGER06", plaintextInvite(stranger))
            awaitIdle(vm)

            val afterRestart = restart(prefs, seam)
            chainTip(afterRestart, START_HEIGHT + 1)
            chainTip(afterRestart, START_HEIGHT + 2)
            afterRestart.grantHandshakeSpendConsent()
            awaitIdle(afterRestart)

            assertWaitsForJoin(prefs, seam, "GSTRANGER06", "after a kill and a restart")

            join(afterRestart, "GSTRANGER06")
            assertEquals("Join after the restart must send the one reply", 1, seam.submits.get())
        }

    // ---- 5. controls: a saved contact, a finished key exchange --------------------------------------------------

    /**
     * An invite from a saved contact waits for Join like a stranger's (W-137, ZC-922; it was joined at once, its free
     * NOSTR reply tried at once). Round 8 P1 (PHONE_TRIAGE F3): with no relay key the reply is paid, never under the
     * standing consent, and Join pays it once.
     */
    @Test
    fun `a saved contact's plaintext-key invite also waits for Join, tries no reply, and Join pays it once`() =
        runBlocking {
            val contact = SyntheticUnifiedAddress.mainnet(seed = 407)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(contact)
            val vm = screen(prefs, seam, saved = setOf(contact))

            processInvite(vm, "GCONTACT07", plaintextInvite(contact))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertTrue(prefs.base.groupInfo.containsKey("GCONTACT07"))
            // [test-change-ok: W-137 ZC-922 every invite waits for Join] (was: the free NOSTR reply is tried first)
            assertEquals("no reply is tried before Join", 0, prefs.nostrLookups.get())
            assertEquals("a saved contact's reply was paid under the standing consent", 0, seam.submits.get())
            assertTrue("a saved contact's invite must wait for Join", prefs.needsJoin.containsKey("GCONTACT07"))

            // [test-change-ok: W-137 ZC-922 ZC-1545 review follow-up] (was: the first Join paid; a known contact's price is shown first)
            join(vm, "GCONTACT07")
            assertEquals("the first Join paid the reply although no price was shown", 0, seam.submits.get())
            join(vm, "GCONTACT07")
            assertEquals("the second Join must pay the reply once", 1, seam.submits.get())
        }

    /**
     * An invite over a finished key exchange (k2) waits for Join too (W-137, ZC-922; it was joined at once, its free reply
     * tried at once). Round 8 P1: with no relay key its reply is paid only on Join, never under the standing consent.
     */
    @Test
    fun `an invite over a finished key exchange also waits for Join, tries no reply, and Join pays it once`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 408)
            val prefs = StrangerInvitePrefs(consent = true)
            val sessionKey = GroupInviteDriver.finishedKeyExchange(prefs, peer)
            val seam = HandshakeSeam(peer)
            val vm = screen(prefs, seam)

            processInvite(vm, "GSESSION08", GroupInviteDriver.sessionInvite(peer, sessionKey))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertTrue(prefs.base.groupInfo.containsKey("GSESSION08"))
            // [test-change-ok: W-137 ZC-922 every invite waits for Join] (was: the free NOSTR reply is tried first)
            assertEquals("no reply is tried before Join", 0, prefs.nostrLookups.get())
            assertEquals("an invite over a key exchange was paid under the standing consent", 0, seam.submits.get())
            assertTrue("an invite over a key exchange must wait for Join", prefs.needsJoin.containsKey("GSESSION08"))

            // [test-change-ok: W-137 ZC-922 ZC-1545 review follow-up] (was: the first Join paid; a known contact's price is shown first)
            join(vm, "GSESSION08")
            assertEquals("the first Join paid the reply although no price was shown", 0, seam.submits.get())
            join(vm, "GSESSION08")
            assertEquals("the second Join must pay the reply once", 1, seam.submits.get())
        }

    // ---- 6. ten invites -----------------------------------------------------------------------------------------

    @Test
    fun `ten stranger invites for ten groups send nothing at all`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 409)
            val prefs = StrangerInvitePrefs(consent = true)
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)

            val groups = (1..10).map { "GSTRANGERX%02d".format(it) }
            groups.forEach { processInvite(vm, it, plaintextInvite(stranger)) }
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)
            chainTip(vm, START_HEIGHT + 2)

            groups.forEach { assertWaitsForJoin(prefs, seam, it, "invite $it of ten") }
            assertEquals("ten stranger invites were paid for (about 17,000 zatoshi each)", 0, seam.proposalRequests.get())
        }

    // ---- 7. our own address, an invalid address, an unsaved mark ------------------------------------------------

    @Test
    fun `an invite naming our own address is ignored`() =
        runBlocking {
            val ours = SyntheticUnifiedAddress.mainnet(seed = 410)
            val prefs = StrangerInvitePrefs(consent = true).apply { selfAddresses += ours }
            val seam = HandshakeSeam(ours)
            val vm = screen(prefs, seam)

            processInvite(vm, "GSELF10", plaintextInvite(ours))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertFalse("an invite naming our own address was saved", prefs.base.groupInfo.containsKey("GSELF10"))
            assertTrue(prefs.needsJoin.isEmpty())
            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, prefs.nostrLookups.get())
        }

    @Test
    fun `an invite naming an address that is not a valid mainnet Unified Address is ignored`() =
        runBlocking {
            for ((i, invalid) in listOf(
                SyntheticUnifiedAddress.testnet(seed = 411),
                SyntheticUnifiedAddress.mainnetBadChecksum(seed = 412),
            ).withIndex()) {
                val prefs = StrangerInvitePrefs(consent = true)
                val seam = HandshakeSeam(invalid)
                val vm = screen(prefs, seam)
                val groupId = "GINVALID1$i"

                processInvite(vm, groupId, plaintextInvite(invalid))
                awaitIdle(vm)
                chainTip(vm, START_HEIGHT + 1 + i)

                assertFalse("an invite from ${invalid.take(10)}… was saved", prefs.base.groupInfo.containsKey(groupId))
                assertTrue(prefs.needsJoin.isEmpty())
                assertEquals(0, seam.proposalRequests.get())
                assertEquals(0, prefs.nostrLookups.get())
            }
        }

    @Test
    fun `a waiting mark that could not be saved stores nothing and sends nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 413)
            val prefs = StrangerInvitePrefs(consent = true).apply { needsJoinWritesFail.set(true) }
            val seam = HandshakeSeam(stranger)
            val vm = screen(prefs, seam)

            processInvite(vm, "GUNSAVED13", plaintextInvite(stranger))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertFalse("the group was saved without its waiting mark", prefs.base.groupInfo.containsKey("GUNSAVED13"))
            val stored = prefs.writes.filter { it in setOf("saveGroupInfo", "saveGroupMembers", "saveGroupKey", "setHeldGroupAccept") }
            assertEquals("something was stored for an invite whose waiting mark was not saved", emptyList<String>(), stored)
            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, prefs.nostrLookups.get())
            assertEquals(0, queuedGroupJoins())
        }
}
