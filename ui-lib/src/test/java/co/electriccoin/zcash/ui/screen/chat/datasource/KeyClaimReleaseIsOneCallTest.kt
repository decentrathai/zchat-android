package co.electriccoin.zcash.ui.screen.chat.datasource

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-634 (review of part B, findings 3 and 4; design 5.5 item 6 "the same store function that removes the mark returns
 * the held ZBOOT"): at d1c127cb2 the held ZBOOT is read by one call and the mark removed by another, so a ZBOOT held in
 * between is lost; and holding a ZBOOT does not bump the handshake tick, so the held-connection banner shows late.
 *
 * Wanted: releaseKeyClaim returns the held ZBOOT ("" when nothing was held, null when no mark stood) and removes the mark
 * in the same commit, under the key store's lock; holdClaimedBoot bumps the tick. The REAL [ZchatPreferencesImpl] over
 * in-memory files.
 */
class KeyClaimReleaseIsOneCallTest {
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
    fun `releaseKeyClaim hands back the held ZBOOT in the commit that removes the mark`() {
        store.storePeerKey(PEER, KEY, true, false)
        val before = ticks.value
        store.holdClaimedBoot(PEER, "ZBOOT-held")
        assertTrue("holding a ZBOOT did not bump the handshake tick", ticks.value > before)
        keys.commits.clear()

        val released: Any? = store.releaseKeyClaim(PEER, true)

        assertEquals("the release did not hand back the held ZBOOT", "ZBOOT-held", released)
        assertEquals("the release took more than one commit", 1, keys.commits.size)
        assertNull(store.getKeyClaim(PEER))
        assertNull(store.getHeldClaimBoot(PEER))
        assertTrue(store.isE2EVerified(PEER))
    }

    @Test
    fun `a release with nothing held answers empty, and with no mark answers null`() {
        store.storePeerKey(PEER, KEY, true, false)
        val first: Any? = store.releaseKeyClaim(PEER, false)
        val second: Any? = store.releaseKeyClaim(PEER, false)
        assertEquals("", first)
        assertNull(second)
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
        const val PEER = "usyntheticclaimpeer000000002"
        const val KEY = "c3ludGhldGljLWNsYWltZWQta2V5Mg"
    }
}
