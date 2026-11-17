package co.electriccoin.zcash.ui.common.model

import java.util.concurrent.ConcurrentHashMap

/*
 * ZC-1082 (FIRST-CONTACT-SPEC N-13, ZC-862) — the receivers of a Zcash address (ZIP 316).
 *
 * After a restore the wallet library names each own send by the single receiver its note was paid to (a short
 * single-receiver address, `zcash_client_backend` `receiver.to_zcash_address`), never by the address text the app sent
 * to. Address texts cannot be compared then; receivers can. A Unified Address is Bech32m over F4Jumble(items || padding),
 * each item a CompactSize typecode, a CompactSize length and the value; the padding is the HRP in 16 zero-filled bytes.
 * A Sapling address (Bech32), a transparent one (Base58Check) and a TEX address (Bech32m) carry one receiver each.
 *
 * Pure Kotlin, no SDK call (it runs on the unit-test JVM). Anything malformed decodes to null, and null is never the
 * same as, or within, anything. BLAKE2b (RFC 7693) is written here because no library on the app's classpath has it;
 * it is pinned by the RFC's vectors and by vectors from an independent implementation (ZcashReceiversTest).
 */

/** One receiver: its network ("main", "test"), ZIP 316 typecode (0 P2PKH, 1 P2SH, 2 Sapling, 3 Orchard, …) and raw bytes. */
internal data class ZcashReceiver(
    val network: String,
    val typecode: Int,
    val hex: String,
)

internal object ZcashReceivers {
    private const val PADDING_LENGTH = 16
    private const val P2PKH = 0
    private const val P2SH = 1
    private const val SAPLING = 2
    private const val ORCHARD = 3
    private const val TRANSPARENT_LENGTH = 20
    private const val SHIELDED_LENGTH = 43
    private const val METADATA_MIN = 0xC0
    private const val MUST_UNDERSTAND_MIN = 0xE0
    private const val METADATA_MAX = 0xFC
    private const val MAX_TYPECODE = 0x02000000L
    private const val CACHE_LIMIT = 1_024

    private val UNIFIED_HRPS =
        mapOf(
            "u" to "main",
            "utest" to "test",
            "uregtest" to "regtest",
            "zu" to "main",
            "zutest" to "test",
            "zuregtest" to "regtest",
            "tu" to "main",
            "tutest" to "test",
            "turegtest" to "regtest",
        )
    private val TRANSPARENT_ONLY_HRPS = setOf("tu", "tutest", "turegtest")
    private val SAPLING_HRPS = mapOf("zs" to "main", "ztestsapling" to "test", "zregtestsapling" to "regtest")
    private val TEX_HRPS = mapOf("tex" to "main", "textest" to "test", "texregtest" to "regtest")

    /** Base58Check prefix (hex) to network and typecode. Testnet and regtest share their prefixes. */
    private val TRANSPARENT_PREFIXES =
        mapOf(
            "1cb8" to ("main" to P2PKH),
            "1cbd" to ("main" to P2SH),
            "1d25" to ("test" to P2PKH),
            "1cba" to ("test" to P2SH),
        )

    /** Decoded results by address text; an empty set stands for "malformed" (a real result is never empty). */
    private val cache = ConcurrentHashMap<String, Set<ZcashReceiver>>()

    /** The receivers of [address], or null when it is not a well-formed Zcash address. */
    fun of(address: String): Set<ZcashReceiver>? {
        cache[address]?.let { return it.ifEmpty { null } }
        val decoded = decode(address)
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[address] = decoded ?: emptySet()
        return decoded
    }

    /** True when [address] and [container] both decode and every receiver of [address] is one of [container]'s. */
    fun within(
        address: String,
        container: String,
    ): Boolean {
        val inner = of(address) ?: return false
        val outer = of(container) ?: return false
        return outer.containsAll(inner)
    }

