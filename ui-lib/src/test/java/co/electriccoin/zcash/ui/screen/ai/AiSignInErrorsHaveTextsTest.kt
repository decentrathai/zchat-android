package co.electriccoin.zcash.ui.screen.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import javax.xml.parsers.DocumentBuilderFactory

/** The text of one key of `ui/common/values{,-es}/ai_strings.xml`, with Android's escapes undone. */
internal fun aiSignInText(
    key: String,
    folder: String = "values",
): String {
    val file = AiSignInApi.uiLib.resolve("src/main/res/ui/common/$folder/ai_strings.xml")
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val strings = builder.parse(file).getElementsByTagName("string")
    for (index in 0 until strings.length) {
        val element = strings.item(index) as org.w3c.dom.Element
        if (element.getAttribute("name") == key) {
            return element.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }
    throw AssertionError("$key is not in $folder/ai_strings.xml")
}

/**
 * AI-KEY-S (W-52, W-53; AI-SIGNIN-KEY-SPEC 7.1, the eleven keys marked S; must point 3).
 *
 * WHY THIS FAILS TODAY: none of the eleven keys exists, AiApiClient has no `SIGN_IN_TEXTS` and localizeAiMessage takes
 * no map of them.
 *
 * THE CONTRACT, reached by name so that this class compiles before the fix:
 *   AiApiClient.Companion.getSIGN_IN_TEXTS(): Map<String, String>   resource key to the fixed English sentence, for the
 *       ten keys that are sentences (ai_link_again is a label on a button)
 *   localizeAiMessage(text: String, localized: Map<String, String>): String   swaps each sentence for localized[key]
 */
class AiSignInErrorsHaveTextsTest {
    @Test
    fun `the eleven keys hold the English of spec 7_1 word for word`() {
        ENGLISH.forEach { (key, text) -> assertEquals(key, text, aiSignInText(key)) }
    }

    @Test
    fun `the eleven keys exist in Spanish, translated, without placeholders`() {
        ENGLISH.keys.forEach { key ->
            val english = aiSignInText(key)
            val spanish = aiSignInText(key, "values-es")
            assertTrue("$key is empty in Spanish", spanish.isNotBlank())
            assertNotEquals("$key is not translated", english, spanish)
            listOf(english, spanish).forEach { assertFalse("$key has a placeholder", PLACEHOLDER.containsMatchIn(it)) }
        }
    }

    @Test
    fun `support stands in the parked notice and in no other of the eleven`() {
        ENGLISH.keys.forEach { key ->
            val named = key == PARKED
            assertEquals(key, named, "support" in aiSignInText(key).lowercase())
            assertEquals("$key (es)", named, "soporte" in aiSignInText(key, "values-es").lowercase())
        }
    }

    @Test
    fun `the dropped notice says it could not match and not whose the account is`() {
        val text = aiSignInText(DROPPED)
        assertTrue("could not match to this wallet" in text)
        listOf("another wallet", "someone", "belongs").forEach { assertFalse(it, it in text) }
    }

    @Test
    fun `the parked notice says may hold paid credit and names the way to it`() {
        val text = aiSignInText(PARKED)
        assertTrue("may hold paid credit" in text)
        assertTrue("Look for AI credit from before 2.13" in text)
    }

    @Test
    fun `every fixed English sentence in the client equals its resource word for word`() {
        val sentences = clientSentences()
        assertEquals(ENGLISH.keys - BUTTON_LABEL, sentences.keys)
        sentences.forEach { (key, text) -> assertEquals(key, aiSignInText(key), text) }
    }

    @Test
    fun `localizeAiMessage swaps each sentence, and the composable hands it every key`() {
        val sentences = clientSentences()
        val parameters = listOf(String::class.java, Map::class.java)
        val swap =
            Class
                .forName("$PACKAGE.AiUserMessagesKt")
                .methods
                .firstOrNull { it.name == "localizeAiMessage" && it.parameterTypes.toList() == parameters }
                ?: throw AssertionError("no localizeAiMessage(String, Map)")
        val spanish = sentences.keys.associateWith { aiSignInText(it, "values-es") }
        sentences.forEach { (key, text) ->
            val swapped =
                try {
                    swap.invoke(null, text, spanish)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            assertEquals(key, spanish[key], swapped)
        }
        val source = AiSignInApi.read(AiSignInApi.mainAiFile("AiUserMessages.kt"))
        sentences.keys.forEach { assertTrue("localizedAiMessage does not read $it", "R.string.$it" in source) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun clientSentences(): Map<String, String> {
        val companion = Class.forName("$PACKAGE.AiApiClient\$Companion")
        val getter =
            companion.methods.firstOrNull { it.name == "getSIGN_IN_TEXTS" }
                ?: throw AssertionError("AiApiClient has no SIGN_IN_TEXTS")
        return getter.invoke(AiApiClient.Companion) as Map<String, String>
    }

    private companion object {
        const val PACKAGE = "co.electriccoin.zcash.ui.screen.ai"
        const val PARKED = "ai_notice_key_parked"
        const val DROPPED = "ai_notice_other_wallet_dropped"
        const val BUTTON_LABEL = "ai_link_again"
        val PLACEHOLDER = Regex("%[0-9$]*[a-zA-Z]")
        val ENGLISH =
            mapOf(
                "ai_signin_wallet_not_ready" to "ZCHAT is still opening your wallet. Tap Retry in a moment.",
                "ai_signin_server_not_ready" to "ZCHAT's AI service cannot sign this wallet in yet. Try again later.",
                "ai_signin_too_many" to "Too many AI sign-in attempts from this network. Try again later.",
                "ai_signup_limit" to "ZCHAT cannot open another new AI account from this network right now. Try again later.",
                "ai_signin_refused" to "The AI service did not accept this wallet's sign-in. Try again later.",
                "ai_signin_elsewhere" to "This wallet's AI account was just opened on another phone. Tap Retry to use it here.",
                DROPPED to
                    "The AI account this phone used before is one that ZCHAT could not match to this wallet, and it held no paid " +
                    "credit. ZCHAT opened this wallet's own AI account.",
                PARKED to
                    "This phone holds a key to an AI account that may hold paid credit and that ZCHAT cannot match to this wallet. " +
                    "ZCHAT keeps the key and does not use it. This wallet has its own AI account. If that credit is yours, write to " +
                    "ZCHAT support: after a check of the wallet it belongs to, Try to link opens it. If support had to remove " +
                    "someone else's sign-in key from that account, use Look for AI credit from before 2.13 instead.",
                "ai_notice_new_account_after_refusal" to
                    "This phone's key to its AI account no longer works, and this recovery phrase is not linked to that account. " +
                    "ZCHAT opened a new AI account. Credit of the earlier account is not deleted: it stays on ZCHAT's server.",
                "ai_notice_link_lost" to
                    "This AI account is no longer linked to your recovery phrase. This phone's key still opens it. Link again asks " +
                    "ZCHAT's server to link it.",
                BUTTON_LABEL to "Link again",
            )
    }
}
