package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.R
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R5-3, round-4 report section 5C — "the new 'the key is gone' start-up screen has no test and has never been
 * on a phone".
 *
 * That screen is the last thing a user sees about chats ZCHAT cannot open any more, so what it says and what
 * its one button does are checked here: the screen is a composable and this module has no Robolectric, but the
 * seam ([walletStartupFailedContent]) holds exactly the text and buttons the composable renders, and the
 * sentences themselves are read out of the shipped resource files in English and Spanish.
 */
class WalletStartupKeyGoneScreenTest {
    private val keyGone = walletStartupFailedContent(WalletStartupRepair.Offer.ChatCopyRestoreFailed)

    @Test
    fun `the key is gone screen says so in its own words, not the restore offer's`() {
        assertEquals(R.string.wallet_startup_chat_copy_gone_title, keyGone.title)
        assertEquals(R.string.wallet_startup_chat_copy_gone_message, keyGone.message)

        val restoreOffer = walletStartupFailedContent(WalletStartupRepair.Offer.RestoreChatCopy)
        assertNotEquals(restoreOffer.title, keyGone.title, "it shows the restore offer's title")
        assertNotEquals(restoreOffer.message, keyGone.message, "it shows the restore offer's explanation")
    }

    @Test
    fun `it has exactly one way on, and that way never restores or retries`() {
        assertEquals(
            listOf(WalletStartupButton(R.string.wallet_startup_chat_copy_gone_button, WalletStartupAction.CHAT_COPY_FAILURE_READ)),
            keyGone.buttons,
            "the screen that ends the restore must offer one button that only says it was read",
        )
    }

    @Test
    fun `when that button changes nothing the screen says so`() {
        assertEquals(R.string.wallet_startup_chat_copy_failed, keyGone.actionFailedMessage)
    }

    // Pin: the offer that still has a way back keeps both buttons, restore first.
    @Test
    fun `the restore offer still offers restore and continue, in that order`() {
        val restoreOffer = walletStartupFailedContent(WalletStartupRepair.Offer.RestoreChatCopy)
        assertEquals(
            listOf(
                WalletStartupButton(R.string.wallet_startup_chat_copy_restore, WalletStartupAction.RESTORE_CHAT_COPY),
                WalletStartupButton(R.string.wallet_startup_chat_copy_continue, WalletStartupAction.CONTINUE_WITHOUT_CHAT_COPY),
            ),
            restoreOffer.buttons,
        )
    }

    // Pin: every start-up failure has its own screen, and none of them is a dead end.
    @Test
    fun `every start-up failure has its own title, an explanation and a way on`() {
        val offers =
            listOf(
                WalletStartupRepair.Offer.SafetyScreen,
                WalletStartupRepair.Offer.SetAsideAndRescan,
                WalletStartupRepair.Offer.SeedStoreUnreadable,
                WalletStartupRepair.Offer.ChatStoresUnavailable,
                WalletStartupRepair.Offer.RestoreChatCopy,
                WalletStartupRepair.Offer.ChatCopyRestoreFailed,
            )
        offers.forEach { offer ->
            val content = walletStartupFailedContent(offer)
            assertNotEquals(0, content.title, "$offer has no title")
            assertNotEquals(0, content.message, "$offer has no explanation")
            assertTrue(content.buttons.isNotEmpty(), "$offer leaves the user with no button")
        }
        assertEquals(offers.size, offers.map { walletStartupFailedContent(it).title }.toSet().size, "two failures share a title")
    }

    @Test
    fun `the key is gone screen is written in English and in Spanish`() {
        val english = strings("values")
        val spanish = strings("values-es")
        for (key in KEY_GONE_KEYS) {
            val en = english[key] ?: fail("error/values/strings.xml has no $key")
            val es = spanish[key] ?: fail("error/values-es/strings.xml has no $key")
            assertTrue(en.isNotBlank(), "$key is blank in English")
            assertTrue(es.isNotBlank(), "$key is blank in Spanish")
        }
        assertNotEquals(
            english[GONE_MESSAGE]?.normalize(),
            spanish[GONE_MESSAGE]?.normalize(),
            "the Spanish explanation is the English text",
        )
    }

    @Test
    fun `it promises in both languages that nothing was deleted`() {
        assertTrue(
            strings("values")[GONE_MESSAGE].orEmpty().contains("nothing was deleted", ignoreCase = true),
            "the English text no longer tells the user that the copy is still on the phone",
        )
        assertTrue(
            strings("values-es")[GONE_MESSAGE].orEmpty().contains("no se borró nada", ignoreCase = true),
            "the Spanish text no longer tells the user that the copy is still on the phone",
        )
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
        const val GONE_MESSAGE = "wallet_startup_chat_copy_gone_message"

        val KEY_GONE_KEYS =
            listOf(
                "wallet_startup_chat_copy_gone_title",
                GONE_MESSAGE,
                "wallet_startup_chat_copy_gone_button",
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
