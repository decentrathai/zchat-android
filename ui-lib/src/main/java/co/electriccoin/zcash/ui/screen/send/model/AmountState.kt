package co.electriccoin.zcash.ui.screen.send.model

import androidx.compose.runtime.saveable.mapSaver
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.toZatoshi
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.design.util.AmountInvalidReason
import co.electriccoin.zcash.ui.design.util.AmountRead
import co.electriccoin.zcash.ui.design.util.AmountReading
import co.electriccoin.zcash.ui.design.util.AmountTextReader
import java.math.BigDecimal

/** ZEC has 8 decimals (one zatoshi). */
private const val ZEC_DECIMALS = 8

/** The USD field keeps cents. */
private const val FIAT_DECIMALS = 2

/** Every ZEC that can exist (QA2 m4, ZC-1764): a larger amount is never usable, and it is compared before zatoshi. */
private val MAX_ZEC: BigDecimal = BigDecimal.valueOf(21_000_000L)

/**
 * Main Send's amount: the ZEC and USD fields' texts and what they read as.
 *
 * F1 (fix-all 2.13.0): both fields are read with the shared amount reader (AmountTextReader). A user's edit goes
 * through [edit], the one amount state machine of every amount field (QA2 m3, AmountFieldState): a typed mark is the
 * decimal point (and shows at once as the phone's mark, H14), a key that cannot be taken is not taken; a paste that
 * reads two ways ("1,000", or "1.500" on a Spanish phone) is an [Ask], so Send (which needs [Valid]) stays disabled
 * until a reading is tapped, and a key on it empties the field; text the field already holds or the app wrote is read
 * as it stands (STORED, H1). Everything the app writes is never grouped (H2). Nothing remembers how a text arrived.
 */
sealed interface AmountState {
    val value: String
    val fiatValue: String
    val lastFieldChangedByUser: AmountField

    /**
     * The USD text a ZEC edit or a Send-all fill keeps when there is no fresh rate (T1 fixup, X-F1-3): "" while the
     * USD field holds an unanswered paste question or a paste it could not read (no one chose that text), otherwise
     * [fiatValue], as before.
     */
    val fiatValueKeptByZecEdit: String
        get() =
            when {
                this is Ask && this.field == AmountField.FIAT -> ""
                this is Invalid && reason != null && lastFieldChangedByUser == AmountField.FIAT -> ""
                else -> fiatValue
            }

    data class Valid(
        override val value: String,
        override val fiatValue: String,
        override val lastFieldChangedByUser: AmountField,
        val zatoshi: Zatoshi,
    ) : AmountState

    data class Invalid(
        override val value: String,
        override val fiatValue: String,
        override val lastFieldChangedByUser: AmountField,
        /** Why the text can't be used (shown under the field); null for an empty or zero amount. */
        val reason: AmountInvalidReason? = null,
    ) : AmountState

    /**
     * A paste in [field] that reads two ways: [options] are the usable readings ascending, nothing preselected;
     * [notUsable] the reading that has more decimals than the field holds (shown, not offered). [raw] is the paste as it
     * was pasted; the other field is blank (H3).
     */
    data class Ask(
        override val value: String,
        override val fiatValue: String,
        override val lastFieldChangedByUser: AmountField,
        val options: List<BigDecimal>,
        val field: AmountField,
        val raw: String,
        val notUsable: List<BigDecimal> = emptyList(),
    ) : AmountState

