package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * ZC-633 (HIGH) — a group invitation read before the key that unwraps it is stored.
 *
 * The history pass reads group memos inside its loop. When this phone does not yet hold the session that unwraps the
 * invitation's key (the inviter's key is not stored yet: the key-exchange answer of the same pass is stored later, or a
 * restore recovers this phone's own key pairs after the loop), processGroupInviteLocked still saves the group: active,
 * with no key and no creator key, the invitation's id recorded as used, and no "I joined". Every later read of the same
 * invitation takes the existing-group branch ("already exists, skipping"): nothing ever unwraps the key, the admin's
 * phone never hears "I joined", and the group never works.
 *
 * Wanted (FIRST-CONTACT-SPEC 5.7 / 6.6): when the same on-chain invitation (its id is the record's used id, its source
 * time is the record's creation time, its inviter is the record's creator, its key number not below the stored one) is
 * read again by a history pass and now unwraps, the join is finished at the end of that pass: the key at the
 * invitation's number, the creator key of the unwrapping session, and the join's own reply (free over the relays, or
 * the Join banner's waiting record when there is no relay route; nothing is ever proposed or paid). No reply at all when
 * the same pass read this phone's own sent "I joined" for the group (a restore: the admin already has it). Nothing is
 * taken from another invitation, a relay copy, a group that has its key, or an invitation that still does not unwrap.
 *
 * QA2 U9 review follow-up (W-137, ZC-922, finding 2): the finished join is a group invite with a key, so it waits for the
 * user's Join like every other one (the join's own reply is no longer sent by the pass): the group is saved and joined as
 * before, and a fresh waiting record (nothing tried yet) is written; Join sends the same reply, free over the relays when
 * a relay takes it. With no relay route, the first Join pays nothing and puts the reply back with the failed relay try (the
 * paid banner shows its price), and only the second Join pays it once. No reply and no record when the pass read our own
 * "I joined" for the group.
 *
 * All addresses, keys, txids and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteReadBeforeItsKeyStillJoinsTest {
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

    private val inviteTx: TransactionId = AnnounceDriver.txId(633)
    private val otherInviteTx: TransactionId = AnnounceDriver.txId(634)
    private val ownAcceptTx: TransactionId = AnnounceDriver.txId(635)
    private val invitedAt: Instant = Instant.ofEpochMilli(T + 6_330_000)
    private val epoch = 3
    private val groupKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 11 + 3).toByte() })

    /** This phone and the creator: our key pair for the creator is stored, the creator's key is NOT yet. */
    private class Rig(
        val phone: AnnouncePhone,
        val seam: HandshakeSeam,
        val vm: ChatViewModel,
        val ours: E2EKeyPair,
        val admin: E2EKeyPair,
    ) {
        /** The session the creator wrapped k2 under (its private key with our public key; the same secret). */
        val session: ByteArray
            get() = E2EEncryption.deriveSharedSecret(ours.privateKey, admin.publicKey, E2EKeyVersion.fromValue(2))

        /** The creator's key is stored now (the key-exchange answer, or a restore's recovery, came after the invite). */
        fun storeCreatorKey() {
            phone.peerKeys[ADMIN] = admin.publicKey
        }
    }

    private suspend fun rig(relayRoute: Boolean = true): Rig {
        val phone = AnnouncePhone()
        val ours = E2EEncryption.generateKeyPair()
        val admin = E2EEncryption.generateKeyPair()
        phone.ourKeys[ADMIN] = ours
        if (relayRoute) phone.nostrKeys[ADMIN] = ADMIN_NOSTR
        val seam = HandshakeSeam(ADMIN)
        val vm = AnnounceDriver.chatVm(phone, seam)
        AnnounceDriver.relayAcks(published)
        return Rig(phone, seam, vm, ours, admin)
    }

    private fun k2Invite(
        rig: Rig,
        key: String = groupKey,
        keyEpoch: Int = epoch,
    ): String = AnnounceDriver.gi(ADMIN, rig.session, key, keyEpoch)

    private fun encKeyInvite(
        rig: Rig,
        key: String = groupKey,
    ): String =
        "ZMSG:3.0:GROUP:GI:$GID:" +
            JSONObject()
                .put("name", "Announce group")
                .put("inviter", ADMIN)
                .put("key_epoch", epoch)
                .put("enc_key", E2EEncryption.encryptGroupKeyForMember(rig.ours.publicKey, Base64.getDecoder().decode(key)))
                .toString()

    /** One memo of a history pass: the memo, its transaction, its block time, and whether it is our own send. */
    private class Memo(
        val memo: String,
        val txId: TransactionId,
        val time: Instant,
        val ownSend: Boolean = false,
        val ownRecipient: String? = null,
    )

    /** One whole history pass over [memos], as the pass runs it: begin, the loop's group memos, the end-of-pass hook. */
    private suspend fun pass(
        rig: Rig,
        vararg memos: Memo,
    ) {
        val pass = AdminCheck.beginPass(rig.vm)
        val inPass =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "processGroupMessageInPass" && !it.isSynthetic }
                .apply { isAccessible = true }
        for (m in memos) {
            try {
                inPass.invoke(rig.vm, m.memo, m.txId, m.time, m.ownSend, m.ownRecipient, 1_000L, pass)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        AdminCheck.endPass(rig.vm, pass, true)
        HandshakeHarness.awaitIdle(rig.vm)
    }

    private fun repliesToCreator(): Int =
        published.count { (text, to) -> to == ADMIN_NOSTR && ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_ACCEPT }

    /** The state every case starts from: the invitation was read before its key could be unwrapped (today's pin). */
    private fun assertStuck(
        rig: Rig,
        what: String,
    ) {
        val info = rig.phone.info(GID)
        assertTrue("$what: fixture: the group must be saved active, as today", info.isActive)
        assertNull("$what: fixture: the group must have no key yet", rig.phone.keys["$GID|$epoch"])
        assertNull("$what: fixture: the group must have no creator key yet", info.creatorPublicKey)
        assertEquals("$what: fixture: nothing may be sent for a group without its key", 0, repliesToCreator())
        assertNull("$what: fixture: no waiting record for a group without its key", rig.phone.needsJoin[GID])
        assertEquals("$what: something was proposed for payment", 0, rig.seam.proposalRequests.get())
    }

    private fun assertFinished(
        rig: Rig,
        what: String,
    ) {
        assertEquals("$what: the invitation's key was not taken at its number", groupKey, rig.phone.keys["$GID|$epoch"])
        assertEquals("$what: the group's key number is not the invitation's", epoch, rig.phone.epochs[GID])
        assertEquals("$what: the record does not carry the key", groupKey, rig.phone.info(GID).groupKey)
        assertEquals(
            "$what: the creator key is not the key of the session that unwrapped the invitation",
            rig.admin.publicKey,
            rig.phone.info(GID).creatorPublicKey,
        )
        assertTrue("$what: the group is no longer active", rig.phone.info(GID).isActive)
        assertEquals("$what: something was proposed for payment", 0, rig.seam.proposalRequests.get())
        assertEquals("$what: something was submitted", 0, rig.seam.submits.get())
    }

    // ---- (a) the stuck half --------------------------------------------------------------------------------------

    // [test-change-ok: W-137 ZC-922 ZC-633 review follow-up] (was: the finished join sent "I joined" free at once, no Join)
    @Test
    fun `(a) a k2 invitation read before the creator's key joins at the next pass and waits for Join, which sends I joined once`() =
        runBlocking {
            val rig = rig()
            val invite = Memo(k2Invite(rig), inviteTx, invitedAt)

            pass(rig, invite)
            assertStuck(rig, "pass 1")

            rig.storeCreatorKey()
            pass(rig, invite)

            assertFinished(rig, "the pass after the creator's key was stored")
            assertEquals("the finished join sent 'I joined' before the user tapped Join", 0, repliesToCreator())
            val record = rig.phone.needsJoin[GID]
            assertNotNull("the finished join does not wait for Join", record)
            assertFalse("the waiting record already says a relay try failed", GroupInviteWaitRecord.relayFailed(record))

            pass(rig, invite)
            assertEquals("a later pass sent 'I joined' before Join", 0, repliesToCreator())
            assertEquals("a later pass changed the waiting record", record, rig.phone.needsJoin[GID])

            assertTrue("Join was refused", rig.vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(rig.vm)
            assertEquals("Join did not send the one 'I joined' free over the relays", 1, repliesToCreator())
            assertNull("the waiting record stayed after Join", rig.phone.needsJoin[GID])
            assertEquals("something was proposed for payment", 0, rig.seam.proposalRequests.get())
            assertEquals("something was submitted", 0, rig.seam.submits.get())

            pass(rig, invite)
            assertEquals("a later pass sent 'I joined' again", 1, repliesToCreator())
        }

    // ---- (d) no relay route: the Join banner, nothing proposed -------------------------------------------------------

    @Test
    fun `(d) with no relay route to the creator the finished join waits for Join and proposes nothing`() =
        runBlocking {
            val rig = rig(relayRoute = false)
            val invite = Memo(k2Invite(rig), inviteTx, invitedAt)

            pass(rig, invite)
            assertStuck(rig, "pass 1")

            rig.storeCreatorKey()
            pass(rig, invite)

            assertFinished(rig, "the pass after the creator's key was stored")
            assertNotNull("the reply that cannot go free does not wait for Join", rig.phone.needsJoin[GID])
            assertEquals("a reply went out with no relay route", 0, repliesToCreator())
        }

    // The finished join with no relay route: its paid reply goes through the quote, never at the first Join.
    @Test
    fun `(j) with no relay route the first Join of a finished join pays nothing and keeps the price question, the second Join pays once`() =
        runBlocking {
            val rig = rig(relayRoute = false)
            val invite = Memo(k2Invite(rig), inviteTx, invitedAt)

            pass(rig, invite)
            rig.storeCreatorKey()
            pass(rig, invite)
            assertFinished(rig, "the pass after the creator's key was stored")
            assertFalse(
                "the waiting record already says a relay try failed",
                GroupInviteWaitRecord.relayFailed(rig.phone.needsJoin[GID]),
            )
            assertEquals(0, rig.seam.submits.get())

            assertTrue("the first Join was refused", rig.vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(rig.vm)
            assertEquals("the first Join paid the reply with no price shown", 0, rig.seam.submits.get())
            assertEquals("the first Join built a payment", 0, rig.seam.proposalRequests.get())
            assertEquals("a reply went out with no relay route", 0, repliesToCreator())
            assertTrue(
                "the reply is not back to waiting with its failed relay try (the paid banner and its price)",
                GroupInviteWaitRecord.relayFailed(rig.phone.needsJoin[GID]),
            )

            assertTrue("the second Join was refused", rig.vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(rig.vm)
            assertEquals("the second Join must pay exactly one reply", 1, rig.seam.submits.get())
            assertNull("the waiting record stayed after the paid Join", rig.phone.needsJoin[GID])
        }

    // ---- (c) a restore: our own "I joined" is in the same pass ------------------------------------------------------

    @Test
    fun `(c) when the pass also reads our own sent I joined for the group the join is finished and nothing is sent or kept`() =
        runBlocking {
            val rig = rig()
            val invite = Memo(k2Invite(rig), inviteTx, invitedAt)
            val ownAccept =
                Memo(
                    ZMSGGroupProtocol.createGroupAcceptMessage(GID, rig.phone.self, rig.ours.publicKey, "", epoch),
                    ownAcceptTx,
                    invitedAt.plusSeconds(60),
                    ownSend = true,
                    ownRecipient = ADMIN,
                )

            pass(rig, invite, ownAccept)
            assertStuck(rig, "pass 1 (a restore: the keys are recovered after the loop)")

            rig.storeCreatorKey()
            pass(rig, invite, ownAccept)

            assertFinished(rig, "the pass after the recovery")
            assertEquals("'I joined' was sent again although our own is in the history", 0, repliesToCreator())
            assertNull("a waiting record was left although our own 'I joined' is in the history", rig.phone.needsJoin[GID])
        }

    // ---- (i) the enc_key form -------------------------------------------------------------------------------------

    // [test-change-ok: W-137 ZC-922 ZC-633 review follow-up] (was: the finished join sent "I joined" free at once, no Join)
    @Test
    fun `(i) an enc_key invitation read before our key pair is recovered joins at the next pass and waits for Join`() =
        runBlocking {
            val rig = rig()
            // A restore: this phone's key pair for the creator is recovered after the loop; the creator is a saved contact.
            rig.phone.ourKeys.remove(ADMIN)
            rig.storeCreatorKey()
            AutoSpendVm.setField(rig.vm, "contactBook", SavedContacts(setOf(ADMIN)))
            val invite = Memo(encKeyInvite(rig), inviteTx, invitedAt)

            pass(rig, invite)
            assertStuck(rig, "pass 1")

            rig.phone.ourKeys[ADMIN] = rig.ours
            pass(rig, invite)

            assertEquals("the invitation's key was not taken at its number", groupKey, rig.phone.keys["$GID|$epoch"])
            assertEquals("the group's key number is not the invitation's", epoch, rig.phone.epochs[GID])
            assertEquals("the finished join sent 'I joined' before the user tapped Join", 0, repliesToCreator())
            assertNotNull("the finished join does not wait for Join", rig.phone.needsJoin[GID])
            assertEquals("something was proposed for payment", 0, rig.seam.proposalRequests.get())

            assertTrue("Join was refused", rig.vm.joinGroupInvite(GID))
            HandshakeHarness.awaitIdle(rig.vm)
            assertEquals("Join did not send the one 'I joined' free over the relays", 1, repliesToCreator())
            assertEquals("something was proposed for payment", 0, rig.seam.proposalRequests.get())
        }

    // ---- guards ---------------------------------------------------------------------------------------------------

    @Test
    fun `(f) a different invitation of the same group that unwraps is not taken`() =
        runBlocking {
            val rig = rig()
            pass(rig, Memo(k2Invite(rig), inviteTx, invitedAt))
            assertStuck(rig, "pass 1")

            rig.storeCreatorKey()
            pass(rig, Memo(k2Invite(rig, key = AnnounceDriver.newGroupKey()), otherInviteTx, invitedAt.plusSeconds(5)))

            assertNull("a second invitation of an active group gave it a key", rig.phone.keys["$GID|$epoch"])
            assertNull("a second invitation of an active group gave it a creator key", rig.phone.info(GID).creatorPublicKey)
            assertEquals("a second invitation of an active group sent 'I joined'", 0, repliesToCreator())
            assertNull("a second invitation of an active group left a waiting record", rig.phone.needsJoin[GID])
        }

    @Test
    fun `(g) the creating invitation is not taken when the record's creation time is not the invitation's`() =
        runBlocking {
            val rig = rig()
            val invite = Memo(k2Invite(rig), inviteTx, invitedAt)
            pass(rig, invite)
            assertStuck(rig, "pass 1")
            // A record first seen unmined (or one ZC-790 superseded): the same id is used, the creation time differs.
            val info = rig.phone.info(GID)
            rig.phone.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(info.copy(createdAt = invitedAt.plusSeconds(1)))

            rig.storeCreatorKey()
            pass(rig, invite)

            assertNull("an invitation not tied to the record by its time gave it a key", rig.phone.keys["$GID|$epoch"])
            assertEquals("an invitation not tied to the record sent 'I joined'", 0, repliesToCreator())
        }

    @Test
    fun `(h) a group that has its key is untouched when its invitation is read again`() =
        runBlocking {
            val rig = rig()
            rig.storeCreatorKey()
            val held = AnnounceDriver.newGroupKey()
            rig.phone.putGroup(
                GroupInfo(
                    groupId = GID,
                    name = "Announce group",
                    creatorAddress = ADMIN,
                    createdAt = invitedAt,
                    isActive = true,
                    groupKey = held,
                    creatorPublicKey = rig.admin.publicKey,
                ),
                listOf(
                    GroupMember(address = ADMIN, joinedAt = invitedAt, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = rig.phone.self, joinedAt = invitedAt, status = MemberStatus.ACTIVE),
                ),
                epoch,
                held,
            )
            rig.phone.states[GID] = AnnounceState(usedInviteIds = setOf(inviteTx.txIdString()))

            pass(rig, Memo(k2Invite(rig), inviteTx, invitedAt))

            assertEquals("the key of a group that has its key was replaced", held, rig.phone.keys["$GID|$epoch"])
            assertEquals("'I joined' was sent for a group that has its key", 0, repliesToCreator())
            assertNull("a waiting record was left for a group that has its key", rig.phone.needsJoin[GID])
        }

    @Test
    fun `(k) an invitation that still does not unwrap stays as today`() =
        runBlocking {
            val rig = rig()
            val invite = Memo(k2Invite(rig), inviteTx, invitedAt)
            pass(rig, invite)
            pass(rig, invite)
            assertStuck(rig, "a second pass with no session")
        }

    @Test
    fun `a relay copy of the invitation finishes nothing`() =
        runBlocking {
            val rig = rig()
            pass(rig, Memo(k2Invite(rig), inviteTx, invitedAt))
            assertStuck(rig, "pass 1")

            rig.storeCreatorKey()
            AnnounceDriver.receive(rig.vm, k2Invite(rig), invitedAt, sender = ADMIN, eventId = "synthetic-zc633-relay-invite")
            HandshakeHarness.awaitIdle(rig.vm)

            assertNull("a relay copy gave the group its key", rig.phone.keys["$GID|$epoch"])
            assertEquals("a relay copy sent 'I joined'", 0, repliesToCreator())
        }
}
