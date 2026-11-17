package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * ZC-1409 — the label over an AI reply whose stream ended before `[DONE]` is a string resource in English and Spanish
 * (an agent draft for the native-speaker pass). It says only that the reply was cut off: the code does not know
 * whether the cut stream was charged, so the label claims nothing about cost or a refund.
 */
class AiCutOffReplyStringHasSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun the_cut_off_label_is_english_and_spanish() {
        assertEquals("Assistant · external AI · reply cut off", en[KEY], "English $KEY")
        val spanish = assertNotNull(es[KEY], "no Spanish $KEY")
        assertNotEquals(en[KEY], spanish, "$KEY is not translated")
    }

    @Test
    fun the_cut_off_label_makes_no_cost_claim() {
        for (lang in listOf(en, es)) {
            val label = lang[KEY].orEmpty().lowercase()
            for (claim in listOf("free", "refund", "charged", "gratis", "reembolso", "cobrado")) {
                assertFalse(claim in label, "$KEY claims something about cost: $label")
            }
        }
    }

    private companion object {
        const val KEY = "ai_assistant_external_cut_off"
    }
}
