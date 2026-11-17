package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X
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
 * GR-OPEN G2, finding 4a (fold-specs/groups SPEC §2.1-§2.3, review F and I): in an all-Open group the removed member is
 * never told.
 *
 * At HEAD every group control (a kick, a new key, the removal notice) is signed with the admin's per-peer key, which only
 * a key exchange gives. An all-Open group never runs one, so `removalNoticeRow` finds no row and nothing is sent to the
 * removed member; on the receiver an unsigned kick is dropped ("UNSIGNED — ignored"). The member can still type.
 *
 * Wanted, a "sealed admin control": over the relays only, sealed by the creator's relay identity (the same trust the
 * plaintext relay invite already carries), with `"sig": ""`. The removed member gets a GROUP_KICK naming itself with no
 * key; its phone applies it only in a group it joined with the creator's plaintext relay invite (`plainKeyJoin`), only
 * from the creator's seal, only naming the creator as the kicker, only at a newer epoch, and never on-chain.
 *
 * Real ChatViewModel and GroupViewModel over the MA2/MA3 phones. All addresses, keys and ids are synthetic; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupRemovalTellsRemovedMemberTest {
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

    private fun fixture() = GroupPaidOnceFixture().apply { scheduler = this@OpenGroupRemovalTellsRemovedMemberTest.scheduler }

    private fun active(phone: AnnouncePhone): Boolean = phone.info(GID).isActive

    @Test
    fun `S1 removing X (relay key, no session) relays one GROUP_KICK to X naming X, no key, no sig, no gk - 0 on-chain, no question`() =
        runBlocking {
            val admin = OpenGroup.creator(listOf(X))
            val f = fixture()
            val seam = AnnounceSeam()
            val vm = MA3.groupVm(admin, f, seam)

            f.awaitNewJobs(vm) { vm.kickMember(GID, X) }
            assertNull("a cost question was asked", vm.paidActionConfirmation.value)
            assertNull("a no-key question was asked", vm.rekeyUnkeyableConfirmation.value)

            val kicks = OpenGroup.published(net, GroupMessageType.GROUP_KICK, OpenGroup.npub(X))
            assertEquals("the removed member was not told over the relays: ${net.to(null)}", 1, kicks.size)
            val kick = kicks.single()
            assertEquals(X, kick.getString("kicked"))
            assertEquals(C, kick.getString("kicker"))
            assertEquals(2, kick.getInt("new_epoch"))
            assertFalse("the removal notice carries a wrapped key", kick.has("enc_key"))
            assertFalse("the removal notice carries the new key in plaintext", kick.has("gk"))
            assertEquals("", kick.optString("sig", "missing"))
            assertTrue("something was paid on-chain: ${seam.proposals}", seam.proposals.isEmpty())
        }

    @Test
    fun `S2 X's phone - the creator's sealed kick makes the group inactive and removed, nothing is quoted or sent`() =
        runBlocking {
            val (phone, chat) = OpenGroup.plainMember(X)

            MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 2, gk = null), sender = C)

            val info = phone.info(GID)
            assertFalse("the sealed kick did not remove X's phone from the group", info.isActive)
            assertTrue("the group is not saved as one X was removed from", info.removedByAdmin)

            val f = fixture()
            val vm = AnnounceDriver.groupVm(phone, f)
            assertEquals(0, vm.groupMessageCost(GID).recipients)
            net.clear()
            f.awaitNewJobs(vm) { vm.sendGroupMessage(GID, "after my removal") }
            val event = vm.groupSendEvent.value
            assertTrue("the send was not refused as not in the group: $event", event is GroupSendResult.NotInGroup)
            assertTrue((event as GroupSendResult.NotInGroup).removed)
            assertTrue("something was published: ${net.to(null)}", net.to(null).isEmpty())
            assertEquals("something was paid", 0, f.submits())
        }

    @Test
    fun `S3 security - the same unsigned kick sealed by a member who is not the creator is ignored`() =
        runBlocking {
            val (phone, chat) = OpenGroup.plainMember(X)

            MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 2, gk = null), sender = B)

            assertTrue(active(phone))
        }

    @Test
    fun `S4 security - the same unsigned kick arriving on-chain (no seal) is ignored`() =
        runBlocking {
            val (phone, chat) = OpenGroup.plainMember(X)

            AnnounceDriver.receive(
                chat,
                OpenGroup.sealedKick(X, newEpoch = 2, gk = null),
                Instant.ofEpochMilli(MA3.now()),
                AnnounceDriver.txId(41),
                minedHeight = 3_000_000L,
            )

            assertTrue(active(phone))
        }

    @Test
    fun `S5 security - a sealed kick is ignored in a group joined through a key exchange (k2), even from the creator's seal`() =
        runBlocking {
            val phone = AnnouncePhone(X)
            phone.nostrKeys[C] = MA3.NPUB.getValue(C)
            phone.link(C)
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(X))
            MA3.relay(
                chat,
                AnnounceDriver.gi(C, phone.sessionWith(C), MA3.groupKey, keyEpoch = 1, groupId = GID),
                sender = C,
                timeMs = MA3.now() - 3_000_000L,
            )
            assertTrue("fixture: the k2 invite created the group", phone.infos[GID] != null && active(phone))

            MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 2, gk = null), sender = C)

            assertTrue(active(phone))
        }

    @Test
    fun `S6 security - a sealed kick at an epoch not above the current one is ignored (replay)`() =
        runBlocking {
            val (phone, chat) = OpenGroup.plainMember(X)

            MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 1, gk = null), sender = C)

            assertTrue(active(phone))
        }

    @Test
    fun `S7 security - a sealed kick is ignored with no plainKeyJoin record, and when the kicker is not the creator`() =
        runBlocking {
            // (a) The same group as a plaintext join would make, but written directly: no plain-key-join record.
            val phone = AnnouncePhone(X)
            phone.nostrKeys[C] = MA3.NPUB.getValue(C)
            phone.putGroup(
                co.electriccoin.zcash.ui.screen.chat.model.GroupInfo(
                    groupId = GID,
                    name = "Open group",
                    creatorAddress = C,
                    createdAt = Instant.ofEpochMilli(MA3.now() - 3_600_000L),
                ),
                listOf(OpenGroup.member(C), OpenGroup.member(X)),
                1,
                MA3.groupKey,
            )
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(X))
            MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 2, gk = null), sender = C)
            assertTrue("a group with no plain-key join applied a sealed kick", active(phone))

            // (b) A plaintext join, but the kick names another kicker than the creator.
            val (phone2, chat2) = OpenGroup.plainMember(X)
            MA3.relay(chat2, OpenGroup.sealedKick(X, newEpoch = 2, gk = null, kicker = B), sender = C)
            assertTrue("a sealed kick naming a kicker that is not the creator was applied", active(phone2))
        }

    @Test
    fun `S8 X joined with the plaintext relay invite, then got an E2E key for the creator (half KEX) - the sealed kick is still applied`() =
        runBlocking {
            val (phone, chat) = OpenGroup.plainMember(X)
            // The creator's key exchange reached X, its answer is not back yet: X holds a key for the creator.
            phone.peerKeys[C] = E2EEncryption.generateKeyPair().publicKey

            MA3.relay(chat, OpenGroup.sealedKick(X, newEpoch = 2, gk = null), sender = C)

            assertFalse("a half key exchange made X refuse the creator's sealed kick (review F)", active(phone))
            assertTrue(phone.info(GID).removedByAdmin)
        }
}
