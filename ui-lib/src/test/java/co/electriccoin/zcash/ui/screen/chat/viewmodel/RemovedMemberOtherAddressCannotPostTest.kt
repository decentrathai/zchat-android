package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B2
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X2
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
import java.time.Instant

/**
 * GR-OPEN G1 (fold-specs/groups SPEC §1, review A-C; Fold E2 finding 1, HIGH): a removed member keeps posting when its
 * messages come from another address of its wallet.
 *
 * At HEAD, ChatViewModel.processGroupMsg's removal gate matches LEFT rows by canonical address only, and the D4
 * epoch-floor gate drops a pre-removal-key message only when its author is NOT verified. Over the relays the author is
 * always "verified" (the seal names the address the relay key is bound to), so a removed member X posting under the old
 * key from X2, another address of its wallet with its own relay identity (no row, no alias), is stored and shown on the
 * creator's phone. The seal proves who sent it, not that the sender is a member.
 *
 * Wanted: on a phone whose roster is the whole group (the creator's, or a member's whose roster the creator announced),
 * a message that decrypts only under a key from before a removal, from a sender who is no key-holding row, is HELD:
 * stored with `"held": "removal"`, not shown, and released (shown, at its original time) when the same authenticated
 * sender later posts at an epoch at or above the floor. On any phone, a relay message whose seal key is the relay key of
 * a LEFT row (and of no non-LEFT key-holding row) is held the same way. Held rows are capped at 50 per group; the history
 * rescan keeps the mark.
 *
 * Controls (must stay): a key-holding row's old-key message (B lags), no removal ever, R9-3's mined-before messages
 * (unsigned AND signed, review B), our own old-key message in the rescan, a member phone without an announced roster,
 * a relay key shared with an ACTIVE key-holding row (review C).
 *
 * Real ChatViewModel and GroupViewModel over the MA2/MA3 phones. All addresses, keys and ids are synthetic; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovedMemberOtherAddressCannotPostTest {
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
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@RemovedMemberOtherAddressCannotPostTest.scheduler }

    private class Creator(
        val admin: AnnouncerPhone,
        val chat: ChatViewModel,
    ) {
        fun key(epoch: Int): String = admin.base.keys.getValue("$GID|$epoch")

        val messages: Map<String, String> get() = admin.base.messages
    }

    /** The creator of an all-Open group of B and X (no key exchange, relay keys), after it removed X. */
    private suspend fun creatorAfterRemovingX(): Creator {
        val admin =
            OpenGroup.creator(
                listOf(B, X),
                extraRelayKeys = mapOf(X2 to OpenGroup.NPUB_X2, B2 to OpenGroup.NPUB_B2),
            )
        val f = fixture()
        val vm = MA3.groupVm(admin, f, AnnounceSeam())
        OpenGroup.kick(vm, f, X)
        assertEquals("fixture: the removal raised the creator's floor", 2, admin.base.floors[GID])
        assertEquals("fixture: X is LEFT", MemberStatus.LEFT, admin.status(X))
        return Creator(admin, MA3.chatVm(admin))
    }

    /** What a new group screen of the creator lists. */
    private suspend fun listed(admin: AnnouncerPhone): List<String> {
        val f = fixture()
        val vm = MA3.groupVm(admin, f, AnnounceSeam())
        return MA3
            .load(vm, f)
            ?.messages
            ?.mapNotNull { it.decryptedContent }
            .orEmpty()
    }

    private fun texts(messages: Map<String, String>): List<String> = OpenGroup.rows(messages).map { it.optString("decrypted") }

    private var seq = 100L

    private fun relayGm(
        c: Creator,
        sender: String,
        text: String,
        epoch: Int,
        timeMs: Long = MA3.now(),
    ) = MA3.relay(c.chat, OpenGroup.gm(c.key(epoch), sender, text, epoch, seq++), sender = sender, timeMs = timeMs)

    // ------------------------------------------------------------------ R1: held

    @Test
    fun `R1a creator - after removing X, an old-key relay message sealed by X2 (no row, no alias, own relay key) is held`() =
        runBlocking {
            val c = creatorAfterRemovingX()

            relayGm(c, X2, "X2 after the removal", epoch = 1)

            assertEquals(
                "the old-key relay message of X2 (not a key-holding row) is not stored held: ${OpenGroup.row(
                    c.messages,
                    "X2 after the removal"
                )}",
                "removal",
                OpenGroup.heldMark(c.messages, "X2 after the removal"),
            )
            assertFalse("the group screen shows it", listed(c.admin).contains("X2 after the removal"))
            assertTrue(
                "X2 was added to the creator's roster",
                c.admin.base
                    .roster(GID)
                    .none { it.address == X2 }
            )
        }

    @Test
    fun `R1b creator - the same message on-chain with X2's valid pairwise signature is held`() =
        runBlocking {
            val c = creatorAfterRemovingX()
            // The creator holds a key-exchange key for X2, so its signature verifies: an authenticated, not a member.
            val x2: E2EKeyPair = c.admin.base.link(X2)
            val memo =
                OpenGroup.gm(c.key(1), X2, "X2 signed after the removal", epoch = 1, seq = seq++) { data ->
                    E2EEncryption.sign(x2.privateKey, data)
                }

            AnnounceDriver.receive(c.chat, memo, Instant.ofEpochMilli(MA3.now()), AnnounceDriver.txId(11), minedHeight = 9_000_000L)

            assertEquals("removal", OpenGroup.heldMark(c.messages, "X2 signed after the removal"))
            assertFalse(listed(c.admin).contains("X2 signed after the removal"))
        }

    /** A member's phone (AnnounceDriver's group) with X and Y as rows, after a verified relay kick of X to epoch 2. */
    private suspend fun memberAfterKick(
        announced: Boolean,
        relayKeys: Map<String, String> = emptyMap(),
    ): Pair<AnnouncePhone, ChatViewModel> {
        val phone = AnnouncePhone()
        val admin =
            AnnounceDriver.memberPhone(
                phone,
                extraRows = listOf(OpenGroup.member(AnnounceDriver.X), OpenGroup.member(AnnounceDriver.Y)),
            )
        if (announced) phone.states[AnnounceDriver.GID] = phone.state(AnnounceDriver.GID).copy(rosterAnnounced = true)
        relayKeys.forEach { (address, key) -> phone.nostrKeys[address] = key }
        val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
        AnnounceDriver.receive(
            chat,
            AnnounceDriver.kick(phone, admin, AnnounceDriver.X, newEpoch = 2, newKey = AnnounceDriver.newGroupKey()),
            Instant.ofEpochMilli(MA3.now() - 60_000L),
            sender = AnnounceDriver.ADMIN,
            eventId = "synthetic-gropen-kick-2",
        )
        assertEquals("fixture: the kick raised the floor", 2, phone.floors[AnnounceDriver.GID])
        return phone to chat
    }

    private fun memberRelay(
        phone: AnnouncePhone,
        chat: ChatViewModel,
        sender: String,
        text: String,
        epoch: Int = 1,
    ) = AnnounceDriver.receive(
        chat,
        AnnounceDriver.gm(phone, sender, text, epoch = epoch, seq = seq++),
        Instant.ofEpochMilli(MA3.now()),
        sender = sender,
        eventId = "synthetic-gropen-relay-${seq++}",
    )

    private fun memberRows(phone: AnnouncePhone) = OpenGroup.rows(phone.messages, AnnounceDriver.GID)

    private fun memberHeld(
        phone: AnnouncePhone,
        text: String,
    ): String? = memberRows(phone).firstOrNull { it.optString("decrypted") == text }?.optString("held")?.takeIf { it.isNotEmpty() }

    private fun memberStored(
        phone: AnnouncePhone,
        text: String,
    ): Boolean = memberRows(phone).any { it.optString("decrypted") == text }

    @Test
    fun `R1c member with the announced roster - a verified kick of X, then X2's old-key relay message is held`() =
        runBlocking {
            val (phone, chat) = memberAfterKick(announced = true, relayKeys = mapOf(AnnounceDriver.X2 to OpenGroup.NPUB_X2))

            memberRelay(phone, chat, AnnounceDriver.X2, "X2 on an announced roster")

            assertEquals("removal", memberHeld(phone, "X2 on an announced roster"))
        }

    @Test
    fun `R1d any phone - a relay message sealed by a LEFT row's relay key (no key holder's) is held, whatever address it maps to`() =
        runBlocking {
            // X's relay key is stored under X2 as well (a contact code bound it twice); no announced roster.
            val shared = "4".repeat(64)
            val (phone, chat) =
                memberAfterKick(
                    announced = false,
                    relayKeys = mapOf(AnnounceDriver.X to shared, AnnounceDriver.X2 to shared),
                )

            memberRelay(phone, chat, AnnounceDriver.X2, "sealed by the removed member's relay key")

            assertEquals("removal", memberHeld(phone, "sealed by the removed member's relay key"))
        }

    // ------------------------------------------------------------------ controls

    @Test
    fun `C1 control - B (key-holding row, its own row address) posting under the old key after X's removal is shown`() =
        runBlocking {
            val c = creatorAfterRemovingX()

            relayGm(c, B, "B lags under the old key", epoch = 1)

            assertTrue(texts(c.messages).contains("B lags under the old key"))
            assertNull("B's message is held", OpenGroup.heldMark(c.messages, "B lags under the old key"))
            assertTrue(listed(c.admin).contains("B lags under the old key"))
        }

    @Test
    fun `C1b control - E2 run 1 - B posts as B2, X is removed, B gets the sealed epoch-2 key, then posts at epoch 2 as B2 - shown`() =
        runBlocking {
            val admin =
                OpenGroup.creator(
                    listOf(B, X),
                    extraRelayKeys = mapOf(B2 to OpenGroup.NPUB_B2),
                )
            val (bPhone, bChat) = OpenGroup.plainMember(B)
            val f = fixture()
            val vm = MA3.groupVm(admin, f, AnnounceSeam())
            val c = Creator(admin, MA3.chatVm(admin))
            relayGm(c, B2, "B2 before the removal", epoch = 1, timeMs = MA3.now() - 120_000L)
            assertTrue("fixture: before any removal B2's message is shown", listed(admin).contains("B2 before the removal"))

            OpenGroup.kick(vm, f, X)
            val toB = net.ofType(GroupMessageType.GROUP_KICK, OpenGroup.npub(B))
            assertTrue("the creator did not send B the new key over the relays (B has no key exchange): $toB", toB.isNotEmpty())
            // B wrote once more under the old key before its phone took the new one: held on the creator until B posts
            // under the new key.
            relayGm(c, B2, "B2 after the removal, old key", epoch = 1, timeMs = MA3.now() - 30_000L)
            toB.forEach { MA3.relay(bChat, it, sender = C) }
            assertEquals("B's phone did not adopt the epoch-2 key", 2, bPhone.epochs[GID])
            assertEquals("B's epoch-2 key is not the creator's", admin.base.keys["$GID|2"], bPhone.keys["$GID|2"])

            MA3.relay(
                c.chat,
                OpenGroup.gm(bPhone.keys.getValue("$GID|2"), B2, "B2 at the new key", epoch = 2, seq = seq++),
                sender = B2,
            )

            val shown = listed(admin)
            assertTrue("B2's epoch-2 message is not shown: $shown", shown.contains("B2 at the new key"))
            assertTrue("B2's message from before the removal is no longer shown: $shown", shown.contains("B2 before the removal"))
            assertTrue("B2's old-key message after the removal was not released: $shown", shown.contains("B2 after the removal, old key"))
        }

    @Test
    fun `C2 control - no removal ever (floor 0) - X2's message is shown as today`() =
        runBlocking {
            val admin = OpenGroup.creator(listOf(B, X), extraRelayKeys = mapOf(X2 to OpenGroup.NPUB_X2))
            val c = Creator(admin, MA3.chatVm(admin))

            relayGm(c, X2, "X2 with no removal", epoch = 1)

            assertNull(OpenGroup.heldMark(c.messages, "X2 with no removal"))
            assertTrue(listed(admin).contains("X2 with no removal"))
        }

    /** A member's phone with the announced roster after an on-chain kick of X mined at [KICK_HEIGHT]. */
    private suspend fun announcedMemberAfterOnChainKick(): Triple<AnnouncePhone, ChatViewModel, E2EKeyPair> {
        val phone = AnnouncePhone()
        val admin =
            AnnounceDriver.memberPhone(
                phone,
                extraRows = listOf(OpenGroup.member(AnnounceDriver.X), OpenGroup.member(AnnounceDriver.Y)),
            )
        phone.states[AnnounceDriver.GID] = phone.state(AnnounceDriver.GID).copy(rosterAnnounced = true)
        val x2 = phone.link(AnnounceDriver.X2)
        val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
        AnnounceDriver.receive(
            chat,
            AnnounceDriver.kick(phone, admin, AnnounceDriver.X, newEpoch = 2, newKey = AnnounceDriver.newGroupKey()),
            OpenGroupChain.blockTime(1),
            AnnounceDriver.txId(1),
            minedHeight = KICK_HEIGHT,
        )
        assertEquals("fixture: the on-chain kick raised the floor", 2, phone.floors[AnnounceDriver.GID])
        return Triple(phone, chat, x2)
    }

    @Test
    fun `C3 control - X2's unsigned old-key message mined before the removal (R9-3) is shown`() =
        runBlocking {
            val (phone, chat, _) = announcedMemberAfterOnChainKick()
            val memo = AnnounceDriver.gm(phone, AnnounceDriver.X2, "X2 unsigned, mined before", epoch = 1, seq = seq++)

            AnnounceDriver.receive(chat, memo, OpenGroupChain.blockTime(2), AnnounceDriver.txId(2), minedHeight = KICK_HEIGHT - 10)

            assertTrue(memberStored(phone, "X2 unsigned, mined before"))
            assertNull(memberHeld(phone, "X2 unsigned, mined before"))
        }

    @Test
    fun `C3b control - X2's SIGNED (verified) old-key message mined before the removal is shown, live and in the history rescan`() =
        runBlocking {
            val (phone, chat, x2) = announcedMemberAfterOnChainKick()
            val live = AnnounceDriver.gm(phone, AnnounceDriver.X2, "X2 signed, mined before (live)", signer = x2, epoch = 1, seq = seq++)
            AnnounceDriver.receive(chat, live, OpenGroupChain.blockTime(3), AnnounceDriver.txId(3), minedHeight = KICK_HEIGHT - 10)
            assertTrue("the live path dropped it", memberStored(phone, "X2 signed, mined before (live)"))
            assertNull("the live path held it (review B)", memberHeld(phone, "X2 signed, mined before (live)"))

            val scanned =
                AnnounceDriver.gm(phone, AnnounceDriver.X2, "X2 signed, mined before (rescan)", signer = x2, epoch = 1, seq = seq++)
            val f = fixture()
            val vm = AnnounceDriver.groupVm(phone, f)
            OpenGroupChain.rescan(
                vm,
                listOf(OpenGroupChain.received(4, scanned, KICK_HEIGHT - 10)),
                groupId = AnnounceDriver.GID,
            )
            assertTrue("the rescan dropped it (review B)", memberStored(phone, "X2 signed, mined before (rescan)"))
            assertNull("the rescan held it (review B)", memberHeld(phone, "X2 signed, mined before (rescan)"))
            val shown =
                AnnounceDriver
                    .loadedConversation(vm, f)
                    ?.messages
                    ?.mapNotNull { it.decryptedContent }
                    .orEmpty()
            assertTrue("the group screen does not list it: $shown", shown.contains("X2 signed, mined before (rescan)"))
        }

    @Test
    fun `C4 control - our own old-key message replayed by the scan is shown`() =
        runBlocking {
            val c = creatorAfterRemovingX()
            val own = OpenGroup.gm(c.key(1), C, "our own under the old key", epoch = 1, seq = seq++)
            val f = fixture()
            val vm = MA3.groupVm(c.admin, f, AnnounceSeam())

            OpenGroupChain.rescan(vm, listOf(OpenGroupChain.sent(5, own, 9_000_100L)))

            assertTrue(texts(c.messages).contains("our own under the old key"))
            assertNull(OpenGroup.heldMark(c.messages, "our own under the old key"))
            assertTrue(listed(c.admin).contains("our own under the old key"))
        }

    @Test
    fun `C5 control - member phone without an announced roster - X2 (unknown relay key) is shown as today`() =
        runBlocking {
            val (phone, chat) = memberAfterKick(announced = false, relayKeys = mapOf(AnnounceDriver.X2 to OpenGroup.NPUB_X2))

            memberRelay(phone, chat, AnnounceDriver.X2, "X2 on a lazy roster")

            assertTrue(memberStored(phone, "X2 on a lazy roster"))
            assertNull(memberHeld(phone, "X2 on a lazy roster"))
        }

    @Test
    fun `C6 control - a relay key that belongs to a LEFT row AND to an ACTIVE key-holding row - that row's messages are shown`() =
        runBlocking {
            val shared = "5".repeat(64)
            val (phone, chat) =
                memberAfterKick(
                    announced = false,
                    relayKeys = mapOf(AnnounceDriver.X to shared, AnnounceDriver.Y to shared),
                )

            memberRelay(phone, chat, AnnounceDriver.Y, "Y shares the removed member's relay key")

            assertTrue(memberStored(phone, "Y shares the removed member's relay key"))
            assertNull("an ACTIVE key holder's message is held (review C)", memberHeld(phone, "Y shares the removed member's relay key"))
        }

    // ------------------------------------------------------------------ H: the hold

    @Test
    fun `H1 a held message is released (shown, original time) when the same authenticated sender posts at the floor epoch`() =
        runBlocking {
            val c = creatorAfterRemovingX()
            val heldAt = MA3.now() - 30_000L
            relayGm(c, X2, "X2 held at the old key", epoch = 1, timeMs = heldAt)
            assertEquals("fixture: held", "removal", OpenGroup.heldMark(c.messages, "X2 held at the old key"))

            relayGm(c, X2, "X2 at the new key", epoch = 2)

            assertNull("the held row was not released", OpenGroup.heldMark(c.messages, "X2 held at the old key"))
            val shown = listed(c.admin)
            assertTrue(shown.contains("X2 held at the old key"))
            assertTrue(shown.contains("X2 at the new key"))
            assertEquals(
                "the released row lost its original time",
                heldAt,
                OpenGroup.row(c.messages, "X2 held at the old key")!!.getLong("timestamp"),
            )
        }

    @Test
    fun `H2 a held message is never released by an on-chain unsigned message claiming that sender, nor by a LEFT row`() =
        runBlocking {
            val c = creatorAfterRemovingX()
            relayGm(c, X2, "X2 held (H2)", epoch = 1)
            assertEquals("fixture: held", "removal", OpenGroup.heldMark(c.messages, "X2 held (H2)"))

            val claim = OpenGroup.gm(c.key(2), X2, "unsigned claim of X2 at the new key", epoch = 2, seq = seq++)
            AnnounceDriver.receive(c.chat, claim, Instant.ofEpochMilli(MA3.now()), AnnounceDriver.txId(21), minedHeight = 9_000_200L)
            relayGm(c, X, "the removed row at the new key", epoch = 2)

            assertEquals("an unauthenticated or LEFT sender released it", "removal", OpenGroup.heldMark(c.messages, "X2 held (H2)"))
            assertFalse(listed(c.admin).contains("X2 held (H2)"))
        }

    @Test
    fun `H3 a held message raises no unread count and no chat-list preview, and the history rescan carries the held mark forward`() =
        runBlocking {
            val c = creatorAfterRemovingX()
            relayGm(c, B, "B shown before", epoch = 2, timeMs = MA3.now() - 60_000L)
            relayGm(c, X2, "X2 held (H3)", epoch = 1)
            assertEquals("fixture: held", "removal", OpenGroup.heldMark(c.messages, "X2 held (H3)"))

            val f = fixture()
            val vm = MA3.groupVm(c.admin, f, AnnounceSeam())
            f.awaitNewJobs(vm) { vm.loadGroups() }
            val conversation = vm.groupConversations.value.single { it.groupInfo.groupId == GID }
            assertEquals("the chat list previews the held message", "B shown before", conversation.lastMessage?.decryptedContent)
            assertEquals(0, conversation.unreadCount)

            // Two history merges (each with a message of ours, so the merge runs and writes the store back).
            repeat(2) { pass ->
                val g = fixture()
                val rescanVm = MA3.groupVm(c.admin, g, AnnounceSeam())
                val own = OpenGroup.gm(c.key(2), C, "ours, pass $pass", epoch = 2, seq = 500L + pass)
                OpenGroupChain.rescan(rescanVm, listOf(OpenGroupChain.sent(30 + pass, own, 9_000_300L + pass)))
                assertEquals(
                    "history merge $pass lost the held row or its mark",
                    "removal",
                    OpenGroup.heldMark(c.messages, "X2 held (H3)"),
                )
            }
            assertTrue("fixture: the merges ran", texts(c.messages).contains("ours, pass 1"))
            assertFalse(listed(c.admin).contains("X2 held (H3)"))
        }

    @Test
    fun `H4 at most 50 held rows per group - the oldest held row is dropped first, never a shown row`() =
        runBlocking {
            val c = creatorAfterRemovingX()
            relayGm(c, B, "B shown (H4)", epoch = 2, timeMs = MA3.now() - 600_000L)
            for (i in 1..51) relayGm(c, X2, "X2 held #$i", epoch = 1, timeMs = MA3.now() - 500_000L + i * 1_000L)

            val held = OpenGroup.rows(c.messages).filter { it.optString("held").isNotEmpty() }
            assertEquals("held rows are not capped at 50", 50, held.size)
            assertFalse("the oldest held row was kept", texts(c.messages).contains("X2 held #1"))
            assertTrue("the newest held row was dropped", texts(c.messages).contains("X2 held #51"))
            assertTrue("a shown row was dropped by the cap", texts(c.messages).contains("B shown (H4)"))
        }

    private companion object {
        const val KICK_HEIGHT = 3_000_100L
    }
}
