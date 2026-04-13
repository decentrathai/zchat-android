package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

/**
 * Ratcheted ciphertext produced by [E2ERatchet.encrypt] and consumed by [E2ERatchet.decrypt].
 *
 * @property direction Chain identifier. 0x00 = lower→higher pubkey, 0x01 = higher→lower.
 * @property counter Monotonic message index within the direction's chain (starts at 0).
 * @property bytes AES-256-GCM output (ciphertext || 16-byte auth tag).
 */
data class Ciphertext(
    val direction: Byte,
    val counter: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ciphertext) return false
        return direction == other.direction &&
            counter == other.counter &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = direction.toInt()
        result = 31 * result + counter.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
