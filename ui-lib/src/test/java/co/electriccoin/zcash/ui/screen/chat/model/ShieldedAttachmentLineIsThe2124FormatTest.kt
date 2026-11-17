package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SR-A × compat-2124 (fix-all 2.13.0, train T5b; t5b SPECS §3 C-SRA-8): in a Shielded chat the attachment's link now
 * travels on-chain, inside the E2E1 ratchet, as the same `ZFILEMessage.serialize()` line 2.12.4's own on-chain path sent.
 * No new wire element: a 2.12.4 phone that reads the line parses it with its own code.
 *
 * [Frozen2124] is `ZFILEMessage.parse` and its host check copied verbatim from `v2.12.4` (a1fb4136f),
 * `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZFILEMessage.kt` lines 3-12 (the type codes) and
 * 64-115 (the comments left out).
 * Pinned: the line SR-A sends (blurhash empty, view-once optional) parses field for field under 2.12.4 when its host is
 * one 2.12.4 allows, and today's parser reads the same fields; a 2.13-only host (nostr.download) is dropped by 2.12.4
 * (KI-37, disclosed by SR-C1's warning and the release note). A 2.12.4 friend cannot see Ironwood notes until they update
 * and rescan, so this is the format they parse after updating. Passes before and after the fix. Synthetic values.
 */
class ShieldedAttachmentLineIsThe2124FormatTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val wrappedKey = "A".repeat(78) + "=="

    private fun line(
        url: String,
        viewOnce: Boolean,
    ) = ZFILEMessage(sha.take(32), ZFILEType.JPEG, 4_124L, url, wrappedKey, blurhash = "", viewOnce = viewOnce).serialize()

    @Test
    fun `the Shielded link line parses field for field under 2_12_4 on a host it allows`() {
        for (viewOnce in listOf(false, true)) {
            val raw = line("https://blossom.primal.net/$sha", viewOnce)
            val old = assertNotNull(Frozen2124.parse(raw), "2.12.4 cannot parse the Shielded link line: ${raw.take(20)}")
            val now = assertNotNull(ZFILEMessage.parse(raw), "today's parser cannot read its own line")
            assertEquals(now.hash, old.hash)
            assertEquals(now.type.code, old.type)
            assertEquals(now.size, old.size)
            assertEquals(now.url, old.url)
            assertEquals(now.wrappedKey, old.wrappedKey)
            assertEquals(now.blurhash, old.blurhash)
            assertEquals(now.viewOnce, old.viewOnce)
            assertEquals(viewOnce, old.viewOnce)
        }
    }

    @Test
    fun `a 2_13-only host is dropped by 2_12_4 (KI-37, disclosed) and read by 2_13`() {
        val raw = line("https://nostr.download/$sha", viewOnce = false)
        assertNull(Frozen2124.parse(raw), "2.12.4 was expected to drop a nostr.download link (KI-37)")
        assertNotNull(ZFILEMessage.parse(raw), "2.13 cannot read its own nostr.download link")
    }

    /** 2.12.4's parser, copied from v2.12.4 (see the class KDoc); the result keeps the type code as 2.12.4 read it. */
    private object Frozen2124 {
        data class Parsed(
            val hash: String,
            val type: String,
            val size: Long,
            val url: String,
            val wrappedKey: String,
            val blurhash: String,
            val viewOnce: Boolean,
        )

        private const val HASH_LEN = 32
        private const val MAX_URL_LEN = 512
        private val ALLOWED_MEDIA_HOSTS =
            setOf("blossom.primal.net", "blossom.band", "blossom.nostr.build", "nostr.build")
        private val TYPE_CODES = setOf("j", "p", "g", "w", "d", "z", "t", "a")

        private fun isAllowedMediaUrl(url: String): Boolean {
            if (url.length !in 1..MAX_URL_LEN) return false
            val uri =
                try {
                    java.net.URI(url)
                } catch (e: Exception) {
                    return false
                }
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (uri.port != -1 && uri.port != 443) return false
            val host = uri.host?.lowercase() ?: return false
            return ALLOWED_MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        fun parse(raw: String): Parsed? {
            if (!raw.startsWith("ZFILE|")) return null
            val parts = raw.removePrefix("ZFILE|").split("|")
            if (parts.size < 6) return null
            val hash = parts[0]
            if (hash.length != HASH_LEN || !hash.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val fileType = parts[1].takeIf { it in TYPE_CODES } ?: return null
            val fileSize = parts[2].toLongOrNull() ?: return null
            if (!isAllowedMediaUrl(parts[3])) return null
            return Parsed(
                hash = hash,
                type = fileType,
                size = fileSize,
                url = parts[3],
                wrappedKey = parts[4],
                blurhash = parts.getOrElse(5) { "" },
                viewOnce = parts.getOrElse(6) { "0" } == "1",
            )
        }
    }
}
