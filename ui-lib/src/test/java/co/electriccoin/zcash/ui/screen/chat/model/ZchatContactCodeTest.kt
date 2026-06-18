package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZchatContactCodeTest {
    private val ua = "u1mocklongunifiedaddressvalueforzchatcontactcodetestxxxxxxxxxxxx"
    private val npub = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" // 64 hex
    private val relay = "wss://relay.zsend.xyz"

    @Test
    fun `bare unified address parses, no open`() {
        val c = ZchatContactCode.parse(ua)!!
        assertEquals(ua, c.zcashAddress)
        assertNull(c.nostrPubkeyHex)
        assertFalse(c.supportsOpen)
    }

    @Test
    fun `full code parses all fields and supports open`() {
        val c = ZchatContactCode.parse("zchat:c1?z=$ua&n=$npub&r=wss%3A%2F%2Frelay.zsend.xyz")!!
        assertEquals(ua, c.zcashAddress)
        assertEquals(npub, c.nostrPubkeyHex)
        assertEquals(relay, c.relayUrl)
        assertTrue(c.supportsOpen)
    }

    @Test
    fun `code without nostr key does not support open`() {
        val c = ZchatContactCode.parse("zchat:c1?z=$ua")!!
        assertEquals(ua, c.zcashAddress)
        assertFalse(c.supportsOpen)
    }

    @Test
    fun `zcash uri yields address only`() {
        val c = ZchatContactCode.parse("zcash:$ua?amount=0.1")!!
        assertEquals(ua, c.zcashAddress)
        assertFalse(c.supportsOpen)
    }

    @Test
    fun `serialize then parse round-trips`() {
        val original = ZchatContactCode(ua, npub, relay)
        val round = ZchatContactCode.parse(original.serialize())!!
        assertEquals(original.zcashAddress, round.zcashAddress)
        assertEquals(original.nostrPubkeyHex, round.nostrPubkeyHex)
        assertEquals(original.relayUrl, round.relayUrl)
        assertTrue(round.supportsOpen)
    }

    @Test
    fun `bad nostr hex is dropped, address still resolves`() {
        val c = ZchatContactCode.parse("zchat:c1?z=$ua&n=nothex&r=$relay")!!
        assertEquals(ua, c.zcashAddress)
        assertNull(c.nostrPubkeyHex) // not 64-hex → dropped
        assertFalse(c.supportsOpen)
    }

    @Test
    fun `garbage and empty return null`() {
        assertNull(ZchatContactCode.parse(null))
        assertNull(ZchatContactCode.parse(""))
        assertNull(ZchatContactCode.parse("hello world"))
        assertNull(ZchatContactCode.parse("zchat:c1?z=notanaddress"))
    }

    @Test
    fun `non-wss relay is rejected`() {
        val c = ZchatContactCode.parse("zchat:c1?z=$ua&n=$npub&r=http%3A%2F%2Fevil")!!
        assertNull(c.relayUrl)
        assertFalse(c.supportsOpen) // needs a valid relay too
    }
}
