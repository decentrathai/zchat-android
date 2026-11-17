package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA2 Q2-82 (ZC-1679, W-137): "ZCHAT_CONVID Inconsistent mapping detected" was logged at warning level 24 times on the
 * Seeker during calls, on the normal state of a chat.
 *
 * A chat has two conversation ids by design: the one this phone made for sending (`peer:<address>` = our id) and the one
 * the contact made (`conv:<theirId>` = the address); setConversationMapping writes only the conv-to-peer direction for
 * the second and never touches `peer:`. So reading the contact's id finds `peer:<address>` naming a different id, and
 * getPeerByConversationId warned "Inconsistent mapping" for every such read. That is no inconsistency and the line
 * made the log look broken while nothing was.
 *
 * Wanted: a warning only for a real conv-to-peer conflict, i.e. the id the address calls its own belongs to a different
 * address (`conv:<ownId>` names another peer). The normal two-id state logs no warning (debug level is fine). The read
 * still repairs nothing and still returns the address.
 *
 * A [ZchatPreferencesImpl] with no Context (the QsClaimStoreTest idiom) over an in-memory id file; the capturing
 * android.util.Log of this source set records the warnings. Every id and address is synthetic.
 */
class ConvIdWarningOnlyOnARealConflictTest {
    private companion object {
        const val PEER = "usyntheticq282peer00000001"
        const val OTHER = "usyntheticq282peer00000002"
        const val THEIR_ID = "TH3IR1D1"
        const val OWN_ID = "0WNS3ND1"
    }

    @BeforeTest
    fun startCapture() = android.util.Log.startCapture()

    @AfterTest
    fun stopCapture() = android.util.Log.stopCapture()

    private class IdFile(
        initial: Map<String, String>,
    ) {
        val values: MutableMap<String, Any?> = Collections.synchronizedMap(LinkedHashMap<String, Any?>(initial))
        val prefs: SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "contains" -> values.containsKey(args!![0] as String)
                    "getAll" -> synchronized(values) { LinkedHashMap(values) }
                    "getString" -> if (values.containsKey(args!![0] as String)) values[args[0] as String] else args[1]
                    else -> error("a read must only read: ${method.name}")
                }
            } as SharedPreferences
    }

    private fun store(file: IdFile): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        ZchatPreferencesImpl::class.java
            .getDeclaredField("convMappingPrefs")
            .apply { isAccessible = true }
            .set(impl, file.prefs)
        return impl
    }

    private fun inconsistencyWarnings() =
        android.util.Log
            .capturedWarnings()
            .filter { it.contains("Inconsistent mapping") }

    @Test
    fun `reading the contact's own id while the address has a different send id logs no warning`() {
        // conv:<theirs> = address, conv:<ours> = address, peer:<address> = ours: two ids, no conflict.
        val file =
            IdFile(
                mapOf(
                    "conv:$THEIR_ID" to PEER,
                    "conv:$OWN_ID" to PEER,
                    "peer:$PEER" to OWN_ID,
                ),
            )

        val result = store(file).getPeerByConversationId(THEIR_ID)

        assertEquals(PEER, result, "the read must still return the address")
        assertEquals(
            emptyList(),
            inconsistencyWarnings(),
            "the normal two-id state of a chat was logged as an inconsistent mapping at warning level",
        )
    }

    @Test
    fun `reading the id the address calls its own logs no warning (guard)`() {
        val file = IdFile(mapOf("conv:$OWN_ID" to PEER, "peer:$PEER" to OWN_ID))

        assertEquals(PEER, store(file).getPeerByConversationId(OWN_ID))
        assertEquals(emptyList(), inconsistencyWarnings())
    }

    @Test
    fun `a real conflict, the address's own id belonging to another address, still warns and repairs nothing (guard)`() {
        // peer:<address> names an id that conv: files under another address: that is a conv-to-peer conflict.
        val file =
            IdFile(
                mapOf(
                    "conv:$THEIR_ID" to PEER,
                    "conv:$OWN_ID" to OTHER,
                    "peer:$PEER" to OWN_ID,
                ),
            )
        val before = LinkedHashMap(file.values)

        val result = store(file).getPeerByConversationId(THEIR_ID)

        assertEquals(PEER, result, "the read must return the stored address even when it warns")
        assertTrue(inconsistencyWarnings().isNotEmpty(), "a real conv-to-peer conflict must still be warned about")
        assertEquals(before, LinkedHashMap(file.values), "the read must not repair (the startup repair does)")
    }
}
