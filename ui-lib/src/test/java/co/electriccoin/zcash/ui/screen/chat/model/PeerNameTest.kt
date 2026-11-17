package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GROUP-LABEL — PeerName, the one resolver the group screens use: nickname, then the saved contact name, then the
 * roster snapshot, else the short address; both sides looked up by the canonical address. Synthetic addresses only.
 */
class PeerNameTest {
    private val live = "usyntheticpeernamelive000000000000000001"
    private val invited = "usyntheticpeernameinvited000000000000001"
    private val aliases = mapOf(invited to live)
    private val canonical: (String) -> String = { a -> (aliases[a.trim().lowercase()] ?: a.trim().lowercase()) }

    private fun keyed(vararg entries: Pair<String, String>) = PeerName.keyed(entries.toList(), canonical)

    @Test
    fun `nickname comes before the contact name, which comes before the roster snapshot`() {
        val nick = keyed(live to "Ali")
        val book = keyed(live to "Alice")
        assertEquals("Ali", PeerName.name(live, nick, book, canonical, "Old"))
        assertEquals("Alice", PeerName.name(live, emptyMap(), book, canonical, "Old"))
        assertEquals("Old", PeerName.name(live, emptyMap(), emptyMap(), canonical, "Old"))
        assertNull(PeerName.name(live, emptyMap(), emptyMap(), canonical, null))
    }

    @Test
    fun `a name saved under the invited address names the live address, and the other way round`() {
        assertEquals("Alice", PeerName.name(live, emptyMap(), keyed(invited to "Alice"), canonical))
        assertEquals("Ali", PeerName.name(live, keyed(invited to "Ali"), keyed(invited to "Alice"), canonical))
        assertEquals("Alice", PeerName.name(invited, emptyMap(), keyed(live to "Alice"), canonical))
        assertEquals("Alice", PeerName.name(live.uppercase(), emptyMap(), keyed(" $live " to "Alice"), canonical))
    }

    @Test
    fun `an entry under the canonical address itself wins over one under an alias`() {
        assertEquals("Live", PeerName.name(live, emptyMap(), keyed(invited to "Invited", live to "Live"), canonical))
        assertEquals("Live", PeerName.name(live, emptyMap(), keyed(live to "Live", invited to "Invited"), canonical))
    }

    @Test
    fun `blank names count as none`() {
        assertEquals("Alice", PeerName.name(live, keyed(live to "  "), keyed(live to "Alice"), canonical))
        assertEquals("Old", PeerName.name(live, keyed(live to ""), keyed(live to " "), canonical, "Old"))
        assertNull(PeerName.name(live, keyed(live to ""), keyed(live to " "), canonical, "  "))
        assertEquals(PeerName.shortAddress(live), PeerName.label(live, emptyMap(), emptyMap(), canonical, " "))
    }

    @Test
    fun `the short address is the member list's and getDisplayName's, and a short address stays whole`() {
        assertEquals(GroupMember(address = live).displayName, PeerName.shortAddress(live))
        assertEquals("${live.take(8)}...${live.takeLast(6)}", PeerName.shortAddress(live))
        val twentyOne = "u".repeat(21)
        assertEquals("uuuuuuuu...uuuuuu", PeerName.shortAddress(twentyOne))
        val twenty = "u1short0000000000020"
        assertEquals(twenty, PeerName.shortAddress(twenty))
        assertEquals("", PeerName.shortAddress(""))
    }

    @Test
    fun `label is the name, else the short address`() {
        assertEquals("Alice", PeerName.label(live, emptyMap(), keyed(live to "Alice"), canonical))
        assertEquals(PeerName.shortAddress(live), PeerName.label(live, emptyMap(), emptyMap(), canonical))
    }
}
