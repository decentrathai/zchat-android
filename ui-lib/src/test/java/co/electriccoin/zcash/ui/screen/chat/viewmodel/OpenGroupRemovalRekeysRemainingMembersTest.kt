package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.W
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GR-OPEN G2, finding 4b (fold-specs/groups SPEC §2.1-§2.4, review D and E): after a removal in an all-Open group the
 * creator cannot send.
 *
 * At HEAD `rotateAndNotify` sends the new key only to members with a key exchange (a signed control). Every member of an
 * all-Open group has none, so each one is marked owed the new key, `cannotReadMembers` leaves it out of the recipients,
 * and the creator's next message ends as NoActiveRecipients(cannotRead = [B]). The owed-key retry needs a key exchange
 * too, so nothing ever repairs it.
 *
 * Wanted: a member with no session, no E2E key of ours for any of its addresses and a relay key gets the new key as a
 * sealed control (GROUP_KICK or GROUP_KEY with `"gk"`, `"sig": ""`) through `sendGroupMemoWithRetry(...,
 * mayPayOnChain = { false })`: over the relays only, never built or paid on-chain, refused with a Keystone account
 * selected (review D). The new key is never sent to a relay key of the removed member or of any LEFT row. A member counts
 * as reachable for the no-key question and the recipients only while the relays can reach it NOW (review E), so with the
 * relays down it is still asked about, left owed, and never quoted on-chain for a message it cannot read.
 *
 * Real GroupViewModel (and the members' ChatViewModels) over the MA3 phones; the relays are [RelayNet], the wallet a
 * recording seam. All addresses, keys and ids are synthetic; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupRemovalRekeysRemainingMembersTest {
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

    private fun fixture(selectedAccount: (suspend () -> WalletAccount)? = null) =
        GroupPaidOnceFixture(selectedAccount = selectedAccount).apply {
            scheduler = this@OpenGroupRemovalRekeysRemainingMembersTest.scheduler
        }

    private class Admin(
        val phone: AnnouncerPhone,
        val vm: GroupViewModel,
        val f: GroupPaidOnceFixture,
        val seam: AnnounceSeam,
    )

    private suspend fun admin(
        members: List<String>,
        extraRelayKeys: Map<String, String> = emptyMap(),
        selectedAccount: (suspend () -> WalletAccount)? = null,
        adjust: (AnnouncerPhone) -> Unit = {},
    ): Admin {
        val phone = OpenGroup.creator(members, extraRelayKeys)
        adjust(phone)
        val f = fixture(selectedAccount)
        val seam = AnnounceSeam()
        return Admin(phone, MA3.groupVm(phone, f, seam), f, seam)
    }

    private fun row(
        a: Admin,
        address: String,
    ): GroupMember =
        a.phone.base
            .roster(GID)
            .single { it.address == address }

    /** The sealed controls carrying the new key (a `gk`) published to [npub]. */
    private fun keyCopies(npub: String): List<JSONObject> =
        (
            OpenGroup.published(net, GroupMessageType.GROUP_KICK, npub) +
                OpenGroup.published(net, GroupMessageType.GROUP_KEY, npub)
        ).filter { it.has("gk") }

    private suspend fun sendMessage(
        a: Admin,
        text: String,
    ) {
        a.f.awaitNewJobs(a.vm) { a.vm.sendGroupMessage(GID, text) }
        if (a.vm.paidActionConfirmation.value != null) a.f.awaitNewJobs(a.vm) { a.vm.confirmPaidAction() }
    }

    private fun onChainKeyMemos(a: Admin): List<PaidMemo> =
        a.seam.proposals.filter {
            val type = ZMSGGroupProtocol.parseMessageType(it.memo)
            type == GroupMessageType.GROUP_KICK || type == GroupMessageType.GROUP_KEY
        }

    @Test
    fun `K1 no sessions, relay keys - kicking X sends B a sealed GROUP_KICK with gk, B is keyed, the next message reaches B, 0 on-chain`() =
        runBlocking {
            val a = admin(listOf(B, X))

            val asked = OpenGroup.kick(a.vm, a.f, X)

            val toB = keyCopies(OpenGroup.npub(B))
            assertEquals("B was not sent the new key over the relays: ${net.to(OpenGroup.npub(B))}", 1, toB.size)
            val kick = toB.single()
            assertEquals(X, kick.getString("kicked"))
            assertEquals(C, kick.getString("kicker"))
            assertEquals(2, kick.getInt("new_epoch"))
            assertEquals("the sealed kick's key is not the creator's new key", a.phone.base.keys["$GID|2"], kick.getString("gk"))
            assertEquals("", kick.optString("sig", "missing"))
            assertFalse("a no-key question was asked although B has a free relay route", asked)
            assertNull("B is still owed the new key", row(a, B).pendingKeyEpoch)

            val cost = a.vm.groupMessageCost(GID)
            assertEquals("B is counted as a member who can't read new messages", 0, cost.cannotRead)
            assertEquals(0, cost.onChainMessages)
            assertEquals(1, cost.recipients)

            net.clear()
            sendMessage(a, "admin after the removal")
            assertTrue(
                "the creator's message did not reach B: ${a.vm.groupSendEvent.value}",
                net.ofType(GroupMessageType.GROUP_MSG, OpenGroup.npub(B)).isNotEmpty(),
            )
            assertTrue("something was paid on-chain: ${a.seam.proposals}", a.seam.proposals.isEmpty())
        }

    @Test
    fun `K2 B's phone adopts the epoch-2 key from the sealed kick, marks X LEFT, raises its floor, reads the next message`() =
        runBlocking {
            val a = admin(listOf(B, X))
            val (bPhone, bChat) = OpenGroup.plainMember(B)

            OpenGroup.kick(a.vm, a.f, X)
            val kicks = net.ofType(GroupMessageType.GROUP_KICK, OpenGroup.npub(B))
            assertTrue("fixture: no kick reached B over the relays", kicks.isNotEmpty())
            kicks.forEach { MA3.relay(bChat, it, sender = C) }

            assertEquals("B did not adopt the new key epoch", 2, bPhone.epochs[GID])
            assertEquals(a.phone.base.keys["$GID|2"], bPhone.keys["$GID|2"])
            assertEquals("B's phone does not list X as removed", MemberStatus.LEFT, bPhone.status(GID, X))
            assertEquals("B's removal floor did not move", 2, bPhone.floors[GID])

            net.clear()
            sendMessage(a, "admin at epoch 2")
            val msgs = net.ofType(GroupMessageType.GROUP_MSG, OpenGroup.npub(B))
            assertTrue("fixture: the admin's message was not published to B", msgs.isNotEmpty())
            msgs.forEach { MA3.relay(bChat, it, sender = C) }
            assertTrue(
                "B's phone cannot read the admin's epoch-2 message",
                OpenGroup.rows(bPhone.messages).any { it.optString("decrypted") == "admin at epoch 2" },
            )
        }

    @Test
    fun `K3 no rekey question when every key holder has a session or a relay key - it still names a holder with neither`() =
        runBlocking {
            // (a) B and W have relay keys: no question.
            val a = admin(listOf(B, W, X))
            run {
                a.f.awaitNewJobs(a.vm) { a.vm.kickMember(GID, X) }
                assertNull(
                    "a no-key question names members the relays can reach: ${a.vm.rekeyUnkeyableConfirmation.value}",
                    a.vm.rekeyUnkeyableConfirmation.value,
                )
            }

            // (b) R has neither a session nor a relay key: it alone is named.
            val b = admin(listOf(B, MEMBER_R, X))
            b.phone.base.nostrKeys
                .remove(MEMBER_R)
            b.f.awaitNewJobs(b.vm) { b.vm.kickMember(GID, X) }
            val question = b.vm.rekeyUnkeyableConfirmation.value
            assertNotNull("R, who can't be sent the new key at all, is not named", question)
            assertEquals(listOf(R_NAME), question!!.names)
        }

    @Test
    fun `K3b relays not ready - the removal question still names B and B stays owed`() =
        runBlocking {
            val a = admin(listOf(B, X))
            NostrChatBridge.unregisterPublisher()

            a.f.awaitNewJobs(a.vm) { a.vm.kickMember(GID, X) }
            val question = a.vm.rekeyUnkeyableConfirmation.value
            assertNotNull("with the relays down B can't be sent the key, but nobody is asked (review E)", question)
            assertEquals(listOf(B_NAME), question!!.names)
            a.f.awaitNewJobs(a.vm) { a.vm.confirmRekeyUnkeyable() }
            if (a.vm.paidActionConfirmation.value != null) a.f.awaitNewJobs(a.vm) { a.vm.confirmPaidAction() }

            assertEquals("B is not owed the new key", 2, row(a, B).pendingKeyEpoch)
            assertTrue("something was paid on-chain", a.seam.proposals.isEmpty())
        }

    @Test
    fun `K4 security - the new key is never published to X's relay key, nor to a second row sharing X's relay key`() =
        runBlocking {
            // W's relay key is X's (a contact code bound it twice).
            val a = admin(listOf(B, W, X), extraRelayKeys = mapOf(W to OpenGroup.npub(X)))

            OpenGroup.kick(a.vm, a.f, X)

            assertEquals("fixture: B was not sent the new key (the sealed route does not exist)", 1, keyCopies(OpenGroup.npub(B)).size)
            assertTrue(
                "the new key was published to the removed member's relay key: ${net.to(OpenGroup.npub(X))}",
                keyCopies(OpenGroup.npub(X)).isEmpty(),
            )
            assertEquals("W (sharing X's relay key) is not left owed", 2, row(a, W).pendingKeyEpoch)
        }

    @Test
    fun `K5 the owed-key retry on screen open sends the current key free to an owed member with no session - 0 on-chain`() =
        runBlocking {
            val a =
                admin(listOf(B)) { phone ->
                    // The group is at epoch 2 and B is still owed that key (the relays were down at the removal).
                    phone.base.keys["$GID|2"] = AnnounceDriver.newGroupKey()
                    phone.base.epochs[GID] = 2
                    val roster = phone.base.roster(GID).map { if (it.address == B) it.copy(pendingKeyEpoch = 2) else it }
                    phone.base.rosters[GID] = ZMSGGroupProtocol.serializeGroupMembers(roster)
                }

            MA3.load(a.vm, a.f)

            val toB = keyCopies(OpenGroup.npub(B))
            assertEquals("the owed key was not re-sent free over the relays: ${net.to(OpenGroup.npub(B))}", 1, toB.size)
            val copy = toB.single()
            assertEquals(2, copy.getInt("epoch"))
            assertEquals(a.phone.base.keys["$GID|2"], copy.getString("gk"))
            assertNull("B is still owed after the free delivery", row(a, B).pendingKeyEpoch)
            assertTrue("something was paid on-chain", a.seam.proposals.isEmpty())
        }

    @Test
    fun `K7 a sealed GROUP_KEY rotation (rotateGroupKey) reaches B the same way`() =
        runBlocking {
            val a = admin(listOf(B))
            val (bPhone, bChat) = OpenGroup.plainMember(B)

            a.f.awaitNewJobs(a.vm) { a.vm.rotateGroupKey(GID) }
            val asked = a.vm.rekeyUnkeyableConfirmation.value != null
            if (asked) a.f.awaitNewJobs(a.vm) { a.vm.confirmRekeyUnkeyable() }
            if (a.vm.paidActionConfirmation.value != null) a.f.awaitNewJobs(a.vm) { a.vm.confirmPaidAction() }

            val toB = keyCopies(OpenGroup.npub(B))
            assertEquals("the rotation did not reach B over the relays", 1, toB.size)
            assertEquals(C, toB.single().getString("signer"))
            assertEquals(2, toB.single().getInt("epoch"))
            assertEquals("", toB.single().optString("sig", "missing"))
            assertFalse("a no-key question was asked although B has a free relay route", asked)
            assertNull(row(a, B).pendingKeyEpoch)

            net.ofType(GroupMessageType.GROUP_KEY, OpenGroup.npub(B)).forEach { MA3.relay(bChat, it, sender = C) }
            assertEquals("B did not adopt the rotated key", 2, bPhone.epochs[GID])
            assertEquals(a.phone.base.keys["$GID|2"], bPhone.keys["$GID|2"])
            assertTrue("something was paid on-chain", a.seam.proposals.isEmpty())
        }

    @Test
    fun `K8 relays down across the removal - cannotRead 1, nothing quoted on-chain, a send never pays for B, no key memo on-chain`() =
        runBlocking {
            val a = admin(listOf(B, X))
            NostrChatBridge.unregisterPublisher()
            OpenGroup.kick(a.vm, a.f, X)

            val cost = a.vm.groupMessageCost(GID)
            assertEquals(1, cost.cannotRead)
            assertEquals(0, cost.onChainMessages)
            assertEquals(0, cost.recipients)

            sendMessage(a, "admin with the relays down")
            assertNull("a cost was quoted for B", a.vm.paidActionConfirmation.value)
            assertTrue("something was paid on-chain: ${a.seam.proposals}", a.seam.proposals.isEmpty())
            assertTrue("a key memo was built on-chain", onChainKeyMemos(a).isEmpty())
        }

    @Test
    fun `K9 Keystone admin - kickMember publishes nothing, builds nothing on-chain, and the banner names the Keystone refusal`() =
        runBlocking {
            val a = admin(listOf(B, X), selectedAccount = ::keystoneAccount)

            OpenGroup.kick(a.vm, a.f, X)

            assertTrue("a Keystone admin published over the relays: ${net.to(null)}", net.to(null).isEmpty())
            assertTrue("a Keystone admin built something on-chain", a.seam.proposals.isEmpty())
            val event = a.vm.groupSendEvent.value
            assertTrue("no removal banner: $event", event is GroupSendResult.RemovalIncomplete)
            assertEquals(
                "the banner does not name the Keystone refusal",
                ChatSendCopy.GROUP_KEYSTONE_REFUSED,
                (event as GroupSendResult.RemovalIncomplete).refusal,
            )
        }

    @Test
    fun `K10 admin holds B's E2E public key but no session (half KEX) - no sealed form, B asked about and owed, as today`() =
        runBlocking {
            val a = admin(listOf(B, X)) { phone -> phone.base.peerKeys[B] = E2EEncryption.generateKeyPair().publicKey }

            val asked = OpenGroup.kick(a.vm, a.f, X)

            assertTrue("B (half key exchange) was not asked about", asked)
            assertEquals(2, row(a, B).pendingKeyEpoch)
            assertTrue("a sealed key went to a member the admin holds a key for", keyCopies(OpenGroup.npub(B)).isEmpty())
            assertTrue(a.seam.proposals.isEmpty())
        }

    @Test
    fun `K11 the sealed kick never carries gk to a row whose relay key equals any relay key of X or of a LEFT row`() =
        runBlocking {
            // L left earlier; W's relay key is L's.
            val a =
                admin(listOf(B, W, X), extraRelayKeys = mapOf(W to NPUB_L, MEMBER_L to NPUB_L)) { phone ->
                    val roster = phone.base.roster(GID) + OpenGroup.member(MEMBER_L, MemberStatus.LEFT)
                    phone.base.rosters[GID] = ZMSGGroupProtocol.serializeGroupMembers(roster)
                }

            OpenGroup.kick(a.vm, a.f, X)

            assertEquals("fixture: B was not sent the new key (the sealed route does not exist)", 1, keyCopies(OpenGroup.npub(B)).size)
            assertTrue("the new key went to a LEFT row's relay key: ${net.to(NPUB_L)}", keyCopies(NPUB_L).isEmpty())
            assertTrue("the new key went to X's relay key", keyCopies(OpenGroup.npub(X)).isEmpty())
            assertEquals("W (sharing a LEFT row's relay key) is not left owed", 2, row(a, W).pendingKeyEpoch)
        }

    @Test
    fun `K12 B's sealed key was not acked at the removal - the next message first re-sends B the key free, then reaches B`() =
        runBlocking {
            val a = admin(listOf(B, X))
            net.acks = { npub -> if (npub == OpenGroup.npub(B)) 0 else 1 }
            OpenGroup.kick(a.vm, a.f, X)
            assertEquals("fixture: B is owed the new key after an unacked sealed kick", 2, row(a, B).pendingKeyEpoch)

            net.acks = { 1 }
            net.clear()
            sendMessage(a, "admin after a failed sealed delivery")

            val toB = net.to(OpenGroup.npub(B))
            val keyAt = toB.indexOfFirst { ZMSGGroupProtocol.parseMessageType(it) == GroupMessageType.GROUP_KEY }
            val msgAt = toB.indexOfFirst { ZMSGGroupProtocol.parseMessageType(it) == GroupMessageType.GROUP_MSG }
            assertTrue("B was not re-sent the key before the message: $toB", keyAt >= 0 && msgAt > keyAt)
            val copy = keyCopies(OpenGroup.npub(B)).single()
            assertEquals(2, copy.getInt("epoch"))
            assertEquals(a.phone.base.keys["$GID|2"], copy.getString("gk"))
            assertNull("B is still owed after the free re-send", row(a, B).pendingKeyEpoch)
            assertTrue("something was paid on-chain: ${a.seam.proposals}", a.seam.proposals.isEmpty())
        }

    private suspend fun keystoneAccount(): WalletAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-gropen-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-gropen-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }

    private companion object {
        const val MEMBER_R = "usyntheticgropenmemberr000001"
        const val MEMBER_L = "usyntheticgropenmemberl000001"
        val NPUB_L = "6".repeat(64)

        /** How the group screens name a member with no nickname and no saved contact: GroupMember.displayName. */
        val R_NAME = "${MEMBER_R.take(8)}...${MEMBER_R.takeLast(6)}"
        val B_NAME = "${B.take(8)}...${B.takeLast(6)}"
    }
}
