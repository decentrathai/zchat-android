package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminNotice
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GROUP_NAME
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
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
import java.time.Instant

/**
 * ZC-953 (review of train T7a2, 13 Oct 2026, finding 5; KI-25 R1, R5, R11; skill compat-2124) — a CONTROL: a group
 * upgraded from 2.12.4 whose admin's key sits only under an address text that is an alias of the stored creator
 * address. Every case passes at `e224d16ac`; the class pins what such a group does today and changes nothing.
 *
 * The shape, from the code at tag `v2.12.4` (`a1fb4136f`):
 * - the group's record is what 2.12.4 wrote ([Frozen2124.serializeGroupInfo], `ZMSGGroupProtocol.kt@v2.12.4:681-692`,
 *   verbatim): no `creator_pub`, so no stored creator key;
 * - the alias is what 2.12.4's one writer leaves (`ChatViewModel.kt@v2.12.4:8554`,
 *   `setPeerAddressAlias(matched.address, accepterAddress)`, stored as `alias:<rep>` → the live address,
 *   `ZchatPreferences.kt@v2.12.4:2499-2512`): after a signed GROUP_ACCEPT of some group, the address text the phone
 *   holds that person's key under (the rep, here Y) points to the address the person declared (here X). 2.12.4 never
 *   moves the key, so it stays under Y, the non-canonical text;
 * - that person is the creator of this group under X (`creator` = X), where no key is stored.
 * The roster and the invitation in the history are written with today's writers; they are not what this class is about.
 *
 * What each build does with the admin's signed removal in such a group:
 * - 2.12.4 itself refused it, whichever of the two address texts it names ([Frozen2124.verifyGroupAdminControl],
 *   `ChatViewModel.kt@v2.12.4:8644-8681`, reduced to a pure function as in [AdminControlAcrossVersions2124Test]): no
 *   key under X, and Y is not the creator's text. Nothing told the user.
 * - `00f1a349b` (unreleased, before KI-25 pair 1) accepted the one that names Y, through the address comparison
 *   `ZMSGGroupProtocol.isGroupControlAuthorized` with no creator key (X and Y resolve to the same text).
 * - this branch refuses both again (R1: the creator key alone decides; R11: the repair never follows an alias), shows
 *   the check line and the card, and applies the change after the user confirmed the chat, which needs the group's
 *   invitation in this wallet's history. Without it the card can only say "ask the admin to create a new group".
 *   ZC-953 had the card suggest the single key stored under an alias of the creator address; that was reverted on
 *   14 Oct 2026 (KI-25 D3: any contact can plant an alias with a signed group-accept), so the second case of (d) pins
 *   "no chat is offered" again.
 *
 * An alias in the other direction (the creator address resolves to the text that holds the key) was read by the gate
 * until KI-25 pair 3 (D1, ZC-598); since then that group asks once too, and the last case pins it.
 *
 * All addresses, keys, ids and times are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpgradedGroupWithAdminKeyUnderAnAlias2124Test {
    private val sendScopes = SendHarnessScopes()

    /** Verbatim from v2.12.4 (a1fb4136f), its formatting kept; see the class KDoc for the files and lines. */
    @Suppress(
        "ReturnCount",
        "ktlint:standard:multiline-expression-wrapping",
        "ktlint:standard:function-signature",
        "ktlint:standard:function-expression-body",
        "ktlint:standard:chain-method-continuation",
    )
    private object Frozen2124 {
        // ---- ZMSGGroupProtocol.kt@v2.12.4:681-692

        fun serializeGroupInfo(info: GroupInfo): String {
            return JSONObject().apply {
                put("group_id", info.groupId)
                put("name", info.name)
                put("creator", info.creatorAddress)
                put("created_at", info.createdAt.toEpochMilli())
                put("admin_policy", info.adminPolicy.name)
                put("current_epoch", info.currentEpoch)
                info.groupKey?.let { put("group_key", it) }
                put("is_active", info.isActive)
            }.toString()
        }

        // ---- ChatViewModel.kt@v2.12.4:8644-8681, pure: [keyAt] is `zchatPreferences.getE2EPeerPublicKey` (a plain read
        // of the key stored under exactly that text, `ZchatPreferences.kt@v2.12.4:2765-2767`), [creatorAddress] the
        // group record's creator (null: no record). The log lines are left out.

        fun verifyGroupAdminControl(
            signerAddress: String,
            signedData: String,
            signature: String?,
            keyAt: (String) -> String?,
            creatorAddress: String?
        ): Boolean {
            if (signature.isNullOrEmpty()) {
                return false
            }
            if (signerAddress.contains('|')) {
                return false
            }
            // AUTHENTICATE: the signature must verify against the signer's KEX-established E2E key.
            val signerPub = keyAt(signerAddress)
            if (signerPub == null) {
                return false
            }
            val authentic = runCatching { E2EEncryption.verify(signerPub, signedData, signature) }.getOrDefault(false)
            if (!authentic) {
                return false
            }
            // AUTHORIZE: the (authenticated) signer must actually be the group's admin/creator.
            if (creatorAddress == null || creatorAddress != signerAddress) {
                return false
            }
            return true
        }
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
     * The upgraded phone: a chat with the admin under [Y] (the key is there), the group's record as 2.12.4 wrote it
     * with the creator address [X_OLD] and no key under it, and one alias, from [aliasFrom] to [aliasTo].
     */
    private fun upgradedPhone(
        aliasFrom: String = Y,
        aliasTo: String = X_OLD,
    ): AdminCheckPhone {
        val p = AdminCheckPhone()
        p.adminKeys = p.base.link(Y)
        p.base.aliases[aliasFrom] = aliasTo
        p.base.infos[GID] =
            Frozen2124.serializeGroupInfo(
                GroupInfo(groupId = GID, name = GROUP_NAME, creatorAddress = X_OLD, createdAt = created, isActive = true),
            )
        p.base.rosters[GID] =
            ZMSGGroupProtocol.serializeGroupMembers(
                listOf(
                    GroupMember(address = X_OLD, joinedAt = created, status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(address = p.self, joinedAt = created, status = MemberStatus.ACTIVE),
                    GroupMember(address = M, joinedAt = created, status = MemberStatus.ACTIVE),
                ),
            )
        p.base.epochs[GID] = 1
        p.base.keys["$GID|1"] = AnnounceDriver.newGroupKey()
        return p
    }

    /** A pass of the scan over one on-chain memo (a pass is this call made again). */
    private fun onChain(
        vm: ChatViewModel,
        memo: String,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(6), minedHeight = HEIGHT + 6)

    private fun kick(
        p: AdminCheckPhone,
        naming: String,
        newKey: String? = AnnounceDriver.newGroupKey(),
    ): String = AdminCheck.kickNaming(p, naming = naming, kicked = M, newEpoch = 2, newKey = newKey)

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - the fixture is a 2_12_4 record with no creator key and the admin's key only under the alias`() {
        val p = upgradedPhone()

        val stored = JSONObject(p.base.infos.getValue(GID))
        assertFalse("a 2.12.4 record holds a creator key", stored.has("creator_pub"))
        assertEquals(X_OLD, stored.getString("creator"))
        val read = ZMSGGroupProtocol.deserializeGroupInfo(p.base.infos.getValue(GID))
        assertNotNull("today's reader does not read the 2.12.4 record", read)
        assertNull(read!!.creatorPublicKey)
        assertTrue(read.isActive)
        assertNull("a key is stored under the creator address", p.prefs.getE2EPeerPublicKey(X_OLD))
        assertEquals("the admin's key is not under the alias", p.admin.publicKey, p.prefs.getE2EPeerPublicKey(Y))
        assertEquals("the alias does not point to the creator address", X_OLD, p.prefs.resolvePeerAddress(Y))
        assertEquals("the creator address is not its own canonical text", X_OLD, p.prefs.resolvePeerAddress(X_OLD))
    }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - 2_12_4 itself refused the admin's removal under both address texts`() {
        val p = upgradedPhone()
        for (naming in listOf(Y, X_OLD)) {
            val signedData = ZMSGGroupProtocol.groupKickSignedData(GID, M, naming, 2, null)
            val signature = E2EEncryption.sign(p.admin.privateKey, signedData)

            assertFalse(
                "2.12.4's gate accepted the admin's removal naming $naming",
                Frozen2124.verifyGroupAdminControl(naming, signedData, signature, { p.base.peerKeys[it] }, X_OLD),
            )
        }
    }

    @Test
    fun `b - the address comparison of 00f1a349b, which is gone from the gate, authorized the one that names the alias`() {
        val p = upgradedPhone()
        val resolve = { address: String -> p.prefs.resolvePeerAddress(address) }

        assertTrue(
            "fixture: with no creator key the address comparison authorizes the text that resolves to the creator's",
            ZMSGGroupProtocol.isGroupControlAuthorized(null, p.admin.publicKey, X_OLD, Y, resolve),
        )
    }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - control - today the admin's removal is refused under both address texts and the group shows the check line`() =
        runBlocking {
            for (naming in listOf(Y, X_OLD)) {
                val p = upgradedPhone()
                val seam = HandshakeSeam(Y)
                val vm = AdminCheck.chatVm(p, seam)
                val before = p.snapshot()

                repeat(3) { onChain(vm, kick(p, naming)) }

                assertEquals("the admin's removal naming $naming changed the group", before, p.snapshot())
                assertEquals(MemberStatus.ACTIVE, p.base.status(GID, M))
                assertEquals(1, p.base.epochs[GID])
                assertFalse("a creator key was stored", JSONObject(p.base.infos.getValue(GID)).has("creator_pub"))
                assertEquals("the group does not read as one that cannot confirm its admin", "UNKNOWN", AdminCheck.keyState(vm))
                assertEquals(
                    "the group chat does not show the check line",
                    GroupAdminNotice.CHECK_LINE,
                    GroupAdminCheck.notice(p.prefs, emptyList(), GID, newestReceivedUnreadable = false),
                )
                assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
            }
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - control - with the invitation in the history the card names the chat and the removal is applied after Confirm`() =
        runBlocking {
            val p = upgradedPhone()
            p.creatingInvite(p.base.sessionWith(Y), created)
            p.base.nostrKeys[Y] = Y_NOSTR
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val newKey = AnnounceDriver.newGroupKey()
            val memo = kick(p, Y, newKey)

            val preview = AdminCheck.loadPreview(vm) ?: throw AssertionError("the confirm dialog has no chat to show")
            assertEquals("the dialog does not name the chat that holds the admin's key", Y, AdminCheck.property(preview, "address"))
            assertEquals(p.admin.publicKey, AdminCheck.property(preview, "contactKey"))
            assertEquals("the safety number was not compared yet", "NOT_VERIFIED", AdminCheck.confirm(vm, Y, p.admin.publicKey))

            p.verified += Y
            onChain(vm, memo)
            assertEquals("the removal was applied before the confirmation", MemberStatus.ACTIVE, p.base.status(GID, M))

            assertEquals(CONFIRMED, AdminCheck.confirm(vm, Y, p.admin.publicKey))
            HandshakeHarness.awaitIdle(vm)
            onChain(vm, memo)

            assertEquals("the confirmed admin's removal was not applied", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(2, p.base.epochs[GID])
            assertEquals(newKey, p.groupKey(2))
            assertEquals("the confirmation wrote an alias", mapOf(Y to X_OLD), HashMap(p.base.aliases))
            assertEquals("the confirmation changed the creator address", X_OLD, p.base.info(GID).creatorAddress)
            assertEquals("OK", AdminCheck.keyState(vm))
        }

    @Test
    fun `d - control - without the invitation in the history no chat is offered and nothing can be confirmed (ZC-953 reverted)`() =
        runBlocking {
            val p = upgradedPhone()
            p.verified += Y
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val before = p.snapshot()

            assertNull("a chat is offered although no record names one", AdminCheck.loadPreview(vm))
            assertEquals("NOT_THE_RECORDED_CHAT", AdminCheck.confirm(vm, Y, p.admin.publicKey))
            HandshakeHarness.awaitIdle(vm)
            onChain(vm, kick(p, Y))

            assertEquals("something changed", before, p.snapshot())
            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - pin - when the creator address resolves to the text that holds the key, the group asks once (KI-25 pair 3, D1)`() =
        runBlocking {
            // KI-25 pair 3 (ZC-598, D1): a key at the creator address, or at its alias target, is never the creator's by
            // itself any more; this group, whose record has no creator key, asks for the confirmation once.
            val p = upgradedPhone(aliasFrom = X_OLD, aliasTo = Y)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
            onChain(vm, kick(p, Y, AnnounceDriver.newGroupKey()))

            assertEquals("a removal was applied by a key the group never confirmed", MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(1, p.base.epochs[GID])
            assertNull("a control wrote a creator key", p.base.info(GID).creatorPublicKey)
        }
}
