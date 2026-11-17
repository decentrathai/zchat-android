package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 MA2 (MEMBER_ANNOUNCE_DESIGN.md v2, §3, §5) — one phone's stores for the member-announce receiver tests.
 *
 * Every group, roster, key, epoch, announce state, needs-join record and E2E link of the phone lives here, answered
 * by method name (so this file compiles against the MA1 store). [link] gives the phone a completed key exchange with a
 * peer and returns the PEER's key pair for us (what the peer signs with when it writes to this phone). The phone is
 * [self] ([HandshakeHarness.SELF] unless a test says otherwise), the handshake-spend consent is GRANTED, and nothing
 * here spends: a test counts proposals on its [HandshakeSeam].
 *
 * All addresses, keys, txids and group ids are synthetic.
 */
internal class AnnouncePhone(
    val self: String = HandshakeHarness.SELF,
) {
    val infos = ConcurrentHashMap<String, String>()
    val rosters = ConcurrentHashMap<String, String>()
    val keys = ConcurrentHashMap<String, String>()
    val epochs = ConcurrentHashMap<String, Int>()
    val floors = ConcurrentHashMap<String, Int>()

    /**
     * Round 9 R9-3: per group, the removal each floor raise came from (the epoch it rotated into) and that removal's
     * height (the kick's mined height, or the height this phone had scanned to when it acted on it), null when unknown.
     */
    val kickHeights = ConcurrentHashMap<String, Map<Int, Long?>>()
    val states = ConcurrentHashMap<String, AnnounceState>()
    val needsJoin = ConcurrentHashMap<String, String>()

    /** When true, a waiting record is stored as a returned Join leaves it (the relay-failed flag set); see [withRelayTryFailed]. */
    @Volatile var needsJoinAfterRelayTry: Boolean = false
    val heldAccepts = ConcurrentHashMap<String, String>()
    val messages = ConcurrentHashMap<String, String>()
    val ourKeys = ConcurrentHashMap<String, E2EKeyPair>()
    val peerKeys = ConcurrentHashMap<String, String>()
    val nostrKeys = ConcurrentHashMap<String, String>()
    val aliases = ConcurrentHashMap<String, String>()
    val awaitingAccept = ConcurrentHashMap<String, String>()
    val keyChanged: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val selfCreated: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val drafts = ConcurrentHashMap<String, String>()
    val spendConsents = ConcurrentHashMap<String, Set<String>>()
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** KI-23: the paid deliveries of a group key per grant key (the creator's Resend key writes one before it pays). */
    val keyGrantPaidCounts = ConcurrentHashMap<String, Int>()

    /** A completed key exchange between this phone and [peer]; returns [peer]'s key pair for this phone. */
    fun link(peer: String): E2EKeyPair {
        val ours = E2EEncryption.generateKeyPair()
        val theirs = E2EEncryption.generateKeyPair()
        ourKeys[peer] = ours
        peerKeys[peer] = theirs.publicKey
        return theirs
    }

    /** The session key this phone shares with [peer] (what k2 / a rotated key is wrapped under). */
    fun sessionWith(peer: String): ByteArray =
        E2EEncryption.deriveSharedSecret(ourKeys.getValue(peer).privateKey, peerKeys.getValue(peer), E2EKeyVersion.fromValue(2))

    fun info(groupId: String): GroupInfo = ZMSGGroupProtocol.deserializeGroupInfo(infos.getValue(groupId))!!

    fun roster(groupId: String): List<GroupMember> = rosters[groupId]?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }.orEmpty()

    fun status(
        groupId: String,
        address: String
    ): MemberStatus? = roster(groupId).firstOrNull { it.address == address }?.status

    fun state(groupId: String): AnnounceState = states[groupId] ?: AnnounceState()

    fun storedMessageIds(groupId: String): List<String> =
        messages[groupId]
            ?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } } }
            .orEmpty()

    fun putGroup(
        info: GroupInfo,
        roster: List<GroupMember>,
        epoch: Int,
        groupKey: String,
    ) {
        infos[info.groupId] = ZMSGGroupProtocol.serializeGroupInfo(info)
        rosters[info.groupId] = ZMSGGroupProtocol.serializeGroupMembers(roster)
        epochs[info.groupId] = epoch
        keys["${info.groupId}|$epoch"] = groupKey
    }

    private fun resolve(address: String): String {
        var a = address
        repeat(8) { a = aliases[a] ?: return a }
        return a
    }

    /**
     * Round 9 R9-5: ZchatPreferencesImpl marks a group's store as changed on every group save (messages, members, the
     * announce state), which an open group screen follows. This fake does the same through the same companion call, when
     * the build has it (read by name, so this file compiles on a build without it).
     */
    private fun markGroupStoreChanged(groupId: String) {
        storeChangedHook?.invoke(ZchatPreferences.Companion, groupId)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]

            fun s(i: Int) = args!![i] as String
            calls += method.name
            when (method.name) {
                "withGroupLock" -> synchronized(ZchatPreferences.groupLock(s(0))) { (arg(1) as Function0<*>).invoke() }
                "getGroupAnnounceState" -> states[s(0)] ?: AnnounceState()
                "saveGroupAnnounceState" -> {
                    states[s(0)] = arg(1) as AnnounceState
                    markGroupStoreChanged(s(0))
                    true
                }
                "getAllGroupIds" -> infos.keys.toSet()
                "getGroupInfo" -> infos[s(0)]
                "saveGroupInfo" -> {
                    infos[s(0)] = s(1)
                    null
                }
                "getGroupMembers" -> rosters[s(0)]
                "saveGroupMembers" -> {
                    rosters[s(0)] = s(1)
                    markGroupStoreChanged(s(0))
                    null
                }
                "saveGroupMembersDurably" -> {
                    rosters[s(0)] = s(1)
                    if (args!!.size == 3) states[s(0)] = arg(2) as AnnounceState
                    markGroupStoreChanged(s(0))
                    true
                }
                "deleteGroup" -> {
                    infos.remove(s(0))
                    rosters.remove(s(0))
                    needsJoin.remove(s(0))
                    null
                }
                "isGroupSelfCreated" -> s(0) in selfCreated
                "isSelfAddress" -> arg(0) == self
                "resolvePeerAddress" -> resolve(s(0))
                "setPeerAddressAlias" -> {
                    aliases[s(0)] = s(1)
                    aliases.remove(s(1))
                    null
                }
                "clearPeerAddressAlias" -> {
                    aliases.remove(s(0))
                    null
                }
                // Round 8 MA-V1: the address a verified signed accept declared (the canonical side of an alias).
                "isPeerAddressAliasTarget" -> s(0) in aliases.values
                "getGroupKeyEpoch" -> epochs[s(0)] ?: 0
                "setGroupKeyEpoch" -> {
                    epochs[s(0)] = arg(1) as Int
                    null
                }
                "getGroupKey" -> keys["${arg(0)}|${arg(1)}"]
                "saveGroupKey" -> {
                    keys["${arg(0)}|${arg(1)}"] = s(2)
                    null
                }
                "getGroupRemovalFloor" -> floors[s(0)] ?: 0
                "setGroupRemovalFloor" -> {
                    floors[s(0)] = maxOf(floors[s(0)] ?: 0, arg(1) as Int)
                    // Round 9 R9-3: the three-argument form also records the removal's height (the same epoch twice
                    // keeps the lower height; an unknown height stays unknown).
                    if (args!!.size >= 3) {
                        val epoch = arg(1) as Int
                        val height = arg(2) as Long?
                        val held = kickHeights[s(0)].orEmpty()
                        val kept =
                            when {
                                !held.containsKey(epoch) -> height
                                height == null || held[epoch] == null -> null
                                else -> minOf(height, held.getValue(epoch)!!)
                            }
                        kickHeights[s(0)] = held + (epoch to kept)
                    }
                    null
                }
                "getGroupRemovalKickHeights" -> kickHeights[s(0)].orEmpty()
                "getE2EPeerPublicKey" -> peerKeys[s(0)]
                "setE2EPeerPublicKey" -> {
                    peerKeys[s(0)] = s(1)
                    null
                }
                // ZC-634: the one write of a contact key (no claim marks are modelled here); always saved.
                "storePeerKey" -> {
                    peerKeys[s(0)] = s(1)
                    true
                }
                "getE2EPrivateKey" -> ourKeys[s(0)]?.privateKey
                "getE2EOurPublicKey" -> ourKeys[s(0)]?.publicKey
                "getE2EKeyVersion" -> 2
                "isE2EKeyExchangeComplete" -> peerKeys.containsKey(s(0)) && ourKeys.containsKey(s(0))
                "isE2EEnabled" -> ourKeys.containsKey(s(0))
                "isE2EKeyChanged" -> s(0) in keyChanged
                "getInboundKexAwaitingAccept" -> awaitingAccept[s(0)]
                "getPeerNostrPubkey" -> nostrKeys[s(0)]
                "getAllConversationPeerAddresses" -> ourKeys.keys.toSet()
                "getAllPeerToConvIdMappings" -> ourKeys.keys.associateWith { "%08X".format(it.hashCode()) }
                "hasConsentedToHandshakeSpend" -> true
                "getGroupMessages" -> messages[s(0)]
                "saveGroupMessages" -> {
                    messages[s(0)] = s(1)
                    markGroupStoreChanged(s(0))
                    null
                }
                "getGroupInviteNeedsJoin" -> needsJoin[s(0)]
                "setGroupInviteNeedsJoin" -> {
                    val value = arg(1) as String?
                    if (value == null) {
                        needsJoin.remove(s(0))
                    } else {
                        needsJoin[s(0)] = if (needsJoinAfterRelayTry) withRelayTryFailed(value) else value
                    }
                    true
                }
                "getHeldGroupAccepts" -> HashMap(heldAccepts)
                "setHeldGroupAccept" -> {
                    val value = arg(1) as String?
                    if (value == null) heldAccepts.remove(s(0)) else heldAccepts[s(0)] = value
                    true
                }
                "getGroupMessageSequence" -> 0L
                "incrementGroupMessageSequence" -> 1L
                "getGroupDraft" -> drafts[s(0)]
                "setGroupDraft" -> {
                    drafts[s(0)] = s(1)
                    null
                }
                "clearGroupDraft" -> {
                    drafts.remove(s(0))
                    null
                }
                "getGroupMessageSpendConsent" -> spendConsents[s(0)].orEmpty()
                "setGroupMessageSpendConsent" -> {
                    @Suppress("UNCHECKED_CAST")
                    spendConsents[s(0)] = (arg(1) as Collection<String>).toSet()
                    true
                }
                "getGroupKeyGrantPaidCount" -> keyGrantPaidCounts[s(0)] ?: 0
                "setGroupKeyGrantPaidCount" -> {
                    if ((arg(1) as Int) > 0) keyGrantPaidCounts[s(0)] = arg(1) as Int else keyGrantPaidCounts.remove(s(0))
                    true
                }
                "toString" -> "AnnouncePhone($self)"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        List::class.java -> emptyList<Any>()
                        Set::class.java -> emptySet<Any>()
                        Map::class.java -> emptyMap<Any, Any>()
                        else -> null
                    }
            }
        } as ZchatPreferences
}

