package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionOverview
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.MemoReadFixture.MemoSdk
import co.electriccoin.zcash.ui.common.repository.MemoReadFixture.overview
import co.electriccoin.zcash.ui.common.repository.MemoReadFixture.send
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ZC-946-3 (review 4 of train T7a1, 14 Oct, nit 3): the memo cache under `TransactionRepositoryImpl.getMemos` is
 * emptied only when the next read meets another synchronizer instance. After a Reset that the user does not follow with
 * a new wallet, up to 2,000 decrypted memos of the old wallet stay in memory until the process ends.
 *
 * Wanted: the cache is emptied when the synchronizer goes away (the repository's synchronizer flow becomes null), and
 * Reset empties it itself when it clears the app's in-memory data. Measured by the SDK's memo reads: after either, the
 * next read of the same transaction, even from the same synchronizer instance, asks the SDK again.
 *
 * The real TransactionRepositoryImpl over a counting stand-in synchronizer (MemoReadFixture), and the real
 * ResetZashiUseCase's in-memory clear on an instance with only the fields that step uses. The repository field of the
 * use case is found by type, so this file compiles before it exists. Every id is synthetic.
 */
class MemoCacheForgetsTheOldWalletTest {
    private val sdk = MemoSdk()

    /** The synchronizer the repository's history flow lists from: no transactions. */
    private val listing: Synchronizer =
        Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
            when (method.name) {
                "getTransactions" -> flowOf(emptyList<TransactionOverview>())
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ListingSdk"
                else -> throw UnsupportedOperationException("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    private val current = MutableStateFlow<Synchronizer?>(listing)

    private val provider =
        object : SynchronizerProvider {
            override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
            override val synchronizer: StateFlow<Synchronizer?> = current

            override suspend fun getSynchronizer(): Synchronizer = sdk.synchronizer

            override fun resetSynchronizer() = Unit
        }

    private val tx = send(overview(11, memoCount = 1))

    private fun warmRepository(): TransactionRepositoryImpl =
        runBlocking {
            val repo = TransactionRepositoryImpl(Zc101FakeAccountDataSource(syntheticZashiAccount()), provider)
            sdk.raw[tx.overview.txId.txIdString()] = listOf("ZMSG|synthetic-eleven")
            repo.getMemos(tx)
            repo.getMemos(tx)
            assertEquals("fixture: the second read was answered from memory", 1, sdk.reads.get())
            repo
        }

    @Test
    fun `when the synchronizer goes away the old wallet's memos are no longer held`() =
        runBlocking {
            val repo = warmRepository()
            val seen = CopyOnWriteArrayList<String>()
            val watching = launch(Dispatchers.Default) { repo.transactions.collect { seen += if (it == null) "none" else "list" } }
            try {
                assertTrue("fixture: the history never listed", waitFor { "list" in seen })

                current.value = null
                assertTrue("fixture: the history never saw the synchronizer go", waitFor { seen.lastOrNull() == "none" })

                repo.getMemos(tx)
                assertEquals("the old wallet's memos were still held after the synchronizer went away", 2, sdk.reads.get())
            } finally {
                watching.cancel()
            }
        }

    @Test
    fun `a Reset empties the memo cache`() =
        runBlocking {
            val repo = warmRepository()
            val reset = bare(ResetZashiUseCase::class.java)
            val field =
                ResetZashiUseCase::class.java.declaredFields.firstOrNull { TransactionRepository::class.java.isAssignableFrom(it.type) }
            assertNotNull("Reset never reaches the memo cache: ResetZashiUseCase holds no TransactionRepository", field)
            field!!.apply { isAccessible = true }.set(reset, repo)
            ResetZashiUseCase::class.java
                .getDeclaredField("homeMessageCacheRepository")
                .apply { isAccessible = true }
                .set(reset, noHomeMessages())

            try {
                ResetZashiUseCase::class.java
                    .getDeclaredMethod("clearInMemoryData")
                    .apply { isAccessible = true }
                    .invoke(reset)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

            repo.getMemos(tx)
            assertEquals("the old wallet's memos were still held after Reset cleared the in-memory data", 2, sdk.reads.get())
        }

    private suspend fun waitFor(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(WAIT_MS) { while (!condition()) delay(POLL_MS) } != null

    private fun noHomeMessages(): HomeMessageCacheRepository =
        Proxy.newProxyInstance(
            HomeMessageCacheRepository::class.java.classLoader,
            arrayOf(HomeMessageCacheRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "reset" -> Unit
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "NoHomeMessages"
                else -> throw UnsupportedOperationException("unexpected HomeMessageCacheRepository.${method.name}")
            }
        } as HomeMessageCacheRepository

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L

        /** An instance with no constructor run; the test sets the fields the step under test uses. */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> bare(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, type) as T
        }
    }
}
