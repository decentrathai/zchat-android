package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 9 R9-1 (Round 8 devices section 6, REVIEW_7C F10 request side) — the Request Payment dialog is a money screen
 * and is all English on an es-ES phone, with a US-only ZEC figure, and it never says what sending the request costs.
 *
 * Wanted:
 * - every text of the dialog comes from string resources in English and Spanish with the same placeholders; no
 *   hard-coded English literal is left in the dialog, and figures go through PaidActionCopy.zec (never a private
 *   DecimalFormat for ZEC, never Locale.US);
 * - the dialog shows what sending the request costs: "at least X ZEC" in a Shielded chat, the figure the send's own
 *   question quotes (paymentRequestSendCost(VAULT) = onChainSendMinimumCost(1, dust)), and "free" in a Tunnel or Open
 *   chat, where the request goes over the relays;
 * - the question before a Shielded request has its own text (paid_action_payment_request) in English and Spanish.
 *
 * Reads the resource files and the view's source, as the other copy tests do.
 */
class PaymentRequestDialogLocalizedTest {
    private val keys =
        listOf(
            "request_payment_title",
            "request_payment_amount_usd_hint",
            "request_payment_amount_label_zec",
            "request_payment_amount_label_usd",
            "request_payment_quick_amounts",
            "request_payment_reason_label",
            "request_payment_button",
            "request_payment_cost_onchain",
            "request_payment_cost_free",
            "paid_action_payment_request",
        )

    /** PaymentRequestComposerDialog in ChatDialogs.kt, up to the next section. */
    private val block: String by lazy {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt")
        val start = source.indexOf("internal fun PaymentRequestComposerDialog(")
        check(start >= 0) { "no PaymentRequestComposerDialog in ChatDialogs" }
        val end = source.indexOf("// PAYMENT REQUEST CONTENT", start)
        check(end > start) { "no end marker after PaymentRequestComposerDialog" }
        source.substring(start, end)
    }

    @Test
    fun `the dialog text is in english and spanish resources with the same placeholders`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val placeholders = Regex("""%\d\$[sd]""")
        for (key in keys) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertEquals(
                placeholders.findAll(english).map { it.value }.toSet(),
                placeholders.findAll(spanish).map { it.value }.toSet(),
                "$key: English and Spanish use different placeholders: \"$english\" / \"$spanish\"",
            )
            assertTrue(english != spanish, "$key: the Spanish text is the English text: \"$spanish\"")
        }
        val cost = en.getValue("request_payment_cost_onchain")
        assertTrue("%1\$s" in cost && "at least" in cost, "the on-chain cost line is not a floor with its figure: $cost")
        assertTrue("al menos" in es.getValue("request_payment_cost_onchain"), "the Spanish cost line is not a floor")
        assertTrue("Free" in en.getValue("request_payment_cost_free"), en.getValue("request_payment_cost_free"))
        assertTrue("Gratis" in es.getValue("request_payment_cost_free"), es.getValue("request_payment_cost_free"))
    }

    @Test
    fun `the dialog uses the resources and has no hard-coded english`() {
        val code = block.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        val english =
            Regex(""""((?:[^"\\]|\\.)*)"""")
                .findAll(code)
                .map { it.groupValues[1] }
                .filter { Regex("[A-Za-z]{2,}").containsMatchIn(it) }
                .toList()
        assertTrue(english.isEmpty(), "the Request Payment dialog still has hard-coded English: $english")
        for (key in keys - "paid_action_payment_request") {
            assertTrue("R.string.$key" in block, "the dialog does not use $key")
        }
        assertTrue("R.string.paid_action_cancel" in block, "Cancel does not use paid_action_cancel")
    }

    @Test
    fun `ZEC figures are written for the phone's language`() {
        assertTrue("PaidActionCopy.zec(" in block, "the dialog does not write ZEC with PaidActionCopy.zec")
        for (literal in listOf("zecFormat", "Locale.US")) {
            assertTrue(literal !in block, "the dialog still formats ZEC with $literal")
        }
    }

    @Test
    fun `the dialog shows what sending the request costs, the figure the send asks with`() {
        val costOf =
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmationKt")
                .getMethod("paymentRequestSendCost", ConversationMode::class.java)
        val vault = costOf.invoke(null, ConversationMode.VAULT) as Zatoshi?
        assertEquals(onChainSendMinimumCost(1, Zatoshi(1_000L)), vault, "a Shielded request costs one one-memo send")
        assertNull(costOf.invoke(null, ConversationMode.TUNNEL), "a Tunnel request goes over the relays, free")
        assertNull(costOf.invoke(null, ConversationMode.OPEN), "an Open request goes over the relays, free")

        val detail = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        assertTrue(
            "paymentRequestSendCost(conversationMode)" in detail,
            "ChatDetailView does not hand the dialog the cost of sending the request in this chat's mode",
        )
        assertTrue("R.string.request_payment_cost_onchain" in block && "R.string.request_payment_cost_free" in block)
    }

    @Test
    fun `the question before a Shielded request has its own text`() {
        val copy = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/PaidActionCopy.kt")
        assertTrue(
            "PaidAction.PAYMENT_REQUEST -> R.string.paid_action_payment_request" in copy,
            "PaidAction.PAYMENT_REQUEST has no text of its own",
        )
    }
}
