package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.TransactionId
import java.time.Instant

/**
 * Admin policy for group management.
 */
enum class AdminPolicy {
    CREATOR_ONLY,   // Only creator can manage group
    MULTI_ADMIN,    // Multiple admins (future)
    DEMOCRATIC      // Majority vote (future)
}

/**
 * Group membership status.
 */
enum class MemberStatus {
    INVITED,    // Invitation sent, pending acceptance
    ACTIVE,     // Member has accepted and is active
    LEFT,       // Member voluntarily left
    KICKED      // Member was removed by admin
}

/**
 * Delivery status of the GROUP_INVITE we sent to a member (P1.4). Tracked on rosters WE invite
 * from; null for members we didn't invite (the creator, or legacy rosters saved before tracking).
 * Distinct from [MemberStatus]: SENT means the invite tx was submitted on-chain, NOT that the
 * member accepted (acceptance flips [MemberStatus.INVITED] → ACTIVE via GROUP_ACCEPT).
 */
enum class InviteStatus {
    INVITE_PENDING, // invite not yet attempted / still in flight
    SENT,           // invite submitted on-chain, awaiting the member's GROUP_ACCEPT
    FAILED          // invite failed after retries — repairable via "Resend invite" in group settings
}

/**
 * Group message type for protocol parsing.
 */
enum class GroupMessageType(val code: String) {
    GROUP_CREATE("GC"),     // Create new group
    GROUP_INVITE("GI"),     // Invite member
    GROUP_ACCEPT("GA"),     // Accept invitation
    GROUP_LEAVE("GL"),      // Leave group
    GROUP_KICK("GK"),       // Kick member (admin only)
    GROUP_MSG("GM"),        // Regular message
    GROUP_KEY("GY"),        // Key rotation
    GROUP_INFO("GF"),       // Update group info
    GROUP_KEY_REQUEST("GR"), // D3: member PULLS the current key from the admin (missed rotation)

    // Round 8 MA1 (member announce): the admin tells a member that one other member is in (A) or out (R) of the group,
    // signed per recipient (ZMSGGroupProtocol.createGroupMemberAnnounceMessage). Nothing acts on it yet: until MA2 it
    // takes the dispatch's `else` branch, as it does on v2.12.4, where fromCode("GP") is null.
    GROUP_MEMBER("GP"),

    // Round 8 MA1: a member asks the admin to re-send the current announces (relay only, never on-chain).
    GROUP_ROSTER_QUERY("GQ");

    companion object {
        fun fromCode(code: String): GroupMessageType? =
            entries.find { it.code == code }
    }
}

/**
 * Represents a group chat.
 */
data class GroupInfo(
    val groupId: String,
    val name: String,
    val creatorAddress: String,
    val createdAt: Instant,
    val adminPolicy: AdminPolicy = AdminPolicy.CREATOR_ONLY,
    val currentEpoch: Int = 0,
    val groupKey: String? = null,  // Encrypted group key (Base64)
    val isActive: Boolean = true,
    // #187 [7]: the creator's E2E public key, captured at JOIN from the address we already trust as
    // the inviter (== creator by definition at JOIN). Lets control-message AUTHORIZATION compare on
    // KEY IDENTITY (signerPub == creatorPublicKey) instead of the creatorAddress STRING, so a
    // legitimate admin whose address representation later drifts (#205/#214) still authorizes their
    // own signed kick/rotation. Nullable + LAST so every positional GroupInfo(...) construction and
    // legacy stored JSON (no creator_pub) load unchanged; null → the legacy string gate.
    val creatorPublicKey: String? = null,
    /**
     * Round 8 P6 (PHONE_TRIAGE F5b) — true when this phone left the group because the admin removed us (a verified
     * GROUP_KICK naming us), false for a group we left ourselves. Only read while [isActive] is false, to say which
     * one happened. Local only; LAST so every positional construction and stored JSON load unchanged.
     */
    val removedByAdmin: Boolean = false,
    /**
     * Round 8 MA1 (member announce, design §4.1) — true when this phone rebuilt the group from its own sent invite
     * (a restore, or a second device on the same seed) instead of creating it here. Such an announcer sends member
     * announces only on the admin's tap, never automatically. Local only; written only when true, so every other
     * group serializes as before; LAST so every positional construction and stored JSON load unchanged.
     */
    val announcerRebuilt: Boolean = false,
    /**
     * ZC-757 (G1, G6 step 1) — a group this phone rebuilt ([announcerRebuilt]) whose admin made a new key on this phone
     * after the rebuild (written after the new key's number is on the disk). Local only; written only when true.
     */
    val rekeyedAfterRebuild: Boolean = false,
    /**
     * ZC-757 (G6 step 2) — that new key, or an invitation after it, was sent to at least one member row. Only then does a
     * rebuilt group send messages and grant key requests. Local only; written only when true.
     */
    val rebuiltKeyReachedMember: Boolean = false,
) {
    /**
     * Generate a unique group ID.
     */
    companion object {
        fun generateGroupId(creatorAddress: String): String {
            val timestamp = System.currentTimeMillis()
            val random = java.util.UUID.randomUUID().toString().take(8)
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$creatorAddress$timestamp$random".toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
            return "zgrp_$hash"
        }

        private val GROUP_ID_SHAPE = Regex("^zgrp_[0-9a-f]{24}$")

        /**
         * Round 8 MA1 — true when [groupId] has exactly the shape [generateGroupId] writes ("zgrp_" and 24 lowercase
         * hex digits, since v2.8.1). A member-announce memo naming any other id is dropped whole.
         */
        fun isGroupIdShaped(groupId: String): Boolean = GROUP_ID_SHAPE.matches(groupId)
    }
}

