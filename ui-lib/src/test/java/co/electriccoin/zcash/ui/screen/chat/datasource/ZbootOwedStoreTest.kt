package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-534 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §2.3 part 3) — the durable "our quoted ZBOOT is still owed"
 * mark: one "zbootowed:<peer>" entry in the conversation-mode file, beside the allowance "zbootallowance:<peer>". It is
 * written where a ZBOOT attempt provably sent nothing and the allowance was put back, so the next process can pay the
 * ZBOOT its confirmed question quoted.
 *
 * Must hold: the mark is written, read back and listed; it is never listed for a contact that only has an allowance;
 * withdrawing or consuming the allowance (setZbootQuoted false) removes it, so it never outlives its allowance; clearAll
 * wipes it; a write that does not reach the disk reports false. Uses the real ZchatPreferencesImpl over in-memory files
 * ([realPreferences]). The API is named by reflection, so this file compiles before it exists.
 */
class ZbootOwedStoreTest {
    @Test
    fun `the mark is written, read back, listed and cleared`() {
        val (impl, _) = realPreferences()

        assertFalse("no mark means not owed", isOwed(impl, PEER))
        assertEquals(emptySet<String>(), owedPeers(impl))
        assertTrue(setOwed(impl, PEER, true))
        assertTrue(isOwed(impl, PEER))
        assertFalse(isOwed(impl, OTHER))
        assertEquals(setOf(PEER), owedPeers(impl))
        assertTrue(setOwed(impl, PEER, false))
        assertFalse(isOwed(impl, PEER))
        assertEquals(emptySet<String>(), owedPeers(impl))
    }

    @Test
    fun `an allowance alone is never listed as owed`() {
        val (impl, _) = realPreferences()

        assertTrue(impl.setZbootQuoted(OTHER, true))
        assertTrue(impl.setZbootQuoted(PEER, true))
        assertTrue(setOwed(impl, PEER, true))

        assertEquals("only the marks are owed contacts", setOf(PEER), owedPeers(impl))
        assertFalse(isOwed(impl, OTHER))
    }

    @Test
    fun `the mark never outlives its allowance`() {
        val (impl, files) = realPreferences()

        assertTrue(impl.setZbootQuoted(PEER, true))
        assertTrue(setOwed(impl, PEER, true))
        assertTrue(impl.setZbootQuoted(OTHER, true))
        assertTrue(setOwed(impl, OTHER, true))
        // The stamp of a paying ZBOOT, a relay delivery or a withdrawn quote: the allowance goes.
        assertTrue(impl.setZbootQuoted(PEER, false))

        assertFalse("the owed mark outlived its allowance", isOwed(impl, PEER))
        assertEquals("another contact's mark must stay", setOf(OTHER), owedPeers(impl))
        val left =
            files
                .getValue("modePrefs")
                .all
                .keys
                .filter { it.endsWith(PEER) }
        assertTrue("nothing of the quoted ZBOOT may stay: $left", left.isEmpty())
    }

    @Test
    fun `writing the allowance again keeps the mark`() {
        val (impl, _) = realPreferences()

        assertTrue(impl.setZbootQuoted(PEER, true))
        assertTrue(setOwed(impl, PEER, true))
        assertTrue(impl.setZbootQuoted(PEER, true))

        assertTrue(isOwed(impl, PEER))
        assertTrue(impl.isZbootQuoted(PEER))
    }

    @Test
    fun `clearAll wipes the mark`() {
        val (impl, _) = realPreferences()

        assertTrue(impl.setZbootQuoted(PEER, true))
        assertTrue(setOwed(impl, PEER, true))
        impl.clearAll()

        assertFalse(isOwed(impl, PEER))
        assertTrue(owedPeers(impl).isEmpty())
        assertFalse(impl.isZbootQuoted(PEER))
    }

    @Test
    fun `a mark that does not reach the disk reports false`() {
        val (impl, files) = realPreferences()
        val field = ZchatPreferencesImpl::class.java.getDeclaredField("modePrefs").apply { isAccessible = true }
        field.set(impl, refusingCommits(files.getValue("modePrefs")))

        assertFalse("a failed commit must report false", setOwed(impl, PEER, true))
        assertFalse(isOwed(impl, PEER))
        assertTrue(owedPeers(impl).isEmpty())
    }

    private fun isOwed(
        impl: ZchatPreferencesImpl,
        peer: String,
    ): Boolean = call(impl, "isZbootOwed", arrayOf(String::class.java), peer) as Boolean

    private fun setOwed(
        impl: ZchatPreferencesImpl,
        peer: String,
        owed: Boolean,
    ): Boolean = call(impl, "setZbootOwed", arrayOf(String::class.java, java.lang.Boolean.TYPE), peer, owed) as Boolean

    @Suppress("UNCHECKED_CAST")
    private fun owedPeers(impl: ZchatPreferencesImpl): Set<String> = call(impl, "getZbootOwedPeers", emptyArray()) as Set<String>

    private fun call(
        impl: ZchatPreferencesImpl,
        name: String,
        types: Array<Class<*>>,
        vararg args: Any,
    ): Any? =
        try {
            ZchatPreferencesImpl::class.java.getMethod(name, *types).invoke(impl, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /** [delegate] whose editors never reach the disk: every commit reports false and changes nothing. */
    private fun refusingCommits(delegate: SharedPreferences): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "edit") refusingEditor() else method.invoke(delegate, *(args ?: emptyArray()))
        } as SharedPreferences

    private fun refusingEditor(): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, _ ->
            when (method.name) {
                "commit" -> false
                "apply" -> Unit
                else -> proxy
            }
        } as SharedPreferences.Editor

    private companion object {
        const val PEER = "usyntheticzc534owedstore0001"
        const val OTHER = "usyntheticzc534owedstore0002"
    }
}
