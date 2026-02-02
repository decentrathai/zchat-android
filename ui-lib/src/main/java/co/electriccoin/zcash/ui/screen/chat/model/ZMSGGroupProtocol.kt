package co.electriccoin.zcash.ui.screen.chat.model

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ZMSG-GROUP Protocol for Group Chat Messages
 *
 * Protocol format: ZMSG:3.0:GROUP:<type>:<group_id>:<payload>
 *
 * Message types:
 * - GC: GROUP_CREATE - Create new group
 * - GI: GROUP_INVITE - Invite member
 * - GA: GROUP_ACCEPT - Accept invitation
 * - GL: GROUP_LEAVE - Leave group
 * - GK: GROUP_KICK - Kick member
 * - GM: GROUP_MSG - Regular message
 * - GY: GROUP_KEY - Key rotation
 * - GF: GROUP_INFO - Update group info
 */
object ZMSGGroupProtocol {

    private const val TAG = "ZMSG_GROUP"

    // Protocol prefix
    private const val GROUP_PREFIX = "ZMSG:3.0:GROUP:"

    // AES-256-GCM parameters
    private const val AES_KEY_SIZE = 256
    private const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    /**
     * Check if a memo is a group protocol message
     */
    fun isGroupMessage(memo: String): Boolean {
        return memo.startsWith(GROUP_PREFIX)
    }

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

    // ==========================================
    // MESSAGE CREATION
    // ==========================================

    /**
     * Create a GROUP_CREATE message
     */
    fun createGroupCreateMessage(
        groupId: String,
        name: String,
        creatorAddress: String,
        members: List<String>,
        groupKey: ByteArray
    ): String {
        val payload = JSONObject().apply {
            put("name", name)
            put("creator", creatorAddress)
            put("created_at", System.currentTimeMillis() / 1000)
            put("members", JSONArray(members))
            put("admin_policy", AdminPolicy.CREATOR_ONLY.name)
            put("key_epoch", 0)
            // Group key will be encrypted per-recipient in the send flow
        }
        return "${GROUP_PREFIX}GC:$groupId:${payload}"
    }

    /**
     * Create a GROUP_INVITE message for a specific member
     * The group key is encrypted with the recipient's public key
     */
    fun createGroupInviteMessage(
        groupId: String,
        groupName: String,
        inviterAddress: String,
        inviterPublicKey: String,
        allMembers: List<String>,
        keyEpoch: Int,
        encryptedGroupKey: String  // Pre-encrypted with recipient's key
    ): String {
        val payload = JSONObject().apply {
            put("name", groupName)
            put("inviter", inviterAddress)
            put("inviter_pub", inviterPublicKey)
            put("members", JSONArray(allMembers))
            put("key_epoch", keyEpoch)
            put("enc_key", encryptedGroupKey)
        }
        return "${GROUP_PREFIX}GI:$groupId:${payload}"
    }

    /**
     * Create a simplified GROUP_INVITE message with the group key included directly.
     * Note: This includes the key in base64 - less secure but simpler for initial implementation.
     * TODO: Add per-recipient encryption using their public key
     */
    fun createGroupInviteMessage(
        groupId: String,
        groupName: String,
        inviterAddress: String,
        inviteeAddress: String,
        groupKey: ByteArray,
        memberAddresses: List<String>
    ): String {
        val encodedKey = Base64.encodeToString(groupKey, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("name", groupName)
            put("inviter", inviterAddress)
            put("invitee", inviteeAddress)
            put("members", JSONArray(memberAddresses))
            put("key_epoch", 0)
            put("group_key", encodedKey)
        }
        return "${GROUP_PREFIX}GI:$groupId:${payload}"
    }

    /**
     * Create a GROUP_ACCEPT message
     */
    fun createGroupAcceptMessage(
        groupId: String,
        accepterAddress: String,
        accepterPublicKey: String
    ): String {
        val payload = JSONObject().apply {
            put("accepter", accepterAddress)
            put("accepter_pub", accepterPublicKey)
        }
        return "${GROUP_PREFIX}GA:$groupId:${payload}"
    }

