package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.BuildConfig

/**
 * AI-KEY-D (W-53): where the AI client goes and which service name the sign-in proof names.
 *
 * This is the debug file. It returns the two constants, unless the build was made with BOTH
 * `-PaiAuthBaseUrl=…` and `-PaiAuthAudience=…` (two build fields of the debug type only, empty by default). Only
 * then, and only here, the client may talk cleartext, and only to `http://127.0.0.1:<port>`, which `adb reverse`
 * leads to a server branch on the developer's PC. The release file holds the constants and no switch.
 */
internal object AiAuthEndpoint {
    const val DEFAULT_BASE_URL = "https://api.zsend.xyz"
    const val DEFAULT_SERVICE_NAME = "api.zsend.xyz"

    private val LOOPBACK = Regex("""http://127\.0\.0\.1:\d{1,5}""")

    private val switched: Boolean
        get() = BuildConfig.AI_AUTH_BASE_URL.isNotEmpty() && BuildConfig.AI_AUTH_AUDIENCE.isNotEmpty()

    val baseUrl: String
        get() = if (switched) BuildConfig.AI_AUTH_BASE_URL else DEFAULT_BASE_URL

    val serviceName: String
        get() = if (switched) BuildConfig.AI_AUTH_AUDIENCE else DEFAULT_SERVICE_NAME

    /** Cleartext only with the switch, and only to the loopback address with a port. */
    fun allowsCleartext(url: String): Boolean = switched && LOOPBACK.matches(url)
}
