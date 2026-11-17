package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import co.electriccoin.zcash.spackle.process.ProcessNameCompat
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.common.util.redactConvId
import co.electriccoin.zcash.ui.nostr.RelaySetState
import co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerEntry
import co.electriccoin.zcash.ui.screen.chat.model.AnnouncePair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageConsent
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.announceLedgerFromJson
import co.electriccoin.zcash.ui.screen.chat.model.announceLedgerToJson
import co.electriccoin.zcash.ui.screen.chat.model.announceStateFromJson
import co.electriccoin.zcash.ui.screen.chat.model.announceStateToJson
import co.electriccoin.zcash.ui.screen.chat.model.casAnnounceLedger
import kotlinx.coroutines.flow.update

/**
 * Result of a rate-limited destroy-PIN verification.
 */
sealed class DestroyPinVerifyResult {
    /** Correct PIN. Counter is cleared. */
    data object Success : DestroyPinVerifyResult()

    /** Wrong PIN. Caller may try again immediately. */
    data class Failed(val attemptsRemaining: Int) : DestroyPinVerifyResult()

    /** Rate-limited. Caller must wait [remainingMillis] before retrying. */
    data class LockedOut(val remainingMillis: Long) : DestroyPinVerifyResult()
}

/**
 * Notification privacy levels for ZCHAT.
 * Controls how much information is shown in notifications.
 */
enum class NotificationPrivacy {
    /** Shows sender name and message content. "Alice: Hey, how are you?" */
    FULL_PREVIEW,

    /** Shows only the sender name. "New message from Alice" */
    SENDER_ONLY,

    /** Shows a generic notification. "New ZCHAT message" */
    NEW_MESSAGE,

    /** No notifications are shown. User must check app manually. */
    SILENT
}

/**
 * Interface for ZCHAT preferences.
 *
 * Extends [co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyGenStore] — the narrow slice
 * (our-key get/set, key version, provenance, seed-index allocator) that
 * [co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider] consumes for
 * seed-derived E2E key generation (D1-recovery Stage 3). Those members are declared there.
 */
interface ZchatPreferences :
    co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyGenStore,
    OwnChatIdentityStore {
    /**
     * Check if user has acknowledged that messages cost ZEC.
     */
    fun hasAcknowledgedMessageCost(): Boolean

    /**
     * Mark that user has acknowledged message cost.
     */
    fun setAcknowledgedMessageCost()

    /**
     * True once the user has agreed that ZChat may spend ZEC on AUTOMATIC handshake memos
     * (KEX / KEXACK / ZBOOT / time-lock / group-accept). These carry no user gesture of their own —
     * a block tick or merely opening a chat fires them — so without this they spend silently.
     */
    fun hasConsentedToHandshakeSpend(): Boolean = false

    /**
     * R5-2 — written synchronously, and @return whether it reached the disk. Granting consent releases every
     * queued automatic spend at once, so a grant that was not written must release nothing: the app would
     * otherwise spend against a consent no restart can find, and ask the user a second time for memos it
     * has already paid for.
     */
    fun setConsentedToHandshakeSpend(): Boolean = false

    /**
     * Forget that consent, so the next automatic handshake asks again. Confirming a move of the old
     * Orchard balance calls this, before the move is sent (owner decision 2, 2026-09-10): queued
     * handshakes would otherwise spend the moved funds the moment they confirm.
     *
     * R4-1 — written synchronously, and @return whether it reached the disk. The move must not be handed
     * to the SDK until it has: a kill with consent still stored lets the queued handshakes spend the
     * moved funds without asking.
     */
    fun clearConsentedToHandshakeSpend(): Boolean = false

    /**
     * ZC1013-X2 — the automatic setups the user answered "Not now" to in the question "Allow handshake transactions?",
     * as AutomaticSetupQueue writes them (kind, target and scope, tab-separated, one per setup). They outlive the
     * process: under the standing consent such a setup still asks, until Allow clears them. Empty when none is stored.
     */
    fun getDeclinedAutomaticSetups(): Set<String> = emptySet()

    /** ZC1013-X2 — replaces the stored "Not now" marks (empty removes them); @return whether the write reached the disk. */
    fun setDeclinedAutomaticSetups(marks: Set<String>): Boolean = false

    /**
     * R5-1 — the members of [groupId] the user has already agreed to pay an on-chain memo for when they
     * send a message to that group. One consent per group, remembered, NOT one per message: the addresses
     * are kept (not a count) so a consent for two members can never be spent on a different, larger set.
     * Empty when the user has never agreed to pay for this group.
     *
     * ZC-130 — a member's consent lasts only while the member stays in the group's paid set without leaving the
     * group: every roster write ([saveGroupMembers], [saveGroupMembersDurably]) drops a member whose row is no longer
     * ACTIVE (left, removed, re-invited), and the whole consent when our own row is no longer ACTIVE or the roster does
     * not parse; a message that pays without asking first cuts it to the members it pays for. So the next message
     * asks again for a member back in the paid set after leaving the group, or after being reachable free.
     */
    fun getGroupMessageSpendConsent(groupId: String): Set<String> = emptySet()

    /**
     * R5-1 — remember that the user agreed to pay for [payees] when messaging [groupId]. Called only after
     * the user confirmed a quote that covered exactly those members (stored as they are, never added to the old
     * consent), or with a cut of the stored consent (ZC-130). @return whether it reached the disk; a lost write
     * only means the next message asks again, which is the safe direction.
     */
    fun setGroupMessageSpendConsent(
        groupId: String,
        payees: Set<String>
    ): Boolean = false

    /**
     * The Orchard balance, in zatoshi, at which the user dismissed the "Old balance" card of the account
     * [accountKey] for a balance too small to move; null when never dismissed. The card comes back when
     * the balance differs from it (owner decision 3, 2026-09-10).
     */
    fun getDismissedOldBalance(accountKey: String): Long? = null

    fun setDismissedOldBalance(accountKey: String, orchardZatoshi: Long) = Unit

    /**
     * Bridge 3: the user hid the Shielded-chat reminder in the chat with [peerAddress]; the chat then shows the
     * small "May need update" chip, which clears this. Kept on disk under a hash of the address.
     */
    fun isShieldedReminderHidden(peerAddress: String): Boolean = false

    fun setShieldedReminderHidden(peerAddress: String, hidden: Boolean) = Unit

    /**
     * Get the set of hidden message IDs.
     */
    fun getHiddenMessageIds(): Set<String>

    /**
     * Add a message ID to the hidden set.
     */
    fun hideMessage(messageId: String)

    /**
     * IDs of inbound payment REQUESTS the user has already fulfilled (paid). Durable so the "Pay"
     * affordance stays hidden across reloads — prevents accidentally paying the same request twice.
     */
    fun getPaidRequestIds(): Set<String>

    /**
     * Mark an inbound payment request (by its message id) as paid. B2 (Grok Q4): written with a checked,
     * synchronous commit() because it is the only durable record that stops a second payment after a restart.
     *
     * @return true when the marker reached the disk. On false the caller must treat the request as possibly
     *   paid for the rest of this process and tell the user to check before paying again.
     */
    fun markRequestPaid(requestMessageId: String): Boolean

    /**
     * C3 (B2 review item 5): withdraw a paid marker the fulfil wrote before its submit, once the failure proved that
     * nothing was sent. D1 (REVIEW_7C F9 c): written with a checked commit(). With apply() a kill right after it put
     * the marker back on the next start, and a request nobody paid showed "Paid" (no Pay button) for good. A failed
     * write is logged; it can only keep the request unpayable, never pay it.
     */
    fun unmarkRequestPaid(requestMessageId: String)

    /**
     * Round 8 P2 (PHONE_TRIAGE F2): a fulfil of [requestMessageId] is about to hand its payment to the SDK. Writes the
     * paid marker ([markRequestPaid]) and a started record ([FulfilStarted]) in ONE checked commit(), so a kill can
     * never leave one without the other. The record lets a later, fully read history pass withdraw a marker whose
     * payment never reached the chain (ChatViewModel's fulfil reconcile). The peer and our own address are stored as
     * hashes ([fulfilAddressHash]), never as addresses.
     *
     * @param attemptHeight the chain tip when the submit started, or -1 when unknown (never withdrawn automatically).
     * @return true when both reached the disk. On false the caller submits nothing.
     */
    fun markFulfilStarted(
        requestMessageId: String,
        attemptHeight: Long,
        peerAddress: String,
        amountZatoshi: Long,
        selfAddress: String,
        startedAtMillis: Long,
    ): Boolean = false

    /** Round 8 P2: drop the started record of [requestMessageId] (the paid marker is left as it is). */
    fun clearFulfilStarted(requestMessageId: String) {}

    /** Round 8 P2: the started records, by request id. */
    fun getFulfilStarted(): Map<String, FulfilStarted> = emptyMap()

    /**
     * F5 (fix-all 2.13.0, train T5): the SDK stored the fulfil's transactions [stored]; their ids and expiry heights
     * are added to the started record of [requestMessageId] in ONE checked commit(), only while the record written by
     * the fulfil that started at [startedAtMillis] is still there. Called by F2's onTransactionsStored, before anything
     * is submitted: on false the caller throws, so nothing is submitted (the marker stays, the request stays unpayable).
     */
    fun recordFulfilStored(
        requestMessageId: String,
        startedAtMillis: Long,
        stored: List<co.electriccoin.zcash.ui.common.datasource.StoredTransaction>,
    ): Boolean = false

    /**
     * Round 8 P2: a fulfil that started its submit and has not been seen mined. [peerHash] and [selfHash] are
     * [fulfilAddressHash] of the requester and of our own address at the time. F5: [txIds] and [expiries] are the
     * transactions the SDK stored for it ([recordFulfilStored]); empty on a record an older build wrote, or when the
     * process ended before the SDK stored anything.
     */
    data class FulfilStarted(
        val attemptHeight: Long,
        val peerHash: String,
        val amountZatoshi: Long,
        val selfHash: String,
        val startedAtMillis: Long,
        val txIds: List<String> = emptyList(),
        val expiries: Map<String, Long> = emptyMap(),
    )

    /**
     * Add multiple message IDs to the hidden set.
     */
    fun hideMessages(messageIds: Set<String>)

    /**
     * Remove a message ID from the hidden set (unhide).
     */
    fun unhideMessage(messageId: String)

    /**
     * R2-5 (c): the messages that EXPIRED under a disappearing-messages timer. Kept apart from the hidden set (a
     * message the user hid can be unhidden; an expired one is gone for good), so a reply quoting one keeps showing
     * "Original message expired" after the timer is turned off. The expired messages are also in the hidden set.
     */
    fun getExpiredMessageIds(): Set<String> = emptySet()

    fun markMessagesExpired(messageIds: Set<String>) = Unit

    // ==========================================
    // USER STATUS
    // ==========================================

    /**
     * Get the user's current status text.
     */
    fun getUserStatus(): String

    /**
     * Set the user's status text.
     */
    fun setUserStatus(status: String)

    /**
     * ZC-283 — what each contact got of the last status broadcast (see
     * co.electriccoin.zcash.ui.screen.chat.model.StatusBroadcastRecord: hashes only, no address and no text), as
     * written, or null. It survives restarts so the same status is not paid for again.
     */
    fun getStatusBroadcastRecord(): String? = null

    /**
     * ZC-283 (R4-1) — @return whether the write reached the disk. A contact is recorded right before its paid status
     * memo is submitted, so a memo whose record was not written must not be submitted. null removes the record.
     */
    fun setStatusBroadcastRecord(value: String?): Boolean = false

    /**
     * Get the timestamp when status was last updated.
     */
    fun getUserStatusTimestamp(): Long

    /**
     * Get status for a specific peer address.
     */
    fun getPeerStatus(peerAddress: String): String?

    /**
     * Set status for a peer (received from their messages).
     */
    fun setPeerStatus(peerAddress: String, status: String)

    /**
     * Get all stored peer statuses.
     */
    fun getAllPeerStatuses(): Map<String, String>

    // ==========================================
    // MEMO TEMPLATES
    // ==========================================

    /**
     * Get all custom memo templates as JSON strings.
     */
    fun getCustomMemoTemplates(): List<String>

    /**
     * Save a custom memo template (as JSON string).
     */
    fun saveCustomMemoTemplate(templateJson: String)

    /**
     * Remove a custom memo template by ID.
     */
    fun removeCustomMemoTemplate(templateId: String)

    /**
     * Get all custom template JSON strings.
     */
    fun getAllCustomTemplateJson(): Set<String>

    // ==========================================
    // FONT SIZE
    // ==========================================

    /**
     * Get the font size scale (1.0 = normal, 1.1 = 10% bigger, etc.)
     * Default is 1.0
     */
    fun getFontSizeScale(): Float

    /**
     * Set the font size scale.
     */
    fun setFontSizeScale(scale: Float)

    // ==========================================
    // CONTACT NICKNAMES
    // ==========================================

    /**
     * Get nickname for a contact address.
     * @return The nickname, or null if not set
     */
    fun getNickname(address: String): String?

    /**
     * Set nickname for a contact address.
     * @param address The contact's Zcash address
     * @param nickname The nickname to set (empty string to clear)
     */
    fun setNickname(address: String, nickname: String)

    /**
     * Get display name for an address (nickname if set, otherwise truncated address).
     * @return Nickname if set, otherwise first 8 + last 6 chars of address
     */
    fun getDisplayName(address: String): String

    /**
     * Get all stored nicknames.
     * @return Map of address -> nickname
     */
    fun getAllNicknames(): Map<String, String>

    // ==========================================
    // VIEW-ONCE FILE STATE
    // ==========================================

    /**
     * Mark a view-once file (image or audio) as consumed on this device. After this
     * call the renderer collapses the bubble to a "Viewed" placeholder and the cache
     * file is deleted by the caller. Idempotent.
     */
    fun markFileViewed(fileHash: String)

    /** True iff the file has already been consumed via markFileViewed. */
    fun isFileViewed(fileHash: String): Boolean

    /** Snapshot of every viewed-file hash — used by convertToConversations on rebuild. */
    fun getAllViewedFiles(): Set<String>

    // ==========================================
    // CONVERSATION MODE (Vault / Tunnel / Open)
    // ==========================================

    /** Returns the stored mode for a peer, or [ConversationMode.DEFAULT] if unset. */
    fun getConversationMode(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode

    /**
     * Returns the EXPLICITLY-stored mode for a peer, or null if the user never picked one. Unlike
     * [getConversationMode] this does NOT collapse "unset" into the VAULT default, so the new-chat
     * composer can apply its own smart default (Tunnel) for fresh peers while still respecting any
     * mode the user previously chose for an existing peer.
     */
    fun getConversationModeOrNull(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?

    /** Persist the mode for a peer. Pass null to clear (revert to DEFAULT). */
    fun setConversationMode(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?)

    /**
     * True iff the USER explicitly picked a mode for this chat (mode-picker), as opposed to an
     * auto-upgrade (responder ZBOOT) or an auto-adopt (peer ZMODE control). Gates ZMODE auto-adopt:
     * a peer's mode change must never override a deliberate local choice. Cleared together with the
     * mode itself (setConversationMode(peer, null)).
     */
    fun isConversationModeExplicit(peerAddress: String): Boolean

    /** Pin the current mode as the user's explicit choice (see [isConversationModeExplicit]). */
    fun setConversationModeExplicit(peerAddress: String)

    /** ZC-1230 — take back a pin an Accept made, when that Accept paid nothing and the chat went back to Open. */
    fun clearConversationModeExplicit(peerAddress: String) = Unit

    /**
     * MONOTONIC last-writer-wins guard for inbound ZMODE mode-change controls (mirrors
     * [setDisappearingTtl]): returns false + writes nothing when [sinceMillis] is not newer than the
     * stored watermark, making relay-replay / chain-rescan adoption idempotent.
     */
    fun advancePeerModeChangeSince(peerAddress: String, sinceMillis: Long): Boolean

    /**
     * ZC-1478 (W-122) — what this phone recorded about a chat's mode switches:
     * - [ownSinceMillis] (M3): when the user last switched this chat's mode on this phone, the since our ZMODE carried;
     *   0 when never (or before this was recorded). A contact's switch older than it is not adopted (ModeAdoption).
     * - [movedToPaidByPeer] (M1): the contact moved this chat into a mode that can pay (Shielded, or a Tunnel whose
     *   setup is not done) and the user has not answered a price question in it since. While set, nothing automatic
     *   pays for this chat's setup, and the first on-chain send asks with the price. Cleared when the user answers a
     *   price question for a send or picks a mode.
     */
    data class ModeSwitchRecord(
        val ownSinceMillis: Long,
        val movedToPaidByPeer: Boolean,
    )

    /** ZC-1478 — the chat's [ModeSwitchRecord]; null when nothing was recorded (read as since 0, not moved). */
    fun getModeSwitchRecord(peerAddress: String): ModeSwitchRecord? = null

    /** ZC-1478 — record [sinceMillis] as this chat's own last switch ([ModeSwitchRecord.ownSinceMillis]). */
    fun setOwnModeChangeSince(peerAddress: String, sinceMillis: Long) = Unit

    /** ZC-1478 — set or clear [ModeSwitchRecord.movedToPaidByPeer], with commit(); read it back to know it holds. */
    fun setModeMovedToPaidByPeer(peerAddress: String, moved: Boolean) = Unit

    /**
     * #37 — a peer's request to move a chat to a PAID mode (Shielded/VAULT), held for the accept/keep
     * prompt (#187: never auto-adopted). PERSISTED + process-wide reactive (mirrors [disappearingTtls])
     * so the prompt survives (a) the two-VM split — the chat-LIST ChatViewModel usually processes the
     * inbound ZMODE (its instance wins the monotonic since-guard) while the chat-DETAIL instance renders
     * the dialog — and (b) the chat not being open / process death when the control arrives. Keyed by
     * peer address; cleared on accept or keep.
     */
    val pendingModeSwitches: kotlinx.coroutines.flow.StateFlow<Map<String, co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch>>

    /** Persist [request] (one per peer; a newer request replaces the older). */
    fun setPendingModeSwitch(request: co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch)

    /** Drop the pending switch request for [peerAddress] (user accepted or kept). */
    fun clearPendingModeSwitch(peerAddress: String)

    /** An OUTBOUND mode-change control (ZMODE) we could not confirm delivered — retried free over NOSTR. */
    data class PendingModeSync(val modeName: String, val sinceMillis: Long)

    /** Record an undelivered outbound ZMODE for [peerAddress] so the free NOSTR retry loop re-sends it. */
    fun setPendingModeSync(peerAddress: String, modeName: String, sinceMillis: Long)

    /** All outbound ZMODE controls still awaiting confirmed delivery. */
    fun getPendingModeSyncs(): Map<String, PendingModeSync>

    /** Delivery confirmed (relay ack) — stop retrying for [peerAddress]. */
    fun clearPendingModeSync(peerAddress: String)

    /** Per-conversation disappearing-messages TTL (B17). effectiveSinceMillis = last-writer-wins ordering
     *  key + non-retroactivity boundary; synced to the peer via an authenticated control. */
    data class DisappearingTtl(val ttlSeconds: Long, val effectiveSinceMillis: Long)
    /** #226 process-wide singleton flow so list VM + detail VM observe the same source (like readMarkers). */
    val disappearingTtls: kotlinx.coroutines.flow.StateFlow<Map<String, DisappearingTtl>>
    fun getDisappearingTtl(peerAddress: String): DisappearingTtl?
    /** MONOTONIC on effectiveSinceMillis — returns false + writes nothing if the incoming since is not newer
     *  (makes chain-rescan / relay-replay adoption idempotent). */
    fun setDisappearingTtl(peerAddress: String, ttlSeconds: Long, effectiveSinceMillis: Long): Boolean
    /** First-observed anchor for a message's expiry clock (e.g. a PAY/CND lock's unlock time). */
    fun getOrPutMessageExpiryAnchorMillis(messageId: String, nowMillis: Long): Long
    fun clearMessageExpiryAnchor(messageId: String)

    /** Peer's published NOSTR pubkey (32-byte hex), set after a successful ZBOOT handshake. */
    fun getPeerNostrPubkey(peerAddress: String): String?

    fun setPeerNostrPubkey(peerAddress: String, pubkeyHex: String?)

    /**
     * Reverse lookup — find the peer Zcash address that registered the given NOSTR pubkey. M1: when one key is bound
     * to several addresses (a legacy double binding), the same one is always returned (NostrPeerBinding.choose).
     */
    fun findPeerByNostrPubkey(pubkeyHex: String): String?

    /**
     * ZC-1337 — the contact whose held ZBOOT (a claim mark's or a pre-update mark's slot) names the relay key [pubkeyHex], or
     * null. Such a ZBOOT verified against the stored contact key and binds nothing yet, so this grants no trust: it only says
     * a relay message from that key is kept for after the hold instead of dropped as a stranger's.
     */
    fun findPeerByHeldBootNostrPubkey(pubkeyHex: String): String? = null

    /** ZC-1349 — when the relay message [eventId], kept for a held connection, first arrived (seconds); null: not recorded. */
    fun getHeldConnectionArrivalSec(eventId: String): Long? = null

    /** ZC-1349 — record [getHeldConnectionArrivalSec], so a replay after a restart does not count as a new arrival. */
    fun setHeldConnectionArrivalSec(
        eventId: String,
        arrivalSec: Long,
    ): Boolean = false

    /**
     * ZC-1346 — keep [raw], a ZBOOT that verified while its key waits for Accept with no claim mark, for
     * [findPeerByHeldBootNostrPubkey] (the latest only). It binds nothing and goes with the hold. @return false: no hold or not saved.
     */
    fun holdAcceptWaitingBoot(
        peerAddress: String,
        raw: String,
    ): Boolean = false

    /** ZC-1346, ZC-1349 — forget every ZBOOT held for [peerAddress]; marks and the hold stay. @return false: not saved. */
    fun forgetHeldBoots(peerAddress: String): Boolean = true

    /**
     * M1 — remember [pubkeyHex] as one of our own relay (NOSTR) keys (any rotation index we derived), so no contact code
     * or key exchange binds it to a contact. Called wherever our identity is derived.
     */
    fun registerOwnNostrPubkey(pubkeyHex: String) {}

    /**
     * M1 fixup — run the one-time relay-key repair again at the next start: a key was just bound to a valid address while
     * an address that fails NostrPeerBinding.isBindable still holds it.
     */
    fun rearmNostrKeyRepair() {}

    /** M1 — whether [pubkeyHex] is one of our own relay keys ([registerOwnNostrPubkey]). */
    fun isOwnNostrPubkey(pubkeyHex: String): Boolean = false

    /** All peers whose conversation mode is TUNNEL. Used by the block-driven ZBOOT maturation
     *  retry so a handshake stuck on an immature KEX change self-heals without a manual chat-open. */
    fun getTunnelModePeers(): List<String>

    /**
     * Persistent replay defense for inbound NOSTR gift-wraps (#188). Relays REPLAY stored gift-wraps
     * on every (re)subscribe, and the same event lands on multiple relays — so the receive path must
     * drop any gift-wrap whose unique event id it has already handled. This is a DEDICATED bounded set
     * that survives process death, independent of the message store: the old dedup keyed on the
     * pending-message list, so deleting a message or pruning the list resurrected replays, and call
     * signals had NO event-id dedup at all (only a freshness window).
     */
    fun hasSeenNostrEvent(eventId: String): Boolean

    /** Record [eventId] as handled. Bounded (LRU); the oldest ids fall out once the cap is reached. */
    fun markNostrEventSeen(eventId: String)

    /**
     * Un-record [eventId] so a relay REPLAY can redeliver it. Used when handling was DEFERRED rather than
     * completed (e.g. a NOSTR GROUP_INVITE that arrived before our address loaded): the in-memory defer
     * queue does not survive process death, so un-seeing the gift-wrap lets the relay re-deliver it on the
     * next (re)subscribe — closing the "stranded forever after a cold-start crash" window. No-op if absent.
     */
    fun unmarkNostrEventSeen(eventId: String)

    /**
     * KI-24 part B: [hasSeenNostrEvent], or a gift-wrap created before the seen list's floor. [wrapCreatedAtSec] is the
     * wrap's OUTER created_at (seconds), null when unknown (then the list alone decides). The floor rises only when
     * entries leave the list, and never above now − 49 h (the 48 h NIP-59 jitter plus one hour), so a new wrap is never
     * taken for an old one.
     */
    fun hasSeenNostrEvent(
        eventId: String,
        wrapCreatedAtSec: Long?,
    ): Boolean = hasSeenNostrEvent(eventId)

    /** KI-24 part B: [markNostrEventSeen] with the wrap's outer created_at, so the list can keep it by time. */
    fun markNostrEventSeen(
        eventId: String,
        wrapCreatedAtSec: Long,
    ) = markNostrEventSeen(eventId)

    /** KI-24 part B: the `since` (seconds) the inbox's next REQ to [relayUrl] carries, or null for none. */
    fun getNostrRelaySince(relayUrl: String): Long? = null

    /**
     * KI-24 part B: moves [relayUrl]'s `since` line to [sinceSec] if that is higher than the stored value; it is never
     * stored above now − 49 h. @return whether it moved.
     */
    fun advanceNostrRelaySince(
        relayUrl: String,
        sinceSec: Long,
    ): Boolean = false

    /** RELAY-HEALTH: the seen list's floor (seconds; 0 = none): every wrap created at or before it counts as seen. */
    fun nostrSeenFloorSec(): Long = 0L

    /** RELAY-HEALTH: the saved relay set (the app-scope "zchat_relay_set" file), or null for the defaults. */
    fun getRelaySetState(): RelaySetState? = null

    /**
     * RELAY-HEALTH: saves the relay set with commit() (only on a swap, never on a timer: FD-5); null clears it. The file
     * is app scope (a network property, not a wallet's) and Destroy deletes it with every other preferences file.
     */
    fun saveRelaySetState(state: RelaySetState?) = Unit

    /**
     * A pending inbound OPEN ("free NOSTR from message #1") contact request (#224). When a NIP-17 DM
     * arrives from a NOSTR pubkey we don't recognise AND it is a ZMSG v4 INIT, the sender is claiming a
     * Zcash identity we've never met. We do NOT auto-trust it — the gift-wrap proves only which NOSTR
     * key sent it, NOT that that key owns [senderAddress] — so we hold it here for the user to accept
     * or reject (manual TOFU gate). All fields except [senderNostrPubkeyHex]/[eventId] are
     * attacker-controlled and must be treated as untrusted until the user accepts.
     */
    data class MessageRequest(
        val senderNostrPubkeyHex: String, // authenticated by the gift wrap — who actually sent it
        val senderAddress: String,        // CLAIMED Zcash address (from the INIT body — UNVERIFIED)
        val relayUrl: String,             // relay to reply on once accepted
        val firstMessage: String,         // the INIT's inner plaintext (shown as a preview)
        val timestampMillis: Long,
        val eventId: String,              // gift-wrap event id (for completeness/audit)
        // STABLE cross-device rumor id of the first message — used as its ChatMessage id on accept so a
        // reaction/reply the sender attaches correlates across both devices (matches observeNostrInbound).
        val rumorId: String = "",
    )

    /** Persist an inbound OPEN contact [request], keyed by sender NOSTR pubkey (one pending per pubkey;
     *  a newer INIT from the same pubkey replaces the older). Bounded: a new sender over the cap throws
     *  [MessageRequestStoreFullException] and nothing already stored is evicted (ZC-1404). */
    fun addMessageRequest(request: MessageRequest)

    /** ZC-1404: the request store is full; the new request was not stored (and must not be marked seen). */
    class MessageRequestStoreFullException : IllegalStateException("message request store is full")

    /** All pending inbound OPEN contact requests, newest first. */
    fun getMessageRequests(): List<MessageRequest>

    /** Drop the pending request from [senderNostrPubkeyHex] (on accept or reject). */
    fun removeMessageRequest(senderNostrPubkeyHex: String)

    /** True iff the user previously REJECTED+blocked this NOSTR pubkey — its future INITs are dropped
     *  silently instead of resurfacing as a request (anti-nag / anti-spam). */
    fun isNostrPubkeyBlocked(pubkeyHex: String): Boolean

    /** Block [pubkeyHex] so its future unsolicited INITs are dropped. Bounded (LRU). */
    fun blockNostrPubkey(pubkeyHex: String)

    /**
     * ZC-1466: lift the block on [pubkeyHex] (the user's own scan or paste bound it, or Restore), and take its request off
     * the Declined list. A key that is not blocked is left as it is.
     */
    fun unblockNostrPubkey(pubkeyHex: String) = Unit

    /**
     * ZC-1464: keep a declined [request] for the Declined requests list (newest first, at most
     * [MAX_DECLINED_REQUESTS]; the oldest goes first). A second decline of the same key replaces its entry. The list holds
     * message text, so the destroy and reset wipe ([clearAll]) empties it. Blocking the key is the caller's step.
     */
    fun declineMessageRequest(request: MessageRequest) = Unit

    /** ZC-1464: the Declined requests list, newest decline first. */
    fun getDeclinedMessageRequests(): List<MessageRequest> = emptyList()

    /**
     * ZC-1464 Restore: the declined request from [senderNostrPubkeyHex] goes back to the requests inbox, its key is
     * unblocked and it leaves the Declined list. Returns the restored request, or null when none was listed or the inbox
     * is full (then nothing changes).
     */
    fun restoreDeclinedMessageRequest(senderNostrPubkeyHex: String): MessageRequest? = null

    /**
     * ZC-1464 (sender side): [peerAddress] declined our chat request (a ZRCPT|DECLINED from its bound relay key), until
     * a ZRCPT|RESTORED from that key clears it. The composer reads it.
     */
    fun isPeerRequestDeclined(peerAddress: String): Boolean = false

    fun setPeerRequestDeclined(
        peerAddress: String,
        declined: Boolean,
    ) = Unit

    /**
     * ZC-1465: an authenticated relay rumor (any kind, a read receipt included) came from [peerAddress]'s bound relay
     * key, so the contact has our chat and the Open chat-request hint is off.
     */
    fun wasPeerHeardFrom(peerAddress: String): Boolean = false

    fun markPeerHeardFrom(peerAddress: String) = Unit

    /**
     * #201 anti-flap: has this on-chain KEX/KEXACK transaction ALREADY been processed? A wallet
     * re-scans its whole shielded history on every sync, so without per-txid dedup an OLD KEX (from a
     * peer that has since rotated its key / reinstalled) is re-handled forever — each pass sees a key
     * that differs from the currently-stored one, fires a false "PEER KEY CHANGED", clears the
     * KEXACK-paid guard, and re-sends a KEXACK. That churn perpetually locks the single spendable note
     * (every on-chain send then fails "Insufficient balance (have 0)") and spams a false key-change
     * warning. Deduping by txid makes each KEX tx processed exactly once → the churn becomes one-time.
     */
    fun hasProcessedKexTx(txId: String): Boolean

    /** Record KEX/KEXACK [txId] as processed. Bounded (LRU). */
    fun markKexTxProcessed(txId: String)

    /**
     * KI-24 part A: [hasProcessedKexTx], or a KEX mined at or below the store's height floor. [minedHeight] is null for
     * a mempool KEX, which the floor never covers. The floor rises when entries leave the bounded set, to the highest
     * evicted mined height but never above the fully scanned height at that moment, so a KEX an earlier pass has not
     * seen is still handled.
     */
    fun hasProcessedKexTx(
        txId: String,
        minedHeight: Long?,
    ): Boolean = hasProcessedKexTx(txId)

    /**
     * KI-24 part A: [markKexTxProcessed] with the mined height, and [scannedTip] (the fully scanned height, null when
     * unknown) for the floor. A txid already recorded without a height gets it (entries written before KI-24).
     */
    fun markKexTxProcessed(
        txId: String,
        minedHeight: Long?,
        scannedTip: Long?,
    ) = markKexTxProcessed(txId)

    /**
     * KI-24 fixup G-KI24-2: the history pass's report for the processed-KEX floor. [lowestUnrecordedOfferHeight] is the
     * lowest mined height of an inbound key offer the pass read that the store has not recorded (null for none): the
     * floor stays below it. Only a [passComplete] pass (the whole history, the same transactions as the pass before;
     * ZC-074: RestoreKexPolicy.isKexFloorPassComplete, with an unread inbound memo reported as an offer at its height)
     * lets the floor rise again, never above [passTopHeight] (the highest mined height it read).
     */
    fun limitKexFloor(
        passComplete: Boolean,
        passTopHeight: Long?,
        lowestUnrecordedOfferHeight: Long?,
    ) {}

    /**
     * D2 — wallet-wide budget on AUTOMATIC paid first-contact KEXACKs. Each on-chain KEXACK costs
     * real ZEC (fee + note), and the send is triggered automatically by an INBOUND KEX — so without
     * a global cap an attacker spraying fresh-key KEXs from many addresses could drain the wallet
     * one ack at a time. Consumes one unit of a rolling-window budget: returns true (recording the
     * spend timestamp) iff fewer than [maxPerWindow] acks were consumed within the [windowMillis]
     * before [nowMillis]; returns false WITHOUT recording when exhausted — the caller must not
     * spend, and must not mark the ack as sent (so it retries once the budget frees).
     */
    fun tryConsumeKexAckBudget(nowMillis: Long, maxPerWindow: Int, windowMillis: Long): Boolean

    /**
     * Has this ZBOOT signature already been processed? Backed by a DEDICATED bounded set, kept
     * SEPARATE from the KEX/KEXACK txid set on purpose: ZBOOT dedup keys churn fast (a distinct
     * signature per rotation / re-scan), and when they shared the KEX set they evicted genuine KEX
     * txids from its FIFO — a later history re-scan then re-processed a superseded OLD-key KEX and
     * fired a false "PEER KEY CHANGED". Isolating ZBOOT churn keeps each KEX tx processed exactly
     * once (#201) while ZBOOT re-adoption stays protected by its own signed-epoch monotonicity guard.
     */
    fun hasProcessedBootSig(signature: String): Boolean

    /** Record ZBOOT [signature] as processed. Bounded (LRU), own store. */
    fun markBootSigProcessed(signature: String)

    /**
     * #205 — record [address] as a representation of OUR OWN wallet address. A single wallet can
     * present several valid unified-address strings; registering each one we observe (the chat
     * identity, the account's rotating Receive address) lets [isSelfAddress] recognise all of
     * them. Idempotent, persisted across process death. No-op on blank input. P2g: a saved chat
     * identity is kept for good; other addresses go to a capped list that drops the oldest first.
     */
    fun registerSelfAddress(address: String)

    /**
     * #205 — true if [address] is any known representation of our own wallet address. Compares by
     * address hash so it tolerates the diversifier/derivation drift that breaks raw `==` self
     * checks (self-message filtering, am-I-kicked, group creator). Returns false for blank input.
     */
    fun isSelfAddress(address: String): Boolean

    /** Peer's preferred NOSTR relay (from ZBOOT). */
    fun getPeerNostrRelay(peerAddress: String): String?

    fun setPeerNostrRelay(peerAddress: String, relayUrl: String?)

    /**
     * Highest ZBOOT rotation epoch we've already ADOPTED for this peer (#225 hardening). A ZBOOT whose
     * signed epoch is strictly LOWER than this is a stale/replayed handshake (e.g. an older on-chain
     * ZBOOT re-scanned from history) and MUST NOT be re-adopted — otherwise it could downgrade the live
     * pubkey back to a dead one. Defaults to 0 (also the epoch carried by legacy v2 ZBOOTs).
     */
    fun getPeerBootEpoch(peerAddress: String): Long

    fun setPeerBootEpoch(peerAddress: String, epoch: Long)

    /** True iff we've already published our own ZBOOT (NOSTR pubkey + relay) to this peer. */
    fun isOwnBootSent(peerAddress: String): Boolean

    /**
     * R5-2 — recording a sent bootstrap is written synchronously, and @return whether it reached the disk. It
     * is set immediately before the paid KEX it guards, so a KEX whose marker was not written must not be
     * sent: a restart would otherwise find the bootstrap re-armed and pay for a second one. Re-arming it
     * (false) is written in the background and always reports true: losing a re-arm can only mean NOT
     * paying again.
     */
    fun setOwnBootSent(peerAddress: String, sent: Boolean): Boolean

    /**
     * The NOSTR pubkey we last delivered to [peerAddress] via a signed ZBOOT (the sequenced
     * NOSTR-identity handshake that follows KEX/KEXACK). Used for idempotency: skip re-sending the
     * same identity (avoids on-chain drain + a ZBOOT ping-pong), but DO re-send if our identity
     * rotates (pubkey differs). Null = never sent.
     */
    fun getSentNostrBootPubkey(peerAddress: String): String?

    /**
     * R5-2 — recording a delivered identity is written synchronously, and @return whether it reached the disk.
     * The ZBOOT path reads it to decide whether our identity still has to be paid on-chain, so a ZBOOT whose
     * marker was not written must not be paid for. Re-arming it (null) is written in the background and
     * always reports true: losing a re-arm can only mean NOT paying again.
     */
    fun setSentNostrBootPubkey(peerAddress: String, pubkeyHex: String?): Boolean

    /**
     * P2b — the durable record of a paid automatic chat setup (see
     * co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord): `<identity>|<epoch millis>` per
     * slot, or null. It survives restarts so the same setup is not paid again within the cooldown.
     */
    fun getHandshakePaidRecord(slot: String): String? = null

    /**
     * R4-1 — @return whether the write reached the disk. This record is the only thing that stops the same
     * setup being paid again inside the cooldown after a restart, so an attempt whose record was not
     * written must not pay.
     */
    fun setHandshakePaidRecord(slot: String, record: String?): Boolean = false

    /**
     * R9-2 (REVIEW_R8 F4) — the contacts owed our KEX: the key exchange the user confirmed with a first message
     * (PaidAction.FIRST_MESSAGE quotes it), not yet SENT or MAYBE_SENT. It survives restarts so a KEX interrupted by
     * a closed chat screen or the end of the process is sent later under that same confirmation.
     */
    fun getKexOwedPeers(): Set<String> = emptySet()

    /** R9-2 — whether our KEX to [peerAddress] is still owed (see [getKexOwedPeers]). */
    fun isKexOwed(peerAddress: String): Boolean = false

    /**
     * R9-2 — record ([owed] true) or clear the owed KEX to [peerAddress]. @return whether the write reached the disk
     * (a mark that was not written is only lost after a restart; this process still sends the KEX it owes).
     */
    fun setKexOwed(peerAddress: String, owed: Boolean): Boolean = false

    /**
     * T1b-1 (fix-all 2.13.0) — the account that confirmed the KEX owed to [peerAddress] (its UUID in hex, as the chat
     * identity is saved per account), or null when none is recorded: a mark written before T1b-1 (test builds only),
     * which belongs to the ZChat account. Only that account pays the owed KEX; another account neither pays nor clears
     * it. Cleared with the mark ([setKexOwed] false).
     */
    fun getKexOwedAccount(peerAddress: String): String? = null

    /** T1b-1 — record ([accountKey]) or clear (null) the account of [getKexOwedAccount]. @return whether it reached the disk. */
    fun setKexOwedAccount(
        peerAddress: String,
        accountKey: String?,
    ): Boolean = false

    /**
     * ZC-285 (fix-all 2.13.0) — why our KEX to [peerAddress] is owed: null for a first message (R9-2; every mark written
     * before ZC-285, and every 2.12.4 install, has none), [KEX_OWED_REASON_RESET] for a confirmed Reset encryption, or
     * [KEX_OWED_REASON_RECONNECT] for a confirmed key-change reconnect (ZC-300). Such a KEX is quoted alone, so it is paid
     * without the first-message plan check (M2). Cleared with the mark ([setKexOwed] false); never listed by
     * [getKexOwedPeers].
     */
    fun getKexOwedReason(peerAddress: String): String? = null

    /**
     * ZC-285 — record ([reason]) or clear (null) the reason of [getKexOwedReason]. @return whether it reached the disk; a
     * store without it reports false, so a Reset or reconnect that needs it stops before anything is changed.
     */
    fun setKexOwedReason(
        peerAddress: String,
        reason: String?,
    ): Boolean = false

    /** ZC-1272 — the legs the one price question of this chat covered (QuotedChatSetup.encode), or null. */
    fun getQuotedChatSetup(peerAddress: String): String? = null

    /** ZC-1334 — the contacts with a [getQuotedChatSetup] record: a block pays the key exchange such a question covered. */
    fun getQuotedChatSetupPeers(): Set<String> = emptySet()

    /** ZC-1272 — write or (null) clear [getQuotedChatSetup]. @return whether it reached the disk. */
    fun setQuotedChatSetup(
        peerAddress: String,
        encoded: String?,
    ): Boolean = false

    /**
     * Round 10b R10B-1 (F-R9C-1) — whether a first-message question the user confirmed for [peerAddress] quoted our ZBOOT
     * as a third on-chain transaction (the contact's relay key was unknown). In a Shielded chat that allowance is the only
     * thing that lets the ZBOOT after the contact's KEXACK go on-chain; the ZBOOT that pays consumes it.
     */
    fun isZbootQuoted(peerAddress: String): Boolean = false

    /**
     * Round 10b R10B-1 — record or consume the allowance of [isZbootQuoted]. @return whether the write reached the disk.
     * ZC-534: removing the allowance ([quoted] false) removes the owed mark of [isZbootOwed] in the same write.
     */
    fun setZbootQuoted(peerAddress: String, quoted: Boolean): Boolean = false

    /**
     * ZC-1390 — the account that confirmed the question behind the allowance of [isZbootQuoted] (its UUID in hex,
     * GetDefaultUnifiedAddressUseCase.accountKeyOf), or null when none is recorded: an allowance an earlier 2.13.0 test
     * build wrote (2.12.4 has no allowance key). Only that account pays the ZBOOT. Cleared with the allowance
     * ([setZbootQuoted] false).
     */
    fun getZbootQuotedAccount(peerAddress: String): String? = null

    /**
     * ZC-1390 — record ([accountKey]) or clear (null) [getZbootQuotedAccount]. @return whether it reached the disk. A store
     * without it (a test store) keeps no account and reports success: its allowance then reads as one with none recorded.
     */
    fun setZbootQuotedAccount(
        peerAddress: String,
        accountKey: String?,
    ): Boolean = true

    /**
     * ZC-661: restore the original confirmed allowance and its owed mark in one checked commit. ZC-1432: with the account
     * ([accountKey], [getZbootQuotedAccount]) it named before it was used up (null: none recorded).
     */
    fun restoreQuotedZbootAsOwed(
        peerAddress: String,
        accountKey: String?,
    ): Boolean = false

    /**
     * ZC-534 — whether the ZBOOT a confirmed question quoted for [peerAddress] is still owed: an attempt provably sent
     * nothing and put its allowance back ([isZbootQuoted]), and is waiting (for a block, or for the next process after a
     * kill). While the mark and the allowance both stand, the delivered-identity marker ([getSentNostrBootPubkey]) may
     * name an identity that was never sent, and the per-block retry pays the ZBOOT once on that allowance. The mark
     * never outlives its allowance ([setZbootQuoted] false removes both). A store without it reports "not owed".
     */
    fun isZbootOwed(peerAddress: String): Boolean = false

    /** ZC-534 — record ([owed] true) or clear the mark of [isZbootOwed]. @return whether the write reached the disk. */
    fun setZbootOwed(
        peerAddress: String,
        owed: Boolean,
    ): Boolean = false

    /**
     * ZC-1352 — the user deleted the never-sent first message to [peerAddress] with nothing else in the chat: no KEX or
     * ZBOOT goes to that contact, by any trigger, until the user sends again or the contact writes first.
     */
    fun isAutomaticSetupWithdrawn(peerAddress: String): Boolean = false

    /** ZC-1352 — record or clear the mark of [isAutomaticSetupWithdrawn]. @return whether the write reached the disk. */
    fun setAutomaticSetupWithdrawn(
        peerAddress: String,
        withdrawn: Boolean,
    ): Boolean = false

    /** ZC-534 — the contacts whose quoted ZBOOT is marked owed ([isZbootOwed]). */
    fun getZbootOwedPeers(): Set<String> = emptySet()

    /**
     * R3-4 — the paid GROUP_ACCEPTs held for the handshake-spend consent, by group id (each an opaque record
     * the ChatViewModel writes). They survive an app kill so the accept can be offered again; removed when the
     * accept is claimed for payment or the group is deleted.
     */
    fun getHeldGroupAccepts(): Map<String, String> = emptyMap()

    /**
     * R4-1 — @return whether the write reached the disk. The REMOVAL is what keeps a killed process from
     * restoring an accept it already paid for, so a caller that is about to pay must not proceed on false.
     * B3: a removal is true only when every stored copy is gone (the encrypted row and any plaintext copy an
     * earlier version left behind), since any copy left would be loaded again by a later start.
     */
    fun setHeldGroupAccept(groupId: String, record: String?): Boolean = false

    /**
     * Round 8 D4 (DECISIONS_NIGHT §1) — the "I joined" reply (a record in the [setHeldGroupAccept] form) of a group
     * invite from an address we have no chat with, while the invite waits for the user's Join. Null when nothing
     * waits. A reply stored here is never sent, free or paid, and never offered to the consent question; Join moves it
     * to the normal accept path, and deleting the group drops it.
     */
    fun getGroupInviteNeedsJoin(groupId: String): String? = null

    /** Round 8 D4 — @return whether the change reached the disk. A mark that was not saved must store nothing else. */
    fun setGroupInviteNeedsJoin(groupId: String, record: String?): Boolean = false

    /**
     * ZC-420 — the mark of a free-first relay copy that only a public relay took ("<rumorId>|<markedAtMs>|<extra>"), by its
     * slot (RelayCopySlots), or null. Kept encrypted in the held-reply store; read and written only by the foreground
     * service's adapter for NostrChatBridge, never by a view model.
     */
    fun getRelayCopyMark(slot: String): String? = null

    /** ZC-420 — writes [value] with commit(), or removes the mark (null) with apply(); @return whether it reached the disk. */
    fun setRelayCopyMark(
        slot: String,
        value: String?,
    ): Boolean = false

    /** ZC-420 — every slot that holds a relay-copy mark. */
    fun getRelayCopyMarkSlots(): Set<String> = emptySet()

    /**
     * The peer E2E pubkey for which WE received an on-chain KEX from this peer — i.e. we are the
     * RESPONDER for that handshake. Set ONLY in the received-KEX path (never on our own outgoing KEX),
     * so it is the one reliable durable "we are the responder" signal (the kexTxId/kexAckTxId markers
     * are set on BOTH directions and so can't distinguish role). Null = we never received a KEX (we
     * either initiated or there's no chat). Used to gate the responder-side KEXACK retry.
     */
    fun getReceivedKexPubkey(peerAddress: String): String?

    fun setReceivedKexPubkey(peerAddress: String, pubkeyHex: String?)

    /**
     * ZC-599 (FIRST-CONTACT-SPEC 5.4 item 4, FC-2a) — the conversation id of the key exchange [getReceivedKexPubkey]
     * records, kept beside it so every answer to it (the automatic one, Accept, the retry) goes out under that id. Null:
     * none kept (a request an earlier build received; the history pass fills it in where it can).
     */
    fun getReceivedKexConvId(peerAddress: String): String?

    fun setReceivedKexConvId(
        peerAddress: String,
        convId: String?,
    )

    /**
     * R2-2 (a) — the E2E key a first-contact KEX offered, from an address we have no chat with, while it waits
     * for the user to accept the chat. Null when nothing waits. While it is set no automatic handshake (KEXACK,
     * KEX, ZBOOT) is paid towards that address; Accept clears it and pays the one KEXACK.
     */
    fun getInboundKexAwaitingAccept(peerAddress: String): String? = null

    /**
     * GPT-6 F3: true only when the change was written to disk. A hold that was not saved is lost on the next
     * restart, so the caller must not store anything that makes the address look like an answered peer.
     */
    fun setInboundKexAwaitingAccept(peerAddress: String, pubkeyHex: String?): Boolean = false

    /**
     * ZC-1017 — the decision on the Tunnel offer of a paid key exchange held in an Open chat (OpenChatTunnelOffer:
     * "kept|<key>" or "accepted|<key>"), or null. Written with a checked commit(): true only when it reached the disk.
     */
    fun getOpenChatKexOffer(peerAddress: String): String? = null

    fun setOpenChatKexOffer(peerAddress: String, record: String?): Boolean = false

    /**
     * The peer E2E pubkey for which we have SUCCESSFULLY sent a KEXACK. Durable de-dupe so a KEXACK
     * (a ~1000-zatoshi on-chain spend) is paid for at most ONCE per key and is NOT re-sent on every
     * cold app start — the prior in-memory-only guard re-drained on process death. A FAILED ack is not
     * recorded, so it still retries (Tunnel deadlock recovery preserved). Cleared on key change.
     */
    fun getSentKexAckPubkey(peerAddress: String): String?

    /**
     * R5-2 — recording an ack is written synchronously, and @return whether it reached the disk. This marker
     * is the ONLY durable record that stops the next chat open paying for the same KEXACK, so an ack whose
     * marker was not written must not be paid for. Re-arming it (null) is written in the background and
     * always reports true: losing a re-arm can only mean NOT paying again.
     */
    fun setSentKexAckPubkey(peerAddress: String, pubkeyHex: String?): Boolean

    /**
     * ZC-660 — the contact's key the user adopted with "Accept the new key" (`acceptedkey:<peer>`), or null. Written by
     * the accept before the remembered key's removal; removed by [clearE2EKeys] (a Reset), kept by [clearE2EPeerState]
     * (the accept's own wipe). Absent on a phone upgraded from 2.12.4 (the rule it drives is then off).
     */
    fun getAcceptedKey(peerAddress: String): String? = null

    /**
     * ZC-739-R3 — the refused key an "Accept the new key" wrote its quoted owed key exchange for (`acceptowed:<peer>`), or
     * null. "Not their key" for exactly that key drops that owed mark; a reconnect owed for any other reason stays.
     */
    fun getAcceptOwedKey(peerAddress: String): String? = null

    /** ZC-739-R3 — writes [getAcceptOwedKey] with commit() (null removes it); @return whether it reached the disk. */
    fun setAcceptOwedKey(
        peerAddress: String,
        publicKey: String?,
    ): Boolean = false

    /** ZC-660 — writes [getAcceptedKey] with commit(); @return whether it reached the disk. */
    fun setAcceptedKey(
        peerAddress: String,
        publicKey: String?,
    ): Boolean = false

    /** True the first time the user opens the app and we should show the 3-mode onboarding. */
    fun hasSeenModeIntro(): Boolean
    fun setHasSeenModeIntro(seen: Boolean)

    /**
     * True once the one-off rename of the old ZChat Team contact entry has been made (TeamContactMigration).
     * It is a migration, not a rule: a label the user then removes stays removed.
     */
    fun hasRenamedOldTeamContact(): Boolean

    fun setRenamedOldTeamContact()

    /** True once the user has seen the security note for [mode] on [peerAddress]. One-shot per (peer, mode). */
    fun hasSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode): Boolean
    fun setSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode)

    // ==========================================
    // DESTROY / REMOTE KILL SETTINGS
    // ==========================================

    /**
     * Set the destroy PIN (stored as hash for security). suspend — PBKDF2 runs on Dispatchers.Default.
     */
    suspend fun setDestroyPin(pin: String)

    /**
     * Verify the destroy PIN by comparing hash. suspend — PBKDF2 runs on Dispatchers.Default.
     * @return true if the provided PIN matches the stored hash
     */
    suspend fun verifyDestroyPin(pin: String): Boolean

    /**
     * Verify the destroy PIN with rate-limit-aware result. suspend — PBKDF2 runs on Dispatchers.Default.
     * Callers should prefer this over the boolean variant when they need to surface a lockout.
     */
    suspend fun verifyDestroyPinWithLockout(pin: String): DestroyPinVerifyResult

    /**
     * Check if destroy PIN is set.
     */
    fun hasDestroyPin(): Boolean

    /**
     * Clear the destroy PIN + its lockout counters (recovery path for a forgotten/mis-set PIN).
     * After this, [hasDestroyPin] is false so the next destroy attempt routes to fresh setup.
     * MUST be gated behind device-credential re-auth at the call site — it removes the
     * anti-accidental-wipe PIN, so it deserves the same factor that already protects app access
     * and Send Funds. Without this, a forgotten destroy PIN permanently bricks the wipe feature.
     */
    fun clearDestroyPin()

    /**
     * DESTROY-ADV-W (ZC-722; owner W-47, W-52): what Destroy All may keep, as saved with the Destroy PIN (today only
     * "contacts": the names and addresses of the chat contacts). Empty when no Destroy PIN is stored, when nothing was
     * chosen, and for any value this version does not know: Destroy All then destroys everything. Reading never writes.
     */
    fun getDestroyKeep(): Set<String> = emptySet()

    /** Saves what Destroy All may keep; nothing is written and false is answered when no Destroy PIN is stored. */
    fun setDestroyKeep(keep: Set<String>): Boolean = false

    /**
     * The Destroy PIN and the choice in ONE checked commit (the set-up's Save). False when the commit failed: the old
     * PIN and the old choice are then still there.
     */
    suspend fun setDestroySetup(
        pin: String,
        keep: Set<String>
    ): Boolean = false

    /**
     * Check if remote kill is enabled.
     */
    fun isRemoteKillEnabled(): Boolean

    /**
     * Enable/disable remote kill.
     */
    fun setRemoteKillEnabled(enabled: Boolean)

    /**
     * Verify the remote kill secret phrase by comparing hash. suspend — PBKDF2 runs on
     * Dispatchers.Default.
     * @param phrase The phrase to verify (will be hashed and compared)
     * @return true if the provided phrase matches the stored hash
     */
    suspend fun verifyRemoteKillPhrase(phrase: String): Boolean

    /**
     * Set the remote kill secret phrase. suspend — PBKDF2 runs on Dispatchers.Default.
     * NOTE: The phrase cannot be recovered after setting. User must remember it.
     */
    suspend fun setRemoteKillPhrase(phrase: String)

    /**
     * Check if remote kill phrase is set.
     */
    fun hasRemoteKillPhrase(): Boolean

    /**
     * Get the remote kill amount in Zatoshi.
     * Default is a unique amount like 1337 zatoshi.
     */
    fun getRemoteKillAmount(): Long

    /**
     * Set the remote kill amount.
     */
    fun setRemoteKillAmount(amountZatoshi: Long)

    /**
     * Block height at which remote kill was armed. 0 means "not recorded yet".
     *
     * A transaction mined at or below this height PREDATES arming and was therefore never a kill
     * signal, whatever its memo says. This matters because ZChat re-derives conversations from the
     * wallet's FULL on-chain history on every scan, so every historical memo is re-offered to the
     * kill check on every cold start. Without this floor a single matching transaction anywhere in
     * a user's past re-wipes their wallet on every launch — and it is the only guard that also
     * protects a user who ALREADY has such a transaction in their history when the fix ships,
     * because any purely "seen-set" guard starts empty on that upgrade.
     *
     * Despite the name, no build records this at arming (there is no reachable arming path). It is
     * written by ChatViewModel.anchorRemoteKillFloorIfUnset as maxOf(chain tip, highest mined height in
     * the wallet's history) the first time both are known on an install with remote kill enabled.
     */
    fun getRemoteKillArmedHeight(): Long

    /** Record the height at which remote kill was armed. See [getRemoteKillArmedHeight]. */
    fun setRemoteKillArmedHeight(height: Long)

    /**
     * Durable record of the kill signals this install has ALREADY acted on. The in-memory
     * `processedKillCheckTxIds` set in ChatViewModel is empty on every cold start, so it can only
     * ever be a within-session race guard, never a replay guard across launches. Only txids that
     * actually MATCHED are recorded here, so the set stays tiny.
     */
    fun hasProcessedKillSignalTx(txId: String): Boolean

    /** Record that a matching kill signal was acted on. Durable before the wipe starts. */
    fun markKillSignalTxProcessed(txId: String)

    /**
     * Decrypted-plaintext cache for ratcheted E2E messages, keyed by a hash of the ciphertext. The
     * symmetric per-message-key ratchet decrypts a given message EXACTLY ONCE (a consumed counter is
     * refused as a replay — NOT forward secrecy; keys re-derive from the stored root), but ZCHAT
     * re-derives conversations from the on-chain history on every sync, so a
     * message would be "decrypted" repeatedly — the 2nd+ attempt fails and the UI showed a confusing
     * "Encrypted message" placeholder. Rule: decrypt once, persist the plaintext here, and NEVER
     * re-run the ratchet on an already-seen ciphertext. Stored in EncryptedSharedPreferences (same
     * AES-256-GCM protection as the E2E keys). [getDecryptedText] returns null if never decrypted.
     */
    fun getDecryptedText(ciphertextHash: String): String?
    fun putDecryptedText(ciphertextHash: String, plaintext: String)

    /** Remove one cached decrypted value — used to scrub a poisoned entry (a raw "E2E1:" blob that an
     *  older build persisted as if it were plaintext) so it is never served back to the UI. */
    fun removeDecryptedText(ciphertextHash: String)

    /**
     * B3: stable first-seen timestamp (epoch millis) for an un-mined tx, anchoring the send countdown.
     * Persisted + first-writer-wins so it survives leaving/re-entering the chat (which recreates the
     * per-screen ChatViewModel) AND process death — the countdown no longer restarts from ~75s on re-entry.
     */
    fun getOrPutPendingTxFirstSeenMillis(txId: String, nowMillis: Long): Long
    fun clearPendingTxFirstSeen(txId: String)

    /**
     * Clear all preferences (used during destruction).
     */
    fun clearAll()

    /**
     * P2e: every store that belongs to the current wallet (the stores [clearAll] clears), so another
     * wallet's chats can be set aside and put back without being deleted.
     */
    fun walletScopedStores(): List<WalletScopedStore>

    /** P2e: re-read the in-memory copies of those stores after they were rewritten. */
    fun reloadWalletScopedStores()

    /**
     * C2 (B3 review items 9 and 10) — goes up each time the wallet-scoped stores are rewritten for another wallet
     * ([reloadWalletScopedStores]) or wiped ([clearAll]). In-memory state kept outside this store that belongs to
     * the wallet whose stores were live (the held group replies in ChatViewModel) is dropped when it changes.
     */
    fun walletScopeGeneration(): Long = 0L

    /**
     * Round 8 D11 (Grok CLI review, G14) — raises [walletScopeGeneration] BEFORE the wallet-scoped stores are exported
     * or rewritten for another wallet (ChatWalletOwnership.claimFor calls it, under [walletScopeLock], before it
     * touches any store). A holder that checks the generation and then writes, under the same lock, writes nothing
     * into the next wallet's stores.
     */
    fun beginWalletScopeChange() {}

    // ==========================================
    // CONVERSATION IDs (ZMSG v4)
    // ==========================================

    /**
     * Get the conversation ID for a peer address.
     * Returns null if no conversation ID is stored for this peer.
     */
    fun getConversationId(peerAddress: String): String?

    /**
     * Set the conversation ID for a peer address.
     * This is called when we initiate a new conversation.
     */
    fun setConversationId(peerAddress: String, convId: String)

    /**
     * Atomically get existing or create new conversation ID for a peer.
     * Thread-safe across all callers (VMs, services). Prevents race conditions
     * where two callers both see null and generate different IDs.
     * @return Pair of (convId, isNew) where isNew=true if a new ID was generated.
     */
    fun getOrCreateConversationId(peerAddress: String): Pair<String, Boolean>

    /**
     * R2-6 (c): the conversation id for a user MESSAGE send, and whether that message must be the v4 INIT
     * (the one that carries our address, so the recipient can tell who wrote).
     *
     * Creates the id when there is none and, in the same write, marks its INIT as NOT SENT. An existing id
     * whose INIT is still marked not sent comes back with `true` again. [markConversationInitSubmitted] clears
     * the mark when the INIT's submit starts. So a first message refused before submission (Orchard-only
     * wallet, shortfall, Send All short) is sent again as the INIT, also after a restart, and an INIT that may
     * have gone out is never sent again. Our KEX (R3-2) and the free controls (timer, mode change, Tunnel
     * ZBOOT, rotation announce — R4-4) use this call too, so an id one of them creates still owes the INIT.
     * @return Pair of (convId, sendAsInit)
     */
    fun getOrCreateConversationIdForMessage(peerAddress: String): Pair<String, Boolean>

    /** R2-6 (c): true when the next message to [peerAddress] must be the v4 INIT (see [getOrCreateConversationIdForMessage]). */
    fun needsConversationInit(peerAddress: String): Boolean

    /**
     * ZC-1480 item 3 — the relays carried our INIT (with our address) under [convId], the chain did not. The next
     * on-chain message under that id is the v4 INIT that carries our address ([getOrCreateConversationIdForMessage]
     * answers true for it; [getConversationIdOwedInitOnChain]); [markConversationInitSubmitted] clears it.
     * [needsConversationInit] does not change (the chat already exists, so New Chat still hands it over).
     */
    fun markConversationInitOwedOnChain(peerAddress: String, convId: String) = Unit

    /**
     * ZC-1480 item 3 — the id under which the next on-chain message to [peerAddress] owes the INIT
     * ([markConversationInitOwedOnChain]): the chat's own id while the mark is for it; null otherwise.
     */
    fun getConversationIdOwedInitOnChain(peerAddress: String): String? = null

    /**
     * R2-6 (c): the INIT for [convId] is about to be submitted; clear its not-sent mark. Throws when the write
     * fails, so the caller (the use case's onSubmitStarting) submits nothing rather than an unmarked INIT.
     */
    fun markConversationInitSubmitted(
        peerAddress: String,
        convId: String
    )

    /**
     * Get the peer address for a conversation ID.
     * Returns null if no peer is associated with this conversation ID.
     */
    fun getPeerByConversationId(convId: String): String?

    /**
     * Store a mapping from conversation ID to peer address.
     * This is called when we receive a new conversation INIT from someone else.
     *
     * FC-ID (ZC-892): an id's address is written once. When `conv:<id>` already holds another address nothing is
     * written in either direction ([setConversationId] keeps the same rule).
     */
    fun setConversationMapping(convId: String, peerAddress: String)

    /**
     * FC-ID (ZC-892) — the id `peer:<address>` holds when this phone made it for exactly [peerAddress] (its own-id
     * record `own:<id>` names that address), or null: a foreign id, or none. This phone's key exchange goes out only
     * under such an id.
     */
    fun ownConversationId(peerAddress: String): String?

    /** FC-ID — true only when the own-id record of [convId] names [peerAddress]. Every other id is foreign. */
    fun isOwnConversationId(
        convId: String,
        peerAddress: String,
    ): Boolean

    /**
     * FC-ID (5.3a item 3) — the id this phone's own key exchange to [peerAddress] goes out under, chosen where the send
     * is planned, before its price is asked: the address's own-made id, or a new one written in one commit with its
     * `peer:`, `conv:` and own-id entries and the "first message not sent" mark. The old `conv:` entry of a foreign id
     * stays, so memos the contact still sends under it are filed as before. Null when the write was not saved.
     */
    fun ownConversationIdForStart(peerAddress: String): String?

    /**
     * FC-ID (5.3a item 2) — at a restore, the id of this wallet's own send (an ordinary message, or a mined key
     * exchange) to [peerAddress]: when [convId] has no `conv:` entry yet, `conv:`, `peer:` (only when the address has
     * none) and the own-id record are written in one commit and true is returned. An id with an entry stays as it is.
     */
    fun restoreOwnConversationId(
        convId: String,
        peerAddress: String,
    ): Boolean

    /**
     * ZC-757 (k) round 10 (ZC-1145) — [restoreOwnConversationId]; with [asSendId] false (the send paid only a receiver form
     * inside [peerAddress]) no `peer:` entry is written, so the id never becomes the address's send id. Round 11 (ZC-1150):
     * nor later: a marker written in the same commit keeps every other write and read from making it that send id.
     */
    fun restoreOwnConversationId(
        convId: String,
        peerAddress: String,
        asSendId: Boolean,
    ): Boolean

    /**
     * Get all conversation mappings (convId -> peerAddress).
     */
    fun getAllConversationMappings(): Map<String, String>

    /**
     * Get all peer to convId mappings (peerAddress -> convId).
     * Used for validation and repair of bidirectional mappings.
     */
    fun getAllPeerToConvIdMappings(): Map<String, String>

    /**
     * Remove a conversation mapping by convId (deletes conv: key).
     * Used by repair logic to clean up orphaned entries with blank peers.
     */
    fun removeConversationMapping(convId: String)

    /**
     * Record that [repAddress] is an alternate UA representation of the SAME peer we canonically
     * track as [canonicalAddress]. Learned ONLY from a cryptographically-verified signal (e.g. a
     * GROUP_ACCEPT whose accepter_pub matched the E2E key we already hold for [canonicalAddress]),
     * so it never merges distinct peers. Fixes the #205 peer-side address-drift (#214): a wallet
     * emits multiple valid UA strings, and group roster/fan-out logic matches by exact string.
     */
    fun setPeerAddressAlias(repAddress: String, canonicalAddress: String)

    /**
     * Resolve [address] to its canonical peer representation if an alias was learned (see
     * [setPeerAddressAlias]); otherwise returns [address] unchanged. Self-mapping safe + idempotent.
     */
    fun resolvePeerAddress(address: String): String

    /**
     * Assert [address] is itself canonical (a live receive address): drop any alias mapping it
     * elsewhere. Used when a peer re-declares an address we already hold so it never canonicalizes away.
     */
    fun clearPeerAddressAlias(address: String)

    /**
     * Round 8 MA-V1 (member announce, design §8) — true when [address] is the canonical side of a learned alias, i.e.
     * the address a verified signed GROUP_ACCEPT declared (#219; [setPeerAddressAlias] has no other caller). A store
     * that keeps no aliases has none.
     */
    fun isPeerAddressAliasTarget(address: String): Boolean = false

    // ==========================================
    // PENDING MESSAGES (Persist across navigation)
    // ==========================================

    /**
     * Data class representing a pending message for persistence.
     * Only stores essential fields needed for display.
     */
    data class PendingMessageData(
        val id: String,
        val text: String,
        val timestampMillis: Long,
        val peerAddress: String,
        // Direction: false for inbound NOSTR rows (legacy on-chain pending rows are always outgoing).
        val isOutgoing: Boolean = true,
        // Still in flight? Outbound NOSTR rows persist as not-pending once SENT/FAILED; inbound = false.
        val isPending: Boolean = true,
        // MessageStatus enum name (e.g. "SENDING"/"SENT"/"FAILED"). Null = legacy on-chain pending.
        val status: String? = null,
        // Reply threading marker so a restored NOSTR reply still quotes its target.
        val replyToId: String? = null,
        // Cross-device quote preview text so a restored NOSTR reply renders its quote even when the
        // quoted message's local id can't be resolved on this device (R1-reply-quote-not-showing).
        val replyToPreview: String? = null,
        // Raw "ZFILE|…" memo for file/voice rows — lets the loader re-derive the file bubble fields
        // (hash/type/blurhash/viewOnce) via ZFILEMessage.parse instead of persisting each separately.
        val fileZfileContent: String? = null,
        // Inbound NOSTR payment-request (ZREQ) fields. A non-null amount marks the row as a payment
        // request so the loader can rebuild PaymentRequestInfo (amount + "Pay" affordance) after a
        // restart — without these it degraded to a plain text bubble. isPaid is derived from
        // paidRequestIds at load time (same as the live inbound builder), so it isn't persisted here.
        val paymentRequestAmountZatoshi: Long? = null,
        val paymentRequestReason: String? = null,
        // Value per output the user chose for an outgoing on-chain send, so Retry after a reload re-sends at
        // that amount (ChatMessage.sendAmountZatoshi). Null on legacy rows = the default amount.
        val sendAmountZatoshi: Long? = null,
        // P2a — true on every outgoing on-chain row this build writes. A row without it (an older build) was
        // persisted before its broadcast with no record of how far the submit got, so an interrupted one is
        // treated as possibly sent.
        val submitTracked: Boolean = false,
        // P2a — set (durably, before the SDK is asked to store or broadcast anything) when the on-chain submit
        // starts: the wall-clock time and the chain tip then known (null when no tip was known). Non-null
        // means Retry must first prove the send did not go out (SubmitAttemptProof).
        val submitStartedAtMillis: Long? = null,
        val submitAttemptHeight: Long? = null,
        // Exact SDK identities for reconciling this attempt; plaintext equality is not delivery proof.
        val submittedTxIds: List<String> = emptyList(),
        // R2-7 (c) — why a FAILED outgoing row failed (ChatMessage.failureReason), so a reopen still says why.
        val failureReason: String? = null,
        // F4 (fix-all 2.13.0, train T4) — the send attempt's settling, kept with the row (never a separate store):
        // the expiry height of each stored transaction, recorded before its submit (txid to height);
        val submittedExpiries: Map<String, Long> = emptyMap(),
        // the process that made the attempt, and a hash of the ZCHAT account it was made from (the K1 fast path);
        val attemptProcessId: String? = null,
        val attemptAccountHash: String? = null,
        // the first network height a history pass read after the row left the submit path (for a 2.12.4 row, or a
        // row an older 2.13.0 build wrote: the first height a pass of this build read, the cutover); written once;
        val releaseAnchorHeight: Long? = null,
        // the ids of earlier attempts a proven expiry replaced, and the scanned height that proved the last one.
        val supersededTxIds: List<String> = emptyList(),
        val resolvedNotSentAtHeight: Long? = null,
        // ZC-421 — a relay row's rumor: its second and its id, on the row before the rumor is published, so a Retry after a
        // restart makes the same rumor (the same id) again instead of a second message; a payment request keeps both for as
        // long as its row exists. Local to this build (2.12.4 reads named keys only); written only when not null.
        val relayRumorAtSec: Long? = null,
        val relayRumorId: String? = null,
        // ZC-1271 — the total the user accepted for a first message that waits for its funds to confirm, and the account
        // that accepted it. Null on every other row and on every row an older build wrote: a null is no price anyone accepted.
        val acceptedPriceZatoshi: Long? = null,
        val acceptedAccountKey: String? = null,
        // ZC-1306 — the mode the row was accepted in, when not Shielded (New Chat's Tunnel). Null reads as Shielded.
        val acceptedMode: String? = null,
    ) {
        /** ZC-1271: stands waiting for funds on purpose: accepted, in flight, and no submit of it ever started. */
        fun waitsForFunds(): Boolean =
            acceptedPriceZatoshi != null &&
                isOutgoing &&
                isPending &&
                status != "FAILED" &&
                submitStartedAtMillis == null &&
                submittedTxIds.isEmpty()

        companion object {
            /** Reads one stored row. A key an older build did not write keeps its default, so old rows load unchanged. */
            fun fromJson(value: String): PendingMessageData {
                val json = org.json.JSONObject(value)
                return PendingMessageData(
                    id = json.getString("id"),
                    text = json.getString("text"),
                    timestampMillis = json.getLong("timestampMillis"),
                    peerAddress = json.getString("peerAddress"),
                    // Legacy on-chain pending rows lack these keys → default to the
                    // outgoing/pending shape they were written with.
                    isOutgoing = json.optBoolean("isOutgoing", true),
                    isPending = json.optBoolean("isPending", true),
                    status = if (json.isNull("status")) null else json.getString("status"),
                    replyToId = if (json.isNull("replyToId")) null else json.getString("replyToId"),
                    replyToPreview =
                        if (json.has("replyToPreview") &&
                            !json.isNull("replyToPreview")
                        ) {
                            json.getString("replyToPreview")
                        } else {
                            null
                        },
                    fileZfileContent = if (json.isNull("fileZfileContent")) null else json.getString("fileZfileContent"),
                    paymentRequestAmountZatoshi =
                        if (json.has("paymentRequestAmountZatoshi") &&
                            !json.isNull("paymentRequestAmountZatoshi")
                        ) {
                            json.getLong("paymentRequestAmountZatoshi")
                        } else {
                            null
                        },
                    paymentRequestReason =
                        if (json.has("paymentRequestReason") &&
                            !json.isNull("paymentRequestReason")
                        ) {
                            json.getString("paymentRequestReason")
                        } else {
                            null
                        },
                    sendAmountZatoshi = json.optLongOrNull("sendAmountZatoshi"),
                    submitTracked = json.optBoolean("submitTracked", false),
                    submitStartedAtMillis = json.optLongOrNull("submitStartedAtMillis"),
                    submitAttemptHeight = json.optLongOrNull("submitAttemptHeight"),
                    submittedTxIds =
                        json
                            .optJSONArray("submittedTxIds")
                            ?.let { ids ->
                                List(ids.length()) { ids.getString(it) }
                            }.orEmpty(),
                    failureReason =
                        if (json.has("failureReason") && !json.isNull("failureReason")) {
                            json.getString("failureReason")
                        } else {
                            null
                        },
                    submittedExpiries =
                        json
                            .optJSONObject("submittedExpiries")
                            ?.let { expiries ->
                                expiries.keys().asSequence().associateWith { expiries.getLong(it) }
                            }.orEmpty(),
                    attemptProcessId = json.optStringOrNull("attemptProcessId"),
                    attemptAccountHash = json.optStringOrNull("attemptAccountHash"),
                    releaseAnchorHeight = json.optLongOrNull("releaseAnchorHeight"),
                    supersededTxIds =
                        json
                            .optJSONArray("supersededTxIds")
                            ?.let { ids -> List(ids.length()) { ids.getString(it) } }
                            .orEmpty(),
                    resolvedNotSentAtHeight = json.optLongOrNull("resolvedNotSentAtHeight"),
                    relayRumorAtSec = json.optLongOrNull("relayRumorAtSec"),
                    relayRumorId = json.optStringOrNull("relayRumorId"),
                    acceptedPriceZatoshi = json.optLongOrNull("acceptedPriceZatoshi"),
                    acceptedAccountKey = json.optStringOrNull("acceptedAccountKey"),
                    acceptedMode = json.optStringOrNull("acceptedMode"),
                )
            }

            /** Writes one row, every field. */
            fun toJson(message: PendingMessageData): String =
                org.json
                    .JSONObject()
                    .apply {
                        put("id", message.id)
                        put("text", message.text)
                        put("timestampMillis", message.timestampMillis)
                        put("peerAddress", message.peerAddress)
                        put("isOutgoing", message.isOutgoing)
                        put("isPending", message.isPending)
                        put("status", message.status)
                        put("replyToId", message.replyToId)
                        put("replyToPreview", message.replyToPreview)
                        put("fileZfileContent", message.fileZfileContent)
                        put("paymentRequestAmountZatoshi", message.paymentRequestAmountZatoshi)
                        put("paymentRequestReason", message.paymentRequestReason)
                        put("sendAmountZatoshi", message.sendAmountZatoshi)
                        put("submitTracked", message.submitTracked)
                        put("submitStartedAtMillis", message.submitStartedAtMillis)
                        put("submitAttemptHeight", message.submitAttemptHeight)
                        put("submittedTxIds", org.json.JSONArray(message.submittedTxIds))
                        put("failureReason", message.failureReason)
                        put("submittedExpiries", org.json.JSONObject(message.submittedExpiries))
                        put("attemptProcessId", message.attemptProcessId)
                        put("attemptAccountHash", message.attemptAccountHash)
                        put("releaseAnchorHeight", message.releaseAnchorHeight)
                        put("supersededTxIds", org.json.JSONArray(message.supersededTxIds))
                        put("resolvedNotSentAtHeight", message.resolvedNotSentAtHeight)
                        message.relayRumorAtSec?.let { put("relayRumorAtSec", it) }
                        message.relayRumorId?.let { put("relayRumorId", it) }
                        message.acceptedPriceZatoshi?.let { put("acceptedPriceZatoshi", it) }
                        message.acceptedAccountKey?.let { put("acceptedAccountKey", it) }
                        message.acceptedMode?.let { put("acceptedMode", it) }
                    }.toString()

            private fun org.json.JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

            private fun org.json.JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
        }
    }

    /**
     * Get all pending messages that haven't been confirmed yet.
     * These are messages sent by the user that are waiting for blockchain confirmation.
     */
    fun getPendingMessages(): List<PendingMessageData>

    /**
     * Add a pending message.
     * Called when user sends a message before it's confirmed on blockchain.
     */
    fun addPendingMessage(message: PendingMessageData)

    /**
     * Add or replace a pending message and write it to disk before returning (P2a: a submit marker must
     * survive the process being killed right after the broadcast starts).
     */
    fun commitPendingMessage(message: PendingMessageData) = addPendingMessage(message)

    /**
     * Remove a pending message by ID.
     * Called when the message is confirmed on blockchain.
     */
    fun removePendingMessage(messageId: String)

    /**
     * Remove multiple pending messages by their IDs.
     * Called during deduplication when messages are confirmed.
     */
    fun removePendingMessages(messageIds: Set<String>)

    /** A persisted NOSTR reaction (emoji + who reacted + when), keyed by the target message id. */
    data class PersistedReaction(val emoji: String, val senderAddress: String, val timestampMillis: Long)

    /**
     * Persist a NOSTR reaction against [targetId]. Idempotent per (emoji, senderAddress) so relay
     * replays / multi-relay publishes don't inflate the count. Bounded per target. Without this a
     * reaction applied only to the in-memory pendingMessages StateFlow is lost on the next reload.
     */
    fun addNostrReaction(targetId: String, emoji: String, senderAddress: String, timestampMillis: Long)

    /** Persisted NOSTR reactions for [targetId] (empty if none). Re-applied on message load. */
    fun getNostrReactions(targetId: String): List<PersistedReaction>

    /**
     * Clear all pending messages.
     * For cleanup purposes.
     */
    fun clearPendingMessages()

    // ----- Call log: local-only call-history entries (incoming/outgoing/missed/declined) -----
    data class CallLogMessageData(
        val id: String,
        val peerAddress: String,
        val timestampMillis: Long,
        val type: String, // ChatMessage CallLogType.name
        val isVideo: Boolean,
        val durationSec: Long?, // null for missed / declined / no-answer
        val isOutgoing: Boolean,
    )

    fun getCallLogMessages(): List<CallLogMessageData>

    fun addCallLogMessage(message: CallLogMessageData)

    fun removeCallLogMessage(id: String)

    fun clearCallLogMessages()

    // ==========================================
    // NOTIFICATION PRIVACY
    // ==========================================

    /**
     * Get the current notification privacy level.
     * @return The notification privacy level, default is FULL_PREVIEW
     */
    fun getNotificationPrivacy(): NotificationPrivacy

    /**
     * Set the notification privacy level.
     * @param level The privacy level to set
     */
    fun setNotificationPrivacy(level: NotificationPrivacy)

    // ==========================================
    // MESSAGE DRAFTS (Auto-Save)
    // ==========================================

    /**
     * Get the draft message for a peer address.
     * @param peerAddress The peer's Zcash address
     * @return The draft text, or null if no draft exists
     */
    fun getDraft(peerAddress: String): String?

    /**
     * Save a draft message for a peer address.
     * @param peerAddress The peer's Zcash address
     * @param draft The draft text (empty string to clear)
     */
    fun setDraft(peerAddress: String, draft: String)

    /**
     * Clear the draft for a peer address.
     * Called when a message is successfully sent.
     */
    fun clearDraft(peerAddress: String)

    /**
     * Get all drafts (for showing "Draft" indicator in conversation list).
     * @return Map of peerAddress -> draft text
     */
    fun getAllDrafts(): Map<String, String>

    /**
     * Check if a draft exists for a peer address.
     */
    fun hasDraft(peerAddress: String): Boolean

    // ==========================================
    // E2E ENCRYPTION
    // ==========================================

    /**
     * Check if E2E encryption is enabled for a conversation.
     */
    fun isE2EEnabled(peerAddress: String): Boolean

    /**
     * Enable/disable E2E encryption for a conversation.
     */
    fun setE2EEnabled(peerAddress: String, enabled: Boolean)

    // getE2EPrivateKey / getE2EOurPublicKey / setE2EOurKeys (+ key version setter, provenance,
    // seed-index allocator) are inherited from E2EKeyGenStore — see interface KDoc above.

    /**
     * Get the peer's public key for E2E encryption.
     * @return Base64 encoded public key, or null if not received
     */
    fun getE2EPeerPublicKey(peerAddress: String): String?

    /**
     * Store the peer's public key for E2E encryption.
     * @param peerPublicKey Peer's public key (Base64)
     */
    fun setE2EPeerPublicKey(peerAddress: String, peerPublicKey: String)

    /**
     * True if the peer's E2E public key has changed since last acknowledged by the user.
     * Set during KEX handling when the incoming pubkey differs from the stored one.
     * Cleared when the user dismisses the key-changed banner.
     */
    fun isE2EKeyChanged(peerAddress: String): Boolean

    /**
     * ZC-545: clearing the flag ([changed] = false) does nothing while a refused key is remembered for the contact
     * ([getRefusedKeyOffer]): the user has not decided about that key yet. Only [takeRefusedKeyOffer],
     * [dismissRefusedKeyOffer], [clearE2EKeys] and [clearAll] take the flag down then.
     */
    fun setE2EKeyChanged(peerAddress: String, changed: Boolean)

    /**
     * ZC-545 (WAIVERS.md W-32) — the one key offer of a contact that was refused as a key change and is waiting for the
     * user's decision: the first verified, self-signed KEX or KEXACK with a changed key since the key-changed banner
     * went up. Nothing uses it before the user accepts it in the dialog that shows its safety number.
     *
     * @param publicKey the whole offered key (Base64); an accept compares the whole string
     * @param memo the memo that carried it, kept so the accept can run the first-contact adoption on it
     * @param txId that memo's transaction id, when known
     * @param firstSeenMs when the key first reached this phone (kept when the same key arrives again)
     * @param laterOffers how many offers with a DIFFERENT key arrived since; they were not kept
     */
    data class RefusedKeyOffer(
        val publicKey: String,
        val memo: String,
        val txId: String?,
        val firstSeenMs: Long,
        val laterOffers: Int,
    )

    /**
     * ZC-545 — the remembered refused key of [peerAddress], or null. A record exists only while the key-changed flag is
     * up: one whose flag is down (a ZChat 2.12.4 install cleared it after a downgrade) is not returned and is removed.
     * The default (a store without the record) answers null, so an accept fails closed.
     */
    fun getRefusedKeyOffer(peerAddress: String): RefusedKeyOffer? = null

    /** ZC-545 — whether a refused key is remembered for [peerAddress] (its flag up). */
    fun hasRefusedKeyOffer(peerAddress: String): Boolean = false

    /**
     * ZC-545 — a refused offer of [publicKey] from [peerAddress]. With no record (or one whose flag is down) it
     * becomes the record and the key-changed flag is raised in the same commit. With a record of the same key, its
     * memo and txid are replaced (the newest memo of that key wins) and first-seen is kept. With a record of another
     * key only [RefusedKeyOffer.laterOffers] is raised; this offer's memo is dropped.
     * @return whether the write reached the disk; when it did not, nothing is changed.
     */
    fun rememberRefusedKeyOffer(
        peerAddress: String,
        publicKey: String,
        memo: String,
        txId: String?,
        nowMs: Long,
    ): Boolean = false

    /**
     * ZC-545 — the accept's last write: when the remembered key of [peerAddress] equals [publicKey] as a whole string,
     * remove the record and clear the key-changed flag in one commit and return the record; otherwise (no record,
     * another key, or the commit did not reach the disk) change nothing and return null.
     */
    fun takeRefusedKeyOffer(
        peerAddress: String,
        publicKey: String,
    ): RefusedKeyOffer? = null

    /**
     * ZC-545, ZC-702 — "Not their key": only when the remembered key of [peerAddress] equals the whole [publicKey],
     * remove that record and clear the key-changed flag in one commit.
     * @return whether a record was removed (false: none, another key, or an unconfirmed commit; nothing changed).
     */
    fun dismissRefusedKeyOffer(
        peerAddress: String,
        publicKey: String,
    ): Boolean = false

    /**
     * True once the user has confirmed the peer's safety number out-of-band. Distinguishes a
     * "verified" conversation from one that is merely TOFU-encrypted (first-contact trust).
     * Cleared by callers at every peer-key-change site (each [setE2EKeyChanged] `true` call)
     * and by [clearE2EKeys] — NOT inside [setE2EKeyChanged] itself — because a key change
     * invalidates any prior out-of-band verification.
     */
    fun isE2EVerified(peerAddress: String): Boolean

    fun setE2EVerified(peerAddress: String, verified: Boolean)

    /**
     * Get the persistent state store for the symmetric per-message-key E2E ratchet (counters +
     * seen-counter sets; provides replay protection, NOT forward secrecy).
     * Backed by EncryptedSharedPreferences — survives app restart.
     */
    /** Store the KEX transaction ID for root key derivation context. */
    fun setE2EKexTxId(peerAddress: String, txId: String)
    fun getE2EKexTxId(peerAddress: String): String?

    /** Store the KEXACK transaction ID for root key derivation context. */
    fun setE2EKexAckTxId(peerAddress: String, txId: String)
    fun getE2EKexAckTxId(peerAddress: String): String?

    /** ZC-1583: drop the legacy KEX scalar (an own KEX that expired unmined must not stay in the root material). */
    fun removeE2EKexTxId(peerAddress: String)

    /** ZC-1583: drop the legacy KEXACK scalar (an own KEXACK that expired unmined must not stay in the root material). */
    fun removeE2EKexAckTxId(peerAddress: String)

    /**
     * Convergent KEX/KEXACK txid SETS for ratchet-root derivation (B1/B2 fix). Both devices observe the
     * SAME mined KEX/KEXACK txs for a conversation (own via SendTransaction scan, peer's via
     * ReceiveTransaction scan), so a SORTED set yields byte-identical root material on both sides — fixing
     * the last-writer-wins scalar divergence that broke OPEN→VAULT decrypt (dual-KEX pairs). The sets are
     * RECOMPUTED (replaced) from the chain scan each pass — never incrementally accumulated behind a
     * once-ever guard — so they stay a pure function of shared on-chain state: symmetric, rescan-idempotent,
     * reorg-self-healing, and they retroactively heal pre-update / dual-KEX / multi-KEX history.
     */
    fun setKexTxIds(peerAddress: String, txIds: Set<String>)
    fun getKexTxIds(peerAddress: String): Set<String>
    fun setKexAckTxIds(peerAddress: String, txIds: Set<String>)
    fun getKexAckTxIds(peerAddress: String): Set<String>

    /** Clear both convergent txid sets for a peer (e.g. on full E2E reset). The per-scan recompute
     *  repopulates them from chain state, so this is only a transient/clean-slate helper. */
    fun clearKexTxIds(peerAddress: String)

    /** Store the Quantum Shield PSK for a conversation (Base64-encoded 32 bytes). */
    fun setQuantumShieldPSK(peerAddress: String, pskBase64: String)
    fun getQuantumShieldPSK(peerAddress: String): String?

    /** Clear the Quantum Shield PSK and, in the same edit, its [getQuantumShieldBound] record. */
    fun clearQuantumShieldPSK(peerAddress: String)

    /**
     * C7: which key the Quantum Shield setup stored — true for the identity-bound key (both phones can show a
     * security code), false for the 2.12.4 key (no code). Written at setup before the PSK and cleared with it.
     * Null for a shield set up before this record existed.
     */
    fun setQuantumShieldBound(peerAddress: String, bound: Boolean)

    fun getQuantumShieldBound(peerAddress: String): Boolean?

    /** Store our Quantum Shield secret for a conversation (Base64-encoded, for QR display). */
    fun setQuantumShieldOurSecret(peerAddress: String, secretBase64: String)
    fun getQuantumShieldOurSecret(peerAddress: String): String?

    /**
     * D3-2: the Quantum Shield claim records ([QsClaimStore]), in the encrypted E2E store, each written with one
     * commit (true when it landed). `qs_claim_peer_` holds the peer's newest verified claim, `qs_claim_our_seq_` our
     * current claim (seq and body, written before it is published), `qs_claim_sent_seq_` the seq a relay acknowledged,
     * `qs_claim_setup_` the setup record (D3-3). [clearE2EKeys] removes all four; [clearQuantumShieldPSK] only the
     * setup record. Defaults for fakes: nothing stored.
     */
    fun setQsClaimPeer(peerAddress: String, record: String): Boolean = false

    fun getQsClaimPeer(peerAddress: String): String? = null

    fun setQsClaimOurSeq(peerAddress: String, record: String): Boolean = false

    fun getQsClaimOurSeq(peerAddress: String): String? = null

    fun setQsClaimSentSeq(peerAddress: String, seq: Long): Boolean = false

    fun getQsClaimSentSeq(peerAddress: String): Long = 0L

    fun setQsClaimSetup(peerAddress: String, record: String): Boolean = false

    fun getQsClaimSetup(peerAddress: String): String? = null

    /** D3-2: every peer whose conversation mode is TUNNEL or OPEN (the chats a Quantum Shield claim may go to). */
    fun getRelayModePeers(): List<String> = emptyList()

    fun getRatchetStateStore(): co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetStateStore

    /**
     * Check if E2E key exchange is complete (both keys available).
     */
    fun isE2EKeyExchangeComplete(peerAddress: String): Boolean

    /**
     * Clear E2E keys for a conversation. ZC-545: also the remembered refused key ([getRefusedKeyOffer]); a Reset
     * encryption starts over.
     */
    fun clearE2EKeys(peerAddress: String)

    /**
     * ZC-599 (FIRST-CONTACT-SPEC 5.4 item 5) — the asked record: the contact key this phone took on purpose for
     * [peerAddress] (an answer it asked for). Removed with the contact key; null: none.
     */
    fun getKexAskedKey(peerAddress: String): String?

    fun setKexAskedKey(
        peerAddress: String,
        key: String,
    )

    /**
     * ZC-634 (FC-5; FIRST-CONTACT-SPEC 5.5 item 2) — the one write of a contact key: [key] for [peerAddress], its claim
     * mark put when [claim] ("keyclaim:<address>", naming [key]; the verified flag cleared, a ZBOOT held for the same key
     * kept) or removed with its held ZBOOT, and the asked record put when [asked] (kept otherwise), in ONE commit of the
     * key store. A mark says the key came with no tap and no proof.
     * @return whether the commit was saved; when it was not, nothing is stored.
     */
    fun storePeerKey(
        peerAddress: String,
        key: String,
        claim: Boolean,
        asked: Boolean,
    ): Boolean

    /** ZC-634 — the key [peerAddress]'s claim mark names; null: no mark. A mark stands while its entry exists. */
    fun getKeyClaim(peerAddress: String): String?

    /**
     * ZC-634 (5.5 item 2) — put a claim mark on the contact key already stored for [peerAddress], only while that key is
     * [key] (the verified flag cleared), in one commit; no key is written. @return false when another key (or none) is
     * stored, or the commit was not saved.
     */
    fun markKeyClaim(
        peerAddress: String,
        key: String,
    ): Boolean

    /** ZC-634 (5.5 item 3) — the last ZBOOT held back while [peerAddress]'s mark stood (one slot); null: none or no mark. */
    fun getHeldClaimBoot(peerAddress: String): String?

    /**
     * ZC-634 (5.5 item 3) — keep [raw] as the held ZBOOT of [peerAddress]'s mark (the latest only, at most 1,024 characters).
     * @return false when no mark stands or the commit was not saved.
     */
    fun holdClaimedBoot(
        peerAddress: String,
        raw: String,
    ): Boolean

    /**
     * ZC-634 (5.5 item 6) — remove [peerAddress]'s mark with its held ZBOOT, and set the verified flag when [verified] (the
     * user's "Mark as verified"), in one commit under the key store's lock, and hand back the held ZBOOT read in that same
     * section. @return the held ZBOOT; "" when the mark held none; null when no mark stood or the commit was not saved.
     */
    fun releaseKeyClaim(
        peerAddress: String,
        verified: Boolean,
    ): String?

    /**
     * W-72 (ZC-847, ZC-852; SPEC_F_20261019 R2 and the store table) — [storePeerKey] with the pre-update mark: when
     * [legacy] the mark "legacykey:<address>" naming [key] is put; otherwise a mark that names [key] is kept and one that
     * names another key goes with its probe record. In the same ONE commit as the key. The default serves test fakes that
     * only know the four-argument form.
     */
    fun storePeerKey(
        peerAddress: String,
        key: String,
        claim: Boolean,
        asked: Boolean,
        legacy: Boolean,
    ): Boolean = storePeerKey(peerAddress, key, claim, asked)

    /**
     * W-72 (SPEC_F R1) — the key [peerAddress]'s pre-update mark names, only while it is the stored contact key (the mark
     * stands); null: no mark, or one that names another key. A mark says the key was stored before this build (or comes
     * from a memo mined at or below the upgrade height) and nothing has proven it since.
     */
    fun getLegacyKeyMark(peerAddress: String): String? = null

    /** W-72 (SPEC_F R3) — the last ZBOOT held back while [peerAddress]'s pre-update mark stood (one slot); null: none or no mark. */
    fun getHeldLegacyBoot(peerAddress: String): String? = null

    /**
     * W-72 (SPEC_F R3) — keep [raw] as the held ZBOOT of [peerAddress]'s pre-update mark (the latest only, at most 1,024
     * characters). @return false when no mark stands or the commit was not saved.
     */
    fun holdLegacyBoot(
        peerAddress: String,
        raw: String,
    ): Boolean = false

    /**
     * W-72 (SPEC_F R5, R7) — remove [peerAddress]'s pre-update mark with its probe record and held ZBOOT, and set the
     * verified flag when [verified] (the user's "Mark as verified"), in one commit; the handshake tick goes up.
     * @return false when no mark stood or the commit was not saved.
     */
    fun releaseLegacyKey(
        peerAddress: String,
        verified: Boolean,
    ): Boolean = false

    /** W-72 (SPEC_F R4, SPEC_DECISIONS D4) — the probe id of [peerAddress] while its pre-update mark stands; null: none. */
    fun getLegacyProbe(peerAddress: String): String? = null

    /** W-72 (R5) — the probe's sent transactions, one per line, while the probe stands; null: none recorded. */
    fun getLegacyProbeTxIds(peerAddress: String): String? = null

    /**
     * W-72 (R4, D4, D5) — the probe id of [peerAddress]: the one kept, or (the first time) a fresh id with no `conv:` or
     * own-id entry, written with its `conv:` entry and own-id record (`peer:` is left as it is), then kept in
     * "legacyprobe:<address>" until the mark goes. Null: no mark stands, or a write was not saved (nothing is probed).
     */
    fun startLegacyProbe(peerAddress: String): String? = null

    /** W-72 (R5) — record [txId], a transaction of the probe [convId] to [peerAddress]. false: no such probe, or not saved. */
    fun addLegacyProbeTx(
        peerAddress: String,
        convId: String,
        txId: String,
    ): Boolean = false

    /**
     * W-72 (R6, D4) — the contact's probe id this phone has not answered yet ("replyconv:<address>" in the conversation-id
     * store), while its `conv:` entry names [peerAddress]; null: none, or answered. Only on-chain ordinary sends use it.
     */
    fun getReplyConversationId(peerAddress: String): String? = null

    /**
     * W-72 (R6, D4) — the contact's probe [convId], read in a memo mined at [minedHeight], becomes the reply id, unanswered,
     * when there is none or the memo is newer than the one that set it. @return false when the write was not saved.
     */
    fun armReplyConversationId(
        peerAddress: String,
        convId: String,
        minedHeight: Long,
    ): Boolean = false

    /** W-72 (D4) — the reply id [convId] of [peerAddress] was answered (a send under it may have gone out). */
    fun answerReplyConversationId(
        peerAddress: String,
        convId: String,
    ): Boolean = false

    /**
     * W-72 (SPEC_F R5, R7; as ZC-634's releaseKeyClaim since c94569c8e) — [releaseLegacyKey] that hands back, from the same
     * section and commit, the ZBOOT the mark held: its text, "" when none was held, null when no mark stood or the commit
     * was not saved.
     */
    fun releaseLegacyKeyWithBoot(
        peerAddress: String,
        verified: Boolean,
    ): String? = null

    /**
     * W-72 (SPEC_DECISIONS D2) — whether this key store has finished one complete history read since it was made, emptied
     * (Reset, restore) or brought in. Until then every contact key stored for the first time gets a pre-update mark. Null:
     * a store that keeps no such record (the default, test fakes), which counts as read.
     */
    fun isLegacyHistoryRead(): Boolean? = true

    /** W-72 (D2) — record that this key store's history was read completely (the pass acted on every key offer). */
    fun setLegacyHistoryRead() {}

    /**
     * ZC-545 — everything [clearE2EKeys] removes for [peerAddress] EXCEPT our own key pair, its provenance and its
     * version, the key-changed flag and the remembered refused key. The accept of a contact's new key wipes with this:
     * the safety number the user compared is the number of the pair after the accept only while our key stays, and
     * the flag and the record go last ([takeRefusedKeyOffer]).
     *
     * The default serves test fakes that only know [clearE2EKeys]: it puts our key pair, its provenance and version
     * and the flag back around it (not atomic; a fake's record, if it has one, is its own business).
     */
    fun clearE2EPeerState(peerAddress: String) {
        val ourPublic = getE2EOurPublicKey(peerAddress)
        val ourPrivate = getE2EPrivateKey(peerAddress)
        val version = getE2EKeyVersion(peerAddress)
        val provenance = getE2EKeyProvenance(peerAddress)
        val flagged = isE2EKeyChanged(peerAddress)
        clearE2EKeys(peerAddress)
        if (ourPublic != null && ourPrivate != null) {
            setE2EOurKeys(peerAddress, ourPublic, ourPrivate)
            setE2EKeyVersion(peerAddress, version)
            if (provenance != null) setE2EKeyProvenance(peerAddress, provenance)
        }
        if (flagged) setE2EKeyChanged(peerAddress, true)
    }

    /**
     * Get the E2E key derivation version for a peer.
     * @return Key version (1 = legacy SHA-256, 2 = HKDF), defaults to 1 for backwards compatibility
     * (the setter is inherited from E2EKeyGenStore).
     */
    fun getE2EKeyVersion(peerAddress: String): Int

    // ==========================================
    // GROUP CHAT
    // ==========================================

    /**
     * Save group info (as JSON string).
     * @param groupId The unique group identifier
     * @param groupInfoJson JSON representation of GroupInfo
     */
    fun saveGroupInfo(groupId: String, groupInfoJson: String)

    /**
     * Get group info by ID.
     * @return JSON string of GroupInfo, or null if not found
     */
    fun getGroupInfo(groupId: String): String?

    /**
     * ZC-022 (KI-25 R8) — write several group records ([records]: group id to the JSON of its GroupInfo) in one editor
     * and one `commit()`: true only when they reached the disk. When the commit fails, the strings the store held before
     * are put back, so the phone goes on with the records it had. The default reports false, so a store that cannot
     * write durably never reports a record as saved.
     */
    fun saveGroupInfosDurably(records: Map<String, String>): Boolean = false

    /**
     * ZC-704 (KI-25 R19) — put what the group handlers wrote with `apply()` on the disk: one `commit()` on the
     * group-key store, the group-info store and the main file, in that order. True only when all three took it. Each
     * commit is a real one-key change: the counter under the reserved key `flush:seq` of that store (a String of
     * digits; a value that cannot be read counts as 0) grows by one. Nothing else reads that key; it is no group id
     * (a group id holds no colon) and no group key (those end in `_<epoch>`). Never throws: anything thrown reads as
     * refused. The default answers false, so a store that cannot flush never lets a kept change leave its list.
     */
    fun flushGroupStores(): Boolean = false

    /**
     * ZC-704 (KI-25 R19; narrows ZC-728) — one `commit()` on the group-key store, made after a rotated key was
     * written and before its epoch is: true when the commit was REFUSED (or anything was thrown). It commits the same
     * one-key change as [flushGroupStores]. It answers "failed" and not "saved" so that the default, false, reads "the
     * commit was taken": a store that does not know this method goes on adopting keys as before.
     */
    fun groupKeyStoreCommitFailed(): Boolean = false

    /**
     * Get all group IDs.
     */
    fun getAllGroupIds(): Set<String>

    /**
     * Delete a group.
     */
    fun deleteGroup(groupId: String)

    /**
     * Save group members (as JSON string).
     * @param groupId The group ID
     * @param membersJson JSON array of GroupMember objects
     */
    fun saveGroupMembers(groupId: String, membersJson: String)

    /**
     * B1 (GPT-6 Q4) — [saveGroupMembers] written synchronously with `commit()`: true only when the roster reached
     * the disk. For a roster write that is the only record stopping a payment from being made a second time
     * (the owed-key "repaired" stamp written before a paid re-send). The default reports false, so a store
     * that cannot write durably never lets that payment happen.
     */
    fun saveGroupMembersDurably(
        groupId: String,
        membersJson: String
    ): Boolean = false

    /**
     * ZC-690 — write the roster, its announce state and the membership's spend-consent cut in one commit.
     * Call under [withGroupLock]; false never authorizes a removal notice or announce.
     */
    fun saveGroupMembersDurably(
        groupId: String,
        membersJson: String,
        state: AnnounceState
    ): Boolean = false

    /**
     * Get group members.
     * @return JSON array string of GroupMember objects
     */
    fun getGroupMembers(groupId: String): String?

    /**
     * Save group key for encryption.
     * @param groupId The group ID
     * @param keyEpoch The key epoch
     * @param encryptedKey Base64 encoded encrypted group key
     */
    fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String)

    /**
     * ZC-778 — [saveGroupKey] for a key that is about to be SENT to other members. With [durable] it is one `commit()`
     * whose result is checked: it returns only when the key reached the disk and throws [java.io.IOException] when the
     * store refused the write, so nothing is sent under a key this phone may not hold after a restart. Without
     * [durable] it is [saveGroupKey]. The default refuses: a store with no durable write never lets that key go out.
     */
    fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String, durable: Boolean) {
        if (!durable) return saveGroupKey(groupId, keyEpoch, encryptedKey)
        throw java.io.IOException("this store has no durable group key write")
    }

    /**
     * Get group key for a specific epoch.
     * @return Base64 encoded encrypted group key
     */
    fun getGroupKey(groupId: String, keyEpoch: Int): String?

    /**
     * Get the current key epoch for a group.
     */
    fun getGroupKeyEpoch(groupId: String): Int

    /**
     * Set the current key epoch for a group.
     */
    fun setGroupKeyEpoch(groupId: String, epoch: Int)

    /**
     * ZC-778 — [setGroupKeyEpoch] with [durable]: one `commit()` whose result is checked. It returns only when the
     * number reached the disk and throws [java.io.IOException] when the store refused the write. Written after the key
     * it names ([saveGroupKey] with `durable`), never before it. Without [durable] it is [setGroupKeyEpoch].
     */
    fun setGroupKeyEpoch(groupId: String, epoch: Int, durable: Boolean) {
        if (!durable) return setGroupKeyEpoch(groupId, epoch)
        throw java.io.IOException("this store has no durable group key number write")
    }

    /**
     * D4 — the group's removal frontier: the epoch the most recent verified kick rotated INTO.
     * A removed member's retained key is always from BELOW this floor, so a message that decrypts
     * only under a pre-floor epoch needs verified authorship (see
     * [co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol.isSuspectedRemovedInjection]).
     * Default 0 = "no removal ever" → the rule is inert (back-compat for existing groups).
     */
    fun getGroupRemovalFloor(groupId: String): Int

    /**
     * Advance the removal frontier to [epoch]. MONOTONIC: a value not above the stored floor is
     * ignored, so an out-of-order/replayed kick can never LOWER the floor and re-open the window.
     */
    fun setGroupRemovalFloor(groupId: String, epoch: Int)

    /**
     * Round 9 R9-3 — advance the removal frontier to [epoch] (as [setGroupRemovalFloor]) and record, in the same write,
     * the height that removal counts from ([kickHeight]: the kick's mined height, or the height this phone had scanned
     * to when it acted on the removal; null = unknown). See
     * [co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol.recordRemovalKick].
     */
    fun setGroupRemovalFloor(groupId: String, epoch: Int, kickHeight: Long?) = setGroupRemovalFloor(groupId, epoch)

    /**
     * Round 9 R9-3 — the group's removal record (the epoch each recorded removal rotated into, to its height, null =
     * unknown). A floor stored by an older build has no record: empty, and every pre-floor key then counts as having
     * no known removal height (today's behaviour). See
     * [co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol.minedBeforeRemoval].
     */
    fun getGroupRemovalKickHeights(groupId: String): Map<Int, Long?> = emptyMap()

    /**
     * Did WE create this group (vs. being invited to it)? Persisted at creation so the admin/creator
     * role survives a self-address-representation change (the creatorAddress string stored in GroupInfo
     * can drift from our live address across reinstalls/SDK upgrades, which would otherwise make the
     * "is this me?" address comparison wrongly false and hide the admin kick/rotate UI).
     */
    fun isGroupSelfCreated(groupId: String): Boolean

    fun setGroupSelfCreated(groupId: String, created: Boolean)

    /**
     * HS-5 — true once we (the admin) have paid, or are paying, the grant for the GROUP_KEY_REQUEST
     * identified by [requestId] (its group plus the request's txid, or its NOSTR event id). The request
     * stays in the wallet's history for good and the history scan hands it to the grant path on every
     * pass; this durable record is what stops a rescan from paying the same grant again. A member's new
     * tap is a new transaction, hence a new id, and is answered again.
     */
    fun isGroupKeyRequestGranted(requestId: String): Boolean = false

    /**
     * Record ([granted] = true) or withdraw a grant record; withdrawn only when nothing was sent.
     *
     * R5-2 — @return whether the write reached the disk. This record is the ONLY thing that stops a rescan
     * paying the same grant again, so a grant whose record was not written must not be paid for.
     */
    fun setGroupKeyRequestGranted(requestId: String, granted: Boolean): Boolean = false

    /**
     * KI-23 (ZC-020) — how many paid deliveries of one group key to one member were attempted: 0 or 1 in 2.13.0.
     * [grantKey] is `GroupKeyGrantPolicy.grantKey` (group, canonical member, key number). It is set to 1 before the
     * submit of an automatic key grant (the answer to a member's key request) and of the creator's Resend key, and a
     * request for a key whose count is 1 or more is answered over the relays only. It says that a paid delivery was
     * attempted, not that one arrived. A store that does not answer says 0 and saves nothing, so nothing is paid.
     */
    fun getGroupKeyGrantPaidCount(grantKey: String): Int = 0

    /**
     * KI-23 — writes the count of [getGroupKeyGrantPaidCount] (0 removes it) with a checked commit().
     * @return whether the write reached the disk; a paid grant whose count was not written must not be submitted.
     */
    fun setGroupKeyGrantPaidCount(grantKey: String, count: Int): Boolean = false

    /**
     * KI-23 (ZC-020) — whether [tryConsumeGroupKeyGrantBudget] would say yes now. It reads only, so the decision to pay
     * can be taken (and the consent question raised) without using a unit. The default says no: nothing is paid.
     */
    fun groupKeyGrantBudgetAvailable(nowMillis: Long, maxPerWindow: Int, windowMillis: Long): Boolean = false

    /**
     * KI-23 (ZC-020) — the wallet-wide budget of AUTOMATIC paid key grants (the on-chain answer to a member's key
     * request), a copy of [tryConsumeKexAckBudget] with its own key and lock: true, and the time recorded with a
     * commit(), iff fewer than [maxPerWindow] units were used within [windowMillis] before [nowMillis]; false and
     * nothing recorded when it is used up or the write did not reach the disk. One unit per paid attempt sequence,
     * given back only by [giveBackGroupKeyGrantBudget]. It never applies to a payment the user tapped and was quoted for (Sync keys, Resend key), to
     * the owed-key pass, to the key messages after a removal or rotation, or to the sealed relay route.
     */
    fun tryConsumeGroupKeyGrantBudget(nowMillis: Long, maxPerWindow: Int, windowMillis: Long): Boolean = false

    /**
     * ZC-1251 — the unit [tryConsumeGroupKeyGrantBudget] recorded at [consumedAtMillis] goes back: its grant was above its
     * price and paid nothing. @return whether that reached the disk; on false the unit stays used, which pays nothing.
     */
    fun giveBackGroupKeyGrantBudget(consumedAtMillis: Long): Boolean = false

    // ==========================================
    // ROUND 8 MA1: GROUP MEMBER ANNOUNCE (MEMBER_ANNOUNCE_DESIGN.md v2 §5.0, §5.4, §6.0)
    // ==========================================
    // Nothing in the app calls these yet (MA2 / MA3 do). Every member has a default body, so a store that does not
    // keep announce records (the test fakes) reads "nothing stored" and reports every write as not made.

    /**
     * Run [block] under the one process-wide lock of [groupId], shared by every store instance. Every roster and
     * announce read-modify-write goes through it (MA2 / MA3). Re-entrant. Lock order: this lock, then
     * heldGroupAccepts, then [walletScopeLock], never the reverse; no suspend call and no relay send inside [block].
     */
    fun <T> withGroupLock(
        groupId: String,
        block: () -> T
    ): T = synchronized(groupLock(groupId)) { block() }

    /** The announce state of [groupId] (`group_ann_state:<gid>`); the empty state when none is stored. */
    fun getGroupAnnounceState(groupId: String): AnnounceState = AnnounceState()

    /** Write the announce state with commit(); @return whether it reached the disk. Call under [withGroupLock]. */
    fun saveGroupAnnounceState(
        groupId: String,
        state: AnnounceState
    ): Boolean = false

    /** The announcer's ledger of [groupId] (`group_ann_ledger:<gid>`), one entry per (recipient, subject) pair. */
    fun getGroupAnnounceLedger(groupId: String): List<AnnounceLedgerEntry> = emptyList()

    /**
     * Compare-and-set one ledger pair under [withGroupLock], written with commit() (design §6.0, review F8): the entry
     * now naming [pair] must equal [expected] (null = none); RELAY_* never replaces an on-chain state of the same op;
     * CHAIN_PENDING is written only while the pair is owed; [new] null removes the pair (back to owed).
     * @return true only when the new ledger reached the disk.
     */
    fun casGroupAnnounce(
        groupId: String,
        pair: AnnouncePair,
        expected: AnnounceLedgerEntry?,
        new: AnnounceLedgerEntry?
    ): Boolean = false

    /** The announcer's counter for [groupId]: the last announce number handed out (0 = none). */
    fun getGroupAnnounceCounter(groupId: String): Long = 0L

    /**
     * Raise the counter to [n] with commit(), under [withGroupLock]; a value not above the stored one is ignored (the
     * counter only grows). @return whether the counter on disk is now at least [n].
     */
    fun raiseGroupAnnounceCounter(
        groupId: String,
        n: Long
    ): Boolean = false

    /** True once [id] (a canonical address or a NOSTR pubkey) was seen advertising the `gp1` capability. */
    fun isGp1Capable(id: String): Boolean = false

    /** Remember [ids] as `gp1`-capable, with commit(); @return whether they are on disk. */
    fun markGp1Capable(ids: Set<String>): Boolean = false

    /**
     * SR-C1 (KI-37) — what the newest relay rumor from a contact said about its ZChat: [capIronwood] is true when it
     * carried `["zchat_cap","ironwood"]` (2.13.0 puts it in every rumor, 2.12.4 never does); [atSec] is the rumor's
     * created_at (seconds, clamped to the time it was read).
     */
    data class PeerIronwoodCap(
        val capIronwood: Boolean,
        val atSec: Long,
    )

    /** SR-C1 — the newest relay rumor's capability record for [peerAddress], or null when none was recorded. */
    fun getPeerIronwoodCap(peerAddress: String): PeerIronwoodCap? = null

    /**
     * SR-C1 — record the capability of a relay rumor from [peerAddress] written at [atSec]. Only a rumor newer than the
     * stored one replaces it (a replayed older rumor never does). Wiped with the contact's keys ([clearE2EKeys]) and
     * by a full wipe. @return whether the store now holds this rumor's record.
     */
    fun recordPeerIronwoodCap(
        peerAddress: String,
        capIronwood: Boolean,
        atSec: Long,
    ): Boolean = false

    /**
     * ZC-1474 (W-123) — the newest relay rumor's `zatt1` record for [peerAddress] (it reads a free attachment), or null
     * when none was recorded. Same record shape as [getPeerIronwoodCap]: [PeerIronwoodCap.capIronwood] holds the
     * `zatt1` flag here. Newest rumor wins; wiped with the contact's keys and by a full wipe.
     */
    fun getPeerZattCap(peerAddress: String): PeerIronwoodCap? = null

    /**
     * ZC-1474 — record the `zatt1` flag of a relay rumor from [peerAddress] written at [atSec] (newest wins; ZC-1501: a
     * tie goes to the flag off, and the record is tied to the relay key bound now, so it reads as none under another key).
     */
    fun recordPeerZattCap(
        peerAddress: String,
        capZatt: Boolean,
        atSec: Long,
    ): Boolean = false

    /**
     * Get draft for a group conversation.
     */
    fun getGroupDraft(groupId: String): String?

    /**
     * Set draft for a group conversation.
     */
    fun setGroupDraft(groupId: String, draft: String)

    /**
     * Clear draft for a group conversation.
     */
    fun clearGroupDraft(groupId: String)

    /**
     * Get all group drafts.
     */
    fun getAllGroupDrafts(): Map<String, String>

    /**
     * Get the sequence number for sending group messages.
     */
    fun getGroupMessageSequence(groupId: String): Long

    /**
     * Increment and return the next sequence number for group messages.
     */
    fun incrementGroupMessageSequence(groupId: String): Long

    /**
     * Get stored messages for a group (JSON array string).
     */
    fun getGroupMessages(groupId: String): String?

    /**
     * Save messages for a group (JSON array string).
     */
    fun saveGroupMessages(groupId: String, messagesJson: String)

    // ZC-167: the "unroutable messages" store (a memo preview kept in a plain preferences file) is gone: nothing
    // called it, and it could only ever put message text on disk unencrypted. The file an old build may have left is
    // still emptied by clear() (zchat_unroutable_messages).

    // ==========================================
    // IDENTITY MANAGEMENT
    // ==========================================

    /**
     * Get all contact addresses from the address book (nicknames storage).
     * @return Set of all addresses that have nicknames set
     */
    fun getAllContactAddresses(): Set<String>

    /**
     * Get all peer addresses from conversation mappings.
     * @return Set of all peer addresses that have conversations
     */
    fun getAllConversationPeerAddresses(): Set<String>

    /**
     * ZC-1480 — every address a relay key is bound to (a contact reached over Open or Tunnel). The on-chain router's
     * hash-only tier takes them as candidates, still filtered to real addresses and matched only when exactly one fits.
     */
    fun getPeersWithNostrPubkey(): Set<String> = emptySet()

    // ==========================================
    // NOTIFICATION SETTINGS
    // ==========================================

    /**
     * Check if notification sound is enabled.
     * @return true if sound is enabled (default: true)
     */
    fun isNotificationSoundEnabled(): Boolean

    /**
     * Enable/disable notification sound.
     */
    fun setNotificationSoundEnabled(enabled: Boolean)

    /**
     * Check if notification vibration is enabled.
     * @return true if vibration is enabled (default: true)
     */
    fun isNotificationVibrationEnabled(): Boolean

    /**
     * Enable/disable notification vibration.
     */
    fun setNotificationVibrationEnabled(enabled: Boolean)

    /**
     * Get the set of muted conversation addresses.
     */
    fun getMutedConversations(): Set<String>

    /**
     * Mute a conversation by address.
     */
    fun muteConversation(address: String)

    /**
     * Unmute a conversation by address.
     */
    fun unmuteConversation(address: String)

    /**
     * Check if a conversation is muted.
     */
    fun isConversationMuted(address: String): Boolean

    // ==========================================
    // CONVERSATION READ STATE (unread badge)
    // ==========================================

    /**
     * Last-read marker for a conversation, as epoch milliseconds. Incoming messages with a
     * timestamp newer than this are counted as unread. Returns 0 if the conversation has never
     * been opened (so all incoming history counts as unread until first open).
     */
    fun getLastReadTimestamp(peerAddress: String): Long

    /**
     * Mark a conversation read up to [millis] (epoch milliseconds). Monotonic: an earlier value
     * never overwrites a later one, so a stale call can't resurrect already-read messages.
     */
    fun setLastReadTimestamp(peerAddress: String, millis: Long)

    /**
     * Snapshot of every stored last-read marker (peerAddress -> epoch millis). Used to compute
     * unread counts for the whole conversation list in one pass.
     */
    fun getAllLastReadTimestamps(): Map<String, Long>

    /**
     * PROCESS-WIDE reactive view of all last-read markers (#226). ZchatPreferences is a DI singleton,
     * so this single flow is shared across every ChatViewModel instance — including the separate
     * instances that back the chat-LIST and chat-DETAIL screens (distinct NavBackStackEntry stores).
     * [setLastReadTimestamp] emits the updated map here, so a read advanced on the detail screen is
     * observed by the list screen's unread recompute immediately, instead of the list holding a stale
     * per-instance copy seeded once at init (the bug where the badge never cleared after opening).
     */
    val readMarkers: kotlinx.coroutines.flow.StateFlow<Map<String, Long>>

    /**
     * ZC-1104 (W-79): the main ZCHAT preferences, for small chat-list view choices (ChatListGroupsFolderStore). The
     * same file as the last-read markers, so a Reset or a Destroy clears them with it. null where there is no such file.
     */
    val chatListUiPrefs: SharedPreferences? get() = null

    /** #257: bumped whenever a handshake-relevant E2E marker changes (KEXACK sent, received-KEX, boot-sent,
     *  E2E enabled, peer key, key wipe). Lets the chat-list/detail E2E status recompute immediately after a
     *  prefs-only write (e.g. retryKexAckIfResponder success) instead of waiting for the next chain scan. */
    val e2eHandshakeTicks: kotlinx.coroutines.flow.StateFlow<Long>

    // ==========================================
    // WORKER SYNC TIMESTAMP
    // ==========================================

    /**
     * Get the last timestamp when SyncWorker completed a sync.
     */
    fun getLastWorkerSyncTimestamp(): Long

    /**
     * Set the last timestamp when SyncWorker completed a sync.
     */
    fun setLastWorkerSyncTimestamp(millis: Long)

    // ==========================================
    // SEED BACKUP REMINDER
    // ==========================================

    fun hasBackedUpSeed(): Boolean
    fun setHasBackedUpSeed(backed: Boolean)
    fun getFirstOutgoingMessageTimestamp(): Long
    fun setFirstOutgoingMessageTimestamp(millis: Long)
    fun getLastBackupReminderTimestamp(): Long
    fun setLastBackupReminderTimestamp(millis: Long)
    fun getBackupReminderCount(): Int
    fun incrementBackupReminderCount()

    // ==========================================
    // NOSTR KEY ROTATION (#178 Part B)
    // ==========================================
    /** Account-wide NOSTR derivation index. 0 = original identity; bumped by user key rotation. */
    fun getNostrRotationIndex(): Int
    fun setNostrRotationIndex(index: Int)

    /**
     * The rotation index of OUR NOSTR key that [peerAddress] currently knows/has adopted (#250). A v4
     * rotation ZBOOT to an OPEN peer is signed with the key at THIS index (the one they still hold) — not
     * blindly index-1 — so a missed/undelivered rotation can't permanently strand them. -1 = unknown
     * (treated as the original index 0). Advanced only when the peer is known to have received our new key.
     */
    fun getPeerKnownOurRotationIndex(peerAddress: String): Int
    fun setPeerKnownOurRotationIndex(peerAddress: String, index: Int)
    /** Epoch millis of the last time we showed the "rotate your key" reminder (0 = never). */
    fun getLastRotationReminderAt(): Long
    fun setLastRotationReminderAt(millis: Long)

    companion object {
        /**
         * Round 8 D11 (Grok CLI review, G14) — held from the generation bump to the last write while the wallet-scoped
         * stores are moved to another wallet (ChatWalletOwnership.claimFor) or wiped ([clearAll]). A writer that
         * checks [walletScopeGeneration] before writing wallet-scoped state (ChatViewModel's held group replies) does
         * both under it, so the check and the write never straddle a claim or a wipe.
         */
        val walletScopeLock: Any = Any()

        /** ZC-1464 — the Declined requests list keeps at most this many (the newest). */
        const val MAX_DECLINED_REQUESTS = 50

        /** ZC-285 — [getKexOwedReason] of a KEX a confirmed Reset encryption owes. */
        const val KEX_OWED_REASON_RESET = "reset"

        /** ZC-300 — [getKexOwedReason] of a KEX a confirmed key-change reconnect owes (the × on the key-changed banner). */
        const val KEX_OWED_REASON_RECONNECT = "reconnect"

        private val groupLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** Round 8 MA1 — the one lock object of [groupId] in this process (see [withGroupLock]). */
        fun groupLock(groupId: String): Any = groupLocks.computeIfAbsent(groupId) { Any() }

        private val groupStoreVersions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())

        /**
         * Round 9 R9-5 (REVIEW_R8 F10) — per group id, a number that grows every time this process writes that group's
         * messages, roster, announce state or info ([markGroupStoreChanged]). Process-wide, so an open group screen (its
         * own GroupViewModel) hears of what ChatViewModel stores and reloads. Nothing is persisted; it starts empty.
         */
        val groupStoreVersion: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = groupStoreVersions

        /** Round 9 R9-5 — called by every group save of the store, after the write (see [groupStoreVersion]). */
        fun markGroupStoreChanged(groupId: String) {
            groupStoreVersions.update { versions -> versions + (groupId to (versions[groupId] ?: 0L) + 1L) }
        }

        private val pendingRowsVersions = kotlinx.coroutines.flow.MutableStateFlow(0L)

        /**
         * ZC-946 part 4 — a number that grows every time this process writes or removes a pending row
         * ([markPendingRowsChanged]). Process-wide, so the chat list (its own ChatViewModel) hears of a send the chat
         * screen stored. Nothing is persisted; it starts at 0.
         */
        val pendingRowsVersion: kotlinx.coroutines.flow.StateFlow<Long> = pendingRowsVersions

        /** ZC-946 part 4 — called by every pending-row write of the store, after the write (see [pendingRowsVersion]). */
        fun markPendingRowsChanged() {
            pendingRowsVersions.update { it + 1L }
        }

        /**
         * F5: [record] as its JSON object (keys attemptHeight, peerHash, amountZat, selfHash, startedAt, and txIds and
         * expiries only when it holds ids).
         */
        fun encodeFulfilStarted(record: FulfilStarted): org.json.JSONObject {
            val json =
                org.json
                    .JSONObject()
                    .put("attemptHeight", record.attemptHeight)
                    .put("peerHash", record.peerHash)
                    .put("amountZat", record.amountZatoshi)
                    .put("selfHash", record.selfHash)
                    .put("startedAt", record.startedAtMillis)
            if (record.txIds.isNotEmpty()) {
                json.put("txIds", org.json.JSONArray(record.txIds))
                json.put("expiries", org.json.JSONObject(record.expiries))
            }
            return json
        }

        /**
         * F5: a record read from [json], or null without a peer hash. Records written before F5 carry no txIds or
         * expiries and load unchanged (empty); an expiry that cannot be read is left out (never judged by it).
         */
        fun decodeFulfilStarted(json: org.json.JSONObject): FulfilStarted? {
            val peerHash = json.optString("peerHash").takeIf { it.isNotEmpty() } ?: return null
            val ids = json.optJSONArray("txIds")
            val txIds = if (ids == null) emptyList() else (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotEmpty) }
            val expiriesJson = json.optJSONObject("expiries")
            val expiries =
                buildMap {
                    if (expiriesJson != null) {
                        for (id in expiriesJson.keys()) {
                            val expiry = expiriesJson.optLong(id, -1L)
                            if (expiry >= 1) put(id, expiry)
                        }
                    }
                }
            return FulfilStarted(
                attemptHeight = json.optLong("attemptHeight", -1L),
                peerHash = peerHash,
                amountZatoshi = json.optLong("amountZat", 0L),
                selfHash = json.optString("selfHash"),
                startedAtMillis = json.optLong("startedAt", 0L),
                txIds = txIds,
                expiries = expiries,
            )
        }

        /** Round 8 P2: the hash a [FulfilStarted] record keeps instead of an address (SHA-256, first 16 bytes, hex). */
        fun fulfilAddressHash(address: String): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(address.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it) }

        /** D2 — rolling budget window for automatic paid KEXACKs (24 h). */
        const val KEXACK_BUDGET_WINDOW_MS: Long = 86_400_000L

        /** D2 — max automatic paid KEXACKs per [KEXACK_BUDGET_WINDOW_MS], wallet-wide. */
        const val MAX_KEXACK_PER_WINDOW: Int = 500

        /** KI-23 — rolling window of the automatic paid key-grant budget (24 h). */
        const val GROUP_KEY_GRANT_BUDGET_WINDOW_MS: Long = 86_400_000L

        /** KI-23 — max automatic paid key grants per [GROUP_KEY_GRANT_BUDGET_WINDOW_MS], wallet-wide. */
        const val MAX_GROUP_KEY_GRANT_PER_WINDOW: Int = 10
    }
}

// ZC-420: the key prefix of the relay-copy marks in the held-reply store, and the kinds of a group's marks.
private const val RELAY_COPY_PREFIX = "relaycopy:"
private val RELAY_COPY_GROUP_KINDS = listOf("gk", "gi", "gm", "gr")

/**
 * SharedPreferences-based implementation of ZchatPreferences.
 *
 * SECURITY: Sensitive data (E2E keys, group keys, message plaintext, drafts, nicknames,
 * group rosters, reactions) uses EncryptedSharedPreferences with AES256-GCM encryption
 * backed by Android Keystore. Only non-identifying state (settings, dedup sets, sequence
 * counters) uses regular SharedPreferences.
 */
class ZchatPreferencesImpl(context: Context) : ZchatPreferences {

    // P2c KR-1: an unreadable encrypted store is recreated only after a copy of its raw entries is kept
    // in a "__unreadable_" file (see openEncryptedStore). clearAll removes those copies with the rest.
    private val appContext: Context = context.applicationContext ?: context
    private val sharedPrefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Separate prefs file for peer statuses (can grow large)
    private val peerStatusPrefs: SharedPreferences = context.getSharedPreferences(
        PEER_STATUS_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Unified conversation mapping prefs: uses prefixed keys for atomicity
    // "peer:<address>" -> convId  and  "conv:<convId>" -> address
    // This replaces the old separate convIdPrefs and peerToConvIdPrefs files
    // to ensure both directions are written in a single atomic commit().
    private val convMappingPrefs: SharedPreferences = context.getSharedPreferences(
        CONV_MAPPING_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    init {
        // Migrate from old separate prefs files to unified file
        migrateConvIdPrefs(context)
    }

    /**
     * Migration: copy entries from old separate convIdPrefs/peerToConvIdPrefs into
     * the unified convMappingPrefs file, then clear the old files.
     * Safe to run multiple times (idempotent).
     */
    private fun migrateConvIdPrefs(context: Context) {
        val oldConvIdPrefs = context.getSharedPreferences(CONV_ID_PREFS_NAME_OLD, Context.MODE_PRIVATE)
        val oldPeerToConvIdPrefs = context.getSharedPreferences(PEER_TO_CONV_ID_PREFS_NAME_OLD, Context.MODE_PRIVATE)

        val oldConvEntries = oldConvIdPrefs.all
        val oldPeerEntries = oldPeerToConvIdPrefs.all

        if (oldConvEntries.isEmpty() && oldPeerEntries.isEmpty()) return

        Log.d("ZCHAT_MIGRATE", "Migrating ConvID prefs: ${oldConvEntries.size} conv entries, ${oldPeerEntries.size} peer entries")

        val editor = convMappingPrefs.edit()

        // Track which keys have been written OR already exist in target.
        // This prevents re-migration from overwriting newer data if the process was
        // killed between the migration commit and the old-file clear.
        // Also prevents flip-flop when old files disagree (first-writer-wins).
        val writtenKeys = mutableSetOf<String>()
        // Seed with existing keys in target so re-migration doesn't clobber them
        for ((key, _) in convMappingPrefs.all) {
            writtenKeys.add(key)
        }

        // Migrate convId -> peerAddress (old convIdPrefs) — first pass, authoritative
        // Both keys are guarded by writtenKeys to prevent re-migration from overwriting
        // newer data if old files weren't cleared (process killed between commit and clear).
        for ((convId, value) in oldConvEntries) {
            if (value is String && value.isNotBlank()) {
                val convKey = "conv:$convId"
                val peerKey = "peer:$value"
                if (convKey !in writtenKeys) {
                    editor.putString(convKey, value)
                    writtenKeys.add(convKey)
                }
                if (peerKey !in writtenKeys) {
                    editor.putString(peerKey, convId)
                    writtenKeys.add(peerKey)
                }
            }
        }

        // Migrate peerAddress -> convId (old peerToConvIdPrefs) — fills gaps only
        for ((peerAddress, value) in oldPeerEntries) {
            if (value is String && value.isNotBlank()) {
                val peerKey = "peer:$peerAddress"
                val convKey = "conv:$value"
                if (peerKey !in writtenKeys) {
                    editor.putString(peerKey, value)
                    writtenKeys.add(peerKey)
                }
                if (convKey !in writtenKeys) {
                    editor.putString(convKey, peerAddress)
                    writtenKeys.add(convKey)
                }
            }
        }

        val migrationSuccess = editor.commit()

        if (!migrationSuccess) {
            Log.e("ZCHAT_MIGRATE", "Migration commit FAILED - keeping old files for retry on next launch")
            return
        }

        // Clear old files only after successful migration
        oldConvIdPrefs.edit().clear().commit()
        oldPeerToConvIdPrefs.edit().clear().commit()

        Log.d("ZCHAT_MIGRATE", "ConvID migration complete")
    }

    /**
     * One-time migration of sensitive plaintext out of the old UNENCRYPTED SharedPreferences files
     * into the now-encrypted stores, then CLEAR the plain files so nothing sensitive remains at rest
     * in the clear. Covers decrypted message content (zchat_group_messages / zchat_pending_messages),
     * unsent draft plaintext (HIGH-002: zchat_drafts / zchat_group_drafts), contact nicknames
     * (MEDIUM-010: zchat_nicknames), and member rosters (MEDIUM-011: zchat_group_members). Idempotent (no-op once the
     * old files are empty). KI-25 D1 (ZC-598): group info (zchat_group_info) has its own step,
     * [moveLegacyGroupRecords], in the init block directly below the property that opens the group store.
     */
    private fun migrateMessagePlaintextPrefs(context: Context) {
        migratePlainToEncrypted(context.getSharedPreferences(GROUP_MSG_PREFS_NAME, Context.MODE_PRIVATE), groupMsgPrefs)
        migratePlainToEncrypted(context.getSharedPreferences(PENDING_MSG_PREFS_NAME, Context.MODE_PRIVATE), pendingMsgPrefs)
        migratePlainToEncrypted(context.getSharedPreferences(DRAFT_PREFS_NAME, Context.MODE_PRIVATE), draftPrefs)
        migratePlainToEncrypted(context.getSharedPreferences(GROUP_DRAFT_PREFS_NAME, Context.MODE_PRIVATE), groupDraftPrefs)
        migratePlainToEncrypted(context.getSharedPreferences(NICKNAME_PREFS_NAME, Context.MODE_PRIVATE), nicknamePrefs)
        migratePlainToEncrypted(context.getSharedPreferences(GROUP_MEMBERS_PREFS_NAME, Context.MODE_PRIVATE), groupMembersPrefs)
        // R4-1: the conversation-mode file keeps other families, so only its held-accept entries move.
        migrateHeldGroupAcceptsToEncrypted(modePrefs, heldGroupAcceptPrefs)
    }

    /**
     * KI-25 D1 (ZC-598) — the app's main process: the process name equals the application's own. A test that throws
     * (the name can come back without this process on Android 8.1) counts as "not the main process".
     */
    @Suppress("TooGenericExceptionCaught")
    private fun isMainProcess(context: Context): Boolean =
        try {
            ProcessNameCompat.getProcessName(context) == context.applicationInfo.processName
        } catch (e: Exception) {
            false
        }

    /**
     * KI-25 D1 (ZC-598) — the group step of the plaintext move: 2.12.4's plain group file [old] into the encrypted group
     * store [enc], and, in the first start of this build only, the one look. Called from the init block below the group
     * store's property and from nowhere else; no lock, no retry.
     *
     * The look runs when all four hold: this is the main process ([mainProcess]; one that throws counts as no), the old
     * file holds no mark (`old.all[ADMIN_LOOK_MARK] == true`, by type), this process created the group file
     * ([storeIsNew]), and the mark's `commit()` answered true just now. Then each record is looked at inside its own
     * guard ([GroupAdminCheck.firstLook]): a record the look gave a key is written with it, every other record, a string
     * that does not deserialize and a record whose look threw as 2.12.4 wrote it. All records go in one editor and one
     * `commit()`, and only ids the store lacks. After that commit the old file is emptied (`clear().commit()`, else the
     * file is deleted). After a refused or failed commit the old file is kept and the strings 2.12.4 wrote are put back
     * in the store's memory (`apply()`): the older groups are in the list without the look's key and ask.
     * @return the one line of counts it also logs (no id, address or key), or null when the old file held nothing.
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun moveLegacyGroupRecords(
        old: SharedPreferences,
        enc: SharedPreferences,
        storeIsNew: Boolean,
        mainProcess: () -> Boolean,
    ): String? {
        val entries =
            try {
                old.all
            } catch (e: Exception) {
                return null
            }
        if (entries.isEmpty()) return null
        val records = LinkedHashMap<String, String>()
        for ((k, v) in entries) if (k != ADMIN_LOOK_MARK && v is String) records[k] = v
        val main =
            try {
                mainProcess()
            } catch (e: Exception) {
                false
            }
        val noMark = entries[ADMIN_LOOK_MARK] != true
        val markWritten =
            main &&
                noMark &&
                storeIsNew &&
                try {
                    old.edit().putBoolean(ADMIN_LOOK_MARK, true).commit()
                } catch (e: Exception) {
                    false
                }
        val existing =
            try {
                enc.all.keys
            } catch (e: Exception) {
                emptySet()
            }
        val toWrite = LinkedHashMap<String, String>()
        var looked = 0
        var keys = 0
        var threw = 0
        for ((id, raw) in records) {
            if (id in existing) continue
            var value = raw
            if (markWritten) {
                try {
                    val info = ZMSGGroupProtocol.deserializeGroupInfo(raw)
                    if (info != null) {
                        looked++
                        val after = GroupAdminCheck.firstLook(this, info)
                        if (after.creatorPublicKey != info.creatorPublicKey) {
                            value = ZMSGGroupProtocol.serializeGroupInfo(after)
                            keys++
                        }
                    }
                } catch (e: Exception) {
                    threw++
                    value = raw
                }
            }
            toWrite[id] = value
        }
        val committed =
            toWrite.isEmpty() ||
                try {
                    val editor = enc.edit()
                    toWrite.forEach { (id, value) -> editor.putString(id, value) }
                    editor.commit()
                } catch (e: Exception) {
                    false
                }
        if (committed) {
            val cleared =
                try {
                    old.edit().clear().commit()
                } catch (e: Exception) {
                    false
                }
            if (!cleared) runCatching { appContext.deleteSharedPreferences(GROUP_INFO_PREFS_NAME) }
        } else if (toWrite.isNotEmpty()) {
            // A refused commit has already changed the store's memory: the strings 2.12.4 wrote go back in place of
            // the looked-at ones, so this process shows the older groups without the look's key.
            runCatching {
                val editor = enc.edit()
                toWrite.keys.forEach { id -> editor.putString(id, records.getValue(id)) }
                editor.apply()
            }
        }
        val line =
            "group step: main=$main noMark=$noMark newFile=$storeIsNew markWritten=$markWritten " +
                "moved=${toWrite.size} looked=$looked keys=$keys threw=$threw committed=$committed"
        Log.e("ZCHAT_MIGRATE", line)
        return line
    }

    private fun migratePlainToEncrypted(old: SharedPreferences, enc: SharedPreferences) {
        val entries = old.all
        if (entries.isEmpty()) return
        // Don't clobber NEWER encrypted data if a prior migration committed but the process died before the
        // old-file clear — only fill keys the encrypted store doesn't already hold.
        val existing = enc.all.keys
        val editor = enc.edit()
        var moved = 0
        for ((k, v) in entries) {
            if (v is String && k !in existing) {
                editor.putString(k, v)
                moved++
            }
        }
        if (editor.commit()) {
            // Remove the at-rest plaintext ONLY after a durable encrypted write.
            old.edit().clear().commit()
            Log.d("ZCHAT_MIGRATE", "Migrated $moved plaintext message record(s) to encrypted store")
        } else {
            Log.e("ZCHAT_MIGRATE", "Encrypted-migration commit FAILED — keeping old plain file for retry")
        }
    }

    // Contact nicknames: address -> nickname. Social-graph data (who you talk to, by chosen name) —
    // encrypted at rest like the contact/pending stores (MEDIUM-010). Old plain file is migrated +
    // scrubbed in migrateMessagePlaintextPrefs.
    private val nicknamePrefs: SharedPreferences = createEncryptedPrefs(context, NICKNAME_PREFS_NAME_ENC)

    // Message drafts: peerAddress -> draft text. Drafts are unsent MESSAGE PLAINTEXT and were the one
    // message-content store left in the clear (HIGH-002) — encrypted at rest like pendingMsgPrefs.
    private val draftPrefs: SharedPreferences = createEncryptedPrefs(context, DRAFT_PREFS_NAME_ENC)

    // View-once consumption marker: fileHash -> "1" once consumed.
    // Stored unencrypted because the hash is opaque on its own — the cache wipe is what
    // actually protects the bytes.
    private val viewOncePrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_view_once",
        Context.MODE_PRIVATE,
    )

    // Per-conversation mode (VAULT/TUNNEL/OPEN) + the peer's NOSTR pubkey + relay.
    // Tunnel needs all three after the bootstrap completes; Open needs pubkey + relay
    // exchanged out of band.
    private val modePrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_conversation_mode",
        Context.MODE_PRIVATE,
    )

    // R4-1: the GROUP_ACCEPTs held for the handshake-spend consent (R3-4). Each record carries the inviter's
    // full address, our own address and the whole signed accept memo — social-graph plus replayable message
    // content — so it belongs with the encrypted stores, not in the plaintext conversation-mode file it
    // started in. migrateHeldGroupAcceptsToEncrypted moves what an earlier version left there.
    private val heldGroupAcceptPrefs: SharedPreferences = createEncryptedPrefs(context, HELD_GROUP_ACCEPT_PREFS_NAME_ENC)

    // F8 (ZC-112, ZC-600): the send-attempt ledger. Its entries carry contact addresses, group ids, a held reply and
    // transaction ids, so they live in the encrypted held-reply store above, under their own key prefix
    // (PrefsSendAttemptLedger.PREFIX): no new file, and the wipe and the wallet scope cover them with that store. Built
    // on first use, and not part of the ZchatPreferences interface (the ViewModels get it through Koin).
    @Volatile private var sendAttemptLedgerOrNull: PrefsSendAttemptLedger? = null

    val sendAttemptLedger: co.electriccoin.zcash.ui.common.sendattempt.SendAttemptLedger
        get() =
            sendAttemptLedgerOrNull ?: synchronized(this) {
                sendAttemptLedgerOrNull ?: PrefsSendAttemptLedger { heldGroupAcceptPrefs }.also { sendAttemptLedgerOrNull = it }
            }

    // #188 dedicated, bounded, persistent set of handled inbound NOSTR gift-wrap event ids. Its own
    // file so it can't collide with mode/key data and so clearing it never touches conversation state.
    private val nostrSeenPrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_nostr_seen",
        Context.MODE_PRIVATE,
    )
    private val nostrSeenLock = Any()

    // RELAY-HEALTH: the relay set (slots 1 and 2, cooldowns, recent swaps, when nos.lol left slot 2). App scope, plain,
    // written only on a swap (commit). Not a wallet-scoped store; the per-relay `since` values stay in zchat_nostr_seen.
    private val relaySetPrefs: SharedPreferences =
        context.getSharedPreferences(RELAY_SET_PREFS_NAME, Context.MODE_PRIVATE)

    // KI-24 part B: in-memory mirror, kept by the wrap's outer created_at with a floor (NostrSeenLedger); loaded once
    // from disk. The old one-id-per-line list loads with the load time, so nothing seen becomes unseen.
    private val nostrSeenLedger: NostrSeenLedger by lazy {
        synchronized(nostrSeenLock) { NostrSeenLedger(::nostrSeenCap).also { loadNostrSeen(it) } }
    }

    // #201 anti-flap: dedicated bounded persistent set of processed on-chain KEX/KEXACK txids.
    private val kexSeenPrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_kex_seen",
        Context.MODE_PRIVATE,
    )
    private val kexSeenLock = Any()

    // KI-24 part A: in-memory mirror with each txid's mined height and the height floor (KexSeenLedger).
    private val kexSeenLedger: KexSeenLedger by lazy {
        synchronized(kexSeenLock) { KexSeenLedger(::kexSeenCap).also { loadKexSeen(it) } }
    }

    // D2 — automatic-KEXACK spend-budget timestamps (newline-joined epoch millis). Non-identifying
    // rate-limit state → plain prefs (same file as the KEX dedup set, its own key + its own lock).
    private val kexAckBudgetLock = Any()

    // KI-23 — the automatic key-grant budget: the same kind of rate-limit state, in the same file, under its own lock.
    private val groupKeyGrantBudgetLock = Any()

    // Dedicated bounded persistent set of processed ZBOOT signatures — kept SEPARATE from kexSeenLedger
    // so fast-churning ZBOOT dedup keys can't evict genuine KEX/KEXACK txids from that FIFO (the cause
    // of the spurious KEX key-change alarm). ZBOOT re-adoption is still guarded by #225 epoch monotonicity.
    private val bootSeenPrefs: SharedPreferences = context.getSharedPreferences(
        "zchat_boot_seen",
        Context.MODE_PRIVATE,
    )
    private val bootSeenLock = Any()
    private val bootSeenIds: LinkedHashSet<String> by lazy {
        synchronized(bootSeenLock) {
            val stored = bootSeenPrefs.getString(BOOT_SEEN_KEY, null)
            LinkedHashSet(stored?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList())
        }
    }

    // Remote-kill replay guard: txids of kill signals this install has already ACTED ON. Its own
    // store so no other seen-set's churn can evict an entry and let an irreversible wipe re-fire.
    // Only matching signals are ever added, so this stays a handful of entries for the app's life.
    private val killSeenPrefs: SharedPreferences =
        context.getSharedPreferences(
            "zchat_kill_seen",
            Context.MODE_PRIVATE,
        )
    private val killSeenLock = Any()
    private val killSeenIds: LinkedHashSet<String> by lazy {
        synchronized(killSeenLock) {
            val stored = killSeenPrefs.getString(KILL_SEEN_KEY, null)
            LinkedHashSet(stored?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList())
        }
    }

    // #205 — self-address representation registry. A single wallet presents multiple valid
    // unified-address strings (diversifier/derivation/receiver-subset differences), so "is this
    // address me?" cannot be a raw string compare. We record the hash of every representation of
    // OUR OWN address we observe (canonical diversifier-0 UA used for KEX-sign + receive display,
    // plus the account's current unified address) and match by hash. Backed by `prefs` (a stored
    // string-set) so it survives process death and is shared across ChatViewModel/GroupViewModel.
    // P2g: the registry and the saved chat identity live in SelfAddressRegistry.
    private val selfAddresses by lazy { SelfAddressRegistry(prefs) }

    // SECURITY: E2E encryption keys stored in EncryptedSharedPreferences
    // Keys are encrypted with AES256-GCM, master key stored in Android Keystore
    private val e2ePrefs: SharedPreferences = createEncryptedPrefs(context, E2E_PREFS_NAME)

    init {
        // W-72 (SPEC_F R1, SPEC_DECISIONS D1): directly after the key store is open, before anything of this process can
        // write a key. The done-flag lives in the key store itself, so another wallet's store gets its own pass.
        markLegacyKeysAtStart { isMainProcess(context) }
    }

    // Group chat storage. Group names + member rosters are conversation-graph metadata (who talks to
    // whom) — encrypted at rest like the group messages/keys (MEDIUM-011). The old plain roster file is
    // migrated + scrubbed in migrateMessagePlaintextPrefs; the old plain group file in the init block below.
    // KI-25 D1 (ZC-598): opened after the key store, through openGroupStoreAndTellIfNew, which tells whether this
    // process created the file (condition 2 of the one look).
    private val groupStoreOpened: OpenedGroupStore =
        openGroupStoreAndTellIfNew(sharedPrefsDir, GROUP_INFO_PREFS_NAME_ENC, { createEncryptedPrefs(context, GROUP_INFO_PREFS_NAME_ENC) })
    private val groupInfoPrefs: SharedPreferences = groupStoreOpened.prefs

    init {
        // KI-25 D1 (ZC-598): the group step of the plaintext move, with the mark and the one look, directly after the
        // group store was opened and before any later store is.
        moveLegacyGroupRecords(
            context.getSharedPreferences(GROUP_INFO_PREFS_NAME, Context.MODE_PRIVATE),
            groupInfoPrefs,
            groupStoreOpened.isNew,
        ) { isMainProcess(context) }
    }

    private val groupMembersPrefs: SharedPreferences = createEncryptedPrefs(context, GROUP_MEMBERS_PREFS_NAME_ENC)

    // SECURITY: Group encryption keys stored in EncryptedSharedPreferences
    private val groupKeysPrefs: SharedPreferences = createEncryptedPrefs(context, GROUP_KEYS_PREFS_NAME)

    // SECURITY: decrypted message plaintext (see getDecryptedText doc) — encrypted at rest.
    private val decryptedTextPrefs: SharedPreferences = createEncryptedPrefs(context, DECRYPTED_TEXT_PREFS_NAME)

    // SECURITY (#224): inbound OPEN contact requests hold a claimed Zcash address + the first-message
    // plaintext, so they are encrypted at rest. Per-pubkey JSON entry ("req:<pubkey>"); a blocked-pubkey
    // set lives under BLOCKED_PUBKEYS_KEY.
    private val messageRequestPrefs: SharedPreferences = createEncryptedPrefs(context, MESSAGE_REQUEST_PREFS_NAME)
    private val messageRequestLock = Any()

    // SECURITY: Ratchet state (counters, seen-counter sets) stored encrypted
    private val ratchetPrefs: SharedPreferences = createEncryptedPrefs(context, "zchat_ratchet_state")

    // D8 (AES-GCM nonce reuse) — durable ratchet counter FLOORS. PLAIN SharedPreferences BY DESIGN,
    // not createEncryptedPrefs: the corruption self-heal above wipes an ENCRYPTED store and recreates
    // it empty, while the E2E root material (e2ePrefs) survives and re-derives the SAME root — so if
    // ratchetPrefs is wiped, the send counter would restart at 0 under the same root and REUSE an
    // AES-GCM (key, nonce) pair. The floor file survives precisely because it is outside Tink's blast
    // radius; counters are not secret (each E2E1 ciphertext carries its counter on the wire).
    private val ratchetFloorPrefs: SharedPreferences = context.getSharedPreferences(
        RATCHET_COUNTER_FLOOR_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val ratchetStore =
        co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.EncryptedPrefsRatchetStateStore(ratchetPrefs, ratchetFloorPrefs)

    // Group drafts are unsent message plaintext too — same HIGH-002 treatment as draftPrefs.
    private val groupDraftPrefs: SharedPreferences = createEncryptedPrefs(context, GROUP_DRAFT_PREFS_NAME_ENC)

    private val groupSeqPrefs: SharedPreferences = context.getSharedPreferences(
        GROUP_SEQ_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // #audit (plaintext-at-rest): holds DECRYPTED group message plaintext → AES256-GCM encrypted, like
    // decryptedTextPrefs. Distinct "_encrypted" file so we can migrate the old plain file out (below).
    private val groupMsgPrefs: SharedPreferences = createEncryptedPrefs(context, GROUP_MSG_PREFS_NAME_ENC)

    // Pending messages: messageId -> PendingMessageData JSON. Holds DECRYPTED NOSTR message plaintext +
    // peer addresses → encrypted at rest for the same reason.
    private val pendingMsgPrefs: SharedPreferences = createEncryptedPrefs(context, PENDING_MSG_PREFS_NAME_ENC)

    init {
        // Second init block — runs AFTER the encrypted stores above are initialized (init blocks and
        // property initializers execute in source order). Move any pre-existing sensitive plaintext
        // (messages, drafts, nicknames, group info/rosters) out of the old UNENCRYPTED files into the
        // encrypted stores and clear the plain files so nothing lingers.
        migrateMessagePlaintextPrefs(context)
    }

    // NOSTR-reaction persistence. Reactions sent/received over NOSTR are NOT on-chain memos (so they
    // aren't re-derived by the on-chain reactionsByTarget pass) and PendingMessageData has no reactions
    // field — so a reaction applied only to the in-memory pendingMessages StateFlow is WIPED on the next
    // reload (loadPendingMessagesFromPrefs overwrites pendingMessages from storage). Persist them here,
    // keyed by target message id, and re-apply on load. Lines: "emojisenderAddrtsMillis".
    // Reactions carry peer sender addresses + message-id links (conversation-graph metadata) —
    // encrypted at rest (MEDIUM-010), old plain file migrated + scrubbed in the init below.
    private val reactionPrefs: SharedPreferences = createEncryptedPrefs(context, NOSTR_REACTION_PREFS_NAME_ENC)
    private val reactionLock = Any()

    // M1: one read-modify-write of our own relay-key set at a time.
    private val ownNostrKeysLock = Any()

    // Round 9 R9-3: one read-modify-write of a group's removal floor and record at a time.
    private val removalRecordLock = Any()

    init {
        // Runs AFTER reactionPrefs above (source order) — it is declared below the shared migration
        // init block, so its old-plain-file migration has to live in its own init.
        migratePlainToEncrypted(context.getSharedPreferences(NOSTR_REACTION_PREFS_NAME, Context.MODE_PRIVATE), reactionPrefs)
    }

    // Local call-log entries (not secret — plaintext SharedPreferences, like unroutableMsgPrefs).
    private val callLogPrefs: SharedPreferences = context.getSharedPreferences(
        CALL_LOG_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // ZC-167: the file of the removed unroutable-message store; kept only so clear() still empties what an old build
    // wrote there (a memo preview in clear text).
    private val unroutableMsgPrefs: SharedPreferences = context.getSharedPreferences(
        UNROUTABLE_MSG_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Create EncryptedSharedPreferences for secure storage of sensitive data.
     *
     * SECURITY: Uses AES256-GCM encryption with master key stored in Android Keystore.
     * The master key is hardware-backed on devices with secure hardware (TEE/StrongBox).
     *
     * CRITICAL: Never falls back to plaintext. If encryption fails, the app cannot
     * safely store E2E keys, so the store is not opened (the start-up screen with Try again) rather than silently
     * storing private keys in plaintext.
     *
     * ZC-705, what is true now and how it is enforced: the encryption library itself writes a NEW file's key set
     * unencrypted when its Keystore check fails while it makes the file; the opener ([openEncryptedStore]) reads the
     * two key entries after every open and puts such a new file back as it was and tries again, in the main process
     * only. A file that existed before (also one 2.12.4 left with an unprotected key entry) is never rewritten: it is
     * counted and logged by its kind and used as before. ONE EXCEPTION, and this is the one call site that sets it
     * (keepPlainAfterLimit): at the third counted start in a row a new chat store that comes out unprotected again is
     * kept, and the other new chat stores of that process follow, so that a phone whose Keystore makes keys and
     * cannot load them is not held on the Try-again screen for ever with a wallet it could open before the update.
     * The file is opened through [openEncryptedFile] (by key name when it exists, so no Keystore key is made for
     * it); the wallet SDK's own opener still makes the master-key call under the same key name at every wallet start.
     */
    private fun createEncryptedPrefs(context: Context, name: String): SharedPreferences =
        openEncryptedStore(
            name = name,
            open = { openEncryptedFile(context, name) },
            plainPrefs = { fileName -> context.getSharedPreferences(fileName, Context.MODE_PRIVATE) },
            // R3-3: only the start-up check's open (the safety screen) may count a launch or recreate a store.
            userVisible = ChatStoreStart.userVisible,
            // ZC-707: the process's one launch (one count per process; never emptied behind a live object).
            // ZC-705: the one call site of the three-start rule.
            env = appStoreOpenEnv(context, keepPlainAfterLimit = true),
        )

    companion object {
        private const val PREFS_NAME = "zchat_preferences"
        private const val PEER_STATUS_PREFS_NAME = "zchat_peer_statuses"
        private const val CONV_MAPPING_PREFS_NAME = "zchat_conv_mapping"   // unified: "peer:<addr>"->convId, "conv:<id>"->addr
        private const val CONV_ID_PREFS_NAME_OLD = "zchat_conv_ids"          // OLD: convId -> peerAddress (migration source)
        private const val PEER_TO_CONV_ID_PREFS_NAME_OLD = "zchat_peer_conv_ids"  // OLD: peerAddress -> convId (migration source)
        // OLD plain files — kept ONLY as migration sources (see migrateMessagePlaintextPrefs); no longer written.
        private const val NICKNAME_PREFS_NAME = "zchat_nicknames"        // OLD: address -> nickname
        private const val DRAFT_PREFS_NAME = "zchat_drafts"            // OLD: peerAddress -> draft text
        // Encrypted stores (AES256-GCM) — nicknames are social-graph data, drafts are unsent message plaintext.
        private const val NICKNAME_PREFS_NAME_ENC = "zchat_nicknames_encrypted"
        private const val DRAFT_PREFS_NAME_ENC = "zchat_drafts_encrypted"
        private const val HELD_GROUP_ACCEPT_PREFS_NAME_ENC = "zchat_held_group_accepts_encrypted"
        private const val E2E_PREFS_NAME = "zchat_e2e_keys_encrypted"  // E2E encryption keys (AES256-GCM encrypted)
        private const val DECRYPTED_TEXT_PREFS_NAME = "zchat_decrypted_text_encrypted"  // ratcheted-msg plaintext cache (AES256-GCM)
        private const val MESSAGE_REQUEST_PREFS_NAME = "zchat_message_requests_encrypted"  // #224 inbound OPEN requests (AES256-GCM)
        private const val BLOCKED_PUBKEYS_KEY = "__blocked_nostr_pubkeys__"  // newline-joined, within messageRequestPrefs
        private const val REQ_KEY_PREFIX = "req:"  // per-request entry key prefix
        private const val MAX_MESSAGE_REQUESTS = 100  // LRU cap on pending inbound OPEN requests
        private const val MAX_BLOCKED_PUBKEYS = 500   // LRU cap on rejected/blocked NOSTR pubkeys
        private const val DECLINED_REQUESTS_KEY = "__declined_requests__" // ZC-1464: JSON array, within messageRequestPrefs
        private const val NOSTR_SEEN_KEY = "ids"                       // newline-joined handled gift-wrap event ids

        // KI-24 part B: the list is kept by time (entries older than every relay's since leave), so this is only the
        // safety cap against a flood: ~20,000 × 76 bytes ≈ 1.5 MB at most. Was 2,000, which forgot ids a relay still
        // serves (relays keep a wrap about 30 days).
        private const val MAX_SEEN_NOSTR_EVENTS = 20_000

        // KI-24 part B: the seen list's floor (seconds) and each relay's since line, in the same file as the list.
        private const val NOSTR_SEEN_FLOOR_KEY = "floor_sec"
        private const val NOSTR_SINCE_KEY_PREFIX = "nostr_since:"

        // RELAY-HEALTH: the app-scope relay set file and its keys.
        private const val RELAY_SET_PREFS_NAME = "zchat_relay_set"
        private const val RELAY_SET_VERSION_KEY = "v"
        private const val RELAY_SET_VERSION = 1
        private const val RELAY_SET_SLOT1_KEY = "slot1"
        private const val RELAY_SET_SLOT2_KEY = "slot2"
        private const val RELAY_SET_COOLDOWN_KEY = "cooldown"
        private const val RELAY_SET_SWAPS_KEY = "swaps"
        private const val RELAY_SET_COMPAT_OUT_KEY = "compat_out"
        // #201 anti-flap: processed on-chain KEX/KEXACK txid LRU (newline-joined). A conversation sees
        // only a handful of KEX txs over its life, so a small cap is plenty.
        private const val KEX_SEEN_KEY = "ids"
        private const val MAX_SEEN_KEX_TXS = 500

        // KI-24 part A: the processed-KEX height floor, in the same file as the set.
        private const val KEX_SEEN_FLOOR_KEY = "floor_height"

        // KI-24 fixup G-KI24-2: the height the floor may not pass (KexSeenLedger.ceilingHeight), in the same file.
        private const val KEX_SEEN_CEILING_KEY = "floor_ceiling"

        // ZC-074: what the hard cap removed uncovered (KexSeenLedger's overflow), in the same file; absent when empty.
        private const val KEX_SEEN_OVERFLOW_KEY = "overflow"
        // D2 — automatic-KEXACK spend-budget timestamps (own key within kexSeenPrefs).
        private const val KEXACK_BUDGET_KEY = "kexack_budget_ts"

        // KI-23 — automatic key-grant budget timestamps (own key within kexSeenPrefs, own lock).
        private const val GROUP_KEY_GRANT_BUDGET_KEY = "gkgrant_budget_ts"
        // ZBOOT signature dedup LRU — its OWN store so ZBOOT churn never evicts KEX/KEXACK txids above.
        private const val BOOT_SEEN_KEY = "ids"
        private const val MAX_SEEN_BOOT_SIGS = 500

        // Remote-kill replay guard — acted-on kill signal txids, in their OWN store so nothing
        // else's churn can evict one. A wallet sees at most a handful of real kill signals ever.
        private const val KILL_SEEN_KEY = "ids"
        private const val MAX_SEEN_KILL_SIGNALS = 100
        // Group chat prefs. The plain info/members/drafts files are OLD migration sources; live data
        // is in the "_encrypted" stores (info+roster = conversation-graph metadata, drafts = plaintext).
        private const val GROUP_INFO_PREFS_NAME = "zchat_group_info"     // OLD: groupId -> GroupInfo JSON
        private const val GROUP_MEMBERS_PREFS_NAME = "zchat_group_members" // OLD: groupId -> members JSON array
        private const val GROUP_INFO_PREFS_NAME_ENC = "zchat_group_info_encrypted"

        /**
         * KI-25 D1 (ZC-598) — the mark in 2.12.4's old plain group file that the one look was tried: the Boolean true
         * under this key. A group id never contains a colon, so no invite can put a record under it.
         */
        private const val ADMIN_LOOK_MARK = "admin:look_tried"
        private const val GROUP_MEMBERS_PREFS_NAME_ENC = "zchat_group_members_encrypted"
        private const val GROUP_KEYS_PREFS_NAME = "zchat_group_keys_encrypted"  // groupId_epoch -> group key (AES256-GCM encrypted)
        private const val GROUP_DRAFT_PREFS_NAME = "zchat_group_drafts"  // OLD: groupId -> draft text
        private const val GROUP_DRAFT_PREFS_NAME_ENC = "zchat_group_drafts_encrypted"
        private const val GROUP_SEQ_PREFS_NAME = "zchat_group_seq"       // groupId -> sequence number
        // OLD plain files — kept ONLY as the migration source (see migrateMessagePlaintextPrefs); no longer written.
        private const val GROUP_MSG_PREFS_NAME = "zchat_group_messages" // groupId -> messages JSON array
        private const val PENDING_MSG_PREFS_NAME = "zchat_pending_messages" // messageId -> PendingMessageData JSON
        // Encrypted stores (AES256-GCM) — hold DECRYPTED message plaintext, so never at rest in the clear.
        private const val GROUP_MSG_PREFS_NAME_ENC = "zchat_group_messages_encrypted"
        private const val PENDING_MSG_PREFS_NAME_ENC = "zchat_pending_messages_encrypted"
        private const val NOSTR_REACTION_PREFS_NAME = "zchat_nostr_reactions" // OLD: targetMsgId -> reactions (migration source)
        private const val NOSTR_REACTION_PREFS_NAME_ENC = "zchat_nostr_reactions_encrypted"
        private const val MAX_REACTIONS_PER_TARGET = 64

        // LRU cap on the number of DISTINCT reaction target ids persisted. addNostrReaction writes one
        // prefs key per targetId, and a malicious peer can send ZREACTs for unbounded FABRICATED
        // targetIds — unbounded prefs growth = storage exhaustion / ANR. Evict the targets whose newest
        // reaction is oldest once at/over cap.
        private const val MAX_REACTION_TARGETS = 1000
        private const val CALL_LOG_PREFS_NAME = "zchat_call_log" // id -> CallLogMessageData JSON
        // D8: PLAIN file (must survive the encrypted-store corruption self-heal) — see ratchetFloorPrefs.
        private const val RATCHET_COUNTER_FLOOR_PREFS_NAME = "zchat_ratchet_counter_floor"
        private const val UNROUTABLE_MSG_PREFS_NAME = "zchat_unroutable_messages" // ZC-167: only emptied, never written
        private const val GROUP_IDS_KEY = "group_ids"                    // Set of all group IDs
        private const val GROUP_EPOCH_PREFIX = "epoch_"                  // Prefix for epoch storage
        private const val GROUP_SELF_CREATED_PREFIX = "grpcreator_"      // Prefix: did WE create this group
        private const val GROUP_REMOVAL_FLOOR_PREFIX = "grpremfloor_"    // D4: epoch the last kick rotated INTO
        private const val GROUP_REMOVAL_KICKS_PREFIX = "grpremkicks_" // R9-3: each removal's epoch -> its height
        private const val GROUP_KEY_REQUEST_GRANTED_PREFIX = "grpkeyreqgranted_" // HS-5: grant paid for this request

        // KI-23: paid deliveries of one group key to one member; the suffix is a hash of the grant key (no address).
        private const val GROUP_KEY_GRANT_PAID_PREFIX = "gk_grant_paid:"
        // E2E key prefixes
        private const val E2E_ENABLED_PREFIX = "e2e_enabled_"
        private const val E2E_OUR_PUBLIC_PREFIX = "e2e_our_pub_"
        private const val E2E_OUR_PRIVATE_PREFIX = "e2e_our_priv_"
        private const val E2E_PEER_PUBLIC_PREFIX = "e2e_peer_pub_"
        private const val E2E_KEY_VERSION_PREFIX = "e2e_key_ver_"

        // ZC-545: the stored form of a remembered refused key ("keyoffer:<peer>").
        private const val KEY_OFFER_VERSION = 1
        private const val KEY_OFFER_FIELDS = 6

        // ZC-545: serializes every write of a contact's key-changed flag and of its remembered refused key. In the
        // companion (one per process, like the store itself), so it exists however an instance was made.
        private val keyOfferLock = Any()

        // ZC-634: the claim mark's entry format, and the most of a held ZBOOT it keeps.
        private const val KEY_CLAIM_VERSION = 1
        private const val KEY_CLAIM_HELD_MAX = 1_024

        // ZC-1346: "acceptheldboot:<peer>" = a ZBOOT held beside the Accept hold, in the same file as the hold.
        private const val ACCEPT_WAITING_BOOT_PREFIX = "acceptheldboot:"

        // ZC-1349: lines of "<event id> <arrival seconds>", oldest dropped first.
        private const val HELD_CONNECTION_ARRIVALS_KEY = "heldconnarrivals"
        private const val HELD_CONNECTION_ARRIVALS_CAP = 512

        // W-72 (SPEC_DECISIONS D1, D2): the key store's done-flag of the pre-update marks and its first-complete-read marker.
        private const val LEGACY_KEY_DONE = "legacykey_done"

        // W-72 (D4): the probe record keeps at most this many of the probe's transactions (the latest), and the id length.
        private const val LEGACY_PROBE_MAX_TXS = 16
        private const val CONV_ID_FORMAT_LENGTH = 8
        private const val LEGACY_HISTORY_READ = "legacykey_history_read"
        // D1-recovery Stage 3: key provenance marker ("random" | "seed-v3:<idx>") + the monotonic
        // per-account seed-derivation index allocator (see SeedDerivedE2EKeyProvider).
        private const val E2E_KEY_PROVENANCE_PREFIX = "e2e_key_provenance_"
        private const val E2E_SEED_KEY_NEXT_INDEX_KEY = "e2e_seed_key_next_index"
        // R5 send gate: set on a seed-restore key re-derivation, gates sends until the counter
        // floor is re-established so a post-restore send can't reuse a burned AES-GCM nonce.
        private const val E2E_RECOVERY_FLOOR_PENDING_PREFIX = "e2e_recovery_floor_pending_"
        private const val KEY_ACKNOWLEDGED_MESSAGE_COST = "acknowledged_message_cost"
        private const val KEY_CONSENTED_HANDSHAKE_SPEND = "consented_handshake_spend"

        // R5-1: the members of one group the user agreed to pay a message memo for.
        private const val KEY_GROUP_MESSAGE_SPEND_CONSENT_PREFIX = "group_msg_spend_consent_"

        // Round 8 MA1 — member-announce records in the group-members store (never a bare group id: ':' is not in one).
        private const val KEY_GROUP_ANN_STATE_PREFIX = "group_ann_state:"

        // ZC-704 (KI-25 R19): the reserved counter key of flushGroupStores and groupKeyStoreCommitFailed, in the
        // group-key store, the group-info store and the main file. Only those two methods read it.
        private const val FLUSH_SEQ_KEY = "flush:seq"
        private const val KEY_GROUP_ANN_LEDGER_PREFIX = "group_ann_ledger:"
        private const val KEY_GROUP_ANN_COUNTER_PREFIX = "group_ann_counter:"
        private const val KEY_GP1_CAPABLE = "group_gp1_capable"
        private val GP1_CAPABLE_LOCK = Any()
        private const val PEER_IRONWOOD_CAP_PREFIX = "peer_cap_ironwood_"
        private val PEER_IRONWOOD_CAP_LOCK = Any()
        private const val PEER_ZATT_CAP_PREFIX = "peer_cap_zatt1_"
        private val PEER_ZATT_CAP_LOCK = Any()
        private const val KEY_DISMISSED_OLD_BALANCE_PREFIX = "dismissed_old_balance_"
        private const val KEY_SHIELDED_REMINDER_HIDDEN_PREFIX = "shielded_reminder_hidden_"
        private const val KEY_HIDDEN_MESSAGES = "hidden_message_ids"
        private const val KEY_EXPIRED_MESSAGES = "expired_message_ids"
        private const val KEY_PAID_REQUESTS = "paid_payment_request_ids"

        // Round 8 P2: JSON object, request id -> started fulfil record (FulfilStarted). Same store as the paid set.
        private const val KEY_FULFIL_STARTED = "fulfil_started_records"
        private const val KEY_USER_STATUS = "user_status"

        // ZC-283: the status broadcast record (modePrefs).
        private const val KEY_STATUS_BROADCAST_RECORD = "status_bcast_v1"
        private const val KEY_USER_STATUS_TIMESTAMP = "user_status_timestamp"
        private const val KEY_CUSTOM_MEMO_TEMPLATES = "custom_memo_templates"
        private const val KEY_FONT_SIZE_SCALE = "font_size_scale"
        // Destroy / Remote Kill keys
        private const val KEY_DESTROY_PIN = "destroy_pin"
        private const val KEY_DESTROY_PIN_FORMAT_V2 = "destroy_pin_format_v2"
        private const val KEY_REMOTE_KILL_PHRASE_FORMAT_V2 = "remote_kill_phrase_format_v2"
        private const val KEY_PIN_FAIL_COUNT = "destroy_pin_fail_count"
        private const val KEY_PIN_VIOLATIONS = "destroy_pin_violations"
        private const val KEY_PIN_LOCKOUT_ELAPSED = "destroy_pin_lockout_elapsed"
        private const val KEY_PIN_LOCKOUT_WALL = "destroy_pin_lockout_wall"

        // DESTROY-ADV-W (ZC-722): what Destroy All may keep, comma-separated, beside the Destroy PIN (e2ePrefs).
        private const val KEY_DESTROY_KEEP = "destroy_keep_v1"

        // ZC1013-X2: the automatic setups answered "Not now" (encrypted E2E store; new in 2.13.0).
        private const val KEY_DECLINED_AUTOMATIC_SETUPS = "declined_automatic_setups_v1"
        private val DESTROY_KEEP_VALUES = setOf("contacts")
        // One-shot marker — once true, all subsequent reads/writes for PIN + kill-phrase
        // go through e2ePrefs (encrypted at rest). Persisted in e2ePrefs itself so a tampered
        // plain prefs cannot reset it.
        private const val KEY_DESTROY_STORAGE_V3 = "destroy_storage_v3_done"
        private const val KEY_REMOTE_KILL_ENABLED = "remote_kill_enabled"
        private const val KEY_REMOTE_KILL_PHRASE_HASH = "remote_kill_phrase_hash"  // SHA-256 hash, not plaintext
        private const val KEY_REMOTE_KILL_AMOUNT = "remote_kill_amount"

        // The pre-v3 keys migrateDestroyStorageIfNeeded copies from `prefs`.
        private val LEGACY_DESTROY_KEYS =
            listOf(
                KEY_DESTROY_PIN,
                KEY_DESTROY_PIN_FORMAT_V2,
                KEY_REMOTE_KILL_PHRASE_HASH,
                KEY_REMOTE_KILL_PHRASE_FORMAT_V2,
                KEY_PIN_FAIL_COUNT,
                KEY_PIN_VIOLATIONS,
                KEY_PIN_LOCKOUT_ELAPSED,
                KEY_PIN_LOCKOUT_WALL,
            )

        // Height floor below which no transaction can be a kill signal. See getRemoteKillArmedHeight.
        private const val KEY_REMOTE_KILL_ARMED_HEIGHT = "remote_kill_armed_height"
        private const val DEFAULT_REMOTE_KILL_AMOUNT = 1337L // 0.00001337 ZEC - unique amount
        // Notification Privacy
        private const val KEY_NOTIFICATION_PRIVACY = "notification_privacy"
        // Notification Settings
        private const val KEY_NOTIFICATION_SOUND = "notification_sound_enabled"
        private const val KEY_NOTIFICATION_VIBRATION = "notification_vibration_enabled"
        private const val KEY_MUTED_CONVERSATIONS = "muted_conversations"
        // Conversation read state: "lastread:<peerAddress>" -> epoch millis (unread-badge support)
        private const val LAST_READ_PREFIX = "lastread:"
        private const val DISAPPEAR_TTL_PREFIX = "disappear_ttl:"
        private const val DISAPPEAR_SINCE_PREFIX = "disappear_since:"
        private const val EXPIRY_ANCHOR_PREFIX = "expiry_anchor:"
        // Worker Sync
        private const val KEY_LAST_WORKER_SYNC_TIMESTAMP = "last_worker_sync_timestamp"
        // Seed Backup Reminder
        private const val KEY_HAS_BACKED_UP_SEED = "has_backed_up_seed"
        private const val KEY_FIRST_OUTGOING_MSG_TS = "first_outgoing_msg_timestamp"
        private const val KEY_LAST_BACKUP_REMINDER_TS = "last_backup_reminder_timestamp"
        private const val KEY_NOSTR_ROTATION_INDEX = "nostr_rotation_index"

        // M1: our own relay keys (modePrefs), and the one-time relay-key repair's done flag (modePrefs, commit()).
        private const val KEY_OWN_NOSTR_PUBKEYS = "own_nostr_pubkeys"
        private const val KEY_NOSTR_BIND_REPAIR_DONE = "nostrbind_repair_v1"
        private const val KEY_LAST_ROTATION_REMINDER_TS = "last_rotation_reminder_timestamp"
        private const val KEY_BACKUP_REMINDER_COUNT = "backup_reminder_count"
    }

    override fun hasAcknowledgedMessageCost(): Boolean {
        return prefs.getBoolean(KEY_ACKNOWLEDGED_MESSAGE_COST, false)
    }

    override fun setAcknowledgedMessageCost() {
        prefs.edit().putBoolean(KEY_ACKNOWLEDGED_MESSAGE_COST, true).apply()
    }

    override fun hasConsentedToHandshakeSpend(): Boolean =
        prefs.getBoolean(KEY_CONSENTED_HANDSHAKE_SPEND, false)

    override fun setConsentedToHandshakeSpend(): Boolean =
        // R5-2: commit(), not apply() — the queued automatic spends are released the moment this returns, so
        // the consent they are made under must be on disk by then, not whenever the async writer gets to it.
        // Its result is returned so the caller can release nothing.
        prefs.edit().putBoolean(KEY_CONSENTED_HANDSHAKE_SPEND, true).commit()

    override fun clearConsentedToHandshakeSpend(): Boolean =
        // R4-1: commit(), not apply() — this runs immediately before a move of the old balance is sent, and
        // a kill in that window must not leave consent standing on disk. Its result is returned so the
        // caller can refuse to send the move.
        prefs.edit().remove(KEY_CONSENTED_HANDSHAKE_SPEND).commit()

    // ZC1013-X2: the marks name contacts' addresses, so they live in the encrypted E2E store, never in plain prefs. A new
    // key: 2.12.4 never wrote it (absent = no marks), and a build that does not know it ignores it.
    override fun getDeclinedAutomaticSetups(): Set<String> =
        try {
            e2ePrefs.getStringSet(KEY_DECLINED_AUTOMATIC_SETUPS, null)?.toSet().orEmpty()
        } catch (e: ClassCastException) {
            emptySet()
        }

    override fun setDeclinedAutomaticSetups(marks: Set<String>): Boolean =
        if (marks.isEmpty()) {
            e2ePrefs.edit().remove(KEY_DECLINED_AUTOMATIC_SETUPS).commit()
        } else {
            e2ePrefs.edit().putStringSet(KEY_DECLINED_AUTOMATIC_SETUPS, HashSet(marks)).commit()
        }

    // R5-1 — the consent holds MEMBER ADDRESSES, which are the same conversation-graph metadata as the
    // roster (MEDIUM-011), so it lives in the ENCRYPTED group-members store, never in plain prefs. Its
    // key is prefixed, so it can never collide with a roster entry (those are keyed by the bare groupId).
    override fun getGroupMessageSpendConsent(groupId: String): Set<String> =
        groupMembersPrefs.getStringSet(KEY_GROUP_MESSAGE_SPEND_CONSENT_PREFIX + groupId, null).orEmpty()

    override fun setGroupMessageSpendConsent(
        groupId: String,
        payees: Set<String>
    ): Boolean =
        // commit(), not apply(): the send that follows spends ZEC, and a kill in between must not leave
        // the user paying again for a set they already agreed to. A copy is stored — SharedPreferences
        // must never be handed a set the caller can still mutate.
        groupMembersPrefs
            .edit()
            .putStringSet(KEY_GROUP_MESSAGE_SPEND_CONSENT_PREFIX + groupId, payees.toSet())
            .commit()

    override fun getDismissedOldBalance(accountKey: String): Long? =
        (KEY_DISMISSED_OLD_BALANCE_PREFIX + accountKey).let { key ->
            if (prefs.contains(key)) prefs.getLong(key, 0L) else null
        }

    override fun setDismissedOldBalance(accountKey: String, orchardZatoshi: Long) {
        prefs.edit().putLong(KEY_DISMISSED_OLD_BALANCE_PREFIX + accountKey, orchardZatoshi).apply()
    }

    override fun isShieldedReminderHidden(peerAddress: String): Boolean =
        prefs.getBoolean(shieldedReminderHiddenKey(peerAddress), false)

    override fun setShieldedReminderHidden(peerAddress: String, hidden: Boolean) {
        val key = shieldedReminderHiddenKey(peerAddress)
        prefs.edit().apply { if (hidden) putBoolean(key, true) else remove(key) }.apply()
    }

    private fun shieldedReminderHiddenKey(peerAddress: String): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(peerAddress.toByteArray(Charsets.UTF_8))
        return KEY_SHIELDED_REMINDER_HIDDEN_PREFIX + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    override fun getHiddenMessageIds(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_MESSAGES, emptySet()) ?: emptySet()
    }

    override fun hideMessage(messageId: String) {
        val current = getHiddenMessageIds().toMutableSet()
        current.add(messageId)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
        // ZC-946 part 4 (review 19 Oct, finding 3): the chat list drops a taken row the user hid.
        ZchatPreferences.markPendingRowsChanged()
    }

    override fun getPaidRequestIds(): Set<String> {
        return prefs.getStringSet(KEY_PAID_REQUESTS, emptySet()) ?: emptySet()
    }

    override fun markRequestPaid(requestMessageId: String): Boolean =
        // One read-modify-write at a time: the fulfil and the history scan-back can mark at once, and a lost
        // update here is a lost paid marker.
        synchronized(this) {
            val current = getPaidRequestIds().toMutableSet()
            current.add(requestMessageId)
            prefs.edit().putStringSet(KEY_PAID_REQUESTS, current).commit()
        }

    override fun unmarkRequestPaid(requestMessageId: String) {
        synchronized(this) {
            val current = getPaidRequestIds().toMutableSet()
            if (current.remove(requestMessageId) && !prefs.edit().putStringSet(KEY_PAID_REQUESTS, current).commit()) {
                Log.w("ZCHAT_PAY", "a withdrawn paid marker could not be saved; the request may show Paid after a restart")
            }
        }
    }

    override fun markFulfilStarted(
        requestMessageId: String,
        attemptHeight: Long,
        peerAddress: String,
        amountZatoshi: Long,
        selfAddress: String,
        startedAtMillis: Long,
    ): Boolean =
        synchronized(this) {
            val paid = getPaidRequestIds().toMutableSet()
            paid.add(requestMessageId)
            val records = readFulfilStartedJson()
            records.put(
                requestMessageId,
                org.json
                    .JSONObject()
                    .put("attemptHeight", attemptHeight)
                    .put("peerHash", ZchatPreferences.fulfilAddressHash(peerAddress))
                    .put("amountZat", amountZatoshi)
                    .put("selfHash", ZchatPreferences.fulfilAddressHash(selfAddress))
                    .put("startedAt", startedAtMillis),
            )
            prefs
                .edit()
                .putStringSet(KEY_PAID_REQUESTS, paid)
                .putString(KEY_FULFIL_STARTED, records.toString())
                .commit()
        }

    override fun clearFulfilStarted(requestMessageId: String) {
        synchronized(this) {
            val records = readFulfilStartedJson()
            if (records.remove(requestMessageId) != null &&
                !prefs.edit().putString(KEY_FULFIL_STARTED, records.toString()).commit()
            ) {
                // Fail safe: a record left behind can only lead to a withdrawal that its own checks still gate.
                Log.w("ZCHAT_PAY", "a finished fulfil record could not be cleared")
            }
        }
    }

    override fun getFulfilStarted(): Map<String, ZchatPreferences.FulfilStarted> =
        synchronized(this) {
            val records = readFulfilStartedJson()
            buildMap {
                for (id in records.keys()) {
                    val r = records.optJSONObject(id) ?: continue
                    put(id, ZchatPreferences.decodeFulfilStarted(r) ?: continue)
                }
            }
        }

    override fun recordFulfilStored(
        requestMessageId: String,
        startedAtMillis: Long,
        stored: List<co.electriccoin.zcash.ui.common.datasource.StoredTransaction>,
    ): Boolean =
        synchronized(this) {
            val records = readFulfilStartedJson()
            val current =
                records.optJSONObject(requestMessageId)?.let { ZchatPreferences.decodeFulfilStarted(it) }
                    ?: return@synchronized false
            if (current.startedAtMillis != startedAtMillis) return@synchronized false
            val withIds =
                current.copy(
                    txIds = (current.txIds + stored.map { it.txId }).distinct(),
                    expiries = current.expiries + stored.mapNotNull { s -> s.expiryHeight?.let { s.txId to it } },
                )
            records.put(requestMessageId, ZchatPreferences.encodeFulfilStarted(withIds))
            prefs.edit().putString(KEY_FULFIL_STARTED, records.toString()).commit()
        }

    @Suppress("SwallowedException")
    private fun readFulfilStartedJson(): org.json.JSONObject =
        prefs.getString(KEY_FULFIL_STARTED, null)?.let {
            try {
                org.json.JSONObject(it)
            } catch (e: org.json.JSONException) {
                Log.w("ZCHAT_PAY", "fulfil started records unreadable; none are withdrawn")
                null
            }
        } ?: org.json.JSONObject()

    override fun hideMessages(messageIds: Set<String>) {
        val current = getHiddenMessageIds().toMutableSet()
        current.addAll(messageIds)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
        // ZC-946 part 4 (review 19 Oct, finding 3): the chat list drops a taken row the user hid.
        ZchatPreferences.markPendingRowsChanged()
    }

    override fun unhideMessage(messageId: String) {
        val current = getHiddenMessageIds().toMutableSet()
        current.remove(messageId)
        prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, current).apply()
        // ZC-946 part 4 (review 19 Oct, finding 3): the chat list drops a taken row the user hid.
        ZchatPreferences.markPendingRowsChanged()
    }

    override fun getExpiredMessageIds(): Set<String> = prefs.getStringSet(KEY_EXPIRED_MESSAGES, emptySet()) ?: emptySet()

    override fun markMessagesExpired(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        val current = getExpiredMessageIds().toMutableSet()
        if (!current.addAll(messageIds)) return
        prefs.edit().putStringSet(KEY_EXPIRED_MESSAGES, current).apply()
    }

    // ==========================================
    // USER STATUS IMPLEMENTATION
    // ==========================================

    override fun getUserStatus(): String {
        return prefs.getString(KEY_USER_STATUS, "") ?: ""
    }

    override fun setUserStatus(status: String) {
        prefs.edit()
            .putString(KEY_USER_STATUS, status)
            .putLong(KEY_USER_STATUS_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    override fun getUserStatusTimestamp(): Long {
        return prefs.getLong(KEY_USER_STATUS_TIMESTAMP, 0L)
    }

    override fun getPeerStatus(peerAddress: String): String? {
        return peerStatusPrefs.getString(peerAddress, null)
    }

    override fun setPeerStatus(peerAddress: String, status: String) {
        peerStatusPrefs.edit().putString(peerAddress, status).apply()
    }

    override fun getAllPeerStatuses(): Map<String, String> {
        return peerStatusPrefs.all
            .filterValues { it is String }
            .mapValues { it.value as String }
    }

    // ==========================================
    // MEMO TEMPLATES IMPLEMENTATION
    // ==========================================

    override fun getCustomMemoTemplates(): List<String> {
        return getAllCustomTemplateJson().toList()
    }

    override fun saveCustomMemoTemplate(templateJson: String) {
        val current = getAllCustomTemplateJson().toMutableSet()
        // Remove any existing template with same ID (update)
        val templateId = extractTemplateId(templateJson)
        if (templateId != null) {
            current.removeAll { extractTemplateId(it) == templateId }
        }
        current.add(templateJson)
        prefs.edit().putStringSet(KEY_CUSTOM_MEMO_TEMPLATES, current).apply()
    }

    override fun removeCustomMemoTemplate(templateId: String) {
        val current = getAllCustomTemplateJson().toMutableSet()
        current.removeAll { extractTemplateId(it) == templateId }
        prefs.edit().putStringSet(KEY_CUSTOM_MEMO_TEMPLATES, current).apply()
    }

    override fun getAllCustomTemplateJson(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_MEMO_TEMPLATES, emptySet()) ?: emptySet()
    }

    /**
     * Extract template ID from JSON string (simple parsing).
     * Format expected: {"id":"...", ...}
     */
    private fun extractTemplateId(json: String): String? {
        val regex = """"id"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    // ==========================================
    // FONT SIZE IMPLEMENTATION
    // ==========================================

    override fun getFontSizeScale(): Float {
        return prefs.getFloat(KEY_FONT_SIZE_SCALE, 1.0f)
    }

    override fun setFontSizeScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE_SCALE, scale).apply()
    }

    // ==========================================
    // CONTACT NICKNAMES IMPLEMENTATION
    // ==========================================

    override fun getNickname(address: String): String? {
        return nicknamePrefs.getString(address, null)
    }

    override fun setNickname(address: String, nickname: String) {
        if (nickname.isBlank()) {
            // Clear nickname if empty
            nicknamePrefs.edit().remove(address).apply()
        } else {
            nicknamePrefs.edit().putString(address, nickname.trim()).apply()
        }
    }

    override fun getDisplayName(address: String): String {
        // Return nickname if set, otherwise truncate address
        val nickname = getNickname(address)
        if (!nickname.isNullOrBlank()) {
            return nickname
        }
        // Truncate: first 8 chars + "..." + last 6 chars
        return if (address.length > 20) {
            "${address.take(8)}...${address.takeLast(6)}"
        } else {
            address
        }
    }

    override fun getAllNicknames(): Map<String, String> {
        return nicknamePrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun markFileViewed(fileHash: String) {
        viewOncePrefs.edit().putString(fileHash, "1").apply()
    }

    override fun isFileViewed(fileHash: String): Boolean =
        viewOncePrefs.contains(fileHash)

    override fun getAllViewedFiles(): Set<String> = viewOncePrefs.all.keys

    // --- Conversation mode ---

    private fun modeKey(peer: String) = "mode:$peer"
    private fun modeExplicitKey(peer: String) = "modeexplicit:$peer"
    private fun modeSinceKey(peer: String) = "modesince:$peer"

    // ZC-1478: our own last switch time and the "moved to a paid mode by the contact" mark, beside the mode.
    private fun ownModeSinceKey(peer: String) = "ownmodesince:$peer"

    private fun movedToPaidKey(peer: String) = "movedtopaid:$peer"

    private fun pubkeyKey(peer: String) = "pubkey:$peer"
    private fun relayKey(peer: String) = "relay:$peer"
    private fun bootSentKey(peer: String) = "bootsent:$peer"
    private val keyHasSeenModeIntro = "hasSeenModeIntro"
    private val keyRenamedOldTeamContact = "renamedOldTeamContact"

    override fun getConversationMode(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode {
        val name = modePrefs.getString(modeKey(peerAddress), null) ?: return co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.DEFAULT
        return runCatching { co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.valueOf(name) }
            .getOrDefault(co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.DEFAULT)
    }

    override fun getConversationModeOrNull(peerAddress: String): co.electriccoin.zcash.ui.screen.chat.model.ConversationMode? {
        val name = modePrefs.getString(modeKey(peerAddress), null) ?: return null
        return runCatching { co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.valueOf(name) }.getOrNull()
    }

    override fun setConversationMode(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode?) {
        modePrefs.edit().apply {
            if (mode == null) {
                remove(modeKey(peerAddress))
                // Clearing the mode also clears the "user explicitly chose this" pin — a cleared chat
                // reverts to full default behavior (smart defaults, auto-upgrade, ZMODE auto-adopt).
                remove(modeExplicitKey(peerAddress))
                // ZC-1478: and the mode's own records.
                remove(ownModeSinceKey(peerAddress))
                remove(movedToPaidKey(peerAddress))
            } else {
                putString(modeKey(peerAddress), mode.name)
            }
        }.apply()
        // The mode drives transport routing + the chat header's status word. Bump the shared tick so
        // an ALREADY-OPEN chat detail re-reads it immediately (e.g. the receiver-side silent Tunnel
        // adoption on an inbound ZBOOT) instead of showing "shielded" until the next reopen.
        bumpE2EHandshakeTick()
    }

    override fun isConversationModeExplicit(peerAddress: String): Boolean =
        modePrefs.getBoolean(modeExplicitKey(peerAddress), false)

    override fun setConversationModeExplicit(peerAddress: String) {
        modePrefs.edit().putBoolean(modeExplicitKey(peerAddress), true).apply()
    }

    override fun clearConversationModeExplicit(peerAddress: String) {
        modePrefs.edit().remove(modeExplicitKey(peerAddress)).apply()
    }

    override fun getModeSwitchRecord(peerAddress: String): ZchatPreferences.ModeSwitchRecord? {
        val since = modePrefs.getLong(ownModeSinceKey(peerAddress), 0L)
        val moved = modePrefs.getBoolean(movedToPaidKey(peerAddress), false)
        return if (since == 0L && !moved) null else ZchatPreferences.ModeSwitchRecord(since, moved)
    }

    override fun setOwnModeChangeSince(peerAddress: String, sinceMillis: Long) {
        modePrefs.edit().putLong(ownModeSinceKey(peerAddress), sinceMillis).apply()
    }

    // commit(): the mark guards money (M1), so it is on disk before the adopted mode is written.
    override fun setModeMovedToPaidByPeer(peerAddress: String, moved: Boolean) {
        if (modePrefs.getBoolean(movedToPaidKey(peerAddress), false) == moved) return
        val edit = modePrefs.edit()
        if (moved) edit.putBoolean(movedToPaidKey(peerAddress), true) else edit.remove(movedToPaidKey(peerAddress))
        edit.commit()
    }

    @Synchronized
    override fun advancePeerModeChangeSince(peerAddress: String, sinceMillis: Long): Boolean {
        // Monotonic: reject a non-newer since → last-writer-wins + idempotent under relay-replay/rescan.
        if (sinceMillis <= modePrefs.getLong(modeSinceKey(peerAddress), 0L)) return false
        modePrefs.edit().putLong(modeSinceKey(peerAddress), sinceMillis).apply()
        return true
    }

    private fun modeNoteKey(peer: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode) = "modenote:$peer:${mode.name}"
    override fun hasSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode): Boolean =
        modePrefs.getBoolean(modeNoteKey(peerAddress, mode), false)
    override fun setSeenModeSecurityNote(peerAddress: String, mode: co.electriccoin.zcash.ui.screen.chat.model.ConversationMode) {
        modePrefs.edit().putBoolean(modeNoteKey(peerAddress, mode), true).apply()
    }

    override fun getPeerNostrPubkey(peerAddress: String): String? = modePrefs.getString(pubkeyKey(peerAddress), null)
    override fun setPeerNostrPubkey(peerAddress: String, pubkeyHex: String?) {
        modePrefs.edit().apply {
            if (pubkeyHex == null) remove(pubkeyKey(peerAddress)) else putString(pubkeyKey(peerAddress), pubkeyHex)
        }.apply()
    }

    override fun findPeerByNostrPubkey(pubkeyHex: String): String? {
        // Scan modePrefs.all for "pubkey:<peer>" entries; typically O(small). M1: collect every match, then choose one by
        // a fixed rule. The first match of a HashMap copy (the old rule) depended on the key strings, so a key bound to
        // a real address and to a broken copy of it could route every relay message to the copy.
        val target = pubkeyHex.lowercase()
        val all = modePrefs.all
        val matches =
            all.mapNotNull { (key, value) ->
                if (key.startsWith("pubkey:") && (value as? String)?.lowercase() == target) key.removePrefix("pubkey:") else null
            }
        if (matches.size <= 1) return matches.firstOrNull()
        Log.w("ZCHAT_NOSTR", "one relay key is bound to ${matches.size} addresses; routing to one by rule")
        return NostrPeerBinding.choose(matches, nostrBindingFacts(all))
    }

    /** M1 — what NostrPeerBinding.choose ranks by: a saved name, a stored mode, the most recent stored row. */
    private fun nostrBindingFacts(modeEntries: Map<String, *>): (String) -> NostrPeerBinding.Facts {
        val lastRow by lazy {
            getPendingMessages().groupBy { it.peerAddress }.mapValues { (_, rows) -> rows.maxOf { it.timestampMillis } }
        }
        return { peer ->
            NostrPeerBinding.Facts(
                isContact = getNickname(peer) != null,
                hasStoredMode = modeEntries.containsKey(modeKey(peer)),
                lastRowMillis = lastRow[peer] ?: 0L,
            )
        }
    }

    override fun registerOwnNostrPubkey(pubkeyHex: String) {
        val key = pubkeyHex.lowercase()
        synchronized(ownNostrKeysLock) {
            val current = modePrefs.getStringSet(KEY_OWN_NOSTR_PUBKEYS, null).orEmpty()
            if (key in current) return
            modePrefs.edit().putStringSet(KEY_OWN_NOSTR_PUBKEYS, HashSet(current) + key).apply()
        }
    }

    override fun isOwnNostrPubkey(pubkeyHex: String): Boolean =
        pubkeyHex.lowercase() in modePrefs.getStringSet(KEY_OWN_NOSTR_PUBKEYS, null).orEmpty()

    // M1 fixup: commit(), so a kill right after the bind still repairs at the next start. A failed write is logged; the
    // key already routes to the valid address, only the cut copy's binding and rows stay until a later bind re-arms it.
    @Synchronized
    override fun rearmNostrKeyRepair() {
        if (!modePrefs.edit().remove(KEY_NOSTR_BIND_REPAIR_DONE).commit()) {
            Log.w("ZCHAT_NOSTR", "relay key repair could not be re-armed")
        }
    }

    /**
     * M1 — the one-time repair of a relay key bound to more than one address (the M1 bug: a contact code whose address
     * was cut bound the contact's key to a broken copy of the address, and the contact's relay messages were stored
     * under that copy). For each such key: every bound address that fails NostrPeerBinding.isBindable is unbound, and
     * its delivered relay rows (inbound rows, and outgoing SENT rows; ids nmsg-/nostr-, no transaction id) move to the
     * address NostrPeerBinding.choose picks among the valid rest, keeping their ids and times, with one [noteText]
     * system note there. Nothing else moves: pending_ rows, FAILED or unsent rows and on-chain rows stay (a moved FAILED
     * row's Retry would pay in the other chat); nothing is deleted. Two VALID addresses on one key both stay bound (the
     * routing rule makes them stable). Runs once per wallet: the done flag is written with commit(); a bind that takes a
     * key from a lone broken holder re-arms it ([rearmNostrKeyRepair]), and the next start then finds that key on two
     * addresses. Returns the number of rows moved, or -1 when it had already run.
     */
    @Synchronized
    fun repairDoubleBoundNostrKeysOnce(noteText: () -> String): Int {
        if (modePrefs.getBoolean(KEY_NOSTR_BIND_REPAIR_DONE, false)) return -1
        val all = modePrefs.all
        val byKey =
            all.entries
                .filter { it.key.startsWith("pubkey:") && it.value is String }
                .groupBy({ (it.value as String).lowercase() }, { it.key.removePrefix("pubkey:") })
                .filterValues { it.size > 1 }
        var moved = 0
        var unbound = 0
        if (byKey.isNotEmpty()) {
            val facts = nostrBindingFacts(all)
            val rows = getPendingMessages()
            val modeEdit = modePrefs.edit()
            val movedRows = mutableListOf<ZchatPreferences.PendingMessageData>()
            val notedPeers = mutableSetOf<String>()
            for ((_, addresses) in byKey) {
                val (valid, broken) = addresses.partition { NostrPeerBinding.isBindable(it) }
                if (broken.isEmpty() || valid.isEmpty()) continue
                val keep = NostrPeerBinding.choose(valid, facts) ?: continue
                for (address in broken) {
                    modeEdit.remove(pubkeyKey(address))
                    unbound++
                    val relayRows = rows.filter { it.peerAddress == address && isDeliveredRelayRow(it) }
                    movedRows += relayRows.map { it.copy(peerAddress = keep) }
                    if (relayRows.isNotEmpty()) notedPeers += keep
                }
            }
            if (movedRows.isNotEmpty()) {
                val rowEdit = pendingMsgPrefs.edit()
                for (row in movedRows) rowEdit.putString(row.id, ZchatPreferences.PendingMessageData.toJson(row))
                val now = System.currentTimeMillis()
                for (peer in notedPeers) {
                    val note =
                        ZchatPreferences.PendingMessageData(
                            id = SysNotes.rowsMovedNoteId(peer),
                            text = noteText(),
                            timestampMillis = now,
                            peerAddress = peer,
                            isOutgoing = false,
                            isPending = false,
                            status = co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENT.name,
                        )
                    rowEdit.putString(note.id, ZchatPreferences.PendingMessageData.toJson(note))
                }
                check(rowEdit.commit()) { "moved rows could not be written" }
                moved = movedRows.size
            }
            check(modeEdit.commit()) { "relay key bindings could not be written" }
        }
        check(modePrefs.edit().putBoolean(KEY_NOSTR_BIND_REPAIR_DONE, true).commit()) { "repair flag could not be written" }
        Log.i(
            "ZCHAT_NOSTR",
            "relay key repair: ${byKey.size} keys on several addresses, $unbound broken bindings removed, $moved rows moved",
        )
        return moved
    }

    /** M1 — a row that reached a relay and belongs to no send in progress: inbound, or outgoing and SENT, with no transaction id. */
    private fun isDeliveredRelayRow(row: ZchatPreferences.PendingMessageData): Boolean =
        (row.id.startsWith("nmsg-") || row.id.startsWith("nostr-")) &&
            row.submittedTxIds.isEmpty() &&
            !row.isPending &&
            (!row.isOutgoing || row.status == co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENT.name)

    override fun getTunnelModePeers(): List<String> =
        modePrefs.all.mapNotNull { (key, value) ->
            key.takeIf {
                it.startsWith("mode:") &&
                    value == co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL.name
            }?.removePrefix("mode:")
        }

    override fun getRelayModePeers(): List<String> {
        val relayModes =
            setOf(
                co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL.name,
                co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.OPEN.name,
            )
        val peers = ArrayList<String>()
        for ((key, value) in modePrefs.all) {
            if (key.startsWith("mode:") && value in relayModes) peers += key.removePrefix("mode:")
        }
        return peers
    }

    // #37 — persisted accept/keep prompt for a peer's PAID-mode switch (see interface doc). Stored as
    // "NEWMODE|CURMODE|peerName" (name last + split-limit so a '|' in a display name can't corrupt the
    // mode fields). Declared AFTER modePrefs (property-initialization order).
    private fun pendingSwitchKey(peer: String) = "pendingswitch:$peer"

    private fun parsePendingModeSwitch(peer: String, raw: String): co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch? {
        val parts = raw.split('|', limit = 3)
        if (parts.size < 3) return null
        val newMode = runCatching { co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.valueOf(parts[0]) }.getOrNull() ?: return null
        val curMode = runCatching { co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.valueOf(parts[1]) }.getOrNull() ?: return null
        return co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch(
            peerAddress = peer,
            peerName = parts[2],
            newMode = newMode,
            currentMode = curMode,
        )
    }

    private fun loadAllPendingModeSwitchesRaw(): Map<String, co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch> =
        modePrefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("pendingswitch:")) return@mapNotNull null
            val peer = key.removePrefix("pendingswitch:")
            (value as? String)?.let { parsePendingModeSwitch(peer, it) }?.let { peer to it }
        }.toMap()

    private val _pendingModeSwitches =
        kotlinx.coroutines.flow.MutableStateFlow(loadAllPendingModeSwitchesRaw())
    override val pendingModeSwitches:
        kotlinx.coroutines.flow.StateFlow<Map<String, co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch>> =
        _pendingModeSwitches

    override fun setPendingModeSwitch(request: co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch) {
        modePrefs.edit()
            .putString(
                pendingSwitchKey(request.peerAddress),
                "${request.newMode.name}|${request.currentMode.name}|${request.peerName}",
            )
            .apply()
        _pendingModeSwitches.update { it + (request.peerAddress to request) }
    }

    override fun clearPendingModeSwitch(peerAddress: String) {
        modePrefs.edit().remove(pendingSwitchKey(peerAddress)).apply()
        _pendingModeSwitches.update { it - peerAddress }
    }

    // #37 — outbound ZMODE delivery retry store: "MODENAME|sinceMillis" per peer. The retry loop only
    // ever re-publishes over FREE NOSTR (never on-chain), so persisting/retrying can't spend ZEC.
    private fun pendingModeSyncKey(peer: String) = "pendingmodesync:$peer"

    override fun setPendingModeSync(peerAddress: String, modeName: String, sinceMillis: Long) {
        modePrefs.edit().putString(pendingModeSyncKey(peerAddress), "$modeName|$sinceMillis").apply()
    }

    override fun getPendingModeSyncs(): Map<String, ZchatPreferences.PendingModeSync> =
        modePrefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("pendingmodesync:")) return@mapNotNull null
            val peer = key.removePrefix("pendingmodesync:")
            val parts = (value as? String)?.split('|') ?: return@mapNotNull null
            if (parts.size != 2) return@mapNotNull null
            val since = parts[1].toLongOrNull() ?: return@mapNotNull null
            peer to ZchatPreferences.PendingModeSync(parts[0], since)
        }.toMap()

    override fun clearPendingModeSync(peerAddress: String) {
        modePrefs.edit().remove(pendingModeSyncKey(peerAddress)).apply()
    }

    override fun hasSeenNostrEvent(eventId: String): Boolean =
        synchronized(nostrSeenLock) { nostrSeenLedger.contains(eventId) }

    override fun hasSeenNostrEvent(
        eventId: String,
        wrapCreatedAtSec: Long?,
    ): Boolean = synchronized(nostrSeenLock) { nostrSeenLedger.isSeen(eventId, wrapCreatedAtSec) }

    // Where only the id is known, the entry is kept from now: later than the wrap's real time, so it stays longer.
    override fun markNostrEventSeen(eventId: String) = markNostrEventSeen(eventId, nowSec())

    override fun markNostrEventSeen(
        eventId: String,
        wrapCreatedAtSec: Long,
    ) {
        synchronized(nostrSeenLock) {
            val now = nowSec()
            val outcome = nostrSeenLedger.record(eventId, wrapCreatedAtSec, lowestNostrRelaySince(), now)
            // We only persist on a genuine first sighting.
            if (!outcome.changed) return
            outcome.capForcedOutNewestSec?.let { newest ->
                android.util.Log.w(
                    "NostrSeen",
                    "seen list over its cap: oldest entries dropped (newest dropped $newest), " +
                        "floor now ${nostrSeenLedger.floorSec}",
                )
            }
            persistNostrSeen()
        }
    }

    override fun unmarkNostrEventSeen(eventId: String) {
        synchronized(nostrSeenLock) {
            if (nostrSeenLedger.remove(eventId)) persistNostrSeen()
        }
    }

    override fun getNostrRelaySince(relayUrl: String): Long? =
        nostrSeenPrefs.getLong(nostrSinceKey(relayUrl), -1L).takeIf { it > 0 }

    override fun advanceNostrRelaySince(
        relayUrl: String,
        sinceSec: Long,
    ): Boolean =
        synchronized(nostrSeenLock) {
            val next =
                co.electriccoin.zcash.ui.nostr.RelaySinceLine
                    .nextStored(getNostrRelaySince(relayUrl), sinceSec, nowSec())
                    ?: return@synchronized false
            nostrSeenPrefs.edit().putLong(nostrSinceKey(relayUrl), next).apply()
            true
        }

    // F0 debug seam (KI-24): a small cap to reach the overflow in a phone check. Release: always the constant.
    private fun nostrSeenCap(): Long =
        FaultSeams.valueOf(FaultSeam.SEEN_NOSTR_CAP)?.coerceAtLeast(1) ?: MAX_SEEN_NOSTR_EVENTS.toLong()

    private fun nostrSinceKey(relayUrl: String): String =
        NOSTR_SINCE_KEY_PREFIX +
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(relayUrl.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)

    // The lowest since over the inbox relays, or null while one of them has none (nothing is old enough to drop yet).
    // RELAY-HEALTH: the relays the phone reads (the saved set), plus nos.lol while it is out of slot 2 (under 30 days):
    // its older since holds the prune down until it comes back and replays from there. Nothing saved: DEFAULT_RELAYS.
    private fun lowestNostrRelaySince(): Long? {
        val relays =
            RelaySetState.floorSetFrom(
                stored = getRelaySetState(),
                defaults = co.electriccoin.zcash.ui.nostr.NostrRelayPool.DEFAULT_RELAYS,
                catalog = co.electriccoin.zcash.ui.nostr.RelayCatalog.PUBLIC,
                nowMs = nowSec() * 1000,
            )
        val values = relays.map { getNostrRelaySince(it) }
        return if (values.any { it == null }) null else values.filterNotNull().minOrNull()
    }

    override fun nostrSeenFloorSec(): Long = synchronized(nostrSeenLock) { nostrSeenLedger.floorSec }

    override fun getRelaySetState(): RelaySetState? {
        val slot1 = relaySetPrefs.getString(RELAY_SET_SLOT1_KEY, null) ?: return null
        val slot2 = relaySetPrefs.getString(RELAY_SET_SLOT2_KEY, null) ?: return null
        val cooldowns =
            relaySetPrefs
                .getString(RELAY_SET_COOLDOWN_KEY, null)
                .orEmpty()
                .lines()
                .mapNotNull { line ->
                    val bar = line.lastIndexOf('|')
                    val until = if (bar > 0) line.substring(bar + 1).toLongOrNull() else null
                    if (until != null) line.substring(0, bar) to until else null
                }.toMap()
        val swaps =
            relaySetPrefs
                .getString(RELAY_SET_SWAPS_KEY, null)
                .orEmpty()
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }
        val compatOut = relaySetPrefs.getLong(RELAY_SET_COMPAT_OUT_KEY, -1L).takeIf { it > 0 }
        return RelaySetState(slot1, slot2, cooldowns, swaps, compatOut)
    }

    override fun saveRelaySetState(state: RelaySetState?) {
        val edit = relaySetPrefs.edit().clear()
        if (state != null) {
            edit
                .putInt(RELAY_SET_VERSION_KEY, RELAY_SET_VERSION)
                .putString(RELAY_SET_SLOT1_KEY, state.slot1)
                .putString(RELAY_SET_SLOT2_KEY, state.slot2)
                .putString(RELAY_SET_COOLDOWN_KEY, state.cooldownUntilMs.entries.joinToString("\n") { "${it.key}|${it.value}" })
                .putString(RELAY_SET_SWAPS_KEY, state.swapTimesMs.joinToString(","))
            state.compatOutSinceMs?.let { edit.putLong(RELAY_SET_COMPAT_OUT_KEY, it) }
        }
        edit.commit()
    }

    private fun loadNostrSeen(ledger: NostrSeenLedger) {
        ledger.load(
            nostrSeenPrefs.getString(NOSTR_SEEN_KEY, null),
            nostrSeenPrefs.getLong(NOSTR_SEEN_FLOOR_KEY, 0L),
            nowSec(),
        )
    }

    // The list and its floor in one edit, so a wipe, a wallet move or a chat copy always carries both.
    private fun persistNostrSeen() {
        nostrSeenPrefs
            .edit()
            .putString(NOSTR_SEEN_KEY, nostrSeenLedger.serialize())
            .putLong(NOSTR_SEEN_FLOOR_KEY, nostrSeenLedger.floorSec)
            .apply()
    }

    override fun hasProcessedKexTx(txId: String): Boolean =
        synchronized(kexSeenLock) { kexSeenLedger.contains(txId) }

    override fun hasProcessedKexTx(
        txId: String,
        minedHeight: Long?,
    ): Boolean = synchronized(kexSeenLock) { kexSeenLedger.isProcessed(txId, minedHeight) }

    override fun markKexTxProcessed(txId: String) = markKexTxProcessed(txId, null, null)

    override fun markKexTxProcessed(
        txId: String,
        minedHeight: Long?,
        scannedTip: Long?,
    ) {
        synchronized(kexSeenLock) {
            when (kexSeenLedger.record(txId, minedHeight, scannedTip)) {
                KexSeenLedger.Change.NONE -> Unit
                KexSeenLedger.Change.ADDED, KexSeenLedger.Change.HEIGHT_FILLED -> persistKexSeen(durable = false)
                KexSeenLedger.Change.FLOOR_RAISED -> {
                    // commit(), not apply(): the set lost entries, and only the floor now stands for them.
                    persistKexSeen(durable = true)
                    android.util.Log.d("ZCHAT_KEX", "processed-KEX floor raised to ${kexSeenLedger.floorHeight}")
                }
                // ZC-074: commit(), not apply(): the set lost entries, and only the overflow now stands for them.
                KexSeenLedger.Change.OVERFLOWED -> persistKexSeen(durable = true)
            }
        }
    }

    override fun limitKexFloor(
        passComplete: Boolean,
        passTopHeight: Long?,
        lowestUnrecordedOfferHeight: Long?,
    ) {
        synchronized(kexSeenLock) {
            val before = kexSeenLedger.ceilingHeight
            if (!kexSeenLedger.updateCeiling(passComplete, passTopHeight, lowestUnrecordedOfferHeight)) return
            // A lower ceiling protects a put-off offer: commit() it before any later eviction can count on it.
            val lowered = (kexSeenLedger.ceilingHeight ?: Long.MAX_VALUE) < (before ?: Long.MAX_VALUE)
            persistKexSeen(durable = lowered)
        }
    }

    // F0 debug seam (KI-24): a small cap to reach the overflow in a phone check. Release: always the constant.
    private fun kexSeenCap(): Long =
        FaultSeams.valueOf(FaultSeam.SEEN_KEX_CAP)?.coerceAtLeast(1) ?: MAX_SEEN_KEX_TXS.toLong()

    private fun loadKexSeen(ledger: KexSeenLedger) {
        // G-KI24-2: no stored ceiling (a new or restored wallet, or 2.12.x) is 0: the floor waits for a complete pass.
        ledger.load(
            kexSeenPrefs.getString(KEX_SEEN_KEY, null),
            kexSeenPrefs.getLong(KEX_SEEN_FLOOR_KEY, 0L),
            kexSeenPrefs.getLong(KEX_SEEN_CEILING_KEY, 0L),
            // ZC-074: none stored (2.12.x, or a file written before ZC-074) is an empty overflow.
            kexSeenPrefs.getString(KEX_SEEN_OVERFLOW_KEY, null),
        )
    }

    // The set, its floor, its ceiling and (ZC-074) its overflow in one edit (same file), so a wipe, a wallet move or a
    // chat copy always carries them together.
    private fun persistKexSeen(durable: Boolean) {
        val edit =
            kexSeenPrefs
                .edit()
                .putString(KEX_SEEN_KEY, kexSeenLedger.serialize())
                .putLong(KEX_SEEN_FLOOR_KEY, kexSeenLedger.floorHeight)
                .putLong(KEX_SEEN_CEILING_KEY, kexSeenLedger.ceilingHeight ?: 0L)
        val overflow = kexSeenLedger.serializeOverflow()
        if (overflow.isEmpty()) edit.remove(KEX_SEEN_OVERFLOW_KEY) else edit.putString(KEX_SEEN_OVERFLOW_KEY, overflow)
        if (durable) edit.commit() else edit.apply()
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    override fun tryConsumeKexAckBudget(nowMillis: Long, maxPerWindow: Int, windowMillis: Long): Boolean {
        synchronized(kexAckBudgetLock) {
            // Prune spends that have rolled out of the window, then check the remaining count.
            val cutoff = nowMillis - windowMillis
            val recent = kexSeenPrefs.getString(KEXACK_BUDGET_KEY, null)
                ?.split('\n')
                ?.mapNotNull { it.toLongOrNull() }
                ?.filter { it >= cutoff }
                ?: emptyList()
            if (recent.size >= maxPerWindow) return false
            // commit() (not apply()) — the caller SPENDS ZEC immediately after a true return, so the
            // budget consumption must be durably recorded before the money moves.
            kexSeenPrefs.edit()
                .putString(KEXACK_BUDGET_KEY, (recent + nowMillis).joinToString("\n"))
                .commit()
            return true
        }
    }

    private fun recentGroupKeyGrants(nowMillis: Long, windowMillis: Long): List<Long> {
        val cutoff = nowMillis - windowMillis
        return kexSeenPrefs
            .getString(GROUP_KEY_GRANT_BUDGET_KEY, null)
            ?.split('\n')
            ?.mapNotNull { it.toLongOrNull() }
            ?.filter { it > cutoff }
            ?: emptyList()
    }

    override fun groupKeyGrantBudgetAvailable(nowMillis: Long, maxPerWindow: Int, windowMillis: Long): Boolean =
        synchronized(groupKeyGrantBudgetLock) { recentGroupKeyGrants(nowMillis, windowMillis).size < maxPerWindow }

    override fun tryConsumeGroupKeyGrantBudget(nowMillis: Long, maxPerWindow: Int, windowMillis: Long): Boolean {
        synchronized(groupKeyGrantBudgetLock) {
            val recent = recentGroupKeyGrants(nowMillis, windowMillis)
            if (recent.size >= maxPerWindow) return false
            // commit(), and its result returned: the caller pays right after a true, so the unit must be on disk.
            return kexSeenPrefs
                .edit()
                .putString(GROUP_KEY_GRANT_BUDGET_KEY, (recent + nowMillis).joinToString("\n"))
                .commit()
        }
    }

    override fun giveBackGroupKeyGrantBudget(consumedAtMillis: Long): Boolean =
        synchronized(groupKeyGrantBudgetLock) {
            val recent = recentGroupKeyGrants(consumedAtMillis, ZchatPreferences.GROUP_KEY_GRANT_BUDGET_WINDOW_MS)
            consumedAtMillis in recent &&
                kexSeenPrefs
                    .edit()
                    .putString(GROUP_KEY_GRANT_BUDGET_KEY, (recent - consumedAtMillis).joinToString("\n"))
                    .commit()
        }

    override fun hasProcessedBootSig(signature: String): Boolean =
        synchronized(bootSeenLock) { bootSeenIds.contains(signature) }

    override fun markBootSigProcessed(signature: String) {
        synchronized(bootSeenLock) {
            if (!bootSeenIds.add(signature)) return
            evictOldestToCapacity(bootSeenIds, MAX_SEEN_BOOT_SIGS)
            bootSeenPrefs.edit().putString(BOOT_SEEN_KEY, bootSeenIds.joinToString("\n")).apply()
        }
    }

    // ----- #224 inbound OPEN contact requests (+ blocked-pubkey set) -----

    override fun addMessageRequest(request: ZchatPreferences.MessageRequest) {
        if (request.senderNostrPubkeyHex.isBlank()) return
        synchronized(messageRequestLock) {
            val json = org.json.JSONObject().apply {
                put("pubkey", request.senderNostrPubkeyHex)
                put("address", request.senderAddress)
                put("relay", request.relayUrl)
                put("message", request.firstMessage)
                put("timestampMillis", request.timestampMillis)
                put("eventId", request.eventId)
                put("rumorId", request.rumorId)
            }
            // ZC-1404: a full store keeps the requests it already holds. The old LRU eviction let a flood of
            // throwaway relay keys push out a real pending request whose gift-wrap was already marked seen, so it
            // never came back. A new sender over the cap is refused instead (the caller leaves its wrap unseen);
            // a sender that already has a request replaces it in place, so the count does not grow.
            val all = loadMessageRequestsUnlocked()
            val isNewSender = all.none { it.senderNostrPubkeyHex == request.senderNostrPubkeyHex }
            if (isNewSender && all.size >= MAX_MESSAGE_REQUESTS) {
                throw ZchatPreferences.MessageRequestStoreFullException()
            }
            messageRequestPrefs.edit().putString(REQ_KEY_PREFIX + request.senderNostrPubkeyHex, json.toString()).apply()
        }
    }

    override fun getMessageRequests(): List<ZchatPreferences.MessageRequest> =
        synchronized(messageRequestLock) { loadMessageRequestsUnlocked().sortedByDescending { it.timestampMillis } }

    private fun loadMessageRequestsUnlocked(): List<ZchatPreferences.MessageRequest> {
        val result = mutableListOf<ZchatPreferences.MessageRequest>()
        for ((key, value) in messageRequestPrefs.all) {
            if (!key.startsWith(REQ_KEY_PREFIX) || value !is String) continue
            try {
                val json = org.json.JSONObject(value)
                result.add(
                    ZchatPreferences.MessageRequest(
                        senderNostrPubkeyHex = json.getString("pubkey"),
                        senderAddress = json.getString("address"),
                        relayUrl = json.optString("relay", ""),
                        firstMessage = json.optString("message", ""),
                        timestampMillis = json.optLong("timestampMillis", 0L),
                        eventId = json.optString("eventId", ""),
                        rumorId = json.optString("rumorId", ""),
                    )
                )
            } catch (e: Exception) {
                Log.w("ZchatPreferences", "Failed to parse message request: $key", e)
            }
        }
        return result
    }

    override fun removeMessageRequest(senderNostrPubkeyHex: String) {
        synchronized(messageRequestLock) {
            messageRequestPrefs.edit().remove(REQ_KEY_PREFIX + senderNostrPubkeyHex).apply()
        }
    }

    override fun isNostrPubkeyBlocked(pubkeyHex: String): Boolean {
        if (pubkeyHex.isBlank()) return false
        synchronized(messageRequestLock) {
            val raw = messageRequestPrefs.getString(BLOCKED_PUBKEYS_KEY, null) ?: return false
            return raw.split("\n").contains(pubkeyHex.lowercase())
        }
    }

    override fun blockNostrPubkey(pubkeyHex: String) {
        if (pubkeyHex.isBlank()) return
        // ZC-1346, ZC-1349: a blocked relay key is named by no held ZBOOT.
        findPeerByHeldBootNostrPubkey(pubkeyHex)?.let(::forgetHeldBoots)
        synchronized(messageRequestLock) {
            val raw = messageRequestPrefs.getString(BLOCKED_PUBKEYS_KEY, null)
            val set = LinkedHashSet(raw?.split("\n")?.filter { it.isNotBlank() } ?: emptyList())
            if (!set.add(pubkeyHex.lowercase())) return
            while (set.size > MAX_BLOCKED_PUBKEYS) {
                val it = set.iterator(); it.next(); it.remove()
            }
            messageRequestPrefs.edit().putString(BLOCKED_PUBKEYS_KEY, set.joinToString("\n")).apply()
        }
    }

    override fun unblockNostrPubkey(pubkeyHex: String) {
        if (pubkeyHex.isBlank()) return
        val key = pubkeyHex.lowercase()
        synchronized(messageRequestLock) {
            val raw = messageRequestPrefs.getString(BLOCKED_PUBKEYS_KEY, null)
            val set = LinkedHashSet(raw?.split("\n")?.filter { it.isNotBlank() } ?: emptyList())
            val declined = loadDeclinedUnlocked()
            val keptDeclined = declined.filterNot { it.senderNostrPubkeyHex.equals(key, ignoreCase = true) }
            if (!set.remove(key) && keptDeclined.size == declined.size) return
            messageRequestPrefs
                .edit()
                .putString(BLOCKED_PUBKEYS_KEY, set.joinToString("\n"))
                .putString(DECLINED_REQUESTS_KEY, declinedToJson(keptDeclined))
                .apply()
        }
    }

    // ----- ZC-1464 Declined requests list (in messageRequestPrefs, so clearAll wipes it with the requests) -----

    override fun declineMessageRequest(request: ZchatPreferences.MessageRequest) {
        if (request.senderNostrPubkeyHex.isBlank()) return
        synchronized(messageRequestLock) {
            val key = request.senderNostrPubkeyHex
            val others = loadDeclinedUnlocked().filterNot { it.senderNostrPubkeyHex.equals(key, ignoreCase = true) }
            val kept = (listOf(request) + others).take(ZchatPreferences.MAX_DECLINED_REQUESTS)
            messageRequestPrefs.edit().putString(DECLINED_REQUESTS_KEY, declinedToJson(kept)).apply()
        }
    }

    override fun getDeclinedMessageRequests(): List<ZchatPreferences.MessageRequest> =
        synchronized(messageRequestLock) { loadDeclinedUnlocked() }

    override fun restoreDeclinedMessageRequest(senderNostrPubkeyHex: String): ZchatPreferences.MessageRequest? =
        synchronized(messageRequestLock) {
            val entry =
                loadDeclinedUnlocked().firstOrNull { it.senderNostrPubkeyHex.equals(senderNostrPubkeyHex, ignoreCase = true) }
                    ?: return@synchronized null
            try {
                addMessageRequest(entry)
            } catch (_: ZchatPreferences.MessageRequestStoreFullException) {
                return@synchronized null
            }
            unblockNostrPubkey(entry.senderNostrPubkeyHex)
            entry
        }

    /** Newest decline first; an entry that cannot be read is skipped. */
    private fun loadDeclinedUnlocked(): List<ZchatPreferences.MessageRequest> {
        val raw = messageRequestPrefs.getString(DECLINED_REQUESTS_KEY, null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val json = array.optJSONObject(i) ?: return@mapNotNull null
                val pubkey = json.optString("pubkey", "")
                if (pubkey.isBlank()) return@mapNotNull null
                ZchatPreferences.MessageRequest(
                    senderNostrPubkeyHex = pubkey,
                    senderAddress = json.optString("address", ""),
                    relayUrl = json.optString("relay", ""),
                    firstMessage = json.optString("message", ""),
                    timestampMillis = json.optLong("timestampMillis", 0L),
                    eventId = json.optString("eventId", ""),
                    rumorId = json.optString("rumorId", ""),
                )
            }
        } catch (e: org.json.JSONException) {
            Log.w("ZchatPreferences", "Declined requests list unreadable; treated as empty", e)
            emptyList()
        }
    }

    private fun declinedToJson(list: List<ZchatPreferences.MessageRequest>): String =
        org.json
            .JSONArray()
            .apply {
                list.forEach { r ->
                    put(
                        org.json.JSONObject().apply {
                            put("pubkey", r.senderNostrPubkeyHex)
                            put("address", r.senderAddress)
                            put("relay", r.relayUrl)
                            put("message", r.firstMessage)
                            put("timestampMillis", r.timestampMillis)
                            put("eventId", r.eventId)
                            put("rumorId", r.rumorId)
                        },
                    )
                }
            }.toString()

    // ZC-1464 / ZC-1465: per-contact marks in modePrefs, next to the other per-contact markers, so a reset wipes them.
    override fun isPeerRequestDeclined(peerAddress: String): Boolean = modePrefs.getBoolean("reqdeclined:$peerAddress", false)

    override fun setPeerRequestDeclined(
        peerAddress: String,
        declined: Boolean,
    ) {
        val editor = modePrefs.edit()
        if (declined) editor.putBoolean("reqdeclined:$peerAddress", true) else editor.remove("reqdeclined:$peerAddress")
        editor.apply()
    }

    override fun wasPeerHeardFrom(peerAddress: String): Boolean = modePrefs.getBoolean("heardfrom:$peerAddress", false)

    override fun markPeerHeardFrom(peerAddress: String) {
        if (peerAddress.isBlank()) return
        modePrefs.edit().putBoolean("heardfrom:$peerAddress", true).apply()
    }

    override fun registerSelfAddress(address: String) = selfAddresses.register(address)

    override fun isSelfAddress(address: String): Boolean = selfAddresses.isSelf(address)

    override fun pinnedIdentity(accountKey: String): String? = selfAddresses.pinnedIdentity(accountKey)

    override fun pinIdentityIfAbsent(
        accountKey: String,
        address: String
    ): String = selfAddresses.pinIdentityIfAbsent(accountKey, address)

    override fun getPeerNostrRelay(peerAddress: String): String? = modePrefs.getString(relayKey(peerAddress), null)
    override fun setPeerNostrRelay(peerAddress: String, relayUrl: String?) {
        modePrefs.edit().apply {
            if (relayUrl == null) remove(relayKey(peerAddress)) else putString(relayKey(peerAddress), relayUrl)
        }.apply()
    }

    private fun bootEpochKey(peer: String) = "bootepoch:$peer"
    override fun getPeerBootEpoch(peerAddress: String): Long = modePrefs.getLong(bootEpochKey(peerAddress), 0L)
    override fun setPeerBootEpoch(peerAddress: String, epoch: Long) {
        modePrefs.edit().putLong(bootEpochKey(peerAddress), epoch).apply()
    }

    override fun isOwnBootSent(peerAddress: String): Boolean = modePrefs.getBoolean(bootSentKey(peerAddress), false)
    override fun setOwnBootSent(peerAddress: String, sent: Boolean): Boolean {
        // R5-2: the RECORDING write (sent = true) goes down immediately before the paid KEX it guards, so it
        // is committed and its result returned — a KEX whose marker was not written is never paid for.
        // The RE-ARM (sent = false) stays in the background: losing it can only mean NOT paying again, and
        // rotateNostrIdentity writes one per peer in a loop, where a synchronous write per contact would
        // stall that pass for nothing. It reports true because no caller may refuse to re-arm.
        val editor = modePrefs.edit().putBoolean(bootSentKey(peerAddress), sent)
        val saved = if (sent) editor.commit() else true.also { editor.apply() }
        bumpE2EHandshakeTick()
        return saved
    }

    private fun peerKnownOurRotKey(peer: String) = "peerknownourrot:$peer"
    override fun getPeerKnownOurRotationIndex(peerAddress: String): Int =
        modePrefs.getInt(peerKnownOurRotKey(peerAddress), -1)
    override fun setPeerKnownOurRotationIndex(peerAddress: String, index: Int) {
        modePrefs.edit().putInt(peerKnownOurRotKey(peerAddress), index).apply()
    }

    private fun sentNostrBootKey(peer: String) = "sentnostrboot:$peer"
    override fun getSentNostrBootPubkey(peerAddress: String): String? =
        modePrefs.getString(sentNostrBootKey(peerAddress), null)
    override fun setSentNostrBootPubkey(peerAddress: String, pubkeyHex: String?): Boolean {
        // R5-2: the RECORDING write is committed and its result returned — the ZBOOT path reads this marker
        // to decide whether our identity still has to be paid on-chain, so an unrecorded ZBOOT is never paid
        // for. The RE-ARM (null) stays in the background: losing it can only mean NOT paying again.
        val editor = modePrefs.edit()
        if (pubkeyHex == null) {
            editor.remove(sentNostrBootKey(peerAddress))
            editor.apply()
            return true
        }
        editor.putString(sentNostrBootKey(peerAddress), pubkeyHex)
        return editor.commit()
    }

    // ZC-283: in modePrefs, next to the other paid-send markers, so a reset or a wallet change wipes it with them.
    override fun getStatusBroadcastRecord(): String? = modePrefs.getString(KEY_STATUS_BROADCAST_RECORD, null)

    override fun setStatusBroadcastRecord(value: String?): Boolean {
        // commit(): a contact is recorded just before its paid memo is submitted and the record must not be lost to
        // a process kill. R4-1: the result is returned, so a memo whose record did not reach the disk is not paid.
        val editor = modePrefs.edit()
        if (value == null) editor.remove(KEY_STATUS_BROADCAST_RECORD) else editor.putString(KEY_STATUS_BROADCAST_RECORD, value)
        return editor.commit()
    }

    private fun handshakePaidKey(slot: String) = "handshakepaid:$slot"

    override fun getHandshakePaidRecord(slot: String): String? = modePrefs.getString(handshakePaidKey(slot), null)

    override fun setHandshakePaidRecord(slot: String, record: String?): Boolean {
        // commit(): the record is written just before a paid attempt and must not be lost to a process kill.
        // R4-1: its result is returned, so an attempt whose record did not reach the disk never pays.
        val editor = modePrefs.edit()
        if (record == null) editor.remove(handshakePaidKey(slot)) else editor.putString(handshakePaidKey(slot), record)
        return editor.commit()
    }

    // R9-2: one "kexowed:<peer>" entry per contact owed our first-message KEX. In modePrefs, next to the other
    // handshake markers, so a reset or a wallet change wipes it with them.
    private fun kexOwedKey(peer: String) = "kexowed:$peer"

    override fun getKexOwedPeers(): Set<String> =
        modePrefs.all.keys
            .filter { it.startsWith("kexowed:") }
            .mapTo(mutableSetOf()) { it.removePrefix("kexowed:") }

    override fun isKexOwed(peerAddress: String): Boolean = modePrefs.getBoolean(kexOwedKey(peerAddress), false)

    override fun setKexOwed(peerAddress: String, owed: Boolean): Boolean {
        // commit(): the mark is written just before the paid KEX it stands for, and must survive a process kill.
        val editor = modePrefs.edit()
        if (owed) {
            editor.putBoolean(kexOwedKey(peerAddress), true)
        } else {
            // T1b-1: the account that confirmed it goes with the mark; ZC-285: and its reason.
            editor
                .remove(kexOwedKey(peerAddress))
                .remove(kexOwedAccountKey(peerAddress))
                .remove(kexOwedReasonKey(peerAddress))
        }
        return editor.commit()
    }

    // T1b-1: "kexowner:<peer>" = the account (UUID hex) that confirmed the owed KEX. Not a "kexowed:" key, so
    // getKexOwedPeers never lists it.
    private fun kexOwedAccountKey(peer: String) = "kexowner:$peer"

    override fun getKexOwedAccount(peerAddress: String): String? = modePrefs.getString(kexOwedAccountKey(peerAddress), null)

    override fun setKexOwedAccount(
        peerAddress: String,
        accountKey: String?,
    ): Boolean {
        val editor = modePrefs.edit()
        if (accountKey == null) {
            editor.remove(kexOwedAccountKey(peerAddress))
        } else {
            editor.putString(kexOwedAccountKey(peerAddress), accountKey)
        }
        return editor.commit()
    }

    // ZC-285: "kexowedwhy:<peer>" = why our KEX is owed (absent: a first message). Not a "kexowed:" key, so
    // getKexOwedPeers never lists it; in modePrefs with the mark, so clearAll wipes it with the other handshake markers.
    private fun kexOwedReasonKey(peer: String) = "kexowedwhy:$peer"

    override fun getKexOwedReason(peerAddress: String): String? = modePrefs.getString(kexOwedReasonKey(peerAddress), null)

    // ZC-1272: in modePrefs with the other handshake markers, so a reset or a wallet change wipes it with them.
    private fun quotedChatSetupKey(peer: String) = "quotedsetup:$peer"

    override fun getQuotedChatSetup(peerAddress: String): String? = modePrefs.getString(quotedChatSetupKey(peerAddress), null)

    override fun getQuotedChatSetupPeers(): Set<String> =
        modePrefs.all.keys
            .filter { it.startsWith("quotedsetup:") }
            .mapTo(mutableSetOf()) { it.removePrefix("quotedsetup:") }

    override fun setQuotedChatSetup(
        peerAddress: String,
        encoded: String?,
    ): Boolean {
        val editor = modePrefs.edit()
        if (encoded == null) {
            editor.remove(quotedChatSetupKey(peerAddress))
        } else {
            editor.putString(quotedChatSetupKey(peerAddress), encoded)
        }
        return editor.commit()
    }

    override fun setKexOwedReason(
        peerAddress: String,
        reason: String?,
    ): Boolean {
        // commit(): written before a Reset wipes anything, and read after a process kill.
        val editor = modePrefs.edit()
        if (reason == null) {
            editor.remove(kexOwedReasonKey(peerAddress))
        } else {
            editor.putString(kexOwedReasonKey(peerAddress), reason)
        }
        return editor.commit()
    }

    // Round 10b R10B-1: one "zbootquoted:<peer>" entry per contact whose confirmed first-message question quoted our
    // on-chain ZBOOT. In modePrefs next to the other handshake markers, so a reset or a wallet change wipes it with them.
    // T2-extra (a): an allowance is now stored as "zbootallowance:<peer>". A "zbootquoted:<peer>" entry was written by an
    // earlier 2.13.0 test build (the key is not in 2.12.4), some of them for a question later refused on Keystone (T1 L11);
    // it is not an allowance, and it is removed when read or when a new allowance is written.
    private fun zbootQuotedKey(peer: String) = "zbootallowance:$peer"

    private fun earlierZbootQuotedKey(peer: String) = "zbootquoted:$peer"

    override fun isZbootQuoted(peerAddress: String): Boolean {
        if (modePrefs.contains(earlierZbootQuotedKey(peerAddress))) {
            modePrefs.edit().remove(earlierZbootQuotedKey(peerAddress)).commit()
        }
        return modePrefs.getBoolean(zbootQuotedKey(peerAddress), false)
    }

    override fun setZbootQuoted(peerAddress: String, quoted: Boolean): Boolean {
        // commit(): written before the message it was confirmed with is paid, and consumed just before the ZBOOT's submit.
        val editor = modePrefs.edit().remove(earlierZbootQuotedKey(peerAddress))
        if (quoted) {
            editor.putBoolean(zbootQuotedKey(peerAddress), true)
        } else {
            // ZC-534: an owed mark never outlives its allowance; both go in this one write. ZC-1390: nor its account.
            editor.remove(zbootQuotedKey(peerAddress)).remove(zbootOwedKey(peerAddress)).remove(zbootQuotedAccountKey(peerAddress))
        }
        return editor.commit()
    }

    // ZC-1390: "zbootowner:<peer>" = the account (UUID hex) whose confirmed question wrote "zbootallowance:<peer>". In
    // modePrefs beside it, so clearAll wipes it with the other handshake markers. New in 2.13.0 (absent = none recorded).
    private fun zbootQuotedAccountKey(peer: String) = "zbootowner:$peer"

    override fun getZbootQuotedAccount(peerAddress: String): String? = modePrefs.getString(zbootQuotedAccountKey(peerAddress), null)

    override fun setZbootQuotedAccount(
        peerAddress: String,
        accountKey: String?,
    ): Boolean {
        val editor = modePrefs.edit()
        if (accountKey == null) {
            editor.remove(zbootQuotedAccountKey(peerAddress))
        } else {
            editor.putString(zbootQuotedAccountKey(peerAddress), accountKey)
        }
        return editor.commit()
    }

    override fun restoreQuotedZbootAsOwed(
        peerAddress: String,
        accountKey: String?,
    ): Boolean {
        val editor =
            modePrefs
                .edit()
                .remove(earlierZbootQuotedKey(peerAddress))
                .putBoolean(zbootQuotedKey(peerAddress), true)
                .putBoolean(zbootOwedKey(peerAddress), true)
        if (accountKey == null) {
            editor.remove(zbootQuotedAccountKey(peerAddress))
        } else {
            editor.putString(zbootQuotedAccountKey(peerAddress), accountKey)
        }
        return editor.commit()
    }

    // ZC-534: one "zbootowed:<peer>" entry per contact whose quoted ZBOOT provably sent nothing and still waits. In
    // modePrefs beside "zbootallowance:<peer>", so clearAll wipes it with the other handshake markers. New in 2.13.0
    // (2.12.4 has no such key; absent = not owed).
    private fun zbootOwedKey(peer: String) = "zbootowed:$peer"

    override fun isZbootOwed(peerAddress: String): Boolean = modePrefs.getBoolean(zbootOwedKey(peerAddress), false)

    // ZC-1352: in modePrefs beside the handshake markers, so clearAll wipes it with them. New in 2.13.0 (absent = not withdrawn).
    private fun setupWithdrawnKey(peer: String) = "setupwithdrawn:$peer"

    override fun isAutomaticSetupWithdrawn(peerAddress: String): Boolean = modePrefs.getBoolean(setupWithdrawnKey(peerAddress), false)

    override fun setAutomaticSetupWithdrawn(
        peerAddress: String,
        withdrawn: Boolean,
    ): Boolean {
        val editor = modePrefs.edit()
        if (withdrawn) editor.putBoolean(setupWithdrawnKey(peerAddress), true) else editor.remove(setupWithdrawnKey(peerAddress))
        return editor.commit()
    }

    override fun setZbootOwed(
        peerAddress: String,
        owed: Boolean,
    ): Boolean {
        // commit(): the mark must survive the process kill it exists for.
        val editor = modePrefs.edit()
        if (owed) editor.putBoolean(zbootOwedKey(peerAddress), true) else editor.remove(zbootOwedKey(peerAddress))
        return editor.commit()
    }

    override fun getZbootOwedPeers(): Set<String> =
        modePrefs.all
            .filter { (key, value) -> key.startsWith("zbootowed:") && value == true }
            .keys
            .mapTo(mutableSetOf()) { it.removePrefix("zbootowed:") }

    // R3-4: the held GROUP_ACCEPTs, one "heldgroupaccept:<groupId>" entry each. The prefix is kept so a
    // record an earlier version wrote into modePrefs is recognised and moved
    // (migrateHeldGroupAcceptsToEncrypted); R4-1 keeps them in heldGroupAcceptPrefs, encrypted at rest.
    private fun heldGroupAcceptKey(groupId: String) = "heldgroupaccept:$groupId"

    override fun getHeldGroupAccepts(): Map<String, String> =
        heldGroupAcceptPrefs.all
            .mapNotNull { (key, value) ->
                if (!key.startsWith("heldgroupaccept:")) return@mapNotNull null
                (value as? String)?.let { key.removePrefix("heldgroupaccept:") to it }
            }.toMap()

    override fun setHeldGroupAccept(groupId: String, record: String?): Boolean {
        // commit(): written before a kill could lose the unsent accept, and removed just before the accept is
        // paid, so a kill after the payment can never make it paid twice.
        // R4-1: its result is returned, so a removal that did not reach the disk is never taken as done.
        if (record != null) return heldGroupAcceptPrefs.edit().putString(heldGroupAcceptKey(groupId), record).commit()
        // B3 (GPT Q2): a removal also removes the plaintext copy an earlier version wrote into modePrefs. The
        // migration keeps that copy when its cleanup fails, and the next start would import it again — so the
        // removal is confirmed only when BOTH stores confirm it, and a reply is never paid while a copy remains.
        val encryptedGone = heldGroupAcceptPrefs.edit().remove(heldGroupAcceptKey(groupId)).commit()
        val plaintextGone = modePrefs.edit().remove(heldGroupAcceptKey(groupId)).commit()
        return encryptedGone && plaintextGone
    }

    // Round 8 D4: a stranger's group invite waiting for Join, one "invitejoin:<groupId>" entry each, in the same
    // encrypted, wallet-scoped store as the held replies (getHeldGroupAccepts reads only its own prefix).
    private fun groupInviteNeedsJoinKey(groupId: String) = "invitejoin:$groupId"

    override fun getGroupInviteNeedsJoin(groupId: String): String? =
        heldGroupAcceptPrefs.getString(groupInviteNeedsJoinKey(groupId), null)

    override fun setGroupInviteNeedsJoin(groupId: String, record: String?): Boolean {
        // commit(): the mark is what keeps a stranger's invite from being answered and paid; it must survive a kill.
        val editor = heldGroupAcceptPrefs.edit()
        if (record == null) {
            editor.remove(groupInviteNeedsJoinKey(groupId))
        } else {
            editor.putString(groupInviteNeedsJoinKey(groupId), record)
        }
        return editor.commit().also {
            if (it) ZchatPreferences.markGroupStoreChanged(groupId)
        }
    }

    // ZC-420: the relay-copy marks, one "relaycopy:<slot>" entry each, in the same encrypted, wallet-scoped store (a slot
    // names a contact, or a group and a member, in clear, so it never goes to a plain file). Each reader of the store
    // filters its own prefix: the held replies, the needs-join records and F8's ledger never see a mark.
    private fun relayCopyKey(slot: String) = RELAY_COPY_PREFIX + slot

    override fun getRelayCopyMark(slot: String): String? = heldGroupAcceptPrefs.getString(relayCopyKey(slot), null)

    override fun setRelayCopyMark(
        slot: String,
        value: String?,
    ): Boolean =
        runCatching {
            val editor = heldGroupAcceptPrefs.edit()
            if (value == null) {
                editor.remove(relayCopyKey(slot)).apply()
                true
            } else {
                editor.putString(relayCopyKey(slot), value).commit()
            }
        }.getOrDefault(false)

    override fun getRelayCopyMarkSlots(): Set<String> =
        heldGroupAcceptPrefs.all.keys
            .filter { it.startsWith(RELAY_COPY_PREFIX) }
            .mapTo(mutableSetOf()) { it.removePrefix(RELAY_COPY_PREFIX) }

    /**
     * R4-1 — move the held GROUP_ACCEPTs an earlier version wrote into the plaintext conversation-mode file
     * [old] into the encrypted store [enc]. Only the "heldgroupaccept:" family is touched; everything else in
     * that file stays. A record the encrypted store already holds is never clobbered, and the plaintext copy
     * is removed ONLY after a durable encrypted write — a failed move keeps it for the next launch, because
     * an unsent group reply that is dropped leaves the user silently outside the group.
     */
    private fun migrateHeldGroupAcceptsToEncrypted(old: SharedPreferences, enc: SharedPreferences) {
        val stale = old.all.filterKeys { it.startsWith("heldgroupaccept:") }
        if (stale.isEmpty()) return
        val existing = enc.all.keys
        val editor = enc.edit()
        var moved = 0
        for ((key, value) in stale) {
            if (value is String && key !in existing) {
                editor.putString(key, value)
                moved++
            }
        }
        if (editor.commit()) {
            val cleanup = old.edit()
            stale.keys.forEach(cleanup::remove)
            cleanup.commit()
            Log.d("ZCHAT_MIGRATE", "Moved $moved held group reply/replies to the encrypted store")
        } else {
            Log.e("ZCHAT_MIGRATE", "Held group reply move FAILED — keeping the plain copy for retry")
        }
    }

    private fun receivedKexKey(peer: String) = "receivedkex:$peer"
    override fun getReceivedKexPubkey(peerAddress: String): String? =
        modePrefs.getString(receivedKexKey(peerAddress), null)
    override fun setReceivedKexPubkey(peerAddress: String, pubkeyHex: String?) {
        modePrefs.edit().apply {
            if (pubkeyHex == null) remove(receivedKexKey(peerAddress)) else putString(receivedKexKey(peerAddress), pubkeyHex)
        }.apply()
        bumpE2EHandshakeTick()
    }

    private fun receivedKexConvIdKey(peer: String) = "receivedkexconv:$peer"

    override fun getReceivedKexConvId(peerAddress: String): String? = modePrefs.getString(receivedKexConvIdKey(peerAddress), null)

    override fun setReceivedKexConvId(
        peerAddress: String,
        convId: String?,
    ) {
        val key = receivedKexConvIdKey(peerAddress)
        val editor = modePrefs.edit()
        if (convId == null) editor.remove(key) else editor.putString(key, convId)
        editor.apply()
    }

    private fun kexAwaitingAcceptKey(peer: String) = "kexawaitaccept:$peer"

    override fun getInboundKexAwaitingAccept(peerAddress: String): String? =
        modePrefs.getString(kexAwaitingAcceptKey(peerAddress), null)

    override fun setInboundKexAwaitingAccept(peerAddress: String, pubkeyHex: String?): Boolean {
        // commit(): the marker is what holds a stranger's paid KEXACK back; it must not be lost to a process kill.
        // GPT-6 F3: its result is returned, so a hold that did not reach the disk is never taken as saved.
        val editor = modePrefs.edit()
        val key = kexAwaitingAcceptKey(peerAddress)
        // ZC-1346: the ZBOOT held for this hold goes with it, also when another key replaces it.
        if (pubkeyHex != modePrefs.getString(key, null)) editor.remove(acceptWaitingBootKey(peerAddress))
        if (pubkeyHex == null) editor.remove(key) else editor.putString(key, pubkeyHex)
        val saved = editor.commit()
        bumpE2EHandshakeTick()
        return saved
    }

    private fun acceptWaitingBootKey(peer: String) = "$ACCEPT_WAITING_BOOT_PREFIX$peer"

    override fun holdAcceptWaitingBoot(
        peerAddress: String,
        raw: String,
    ): Boolean {
        if (raw.length > KEY_CLAIM_HELD_MAX || getInboundKexAwaitingAccept(peerAddress) == null) return false
        return modePrefs.edit().putString(acceptWaitingBootKey(peerAddress), raw).commit()
    }

    private fun openChatKexOfferKey(peer: String) = "kexopenoffer:$peer"

    override fun getOpenChatKexOffer(peerAddress: String): String? = modePrefs.getString(openChatKexOfferKey(peerAddress), null)

    override fun setOpenChatKexOffer(peerAddress: String, record: String?): Boolean {
        val editor = modePrefs.edit()
        val key = openChatKexOfferKey(peerAddress)
        if (record == null) editor.remove(key) else editor.putString(key, record)
        val saved = editor.commit()
        bumpE2EHandshakeTick()
        return saved
    }

    private fun acceptedKeyKey(peer: String) = "acceptedkey:$peer"

    private fun acceptOwedKeyKey(peer: String) = "acceptowed:$peer"

    override fun getAcceptOwedKey(peerAddress: String): String? = modePrefs.getString(acceptOwedKeyKey(peerAddress), null)

    override fun setAcceptOwedKey(
        peerAddress: String,
        publicKey: String?,
    ): Boolean {
        val editor = modePrefs.edit()
        if (publicKey == null) editor.remove(acceptOwedKeyKey(peerAddress)) else editor.putString(acceptOwedKeyKey(peerAddress), publicKey)
        return editor.commit()
    }

    override fun getAcceptedKey(peerAddress: String): String? = modePrefs.getString(acceptedKeyKey(peerAddress), null)

    override fun setAcceptedKey(
        peerAddress: String,
        publicKey: String?,
    ): Boolean {
        val editor = modePrefs.edit()
        if (publicKey == null) editor.remove(acceptedKeyKey(peerAddress)) else editor.putString(acceptedKeyKey(peerAddress), publicKey)
        return editor.commit()
    }

    private fun sentKexAckKey(peer: String) = "sentkexack:$peer"
    override fun getSentKexAckPubkey(peerAddress: String): String? =
        modePrefs.getString(sentKexAckKey(peerAddress), null)
    override fun setSentKexAckPubkey(peerAddress: String, pubkeyHex: String?): Boolean {
        // R5-2: the RECORDING write is committed and its result returned — it is the ONLY durable record that
        // stops the next chat open paying for the same KEXACK, so an unrecorded ack is never paid for. The
        // RE-ARM (null) stays in the background: losing it can only mean NOT paying again.
        val editor = modePrefs.edit()
        val saved =
            if (pubkeyHex == null) {
                editor.remove(sentKexAckKey(peerAddress))
                true.also { editor.apply() }
            } else {
                editor.putString(sentKexAckKey(peerAddress), pubkeyHex)
                editor.commit()
            }
        bumpE2EHandshakeTick()
        return saved
    }

    override fun hasSeenModeIntro(): Boolean = modePrefs.getBoolean(keyHasSeenModeIntro, false)
    override fun setHasSeenModeIntro(seen: Boolean) {
        modePrefs.edit().putBoolean(keyHasSeenModeIntro, seen).apply()
    }

    override fun hasRenamedOldTeamContact(): Boolean = modePrefs.getBoolean(keyRenamedOldTeamContact, false)

    // apply(), not commit(): a lost write costs one more contact-book read at the next start and nothing else
    // (the rename itself is idempotent while the label is there). No money or message state depends on it.
    override fun setRenamedOldTeamContact() {
        modePrefs.edit().putBoolean(keyRenamedOldTeamContact, true).apply()
    }

    // ==========================================
    // DESTROY / REMOTE KILL IMPLEMENTATION
    // ==========================================
    //
    // Storage:
    //   PIN hash, kill-phrase hash, format-v2 flags, and rate-limit counters live in
    //   `e2ePrefs` (EncryptedSharedPreferences, AES-256-GCM via Android Keystore). The app
    //   already requires Keystore for the E2E ratchet state in this same store, so making
    //   PIN/phrase keys depend on Keystore introduces no new failure mode.
    //
    //   Pre-v3 installs stored PIN + flags in plain `prefs`. On first access after upgrade,
    //   [migrateDestroyStorageIfNeeded] copies everything into `e2ePrefs` and sets a marker
    //   key (also in `e2ePrefs`) so the migration runs exactly once. Old plain keys are NOT
    //   immediately deleted — they remain readable as a downgrade-safety net until the next
    //   successful write replaces them with empty values.
    private val destroyStore get(): SharedPreferences {
        migrateDestroyStorageIfNeeded()
        return e2ePrefs
    }

    // FD-5: write the Destroy store only through this editor. It sets the migration marker in the same write,
    // so once v3 code has stored anything here a legacy plain copy is never migrated over it; a read alone
    // never writes (see migrateDestroyStorageIfNeeded).
    private fun destroyStoreEditor(): SharedPreferences.Editor =
        destroyStore.edit().putBoolean(KEY_DESTROY_STORAGE_V3, true)

    private fun migrateDestroyStorageIfNeeded() {
        if (e2ePrefs.getBoolean(KEY_DESTROY_STORAGE_V3, false)) return
        // FD-5: with nothing to copy, return before any editor exists. A commit, even an empty one, writes the
        // file, and the chat list reads hasDestroyPin() on every recomposition: right after the Destroy file
        // sweep that write brought zchat_e2e_keys_encrypted.xml back. Since v3 nothing writes these keys to
        // `prefs`, so on a fresh install or after the wipe this is 8 reads of the in-memory map.
        if (LEGACY_DESTROY_KEYS.none { prefs.contains(it) }) return
        val editor = e2ePrefs.edit()
        prefs.getString(KEY_DESTROY_PIN, null)?.let { editor.putString(KEY_DESTROY_PIN, it) }
        if (prefs.contains(KEY_DESTROY_PIN_FORMAT_V2)) {
            editor.putBoolean(KEY_DESTROY_PIN_FORMAT_V2, prefs.getBoolean(KEY_DESTROY_PIN_FORMAT_V2, false))
        }
        prefs.getString(KEY_REMOTE_KILL_PHRASE_HASH, null)?.let { editor.putString(KEY_REMOTE_KILL_PHRASE_HASH, it) }
        if (prefs.contains(KEY_REMOTE_KILL_PHRASE_FORMAT_V2)) {
            editor.putBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, prefs.getBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, false))
        }
        if (prefs.contains(KEY_PIN_FAIL_COUNT)) editor.putInt(KEY_PIN_FAIL_COUNT, prefs.getInt(KEY_PIN_FAIL_COUNT, 0))
        if (prefs.contains(KEY_PIN_VIOLATIONS)) editor.putInt(KEY_PIN_VIOLATIONS, prefs.getInt(KEY_PIN_VIOLATIONS, 0))
        if (prefs.contains(KEY_PIN_LOCKOUT_ELAPSED)) editor.putLong(KEY_PIN_LOCKOUT_ELAPSED, prefs.getLong(KEY_PIN_LOCKOUT_ELAPSED, 0L))
        if (prefs.contains(KEY_PIN_LOCKOUT_WALL)) editor.putLong(KEY_PIN_LOCKOUT_WALL, prefs.getLong(KEY_PIN_LOCKOUT_WALL, 0L))
        // commit() — synchronous so the marker is durable before any subsequent verify can race.
        editor.putBoolean(KEY_DESTROY_STORAGE_V3, true).commit()
    }

    override suspend fun setDestroyPin(pin: String) {
        // Store PBKDF2 hash in encrypted prefs. Set v2 flag atomically.
        val hashed = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.hashAsync(pin)
        destroyStoreEditor()
            .putString(KEY_DESTROY_PIN, hashed)
            .putBoolean(KEY_DESTROY_PIN_FORMAT_V2, true)
            .apply()
        // Wipe any legacy plain-prefs copy left over from pre-v3 installs so the only PIN hash
        // on disk is the encrypted one.
        if (prefs.contains(KEY_DESTROY_PIN)) {
            prefs.edit().remove(KEY_DESTROY_PIN).remove(KEY_DESTROY_PIN_FORMAT_V2).apply()
        }
    }

    override suspend fun verifyDestroyPin(pin: String): Boolean {
        // Boolean shim — delegates to the lockout-aware variant. Lockouts surface as `false`
        // here so existing call sites that ignore lockouts still get a fail-safe response.
        return verifyDestroyPinWithLockout(pin) is DestroyPinVerifyResult.Success
    }

    override suspend fun verifyDestroyPinWithLockout(pin: String): DestroyPinVerifyResult {
        val store = destroyStore
        val storedHash = store.getString(KEY_DESTROY_PIN, null)
            ?: return DestroyPinVerifyResult.Failed(attemptsRemaining = 0)
        val v2 = store.getBoolean(KEY_DESTROY_PIN_FORMAT_V2, false)
        val isLegacy = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.isLegacyFormat(storedHash)
        // Downgrade defense: once v2 is set, refuse legacy format outright.
        if (v2 && isLegacy) return DestroyPinVerifyResult.Failed(attemptsRemaining = 0)

        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val state = readPinAttemptState()
        val remaining = co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy
            .remainingLockoutMillis(state, nowElapsed, nowWall)
        if (remaining > 0L) return DestroyPinVerifyResult.LockedOut(remainingMillis = remaining)

        val matches = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.verifyAsync(pin, storedHash)
        if (matches) {
            // Clear counter on success.
            writePinAttemptState(co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.onSuccess())
            // Auto-upgrade legacy hash on the first successful verify (sets v2 atomically).
            if (isLegacy) setDestroyPin(pin)
            return DestroyPinVerifyResult.Success
        }
        val nextState = co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy
            .onFailure(state, nowElapsed, nowWall)
        writePinAttemptState(nextState)
        val newRemaining = co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy
            .remainingLockoutMillis(nextState, nowElapsed, nowWall)
        return if (newRemaining > 0L) {
            DestroyPinVerifyResult.LockedOut(remainingMillis = newRemaining)
        } else {
            val remainAttempts = (co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.MAX_ATTEMPTS - nextState.failedAttempts)
                .coerceAtLeast(0)
            DestroyPinVerifyResult.Failed(attemptsRemaining = remainAttempts)
        }
    }

    private fun readPinAttemptState(): co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.State {
        val store = destroyStore
        return co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.State(
            failedAttempts = store.getInt(KEY_PIN_FAIL_COUNT, 0),
            violations = store.getInt(KEY_PIN_VIOLATIONS, 0),
            lockoutUntilElapsed = store.getLong(KEY_PIN_LOCKOUT_ELAPSED, 0L),
            lockoutUntilWall = store.getLong(KEY_PIN_LOCKOUT_WALL, 0L),
        )
    }

    private fun writePinAttemptState(state: co.electriccoin.zcash.ui.screen.chat.filesharing.PinAttemptPolicy.State) {
        destroyStoreEditor()
            .putInt(KEY_PIN_FAIL_COUNT, state.failedAttempts)
            .putInt(KEY_PIN_VIOLATIONS, state.violations)
            .putLong(KEY_PIN_LOCKOUT_ELAPSED, state.lockoutUntilElapsed)
            .putLong(KEY_PIN_LOCKOUT_WALL, state.lockoutUntilWall)
            .apply()
    }

    override fun hasDestroyPin(): Boolean {
        return destroyStore.getString(KEY_DESTROY_PIN, null) != null
    }

    override fun clearDestroyPin() {
        // Remove the PIN hash + format flag AND reset the attempt/lockout counters so a fresh setup
        // starts clean. destroyStore = e2ePrefs (migration runs via the getter).
        // DESTROY-ADV-W: the choice of what Destroy All keeps goes in the same write (it lives with the PIN).
        destroyStoreEditor()
            .remove(KEY_DESTROY_PIN)
            .remove(KEY_DESTROY_PIN_FORMAT_V2)
            .remove(KEY_DESTROY_KEEP)
            .remove(KEY_PIN_FAIL_COUNT)
            .remove(KEY_PIN_VIOLATIONS)
            .remove(KEY_PIN_LOCKOUT_ELAPSED)
            .remove(KEY_PIN_LOCKOUT_WALL)
            .apply()
        // Scrub any leftover legacy plain-prefs copy too, so no stale hash can resurrect via migration.
        if (prefs.contains(KEY_DESTROY_PIN)) {
            prefs.edit().remove(KEY_DESTROY_PIN).remove(KEY_DESTROY_PIN_FORMAT_V2).apply()
        }
    }

    override fun getDestroyKeep(): Set<String> {
        val store = destroyStore
        if (store.getString(KEY_DESTROY_PIN, null) == null) return emptySet()
        val raw =
            try {
                store.getString(KEY_DESTROY_KEEP, null)
            } catch (e: ClassCastException) {
                null
            } ?: return emptySet()
        val values = raw.split(',').filter { it.isNotEmpty() }.toSet()
        // A value this version does not know means "keep nothing": never a part of a choice.
        return if (values.all { it in DESTROY_KEEP_VALUES }) values else emptySet()
    }

    override fun setDestroyKeep(keep: Set<String>): Boolean {
        if (!hasDestroyPin() || !keep.all { it in DESTROY_KEEP_VALUES }) return false
        val before = destroySetupInMemory()
        if (putDestroyKeep(destroyStoreEditor(), keep).commit()) return true
        restoreDestroySetup(before)
        return false
    }

    /** ZC-1043: the Destroy PIN, its format flag and the keep choice as the Destroy store holds them now. */
    private fun destroySetupInMemory(): Map<String, Any?> {
        val store = destroyStore
        return listOf(KEY_DESTROY_PIN, KEY_DESTROY_PIN_FORMAT_V2, KEY_DESTROY_KEEP).associateWith { key -> store.all[key] }
    }

    /**
     * ZC-1043: SharedPreferences puts a change in memory before its disk write and keeps it there when commit() answers
     * false. A Destroy setting that did not reach the disk is taken back out of memory, so the app never acts on a PIN or
     * a choice it told the user was not saved (the disk still holds [before]; apply() writes the same values again).
     */
    private fun restoreDestroySetup(before: Map<String, Any?>) {
        try {
            val editor = destroyStore.edit()
            for ((key, value) in before) {
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> editor.remove(key)
                }
            }
            editor.apply()
        } catch (e: Exception) {
            Log.w("ZchatPreferences", "the Destroy setting that was not saved could not be taken back: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun setDestroySetup(
        pin: String,
        keep: Set<String>
    ): Boolean {
        if (!keep.all { it in DESTROY_KEEP_VALUES }) return false
        val hashed = SecureHash.hashAsync(pin)
        val before = destroySetupInMemory()
        val editor =
            destroyStoreEditor()
                .putString(KEY_DESTROY_PIN, hashed)
                .putBoolean(KEY_DESTROY_PIN_FORMAT_V2, true)
        if (!putDestroyKeep(editor, keep).commit()) {
            restoreDestroySetup(before)
            return false
        }
        if (prefs.contains(KEY_DESTROY_PIN)) {
            prefs
                .edit()
                .remove(KEY_DESTROY_PIN)
                .remove(KEY_DESTROY_PIN_FORMAT_V2)
                .apply()
        }
        return true
    }

    private fun putDestroyKeep(
        editor: SharedPreferences.Editor,
        keep: Set<String>
    ): SharedPreferences.Editor =
        if (keep.isEmpty()) editor.remove(KEY_DESTROY_KEEP) else editor.putString(KEY_DESTROY_KEEP, keep.sorted().joinToString(","))

    override fun isRemoteKillEnabled(): Boolean {
        return prefs.getBoolean(KEY_REMOTE_KILL_ENABLED, false)
    }

    override fun setRemoteKillEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMOTE_KILL_ENABLED, enabled).apply()
    }

    override suspend fun verifyRemoteKillPhrase(phrase: String): Boolean {
        val store = destroyStore
        val storedHash = store.getString(KEY_REMOTE_KILL_PHRASE_HASH, null) ?: return false
        val v2 = store.getBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, false)
        val isLegacy = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.isLegacyFormat(storedHash)
        // Downgrade defense: once v2 is set, refuse legacy format outright. Verify-only path
        // — kill path should destroy, not re-hash.
        if (v2 && isLegacy) return false
        return co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.verifyAsync(phrase, storedHash)
    }

    override suspend fun setRemoteKillPhrase(phrase: String) {
        // Store PBKDF2 hash in encrypted prefs. Set v2 flag atomically.
        val hashed = co.electriccoin.zcash.ui.screen.chat.filesharing.SecureHash.hashAsync(phrase)
        destroyStoreEditor()
            .putString(KEY_REMOTE_KILL_PHRASE_HASH, hashed)
            .putBoolean(KEY_REMOTE_KILL_PHRASE_FORMAT_V2, true)
            .apply()
        // Wipe legacy plain copy.
        if (prefs.contains(KEY_REMOTE_KILL_PHRASE_HASH)) {
            prefs.edit().remove(KEY_REMOTE_KILL_PHRASE_HASH).remove(KEY_REMOTE_KILL_PHRASE_FORMAT_V2).apply()
        }
    }

    override fun hasRemoteKillPhrase(): Boolean {
        return destroyStore.getString(KEY_REMOTE_KILL_PHRASE_HASH, null) != null
    }

    override fun getRemoteKillAmount(): Long {
        return prefs.getLong(KEY_REMOTE_KILL_AMOUNT, DEFAULT_REMOTE_KILL_AMOUNT)
    }

    override fun setRemoteKillAmount(amountZatoshi: Long) {
        prefs.edit().putLong(KEY_REMOTE_KILL_AMOUNT, amountZatoshi).apply()
    }

    override fun getRemoteKillArmedHeight(): Long = prefs.getLong(KEY_REMOTE_KILL_ARMED_HEIGHT, 0L)

    override fun setRemoteKillArmedHeight(height: Long) {
        // MONOTONIC FORWARD. The floor may rise but must never fall.
        //
        // It must be able to RISE, because remote kill can be disarmed and re-armed: armed at 100,
        // disabled at 120, a matching transaction arrives at 150 and is correctly ignored while
        // disabled, then re-armed at 200. If the floor stayed pinned at 100 that transaction would
        // sit above it, outside the seen-set, and wipe the wallet on the next scan — the very bug
        // this guard exists to stop. setupRemoteKill re-anchors on every arm, so re-arming is safe.
        // (That arming path is NOT reachable — setupRemoteKill has no caller and no build has an arming
        // screen — so the disarm/re-arm scenario above cannot occur today. The only writer that runs is
        // ChatViewModel.anchorRemoteKillFloorIfUnset, once, over a floor of 0. The rules are kept so the
        // setter stays correct if arming is ever wired.)
        //
        // It must never FALL to another positive value, because lowering it would re-expose history
        // that was already ruled out.
        //
        // A non-positive height is the one exception: it explicitly means "unknown" and DROPS the
        // anchor. Arming uses that when it cannot read the chain tip, because a stale floor left
        // over from an earlier arming is worse than no floor at all — no floor makes the kill check
        // fail CLOSED and re-anchor at the current tip on the next scan, whereas a stale one would
        // leave everything received since that earlier arming eligible to wipe the wallet.
        if (height <= 0L) {
            prefs.edit().remove(KEY_REMOTE_KILL_ARMED_HEIGHT).commit()
            return
        }
        if (height <= prefs.getLong(KEY_REMOTE_KILL_ARMED_HEIGHT, 0L)) return
        // commit(), not apply(): this floor is the guard standing between a re-delivered historical
        // memo and an irreversible wallet wipe. It must be on disk before the scan that anchored it
        // can act on anything.
        prefs.edit().putLong(KEY_REMOTE_KILL_ARMED_HEIGHT, height).commit()
    }

    override fun hasProcessedKillSignalTx(txId: String): Boolean =
        synchronized(killSeenLock) { killSeenIds.contains(txId) }

    override fun markKillSignalTxProcessed(txId: String) {
        synchronized(killSeenLock) {
            if (!killSeenIds.add(txId)) return
            evictOldestToCapacity(killSeenIds, MAX_SEEN_KILL_SIGNALS)
            // commit(), not apply(): the caller DESTROYS THE WALLET the moment this returns, so the
            // record has to be durable before the destruction starts — same reasoning as the
            // KEXACK spend budget, which commits before money moves.
            killSeenPrefs.edit().putString(KILL_SEEN_KEY, killSeenIds.joinToString("\n")).commit()
        }
    }

    override fun getDecryptedText(ciphertextHash: String): String? =
        decryptedTextPrefs.getString(ciphertextHash, null)

    override fun putDecryptedText(ciphertextHash: String, plaintext: String) {
        decryptedTextPrefs.edit().putString(ciphertextHash, plaintext).apply()
    }

    override fun removeDecryptedText(ciphertextHash: String) {
        decryptedTextPrefs.edit().remove(ciphertextHash).apply()
    }

    @Synchronized
    override fun getOrPutPendingTxFirstSeenMillis(txId: String, nowMillis: Long): Long {
        val key = "pending_tx_first_seen_$txId"
        val existing = prefs.getLong(key, -1L)
        if (existing > 0L) return existing
        prefs.edit().putLong(key, nowMillis).apply()
        return nowMillis
    }
    override fun clearPendingTxFirstSeen(txId: String) {
        prefs.edit().remove("pending_tx_first_seen_$txId").apply()
    }

    // Round 8 D11 (G14): under walletScopeLock from the first bump to the last write, and the generation is raised
    // again after the wipe, so a reply write checked before the wipe cannot land in the wiped (next wallet's) stores.
    override fun clearAll() {
        synchronized(ZchatPreferences.walletScopeLock) {
            walletScopeReloads++
            clearAllStores()
            walletScopeReloads++
        }
    }

    private fun clearAllStores() {
        // Use commit() instead of apply() for security-critical clear operations.
        // If the app is killed before async apply() completes, sensitive data persists.
        prefs.edit().clear().commit()
        // #226: reset the shared read-marker flow to match the wiped store. The disappearing-TTL flow is
        // the same kind of process-wide in-memory mirror backed by `prefs` (just cleared above) — reset it
        // too, otherwise it keeps serving the previous identity's TTLs after a reset.
        _readMarkers.value = emptyMap()
        _disappearingTtls.value = emptyMap()
        // #37 pending mode-switch prompts are the same kind of in-memory mirror, backed by modePrefs
        // (cleared below) — reset so a wiped identity doesn't keep serving stale prompts.
        _pendingModeSwitches.value = emptyMap()
        decryptedTextPrefs.edit().clear().commit()
        peerStatusPrefs.edit().clear().commit()
        convMappingPrefs.edit().clear().commit()
        nicknamePrefs.edit().clear().commit()
        draftPrefs.edit().clear().commit()
        // W-72 (D1): the emptied key store has nothing to mark; the done-flag goes in right after the wipe, so a key stored after it
        // is never marked by a later start (a restore's keys are marked by the history-read rule, D2).
        e2ePrefs.edit().clear().commit()
        e2ePrefs.edit().putBoolean(LEGACY_KEY_DONE, true).commit()
        // Group chat prefs
        groupInfoPrefs.edit().clear().commit()
        groupMembersPrefs.edit().clear().commit()
        groupKeysPrefs.edit().clear().commit()
        groupDraftPrefs.edit().clear().commit()
        groupSeqPrefs.edit().clear().commit()
        groupMsgPrefs.edit().clear().commit()
        // Pending messages
        pendingMsgPrefs.edit().clear().commit()
        callLogPrefs.edit().clear().commit()
        // Unroutable messages
        unroutableMsgPrefs.edit().clear().commit()
        // Conversation modes AND the NOSTR identity keys (pubkey/relay/bootsent) live here —
        // both are sensitive and must be wiped on destroy/reset, not just by the destroy
        // file-nuke backstop.
        modePrefs.edit().clear().commit()
        // B3: the held group replies (R4-1 moved them out of modePrefs into their own encrypted store) carry the
        // inviter's address, our own address and a signed memo — the same wipe-on-reset rule as modePrefs.
        heldGroupAcceptPrefs.edit().clear().commit()
        // CRITICAL: the per-message-key ratchet session state (counters/seen-sets = E2E session
        // material) lives in ratchetPrefs. It MUST be wiped on destroy/reset, not left to the
        // best-effort file-nuke. Also clear the view-once consumed-file markers.
        ratchetPrefs.edit().clear().commit()
        viewOncePrefs.edit().clear().commit()
        // #188 persistent NOSTR replay-dedup set — not sensitive, but it must not survive a wipe or a
        // fresh wallet would carry the previous identity's seen-event history. Same destroy/reset
        // principle as the rest: clear it here so the targeted clear is complete, not file-nuke-only.
        nostrSeenPrefs.edit().clear().commit()
        // #201 processed-KEX-txid dedup set — same wipe-on-destroy principle as nostrSeenPrefs.
        kexSeenPrefs.edit().clear().commit()
        // ZBOOT signature dedup set — same principle; a fresh wallet must not inherit prior ZBOOT history.
        bootSeenPrefs.edit().clear().commit()
        // Acted-on remote-kill signal txids — same principle; a fresh wallet must not inherit the
        // previous identity's kill history (and the armed-height floor in `prefs` goes with the
        // rest of the remote-kill config, so the guard re-anchors cleanly for the new wallet).
        killSeenPrefs.edit().clear().commit()
        // The two seen-sets above also have in-memory LinkedHashSet mirrors. Clearing only the disk file
        // leaves the mirrors populated, so the next markNostrEventSeen/markKexTxProcessed re-serializes the
        // ENTIRE stale set straight back to disk — resurrecting the previous identity's seen-event history
        // that #188 says must not survive. Clear the mirrors under their locks too.
        synchronized(nostrSeenLock) { nostrSeenLedger.clear() }
        synchronized(kexSeenLock) { kexSeenLedger.clear() }
        synchronized(bootSeenLock) { bootSeenIds.clear() }
        synchronized(killSeenLock) { killSeenIds.clear() }
        // #210 NOSTR reactions hold peer sender addresses + target message-id links — conversation-graph
        // metadata that must not survive a destroy/reset. (Was the only store missing from this wipe.)
        reactionPrefs.edit().clear().commit()
        // #224 inbound OPEN contact requests hold a claimed address + first-message plaintext, plus the
        // blocked-pubkey set — sensitive, must be wiped on destroy/reset.
        messageRequestPrefs.edit().clear().commit()
        // P2c KR-1: copies of unreadable encrypted stores (ciphertext + keyset) and the open-failure
        // counter belong to this identity too — a reset must not leave them behind.
        // ZC-733-B (rule S14): only this wallet's: a copy tagged with another wallet stays with its marks.
        val listed = unreadableStoreCopyNames(sharedPrefsDir.list()?.toList().orEmpty())
        val marksFile = java.io.File(sharedPrefsDir, "$ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME.xml")
        if (listed.isEmpty() && !marksFile.exists()) {
            appContext.deleteSharedPreferences(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
        } else {
            val keptCopies = KeptChatStoreCopies.forApp(appContext)
            keptCopies.copiesToWipe(listed).forEach { appContext.deleteSharedPreferences(it) }
            if (!keptCopies.cleanMarksForWipe()) appContext.deleteSharedPreferences(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
        }
    }

    // P2e: the same stores clearAll() clears, by file name. The ratchet counter floor stays out on purpose:
    // it only ever rises, is not secret, and must survive anything that resets the ratchet state.
    override fun walletScopedStores(): List<WalletScopedStore> =
        listOf(
            PREFS_NAME to prefs,
            DECRYPTED_TEXT_PREFS_NAME to decryptedTextPrefs,
            PEER_STATUS_PREFS_NAME to peerStatusPrefs,
            CONV_MAPPING_PREFS_NAME to convMappingPrefs,
            NICKNAME_PREFS_NAME_ENC to nicknamePrefs,
            DRAFT_PREFS_NAME_ENC to draftPrefs,
            E2E_PREFS_NAME to e2ePrefs,
            GROUP_INFO_PREFS_NAME_ENC to groupInfoPrefs,
            GROUP_MEMBERS_PREFS_NAME_ENC to groupMembersPrefs,
            GROUP_KEYS_PREFS_NAME to groupKeysPrefs,
            GROUP_DRAFT_PREFS_NAME_ENC to groupDraftPrefs,
            GROUP_SEQ_PREFS_NAME to groupSeqPrefs,
            GROUP_MSG_PREFS_NAME_ENC to groupMsgPrefs,
            PENDING_MSG_PREFS_NAME_ENC to pendingMsgPrefs,
            CALL_LOG_PREFS_NAME to callLogPrefs,
            UNROUTABLE_MSG_PREFS_NAME to unroutableMsgPrefs,
            "zchat_conversation_mode" to modePrefs,
            // B3: a held group reply belongs to the wallet that joined the group; it is set aside and put back
            // with that wallet, so another phrase never sees (or drops, or pays) it.
            HELD_GROUP_ACCEPT_PREFS_NAME_ENC to heldGroupAcceptPrefs,
            "zchat_ratchet_state" to ratchetPrefs,
            "zchat_view_once" to viewOncePrefs,
            "zchat_nostr_seen" to nostrSeenPrefs,
            "zchat_kex_seen" to kexSeenPrefs,
            "zchat_boot_seen" to bootSeenPrefs,
            "zchat_kill_seen" to killSeenPrefs,
            NOSTR_REACTION_PREFS_NAME_ENC to reactionPrefs,
            MESSAGE_REQUEST_PREFS_NAME to messageRequestPrefs,
        ).map { (name, store) -> PrefsWalletScopedStore(name) { store } }

    // C2: see walletScopeGeneration(). Raised at the start of the reload and of a wipe. A holder that re-checks it
    // under its own lock before each write (ChatViewModel's held group replies) writes nothing into the new
    // wallet's stores once it is raised.
    // Round 8 D11 (G14): the stores are imported just before the reload, so the claim also raises it before any
    // export or import (beginWalletScopeChange), and the claim, the wipe and those writers share walletScopeLock:
    // a write can no longer land between the import and the reload.
    @Volatile
    private var walletScopeReloads: Long = 0L

    override fun walletScopeGeneration(): Long = walletScopeReloads

    override fun beginWalletScopeChange() {
        synchronized(ZchatPreferences.walletScopeLock) { walletScopeReloads++ }
    }

    override fun reloadWalletScopedStores() {
        synchronized(ZchatPreferences.walletScopeLock) { walletScopeReloads++ }
        _readMarkers.value = loadAllLastReadTimestampsRaw()
        _disappearingTtls.value = loadAllDisappearingTtlsRaw()
        _pendingModeSwitches.value = loadAllPendingModeSwitchesRaw()
        // KI-24: these two keep a time or a height per entry and a floor; the ZBOOT and kill sets keep their format.
        synchronized(nostrSeenLock) { loadNostrSeen(nostrSeenLedger) }
        synchronized(kexSeenLock) { loadKexSeen(kexSeenLedger) }
        reloadSeenSet(bootSeenLock, bootSeenIds, bootSeenPrefs, BOOT_SEEN_KEY)
        reloadSeenSet(killSeenLock, killSeenIds, killSeenPrefs, KILL_SEEN_KEY)
        // M1: the wallet just loaded may never have had the one-time relay-key repair (its flag lives in its own stores).
        repairDoubleBoundNostrKeysAtStart()
        // W-72 (D1): nor the pre-update marks (a store brought in without the done-flag is marked now).
        markLegacyKeysAtStart { isMainProcess(appContext) }
        bumpE2EHandshakeTick()
    }

    private fun reloadSeenSet(
        lock: Any,
        ids: LinkedHashSet<String>,
        store: SharedPreferences,
        key: String
    ) = synchronized(lock) {
        ids.clear()
        val stored = store.getString(key, null) ?: return@synchronized
        ids.addAll(stored.split('\n').filter { it.isNotEmpty() })
    }

    // ==========================================
    // CONVERSATION IDs IMPLEMENTATION
    // ==========================================

    override fun getConversationId(peerAddress: String): String? {
        val result = sendIdOf(peerAddress)
        android.util.Log.d("ZCHAT_CONVID", "getConversationId(${peerAddress.redactAddress()}) = ${result?.redactConvId()}")
        return result
    }

    override fun setConversationId(peerAddress: String, convId: String) {
        // Validate inputs to prevent storage corruption
        if (peerAddress.isBlank()) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationId: REJECTED blank peerAddress")
            return
        }
        if (convId.length != 8 || !convId.all { it in 'A'..'Z' || it in '0'..'9' }) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationId: REJECTED invalid convId format: ${convId.redactConvId()}")
            return
        }
        android.util.Log.d("ZCHAT_CONVID", "setConversationId: peer=${peerAddress.redactAddress()}, convId=${convId.redactConvId()}")
        // Write both directions. Do NOT delete old conv:X entries — they may be
        // the remote device's convId which is still needed for routing incoming messages.
        synchronized(this) {
            // FC-ID (ZC-892): an id's address is written once.
            if (convIdHeldByAnother(convId, peerAddress)) return
            // ZC-757 (k) round 11 (ZC-1150): an id restored for a receiver form inside the address is never its send id.
            if (notSendIdFor(convId, peerAddress)) return
            val editor = convMappingPrefs.edit()
                .putString("peer:$peerAddress", convId)
                .putString("conv:$convId", peerAddress)
            val success = editor.commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "FAILED to write convId mapping!")
            }
        }
    }

    override fun getOrCreateConversationId(peerAddress: String): Pair<String, Boolean> {
        synchronized(this) {
            val existing = sendIdOf(peerAddress)
            if (existing != null) {
                android.util.Log.d("ZCHAT_CONVID", "getOrCreateConversationId(${peerAddress.redactAddress()}) = existing ${existing.redactConvId()}")
                return existing to false
            }
            // FC-ID: an id nobody placed, recorded as this phone's own in the same commit.
            val newId = freshConversationId()
            val editor = convMappingPrefs.edit()
                .putString("peer:$peerAddress", newId)
                .putString("conv:$newId", peerAddress)
                .putString(ownIdKey(newId), peerAddress)
            val success = editor.commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "getOrCreateConversationId: FAILED to write!")
            }
            android.util.Log.d("ZCHAT_CONVID", "getOrCreateConversationId(${peerAddress.redactAddress()}) = new ${newId.redactConvId()}")
            return newId to true
        }
    }

    private fun initNotSentKey(peerAddress: String) = "initpending:$peerAddress"

    // ZC-1480 item 3: the id whose INIT went over the relays only (Open); the chain still owes it.
    private fun initOwedOnChainKey(peerAddress: String) = "initowedonchain:$peerAddress"

    override fun markConversationInitOwedOnChain(
        peerAddress: String,
        convId: String,
    ) {
        synchronized(this) {
            if (convMappingPrefs.getString(initOwedOnChainKey(peerAddress), null) == convId) return
            convMappingPrefs.edit().putString(initOwedOnChainKey(peerAddress), convId).apply()
        }
    }

    override fun getConversationIdOwedInitOnChain(peerAddress: String): String? =
        synchronized(this) {
            val existing = sendIdOf(peerAddress) ?: return@synchronized null
            existing.takeIf { convMappingPrefs.getString(initOwedOnChainKey(peerAddress), null) == it }
        }

    override fun getOrCreateConversationIdForMessage(peerAddress: String): Pair<String, Boolean> {
        synchronized(this) {
            val existing = sendIdOf(peerAddress)
            if (existing != null) {
                // ZC-1480 item 3: an id whose INIT went over the relays only still owes the chain its INIT.
                return existing to (
                    convMappingPrefs.getString(initNotSentKey(peerAddress), null) == existing ||
                        convMappingPrefs.getString(initOwedOnChainKey(peerAddress), null) == existing
                )
            }
            // FC-ID: an id nobody placed, recorded as this phone's own in the same commit.
            val newId = freshConversationId()
            // One commit: the id never exists without its "INIT not sent" mark, even across a process death.
            val success =
                convMappingPrefs
                    .edit()
                    .putString("peer:$peerAddress", newId)
                    .putString("conv:$newId", peerAddress)
                    .putString(ownIdKey(newId), peerAddress)
                    .putString(initNotSentKey(peerAddress), newId)
                    .commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "getOrCreateConversationIdForMessage: FAILED to write!")
            }
            return newId to true
        }
    }

    override fun needsConversationInit(peerAddress: String): Boolean =
        synchronized(this) {
            val existing = sendIdOf(peerAddress) ?: return@synchronized true
            convMappingPrefs.getString(initNotSentKey(peerAddress), null) == existing
        }

    override fun markConversationInitSubmitted(
        peerAddress: String,
        convId: String
    ) {
        synchronized(this) {
            val notSent = convMappingPrefs.getString(initNotSentKey(peerAddress), null) == convId
            val owed = convMappingPrefs.getString(initOwedOnChainKey(peerAddress), null) == convId
            if (!notSent && !owed) return
            val edit = convMappingPrefs.edit()
            if (notSent) edit.remove(initNotSentKey(peerAddress))
            if (owed) edit.remove(initOwedOnChainKey(peerAddress))
            check(edit.commit()) {
                "Couldn't record that the first message is being sent; nothing was sent."
            }
        }
    }

    override fun getPeerByConversationId(convId: String): String? {
        val result = convMappingPrefs.getString("conv:$convId", null)
        android.util.Log.d("ZCHAT_CONVID", "getPeerByConversationId(${convId.redactConvId()}) = ${result?.redactAddress()}")

        // Log bidirectional inconsistency but do NOT auto-repair here.
        // Auto-repair in a read path is destructive: it can clobber newer mappings
        // written by setConversationId (e.g., after convId renegotiation).
        // The validateAndRepairConvIdMappings() function handles repair at startup.
        if (result != null) {
            val reverseConvId = convMappingPrefs.getString("peer:$result", null)
            if (reverseConvId != convId) {
                // ZC-1679 (Q2-82): a chat has two ids by design (ours for sending in peer:, the contact's in conv:), so a
                // different send id is no conflict. Warn only when the address's own send id is filed under another
                // address (a real conv-to-peer conflict); the normal state is a debug line.
                val ownIdPeer = reverseConvId?.let { convMappingPrefs.getString("conv:$it", null) }
                val ids = "convId=${convId.redactConvId()} peer=${result.redactAddress()} reverseConvId=${reverseConvId?.redactConvId()}"
                if (ownIdPeer != null && ownIdPeer != result) {
                    val owner = ownIdPeer.redactAddress()
                    android.util.Log.w("ZCHAT_CONVID", "Inconsistent mapping detected (read-only, not repairing): $ids ownIdPeer=$owner")
                } else {
                    android.util.Log.d("ZCHAT_CONVID", "second id of a chat (no conflict): $ids")
                }
            }
        }
        return result
    }

    override fun setConversationMapping(convId: String, peerAddress: String) {
        // Validate inputs to prevent storage corruption
        if (convId.length != 8 || !convId.all { it in 'A'..'Z' || it in '0'..'9' }) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationMapping: REJECTED invalid convId format: ${convId.redactConvId()}")
            return
        }
        if (peerAddress.isBlank()) {
            android.util.Log.e("ZCHAT_CONVID", "setConversationMapping: REJECTED blank peerAddress")
            return
        }
        android.util.Log.d("ZCHAT_CONVID", "setConversationMapping: convId=${convId.redactConvId()}, peer=${peerAddress.redactAddress()}")
        // Write ONLY the conv→peer direction. A peer can have multiple convIds
        // (one generated locally for sending, one received from the remote device).
        // The peer→convId direction is managed exclusively by setConversationId()
        // and getOrCreateConversationId() for OUR outgoing convId.
        // NEVER delete old conv:X entries here — they may belong to the remote side.
        synchronized(this) {
            // FC-ID (ZC-892): an id's address is written once; no inbound memo moves it to the address it claims.
            if (convIdHeldByAnother(convId, peerAddress)) return
            // ZC-757 (k) round 12 (ZC-1155): an id restored for a receiver form inside the address and already filed under
            // it has nothing left to write (never its send id), so the startup repair writes nothing for it.
            if (notSendIdFor(convId, peerAddress) && convMappingPrefs.getString("conv:$convId", null) == peerAddress) return
            val editor = convMappingPrefs.edit()
                .putString("conv:$convId", peerAddress)
            // Only set peer→convId if no mapping exists yet (don't overwrite our own convId). ZC-757 (k) round 11
            // (ZC-1150): never an id restored for a receiver form inside the address (the startup repair calls this too).
            val existingConvId = convMappingPrefs.getString("peer:$peerAddress", null)
            if (existingConvId == null && !notSendIdFor(convId, peerAddress)) {
                editor.putString("peer:$peerAddress", convId)
            }
            val success = editor.commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "FAILED to write convId mapping!")
            }
        }
    }

    /** FC-ID: the own-id record of [convId] (`own:<id>` = the address the id was made for). */
    private fun ownIdKey(convId: String) = "own:$convId"

    /**
     * ZC-757 (k) round 11 (ZC-1150): the marker `nosendid:<id>` = the address [restoreOwnConversationId] filed the id under
     * with asSendId false (our restored send paid only a receiver form inside that address). A key of its own in the id
     * file: no existing entry changes, and a reader that does not know it ignores it.
     */
    private fun notSendIdKey(convId: String) = "nosendid:$convId"

    /** ZC-757 (k) round 11: true when [convId] was restored for a receiver form inside [peerAddress] (never its send id). */
    private fun notSendIdFor(
        convId: String,
        peerAddress: String,
    ): Boolean = convMappingPrefs.getString(notSendIdKey(convId), null) == peerAddress

    /**
     * ZC-757 (k) round 11: `peer:<address>`, unless it names an id restored for a receiver form inside [peerAddress] (an
     * entry a repair or an earlier write made before the marker was read): that one counts as none, and the next id made
     * for the address replaces it.
     */
    private fun sendIdOf(peerAddress: String): String? =
        convMappingPrefs.getString("peer:$peerAddress", null)?.takeUnless { notSendIdFor(it, peerAddress) }

    /** FC-ID: true (and one redacted line logged) when `conv:<id>` already holds an address other than [peerAddress]. */
    private fun convIdHeldByAnother(
        convId: String,
        peerAddress: String,
    ): Boolean {
        val held = convMappingPrefs.getString("conv:$convId", null) ?: return false
        if (held == peerAddress) return false
        android.util.Log.w(
            "ZCHAT_CONVID",
            "convId ${convId.redactConvId()} stays with ${held.redactAddress()}: not moved to ${peerAddress.redactAddress()}",
        )
        return true
    }

    /** FC-ID (5.3a item 2): a new id that has no entry in the file (a placed or known id is never handed out). Under the lock. */
    private fun freshConversationId(): String {
        while (true) {
            val id = ZMSGProtocol.generateConversationId()
            if (convMappingPrefs.getString("conv:$id", null) == null && convMappingPrefs.getString(ownIdKey(id), null) == null) {
                return id
            }
        }
    }

    override fun ownConversationId(peerAddress: String): String? =
        synchronized(this) {
            val id = sendIdOf(peerAddress) ?: return@synchronized null
            id.takeIf { convMappingPrefs.getString(ownIdKey(it), null) == peerAddress }
        }

    override fun isOwnConversationId(
        convId: String,
        peerAddress: String,
    ): Boolean = synchronized(this) { convMappingPrefs.getString(ownIdKey(convId), null) == peerAddress }

    override fun ownConversationIdForStart(peerAddress: String): String? {
        if (peerAddress.isBlank()) return null
        synchronized(this) {
            ownConversationId(peerAddress)?.let { return it }
            val newId = freshConversationId()
            // One commit: peer:, conv:, the own-id record and the "first message not sent" mark. The old conv: entry of
            // a foreign id stays (the contact's memos under it are still filed under this address).
            val success =
                convMappingPrefs
                    .edit()
                    .putString("peer:$peerAddress", newId)
                    .putString("conv:$newId", peerAddress)
                    .putString(ownIdKey(newId), peerAddress)
                    .putString(initNotSentKey(peerAddress), newId)
                    .commit()
            if (!success) {
                android.util.Log.e("ZCHAT_CONVID", "ownConversationIdForStart: FAILED to write — nothing is sent under a new id")
                return null
            }
            android.util.Log.d("ZCHAT_CONVID", "own id for a start to ${peerAddress.redactAddress()}: ${newId.redactConvId()}")
            return newId
        }
    }

    override fun restoreOwnConversationId(
        convId: String,
        peerAddress: String,
    ): Boolean = restoreOwnConversationId(convId, peerAddress, asSendId = true)

    override fun restoreOwnConversationId(
        convId: String,
        peerAddress: String,
        asSendId: Boolean,
    ): Boolean {
        if (peerAddress.isBlank() || convId.length != 8 || !convId.all { it in 'A'..'Z' || it in '0'..'9' }) return false
        synchronized(this) {
            if (convMappingPrefs.getString("conv:$convId", null) != null) return false
            val editor =
                convMappingPrefs
                    .edit()
                    .putString("conv:$convId", peerAddress)
                    .putString(ownIdKey(convId), peerAddress)
            if (asSendId && convMappingPrefs.getString("peer:$peerAddress", null) == null) {
                editor.putString("peer:$peerAddress", convId)
            }
            // ZC-757 (k) round 11 (ZC-1150): the marker, in the same commit, keeps every later write from making the id
            // the address's send id (the startup repair, setConversationMapping, setConversationId).
            if (!asSendId) editor.putString(notSendIdKey(convId), peerAddress)
            val success = editor.commit()
            if (!success) android.util.Log.e("ZCHAT_CONVID", "restoreOwnConversationId: FAILED to write")
            return success
        }
    }

    override fun getAllConversationMappings(): Map<String, String> {
        return convMappingPrefs.all
            .filterKeys { it.startsWith("conv:") }
            .filterValues { it is String }
            .mapKeys { it.key.removePrefix("conv:") }
            .mapValues { it.value as String }
    }

    override fun getAllPeerToConvIdMappings(): Map<String, String> {
        return convMappingPrefs.all
            .filterKeys { it.startsWith("peer:") }
            .filterValues { it is String }
            .mapKeys { it.key.removePrefix("peer:") }
            .mapValues { it.value as String }
    }

    override fun removeConversationMapping(convId: String) {
        synchronized(this) {
            convMappingPrefs.edit()
                .remove("conv:$convId")
                .commit()
        }
    }

    override fun setPeerAddressAlias(repAddress: String, canonicalAddress: String) {
        if (repAddress.isBlank() || canonicalAddress.isBlank() || repAddress == canonicalAddress) return
        // FIXED-POINT INVARIANT: the canonical (live receive) address must resolve to ITSELF, so it can
        // never itself be an alias key. Removing alias:$canonicalAddress here breaks the circular pair
        // that an earlier, wrong-direction alias could leave (live->stale AND stale->live) — which made
        // resolvePeerAddress non-deterministic and could canonicalize a peer's LIVE address back to a
        // stale rep its wallet no longer scans (#218). Stored in the conv-mapping file under "alias:".
        synchronized(this) {
            convMappingPrefs.edit()
                .remove("alias:$canonicalAddress")
                .putString("alias:$repAddress", canonicalAddress)
                .commit()
        }
    }

    override fun clearPeerAddressAlias(address: String) {
        if (address.isBlank()) return
        // Assert [address] is canonical (a fixed point): drop any alias that would map it elsewhere.
        synchronized(this) {
            convMappingPrefs.edit().remove("alias:$address").commit()
        }
    }

    override fun isPeerAddressAliasTarget(address: String): Boolean {
        if (address.isBlank()) return false
        return convMappingPrefs.all.any { (key, value) -> key.startsWith("alias:") && value == address && key != "alias:$address" }
    }

    override fun resolvePeerAddress(address: String): String {
        if (address.isBlank()) return address
        val target = convMappingPrefs.getString("alias:$address", null) ?: return address
        // One hop only, but guard the degenerate self-cycle so we never loop or return a known-stale rep.
        return if (target == address) address else target
    }

    // ==========================================
    // PENDING MESSAGES IMPLEMENTATION
    // ==========================================

    override fun getPendingMessages(): List<ZchatPreferences.PendingMessageData> {
        val result = mutableListOf<ZchatPreferences.PendingMessageData>()
        for ((key, value) in pendingMsgPrefs.all) {
            if (value is String) {
                try {
                    result.add(ZchatPreferences.PendingMessageData.fromJson(value))
                } catch (e: Exception) {
                    Log.w("ZchatPreferences", "Failed to parse pending message: $key", e)
                }
            }
        }
        return result.sortedBy { it.timestampMillis }
    }

    override fun addPendingMessage(message: ZchatPreferences.PendingMessageData) {
        pendingMsgPrefs.edit().putString(message.id, ZchatPreferences.PendingMessageData.toJson(message)).apply()
        ZchatPreferences.markPendingRowsChanged()
        Log.d("ZCHAT_PENDING", "Added pending message: ${message.id.take(8)}... to ${message.peerAddress.redactAddress()}")
    }

    override fun commitPendingMessage(message: ZchatPreferences.PendingMessageData) {
        check(pendingMsgPrefs.edit().putString(message.id, ZchatPreferences.PendingMessageData.toJson(message)).commit()) {
            "Pending message could not be written"
        }
        ZchatPreferences.markPendingRowsChanged()
    }

    override fun removePendingMessage(messageId: String) {
        // ZC-1960: commit(), not apply(), as every other writer under the claim lock: a Delete of a row waiting for its
        // funds is on disk when it returns, so a kill right after it never brings the row back to be sent and paid.
        if (!pendingMsgPrefs.edit().remove(messageId).commit()) {
            Log.w("ZCHAT_PENDING", "Removed pending message not written to disk: ${messageId.take(8)}...")
        }
        ZchatPreferences.markPendingRowsChanged()
        Log.d("ZCHAT_PENDING", "Removed pending message: ${messageId.take(8)}...")
    }

    override fun removePendingMessages(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        val editor = pendingMsgPrefs.edit()
        for (id in messageIds) {
            editor.remove(id)
        }
        editor.apply()
        ZchatPreferences.markPendingRowsChanged()
        Log.d("ZCHAT_PENDING", "Removed ${messageIds.size} pending messages")
    }

    // #210 NOSTR-reaction persistence. One newline-joined block per target id; each line is
    // emoji + U+001F + senderAddress + U+001F + timestampMillis. The unit separator never appears
    // in an emoji or a u1 address, so it is a safe field delimiter.
    override fun addNostrReaction(targetId: String, emoji: String, senderAddress: String, timestampMillis: Long) {
        if (targetId.isBlank() || emoji.isBlank()) return
        // The on-disk format is newline-joined "emoji<US>sender<US>ts" lines (US = U+001F). A peer-supplied emoji (or
        // sender) that embeds '\n' or U+001F would FORGE extra well-formed rows on the next parse — e.g. a
        // reaction attributed to a fabricated sender that bypasses the (emoji, sender) idempotency dedup.
        // Reject any value carrying a structural delimiter.
        if (emoji.any { it == '\n' || it == '\u001F' } || senderAddress.any { it == '\n' || it == '\u001F' }) return
        synchronized(reactionLock) {
            val existing = getNostrReactions(targetId)
            // Idempotent per (emoji, sender) so relay replays / multi-relay publishes do not stack.
            if (existing.any { it.emoji == emoji && it.senderAddress == senderAddress }) return
            val updated = (existing + ZchatPreferences.PersistedReaction(emoji, senderAddress, timestampMillis))
                .takeLast(MAX_REACTIONS_PER_TARGET)
            val serialized = updated.joinToString("\n") { "${it.emoji}\u001F${it.senderAddress}\u001F${it.timestampMillis}" }
            val editor = reactionPrefs.edit().putString(targetId, serialized)
            // Bound the number of distinct target keys so a peer flooding ZREACTs with fabricated
            // targetIds can't grow the prefs file without limit. Only runs when at/over cap AND this is
            // a NEW target; evicts the targets whose NEWEST reaction is oldest.
            val keys = reactionPrefs.all.keys
            if (targetId !in keys && keys.size >= MAX_REACTION_TARGETS) {
                keys.asSequence()
                    .filter { it != targetId }
                    .map { it to (getNostrReactions(it).maxOfOrNull { r -> r.timestampMillis } ?: 0L) }
                    .sortedBy { it.second }
                    .take(keys.size - MAX_REACTION_TARGETS + 1)
                    .forEach { editor.remove(it.first) }
            }
            editor.apply()
        }
    }

    override fun getNostrReactions(targetId: String): List<ZchatPreferences.PersistedReaction> {
        val raw = reactionPrefs.getString(targetId, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("\u001F")
            if (parts.size != 3) return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            ZchatPreferences.PersistedReaction(parts[0], parts[1], ts)
        }
    }

    override fun clearPendingMessages() {
        pendingMsgPrefs.edit().clear().apply()
        ZchatPreferences.markPendingRowsChanged()
        Log.d("ZCHAT_PENDING", "Cleared all pending messages")
    }

    override fun getCallLogMessages(): List<ZchatPreferences.CallLogMessageData> {
        val result = mutableListOf<ZchatPreferences.CallLogMessageData>()
        for ((key, value) in callLogPrefs.all) {
            if (value is String) {
                try {
                    val json = org.json.JSONObject(value)
                    result.add(
                        ZchatPreferences.CallLogMessageData(
                            id = json.getString("id"),
                            peerAddress = json.getString("peerAddress"),
                            timestampMillis = json.getLong("timestampMillis"),
                            type = json.getString("type"),
                            isVideo = json.getBoolean("isVideo"),
                            durationSec = if (json.isNull("durationSec")) null else json.getLong("durationSec"),
                            isOutgoing = json.getBoolean("isOutgoing"),
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ZchatPreferences", "Failed to parse call log: $key", e)
                }
            }
        }
        return result.sortedBy { it.timestampMillis }
    }

    override fun addCallLogMessage(message: ZchatPreferences.CallLogMessageData) {
        val json = org.json.JSONObject().apply {
            put("id", message.id)
            put("peerAddress", message.peerAddress)
            put("timestampMillis", message.timestampMillis)
            put("type", message.type)
            put("isVideo", message.isVideo)
            put("durationSec", message.durationSec ?: org.json.JSONObject.NULL)
            put("isOutgoing", message.isOutgoing)
        }
        callLogPrefs.edit().putString(message.id, json.toString()).apply()
    }

    override fun removeCallLogMessage(id: String) {
        callLogPrefs.edit().remove(id).apply()
    }

    override fun clearCallLogMessages() {
        callLogPrefs.edit().clear().apply()
    }

    // ==========================================
    // NOTIFICATION PRIVACY IMPLEMENTATION
    // ==========================================

    override fun getNotificationPrivacy(): NotificationPrivacy {
        val value = prefs.getString(KEY_NOTIFICATION_PRIVACY, null)
        return if (value != null) {
            try {
                NotificationPrivacy.valueOf(value)
            } catch (e: IllegalArgumentException) {
                NotificationPrivacy.FULL_PREVIEW
            }
        } else {
            NotificationPrivacy.FULL_PREVIEW
        }
    }

    override fun setNotificationPrivacy(level: NotificationPrivacy) {
        prefs.edit().putString(KEY_NOTIFICATION_PRIVACY, level.name).apply()
    }

    // ==========================================
    // MESSAGE DRAFTS IMPLEMENTATION
    // ==========================================

    override fun getDraft(peerAddress: String): String? {
        return draftPrefs.getString(peerAddress, null)
    }

    override fun setDraft(peerAddress: String, draft: String) {
        if (draft.isBlank()) {
            // Clear draft if empty
            draftPrefs.edit().remove(peerAddress).apply()
        } else {
            draftPrefs.edit().putString(peerAddress, draft).apply()
        }
    }

    override fun clearDraft(peerAddress: String) {
        draftPrefs.edit().remove(peerAddress).apply()
    }

    override fun getAllDrafts(): Map<String, String> {
        return draftPrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun hasDraft(peerAddress: String): Boolean {
        val draft = getDraft(peerAddress)
        return !draft.isNullOrBlank()
    }

    // ==========================================
    // E2E ENCRYPTION IMPLEMENTATION
    // ==========================================

    override fun isE2EEnabled(peerAddress: String): Boolean {
        return e2ePrefs.getBoolean("$E2E_ENABLED_PREFIX$peerAddress", false)
    }

    override fun setE2EEnabled(peerAddress: String, enabled: Boolean) {
        e2ePrefs.edit().putBoolean("$E2E_ENABLED_PREFIX$peerAddress", enabled).apply()
        bumpE2EHandshakeTick()
    }

    override fun getE2EPrivateKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_OUR_PRIVATE_PREFIX$peerAddress", null)
    }

    override fun getE2EPeerPublicKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", null)
    }

    override fun getE2EOurPublicKey(peerAddress: String): String? {
        return e2ePrefs.getString("$E2E_OUR_PUBLIC_PREFIX$peerAddress", null)
    }

    override fun setE2EOurKeys(peerAddress: String, ourPublicKey: String, ourPrivateKey: String) {
        e2ePrefs.edit()
            .putString("$E2E_OUR_PUBLIC_PREFIX$peerAddress", ourPublicKey)
            .putString("$E2E_OUR_PRIVATE_PREFIX$peerAddress", ourPrivateKey)
            .apply()
    }

    override fun setE2EPeerPublicKey(peerAddress: String, peerPublicKey: String) {
        e2ePrefs.edit()
            .putString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", peerPublicKey)
            .apply()
        bumpE2EHandshakeTick()
    }

    override fun isE2EKeyChanged(peerAddress: String): Boolean {
        return e2ePrefs.getBoolean("e2e_key_changed_$peerAddress", false)
    }

    override fun setE2EKeyChanged(peerAddress: String, changed: Boolean) {
        synchronized(keyOfferLock) {
            val flagged = e2ePrefs.getBoolean("e2e_key_changed_$peerAddress", false)
            val hasRecord = e2ePrefs.contains(keyOfferKey(peerAddress))
            // ZC-545 (R1): while a refused key waits for the user, nothing but the accept, Not their key or a Reset
            // takes the flag down.
            if (!changed && flagged && hasRecord) return
            val editor = e2ePrefs.edit().putBoolean("e2e_key_changed_$peerAddress", changed)
            // A record whose flag was down belongs to an episode that is over (2.12.4 cleared its flag): it goes.
            if (hasRecord && !flagged) editor.remove(keyOfferKey(peerAddress))
            editor.apply()
        }
        // ZC-300: an open chat re-reads its key-changed banner on this tick. The confirmed reconnect clears the flag on the
        // process send scope after the price question has closed, so without it the banner stayed up until another redraw.
        bumpE2EHandshakeTick()
    }

    override fun isE2EVerified(peerAddress: String): Boolean {
        return e2ePrefs.getBoolean("e2e_verified_$peerAddress", false)
    }

    override fun setE2EVerified(peerAddress: String, verified: Boolean) {
        e2ePrefs.edit().putBoolean("e2e_verified_$peerAddress", verified).apply()
    }

    override fun setE2EKexTxId(peerAddress: String, txId: String) {
        e2ePrefs.edit().putString("e2e_kex_txid_$peerAddress", txId).apply()
    }
    override fun getE2EKexTxId(peerAddress: String): String? =
        e2ePrefs.getString("e2e_kex_txid_$peerAddress", null)

    override fun setE2EKexAckTxId(peerAddress: String, txId: String) {
        e2ePrefs.edit().putString("e2e_kexack_txid_$peerAddress", txId).apply()
    }
    override fun getE2EKexAckTxId(peerAddress: String): String? =
        e2ePrefs.getString("e2e_kexack_txid_$peerAddress", null)

    override fun removeE2EKexTxId(peerAddress: String) {
        e2ePrefs.edit().remove("e2e_kex_txid_$peerAddress").apply()
    }

    override fun removeE2EKexAckTxId(peerAddress: String) {
        e2ePrefs.edit().remove("e2e_kexack_txid_$peerAddress").apply()
    }

    override fun setKexTxIds(peerAddress: String, txIds: Set<String>) {
        // Store a COPY (never the caller's live set) under a fresh set instance.
        e2ePrefs.edit().putStringSet("kex_txids_$peerAddress", HashSet(txIds)).apply()
    }
    override fun getKexTxIds(peerAddress: String): Set<String> =
        // Return a COPY — SharedPreferences.getStringSet hands back its internal instance, which must not
        // be mutated or leak into caller state.
        (e2ePrefs.getStringSet("kex_txids_$peerAddress", emptySet()) ?: emptySet()).toSet()

    override fun setKexAckTxIds(peerAddress: String, txIds: Set<String>) {
        e2ePrefs.edit().putStringSet("kexack_txids_$peerAddress", HashSet(txIds)).apply()
    }
    override fun getKexAckTxIds(peerAddress: String): Set<String> =
        (e2ePrefs.getStringSet("kexack_txids_$peerAddress", emptySet()) ?: emptySet()).toSet()

    override fun clearKexTxIds(peerAddress: String) {
        e2ePrefs.edit()
            .remove("kex_txids_$peerAddress")
            .remove("kexack_txids_$peerAddress")
            .apply()
    }

    override fun setQuantumShieldPSK(peerAddress: String, pskBase64: String) {
        e2ePrefs.edit().putString("qs_psk_$peerAddress", pskBase64).commit()
    }
    override fun getQuantumShieldPSK(peerAddress: String): String? =
        e2ePrefs.getString("qs_psk_$peerAddress", null)
    override fun clearQuantumShieldPSK(peerAddress: String) {
        e2ePrefs
            .edit()
            .remove("qs_psk_$peerAddress")
            .remove("qs_bound_$peerAddress")
            // D3-2: the setup record goes with the key; the claims and our seq stay (our seq never goes down).
            .remove("qs_claim_setup_$peerAddress")
            .commit()
    }

    override fun setQuantumShieldBound(peerAddress: String, bound: Boolean) {
        e2ePrefs.edit().putBoolean("qs_bound_$peerAddress", bound).commit()
    }

    override fun getQuantumShieldBound(peerAddress: String): Boolean? =
        if (e2ePrefs.contains("qs_bound_$peerAddress")) e2ePrefs.getBoolean("qs_bound_$peerAddress", false) else null

    override fun setQuantumShieldOurSecret(peerAddress: String, secretBase64: String) {
        e2ePrefs.edit().putString("qs_our_secret_$peerAddress", secretBase64).commit()
    }
    override fun getQuantumShieldOurSecret(peerAddress: String): String? =
        e2ePrefs.getString("qs_our_secret_$peerAddress", null)

    // D3-2: the Quantum Shield claim records (see the interface), one commit each.
    override fun setQsClaimPeer(peerAddress: String, record: String): Boolean =
        e2ePrefs.edit().putString("qs_claim_peer_$peerAddress", record).commit()

    override fun getQsClaimPeer(peerAddress: String): String? = e2ePrefs.getString("qs_claim_peer_$peerAddress", null)

    override fun setQsClaimOurSeq(peerAddress: String, record: String): Boolean =
        e2ePrefs.edit().putString("qs_claim_our_seq_$peerAddress", record).commit()

    override fun getQsClaimOurSeq(peerAddress: String): String? = e2ePrefs.getString("qs_claim_our_seq_$peerAddress", null)

    override fun setQsClaimSentSeq(peerAddress: String, seq: Long): Boolean =
        e2ePrefs.edit().putLong("qs_claim_sent_seq_$peerAddress", seq).commit()

    override fun getQsClaimSentSeq(peerAddress: String): Long = e2ePrefs.getLong("qs_claim_sent_seq_$peerAddress", 0L)

    override fun setQsClaimSetup(peerAddress: String, record: String): Boolean =
        e2ePrefs.edit().putString("qs_claim_setup_$peerAddress", record).commit()

    override fun getQsClaimSetup(peerAddress: String): String? = e2ePrefs.getString("qs_claim_setup_$peerAddress", null)

    override fun getRatchetStateStore(): co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetStateStore = ratchetStore

    override fun isE2EKeyExchangeComplete(peerAddress: String): Boolean {
        val ourPrivate = getE2EPrivateKey(peerAddress)
        val peerPublic = getE2EPeerPublicKey(peerAddress)
        return ourPrivate != null && peerPublic != null
    }

    private fun kexAskedKey(peerAddress: String) = "kexasked:$peerAddress"

    override fun getKexAskedKey(peerAddress: String): String? = e2ePrefs.getString(kexAskedKey(peerAddress), null)

    override fun setKexAskedKey(
        peerAddress: String,
        key: String,
    ) {
        e2ePrefs.edit().putString(kexAskedKey(peerAddress), key).apply()
    }

    // ZC-634: "keyclaim:<peer>" = "<version>\n<key>\n<held ZBOOT, or nothing>", in the E2E key file beside the key it
    // qualifies, so the key, its mark and its asked record are one commit and travel together. ZChat 2.12.4 has no reader.
    private fun keyClaimKey(peerAddress: String) = "keyclaim:$peerAddress"

    private fun encodeKeyClaim(
        key: String,
        held: String?,
    ): String = "$KEY_CLAIM_VERSION\n$key\n${held.orEmpty()}"

    /** The mark's key and held ZBOOT; an entry this build cannot read still stands (naming no key, holding nothing). */
    private fun decodeKeyClaim(raw: String?): Pair<String, String?>? {
        val parts = raw?.split("\n", limit = 3) ?: return null
        if (parts.size < 2 || parts[0] != KEY_CLAIM_VERSION.toString()) return "" to null
        return parts[1] to parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
    }

    override fun storePeerKey(
        peerAddress: String,
        key: String,
        claim: Boolean,
        asked: Boolean,
    ): Boolean = storePeerKey(peerAddress, key, claim, asked, legacy = false)

    override fun storePeerKey(
        peerAddress: String,
        key: String,
        claim: Boolean,
        asked: Boolean,
        legacy: Boolean,
    ): Boolean {
        val saved =
            synchronized(keyOfferLock) {
                val editor = e2ePrefs.edit().putString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", key)
                // W-72 (SPEC_DECISIONS D6): another key over a pre-update mark is stored as a claim (it moves no relay identity).
                val mark = decodeLegacyMark(e2ePrefs.getString(legacyKeyKey(peerAddress), null))
                if (claim || (mark != null && mark.first != key)) {
                    val kept = decodeKeyClaim(e2ePrefs.getString(keyClaimKey(peerAddress), null))?.takeIf { it.first == key }?.second
                    editor.putString(keyClaimKey(peerAddress), encodeKeyClaim(key, kept)).remove("e2e_verified_$peerAddress")
                } else {
                    editor.remove(keyClaimKey(peerAddress))
                }
                // W-72 (R2, store table): a mark is put when asked, kept while it names this key, and goes with another key.
                if (legacy) {
                    editor.putString(legacyKeyKey(peerAddress), encodeLegacyMark(key, mark?.takeIf { it.first == key }?.second))
                    // Review of 3426ade7d, finding 5: a mark for another key takes the old key's probe with it.
                    if (mark != null && mark.first != key) editor.remove(legacyProbeKey(peerAddress))
                } else if (mark != null && mark.first != key) {
                    editor.remove(legacyKeyKey(peerAddress)).remove(legacyProbeKey(peerAddress))
                }
                if (asked) editor.putString(kexAskedKey(peerAddress), key)
                editor.commit()
            }
        if (saved) bumpE2EHandshakeTick()
        return saved
    }

    // W-72 (SPEC_F store table): "legacykey:<peer>" = the contact key a pre-update mark names (then a line break and the
    // ZBOOT the mark held back, when one was), "legacyprobe:<peer>" = the probe's record; both in the E2E key file beside
    // the key and removed with it. ZChat 2.12.4 has no reader for either.
    private fun legacyKeyKey(peerAddress: String) = "legacykey:$peerAddress"

    private fun legacyProbeKey(peerAddress: String) = "legacyprobe:$peerAddress"

    private fun encodeLegacyMark(
        key: String,
        held: String?,
    ): String = if (held.isNullOrEmpty()) key else "$key\n$held"

    /** The mark's key and held ZBOOT, or null: no mark. A Base64 key holds no line break. */
    private fun decodeLegacyMark(raw: String?): Pair<String, String?>? {
        val parts = raw?.split("\n", limit = 2) ?: return null
        return parts[0] to parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    override fun getLegacyKeyMark(peerAddress: String): String? {
        val mark = decodeLegacyMark(e2ePrefs.getString(legacyKeyKey(peerAddress), null)) ?: return null
        val stored = e2ePrefs.getString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", null) ?: return null
        return mark.first.takeIf { it.isNotEmpty() && it == stored }
    }

    override fun getHeldLegacyBoot(peerAddress: String): String? {
        if (getLegacyKeyMark(peerAddress) == null) return null
        return decodeLegacyMark(e2ePrefs.getString(legacyKeyKey(peerAddress), null))?.second
    }

    override fun holdLegacyBoot(
        peerAddress: String,
        raw: String,
    ): Boolean {
        val saved =
            synchronized(keyOfferLock) {
                val key = getLegacyKeyMark(peerAddress) ?: return@synchronized false
                e2ePrefs.edit().putString(legacyKeyKey(peerAddress), encodeLegacyMark(key, raw.take(KEY_CLAIM_HELD_MAX))).commit()
            }
        // As holdClaimedBoot: an open chat redraws its banner.
        if (saved) bumpE2EHandshakeTick()
        return saved
    }

    // W-72 (D4): "legacyprobe:<peer>" = "1\n<id>\n<txid>,<txid>…" (at most LEGACY_PROBE_MAX_TXS, the latest).
    private fun decodeLegacyProbe(raw: String?): Pair<String, List<String>>? {
        val parts = raw?.split("\n", limit = 3) ?: return null
        if (parts.size < 2 || parts[0] != "1" || parts[1].length != CONV_ID_FORMAT_LENGTH) return null
        val txIds =
            parts
                .getOrNull(2)
                .orEmpty()
                .split(',')
                .filter { it.isNotEmpty() }
        return parts[1] to txIds
    }

    private fun encodeLegacyProbe(
        convId: String,
        txIds: List<String>,
    ): String = "1\n$convId\n${txIds.takeLast(LEGACY_PROBE_MAX_TXS).joinToString(",")}"

    override fun getLegacyProbe(peerAddress: String): String? {
        if (getLegacyKeyMark(peerAddress) == null) return null
        return decodeLegacyProbe(e2ePrefs.getString(legacyProbeKey(peerAddress), null))?.first
    }

    override fun getLegacyProbeTxIds(peerAddress: String): String? {
        if (getLegacyKeyMark(peerAddress) == null) return null
        return decodeLegacyProbe(e2ePrefs.getString(legacyProbeKey(peerAddress), null))
            ?.second
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    }

    override fun startLegacyProbe(peerAddress: String): String? {
        getLegacyProbe(peerAddress)?.let { return it }
        if (getLegacyKeyMark(peerAddress) == null) return null
        // The id first, in the conversation-id store (a kill before the record leaves an unused own id, nothing else).
        val fresh =
            synchronized(this) {
                val id = freshConversationId()
                val saved =
                    convMappingPrefs
                        .edit()
                        .putString("conv:$id", peerAddress)
                        .putString(ownIdKey(id), peerAddress)
                        .commit()
                if (saved) id else null
            } ?: return null
        return synchronized(keyOfferLock) {
            if (getLegacyKeyMark(peerAddress) == null) return@synchronized null
            // Another send made one meanwhile: that one is the probe.
            decodeLegacyProbe(e2ePrefs.getString(legacyProbeKey(peerAddress), null))?.first?.let { return@synchronized it }
            if (e2ePrefs.edit().putString(legacyProbeKey(peerAddress), encodeLegacyProbe(fresh, emptyList())).commit()) fresh else null
        }
    }

    override fun addLegacyProbeTx(
        peerAddress: String,
        convId: String,
        txId: String,
    ): Boolean =
        synchronized(keyOfferLock) {
            val probe = decodeLegacyProbe(e2ePrefs.getString(legacyProbeKey(peerAddress), null)) ?: return@synchronized false
            if (probe.first != convId) return@synchronized false
            if (txId in probe.second) return@synchronized true
            e2ePrefs.edit().putString(legacyProbeKey(peerAddress), encodeLegacyProbe(convId, probe.second + txId)).commit()
        }

    // W-72 (R6, D4): "replyconv:<peer>" = "1\n<id>\n<mined height of the memo that set it>\n<1 answered, 0 not>", in the
    // conversation-id store. Its prefix is neither "conv:" nor "peer:", so ZChat 2.12.4's loops over this file skip it.
    private fun replyConvKey(peerAddress: String) = "replyconv:$peerAddress"

    private class ReplyConv(
        val convId: String,
        val height: Long,
        val answered: Boolean,
    )

    private fun decodeReplyConv(raw: String?): ReplyConv? {
        val parts = raw?.split("\n") ?: return null
        if (parts.size != 4 || parts[0] != "1") return null
        val height = parts[2].toLongOrNull() ?: return null
        return ReplyConv(parts[1], height, parts[3] == "1")
    }

    override fun getReplyConversationId(peerAddress: String): String? =
        synchronized(this) {
            val entry = decodeReplyConv(convMappingPrefs.getString(replyConvKey(peerAddress), null)) ?: return@synchronized null
            entry.convId.takeIf { !entry.answered && convMappingPrefs.getString("conv:$it", null) == peerAddress }
        }

    override fun armReplyConversationId(
        peerAddress: String,
        convId: String,
        minedHeight: Long,
    ): Boolean =
        synchronized(this) {
            val entry = decodeReplyConv(convMappingPrefs.getString(replyConvKey(peerAddress), null))
            if (entry != null && minedHeight <= entry.height) return@synchronized true
            convMappingPrefs.edit().putString(replyConvKey(peerAddress), "1\n$convId\n$minedHeight\n0").commit()
        }

    override fun answerReplyConversationId(
        peerAddress: String,
        convId: String,
    ): Boolean =
        synchronized(this) {
            val entry = decodeReplyConv(convMappingPrefs.getString(replyConvKey(peerAddress), null)) ?: return@synchronized false
            if (entry.convId != convId || entry.answered) return@synchronized entry.convId == convId
            convMappingPrefs.edit().putString(replyConvKey(peerAddress), "1\n$convId\n${entry.height}\n1").commit()
        }

    override fun releaseLegacyKey(
        peerAddress: String,
        verified: Boolean,
    ): Boolean = releaseLegacyKeyWithBoot(peerAddress, verified) != null

    override fun releaseLegacyKeyWithBoot(
        peerAddress: String,
        verified: Boolean,
    ): String? {
        val held =
            synchronized(keyOfferLock) {
                val raw = e2ePrefs.getString(legacyKeyKey(peerAddress), null) ?: return@synchronized null
                val stands = getLegacyKeyMark(peerAddress) != null
                val editor = e2ePrefs.edit().remove(legacyKeyKey(peerAddress)).remove(legacyProbeKey(peerAddress))
                if (verified) editor.putBoolean("e2e_verified_$peerAddress", true)
                // A mark that named another key held nothing for this key.
                if (editor.commit()) decodeLegacyMark(raw)?.second?.takeIf { stands }.orEmpty() else null
            }
        if (held != null) bumpE2EHandshakeTick()
        return held
    }

    override fun isLegacyHistoryRead(): Boolean? = e2ePrefs.getBoolean(LEGACY_HISTORY_READ, false)

    override fun setLegacyHistoryRead() {
        if (!e2ePrefs.edit().putBoolean(LEGACY_HISTORY_READ, true).commit()) {
            Log.w("ZCHAT_E2E", "the key store's complete history read was not recorded; the next complete pass records it")
        }
    }

    /** W-72 (D1) — [markLegacyKeysOnce] with a failure logged (the done-flag stays unset, so the next start or reload tries again). */
    private fun markLegacyKeysAtStart(mainProcess: () -> Boolean) {
        runCatching { markLegacyKeysOnce(mainProcess) }
            .onFailure { Log.w("ZCHAT_E2E", "pre-update key marks not written; the next start tries again", it) }
    }

    /**
     * W-72 (SPEC_F R1, SPEC_DECISIONS D1) — every contact key in the key store ("e2e_peer_pub_<address>") gets a pre-update
     * mark naming it, except at an address the user already marked verified (2.12.4 clears that flag at every key change, so
     * the tap was about this key), and the store's done-flag "legacykey_done" is written, all in ONE commit after the whole
     * store was read. A store with the flag is left alone: the first start of this build marks what 2.12.4 left, and a
     * store brought in later without the flag (another wallet's, a kept copy put back) is marked at the next start or
     * reload. Only in the main process ([mainProcess]; one that throws counts as no). A commit that is not saved leaves the
     * flag unset, so the next start tries again. @return how many marks were written, or null: nothing ran or nothing saved.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun markLegacyKeysOnce(mainProcess: () -> Boolean): Int? {
        val main =
            try {
                mainProcess()
            } catch (e: Exception) {
                false
            }
        if (!main) return null
        val marked =
            synchronized(keyOfferLock) {
                val entries = e2ePrefs.all
                if (entries[LEGACY_KEY_DONE] == true) return null
                val editor = e2ePrefs.edit()
                var count = 0
                for ((name, value) in entries) {
                    if (!name.startsWith(E2E_PEER_PUBLIC_PREFIX) || value !is String || value.isEmpty()) continue
                    val peer = name.removePrefix(E2E_PEER_PUBLIC_PREFIX)
                    if (entries["e2e_verified_$peer"] == true) continue
                    if (decodeLegacyMark(entries[legacyKeyKey(peer)] as? String)?.first == value) continue
                    editor.putString(legacyKeyKey(peer), value)
                    count++
                }
                if (!editor.putBoolean(LEGACY_KEY_DONE, true).commit()) return null
                count
            }
        // Review of 3426ade7d, finding 4: no tick here. At the start this runs from the init block, before the tick flow
        // below it exists; a reload bumps the tick itself once the stores are read again.
        Log.d("ZCHAT_E2E", "pre-update key marks: $marked")
        return marked
    }

    override fun getKeyClaim(peerAddress: String): String? = decodeKeyClaim(e2ePrefs.getString(keyClaimKey(peerAddress), null))?.first

    override fun markKeyClaim(
        peerAddress: String,
        key: String,
    ): Boolean {
        val saved =
            synchronized(keyOfferLock) {
                if (e2ePrefs.getString("$E2E_PEER_PUBLIC_PREFIX$peerAddress", null) != key) return@synchronized false
                val held = decodeKeyClaim(e2ePrefs.getString(keyClaimKey(peerAddress), null))?.takeIf { it.first == key }?.second
                e2ePrefs
                    .edit()
                    .putString(keyClaimKey(peerAddress), encodeKeyClaim(key, held))
                    .remove("e2e_verified_$peerAddress")
                    .commit()
            }
        if (saved) bumpE2EHandshakeTick()
        return saved
    }

    override fun findPeerByHeldBootNostrPubkey(pubkeyHex: String): String? {
        val peers =
            e2ePrefs.all.keys.mapNotNull { key ->
                when {
                    key.startsWith("keyclaim:") -> key.removePrefix("keyclaim:")
                    key.startsWith("legacykey:") -> key.removePrefix("legacykey:")
                    else -> null
                }
            }

        fun names(raw: String) =
            co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
                .parse(raw)
                ?.senderNostrPubkeyHex
                .equals(pubkeyHex, ignoreCase = true)
        return peers.distinct().sorted().firstOrNull { peer ->
            listOfNotNull(getHeldClaimBoot(peer), getHeldLegacyBoot(peer)).any(::names)
        } ?: modePrefs.all.keys
            .filter { it.startsWith(ACCEPT_WAITING_BOOT_PREFIX) }
            .map { it.removePrefix(ACCEPT_WAITING_BOOT_PREFIX) }
            .sorted()
            .firstOrNull { peer ->
                getInboundKexAwaitingAccept(peer) != null &&
                    modePrefs.getString(acceptWaitingBootKey(peer), null)?.let(::names) == true
            }
    }

    private val heldConnectionArrivalLock = Any()

    private fun readHeldConnectionArrivals(): LinkedHashMap<String, Long> {
        val arrivals = LinkedHashMap<String, Long>()
        modePrefs.getString(HELD_CONNECTION_ARRIVALS_KEY, null)?.lineSequence()?.forEach { line ->
            val parts = line.split(" ")
            parts.getOrNull(1)?.toLongOrNull()?.let { arrivals[parts[0]] = it }
        }
        return arrivals
    }

    override fun getHeldConnectionArrivalSec(eventId: String): Long? =
        synchronized(heldConnectionArrivalLock) { readHeldConnectionArrivals()[eventId] }

    override fun setHeldConnectionArrivalSec(
        eventId: String,
        arrivalSec: Long,
    ): Boolean =
        synchronized(heldConnectionArrivalLock) {
            val arrivals = readHeldConnectionArrivals()
            arrivals[eventId] = arrivalSec
            while (arrivals.size > HELD_CONNECTION_ARRIVALS_CAP) arrivals.remove(arrivals.keys.first())
            val encoded = arrivals.entries.joinToString("\n") { "${it.key} ${it.value}" }
            modePrefs.edit().putString(HELD_CONNECTION_ARRIVALS_KEY, encoded).commit()
        }

    override fun forgetHeldBoots(peerAddress: String): Boolean {
        val marksSaved =
            synchronized(keyOfferLock) {
                val editor = e2ePrefs.edit()
                var changed = false
                decodeKeyClaim(e2ePrefs.getString(keyClaimKey(peerAddress), null))?.takeIf { it.second != null }?.let {
                    editor.putString(keyClaimKey(peerAddress), encodeKeyClaim(it.first, null))
                    changed = true
                }
                decodeLegacyMark(e2ePrefs.getString(legacyKeyKey(peerAddress), null))?.takeIf { it.second != null }?.let {
                    editor.putString(legacyKeyKey(peerAddress), encodeLegacyMark(it.first, null))
                    changed = true
                }
                !changed || editor.commit()
            }
        val slotSaved = modePrefs.edit().remove(acceptWaitingBootKey(peerAddress)).commit()
        bumpE2EHandshakeTick()
        return marksSaved && slotSaved
    }

    override fun getHeldClaimBoot(peerAddress: String): String? =
        decodeKeyClaim(e2ePrefs.getString(keyClaimKey(peerAddress), null))?.second

    override fun holdClaimedBoot(
        peerAddress: String,
        raw: String,
    ): Boolean =
        synchronized(keyOfferLock) {
            val entry = decodeKeyClaim(e2ePrefs.getString(keyClaimKey(peerAddress), null)) ?: return@synchronized false
            e2ePrefs.edit().putString(keyClaimKey(peerAddress), encodeKeyClaim(entry.first, raw.take(KEY_CLAIM_HELD_MAX))).commit()
        }.also { saved -> if (saved) bumpE2EHandshakeTick() }

    override fun releaseKeyClaim(
        peerAddress: String,
        verified: Boolean,
    ): String? {
        val held =
            synchronized(keyOfferLock) {
                val raw = e2ePrefs.getString(keyClaimKey(peerAddress), null) ?: return@synchronized null
                val editor = e2ePrefs.edit().remove(keyClaimKey(peerAddress))
                if (verified) editor.putBoolean("e2e_verified_$peerAddress", true)
                if (editor.commit()) decodeKeyClaim(raw)?.second.orEmpty() else null
            }
        if (held != null) bumpE2EHandshakeTick()
        return held
    }

    override fun clearE2EKeys(peerAddress: String) {
        synchronized(keyOfferLock) {
            removePeerE2EState(e2ePrefs.edit(), peerAddress)
                // ZC-545: what clearE2EPeerState keeps. Our own key pair and its version.
                .remove("$E2E_OUR_PUBLIC_PREFIX$peerAddress")
                .remove("$E2E_OUR_PRIVATE_PREFIX$peerAddress")
                .remove("$E2E_KEY_VERSION_PREFIX$peerAddress")
                // Provenance is per key-generation: the next gen (Reset Encryption re-KEX) allocates a
                // FRESH seed index — the global counter is never rewound — which preserves the D7
                // generation-scoping exactly like the old random re-gen did (design §4).
                .remove("$E2E_KEY_PROVENANCE_PREFIX$peerAddress")
                // Clear the key-changed marker too, so a later re-establish starts from un-flagged rather than
                // inheriting a stale marker for a new key; and the refused key that waited behind it (ZC-545).
                .remove("e2e_key_changed_$peerAddress")
                .remove(keyOfferKey(peerAddress))
                .apply()
            // ZC-660: a Reset starts over; the key a past accept adopted no longer says anything. Best effort: the Reset's
            // own edit above never depends on it (left behind, it only matters while our key exchange is out or owed).
            runCatching { modePrefs.edit().remove(acceptedKeyKey(peerAddress)).apply() }
        }
        bumpE2EHandshakeTick()
    }

    override fun clearE2EPeerState(peerAddress: String) {
        synchronized(keyOfferLock) { removePeerE2EState(e2ePrefs.edit(), peerAddress).apply() }
        bumpE2EHandshakeTick()
    }

    /**
     * ZC-545 — the removals shared by [clearE2EKeys] (a Reset) and [clearE2EPeerState] (the accept of a contact's new
     * key): everything of the session with [peerAddress] except our own key pair, its provenance and version, the
     * key-changed flag and the remembered refused key.
     */
    private fun removePeerE2EState(
        editor: SharedPreferences.Editor,
        peerAddress: String,
    ): SharedPreferences.Editor =
        editor
            .remove("$E2E_ENABLED_PREFIX$peerAddress")
            .remove("$E2E_PEER_PUBLIC_PREFIX$peerAddress")
            // ZC-599: the asked record goes with the contact key it qualifies.
            .remove(kexAskedKey(peerAddress))
            // ZC-634: so does its claim mark, with the ZBOOT the mark held back.
            .remove(keyClaimKey(peerAddress))
            // W-72: and its pre-update mark with the probe record.
            .remove(legacyKeyKey(peerAddress))
            .remove(legacyProbeKey(peerAddress))
            // Reset establishes a FRESH root (new KEX), so counter 0 is safe again — a lingering R5
            // recovery-pending marker would wrongly gate the fresh conversation. Clear it (the
            // matching floor is dropped by ratchetStore.delete on the re-pair path).
            .remove("$E2E_RECOVERY_FLOOR_PENDING_PREFIX$peerAddress")
            // Clear the verification too, so a later re-establish starts unverified rather than inheriting a stale
            // "verified" for a new key.
            .remove("e2e_verified_$peerAddress")
            // The KEX/KEXACK txids feed the ratchet root derivation and are tied to the keys we're
            // clearing; leaving them would mix a stale txid into the root after a re-KEX, desyncing the
            // root from the peer (who, on a mutual reset, no longer has them either).
            .remove("e2e_kex_txid_$peerAddress")
            .remove("e2e_kexack_txid_$peerAddress")
            // Convergent txid SETS are per key-generation too — a stale old-generation txid would poison
            // the post-reset root and re-desync from the peer (who cleared theirs on their reset).
            .remove("kex_txids_$peerAddress")
            .remove("kexack_txids_$peerAddress")
            // SR-C1: what the contact's relay rumors said about its ZChat goes with its keys.
            .remove(PEER_IRONWOOD_CAP_PREFIX + peerAddress)
            .remove(PEER_IRONWOOD_CAP_PREFIX + resolvePeerAddress(peerAddress))
            // ZC-1474: the zatt1 record goes with them too.
            .remove(PEER_ZATT_CAP_PREFIX + peerAddress)
            .remove(PEER_ZATT_CAP_PREFIX + resolvePeerAddress(peerAddress))
            // D3-2: the Quantum Shield claims were signed with the keys cleared here; all four records go with them.
            .remove("qs_claim_peer_$peerAddress")
            .remove("qs_claim_our_seq_$peerAddress")
            .remove("qs_claim_sent_seq_$peerAddress")
            .remove("qs_claim_setup_$peerAddress")

    // ZC-545: "keyoffer:<peer>" = the one refused key of a contact that waits for the user's decision, a versioned JSON
    // string in the E2E key file next to "e2e_key_changed_<peer>". Absent: none. ZChat 2.12.4 has no reader for it.
    private fun keyOfferKey(peer: String) = "keyoffer:$peer"

    // The record as text: "<version>\n<firstSeenMs>\n<laterOffers>\n<t + txid, or n for none>\n<key>\n<memo>". The memo
    // is last and taken whole, so its own characters never matter; a key (Base64) and a txid hold no line break.
    private fun encodeKeyOffer(offer: ZchatPreferences.RefusedKeyOffer): String =
        listOf(
            KEY_OFFER_VERSION.toString(),
            offer.firstSeenMs.toString(),
            offer.laterOffers.toString(),
            offer.txId?.let { "t$it" } ?: "n",
            offer.publicKey,
            offer.memo,
        ).joinToString("\n")

    private fun decodeKeyOffer(raw: String?): ZchatPreferences.RefusedKeyOffer? {
        // Not readable (another version, a cut value): no key is offered for an accept (fail closed).
        val parts = raw?.split("\n", limit = KEY_OFFER_FIELDS) ?: return null
        if (parts.size != KEY_OFFER_FIELDS || parts[0] != KEY_OFFER_VERSION.toString()) return null
        val firstSeen = parts[1].toLongOrNull() ?: return null
        val later = parts[2].toIntOrNull() ?: return null
        val txId =
            when {
                parts[3] == "n" -> null
                parts[3].startsWith("t") -> parts[3].substring(1)
                else -> return null
            }
        if (parts[4].isEmpty()) return null
        return ZchatPreferences.RefusedKeyOffer(parts[4], parts[5], txId, firstSeen, later)
    }

    /** The record of [peerAddress] while its flag is up; call under [keyOfferLock]. */
    private fun currentKeyOffer(peerAddress: String): ZchatPreferences.RefusedKeyOffer? =
        if (e2ePrefs.getBoolean("e2e_key_changed_$peerAddress", false)) {
            decodeKeyOffer(e2ePrefs.getString(keyOfferKey(peerAddress), null))
        } else {
            null
        }

    override fun getRefusedKeyOffer(peerAddress: String): ZchatPreferences.RefusedKeyOffer? =
        synchronized(keyOfferLock) {
            val offer = currentKeyOffer(peerAddress)
            // R1: a record without its flag (or one this build cannot read) is dropped when read.
            if (offer == null && e2ePrefs.contains(keyOfferKey(peerAddress))) {
                e2ePrefs.edit().remove(keyOfferKey(peerAddress)).apply()
            }
            offer
        }

    override fun hasRefusedKeyOffer(peerAddress: String): Boolean = getRefusedKeyOffer(peerAddress) != null

    override fun rememberRefusedKeyOffer(
        peerAddress: String,
        publicKey: String,
        memo: String,
        txId: String?,
        nowMs: Long,
    ): Boolean {
        val saved =
            synchronized(keyOfferLock) {
                val existing = currentKeyOffer(peerAddress)
                val next =
                    when {
                        existing == null -> ZchatPreferences.RefusedKeyOffer(publicKey, memo, txId, nowMs, 0)
                        existing.publicKey == publicKey -> existing.copy(memo = memo, txId = txId)
                        else -> existing.copy(laterOffers = existing.laterOffers + 1)
                    }
                commitKeyOffer(peerAddress) {
                    putString(keyOfferKey(peerAddress), encodeKeyOffer(next)).putBoolean("e2e_key_changed_$peerAddress", true)
                }
            }
        if (saved) bumpE2EHandshakeTick()
        return saved
    }

    override fun takeRefusedKeyOffer(
        peerAddress: String,
        publicKey: String,
    ): ZchatPreferences.RefusedKeyOffer? {
        val taken =
            synchronized(keyOfferLock) {
                val existing = currentKeyOffer(peerAddress)
                if (existing == null || existing.publicKey != publicKey) return null
                val removed =
                    commitKeyOffer(peerAddress) { remove(keyOfferKey(peerAddress)).remove("e2e_key_changed_$peerAddress") }
                if (removed) existing else null
            }
        if (taken != null) bumpE2EHandshakeTick()
        return taken
    }

    override fun dismissRefusedKeyOffer(
        peerAddress: String,
        publicKey: String,
    ): Boolean {
        val dismissed =
            synchronized(keyOfferLock) {
                val existing = currentKeyOffer(peerAddress)
                if (existing == null || existing.publicKey != publicKey) return false
                commitKeyOffer(peerAddress) { remove(keyOfferKey(peerAddress)).remove("e2e_key_changed_$peerAddress") }
            }
        if (dismissed) bumpE2EHandshakeTick()
        return dismissed
    }

    /**
     * ZC-545 — one commit of the record and the flag of [peerAddress]. A commit that does not reach the disk has still
     * changed the values in memory (SharedPreferences), so both are put back as they were: a failed write changes
     * nothing. Call under [keyOfferLock].
     */
    private fun commitKeyOffer(
        peerAddress: String,
        change: SharedPreferences.Editor.() -> SharedPreferences.Editor,
    ): Boolean {
        val recordBefore = e2ePrefs.getString(keyOfferKey(peerAddress), null)
        val hadFlag = e2ePrefs.contains("e2e_key_changed_$peerAddress")
        val flagBefore = e2ePrefs.getBoolean("e2e_key_changed_$peerAddress", false)
        if (e2ePrefs.edit().change().commit()) return true
        val undo = e2ePrefs.edit()
        if (recordBefore == null) undo.remove(keyOfferKey(peerAddress)) else undo.putString(keyOfferKey(peerAddress), recordBefore)
        if (hadFlag) undo.putBoolean("e2e_key_changed_$peerAddress", flagBefore) else undo.remove("e2e_key_changed_$peerAddress")
        undo.apply()
        return false
    }

    override fun getE2EKeyVersion(peerAddress: String): Int {
        // Default to version 1 (legacy) for backwards compatibility with existing keys
        return e2ePrefs.getInt("$E2E_KEY_VERSION_PREFIX$peerAddress", 1)
    }

    override fun setE2EKeyVersion(peerAddress: String, version: Int) {
        e2ePrefs.edit()
            .putInt("$E2E_KEY_VERSION_PREFIX$peerAddress", version)
            .apply()
    }

    override fun getE2EKeyProvenance(peerAddress: String): String? =
        e2ePrefs.getString("$E2E_KEY_PROVENANCE_PREFIX$peerAddress", null)

    override fun setE2EKeyProvenance(peerAddress: String, provenance: String) {
        e2ePrefs.edit().putString("$E2E_KEY_PROVENANCE_PREFIX$peerAddress", provenance).apply()
    }

    override fun getNextE2ESeedKeyIndex(): Int =
        e2ePrefs.getInt(E2E_SEED_KEY_NEXT_INDEX_KEY, 0)

    override fun setNextE2ESeedKeyIndex(value: Int) {
        // .commit() (synchronous): an .apply() lost to a crash could hand the SAME index — i.e.
        // the same derived keypair — to two different peers on the next launch. Rare call
        // (once per new-peer key generation), so the sync write is cheap.
        e2ePrefs.edit().putInt(E2E_SEED_KEY_NEXT_INDEX_KEY, value).commit()
    }

    override fun setRecoveredE2EKeys(
        peerAddress: String,
        ourPublicKey: String,
        ourPrivateKey: String,
        version: Int,
        provenance: String,
    ) {
        // KR-3: ONE synchronous edit. Separate editors (the keys used to go through .apply(), the marker
        // through .commit()) let a process death leave the recovered key on disk without its send-gate
        // marker; the next recovery then refuses the existing key and the gate is never armed. A failed
        // commit keeps the whole edit in memory for this process and loses the whole edit on restart,
        // where recovery simply runs again: key and marker can never be split.
        val committed =
            e2ePrefs
                .edit()
                .putString("$E2E_OUR_PUBLIC_PREFIX$peerAddress", ourPublicKey)
                .putString("$E2E_OUR_PRIVATE_PREFIX$peerAddress", ourPrivateKey)
                .putInt("$E2E_KEY_VERSION_PREFIX$peerAddress", version)
                .putString("$E2E_KEY_PROVENANCE_PREFIX$peerAddress", provenance)
                .putBoolean("$E2E_RECOVERY_FLOOR_PENDING_PREFIX$peerAddress", true)
                .commit()
        if (!committed) {
            Log.w("ZCHAT_E2E", "Recovered E2E key for ${peerAddress.redactAddress()} not yet on disk; recovery reruns after a restart")
        }
    }

    override fun isE2ERecoveryFloorPending(peerAddress: String): Boolean =
        e2ePrefs.getBoolean("$E2E_RECOVERY_FLOOR_PENDING_PREFIX$peerAddress", false)

    override fun setE2ERecoveryFloorPending(peerAddress: String, pending: Boolean) {
        // .commit() (synchronous): this marker gates a nonce-reuse-critical send. An .apply() lost
        // to a crash right after recovery could drop the gate and let the first post-restore send
        // reuse a burned counter — so it must be durable before the send path can observe its
        // absence. Cleared with the same discipline once the floor is established.
        e2ePrefs.edit().putBoolean("$E2E_RECOVERY_FLOOR_PENDING_PREFIX$peerAddress", pending).commit()
    }

    // ==========================================
    // GROUP CHAT IMPLEMENTATION
    // ==========================================

    override fun saveGroupInfo(groupId: String, groupInfoJson: String) {
        // Save group info
        groupInfoPrefs.edit().putString(groupId, groupInfoJson).apply()
        // Add to group IDs set
        val groupIds = getAllGroupIds().toMutableSet()
        groupIds.add(groupId)
        prefs.edit().putStringSet(GROUP_IDS_KEY, groupIds).apply()
        ZchatPreferences.markGroupStoreChanged(groupId)
    }

    override fun getGroupInfo(groupId: String): String? {
        return groupInfoPrefs.getString(groupId, null)
    }

    // ZC-022 (KI-25 R8): one editor, one commit(). A failed commit() can leave the new strings in memory, so the
    // previous ones are put back (a record that did not exist is removed again).
    override fun saveGroupInfosDurably(records: Map<String, String>): Boolean {
        if (records.isEmpty()) return true
        val previous = records.keys.associateWith { groupInfoPrefs.getString(it, null) }
        val editor = groupInfoPrefs.edit()
        records.forEach { (groupId, json) -> editor.putString(groupId, json) }
        val saved = runCatching { editor.commit() }.getOrDefault(false)
        if (!saved) {
            val restore = groupInfoPrefs.edit()
            previous.forEach { (groupId, json) -> if (json == null) restore.remove(groupId) else restore.putString(groupId, json) }
            runCatching { restore.apply() }
        }
        records.keys.forEach { ZchatPreferences.markGroupStoreChanged(it) }
        return saved
    }

    // ZC-704 (KI-25 R19): all three are tried, in this order, also when an earlier one refused.
    override fun flushGroupStores(): Boolean {
        val keys = commitFlushCounter(groupKeysPrefs)
        val infos = commitFlushCounter(groupInfoPrefs)
        val main = commitFlushCounter(prefs)
        return keys && infos && main
    }

    override fun groupKeyStoreCommitFailed(): Boolean = !commitFlushCounter(groupKeysPrefs)

    /**
     * ZC-704 (KI-25 R19) — one `commit()` of [store] that changes exactly one key: the counter under [FLUSH_SEQ_KEY]
     * grows by one (a value that is no String of digits counts as 0). A commit that changes a key writes the file's
     * whole map, with what earlier `apply()` calls put there. @return false when the store refused or threw.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun commitFlushCounter(store: SharedPreferences): Boolean =
        try {
            val held =
                try {
                    store.getString(FLUSH_SEQ_KEY, null)
                } catch (e: ClassCastException) {
                    null
                }
            val count = held?.takeIf { text -> text.isNotEmpty() && text.all { it in '0'..'9' } }?.toLongOrNull() ?: 0L
            store.edit().putString(FLUSH_SEQ_KEY, (count + 1).toString()).commit()
        } catch (e: Exception) {
            false
        }

    override fun getAllGroupIds(): Set<String> {
        return prefs.getStringSet(GROUP_IDS_KEY, emptySet()) ?: emptySet()
    }

    override fun deleteGroup(groupId: String) {
        // Round 8 MA1 (design §5.0): the announce ledger and the per-member announce state go, but hw and the used
        // invite ids stay as a tombstone (and the counter, which only grows), so an old invite or announce replayed
        // after the delete cannot bring the group back. Not under withGroupLock here: ignoreGroupInvite calls this
        // inside the held-accepts lock, and the lock order is group lock first; the callers take the group lock (MA2).
        // ZC-294: the ids of invites seen while the group was active stay too (they are not in the used ids); the
        // re-invite kept for a later removal goes with the group.
        val old = getGroupAnnounceState(groupId)
        val tombstone =
            AnnounceState(hw = old.hw, usedInviteIds = old.usedInviteIds, seenActiveInviteIds = old.seenActiveInviteIds)
        val announceEditor = groupMembersPrefs.edit().remove(KEY_GROUP_ANN_LEDGER_PREFIX + groupId)
        if (tombstone == AnnounceState()) {
            announceEditor.remove(KEY_GROUP_ANN_STATE_PREFIX + groupId)
        } else {
            announceEditor.putString(KEY_GROUP_ANN_STATE_PREFIX + groupId, announceStateToJson(tombstone))
        }
        announceEditor.commit()
        // Remove group info
        groupInfoPrefs.edit().remove(groupId).apply()
        // Remove members, and the R5-1 message-spend consent given for them: a group re-joined under
        // the same id must ask again before it pays for anybody.
        groupMembersPrefs
            .edit()
            .remove(groupId)
            .remove(KEY_GROUP_MESSAGE_SPEND_CONSENT_PREFIX + groupId)
            .apply()
        // R3-4: and the GROUP_ACCEPT held for consent (the restore also drops one whose group is gone). B3: it
        // lives in the encrypted store since R4-1; the plaintext key an earlier version wrote goes too.
        heldGroupAcceptPrefs.edit().remove(heldGroupAcceptKey(groupId)).apply()
        modePrefs.edit().remove(heldGroupAcceptKey(groupId)).apply()
        // Round 8 D4: and a stranger's invite still waiting for Join (Ignore is this delete).
        heldGroupAcceptPrefs.edit().remove(groupInviteNeedsJoinKey(groupId)).apply()
        // ZC-420: and that group's relay-copy marks (keys, invites, messages, removal notices: "gk|", "gi|", "gm|", "gr|"); a
        // deleted group is never opened again, so nothing else would forget them.
        val relayCopyKeys =
            heldGroupAcceptPrefs.all.keys.filter { key ->
                RELAY_COPY_GROUP_KINDS.any { kind -> key.startsWith("$RELAY_COPY_PREFIX$kind|$groupId|") }
            }
        if (relayCopyKeys.isNotEmpty()) {
            val relayCopyEditor = heldGroupAcceptPrefs.edit()
            relayCopyKeys.forEach { relayCopyEditor.remove(it) }
            relayCopyEditor.apply()
        }
        // Remove draft
        groupDraftPrefs.edit().remove(groupId).apply()
        // Remove sequence
        groupSeqPrefs.edit().remove(groupId).apply()
        // Remove from group IDs set
        val groupIds = getAllGroupIds().toMutableSet()
        groupIds.remove(groupId)
        prefs.edit().putStringSet(GROUP_IDS_KEY, groupIds).apply()
        // Remove all keys for this group. ZC-708: only entries named exactly "<id>_<key number>" (saveGroupKey; the
        // number may be negative), never another group's whose id merely starts with this one.
        val keyPrefix = "${groupId}_"
        val keysToRemove =
            groupKeysPrefs.all.keys.filter { name ->
                name.startsWith(keyPrefix) &&
                    name.substring(keyPrefix.length).let { number -> number.toIntOrNull()?.toString() == number }
            }
        val keysEditor = groupKeysPrefs.edit()
        keysToRemove.forEach { keysEditor.remove(it) }
        keysEditor.apply()
    }

    override fun saveGroupMembers(groupId: String, membersJson: String) {
        val editor = groupMembersPrefs.edit().putString(groupId, membersJson)
        cutGroupMessageSpendConsent(groupId, membersJson, editor)
        editor.apply()
        ZchatPreferences.markGroupStoreChanged(groupId)
    }

    // commit(), not apply(): the paid re-send that follows must never run on a stamp that a kill can lose.
    override fun saveGroupMembersDurably(
        groupId: String,
        membersJson: String
    ): Boolean {
        val editor = groupMembersPrefs.edit().putString(groupId, membersJson)
        cutGroupMessageSpendConsent(groupId, membersJson, editor)
        return editor.commit().also { ZchatPreferences.markGroupStoreChanged(groupId) }
    }

    override fun saveGroupMembersDurably(
        groupId: String,
        membersJson: String,
        state: AnnounceState
    ): Boolean {
        val editor =
            groupMembersPrefs
                .edit()
                .putString(groupId, membersJson)
                .putString(KEY_GROUP_ANN_STATE_PREFIX + groupId, announceStateToJson(state))
        cutGroupMessageSpendConsent(groupId, membersJson, editor)
        return editor.commit().also { ZchatPreferences.markGroupStoreChanged(groupId) }
    }

    /**
     * ZC-130 — every roster write passes here (all of ChatViewModel's and GroupViewModel's), so the R5-1 group-message
     * spend consent ends with the membership it was given for ([GroupMessageConsent.keepFor]): a member who left, was
     * removed or re-invited, and the whole consent when our own row is no longer ACTIVE or the roster does not parse.
     * Written in [editor], the roster's own edit, so the roster and its consent reach memory and disk together; nothing
     * is written when the consent does not change, and a roster write never creates one.
     */
    private fun cutGroupMessageSpendConsent(
        groupId: String,
        membersJson: String,
        editor: SharedPreferences.Editor,
    ) {
        val key = KEY_GROUP_MESSAGE_SPEND_CONSENT_PREFIX + groupId
        val consent = groupMembersPrefs.getStringSet(key, null)?.toSet() ?: return
        val kept =
            GroupMessageConsent.keepFor(
                consent = consent,
                members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson),
                canonical = ::resolvePeerAddress,
                isSelf = ::isSelfAddress,
            )
        if (kept == consent) return
        if (kept.isEmpty()) editor.remove(key) else editor.putStringSet(key, kept)
    }

    override fun getGroupMembers(groupId: String): String? {
        return groupMembersPrefs.getString(groupId, null)
    }

    override fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String) {
        groupKeysPrefs.edit().putString("${groupId}_$keyEpoch", encryptedKey).apply()
    }

    // ZC-778: commit(), and its result is checked. The key is on the disk before its caller sends it to anyone.
    override fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String, durable: Boolean) {
        if (!durable) return saveGroupKey(groupId, keyEpoch, encryptedKey)
        if (!groupKeysPrefs.edit().putString("${groupId}_$keyEpoch", encryptedKey).commit()) {
            throw java.io.IOException("the group key store refused the write")
        }
    }

    override fun getGroupKey(groupId: String, keyEpoch: Int): String? {
        return groupKeysPrefs.getString("${groupId}_$keyEpoch", null)
    }

    override fun getGroupKeyEpoch(groupId: String): Int {
        return prefs.getInt("$GROUP_EPOCH_PREFIX$groupId", 0)
    }

    override fun setGroupKeyEpoch(groupId: String, epoch: Int) {
        prefs.edit().putInt("$GROUP_EPOCH_PREFIX$groupId", epoch).apply()
    }

    // ZC-778: commit(), and its result is checked. Written after the key it names, never before it.
    override fun setGroupKeyEpoch(groupId: String, epoch: Int, durable: Boolean) {
        if (!durable) return setGroupKeyEpoch(groupId, epoch)
        if (!prefs.edit().putInt("$GROUP_EPOCH_PREFIX$groupId", epoch).commit()) {
            throw java.io.IOException("the store refused the group key number")
        }
    }

    override fun getGroupRemovalFloor(groupId: String): Int {
        return prefs.getInt("$GROUP_REMOVAL_FLOOR_PREFIX$groupId", 0)
    }

    override fun setGroupRemovalFloor(groupId: String, epoch: Int) {
        // Monotonic max — a stale/replayed kick must never LOWER the frontier.
        if (epoch > getGroupRemovalFloor(groupId)) {
            prefs.edit().putInt("$GROUP_REMOVAL_FLOOR_PREFIX$groupId", epoch).apply()
        }
    }

    // Round 9 R9-3: the floor and its removal record are written in one edit, so neither is ever stored without the
    // other. The floor stays the monotonic max; the record is read back by getGroupRemovalKickHeights.
    override fun setGroupRemovalFloor(groupId: String, epoch: Int, kickHeight: Long?) {
        synchronized(removalRecordLock) {
            val floorBefore = getGroupRemovalFloor(groupId)
            val record =
                ZMSGGroupProtocol.recordRemovalKick(getGroupRemovalKickHeights(groupId), floorBefore, epoch, kickHeight)
            prefs
                .edit()
                .putString("$GROUP_REMOVAL_KICKS_PREFIX$groupId", ZMSGGroupProtocol.serializeRemovalKicks(record))
                .putInt("$GROUP_REMOVAL_FLOOR_PREFIX$groupId", maxOf(floorBefore, epoch))
                .apply()
        }
    }

    override fun getGroupRemovalKickHeights(groupId: String): Map<Int, Long?> =
        ZMSGGroupProtocol.deserializeRemovalKicks(prefs.getString("$GROUP_REMOVAL_KICKS_PREFIX$groupId", null)).orEmpty()

    override fun isGroupSelfCreated(groupId: String): Boolean {
        return prefs.getBoolean("$GROUP_SELF_CREATED_PREFIX$groupId", false)
    }

    override fun setGroupSelfCreated(groupId: String, created: Boolean) {
        prefs.edit().putBoolean("$GROUP_SELF_CREATED_PREFIX$groupId", created).apply()
    }

    // Round 8 MA1 — the announce records live in the ENCRYPTED group-members store with the roster (they name members),
    // under prefixed keys that can never be a group id, and every write is a commit().
    override fun getGroupAnnounceState(groupId: String): AnnounceState =
        announceStateFromJson(groupMembersPrefs.getString(KEY_GROUP_ANN_STATE_PREFIX + groupId, null))

    override fun saveGroupAnnounceState(
        groupId: String,
        state: AnnounceState
    ): Boolean =
        groupMembersPrefs
            .edit()
            .putString(KEY_GROUP_ANN_STATE_PREFIX + groupId, announceStateToJson(state))
            .commit()
            .also { ZchatPreferences.markGroupStoreChanged(groupId) }

    override fun getGroupAnnounceLedger(groupId: String): List<AnnounceLedgerEntry> =
        announceLedgerFromJson(groupMembersPrefs.getString(KEY_GROUP_ANN_LEDGER_PREFIX + groupId, null))

    override fun casGroupAnnounce(
        groupId: String,
        pair: AnnouncePair,
        expected: AnnounceLedgerEntry?,
        new: AnnounceLedgerEntry?
    ): Boolean =
        withGroupLock(groupId) {
            val next = casAnnounceLedger(getGroupAnnounceLedger(groupId), pair, expected, new)
            next != null &&
                groupMembersPrefs
                    .edit()
                    .putString(KEY_GROUP_ANN_LEDGER_PREFIX + groupId, announceLedgerToJson(next))
                    .commit()
        }

    override fun getGroupAnnounceCounter(groupId: String): Long = groupMembersPrefs.getLong(KEY_GROUP_ANN_COUNTER_PREFIX + groupId, 0L)

    override fun raiseGroupAnnounceCounter(
        groupId: String,
        n: Long
    ): Boolean =
        withGroupLock(groupId) {
            n <= getGroupAnnounceCounter(groupId) ||
                groupMembersPrefs.edit().putLong(KEY_GROUP_ANN_COUNTER_PREFIX + groupId, n).commit()
        }

    override fun isGp1Capable(id: String): Boolean = id in groupMembersPrefs.getStringSet(KEY_GP1_CAPABLE, null).orEmpty()

    override fun markGp1Capable(ids: Set<String>): Boolean =
        synchronized(GP1_CAPABLE_LOCK) {
            val known = groupMembersPrefs.getStringSet(KEY_GP1_CAPABLE, null).orEmpty()
            known.containsAll(ids) ||
                groupMembersPrefs.edit().putStringSet(KEY_GP1_CAPABLE, (known + ids).toSet()).commit()
        }

    // SR-C1: "<1|0>:<atSec>" in the E2E store, keyed by the contact's canonical address (so a full wipe clears it with
    // every key, and clearE2EKeys clears it with this contact's keys).
    override fun getPeerIronwoodCap(peerAddress: String): ZchatPreferences.PeerIronwoodCap? {
        val raw = e2ePrefs.getString(PEER_IRONWOOD_CAP_PREFIX + resolvePeerAddress(peerAddress), null) ?: return null
        val flag = raw.substringBefore(':', "")
        val at = raw.substringAfter(':', "").toLongOrNull() ?: return null
        return when (flag) {
            "1" -> ZchatPreferences.PeerIronwoodCap(capIronwood = true, atSec = at)
            "0" -> ZchatPreferences.PeerIronwoodCap(capIronwood = false, atSec = at)
            else -> null
        }
    }

    override fun recordPeerIronwoodCap(
        peerAddress: String,
        capIronwood: Boolean,
        atSec: Long,
    ): Boolean =
        synchronized(PEER_IRONWOOD_CAP_LOCK) {
            val stored = getPeerIronwoodCap(peerAddress)
            if (stored != null && atSec <= stored.atSec) {
                return@synchronized stored.atSec == atSec && stored.capIronwood == capIronwood
            }
            e2ePrefs
                .edit()
                .putString(PEER_IRONWOOD_CAP_PREFIX + resolvePeerAddress(peerAddress), "${if (capIronwood) 1 else 0}:$atSec")
                .commit()
        }

    // ZC-1474: "<1|0>:<atSec>:<relay key>" in the E2E store, as the SR-C1 record above (wiped the same ways). ZC-1501: the
    // record belongs to the relay key bound when it was written; under any other bound key (or none) there is no record,
    // so a grant never carries over to a new key. A record without a key (none was ever published) counts as none.
    override fun getPeerZattCap(peerAddress: String): ZchatPreferences.PeerIronwoodCap? {
        val raw = e2ePrefs.getString(PEER_ZATT_CAP_PREFIX + resolvePeerAddress(peerAddress), null) ?: return null
        val parts = raw.split(':')
        if (parts.size != 3) return null
        val at = parts[1].toLongOrNull() ?: return null
        val bound = getPeerNostrPubkey(peerAddress)?.takeIf { it.isNotBlank() } ?: return null
        if (!parts[2].equals(bound, ignoreCase = true)) return null
        return when (parts[0]) {
            "1" -> ZchatPreferences.PeerIronwoodCap(capIronwood = true, atSec = at)
            "0" -> ZchatPreferences.PeerIronwoodCap(capIronwood = false, atSec = at)
            else -> null
        }
    }

    // ZC-1501: a newer rumor replaces the record; on an equal time only a rumor without zatt1 does (the downgrade wins
    // a tie). Nothing is recorded while no relay key is bound.
    override fun recordPeerZattCap(
        peerAddress: String,
        capZatt: Boolean,
        atSec: Long,
    ): Boolean =
        synchronized(PEER_ZATT_CAP_LOCK) {
            val bound = getPeerNostrPubkey(peerAddress)?.takeIf { it.isNotBlank() } ?: return@synchronized false
            val stored = getPeerZattCap(peerAddress)
            if (stored != null && (atSec < stored.atSec || (atSec == stored.atSec && (capZatt || !stored.capIronwood)))) {
                return@synchronized stored.atSec == atSec && stored.capIronwood == capZatt
            }
            e2ePrefs
                .edit()
                .putString(PEER_ZATT_CAP_PREFIX + resolvePeerAddress(peerAddress), "${if (capZatt) 1 else 0}:$atSec:${bound.lowercase()}")
                .commit()
        }

    private fun groupKeyGrantPaidKey(grantKey: String): String =
        GROUP_KEY_GRANT_PAID_PREFIX +
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(grantKey.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it) }

    override fun getGroupKeyGrantPaidCount(grantKey: String): Int = prefs.getInt(groupKeyGrantPaidKey(grantKey), 0)

    override fun setGroupKeyGrantPaidCount(grantKey: String, count: Int): Boolean {
        // commit(), and its result returned: this count is what keeps a second request for the same key from being
        // paid for, so it is on disk before the submit it guards.
        val key = groupKeyGrantPaidKey(grantKey)
        val editor = prefs.edit()
        if (count > 0) editor.putInt(key, count) else editor.remove(key)
        return editor.commit()
    }

    override fun isGroupKeyRequestGranted(requestId: String): Boolean =
        prefs.getBoolean("$GROUP_KEY_REQUEST_GRANTED_PREFIX$requestId", false)

    override fun setGroupKeyRequestGranted(requestId: String, granted: Boolean): Boolean {
        // commit(), not apply(): this record is what keeps a money-spending grant from being paid twice, so
        // it must be on disk before the submit it guards, not whenever the async writer gets to it.
        // R5-2: and its result is returned, so a grant whose record did not reach the disk never pays.
        val key = "$GROUP_KEY_REQUEST_GRANTED_PREFIX$requestId"
        val editor = prefs.edit()
        if (granted) {
            editor.putBoolean(key, true)
        } else {
            editor.remove(key)
        }
        return editor.commit()
    }

    override fun getGroupDraft(groupId: String): String? {
        return groupDraftPrefs.getString(groupId, null)
    }

    override fun setGroupDraft(groupId: String, draft: String) {
        if (draft.isBlank()) {
            groupDraftPrefs.edit().remove(groupId).apply()
        } else {
            groupDraftPrefs.edit().putString(groupId, draft).apply()
        }
    }

    override fun clearGroupDraft(groupId: String) {
        groupDraftPrefs.edit().remove(groupId).apply()
    }

    override fun getAllGroupDrafts(): Map<String, String> {
        return groupDraftPrefs.all
            .filterValues { it is String && it.isNotBlank() }
            .mapValues { it.value as String }
    }

    override fun getGroupMessageSequence(groupId: String): Long {
        return groupSeqPrefs.getLong(groupId, 0L)
    }

    @Synchronized
    override fun incrementGroupMessageSequence(groupId: String): Long {
        // @Synchronized (matching advancePeerModeChangeSince / setDisappearingTtl / getOrPutPendingTx…):
        // this is a read-modify-write, so two concurrent group sends (e.g. share fan-out + a user send)
        // could otherwise both read seq=N and both return N+1, handing out a DUPLICATE sequence number
        // that peer-side ordering/dedup may drop as a replay.
        val current = getGroupMessageSequence(groupId)
        val next = current + 1
        groupSeqPrefs.edit().putLong(groupId, next).apply()
        return next
    }

    override fun getGroupMessages(groupId: String): String? {
        return groupMsgPrefs.getString(groupId, null)
    }

    override fun saveGroupMessages(groupId: String, messagesJson: String) {
        groupMsgPrefs.edit().putString(groupId, messagesJson).apply()
        ZchatPreferences.markGroupStoreChanged(groupId)
    }

    // ==========================================
    // IDENTITY MANAGEMENT IMPLEMENTATION
    // ==========================================

    override fun getAllContactAddresses(): Set<String> {
        // Return all addresses that have nicknames (address book contacts)
        return nicknamePrefs.all.keys
    }

    override fun getAllConversationPeerAddresses(): Set<String> {
        // Return all peer addresses from conversation mappings
        return convMappingPrefs.all.keys
            .filter { it.startsWith("peer:") }
            .map { it.removePrefix("peer:") }
            .toSet()
    }

    override fun getPeersWithNostrPubkey(): Set<String> =
        modePrefs.all.entries
            .filter { it.key.startsWith("pubkey:") && it.value is String }
            .map { it.key.removePrefix("pubkey:") }
            .toSet()

    // ==========================================
    // NOTIFICATION SETTINGS IMPLEMENTATION
    // ==========================================

    override fun isNotificationSoundEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_SOUND, true)
    }

    override fun setNotificationSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_SOUND, enabled).apply()
    }

    override fun isNotificationVibrationEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_VIBRATION, true)
    }

    override fun setNotificationVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_VIBRATION, enabled).apply()
    }

    override fun getMutedConversations(): Set<String> {
        return prefs.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet()) ?: emptySet()
    }

    override fun muteConversation(address: String) {
        val current = getMutedConversations().toMutableSet()
        current.add(address)
        prefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, current).apply()
    }

    override fun unmuteConversation(address: String) {
        val current = getMutedConversations().toMutableSet()
        current.remove(address)
        prefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, current).apply()
    }

    override fun isConversationMuted(address: String): Boolean {
        return getMutedConversations().contains(address)
    }

    // ==========================================
    // CONVERSATION READ STATE IMPLEMENTATION
    // ==========================================

    private fun lastReadKey(peerAddress: String) = "$LAST_READ_PREFIX$peerAddress"

    // #226 shared reactive read-marker map. Seeded once from prefs; every monotonic write emits the new
    // map so all ChatViewModel instances (list + detail) recompute unread off the SAME source.
    private val _readMarkers: kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>> =
        kotlinx.coroutines.flow.MutableStateFlow(loadAllLastReadTimestampsRaw())
    override val readMarkers: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _readMarkers

    // #257 handshake-state tick (see interface). Atomic bump; consumers re-read the actual markers.
    private val _e2eHandshakeTicks = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val e2eHandshakeTicks: kotlinx.coroutines.flow.StateFlow<Long> = _e2eHandshakeTicks
    private fun bumpE2EHandshakeTick() { _e2eHandshakeTicks.update { it + 1 } }

    // B17 disappearing-messages TTL — #226 process-wide singleton flow (mirror of _readMarkers).
    private fun loadAllDisappearingTtlsRaw(): Map<String, ZchatPreferences.DisappearingTtl> =
        prefs.all.keys.filter { it.startsWith(DISAPPEAR_TTL_PREFIX) }.mapNotNull { key ->
            val peer = key.removePrefix(DISAPPEAR_TTL_PREFIX)
            val ttl = prefs.getLong(key, -1L)
            if (ttl < 0) null else peer to ZchatPreferences.DisappearingTtl(ttl, prefs.getLong("$DISAPPEAR_SINCE_PREFIX$peer", 0L))
        }.toMap()
    private val _disappearingTtls = kotlinx.coroutines.flow.MutableStateFlow(loadAllDisappearingTtlsRaw())
    override val disappearingTtls: kotlinx.coroutines.flow.StateFlow<Map<String, ZchatPreferences.DisappearingTtl>> = _disappearingTtls
    override fun getDisappearingTtl(peerAddress: String): ZchatPreferences.DisappearingTtl? {
        val ttl = prefs.getLong("$DISAPPEAR_TTL_PREFIX$peerAddress", -1L)
        return if (ttl < 0) null else ZchatPreferences.DisappearingTtl(ttl, prefs.getLong("$DISAPPEAR_SINCE_PREFIX$peerAddress", 0L))
    }
    @Synchronized
    override fun setDisappearingTtl(peerAddress: String, ttlSeconds: Long, effectiveSinceMillis: Long): Boolean {
        // Monotonic: reject a non-newer since → idempotent under chain-rescan / relay-replay.
        if (effectiveSinceMillis <= prefs.getLong("$DISAPPEAR_SINCE_PREFIX$peerAddress", 0L)) return false
        prefs.edit()
            .putLong("$DISAPPEAR_TTL_PREFIX$peerAddress", ttlSeconds)
            .putLong("$DISAPPEAR_SINCE_PREFIX$peerAddress", effectiveSinceMillis)
            .apply()
        _disappearingTtls.update { it + (peerAddress to ZchatPreferences.DisappearingTtl(ttlSeconds, effectiveSinceMillis)) }
        return true
    }
    override fun getOrPutMessageExpiryAnchorMillis(messageId: String, nowMillis: Long): Long {
        val k = "$EXPIRY_ANCHOR_PREFIX$messageId"
        val v = prefs.getLong(k, 0L)
        return if (v > 0) v else nowMillis.also { prefs.edit().putLong(k, it).apply() }
    }
    override fun clearMessageExpiryAnchor(messageId: String) {
        prefs.edit().remove("$EXPIRY_ANCHOR_PREFIX$messageId").apply()
    }

    override fun getLastReadTimestamp(peerAddress: String): Long {
        return prefs.getLong(lastReadKey(peerAddress), 0L)
    }

    override fun setLastReadTimestamp(peerAddress: String, millis: Long) {
        // Monotonic: never move the marker backwards.
        if (millis <= getLastReadTimestamp(peerAddress)) return
        prefs.edit().putLong(lastReadKey(peerAddress), millis).apply()
        // Publish to the shared flow so other ChatViewModel instances (e.g. the chat-list screen) see
        // the advance immediately and clear the unread badge without waiting for a VM recreate. Use the
        // atomic update {} (compare-and-set) — the flow is a process-wide singleton, so a plain
        // value=value+(..) read-modify-write could lose a concurrent advance for a different peer.
        _readMarkers.update { current ->
            val existing = current[peerAddress] ?: 0L
            if (millis <= existing) current else current + (peerAddress to millis)
        }
    }

    override fun getAllLastReadTimestamps(): Map<String, Long> = _readMarkers.value

    override val chatListUiPrefs: SharedPreferences get() = prefs

    private fun loadAllLastReadTimestampsRaw(): Map<String, Long> {
        return prefs.all
            .filterKeys { it.startsWith(LAST_READ_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? Long)?.let { key.removePrefix(LAST_READ_PREFIX) to it }
            }
            .toMap()
    }

    // ==========================================
    // WORKER SYNC TIMESTAMP IMPLEMENTATION
    // ==========================================

    override fun getLastWorkerSyncTimestamp(): Long {
        return prefs.getLong(KEY_LAST_WORKER_SYNC_TIMESTAMP, 0L)
    }

    override fun setLastWorkerSyncTimestamp(millis: Long) {
        prefs.edit().putLong(KEY_LAST_WORKER_SYNC_TIMESTAMP, millis).apply()
    }

    // ==========================================
    // SEED BACKUP REMINDER IMPLEMENTATION
    // ==========================================

    override fun hasBackedUpSeed(): Boolean {
        return prefs.getBoolean(KEY_HAS_BACKED_UP_SEED, false)
    }

    override fun setHasBackedUpSeed(backed: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_BACKED_UP_SEED, backed).apply()
    }

    override fun getFirstOutgoingMessageTimestamp(): Long {
        return prefs.getLong(KEY_FIRST_OUTGOING_MSG_TS, 0L)
    }

    override fun setFirstOutgoingMessageTimestamp(millis: Long) {
        prefs.edit().putLong(KEY_FIRST_OUTGOING_MSG_TS, millis).apply()
    }

    override fun getNostrRotationIndex(): Int = prefs.getInt(KEY_NOSTR_ROTATION_INDEX, 0)
    override fun setNostrRotationIndex(index: Int) {
        prefs.edit().putInt(KEY_NOSTR_ROTATION_INDEX, index).apply()
    }

    override fun getLastRotationReminderAt(): Long = prefs.getLong(KEY_LAST_ROTATION_REMINDER_TS, 0L)
    override fun setLastRotationReminderAt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_ROTATION_REMINDER_TS, millis).apply()
    }

    override fun getLastBackupReminderTimestamp(): Long {
        return prefs.getLong(KEY_LAST_BACKUP_REMINDER_TS, 0L)
    }

    override fun setLastBackupReminderTimestamp(millis: Long) {
        prefs.edit().putLong(KEY_LAST_BACKUP_REMINDER_TS, millis).apply()
    }

    override fun getBackupReminderCount(): Int {
        return prefs.getInt(KEY_BACKUP_REMINDER_COUNT, 0)
    }

    override fun incrementBackupReminderCount() {
        val count = getBackupReminderCount()
        prefs.edit().putInt(KEY_BACKUP_REMINDER_COUNT, count + 1).apply()
    }

    init {
        // M1: the last init block, so every store above is ready. Runs before any relay message is dispatched (dispatch
        // reads this store).
        repairDoubleBoundNostrKeysAtStart()
    }

    /**
     * M1 — [repairDoubleBoundNostrKeysOnce] with the note in the user's language. A failure leaves the flag unset, so the
     * next start tries again.
     */
    private fun repairDoubleBoundNostrKeysAtStart() {
        runCatching {
            repairDoubleBoundNostrKeysOnce { appContext.getString(co.electriccoin.zcash.ui.R.string.chat_note_rows_moved) }
        }.onFailure { Log.w("ZCHAT_NOSTR", "relay key repair failed; it runs again at the next start", it) }
    }
}

/**
 * MED-B: bound a dedup set to [maxSize] by evicting the OLDEST entries first. [ids] is a [LinkedHashSet]
 * so its iterator yields insertion order — `iterator().next()` is always the oldest. Extracted as a pure,
 * unit-tested top-level function (no Context) so the ZBOOT-seen store's DoS bound is verifiable in the JVM
 * suite. This store is SEPARATE from the KEX-txid dedup FIFO (the point of MED-B): fast-churning ZBOOT
 * signatures evict only each other here, never a genuine KEX/KEXACK txid, so a later history re-scan can't
 * re-process a superseded old-key KEX and raise a false "peer key changed" banner.
 */
internal fun evictOldestToCapacity(ids: LinkedHashSet<String>, maxSize: Int) {
    while (ids.size > maxSize) {
        val oldest = ids.iterator().next()
        ids.remove(oldest)
    }
}
