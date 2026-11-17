package co.electriccoin.zcash.ui.screen.ai

/**
 * AI-KEY-D (W-53): where the AI client goes and which service name the sign-in proof names.
 *
 * This is the release file: the two constants and nothing else. The object exists a second time for the debug
 * build type, where a build made for a local check can point the client elsewhere; a release build cannot read
 * that switch, because this file does not hold it. Shared code names only the object.
 */
internal object AiAuthEndpoint {
    const val DEFAULT_BASE_URL = "https://api.zsend.xyz"
    const val DEFAULT_SERVICE_NAME = "api.zsend.xyz"

    val baseUrl: String
        get() = DEFAULT_BASE_URL

    val serviceName: String
        get() = DEFAULT_SERVICE_NAME

    /** A release build talks over TLS only. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun allowsCleartext(url: String): Boolean = false
}
