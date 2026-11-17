package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.STRANGER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
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
import java.time.Instant
import java.util.Collections

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R8 and R11; section 4.1 class 5) — the confirmation of a group's admin: seven
 * checks, then one write, and nothing else.
 *
 * `ChatViewModel.confirmGroupAdmin(groupId, candidate, shownKey)` binds the chat the phone's own records give
 * (the creating invitation, class 4) to the group, when the user compared that chat's safety number and the chat's
 * key is still the one the dialog showed. It writes the group's creator key once, through
 * `saveGroupInfosDurably` with one record, and nothing else: not the creator address, not an alias, not the member
 * list, not a contact's key or its verified mark. It pays nothing and sends one free relay message, the roster query
 * to the confirmed chat. Every refusal leaves the record as it was and sends nothing.
 *
 * At `00f1a349b` there is no confirmation: a member's phone that cannot match its group's admin to a key drops every
 * change of that admin for good.
 *
 * All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmGroupAdminChecksThenWritesOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

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
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val created: Instant = Instant.ofEpochMilli(T)
    private val kickTime: Instant = Instant.ofEpochMilli(T + 60_000)

    /**
     * A drifted phone ready for the confirmation: the creating invite from the chat under [Y] is in the history, the
     * user compared that chat's safety number, and the chat can be reached over the relays (a publisher is registered).
     */
    private fun readyPhone(base: AdminCheckPhone = AdminCheck.driftedPhone()): AdminCheckPhone =
        base.also { p ->
            p.creatingInvite(p.base.sessionWith(Y), created)
            p.verified += Y
            p.base.nostrKeys[Y] = Y_NOSTR
            AdminCheck.relayRecorder(published)
        }

    /** A pass of the scan over one on-chain memo (a pass is this call made again). */
    private fun onChain(
        vm: ChatViewModel,
        memo: String,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(6), minedHeight = HEIGHT + 6)

    /**
     * The confirmation of [candidate] with [shownKey] on [p] is refused with one of [reasons], leaves the group's
     * record as it was, writes nothing ([writes] attempted store writes, none stored), publishes nothing and proposes
     * nothing.
     */
    private suspend fun assertRefused(
        p: AdminCheckPhone,
        candidate: String,
        shownKey: String,
        vararg reasons: String,
        writes: Int = 0,
    ) {
        val seam = HandshakeSeam(Y)
        val vm = AdminCheck.chatVm(p, seam)
        val before = p.snapshot()

        val outcome = AdminCheck.confirm(vm, candidate, shownKey)
        HandshakeHarness.awaitIdle(vm)

        assertTrue("the confirmation answered $outcome, wanted ${reasons.joinToString(" or ")}", outcome in reasons)
        assertEquals("a refused confirmation changed a store", before, p.snapshot())
        assertEquals("a refused confirmation reached the group store", writes, p.durableWrites.size)
        assertEquals("a refused confirmation published over the relays", emptyList<Pair<String, String>>(), published.toList())
        assertEquals("a refused confirmation proposed a payment", 0, seam.proposalRequests.get())
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - the recorded and verified chat is confirmed with one durable write and one free roster query`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val key = p.admin.publicKey
            val recordBefore = p.base.info(GID)
            val rosterBefore = p.base.rosters[GID]
            val callsBefore = p.calls.size

            val outcome = AdminCheck.confirm(vm, Y, key)
            HandshakeHarness.awaitIdle(vm)

            assertEquals(CONFIRMED, outcome)
            assertEquals("the creator key was not written through one durable write", 1, p.durableWrites.size)
            assertEquals("the durable write carried more than the group's record", setOf(GID), p.durableWrites.single().keys)
            assertEquals(
                "the record written is not the stored record with the confirmed key and nothing else changed",
                recordBefore.copy(creatorPublicKey = key),
                ZMSGGroupProtocol.deserializeGroupInfo(p.durableWrites.single().getValue(GID)),
            )
            assertEquals("the group's creator key is not the confirmed chat's key", key, p.base.info(GID).creatorPublicKey)
            assertEquals("the confirmation rewrote the group's creator address", X_OLD, p.base.info(GID).creatorAddress)
            assertEquals("the confirmation rewrote the member list", rosterBefore, p.base.rosters[GID])

            val calls = p.calls.drop(callsBefore)
            val forbidden = listOf("setPeerAddressAlias", "clearPeerAddressAlias", "setE2EVerified", "setE2EPeerPublicKey")
            assertEquals(
                "the confirmation wrote an alias, a contact key, a verified mark, the member list or the record a second way",
                emptyList<String>(),
                calls.filter { it in forbidden || it.startsWith("saveGroupMembers") || it == "saveGroupInfo" },
            )
            assertEquals("the confirmation changed the contact's key or an alias", p.admin.publicKey, p.base.peerKeys[Y])
            assertTrue("the confirmation wrote an address alias", p.base.aliases.isEmpty())

            assertEquals("the confirmation proposed a payment", 0, seam.proposalRequests.get())
            assertEquals(
                "the confirmation did not publish exactly one relay message, the roster query to the confirmed chat",
                listOf(ZMSGGroupProtocol.createGroupRosterQueryMessage(GID) to Y_NOSTR),
                published.toList(),
            )
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - a group whose creator key a chat still holds is not needed and its key is never replaced`() =
        runBlocking {
            val p = readyPhone(AdminCheck.pinnedPhone())

            assertRefused(p, Y, p.admin.publicKey, "NOT_NEEDED")
        }

    @Test
    fun `b - a verified contact that is not the recorded chat is refused`() =
        runBlocking {
            val p = readyPhone()
            val member = p.base.link(M)
            p.verified += M

            assertRefused(p, M, member.publicKey, "NOT_THE_RECORDED_CHAT")
        }

    @Test
    fun `b - a contact with no record at all is refused even when it is verified and holds a key`() =
        runBlocking {
            // No creating invite in the history: the phone's records give no chat, so no chat can be confirmed.
            val p = AdminCheck.driftedPhone()
            p.verified += Y
            p.base.nostrKeys[Y] = Y_NOSTR
            AdminCheck.relayRecorder(published)

            assertRefused(p, Y, p.admin.publicKey, "NOT_THE_RECORDED_CHAT")
        }

    @Test
    fun `b - an own address is refused`() =
        runBlocking {
            val p = readyPhone()
            p.ownAddresses += Y

            assertRefused(p, Y, p.admin.publicKey, "OWN_ADDRESS")
        }

    @Test
    fun `b - a chat request still waiting for Accept is refused`() =
        runBlocking {
            val p = readyPhone()
            p.base.awaitingAccept[Y] = p.admin.publicKey

            assertRefused(p, Y, p.admin.publicKey, reasonForAnUnfinishedChat(p))
        }

    @Test
    fun `b - a chat without a key pair of ours is refused`() =
        runBlocking {
            val p = readyPhone()
            val key = p.admin.publicKey
            // The contact (a saved one) still holds its key; our own key pair for that chat is gone.
            p.name(Y, "Synthetic Admin")
            p.base.ourKeys.remove(Y)

            assertRefused(p, Y, key, reasonForAnUnfinishedChat(p))
        }

    /**
     * A chat that is not finished fails check 4 (`NOT_A_SECURE_CHAT`) when the phone's records still give it as the
     * candidate, and check 2 (`NOT_THE_RECORDED_CHAT`) when they give no candidate any more (R7 tries the sessions of
     * finished chats only): the confirmation and the card must agree on which.
     */
    private suspend fun reasonForAnUnfinishedChat(p: AdminCheckPhone): String {
        val f = GroupPaidOnceFixture()
        f.scheduler = scheduler
        val candidate = AdminCheck.loadCandidate(AdminCheck.groupVm(p, f), f)
        return if (candidate != null && AdminCheck.property(candidate, "address") == Y) "NOT_A_SECURE_CHAT" else "NOT_THE_RECORDED_CHAT"
    }

    @Test
    fun `b - a chat whose key change is pending is refused`() =
        runBlocking {
            val p = readyPhone()
            p.base.keyChanged += Y

            assertRefused(p, Y, p.admin.publicKey, "KEY_CHANGED")
        }

    @Test
    fun `b - a chat whose safety number was not compared is refused`() =
        runBlocking {
            val p = readyPhone()
            p.verified -= Y

            assertRefused(p, Y, p.admin.publicKey, "NOT_VERIFIED")
        }

    @Test
    fun `b - a key other than the one the dialog showed is refused`() =
        runBlocking {
            val p = readyPhone()

            assertRefused(p, Y, E2EEncryption.generateKeyPair().publicKey, "KEY_DIFFERS")
        }

    @Test
    fun `b - a store that refuses the write is reported and nothing is published`() =
        runBlocking {
            val p = readyPhone()
            p.storeRefuses = true

            assertRefused(p, Y, p.admin.publicKey, "STORE_REFUSED", writes = 1)
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - a contact key replaced while the write is made is refused and the previous creator key is back`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val key = p.admin.publicKey
            val recordBefore = p.base.info(GID)
            val replacement = E2EEncryption.generateKeyPair().publicKey
            // Inside the store call that writes the creator key, the contact's key becomes another one.
            p.onDurableWrite = { p.base.peerKeys[Y] = replacement }

            val outcome = AdminCheck.confirm(vm, Y, key)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("KEY_DIFFERS", outcome)
            assertNull("a key the chat no longer holds stayed the group's creator key", p.base.info(GID).creatorPublicKey)
            assertEquals("the record is not the one from before the confirmation", recordBefore, p.base.info(GID))
            assertEquals("the contact's key was written by the confirmation", replacement, p.base.peerKeys[Y])
            assertEquals("a refused confirmation published over the relays", emptyList<Pair<String, String>>(), published.toList())
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - the admin's on-chain kick changes nothing before the confirmation and is applied on the next pass after it`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val newKey = AnnounceDriver.newGroupKey()
            val kick = AdminCheck.kickNaming(p, naming = Y, kicked = M, newEpoch = 2, newKey = newKey)
            val before = p.snapshot()

            repeat(3) { onChain(vm, kick) }
            assertEquals("a kick changed the group before its admin was confirmed", before, p.snapshot())

            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            HandshakeHarness.awaitIdle(vm)
            assertEquals("the confirmation itself removed a member", MemberStatus.ACTIVE, p.base.status(GID, M))

            onChain(vm, kick)

            assertEquals("the confirmed admin's kick did not remove the member", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals("the confirmed admin's kick did not move the key epoch", 2, p.base.epochs[GID])
            assertEquals("the new group key was not adopted from the confirmed chat", newKey, p.groupKey(2))
            assertEquals("the removal raised no removal floor", 2, p.base.floors[GID])
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - a verified member's signed controls naming the member change nothing before and after the confirmation`() =
        runBlocking {
            val p = readyPhone()
            val member = p.base.link(M)
            p.verified += M
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val kick =
                AdminCheck.kickNaming(
                    p,
                    naming = M,
                    kicked = STRANGER,
                    newEpoch = 2,
                    newKey = AnnounceDriver.newGroupKey(),
                    signer = member,
                    wrapFor = M,
                )
            val selfKick = AdminCheck.kickNaming(p, naming = M, kicked = p.self, newEpoch = 2, newKey = null, signer = member, wrapFor = M)
            val rotation =
                AdminCheck.keyNaming(p, naming = M, newEpoch = 2, newKey = AnnounceDriver.newGroupKey(), signer = member, wrapFor = M)

            // Each control on three passes of the scan.
            fun memberControls() = repeat(3) { listOf(kick, selfKick, rotation).forEach { onChain(vm, it) } }

            val before = p.snapshot()
            memberControls()
            assertEquals("a member's signed control changed the group before the confirmation", before, p.snapshot())

            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            HandshakeHarness.awaitIdle(vm)
            val confirmed = p.snapshot()
            assertNotNull(p.base.info(GID).creatorPublicKey)

            memberControls()
            assertEquals("a member's signed control changed the group after the confirmation", confirmed, p.snapshot())
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (f)

    @Test
    fun `f - source - the confirmation takes the contact lock, the group lock, the wallet-scope lock, then the key monitor`() {
        val body = AdminCheckSource.structure(AdminCheckSource.chatMember("confirmGroupAdmin"))

        fun lockBlock(
            what: String,
            call: Regex,
            inside: IntRange,
        ): IntRange {
            val found = call.find(body, inside.first)?.range
            val at =
                found?.first?.takeIf { it <= inside.last }
                    ?: throw AssertionError("confirmGroupAdmin does not take $what inside the lock before it (order: $ORDER)")
            return AdminCheckSource.lambdaOf(body, at)
                ?: throw AssertionError("confirmGroupAdmin: $what guards no block")
        }

        val contact = lockBlock("the contact lock (withPeerSession)", Regex("""withPeerSession\s*\("""), body.indices)
        val group = lockBlock("the group lock (withGroupLock)", Regex("""withGroupLock\s*\("""), contact)
        val wallet =
            lockBlock(
                "the wallet-scope lock (synchronized(ZchatPreferences.walletScopeLock))",
                Regex("""synchronized\s*\(\s*(?:ZchatPreferences\s*\.\s*)?walletScopeLock\s*\)"""),
                group,
            )
        val keys = lockBlock("the key monitor (synchronized(peerKeyMonitor))", Regex("""synchronized\s*\(\s*peerKeyMonitor\s*\)"""), wallet)

        for (lock in listOf("withPeerSession", "withGroupLock", "walletScopeLock", "peerKeyMonitor")) {
            assertEquals(
                "confirmGroupAdmin names $lock more than once: one nesting, in the order $ORDER",
                1,
                Regex("""\b$lock\b""").findAll(body).count(),
            )
        }
        val monitored = body.substring(keys)
        assertEquals(
            "inside its key-monitor block confirmGroupAdmin takes a lock that must be taken before the key monitor",
            emptyList<String>(),
            listOf("withGroupLock(", "withHeldAcceptsLock(", "writeForInviteWallet(", "withPeerSession(").filter { it in monitored },
        )
    }

    private companion object {
        const val ORDER = "withPeerSession, withGroupLock, walletScopeLock, peerKeyMonitor"
    }
}
