package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Round 7c C2 — B3 review items 9 and 10: the held group replies in memory belong to one wallet.
 *
 * A held group reply ("I joined", a paid GROUP_ACCEPT) is kept in the wallet's own store (B3) and, while the app
 * runs, in a map shared by every chat screen, with a "loaded once" flag. A change of recovery phrase in the same
 * process (Delete Wallet keeping files, then restoring another phrase) swaps the stores but left that map and
 * flag as they were, so:
 *  - item 9: wallet B's next block pays wallet A's signed reply from B's funds when B is in the same group, and A
 *    pays it again once A is back (A's own copy is still in A's stores); a reply A was sending when the phrase
 *    changed is written into B's store, or retried after a block wait from B's funds; B's user is asked to pay
 *    for A's reply;
 *  - item 10: after A → B → A, A's reply is not loaded again until the app is restarted.
 *
 * Wanted: a phrase change drops the previous wallet's replies from memory (never from its stores) and loads the
 * new wallet's; nothing claimed under one wallet is paid, stored or offered under another.
 *
 * The phrase change is [TwoWallets.claim]: the live stores switch to the other wallet's and the stores' reload
 * counter goes up, as ChatWalletOwnership.claimFor → ZchatPreferences.reloadWalletScopedStores does. All ids,
 * addresses and memos are synthetic.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): a paid group reply is no longer held for the standing consent; it waits for Join (a
 * needs-join record in the wallet's own stores), Join pays it, and a reply a test build stored for the consent becomes
 * a needs-join record at the first block of its own wallet. The rules above hold for those: nothing of one wallet is
 * written, paid or offered under another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldGroupAcceptBelongsToItsWalletTest {
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

    /** The live stores of one phone, which belong to wallet [a] or wallet [b]; the ViewModel sees [proxy]. */
    private class TwoWallets(
        val a: AutoSpendPrefs,
        val b: AutoSpendPrefs,
    ) {
        @Volatile var current: AutoSpendPrefs = a
        private val reloads = AtomicLong(0)

        /** A phrase change: [wallet]'s stores become the live ones and every in-memory copy is re-read. */
        fun claim(wallet: AutoSpendPrefs) {
            current = wallet
            reloads.incrementAndGet()
        }

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "walletScopeGeneration" -> reloads.get()
                    "toString" -> "TwoWallets"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else ->
                        try {
                            method.invoke(current.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
    }

    private fun invitePayload(inviter: String): String {
        val members = JSONArray()
        members.put(inviter)
        members.put(HandshakeHarness.SELF)
        val invite = JSONObject()
        invite.put("name", "Synthetic group")
        invite.put("inviter", inviter)
        invite.put("group_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
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

    private suspend fun chainTip(vm: ChatViewModel) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, HandshakeHarness.START_HEIGHT)
        awaitIdle(vm)
    }

    private suspend fun screen(
        prefs: ZchatPreferences,
        seam: HandshakeSeam,
        heights: HeightFeed = HeightFeed(),
        ironwoodPending: Long = 0L,
    ): ChatViewModel = chatViewModelWith(prefs, seam, heights, ironwoodPending).also(AutoSpendVm::wireBootstrapFields)

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

    /** Round 8 P1: [wallet]'s waiting reply for [groupId] as a test build stored it for the consent (R3-4 record). */
    private fun storedForConsentByATestBuild(
        wallet: AutoSpendPrefs,
        groupId: String,
    ) {
        val record = wallet.needsJoin.remove(groupId)
        assertTrue("fixture sanity: the reply waited for Join", record != null)
        wallet.heldGroupAccepts[groupId] = record!!
    }

    /** Wallet A joined [groupId]; wallet B's stores hold the same group (both phrases were invited). */
    private fun bothJoined(
        wallets: TwoWallets,
        groupId: String
    ) {
        val info = wallets.a.groupInfo[groupId]
        assertTrue("fixture sanity: wallet A joined the group", info != null)
        wallets.b.groupInfo[groupId] = info!!
    }

    @Test
    fun `wallet B never pays wallet A's held reply for a group both joined, and A pays it once when back`() =
        runBlocking {
            val inviter = "usyntheticheldwallet01"
            val wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                    AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) },
                )
            val seam = HandshakeSeam(inviter)
            val underA = screen(wallets.proxy, seam)
            processInvite(underA, "GSYNTHW1", inviter)
            awaitIdle(underA)
            bothJoined(wallets, "GSYNTHW1")
            assertEquals("fixture sanity: A's reply waits for A's Join", 0, seam.proposalRequests.get())
            assertTrue("fixture sanity: A's reply is in A's store", wallets.a.needsJoin.containsKey("GSYNTHW1"))

            wallets.claim(wallets.b)
            val underB = screen(wallets.proxy, seam)
            chainTip(underB)
            GroupInviteDriver.join(underB, "GSYNTHW1")

            assertEquals("wallet B paid wallet A's group reply from B's funds", 0, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
            assertTrue("wallet A's reply was written into wallet B's store", wallets.b.heldGroupAccepts.isEmpty())
            assertTrue("wallet A's reply was written into wallet B's store", wallets.b.needsJoin.isEmpty())
            assertTrue("wallet A's own copy must stay in A's store", wallets.a.needsJoin.containsKey("GSYNTHW1"))

            // A is restored in the same process and taps Join: its reply is sent once, here and after a restart.
            wallets.claim(wallets.a)
            wallets.a.consent = true
            val backToA = screen(wallets.proxy, seam)
            chainTip(backToA)
            assertEquals("a block paid wallet A's reply that waits for Join", 0, seam.submits.get())
            GroupInviteDriver.join(backToA, "GSYNTHW1")
            assertEquals("wallet A's reply must be sent once when A is back and joins", 1, seam.submits.get())
            chainTip(backToA)
            AutoSpendVm.resetProcessState()
            val restarted = screen(wallets.proxy, seam)
            chainTip(restarted)
            GroupInviteDriver.join(restarted, "GSYNTHW1")
            assertEquals("wallet A's reply was paid twice", 1, seam.submits.get())
            assertEquals(1, seam.broadcasts.size)
            assertTrue(wallets.a.heldGroupAccepts.isEmpty())
            assertTrue(wallets.b.heldGroupAccepts.isEmpty())
        }

    @Test
    fun `wallet B is not asked to pay for wallet A's held reply`() =
        runBlocking {
            val inviter = "usyntheticheldwallet02"
            val wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                )
            val seam = HandshakeSeam(inviter)
            val underA = screen(wallets.proxy, seam)
            processInvite(underA, "GSYNTHW2", inviter)
            awaitIdle(underA)
            bothJoined(wallets, "GSYNTHW2")

            wallets.claim(wallets.b)
            val underB = screen(wallets.proxy, seam)
            chainTip(underB)
            assertFalse(
                "wallet B's user was asked to pay for wallet A's group reply",
                HandshakeHarness.sendMessageState(underB) is SendMessageState.NeedsHandshakeSpendConsent,
            )

            underB.grantHandshakeSpendConsent()
            awaitIdle(underB)
            // Round 8 P1: nor offered by B's group screen (B has no waiting invite for it).
            assertFalse("wallet B's Join answered wallet A's invite", GroupInviteDriver.join(underB, "GSYNTHW2"))
            assertEquals("wallet B paid wallet A's group reply after B's consent", 0, seam.proposalRequests.get())
            assertTrue(wallets.b.heldGroupAccepts.isEmpty())
            assertTrue(wallets.b.needsJoin.isEmpty())
            assertTrue(wallets.a.needsJoin.containsKey("GSYNTHW2"))
        }

    @Test
    fun `a reply A was sending when the phrase changed is not put into wallet B's store`() =
        runBlocking {
            val inviter = "usyntheticheldwallet03"
            lateinit var wallets: TwoWallets
            // The phrase changes while A's attempt is on the wire; the attempt then provably sends nothing.
            val seam =
                HandshakeSeam(
                    peer = inviter,
                    failFirstProposalWith = InsufficientFundsException("synthetic genuine shortfall"),
                    onFirstAttempt = {
                        wallets.b.groupInfo.putAll(wallets.a.groupInfo)
                        wallets.claim(wallets.b)
                    },
                )
            wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) },
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                )
            val underA = screen(wallets.proxy, seam)
            processInvite(underA, "GSYNTHW3", inviter)
            awaitIdle(underA)
            GroupInviteDriver.join(underA, "GSYNTHW3")
            assertEquals("fixture sanity: A's reply was attempted once", 1, seam.proposalRequests.get())
            assertTrue("fixture sanity: the phrase changed during the attempt", wallets.current === wallets.b)

            assertTrue(
                "wallet A's reply was written into wallet B's store: ${wallets.b.heldGroupAccepts.keys}",
                wallets.b.heldGroupAccepts.isEmpty(),
            )
            assertTrue(
                "wallet A's reply was written into wallet B's store as waiting for Join: ${wallets.b.needsJoin.keys}",
                wallets.b.needsJoin.isEmpty(),
            )
            assertTrue("wallet A's reply must stay in A's own store", wallets.a.heldGroupAccepts.containsKey("GSYNTHW3"))
            val underB = screen(wallets.proxy, seam)
            underB.grantHandshakeSpendConsent()
            awaitIdle(underB)
            chainTip(underB)
            GroupInviteDriver.join(underB, "GSYNTHW3")
            assertEquals("wallet B paid wallet A's reply", 1, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
        }

    @Test
    fun `a reply A was retrying after a block wait is not paid from wallet B`() =
        runBlocking {
            val inviter = "usyntheticheldwallet04"
            val wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) },
                    AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) },
                )
            // A's first attempt meets funds still confirming, so it waits for the next block.
            val seam = HandshakeSeam(inviter, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val underA = screen(wallets.proxy, seam, heights, ironwoodPending = 200_000L)
            processInvite(underA, "GSYNTHW4", inviter)
            awaitIdle(underA)
            joinNoWait(underA, "GSYNTHW4")
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the first attempt hit the shortfall", 1, seam.proposalRequests.get())

            bothJoined(wallets, "GSYNTHW4")
            wallets.claim(wallets.b)
            heights.advance()
            awaitIdle(underA)

            assertEquals("wallet A's reply was retried from wallet B's funds after the block", 1, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
            assertTrue(wallets.b.heldGroupAccepts.isEmpty())
            assertTrue("wallet A's reply was written into wallet B's store as waiting for Join", wallets.b.needsJoin.isEmpty())
        }

    /**
     * Round 8 P1: A's reply was stored for the consent by a test build. It is turned into A's waiting invite when A is
     * live again in the same process (it was loaded and paid under A's consent), never under B; Join then sends it once.
     */
    @Test
    fun `A to B to A in one process turns A's stored reply into A's waiting invite without a restart`() =
        runBlocking {
            val inviter = "usyntheticheldwallet05"
            val wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                    // B never joined the group.
                    AutoSpendPrefs(consent = true),
                )
            val seam = HandshakeSeam(inviter)
            val underA = screen(wallets.proxy, seam)
            chainTip(underA)
            processInvite(underA, "GSYNTHW5", inviter)
            awaitIdle(underA)
            storedForConsentByATestBuild(wallets.a, "GSYNTHW5")
            assertTrue("fixture sanity: A's reply is held", wallets.a.heldGroupAccepts.containsKey("GSYNTHW5"))

            wallets.claim(wallets.b)
            chainTip(screen(wallets.proxy, seam))
            assertEquals(0, seam.proposalRequests.get())
            assertTrue("wallet A's own copy must stay in A's store", wallets.a.heldGroupAccepts.containsKey("GSYNTHW5"))
            assertTrue("wallet A's reply was turned into wallet B's waiting invite", wallets.b.needsJoin.isEmpty())

            // A comes back in the same process; A had agreed to pay before (its reply waited for funds).
            wallets.claim(wallets.a)
            wallets.a.consent = true
            val backToA = screen(wallets.proxy, seam)
            chainTip(backToA)
            assertEquals("the standing consent paid wallet A's group reply", 0, seam.submits.get())
            assertTrue(
                "wallet A's stored reply is not turned into its waiting invite until the app is restarted",
                wallets.a.needsJoin.containsKey("GSYNTHW5"),
            )
            GroupInviteDriver.join(backToA, "GSYNTHW5")
            assertEquals("wallet A's reply must be sent once by Join", 1, seam.submits.get())
            chainTip(backToA)
            assertEquals("wallet A's held reply was paid twice", 1, seam.submits.get())
            assertTrue(wallets.a.heldGroupAccepts.isEmpty())
        }

    /** Control: the live wallet pays its own reply once (Round 8 P1: by Join, not by its consent). */
    @Test
    fun `control - wallet B's own reply is paid once from B after Join`() =
        runBlocking {
            val inviter = "usyntheticheldwallet06"
            val wallets =
                TwoWallets(
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                    AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) },
                )
            val seam = HandshakeSeam(inviter)
            wallets.claim(wallets.b)
            val underB = screen(wallets.proxy, seam)
            processInvite(underB, "GSYNTHW6", inviter)
            awaitIdle(underB)
            assertTrue(wallets.b.needsJoin.containsKey("GSYNTHW6"))

            underB.grantHandshakeSpendConsent()
            awaitIdle(underB)
            chainTip(underB)
            assertEquals("the standing consent paid a group join", 0, seam.submits.get())
            GroupInviteDriver.join(underB, "GSYNTHW6")
            assertEquals(1, seam.submits.get())
            assertTrue(wallets.b.heldGroupAccepts.isEmpty())
            assertTrue(wallets.a.heldGroupAccepts.isEmpty())
        }
}
