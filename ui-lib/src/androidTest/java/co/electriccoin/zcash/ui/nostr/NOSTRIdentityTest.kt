package co.electriccoin.zcash.ui.nostr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class NOSTRIdentityTest {

    private val testSeed = ByteArray(64) { it.toByte() }

    @Test
    fun derive_produces_32_byte_private_key() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertEquals(32, identity.privateKey.size)
    }

    @Test
    fun same_seed_produces_same_keys() {
        val id1 = NOSTRIdentity.fromSeed(testSeed)
        val id2 = NOSTRIdentity.fromSeed(testSeed)
        assertTrue(id1.privateKey.contentEquals(id2.privateKey))
        assertTrue(id1.publicKey.contentEquals(id2.publicKey))
    }

    @Test
    fun different_seeds_produce_different_keys() {
        val seed2 = ByteArray(64) { (it + 100).toByte() }
        val id1 = NOSTRIdentity.fromSeed(testSeed)
        val id2 = NOSTRIdentity.fromSeed(seed2)
        assert(!id1.privateKey.contentEquals(id2.privateKey))
    }

    @Test
    fun npub_starts_with_npub1() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        assertTrue(identity.npub.startsWith("npub1"), "npub should start with npub1 but was: ${identity.npub}")
    }

    @Test
    fun signNIP98Event_produces_nonempty_base64() {
        val identity = NOSTRIdentity.fromSeed(testSeed)
        val event = identity.signNIP98Event("https://nostr.build/upload", "POST")
        assertNotNull(event)
        assertTrue(event.isNotEmpty())
        // Should be valid base64
        android.util.Base64.decode(event, android.util.Base64.NO_WRAP)
    }
}
