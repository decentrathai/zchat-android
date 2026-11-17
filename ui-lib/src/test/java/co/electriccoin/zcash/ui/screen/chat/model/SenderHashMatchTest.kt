package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 8 P3 (PHONE_TRIAGE F1, item 2) — the hash-only routing tier routes a ZREQ| or ZTL| memo only when its sender
 * hash names exactly one known contact, and never guesses between two.
 *
 * A collision of the real 8-byte hash cannot be built, so the collision control injects the hash function. The
 * integration side (the real router) is ShieldedPaymentRequestRoutesToItsContactTest. All addresses are synthetic.
 */
class SenderHashMatchTest {
    private companion object {
        const val PEER = "u1syntheticsenderhashmatchpeer000000000001"
        const val OTHER = "u1syntheticsenderhashmatchother00000000002"
    }

    @Test
    fun `two contacts under the same hash are never guessed between`() {
        val collidingHash = "0123456789abcdef"
        assertNull(SenderHashMatch.unique(collidingHash, listOf(PEER, OTHER)) { listOf(collidingHash) })
    }

    @Test
    fun `one contact found twice (a chat and the contact book) is one match`() {
        assertEquals(PEER, SenderHashMatch.unique(ZMSGProtocol.generateAddressHash(PEER), listOf(PEER, OTHER, PEER)))
    }

    @Test
    fun `the legacy 6-byte hash also names its contact`() {
        assertEquals(PEER, SenderHashMatch.unique(ZMSGProtocol.generateLegacyAddressHash(PEER), listOf(PEER, OTHER)))
    }

    @Test
    fun `a hash that names nobody matches nothing`() {
        assertNull(SenderHashMatch.unique(ZMSGProtocol.generateAddressHash("u1synthetic-stranger"), listOf(PEER, OTHER)))
        assertNull(SenderHashMatch.unique(ZMSGProtocol.generateAddressHash(PEER), emptyList()))
    }
}