    /**
     * The Unified Address with [receivers] (typecode to raw bytes, in ascending typecode order) under [hrp], or null when
     * they do not make a valid one.
     */
    fun unifiedAddress(
        hrp: String,
        receivers: List<Pair<Int, ByteArray>>,
    ): String? {
        if (hrp !in UNIFIED_HRPS || receivers.isEmpty()) return null
        val raw = java.io.ByteArrayOutputStream()
        for ((typecode, bytes) in receivers) {
            raw.write(compactSize(typecode.toLong()))
            raw.write(compactSize(bytes.size.toLong()))
            raw.write(bytes)
        }
        raw.write(hrp.toByteArray(Charsets.US_ASCII).copyOf(PADDING_LENGTH))
        val jumbled = F4Jumble.jumble(raw.toByteArray()) ?: return null
        val data = convertBits(jumbled.map { it.toInt() and 0xff }.toIntArray(), from = 8, to = 5, pad = true) ?: return null
        val address = encodeBech32m(hrp, data)
        return address.takeIf { of(it) != null }
    }

    @Suppress("ReturnCount")
    private fun decode(address: String): Set<ZcashReceiver>? {
        val identity = decodeZcashAddress(address) ?: return null
        val parts = identity.split(':', limit = 3)
        return when (parts[0]) {
            "base58" -> decodeTransparent(parts[1])
            "bech32", "bech32m" -> {
                if (parts.size != 3 || parts[2].isEmpty()) return null
                val values = parts[2].split(',').map { it.toIntOrNull() ?: return null }.toIntArray()
                val bytes = convertBits(values, from = 5, to = 8, pad = false) ?: return null
                val hrp = parts[1]
                if (parts[0] == "bech32") decodeSapling(hrp, bytes) else decodeUnifiedOrTex(hrp, bytes)
            }
            else -> null
        }
    }

    private fun decodeTransparent(payloadHex: String): Set<ZcashReceiver>? {
        if (payloadHex.length != 2 * (2 + TRANSPARENT_LENGTH)) return null
        val (network, typecode) = TRANSPARENT_PREFIXES[payloadHex.substring(0, 4)] ?: return null
        return setOf(ZcashReceiver(network, typecode, payloadHex.substring(4)))
    }

    private fun decodeSapling(
        hrp: String,
        bytes: ByteArray,
    ): Set<ZcashReceiver>? {
        val network = SAPLING_HRPS[hrp] ?: return null
        if (bytes.size != SHIELDED_LENGTH) return null
        return setOf(ZcashReceiver(network, SAPLING, hex(bytes)))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun decodeUnifiedOrTex(
        hrp: String,
        bytes: ByteArray,
    ): Set<ZcashReceiver>? {
        TEX_HRPS[hrp]?.let { network ->
            if (bytes.size != TRANSPARENT_LENGTH) return null
            return setOf(ZcashReceiver(network, P2PKH, hex(bytes)))
        }
        val network = UNIFIED_HRPS[hrp] ?: return null
        if (hrp.length > PADDING_LENGTH) return null
        val plain = F4Jumble.unjumble(bytes) ?: return null
        val itemsEnd = plain.size - PADDING_LENGTH
        val padding = plain.copyOfRange(itemsEnd, plain.size)
        if (!padding.contentEquals(hrp.toByteArray(Charsets.US_ASCII).copyOf(PADDING_LENGTH))) return null
        val receivers = LinkedHashSet<ZcashReceiver>()
        var offset = 0
        var previous = -1L
        var hasShielded = false
        while (offset < itemsEnd) {
            val (typecode, afterType) = readCompactSize(plain, offset, itemsEnd) ?: return null
            val (length, afterLength) = readCompactSize(plain, afterType, itemsEnd) ?: return null
            if (typecode > MAX_TYPECODE || typecode <= previous) return null
            if (length > itemsEnd - afterLength) return null
            val value = plain.copyOfRange(afterLength, afterLength + length.toInt())
            offset = afterLength + length.toInt()
            previous = typecode
            val code = typecode.toInt()
            when {
                code in MUST_UNDERSTAND_MIN..METADATA_MAX -> return null
                code in METADATA_MIN until MUST_UNDERSTAND_MIN -> continue
                (code == P2PKH || code == P2SH) && length.toInt() != TRANSPARENT_LENGTH -> return null
                (code == SAPLING || code == ORCHARD) && length.toInt() != SHIELDED_LENGTH -> return null
            }
            if (code == P2SH && receivers.any { it.typecode == P2PKH }) return null
            if (code != P2PKH && code != P2SH) hasShielded = true
            receivers += ZcashReceiver(network, code, hex(value))
        }
        if (receivers.isEmpty()) return null
        if (!hasShielded && hrp !in TRANSPARENT_ONLY_HRPS) return null
        return receivers
    }

    /** A CompactSize at [offset] (canonical only), with the offset after it; null when it does not fit before [end]. */
    @Suppress("ReturnCount", "MagicNumber")
    private fun readCompactSize(
        bytes: ByteArray,
        offset: Int,
        end: Int,
    ): Pair<Long, Int>? {
        if (offset >= end) return null
        val first = bytes[offset].toInt() and 0xff
        val width =
            when (first) {
                0xfd -> 2
                0xfe -> 4
                0xff -> 8
                else -> return first.toLong() to offset + 1
            }
        if (end - offset - 1 < width) return null
        var value = 0L
        for (i in width - 1 downTo 0) value = (value shl 8) or (bytes[offset + 1 + i].toLong() and 0xff)
        val minimum =
            when (width) {
                2 -> 0xfdL
                4 -> 0x10000L
                else -> 0x100000000L
            }
        if (value < minimum || value < 0) return null
        return value to offset + 1 + width
    }

    @Suppress("MagicNumber")
    private fun compactSize(value: Long): ByteArray =
        when {
            value < 0xfd -> byteArrayOf(value.toByte())
            value <= 0xffff -> byteArrayOf(0xfd.toByte(), value.toByte(), (value ushr 8).toByte())
            else -> byteArrayOf(0xfe.toByte()) + ByteArray(4) { (value ushr (8 * it)).toByte() }
        }

    /** BIP 173 convertbits; without [pad], leftover bits must be fewer than [from] and zero. */
    @Suppress("ReturnCount")
    private fun convertBits(
        values: IntArray,
        from: Int,
        to: Int,
        pad: Boolean,
    ): ByteArray? {
        var acc = 0
        var bits = 0
        val maxValue = (1 shl to) - 1
        val out = java.io.ByteArrayOutputStream()
        for (v in values) {
            if (v < 0 || v ushr from != 0) return null
            acc = ((acc shl from) or v) and 0xffff
            bits += from
            while (bits >= to) {
                bits -= to
                out.write((acc ushr bits) and maxValue)
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc shl (to - bits)) and maxValue)
        } else if (bits >= from || ((acc shl (to - bits)) and maxValue) != 0) {
            return null
        }
        return out.toByteArray()
    }

