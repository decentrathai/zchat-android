package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * SR-A A-9 (fix-all 2.13.0, train T5b; photos-privacy SPEC §1.3 A-9 with R-A1, t5b SPECS §3 C-SRA-5): how many memos the
 * on-chain builder packs a Shielded attachment's link into, by the one rule the price question and the send share
 * ([onChainMemoCountFor]: the E2E1 wire of the ratchet, a reply or the v4 INIT with our address).
 *
 * The line is the one sendAttachmentBlob sends: `ZFILE|<32 hex>|<type>|<size>|<url>|<80-character wrapped key>||[1]`
 * (blurhash empty, view-once optional). Pinned here:
 * - a reply is one memo for every url shape the upload hosts return today (Blossom PUT hosts, the NIP-96 shapes after
 *   ZC-249 — nostr.download's trailing dot, files.sovbit.host — a `<npub>.blossom.band/<64 hex>.bin` of 153 characters
 *   and `image.nostr.build/<64 hex>.jpg`), sizes of 3 to 8 digits, view-once on and off;
 * - the INIT is two memos for each of them, with our address at 178 and at 213 bytes;
 * - the bound: a 177-character url still fits one reply memo with an 8-digit size and view-once; 178 makes two. That is
 *   why the upload must skip a host whose url would change the quoted memo count (A-17).
 * Passes before and after the fix: it pins the quote rule and the bound. All values are synthetic.
 */
class ShieldedAttachmentMemoCountTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val npub = "npub1" + "q".repeat(58)
    private val shapes =
        listOf(
            "https://nostr.download/$sha",
            "https://blossom.yakihonne.com/$sha",
            "https://cdn.hzrd149.com/$sha",
            "https://files.sovbit.host/$sha",
            "https://nostr.download/$sha.",
            "https://$npub.blossom.band/$sha.bin",
            "https://image.nostr.build/$sha.jpg",
        )
    private val sizes = listOf(100L, 4_096L, 99_999L, 1_048_576L, 25_000_000L, 99_999_999L)
    private val wrappedKey = "A".repeat(80)

    private fun line(
        url: String,
        size: Long,
        viewOnce: Boolean,
    ): String = ZFILEMessage(sha.take(32), ZFILEType.JPEG, size, url, wrappedKey, blurhash = "", viewOnce = viewOnce).serialize()

    private fun address(bytes: Int) = "u1" + "q".repeat(bytes - 2)

    @Test
    fun `fixture sanity - the longest host shape is the 153-character blossom band url`() {
        assertEquals(153, shapes.maxOf { it.length })
    }

    @Test
    fun `a reply carries every host shape's link in one memo`() {
        for (url in shapes) {
            for (size in sizes) {
                for (viewOnce in listOf(false, true)) {
                    val text = line(url, size, viewOnce)
                    for (self in listOf(address(178), address(213))) {
                        assertEquals(1, onChainMemoCountFor(text, encrypted = true, isInit = false, self = self), "reply: $url $size")
                    }
                }
            }
        }
    }

    @Test
    fun `the INIT carries every host shape's link in two memos`() {
        for (url in shapes) {
            for (size in sizes) {
                for (viewOnce in listOf(false, true)) {
                    val text = line(url, size, viewOnce)
                    for (self in listOf(address(178), address(213))) {
                        assertEquals(2, onChainMemoCountFor(text, encrypted = true, isInit = true, self = self), "INIT: $url $size")
                    }
                }
            }
        }
    }

    @Test
    fun `a 177-character url fits one reply memo and a 178-character url needs two`() {
        fun url(length: Int) = "https://nostr.download/" + "a".repeat(length - "https://nostr.download/".length)
        val self = address(213)
        assertEquals(1, onChainMemoCountFor(line(url(177), 99_999_999L, true), encrypted = true, isInit = false, self = self))
        assertEquals(2, onChainMemoCountFor(line(url(178), 99_999_999L, true), encrypted = true, isInit = false, self = self))
    }
}
