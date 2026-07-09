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
    GROUP_INFO("GF");       // Update group info

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
    val isActive: Boolean = true
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
    val inviteStatus: InviteStatus? = null  // P1.4: delivery status of OUR invite to this member
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
    val isFailed: Boolean = false
) {
    /**
     * Display text for the message.
     */
    val displayText: String
        get() = when {
            isFailed -> "[Failed to send]"
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
         * Compare messages for ordering.
         * Uses block height, tx index, sequence, and sender for deterministic ordering.
         */
        fun compareForOrdering(a: GroupMessage, b: GroupMessage): Int {
            // 1. Block height (confirmed before pending)
            if (a.blockHeight != b.blockHeight) {
                if (a.blockHeight == null) return 1  // Pending goes last
                if (b.blockHeight == null) return -1
                return a.blockHeight.compareTo(b.blockHeight)
            }
            // 2. Transaction index within block
            if (a.txIndex != b.txIndex) {
                val aIdx = a.txIndex ?: Int.MAX_VALUE
                val bIdx = b.txIndex ?: Int.MAX_VALUE
                return aIdx.compareTo(bIdx)
            }
            // 3. Sender sequence (for same-sender rapid messages)
            if (a.senderAddress == b.senderAddress && a.seq != b.seq) {
                return a.seq.compareTo(b.seq)
            }
            // 4. Deterministic tie-break by sender
            return a.senderAddress.compareTo(b.senderAddress)
        }
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
    val draft: String? = null
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
    val signature: String = ""
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
    val timestamp: Long
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
    val signature: String?
)

/**
 * State for the create group screen.
 */
data class CreateGroupState(
    val groupName: String = "",
    val selectedMembers: List<String> = emptyList(),
    val availableContacts: List<Contact> = emptyList(),
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

    val memberCount: Int
        get() = selectedMembers.size + 1  // +1 for creator
}

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
        val isCreator: Boolean
    ) : GroupSettingsState
    data class Error(val message: String) : GroupSettingsState
}
