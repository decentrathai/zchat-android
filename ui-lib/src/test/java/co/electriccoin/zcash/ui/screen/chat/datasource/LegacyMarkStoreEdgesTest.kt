package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W-72, independent review of 3426ade7d findings 4 and 5 (P3), at the store:
 *  - the start pass runs from the init block, before the handshake-tick flow below it in the class is made: written marks
 *    must not be followed by a crash and a false "marks not written" (the instance here has no tick flow, as at init);
 *  - a mark written for another key (the restore rule) takes the old key's probe record with it (the spec's store table:
 *    the probe goes with the mark).
 *
 * The REAL [ZchatPreferencesImpl] functions over in-memory files. Today the pass throws after its commit and the old probe
 * record stays. Every address and key is synthetic.
 */
class LegacyMarkStoreEdgesTest {
    private val keys = ConversationIdStoreFixture.RecordingPrefs()

    private fun bareStore(withTicks: Boolean): ZchatPreferencesImpl =
        (unsafeAllocate(ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl).also { impl ->
            set(impl, "e2ePrefs", keys)
            set(impl, "convMappingPrefs", ConversationIdStoreFixture.RecordingPrefs())
            if (withTicks) set(impl, "_e2eHandshakeTicks", kotlinx.coroutines.flow.MutableStateFlow(0L))
        }

    @Test
    fun `the start pass at init, before the tick flow exists, writes its marks and does not throw`() {
        val store = bareStore(withTicks = false)
        keys
            .edit()
            .putString("e2e_peer_pub_$PEER", KEY)
            .commit()

        val method =
            ZchatPreferencesImpl::class.java.declaredMethods.first { it.name == "markLegacyKeysOnce" && it.parameterCount == 1 }
        method.isAccessible = true
        val result =
            try {
                method.invoke(store, { true })
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw AssertionError("the start pass threw after writing its marks: ${e.targetException.javaClass.simpleName}")
            }

        assertEquals("the pass did not report the mark it wrote", 1, result)
        assertEquals(KEY, keys.getString("legacykey:$PEER", null))
        assertTrue(keys.getBoolean("legacykey_done", false))
    }

    @Test
    fun `a mark written for another key takes the old key's probe record`() {
        val store = bareStore(withTicks = true)
        keys
            .edit()
            .putString("e2e_peer_pub_$PEER", KEY)
            .putString("legacykey:$PEER", KEY)
            .putString("legacyprobe:$PEER", "1\nZC72PRB5\n")
            .commit()

        val method =
            ZchatPreferencesImpl::class.java.methods.first { it.name == "storePeerKey" && it.parameterCount == 5 }
        assertEquals(true, method.invoke(store, PEER, OTHER, false, false, true))

        assertEquals("the new key's mark was not written", OTHER, keys.getString("legacykey:$PEER", null))
        assertFalse("the old key's probe record stayed with the new mark", keys.contains("legacyprobe:$PEER"))
    }

    private fun set(
        impl: ZchatPreferencesImpl,
        name: String,
        value: Any,
    ) {
        ZchatPreferencesImpl::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(impl, value)
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

    private companion object {
        const val PEER = "usyntheticlegacyedges0000001"
        const val KEY = "c3ludGhldGljLWxlZ2FjeS1lZGdlcw"
        const val OTHER = "c3ludGhldGljLW90aGVyLWtleQ"
    }
}