    /**
     * Create a GROUP_MSG message (encrypted group message)
     */
    fun createGroupMsgMessage(
        groupId: String,
        seq: Long,
        epoch: Int,
        senderAddress: String,
        plaintext: String,
        groupKey: ByteArray
    ): String {
        // Encrypt the message
        val encrypted = encryptMessage(plaintext, groupKey)

        val payload = JSONObject().apply {
            put("seq", seq)
            put("epoch", epoch)
            put("sender", senderAddress)
            put("nonce", encrypted.nonce)
            put("ct", encrypted.ciphertext)
            put("ts", System.currentTimeMillis() / 1000)
        }
        return "${GROUP_PREFIX}GM:$groupId:${payload}"
    }

    /**
     * Create a GROUP_LEAVE message
     */
    fun createGroupLeaveMessage(
        groupId: String,
        leaverAddress: String
    ): String {
        val payload = JSONObject().apply {
            put("leaver", leaverAddress)
            put("ts", System.currentTimeMillis() / 1000)
        }
        return "${GROUP_PREFIX}GL:$groupId:${payload}"
    }

    /**
     * Create a GROUP_KICK message
     */
    fun createGroupKickMessage(
        groupId: String,
        kickedAddress: String,
        kickerAddress: String,
        newEpoch: Int,
        encryptedNewKey: String?
    ): String {
        val payload = JSONObject().apply {
            put("kicked", kickedAddress)
            put("kicker", kickerAddress)
            put("new_epoch", newEpoch)
            encryptedNewKey?.let { put("enc_key", it) }
        }
        return "${GROUP_PREFIX}GK:$groupId:${payload}"
    }

    /**
     * Create a GROUP_KEY message (key rotation)
     */
    fun createGroupKeyMessage(
        groupId: String,
        newEpoch: Int,
        encryptedGroupKey: String,
        reason: String = "rotation"
    ): String {
        val payload = JSONObject().apply {
            put("epoch", newEpoch)
            put("enc_key", encryptedGroupKey)
            put("reason", reason)
            put("ts", System.currentTimeMillis() / 1000)
        }
        return "${GROUP_PREFIX}GY:$groupId:${payload}"
    }

    /**
     * Create a GROUP_INFO message (update group info)
     */
    fun createGroupInfoMessage(
        groupId: String,
        newName: String? = null,
        updaterAddress: String
    ): String {
        val payload = JSONObject().apply {
            newName?.let { put("name", it) }
            put("updater", updaterAddress)
            put("ts", System.currentTimeMillis() / 1000)
        }
        return "${GROUP_PREFIX}GF:$groupId:${payload}"
    }

    // ==========================================
    // PAYLOAD PARSING
    // ==========================================

