package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.model.SubmitResult
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.fail

/**
 * F2 (fix-all 2.13.0, train T2): the one submit path, as the F2 tests drive it.
 *
 * `createStoreAndSubmit(broadcaster, endpoint, onStored, ownSpends, attempts, create)` in ProposalDataSource.kt is
 * reached BY NAME, so these test files compile before it exists (the RED commit): create every leg through the SDK's
 * public [Broadcaster], hand the stored ids and expiry heights to `onStored` before anything is submitted, then
 * submit EVERY leg to [ENDPOINT]. The stored records are read through their getters (`getTxId`,
 * `getExpiryHeight`) for the same reason.
 *
 * Every txid, raw transaction and endpoint here is synthetic; nothing touches a network or a wallet.
 */
internal object F2SubmitPath {
    private const val FILE_CLASS = "co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceKt"

    const val FUNCTION = "createStoreAndSubmit"

    /** The lightwalletd endpoint every leg is submitted to (never contacted: the broadcaster is a fake). */
    val ENDPOINT = LightWalletEndpoint("fake.invalid", 443, true)

    /** Neither is read by the fake broadcaster; they only fill the SDK signature. */
    private val proposal: Proposal = unusable()
    private val usk: UnifiedSpendingKey = unusable()

    suspend fun createStoreAndSubmit(
        broadcaster: Broadcaster,
        onStored: (suspend (List<Any>) -> Unit)? = null,
        ownSpends: (suspend () -> List<String>)? = null,
        create: suspend (Broadcaster) -> List<CreatedTransaction> = { it.createProposedTransactions(proposal, usk) },
    ): SubmitResult {
        val method =
            Class.forName(FILE_CLASS).declaredMethods.firstOrNull { it.name == FUNCTION }
                ?: fail(
                    "F2's one submit path $FUNCTION(broadcaster, endpoint, onStored, ownSpends, attempts, create) " +
                        "does not exist: sends still go through the SDK's create-and-submit flow, which stops at the " +
                        "first failed leg and never reports the ids and expiry before the submit",
                )
        method.isAccessible = true
        val result =
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    method.invoke(null, broadcaster, ENDPOINT, onStored, ownSpends, SendAttemptRegistry(), create, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        return result as SubmitResult
    }

    fun storedIds(stored: List<Any>): List<String> = stored.map { it.javaClass.getMethod("getTxId").invoke(it) as String }

    fun storedExpiries(stored: List<Any>): List<Long> =
        stored.map { it.javaClass.getMethod("getExpiryHeight").invoke(it) as Long }

    /** lightwalletd's answer to a leg that spends an output it has not seen: leg 2 while leg 1 is not in its mempool. */
    fun missingInputs(transaction: CreatedTransaction): TransactionSubmitResult =
        TransactionSubmitResult.Failure(transaction.txId, false, MISSING_INPUTS, "synthetic: missing inputs", false)

    /** A definitive server rejection of the transaction itself (not a transport failure). */
    fun rejected(transaction: CreatedTransaction): TransactionSubmitResult =
        TransactionSubmitResult.Failure(transaction.txId, false, REJECTED, "synthetic: rejected by the server", false)

    const val MISSING_INPUTS = -25

    const val REJECTED = -26

    inline fun <reified T> unusable(): T =
        if (T::class.java.isInterface) {
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
                throw UnsupportedOperationException("not read by the fake")
            } as T
        } else {
            val unsafe =
                Class
                    .forName("sun.misc.Unsafe")
                    .getDeclaredField("theUnsafe")
                    .apply { isAccessible = true }
                    .get(null)
            unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
        }
}
