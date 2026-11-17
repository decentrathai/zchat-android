package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlin.random.Random

/**
 * Synthetic Bech32m strings shaped like Zcash Unified Addresses, for tests that need an address to pass (or
 * fail) the checksum a real one carries. The payload is random bytes, not a real receiver encoding, so no
 * wallet can hold these addresses.
 */
internal object SyntheticUnifiedAddress {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** A mainnet-shaped ("u1…") address with a valid Bech32m checksum, deterministic in [seed]. */
    fun mainnet(seed: Int): String = encode("u", Random(seed).nextBytes(PAYLOAD_BYTES))

    /** The same shape on the test network ("utest1…"). */
    fun testnet(seed: Int): String = encode("utest", Random(seed).nextBytes(PAYLOAD_BYTES))

    /** [mainnet] with its last character changed, so the checksum no longer holds. */
    fun mainnetBadChecksum(seed: Int): String {
        val good = mainnet(seed)
        val last = good.last()
        val swapped = CHARSET[(CHARSET.indexOf(last) + 1) % CHARSET.length]
        return good.dropLast(1) + swapped
    }

    private fun encode(
        hrp: String,
        payload: ByteArray
    ): String {
        val data = toFiveBit(payload)
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor BECH32M_CONST
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
    }

    private fun toFiveBit(bytes: ByteArray): IntArray {
        val out = mutableListOf<Int>()
        var acc = 0
        var bits = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out += (acc ushr bits) and 31
            }
        }
        if (bits > 0) out += (acc shl (5 - bits)) and 31
        return out.toIntArray()
    }

    private fun hrpExpand(hrp: String): IntArray =
        (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private const val PAYLOAD_BYTES = 100
}
