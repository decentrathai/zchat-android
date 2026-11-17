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
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y2
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-953 reverted (orchestrator's decision of 14 Oct 2026, security first; KI-25 D3, R7, R8, R11; skill compat-2124)
 * — the admin check never suggests a chat because of an address alias.
 *
 * Why: the one writer of an alias is the GROUP_ACCEPT handler (#218 / #219): a signed accept from any member of any
 * group whose key this phone holds makes the text that member was invited under point to the address the member
 * DECLARED. The signature proves only that the member holds its own key, not that the declared address is the member's.
 * So any contact with a finished chat can point its own chat at the creator address of a group it did not create, and a
 * suggestion that follows aliases (ZC-953, `9170225e4`) offered that contact as the group's admin, or turned the real
 * admin's suggestion into "two keys, none offered". KI-25's rule D3 dropped alias lookups for exactly this reason.
 *
 * Wanted (option A): no suggestion from an alias. A group upgraded from 2.12.4 whose admin's key sits only under an
 * alias of the creator address confirms through the original invitation (R7 source 1), or the card advises creating a
 * new group, as before ZC-953.
 *
 * - (a) a contact's real signed GROUP_ACCEPT (the handler's own path) plants an alias from the contact's chat to the
 *   creator address of a group it did not create: the card and the confirm dialog offer no chat, Confirm after the
 *   safety number was compared is `NOT_THE_RECORDED_CHAT`, the contact's signed removal is refused and the group stays
 *   UNKNOWN;
 * - (b) the admin's own chat under the single alias of the creator address (the shape of ZC-953) is not suggested
 *   either: no chat on the card or in the dialog;
 * - (c) its Confirm, after the safety number was compared, is `NOT_THE_RECORDED_CHAT`; the admin's removal stays refused
 *   and nothing is written;
 * - (d) two alias keys: no suggestion, nothing can be confirmed (unchanged from ZC-953's RED);
 * - (e) zero alias keys: no suggestion (unchanged from ZC-953's RED).
 *
 * At `9170225e4` (the suggestion) (a), (b) and (c) fail: the alias chat is offered and confirmed.
 *
 * All addresses, keys, ids and times are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCheckNeverSuggestsAnAliasKeyTest {
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

    private val created: Instant = Instant.ofEpochMilli(T)
    private val kickTime: Instant = Instant.ofEpochMilli(T + 60_000)

    /**
     * A group upgraded from 2.12.4 on this phone: creator address [creator], no creator key, no key under [creator],
     * no invitation in the history. [aliases] maps an address text to its alias target.
     */
    private fun upgradedPhone(
        creator: String = X_OLD,
        adminChat: String? = Y,
        aliases: Map<String, String> = mapOf(Y to X_OLD),
    ): AdminCheckPhone {
        val p = AdminCheckPhone()
        if (adminChat != null) p.adminKeys = p.base.link(adminChat)
        p.base.aliases.putAll(aliases)
        p.base.infos[GID] =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(groupId = GID, name = GROUP_NAME, creatorAddress = creator, createdAt = created, isActive = true),
            )
        p.base.rosters[GID] =
            ZMSGGroupProtocol.serializeGroupMembers(
                listOf(
                    GroupMember(address = creator, joinedAt = created, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = p.self, joinedAt = created, status = MemberStatus.ACTIVE),
                    GroupMember(address = M, joinedAt = created, status = MemberStatus.ACTIVE),
                ),
            )
        p.base.epochs[GID] = 1
        p.base.keys["$GID|1"] = AnnounceDriver.newGroupKey()
        assertFalse("fixture: the record holds a creator key", JSONObject(p.base.infos.getValue(GID)).has("creator_pub"))
        assertNull("fixture: a key is stored under the creator address", p.prefs.getE2EPeerPublicKey(creator))
        return p
    }

    private fun onChain(
        vm: ChatViewModel,
        memo: String,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(6), minedHeight = HEIGHT + 6)

    private fun kick(
        p: AdminCheckPhone,
        naming: String,
        signer: E2EKeyPair = p.admin,
        wrapFor: String = Y,
    ): String =
        AdminCheck.kickNaming(
            p,
            naming = naming,
            kicked = M,
            newEpoch = 2,
            newKey = AnnounceDriver.newGroupKey(),
            signer = signer,
            wrapFor = wrapFor,
        )

    /** What the check card in Group settings offers on [p] (null: no chat). */
    private suspend fun cardCandidate(p: AdminCheckPhone): Any? {
        val f = GroupPaidOnceFixture()
        f.scheduler = scheduler
        val vm = AdminCheck.groupVm(p, f)
        val candidate = AdminCheck.loadCandidate(vm, f)
        assertEquals("looking for the candidate proposed a payment", 0, f.seam.proposalRequests.get())
        return candidate
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - an alias a contact planted with its signed group-accept never makes it the suggested admin`() =
        runBlocking {
            // The upgraded group, created under CREATOR; this phone has no chat with its admin at all.
            val p = upgradedPhone(creator = CREATOR, adminChat = null, aliases = emptyMap())
            // A contact with a finished chat, invited by this phone to another group, of which it is no admin.
            val contact = p.base.link(CONTACT)
            p.base.selfCreated += OTHER_GID
            p.base.putGroup(
                GroupInfo(groupId = OTHER_GID, name = "Other group", creatorAddress = p.self, createdAt = created),
                listOf(
                    GroupMember(address = p.self, joinedAt = created, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = CONTACT, joinedAt = created, status = MemberStatus.INVITED),
                ),
                1,
                AnnounceDriver.newGroupKey(),
            )
            val seam = HandshakeSeam(CONTACT)
            val vm = AdminCheck.chatVm(p, seam)

            // The contact accepts, signed with its own key, and DECLARES the creator address of the upgraded group.
            val sig = E2EEncryption.sign(contact.privateKey, ZMSGGroupProtocol.groupAcceptSignedData(OTHER_GID, CREATOR, contact.publicKey))
            val accept = ZMSGGroupProtocol.createGroupAcceptMessage(OTHER_GID, CREATOR, contact.publicKey, sig)
            AnnounceDriver.receive(vm, accept, Instant.ofEpochMilli(T + 1_000), AnnounceDriver.txId(1), minedHeight = HEIGHT + 1)
            assertEquals("fixture: the signed group-accept planted no alias", CREATOR, p.base.aliases[CONTACT])
            p.verified += CONTACT
            val before = p.snapshot()

            assertNull("the card suggests the contact that planted an alias as the group's admin", cardCandidate(p))
            assertNull("the confirm dialog offers the contact that planted an alias", AdminCheck.loadPreview(vm))
            assertEquals(
                "the contact that planted an alias could be confirmed as the group's admin",
                "NOT_THE_RECORDED_CHAT",
                AdminCheck.confirm(vm, CONTACT, contact.publicKey),
            )
            HandshakeHarness.awaitIdle(vm)
            for (naming in listOf(CONTACT, CREATOR)) onChain(vm, kick(p, naming, signer = contact, wrapFor = CONTACT))

            assertEquals("the contact's removal changed the group", before, p.snapshot())
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
            assertNull("a creator key was stored", p.base.info(GID).creatorPublicKey)
            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - the single alias key of the creator address is not suggested`() =
        runBlocking {
            val p = upgradedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            assertNull("the card suggests a chat from an alias", cardCandidate(p))
            assertNull("the confirm dialog offers a chat from an alias", AdminCheck.loadPreview(vm))
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - the alias chat cannot be confirmed even after the safety number was compared, and the removal stays refused`() =
        runBlocking {
            val p = upgradedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            p.verified += Y
            val before = p.snapshot()

            assertEquals("a chat from an alias was confirmed", "NOT_THE_RECORDED_CHAT", AdminCheck.confirm(vm, Y, p.admin.publicKey))
            HandshakeHarness.awaitIdle(vm)
            for (naming in listOf(Y, X_OLD)) onChain(vm, kick(p, naming))

            assertEquals("something changed", before, p.snapshot())
            assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(1, p.base.epochs[GID])
            assertNull("a creator key was stored", p.base.info(GID).creatorPublicKey)
            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - two alias keys give no suggestion and nothing can be confirmed`() =
        runBlocking {
            val p = upgradedPhone(aliases = mapOf(Y to X_OLD, Y2 to X_OLD))
            p.base.link(Y2)
            p.verified += listOf(Y, Y2)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val before = p.snapshot()

            assertNull("a chat is suggested although two alias keys exist", cardCandidate(p))
            assertNull("the dialog offers a chat although two alias keys exist", AdminCheck.loadPreview(vm))
            assertEquals("NOT_THE_RECORDED_CHAT", AdminCheck.confirm(vm, Y, p.admin.publicKey))
            HandshakeHarness.awaitIdle(vm)
            onChain(vm, kick(p, Y))

            assertEquals("something changed", before, p.snapshot())
            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - zero alias keys give no suggestion`() =
        runBlocking {
            // An alias of the creator address whose text holds no key, and no alias for the admin's chat.
            val keyless = upgradedPhone(aliases = mapOf(STRANGER_TEXT to X_OLD))
            assertNull("an alias with no key under it was suggested", cardCandidate(keyless))
            assertNull(AdminCheck.loadPreview(AdminCheck.chatVm(keyless, HandshakeSeam(Y))))

            val noAlias = upgradedPhone(aliases = emptyMap())
            assertNull("a chat was suggested with no alias at all", cardCandidate(noAlias))
            assertNull(AdminCheck.loadPreview(AdminCheck.chatVm(noAlias, HandshakeSeam(Y))))
        }

    private companion object {
        const val STRANGER_TEXT = "usyntheticzc953keylessalias01"

        /** A second group, created by this phone, to which [CONTACT] was invited. */
        const val OTHER_GID = "zgrp_9e8d7c6b5a4f3e2d1c0b9a8f"

        // The GROUP_ACCEPT handler adopts a declared address only in the shape of a unified address (`u1`, over 100
        // characters), so these two synthetic texts have that shape.

        /** The creator address of the upgraded group. */
        val CREATOR = "u1" + "d".repeat(110) + "zc953creator"

        /** The contact that plants the alias. */
        val CONTACT = "u1" + "e".repeat(110) + "zc953contact"
    }
}
