package co.electriccoin.zcash.ui.screen.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AI-KEY-P (W-52, W-53; AI-SIGNIN-KEY-SPEC 7.1, the twelve keys marked P; must point 3).
 *
 * WHY THIS FAILS TODAY: none of the twelve keys exists, and AiApiClient has no map of the sentences of this batch.
 *
 * THE CONTRACT, by name: AiApiClient.Companion.getOTHER_ACCOUNT_TEXTS(): Map<String, String>, resource key to the fixed
 * English sentence, for the nine sentences AiAccountAccess can answer. It is a map of its own because AI-KEY-S's
 * AiSignInErrorsHaveTextsTest, which stays unedited, pins SIGN_IN_TEXTS to exactly its ten keys.
 */
class AiNoticesHaveTextsTest {
    @Test
    fun `the twelve keys hold the English of spec 7_1 word for word`() {
        ENGLISH.forEach { (key, text) -> assertEquals(key, text, aiSignInText(key)) }
    }

    @Test
    fun `the twelve keys exist in Spanish, translated, without placeholders`() {
        ENGLISH.keys.forEach { key ->
            val english = aiSignInText(key)
            val spanish = aiSignInText(key, ES)
            assertTrue("$key is empty in Spanish", spanish.isNotBlank())
            assertNotEquals("$key is not translated", english, spanish)
            listOf(english, spanish).forEach { assertFalse("$key has a placeholder", PLACEHOLDER.containsMatchIn(it)) }
        }
    }

    /** Must point 3: support is named only where no recovery phrase can open the account. */
    @Test
    fun `support stands in exactly four strings of the file, in both languages`() {
        assertEquals(NAMES_SUPPORT, allStrings(EN).filterValues { "support" in it.lowercase() }.keys)
        assertEquals(NAMES_SUPPORT, allStrings(ES).filterValues { "soporte" in it.lowercase() }.keys)
    }

    @Test
    fun `the note says a code made from the chat address goes together with the AI sign-in key`() {
        val english = aiSignInText(NOTE)
        listOf("code made from this wallet's chat address", "together with", "AI sign-in key").forEach {
            assertTrue(it, it in english)
        }
        val spanish = aiSignInText(NOTE, ES)
        listOf("código creado a partir de la dirección de chat", "junto con", "clave de inicio de sesión de IA").forEach {
            assertTrue(it, it in spanish)
        }
    }

    @Test
    fun `the nothing-found sentence ends with what the server can open for this wallet`() {
        assertTrue(aiSignInText(NONE).endsWith("that it can open for this wallet."))
    }

    @Test
    fun `the two sentences about an account the phrase cannot open say may hold paid credit`() {
        listOf(TWO_PAID, NEEDS_CHECK).forEach { key ->
            assertTrue(key, "may hold paid credit" in aiSignInText(key))
            assertTrue("$key (es)", "puede tener saldo pagado" in aiSignInText(key, ES))
        }
    }

    @Test
    fun `the sentence about a dead parked key names the way to the credit`() {
        assertTrue("Look for AI credit from before 2.13" in aiSignInText(DEAD))
        assertTrue("Buscar saldo de IA anterior a la 2.13" in aiSignInText(DEAD, ES))
    }

    @Test
    fun `no string of the file says by itself`() {
        val all = allStrings(EN).values + allStrings(ES).values
        all.forEach { text ->
            assertFalse(text, "by itself" in text.lowercase())
            assertFalse(text, "por sí solo" in text.lowercase())
        }
    }

    @Test
    fun `every fixed English sentence in the client equals its resource word for word`() {
        val sentences = clientSentences()
        assertEquals(ENGLISH.keys - NOT_ANSWERS, sentences.keys)
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
        val spanish = sentences.keys.associateWith { aiSignInText(it, ES) }
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

    private fun allStrings(folder: String): Map<String, String> {
        val file = AiSignInApi.uiLib.resolve("src/main/res/ui/common/$folder/ai_strings.xml")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val strings = builder.parse(file).getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val element = strings.item(index) as org.w3c.dom.Element
            element.getAttribute("name") to element.textContent
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun clientSentences(): Map<String, String> {
        val companion = Class.forName("$PACKAGE.AiApiClient\$Companion")
        val getter =
            companion.methods.firstOrNull { it.name == "getOTHER_ACCOUNT_TEXTS" }
                ?: throw AssertionError("AiApiClient has no OTHER_ACCOUNT_TEXTS")
        return getter.invoke(AiApiClient.Companion) as Map<String, String>
    }

    private companion object {
        const val PACKAGE = "co.electriccoin.zcash.ui.screen.ai"
        const val EN = "values"
        const val ES = "values-es"
        const val TWO_PAID = "ai_notice_two_paid_accounts"
        const val DEAD = "ai_notice_parked_key_dead"
        const val LOOK_FOR = "ai_look_for_old_credit"
        const val NOTE = "ai_look_for_old_credit_note"
        const val NONE = "ai_old_credit_none"
        const val NEEDS_CHECK = "ai_old_credit_needs_check"
        const val TWO_FOUND = "ai_old_credit_two_accounts"
        const val TRY_TO_LINK = "ai_try_to_link"
        val PLACEHOLDER = Regex("%[0-9$]*[a-zA-Z]")
        val NOT_ANSWERS = setOf(LOOK_FOR, NOTE, TRY_TO_LINK)
        val NAMES_SUPPORT = setOf(TWO_PAID, NEEDS_CHECK, TWO_FOUND, "ai_notice_key_parked")
        val ENGLISH =
            mapOf(
                "ai_notice_trial_account_left" to
                    "This wallet's recovery phrase already had an AI account. ZCHAT now uses that one; the trial account this " +
                    "phone used before is left.",
                TWO_PAID to
                    "This wallet has two AI accounts that may hold paid credit. This phone uses one of them; your recovery phrase " +
                    "opens the other. ZCHAT support can join them into one after a check that this wallet is yours. Until then " +
                    "keep this phone's app data: its key is the only way to the account it uses.",
                DEAD to
                    "A key this phone kept for another AI account no longer works, so ZCHAT removed it. If that credit is yours, " +
                    "Look for AI credit from before 2.13 is the way to it.",
                "ai_notice_parked_key_opened" to "The AI account this phone kept a key for is now this wallet's AI account.",
                LOOK_FOR to "Look for AI credit from before 2.13",
                NOTE to
                    "This sends ZCHAT's server a code made from this wallet's chat address together with this wallet's AI sign-in " +
                    "key, to find AI credit you had before. Older versions sent the same code when they first used AI.",
                NONE to "ZCHAT's server found no AI credit from before 2.13 that it can open for this wallet.",
                "ai_old_credit_opened" to "ZCHAT found this wallet's earlier AI account and linked it to your recovery phrase.",
                NEEDS_CHECK to
                    "ZCHAT's server has an AI account from before 2.13 for this wallet that may hold paid credit. It is still tied " +
                    "to the phone that used it: open ZCHAT 2.13 or newer on that phone once. If that phone is gone, write to ZCHAT " +
                    "support: after a check that this wallet is yours, this button opens the account.",
                TWO_FOUND to
                    "ZCHAT's server found an earlier AI account for this wallet, and this wallet's current AI account may hold paid " +
                    "credit too. ZCHAT support can join the two after a check that this wallet is yours.",
                TRY_TO_LINK to "Try to link",
                "ai_topup_not_linked" to
                    "Top-ups work only for an AI account that is linked to this wallet's recovery phrase. This one is not.",
            )
    }
}
