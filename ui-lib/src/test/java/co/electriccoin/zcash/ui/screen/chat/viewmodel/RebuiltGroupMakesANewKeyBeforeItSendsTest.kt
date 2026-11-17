package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-757 (FIRST-CONTACT-SPEC 5.8, G1-G7; 6.7) — after a restore, a group this phone CREATED is rebuilt from its own sent
 * history, but it cannot get its newest key back and sends under an old one.
 *
 * At cc3512070: the rebuild stores the key number of the invitation it rebuilt from (0); this phone's own on-chain key
 * changes and removals are dropped by the signature check (no contact key for our own address), so the number stays 0,
 * a removed member is listed again and the removal floor stays 0; rebuilt rows have no delivery record, so a key change
 * reaches nobody; a key change is numbered "stored + 1" (ZC-778), a number members already hold and refuse; and a send
 * goes out under the old key.
 *
 * Wanted (no wire change): G3/G4 our own on-chain key change and removal raise the stored number and apply the removal
 * again (a LEFT row, the removal's number, the removal floor with its height); G5 a row from our own mined invitation is
 * a key holder; G1 no message, invitation or key grant before a new key made on this phone; G2 that key is numbered
 * above any number a member can hold (a time floor: whole minutes of the later of the phone's clock and the newest block
 * time); G6 messages only once the new key (or an invitation after it) was sent to a member row. A key change is the
 * existing quoted action, never automatic.
 *
 * New names are read by name (RebuiltGroupKey, GroupSendResult.NeedsNewKey, GroupViewModel.rebuiltGroupNotice), so this
 * file compiles before they exist. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltGroupMakesANewKeyBeforeItSendsTest {
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
        net.register()
        // T7bG review round 2, finding 2: the first new key waits for a pass's block time; here a pass read one an hour ago.
        setRebuiltClock(NOW_MS, NOW_MS - 3_600_000L)
    }

    @After
    fun tearDown() {
        setRebuiltClock(null, 0L)
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ---- fixture ----------------------------------------------------------------------------------------------------

    /** Sets the phone clock and the newest block time ZC-757 reads (by name; nothing before it exists). */
    private fun setRebuiltClock(
        nowMs: Long?,
        newestBlockMs: Long,
    ) {
        val cls = runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.RebuiltGroupKey") }.getOrNull() ?: return
        val instance = cls.getField("INSTANCE").get(null)
        val clock: () -> Long = if (nowMs == null) ({ System.currentTimeMillis() }) else ({ nowMs })
        cls.getMethod("setClock", Function0::class.java).invoke(instance, clock)
        cls.getMethod("setNewestMinedTxMs", Long::class.javaPrimitiveType).invoke(instance, newestBlockMs)
    }

    private val oldKey = MA3.groupKey

    /** A restored creator's phone: no group record yet; [linked] members have a secure chat and a relay key with it. */
    private fun restoredAdmin(): AnnouncerPhone = AnnouncerPhone(C)

    /** [member]'s phone in the group at key number [epoch], linked with [admin] (a secure chat both ways). */
    private fun member(
        member: String,
        admin: AnnouncerPhone,
        epoch: Int = 1,
    ): AnnouncerPhone =
        MA3.memberPhone(member, admin, epoch).also { admin.base.nostrKeys[member] = NPUB.getValue(member) }

    private fun ownInvite(
        admin: AnnouncerPhone,
        to: String,
        epoch: Int = 0,
    ): String =
        if (admin.base.ourKeys.containsKey(to) && admin.base.peerKeys.containsKey(to)) {
            AnnounceDriver.gi(C, admin.base.sessionWith(to), oldKey, epoch, GID)
        } else {
            "ZMSG:3.0:GROUP:GI:$GID:" +
                JSONObject()
                    .put("name", "Announce group")
                    .put("inviter", C)
                    .put("key_epoch", epoch)
                    .put("k2", "AAAA")
                    .toString()
        }

    private fun ownKeyChange(
        admin: AnnouncerPhone,
        to: String,
        epoch: Int,
    ): String {
        val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), admin.base.sessionWith(to))
        val signed = ZMSGGroupProtocol.groupKeySignedData(GID, C, epoch, wrapped, "rotation")
        val sig = E2EEncryption.sign(MA3.keyFor(admin, to).privateKey, signed)
        return ZMSGGroupProtocol.createGroupKeyMessage(GID, C, epoch, wrapped, sig)
    }

    private fun ownRemoval(
        admin: AnnouncerPhone,
        removed: String,
        to: String,
        epoch: Int,
    ): String {
        val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), admin.base.sessionWith(to))
        val signed = ZMSGGroupProtocol.groupKickSignedData(GID, removed, C, epoch, wrapped)
        val sig = E2EEncryption.sign(MA3.keyFor(admin, to).privateKey, signed)
        return ZMSGGroupProtocol.createGroupKickMessage(GID, removed, C, epoch, wrapped, sig)
    }

    /** One of our own mined transactions, as the history pass hands it over. */
    private fun own(
        vm: ChatViewModel,
        memo: String,
        tx: Int,
        to: String,
        height: Long,
    ) = AnnounceDriver.receive(
        vm,
        memo,
        Instant.ofEpochMilli(AnnounceDriver.T + tx * 60_000L),
        AnnounceDriver.txId(tx),
        ownSend = true,
        ownRecipient = to,
        minedHeight = height,
    )

    private suspend fun gvm(
        admin: AnnouncerPhone,
        f: GroupPaidOnceFixture = GroupPaidOnceFixture().apply { scheduler = this@RebuiltGroupMakesANewKeyBeforeItSendsTest.scheduler },
    ): Pair<GroupViewModel, GroupPaidOnceFixture> = MA3.groupVm(admin, f, AnnounceSeam()) to f

    /** The admin's key change: the question about members that cannot be given the key, then the quote, both answered yes. */
    private suspend fun rotate(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
    ) {
        f.awaitNewJobs(vm) { vm.rotateGroupKey(GID) }
        if (vm.rekeyUnkeyableConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmRekeyUnkeyable() }
        if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
    }

    private suspend fun sendResult(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
    ): Any? {
        vm.consumeGroupSendEvent()
        f.awaitNewJobs(vm) { vm.sendGroupMessage(GID, "synthetic") }
        if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
        return vm.groupSendEvent.value
    }

    private fun isNeedsNewKey(result: Any?): Boolean = result?.javaClass?.simpleName == "NeedsNewKey"

    private fun flag(
        admin: AnnouncerPhone,
        name: String,
    ): Boolean = JSONObject(admin.base.infos.getValue(GID)).optBoolean(name, false)

    private fun keyChangesTo(npub: String): List<Int> =
        net.ofType(GroupMessageType.GROUP_KEY, npub).mapNotNull { ZMSGGroupProtocol.parsePayload(it) }.mapNotNull {
            ZMSGGroupProtocol.parseGroupKeyPayload(GID, it)?.epoch
        }

    /** The restore of cases (a)-(h): our own mined invitation to A at number 0 and our own key change to number 3. */
    private suspend fun restoredWithKeyChange(): Triple<AnnouncerPhone, AnnouncerPhone, ChatViewModel> {
        val admin = restoredAdmin()
        val phoneA = member(A, admin, epoch = 3)
        val vm = MA3.chatVm(admin)
        own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
        own(vm, ownKeyChange(admin, A, 3), tx = 2, to = A, height = 110)
        return Triple(admin, phoneA, vm)
    }

    // ---- (a) the rebuild ---------------------------------------------------------------------------------------------

    @Test
    fun `(a) the rebuilt group takes number 3 from our own key change and sends and invites nothing before a new key`() =
        runBlocking {
            val (admin, _, _) = restoredWithKeyChange()
            assertTrue("fixture: the group was not rebuilt", admin.base.info(GID).announcerRebuilt)
            assertEquals("the stored key number ignores our own on-chain key change", 3, admin.base.epochs[GID])

            val (vm, f) = gvm(admin)
            val result = sendResult(vm, f)
            assertTrue("a send in a rebuilt group without a new key is not refused with 'needs a new key': $result", isNeedsNewKey(result))
            assertEquals("a message went over the relays under an old key", 0, net.ofType(GroupMessageType.GROUP_MSG).size)
            vm.consumeGroupSendEvent()
            f.awaitNewJobs(vm) { vm.addMemberToGroup(GID, D) }
            assertTrue("an invitation in a rebuilt group without a new key is not refused", isNeedsNewKey(vm.groupSendEvent.value))
            assertEquals("an invitation went out under an old key", 0, net.ofType(GroupMessageType.GROUP_INVITE).size)
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }

    // ---- (b) (h) (m) the new key -------------------------------------------------------------------------------------

    @Test
    fun `(b, h, m) the key change is numbered above the time floor, reaches A, and then the group works also after a restart`() =
        runBlocking {
            val (admin, phoneA, _) = restoredWithKeyChange()
            assertEquals(
                "(h) the row of our own mined invitation is not recorded as delivered",
                InviteStatus.SENT,
                admin.base
                    .roster(GID)
                    .single { it.address == A }
                    .inviteStatus,
            )
            val (vm, f) = gvm(admin)
            rotate(vm, f)

            val expected = (NOW_MS / 60_000L).toInt() + 1
            assertEquals("the new key's number is not one above the time floor", expected, admin.base.epochs[GID])
            assertTrue("the new key is not on the disk under its number", admin.base.keys.containsKey("$GID|$expected"))
            assertEquals("(h) A was not sent the new key", listOf(expected), keyChangesTo(NPUB.getValue(A)))
            assertTrue("the rebuilt group's new key is not recorded", flag(admin, "rekeyed_after_rebuild"))
            assertTrue("the new key reached A but the group does not know", flag(admin, "rebuilt_key_reached_member"))

            net.ofType(GroupMessageType.GROUP_KEY, NPUB.getValue(A)).forEach { MA3.relay(MA3.chatVm(phoneA), it, sender = C) }
            assertEquals("A (at number 3) did not take the new key", expected, phoneA.base.epochs[GID])

            assertFalse("a send after the new key reached A is still refused", isNeedsNewKey(sendResult(vm, f)))
            val (afterRestart, f2) = gvm(admin)
            assertFalse("(m) after a restart the group asks for a new key again", isNeedsNewKey(sendResult(afterRestart, f2)))
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(c) with the phone's clock a year back the number comes from the newest block time`() =
        runBlocking {
            setRebuiltClock(NOW_MS - 365L * 24 * 3_600_000L, NOW_MS)
            val (admin, _, _) = restoredWithKeyChange()
            val (vm, f) = gvm(admin)
            rotate(vm, f)
            assertEquals("the number follows a clock set back", (NOW_MS / 60_000L).toInt() + 1, admin.base.epochs[GID])
        }

    @Test
    fun `(d) a member at number 7 (a key change that went over the relays only) takes the new key too`() =
        runBlocking {
            val admin = restoredAdmin()
            val phoneA = member(A, admin, epoch = 3)
            val phoneB = member(B, admin, epoch = 7)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
            own(vm, ownInvite(admin, B), tx = 2, to = B, height = 101)
            own(vm, ownKeyChange(admin, A, 3), tx = 3, to = A, height = 110)
            val (gvm, f) = gvm(admin)
            rotate(gvm, f)
            val n = admin.base.epochs.getValue(GID)
            for ((who, phone) in listOf(A to phoneA, B to phoneB)) {
                val memberVm = MA3.chatVm(phone)
                net.ofType(GroupMessageType.GROUP_KEY, NPUB.getValue(who)).forEach { MA3.relay(memberVm, it, sender = C) }
                assertEquals("the member did not take the new key ${who.take(22)}", n, phone.base.epochs[GID])
            }
        }

    // ---- (f) (f2) our own removal ------------------------------------------------------------------------------------

    @Test
    fun `(f, f2) our own removal of B after our own invitation to B leaves one LEFT row, the floor at its number, and no key for B`() =
        runBlocking {
            val admin = restoredAdmin()
            member(A, admin, epoch = 4)
            member(B, admin, epoch = 3)
            val vm = MA3.chatVm(admin)
            repeat(2) {
                own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
                own(vm, ownInvite(admin, B), tx = 2, to = B, height = 101)
                own(vm, ownRemoval(admin, B, A, 4), tx = 3, to = A, height = 120)
            }
            val rowsOfB = admin.base.roster(GID).filter { it.address == B }
            assertEquals("B is listed more than once or not at all: ${rowsOfB.map { it.status }}", 1, rowsOfB.size)
            assertEquals("our own removal of B is not applied again", MemberStatus.LEFT, rowsOfB.single().status)
            assertEquals("(f2) the removal floor is not the removal's number", 4, admin.base.floors[GID])
            assertEquals("(f2) the removal's height is not recorded", 120L, admin.base.kickHeights[GID]?.get(4))
            assertEquals("the stored number is not the removal's", 4, admin.base.epochs[GID])

            val (gvm, f) = gvm(admin)
            rotate(gvm, f)
            assertTrue("B, removed, was sent the new key", keyChangesTo(NPUB.getValue(B)).isEmpty())
            assertEquals("A was not sent the new key", 1, keyChangesTo(NPUB.getValue(A)).size)
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }

    // ---- (n) (o) a key change that reaches nobody ----------------------------------------------------------------------

    @Test
    fun `(n, o) a new key nobody was sent allows an invitation but no message, and the first invitation lets messages go`() =
        runBlocking {
            val admin = restoredAdmin()
            val vm = MA3.chatVm(admin)
            // A restored wallet's recipient text with no secure chat (N-13): A's row is a key holder this phone cannot key.
            own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
            own(vm, ownKeyChange0(), tx = 2, to = A, height = 110)
            val (gvm, f) = gvm(admin)
            rotate(gvm, f)

            val n = admin.base.epochs.getValue(GID)
            assertTrue("no new key was made", n > 3)
            assertTrue("the new key is not on the disk", admin.base.keys.containsKey("$GID|$n"))
            assertTrue("step 1's flag is not set", flag(admin, "rekeyed_after_rebuild"))
            assertFalse("step 2's flag is set although nobody got the key", flag(admin, "rebuilt_key_reached_member"))
            val refused = sendResult(gvm, f)
            assertTrue("a message nobody can read is not refused: $refused", isNeedsNewKey(refused))
            assertEquals(
                "the notice is not the 'no reader' one",
                "NO_READER",
                GroupViewModel::class.java.methods
                    .firstOrNull { it.name == "rebuiltGroupNotice" }
                    ?.invoke(gvm, GID)
                    ?.toString(),
            )

            // (o) an invitation carries the new key to D.
            member(D, admin, epoch = 0)
            gvm.consumeGroupSendEvent()
            f.awaitNewJobs(gvm) { gvm.addMemberToGroup(GID, D) }
            if (gvm.paidActionConfirmation.value != null) MA3.confirm(gvm, f)
            val invites = net.ofType(GroupMessageType.GROUP_INVITE, NPUB.getValue(D))
            assertEquals("the invitation was not sent", 1, invites.size)
            val carried = JSONObject(ZMSGGroupProtocol.parsePayload(invites.single())!!).optInt("key_epoch")
            assertEquals("the invitation does not carry the new number", n, carried)
            assertTrue("step 2's flag is not set after the invitation", flag(admin, "rebuilt_key_reached_member"))
            assertFalse("a message is still refused after the invitation", isNeedsNewKey(sendResult(gvm, f)))
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }

    /** Our own key change to number 3 sent to a member this restored phone has no secure chat with (not readable here). */
    private fun ownKeyChange0(): String {
        val signer = E2EEncryption.generateKeyPair()
        val wrapped = E2EEncryption.encrypt(AnnounceDriver.newGroupKey(), ByteArray(32) { 7 })
        val sig = E2EEncryption.sign(signer.privateKey, ZMSGGroupProtocol.groupKeySignedData(GID, C, 3, wrapped, "rotation"))
        return ZMSGGroupProtocol.createGroupKeyMessage(GID, C, 3, wrapped, sig)
    }

    // ---- (j) guard ---------------------------------------------------------------------------------------------------

    @Test
    fun `(j) a group that was not rebuilt numbers its key change as today and sends as today`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE), epoch = 1)
            member(A, admin, epoch = 1)
            val (vm, f) = gvm(admin)
            rotate(vm, f)
            assertEquals("a group that was not rebuilt is numbered by the time floor", 2, admin.base.epochs[GID])
            assertNotEquals("a group that was not rebuilt is refused", true, isNeedsNewKey(sendResult(vm, f)))
        }

    private companion object {
        /** 2027-01-15, as milliseconds: the phone's clock in these cases. */
        const val NOW_MS = 1_800_000_000_000L
    }
}
