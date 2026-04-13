package co.electriccoin.zcash.ui.screen.chat.crypto

/**
 * Shield status for a conversation's Quantum Shield PSK.
 */
enum class QuantumShieldStatus {
    /** No PSK exchange initiated. */
    NONE,
    /** Our secret generated, waiting for peer's secret via QR exchange. */
    PENDING,
    /** Both secrets exchanged, PSK derived and active. */
    ACTIVE,
}

/**
 * Immutable state for the Quantum Shield per conversation. Transitions:
 *
 * ```
 * NONE → generateOurSecret() → PENDING
 * PENDING → addPeerSecret() → ACTIVE (PSK derived)
 * ACTIVE → reset() → NONE
 * ```
 *
 * The PSK is derived via [QuantumShield.derivePSK] which is order-independent
 * (both parties reach the same PSK regardless of who scanned first).
 */
data class QuantumShieldState(
    val ourSecret: ByteArray? = null,
    val peerSecret: ByteArray? = null,
    val psk: ByteArray? = null,
) {
    val status: QuantumShieldStatus
        get() = when {
            psk != null -> QuantumShieldStatus.ACTIVE
            ourSecret != null -> QuantumShieldStatus.PENDING
            else -> QuantumShieldStatus.NONE
        }

    /** Generate our 32-byte random secret. Transitions NONE → PENDING. */
    fun generateOurSecret(): QuantumShieldState =
        copy(ourSecret = QuantumShield.generateRandom())

    /**
     * Add the peer's secret (scanned from their QR code). If our secret is already
     * present, derives the PSK and transitions to ACTIVE.
     */
    fun addPeerSecret(secret: ByteArray): QuantumShieldState {
        val newState = copy(peerSecret = secret)
        return if (newState.ourSecret != null) {
            newState.copy(psk = QuantumShield.derivePSK(newState.ourSecret, secret))
        } else {
            newState
        }
    }

    /** Reset to NONE — clears all secrets and PSK. */
    fun reset(): QuantumShieldState = QuantumShieldState()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuantumShieldState) return false
        return ourSecret.contentEqualsNullable(other.ourSecret) &&
            peerSecret.contentEqualsNullable(other.peerSecret) &&
            psk.contentEqualsNullable(other.psk)
    }

    override fun hashCode(): Int {
        var result = ourSecret?.contentHashCode() ?: 0
        result = 31 * result + (peerSecret?.contentHashCode() ?: 0)
        result = 31 * result + (psk?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null) other == null else other != null && this.contentEquals(other)
