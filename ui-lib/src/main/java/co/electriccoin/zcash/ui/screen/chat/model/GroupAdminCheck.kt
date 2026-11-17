package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * ZC-022 (KI-25 R5) — what this phone knows about the key of a group's admin.
 * - [NOT_APPLICABLE]: there is nothing to confirm (no group record, a group this phone is not in, an invite that still
 *   waits for Join, a group this phone created, a group joined with the creator's plaintext relay invite);
 * - [OK]: a contact on this phone holds the group's creator key;
 * - [UNKNOWN]: the phone cannot match the group's admin to a key, so it accepts none of the admin's changes;
 * - [ORPHANED]: a creator key is stored and no contact holds it any more (KI-25 R14, ZC-572): the admin's key changed
 *   on this phone, and the group asks for the confirmation again.
 */
enum class AdminKeyState { NOT_APPLICABLE, OK, UNKNOWN, ORPHANED }

/** ZC-022 (KI-25 R6) — the one line a group chat shows about its admin: the check line, or one of the two catch-up lines. */
enum class GroupAdminNotice { CHECK_LINE, CATCHUP_KEY, CATCHUP_LIST }

/**
 * ZC-022 (KI-25 R7) — which of the phone's own records named the chat: the invitation that created the group, or the
 * admin's address. Never an address alias (KI-25 D3; ZC-953's alias suggestion was reverted: any contact can plant an
 * alias with a signed group-accept).
 */
enum class AdminCandidateSource { INVITATION, ADDRESS }

/**
 * ZC-022 (KI-25 R7) — the one chat the phone's own records tie to a group's creation: its [address], its [name] as the
 * chat list shows it, the [source], and three facts for the card.
 */
data class AdminCandidate(
    val address: String,
    val name: String?,
    val source: AdminCandidateSource,
    /** A contact key, our own key pair, and no chat request still waiting for Accept. */
    val chatFinished: Boolean = false,
    val keyChangePending: Boolean = false,
    /** The user compared the safety number with this contact ("Mark as verified"). */
    val safetyNumberCompared: Boolean = false,
)

/**
 * ZC-022 (KI-25 R8) — what the confirm dialog shows of the chat a user may confirm as [groupId]'s admin: its [name]
 * and [address], the contact's key the dialog opened with ([contactKey], which Confirm hands back), the chat's
 * [safetyNumber] and the [source] of the candidate.
 */
data class GroupAdminPreview(
    val groupId: String,
    val name: String,
    val address: String,
    val contactKey: String,
    val safetyNumber: String,
    val source: AdminCandidateSource,
)

/** ZC-022 (KI-25 R8) — why a confirmation of a group's admin was refused, in the order the checks are made. */
enum class AdminConfirmRefusal {
    NOT_NEEDED,
    NOT_THE_RECORDED_CHAT,
    OWN_ADDRESS,
    NOT_A_SECURE_CHAT,
    KEY_CHANGED,
    NOT_VERIFIED,
    KEY_DIFFERS,
    STORE_REFUSED,

    /**
     * ZC-951 residual (review of 14 Oct, finding 5): the record with the confirmed key was written, the contact's key
     * changed meanwhile, and neither the write-back nor the put-back of the record from before took: the key the user
     * compared stays in force on this phone, and the card asks again.
     */
    KEY_CHANGED_NOT_UNDONE,
}

/**
 * ZC-022 (KI-25 R8) — what a confirmation of a group's admin did. ZC-951: every outcome names the group ([groupId])
 * and the chat ([candidate]) it was made for, and a screen shows only the outcome that [isFor] its own.
 */
sealed class AdminConfirmOutcome {
    abstract val groupId: String
    abstract val candidate: String

    /** ZC-951 — true when this is what confirming the chat at [candidate] as the admin of [groupId] did. */
    fun isFor(
        groupId: String,
        candidate: String?,
    ): Boolean = this.groupId == groupId && this.candidate == candidate

    data class Confirmed(
        override val groupId: String,
        override val candidate: String,
    ) : AdminConfirmOutcome()

    data class Refused(
        val reason: AdminConfirmRefusal,
        override val groupId: String,
        override val candidate: String,
    ) : AdminConfirmOutcome()
}

/**
 * ZC-704 (KI-25 R19) — what a handler of an admin change ([KeptAdminChange.KICK], [KeptAdminChange.KEY]) did with it:
 * - [NotParsed]: the payload is no such change;
 * - [Failed]: the handler threw, and nothing else;
 * - [NotSaved]: the gate or the sealed route accepted the change, the group-key store refused its commit, and the
 *   epoch was not set;
 * - [RefusedByGate]: the gate refused it. It carries the change's [RefusedByGate.epoch], whether it was
 *   [RefusedByGate.signed], and, for a copy that came over the relays, the chat of the sealing contact whose stored
 *   key verifies the signature ([GroupAdminCheck.signerChat]), or none;
 * - [NotNewer]: its epoch is not above the group's;
 * - [Applied]: the handler ran to its end.
 */
sealed class AdminChangeOutcome {
    object NotParsed : AdminChangeOutcome()

    object Failed : AdminChangeOutcome()

    object NotSaved : AdminChangeOutcome()

    object NotNewer : AdminChangeOutcome()

    object Applied : AdminChangeOutcome()

    data class RefusedByGate(
        val epoch: Int,
        val signed: Boolean,
        val signerChat: String?,
    ) : AdminChangeOutcome()
}

/** ZC-704 (KI-25 R19) — whether a handled admin change is kept, and as which kind of entry ([GroupAdminCheck.keepAs]). */
enum class KeepAs { NOT_KEPT, AS_REFUSED, AS_ACCEPTED }

/**
 * ZC-022 (KI-25 R2, R3, R5 to R8) — the pure rules about a group's admin key. Every function here only reads the
 * store or the wallet's history; none writes, sends or pays.
 *
 * `savedContacts` is the address of every saved contact (the contact book), which the store itself does not hold.
 */
object GroupAdminCheck {
    /**
     * The one address set every lookup walks: the conversation addresses and the saved contacts, sorted. A store that
     * cannot list its conversations contributes none.
     */
    fun contactAddresses(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
    ): List<String> {
        val mapped = runCatching { prefs.getAllPeerToConvIdMappings().keys.toSet() }.getOrNull().orEmpty()
        val conversations = runCatching { prefs.getAllConversationPeerAddresses().toSet() }.getOrNull().orEmpty()
        return (mapped + conversations + savedContacts).sorted()
    }

    /** The lookup order: the creator address, its alias target, then [contactAddresses]. */
    private fun lookupOrder(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        creatorAddress: String,
    ): List<String> =
        (listOf(creatorAddress, prefs.resolvePeerAddress(creatorAddress)) + contactAddresses(prefs, savedContacts)).distinct()

    /**
     * The first address (in the lookup order) whose stored contact key equals [creatorKey], the group's creator key;
     * null when no creator key is given or no contact holds it.
     */
    fun creatorHolder(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        info: GroupInfo,
        creatorKey: String? = info.creatorPublicKey,
    ): String? {
        val key = creatorKey?.takeIf { it.isNotEmpty() } ?: return null
        return lookupOrder(prefs, savedContacts, info.creatorAddress).firstOrNull { prefs.getE2EPeerPublicKey(it) == key }
    }

    /**
     * The first such address for which this phone also holds its own key pair, so a session can be derived and a
     * request can be signed. Losing our own key pair gives no session here and still leaves [creatorHolder].
     */
    fun creatorSession(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        info: GroupInfo,
        creatorKey: String? = info.creatorPublicKey,
    ): String? {
        val key = creatorKey?.takeIf { it.isNotEmpty() } ?: return null
        return lookupOrder(prefs, savedContacts, info.creatorAddress)
            .firstOrNull { prefs.getE2EPeerPublicKey(it) == key && prefs.getE2EPrivateKey(it) != null }
    }

    /** KI-25 R3 — where this phone's requests to the group's admin go: the admin's contact, else the stored creator address. */
    fun adminTarget(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        info: GroupInfo,
    ): String = creatorSession(prefs, savedContacts, info) ?: creatorHolder(prefs, savedContacts, info) ?: info.creatorAddress

    /**
     * KI-25 R5 — the state of [groupId]'s admin key, from this phone's own data, in this order:
     * 1. no group record, a group that is not active, a group this phone created, or an invite that still waits for
     *    Join: [AdminKeyState.NOT_APPLICABLE];
     * 2. a creator key is stored and a contact holds it: [AdminKeyState.OK];
     *    (KI-25 R14, ZC-572) a creator key is stored and no contact holds it: [AdminKeyState.ORPHANED];
     * 3. (KI-25 pair 3, D1: withdrawn) a key held at the creator address no longer counts by itself: after the first
     *    start's one look nothing writes a creator key from an address;
     * 4. no creator key is stored and the group was joined with the creator's plaintext relay invite (its admin
     *    changes are sealed, not signed): [AdminKeyState.NOT_APPLICABLE], unless (R17) the chat at the creator
     *    address is a finished chat, or R19's list holds a refused change that the stored admin address sealed
     *    ([openGroupAsks]): then [AdminKeyState.UNKNOWN];
     * 5. otherwise [AdminKeyState.UNKNOWN].
     */
    fun keyState(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        groupId: String,
    ): AdminKeyState {
        val info = prefs.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
        if (info == null || !info.isActive) return AdminKeyState.NOT_APPLICABLE
        // Our own group first: on the admin's phone nothing more is read (all four conditions of row 1 answer the same).
        if (prefs.isGroupSelfCreated(groupId) || prefs.isSelfAddress(info.creatorAddress)) return AdminKeyState.NOT_APPLICABLE
        if (prefs.getGroupInviteNeedsJoin(groupId) != null) return AdminKeyState.NOT_APPLICABLE
        val stored = info.creatorPublicKey?.takeIf { it.isNotEmpty() }
        return when {
            stored != null ->
                if (creatorHolder(prefs, savedContacts, info) != null) AdminKeyState.OK else AdminKeyState.ORPHANED
            plainKeyJoin(prefs, groupId) -> if (openGroupAsks(prefs, info)) AdminKeyState.UNKNOWN else AdminKeyState.NOT_APPLICABLE
            else -> AdminKeyState.UNKNOWN
        }
    }

    /**
     * KI-25 R17 (pair 3) — an Open group (joined with the creator's plaintext relay invite, no creator key) asks for the
     * confirmation once the chat at its stored admin address is a finished chat, or while R19's list holds a change kept
     * as refused that the stored admin address sealed (ZC-729, the telling half). An entry kept as accepted does not count.
     */
    private fun openGroupAsks(
        prefs: ZchatPreferences,
        info: GroupInfo,
    ): Boolean = finishedChatKey(prefs, info.creatorAddress) != null || keptSealedByTheAdminAddress(prefs, info)

    /**
     * KI-25 R17 — R19's list of [info]'s group holds a change kept as refused whose seal came from the stored admin
     * address. ZC-967: compared as stored, never through an address alias (one any member can plant).
     */
    private fun keptSealedByTheAdminAddress(
        prefs: ZchatPreferences,
        info: GroupInfo,
    ): Boolean {
        val state = runCatching { prefs.getGroupAnnounceState(info.groupId) as Any? }.getOrNull() as? AnnounceState ?: return false
        return state.keptAdminChanges.any { !it.accepted && it.from == info.creatorAddress }
    }

    /**
     * KI-25 R17 (pair 3; ZC-729) — R19's condition 2 for an Open group: it reads NOT_APPLICABLE by row 5 (joined with
     * the creator's plaintext relay invite, no creator key) and the change was sealed by its stored admin address
     * ([sealSender], the sender test of the sealed route). ZC-967: compared as stored, never through an address alias.
     */
    fun openGroupSealedByItsAdmin(
        prefs: ZchatPreferences,
        info: GroupInfo,
        sealSender: String,
    ): Boolean =
        info.creatorPublicKey.isNullOrEmpty() &&
            plainKeyJoin(prefs, info.groupId) &&
            sealSender == info.creatorAddress

    /**
     * KI-25 R17 (pair 3; ZC-729) — R19's condition 4 one step further, for a change [openGroupSealedByItsAdmin] holds
     * and [signerChat] found no chat for: the first finished chat in [contactAddresses] whose stored key verifies
     * [signature] over [signedData] and that has a rank. Only a seal whose sender is the stored admin address itself
     * passes [openGroupSealedByItsAdmin] (compared as stored, never through an alias: ZC-967), so this search is made
     * for no other sender.
     */
    fun openGroupSignerChat(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        info: GroupInfo,
        signedData: String,
        signature: String,
    ): String? =
        contactAddresses(prefs, savedContacts).firstOrNull { chat ->
            val key = finishedChatKey(prefs, chat)
            key != null &&
                runCatching { E2EEncryption.verify(key, signedData, signature) }.getOrDefault(false) &&
                signerChatRank(prefs, info, chat) != null
        }

    /**
     * KI-25 R17 (pair 3; ZC-729, the telling half) — the card ends with `group_admin_no_candidate_open`: an Open group
     * that reads UNKNOWN only because R19's list holds a refused change its stored admin address sealed, with no finished
     * chat at that address (so R7 names no chat). A new Open group would land in the same state, so the new-group advice
     * is not given.
     */
    fun openGroupAdminElsewhere(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        groupId: String,
    ): Boolean {
        val info = prefs.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } ?: return false
        return keyState(prefs, savedContacts, groupId) == AdminKeyState.UNKNOWN &&
            info.creatorPublicKey.isNullOrEmpty() &&
            plainKeyJoin(prefs, groupId) &&
            finishedChatKey(prefs, info.creatorAddress) == null &&
            keptSealedByTheAdminAddress(prefs, info)
    }

    /**
     * KI-25 D1, D3 (pair 3, ZC-598) — the one look at a record that 2.12.4 wrote, in the first start of this build only
     * (called by the store's move of 2.12.4's plain group file, `ZchatPreferencesImpl.moveLegacyGroupRecords`, and by
     * nothing else): [info] with the key of a finished chat at its creator address as its creator key; [info] itself
     * when it has a creator key already, when it is this phone's own group or names an own address as its creator, or
     * when there is no finished chat at the creator address itself (no alias is followed). A key with no key pair of
     * ours there, and a chat request still waiting for Accept, is not a finished chat ([finishedChatKey]). Reads only.
     */
    fun firstLook(
        prefs: ZchatPreferences,
        info: GroupInfo,
    ): GroupInfo {
        if (!info.creatorPublicKey.isNullOrEmpty()) return info
        if (prefs.isGroupSelfCreated(info.groupId) || prefs.isSelfAddress(info.creatorAddress)) return info
        val key = finishedChatKey(prefs, info.creatorAddress) ?: return info
        return info.copy(creatorPublicKey = key)
    }

    private fun plainKeyJoin(
        prefs: ZchatPreferences,
        groupId: String,
    ): Boolean = (runCatching { prefs.getGroupAnnounceState(groupId) as Any? }.getOrNull() as? AnnounceState)?.plainKeyJoin == true

    /** KI-25 R5 — true while the phone cannot match the group's admin to a key: the card shows and a chat can be confirmed. */
    fun needsConfirmation(state: AdminKeyState): Boolean = state == AdminKeyState.UNKNOWN || state == AdminKeyState.ORPHANED

    /** KI-25 R7, R8 — a finished chat with [address]: a contact key, our own key pair, and no chat request waiting for Accept. */
    fun chatFinished(
        prefs: ZchatPreferences,
        address: String,
    ): Boolean =
        prefs.getE2EPeerPublicKey(address) != null &&
            prefs.getE2EPrivateKey(address) != null &&
            runCatching { prefs.getInboundKexAwaitingAccept(address) }.getOrNull() == null

    /** KI-25 R8 — a key change of [address] is not settled: the key-changed mark, or a refused key that still waits. */
    fun keyChangePending(
        prefs: ZchatPreferences,
        address: String,
    ): Boolean = prefs.isE2EKeyChanged(address) || runCatching { prefs.getRefusedKeyOffer(address) }.getOrNull() != null

    /**
     * KI-25 R7 — the memos of the wallet's received transactions whose block time is [info]'s stored creation time, to
     * the millisecond. No other transaction's memos are read. A memo that cannot be read counts as none.
     */
    suspend fun creationTimeMemos(
        history: TransactionRepository,
        info: GroupInfo,
    ): List<String> {
        val createdAt = info.createdAt.toEpochMilli()
        val memos = mutableListOf<String>()
        for (tx in history.getTransactions()) {
            if (tx !is ReceiveTransaction || tx.timestamp?.toEpochMilli() != createdAt) continue
            try {
                memos += history.getMemos(tx)
            } catch (e: Exception) {
                // An unreadable memo names nobody; a cancelled read ends the lookup.
                if (e is CancellationException) throw e
            }
        }
        return memos
    }

    /** The wrapped key (`k2`) and key epoch of [memo] when it is a `GROUP_INVITE` for [groupId] that carries one. */
    private fun wrappedInviteKey(
        memo: String,
        groupId: String,
    ): Pair<String, Int>? {
        if (!ZMSGGroupProtocol.isGroupMessage(memo)) return null
        if (ZMSGGroupProtocol.parseMessageType(memo) != GroupMessageType.GROUP_INVITE) return null
        if (ZMSGGroupProtocol.parseGroupId(memo) != groupId) return null
        val json = ZMSGGroupProtocol.parsePayload(memo)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val k2 = json.optString("k2", "").takeIf { it.isNotEmpty() } ?: return null
        return k2 to json.optInt("key_epoch", 0)
    }

    /**
     * KI-25 R7, source 1 (the invitation) — the chat that sent the invitation which created [groupId] on this phone,
     * from [memos] (the memos of the received transactions at the group's creation time, [creationTimeMemos]). The
     * session ([sessionOf]) of every finished chat in [contactAddresses] is tried on each invite's `k2`; the addresses
     * whose session unwraps it to exactly the key this phone stores for the group at the invite's key epoch (else the
     * record's key) are the candidate when they all carry one and the same contact key: the first of them, in the
     * order of the address set. None, or two different keys: null.
     *
     * The roster, the verified marks and who sent a control are not read.
     */
    fun invitationCandidate(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        info: GroupInfo,
        memos: List<String>,
        sessionOf: (String) -> ByteArray?,
    ): String? {
        val invites = memos.mapNotNull { wrappedInviteKey(it, info.groupId) }
        if (invites.isEmpty()) return null
        val matches =
            contactAddresses(prefs, savedContacts).filter { address ->
                if (!chatFinished(prefs, address)) return@filter false
                val session = sessionOf(address) ?: return@filter false
                invites.any { (k2, epoch) ->
                    val groupKey = prefs.getGroupKey(info.groupId, epoch) ?: info.groupKey
                    groupKey != null && runCatching { E2EEncryption.decrypt(k2, session) }.getOrNull() == groupKey
                }
            }
        val keys = matches.map { prefs.getE2EPeerPublicKey(it) }.toSet()
        return matches.firstOrNull()?.takeIf { keys.size == 1 }
    }

    /**
     * KI-25 R7, source 2 (the admin's address; pair 2, ZC-572) — the chat at [info]'s stored creator address itself,
     * when a contact key is stored there and it is not a chat request still waiting for Accept. No alias is followed.
     * Weaker than the invitation (an address claim), so it is asked only when source 1 named no chat; the user's
     * compared safety number and Confirm (R8) stand between a claimed key and the admin role.
     */
    fun addressCandidate(
        prefs: ZchatPreferences,
        info: GroupInfo,
    ): String? {
        val address = info.creatorAddress
        if (prefs.getE2EPeerPublicKey(address) == null) return null
        if (runCatching { prefs.getInboundKexAwaitingAccept(address) }.getOrNull() != null) return null
        return address
    }

    /**
     * KI-25 R7 — the chat a user may confirm as [info]'s admin, and from which record: the invitation ([invited], source
     * 1), else the admin's address ([addressCandidate], source 2). Null when none names a chat. No address alias is
     * followed (KI-25 D3): the alias writer, the GROUP_ACCEPT handler, takes whatever address a member declares, so an
     * alias could name any contact as the admin (ZC-953's suggestion, reverted). [savedContacts] is no longer read.
     */
    @Suppress("UNUSED_PARAMETER")
    fun recordedCandidate(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        info: GroupInfo,
        invited: String?,
    ): Pair<String, AdminCandidateSource>? =
        invited?.let { it to AdminCandidateSource.INVITATION }
            ?: addressCandidate(prefs, info)?.let { it to AdminCandidateSource.ADDRESS }

    /** KI-25 R7 — the candidate at [address] with the three facts the card needs, read now. */
    fun candidate(
        prefs: ZchatPreferences,
        address: String,
        name: String?,
        source: AdminCandidateSource,
    ): AdminCandidate =
        AdminCandidate(
            address = address,
            name = name,
            source = source,
            chatFinished = chatFinished(prefs, address),
            keyChangePending = keyChangePending(prefs, address),
            safetyNumberCompared = runCatching { prefs.isE2EVerified(address) }.getOrDefault(false),
        )

    /**
     * KI-25 R8 — the seven checks of a confirmation, in order; the first that fails is the refusal, null when all pass:
     * 1. the group's state is UNKNOWN or ORPHANED (a creator key that a chat still holds is never replaced);
     * 2. [candidate] is the address the phone's records give now ([recorded], R7);
     * 3. it is not an own address;
     * 4. the chat is finished ([chatFinished]);
     * 5. no key change is pending ([keyChangePending]);
     * 6. the user compared the safety number;
     * 7. the stored contact key is [shownKey], the one the dialog showed, whole strings.
     */
    @Suppress("LongParameterList")
    fun confirmRefusal(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        groupId: String,
        candidate: String,
        shownKey: String,
        recorded: String?,
    ): AdminConfirmRefusal? =
        when {
            !needsConfirmation(keyState(prefs, savedContacts, groupId)) -> AdminConfirmRefusal.NOT_NEEDED
            candidate != recorded -> AdminConfirmRefusal.NOT_THE_RECORDED_CHAT
            prefs.isSelfAddress(candidate) -> AdminConfirmRefusal.OWN_ADDRESS
            !chatFinished(prefs, candidate) -> AdminConfirmRefusal.NOT_A_SECURE_CHAT
            keyChangePending(prefs, candidate) -> AdminConfirmRefusal.KEY_CHANGED
            !prefs.isE2EVerified(candidate) -> AdminConfirmRefusal.NOT_VERIFIED
            prefs.getE2EPeerPublicKey(candidate) != shownKey -> AdminConfirmRefusal.KEY_DIFFERS
            else -> null
        }

    /**
     * KI-25 R6, R9 — the one line [groupId]'s chat shows about its admin, at most one, in this order:
     * - the check line while the state is UNKNOWN or ORPHANED;
     * - the key line, in a group this phone did not create: the newest received message cannot be read
     *   ([newestReceivedUnreadable]) and the admin's contact is known to run a ZChat older than 2.13, which cannot
     *   answer a key request;
     * - the member-list line: the state is OK, the creator key's holder is not the stored creator address (the group
     *   was confirmed, or joined through the fallback), no announce was ever applied, and the roster holds an ACTIVE
     *   member besides this phone and the admin.
     */
    fun notice(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        groupId: String,
        newestReceivedUnreadable: Boolean,
    ): GroupAdminNotice? {
        val state = keyState(prefs, savedContacts, groupId)
        if (needsConfirmation(state)) return GroupAdminNotice.CHECK_LINE
        val info = prefs.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) }
        if (info == null || !info.isActive) return null
        if (prefs.isGroupSelfCreated(groupId) || prefs.isSelfAddress(info.creatorAddress)) return null
        if (prefs.getGroupInviteNeedsJoin(groupId) != null) return null
        if (newestReceivedUnreadable) {
            val admin = adminTarget(prefs, savedContacts, info)
            if (runCatching { prefs.getPeerIronwoodCap(admin) }.getOrNull()?.capIronwood == false) return GroupAdminNotice.CATCHUP_KEY
        }
        if (state != AdminKeyState.OK) return null
        val holder = creatorHolder(prefs, savedContacts, info)
        if (holder == null || holder == info.creatorAddress) return null
        val announceState = runCatching { prefs.getGroupAnnounceState(groupId) as Any? }.getOrNull() as? AnnounceState
        if (announceState?.rosterAnnounced == true) return null
        val admin = setOf(prefs.resolvePeerAddress(info.creatorAddress), prefs.resolvePeerAddress(holder))
        val othersListed =
            prefs
                .getGroupMembers(groupId)
                ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                .orEmpty()
                .filter { it.status == MemberStatus.ACTIVE && !prefs.isSelfAddress(it.address) }
                .any { prefs.resolvePeerAddress(it.address) !in admin }
        return if (othersListed) GroupAdminNotice.CATCHUP_LIST else null
    }

    // ── ZC-704 (KI-25 R19): the admin's changes kept for the confirmation ──────────────────────────────────────────

    /** An entry is never applied, and leaves the list, 90 days after this phone's own clock kept it. */
    private const val KEPT_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1_000

    /** Per signer chat, the removals and the key changes kept (each counted apart): those with the highest epochs. */
    private const val KEPT_PER_CHAT_AND_KIND = 8

    /** An entry whose handler threw in this many runs leaves at the start of the next. */
    private const val KEPT_MAX_FAILED_RUNS = 3

    private const val RANK_ADMIN_ADDRESS = 0
    private const val RANK_VERIFIED = 1
    private const val RANK_FINISHED = 2

    /**
     * KI-25 D3 — the contact key of a finished chat with [address]: a contact key is stored there, this phone holds
     * its own key pair for it, and the chat is no request still waiting for Accept ([chatFinished]). Null otherwise.
     */
    fun finishedChatKey(
        prefs: ZchatPreferences,
        address: String,
    ): String? = prefs.getE2EPeerPublicKey(address)?.takeIf { chatFinished(prefs, address) }

    /**
     * KI-25 R19 — the rank of a signer chat in a group's list, lower is higher: 0 the chat at the group's stored admin
     * address that holds a contact key; 1 a finished chat whose safety number the user compared; 2 any other
     * finished chat. Null: no rank (also every chat request still waiting for Accept), and no change of it is kept.
     * These are the chats the confirm card could name.
     */
    fun signerChatRank(
        prefs: ZchatPreferences,
        info: GroupInfo,
        address: String,
    ): Int? =
        when {
            runCatching { prefs.getInboundKexAwaitingAccept(address) }.getOrNull() != null -> null
            address == info.creatorAddress && prefs.getE2EPeerPublicKey(address) != null -> RANK_ADMIN_ADDRESS
            finishedChatKey(prefs, address) == null -> null
            runCatching { prefs.isE2EVerified(address) }.getOrDefault(false) -> RANK_VERIFIED
            else -> RANK_FINISHED
        }

    /**
     * KI-25 R19, conditions 4 and 5 — the signer chat of a signed admin change the gate refused, which came over the
     * relays sealed by [sealSender]: the first chat whose stored contact key verifies [signature] over [signedData]
     * and that has a rank ([signerChatRank]). Tried in this order: the chat the message was routed to, then every chat
     * of the one address set ([contactAddresses]) bound to the same relay identity. The search goes on after a chat
     * that verifies and has no rank; a key of any other contact never counts. Null when there is none.
     */
    @Suppress("LongParameterList")
    fun signerChat(
        prefs: ZchatPreferences,
        savedContacts: Collection<String>,
        info: GroupInfo,
        sealSender: String,
        signedData: String,
        signature: String,
    ): String? {
        val relayKey = runCatching { prefs.getPeerNostrPubkey(sealSender) }.getOrNull()
        val sameIdentity =
            if (relayKey == null) {
                emptyList()
            } else {
                contactAddresses(prefs, savedContacts)
                    .filter { it != sealSender && runCatching { prefs.getPeerNostrPubkey(it) }.getOrNull() == relayKey }
            }
        return (listOf(sealSender) + sameIdentity).firstOrNull { chat ->
            val key = prefs.getE2EPeerPublicKey(chat)
            key != null &&
                runCatching { E2EEncryption.verify(key, signedData, signature) }.getOrDefault(false) &&
                signerChatRank(prefs, info, chat) != null
        }
    }

    /**
     * KI-25 R19 — is a change with this [outcome] kept? Only a copy that came over the relays (an event id and a seal
     * sender): as refused when the gate refused it, it carried a signature and a signer chat was found; as accepted
     * when its key could not be saved. Never one that was not parsed, whose handler threw, that was not newer or
     * that was applied.
     */
    fun keepAs(
        outcome: AdminChangeOutcome,
        eventId: String?,
        sealSender: String?,
    ): KeepAs =
        when {
            eventId.isNullOrEmpty() || sealSender.isNullOrEmpty() -> KeepAs.NOT_KEPT
            outcome is AdminChangeOutcome.NotSaved -> KeepAs.AS_ACCEPTED
            outcome is AdminChangeOutcome.RefusedByGate && outcome.signed && outcome.signerChat != null -> KeepAs.AS_REFUSED
            else -> KeepAs.NOT_KEPT
        }

    /**
     * ZC-668's rule for two invites of one chat: the new one replaces the kept one when its key epoch is higher, or
     * at one key epoch when its source time is later. An invite of a lower key epoch never replaces a kept one.
     */
    fun inviteReplaces(
        newKeyEpoch: Int,
        newAtMs: Long,
        keptKeyEpoch: Int,
        keptAtMs: Long,
    ): Boolean = newKeyEpoch > keptKeyEpoch || (newKeyEpoch == keptKeyEpoch && newAtMs > keptAtMs)

    /**
     * KI-25 R9 — a history pass began after a group's confirmation when its number is above the number recorded for
     * the group, or the group has none (the key was written in an earlier process, or never by a confirmation).
     */
    fun passBeganAfterConfirmation(
        passNumber: Long,
        confirmedAtPass: Long?,
    ): Boolean = confirmedAtPass == null || passNumber > confirmedAtPass

    private fun keptTooLong(
        entry: KeptAdminChange,
        nowMs: Long,
    ): Boolean = nowMs - entry.kept > KEPT_MAX_AGE_MS

    /**
     * KI-25 R19, the bounds — the list after [incoming] was offered to [kept] at [nowMs]; [rankOf] gives a signer
     * chat's rank ([signerChatRank]). The stored order stays and a kept [incoming] comes last.
     * - Entries kept more than 90 days ago leave.
     * - Not kept: a second entry of one event id; kept as refused, a second one of one signer chat, kind and epoch,
     *   and one of a chat with no rank; kept as accepted, a second one of one kind and epoch.
     * - Among the entries kept as refused, per signer chat: the eight removals and the eight key changes with the
     *   highest epochs (a ninth pushes that chat's lowest epoch of that kind out, which can be the incoming one), and
     *   one invite, chosen by [inviteReplaces].
     * - Per group [KeptAdminChange.MAX_ENTRIES] entries. At a full list an entry kept as refused takes a place from a
     *   chat that ranks below its own; an entry kept as accepted always takes one, and is not counted in a chat's
     *   eight. The place is always an entry kept as refused ([leavingEntry]).
     * - ZC-952: when no chat ranks below the incoming one's, an entry kept as refused takes the oldest entry of
     *   another unverified chat ([unverifiedPlace]); never one of a verified chat, of the chat at the admin's address,
     *   or of its own chat (which stays within its 17). When there is none to take, the incoming entry is not kept.
     */
    fun withKeptChange(
        kept: List<KeptAdminChange>,
        incoming: KeptAdminChange,
        rankOf: (String) -> Int?,
        nowMs: Long,
    ): List<KeptAdminChange> {
        val live = kept.filterNot { keptTooLong(it, nowMs) }
        if (live.any { it.id == incoming.id }) return live
        val ranks = HashMap<String, Int>()
        val rank = { chat: String -> ranks.getOrPut(chat) { rankOf(chat) ?: Int.MAX_VALUE } }
        val offered = if (incoming.accepted) withAccepted(live, incoming) else withRefused(live, incoming, rankOf)
        if (offered == null || offered.size <= KeptAdminChange.MAX_ENTRIES) return offered ?: live
        val refused = offered.filter { it !== incoming && !it.accepted }
        val leaving = leavingEntry(refused, rank) ?: return live
        if (incoming.accepted || rank(leaving.by) > rank(incoming.by)) return offered.filter { it !== leaving }
        val place = unverifiedPlace(refused.filter { it.by != incoming.by }, rank) ?: return live
        return offered.filter { it !== place }
    }

    /**
     * ZC-952 — the place an entry kept as refused takes at a full list when no chat ranks below its own: of
     * [candidates] (other chats' entries kept as refused), those of unverified finished chats; the chat that holds the
     * most of them (a flood pushes out its own kind first), of several the one whose newest entry was kept most
     * recently (ZC-598); of that chat its oldest entry (then its invite, then its lowest epoch). Null when there is none.
     */
    private fun unverifiedPlace(
        candidates: List<KeptAdminChange>,
        rank: (String) -> Int,
    ): KeptAdminChange? {
        val byChat = candidates.filter { rank(it.by) >= RANK_FINISHED }.groupBy { it.by }
        val chat =
            byChat.keys.minWithOrNull(
                compareByDescending<String> { byChat.getValue(it).size }
                    .thenByDescending { byChat.getValue(it).maxOf { entry -> entry.kept } }
                    .thenBy { it },
            ) ?: return null
        return byChat.getValue(chat).minWithOrNull(
            compareBy<KeptAdminChange>({ it.kept }, { if (it.kind == KeptAdminChange.INVITE) 0 else 1 }, { it.epoch })
                .thenBy { if (it.kind == KeptAdminChange.KEY) 0 else 1 }
                .thenBy { it.id },
        )
    }

    /** [live] with the accepted [incoming], or null when an accepted entry of its kind and epoch is held already. */
    private fun withAccepted(
        live: List<KeptAdminChange>,
        incoming: KeptAdminChange,
    ): List<KeptAdminChange>? =
        if (live.any { it.accepted && it.kind == incoming.kind && it.epoch == incoming.epoch }) null else live + incoming

    /** [live] with the refused [incoming] inside its own chat's bounds, or null when it is not kept there. */
    private fun withRefused(
        live: List<KeptAdminChange>,
        incoming: KeptAdminChange,
        rankOf: (String) -> Int?,
    ): List<KeptAdminChange>? {
        if (rankOf(incoming.by) == null) return null
        val ofChatAndKind = live.filter { !it.accepted && it.by == incoming.by && it.kind == incoming.kind }
        if (incoming.kind == KeptAdminChange.INVITE) {
            val held = ofChatAndKind.firstOrNull() ?: return live + incoming
            if (!inviteReplaces(incoming.epoch, incoming.at, held.epoch, held.at)) return null
            return live.filter { it !== held } + incoming
        }
        if (ofChatAndKind.any { it.epoch == incoming.epoch }) return null
        if (ofChatAndKind.size < KEPT_PER_CHAT_AND_KIND) return live + incoming
        val lowest = ofChatAndKind.minByOrNull { it.epoch } ?: return live + incoming
        return if (incoming.epoch < lowest.epoch) null else live.filter { it !== lowest } + incoming
    }

    /**
     * The entry that gives its place up at a full list, of [candidates] (entries kept as refused): from the
     * lowest-ranking chat; of several chats of that rank, from the one whose newest entry was kept longest ago; of
     * that chat, its invite first, then its lowest epoch (at one epoch the key change before the removal).
     */
    private fun leavingEntry(
        candidates: List<KeptAdminChange>,
        rank: (String) -> Int,
    ): KeptAdminChange? {
        val byChat = candidates.groupBy { it.by }
        val chat =
            byChat.keys.minWithOrNull(
                compareByDescending<String> { rank(it) }
                    .thenBy { byChat.getValue(it).maxOf { entry -> entry.kept } }
                    .thenBy { it },
            ) ?: return null
        val entries = byChat.getValue(chat)
        return entries.firstOrNull { it.kind == KeptAdminChange.INVITE }
            ?: entries.minWithOrNull(compareBy({ it.epoch }, { if (it.kind == KeptAdminChange.KEY) 0 else 1 }, { it.id }))
    }

    /**
     * KI-25 R19 — the order of a run: ascending epoch; at one epoch the removal before the key change (a removal and
     * the re-send of its key carry one epoch, and the second of them is refused as not newer); then by source time,
     * then by id.
     */
    fun orderedKeptChanges(kept: List<KeptAdminChange>): List<KeptAdminChange> = kept.sortedWith(runOrder)

    private val runOrder: Comparator<KeptAdminChange> =
        compareBy(
            { it.epoch },
            {
                when (it.kind) {
                    KeptAdminChange.KICK -> 0
                    KeptAdminChange.KEY -> 1
                    else -> 2
                }
            },
            { it.at },
            { it.id },
        )

    /**
     * KI-25 R19, step 1 of the hook at the end of a history pass — [kept] without the entries kept more than 90 days
     * before [nowMs] and without those whose handler threw in three runs. An entry whose `kept` time the reader had to
     * set ([KeptAdminChange.keptReadAsNow]) comes back as a plain entry, so the hook writes that time down.
     */
    fun keptChangesBeforeRun(
        kept: List<KeptAdminChange>,
        nowMs: Long,
    ): List<KeptAdminChange> =
        kept
            .filterNot { keptTooLong(it, nowMs) || it.failedRuns >= KEPT_MAX_FAILED_RUNS }
            .map { if (it.keptReadAsNow) it.copy(keptReadAsNow = false) else it }

    /**
     * KI-25 R19 — [entry] after a run handed it to its handler: null when it leaves the list (applied, not newer,
     * refused by the gate, not parsed: nothing brings such a change back); with its count of failed runs raised by
     * one when the handler threw; unchanged when its key could not be saved, which counts nothing.
     */
    fun keptChangeAfterRun(
        entry: KeptAdminChange,
        outcome: AdminChangeOutcome,
    ): KeptAdminChange? =
        when (outcome) {
            is AdminChangeOutcome.Failed -> entry.copy(failedRuns = entry.failedRuns + 1)
            is AdminChangeOutcome.NotSaved -> entry
            else -> null
        }

    /**
     * KI-25 R19 — the removals and key changes of [kept] that are due at the end of a history pass, in the order of
     * the run ([orderedKeptChanges]); an invite is never run. None in a group that is not active or is this phone's
     * own. An entry kept as accepted is due at the end of every pass: it was judged when it arrived and only its key
     * is missing. An entry kept as refused is due only when the pass read the whole history ([passComplete]), the
     * group reads [AdminKeyState.OK] and the pass began after the confirmation: the chain's changes first.
     */
    @Suppress("LongParameterList")
    fun dueKeptChanges(
        kept: List<KeptAdminChange>,
        state: AdminKeyState,
        groupActive: Boolean,
        ownGroup: Boolean,
        passComplete: Boolean,
        beganAfterConfirmation: Boolean,
    ): List<KeptAdminChange> {
        if (!groupActive || ownGroup) return emptyList()
        val refusedDue = passComplete && state == AdminKeyState.OK && beganAfterConfirmation
        return orderedKeptChanges(
            kept.filter { it.kind != KeptAdminChange.INVITE && (it.accepted || refusedDue) },
        )
    }
}
