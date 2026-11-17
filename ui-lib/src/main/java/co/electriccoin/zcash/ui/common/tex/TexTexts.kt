package co.electriccoin.zcash.ui.common.tex

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.AmountTextReader
import java.math.BigDecimal
import java.util.Locale

/** A text resource and the ZEC figures it is formatted with. */
data class TexText(
    @StringRes val resId: Int,
    val args: List<String> = emptyList(),
)

/** The title and full text of a TEX send's state, as the transaction detail and the notice say it. */
data class TexTexts(
    val title: TexText,
    val body: TexText,
)

/** Zatoshi as ZEC, as a field writes it (F1: never grouped, exact to 8 decimals, the phone's decimal mark). */
fun texZec(
    zatoshi: Long,
    locale: Locale,
): String = AmountTextReader.formatForField(BigDecimal.valueOf(zatoshi, ZEC_DECIMALS), locale, ZEC_DECIMALS)

/** The detail and notice text of [status]; null for a send that needs no word ([TexState.DONE]). */
@Suppress("CyclomaticComplexMethod")
fun texTextsOf(
    status: TexStatus,
    locale: Locale,
): TexTexts? {
    val found = status.foundZat
    val fee = status.feeZat
    return when (status.state) {
        TexState.DONE -> null
        TexState.SENDING_STEP1 ->
            TexTexts(TexText(R.string.tex_status_sending_title), TexText(R.string.tex_status_sending_step1_body))
        TexState.SENDING_STEP2 ->
            TexTexts(TexText(R.string.tex_status_sending_title), TexText(R.string.tex_status_sending_step2_body))
        TexState.NOT_SENT ->
            TexTexts(TexText(R.string.tex_status_not_sent_title), TexText(R.string.tex_status_not_sent_body))
        TexState.CHECKING ->
            TexTexts(
                TexText(R.string.tex_status_checking_title),
                TexText(if (status.torFailing) R.string.tex_status_tor_failing_body else R.string.tex_status_checking_body),
            )
        TexState.NEEDS_TOR ->
            TexTexts(
                TexText(R.string.tex_status_checking_title),
                TexText(if (status.torOffByUser) R.string.tex_status_tor_off_body else R.string.tex_status_needs_tor_body),
            )
        TexState.RETURNED ->
            TexTexts(
                TexText(R.string.tex_status_returned_title),
                TexText(
                    if (status.waitingForOtherFunds) R.string.tex_status_waiting_other_body else R.string.tex_status_returned_body,
                ),
            )
        TexState.SHIELDING ->
            TexTexts(
                TexText(R.string.tex_status_returned_title),
                if (found != null && fee != null) {
                    TexText(R.string.tex_status_shielding_body, listOf(texZec(found, locale), texZec(fee, locale)))
                } else {
                    TexText(R.string.tex_status_returned_body)
                },
            )
        TexState.SHIELDED ->
            TexTexts(
                TexText(R.string.tex_status_shielded_title),
                if (found != null && fee != null) {
                    TexText(
                        if (status.torTurnedOnByZchat) R.string.tex_status_shielded_body_tor else R.string.tex_status_shielded_body,
                        listOf(texZec(found, locale), texZec(fee, locale), texZec(found - fee, locale)),
                    )
                } else {
                    TexText(R.string.tex_status_returned_body)
                },
            )
        TexState.NEEDS_SUPPORT ->
            if (status.unmatched) {
                TexTexts(TexText(R.string.tex_status_unmatched_title), TexText(R.string.tex_status_unmatched_body))
            } else {
                TexTexts(TexText(R.string.tex_status_needs_support_title), TexText(R.string.tex_status_needs_support_body))
            }
    }
}

/** The Home bar's title and one-line text for [status], or null when Home says nothing about it. */
fun texHomeTextsOf(
    status: TexStatus,
    locale: Locale,
): TexTexts? =
    when (status.state) {
        TexState.CHECKING, TexState.NEEDS_TOR ->
            TexTexts(TexText(R.string.tex_status_checking_title), TexText(R.string.tex_home_checking_subtitle))
        TexState.RETURNED, TexState.SHIELDING ->
            TexTexts(TexText(R.string.tex_status_returned_title), TexText(R.string.tex_home_returned_subtitle))
        TexState.NEEDS_SUPPORT ->
            if (status.unmatched) {
                TexTexts(TexText(R.string.tex_status_unmatched_title), TexText(R.string.tex_home_unmatched_subtitle))
            } else {
                TexTexts(TexText(R.string.tex_status_needs_support_title), TexText(R.string.tex_home_needs_support_subtitle))
            }
        TexState.SHIELDED -> {
            val found = status.foundZat
            val fee = status.feeZat
            if (status.noticeShown || found == null || fee == null) {
                null
            } else {
                TexTexts(
                    TexText(R.string.tex_status_shielded_title),
                    TexText(R.string.tex_home_shielded_subtitle, listOf(texZec(found - fee, locale), texZec(fee, locale))),
                )
            }
        }
        else -> null
    }

/** The TEX send Home tells about first: one that needs support, then one being recovered, then an unread notice. */
fun texHomeStatusOf(statuses: List<TexStatus>): TexStatus? =
    statuses.firstOrNull { it.state == TexState.NEEDS_SUPPORT }
        ?: statuses.firstOrNull { it.state == TexState.RETURNED || it.state == TexState.SHIELDING }
        ?: statuses.firstOrNull { it.state == TexState.CHECKING || it.state == TexState.NEEDS_TOR }
        ?: statuses.firstOrNull { it.state == TexState.SHIELDED && !it.noticeShown && it.foundZat != null }

private const val ZEC_DECIMALS = 8
