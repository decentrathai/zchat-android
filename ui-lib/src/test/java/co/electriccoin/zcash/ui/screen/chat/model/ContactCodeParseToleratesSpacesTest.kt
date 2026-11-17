package co.electriccoin.zcash.ui.screen.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * QR-7 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §1.3): a contact code pasted with a space or a line break in it
 * is the same code. The HONOR keyboard added a space after "?", so the key became " z", parse returned null and New
 * Chat said "invalid address". None of z (an address), n (hex) or r (URL-encoded) can hold a whitespace character, so
 * parse drops every one. The address is synthetic: parse checks only its prefix and length.
 */
class ContactCodeParseToleratesSpacesTest {
    private val address = "u1" + "q".repeat(211)
    private val key = "0123456789abcdef".repeat(4)
    private val relay = "wss://relay.zsend.xyz"
    private val clean = "zchat:c1?z=$address&n=$key&r=wss%3A%2F%2Frelay.zsend.xyz"

    private fun assertSameAsClean(
        text: String,
        what: String
    ) {
        val parsed = assertNotNull(ZchatContactCode.parse(text), "$what: the code was refused")
        assertEquals(address, parsed.zcashAddress, "$what: address")
        assertEquals(key, parsed.nostrPubkeyHex, "$what: relay key")
        assertEquals(relay, parsed.relayUrl, "$what: relay")
    }

    @Test
    fun a_space_after_the_question_mark_is_ignored() {
        assertSameAsClean(clean.replace("?z=", "? z="), "a space after ?")
    }

    @Test
    fun a_line_break_before_a_field_is_ignored() {
        assertSameAsClean(clean.replace("&n=", "\n&n="), "a newline before &n=")
        assertSameAsClean(clean.replace("&r=", "\r\n&r="), "a CRLF before &r=")
    }

    @Test
    fun spaces_and_tabs_around_and_inside_the_fields_are_ignored() {
        assertSameAsClean("  $clean \n", "spaces around")
        assertSameAsClean(clean.replace("&n=", " & n = ").replace("&r=", "\t&r="), "spaces around the separators")
    }

    @Test
    fun a_space_inside_the_address_gives_the_address_without_it() {
        val split = clean.replace("z=$address", "z=${address.take(100)} ${address.drop(100)}")
        assertSameAsClean(split, "a space inside the address")
    }

    @Test
    fun the_clean_code_still_parses_the_same() {
        assertSameAsClean(clean, "the clean code")
    }
}
