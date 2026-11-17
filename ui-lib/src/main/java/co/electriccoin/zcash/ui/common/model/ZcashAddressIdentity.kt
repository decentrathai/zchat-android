package co.electriccoin.zcash.ui.common.model

import java.math.BigInteger
import java.security.MessageDigest

/*
 * R3-1 — whether two address strings are the same Zcash address, by decoding both, never by comparing text.
 *
 * Zcash addresses come in two encodings:
 *  - Bech32 (BIP 173: Sapling "zs…") and Bech32m (BIP 350: Unified "u…", TEX "tex…"). Case-insensitive but
 *    never mixed-case; an all-upper-case string is the same address as its lower case, and QR codes use
 *    upper case because it fits the smaller alphanumeric mode. Unified addresses have no length limit.
 *  - Base58Check (transparent "t1…"/"t3…"). Case-sensitive; the last 4 bytes are the first 4 bytes of
 *    SHA-256(SHA-256(payload)).
 *
 * Each string is decoded to its variant, human-readable part and data (Bech32/Bech32m, checksum verified)
 * or to its payload bytes (Base58Check, checksum verified). Two strings are the same address only when both
 * decode and the decoded values are equal. A string that decodes as neither is not an address, and is never
 * the same as anything, itself included (fail closed). Pure Kotlin, no SDK call, so it runs on the unit-test
 * JVM; it does not check that the address belongs to any network or that its receivers can be paid.
 */

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private const val BECH32_CONST = 1
private const val BECH32M_CONST = 0x2bc830a3
private const val BECH32_CHECKSUM_LENGTH = 6
private const val HRP_MIN_CHAR = 33
private const val HRP_MAX_CHAR = 126
private val BECH32_GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
private const val BECH32_TOP_SHIFT = 25
private const val BECH32_LOW_MASK = 0x1ffffff
private const val BECH32_BITS = 5
private const val BECH32_HIGH_SHIFT = 5
private const val BECH32_LOW_BITS = 31

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val BASE58_RADIX = 58L
private const val BASE58_CHECKSUM_LENGTH = 4

/** Longer than any Zcash address (a Unified Address with every receiver is well under this). */
private const val MAX_ADDRESS_LENGTH = 2_000

/** True only when [a] and [b] both decode and are the same address. */
internal fun sameZcashAddress(
    a: String,
    b: String
): Boolean {
    val decodedA = decodeZcashAddress(a) ?: return false
    val decodedB = decodeZcashAddress(b) ?: return false
    return decodedA == decodedB
}

/**
 * The decoded identity of [address]: "bech32:<hrp>:<data values>" / "bech32m:…" (checksum dropped) or
 * "base58:<payload hex>" (checksum dropped). Null when it decodes as neither.
 */
internal fun decodeZcashAddress(address: String): String? {
    if (address.isEmpty() || address.length > MAX_ADDRESS_LENGTH) return null
    return decodeBech32(address) ?: decodeBase58Check(address)
}

@Suppress("ReturnCount")
private fun decodeBech32(address: String): String? {
    val hasLower = address.any { it in 'a'..'z' }
    val hasUpper = address.any { it in 'A'..'Z' }
    if (hasLower && hasUpper) return null
    val value = address.lowercase()
    val separator = value.lastIndexOf('1')
    if (separator < 1 || value.length - separator - 1 < BECH32_CHECKSUM_LENGTH) return null
    val hrp = value.substring(0, separator)
    if (hrp.any { it.code !in HRP_MIN_CHAR..HRP_MAX_CHAR }) return null
    val data = IntArray(value.length - separator - 1)
    for (i in data.indices) {
        val v = BECH32_CHARSET.indexOf(value[separator + 1 + i])
        if (v < 0) return null
        data[i] = v
    }
    val variant =
        when (bech32Polymod(bech32HrpExpand(hrp) + data)) {
            BECH32_CONST -> "bech32"
            BECH32M_CONST -> "bech32m"
            else -> return null
        }
    val payload = data.copyOf(data.size - BECH32_CHECKSUM_LENGTH).joinToString(",")
    return "$variant:$hrp:$payload"
}

internal fun bech32HrpExpand(hrp: String): IntArray =
    (hrp.map { it.code ushr BECH32_HIGH_SHIFT } + 0 + hrp.map { it.code and BECH32_LOW_BITS }).toIntArray()

internal fun bech32Polymod(values: IntArray): Int {
    var chk = 1
    for (v in values) {
        val top = chk ushr BECH32_TOP_SHIFT
        chk = ((chk and BECH32_LOW_MASK) shl BECH32_BITS) xor v
        for (i in BECH32_GENERATOR.indices) {
            if ((top ushr i) and 1 == 1) chk = chk xor BECH32_GENERATOR[i]
        }
    }
    return chk
}

@Suppress("ReturnCount")
private fun decodeBase58Check(address: String): String? {
    var number = BigInteger.ZERO
    val radix = BigInteger.valueOf(BASE58_RADIX)
    for (c in address) {
        val digit = BASE58_ALPHABET.indexOf(c)
        if (digit < 0) return null
        number = number.multiply(radix).add(BigInteger.valueOf(digit.toLong()))
    }
    val leadingZeros = address.takeWhile { it == BASE58_ALPHABET[0] }.length
    val magnitude = number.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
    val bytes = ByteArray(leadingZeros) + magnitude
    if (bytes.size <= BASE58_CHECKSUM_LENGTH) return null
    val payload = bytes.copyOf(bytes.size - BASE58_CHECKSUM_LENGTH)
    val checksum = bytes.copyOfRange(bytes.size - BASE58_CHECKSUM_LENGTH, bytes.size)
    val sha256 = MessageDigest.getInstance("SHA-256")
    val expected = sha256.digest(sha256.digest(payload)).copyOf(BASE58_CHECKSUM_LENGTH)
    if (!MessageDigest.isEqual(checksum, expected)) return null
    return "base58:" + payload.joinToString("") { "%02x".format(it) }
}
