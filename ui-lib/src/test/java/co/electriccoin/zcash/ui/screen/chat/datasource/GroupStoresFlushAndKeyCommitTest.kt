package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * KI-25 pair 1b, ZC-704 (KI-25-SPEC 3.6b R19, "an entry leaves the list only after its effect is on the disk";
 * section 4.1b class 25) — the store's two new methods, on the real [ZchatPreferencesImpl] ([realPreferences]) over
 * in-memory preference files that record their commits ([CommitLogFile]).
 *
 * - `flushGroupStores(): Boolean` commits the group-key store, the group-info store and the main file, in that
 *   order, and answers true only when all three did;
 * - `groupKeyStoreCommitFailed(): Boolean` commits the group-key store and answers true when that was refused;
 * - each commit is a real one-key change: the counter under the reserved key `flush:seq` (a String of digits) grows
 *   by one in the store that is committed; a value that cannot be read counts as 0; a store that throws reads as
 *   refused and nothing escapes;
 * - the key is no group and no group key, the wipe clears it, a set-aside bundle carries it, and 2.12.4 never
 *   selects it ([Frozen2124]).
 *
 * Both methods are reached by name through reflection, so this file compiles on the commit before the fix, where
 * every case fails with "no such operation".
 *
 * All group ids, keys and values are synthetic.
 */
class GroupStoresFlushAndKeyCommitTest {
    /** The commits of every [CommitLogFile] of one store, in call order: the file's name and the keys its editor changed. */
    private val commits = mutableListOf<Pair<String, Set<String>>>()

    /**
     * A preferences file in memory with the platform's two copies: what the process holds ([memory]) and what a
     * `commit()` wrote ([committed]). `apply()` changes the memory only; a `commit()` that is taken writes the whole
     * memory out and is logged in [commits]; one that is refused ([refuse]) keeps the new values in memory, as the
     * platform does, writes nothing and answers false; with [throws] `edit()` throws. A value of another type under a
     * key read as a String throws `ClassCastException`, as the platform does.
     */
    private inner class CommitLogFile(
        val name: String,
    ) : SharedPreferences {
        val memory = LinkedHashMap<String, Any?>()
        var committed: Map<String, Any?> = emptyMap()
        var refuse = false
        var throws = false

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(memory)

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = memory[key] as String? ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = memory[key] as MutableSet<String>? ?: defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = memory[key] as Int? ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = memory[key] as Long? ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = memory[key] as Float? ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = memory[key] as Boolean? ?: defValue

        override fun contains(key: String): Boolean = memory.containsKey(key)

        override fun edit(): SharedPreferences.Editor {
            if (throws) throw IllegalStateException("fixture: the store $name cannot be written")
            return Editor()
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private var clear = false

            private fun change(
                key: String,
                value: Any?,
            ) = apply { changes[key] = value }

            override fun putString(
                key: String,
                value: String?,
            ) = change(key, value)

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = change(key, values?.let { HashSet(it) })

            override fun putInt(
                key: String,
                value: Int,
            ) = change(key, value)

            override fun putLong(
                key: String,
                value: Long,
            ) = change(key, value)

            override fun putFloat(
                key: String,
                value: Float,
            ) = change(key, value)

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = change(key, value)

            override fun remove(key: String) = change(key, null)

            override fun clear() = apply { clear = true }

            private fun toMemory() {
                if (clear) memory.clear()
                changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
            }

            override fun commit(): Boolean {
                toMemory()
                if (refuse) return false
                committed = LinkedHashMap(memory)
                commits += name to changes.keys.toSet()
                return true
            }

            override fun apply() = toMemory()
        }
    }

    /** The real store with its group-key store, its group-info store and its main file as [CommitLogFile]s. */
    private inner class Store {
        val impl: ZchatPreferencesImpl = realPreferences().first
        val keys = CommitLogFile(KEYS)
        val info = CommitLogFile(INFO)
        val main = CommitLogFile(MAIN)
        val three = listOf(keys, info, main)

        init {
            for ((field, file) in listOf("groupKeysPrefs" to keys, "groupInfoPrefs" to info, "prefs" to main)) {
                ZchatPreferencesImpl::class.java
                    .getDeclaredField(field)
                    .apply { isAccessible = true }
                    .set(impl, file)
            }
        }

        private fun call(name: String): Boolean {
            val method =
                ZchatPreferencesImpl::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: AdminCheck.missingKept("ZchatPreferences.$name(): Boolean")
            return try {
                method.invoke(impl) as Boolean
            } catch (e: InvocationTargetException) {
                throw AssertionError("$name threw ${e.targetException.javaClass.simpleName}: a method never throws", e.targetException)
            }
        }

        fun flush(): Boolean = call("flushGroupStores")

        fun keyCommitFailed(): Boolean = call("groupKeyStoreCommitFailed")

        fun counter(file: CommitLogFile): Any? = file.memory[COUNTER]
    }

