package co.electriccoin.zcash.ui.screen.send

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 (W-136/W-137, batch B1, unit U1-wallet): wallet controls a user (or TalkBack) cannot name or reach.
 *
 * - Q2-05 (ZC-1599): unlabeled icon buttons on the money path (Receive (i) x2, Send Address Book, Activity filter,
 *   bookmark, Swap (i), refund (i), swap direction, slippage); the eye keeps saying "Hide balances" while hidden.
 *   Pinned here: every label exists in English and Spanish and is used by a screen; the Send Address Book button, the
 *   transaction bookmark and the eye carry theirs. The remaining sites are U8's "05 remainder" (TalkBack check).
 * - Q2-06 (ZC-166): hiding balances also hides the Wallet tab, which has no eye to show them again.
 * - Q2-07 (ZC-1612): the transparent card has no Copy; the transparent Request flow never says it is public (the
 *   existing "Not Private" chip shows only on the QR step).
 * - Q2-16 (ZC-1619): cancelling the system Send Funds prompt returns to CONFIRMATION with no message; it must say
 *   "Not sent — nothing was paid".
 *
 * Sources and resources are read as text, so this file compiles before the fix.
 */
class WalletControlsAreNamedAndReachableTest {
    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")

    private fun source(path: String): String = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/$path")

    private val mainSources: List<String> by lazy {
        File(ChatResourceText.repoRoot, "ui-lib/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
    }

    /** The keys whose English text is [english] (case-insensitive) and whose Spanish text contains [spanish]. */
    private fun labelKeys(
        english: String,
        spanish: String
    ): List<String> =
        en
            .filter { it.value.trim().equals(english, ignoreCase = true) }
            .keys
            .filter { es[it]?.lowercase()?.contains(spanish.lowercase()) == true }

    private fun labelKey(
        english: String,
        spanish: String
    ): String {
        val keys = labelKeys(english, spanish)
        if (keys.isEmpty()) fail("no string reads \"$english\" in English and \"$spanish\" in Spanish")
        return keys.firstOrNull { key -> mainSources.any { "R.string.$key" in it } }
            ?: fail("\"$english\" (${keys.joinToString()}) is used by no screen")
    }

    // ---- Q2-05 ----

    @Test
    fun q2_05_every_money_path_icon_label_exists_in_both_languages_and_is_used() {
        val labels =
            listOf(
                "About shielded addresses" to "Acerca de las direcciones protegidas",
                "About transparent addresses" to "Acerca de las direcciones transparentes",
                "Address book" to "Libreta de direcciones",
                "Filter" to "Filtrar",
                "Bookmark" to "Agregar marcador",
                "Remove bookmark" to "Quitar marcador",
                "About swaps" to "Acerca de los intercambios",
                "Refund address help" to "Ayuda sobre la dirección de reembolso",
                "Swap direction" to "Cambiar el sentido del intercambio",
                "Slippage" to "Deslizamiento",
                "Show balances" to "Mostrar saldos",
            )
        val missing = labels.filter { (e, s) -> runCatching { labelKey(e, s) }.isFailure }
        assertTrue(missing.isEmpty(), "labels missing in a language or used by no screen: $missing")
    }

    @Test
    fun q2_05_the_send_address_book_button_has_a_name() {
        val view = source("screen/send/view/SendView.kt")
        val at = view.indexOf("onClick = sendAddressBookState.onButtonClick")
        if (at < 0) fail("the Send Address Book button is gone from SendView")
        val image = view.substring(at, minOf(view.length, at + 600))
        val description =
            Regex("""contentDescription\s*=\s*([^\n]+)""")
                .find(image)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: fail("the Send Address Book button has no contentDescription")
        assertFalse(description.startsWith("null"), "the Send Address Book button is unlabeled: $description")
    }

    @Test
    fun q2_05_the_bookmark_and_the_eye_say_what_they_do_now() {
        val bookmark = labelKey("Bookmark", "Agregar marcador")
        val remove = labelKey("Remove bookmark", "Quitar marcador")
        val detail = source("screen/transactiondetail/TransactionDetailVM.kt")
        assertTrue("R.string.$bookmark" in detail, "the transaction bookmark has no 'Bookmark' label")
        assertTrue("R.string.$remove" in detail, "the transaction bookmark has no 'Remove bookmark' label")

        val show = labelKey("Show balances", "Mostrar saldos")
        val appBar = source("common/appbar/ZashiTopAppBarVM.kt")
        assertTrue(
            "R.string.$show" in appBar,
            "the eye keeps saying 'Hide balances' while the balances are hidden"
        )
    }

    // ---- Q2-06 ----

    @Test
    fun q2_06_the_wallet_tab_has_the_eye_to_show_hidden_balances() {
        val tab = source("screen/wallettab/WalletTabView.kt")
        assertTrue(
            "balanceVisibilityButton" in tab || "ic_app_bar_balances_" in tab || "hide_balances" in tab,
            "the Wallet tab header has no show/hide balances control, so hidden balances cannot be shown there"
        )
    }

    // ---- Q2-07 ----

    @Test
    fun q2_07_the_transparent_card_can_be_copied() {
        val view = source("screen/receive/ReceiveView.kt")
        val row = view.substringAfter("AnimatedVisibility(visible = state.isExpanded)", missingDelimiterValue = "")
        if (row.isEmpty()) fail("the Receive card's button row is gone from ReceiveView")
        val copies = Regex("""R\.string\.receive_copy""").findAll(row).map { it.range.first }.toList()
        if (copies.isEmpty()) fail("the Receive card has no Copy button")
        // One Copy that sits behind `if (state.isShielded)` is the bug; a second Copy for the transparent card, or
        // a Copy that is not gated by the shielded flag, is the fix.
        val gated = row.substring(0, copies.first()).substringAfter("Row(").contains("if (state.isShielded) {")
        assertTrue(
            copies.size >= 2 || !gated,
            "the Receive card offers Copy only for the shielded address (the transparent card has no Copy)"
        )
    }

    @Test
    fun q2_07_the_transparent_request_amount_and_memo_say_not_private() {
        for (file in listOf("screen/request/view/RequestAmountView.kt", "screen/request/view/RequestMemoView.kt")) {
            assertTrue(
                "R.string.request_privacy_level_transparent" in source(file),
                "$file never shows the 'Not Private' chip for a transparent request"
            )
        }
    }

    // ---- Q2-16 ----

    @Test
    fun q2_16_cancelling_send_funds_says_nothing_was_paid() {
        val keys =
            en
                .filter { (_, v) -> v.trim() == "Not sent \u2014 nothing was paid" }
                .keys
                .filter { es[it]?.trim() == "No enviado \u2014 no se pag\u00f3 nada" }
        assertTrue(keys.isNotEmpty(), "no 'Not sent — nothing was paid' text in both languages")
        assertTrue(
            keys.any { key -> mainSources.any { "R.string.$key" in it } },
            "no screen or use case shows the Send Funds cancel text (keys: $keys)"
        )

        val submit = source("common/usecase/SubmitProposalUseCase.kt")
        val cancel = submit.substringAfter("catch (_: BiometricsCancelledException) {", missingDelimiterValue = "")
        assertFalse(
            cancel.trimStart().startsWith("// do nothing"),
            "SubmitProposalUseCase still swallows the Send Funds cancel silently"
        )
    }
}
