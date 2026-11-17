package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-420 (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.3 R-store, §4.2, §7): where the relay-copy marks are kept.
 *
 * The marks ("relaycopy:<slot>") live in the encrypted, wallet-scoped held-reply store beside the held group replies,
 * the needs-join records and F8's ledger, each under its own prefix: `setRelayCopyMark(slot, value)` writes with commit()
 * and reports whether it reached the disk (null removes), `getRelayCopyMark(slot)` and `getRelayCopyMarkSlots()` read
 * them back. A wipe removes them, they move with their wallet, Delete Group removes that group's key, invite and
 * message marks only, and an install upgraded from 2.12.4 (which never wrote one) has none. The store is the real
 * [ZchatPreferencesImpl] over in-memory files ([realPreferences]); the methods are reached by name. Every value is
 * synthetic.
 */
class RelayCopyMarkStoreTest {
    private companion object {
        const val HELD_STORE_FIELD = "heldGroupAcceptPrefs"
        const val HELD_STORE_FILE = "zchat_held_group_accepts_encrypted"
        const val PREFIX = "relaycopy:"
        const val GROUP = "GSYNTHZC420A"
        const val OTHER_GROUP = "GSYNTHZC420B"
        const val MEMBER = "usyntheticzc420storemember01"
        const val PEER = "usyntheticzc420storepeer01"
        const val VALUE = "rumor-store|1790000000000|extra"
        const val GROUP_INFO = """{"id":"GSYNTHZC420A","name":"synthetic","active":true}"""
        const val RECORD = """{"inviter":"usyntheticzc420inviter01","accepter":"usyntheticzc420self01","memo":"synthetic-accept"}"""
    }

    private fun call(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("ZchatPreferencesImpl.$name/${args.size} does not exist (ZC-420): the marks have no store")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun set(
        impl: ZchatPreferencesImpl,
        slot: String,
        value: String?,
    ): Boolean = call(impl, "setRelayCopyMark", slot, value) as Boolean

    private fun get(
        impl: ZchatPreferencesImpl,
        slot: String,
    ): String? = call(impl, "getRelayCopyMark", slot) as String?

    @Suppress("UNCHECKED_CAST")
    private fun slots(impl: ZchatPreferencesImpl): Set<String> = call(impl, "getRelayCopyMarkSlots") as Set<String>

    private val zb = "zb|$PEER"
    private val gk = "gk|$GROUP|$MEMBER"
    private val gi = "gi|$GROUP|$MEMBER"
    private val gm = "gm|$GROUP|$MEMBER|3|41"

    @Test
    fun `a mark is written, read, listed and removed in the held-reply store under its own prefix`() {
        val (impl, files) = realPreferences()

        assertTrue("a mark that reached the disk reports true", set(impl, zb, VALUE))
        assertEquals(VALUE, get(impl, zb))
        assertEquals(setOf(zb), slots(impl))
        assertEquals(mapOf("$PREFIX$zb" to VALUE), files.getValue(HELD_STORE_FIELD).snapshot())

        set(impl, zb, null)
        assertNull(get(impl, zb))
        assertTrue(slots(impl).isEmpty())
    }

    @Test
    fun `an install upgraded from 2_12_4 has no marks`() {
        val (impl, _) = realPreferences()
        assertTrue(slots(impl).isEmpty())
        assertNull(get(impl, zb))
    }

    @Test
    fun `a wipe removes the marks and they move with their wallet in the held-reply store only`() {
        val (impl, files) = realPreferences()
        set(impl, zb, VALUE)
        set(impl, gm, VALUE)

        val carrying =
            impl
                .walletScopedStores()
                .filter { store -> store.exportEntries().keys.any { it.startsWith(PREFIX) } }
                .map { it.storeId }
        assertEquals("exactly the held-reply store carries the marks with the wallet", listOf(HELD_STORE_FILE), carrying)

        impl.clearAll()
        assertTrue(slots(impl).isEmpty())
        val held = files.getValue(HELD_STORE_FIELD).snapshot()
        assertTrue(held.keys.none { it.startsWith(PREFIX) })
    }

    @Test
    fun `a commit that does not reach the disk reports false and stores nothing, also when it throws`() {
        for (throwing in listOf(false, true)) {
            val (impl, files) = realPreferences()
            val backing = files.getValue(HELD_STORE_FIELD)
            ZchatPreferencesImpl::class.java
                .getDeclaredField(HELD_STORE_FIELD)
                .apply { isAccessible = true }
                .set(impl, failingStore(backing, throwing))

            assertFalse("a mark that did not reach the disk reported true (throwing=$throwing)", set(impl, zb, VALUE))
            assertTrue(backing.snapshot().isEmpty())
        }
    }

    @Test
    fun `held replies and needs-join records answer as before beside marks, and are never listed as marks`() {
        val (impl, _) = realPreferences()
        assertTrue(impl.setHeldGroupAccept(GROUP, RECORD))
        assertTrue(impl.setGroupInviteNeedsJoin(OTHER_GROUP, RECORD))
        set(impl, gi, VALUE)
        set(impl, zb, VALUE)

        assertEquals(mapOf(GROUP to RECORD), impl.getHeldGroupAccepts())
        assertEquals(RECORD, impl.getGroupInviteNeedsJoin(OTHER_GROUP))
        assertNull(impl.getGroupInviteNeedsJoin(GROUP))
        assertEquals(setOf(gi, zb), slots(impl))
    }

    @Test
    fun `Delete Group removes that group's key, invite and message marks only`() {
        val (impl, _) = realPreferences()
        impl.saveGroupInfo(GROUP, GROUP_INFO)
        val otherGk = "gk|$OTHER_GROUP|$MEMBER"
        val otherGm = "gm|$OTHER_GROUP|$MEMBER|1|1"
        for (s in listOf(zb, gk, gi, gm, otherGk, otherGm)) assertTrue(set(impl, s, VALUE))

        impl.deleteGroup(GROUP)

        assertEquals(setOf(zb, otherGk, otherGm), slots(impl))
    }

    /** [backing] with an editor whose commit never reaches the disk: it returns false, or throws as a store that cannot encrypt. */
    private fun failingStore(
        backing: MemorySharedPreferences,
        throwing: Boolean,
    ): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") {
                return@newProxyInstance try {
                    method.invoke(backing, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            lateinit var editor: Any
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, edit, _ ->
                    when (edit.name) {
                        "commit" -> if (throwing) throw SecurityException("synthetic: the store cannot encrypt") else false
                        "apply" -> null
                        else -> editor
                    }
                }
            editor
        } as SharedPreferences
}
