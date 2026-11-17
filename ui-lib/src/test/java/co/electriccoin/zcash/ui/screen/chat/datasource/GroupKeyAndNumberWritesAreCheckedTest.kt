package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * ZC-778 (review of 12 Oct 2026, finding 1, part d) — the store's checked writes of a group key and of its number.
 *
 * The creator's removal saves the new key before it sends it, and has to know that the key reached the disk. The plain
 * writes (`saveGroupKey(groupId, keyEpoch, key)` and `setGroupKeyEpoch(groupId, epoch)`) are `apply()` and report
 * nothing. Wanted: a checked form of each, taking one more argument (`durable`). With `durable = true` it is one
 * `commit()`: it returns only when that commit succeeded and throws an IOException when the store refused it. With
 * `durable = false` it is the plain write. The plain forms themselves stay as they are.
 *
 * 2.12.4 compatibility (skill compat-2124): the checked writes use the names 2.12.4 reads. Copied from
 * `git show v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt`,
 * lines 2947-2953: `groupKeysPrefs.getString("${groupId}_$keyEpoch", null)` and
 * `prefs.getInt("$GROUP_EPOCH_PREFIX$groupId", 0)` with `GROUP_EPOCH_PREFIX = "epoch_"`.
 *
 * No Context: the store is allocated without its constructor and given fake preference files whose `commit()` result
 * is chosen here, following HandshakeMarkerWritesReportedTest. The checked forms are found by name, so this file
 * compiles before they exist. All values are synthetic.
 */
class GroupKeyAndNumberWritesAreCheckedTest {
    @Test
    fun `the checked key write is one commit under the name the stored keys have`() {
        val keys = FakeFile(commitSucceeds = true)
        val impl = storeWith(keys, FakeFile(commitSucceeds = true))
        val thrown = thrownBy { checkedWrite("saveGroupKey", 4).invoke(impl, GROUP, 2, KEY, true) }
        assertEquals("a key the store took was reported as refused", null, thrown)
        assertEquals(listOf("putString ${GROUP}_2=$KEY", "commit"), keys.calls.toList())
    }

    @Test
    fun `the checked key write throws when the store refused the commit`() {
        val keys = FakeFile(commitSucceeds = false)
        val impl = storeWith(keys, FakeFile(commitSucceeds = true))
        val thrown = thrownBy { checkedWrite("saveGroupKey", 4).invoke(impl, GROUP, 2, KEY, true) }
        assertTrue("a refused key commit was reported as saved (thrown: $thrown)", thrown is IOException)
        assertEquals(listOf("putString ${GROUP}_2=$KEY", "commit"), keys.calls.toList())
    }

    @Test
    fun `the checked number write is one commit under the name the stored numbers have`() {
        val numbers = FakeFile(commitSucceeds = true)
        val impl = storeWith(FakeFile(commitSucceeds = true), numbers)
        val thrown = thrownBy { checkedWrite("setGroupKeyEpoch", 3).invoke(impl, GROUP, 2, true) }
        assertEquals("a number the store took was reported as refused", null, thrown)
        assertEquals(listOf("putInt epoch_$GROUP=2", "commit"), numbers.calls.toList())
    }

    @Test
    fun `the checked number write throws when the store refused the commit`() {
        val numbers = FakeFile(commitSucceeds = false)
        val impl = storeWith(FakeFile(commitSucceeds = true), numbers)
        val thrown = thrownBy { checkedWrite("setGroupKeyEpoch", 3).invoke(impl, GROUP, 2, true) }
        assertTrue("a refused number commit was reported as saved (thrown: $thrown)", thrown is IOException)
        assertEquals(listOf("putInt epoch_$GROUP=2", "commit"), numbers.calls.toList())
    }

    @Test
    fun `with durable false the checked forms are the plain background writes`() {
        val keys = FakeFile(commitSucceeds = false)
        val numbers = FakeFile(commitSucceeds = false)
        val impl = storeWith(keys, numbers)
        assertEquals(null, thrownBy { checkedWrite("saveGroupKey", 4).invoke(impl, GROUP, 2, KEY, false) })
        assertEquals(null, thrownBy { checkedWrite("setGroupKeyEpoch", 3).invoke(impl, GROUP, 2, false) })
        assertEquals(listOf("putString ${GROUP}_2=$KEY", "apply"), keys.calls.toList())
        assertEquals(listOf("putInt epoch_$GROUP=2", "apply"), numbers.calls.toList())
    }

    @Test
    fun `control - the plain writes stay in the background under the same names`() {
        val keys = FakeFile(commitSucceeds = false)
        val numbers = FakeFile(commitSucceeds = false)
        val impl = storeWith(keys, numbers)
        impl.saveGroupKey(GROUP, 2, KEY)
        impl.setGroupKeyEpoch(GROUP, 2)
        assertEquals(listOf("putString ${GROUP}_2=$KEY", "apply"), keys.calls.toList())
        assertEquals(listOf("putInt epoch_$GROUP=2", "apply"), numbers.calls.toList())
    }

    // ------------------------------------------------------------------ harness

    /** One preferences file: every write made on it, in order, and the result its `commit()` reports. */
    private class FakeFile(
        private val commitSucceeds: Boolean,
    ) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val prefs: SharedPreferences by lazy {
            lateinit var editor: SharedPreferences.Editor
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "commit" -> {
                            calls += "commit"
                            commitSucceeds
                        }
                        "apply" -> {
                            calls += "apply"
                            null
                        }
                        "putString", "putInt" -> {
                            calls += "${method.name} ${args!![0]}=${args[1]}"
                            editor
                        }
                        else -> editor
                    }
                } as SharedPreferences.Editor
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "edit" -> editor
                    "getInt" -> 0
                    "getBoolean" -> false
                    else -> null
                }
            } as SharedPreferences
        }
    }

    /** A [ZchatPreferencesImpl] with no Context, whose group keys go to [keys] and whose key numbers go to [numbers]. */
    private fun storeWith(
        keys: FakeFile,
        numbers: FakeFile,
    ): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        for ((field, file) in listOf("groupKeysPrefs" to keys, "prefs" to numbers)) {
            ZchatPreferencesImpl::class.java
                .getDeclaredField(field)
                .apply { isAccessible = true }
                .set(impl, file.prefs)
        }
        return impl
    }

    /** The checked form of [name]: the overload with [parameters] arguments, the last one the `durable` flag. */
    private fun checkedWrite(
        name: String,
        parameters: Int,
    ): Method {
        val method =
            ZchatPreferencesImpl::class.java.methods.firstOrNull {
                it.name == name && it.parameterCount == parameters && it.parameterTypes.last() == java.lang.Boolean.TYPE
            }
        assertNotNull("the store has no checked form of $name (one more argument: durable)", method)
        return method!!
    }

    /** What [write] threw (the store's own exception, not reflection's wrapper), or null. */
    private fun thrownBy(write: () -> Unit): Throwable? =
        try {
            write()
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }

    private companion object {
        const val GROUP = "zgrp_synthetic778"
        const val KEY = "synthetic-wrapped-group-key"
    }
}