/**
 * Represents a member in a group.
 */
data class GroupMember(
    val address: String,
    val publicKey: String? = null,  // E2E public key for key exchange
    val joinedAt: Instant = Instant.now(),
    val status: MemberStatus = MemberStatus.INVITED,
    val isAdmin: Boolean = false,
    val nickname: String? = null,  // Local nickname for this member
    val inviteStatus: InviteStatus? = null,  // P1.4: delivery status of OUR invite to this member
    // D3: the key epoch we still OWE this member after an incomplete rotation fan-out (kick/rotate),
    // null = up to date. Tracked only on rosters WE rotate from (the admin side); persisted as an
    // OPTIONAL roster field (absent on legacy rosters → null) and repaired by retryPendingGroupKeys
    // / the GROUP_KEY_REQUEST pull path.
    val pendingKeyEpoch: Int? = null,
    // C1 (B1 review item 2): the last key memo to this member MAY have been sent — the wallet stored its
    // transaction(s) and the network rejected them, so the SDK may still broadcast them. Not owed (paying again
    // could pay twice) and not confirmed either: these are the stored transaction ids (possibly none known),
    // kept so a later reconciliation can tell "mined" from "expired unmined". null = no such send. Local only,
    // OPTIONAL roster field (absent on older rosters → null).
    val pendingKeyTxIds: List<String>? = null,
) {
    /**
     * Display name: nickname or truncated address.
     */
    val displayName: String
        get() = nickname ?: "${address.take(8)}...${address.takeLast(6)}"
}

/**
 * Represents a message in a group chat.
 */
