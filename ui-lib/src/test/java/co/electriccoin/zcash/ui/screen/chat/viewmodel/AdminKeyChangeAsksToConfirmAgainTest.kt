package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminNotice
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PendingReinvite
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * KI-25 pair 2, ZC-572 (KI-25-SPEC 3.6b R14, R7 source 2; section 4.2 class 12) — a group with a stored creator key
 * `K_old` whose admin's chat, at the stored creator address, now holds another key `K_new` (the admin reset encryption
 * and this phone took the new key). The chat's safety number is not compared yet.
 *
 * - (a) the group reads ORPHANED; the group chat shows the check line; the card and the confirm dialog offer the chat
 *   at the creator address, source ADDRESS. A finished chat that sits only at the alias target of the creator address
 *   is not offered.
 * - (b) a removal signed with `K_new` changes nothing.
 * - (c) Confirm is refused while the safety number is not compared; after the mark it is `Confirmed` and the stored
 *   creator key is replaced by `K_new`; the same removal on the next pass applies; one signed with `K_old` is refused.
 * - (d) the same key stored again (after this phone's own Reset): OK by itself, with no step and no write.
 * - (e) in state OK a confirmation is `NOT_NEEDED`.
 * - (f) through the real Reset and the real accept of a new key: [AdminKeyChangeThroughTheRealPathsTest].
 * - (g) R19 in ORPHANED: a relay removal signed with `K_new` and sealed by the chat at the creator address is kept and
 *   applied by the first complete pass after the confirmation of `K_new`; with such a change kept, `K_old` stored
 *   again turns the group OK by itself, the next complete pass refuses the change, it leaves the list and M stays;
 *   and ZC-294's slot holding a re-invite kept against `K_old` gives way, after the confirmation of `K_new`, to the
 *   re-invite the `K_new` chat sent.
 *
 * At the base (pairs 1 and 1b) the group reads UNKNOWN, there is no second source, and Confirm is
 * `NOT_THE_RECORDED_CHAT`. Nothing is paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminKeyChangeAsksToConfirmAgainTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

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
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    private val kickTime: Instant = Instant.ofEpochMilli(T + 60_000)
    private val relayTime: Instant = Instant.ofEpochMilli(T + 120_000)

    /** The phone of this class: [p], the admin's old key [old] (stored as the group's creator key) and new key [new]. */
    private class Orphaned(
        val p: AdminCheckPhone,
        val old: E2EKeyPair,
        val new: E2EKeyPair,
    ) {
        private val base: AnnouncePhone get() = p.base

        fun record(): String = base.infos.getValue(GID)

        fun storedKey(): String? = base.info(GID).creatorPublicKey

        fun creatorAddress(): String = base.info(GID).creatorAddress
    }

    /**
     * A drifted phone whose group record stores [Orphaned.old] as the creator key, and whose chat at the creator address
     * [X_OLD] now holds [Orphaned.new] (our own key pair for it stays). The chat at [Y] (another contact's) stays.
     */
    private fun orphaned(): Orphaned {
        val p = AdminCheck.driftedPhone()
        val old = E2EEncryption.generateKeyPair()
        val new = p.base.link(X_OLD)
        p.base.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(creatorPublicKey = old.publicKey))
        p.base.nostrKeys[X_OLD] = X_NOSTR
        return Orphaned(p, old, new)
    }

    private fun kick(
        o: Orphaned,
        signer: E2EKeyPair,
        newEpoch: Int = 2,
        newKey: String? = AnnounceDriver.newGroupKey(),
    ): String =
        AdminCheck.kickNaming(o.p, naming = X_OLD, kicked = M, newEpoch = newEpoch, newKey = newKey, signer = signer, wrapFor = X_OLD)

    private fun onChain(
        vm: ChatViewModel,
        memo: String,
        n: Int = 6,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(n), minedHeight = HEIGHT + n)

    private fun completePass(vm: ChatViewModel) {
        val pass = AdminCheck.beginPass(vm)
        AdminCheck.endPass(vm, pass, complete = true)
    }

    private fun keptIds(p: AdminCheckPhone): List<String> = AdminCheck.keptChanges(p).map { it.getString("id") }

    private suspend fun cardCandidate(p: AdminCheckPhone): Any? {
        val f = GroupPaidOnceFixture()
        f.scheduler = scheduler
        val vm = AdminCheck.groupVm(p, f)
        val candidate = AdminCheck.loadCandidate(vm, f)
        assertEquals("looking for the candidate proposed a payment", 0, f.seam.proposalRequests.get())
        return candidate
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - the group reads ORPHANED, shows the check line and offers the chat at the creator address`() =
        runBlocking {
            val o = orphaned()
            val vm = AdminCheck.chatVm(o.p, HandshakeSeam(X_OLD))

            assertEquals("a stored creator key that no chat holds does not read ORPHANED", "ORPHANED", AdminCheck.keyState(vm))
            assertEquals(GroupAdminNotice.CHECK_LINE, GroupAdminCheck.notice(o.p.prefs, emptyList(), GID, newestReceivedUnreadable = false))

            val card = cardCandidate(o.p) ?: throw AssertionError("the card offers no chat in ORPHANED")
            assertEquals("the card does not offer the chat at the creator address", X_OLD, AdminCheck.property(card, "address"))
            val source = AdminCheck.property(card, "source").toString()
            assertEquals("the candidate's source is not the admin's address", "ADDRESS", source)

            val preview = AdminCheck.loadPreview(vm) ?: throw AssertionError("the confirm dialog has no chat to show in ORPHANED")
            assertEquals(X_OLD, AdminCheck.property(preview, "address"))
            assertEquals("the dialog does not show the new key", o.new.publicKey, AdminCheck.property(preview, "contactKey"))
            assertEquals("ADDRESS", AdminCheck.property(preview, "source").toString())
        }

    @Test
    fun `a - a finished chat only at the alias target of the creator address is not offered`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.base.infos[GID] =
                ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(creatorPublicKey = E2EEncryption.generateKeyPair().publicKey))
            p.base.aliases[X_OLD] = Y
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            assertEquals("ORPHANED", AdminCheck.keyState(vm))
            assertNull("a chat at the alias target of the creator address was offered", cardCandidate(p))
            assertNull(AdminCheck.loadPreview(vm))
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - a removal signed with the new key changes nothing before a confirmation`() =
        runBlocking {
            val o = orphaned()
            val seam = HandshakeSeam(X_OLD)
            val vm = AdminCheck.chatVm(o.p, seam)
            val before = o.p.snapshot()

            repeat(2) { onChain(vm, kick(o, o.new)) }

            assertEquals("a removal signed with a key the group does not trust changed the group", before, o.p.snapshot())
            assertEquals(MemberStatus.ACTIVE, o.p.base.status(GID, M))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - Confirm after the safety number replaces the stored key, the new key's removal applies and the old key's is refused`() =
        runBlocking {
            val o = orphaned()
            val seam = HandshakeSeam(X_OLD)
            val vm = AdminCheck.chatVm(o.p, seam)
            val newKey = AnnounceDriver.newGroupKey()
            val removal = kick(o, o.new, newKey = newKey)

            val early = AdminCheck.confirm(vm, X_OLD, o.new.publicKey)
            assertEquals("a confirmation without a compared safety number", "NOT_VERIFIED", early)
            assertEquals("the refusal replaced the stored key", o.old.publicKey, o.storedKey())

            o.p.verified += X_OLD
            val confirmed = AdminCheck.confirm(vm, X_OLD, o.new.publicKey)
            assertEquals("the chat at the creator address could not be confirmed", CONFIRMED, confirmed)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("the confirmation did not replace the stored creator key", o.new.publicKey, o.storedKey())
            assertEquals("the confirmation changed the creator address", X_OLD, o.creatorAddress())
            assertEquals("OK", AdminCheck.keyState(vm))

            onChain(vm, removal)
            assertEquals("the removal signed with the confirmed key was not applied", MemberStatus.LEFT, o.p.base.status(GID, M))
            assertEquals(newKey, o.p.groupKey(2))

            val byOldKey = kick(o, o.old, newEpoch = 3)
            onChain(vm, byOldKey, n = 7)
            assertEquals("a removal signed with the replaced key was applied", 2, o.p.base.epochs[GID])
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - the same key stored again turns the group OK by itself, with no step and no write`() =
        runBlocking {
            val o = orphaned()
            val vm = AdminCheck.chatVm(o.p, HandshakeSeam(X_OLD))
            assertEquals("ORPHANED", AdminCheck.keyState(vm))
            val record = o.record()

            o.p.base.peerKeys[X_OLD] = o.old.publicKey

            assertEquals("the key that came back does not read OK", "OK", AdminCheck.keyState(vm))
            assertEquals("the record was written", record, o.record())
            assertEquals("a durable write was made", 0, o.p.durableWrites.size)
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - in state OK a confirmation is NOT_NEEDED`() =
        runBlocking {
            val o = orphaned()
            o.p.base.peerKeys[X_OLD] = o.old.publicKey
            o.p.verified += X_OLD
            val vm = AdminCheck.chatVm(o.p, HandshakeSeam(X_OLD))

            assertEquals("NOT_NEEDED", AdminCheck.confirm(vm, X_OLD, o.old.publicKey))
            assertEquals(o.old.publicKey, o.storedKey())
        }

    // ------------------------------------------------------------------ (g)

    @Test
    fun `g - a relay removal signed with the new key is kept while ORPHANED and applied after the confirmation of the new key`() =
        runBlocking {
            val o = orphaned()
            val seam = HandshakeSeam(X_OLD)
            val vm = AdminCheck.chatVm(o.p, seam)
            val newKey = AnnounceDriver.newGroupKey()

            AdminCheck.relayDeliver(vm, kick(o, o.new, newKey = newKey), X_OLD, KICK_RELAY, relayTime)

            assertEquals("a relay removal of the new key was not kept while ORPHANED", listOf(KICK_RELAY), keptIds(o.p))
            assertEquals(MemberStatus.ACTIVE, o.p.base.status(GID, M))

            o.p.verified += X_OLD
            assertEquals(CONFIRMED, AdminCheck.confirm(vm, X_OLD, o.new.publicKey))
            AdminCheck.awaitOwnJobs(vm)
            completePass(vm)

            assertEquals("the kept removal was not applied after the confirmation", MemberStatus.LEFT, o.p.base.status(GID, M))
            assertEquals(newKey, o.p.groupKey(2))
            assertEquals(emptyList<String>(), keptIds(o.p))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    @Test
    fun `g - with a kept change the old key coming back turns the group OK, and the next complete pass refuses and drops it`() =
        runBlocking {
            val o = orphaned()
            val vm = AdminCheck.chatVm(o.p, HandshakeSeam(X_OLD))
            AdminCheck.relayDeliver(vm, kick(o, o.new), X_OLD, KICK_RELAY, relayTime)
            assertEquals("fixture: the relay removal is kept", listOf(KICK_RELAY), keptIds(o.p))

            o.p.base.peerKeys[X_OLD] = o.old.publicKey
            assertEquals("OK", AdminCheck.keyState(vm))
            completePass(vm)

            assertEquals("a change the stored key refused was not dropped", emptyList<String>(), keptIds(o.p))
            assertEquals("a change the stored key refused was applied", MemberStatus.ACTIVE, o.p.base.status(GID, M))
            assertEquals(1, o.p.base.epochs[GID])
        }

    @Test
    fun `g - the slot's re-invite kept against the old key gives way to the new key chat's kept re-invite after the confirmation`() =
        runBlocking {
            val o = orphaned()
            val vm = AdminCheck.chatVm(o.p, HandshakeSeam(X_OLD))
            val base = o.p.base
            val ours = base.ourKeys.getValue(X_OLD)
            // ZC-294's slot holds a re-invite it kept against K_old: its key is wrapped under the session with K_old.
            val oldSession =
                E2EEncryption.deriveSharedSecret(
                    ours.privateKey,
                    o.old.publicKey,
                    E2EKeyVersion.fromValue(2),
                )
            val oldInvite = ZMSGGroupProtocol.parsePayload(AnnounceDriver.gi(X_OLD, oldSession, AnnounceDriver.newGroupKey(), 3, GID))!!
            val before = base.state(GID)
            base.states[GID] = before.copy(pendingReinvite = PendingReinvite(INVITE_OLD, relayTime.toEpochMilli(), oldInvite, X_OLD))

            // The K_new chat's re-invite over the relays, while ORPHANED: kept for the confirmation.
            val newInvite = AnnounceDriver.gi(X_OLD, o.p.base.sessionWith(X_OLD), AnnounceDriver.newGroupKey(), 3, GID)
            AdminCheck.relayDeliver(vm, newInvite, X_OLD, INVITE_NEW, relayTime.plusSeconds(60))
            val kept = AdminCheck.keptChanges(o.p).filter { it.getString("t") == "GI" }.map { it.getString("id") }
            assertEquals("the new key chat's re-invite was not kept while ORPHANED", listOf(INVITE_NEW), kept)

            o.p.verified += X_OLD
            assertEquals(CONFIRMED, AdminCheck.confirm(vm, X_OLD, o.new.publicKey))
            AdminCheck.awaitOwnJobs(vm)

            val slot = base.state(GID).pendingReinvite
            assertEquals("the re-invite kept against the replaced key kept the slot", INVITE_NEW, slot?.inviteId)
            assertTrue("a kept invite stayed in the list", AdminCheck.keptChanges(o.p).none { it.getString("t") == "GI" })
            val record = JSONObject(base.infos.getValue(GID))
            assertTrue("fixture: the record is the group's", record.has("creator_pub"))
        }

    private companion object {
        val X_NOSTR = "3e".repeat(32)
        const val KICK_RELAY = "synthetic-zc572-relay-kick-new-key"
        const val INVITE_OLD = "synthetic-zc572-slot-invite-old-key"
        const val INVITE_NEW = "synthetic-zc572-relay-invite-new-key"
    }
}
