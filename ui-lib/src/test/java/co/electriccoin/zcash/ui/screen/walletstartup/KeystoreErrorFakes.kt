package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R2-3 test support: a stand-in for a SharedPreferences file that records every attempt to change it. Reads work
 * on [entries]; edit() is counted, and nothing written through an editor ever reaches [entries] unless
 * [allowWrites] is on.
 */
internal class RecordingPrefs(
    initial: Map<String, Any?> = emptyMap(),
    private val allowWrites: Boolean = false,
) : SharedPreferences {
    val entries = LinkedHashMap<String, Any?>(initial)
    var editCalls = 0
        private set

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(entries)

    override fun getString(
        key: String,
        defValue: String?
    ): String? = entries[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? = (entries[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(
        key: String,
        defValue: Int
    ): Int = entries[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long
    ): Long = entries[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float
    ): Float = entries[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean
    ): Boolean = entries[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = entries.containsKey(key)

    override fun edit(): SharedPreferences.Editor {
        editCalls++
        return RecordingEditor()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        Unit

    private inner class RecordingEditor : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clear = false

        override fun putString(
            key: String,
            value: String?
        ) = apply { puts[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ) = apply { puts[key] = values?.toSet() }

        override fun putInt(
            key: String,
            value: Int
        ) = apply { puts[key] = value }

        override fun putLong(
            key: String,
            value: Long
        ) = apply { puts[key] = value }

        override fun putFloat(
            key: String,
            value: Float
        ) = apply { puts[key] = value }

        override fun putBoolean(
            key: String,
            value: Boolean
        ) = apply { puts[key] = value }

        override fun remove(key: String) = apply { removes += key }

        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (!allowWrites) return true
            if (clear) entries.clear()
            removes.forEach { entries.remove(it) }
            entries.putAll(puts)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

/**
 * A Context whose shared-preferences files are [RecordingPrefs], and which records every attempt to delete or
 * move one.
 */
internal class RecordingContext : ContextWrapper(null) {
    val files = LinkedHashMap<String, RecordingPrefs>()
    val deletedFiles = CopyOnWriteArrayList<String>()
    val movedFiles = CopyOnWriteArrayList<String>()

    override fun getSharedPreferences(
        name: String,
        mode: Int
    ): SharedPreferences = synchronized(files) { files.getOrPut(name) { RecordingPrefs() } }

    override fun deleteSharedPreferences(name: String): Boolean {
        deletedFiles += name
        return false
    }

    override fun moveSharedPreferencesFrom(
        sourceContext: Context?,
        name: String
    ): Boolean {
        movedFiles += name
        return false
    }

    override fun getApplicationContext(): Context = this
}

/** Records what reaches the JVM's default uncaught-exception handler (a crash on a phone) while [block] runs. */
internal inline fun <T> recordingCrashes(block: (crashes: List<Throwable>) -> T): T {
    val crashes = CopyOnWriteArrayList<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, throwable -> crashes += throwable }
    return try {
        block(crashes)
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}

internal inline fun <reified T : Any> unusedDependency(): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "unused<${T::class.java.simpleName}>"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name}() is not used here")
        }
    } as T

internal fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause?.takeIf { next -> next !== it } }.take(16)
