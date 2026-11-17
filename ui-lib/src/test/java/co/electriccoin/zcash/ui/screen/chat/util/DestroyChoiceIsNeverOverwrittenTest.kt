package co.electriccoin.zcash.ui.screen.chat.util

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyChoice
import co.electriccoin.zcash.ui.screen.onboarding.DestroyPinSetupFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1043 (cross-family review of 19 Oct 2026: GPT T7aD-1 findings 3 and 4, GPT T7aD-2 finding 1, Grok T7aD-1 finding 3
 * and T7aD-2a finding 1) — what Destroy All keeps is never overwritten by something older than the user's last choice.
 *
 *  (a) the Destroy PIN set-up's read of the saved choice (load(), off the main thread) that answers after the user chose
 *      in step 2 leaves the user's choice as it is, both ways;
 *  (b) DestroyManager's reload of the saved choice that started before a Save and answers after it does not put the older
 *      choice back in memory (the one Destroy All acts on);
 *  (c) a choice or a PIN set-up whose write did not reach the disk does not stay in memory: SharedPreferences puts the new
 *      value in memory before the disk write and keeps it there when commit() answers false, so the store answers the
 *      value that was saved before.
 *
 * Everything is synthetic.
 */
class DestroyChoiceIsNeverOverwrittenTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(120)

    /** The Destroy choice as the store keeps it; [readGate] holds the first getDestroyKeep until it is opened. */
    private class KeepPrefs(
        @Volatile var keep: Set<String>,
    ) {
        @Volatile var readGate: CountDownLatch? = null
        val readEntered = CountDownLatch(1)
        val reads = AtomicInteger()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "hasDestroyPin" -> true
                    "getDestroyKeep" -> {
                        val answer = keep
                        if (reads.incrementAndGet() == 1) {
                            readEntered.countDown()
                            readGate?.await(30, TimeUnit.SECONDS)
                        }
                        answer
                    }
                    "walletScopeGeneration" -> 5L
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "KeepPrefs"
                    else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
                }
            } as ZchatPreferences
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any?,
    ) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            type = type.superclass
        }
        throw AssertionError("${target.javaClass.simpleName} has no field $name")
    }

    private fun heldKeep(manager: DestroyManager): Any? {
        val choice =
            DestroyManager::class.java.declaredFields
                .first { it.name == "keepChoice" }
                .apply { isAccessible = true }
                .get(manager) ?: return null
        return choice.javaClass.methods
            .first { it.name == "getKeep" }
            .invoke(choice)
    }

    // ---- (a) ----------------------------------------------------------------------------------------------------------

    @Test
    fun `(a) a late read of the saved choice leaves the choice the user made in step 2`() {
        FakePhone().use { phone ->
            val prefs = KeepPrefs(keep = emptySet())
            val manager = phone.manager()
            setField(manager, "zchatPreferences", prefs.proxy)
            val flow = DestroyPinSetupFlow(prefs.proxy, manager) { false }
            flow.onSetUp()
            flow.onChooseKeep()
            flow.onTickContacts(true)
            flow.load()
            assertEquals("a late read of 'everything' undid the user's 'keep'", DestroyChoice.KEEP_SOME, flow.state.value.choice)
            assertTrue("a late read of 'everything' took the user's tick away", flow.state.value.contactsTicked)
        }
        FakePhone().use { phone ->
            val prefs = KeepPrefs(keep = setOf(DestroyWipe.KEEP_CONTACTS))
            val manager = phone.manager()
            setField(manager, "zchatPreferences", prefs.proxy)
            val flow = DestroyPinSetupFlow(prefs.proxy, manager) { false }
            flow.onSetUp()
            flow.onChooseEverything()
            flow.load()
            assertEquals("a late read of 'keep' undid the user's 'everything'", DestroyChoice.EVERYTHING, flow.state.value.choice)
        }
    }

    // ---- (b) ----------------------------------------------------------------------------------------------------------

    @Test
    fun `(b) a reload that started before a Save does not put the older choice back`() {
        FakePhone().use { phone ->
            val prefs = KeepPrefs(keep = setOf(DestroyWipe.KEEP_CONTACTS))
            prefs.readGate = CountDownLatch(1)
            val manager = phone.manager()
            setField(manager, "zchatPreferences", prefs.proxy)

            val reload = Thread { manager.reloadDestroyKeep() }.apply { start() }
            assertTrue("fixture: the reload read the store", prefs.readEntered.await(30, TimeUnit.SECONDS))

            val saved =
                runBlocking {
                    manager.saveDestroyKeep(emptySet()) { keep ->
                        prefs.keep = keep
                        true
                    }
                }
            assertEquals("fixture: the Save went through", DestroyKeepSave.SAVED, saved)
            assertEquals("fixture: the Save is held", emptySet<String>(), heldKeep(manager))

            prefs.readGate?.countDown()
            reload.join(30_000)
            assertFalse("fixture: the reload ended", reload.isAlive)
            assertEquals("an older reload put the earlier choice back over the Save", emptySet<String>(), heldKeep(manager))
        }
    }

    // ---- (c) ----------------------------------------------------------------------------------------------------------

    /**
     * SharedPreferences as Android keeps it: commit() and apply() put the change in memory first; commit() then answers
     * whether the disk write went through ([diskFails]: it did not), and the memory keeps the change either way.
     */
    private class AndroidLikePrefs : SharedPreferences {
        val memory = HashMap<String, Any?>()

        @Volatile var diskFails = false

        override fun getAll(): MutableMap<String, *> = synchronized(memory) { HashMap(memory) }

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = synchronized(memory) { memory[key] as String? ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = synchronized(memory) { memory[key] as MutableSet<String>? ?: defValues }

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = synchronized(memory) { memory[key] as Int? ?: defValue }

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = synchronized(memory) { memory[key] as Long? ?: defValue }

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = synchronized(memory) { memory[key] as Float? ?: defValue }

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = synchronized(memory) { memory[key] as Boolean? ?: defValue }

        override fun contains(key: String): Boolean = synchronized(memory) { memory.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
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

            private fun toMemory() {
                synchronized(memory) {
                    if (clear) memory.clear()
                    removals.forEach { memory.remove(it) }
                    changes.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v }
                }
            }

            override fun commit(): Boolean {
                toMemory()
                return !diskFails
            }

            override fun apply() = toMemory()
        }
    }

    private fun implOver(store: AndroidLikePrefs): ZchatPreferences {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val impl = allocate.invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        setField(impl, "e2ePrefs", store)
        return impl
    }

    private fun seeded(): AndroidLikePrefs =
        AndroidLikePrefs().apply {
            memory["destroy_storage_v3_done"] = true
            memory["destroy_pin"] = "synthetic-saved-hash"
            memory["destroy_pin_format_v2"] = true
            memory["destroy_keep_v1"] = "contacts"
        }

    @Test
    fun `(c) a choice whose write did not reach the disk does not stay in memory`() {
        val store = seeded()
        val prefs = implOver(store)
        assertEquals("fixture", setOf("contacts"), prefs.getDestroyKeep())
        store.diskFails = true

        assertFalse("a write that did not reach the disk was reported as saved", prefs.setDestroyKeep(emptySet()))

        assertEquals("the unsaved choice stayed in memory", setOf("contacts"), prefs.getDestroyKeep())
    }

    @Test
    fun `(c) a PIN set-up whose write did not reach the disk leaves the PIN and the choice as they were`() {
        val store = seeded()
        val prefs = implOver(store)
        store.diskFails = true

        val saved = runBlocking { prefs.setDestroySetup("13579", emptySet()) }

        assertFalse("a write that did not reach the disk was reported as saved", saved)
        assertEquals("the unsaved PIN stayed in memory", "synthetic-saved-hash", store.memory["destroy_pin"])
        assertEquals("the unsaved choice stayed in memory", setOf("contacts"), prefs.getDestroyKeep())

        val fresh = AndroidLikePrefs().apply { memory["destroy_storage_v3_done"] = true }
        val first = implOver(fresh)
        fresh.diskFails = true
        assertFalse(runBlocking { first.setDestroySetup("13579", setOf("contacts")) })
        assertFalse("a first PIN that was not saved is in memory", first.hasDestroyPin())
        assertEquals("a first choice that was not saved is in memory", emptySet<String>(), first.getDestroyKeep())
    }
}
