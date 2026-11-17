package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * C5 (round 7 review of R6-4, finding 2) — a held GROUP_ACCEPT claimed by a screen is lost when that screen closes.
 *
 * Our paid "I joined" answer to a group invite is held until the wallet can pay it, and with consent given every
 * block tries it again (R6-4). The attempt ran in the screen's own ViewModel scope. The claim took it out of the
 * process-wide map and, with consent, deleted its stored copy; a "funds still confirming" shortfall then waits for
 * the next block. Closing that screen cancels the wait, nothing puts the accept back, and it is gone from memory
 * and from disk: no block, start or grant sends it again, and the creator keeps us Invited.
 *
 * Wanted:
 * - the attempt outlives the screen that started it, and the stored copy stays on disk until the moment the
 *   transaction is handed to the SDK (so a kill during the wait loses nothing either);
 * - an attempt cancelled before its submit started puts the accept back, in memory and on disk;
 * - the accept is still paid exactly once, and never while its stored copy could not be removed (R4-1).
 *
 * Round 8 P1 (PHONE_TRIAGE F3): the paid reply is attempted only after the group screen's Join (it was attempted under
 * the standing consent, claimed by a block), and a reply that was not sent goes back to waiting for Join (it was held
 * again in memory for the next block). The guards above hold on that path.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldGroupAcceptSurvivesScreenCloseTest {
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

    /** The screen goes away: its ViewModelStore is cleared, which cancels the ViewModel's own scope. */
    private fun closeScreen(vm: ChatViewModel) {
        ViewModelStore().apply { put("chat", vm) }.clear()
    }

    private fun heldInMemory(groupId: String): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAccepts")
            .apply { isAccessible = true }
            .let { (it.get(null) as Map<*, *>).containsKey(groupId) }

    private suspend fun screen(
        prefs: ZchatPreferences,
        seam: HandshakeSeam,
        heights: HeightFeed,
        accounts: SelectedAccountFeed,
    ): ChatViewModel = chatViewModelWith(prefs, seam, heights, accountSource = accounts).also(AutoSpendVm::wireBootstrapFields)

    @Test
    fun `closing the screen whose Join claimed the accept does not lose it, and it is sent once when funds confirm`() =
        runBlocking {
            val inviter = "usyntheticc5screenclose01"
            val groupId = "GSYNTHC54"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val accounts = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
            val heights = HeightFeed()
            val storedAtSubmit: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
            val seam =
                HandshakeSeam(
                    peer = inviter,
                    failFirstProposalWith = stillConfirming(),
                    onSubmit = { storedAtSubmit += prefs.heldGroupAccepts.containsKey(groupId) },
                )
            val first = screen(prefs.proxy, seam, heights, accounts)
            processInvite(first, groupId, inviter)
            awaitIdle(first)
            assertTrue("fixture sanity: the accept waits for Join on disk", prefs.needsJoin.containsKey(groupId))
            assertEquals("fixture sanity: nothing is paid without consent", 0, seam.proposalRequests.get())

            // The user taps Join on this screen; the attempt meets the "funds still confirming" shortfall, so it waits
            // for the next block.
            joinNoWait(first, groupId)
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the Join's attempt met the shortfall", 1, seam.proposalRequests.get())

            closeScreen(first)
            assertTrue(
                "the accept's stored copy was deleted when the block claimed it, before anything was handed to the " +
                    "SDK; with the screen closed nothing sends it again and a restart cannot restore it",
                prefs.heldGroupAccepts.containsKey(groupId),
            )
            assertTrue(
                "closing the screen cancelled the claimed accept's attempt and nothing put it back",
                heldInMemory(groupId) || heights.isWaiting(),
            )

            // The deposit confirms; another screen sees the next blocks.
            accounts.ironwoodSpendable()
            val second = screen(prefs.proxy, seam, heights, accounts)
            heights.advance()
            awaitIdle(second)
            tip(second, HandshakeHarness.START_HEIGHT + 2)
            awaitIdle(second)
            assertEquals("the held accept was never sent after its screen closed", 1, seam.submits.get())
            tip(second, HandshakeHarness.START_HEIGHT + 3)
            awaitIdle(second)
            assertEquals("the held accept was paid twice", 1, seam.submits.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals("the stored copy must be gone before the submit (R4-1)", listOf(false), storedAtSubmit.toList())
            assertTrue("a sent accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
            assertFalse("a sent accept must not stay held", heldInMemory(groupId))
        }

    @Test
    fun `an attempt cancelled while it waits for a block puts the accept back on disk as waiting for Join`() =
        runBlocking {
            val inviter = "usyntheticc5screenclose02"
            val groupId = "GSYNTHC55"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val accounts = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
            val heights = HeightFeed()
            val seam = HandshakeSeam(inviter, failFirstProposalWith = stillConfirming())
            val first = screen(prefs.proxy, seam, heights, accounts)

            // Round 8 P1: the Join's attempt claims the accept and waits for the next block.
            processInvite(first, groupId, inviter)
            awaitIdle(first)
            joinNoWait(first, groupId)
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the attempt met the shortfall", 1, seam.proposalRequests.get())

            // The attempt itself is cancelled before any submit (the screen goes, and so does the send's scope).
            closeScreen(first)
            HandshakeHarness.cancelHeldAcceptSends()
            awaitTrue { !heights.isWaiting() }

            // Round 8 P1: put back as waiting for Join (it was put back in memory and as a held copy for the next block).
            assertTrue(
                "an attempt cancelled before its submit started lost the accept (it does not wait for Join again)",
                prefs.needsJoin.containsKey(groupId),
            )
            assertFalse("a cancelled accept stays held in memory for the automatic sends", heldInMemory(groupId))
            assertEquals(0, seam.submits.get())

            accounts.ironwoodSpendable()
            val second = screen(prefs.proxy, seam, heights, accounts)
            tip(second, HandshakeHarness.START_HEIGHT + 1)
            awaitIdle(second)
            assertEquals("a block paid a reply that waits for Join", 0, seam.submits.get())
            GroupInviteDriver.join(second, groupId)
            assertEquals("the accept put back after the cancel must be sent once by Join", 1, seam.submits.get())
            tip(second, HandshakeHarness.START_HEIGHT + 2)
            awaitIdle(second)
            assertEquals("the held accept was paid twice", 1, seam.submits.get())
            assertTrue(prefs.heldGroupAccepts.isEmpty())
            assertFalse(heldInMemory(groupId))
        }

    /**
     * Guard (passes before and after): the stored copy of an accept whose attempt waits for a block is not loaded
     * again by the first block of the process and paid a second time.
     */
    @Test
    fun `guard - an accept whose attempt waits for a block is not loaded again by the first block and paid twice`() =
        runBlocking {
            val inviter = "usyntheticc5screenclose03"
            val groupId = "GSYNTHC56"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val accounts = SelectedAccountFeed.create().apply { onlyConfirmingIronwood() }
            val heights = HeightFeed()
            val seam = HandshakeSeam(inviter, failFirstProposalWith = stillConfirming())
            val vm = screen(prefs.proxy, seam, heights, accounts)

            processInvite(vm, groupId, inviter)
            awaitIdle(vm)
            joinNoWait(vm, groupId)
            awaitTrue { heights.isWaiting() }
            // The first block of the process handles the stored held accepts while this one is still claimed.
            tip(vm, HandshakeHarness.START_HEIGHT + 1)
            accounts.ironwoodSpendable()
            heights.advance()
            awaitIdle(vm)
            tip(vm, HandshakeHarness.START_HEIGHT + 2)
            awaitIdle(vm)
            tip(vm, HandshakeHarness.START_HEIGHT + 3)
            awaitIdle(vm)

            assertEquals("the accept was paid twice", 1, seam.submits.get())
            assertEquals(1, seam.broadcasts.size)
            assertTrue(prefs.heldGroupAccepts.isEmpty())
            assertFalse(heldInMemory(groupId))
            assertTrue("a sent accept was turned back into an invite that waits for Join", prefs.needsJoin.isEmpty())
        }

    /**
     * Control: R4-1 per attempt. A stored copy that cannot be removed before the submit sends nothing and keeps the
     * accept (Round 8 P1: its stored copy stays and it waits for Join again, instead of staying held in memory for the
     * next block); once the store works, the next Join sends it once.
     */
    @Test
    fun `control - a stored copy that cannot be removed sends nothing and keeps the accept`() =
        runBlocking {
            val inviter = "usyntheticc5screenclose04"
            val groupId = "GSYNTHC57"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val removalFails = AtomicBoolean(true)
            val store: ZchatPreferences =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { _, method, args ->
                    if (removalFails.get() && method.name == "setHeldGroupAccept" && args?.get(1) == null) {
                        false
                    } else {
                        try {
                            method.invoke(prefs.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                } as ZchatPreferences
            val accounts = SelectedAccountFeed.create()
            val heights = HeightFeed()
            val seam = HandshakeSeam(inviter)
            val vm = screen(store, seam, heights, accounts)

            processInvite(vm, groupId, inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, groupId)
            assertEquals(
                "the accept was handed to the SDK although its stored copy is still on disk; a restart pays it again",
                0,
                seam.submits.get(),
            )
            assertTrue("the accept must wait for Join again", prefs.needsJoin.containsKey(groupId))
            assertTrue("the accept must stay held on disk", prefs.heldGroupAccepts.containsKey(groupId))

            removalFails.set(false)
            tip(vm, HandshakeHarness.START_HEIGHT + 1)
            awaitIdle(vm)
            assertEquals("a block paid a reply that waits for Join", 0, seam.submits.get())
            GroupInviteDriver.join(vm, groupId)
            assertEquals(1, seam.submits.get())
            tip(vm, HandshakeHarness.START_HEIGHT + 2)
            awaitIdle(vm)
            assertEquals(1, seam.submits.get())
            assertTrue(prefs.heldGroupAccepts.isEmpty())
        }
}
