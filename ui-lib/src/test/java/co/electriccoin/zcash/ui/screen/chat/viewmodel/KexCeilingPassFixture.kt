package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-074 test support (high-items SPECS §3.5 K2, K3): the real history pass (convertToConversations, the 6-argument
 * form) run twice over the same transaction list with the history scan complete, so the second pass is stable; the
 * processed-KEX store's calls go to the real ZchatPreferencesImpl store ([realPreferences]), as in
 * KexFloorWaitsForAPutOffOfferTest; every other call gets [ScanPrefs]'s empty answers. [ceiling] reads the KEX ceiling the
 * pass left in the "zchat_kex_seen" file. Every key, txid and address is synthetic.
 */
internal class KexCeilingPassFixture {
    companion object {
        val PEER: String = SyntheticUnifiedAddress.mainnet(7402)
        val ME: String = SyntheticUnifiedAddress.mainnet(7403)
        const val SCANNED = 3_000_400L
        val KEX_STORE_CALLS = setOf("hasProcessedKexTx", "markKexTxProcessed", "limitKexFloor")

        fun txId(n: Int): String = TransactionId.new("%064x".format(n)).txIdString()

        /** An inbound KEXACK memo the pass marks processed up front (its payload does not verify). */
        fun kexAckMemo(n: Int): String = ZMSGConstants.Prefixes.V4 + "KC%06d".format(n) + "|KEXACK|synthetichash|synthetic-payload-$n"

        /** One of our own encrypted messages with no conversation id and no parseable envelope. */
        const val UNPARSEABLE_OWN_ENVELOPE = "E2E1:synthetic-not-an-envelope"
    }

    private val store: ZchatPreferences
    private val files: Map<String, MemorySharedPreferences>

    init {
        val (prefs, f) = realPreferences()
        store = prefs
        files = f
    }

    /** Mined KEX/KEXACK txids the store holds, as hasProcessedKexTx answers at [minedHeight]. */
    fun processed(
        txId: String,
        minedHeight: Long?,
    ): Boolean = store.hasProcessedKexTx(txId, minedHeight)

    fun ceiling(): Long = files.getValue("kexSeenPrefs").getLong("floor_ceiling", -1L)

    fun incoming(
        n: Int,
        minedHeight: Long?,
    ): Transaction {
        val id = TransactionId.new("%064x".format(n))
        val at = Instant.now().minusSeconds(600L)
        val overview = ScanPassHarness.overview(id, minedHeight, at, sent = false, amount = 1_000)
        return if (minedHeight != null) {
            ReceiveTransaction.Success(id, Zatoshi(1_000), at, 1, emptyList(), overview, null)
        } else {
            ReceiveTransaction.Pending(id, Zatoshi(1_000), at, 1, emptyList(), overview, null)
        }
    }

    fun outgoing(
        n: Int,
        minedHeight: Long,
    ): Transaction {
        val id = TransactionId.new("%064x".format(n))
        val at = Instant.now().minusSeconds(600L)
        val overview = ScanPassHarness.overview(id, minedHeight, at, sent = true, amount = 1_000)
        return SendTransaction.Success(id, Zatoshi(1_000), at, 1, Zatoshi(10_000), emptyList(), overview, null)
    }

    /**
     * Two passes over [transactions] with the history scan complete. [memos] gives each txid's memo (none: no memo);
     * [unreadable] are the txids whose getMemos throws. [scheduler] is the test Main dispatcher's: the pass schedules a
     * delayed re-check while our own history is unread, which runs (and only bumps a counter) before the next pass.
     */
    suspend fun twoCompletePasses(
        transactions: List<Transaction>,
        memos: Map<String, String>,
        scheduler: TestCoroutineScheduler,
        unreadable: Set<String> = emptySet(),
    ) {
        val vm = scanPassViewModel()
        repeat(2) {
            pass(vm, transactions, repository(memos, unreadable))
            scheduler.advanceUntilIdle()
            awaitIdle(vm)
        }
    }

    private var passVm: ChatViewModel? = null

    /**
     * ZC-1068: one history pass over [transactions] (the wallet's stored list, nothing fetched) on this fixture's one view
     * model, with the history scan [scanComplete]; [memos] and [unreadable] as in [twoCompletePasses].
     */
    suspend fun onePass(
        transactions: List<Transaction>,
        memos: Map<String, String>,
        scheduler: TestCoroutineScheduler,
        unreadable: Set<String> = emptySet(),
        scanComplete: Boolean = true,
    ) {
        val vm = passVm ?: scanPassViewModel().also { passVm = it }
        pass(vm, transactions, repository(memos, unreadable), scanComplete)
        scheduler.advanceUntilIdle()
        awaitIdle(vm)
    }

    private suspend fun pass(
        vm: ChatViewModel,
        transactions: List<Transaction>,
        repository: TransactionRepository,
        scanComplete: Boolean = true,
    ) {
        PaymentHarness.setField(vm, "zchatPreferences", kexStoreOver(store))
        PaymentHarness.setField(vm, "contactBook", ScanPassHarness.noContacts())
        PaymentHarness.setField(vm, "transactionRepository", repository)
        PaymentHarness.setField(vm, "synchronizerProvider", ScanPassHarness.synchronizerProvider(synchronizer()))
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        kotlin.coroutines.intrinsics
            .suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    method.invoke(
                        vm,
                        transactions,
                        ME,
                        emptySet<String>(),
                        PaymentHarness.pendingMessages(vm),
                        emptySet<String>(),
                        scanComplete,
                        continuation,
                    )
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
    }

    private fun kexStoreOver(store: ZchatPreferences): ZchatPreferences {
        val rest =
            ScanPrefs(
                mapOf(
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    // ZC-599: the answers are filed under PEER by their id and mined at or below the upgrade height,
                    // so the pass reads them as before (leg 3) and marks them processed up front.
                    "getPeerByConversationId" to { a -> PEER.takeIf { (a[0] as String).startsWith("KC") } },
                ),
            ).proxy
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            val target = if (method.name in KEX_STORE_CALLS) store else rest
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences
    }

    private suspend fun scanPassViewModel(): ChatViewModel {
        val vm = paymentViewModel(PaymentPrefs(PEER), PaymentSeam(PEER))
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        PaymentHarness.setField(vm, "unlockedMessages", MutableStateFlow<Map<String, String>>(emptyMap()))
        return vm
    }

    private fun repository(
        memos: Map<String, String>,
        unreadable: Set<String>,
    ): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getMemos" -> {
                    val id = (args!![0] as Transaction).id.txIdString()
                    if (id in unreadable) throw IllegalStateException("synthetic memo read failure")
                    memos[id]?.let { listOf(it) } ?: emptyList<String>()
                }
                "getAllRecipientAddresses" -> emptyList<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "KexCeilingRepository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    private fun synchronizer(): Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(SCANNED))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "KexCeilingSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer
}
