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
 * - GR: GROUP_KEY_REQUEST - Member pulls the current key
 * - GP: GROUP_MEMBER - Admin announces one member in or out (Round 8 MA1; not acted on before MA2)
 * - GQ: GROUP_ROSTER_QUERY - Member asks the admin to re-send the announces (Round 8 MA1; relay only)
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

    /**
     * B7 (GROUP-MSG-TIME): every value at or above this is already epoch MILLISECONDS; below it, epoch
     * SECONDS. 1e11 ms is 1973-03-03 and 1e11 s is the year 5138, so no real time is ambiguous.
     */
    private const val FIRST_MILLIS_VALUE = 100_000_000_000L

    /**
     * B7 (GROUP-MSG-TIME) — the instant a group time value names. Every sender (2.12.4 and 2.13.0) writes
     * the wire `ts` / `created_at` in SECONDS (`System.currentTimeMillis() / 1000`), and the wire stays
     * that way so a 2.12.4 phone keeps reading what 2.13.0 sends. The receiver used to read it as
     * milliseconds (a 1970 date) and stored that wrong instant's `toEpochMilli()`, which is the raw
     * seconds number again, so rows already on phones hold seconds too. A value that is clearly
     * milliseconds (>= [FIRST_MILLIS_VALUE]: every live-chain, NOSTR and own pending row) stays
     * milliseconds. A negative value (never written by any sender) reads as the epoch, never a throw.
     */
    fun groupTimeToInstant(value: Long): java.time.Instant =
        when {
            value >= FIRST_MILLIS_VALUE -> java.time.Instant.ofEpochMilli(value)
            value > 0 -> java.time.Instant.ofEpochSecond(value)
            else -> java.time.Instant.EPOCH
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
     *
     * ZC-506: [keyEpoch] is the key epoch of the invite this accept answers (`key_epoch`: additive, optional, NOT part
     * of the signed data, ignored by v2.12.4). The creator refuses, as a comeback, an accept that answers an invite from
     * before the member's removal ([groupAcceptKeyEpoch]). With null the memo is byte for byte the legacy one (tests and
     * replies stored by earlier builds only: the app always passes an epoch). With an epoch the memo is written here,
     * not by the platform's JSON writer: the keys in a fixed order, the value always present, and '/' unescaped, so its
     * bytes depend only on how long the parts are (Android's writer turns every '/' of the base64 key and signature
     * into two bytes). There is no shorter form for a memo that is too long: such an accept travels over the relays.
     */
    fun createGroupAcceptMessage(
        groupId: String,
        accepterAddress: String,
        accepterPublicKey: String,
        signature: String = "",
        keyEpoch: Int? = null,
    ): String {
        if (keyEpoch != null) {
            val sig = if (signature.isNotEmpty()) ",\"sig\":${jsonText(signature)}" else ""
            return "${GROUP_PREFIX}GA:$groupId:{\"accepter\":${jsonText(accepterAddress)}," +
                "\"accepter_pub\":${jsonText(accepterPublicKey)}$sig,\"key_epoch\":$keyEpoch}"
        }
        val payload = JSONObject().apply {
            put("accepter", accepterAddress)
            put("accepter_pub", accepterPublicKey)
            if (signature.isNotEmpty()) put("sig", signature)
        }
        return "${GROUP_PREFIX}GA:$groupId:${payload}"
    }

    /** ZC-506 — [text] as a JSON string: quotes, backslashes and control characters escaped, '/' written as it is. */
    private fun jsonText(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u").append(c.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private const val HEX_RADIX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4

    /**
     * ZC-506 — the key epoch a GROUP_ACCEPT says it answers ([createGroupAcceptMessage]), or null when it says none: only
     * a whole number from 0 to Int.MAX_VALUE counts (a negative, fractional, textual or larger value is "absent", and
     * the creator then judges the accept by its time alone). The value is not signed.
     */
    fun groupAcceptKeyEpoch(json: JSONObject): Int? {
        val value =
            when (val raw = json.opt("key_epoch")) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> return null
            }
        return if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else null
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
        val ts = System.currentTimeMillis() / 1000

        val signed = groupMsgMemo(groupId, seq, epoch, senderAddress, encrypted.nonce, encrypted.ciphertext, ts, signature)
        // Byte-measured (multibyte-safe): drop the signature rather than overflow the memo and fail send.
        return if (signed.toByteArray(Charsets.UTF_8).size <= ZMSGConstants.MAX_MEMO_SIZE) {
            signed
        } else {
            groupMsgMemo(groupId, seq, epoch, senderAddress, encrypted.nonce, encrypted.ciphertext, ts, null)
        }
    }

    /**
     * The GROUP_MSG wire string. R6-3 (round 5 report §5 item 4): the JSON is written with '/' unescaped.
     * Android's org.json writes every '/' as "\/", and the nonce and ciphertext are base64, so the same
     * text used to make a memo whose size depended on how many '/' its random ciphertext happened to hold —
     * a message near the limit could fit for one member and overflow for the next, and no length check
     * made before the send could be exact. '/' needs no escape in JSON, every JSON reader (org.json
     * included, so every ZChat version) reads both forms identically, and the signature covers the
     * ciphertext string, not the JSON. With it unescaped the size depends only on the lengths of the
     * parts, which is what [maxGroupMsgTextBytes] measures.
     */
    @Suppress("LongParameterList")
    private fun groupMsgMemo(
        groupId: String,
        seq: Long,
        epoch: Int,
        senderAddress: String,
        nonce: String,
        ciphertext: String,
        ts: Long,
        signature: String?,
    ): String {
        val payload = JSONObject().apply {
            put("seq", seq)
            put("epoch", epoch)
            put("sender", senderAddress)
            put("nonce", nonce)
            put("ct", ciphertext)
            put("ts", ts)
            if (!signature.isNullOrEmpty()) put("sig", signature)
        }
        return "${GROUP_PREFIX}GM:$groupId:${payload.toString().replace("\\/", "/")}"
    }

    /**
     * R6-3 (round 5 report §5 item 4) — the most UTF-8 bytes of text one GROUP_MSG memo for this group,
     * sender, sequence number and key epoch can carry within [ZMSGConstants.MAX_MEMO_SIZE]. Measured on
     * the real wire layout ([groupMsgMemo]) with the unsigned memo — the floor [createGroupMsgMessage]
     * falls back to — and the exact lengths AES-GCM and base64 give that many bytes, so a text of at most
     * this many bytes always fits and one byte more never does. A group memo is one memo per member, never
     * chunked, so this is the whole budget for the message.
     */
    fun maxGroupMsgTextBytes(
        groupId: String,
        seq: Long,
        epoch: Int,
        senderAddress: String,
    ): Int {
        val ts = System.currentTimeMillis() / 1000
        val nonce = "A".repeat(base64Length(GCM_NONCE_LENGTH))

        fun memoBytes(textBytes: Int): Int =
            groupMsgMemo(
                groupId,
                seq,
                epoch,
                senderAddress,
                nonce,
                "A".repeat(base64Length(textBytes + GCM_TAG_LENGTH / Byte.SIZE_BITS)),
                ts,
                null
            ).toByteArray(Charsets.UTF_8).size

        var fits = -1
        while (fits < ZMSGConstants.MAX_MEMO_SIZE && memoBytes(fits + 1) <= ZMSGConstants.MAX_MEMO_SIZE) fits++
        return fits.coerceAtLeast(0)
    }

    /**
     * R6-3 — how many bytes [plaintext] is over what one GROUP_MSG memo can carry (0 when it fits). Cutting
     * that many characters of any kind is always enough, because every character is at least one byte.
     */
    fun groupMsgOverBy(
        groupId: String,
        seq: Long,
        epoch: Int,
        senderAddress: String,
        plaintext: String,
    ): Int {
        val max = maxGroupMsgTextBytes(groupId, seq, epoch, senderAddress)
        return (plaintext.toByteArray(Charsets.UTF_8).size - max).coerceAtLeast(0)
    }

    private fun base64Length(bytes: Int): Int = (bytes + 2) / 3 * 4

    /**
     * Round 8 MA1 (design §2.2) — canonical bytes a GROUP_LEAVE signature covers: `GL|1|<gid>|<leaver>|<ts>`, with
     * [ts] in unix SECONDS exactly as the memo writes it. Signed with the leaver's pairwise key for each recipient
     * (as GA #219). MUST match exactly on sign + verify.
     */
    fun groupLeaveSignedData(
        groupId: String,
        leaver: String,
        ts: Long
    ): String = "GL|1|$groupId|$leaver|$ts"

    /**
     * Create a GROUP_LEAVE message. [ts] is unix SECONDS (the wire has always written seconds; v2.12.4 reads only
     * `leaver`). Round 8 MA1: [signature], when given, is the leaver's signature over [groupLeaveSignedData] for this
     * [ts]; it is added only when the memo still fits [ZMSGConstants.MAX_MEMO_SIZE], and then written with '/'
     * unescaped (as GM). Without a signature the memo is the legacy one, byte for byte.
     */
    fun createGroupLeaveMessage(
        groupId: String,
        leaverAddress: String,
        signature: String? = null,
        ts: Long = System.currentTimeMillis() / 1000,
    ): String {
        val payload = JSONObject().apply {
            put("leaver", leaverAddress)
            put("ts", ts)
        }
        val unsigned = "${GROUP_PREFIX}GL:$groupId:$payload"
        if (signature.isNullOrEmpty()) return unsigned
        val signed = "${GROUP_PREFIX}GL:$groupId:${payload.put("sig", signature).toString().replace("\\/", "/")}"
        return if (signed.toByteArray(Charsets.UTF_8).size <= ZMSGConstants.MAX_MEMO_SIZE) signed else unsigned
    }

    // ==========================================
    // ROUND 8 MA1: GROUP MEMBER ANNOUNCE (GP) / ROSTER QUERY (GQ)
    // ==========================================
    // MEMBER_ANNOUNCE_DESIGN.md v2 §2. One subject per memo, the same format on both channels:
    //   ZMSG:3.0:GROUP:GP:<gid>:{"v":1,"o":"A"|"R","m":<UA>,"n":<Long>,"e":<Int>,"sig":<b64>[,"np":<hex64>]}
    // The only valid signer is the group's admin, checked by the receiver against the creator's stored key (MA2), so
    // the payload carries no signer field. Nothing in the app builds or reads a GP before MA2 / MA3.

    private const val GP_VERSION = 1
    private val ANNOUNCE_SUBJECT = Regex("^[A-Za-z0-9]{20,512}$")
    private val ANNOUNCE_NOSTR_PUBKEY = Regex("^[0-9a-f]{64}$")
    private val ANNOUNCE_KEYS = setOf("v", "o", "m", "n", "e", "sig")

    /**
     * Canonical bytes a GROUP_MEMBER signature covers: `GP|1|<gid>|<o>|<m>|<n>|<e>|<np or empty>`. Signed per
     * recipient with the admin's pairwise key for that recipient (#187, as GK / GY). MUST match exactly.
     */
    fun groupMemberAnnounceSignedData(
        groupId: String,
        op: AnnounceOp,
        subject: String,
        n: Long,
        epoch: Int,
        nostrPubkey: String?
    ): String = "GP|$GP_VERSION|$groupId|${op.name}|$subject|$n|$epoch|${nostrPubkey.orEmpty()}"

    /**
     * Build a GROUP_MEMBER memo, or null when it would pass [ZMSGConstants.MAX_MEMO_SIZE] (that pair is TOO_BIG, never
     * truncated) or when [parseGroupMemberAnnouncePayload] would reject it. '/' is written unescaped (as GM), so the
     * size depends only on the lengths of the parts.
     */
    @Suppress("LongParameterList")
    fun createGroupMemberAnnounceMessage(
        groupId: String,
        op: AnnounceOp,
        subject: String,
        n: Long,
        epoch: Int,
        signature: String,
        nostrPubkey: String? = null
    ): String? {
        if (!GroupInfo.isGroupIdShaped(groupId) || !ANNOUNCE_SUBJECT.matches(subject)) return null
        if (n < 1 || epoch < 0 || signature.isEmpty()) return null
        if (nostrPubkey != null && !ANNOUNCE_NOSTR_PUBKEY.matches(nostrPubkey)) return null
        val payload =
            JSONObject().apply {
                put("v", GP_VERSION)
                put("o", op.name)
                put("m", subject)
                put("n", n)
                put("e", epoch)
                put("sig", signature)
                if (nostrPubkey != null) put("np", nostrPubkey)
            }
        val memo = "${GROUP_PREFIX}GP:$groupId:${payload.toString().replace("\\/", "/")}"
        return memo.takeIf { it.toByteArray(Charsets.UTF_8).size <= ZMSGConstants.MAX_MEMO_SIZE }
    }

    /** A JSON integer (never a string, a double or a big number), as a Long; null otherwise. */
    private fun jsonInteger(value: Any?): Long? =
        when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }

    /**
     * Strict GROUP_MEMBER parse (design §2.1); any failure → null, the whole memo is dropped. Checks: [groupId] shaped
     * like [GroupInfo.generateGroupId] output; exactly the keys v, o, m, n, e, sig (+ np); `v` the JSON integer 1;
     * `o` "A" or "R"; `m` 20-512 letters and digits (so never '|', ':', ',' or "E2E_INIT:"); `n` a JSON integer >= 1;
     * `e` a JSON integer >= 0; `sig` a non-empty string; `np` 64 lowercase hex. Nothing here authenticates.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
    fun parseGroupMemberAnnouncePayload(
        groupId: String,
        payload: String
    ): GroupMemberAnnounce? {
        if (!GroupInfo.isGroupIdShaped(groupId)) return null
        val json =
            try {
                JSONObject(payload)
            } catch (e: Exception) {
                return null
            }
        val keys = json.keys().asSequence().toSet()
        if (keys != ANNOUNCE_KEYS && keys != ANNOUNCE_KEYS + "np") return null
        if (jsonInteger(json.opt("v")) != GP_VERSION.toLong()) return null
        val op =
            when (json.opt("o")) {
                "A" -> AnnounceOp.A
                "R" -> AnnounceOp.R
                else -> return null
            }
        val subject = (json.opt("m") as? String)?.takeIf { ANNOUNCE_SUBJECT.matches(it) } ?: return null
        val n = jsonInteger(json.opt("n"))?.takeIf { it >= 1 } ?: return null
        val epoch = jsonInteger(json.opt("e"))?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
        val signature = (json.opt("sig") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val nostrPubkey =
            if (json.has("np")) {
                (json.opt("np") as? String)?.takeIf { ANNOUNCE_NOSTR_PUBKEY.matches(it) } ?: return null
            } else {
                null
            }
        return GroupMemberAnnounce(groupId, op, subject, n, epoch.toInt(), signature, nostrPubkey)
    }

    /** Build a GROUP_ROSTER_QUERY memo, `{"v":1}` (design §2.3; relay only, never sent on-chain). */
    fun createGroupRosterQueryMessage(groupId: String): String = "${GROUP_PREFIX}GQ:$groupId:{\"v\":$GP_VERSION}"

    /** Strict GROUP_ROSTER_QUERY parse: a shaped [groupId] and exactly `{"v":1}` with an integer 1; else null. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    fun parseGroupRosterQueryPayload(
        groupId: String,
        payload: String
    ): GroupRosterQueryPayload? {
        if (!GroupInfo.isGroupIdShaped(groupId)) return null
        val json =
            try {
                JSONObject(payload)
            } catch (e: Exception) {
                return null
            }
        if (json.keys().asSequence().toSet() != setOf("v")) return null
        if (jsonInteger(json.opt("v")) != GP_VERSION.toLong()) return null
        return GroupRosterQueryPayload(groupId, GP_VERSION)
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
     * #5 authorization (pure, unit-tested): is [sender] a REMOVED (LEFT) member of [roster]? A kicked
     * member's pre-rotation epoch key stays valid within the epoch-lookback window, so a GROUP_MSG from
     * them must be dropped even though it decrypts. Matching is canonicalization-tolerant ([canonicalize],
     * e.g. resolvePeerAddress) so a DRIFTED UA representation of the same peer (#205/#214) can't slip a
     * removed member's message past this gate under a different-looking address.
     */
    fun isRemovedMember(
        sender: String,
        roster: List<GroupMember>,
        canonicalize: (String) -> String,
    ): Boolean {
        val canonSender = canonicalize(sender)
        return roster.any { canonicalize(it.address) == canonSender && it.status == MemberStatus.LEFT }
    }

    // ==========================================
    // D3 — ROTATION DELIVERY / KEY-PULL AUTHORIZATION (pure, unit-tested)
    // ==========================================
    // rotateAndNotify commits the new epoch even when some remaining members never received the
    // rotated key (no KEX session / send failure / offline). Those members keep sending old-epoch
    // ciphertext that a kicked member can still read. These pure helpers classify the fan-out
    // outcome (who is still OWED the key — persisted as GroupMember.pendingKeyEpoch), select the
    // retry set, and authorize the pull side (GROUP_KEY_REQUEST).

    /**
     * Outcome of a rotation fan-out: an exact partition of the recipients into [delivered] (their
     * pending marker cleared) and [pending] (marked as owed the new epoch). [removalComplete] is the
     * D3 "may we claim the removal/rotation is fully propagated?" verdict — false means some remaining
     * member still holds only the OLD key and will keep fanning out ciphertext a kicked member can read.
     */
    data class RotationDeliveryOutcome(
        val delivered: List<GroupMember>,
        val pending: List<GroupMember>,
    ) {
        val removalComplete: Boolean get() = pending.isEmpty()
    }

    /**
     * Classify a rotation fan-out to [recipients] (already filtered by the caller: key-HOLDERS per
     * [holdsGroupKey] — ACTIVE or a delivered SENT invitee — not self, not the kicked member; a
     * kicked member must never reappear here). [sendSucceeded] is the per-recipient send result;
     * failures/skips are marked owed [newEpoch] via [GroupMember.pendingKeyEpoch], successes have any
     * stale marker cleared.
     */
    fun rotationDeliveryOutcome(
        recipients: List<GroupMember>,
        newEpoch: Int,
        sendSucceeded: (GroupMember) -> Boolean,
    ): RotationDeliveryOutcome {
        val (ok, missed) = recipients.partition(sendSucceeded)
        return RotationDeliveryOutcome(
            delivered = ok.map { it.copy(pendingKeyEpoch = null) },
            pending = missed.map { it.copy(pendingKeyEpoch = newEpoch) },
        )
    }

    /**
     * Does [member] currently HOLD a live group key that a kick/rotation must replace? True for
     * ACTIVE members and for INVITED members whose invite was actually delivered
     * ([InviteStatus.SENT]) — the compact invite carries the CURRENT epoch key, so a delivered
     * invitee already holds it and MUST be re-keyed on a rotation (review F3) or it keeps decrypting
     * post-rotation traffic and its roster keeps listing a kicked member. An INVITED member whose
     * invite is still pending/failed never received any key and must NOT be re-keyed; LEFT/KICKED
     * rows never hold a live key. Single source of truth for the rotation fan-out recipient set
     * ([GroupViewModel.rotateAndNotify]) and the D3 retry-owed set ([membersOwedKey]).
     */
    fun holdsGroupKey(member: GroupMember): Boolean =
        member.status == MemberStatus.ACTIVE ||
            (member.status == MemberStatus.INVITED && member.inviteStatus == InviteStatus.SENT)

    /**
     * D3 — who is still owed the [newEpoch] key after a rotation: every roster row that HOLDS a group
     * key ([holdsGroupKey]: ACTIVE, or an INVITED member with a delivered SENT invite) whose
     * canonical address is NOT among [deliveredTo] (canonical-matched via [canonicalize], e.g.
     * resolvePeerAddress, so a #205/#214 drifted representation of a delivered peer isn't re-owed).
     * LEFT rows are NEVER owed — a kicked member must not be handed the post-kick key — and an INVITED
     * member whose invite is still pending/failed never received a key, so it is not owed a rotation
     * key either (it gets the CURRENT key when its invite is finally sent). Returned rows carry
     * [GroupMember.pendingKeyEpoch] = [newEpoch] so the caller can persist / retry them directly.
     * The retry pass derives [deliveredTo] from the persisted roster (rows with no pending marker).
     */
    fun membersOwedKey(
        roster: List<GroupMember>,
        deliveredTo: Collection<String>,
        newEpoch: Int,
        canonicalize: (String) -> String,
    ): List<GroupMember> {
        val deliveredCanon = deliveredTo.map(canonicalize).toSet()
        return roster
            .filter { holdsGroupKey(it) && canonicalize(it.address) !in deliveredCanon }
            .map { it.copy(pendingKeyEpoch = newEpoch) }
    }

    /**
     * D3 pull-side AUTHORIZATION for a GROUP_KEY_REQUEST: may [requesterAddress] be re-sent the
     * current group key? Grant ONLY when every roster row canonicalizing to the requester is
     * non-LEFT and at least one is ACTIVE — a kicked (LEFT) member must never pull the post-kick
     * key, INCLUDING via a stale ACTIVE duplicate row under a drifted representation (#205/#214),
     * so a LEFT tombstone vetoes. INVITED members are refused too (their key rides the invite).
     * [requestedEpoch] (the epoch the requester claims to already hold) is bounded to
     * [0, [currentEpoch]] — a negative or ahead-of-current claim is malformed/hostile → refuse.
     * AUTHENTICATION (the request signature / NOSTR seal) is the caller's job, mirroring the
     * verify-then-authorize split of the #187 control handlers.
     */
    /**
     * #187 control-message AUTHORIZATION: is the (already-authenticated) [signerAddress] the group's
     * admin/creator? Compare on the CANONICAL representation — a rep-drifted admin (#205/#214) whose
     * live signer address differs from the stored [creatorAddress] must still authorize their own
     * signed kick/rotation, exactly like every other group address comparison and the sibling
     * GROUP_KEY_REQUEST path. [canonicalize] is identity when no alias exists, so the common no-drift
     * case is unchanged. AUTHENTICATION (the signature over the signer's KEX'd E2E key) is the
     * caller's job, mirroring the verify-then-authorize split of the #187 handlers.
     */
    fun isGroupControlAdmin(
        creatorAddress: String,
        signerAddress: String,
        canonicalize: (String) -> String,
    ): Boolean = canonicalize(creatorAddress) == canonicalize(signerAddress)

    /**
     * #187 [7] control-message AUTHORIZATION, drift-robust. The caller has ALREADY AUTHENTICATED the
     * control message — [signerPub] is the KEX'd E2E key the #187 signature verified against — so
     * comparing [signerPub] to the creator's captured key [creatorPublicKey] is equivalent to
     * re-verifying that signature against the creator's key, and is IMMUNE to the admin's address
     * representation drifting (X→Y) because it never touches the address string.
     *
     * When [creatorPublicKey] is null (legacy group joined before capture, or one already drifted at
     * upgrade so no key could be snapshot) fall back to the EXACT existing canonical string gate
     * [isGroupControlAdmin] — identical behavior to today for any un-healed group.
     *
     * Pure (no I/O, no android deps): extracted from verifyGroupAdminControl so the AUTHORIZE half is
     * unit-testable with real key strings without instantiating ChatViewModel.
     */
    fun isGroupControlAuthorized(
        creatorPublicKey: String?,
        signerPub: String,
        creatorAddress: String,
        signerAddress: String,
        canonicalize: (String) -> String,
    ): Boolean =
        if (creatorPublicKey != null) {
            signerPub == creatorPublicKey
        } else {
            isGroupControlAdmin(creatorAddress, signerAddress, canonicalize)
        }

    fun mayGrantKeyRequest(
        requesterAddress: String,
        roster: List<GroupMember>,
        requestedEpoch: Int,
        currentEpoch: Int,
        canonicalize: (String) -> String,
    ): Boolean {
        if (requestedEpoch < 0 || requestedEpoch > currentEpoch) return false
        val canonRequester = canonicalize(requesterAddress)
        val matching = roster.filter { canonicalize(it.address) == canonRequester }
        if (matching.any { it.status == MemberStatus.LEFT }) return false
        return matching.any { it.status == MemberStatus.ACTIVE }
    }

    /**
     * D4 — the epoch-floor authorship gate: should this decrypted GROUP_MSG be DROPPED as a
     * suspected removed-member injection?
     *
     * On-chain attribution deliberately FAILS OPEN (gating live attribution on signatures breaks
     * mesh delivery — see [resolveGroupMsgSender]), so `isRemovedMember(effectiveSender)` alone is
     * bypassable: a removed member holding a retained pre-kick key can stamp an ACTIVE member's
     * address on an old-epoch message. Two facts thread the needle: a removed member can only
     * produce ciphertext that decrypts under a PRE-REMOVAL epoch (they never receive the post-kick
     * key), and they cannot forge the impersonated member's pairwise author signature. So verified
     * authorship is required ONLY when [decryptedEpoch] (the epoch of the key that actually
     * decrypted the message — not the self-asserted payload epoch) is below [removalFloor] (the
     * epoch the most recent kick rotated INTO; 0 = no kick ever → rule inert). Current-epoch
     * messages stay fully fail-open (mesh + re-KEX tolerance preserved), and a signed old-epoch
     * message from a genuine lagging sender (D3) or a late-rendered pre-kick message (#215) passes.
     * ACCEPTED false-drop: a legit pre-removal-epoch message that shipped UNSIGNED (memo-overflow
     * sig drop / un-adopted re-KEX key) is dropped — callers log it at WARN with the reason.
     *
     * Drop iff: the effective sender is a removed (LEFT) member — the pre-existing #5 clause — OR
     * the message decrypts only under a pre-removal epoch without proven authorship.
     *
     * Round 9 R9-3 (REVIEW_R8 F2 item 3): a pre-removal-epoch message [minedBeforeRemoval] (its transaction was mined
     * below the height of every removal that retired its key, see [minedBeforeRemoval]) is not a removed member's
     * injection: when it was mined the member was not removed yet, so it passes the epoch clause as it would have had
     * it been seen before the removal. Default false: today's behaviour.
     */
    fun isSuspectedRemovedInjection(
        effectiveSender: String,
        decryptedEpoch: Int,
        removalFloor: Int,
        authorVerified: Boolean,
        isRemovedMember: (String) -> Boolean,
        minedBeforeRemoval: Boolean = false,
    ): Boolean =
        isRemovedMember(effectiveSender) || (decryptedEpoch < removalFloor && !authorVerified && !minedBeforeRemoval)

    /**
     * GR-OPEN G1 (fold-specs/groups SPEC §1.3, review A and B) — should this decrypted GROUP_MSG be HELD (stored, not
     * shown) as a message from someone who is not a member, sent under a key from before a removal?
     *
     * [isSuspectedRemovedInjection] asks only for proven authorship, and over the relays the author is always proven:
     * the seal names the address the relay key is bound to. That proves who sent the message, not that the sender is a
     * member, so a removed member posting under the old key from another address of its wallet passed. On a phone
     * whose roster is the whole group ([rosterAuthoritative]: the creator's, or a member's whose roster the creator
     * announced), a message that decrypts only under a key below [removalFloor] is held when its sender is no
     * key-holding row ([senderIsKeyHolderRow]), is not us ([isSelf]) and was not mined before the removal
     * ([minedBeforeRemoval], computed whether or not the author is verified, review B). The caller releases a held
     * message when the same authenticated sender later posts at an epoch at or above the floor. Floor 0 never holds.
     */
    @Suppress("LongParameterList")
    fun isPreRemovalNonMember(
        decryptedEpoch: Int,
        removalFloor: Int,
        rosterAuthoritative: Boolean,
        senderIsKeyHolderRow: Boolean,
        isSelf: Boolean,
        minedBeforeRemoval: Boolean,
    ): Boolean =
        rosterAuthoritative &&
            removalFloor > 0 &&
            decryptedEpoch < removalFloor &&
            !senderIsKeyHolderRow &&
            !isSelf &&
            !minedBeforeRemoval

    /**
     * GR-OPEN G1 (fold-specs/groups SPEC §1.3, review C) — is a relay message sealed by the relay key of a removed (LEFT)
     * row, whatever address that key maps to? [leftRowRelayKeys] are the relay keys of the group's LEFT rows,
     * [keyHolderRelayKeys] those of its non-LEFT key-holding rows: a key that also belongs to a current key holder (a
     * contact code can bind one relay key to two addresses) never holds. Relay keys are hex, compared ignoring case.
     */
    fun isRemovedByRelayKey(
        senderRelayKey: String?,
        leftRowRelayKeys: Set<String>,
        keyHolderRelayKeys: Set<String>,
    ): Boolean {
        val key = senderRelayKey?.lowercase() ?: return false
        return key in leftRowRelayKeys.map { it.lowercase() } && key !in keyHolderRelayKeys.map { it.lowercase() }
    }

    /** GR-OPEN G1 — the most held (not shown) group messages one group keeps; the oldest held row goes first. */
    const val MAX_HELD_GROUP_MESSAGES = 50

    /**
     * GR-OPEN G2 (fold-specs/groups SPEC §2.2, review F and I) — may this phone act on a "sealed admin control": an
     * unsigned GROUP_KICK or GROUP_KEY that carries the new group key in plaintext (`gk`)? True only when all hold:
     * - it came over the relays ([authenticatedSender] != null; never on-chain);
     * - it carries no signature (a signed control is judged by the signed gate);
     * - this phone joined the group with the creator's plaintext relay invite ([plainKeyJoin], AnnounceState): a group
     *   joined through a key exchange, or one this phone had before this rule existed, never takes one;
     * - the seal is the creator's, and the admin the control names ([claimedAdmin]: the kick's kicker, the key's
     *   signer) is the creator too, both compared with the stored [creatorAddress] as stored. ZC-967: never through an
     *   address alias: the GROUP_ACCEPT handler writes an alias to whatever address a member declares, so an alias
     *   proves nothing about who created a group.
     * The trust is the plaintext invite's: the relay binding of the creator's key, first-come and never rebound.
     */
    fun sealedAdminControlAuthorized(
        authenticatedSender: String?,
        signature: String?,
        claimedAdmin: String,
        creatorAddress: String,
        plainKeyJoin: Boolean,
    ): Boolean {
        if (authenticatedSender == null || !signature.isNullOrEmpty() || !plainKeyJoin) return false
        return authenticatedSender == creatorAddress && claimedAdmin == creatorAddress
    }

    /**
     * Round 9 R9-3 — was a message under key [decryptedEpoch], mined at [minedHeight], mined before every removal that
     * retired that key? [kickHeights] is the group's removal record (the epoch each removal rotated into, to the height
     * it counts from: the kick's mined height, or the height this phone had scanned to when it acted on a kick that was
     * not mined yet or came over the relays, or the creator's scanned height at its own removal; null = unknown), and
     * [removalFloor] the group's floor.
     *
     * True only when [minedHeight] is known and below the lowest height of the removals that rotated past
     * [decryptedEpoch] (each of them removed a holder of that key), and every one of those heights is known. A floor
     * above the record's highest removal (raised by an older build, which stored the epoch only) makes every key below
     * it unknown, which is today's behaviour. An unmined message, or one mined in a removal's own block, is never
     * "before".
     */
    fun minedBeforeRemoval(
        minedHeight: Long?,
        decryptedEpoch: Int,
        removalFloor: Int,
        kickHeights: Map<Int, Long?>,
    ): Boolean {
        if (minedHeight == null || decryptedEpoch >= removalFloor) return false
        val top = kickHeights.keys.maxOrNull() ?: return false
        if (top < removalFloor) return false
        val counted = kickHeights.filterKeys { it > decryptedEpoch }
        if (counted.isEmpty() || counted.values.any { it == null }) return false
        val bound = counted.values.filterNotNull().minOrNull() ?: return false
        return minedHeight < bound
    }

    /**
     * Round 9 R9-3 — [held] (a group's removal record, see [minedBeforeRemoval]) after a removal into [epoch] counted
     * from [kickHeight] (null = unknown). [floorBefore] is the floor before this removal: when it is above every
     * removal the record holds, it was raised without a record (a removal an older build took), so that epoch is added
     * with an unknown height first. The same epoch twice keeps the lower height, and an unknown height stays unknown.
     */
    fun recordRemovalKick(
        held: Map<Int, Long?>,
        floorBefore: Int,
        epoch: Int,
        kickHeight: Long?,
    ): Map<Int, Long?> {
        val top = held.keys.maxOrNull() ?: 0
        val withOlder = if (floorBefore > top) held + (floorBefore to null) else held
        val kept =
            when {
                !withOlder.containsKey(epoch) -> kickHeight
                kickHeight == null -> null
                else -> withOlder[epoch]?.let { minOf(it, kickHeight) }
            }
        return withOlder + (epoch to kept)
    }

    /** Round 9 R9-3 — a removal record as stored: `{"<epoch>": <height or null>, ...}`. */
    fun serializeRemovalKicks(kicks: Map<Int, Long?>): String =
        JSONObject()
            .apply { kicks.forEach { (epoch, height) -> put(epoch.toString(), height ?: JSONObject.NULL) } }
            .toString()

    /**
     * Round 9 R9-3 — a stored removal record, or null when [json] is absent or unreadable (the caller then treats the
     * floor as having no known removal height, today's behaviour). A value that is not a number reads as unknown.
     */
    fun deserializeRemovalKicks(json: String?): Map<Int, Long?>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associate { key ->
                val value = o.opt(key)
                key.toInt() to (if (value is Number) value.toLong() else null)
            }
        }.getOrNull()
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
        signature: String,
        // GR-OPEN G2: the sealed form's new group key in plaintext (`gk`), sent only over the relays with "sig": "".
        plainKey: String? = null,
    ): String {
        val payload = JSONObject().apply {
            put("kicked", kickedAddress)
            put("kicker", kickerAddress)
            put("new_epoch", newEpoch)
            encryptedNewKey?.let { put("enc_key", it) }
            plainKey?.let { put("gk", it) }
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
        reason: String = "rotation",
        // GR-OPEN G2: the sealed form's group key in plaintext (`gk`, with no enc_key and "sig": ""), relays only.
        plainKey: String? = null,
    ): String {
        val payload = JSONObject().apply {
            put("signer", signerAddress)
            put("epoch", newEpoch)
            if (plainKey == null || encryptedGroupKey.isNotEmpty()) put("enc_key", encryptedGroupKey)
            plainKey?.let { put("gk", it) }
            put("reason", reason)
            put("sig", signature)
        }
        return "${GROUP_PREFIX}GY:$groupId:${payload}"
    }

    /**
     * Canonical bytes a GROUP_KEY_REQUEST signature covers. MUST match exactly on sign + verify.
     * Binds the group, the requester's address, and the epoch they claim to hold. Sibling of
     * [groupKickSignedData]/[groupKeySignedData]; the "GR|" prefix domain-separates it. The epoch is
     * numeric (delimiter-safe); the admin-side verifier rejects a requester address containing '|'
     * (a real u1 never has one), mirroring the #187 canonicalization guard.
     */
    fun groupKeyRequestSignedData(
        groupId: String,
        requesterAddress: String,
        haveEpoch: Int
    ): String = "GR|$groupId|$requesterAddress|$haveEpoch"

    /**
     * Create a GROUP_KEY_REQUEST message (D3 pull side). [signature] is the requester's signature
     * over [groupKeyRequestSignedData] with their pairwise KEX key for the admin; empty (omitted from
     * the wire, keeping the memo compact) when no session exists — the NOSTR seal can still
     * authenticate such a request, but an on-chain unsigned one must be ignored by the admin.
     */
    fun createGroupKeyRequestMessage(
        groupId: String,
        requesterAddress: String,
        haveEpoch: Int,
        signature: String
    ): String {
        val payload = JSONObject().apply {
            put("requester", requesterAddress)
            put("have_epoch", haveEpoch)
            if (signature.isNotEmpty()) put("sig", signature)
        }
        return "${GROUP_PREFIX}GR:$groupId:${payload}"
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
                signature = json.optString("sig", ""),
                keyEpoch = groupAcceptKeyEpoch(json),
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
                // Round 8 MA1: optional (0 when absent); unix seconds.
                timestamp = json.optLong("ts", 0L),
                // Round 8 MA1: the optional pairwise signature (absent on v2.12.4 and unsigned leaves).
                signature = json.optString("sig", "").ifEmpty { null },
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
                // GR-OPEN G2: the sealed form's empty "sig" reads as no signature.
                signature = if (json.has("sig")) json.getString("sig").takeIf { it.isNotEmpty() } else null,
                plainKey = if (json.has("gk")) json.getString("gk").takeIf { it.isNotEmpty() } else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_KICK payload", e)
            null
        }
    }

    /** Parse GROUP_KEY_REQUEST payload (D3). Malformed → null (never throws into the receive path). */
    fun parseGroupKeyRequestPayload(payload: String): GroupKeyRequestPayload? {
        return try {
            val json = JSONObject(payload)
            GroupKeyRequestPayload(
                groupId = "", // Set by caller from the parsed groupId
                requester = json.getString("requester"),
                haveEpoch = json.getInt("have_epoch"),
                signature = if (json.has("sig")) json.getString("sig") else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GROUP_KEY_REQUEST payload", e)
            null
        }
    }

    /** Parse GROUP_KEY (key-rotation) payload, including the #187 signature. */
    fun parseGroupKeyPayload(groupId: String, payload: String): GroupKeyPayload? {
        return try {
            val json = JSONObject(payload)
            val plainKey = if (json.has("gk")) json.getString("gk").takeIf { it.isNotEmpty() } else null
            GroupKeyPayload(
                groupId = groupId,
                signer = json.getString("signer"),
                epoch = json.getInt("epoch"),
                // GR-OPEN G2: the sealed form carries its key as `gk` and no enc_key; every other form needs enc_key.
                encryptedGroupKey = if (plainKey != null) json.optString("enc_key", "") else json.getString("enc_key"),
                reason = if (json.has("reason")) json.getString("reason") else "rotation",
                signature = if (json.has("sig")) json.getString("sig").takeIf { it.isNotEmpty() } else null,
                plainKey = plainKey,
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
            // #187 [7]: emitted ONLY when captured, so a legacy/un-healed group serializes
            // byte-identically to today (no wire change until a key is actually captured).
            info.creatorPublicKey?.let { put("creator_pub", it) }
            // Round 8 P6: emitted only for a group we were removed from, so every other group serializes as before.
            if (info.removedByAdmin) put("removed", true)
            // Round 8 MA1: emitted only for a group this phone rebuilt from its own sent invite.
            if (info.announcerRebuilt) put("announcer_rebuilt", true)
            // ZC-757: emitted only for a rebuilt group past its new key / past a member reached.
            if (info.rekeyedAfterRebuild) put("rekeyed_after_rebuild", true)
            if (info.rebuiltKeyReachedMember) put("rebuilt_key_reached_member", true)
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
                isActive = obj.optBoolean("is_active", true),
                // #187 [7]: absent on legacy stored JSON → null (loads unchanged, no migration).
                creatorPublicKey = obj.optString("creator_pub", "").ifEmpty { null },
                // Round 8 P6: absent on every group we were not removed from.
                removedByAdmin = obj.optBoolean("removed", false),
                // Round 8 MA1: absent on every group that was not rebuilt.
                announcerRebuilt = obj.optBoolean("announcer_rebuilt", false),
                rekeyedAfterRebuild = obj.optBoolean("rekeyed_after_rebuild", false),
                rebuiltKeyReachedMember = obj.optBoolean("rebuilt_key_reached_member", false),
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
                // D3: optional (absent = up to date) — same additive pattern as invite_status.
                member.pendingKeyEpoch?.let { put("pending_key_epoch", it) }
                // C1: optional too (absent = no unconfirmed key memo).
                member.pendingKeyTxIds?.let { put("pending_key_tx_ids", JSONArray(it)) }
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
                        ?.let { runCatching { InviteStatus.valueOf(it) }.getOrNull() },
                    // D3: tolerate legacy rosters saved before delivery tracking (absent → null)
                    pendingKeyEpoch = if (obj.has("pending_key_epoch")) obj.getInt("pending_key_epoch") else null,
                    // C1: absent on rosters written before it → null
                    pendingKeyTxIds =
                        obj.optJSONArray("pending_key_tx_ids")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } },
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