    // ------------------------------------------------------- flushGroupStores

    @Test
    fun `flushGroupStores commits the group-key store, the group-info store and the main file, in that order`() {
        val store = Store()
        store.impl.saveGroupKey(GROUP, 2, GROUP_KEY)
        store.impl.saveGroupInfo(GROUP, GROUP_INFO)
        store.impl.setGroupKeyEpoch(GROUP, 2)
        store.impl.setGroupRemovalFloor(GROUP, 2)
        assertTrue("fixture: the writes before the flush were made with apply()", commits.isEmpty())
        assertNull("fixture: the key is not in the committed copy yet", store.keys.committed["${GROUP}_2"])

        assertTrue("flushGroupStores answered false although all three stores took their commit", store.flush())

        assertEquals("not one commit() on each of the three stores, in that order", listOf(KEYS, INFO, MAIN), commits.map { it.first })
        assertEquals("the key written with apply() is not in the committed copy", GROUP_KEY, store.keys.committed["${GROUP}_2"])
        assertEquals("the group record written with apply() is not in the committed copy", GROUP_INFO, store.info.committed[GROUP])
        val mainFile = store.main.committed
        val twos = mainFile.values.count { it == 2 }
        assertTrue("the epoch and the floor written with apply() are not in the main file's committed copy", twos >= 2)
    }

    @Test
    fun `flushGroupStores answers false when one of the three stores refuses`() {
        for (refusing in listOf(KEYS, INFO, MAIN)) {
            commits.clear()
            val store = Store()
            store.three.single { it.name == refusing }.refuse = true

            assertFalse("flushGroupStores answered true although $refusing refused its commit", store.flush())
        }
    }

    // ---------------------------------------------- groupKeyStoreCommitFailed

    @Test
    fun `groupKeyStoreCommitFailed makes one commit on the group-key store and says whether it was refused`() {
        val store = Store()
        store.impl.saveGroupKey(GROUP, 3, GROUP_KEY)

        assertFalse("groupKeyStoreCommitFailed answered true although the commit was taken", store.keyCommitFailed())

        assertEquals("not exactly one commit(), on the group-key store", listOf(KEYS), commits.map { it.first })
        assertEquals("the key written with saveGroupKey is not in the committed copy", GROUP_KEY, store.keys.committed["${GROUP}_3"])

        store.keys.refuse = true
        store.impl.saveGroupKey(GROUP, 4, GROUP_KEY)
        assertTrue("groupKeyStoreCommitFailed answered false although the commit was refused", store.keyCommitFailed())
        assertNull("fixture: a refused commit wrote nothing", store.keys.committed["${GROUP}_4"])
        assertEquals("the key of a refused commit is not in the store's memory", GROUP_KEY, store.impl.getGroupKey(GROUP, 4))
    }

    // ------------------------------------------------- a real one-key change

    @Test
    fun `every commit of either method changes exactly one key, the counter, and the counter grows by one`() {
        val store = Store()

        assertTrue(store.flush())
        assertEquals(List(3) { setOf(COUNTER) }, commits.map { it.second })
        assertEquals("the counter does not start at 1 as a String of digits", listOf<Any?>("1", "1", "1"), store.three.map(store::counter))

        assertFalse(store.keyCommitFailed())
        assertEquals("the key commit changed another key than the counter", setOf(COUNTER), commits.last().second)
        assertEquals("the key commit did not add one to the group-key store's counter", "2", store.counter(store.keys))
        assertEquals("the key commit touched another store", listOf<Any?>("1", "1"), listOf(store.info, store.main).map(store::counter))

        assertTrue(store.flush())
        assertEquals(listOf<Any?>("3", "2", "2"), store.three.map(store::counter))
        assertEquals(listOf(KEYS, INFO, MAIN, KEYS, KEYS, INFO, MAIN), commits.map { it.first })
        assertEquals("a commit changed more than the counter", setOf(setOf(COUNTER)), commits.map { it.second }.toSet())
    }

