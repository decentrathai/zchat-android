package co.electriccoin.zcash.ui.common.datasource

/**
 * T1b-3a (T1 finding 4 / F9 diagnostic): a log line for an SDK refusal, for debug builds only (every Twig call is
 * stripped from the release build by spackle's proguard rule).
 *
 * Each exception in the cause chain is named with its message. A long token, which is how an address, a key, a
 * memo or a txid looks, is replaced, so the amounts the SDK names ("have 582002, need 587002") stay readable and
 * no address reaches the log. The line is cut at [MAX_LOG_CHARS].
 */
internal fun sdkRefusalLogText(e: Throwable): String =
    generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CHAIN)
        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${redactLongTokens(it.message.orEmpty())}" }
        .take(MAX_LOG_CHARS)

/** 20 or more address, key, memo or txid characters in a row; an amount in zatoshi has at most 16 digits. */
private val LONG_TOKEN = Regex("""[A-Za-z0-9+/=_-]{20,}""")

/** A Rust or Kotlin type name such as "InsufficientFunds" or "ChangeRequired": capitalised words only, kept. */
private val TYPE_NAME = Regex("""(?:[A-Z][a-z]+)+""")

private fun redactLongTokens(message: String): String =
    LONG_TOKEN.replace(message) { if (TYPE_NAME.matches(it.value)) it.value else "<${it.value.length} chars>" }

private const val MAX_CHAIN = 8
private const val MAX_LOG_CHARS = 1_500