data class GroupMessage(
    val id: String,
    val groupId: String,
    val txId: TransactionId?,
    val seq: Long,              // Sequence number from sender
    val epoch: Int,             // Key epoch used for encryption
    val senderAddress: String,
    val encryptedContent: String?,  // Encrypted message content
    val decryptedContent: String?,  // Decrypted message (after processing)
    val nonce: String?,         // Encryption nonce
    val timestamp: Instant,
    val blockHeight: Long? = null,
    val txIndex: Int? = null,
    val isPending: Boolean = false,
    val isFailed: Boolean = false,
    // GR-OPEN G1: stored but not shown (a pre-removal-key message from a non-member, SPEC §1.3); the stored row carries
    // "held": "removal". Only the display paths leave it out; the merge and dedup paths keep it.
    val held: Boolean = false,
) {
    /**
     * Display text for the message.
     */
    val displayText: String
        get() = when {
            // ZC-493: a failed message keeps its text on screen (the group screen draws "!" beside it); the row is the
            // only place the user can still read it.
            isFailed -> decryptedContent ?: "[Failed to send]"
            isPending -> decryptedContent ?: "[Sending...]"
            decryptedContent != null -> decryptedContent
            // More actionable than a bare "[Unable to decrypt]" — points at the usual cause (a group
            // key this device doesn't have, e.g. joined after the message or an epoch rotation).
            else -> "🔒 Can't decrypt — you may be missing this group's key"
        }

    /**
     * True when the message could not be decrypted (no plaintext, not pending/failed) — i.e. this
     * device is likely missing the group key for the message's epoch. The UI surfaces a
     * "Sync group keys" recovery action for these.
     */
    val isUndecryptable: Boolean
        get() = !isFailed && !isPending && decryptedContent == null

    companion object {
        /**
         * Round 9 R9-5 (REVIEW_R8 F5 = device D-F3) — the order the group screen lists messages in: by time, then the
         * chain order (block height, then index in the block, each unknown last), then sender, the sender's sequence
         * number and the row id. A total order, so the list is the same however the rows arrived.
         *
         * Before, the time was never compared: relay rows and the rows the live path stored (no height) were ordered by
         * sender address, and a mined row went above every relay row.
         */
        private val ORDERING: Comparator<GroupMessage> =
            compareBy<GroupMessage> { it.timestamp }
                .thenBy(nullsLast()) { it.blockHeight }
                .thenBy(nullsLast()) { it.txIndex }
                .thenBy { it.senderAddress }
                .thenBy { it.seq }
                .thenBy { it.id }

        fun compareForOrdering(
            a: GroupMessage,
            b: GroupMessage
        ): Int = ORDERING.compare(a, b)
    }
}

/**
 * Represents a group conversation for the chat list.
 */
data class GroupConversation(
    val groupInfo: GroupInfo,
    val members: List<GroupMember>,
    val messages: List<GroupMessage>,
    val lastMessage: GroupMessage?,
    val unreadCount: Int = 0,
    val draft: String? = null,
    // Round 8 MA2 (member announce, design §5.2 / §9), on a member's phone whose roster the group's creator announced:
    // the member addresses the creator shared ("Shared by the group creator"), the message senders who are not in the
    // member list (a message to the group does not reach them), and whether the one-time notice shows.
    val sharedByCreator: Set<String> = emptySet(),
    val sendersNotInList: Set<String> = emptySet(),
    // Round 10b R10B-2 (F-R9B-2): the message senders the creator removed (an R announce, or a roster row KICKED or
    // LEFT): "No longer in this group", never "not in your member list yet". Not in sendersNotInList.
    val sendersRemoved: Set<String> = emptySet(),
    // GROUP-LABEL: sender address -> the name the 1-to-1 chat header uses (nickname, saved contact, roster snapshot),
    // only for senders with a row in this group's roster; a sender with no entry shows PeerName.shortAddress. Filled for
    // the group screen only (loadGroup detail), empty on the chat list.
    val senderNames: Map<String, String> = emptyMap(),
    val rosterSharedNotice: Boolean = false,
    // Round 8 MA3 (member announce, design §6.2, §9), on the group's announcer only: the introduce banner, the rebuilt
    // group's button and the member-row notes. Null on a member's phone.
    val introduce: GroupIntroduceInfo? = null,
) {
    /**
     * Number of active members.
     */
    val activeMemberCount: Int
        get() = members.count { it.status == MemberStatus.ACTIVE }

    /**
     * Header member-count label. The chat header used to show the ACTIVE count while Group Settings
     * shows the TOTAL roster, so a group with pending invites read "1 members" in the header and
     * "3 members" in settings (#A). Show the TOTAL (matching settings) and, when some members are still
     * invited/pending, the active count too — so both surfaces agree and the pending state is visible.
     */
    val memberCountLabel: String
        get() {
            // Exclude removed (LEFT) members from BOTH the total and the active count — a kicked member
            // is gone, not a pending invitee.
            val total = members.count { it.status != MemberStatus.LEFT }
            val unit = if (total == 1) "member" else "members"
            return if (activeMemberCount < total) "$total $unit · $activeMemberCount active" else "$total $unit"
        }

    /**
     * Whether the current user is an admin.
     */
    fun isAdmin(userAddress: String): Boolean =
        members.find { it.address == userAddress }?.isAdmin == true ||
        groupInfo.creatorAddress == userAddress

    /**
     * Whether the group has a draft message.
     */
    val hasDraft: Boolean
        get() = !draft.isNullOrBlank()

    /**
     * Display name with member count.
     */
    val displayTitle: String
        get() = "${groupInfo.name} (${activeMemberCount})"
}

