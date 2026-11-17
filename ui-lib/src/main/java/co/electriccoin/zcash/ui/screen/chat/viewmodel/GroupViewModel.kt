package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.moneyRefusalMessage
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEntry
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEnvironment
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptGate
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptState
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptTag
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopySlots
import co.electriccoin.zcash.ui.nostr.RelayRepair
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AdminPolicy
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AdminCandidate
import co.electriccoin.zcash.ui.screen.chat.model.AdminKeyState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnouncePair
import co.electriccoin.zcash.ui.screen.chat.model.GroupIntroduceInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupUnread
import co.electriccoin.zcash.ui.screen.chat.model.IntroduceProgress
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.announceLedgerEntryFor
import co.electriccoin.zcash.ui.screen.chat.model.announcePairKey
import co.electriccoin.zcash.ui.screen.chat.model.memberAnnounceFits
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.CreateGroupState
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminNotice
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageCost
import co.electriccoin.zcash.ui.screen.chat.model.GroupKeyGrantPolicy
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.mayReArmHandshakeMarker
import co.electriccoin.zcash.ui.screen.chat.model.GroupResendOffer
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.PeerName
import co.electriccoin.zcash.ui.screen.chat.model.RebuiltGroupKey
import co.electriccoin.zcash.ui.screen.chat.model.groupMessageOnChainCost
import co.electriccoin.zcash.ui.screen.chat.model.paidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.KexAnswerPrice
import co.electriccoin.zcash.ui.screen.chat.usecase.isTransientWait
import co.electriccoin.zcash.ui.screen.chat.usecase.memoFailureProvesNothingSent
import co.electriccoin.zcash.ui.screen.chat.usecase.storedTxIdsOf
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * P0.1 — user-visible outcome of a group send / invite, replacing the old silent Log.w-only paths.
 * Emitted on [GroupViewModel.groupSendEvent] and rendered by the group screens as a banner.
 */
sealed interface GroupSendResult {
    /** Which group produced this event. The event flow is process-wide (companion), so screens MUST
     *  compare this against the group they're showing and ignore a mismatch — otherwise a pending
     *  event from group A can surface in / clear the composer of group B. */
    val groupId: String

    /**
     * No roster member is ACTIVE yet (invitees haven't accepted) — nothing was transmitted, nothing was
     * spent and the typed text is kept. R6-1: [notJoined] names the members still Invited (nickname, else
     * the short address) so the user is told WHO has not joined; it is empty when nobody else is in the
     * group at all.
     */
    data class NoActiveRecipients(
        override val groupId: String,
        val notJoined: List<String> = emptyList(),
        /**
         * Round 8 P5 (PHONE_TRIAGE F5a) — the ACTIVE members left out because they can't read new messages yet:
         * owed the current key with no secure session to send it under ([GroupViewModel.messageRecipients]).
         * Named so "nobody else is in this group" is never said while they are still in it.
         */
        val cannotRead: List<String> = emptyList(),
        /**
         * ZC-757 — the group was rebuilt from this phone's history: it lists who it invited but counts a member as one to
         * send to only once that member has written here since, so "has not joined" would be false. The banner says that.
         */
        val rebuilt: Boolean = false,
    ) : GroupSendResult

    /**
     * R6-3 (round 5 report §5 item 4) — the text does not fit in one group memo (a group message is one
     * 512-byte memo per member, never chunked). Refused before the cost prompt: nothing was quoted, sent or
     * spent, no sequence number was taken and the typed text is kept. [overBy] is how many bytes too long
     * it is — cutting that many characters of any kind is always enough.
     */
    data class MessageTooLong(
        override val groupId: String,
        val overBy: Int,
    ) : GroupSendResult

    /**
     * Round 8 P6 (PHONE_TRIAGE F5b) — this phone is no longer in the group: the admin removed us ([removed]), or we
     * left it. Nothing was quoted, sent or spent, and the typed text is kept.
     */
    data class NotInGroup(
        override val groupId: String,
        val removed: Boolean,
    ) : GroupSendResult

    /**
     * ZC-757 (G1, G6) — a group this phone rebuilt from its history sends nothing under an old key: before its admin made a
     * new key on this phone ([noReader] false) no message, invitation or key grant goes out; after a new key that no
     * member was sent yet ([noReader] true) no message goes out (an invitation may). Nothing was quoted, sent or spent,
     * and the typed text is kept.
     */
    data class NeedsNewKey(
        override val groupId: String,
        val noReader: Boolean,
    ) : GroupSendResult

    /** Delivered to [sent] of [total] ACTIVE members; the rest failed after retries. */
    data class PartialDelivery(override val groupId: String, val sent: Int, val total: Int) : GroupSendResult

    /**
     * Every recipient failed after retries — nothing was transmitted; the draft is kept.
     *
     * Round 8 D8 (REVIEW_7C F3): [refusal] is the sentence of a refusal no balance can fix ([groupSendRefusal]: sealed
     * Orchard, Keystone, Ironwood still confirming, a public crossing), shown instead of "check your balance".
     */
    data class AllFailed(
        override val groupId: String,
        val refusal: String? = null,
    ) : GroupSendResult

    /**
     * R5-1 — the user was shown what this message costs on-chain and declined. Nothing was sent, nothing
     * was spent, no sequence number was burned and the typed text is still in the composer. Emitted so the
     * composer, which armed itself at the send tap, stops waiting for an outcome and resumes saving the
     * draft instead of holding the text hostage.
     */
    data class CostDeclined(override val groupId: String) : GroupSendResult

    /**
     * GROUP_INVITEs to [addresses] couldn't be sent — repairable via Resend in group settings. Round 8 D8 (F3): with a
     * [refusal] ([groupSendRefusal]) the banner names it instead of "check your balance".
     */
    data class InviteFailed(
        override val groupId: String,
        val addresses: List<String>,
        val refusal: String? = null,
    ) : GroupSendResult

    /**
     * D3 soft-warn — a kick/rotation committed locally, but [pendingMembers] remaining member(s)
     * haven't received the new group key (no KEX session / send failed / offline). Deliberately NOT
     * a hard block: an offline remaining member must not wedge a legitimate removal. Until repaired
     * (retryPendingGroupKeys push / GROUP_KEY_REQUEST pull), those members keep sending old-epoch
     * ciphertext a kicked member can still read — surfaced so the admin knows the group isn't clean.
     */
    data class RemovalIncomplete(
        override val groupId: String,
        val pendingMembers: Int,
        /**
         * C1 (B1 review item 2) — member(s) whose new key memo MAY have been sent (stored by the wallet, then
         * rejected). Not re-sent (that could pay twice), and not confirmed either, so the admin is told.
         */
        val unconfirmedMembers: Int = 0,
        /**
         * Round 8 D8 (F3) — the refusal ([groupSendRefusal]) that stopped the new key memos. The banner names it and
         * does not say "retrying automatically": the automatic retry meets the same refusal until it is lifted.
         */
        val refusal: String? = null,
        /**
         * Round 8 P5 (PHONE_TRIAGE F5a) — key holders the new key could not be sent to at all: there is no secure
         * session (KEX) with them. Counted apart from [pendingMembers] (send failures, which the automatic retry
         * can repair): nothing retries them until a key exchange with them completes, so the banner says so
         * instead of "retrying automatically".
         */
        val noSessionMembers: Int = 0,
    ) : GroupSendResult

    /**
     * B1 (GPT-6 Q3) — a [kind] may have been sent to [members] member(s): the submit started and its failure did
     * not prove that nothing was stored, so the SDK may still broadcast it. It is NOT offered as a paid Resend or
     * kept as a draft to send again, so a transaction that lands late is never paid a second time.
     *
     * C4 (B1 review item 3): [members] counts MEMBERS (one memo each), [kind] says whether a message or an invite
     * is meant, and [notSent] counts the members of the same send to whom provably nothing was sent (a failed
     * invite keeps its Resend), so a mixed outcome is not hidden behind the "may have been sent" line.
     */
    data class MaybeSent(
        override val groupId: String,
        val kind: MaybeSentKind,
        val members: Int,
        val notSent: Int = 0,
        /**
         * Round 8 MA3 — for [MaybeSentKind.INTRODUCE]: the member announces sent (paid for, or, MA-V3, free over the
         * relays when the recipient became reachable during the run).
         */
        val sent: Int = 0,
    ) : GroupSendResult

    /**
     * ZC-420-G — the message went out, and for the members named in [names] (as the group screens name them) only a
     * public relay took their copy: ZChat's relay and nos.lol, the relays every ZChat version reads, did not, so a member
     * on ZChat 2.12.4 may not see it. The copy is remembered and delivered again over the relays, free, each time the
     * group is opened. The message counts as sent (nothing is kept to send again and nothing is paid). [atOpen]: raised by
     * a group open for copies that are still not confirmed, not by a send, so the composer never takes it as the outcome
     * of a send it waits for.
     */
    data class RelayUnconfirmed(
        override val groupId: String,
        val names: List<String>,
        val atOpen: Boolean = false,
    ) : GroupSendResult

    /**
     * C4 — what a [MaybeSent] refers to. A rotation's key memo is reported by [RemovalIncomplete] instead. Round 8 MA3:
     * [INTRODUCE] is the outcome of an "Introduce them" run, counted in announces (pairs): sent, may have been sent
     * (never paid again), not sent (nothing spent for them).
     */
    enum class MaybeSentKind { MESSAGE, INVITE, INTRODUCE }
}

/**
 * Round 8 P5 (PHONE_TRIAGE F5a) — asked before a removal ([removing]) or a key rotation of [groupId] when some key
 * holders, named in [names] the way the group screens name them, can't be sent the new key: there is no secure
 * session (KEX) with them. Nothing is quoted, sent or changed until the admin answers. Cancel leaves the epoch, the
 * roster and the removal floor as they were.
 */
data class RekeyUnkeyableConfirmation(
    val groupId: String,
    val names: List<String>,
    val removing: Boolean,
)

/**
 * Round 8 D8 (REVIEW_7C F3) — the sentence for a group memo refused before anything was stored for a reason no
 * balance can fix, or null. The first such refusal in [error]'s cause chain decides:
 *
 * - sealed Orchard: ChatSendCopy.ORCHARD_POOL_SEALED, or, while Ironwood is still confirming,
 *   OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE (not IRONWOOD_FIRST_FUNDS_CONFIRMING: the pending Ironwood may
 *   be our own change, Round 8 D7 sweep #10);
 * - the ZC-102 Keystone refusal: ChatSendCopy.GROUP_KEYSTONE_REFUSED;
 * - a public or Sapling crossing, or a proposal ZChat could not read: that refusal's own text (moneyRefusalMessage).
 *
 * Each of these is thrown by the send chokepoint before its submit, so nothing was sent or paid.
 */
internal fun groupSendRefusal(error: Throwable): String? {
    val own =
        generateSequence(error) { it.cause }
            .take(MAX_REFUSAL_CAUSE_DEPTH)
            .firstNotNullOfOrNull { cause ->
                when {
                    cause is co.electriccoin.zcash.ui.common.model.OrchardSealedException ->
                        if (cause.ironwoodPending > Zatoshi(0)) {
                            co.electriccoin.zcash.ui.common.model.OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE
                        } else {
                            co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.ORCHARD_POOL_SEALED
                        }
                    cause is UnsupportedOperationException &&
                        cause.message == CreateChunkedMessageProposalUseCase.KEYSTONE_AUTO_SEND_REFUSED_MESSAGE ->
                        co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.GROUP_KEYSTONE_REFUSED
                    else -> null
                }
            }
    return own ?: moneyRefusalMessage(error)
}

/** How far down a cause chain [groupSendRefusal] looks; matches the error mapper. */
private const val MAX_REFUSAL_CAUSE_DEPTH = 20

/**
 * ViewModel for Group Chat functionality.
 * Manages group creation, messaging, and member management.
 */
