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

    // Protocol prefix - use centralized constant
    private const val GROUP_PREFIX = ZMSGConstants.Prefixes.GROUP

    // #195 bound an unbounded field: the user-typed group name is embedded verbatim in the on-chain
    // GROUP_INVITE memo, which also carries a ~213-byte inviter address + ~100-byte wrapped key inside
    // the 512-byte budget. An unbounded name could overflow it. 100 UTF-8 bytes leaves comfortable
    // headroom while accommodating any reasonable name. Enforced byte-safely (never splits a code point).
    const val MAX_GROUP_NAME_BYTES = 100

    /**
     * Cap a user-typed group name to [MAX_GROUP_NAME_BYTES] UTF-8 bytes, byte-safely (never splits a
     * multibyte code point). Apply at the source (group creation) so the stored name matches the one
     * that goes on-chain, and defensively in the invite builder so the memo can't overflow no matter
     * the caller. Idempotent for already-short names.
     */
    fun boundGroupName(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_GROUP_NAME_BYTES) return name
        var result = name
        while (result.isNotEmpty() && result.toByteArray(Charsets.UTF_8).size > MAX_GROUP_NAME_BYTES) {
            result = result.substring(0, result.length - 1)
        }
        return result
    }

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
     * Create a COMPACT GROUP_INVITE that fits within Zcash's 512-byte memo limit.
     *
     * The legacy invites embedded the full member roster (N × ~213-byte unified addresses) plus an
     * ECIES blob (~212 bytes — it carries a fresh ephemeral public key every time). That overflowed
     * 512 bytes for ANY group size, so invites silently failed with MemoTooLong and groups never
     * formed (#194). This form carries only what the invitee needs to JOIN:
     *   - name, inviter, key_epoch
     *   - "k2": the group key wrapped under the EXISTING authenticated KEX session shared with the
     *           invitee (E2EEncryption.encrypt → "E2E:<nonce>:<ct>", ~100 bytes, no ephemeral key) —
     *           smaller AND more secure than ECIES (the session is authenticated by the KEX).
     *     or
     *   - "group_key": the plaintext base64 key (legacy fallback used only when no KEX exists).
     *
     * The full member roster is intentionally omitted; each member discovers peers lazily as they
     * post (ChatViewModel.addOrActivateGroupMember). This keeps invites 100% on private Zcash — no
     * NOSTR, no public relay metadata — and within a single memo (no multi-part reassembly needed).
     */
    fun createGroupInviteCompact(
        groupId: String,
        groupName: String,
        inviterAddress: String,
        keyEpoch: Int,
        encryptedGroupKey: String,
        isSessionEncrypted: Boolean
    ): String {
        val payload = JSONObject().apply {
            put("name", boundGroupName(groupName))
            put("inviter", inviterAddress)
            put("key_epoch", keyEpoch)
            if (isSessionEncrypted) {
                put("k2", encryptedGroupKey)
            } else {
                put("group_key", encryptedGroupKey)
            }
        }
        return "${GROUP_PREFIX}GI:$groupId:${payload}"
    }

    /**
     * Canonical bytes a GROUP_ACCEPT signature (#219) covers. MUST match exactly on sign + verify.
     * Binds the group, the accepter's declared receive address, and their E2E public key, so a verified
     * signature proves the address-adoption (#218) was authorized by the holder of that key — not anyone
     * who merely observed the (public) key. Sibling of groupKickSignedData / groupKeySignedData (#187).
     */
    fun groupAcceptSignedData(
        groupId: String,
        accepterAddress: String,
        accepterPublicKey: String
    ): String = "GA|$groupId|$accepterAddress|$accepterPublicKey"

    /**
     * Create a GROUP_ACCEPT message. [signature] is the accepter's signature over
     * [groupAcceptSignedData] (empty for legacy pre-#219 accepts).
     */
    fun createGroupAcceptMessage(
        groupId: String,
        accepterAddress: String,
        accepterPublicKey: String,
        signature: String = ""
    ): String {
        val payload = JSONObject().apply {
            put("accepter", accepterAddress)
            put("accepter_pub", accepterPublicKey)
            if (signature.isNotEmpty()) put("sig", signature)
        }
        return "${GROUP_PREFIX}GA:$groupId:${payload}"
    }

    /**
     * Create a GROUP_MSG message (encrypted group message).
     *
     * [signer] (author-authentication) is invoked with groupMsgSignedData(GM|groupId|sender|epoch|seq|ct)
     * and returns the sender's signature over it (or null). The group key is SYMMETRIC, so without a
     * per-author signature any member could stamp ANOTHER member's address on a message they authored.
     * The caller signs each recipient's copy with the sender's PAIRWISE KEX key for THAT recipient (like
     * the #187 control fan-out); the recipient verifies it against the sender's KEX pubkey. [signer] is
     * null (-> no "sig") when the sender holds no pairwise key for the recipient — the receiver then fails
     * OPEN. Default null makes an unsigned memo byte-identical to the legacy wire (back-compat).
     *
     * Memo budget (#194/#195): GROUP_MSG already embeds a full ~213-byte sender address; on a long message
     * the ~96-byte signature could push the memo past Zcash's 512-byte limit (MemoTooLong). Rather than
     * fail the send, the "sig" is included ONLY when it still fits MAX_MEMO_SIZE; otherwise the message
     * ships unsigned (== today's wire) and the receiver treats it as best-effort attribution.
     */
    fun createGroupMsgMessage(
        groupId: String,
        seq: Long,
        epoch: Int,
        senderAddress: String,
        plaintext: String,
        groupKey: ByteArray,
        signer: ((signedData: String) -> String?)? = null
    ): String {
        // Encrypt the message
        val encrypted = encryptMessage(plaintext, groupKey)

        val signature = signer?.invoke(
            groupMsgSignedData(groupId, senderAddress, epoch, seq, encrypted.ciphertext)
        )

        fun build(includeSig: Boolean): String {
            val payload = JSONObject().apply {
                put("seq", seq)
                put("epoch", epoch)
                put("sender", senderAddress)
                put("nonce", encrypted.nonce)
                put("ct", encrypted.ciphertext)
                put("ts", System.currentTimeMillis() / 1000)
                if (includeSig && !signature.isNullOrEmpty()) put("sig", signature)
            }
            return "${GROUP_PREFIX}GM:$groupId:${payload}"
        }

        val signed = build(includeSig = true)
        // Byte-measured (multibyte-safe): drop the signature rather than overflow the memo and fail send.
        return if (signed.toByteArray(Charsets.UTF_8).size <= ZMSGConstants.MAX_MEMO_SIZE) {
            signed
        } else {
            build(includeSig = false)
        }
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

    // ==========================================
    // #187 SIGNED CONTROL-MESSAGE AUTH
    // ==========================================
    // GROUP_KICK / GROUP_KEY mutate the roster / group key, so an UNSIGNED one is forgeable (any
    // on-chain party could evict a member or poison the key — the reason these were "intentionally NOT
    // acted on"). Each is delivered PER-MEMBER (GroupViewModel fan-out), so each copy is signed with the
    // admin's EXISTING per-peer KEX key (`getE2EPrivateKey(member)`), which the recipient verifies
    // against `getE2EPeerPublicKey(admin)` — the keypair the KEX established. The signature covers a
    // CANONICAL string of the security-critical fields, reconstructed identically on both sides below.
    // The receiver must ALSO authorize (the signer == the group's admin) before acting.

    /** Canonical bytes a GROUP_KICK signature covers. MUST match exactly on sign + verify. */
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

    /**
     * Canonical bytes a GROUP_MSG author signature covers. MUST match exactly on sign + verify.
     * Binds the group, the CLAIMED sender, the key epoch/sequence, and the ciphertext, so a valid
     * signature proves the holder of the sender's KEX key authored THIS exact message under THIS sender
     * address. The group key is SYMMETRIC, so without this any member could stamp ANOTHER member's
     * address on a message they authored; ciphertext-binding also stops a captured signature being lifted
     * onto different content at the same (epoch, seq). Sibling of groupKickSignedData/groupKeySignedData;
     * the "GM|" prefix domain-separates it from the control types. ciphertext is base64 and epoch/seq are
     * numeric (delimiter-safe); the verifier rejects a sender address containing '|' (a u1 never does).
     */
    fun groupMsgSignedData(
        groupId: String,
        sender: String,
        epoch: Int,
        seq: Long,
        ciphertext: String
    ): String = "GM|$groupId|$sender|$epoch|$seq|$ciphertext"

    /**
     * Outcome of [resolveGroupMsgSender]. The message is NEVER dropped, so [effectiveSender] is always
     * a usable attribution.
     */
    data class GroupMsgSenderResolution(
        /** Who the message is attributed to — the NOSTR seal-authenticated sender, or (on-chain) the
         *  self-asserted claimed sender. Never null; the message is rendered regardless. */
        val effectiveSender: String,
        /** True iff the attribution is cryptographically proven: a NOSTR seal, or a VALID on-chain
         *  author signature against the held key. Reserved for future hardening (e.g. gating roster
         *  mutations); the current caller renders regardless of this flag. */
        val authenticated: Boolean,
        /** True iff a signature was PRESENT (and checkable — sender key held) but did NOT verify: a
         *  forgery, OR a legit re-KEX'd sender's rotation-key signature we can't check until we adopt
         *  their new KEX. The caller logs this as a best-effort render — explicitly NOT a drop. */
        val signaturePresentButUnverified: Boolean,
    )

    /**
     * Resolve who authored a GROUP_MSG and whether that attribution is authenticated, WITHOUT ever
     * dropping the message. Pure (no I/O, no android deps): the caller supplies [heldKey] (the E2E
     * pubkey it holds for [claimedSender], or null) and a [verify] function, so this is unit-testable
     * in isolation from ChatViewModel.
     *
     * The group key is SYMMETRIC, so any member could stamp another member's address on a message.
     *  - [authenticatedSender] != null → NOSTR seal is ground truth: authenticated.
     *  - on-chain ([authenticatedSender] == null): a VALID pairwise author signature over
     *    [groupMsgSignedData] authenticates the claimed sender. An ABSENT signature, an UNKNOWN sender
     *    key ([heldKey] == null), or a PRESENT-but-INVALID signature is UNauthenticated but still
     *    rendered best-effort (FAIL OPEN) — a present-but-invalid sig is indistinguishable here from a
     *    legit sender who re-KEX'd and signed with a key we haven't adopted yet, so dropping would lose
     *    real messages. Only a PRESENT-but-unverified sig (with a held key) raises
     *    [GroupMsgSenderResolution.signaturePresentButUnverified] for the caller to log.
     * A [claimedSender] containing '|' (the [groupMsgSignedData] delimiter; a real u1 never has one) is
     * treated as unsigned, defeating delimiter injection.
     */
    fun resolveGroupMsgSender(
        authenticatedSender: String?,
        claimedSender: String,
        signature: String,
        groupId: String,
        epoch: Int,
        seq: Long,
        ciphertext: String,
        heldKey: String?,
        verify: (publicKey: String, data: String, signature: String) -> Boolean,
    ): GroupMsgSenderResolution {
        if (authenticatedSender != null) {
            return GroupMsgSenderResolution(
                effectiveSender = authenticatedSender,
                authenticated = true,
                signaturePresentButUnverified = false,
            )
        }
        val signaturePresent = signature.isNotEmpty() && !claimedSender.contains('|')
        // runCatching: a malformed forged signature/key must resolve to false, never throw (the caller's
        // outer catch would otherwise abort processing the whole message).
        val authentic = signaturePresent && heldKey != null &&
            runCatching {
                verify(heldKey, groupMsgSignedData(groupId, claimedSender, epoch, seq, ciphertext), signature)
            }.getOrDefault(false)
        return GroupMsgSenderResolution(
            effectiveSender = claimedSender,
            authenticated = authentic,
            signaturePresentButUnverified = signaturePresent && heldKey != null && !authentic,
        )
    }

    /**
     * Create a GROUP_KICK message. [signature] is the admin's signature over [groupKickSignedData]
     * for THIS recipient (the caller signs per-member). Unsigned kicks must not be acted on.
     */
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
                // Compact invites (#194) omit the roster; tolerate its absence.
                members = json.optJSONArray("members")?.let { parseJsonArray(it) } ?: emptyList(),
                keyEpoch = json.optInt("key_epoch", 0),
                // enc_key (ECIES) absent on compact invites, which carry k2 / group_key instead.
                encryptedGroupKey = json.optString("enc_key", "")
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
                accepterPublicKey = json.optString("accepter_pub", ""),
                signature = json.optString("sig", "")
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
                timestamp = json.getLong("ts"),
                // Absent on legacy/unsigned senders and on messages that shipped unsigned to fit the memo.
                signature = json.optString("sig", "")
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
                encryptedGroupKey = if (json.has("enc_key")) json.getString("enc_key") else null,
                signature = if (json.has("sig")) json.getString("sig") else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_KICK payload", e)
            null
        }
    }

    /** Parse GROUP_KEY (key-rotation) payload, including the #187 signature. */
    fun parseGroupKeyPayload(groupId: String, payload: String): GroupKeyPayload? {
        return try {
            val json = JSONObject(payload)
            GroupKeyPayload(
                groupId = groupId,
                signer = json.getString("signer"),
                epoch = json.getInt("epoch"),
                encryptedGroupKey = json.getString("enc_key"),
                reason = if (json.has("reason")) json.getString("reason") else "rotation",
                signature = if (json.has("sig")) json.getString("sig") else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_KEY payload", e)
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
                member.inviteStatus?.let { put("invite_status", it.name) }
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
                    nickname = if (obj.has("nickname")) obj.getString("nickname") else null,
                    // P1.4: tolerate legacy rosters (no field) and unknown values (forward compat)
                    inviteStatus = obj.optString("invite_status")
                        .takeIf { it.isNotEmpty() }
                        ?.let { runCatching { InviteStatus.valueOf(it) }.getOrNull() }
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
