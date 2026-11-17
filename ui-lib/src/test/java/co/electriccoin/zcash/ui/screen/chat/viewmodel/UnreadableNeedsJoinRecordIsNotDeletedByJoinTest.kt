package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.heldInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.join
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.processInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.PASS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.JoinHeldCopy.joinNoWait
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-284 (R8 findings, SPECS §6, sub-case) — Join deletes a waiting record it could not read.
 *
 * When the store throws while Join reads the invite's waiting record, the read is answered with a stand-in ("the invite
 * counts as waiting", so nothing is paid). Join then fails to decode that stand-in, and still deletes the real record
 * before it gives up: the reply is gone, the Join banner disappears, and the creator keeps us Invited.
 *
 * Wanted: a record that cannot be read is never deleted. Join answers false, writes nothing and sends nothing; once the
 * store reads again, the same invite is joined and its one reply is sent. A record that reads but is not a reply (a
 * damaged one) is still cleared, as before.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreadableNeedsJoinRecordIsNotDeletedByJoinTest {
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

    /** A phone that already chats with [inviter], whose waiting-record reads can be made to throw. */
    private class Phone(
        seed: Int,
    ) {
        val inviter: String = SyntheticUnifiedAddress.mainnet(seed)

        // [test-change-ok: W-137 ZC-922 ZC-633 review follow-up] this test is about the paid Join: it starts from the record a
        // returned first Join leaves (the price flag set), not from a fresh one.
        val prefs =
            StrangerInvitePrefs(consent = false).apply {
                e2eEnabled += inviter
                needsJoinAfterRelayTry = true
            }

        /** The held copies of replies on disk, by group id. */
        val heldOnDisk get() = prefs.base.heldGroupAccepts

        /** How many of the next waiting-record reads throw. */
        val failReads = AtomicInteger(0)

        /** The group ids whose waiting record a caller asked to delete. */
        val clears: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val store: ZchatPreferences =
            JoinHeldCopy.intercepting(prefs.proxy) { name, args ->
                when {
                    name == "getGroupInviteNeedsJoin" && failReads.getAndUpdate { if (it > 0) it - 1 else 0 } > 0 ->
                        throw IllegalStateException("synthetic: the encrypted store could not be read")
                    name == "setGroupInviteNeedsJoin" && args[1] == null -> {
                        clears += args[0] as String
                        PASS
                    }
                    else -> PASS
                }
            }
    }

    private suspend fun waitingInvite(
        phone: Phone,
        seam: HandshakeSeam,
        groupId: String,
    ): ChatViewModel {
        val vm =
            chatViewModelWith(phone.store, seam).also { vm ->
                AutoSpendVm.wireBootstrapFields(vm)
                AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
            }
        processInvite(vm, groupId, plaintextInvite(phone.inviter))
        awaitIdle(vm)
        assertTrue("fixture sanity: the invite waits for Join", phone.prefs.needsJoin.containsKey(groupId))
        phone.clears.clear()
        return vm
    }

    @Test
    fun `a Join whose record read throws deletes nothing, sends nothing, and the next Join sends the one reply`() =
        runBlocking {
            val phone = Phone(seed = 2848)
            val seam = HandshakeSeam(phone.inviter)
            val vm = waitingInvite(phone, seam, "GJOIN2848")
            val record = phone.prefs.needsJoin.getValue("GJOIN2848")

            phone.failReads.set(1)
            assertFalse("Join must answer false when its record cannot be read", joinNoWait(vm, "GJOIN2848"))
            awaitIdle(vm)

            assertTrue(
                "Join asked the store to delete a waiting record it could not read: ${phone.clears}",
                phone.clears.isEmpty(),
            )
            assertEquals("the waiting record is gone or changed", record, phone.prefs.needsJoin["GJOIN2848"])
            assertEquals(0, seam.proposalRequests.get())
            assertTrue(phone.heldOnDisk.isEmpty())
            assertFalse(heldInMemory("GJOIN2848"))

            // The store reads again: the invite is still there, and Join sends its one reply.
            assertTrue("the invite could not be joined once its record read again", join(vm, "GJOIN2848"))
            assertEquals("Join must pay exactly one reply", 1, seam.submits.get())
            assertFalse(phone.prefs.needsJoin.containsKey("GJOIN2848"))
            assertFalse("a second Join must send nothing", join(vm, "GJOIN2848"))
            assertEquals(1, seam.submits.get())
        }

    /** Control (passes before and after): a record that reads but is not a reply is cleared, and nothing is sent. */
    @Test
    fun `control - a damaged record that reads is cleared by Join and nothing is sent`() =
        runBlocking {
            val phone = Phone(seed = 2849)
            val seam = HandshakeSeam(phone.inviter)
            val vm = waitingInvite(phone, seam, "GJOIN2849")
            phone.prefs.needsJoin["GJOIN2849"] = "synthetic: not a stored reply"

            assertFalse(joinNoWait(vm, "GJOIN2849"))
            awaitIdle(vm)

            assertFalse("a damaged record must not keep the banner up", phone.prefs.needsJoin.containsKey("GJOIN2849"))
            assertEquals(0, seam.proposalRequests.get())
            assertTrue(phone.heldOnDisk.isEmpty())
            assertFalse(heldInMemory("GJOIN2849"))
        }
}
