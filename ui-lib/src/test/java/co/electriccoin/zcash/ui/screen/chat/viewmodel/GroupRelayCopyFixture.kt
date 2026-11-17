package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopyMarksApi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-420-G (fix-all 2.13.0, train T6b; ZC-420-SPEC §4.3) — the group side of the relay-copy marks: the MA3 phones
 * ([MA3], [AnnouncerPhone]) with relays that answer two counts per recipient.
 *
 * [CountedRelayNet] is MA3's [RelayNet] with the one added knob of the spec, the readable count: per recipient relay key
 * it answers (shared, readable), where shared = relays every ZChat version reads (ZChat's relay, nos.lol) and readable =
 * any relay that took the copy. "Only a public relay took it" is (0, 1). It also installs the kept-wrap resender
 * (NostrChatBridge.registerKeptResender): [CountedRelayNet.kept] answers what the outbox of the same process would
 * answer for a kept wrap, and null models a restart (the outbox is empty).
 *
 * [GroupCopyWorld] is the creator's phone C ([MA3.adminPhone]) with members, each linked to C (a completed key exchange),
 * nicknames on the roster rows (so a banner names "Bea", not an address), the relay keys of the given members, an
 * in-memory mark store registered by name ([RelayCopyMarksApi]) and the wallet seam that records every on-chain proposal.
 * [GroupCopyWorld.prefs] counts the group's message numbers (MA2's store answers 1 for every message).
 *
 * All addresses, keys, ids and memos are synthetic.
 */
internal class CountedRelayNet {
    /** Every relay copy published (recipient relay key to plaintext), in order. */
    val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** Every question to the kept-wrap resender (rumor id to recipient relay key). */
    val keptAsks: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    private val answers = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val events = AtomicInteger()

    /** What the outbox answers for a kept wrap; null = nothing kept (after a restart). */
    @Volatile var kept: ((rumorId: String, npub: String) -> NostrChatBridge.PublishResult?)? = null

    /** The relays of [npub] answer [shared] relays every version reads and [readable] relays that took it. */
    fun answer(
        npub: String,
        shared: Int,
        readable: Int,
    ) {
        answers[npub] = shared to readable
    }

    /** The same process: the outbox holds every wrap a readable relay took, and no shared relay holds it yet. */
    fun keptNotShared() {
        kept = { id, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = id, readableAcks = 1) }
    }

    /** The same process, and a shared relay holds the kept wrap now. */
    fun keptShared() {
        kept = { id, _ -> NostrChatBridge.PublishResult(acks = 1, messageId = id, readableAcks = 1) }
    }

    fun install() {
        NostrChatBridge.registerPublisher { plaintext, recipient ->
            published += recipient to plaintext
            val (shared, readable) = answers[recipient] ?: (1 to 1)
            NostrChatBridge.PublishResult(
                acks = shared,
                messageId = "synthetic-g420-event-${events.incrementAndGet()}",
                readableAcks = readable,
            )
        }
        NostrChatBridge.registerKeptResender { id, npub ->
            keptAsks += id to npub
            kept?.invoke(id, npub)
        }
    }

    fun uninstall() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
    }

    fun clear() {
        published.clear()
        keptAsks.clear()
    }

    fun to(npub: String): List<String> = published.filter { it.first == npub }.map { it.second }

    fun ofType(
        type: GroupMessageType,
        npub: String,
    ): List<String> = to(npub).filter { ZMSGGroupProtocol.parseMessageType(it) == type }
}