/**
 * Payload for GROUP_CREATE message.
 */
data class GroupCreatePayload(
    val groupId: String,
    val name: String,
    val creator: String,
    val createdAt: Long,  // Unix timestamp
    val members: List<String>,  // Initial member addresses
    val adminPolicy: String,
    val keyEpoch: Int,
    val encryptedGroupKey: String  // Per-recipient encrypted key
)

/**
 * Payload for GROUP_INVITE message.
 */
data class GroupInvitePayload(
    val groupId: String,
    val groupName: String,
    val inviter: String,
    val inviterPublicKey: String,  // For key exchange
    val members: List<String>,
    val keyEpoch: Int,
    val encryptedGroupKey: String
)

/**
 * Payload for GROUP_ACCEPT message.
 */
data class GroupAcceptPayload(
    val groupId: String,
    val accepter: String,
    val accepterPublicKey: String, // For key exchange
    // #219: accepter's signature over groupAcceptSignedData(groupId, accepter, accepterPublicKey),
    // made with their E2E private key. Lets the inviter authenticate the accept before adopting the
    // accepter's declared receive address (#218) — an unsigned/forged accept can't redirect fan-out.
    // Empty for legacy (pre-#219) accepts.
    val signature: String = "",
    // ZC-506: the key epoch of the invite this accept answers (unsigned); null when the accept carries none.
    val keyEpoch: Int? = null,
)

/**
 * Payload for GROUP_MSG message.
 */
data class GroupMsgPayload(
    val groupId: String,
    val seq: Long,
    val epoch: Int,
    val sender: String,
    val nonce: String,
    val ciphertext: String,
    val timestamp: Long,
    // Author signature over groupMsgSignedData (empty = legacy/unsigned sender, or omitted to fit the
    // 512-byte memo). Only the ON-CHAIN receive path consults it; the NOSTR path is seal-authenticated.
    val signature: String = ""
)

/**
 * Payload for GROUP_LEAVE message.
 */
data class GroupLeavePayload(
    val groupId: String,
    val leaver: String,
    // Unix SECONDS, as every sender writes it; 0 when the memo carries none.
    val timestamp: Long,
    // Round 8 MA1: the leaver's pairwise signature over ZMSGGroupProtocol.groupLeaveSignedData, or null (unsigned, as
    // v2.12.4 and every earlier leave). Nothing verifies it before MA2.
    val signature: String? = null,
)

/**
 * Payload for GROUP_KICK message. [signature] is the admin's #187 signature over the canonical
 * kick fields ([ZMSGGroupProtocol.groupKickSignedData]); null/empty for legacy unsigned kicks, which
 * the receiver MUST refuse to act on.
 */
data class GroupKickPayload(
    val groupId: String,
    val kicked: String,
    val kicker: String,
    val newEpoch: Int,
    val encryptedGroupKey: String?,  // New key for remaining members
    val signature: String?,
    // GR-OPEN G2: the sealed form's new group key in plaintext (`gk`); null in every signed kick.
    val plainKey: String? = null,
)

/**
 * Payload for GROUP_KEY_REQUEST (D3 pull side — replaces the inert #197 "sync group keys"): a member
 * who missed a rotation asks the admin to re-send the CURRENT wrapped group key. [haveEpoch] is the
 * epoch the requester already holds (bounded by the admin via
 * [ZMSGGroupProtocol.mayGrantKeyRequest]). [signature] is the requester's signature over
 * [ZMSGGroupProtocol.groupKeyRequestSignedData] with their pairwise KEX key for the admin
 * (null/absent on the NOSTR path, where the seal authenticates instead); an on-chain request the
 * admin can't authenticate MUST be ignored.
 */
data class GroupKeyRequestPayload(
    val groupId: String,
    val requester: String,
    val haveEpoch: Int,
    val signature: String?
)

/**
 * Payload for GROUP_KEY (key-rotation) message. [signature] is the admin's #187 signature over the
 * canonical rotation fields ([ZMSGGroupProtocol.groupKeySignedData]); null/empty for legacy unsigned
 * rotations, which the receiver MUST refuse to act on.
 */
data class GroupKeyPayload(
    val groupId: String,
    val signer: String,
    val epoch: Int,
    val encryptedGroupKey: String,
    val reason: String,
    val signature: String?,
    // GR-OPEN G2: the sealed form's group key in plaintext (`gk`; encryptedGroupKey is then empty); null when signed.
    val plainKey: String? = null,
)

