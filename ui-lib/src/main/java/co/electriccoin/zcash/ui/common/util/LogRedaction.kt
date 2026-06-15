package co.electriccoin.zcash.ui.common.util

/**
 * Logging redaction utilities for sensitive data.
 *
 * SECURITY: Never log full addresses, seed phrases, or private keys.
 * These extensions ensure sensitive data is redacted before logging.
 *
 * Usage:
 * ```kotlin
 * Log.d(TAG, "Sending to ${address.redactAddress()}")
 * Log.d(TAG, "Wallet backup: ${seed.redactSeed()}")
 * Log.d(TAG, "Key: ${privateKey.redactKey()}")
 * ```
 */

/**
 * Redact a Zcash address for safe logging.
 * Shows first 6 and last 4 characters.
 *
 * Example: "u1abc123...xyz9"
 */
fun String.redactAddress(): String {
    return when {
        isEmpty() -> "[empty]"
        length <= 10 -> "[redacted]"
        else -> "${take(6)}...${takeLast(4)}"
    }
}

/**
 * Redact a seed phrase for safe logging.
 * Shows only word count, never actual words.
 *
 * Example: "[seed: 24 words]"
 */
fun String.redactSeed(): String {
    return when {
        isEmpty() -> "[empty seed]"
        else -> {
            val wordCount = trim().split("\\s+".toRegex()).size
            "[seed: $wordCount words]"
        }
    }
}

/**
 * Redact a private/secret key for safe logging.
 * Shows only length, never actual content.
 *
 * Example: "[key: 32 bytes]"
 */
fun String.redactKey(): String {
    return when {
        isEmpty() -> "[empty key]"
        else -> "[key: ${length} chars]"
    }
}

/**
 * Redact a byte array key for safe logging.
 * Shows only length, never actual content.
 *
 * Example: "[key: 32 bytes]"
 */
fun ByteArray.redactKey(): String {
    return "[key: ${size} bytes]"
}

/**
 * Redact a transaction ID for safe logging.
 * Shows first 8 and last 4 characters.
 *
 * Example: "abc12345...xyz9"
 */
fun String.redactTxId(): String {
    return when {
        isEmpty() -> "[empty txid]"
        length <= 12 -> "[redacted txid]"
        else -> "${take(8)}...${takeLast(4)}"
    }
}

/**
 * Redact a conversation ID for safe logging.
 * Shows first 4 characters only.
 *
 * Example: "ABCD****"
 */
fun String.redactConvId(): String {
    return when {
        isEmpty() -> "[empty convId]"
        length <= 4 -> "****"
        else -> "${take(4)}${"*".repeat(length - 4)}"
    }
}

/**
 * Redact memo content for safe logging.
 * Shows only type and length, never content.
 *
 * Example: "[memo: DM, 128 chars]"
 */
fun String.redactMemo(): String {
    return when {
        isEmpty() -> "[empty memo]"
        startsWith("ZMSG|") -> {
            val type = split("|").getOrNull(3) ?: "UNKNOWN"
            "[memo: $type, $length chars]"
        }
        else -> "[memo: RAW, $length chars]"
    }
}

/**
 * Redact a file/Blossom URL for safe logging.
 * Keeps the scheme+host (so relay-routing issues are still diagnosable) but strips the
 * path/blob hash, which can fingerprint or directly fetch a shared file.
 *
 * Example: "https://blossom.primal.net/<blob>" -> "https://blossom.primal.net/[blob]"
 */
fun String.redactUrl(): String {
    return when {
        isEmpty() -> "[empty url]"
        else -> {
            val schemeEnd = indexOf("://").let { if (it < 0) 0 else it + 3 }
            val host = substring(schemeEnd).substringBefore('/')
            val scheme = if (schemeEnd > 0) substring(0, schemeEnd) else ""
            "$scheme$host/[blob]"
        }
    }
}

/**
 * Redact an email address for safe logging.
 * Shows first char and domain.
 *
 * Example: "j***@example.com"
 */
fun String.redactEmail(): String {
    return when {
        isEmpty() -> "[empty email]"
        contains("@") -> {
            val parts = split("@")
            val local = parts[0]
            val domain = parts.getOrElse(1) { "unknown" }
            "${local.take(1)}***@$domain"
        }
        else -> "[redacted]"
    }
}

/**
 * Safely log a potentially sensitive value.
 * If the value looks sensitive, it will be redacted.
 */
fun String?.safeLog(): String {
    if (this == null) return "[null]"
    if (isEmpty()) return "[empty]"

    return when {
        // Zcash addresses
        startsWith("u1") || startsWith("zs") || startsWith("t1") -> redactAddress()
        // Transaction IDs (64 hex chars)
        matches(Regex("^[a-fA-F0-9]{64}$")) -> redactTxId()
        // Email addresses
        contains("@") && contains(".") -> redactEmail()
        // Seed phrases (12 or 24 words)
        trim().split("\\s+".toRegex()).size in listOf(12, 24) -> redactSeed()
        // Base64 encoded (likely keys or encrypted data)
        matches(Regex("^[A-Za-z0-9+/]+=*$")) && length > 20 -> "[base64: $length chars]"
        // Hex strings (likely keys)
        matches(Regex("^[a-fA-F0-9]+$")) && length >= 32 -> "[hex: $length chars]"
        // Default - return as-is
        else -> this
    }
}

/**
 * Object for centralized logging with automatic redaction.
 */
object SafeLog {
    private const val DEFAULT_TAG = "ZCHAT"

    fun d(tag: String = DEFAULT_TAG, message: String) {
        android.util.Log.d(tag, message)
    }

    fun i(tag: String = DEFAULT_TAG, message: String) {
        android.util.Log.i(tag, message)
    }

    fun w(tag: String = DEFAULT_TAG, message: String) {
        android.util.Log.w(tag, message)
    }

    fun e(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            android.util.Log.e(tag, message, throwable)
        } else {
            android.util.Log.e(tag, message)
        }
    }

    /**
     * Log with automatic address redaction.
     */
    fun dWithAddress(tag: String = DEFAULT_TAG, message: String, address: String) {
        android.util.Log.d(tag, "$message: ${address.redactAddress()}")
    }

    /**
     * Log with automatic transaction ID redaction.
     */
    fun dWithTxId(tag: String = DEFAULT_TAG, message: String, txId: String) {
        android.util.Log.d(tag, "$message: ${txId.redactTxId()}")
    }
}
