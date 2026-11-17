package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatClaim
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryChatOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryStore
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.announceStateToJson
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.STRANGER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y2
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y2_NOSTR
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * KI-25 pair 1b, ZC-704 (KI-25-SPEC 3.6b R19; section 4.1b class 21) — a removal or a key change that reaches a
 * phone over the relays while its group cannot confirm its admin is stored in the group's announce state and applied
 * after the confirmation, at the end of the first complete history pass that began after it.
 *
 * The phone is [AdminCheck.driftedPhone] unless a case says otherwise, over a real ChatViewModel. Every relay
 * delivery is made as the reader makes it ([AdminCheck.relayDeliver]: the event is marked seen first, and nothing
 * delivers it again), and every pass goes through the seam ([AdminCheck.beginPass], [AdminCheck.receiveInPass],
 * [AdminCheck.endPass]) or, in (n) and (q), through the real history flow. The stored list is read back from the
 * fixture's store as JSON ([AdminCheck.keptChanges]: `announceStateToJson`, the key `kept_admin_changes`).
 *
 * At `0c7a15fc7` (pair 1's fix) the relay event is marked seen, the gate refuses the change and nothing is stored, so
 * no pass can apply it: the cases fail where the list should hold the change, or where a commit of the group-key
 * store should come before the epoch. The cases named "pin" hold there too.
 *
 * Nothing is paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class RefusedRelayAdminChangeIsStoredAndAppliedAfterTheConfirmationTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private var ownSendScope: CoroutineScope? = null

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        ownSendScope?.cancel()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    private val created: Instant = Instant.ofEpochMilli(T)
    private val relayTime: Instant = Instant.ofEpochMilli(T + 60_000)
    private val chainTime: Instant = Instant.ofEpochMilli(T + 30_000)
    private val rosterQuery = ZMSGGroupProtocol.createGroupRosterQueryMessage(GID)

    // ----------------------------------------------------------------- phones

    /** A drifted phone ready for the confirmation: the creating invite from Y is in the history and Y is verified. */
    private fun readyPhone(): AdminCheckPhone =
        AdminCheck.driftedPhone().also { p ->
            p.creatingInvite(p.base.sessionWith(Y), created)
            p.verified += Y
            p.base.nostrKeys[Y] = Y_NOSTR
        }

    /** A phone whose group reads OK: the admin's key is the stored creator key. */
    private fun confirmedPhone(): AdminCheckPhone = AdminCheck.pinnedPhone().also { it.base.nostrKeys[Y] = Y_NOSTR }

    /** A second member beside M, ACTIVE in the roster. */
    private fun AdminCheckPhone.withSecondMember(): AdminCheckPhone =
        also {
            val row = GroupMember(address = M2, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE)
            base.rosters[GID] = ZMSGGroupProtocol.serializeGroupMembers(base.roster(GID) + row)
        }

    private suspend fun confirm(
        vm: ChatViewModel,
        p: AdminCheckPhone,
    ) {
        assertEquals("fixture: the admin's chat is confirmed", CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
        // Only the view model's own jobs: the roster query waits in the process-wide send scope, which a case on a
        // standard dispatcher advances itself after this returns (the wait of pair 1's class 8 (h)).
        AdminCheck.awaitOwnJobs(vm)
    }

    /** The confirmation's write as the store holds it after a kill: the key is in the record, no number is recorded. */
    private fun writeConfirmedKey(p: AdminCheckPhone) {
        val confirmed = p.base.info(GID).copy(creatorPublicKey = p.admin.publicKey)
        assertTrue(p.prefs.saveGroupInfosDurably(mapOf(GID to ZMSGGroupProtocol.serializeGroupInfo(confirmed))))
        AdminCheck.forgetConfirmedPasses()
    }

    // ---------------------------------------------------------------- changes

    private fun kick(
        p: AdminCheckPhone,
        kicked: String = M,
        epoch: Int = 2,
        key: String? = AnnounceDriver.newGroupKey(),
        signer: E2EKeyPair = p.admin,
    ): String = AdminCheck.kickNaming(p, naming = Y, kicked = kicked, newEpoch = epoch, newKey = key, signer = signer)

    private fun keyChange(
        p: AdminCheckPhone,
        epoch: Int,
        key: String = AnnounceDriver.newGroupKey(),
    ): String = AdminCheck.keyNaming(p, naming = Y, newEpoch = epoch, newKey = key)

    private fun deliver(
        vm: ChatViewModel,
        memo: String,
        eventId: String,
        time: Instant = relayTime,
        sender: String = Y,
    ) = AdminCheck.relayDeliver(vm, memo, sender, eventId, time)

    private fun keptIds(
        p: AdminCheckPhone,
        groupId: String = GID,
    ): List<String> = AdminCheck.keptChanges(p, groupId).map { it.getString("id") }

    private fun entry(
        p: AdminCheckPhone,
        id: String,
    ): JSONObject = AdminCheck.keptChanges(p).single { it.getString("id") == id }

    private fun assertKept(
        p: AdminCheckPhone,
        vararg ids: String,
    ) = assertEquals(
        "the group's stored state does not hold the change(s) that came over the relays while it could not confirm its admin",
        ids.toSet(),
        keptIds(p).toSet(),
    )

    /** One history pass through the seam: begin, what the loop hands over ([inLoop]), the hook at its end. */
    private fun pass(
        vm: ChatViewModel,
        complete: Boolean = true,
        inLoop: (Any) -> Unit = {},
    ) {
        val pass = AdminCheck.beginPass(vm)
        inLoop(pass)
        AdminCheck.endPass(vm, pass, complete)
    }

    private fun epochWrites(p: AdminCheckPhone): List<Any?> = p.callLog.filter { it.first == "setGroupKeyEpoch" }.map { it.second[1] }

    private fun names(p: AdminCheckPhone): List<String> = p.callLog.map { it.first }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - a relay kick the group could not confirm is stored, and applied by the first complete pass after the confirmation`() =
        runBlocking {
            val p = readyPhone()
            AdminCheck.relayRecorder(published)
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            ownSendScope = AdminCheck.sendScopeOn(StandardTestDispatcher(scheduler))
            AdminCheck.setScannedTip(vm, TIP_AT_KEEP)
            val newKey = AnnounceDriver.newGroupKey()
            val memo = kick(p, key = newKey)
            val before = p.snapshot() - "states"

            deliver(vm, memo, KICK_1)

            assertEquals("a kick the group could not confirm changed something", before, p.snapshot() - "states")
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(1, p.base.epochs[GID])
            assertNull("a refused kick raised the removal floor", p.base.floors[GID])
            assertTrue("the relay event is not in the seen list", KICK_1 in p.seenEvents)
            assertKept(p, KICK_1)
            val kept = entry(p, KICK_1)
            assertEquals("GK", kept.getString("t"))
            assertEquals(2, kept.getInt("e"))
            assertEquals("the payload was not kept as it came", ZMSGGroupProtocol.parsePayload(memo), kept.getString("p"))
            assertEquals(Y, kept.getString("from"))
            assertEquals("the signer chat is not the chat whose key verified the signature", Y, kept.getString("by"))
            assertEquals(relayTime.toEpochMilli(), kept.getLong("at"))
            assertEquals("the entry does not record the height scanned to when it was kept", TIP_AT_KEEP, kept.getLong("h"))
            assertFalse("a change the gate refused is marked as accepted", kept.optBoolean("acc", false))
            assertFalse(kept.has("f"))

            confirm(vm, p)
            scheduler.advanceUntilIdle()

            assertEquals("the confirmation applied the stored kick itself", MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(1, p.base.epochs[GID])
            assertKept(p, KICK_1)
            assertEquals(0, seam.proposalRequests.get())
            assertEquals(
                "the confirmation did not publish exactly the one roster query",
                listOf(rosterQuery to Y_NOSTR),
                published.toList(),
            )

            // The phone has scanned further by now; the removal still counts from the height at the keeping.
            AdminCheck.setScannedTip(vm, TIP_LATER)
            pass(vm)
            scheduler.advanceUntilIdle()

            assertEquals("after the confirmation and a complete pass M is still ACTIVE", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals("the key epoch is not the kick's", 2, p.base.epochs[GID])
            assertEquals("the kick's key was not adopted", newKey, p.groupKey(2))
            assertEquals(2, p.base.floors[GID])
            assertEquals(
                "the removal floor does not count from the height the phone had scanned to when the change was kept",
                TIP_AT_KEEP,
                p.base.kickHeights[GID]?.get(2),
            )
            assertEquals("the stored list is not empty after the run", emptyList<String>(), keptIds(p))
            assertEquals("the run published something", listOf(rosterQuery to Y_NOSTR), published.toList())
            assertEquals("the run proposed a payment", 0, seam.proposalRequests.get())
            assertNull(
                "the group shows a line although the stored removal was applied",
                GroupAdminCheck.notice(p.prefs, emptyList(), GID, newestReceivedUnreadable = false),
            )
            assertFalse("an event was taken out of the seen list", "unmarkNostrEventSeen" in p.calls)
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - pin - the same event handed over a second time adds no entry`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val memo = kick(p)

            deliver(vm, memo, KICK_1)
            val once = keptIds(p)
            deliver(vm, memo, KICK_1)
            AnnounceDriver.receive(vm, memo, relayTime.plusSeconds(5), sender = Y, eventId = KICK_1)

            assertEquals("a second hand-over of one event added an entry", once, keptIds(p))
            assertTrue("one event is held more than once", keptIds(p).size <= 1)
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
        }

    @Test
    fun `b - after the pass one more hand-over and three more passes change nothing`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val memo = kick(p)
            deliver(vm, memo, KICK_1)
            deliver(vm, memo, KICK_1)
            assertEquals("the same event handed over twice is not held exactly once", listOf(KICK_1), keptIds(p))

            confirm(vm, p)
            pass(vm)
            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            val applied = p.snapshot()
            val writes = epochWrites(p)

            AnnounceDriver.receive(vm, memo, relayTime, sender = Y, eventId = KICK_1)
            repeat(3) { pass(vm) }

            assertEquals("a further hand-over or pass changed something", applied, p.snapshot())
            assertEquals("the kick was applied a second time", writes, epochWrites(p))
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - stored changes run in the order epoch, then removal before key change`() =
        runBlocking {
            val p = readyPhone().withSecondMember()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val keyOfKick2 = AnnounceDriver.newGroupKey()
            val keyOfKick3 = AnnounceDriver.newGroupKey()

            deliver(vm, keyChange(p, 3), KEY_3)
            deliver(vm, kick(p, M, 2, keyOfKick2), KICK_1)
            deliver(vm, kick(p, M2, 3, keyOfKick3), KICK_2)
            assertKept(p, KEY_3, KICK_1, KICK_2)

            confirm(vm, p)
            p.callLog.clear()
            pass(vm)

            assertEquals("the kick at epoch 2 was not applied", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(
                "the kick at epoch 3 was not applied: the key change at that epoch ran before it",
                MemberStatus.LEFT,
                p.base.status(GID, M2),
            )
            assertEquals("the handlers did not run as kick 2, kick 3, key 3", listOf<Any?>(2, 3), epochWrites(p))
            assertEquals(keyOfKick2, p.groupKey(2))
            assertEquals("the key at epoch 3 is not the kick's: the key change ran first", keyOfKick3, p.groupKey(3))
            assertEquals(
                listOf<Any?>(2, 3),
                p.callLog.filter { it.first == "setGroupRemovalFloor" }.map { it.second[1] },
            )
            assertEquals(emptyList<String>(), keptIds(p))
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - the chain first, then the stored changes`() =
        runBlocking {
            val p = readyPhone().withSecondMember()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val chainKey = AnnounceDriver.newGroupKey()
            val chainKick = kick(p, M, 2, chainKey)
            deliver(vm, kick(p, M2, 3), KICK_2)
            assertKept(p, KICK_2)

            // While the group cannot confirm its admin every pass hands the on-chain kick over and it is refused.
            repeat(3) { pass(vm) { AdminCheck.receiveInPass(vm, it, chainKick, AnnounceDriver.txId(6), chainTime, HEIGHT + 6) } }
            assertEquals(
                "fixture: the on-chain kick is refused while the admin is not confirmed",
                MemberStatus.ACTIVE,
                p.base.status(GID, M),
            )
            assertKept(p, KICK_2)

            confirm(vm, p)
            p.callLog.clear()
            pass(vm) { AdminCheck.receiveInPass(vm, it, chainKick, AnnounceDriver.txId(6), chainTime, HEIGHT + 6) }

            assertEquals("the on-chain kick was not applied", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals("the stored relay kick was not applied after the on-chain one", MemberStatus.LEFT, p.base.status(GID, M2))
            assertEquals("the on-chain change was not handed over first", listOf<Any?>(2, 3), epochWrites(p))
            assertEquals(chainKey, p.groupKey(2))
            assertEquals(emptyList<String>(), keptIds(p))
        }

    @Test
    fun `d - the named loss - a stored kick older than an on-chain key change is not applied`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val chainKey = AnnounceDriver.newGroupKey()
            deliver(vm, kick(p, M, 2), KICK_1)
            assertKept(p, KICK_1)

            confirm(vm, p)
            pass(vm) { AdminCheck.receiveInPass(vm, it, keyChange(p, 3, chainKey), AnnounceDriver.txId(7), chainTime, HEIGHT + 7) }

            // Register ZC-716: the order "the chain first" does not save this removal.
            assertEquals(3, p.base.epochs[GID])
            assertEquals(chainKey, p.groupKey(3))
            assertEquals(
                "a stored kick that is not newer than the on-chain key change was applied",
                MemberStatus.ACTIVE,
                p.base.status(GID, M),
            )
            assertEquals("a stored change that was not newer stays in the list", emptyList<String>(), keptIds(p))
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - a kick signed with another member's key is kept, refused after the confirmation, and never comes back`() =
        runBlocking {
            val p = readyPhone().withSecondMember()
            val member = p.base.link(M)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val forged = AdminCheck.kickNaming(p, M, M2, 2, AnnounceDriver.newGroupKey(), signer = member, wrapFor = M)

            deliver(vm, forged, KICK_1, sender = M)
            assertKept(p, KICK_1)
            assertEquals("the signer chat is not the member's own chat", M, entry(p, KICK_1).getString("by"))

            confirm(vm, p)
            assertKept(p, KICK_1)
            pass(vm)

            assertEquals("a change signed with another key was applied after the confirmation", MemberStatus.ACTIVE, p.base.status(GID, M2))
            assertEquals(1, p.base.epochs[GID])
            assertEquals("a change the gate refused in the run stays in the list", emptyList<String>(), keptIds(p))

            val after = p.snapshot()
            pass(vm)
            assertEquals("something brought the refused change back", after, p.snapshot())
            assertTrue("the relay event left the seen list", KICK_1 in p.seenEvents)
        }

    // ------------------------------------------------------------------ (f)

    /** [deliver] changes nothing but, at most, the seen list: nothing is kept and everything else is as today. */
    private suspend fun assertNotKept(
        what: String,
        p: AdminCheckPhone,
        groupId: String = GID,
        deliver: (ChatViewModel) -> Unit,
    ) {
        val seam = HandshakeSeam(Y)
        val vm = AdminCheck.chatVm(p, seam)
        val before = p.snapshot()
        deliver(vm)
        assertEquals("$what was kept", emptyList<String>(), keptIds(p, groupId))
        assertEquals("$what changed something", before, p.snapshot())
        assertEquals(0, seam.proposalRequests.get())
    }

    @Test
    fun `f - pin - not kept - an unknown group, a group this phone left, and this phone's own group`() =
        runBlocking {
            val other = "zgrp_0f0e0d0c0b0a090807060504"
            readyPhone().let { p ->
                assertNotKept("a kick for a group this phone does not hold", p, other) { vm ->
                    deliver(vm, AdminCheck.kickNaming(p, Y, M, 2, AnnounceDriver.newGroupKey(), groupId = other), KICK_1)
                }
                assertNull(p.base.states[other])
            }
            readyPhone().let { p ->
                p.base.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(isActive = false))
                assertNotKept("a kick for a group this phone left", p) { vm -> deliver(vm, kick(p), KICK_1) }
            }
            readyPhone().let { p ->
                p.base.selfCreated += GID
                assertNotKept("a kick for this phone's own group", p) { vm -> deliver(vm, kick(p), KICK_1) }
            }
        }

    @Test
    fun `f - pin - not kept - a refusal in state OK, an unsigned change, and a signature no key of the sealing contact verifies`() =
        runBlocking {
            confirmedPhone().let { p ->
                val member = p.base.link(M)
                val forged = AdminCheck.kickNaming(p, M, STRANGER, 2, AnnounceDriver.newGroupKey(), signer = member, wrapFor = M)
                assertNotKept("a kick the gate refused in a group that reads OK", p) { vm -> deliver(vm, forged, KICK_1, sender = M) }
            }
            readyPhone().let { p ->
                val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), p.base.sessionWith(Y))
                val unsigned = ZMSGGroupProtocol.createGroupKickMessage(GID, M, Y, 2, wrapped, "")
                assertNotKept("an unsigned kick", p) { vm -> deliver(vm, unsigned, KICK_1) }
            }
            readyPhone().let { p ->
                val stranger = E2EEncryption.generateKeyPair()
                assertNotKept("a kick whose signature verifies against no key of the sealing contact", p) { vm ->
                    deliver(vm, kick(p, signer = stranger), KICK_1)
                }
            }
        }

    @Test
    fun `f - pin - not kept - a signer chat that waits for Accept, an epoch not above the group's, and a member announce`() =
        runBlocking {
            readyPhone().let { p ->
                p.base.awaitingAccept[Y] = "synthetic-waiting-kex"
                assertNotKept("a kick whose signer chat is a request waiting for Accept and not at the admin's address", p) { vm ->
                    deliver(vm, kick(p), KICK_1)
                }
            }
            readyPhone().let { p ->
                assertNotKept("a kick whose epoch is not above the group's", p) { vm -> deliver(vm, kick(p, epoch = 1), KICK_1) }
                assertNotKept("a key change whose epoch is not above the group's", p) { vm -> deliver(vm, keyChange(p, 1), KEY_3) }
            }
            readyPhone().let { p ->
                val announce = AnnounceDriver.gp(AnnounceOp.A, STRANGER, T + 5_000, 1, p.admin, GID)
                assertNotKept("a member announce", p) { vm -> deliver(vm, announce, KICK_1) }
            }
        }

    @Test
    fun `f - pin - not kept - a payload of 2,049 characters, a payload that does not parse, and a copy from the chain`() =
        runBlocking {
            readyPhone().let { p ->
                // The signature's length varies by a character or two, so the padding is fitted to the memo it gives.
                var padding = 1_200
                var memo = kick(p, kicked = "k".repeat(padding))
                repeat(40) {
                    val length = ZMSGGroupProtocol.parsePayload(memo)!!.length
                    if (length != 2_049) {
                        padding += 2_049 - length
                        memo = kick(p, kicked = "k".repeat(padding))
                    }
                }
                assertEquals("fixture: the payload has 2,049 characters", 2_049, ZMSGGroupProtocol.parsePayload(memo)!!.length)
                assertNotKept("a kick whose payload has 2,049 characters", p) { vm -> deliver(vm, memo, KICK_1) }
            }
            readyPhone().let { p ->
                assertNotKept("a kick whose payload does not parse", p) { vm ->
                    deliver(vm, "ZMSG:3.0:GROUP:GK:$GID:{\"kicked\":", KICK_1)
                }
                assertNotKept("a key change whose payload does not parse", p) { vm ->
                    deliver(vm, "ZMSG:3.0:GROUP:GY:$GID:{\"signer\":", KEY_3)
                }
            }
            readyPhone().let { p ->
                assertNotKept("a kick from the chain", p) { vm ->
                    val pass = AdminCheck.beginPass(vm)
                    AdminCheck.receiveInPass(vm, pass, kick(p), AnnounceDriver.txId(6), chainTime, HEIGHT + 6)
                    AdminCheck.endPassIfBuilt(vm, pass, complete = true)
                }
            }
        }

    @Test
    fun `f - pin - not kept - a handler that throws, and a sealed change in an Open group is applied as today`() =
        runBlocking {
            confirmedPhone().let { p ->
                assertNotKept("a kick whose handler threw", p) { vm ->
                    p.onCall = { if (it == "getGroupMembers") throw IllegalStateException("fixture: the roster cannot be read") }
                    try {
                        deliver(vm, kick(p), KICK_1)
                    } finally {
                        p.onCall = null
                    }
                }
            }
            val p = AdminCheck.plainKeyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val newKey = AnnounceDriver.newGroupKey()
            val sealed = ZMSGGroupProtocol.createGroupKickMessage(GID, M, X_OLD, 2, null, "", plainKey = newKey)

            deliver(vm, sealed, KICK_1, sender = X_OLD)

            assertEquals("fixture: the creator's sealed kick is applied as it arrives", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
            assertEquals("a sealed change that was applied was kept", emptyList<String>(), keptIds(p))
        }

    // ------------------------------------------------------------------ (g)

    @Test
    fun `g - the signer search - the seal is routed to a second address text and only Y holds the admin's key`() =
        runBlocking {
            val p = readyPhone()
            p.base.link(Y2)
            p.base.nostrKeys[Y2] = Y_NOSTR
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            deliver(vm, kick(p), KICK_1, sender = Y2)

            assertKept(p, KICK_1)
            assertEquals("the seal's sender was not kept as the reader passed it", Y2, entry(p, KICK_1).getString("from"))
            assertEquals("the signer chat is not the chat that holds the admin's key", Y, entry(p, KICK_1).getString("by"))
        }

    @Test
    fun `g - the search goes on after a chat that verifies and still waits for Accept`() =
        runBlocking {
            val p = readyPhone()
            p.base.link(Y2)
            p.base.peerKeys[Y2] = p.admin.publicKey
            p.base.awaitingAccept[Y2] = "synthetic-waiting-kex"
            p.base.nostrKeys[Y2] = Y_NOSTR
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            deliver(vm, kick(p), KICK_1, sender = Y2)

            assertKept(p, KICK_1)
            assertEquals(
                "the finished chat was hidden by a request that verifies and waits for Accept",
                Y,
                entry(p, KICK_1).getString("by"),
            )
        }

    @Test
    fun `g - a chat bound to another relay identity that holds a verifying key does not count`() =
        runBlocking {
            // Control: bound to the same relay identity, Y's key counts.
            val same = readyPhone()
            same.base.link(Y2)
            same.base.nostrKeys[Y2] = Y_NOSTR
            deliver(AdminCheck.chatVm(same, HandshakeSeam(Y)), kick(same), KICK_1, sender = Y2)
            assertKept(same, KICK_1)

            val other = readyPhone()
            other.base.link(Y2)
            other.base.nostrKeys[Y2] = Y2_NOSTR
            assertNotKept("a kick sealed by a contact whose own chats hold no verifying key", other) { vm ->
                deliver(vm, kick(other), KICK_1, sender = Y2)
            }
        }

    // ------------------------------------------------------------------ (h)

    @Test
    fun `h - the kept change survives a restart and nothing is delivered again`() =
        runBlocking {
            val p = readyPhone()
            val newKey = AnnounceDriver.newGroupKey()
            deliver(AdminCheck.chatVm(p, HandshakeSeam(Y)), kick(p, key = newKey), KICK_1)
            assertKept(p, KICK_1)

            // A new process over the same store.
            AdminCheck.forgetConfirmedPasses()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            confirm(vm, p)
            pass(vm)

            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
            assertEquals(emptyList<String>(), keptIds(p))
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (i)

    @Test
    fun `i - after a kill between the confirmation and the end of the pass the first complete pass applies the list once`() =
        runBlocking {
            val p = readyPhone()
            val newKey = AnnounceDriver.newGroupKey()
            deliver(AdminCheck.chatVm(p, HandshakeSeam(Y)), kick(p, key = newKey), KICK_1)
            assertKept(p, KICK_1)

            writeConfirmedKey(p)
            assertNull("fixture: no number is recorded for the group", AdminCheck.confirmedAtPass())
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            assertEquals("the confirmation's write alone applied the list", MemberStatus.ACTIVE, p.base.status(GID, M))
            p.callLog.clear()

            pass(vm)

            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(newKey, p.groupKey(2))
            assertEquals(emptyList<String>(), keptIds(p))
            assertEquals("the list was not applied exactly once", listOf<Any?>(2), epochWrites(p))

            val applied = p.snapshot()
            pass(vm)
            assertEquals("a second pass changed something", applied, p.snapshot())
            assertEquals(listOf<Any?>(2), epochWrites(p))
        }

    // ------------------------------------------------------------------ (j)

    @Test
    fun `j - the list is written only after the flush, and a flush that answers false keeps the entries for the next pass`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            AdminCheck.setScannedTip(vm, TIP_AT_KEEP)
            val newKey = AnnounceDriver.newGroupKey()
            deliver(vm, kick(p, key = newKey), KICK_1)
            assertKept(p, KICK_1)
            val stored = AdminCheck.keptChanges(p).map { it.toString() }
            confirm(vm, p)

            p.flushAnswers = false
            p.callLog.clear()
            pass(vm)

            assertEquals("the change was not applied", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(2, p.base.epochs[GID])
            assertEquals("the list changed although the flush answered false", stored, AdminCheck.keptChanges(p).map { it.toString() })
            val refusedFlush = names(p).lastIndexOf("flushGroupStores")
            assertTrue("the run did not end with flushGroupStores", refusedFlush >= 0)
            assertFalse(
                "the announce state was written after a flush that answered false",
                "saveGroupAnnounceState" in names(p).drop(refusedFlush),
            )

            // The next complete pass repeats the change with no second effect.
            AdminCheck.setScannedTip(vm, TIP_LATER)
            val applied = p.snapshot() - "states"
            pass(vm)
            assertEquals("the repeated change had a second effect", applied, p.snapshot() - "states")
            assertEquals(TIP_AT_KEEP, p.base.kickHeights[GID]?.get(2))
            assertKept(p, KICK_1)

            p.flushAnswers = true
            p.callLog.clear()
            pass(vm)

            assertEquals("the list is not empty once the flush answered true", emptyList<String>(), keptIds(p))
            val flush = names(p).indexOf("flushGroupStores")
            val listWrite = names(p).lastIndexOf("saveGroupAnnounceState")
            assertTrue("flushGroupStores does not come before the list's write", flush in 0 until listWrite)
            assertEquals(newKey, p.groupKey(2))
        }

    // ------------------------------------------------------------------ (k)

    @Test
    fun `k - the run stops at a removal of this phone and the list of the closed group is dropped`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p, kicked = p.self, key = null), KICK_1)
            deliver(vm, keyChange(p, 3), KEY_3)
            assertKept(p, KICK_1, KEY_3)

            confirm(vm, p)
            pass(vm)

            assertFalse("the stored removal of this phone did not close the group", p.base.info(GID).isActive)
            assertTrue("the group is not marked as one this phone was removed from", p.base.info(GID).removedByAdmin)
            assertEquals("the key change after the own removal was handed over", 1, p.base.epochs[GID])
            assertNull(p.groupKey(3))
            assertEquals("the list of the closed group is not empty", emptyList<String>(), keptIds(p))
        }

    /** A stored removal of this phone was applied and the flush answered false: the phone, and its record from before. */
    private suspend fun killedAfterTheOwnRemoval(): Pair<AdminCheckPhone, String> {
        val p = readyPhone()
        val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
        deliver(vm, kick(p, kicked = p.self, key = null), KICK_1)
        deliver(vm, keyChange(p, 3), KEY_3)
        assertKept(p, KICK_1, KEY_3)
        confirm(vm, p)
        val activeRecord = p.base.infos.getValue(GID)

        p.flushAnswers = false
        pass(vm)

        assertFalse("the stored removal of this phone did not close the group", p.base.info(GID).isActive)
        assertKept(p, KICK_1, KEY_3)
        p.flushAnswers = true
        AdminCheck.forgetConfirmedPasses()
        return p to activeRecord
    }

    /** Counts the store calls at which the group's record was active while its list was empty. */
    private fun watchActiveWithAnEmptyList(p: AdminCheckPhone): () -> Int {
        var seen = 0
        p.onCall = { if (p.base.info(GID).isActive && keptIds(p).isEmpty()) seen++ }
        return { seen }
    }

    @Test
    fun `k - the kill after the handler - over a record that is still active the next pass applies the removal again`() =
        runBlocking {
            val (p, activeRecord) = killedAfterTheOwnRemoval()
            // The record's apply() was lost with the process; the list's commit was never made.
            p.base.infos[GID] = activeRecord
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val violations = watchActiveWithAnEmptyList(p)

            pass(vm)
            p.onCall = null

            assertFalse("the removal was not applied again", p.base.info(GID).isActive)
            assertTrue(p.base.info(GID).removedByAdmin)
            assertEquals(emptyList<String>(), keptIds(p))
            assertEquals(1, p.base.epochs[GID])
            assertEquals("the list was empty while the record was active", 0, violations())
        }

    @Test
    fun `k - the kill after the handler - over a record that is inactive the next pass drops the list after the flush`() =
        runBlocking {
            val (p, _) = killedAfterTheOwnRemoval()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val violations = watchActiveWithAnEmptyList(p)
            p.callLog.clear()

            pass(vm, complete = false)
            p.onCall = null

            assertEquals("the list of the inactive group was not dropped", emptyList<String>(), keptIds(p))
            val flush = names(p).indexOf("flushGroupStores")
            assertTrue("the list was dropped before the flush", flush in 0 until names(p).lastIndexOf("saveGroupAnnounceState"))
            assertEquals("the key change behind the own removal was handed over", 1, p.base.epochs[GID])
            assertEquals(0, violations())
        }

    @Test
    fun `k - a handler that throws stops the run, is counted, and after three failed runs its entry leaves`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val lastKey = AnnounceDriver.newGroupKey()
            deliver(vm, keyChange(p, 2), KEY_2)
            deliver(vm, kick(p, M, 3), KICK_1)
            deliver(vm, keyChange(p, 4, lastKey), KEY_4)
            assertKept(p, KEY_2, KICK_1, KEY_4)
            confirm(vm, p)

            fun failingPass() {
                p.onCall = { if (it == "getGroupMembers") throw IllegalStateException("fixture: the roster cannot be read") }
                try {
                    pass(vm)
                } finally {
                    p.onCall = null
                }
            }

            failingPass()
            assertEquals("the first entry is not gone, or the second and third did not stay", listOf(KICK_1, KEY_4), keptIds(p))
            assertEquals("the failed run was not counted", 1, entry(p, KICK_1).optInt("f", 0))
            assertFalse(entry(p, KEY_4).has("f"))
            assertEquals("the run did not stop at the entry whose handler threw", 2, p.base.epochs[GID])

            failingPass()
            failingPass()
            assertEquals(listOf(KICK_1, KEY_4), keptIds(p))
            assertEquals(3, entry(p, KICK_1).optInt("f", 0))
            assertEquals(2, p.base.epochs[GID])

            // An entry that failed in three runs leaves at the start of the next, and the one behind it is applied.
            failingPass()
            assertEquals("the entry that failed in three runs still holds the rest back", emptyList<String>(), keptIds(p))
            assertEquals(4, p.base.epochs[GID])
            assertEquals(lastKey, p.groupKey(4))
            assertEquals("the kick whose handler threw was applied", MemberStatus.ACTIVE, p.base.status(GID, M))
        }

    // ------------------------------------------------------------------ (l)

    @Test
    fun `l - release - the user leaves the group - the list is dropped at the end of the next pass, complete or not`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            assertKept(p, KICK_1)

            // What leaving writes: the record inactive, and the time.
            p.base.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(isActive = false))
            p.base.states[GID] = p.base.state(GID).copy(inactiveSince = T + 90_000)
            assertKept(p, KICK_1)

            pass(vm, complete = false)

            assertEquals("the list of a group the user left outlived the next pass", emptyList<String>(), keptIds(p))
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(T + 90_000, p.base.state(GID).inactiveSince)
        }

    /** The state a real keep left, with a used invite id beside it. */
    private suspend fun stateWithAKeptChange(): AnnounceState {
        val p = readyPhone()
        deliver(AdminCheck.chatVm(p, HandshakeSeam(Y)), kick(p), KICK_1)
        assertKept(p, KICK_1)
        return p.base.state(GID).copy(hw = 4, usedInviteIds = setOf("synthetic-used-invite"))
    }

    @Test
    fun `l - release - deleteGroup - the tombstone holds no kept change`() =
        runBlocking {
            val state = stateWithAKeptChange()
            val (store, files) = realPreferences()
            val file = files.getValue("groupMembersPrefs")
            assertTrue(store.saveGroupAnnounceState(GID, state))
            assertEquals(
                "the real store does not write the kept change",
                listOf(KICK_1),
                AdminCheck.keptChangesOf(file.getString("group_ann_state:$GID", null)!!).map { it.getString("id") },
            )

            store.deleteGroup(GID)

            val tombstone = file.getString("group_ann_state:$GID", null) ?: throw AssertionError("fixture: the tombstone is kept")
            assertEquals(
                "the tombstone of a deleted group holds a kept change",
                emptyList<JSONObject>(),
                AdminCheck.keptChangesOf(tombstone),
            )
            assertEquals(setOf("synthetic-used-invite"), store.getGroupAnnounceState(GID).usedInviteIds)
            assertEquals(4L, store.getGroupAnnounceState(GID).hw)
        }

    @Test
    fun `l - release - the wipe leaves no announce state`() =
        runBlocking {
            val state = stateWithAKeptChange()
            val (store, files) = realPreferences()
            assertTrue(store.saveGroupAnnounceState(GID, state))
            assertEquals(
                listOf(KICK_1),
                AdminCheck.keptChangesOf(announceStateToJson(store.getGroupAnnounceState(GID))).map { it.getString("id") },
            )

            store.clearAll()

            assertTrue("the wipe left something in the group-members store", files.getValue("groupMembersPrefs").all.isEmpty())
            assertEquals(AnnounceState(), store.getGroupAnnounceState(GID))
        }

    // ------------------------------------------------------------------ (m)

    @Test
    fun `m - a change of wallet between the keep's read and its write - nothing is written`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            assertKept(p, KICK_1)
            val stored = p.base.state(GID)

            // The recovery phrase changes while the next change is handled: after the branch read the wallet, before
            // the keep writes.
            var armed = true
            p.onCall = { name ->
                if (armed && name == "getGroupInfo") {
                    armed = false
                    p.walletGeneration += 1
                }
            }
            deliver(vm, keyChange(p, 3), KEY_3)
            p.onCall = null

            assertFalse("fixture: the wallet changed while the change was handled", armed)
            assertEquals("a change of another wallet's group was written into this wallet's stores", stored, p.base.state(GID))
            assertEquals(listOf(KICK_1), keptIds(p))
        }

    @Test
    fun `m - the list moves with its wallet's stores and the other wallet's group of the same id holds none`() =
        runBlocking {
            val p = readyPhone()
            deliver(AdminCheck.chatVm(p, HandshakeSeam(Y)), kick(p), KICK_1)
            assertKept(p, KICK_1)
            val stateKey = "group_ann_state:$GID"
            val stored = announceStateToJson(p.base.state(GID))
            val members = InMemoryStore("zchat_group_members_encrypted", mapOf(stateKey to stored))
            val phone = InMemoryChatOwnership(owner = "synthetic-wallet-a", stores = listOf(members))

            assertEquals(ChatClaim.STARTED_EMPTY, phone.ownership.claimFor("synthetic-wallet-b"))
            assertNull("the other wallet's group of the same id holds the list", members.entries[stateKey])

            assertEquals(ChatClaim.RESTORED, phone.ownership.claimFor("synthetic-wallet-a"))
            val back = members.entries[stateKey] as String
            assertEquals(
                "the list did not come back with its wallet's stores",
                listOf(KICK_1),
                AdminCheck.keptChangesOf(back).map { it.getString("id") },
            )
        }

    @Test
    fun `m - the hook does nothing at the end of a pass whose wallet generation changed after it took its number`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            assertKept(p, KICK_1)
            confirm(vm, p)

            val earlierWallet = AdminCheck.beginPass(vm)
            p.walletGeneration = 1L
            val before = p.snapshot()
            p.callLog.clear()
            AdminCheck.endPass(vm, earlierWallet, complete = true)

            assertEquals("a pass of an earlier wallet generation ran the list", before, p.snapshot())
            assertFalse("the hook flushed for a pass of an earlier wallet generation", "flushGroupStores" in names(p))
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
        }

    // ------------------------------------------------------------------ (n)

    @Test
    fun `n - one view model keeps and another confirms - the list is applied once`() =
        runBlocking {
            val p = readyPhone()
            val seams = listOf(HandshakeSeam(Y), HandshakeSeam(Y))
            val keeps = AdminCheck.chatVm(p, seams[0])
            val confirms = AdminCheck.chatVm(p, seams[1])
            deliver(keeps, kick(p), KICK_1)
            assertKept(p, KICK_1)

            confirm(confirms, p)
            p.callLog.clear()
            pass(keeps)

            assertEquals("the other view model's pass did not apply the list", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(emptyList<String>(), keptIds(p))
            val applied = p.snapshot()

            pass(confirms)

            assertEquals("the second view model's pass changed something", applied, p.snapshot())
            assertEquals("the kick was not applied exactly once", listOf<Any?>(2), epochWrites(p))
            assertEquals(0, seams.sumOf { it.proposalRequests.get() })
        }

    @Test
    fun `n - the confirming screen is left - the other view model's pass, begun by the signal alone, applies the list`() =
        runTest {
            val p = readyPhone()
            val newKey = AnnounceDriver.newGroupKey()
            val seams = listOf(HandshakeSeam(Y), HandshakeSeam(Y))
            val settings = AdminCheck.chatVm(p, seams[0]) // the Group settings screen's view model: it confirms
            val chatList = AdminCheck.chatVm(p, seams[1]) // the chat list's view model: it stays
            val settingsCollector = AdminCheck.collectHistory(settings, backgroundScope)
            val chatListCollector = AdminCheck.collectHistory(chatList, backgroundScope)
            try {
                runCurrent()
                deliver(chatList, kick(p, key = newKey), KICK_1)
                assertKept(p, KICK_1)

                // The screen is left while the confirmation writes: its collection and its own scope are cancelled
                // before its pass runs.
                p.onDurableWrite = {
                    settingsCollector.cancel()
                    settings.viewModelScope.cancel()
                }
                AdminCheck.confirmNow(settings, Y, p.admin.publicKey)
                AdminCheck.awaitOwnJobs(settings)
                p.onDurableWrite = null
                assertEquals("fixture: the confirmation's write stood", p.admin.publicKey, p.base.info(GID).creatorPublicKey)
                runCurrent()

                assertEquals("the stored kick was not applied by the pass the signal began", MemberStatus.LEFT, p.base.status(GID, M))
                assertEquals(2, p.base.epochs[GID])
                assertEquals(newKey, p.groupKey(2))
                assertEquals(emptyList<String>(), keptIds(p))
                assertEquals(0, seams.sumOf { it.proposalRequests.get() })
            } finally {
                p.onDurableWrite = null
                // The counter, the per-group numbers and the signal are process-wide: no collector outlives the case.
                settingsCollector.cancelAndJoin()
                chatListCollector.cancelAndJoin()
            }
        }

    // ------------------------------------------------------------------ (o)

    @Test
    fun `o - a pass that began before the confirmation does not run the list and the next one does`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            assertKept(p, KICK_1)

            val running = AdminCheck.beginPass(vm)
            confirm(vm, p)
            val confirmed = p.snapshot()
            AdminCheck.endPass(vm, running, complete = true)

            assertEquals("a pass that began before the confirmation ran the list", confirmed, p.snapshot())

            pass(vm)
            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(emptyList<String>(), keptIds(p))
        }

    @Test
    fun `o - a pass that is not complete does not run the list and the next complete pass does`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            assertKept(p, KICK_1)
            confirm(vm, p)
            val confirmed = p.snapshot()
            p.callLog.clear()

            pass(vm, complete = false)

            assertEquals("a pass that did not read the whole history ran the list", confirmed, p.snapshot())
            assertFalse("a pass with nothing due wrote the announce state", "saveGroupAnnounceState" in names(p))

            pass(vm)
            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(emptyList<String>(), keptIds(p))
        }

    private fun agedEntry(
        p: AdminCheckPhone,
        id: String,
    ): JSONObject =
        AdminCheck.keptEntry(
            id = id,
            kind = "GY",
            epoch = 5,
            payload = ZMSGGroupProtocol.parsePayload(keyChange(p, 5))!!,
            from = Y,
            at = relayTime.toEpochMilli(),
            kept = System.currentTimeMillis() - 91 * DAY,
        )

    @Test
    fun `o - in a group that still waits a complete pass leaves the list alone but for the age limit`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            AdminCheck.putKept(p, listOf(agedEntry(p, AGED)))
            assertKept(p, KICK_1, AGED)
            val fresh = entry(p, KICK_1).toString()

            pass(vm)

            assertEquals("an entry kept 91 days ago did not leave, or the fresh one did", listOf(KICK_1), keptIds(p))
            assertEquals("the pass changed an entry of a group that still waits", fresh, entry(p, KICK_1).toString())
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(1, p.base.epochs[GID])
        }

    @Test
    fun `o - steps 1 and 2 at every pass - an aged entry leaves and a left group's list is dropped after the flush`() =
        runBlocking {
            // Step 1 at a pass that is not complete, in a group that reads OK: the aged entry leaves, the others stay.
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            AdminCheck.putKept(p, listOf(agedEntry(p, AGED)))
            assertKept(p, KICK_1, AGED)
            confirm(vm, p)
            val fresh = entry(p, KICK_1).toString()

            pass(vm, complete = false)

            assertEquals("an entry kept 91 days ago outlived a pass that was not complete", listOf(KICK_1), keptIds(p))
            assertEquals("an entry kept as refused changed at a pass that was not complete", fresh, entry(p, KICK_1).toString())
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))

            // Step 2 at a pass that is not complete: the list of a group the user has left, after the flush only.
            p.base.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(isActive = false))
            p.flushAnswers = false
            pass(vm, complete = false)
            assertEquals("the list of a left group was dropped although the flush answered false", listOf(KICK_1), keptIds(p))

            p.flushAnswers = true
            p.callLog.clear()
            pass(vm, complete = false)
            assertEquals(emptyList<String>(), keptIds(p))
            val flush = names(p).indexOf("flushGroupStores")
            assertTrue("the list was dropped before the flush", flush in 0 until names(p).lastIndexOf("saveGroupAnnounceState"))
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
        }

    @Test
    fun `o - a pass that changes nothing in a list writes nothing`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, kick(p), KICK_1)
            assertKept(p, KICK_1)
            val before = p.snapshot()
            p.callLog.clear()

            pass(vm)
            pass(vm, complete = false)

            assertEquals(before, p.snapshot())
            val writes = names(p).filter { it.startsWith("save") || it.startsWith("set") || it == "flushGroupStores" }
            assertEquals("a pass that found nothing to do wrote to the store", emptyList<String>(), writes)
        }

    // ------------------------------------------------------------------ (p)

    @Test
    fun `p - the key is saved and the group-key store committed before the epoch is set`() =
        runBlocking {
            for (sealed in listOf(false, true)) {
                val p = if (sealed) AdminCheck.plainKeyPhone() else confirmedPhone()
                val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
                val newKey = AnnounceDriver.newGroupKey()
                val memo =
                    if (sealed) {
                        ZMSGGroupProtocol.createGroupKickMessage(GID, M, X_OLD, 2, null, "", plainKey = newKey)
                    } else {
                        kick(p, key = newKey)
                    }
                p.callLog.clear()

                deliver(vm, memo, KICK_1, sender = if (sealed) X_OLD else Y)

                val route = if (sealed) "the sealed route" else "the signed route"
                assertEquals("fixture: the kick was applied ($route)", 2, p.base.epochs[GID])
                val save = names(p).indexOf("saveGroupKey")
                val commit = names(p).indexOf("groupKeyStoreCommitFailed")
                val epoch = names(p).indexOf("setGroupKeyEpoch")
                assertTrue("$route: the group-key store was not committed before the epoch was set (ZC-728)", commit in 0 until epoch)
                assertTrue("$route: the key was not saved before the group-key store was committed", save in 0 until commit)
            }
        }

    @Test
    fun `p - a stored entry whose key could not be saved stays with no count and the run stops, pass after pass`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val keyOfKick = AnnounceDriver.newGroupKey()
            val keyOfChange = AnnounceDriver.newGroupKey()
            deliver(vm, kick(p, M, 2, keyOfKick), KICK_1)
            deliver(vm, keyChange(p, 3, keyOfChange), KEY_3)
            assertKept(p, KICK_1, KEY_3)
            confirm(vm, p)

            p.keyCommitFails = true
            repeat(5) { run ->
                pass(vm)
                assertEquals("run ${run + 1}: the epoch was advanced although the key could not be saved", 1, p.base.epochs[GID])
                assertEquals(
                    "run ${run + 1}: an entry left the list although its key could not be saved",
                    listOf(KICK_1, KEY_3),
                    keptIds(p),
                )
                assertEquals("run ${run + 1}: a refused key-store commit was counted as a failed run", 0, entry(p, KICK_1).optInt("f", 0))
                assertEquals(0, entry(p, KEY_3).optInt("f", 0))
            }
            assertEquals("the run did not stop at the entry that was not saved", null, p.groupKey(3))

            p.keyCommitFails = false
            pass(vm)

            assertEquals("the entries were not applied once the commit was taken", 3, p.base.epochs[GID])
            assertEquals(keyOfKick, p.groupKey(2))
            assertEquals(keyOfChange, p.groupKey(3))
            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(emptyList<String>(), keptIds(p))
        }

    @Test
    fun `p - a copy from the chain whose key could not be saved keeps nothing and the next pass applies it`() =
        runBlocking {
            val p = confirmedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val newKey = AnnounceDriver.newGroupKey()
            val change = keyChange(p, 2, newKey)

            p.keyCommitFails = true
            val refused = AdminCheck.beginPass(vm)
            AdminCheck.receiveInPass(vm, refused, change, AnnounceDriver.txId(7), chainTime, HEIGHT + 7)

            assertEquals("the epoch was set although the group-key store refused its commit (ZC-728)", 1, p.base.epochs[GID])
            assertEquals("a copy from the chain was kept", emptyList<String>(), keptIds(p))
            AdminCheck.endPass(vm, refused, complete = true)
            assertEquals(1, p.base.epochs[GID])

            p.keyCommitFails = false
            pass(vm) { AdminCheck.receiveInPass(vm, it, change, AnnounceDriver.txId(7), chainTime, HEIGHT + 7) }

            assertEquals(2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
        }

    @Test
    fun `p - a live relay key change in a confirmed group whose key could not be saved is kept as accepted`() =
        runBlocking {
            val p = confirmedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val newKey = AnnounceDriver.newGroupKey()

            p.keyCommitFails = true
            deliver(vm, keyChange(p, 2, newKey), KEY_2)

            assertTrue("the relay event is not in the seen list", KEY_2 in p.seenEvents)
            assertEquals("the epoch was set although the group-key store refused its commit (ZC-728)", 1, p.base.epochs[GID])
            assertEquals("the key store does not answer the new key at the new epoch", newKey, p.groupKey(2))
            assertEquals("the list does not hold the change whose key could not be saved", listOf(KEY_2), keptIds(p))
            val kept = entry(p, KEY_2)
            assertTrue("the entry is not marked as accepted", kept.optBoolean("acc", false))
            assertEquals("GY", kept.getString("t"))
            assertEquals(2, kept.getInt("e"))
            assertEquals("an accepted entry's signer chat is not the seal's sender", Y, kept.getString("by"))

            // The next pass ends with the commit taken; it need not be complete, and nothing delivers the event again.
            p.keyCommitFails = false
            pass(vm, complete = false)

            assertEquals("the accepted change was not finished at the end of the next pass", 2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
            assertEquals(emptyList<String>(), keptIds(p))
        }

    @Test
    fun `p - a live relay kick whose key could not be saved removes the member at once and keeps its floor's height`() =
        runBlocking {
            val p = confirmedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            AdminCheck.setScannedTip(vm, TIP_AT_KEEP)
            val newKey = AnnounceDriver.newGroupKey()

            p.keyCommitFails = true
            deliver(vm, kick(p, key = newKey), KICK_1)

            assertEquals("the removal itself was not applied at the first try", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(2, p.base.floors[GID])
            assertEquals(
                "the floor does not count from the height scanned to at the first try",
                TIP_AT_KEEP,
                p.base.kickHeights[GID]?.get(2),
            )
            assertEquals("the key store does not answer the new key at the new epoch", newKey, p.groupKey(2))
            assertEquals("the epoch was set although the group-key store refused its commit (ZC-728)", 1, p.base.epochs[GID])
            assertEquals(listOf(KICK_1), keptIds(p))
            assertTrue(entry(p, KICK_1).optBoolean("acc", false))
            assertEquals(TIP_AT_KEEP, entry(p, KICK_1).getLong("h"))

            AdminCheck.setScannedTip(vm, TIP_LATER)
            p.keyCommitFails = false
            pass(vm, complete = false)

            assertEquals(2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
            assertEquals("the second try moved the floor's height", TIP_AT_KEEP, p.base.kickHeights[GID]?.get(2))
            assertEquals(MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(emptyList<String>(), keptIds(p))
        }

    @Test
    fun `p - a sealed key change in an Open group whose key could not be saved is kept as accepted and applied at the next pass's end`() =
        runBlocking {
            val p = AdminCheck.plainKeyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val newKey = AnnounceDriver.newGroupKey()
            val sealed = ZMSGGroupProtocol.createGroupKeyMessage(GID, X_OLD, 2, "", "", "rotation", plainKey = newKey)

            p.keyCommitFails = true
            deliver(vm, sealed, KEY_2, sender = X_OLD)

            assertEquals("the epoch was set although the group-key store refused its commit (ZC-728)", 1, p.base.epochs[GID])
            assertEquals(listOf(KEY_2), keptIds(p))
            assertTrue(entry(p, KEY_2).optBoolean("acc", false))
            assertEquals(X_OLD, entry(p, KEY_2).getString("by"))

            p.keyCommitFails = false
            pass(vm, complete = false)

            assertEquals(2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
            assertEquals(emptyList<String>(), keptIds(p))
        }

    @Test
    fun `p - when the entry's own write is refused too the run at the end of the next pass still applies it`() =
        runBlocking {
            val p = confirmedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val newKey = AnnounceDriver.newGroupKey()

            p.keyCommitFails = true
            p.announceWritesRefused = true
            deliver(vm, keyChange(p, 2, newKey), KEY_2)

            assertEquals("the epoch was set although the group-key store refused its commit (ZC-728)", 1, p.base.epochs[GID])
            assertEquals("the entry is not in the announce state in memory", listOf(KEY_2), keptIds(p))

            p.keyCommitFails = false
            pass(vm, complete = false)

            assertEquals("the run did not find the entry whose own write was refused", 2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
        }

    @Test
    fun `p - pin - not kept as accepted - a relay copy whose handler threw and one the gate refused in state OK`() =
        runBlocking {
            confirmedPhone().let { p ->
                p.keyCommitFails = true
                assertNotKept("a relay kick whose handler threw", p) { vm ->
                    p.onCall = { if (it == "getGroupMembers") throw IllegalStateException("fixture: the roster cannot be read") }
                    try {
                        deliver(vm, kick(p), KICK_1)
                    } finally {
                        p.onCall = null
                    }
                }
            }
            confirmedPhone().let { p ->
                p.keyCommitFails = true
                val stranger = E2EEncryption.generateKeyPair()
                assertNotKept("a relay key change the gate refused in state OK", p) { vm ->
                    deliver(vm, AdminCheck.keyNaming(p, Y, 2, AnnounceDriver.newGroupKey(), signer = stranger), KEY_2)
                }
            }
        }

    // ------------------------------------------------------------------ (q)

    @Test
    fun `q - the wiring - on the real history flow the hook runs after the loop of a complete pass`() =
        runTest {
            val p = readyPhone().withSecondMember()
            val chainKey = AnnounceDriver.newGroupKey()
            p.history.received(kick(p, M, 2, chainKey), chainTime, n = 6)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val collector = AdminCheck.collectHistory(vm, backgroundScope)
            try {
                runCurrent()
                deliver(vm, kick(p, M2, 3), KICK_2)
                assertKept(p, KICK_2)
                assertEquals(
                    "fixture: the on-chain kick is refused while the admin is not confirmed",
                    MemberStatus.ACTIVE,
                    p.base.status(GID, M),
                )

                AdminCheck.confirmNow(vm, Y, p.admin.publicKey)
                assertEquals(CONFIRMED, AdminCheck.awaitOutcome(vm))
                runCurrent()

                assertEquals("the pass the signal began did not apply the on-chain kick", MemberStatus.LEFT, p.base.status(GID, M))
                assertEquals("the hook did not run the stored kick after the pass's loop", MemberStatus.LEFT, p.base.status(GID, M2))
                assertEquals(chainKey, p.groupKey(2))
                assertEquals(3, p.base.epochs[GID])
                assertEquals(emptyList<String>(), keptIds(p))
            } finally {
                collector.cancelAndJoin()
            }
        }

    @Test
    fun `q - the wiring - a pass over a history scan that is not complete does not run the list and a complete one does`() =
        runTest {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val restoring = AdminCheck.collectHistory(vm, backgroundScope, scanComplete = false)
            try {
                runCurrent()
                deliver(vm, kick(p), KICK_1)
                assertKept(p, KICK_1)
                AdminCheck.confirmNow(vm, Y, p.admin.publicKey)
                assertEquals(CONFIRMED, AdminCheck.awaitOutcome(vm))
                runCurrent()

                assertEquals("a pass made while the history scan was not complete ran the list", MemberStatus.ACTIVE, p.base.status(GID, M))
                assertKept(p, KICK_1)
            } finally {
                restoring.cancelAndJoin()
            }
            val scanned = AdminCheck.collectHistory(vm, backgroundScope)
            try {
                runCurrent()
                assertEquals("the first complete pass did not run the list", MemberStatus.LEFT, p.base.status(GID, M))
                assertEquals(emptyList<String>(), keptIds(p))
            } finally {
                scanned.cancelAndJoin()
            }
        }

    @Test
    fun `q - the wiring - a pass with a memo it could not read does not run the list and the next complete one does`() =
        runTest {
            val p = readyPhone()
            val unreadable = p.history.received(rosterQuery, chainTime, n = 7)
            p.history.unreadable += unreadable.id.txIdString()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val collector = AdminCheck.collectHistory(vm, backgroundScope)
            try {
                runCurrent()
                deliver(vm, kick(p), KICK_1)
                assertKept(p, KICK_1)
                AdminCheck.confirmNow(vm, Y, p.admin.publicKey)
                assertEquals(CONFIRMED, AdminCheck.awaitOutcome(vm))
                runCurrent()

                assertEquals("a pass that could not read a memo ran the list", MemberStatus.ACTIVE, p.base.status(GID, M))
                assertKept(p, KICK_1)

                // The memo reads now, and a new transaction starts the next pass.
                p.history.unreadable.clear()
                p.history.received(rosterQuery, chainTime.plusSeconds(600), n = 9)
                runCurrent()

                assertEquals("the first complete pass did not run the list", MemberStatus.LEFT, p.base.status(GID, M))
                assertEquals(emptyList<String>(), keptIds(p))
            } finally {
                collector.cancelAndJoin()
            }
        }

    @Test
    fun `q - the wiring - on the real history flow kept invites are handed over before the loop`() =
        runTest {
            val p = readyPhone()
            val removalTime = chainTime
            val inviteTime = relayTime
            val key = AnnounceDriver.newGroupKey()
            p.history.received(kick(p, kicked = p.self, key = null), removalTime, n = 6)
            val invite = AnnounceDriver.gi(X_OLD, p.base.sessionWith(Y), key, 3, GID)
            deliver(AdminCheck.chatVm(p, HandshakeSeam(Y)), invite, INVITE, inviteTime)
            assertEquals(
                "the stored list does not hold the re-invite that came over the relays",
                listOf(INVITE),
                AdminCheck.keptChanges(p).filter { it.getString("t") == "GI" }.map { it.getString("id") },
            )

            // The confirmation's write reached the store and the process died before its hand-over.
            writeConfirmedKey(p)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val collector = AdminCheck.collectHistory(vm, backgroundScope)
            try {
                runCurrent()

                assertFalse("the on-chain removal of this phone was not applied by the pass", p.base.info(GID).isActive)
                val offer = p.base.needsJoin[GID]?.let(::JSONObject)
                val reopen = offer?.optJSONObject("reopen")
                assertEquals(
                    "the pass did not hand the kept invite over before its loop: the removal found nothing to offer",
                    INVITE,
                    reopen?.optString("invite_id"),
                )
                assertEquals(key, reopen?.optString("key"))
            } finally {
                collector.cancelAndJoin()
            }
        }

    @Test
    fun `q - pin - source - the pass's number reaches the dispatcher with an on-chain memo`() {
        val loop = AdminCheckSource.structure(AdminCheckSource.chatMember("convertToConversationsInPass"))
        val handOver = Regex("""\bprocessGroupMessageInPass\s*\(""").find(loop) ?: throw AssertionError("the pass hands no group memo over")
        val arguments = loop.substring(handOver.range.last, AdminCheckSource.closing(loop, handOver.range.last)!!)
        assertTrue(
            "the pass does not hand its own number over with an on-chain group memo",
            Regex("""\bhistoryPass\b""").containsMatchIn(arguments),
        )
    }

    // ------------------------------------------------------------------ (r)

    @Test
    fun `r - pin - source - the seen list, the inactive time and the announce state are left as they are`() {
        val invite = AdminCheckSource.structure(AdminCheckSource.chatMember("processGroupInviteLocked"))
        assertEquals(
            "an event is taken out of the seen list somewhere else than in the invite handler",
            mapOf("ChatViewModel.kt" to AdminCheckSource.callsOf(invite, "unmarkNostrEventSeen").size),
            AdminCheckSource.callSites("unmarkNostrEventSeen"),
        )
        // At `0c7a15fc7`: the deferred invite, and ZC-668's kept copy that could not be saved.
        assertEquals(2, AdminCheckSource.callsOf(invite, "unmarkNostrEventSeen").size)

        for ((file, body) in listOf(
            "ChatViewModel" to AdminCheckSource.chatMember("recordInactiveSince"),
            "GroupViewModel" to AdminCheckSource.memberOf("viewmodel/GroupViewModel.kt", "recordInactiveSince"),
        )) {
            assertFalse("$file.recordInactiveSince names the kept list", Regex("""(?i)kept(AdminChange|_admin)""").containsMatchIn(body))
        }

        // A state built from scratch drops the list: only the reader and deleteGroup's tombstone may build one.
        val built =
            AdminCheckSource
                .mainSources()
                .mapValues { (_, code) -> Regex("""(?<!class )\bAnnounceState\(\s*[^)\s]""").findAll(code).count() }
                .filterValues { it > 0 }
        assertEquals(
            "AnnounceState( is built from scratch somewhere else than in the reader and in deleteGroup",
            mapOf("GroupMemberAnnounce.kt" to 1, "ZchatPreferences.kt" to 1),
            built,
        )

        val confirm = AdminCheckSource.structure(AdminCheckSource.chatMember("confirmGroupAdmin"))
        assertEquals(
            "confirmGroupAdmin runs the stored list itself",
            emptyList<Int>(),
            AdminCheckSource.callsOf(confirm, AdminCheckSource.RUN),
        )
        assertFalse("confirmGroupAdmin reads the history for the list", "getTransactions(" in confirm)
    }

    private fun chatMember(name: String): String = AdminCheckSource.structure(AdminCheckSource.memberOf("viewmodel/ChatViewModel.kt", name))

    private fun assertOneCallSite(
        what: String,
        name: String,
        count: Int = 1,
    ) {
        val sites = AdminCheckSource.callSites(name)
        if (sites.isEmpty()) AdminCheck.missingKept("ChatViewModel.$name(…) ($what) is called nowhere")
        assertEquals("$what is not called at exactly $count place(s) of the main sources", mapOf("ChatViewModel.kt" to count), sites)
    }

    @Test
    fun `r - source - the keep step stands inside the group lock of the dispatcher's two admin branches and nowhere else`() {
        assertOneCallSite("the keep step", AdminCheckSource.KEEP, count = 2)
        val anyBranch = Regex("""GroupMessageType\s*\.\s*\w+\s*->""")
        val kickBranch = Regex("""GroupMessageType\s*\.\s*GROUP_KICK\s*->""")
        val dispatcher = AdminCheckSource.structure(AdminCheckSource.chatMemberContaining(kickBranch))
        for (type in listOf("GROUP_KICK", "GROUP_KEY")) {
            val start = Regex("""GroupMessageType\s*\.\s*$type\s*->""").find(dispatcher)!!.range
            val end = anyBranch.find(dispatcher, start.last + 1)?.range?.first ?: dispatcher.length
            val lockAt = dispatcher.indexOf("withGroupLock(", start.last).takeIf { it in start.last until end }
            val lock =
                lockAt?.let { AdminCheckSource.lambdaOf(dispatcher, it) }
                    ?: throw AssertionError("the dispatcher's $type branch has no withGroupLock( block")
            val keep = AdminCheckSource.callsOf(dispatcher.substring(0, end), AdminCheckSource.KEEP).filter { it > start.last }
            assertEquals("the $type branch does not call the keep step exactly once", 1, keep.size)
            assertTrue("in the $type branch the keep step stands outside the withGroupLock( block", keep.single() in lock)
        }
        assertForbiddenFree(AdminCheckSource.KEEP)
    }

    @Test
    fun `r - source - the run is called from the end of the history pass alone`() {
        assertOneCallSite("the run of the stored changes", AdminCheckSource.RUN)
        assertOneCallSite("the hook at the end of a history pass", AdminCheckSource.HOOK)
        assertEquals(
            "the run is not called from the hook",
            1,
            AdminCheckSource.callsOf(chatMember(AdminCheckSource.HOOK), AdminCheckSource.RUN).size,
        )

        val pass = AdminCheckSource.structure(AdminCheckSource.chatMember("convertToConversationsInPass"))
        val hook =
            AdminCheckSource.callsOf(pass, AdminCheckSource.HOOK).singleOrNull()
                ?: throw AssertionError("the hook is not called from the history pass (convertToConversationsInPass)")
        assertTrue(
            "the hook is called before the pass's loop hands the group memos over",
            hook > pass.lastIndexOf("processGroupMessageInPass("),
        )
        val arguments = pass.substring(hook, AdminCheckSource.closing(pass, pass.indexOf('(', hook))!!)
        assertTrue(
            "the hook is not told the pass's own test of a whole history (historyScanComplete && memoReadFailures == 0)",
            Regex("""historyScanComplete\s*&&\s*memoReadFailures\s*==\s*0""").containsMatchIn(arguments),
        )
        assertForbiddenFree(AdminCheckSource.RUN)
        assertForbiddenFree(AdminCheckSource.HOOK)
    }

    @Test
    fun `r - source - the hand-over is called from the confirmation, outside the wallet lock, and from the pass before its loop`() {
        assertOneCallSite("the hand-over of kept invites", AdminCheckSource.HAND_OVER, count = 2)
        assertOneCallSite("the hand-over before a pass's loop", AdminCheckSource.HAND_OVER_BEFORE_LOOP)

        val confirm = AdminCheckSource.structure(AdminCheckSource.chatMember("confirmGroupAdmin"))
        val call =
            AdminCheckSource.callsOf(confirm, AdminCheckSource.HAND_OVER).singleOrNull()
                ?: throw AssertionError("confirmGroupAdmin does not hand the kept invites over exactly once")
        val groupLock =
            Regex("""withGroupLock\s*\(""").find(confirm)?.let { AdminCheckSource.lambdaOf(confirm, it.range.first) }
                ?: throw AssertionError("confirmGroupAdmin has no withGroupLock( block")
        val walletLock =
            Regex("""synchronized\s*\(\s*ZchatPreferences\s*\.\s*walletScopeLock\s*\)""")
                .find(confirm)
                ?.let { AdminCheckSource.lambdaOf(confirm, it.range.first) }
                ?: throw AssertionError("confirmGroupAdmin has no synchronized(ZchatPreferences.walletScopeLock) block")
        assertTrue("the hand-over stands outside confirmGroupAdmin's withGroupLock( block", call in groupLock)
        assertFalse("the hand-over stands inside the synchronized(ZchatPreferences.walletScopeLock) block", call in walletLock)
        assertTrue("the hand-over comes before the confirmation's write", call > walletLock.last)

        assertEquals(
            "the pass's hand-over does not go through the one hand-over function",
            1,
            AdminCheckSource.callsOf(chatMember(AdminCheckSource.HAND_OVER_BEFORE_LOOP), AdminCheckSource.HAND_OVER).size,
        )
        val passes =
            listOf("convertToConversations", "convertToConversationsInPass")
                .map { AdminCheckSource.structure(AdminCheckSource.chatMember(it)) }
        val calls = passes.map { AdminCheckSource.callsOf(it, AdminCheckSource.HAND_OVER_BEFORE_LOOP) }
        assertEquals("the hand-over before the loop is not called from the history pass", 1, calls.sumOf { it.size })
        calls[1].singleOrNull()?.let { inPass ->
            assertTrue("the hand-over is called after the pass's loop began", inPass < passes[1].indexOf("processGroupMessageInPass("))
        }
        assertForbiddenFree(AdminCheckSource.HAND_OVER)
        assertForbiddenFree(AdminCheckSource.HAND_OVER_BEFORE_LOOP)
    }

    /** The keep, the run and the hand-over send nothing, pay nothing, touch no seen list and read no history. */
    private fun assertForbiddenFree(name: String) {
        val body = chatMember(name)
        val forbidden =
            listOf(
                "sendGroupMemoWithRetry",
                "trySendGroupMemoOverNostr",
                "createChunkedMessageProposal",
                "markNostrEventSeen",
                "unmarkNostrEventSeen",
                "getMemos(",
                "getTransactions(",
            )
        assertEquals("$name contains what it must not", emptyList<String>(), forbidden.filter { it in body })
    }

    private companion object {
        const val M2 = "usyntheticki25memberm0000002"
        const val KICK_1 = "synthetic-ki25-relay-kick-1"
        const val KICK_2 = "synthetic-ki25-relay-kick-2"
        const val KEY_2 = "synthetic-ki25-relay-key-epoch-2"
        const val KEY_3 = "synthetic-ki25-relay-key-epoch-3"
        const val KEY_4 = "synthetic-ki25-relay-key-epoch-4"
        const val AGED = "synthetic-ki25-relay-key-kept-91-days-ago"
        const val INVITE = "synthetic-ki25-relay-reinvite"
        const val DAY = 86_400_000L

        /** The height the phone had scanned to when a change was kept, and a later one. */
        const val TIP_AT_KEEP = HEIGHT + 50
        const val TIP_LATER = HEIGHT + 500
    }
}