    companion object {
        /**
         * A user's edit of main Send's [field]: its text becomes [newText]; [selectionBefore] is the field's selection
         * before the edit (start..end), null when it is not known. The edit goes through the one amount state machine
         * (QA2 m3, AmountFieldState.edit): an open question in [field] is that machine's question, a refusal shown in
         * [field] its refusal, and any other text its typed text. A key that cannot be taken leaves [current] as it is.
         */
        @Suppress("LongParameterList")
        fun edit(
            current: AmountState,
            field: AmountField,
            newText: String,
            selectionBefore: IntRange?,
            locale: java.util.Locale,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
        ): AmountState =
            editResult(current, field, newText, selectionBefore, locale, isTransparentOrTextRecipient, exchangeRateState).state

        /**
         * QA2 m8 (ZC-1780, ZC-1774): [edit] and whether the amount machine took the edit. Not taken leaves [current] as it
         * is; a taken edit may give a state equal to [current] ("$1.2" pasted over "1.2").
         */
        @Suppress("LongParameterList")
        fun editResult(
            current: AmountState,
            field: AmountField,
            newText: String,
            selectionBefore: IntRange?,
            locale: java.util.Locale,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
        ): AmountEditResult<AmountState> {
            val (maxDecimals, unit) =
                if (field == AmountField.ZEC) ZEC_DECIMALS to AmountUnit.ZEC else FIAT_DECIMALS to AmountUnit.USD
            val before = fieldStateOf(current, field, locale)
            val result = before.editResult(newText, locale, maxDecimals, unit, true, selectionBefore)
            if (!result.taken) return AmountEditResult(current, taken = false)
            val after = result.state
            val readings = after.readings
            val error = after.error
            val next =
                when {
                    readings != null ->
                        Ask(
                            value = if (field == AmountField.ZEC) after.shown else "",
                            fiatValue = if (field == AmountField.FIAT) after.shown else "",
                            lastFieldChangedByUser = field,
                            options = readings.filter { it.usable }.map { it.value },
                            field = field,
                            raw = after.shown,
                            notUsable = readings.filterNot { it.usable }.map { it.value }
                        )
                    error != null ->
                        if (field == AmountField.ZEC) {
                            Invalid(after.shown, "", AmountField.ZEC, error)
                        } else {
                            Invalid("", after.shown, AmountField.FIAT, error)
                        }
                    field == AmountField.ZEC ->
                        newFromZec(
                            locale = locale,
                            value = AmountTextReader.toFieldText(after.settled, locale),
                            fiatValue = current.fiatValueKeptByZecEdit,
                            isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                            exchangeRateState = exchangeRateState,
                        )
                    else ->
                        newFromFiat(
                            locale = locale,
                            value = current.value,
                            fiatValue = AmountTextReader.toFieldText(after.settled, locale),
                            isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                            exchangeRateState = exchangeRateState,
                        )
                }
            return AmountEditResult(next, taken = true)
        }

        /** [field] of [current] as the amount machine's state; the field shows its text with the phone's mark. */
        private fun fieldStateOf(
            current: AmountState,
            field: AmountField,
            locale: java.util.Locale
        ): AmountFieldState {
            val text = if (field == AmountField.ZEC) current.value else current.fiatValue
            return when {
                current is Ask && current.field == field ->
                    AmountFieldState(
                        shown = current.raw,
                        readings =
                            (current.options.map { AmountReading(it, true) } + current.notUsable.map { AmountReading(it, false) })
                                .sortedWith(compareBy { it.value })
                    )
                current is Invalid && current.reason != null && current.lastFieldChangedByUser == field ->
                    AmountFieldState(shown = text, error = current.reason)
                else -> {
                    val mark = AmountTextReader.decimalMarkOf(locale)
                    AmountFieldState(shown = text, settled = if (mark == '.') text else text.replace(mark, '.'))
                }
            }
        }

        /**
         * The state after the ZEC field's text becomes [value], text the field already holds or the app wrote, read as
         * typing reads it (it never asks). A user's edit goes through [edit].
         */
        @Suppress("LongParameterList", "ReturnCount")
        fun newFromZec(
            locale: java.util.Locale,
            value: String,
            fiatValue: String,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
            lastFieldChangedByUser: AmountField = AmountField.ZEC,
        ): AmountState {
            val read = AmountTextReader.readTyped(value, locale, ZEC_DECIMALS)
            val text =
                when (read) {
                    is AmountRead.Ask ->
                        return Ask(
                            value = value,
                            fiatValue = "",
                            lastFieldChangedByUser = lastFieldChangedByUser,
                            options = read.options,
                            field = AmountField.ZEC,
                            raw = value,
                            notUsable = read.readings.filterNot { it.usable }.map { it.value }
                        )
                    is AmountRead.Invalid ->
                        return Invalid(value, "", lastFieldChangedByUser, read.reason)
                    is AmountRead.Exact -> read
                }
            val shown = AmountTextReader.toFieldText(text.text, locale)
            val zecAmount = text.value ?: return Invalid(shown, if (shown.isBlank()) "" else fiatValue, lastFieldChangedByUser)

            // More ZEC than can exist: the text reads as typed (QA2 m3, the machine keeps it typed text), but the amount
            // can't be used, so Send stays disabled and the field says "invalid amount". The decimal is compared before it
            // becomes zatoshi, which would wrap past 2^64 (184467440738.09551616 was 1 ZEC; QA2 m4, ZC-1764), and the
            // dollar text of an earlier amount is not kept beside it (ZC-1765).
            if (zecAmount > MAX_ZEC) return Invalid(shown, "", lastFieldChangedByUser)
            val zatoshi =
                try {
                    zecAmount.convertZecToZatoshi()
                } catch (_: IllegalArgumentException) {
                    return Invalid(shown, "", lastFieldChangedByUser)
                }

            // Note that the zero funds sending is supported for sending a memo-only shielded transaction.
            return when {
                (zatoshi.value == 0L && isTransparentOrTextRecipient) ->
                    Invalid(shown, fiatValue, lastFieldChangedByUser)
                else ->
                    Valid(
                        value = shown,
                        zatoshi = zatoshi,
                        fiatValue = freshConversion(exchangeRateState)?.let { fiatText(zatoshi, it, locale) } ?: fiatValue,
                        lastFieldChangedByUser = lastFieldChangedByUser
                    )
            }
        }

        /**
         * The state after the USD field's text becomes [fiatValue], read as [newFromZec] reads its text. An error here
         * blanks the ZEC side, so the old ZEC amount can't come back (H3).
         */
        @Suppress("LongParameterList", "ReturnCount")
        fun newFromFiat(
            locale: java.util.Locale,
            value: String,
            fiatValue: String,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
        ): AmountState {
            val read = AmountTextReader.readTyped(fiatValue, locale, FIAT_DECIMALS)
            val text =
                when (read) {
                    is AmountRead.Ask ->
                        return Ask(
                            value = "",
                            fiatValue = fiatValue,
                            lastFieldChangedByUser = AmountField.FIAT,
                            options = read.options,
                            field = AmountField.FIAT,
                            raw = fiatValue,
                            notUsable = read.readings.filterNot { it.usable }.map { it.value }
                        )
                    is AmountRead.Invalid -> return Invalid("", fiatValue, AmountField.FIAT, read.reason)
                    is AmountRead.Exact -> read
                }
            val shown = AmountTextReader.toFieldText(text.text, locale)
            val fiatAmount = text.value ?: return Invalid("", shown, AmountField.FIAT)

            val conversion = (exchangeRateState as? ExchangeRateState.Data)?.currencyConversion
            // QA2 m4 (ZC-1764, the same wrap on the dollar side): dollars worth more ZEC than can exist are never converted.
            val price = conversion?.priceOfZec?.takeIf { it > 0.0 && it.isFinite() }?.let(BigDecimal::valueOf)
            if (price != null && fiatAmount > MAX_ZEC.multiply(price)) return Invalid("", shown, AmountField.FIAT)
            val zatoshi = conversion?.toZatoshi(amount = fiatAmount)

            return when {
                zatoshi == null || (zatoshi.value == 0L && isTransparentOrTextRecipient) ->
                    Invalid("", shown, AmountField.FIAT)
                else ->
                    Valid(
                        value = AmountTextReader.formatForField(zatoshi.toZec(), locale, ZEC_DECIMALS),
                        zatoshi = zatoshi,
                        fiatValue = shown,
                        lastFieldChangedByUser = AmountField.FIAT
                    )
            }
        }

        /**
         * The state for an amount the app already holds as zatoshi (a scanned ZIP-321 request or a resend, Back from
         * review, Send all). The text is written with the no-grouping field formatter, so every zatoshi is kept and it
         * reads back on every phone language; it never meets the paste rules. [fiatValueIfNoRate] is kept when there
         * is no fresh rate.
         */
        @Suppress("LongParameterList")
        fun fromZatoshi(
            zatoshi: Zatoshi,
            locale: java.util.Locale,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
            lastFieldChangedByUser: AmountField = AmountField.ZEC,
            fiatValueIfNoRate: String = "",
        ): AmountState {
            val value = AmountTextReader.formatForField(zatoshi.toZec(), locale, ZEC_DECIMALS)
            val fiatValue = freshConversion(exchangeRateState)?.let { fiatText(zatoshi, it, locale) } ?: fiatValueIfNoRate
            return if (zatoshi.value == 0L && isTransparentOrTextRecipient) {
                Invalid(value, fiatValue, lastFieldChangedByUser)
            } else {
                Valid(value, fiatValue, lastFieldChangedByUser, zatoshi)
            }
        }

        /**
         * The reading tapped under the field of [ask]: written as the field's own text; the question closes. Only a
         * usable reading of [ask] is taken (QA2 m4, ZC-1765); any other value leaves the question open.
         */
        fun choose(
            ask: Ask,
            option: BigDecimal,
            locale: java.util.Locale,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
        ): AmountState =
            if (ask.options.none { it.compareTo(option) == 0 }) {
                ask
            } else {
                chosen(ask, option, locale, isTransparentOrTextRecipient, exchangeRateState)
            }

        private fun chosen(
            ask: Ask,
            option: BigDecimal,
            locale: java.util.Locale,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
        ): AmountState =
            when (ask.field) {
                AmountField.ZEC ->
                    newFromZec(
                        locale = locale,
                        value = AmountTextReader.formatForField(option, locale, ZEC_DECIMALS),
                        fiatValue = "",
                        isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                        exchangeRateState = exchangeRateState,
                    )
                AmountField.FIAT ->
                    newFromFiat(
                        locale = locale,
                        value = "",
                        fiatValue = AmountTextReader.formatForField(option, locale, FIAT_DECIMALS),
                        isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                        exchangeRateState = exchangeRateState,
                    )
            }

        /**
         * [current] after the recipient or the exchange rate changed. An open question and a refusal stay exactly
         * as they are (H3: nothing revives the old amount or answers the question); any other text is re-read as the
         * field's own text (H1), so a typed "1,500" on a Spanish phone stays 1.5, as before the ZEC text is kept and
         * the other side is converted again. A Send-all fill priced for another kind of recipient ([isFillStale]) is
         * cleared.
         */
        fun refreshed(
            current: AmountState,
            isFillStale: Boolean,
            locale: java.util.Locale,
            isTransparentOrTextRecipient: Boolean,
            exchangeRateState: ExchangeRateState,
        ): AmountState {
            if (current is Ask) return current
            if (current is Invalid && current.reason != null) return current
            val value = if (isFillStale) "" else current.value
            val fiatValue = if (isFillStale) "" else current.fiatValue
            return if (value.isNotBlank() || fiatValue.isBlank()) {
                newFromZec(
                    locale = locale,
                    value = value,
                    fiatValue = fiatValue,
                    isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                    exchangeRateState = exchangeRateState,
                    lastFieldChangedByUser = current.lastFieldChangedByUser,
                )
            } else {
                newFromFiat(
                    locale = locale,
                    value = value,
                    fiatValue = fiatValue,
                    isTransparentOrTextRecipient = isTransparentOrTextRecipient,
                    exchangeRateState = exchangeRateState,
                )
            }
        }

        private fun Zatoshi.toZec(): BigDecimal = BigDecimal.valueOf(value, ZEC_DECIMALS)

        /** The rate to convert with, or null when there is none or it is stale (not loading). */
        private fun freshConversion(exchangeRateState: ExchangeRateState): FiatCurrencyConversion? =
            if (exchangeRateState !is ExchangeRateState.Data ||
                (!exchangeRateState.isLoading && exchangeRateState.isStale)
            ) {
                null
            } else {
                exchangeRateState.currencyConversion
            }

        /** [zatoshi] in the fiat currency, as the USD field writes it: to the cent, never grouped (H2). */
        private fun fiatText(
            zatoshi: Zatoshi,
            conversion: FiatCurrencyConversion,
            locale: java.util.Locale
        ): String =
            AmountTextReader.formatForField(
                zatoshi.toZec().multiply(BigDecimal.valueOf(conversion.priceOfZec)),
                locale,
                FIAT_DECIMALS
            )

        private const val TYPE_VALID = "valid" // $NON-NLS
        private const val TYPE_INVALID = "invalid" // $NON-NLS
        private const val TYPE_ASK = "ask" // $NON-NLS
        private const val KEY_TYPE = "type" // $NON-NLS
        private const val KEY_VALUE = "value" // $NON-NLS
        private const val KEY_FIAT_VALUE = "fiat_value" // $NON-NLS
        private const val KEY_ZATOSHI = "zatoshi" // $NON-NLS
        private const val KEY_LAST_FIELD_CHANGED_BY_USER = "last_field_changed_by_user" // $NON-NLS
        private const val KEY_NOT_USABLE = "not_usable" // $NON-NLS
        private const val KEY_REASON = "reason" // $NON-NLS
        private const val KEY_OPTIONS = "options" // $NON-NLS
        private const val KEY_FIELD = "field" // $NON-NLS
        private const val KEY_RAW = "raw" // $NON-NLS

        internal val Saver
            get() =
                run {
                    mapSaver(
                        save = { it.toSaverMap() },
                        restore = {
                            if (it.isEmpty()) {
                                null
                            } else {
                                val amountString = (it[KEY_VALUE] as String)
                                val fiatAmountString = (it[KEY_FIAT_VALUE] as String)
                                val type = (it[KEY_TYPE] as String)
                                val lastFieldChangedByUser =
                                    AmountField.valueOf(it[KEY_LAST_FIELD_CHANGED_BY_USER] as String)
                                when (type) {
                                    TYPE_VALID ->
                                        Valid(
                                            value = amountString,
                                            fiatValue = fiatAmountString,
                                            zatoshi = Zatoshi(it[KEY_ZATOSHI] as Long),
                                            lastFieldChangedByUser = lastFieldChangedByUser
                                        )

                                    TYPE_INVALID ->
                                        Invalid(
                                            value = amountString,
                                            fiatValue = fiatAmountString,
                                            lastFieldChangedByUser = lastFieldChangedByUser,
                                            reason = reasonOf(it[KEY_REASON] as? String)
                                        )

                                    // H12: an open question survives a rotation.
                                    TYPE_ASK ->
                                        Ask(
                                            value = amountString,
                                            fiatValue = fiatAmountString,
                                            lastFieldChangedByUser = lastFieldChangedByUser,
                                            options = decimalsOf(it[KEY_OPTIONS] as String),
                                            field = AmountField.valueOf(it[KEY_FIELD] as String),
                                            raw = it[KEY_RAW] as String,
                                            notUsable = decimalsOf(it[KEY_NOT_USABLE] as? String ?: "")
                                        )

                                    else -> null
                                }
                            }
                        }
                    )
                }

        private fun AmountState.toSaverMap(): HashMap<String, Any> {
            val saverMap = HashMap<String, Any>()
            when (this) {
                is Valid -> {
                    saverMap[KEY_TYPE] = TYPE_VALID
                    saverMap[KEY_ZATOSHI] = this.zatoshi.value
                }

                is Invalid -> {
                    saverMap[KEY_TYPE] = TYPE_INVALID
                    reasonKey(reason)?.let { key -> saverMap[KEY_REASON] = key }
                }

                is Ask -> {
                    saverMap[KEY_TYPE] = TYPE_ASK
                    saverMap[KEY_OPTIONS] = options.joinToString("|") { option -> option.toPlainString() }
                    saverMap[KEY_FIELD] = field.name
                    saverMap[KEY_RAW] = raw
                    saverMap[KEY_NOT_USABLE] = notUsable.joinToString("|") { reading -> reading.toPlainString() }
                }
            }
            saverMap[KEY_VALUE] = this.value
            saverMap[KEY_FIAT_VALUE] = this.fiatValue
            saverMap[KEY_LAST_FIELD_CHANGED_BY_USER] = this.lastFieldChangedByUser.name

            return saverMap
        }

        private fun decimalsOf(joined: String): List<BigDecimal> =
            joined.split('|').filter { it.isNotEmpty() }.map { BigDecimal(it) }

        private fun reasonKey(reason: AmountInvalidReason?): String? =
            when (reason) {
                null -> null
                is AmountInvalidReason.TooManyDecimals -> "decimals:${reason.max}"
                AmountInvalidReason.Unreadable -> "unreadable"
                AmountInvalidReason.PasteIntoAmount -> "paste_into"
                is AmountInvalidReason.OtherUnit -> "other_unit:${reason.found.name}"
            }

        private fun reasonOf(key: String?): AmountInvalidReason? =
            when {
                key == null -> null
                key.startsWith("decimals:") -> AmountInvalidReason.TooManyDecimals(key.substringAfter(':').toInt())
                key == "unreadable" -> AmountInvalidReason.Unreadable
                key == "paste_into" -> AmountInvalidReason.PasteIntoAmount
                key.startsWith("other_unit:") ->
                    AmountUnit.entries
                        .firstOrNull { it.name == key.substringAfter(':') }
                        ?.let { AmountInvalidReason.OtherUnit(it) }
                else -> null
            }
    }
}

enum class AmountField { ZEC, FIAT }
