package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2g (address report sections 4 and 5): your own chat address is saved and never forgotten.
 *
 * Every chat message carries your chat address (the "My Address" identity from
 * [GetDefaultUnifiedAddressUseCase]). The wallet computes it fresh on each read: it is "the most
 * recently exposed full address", which a rescan can move in a rare case. If it moves, peers see a
 * stranger and your threads split. Required:
 *  - the first address read for a wallet account is saved, and every later read (also after a
 *    restart) returns the saved one, even when the wallet reports a different one;
 *  - two reads racing on a fresh install agree on one address;
 *  - each wallet account keeps its own address (control);
 *  - the saved address is always recognised as you. The recognition list is capped, and ZChat adds
 *    a new rotating Receive address to it on every start; eviction used to pick an effectively random
 *    entry (SharedPreferences hands back an unordered set), so after about 16 starts your own chat
 *    address could be dropped and your own messages, groups and kicks were treated as someone else's;
 *  - every address a 2.12.4 install already knew as you stays recognised after the upgrade;
 *  - among the rotating addresses the newest are kept and the oldest dropped first.
 *
 * The store is a plain in-memory SharedPreferences that, like Android's, keeps string sets as a
 * HashSet (no order).
 */
class OwnChatAddressIsSavedTest {
    private val identity = "u1synthetic-chat-identity-first"
    private val moved = "u1synthetic-chat-identity-moved"

    private fun useCase(
        prefs: SharedPreferences,
        wallet: SequenceSynchronizer,
        accountUuid: UUID = AccountFixture.ACCOUNT_UUID,
    ): GetDefaultUnifiedAddressUseCase {
        val account = runBlocking { syntheticZashiAccount() }.copy(sdkAccount = AccountFixture.new(accountUuid = accountUuid))
        return GetDefaultUnifiedAddressUseCase(
            synchronizerProvider = wallet,
            getSelectedWalletAccount = GetSelectedWalletAccountUseCase(Zc101FakeAccountDataSource(account)),
            identityStore = { SelfAddressRegistry(prefs) },
        )
    }

    @Test
    fun `your chat address stays the same when the wallet later reports a different one`() =
        runBlocking {
            val prefs = MemoryPrefs()
            val read = useCase(prefs, SequenceSynchronizer(identity, moved))

            assertEquals(identity, read())
            assertEquals(identity, read(), "the chat address changed when the wallet reported a different one")
        }

    @Test
    fun `the saved chat address survives a restart`() =
        runBlocking {
            val prefs = MemoryPrefs()
            assertEquals(identity, useCase(prefs, SequenceSynchronizer(identity))())

            // Next launch: new objects over the same file; the wallet now reports another address.
            val afterRestart = useCase(prefs, SequenceSynchronizer(moved))()

            assertEquals(identity, afterRestart, "the chat address changed across a restart")
        }

    @Test
    fun `two reads racing on a fresh install agree on one address`() =
        runBlocking {
            val prefs = MemoryPrefs()
            val bothAsked = CompletableDeferred<Unit>()
            val wallet = SequenceSynchronizer(identity, moved, gate = bothAsked, gateAfter = 2)
            val read = useCase(prefs, wallet)

            val results = listOf(async { read() }, async { read() }).awaitAll()

            assertEquals(1, results.toSet().size, "two screens got two different chat addresses: $results")
            assertEquals(results.first(), read())
        }

    @Test
    fun `each wallet account keeps its own chat address`() =
        runBlocking {
            val prefs = MemoryPrefs()
            val second = UUID.fromString("00000000-0000-4000-8000-000000000002")

            assertEquals(identity, useCase(prefs, SequenceSynchronizer(identity))())
            assertEquals(moved, useCase(prefs, SequenceSynchronizer(moved), accountUuid = second)())
            assertEquals(identity, useCase(prefs, SequenceSynchronizer(moved))())
        }

    @Test
    fun `your saved chat address is always recognised as you however many receive addresses follow`() =
        runBlocking {
            val prefs = MemoryPrefs()
            val saved = useCase(prefs, SequenceSynchronizer(identity))()
            val registry = SelfAddressRegistry(prefs)

            // What ChatViewModel does on every start: register the identity, then this start's
            // rotating Receive address.
            repeat(ROTATING_STARTS) { start ->
                registry.register(saved)
                registry.register(rotating(start))
            }

            assertTrue(SelfAddressRegistry(prefs).isSelf(saved), "your own chat address was forgotten")
        }

