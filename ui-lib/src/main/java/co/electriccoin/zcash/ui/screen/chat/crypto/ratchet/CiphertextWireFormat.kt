package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import java.util.Base64

/**
 * Serializer/parser for the ratcheted E2E wire format carried inside ZMSG v4 memos.
 *
 * Wire format:
 * ```
 * E2E1:<direction_hex>:<counter_hex>:<ciphertext_base64>
 * ```
 *
 * - `E2E1:` prefix distinguishes from legacy `E2E:` (unratcheted V2).
 * - `direction_hex`: 2 lowercase hex chars (`00` = lower→higher pubkey, `01` = higher→lower).
 * - `counter_hex`: 16 lowercase hex chars (u64 big-endian, zero-padded).
 * - `ciphertext_base64`: standard Base64 (RFC 4648, with `+`, `/`, and `=` padding).
 *
 * Dispatch rule in ChatViewModel: if memo starts with `E2E1:` → ratchet path;
 * if `E2E:` → legacy V2 path.
 */
object CiphertextWireFormat {

    private const val PREFIX = "E2E1:"
    private const val DIR_HEX_LEN = 2
    private const val COUNTER_HEX_LEN = 16

    fun serialize(ct: Ciphertext): String {
        val dirHex = "%02x".format(ct.direction)
        val counterHex = "%016x".format(ct.counter)
        val bodyBase64 = Base64.getEncoder().encodeToString(ct.bytes)
        return "$PREFIX$dirHex:$counterHex:$bodyBase64"
    }

    fun parse(wire: String): Ciphertext? {
        if (!wire.startsWith(PREFIX)) return null
        val body = wire.removePrefix(PREFIX)
        val parts = body.split(":", limit = 3)
        if (parts.size != 3) return null

        val dirHex = parts[0]
        val counterHex = parts[1]
        val base64 = parts[2]

        if (dirHex.length != DIR_HEX_LEN) return null
        if (counterHex.length != COUNTER_HEX_LEN) return null

        val direction = dirHex.toIntOrNull(16)?.toByte() ?: return null
        if (direction != 0x00.toByte() && direction != 0x01.toByte()) return null

        val counter = counterHex.toLongOrNull(16) ?: return null
        if (counter < 0) return null

        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return null
        }

        return Ciphertext(direction, counter, bytes)
    }

    fun isRatcheted(wire: String): Boolean = wire.startsWith(PREFIX)
}
