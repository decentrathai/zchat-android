package co.electriccoin.zcash.ui.screen.chat.model

/**
 * R2-2 (a) — whether a string is a well-formed MAINNET Zcash Unified Address: human-readable part "u", all
 * lower case, Bech32 characters only, and a valid Bech32m checksum (ZIP 316 uses Bech32m without the 90
 * character limit). A pure check with no SDK call: it runs inside the inbound KEX handler, where an address
 * a stranger claims decides whether anything is offered to the user at all. It does not decode the
 * receivers; a string that passes can still hold no receiver we can pay, and the send then fails before
 * anything is spent.
 */
object UnifiedAddressCheck {
    private const val HRP = "u"
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3
    private const val CHECKSUM_LENGTH = 6
    private const val MAX_LENGTH = 1_000
    private const val MIN_DATA_LENGTH = 60
    private const val HRP_MIN_CHAR = 33
    private const val HRP_MAX_CHAR = 126
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /**
     * GPT-6 F4: the checksum splits at the LAST '1', so the "u1" prefix alone does not make the human-readable
     * part "u" ("u1x1…" has part "u1x"). The last '1' must be the one right after "u".
     */
    fun isMainnetUnifiedAddress(address: String): Boolean =
        address.length <= MAX_LENGTH &&
            address.startsWith(HRP + "1") &&
            address.lastIndexOf('1') == HRP.length &&
            address.length - HRP.length - 1 >= MIN_DATA_LENGTH + CHECKSUM_LENGTH &&
            hasBech32mChecksum(address)

    /** Bech32m (BIP 350) checksum over a lower-case "<hrp>1<data>" string, any human-readable part. */
    internal fun hasBech32mChecksum(value: String): Boolean {
        val separator = value.lastIndexOf('1')
        if (separator < 1 || value.length - separator - 1 < CHECKSUM_LENGTH) return false
        val hrp = value.substring(0, separator)
        if (hrp.any { it.code !in HRP_MIN_CHAR..HRP_MAX_CHAR || it in 'A'..'Z' }) return false
        val data = value.substring(separator + 1)
        val values = IntArray(data.length)
        for (i in data.indices) {
            val v = CHARSET.indexOf(data[i])
            if (v < 0) return false
            values[i] = v
        }
        return polymod(hrpExpand(hrp) + values) == BECH32M_CONST
    }

    private fun hrpExpand(hrp: String): IntArray = (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

    @Suppress("MagicNumber")
    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in GENERATOR.indices) {
                if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }
}
