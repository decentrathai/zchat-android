package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * C5 (round 7 review of R6-4, finding 6, and REVIEW_B1-B3 item 9 note 5) — the automatic-spend question counts
 * group joins that will never be paid.
 *
 * Without consent, a held GROUP_ACCEPT is queued for the question ("a group join is waiting", and one more paid
 * transaction in the quote). When the user leaves that group, the next block drops the accept for good, but its
 * queued entry stays. The next question (a ZBOOT, say) then still says a group join is waiting and quotes its
 * transaction, while Allow pays nothing for it. The same happens after a change of recovery phrase in the same
 * process: wallet B's question counts wallet A's queued setups.
 *
 * Wanted: the question counts only what agreeing can pay. All addresses, keys and group ids are synthetic.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): agreeing never pays a group join any more (a paid group reply waits for Join), so no
 * question counts or quotes one; the phrase-change case is kept with a KEX queued under wallet A.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentQuestionCountsOnlyLiveJoinsTest {
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
    ): ChatViewModel = chatViewModelWith(prefs, seam).also(AutoSpendVm::wireBootstrapFields)

    private fun question(vm: ChatViewModel): SendMessageState.NeedsHandshakeSpendConsent {
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("the consent question must be raised, but the screen shows $state", state is SendMessageState.NeedsHandshakeSpendConsent)
        return state as SendMessageState.NeedsHandshakeSpendConsent
    }

    /** A Tunnel chat whose key exchange is complete and whose ZBOOT is due raises the question on [prefs]. */
    private suspend fun zbootQuestion(
        prefs: ZchatPreferences,
        peer: String,
    ): SendMessageState.NeedsHandshakeSpendConsent {
        AutoSpendVm.setHistoryRead(true)
        val seam = HandshakeSeam(peer)
        val vm = screen(prefs, seam)
        AutoSpendVm.sendNostrBoot(vm, peer)
        awaitIdle(vm)
        assertEquals("nothing may be paid without consent", 0, seam.proposalRequests.get())
        return question(vm)
    }

    /** "Leave group": the group info stays, marked inactive (GroupViewModel.leaveGroup). */
    private fun leave(
        prefs: AutoSpendPrefs,
        groupId: String
    ) {
        val info = ZMSGGroupProtocol.deserializeGroupInfo(prefs.groupInfo.getValue(groupId))
        assertTrue("fixture sanity: the joined group reads back", info != null)
        prefs.groupInfo[groupId] = ZMSGGroupProtocol.serializeGroupInfo(info!!.copy(isActive = false))
    }

    @Test
    fun `after the user leaves a group the question no longer counts or quotes its dropped join`() =
        runBlocking {
            val inviter = "usyntheticc5leftgroup01"
            val peer = "usyntheticc5leftgroupzb01"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val first = screen(prefs.proxy, HandshakeSeam(inviter))
            processInvite(first, "GSYNTHC58", inviter)
            awaitIdle(first)
            // Round 8 P1: the join is asked about by Join, not by the consent question.
            assertTrue("fixture sanity: the join waits for Join", prefs.needsJoin.containsKey("GSYNTHC58"))

            leave(prefs, "GSYNTHC58")
            chainTip(first)
            assertTrue("fixture sanity: the block dropped the left group's accept", prefs.heldGroupAccepts.isEmpty())

            prefs.session(peer, complete = true, mode = ConversationMode.TUNNEL)
            val asked = zbootQuestion(prefs.proxy, peer)
            assertEquals(
                "the question says a group join is waiting, but that group was left and its join dropped",
                0,
                asked.groupJoins,
            )
            assertEquals("the question quotes the dropped join's transaction", 1, asked.pendingPeers)
        }

    @Test
    fun `after a change of recovery phrase the question does not count the previous wallet's queued setups`() =
        runBlocking {
            val inviter = "usyntheticc5phrase0001"
            val peer = "usyntheticc5phrasezb001"
            val a = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val b = AutoSpendPrefs(consent = false).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val wallets = TwoWallets(a, b)
            val underA = screen(wallets.proxy, HandshakeSeam(inviter))
            processInvite(underA, "GSYNTHC59", inviter)
            awaitIdle(underA)
            assertTrue("fixture sanity: wallet A's join waits for Join", a.needsJoin.containsKey("GSYNTHC59"))
            // Round 8 P1: a group join is never queued for the question; wallet A's queued setup is a KEX.
            val kexPeer = "usyntheticc5phrasekex01"
            a.session(kexPeer, complete = false)
            a.ownBootSent[kexPeer] = true
            underA.sendKEXMessage(kexPeer, HandshakeHarness.SELF)
            awaitIdle(underA)
            // Round 10b R10B-1 (F-R9C-5): a Shielded chat's ZBOOT after this KEX is never paid unquoted, so the KEX alone.
            assertEquals("fixture sanity: wallet A's KEX is asked about", 1, question(underA).pendingPeers)

            wallets.claim(b)
            val asked = zbootQuestion(wallets.proxy, peer)
            assertEquals("wallet B's question counts wallet A's group join", 0, asked.groupJoins)
            assertEquals("wallet B's question quotes wallet A's transactions", 1, asked.pendingPeers)
        }

    /**
     * Control: a join still active. Round 8 P1: it waits for Join and is not counted or quoted by the next question (it
     * was held and counted: 1 group join, 2 transactions).
     */
    @Test
    fun `control - a join still active waits for Join and is not counted or quoted by a later question`() =
        runBlocking {
            val inviter = "usyntheticc5heldjoin01"
            val peer = "usyntheticc5heldjoinzb01"
            val prefs = AutoSpendPrefs(consent = false).apply { session(inviter, complete = true) }
            val first = screen(prefs.proxy, HandshakeSeam(inviter))
            processInvite(first, "GSYNTHC60", inviter)
            awaitIdle(first)
            chainTip(first)
            assertTrue("fixture sanity: the join still waits for Join", prefs.needsJoin.containsKey("GSYNTHC60"))

            prefs.session(peer, complete = true, mode = ConversationMode.TUNNEL)
            val asked = zbootQuestion(prefs.proxy, peer)
            assertEquals("the question counts a group join, which only Join may pay", 0, asked.groupJoins)
            assertEquals("the question quotes a group join, which only Join may pay", 1, asked.pendingPeers)
        }

    /** The live stores of one phone, wallet A's or wallet B's (the phrase-change fixture of the C2 test). */
    private class TwoWallets(
        a: AutoSpendPrefs,
        b: AutoSpendPrefs,
    ) {
        @Volatile private var current: AutoSpendPrefs = a
        private val reloads = AtomicLong(0)

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
}
