package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.PendingReinvite
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * KI-25 pair 1b, ZC-704 (KI-25-SPEC 3.6b R19, "a re-invite is kept too"; section 4.1b class 24) — a re-invite that
 * reaches a phone over the relays while its group cannot confirm its admin is kept, handed to ZC-294's slot at the
 * confirmation, and offered as Join when a removal of this phone is applied.
 *
 * The phone is [AdminCheck.driftedPhone]: the admin's chat is Y, and no chat sits at the stored admin address. Every
 * relay delivery is made as the reader makes it ([AdminCheck.relayDeliver]: the event is marked seen first), and
 * every pass goes through the seam ([AdminCheck.beginPass], [AdminCheck.receiveInPass], [AdminCheck.endPass]). The
 * stored list is read from the store's JSON ([AdminCheck.keptChanges], kind `GI`).
 *
 * At `0c7a15fc7` (pair 1's fix) such an invite is recorded as seen and not kept: (a) to (c), (e) to (h) and (j) fail
 * where the list should hold it, (i) fails for want of the keep step, and (d) holds there too and pins.
 *
 * Nothing is paid by any case, and Join is never tapped. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefusedRelayReinviteIsKeptForTheConfirmationTest {
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
    private val removalTime: Instant = Instant.ofEpochMilli(T + 60_000)
    private val inviteTime: Instant = Instant.ofEpochMilli(T + 120_000)
    private val rosterQuery = ZMSGGroupProtocol.createGroupRosterQueryMessage(GID)

    /** A drifted phone ready for the confirmation: the creating invite from Y is in the history and Y is verified. */
    private fun readyPhone(): AdminCheckPhone =
        AdminCheck.driftedPhone().also { p ->
            p.creatingInvite(p.base.sessionWith(Y), created)
            p.verified += Y
            p.base.nostrKeys[Y] = Y_NOSTR
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

    /** A compact re-invite with `k2`: [key] at [keyEpoch] wrapped under this phone's session with [wrappedFor]. */
    private fun invite(
        p: AdminCheckPhone,
        keyEpoch: Int = 3,
        key: String = AnnounceDriver.newGroupKey(),
        wrappedFor: String = Y,
    ): String = AnnounceDriver.gi(X_OLD, p.base.sessionWith(wrappedFor), key, keyEpoch, GID)

    /** The admin's removal of this phone to epoch 2, signed with the admin's key (it carries no key for us). */
    private fun removalOfThisPhone(p: AdminCheckPhone): String =
        AdminCheck.kickNaming(p, naming = Y, kicked = p.self, newEpoch = 2, newKey = null)

    private fun deliver(
        vm: ChatViewModel,
        memo: String,
        eventId: String,
        time: Instant = inviteTime,
        sender: String = Y,
    ) = AdminCheck.relayDeliver(vm, memo, sender, eventId, time)

    private fun keptInvites(p: AdminCheckPhone): List<JSONObject> = AdminCheck.keptChanges(p).filter { it.getString("t") == "GI" }

    private fun keptInviteIds(p: AdminCheckPhone): List<String> = keptInvites(p).map { it.getString("id") }

    private fun slot(p: AdminCheckPhone): String? {
        val state = p.base.state(GID)
        return state.pendingReinvite?.inviteId
    }

    private fun completePass(vm: ChatViewModel) {
        val pass = AdminCheck.beginPass(vm)
        AdminCheck.endPass(vm, pass, complete = true)
    }

    private fun joinOffer(p: AdminCheckPhone): JSONObject? = p.base.needsJoin[GID]?.let(::JSONObject)

    private fun assertJoinWaitsFor(
        p: AdminCheckPhone,
        inviteId: String,
        keyEpoch: Int,
        key: String,
    ) {
        assertFalse("the removal of this phone did not close the group", p.base.info(GID).isActive)
        assertTrue("the group is not marked as one this phone was removed from", p.base.info(GID).removedByAdmin)
        val offer = joinOffer(p) ?: throw AssertionError("no Join waits for the kept re-invite: the admin has to invite again")
        assertEquals("the Join record does not name the admin's chat", Y, offer.optString("inviter"))
        val reopen = offer.optJSONObject("reopen")
        assertEquals("the Join record does not name the kept invite", inviteId, reopen?.optString("invite_id"))
        assertEquals("the Join record does not carry the invite's key epoch", keyEpoch, reopen?.optInt("key_epoch"))
        assertEquals("the Join record does not carry the invite's key", key, reopen?.optString("key"))
        assertEquals("offering Join installed the invite's key epoch", 1, p.base.epochs[GID])
        assertNull("offering Join installed the invite's key", p.groupKey(keyEpoch))
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - a removal of this phone and the re-invite that answers it are kept, handed over, and Join is offered`() =
        runBlocking {
            val p = readyPhone()
            AdminCheck.relayRecorder(published)
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            ownSendScope = AdminCheck.sendScopeOn(StandardTestDispatcher(scheduler))
            val key = AnnounceDriver.newGroupKey()

            val inviteMemo = invite(p, 3, key)

            deliver(vm, removalOfThisPhone(p), REMOVAL, removalTime)
            deliver(vm, inviteMemo, INVITE_1)

            assertTrue("a removal the group could not confirm closed the group", p.base.info(GID).isActive)
            assertEquals(
                "the stored list does not hold the removal and the re-invite that came over the relays",
                setOf(REMOVAL, INVITE_1),
                AdminCheck.keptChanges(p).map { it.getString("id") }.toSet(),
            )
            val keptInvite = keptInvites(p).single()
            assertEquals(3, keptInvite.getInt("e"))
            assertEquals(Y, keptInvite.getString("from"))
            assertEquals(Y, keptInvite.getString("by"))
            assertEquals(inviteTime.toEpochMilli(), keptInvite.getLong("at"))
            assertEquals(
                "the invite's payload was not kept as it came",
                ZMSGGroupProtocol.parsePayload(inviteMemo),
                keptInvite.getString("p"),
            )
            assertFalse("a kept invite records a height", keptInvite.has("h"))
            assertNull("ZC-294 kept an invite of a group that cannot confirm its admin", slot(p))
            assertTrue("the invite's id was not remembered as seen while active", INVITE_1 in p.base.state(GID).seenActiveInviteIds)

            confirm(vm, p)
            scheduler.advanceUntilIdle()

            assertEquals("the confirmation did not hand the kept invite to ZC-294's slot", INVITE_1, slot(p))
            assertEquals("the list still holds an invite after the hand-over", emptyList<String>(), keptInviteIds(p))
            assertTrue("the confirmation applied the removal itself", p.base.info(GID).isActive)
            assertNull(joinOffer(p))

            completePass(vm)
            scheduler.advanceUntilIdle()

            assertJoinWaitsFor(p, INVITE_1, 3, key)
            assertEquals(
                "the stored list is not empty after the pass",
                emptyList<String>(),
                AdminCheck.keptChanges(p).map { it.getString("id") },
            )
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
            assertEquals("something was published besides the one roster query", listOf(rosterQuery to Y_NOSTR), published.toList())
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - of the kept invites of the admin's chat and of another member only the confirmed chat's reaches the slot`() =
        runBlocking {
            val p = readyPhone()
            p.base.link(M)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            deliver(vm, invite(p, 3), INVITE_1)
            deliver(vm, invite(p, 5, wrappedFor = M), INVITE_2, sender = M)

            assertEquals("the invites of two finished chats are not both kept", setOf(INVITE_1, INVITE_2), keptInviteIds(p).toSet())
            assertEquals(setOf(Y, M), keptInvites(p).map { it.getString("by") }.toSet())

            confirm(vm, p)

            assertEquals("another member's invite with a higher key epoch took the admin's place", INVITE_1, slot(p))
            assertEquals("the other chat's kept invite did not leave the list", emptyList<String>(), keptInviteIds(p))
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - one kept invite per chat - a lower key epoch never replaces it, a higher one does, then the later time`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            deliver(vm, invite(p, 3), INVITE_1)
            assertEquals("the stored list does not hold the re-invite that came over the relays", listOf(INVITE_1), keptInviteIds(p))

            deliver(vm, invite(p, 2), INVITE_2, time = inviteTime.plusSeconds(10))
            assertEquals("a later invite of a lower key epoch replaced the kept one", listOf(INVITE_1), keptInviteIds(p))

            deliver(vm, invite(p, 4), INVITE_3, time = inviteTime.minusSeconds(10))
            assertEquals("an invite of a higher key epoch did not replace the kept one", listOf(INVITE_3), keptInviteIds(p))

            deliver(vm, invite(p, 4), INVITE_4, time = inviteTime.plusSeconds(20))
            assertEquals("at one key epoch the later invite did not win", listOf(INVITE_4), keptInviteIds(p))

            deliver(vm, invite(p, 4), "synthetic-ki25-reinvite-5", time = inviteTime.plusSeconds(5))
            assertEquals("at one key epoch an earlier invite replaced the kept one", listOf(INVITE_4), keptInviteIds(p))
            assertNull(slot(p))
        }

    // ------------------------------------------------------------------ (d)

    private suspend fun assertNotKept(
        what: String,
        p: AdminCheckPhone = readyPhone(),
        deliver: (ChatViewModel, AdminCheckPhone) -> Unit,
    ) {
        val seam = HandshakeSeam(Y)
        val vm = AdminCheck.chatVm(p, seam)
        deliver(vm, p)
        assertEquals("$what was kept", emptyList<String>(), keptInviteIds(p))
        assertNull("$what reached ZC-294's slot", slot(p))
        assertNull("$what offered Join", joinOffer(p))
        assertEquals(0, seam.proposalRequests.get())
    }

    @Test
    fun `d - not kept - a key that does not unwrap under its sealer's session, and a sealer still waiting for Accept`() =
        runBlocking {
            assertNotKept("an invite whose k2 is wrapped under another chat's session", readyPhone().also { it.base.link(M) }) { vm, p ->
                deliver(vm, invite(p, 3, wrappedFor = M), INVITE_1)
            }
            assertNotKept(
                "an invite sealed by a chat request still waiting for Accept",
                readyPhone().also { it.base.awaitingAccept[Y] = "synthetic-waiting-kex" },
            ) { vm, p -> deliver(vm, invite(p, 3), INVITE_1) }
        }

    @Test
    fun `d - not kept - a plaintext-key invite, an enc_key invite and a payload of 2,049 characters`() =
        runBlocking {
            fun form(vararg fields: Pair<String, Any>): String =
                "ZMSG:3.0:GROUP:GI:$GID:" +
                    JSONObject()
                        .put("name", AdminCheck.GROUP_NAME)
                        .put("inviter", X_OLD)
                        .put("key_epoch", 3)
                        .apply { fields.forEach { (k, v) -> put(k, v) } }
                        .toString()
            assertNotKept("a plaintext-key invite outside an Open group") { vm, _ ->
                deliver(vm, form("group_key" to AnnounceDriver.newGroupKey()), INVITE_1)
            }
            assertNotKept("an enc_key invite") { vm, _ -> deliver(vm, form("enc_key" to "c3ludGhldGljLWVuYy1rZXk="), INVITE_1) }
            assertNotKept("an invite whose payload has 2,049 characters") { vm, p ->
                val k2 = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), p.base.sessionWith(Y))
                val bare = ZMSGGroupProtocol.parsePayload(form("k2" to k2, "name" to ""))!!.length
                val memo = form("k2" to k2, "name" to "n".repeat(2_049 - bare))
                assertEquals("fixture: the payload has 2,049 characters", 2_049, ZMSGGroupProtocol.parsePayload(memo)!!.length)
                deliver(vm, memo, INVITE_1)
            }
        }

    @Test
    fun `d - not kept - a copy from the chain and an invite to a group this phone already left`() =
        runBlocking {
            assertNotKept("an invite from the chain") { vm, p ->
                val pass = AdminCheck.beginPass(vm)
                AdminCheck.receiveInPass(vm, pass, invite(p, 3), AnnounceDriver.txId(8), inviteTime, HEIGHT + 8)
                AdminCheck.endPassIfBuilt(vm, pass, complete = true)
            }
            val left =
                readyPhone().also { p ->
                    p.base.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(isActive = false))
                    p.base.states[GID] = p.base.state(GID).copy(inactiveSince = removalTime.toEpochMilli())
                }
            assertNotKept("an invite to a group this phone already left", left) { vm, p -> deliver(vm, invite(p, 3), INVITE_1) }
        }

    @Test
    fun `d - not kept - in a group that reads OK ZC-294 keeps the invite itself`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            deliver(vm, invite(p, 3), INVITE_1)

            assertEquals("fixture: ZC-294 keeps the confirmed admin's invite in its own slot", INVITE_1, slot(p))
            assertEquals("an invite ZC-294 kept itself went into the list as well", emptyList<String>(), keptInviteIds(p))
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - with no removal the handed-over invite waits in the slot, and a removal that arrives later offers it`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val key = AnnounceDriver.newGroupKey()
            deliver(vm, invite(p, 3, key), INVITE_1)
            assertEquals("the stored list does not hold the re-invite that came over the relays", listOf(INVITE_1), keptInviteIds(p))

            confirm(vm, p)
            completePass(vm)

            assertEquals("the confirmed chat's invite is not in ZC-294's slot", INVITE_1, slot(p))
            assertNull("Join was offered although no removal of this phone was applied", joinOffer(p))
            assertTrue(p.base.info(GID).isActive)

            // The removal the invite answers arrives now; it was sent before the invite.
            deliver(vm, removalOfThisPhone(p), REMOVAL, removalTime)

            assertJoinWaitsFor(p, INVITE_1, 3, key)
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (f)

    @Test
    fun `f - the removal came on the chain and the re-invite over the relays - Join is offered in that pass`() =
        runBlocking {
            val p = readyPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val key = AnnounceDriver.newGroupKey()
            deliver(vm, invite(p, 3, key), INVITE_1)
            assertEquals("the stored list does not hold the re-invite that came over the relays", listOf(INVITE_1), keptInviteIds(p))

            confirm(vm, p)
            val pass = AdminCheck.beginPass(vm)
            AdminCheck.receiveInPass(vm, pass, removalOfThisPhone(p), AnnounceDriver.txId(6), removalTime, HEIGHT + 6)

            assertJoinWaitsFor(p, INVITE_1, 3, key)
            AdminCheck.endPass(vm, pass, complete = true)
            assertJoinWaitsFor(p, INVITE_1, 3, key)
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (g)

    @Test
    fun `g - after a kill before the hand-over the next pass hands the invite over, applies the removal and offers Join once`() =
        runBlocking {
            val p = readyPhone()
            val first = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val key = AnnounceDriver.newGroupKey()
            deliver(first, removalOfThisPhone(p), REMOVAL, removalTime)
            deliver(first, invite(p, 3, key), INVITE_1)
            assertEquals(
                "the stored list does not hold the removal and the re-invite",
                setOf(REMOVAL, INVITE_1),
                AdminCheck.keptChanges(p).map { it.getString("id") }.toSet(),
            )

            // The confirmation's write reached the store and the process died before the hand-over: a new view model,
            // and no number is recorded for the group.
            assertTrue(
                p.prefs.saveGroupInfosDurably(
                    mapOf(GID to ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(creatorPublicKey = p.admin.publicKey))),
                ),
            )
            AdminCheck.forgetConfirmedPasses()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)

            fun offers() = p.callLog.count { it.first == "setGroupInviteNeedsJoin" && it.second.getOrNull(1) != null }

            val pass = AdminCheck.beginPass(vm)
            assertEquals("the pass did not hand the kept invite over before its loop", INVITE_1, slot(p))
            assertEquals(emptyList<String>(), keptInviteIds(p))
            assertTrue("the hand-over applied the removal", p.base.info(GID).isActive)
            AdminCheck.endPass(vm, pass, complete = true)

            assertJoinWaitsFor(p, INVITE_1, 3, key)
            assertEquals("Join was not offered exactly once", 1, offers())
            assertEquals(emptyList<String>(), AdminCheck.keptChanges(p).map { it.getString("id") })

            val offered = p.base.needsJoin[GID]
            completePass(vm)
            assertEquals("a further pass offered Join again", 1, offers())
            assertEquals(offered, p.base.needsJoin[GID])
            assertFalse(p.base.info(GID).isActive)
            assertEquals(0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (h)

    @Test
    fun `h - an invite in the slot that no longer passes the creator test does not count and the kept invite takes the slot`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            // A plaintext-key invite sealed by the stored admin address, kept by ZC-294 while the group had no creator
            // key; its key epoch is higher than the kept invite's.
            val plain =
                "ZMSG:3.0:GROUP:GI:$GID:" +
                    JSONObject()
                        .put("name", AdminCheck.GROUP_NAME)
                        .put("inviter", X_OLD)
                        .put("key_epoch", 9)
                        .put("group_key", AnnounceDriver.newGroupKey())
            deliver(vm, plain, INVITE_2, time = inviteTime.plusSeconds(30), sender = X_OLD)
            assertEquals("fixture: ZC-294 kept the plaintext-key invite while the group had no creator key", INVITE_2, slot(p))
            deliver(vm, invite(p, 3), INVITE_1)
            assertEquals("the stored list does not hold the kept invite", listOf(INVITE_1), keptInviteIds(p))

            confirm(vm, p)

            assertEquals("an invite that fails the creator test after the confirmation kept the slot", INVITE_1, slot(p))
            assertEquals(emptyList<String>(), keptInviteIds(p))
        }

    @Test
    fun `h - an invite of the confirmed chat with a higher key epoch in the slot is not replaced by the kept one`() =
        runBlocking {
            val p = readyPhone()
            val first = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(first, invite(p, 3), INVITE_1)
            assertEquals("the stored list does not hold the kept invite", listOf(INVITE_1), keptInviteIds(p))

            // After the confirmation's write: the slot holds the confirmed chat's invite of a higher key epoch.
            assertTrue(
                p.prefs.saveGroupInfosDurably(
                    mapOf(GID to ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(creatorPublicKey = p.admin.publicKey))),
                ),
            )
            val higherPayload = ZMSGGroupProtocol.parsePayload(invite(p, 5))!!
            val higher = PendingReinvite(INVITE_2, inviteTime.minusSeconds(30).toEpochMilli(), higherPayload, Y)
            p.base.states[GID] = p.base.state(GID).copy(pendingReinvite = higher)
            AdminCheck.forgetConfirmedPasses()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            val pass = AdminCheck.beginPass(vm)
            AdminCheck.endPass(vm, pass, complete = true)

            assertEquals("the kept invite replaced one of a higher key epoch in the slot", higher, p.base.state(GID).pendingReinvite)
            assertEquals("the kept invite did not leave the list", emptyList<String>(), keptInviteIds(p))
        }

    // ------------------------------------------------------------------ (i)

    @Test
    fun `i - source - the invite is kept at one place, inside the branch's own write, and nothing here taps Join`() {
        val keep = AdminCheckSource.KEEP_INVITE
        val sites = AdminCheckSource.callSites(keep)
        if (sites.isEmpty()) AdminCheck.missingKept("ChatViewModel.$keep(…) (the keep of a relay re-invite) is called nowhere")
        assertEquals("the keep step for an invite is not called at exactly one place", mapOf("ChatViewModel.kt" to 1), sites)

        val branch = AdminCheckSource.structure(AdminCheckSource.chatMember("processGroupInviteLocked"))
        val call =
            AdminCheckSource.callsOf(branch, keep).singleOrNull()
                ?: throw AssertionError("the keep step for an invite is not called in processGroupInviteLocked")
        val existingGroup = branch.indexOf("existingInfoJson != null")
        // U9 nit (QA2 P3): processGroupInviteLocked returns Unit now (no dead reply), so the branch ends at its bare `return`.
        val newGroup = branch.indexOf("return\n", existingGroup)
        assertTrue("the keep step for an invite stands outside the existing-group branch", call in existingGroup..newGroup)
        val writes =
            Regex("""\bwriteForInviteWallet\s*\(""")
                .findAll(branch)
                .mapNotNull { AdminCheckSource.lambdaOf(branch, it.range.first) }
                .toList()
        assertTrue(
            "the keep step for an invite stands outside the write the branch makes through writeForInviteWallet(",
            writes.any { call in it },
        )

        for (name in listOf(AdminCheckSource.HAND_OVER, AdminCheckSource.HAND_OVER_BEFORE_LOOP, AdminCheckSource.RUN)) {
            val body = AdminCheckSource.memberOf("viewmodel/ChatViewModel.kt", name)
            for (join in listOf("joinGroupInvite(", "offerGroupReopen(")) {
                assertFalse("$name calls $join: Join is the user's tap, and the removal's own code makes the offer", join in body)
            }
        }
    }

    // ------------------------------------------------------------------ (j)

    @Test
    fun `j - after the hand-over a later invite of a lower key epoch does not replace the handed-over one`() =
        runBlocking {
            val p = readyPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            deliver(vm, invite(p, 3), INVITE_1)
            assertEquals("the stored list does not hold the kept invite", listOf(INVITE_1), keptInviteIds(p))

            confirm(vm, p)
            assertEquals("the confirmation did not hand the kept invite to ZC-294's slot", INVITE_1, slot(p))

            // It arrives through ZC-294's own writer: the group reads OK now.
            deliver(vm, invite(p, 2), INVITE_2, time = inviteTime.plusSeconds(60))

            assertEquals("a later-timed invite of a lower key epoch replaced the handed-over one (ZC-668's rule)", INVITE_1, slot(p))
            assertEquals(emptyList<String>(), keptInviteIds(p))
        }

    private companion object {
        const val REMOVAL = "synthetic-ki25-relay-removal-of-this-phone"
        const val INVITE_1 = "synthetic-ki25-reinvite-1"
        const val INVITE_2 = "synthetic-ki25-reinvite-2"
        const val INVITE_3 = "synthetic-ki25-reinvite-3"
        const val INVITE_4 = "synthetic-ki25-reinvite-4"
    }
}
