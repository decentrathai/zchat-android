package co.electriccoin.zcash.ui.screen.request.model

import android.content.Context
import cash.z.ecc.android.sdk.ext.convertUsdToZec
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.ext.toZecString
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.fromZecString
import cash.z.ecc.android.sdk.model.toFiatString
import cash.z.ecc.android.sdk.model.toKotlinLocale
import cash.z.ecc.sdk.extension.floor
import cash.z.ecc.sdk.extension.toZecStringFull
import co.electriccoin.zcash.ui.design.util.getPreferredLocale
import co.electriccoin.zcash.ui.screen.request.ext.convertToDouble

data class Request(
    val amountState: AmountState,
    val memoState: MemoState,
    val qrCodeState: QrCodeState,
)

data class AmountState(
    val amount: String,
    val currency: RequestCurrency,
    val isValid: Boolean?
) {
    fun toZecString(
        conversion: FiatCurrencyConversion,
        context: Context
    ): String =
        runCatching {
            val locale = context.resources.configuration.getPreferredLocale()
            amount.convertToDouble(context).convertUsdToZec(conversion.priceOfZec).toZecString(locale)
        }.getOrElse { "" }

    fun toZecStringFloored(
        conversion: FiatCurrencyConversion,
        context: Context
    ): String =
        runCatching {
            // Format with the SAME locale that RequestVM.createZip321Uri re-parses with
            // (getPreferredLocale). Formatting with Locale.getDefault() (the no-arg toZecStringFull)
            // while parsing with a different preferred locale corrupts the ZIP-321 amount — e.g. a
            // French "0,250" parsed under "en" becomes 250 ZEC (1000x). getFirstMatch(["en","es"]) can
            // resolve getPreferredLocale to a locale whose decimal separator differs from the default.
            val locale = context.resources.configuration.getPreferredLocale()
            amount
                .convertToDouble(context)
                .convertUsdToZec(conversion.priceOfZec)
                .convertZecToZatoshi()
                .floor()
                .toZecStringFull(locale)
        }.getOrElse { "" }

    fun toFiatString(context: Context, conversion: FiatCurrencyConversion) =
        runCatching {
            // SDK 2.5.2: fromZecString now takes (zecString, java.util.Locale); toFiatString takes
            // the SDK's own model.Locale wrapper (via toKotlinLocale()).
            val locale = context.resources.configuration.getPreferredLocale()
            Zatoshi
                .fromZecString(amount, locale)
                ?.toFiatString(conversion, locale.toKotlinLocale())
                ?: ""
        }.getOrElse { "" }
}

sealed class MemoState(
    open val text: String,
    open val byteSize: Int,
    open val zecAmount: String
) {
    fun isValid(): Boolean = this is Valid

    data class Valid(
        override val text: String,
        override val byteSize: Int,
        override val zecAmount: String
    ) : MemoState(text, byteSize, zecAmount)

    data class InValid(
        override val text: String,
        override val byteSize: Int,
        override val zecAmount: String
    ) : MemoState(text, byteSize, zecAmount)

    companion object {
        fun new(memo: String, amount: String): MemoState {
            val bytesCount = Memo.countLength(memo)
            return if (bytesCount > Memo.MAX_MEMO_LENGTH_BYTES) {
                InValid(memo, bytesCount, amount)
            } else {
                Valid(memo, bytesCount, amount)
            }
        }
    }
}

data class QrCodeState(
    val requestUri: String,
    val zecAmount: String,
    val memo: String
)