    @Test
    fun `a counter that is not a String of digits counts as 0 and is overwritten`() {
        for (unreadable in listOf<Any>("synthetic-not-a-number", "", "-4", 7, 7L, true)) {
            commits.clear()
            val store = Store()
            store.three.forEach { it.memory[COUNTER] = unreadable }

            assertTrue("flushGroupStores answered false over a counter of $unreadable", store.flush())
            assertEquals("a counter of $unreadable was not counted as 0", listOf<Any?>("1", "1", "1"), store.three.map(store::counter))

            store.keys.memory[COUNTER] = unreadable
            assertFalse("groupKeyStoreCommitFailed answered true over a counter of $unreadable", store.keyCommitFailed())
            assertEquals("1", store.counter(store.keys))
        }
    }

    @Test
    fun `a store that throws reads as refused and nothing escapes`() {
        for (throwing in listOf(KEYS, INFO, MAIN)) {
            val store = Store()
            store.three.single { it.name == throwing }.throws = true

            assertFalse("flushGroupStores answered true although $throwing threw", store.flush())
        }
        val store = Store()
        store.keys.throws = true
        assertTrue("groupKeyStoreCommitFailed answered false although the group-key store threw", store.keyCommitFailed())
    }

    // ----------------------------------- the key is no group and no group key

