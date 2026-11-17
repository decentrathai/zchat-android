package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1704 remainder (QA2 P3 polish; device-qa free pass, Spanish walk; U7 follow-up review finding 4, "the other labels are
 * still English literals"): the transaction detail of a ZCHAT message shows "🔐 ZCHAT message" in English on a Spanish phone
 * (and every other protocol label of `memoDisplayText` is an English literal too, except the paid-request one, which U7 made
 * a resource).
 *
 * Wanted: each protocol memo's label is a string resource (`StringResource.ByResource`), its English text is unchanged byte
 * for byte, and a Spanish text exists that is not the English one. A plain memo still comes back as the user's own text
 * (MemoDisplayTextValuesTest keeps pinning the values of the branches that are not labels). The label whose text carries
 * the file's own name or size (a readable ZFILE) is built from the file and stays as it is.
 *
 * The resource ids are read by value, so the test does not dictate the keys' names.
 */
class MemoDisplayLabelsAreTranslatedTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    /** The memo that reaches the label, and the English label the transaction detail shows today. */
    private val labels =
        listOf(
            "ZMSG|v4|abc" to "🔐 ZCHAT message",
            "ZMSG:abc" to "🔐 ZCHAT message",
            "ZBOOT|v1|abc" to "🔐 Secure connection request",
            "ZREACT|abc|👍" to "Reaction",
            "ZRCPT|abc" to "Read receipt",
            "ZSTAT|abc" to "Status update",
            "ZREQ|0.01|abc" to "💰 Payment request",
            "ZTL|abc" to "🔒 Time-locked message",
            "ZUNLOCK|abc" to "🔒 Time-locked message",
            "ZEXP|abc" to "🔐 ZCHAT control message",
            "ZMODE|abc" to "🔐 ZCHAT control message",
            "ZFILE|not-a-file" to "📎 Attachment",
        )

    private fun keyOf(memo: String): String {
        val label = memoDisplayText(memo)
        val resource =
            assertIs<StringResource.ByResource>(
                label,
                "memoDisplayText(\"${memo.take(12)}…\") is an English literal, not a string resource: $label",
            )
        return assertNotNull(ChatResourceText.stringName(resource.resource), "no string name for resource id ${resource.resource}")
    }

    @Test
    fun `the ZCHAT message label of the transaction detail is a resource with a Spanish text`() {
        for (memo in listOf("ZMSG|v4|abc", "ZMSG:abc")) {
            val key = keyOf(memo)
            assertEquals("🔐 ZCHAT message", en[key], "$key: the English label must stay as it is")
            val spanish = assertNotNull(es[key], "values-es has no $key")
            assertNotEquals(en[key], spanish, "$key: the Spanish label is the English text")
            assertTrue(!spanish.contains("message", ignoreCase = true), "$key: the Spanish label still says \"message\": $spanish")
        }
    }

    @Test
    fun `every other protocol label is a resource with an unchanged English text and its own Spanish text`() {
        for ((memo, english) in labels) {
            val key = keyOf(memo)
            assertEquals(english, en[key], "$key (for ${memo.take(8)}): the English label must stay as it is")
            val spanish = assertNotNull(es[key], "values-es has no $key")
            assertNotEquals(english, spanish, "$key: the Spanish label is the English text")
        }
    }

    @Test
    fun `labels of different kinds do not share one resource by mistake`() {
        val byKey = labels.groupBy({ keyOf(it.first) }, { it.second }).mapValues { it.value.toSet() }
        for ((key, english) in byKey) {
            assertEquals(1, english.size, "$key serves different English labels: $english")
        }
    }
}