internal class GroupCopyWorld(
    members: List<Pair<String, MemberStatus>>,
    relayKeys: Set<String> = members.map { it.first }.toSet(),
    epoch: Int = 1,
) {
    val admin: AnnouncerPhone = MA3.adminPhone(members, epoch = epoch)

    /** The members' own phones (linked to C), by address. */
    val phones: Map<String, AnnouncerPhone> = members.associate { (address, _) -> address to MA3.memberPhone(address, admin, epoch) }

    val marks = RelayCopyMarksApi.MemoryMarks()
    val net = CountedRelayNet()
    val seam = AnnounceSeam()
    private val seqs = ConcurrentHashMap<String, Long>()

    /** C's store, with real message numbers (1, 2, 3, … per group). */
    val prefs: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getGroupMessageSequence" -> seqs[args!![0] as String] ?: 0L
                "incrementGroupMessageSequence" -> seqs.merge(args!![0] as String, 1L, Long::plus)
                else ->
                    try {
                        method.invoke(admin.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    init {
        relayKeys.forEach { admin.base.nostrKeys[it] = MA3.NPUB.getValue(it) }
        editRoster { rows -> rows.map { if (it.address in NICKNAMES) it.copy(nickname = NICKNAMES.getValue(it.address)) else it } }
        RelayCopyMarksApi.register(marks)
        net.install()
    }

    fun close() {
        net.uninstall()
        RelayCopyMarksApi.unregister()
    }

    /** C's roster as stored. */
    fun roster(): List<GroupMember> = admin.base.roster(MA3.GID)

    fun row(address: String): GroupMember = roster().single { it.address == address }

    fun editRoster(edit: (List<GroupMember>) -> List<GroupMember>) {
        admin.base.rosters[MA3.GID] = ZMSGGroupProtocol.serializeGroupMembers(edit(roster()))
    }

    fun setRow(
        address: String,
        edit: (GroupMember) -> GroupMember,
    ) = editRoster { rows -> rows.map { if (it.address == address) edit(it) else it } }

    /** A contact of C that is not in the group yet: linked to C, with its relay key known. */
    fun contact(address: String): AnnouncerPhone {
        val phone = AnnouncerPhone(address)
        MA3.linkPhones(admin, phone)
        admin.base.nostrKeys[address] = MA3.NPUB.getValue(address)
        return phone
    }

    fun answer(
        member: String,
        shared: Int,
        readable: Int,
    ) = net.answer(MA3.NPUB.getValue(member), shared, readable)

    /** The group screen's GroupViewModel on C (a new one models a restart of the screen or of the app). */
    suspend fun groupVm(fixture: GroupPaidOnceFixture): GroupViewModel {
        val vm = MA3.groupVm(admin, fixture, seam)
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, prefs)
        return vm
    }

    /** Opens the group screen and waits for everything it runs (the repair pass included). */
    suspend fun open(
        vm: GroupViewModel,
        fixture: GroupPaidOnceFixture,
    ): GroupDetailState {
        fixture.awaitNewJobs(vm) { vm.loadGroupDetail(MA3.GID) }
        return vm.groupDetailState.value
    }

    /** The group memos of [type] published to [member]'s relay key. */
    fun copies(
        type: GroupMessageType,
        member: String,
    ): List<String> = net.ofType(type, MA3.NPUB.getValue(member))

    companion object {
        val NICKNAMES =
            mapOf(
                MA3.A to "Ana",
                MA3.B to "Bea",
                MA3.D to "Dan",
                MA3.X to "Xia",
            )

        /** A member row of the group (ACTIVE unless said), for a test that adds members after the world is built. */
        fun memberRow(
            address: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): GroupMember =
            GroupMember(
                address = address,
                joinedAt = java.time.Instant.ofEpochMilli(MA3.now() - 3_600_000L),
                status = status,
                inviteStatus = InviteStatus.SENT,
            )
    }
}

/** The new group result of ZC-420-G, read by name so this file compiles before it exists. */
internal object RelayUnconfirmedApi {
    private const val NAME = "co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupSendResult\$RelayUnconfirmed"

    fun exists(): Boolean = runCatching { Class.forName(NAME) }.isSuccess

    /** (names, atOpen) when [event] is a RelayUnconfirmed result, else null. */
    fun read(event: GroupSendResult?): Pair<List<String>, Boolean>? {
        if (event == null || event.javaClass.name != NAME) return null
        @Suppress("UNCHECKED_CAST")
        val names = event.javaClass.getMethod("getNames").invoke(event) as List<String>
        val atOpen = event.javaClass.getMethod("getAtOpen").invoke(event) as Boolean
        return names to atOpen
    }

    /** A RelayUnconfirmed result built by name. */
    fun make(
        groupId: String,
        names: List<String>,
        atOpen: Boolean,
    ): GroupSendResult {
        val cls =
            runCatching { Class.forName(NAME) }.getOrNull()
                ?: throw AssertionError("GroupSendResult.RelayUnconfirmed does not exist (ZC-420-G)")
        val constructor =
            cls.constructors.firstOrNull { it.parameterCount == 3 }
                ?: throw AssertionError("RelayUnconfirmed has no (groupId, names, atOpen) constructor (ZC-420-G)")
        return constructor.newInstance(groupId, names, atOpen) as GroupSendResult
    }
}
