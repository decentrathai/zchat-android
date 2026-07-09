package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pure-JVM tests for [ZMSGGroupProtocol] payload builders/parsers, GROUP_ACCEPT (#219) signing, and
 * roster (de)serialization. These paths use only [org.json] (available in unit tests) and
 * [E2EEncryption] (which uses java.util.Base64, JVM-safe) — NO android.util.Base64 — so they run on
 * the JVM. The AES-GCM message encrypt/decrypt round-trip (which DOES use android.util.Base64) lives
 * in the androidTest sibling [GroupMessageCryptoTest].
 *
 * Complements the existing [GroupControlSignatureTest] (kick/key signatures) and [GroupNameBoundTest]
 * (name bound) — no overlap: this file covers GROUP_ACCEPT signatures, the canonical accept/format
 * stability, and every parser/serializer not otherwise exercised.
 */
class GroupProtocolPayloadTest {

    // ==========================================
    // GROUP_ACCEPT SIGNATURE (#219)
    // ==========================================

    @Test
    fun `genuine accept signature verifies against the accepter pubkey`() {
        val accepter = E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupAcceptSignedData("gid1", "u1accepter", accepter.publicKey)
        val sig = E2EEncryption.sign(accepter.privateKey, data)
        assertTrue(E2EEncryption.verify(accepter.publicKey, data, sig))
    }

    @Test
    fun `accept signature fails when the declared address is swapped`() {
        val accepter = E2EEncryption.generateKeyPair()
        val signed = ZMSGGroupProtocol.groupAcceptSignedData("gid1", "u1realAddr", accepter.publicKey)
        val sig = E2EEncryption.sign(accepter.privateKey, signed)
        // Attacker reuses the signature but redirects fan-out to a DIFFERENT receive address (#218).
        val tampered = ZMSGGroupProtocol.groupAcceptSignedData("gid1", "u1attackerAddr", accepter.publicKey)
        assertFalse(E2EEncryption.verify(accepter.publicKey, tampered, sig))
    }

    @Test
    fun `accept signature fails when the group id is swapped`() {
        val accepter = E2EEncryption.generateKeyPair()
        val signed = ZMSGGroupProtocol.groupAcceptSignedData("gid1", "u1accepter", accepter.publicKey)
        val sig = E2EEncryption.sign(accepter.privateKey, signed)
        val tampered = ZMSGGroupProtocol.groupAcceptSignedData("gidOTHER", "u1accepter", accepter.publicKey)
        assertFalse(E2EEncryption.verify(accepter.publicKey, tampered, sig))
    }

    @Test
    fun `accept signature made by a WRONG signer does not verify`() {
        val accepter = E2EEncryption.generateKeyPair()
        val attacker = E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupAcceptSignedData("gid1", "u1accepter", accepter.publicKey)
        // Attacker forges an accept for the accepter's key but signs with THEIR own private key.
        val forged = E2EEncryption.sign(attacker.privateKey, data)
        assertFalse(E2EEncryption.verify(accepter.publicKey, data, forged))
    }

    @Test
    fun `accept canonical signed-data format is stable and field-delimited`() {
        assertEquals(
            "GA|gid1|u1accepter|pubKeyB64",
            ZMSGGroupProtocol.groupAcceptSignedData("gid1", "u1accepter", "pubKeyB64")
        )
    }

    @Test
    fun `reordering accept fields changes the signed bytes`() {
        // Swapping the address and pubkey positions must produce different signed bytes so a signature
        // over one arrangement can never validate the other.
        val asIntended = ZMSGGroupProtocol.groupAcceptSignedData("gid1", "addrX", "keyY")
        val reordered = ZMSGGroupProtocol.groupAcceptSignedData("gid1", "keyY", "addrX")
        assertNotEquals(asIntended, reordered)
    }

    // ==========================================
    // CANONICAL SIGNED-DATA STABILITY (kick / key / accept)
    // ==========================================

    @Test
    fun `an extra field would change the kick signed bytes`() {
        // The canonical string is field-delimited; a present vs absent wrapped key must be distinct.
        val withKey = ZMSGGroupProtocol.groupKickSignedData("g", "k", "a", 1, "WRAP")
        val withoutKey = ZMSGGroupProtocol.groupKickSignedData("g", "k", "a", 1, null)
        assertNotEquals(withKey, withoutKey)
        assertTrue(withKey.endsWith("|WRAP"))
        assertTrue(withoutKey.endsWith("|"))
    }

    @Test
    fun `key signed-data binds the reason field`() {
        val rotation = ZMSGGroupProtocol.groupKeySignedData("g", "a", 2, "enc", "rotation")
        val kick = ZMSGGroupProtocol.groupKeySignedData("g", "a", 2, "enc", "member_kicked")
        assertNotEquals(rotation, kick)
    }

    // ==========================================
    // GROUP_ACCEPT MESSAGE BUILD + PARSE
    // ==========================================

    @Test
    fun `accept message round-trips through build and parse with signature`() {
        val memo = ZMSGGroupProtocol.createGroupAcceptMessage("gid1", "u1accepter", "pubB64", "sigB64")
        assertEquals(GroupMessageType.GROUP_ACCEPT, ZMSGGroupProtocol.parseMessageType(memo))
        assertEquals("gid1", ZMSGGroupProtocol.parseGroupId(memo))
        val payload = ZMSGGroupProtocol.parsePayload(memo)!!
        val parsed = ZMSGGroupProtocol.parseGroupAcceptPayload(payload)!!
        assertEquals("u1accepter", parsed.accepter)
        assertEquals("pubB64", parsed.accepterPublicKey)
        assertEquals("sigB64", parsed.signature)
    }

    @Test
    fun `legacy accept without signature omits the sig field and parses as empty`() {
        val memo = ZMSGGroupProtocol.createGroupAcceptMessage("gid1", "u1accepter", "pubB64", signature = "")
        // The empty signature must NOT be serialized into the memo (keeps the memo compact).
        assertFalse(memo.contains("\"sig\""))
        val parsed = ZMSGGroupProtocol.parseGroupAcceptPayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals("", parsed.signature)
    }

    // ==========================================
    // GROUP_KICK PARSE — signature + null-vs-present enc_key
    // ==========================================

    @Test
    fun `kick payload carries the signature and a present enc_key`() {
        val memo = ZMSGGroupProtocol.createGroupKickMessage("gid1", "u1kicked", "u1admin", 4, "WRAPPED", "SIG")
        val parsed = ZMSGGroupProtocol.parseGroupKickPayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals("u1kicked", parsed.kicked)
        assertEquals("u1admin", parsed.kicker)
        assertEquals(4, parsed.newEpoch)
        assertEquals("WRAPPED", parsed.encryptedGroupKey)
        assertEquals("SIG", parsed.signature)
    }

    @Test
    fun `kick payload distinguishes a null enc_key from a present one`() {
        // No new key delivered with this kick (e.g. the kicked member never had the key).
        val memo = ZMSGGroupProtocol.createGroupKickMessage("gid1", "u1kicked", "u1admin", 4, null, "SIG")
        assertFalse("enc_key must be absent, not empty-string", memo.contains("enc_key"))
        val parsed = ZMSGGroupProtocol.parseGroupKickPayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertNull(parsed.encryptedGroupKey)
        assertEquals("SIG", parsed.signature)
    }

    @Test
    fun `kick payload with no signature parses as null signature (must not be acted on)`() {
        // A forged/legacy unsigned kick: the parser tolerates it, but signature == null tells the
        // receive handler to refuse the roster mutation (#187).
        val json = JSONObject().apply {
            put("kicked", "u1kicked")
            put("kicker", "u1admin")
            put("new_epoch", 2)
        }.toString()
        val parsed = ZMSGGroupProtocol.parseGroupKickPayload(json)!!
        assertNull(parsed.signature)
        assertNull(parsed.encryptedGroupKey)
    }

    @Test
    fun `malformed kick payload returns null instead of throwing`() {
        assertNull(ZMSGGroupProtocol.parseGroupKickPayload("{ not json"))
        // Missing required 'kicked' field.
        assertNull(ZMSGGroupProtocol.parseGroupKickPayload("""{"kicker":"a","new_epoch":1}"""))
    }

    // ==========================================
    // GROUP_KEY PARSE — signature + reason default
    // ==========================================

    @Test
    fun `key payload carries signer, epoch, enc_key, reason and signature`() {
        val memo = ZMSGGroupProtocol.createGroupKeyMessage("gid1", "u1admin", 7, "ENCKEY", "SIG", "member_left")
        val parsed = ZMSGGroupProtocol.parseGroupKeyPayload("gid1", ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals("gid1", parsed.groupId)
        assertEquals("u1admin", parsed.signer)
        assertEquals(7, parsed.epoch)
        assertEquals("ENCKEY", parsed.encryptedGroupKey)
        assertEquals("member_left", parsed.reason)
        assertEquals("SIG", parsed.signature)
    }

    @Test
    fun `key payload defaults reason to rotation when absent and null signature when unsigned`() {
        val json = JSONObject().apply {
            put("signer", "u1admin")
            put("epoch", 3)
            put("enc_key", "ENC")
        }.toString()
        val parsed = ZMSGGroupProtocol.parseGroupKeyPayload("gidX", json)!!
        assertEquals("rotation", parsed.reason)
        assertNull(parsed.signature)
    }

    @Test
    fun `malformed key payload returns null instead of throwing`() {
        assertNull(ZMSGGroupProtocol.parseGroupKeyPayload("gid", "}{"))
        // Missing required 'enc_key'.
        assertNull(ZMSGGroupProtocol.parseGroupKeyPayload("gid", """{"signer":"a","epoch":1}"""))
    }

    // ==========================================
    // GROUP_INVITE PARSE — compact tolerance (#194)
    // ==========================================

    @Test
    fun `compact session-encrypted invite parses with no members and no enc_key`() {
        val memo = ZMSGGroupProtocol.createGroupInviteCompact(
            groupId = "gid1",
            groupName = "Squad",
            inviterAddress = "u1inviter",
            keyEpoch = 2,
            encryptedGroupKey = "E2E:nonce:ct",
            isSessionEncrypted = true
        )
        // k2 (session-wrapped) present; the legacy enc_key/group_key/members all absent.
        assertTrue(memo.contains("\"k2\""))
        assertFalse(memo.contains("\"group_key\""))
        val parsed = ZMSGGroupProtocol.parseGroupInvitePayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals("Squad", parsed.groupName)
        assertEquals("u1inviter", parsed.inviter)
        assertEquals(2, parsed.keyEpoch)
        assertTrue("compact invite omits the roster", parsed.members.isEmpty())
        assertEquals("", parsed.encryptedGroupKey) // ECIES enc_key absent on compact invites
        assertEquals("", parsed.inviterPublicKey)
    }

    @Test
    fun `compact fallback invite uses group_key when no KEX session exists`() {
        val memo = ZMSGGroupProtocol.createGroupInviteCompact(
            groupId = "gid1",
            groupName = "Squad",
            inviterAddress = "u1inviter",
            keyEpoch = 0,
            encryptedGroupKey = "BASE64KEY",
            isSessionEncrypted = false
        )
        assertTrue(memo.contains("\"group_key\""))
        assertFalse(memo.contains("\"k2\""))
        // Still parses; the key material lives in group_key, not the enc_key ECIES field.
        val parsed = ZMSGGroupProtocol.parseGroupInvitePayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals("Squad", parsed.groupName)
        assertTrue(parsed.members.isEmpty())
    }

    @Test
    fun `invite builder bounds an over-long group name into the memo`() {
        val longName = "G".repeat(ZMSGGroupProtocol.MAX_GROUP_NAME_BYTES + 40)
        val memo = ZMSGGroupProtocol.createGroupInviteCompact(
            "gid1", longName, "u1inviter", 0, "k", true
        )
        val parsed = ZMSGGroupProtocol.parseGroupInvitePayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertTrue(parsed.groupName.toByteArray(Charsets.UTF_8).size <= ZMSGGroupProtocol.MAX_GROUP_NAME_BYTES)
    }

    @Test
    fun `legacy full invite with roster and inviter_pub still parses`() {
        // Forward/backward compat: a legacy invite that DID carry members + inviter_pub must still parse.
        val json = JSONObject().apply {
            put("name", "Legacy")
            put("inviter", "u1inviter")
            put("inviter_pub", "INVPUB")
            put("members", JSONArray(listOf("u1a", "u1b", "u1c")))
            put("key_epoch", 1)
            put("enc_key", "ECIESBLOB")
        }.toString()
        val parsed = ZMSGGroupProtocol.parseGroupInvitePayload(json)!!
        assertEquals(listOf("u1a", "u1b", "u1c"), parsed.members)
        assertEquals("INVPUB", parsed.inviterPublicKey)
        assertEquals("ECIESBLOB", parsed.encryptedGroupKey)
    }

    @Test
    fun `malformed invite payload returns null instead of throwing`() {
        assertNull(ZMSGGroupProtocol.parseGroupInvitePayload("not json at all"))
        // Missing required 'name'.
        assertNull(ZMSGGroupProtocol.parseGroupInvitePayload("""{"inviter":"u1x"}"""))
    }

    // ==========================================
    // MESSAGE TYPE / GROUP ID / PAYLOAD PARSING
    // ==========================================

    @Test
    fun `non-group memo yields null type, id and payload`() {
        val plain = "ZMSG|v4|ABCD1234|INIT|u1x|hi"
        assertFalse(ZMSGGroupProtocol.isGroupMessage(plain))
        assertNull(ZMSGGroupProtocol.parseMessageType(plain))
        assertNull(ZMSGGroupProtocol.parseGroupId(plain))
        assertNull(ZMSGGroupProtocol.parsePayload(plain))
    }

    @Test
    fun `all group message-type codes round-trip through fromCode`() {
        for (type in GroupMessageType.entries) {
            assertEquals(type, GroupMessageType.fromCode(type.code))
        }
        assertNull(GroupMessageType.fromCode("ZZ"))
    }

    // ==========================================
    // ROSTER SERIALIZE / DESERIALIZE ROUND-TRIP
    // ==========================================

    @Test
    fun `member roster round-trips including the invite_status field`() {
        val members = listOf(
            GroupMember(
                address = "u1admin",
                publicKey = "PUBA",
                joinedAt = Instant.ofEpochMilli(1_700_000_000_000L),
                status = MemberStatus.ACTIVE,
                isAdmin = true,
                nickname = "Boss",
                inviteStatus = null
            ),
            GroupMember(
                address = "u1member",
                publicKey = null,
                joinedAt = Instant.ofEpochMilli(1_700_000_100_000L),
                status = MemberStatus.INVITED,
                isAdmin = false,
                nickname = null,
                inviteStatus = InviteStatus.SENT
            ),
        )
        val json = ZMSGGroupProtocol.serializeGroupMembers(members)
        val restored = ZMSGGroupProtocol.deserializeGroupMembers(json)
        assertEquals(members, restored)
    }

    @Test
    fun `roster deserialize tolerates a legacy row with no invite_status field`() {
        // A roster saved before P1.4 has no "invite_status" — must default to null, not crash.
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("address", "u1legacy")
                put("joined_at", 1_700_000_000_000L)
                put("status", "ACTIVE")
                put("is_admin", false)
            })
        }.toString()
        val restored = ZMSGGroupProtocol.deserializeGroupMembers(json)
        assertEquals(1, restored.size)
        assertNull(restored[0].inviteStatus)
        assertEquals(MemberStatus.ACTIVE, restored[0].status)
    }

    @Test
    fun `roster deserialize tolerates an unknown invite_status value (forward compat)`() {
        // A future app version writes an invite_status we don't know → must map to null, not crash.
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("address", "u1future")
                put("joined_at", 1L)
                put("status", "ACTIVE")
                put("is_admin", false)
                put("invite_status", "SOME_FUTURE_STATE")
            })
        }.toString()
        val restored = ZMSGGroupProtocol.deserializeGroupMembers(json)
        assertEquals(1, restored.size)
        assertNull(restored[0].inviteStatus)
    }

    @Test
    fun `roster deserialize of an unknown member status fails closed (empty list, no crash)`() {
        // Unlike invite_status (which is per-row tolerant via runCatching), the MEMBER STATUS is parsed
        // with MemberStatus.valueOf inside the shared try — an unknown value throws and the whole
        // deserialize returns emptyList() rather than crashing. Documenting the actual fail-closed
        // behavior so a future refactor that changes it is caught.
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("address", "u1future")
                put("joined_at", 1L)
                put("status", "SOME_FUTURE_STATUS")
                put("is_admin", false)
            })
        }.toString()
        val restored = ZMSGGroupProtocol.deserializeGroupMembers(json)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `roster deserialize defaults a missing member status to ACTIVE`() {
        // When the "status" field is absent entirely, optString's "ACTIVE" default applies.
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("address", "u1nostatus")
                put("joined_at", 1L)
                put("is_admin", false)
            })
        }.toString()
        val restored = ZMSGGroupProtocol.deserializeGroupMembers(json)
        assertEquals(1, restored.size)
        assertEquals(MemberStatus.ACTIVE, restored[0].status)
    }

    @Test
    fun `roster deserialize of malformed json yields empty list not exception`() {
        assertTrue(ZMSGGroupProtocol.deserializeGroupMembers("}{").isEmpty())
        assertTrue(ZMSGGroupProtocol.deserializeGroupMembers("").isEmpty())
    }

    @Test
    fun `group info round-trips including a null group key`() {
        val info = GroupInfo(
            groupId = "zgrp_abc",
            name = "Alpha",
            creatorAddress = "u1creator",
            createdAt = Instant.ofEpochMilli(1_700_000_000_000L),
            adminPolicy = AdminPolicy.CREATOR_ONLY,
            currentEpoch = 3,
            groupKey = null,
            isActive = true
        )
        val restored = ZMSGGroupProtocol.deserializeGroupInfo(ZMSGGroupProtocol.serializeGroupInfo(info))!!
        assertEquals(info, restored)
    }

    @Test
    fun `group info round-trips including a present group key and inactive flag`() {
        val info = GroupInfo(
            groupId = "zgrp_def",
            name = "Beta",
            creatorAddress = "u1creator",
            createdAt = Instant.ofEpochMilli(1_700_000_500_000L),
            adminPolicy = AdminPolicy.CREATOR_ONLY,
            currentEpoch = 0,
            groupKey = "BASE64GROUPKEY",
            isActive = false
        )
        val restored = ZMSGGroupProtocol.deserializeGroupInfo(ZMSGGroupProtocol.serializeGroupInfo(info))!!
        assertEquals(info, restored)
    }

    @Test
    fun `group info deserialize of malformed json returns null`() {
        assertNull(ZMSGGroupProtocol.deserializeGroupInfo("nope"))
    }

    // ==========================================
    // GROUP_MSG PAYLOAD (parse only — encryption round-trip is in androidTest)
    // ==========================================

    @Test
    fun `group msg payload parses seq epoch sender nonce ct and ts`() {
        val json = JSONObject().apply {
            put("seq", 42L)
            put("epoch", 5)
            put("sender", "u1sender")
            put("nonce", "NONCEB64")
            put("ct", "CTB64")
            put("ts", 1_700_000_000L)
        }.toString()
        val parsed = ZMSGGroupProtocol.parseGroupMsgPayload(json)!!
        assertEquals(42L, parsed.seq)
        assertEquals(5, parsed.epoch)
        assertEquals("u1sender", parsed.sender)
        assertEquals("NONCEB64", parsed.nonce)
        assertEquals("CTB64", parsed.ciphertext)
        assertEquals(1_700_000_000L, parsed.timestamp)
    }

    @Test
    fun `group leave payload parses leaver and ts`() {
        val memo = ZMSGGroupProtocol.createGroupLeaveMessage("gid1", "u1leaver")
        assertEquals(GroupMessageType.GROUP_LEAVE, ZMSGGroupProtocol.parseMessageType(memo))
        val parsed = ZMSGGroupProtocol.parseGroupLeavePayload(ZMSGGroupProtocol.parsePayload(memo)!!)!!
        assertEquals("u1leaver", parsed.leaver)
        assertTrue("leave ts should be a positive unix time", parsed.timestamp > 0L)
    }

    // ==========================================
    // GROUP_MSG AUTHOR SIGNATURE (#6)
    // ==========================================
    // The group key is SYMMETRIC, so any member could stamp another member's address on a GROUP_MSG
    // they authored. Each sender signs their copy over groupMsgSignedData with their pairwise KEX key;
    // the on-chain receive path verifies that signature against the sender's held key to decide whether
    // the claimed sender is AUTHENTICATED (a mismatch is failed-open — rendered best-effort, not dropped
    // — because a legit re-KEX'd sender's rotation-key signature is indistinguishable from a forgery at
    // that layer). These tests lock in the verify PRIMITIVE that decision keys off: a genuine signature
    // verifies, and ANY tamper of the bound fields (sender / ciphertext / epoch / seq) or a wrong key
    // makes it fail — so a forged attribution is reliably detectable. (The AES-GCM ciphertext round-trip
    // and the ChatViewModel fail-open decision live in androidTest; these cover the pure-JVM crypto/wire.)

    @Test
    fun `genuine group-msg author signature verifies against the sender pubkey`() {
        val sender = E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 7L, "Y2lwaGVydGV4dA==")
        val sig = E2EEncryption.sign(sender.privateKey, data)
        assertTrue(E2EEncryption.verify(sender.publicKey, data, sig))
    }

    @Test
    fun `group-msg signed data has the exact canonical form`() {
        // MUST match sign+verify byte-for-byte; the '|' delimiter is domain-separated by the "GM" prefix.
        assertEquals(
            "GM|gid1|u1sender|3|7|Y2lwaGVydGV4dA==",
            ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 7L, "Y2lwaGVydGV4dA==")
        )
    }

    @Test
    fun `group-msg signature fails when the claimed sender is swapped (impersonation)`() {
        val sender = E2EEncryption.generateKeyPair()
        val sig = E2EEncryption.sign(
            sender.privateKey,
            ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 7L, "Y2lwaGVydGV4dA==")
        )
        // Another member reuses the ciphertext+sig but stamps a DIFFERENT sender address on it.
        val forged = ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1victim", 3, 7L, "Y2lwaGVydGV4dA==")
        assertFalse(E2EEncryption.verify(sender.publicKey, forged, sig))
    }

    @Test
    fun `group-msg signature fails when the ciphertext is tampered (content-binding)`() {
        val sender = E2EEncryption.generateKeyPair()
        val sig = E2EEncryption.sign(
            sender.privateKey,
            ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 7L, "Y2lwaGVydGV4dA==")
        )
        // A captured signature must not lift onto different content at the same (epoch, seq).
        val tampered = ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 7L, "ZGlmZmVyZW50")
        assertFalse(E2EEncryption.verify(sender.publicKey, tampered, sig))
    }

    @Test
    fun `group-msg signature fails when epoch or seq changes (replay-reorder)`() {
        val sender = E2EEncryption.generateKeyPair()
        val sig = E2EEncryption.sign(
            sender.privateKey,
            ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 7L, "Y2lwaGVydGV4dA==")
        )
        assertFalse(
            "epoch bump must break the signature",
            E2EEncryption.verify(sender.publicKey, ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 4, 7L, "Y2lwaGVydGV4dA=="), sig)
        )
        assertFalse(
            "seq bump must break the signature",
            E2EEncryption.verify(sender.publicKey, ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 8L, "Y2lwaGVydGV4dA=="), sig)
        )
    }

    @Test
    fun `group-msg signature fails against a different peer key`() {
        val sender = E2EEncryption.generateKeyPair()
        val other = E2EEncryption.generateKeyPair()
        val data = ZMSGGroupProtocol.groupMsgSignedData("gid1", "u1sender", 3, 7L, "Y2lwaGVydGV4dA==")
        val sig = E2EEncryption.sign(sender.privateKey, data)
        // Verifying the genuine signature against the WRONG held key (e.g. we hold a stale/other peer's
        // key) fails — which the receiver treats as UNauthenticated + fail-open, never a hard drop.
        assertFalse(E2EEncryption.verify(other.publicKey, data, sig))
    }
}
