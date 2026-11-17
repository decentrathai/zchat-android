package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-506 Part A (and the size half of register ZC-517) — how big the "I joined" reply (GROUP_ACCEPT) is with the key
 * epoch it answers, in bytes, for every length a mainnet unified address has.
 *
 * The parts: the frame `ZMSG:3.0:GROUP:GA:<group id>:` (48 bytes with a 29-character group id, `zgrp_` + 24 hex), the
 * member's E2E public key (a P-256 key, 124 base64 characters), its signature (at most 96 base64 characters), the
 * address, and the JSON around them (42 bytes): 310 bytes plus the address. The key epoch adds 13 bytes and its digits.
 * The builder writes '/' unescaped, so the size depends only on the lengths of the parts, never on their characters.
 *
 * Pinned:
 * - 69, 106, 141 and 178 characters (178 = Orchard + Sapling): at most 512 bytes for every key epoch, a ten-digit one
 *   included; at 178 characters with Int.MAX_VALUE exactly 511. One byte is left: the day a part grows by two bytes
 *   this test fails, before a reply stops fitting on a phone.
 * - 213 characters (Orchard + Sapling + transparent, the default chat identity): 523 bytes WITHOUT the value and 537 to
 *   546 with it. It is over one memo either way, so it travels only over the relays, and the builder still writes the
 *   value: there is no shorter form (register ZC-517 holds what the paid Join does then).
 *
 * All addresses, keys and group ids are synthetic.
 */
class GroupAcceptKeyEpochAlwaysFitsTest {
    private fun address(length: Int): String = "u1" + "q".repeat(length - 2)

    private fun bytes(memo: String): Int = memo.toByteArray(Charsets.UTF_8).size

    private fun digits(epoch: Int): Int = epoch.toString().length

    @Test
    fun `up to a 178-character address the accept fits one memo with any key epoch, worst-case characters included`() {
        for (length in listOf(69, 106, 141, 178)) {
            for (epoch in EPOCHS) {
                val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(length), SLASH_KEY, SLASH_SIG, keyEpoch = epoch)
                assertTrue("$length characters, key epoch $epoch: the value is not written", memo.endsWith(",\"key_epoch\":$epoch}"))
                assertFalse("$length characters, key epoch $epoch: a slash was escaped", memo.contains("\\/"))
                assertEquals("$length characters, key epoch $epoch", 310 + length + 13 + digits(epoch), bytes(memo))
                assertTrue("$length characters, key epoch $epoch: ${bytes(memo)} bytes", bytes(memo) <= MEMO_BYTES)
            }
        }
        val worst = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(178), SLASH_KEY, SLASH_SIG, keyEpoch = Int.MAX_VALUE)
        assertEquals("the margin at 178 characters and a ten-digit key epoch is one byte", 511, bytes(worst))
    }

    @Test
    fun `the size is the same whatever the characters of the key and the signature`() {
        for (length in listOf(106, 178, 213)) {
            for (epoch in EPOCHS) {
                val slashes = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(length), SLASH_KEY, SLASH_SIG, keyEpoch = epoch)
                val letters = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(length), LETTER_KEY, LETTER_SIG, keyEpoch = epoch)
                assertEquals("$length characters, key epoch $epoch", bytes(letters), bytes(slashes))
            }
        }
    }

    @Test
    fun `at 213 characters the accept is over one memo with and without the key epoch, and the value is still written`() {
        // Without the value (letters only: the legacy form is written by the platform's JSON writer).
        val legacy = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(213), LETTER_KEY, LETTER_SIG)
        assertEquals(523, bytes(legacy))

        for (epoch in EPOCHS) {
            val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(213), SLASH_KEY, SLASH_SIG, keyEpoch = epoch)
            assertTrue("key epoch $epoch: the value was dropped", memo.endsWith(",\"key_epoch\":$epoch}"))
            assertEquals("key epoch $epoch", 536 + digits(epoch), bytes(memo))
            assertTrue(bytes(memo) > MEMO_BYTES)
        }
        val shortest = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(213), SLASH_KEY, SLASH_SIG, keyEpoch = 0)
        val longest = ZMSGGroupProtocol.createGroupAcceptMessage(GID, address(213), SLASH_KEY, SLASH_SIG, keyEpoch = Int.MAX_VALUE)
        assertEquals(537, bytes(shortest))
        assertEquals(546, bytes(longest))
    }

    @Test
    fun `the fixture is the real layout - a 29-character group id, a 124-character key, a 96-character signature`() {
        assertEquals(29, GID.length)
        assertEquals(124, SLASH_KEY.length)
        assertEquals(96, SLASH_SIG.length)
        assertEquals(ZMSGConstants.MAX_MEMO_SIZE, MEMO_BYTES)
    }

    private companion object {
        const val GID = "zgrp_0a1b2c3d4e5f60718293a4b5"
        const val MEMO_BYTES = 512
        val EPOCHS = listOf(0, 1, 99, 99_999, Int.MAX_VALUE)
        val SLASH_KEY = "/".repeat(124)
        val SLASH_SIG = "/".repeat(96)
        val LETTER_KEY = "A".repeat(124)
        val LETTER_SIG = "B".repeat(96)
    }
}