    @Test
    fun `after the upgrade every address the old version knew as you is still recognised`() {
        val legacy = (0 until LEGACY_CAP).map { "u1synthetic-legacy-own-$it" }
        val prefs = MemoryPrefs()
        prefs
            .edit()
            .putStringSet(LEGACY_KEY, legacy.map { ZMSGProtocol.generateAddressHash(it) }.toSet())
            .commit()
        val registry = SelfAddressRegistry(prefs)

        repeat(ROTATING_STARTS) { registry.register(rotating(it)) }

        val forgotten = legacy.filterNot { registry.isSelf(it) }
        assertTrue(forgotten.isEmpty(), "${forgotten.size} of $LEGACY_CAP own addresses known before the upgrade were forgotten")
    }

    @Test
    fun `the newest receive addresses are kept and the oldest dropped first`() {
        val registry = SelfAddressRegistry(MemoryPrefs())
        val starts = 40

        repeat(starts) { registry.register(rotating(it)) }

        val newest = (starts - LEGACY_CAP until starts).map(::rotating)
        val droppedNewest = newest.filterNot { registry.isSelf(it) }
        assertTrue(droppedNewest.isEmpty(), "recent receive addresses were dropped: ${droppedNewest.size}")
        val oldestKept = (0 until starts - LEGACY_CAP).map(::rotating).filter { registry.isSelf(it) }
        assertTrue(oldestKept.isEmpty(), "the list grew past its cap: ${oldestKept.size} old entries kept")
    }

    @Test
    fun `a stranger and a blank address are never you`() {
        val prefs = MemoryPrefs()
        runBlocking { useCase(prefs, SequenceSynchronizer(identity))() }
        val registry = SelfAddressRegistry(prefs)
        registry.register(rotating(1))

        assertFalse(registry.isSelf("u1synthetic-someone-else"))
        assertFalse(registry.isSelf(""))
        assertTrue(registry.isSelf(rotating(1)))
    }

    private fun rotating(start: Int) = "u1synthetic-rotating-receive-$start"

    private companion object {
        const val LEGACY_KEY = "self_addr_hashes"
        const val LEGACY_CAP = 16
        const val ROTATING_STARTS = 100
    }

    /** The wallet: answers getUnifiedAddress with the next of [answers] (the last one repeats). */
    private class SequenceSynchronizer(
        vararg answers: String,
        private val gate: CompletableDeferred<Unit>? = null,
        private val gateAfter: Int = 0,
    ) : SynchronizerProvider {
        private val answers = answers.toList()
        private var calls = 0

        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer {
            gate?.let {
                if (++calls >= gateAfter) it.complete(Unit)
                it.await()
            }
            return proxy
        }

        override fun resetSynchronizer() = Unit

        private var answered = 0
        private val proxy =
            Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, m, a ->
                when (m.name) {
                    "getUnifiedAddress" -> answers[minOf(answered++, answers.lastIndex)]
                    "toString" -> "SequenceSynchronizer"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a?.get(0)
                    else -> error("unexpected Synchronizer call ${m.name}")
                }
            } as Synchronizer
    }

    /** In-memory SharedPreferences; string sets are stored as HashSet, as Android does. */
    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { HashMap(values) }

        override fun getString(
            key: String,
            defValue: String?
        ): String? = synchronized(values) { values[key] as String? ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = synchronized(values) { values[key] as MutableSet<String>? ?: defValues }

        override fun getInt(
            key: String,
            defValue: Int
        ): Int = synchronized(values) { values[key] as Int? ?: defValue }

        override fun getLong(
            key: String,
            defValue: Long
        ): Long = synchronized(values) { values[key] as Long? ?: defValue }

        override fun getFloat(
            key: String,
            defValue: Float
        ): Float = synchronized(values) { values[key] as Float? ?: defValue }

        override fun getBoolean(
            key: String,
            defValue: Boolean
        ): Boolean = synchronized(values) { values[key] as Boolean? ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?
            ) = apply { changes[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ) = apply { changes[key] = values?.let { HashSet(it) } }

            override fun putInt(
                key: String,
                value: Int
            ) = apply { changes[key] = value }

            override fun putLong(
                key: String,
                value: Long
            ) = apply { changes[key] = value }

            override fun putFloat(
                key: String,
                value: Float
            ) = apply { changes[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean
            ) = apply { changes[key] = value }

            override fun remove(key: String) = apply { removals.add(key) }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach { values.remove(it) }
                    changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
