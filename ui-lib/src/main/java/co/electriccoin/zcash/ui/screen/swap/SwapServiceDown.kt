package co.electriccoin.zcash.ui.screen.swap

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import java.time.ZoneId

/**
 * ZC-1458: the swap service (1Click) answering a server error or not answering in time is the service being down, not
 * the user's connection. On 2 Nov 2026 it answered Cloudflare 521 and the app said "check your connection".
 */
object SwapServiceDown {
    private const val FIRST_SERVER_ERROR = 500

    /** An HTTP 5xx (502, 503, 521 to 524 included) or a timeout from the swap service. */
    fun isServiceDown(error: Throwable?): Boolean =
        when (error) {
            null -> false
            is ResponseException -> error.response.status.value >= FIRST_SERVER_ERROR
            is HttpRequestTimeoutException,
            is ConnectTimeoutException,
            is java.net.SocketTimeoutException -> true
            else -> false
        }

    val title: StringResource
        get() = stringRes(R.string.swap_down_title)

    /** The body and, after a failed check, when it was checked; a second failed check says "checked again". */
    fun body(assets: SwapAssetsData): StringResource {
        val body = stringRes(R.string.swap_down_body)
        val checkedAt = assets.lastFailedCheckAt ?: return body
        val time = stringResByDateTime(checkedAt.atZone(ZoneId.systemDefault()), useFullFormat = false)
        val checked =
            if (assets.failedChecks > 1) {
                stringRes(R.string.swap_down_checked_again, time)
            } else {
                stringRes(R.string.swap_down_checked, time)
            }
        return body + "\n\n" + checked
    }
}
