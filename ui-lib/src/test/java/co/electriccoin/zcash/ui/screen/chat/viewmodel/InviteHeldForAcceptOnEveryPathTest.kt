package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopyMarks
import co.electriccoin.zcash.ui.nostr.RelayCopySlots
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-897 follow-up (T7bG review round 2, finding 5) — the invite to a contact whose first key exchange waits for this
 * phone's Accept is held on every path that builds one, not only on Add member: creating a group, Resend, and the
 * Resend of an invite only a public relay took (the relay-copy path). On each, nothing is published to the relay key
 * bound to that contact's address (it may be a stranger's), nothing is proposed for payment, and the banner names the
 * reason. With the relay publisher registered. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteHeldForAcceptOnEveryPathTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val marks = ConcurrentHashMap<String, String>()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        AnnounceDriver.relayAcks(published)
        NostrChatBridge.registerRelayCopyMarks(
            object : RelayCopyMarks {
                override fun get(slot: String): String? = marks[slot]

                override fun put(
                    slot: String,
                    value: String,
                ): Boolean = true.also { marks[slot] = value }

                override fun remove(slot: String) {
                    marks.remove(slot)
                }

                override fun slots(): Set<String> = marks.keys.toSet()
            },
        )
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterRelayCopyMarks()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val groupKey = AnnounceDriver.newGroupKey()

    /** The creator's phone with contact A, whose key exchange waits for Accept and whose relay key is bound. */
    private fun creator(withGroup: Boolean): AnnouncePhone {
        val phone = AnnouncePhone(self = CREATOR)
        phone.ourKeys[A] = E2EEncryption.generateKeyPair()
        phone.peerKeys[A] = E2EEncryption.generateKeyPair().publicKey
        phone.nostrKeys[A] = A_RELAY_KEY
        phone.awaitingAccept[A] = phone.peerKeys.getValue(A)
        if (withGroup) {
            val t = Instant.ofEpochMilli(AnnounceDriver.T)
            phone.putGroup(
                GroupInfo(groupId = GROUP, name = "Synthetic held group", creatorAddress = CREATOR, createdAt = t, isActive = true),
                listOf(
                    GroupMember(address = CREATOR, joinedAt = t, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = A, joinedAt = t, status = MemberStatus.INVITED, inviteStatus = InviteStatus.FAILED),
                ),
                0,
                groupKey,
            )
            phone.selfCreated += GROUP
        }
        return phone
    }

    private suspend fun gvm(phone: AnnouncePhone): Pair<GroupViewModel, GroupPaidOnceFixture> {
        val f = GroupPaidOnceFixture().apply { scheduler = this@InviteHeldForAcceptOnEveryPathTest.scheduler }
        return AnnounceDriver.groupVm(phone, f) to f
    }

    private fun assertHeld(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
        what: String,
    ) {
        val invites =
            published.filter { (text, to) ->
                to == A_RELAY_KEY && ZMSGGroupProtocol.parseMessageType(text) == GroupMessageType.GROUP_INVITE
            }
        assertTrue("$what: an invite went to the relay key a held key exchange bound", invites.isEmpty())
        assertTrue("$what: the group key went over the relays", published.none { (text, _) -> text.contains(groupKey) })
        assertEquals("$what: something was proposed for payment", 0, f.seam.proposalRequests.get())
        val result = vm.groupSendEvent.value
        assertTrue("$what: the user is not told: $result", result is GroupSendResult.InviteFailed)
        val refusal = (result as GroupSendResult.InviteFailed).refusal
        assertEquals("$what: the banner names another reason", ChatSendCopy.GROUP_INVITE_WAITS_FOR_ACCEPT, refusal)
    }

    @Test
    fun `creating a group with a contact whose key exchange waits holds that contact's invite`() =
        runBlocking {
            val phone = creator(withGroup = false)
            val (vm, f) = gvm(phone)
            vm.setGroupName("Synthetic held group")
            vm.toggleMemberSelection(A)
            f.awaitNewJobs(vm) { vm.createGroup() }
            if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
            assertHeld(vm, f, "create")
            val created = phone.infos.keys.single()
            val row = phone.roster(created).single { it.address == A }
            assertNotEquals("the held invite is shown as sent", InviteStatus.SENT, row.inviteStatus)
        }

    @Test
    fun `Resend to a contact whose key exchange waits holds the invite`() =
        runBlocking {
            val phone = creator(withGroup = true)
            val (vm, f) = gvm(phone)
            f.awaitNewJobs(vm) { vm.resendInvite(GROUP, A) }
            if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
            assertHeld(vm, f, "Resend")
        }

    @Test
    fun `the Resend of an invite only a public relay took holds the invite too`() =
        runBlocking {
            val phone = creator(withGroup = true)
            NostrChatBridge.beginRelayCopy(RelayCopySlots.groupInvite(GROUP, A), "0")
            val slot = RelayCopySlots.groupInvite(GROUP, A)
            assertTrue("fixture: no relay-copy mark for the invite", NostrChatBridge.relayCopyExtra(slot) != null)
            val (vm, f) = gvm(phone)
            f.awaitNewJobs(vm) { vm.resendInvite(GROUP, A) }
            if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
            assertHeld(vm, f, "relay Resend")
        }

    private companion object {
        const val GROUP = "zgrp_8972a1b2c3d4e5f6a7b8c9d0"
        const val CREATOR = "usyntheticzc897creator00003"
        const val A = "usyntheticzc897contacta0002"
        val A_RELAY_KEY = "8e".repeat(32)
    }
}
