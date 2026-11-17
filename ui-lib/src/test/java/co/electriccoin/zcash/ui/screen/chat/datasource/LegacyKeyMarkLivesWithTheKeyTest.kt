package co.electriccoin.zcash.ui.screen.chat.datasource

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W-72 (owner, 19 Oct 2026; register ZC-847, ZC-852, ZC-855), SPEC_F_20261019 rule R1 and the removal rows of its store
 * table as settled in SPEC_DECISIONS D1, D2 and D6, RED tests 1 to 3: a contact key that ZChat 2.12.4 stored carries no
 * origin, so the first start of this build marks every stored contact key once ("legacykey:<address>" = the key, in the key
 * store beside it), except at an address the user already marked verified, and writes the done-flag "legacykey_done" INTO
 * that key store in the same commit, after reading the whole store (D1). A store with the flag is never marked again; a
 * store brought in later without it (another wallet's, a kept copy put back) is marked when the store is opened or
 * reloaded. A mark stands only while it names the stored key; the key's removal takes the mark and its probe record
 * ("legacyprobe:<address>") with it; another key written by storePeerKey takes the mark away and is stored as a claim in
 * the same commit (D6), so the replacement still moves no relay identity. The key store's first complete history read is
 * recorded beside the keys ("legacykey_history_read", D2).
 *
 * The REAL [ZchatPreferencesImpl] key-store functions over in-memory files ([ConversationIdStoreFixture.RecordingPrefs]
 * records each commit's keys); the start pass is called as the init block and reloadWalletScopedStores call it
 * (markLegacyKeysOnce, with the main-process test). Today none of it exists. Every address and key is synthetic.
 */
class LegacyKeyMarkLivesWithTheKeyTest {
    private val keys = ConversationIdStoreFixture.RecordingPrefs()
    private val ids = ConversationIdStoreFixture.RecordingPrefs()
    private val modes = ConversationIdStoreFixture.RecordingPrefs()
    private val store: ZchatPreferencesImpl =
        (unsafeAllocate(ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl).also { impl ->
            set(impl, "e2ePrefs", keys)
            set(impl, "convMappingPrefs", ids)
            set(impl, "modePrefs", modes)
            set(impl, "_e2eHandshakeTicks", MutableStateFlow(0L))
        }

    private fun put2124Keys() {
        // What 2.12.4 left in the key file: contact keys, one address marked verified, our own keys, other entries.
        keys
            .edit()
            .putString("e2e_peer_pub_$A", KEY_A)
            .putString("e2e_peer_pub_$B", KEY_B)
            .putBoolean("e2e_verified_$B", true)
            .putString("e2e_peer_pub_$C", KEY_C)
            .putBoolean("e2e_verified_$C", false)
            .putString("e2e_our_pub_$A", "c3ludGhldGljLW91ci1rZXk")
            .putBoolean("e2e_enabled_$A", true)
            .commit()
        keys.commits.clear()
    }

    private fun startPass(main: Boolean = true): Any? = callDeclared("markLegacyKeysOnce", { main })

    @Test
    fun `(1) the first start marks every contact key with its value in one commit and skips verified addresses`() {
        put2124Keys()

        startPass()

        assertEquals("the first start did not mark A with its key", KEY_A, keys.getString("legacykey:$A", null))
        assertEquals("the first start did not mark C with its key", KEY_C, keys.getString("legacykey:$C", null))
        assertFalse("an address the user marked verified got a mark", keys.contains("legacykey:$B"))
        assertEquals("the marks are not one commit of the key store: ${keys.commits}", 1, keys.commits.size)
        assertEquals(
            "the marks and the done-flag are not one commit",
            setOf("legacykey:$A", "legacykey:$C", "legacykey_done"),
            keys.commits.single()
        )
        assertTrue("the done-flag was not set in the key store", keys.getBoolean("legacykey_done", false))
        assertEquals(
            "the key store holds marks of something that is not a contact key",
            2,
            keys.all.keys.count { it.startsWith("legacykey:") }
        )
        assertEquals("a mark that names the stored key does not stand", KEY_A, call("getLegacyKeyMark", A))
        assertNull(call("getLegacyKeyMark", B))
        assertEquals("the verified flag changed", true, keys.getBoolean("e2e_verified_$B", false))
        assertTrue("the mode store was written", modes.commits.isEmpty())
    }

    @Test
    fun `(1) a start in another process marks nothing and leaves the flag for the main process`() {
        put2124Keys()

        startPass(main = false)

        assertTrue("another process wrote the key store: ${keys.commits}", keys.commits.isEmpty())
        assertFalse("another process set the done-flag", keys.getBoolean("legacykey_done", false))

        startPass()
        assertEquals(KEY_A, keys.getString("legacykey:$A", null))
    }

    @Test
    fun `(2) a key stored after the done-flag and a second start mark nothing, a store brought in without the flag is marked`() {
        put2124Keys()
        startPass()
        keys.commits.clear()

        assertEquals("storePeerKey is missing or did not save", true, call("storePeerKey", D, KEY_D, false, false))
        assertNull("a key stored after the done-flag has a mark", call("getLegacyKeyMark", D))
        startPass()
        assertFalse("a second start marked a key stored after the flag", keys.contains("legacykey:$D"))
        assertEquals("a second start wrote the key store again: ${keys.commits}", 1, keys.commits.size)

        // Another wallet's key store (or a kept copy) put back as 2.12.4 left it: no flag. It is marked at the next pass.
        keys.edit().clear().commit()
        keys
            .edit()
            .putString("e2e_peer_pub_$A", KEY_A)
            .putString("e2e_peer_pub_$C", KEY_C)
            .commit()
        keys.commits.clear()
        startPass()
        assertEquals("a store brought in without the flag was not marked", KEY_A, keys.getString("legacykey:$A", null))
        assertEquals(KEY_C, keys.getString("legacykey:$C", null))
        assertTrue(keys.getBoolean("legacykey_done", false))

        // An empty store (a new wallet, a Reset): the flag only, so nothing stored later is marked by a start.
        keys.edit().clear().commit()
        keys.commits.clear()
        startPass()
        assertEquals("an empty store did not get the flag alone: ${keys.commits}", listOf(setOf("legacykey_done")), keys.commits)
        keys.edit().putString("e2e_peer_pub_$A", KEY_A).commit()
        startPass()
        assertFalse("a key stored after an empty store's flag was marked", keys.contains("legacykey:$A"))
    }

    @Test
    fun `(2) the key store's first complete history read is recorded beside the keys and goes with them`() {
        assertEquals("a store that never read its history says it did", false, call("isLegacyHistoryRead"))
        callUnit("setLegacyHistoryRead")
        assertEquals(true, call("isLegacyHistoryRead"))
        assertTrue("the marker is not in the key store", keys.contains("legacykey_history_read"))
        keys.edit().clear().commit()
        assertEquals("an emptied key store (a Reset, a restore) still says it read its history", false, call("isLegacyHistoryRead"))
    }

    @Test
    fun `(3) the key's removal takes its mark and its probe record, and another key means the mark does not stand`() {
        put2124Keys()
        startPass()
        keys.edit().putString("legacyprobe:$A", "1\nZC72PRB1\n").commit()

        store.clearE2EPeerState(A)
        assertFalse("the key's removal left its mark", keys.contains("legacykey:$A"))
        assertFalse("the key's removal left its probe record", keys.contains("legacyprobe:$A"))

        keys.edit().putString("legacyprobe:$C", "1\nZC72PRB2\n").commit()
        store.clearE2EKeys(C)
        assertFalse("a Reset left the mark", keys.contains("legacykey:$C"))
        assertFalse("a Reset left the probe record", keys.contains("legacyprobe:$C"))

        // storePeerKey writing another key takes the mark away in the same commit; the same key keeps it.
        keys
            .edit()
            .putString("e2e_peer_pub_$C", KEY_C)
            .putString("legacykey:$C", KEY_C)
            .commit()
        assertEquals(true, call("storePeerKey", C, KEY_C, false, true))
        assertEquals("a repeat of the marked key took the mark away", KEY_C, call("getLegacyKeyMark", C))
        keys.commits.clear()
        assertEquals(true, call("storePeerKey", C, OTHER, false, false))
        assertNull("another key kept the mark", call("getLegacyKeyMark", C))
        assertFalse("another key left the mark's entry", keys.contains("legacykey:$C"))
        assertEquals("the replacing key is not stored as a claim (D6: it must not move a relay identity)", OTHER, call("getKeyClaim", C))
        assertTrue(
            "the key, the mark's removal and the claim are not one commit",
            keys.commits.single().containsAll(listOf("e2e_peer_pub_$C", "legacykey:$C", "keyclaim:$C")),
        )

        // A key written by any other path: the mark names the old key and no longer stands.
        keys
            .edit()
            .putString("e2e_peer_pub_$D", KEY_D)
            .putString("legacykey:$D", KEY_D)
            .commit()
        store.setE2EPeerPublicKey(D, OTHER)
        assertNull("a mark naming the old key still stands", call("getLegacyKeyMark", D))
    }

    @Test
    fun `(4) storePeerKey writes a mark with the key in one commit when asked (the restore rule R2)`() {
        assertEquals(true, call("storePeerKey", D, KEY_D, false, false, true))
        assertEquals("the restore rule's mark was not written", KEY_D, call("getLegacyKeyMark", D))
        assertTrue(
            "the key and its mark are not one commit: ${keys.commits}",
            keys.commits.single().containsAll(listOf("e2e_peer_pub_$D", "legacykey:$D")),
        )
        assertEquals(true, call("storePeerKey", D, KEY_D, false, false, false))
        assertEquals("a repeat of the key without the rule took the mark away", KEY_D, call("getLegacyKeyMark", D))
    }

    private fun call(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            ZchatPreferencesImpl::class.java.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("the store has no $name with ${args.size} arguments")
        return try {
            method.invoke(store, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun callUnit(name: String) {
        call(name)
    }

    private fun callDeclared(
        name: String,
        vararg args: Any?,
    ): Any? = callDeclaredOn(store, name, *args)

    private fun callDeclaredOn(
        target: ZchatPreferencesImpl,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            ZchatPreferencesImpl::class.java.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            } ?: throw AssertionError("the store has no $name(() -> Boolean)")
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
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
        const val A = "usyntheticlegacypeer00000001"
        const val B = "usyntheticlegacypeer00000002"
        const val C = "usyntheticlegacypeer00000003"
        const val D = "usyntheticlegacypeer00000004"
        const val KEY_A = "c3ludGhldGljLWxlZ2FjeS1rZXktYQ"
        const val KEY_B = "c3ludGhldGljLWxlZ2FjeS1rZXktYg"
        const val KEY_C = "c3ludGhldGljLWxlZ2FjeS1rZXktYw"
        const val KEY_D = "c3ludGhldGljLWxlZ2FjeS1rZXktZA"
        const val OTHER = "c3ludGhldGljLW90aGVyLWtleQ"
    }
}
