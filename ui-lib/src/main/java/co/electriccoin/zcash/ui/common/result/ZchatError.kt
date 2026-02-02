package co.electriccoin.zcash.ui.common.result

/**
 * Domain-specific error hierarchy for ZCHAT.
 * Based on Boris Cherny's principles - make invalid states unrepresentable.
 *
 * All errors are sealed classes, ensuring exhaustive handling in when expressions.
 *
 * Usage:
 * ```kotlin
 * fun handleError(error: ZchatError): String = when (error) {
 *     is ZchatError.Network.NoConnection -> "Check your internet connection"
 *     is ZchatError.Network.Timeout -> "Request timed out after ${error.durationMs}ms"
 *     is ZchatError.Network.ServerError -> "Server error: ${error.message}"
 *     is ZchatError.Wallet.InsufficientFunds -> "Not enough ZEC"
 *     is ZchatError.Wallet.InvalidAddress -> "Invalid Zcash address"
 *     is ZchatError.Wallet.SyncFailed -> "Wallet sync failed"
 *     is ZchatError.Wallet.NotReady -> "Wallet is not ready yet"
 *     is ZchatError.Crypto.DecryptionFailed -> "Could not decrypt message"
 *     is ZchatError.Crypto.EncryptionFailed -> "Could not encrypt message"
 *     is ZchatError.Crypto.InvalidSignature -> "Invalid signature"
 *     is ZchatError.Crypto.KeyDerivationFailed -> "Key derivation failed"
 *     is ZchatError.Crypto.KeyNotFound -> "Encryption key not found"
 *     is ZchatError.Protocol.InvalidFormat -> "Invalid message format"
 *     is ZchatError.Protocol.UnsupportedVersion -> "Unsupported protocol version"
 *     is ZchatError.Protocol.MissingField -> "Missing required field"
 *     is ZchatError.Identity.NotFound -> "Identity not found"
 *     is ZchatError.Identity.AlreadyExists -> "Identity already exists"
 *     is ZchatError.Identity.SwitchFailed -> "Failed to switch identity"
 *     is ZchatError.Unknown -> "An unexpected error occurred"
 * }
 * ```
 */
sealed class ZchatError {

    /**
     * Human-readable error message.
     */
    abstract val message: String

    /**
     * Original cause if available.
     */
    open val cause: Throwable? = null

    // ============================================
    // Network Errors
    // ============================================

    /**
     * Network-related errors.
     */
    sealed class Network : ZchatError() {
        /**
         * No internet connection available.
         */
        data object NoConnection : Network() {
            override val message: String = "No internet connection"
        }

        /**
         * Request timed out.
         */
        data class Timeout(val durationMs: Long) : Network() {
            override val message: String = "Request timed out after ${durationMs}ms"
        }

        /**
         * Server returned an error.
         */
        data class ServerError(val code: Int, override val message: String) : Network()

        /**
         * Connection was refused.
         */
        data object ConnectionRefused : Network() {
            override val message: String = "Connection refused"
        }

        /**
         * DNS resolution failed.
         */
        data class DnsFailure(val hostname: String) : Network() {
            override val message: String = "Could not resolve host: $hostname"
        }
    }

    // ============================================
    // Wallet Errors
    // ============================================

    /**
     * Wallet-related errors.
     */
    sealed class Wallet : ZchatError() {
        /**
         * Not enough funds for the transaction.
         */
        data class InsufficientFunds(
            val required: Long,
            val available: Long
        ) : Wallet() {
            override val message: String = "Insufficient funds: need $required zatoshi, have $available"
        }

        /**
         * The provided address is invalid.
         */
        data class InvalidAddress(val address: String) : Wallet() {
            override val message: String = "Invalid Zcash address"
        }

        /**
         * Wallet synchronization failed.
         */
        data class SyncFailed(override val cause: Throwable) : Wallet() {
            override val message: String = "Wallet sync failed: ${cause.message}"
        }

        /**
         * Wallet is not ready (still syncing or not initialized).
         */
        data object NotReady : Wallet() {
            override val message: String = "Wallet is not ready"
        }

        /**
         * Transaction submission failed.
         */
        data class TransactionFailed(override val cause: Throwable) : Wallet() {
            override val message: String = "Transaction failed: ${cause.message}"
        }

        /**
         * Transaction was rejected by the network.
         */
        data class TransactionRejected(val reason: String) : Wallet() {
            override val message: String = "Transaction rejected: $reason"
        }
    }

    // ============================================
    // Crypto Errors
    // ============================================

    /**
     * Cryptography-related errors.
     */
    sealed class Crypto : ZchatError() {
        /**
         * Message decryption failed.
         */
        data class DecryptionFailed(val reason: String? = null) : Crypto() {
            override val message: String = "Decryption failed${reason?.let { ": $it" } ?: ""}"
        }

        /**
         * Message encryption failed.
         */
        data class EncryptionFailed(val reason: String? = null) : Crypto() {
            override val message: String = "Encryption failed${reason?.let { ": $it" } ?: ""}"
        }

