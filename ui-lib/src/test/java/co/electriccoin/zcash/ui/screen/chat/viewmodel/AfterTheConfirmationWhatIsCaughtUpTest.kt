package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.NOTICE_CATCHUP_KEY
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.NOTICE_CATCHUP_LIST
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.STRANGER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R9 and R6; section 4.1 class 8) — what a phone catches up on after its user
 * confirmed the group's admin, what it does not, and how.
 *
 * - (a) the admin's member announces are accepted from then on;
 * - (b), (c) a removal that reached this phone over the relays on an earlier build is not brought back by the
 *   confirmation; the group says that its member list may be out of date until the admin's list arrives;
 * - (d) the group says what to ask the admin when the newest message cannot be read and the admin's ZChat is known
 *   to be older than 2.13;
 * - (e), (h) the confirmation raises the pass signal and sends one free roster query, started where leaving the
 *   screen at once does not reach, and nothing else;
 * - (f) a history pass that was already running at the tap applies none of the group's on-chain changes afterwards
 *   (it has already refused the earlier ones); a pass that began after the confirmation applies them in order;
 * - (g) the pass signal makes every live chat view model begin such a pass, also when the screen that confirmed
 *   is gone.
 *
 * At `00f1a349b` there is no confirmation, no pass counter and no pass signal.
 *
 * Nothing is paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AfterTheConfirmationWhatIsCaughtUpTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private var ownSendScope: CoroutineScope? = null

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        ownSendScope?.cancel()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val created: Instant = Instant.ofEpochMilli(T)
    private val kickTime: Instant = Instant.ofEpochMilli(T + 60_000)
    private val keyTime: Instant = Instant.ofEpochMilli(T + 120_000)
    private val rosterQuery = ZMSGGroupProtocol.createGroupRosterQueryMessage(GID)

    /** A drifted phone ready for the confirmation: the creating invite from Y is in the history and Y is verified. */
    private fun readyPhone(): AdminCheckPhone =
        AdminCheck.driftedPhone().also { p ->
            p.creatingInvite(p.base.sessionWith(Y), created)
            p.verified += Y
        }

    private suspend fun confirm(
        vm: ChatViewModel,
        p: AdminCheckPhone,
    ) {
        assertEquals("fixture: the admin's chat is confirmed", CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
        HandshakeHarness.awaitIdle(vm)
    }

    private suspend fun groupScreen(p: AdminCheckPhone): Pair<GroupViewModel, GroupPaidOnceFixture> {
        val f = GroupPaidOnceFixture()
        f.scheduler = scheduler
        return AdminCheck.groupVm(p, f) to f
    }

    private fun assertKickApplied(
        p: AdminCheckPhone,
        newKey: String,
    ) {
        assertEquals("the confirmed admin's kick did not remove the member", MemberStatus.LEFT, p.base.status(GID, M))
        assertEquals("the confirmed admin's kick did not move the key epoch", 2, p.base.epochs[GID])
        assertEquals("the kick's group key was not adopted", newKey, p.groupKey(2))
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - an announce the admin signed and sealed is dropped before the confirmation and adds the member after it`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val announce = AnnounceDriver.gp(AnnounceOp.A, STRANGER, T + 5_000, 1, p.admin, GID)

            AnnounceDriver.receive(vm, announce, kickTime, sender = Y, eventId = "synthetic-event-ki25-announce-1")
            assertNull("fixture: before the confirmation the announce is dropped", p.base.status(GID, STRANGER))

            confirm(vm, p)
            AnnounceDriver.receive(vm, announce, kickTime, sender = Y, eventId = "synthetic-event-ki25-announce-2")

            assertEquals("the confirmed admin's announce did not add the member", MemberStatus.ACTIVE, p.base.status(GID, STRANGER))
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------- (b), (c)

    /**
     * The phone as an earlier build left it after the admin's relay kick of M: the event is in the seen list (the
     * relays do not deliver it again) and nothing of it was kept. Returns the phone and its chat view model, confirmed.
     */
    private suspend fun confirmedAfterAnUnkeptRelayKick(): Pair<AdminCheckPhone, ChatViewModel> {
        val p = readyPhone()
        p.prefs.markNostrEventSeen("synthetic-event-ki25-relay-kick-on-an-earlier-build")
        assertNull("fixture: the group's stored state holds no kept change", p.base.states[GID])
        val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
        confirm(vm, p)
        return p to vm
    }

    @Test
    fun `b - a relay kick received before anything could be kept is not applied and the group shows the member-list line`() =
        runBlocking {
            val (p, _) = confirmedAfterAnUnkeptRelayKick()
            val (groupVm, _) = groupScreen(p)

            assertEquals("the confirmation removed a member by itself", MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals("the confirmation moved the key epoch by itself", 1, p.base.epochs[GID])
            assertFalse("the confirmation took an event out of the seen list", "unmarkNostrEventSeen" in p.calls)
            assertEquals(
                "a confirmed group whose member list the admin has not sent yet does not say so",
                NOTICE_CATCHUP_LIST,
                AdminCheck.notice(groupVm),
            )
        }

    @Test
    fun `c - then the admin's removal announce removes the member and the member-list line is gone`() =
        runBlocking {
            val (p, vm) = confirmedAfterAnUnkeptRelayKick()
            val (groupVm, _) = groupScreen(p)
            assertEquals(NOTICE_CATCHUP_LIST, AdminCheck.notice(groupVm))

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gp(AnnounceOp.R, M, T + 6_000, 1, p.admin, GID),
                kickTime,
                sender = Y,
                eventId = "synthetic-event-ki25-removal-announce",
            )

            assertEquals("the confirmed admin's removal announce did not remove the member", MemberStatus.LEFT, p.base.status(GID, M))
            assertNull("the member-list line is still shown after the admin's list arrived", AdminCheck.notice(groupVm))
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - an unreadable newest message and an admin known to be older than 2_13 show the key line, else no line`() =
        runBlocking {
            val p = readyPhone()
            // The admin's member list has reached this phone, so the member-list line is not due.
            p.base.states[GID] = p.base.state(GID).copy(rosterAnnounced = true)
            // The newest received message is a stored row from M under key epoch 2 with no readable text (the row the
            // group screen draws as "Can't decrypt"). At `00f1a349b` neither writer of the group-message store keeps a
            // message it cannot decrypt, so the row is put there in the form the store's reader takes.
            p.base.messages[GID] =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "synthetic-ki25-unreadable-row")
                            .put("groupId", GID)
                            .put("seq", 1L)
                            .put("epoch", 2)
                            .put("sender", M)
                            .put("encrypted", "c3ludGhldGljLWNpcGhlcnRleHQ=")
                            .put("nonce", "c3ludGhldGljLW5vbmNl")
                            .put("timestamp", keyTime.toEpochMilli()),
                    ).toString()
            val (groupVm, f) = groupScreen(p)
            val shown = AnnounceDriver.loadedConversation(groupVm, f, GID)
            assertTrue(
                "fixture: the newest received message cannot be read",
                shown?.messages?.lastOrNull()?.let { it.isUndecryptable && it.senderAddress == M } == true,
            )

            confirm(AdminCheck.chatVm(p, HandshakeSeam(Y)), p)
            AnnounceDriver.loadedConversation(groupVm, f, GID)

            assertNull("a line is shown although nothing is known about the admin's ZChat", AdminCheck.notice(groupVm))

            p.ironwoodCaps[Y] = false
            assertEquals(
                "the group does not say what to ask an admin whose ZChat cannot answer a key request",
                NOTICE_CATCHUP_KEY,
                AdminCheck.notice(groupVm),
            )

            p.ironwoodCaps[Y] = true
            assertNull("the key line is shown for an admin on 2.13", AdminCheck.notice(groupVm))

            // With the member-list line due as well, the key line comes first (R6: at most one, in this order).
            p.ironwoodCaps[Y] = false
            p.base.states[GID] = p.base.state(GID).copy(rosterAnnounced = false)
            assertEquals(NOTICE_CATCHUP_KEY, AdminCheck.notice(groupVm))
            assertEquals(0, f.seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (e)

    /** `confirmGroupAdmin`'s code, and in it: the group-lock block, where the number is recorded, where the signal is raised. */
    private class ConfirmSource {
        val body = AdminCheckSource.structure(AdminCheckSource.chatMember("confirmGroupAdmin"))
        val groupLock: IntRange =
            Regex("""withGroupLock\s*\(""").find(body)?.let { AdminCheckSource.lambdaOf(body, it.range.first) }
                ?: throw AssertionError("confirmGroupAdmin has no withGroupLock( block")
        private val record = AdminCheckSource.NUMBER_RECORDED.find(body)
        val recorded: Int =
            record?.range?.first
                ?: throw AssertionError("confirmGroupAdmin does not record the group's pass number (adminConfirmedAtPass[…] = …)")
        val raised: List<Int> =
            AdminCheckSource.SIGNAL_RAISED
                .findAll(body)
                .map { it.range.first }
                .toList()
        val queries: List<Int> =
            Regex("""\bsendRosterQuery\s*\(""")
                .findAll(body)
                .map { it.range.first }
                .toList()
    }

    @Test
    fun `e - source - the confirmation raises the pass signal once, in the group lock, and sends only the roster query`() {
        val source = ConfirmSource()
        val body = source.body

        assertEquals("confirmGroupAdmin does not raise the pass signal at exactly one place", 1, source.raised.size)
        assertEquals("confirmGroupAdmin does not call sendRosterQuery( exactly once", 1, source.queries.size)
        assertFalse(
            "confirmGroupAdmin still calls loadConversations(): that begins a pass on the confirming view model alone",
            Regex("""\bloadConversations\s*\(""").containsMatchIn(body),
        )
        val otherSends =
            Regex("""\b((?:send|resend|trySend|publish|broadcast|submit)\w*)\s*\(""")
                .findAll(body)
                .map { it.groupValues[1] }
                .filter { it != "sendRosterQuery" }
                .toList() +
                listOf("createChunkedMessageProposal", "NostrChatBridge", "groupAnnouncer").filter { it in body }
        assertEquals("confirmGroupAdmin sends something besides the one roster query", emptyList<String>(), otherSends)

        val places =
            AdminCheckSource
                .mainSources()
                .mapValues { (_, code) -> AdminCheckSource.SIGNAL_RAISED.findAll(code).count() }
                .filterValues { it > 0 }
        assertEquals("the pass signal is raised somewhere else than in the confirmation", mapOf("ChatViewModel.kt" to 1), places)

        assertTrue("the group's pass number is not recorded inside the withGroupLock( block", source.recorded in source.groupLock)
        assertTrue("the pass signal is not raised inside the withGroupLock( block", source.raised.single() in source.groupLock)
        assertTrue("the pass signal is raised before the group's pass number is recorded", source.recorded < source.raised.single())
    }

    // ------------------------------------------------------------------ (f)

    @Test
    fun `f - a pass that was already running does not apply the kick and a pass begun after the confirmation does`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val newKey = AnnounceDriver.newGroupKey()
            val kick = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = newKey)

            val countBefore = AdminCheck.passCounter()
            val running = AdminCheck.beginPass(vm)
            assertEquals("a pass does not take the counter's next number", countBefore + 1, AdminCheck.passNumber(running))
            assertEquals("beginning a pass did not count it", countBefore + 1, AdminCheck.passCounter())

            confirm(vm, p)
            assertEquals(
                "the confirmation did not record the number the pass counter showed",
                AdminCheck.passCounter(),
                AdminCheck.confirmedAtPass(),
            )
            val confirmed = p.snapshot()

            AdminCheck.receiveInPass(vm, running, kick, AnnounceDriver.txId(6), kickTime, HEIGHT + 6)
            assertEquals("a pass that began before the confirmation applied an on-chain kick after it", confirmed, p.snapshot())

            val fresh = AdminCheck.beginPass(vm)
            val recorded = AdminCheck.confirmedAtPass()!!
            assertTrue("a pass begun after the confirmation has no higher number", AdminCheck.passNumber(fresh) > recorded)
            AdminCheck.receiveInPass(vm, fresh, kick, AnnounceDriver.txId(6), kickTime, HEIGHT + 6)

            assertKickApplied(p, newKey)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `f - the old pass that refused the kick does not apply the later key change and the new pass applies both in order`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val keyAfterKick = AnnounceDriver.newGroupKey()
            val keyAfterChange = AnnounceDriver.newGroupKey()
            val kick = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = keyAfterKick)
            val change = AdminCheck.keyNaming(p, naming = Y, newEpoch = 3, newKey = keyAfterChange)

            // The pass under way at the tap reaches the kick first: the admin is not confirmed, so it refuses it.
            val running = AdminCheck.beginPass(vm)
            val before = p.snapshot()
            AdminCheck.receiveInPass(vm, running, kick, AnnounceDriver.txId(6), kickTime, HEIGHT + 6)
            assertEquals("fixture: before the confirmation the kick is refused", before, p.snapshot())

            confirm(vm, p)
            val confirmed = p.snapshot()

            // Then it reaches the key change. Applied now, the kick would be "not newer" for every later pass.
            AdminCheck.receiveInPass(vm, running, change, AnnounceDriver.txId(7), keyTime, HEIGHT + 7)
            assertEquals("the pass that refused the kick applied the later key change after the confirmation", confirmed, p.snapshot())

            val fresh = AdminCheck.beginPass(vm)
            AdminCheck.receiveInPass(vm, fresh, kick, AnnounceDriver.txId(6), kickTime, HEIGHT + 6)
            assertKickApplied(p, keyAfterKick)
            AdminCheck.receiveInPass(vm, fresh, change, AnnounceDriver.txId(7), keyTime, HEIGHT + 7)

            assertEquals("the key change after the kick was not applied", 3, p.base.epochs[GID])
            assertEquals("the key change's group key was not adopted", keyAfterChange, p.groupKey(3))
            assertEquals("the kick's key was lost", keyAfterKick, p.groupKey(2))
            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `f - a pass whose wallet generation has changed since it began hands the kick over and nothing changes`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val newKey = AnnounceDriver.newGroupKey()
            val kick = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = newKey)
            confirm(vm, p)
            val confirmed = p.snapshot()

            // This pass began after the confirmation, under wallet generation 0: its transactions are that wallet's.
            val earlierWallet = AdminCheck.beginPass(vm)
            p.walletGeneration = 1L
            AdminCheck.receiveInPass(vm, earlierWallet, kick, AnnounceDriver.txId(6), kickTime, HEIGHT + 6)
            assertEquals("a pass of an earlier wallet generation applied its kick to this wallet's group", confirmed, p.snapshot())

            // A pass that reads the generation as it is now applies the same memo.
            val current = AdminCheck.beginPass(vm)
            AdminCheck.receiveInPass(vm, current, kick, AnnounceDriver.txId(6), kickTime, HEIGHT + 6)
            assertKickApplied(p, newKey)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `f - source - in the dispatcher's two admin branches the guard's test stands inside the group lock`() {
        val kickBranch = Regex("""GroupMessageType\s*\.\s*GROUP_KICK\s*->""")
        val keyBranch = Regex("""GroupMessageType\s*\.\s*GROUP_KEY\s*->""")
        val anyBranch = Regex("""GroupMessageType\s*\.\s*\w+\s*->""")
        val dispatcher = AdminCheckSource.structure(AdminCheckSource.chatMemberContaining(kickBranch))

        fun assertGuarded(
            what: String,
            branch: Regex,
            handler: String,
        ) {
            val start = branch.find(dispatcher)?.range ?: throw AssertionError("the dispatcher has no $what branch")
            val end = anyBranch.find(dispatcher, start.last + 1)?.range?.first ?: dispatcher.length
            val lockAt = dispatcher.indexOf("withGroupLock(", start.last).takeIf { it in start.last until end }
            val lock =
                lockAt?.let { AdminCheckSource.lambdaOf(dispatcher, it) }
                    ?: throw AssertionError("the dispatcher's $what branch has no withGroupLock( block")
            val guard = dispatcher.indexOf(AdminCheckSource.GUARD, start.last).takeIf { it in start.last until end }
            assertNotNull(
                "no such operation: the dispatcher's $what branch does not test the guard (${AdminCheckSource.GUARD}groupId, pass))",
                guard,
            )
            assertTrue("in the $what branch the guard's test stands outside the withGroupLock( block", guard!! in lock)
            val handled = dispatcher.indexOf(handler, lock.first).takeIf { it in lock }
            assertNotNull("the $what branch does not hand over to $handler inside its withGroupLock( block", handled)
            assertTrue("in the $what branch the handler is called before the guard's test", guard < handled!!)
        }

        assertGuarded("GROUP_KICK", kickBranch, "processGroupKick(")
        assertGuarded("GROUP_KEY", keyBranch, "processGroupKey(")

        val calls =
            AdminCheckSource
                .mainSources()
                .mapValues { (_, code) ->
                    Regex("""(?<!fun )\b${Regex.escape(AdminCheckSource.GUARD)}""").findAll(code).count()
                }.filterValues { it > 0 }
        assertEquals("the guard's test is made somewhere else than in the two admin branches", mapOf("ChatViewModel.kt" to 2), calls)
    }

    // ------------------------------------------------------------------ (g)

    @Test
    fun `g - the pass signal makes the other live view model begin one pass that applies the on-chain kick`() =
        runTest {
            val p = readyPhone()
            val newKey = AnnounceDriver.newGroupKey()
            val kick = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = newKey)
            val kickTransaction = p.history.received(kick, kickTime, n = 6)
            val kickId = kickTransaction.id.txIdString()
            val seams = listOf(HandshakeSeam(Y), HandshakeSeam(Y))
            val settings = AdminCheck.chatVm(p, seams[0]) // the Group settings screen's view model: it confirms
            val chatList = AdminCheck.chatVm(p, seams[1]) // the chat list's view model: it stays
            val settingsHistory = AdminCheck.ownHistory(settings)
            val chatListHistory = AdminCheck.ownHistory(chatList)

            // A pass reads every transaction's memos, so each view model's passes are its reads of the kick's memo.
            fun settingsPasses() = settingsHistory.memoReads.count { it == kickId }

            fun chatListPasses() = chatListHistory.memoReads.count { it == kickId }
            val settingsCollector = AdminCheck.collectHistory(settings, backgroundScope)
            val chatListCollector = AdminCheck.collectHistory(chatList, backgroundScope)
            try {
                runCurrent()
                assertEquals("fixture: the Group settings view model made its first pass", 1, settingsPasses())
                assertEquals("fixture: the chat list's view model made its first pass", 1, chatListPasses())
                assertEquals("fixture: before the confirmation the on-chain kick is refused", MemberStatus.ACTIVE, p.base.status(GID, M))

                val signalBefore = AdminCheck.passSignal().value
                val countBefore = AdminCheck.passCounter()
                // What the process held at each store call from here on: the signal's value and the group's number.
                val log = Collections.synchronizedList(mutableListOf<Triple<String, Long, Long?>>())
                p.onCall = { name -> log += Triple(name, AdminCheck.passSignal().value, AdminCheck.confirmedAtPass()) }
                // The screen is left while the confirmation writes: its collection and its own scope are cancelled
                // before the scheduler runs again.
                p.onDurableWrite = {
                    settingsCollector.cancel()
                    settings.viewModelScope.cancel()
                }

                AdminCheck.confirmNow(settings, Y, p.admin.publicKey)
                AdminCheck.awaitOwnJobs(settings)
                p.onDurableWrite = null
                runCurrent()

                assertEquals("the confirmation's write did not stand", p.admin.publicKey, p.base.info(GID).creatorPublicKey)
                assertEquals("the pass signal's value did not grow by one", signalBefore + 1, AdminCheck.passSignal().value)
                assertEquals("the view model whose screen was left made a pass", 1, settingsPasses())
                assertEquals(
                    "the other live view model did not make exactly one more pass (no transaction, row or timer changed)",
                    2,
                    chatListPasses(),
                )
                val recorded = AdminCheck.confirmedAtPass()
                assertEquals("the group's number is not the one the counter showed at the confirmation", countBefore, recorded)
                assertEquals("more or fewer than one pass began after the confirmation", countBefore + 1, AdminCheck.passCounter())
                assertTrue("that pass's number is not above the group's recorded number", AdminCheck.passCounter() > recorded!!)
                assertKickApplied(p, newKey)

                // The call log: the signal was not raised before the key was written, and wherever it was seen raised
                // the group's number was already recorded.
                val atWrite = log.first { it.first == "saveGroupInfosDurably" }
                assertEquals("the pass signal was raised before the creator key was written", signalBefore, atWrite.second)
                val raised = log.filter { it.second > signalBefore }
                assertTrue("fixture: the pass the signal started read the stores", raised.isNotEmpty())
                assertEquals(
                    "the pass signal was raised before the group's pass number was recorded",
                    emptyList<Triple<String, Long, Long?>>(),
                    raised.filter { it.third != recorded },
                )
                p.onCall = null

                // A refused confirmation (the group needs none any more) raises no signal and starts no pass.
                AdminCheck.confirmNow(chatList, Y, p.admin.publicKey)
                assertEquals("NOT_NEEDED", AdminCheck.awaitOutcome(chatList))
                runCurrent()
                assertEquals("a refused confirmation raised the pass signal", signalBefore + 1, AdminCheck.passSignal().value)
                assertEquals("a refused confirmation started a pass", 2, chatListPasses())
                assertEquals("a pass or the confirmation proposed a payment", 0, seams.sumOf { it.proposalRequests.get() })
            } finally {
                p.onCall = null
                p.onDurableWrite = null
                // The counter, the per-group numbers and the signal are process-wide: no collector outlives the case.
                settingsCollector.cancelAndJoin()
                chatListCollector.cancelAndJoin()
            }
        }

    // ------------------------------------------------------------------ (h)

    /**
     * A drifted phone ready for the confirmation whose admin can be reached over the relays, its chat view model, and
     * the process-wide send scope as a scope on the test's scheduler: what is launched there runs only when the
     * scheduler runs, while the view model's own scope runs at once.
     */
    private suspend fun phoneWithARelayRoute(): Pair<AdminCheckPhone, ChatViewModel> {
        val p = readyPhone()
        p.base.nostrKeys[Y] = Y_NOSTR
        AdminCheck.relayRecorder(published)
        val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
        ownSendScope = AdminCheck.sendScopeOn(StandardTestDispatcher(scheduler))
        return p to vm
    }

    @Test
    fun `h - Back at once - the roster query is still sent, once`() =
        runBlocking {
            val (p, vm) = phoneWithARelayRoute()
            // The screen is left while the confirmation writes: the view model's own scope is cancelled.
            p.onDurableWrite = { vm.viewModelScope.cancel() }

            AdminCheck.confirmNow(vm, Y, p.admin.publicKey)
            AdminCheck.awaitOwnJobs(vm)

            assertEquals("the confirmation's write did not stand", p.admin.publicKey, p.base.info(GID).creatorPublicKey)
            assertEquals(
                "the roster query was sent before the scheduler ran: it was not launched on the process-wide send scope",
                emptyList<Pair<String, String>>(),
                published.toList(),
            )

            scheduler.advanceUntilIdle()

            assertEquals(
                "leaving the screen at once lost the one roster query to the confirmed admin",
                listOf(rosterQuery to Y_NOSTR),
                published.toList(),
            )
        }

    @Test
    fun `h - without leaving the screen the roster query is sent once as well`() =
        runBlocking {
            val (p, vm) = phoneWithARelayRoute()

            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            AdminCheck.awaitOwnJobs(vm)
            assertEquals(
                "the roster query was sent before the scheduler ran: it was not launched on the process-wide send scope",
                emptyList<Pair<String, String>>(),
                published.toList(),
            )

            scheduler.advanceUntilIdle()

            assertEquals("the confirmation did not send exactly one roster query", listOf(rosterQuery to Y_NOSTR), published.toList())
        }

    @Test
    fun `h - a refused confirmation publishes nothing`() =
        runBlocking {
            val (p, vm) = phoneWithARelayRoute()
            p.verified -= Y

            assertEquals("NOT_VERIFIED", AdminCheck.confirm(vm, Y, p.admin.publicKey))
            AdminCheck.awaitOwnJobs(vm)
            scheduler.advanceUntilIdle()

            assertEquals("a refused confirmation published over the relays", emptyList<Pair<String, String>>(), published.toList())
        }

    @Test
    fun `h - source - the roster query is launched on the process send scope, with no dispatcher, in the group lock`() {
        val source = ConfirmSource()
        val body = source.body
        val query = source.queries.singleOrNull() ?: throw AssertionError("confirmGroupAdmin does not call sendRosterQuery( exactly once")
        val onSendScope = Regex("""processSendScope\s*\(\s*\)\s*\.\s*(launch)\b""").find(body)
        val launch =
            onSendScope?.groups?.get(1)?.range
                ?: throw AssertionError("confirmGroupAdmin launches nothing on processSendScope()")
        assertTrue(
            "the launch on processSendScope() takes a dispatcher argument: on the main thread the send would start inside the lock",
            body.substring(launch.last + 1).trimStart().startsWith("{"),
        )
        val launched =
            AdminCheckSource.lambdaOf(body, launch.first)
                ?: throw AssertionError("the launch on processSendScope() has no block")
        assertTrue("sendRosterQuery( does not stand inside the launch on processSendScope()", query in launched)

        // The launch that encloses the call most closely is that one: never a viewModelScope.launch inside it.
        val innermost =
            Regex("""\blaunch\b""")
                .findAll(body)
                .mapNotNull { AdminCheckSource.lambdaOf(body, it.range.first) }
                .filter { query in it }
                .minByOrNull { it.last - it.first }
        assertEquals("sendRosterQuery( stands inside another launch than the one on processSendScope()", launched, innermost)
        assertFalse(
            "sendRosterQuery( stands inside a viewModelScope.launch within the launch on processSendScope()",
            "viewModelScope" in body.substring(launched),
        )

        assertTrue("the launch on processSendScope() does not stand inside the withGroupLock( block", launch.first in source.groupLock)
        assertTrue("the roster query is launched before the pass signal is raised", source.raised.single() < launch.first)
    }
}
