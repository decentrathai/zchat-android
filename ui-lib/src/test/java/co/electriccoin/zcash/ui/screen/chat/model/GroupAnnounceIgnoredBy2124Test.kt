package co.electriccoin.zcash.ui.screen.chat.model

import android.util.Log
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.Nip17
import co.electriccoin.zcash.ui.nostr.Nip17SendsCapabilityTagTest
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA1 (MEMBER_ANNOUNCE_DESIGN.md v2 §7) — what the new wire and stored data do on a v2.12.4 phone.
 *
 * [Frozen2124] holds VERBATIM copies from v2.12.4 (a1fb4136f), frozen here so this test keeps modelling a 2.12.4
 * phone whatever HEAD does next (the pattern of Nip17SendsCapabilityTagTest.receiveAs2124):
 * - GroupModels.kt:40-53, GroupMessageType and fromCode;
 * - ZMSGGroupProtocol.kt:73-100, parseMessageType, parseGroupId, parsePayload (with isGroupMessage and the prefix);
 * - ZMSGGroupProtocol.kt:697 deserializeGroupInfo and :738 deserializeGroupMembers;
 * - the dispatch `when` of ChatViewModel.processGroupMessage (:7894-7925), each handler call reduced to a recorder
 *   entry, including the `else` branch that logs "Unhandled GROUP message type";
 * - the GL read `json.getString("leaver")` of processGroupLeave (:8599).
 * ktlint reformatted the copies' whitespace and turned three block bodies into expression bodies; no token of logic
 * differs from v2.12.4.
 *
 * Wanted: a GP (A and R, with and without np) and a GQ reach the `else` branch and change nothing; a signed GL gives
 * the same leaver and nothing else; a GroupInfo written with `announcer_rebuilt` loads with every known field and
 * leaves the roster whole; a NIP-17 rumor carrying the new `gp1` tag reads the same.
 *
 * All addresses, keys and group ids are synthetic.
 */
class GroupAnnounceIgnoredBy2124Test {
    private val api = AnnounceApi

    private fun dispatch2124(memo: String): List<String> = mutableListOf<String>().also { Frozen2124.processGroupMessage(memo, it) }

