package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * ZC-897 follow-up (review of T7bG, 19 Oct, finding 1, P1 privacy) — an invite to a contact whose first key exchange
 * still waits for this phone's Accept must not carry the group key anywhere a stranger can read it.
 *
 * Anyone can send a first key exchange that claims contact A's address: it is held for Accept, but its key is stored
 * and the relay key it carries is bound to A at once. At c133b4f22 the invite to such a contact is built in the plain-key
 * form (`group_key`) and the free relay route goes first: the group key is published to the relay key the stranger
 * planted.
 *
 * Wanted: while A's key exchange waits for Accept the invite is held: nothing is published or proposed, the row shows
 * the invite as not sent (Resend after Accept), and the banner says why. The question about the cost is not asked for
 * an invite that is held. After Accept (the key exchange finished) the same add sends the session-wrapped form as
 * before (control).
 *
 * The relay publisher is REGISTERED throughout, so the relay route is live. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteWaitsForTheContactsAcceptTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        AnnounceDriver.relayAcks(published)
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val groupKey = AnnounceDriver.newGroupKey()

    /** The creator's phone with the group (key epoch 0) and contact A, whose key exchange is held or answered. */
    private fun creator(accepted: Boolean): AnnouncePhone {
        val phone = AnnouncePhone(self = CREATOR)
        val t = Instant.ofEpochMilli(AnnounceDriver.T)
        phone.putGroup(
            GroupInfo(groupId = GROUP, name = "Synthetic ZC-897 group", creatorAddress = CREATOR, createdAt = t, isActive = true),
            listOf(GroupMember(address = CREATOR, joinedAt = t, status = MemberStatus.ACTIVE, isAdmin = true)),
            0,
            groupKey,
        )
        phone.selfCreated += GROUP
        // The key exchange claiming A: its key is stored, and its relay key is bound to A (the stranger's, if forged).
        phone.ourKeys[A] = E2EEncryption.generateKeyPair()
        phone.peerKeys[A] = E2EEncryption.generateKeyPair().publicKey
        phone.nostrKeys[A] = A_RELAY_KEY
        if (!accepted) phone.awaitingAccept[A] = phone.peerKeys.getValue(A)
        return phone
    }

    private suspend fun add(phone: AnnouncePhone): Pair<GroupViewModel, GroupPaidOnceFixture> {
        val f = GroupPaidOnceFixture().apply { scheduler = this@InviteWaitsForTheContactsAcceptTest.scheduler }
        val gvm = AnnounceDriver.groupVm(phone, f)
        f.awaitNewJobs(gvm) { gvm.addMemberToGroup(GROUP, A) }
        if (gvm.paidActionConfirmation.value != null) MA3.confirm(gvm, f)
        return gvm to f
    }

    private fun invitesPublished(): List<JSONObject> =
        published
            .filter { (text, to) -> to == A_RELAY_KEY && ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_INVITE }
            .map { (text, _) -> JSONObject(ZMSGGroupProtocol.parsePayload(text)!!) }

    @Test
    fun `an invite to a contact whose key exchange waits for Accept is held - nothing goes to the bound relay key`() =
        runBlocking {
            val phone = creator(accepted = false)
            val (gvm, f) = add(phone)

            assertTrue(
                "the invite was published to the relay key bound by a held key exchange: " +
                    "${invitesPublished().map { it.keys().asSequence().toList() }}",
                invitesPublished().isEmpty(),
            )
            assertTrue(
                "a plain group key went over the relays",
                published.none { (text, _) -> text.contains(groupKey) },
            )
            assertEquals("an invite that is held was proposed for payment", 0, f.seam.proposalRequests.get())
            val result = gvm.groupSendEvent.value
            assertNotNull("the user is not told that the invite was not sent", result)
            assertEquals("InviteFailed", result!!.javaClass.simpleName)
            val refusal = result.javaClass.getMethod("getRefusal").invoke(result) as String?
            assertEquals(
                "the banner does not say that the invite waits for the contact's Accept",
                runCatching {
                    Class
                        .forName("co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy")
                        .getField("GROUP_INVITE_WAITS_FOR_ACCEPT")
                        .get(null)
                }.getOrNull(),
                refusal,
            )
            assertFalse(
                "the invite is shown as sent",
                phone.roster(GROUP).any { it.address == A && it.inviteStatus == InviteStatus.SENT },
            )
        }

    @Test
    fun `control - after Accept the same add sends the session-wrapped invite`() =
        runBlocking {
            val phone = creator(accepted = true)
            add(phone)

            val invites = invitesPublished()
            assertEquals("the invite did not go once over the relays", 1, invites.size)
            assertTrue("the invite is not session-wrapped", invites.single().has("k2"))
            assertFalse("the invite carries the plain key", invites.single().has("group_key"))
        }

    private companion object {
        const val GROUP = "zgrp_8971a1b2c3d4e5f6a7b8c9d0"
        const val CREATOR = "usyntheticzc897creator00002"
        const val A = "usyntheticzc897contacta0001"
        val A_RELAY_KEY = "9f".repeat(32)
    }
}
