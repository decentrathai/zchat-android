package co.electriccoin.zcash.ui.screen.chat.datasource

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W-72 (register ZC-847), SPEC_F_20261019 rules R3 and R7 at the store: while a pre-update mark stands it keeps one held
 * ZBOOT (the latest, at most 1,024 characters); a mark that names another key than the stored one holds nothing. The
 * release removes the mark, its probe record and its held ZBOOT, and sets the verified flag when the user tapped "Mark as
 * verified", in ONE commit, and bumps the handshake tick so an open chat redraws.
 *
 * The REAL [ZchatPreferencesImpl] key-store functions over in-memory files. Today holdLegacyBoot, getHeldLegacyBoot and
 * releaseLegacyKey do not exist. Every address and key is synthetic.
 */
class LegacyKeyMarkHoldAndReleaseTest {
    private val keys = ConversationIdStoreFixture.RecordingPrefs()
    private val ticks = MutableStateFlow(0L)
    private val store: ZchatPreferencesImpl =
        (unsafeAllocate(ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl).also { impl ->
            set(impl, "e2ePrefs", keys)
            set(impl, "convMappingPrefs", ConversationIdStoreFixture.RecordingPrefs())
            set(impl, "modePrefs", ConversationIdStoreFixture.RecordingPrefs())
            set(impl, "_e2eHandshakeTicks", ticks)
        }

    @Test
    fun `(12) the mark holds the latest ZBOOT and the release takes everything in one commit with the verified flag`() {
        keys
            .edit()
            .putString("e2e_peer_pub_$PEER", KEY)
            .putString("legacykey:$PEER", KEY)
            .putString("legacyprobe:$PEER", "1\nZC72PRB3\n")
            .commit()
        keys.commits.clear()

        assertEquals("holdLegacyBoot is missing or did not save", true, call("holdLegacyBoot", PEER, "ZBOOT-one"))
        assertEquals(true, call("holdLegacyBoot", PEER, "ZBOOT-two" + "x".repeat(2_000)))
        val held = call("getHeldLegacyBoot", PEER) as String?
        assertTrue("the slot keeps the latest only: $held", held?.startsWith("ZBOOT-two") == true)
        assertTrue("the slot holds more than 1,024 characters", held!!.length <= 1_024)
        assertEquals("holding a ZBOOT changed the key the mark names", KEY, call("getLegacyKeyMark", PEER))

        keys.commits.clear()
        val before = ticks.value
        assertEquals("releaseLegacyKey is missing or did not save", true, call("releaseLegacyKey", PEER, true))
        assertEquals("the release is not one commit: ${keys.commits}", 1, keys.commits.size)
        assertTrue(
            "the release's commit lacks the mark, the probe record or the verified flag: ${keys.commits}",
            keys.commits.single().containsAll(listOf("legacykey:$PEER", "legacyprobe:$PEER", "e2e_verified_$PEER")),
        )
        assertNull(call("getLegacyKeyMark", PEER))
        assertNull("the release left the held ZBOOT", call("getHeldLegacyBoot", PEER))
        assertFalse(keys.contains("legacyprobe:$PEER"))
        assertTrue("the tap's release did not set the verified flag", store.isE2EVerified(PEER))
        assertTrue("the release did not bump the handshake tick", ticks.value > before)
        assertEquals("a release with no mark reports one", false, call("releaseLegacyKey", PEER, true))
        assertEquals("a ZBOOT is held with no mark", false, call("holdLegacyBoot", PEER, "ZBOOT-three"))
    }

    @Test
    fun `(12) a mark naming another key than the stored one holds nothing, and a release without the tap sets no flag`() {
        keys
            .edit()
            .putString("e2e_peer_pub_$PEER", OTHER)
            .putString("legacykey:$PEER", KEY)
            .commit()
        assertEquals("a mark naming the old key held a ZBOOT", false, call("holdLegacyBoot", PEER, "ZBOOT-four"))
        assertNull(call("getHeldLegacyBoot", PEER))

        keys.edit().putString("e2e_peer_pub_$PEER", KEY).commit()
        assertEquals(true, call("releaseLegacyKey", PEER, false))
        assertFalse("a release without the tap set the verified flag", store.isE2EVerified(PEER))
    }

    private fun call(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            ZchatPreferencesImpl::class.java.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("the store has no $name")
        return try {
            method.invoke(store, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
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
        const val PEER = "usyntheticlegacypeer00000012"
        const val KEY = "c3ludGhldGljLWxlZ2FjeS1rZXktMTI"
        const val OTHER = "c3ludGhldGljLW90aGVyLWtleQ"
    }
}
