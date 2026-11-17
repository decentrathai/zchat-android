package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * KI-23 (fix-all 2.13.0, train T6b; ZC-020, ZC-124): the fixture of the "a key request is paid at most once per member
 * and key" tests.
 *
 * One phone: the creator [MA3.C] of the group [MA3.GID] at key number 1, over F8-G's [GroupLedgerWorld] (the MA3
 * store, F8's in-memory ledger behind its recording proxy, the synthetic wallet the history pass reads). On top of
 * that store sits [KeyGrantStore]: the standing consent, the per-request record, and the two stores this batch adds
 * (the paid count per member and key, the daily budget), each of which can be made to refuse a write. The four new
 * store calls are answered BY NAME, so this file compiles and its tests run on a build that never makes them: there
 * the count stays 0 and no unit is used, and each test fails on the behaviour it asks for.
 *
 * A member's request is the real signed GROUP_KEY_REQUEST memo, handed to the real `processGroupMessage` as the chain
 * scan ([chain]) or the relays ([relay]) hand it over. Every address, key, group id and transaction id is synthetic.
 * Nothing here pays.
 */
internal class KeyGrantStore(
    private val base: ZchatPreferences,
    @Volatile var consent: Boolean = true,
) {
    /** The per-request records (the request ids that count as answered). */
    val granted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The paid deliveries per grant key (`group|member|key number`). */
    val counts = ConcurrentHashMap<String, Int>()

    /** The times of the budget units used, on [clock]. */
    val budget: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** The (max, window) every `tryConsumeGroupKeyGrantBudget` call carried, used or not. */
    val consumeCalls: MutableList<Pair<Int, Long>> = Collections.synchronizedList(mutableListOf())

    /** The budget's clock (the product's own `now` is not used: a test moves this one). */
    @Volatile var clock = 1_800_000_000_000L

    @Volatile var failRecordWrite = false

    @Volatile var failCountWrite = false

    /** The unit cannot be written although the budget has room (a write that did not reach the disk). */
    @Volatile var failBudgetConsume = false

    /** The budget says it is used up, whatever was consumed. */
    @Volatile var budgetUsedUp = false

    @Volatile var failConsentClear = false

    /** Every write of the consent, the record, the count and the budget, in order ("name:arguments"). */
    val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The highest value each count ever held. */
    val highestCount = ConcurrentHashMap<String, Int>()

    fun count(member: String, epoch: Int = 1): Int = counts[grantKey(member, epoch)] ?: 0

    fun grantKey(member: String, epoch: Int = 1): String = MA3.GID + "|" + member + "|" + epoch

    private fun recent(max: Int, window: Long): Boolean =
        synchronized(budget) { budget.count { it > clock - window } < max }

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            when (method.name) {
                "toString" -> "KeyGrantStore"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "hasConsentedToHandshakeSpend" -> consent
                "setConsentedToHandshakeSpend" -> {
                    writes += "setConsentedToHandshakeSpend"
                    consent = true
                    true
                }
                "clearConsentedToHandshakeSpend" -> {
                    writes += "clearConsentedToHandshakeSpend"
                    if (!failConsentClear) consent = false
                    !failConsentClear
                }
                "getTunnelModePeers" -> emptyList<String>()
                "isGroupKeyRequestGranted" -> arg(0) in granted
                "setGroupKeyRequestGranted" -> {
                    writes += "setGroupKeyRequestGranted:${arg(1)}"
                    if (failRecordWrite) {
                        false
                    } else {
                        if (arg(1) == true) granted += arg(0) as String else granted -= arg(0) as String
                        true
                    }
                }
                "getGroupKeyGrantPaidCount" -> counts[arg(0) as String] ?: 0
                "setGroupKeyGrantPaidCount" -> {
                    writes += "setGroupKeyGrantPaidCount:${arg(1)}"
                    if (failCountWrite) {
                        false
                    } else {
                        val value = arg(1) as Int
                        if (value > 0) counts[arg(0) as String] = value else counts.remove(arg(0) as String)
                        highestCount.merge(arg(0) as String, value) { a, b -> maxOf(a, b) }
                        true
                    }
                }
                "groupKeyGrantBudgetAvailable" -> !budgetUsedUp && recent(arg(1) as Int, arg(2) as Long)
                "tryConsumeGroupKeyGrantBudget" -> {
                    consumeCalls += (arg(1) as Int) to (arg(2) as Long)
                    if (budgetUsedUp || failBudgetConsume || !recent(arg(1) as Int, arg(2) as Long)) {
                        false
                    } else {
                        writes += "tryConsumeGroupKeyGrantBudget"
                        budget += clock
                        true
                    }
                }
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}

/** M3 (ZC-110): the confirmed Ironwood of the creator's screen wallet (more than one key answer of 17,000). */
private const val FUNDED_IRONWOOD = 500_000L

internal class KeyGrantWorld(
    scheduler: TestCoroutineScheduler,
    val members: List<String> = listOf(MA3.A, MA3.B),
    consent: Boolean = true,
) {
    val world = GroupLedgerWorld(members.map { it to MemberStatus.ACTIVE }, scheduler = scheduler)
    val store = KeyGrantStore(world.store.proxy, consent)
    val net = RelayNet()
    val seam: GroupLedgerSeam get() = world.seam
    val ledger: LedgerDouble get() = world.ledger

    /** The on-chain submits through the world's own seam so far (each one is a paid transaction). */
    fun submits(): Int = seam.submits.get()

    /** The ledger's entries, as text (to compare two moments). */
    fun ledgerText(): List<String> = ledger.all().map { it.toString() }

    /** Each member's own key pair for the creator (what it signs its request with). */
    private val memberKeys: Map<String, E2EKeyPair> = members.associateWith { world.admin.base.link(it) }

    /** The member's signed request, saying it holds key number [haveEpoch]. */
    fun request(
        member: String,
        haveEpoch: Int,
    ): String =
        ZMSGGroupProtocol.createGroupKeyRequestMessage(
            MA3.GID,
            member,
            haveEpoch,
            E2EEncryption.sign(
                memberKeys.getValue(member).privateKey,
                ZMSGGroupProtocol.groupKeyRequestSignedData(MA3.GID, member, haveEpoch),
            ),
        )

    /** The id the product records a request of transaction [tx] under. */
    fun chainRequestId(tx: Int): String = MA3.GID + "|tx:" + AnnounceDriver.txId(tx).txIdString()

    /** One scan pass reaching [member]'s on-chain request in transaction [tx]. */
    fun chain(
        vm: ChatViewModel,
        member: String,
        tx: Int,
        haveEpoch: Int = 0,
    ) = AnnounceDriver.receive(vm, request(member, haveEpoch), Instant.ofEpochMilli(MA3.now()), AnnounceDriver.txId(tx))

    /** [member]'s request arriving over the relays as event [event]. */
    fun relay(
        vm: ChatViewModel,
        member: String,
        event: String,
        haveEpoch: Int = 0,
    ) = AnnounceDriver.receive(vm, request(member, haveEpoch), Instant.ofEpochMilli(MA3.now()), sender = member, eventId = event)

    /** The creator knows a relay key of [member]; every relay copy is acked by [acks] relays. */
    fun relays(
        member: String,
        acks: Int,
    ) {
        world.admin.base.nostrKeys[member] = npub(member)
        net.acks = { acks }
        net.register()
    }

    fun npub(member: String): String = MA3.NPUB[member] ?: "%064x".format(member.hashCode().toLong() and 0xffffffffL)

    /** The key messages published to [member] over the relays so far. */
    fun keysPublishedTo(member: String): Int = net.ofType(GroupMessageType.GROUP_KEY, npub(member)).size

    /** The group moves to the next key number (a rotation that is done). */
    fun rotate() {
        val next = world.epoch() + 1
        world.keys[MA3.GID + "|" + next] = AnnounceDriver.newGroupKey()
        world.admin.base.epochs[MA3.GID] = next
    }

    /**
     * The chat list's ChatViewModel over this phone, paying through [seam]. [ledger] false: no send-attempt ledger is
     * installed (the ViewModel of every test written before the ledger), so this batch's own rules are tested alone.
     */
    suspend fun chatVm(
        seam: ProposalDataSource,
        ledger: Boolean,
        heights: HeightFeed? = null,
    ): ChatViewModel {
        val vm = world.chatVm()
        val owner = SendHarnessScopes.current()
        PaymentHarness.setField(
            vm,
            "createChunkedMessageProposal",
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = world.accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(owner::own),
                accountDataSource = world.accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            ),
        )
        PaymentHarness.setField(vm, "zchatPreferences", store.proxy)
        if (heights != null) PaymentHarness.setField(vm, "synchronizerProvider", heights.provider)
        if (!ledger) {
            ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "sendAttempts" }?.let {
                it.isAccessible = true
                it.set(vm, null)
            }
        }
        // M3 (ZC-110): the screen reads a wallet that can pay one key answer, so "Allow handshake transactions?" is asked
        // where these tests expect it (a wallet that cannot pay one is no longer asked). Only confirmed Ironwood differs
        // from the world's account, which the send path keeps reading.
        PaymentHarness.setField(vm, "accountDataSource", fundedAccount())
        return vm
    }

    private suspend fun fundedAccount(): AccountDataSource {
        val base = syntheticZashiAccount()
        return Zc101FakeAccountDataSource(
            base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(FUNDED_IRONWOOD), Zatoshi(0), Zatoshi(0)))),
        )
    }

    /** A group screen of the creator over this phone's store (with this fixture's overlay), wallet and ledger. */
    suspend fun screen(): GroupScreen =
        world.screen().also { s ->
            val field = GroupViewModel::class.java.getDeclaredField("zchatPreferences")
            field.isAccessible = true
            field.set(s.vm, store.proxy)
        }

    fun asksForConsent(vm: ChatViewModel): Boolean =
        HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent

    /** The ledger's entry of the key grant to [member] at key number [epoch], or null. */
    fun grantEntry(
        member: String,
        epoch: Int = 1,
    ): EntryView? = world.ledger.of("KEY_GRANT").firstOrNull { it.subject == world.memberSubject(member) && it.identity == "epoch:$epoch" }
}
