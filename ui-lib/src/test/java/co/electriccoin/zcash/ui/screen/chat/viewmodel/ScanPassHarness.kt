package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * R2-5 test support: runs the REAL chat-history conversion (convertToConversations) once, over transactions and a
 * transaction repository the test builds. Unlike [PendingReconcileHarness] the repository is supplied by the test
 * (for R2-5 (b) it is the real TransactionRepositoryImpl over an SDK-3.0.0-shaped synchronizer), and the
 * preferences answer through [prefs] with [overrides] first. All addresses are synthetic unless a test says
 * otherwise.
 */
internal object ScanPassHarness {
    const val SCANNED = 3_000_200L

    fun overview(
        id: TransactionId,
        minedHeight: Long?,
        blockTime: Instant?,
        sent: Boolean,
        amount: Long = 50_000L,
    ): TransactionOverview =
        TransactionOverview(
            id,
            minedHeight?.let { BlockHeight.new(it) },
            null,
            null,
            null,
            sent,
            Zatoshi(amount + 10_000),
            Zatoshi(amount + 10_000),
            Zatoshi(0),
            Zatoshi(10_000),
            false,
            0,
            1,
            1,
            blockTime?.epochSecond,
            if (minedHeight != null) TransactionState.Confirmed else TransactionState.Pending,
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )

    /** The synchronizer the ViewModel's own lookups see (chain heights only). */
    fun synchronizerProvider(synchronizer: Synchronizer): SynchronizerProvider =
        Proxy.newProxyInstance(
            SynchronizerProvider::class.java.classLoader,
            arrayOf(SynchronizerProvider::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getSynchronizer" -> if (args.isNullOrEmpty()) MutableStateFlow<Synchronizer?>(synchronizer) else synchronizer
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ScanPassSynchronizerProvider"
                else -> error("unexpected SynchronizerProvider.${method.name}")
            }
        } as SynchronizerProvider

    /** [prefs] with [overrides] answered first (by method name); every other call goes to [prefs]. */
    fun prefsWith(
        prefs: ZchatPreferences,
        overrides: Map<String, (Array<Any?>) -> Any?>,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            val a = args ?: emptyArray()
            val override = overrides[method.name]
            if (override != null) {
                override(a)
            } else {
                try {
                    method.invoke(prefs, *a)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    /** Scan-time preferences answers every scan needs (as PendingReconcileHarness has them). */
    val scanDefaults: Map<String, (Array<Any?>) -> Any?> =
        mapOf(
            "getConversationId" to { _ -> null },
            "getDisappearingTtl" to { _ -> null },
            "getNickname" to { _ -> null },
            "clearPendingTxFirstSeen" to { _ -> null },
            "isE2ERecoveryFloorPending" to { _ -> false },
            "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
        )

    fun noContacts(): ContactBook =
        Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { _, method, _ ->
            when (method.name) {
                "getContact" -> null
                "hasContact" -> false
                else -> error("unexpected ContactBook.${method.name}")
            }
        } as ContactBook

    /** One scan pass over [transactions]: the conversations the chat list would show. */
    suspend fun scan(
        vm: ChatViewModel,
        prefs: ZchatPreferences,
        contactBook: ContactBook,
        repository: TransactionRepository,
        synchronizer: Synchronizer,
        transactions: List<Transaction>,
        userAddress: String = PaymentHarness.SELF,
        hiddenMsgIds: Set<String> = emptySet(),
    ): List<Conversation> {
        PaymentHarness.setField(vm, "zchatPreferences", prefs)
        PaymentHarness.setField(vm, "contactBook", contactBook)
        PaymentHarness.setField(vm, "transactionRepository", repository)
        PaymentHarness.setField(vm, "synchronizerProvider", synchronizerProvider(synchronizer))
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        return kotlin.coroutines.intrinsics
            .suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(
                        vm,
                        transactions,
                        userAddress,
                        hiddenMsgIds,
                        PaymentHarness.pendingMessages(vm),
                        emptySet<String>(),
                        false,
                        continuation,
                    )
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
    }
}
