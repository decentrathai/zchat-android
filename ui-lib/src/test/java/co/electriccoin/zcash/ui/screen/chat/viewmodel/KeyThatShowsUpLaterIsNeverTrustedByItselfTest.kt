package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b D1; section 4.3 class 14) — a key that shows up later under a group's creator
 * address is never trusted by itself. `driftedPhone()`: the group's record in the store has no creator key.
 *
 * - (a) a finished chat appears at `X_OLD` (an accepted request): still UNKNOWN; a removal naming `X_OLD` signed with
 *   that chat's key changes nothing on three passes; a restart writes nothing; the candidate is that chat (source
 *   ADDRESS) and the confirmation needs the verified mark; after it the same removal applies. (b) the same with `X_OLD`
 *   a saved contact. (c) an unsolicited answer's key (no key pair of ours): UNKNOWN, the card says the chat is not
 *   finished, the removal changes nothing. (d) an invite field's key: the same.
 * - (e) a group that was left, then a key with a relay identity at the creator address: the creator's plaintext
 *   re-invite is not offered for Join (as today). (g) the Open half: a plain-key group that was left, with a key at its
 *   creator's address: it is offered, as today. Both pass at the base and pin.
 * - (f) no control, announce or re-invite writes a key, and `effectiveCreatorPublicKey` reads only the record.
 *
 * At the base (a)-(d) and (f) fail: the later key is taken as the admin's and frozen into the record.
 * Nothing is paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyThatShowsUpLaterIsNeverTrustedByItselfTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
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
        AdminCheck.forgetConfirmedPasses()
    }

    private val kickTime: Instant = Instant.ofEpochMilli(T + 60_000)

    private fun onChain(
        vm: ChatViewModel,
        memo: String,
        n: Int = 6,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(n), minedHeight = HEIGHT + n)

    private fun kick(
        p: AdminCheckPhone,
        signer: E2EKeyPair,
        newKey: String? = AnnounceDriver.newGroupKey(),
    ): String = AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = newKey, signer = signer, wrapFor = X_OLD)

    private fun groupWrites(p: AdminCheckPhone): Int = p.calls.count { it == "saveGroupInfo" || it == "saveGroupInfosDurably" }

    private suspend fun cardCandidate(p: AdminCheckPhone): Any? {
        val f = GroupPaidOnceFixture()
        f.scheduler = scheduler
        val vm = AdminCheck.groupVm(p, f)
        return AdminCheck.loadCandidate(vm, f)
    }

    /** The shared body of (a) and (b): a finished chat at the creator address is never trusted by itself. */
    private suspend fun finishedChatIsNotTrustedByItself(p: AdminCheckPhone) {
        val chat = p.base.link(X_OLD)
        val seam = HandshakeSeam(X_OLD)
        val vm = AdminCheck.chatVm(p, seam)
        val record = p.base.infos.getValue(GID)
        val newKey = AnnounceDriver.newGroupKey()
        val removal = kick(p, chat, newKey)

        assertEquals("a finished chat at the creator address made the group's admin known by itself", "UNKNOWN", AdminCheck.keyState(vm))
        repeat(3) { onChain(vm, removal) }
        assertEquals("a removal signed with the later key was applied", MemberStatus.ACTIVE, p.base.status(GID, M))
        assertEquals("a key was written into the record", record, p.base.infos.getValue(GID))

        // A restart: a new view model, another pass.
        AdminCheck.forgetConfirmedPasses()
        val again = AdminCheck.chatVm(p, seam)
        onChain(again, removal)
        assertEquals("a restart wrote a key", record, p.base.infos.getValue(GID))
        assertEquals(0, groupWrites(p))

        val card = cardCandidate(p) ?: throw AssertionError("the chat at the creator address is not offered")
        assertEquals(X_OLD, AdminCheck.property(card, "address"))
        assertEquals("ADDRESS", AdminCheck.property(card, "source").toString())
        assertEquals("the confirmation did not need the verified mark", "NOT_VERIFIED", AdminCheck.confirm(again, X_OLD, chat.publicKey))

        p.verified += X_OLD
        assertEquals(CONFIRMED, AdminCheck.confirm(again, X_OLD, chat.publicKey))
        HandshakeHarness.awaitIdle(again)
        onChain(again, removal)
        assertEquals("the confirmed admin's removal was not applied", MemberStatus.LEFT, p.base.status(GID, M))
        assertEquals(newKey, p.groupKey(2))
        assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
    }

    @Test
    fun `a - a finished chat that appears at the creator address is not the admin until confirmed`() =
        runBlocking { finishedChatIsNotTrustedByItself(AdminCheck.driftedPhone()) }

    @Test
    fun `b - the same when the creator address is a saved contact`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.savedContacts += Contact(X_OLD, "Synthetic admin")
            finishedChatIsNotTrustedByItself(p)
        }

    /** (c) and (d): a key at the creator address with no key pair of ours. */
    private suspend fun keyWithoutOurPairIsNotTrusted() {
        val p = AdminCheck.driftedPhone()
        val claimed = E2EEncryption.generateKeyPair()
        p.base.peerKeys[X_OLD] = claimed.publicKey
        val vm = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
        val record = p.base.infos.getValue(GID)

        assertEquals("UNKNOWN", AdminCheck.keyState(vm))
        val card = cardCandidate(p) ?: throw AssertionError("the card names no chat")
        assertEquals("the card does not say the chat is not finished", false, AdminCheck.property(card, "chatFinished"))
        repeat(3) { onChain(vm, kick(p, claimed, newKey = null)) }
        assertEquals("a removal signed with a claimed key was applied", MemberStatus.ACTIVE, p.base.status(GID, M))
        assertEquals("a claimed key was written into the record", record, p.base.infos.getValue(GID))
    }

    @Test
    fun `c - an unsolicited answer's key at the creator address changes nothing`() = runBlocking { keyWithoutOurPairIsNotTrusted() }

    @Test
    fun `d - an invite field's key at the creator address changes nothing`() = runBlocking { keyWithoutOurPairIsNotTrusted() }

    // ------------------------------------------------------------------ (e), (g)

    /** X's phone (plaintext join), removed by the creator's sealed kick. */
    private suspend fun removedOpenMember(): Pair<AnnouncePhone, ChatViewModel> {
        net.register()
        val (phone, chat) = OpenGroup.plainMember(OpenGroup.X)
        MA3.relay(chat, OpenGroup.sealedKick(OpenGroup.X, newEpoch = 2, gk = null), sender = OpenGroup.C, timeMs = MA3.now() - 100_000L)
        assertFalse("fixture: the creator's sealed kick did not remove the phone", phone.info(OpenGroup.GID).isActive)
        return phone to chat
    }

    @Test
    fun `e - pin - a group that is not Open, left, with a key at the creator address is not offered the plaintext re-invite`() =
        runBlocking {
            val (phone, chat) = removedOpenMember()
            phone.states[OpenGroup.GID] = phone.state(OpenGroup.GID).copy(plainKeyJoin = false)
            phone.peerKeys[OpenGroup.C] = E2EEncryption.generateKeyPair().publicKey

            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 2, key = AnnounceDriver.newGroupKey()), sender = OpenGroup.C)

            assertNull("a plaintext re-invite was offered while a key sits at the creator address", phone.needsJoin[OpenGroup.GID])
        }

    @Test
    fun `g - pin - an Open group that was left, with a key at its creator address, is offered the creator's plaintext re-invite`() =
        runBlocking {
            val (phone, chat) = removedOpenMember()
            phone.peerKeys[OpenGroup.C] = E2EEncryption.generateKeyPair().publicKey

            MA3.relay(chat, OpenGroup.plainInvite(keyEpoch = 2, key = AnnounceDriver.newGroupKey()), sender = OpenGroup.C)

            assertTrue("the creator's plaintext re-invite was not offered to an Open group", phone.needsJoin[OpenGroup.GID] != null)
        }

    // ------------------------------------------------------------------ (f)

    @Test
    fun `f - no control, announce or re-invite writes a key, and effectiveCreatorPublicKey reads only the record`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            val chat = p.base.link(X_OLD)
            p.base.nostrKeys[X_OLD] = "7a".repeat(32)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
            val record = p.base.infos.getValue(GID)

            onChain(vm, kick(p, chat))
            val announce = AnnounceDriver.gp(AnnounceOp.A, M, 2, 1, chat, GID)
            AnnounceDriver.receive(vm, announce, kickTime, AnnounceDriver.txId(7), minedHeight = HEIGHT + 7)
            val reinvite = AnnounceDriver.gi(X_OLD, p.base.sessionWith(X_OLD), AnnounceDriver.newGroupKey(), 3, GID)
            AdminCheck.relayDeliver(vm, reinvite, X_OLD, "synthetic-zc598-reinvite-1", kickTime.plusSeconds(60))

            assertEquals("a control, an announce or a re-invite wrote a key", record, p.base.infos.getValue(GID))
            assertEquals("the call log holds a group record write", 0, groupWrites(p))

            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
            val root = dir ?: error("repo root not found")
            val source = File(root, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt").readText()
            val start = source.indexOf("private fun effectiveCreatorPublicKey(")
            assertTrue("no effectiveCreatorPublicKey", start >= 0)
            // The member runs to the first blank line after its declaration.
            val end = source.indexOf("\n\n", start)
            val member = source.substring(start, if (end < 0) source.length else end)
            for (forbidden in listOf("getE2EPeerPublicKey(", "resolvePeerAddress(", "saveGroupInfo")) {
                assertFalse("effectiveCreatorPublicKey still uses $forbidden", member.contains(forbidden))
            }
        }
}
