package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerEntry
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.D
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Round 8 MA3, design test 14 (MEMBER_ANNOUNCE_DESIGN.md v2 §6.2, §6.3; review F7, F8, F10, F12) — an on-chain member
 * announce is paid only by the admin's tap, under a quote that counts pairs, and each pair at most once.
 *
 * The creator C has A, B and D, none reachable over the relays: 3 members each to be told about 2 others is 6 pairs.
 * - Opening the group pays nothing; the banner counts the 6 pairs (a member with a relay key that acked nothing is not
 *   in it, but in the "couldn't reach yet" count); with the relays not ready the banner is hidden.
 * - "Introduce them" quotes 6 memos (GROUP_INTRODUCE); declining sends nothing; accepting pays exactly those 6; a second
 *   tap pays nothing.
 * - A pair that may have been sent is never paid again; a pair that provably was not sent is owed again; a refused
 *   ledger write sends nothing; a pair whose ledger entry changed before its submit is not submitted.
 * - Every GP send in GroupViewModel passes mayPayOnChain explicitly (its default is `{ true }`).
 * - Round 8 MA-V3 (VERIFIED_MA_FINDINGS.md #3): a quoted pair whose recipient becomes reachable over the relays inside
 *   the send (after its reachability check and CHAIN_PENDING claim, before the relay try) goes out free over the relays;
 *   its ledger records that relay delivery (RELAY_UNCONFIRMED / RELAY_FINAL), never CHAIN_SENT.
 *
 * At the MA2 tip there is no banner, no introduceMembers and no GROUP_INTRODUCE.
 *
 * Consent GRANTED; proposals are counted on the admin's wallet. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberAnnounceNeverPaysWithoutQuoteTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun admin(): AnnouncerPhone {
        val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, D to MemberStatus.ACTIVE))
        listOf(A, B, D).forEach { MA3.memberPhone(it, admin) }
        return admin
    }

    private suspend fun screen(
        admin: AnnouncerPhone,
        seam: AnnounceSeam,
    ): Pair<GroupViewModel, GroupPaidOnceFixture> {
        val f = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceNeverPaysWithoutQuoteTest.scheduler }
        return MA3.groupVm(admin, f, seam) to f
    }

    private suspend fun tap(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture
    ) = assertTrue("GroupViewModel has no introduceMembers (the banner's tap)", MA3.callGroupVm(vm, f, "introduceMembers"))

    @Test
    fun `opening the group pays nothing and the banner counts every pair with no relay route`() =
        runBlocking {
            val admin = admin()
            net.register()
            val seam = AnnounceSeam()
            val (vm, f) = screen(admin, seam)

            val conversation = MA3.load(vm, f)

            assertEquals("opening the group paid for an announce", 0, seam.proposals.size)
            assertEquals("the banner does not count the 6 pairs", 6, MA3.introduce(conversation, "owedOnChainPairs"))
            assertEquals("the banner is not shown", true, MA3.introduce(conversation, "showBanner"))
        }

    @Test
    fun `a member with a relay key that acked nothing is not in the banner but in the unreachable count`() =
        runBlocking {
            val admin = admin()
            admin.base.nostrKeys[A] = NPUB.getValue(A)
            net.acks = { 0 }
            net.register()
            val seam = AnnounceSeam()
            val (vm, f) = screen(admin, seam)

            val conversation = MA3.load(vm, f)

            assertEquals(0, seam.proposals.size)
            assertEquals("pairs to A (relay key, no ack) are counted as owed on-chain", 4, MA3.introduce(conversation, "owedOnChainPairs"))
            assertEquals("pairs to A are not counted as not reached yet", 2, MA3.introduce(conversation, "unreachablePairs"))
        }

    @Test
    fun `with the relays not ready the banner is hidden`() =
        runBlocking {
            val admin = admin()
            val seam = AnnounceSeam()
            val (vm, f) = screen(admin, seam)

            val conversation = MA3.load(vm, f)

            assertEquals(0, seam.proposals.size)
            assertNotNull("the group screen has no introduce state", MA3.introduce(conversation, "owedOnChainPairs"))
            assertEquals("the banner shows while the relays are not ready", false, MA3.introduce(conversation, "showBanner"))
        }

    @Test
    fun `a tap quotes 6 pairs, decline sends nothing, accept pays exactly 6, and a second tap pays nothing`() =
        runBlocking {
            val admin = admin()
            net.register()
            val seam = AnnounceSeam()
            val (vm, f) = screen(admin, seam)
            MA3.load(vm, f)

            tap(vm, f)
            val quote = vm.paidActionConfirmation.value
            assertNotNull("the tap asked nothing", quote)
            assertEquals("GROUP_INTRODUCE", quote!!.action.name)
            assertEquals("the quote does not count 3 members x 2 others", 6, quote.onChainMessages)
            f.awaitNewJobs(vm) { vm.dismissPaidAction() }
            assertEquals("declining paid something", 0, seam.proposals.size)

            tap(vm, f)
            assertEquals(6, vm.paidActionConfirmation.value?.onChainMessages)
            MA3.confirm(vm, f)
            assertEquals("accepting did not pay exactly the 6 quoted pairs", 6, seam.proposals.size)
            val told = seam.proposals.map { it.recipient to MA3.parseGp(it.memo)?.subject }.toSet()
            assertEquals(
                setOf(A to B, A to D, B to A, B to D, D to A, D to B),
                told,
            )
            assertTrue("a paid memo is not an A announce", seam.proposals.all { MA3.parseGp(it.memo)?.op == AnnounceOp.A })
            assertEquals(6, admin.ledger().count { it.state == AnnounceLedgerState.CHAIN_SENT })

            tap(vm, f)
            assertNull("a second tap asked to pay again", vm.paidActionConfirmation.value)
            assertEquals("a second tap paid again", 6, seam.proposals.size)
        }

    @Test
    fun `a pair that may have been sent is never paid again`() =
        runBlocking {
            val admin = admin()
            net.register()
            val seam = AnnounceSeam(storedThenRejected = 1)
            val (vm, f) = screen(admin, seam)
            MA3.load(vm, f)
            tap(vm, f)
            MA3.confirm(vm, f)
            assertEquals(6, seam.proposals.size)
            assertEquals(
                "the stored-then-rejected pair is not MAYBE_SENT",
                1,
                admin.ledger().count {
                    it.state ==
                        AnnounceLedgerState.MAYBE_SENT
                }
            )

            val again = MA3.load(vm, f)
            assertEquals("a pair that may have been sent is owed again", 0, MA3.introduce(again, "owedOnChainPairs"))
            tap(vm, f)
            assertNull(vm.paidActionConfirmation.value)
            assertEquals("a pair that may have been sent was paid again", 6, seam.proposals.size)
        }

    @Test
    fun `a pair that provably was not sent is owed again`() =
        runBlocking {
            val admin = admin()
            net.register()
            val seam = AnnounceSeam(failProposal = 1 to IllegalStateException("synthetic refusal before anything was stored"))
            val (vm, f) = screen(admin, seam)
            MA3.load(vm, f)
            tap(vm, f)
            MA3.confirm(vm, f)
            assertEquals(5, seam.submitted.size)

            val again = MA3.load(vm, f)
            assertEquals("the unsent pair is not owed again", 1, MA3.introduce(again, "owedOnChainPairs"))
            tap(vm, f)
            assertEquals("the unsent pair is not quoted again", 1, vm.paidActionConfirmation.value?.onChainMessages)
        }

    @Test
    fun `a refused ledger write sends nothing`() =
        runBlocking {
            val admin = admin()
            net.register()
            val seam = AnnounceSeam()
            val (vm, f) = screen(admin, seam)
            MA3.load(vm, f)
            tap(vm, f)
            assertEquals(6, vm.paidActionConfirmation.value?.onChainMessages)
            admin.casRefused = true
            MA3.confirm(vm, f)
            assertEquals("a pair was paid for although its CHAIN_PENDING write was refused", 0, seam.proposals.size)
        }

    @Test
    fun `a pair whose ledger entry changed before its submit is not submitted`() =
        runBlocking {
            val admin = admin()
            net.register()
            val seam = AnnounceSeam()
            // As a relay delivery during the block wait would: at the first proposal, that pair is recorded as
            // delivered over the relays, so the entry is no longer this run's CHAIN_PENDING.
            seam.onProposal = { n, paid ->
                if (n == 1) {
                    val subject = MA3.parseGp(paid.memo)!!.subject
                    admin.ledgers[GID] =
                        admin.ledger().map {
                            if (it.rAddr == paid.recipient && it.sAddr == subject) it.copy(state = AnnounceLedgerState.RELAY_FINAL) else it
                        }
                }
            }
            val (vm, f) = screen(admin, seam)
            MA3.load(vm, f)
            tap(vm, f)
            MA3.confirm(vm, f)
            assertEquals(6, seam.proposals.size)
            assertEquals("the pair delivered meanwhile was still submitted", 5, seam.submitted.size)
            assertTrue(seam.proposals.first() !in seam.submitted)
        }

    @Test
    fun `a quoted pair whose recipient becomes relay-reachable at send time is recorded as a relay delivery, never CHAIN_SENT`() =
        runBlocking {
            val admin = admin()
            net.register()
            val seam = AnnounceSeam()
            val (vm, f) = screen(admin, seam)
            // A's relay key is stored inside the send window of the first pair to A: after payAnnouncePair's
            // reachability check and its CHAIN_PENDING claim, before sendGroupMemoWithRetry's relay try.
            var raced: AnnounceLedgerEntry? = null
            val store = admin.proxy
            val racing =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { _, method, args ->
                    val result =
                        try {
                            method.invoke(store, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    if (method.name == "casGroupAnnounce" && result == true && raced == null) {
                        val next = args!![3] as AnnounceLedgerEntry?
                        if (next?.state == AnnounceLedgerState.CHAIN_PENDING && next.rAddr == A) {
                            raced = next
                            admin.base.nostrKeys[A] = NPUB.getValue(A)
                        }
                    }
                    result
                } as ZchatPreferences
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(vm, racing)
            MA3.load(vm, f)
            tap(vm, f)
            assertEquals("the quote does not count the 6 pairs", 6, vm.paidActionConfirmation.value?.onChainMessages)
            MA3.confirm(vm, f)

            assertNotNull("no pair to A was claimed for payment", raced)
            val subject = raced!!.sAddr
            val entry = admin.ledger().single { it.rAddr == A && it.sAddr == subject }
            assertEquals(
                "a relay delivery of a quoted pair was recorded as an on-chain send",
                AnnounceLedgerState.RELAY_UNCONFIRMED,
                entry.state,
            )
            assertTrue(
                "the pair that went out over the relays was also paid on-chain",
                seam.proposals.none { it.recipient == A && MA3.parseGp(it.memo)?.subject == subject },
            )
            assertEquals(
                "the pair did not go out over the relays exactly once",
                1,
                net.gps(NPUB.getValue(A)).count { it.op == AnnounceOp.A && it.subject == subject },
            )
        }

    @Test
    fun `every GP send in GroupViewModel passes mayPayOnChain explicitly`() {
        val source = groupViewModelSource()
        val sites = gpSendSites(source)
        assertTrue("GroupViewModel has no GP send (an announce or introduce function calling sendGroupMemoWithRetry)", sites.isNotEmpty())
        val implicit = sites.filterNot { it.contains("mayPayOnChain =") }
        assertEquals("GP sends relying on the { true } default:\n${implicit.joinToString("\n---\n")}", 0, implicit.size)
    }

    /** Every `sendGroupMemoWithRetry(...)` call inside a member function whose name mentions announce / introduce. */
    private fun gpSendSites(source: String): List<String> {
        val sites = mutableListOf<String>()
        val member = Regex("^ {4}(?:(?:private|internal|public|suspend|inline)\\s+)*fun\\s+(\\w+)")
        var current: String? = null
        val lines = source.lines()
        for ((i, line) in lines.withIndex()) {
            member.find(line)?.let { current = it.groupValues[1] }
            val code = line.trim()
            if (code.startsWith("*") ||
                code.startsWith("//") ||
                Regex("\\bfun\\s+sendGroupMemoWithRetry\\(").containsMatchIn(line)
            ) {
                continue
            }
            if (!line.contains("sendGroupMemoWithRetry(")) continue
            if (current?.contains(Regex("(?i)announce|introduce")) != true) continue
            // The call's text: from the call to its balanced closing parenthesis, plus a trailing lambda if any.
            val rest = lines.drop(i).joinToString("\n").substringAfter("sendGroupMemoWithRetry(")
            var depth = 1
            var end = 0
            while (end < rest.length && depth > 0) {
                if (rest[end] == '(') depth++
                if (rest[end] == ')') depth--
                end++
            }
            sites += rest.take(end)
        }
        return sites
    }

    private fun groupViewModelSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        val root = dir ?: throw AssertionError("repo root not found")
        return File(root, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/GroupViewModel.kt").readText()
    }
}