    private val rosterJson =
        ZMSGGroupProtocol.serializeGroupMembers(
            listOf(
                GroupMember(CREATOR, "cHVi", Instant.ofEpochMilli(1_700_000_000_000L), MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(MEMBER, null, Instant.ofEpochMilli(1_700_000_001_000L), MemberStatus.ACTIVE, nickname = "Bo"),
                GroupMember(LEAVER, null, Instant.ofEpochMilli(1_700_000_002_000L), MemberStatus.LEFT),
            ),
        )
    private val infoJson =
        ZMSGGroupProtocol.serializeGroupInfo(
            GroupInfo(GID, "Team", CREATOR, Instant.ofEpochMilli(1_700_000_000_000L), currentEpoch = 2, groupKey = "a2V5"),
        )

    @Test
    fun `a GP built by the new builder reaches the frozen else branch and changes nothing`() {
        val admin = E2EEncryption.generateKeyPair()
        for (op in listOf(MaOp.A, MaOp.R)) {
            for (np in listOf(null, NP)) {
                val sig = E2EEncryption.sign(admin.privateKey, api.groupMemberAnnounceSignedData(GID, op, MEMBER, N, 2, np))
                val memo = api.createGroupMemberAnnounceMessage(GID, op, MEMBER, N, 2, sig, np)
                assertNotNull(memo)
                val rosterBefore = rosterJson
                val infoBefore = infoJson
                assertEquals("op=$op np=$np", listOf("Unhandled GROUP message type: null"), dispatch2124(memo!!))
                assertEquals(GID, Frozen2124.parseGroupId(memo))
                assertEquals(rosterBefore, rosterJson)
                assertEquals(infoBefore, infoJson)
            }
        }
    }

    @Test
    fun `a GQ reaches the frozen else branch`() {
        val memo = api.createGroupRosterQueryMessage(GID)
        assertEquals(listOf("Unhandled GROUP message type: null"), dispatch2124(memo))
    }

    @Test
    fun `a signed GL gives a v2_12_4 phone the same leaver and nothing else`() {
        val member = E2EEncryption.generateKeyPair()
        val ts = 1_790_000_000L
        val sig = E2EEncryption.sign(member.privateKey, api.groupLeaveSignedData(GID, LEAVER, ts))
        val signed = api.createGroupLeaveMessage(GID, LEAVER, sig, ts)
        assertTrue("fixture: the GL carries its signature", JSONObject(ZMSGGroupProtocol.parsePayload(signed)!!).has("sig"))
        assertEquals(listOf("GROUP_LEAVE"), dispatch2124(signed))
        assertEquals(LEAVER, Frozen2124.leaverOf(Frozen2124.parsePayload(signed)!!))

        val unsigned = api.createGroupLeaveMessage(GID, LEAVER, null, ts)
        assertEquals(Frozen2124.leaverOf(Frozen2124.parsePayload(unsigned)!!), Frozen2124.leaverOf(Frozen2124.parsePayload(signed)!!))
    }

    @Test
    fun `a GroupInfo written with announcer_rebuilt loads on v2_12_4 with every known field and the roster whole`() {
        val rebuilt = ZMSGGroupProtocol.deserializeGroupInfo(JSONObject(infoJson).put("announcer_rebuilt", true).toString())!!
        assertTrue("fixture: HEAD reads the flag", api.announcerRebuilt(rebuilt))
        val written = ZMSGGroupProtocol.serializeGroupInfo(rebuilt)
        assertTrue(JSONObject(written).has("announcer_rebuilt"))

        val old = Frozen2124.deserializeGroupInfo(written)
        assertNotNull(old)
        assertEquals(rebuilt.groupId, old!!.groupId)
        assertEquals(rebuilt.name, old.name)
        assertEquals(rebuilt.creatorAddress, old.creatorAddress)
        assertEquals(rebuilt.createdAt, old.createdAt)
        assertEquals(rebuilt.adminPolicy, old.adminPolicy)
        assertEquals(rebuilt.currentEpoch, old.currentEpoch)
        assertEquals(rebuilt.groupKey, old.groupKey)
        assertEquals(rebuilt.isActive, old.isActive)

        val roster = Frozen2124.deserializeGroupMembers(rosterJson)
        assertEquals(3, roster.size)
        assertEquals(listOf(MemberStatus.ACTIVE, MemberStatus.ACTIVE, MemberStatus.LEFT), roster.map { it.status })
    }

    @Test
    fun `a roster changed by an announce still loads on v2_12_4`() {
        // A GP merge writes only statuses v2.12.4 knows (no new MemberStatus): an R tombstone is LEFT.
        val merged =
            api.applyMemberAnnounce(
                MaState(),
                ZMSGGroupProtocol.deserializeGroupMembers(rosterJson),
                MaAnn(GID, MaOp.R, "u1" + "z".repeat(60), N, 2, "c2ln"),
                false,
                false,
                { it },
                { null },
            )
        val json = ZMSGGroupProtocol.serializeGroupMembers(merged.roster)
        val old = Frozen2124.deserializeGroupMembers(json)
        assertEquals(4, old.size)
        assertEquals(merged.roster.map { it.address to it.status }, old.map { it.address to it.status })
    }

    @Test
    fun `a NIP-17 rumor with the gp1 tag reads the same on v2_12_4`() {
        val alice = NOSTRIdentity.fromSeed(ByteArray(64) { (0x10 + it).toByte() })
        val bob = NOSTRIdentity.fromSeed(ByteArray(64) { (0xA0 - it).toByte() })
        val content = api.createGroupRosterQueryMessage(GID)
        val sent = Nip17.send(alice.privateKey, alice.publicKey, bob.publicKey, content, createdAtSec = 1_790_000_000L)
        val now = Nip17.receive(sent.giftWrapJson, bob.privateKey)
        assertTrue("fixture: the rumor carries gp1", now.rumorTags.contains(listOf("zchat_cap", "gp1")))
        val old = Nip17SendsCapabilityTagTest.receiveAs2124(sent.giftWrapJson, bob.privateKey)
        assertEquals(content, old.content)
        assertEquals(now.content, old.content)
        assertEquals(sent.rumorId, old.rumorId)
        assertEquals(listOf("Unhandled GROUP message type: null"), dispatch2124(old.content))
    }

    /** VERBATIM v2.12.4 (a1fb4136f) code; only the handler calls of the dispatch are reduced to [recorder] entries. */
    @Suppress("MaxLineLength", "TooGenericExceptionCaught", "SwallowedException")
    private object Frozen2124 {
        private const val TAG = "ZMSG_GROUP"

        // ZMSGConstants.Prefixes.GROUP at a1fb4136f
        private const val GROUP_PREFIX = "ZMSG:3.0:GROUP:"

        // GroupModels.kt:40-53
        enum class GroupMessageType(
            val code: String
        ) {
            GROUP_CREATE("GC"), // Create new group
            GROUP_INVITE("GI"), // Invite member
            GROUP_ACCEPT("GA"), // Accept invitation
            GROUP_LEAVE("GL"), // Leave group
            GROUP_KICK("GK"), // Kick member (admin only)
            GROUP_MSG("GM"), // Regular message
            GROUP_KEY("GY"), // Key rotation
            GROUP_INFO("GF"); // Update group info

            companion object {
                fun fromCode(code: String): GroupMessageType? =
                    entries.find { it.code == code }
            }
        }

        fun isGroupMessage(memo: String): Boolean = memo.startsWith(GROUP_PREFIX)

        // ZMSGGroupProtocol.kt:73-100

        /**
         * Parse the message type from a group message
         */
        fun parseMessageType(memo: String): GroupMessageType? {
            if (!isGroupMessage(memo)) return null
            val parts = memo.removePrefix(GROUP_PREFIX).split(":", limit = 3)
            if (parts.isEmpty()) return null
            return GroupMessageType.fromCode(parts[0])
        }

        /**
         * Parse group ID from a group message
         */
        fun parseGroupId(memo: String): String? {
            if (!isGroupMessage(memo)) return null
            val parts = memo.removePrefix(GROUP_PREFIX).split(":", limit = 3)
            if (parts.size < 2) return null
            return parts[1]
        }

        /**
         * Parse payload JSON from a group message
         */
        fun parsePayload(memo: String): String? {
            if (!isGroupMessage(memo)) return null
            val parts = memo.removePrefix(GROUP_PREFIX).split(":", limit = 3)
            if (parts.size < 3) return null
            return parts[2]
        }

        // ZMSGGroupProtocol.kt:697

        /**
         * Deserialize GroupInfo from JSON
         */
        fun deserializeGroupInfo(json: String): GroupInfo? =
            try {
                val obj = JSONObject(json)
                GroupInfo(
                    groupId = obj.getString("group_id"),
                    name = obj.getString("name"),
                    creatorAddress = obj.getString("creator"),
                    createdAt = java.time.Instant.ofEpochMilli(obj.getLong("created_at")),
                    adminPolicy = AdminPolicy.valueOf(obj.optString("admin_policy", "CREATOR_ONLY")),
                    currentEpoch = obj.optInt("current_epoch", 0),
                    groupKey = if (obj.has("group_key")) obj.getString("group_key") else null,
                    isActive = obj.optBoolean("is_active", true)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize GroupInfo", e)
                null
            }

        // ZMSGGroupProtocol.kt:738

        /**
         * Deserialize GroupMember list from JSON
         */
        fun deserializeGroupMembers(json: String): List<GroupMember> =
            try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    GroupMember(
                        address = obj.getString("address"),
                        publicKey = if (obj.has("public_key")) obj.getString("public_key") else null,
                        joinedAt = java.time.Instant.ofEpochMilli(obj.getLong("joined_at")),
                        status = MemberStatus.valueOf(obj.optString("status", "ACTIVE")),
                        isAdmin = obj.optBoolean("is_admin", false),
                        nickname = if (obj.has("nickname")) obj.getString("nickname") else null,
                        // P1.4: tolerate legacy rosters (no field) and unknown values (forward compat)
                        inviteStatus =
                            obj
                                .optString("invite_status")
                                .takeIf { it.isNotEmpty() }
                                ?.let { runCatching { InviteStatus.valueOf(it) }.getOrNull() }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize GroupMembers", e)
                emptyList()
            }

        // ChatViewModel.kt processGroupMessage (:7894-7925), handler calls reduced to recorder entries
        fun processGroupMessage(
            memo: String,
            recorder: MutableList<String>,
        ) {
            val messageType = parseMessageType(memo)
            val groupId = parseGroupId(memo)
            val payload = parsePayload(memo)

            if (groupId == null || payload == null) {
                Log.w("ZCHAT_GROUP", "Invalid GROUP message: missing groupId or payload")
                recorder += "Invalid GROUP message"
                return
            }

            Log.d("ZCHAT_GROUP", "Processing GROUP message: type=$messageType, groupId=$groupId")

            when (messageType) {
                GroupMessageType.GROUP_INVITE -> {
                    recorder += "GROUP_INVITE" // processGroupInvite(groupId, payload, txId, timestamp, authenticatedSender, nostrEventId)
                }
                GroupMessageType.GROUP_MSG -> {
                    recorder += "GROUP_MSG" // processGroupMsg(groupId, payload, txId, timestamp, authenticatedSender)
                }
                GroupMessageType.GROUP_ACCEPT -> {
                    recorder += "GROUP_ACCEPT" // processGroupAccept(groupId, payload, txId)
                }
                GroupMessageType.GROUP_LEAVE -> {
                    recorder += "GROUP_LEAVE" // processGroupLeave(groupId, payload, txId, authenticatedSender)
                }
                GroupMessageType.GROUP_KICK -> {
                    recorder += "GROUP_KICK" // processGroupKick(groupId, payload, txId)
                }
                GroupMessageType.GROUP_KEY -> {
                    recorder += "GROUP_KEY" // processGroupKey(groupId, payload, txId)
                }
                else -> {
                    Log.d("ZCHAT_GROUP", "Unhandled GROUP message type: $messageType")
                    recorder += "Unhandled GROUP message type: $messageType"
                }
            }
        }

        // ChatViewModel.kt processGroupLeave (:8598-8599)
        fun leaverOf(payload: String): String {
            val json = org.json.JSONObject(payload)
            val leaverAddress = json.getString("leaver")
            return leaverAddress
        }
    }

    private companion object {
        const val GID = "zgrp_0123456789abcdef01234567"
        const val N = 1_790_000_000_123L
        val NP = "0123456789abcdef".repeat(4)
        val CREATOR = "u1" + "c".repeat(60)
        val MEMBER = "u1" + "m".repeat(60)
        val LEAVER = "u1" + "l".repeat(60)
    }
}
