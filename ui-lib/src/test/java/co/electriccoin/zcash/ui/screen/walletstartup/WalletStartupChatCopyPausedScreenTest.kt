package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.R
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B5, Grok Q10 / GPT Q10 / C-2.3 (KEYSTORE-TEMP-TERMINAL-VERDICT): the screen shown when a put-back chat copy could
 * not be unlocked only because the phone's security system did not answer. It must not say the key is gone — that
 * is said only when the phone reports a keyset that does not decrypt — and it must promise, truthfully, that the
 * copy is kept and offered again. Checked through the same seam as the "key is gone" screen
 * ([walletStartupFailedContent]) and in the shipped English and Spanish resource files.
 */
class WalletStartupChatCopyPausedScreenTest {
    private val paused = walletStartupFailedContent(WalletStartupRepair.Offer.ChatCopyRestorePaused)
    private val keyGone = walletStartupFailedContent(WalletStartupRepair.Offer.ChatCopyRestoreFailed)

    @Test
    fun `the paused screen has its own words, not the key is gone screen's`() {
        assertEquals(R.string.wallet_startup_chat_copy_paused_title, paused.title)
        assertEquals(R.string.wallet_startup_chat_copy_paused_message, paused.message)
        assertNotEquals(keyGone.title, paused.title)
        assertNotEquals(keyGone.message, paused.message)
    }

    @Test
    fun `it has one way on, which only says it was read`() {
        assertEquals(
            listOf(WalletStartupButton(R.string.wallet_startup_chat_copy_paused_button, WalletStartupAction.CHAT_COPY_PAUSE_READ)),
            paused.buttons,
        )
        assertEquals(R.string.wallet_startup_chat_copy_failed, paused.actionFailedMessage)
    }

    @Test
    fun `it is written in English and in Spanish`() {
        val english = strings("values")
        val spanish = strings("values-es")
        for (key in PAUSED_KEYS) {
            val en = english[key] ?: fail("error/values/strings.xml has no $key")
            val es = spanish[key] ?: fail("error/values-es/strings.xml has no $key")
            assertTrue(en.isNotBlank(), "$key is blank in English")
            assertTrue(es.isNotBlank(), "$key is blank in Spanish")
        }
        assertNotEquals(english[PAUSED_MESSAGE]?.normalize(), spanish[PAUSED_MESSAGE]?.normalize(), "the Spanish text is the English text")
    }

    @Test
    fun `it never says the key is gone and promises the copy is kept and offered again`() {
        val en = strings("values")[PAUSED_MESSAGE].orEmpty()
        val es = strings("values-es")[PAUSED_MESSAGE].orEmpty()
        assertFalse(en.contains("gone for good", ignoreCase = true), "the English text calls the key gone")
        assertFalse(es.contains("definitivamente", ignoreCase = true), "the Spanish text calls the key gone")
        assertTrue(en.contains("does not mean the key is lost", ignoreCase = true), en)
        assertTrue(en.contains("will offer it again", ignoreCase = true), en)
        assertTrue(en.contains("nothing was deleted", ignoreCase = true), en)
        assertTrue(es.contains("no significa que la clave se haya perdido", ignoreCase = true), es)
        assertTrue(es.contains("volverá a ofrecer", ignoreCase = true), es)
        assertTrue(es.contains("no se borró nada", ignoreCase = true), es)
    }

    private fun String.normalize() = replace(Regex("\\s+"), " ").trim()

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/error/$qualifier/strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        const val PAUSED_MESSAGE = "wallet_startup_chat_copy_paused_message"

        val PAUSED_KEYS =
            listOf(
                "wallet_startup_chat_copy_paused_title",
                PAUSED_MESSAGE,
                "wallet_startup_chat_copy_paused_button",
            )

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/error").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
