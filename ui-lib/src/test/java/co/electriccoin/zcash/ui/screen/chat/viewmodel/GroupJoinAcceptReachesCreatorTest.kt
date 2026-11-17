package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * R6-4 (round 6 report, new problem 1; round 5 phone report §4b) — a member who joined a paid group never
 * sends its "I joined" reply (GROUP_ACCEPT), so the creator, who paid for the invite, keeps the member on
 * "Invited" and can never message them.
 *
 * On the phone (round 5): the invited app lists the group, "2 members", composer open, no question on
 * screen; its wallet never moved; the creator stayed on "Invited" 20 minutes later. In the tree:
 *
 * 1. **The question is asked where nobody can see it.** Each screen gets its own ChatViewModel. The chat
 *    list's instance reads the history, joins the group (processGroupInvite) and, with no consent to pay
 *    for automatic transactions yet, holds the paid accept and raises NeedsHandshakeSpendConsent on ITS
 *    OWN send state. Only the 1:1 chat screen renders that dialog; the chat list renders the paid-action
 *    dialog from the same state but not this one. So the accept waits for an answer to a question that is
 *    never shown, and nothing else sends it (only a grant or the first block after a start re-drives it).
 * 2. **The question does not say it is about the group.** It talks about secure (Tunnel) chats; a user
 *    who just got a group invite has no reason to think "Not now" leaves them outside that group.
 * 3. **With consent, one failed attempt drops the join for good.** An accept whose send provably paid
 *    nothing (the wallet could not cover it yet) is put back only when consent was withdrawn; with consent
 *    given it is dropped, and no block, start or grant sends it again.
 *
 * Wanted: the question is shown on the chat list too and says a group join is waiting; with consent, an
 * accept that provably sent nothing is kept and sent once on a later block; and — guards that hold before
 * and after — nothing is paid without consent, nothing is paid twice (two screens see the same blocks),
 * and an accept for a group the user has left is never paid.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): a group join is no longer paid under the standing consent at all. A reply that cannot
 * go over the relays waits for Join (the group screen asks with the price); Join pays it once; a Join whose send
 * provably paid nothing waits for Join again. The tests below keep their guards (nothing paid without the user's
 * say, nothing paid twice across two screens, nothing paid for a group left) on the Join path.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupJoinAcceptReachesCreatorTest {
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

    // ---- the invite -> accept path ----------------------------------------------------------------

    /** A compact invite as the creator sends it to a member it has no key exchange with (no roster). */
    private fun invitePayload(inviter: String): String =
        JSONObject()
            .put("name", "Synthetic paid group")
            .put("inviter", inviter)
            .put("key_epoch", 0)
            .put("group_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
            .toString()

    /** The real invite handler, as the history scan calls it for an on-chain GROUP_INVITE. */
    private fun processInvite(
        vm: ChatViewModel,
        groupId: String,
        inviter: String,
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

    /** A new chain tip reaching [vm], as observeBlockHeight delivers it (about once per block). */
    private fun chainTip(
        vm: ChatViewModel,
        height: Long,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, height)
    }

    private fun genuineShortfall() = InsufficientFundsException("synthetic genuine shortfall: add ZEC")

    /** Round 8 P1: the group screen's Join. */
    private suspend fun join(
        vm: ChatViewModel,
        groupId: String,
    ): Boolean = GroupInviteDriver.join(vm, groupId)

    /** An automatic KEX towards [peer] (the bootstrap trigger), which asks the consent question when none is given. */
    private suspend fun automaticKex(
        vm: ChatViewModel,
        prefs: AutoSpendPrefs,
        peer: String,
    ) {
        prefs.session(peer, complete = false)
        prefs.ownBootSent[peer] = true
        vm.sendKEXMessage(peer, HandshakeHarness.SELF)
        awaitIdle(vm)
    }

    // ---- 1. the question must be on the screen that raises it -------------------------------------

    @Test
    fun `the chat list shows the consent question its own ChatViewModel raises when it joins a group`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach01"
            val prefs = AutoSpendPrefs(consent = false)
            val seam = HandshakeSeam(inviter)
            val listVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(listVm, "GSYNTH61", inviter)
            awaitIdle(listVm)
            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTH61"))
            assertEquals("no GROUP_ACCEPT may be paid without consent", 0, seam.proposalRequests.get())
            // Round 8 P1: the paid reply waits for Join (the group screen asks); the invite raises no consent question.
            assertTrue("the paid reply must wait for Join", prefs.needsJoin.containsKey("GSYNTH61"))
            assertTrue(
                "a group invite raised the standing consent question",
                HandshakeHarness.sendMessageState(listVm) !is SendMessageState.NeedsHandshakeSpendConsent,
            )
            // The chat list's own ChatViewModel still raises the question for a KEX; the list must show it.
            automaticKex(listVm, prefs, "usyntheticgroupjoinreachk1")
            assertTrue(
                "fixture sanity: the chat list's ChatViewModel asks on its own send state",
                HandshakeHarness.sendMessageState(listVm) is SendMessageState.NeedsHandshakeSpendConsent,
            )

            val list = composableBody(source(ANDROID_CHAT), "AndroidChatList")
            assertTrue(
                "the chat list never renders the consent question its own ChatViewModel raised when it joined " +
                    "the group, so the paid \"I joined\" reply waits for an answer nobody is shown and the " +
                    "creator keeps the member on Invited (round 5 phone, §4b)",
                list.contains("NeedsHandshakeSpendConsent") && list.contains("grantHandshakeSpendConsent()"),
            )
            assertTrue(
                "the chat list's consent question has no \"Not now\" that records the answer",
                list.contains("dismissOrchardShieldingWarning()"),
            )
        }

    // ---- 2. the question must say a group join is waiting ------------------------------------------

    /**
     * Round 8 P1: no group join waits on the consent question any more (it waits for Join), so a question raised while
     * a group reply waits for Join counts no group join. The dialog keeps its group-join line and strings (EN, ES).
     */
    @Test
    fun `the consent question counts no group join, which waits for Join, and its group-join line stays in English and Spanish`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach02"
            val prefs = AutoSpendPrefs(consent = false)
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH62", inviter)
            awaitIdle(vm)
            assertTrue("the paid reply must wait for Join", prefs.needsJoin.containsKey("GSYNTH62"))
            automaticKex(vm, prefs, "usyntheticgroupjoinreachk2")
            val ask = HandshakeHarness.sendMessageState(vm)
            assertTrue("fixture sanity: the user is asked", ask is SendMessageState.NeedsHandshakeSpendConsent)
            val joins =
                runCatching { ask.javaClass.getMethod("getGroupJoins").invoke(ask) as Int }.getOrElse {
                    fail(
                        "the consent question cannot say that a group join is waiting on it: " +
                            "NeedsHandshakeSpendConsent carries no group-join count",
                    )
                    error("unreachable")
                }
            assertEquals("the question counts a group join, which only Join may pay", 0, joins)

            assertTrue(
                "the consent dialog never tells the user a group join is waiting on the answer",
                source(ANDROID_CHAT).contains("R.plurals.$GROUP_JOIN_KEY"),
            )
            listOf("values", "values-es").forEach { qualifier ->
                val items =
                    plurals(qualifier)[GROUP_JOIN_KEY] ?: run {
                        fail("$qualifier has no <plurals name=\"$GROUP_JOIN_KEY\">")
                        error("unreachable")
                    }
                items.forEach { (quantity, text) ->
                    assertTrue("$qualifier/$GROUP_JOIN_KEY[$quantity] must give the count: $text", text.contains("%1\$d"))
                }
            }
        }

    // ---- 3. with consent, a failed attempt must not drop the join ----------------------------------

    /**
     * Round 8 P1: a Join whose send provably paid nothing (the wallet could not cover it yet) must not drop the join:
     * the invite waits for Join again (it was kept for the next block under the standing consent). No block pays it;
     * the next Join pays it once.
     */
    @Test
    fun `a Join whose send provably sent nothing waits for Join again, and the next Join sends it once, never twice`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach03"
            val prefs = AutoSpendPrefs(consent = true)
            // The wallet cannot cover the accept yet: no proposal is built, so nothing was sent.
            val seam = HandshakeSeam(inviter, failFirstProposalWith = genuineShortfall())
            val listVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            val detailVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(listVm, "GSYNTH63", inviter)
            awaitIdle(listVm)
            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTH63"))
            assertEquals("the paid reply was attempted under the standing consent", 0, seam.proposalRequests.get())
            assertTrue(join(listVm, "GSYNTH63"))
            assertEquals("fixture sanity: the accept was attempted once", 1, seam.proposalRequests.get())
            assertEquals("fixture sanity: nothing was broadcast", 0, seam.broadcasts.size)
            assertTrue(
                "an accept whose send provably paid nothing was dropped: nothing sends it again, and the creator keeps " +
                    "the member on Invited",
                prefs.needsJoin.containsKey("GSYNTH63"),
            )
            assertTrue("its held copy must not stay behind", prefs.heldGroupAccepts.isEmpty())

            // Funds arrive; both screens' ViewModels see the next blocks: none of them pays it.
            var height = HandshakeHarness.START_HEIGHT
            repeat(3) {
                height++
                chainTip(listVm, height)
                chainTip(detailVm, height)
                awaitIdle(listVm, detailVm)
            }
            assertEquals("a block paid a reply that waits for Join", 1, seam.proposalRequests.get())

            assertTrue(join(detailVm, "GSYNTH63"))
            assertFalse("a second Join on the other screen must send nothing", join(listVm, "GSYNTH63"))
            repeat(2) {
                height++
                chainTip(listVm, height)
                chainTip(detailVm, height)
                awaitIdle(listVm, detailVm)
            }
            assertEquals("the reply was not sent by the next Join", 1, seam.broadcasts.size)
            assertEquals("the accept was paid more than once", 2, seam.proposalRequests.get())
            assertTrue("a sent accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
            assertTrue(prefs.needsJoin.isEmpty())
        }

    // ---- guards (hold before and after) -------------------------------------------------------------

    /**
     * Guard: blocks never pay a waiting reply; Round 8 P1: neither does a later consent (it paid the held reply once);
     * Join pays it exactly once.
     */
    @Test
    fun `guard - no block and no consent pays the waiting reply, and Join pays it once`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach04"
            val prefs = AutoSpendPrefs(consent = false)
            val seam = HandshakeSeam(inviter)
            val listVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            val detailVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(listVm, "GSYNTH64", inviter)
            awaitIdle(listVm)
            var height = HandshakeHarness.START_HEIGHT
            repeat(3) {
                height++
                chainTip(listVm, height)
                chainTip(detailVm, height)
                awaitIdle(listVm, detailVm)
            }
            assertEquals("a block paid the accept without consent", 0, seam.proposalRequests.get())
            assertTrue("the reply must wait for Join", prefs.needsJoin.containsKey("GSYNTH64"))

            listVm.grantHandshakeSpendConsent()
            detailVm.grantHandshakeSpendConsent()
            awaitIdle(listVm, detailVm)
            repeat(2) {
                height++
                chainTip(listVm, height)
                chainTip(detailVm, height)
                awaitIdle(listVm, detailVm)
            }
            assertEquals("the standing consent paid a group join", 0, seam.proposalRequests.get())
            assertTrue("the reply must still wait for Join", prefs.needsJoin.containsKey("GSYNTH64"))

            assertTrue(join(listVm, "GSYNTH64"))
            assertFalse(join(detailVm, "GSYNTH64"))
            repeat(2) {
                height++
                chainTip(listVm, height)
                chainTip(detailVm, height)
                awaitIdle(listVm, detailVm)
            }
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertTrue(prefs.heldGroupAccepts.isEmpty())
        }

    /** Guard: an accept kept after a failed attempt is never paid once the user has left the group. */
    @Test
    fun `guard - a kept accept for a group the user has left is dropped and never paid`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach05"
            val prefs = AutoSpendPrefs(consent = true)
            val seam = HandshakeSeam(inviter, failFirstProposalWith = genuineShortfall())
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH65", inviter)
            awaitIdle(vm)
            // Round 8 P1: the reply is attempted by Join (the shortfall puts it back to waiting for Join).
            assertTrue(join(vm, "GSYNTH65"))
            prefs.groupInfo.remove("GSYNTH65") // the user left the group

            var height = HandshakeHarness.START_HEIGHT
            repeat(3) {
                height++
                chainTip(vm, height)
                awaitIdle(vm)
            }
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertFalse("Join answered a group the user left", join(vm, "GSYNTH65"))
            assertEquals("an accept for a group the user left was paid", 1, seam.proposalRequests.get())
            assertEquals(0, seam.broadcasts.size)
            assertTrue("a left group's accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
        }

    /**
     * Guard added after the fix: "Leave group" does not delete the group, it keeps its info marked inactive
     * (GroupViewModel.leaveGroup). A kept accept for a group the user has left that way must never be paid,
     * by a later block or by a grant.
     */
    @Test
    fun `guard - a kept accept for a group the user left with Leave is never paid`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach08"
            val prefs = AutoSpendPrefs(consent = true)
            val seam = HandshakeSeam(inviter, failFirstProposalWith = genuineShortfall())
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH68", inviter)
            awaitIdle(vm)
            // Round 8 P1: the reply is attempted by Join (the shortfall puts it back to waiting for Join).
            assertTrue(join(vm, "GSYNTH68"))
            // "Leave group": the group info stays, marked inactive.
            val info = ZMSGGroupProtocol.deserializeGroupInfo(prefs.groupInfo.getValue("GSYNTH68"))
            assertTrue("fixture sanity: the joined group reads back", info != null)
            prefs.groupInfo["GSYNTH68"] = ZMSGGroupProtocol.serializeGroupInfo(info!!.copy(isActive = false))

            var height = HandshakeHarness.START_HEIGHT
            repeat(3) {
                height++
                chainTip(vm, height)
                awaitIdle(vm)
            }
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertFalse("Join answered a group the user left", join(vm, "GSYNTH68"))
            assertEquals("an accept for a group the user left was paid", 1, seam.proposalRequests.get())
            assertEquals(0, seam.broadcasts.size)
            assertTrue("a left group's accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
        }

    /**
     * Control: with consent and funds, one accept is paid and later blocks pay none. Round 8 P1: it is paid by Join
     * (it was paid by the invite under the standing consent).
     */
    @Test
    fun `control - with consent and funds Join pays one accept and later blocks pay nothing more`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach06"
            val prefs = AutoSpendPrefs(consent = true)
            val seam = HandshakeSeam(inviter)
            val listVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            val detailVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(listVm, "GSYNTH66", inviter)
            awaitIdle(listVm)
            assertEquals("the invite paid its reply under the standing consent", 0, seam.proposalRequests.get())
            assertTrue(join(listVm, "GSYNTH66"))
            var height = HandshakeHarness.START_HEIGHT
            repeat(3) {
                height++
                chainTip(listVm, height)
                chainTip(detailVm, height)
                awaitIdle(listVm, detailVm)
            }
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertTrue(prefs.heldGroupAccepts.isEmpty())
        }

    /**
     * Added with the fix: each screen has its own ChatViewModel, so once the question is answered on one
     * screen, another screen whose send state still holds it must not ask it again.
     */
    @Test
    fun `a consent question answered on another screen is not asked again`() =
        runBlocking {
            val inviter = "usyntheticgroupjoinreach07"
            val prefs = AutoSpendPrefs(consent = false)
            val seam = HandshakeSeam(inviter)
            val listVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            val detailVm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(listVm, "GSYNTH67", inviter)
            awaitIdle(listVm)
            // Round 8 P1: the group invite raises no question (its reply waits for Join); a KEX on the list raises it.
            automaticKex(listVm, prefs, "usyntheticgroupjoinreachk7")
            assertTrue(
                "the chat list's question must be open until it is answered",
                HandshakeHarness.sendMessageState(listVm) is SendMessageState.NeedsHandshakeSpendConsent &&
                    listVm.isHandshakeSpendQuestionOpen(),
            )

            detailVm.grantHandshakeSpendConsent() // "Allow" tapped on a 1:1 chat
            awaitIdle(listVm, detailVm)
            assertTrue(
                "the chat list asks again a question already answered on another screen",
                !listVm.isHandshakeSpendQuestionOpen(),
            )
            assertTrue("the answer released a group join", prefs.needsJoin.containsKey("GSYNTH67"))
            assertTrue(join(listVm, "GSYNTH67"))
            assertEquals(1, seam.broadcasts.size)
        }

    // ---- source helpers -----------------------------------------------------------------------------

    private fun source(path: String): String {
        val file = uiLibDir.resolve(path)
        assertTrue("missing ${file.path}", file.isFile)
        return file.readText()
    }

    /** The text of the top-level composable [name] in [file], up to the next top-level declaration. */
    private fun composableBody(
        file: String,
        name: String,
    ): String {
        val start = file.indexOf("\nfun $name(")
        assertTrue("no top-level fun $name in AndroidChat.kt", start >= 0)
        val next =
            Regex("""\n(@Composable|fun |private fun |internal fun )""")
                .find(file, file.indexOf('\n', start + 1))
                ?.range
                ?.first ?: file.length
        return file.substring(start, next)
    }

    /** Every `<plurals>` in the chat money strings of [qualifier]: name to (quantity to text). */
    private fun plurals(qualifier: String): Map<String, Map<String, String>> {
        val file = uiLibDir.resolve("src/main/res/ui/common/$qualifier/chat_money_strings.xml")
        assertTrue("missing ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            val items = el.getElementsByTagName("item")
            el.getAttribute("name") to
                (0 until items.length).associate { j ->
                    val item = items.item(j) as Element
                    item.getAttribute("quantity") to item.textContent
                }
        }
    }

    private companion object {
        const val ANDROID_CHAT = "src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
        const val GROUP_JOIN_KEY = "handshake_consent_group_join"

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/common").isDirectory }
                ?: error("could not locate the ui-lib module from ${start.path}")
        }
    }
}
