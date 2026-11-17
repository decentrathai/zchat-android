package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences

/**
 * FC-ID (ZC-892) test support: the REAL conversation-id store functions of [ZchatPreferencesImpl] over an in-memory
 * file. The instance is made without its constructor (which opens every Android store) and its conversation-id file
 * ([ZchatPreferencesImpl] `convMappingPrefs`) is set to [file]; only the conversation-id functions may be called on it.
 * [file] records every commit as the set of keys it wrote, so a test can tell "in the same commit" apart.
 */
internal class ConversationIdStoreFixture {
    val file = RecordingPrefs()

    val store: ZchatPreferencesImpl =
        (unsafeAllocate(ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl).also { impl ->
            ZchatPreferencesImpl::class.java
                .getDeclaredField("convMappingPrefs")
                .apply { isAccessible = true }
                .set(impl, file)
        }

    fun conv(id: String): String? = file.getString("conv:$id", null)

    fun peer(address: String): String? = file.getString("peer:$address", null)

    fun own(id: String): String? = file.getString("own:$id", null)

    fun initPending(address: String): String? = file.getString("initpending:$address", null)

    /** The keys in the file that start with "own:". */
    fun ownKeys(): Set<String> =
        file.all.keys
            .filter { it.startsWith("own:") }
            .toSet()

    /** Calls a store function by name (it may not exist in a build without FC-ID): null when there is none. */
    fun call(
        name: String,
        vararg args: Any?,
    ): Result<Any?>? {
        val method =
            ZchatPreferencesImpl::class.java.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: return null
        return runCatching {
            try {
                method.invoke(store, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun unsafeAllocate(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe =
            unsafeClass
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)!!
    }

    /** In-memory SharedPreferences that records the keys each commit wrote or removed. */
    class RecordingPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()
        val commits: MutableList<Set<String>> = java.util.Collections.synchronizedList(mutableListOf())

        override fun getAll(): MutableMap<String, *> = synchronized(values) { HashMap(values) }

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = synchronized(values) { values[key] as String? ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = synchronized(values) { values[key] as MutableSet<String>? ?: defValues }

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = synchronized(values) { values[key] as Int? ?: defValue }

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = synchronized(values) { values[key] as Long? ?: defValue }

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = synchronized(values) { values[key] as Float? ?: defValue }

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = synchronized(values) { values[key] as Boolean? ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
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
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach { values.remove(it) }
                    changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                }
                commits += changes.keys + removals
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
