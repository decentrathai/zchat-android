package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GROUP_NAME
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-967 — an Open group (joined with the creator's plaintext relay invite, no creator key) takes an unsigned, sealed
 * admin change only from the relay identity of its stored creator address, compared as it is stored: never through an
 * address alias.
 *
 * The one writer of an alias is the GROUP_ACCEPT handler: a member of a group this phone runs that signs its accept
 * with its own key can declare any address, and the handler points the member's address at it. So the alias proves
 * nothing about who the creator of another group is.
 *
 * - (a) a member of the phone's own group declares the Open group's creator address in a signed accept (the alias is
 *   written), then sends the Open group a sealed, unsigned removal of this phone naming the creator as the kicker: the
 *   group stays active and untouched;
 * - (b) the same member's sealed key change naming the creator as the signer: the key and its epoch stay;
 * - (c) control: the creator's own seal still applies the removal.
 *
 * At the base (a) and (b) fail: the member's seal resolves through the alias to the creator address and the change
 * applies. All addresses, keys, ids and times are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupAdminIsNeverAnAliasTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
    }

    @Test
    fun `a - a sealed removal from a member that planted an alias to the creator address changes nothing`() =
        runBlocking {
            val (p, vm, _) = OpenGroupAlias.planted()
            val before = p.snapshot()

            AdminCheck.relayDeliver(
                vm,
                OpenGroupAlias.sealedKick(kicked = p.self),
                OpenGroupAlias.MEMBER,
                "synthetic-zc967-alias-kick",
                OpenGroupAlias.changeTime,
            )

            assertTrue("a sealed removal from the alias made this phone leave the Open group", p.base.info(GID).isActive)
            assertFalse("the group is saved as one this phone was removed from", p.base.info(GID).removedByAdmin)
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, p.self))
            assertEquals("the sealed removal from the alias changed the group", before, p.snapshot())
        }

    @Test
    fun `b - a sealed key change from a member that planted an alias to the creator address keeps the key`() =
        runBlocking {
            val (p, vm, _) = OpenGroupAlias.planted()
            val key1 = p.groupKey(1)
            val before = p.snapshot()

            AdminCheck.relayDeliver(
                vm,
                OpenGroupAlias.sealedKey(epoch = 2, gk = AnnounceDriver.newGroupKey()),
                OpenGroupAlias.MEMBER,
                "synthetic-zc967-alias-key",
                OpenGroupAlias.changeTime,
            )

            assertEquals("a sealed key change from the alias moved the group's epoch", 1, p.base.epochs[GID])
            assertNull("a sealed key change from the alias stored a key", p.groupKey(2))
            assertEquals(key1, p.groupKey(1))
            assertEquals("the sealed key change from the alias changed the group", before, p.snapshot())
        }

    @Test
    fun `c - control - the creator's own seal still applies the removal`() =
        runBlocking {
            val (p, vm, _) = OpenGroupAlias.planted()

            AdminCheck.relayDeliver(
                vm,
                OpenGroupAlias.sealedKick(kicked = p.self),
                OpenGroupAlias.CREATOR,
                "synthetic-zc967-creator-kick",
                OpenGroupAlias.changeTime,
            )

            assertFalse("the creator's sealed removal was not applied", p.base.info(GID).isActive)
            assertTrue(p.base.info(GID).removedByAdmin)
        }
}

/**
 * ZC-967 — the phone of [OpenGroupAdminIsNeverAnAliasTest]: a member of the Open group [GID] (creator [CREATOR], a
 * unified-address shape, no creator key, plaintext-key join) that also runs its own group [OTHER_GID], to which it
 * invited [MEMBER] (a finished chat). [planted] has [MEMBER] answer with a real signed GROUP_ACCEPT that declares
 * [CREATOR], so the handler writes the alias [MEMBER] → [CREATOR].
 */
internal object OpenGroupAlias {
    const val OTHER_GID = "zgrp_7a6b5c4d3e2f1a0b9c8d7e6f"

