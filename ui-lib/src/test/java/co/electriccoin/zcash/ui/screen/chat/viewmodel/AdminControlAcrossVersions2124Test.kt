package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b section 7, skill compat-2124; section 4.1 class 9) — the admin check across
 * versions: nothing changes on the wire, a 2.12.4 admin's changes are applied by a phone whose admin is confirmed, and
 * a 2.12.4 member judges today's changes exactly as before.
 *
 * The 2.12.4 phone is modelled with frozen code ([Frozen2124]), copied verbatim from tag `v2.12.4` (`a1fb4136f`):
 * - `ZMSGGroupProtocol.kt@v2.12.4:275-290`: `groupKickSignedData`, `groupKeySignedData`;
 * - `ZMSGGroupProtocol.kt@v2.12.4:398-436`: `createGroupKickMessage`, `createGroupKeyMessage` (the spec's range
 *   398-440 ends in the next function's KDoc); `GROUP_PREFIX` is line 35, `ZMSGConstants.kt@v2.12.4:51`;
 * - `ChatViewModel.kt@v2.12.4:8644-8681`: the gate `verifyGroupAdminControl`, reduced to a pure function: the two
 *   store reads (`getE2EPeerPublicKey`, the group record's creator address) are parameters, the log lines are left
 *   out, every test and its order is unchanged.
 * The fields a 2.12.4 phone reads from a kick are those of its parser (`ZMSGGroupProtocol.kt@v2.12.4:564-579`).
 *
 * (a) A 2.12.4 admin writes its current address text into its changes (`GroupViewModel.kt@v2.12.4:1192, 1232-1238`).
 * On a member's phone that holds that admin's key under another text, the group's record holds the key once the admin
 * is confirmed (the record a confirmation leaves is [AdminCheck.pinnedPhone]'s: the creator key, nothing else). At
 * `00f1a349b` such a phone still drops the 2.12.4 admin's kick and rotation, because the address they name holds no
 * key. (b) and (c) pass before and after the fix and pin the wire: a kick built today is accepted and refused by
 * 2.12.4's gate as before, and today's kick and rotation memos are the frozen builders' memos, byte for byte.
 *
 * All addresses, keys and ids are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminControlAcrossVersions2124Test {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val WRAPPED = "d3JhcHBlZC1uZXcta2V5LWJhc2U2NA=="
        const val KICK_SIG = "c3ludGhldGljLXNpZy1raWNr"
        const val KEY_SIG = "c3ludGhldGljLXNpZy1rZXk="

        // The key order is the unit-test JSON library's (org.json 20231013); a phone writes the same fields.
        const val GOLDEN_KICK =
            "ZMSG:3.0:GROUP:GK:zgrp_1c2d3e4f5a6b7c8d9e0f1a2b:{\"enc_key\":\"d3JhcHBlZC1uZXcta2V5LWJhc2U2NA==\"," +
                "\"sig\":\"c3ludGhldGljLXNpZy1raWNr\",\"kicked\":\"usyntheticki25memberm0000001\",\"new_epoch\":2," +
                "\"kicker\":\"usyntheticki25adminold000001\"}"
        const val GOLDEN_KEY =
            "ZMSG:3.0:GROUP:GY:zgrp_1c2d3e4f5a6b7c8d9e0f1a2b:{\"enc_key\":\"d3JhcHBlZC1uZXcta2V5LWJhc2U2NA==\"," +
                "\"sig\":\"c3ludGhldGljLXNpZy1rZXk=\",\"reason\":\"rotation\",\"epoch\":2," +
                "\"signer\":\"usyntheticki25adminold000001\"}"
        const val GOLDEN_KICK_SIGNED =
            "GK|zgrp_1c2d3e4f5a6b7c8d9e0f1a2b|usyntheticki25memberm0000001|usyntheticki25adminold000001|2|$WRAPPED"
        const val GOLDEN_KEY_SIGNED = "GY|zgrp_1c2d3e4f5a6b7c8d9e0f1a2b|usyntheticki25adminold000001|2|$WRAPPED|rotation"
    }

    /** Verbatim from v2.12.4 (a1fb4136f), its formatting kept; see the class KDoc for the files and lines. */
    @Suppress(
        "LongParameterList",
        "ReturnCount",
        "ktlint:standard:multiline-expression-wrapping",
        "ktlint:standard:string-template",
    )
    private object Frozen2124 {
        // ZMSGGroupProtocol.kt:35 and ZMSGConstants.kt:51 at v2.12.4
        private const val GROUP_PREFIX = "ZMSG:3.0:GROUP:"

        // ---- ZMSGGroupProtocol.kt@v2.12.4:275-290

        fun groupKickSignedData(
            groupId: String,
            kickedAddress: String,
            kickerAddress: String,
            newEpoch: Int,
            encryptedNewKey: String?
        ): String = "GK|$groupId|$kickedAddress|$kickerAddress|$newEpoch|${encryptedNewKey.orEmpty()}"

        /** Canonical bytes a GROUP_KEY signature covers. MUST match exactly on sign + verify. */
        fun groupKeySignedData(
            groupId: String,
            signerAddress: String,
            newEpoch: Int,
            encryptedGroupKey: String,
            reason: String
        ): String = "GY|$groupId|$signerAddress|$newEpoch|$encryptedGroupKey|$reason"

        // ---- ZMSGGroupProtocol.kt@v2.12.4:398-436

        fun createGroupKickMessage(
            groupId: String,
            kickedAddress: String,
            kickerAddress: String,
            newEpoch: Int,
            encryptedNewKey: String?,
            signature: String
        ): String {
            val payload = JSONObject().apply {
                put("kicked", kickedAddress)
                put("kicker", kickerAddress)
                put("new_epoch", newEpoch)
                encryptedNewKey?.let { put("enc_key", it) }
                put("sig", signature)
            }
            return "${GROUP_PREFIX}GK:$groupId:${payload}"
        }

        /**
         * Create a GROUP_KEY message (key rotation). [signerAddress] is the admin and [signature] is their
         * signature over [groupKeySignedData] for THIS recipient. Unsigned rotations must not be acted on.
         */
        fun createGroupKeyMessage(
            groupId: String,
            signerAddress: String,
            newEpoch: Int,
            encryptedGroupKey: String,
            signature: String,
            reason: String = "rotation"
        ): String {
            val payload = JSONObject().apply {
                put("signer", signerAddress)
                put("epoch", newEpoch)
                put("enc_key", encryptedGroupKey)
                put("reason", reason)
                put("sig", signature)
            }
            return "${GROUP_PREFIX}GY:$groupId:${payload}"
        }

        // ---- ChatViewModel.kt@v2.12.4:8644-8681, reduced to a pure function ([peerPublicKey] is
        // zchatPreferences.getE2EPeerPublicKey; [creatorAddress] is the stored group record's creator, null when the
        // group has no record; the log lines are left out)

        fun verifyGroupAdminControl(
            signerAddress: String,
            signedData: String,
            signature: String?,
            peerPublicKey: (String) -> String?,
            creatorAddress: String?,
        ): Boolean {
            if (signature.isNullOrEmpty()) {
                return false
            }
            if (signerAddress.contains('|')) {
                return false
            }
            // AUTHENTICATE: the signature must verify against the signer's KEX-established E2E key.
            val signerPub = peerPublicKey(signerAddress)
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
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a - a kick and a rotation built by 2_12_4, naming the old address text, are applied by a confirmed phone`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val seam = HandshakeSeam(Y)
            val vm = AdminCheck.chatVm(p, seam)
            val session = p.base.sessionWith(Y)

            val kickKey = AnnounceDriver.newGroupKey()
            val kickWrapped = E2EEncryption.encrypt(kickKey, session)
            val kick =
                Frozen2124.createGroupKickMessage(
                    GID,
                    M,
                    X_OLD,
                    2,
                    kickWrapped,
                    E2EEncryption.sign(p.admin.privateKey, Frozen2124.groupKickSignedData(GID, M, X_OLD, 2, kickWrapped)),
                )
            AnnounceDriver.receive(vm, kick, Instant.ofEpochMilli(T + 60_000), AnnounceDriver.txId(6), minedHeight = HEIGHT + 6)

            assertEquals("the 2.12.4 admin's removal was not applied", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals("the 2.12.4 admin's removal did not move the key epoch", 2, p.base.epochs[GID])
            assertEquals("the key of the 2.12.4 admin's removal was not adopted", kickKey, p.groupKey(2))

            val rotatedKey = AnnounceDriver.newGroupKey()
            val rotatedWrapped = E2EEncryption.encrypt(rotatedKey, session)
            val rotation =
                Frozen2124.createGroupKeyMessage(
                    GID,
                    X_OLD,
                    3,
                    rotatedWrapped,
                    E2EEncryption.sign(
                        p.admin.privateKey,
                        Frozen2124.groupKeySignedData(GID, X_OLD, 3, rotatedWrapped, "rotation"),
                    ),
                )
            AnnounceDriver.receive(vm, rotation, Instant.ofEpochMilli(T + 120_000), AnnounceDriver.txId(7), minedHeight = HEIGHT + 7)

            assertEquals("the 2.12.4 admin's key change did not move the key epoch", 3, p.base.epochs[GID])
            assertEquals("the 2.12.4 admin's new group key was not adopted", rotatedKey, p.groupKey(3))
            assertEquals(0, seam.proposalRequests.get())
        }

    @Test
    fun `b - a kick built today is judged by the 2_12_4 gate exactly as before`() {
        val admin = E2EEncryption.generateKeyPair()

        // Its admin, known under the address the kick names: accepted.
        assertTrue(
            "2.12.4 refuses its admin's kick built by today's builder",
            judgedBy2124(todaysKick(kicker = X_OLD, signer = admin), keys = mapOf(X_OLD to admin.publicKey), creator = X_OLD),
        )
        // The admin's key under another text than the stored creator address: 2.12.4 refuses, as it always did.
        assertFalse(
            "2.12.4 accepts a kick naming an address that is not its stored creator address",
            judgedBy2124(todaysKick(kicker = Y, signer = admin), keys = mapOf(Y to admin.publicKey), creator = X_OLD),
        )
        // No key under the named address.
        assertFalse(
            "2.12.4 accepts a kick naming an address it holds no key under",
            judgedBy2124(todaysKick(kicker = X_OLD, signer = admin), keys = mapOf(Y to admin.publicKey), creator = X_OLD),
        )
        // Another contact's key signed it.
        assertFalse(
            "2.12.4 accepts a kick its admin's key did not sign",
            judgedBy2124(
                todaysKick(kicker = X_OLD, signer = E2EEncryption.generateKeyPair()),
                keys = mapOf(X_OLD to admin.publicKey),
                creator = X_OLD,
            ),
        )
        // Today's sealed form (an empty signature, relays only) reads as unsigned.
        assertFalse(
            "2.12.4 acts on an unsigned kick",
            judgedBy2124(
                ZMSGGroupProtocol.createGroupKickMessage(GID, M, X_OLD, 2, null, "", plainKey = AnnounceDriver.newGroupKey()),
                keys = mapOf(X_OLD to admin.publicKey),
                creator = X_OLD,
            ),
        )
    }

    @Test
    fun `c - today's kick and rotation memos for fixed inputs are the golden strings`() {
        val kick = ZMSGGroupProtocol.createGroupKickMessage(GID, M, X_OLD, 2, WRAPPED, KICK_SIG)
        val key = ZMSGGroupProtocol.createGroupKeyMessage(GID, X_OLD, 2, WRAPPED, KEY_SIG)

        assertEquals(GOLDEN_KICK, kick)
        assertEquals(GOLDEN_KEY, key)
        assertEquals("today's kick memo is not 2.12.4's", Frozen2124.createGroupKickMessage(GID, M, X_OLD, 2, WRAPPED, KICK_SIG), kick)
        assertEquals("today's rotation memo is not 2.12.4's", Frozen2124.createGroupKeyMessage(GID, X_OLD, 2, WRAPPED, KEY_SIG), key)
        assertEquals(GOLDEN_KICK_SIGNED, ZMSGGroupProtocol.groupKickSignedData(GID, M, X_OLD, 2, WRAPPED))
        assertEquals(GOLDEN_KEY_SIGNED, ZMSGGroupProtocol.groupKeySignedData(GID, X_OLD, 2, WRAPPED, "rotation"))
        assertEquals(GOLDEN_KICK_SIGNED, Frozen2124.groupKickSignedData(GID, M, X_OLD, 2, WRAPPED))
        assertEquals(GOLDEN_KEY_SIGNED, Frozen2124.groupKeySignedData(GID, X_OLD, 2, WRAPPED, "rotation"))
    }

    // ---------------------------------------------------------------- fixture

    /** A signed kick of [M] to epoch 2 naming [kicker], built by today's builder. */
    private fun todaysKick(
        kicker: String,
        signer: E2EKeyPair,
    ): String =
        ZMSGGroupProtocol.createGroupKickMessage(
            GID,
            M,
            kicker,
            2,
            WRAPPED,
            E2EEncryption.sign(signer.privateKey, ZMSGGroupProtocol.groupKickSignedData(GID, M, kicker, 2, WRAPPED)),
        )

    /**
     * [memo] (a kick) as a 2.12.4 phone judges it: the fields its parser reads (kicked, kicker, new_epoch, enc_key,
     * sig), its signed data, its gate. [keys] are the contact keys that phone holds, by address; [creator] is the
     * creator address of its group record.
     */
    private fun judgedBy2124(
        memo: String,
        keys: Map<String, String>,
        creator: String,
    ): Boolean {
        val prefix = "ZMSG:3.0:GROUP:GK:$GID:"
        assertTrue("not a kick memo for the group: $memo", memo.startsWith(prefix))
        val json = JSONObject(memo.removePrefix(prefix))
        val kicker = json.getString("kicker")
        val signedData =
            Frozen2124.groupKickSignedData(
                GID,
                json.getString("kicked"),
                kicker,
                json.getInt("new_epoch"),
                if (json.has("enc_key")) json.getString("enc_key") else null,
            )
        return Frozen2124.verifyGroupAdminControl(
            signerAddress = kicker,
            signedData = signedData,
            signature = if (json.has("sig")) json.getString("sig") else null,
            peerPublicKey = { keys[it] },
            creatorAddress = creator,
        )
    }
}