/** Round 9 R9-5 — ZchatPreferences.markGroupStoreChanged when this build has it, else null. */
private val storeChangedHook: java.lang.reflect.Method? =
    runCatching { ZchatPreferences.Companion::class.java.getMethod("markGroupStoreChanged", String::class.java) }.getOrNull()

/** The member-announce paths of ChatViewModel and GroupViewModel, driven as the scan, the relays and the screens drive them. */
internal object AnnounceDriver {
    const val GID = "zgrp_0a1b2c3d4e5f60718293a4b5"
    const val ADMIN = "usyntheticma2admin0000000001"
    const val X = "usyntheticma2memberx00000001"

    /** Another address form of [X] (not aliased to it: the two forms are different canonical addresses). */
    const val X2 = "usyntheticma2memberx00000002"
    const val Y = "usyntheticma2membery00000001"
    const val Z = "usyntheticma2memberz00000001"
    const val ADMIN_NOSTR = "7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a"

    /** 2026-09-21, as milliseconds: the base of every source time and announce number here. */
    const val T = 1_790_000_000_000L

    fun newGroupKey(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /**
     * A member's phone (this phone, [AnnouncePhone.self]) in group [GID], created by [ADMIN]: ADMIN and us ACTIVE at
     * key epoch 1, the admin's key captured at join ([withCreatorKey]). Returns the admin's key pair for this phone.
     */
    fun memberPhone(
        phone: AnnouncePhone,
        withCreatorKey: Boolean = true,
        extraRows: List<GroupMember> = emptyList(),
        epoch: Int = 1,
    ): E2EKeyPair {
        val admin = phone.link(ADMIN)
        phone.putGroup(
            GroupInfo(
                groupId = GID,
                name = "Announce group",
                creatorAddress = ADMIN,
                createdAt = Instant.ofEpochMilli(T),
                isActive = true,
                creatorPublicKey = if (withCreatorKey) admin.publicKey else null,
            ),
            listOf(
                GroupMember(address = ADMIN, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(address = phone.self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE),
            ) + extraRows,
            epoch,
            newGroupKey(),
        )
        return admin
    }

    /** A GP memo the admin signed with [signer] (the admin's key for this phone, or a forger's). */
    fun gp(
        op: AnnounceOp,
        subject: String,
        n: Long,
        epoch: Int,
        signer: E2EKeyPair,
        groupId: String = GID,
    ): String {
        val sig =
            E2EEncryption.sign(
                signer.privateKey,
                ZMSGGroupProtocol.groupMemberAnnounceSignedData(groupId, op, subject, n, epoch, null)
            )
        return ZMSGGroupProtocol.createGroupMemberAnnounceMessage(groupId, op, subject, n, epoch, sig)!!
    }

    /** A GP memo with no signature at all ("sig" missing), as a forger with no key would write it. */
    fun unsignedGp(
        subject: String,
        n: Long,
        epoch: Int
    ): String =
        "ZMSG:3.0:GROUP:GP:$GID:" +
            JSONObject()
                .put("v", 1)
                .put("o", "A")
                .put("m", subject)
                .put("n", n)
                .put("e", epoch)
                .toString()

    /** An encrypted GROUP_MSG from [sender] under the group key at [epoch]; signed with [signer] when given. */
    fun gm(
        phone: AnnouncePhone,
        sender: String,
        text: String,
        signer: E2EKeyPair? = null,
        epoch: Int = 1,
        seq: Long = 1,
    ): String =
        ZMSGGroupProtocol.createGroupMsgMessage(
            GID,
            seq,
            epoch,
            sender,
            text,
            ZMSGGroupProtocol.decodeGroupKey(phone.keys.getValue("$GID|$epoch")),
            signer?.let { pair -> { data: String -> E2EEncryption.sign(pair.privateKey, data) } },
        )

    /** A signed GROUP_KICK of [kicked] by [ADMIN] to [newEpoch], carrying [newKey] wrapped for this phone. */
    fun kick(
        phone: AnnouncePhone,
        admin: E2EKeyPair,
        kicked: String,
        newEpoch: Int,
        newKey: String,
    ): String {
        val wrapped = E2EEncryption.encrypt(newKey, phone.sessionWith(ADMIN))
        val sig = E2EEncryption.sign(admin.privateKey, ZMSGGroupProtocol.groupKickSignedData(GID, kicked, ADMIN, newEpoch, wrapped))
        return ZMSGGroupProtocol.createGroupKickMessage(GID, kicked, ADMIN, newEpoch, wrapped, sig)
    }

    /** A GROUP_LEAVE from [leaver], signed with [signer] (the leaver's key for this phone) when given. */
    fun gl(
        leaver: String,
        tsSeconds: Long,
        signer: E2EKeyPair? = null
    ): String =
        ZMSGGroupProtocol.createGroupLeaveMessage(
            GID,
            leaver,
            signer?.let { E2EEncryption.sign(it.privateKey, ZMSGGroupProtocol.groupLeaveSignedData(GID, leaver, tsSeconds)) },
            tsSeconds,
        )

    /** A compact GROUP_INVITE from [inviter], its key wrapped under [session] (the session with the inviter). */
    fun gi(
        inviter: String,
        session: ByteArray,
        groupKey: String,
        keyEpoch: Int,
        groupId: String = GID,
    ): String =
        "ZMSG:3.0:GROUP:GI:$groupId:" +
            JSONObject()
                .put("name", "Announce group")
                .put("inviter", inviter)
                .put("key_epoch", keyEpoch)
                .put("k2", E2EEncryption.encrypt(groupKey, session))
                .toString()

    fun txId(n: Int): TransactionId = TransactionId.new(ByteArray(32) { (it * 3 + n * 17).toByte() })

    /**
     * One group memo as the scan (on-chain: [txId], [time] = the block time) or the relays ([sender] = the
     * seal-authenticated peer, [eventId]) hand it to ChatViewModel. [ownSend] / [ownRecipient] are what the scan
     * knows about our own sent transactions; they are passed only to a build whose processGroupMessage takes them
     * (MA2), so this file runs against the MA1 build too.
     */
    fun receive(
        vm: ChatViewModel,
        memo: String,
        time: Instant?,
        txId: TransactionId? = null,
        sender: String? = null,
        eventId: String? = null,
        ownSend: Boolean = false,
        ownRecipient: String? = null,
        // Round 9 R9-3: the transaction's mined height as the scan knows it (null: unmined, or over the relays). Passed
        // only to a build whose processGroupMessage takes it.
        minedHeight: Long? = null,
    ) {
        val method =
            ChatViewModel::class.java.declaredMethods
                .filter { it.name == "processGroupMessage" && !it.isSynthetic }
                .maxBy { it.parameterCount }
                .apply { isAccessible = true }
        val args: Array<Any?> =
            when {
                method.parameterCount >= 8 -> arrayOf(memo, txId, time, sender, eventId, ownSend, ownRecipient, minedHeight)
                method.parameterCount >= 7 -> arrayOf(memo, txId, time, sender, eventId, ownSend, ownRecipient)
                else -> arrayOf(memo, txId, time, sender, eventId)
            }
        try {
            method.invoke(vm, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A real ChatViewModel over [phone] as [AnnouncePhone.self], spending through [seam] (nothing may be proposed). */
    suspend fun chatVm(
        phone: AnnouncePhone,
        seam: HandshakeSeam,
    ): ChatViewModel =
        HandshakeHarness.chatViewModelWith(phone.proxy, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(phone.self))
        }

    /** The group screen's GroupViewModel over [phone] (the [GroupPaidOnceFixture] wiring and SDK seam), as [AnnouncePhone.self]. */
    suspend fun groupVm(
        phone: AnnouncePhone,
        f: GroupPaidOnceFixture,
    ): GroupViewModel {
        val vm = f.groupViewModel()
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, phone.proxy)
        @Suppress("UNCHECKED_CAST")
        (
            GroupViewModel::class.java
                .getDeclaredField("_currentUserAddress")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<String?>
        ).value = phone.self
        vm.consumeGroupSendEvent()
        return vm
    }

    /** Who one group message goes to (GroupViewModel.messageRecipients, private). */
    fun messageRecipients(
        vm: GroupViewModel,
        groupId: String = GID
    ): List<String> {
        @Suppress("UNCHECKED_CAST")
        return GroupViewModel::class.java
            .getDeclaredMethod("messageRecipients", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, groupId) as List<String>
    }

    /** The group screen's conversation after a load, or null. */
    suspend fun loadedConversation(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
        groupId: String = GID,
    ): GroupConversation? {
        f.awaitNewJobs(vm) { vm.loadGroupDetail(groupId) }
        return (vm.groupDetailState.value as? GroupDetailState.Success)?.conversation
    }

    /** A GroupConversation field added by MA2 (read by name, so this file compiles before it exists); null when absent. */
    fun conversationSet(
        conversation: GroupConversation?,
        name: String
    ): Set<*>? =
        conversation?.let { c ->
            runCatching {
                GroupConversation::class.java
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(c) as? Set<*>
            }.getOrNull()
        }

    fun relayAcks(published: MutableList<Pair<String, String>>) =
        NostrChatBridge.registerPublisher { plaintext, recipient ->
            published += plaintext to recipient
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-relay-event-${published.size}")
        }
}