    // The GROUP_ACCEPT handler adopts a declared address only in the shape of a unified address (`u1`, over 100 chars).
    val CREATOR = "u1" + "c".repeat(110) + "zc967creator"

    /** The member of this phone's own group that plants the alias (its own address, with a finished chat). */
    val MEMBER = "u1" + "f".repeat(110) + "zc967member0"

    /** Another member of the Open group. */
    const val OTHER_MEMBER = "usyntheticzc967othermember1"

    val CREATOR_NOSTR = "7c".repeat(32)
    val MEMBER_NOSTR = "8d".repeat(32)

    private val created: Instant = Instant.ofEpochMilli(T)
    val changeTime: Instant = Instant.ofEpochMilli(T + 120_000)

    /** The phone, its ChatViewModel and [MEMBER]'s key pair for this phone, with the alias planted. */
    suspend fun planted(): Triple<AdminCheckPhone, ChatViewModel, E2EKeyPair> {
        val p = AdminCheckPhone()
        p.base.putGroup(
            GroupInfo(
                groupId = GID,
                name = GROUP_NAME,
                creatorAddress = CREATOR,
                createdAt = created,
                isActive = true,
                creatorPublicKey = null,
            ),
            listOf(
                GroupMember(address = CREATOR, joinedAt = created, status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(address = p.self, joinedAt = created, status = MemberStatus.ACTIVE),
                GroupMember(address = OTHER_MEMBER, joinedAt = created, status = MemberStatus.ACTIVE),
            ),
            1,
            AnnounceDriver.newGroupKey(),
        )
        p.base.states[GID] = p.base.state(GID).copy(plainKeyJoin = true)
        p.base.nostrKeys[CREATOR] = CREATOR_NOSTR

        val member = p.base.link(MEMBER)
        p.base.nostrKeys[MEMBER] = MEMBER_NOSTR
        p.base.selfCreated += OTHER_GID
        p.base.putGroup(
            GroupInfo(groupId = OTHER_GID, name = "Own group", creatorAddress = p.self, createdAt = created),
            listOf(
                GroupMember(address = p.self, joinedAt = created, status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(address = MEMBER, joinedAt = created, status = MemberStatus.INVITED),
            ),
            1,
            AnnounceDriver.newGroupKey(),
        )
        val vm = AdminCheck.chatVm(p, HandshakeSeam(MEMBER))
        assertEquals("fixture: the Open group does not read NOT_APPLICABLE", "NOT_APPLICABLE", AdminCheck.keyState(vm))

        // The member accepts the invitation to this phone's own group, signed with its own key, and DECLARES the Open
        // group's creator address.
        val sig = E2EEncryption.sign(member.privateKey, ZMSGGroupProtocol.groupAcceptSignedData(OTHER_GID, CREATOR, member.publicKey))
        val accept = ZMSGGroupProtocol.createGroupAcceptMessage(OTHER_GID, CREATOR, member.publicKey, sig)
        AnnounceDriver.receive(vm, accept, Instant.ofEpochMilli(T + 60_000), AnnounceDriver.txId(1), minedHeight = HEIGHT + 1)
        assertEquals("fixture: the signed group-accept planted no alias", CREATOR, p.base.aliases[MEMBER])
        return Triple(p, vm, member)
    }

    /** A sealed GROUP_KICK of [GID] naming [CREATOR] as the kicker: no signature, no key. */
    fun sealedKick(kicked: String): String =
        "ZMSG:3.0:GROUP:GK:$GID:" +
            JSONObject()
                .put("kicked", kicked)
                .put("kicker", CREATOR)
                .put("new_epoch", 2)
                .put("sig", "")
                .toString()

    /** A sealed GROUP_KEY of [GID] naming [CREATOR] as the signer: no signature, the key in plaintext as `gk`. */
    fun sealedKey(
        epoch: Int,
        gk: String,
    ): String =
        "ZMSG:3.0:GROUP:GY:$GID:" +
            JSONObject()
                .put("signer", CREATOR)
                .put("epoch", epoch)
                .put("reason", "rotation")
                .put("gk", gk)
                .put("sig", "")
                .toString()
}
