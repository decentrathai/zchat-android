package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerEntry
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnouncePair
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMemberAnnounce
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.announceKeyHash
import co.electriccoin.zcash.ui.screen.chat.model.casAnnounceLedger
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 8 MA3 (MEMBER_ANNOUNCE_DESIGN.md v2, §4, §4.1, §6.0-6.4) — several phones of one group for the announcer tests.
 *
 * [AnnouncerPhone] is one phone: the MA2 store ([AnnouncePhone]) plus the announcer's ledger, announce counter and
 * gp1 set (answered by method name, so this file compiles against the MA2 build). [linkPhones] gives two phones a
 * completed key exchange with each other (each holds the other's key), so what one signs the other verifies.
 * [RelayNet] is the relays: one publisher for the whole process that records every relay copy by recipient NOSTR key
 * and acks it. [AnnounceSeam] is the wallet: it records every on-chain memo (recipient and memo text) a GroupViewModel
 * asks to pay for, and pays nothing real.
 *
 * Every test runs with the handshake-spend consent GRANTED. All addresses, keys, ids and memos are synthetic.
 */
internal class AnnouncerPhone(
    self: String,
) {
    val base = AnnouncePhone(self)
    val self: String get() = base.self
    val ledgers = ConcurrentHashMap<String, List<AnnounceLedgerEntry>>()
    val counters = ConcurrentHashMap<String, Long>()
    val gp1: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When true every ledger compare-and-set is refused (as when the write did not reach the disk). */
    @Volatile var casRefused = false

    fun ledger(groupId: String = MA3.GID): List<AnnounceLedgerEntry> = ledgers[groupId].orEmpty()

    fun state(groupId: String = MA3.GID) = base.state(groupId)

    fun status(
        address: String,
        groupId: String = MA3.GID
    ): MemberStatus? = base.status(groupId, address)

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            fun s(i: Int) = args!![i] as String
            when (method.name) {
                "getGroupAnnounceLedger" -> ledgers[s(0)].orEmpty()
                "casGroupAnnounce" ->
                    synchronized(ZchatPreferences.groupLock(s(0))) {
                        if (casRefused) {
                            false
                        } else {
                            val next =
                                casAnnounceLedger(
                                    ledgers[s(0)].orEmpty(),
                                    args!![1] as AnnouncePair,
                                    args[2] as AnnounceLedgerEntry?,
                                    args[3] as AnnounceLedgerEntry?,
                                )
                            if (next != null) ledgers[s(0)] = next
                            next != null
                        }
                    }
                "getGroupAnnounceCounter" -> counters[s(0)] ?: 0L
                "raiseGroupAnnounceCounter" ->
                    synchronized(ZchatPreferences.groupLock(s(0))) {
                        val n = args!![1] as Long
                        if (n > (counters[s(0)] ?: 0L)) counters[s(0)] = n
                        true
                    }
                "isGp1Capable" -> s(0) in gp1
                "markGp1Capable" -> {
                    @Suppress("UNCHECKED_CAST")
                    gp1 += args!![0] as Set<String>
                    true
                }
                else ->
                    try {
                        method.invoke(base.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}

/** One on-chain memo a GroupViewModel asked the wallet to pay for. */
internal data class PaidMemo(
    val recipient: String,
    val memo: String,
)

/**
 * The wallet under a GroupViewModel: records each proposal's recipient and first memo (decoded from the ZIP 321
 * request), and submits it. [failProposal] throws at the proposal with that 1-based number (before anything is stored:
 * nothing sent); [storedThenRejected] makes that 1-based submit "stored, then rejected" (it may still be broadcast);
 * [onProposal] runs at every proposal, before the submit is reached.
 */
internal class AnnounceSeam(
    private val failProposal: Pair<Int, Exception>? = null,
    private val storedThenRejected: Int? = null,
    @Volatile var onProposal: ((n: Int, memo: PaidMemo) -> Unit)? = null,
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    val proposals: MutableList<PaidMemo> = Collections.synchronizedList(mutableListOf())
    val submitted: MutableList<PaidMemo> = Collections.synchronizedList(mutableListOf())
    private val proposalCount = AtomicInteger()
    private val submitCount = AtomicInteger()

    @Volatile private var last: PaidMemo? = null

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean
    ): Zip321TransactionProposal {
        val paid = decode(zip321Uri)
        proposals += paid
        val n = proposalCount.incrementAndGet()
        onProposal?.invoke(n, paid)
        if (failProposal != null && failProposal.first == n) throw failProposal.second
        last = paid
        return syntheticZip321Proposal(paid.recipient, amount = 1_000L, memo = "synthetic-announce-memo")
    }

    // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
    // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
    ): SubmitResult = submitTransaction(proposal, usk)

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): SubmitResult {
        val n = submitCount.incrementAndGet()
        last?.let { submitted += it }
        if (storedThenRejected == n) {
            return SubmitResult.Failure(txIds = listOf("synthetic-stored-$n"), code = 18, description = "rejected")
        }
        return SubmitResult.Success(txIds = listOf("synthetic-announce-txid-$n"))
    }

    private fun decode(uri: String): PaidMemo {
        val recipient = uri.removePrefix("zcash:").substringBefore('?')
        val encoded =
            uri
                .substringAfter('?')
                .split('&')
                .first { it.startsWith("memo=") }
                .removePrefix("memo=")
        return PaidMemo(recipient, String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
    }
}

/** The relays: every relay copy published in this process, by recipient NOSTR key; acked unless [acks] says not. */
internal class RelayNet {
    val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Volatile var acks: (npub: String) -> Int = { 1 }

    fun register() =
        NostrChatBridge.registerPublisher { plaintext, recipient ->
            published += recipient to plaintext
            NostrChatBridge.PublishResult(acks = acks(recipient), messageId = "synthetic-ma3-event-${published.size}")
        }

    fun clear() = published.clear()

    /** Every relay copy to [npub] (all of them when null). */
    fun to(npub: String?): List<String> = published.filter { npub == null || it.first == npub }.map { it.second }

    /** The member announces (GP) published to [npub]. */
    fun gps(npub: String? = null): List<GroupMemberAnnounce> = to(npub).mapNotNull { MA3.parseGp(it) }

    fun ofType(
        type: GroupMessageType,
        npub: String? = null
    ): List<String> = to(npub).filter { ZMSGGroupProtocol.parseMessageType(it) == type }
}

/** The group every MA3 test uses, its phones and the ways to drive them. */
internal object MA3 {
    const val GID = "zgrp_1a2b3c4d5e6f708192a3b4c5"
    const val C = "usyntheticma3adminc0000000001"
    const val A = "usyntheticma3membera000000001"
    const val B = "usyntheticma3memberb000000001"
    const val D = "usyntheticma3memberd000000001"
    const val X = "usyntheticma3memberx000000001"

    val NPUB: Map<String, String> =
        mapOf(
            C to "c".repeat(64),
            A to "a".repeat(64),
            B to "b".repeat(64),
            D to "d".repeat(64),
            X to "e".repeat(64),
        )

    val groupKey: String = AnnounceDriver.newGroupKey()

    /** Now, as milliseconds: announce numbers are the announcer's wall clock, so source times here follow it. */
    fun now(): Long = System.currentTimeMillis()

    /** A completed key exchange between [a] and [b], each holding the other's key (and its own key for the other). */
    fun linkPhones(
        a: AnnouncerPhone,
        b: AnnouncerPhone
    ) {
        val ka = E2EEncryption.generateKeyPair()
        val kb = E2EEncryption.generateKeyPair()
        a.base.ourKeys[b.self] = ka
        a.base.peerKeys[b.self] = kb.publicKey
        b.base.ourKeys[a.self] = kb
        b.base.peerKeys[a.self] = ka.publicKey
    }

    /** [phone]'s own key pair for [peer] (what it signs with when it writes to [peer]). */
    fun keyFor(
        phone: AnnouncerPhone,
        peer: String
    ): E2EKeyPair = phone.base.ourKeys.getValue(peer)

    /**
     * The creator's phone ([C]) with [members] (address to status) at key epoch [epoch]: every ACTIVE member was
     * activated by an authenticated step an hour ago; an INVITED one was sent its invite.
     */
    fun adminPhone(
        members: List<Pair<String, MemberStatus>>,
        epoch: Int = 1,
        rebuilt: Boolean = false,
    ): AnnouncerPhone {
        val phone = AnnouncerPhone(C)
        if (!rebuilt) phone.base.selfCreated += GID
        val t = now() - 3_600_000L
        phone.base.putGroup(
            GroupInfo(
                groupId = GID,
                name = "Announce group",
                creatorAddress = C,
                createdAt = Instant.ofEpochMilli(t),
                isActive = true,
                announcerRebuilt = rebuilt,
            ),
            listOf(GroupMember(address = C, joinedAt = Instant.ofEpochMilli(t), status = MemberStatus.ACTIVE, isAdmin = true)) +
                members.map { (address, status) ->
                    GroupMember(address = address, joinedAt = Instant.ofEpochMilli(t), status = status, inviteStatus = InviteStatus.SENT)
                },
            epoch,
            groupKey,
        )
        val activated =
            members
                .filter { it.second == MemberStatus.ACTIVE }
                .associate { it.first to MemberAnnounceEntry(activatedAtMs = t, activatedBy = ActivatedBy.AUTH) }
        phone.base.states[GID] = phone.base.state(GID).copy(members = activated)
        return phone
    }

    /**
     * A member's phone ([self]) in the group, created by [C]: C and itself ACTIVE, the creator's key captured at join
     * (the key [admin] signs with for this phone). [linkPhones] with the admin first.
     */
    fun memberPhone(
        self: String,
        admin: AnnouncerPhone,
        epoch: Int = 1,
    ): AnnouncerPhone {
        val phone = AnnouncerPhone(self)
        linkPhones(admin, phone)
        val t = now() - 3_600_000L
        phone.base.putGroup(
            GroupInfo(
                groupId = GID,
                name = "Announce group",
                creatorAddress = C,
                createdAt = Instant.ofEpochMilli(t),
                isActive = true,
                creatorPublicKey = keyFor(admin, self).publicKey,
            ),
            listOf(
                GroupMember(address = C, joinedAt = Instant.ofEpochMilli(t), status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(address = self, joinedAt = Instant.ofEpochMilli(t), status = MemberStatus.ACTIVE),
            ),
            epoch,
            groupKey,
        )
        phone.base.nostrKeys[C] = NPUB.getValue(C)
        return phone
    }

    /** Record, on the admin's ledger, that every pair among [members] was already told A on-chain. */
    fun alreadyAnnounced(
        admin: AnnouncerPhone,
        members: List<String>,
        state: AnnounceLedgerState = AnnounceLedgerState.CHAIN_SENT,
    ) {
        val t = now() - 1_800_000L
        var n = t
        val entries =
            admin.base
                .state(GID)
                .members
                .toMutableMap()
        for (m in members) {
            n += 1
            entries[m] = (entries[m] ?: MemberAnnounceEntry()).copy(n = n, op = AnnounceOp.A, epoch = 1, annForm = m)
        }
        admin.base.states[GID] = admin.base.state(GID).copy(members = entries)
        admin.counters[GID] = n
        val ledger = mutableListOf<AnnounceLedgerEntry>()
        for (r in members) {
            for (s in members) {
                if (r == s) continue
                ledger +=
                    AnnounceLedgerEntry(
                        rKey = admin.base.peerKeys[r]?.let { announceKeyHash(it) },
                        rAddr = r,
                        sKey = admin.base.peerKeys[s]?.let { announceKeyHash(it) },
                        sAddr = s,
                        op = AnnounceOp.A,
                        state = state,
                        txids = listOf("synthetic-earlier-$r-$s"),
                        at = t,
                    )
            }
        }
        admin.ledgers[GID] = ledger
    }

    /** A GP [admin] signed for [recipient] (its per-peer key), naming [subject]. */
    fun gpFrom(
        admin: AnnouncerPhone,
        recipient: String,
        op: AnnounceOp,
        subject: String,
        n: Long,
        epoch: Int = 1,
    ): String {
        val sig =
            E2EEncryption.sign(
                keyFor(admin, recipient).privateKey,
                ZMSGGroupProtocol.groupMemberAnnounceSignedData(GID, op, subject, n, epoch, null),
            )
        return ZMSGGroupProtocol.createGroupMemberAnnounceMessage(GID, op, subject, n, epoch, sig)!!
    }

    /** [member]'s signed GROUP_ACCEPT for the group, to [admin]. */
    fun signedAccept(
        member: AnnouncerPhone,
        admin: String = C
    ): String {
        val pub = keyFor(member, admin).publicKey
        val sig = E2EEncryption.sign(keyFor(member, admin).privateKey, ZMSGGroupProtocol.groupAcceptSignedData(GID, member.self, pub))
        return ZMSGGroupProtocol.createGroupAcceptMessage(GID, member.self, pub, sig)
    }

    fun parseGp(memo: String): GroupMemberAnnounce? {
        if (ZMSGGroupProtocol.parseMessageType(memo) != GroupMessageType.GROUP_MEMBER) return null
        val payload = ZMSGGroupProtocol.parsePayload(memo) ?: return null
        return ZMSGGroupProtocol.parseGroupMemberAnnouncePayload(GID, payload)
    }

    private val events = AtomicInteger()

    /** Hand [memo] to [vm] as the relays do: sealed by [sender], at [timeMs]. */
    fun relay(
        vm: ChatViewModel,
        memo: String,
        sender: String,
        timeMs: Long = now(),
    ) = AnnounceDriver.receive(
        vm,
        memo,
        Instant.ofEpochMilli(timeMs),
        sender = sender,
        eventId = "synthetic-ma3-relay-${events.incrementAndGet()}"
    )

    /** Hand [memo] to [vm] as the chain scan does, mined at [timeMs] in transaction [tx]. */
    fun chain(
        vm: ChatViewModel,
        memo: String,
        tx: Int,
        timeMs: Long = now(),
        ownSend: Boolean = false,
        ownRecipient: String? = null,
    ) = AnnounceDriver.receive(
        vm,
        memo,
        Instant.ofEpochMilli(timeMs),
        AnnounceDriver.txId(tx),
        ownSend = ownSend,
        ownRecipient = ownRecipient
    )

    /** A real ChatViewModel over [phone] (nothing may be proposed through [seam]). */
    suspend fun chatVm(
        phone: AnnouncerPhone,
        seam: HandshakeSeam = HandshakeSeam(phone.self),
    ): ChatViewModel =
        HandshakeHarness.chatViewModelWith(phone.proxy, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(phone.self))
        }

    /** The group screen's GroupViewModel over [phone], paying through [seam]. */
    suspend fun groupVm(
        phone: AnnouncerPhone,
        f: GroupPaidOnceFixture,
        seam: AnnounceSeam,
    ): GroupViewModel {
        val vm = f.groupViewModel()

        fun field(name: String) = GroupViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("zchatPreferences").set(vm, phone.proxy)
        @Suppress("UNCHECKED_CAST")
        (field("_currentUserAddress").get(vm) as MutableStateFlow<String?>).value = phone.self
        val accounts = field("accountDataSource").get(vm) as AccountDataSource
        val owner = SendHarnessScopes.current()
        field("createChunkedMessageProposal").set(
            vm,
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(owner::own),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            ),
        )
        vm.consumeGroupSendEvent()
        return vm
    }

    /** The group screen's conversation after a load (T4 runs in it on the announcer), or null. */
    suspend fun load(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
    ): GroupConversation? {
        f.awaitNewJobs(vm) { vm.loadGroupDetail(GID) }
        return (vm.groupDetailState.value as? GroupDetailState.Success)?.conversation
    }

    /** A field of the announcer's detail state (GroupConversation.introduce, MA3), read by name; null when absent. */
    fun introduce(
        conversation: GroupConversation?,
        name: String
    ): Any? {
        val info =
            conversation?.let { c ->
                runCatching {
                    GroupConversation::class.java
                        .getDeclaredField("introduce")
                        .apply { isAccessible = true }
                        .get(c)
                }.getOrNull()
            } ?: return null
        return runCatching {
            info.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(info)
        }.getOrNull()
    }

    /** Call a GroupViewModel function added by MA3 by name ([groupId] only); false when it does not exist. */
    suspend fun callGroupVm(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
        name: String,
    ): Boolean {
        val method = GroupViewModel::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 1 } ?: return false
        f.awaitNewJobs(vm) {
            try {
                method.invoke(vm, GID)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        return true
    }

    /** Accept the quote on screen, waiting for everything it runs. */
    suspend fun confirm(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture
    ) = f.awaitNewJobs(vm) { vm.confirmPaidAction() }

    /**
     * The announcer's process-wide pass state (MA3's GroupAnnounceRuntime, by name): reset between tests, and the
     * 1-20 s spacing between relay sends set to none so a test does not wait (the spacing has its own test).
     */
    fun resetRuntime() {
        val runtime =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupAnnounceRuntime") }.getOrNull() ?: return
        val instance = runtime.getDeclaredField("INSTANCE").get(null)
        runtime.methods.firstOrNull { it.name == "resetForTests" }?.invoke(instance)
    }
}
