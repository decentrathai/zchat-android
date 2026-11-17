package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.heldInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.join
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.START_HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.TIMEOUT_MS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.PASS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.joinNoWait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZC-284 (R8 findings, SPECS §6) — Join clears the invite's waiting record before any durable copy of the reply exists.
 *
 * A known contact invites us to a group; our free "I joined" reply cannot go over the relays, so the invite waits for
 * Join (the needs-join record, on disk). The user taps Join. Join deletes the record first, then tries the relays again
 * (a network round trip), then reads the account, and only then writes the held copy of the reply. If the app is killed
 * anywhere in between, nothing on disk holds the reply: after the restart there is no Join banner, the group looks
 * joined, the creator keeps us Invited and fans no group message out to us, and a re-invite does not reopen an active
 * group.
 *
 * Wanted: from the Join tap until the reply is delivered over the relays or handed to the SDK, a durable copy exists,
 * and the next start turns it back into "waits for Join" (nothing is sent or paid by the start itself). A Join whose
 * durable copy cannot be written sends nothing and keeps the invite waiting. One Join still sends once.
 *
 * The kill is the end of the process: the next process sees the stores as they were at that moment ([Phone.diskCopy]),
 * none of the process-wide maps, and a first chain tip. The second kill test cancels the process send scope and starts
 * the next process over the same store. All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinSurvivesAKillDuringTheRelayTryTest {
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

    /** One phone that already chats with [inviter]; [store] is what the ViewModel sees. */
    private class Phone(
        private val seed: Int,
    ) {
        val inviter: String = SyntheticUnifiedAddress.mainnet(seed)

        // [test-change-ok: W-137 ZC-922 ZC-633 review follow-up] this test is about the paid Join: it starts from the record a
        // returned first Join leaves (the price flag set), not from a fresh one.
        val prefs =
            StrangerInvitePrefs(consent = false).apply {
                e2eEnabled += inviter
                needsJoinAfterRelayTry = true
            }

        /** Whether the phone knows the inviter's relay key (without one the free reply cannot even be tried). */
        val relayKey = AtomicBoolean(false)

        /** With it, writing a held copy reports that it did not reach the disk (removing one still works). */
        val heldCopyWritesFail = AtomicBoolean(false)

        val store: ZchatPreferences =
            JoinHeldCopy.intercepting(prefs.proxy) { name, args ->
                when {
                    name == "getPeerNostrPubkey" && relayKey.get() && args[0] == inviter -> JoinHeldCopy.RELAY_KEY
                    name == "setHeldGroupAccept" && args[1] != null && heldCopyWritesFail.get() -> false
                    else -> PASS
                }
            }

        /** The held copies of replies on disk, by group id. */
        val heldOnDisk get() = prefs.base.heldGroupAccepts

        /** The saved groups, by group id. */
        val groups get() = prefs.base.groupInfo

        /** The disk as it is now, for the process that starts after a kill at this moment. */
        fun diskCopy(): Phone =
            Phone(seed).also { copy ->
                copy.groups.putAll(groups)
                copy.heldOnDisk.putAll(heldOnDisk)
                copy.prefs.needsJoin.putAll(prefs.needsJoin)
                copy.relayKey.set(relayKey.get())
            }
    }

    private suspend fun screen(
        phone: Phone,
        seam: HandshakeSeam,
        accounts: AccountDataSource? = null,
    ): ChatViewModel =
        chatViewModelWith(phone.store, seam, accountSource = accounts).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
        }

    /** The known contact's invite arrived, its free reply could not go, and it waits for Join. */
    private suspend fun waitingInvite(
        phone: Phone,
        seam: HandshakeSeam,
        groupId: String,
        accounts: AccountDataSource? = null,
    ): ChatViewModel {
        val vm = screen(phone, seam, accounts)
        processInvite(vm, groupId, plaintextInvite(phone.inviter))
        awaitIdle(vm)
        assertTrue("fixture sanity: the invite waits for Join", phone.prefs.needsJoin.containsKey(groupId))
        assertTrue("fixture sanity: the group is saved", phone.groups.containsKey(groupId))
        assertEquals("fixture sanity: nothing is paid before Join", 0, seam.proposalRequests.get())
        return vm
    }

    /** The process ends and the app starts again over [disk]: no process-wide state, a fresh screen, a first tip. */
    private suspend fun restartOver(
        disk: Phone,
        seam: HandshakeSeam,
    ): ChatViewModel {
        HandshakeHarness.cancelHeldAcceptSends()
        AutoSpendVm.resetProcessState()
        // The relays are not connected yet when the new process sees its first block.
        NostrChatBridge.unregisterPublisher()
        val vm = screen(disk, seam)
        chainTip(vm, START_HEIGHT + 1)
        return vm
    }

    /** After the restart: the invite waits for Join, nothing was paid, and one Join sends its one reply. */
    private suspend fun assertWaitsForJoinAndJoinSendsOnce(
        disk: Phone,
        seam: HandshakeSeam,
        after: ChatViewModel,
        groupId: String,
        killedDuring: String,
    ) {
        assertTrue(
            "killed during $killedDuring: nothing on disk holds the reply, so the invite does not wait for Join after the " +
                "restart (the group looks joined and the creator never hears of it)",
            disk.prefs.needsJoin.containsKey(groupId),
        )
        assertEquals("the restart built a payment for the reply", 0, seam.proposalRequests.get())
        assertEquals("the restart paid the reply", 0, seam.submits.get())

        assertTrue("Join after the restart must release the reply", join(after, groupId))
        assertEquals("Join after the restart must send exactly one reply", 1, seam.submits.get())
        assertFalse("a second Join must send nothing", join(after, groupId))
        chainTip(after, START_HEIGHT + 2)
        assertEquals("the reply was paid more than once", 1, seam.submits.get())
        assertTrue("a sent reply must not stay stored", disk.heldOnDisk.isEmpty())
        assertFalse("a sent reply must not wait for Join", disk.prefs.needsJoin.containsKey(groupId))
    }

    // ---- J1: a kill while the relay publish is in flight ---------------------------------------------------------

    @Test
    fun `a kill while Join's relay reply is in flight leaves the invite waiting for Join after the restart`() =
        runBlocking {
            val phone = Phone(seed = 2841)
            val seam = HandshakeSeam(phone.inviter)
            val vm = waitingInvite(phone, seam, "GJOIN2841")

            // The relay key is known now, and the publish never comes back before the kill.
            phone.relayKey.set(true)
            val publishEntered = CompletableDeferred<Unit>()
            val neverAnswers = CompletableDeferred<NostrChatBridge.PublishResult>()
            NostrChatBridge.registerPublisher { _, _ ->
                publishEntered.complete(Unit)
                neverAnswers.await()
            }
            assertTrue("fixture sanity: Join released the reply", joinNoWait(vm, "GJOIN2841"))
            withTimeout(TIMEOUT_MS) { publishEntered.await() }

            val disk = phone.diskCopy()
            val after = restartOver(disk, seam)

            assertWaitsForJoinAndJoinSendsOnce(disk, seam, after, "GJOIN2841", "the relay try")
        }

    @Test
    fun `the process send scope cancelled during Join's relay try, then a new process over the same store, still waits for Join`() =
        runBlocking {
            val phone = Phone(seed = 2842)
            val seam = HandshakeSeam(phone.inviter)
            val vm = waitingInvite(phone, seam, "GJOIN2842")

            phone.relayKey.set(true)
            val publishEntered = CompletableDeferred<Unit>()
            val neverAnswers = CompletableDeferred<NostrChatBridge.PublishResult>()
            NostrChatBridge.registerPublisher { _, _ ->
                publishEntered.complete(Unit)
                neverAnswers.await()
            }
            assertTrue("fixture sanity: Join released the reply", joinNoWait(vm, "GJOIN2842"))
            withTimeout(TIMEOUT_MS) { publishEntered.await() }

            val after = restartOver(phone, seam)

            assertWaitsForJoinAndJoinSendsOnce(phone, seam, after, "GJOIN2842", "the relay try (same store)")
        }

    // ---- J1, second case: a kill while the account is read after the relays refused ------------------------------

    @Test
    fun `a kill while Join reads the account after a failed relay try leaves the invite waiting for Join`() =
        runBlocking {
            val phone = Phone(seed = 2843)
            val seam = HandshakeSeam(phone.inviter)
            val accounts = GatedAccounts(SelectedAccountFeed.create())
            val vm = waitingInvite(phone, seam, "GJOIN2843", accounts)

            // The relays take nothing; the account read that follows never comes back before the kill.
            phone.relayKey.set(true)
            NostrChatBridge.registerPublisher { _, _ ->
                accounts.arm()
                NostrChatBridge.PublishResult(acks = 0, messageId = null)
            }
            assertTrue("fixture sanity: Join released the reply", joinNoWait(vm, "GJOIN2843"))
            withTimeout(TIMEOUT_MS) { accounts.entered.await() }
            assertEquals("fixture sanity: nothing was built before the account was read", 0, seam.proposalRequests.get())

            val disk = phone.diskCopy()
            val after = restartOver(disk, seam)

            assertWaitsForJoinAndJoinSendsOnce(disk, seam, after, "GJOIN2843", "the account read")
        }

    // ---- a Join whose durable copy cannot be written sends nothing -----------------------------------------------

    @Test
    fun `a Join whose held copy cannot be written sends nothing and keeps the invite waiting`() =
        runBlocking {
            val phone = Phone(seed = 2844)
            val seam = HandshakeSeam(phone.inviter)
            val vm = waitingInvite(phone, seam, "GJOIN2844")
            val lookupsBefore = phone.prefs.nostrLookups.get()

            phone.heldCopyWritesFail.set(true)
            assertFalse(
                "Join went on although no durable copy of its reply could be written (a kill would lose the reply)",
                joinNoWait(vm, "GJOIN2844"),
            )
            awaitIdle(vm)
            assertTrue("the invite must keep waiting for Join", phone.prefs.needsJoin.containsKey("GJOIN2844"))
            assertEquals("the relays were tried for a Join that was refused", lookupsBefore, phone.prefs.nostrLookups.get())
            assertEquals(0, seam.proposalRequests.get())
            assertFalse("a refused Join left its reply held in memory", heldInMemory("GJOIN2844"))

            // The store works again: the same invite is joined and its one reply is sent.
            phone.heldCopyWritesFail.set(false)
            assertTrue(join(vm, "GJOIN2844"))
            assertEquals(1, seam.submits.get())
            assertTrue(phone.heldOnDisk.isEmpty())
            assertFalse(phone.prefs.needsJoin.containsKey("GJOIN2844"))
        }

    // ---- controls (pass before and after) ------------------------------------------------------------------------

    @Test
    fun `control - with the relays up Join sends one free reply and leaves no held copy and no record, also after a restart`() =
        runBlocking {
            val phone = Phone(seed = 2845)
            val seam = HandshakeSeam(phone.inviter)
            val vm = waitingInvite(phone, seam, "GJOIN2845")

            phone.relayKey.set(true)
            val relayed: MutableList<String> = Collections.synchronizedList(mutableListOf())
            NostrChatBridge.registerPublisher { memo, _ ->
                relayed += memo
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-relay-ack")
            }
            assertTrue(join(vm, "GJOIN2845"))

            assertEquals("the reply must go over the relays once", 1, relayed.size)
            assertEquals("a relay reply must cost nothing", 0, seam.proposalRequests.get())
            assertTrue("a delivered reply left its held copy on disk", phone.heldOnDisk.isEmpty())
            assertFalse("a delivered reply stays held in memory", heldInMemory("GJOIN2845"))
            assertFalse("a delivered reply still waits for Join", phone.prefs.needsJoin.containsKey("GJOIN2845"))
            assertFalse("a second Join must send nothing", join(vm, "GJOIN2845"))
            assertEquals(1, relayed.size)

            val after = restartOver(phone, seam)
            assertFalse("a delivered reply came back as waiting for Join", phone.prefs.needsJoin.containsKey("GJOIN2845"))
            assertFalse(join(after, "GJOIN2845"))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `control - with the relays down Join pays exactly one reply, a second Join and a restart send nothing`() =
        runBlocking {
            val phone = Phone(seed = 2846)
            val storedAtSubmit: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
            val seam =
                HandshakeSeam(
                    peer = phone.inviter,
                    onSubmit = { storedAtSubmit += phone.heldOnDisk.containsKey("GJOIN2846") },
                )
            val vm = waitingInvite(phone, seam, "GJOIN2846")

            assertTrue(join(vm, "GJOIN2846"))
            assertEquals("Join must build exactly one payment", 1, seam.proposalRequests.get())
            assertEquals("Join must pay exactly one reply", 1, seam.submits.get())
            assertEquals("the held copy must be gone before the submit (R4-1)", listOf(false), storedAtSubmit.toList())
            assertFalse("a second Join must send nothing", join(vm, "GJOIN2846"))

            val after = restartOver(phone, seam)
            assertFalse("a paid reply came back as waiting for Join", phone.prefs.needsJoin.containsKey("GJOIN2846"))
            assertFalse(join(after, "GJOIN2846"))
            assertEquals("the reply was paid more than once", 1, seam.submits.get())
            assertEquals(1, seam.proposalRequests.get())
        }

    @Test
    fun `control - a Join whose waiting record cannot be cleared sends nothing and leaves no held copy`() =
        runBlocking {
            val phone = Phone(seed = 2847)
            val seam = HandshakeSeam(phone.inviter)
            val vm = waitingInvite(phone, seam, "GJOIN2847")

            phone.prefs.needsJoinWritesFail.set(true)
            assertFalse("Join must not send while its record cannot be cleared", joinNoWait(vm, "GJOIN2847"))
            awaitIdle(vm)
            assertEquals(0, seam.proposalRequests.get())
            assertTrue("a refused Join left a held copy on disk", phone.heldOnDisk.isEmpty())
            assertFalse("a refused Join left its reply held in memory", heldInMemory("GJOIN2847"))
            assertTrue(phone.prefs.needsJoin.containsKey("GJOIN2847"))

            phone.prefs.needsJoinWritesFail.set(false)
            assertTrue(join(vm, "GJOIN2847"))
            assertEquals(1, seam.submits.get())
        }
}