    private fun encodeBech32m(
        hrp: String,
        data: ByteArray,
    ): String {
        val values = data.map { it.toInt() }.toIntArray()
        val polymod = bech32Polymod(bech32HrpExpand(hrp) + values + IntArray(BECH32M_CHECKSUM_LENGTH)) xor BECH32M_CHECKSUM_CONST
        val checksum = IntArray(BECH32M_CHECKSUM_LENGTH) { (polymod ushr (5 * (BECH32M_CHECKSUM_LENGTH - 1 - it))) and 31 }
        return hrp + "1" + (values + checksum).joinToString("") { BECH32M_CHARSET[it].toString() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private const val BECH32M_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CHECKSUM_CONST = 0x2bc830a3
    private const val BECH32M_CHECKSUM_LENGTH = 6
}

/** F4Jumble and its inverse (ZIP 316), over BLAKE2b-512 with the "UA_F4Jumble_H" / "UA_F4Jumble_G" personalisations. */
internal object F4Jumble {
    private const val MIN_LENGTH = 38
    private const val MAX_LENGTH = 4_194_368
    private const val OUT = 64
    private val H_PERSONAL = "UA_F4Jumble_H".toByteArray(Charsets.US_ASCII)
    private val G_PERSONAL = "UA_F4Jumble_G".toByteArray(Charsets.US_ASCII)

    /** F4Jumble of [message], or null when its length is outside 38..4,194,368 bytes. */
    fun jumble(message: ByteArray): ByteArray? {
        if (message.size !in MIN_LENGTH..MAX_LENGTH) return null
        val m = message.copyOf()
        val left = minOf(OUT, m.size / 2)
        gRound(m, left, 0)
        hRound(m, left, 0)
        gRound(m, left, 1)
        hRound(m, left, 1)
        return m
    }

    /** F4Jumble⁻¹ of [message], or null when its length is outside 38..4,194,368 bytes. */
    fun unjumble(message: ByteArray): ByteArray? {
        if (message.size !in MIN_LENGTH..MAX_LENGTH) return null
        val m = message.copyOf()
        val left = minOf(OUT, m.size / 2)
        hRound(m, left, 1)
        gRound(m, left, 1)
        hRound(m, left, 0)
        gRound(m, left, 0)
        return m
    }

    private fun hRound(
        m: ByteArray,
        left: Int,
        i: Int,
    ) {
        val hash = Blake2b.digest(m.copyOfRange(left, m.size), outputLength = left, personal = H_PERSONAL + byteArrayOf(i.toByte(), 0, 0))
        for (k in 0 until left) m[k] = (m[k].toInt() xor hash[k].toInt()).toByte()
    }

    private fun gRound(
        m: ByteArray,
        left: Int,
        i: Int,
    ) {
        val l = m.copyOf(left)
        val rightLength = m.size - left
        for (j in 0 until (rightLength + OUT - 1) / OUT) {
            val personal = G_PERSONAL + byteArrayOf(i.toByte(), j.toByte(), (j ushr 8).toByte())
            val hash = Blake2b.digest(l, outputLength = OUT, personal = personal)
            val start = left + j * OUT
            for (k in 0 until minOf(OUT, m.size - start)) m[start + k] = (m[start + k].toInt() xor hash[k].toInt()).toByte()
        }
    }
}

/** BLAKE2b (RFC 7693) with an optional key and the 16-byte personalisation of the parameter block. */
@Suppress("MagicNumber")
internal object Blake2b {
    private const val BLOCK = 128
    private const val ROUNDS = 12
    private val IV =
        longArrayOf(
            0x6a09e667f3bcc908UL.toLong(),
            0xbb67ae8584caa73bUL.toLong(),
            0x3c6ef372fe94f82bUL.toLong(),
            0xa54ff53a5f1d36f1UL.toLong(),
            0x510e527fade682d1UL.toLong(),
            0x9b05688c2b3e6c1fUL.toLong(),
            0x1f83d9abfb41bd6bUL.toLong(),
            0x5be0cd19137e2179UL.toLong(),
        )
    private val SIGMA =
        arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

    /** BLAKE2b of [input], [outputLength] 1..64 bytes, [key] 0..64 bytes, [personal] 16 bytes or none. */
    fun digest(
        input: ByteArray,
        outputLength: Int = 64,
        key: ByteArray = ByteArray(0),
        personal: ByteArray? = null,
    ): ByteArray {
        require(outputLength in 1..64) { "BLAKE2b output length must be 1..64" }
        require(key.size <= 64) { "BLAKE2b key must be at most 64 bytes" }
        require(personal == null || personal.size == 16) { "BLAKE2b personalisation must be 16 bytes" }
        val h = IV.copyOf()
        h[0] = h[0] xor (0x01010000L or (key.size.toLong() shl 8) or outputLength.toLong())
        if (personal != null) {
            h[6] = h[6] xor le64(personal, 0)
            h[7] = h[7] xor le64(personal, 8)
        }
        val data = if (key.isEmpty()) input else key.copyOf(BLOCK) + input
        val m = LongArray(16)
        var counter = 0L
        var offset = 0
        while (data.size - offset > BLOCK) {
            for (i in 0 until 16) m[i] = le64(data, offset + 8 * i)
            counter += BLOCK
            compress(h, m, counter, last = false)
            offset += BLOCK
        }
        val tail = data.copyOfRange(offset, data.size).copyOf(BLOCK)
        for (i in 0 until 16) m[i] = le64(tail, 8 * i)
        counter += data.size - offset
        compress(h, m, counter, last = true)
        val out = ByteArray(64)
        for (i in 0 until 8) for (b in 0 until 8) out[8 * i + b] = (h[i] ushr (8 * b)).toByte()
        return out.copyOf(outputLength)
    }

    private fun compress(
        h: LongArray,
        m: LongArray,
        counter: Long,
        last: Boolean,
    ) {
        val v = LongArray(16)
        for (i in 0 until 8) {
            v[i] = h[i]
            v[i + 8] = IV[i]
        }
        v[12] = v[12] xor counter
        if (last) v[14] = v[14].inv()
        for (r in 0 until ROUNDS) {
            val s = SIGMA[r % 10]
            mix(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            mix(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            mix(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            mix(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            mix(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            mix(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            mix(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            mix(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    @Suppress("LongParameterList")
    private fun mix(
        v: LongArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        x: Long,
        y: Long,
    ) {
        v[a] = v[a] + v[b] + x
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }

    private fun le64(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        return value
    }
}