    /**
     * Parse GROUP_CREATE payload
     */
    fun parseGroupCreatePayload(payload: String): GroupCreatePayload? {
        return try {
            val json = JSONObject(payload)
            GroupCreatePayload(
                groupId = "", // Set by caller from parsed groupId
                name = json.getString("name"),
                creator = json.getString("creator"),
                createdAt = json.getLong("created_at"),
                members = parseJsonArray(json.getJSONArray("members")),
                adminPolicy = json.optString("admin_policy", "CREATOR_ONLY"),
                keyEpoch = json.optInt("key_epoch", 0),
                encryptedGroupKey = json.optString("enc_key", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_CREATE payload", e)
            null
        }
    }

    /**
     * Parse GROUP_INVITE payload
     */
    fun parseGroupInvitePayload(payload: String): GroupInvitePayload? {
        return try {
            val json = JSONObject(payload)
            GroupInvitePayload(
                groupId = "", // Set by caller
                groupName = json.getString("name"),
                inviter = json.getString("inviter"),
                inviterPublicKey = json.optString("inviter_pub", ""),
                members = parseJsonArray(json.getJSONArray("members")),
                keyEpoch = json.optInt("key_epoch", 0),
                encryptedGroupKey = json.getString("enc_key")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_INVITE payload", e)
            null
        }
    }

    /**
     * Parse GROUP_ACCEPT payload
     */
    fun parseGroupAcceptPayload(payload: String): GroupAcceptPayload? {
        return try {
            val json = JSONObject(payload)
            GroupAcceptPayload(
                groupId = "", // Set by caller
                accepter = json.getString("accepter"),
                accepterPublicKey = json.optString("accepter_pub", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_ACCEPT payload", e)
            null
        }
    }

    /**
     * Parse GROUP_MSG payload
     */
    fun parseGroupMsgPayload(payload: String): GroupMsgPayload? {
        return try {
            val json = JSONObject(payload)
            GroupMsgPayload(
                groupId = "", // Set by caller
                seq = json.getLong("seq"),
                epoch = json.getInt("epoch"),
                sender = json.getString("sender"),
                nonce = json.getString("nonce"),
                ciphertext = json.getString("ct"),
                timestamp = json.getLong("ts")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_MSG payload", e)
            null
        }
    }

    /**
     * Parse GROUP_LEAVE payload
     */
    fun parseGroupLeavePayload(payload: String): GroupLeavePayload? {
        return try {
            val json = JSONObject(payload)
            GroupLeavePayload(
                groupId = "", // Set by caller
                leaver = json.getString("leaver"),
                timestamp = json.getLong("ts")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_LEAVE payload", e)
            null
        }
    }

    /**
     * Parse GROUP_KICK payload
     */
    fun parseGroupKickPayload(payload: String): GroupKickPayload? {
        return try {
            val json = JSONObject(payload)
            GroupKickPayload(
                groupId = "", // Set by caller
                kicked = json.getString("kicked"),
                kicker = json.getString("kicker"),
                newEpoch = json.getInt("new_epoch"),
                encryptedGroupKey = if (json.has("enc_key")) json.getString("enc_key") else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_KICK payload", e)
            null
        }
    }

    private fun parseJsonArray(jsonArray: JSONArray): List<String> {
        return (0 until jsonArray.length()).map { jsonArray.getString(it) }
    }

    // ==========================================
    // ENCRYPTION
    // ==========================================

    /**
     * Generate a random AES-256 group key
     */
    fun generateGroupKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE)
        return keyGen.generateKey().encoded
    }

    /**
     * Encrypt a message with the group key using AES-256-GCM
     */
    fun encryptMessage(plaintext: String, groupKey: ByteArray): EncryptedMessage {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey: SecretKey = SecretKeySpec(groupKey, "AES")

        // Generate random nonce
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return EncryptedMessage(
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
    }

    /**
     * Decrypt a message with the group key
     */
    fun decryptMessage(nonce: String, ciphertext: String, groupKey: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey: SecretKey = SecretKeySpec(groupKey, "AES")

            val nonceBytes = Base64.decode(nonce, Base64.NO_WRAP)
            val ciphertextBytes = Base64.decode(ciphertext, Base64.NO_WRAP)

            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonceBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plaintext = cipher.doFinal(ciphertextBytes)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message", e)
            null
        }
    }

    /**
     * Encode group key for storage/transport
     */
    fun encodeGroupKey(groupKey: ByteArray): String {
        return Base64.encodeToString(groupKey, Base64.NO_WRAP)
    }

    /**
     * Decode group key from storage/transport
     */
    fun decodeGroupKey(encodedKey: String): ByteArray {
        return Base64.decode(encodedKey, Base64.NO_WRAP)
    }

    // ==========================================
    // JSON SERIALIZATION HELPERS
    // ==========================================

    /**
     * Serialize GroupInfo to JSON
     */
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

    /**
     * Deserialize GroupInfo from JSON
     */
    fun deserializeGroupInfo(json: String): GroupInfo? {
        return try {
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
    }

    /**
     * Serialize GroupMember list to JSON
     */
    fun serializeGroupMembers(members: List<GroupMember>): String {
        val array = JSONArray()
        members.forEach { member ->
            array.put(JSONObject().apply {
                put("address", member.address)
                member.publicKey?.let { put("public_key", it) }
                put("joined_at", member.joinedAt.toEpochMilli())
                put("status", member.status.name)
                put("is_admin", member.isAdmin)
                member.nickname?.let { put("nickname", it) }
            })
        }
        return array.toString()
    }

    /**
     * Deserialize GroupMember list from JSON
     */
    fun deserializeGroupMembers(json: String): List<GroupMember> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                GroupMember(
                    address = obj.getString("address"),
                    publicKey = if (obj.has("public_key")) obj.getString("public_key") else null,
                    joinedAt = java.time.Instant.ofEpochMilli(obj.getLong("joined_at")),
                    status = MemberStatus.valueOf(obj.optString("status", "ACTIVE")),
                    isAdmin = obj.optBoolean("is_admin", false),
                    nickname = if (obj.has("nickname")) obj.getString("nickname") else null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize GroupMembers", e)
            emptyList()
        }
    }

    /**
     * Data class for encrypted message
     */
    data class EncryptedMessage(
        val nonce: String,
        val ciphertext: String
    )
}
