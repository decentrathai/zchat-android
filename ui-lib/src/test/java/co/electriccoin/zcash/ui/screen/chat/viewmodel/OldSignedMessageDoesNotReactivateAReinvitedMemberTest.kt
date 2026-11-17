package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-506 Part B (register ZC-508; ZC-507 asks the same question) — on the creator's phone, a message a member sent
 * BEFORE the creator removed it is not that member's answer to a later invitation.
 *
 * The creator removes X (the key rotates from epoch 1 to epoch 2 and the removal is recorded with the epoch it rotated
 * into) and invites X again: X's row is INVITED and X has to tap Join. The scan replays every group memo on every pass,
 * and a relay can hand an old gift wrap back. Today any message from X whose sender is authenticated makes an INVITED
 * row ACTIVE, with no look at the key the message decrypts under, so X's OLD signed message (it decrypts only under the
 * epoch-1 key) puts X back in the group at the next pass with no Join: the creator's phone shows X as joined, tells the
 * other members so, and sends X the group's messages, which X's phone cannot read yet.
 *
 * Wanted: a message that decrypts only under a key older than the removal neither activates the re-invited member nor
 * vouches (AUTH) for a row an unsigned accept made ACTIVE. The message itself is still stored and shown as before. A
 * post that decrypts at or above the removal's epoch is proof that X holds the key only the new invitation gives, and
 * still brings X back (control d). A first-time invitee (control e) and a member who left by itself, where no key
 * rotated (control f, the limit of this rule), behave as before.
 *
 * X's row carries InviteStatus.SENT, as a real re-invite leaves it: with no invite status the old message is held
 * (GR-OPEN) and never reaches the roster step.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldSignedMessageDoesNotReactivateAReinvitedMemberTest {
    private val sendScopes = SendHarnessScopes()

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

    private val leftAt = T + 600_000

    /**
     * The creator's phone after "remove X, invite X again": X is [xStatus] with a sent invite, the group is at epoch
     * [epoch] (the keys of every epoch up to it are held), the removal floor is [floor], and X's announce record is
     * [entry] (null: X was never removed).
     */
    private fun creatorPhone(
        xStatus: MemberStatus = MemberStatus.INVITED,
        epoch: Int = 2,
        floor: Int = 2,
        entry: MemberAnnounceEntry? = removedByAdmin(),
    ): AnnouncePhone {
        val phone = AnnouncePhone()
        phone.selfCreated += GID
        phone.putGroup(
            GroupInfo(groupId = GID, name = "Old message", creatorAddress = phone.self, createdAt = Instant.ofEpochMilli(T)),
            listOf(
                GroupMember(address = phone.self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(
                    address = X,
                    joinedAt = Instant.ofEpochMilli(T),
                    status = xStatus,
                    inviteStatus = InviteStatus.SENT,
                ),
            ),
            epoch,
            AnnounceDriver.newGroupKey(),
        )
        for (e in 1 until epoch) phone.keys["$GID|$e"] = AnnounceDriver.newGroupKey()
        if (floor > 0) phone.floors[GID] = floor
        if (entry != null) phone.states[GID] = phone.state(GID).copy(members = mapOf(X to entry))
        return phone
    }

    /** What a real admin removal leaves in X's announce record: when, the epoch the key rotated into, authenticated. */
    private fun removedByAdmin(): MemberAnnounceEntry =
        MemberAnnounceEntry(
            n = leftAt + 1,
            op = AnnounceOp.R,
            epoch = 2,
            activatedAtMs = T + 1_000,
            activatedBy = ActivatedBy.AUTH,
            leftAtMs = leftAt,
            removedAtEpoch = 2,
            leftBy = ActivatedBy.AUTH,
        )

    private fun storedRows(phone: AnnouncePhone): List<JSONObject> =
        phone.messages[GID]?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.orEmpty()

    @Test
    fun `a - an old signed on-chain message replayed on three passes leaves the re-invited member INVITED, and is stored and shown`() =
        runBlocking {
            val phone = creatorPhone()
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.state(GID)
            val old = AnnounceDriver.gm(phone, X, "sent before the removal", signer = x, epoch = 1)

            repeat(3) { pass ->
                AnnounceDriver.receive(vm, old, Instant.ofEpochMilli(T + 30_000), AnnounceDriver.txId(1))
                assertEquals(
                    "pass ${pass + 1}: a message X sent before its removal put X back in the group without Join",
                    MemberStatus.INVITED,
                    phone.status(GID, X),
                )
                assertEquals("pass ${pass + 1}: X's announce record changed", before, phone.state(GID))
            }
            // The same message mined late (after the removal and the re-invite): still not an answer to the new invite.
            AnnounceDriver.receive(vm, old, Instant.ofEpochMilli(leftAt + 300_000), AnnounceDriver.txId(1))
            assertEquals("an old message mined after the removal put X back", MemberStatus.INVITED, phone.status(GID, X))

            val rows = storedRows(phone)
            assertEquals("the old message is not stored exactly once", 1, rows.size)
            assertEquals("sent before the removal", rows[0].getString("decrypted"))
            assertFalse("the old message of a member with a sent invite is held, not shown", rows[0].has("held"))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `b - the same old message over the relays, a new event each pass, leaves the re-invited member INVITED`() =
        runBlocking {
            val phone = creatorPhone()
            phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.state(GID)
            val old = AnnounceDriver.gm(phone, X, "sent before the removal", epoch = 1)

            repeat(3) { pass ->
                AnnounceDriver.receive(
                    vm,
                    old,
                    Instant.ofEpochMilli(T + 30_000),
                    sender = X,
                    eventId = "synthetic-old-wrap-${pass + 1}",
                )
                assertEquals(
                    "pass ${pass + 1}: an old relay message put X back in the group without Join",
                    MemberStatus.INVITED,
                    phone.status(GID, X),
                )
                assertEquals("pass ${pass + 1}: X's announce record changed", before, phone.state(GID))
            }
            assertEquals("the old relay message is not stored exactly once", 1, storedRows(phone).size)
            assertFalse(storedRows(phone)[0].has("held"))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `c - an old authenticated message does not vouch for a row an unsigned accept made ACTIVE`() =
        runBlocking {
            // After the re-invite an unsigned accept made X ACTIVE (recorded UNAUTH, so X is not announced).
            val unauth = removedByAdmin().copy(activatedAtMs = leftAt + 60_000, activatedBy = ActivatedBy.UNAUTH)
            val phone = creatorPhone(xStatus = MemberStatus.ACTIVE, entry = unauth)
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)
            val before = phone.state(GID)
            val old = AnnounceDriver.gm(phone, X, "sent before the removal", signer = x, epoch = 1)

            // Mined late: its block time is after the removal, so the time rule alone lets it vouch.
            repeat(3) { pass ->
                AnnounceDriver.receive(vm, old, Instant.ofEpochMilli(leftAt + 300_000), AnnounceDriver.txId(1))
                assertEquals(
                    "pass ${pass + 1}: a message from before the removal vouched for X's unsigned comeback",
                    ActivatedBy.UNAUTH,
                    phone.state(GID).members[X]?.activatedBy,
                )
                assertEquals("pass ${pass + 1}: X's announce record changed", before, phone.state(GID))
            }
            assertEquals(MemberStatus.ACTIVE, phone.status(GID, X))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `d - control - a signed post at the current epoch brings the re-invited member back and releases its held rows`() =
        runBlocking {
            val phone = creatorPhone()
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)
            phone.messages[GID] =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "synthetic-held-row")
                            .put("groupId", GID)
                            .put("txId", "synthetic-held-row")
                            .put("seq", 9)
                            .put("epoch", 1)
                            .put("sender", X)
                            .put("decrypted", "held earlier")
                            .put("timestamp", leftAt + 1_000)
                            .put("held", "removal"),
                    ).toString()

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, X, "I am back", signer = x, epoch = 2, seq = 2),
                Instant.ofEpochMilli(leftAt + 300_000),
                AnnounceDriver.txId(2),
            )

            assertEquals("a post under the new key did not bring X back", MemberStatus.ACTIVE, phone.status(GID, X))
            val entry = phone.state(GID).members[X]
            assertEquals(ActivatedBy.AUTH, entry?.activatedBy)
            assertEquals(leftAt + 300_000, entry?.activatedAtMs)
            val rows = storedRows(phone)
            assertEquals(2, rows.size)
            assertTrue("X's held rows were not released by its post at the current epoch", rows.none { it.has("held") })
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `d2 - control - an old-epoch message from a member whose removal is older than its key still brings it back`() =
        runBlocking {
            // The group rotated twice: X was removed at epoch 2, invited again, and the key later rotated to 3 (another
            // member was removed). A message of X under the epoch-2 key is from after X's own removal.
            val phone = creatorPhone(epoch = 3, floor = 3)
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, X, "after my re-invite", signer = x, epoch = 2),
                Instant.ofEpochMilli(leftAt + 300_000),
                AnnounceDriver.txId(3),
            )

            assertEquals(MemberStatus.ACTIVE, phone.status(GID, X))
            assertEquals(ActivatedBy.AUTH, phone.state(GID).members[X]?.activatedBy)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `e - control - a first-time invitee is made ACTIVE by its first signed message`() =
        runBlocking {
            val phone = creatorPhone(epoch = 1, floor = 0, entry = null)
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, X, "hi, I joined", signer = x),
                Instant.ofEpochMilli(T + 1_000),
                AnnounceDriver.txId(1),
            )

            assertEquals(MemberStatus.ACTIVE, phone.status(GID, X))
            assertEquals(ActivatedBy.AUTH, phone.state(GID).members[X]?.activatedBy)
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `f - control - after a member left by itself no key rotated, so its message at that epoch still activates it`() =
        runBlocking {
            // A voluntary leave records the epoch the group was at (no rotation): old and new messages decrypt under the
            // same key, so the epoch cannot tell them apart. Pinned as the limit of this rule (the register has it).
            val left =
                MemberAnnounceEntry(
                    activatedAtMs = T + 1_000,
                    activatedBy = ActivatedBy.AUTH,
                    leftAtMs = leftAt,
                    removedAtEpoch = 1,
                    leftBy = ActivatedBy.AUTH,
                )
            val phone = creatorPhone(epoch = 1, floor = 0, entry = left)
            val x = phone.link(X)
            val seam = HandshakeSeam(X)
            val vm = AnnounceDriver.chatVm(phone, seam)

            AnnounceDriver.receive(
                vm,
                AnnounceDriver.gm(phone, X, "from before I left", signer = x),
                Instant.ofEpochMilli(T + 30_000),
                AnnounceDriver.txId(1),
            )

            assertEquals(MemberStatus.ACTIVE, phone.status(GID, X))
            assertEquals(ActivatedBy.AUTH, phone.state(GID).members[X]?.activatedBy)
            assertEquals(0, seam.proposalRequests.get())
        }
}
