package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.Base64
import java.util.Collections

/**
 * ZC-1692 (QA2 P3 polish, Fable review of the U9 follow-up, finding 1; medium): after a restore, a group this phone had
 * already joined shows Join / Ignore again, and Ignore deletes the group together with its restored history.
 *
 * The restore reads the chain history in one pass. The creator's invitation (with its key, so it unwraps at once) is read
 * first: every invite with a key now writes a fresh "waiting for Join" record (ZC-922, W-137 B-4). Our own sent "I
 * joined" for the same group is read later in the same pass (`HistoryPass.ownGroupReplies`), and the record stays. The
 * ZC-633 finish path already honours that (InviteReadBeforeItsKeyStillJoinsTest (c)); the ordinary path does not.
 *
 * Wanted: when the pass reads our own on-chain "I joined" for a group after its invitation, the fresh waiting record
 * (a first-time record: no failed relay try, not a re-invite's) is cleared, so the restored group shows no Join / Ignore.
 * It must not change what is paid or when (money-path rule 2): nothing is sent, proposed or submitted, here or by
 * clearing. An invitation with no own reply in the pass, and an own reply for another group, leave the record.
 *
 * Fable review of P3 (finding 2): the first fix kept the link between the record and "this pass wrote it" in the memory of
 * one pass, so it failed when the invitation and our reply were read in different passes, after a pass cancelled before its
 * end, and it cleared a record for an "I joined" older than the invitation. Wanted: every pass decides from the stored
 * state: an on-chain invitation re-read for an active group whose stored waiting record is a first-time one tied to that
 * invitation is cleared only when an own reply LATER than the invitation was read (this pass or the same pass's list).
 *
 * QA2 p3c (Fable review of P3b, finding 7, ZC-1756): "later" is decided by the mined height when the invitation and the
 * reply carry different ones. Block times are not strictly increasing between neighbouring blocks, so a reply mined one
 * block after the invitation can carry an equal or earlier time and the record stayed (Join shown on a joined group), and a
 * reply from an older block can carry a later time. Equal or unknown heights fall back to the times, as before.
 *
 * Synthetic addresses, keys, txids and group ids only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreOwnReplyAfterTheInviteLeavesNoWaitingRecordTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    private val inviteTx: TransactionId = AnnounceDriver.txId(1692)
    private val ownAcceptTx: TransactionId = AnnounceDriver.txId(1693)
    private val otherAcceptTx: TransactionId = AnnounceDriver.txId(1694)
    private val invitedAt: Instant = Instant.ofEpochMilli(T + 16_920_000)
    private val epoch = 3
    private val groupKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 5).toByte() })

    /** Another group id of the shape the app makes (the id check of ZC-708), for the "own reply for another group" case. */
    private val otherGroup = "zgrp_ffffeeeeddddccccbbbbaaaa"

    private class Rig(
        val phone: AnnouncePhone,
        val seam: HandshakeSeam,
        val vm: ChatViewModel,
        val ours: E2EKeyPair,
        val admin: E2EKeyPair,
    ) {
        val session: ByteArray
            get() = E2EEncryption.deriveSharedSecret(ours.privateKey, admin.publicKey, E2EKeyVersion.fromValue(2))
    }

    /** A restored phone: our key pair for the creator and the creator's key are both stored, so the invitation unwraps at once. */
    private suspend fun rig(): Rig {
        val phone = AnnouncePhone()
        val ours = E2EEncryption.generateKeyPair()
        val admin = E2EEncryption.generateKeyPair()
        phone.ourKeys[ADMIN] = ours
        phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        phone.peerKeys[ADMIN] = admin.publicKey
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.relayAcks(published)
        return Rig(phone, seam, vm, ours, admin)
    }

    private class Memo(
        val memo: String,
        val txId: TransactionId,
        val time: Instant,
        val ownSend: Boolean = false,
        val ownRecipient: String? = null,
        val height: Long? = 1_000L,
    )

    /** One whole history pass over [memos] in the given (chronological) order: begin, the loop's group memos, the end hook. */
    private suspend fun pass(
        rig: Rig,
        vararg memos: Memo,
    ) = runPass(rig, end = true, *memos)

    /** A pass the next emission cancels before its end hook ran (`collectLatest`): the memos are read, nothing ends it. */
    private suspend fun cancelledPass(
        rig: Rig,
        vararg memos: Memo,
    ) = runPass(rig, end = false, *memos)

    private suspend fun runPass(
        rig: Rig,
        end: Boolean,
        vararg memos: Memo,
    ) {
        val pass = AdminCheck.beginPass(rig.vm)
        val inPass =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "processGroupMessageInPass" && !it.isSynthetic }
                .apply { isAccessible = true }
        for (m in memos) {
            try {
                inPass.invoke(rig.vm, m.memo, m.txId, m.time, m.ownSend, m.ownRecipient, m.height, pass)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        if (end) AdminCheck.endPass(rig.vm, pass, true)
        HandshakeHarness.awaitIdle(rig.vm)
    }

    private fun invite(rig: Rig) =
        Memo(AnnounceDriver.gi(ADMIN, rig.session, groupKey, epoch), inviteTx, invitedAt)

    private fun ownAccept(
        rig: Rig,
        group: String = GID,
        tx: TransactionId = ownAcceptTx,
        at: Instant = invitedAt.plusSeconds(60),
        height: Long? = 1_000L,
    ) = Memo(
        ZMSGGroupProtocol.createGroupAcceptMessage(group, rig.phone.self, rig.ours.publicKey, "", epoch),
        tx,
        at,
        ownSend = true,
        ownRecipient = ADMIN,
        height = height,
    )

    private fun repliesToCreator(): Int =
        published.count { (text, to) -> to == ADMIN_NOSTR && ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_ACCEPT }

    private fun assertNothingSentOrPaid(
        rig: Rig,
        what: String,
    ) {
        assertEquals("$what: a reply went out over the relays", 0, repliesToCreator())
        assertEquals("$what: something was proposed for payment", 0, rig.seam.proposalRequests.get())
        assertEquals("$what: something was submitted", 0, rig.seam.submits.get())
    }

    @Test
    fun `restore - our own on-chain reply read after the invite leaves no waiting record`() =
        runBlocking {
            val rig = rig()
            pass(rig, invite(rig), ownAccept(rig))

            val info = rig.phone.info(GID)
            assertTrue("fixture: the group is saved and active", info.isActive)
            assertEquals("fixture: the invitation's key was taken at its number", groupKey, rig.phone.keys["$GID|$epoch"])
            assertNull(
                "the restored group still waits for Join (Join / Ignore) although our own \"I joined\" is in the history",
                rig.phone.needsJoin[GID],
            )
            assertNothingSentOrPaid(rig, "the restore pass")
        }

    @Test
    fun `restore - a later pass over the same history keeps the group joined and asks nothing`() =
        runBlocking {
            val rig = rig()
            val invite = invite(rig)
            val ownAccept = ownAccept(rig)
            pass(rig, invite, ownAccept)
            pass(rig, invite, ownAccept)

            assertNull("a second pass brought the waiting record back", rig.phone.needsJoin[GID])
            assertTrue("the group is no longer active", rig.phone.info(GID).isActive)
            assertNothingSentOrPaid(rig, "the second restore pass")
        }

    @Test
    fun `an invitation with no reply of ours in the pass still waits for Join and pays nothing`() =
        runBlocking {
            val rig = rig()
            pass(rig, invite(rig))

            assertNotNull("a fresh invitation no longer waits for Join", rig.phone.needsJoin[GID])
            assertNothingSentOrPaid(rig, "the fresh invitation")
        }

    @Test
    fun `our own reply for another group does not clear this group's waiting record`() =
        runBlocking {
            val rig = rig()
            pass(rig, invite(rig), ownAccept(rig, group = otherGroup, tx = otherAcceptTx))

            assertNotNull("an own reply for another group cleared this group's waiting record", rig.phone.needsJoin[GID])
            assertNothingSentOrPaid(rig, "the pass with another group's reply")
        }

    // ---- ZC-1692 (Fable review of P3, finding 2): the decision comes from the stored state in every pass ----

    @Test
    fun `restore - the invite in one pass and our own reply only in the next pass leaves no waiting record`() =
        runBlocking {
            val rig = rig()
            val invite = invite(rig)
            pass(rig, invite)
            assertNotNull("fixture: the first pass wrote the waiting record", rig.phone.needsJoin[GID])

            pass(rig, invite, ownAccept(rig))

            assertNull(
                "the later pass read our own \"I joined\" after the invitation and left Join / Ignore on a joined group",
                rig.phone.needsJoin[GID],
            )
            assertTrue("the group was deleted or deactivated", rig.phone.info(GID).isActive)
            assertNothingSentOrPaid(rig, "the second pass")
        }

    @Test
    fun `restore - a pass cancelled before its end leaves no record behind for the next pass`() =
        runBlocking {
            val rig = rig()
            val invite = invite(rig)
            cancelledPass(rig, invite)
            assertNotNull("fixture: the cancelled pass wrote the waiting record", rig.phone.needsJoin[GID])

            pass(rig, invite, ownAccept(rig))

            assertNull(
                "a pass cancelled after it wrote the record left it on disk for good",
                rig.phone.needsJoin[GID],
            )
            assertNothingSentOrPaid(rig, "the pass after the cancelled one")
        }

    @Test
    fun `an own reply older than the invitation does not clear the record of a new invitation`() =
        runBlocking {
            val rig = rig()
            // The group was joined, ignored (deleted) and invited again: the old "I joined" is in the history before the
            // new invitation. It is no answer to it, so the new invitation still waits for Join.
            pass(rig, ownAccept(rig, at = invitedAt.minusSeconds(3_600)), invite(rig))

            assertNotNull(
                "an \"I joined\" older than the invitation cleared the waiting record of the newer invitation",
                rig.phone.needsJoin[GID],
            )
            assertNothingSentOrPaid(rig, "the pass with an older own reply")
        }

    @Test
    fun `a record the relay try marked as failed is kept even when our own reply is read after the invitation`() =
        runBlocking {
            val rig = rig()
            val invite = invite(rig)
            pass(rig, invite)
            val stored = rig.phone.needsJoin[GID]
            assertNotNull("fixture: the waiting record", stored)
            val failed =
                org.json
                    .JSONObject(stored!!)
                    .put(co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord.RELAY_FAILED, true)
                    .toString()
            rig.phone.needsJoin[GID] = failed

            pass(rig, invite, ownAccept(rig))

            assertEquals("a record that is not a first-time one was cleared", failed, rig.phone.needsJoin[GID])
            assertNothingSentOrPaid(rig, "the pass with a failed-relay record")
        }

    // ---- ZC-1692 / ZC-1756 (QA2 p3c): the mined heights decide "later" when they differ ----

    @Test
    fun `a reply mined one block after the invitation clears the record although its block time is equal`() =
        runBlocking {
            val rig = rig()
            pass(rig, invite(rig), ownAccept(rig, at = invitedAt, height = 1_001L))

            assertNull(
                "a reply mined in the next block was not taken for later because its block time equals the invitation's",
                rig.phone.needsJoin[GID],
            )
            assertNothingSentOrPaid(rig, "the pass with an equal block time")
        }

    @Test
    fun `a reply mined one block after the invitation clears the record although its block time is earlier`() =
        runBlocking {
            val rig = rig()
            pass(rig, invite(rig), ownAccept(rig, at = invitedAt.minusSeconds(4), height = 1_001L))

            assertNull(
                "a reply mined in the next block was not taken for later because its block time is earlier than the invitation's",
                rig.phone.needsJoin[GID],
            )
            assertNothingSentOrPaid(rig, "the pass with an earlier block time")
        }

    @Test
    fun `a reply from an older block keeps the record although its block time is later`() =
        runBlocking {
            val rig = rig()
            pass(rig, ownAccept(rig, at = invitedAt.plusSeconds(4), height = 999L), invite(rig))

            assertNotNull(
                "a reply mined before the invitation was taken for the answer to it because its block time is later",
                rig.phone.needsJoin[GID],
            )
            assertNothingSentOrPaid(rig, "the pass with an older block and a later time")
        }
}
