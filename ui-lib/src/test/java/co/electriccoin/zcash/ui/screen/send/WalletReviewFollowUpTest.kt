package co.electriccoin.zcash.ui.screen.send

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 (W-137, unit U1-wallet), review follow-up of fix 1d7de12d4 (ZC-1600, ZC-731, ZC-1669, ZC-1599 family).
 * Resources and sources are read by name, so this file compiles before the fix.
 *
 * - Nit 1: the Spanish shield title "¡Protegidas!" is feminine plural (from "monedas") while the line under it now
 *   reads "Tu ZEC se ha protegido"; it must agree: "¡Protegido!".
 * - Nit 2: "Fee not known yet" promises a value that never comes for a mined send whose fee the SDK does not know;
 *   "yet" is only for a pending transaction, a completed one says "Fee not known".
 * - Nit 3: the "Support ZCHAT" title stayed after the user replaced the prefilled donation address with another
 *   recipient; it shows only while the field still holds the donation address.
 */
class WalletReviewFollowUpTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")

    private fun plain(text: String): String =
        text
            .replace(Regex("""<xliff:g[^>]*>"""), "")
            .replace("</xliff:g>", "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    @Test
    fun nit1_the_spanish_shield_title_agrees_with_its_subtitle() {
        val key = "send_confirmation_success_title_transparent"
        // ZC-1708 (QA2 P3): the screen opens at the broadcast, while the line under it says the ZEC "is being shielded", so the
        // title is no longer "¡Protegido!" but a "sent" title (ShieldTitleAndSpanishSiblingsP3Test pins the rule).
        val title = plain(es[key] ?: fail("values-es has no $key"))
        assertTrue(Regex("""^¡?Enviad""").containsMatchIn(title), "the Spanish title is not a sent title: $title")
        assertEquals(
            "Tu ZEC se está protegiendo. Enviado a la red Zcash — aún sin confirmar.",
            plain(es.getValue("send_confirmation_success_subtitle_transparent")),
        )
    }

    @Test
    fun nit2_a_completed_send_says_fee_not_known_and_only_a_pending_one_says_yet() {
        val pendingKey =
            en.entries
                .firstOrNull {
                    it.key.startsWith("transaction_detail_") && plain(it.value).lowercase() == "fee not known yet"
                }?.key ?: fail("no transaction_detail_ string reads \"Fee not known yet\" (pending)")
        val completedKey =
            en.entries
                .firstOrNull {
                    it.key.startsWith("transaction_detail_") && plain(it.value).lowercase() == "fee not known"
                }?.key ?: fail("no transaction_detail_ string reads \"Fee not known\" (completed)")
        assertNotEquals(pendingKey, completedKey)

        val spanish = plain(es[completedKey] ?: fail("values-es has no $completedKey"))
        assertTrue("comisión" in spanish.lowercase(), spanish)
        assertTrue(
            "no conocida" in spanish.lowercase() || "desconocida" in spanish.lowercase(),
            "the Spanish completed-fee text must say the fee is not known: $spanish"
        )
        assertFalse("aún" in spanish.lowercase() || "todavía" in spanish.lowercase(), "no \"yet\" for a mined send: $spanish")
        assertFalse(Regex("""\d""").containsMatchIn(spanish), spanish)
        assertTrue(
            "aún" in plain(es.getValue(pendingKey)).lowercase() || "todavía" in plain(es.getValue(pendingKey)).lowercase(),
            "the pending text keeps its \"yet\""
        )

        val vm =
            ChatResourceText.source(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactiondetail/TransactionDetailVM.kt"
            )
        val body = vm.substringAfter("fun createFeeStringRes(").substringBefore("\n    }\n")
        assertTrue("R.string.$pendingKey" in body, "createFeeStringRes never shows R.string.$pendingKey")
        assertTrue("R.string.$completedKey" in body, "createFeeStringRes never shows R.string.$completedKey")
        assertTrue("isPending(" in body, "createFeeStringRes does not tell a pending send from a completed one")
    }

    @Test
    fun nit3_the_support_title_shows_only_while_the_donation_address_is_prefilled() {
        val view =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/send/view/SendView.kt")
        val titleLine =
            view.lines().firstOrNull { "title =" in it && "R.string.settings_support_zchat" in it }
                ?: fail("SendView no longer sets the Support ZCHAT title")
        assertTrue("isSupportSend" in titleLine, titleLine)
        assertTrue(
            "isDonationAddressPrefilled" in titleLine,
            "the Support ZCHAT title ignores whether the donation address is still in the field: ${titleLine.trim()}"
        )
    }
}
