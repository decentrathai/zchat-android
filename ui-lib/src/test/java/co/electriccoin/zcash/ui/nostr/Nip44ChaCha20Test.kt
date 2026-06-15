package co.electriccoin.zcash.ui.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ChaCha20 primitive against RFC 8439 Appendix A.1 keystream test vectors.
 *
 * This is the regression guard for the counter-origin bug: NIP-44 v2 starts the ChaCha20
 * block counter at 0, but a prior implementation started at 1. Self-round-trip tests
 * could not catch that (both sides shared the offset); these external RFC vectors can,
 * because they fix the exact keystream bytes the spec requires at counter 0 and 1.
 *
 * RFC 8439 §2.4: ChaCha20 encryption of an all-zero plaintext yields the keystream, so
 * we encrypt zeros and assert the output equals the published keystream.
 */
class Nip44ChaCha20Test {

    private val zeroKey = ByteArray(32)
    private val zeroNonce = ByteArray(12)

    /**
     * RFC 8439 Appendix A.1, Test Vector #1:
     *   Key = 0x00..00 (32 bytes), Nonce = 0x00..00 (12 bytes), Initial Counter = 0.
     * Expected first 64-byte keystream block.
     */
    @Test
    fun `RFC 8439 A1 vector 1 — counter 0 keystream`() {
        val expected = hex(
            "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
                "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586",
        )
        val keystream = Nip44Encryption.chacha20(zeroKey, zeroNonce, ByteArray(64))
        assertEquals(hexStr(expected), hexStr(keystream))
    }

    /**
     * RFC 8439 Appendix A.1, Test Vector #2:
     *   Same zero key/nonce, Initial Counter = 1.
     * Since our chacha20 always starts at counter 0, the SECOND 64-byte block of a
     * 128-byte encryption equals this counter-1 keystream — proving the counter
     * increments correctly from its 0 origin.
     */
    @Test
    fun `RFC 8439 A1 vector 2 — counter 1 is the second block`() {
        val expectedCounter1 = hex(
            "9f07e7be5551387a98ba977c732d080dcb0f29a048e3656912c6533e32ee7aed" +
                "29b721769ce64e43d57133b074d839d531ed1f28510afb45ace10a1f4b794d6f",
        )
        val twoBlocks = Nip44Encryption.chacha20(zeroKey, zeroNonce, ByteArray(128))
        val secondBlock = twoBlocks.copyOfRange(64, 128)
        assertEquals(hexStr(expectedCounter1), hexStr(secondBlock))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hexStr(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
