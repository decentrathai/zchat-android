package co.electriccoin.zcash.ui.screen.chat.datasource

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-634 part B (FIRST-CONTACT-SPEC revision 4a, 5.5 items 2, 3 and 6; 6.4 case l; finding 3): the claim mark lives in
 * the key store with the key it qualifies. The REAL [ZchatPreferencesImpl] key-store functions over in-memory files (the
 * E2E key file and the conversation-id file; [ConversationIdStoreFixture.RecordingPrefs] records each commit's keys).
 *
 * Today there is no mark and no single write of a key with its mark and asked record (storePeerKey is missing). Wanted:
 * storePeerKey puts the key, puts or removes "keyclaim:", puts the asked record and clears the verified flag when it
 * writes a mark, in one commit; the held ZBOOT is one slot of the entry (at most 1,024 characters); the release removes
 * the mark (and sets the verified flag for the tap) in one commit; the key's removal takes its mark with it; a key store
 * set aside and put back carries its marks; the mode store is never written.
 */
class KeyClaimLivesWithTheKeyTest {
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

    @Test
    fun `(l) the key, its mark and its asked record are one commit, and they leave and come back together`() {
        keys.edit().putBoolean("e2e_verified_$PEER", true).commit()
        keys.commits.clear()

        assertEquals("storePeerKey is missing or did not save", true, call("storePeerKey", PEER, KEY, true, true))

        assertEquals("not one commit: ${keys.commits}", 1, keys.commits.size)
        assertTrue(
            "the commit lacks the key, its mark, its asked record or the verified flag: ${keys.commits}",
            keys.commits.single().containsAll(listOf("e2e_peer_pub_$PEER", "keyclaim:$PEER", "kexasked:$PEER", "e2e_verified_$PEER")),
        )
        assertEquals(KEY, call("getKeyClaim", PEER))
        assertFalse("writing a mark left the verified flag", store.isE2EVerified(PEER))
        assertTrue("the mode store was written", modes.commits.isEmpty())

        // The key store set aside (emptied) and put back from its copy.
        val copy = keys.all
        keys.edit().clear().commit()
        assertNull("after the emptying a mark is left", call("getKeyClaim", PEER))
        val put = keys.edit()
        copy.forEach { (k, v) -> put.putString(k, v as? String) }
        put.commit()
        assertEquals("after the put-back the mark is gone", KEY, call("getKeyClaim", PEER))
        assertEquals(KEY, store.getE2EPeerPublicKey(PEER))

        store.clearE2EPeerState(PEER)
        assertNull("the key's removal left its mark", call("getKeyClaim", PEER))
        assertNull(store.getE2EPeerPublicKey(PEER))
    }

    @Test
    fun `a key stored with no mark removes a mark, and the held ZBOOT is one slot released with the mark`() {
        assertEquals(true, call("storePeerKey", PEER, KEY, true, false))
        assertNull("no asked record was asked for", store.getKexAskedKey(PEER))
        assertEquals(true, call("holdClaimedBoot", PEER, "ZBOOT-one"))
        assertEquals(true, call("holdClaimedBoot", PEER, "ZBOOT-two" + "x".repeat(2_000)))
        val held = call("getHeldClaimBoot", PEER) as String?
        assertNotNull(held)
        assertTrue("the slot keeps the latest only: $held", held!!.startsWith("ZBOOT-two"))
        assertTrue("the slot holds more than 1,024 characters", held.length <= 1_024)
        assertEquals(KEY, call("getKeyClaim", PEER))

        // Review of part B, finding 3: the release hands back the held ZBOOT it removed.
        assertEquals(held, call("releaseKeyClaim", PEER, true))
        assertNull(call("getKeyClaim", PEER))
        assertNull("the release left the held ZBOOT", call("getHeldClaimBoot", PEER))
        assertTrue("the tap's release did not set the verified flag", store.isE2EVerified(PEER))
        assertNull("a release with no mark reports one", call("releaseKeyClaim", PEER, true))
        assertEquals("a ZBOOT is held with no mark", false, call("holdClaimedBoot", PEER, "ZBOOT-three"))

        assertEquals(true, call("storePeerKey", PEER, KEY, true, false))
        assertEquals(true, call("storePeerKey", PEER, OTHER, false, true))
        assertNull("a key stored with no mark kept the old mark", call("getKeyClaim", PEER))
        assertEquals(OTHER, store.getKexAskedKey(PEER))
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
        const val PEER = "usyntheticclaimpeer000000001"
        const val KEY = "c3ludGhldGljLWNsYWltZWQta2V5"
        const val OTHER = "c3ludGhldGljLW90aGVyLWtleQ"
    }
}
