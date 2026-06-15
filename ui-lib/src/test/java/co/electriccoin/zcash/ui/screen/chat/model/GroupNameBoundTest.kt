package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ZMSGGroupProtocol.boundGroupName] — the #195 field bound that caps the
 * user-typed group name embedded in the on-chain GROUP_INVITE memo. The cap must be byte-safe (the
 * memo budget is measured in UTF-8 bytes, not chars) and must never split a multibyte code point,
 * which would corrupt the name on the invitee's side.
 */
class GroupNameBoundTest {

    private val maxBytes = ZMSGGroupProtocol.MAX_GROUP_NAME_BYTES

    @Test
    fun `short ASCII name is returned unchanged`() {
        val name = "Team Zcash"
        assertEquals(name, ZMSGGroupProtocol.boundGroupName(name))
    }

    @Test
    fun `empty name is returned unchanged`() {
        assertEquals("", ZMSGGroupProtocol.boundGroupName(""))
    }

    @Test
    fun `a name exactly at the byte limit is left intact`() {
        val name = "a".repeat(maxBytes)
        assertEquals(maxBytes, name.toByteArray(Charsets.UTF_8).size)
        assertEquals(name, ZMSGGroupProtocol.boundGroupName(name))
    }

    @Test
    fun `an over-long ASCII name is trimmed to within the byte limit`() {
        val name = "x".repeat(maxBytes + 50)
        val bounded = ZMSGGroupProtocol.boundGroupName(name)
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= maxBytes)
        // For pure-ASCII, each char is one byte, so it trims to exactly the limit.
        assertEquals(maxBytes, bounded.length)
    }

    @Test
    fun `a multibyte name is trimmed byte-safely and stays valid UTF-8`() {
        // Each 😀 is 4 UTF-8 bytes; this is well over the byte budget.
        val name = "😀".repeat(maxBytes) // maxBytes * 4 bytes
        val bounded = ZMSGGroupProtocol.boundGroupName(name)
        val bytes = bounded.toByteArray(Charsets.UTF_8)
        assertTrue("bounded name must fit the byte budget", bytes.size <= maxBytes)
        // Round-tripping the bytes must reproduce the string exactly — i.e. no code point was split
        // (a split surrogate/continuation byte would not round-trip cleanly).
        assertEquals(bounded, String(bytes, Charsets.UTF_8))
        // And every retained char must be a whole emoji (4 bytes each), so the byte count is a
        // multiple of 4 and never exceeds the largest multiple of 4 that fits.
        assertEquals(0, bytes.size % 4)
    }

    @Test
    fun `mixed ASCII and multibyte trims without corrupting the trailing code point`() {
        val name = "Group-" + "é".repeat(maxBytes) // 'é' is 2 UTF-8 bytes
        val bounded = ZMSGGroupProtocol.boundGroupName(name)
        val bytes = bounded.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= maxBytes)
        assertEquals(bounded, String(bytes, Charsets.UTF_8))
    }
}