/**
 * State for the create group screen.
 */
data class CreateGroupState(
    val groupName: String = "",
    val selectedMembers: List<String> = emptyList(),
    val availableContacts: List<Contact> = emptyList(),
    // ZC-733-B: the saved names cannot be read; the picker says so above its list.
    val contactNamesUnreadable: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    val createdGroupId: String? = null,
    // #199: members whose GROUP_INVITE couldn't be sent (e.g. ran out of spendable notes even after
    // block-wait retries). Surfaced so the user knows who wasn't invited instead of the invite being
    // silently dropped; retained so a retry can target only these.
    val failedInvites: List<String> = emptyList()
) {
    val isValid: Boolean
        get() = groupName.isNotBlank() && selectedMembers.isNotEmpty()

    /** The group's size for the Create Group button: everyone chosen plus the creator. Not a cost count. */
    val memberCount: Int
        get() = selectedMembers.size + 1  // +1 for creator

    /**
     * R6-2 — how many members an invite and every group message go to: everyone chosen, never the creator,
     * who is not paid for. The cost note quotes this, so it states the same count and total as the
     * GROUP_CREATE consent prompt (one on-chain invite per chosen member reached on-chain).
     */
    val inviteeCount: Int
        get() = selectedMembers.distinct().size

    /**
     * Round 10b R10B-3 (F-R9C-6): the group size the Create Group button names, or null with nobody chosen yet (the
     * button then reads plain «Create Group» and stays disabled; it read «Create Group (1 member)»).
     */
    val buttonGroupSize: Int?
        get() = if (selectedMembers.isEmpty()) null else memberCount

    /**
     * Round 10b R10B-3 (F-R9C-6): the member count the cost note quotes, or null with nobody chosen yet (the note then
     * asks for a member instead of quoting «at least 0.00017 ZEC for 1 member» for a member nobody chose).
     */
    val costNoteMembers: Int?
        get() = inviteeCount.takeIf { it > 0 }
}

/**
 * Round 8 MA3 (MEMBER_ANNOUNCE_DESIGN.md v2 §6.2, §9) — what the group's announcer sees about its member announces.
 * - [owedOnChainPairs]: `A` announces still owed to members with no relay route (each would be one paid memo);
 * - [unreachablePairs]: announces owed to members with a relay key that never acked (retried free, never paid);
 * - [showBanner]: the "Introduce them" banner shows (pairs owed, relays ready, not hidden by "Not now", not rebuilt);
 * - [rebuilt]: this phone rebuilt the group; only the "Introduce members" tap announces;
 * - [noSecureLink] / [tooBig] / [leftUnconfirmed]: member rows with the matching note (addresses as in the roster).
 */
data class GroupIntroduceInfo(
    val owedOnChainPairs: Int = 0,
    val unreachablePairs: Int = 0,
    val showBanner: Boolean = false,
    val rebuilt: Boolean = false,
    val noSecureLink: Set<String> = emptySet(),
    val tooBig: Set<String> = emptySet(),
    val leftUnconfirmed: Set<String> = emptySet(),
    // True while some ACTIVE member has not shown it reads member announces (no gp1 seen): the mixed-version note.
    val mixedVersion: Boolean = false,
)

/** Round 8 MA3 — an "Introduce them" run of [groupId]: [done] of [total] announces paid so far. */
data class IntroduceProgress(
    val groupId: String,
    val done: Int,
    val total: Int,
)

/**
 * State for the group detail screen.
 */
sealed interface GroupDetailState {
    data object Loading : GroupDetailState
    data class Success(
        val conversation: GroupConversation,
        val currentUserAddress: String,
        val zecPriceUsd: Double? = null
    ) : GroupDetailState
    data class Error(val message: String) : GroupDetailState
}

/**
 * State for group settings screen.
 */
