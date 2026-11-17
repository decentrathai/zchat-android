package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal

/** What a ZIP 321 request asks to pay: how many payments, and their amounts summed in zatoshi. */
internal data class Zip321Totals(
    val payments: Int,
    val total: Long,
)

private const val ZIP321_SCHEME = "zcash:"
private const val ZEC_DECIMALS = 8
private val ZIP321_AMOUNT = Regex("^\\d+(?:\\.\\d{1,8})?$")

/**
 * The payment count and total of a ZIP 321 URI in the form the SDK writes into a proposal step
 * (zip321 `TransactionRequest::to_uri`): "zcash:" for no payment, "zcash:ADDR?amount=1.5" for one, and
 * "zcash:?address=A&amount=1&address.1=B&amount.1=0.5" for several. Parameters other than address and
 * amount are ignored. Null for anything else (a repeated index, an amount without an address, an amount
 * that is not a plain decimal of at most 8 places): every caller refuses on null.
 */
internal fun readZip321Totals(uri: String): Zip321Totals? {
    val request = parseZip321Request(uri) ?: return null
    val total =
        try {
            request.amounts.values.fold(0L) { sum, value -> Math.addExact(sum, value) }
        } catch (_: ArithmeticException) {
            return null
        }
    return Zip321Totals(payments = request.addresses.size, total = total)
}

/**
 * The recipient address text of each payment of a ZIP 321 URI read as [readZip321Totals] reads it, in
 * payment-index order. Null when [readZip321Totals] would be null.
 */
internal fun readZip321Recipients(uri: String): List<String>? {
    if (readZip321Totals(uri) == null) return null
    val request = parseZip321Request(uri) ?: return null
    return request.addresses.keys
        .sorted()
        .map { request.addresses.getValue(it) }
}

private class Zip321Request(
    val addresses: Map<Int, String>,
    val amounts: Map<Int, Long>,
)

@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun parseZip321Request(uri: String): Zip321Request? {
    if (!uri.startsWith(ZIP321_SCHEME)) return null
    val rest = uri.substring(ZIP321_SCHEME.length)
    val path = rest.substringBefore('?')
    val query = rest.substringAfter('?', missingDelimiterValue = "")
    val addresses = mutableMapOf<Int, String>()
    val amounts = mutableMapOf<Int, Long>()
    if (path.isNotEmpty()) addresses[0] = path
    if (query.isNotEmpty()) {
        for (param in query.split('&')) {
            if ('=' !in param) return null
            val name = param.substringBefore('=')
            val key = name.substringBefore('.')
            if (key != "address" && key != "amount") continue
            val index =
                if ('.' in name) {
                    name.substringAfter('.').toIntOrNull()?.takeIf { it > 0 } ?: return null
                } else {
                    0
                }
            if (key == "address") {
                if (addresses.put(index, param.substringAfter('=')) != null) return null
            } else {
                val value = zatoshiOrNull(param.substringAfter('=')) ?: return null
                if (amounts.put(index, value) != null) return null
            }
        }
    }
    if (!addresses.keys.containsAll(amounts.keys)) return null
    return Zip321Request(addresses, amounts)
}

/**
 * A scanned payment request asks for more than one payment. The wallet pays one scanned payment at a
 * time and shows only that one on the review screen, so the request is refused as a whole rather than
 * paying part of it without saying so (R2-1 b). The error sheet shows its own EN/ES text.
 */
class ScannedMultiPaymentException(
    val payments: Int,
) : Exception("This payment request asks for $payments payments. ZChat pays one scanned payment at a time.")

/**
 * Null when the proposal [shape] pays exactly one payment of [shown] zatoshi to [shownRecipient], summed
 * over its steps (a TEX payment is two steps and the first pays nothing); otherwise why not. Reads the
 * SDK's own transaction requests, so a URI the two ZIP 321 parsers read differently cannot pay more, or
 * pay someone else, than the review screen shows. The recipients are compared as decoded addresses
 * ([sameZcashAddress]): an upper-case QR matches the SDK's lower case, and text that does not decode as an
 * address on either side is a mismatch. Fails closed on anything unreadable. The reason never carries an
 * address, because it ends up in a log.
 */
@Suppress("ReturnCount")
internal fun scannedRequestMismatch(
    shape: SweepShape?,
    shown: Long,
    shownRecipient: String,
): String? {
    if (shape == null) return "the proposal cannot be read"
    val requests = shape.transactionRequests.map { readZip321Totals(it) ?: return "a transaction request cannot be read" }
    val recipients =
        shape.transactionRequests.flatMap { readZip321Recipients(it) ?: return "a transaction request cannot be read" }
    val payments = requests.sumOf { it.payments }
    val total =
        try {
            requests.fold(0L) { sum, request -> Math.addExact(sum, request.total) }
        } catch (_: ArithmeticException) {
            return "the proposal total overflows"
        }
    return when {
        payments != 1 -> "the proposal pays $payments payments, the review shows 1"
        total != shown -> "the proposal pays $total zatoshi, the review shows $shown"
        !sameZcashAddress(recipients.single(), shownRecipient) -> "the proposal pays a recipient the review does not show"
        else -> null
    }
}

private fun zatoshiOrNull(zec: String): Long? =
    if (ZIP321_AMOUNT.matches(zec)) {
        runCatching { BigDecimal(zec).movePointRight(ZEC_DECIMALS).longValueExact() }.getOrNull()
    } else {
        null
    }
