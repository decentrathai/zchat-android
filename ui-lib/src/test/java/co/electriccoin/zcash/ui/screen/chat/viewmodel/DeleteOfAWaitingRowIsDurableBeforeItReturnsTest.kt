package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-1960 (Fable review of fix/2130-msg, 16 Nov 2026; register medium, money) — Delete of a row waiting for its funds
 * removes it under the claim lock with SharedPreferences.apply(), while every other writer under that lock commits. apply()
 * returns before the removal is on disk: a process kill right after Delete brings the row back on the next start, and
 * resumeFundsWaitRows then sends it and pays for it.
 *
 * Wanted: when Delete returns, the waiting row's removal is on disk. The real ZchatPreferencesImpl pending-row store runs
 * over a SharedPreferences in which only commit() reaches the disk and apply() is lost at a kill (as apply() is until its
 * background write ends); the rest of the store is the fixture's. The control shows a row written by the store survives
 * the kill. Through the ViewModel's Delete (hideMessage); every address and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteOfAWaitingRowIsDurableBeforeItReturnsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1960deletedisk001"
        const val ROW_ID = "pending_zc1960"
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `control - a waiting row the store wrote survives a process kill`() =
        runBlocking {
            val prefs = KillablePrefs()
            pendingStore(prefs).commitPendingMessage(waitingRow())
            assertNotNull("fixture: the written row did not survive the kill", stored(pendingStore(prefs.afterKill())))
        }

    @Test
    fun `a deleted waiting row stays deleted after a process kill right after Delete`() =
        runBlocking {
            val prefs = KillablePrefs()
            val rows = pendingStore(prefs)
            rows.commitPendingMessage(waitingRow())
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen().also(::canHideMessages)
            PaymentHarness.setField(vm, "zchatPreferences", pendingRowsFrom(rows, world.chat.prefs))

            vm.hideMessage(ROW_ID)

            assertNull("fixture: Delete did not remove the waiting row", stored(rows))
            assertNull(
                "the deleted waiting row came back after a process kill (its removal was not on disk when Delete returned)",
                stored(pendingStore(prefs.afterKill())),
            )
        }

    private suspend fun waitingRow() =
        ZchatPreferences.PendingMessageData(
            id = ROW_ID,
            text = "synthetic waiting first words",
            timestampMillis = System.currentTimeMillis(),
            peerAddress = PEER,
            isPending = true,
            status = MessageStatus.SENDING.name,
            sendAmountZatoshi = 1_000L,
            submitTracked = true,
            acceptedPriceZatoshi = 51_000L,
            acceptedAccountKey = GetDefaultUnifiedAddressUseCase.accountKeyOf(syntheticZashiAccount()),
        )

    private fun stored(store: ZchatPreferences) = store.getPendingMessages().firstOrNull { it.id == ROW_ID }

    /** The real ZchatPreferencesImpl, allocated without its constructor, with only its pending-row store set. */
    private fun pendingStore(prefs: SharedPreferences): ZchatPreferencesImpl {
        val unsafe =
            Class
                .forName("sun.misc.Unsafe")
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        val impl =
            unsafe.javaClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        ZchatPreferencesImpl::class.java
            .getDeclaredField("pendingMsgPrefs")
            .apply { isAccessible = true }
            .set(impl, prefs)
        return impl
    }

    /** Every pending-row call (any method named for pending messages) goes to [rows]; everything else to [rest]. */
    private fun pendingRowsFrom(
        rows: ZchatPreferences,
        rest: ZchatPreferences,
    ): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
            val target = if (method.name.contains("PendingMessage")) rows else rest
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences

    /**
     * A SharedPreferences whose reads see every edit at once, while only commit() writes the disk: an apply() not yet
     * written is lost by [afterKill], which is the store the next process start reads.
     */
    private class KillablePrefs(
        private val disk: MutableMap<String, Any?> = HashMap(),
    ) : SharedPreferences {
        private val memory = HashMap<String, Any?>(disk)

        fun afterKill(): KillablePrefs = KillablePrefs(synchronized(this) { HashMap(disk) })

        override fun getAll(): MutableMap<String, *> = synchronized(this) { HashMap(memory) }

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = synchronized(this) { memory[key] as String? ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = synchronized(this) { memory[key] as MutableSet<String>? ?: defValues }

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = synchronized(this) { memory[key] as Int? ?: defValue }

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = synchronized(this) { memory[key] as Long? ?: defValue }

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = synchronized(this) { memory[key] as Float? ?: defValue }

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = synchronized(this) { memory[key] as Boolean? ?: defValue }

        override fun contains(key: String): Boolean = synchronized(this) { memory.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private fun write(
            into: MutableMap<String, Any?>,
            clear: Boolean,
            removals: Set<String>,
            changes: Map<String, Any?>,
        ) {
            if (clear) into.clear()
            removals.forEach { into.remove(it) }
            changes.forEach { (k, v) -> if (v == null) into.remove(k) else into[k] = v }
        }

        private inner class Editor : SharedPreferences.Editor {
            private val changes = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?,
            ) = apply { changes[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { changes[key] = values?.let { HashSet(it) } }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { changes[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { changes[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { changes[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { changes[key] = value }

            override fun remove(key: String) = apply { removals.add(key) }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(this@KillablePrefs) {
                    write(memory, clear, removals, changes)
                    write(disk, clear, removals, changes)
                }
                return true
            }

            // In memory at once; on disk only later, so a kill before that write loses it.
            override fun apply() {
                synchronized(this@KillablePrefs) { write(memory, clear, removals, changes) }
            }
        }
    }
}
