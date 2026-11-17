package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4c fixup, QR7-2 (train T4c cross review, REVIEW_CROSS.md; register ZC-307): a text that holds two contact codes is
 * not a contact.
 *
 * At 6f0678116 [ZchatContactCode.parse] reads the query after the first `?`, splits it on `&` and keeps the last value
 * of each key. Two codes pasted together (with a line break or a space between them, both dropped, or with nothing)
 * give the address of the first code and the relay key and relay of the second. New Chat then stores that key for the
 * first address (when it is not bound to anyone yet), and that chat's free relay messages are sealed to the second
 * person's key: they go to the wrong person.
 *
 * Wanted: parse returns null for a text with a second `zchat:` or `zcash:` prefix or a repeated `z`, `n` or `r` key.
 * One code, with or without a key, still parses as before. Addresses and keys are synthetic.
 */
class TwoContactCodesPastedTogetherAreRefusedTest {
    private val a = "u1" + "a".repeat(211)
    private val b = "u1" + "b".repeat(211)
    private val keyA = "0a".repeat(32)
    private val keyB = "0b".repeat(32)
    private val relay = "wss%3A%2F%2Frelay.zsend.xyz"

    private fun code(
        address: String,
        key: String? = null
    ): String = "zchat:c1?z=$address" + (key?.let { "&n=$it&r=$relay" } ?: "")

    private fun describe(c: ZchatContactCode?): String =
        when {
            c == null -> "refused"
            else ->
                "parsed: address " + (
                    if (c.zcashAddress == a) {
                        "A"
                    } else if (c.zcashAddress == b) {
                        "B"
                    } else {
                        "other"
                    }
                ) +
                    ", key " +
                    (
                        if (c.nostrPubkeyHex ==
                            keyA
                        ) {
                            "A"
                        } else if (c.nostrPubkeyHex == keyB) {
                            "B"
                        } else {
                            c.nostrPubkeyHex?.let { "other" } ?: "none"
                        }
                    )
        }

    @Test
    fun `a code with no key followed by another person's code is refused`() {
        for ((name, text) in joins(code(a), code(b, keyB))) {
            assertTrue("$name: ${describe(ZchatContactCode.parse(text))}", ZchatContactCode.parse(text) == null)
        }
    }

    @Test
    fun `two full codes pasted together are refused`() {
        for ((name, text) in joins(code(a, keyA), code(b, keyB))) {
            assertTrue("$name: ${describe(ZchatContactCode.parse(text))}", ZchatContactCode.parse(text) == null)
        }
    }

    @Test
    fun `a code followed by a zcash uri or a zcash uri followed by a code is refused`() {
        for ((name, text) in joins(code(a, keyA), "zcash:$b") + joins("zcash:$a", code(b, keyB))) {
            assertTrue("$name: ${describe(ZchatContactCode.parse(text))}", ZchatContactCode.parse(text) == null)
        }
    }

    @Test
    fun `a repeated key is refused`() {
        val texts =
            listOf(
                "second n" to "zchat:c1?z=$a&n=$keyA&r=$relay&n=$keyB",
                "second z" to "zchat:c1?z=$a&n=$keyA&z=$b",
                "second r" to "zchat:c1?z=$a&n=$keyA&r=$relay&r=wss%3A%2F%2Fother.invalid",
            )
        for ((name, text) in texts) {
            assertTrue("$name: ${describe(ZchatContactCode.parse(text))}", ZchatContactCode.parse(text) == null)
        }
    }

    /** Guard, GREEN before and after: one code, a zcash URI and a bare address still parse as before. */
    @Test
    fun `guard - one code, one zcash uri and one address still parse`() {
        val full = ZchatContactCode.parse(code(a, keyA))
        assertNotNull(full)
        assertEquals(a, full!!.zcashAddress)
        assertEquals(keyA, full.nostrPubkeyHex)
        assertEquals("wss://relay.zsend.xyz", full.relayUrl)
        assertEquals(a, ZchatContactCode.parse(code(a))?.zcashAddress)
        assertEquals(a, ZchatContactCode.parse("zcash:$a?amount=0.1")?.zcashAddress)
        assertEquals(a, ZchatContactCode.parse(a)?.zcashAddress)
        assertEquals(
            "a code split by a line break still parses",
            keyA,
            ZchatContactCode.parse("zchat:c1?z=$a\n&n=$keyA&r=$relay")?.nostrPubkeyHex
        )
    }

    /** The ways a paste joins two texts: a line break, a space, or nothing (whitespace is dropped by parse). */
    private fun joins(
        first: String,
        second: String
    ): List<Pair<String, String>> =
        listOf(
            "joined by a line break" to "$first\n$second",
            "joined by a space" to "$first $second",
            "joined with nothing between" to "$first$second",
        )
}
