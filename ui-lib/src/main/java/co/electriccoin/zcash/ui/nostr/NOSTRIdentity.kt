package co.electriccoin.zcash.ui.nostr

import android.util.Base64
import fr.acinq.secp256k1.Secp256k1
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NOSTR identity derived from a BIP-39 seed via BIP-32 key derivation at path m/44'/1237'/0'/0/0.
 *
 * Provides:
 * - secp256k1 private key (32 bytes)
 * - x-only public key (32 bytes, per BIP-340 / NIP-01)
 * - Bech32-encoded npub address
 * - NIP-98 HTTP Auth event signing (Schnorr / BIP-340)
 */
class NOSTRIdentity private constructor(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val npub: String
) {

    /**
     * Sign a NIP-98 HTTP Auth event (kind 27235) for the given URL and HTTP method.
     *
     * @return Base64-encoded JSON event string suitable for the Authorization header.
     */
    fun signNIP98Event(
        url: String,
        method: String
    ): String {
        val createdAt = System.currentTimeMillis() / MILLIS_PER_SECOND
        val pubkeyHex = publicKey.toHexString()
        val tagsJson = """[["u","$url"],["method","$method"]]"""

        // NIP-01 serialization for event ID:  [0, pubkey, created_at, kind, tags, content]
        val serialized = """[0,"$pubkeyHex",$createdAt,$NIP98_KIND,$tagsJson,""]"""
        val eventId = sha256(serialized.toByteArray(Charsets.UTF_8))
        val eventIdHex = eventId.toHexString()

        // Schnorr signature (BIP-340) via secp256k1-kmp
        val auxRand = ByteArray(AUX_RANDOM_SIZE).also { SecureRandom().nextBytes(it) }
        val sig = Secp256k1.signSchnorr(eventId, privateKey, auxRand)
        val sigHex = sig.toHexString()

        val eventJson = buildString {
            append("""{"id":"$eventIdHex",""")
            append(""""pubkey":"$pubkeyHex",""")
            append(""""created_at":$createdAt,""")
            append(""""kind":$NIP98_KIND,""")
            append(""""tags":$tagsJson,""")
            append(""""content":"",""")
            append(""""sig":"$sigHex"}""")
        }

        return Base64.encodeToString(eventJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    companion object {

        private const val NIP98_KIND = 27235
        private const val MILLIS_PER_SECOND = 1000L
        private const val AUX_RANDOM_SIZE = 32
        private const val HARDENED_FLAG = 0x80000000.toInt()
        private const val KEY_SIZE = 32
        private const val CHAIN_CODE_OFFSET = 32
        private const val HMAC_KEY_BITCOIN_SEED = "Bitcoin seed"
        private const val BECH32_ENCODING_BITS = 5
        private const val BYTE_BITS = 8

        // BIP-44 path: m/44'/1237'/0'/0/0
        private val DERIVATION_PATH = intArrayOf(
            44 or HARDENED_FLAG,     // purpose (hardened)
            1237 or HARDENED_FLAG,   // coin type for NOSTR (hardened)
            0 or HARDENED_FLAG,      // account (hardened)
            0,                       // change (normal)
            0                        // index (normal)
        )

        /**
         * Derive a NOSTR identity from a BIP-39 seed (64 bytes).
         */
        fun fromSeed(seed: ByteArray): NOSTRIdentity {
            require(seed.size >= KEY_SIZE) { "Seed must be at least 32 bytes" }

            val (privateKey, _) = deriveKey(seed)

            // 65-byte uncompressed public key -> x-only (bytes 1..32)
            val pubkey65 = Secp256k1.pubkeyCreate(privateKey)
            val xOnlyPubkey = pubkey65.copyOfRange(1, 1 + KEY_SIZE)

            val npub = bech32Encode("npub", xOnlyPubkey)

            return NOSTRIdentity(privateKey, xOnlyPubkey, npub)
        }

        /**
         * BIP-32 key derivation from seed to the NOSTR path.
         *
         * @return Pair of (privateKey, chainCode), both 32 bytes.
         */
        private fun deriveKey(seed: ByteArray): Pair<ByteArray, ByteArray> {
            // Master key generation: HMAC-SHA512("Bitcoin seed", seed)
            val masterHmac = hmacSha512(HMAC_KEY_BITCOIN_SEED.toByteArray(Charsets.UTF_8), seed)
            var key = masterHmac.copyOfRange(0, KEY_SIZE)
            var chainCode = masterHmac.copyOfRange(CHAIN_CODE_OFFSET, masterHmac.size)

            // Derive each segment of the path
            for (index in DERIVATION_PATH) {
                val data: ByteArray = if (index < 0) {
                    // Hardened: 0x00 || key || index (big-endian)
                    // Note: negative int in Kotlin means the hardened bit (0x80000000) is set
                    val buf = ByteArrayOutputStream(1 + KEY_SIZE + Int.SIZE_BYTES)
                    buf.write(0)
                    buf.write(key)
                    buf.write(intToBigEndianBytes(index))
                    buf.toByteArray()
                } else {
                    // Normal: compressedPubKey || index (big-endian)
                    val compressed = compressPublicKey(Secp256k1.pubkeyCreate(key))
                    val buf = ByteArrayOutputStream(compressed.size + Int.SIZE_BYTES)
                    buf.write(compressed)
                    buf.write(intToBigEndianBytes(index))
                    buf.toByteArray()
                }

                val hmacResult = hmacSha512(chainCode, data)
                val il = hmacResult.copyOfRange(0, KEY_SIZE)
                val ir = hmacResult.copyOfRange(CHAIN_CODE_OFFSET, hmacResult.size)

                // Child key = privKeyTweakAdd(parentKey, IL)
                key = Secp256k1.privKeyTweakAdd(key, il)
                chainCode = ir
            }

            return Pair(key, chainCode)
        }

        /**
         * Compress a 65-byte uncompressed public key to 33 bytes.
         */
        private fun compressPublicKey(uncompressed: ByteArray): ByteArray {
            require(uncompressed.size == UNCOMPRESSED_PUBKEY_SIZE) {
                "Expected 65-byte uncompressed public key"
            }
            val prefix = if (uncompressed[uncompressed.size - 1].toInt() and 1 == 0) {
                COMPRESSED_PREFIX_EVEN
            } else {
                COMPRESSED_PREFIX_ODD
            }
            val compressed = ByteArray(COMPRESSED_PUBKEY_SIZE)
            compressed[0] = prefix
            System.arraycopy(uncompressed, 1, compressed, 1, KEY_SIZE)
            return compressed
        }

        private const val UNCOMPRESSED_PUBKEY_SIZE = 65
        private const val COMPRESSED_PUBKEY_SIZE = 33
        private const val COMPRESSED_PREFIX_EVEN: Byte = 0x02
        private const val COMPRESSED_PREFIX_ODD: Byte = 0x03

        private fun hmacSha512(
            key: ByteArray,
            data: ByteArray
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(key, "HmacSHA512"))
            return mac.doFinal(data)
        }

        private fun sha256(data: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(data)
        }

        private fun intToBigEndianBytes(value: Int): ByteArray {
            return ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
        }

        // --- Bech32 encoding (BIP-173) ---

        private const val BECH32_ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        private val BECH32_GENERATOR = intArrayOf(
            0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
        )

        /**
         * Encode raw bytes as a Bech32 string with the given human-readable part (HRP).
         */
        private fun bech32Encode(
            hrp: String,
            data: ByteArray
        ): String {
            val values = convertBits(data, BYTE_BITS, BECH32_ENCODING_BITS, true)
            val checksum = bech32CreateChecksum(hrp, values)

            val sb = StringBuilder(hrp.length + 1 + values.size + checksum.size)
            sb.append(hrp)
            sb.append('1')
            for (v in values) {
                sb.append(BECH32_ALPHABET[v.toInt()])
            }
            for (v in checksum) {
                sb.append(BECH32_ALPHABET[v.toInt()])
            }
            return sb.toString()
        }

        private fun bech32Polymod(values: ByteArray): Int {
            var chk = 1
            for (v in values) {
                val top = chk ushr 25
                chk = (chk and 0x1ffffff shl BECH32_ENCODING_BITS) xor v.toInt()
                for (i in BECH32_GENERATOR.indices) {
                    if (top ushr i and 1 != 0) {
                        chk = chk xor BECH32_GENERATOR[i]
                    }
                }
            }
            return chk
        }

        private fun bech32HrpExpand(hrp: String): ByteArray {
            val result = ByteArray(hrp.length * 2 + 1)
            for (i in hrp.indices) {
                result[i] = (hrp[i].code ushr BECH32_ENCODING_BITS).toByte()
                result[i + hrp.length + 1] = (hrp[i].code and 31).toByte()
            }
            result[hrp.length] = 0
            return result
        }

        private fun bech32CreateChecksum(
            hrp: String,
            values: ByteArray
        ): ByteArray {
            val hrpExpand = bech32HrpExpand(hrp)
            val enc = ByteArray(hrpExpand.size + values.size + BECH32_CHECKSUM_SIZE)
            System.arraycopy(hrpExpand, 0, enc, 0, hrpExpand.size)
            System.arraycopy(values, 0, enc, hrpExpand.size, values.size)
            val polymod = bech32Polymod(enc) xor 1
            val result = ByteArray(BECH32_CHECKSUM_SIZE)
            for (i in result.indices) {
                result[i] = (polymod ushr (BECH32_ENCODING_BITS * (BECH32_CHECKSUM_SIZE - 1 - i)) and 31).toByte()
            }
            return result
        }

        private const val BECH32_CHECKSUM_SIZE = 6

        /**
         * Convert between bit groups (e.g., 8-bit bytes to 5-bit Bech32 values).
         */
        @Suppress("MagicNumber")
        private fun convertBits(
            data: ByteArray,
            fromBits: Int,
            toBits: Int,
            pad: Boolean
        ): ByteArray {
            var acc = 0
            var bits = 0
            val maxV = (1 shl toBits) - 1
            val result = ByteArrayOutputStream(data.size * fromBits / toBits + 1)

            for (b in data) {
                acc = acc shl fromBits or (b.toInt() and 0xff)
                bits += fromBits
                while (bits >= toBits) {
                    bits -= toBits
                    result.write(acc ushr bits and maxV)
                }
            }

            if (pad && bits > 0) {
                result.write(acc shl (toBits - bits) and maxV)
            }

            return result.toByteArray()
        }
    }
}

/**
 * Convert a byte array to a lowercase hex string.
 */
private fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}