        /**
         * Signature verification failed.
         */
        data object InvalidSignature : Crypto() {
            override val message: String = "Invalid signature"
        }

        /**
         * Key derivation failed.
         */
        data class KeyDerivationFailed(val reason: String) : Crypto() {
            override val message: String = "Key derivation failed: $reason"
        }

        /**
         * Required key not found.
         */
        data class KeyNotFound(val keyType: String) : Crypto() {
            override val message: String = "$keyType key not found"
        }

        /**
         * HKDF operation failed.
         */
        data class HkdfFailed(val reason: String) : Crypto() {
            override val message: String = "HKDF failed: $reason"
        }

        /**
         * ECIES operation failed.
         */
        data class EciesFailed(val reason: String) : Crypto() {
            override val message: String = "ECIES failed: $reason"
        }
    }

    // ============================================
    // Protocol Errors
    // ============================================

    /**
     * ZMSG Protocol-related errors.
     */
    sealed class Protocol : ZchatError() {
        /**
         * Message format is invalid.
         */
        data class InvalidFormat(val details: String) : Protocol() {
            override val message: String = "Invalid message format: $details"
        }

        /**
         * Protocol version is not supported.
         */
        data class UnsupportedVersion(val version: Int) : Protocol() {
            override val message: String = "Unsupported protocol version: $version"
        }

        /**
         * Required field is missing.
         */
        data class MissingField(val field: String) : Protocol() {
            override val message: String = "Missing required field: $field"
        }

        /**
         * Message type is unknown.
         */
        data class UnknownMessageType(val type: String) : Protocol() {
            override val message: String = "Unknown message type: $type"
        }

        /**
         * Conversation ID is invalid.
         */
        data class InvalidConversationId(val convId: String) : Protocol() {
            override val message: String = "Invalid conversation ID"
        }
    }

    // ============================================
    // Identity Errors
    // ============================================

    /**
     * Identity management errors.
     */
    sealed class Identity : ZchatError() {
        /**
         * Identity not found.
         */
        data class NotFound(val identityId: String) : Identity() {
            override val message: String = "Identity not found"
        }

        /**
         * Identity already exists.
         */
        data class AlreadyExists(val name: String) : Identity() {
            override val message: String = "Identity '$name' already exists"
        }

        /**
         * Failed to switch identity.
         */
        data class SwitchFailed(val reason: String) : Identity() {
            override val message: String = "Failed to switch identity: $reason"
        }

        /**
         * Failed to create identity.
         */
        data class CreationFailed(val reason: String) : Identity() {
            override val message: String = "Failed to create identity: $reason"
        }
    }

    // ============================================
    // Group Errors
    // ============================================

    /**
     * Group chat errors.
     */
    sealed class Group : ZchatError() {
        /**
         * Group not found.
         */
        data class NotFound(val groupId: String) : Group() {
            override val message: String = "Group not found"
        }

        /**
         * Not a member of the group.
         */
        data class NotMember(val groupId: String) : Group() {
            override val message: String = "Not a member of this group"
        }

        /**
         * Failed to join group.
         */
        data class JoinFailed(val reason: String) : Group() {
            override val message: String = "Failed to join group: $reason"
        }

        /**
         * Failed to leave group.
         */
        data class LeaveFailed(val reason: String) : Group() {
            override val message: String = "Failed to leave group: $reason"
        }

        /**
         * Group key decryption failed.
         */
        data class KeyDecryptionFailed(val reason: String) : Group() {
            override val message: String = "Group key decryption failed: $reason"
        }
    }

    // ============================================
    // Unknown Error
    // ============================================

    /**
     * Unknown or unexpected error.
     */
    data class Unknown(
        override val message: String = "An unexpected error occurred",
        override val cause: Throwable? = null
    ) : ZchatError()

    companion object {
        /**
         * Create an error from an exception.
         */
        fun fromException(e: Throwable): ZchatError = when (e) {
            is java.net.UnknownHostException -> Network.DnsFailure(e.message ?: "unknown")
            is java.net.SocketTimeoutException -> Network.Timeout(30000)
            is java.net.ConnectException -> Network.ConnectionRefused
            is java.io.IOException -> Network.NoConnection
            is javax.crypto.BadPaddingException -> Crypto.DecryptionFailed("Invalid padding")
            is javax.crypto.IllegalBlockSizeException -> Crypto.DecryptionFailed("Invalid block size")
            is java.security.InvalidKeyException -> Crypto.KeyDerivationFailed("Invalid key")
            else -> Unknown(e.message ?: "Unknown error", e)
        }
    }
}

/**
 * Type alias for common result types.
 */
typealias NetworkResult<T> = ZchatResult<T, ZchatError.Network>
typealias WalletResult<T> = ZchatResult<T, ZchatError.Wallet>
typealias CryptoResult<T> = ZchatResult<T, ZchatError.Crypto>
typealias ProtocolResult<T> = ZchatResult<T, ZchatError.Protocol>
typealias IdentityResult<T> = ZchatResult<T, ZchatError.Identity>
typealias GroupResult<T> = ZchatResult<T, ZchatError.Group>
