package co.electriccoin.zcash.ui.screen.insufficientfunds

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin for M-1 (copy): the wallet PAYMENT and SWAP shortfall sheets open by asserting a fact the app
 * never checked — "Your previous transaction is still confirming".
 *
 * The sheet is shown for every `InsufficientFundsException`, pending or not. On the owner's phone nothing
 * was pending (443,002 Ironwood, 139,000 sealed Orchard, all confirmed), so "please wait" was false and
 * waiting never helped. The copy must not lead with a pending transaction it has not verified, and the
 * Spanish locale must carry the PAYMENT and SWAP strings too (today it falls back to English).
 *
 * Reads the resource files directly: unit tests run with the module directory as the working dir (the
 * lookup walks upward too, as ReleaseHookAbsenceTest does).
 */
class InsufficientFundsCopyTest {
    @Test
    fun paymentAndSwapCopyDoNotClaimPendingTx() {
        val english = strings("values")
        val spanish = strings("values-es")

        PAYMENT_AND_SWAP_KEYS.forEach { key ->
            val text = english[key] ?: fail("values/strings.xml no longer defines $key")
            assertFalse(
                text.trimStart().startsWith(UNVERIFIED_PENDING_CLAIM, ignoreCase = true),
                "$key opens with '$UNVERIFIED_PENDING_CLAIM', but the sheet is shown without checking that " +
                    "anything is pending: \"$text\"",
            )
        }
        PAYMENT_AND_SWAP_KEYS.forEach { key ->
            assertTrue(key in spanish, "values-es/strings.xml must translate $key; defines only ${spanish.keys}")
        }
    }

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/insufficient_funds/$qualifier/strings.xml")
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
        const val UNVERIFIED_PENDING_CLAIM = "Your previous transaction is still confirming"

        val PAYMENT_AND_SWAP_KEYS =
            listOf("insufficient_funds_payment_description", "insufficient_funds_swap_description")

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/insufficient_funds").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