    @Test
    fun `the counter key is no group and no group key, a deleted group leaves it and the wipe clears it`() {
        val store = Store()
        store.impl.saveGroupInfo(GROUP, GROUP_INFO)
        store.impl.saveGroupKey(GROUP, 1, GROUP_KEY)
        assertTrue(store.flush())
        val counterValue = store.counter(store.keys)

        val asRecord = store.impl.getGroupInfo(COUNTER)
        assertNull(
            "the counter in the group-info store reads as a group record",
            asRecord?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) },
        )
        for (groupId in listOf(GROUP, COUNTER, "flush", "flush:", "flush:seq_", "")) {
            for (epoch in listOf(-1, 0, 1, 2)) {
                val answered = store.impl.getGroupKey(groupId, epoch)
                assertTrue(
                    "getGroupKey($groupId, $epoch) answered the counter",
                    answered == null || (groupId == GROUP && epoch == 1 && answered == GROUP_KEY),
                )
            }
        }
        assertEquals("fixture: the group's own key is answered", GROUP_KEY, store.impl.getGroupKey(GROUP, 1))
        assertFalse("the counter is listed as a group", COUNTER in store.impl.getAllGroupIds())

        store.impl.deleteGroup(GROUP)
        assertNull("fixture: the group's key went with the group", store.impl.getGroupKey(GROUP, 1))
        assertEquals("deleteGroup took the counter out of a store", listOf(counterValue, "1", "1"), store.three.map(store::counter))

        store.impl.clearAll()
        assertEquals("the wipe left the counter in a store", listOf<Any?>(null, null, null), store.three.map(store::counter))
    }

    @Test
    fun `a set-aside bundle of the wallet's stores carries the counter and puts it back`() {
        val store = Store()
        assertTrue(store.flush())
        assertFalse(store.keyCommitFailed())
        val before = store.three.map(store::counter)
        assertEquals(listOf<Any?>("2", "1", "1"), before)

        val scoped = store.impl.walletScopedStores()
        val bundle = scoped.associate { it.storeId to it.exportEntries() }
        assertEquals("not exactly the three stores carry the counter in a bundle", 3, bundle.values.count { COUNTER in it })

        // Another wallet's (empty) stores come in, then this wallet's are put back.
        scoped.forEach { it.importEntries(emptyMap()) }
        assertEquals(listOf<Any?>(null, null, null), store.three.map(store::counter))
        scoped.forEach { it.importEntries(bundle.getValue(it.storeId)) }

        assertEquals("the bundle did not put the counter back", before, store.three.map(store::counter))
    }

    // ----------------------------------------------------------------- 2.12.4

    /**
     * What 2.12.4 does with the keys of the two files it shares with this build, copied from
     * `git show v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt`
     * (compat-2124 rule 1):
     * - [groupKeysToRemove]: line 2929, the key filter of `deleteGroup` over the group-key store;
     * - [disappearingTtlKeys]: line 3136, the first listing of the main file (`loadAllDisappearingTtlsRaw`), with the
     *   constant of line 1625;
     * - [lastReadTimestamps]: lines 3188-3192, the second listing of the main file (`loadAllLastReadTimestampsRaw`),
     *   with the constant of line 1624.
     * 2.12.4 lists these files nowhere else; it reads every other key by its name.
     */
    private object Frozen2124 {
        private const val LAST_READ_PREFIX = "lastread:"
        private const val DISAPPEAR_TTL_PREFIX = "disappear_ttl:"

        fun groupKeysToRemove(
            groupKeysPrefs: SharedPreferences,
            groupId: String,
        ): List<String> {
            val keysToRemove = groupKeysPrefs.all.keys.filter { it.startsWith("${groupId}_") }
            return keysToRemove
        }

        fun disappearingTtlKeys(prefs: SharedPreferences): List<String> = prefs.all.keys.filter { it.startsWith(DISAPPEAR_TTL_PREFIX) }

        fun lastReadTimestamps(prefs: SharedPreferences): Map<String, Long> =
            prefs.all
                .filterKeys { it.startsWith(LAST_READ_PREFIX) }
                .mapNotNull { (key, value) ->
                    (value as? Long)?.let { key.removePrefix(LAST_READ_PREFIX) to it }
                }.toMap()
    }

    @Test
    fun `2_12_4 never selects the counter - its deleteGroup filter and its two listings of the main file`() {
        val store = Store()
        store.impl.saveGroupKey(GROUP, 1, GROUP_KEY)
        assertTrue(store.flush())
        assertEquals(
            "fixture: the counter is in the group-key store and the main file",
            listOf<Any?>("1", "1"),
            listOf(store.keys, store.main).map(store::counter),
        )

        for (groupId in listOf(GROUP, "flush", "flush:seq", "zgrp_")) {
            assertFalse(
                "2.12.4's deleteGroup($groupId) would remove the counter from the group-key store",
                COUNTER in Frozen2124.groupKeysToRemove(store.keys, groupId),
            )
        }
        assertEquals(
            "fixture: the frozen filter selects the group's own key",
            listOf("${GROUP}_1"),
            Frozen2124.groupKeysToRemove(store.keys, GROUP),
        )
        assertEquals(
            "2.12.4 lists the counter as a disappearing-message timer",
            emptyList<String>(),
            Frozen2124.disappearingTtlKeys(store.main),
        )
        assertEquals("2.12.4 lists the counter as a read marker", emptyMap<String, Long>(), Frozen2124.lastReadTimestamps(store.main))
    }

    // ------------------------------------------------------------ source scan

    @Test
    fun `source - the real store overrides both methods and the interface defaults answer false`() {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val source =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt") }
                .firstOrNull { it.isFile }
                ?.readText()
                ?: throw AssertionError("could not find ZchatPreferences.kt from ${start.path}")
        for (name in listOf("flushGroupStores", "groupKeyStoreCommitFailed")) {
            if (!Regex("""\bfun\s+$name\s*\(\s*\)\s*:\s*Boolean""").containsMatchIn(source)) {
                AdminCheck.missingKept("ZchatPreferences.$name(): Boolean")
            }
            assertTrue(
                "the interface default of $name does not answer false (a store that does not know it reads: not flushed, commit taken)",
                Regex("""(?<!override )fun\s+$name\s*\(\s*\)\s*:\s*Boolean\s*=\s*false\b""").containsMatchIn(source),
            )
            assertTrue(
                "ZchatPreferencesImpl does not override $name: " +
                    (if (name == "flushGroupStores") "every list would stay for ever" else "no key commit would ever be made"),
                Regex("""override\s+fun\s+$name\s*\(\s*\)\s*:\s*Boolean""").containsMatchIn(source) &&
                    ZchatPreferencesImpl::class.java.declaredMethods.any { it.name == name && it.parameterCount == 0 },
            )
        }
    }

    private companion object {
        const val COUNTER = "flush:seq"
        const val KEYS = "group-key store"
        const val INFO = "group-info store"
        const val MAIN = "main file"
        const val GROUP = "zgrp_25a0b1c2d3e4f5a6b7c8d9e0"
        const val GROUP_KEY = "c3ludGhldGljLWdyb3VwLWtleS1mb3ItY2xhc3MtMjU="
        const val GROUP_INFO = """{"id":"zgrp_25a0b1c2d3e4f5a6b7c8d9e0","name":"synthetic","active":true}"""
    }
}