class GroupViewModel(
    private val transactionRepository: TransactionRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val accountDataSource: AccountDataSource,
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
    private val zchatPreferences: ZchatPreferences,
    private val synchronizerProvider: SynchronizerProvider,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val contactBook: ContactBook,
    // D1-recovery Stage 3a: seed access for seed-derived E2E key generation (the group creator's
    // per-groupId identity key). Koin viewModelOf auto-wires the registered single.
    private val persistableWalletProvider: co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider,
    // F8-G (ZC-112): the send-attempt ledger of the group memos this ViewModel pays for on-chain (invites, key
    // messages, member announces, message copies) and the record of a key rotation in progress. Koin hands it in
    // (viewModelOf); null (a unit test that installs none) records nothing and behaves as before F8-G.
    private val sendAttempts: SendAttemptEnvironment? = null,
) : ViewModel() {

    // Round 8 D8 (REVIEW_7C F3): per group, the first refusal no balance can fix that stopped a rotation's key memos
    // (written by rotateAndNotify, read and removed by the kick / rotate that ran it, for its RemovalIncomplete banner).
    private val rotationRefusals = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Round 8 P5 (PHONE_TRIAGE F5a): per group, the key holders the last rotation could not send the new key to because
    // there is no secure session with them (one per member). Written by rotateAndNotify, read and removed by the kick /
    // rotate that ran it, which reports them apart from send failures.
    private val rotationNoSession = java.util.concurrent.ConcurrentHashMap<String, NoSessionRows>()

    // GR-OPEN fixup (G-GR-1): per group, the members (canonical addresses) whose last sealed key got no relay ack. Written
    // by every sealed key send (an ack removes the member); read by the cost line ([SealedKeyCount.LAST_RESULT]).
    private val sealedKeyUnacked = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Round 8 P5 — [rows]: every recipient row rotateAndNotify skipped for having no session; [members]: of those, one
     * address per member none of whose rows was sent the key (what the banner counts).
     */
    private class NoSessionRows(
        val rows: List<String>,
        val members: List<String>,
    )

    // Same wired gen path as ChatViewModel: seed-derived, provenance-marked, never-overwrite
    // (the mutex + prefs backing are process-wide, so both ViewModels share one allocation line).
    private val seedKeyProvider =
        co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider.create(
            zchatPreferences, persistableWalletProvider
        )

    companion object {
        private const val TAG = "GroupViewModel"

        /** GR-OPEN G1 — the stored mark of a held group message ("held": "removal"), as ChatViewModel writes it. */
        private const val HELD_REMOVAL = "removal"
        // Default amount per group message output (1000 zatoshi = 0.00001 ZEC)
        const val DEFAULT_MESSAGE_AMOUNT = 1000L
        // #199 invite retry through single-note serialization. Bounded so a genuinely-out-of-funds
        // wallet doesn't loop forever; the wait per attempt is one block (mirrors ChatViewModel's queue).
        private const val MAX_INVITE_RETRIES = 4

        /** T1b-1: how long a group send waits for the selected account before treating it as not Keystone. */
        private const val KEYSTONE_CHECK_TIMEOUT_MS = 3_000L

        private const val INVITE_BLOCK_WAIT_TIMEOUT_MS = 300_000L

        /**
         * ZC-420-G — how many relay copies one group open may try to deliver again (each one a NostrChatBridge.repairRelayCopy
         * call); a copy that is forgotten, or kept because it cannot go now, uses none.
         */
        private const val MAX_RELAY_COPY_REPAIRS_PER_OPEN = 16

        // Bound the backward epoch search when a held key's epoch number disagrees with the
        // message's claimed epoch (key-rotation / lagging-peer drift). Mirrors ChatViewModel.
        private const val MAX_GROUP_EPOCH_LOOKBACK = 64

        // P0.1 — backing flow for [groupSendEvent]. Process-wide (companion) ON PURPOSE: each nav
        // destination gets its OWN GroupViewModel instance (viewModelOf), so an InviteFailed emitted
        // by the create-group screen's instance must survive into the detail screen the user lands
        // on. Same cross-instance pattern as the #226 shared read-marker flow.
        private val _groupSendEvent = MutableStateFlow<GroupSendResult?>(null)

        // D3 — per-group retryPendingGroupKeys in-flight guard (kick flow and loadGroupDetail both schedule
        // a pass; one at a time). PROCESS-WIDE since A2: each nav destination has its own GroupViewModel,
        // and a per-instance guard let the group screen and the settings screen each pay the same owed key.
        private val keyRetriesInFlight: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()

        /** Round 9 R9-5 — how long the open group screen waits after a store write before it reloads (writes come in bursts). */
        private const val GROUP_STORE_RELOAD_DEBOUNCE_MS = 250L

        // D5 / KI-22 — the change lock of each group, ONE per group for the whole process (see [withGroupChange]). It was
        // a field of each instance, and each nav destination has its own GroupViewModel, so a kick in group settings and a
        // rotation on the group screen did not exclude each other: both read epoch N and minted two different N+1 keys
        // (receivers keep the first and reject the second as a replay: a permanent fork).
        private val rotationLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        // ZC-493 — per group, the identities (sender, seq, epoch) of our own messages whose chain copies the last
        // history scan found ALL expired and unmined: at least one own transaction listed as SendTransaction.Failed
        // with no mined height, none mined or still pending. [loadGroup] draws such a row as failed, with its text.
        // Identities only (no text, no transaction id), in memory only, replaced by every scan of the group; nothing
        // is stored, hidden or deleted for it, and nothing is paid or retried on it. PROCESS-WIDE like the fields
        // above: each nav destination has its own GroupViewModel, so a per-instance set would lose the mark each time
        // the screen is reopened.
        private val undeliveredOwnMessages =
            java.util.concurrent.ConcurrentHashMap<String, Set<Triple<String, Long, Int>>>()

        /**
         * F8-G (G4-5) — the ledger keys whose Resend (an invite, a key, a message copy) is running, for the whole
         * process: each nav destination has its own GroupViewModel, so two screens that draw the same badge share
         * this one set. A key is added at the confirmed tap and removed in a `finally`, whatever the Resend did. A
         * second confirm of the same key while it runs is dropped; the badge of a running Resend is not drawn; the
         * history pass leaves such an entry alone.
         */
        internal val groupResendsInFlight: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
    }

    // Current user address
    private val _currentUserAddress = MutableStateFlow<String?>(null)

    // ZC-1045, ZC-1046: the groups opened on this screen before its own address was known (group to afterSend).
    private val openPassesAwaitingAddress = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val currentUserAddress: StateFlow<String?> = _currentUserAddress.asStateFlow()

    // Group list state
    private val _groupConversations = MutableStateFlow<List<GroupConversation>>(emptyList())
    val groupConversations: StateFlow<List<GroupConversation>> = _groupConversations.asStateFlow()

    // Create group state
    private val _createGroupState = MutableStateFlow(CreateGroupState())
    val createGroupState: StateFlow<CreateGroupState> = _createGroupState.asStateFlow()

    // Group detail state (for viewing a specific group)
    private val _groupDetailState = MutableStateFlow<GroupDetailState>(GroupDetailState.Loading)

    /** Round 8 MA2 — the groups whose one-time roster notice this screen is showing (see rosterNoticeShown). */
    private val rosterNoticesShowing: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()
    val groupDetailState: StateFlow<GroupDetailState> = _groupDetailState.asStateFlow()

    // Send message state
    private val _isSendingMessage = MutableStateFlow(false)
    val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()

    // ZEC price
    private val _zecPriceUsd = MutableStateFlow<Double?>(null)
    val zecPriceUsd: StateFlow<Double?> = _zecPriceUsd.asStateFlow()

    // Group settings state
    private val _groupSettingsState = MutableStateFlow<GroupSettingsState>(GroupSettingsState.Loading)
    val groupSettingsState: StateFlow<GroupSettingsState> = _groupSettingsState.asStateFlow()

    // Candidate contacts the admin can add to an EXISTING group (Add-member picker). Populated by
    // loadAddMemberCandidates(groupId): saved contacts + KEX'd peers, minus those already in the group.
    private val _addMemberCandidates = MutableStateFlow<List<Contact>>(emptyList())
    val addMemberCandidates: StateFlow<List<Contact>> = _addMemberCandidates.asStateFlow()

    // ZC-733-B: whether the add-member picker's saved names could be read at its last load.
    private val _contactNamesUnreadable = MutableStateFlow(false)
    val contactNamesUnreadable: StateFlow<Boolean> = _contactNamesUnreadable.asStateFlow()

    // Pending group messages (not yet on chain)
    private val pendingGroupMessages = MutableStateFlow<Map<String, List<GroupMessage>>>(emptyMap())

    // SF-3 — per-(groupId,memberAddress) resend-in-flight guard. A double-tap on Resend would
    // otherwise fire two on-chain invite txs. Accessed only from the main-dispatched viewModelScope,
    // but kept synchronized for safety.
    private val resendInvitesInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // R2-2 (b) — a group control that pays on-chain asks first: the quote on screen, and the tap it runs.
    private val _paidActionConfirmation = MutableStateFlow<PaidActionConfirmation?>(null)
    val paidActionConfirmation: StateFlow<PaidActionConfirmation?> = _paidActionConfirmation.asStateFlow()

    @Volatile private var pendingPaidAction: (() -> Unit)? = null

    // R5-1 — what to run when the user declines the quote above. A group MESSAGE needs it: the composer
    // armed itself at the send tap and would otherwise wait for an outcome that never comes, leaving the
    // typed text frozen (never saved as a draft) with no explanation on screen.
    @Volatile private var pendingPaidActionDeclined: (() -> Unit)? = null

    // Round 8 P5 (PHONE_TRIAGE F5a) — a removal or rotation that would strand key holders with no secure session asks
    // first, before its cost is quoted: who they are, and the tap it runs once the admin says "Remove anyway".
    private val _rekeyUnkeyableConfirmation = MutableStateFlow<RekeyUnkeyableConfirmation?>(null)
    val rekeyUnkeyableConfirmation: StateFlow<RekeyUnkeyableConfirmation?> = _rekeyUnkeyableConfirmation.asStateFlow()

    /** The removal or rotation waiting on [rekeyUnkeyableConfirmation], and the members (canonical) it named. */
    private class PendingRekey(
        val groupId: String,
        val kickedAddress: String?,
        val named: Set<String>,
        val proceed: () -> Unit,
    )

    @Volatile private var pendingRekey: PendingRekey? = null

    /**
     * R2-2 (b) — [pay] sends a memo to each of [quoted]'s recipients. When the quote counts some of them as
     * on-chain messages, show that count and the least total and keep [pay] until [confirmPaidAction]; when
     * it counts none, run it at once. [onDeclined] runs instead if the user dismisses the quote.
     *
     * B1 (GPT-6 Q3b): the count shown and the decision to skip the prompt come from the SAME frozen quote the
     * payment is later bound to. Nothing checks the free relay route a second time here: a member quoted as
     * on-chain who looked free on a second check used to skip the prompt and still be paid on-chain.
     */
    private fun askBeforePaying(
        action: PaidAction,
        quoted: QuotedFanOut,
        onDeclined: () -> Unit = {},
        // Round 8 MA3: on-chain member announces the same tap pays for, counted as pairs ([QuotedPairs]).
        extraOnChain: Int = 0,
        pay: () -> Unit,
    ) {
        val onChain = quoted.paidRecipients.size + extraOnChain
        if (onChain == 0) {
            pay()
            return
        }
        pendingPaidAction = pay
        pendingPaidActionDeclined = onDeclined
        _paidActionConfirmation.value = paidActionConfirmation(action, onChain)
    }

    /** R2-2 (b) — the user accepted the quoted cost: run the waiting group control once. */
    fun confirmPaidAction() {
        val pay = pendingPaidAction ?: return
        pendingPaidAction = null
        pendingPaidActionDeclined = null
        _paidActionConfirmation.value = null
        pay()
    }

    /** R2-2 (b) — the user declined: nothing is sent and nothing changes. */
    fun dismissPaidAction() {
        val declined = pendingPaidActionDeclined
        pendingPaidAction = null
        pendingPaidActionDeclined = null
        _paidActionConfirmation.value = null
        // R5-1: after the state is cleared, so a screen that reacts to the event sees no stale quote.
        declined?.invoke()
    }

    /** R2-2 (b) — a memo to [address] would go free over Nostr right now (sendGroupMemoWithRetry's first try). */
    private fun reachableFree(address: String): Boolean {
        if (findPeerNostrPubkeyAnyRep(address) == null) return false
        return co.electriccoin.zcash.ui.nostr.NostrChatBridge
            .isOutboundReady()
    }

    /**
     * R4-2 — one group control's fan-out exactly as it was quoted: [recipients] are the members the prompt
     * covered, of which [paidRecipients] are the ones it counted as on-chain messages. Every other
     * recipient was quoted as free, so it must never be paid for under this quote.
     */
    private data class QuotedFanOut(
        val recipients: List<String>,
        val paidRecipients: Set<String>,
    ) {
        /** True only for a member the prompt counted as an on-chain message. */
        fun mayPayFor(address: String): Boolean = address in paidRecipients
    }

    /**
     * Round 8 MA3 (MEMBER_ANNOUNCE_DESIGN.md v2 §6.3, review F7) — member announces exactly as they were quoted: one
     * memo per (recipient, subject) pair. [paidPairs] ([announcePairKey]) are the pairs whose recipient is not reachable
     * free; only those may ever be paid for under this quote, and never with a `{ true }` default.
     */
    private data class QuotedPairs(
        val pairs: List<AnnouncePair>,
        val paidPairs: Set<String>,
    ) {
        fun mayPayFor(pair: AnnouncePair): Boolean = announcePairKey(pair) in paidPairs
    }

    private fun quotePairs(pairs: List<AnnouncePair>): QuotedPairs =
        QuotedPairs(pairs, pairs.filterNot { reachableFree(it.recipient) }.map { announcePairKey(it) }.toSet())

    /**
     * R4-2 — quote [recipients]: who is on it, and who on it is not reachable free right now. ZC-972: a member in
     * [relaysMissed] (its relay copy of this very message just got no ack) is quoted as on-chain whatever the forecast.
     */
    private fun quoteFanOut(
        recipients: Collection<String>,
        relaysMissed: Set<String> = emptySet(),
    ): QuotedFanOut {
        val distinct = recipients.distinct()
        return QuotedFanOut(
            recipients = distinct,
            paidRecipients = distinct.filter { it in relaysMissed || !reachableFree(it) }.toSet(),
        )
    }

    /**
     * R4-2 (round 3 report §4 item 3) — what GPT-6 F2 did for createGroup, for every other group control:
     * quote [plan]'s fan-out, and on Confirm run [pay] only while it is still the plan that was quoted.
     * When a recipient was added or dropped, or one quoted as free now needs an on-chain message, nothing
     * is sent and the changed plan is quoted again. [pay] is handed the frozen quote, and pays on-chain
     * only for the members [QuotedFanOut.paidRecipients] holds — a member quoted as free whose free
     * delivery fails is recorded as a failed delivery, never paid for behind the user's back.
     */
    private fun askForQuotedPlan(
        action: PaidAction,
        plan: () -> Collection<String>,
        onDeclined: () -> Unit = {},
        // ZC-972: members quoted as on-chain whatever the forecast (their relay copy of this message got no ack).
        relaysMissed: Set<String> = emptySet(),
        pay: (QuotedFanOut) -> Unit,
    ) {
        val quoted = quoteFanOut(plan(), relaysMissed)
        askBeforePaying(action, quoted, onDeclined) {
            val now = quoteFanOut(plan(), relaysMissed)
            if (now.recipients != quoted.recipients || !quoted.paidRecipients.containsAll(now.paidRecipients)) {
                Log.w(TAG, "$action: the plan changed after its cost was quoted — asking again, nothing sent")
                askForQuotedPlan(action, plan, onDeclined, relaysMissed, pay)
            } else {
                pay(quoted)
            }
        }
    }

    /**
     * R5-1 (round 4 report §5B) — the cost surface a group MESSAGE never had. A message fans one on-chain
     * memo out to every member with no free relay route, so it is quoted from the send path's OWN recipient
     * list ([messageRecipients]) and the send's own sizer (a group memo is a rawMemo: exactly one memo
     * output per recipient, whatever the text).
     *
     * The consent is ONE PER GROUP and remembered, not one per message — a chat would be unusable if every
     * line raised a dialog; an unchanged paid set asks once per group. It is stored per member address, not as
     * a count, so a different set of the same size still asks.
     *
     * ZC-130 — a member's consent lasts only while the member stays in the paid set without leaving the group.
     * A message that pays without asking first cuts the stored consent to the members it pays for (a member now
     * reachable free, or no longer a recipient, drops out), and asks instead when that cut does not reach the
     * disk; a Pay stores exactly the members its question covered. The store drops a member whose roster row
     * stops being ACTIVE ([ZchatPreferences.saveGroupMembers]), also when no message is sent in between. So it
     * asks again whenever the paid set holds a member not covered: one who joined, one back in the paid set after
     * leaving the group, or one who was reachable free and now needs an on-chain memo again.
     */
    private fun askForQuotedGroupMessage(
        groupId: String,
        onDeclined: () -> Unit,
        // ZC-972: the members whose relay copy of this message just got no ack, quoted as on-chain.
        relaysMissed: Set<String> = emptySet(),
        pay: (QuotedFanOut) -> Unit,
    ) {
        val quoted = quoteFanOut(messageRecipients(groupId), relaysMissed)
        val consented = zchatPreferences.getGroupMessageSpendConsent(groupId)
        if (consented.containsAll(quoted.paidRecipients)) {
            // Nothing is paid for, or every member that would be paid for is already covered. The consent keeps
            // only those members, and the cut must reach the disk before anything is paid on it.
            val kept = consented.intersect(quoted.paidRecipients)
            if (kept == consented || zchatPreferences.setGroupMessageSpendConsent(groupId, kept)) {
                pay(quoted)
                return
            }
            Log.w(TAG, "Group $groupId: the message-spend consent could not be cut on disk — asking instead of paying")
        }
        askForQuotedPlan(PaidAction.GROUP_MESSAGE, { messageRecipients(groupId) }, onDeclined, relaysMissed) { fresh ->
            // Remember BEFORE the send: a kill mid-fan-out must not make the next message ask again for a
            // set the user has already agreed to (and already paid part of). Exactly the agreed set (ZC-130).
            if (!zchatPreferences.setGroupMessageSpendConsent(groupId, fresh.paidRecipients)) {
                Log.w(TAG, "Group $groupId: the message-spend consent did not reach the disk — the next message asks again")
            }
            pay(fresh)
        }
    }

    /**
     * R5-1 — what one message to [groupId] costs on-chain right now, for the composer's cost line. Built
     * from the same recipient list and the same per-memo sizer as [sendGroupMessage], so the line can never
     * show a number the send disagrees with.
     */
    fun groupMessageCost(groupId: String): GroupMessageCost {
        val quoted = quoteFanOut(messageRecipients(groupId))
        return GroupMessageCost(
            recipients = quoted.recipients.size,
            onChainMessages = quoted.paidRecipients.size,
            totalCost = groupMessageOnChainCost(quoted.paidRecipients.size),
            // Round 8 P5: the members left out because they can't read new messages yet, said on the cost line.
            cannotRead = cannotReadMembers(groupId, SealedKeyCount.LAST_RESULT).size,
            // Round 8 P7 (PHONE_TRIAGE F4): only the admin's roster is the whole group; a member's lists the members
            // it has heard from, and the message goes to those only. Round 8 MA2: once the creator has announced the
            // roster, a member's phone knows the whole group too.
            knownOnly = !isGroupAdmin(groupId) && !announceState(groupId).rosterAnnounced,
        )
    }

    /** Round 8 MA2 — record that this phone left [groupId] now (its announce state's inactiveSince), under the group lock. */
    private fun recordInactiveSince(groupId: String) {
        synchronized(ZchatPreferences.groupLock(groupId)) {
            val state = announceState(groupId)
            runCatching { zchatPreferences.saveGroupAnnounceState(groupId, state.copy(inactiveSince = System.currentTimeMillis())) }
        }
    }

    /** Round 8 MA2 — the announce state of [groupId]; the empty state when none is stored or the store cannot read it. */
    private fun announceState(groupId: String): AnnounceState =
        runCatching { zchatPreferences.getGroupAnnounceState(groupId) as Any? }.getOrNull() as? AnnounceState ?: AnnounceState()

    /**
     * Round 8 MA2 (design §5.2, review M1 / F6) — on a member's phone whose roster the creator announced, the canonical
     * addresses a message may go to: the members announced in (`A`) and the creator. Null on the creator's phone and
     * in a group with no announced roster (every ACTIVE row counts, as before).
     */
    private fun announcedRecipients(groupId: String): Set<String>? {
        // The creator's phone is never limited (its roster is the whole group); a store that cannot answer counts as not.
        if (runCatching { isGroupAdmin(groupId) }.getOrDefault(false)) return null
        val state = announceState(groupId)
        if (!state.rosterAnnounced) return null
        val creator =
            zchatPreferences
                .getGroupInfo(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                ?.creatorAddress
                ?.let { zchatPreferences.resolvePeerAddress(it) }
        return state.members.filterValues { it.op == AnnounceOp.A }.keys + listOfNotNull(creator)
    }

    /**
     * R6-3 — how many bytes [message] is over one GROUP_MSG memo of [groupId] (0 when it fits), measured on
     * the wire layout the fan-out builds, for sequence number [seq] and key epoch [epoch].
     */
    private fun groupMessageOverBy(
        groupId: String,
        message: String,
        senderAddress: String,
        seq: Long,
        epoch: Int = zchatPreferences.getGroupKeyEpoch(groupId),
    ): Int = ZMSGGroupProtocol.groupMsgOverBy(groupId, seq, epoch, senderAddress, message)

    /** R2-2 (b) — the members a kick / rotation sends a signed control to (rotateAndNotify's recipients with a session). */
    private fun rotationRecipients(
        groupId: String,
        kickedAddress: String?,
    ): List<String> =
        rotationKeyHolders(groupId, kickedAddress)
            .map { it.address }
            .filter { hasGroupSession(it) }

    /**
     * Round 8 P6 (PHONE_TRIAGE F5b) — who a kick of [kickedAddress] sends its removal notice to: one row of the removed
     * member that holds the current key and has a secure session with us, or nobody. Quoted with the kick.
     */
    private fun removalNoticeRecipients(
        groupId: String,
        kickedAddress: String,
    ): List<String> {
        val members =
            zchatPreferences.getGroupMembers(groupId)?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                ?: return emptyList()
        return listOfNotNull(removalNoticeRow(members, kickedAddress))
    }

    /**
     * Round 8 P6 — in [members], the row of [kickedAddress] (matched canonically, #205/#214) a removal notice goes to:
     * the first that holds the current key ([ZMSGGroupProtocol.holdsGroupKey]) and has a secure session with us
     * ([hasGroupSession]). null when there is none: the notice is signed with our per-peer key, which only a session has.
     */
    private fun removalNoticeRow(
        members: List<GroupMember>,
        kickedAddress: String,
    ): String? {
        val kickedCanonical = zchatPreferences.resolvePeerAddress(kickedAddress)
        return members
            .filter {
                ZMSGGroupProtocol.holdsGroupKey(it) &&
                    !zchatPreferences.isSelfAddress(it.address) &&
                    zchatPreferences.resolvePeerAddress(it.address) == kickedCanonical
            }.map { it.address }
            .firstOrNull { hasGroupSession(it) }
    }

    /**
     * Round 8 P6 (PHONE_TRIAGE F5b) — tell the removed member: a GROUP_KICK naming [kickedAddress] with NO key, signed
     * like every kick ([ZMSGGroupProtocol.groupKickSignedData] with a null key) with our per-peer key for
     * [noticeAddress]. Free over the relays, or on-chain only when the kick's quote counted it ([mayPayOnChain]). Sent
     * after the removal is committed, so a member is never told about a removal that did not happen. The new key never
     * goes to them. A notice that could not be sent is logged; the removal stands.
     */
    private suspend fun sendRemovalNotice(
        groupId: String,
        kickedAddress: String,
        adminAddress: String,
        newEpoch: Int,
        noticeAddress: String,
        mayPayOnChain: (String) -> Boolean,
    ) {
        val ourPriv = zchatPreferences.getE2EPrivateKey(noticeAddress) ?: return
        val signedData = ZMSGGroupProtocol.groupKickSignedData(groupId, kickedAddress, adminAddress, newEpoch, null)
        val memo =
            ZMSGGroupProtocol.createGroupKickMessage(
                groupId,
                kickedAddress,
                adminAddress,
                newEpoch,
                null,
                E2EEncryption.sign(ourPriv, signedData),
            )
        val outcome =
            try {
                sendGroupMemoWithRetry(noticeAddress, memo, adminAddress) { mayPayOnChain(noticeAddress) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Removal notice to ${noticeAddress.redactAddress()} failed", e)
                GroupMemoOutcome.NOTHING_SENT
            }
        if (outcome == GroupMemoOutcome.NOTHING_SENT) {
            Log.w(TAG, "Removal notice to ${noticeAddress.redactAddress()} not sent — they are not told they were removed")
        }
    }

    /**
     * GR-OPEN G2 (SPEC §2.2) — send [address] the new key of [groupId] ([keyBase64] at [epoch]) as a sealed control: a
     * GROUP_KICK naming [kickedAddress] (a removal) or a GROUP_KEY with [reason] (a rotation, or the owed-key re-send),
     * with "sig": "" and the key in plaintext as `gk`. Only through [sendGroupMemoWithRetry] with `mayPayOnChain = { false
     * }` (review D): the Keystone refusal applies, the relay attempt is the only one, and a failed relay publish builds and
     * pays nothing. Never to a relay key of the removed member or of a LEFT row ([removedRelayKeys]).
     * @return true when a relay acked it.
     */
    @Suppress("LongParameterList")
    private suspend fun sendSealedKey(
        groupId: String,
        address: String,
        adminAddress: String,
        kickedAddress: String?,
        epoch: Int,
        keyBase64: String,
        reason: String = "rotation",
        // ZC-055 (phone S-C C2): read after the debug hold and before the relay copy, as for the signed key message.
        stillWanted: () -> Boolean = { true },
        // ZC-420-G P-G3 (phone): this member's key copy among the relay-copy marks, as for the signed key message.
        relaySlot: String? = null,
        relayExtra: String = "",
    ): Boolean {
        if (!sealedKeyRouteOpen(groupId, address, kickedAddress)) {
            Log.w(TAG, "Sealed key to ${address.redactAddress()} not sent: shares a removed member's relay key")
            return false
        }
        val memo =
            if (kickedAddress != null) {
                ZMSGGroupProtocol.createGroupKickMessage(groupId, kickedAddress, adminAddress, epoch, null, "", keyBase64)
            } else {
                ZMSGGroupProtocol.createGroupKeyMessage(groupId, adminAddress, epoch, "", "", reason, keyBase64)
            }
        val outcome =
            try {
                sendGroupMemoWithRetry(
                    address,
                    memo,
                    adminAddress,
                    onRefused = { rotationRefusals.putIfAbsent(groupId, it) },
                    relaySlot = relaySlot,
                    relayExtra = relayExtra,
                    stillWanted = stillWanted,
                    mayPayOnChain = { false },
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Sealed key to ${address.redactAddress()} failed", e)
                GroupMemoOutcome.NOTHING_SENT
            }
        if (outcome != GroupMemoOutcome.SENT) {
            Log.w(TAG, "Sealed key to ${address.redactAddress()} not delivered over the relays — still owed")
        }
        return outcome == GroupMemoOutcome.SENT
    }

    /**
     * GR-OPEN G2 (finding 4a) — the row of [kickedAddress] (matched canonically) a sealed removal notice goes to when no
     * row has a session: the first that holds the current key and can take a sealed control ([sealedRouteEligible]).
     */
    private fun sealedRemovalNoticeRow(
        members: List<GroupMember>,
        kickedAddress: String,
    ): String? {
        val kickedCanonical = zchatPreferences.resolvePeerAddress(kickedAddress)
        return members
            .filter {
                ZMSGGroupProtocol.holdsGroupKey(it) &&
                    !zchatPreferences.isSelfAddress(it.address) &&
                    zchatPreferences.resolvePeerAddress(it.address) == kickedCanonical
            }.map { it.address }
            .firstOrNull { sealedRouteEligible(it) }
    }

    /**
     * GR-OPEN G2 (finding 4a) — tell the removed member with a sealed GROUP_KICK naming [kickedAddress]: no key, no
     * `gk`, "sig": "", over the relays only ([sendGroupMemoWithRetry] with `mayPayOnChain = { false }`). Sent after the
     * removal is committed; a notice that could not go is logged and the removal stands. ZC-420-G R-1: a notice only a
     * public relay took is marked ([RelayCopySlots.groupRemoval]) and sent again at a group open
     * ([repairUnconfirmedGroupRemovals]).
     */
    private suspend fun sendSealedRemovalNotice(
        groupId: String,
        kickedAddress: String,
        adminAddress: String,
        newEpoch: Int,
        noticeAddress: String,
    ) {
        val memo = ZMSGGroupProtocol.createGroupKickMessage(groupId, kickedAddress, adminAddress, newEpoch, null, "")
        val outcome =
            try {
                sendGroupMemoWithRetry(
                    noticeAddress,
                    memo,
                    adminAddress,
                    relaySlot = RelayCopySlots.groupRemoval(groupId, noticeAddress),
                    relayExtra = "$newEpoch|$kickedAddress",
                    mayPayOnChain = { false },
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Sealed removal notice to ${noticeAddress.redactAddress()} failed", e)
                GroupMemoOutcome.NOTHING_SENT
            }
        if (outcome != GroupMemoOutcome.SENT) {
            Log.w(TAG, "Sealed removal notice to ${noticeAddress.redactAddress()} not sent — they are not told they were removed")
        }
    }

    /**
     * Round 8 P6 (PHONE_TRIAGE F5b) — whether this phone is still in [groupId]: false once the admin removed us or we
     * left ([GroupInfo.isActive]). Nothing is quoted or sent in a group we are not in.
     */
    private fun isInGroup(groupId: String): Boolean =
        zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }?.isActive != false

    /**
     * ZC-757 — the stage of [groupId] as a group this phone rebuilt ([RebuiltGroupKey.stage]); NONE for every other
     * group, and for one that cannot be read.
     */
    private fun rebuiltStage(groupId: String): RebuiltGroupKey.Stage =
        RebuiltGroupKey.stage(
            runCatching { zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } }.getOrNull(),
        )

    /**
     * ZC-757 (G1, G6) — the refusal of a message (or, with [invitation], of an invitation) in a group this phone rebuilt
     * and whose new key has not reached a member yet; null when it may be sent. An invitation is refused only before the
     * new key.
     */
    private fun rebuiltRefusal(
        groupId: String,
        invitation: Boolean = false,
    ): GroupSendResult.NeedsNewKey? =
        when (rebuiltStage(groupId)) {
            RebuiltGroupKey.Stage.NEEDS_KEY -> GroupSendResult.NeedsNewKey(groupId, noReader = false)
            RebuiltGroupKey.Stage.NO_READER -> if (invitation) null else GroupSendResult.NeedsNewKey(groupId, noReader = true)
            RebuiltGroupKey.Stage.NONE -> null
        }

    /**
     * ZC-757 — the notice of a group this phone rebuilt, to its admin: NEEDS_KEY before the new key, NO_READER after a
     * new key no member was sent; null (no notice) otherwise.
     */
    fun rebuiltGroupNotice(groupId: String): RebuiltGroupKey.Stage? = rebuiltStage(groupId).takeIf { it != RebuiltGroupKey.Stage.NONE }

    /**
     * ZC-757 (G6) — the record of a rebuilt group after its new key ([rekeyed]) or after that key, or an invitation
     * carrying it, was sent to a member row ([reached]). Only flags that are not set yet are written; read again under the
     * group lock.
     */
    private fun markRebuiltProgress(
        groupId: String,
        rekeyed: Boolean,
        reached: Boolean,
    ) {
        withGroupLock(groupId) {
            val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return@withGroupLock
            if (!info.announcerRebuilt) return@withGroupLock
            val now = info.rekeyedAfterRebuild || rekeyed
            val after = info.copy(rekeyedAfterRebuild = now, rebuiltKeyReachedMember = info.rebuiltKeyReachedMember || (reached && now))
            if (after != info) zchatPreferences.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(after))
        }
    }

    /**
     * ZC-1069 — an owed key of [groupId] that went ([GroupMemoOutcome.SENT]) or may have gone ([GroupMemoOutcome.MAYBE_SENT])
     * out: in a rebuilt group whose new key reached nobody, the current key is that new key (no owed key goes out before
     * it), so it now reached a member row, as an invitation's does (G6 step 2). Writes nothing in any other group.
     */
    private fun markOwedKeyReached(
        groupId: String,
        outcome: GroupMemoOutcome,
    ) {
        if (outcome == GroupMemoOutcome.SENT || outcome == GroupMemoOutcome.MAYBE_SENT) markRebuiltProgress(groupId, false, true)
    }

    /**
     * ZC-757 (T7bG review round 2, finding 2) — the refusal of the first new key of a rebuilt group while no history pass
     * of this process has read the newest block time: the phone's clock alone could number it below what members hold.
     * Nothing is made, sent or paid; null when the key may be made.
     */
    private fun rebuiltKeyWaitsForHistory(groupId: String): GroupSendResult.RemovalIncomplete? =
        if (rebuiltStage(groupId) == RebuiltGroupKey.Stage.NEEDS_KEY && RebuiltGroupKey.newestMinedTxMs <= 0L) {
            GroupSendResult.RemovalIncomplete(
                groupId,
                pendingMembers = 0,
                refusal = co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.REBUILT_KEY_WAITS_FOR_HISTORY,
            )
        } else {
            null
        }

    /** ZC-757 — [groupId]'s record, or null when it cannot be read. */
    private fun rebuiltGroupInfo(groupId: String): GroupInfo? =
        runCatching { zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } }.getOrNull()

    /** Round 8 P6 — the refusal of a send in a group this phone is no longer in, or null while we are in it. */
    private fun notInGroupResult(groupId: String): GroupSendResult.NotInGroup? {
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return null
        return if (info.isActive) null else GroupSendResult.NotInGroup(groupId, removed = info.removedByAdmin)
    }

    /**
     * The key holders a kick of [kickedAddress] (null: a rotation) must re-key: every roster row that holds the
     * current key ([ZMSGGroupProtocol.holdsGroupKey]), not us, not the removed member (matched canonically).
     */
    private fun rotationKeyHolders(
        groupId: String,
        kickedAddress: String?,
    ): List<GroupMember> {
        val members =
            zchatPreferences.getGroupMembers(groupId)?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                ?: return emptyList()
        val kickedCanonical = kickedAddress?.let { zchatPreferences.resolvePeerAddress(it) }
        return members.filter {
            ZMSGGroupProtocol.holdsGroupKey(it) &&
                !zchatPreferences.isSelfAddress(it.address) &&
                (kickedCanonical == null || zchatPreferences.resolvePeerAddress(it.address) != kickedCanonical)
        }
    }

    /**
     * True when a signed group control (a new key) can be sent to [address]: we hold a KEX session with them and our
     * per-peer private key. rotateAndNotify and the owed-key retry skip everyone else.
     */
    private fun hasGroupSession(address: String): Boolean =
        deriveSessionKey(address) != null && zchatPreferences.getE2EPrivateKey(address) != null

    /** GR-OPEN G1 — [address] is a non-LEFT row of [groupId] that holds the current key (matched canonically). */
    private fun senderIsKeyHolderRow(
        groupId: String,
        address: String,
    ): Boolean {
        val canon = zchatPreferences.resolvePeerAddress(address)
        return storedRoster(groupId).orEmpty().any {
            it.status != MemberStatus.LEFT &&
                ZMSGGroupProtocol.holdsGroupKey(it) &&
                zchatPreferences.resolvePeerAddress(it.address) == canon
        }
    }

    /** GR-OPEN G2 — [address], its canonical form and every known conversation peer that canonicalizes to it. */
    private fun representationsOf(address: String): List<String> {
        val canonical = zchatPreferences.resolvePeerAddress(address)
        return (
            listOf(address, canonical) +
                zchatPreferences.getAllConversationPeerAddresses().filter { zchatPreferences.resolvePeerAddress(it) == canonical }
        ).distinct()
    }

    /** GR-OPEN G2 — every relay key stored for any representation of [address], lowercased. */
    private fun relayKeysAnyRep(address: String): Set<String> =
        representationsOf(address).mapNotNull { zchatPreferences.getPeerNostrPubkey(it)?.lowercase() }.toSet()

    /**
     * GR-OPEN G2 (fold-specs/groups SPEC §2.2, review F) — may a group control go to [address] in its sealed form (over
     * the relays only, unsigned, the key in plaintext)? Only when no representation of it has a secure session with us,
     * we hold no E2E public key for any representation (so it never key-exchanged with us and its phone can only have
     * joined with the creator's plaintext invite), and it has a relay key. A member we hold a key for but have no
     * session with keeps today's behaviour (asked about, owed).
     */
    private fun sealedRouteEligible(address: String): Boolean {
        // The relay key first: a member with none can never take a sealed control, and nothing else is read for it.
        if (findPeerNostrPubkeyAnyRep(address) == null) return false
        val reps = representationsOf(address)
        if (reps.any { hasGroupSession(it) }) return false
        return reps.none { runCatching { zchatPreferences.getE2EPeerPublicKey(it) }.getOrNull() != null }
    }

    /**
     * GR-OPEN G2 (SPEC §2.2) — the relay keys the new group key must never be published to: every relay key of every
     * representation of [kickedAddress] (null for a rotation) and of every LEFT row of [groupId], lowercased.
     */
    private fun removedRelayKeys(
        groupId: String,
        kickedAddress: String?,
    ): Set<String> {
        val left = storedRoster(groupId).orEmpty().filter { it.status == MemberStatus.LEFT }.map { it.address }
        return (listOfNotNull(kickedAddress) + left).flatMap { relayKeysAnyRep(it) }.toSet()
    }

    /**
     * GR-OPEN G2 — [address] can be sent the new key of [groupId] as a sealed control now or later: [sealedRouteEligible]
     * and its relay key is no relay key of the removed member or of a LEFT row ([removedRelayKeys]).
     */
    private fun sealedKeyRouteOpen(
        groupId: String,
        address: String,
        kickedAddress: String?,
    ): Boolean {
        if (!sealedRouteEligible(address)) return false
        val key = findPeerNostrPubkeyAnyRep(address)?.lowercase() ?: return false
        return key !in removedRelayKeys(groupId, kickedAddress)
    }

    /** GR-OPEN G2 — an ACTIVE row of [groupId] is owed the current key, has no session, and the sealed route reaches it now. */
    private fun hasSealedOwedMember(groupId: String): Boolean =
        isGroupAdmin(groupId) &&
            storedRoster(groupId).orEmpty().any {
                it.status == MemberStatus.ACTIVE &&
                    it.pendingKeyEpoch != null &&
                    !zchatPreferences.isSelfAddress(it.address) &&
                    !hasGroupSession(it.address) &&
                    sealedKeyRouteOpen(groupId, it.address, null) &&
                    reachableFree(it.address)
            }

    /**
     * GR-OPEN G2 (review E) — [address] is reachable for a new key RIGHT NOW: a secure session, or the sealed route while
     * the relays can reach it ([reachableFree]). With the relays down a member is not reachable, so it is asked about and
     * left out of the recipients, never quoted on-chain for a message it cannot read.
     */
    private fun keyReachableNow(
        groupId: String,
        address: String,
        kickedAddress: String?,
        count: SealedKeyCount = SealedKeyCount.FORECAST,
    ): Boolean =
        hasGroupSession(address) ||
            (
                count != SealedKeyCount.AFTER_PASS &&
                    sealedKeyRouteOpen(groupId, address, kickedAddress) &&
                    reachableFree(address) &&
                    !(count == SealedKeyCount.LAST_RESULT && sealedKeyWasUnacked(groupId, address))
            )

    /**
     * GR-OPEN fixup (G-GR-1) — how [keyReachableNow] counts a member with no session that only the sealed route can send
     * the key to.
     */
    private enum class SealedKeyCount {
        /** Reachable while the relays are up: the sealed re-send before a message will be tried (quote, checks, question). */
        FORECAST,

        /** As [FORECAST], but not while its last sealed key got no relay ack ([sealedKeyUnacked]): the cost line. */
        LAST_RESULT,

        /**
         * Never: the sealed-only re-send before this message has just run, so a member still owed the key got no ack in
         * it and cannot read the message (it would be marked seen on its phone, fail to decrypt and never be replayed).
         */
        AFTER_PASS,
    }

    /** GR-OPEN fixup — record whether the sealed key sent to [address] in [groupId] was acked by a relay. */
    private fun noteSealedKeyResult(
        groupId: String,
        address: String,
        acked: Boolean,
    ) {
        val canonical = zchatPreferences.resolvePeerAddress(address)
        if (acked) {
            sealedKeyUnacked[groupId]?.remove(canonical)
        } else {
            val unacked = sealedKeyUnacked.getOrPut(groupId) { java.util.concurrent.ConcurrentSkipListSet() }
            unacked.add(canonical)
        }
    }

    private fun sealedKeyWasUnacked(
        groupId: String,
        address: String,
    ): Boolean = sealedKeyUnacked[groupId]?.contains(zchatPreferences.resolvePeerAddress(address)) == true

    /**
     * Round 8 P5 (PHONE_TRIAGE F5a) — the key holders a kick of [kickedAddress] (null: a rotation) cannot send the new
     * key to, one row per member: no row of theirs (#214 duplicate representations, matched canonically) has a secure
     * session with us. After the rotation they are owed the key with nothing that can deliver it until a key exchange
     * with them completes.
     */
    private fun unkeyableKeyHolders(
        groupId: String,
        kickedAddress: String?,
    ): List<GroupMember> =
        rotationKeyHolders(groupId, kickedAddress)
            .groupBy { zchatPreferences.resolvePeerAddress(it.address) }
            .values
            // GR-OPEN G2 (review E): a member the sealed route reaches over the relays right now is not asked about.
            .filter { rows -> rows.none { keyReachableNow(groupId, it.address, kickedAddress) } }
            .map { it.first() }

    /**
     * Round 8 P5 (PHONE_TRIAGE F5a) — run [proceed] (the kick or rotation, which then quotes its cost) at once when
     * every key holder can be sent the new key; otherwise ask first with [rekeyUnkeyableConfirmation], naming the
     * members who can't. Nothing is quoted, sent or changed before the answer.
     */
    private fun askAboutUnkeyable(
        groupId: String,
        kickedAddress: String?,
        proceed: () -> Unit,
    ) {
        val unkeyable = unkeyableKeyHolders(groupId, kickedAddress)
        if (unkeyable.isEmpty()) {
            proceed()
            return
        }
        pendingRekey =
            PendingRekey(
                groupId = groupId,
                kickedAddress = kickedAddress,
                named = unkeyable.map { zchatPreferences.resolvePeerAddress(it.address) }.toSet(),
                proceed = proceed,
            )
        val book = nameBook()
        _rekeyUnkeyableConfirmation.value =
            RekeyUnkeyableConfirmation(
                groupId = groupId,
                names = unkeyable.map { memberName(it, book) },
                removing = kickedAddress != null,
            )
    }

    /**
     * Round 8 P5 — "Remove anyway" (or "Rotate anyway"): run the waiting kick or rotation once. If a key holder the
     * question did not name has lost its session since, it is asked again with the new list and nothing runs.
     */
    fun confirmRekeyUnkeyable() {
        val pending = pendingRekey ?: return
        pendingRekey = null
        _rekeyUnkeyableConfirmation.value = null
        val now =
            unkeyableKeyHolders(pending.groupId, pending.kickedAddress)
                .map { zchatPreferences.resolvePeerAddress(it.address) }
        if (!pending.named.containsAll(now)) {
            Log.w(TAG, "Rekey: more members lost their secure session after the question — asking again, nothing changed")
            askAboutUnkeyable(pending.groupId, pending.kickedAddress, pending.proceed)
            return
        }
        pending.proceed()
    }

    /** Round 8 P5 — Cancel: nothing runs; the epoch, the roster and the removal floor stay as they were. */
    fun dismissRekeyUnkeyable() {
        pendingRekey = null
        _rekeyUnkeyableConfirmation.value = null
    }

    // D5 — per-group rotation lock. Serializes rotateAndNotify's epoch read->commit so two rapid
    // kicks/rotations can't both read epoch N and mint two DIFFERENT N+1 keys (receivers keep the
    // first, reject the second as replay -> permanent group fork). The second caller waits and reads
    // the freshly committed epoch, advancing to N+2. KI-22: process-wide (companion), held by every admin change.
    private fun rotationLock(groupId: String) = rotationLocks.getOrPut(groupId) { Mutex() }

    // KI-22 — the groups for which a change started on THIS screen waits for another change of the same group; the
    // screen shows "Waiting for the other change to this group to finish…" for them.
    private val waitingForChange = HashMap<String, Int>()
    private val _groupsWaitingForChange = MutableStateFlow<Set<String>>(emptySet())
    val groupsWaitingForChange: StateFlow<Set<String>> = _groupsWaitingForChange.asStateFlow()

    private fun noteWaitingForChange(
        groupId: String,
        delta: Int,
    ) {
        synchronized(waitingForChange) {
            val n = (waitingForChange[groupId] ?: 0) + delta
            if (n > 0) waitingForChange[groupId] = n else waitingForChange.remove(groupId)
            _groupsWaitingForChange.value = waitingForChange.keys.toSet()
        }
    }

    /**
     * KI-22 — run [block] holding [groupId]'s change lock, the one lock for the whole app (see [rotationLocks]). Every
     * admin change of a group holds it: kick, rotate, add a member, re-send an invite, leave, the owed-key pass and
     * introductions. A change started while another one runs (from this screen or another) waits, and this screen
     * says so while it waits. Not re-entrant: no holder calls another holder. Lock order: this Mutex first, then the
     * process-wide group lock ([withGroupLock]), which is never held across a suspension.
     */
    private suspend inline fun <T> withGroupChange(
        groupId: String,
        block: () -> T,
    ): T {
        val lock = rotationLock(groupId)
        if (!lock.tryLock()) {
            noteWaitingForChange(groupId, 1)
            try {
                lock.lock()
            } finally {
                noteWaitingForChange(groupId, -1)
            }
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * KI-22 — [block] under the process-wide lock of [groupId] ([ZchatPreferences.groupLock]), the lock ChatViewModel's
     * inbound GROUP_ACCEPT / LEAVE / KICK / KEY handlers hold. Every roster read-modify-write of this ViewModel runs
     * inside it, reading the roster fresh. No suspend call inside [block].
     */
    private inline fun <T> withGroupLock(
        groupId: String,
        block: () -> T,
    ): T = synchronized(ZchatPreferences.groupLock(groupId)) { block() }

    /** KI-22 — the roster of [groupId] as stored now (null when there is none). Call under [withGroupLock]. */
    private fun storedRoster(groupId: String): List<GroupMember>? =
        zchatPreferences.getGroupMembers(groupId)?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }

    // P0.1 — latest visible group-send outcome; consumed (nulled) by the screen that rendered it.
    val groupSendEvent: StateFlow<GroupSendResult?> = _groupSendEvent.asStateFlow()

    /** Clear the current [groupSendEvent] once the UI has surfaced it. */
    fun consumeGroupSendEvent() {
        _groupSendEvent.value = null
    }

    /**
     * Round 9 R9-5 (REVIEW_R8 F10 = device D-F4) — the group whose screen state this ViewModel last built, and the
     * [ZchatPreferences.groupStoreVersion] of that group read just BEFORE it read the store (so a write during the read
     * still counts as new).
     */
    @Volatile
    private var shownStoreVersion: Pair<String, Long?>? = null

    init {
        loadCurrentUserAddress()
        loadGroups()
        observeExchangeRate()
        observeGroupStore()
        observeOwnAddress()
    }

    /**
     * Phone check P-G1 r1 of train T6b: the group screens are built with the address known at that moment ("" before
     * [loadCurrentUserAddress] has read it), and a message is drawn as ours only when its sender is that address. On a
     * wallet whose history is read at once both states of a group open were built before the address arrived, and our
     * own message stayed on the left. Once the address is known (or changes), the shown screens carry it.
     */
    private fun observeOwnAddress() {
        viewModelScope.launch {
            _currentUserAddress.collect { address ->
                if (address == null) return@collect
                // ZC-1045, ZC-1046: the passes of a group open that came before the address.
                for (groupId in openPassesAwaitingAddress.keys.toList()) {
                    val afterSend = openPassesAwaitingAddress.remove(groupId) ?: continue
                    viewModelScope.launch { runOpenKeyPasses(groupId, afterSend) }
                }
                val detail = _groupDetailState.value
                if (detail is GroupDetailState.Success && detail.currentUserAddress != address) {
                    _groupDetailState.value = detail.copy(currentUserAddress = address)
                }
                val settings = _groupSettingsState.value
                if (settings is GroupSettingsState.Success && settings.currentUserAddress != address) {
                    _groupSettingsState.value = settings.copy(currentUserAddress = address)
                }
            }
        }
    }

    private fun noteShownStoreVersion(groupId: String) {
        shownStoreVersion = groupId to ZchatPreferences.groupStoreVersion.value[groupId]
    }

    /** The group this screen is showing when its store has been written since the screen last read it, else null. */
    private fun shownGroupWithNewWrites(): String? {
        val shown = (_groupDetailState.value as? GroupDetailState.Success)?.conversation?.groupInfo?.groupId ?: return null
        val version = ZchatPreferences.groupStoreVersion.value[shown] ?: return null
        return shown.takeIf { shownStoreVersion != (shown to version) }
    }

    /**
     * Round 9 R9-5 (REVIEW_R8 F10 = device D-F4) — the open group screen follows the group stores. ChatViewModel writes
     * a group's messages, roster and announce state when a message, an accept or an announce arrives, and before this
     * the screen only read them when it opened and after its own actions ("No messages yet" 20 s after a message was
     * decrypted). Now each write of the shown group reloads the screen state from the store (as [refreshGroupDetail]),
     * at most once per [GROUP_STORE_RELOAD_DEBOUNCE_MS]: no owed-key retry, no announce pass, nothing sent or paid.
     */
    private fun observeGroupStore() {
        viewModelScope.launch {
            ZchatPreferences.groupStoreVersion.collect {
                if (shownGroupWithNewWrites() == null) return@collect
                // Writes come in bursts (a message stores its row and its sender's roster row): wait, then read once.
                delay(GROUP_STORE_RELOAD_DEBOUNCE_MS)
                val groupId = shownGroupWithNewWrites() ?: return@collect
                runCatching { refreshGroupDetail(groupId) }
                    .onFailure { Log.w(TAG, "Group ${groupId.take(12)}: the screen could not reload after a store write", it) }
            }
        }
    }

    private fun loadCurrentUserAddress() {
        viewModelScope.launch {
            val address = getDefaultUnifiedAddress()
            _currentUserAddress.value = address
            // #205 — record our canonical address so hash-tolerant self-checks (isAdmin / isCreator)
            // recognise us even when a group's stored creatorAddress is a different representation.
            zchatPreferences.registerSelfAddress(address)
        }
    }

    private fun observeExchangeRate() {
        viewModelScope.launch {
            exchangeRateRepository.state.collectLatest { state ->
                if (state is ExchangeRateState.Data) {
                    _zecPriceUsd.value = state.currencyConversion?.priceOfZec
                }
            }
        }
    }

    /**
     * Load all groups from preferences.
     */
    fun loadGroups() {
        viewModelScope.launch {
            try {
                val groupIds = zchatPreferences.getAllGroupIds()
                val groups = groupIds.mapNotNull { groupId ->
                    loadGroup(groupId)
                }
                _groupConversations.value = groups.sortedByDescending {
                    it.lastMessage?.timestamp ?: it.groupInfo.createdAt
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load groups", e)
            }
        }
    }

    /**
     * Load a single group conversation. [detail]: loaded for the group screen (Round 8 MA2: only it shows, and so uses
     * up, the one-time roster notice).
     */
    private fun loadGroup(
        groupId: String,
        detail: Boolean = false,
    ): GroupConversation? {
        val infoJson = zchatPreferences.getGroupInfo(groupId) ?: return null
        val groupInfo = ZMSGGroupProtocol.deserializeGroupInfo(infoJson) ?: return null

        val membersJson = zchatPreferences.getGroupMembers(groupId)
        val members = if (membersJson != null) {
            ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
        } else {
            emptyList()
        }

        val draft = zchatPreferences.getGroupDraft(groupId)
        val pending = pendingGroupMessages.value[groupId] ?: emptyList()

        // Load messages from stored history (loaded asynchronously via loadGroupMessagesFromHistory)
        val storedMessagesJson = zchatPreferences.getGroupMessages(groupId)
        // GR-OPEN G1 (SPEC §1.3 step 3): a held row (a pre-removal-key message from a non-member) is kept in the store
        // but never shown, previewed or counted; it is shown once released (the same authenticated sender posts at the
        // current epoch).
        val storedMessages = if (storedMessagesJson != null) {
            parseStoredGroupMessages(storedMessagesJson).filterNot { it.held }
        } else {
            emptyList()
        }

        // Reconcile optimistic pending messages against their mined copies. A pending message is one
        // WE sent from this device; it's superseded once a stored (mined + chain-decrypted) message
        // with the SAME (sender, seq, epoch) identity appears. Keep ALL stored messages and drop only
        // the pending entries that have mined — never filter stored by seq alone: seq is per-sender, so
        // two different senders can share a seq and a seq-only filter would (a) hide another member's
        // message and (b) leave OUR own mined message perpetually showing as "pending".
        fun identity(m: GroupMessage) = Triple(m.senderAddress, m.seq, m.epoch)
        // #426: the two writers of the group-message store use DIFFERENT id schemes for the same on-chain
        // message — the history scan writes "<txid>_<seq>" while ChatViewModel's live path writes plain
        // "<txid>" — so id-dedup fails across them and one message is stored twice. (sender, seq, epoch) is
        // globally unique per message (seq is per-sender monotonic; it's the SAME identity the pending
        // reconciliation below already trusts), so collapse duplicates on it, preferring a decrypted copy.
        // ZC-493: the shown row of an own message whose chain copies all expired unmined (see
        // [undeliveredOwnMessages]) is drawn as failed, with its text. A stored block height on any row of the message
        // (a late mine, stored by ChatViewModel's live pass) takes the mark off at once, before the next scan. Display
        // only: the row is chosen as before and the store is not written.
        val undelivered = undeliveredOwnMessages[groupId].orEmpty()
        val dedupedStored = storedMessages
            .groupBy { identity(it) }
            .values
            .map { dup ->
                val shown = dup.firstOrNull { it.decryptedContent != null } ?: dup.first()
                val expiredUnmined =
                    shown.decryptedContent != null && identity(shown) in undelivered && dup.none { it.blockHeight != null }
                if (expiredUnmined) shown.copy(isFailed = true) else shown
            }
        val storedIdentities = dedupedStored.map { identity(it) }.toSet()
        val unreconciledPending = pending.filterNot { identity(it) in storedIdentities }
        val messages = (dedupedStored + unreconciledPending).sortedBy { it.timestamp }

        // Round 8 MA2 (design §5.2, §8, §9): on a member's phone whose roster the creator announced, the members the
        // creator shared, the senders of messages who are not (yet) in the member list, and the one-time notice.
        val announced = announcedRecipients(groupId)
        val canon = zchatPreferences::resolvePeerAddress
        val sharedByCreator =
            if (announced == null) {
                emptySet()
            } else {
                val creator = canon(groupInfo.creatorAddress)
                members.map { it.address }.filter { canon(it) in announced && canon(it) != creator }.toSet()
            }
        val listed = members.filter { it.status == MemberStatus.ACTIVE }.map { canon(it.address) }.toSet()
        // Round 10b R10B-2 (F-R9B-2): a sender the creator removed (an R announce, or a roster row KICKED or LEFT) is
        // "no longer in this group", never "not in your member list yet"; that label keeps only senders never listed.
        val sendersRemoved =
            if (announced == null) {
                emptySet()
            } else {
                val announceState = announceState(groupId)
                val removedRows =
                    members
                        .filter { it.status == MemberStatus.KICKED || it.status == MemberStatus.LEFT }
                        .map { canon(it.address) }
                        .toSet()
                messages
                    .map { it.senderAddress }
                    .filter { sender ->
                        val c = canon(sender)
                        !zchatPreferences.isSelfAddress(sender) &&
                            c !in listed &&
                            (announceState.members[c]?.op == AnnounceOp.R || c in removedRows)
                    }.toSet()
            }
        val sendersNotInList =
            if (announced == null) {
                emptySet()
            } else {
                messages
                    .map { it.senderAddress }
                    .filter {
                        !zchatPreferences.isSelfAddress(it) &&
                            it !in sendersRemoved &&
                            (canon(it) !in announced || canon(it) !in listed)
                    }.toSet()
            }

        return GroupConversation(
            groupInfo = groupInfo,
            members = members,
            messages = messages,
            lastMessage = messages.lastOrNull(),
            unreadCount = 0,
            draft = draft,
            sharedByCreator = sharedByCreator,
            sendersNotInList = sendersNotInList,
            sendersRemoved = sendersRemoved,
            // GROUP-LABEL: names for the shown messages' senders (held rows are already left out above) and the members.
            senderNames = if (detail) groupNames(members, messages) else emptyMap(),
            rosterSharedNotice = detail && announced != null && rosterNoticeShown(groupId),
            // Round 8 MA3 (design §6.2, §9): the announcer's banner, button and row notes.
            introduce = if (detail) runCatching { introduceInfo(groupId, groupInfo, members) }.getOrNull() else null,
        )
    }

    /**
     * Round 8 MA2 (design §8, `group_roster_now_shared`) — whether the one-time "the creator now shares every member's
     * address" notice shows for [groupId]: from the first load after the roster was announced, for as long as this
     * screen's ViewModel lives. It is marked shown in the announce state at that first load, so it is not shown again.
     */
    private fun rosterNoticeShown(groupId: String): Boolean {
        if (groupId in rosterNoticesShowing) return true
        val first =
            synchronized(ZchatPreferences.groupLock(groupId)) {
                val state = announceState(groupId)
                !state.rosterNoticeShown &&
                    runCatching {
                        zchatPreferences.saveGroupAnnounceState(groupId, state.copy(rosterNoticeShown = true))
                    }.getOrDefault(false)
            }
        if (first) rosterNoticesShowing += groupId
        return first
    }

    /**
     * Parse stored group messages from JSON.
     */
    private fun parseStoredGroupMessages(jsonString: String): List<GroupMessage> {
        return try {
            val jsonArray = org.json.JSONArray(jsonString)
            (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val obj = jsonArray.getJSONObject(i)
                    GroupMessage(
                        id = obj.getString("id"),
                        groupId = obj.getString("groupId"),
                        txId = null, // TransactionId can't be reconstructed from string
                        seq = obj.getLong("seq"),
                        epoch = obj.getInt("epoch"),
                        senderAddress = obj.getString("sender"),
                        encryptedContent = if (obj.has("encrypted")) obj.getString("encrypted") else null,
                        decryptedContent = if (obj.has("decrypted")) obj.getString("decrypted") else null,
                        nonce = if (obj.has("nonce")) obj.getString("nonce") else null,
                        // B7 (GROUP-MSG-TIME): rows the old history scan stored hold the raw wire SECONDS;
                        // live-path, NOSTR and pending rows hold milliseconds. Read both; never rewrite.
                        timestamp = ZMSGGroupProtocol.groupTimeToInstant(obj.getLong("timestamp")),
                        blockHeight = if (obj.has("blockHeight")) obj.getLong("blockHeight") else null,
                        txIndex = if (obj.has("txIndex")) obj.getInt("txIndex") else null,
                        isPending = false,
                        isFailed = false,
                        // GR-OPEN G1: read so the history merge writes the mark back (a parser that skipped held rows
                        // would delete them on the next merge).
                        held = obj.optString("held").isNotEmpty(),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse stored message at index $i", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored group messages JSON", e)
            emptyList()
        }
    }

    /**
     * Serialize group messages to JSON for storage.
     */
    private fun serializeGroupMessages(messages: List<GroupMessage>): String {
        val jsonArray = org.json.JSONArray()
        messages.forEach { msg ->
            val obj = org.json.JSONObject().apply {
                put("id", msg.id)
                put("groupId", msg.groupId)
                msg.txId?.let { put("txId", it.txIdString()) }
                put("seq", msg.seq)
                put("epoch", msg.epoch)
                put("sender", msg.senderAddress)
                msg.encryptedContent?.let { put("encrypted", it) }
                msg.decryptedContent?.let { put("decrypted", it) }
                msg.nonce?.let { put("nonce", it) }
                put("timestamp", msg.timestamp.toEpochMilli())
                msg.blockHeight?.let { put("blockHeight", it) }
                msg.txIndex?.let { put("txIndex", it) }
                if (msg.held) put("held", HELD_REMOVAL)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    /**
     * R8-fixup F2 (REVIEW_R8 F2 a) — store our own group message [message] (its text, sequence number, key epoch and
     * time) as a "grps-" row, appended in the schema parseStoredGroupMessages reads; the other rows are not touched. The
     * row stands for the message however it was delivered, so the group screen still lists it after the ViewModel that
     * sent it is gone. Written once per (sender, seq, epoch).
     */
    private fun storeOwnGroupMessage(message: GroupMessage) {
        val groupId = message.groupId
        synchronized(ZchatPreferences.groupLock(groupId)) {
            val rows =
                zchatPreferences.getGroupMessages(groupId)?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
                    ?: org.json.JSONArray()
            val id = "grps-$groupId-${message.epoch}-${message.seq}"
            val present = (0 until rows.length()).any { rows.optJSONObject(it)?.optString("id") == id }
            if (present) return
            rows.put(
                org.json.JSONObject().apply {
                    put("id", id)
                    put("groupId", groupId)
                    put("seq", message.seq)
                    put("epoch", message.epoch)
                    put("sender", message.senderAddress)
                    message.decryptedContent?.let { put("decrypted", it) }
                    put("timestamp", message.timestamp.toEpochMilli())
                }
            )
            zchatPreferences.saveGroupMessages(groupId, rows.toString())
        }
    }

    /**
     * Load group messages from blockchain transaction history.
     * This scans all transactions for messages belonging to the specified group.
     */
    suspend fun loadGroupMessagesFromHistory(groupId: String) {
        try {
            val transactions = transactionRepository.getTransactions()
            if (transactions.isEmpty()) {
                // ZC-493: a wallet that lists no transaction has no expired copy of any message.
                undeliveredOwnMessages.remove(groupId)
                return
            }
            // Per-message epoch decrypt: each message is decrypted with the key for ITS OWN epoch (looked
            // up inside parseAndDecryptGroupMessage), not a single current-epoch key — otherwise every
            // message from before a key rotation fails to decrypt.

            val messagesFromHistory = mutableListOf<GroupMessage>()
            // ZC-493: our own messages by the state of their chain copies. "expired" = the wallet lists the transaction
            // as expired and it was never mined; "live" = mined, or not mined yet and not expired (it may still land).
            val expiredOwnCopies = HashSet<Triple<String, Long, Int>>()
            val liveOwnCopies = HashSet<Triple<String, Long, Int>>()

            for (tx in transactions) {
                val memos = try {
                    transactionRepository.getMemos(tx)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "getMemos failed: ${e.message}")
                    emptyList()
                }
                for (memo: String in memos) {
                    if (!ZMSGGroupProtocol.isGroupMessage(memo)) continue

                    val parsedGroupId = ZMSGGroupProtocol.parseGroupId(memo)
                    if (parsedGroupId != groupId) continue

                    val message = parseAndDecryptGroupMessage(memo, tx)
                    if (message != null) {
                        messagesFromHistory.add(message)
                        if (tx is SendTransaction) {
                            val identity = Triple(message.senderAddress, message.seq, message.epoch)
                            if (tx is SendTransaction.Failed && tx.overview.minedHeight == null) {
                                expiredOwnCopies += identity
                            } else {
                                liveOwnCopies += identity
                            }
                        }
                    }
                }
            }

            // Sort by timestamp and deduplicate by id (txId+seq), NOT by seq alone: seq is a
            // per-DEVICE counter, so different senders reuse the same seq numbers. Grouping by seq
            // collapsed distinct cross-sender messages into one and silently dropped them. id is
            // globally unique per transaction, so true re-parses of the same tx still collapse.
            val uniqueMessages = messagesFromHistory
                .groupBy { it.id }
                .mapValues { (_, msgs) -> msgs.maxByOrNull { it.timestamp } }
                .values
                .filterNotNull()
                .sortedBy { it.timestamp }

            // ZC-493: replace the group's set before the store is written (the write reloads an open screen). Every row is
            // still stored and carried below exactly as before, whatever the state of its transaction.
            undeliveredOwnMessages[groupId] = expiredOwnCopies - liveOwnCopies

            // Save to preferences for persistence
            if (uniqueMessages.isNotEmpty()) {
                // MERGE, don't overwrite: this scan only re-derives ON-CHAIN messages, but ChatViewModel
                // delivers FREE-NOSTR group messages (#11, id "grpn-…") into the SAME store and those are
                // NOT re-derivable from chain — a wholesale overwrite here permanently deleted every one of
                // them. (An on-chain double-write across the two id schemes is collapsed at display time by
                // loadGroup, #426.)
                // R8-fixup F2 (REVIEW_R8 F2): the merge never deletes. Every stored row is carried forward — relay rows,
                // our own relay-sent rows ("grps-"), and on-chain rows the live path accepted and showed that this scan
                // does not re-derive (after a removal it drops unsigned pre-removal messages, and members introduced by
                // the member announce share no key, so theirs are all unsigned). A stored row is dropped only when this
                // scan re-derived the same message: the same id, or the same (sender, seq, epoch) with the same text.
                // The D4 gate still keeps the scan from ADDING a pre-removal message it never had.
                val storedRows =
                    zchatPreferences
                        .getGroupMessages(groupId)
                        ?.let { parseStoredGroupMessages(it) }
                        ?: emptyList()
                val derivedIds = uniqueMessages.mapTo(HashSet()) { it.id }
                val derivedCopies =
                    uniqueMessages.mapTo(HashSet()) { Triple(it.senderAddress, it.seq, it.epoch) to it.decryptedContent }
                val carried =
                    storedRows.filterNot {
                        it.id in derivedIds || (Triple(it.senderAddress, it.seq, it.epoch) to it.decryptedContent) in derivedCopies
                    }
                // GR-OPEN G1: a message the live path HELD stays held when this scan re-derives it (same id, or the same
                // (sender, seq, epoch) with the same text); only the live path's release shows it.
                val heldIds = storedRows.filter { it.held }.mapTo(HashSet()) { it.id }
                val heldCopies =
                    storedRows.filter { it.held }.mapTo(HashSet()) { Triple(it.senderAddress, it.seq, it.epoch) to it.decryptedContent }
                val derived =
                    uniqueMessages.map {
                        val wasHeld = it.id in heldIds || (Triple(it.senderAddress, it.seq, it.epoch) to it.decryptedContent) in heldCopies
                        if (wasHeld) it.copy(held = true) else it
                    }
                val merged = (derived + carried).sortedBy { it.timestamp }
                zchatPreferences.saveGroupMessages(groupId, serializeGroupMessages(merged))

                // Prune in-memory optimistic pending entries that have now mined (same sender, seq,
                // epoch), so the pending list doesn't grow unbounded across a session. loadGroup's merge
                // already hides them from display; this reclaims the memory and avoids re-merging them.
                val minedIdentities = uniqueMessages.map { Triple(it.senderAddress, it.seq, it.epoch) }.toSet()
                val curPending = pendingGroupMessages.value
                val groupPending = curPending[groupId]
                if (groupPending != null) {
                    val remaining = groupPending.filterNot {
                        Triple(it.senderAddress, it.seq, it.epoch) in minedIdentities
                    }
                    if (remaining.size != groupPending.size) {
                        pendingGroupMessages.value = curPending.toMutableMap().apply { put(groupId, remaining) }
                    }
                }
            }

            // Refresh the group display
            loadGroups()
            Log.d(TAG, "Loaded ${uniqueMessages.size} messages from history for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load group messages from history for $groupId", e)
        }
    }

    /**
     * Parse and decrypt a group message from a memo.
     */
    private fun parseAndDecryptGroupMessage(
        memo: String,
        tx: Transaction,
    ): GroupMessage? {
        return try {
            val msgType = ZMSGGroupProtocol.parseMessageType(memo)
            if (msgType != GroupMessageType.GROUP_MSG) return null

            val groupId = ZMSGGroupProtocol.parseGroupId(memo) ?: return null
            val payload = ZMSGGroupProtocol.parsePayload(memo) ?: return null
            val msgPayload = ZMSGGroupProtocol.parseGroupMsgPayload(payload) ?: return null

            // Try the message's own epoch first, then fall back across a bounded window of held
            // keys so messages survive key-rotation / lagging-peer epoch-number drift. Mirrors the
            // live processGroupMsg path in ChatViewModel (#215).
            val currentEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
            val windowStart = maxOf(0, currentEpoch - MAX_GROUP_EPOCH_LOOKBACK)
            val candidateEpochs =
                (listOf(msgPayload.epoch, currentEpoch) + (windowStart..currentEpoch))
                    .filter { it >= 0 }
                    .distinct()
            var decrypted: String? = null
            // D4 — the epoch of the key that ACTUALLY decrypted (not the self-asserted payload
            // epoch); feeds the removal-floor gate below.
            var decryptedEpoch = -1
            var triedAnyKey = false
            for (epoch in candidateEpochs) {
                val encodedKey = zchatPreferences.getGroupKey(groupId, epoch) ?: continue
                triedAnyKey = true
                decrypted = runCatching {
                    ZMSGGroupProtocol.decryptMessage(
                        msgPayload.nonce,
                        msgPayload.ciphertext,
                        ZMSGGroupProtocol.decodeGroupKey(encodedKey)
                    )
                }.getOrNull()
                if (decrypted != null) {
                    decryptedEpoch = epoch
                    break
                }
            }
            if (decrypted == null) {
                if (!triedAnyKey) {
                    Log.w(TAG, "No group key for $groupId epoch ${msgPayload.epoch}")
                } else {
                    Log.w(TAG, "Failed to decrypt group message for $groupId epoch ${msgPayload.epoch}")
                }
                return null
            }

            // D4 — the epoch-floor authorship gate, MIRRORED from the live path (ChatViewModel.
            // processGroupMsg): this history rescan re-derives every on-chain memo into the SAME
            // store, so without it a removed member's forged-attribution message dropped live would
            // simply be re-materialized here on the next group open. Only the epoch clause applies —
            // isRemovedMember is deliberately NOT consulted ({ false }), because history legitimately
            // contains a kicked member's PRE-kick messages and this scan re-derives (not appends) the
            // chain-backed rows; the live gate already blocks their NEW arrivals. Current-epoch
            // messages stay fully fail-open (mesh), floor 0 (no kick ever) is inert, and a signed
            // old-epoch message passes (#215 late render / D3 lagging sender).
            val removalFloor = zchatPreferences.getGroupRemovalFloor(groupId)
            if (removalFloor > 0 && decryptedEpoch < removalFloor) {
                // Self-authored messages are trivially author-verified: this device SENT them. We key this on
                // the TRANSACTION DIRECTION (tx is SendTransaction) — NOT the payload's claimed sender, which
                // is attacker-controllable: a removed member holding a pre-removal epoch key could set
                // sender=<our own address> and, if we trusted that field, have their forgery rendered as ours.
                // A memo carried by a transaction OUR OWN wallet actually sent is unforgeable (a removed member
                // cannot make a tx originate from our wallet). Without this exemption the gate below
                // misclassifies 100% of our OWN pre-kick messages as removed-member injections — getE2EPeerPublicKey(self)
                // is structurally always null, so there is no verifiable author signature — and drops them; since
                // our own group messages persist durably ONLY via this rescan merge (sendGroupMessage keeps just
                // an in-memory optimistic bubble), that would silently and permanently erase our entire own
                // pre-kick history on every routine kick.
                val isSelfAuthored = tx is co.electriccoin.zcash.ui.common.repository.SendTransaction
                val authorVerified = isSelfAuthored || ZMSGGroupProtocol.resolveGroupMsgSender(
                    authenticatedSender = null,
                    claimedSender = msgPayload.sender,
                    signature = msgPayload.signature,
                    groupId = groupId,
                    epoch = msgPayload.epoch,
                    seq = msgPayload.seq,
                    ciphertext = msgPayload.ciphertext,
                    heldKey = runCatching { zchatPreferences.getE2EPeerPublicKey(msgPayload.sender) }.getOrNull(),
                    verify = E2EEncryption::verify,
                ).authenticated
                // Round 9 R9-3 (REVIEW_R8 F2 item 3): mirrored from the live path. An unsigned pre-removal message whose
                // transaction was mined before every removal that retired its key is kept: no member was removed when it
                // was mined. Unmined, or with no known removal height, it stays gated.
                val minedHeight = tx.overview.minedHeight?.value
                val minedBeforeRemoval =
                    minedHeight != null &&
                        !authorVerified &&
                        ZMSGGroupProtocol.minedBeforeRemoval(
                            minedHeight,
                            decryptedEpoch,
                            removalFloor,
                            zchatPreferences.getGroupRemovalKickHeights(groupId),
                        )
                if (ZMSGGroupProtocol.isSuspectedRemovedInjection(
                        effectiveSender = msgPayload.sender,
                        decryptedEpoch = decryptedEpoch,
                        removalFloor = removalFloor,
                        authorVerified = authorVerified,
                        isRemovedMember = { false },
                        minedBeforeRemoval = minedBeforeRemoval,
                    )
                ) {
                    Log.w(
                        TAG,
                        "Dropped historical GROUP_MSG claiming ${msgPayload.sender.redactAddress()} — decrypts only " +
                            "under pre-removal epoch $decryptedEpoch < floor $removalFloor with no verified author " +
                            "signature (suspected removed-member injection; accepted false-drop for unsigned legit, D4)"
                    )
                    return null
                }
                // GR-OPEN G1 (SPEC §1.3 step 4): on a phone whose roster is the whole group, a pre-removal-key message
                // from a sender who is no key-holding row is not materialised by this scan (the live path holds it; a
                // stored held row is carried by the merge). Its own mined-before value, whether or not the author is
                // verified (review B); our own sends are exempt by the transaction's direction.
                val minedBeforeAnyAuth =
                    minedHeight != null &&
                        ZMSGGroupProtocol.minedBeforeRemoval(
                            minedHeight,
                            decryptedEpoch,
                            removalFloor,
                            zchatPreferences.getGroupRemovalKickHeights(groupId),
                        )
                if (ZMSGGroupProtocol.isPreRemovalNonMember(
                        decryptedEpoch = decryptedEpoch,
                        removalFloor = removalFloor,
                        rosterAuthoritative = isGroupAdmin(groupId) || announceState(groupId).rosterAnnounced,
                        senderIsKeyHolderRow = senderIsKeyHolderRow(groupId, msgPayload.sender),
                        isSelf = isSelfAuthored,
                        minedBeforeRemoval = minedBeforeAnyAuth,
                    )
                ) {
                    Log.w(
                        TAG,
                        "Not materialised historical GROUP_MSG claiming ${msgPayload.sender.redactAddress()} — not a " +
                            "key-holding member at a retired key (epoch $decryptedEpoch < floor $removalFloor)"
                    )
                    return null
                }
            }

            GroupMessage(
                id = "${tx.id.txIdString()}_${msgPayload.seq}",
                groupId = groupId,
                txId = tx.id,
                seq = msgPayload.seq,
                epoch = msgPayload.epoch,
                senderAddress = msgPayload.sender,
                encryptedContent = msgPayload.ciphertext,
                decryptedContent = decrypted,
                nonce = msgPayload.nonce,
                // B7 (GROUP-MSG-TIME): the sender writes ts in SECONDS (the wire stays so for 2.12.4).
                timestamp = ZMSGGroupProtocol.groupTimeToInstant(msgPayload.timestamp),
                blockHeight = tx.overview.minedHeight?.value,
                txIndex = tx.overview.index?.toInt(),
                isPending = false,
                isFailed = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse group message from memo", e)
            null
        }
    }

    /**
     * Load group detail for viewing.
     * Also triggers loading of message history from blockchain.
     */
    fun loadGroupDetail(groupId: String) = loadGroupDetail(groupId, retryOwedKeys = true)

    /**
     * [retryOwedKeys] false is used by the group controls that have JUST pushed the owed keys themselves
     * under their own quote (kick, rotate, key sync). R4-2: the A2 automatic retry below runs on the
     * handshake-spend consent, not on the prompt the user just confirmed, so letting it fire inside a
     * quoted action would pay on-chain for exactly the members that action quoted as free — one extra
     * send per member, right after a prompt that promised none. It is also pure duplicate work there:
     * those paths have already tried every owed member. Opening the screen still retries as before.
     *
     * ZC-420-G: with [retryOwedKeys] the open also runs the relay-copy repair ([repairUnconfirmedGroupCopies]).
     * [afterSend]: the reload that follows a send, which has just shown its own result, so the repair raises no
     * group-open notice.
     */
    private fun loadGroupDetail(
        groupId: String,
        retryOwedKeys: Boolean,
        afterSend: Boolean = false,
    ) {
        // SF-2 — the send-event flow is process-wide, so a pending event from ANOTHER group must not
        // survive into this one (it would surface in / clear this group's composer). Drop a stale
        // cross-group event on entry. An event for THIS group (just emitted by our own send path,
        // which then calls loadGroupDetail) is preserved.
        _groupSendEvent.value?.let { pending ->
            if (pending.groupId != groupId) {
                _groupSendEvent.value = null
            }
        }
        viewModelScope.launch {
            _groupDetailState.value = GroupDetailState.Loading

            // ZC-082: a rotation this phone started and did not finish is completed first (the creator's phone only; it
            // sends and pays nothing: the members still owed the key are marked, and the pass below delivers it).
            healInterruptedRotationIfIdle(groupId)

            // First load from stored data (fast)
            noteShownStoreVersion(groupId)
            val group = loadGroup(groupId, detail = true)
            if (group != null) {
                _groupDetailState.value = GroupDetailState.Success(
                    conversation = group,
                    currentUserAddress = _currentUserAddress.value ?: "",
                    zecPriceUsd = _zecPriceUsd.value
                )

                // Then load history from blockchain (may update messages)
                loadGroupMessagesFromHistory(groupId)

                // Refresh the UI with any new messages found
                noteShownStoreVersion(groupId)
                val updatedGroup = loadGroup(groupId, detail = true)
                if (updatedGroup != null) {
                    _groupDetailState.value = GroupDetailState.Success(
                        conversation = updatedGroup,
                        currentUserAddress = _currentUserAddress.value ?: "",
                        zecPriceUsd = _zecPriceUsd.value
                    )
                }

                // ZC-1045, ZC-1046: the owed-key pass and the key-copy repair below sign with this screen's own address. A
                // new screen reads it asynchronously (after a start it waits for the wallet), and both used to return at
                // once, silently, when the open came first; nothing ran them again on that screen. Now such an open leaves
                // them to the moment the address arrives ([observeOwnAddress] runs [runOpenKeyPasses]).
                val passesWaitForAddress = retryOwedKeys && _currentUserAddress.value == null
                if (passesWaitForAddress) openPassesAwaitingAddress[groupId] = afterSend

                // D3 — cheap push-retry trigger: whenever the group is viewed and some member is
                // still owed a rotated key, re-attempt delivery. Idempotent (receiver epoch
                // replay-guard) and admin-gated inside; runs AFTER the UI refresh so it never
                // delays rendering. AUTOMATIC: merely opening the screen asked for no spend, so an
                // on-chain re-send needs the handshake-spend consent (A2).
                if (retryOwedKeys && !passesWaitForAddress && (updatedGroup ?: group).members.any { it.pendingKeyEpoch != null }) {
                    retryPendingGroupKeysInternal(groupId, automatic = true)
                }
                // ZC-420-G: the copies only a public relay took (messages, keys, removals, invites) are confirmed or delivered
                // again over the relays, free; after the owed-key pass, which owns a member owed again. Every plain open.
                if (retryOwedKeys && !passesWaitForAddress) repairUnconfirmedGroupCopies(groupId, raiseAtOpen = !afterSend)
                // Round 8 MA3 (design §4 T4, §6.1): the free member announces (announcer) or the roster query (member).
                announceOnOpen(groupId)
            } else {
                _groupDetailState.value = GroupDetailState.Error("Group not found")
            }
        }
    }

    /**
     * ZC-1045, ZC-1046 — the two passes of a group open that need this screen's own address (the owed-key pass and the
     * relay-copy repair), for an open that came before the address: run once it is known, as the open would have.
     */
    private suspend fun runOpenKeyPasses(
        groupId: String,
        afterSend: Boolean,
    ) {
        val owed = withGroupLock(groupId) { storedRoster(groupId) }.orEmpty().any { it.pendingKeyEpoch != null }
        if (owed) retryPendingGroupKeysInternal(groupId, automatic = true)
        repairUnconfirmedGroupCopies(groupId, raiseAtOpen = !afterSend)
    }

    /**
     * Load group settings for a specific group.
     */
    fun loadGroupSettings(groupId: String) = loadGroupSettings(groupId, repairInvites = true)

    /** [loadGroupSettings]; [repairInvites] false is the reload after the invite repair itself. */
    private fun loadGroupSettings(
        groupId: String,
        repairInvites: Boolean,
    ) {
        viewModelScope.launch {
            _groupSettingsState.value = GroupSettingsState.Loading

            val group = loadGroup(groupId)
            if (group != null) {
                val currentAddress = _currentUserAddress.value ?: ""
                // Robust to self-address drift (#205): trust the persisted creator flag first, fall back
                // to a hash-tolerant self-check for groups created before the flag existed (the stored
                // creatorAddress may be a different representation of our own address).
                val isCreator = zchatPreferences.isGroupSelfCreated(groupId) ||
                    zchatPreferences.isSelfAddress(group.groupInfo.creatorAddress)

                _groupSettingsState.value = GroupSettingsState.Success(
                    groupInfo = group.groupInfo,
                    members = group.members,
                    currentUserAddress = currentAddress,
                    isCreator = isCreator,
                    sharedByCreator = group.sharedByCreator,
                    // Round 8 MA3: the member-row notes of the member announce, on the group's creator.
                    introduce = if (isCreator) runCatching { introduceInfo(groupId, group.groupInfo, group.members) }.getOrNull() else null,
                    // Round 9 R9-5 (REVIEW_R8 F11 = device D-F14): the header's "known members" follows the same
                    // groupMessageCost as the group screen (the creator announced the roster: the whole group).
                    knownOnly = runCatching { groupMessageCost(groupId).knownOnly }.getOrDefault(!isCreator),
                    // GROUP-LABEL: the member rows use the names the group screen's bubbles use.
                    names = groupNames(group.members, emptyList()),
                    // F8-G: the failed key messages (the creator's phone) and message copies, per member row.
                    keyNotDelivered = if (isCreator) keyNotDelivered(groupId, group.members) else emptyMap(),
                    messageNotDelivered = messageNotDelivered(groupId, group.members),
                    // ZC-420-G: the invites only a public relay took ("Invited, not confirmed", with a free Resend).
                    inviteUnconfirmed = if (isCreator) inviteUnconfirmed(groupId, group.members) else emptySet(),
                )
                // ZC-420-G: confirm those invites from their kept wraps (never a new invite), then show what changed.
                if (repairInvites && isCreator && repairUnconfirmedGroupInvitesNow(groupId)) {
                    loadGroupSettings(groupId, repairInvites = false)
                }
            } else {
                _groupSettingsState.value = GroupSettingsState.Error("Group not found")
            }
        }
    }

    // ==========================================
    // CREATE GROUP FLOW
    // ==========================================

    /**
     * ZC-1179: the peers of accepted Open (relay-only) chats, shared by the create-group and the Add-member
     * pickers. Listed: a conversation peer whose relay key routes back to it, not already in [listedCanon]. Not
     * listed: our own address, a peer whose key offer still waits for Accept.
     */
    private fun relayChatPeers(listedCanon: Set<String>): List<Contact> {
        fun relayKeyRoutesTo(addr: String): Boolean {
            val key = zchatPreferences.getPeerNostrPubkey(addr) ?: return false
            val routed = zchatPreferences.findPeerByNostrPubkey(key) ?: return false
            return zchatPreferences.resolvePeerAddress(routed) == addr
        }
        return zchatPreferences
            .getAllPeerToConvIdMappings()
            .keys
            .map { zchatPreferences.resolvePeerAddress(it) }
            .distinct()
            .filter {
                it.startsWith("u1") &&
                    it.trim().lowercase() !in listedCanon &&
                    !zchatPreferences.isSelfAddress(it) &&
                    zchatPreferences.getInboundKexAwaitingAccept(it) == null &&
                    relayKeyRoutesTo(it)
            }.map { addr -> Contact(address = addr, name = zchatPreferences.getDisplayName(addr)) }
    }

    /**
     * Get available contacts for adding to a group.
     */
    fun loadAvailableContacts() {
        viewModelScope.launch {
            // #196: exclude SELF from the saved side too — you are added as creator, so offering your own
            // saved address as an invitee is nonsensical and (pre-fix) fed a self-invite. The invitee set is
            // independently sanitized in createGroup(), but keeping it out of the picker is the clean UX.
            val saved =
                contactBook
                    .getAllContacts()
                    .filterNot { zchatPreferences.isSelfAddress(it.address) }
                    // ZC-1221: one row per canonical address, the first saved entry wins.
                    .distinctBy { it.address.trim().lowercase() }
            // #196: canonicalize saved addresses so a KEX peer that differs only by UA representation
            // (case / unified-address drift) is recognized as already-saved and not surfaced twice.
            val savedCanon = saved.map { it.address.trim().lowercase() }.toSet()
            // #196: ALSO surface KEX'd CONVERSATION peers, not just Address Book entries. A peer you've
            // completed a key exchange with already has the E2E identity key a group needs (each member's
            // group key is ECIES-wrapped to that key), so they are addable even if you never saved them
            // as a contact. Without this the picker showed only seeded/saved contacts — you literally
            // could not add the person you were already chatting with, which blocked all 2-device group
            // testing (the Address Book and the conversation store are SEPARATE). Merge + de-dupe by
            // canonical address; saved contacts win (they carry the user's chosen nickname). Exclude SELF
            // (you are added as creator below — listing yourself as an invitee is nonsensical and would
            // double-add) and resolve UA drift to the canonical peer address before filtering.
            val kexPeers = zchatPreferences.getAllPeerToConvIdMappings().keys
                .map { zchatPreferences.resolvePeerAddress(it) }
                .filter {
                    it.startsWith("u1") &&
                        it.trim().lowercase() !in savedCanon &&
                        !zchatPreferences.isSelfAddress(it) &&
                        zchatPreferences.getE2EPeerPublicKey(it) != null
                }
                .distinct()
                .map { addr ->
                    co.electriccoin.zcash.ui.screen.chat.model.Contact(
                        address = addr,
                        name = zchatPreferences.getDisplayName(addr),
                    )
                }
            // G3 8d (groups SPEC §3.4): ALSO the peers of accepted Open (relay-only)
            // chats. They have no E2E key, so the list above leaves them out, yet their invite is the plaintext relay invite
            // (free, as for a saved contact without a key exchange; the create quote still decides what is paid). Listed:
            // a conversation peer whose relay key routes back to it (findPeerByNostrPubkey), so one key is listed once and a
            // second address a key was bound to is not. Not listed: our own address, a peer whose key offer still waits for
            // Accept, and a message request (it has no chat until accepted).
            val relayPeers = relayChatPeers(savedCanon + kexPeers.map { it.address.trim().lowercase() })
            _createGroupState.value = _createGroupState.value.copy(
                availableContacts = saved + kexPeers + relayPeers,
                contactNamesUnreadable = contactBook.unreadable.value,
            )
        }
    }

    /**
     * Update group name in create flow.
     */
    fun setGroupName(name: String) {
        _createGroupState.value = _createGroupState.value.copy(
            groupName = name,
            error = null
        )
    }

    /**
     * Toggle member selection in create flow.
     */
    fun toggleMemberSelection(address: String) {
        val current = _createGroupState.value.selectedMembers.toMutableList()
        if (current.contains(address)) {
            current.remove(address)
        } else {
            // Limit to 9 members (+ creator = 10 total)
            if (current.size < 9) {
                current.add(address)
            }
        }
        _createGroupState.value = _createGroupState.value.copy(
            selectedMembers = current,
            error = null
        )
    }

    /**
     * Create a new group.
     */
    fun createGroup() {
        val state = _createGroupState.value
        if (!state.isValid) {
            _createGroupState.value = state.copy(error = "Please enter a group name and select members")
            return
        }

        val creatorAddress = _currentUserAddress.value
        if (creatorAddress == null) {
            _createGroupState.value = state.copy(error = "Wallet not ready")
            return
        }
        // R2-2 (b): one invite per member, each on-chain unless the member is reachable over Nostr. Ask first.
        // GPT-6 F2: the quote is of THIS plan (name, invitees, and which invitees go on-chain); Confirm runs it
        // only while it is still the plan on screen, and the invites pay on-chain only for the quoted members.
        val quoted = quoteCreateGroup(state, creatorAddress)
        // B1 (GPT-6 Q3b): the prompt shows this quote's paid invitees; it does not check reachability again.
        askBeforePaying(PaidAction.GROUP_CREATE, QuotedFanOut(quoted.invitees, quoted.paidInvitees)) { confirmCreateGroup(quoted) }
    }

    /** GPT-6 F2 — a new group's plan as quoted: [paidInvitees] are the invitees quoted as on-chain invites. */
    private data class QuotedCreateGroup(
        val creatorAddress: String,
        val groupName: String,
        val invitees: List<String>,
        val paidInvitees: Set<String>,
    )

    private fun quoteCreateGroup(
        state: CreateGroupState,
        creatorAddress: String,
    ): QuotedCreateGroup {
        val invitees =
            state.selectedMembers
                .map { zchatPreferences.resolvePeerAddress(it) }
                .filterNot { zchatPreferences.isSelfAddress(it) || it == creatorAddress }
                .distinct()
        return QuotedCreateGroup(
            creatorAddress = creatorAddress,
            groupName = state.groupName,
            invitees = invitees,
            paidInvitees = invitees.filterNot { reachableFree(it) }.toSet(),
        )
    }

    /**
     * GPT-6 F2 — Confirm on a quoted new group. When the name, the invitees, or the invitees that go on-chain
     * are no longer what was quoted, nothing is sent and the changed plan is quoted again.
     */
    private fun confirmCreateGroup(quoted: QuotedCreateGroup) {
        val state = _createGroupState.value
        val creatorAddress = _currentUserAddress.value
        val now = if (state.isValid && creatorAddress != null) quoteCreateGroup(state, creatorAddress) else null
        if (now == null ||
            now.creatorAddress != quoted.creatorAddress ||
            now.groupName != quoted.groupName ||
            now.invitees != quoted.invitees ||
            !quoted.paidInvitees.containsAll(now.paidInvitees)
        ) {
            Log.w(TAG, "Create group: the plan changed after its cost was quoted — asking again, nothing sent")
            createGroup()
            return
        }
        createGroup(quoted, state)
    }

    /**
     * Creates the group [quoted] describes and sends its invites. [state] is the create screen's state at
     * Confirm; its name and invitees are the quoted ones.
     */
    private fun createGroup(
        quoted: QuotedCreateGroup,
        state: CreateGroupState,
    ) {
        val creatorAddress = quoted.creatorAddress
        viewModelScope.launch {
            _createGroupState.value = state.copy(isCreating = true, error = null)

            try {
                // Generate group ID
                val groupId = GroupInfo.generateGroupId(creatorAddress)

                // Generate group key
                val groupKey = ZMSGGroupProtocol.generateGroupKey()
                val encodedGroupKey = ZMSGGroupProtocol.encodeGroupKey(groupKey)

                // Create group info
                val groupInfo = GroupInfo(
                    groupId = groupId,
                    // #195 bound the unbounded name at the source so the stored name matches what goes
                    // on-chain in the invite (also defensively capped in createGroupInviteCompact).
                    name = ZMSGGroupProtocol.boundGroupName(state.groupName),
                    creatorAddress = creatorAddress,
                    createdAt = Instant.now(),
                    adminPolicy = AdminPolicy.CREATOR_ONLY,
                    currentEpoch = 0,
                    groupKey = encodedGroupKey,
                    isActive = true
                )

                // Create member list (creator + selected members).
                // #196 defense-in-depth: resolve any UA-drifted selection to its canonical peer address
                // and strip self / the creator from the invitee set so the creator can never be added
                // twice (once as creator, once as a stale-cased invitee) and a duplicate member row
                // cannot poison the group roster / per-member key wrapping.
                // GPT-6 F2: the quoted invitees (the same sanitizing, frozen when the cost was quoted).
                val allMemberAddresses = (listOf(creatorAddress) + quoted.invitees).distinct()
                val members = allMemberAddresses.mapIndexed { index, address ->
                    GroupMember(
                        address = address,
                        publicKey = if (address == creatorAddress) {
                            // Creator E2E key, keyed by groupId (D1-recovery Stage 3a: seed-derived
                            // + V2 + provenance via the provider). groupId is freshly generated
                            // above so no stored key can exist; the provider's never-overwrite
                            // guard still holds if that ever changes.
                            seedKeyProvider.getOrCreateKeyPair(groupId).publicKey
                        } else null,
                        joinedAt = Instant.now(),
                        status = if (address == creatorAddress) MemberStatus.ACTIVE else MemberStatus.INVITED,
                        isAdmin = address == creatorAddress,
                        nickname = contactBook.getContact(address)?.name,
                        // P1.4: flips to SENT/FAILED as each GROUP_INVITE resolves in the loop below.
                        inviteStatus = if (address == creatorAddress) null else InviteStatus.INVITE_PENDING
                    )
                }

                // Save group info and members (KI-22: under the group lock, like every roster write)
                withGroupLock(groupId) {
                    zchatPreferences.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(groupInfo))
                    zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(members))
                    zchatPreferences.saveGroupKey(groupId, 0, encodedGroupKey)
                    zchatPreferences.setGroupKeyEpoch(groupId, 0)
                }
                // Persist that WE are the creator/admin so the role survives a self-address drift (the
                // creatorAddress string can later differ from our live address across reinstalls).
                zchatPreferences.setGroupSelfCreated(groupId, true)

                Log.d(TAG, "Group created: $groupId with ${members.size} members")

                // Send GROUP_INVITE to all invited members. Each invite is a separate shielded tx, so
                // the single-note constraint serializes them: invite #2 can't spend until #1's change
                // confirms (~a block later). The old loop's fixed 500 ms gap was FAR shorter than block
                // time, so every invite after the first hit transient InsufficientFunds and was SILENTLY
                // dropped (#199) — multi-member groups never actually invited anyone but the first.
                // sendInviteWithRetry waits for the next block on that transient case and retries;
                // genuinely-failed members are collected + surfaced instead of vanishing.
                // #196: invite the SANITIZED roster (allMemberAddresses, minus the creator) — NOT the raw
                // state.selectedMembers. Iterating the raw selection would (a) send a real shielded invite tx
                // to OURSELVES if our own address slipped into the selection (it is not a roster member), and
                // (b) send DUPLICATE invites — and duplicate on-chain txs / retries — for two selections that
                // canonicalize to the same peer under UA-representation drift. Both waste ZEC. The roster
                // (`members`) was already built from allMemberAddresses, so this keeps invites and roster in
                // lockstep.
                val inviteeAddresses = allMemberAddresses.filterNot { it == creatorAddress }
                val failedInvites = mutableListOf<String>()
                val maybeSentInvites = mutableListOf<String>()
                // Round 8 D8 (F3): the first refusal no balance can fix, named on the failed-invite banner.
                var inviteRefusal: String? = null
                for (memberAddress in inviteeAddresses) {
                    // ZC-897: held until the contact's key exchange is accepted; nothing is sent or paid for it.
                    if (inviteWaitsForAccept(memberAddress)) {
                        Log.w(TAG, "GROUP_INVITE to ${memberAddress.redactAddress()} held: its key exchange waits for Accept")
                        setMemberInviteStatus(groupId, memberAddress, InviteStatus.FAILED)
                        failedInvites.add(memberAddress)
                        inviteRefusal = inviteRefusal
                            ?: co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.GROUP_INVITE_WAITS_FOR_ACCEPT
                        continue
                    }
                    Log.d(TAG, "Sending GROUP_INVITE to ${memberAddress.redactAddress()}")

                    val inviteMemo = buildCompactInviteMemo(
                        groupId = groupId,
                        groupName = state.groupName,
                        inviterAddress = creatorAddress,
                        keyEpoch = 0,
                        encodedGroupKey = encodedGroupKey,
                        memberAddress = memberAddress
                    )

                    // GPT-6 F2: a member quoted as free is not paid on-chain under this quote when the free
                    // delivery fails; the invite is shown as failed, with Resend.
                    val outcome =
                        sendGroupMemoWithRetry(
                            memberAddress,
                            inviteMemo,
                            creatorAddress,
                            onRefused = { inviteRefusal = inviteRefusal ?: it },
                            // ZC-420-G: the invite among the relay-copy marks, under the row's address.
                            relaySlot = RelayCopySlots.groupInvite(groupId, memberAddress),
                            // F8-G: the invite's attempt in the ledger, and ZC-055: only while the member is still invited.
                            attempt = GroupSendAttemptRules.invite(groupId, zchatPreferences.resolvePeerAddress(memberAddress), 0),
                            stillWanted = { stillInvited(groupId, memberAddress) },
                        ) {
                            memberAddress in quoted.paidInvitees
                        }
                    // P1.4: persist the per-member outcome so group settings can show it + offer Resend.
                    // F8-G: an earlier attempt of this invite is unresolved (PENDING): the row keeps what it had.
                    outcome.inviteStatus?.let { setMemberInviteStatus(groupId, memberAddress, it) }
                    when (outcome) {
                        GroupMemoOutcome.NOTHING_SENT -> failedInvites.add(memberAddress)
                        GroupMemoOutcome.MAYBE_SENT -> maybeSentInvites.add(memberAddress)
                        GroupMemoOutcome.SENT -> Unit
                        GroupMemoOutcome.PENDING -> Unit
                    }
                }

                if (maybeSentInvites.isNotEmpty()) {
                    // C4 (B1 review item 3): one banner for a mixed outcome — the invites that may have been sent
                    // and, in the same line, the failed ones (which keep their Resend in Group Settings).
                    _groupSendEvent.value =
                        GroupSendResult.MaybeSent(
                            groupId,
                            GroupSendResult.MaybeSentKind.INVITE,
                            members = maybeSentInvites.size,
                            notSent = failedInvites.size,
                        )
                } else if (failedInvites.isNotEmpty()) {
                    // P0.1: surfaced as a banner on the group screen the user lands on (the old
                    // Toast-only surfacing died with this screen).
                    _groupSendEvent.value = GroupSendResult.InviteFailed(groupId, failedInvites.toList(), inviteRefusal)
                }

                _createGroupState.value = CreateGroupState(
                    createdGroupId = groupId,
                    failedInvites = failedInvites
                )

                // Refresh groups list
                loadGroups()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create group", e)
                _createGroupState.value = state.copy(
                    isCreating = false,
                    error = e.toZchatUserMessage("Failed to create group. Please try again.")
                )
            }
        }
    }

    /**
     * Derive the symmetric key shared with [peerAddress] from a completed KEX, mirroring
     * ChatViewModel.getE2ESharedKey. Used to wrap the group key in a compact invite. Returns null
     * when no key exchange has completed (caller then falls back to a plaintext-key invite).
     */
    private fun deriveSessionKey(peerAddress: String): ByteArray? {
        val ourPrivateKey = zchatPreferences.getE2EPrivateKey(peerAddress) ?: return null
        val peerPublicKey = zchatPreferences.getE2EPeerPublicKey(peerAddress) ?: return null
        val keyVersion = E2EKeyVersion.fromValue(zchatPreferences.getE2EKeyVersion(peerAddress))
        return try {
            E2EEncryption.deriveSharedSecret(ourPrivateKey, peerPublicKey, keyVersion)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive session key for ${peerAddress.redactAddress()}", e)
            null
        }
    }

    /**
     * ZC-897 (and its review, 19 Oct, finding 1) — true while [memberAddress]'s first key exchange waits for our Accept.
     * That contact never got our key, so it cannot open a session-wrapped invite, and the relay key bound to its address
     * may be a stranger's (anyone can send a key exchange claiming it), so no plain-key form may go there either: the
     * invite is held, nothing is sent or paid, and the user is told to Accept first and then Resend.
     */
    private fun inviteWaitsForAccept(memberAddress: String): Boolean =
        zchatPreferences.getInboundKexAwaitingAccept(memberAddress) != null ||
            zchatPreferences.getInboundKexAwaitingAccept(zchatPreferences.resolvePeerAddress(memberAddress)) != null

    /** ZC-897 — the banner of an invite held by [inviteWaitsForAccept]: nothing was sent or paid. */
    private fun inviteHeldForAccept(
        groupId: String,
        memberAddress: String,
    ): GroupSendResult.InviteFailed =
        GroupSendResult.InviteFailed(
            groupId,
            listOf(memberAddress),
            co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.GROUP_INVITE_WAITS_FOR_ACCEPT,
        )

    /**
     * Build the COMPACT GROUP_INVITE memo for [memberAddress] — fits Zcash's 512-byte memo. The
     * legacy invite embedded the full roster + a fat ECIES blob and overflowed for ANY group size
     * (#194), so groups never formed. Prefer wrapping the group key under the existing authenticated
     * KEX session (small + authenticated); fall back to a plaintext key only when no KEX exists. The
     * roster is NOT shipped — peers are discovered lazily as they post (see
     * ChatViewModel.addOrActivateGroupMember). Shared by the create-group loop and the P1.4
     * single-member Resend path.
     */
    private fun buildCompactInviteMemo(
        groupId: String,
        groupName: String,
        inviterAddress: String,
        keyEpoch: Int,
        encodedGroupKey: String,
        memberAddress: String
    ): String {
        // ZC-897: an invite to a contact whose first key exchange still waits for our Accept is never built here: the
        // callers hold it ([inviteWaitsForAccept]).
        val sessionKey = deriveSessionKey(memberAddress)
        return if (sessionKey != null) {
            val wrappedKey = E2EEncryption.encrypt(encodedGroupKey, sessionKey)
            Log.d(TAG, "Compact invite (session-wrapped key) for ${memberAddress.redactAddress()}")
            ZMSGGroupProtocol.createGroupInviteCompact(
                groupId = groupId,
                groupName = groupName,
                inviterAddress = inviterAddress,
                keyEpoch = keyEpoch,
                encryptedGroupKey = wrappedKey,
                isSessionEncrypted = true
            )
        } else {
            // Fallback: plaintext group key (backward compat, no prior KEX)
            Log.w(TAG, "No KEX with ${memberAddress.redactAddress()} - compact invite with plaintext key")
            ZMSGGroupProtocol.createGroupInviteCompact(
                groupId = groupId,
                groupName = groupName,
                inviterAddress = inviterAddress,
                keyEpoch = keyEpoch,
                encryptedGroupKey = encodedGroupKey,
                isSessionEncrypted = false
            )
        }
    }

    /**
     * P1.4 — re-run the single-member invite path for a member whose invite FAILED (from group
     * settings). Sends a fresh compact GROUP_INVITE carrying the CURRENT epoch key (a late joiner
     * needs the newest key, not the one from group creation) and updates the member's persisted
     * [InviteStatus] so the settings badge tracks the outcome.
     */
    fun resendInvite(groupId: String, memberAddress: String) {
        if (_currentUserAddress.value == null) return
        // ZC-757 (G1): as addMemberToGroup.
        rebuiltRefusal(groupId, invitation = true)?.let {
            _groupSendEvent.value = it
            return
        }
        // ZC-897: as addMemberToGroup.
        if (inviteWaitsForAccept(memberAddress)) {
            _groupSendEvent.value = inviteHeldForAccept(groupId, memberAddress)
            return
        }
        // ZC-420-G: an invite only a public relay took ("Invited, not confirmed") is sent again over the relays, free,
        // with no price question; a row with no such mark takes the quoted Resend below, as before.
        if (inviteRelayUnconfirmed(groupId, memberAddress)) {
            resendUnconfirmedInvite(groupId, memberAddress)
            return
        }
        // R4-2: quote this member, then re-send under that quote only — a member quoted as free (and one
        // the prompt never mentioned, because nothing was quoted) is never paid for on-chain.
        // F8-G (G4-1): the failed attempt this question is raised for, if the ledger still holds one (the marker of the
        // invite that never reached the chain): the Resend then pays against exactly that state, once.
        val retryOf = inviteOfferStartedAt(groupId, memberAddress)
        // F8G-X2 (ZC-055): what the row is at the tap; Pay sends only while it is still that.
        val statusAtTap = memberStatusNow(groupId, memberAddress)
        askForQuotedPlan(PaidAction.GROUP_RESEND_INVITE, { listOf(memberAddress) }) { quoted ->
            resendInvite(groupId, memberAddress, quoted, retryOf, statusAtTap)
        }
    }

    /** F8-G: the marker of the failed (never mined) invite of [memberAddress] at the current key number, or null. */
    private fun inviteOfferStartedAt(
        groupId: String,
        memberAddress: String,
    ): Long? =
        runCatching {
            sendAttempts?.ledger?.offerStartedAt(
                GroupSendAttemptRules
                    .invite(groupId, zchatPreferences.resolvePeerAddress(memberAddress), zchatPreferences.getGroupKeyEpoch(groupId))
                    .key,
            )
        }.getOrNull()

    /** ZC-055: [memberAddress] still has a row that is INVITED (not joined, not left, not removed). */
    private fun stillInvited(
        groupId: String,
        memberAddress: String,
    ): Boolean =
        withGroupLock(groupId) {
            val canon = zchatPreferences.resolvePeerAddress(memberAddress)
            storedRoster(groupId).orEmpty().any {
                zchatPreferences.resolvePeerAddress(it.address) == canon && it.status == MemberStatus.INVITED
            }
        }

    /**
     * ZC-055: what [memberAddress] is in the group now, over every row of that member: INVITED if a row is, else ACTIVE
     * if a row is, else the first row's status; null when it has no row.
     */
    private fun memberStatusNow(
        groupId: String,
        memberAddress: String,
    ): MemberStatus? =
        withGroupLock(groupId) {
            val canon = zchatPreferences.resolvePeerAddress(memberAddress)
            val statuses =
                storedRoster(groupId).orEmpty().filter { zchatPreferences.resolvePeerAddress(it.address) == canon }.map { it.status }
            listOf(MemberStatus.INVITED, MemberStatus.ACTIVE).firstOrNull { it in statuses } ?: statuses.firstOrNull()
        }

    /** ZC-055: [memberAddress] still has a row that is ACTIVE (a message copy is for members of the group only). */
    private fun stillActive(
        groupId: String,
        memberAddress: String,
    ): Boolean =
        withGroupLock(groupId) {
            val canon = zchatPreferences.resolvePeerAddress(memberAddress)
            storedRoster(groupId).orEmpty().any {
                zchatPreferences.resolvePeerAddress(it.address) == canon && it.status == MemberStatus.ACTIVE
            }
        }

    /**
     * F8-G: whether an on-chain attempt under [tag] would be refused by the ledger now (an earlier attempt of the key
     * is unresolved, mined and not yet deep, or failed and not the state this tap was confirmed for). It only reads;
     * a ledger that cannot be read answers true (nothing is paid without it).
     */
    private fun ledgerHolds(tag: SendAttemptTag): Boolean {
        val ledger = sendAttempts?.ledger ?: return false
        return runCatching { ledger.gate(tag.key, tag.retryOf) != SendAttemptGate.Proceed(tag.retryOf) }.getOrDefault(true)
    }

    /**
     * R4-2 — the quoted resend: [quoted] decides whether this member may be paid for on-chain. [statusAtTap] is what the
     * member's row was when Resend was tapped (F8G-X2).
     */
    private fun resendInvite(
        groupId: String,
        memberAddress: String,
        quoted: QuotedFanOut,
        retryOf: Long?,
        statusAtTap: MemberStatus?,
    ) {
        val inviterAddress = _currentUserAddress.value ?: return
        // SF-3 — early-return if a resend for THIS member is already in flight, so a double-tap can't
        // fire two on-chain invite txs. Add returns false if the key was already present.
        val inFlightKey = "$groupId|$memberAddress"
        if (!resendInvitesInFlight.add(inFlightKey)) {
            Log.d(TAG, "resendInvite: already in flight for ${memberAddress.redactAddress()} — ignoring")
            return
        }
        viewModelScope.launch {
            try {
                // KI-22: under the group's change lock from the epoch read through the final badge write, so a kick or a
                // rotation from another screen waits instead of leaving this member with a stale key.
                withGroupChange(groupId) {
                    val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                    if (info == null) {
                        Log.e(TAG, "resendInvite: group $groupId not found")
                        return@launch
                    }
                    // F8G-X2: read at Pay, under the change lock, against the row at the tap (the Resend is offered on an
                    // INVITED row only). A member removed, gone or joined while the price question was open is not sent
                    // an invite, and so never the group's current key; a LEFT row or no row never is. Nothing is
                    // written or paid; the screen shows the row as it is now.
                    val statusAtPay = memberStatusNow(groupId, memberAddress)
                    if (statusAtPay != statusAtTap || statusAtPay == null || statusAtPay == MemberStatus.LEFT) {
                        Log.w(TAG, "resendInvite: the row of ${memberAddress.redactAddress()} changed or is gone; nothing sent")
                        loadGroupSettings(groupId)
                        return@launch
                    }
                    val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
                    val encodedGroupKey = zchatPreferences.getGroupKey(groupId, keyEpoch)
                    if (encodedGroupKey == null) {
                        Log.e(TAG, "resendInvite: no group key for $groupId epoch $keyEpoch")
                        _groupSendEvent.value = GroupSendResult.InviteFailed(groupId, listOf(memberAddress))
                        return@launch
                    }
                    // F8-G: the invite's attempt in the ledger. When an earlier attempt of this invite stands (on its way,
                    // mined, or failed with another marker than the one this question was raised for) and the member
                    // is not reachable free, nothing is written, sent or paid: the row keeps its badge.
                    val tag =
                        GroupSendAttemptRules.invite(groupId, zchatPreferences.resolvePeerAddress(memberAddress), keyEpoch, retryOf)
                    if (ledgerHolds(tag) && !reachableFree(memberAddress)) {
                        Log.w(TAG, "resendInvite: an earlier attempt of this invite is unresolved; nothing sent")
                        loadGroupSettings(groupId)
                        return@launch
                    }
                    val statusBefore =
                        withGroupLock(groupId) { storedRoster(groupId).orEmpty().firstOrNull { it.address == memberAddress }?.inviteStatus }
                    var refusal: String? = null
                    // F8-G: this invite's Resend is running, for the whole process (the history pass leaves its row and
                    // its entry alone meanwhile); removed whatever the Resend did.
                    groupResendsInFlight.add(tag.key)
                    val outcome =
                        try {
                            setMemberInviteStatus(groupId, memberAddress, InviteStatus.INVITE_PENDING)
                            loadGroupSettings(groupId) // show the in-flight badge immediately
                            val inviteMemo =
                                buildCompactInviteMemo(
                                    groupId = groupId,
                                    groupName = info.name,
                                    inviterAddress = inviterAddress,
                                    keyEpoch = keyEpoch,
                                    encodedGroupKey = encodedGroupKey,
                                    memberAddress = memberAddress,
                                )
                            sendGroupMemoWithRetry(
                                memberAddress,
                                inviteMemo,
                                inviterAddress,
                                onRefused = { refusal = refusal ?: it },
                                // ZC-420-G: the invite among the relay-copy marks, under the row's address.
                                relaySlot = RelayCopySlots.groupInvite(groupId, memberAddress),
                                attempt = tag,
                                // ZC-055: only while the member's row is what it was at the tap (an invited member who
                                // joined, left or was removed while this invite waited is not sent it).
                                stillWanted = { memberStatusNow(groupId, memberAddress) == statusAtTap },
                            ) {
                                quoted.mayPayFor(memberAddress)
                            }
                        } finally {
                            groupResendsInFlight.remove(tag.key)
                        }
                    // F8-G: PENDING (another attempt took this invite meanwhile) puts back the badge the row had.
                    setMemberInviteStatus(
                        groupId,
                        memberAddress,
                        outcome.inviteStatus ?: statusBefore ?: InviteStatus.SENT,
                        inviteEpoch = keyEpoch.takeIf { outcome.inviteStatus != null },
                    )
                    // ZC-757 (G6 step 2): as addMemberToGroup.
                    if (outcome == GroupMemoOutcome.SENT || outcome == GroupMemoOutcome.MAYBE_SENT) {
                        markRebuiltProgress(groupId, false, true)
                    }
                    reportInviteOutcome(groupId, memberAddress, outcome, refusal)
                    loadGroupSettings(groupId)
                }
            } finally {
                resendInvitesInFlight.remove(inFlightKey)
            }
        }
    }

    /**
     * P1.4 — persist [status] for one roster member (zchat_group_members JSON). KI-22: a fresh read-modify-write under
     * the group lock.
     *
     * KI-22 safety net: [inviteEpoch] is the key epoch the invite carried. When the invite is SENT (it reached, or may
     * have reached, the member, who then holds that key) and the group's epoch is above it, the member is marked owed
     * the current key (pendingKeyEpoch), so the owed-key pass delivers it. The add and the re-send hold the group's
     * change lock ([withGroupChange]), so the epoch should never move under them.
     */
    private fun setMemberInviteStatus(
        groupId: String,
        memberAddress: String,
        status: InviteStatus,
        inviteEpoch: Int? = null,
    ) {
        withGroupLock(groupId) {
            val members = storedRoster(groupId) ?: return
            val epochNow = zchatPreferences.getGroupKeyEpoch(groupId)
            val owesNewerKey = status == InviteStatus.SENT && inviteEpoch != null && epochNow > inviteEpoch
            if (owesNewerKey) Log.w(TAG, "Invite to ${memberAddress.redactAddress()} carried epoch $inviteEpoch; now $epochNow — owed")
            val updated =
                members.map {
                    when {
                        it.address != memberAddress -> it
                        owesNewerKey -> it.copy(inviteStatus = status, pendingKeyEpoch = epochNow, pendingKeyTxIds = null)
                        else -> it.copy(inviteStatus = status)
                    }
                }
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
        }
    }

    /** True iff we are the admin (creator) of [groupId] — mirrors the kick/rotate gate (#204). */
    private fun isGroupAdmin(groupId: String): Boolean =
        zchatPreferences.isGroupSelfCreated(groupId) ||
            (
                zchatPreferences.getGroupInfo(groupId)
                    ?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                    ?.creatorAddress
                    ?.let { zchatPreferences.isSelfAddress(it) } ?: false
            )

    /**
     * Populate [addMemberCandidates] for the Add-member picker on an EXISTING group: saved contacts +
     * KEX'd conversation peers (same set the create-group picker uses), MINUS anyone already ACTIVE or
     * INVITED in this group. A previously-removed (LEFT) member is NOT excluded — they can be re-added.
     */
    fun loadAddMemberCandidates(groupId: String) {
        // Clear stale candidates so re-opening the picker (or a different group) never flashes a
        // previous group's list before the fresh query resolves.
        _addMemberCandidates.value = emptyList()
        viewModelScope.launch {
            val saved =
                contactBook
                    .getAllContacts()
                    .filterNot { zchatPreferences.isSelfAddress(it.address) }
                    // ZC-1221: one row per canonical address, the first saved entry wins.
                    .distinctBy { it.address.trim().lowercase() }
            _contactNamesUnreadable.value = contactBook.unreadable.value
            val savedCanon = saved.map { it.address.trim().lowercase() }.toSet()
            val kexPeers = zchatPreferences.getAllPeerToConvIdMappings().keys
                .map { zchatPreferences.resolvePeerAddress(it) }
                .filter {
                    it.startsWith("u1") &&
                        it.trim().lowercase() !in savedCanon &&
                        !zchatPreferences.isSelfAddress(it) &&
                        zchatPreferences.getE2EPeerPublicKey(it) != null
                }
                .distinct()
                .map { Contact(address = it, name = zchatPreferences.getDisplayName(it)) }

            val members = zchatPreferences.getGroupMembers(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) } ?: emptyList()
            // Block anyone already in the group as ACTIVE or INVITED (LEFT members are re-addable).
            val blockedCanon = members
                .filter { it.status == MemberStatus.ACTIVE || it.status == MemberStatus.INVITED }
                .map { zchatPreferences.resolvePeerAddress(it.address) }
                .toSet()

            val relayPeers = relayChatPeers(savedCanon + kexPeers.map { it.address.trim().lowercase() })
            _addMemberCandidates.value =
                (saved + kexPeers + relayPeers)
                    .filterNot { zchatPreferences.resolvePeerAddress(it.address) in blockedCanon }
        }
    }

    /**
     * ADMIN-ONLY: add [memberAddress] to an EXISTING group. Adds them to the roster as INVITED and sends
     * a compact GROUP_INVITE carrying the CURRENT epoch key (free over NOSTR when they're a known peer,
     * on-chain fallback otherwise — see sendGroupMemoWithRetry). No-op if we aren't the admin or the
     * member is already ACTIVE/INVITED. The invite badge tracks the outcome (Inviting… → Invited/Failed).
     */
    fun addMemberToGroup(groupId: String, memberAddress: String) {
        if (_currentUserAddress.value == null) return
        // ZC-757 (G1): a rebuilt group invites nobody before its new key (an invitation would carry an old key).
        rebuiltRefusal(groupId, invitation = true)?.let {
            _groupSendEvent.value = it
            return
        }
        // ZC-897: held until the contact's key exchange is accepted; no cost question, nothing sent or paid.
        if (inviteWaitsForAccept(memberAddress)) {
            _groupSendEvent.value = inviteHeldForAccept(groupId, memberAddress)
            return
        }
        // R4-2: the invite runs under the quote it was shown with — a member quoted as free is never paid for.
        askForQuotedPlan(PaidAction.GROUP_ADD_MEMBER, { listOf(memberAddress) }) { quoted ->
            addMemberToGroup(groupId, memberAddress, quoted)
        }
    }

    /** R4-2 — the quoted add: [quoted] decides whether the new member may be paid for on-chain. */
    private fun addMemberToGroup(
        groupId: String,
        memberAddress: String,
        quoted: QuotedFanOut,
    ) {
        val inviterAddress = _currentUserAddress.value ?: return
        viewModelScope.launch {
            if (!isGroupAdmin(groupId)) {
                Log.w(TAG, "addMemberToGroup: only the group admin may add members — aborting")
                return@launch
            }
            // KI-22: the group's change lock from the roster and epoch read through the final invite-status write, so a
            // kick or a rotation from another screen waits and then keys the new member (a delivered invitee holds the
            // key), instead of leaving it with the key this invite carries.
            withGroupChange(groupId) { addMemberToGroup(groupId, memberAddress, inviterAddress, quoted) }
        }
    }

    /**
     * KI-22 — the quoted add's body, run under the group's change lock. Same name as its caller on purpose: the paid
     * group memo it sends stays listed under addMemberToGroup (PaidSendCallSiteLockTest).
     */
    private suspend fun addMemberToGroup(
        groupId: String,
        memberAddress: String,
        inviterAddress: String,
        quoted: QuotedFanOut,
    ) {
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return
        val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
        val encodedGroupKey =
            zchatPreferences.getGroupKey(groupId, keyEpoch) ?: run {
                Log.e(TAG, "addMemberToGroup: no group key for $groupId epoch $keyEpoch")
                return
            }

        val canon = zchatPreferences.resolvePeerAddress(memberAddress)
        // Add (or re-activate a LEFT row) as INVITED so the roster shows them immediately as pending: a fresh
        // read-modify-write under the group lock (KI-22). The stored row's address; null = already ACTIVE / INVITED.
        val rowAddress: String? =
            withGroupLock(groupId) {
                val members = storedRoster(groupId).orEmpty()
                if (members.any {
                        zchatPreferences.resolvePeerAddress(it.address) == canon &&
                            (it.status == MemberStatus.ACTIVE || it.status == MemberStatus.INVITED)
                    }
                ) {
                    Log.d(TAG, "addMemberToGroup: ${memberAddress.redactAddress()} already active/invited")
                    null
                } else {
                    val row = members.find { zchatPreferences.resolvePeerAddress(it.address) == canon }
                    val updatedMembers =
                        if (row != null) {
                            members.map {
                                if (it.address == row.address) {
                                    it.copy(status = MemberStatus.INVITED, inviteStatus = InviteStatus.INVITE_PENDING)
                                } else {
                                    it
                                }
                            }
                        } else {
                            members +
                                GroupMember(
                                    address = memberAddress,
                                    publicKey = null,
                                    joinedAt = Instant.now(),
                                    status = MemberStatus.INVITED,
                                    isAdmin = false,
                                    nickname = contactBook.getContact(memberAddress)?.name,
                                    inviteStatus = InviteStatus.INVITE_PENDING,
                                )
                        }
                    zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updatedMembers))
                    row?.address ?: memberAddress
                }
            }
        loadGroupSettings(groupId)
        if (rowAddress == null) return

        val inviteMemo =
            buildCompactInviteMemo(
                groupId = groupId,
                groupName = info.name,
                inviterAddress = inviterAddress,
                keyEpoch = keyEpoch,
                encodedGroupKey = encodedGroupKey,
                memberAddress = memberAddress,
            )
        var refusal: String? = null
        val outcome =
            sendGroupMemoWithRetry(
                memberAddress,
                inviteMemo,
                inviterAddress,
                onRefused = { refusal = refusal ?: it },
                // ZC-420-G: the invite among the relay-copy marks, under the stored row's address.
                relaySlot = RelayCopySlots.groupInvite(groupId, rowAddress),
                // F8-G: the invite's attempt in the ledger, and ZC-055: only while the member is still invited.
                attempt = GroupSendAttemptRules.invite(groupId, canon, keyEpoch),
                stillWanted = { stillInvited(groupId, rowAddress) },
            ) {
                quoted.mayPayFor(memberAddress)
            }
        // F8-G: PENDING (an earlier invite of this key to this member is unresolved, e.g. the member was removed and is
        // added again while it is still on its way): the earlier attempt owns the member, shown as invited; it is judged
        // from the chain and shows "Invite failed" with Resend if it never arrives.
        // Write the terminal badge on the ACTUAL stored roster row. For a re-added LEFT member the row
        // is keyed on the canonicalized stored address ([rowAddress]), which can differ from the raw picked
        // address — using the raw address would match nothing and leave the badge stuck on "Inviting…".
        // KI-22: with the epoch the invite carried, so a member whose key is already stale is marked owed the current one.
        setMemberInviteStatus(groupId, rowAddress, outcome.inviteStatus ?: InviteStatus.SENT, inviteEpoch = keyEpoch)
        // ZC-757 (G6 step 2): an invitation that went (or may have gone) out carries the new key to a member.
        if (outcome == GroupMemoOutcome.SENT || outcome == GroupMemoOutcome.MAYBE_SENT) markRebuiltProgress(groupId, false, true)
        reportInviteOutcome(groupId, memberAddress, outcome, refusal)
        loadGroupSettings(groupId)
    }

    /**
     * Send one per-member group memo (invite / signed kick / signed key-rotation), retrying through
     * the single-note serialization that makes back-to-back sends fail with TRANSIENT insufficient
     * funds (the previous send's change hasn't confirmed yet). On that case we wait for the next block
     * (the change to mature) and retry, up to [MAX_INVITE_RETRIES]. A GENUINE shortfall ("add ZEC") is
     * NOT retried — waiting can't fix it.
     *
     * [mayPayOnChain] is asked before every on-chain attempt, including each one after a block wait; false
     * sends nothing further and returns [GroupMemoOutcome.NOTHING_SENT]. The free NOSTR delivery is always
     * tried first.
     *
     * B1 (GPT-6 Q3): a failure is [GroupMemoOutcome.NOTHING_SENT] only when it proves no transaction exists
     * that could still be broadcast. Every other failure — above all a SubmitRejectedException whose transaction
     * the SDK stored and may still broadcast — is [GroupMemoOutcome.MAYBE_SENT], which no caller may pay for again.
     *
     * C1 (B1 review item 1): the proof is [memoFailureProvesNothingSent], keyed on whether the use case reached
     * its submit (`onSubmitStarting`). A refusal before it (sealed Orchard, the ZC-102 Keystone refusal, a public
     * crossing, …) stored nothing and is NOTHING_SENT; after it, only a rejection that stored nothing is, also as
     * the cause of the use case's shortfall notice (D1); a shortfall alone is not trusted (nor retried). [onMaybeSent]
     * is handed the ids of the transactions the SDK stored (possibly none known) when the outcome is MAYBE_SENT.
     *
     * Round 8 D8 (REVIEW_7C F3): [onRefused] is handed the sentence of a refusal no balance can fix ([groupSendRefusal])
     * when the outcome is NOTHING_SENT because of one, so the banner names it instead of "check your balance".
     *
     * Round 8 D11 (G4): [beforeSubmit] runs right before an on-chain attempt is handed to the SDK, after everything that
     * can refuse it without storing a transaction (never for the free NOSTR delivery). If it throws, that attempt is
     * not submitted and counts as nothing sent.
     *
     * Round 8 MA-V3 (VERIFIED_MA_FINDINGS #3): [onRelayDelivered] runs when the outcome is SENT because the memo went
     * out free over the relays (no on-chain transaction), so a caller that records the send can tell the two apart.
     *
     * F8-G (ZC-112): [attempt] is what this memo is in the send-attempt ledger (null, or no ledger: nothing is recorded,
     * as before). The ledger is asked inside the on-chain loop only, after the free relay route and the caller's own
     * checks and before the proposal: an earlier attempt of the same key that is unresolved, mined and not yet deep, or
     * proven not sent and waiting for the user's Resend answers [GroupMemoOutcome.PENDING] and nothing is built or paid.
     * The attempt is written before the SDK is asked and its ids before the first submit; when either write fails,
     * nothing is submitted. A free relay delivery of a tagged memo settles a failed attempt of its key.
     *
     * ZC-055: [stillWanted] is asked once after the debug hold and before the relay copy, and before every on-chain
     * attempt (also each one after a block wait): false (the member left, was removed, or joined meanwhile) sends
     * nothing further and returns NOTHING_SENT.
     *
     * ZC-420-G: [relaySlot] names this copy among the relay-copy marks (NostrChatBridge), with [relayExtra] as what the
     * mark remembers (a key number, a removed address). Right before the relay step, once nothing above can end the send,
     * the slot's old mark is forgotten: this copy replaces the one it stood for. The relay step marks this copy when only
     * a public relay took it; a group open delivers it again, free. null: no mark (today's behaviour).
     */
    private suspend fun sendGroupMemoWithRetry(
        memberAddress: String,
        inviteMemo: String,
        creatorAddress: String,
        onMaybeSent: (storedTxIds: List<String>) -> Unit = {},
        onRefused: (refusal: String) -> Unit = {},
        onRelayDelivered: () -> Unit = {},
        relaySlot: String? = null,
        relayExtra: String = "",
        // D11 (G4): before [mayPayOnChain], which several callers pass as a trailing lambda and must stay last.
        beforeSubmit: (() -> Unit)? = null,
        attempt: SendAttemptTag? = null,
        stillWanted: () -> Boolean = { true },
        // ZC-972: runs when the free relay route did not deliver and [mayPayOnChain] refuses the on-chain copy.
        onNotPaidOnChain: () -> Unit = {},
        // ZC-1251: an automatic send's limit; a build above it pays nothing.
        priceLimit: KexAnswerPrice? = null,
        mayPayOnChain: () -> Boolean = { true },
    ): GroupMemoOutcome {
        // F0 debug seam (KI-22's two-screens phone check): hold every group memo send so a rotation stays in flight
        // long enough to start a second change from another screen. Never armed in a release build (FaultSeams).
        FaultSeams.delayIfArmed(FaultSeam.GROUP_SEND_DELAY_MS)
        // T1b-1 §2 (K0 §4): with a Keystone account selected the chat is closed on every transport. A group memo carries
        // our address, so it goes neither over the relays nor on-chain: nothing is published, built or paid, and the
        // caller's banner names the Keystone refusal.
        if (keystoneAccountSelected()) {
            Log.w(TAG, "Group memo to ${memberAddress.redactAddress()} refused — a Keystone account is selected")
            onRefused(co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.GROUP_KEYSTONE_REFUSED)
            return GroupMemoOutcome.NOTHING_SENT
        }
        // #11 — prefer a FREE NOSTR delivery. Group invites/messages/signed-control were carried in an
        // on-chain (Vault) memo UNCONDITIONALLY, so a near-zero-balance wallet could not form or message
        // a group even when every member was already reachable over NOSTR (they DM for free). When the
        // member is a known NOSTR peer and the outbound publisher is ready, deliver over NIP-17 (no ZEC,
        // no spendable note needed) exactly like a 1:1 Tunnel/Open message; the recipient authenticates
        // the seal pubkey and runs the SAME per-type trust gates as the on-chain path (compact-invite k2
        // requires the authenticated KEX session; #187 signed kick/key). Falls through to the on-chain
        // path below when the peer has no NOSTR key or the publisher isn't ready.
        // ZC-055: the member this memo is for may have left (or been removed, or joined) while the send waited.
        if (!stillWanted()) {
            Log.w(TAG, "Group memo to ${memberAddress.redactAddress()} not sent: it is no longer meant for this member")
            return GroupMemoOutcome.NOTHING_SENT
        }
        // ZC-420-G: a new send of this copy replaces the one an older mark stood for, whatever this send does: an old
        // invitation's mark never sits on a new invitation.
        relaySlot?.let(NostrChatBridge::forgetRelayCopy)
        val relayed =
            trySendGroupMemoOverNostr(memberAddress, inviteMemo) { published ->
                relaySlot?.let { NostrChatBridge.noteRelayCopy(it, published, relayExtra) }
            }
        if (relayed) {
            onRelayDelivered()
            // F8-G: delivered by another route: a failed on-chain attempt of this key needs no Resend any more.
            attempt?.let { settleDelivered(it) }
            return GroupMemoOutcome.SENT
        }

        var tries = 0
        while (true) {
            if (!mayPayOnChain()) {
                Log.w(TAG, "Group memo to ${memberAddress.redactAddress()} not paid on-chain (no spend consent)")
                onNotPaidOnChain()
                return GroupMemoOutcome.NOTHING_SENT
            }
            // ZC-055: asked again before every on-chain attempt (a block wait is minutes long).
            if (!stillWanted()) {
                Log.w(TAG, "Group memo to ${memberAddress.redactAddress()} not paid on-chain: it is no longer meant for this member")
                return GroupMemoOutcome.NOTHING_SENT
            }
            // F8-G step 0: the gate. It only reads. A ledger that cannot be read sends nothing.
            val capture = attempt?.let { tag -> sendAttempts?.let { GroupSendAttemptCapture(it, tag, ::networkTip) } }
            if (capture != null) {
                when (capture.gate()) {
                    GroupGateAnswer.PROCEED -> Unit
                    GroupGateAnswer.PENDING -> return GroupMemoOutcome.PENDING
                    GroupGateAnswer.UNREADABLE -> {
                        onRefused(co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.STORAGE_PAUSED_PAID_STEP)
                        return GroupMemoOutcome.NOTHING_SENT
                    }
                }
            }
            // C1: set by the use case right before it asks the SDK to store and broadcast THIS attempt.
            var submitStarted = false
            try {
                createChunkedMessageProposal(
                    destinationAddress = memberAddress,
                    senderAddress = creatorAddress,
                    message = inviteMemo,
                    isFirstMessage = false,
                    amountPerOutput = Zatoshi(DEFAULT_MESSAGE_AMOUNT),
                    directSubmit = true,
                    skipNavigation = true,
                    rawMemo = true, // Pre-formatted GROUP message
                    onSubmitStarting = {
                        // F8-G step 1: the attempt is in the ledger before the SDK is asked, and before the caller's
                        // own stamp, so an attempt that lost its key to another one stamps nothing. A throw here is
                        // before the submit: nothing was sent.
                        capture?.begin()
                        // D11 (G4): the caller's last step; the submit counts as started only once it succeeded.
                        beforeSubmit?.invoke()
                        submitStarted = true
                    },
                    // F8-G step 2: the ids are committed after the SDK stored every transaction and before the first
                    // submit; a throw there submits nothing. A Resend's commit replaces the failed entry it retries.
                    onTransactionsStored =
                        capture?.let { c ->
                            { stored ->
                                c.stored(stored)
                                settleKeySibling(attempt)
                            }
                        },
                    kexAnswerPrice = priceLimit,
                )
                // F8-G: stored, but its ids could not be recorded, so nothing was submitted: nothing is reported as sent.
                return if (capture?.idsCommitFailed == true) GroupMemoOutcome.PENDING else GroupMemoOutcome.SENT
            } catch (e: Exception) {
                // Coroutine cancellation (user backs out of the minutes-long "Creating…" screen, or
                // process death) is NOT a permanent send failure — let it propagate instead of being
                // swallowed as `return false`, which silently loses every remaining group invite/memo.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // F8-G: another attempt of this key appeared since the gate (or the failed state this Resend was
                // confirmed for is gone): that attempt owns the key, and this one sent nothing.
                if (capture?.lostToAnother == true) return GroupMemoOutcome.PENDING
                if (capture?.writeRefused == true) {
                    onRefused(co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.STORAGE_PAUSED_PAID_STEP)
                    return GroupMemoOutcome.NOTHING_SENT
                }
                // C1: a shortfall is only waited out before the submit; after it, a transaction may be stored.
                if (!submitStarted && isTransientInsufficientFunds(e) && tries < MAX_INVITE_RETRIES) {
                    tries++
                    // F8-G step 3: a first attempt that provably sent nothing leaves no entry.
                    capture?.discard()
                    Log.w(
                        TAG,
                        "Group memo to ${memberAddress.redactAddress()} hit transient insufficient funds " +
                            "(previous send's change pending) — waiting for next block, retry $tries/$MAX_INVITE_RETRIES"
                    )
                    if (!waitForNextBlock()) {
                        // The last attempt failed for want of funds, before any transaction was built.
                        Log.e(TAG, "Gave up waiting for a new block — group memo to ${memberAddress.redactAddress()} failed")
                        groupSendRefusal(e)?.let(onRefused)
                        return GroupMemoOutcome.NOTHING_SENT
                    }
                } else if (memoFailureProvesNothingSent(e, submitStarted)) {
                    Log.e(TAG, "Group memo to ${memberAddress.redactAddress()} failed permanently, nothing sent", e)
                    // F8-G step 3: only a first entry that holds no ids goes; one with ids stays for the chain.
                    capture?.discard()
                    groupSendRefusal(e)?.let(onRefused)
                    return GroupMemoOutcome.NOTHING_SENT
                } else {
                    Log.e(TAG, "Group memo to ${memberAddress.redactAddress()} failed, but it may have been sent", e)
                    onMaybeSent(storedTxIdsOf(e))
                    return GroupMemoOutcome.MAYBE_SENT
                }
            } finally {
                capture?.close()
            }
        }
    }

    /** F8-G: the network height this phone knows, for an attempt's record (null when unknown). */
    private fun networkTip(): Long? =
        try {
            synchronizerProvider.synchronizer.value
                ?.networkHeight
                ?.value
                ?.value
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }

    /**
     * F8-G: a memo of [tag]'s key was delivered free over the relays: a failed (never mined) on-chain attempt of that
     * key is settled (deleted), and for a key message also a failed attempt of the other key kind (a key grant) for the
     * same member and key number, so no "Key not delivered" badge outlives the delivery. Only failed entries are
     * touched; one that is on its way or mined is left to the chain.
     */
    private fun settleDelivered(tag: SendAttemptTag) {
        val ledger = sendAttempts?.ledger ?: return
        runCatching { ledger.settleDelivered(tag.key) }
        settleKeySibling(tag)
    }

    /**
     * F8-G: the current key is on its way to (or reached) the member [tag] names, so a FAILED attempt of the other key
     * kind for that member and key number has nothing left to offer: it is deleted. No-op for the other kinds.
     */
    private fun settleKeySibling(tag: SendAttemptTag) {
        val ledger = sendAttempts?.ledger ?: return
        val sibling = GroupSendAttemptRules.siblingKey(tag.kind, tag.subject, tag.identity) ?: return
        runCatching { ledger.settleDelivered(sibling) }
    }

    /**
     * B1 (GPT-6 Q3) — what one group memo send did. [SENT] and [NOTHING_SENT] are proven; [MAYBE_SENT] follows
     * a transaction the SDK stored and may still broadcast (P2a), so re-arming an owed key, writing the member
     * as owed, or offering a paid Resend / re-send for it would pay for the same memo twice.
     */
    private enum class GroupMemoOutcome {
        SENT,
        NOTHING_SENT,
        MAYBE_SENT,

        /**
         * F8-G (ZC-112): an earlier attempt of this memo's ledger key is unresolved, mined and not yet deep, or proven
         * not sent and waiting for the user's Resend (or this attempt was stored but its ids could not be recorded, so
         * it was not submitted). Nothing was built or paid by this call, and the caller changes NOTHING: no roster
         * field, announce record, allowance or counter, and no rollback of anything it wrote.
         */
        PENDING,
        ;

        /**
         * The invite badge for this outcome. A memo that may have been sent is shown as sent (and so offers
         * no Resend): if its transaction lands late, a Resend would have paid for the invite twice. F8-G: null for
         * [PENDING], which writes no badge.
         */
        val inviteStatus: InviteStatus?
            get() =
                when (this) {
                    NOTHING_SENT -> InviteStatus.FAILED
                    SENT, MAYBE_SENT -> InviteStatus.SENT
                    PENDING -> null
                }
    }

    /**
     * B1 — the banner for one invite: failed (Resend offered) or may have been sent (no Resend). Round 8 D8 (F3): a
     * failed one names its [refusal] when a refusal no balance can fix stopped it.
     */
    private fun reportInviteOutcome(
        groupId: String,
        memberAddress: String,
        outcome: GroupMemoOutcome,
        refusal: String? = null,
    ) {
        when (outcome) {
            GroupMemoOutcome.NOTHING_SENT ->
                _groupSendEvent.value = GroupSendResult.InviteFailed(groupId, listOf(memberAddress), refusal)
            GroupMemoOutcome.MAYBE_SENT ->
                _groupSendEvent.value = GroupSendResult.MaybeSent(groupId, GroupSendResult.MaybeSentKind.INVITE, members = 1)
            GroupMemoOutcome.SENT -> Unit
            // F8-G: nothing was sent by this call and nothing failed: the earlier attempt's own state stands.
            GroupMemoOutcome.PENDING -> Unit
        }
    }

    /**
     * T1b-1 §2 — whether the selected account is a Keystone account while Keystone chat signing is closed. An account
     * that cannot be read within a few seconds is not treated as Keystone (the send chokepoint reads it again).
     */
    private suspend fun keystoneAccountSelected(): Boolean =
        !co.electriccoin.zcash.ui.common.model.KeystoneAvailability.CHAT_SIGNING &&
            try {
                kotlinx.coroutines.withTimeoutOrNull(KEYSTONE_CHECK_TIMEOUT_MS) {
                    accountDataSource.getSelectedAccount()
                } is co.electriccoin.zcash.ui.common.model.KeystoneAccount
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }

    /**
     * #11 — try to deliver a group protocol memo (invite / message / signed control) over NOSTR for
     * FREE, mirroring the 1:1 Tunnel/Open channel. Returns true only if it was published to at least one
     * relay. Returns false (→ caller falls back to on-chain) when the member isn't a known NOSTR peer,
     * the publisher isn't ready, or publishing yields zero acks. The recipient routes inbound NOSTR
     * group memos through the SAME processGroupMessage as on-chain, so the trust gates are identical.
     * We try the member's stored representation and its canonical resolution (#205/#214 address drift)
     * before giving up, so a drifted UA rep doesn't needlessly force an on-chain (paid) send.
     *
     * ZC-420-G: [onPublished] is handed the publish result (the funnel notes it among the relay-copy marks); it decides
     * nothing here.
     */
    private suspend fun trySendGroupMemoOverNostr(
        memberAddress: String,
        memo: String,
        onPublished: (NostrChatBridge.PublishResult) -> Unit = {},
    ): Boolean {
        // ZC-083 (T1b-1 §2, K0 §4): a group memo carries our address; nothing goes over the relays while a Keystone
        // account is selected. The announcer's relay passes come here without the funnel's own check.
        if (keystoneAccountSelected()) return false
        val peerPub = findPeerNostrPubkeyAnyRep(memberAddress) ?: return false
        if (!co.electriccoin.zcash.ui.nostr.NostrChatBridge.isOutboundReady()) return false
        // ZC-393: a free-first relay copy: any readable relay is enough here, so ZC-393's stricter count never starts an
        // on-chain spend (residual R-1).
        return try {
            val result = co.electriccoin.zcash.ui.nostr.NostrChatBridge.publish(memo, peerPub)
            // Bookkeeping only: a failure here never turns a delivered copy into an on-chain fallback.
            runCatching { onPublished(result) }
            val delivered = result.readableAcks > 0
            if (delivered) {
                Log.d(
                    TAG,
                    "Group memo to ${memberAddress.redactAddress()} sent FREE over NOSTR (${result.readableAcks} relay ack)"
                )
            }
            delivered
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "NOSTR group memo to ${memberAddress.redactAddress()} failed — falling back to on-chain", e)
            false
        }
    }

    /**
     * Rep-tolerant NOSTR-pubkey lookup (#205/#214 address drift). A peer's NOSTR key may be stored
     * under a DIFFERENT unified-address representation than the (resolved) group-roster address — e.g.
     * the group fan-out canonicalizes a member to one rep while the KEX/first-contact stored the key
     * under another. A rep-sensitive lookup then wrongly falls back to a paid on-chain send. Try the
     * given address, its canonical, then EVERY known peer rep that canonicalizes to the same peer.
     */
    private fun findPeerNostrPubkeyAnyRep(address: String): String? {
        zchatPreferences.getPeerNostrPubkey(address)?.let { return it }
        val canonical = zchatPreferences.resolvePeerAddress(address)
        if (canonical != address) zchatPreferences.getPeerNostrPubkey(canonical)?.let { return it }
        return zchatPreferences.getAllConversationPeerAddresses().asSequence()
            .filter { it != address && zchatPreferences.resolvePeerAddress(it) == canonical }
            .mapNotNull { zchatPreferences.getPeerNostrPubkey(it) }
            .firstOrNull()
    }

    /**
     * True only for a TRANSIENT "funds still confirming" refusal, which resolves on the next blocks: the use case's
     * "…confirm on-chain…" shortfall (our change maturing OR ZEC we just received), or, Round 8 D7 (sweep #8), the
     * sealed-Orchard refusal while Ironwood is still confirming (a wallet that still holds Orchard meets it whenever
     * the previous member's send locked its only Ironwood note). A genuine shortfall, and the "move your old
     * balance" refusal, must NOT retry. The one rule is [isTransientWait].
     */
    private fun isTransientInsufficientFunds(throwable: Throwable): Boolean = isTransientWait(throwable)

    /**
     * Suspend until the network block height advances past its current value (the previous invite's
     * change confirms), or [timeoutMs] elapses. Returns true if a new block landed.
     */
    private suspend fun waitForNextBlock(timeoutMs: Long = INVITE_BLOCK_WAIT_TIMEOUT_MS): Boolean {
        return try {
            val synchronizer = synchronizerProvider.getSynchronizer()
            val startHeight = synchronizer.networkHeight.value?.value
            withTimeoutOrNull(timeoutMs) {
                synchronizer.networkHeight.first { h ->
                    h != null && (startHeight == null || h.value > startHeight)
                }
            } != null
        } catch (e: Exception) {
            Log.e(TAG, "waitForNextBlock failed", e)
            false
        }
    }

    /**
     * #187 — ADMIN-ONLY: kick [kickedAddress] from the group. Rotates to a fresh group key so the
     * removed member can't read future messages, and notifies every remaining member with a per-member
     * SIGNED GROUP_KICK (signed with our per-peer KEX key, which they verify against ours). See
     * [rotateAndNotify]. No-op (logged) if we aren't the admin.
     */
    fun kickMember(groupId: String, kickedAddress: String) {
        // R2-2 (b): a signed kick with the new key goes to every remaining key holder. Ask first.
        // R4-2: and the kick then runs that quoted fan-out — a remaining member quoted as free is never
        // paid for on-chain, not by the rotation and not by its owed-key retry.
        // Round 8 P5 (PHONE_TRIAGE F5a): a remaining key holder with no secure session can't be sent the new key.
        // Say who, and change nothing, before the cost is quoted.
        askAboutUnkeyable(groupId, kickedAddress) { kickMemberQuoted(groupId, kickedAddress) }
    }

    /** #187 / R4-2 — the kick once no member is stranded unasked: quote its fan-out, then rotate and notify. */
    private fun kickMemberQuoted(
        groupId: String,
        kickedAddress: String,
    ) {
        // Round 8 P6 (PHONE_TRIAGE F5b): the plan also holds the removed member's notice (no key), so it is quoted too.
        val plan = { rotationRecipients(groupId, kickedAddress) + removalNoticeRecipients(groupId, kickedAddress) }
        // Round 8 MA3 (design §4 T3, §6.3, review F5): and the on-chain `R` announces to the remaining members with no
        // relay route, counted as pairs in the same quote. The removed member is told by the notice above only.
        val quoted = quoteFanOut(plan())
        val removal = quotePairs(removalAnnouncePairs(groupId, kickedAddress))
        askBeforePaying(PaidAction.GROUP_REMOVE_MEMBER, quoted, extraOnChain = removal.paidPairs.size) {
            val now = quoteFanOut(plan())
            val nowRemoval = quotePairs(removalAnnouncePairs(groupId, kickedAddress))
            if (now.recipients != quoted.recipients ||
                !quoted.paidRecipients.containsAll(now.paidRecipients) ||
                !removal.paidPairs.containsAll(nowRemoval.paidPairs)
            ) {
                Log.w(TAG, "${PaidAction.GROUP_REMOVE_MEMBER}: the plan changed after its cost was quoted — asking again, nothing sent")
                kickMemberQuoted(groupId, kickedAddress)
                return@askBeforePaying
            }
            viewModelScope.launch {
                val notNotified =
                    rotateAndNotify(groupId, kickedAddress, quoted::mayPayFor) {
                        announceRemoval(groupId, kickedAddress, removal)
                    } ?: return@launch
                // Round 8 MA3: the free relay copies of `R` (to the remaining members and to the removed member).
                announcer.requestPass(viewModelScope, groupId, resendR = true, force = true)
                // Round 8 P5: members with no secure session are reported apart; the rest are send failures.
                val skipped = rotationNoSession.remove(groupId)
                val noSession = skipped?.members.orEmpty()
                val failed = notNotified.filterNot { it in skipped?.rows.orEmpty() }
                // Round 8 D8 (F3): the refusal no balance can fix that stopped the new key memos, if one did.
                val refusal = rotationRefusals.remove(groupId)
                val unconfirmed = unconfirmedKeyHolders(groupId)
                if (failed.isNotEmpty() || noSession.isNotEmpty() || unconfirmed > 0) {
                    // D3 — soft-warn, never hard-block: the member IS removed locally (an offline
                    // remaining member must not wedge a legitimate removal), but the group isn't clean
                    // until every remaining member holds the new key. Surface it and start the
                    // idempotent push retry (the receiver's epoch replay-guard no-ops duplicates).
                    // C1: a member whose key memo MAY have been sent is counted too, but not re-sent.
                    // Round 8 P5: a member with no secure session is counted apart; nothing can send it the key before a key exchange.
                    Log.w(
                        TAG,
                        "Kick: couldn't notify ${failed.size} member(s), ${noSession.size} with no session, " +
                            "$unconfirmed unconfirmed",
                    )
                    _groupSendEvent.value =
                        GroupSendResult.RemovalIncomplete(groupId, failed.size, unconfirmed, refusal, noSession.size)
                    if (failed.isNotEmpty()) viewModelScope.launch { retryPendingGroupKeysInternal(groupId, quoted = quoted) }
                }
                loadGroupDetail(groupId, retryOwedKeys = false)
                loadGroupSettings(groupId) // refresh the settings screen the action was triggered from
            }
        }
    }

    // ==========================================
    // ROUND 8 MA3: THE MEMBER ANNOUNCE (GP), ANNOUNCER SIDE
    // ==========================================
    // MEMBER_ANNOUNCE_DESIGN.md v2 §4 (T3, T4), §4.1, §6.2, §6.3. Relay copies are free and sent by the passes
    // (GroupAnnouncer). An on-chain copy is paid only here, by a tap (Introduce them, or a removal), under a quote that
    // counts pairs, one compare-and-set ledger record per pair, and never with sendGroupMemoWithRetry's `{ true }`.

    @Volatile private var cachedAnnouncer: Pair<ZchatPreferences, GroupAnnouncer>? = null

    /** The announcer over the store this ViewModel uses now. */
    private val announcer: GroupAnnouncer
        get() {
            val prefs = zchatPreferences
            cachedAnnouncer?.let { (p, a) -> if (p === prefs) return a }
            return GroupAnnouncer(prefs, { address, memo -> trySendGroupMemoOverNostr(address, memo) }, ::findPeerNostrPubkeyAnyRep)
                .also { cachedAnnouncer = prefs to it }
        }

    override fun onCleared() {
        super.onCleared()
        // Round 8 MA3: a pass waiting for the relays is not run for a cleared screen.
        cachedAnnouncer?.second?.close()
    }

    private val _introduceProgress = MutableStateFlow<IntroduceProgress?>(null)

    /** Round 8 MA3 — "Introducing k of n…" while an Introduce run pays, else null. */
    val introduceProgress: StateFlow<IntroduceProgress?> = _introduceProgress.asStateFlow()

    /**
     * Design §6.2 — the pairs the banner counts: `A` announces still owed to a recipient with no relay key (the only
     * ones that can need an on-chain memo; a recipient with a relay key is re-sent free). `R` debt is paid in the
     * removal's quote, and a leave never makes on-chain debt.
     */
    private fun owedOnChainPairs(groupId: String): List<AnnouncePair> =
        announcer.owedPairs(groupId).filter { it.op == AnnounceOp.A && findPeerNostrPubkeyAnyRep(it.recipient) == null }

    /** The announcer's banner, button and row notes for [groupId] (design §6.2, §9); null when this phone is not it. */
    @Suppress("CyclomaticComplexMethod")
    private fun introduceInfo(
        groupId: String,
        info: GroupInfo,
        roster: List<GroupMember>,
    ): GroupIntroduceInfo? {
        if (!info.isActive || !isGroupAdmin(groupId) || !hasMembersToIntroduce(roster)) return null
        val a = announcer
        val state = a.state(groupId)
        val owed = a.owedPairs(groupId)
        val ledger = a.ledger(groupId)
        val onChain = owed.filter { it.op == AnnounceOp.A && findPeerNostrPubkeyAnyRep(it.recipient) == null }
        // Round 10b R10B-2 (F-R9B-1): only a pair whose relay send was tried and not acked counts as not reached; a pair
        // no pass has tried yet (the pass is spaced, or waits for the relays) is not a failure.
        val unreachable =
            owed.count { pair ->
                findPeerNostrPubkeyAnyRep(pair.recipient) != null &&
                    announceLedgerEntryFor(ledger, pair).let { it == null || it.op != pair.op } &&
                    a.relayTriedNotAcked(groupId, pair)
            }
        val keys = onChain.map { announcePairKey(it) }.toSet()
        val rows = roster.filter { it.status == MemberStatus.ACTIVE && !it.isAdmin && !zchatPreferences.isSelfAddress(it.address) }
        val noSecureLink = rows.filterNot { a.isEligible(state, it) }.map { it.address }.toSet()
        val epoch = zchatPreferences.getGroupKeyEpoch(groupId)
        val tooBig =
            rows
                .filter { it.address !in noSecureLink }
                .filterNot { row ->
                    val form = state.members[a.canon(row.address)]?.annForm ?: row.address
                    memberAnnounceFits(groupId, form, maxOf(System.currentTimeMillis(), state.hw) + 1, epoch)
                }.map { it.address }
                .toSet()
        val leftUnconfirmed =
            roster
                .filter { it.status == MemberStatus.LEFT && !zchatPreferences.isSelfAddress(it.address) }
                .filter { row ->
                    state.members[a.canon(row.address)]?.let { it.leftBy == ActivatedBy.UNAUTH && it.op != AnnounceOp.R } == true
                }.map { it.address }
                .toSet()
        return GroupIntroduceInfo(
            owedOnChainPairs = onChain.size,
            unreachablePairs = unreachable,
            showBanner =
                keys.isNotEmpty() &&
                    !info.announcerRebuilt &&
                    relaysReady() &&
                    !state.introduceHidden.containsAll(keys),
            rebuilt = info.announcerRebuilt,
            noSecureLink = noSecureLink,
            tooBig = tooBig,
            leftUnconfirmed = leftUnconfirmed,
            mixedVersion = rows.any { !a.isGp1(it.address) },
        )
    }

    /**
     * Design §4 T4 — opening the group screen: on the announcer, a relay pass (free) that also re-sends every current
     * `R` (never on a group this phone rebuilt: only the tap announces there); on a member's phone whose creator can be
     * reached over the relays, one roster query (GQ) the first time on this build or after a restore (§6.1).
     */
    private fun announceOnOpen(groupId: String) {
        runCatching {
            val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return
            if (!info.isActive) return
            if (isGroupAdmin(groupId)) {
                val roster =
                    zchatPreferences.getGroupMembers(groupId)?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }.orEmpty()
                if (!hasMembersToIntroduce(roster)) return
                announcer.requestPass(viewModelScope, groupId, resendR = true) { refreshGroupDetail(groupId) }
            } else if (announceState(groupId).rosterQueryAtMs == 0L && relaysReady()) {
                // ZC-022 (KI-25 R3): the query goes to the admin's contact (the chat that holds the creator's key), else
                // to the stored creator address as before. Relay only.
                val adminTarget = adminTarget(info)
                if (findPeerNostrPubkeyAnyRep(adminTarget) == null) return
                viewModelScope.launch {
                    if (trySendGroupMemoOverNostr(adminTarget, ZMSGGroupProtocol.createGroupRosterQueryMessage(groupId))) {
                        synchronized(ZchatPreferences.groupLock(groupId)) {
                            val state = announceState(groupId)
                            runCatching {
                                zchatPreferences.saveGroupAnnounceState(groupId, state.copy(rosterQueryAtMs = System.currentTimeMillis()))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Design §3 — a member announce needs two members besides the creator (one to tell, one to tell about): a group
     * with fewer has nothing to announce, and its screen reads no announce records.
     */
    private fun hasMembersToIntroduce(roster: List<GroupMember>): Boolean =
        roster
            .filter { !it.isAdmin && !zchatPreferences.isSelfAddress(it.address) }
            .map { zchatPreferences.resolvePeerAddress(it.address) }
            .distinct()
            .size >= 2

    private fun relaysReady(): Boolean =
        co.electriccoin.zcash.ui.nostr.NostrChatBridge
            .isOutboundReady()

    /** Round 9 R9-3: the height up to which this wallet has scanned every block, or null (as ChatViewModel.fullyScannedTip). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun scannedTip(): Long? =
        try {
            synchronizerProvider.synchronizer.value
                ?.fullyScannedHeight
                ?.value
                ?.value
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }

    /** Re-emit [groupId]'s screen state when it is the one showing (after a pass changed its counts). */
    private fun refreshGroupDetail(groupId: String) {
        val current = _groupDetailState.value as? GroupDetailState.Success ?: return
        if (current.conversation.groupInfo.groupId != groupId) return
        noteShownStoreVersion(groupId)
        val group = loadGroup(groupId, detail = true) ?: return
        _groupDetailState.value = current.copy(conversation = group)
    }

    /** Design §6.2 — "Not now": hide the banner until a pair it did not count is owed. */
    fun dismissIntroduceBanner(groupId: String) {
        val keys = owedOnChainPairs(groupId).map { announcePairKey(it) }.toSet()
        synchronized(ZchatPreferences.groupLock(groupId)) {
            val state = announceState(groupId)
            runCatching { zchatPreferences.saveGroupAnnounceState(groupId, state.copy(introduceHidden = keys)) }
        }
        refreshGroupDetail(groupId)
    }

    /**
     * Design §6.3 — "Introduce them" (the banner) or "Introduce members" (a group this phone rebuilt): number, send
     * the free relay copies, then quote the on-chain pairs ([PaidAction.GROUP_INTRODUCE], one memo per pair) and pay
     * exactly those once the admin accepts. Declining sends nothing on-chain.
     */
    fun introduceMembers(groupId: String) {
        if (!isInGroup(groupId) || !isGroupAdmin(groupId)) return
        viewModelScope.launch {
            announcer.number(groupId)
            announcer.requestPass(viewModelScope, groupId, force = true) { refreshGroupDetail(groupId) }
            askToIntroduce(groupId)
        }
    }

    private fun askToIntroduce(groupId: String) {
        val quoted = quotePairs(owedOnChainPairs(groupId))
        // F8-G (G4-1): per quoted pair, the failed (never mined) attempt this question is raised for, if the ledger
        // still holds one: that pair is then paid against exactly that state, once.
        val retries = announceOffers(groupId, quoted)
        askBeforePaying(PaidAction.GROUP_INTRODUCE, QuotedFanOut(emptyList(), emptySet()), extraOnChain = quoted.paidPairs.size) {
            val now = quotePairs(owedOnChainPairs(groupId))
            if (!quoted.paidPairs.containsAll(now.paidPairs)) {
                Log.w(TAG, "${PaidAction.GROUP_INTRODUCE}: more pairs are owed than were quoted — asking again, nothing sent")
                askToIntroduce(groupId)
            } else if (announceOffers(groupId, quoted) != retries) {
                // A failed attempt of a quoted pair appeared, was replaced or went away while the question was open.
                Log.w(TAG, "${PaidAction.GROUP_INTRODUCE}: a failed announce changed after the quote — asking again, nothing sent")
                askToIntroduce(groupId)
            } else if (quoted.paidPairs.isNotEmpty()) {
                viewModelScope.launch { payIntroductions(groupId, quoted, retries) }
            }
        }
    }

    /** F8-G: pair name to the marker of that pair's failed announce attempt, for the pairs [quoted] would pay. */
    private fun announceOffers(
        groupId: String,
        quoted: QuotedPairs,
    ): Map<String, Long> {
        val ledger = sendAttempts?.ledger ?: return emptyMap()
        return quoted.paidPairs
            .mapNotNull { pairKey ->
                val key = GroupSendAttemptRules.announce(groupId, pairKey).key
                runCatching { ledger.offerStartedAt(key) }.getOrNull()?.let { pairKey to it }
            }.toMap()
    }

    /** Pay the quoted pairs still owed, one by one, under the group's rotation lock (D5), with a progress line. */
    private suspend fun payIntroductions(
        groupId: String,
        quoted: QuotedPairs,
        // F8-G: pair name to the marker of the failed attempt the question was raised for.
        retries: Map<String, Long> = emptyMap(),
    ) {
        val adminAddress = _currentUserAddress.value ?: return
        var sent = 0
        var maybe = 0
        var failed = 0
        withGroupChange(groupId) {
            val todo = owedOnChainPairs(groupId).filter { quoted.mayPayFor(it) }
            for ((i, pair) in todo.withIndex()) {
                _introduceProgress.value = IntroduceProgress(groupId, i, todo.size)
                when (payAnnouncePair(groupId, pair, quoted, adminAddress, retries[announcePairKey(pair)])) {
                    GroupMemoOutcome.SENT -> sent++
                    GroupMemoOutcome.MAYBE_SENT -> maybe++
                    GroupMemoOutcome.NOTHING_SENT -> failed++
                    // F8-G: an earlier attempt of this pair is unresolved: neither sent nor failed by this run.
                    GroupMemoOutcome.PENDING -> Unit
                    null -> Unit
                }
            }
            _introduceProgress.value = null
        }
        if (sent + maybe + failed > 0) {
            _groupSendEvent.value =
                GroupSendResult.MaybeSent(groupId, GroupSendResult.MaybeSentKind.INTRODUCE, members = maybe, notSent = failed, sent = sent)
        }
        loadGroupDetail(groupId, retryOwedKeys = false)
    }

    /**
     * Design §4 T3 — the on-chain `R` pairs a removal of [kickedAddress] would owe, for its quote: one per remaining
     * member we can vouch for (design §3) that was not already told `R`. Empty when the member was never announced.
     * The removed member itself is not among them: P6's signed notice, inside the same quote, already tells it.
     */
    private fun removalAnnouncePairs(
        groupId: String,
        kickedAddress: String,
    ): List<AnnouncePair> {
        val a = announcer
        val kicked = a.canon(kickedAddress)
        val state = a.state(groupId)
        val entry = state.members[kicked] ?: return emptyList()
        if (entry.op != AnnounceOp.A) return emptyList()
        val form = entry.annForm ?: kickedAddress
        val kickedKey = a.keyHash(form)
        val ledger = a.ledger(groupId)
        val roster =
            zchatPreferences.getGroupMembers(groupId)?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }.orEmpty()
        return roster
            .filter { it.status == MemberStatus.ACTIVE && !it.isAdmin && !zchatPreferences.isSelfAddress(it.address) }
            .filter { a.canon(it.address) != kicked && a.isEligible(state, it) && a.keyHash(it.address) != kickedKey }
            .distinctBy { a.keyHash(it.address) }
            .map { r ->
                AnnouncePair(r.address, a.canon(r.address), a.keyHash(r.address), form, kicked, kickedKey, AnnounceOp.R, 0L, 0)
            }.filterNot { pair ->
                val last = announceLedgerEntryFor(ledger, pair)
                last != null && last.op == AnnounceOp.R && last.state != AnnounceLedgerState.RELAY_UNCONFIRMED
            }
    }

    /**
     * Design §4 T3, §6.3 — after the roster and removal record are committed (under the rotation lock), number its
     * `R` and pay the removal's quoted on-chain `R` pairs. The free relay copies go out in the pass requested next.
     */
    private suspend fun announceRemoval(
        groupId: String,
        kickedAddress: String,
        quoted: QuotedPairs,
    ) {
        val adminAddress = _currentUserAddress.value ?: return
        val a = announcer
        val kicked = a.canon(kickedAddress)
        a.number(groupId)
        if (quoted.paidPairs.isEmpty()) return
        for (pair in a.owedPairs(groupId).filter { it.op == AnnounceOp.R && it.sAddr == kicked && quoted.mayPayFor(it) }) {
            payAnnouncePair(groupId, pair, quoted, adminAddress)
        }
    }

    /**
     * Design §6.3 — pay one quoted announce pair on-chain, at most once:
     * 1. its ledger entry goes to CHAIN_PENDING (compare-and-set, commit()); a refused write, or a pair no longer owed,
     *    sends nothing;
     * 2. the memo goes through sendGroupMemoWithRetry with an explicit mayPayOnChain (the quote covers this pair and
     *    the entry is still this run's CHAIN_PENDING), and beforeSubmit checks the entry again right before the SDK;
     * 3. SENT: CHAIN_SENT; MAYBE_SENT: MAYBE_SENT, never paid again; NOTHING_SENT: back to what it was (owed again),
     *    a checked write.
     * A recipient reachable over the relays now is left to the free relay pass. Null when nothing was attempted.
     * Round 8 MA-V3 (VERIFIED_MA_FINDINGS #3): a recipient that became reachable over the relays between that check and
     * the send gets the memo free (sendGroupMemoWithRetry tries the relays first); that SENT is recorded as the relay
     * delivery it was (RELAY_FINAL for a gp1 recipient, else RELAY_UNCONFIRMED, re-sent free later), never CHAIN_SENT.
     */
    private suspend fun payAnnouncePair(
        groupId: String,
        pair: AnnouncePair,
        quoted: QuotedPairs,
        adminAddress: String,
        // F8-G: the marker of the failed attempt of this pair the user's quote was shown for (null: none).
        retryOf: Long? = null,
    ): GroupMemoOutcome? {
        if (!quoted.mayPayFor(pair) || reachableFree(pair.recipient)) return null
        val a = announcer
        val paying = "$groupId|${announcePairKey(pair)}"
        if (!GroupAnnounceRuntime.paying.add(paying)) return null
        try {
            val before = a.entryFor(groupId, pair)
            val pending = a.entry(pair, AnnounceLedgerState.CHAIN_PENDING)
            val claimed = synchronized(ZchatPreferences.groupLock(groupId)) { a.cas(groupId, pair, before, pending) }
            if (!claimed) {
                Log.w(TAG, "Announce to ${pair.recipient.redactAddress()} not paid: its ledger record was refused")
                return null
            }
            val memo = a.memo(groupId, pair)
            if (memo == null) {
                a.cas(groupId, pair, pending, a.entry(pair, AnnounceLedgerState.TOO_BIG))
                return null
            }
            val stillPending = { a.entryFor(groupId, pair) == pending }
            var stored: List<String> = emptyList()
            var relayed = false
            val outcome =
                try {
                    sendGroupMemoWithRetry(
                        pair.recipient,
                        memo,
                        adminAddress,
                        onMaybeSent = { stored = it },
                        onRelayDelivered = { relayed = true },
                        beforeSubmit = { check(stillPending()) { "the announce is no longer this run's to pay" } },
                        // F8-G: the on-chain copy's attempt in the ledger (its ids are recorded before the submit).
                        attempt = GroupSendAttemptRules.announce(groupId, announcePairKey(pair), retryOf),
                        mayPayOnChain = { quoted.mayPayFor(pair) && stillPending() },
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Announce to ${pair.recipient.redactAddress()} failed (${e.javaClass.simpleName})")
                    GroupMemoOutcome.MAYBE_SENT
                }
            val after =
                when (outcome) {
                    GroupMemoOutcome.SENT ->
                        when {
                            !relayed -> a.entry(pair, AnnounceLedgerState.CHAIN_SENT)
                            a.isGp1(pair.recipient) -> a.entry(pair, AnnounceLedgerState.RELAY_FINAL)
                            else -> a.entry(pair, AnnounceLedgerState.RELAY_UNCONFIRMED)
                        }
                    GroupMemoOutcome.MAYBE_SENT -> a.entry(pair, AnnounceLedgerState.MAYBE_SENT, txids = stored)
                    GroupMemoOutcome.NOTHING_SENT -> before
                    // F8-G: nothing was sent by this call: the claim goes back to what the pair had.
                    GroupMemoOutcome.PENDING -> before
                }
            val written =
                synchronized(ZchatPreferences.groupLock(groupId)) {
                    if (relayed && outcome == GroupMemoOutcome.SENT) {
                        // MA-V3: the store never lets a relay state replace an on-chain one for the same op
                        // (casAnnounceLedger), so this run's own CHAIN_PENDING claim is released first, under the same
                        // lock, and the relay delivery is then recorded in its place.
                        a.cas(groupId, pair, pending, null) && a.cas(groupId, pair, null, after)
                    } else {
                        a.cas(groupId, pair, pending, after)
                    }
                }
            if (!written) Log.w(TAG, "Announce to ${pair.recipient.redactAddress()}: outcome $outcome not recorded (the entry changed)")
            return outcome
        } finally {
            GroupAnnounceRuntime.paying.remove(paying)
        }
    }

    /**
     * #187 — ADMIN-ONLY: rotate the group key (e.g. periodic hygiene) and push a per-member SIGNED
     * GROUP_KEY to every member. No member is removed. No-op (logged) if we aren't the admin.
     */
    fun rotateGroupKey(groupId: String) {
        // ZC-757 (review round 2, finding 2): a rebuilt group's first new key waits for a pass's block time.
        rebuiltKeyWaitsForHistory(groupId)?.let {
            _groupSendEvent.value = it
            return
        }
        // R2-2 (b): the new key goes to every key holder as its own memo. Ask first.
        // R4-2: the rotation then runs that quoted fan-out and pays on-chain only for its quoted members.
        // Round 8 P5 (PHONE_TRIAGE F5a): the same question as a kick, before the cost is quoted.
        askAboutUnkeyable(groupId, null) { rotateGroupKeyQuoted(groupId) }
    }

    /** #187 / R4-2 — the rotation once no member is stranded unasked: quote its fan-out, then rotate and notify. */
    private fun rotateGroupKeyQuoted(groupId: String) {
        askForQuotedPlan(PaidAction.GROUP_ROTATE_KEY, { rotationRecipients(groupId, null) }) { quoted ->
            viewModelScope.launch {
                val notNotified = rotateAndNotify(groupId, null, quoted::mayPayFor) ?: return@launch
                // Round 8 P5: members with no secure session are reported apart; the rest are send failures.
                val skipped = rotationNoSession.remove(groupId)
                val noSession = skipped?.members.orEmpty()
                val failed = notNotified.filterNot { it in skipped?.rows.orEmpty() }
                // Round 8 D8 (F3): the refusal no balance can fix that stopped the new key memos, if one did.
                val refusal = rotationRefusals.remove(groupId)
                val unconfirmed = unconfirmedKeyHolders(groupId)
                if (failed.isNotEmpty() || noSession.isNotEmpty() || unconfirmed > 0) {
                    // D3 — same incomplete-delivery surface + retry as a kick: members who missed the
                    // rotation would otherwise keep sending under the old epoch indefinitely.
                    // C1: a member whose key memo MAY have been sent is counted too, but not re-sent.
                    Log.w(
                        TAG,
                        "Rotate: couldn't deliver the new key to ${failed.size} member(s), ${noSession.size} with no session, " +
                            "$unconfirmed unconfirmed",
                    )
                    _groupSendEvent.value =
                        GroupSendResult.RemovalIncomplete(groupId, failed.size, unconfirmed, refusal, noSession.size)
                    if (failed.isNotEmpty()) viewModelScope.launch { retryPendingGroupKeysInternal(groupId, quoted = quoted) }
                }
                loadGroupDetail(groupId, retryOwedKeys = false)
                loadGroupSettings(groupId) // refresh the settings screen the action was triggered from
            }
        }
    }

    /**
     * C1 (B1 review item 2) — the key holders (other than us) whose last key memo MAY have been sent: its
     * transaction was stored, then rejected, and may still be broadcast. rotateAndNotify rewrites that record on
     * every recipient row, so right after a rotation this counts exactly that rotation's unconfirmed members.
     */
    private fun unconfirmedKeyHolders(groupId: String): Int =
        zchatPreferences
            .getGroupMembers(groupId)
            ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
            .orEmpty()
            .count { ZMSGGroupProtocol.holdsGroupKey(it) && it.pendingKeyTxIds != null && !zchatPreferences.isSelfAddress(it.address) }

    /** KI-22 — true when every stored row of [address]'s member is LEFT now (a GROUP_LEAVE stored during a change). */
    private fun hasLeftNow(
        groupId: String,
        address: String,
    ): Boolean =
        withGroupLock(groupId) {
            val canon = zchatPreferences.resolvePeerAddress(address)
            val rows = storedRoster(groupId).orEmpty().filter { zchatPreferences.resolvePeerAddress(it.address) == canon }
            rows.isNotEmpty() && rows.all { it.status == MemberStatus.LEFT }
        }

    /**
     * ZC-778 (b) — the number a new group key takes: the first one above the current number that holds no stored key.
     * A number whose key is already stored was sent to members by an earlier try that did not finish (the key is saved
     * before it is sent); a second, different key under that number would be refused by them as a replay.
     */
    private fun nextFreeKeyEpoch(groupId: String): Int {
        val stored = zchatPreferences.getGroupKeyEpoch(groupId)
        // ZC-757 (G2): a rebuilt group's first new key starts above any number a member can hold.
        var epoch =
            if (rebuiltStage(groupId) == RebuiltGroupKey.Stage.NEEDS_KEY) RebuiltGroupKey.firstKeyNumber(stored) else stored + 1
        while (zchatPreferences.getGroupKey(groupId, epoch) != null) epoch++
        return epoch
    }

    /**
     * ZC-778 — one checked store write of a rotation ([write] returns only when it reached the disk, see
     * [ZchatPreferences.saveGroupKey] with `durable`): false when the store refused it or threw.
     */
    private fun storeTook(
        what: String,
        write: () -> Unit,
    ): Boolean =
        try {
            write()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Rotation: $what did not reach the disk (${e.javaClass.simpleName})")
            false
        }

    /**
     * ZC-778 — the result of a rotation the phone's store stopped before anything was sent or paid: the existing
     * storage notice, and nothing else. (A removal stopped after its key messages has its own sentence, ZC-791.)
     */
    private fun rotationStoreRefused(groupId: String): GroupSendResult =
        GroupSendResult.RemovalIncomplete(
            groupId,
            pendingMembers = 0,
            refusal = co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.STORAGE_PAUSED_PAID_STEP,
        )

    /**
     * Shared admin path for kick + rotate: gen a fresh group key at the next free number ([nextFreeKeyEpoch]; ZC-778:
     * saved on this phone before it is sent), wrap it per-member under
     * our authenticated KEX session with them, SIGN the canonical control payload with our per-peer
     * key, and send each member their own verifiable GROUP_KICK (when [kickedAddress] != null) or
     * GROUP_KEY. Then update our local roster + adopt the new key. Members we have no KEX session with
     * can't be sent a verifiable control msg — they're returned as "failed" (they keep the old key until
     * a KEX + re-sync), and Round 8 P5 also records them in [rotationNoSession] so the caller reports them apart
     * from send failures. @return addresses we couldn't notify; null when the phone's store stopped the rotation
     * (ZC-690, ZC-778), which has then shown its own result ([rotationStoreRefused]).
     */
    private suspend fun rotateAndNotify(
        groupId: String,
        kickedAddress: String?,
        mayPayOnChain: (String) -> Boolean,
    ): List<String>? = rotateAndNotify(groupId, kickedAddress, mayPayOnChain, afterCommit = null)

    /** [rotateAndNotify], and Round 8 MA3's [afterCommit]: what a removal pays once committed, under the same lock. */
    private suspend fun rotateAndNotify(
        groupId: String,
        kickedAddress: String?,
        /**
         * R4-2 — true only for a member the kick / rotation prompt counted as an on-chain message. The
         * roster is re-read here under the rotation lock (D5), so a member who appeared after the quote
         * still gets the new key over the free route, but is never paid for.
         */
        mayPayOnChain: (String) -> Boolean,
        // Round 8 MA3 (design §6.3): what the removal pays after it is committed, under the same rotation lock (its
        // on-chain `R` announces), handed the new key epoch.
        afterCommit: (suspend (newEpoch: Int) -> Unit)?,
    ): List<String>? {
        val adminAddress = _currentUserAddress.value ?: return emptyList()
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
        // Authorize via the persisted self-created flag (robust to self-address drift), falling back to
        // the address comparison for legacy groups. The control messages we send are still SIGNED with
        // our per-peer KEX key, so recipients independently verify authenticity (#187) regardless.
        val amAdmin = zchatPreferences.isGroupSelfCreated(groupId) ||
            (info?.creatorAddress?.let { zchatPreferences.isSelfAddress(it) } ?: false)
        if (info == null || !amAdmin) {
            Log.w(TAG, "rotateAndNotify: only the group admin may kick/rotate — aborting")
            return emptyList()
        }
        // ZC-757 (review round 2, finding 2): also for a removal, which makes the new key as well.
        rebuiltKeyWaitsForHistory(groupId)?.let {
            _groupSendEvent.value = it
            return null
        }
        // D5 / review F1 — the lock MUST span the ROSTER + EPOCH read through the two commit calls.
        // Two rapid kicks/rotations must not both (a) read epoch N and mint two different N+1 keys
        // (epoch fork) NOR (b) capture the SAME pre-lock roster snapshot: with the roster read outside
        // the lock, the second kick would re-key the member the first kick just removed AND rewrite the
        // roster from its stale copy — RESURRECTING the kicked member as ACTIVE. Reading the roster
        // INSIDE the lock (as the sibling retryPendingGroupKeysInternal already does) makes each
        // rotation observe the previous one's committed roster; the second caller waits here and reads
        // the freshly committed epoch/roster, advancing to N+2. The monotonic epoch is the idempotent
        // rotation id.
        return withGroupChange(groupId) {
            // ZC-082: an earlier rotation that was cut off is completed first; this one then takes the next number.
            if (healInterruptedRotation(groupId) == Heal.REFUSED) {
                _groupSendEvent.value = rotationStoreRefused(groupId)
                return@withGroupChange null
            }
            val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return@withGroupChange emptyList()
            val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
            val newEpoch = nextFreeKeyEpoch(groupId)
            val newKeyBase64 = ZMSGGroupProtocol.encodeGroupKey(ZMSGGroupProtocol.generateGroupKey())

            // Canonicalize the kicked-member and self comparisons (#205/#214 address drift). A roster that
            // still holds the kicked peer under a SECOND unified-address representation must NOT keep an ACTIVE
            // row that then receives the freshly-rotated key — that would let the kicked member keep decrypting
            // every post-kick message. Match by resolved (canonical) address, and use the hash-tolerant
            // self-check for the admin exclusion (same pattern as sendGroupMessage).
            val kickedCanonical = kickedAddress?.let { zchatPreferences.resolvePeerAddress(it) }
            // review F3 — fan out to every current KEY-HOLDER, not just ACTIVE members. An INVITED
            // member whose invite was actually delivered (InviteStatus.SENT) already holds the CURRENT
            // group key (the invite carries it), so a kick/rotation must re-key them too or they keep
            // decrypting post-rotation traffic while a kicked peer is being locked out. Invitees whose
            // invite is still pending/failed never received a key and are (correctly) excluded — see
            // ZMSGGroupProtocol.holdsGroupKey.
            val recipients = members.filter {
                ZMSGGroupProtocol.holdsGroupKey(it) &&
                    !zchatPreferences.isSelfAddress(it.address) &&
                    (kickedCanonical == null || zchatPreferences.resolvePeerAddress(it.address) != kickedCanonical)
            }
            val failed = mutableListOf<String>()
            // Round 8 P5 (PHONE_TRIAGE F5a): key holders with no secure session, reported apart from send failures.
            val noSession = mutableListOf<String>()
            rotationNoSession.remove(groupId)
            // C1 — canonical address → stored txids, for the members whose key memo MAY have been sent.
            val unconfirmed = mutableMapOf<String, List<String>>()
            // Round 8 D8 (F3): this rotation's first refusal no balance can fix; its caller reads and removes it.
            rotationRefusals.remove(groupId)
            // ZC-082: the record of this rotation (its key number and the member it removes) is written before the key
            // and before anyone is sent it, so a rotation that is cut off (a kill, a closed screen) is completed at
            // the next group open instead of leaving members on a key number this phone never moved to. Refused:
            // nothing is sent or paid, nothing changes, and the user is told.
            val rotationRecord = writeRotationRecord(groupId, newEpoch, kickedAddress)
            if (rotationRecord == RotationRecord.Refused) {
                _groupSendEvent.value = rotationStoreRefused(groupId)
                return@withGroupChange null
            }
            // ZC-778 (a): the new key is on the disk, under its number, BEFORE anyone is sent it. A key the members hold
            // and the creator lost (a refused save, a kill, a closed screen) makes them unreadable to the creator.
            // Refused: nothing is sent or paid, nothing changes, and the user is told.
            if (!storeTook("the new group key") { zchatPreferences.saveGroupKey(groupId, newEpoch, newKeyBase64, durable = true) }) {
                deleteRotationRecord(rotationRecord)
                _groupSendEvent.value = rotationStoreRefused(groupId)
                return@withGroupChange null
            }
            // F8-G: members whose key memo answered PENDING: their rows are left exactly as they are.
            val untouched = mutableSetOf<String>()
            // ZC-757 (G6 step 2): whether the new key was sent (or may have been) to at least one member row.
            var keySentToAny = false
            for (member in recipients) {
                // KI-22: a member whose GROUP_LEAVE was stored while earlier key memos were on their way is not sent the key.
                if (hasLeftNow(groupId, member.address)) {
                    Log.d(TAG, "Rotation: ${member.address.redactAddress()} left meanwhile — not sent the new key")
                    continue
                }
                val sessionKey = deriveSessionKey(member.address)
                val ourPriv = zchatPreferences.getE2EPrivateKey(member.address)
                if (sessionKey == null || ourPriv == null) {
                    // GR-OPEN G2 (SPEC §2.2): a member of an all-Open group (no session, no E2E key of ours, a relay key)
                    // gets the new key as a sealed control, over the relays only: never built or paid on-chain.
                    if (sealedRouteEligible(member.address)) {
                        // ZC-055: a member who left while its sealed key waited (the debug hold) is not sent the key.
                        val stillWanted = { !hasLeftNow(groupId, member.address) }
                        val acked =
                            sendSealedKey(
                                groupId,
                                member.address,
                                adminAddress,
                                kickedAddress,
                                newEpoch,
                                newKeyBase64,
                                stillWanted = stillWanted,
                                // ZC-420-G P-G3: marked like the signed key copy (the removed address for a removal).
                                relaySlot = RelayCopySlots.groupKey(groupId, member.address),
                                relayExtra = if (kickedAddress != null) "$newEpoch|$kickedAddress" else "$newEpoch",
                            )
                        if (!acked && !stillWanted()) continue
                        noteSealedKeyResult(groupId, member.address, acked)
                        if (acked) keySentToAny = true
                        if (acked) continue
                        if (!sealedKeyRouteOpen(groupId, member.address, kickedAddress)) noSession.add(member.address)
                        failed.add(member.address)
                        continue
                    }
                    Log.w(TAG, "No KEX session with ${member.address.redactAddress()} — can't send verifiable control msg")
                    noSession.add(member.address)
                    failed.add(member.address)
                    continue
                }
                // Wrap the new key for THIS member, then sign the canonical payload (which includes that
                // per-member wrapped key) with our per-peer key so only the real admin's copy verifies.
                val memo = if (kickedAddress != null) {
                    removalNoticeMemo(groupId, kickedAddress, adminAddress, newEpoch, newKeyBase64, sessionKey, ourPriv)
                } else {
                    val wrapped = E2EEncryption.encrypt(newKeyBase64, sessionKey)
                    val signedData = ZMSGGroupProtocol.groupKeySignedData(groupId, adminAddress, newEpoch, wrapped, "rotation")
                    val sig = E2EEncryption.sign(ourPriv, signedData)
                    ZMSGGroupProtocol.createGroupKeyMessage(groupId, adminAddress, newEpoch, wrapped, sig)
                }
                // R4-2: a member the prompt quoted as free is never paid for on-chain here; a failed free
                // delivery leaves them owed the key (pendingKeyEpoch), which a later, freshly quoted
                // key-sync repairs.
                // B1 (GPT-6 Q3): only a send that PROVABLY sent nothing leaves the member owed. A memo whose
                // transaction may still be broadcast is not owed: the follow-up repair would pay for it again.
                // C1: it is recorded as unconfirmed instead, with the ids of its stored transactions.
                val onMaybeSent = { txIds: List<String> -> unconfirmed[zchatPreferences.resolvePeerAddress(member.address)] = txIds }
                val outcome =
                    sendGroupMemoWithRetry(
                        member.address,
                        memo,
                        adminAddress,
                        onMaybeSent,
                        onRefused = { rotationRefusals.putIfAbsent(groupId, it) },
                        // ZC-420-G: this member's key copy among the relay-copy marks: the key number, and the removed
                        // address when this copy is a removal notice (a repair re-delivers a removal as a removal).
                        relaySlot = RelayCopySlots.groupKey(groupId, member.address),
                        relayExtra = if (kickedAddress != null) "$newEpoch|$kickedAddress" else "$newEpoch",
                        // F8-G: the key memo's attempt in the ledger (the first memo of a new key number).
                        attempt =
                            GroupSendAttemptRules.key(
                                SendAttemptKind.KEY_MEMO,
                                groupId,
                                zchatPreferences.resolvePeerAddress(member.address),
                                newEpoch,
                            ),
                        // ZC-055: a member who left while this memo waited (a block, the relays) is not sent the new key.
                        stillWanted = { !hasLeftNow(groupId, member.address) },
                    ) { mayPayOnChain(member.address) }
                if (outcome == GroupMemoOutcome.NOTHING_SENT) failed.add(member.address)
                if (outcome == GroupMemoOutcome.SENT || outcome == GroupMemoOutcome.MAYBE_SENT) keySentToAny = true
                // F8-G: PENDING is on MAYBE_SENT's side (not failed, so not owed); the unresolved attempt owns the member.
                if (outcome == GroupMemoOutcome.PENDING) untouched.add(zchatPreferences.resolvePeerAddress(member.address))
            }

            // D3 — classify the fan-out (pure, unit-tested): who is still OWED the new key. Persisted
            // on the roster as the OPTIONAL pendingKeyEpoch field (absent = up to date, back-compat)
            // so retryPendingGroupKeys / the GROUP_KEY_REQUEST pull can repair it later. The kicked
            // member was filtered out of recipients above and can never appear here.
            val outcome =
                ZMSGGroupProtocol.rotationDeliveryOutcome(recipients, newEpoch) { it.address !in failed }
            val pendingCanon = outcome.pending.map { zchatPreferences.resolvePeerAddress(it.address) }.toSet()
            val deliveredCanon = outcome.delivered.map { zchatPreferences.resolvePeerAddress(it.address) }.toSet()

            // Update our own state: drop the kicked member from the roster (EVERY row that
            // canonicalizes to them, so fan-out stops paying to message any drifted representation),
            // record per-member key-delivery state — the roster now writes on the rotate-only path
            // too — and adopt the new key + epoch so our subsequent sends use it.
            // KI-22: applied to the roster as stored NOW (re-read under the group lock), never to the copy read at lock
            // entry, so a GROUP_LEAVE / GROUP_ACCEPT that ChatViewModel stored while the key memos were on their way is
            // kept. Only this rotation's own changes are written: a row that is LEFT now stays as it is (a member who
            // left meanwhile is not recorded as keyed), and a row that holds the key now but was not a recipient (an
            // invitee who accepted meanwhile) is owed the new key.
            val recipientCanon = recipients.map { zchatPreferences.resolvePeerAddress(it.address) }.toSet()
            val saved =
                withGroupLock(groupId) {
                    val updated =
                        (storedRoster(groupId) ?: members).map { member ->
                            val canon = zchatPreferences.resolvePeerAddress(member.address)
                            when {
                                kickedCanonical != null && canon == kickedCanonical -> member.copy(status = MemberStatus.LEFT)
                                member.status == MemberStatus.LEFT -> member
                                canon in untouched -> member
                                // C1: every recipient row is rewritten, so an older unconfirmed record never outlives this epoch.
                                canon in pendingCanon -> member.copy(pendingKeyEpoch = newEpoch, pendingKeyTxIds = null)
                                canon in deliveredCanon -> member.copy(pendingKeyEpoch = null, pendingKeyTxIds = unconfirmed[canon])
                                canon !in recipientCanon &&
                                    ZMSGGroupProtocol.holdsGroupKey(member) &&
                                    !zchatPreferences.isSelfAddress(member.address) ->
                                    member.copy(pendingKeyEpoch = newEpoch, pendingKeyTxIds = null)
                                else -> member
                            }
                        }
                    val updatedJson = ZMSGGroupProtocol.serializeGroupMembers(updated)
                    if (kickedCanonical == null) {
                        zchatPreferences.saveGroupMembers(groupId, updatedJson)
                        true
                    } else {
                        // ZC-690: record this removal before its notice can suspend, in the roster's own durable edit.
                        val state = announceState(groupId)
                        val entry = state.members[kickedCanonical] ?: MemberAnnounceEntry()
                        val removed =
                            entry.copy(leftAtMs = System.currentTimeMillis(), removedAtEpoch = newEpoch, leftBy = ActivatedBy.AUTH)
                        try {
                            zchatPreferences.saveGroupMembersDurably(
                                groupId,
                                updatedJson,
                                state.copy(members = state.members + (kickedCanonical to removed)),
                            )
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "Removal commit failed: ${e.javaClass.simpleName}")
                            false
                        }
                    }
                }
            if (!saved) {
                Log.w(TAG, "Removal roster and record did not reach the disk — stopping before its notice")
                // ZC-778 (c): the remaining members were already sent the new key, so the creator moves to its number
                // (the key is on the disk since before the fan-out) and the user is told. No notice and no announce: the
                // removal is not recorded on this phone, and removing the member again takes the next free number.
                storeTook("the group key number") { zchatPreferences.setGroupKeyEpoch(groupId, newEpoch, durable = true) }
                // ZC-757 (G1, G6): the new key's number is on the disk: a rebuilt group's new key exists. ZC-1069: only
                // when it is (as the normal path below checks); a refused number leaves the group needing its new key.
                if (zchatPreferences.getGroupKeyEpoch(groupId) >= newEpoch) {
                    markRebuiltProgress(groupId, rekeyed = true, reached = keySentToAny)
                }
                // ZC-791 (ZC-778's remainder): the other members hold the new key and enforce its number as the removal
                // floor, so this phone adopts the floor too: an old-key message needs a verified author here as well.
                storeTook("the removal floor") { zchatPreferences.setGroupRemovalFloor(groupId, newEpoch, scannedTip()) }
                // ZC-791: this state has its own sentence. Key messages were sent (and the on-chain ones paid), the
                // member is still in the group on this phone, and removing again is a new, separately asked action.
                _groupSendEvent.value =
                    GroupSendResult.RemovalIncomplete(
                        groupId,
                        pendingMembers = 0,
                        refusal = co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.GROUP_REMOVAL_NOT_SAVED,
                    )
                // ZC-082: this rotation ended with its own sentence; it is not completed later (the removal is asked again).
                deleteRotationRecord(rotationRecord)
                return@withGroupChange null
            }
            if (kickedAddress != null) {
                // D4 — advance the removal frontier: the kicked member's retained key is from BELOW
                // newEpoch, so processGroupMsg requires verified authorship for pre-floor messages.
                // Round 9 R9-3: the creator's own removal counts from the height its phone has scanned to now (every
                // transaction below it was mined before this removal existed); null when unknown (today's behaviour).
                zchatPreferences.setGroupRemovalFloor(groupId, newEpoch, scannedTip())
            }
            // ZC-778 (d): the key was committed before the fan-out; its number follows it, a checked commit too. A
            // refused number is logged and the run goes on: the roster, the removal record and the key are on the disk.
            storeTook("the group key number") { zchatPreferences.setGroupKeyEpoch(groupId, newEpoch, durable = true) }
            // ZC-757 (G1, G6): written after the number; a kill in between costs one more key change.
            if (zchatPreferences.getGroupKeyEpoch(groupId) >= newEpoch) {
                markRebuiltProgress(groupId, rekeyed = true, reached = keySentToAny)
            }
            // ZC-082: the roster, the removal and the key number are written: the rotation's record has done its work.
            // (When the number was refused the record stays, and the next group open completes the rotation.)
            if (zchatPreferences.getGroupKeyEpoch(groupId) >= newEpoch) deleteRotationRecord(rotationRecord)
            // One entry per member, and not a member another of whose rows was sent the key.
            val keyedCanon =
                recipients
                    .filter { it.address !in noSession }
                    .map { zchatPreferences.resolvePeerAddress(it.address) }
                    .toSet()
            rotationNoSession[groupId] =
                NoSessionRows(
                    rows = noSession.toList(),
                    members =
                        noSession
                            .distinctBy { zchatPreferences.resolvePeerAddress(it) }
                            .filterNot { zchatPreferences.resolvePeerAddress(it) in keyedCanon },
                )
            // Round 8 P6 (PHONE_TRIAGE F5b): now that the removal is committed, tell the removed member (no key).
            if (kickedAddress != null) {
                val noticeAddress = removalNoticeRow(members, kickedAddress)
                if (noticeAddress != null) {
                    sendRemovalNotice(groupId, kickedAddress, adminAddress, newEpoch, noticeAddress, mayPayOnChain)
                } else {
                    // GR-OPEN G2 (finding 4a): no session row, so the removed member is told with a sealed kick naming it,
                    // with no key, over the relays only.
                    sealedRemovalNoticeRow(members, kickedAddress)?.let { row ->
                        sendSealedRemovalNotice(groupId, kickedAddress, adminAddress, newEpoch, row)
                    }
                }
            }
            afterCommit?.invoke(newEpoch)
            failed
        }
    }

    /**
     * D3 push retry — re-send the CURRENT group key to every ACTIVE member still owed it
     * ([GroupMember.pendingKeyEpoch] != null), as a SIGNED GROUP_KEY (reason="resync") wrapped under
     * their KEX session. IDEMPOTENT: the receiver's epoch-monotonicity replay guard no-ops a
     * duplicate, so re-sending is always safe. Clears the pending marker per member on success.
     * Admin-only (silent no-op otherwise). Serialized against kick/rotate via the D5 [rotationLock]
     * so it can't race an epoch read→commit, and against itself via a process-wide in-flight guard.
     */
    fun retryPendingGroupKeys(groupId: String) {
        viewModelScope.launch { retryPendingGroupKeysInternal(groupId) }
    }

    /**
     * MONEY SAFETY (A2):
     * - [automatic] (opening the group screen): an on-chain re-send is paid only with the handshake-spend
     *   consent, asked before every on-chain attempt. The user-initiated routes (the admin's sync-keys tap,
     *   the kick / rotate follow-up) are their own consent. Without it the member stays owed; a NOSTR
     *   delivery is still tried, since it is free.
     * - Each member is recorded as repaired (pendingKeyEpoch cleared) BEFORE its paid send, as a KEXACK marker
     *   is: the repository broadcasts in a detached scope, so a back-out mid-broadcast must not leave the
     *   member owed and pay again on the next open. The record is restored only when nothing was sent
     *   (NOTHING_SENT, or a failure other than cancellation).
     * - B1: that record is written with a checked commit() and nothing is paid when it does not reach the
     *   disk, so a kill after the broadcast cannot make the restarted app pay the same key again.
     * - Round 8 D11 (Grok CLI review, G4): it is written right before the attempt that is really handed to the SDK
     *   (the send's `beforeSubmit`), not before the send, so a kill while the send waits for a block (nothing built
     *   or sent yet) leaves the member owed on disk; the restore after a proven non-send is a checked commit() too,
     *   so a kill right after it does not leave the disk saying the key was delivered. A key delivered free over
     *   NOSTR (no submit) is recorded after its delivery.
     */
    private suspend fun retryPendingGroupKeysInternal(
        groupId: String,
        automatic: Boolean = false,
        /**
         * R4-2 — the quote this retry runs under, when it was started by a user action that showed a cost
         * (the admin's sync-keys tap, a kick / rotate follow-up). Only the members that quote counted as
         * on-chain may be paid for; everyone else gets the free Nostr delivery or stays owed. null = no
         * quote (the legacy always-pay behaviour).
         */
        quoted: QuotedFanOut? = null,
        // GR-OPEN G2: only the owed members with no session that the sealed route reaches (before a group message).
        sealedOnly: Boolean = false,
        // F8-G: the Resend of a "Key not delivered" badge: only this member, whatever its row says it is owed, with the
        // current key, and with no roster write at all (the roster never said the key was owed).
        onlyMember: String? = null,
        // F8-G: the marker of the failed key attempt that Resend was confirmed for.
        retryOf: Long? = null,
    ) {
        val adminAddress = _currentUserAddress.value ?: return
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
        val amAdmin = zchatPreferences.isGroupSelfCreated(groupId) ||
            (info?.creatorAddress?.let { zchatPreferences.isSelfAddress(it) } ?: false)
        if (!amAdmin) return
        // ZC-757 (G1; review round 2, finding 3): no owed key goes out under an old key in a rebuilt group that still needs
        // its new key, from any caller (the sync tap, Resend key, the pass on open, a rotation's follow-up).
        if (RebuiltGroupKey.stage(info) == RebuiltGroupKey.Stage.NEEDS_KEY) return
        val resend = onlyMember != null
        // Double-trigger guard (kick flow + loadGroupDetail both schedule this) — one pass at a time. A Resend has its
        // own process-wide guard and waits for the group's change lock instead.
        if (!resend && !keyRetriesInFlight.add(groupId)) return
        // KI23-X1: the key-payment claim this Resend holds, given back in the finally below.
        var resendClaim: String? = null
        try {
            withGroupChange(groupId) {
                // ZC-082: a rotation that was cut off is completed first. When its roster could not be saved, no key
                // is sent in this call.
                if (healInterruptedRotation(groupId) == Heal.REFUSED) return
                val membersJson = zchatPreferences.getGroupMembers(groupId) ?: return
                val members = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)
                val currentEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
                val keyBase64 = zchatPreferences.getGroupKey(groupId, currentEpoch) ?: return
                // KI23-X1: a Resend sends only under the member's key-payment claim, the one the automatic answer to the
                // member's key request takes. While that answer runs (block waits included) the Resend sends nothing,
                // so the two never both pay and neither puts back a paid count the other relies on.
                if (onlyMember != null) {
                    val claim = GroupKeyGrantPolicy.grantKey(groupId, zchatPreferences.resolvePeerAddress(onlyMember), currentEpoch)
                    if (!GroupKeyPaymentClaim.tryTake(claim)) {
                        Log.w(TAG, "Resend key: the member's key is being paid by its automatic answer; nothing sent")
                        return
                    }
                    resendClaim = claim
                }
                // Owed = ACTIVE ∧ pendingKeyEpoch != null (pure, unit-tested): deliveredTo is derived
                // from the persisted markers — rows WITHOUT one are up to date.
                val owed =
                    if (onlyMember != null) {
                        val canon = zchatPreferences.resolvePeerAddress(onlyMember)
                        members
                            .filter { zchatPreferences.resolvePeerAddress(it.address) == canon && ZMSGGroupProtocol.holdsGroupKey(it) }
                            .sortedByDescending { it.address == onlyMember }
                            .take(1)
                    } else {
                        ZMSGGroupProtocol.membersOwedKey(
                            roster = members,
                            deliveredTo = members.filter { it.pendingKeyEpoch == null }.map { it.address },
                            newEpoch = currentEpoch,
                            canonicalize = zchatPreferences::resolvePeerAddress,
                        )
                    }
                if (owed.isEmpty()) return
                Log.d(TAG, "D3 retry: re-sending epoch-$currentEpoch key to ${owed.size} owed member(s) in $groupId")
                val mayPayFor: (String) -> Boolean =
                    when {
                        automatic -> { _ -> zchatPreferences.hasConsentedToHandshakeSpend() }
                        // R4-2: under a quote, only the members the prompt counted as on-chain.
                        quoted != null -> { address -> quoted.mayPayFor(address) }
                        else -> { _ -> true }
                    }
                for (member in owed) {
                    // KI-22: a member whose GROUP_LEAVE was stored while earlier re-sends were on their way is not sent the key.
                    if (hasLeftNow(groupId, member.address)) continue
                    val mayPayOnChain: () -> Boolean = { mayPayFor(member.address) }
                    val sessionKey = deriveSessionKey(member.address)
                    val ourPriv = zchatPreferences.getE2EPrivateKey(member.address)
                    if (sealedOnly && sessionKey != null && ourPriv != null) continue
                    // F8-G: the Resend re-sends a key message that was built under a session; without one it sends nothing.
                    if (resend && (sessionKey == null || ourPriv == null)) continue
                    if (sessionKey == null || ourPriv == null) {
                        // GR-OPEN G2 (SPEC §2.2): an owed member of an all-Open group gets the current key as a sealed
                        // GROUP_KEY, free over the relays only (never built or paid on-chain, so KI-23's caps do not
                        // apply); recorded as delivered only after a relay ack.
                        if (sealedRouteEligible(member.address)) {
                            val acked =
                                sendSealedKey(
                                    groupId,
                                    member.address,
                                    adminAddress,
                                    null,
                                    currentEpoch,
                                    keyBase64,
                                    reason = "resync",
                                    // ZC-055: a member who left while this key waited is not sent it.
                                    stillWanted = { !hasLeftNow(groupId, member.address) },
                                    // ZC-420-G P-G3: this member's key copy among the relay-copy marks.
                                    relaySlot = RelayCopySlots.groupKey(groupId, member.address),
                                    relayExtra = "$currentEpoch",
                                )
                            noteSealedKeyResult(groupId, member.address, acked)
                            // ZC-1069: a relay acknowledged it, so the current key reached a member row.
                            if (acked) markOwedKeyReached(groupId, GroupMemoOutcome.SENT)
                            // ZC-289: a checked commit(), as every delivery recorded as done; not saved = still owed.
                            if (acked &&
                                !recordKeyOwed(groupId, zchatPreferences.resolvePeerAddress(member.address), durable = true) { null }
                            ) {
                                Log.w(TAG, "Sealed key delivered to ${member.address.redactAddress()}; its record was not saved to disk")
                            }
                        }
                        continue // still no KEX — next trigger retries
                    }
                    val memo = resyncKeyMemo(groupId, adminAddress, currentEpoch, keyBase64, sessionKey, ourPriv)
                    val canonical = zchatPreferences.resolvePeerAddress(member.address)
                    // F8-G: this key message in the ledger. A Resend carries the kind and the marker of the failed
                    // attempt its badge stood for (a key message or a key grant); when that state is gone, nothing is sent.
                    val keyTag = keyAttemptTag(groupId, canonical, currentEpoch, retryOf) ?: continue
                    // F8G-X1, KI23-X1: read again under the claim: the other key kind of this member and number on its
                    // way or mined delivers this key, so the Resend sends nothing.
                    if (resend && keySiblingDelivers(keyTag)) {
                        Log.d(TAG, "Resend key: the member's key is on its way or mined by the other kind; nothing sent")
                        continue
                    }
                    // ZC-055: a member who left while this memo waited (a block, the relays) is not sent the key.
                    val stillWanted = { !hasLeftNow(groupId, member.address) }
                    if (!mayPayOnChain()) {
                        // No consent to pay: free NOSTR delivery only, and nothing to stamp until it lands.
                        val freeOutcome =
                            sendGroupMemoWithRetry(
                                member.address,
                                memo,
                                adminAddress,
                                // ZC-420-G: this member's key copy among the relay-copy marks.
                                relaySlot = RelayCopySlots.groupKey(groupId, member.address),
                                relayExtra = "$currentEpoch",
                                attempt = keyTag,
                                stillWanted = stillWanted,
                                mayPayOnChain = { false },
                            )
                        // ZC-289: a checked commit(): a record lost in a kill would leave the member owed on disk, and the
                        // next automatic pass under consent, with the relays down, would pay to send this key again.
                        val delivered = !resend && freeOutcome == GroupMemoOutcome.SENT
                        if (delivered && !recordKeyOwed(groupId, canonical, durable = true) { null }) {
                            Log.w(TAG, "Key delivered to ${member.address.redactAddress()}; its record was not saved to disk")
                        }
                        // ZC-1069: the current key reached a member row (see markOwedKeyReached).
                        markOwedKeyReached(groupId, freeOutcome)
                        continue
                    }
                    var storedTxIds: List<String>? = null
                    // D11 (G4): set once this member's stamp is on disk, right before the attempt is handed to the SDK.
                    var stamped = false
                    // KI-23: set once THIS Resend raised the member's paid count for the current key from 0 to 1.
                    var resendCountStamped = false
                    val grantKey = GroupKeyGrantPolicy.grantKey(groupId, canonical, currentEpoch)
                    val outcome =
                        try {
                            sendGroupMemoWithRetry(
                                member.address,
                                memo,
                                adminAddress,
                                { storedTxIds = it },
                                // ZC-420-G: this member's key copy among the relay-copy marks.
                                relaySlot = RelayCopySlots.groupKey(groupId, member.address),
                                relayExtra = "$currentEpoch",
                                attempt = keyTag,
                                stillWanted = stillWanted,
                                // ZC-1251: the pass nobody tapped pays at most the one handshake every question quotes.
                                // Above it nothing is stamped or sent: the member stays owed for a later pass.
                                priceLimit = if (automatic) KexAnswerPrice(onChainSendMinimumCost()) else null,
                                mayPayOnChain = mayPayOnChain,
                                // Stamp on disk right before the submit (B1, GPT-6 Q4; D11 G4): it is the only record
                                // that stops the next group open (possibly after a kill) from paying this key again.
                                // When it cannot be saved, this attempt is not submitted: nothing is paid, the member
                                // stays owed and a later pass tries again.
                                // F8-G: a Resend stamps nothing in the roster: it never said this key was owed.
                                // KI-23 (D2): a Resend that pays closes the automatic door behind it: the member's
                                // paid count for the current key goes from 0 to 1 here, checked, so a later key
                                // request of that member is answered over the relays only. Nothing refuses the tap:
                                // not the count (a 1 is left as it is), not the daily budget, not the Settings switch
                                // (KI23-X1: only the claim above, while the automatic answer itself is paying).
                                // A count that cannot be saved submits nothing, as an owed-key stamp that cannot.
                                beforeSubmit = {
                                    if (resend && zchatPreferences.getGroupKeyGrantPaidCount(grantKey) == 0) {
                                        if (!zchatPreferences.setGroupKeyGrantPaidCount(grantKey, 1)) {
                                            Log.w(TAG, "Paid count for ${member.address.redactAddress()} not saved — key NOT re-sent")
                                            throw OwedKeyStampNotSavedException()
                                        }
                                        resendCountStamped = true
                                    }
                                    if (!resend) {
                                        if (!recordKeyOwed(groupId, canonical, durable = true) { null }) {
                                            Log.w(
                                                TAG,
                                                "Owed-key stamp for ${member.address.redactAddress()} not saved — key NOT re-sent, still owed"
                                            )
                                            throw OwedKeyStampNotSavedException()
                                        }
                                        stamped = true
                                    }
                                },
                            )
                        } catch (e: Exception) {
                            if (!mayReArmHandshakeMarker(e)) throw e // may already be on the wire: keep it
                            Log.e(TAG, "Key re-send to ${member.address.redactAddress()} failed", e)
                            GroupMemoOutcome.NOTHING_SENT
                        }
                    // B1 (GPT-6 Q3): re-arm ONLY when nothing was sent. A transaction the SDK stored and may still
                    // broadcast (MAYBE_SENT) keeps the stamp, so the next group open does not pay for it again.
                    when (outcome) {
                        GroupMemoOutcome.NOTHING_SENT -> {
                            // Nothing was sent: the member is owed again, exactly as before this attempt. D11 (G4): on
                            // disk, so a kill right after this cannot leave the disk saying the key was delivered.
                            // F8-G: a Resend wrote no stamp, so it has nothing to put back.
                            val reArmed =
                                resend ||
                                    recordKeyOwed(groupId, canonical, durable = true) { row ->
                                        members.firstOrNull { it.address == row.address }?.pendingKeyEpoch
                                    }
                            if (!reArmed) Log.w(TAG, "Owed-key re-arm for ${member.address.redactAddress()} not saved to disk")
                            // KI-23: proven nothing sent: the count this Resend raised goes back to 0 (only its own 1).
                            if (resendCountStamped &&
                                zchatPreferences.getGroupKeyGrantPaidCount(grantKey) == 1 &&
                                !zchatPreferences.setGroupKeyGrantPaidCount(grantKey, 0)
                            ) {
                                Log.w(TAG, "Paid count for ${member.address.redactAddress()} not put back; this key stays relay-only")
                            }
                        }
                        // C1: keep the stamp, and keep the stored transaction ids for a later reconciliation.
                        GroupMemoOutcome.MAYBE_SENT -> if (!resend) recordKeyTxIds(groupId, canonical, storedTxIds.orEmpty())
                        GroupMemoOutcome.SENT ->
                            // D11 (G4): delivered free over NOSTR (no submit, so no stamp yet): the member is up to date.
                            if (!resend && !stamped && !recordKeyOwed(groupId, canonical, durable = true) { null }) {
                                Log.w(TAG, "Key delivered to ${member.address.redactAddress()}; its record was not saved to disk")
                            }
                        // F8-G: an earlier attempt of this key to this member is unresolved or waits for Resend. Nothing
                        // was built or stamped (the gate comes before the stamp): the row stays exactly as it is.
                        GroupMemoOutcome.PENDING -> Unit
                    }
                    // ZC-1069: the current key reached a member row (see markOwedKeyReached).
                    markOwedKeyReached(groupId, outcome)
                }
            }
        } finally {
            if (!resend) keyRetriesInFlight.remove(groupId)
            resendClaim?.let { GroupKeyPaymentClaim.release(it) }
        }
    }

    /**
     * F8G-X1, KI23-X1: whether the other key kind of [tag]'s member and key number (a key grant for a key message, a key
     * message for a key grant) is on its way or mined, which delivers the same key. A ledger that cannot be read answers
     * yes (nothing is sent); with no ledger there is nothing to read.
     */
    private fun keySiblingDelivers(tag: SendAttemptTag): Boolean {
        val ledger = sendAttempts?.ledger ?: return false
        val siblingKey = GroupSendAttemptRules.siblingKey(tag.kind, tag.subject, tag.identity) ?: return false
        val sibling = runCatching { ledger.get(siblingKey) }.getOrElse { return true }
        return sibling != null && sibling.state != SendAttemptState.NOT_SENT
    }

    /**
     * F8-G: the ledger tag of a key message to [memberCanon] at [epoch]. Without [retryOf] it is a KEY_MEMO attempt (a
     * first send, or one that meets PENDING on an entry of its key). With [retryOf] it carries the kind of the failed
     * entry that marker belongs to (the key message's, or a key grant's), or is null when no such failed entry stands
     * any more: the offer is gone and nothing is sent.
     */
    private fun keyAttemptTag(
        groupId: String,
        memberCanon: String,
        epoch: Int,
        retryOf: Long?,
    ): SendAttemptTag? {
        if (retryOf == null) return GroupSendAttemptRules.key(SendAttemptKind.KEY_MEMO, groupId, memberCanon, epoch)
        val ledger = sendAttempts?.ledger ?: return null
        return listOf(SendAttemptKind.KEY_MEMO, SendAttemptKind.KEY_GRANT)
            .map { GroupSendAttemptRules.key(it, groupId, memberCanon, epoch, retryOf) }
            .firstOrNull { tag -> runCatching { ledger.offerStartedAt(tag.key) }.getOrNull() == retryOf }
    }

    // ── F8-G: the per-member Resend of a key message and of a message copy (SPEC §3.5, §4.5, §4.7) ─────────────────────

    /** F8-G: the entries of the send-attempt ledger, or none when there is no ledger or it cannot be read. */
    private fun ledgerEntries(): List<SendAttemptEntry> = runCatching { sendAttempts?.ledger?.all() }.getOrNull().orEmpty()

    /**
     * F8-G — "Key not delivered", per member row address: the members whose key message (or key grant) for the CURRENT
     * key number was proven never to have reached the chain ([GroupSendAttemptRules.keyOffers]), while this phone
     * holds that key. Derived from the ledger at each load; the roster is not written.
     */
    private fun keyNotDelivered(
        groupId: String,
        members: List<GroupMember>,
    ): Map<String, GroupResendOffer> {
        if (sendAttempts == null) return emptyMap()
        val epoch = zchatPreferences.getGroupKeyEpoch(groupId)
        if (zchatPreferences.getGroupKey(groupId, epoch) == null) return emptyMap()
        val holders =
            members.filter { ZMSGGroupProtocol.holdsGroupKey(it) && !zchatPreferences.isSelfAddress(it.address) }
        val offers =
            GroupSendAttemptRules.keyOffers(
                ledgerEntries(),
                groupId,
                epoch,
                holders.mapTo(HashSet()) { zchatPreferences.resolvePeerAddress(it.address) },
                groupResendsInFlight.toSet(),
            )
        return offersByRow(holders, offers)
    }

    /**
     * F8-G — "Message not delivered", per member row address: for each ACTIVE member, the oldest copy of one of our
     * group messages that was proven never to have reached the chain ([GroupSendAttemptRules.messageOffers]).
     */
    private fun messageNotDelivered(
        groupId: String,
        members: List<GroupMember>,
    ): Map<String, GroupResendOffer> {
        if (sendAttempts == null) return emptyMap()
        val active = members.filter { it.status == MemberStatus.ACTIVE && !zchatPreferences.isSelfAddress(it.address) }
        val offers =
            GroupSendAttemptRules.messageOffers(
                ledgerEntries(),
                groupId,
                active.mapTo(HashSet()) { zchatPreferences.resolvePeerAddress(it.address) },
                { epoch -> zchatPreferences.getGroupKey(groupId, epoch) != null },
                groupResendsInFlight.toSet(),
            )
        return offersByRow(active, offers)
    }

    /** One row per member (the first whose canonical address has an offer). */
    private fun offersByRow(
        rows: List<GroupMember>,
        offers: Map<String, GroupResendOffer>,
    ): Map<String, GroupResendOffer> {
        val byRow = LinkedHashMap<String, GroupResendOffer>()
        val taken = HashSet<String>()
        for (row in rows) {
            val canon = zchatPreferences.resolvePeerAddress(row.address)
            val offer = offers[canon] ?: continue
            if (taken.add(canon)) byRow[row.address] = offer
        }
        return byRow
    }

    /**
     * F8-G: whether [offer] is still what [memberAddress]'s "Key not delivered" badge says: the entry of that key is
     * still the failed state with that marker, it is a key message or key grant of this group for this member and for
     * the CURRENT key number, this phone holds that key, and the member still holds the group key.
     *
     * F8G-X1: and the other key kind of the same member and key number (a key grant for a key message, a key message
     * for a key grant) is neither on its way nor mined: that delivery brings the same key, so the badge is out of date
     * (the rule [GroupSendAttemptRules.keyOffers] draws the badge with). A ledger that cannot be read answers no.
     */
    private fun keyOfferStands(
        groupId: String,
        memberAddress: String,
        offer: GroupResendOffer,
    ): Boolean {
        val ledger = sendAttempts?.ledger ?: return false
        val entry = runCatching { ledger.get(offer.key) }.getOrNull() ?: return false
        val siblingKey = GroupSendAttemptRules.siblingKey(entry.kind, entry.subject, entry.identity) ?: return false
        val sibling = runCatching { ledger.get(siblingKey) }.getOrElse { return false }
        if (sibling != null && sibling.state != SendAttemptState.NOT_SENT) return false
        val canon = zchatPreferences.resolvePeerAddress(memberAddress)
        val epoch = zchatPreferences.getGroupKeyEpoch(groupId)
        return entry.state == SendAttemptState.NOT_SENT &&
            entry.startedAtMillis == offer.startedAt &&
            GroupSendAttemptRules.isKeyKind(entry.kind) &&
            entry.subject == GroupSendAttemptRules.memberSubject(groupId, canon) &&
            entry.identity == GroupSendAttemptRules.epochIdentity(epoch) &&
            zchatPreferences.getGroupKey(groupId, epoch) != null &&
            withGroupLock(groupId) {
                storedRoster(groupId).orEmpty().any {
                    zchatPreferences.resolvePeerAddress(it.address) == canon && ZMSGGroupProtocol.holdsGroupKey(it)
                }
            }
    }

    /**
     * F8-G (SPEC §4.7) — Resend on a member's "Key not delivered" badge, on the creator's phone. [offer] is what the
     * badge was drawn from. A badge that is out of date (sent again from another screen, mined late, delivered another
     * way, the key number moved on, the member left) reloads the screen and asks nothing. Otherwise the price of ONE
     * key message to this member is asked ([PaidAction.GROUP_KEY_SYNC]); on confirm the state is read again and the
     * CURRENT key is sent once, against exactly that failed attempt. Nothing is written to the roster.
     */
    fun resendKeyToMember(
        groupId: String,
        memberAddress: String,
        offer: GroupResendOffer,
    ) {
        if (_currentUserAddress.value == null || !isGroupAdmin(groupId)) return
        if (!keyOfferStands(groupId, memberAddress, offer)) {
            Log.d(TAG, "Resend key: the badge is out of date — nothing asked, nothing sent")
            loadGroupSettings(groupId)
            return
        }
        askForQuotedPlan(PaidAction.GROUP_KEY_SYNC, { listOf(memberAddress) }) { quoted ->
            viewModelScope.launch {
                // One Resend of a key at a time, for the whole process (two screens are two ViewModels).
                if (!groupResendsInFlight.add(offer.key)) {
                    Log.d(TAG, "Resend key: already running for this member — ignoring")
                    return@launch
                }
                try {
                    loadGroupSettings(groupId)
                    if (keyOfferStands(groupId, memberAddress, offer)) {
                        retryPendingGroupKeysInternal(groupId, quoted = quoted, onlyMember = memberAddress, retryOf = offer.startedAt)
                    } else {
                        Log.d(TAG, "Resend key: the failed attempt is gone or replaced — nothing sent")
                    }
                } finally {
                    groupResendsInFlight.remove(offer.key)
                    loadGroupSettings(groupId)
                }
            }
        }
    }

    /** F8-G: our own message's text and the sender string its first copies carried. */
    private class OwnMessageText(
        val sender: String,
        val text: String,
    )

    /**
     * F8-G (ZC-619; SPEC §4.5) — the text of our own group message ([seq], [epoch]) for a Resend, read by identity from
     * the STORED rows of the group (not by a row id: the row the send wrote is replaced by the history scan's rows; and
     * not from the screen's list, which is collapsed to one row per identity). The candidates are the rows filed under
     * one of our addresses with that sequence number and key number that have a decrypted text; held rows are left out.
     *
     * One text or nothing: a row is filed under the sender its memo CLAIMS, and a holder of the group key can post
     * under our address on-chain. When the candidates do not all hold the same text, or there is none, this returns
     * null and the Resend sends nothing: ZChat never signs and pays for a text it cannot tell is the user's.
     */
    private fun ownMessageText(
        groupId: String,
        epoch: Int,
        seq: Long,
    ): OwnMessageText? {
        val candidates =
            zchatPreferences
                .getGroupMessages(groupId)
                ?.let { parseStoredGroupMessages(it) }
                .orEmpty()
                .filter { !it.held && it.seq == seq && it.epoch == epoch && it.decryptedContent != null }
                .filter { zchatPreferences.isSelfAddress(it.senderAddress) }
        val texts = candidates.mapNotNull { it.decryptedContent }.distinct()
        if (texts.size != 1) {
            Log.w(
                TAG,
                "Resend: the stored rows of our message $seq (key $epoch) hold ${texts.size} texts — refused, nothing sent",
            )
            return null
        }
        return OwnMessageText(candidates.first().senderAddress, texts.single())
    }

    /**
     * F8-G: whether [offer] is still what [memberAddress]'s "Message not delivered" badge says; then the key number and
     * the sequence number of that message. Null when the entry is gone or different, the member is no longer ACTIVE,
     * the key of that message is gone, or its text cannot be told ([ownMessageText]).
     */
    private fun messageCopyOffer(
        groupId: String,
        memberAddress: String,
        offer: GroupResendOffer,
    ): Pair<Int, Long>? {
        val entry = runCatching { sendAttempts?.ledger?.get(offer.key) }.getOrNull() ?: return null
        val canon = zchatPreferences.resolvePeerAddress(memberAddress)
        if (entry.state != SendAttemptState.NOT_SENT || entry.startedAtMillis != offer.startedAt) return null
        if (entry.kind != SendAttemptKind.GROUP_MESSAGE || entry.subject != GroupSendAttemptRules.memberSubject(groupId, canon)) return null
        val copy = GroupSendAttemptRules.messageOf(entry.identity) ?: return null
        if (!stillActive(groupId, memberAddress) || zchatPreferences.getGroupKey(groupId, copy.first) == null) return null
        if (ownMessageText(groupId, copy.first, copy.second) == null) return null
        return copy
    }

    /**
     * F8-G (SPEC §4.5) — Resend on a member's "Message not delivered" badge: the same rules as the 1:1 Retry. [offer]
     * is what the badge was drawn from. A badge that is out of date, a member who left or was removed, a missing key
     * or a text that cannot be told reloads the screen and asks nothing. Otherwise the price of ONE message to this
     * member is asked ([PaidAction.GROUP_MESSAGE]); on confirm the state is read again and that one copy is sent once,
     * against exactly that failed attempt.
     */
    fun resendMessageCopy(
        groupId: String,
        memberAddress: String,
        offer: GroupResendOffer,
    ) {
        if (_currentUserAddress.value == null) return
        if (messageCopyOffer(groupId, memberAddress, offer) == null) {
            Log.d(TAG, "Resend message: the badge is out of date or the copy cannot be sent again — nothing asked")
            loadGroupSettings(groupId)
            return
        }
        askForQuotedPlan(PaidAction.GROUP_MESSAGE, { listOf(memberAddress) }) { quoted ->
            viewModelScope.launch {
                if (!groupResendsInFlight.add(offer.key)) {
                    Log.d(TAG, "Resend message: already running for this copy — ignoring")
                    return@launch
                }
                try {
                    loadGroupSettings(groupId)
                    val copy = messageCopyOffer(groupId, memberAddress, offer)
                    if (copy != null) {
                        sendMessageCopies(groupId, copy.first, copy.second, listOf(memberAddress), quoted, offer.startedAt)
                    } else {
                        Log.d(TAG, "Resend message: the failed attempt is gone or replaced — nothing sent")
                    }
                } finally {
                    groupResendsInFlight.remove(offer.key)
                    loadGroupSettings(groupId)
                }
            }
        }
    }

    /**
     * F8-G (SPEC §4.5) — send our message ([seq], [epoch]) again to [members], one memo each, in today's GROUP_MSG
     * layout: the stored text and sender string, the same sequence number and key number, the key of that number, a
     * fresh nonce, and the member's pairwise signature. Receivers that already hold the message drop the copy by its
     * (sender, seq, epoch). Paid on-chain only for a member [quoted] counted, and only against the failed attempt
     * [retryOf] names (the ledger refuses anything else). Nothing is stored or drawn: the message's row is the one the
     * first send left.
     */
    private suspend fun sendMessageCopies(
        groupId: String,
        epoch: Int,
        seq: Long,
        members: List<String>,
        quoted: QuotedFanOut,
        retryOf: Long?,
    ) {
        val payer = _currentUserAddress.value ?: return
        for (member in members) {
            // ZC-420-G: the one builder of a copy of our message, shared with the relay-copy repair.
            val memo = groupMessageCopyMemo(groupId, epoch, seq, member) ?: return
            val canonical = zchatPreferences.resolvePeerAddress(member)
            val outcome =
                sendGroupMemoWithRetry(
                    member,
                    memo,
                    payer,
                    // ZC-420-G: this copy among the relay-copy marks, as the fan-out names it (the canonical member).
                    relaySlot = RelayCopySlots.groupMessage(groupId, canonical, epoch, seq),
                    attempt = GroupSendAttemptRules.message(groupId, canonical, epoch, seq, retryOf),
                    // ZC-055: only while the member is still in the group.
                    stillWanted = { stillActive(groupId, member) },
                ) {
                    quoted.mayPayFor(member)
                }
            Log.d(TAG, "Resend of message $seq to ${member.redactAddress()}: $outcome")
        }
    }

    // ── ZC-420-G: group relay copies only a public relay took (ZC-420-SPEC §3.5, §3.6) ─────────────────────────────────
    //
    // A group memo the relays took is delivered for the money decision (nothing here changes that). When no relay every
    // ZChat version reads took it, the funnel marks it (NostrChatBridge's relay-copy marks); a group open confirms the
    // copy from its kept wrap, or builds it again and publishes it over the relays, free. These functions call the bridge
    // only: nothing here builds a proposal or asks a price.

    /** ZC-420-G: what one group open may still try ([MAX_RELAY_COPY_REPAIRS_PER_OPEN] publish attempts). */
    private class RelayRepairBudget(
        private var left: Int = MAX_RELAY_COPY_REPAIRS_PER_OPEN,
    ) {
        fun take(): Boolean = (left > 0).also { if (it) left-- }
    }

    /** ZC-420-G: the copy of message [seq] (key [epoch]) to [recipient] (canonical, as the fan-out names it) is marked. */
    private fun recipientCopyMarked(
        groupId: String,
        recipient: String,
        epoch: Int,
        seq: Long,
    ): Boolean =
        NostrChatBridge
            .relayCopyExtra(RelayCopySlots.groupMessage(groupId, recipient, epoch, seq)) != null

    /** ZC-420-G: the RelayUnconfirmed result naming [members] as the group screens name them, or null for none. */
    private fun relayUnconfirmedResult(
        groupId: String,
        members: List<String>,
        atOpen: Boolean = false,
    ): GroupSendResult.RelayUnconfirmed? {
        if (members.isEmpty()) return null
        val roster =
            zchatPreferences
                .getGroupMembers(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                .orEmpty()
        val book = nameBook()
        val names =
            members
                .distinctBy { zchatPreferences.resolvePeerAddress(it) }
                .map { member ->
                    val canonical = zchatPreferences.resolvePeerAddress(member)
                    roster.firstOrNull { zchatPreferences.resolvePeerAddress(it.address) == canonical }?.let { memberName(it, book) }
                        ?: PeerName.label(member, book.nicknames, book.contacts, ::nameKey)
                }
        return GroupSendResult.RelayUnconfirmed(groupId, names, atOpen)
    }

    /** ZC-420-G: the INVITED rows of [members] whose invite only a public relay took ("Invited, not confirmed"). */
    private fun inviteUnconfirmed(
        groupId: String,
        members: List<GroupMember>,
    ): Set<String> {
        if (!NostrChatBridge.relayCopyMarksReady()) return emptySet()
        return members
            .filter { it.status == MemberStatus.INVITED }
            .filter {
                NostrChatBridge
                    .relayCopyExtra(RelayCopySlots.groupInvite(groupId, it.address)) != null
            }.map { it.address }
            .toSet()
    }

    /** ZC-420-G: [memberAddress]'s row is INVITED and its invite only a public relay took. */
    private fun inviteRelayUnconfirmed(
        groupId: String,
        memberAddress: String,
    ): Boolean {
        val slot = RelayCopySlots.groupInvite(groupId, memberAddress)
        if (NostrChatBridge.relayCopyExtra(slot) == null) return false
        return withGroupLock(groupId) {
            storedRoster(groupId).orEmpty().any { it.address == memberAddress && it.status == MemberStatus.INVITED }
        }
    }

    /**
     * ZC-420-G (SPEC §3.6 item 5) — Resend on an "Invited, not confirmed" row: the invite's kept wrap is sent again, or,
     * when nothing is kept, one invite at the current key goes over the relays. Never on-chain and never a price
     * question; the stored status is left as it is. With a Keystone account selected nothing is published (the chat is
     * closed there) and the existing refusal is shown.
     */
    private fun resendUnconfirmedInvite(
        groupId: String,
        memberAddress: String,
    ) {
        val inviterAddress = _currentUserAddress.value ?: return
        val inFlightKey = "$groupId|$memberAddress"
        if (!resendInvitesInFlight.add(inFlightKey)) {
            Log.d(TAG, "resendInvite: already in flight for ${memberAddress.redactAddress()} — ignoring")
            return
        }
        viewModelScope.launch {
            try {
                if (keystoneAccountSelected()) {
                    _groupSendEvent.value =
                        GroupSendResult.InviteFailed(
                            groupId,
                            listOf(memberAddress),
                            co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.GROUP_KEYSTONE_REFUSED,
                        )
                    return@launch
                }
                withGroupChange(groupId) {
                    val slot = RelayCopySlots.groupInvite(groupId, memberAddress)
                    val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
                    val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
                    val encodedGroupKey = zchatPreferences.getGroupKey(groupId, keyEpoch)
                    val relayKey = findPeerNostrPubkeyAnyRep(memberAddress)
                    // ZC-897: never rebuilt for a contact whose key exchange waits for Accept.
                    if (info == null || encodedGroupKey == null || relayKey == null || inviteWaitsForAccept(memberAddress)) {
                        Log.w(TAG, "Resend of a not-confirmed invite: nothing to send it with now — nothing sent")
                        return@withGroupChange
                    }
                    val answer =
                        NostrChatBridge.repairRelayCopy(slot, relayKey) {
                            buildCompactInviteMemo(
                                groupId = groupId,
                                groupName = info.name,
                                inviterAddress = inviterAddress,
                                keyEpoch = keyEpoch,
                                encodedGroupKey = encodedGroupKey,
                                memberAddress = memberAddress,
                            )
                        }
                    Log.d(TAG, "Resend of a not-confirmed invite to ${memberAddress.redactAddress()}: $answer")
                }
                loadGroupSettings(groupId, repairInvites = false)
            } finally {
                resendInvitesInFlight.remove(inFlightKey)
            }
        }
    }

    /**
     * ZC-420-G (SPEC §3.5 item 4, §3.6) — at a group open: the copies of this group only a public relay took are
     * confirmed from their kept wraps or delivered again over the relays, free: first the keys and removals (the
     * creator's phone), then the invites (confirmed only, never a new invite), then the messages, oldest first. At most
     * [MAX_RELAY_COPY_REPAIRS_PER_OPEN] publish attempts. Nothing without a publisher or with a Keystone account
     * selected. With [raiseAtOpen], while a message copy is still not confirmed, the members it is for are named
     * (RelayUnconfirmed with atOpen), unless another result is waiting.
     */
    private suspend fun repairUnconfirmedGroupCopies(
        groupId: String,
        raiseAtOpen: Boolean,
    ) {
        val bridge = NostrChatBridge
        val slots = RelayCopySlots
        if (!bridge.relayCopyMarksReady() || !bridge.isOutboundReady()) return
        val keys = bridge.unconfirmedRelayCopies(slots.ofGroup(slots.GROUP_KEY, groupId))
        val invites = bridge.unconfirmedRelayCopies(slots.ofGroup(slots.GROUP_INVITE, groupId))
        val messages = bridge.unconfirmedRelayCopies(slots.ofGroup(slots.GROUP_MESSAGE, groupId))
        val removals = bridge.unconfirmedRelayCopies(slots.ofGroup(slots.GROUP_REMOVAL, groupId))
        if (keys.isEmpty() && invites.isEmpty() && messages.isEmpty() && removals.isEmpty()) return
        if (keystoneAccountSelected()) return
        val budget = RelayRepairBudget()
        try {
            if (keys.isNotEmpty()) repairUnconfirmedGroupKeys(groupId, keys, budget)
            if (removals.isNotEmpty()) repairUnconfirmedGroupRemovals(groupId, removals, budget)
            if (invites.isNotEmpty()) repairUnconfirmedGroupInvites(groupId, invites, budget)
            if (messages.isNotEmpty()) repairUnconfirmedGroupMessages(groupId, messages, budget)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Group ${groupId.take(12)}: relay copies not repaired (${e.javaClass.simpleName})")
        }
        if (!raiseAtOpen || _groupSendEvent.value != null) return
        val left = bridge.unconfirmedRelayCopies(slots.ofGroup(slots.GROUP_MESSAGE, groupId))
        if (left.isEmpty()) return
        val members =
            left
                .mapNotNull { parseRelayMessageSlot(groupId, it)?.first }
                .distinct()
                .filter { member -> activeMemberNow(groupId, member) && findPeerNostrPubkeyAnyRep(member) != null }
        val notice = relayUnconfirmedResult(groupId, members, atOpen = true) ?: return
        if (_groupSendEvent.value == null) _groupSendEvent.value = notice
    }

    /** ZC-420-G: (member, key number, sequence number) of a message copy's slot, or null when it is not one of [groupId]. */
    private fun parseRelayMessageSlot(
        groupId: String,
        slot: String,
    ): Triple<String, Int, Long>? {
        val slots = RelayCopySlots
        val prefix = slots.ofGroup(slots.GROUP_MESSAGE, groupId)
        if (!slot.startsWith(prefix)) return null
        val rest = slot.removePrefix(prefix)
        val seq = rest.substringAfterLast('|').toLongOrNull() ?: return null
        val head = rest.substringBeforeLast('|', "")
        val epoch = head.substringAfterLast('|').toIntOrNull() ?: return null
        val member = head.substringBeforeLast('|', "")
        return if (member.isEmpty()) null else Triple(member, epoch, seq)
    }

    /** ZC-420-G: [member] has an ACTIVE row in [groupId], matched by its canonical address as it resolves now. */
    private fun activeMemberNow(
        groupId: String,
        member: String,
    ): Boolean {
        val canonical = zchatPreferences.resolvePeerAddress(member)
        return withGroupLock(groupId) {
            storedRoster(groupId).orEmpty().any {
                it.status == MemberStatus.ACTIVE && zchatPreferences.resolvePeerAddress(it.address) == canonical
            }
        }
    }

    /**
     * ZC-420-G (SPEC §3.6 item 2) — the key copies ("gk"), on the creator's phone, under the group's change lock. A copy
     * is forgotten when its key number is not the group's now, the member left or holds no key, the member is owed a key
     * again (the owed-key pass, which ran just before, owns that re-send), or there is no session to sign it with and the
     * sealed route (GR-OPEN G2, [sealedKeyRouteOpen]) does not reach the member either (ZC-420-G P-G3: a member with no
     * session gets the copy sealed, over the relays only); it is kept, untried, while the member has no relay key.
     * Otherwise it is confirmed or sent again: a removal's copy as the
     * removal notice of the current key while the removed person has no ACTIVE or INVITED row (a member that got the key
     * first could never apply the removal afterwards), else as the plain key ("resync").
     */
    private suspend fun repairUnconfirmedGroupKeys(
        groupId: String,
        slots: List<String>,
        budget: RelayRepairBudget,
    ) {
        if (!isGroupAdmin(groupId)) return
        val adminAddress = _currentUserAddress.value ?: return
        val bridge = NostrChatBridge
        withGroupChange(groupId) {
            val epoch = zchatPreferences.getGroupKeyEpoch(groupId)
            val keyBase64 = zchatPreferences.getGroupKey(groupId, epoch)
            val roster = withGroupLock(groupId) { storedRoster(groupId).orEmpty() }
            for (slot in slots) {
                val extra = bridge.relayCopyExtra(slot) ?: continue
                val row = roster.firstOrNull { RelayCopySlots.groupKey(groupId, it.address) == slot }
                val current =
                    keyBase64 != null &&
                        extra.substringBefore('|').toIntOrNull() == epoch &&
                        row != null &&
                        ZMSGGroupProtocol.holdsGroupKey(row) &&
                        row.pendingKeyEpoch == null &&
                        !hasLeftNow(groupId, row.address)
                val sessionKey = row?.takeIf { current }?.let { deriveSessionKey(it.address) }
                val ourPriv = row?.takeIf { current }?.let { zchatPreferences.getE2EPrivateKey(it.address) }
                val removed = extra.substringAfter('|', "").ifEmpty { null }?.takeIf { stillRemoved(roster, it) }
                // ZC-420-G P-G3 (phone): a member with no session gets the copy in its sealed form (GR-OPEN G2), only while
                // the sealed route reaches it and never to a relay key of the removed member or of a LEFT row.
                val signed = sessionKey != null && ourPriv != null
                val sealed = current && !signed && sealedKeyRouteOpen(groupId, row.address, removed)
                if (!current || (!signed && !sealed)) {
                    bridge.forgetRelayCopy(slot)
                    continue
                }
                val relayKey = findPeerNostrPubkeyAnyRep(row.address) ?: continue
                if (!budget.take()) break
                val answer =
                    bridge.repairRelayCopy(slot, relayKey) {
                        when {
                            sessionKey != null && ourPriv != null && removed != null ->
                                removalNoticeMemo(groupId, removed, adminAddress, epoch, keyBase64, sessionKey, ourPriv)
                            sessionKey != null && ourPriv != null ->
                                resyncKeyMemo(groupId, adminAddress, epoch, keyBase64, sessionKey, ourPriv)
                            removed != null ->
                                ZMSGGroupProtocol.createGroupKickMessage(groupId, removed, adminAddress, epoch, null, "", keyBase64)
                            else ->
                                ZMSGGroupProtocol.createGroupKeyMessage(groupId, adminAddress, epoch, "", "", "resync", keyBase64)
                        }
                    }
                Log.d(TAG, "Relay copy of a group key to ${row.address.redactAddress()}: $answer")
            }
        }
    }

    /**
     * ZC-420-G R-1 (the sealed route) — the sealed removal notices ("gr"), on the creator's phone, under the group's change
     * lock. A mark is forgotten when it cannot be read back, the removal no longer stands ([stillRemoved]: the person has
     * an ACTIVE or INVITED row again), or the row it went to can no longer take a sealed control ([sealedRouteEligible]:
     * a key exchange came meanwhile, or it has no relay key); otherwise the same notice is confirmed from its kept wrap
     * or sent again: a GROUP_KICK naming the removed member at the key number of the removal, with no key, no `gk` and
     * "sig": "", over the relays only. Nothing is built for a payment and no key ever goes to the removed member.
     */
    private suspend fun repairUnconfirmedGroupRemovals(
        groupId: String,
        slots: List<String>,
        budget: RelayRepairBudget,
    ) {
        if (!isGroupAdmin(groupId)) return
        val adminAddress = _currentUserAddress.value ?: return
        val bridge = NostrChatBridge
        val prefix = RelayCopySlots.ofGroup(RelayCopySlots.GROUP_REMOVAL, groupId)
        withGroupChange(groupId) {
            val roster = withGroupLock(groupId) { storedRoster(groupId).orEmpty() }
            for (slot in slots) {
                val extra = bridge.relayCopyExtra(slot) ?: continue
                val noticeAddress = slot.removePrefix(prefix)
                val epoch = extra.substringBefore('|').toIntOrNull()
                val removed = extra.substringAfter('|', "").ifEmpty { null }
                if (noticeAddress.isEmpty() ||
                    epoch == null ||
                    removed == null ||
                    !stillRemoved(roster, removed) ||
                    !sealedRouteEligible(noticeAddress)
                ) {
                    bridge.forgetRelayCopy(slot)
                    continue
                }
                val relayKey = findPeerNostrPubkeyAnyRep(noticeAddress) ?: continue
                if (!budget.take()) break
                val answer =
                    bridge.repairRelayCopy(slot, relayKey) {
                        ZMSGGroupProtocol.createGroupKickMessage(groupId, removed, adminAddress, epoch, null, "")
                    }
                Log.d(TAG, "Relay copy of a removal notice to ${noticeAddress.redactAddress()}: $answer")
            }
        }
    }

    /** ZC-420-G: no ACTIVE or INVITED row of [roster] is [removedAddress] (canonical match): the removal still stands. */
    private fun stillRemoved(
        roster: List<GroupMember>,
        removedAddress: String,
    ): Boolean {
        val canonical = zchatPreferences.resolvePeerAddress(removedAddress)
        return roster.none {
            (it.status == MemberStatus.ACTIVE || it.status == MemberStatus.INVITED) &&
                zchatPreferences.resolvePeerAddress(it.address) == canonical
        }
    }

    /**
     * ZC-420-G (SPEC §3.6 item 4) — the invite copies ("gi"): forgotten when the row is no longer INVITED; else confirmed
     * from the kept wrap only. Nothing is rebuilt: a new invite is the creator's choice (the free Resend). True when a
     * mark went.
     */
    private suspend fun repairUnconfirmedGroupInvites(
        groupId: String,
        slots: List<String>,
        budget: RelayRepairBudget,
    ): Boolean {
        val bridge = NostrChatBridge
        val roster = withGroupLock(groupId) { storedRoster(groupId).orEmpty() }
        var changed = false
        for (slot in slots) {
            val row = roster.firstOrNull { RelayCopySlots.groupInvite(groupId, it.address) == slot }
            if (row == null || row.status != MemberStatus.INVITED) {
                bridge.forgetRelayCopy(slot)
                changed = true
                continue
            }
            val relayKey = findPeerNostrPubkeyAnyRep(row.address) ?: continue
            if (!budget.take()) break
            val answer = bridge.repairRelayCopy(slot, relayKey) { null }
            if (answer == RelayRepair.CONFIRMED) changed = true
        }
        return changed
    }

    /** ZC-420-G: Group Settings confirms the not-confirmed invites from their kept wraps; true when a mark went. */
    private suspend fun repairUnconfirmedGroupInvitesNow(groupId: String): Boolean {
        val bridge = NostrChatBridge
        val slots = RelayCopySlots
        if (!bridge.relayCopyMarksReady() || !bridge.isOutboundReady()) return false
        val invites = bridge.unconfirmedRelayCopies(slots.ofGroup(slots.GROUP_INVITE, groupId))
        if (invites.isEmpty() || keystoneAccountSelected()) return false
        return try {
            repairUnconfirmedGroupInvites(groupId, invites, RelayRepairBudget())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Group ${groupId.take(12)}: invite copies not repaired (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * ZC-420-G (SPEC §3.5 item 4) — the message copies ("gm"), oldest first: forgotten when this phone is out of the
     * group, the member has no ACTIVE row (matched by canonical address as it resolves now), the key of that number is
     * gone, or our text of that message cannot be told; kept, untried, while the member has no relay key; else confirmed
     * or sent again as the same message (same text, sequence number and key number; [groupMessageCopyMemo]).
     */
    private suspend fun repairUnconfirmedGroupMessages(
        groupId: String,
        slots: List<String>,
        budget: RelayRepairBudget,
    ) {
        val bridge = NostrChatBridge
        val inGroup = isInGroup(groupId)
        for (slot in slots) {
            val copy = parseRelayMessageSlot(groupId, slot)
            val wanted =
                inGroup &&
                    copy != null &&
                    activeMemberNow(groupId, copy.first) &&
                    zchatPreferences.getGroupKey(groupId, copy.second) != null &&
                    ownMessageText(groupId, copy.second, copy.third) != null
            if (!wanted) {
                bridge.forgetRelayCopy(slot)
                continue
            }
            val (member, epoch, seq) = copy
            val relayKey = findPeerNostrPubkeyAnyRep(member) ?: continue
            if (!budget.take()) break
            val answer = bridge.repairRelayCopy(slot, relayKey) { groupMessageCopyMemo(groupId, epoch, seq, member) }
            Log.d(TAG, "Relay copy of message $seq to ${member.redactAddress()}: $answer")
        }
    }

    /**
     * F8-G (SPEC §4.5), ZC-420-G — a copy of our message ([seq], key [epoch]) for [member]: today's GROUP_MSG layout with
     * the stored text and sender string ([ownMessageText]), the same sequence number and key number, the key of that
     * number, a fresh nonce and the member's pairwise signature. Null when the text cannot be told or the key is gone.
     * Receivers that hold the message already drop the copy by its (sender, seq, epoch).
     */
    private fun groupMessageCopyMemo(
        groupId: String,
        epoch: Int,
        seq: Long,
        member: String,
    ): String? {
        val own = ownMessageText(groupId, epoch, seq) ?: return null
        val groupKey = zchatPreferences.getGroupKey(groupId, epoch)?.let { ZMSGGroupProtocol.decodeGroupKey(it) } ?: return null
        val memberPriv = zchatPreferences.getE2EPrivateKey(member)
        return ZMSGGroupProtocol.createGroupMsgMessage(
            groupId = groupId,
            seq = seq,
            epoch = epoch,
            senderAddress = own.sender,
            plaintext = own.text,
            groupKey = groupKey,
            signer =
                memberPriv?.let { priv ->
                    { signedData: String -> runCatching { E2EEncryption.sign(priv, signedData) }.getOrNull() }
                },
        )
    }

    /**
     * D3, ZC-420-G — the signed GROUP_KEY "resync" of key [epoch] ([keyBase64]) for one member: the key wrapped under
     * our session key with them, signed with our pairwise private key. Shared by the owed-key pass and the relay-copy
     * repair.
     */
    @Suppress("LongParameterList")
    private fun resyncKeyMemo(
        groupId: String,
        adminAddress: String,
        epoch: Int,
        keyBase64: String,
        sessionKey: ByteArray,
        ourPrivateKey: String,
    ): String {
        val wrapped = E2EEncryption.encrypt(keyBase64, sessionKey)
        val signedData = ZMSGGroupProtocol.groupKeySignedData(groupId, adminAddress, epoch, wrapped, "resync")
        val sig = E2EEncryption.sign(ourPrivateKey, signedData)
        return ZMSGGroupProtocol.createGroupKeyMessage(groupId, adminAddress, epoch, wrapped, sig, "resync")
    }

    /**
     * #187, ZC-420-G — the signed GROUP_KICK of [removedAddress] at key [epoch] for one remaining member: the key
     * ([keyBase64]) wrapped under our session key with them, signed over groupKickSignedData with our pairwise private
     * key. Shared by the removal (rotateAndNotify) and the relay-copy repair, so a re-delivered removal is the removal
     * 2.12.4 already applies.
     */
    @Suppress("LongParameterList")
    private fun removalNoticeMemo(
        groupId: String,
        removedAddress: String,
        adminAddress: String,
        epoch: Int,
        keyBase64: String,
        sessionKey: ByteArray,
        ourPrivateKey: String,
    ): String {
        val wrapped = E2EEncryption.encrypt(keyBase64, sessionKey)
        val signedData = ZMSGGroupProtocol.groupKickSignedData(groupId, removedAddress, adminAddress, epoch, wrapped)
        val sig = E2EEncryption.sign(ourPrivateKey, signedData)
        return ZMSGGroupProtocol.createGroupKickMessage(groupId, removedAddress, adminAddress, epoch, wrapped, sig)
    }

    // ── ZC-082: the record of a key rotation in progress, and its completion after a cut-off ─────────────────────────

    /** What [writeRotationRecord] did. */
    private sealed interface RotationRecord {
        /** No ledger: nothing is recorded, as before F8-G. */
        data object None : RotationRecord

        /** The record did not reach the store: the rotation does not start. */
        data object Refused : RotationRecord

        class Written(
            val entry: SendAttemptEntry,
        ) : RotationRecord
    }

    /**
     * ZC-082 — writes "a rotation of [groupId] to key number [epoch] is in progress" (and the member it removes) into
     * the send-attempt ledger, a checked write, before the key is saved or sent to anyone.
     */
    private fun writeRotationRecord(
        groupId: String,
        epoch: Int,
        kickedAddress: String?,
    ): RotationRecord {
        val ledger = sendAttempts?.ledger ?: return RotationRecord.None
        val entry =
            GroupSendAttemptRules.rotationEntry(
                groupId,
                GroupSendAttemptRules.Rotation(epoch, kickedAddress),
                GroupSendAttemptRules.nextMarker(),
            )
        return if (runCatching { ledger.writeAhead(entry) }.getOrDefault(false)) RotationRecord.Written(entry) else RotationRecord.Refused
    }

    /** ZC-082 — removes a rotation's record (a failed removal is tried again by [healInterruptedRotation], which then only deletes). */
    private fun deleteRotationRecord(record: RotationRecord) {
        if (record !is RotationRecord.Written) return
        val gone = runCatching { sendAttempts?.ledger?.delete(record.entry.key, record.entry.startedAtMillis) }.getOrNull() == true
        if (!gone) Log.w(TAG, "Rotation: its record could not be removed yet (the next group open removes it)")
    }

    /**
     * ZC-082 — [healInterruptedRotation] at a group open, only while no change of the group is running (a running
     * rotation is not an interrupted one, and the screen never waits for it here).
     */
    private fun healInterruptedRotationIfIdle(groupId: String) {
        if (sendAttempts == null) return
        val lock = rotationLock(groupId)
        if (!lock.tryLock()) return
        try {
            healInterruptedRotation(groupId)
        } finally {
            lock.unlock()
        }
    }

    /** What [healInterruptedRotation] did. */
    private enum class Heal {
        /** There was nothing to complete (or only a leftover record to remove). */
        NONE,

        /** An interrupted rotation was completed. */
        DONE,

        /** The roster (or the key number) of an interrupted rotation could not be saved: nothing else was written. */
        REFUSED,
    }

    /**
     * ZC-082 — completes a rotation of [groupId] that this phone started and did not finish (the process was killed or
     * the screen closed after some members were sent the new key and before the roster and the key number were
     * written). Call under [withGroupChange] only, so no rotation of the group is running. The creator's phone only.
     *
     * For each rotation record of the group:
     * - its key is not stored: nothing was sent under that number (the key is saved before it is sent, ZC-778): the
     *   record is removed;
     * - the group is already at (or past) its number: the rotation finished and only the record was left: removed;
     * - otherwise the rotation's own commit is applied, in this order: (1) ONE durable roster edit: every row of the
     *   removed member is LEFT, with the removal record, and every other key holder is owed the new key, except the
     *   members that have a ledger entry for a key message of that number (on its way, mined, or failed with its own
     *   badge), which are written up to date (F8G-X3: an older owed mark is cleared),
     *   [GroupSendAttemptRules.healedRoster]; refused: nothing else is written, the record stays and [Heal.REFUSED]
     *   tells the caller to send no key in this call; (2) the removal floor; (3) the key number; (4) the record is
     *   removed. The removed member is never owed or sent the key.
     *
     * It sends and pays nothing. The owed members get the key from the owed-key pass, as after any rotation. The removal
     * notice and the member announces of an interrupted removal are not replayed (a residual of this batch).
     */
    private fun healInterruptedRotation(groupId: String): Heal {
        val ledger = sendAttempts?.ledger ?: return Heal.NONE
        val entries = runCatching { ledger.all() }.getOrNull() ?: return Heal.NONE
        val records = entries.filter { it.kind == SendAttemptKind.ROTATION && it.subject == groupId }
        if (records.isEmpty() || !isGroupAdmin(groupId)) return Heal.NONE
        var result = Heal.NONE
        for (record in records.sortedBy { GroupSendAttemptRules.rotationOf(it)?.epoch ?: 0 }) {
            val rotation = GroupSendAttemptRules.rotationOf(record)
            if (rotation == null ||
                rotation.epoch <= zchatPreferences.getGroupKeyEpoch(groupId) ||
                zchatPreferences.getGroupKey(groupId, rotation.epoch) == null
            ) {
                runCatching { ledger.delete(record.key, record.startedAtMillis) }
                continue
            }
            val epoch = rotation.epoch
            val kickedCanon = rotation.kicked?.let { zchatPreferences.resolvePeerAddress(it) }
            val covered = GroupSendAttemptRules.coveredByKeyEntry(entries, groupId, epoch)
            val saved =
                withGroupLock(groupId) {
                    val roster = storedRoster(groupId) ?: return@withGroupLock false
                    val healed =
                        GroupSendAttemptRules.healedRoster(
                            roster,
                            epoch,
                            kickedCanon,
                            covered,
                            zchatPreferences::resolvePeerAddress,
                            zchatPreferences::isSelfAddress,
                        )
                    val json = ZMSGGroupProtocol.serializeGroupMembers(healed)
                    try {
                        if (kickedCanon == null) {
                            zchatPreferences.saveGroupMembersDurably(groupId, json)
                        } else {
                            // ZC-690: the removal's record goes into the roster's own durable edit.
                            val state = announceState(groupId)
                            val removed =
                                (state.members[kickedCanon] ?: MemberAnnounceEntry())
                                    .copy(leftAtMs = System.currentTimeMillis(), removedAtEpoch = epoch, leftBy = ActivatedBy.AUTH)
                            val withRemoval = state.copy(members = state.members + (kickedCanon to removed))
                            zchatPreferences.saveGroupMembersDurably(groupId, json, withRemoval)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        false
                    }
                }
            if (!saved) {
                Log.w(TAG, "An interrupted rotation of $groupId could not be completed: its roster did not reach the disk")
                return Heal.REFUSED
            }
            if (kickedCanon != null) storeTook("the removal floor") { zchatPreferences.setGroupRemovalFloor(groupId, epoch, scannedTip()) }
            val numbered = storeTook("the group key number") { zchatPreferences.setGroupKeyEpoch(groupId, epoch, durable = true) }
            if (!numbered) return Heal.REFUSED
            runCatching { ledger.delete(record.key, record.startedAtMillis) }
            Log.w("ZCHAT_GROUP", "an interrupted rotation of $groupId was completed (epoch $epoch)")
            result = Heal.DONE
        }
        return result
    }

    /** Round 8 D11 (G4) — the owed-key stamp could not be saved right before its submit; nothing was sent. */
    private class OwedKeyStampNotSavedException : Exception("owed-key stamp not saved before its submit")

    /**
     * C1 — record on every roster row that canonicalizes to [canonical] that its key memo MAY have been sent, with
     * the stored transaction ids. `apply()`: losing it cannot cause a second payment (the row is not owed).
     */
    private fun recordKeyTxIds(
        groupId: String,
        canonical: String,
        txIds: List<String>,
    ) {
        // KI-22: a fresh read-modify-write under the group lock.
        withGroupLock(groupId) {
            val current = storedRoster(groupId) ?: return
            val updated =
                current.map {
                    if (zchatPreferences.resolvePeerAddress(it.address) == canonical) it.copy(pendingKeyTxIds = txIds) else it
                }
            zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
        }
    }

    /**
     * Rewrite the owed-key field of every roster row that canonicalizes to [canonical], reading the roster
     * fresh so earlier per-member writes of the same retry pass are kept.
     *
     * B1 (GPT-6 Q4): [durable] writes with a checked `commit()` and returns whether the roster reached the disk
     * (false too when there is no roster). Otherwise the write is `apply()` and returns true: that is only for
     * re-arming a member as owed, whose loss cannot cause a second payment. ZC-289: every delivery recorded as done
     * (the stamp before a paid submit, a free relay delivery, an acked sealed key) is [durable]; a lost record of
     * one leaves the member owed on disk, and the next automatic pass may pay to send the key again.
     */
    private fun recordKeyOwed(
        groupId: String,
        canonical: String,
        durable: Boolean = false,
        pendingKeyEpoch: (GroupMember) -> Int?,
    ): Boolean =
        // KI-22: a fresh read-modify-write under the group lock.
        withGroupLock(groupId) {
            val current = storedRoster(groupId) ?: return false
            val updated =
                current.map {
                    if (zchatPreferences.resolvePeerAddress(it.address) == canonical) it.copy(pendingKeyEpoch = pendingKeyEpoch(it)) else it
                }
            val json = ZMSGGroupProtocol.serializeGroupMembers(updated)
            if (durable) {
                zchatPreferences.saveGroupMembersDurably(groupId, json)
            } else {
                zchatPreferences.saveGroupMembers(groupId, json)
                true
            }
        }

    /**
     * D3 pull side (#197) — "Tap to sync group keys" used to only reload LOCAL state, sending
     * NOTHING, so a genuinely missing key could never be recovered. Now: if WE are the admin,
     * re-push any owed keys (there is nobody above us to pull from); otherwise send the admin a
     * SIGNED GROUP_KEY_REQUEST carrying the epoch we already hold — the admin authenticates it,
     * authorizes via [ZMSGGroupProtocol.mayGrantKeyRequest] (ACTIVE members only; a kicked member
     * can never pull the post-kick key), and re-sends the CURRENT wrapped GROUP_KEY. Ends with the
     * original local reload either way.
     */
    fun requestGroupKeySync(groupId: String) {
        // ZC-022 (KI-25 R4a): while this phone cannot confirm the group's admin, the admin's signed answer would be
        // refused, so the request could only cost. Nothing is asked, quoted, sent or paid.
        if (adminNotConfirmed(groupId)) {
            // KI-25 R17 (pair 3): an Open group still asks its admin over the relays only (the admin's sealed answer
            // needs no key), free and with no question; never on-chain.
            if (requestKeyOverRelaysOnly(groupId)) return
            Log.w(TAG, "Key sync for $groupId not requested: this phone cannot confirm the group's admin")
            return
        }
        // R2-2 (b): the admin re-sends the key to each member still owed it; a member asks the admin. Ask first.
        // R4-2: the sync then runs that quoted fan-out; a member (or admin) quoted as free is never paid for.
        askForQuotedPlan(PaidAction.GROUP_KEY_SYNC, { keySyncRecipients(groupId) }) { quoted ->
            requestGroupKeySync(groupId, quoted)
        }
    }

    /**
     * R4-2 — the quoted key sync: [quoted] decides which recipients may be paid for on-chain.
     * ZC-022 (KI-25 R3, R4) — on a member's phone the request goes to the one address [quoted] names, never to an
     * address looked up again. When that address is no longer the admin's contact ([adminTarget]), nothing is sent,
     * free or paid, and the tap's question is asked again for the new plan. It is signed with our key for that
     * address, and it goes on-chain only when it was quoted as paid, is signed, and the admin's ZChat is not known to
     * be older than 2.13 ([keyRequestMayGoOnChain]); the free relay copy is tried either way.
     */
    private fun requestGroupKeySync(groupId: String, quoted: QuotedFanOut) {
        viewModelScope.launch {
            val myAddress = _currentUserAddress.value
            val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
            val amAdmin = zchatPreferences.isGroupSelfCreated(groupId) ||
                (info?.creatorAddress?.let { zchatPreferences.isSelfAddress(it) } ?: false)
            when {
                // Round 8 P6: nothing is sent for a group this phone is no longer in.
                myAddress == null || info == null || !info.isActive -> Unit
                amAdmin -> retryPendingGroupKeysInternal(groupId, quoted = quoted)
                // ZC-022 (KI-25 R4a): the admin stopped being confirmed after the question was asked.
                adminNotConfirmed(groupId) ->
                    Log.w(TAG, "GROUP_KEY_REQUEST for $groupId not sent: this phone cannot confirm the group's admin")
                else -> {
                    // ZC-022 (KI-25 R4b): the payee is the plan's.
                    val adminAddress = quoted.recipients.singleOrNull()
                    if (adminAddress == null) {
                        Log.w(TAG, "GROUP_KEY_REQUEST for $groupId not sent: no request that can be answered was quoted")
                    } else if (adminAddress != adminTarget(info)) {
                        Log.w(TAG, "GROUP_KEY_REQUEST for $groupId not sent: the quoted address is no longer the admin's — asking again")
                        requestGroupKeySync(groupId)
                    } else {
                        val haveEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
                        val signedData = ZMSGGroupProtocol.groupKeyRequestSignedData(groupId, myAddress, haveEpoch)
                        // Sign with our pairwise KEX key for the admin (rep-tolerant lookup, #205/#214).
                        // Without a session we send unsigned over the relays only: the NOSTR seal can still
                        // authenticate it, and the admin's phone ignores an unsigned on-chain request (R4d).
                        val sig = keyRequestSigningKey(adminAddress)?.let { E2EEncryption.sign(it, signedData) }.orEmpty()
                        val memo = ZMSGGroupProtocol.createGroupKeyRequestMessage(groupId, myAddress, haveEpoch, sig)
                        // R4-2: an admin quoted as free is never paid for on-chain under that quote.
                        val mayPay = sig.isNotEmpty() && keyRequestMayGoOnChain(adminAddress)
                        val outcome = sendGroupMemoWithRetry(adminAddress, memo, myAddress) { mayPay && quoted.mayPayFor(adminAddress) }
                        if (outcome != GroupMemoOutcome.SENT) {
                            Log.w(TAG, "GROUP_KEY_REQUEST for $groupId could not be sent — will retry on next tap")
                        }
                    }
                }
            }
            loadGroupDetail(groupId, retryOwedKeys = false)
        }
    }

    /**
     * R2-2 (b) — who a key-sync tap sends to: the owed members with a session (admin), or the admin (member).
     * ZC-022 (KI-25 R3, R4) — a member's request goes to the admin's contact ([adminTarget]). Nobody is named while the
     * admin is not confirmed (R4a), and nobody when the request could only go on-chain and must not: the admin's contact
     * cannot be reached free right now and the request cannot be signed or the admin's ZChat is known to be older than
     * 2.13 ([keyRequestMayGoOnChain]). So no price is ever asked for a request that will not be paid for.
     */
    private fun keySyncRecipients(groupId: String): List<String> {
        val myAddress = _currentUserAddress.value ?: return emptyList()
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return emptyList()
        // Round 8 P6: a phone no longer in the group asks nobody for a key (the admin would refuse it anyway).
        if (!info.isActive) return emptyList()
        val amAdmin = zchatPreferences.isGroupSelfCreated(groupId) || zchatPreferences.isSelfAddress(info.creatorAddress)
        if (!amAdmin) {
            if (adminNotConfirmed(groupId)) return emptyList()
            val adminAddress = adminTarget(info)
            val canBeSent =
                reachableFree(adminAddress) || (keyRequestSigningKey(adminAddress) != null && keyRequestMayGoOnChain(adminAddress))
            return listOf(adminAddress).filter { it != myAddress && canBeSent }
        }
        val members =
            zchatPreferences.getGroupMembers(groupId)?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                ?: return emptyList()
        return members
            .filter { it.status == MemberStatus.ACTIVE && it.pendingKeyEpoch != null }
            .map { it.address }
            .filter { deriveSessionKey(it) != null && zchatPreferences.getE2EPrivateKey(it) != null }
    }

    /** ZC-022 (KI-25 R2) — the addresses of the saved contacts, for [GroupAdminCheck]'s one address set; none when unreadable. */
    private fun savedContactAddresses(): List<String> =
        runCatching { contactBook.getAllContacts().map { it.address } }.getOrDefault(emptyList())

    /**
     * ZC-022 (KI-25 R5) — what this phone knows about the key of [groupId]'s admin ([GroupAdminCheck.keyState]), the
     * same reading as ChatViewModel's. Reading it writes nothing.
     */
    fun groupAdminKeyState(groupId: String): AdminKeyState =
        GroupAdminCheck.keyState(zchatPreferences, savedContactAddresses(), groupId)

    /**
     * ZC-022 (KI-25 R4a) and R17 (pair 3) — the sync-keys tap of [groupId]'s chat. While this phone cannot confirm the
     * group's admin the admin's signed answer would be refused, so nothing is asked or paid and Group settings open,
     * where the check is (true). An Open group ([isOpenGroupWithoutCreatorKey]) is the exception: its admin's sealed
     * answer needs no key, so it asks over the relays only, free and with no question ([requestGroupKeySync]).
     * Otherwise the key request as before. @return true when Group settings should open (nothing was requested).
     */
    fun syncKeysTapOpensSettings(groupId: String): Boolean {
        if (GroupAdminCheck.needsConfirmation(groupAdminKeyState(groupId)) && !isOpenGroupWithoutCreatorKey(groupId)) return true
        requestGroupKeySync(groupId)
        return false
    }

    /** KI-25 R17 — [groupId] is active, joined with the creator's plaintext relay invite, and holds no creator key. */
    private fun isOpenGroupWithoutCreatorKey(groupId: String): Boolean {
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return false
        val open = runCatching { zchatPreferences.getGroupAnnounceState(groupId).plainKeyJoin }.getOrDefault(false)
        return open && info.isActive && info.creatorPublicKey.isNullOrEmpty()
    }

    /**
     * KI-25 R17 (pair 3) — the key request of an Open group (joined with the creator's plaintext relay invite, no
     * creator key) that cannot confirm its admin: unsigned, over the relays only, to the stored admin address, with no
     * price question; on-chain never. Reached from the sync-keys tap ([syncKeysTapOpensSettings]).
     * @return false when [groupId] is not such a group (nothing is sent).
     */
    private fun requestKeyOverRelaysOnly(groupId: String): Boolean {
        val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return false
        val myAddress = _currentUserAddress.value
        if (!isOpenGroupWithoutCreatorKey(groupId) || myAddress == null) return false
        viewModelScope.launch {
            val haveEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
            val memo = ZMSGGroupProtocol.createGroupKeyRequestMessage(groupId, myAddress, haveEpoch, "")
            val outcome = sendGroupMemoWithRetry(info.creatorAddress, memo, myAddress) { false }
            if (outcome != GroupMemoOutcome.SENT) Log.w(TAG, "GROUP_KEY_REQUEST for $groupId not delivered over the relays")
        }
        return true
    }

    /**
     * KI-25 R17 (pair 3; ZC-729) — the card ends with `group_admin_no_candidate_open`
     * ([GroupAdminCheck.openGroupAdminElsewhere]).
     */
    fun groupAdminOpenElsewhere(groupId: String): Boolean =
        runCatching { GroupAdminCheck.openGroupAdminElsewhere(zchatPreferences, savedContactAddresses(), groupId) }.getOrDefault(false)

    /** ZC-022 (KI-25 R4a) — this phone cannot match [groupId]'s admin to a key, so no key request is made. */
    private fun adminNotConfirmed(groupId: String): Boolean =
        groupAdminKeyState(groupId).let { it == AdminKeyState.UNKNOWN || it == AdminKeyState.ORPHANED }

    /**
     * ZC-022 (KI-25 R6, R9) — the one line [groupId]'s chat shows about its admin ([GroupAdminCheck.notice]): the check
     * line while this phone cannot confirm the admin, else one of the two catch-up lines, else none. Read from the
     * phone's own data each time (the group screen reads it with every reload, like the cost line); nothing is stored
     * for it and it cannot be hidden. The newest received message is the loaded group screen's.
     */
    fun groupAdminNotice(groupId: String): GroupAdminNotice? {
        val ownAddress = _currentUserAddress.value
        val newestReceived =
            (_groupDetailState.value as? GroupDetailState.Success)
                ?.conversation
                ?.takeIf { it.groupInfo.groupId == groupId }
                ?.messages
                ?.filter { !it.held && it.senderAddress != ownAddress && !zchatPreferences.isSelfAddress(it.senderAddress) }
                ?.maxByOrNull { it.timestamp }
        return GroupAdminCheck.notice(zchatPreferences, savedContactAddresses(), groupId, newestReceived?.isUndecryptable == true)
    }

    private val _groupAdminCandidate = MutableStateFlow<AdminCandidate?>(null)

    /**
     * ZC-022 (KI-25 R7) — the one chat the phone's own records tie to the creation of the group
     * [loadGroupAdminCandidate] last looked at, or null: there is no list and no pick.
     */
    val groupAdminCandidate: StateFlow<AdminCandidate?> = _groupAdminCandidate.asStateFlow()

    private val _groupAdminCandidateChecked = MutableStateFlow<String?>(null)

    /**
     * ZC-022 (KI-25 R7) — the id of the group whose lookup [loadGroupAdminCandidate] has finished, so the card says
     * "no record" only after the phone's records were read, never while they are being read.
     */
    val groupAdminCandidateChecked: StateFlow<String?> = _groupAdminCandidateChecked.asStateFlow()

    /**
     * ZC-022 (KI-25 R7) — look for the chat a user may confirm as [groupId]'s admin, for the card in Group settings.
     * Source 1, the invitation: the chat whose session unwraps the invitation that created the group on this phone
     * ([GroupAdminCheck.invitationCandidate]). The wallet's history is read only while the group's state is UNKNOWN or
     * ORPHANED, and only the memos of the transactions at the group's creation time. When that names no chat, the chat
     * at the admin's address (source 2, pair 2; [GroupAdminCheck.recordedCandidate]); never an address alias (KI-25 D3,
     * ZC-953 reverted). Reads only: nothing is written, sent or paid.
     */
    fun loadGroupAdminCandidate(groupId: String) {
        viewModelScope.launch {
            val info = zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
            if (info == null || !adminNotConfirmed(groupId)) {
                _groupAdminCandidate.value = null
                _groupAdminCandidateChecked.value = groupId
                return@launch
            }
            val memos = GroupAdminCheck.creationTimeMemos(transactionRepository, info)
            val saved = savedContactAddresses()
            val invited = GroupAdminCheck.invitationCandidate(zchatPreferences, saved, info, memos, ::deriveSessionKey)
            // R7: the invitation, else the admin's address (pair 2); never an alias (D3).
            _groupAdminCandidate.value =
                GroupAdminCheck.recordedCandidate(zchatPreferences, saved, info, invited)?.let { (address, source) ->
                    val book = nameBook()
                    val name = PeerName.name(address, book.nicknames, book.contacts, ::nameKey)
                    GroupAdminCheck.candidate(zchatPreferences, address, name, source)
                }
            _groupAdminCandidateChecked.value = groupId
        }
    }

    /** ZC-022 (KI-25 R3) — where this phone's requests to the admin of [info]'s group go ([GroupAdminCheck.adminTarget]). */
    private fun adminTarget(info: GroupInfo): String = GroupAdminCheck.adminTarget(zchatPreferences, savedContactAddresses(), info)

    /** Our key for the chat with [adminAddress], which a key request to it is signed with (rep-tolerant, #205/#214), or null. */
    private fun keyRequestSigningKey(adminAddress: String): String? =
        zchatPreferences.getE2EPrivateKey(adminAddress)
            ?: zchatPreferences.getE2EPrivateKey(zchatPreferences.resolvePeerAddress(adminAddress))

    /**
     * ZC-022 (KI-25 R4c) — false when the SR-C1 record of [adminAddress] says its ZChat is older than 2.13: that
     * version has no key-request handler and cannot see an on-chain message from 2.13, so the request is never paid
     * for. With no record, or a 2.13 record, true.
     */
    private fun keyRequestMayGoOnChain(adminAddress: String): Boolean =
        runCatching { zchatPreferences.getPeerIronwoodCap(adminAddress) }.getOrNull()?.capIronwood != false

    /**
     * Reset create group state.
     */
    fun resetCreateGroupState() {
        _createGroupState.value = CreateGroupState()
    }

    // ==========================================
    // MESSAGING
    // ==========================================

    /**
     * Send a message to a group.
     */
    fun sendGroupMessage(groupId: String, message: String) {
        if (message.isBlank()) return

        val senderAddress = _currentUserAddress.value ?: return

        // R5-1 — the composer stops saving drafts at the send tap and hands the text here, and the quote
        // below can sit on screen for as long as the user wants. Persist the text FIRST, so backing out of
        // the prompt (or a kill under it) cannot take the message with it. A delivered send clears it.
        zchatPreferences.setGroupDraft(groupId, message)

        // Round 8 P6 (PHONE_TRIAGE F5b): a group this phone was removed from (or left) sends nothing. Nothing is quoted,
        // sent or spent, and the text saved above stays.
        notInGroupResult(groupId)?.let {
            Log.w(TAG, "Group $groupId: this phone is no longer in it — message NOT sent; draft kept")
            _groupSendEvent.value = it
            // A screen opened before the removal arrived reloads, so it shows the removal and switches the composer off.
            loadGroupDetail(groupId, retryOwedKeys = false)
            return
        }

        // ZC-757 (G1, G6): a rebuilt group sends no message until its new key reached a member. Nothing is quoted.
        rebuiltRefusal(groupId)?.let {
            Log.w(TAG, "Group $groupId was rebuilt and has no new key a member holds — message NOT sent; draft kept")
            _groupSendEvent.value = it
            return
        }

        // R6-1 (round 5 report §5 item 1) — a message nobody can receive yet is not a message to send. Only
        // ACTIVE members receive one (and only they are ever paid for), so while every other member is still
        // Invited the send used to run a fan-out to nobody: it took a sequence number, drew and removed a
        // bubble and named no one. Stop here instead: nothing is quoted, nothing is spent, the text saved
        // above stays, and the user is told who has not joined yet.
        if (messageRecipients(groupId).isEmpty()) {
            Log.w(TAG, "Group $groupId has no ACTIVE recipients — message NOT sent; draft kept")
            _groupSendEvent.value =
                GroupSendResult.NoActiveRecipients(
                    groupId,
                    notJoinedNames(groupId),
                    cannotReadNames(groupId, SealedKeyCount.LAST_RESULT),
                    rebuilt = rebuiltGroupInfo(groupId)?.announcerRebuilt == true,
                )
            return
        }

        // R6-3 (round 5 report §5 item 4) — a group message is ONE memo per member, never chunked, and it
        // carries the sender's full address beside the ciphertext. Text that cannot fit used to be quoted,
        // then fail for every member at the pre-submit memo guard with a reason that named nothing. Measure it
        // on the real wire layout first: nothing is quoted, spent or numbered, the text saved above stays,
        // and the user is told how much to cut.
        val overBy = groupMessageOverBy(groupId, message, senderAddress, zchatPreferences.getGroupMessageSequence(groupId) + 1)
        if (overBy > 0) {
            Log.w(TAG, "Group $groupId: message is $overBy bytes over one group memo — NOT sent; draft kept")
            _groupSendEvent.value = GroupSendResult.MessageTooLong(groupId, overBy)
            return
        }

        // R5-1 (round 4 report §5B) — a group message is the largest group spend there was: one paid
        // on-chain memo per member with no free relay route. Quote it and take one consent per group
        // BEFORE anything is spent, and before the sequence number is burned or an optimistic bubble
        // appears. Declining leaves the group exactly as it was, with the typed text still in the box.
        askForQuotedGroupMessage(
            groupId,
            onDeclined = { _groupSendEvent.value = GroupSendResult.CostDeclined(groupId) },
        ) { quoted ->
            sendQuotedGroupMessage(groupId, message, senderAddress, quoted)
        }
    }

    /**
     * R5-1 — the fan-out of one group message, paying on-chain only for the members [quoted] covered.
     *
     * ZC-972: a member quoted as free whose relay copy got no ack is not paid for (the quote said free). When the message
     * then reached nobody, those members are quoted as on-chain and the user is asked, once ([relaysMissedAsked] is the
     * send that answered such a question); Cancel pays nothing and keeps the text. Never "check your balance" for it.
     */
    private fun sendQuotedGroupMessage(
        groupId: String,
        message: String,
        senderAddress: String,
        quoted: QuotedFanOut,
        relaysMissedAsked: Boolean = false,
    ) {
        viewModelScope.launch {
            _isSendingMessage.value = true

            try {
                // Round 8 P6: removed from (or left) the group while the cost prompt was on screen: nothing is sent.
                notInGroupResult(groupId)?.let {
                    _groupSendEvent.value = it
                    loadGroupDetail(groupId, retryOwedKeys = false)
                    return@launch
                }

                // GR-OPEN G2: an owed member that only the sealed route reaches is sent the current key first (free,
                // relays only, never paid), so it can read this message.
                val sealedPassRan = hasSealedOwedMember(groupId)
                if (sealedPassRan) {
                    retryPendingGroupKeysInternal(groupId, quoted = QuotedFanOut(emptyList(), emptySet()), sealedOnly = true)
                }
                // GR-OPEN fixup (G-GR-1): after that pass, a member still owed the key with no session got no relay ack
                // for it, so it is left out and named as unable to read (never sent a message under a key it lacks).
                val count = if (sealedPassRan) SealedKeyCount.AFTER_PASS else SealedKeyCount.FORECAST

                // R5-1: the SAME list the cost line and the consent quote were built from (less, after the pass, the
                // members whose key got no ack: they are only ever free, so nothing quoted as free is paid for).
                val recipients = messageRecipients(groupId, count)

                if (recipients.isEmpty()) {
                    // R6-1 — the roster lost its last ACTIVE member while the cost prompt was on screen.
                    // The send reaches NO ONE: check it BEFORE a sequence number is taken or an optimistic
                    // bubble is drawn, KEEP the draft, and say who has not joined yet.
                    Log.w(TAG, "Group $groupId has no ACTIVE recipients — message NOT transmitted; draft kept")
                    _groupSendEvent.value =
                        GroupSendResult.NoActiveRecipients(groupId, notJoinedNames(groupId), cannotReadNames(groupId, count))
                    loadGroupDetail(groupId, retryOwedKeys = true, afterSend = true)
                    return@launch
                }

                // Get group key
                val keyEpoch = zchatPreferences.getGroupKeyEpoch(groupId)
                val encodedKey = zchatPreferences.getGroupKey(groupId, keyEpoch)
                if (encodedKey == null) {
                    Log.e(TAG, "No group key found for $groupId")
                    // P0.1: nothing was transmitted — tell the user instead of failing silently
                    // (the composer keeps the typed message, see GroupDetailView).
                    _groupSendEvent.value = GroupSendResult.AllFailed(groupId)
                    _isSendingMessage.value = false
                    return@launch
                }

                val groupKey = ZMSGGroupProtocol.decodeGroupKey(encodedKey)

                // Get next sequence number
                val seq = zchatPreferences.incrementGroupMessageSequence(groupId)

                // R6-3 — the length was checked before the quote; check it again with the sequence number and
                // key epoch this send really uses (a longer number, or a rotation while the prompt was open,
                // takes room), BEFORE a bubble is drawn or any member is paid for. No member is ever sent a
                // memo that cannot fit, so the fan-out can never deliver to some members and not others
                // because of the length.
                val overBy = groupMessageOverBy(groupId, message, senderAddress, seq, keyEpoch)
                if (overBy > 0) {
                    Log.w(TAG, "Group $groupId: message is $overBy bytes over one group memo — NOT transmitted; draft kept")
                    _groupSendEvent.value = GroupSendResult.MessageTooLong(groupId, overBy)
                    return@launch
                }

                // The encrypted+signed memo is built PER RECIPIENT inside the fan-out loop below: the
                // author signature (mirroring #187) is signed with our PAIRWISE KEX key for each
                // recipient, so a single broadcast memo can't carry one signature the whole group could
                // verify (E2E keypairs are per-peer).

                // Create pending message for immediate display
                val pendingMessage = GroupMessage(
                    id = "pending_${System.currentTimeMillis()}",
                    groupId = groupId,
                    txId = null,
                    seq = seq,
                    epoch = keyEpoch,
                    senderAddress = senderAddress,
                    encryptedContent = null,
                    decryptedContent = message,
                    nonce = null,
                    timestamp = Instant.now(),
                    isPending = true
                )

                // Add to pending messages
                val currentPending = pendingGroupMessages.value.toMutableMap()
                val groupPending = currentPending[groupId]?.toMutableList() ?: mutableListOf()
                groupPending.add(pendingMessage)
                currentPending[groupId] = groupPending
                pendingGroupMessages.value = currentPending

                // Removes the optimistic pending bubble added above — used when the send reaches NO
                // recipient, so it never lingers as a permanent "[Sending…]" that looks delivered.
                fun dropOptimisticPending() {
                    val cur = pendingGroupMessages.value.toMutableMap()
                    cur[groupId] = cur[groupId].orEmpty().filterNot { it.id == pendingMessage.id }
                    pendingGroupMessages.value = cur
                }

                Log.d(TAG, "Sending group message to ${recipients.size} members")

                // Send to each recipient through the BLOCK-AWARE RETRY path. A group message is a
                // shielded tx like any other, so on a single-note wallet the first message right after
                // the invite/accept (which just consumed the only note) fails with TRANSIENT insufficient
                // funds. sendGroupMemoWithRetry waits for the change to confirm and retries (#217).
                var sentCount = 0
                // B1 (GPT-6 Q3): members whose copy may still be broadcast. Not failed: keeping the text to send
                // again would pay for them twice.
                var maybeSentCount = 0
                // C4 (B1 review item 3): members to whom provably nothing was sent, named on the banner.
                var notSentCount = 0
                // Round 8 D8 (F3): the first refusal no balance can fix, named when nobody got the message.
                var messageRefusal: String? = null
                // ZC-972: members quoted as free whose relay copy got no ack, so nothing was sent to them.
                val relaysMissed = mutableSetOf<String>()
                for (recipient in recipients) {
                    Log.d(TAG, "Sending group message to ${recipient.redactAddress()}")
                    // Author-signature (mirrors #187 control fan-out): sign THIS recipient's copy with our
                    // pairwise KEX private key for them; they verify it against getE2EPeerPublicKey(us).
                    // The group key is symmetric, so without this any member could stamp another member's
                    // address on a GROUP_MSG they authored. If we hold no pairwise key for this recipient
                    // (lazy roster #194 — no KEX yet) we send UNSIGNED: they couldn't verify it anyway and
                    // the receiver fails OPEN, so first-posts still go through.
                    val recipientPriv = zchatPreferences.getE2EPrivateKey(recipient)
                    val memo = ZMSGGroupProtocol.createGroupMsgMessage(
                        groupId = groupId,
                        seq = seq,
                        epoch = keyEpoch,
                        senderAddress = senderAddress,
                        plaintext = message,
                        groupKey = groupKey,
                        signer = recipientPriv?.let { priv ->
                            { signedData: String -> runCatching { E2EEncryption.sign(priv, signedData) }.getOrNull() }
                        }
                    )
                    // R5-1: a member the quote counted as free is NEVER paid for on-chain behind the
                    // user's back — the free delivery failing is recorded as a failed delivery instead.
                    val outcome =
                        sendGroupMemoWithRetry(
                            recipient,
                            memo,
                            senderAddress,
                            onRefused = { messageRefusal = messageRefusal ?: it },
                            // ZC-420-G: this member's copy among the relay-copy marks (recipient is canonical already).
                            relaySlot = RelayCopySlots.groupMessage(groupId, recipient, keyEpoch, seq),
                            // F8-G: this member's copy in the ledger (only an on-chain copy gets an entry).
                            attempt =
                                GroupSendAttemptRules.message(groupId, zchatPreferences.resolvePeerAddress(recipient), keyEpoch, seq),
                            // ZC-055: a member who left or was removed while earlier copies went out gets none.
                            stillWanted = { stillActive(groupId, recipient) },
                            onNotPaidOnChain = { relaysMissed += recipient },
                        ) {
                            quoted.mayPayFor(recipient)
                        }
                    when (outcome) {
                        GroupMemoOutcome.SENT -> sentCount++
                        GroupMemoOutcome.MAYBE_SENT -> maybeSentCount++
                        // F8-G: an unresolved copy of this very message to this member: counted as "may have been sent".
                        GroupMemoOutcome.PENDING -> maybeSentCount++
                        GroupMemoOutcome.NOTHING_SENT -> {
                            notSentCount++
                            Log.e(TAG, "Group message to ${recipient.redactAddress()} failed after retries")
                        }
                    }
                    // Small delay between sends to avoid overwhelming the wallet
                    delay(500)
                }

                if (sentCount == 0 && maybeSentCount == 0) {
                    // Delivered to nobody (every recipient failed after retries). Same as the empty case:
                    // drop the false pending bubble and preserve the draft for retry.
                    Log.w(TAG, "Group $groupId: delivered to 0/${recipients.size} members — draft kept for retry")
                    if (relaysMissed.isNotEmpty() && messageRefusal == null && !relaysMissedAsked) {
                        // ZC-972: the relays took no copy for members quoted as free. Their on-chain copies are quoted and
                        // the user is asked; nothing is paid before the tap, and Cancel keeps the text.
                        Log.w(TAG, "Group $groupId: the relays took no copy for ${relaysMissed.size} member(s) — asking the on-chain cost")
                        dropOptimisticPending()
                        val missed = relaysMissed.toSet()
                        askForQuotedGroupMessage(
                            groupId,
                            onDeclined = { _groupSendEvent.value = GroupSendResult.CostDeclined(groupId) },
                            relaysMissed = missed,
                        ) { fresh ->
                            sendQuotedGroupMessage(groupId, message, senderAddress, fresh, relaysMissedAsked = true)
                        }
                        return@launch
                    }
                    // P0.1: visible, not just a log line. Round 8 D8 (F3): naming a refusal no balance can fix.
                    _groupSendEvent.value = GroupSendResult.AllFailed(groupId, messageRefusal)
                    dropOptimisticPending()
                    loadGroupDetail(groupId, retryOwedKeys = true, afterSend = true)
                    return@launch
                }
                if (maybeSentCount > 0) {
                    Log.w(TAG, "Group $groupId: $maybeSentCount member(s) may have been sent the message — not re-sent")
                    _groupSendEvent.value =
                        GroupSendResult.MaybeSent(
                            groupId,
                            GroupSendResult.MaybeSentKind.MESSAGE,
                            members = maybeSentCount,
                            notSent = notSentCount,
                        )
                } else if (sentCount < recipients.size) {
                    Log.w(TAG, "Group $groupId: partial delivery to $sentCount/${recipients.size} members")
                    // P0.1: visible, not just a log line
                    _groupSendEvent.value = GroupSendResult.PartialDelivery(groupId, sentCount, recipients.size)
                } else {
                    // ZC-420-G: delivered to every member, and for some of them only a public relay took the copy: say
                    // who. The copy counts as sent (it decided nothing paid); a group open delivers it again, free.
                    relayUnconfirmedResult(groupId, recipients.filter { recipientCopyMarked(groupId, it, keyEpoch, seq) })
                        ?.let { _groupSendEvent.value = it }
                }

                // R8-fixup F2 (REVIEW_R8 F2 a): our own message is stored once it reached (or may have reached) a member.
                // A copy that went free over the relays is on no chain, so the history scan can never re-derive it; an
                // on-chain copy is superseded by the scan's row for the same (sender, seq, epoch).
                storeOwnGroupMessage(pendingMessage)

                // Delivered (or may have been delivered) to at least one member — clear the draft and refresh.
                zchatPreferences.clearGroupDraft(groupId)
                loadGroupDetail(groupId, retryOwedKeys = true, afterSend = true)

            } catch (e: Exception) {
                // Cancellation (screen closed / process death) is not a send failure — propagate.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to send group message", e)
                // P0.1: an unexpected error is still a failed send — make it visible so the composer
                // keeps the typed message instead of clearing as if it had been delivered.
                _groupSendEvent.value = GroupSendResult.AllFailed(groupId)
            } finally {
                _isSendingMessage.value = false
            }
        }
    }

    // ==========================================
    // DRAFT MANAGEMENT
    // ==========================================

    /**
     * ZC-1104 (W-79): the group screen shows [groupId]: its messages are read up to max(now, its newest shown message),
     * as markConversationRead does for a chat. Monotonic and shared (readMarkers), so the chat list's Groups row
     * recounts at once.
     */
    fun markGroupRead(groupId: String) {
        val newest =
            (_groupDetailState.value as? GroupDetailState.Success)
                ?.conversation
                ?.takeIf { it.groupInfo.groupId == groupId }
                ?.messages
                ?.maxOfOrNull { it.timestamp.toEpochMilli() }
                ?: 0L
        zchatPreferences.setLastReadTimestamp(
            GroupUnread.markerKey(groupId),
            maxOf(System.currentTimeMillis(), newest)
        )
    }

    /**
     * Save draft for a group.
     */
    fun saveGroupDraft(groupId: String, draft: String) {
        zchatPreferences.setGroupDraft(groupId, draft)
    }

    /**
     * Get draft for a group.
     */
    fun getGroupDraft(groupId: String): String? {
        return zchatPreferences.getGroupDraft(groupId)
    }

    /**
     * Clear draft for a group.
     */
    fun clearGroupDraft(groupId: String) {
        zchatPreferences.clearGroupDraft(groupId)
    }

    // ==========================================
    // MEMBER MANAGEMENT
    // ==========================================

    /**
     * Leave a group.
     */
    fun leaveGroup(groupId: String, onComplete: () -> Unit = {}) {
        val userAddress = _currentUserAddress.value ?: return
        // R2-2 (b): GROUP_LEAVE goes to every other ACTIVE member. Ask first; nothing changes until confirmed.
        // R4-2: and the broadcast runs that quoted fan-out — a member quoted as free is never paid for.
        askForQuotedPlan(PaidAction.GROUP_LEAVE, { activeMembersOtherThan(groupId, userAddress) }) { quoted ->
            leaveGroup(groupId, quoted, onComplete)
        }
    }

    /**
     * R5-1 — who one group message goes to, and the single source of that list: the composer's cost line,
     * the consent quote and the fan-out itself all read it, so the number on screen is the number paid for.
     *
     * Active members other than ourselves. Uses the #205 hash-tolerant self-check (a drifted rep of OUR OWN
     * address must not be treated as a recipient — we'd pay to message ourselves), and canonicalizes + dedups
     * across each peer's UA representations so a roster that still holds duplicate reps of one member (legacy
     * rows from before the #214 alias fix) fans out — and is quoted — ONCE, not twice.
     *
     * Round 8 P5 (PHONE_TRIAGE F5a): a member who is owed the current key ([GroupMember.pendingKeyEpoch] != null)
     * and has no secure session with us is left out ([cannotReadMembers]). Nothing can send them the key, so a message
     * sent to them under it could not be decrypted, and paying for it bought nothing. A member owed the key who HAS a
     * session stays in: the owed-key retry can still reach them.
     */
    private fun messageRecipients(groupId: String): List<String> = messageRecipients(groupId, SealedKeyCount.FORECAST)

    /** GR-OPEN fixup — [messageRecipients] with the members that [count] says cannot read left out. */
    private fun messageRecipients(
        groupId: String,
        count: SealedKeyCount,
    ): List<String> {
        // Round 8 P6 (PHONE_TRIAGE F5b): nobody, in a group this phone is no longer in.
        if (!isInGroup(groupId)) return emptyList()
        val cannotRead = cannotReadMembers(groupId, count).map { zchatPreferences.resolvePeerAddress(it.address) }.toSet()
        // Round 8 MA2: once the roster is announced, only announced members and the creator (so a member learned from a
        // message in another address form is never a second recipient, and an `R` removes the member completely).
        val announced = announcedRecipients(groupId)
        return zchatPreferences
            .getGroupMembers(groupId)
            ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
            .orEmpty()
            .filter { it.status == MemberStatus.ACTIVE && !zchatPreferences.isSelfAddress(it.address) }
            .map { zchatPreferences.resolvePeerAddress(it.address) }
            .distinct()
            .filter { announced == null || it in announced }
            .filterNot { it in cannotRead }
    }

    /**
     * Round 8 P5 (PHONE_TRIAGE F5a) — the ACTIVE members (not us, one row per member) who can't read a new message:
     * owed the current key and with no secure session to send it under. Every row of the member must be owed and
     * session-less, so a member one of whose representations holds the key is never dropped.
     */
    private fun cannotReadMembers(
        groupId: String,
        count: SealedKeyCount = SealedKeyCount.FORECAST,
    ): List<GroupMember> {
        // Round 8 P6: nothing is said about other members in a group this phone is no longer in.
        if (!isInGroup(groupId)) return emptyList()
        val announced = announcedRecipients(groupId)
        val active =
            zchatPreferences
                .getGroupMembers(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                .orEmpty()
                .filter { it.status == MemberStatus.ACTIVE && !zchatPreferences.isSelfAddress(it.address) }
                // Round 8 MA2: only the members a message may go to ([announcedRecipients]).
                .filter { announced == null || zchatPreferences.resolvePeerAddress(it.address) in announced }
        return active
            .groupBy { zchatPreferences.resolvePeerAddress(it.address) }
            .values
            // GR-OPEN G2 (review E): the sealed route counts only while the relays can reach the member right now.
            .filter { rows -> rows.all { it.pendingKeyEpoch != null && !keyReachableNow(groupId, it.address, null, count) } }
            .map { it.first() }
    }

    /**
     * GROUP-LABEL — the canonical key of [address] for names: trimmed, lowercased (as the contact book compares) and
     * resolved through the peer alias (the invited address -> the address the member accepted with).
     */
    private fun nameKey(address: String): String =
        zchatPreferences
            .resolvePeerAddress(address.trim().lowercase())
            .trim()
            .lowercase()

    /** GROUP-LABEL — the 1-to-1 nicknames and saved contact names, each read once and keyed by [nameKey]. */
    private class NameBook(
        val nicknames: Map<String, String>,
        val contacts: Map<String, String>,
    )

    /** [NameBook] now; a store that cannot be read gives no names from it (short addresses), never a failed screen. */
    private fun nameBook(): NameBook =
        NameBook(
            nicknames =
                runCatching { PeerName.keyed(zchatPreferences.getAllNicknames().toList(), ::nameKey) }
                    .getOrDefault(emptyMap()),
            contacts =
                runCatching { PeerName.keyed(contactBook.getAllContacts().map { it.address to it.name }, ::nameKey) }
                    .getOrDefault(emptyMap()),
        )

    /**
     * GROUP-LABEL — how the group screens name [member]: as the 1-to-1 chat header does (its nickname, else the saved
     * contact name), else the roster row's snapshot, else the short address.
     */
    private fun memberName(
        member: GroupMember,
        book: NameBook = nameBook(),
    ): String = PeerName.label(member.address, book.nicknames, book.contacts, ::nameKey, member.nickname)

    /**
     * GROUP-LABEL — the names of [members] and of the senders of [messages], by address, for the group screens.
     *
     * A sender is named only when its canonical address is a row of this group's roster (any status, so a removed
     * member's old messages keep their name): an on-chain message may claim any sender address, and must not show the
     * name of a contact who is not in the group. Self is left out (own messages carry no header). Only real names get an
     * entry; the rest show the short address. Any failure gives no names, never a failed screen.
     */
    private fun groupNames(
        members: List<GroupMember>,
        messages: List<GroupMessage>,
    ): Map<String, String> =
        runCatching {
            val book = nameBook()
            val rows = members.groupBy { nameKey(it.address) }
            (members.map { it.address } + messages.map { it.senderAddress })
                .distinct()
                .filterNot { zchatPreferences.isSelfAddress(it) }
                .mapNotNull { address ->
                    val rowsOfAddress = rows[nameKey(address)] ?: return@mapNotNull null
                    val snapshot =
                        rowsOfAddress.firstOrNull { it.address == address }?.nickname
                            ?: rowsOfAddress.firstNotNullOfOrNull { it.nickname?.takeIf { n -> n.isNotBlank() } }
                    PeerName.name(address, book.nicknames, book.contacts, ::nameKey, snapshot)?.let { address to it }
                }.toMap()
        }.getOrDefault(emptyMap())

    /**
     * R6-1 — the members a message cannot reach because they have not joined yet: still Invited, not us,
     * one entry per member (the #214 alias dedup [messageRecipients] uses). Named as the group screens name
     * them ([memberName]: the 1-to-1 nickname, else the saved contact name, else the roster snapshot, else the short
     * address) — never the full one.
     */
    private fun notJoinedNames(groupId: String): List<String> =
        zchatPreferences
            .getGroupMembers(groupId)
            ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
            .orEmpty()
            .filter { it.status == MemberStatus.INVITED && !zchatPreferences.isSelfAddress(it.address) }
            .distinctBy { zchatPreferences.resolvePeerAddress(it.address) }
            .let { invited ->
                val book = nameBook()
                invited.map { memberName(it, book) }
            }

    /** Round 8 P5 — [cannotReadMembers] as the group screens name them. */
    private fun cannotReadNames(
        groupId: String,
        count: SealedKeyCount = SealedKeyCount.FORECAST,
    ): List<String> {
        val book = nameBook()
        return cannotReadMembers(groupId, count).map { memberName(it, book) }
    }

    /** R2-2 (b) / R4-2 — who a GROUP_LEAVE is broadcast to. */
    private fun activeMembersOtherThan(groupId: String, userAddress: String): List<String> =
        zchatPreferences
            .getGroupMembers(groupId)
            ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
            .orEmpty()
            .filter { it.status == MemberStatus.ACTIVE && it.address != userAddress }
            .map { it.address }

    /** R4-2 — the quoted leave: [quoted] decides which members may be paid for on-chain. */
    private fun leaveGroup(
        groupId: String,
        quoted: QuotedFanOut,
        onComplete: () -> Unit,
    ) {
        val userAddress = _currentUserAddress.value ?: return

        viewModelScope.launch {
            try {
                // KI-22: under the group's change lock, so it never interleaves with a kick, rotation or add of this group
                // from another screen.
                withGroupChange(groupId) { leaveGroup(groupId, userAddress, quoted) }
                // Refresh groups
                loadGroups()
                onComplete()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to leave group", e)
            }
        }
    }

    /**
     * KI-22 — the quoted leave's body, run under the group's change lock. Same name as its caller on purpose: the paid
     * GROUP_LEAVE memos it sends stay listed under leaveGroup (PaidSendCallSiteLockTest).
     */
    private suspend fun leaveGroup(
        groupId: String,
        userAddress: String,
        quoted: QuotedFanOut,
    ) {
        // Update local status, and mark the group inactive locally: fresh read-modify-writes under the group lock (KI-22).
        withGroupLock(groupId) {
            storedRoster(groupId)?.let { members ->
                val updated = members.map { if (it.address == userAddress) it.copy(status = MemberStatus.LEFT) else it }
                zchatPreferences.saveGroupMembers(groupId, ZMSGGroupProtocol.serializeGroupMembers(updated))
            }
            zchatPreferences.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }?.let { info ->
                zchatPreferences.saveGroupInfo(groupId, ZMSGGroupProtocol.serializeGroupInfo(info.copy(isActive = false)))
                // Round 8 MA2 (design §5.3): only an invite newer than this leave can reopen the group.
                recordInactiveSince(groupId)
            }
        }

        // Send GROUP_LEAVE message to other active members
        val recipients = storedRoster(groupId).orEmpty().filter { it.status == MemberStatus.ACTIVE && it.address != userAddress }
        if (recipients.isEmpty()) return

        val leaveMemo = ZMSGGroupProtocol.createGroupLeaveMessage(groupId = groupId, leaverAddress = userAddress)
        Log.d(TAG, "Broadcasting GROUP_LEAVE to ${recipients.size} members")
        for (recipient in recipients) {
            try {
                Log.d(TAG, "Sending GROUP_LEAVE to ${recipient.address.redactAddress()}")
                // #199/#217: route through the block-aware, NOSTR-free-preferring path instead of
                // a bare createChunkedMessageProposal in a fixed-500ms loop. The old loop made the
                // 2nd+ GROUP_LEAVE silently fail with transient insufficient funds on a single-note
                // wallet (change from send #1 not yet confirmed), so those members never learned we
                // left and kept paying to fan out to us; it also never used the free NOSTR path.
                sendGroupMemoWithRetry(recipient.address, leaveMemo, userAddress) {
                    quoted.mayPayFor(recipient.address)
                }
                // Small delay between sends
                delay(500)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to send GROUP_LEAVE to ${recipient.address.redactAddress()}", e)
                // Continue to next recipient - best effort broadcast
            }
        }
    }

    /**
     * Delete a group (admin only).
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            // Round 8 MA2 (MA1 review note 10): under the group's process-wide lock (ChatViewModel's roster writes take it).
            synchronized(ZchatPreferences.groupLock(groupId)) { zchatPreferences.deleteGroup(groupId) }
            loadGroups()
        }
    }

    /**
     * Check if user is admin of a group.
     */
    fun isAdmin(groupId: String): Boolean {
        if (zchatPreferences.isGroupSelfCreated(groupId)) return true
        val infoJson = zchatPreferences.getGroupInfo(groupId) ?: return false
        val info = ZMSGGroupProtocol.deserializeGroupInfo(infoJson) ?: return false
        // #205 hash-tolerant: the stored creatorAddress may be a different representation of our
        // own address than the one currently loaded; persisted self-created flag is the primary
        // signal, this is the fallback.
        return zchatPreferences.isSelfAddress(info.creatorAddress)
    }
}