sealed interface GroupSettingsState {
    data object Loading : GroupSettingsState
    data class Success(
        val groupInfo: GroupInfo,
        val members: List<GroupMember>,
        val currentUserAddress: String,
        val isCreator: Boolean,
        // Round 8 MA2: the member addresses the group's creator shared with this phone (GroupConversation.sharedByCreator).
        val sharedByCreator: Set<String> = emptySet(),
        // Round 8 MA3: on the group's creator, the member-row notes of the member announce (GroupConversation.introduce).
        val introduce: GroupIntroduceInfo? = null,
        // Round 9 R9-5 (REVIEW_R8 F11): this phone's roster holds only the members it has heard from, so the header says
        // "known members" (GroupMessageCost.knownOnly: not the admin, and the creator has not announced the roster).
        val knownOnly: Boolean = !isCreator,
        // GROUP-LABEL: member address -> the name the 1-to-1 chat header uses (GroupConversation.senderNames); a member
        // with no entry shows its roster snapshot or short address (GroupMember.displayName).
        val names: Map<String, String> = emptyMap(),
        // F8-G (ZC-112): member address -> the failed key message this member's "Key not delivered" badge stands for
        // (the current key's message or grant was proven never to have reached the chain). Only on the creator's phone.
        val keyNotDelivered: Map<String, GroupResendOffer> = emptyMap(),
        // F8-G (ZC-112): member address -> the oldest copy of one of our group messages that was proven never to have
        // reached the chain for this member ("Message not delivered").
        val messageNotDelivered: Map<String, GroupResendOffer> = emptyMap(),
        // ZC-420-G: the INVITED member rows whose invite only a public relay took ("Invited, not confirmed", with a Resend
        // that goes over the relays and asks no price). Only on the creator's phone.
        val inviteUnconfirmed: Set<String> = emptySet(),
    ) : GroupSettingsState
    data class Error(val message: String) : GroupSettingsState
}

/**
 * F8-G (ZC-112) — what a member row's "Key not delivered" or "Message not delivered" badge was drawn from: the key of the
 * send-attempt ledger entry and that entry's marker. Resend hands it back, so a tap on a badge that is out of date (the
 * attempt was sent again from another screen, mined late, or delivered another way) asks nothing and pays nothing.
 */
data class GroupResendOffer(
    val key: String,
    val startedAt: Long,
)

/**
 * ZC-757 (FIRST-CONTACT-SPEC 5.8, G1, G2, G6) — the rules of a group this phone rebuilt from its own sent history (a
 * restore, or a second phone on the same phrase). Its key number and member list are only partly known, so it sends
 * nothing under an old key: its admin makes a new key first, numbered above any number a member can hold.
 */
object RebuiltGroupKey {
    /** What a rebuilt group may do now. */
    enum class Stage {
        /** Not a rebuilt group, or one whose new key reached a member: as any other group. */
        NONE,

        /** No new key yet: no message, no invitation, no key grant. */
        NEEDS_KEY,

        /** A new key that no member row was sent yet: invitations only. */
        NO_READER,
    }

    /**
     * The block time (milliseconds) of the newest mined transaction the wallet listed in a history pass of this process
     * that read the whole history (ZC-1068: scan complete, every memo read); 0 before one. With the phone's clock it gives the time floor ([firstKeyNumber]).
     */
    @Volatile
    var newestMinedTxMs: Long = 0L

    /** The phone's clock (milliseconds); a test may set it. */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    private const val MS_PER_MINUTE = 60_000L

    fun stage(info: GroupInfo?): Stage =
        when {
            info == null || !info.isActive || !info.announcerRebuilt -> Stage.NONE
            !info.rekeyedAfterRebuild -> Stage.NEEDS_KEY
            !info.rebuiltKeyReachedMember -> Stage.NO_READER
            else -> Stage.NONE
        }

    /** G2's time floor: whole minutes since 1970 of the later of [nowMs] and [newestBlockMs]. */
    fun timeFloor(
        nowMs: Long,
        newestBlockMs: Long,
    ): Int = (maxOf(nowMs, newestBlockMs, 0L) / MS_PER_MINUTE).coerceAtMost(Int.MAX_VALUE.toLong() - 1).toInt()

    /**
     * G2 — the first number a rebuilt group's new key may take: above the stored number (which G3 raised to the highest
     * number of this phone's own on-chain memos of the group) and above the time floor.
     */
    fun firstKeyNumber(
        storedEpoch: Int,
        nowMs: Long = clock(),
        newestBlockMs: Long = newestMinedTxMs,
    ): Int = maxOf(storedEpoch, timeFloor(nowMs, newestBlockMs)) + 1
}
