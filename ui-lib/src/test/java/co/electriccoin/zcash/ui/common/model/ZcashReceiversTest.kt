package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * ZC-1082 (FIRST-CONTACT-SPEC N-13, ZC-862) — the receivers of a Zcash address, decoded as ZIP 316 says: Bech32m, then
 * F4Jumble⁻¹ (BLAKE2b with personalisations), then the typecode / length / value items, with the HRP padding checked.
 *
 * After a restore the wallet library names each own send by the single receiver its note was paid to (a short
 * single-receiver address), never by the address text the app sent to; the app can only tell which address that
 * receiver belongs to by comparing receivers. This pins the decoder against vectors made outside this code base
 * ([Zip316TestVectors.kt]): RFC 7693's BLAKE2b vectors (Appendix A and the Appendix E self-test), BLAKE2b with a
 * personalisation from Python's hashlib, the F4Jumble vectors (short and long), every ZIP 316 Unified Address vector, both
 * platform-fee addresses the app paid, the single-receiver forms (Unified, Sapling, transparent, TEX), and malformed
 * input, which is never an address (null).
 *
 * Failure messages name vectors by index, never by address.
 */
class ZcashReceiversTest {
    @Test
    fun `BLAKE2b-512 of abc is RFC 7693 Appendix A`() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(Blake2b.digest("abc".toByteArray(), outputLength = 64)),
        )
    }

    @Test
    fun `BLAKE2b passes the RFC 7693 Appendix E self-test, keyed and unkeyed, at four output lengths`() {
        val results = java.io.ByteArrayOutputStream()
        for (outLen in intArrayOf(20, 32, 48, 64)) {
            for (inLen in intArrayOf(0, 3, 128, 129, 255, 1024)) {
                val input = selftestSequence(inLen, inLen)
                results.write(Blake2b.digest(input, outputLength = outLen))
                val key = selftestSequence(outLen, outLen)
                results.write(Blake2b.digest(input, outputLength = outLen, key = key))
            }
        }
        assertEquals(
            "c23a7800d98123bd10f506c61e29da5603d763b8bbad2e737f5e765a7bccd475",
            hex(Blake2b.digest(results.toByteArray(), outputLength = 32)),
        )
    }

    @Test
    fun `BLAKE2b with a personalisation matches an independent implementation`() {
        BLAKE2B_PERSONAL_VECTORS.forEachIndexed { i, v ->
            assertEquals(
                "personalised vector $i",
                v.digest,
                hex(Blake2b.digest(unhex(v.message), outputLength = v.outputLength, personal = unhex(v.personal))),
            )
        }
    }

    @Test
    fun `F4Jumble and its inverse match the zcash test vectors`() {
        F4JUMBLE_VECTORS.forEachIndexed { i, (normal, jumbled) ->
            assertEquals("F4Jumble vector $i", jumbled, F4Jumble.jumble(unhex(normal))?.let(::hex))
            assertEquals("F4Jumble inverse vector $i", normal, F4Jumble.unjumble(unhex(jumbled))?.let(::hex))
        }
    }

    @Test
    fun `F4Jumble matches the long zcash test vectors`() {
        F4JUMBLE_LONG_VECTORS.forEachIndexed { i, (length, digest) ->
            val normal = ByteArray(length) { it.toByte() }
            val jumbled = F4Jumble.jumble(normal)
            assertNotNull("long vector $i", jumbled)
            assertEquals("long vector $i", digest, hex(Blake2b.digest(jumbled!!, outputLength = 64)))
            assertArrayEquals("long vector $i inverse", normal, F4Jumble.unjumble(jumbled))
        }
    }

    @Test
    fun `F4Jumble refuses a message outside 38 to 4194368 bytes`() {
        assertNull(F4Jumble.jumble(ByteArray(37)))
        assertNull(F4Jumble.unjumble(ByteArray(37)))
        assertNull(F4Jumble.jumble(ByteArray(4_194_369)))
        assertNotNull(F4Jumble.jumble(ByteArray(38)))
    }

    @Test
    fun `every ZIP 316 Unified Address vector decodes to its receivers and encodes back to itself`() {
        ZIP316_UNIFIED_ADDRESSES.forEachIndexed { i, v ->
            val expected = v.receivers.map { (typecode, bytes) -> ZcashReceiver("main", typecode, bytes) }.toSet()
            assertEquals("vector $i receivers", expected, ZcashReceivers.of(v.address))
            assertEquals(
                "vector $i encodes back",
                true,
                ZcashReceivers.unifiedAddress("u", v.receivers.map { (tc, bytes) -> tc to unhex(bytes) }) == v.address,
            )
            assertTrue("vector $i is within itself", ZcashReceivers.within(v.address, v.address))
        }
    }

    @Test
    fun `an all upper-case address is the same address, a mixed-case one is none`() {
        val v = ZIP316_UNIFIED_ADDRESSES.first()
        assertEquals(ZcashReceivers.of(v.address), ZcashReceivers.of(v.address.uppercase()))
        val mixed = v.address.substring(0, 10).uppercase() + v.address.substring(10)
        assertNull(ZcashReceivers.of(mixed))
    }

    @Test
    fun `each single-receiver form of a Unified Address sits within it, and the address is not within one form`() {
        var sapling = 0
        var orchard = 0
        var transparent = 0
        ZIP316_UNIFIED_ADDRESSES.forEachIndexed { i, v ->
            for ((typecode, bytes) in v.receivers) {
                val forms =
                    when (typecode) {
                        2 -> {
                            sapling++
                            listOf(bech32("zs", unhex(bytes), BECH32), ZcashReceivers.unifiedAddress("u", listOf(2 to unhex(bytes))))
                        }
                        3 -> listOf(ZcashReceivers.unifiedAddress("u", listOf(3 to unhex(bytes)))).also { orchard++ }
                        0 ->
                            listOf(
                                base58Check(byteArrayOf(0x1c, 0xb8.toByte()) + unhex(bytes)),
                                bech32("tex", unhex(bytes), BECH32M),
                            ).also { transparent++ }
                        else -> emptyList()
                    }
                forms.forEachIndexed { k, form ->
                    assertNotNull("vector $i typecode $typecode form $k encodes", form)
                    assertEquals(
                        "vector $i typecode $typecode form $k receivers",
                        setOf(ZcashReceiver("main", typecode, bytes)),
                        ZcashReceivers.of(form!!),
                    )
                    assertTrue("vector $i typecode $typecode form $k within", ZcashReceivers.within(form, v.address))
                    if (v.receivers.size > 1) {
                        assertFalse("vector $i is within its form $k", ZcashReceivers.within(v.address, form))
                    }
                }
            }
        }
        assertTrue("the vectors hold every receiver kind", sapling > 0 && orchard > 0 && transparent > 0)
    }

    @Test
    fun `a receiver on the test network is not within a mainnet address`() {
        val v = ZIP316_UNIFIED_ADDRESSES.first { vector -> vector.receivers.any { it.first == 3 } }
        val orchard = unhex(v.receivers.first { it.first == 3 }.second)
        val testnetForm = ZcashReceivers.unifiedAddress("utest", listOf(3 to orchard))
        assertEquals(setOf(ZcashReceiver("test", 3, hex(orchard))), ZcashReceivers.of(testnetForm!!))
        assertFalse(ZcashReceivers.within(testnetForm, v.address))
    }

    @Test
    fun `both platform-fee addresses decode, and each of their receiver forms is within them`() {
        val fees =
            listOf("current" to ZMSGConstants.PLATFORM_FEE_ADDRESS) +
                ZMSGConstants.PREVIOUS_PLATFORM_FEE_ADDRESSES.map { "previous" to it }
        for ((label, fee) in fees) {
            val receivers = ZcashReceivers.of(fee)
            assertNotNull("the $label fee address decodes", receivers)
            assertEquals("the $label fee address's receiver kinds", setOf(2, 3), receivers!!.map { it.typecode }.toSet())
            assertTrue("the $label fee address is a mainnet one", receivers.all { it.network == "main" })
            for (r in receivers) {
                val unified = ZcashReceivers.unifiedAddress("u", listOf(r.typecode to unhex(r.hex)))!!
                assertTrue("the $label fee's typecode ${r.typecode} form is within it", ZcashReceivers.within(unified, fee))
                assertFalse("the $label fee is within its typecode ${r.typecode} form", ZcashReceivers.within(fee, unified))
                if (r.typecode == 2) assertTrue(ZcashReceivers.within(bech32("zs", unhex(r.hex), BECH32)!!, fee))
            }
        }
    }

    @Test
    fun `malformed input is never an address`() {
        val v = ZIP316_UNIFIED_ADDRESSES.first { vector -> vector.receivers.map { it.first } == listOf(2, 3) }
        val sapling = unhex(v.receivers[0].second)
        val orchard = unhex(v.receivers[1].second)
        val cases =
            listOf(
                "empty" to "",
                "plain text" to "hello world",
                "no data" to "u1",
                "bad checksum" to v.address.dropLast(1) + (if (v.address.last() == 'q') 'p' else 'q'),
                "Bech32 checksum on a Unified Address" to bech32Raw("u", jumbledItems("u", item(2, sapling) + item(3, orchard)), BECH32),
                "padding of another HRP" to bech32Raw("u", jumbledItems("utest", item(2, sapling) + item(3, orchard)), BECH32M),
                "truncated item" to bech32Raw("u", jumbledItems("u", item(2, sapling) + byteArrayOf(3, 43) + orchard.copyOf(42)), BECH32M),
                "Orchard receiver of 42 bytes" to
                    bech32Raw("u", jumbledItems("u", item(2, sapling) + item(3, orchard.copyOf(42))), BECH32M),
                "items out of order" to bech32Raw("u", jumbledItems("u", item(3, orchard) + item(2, sapling)), BECH32M),
                "the same typecode twice" to bech32Raw("u", jumbledItems("u", item(3, orchard) + item(3, orchard)), BECH32M),
                "transparent only" to bech32Raw("u", jumbledItems("u", item(0, ByteArray(20) { 7 })), BECH32M),
                "P2PKH and P2SH" to
                    bech32Raw("u", jumbledItems("u", item(0, ByteArray(20)) + item(1, ByteArray(20)) + item(3, orchard)), BECH32M),
                "non-canonical typecode" to bech32Raw("u", jumbledItems("u", byteArrayOf(0xfd.toByte(), 3, 0, 43) + orchard), BECH32M),
                "unknown MUST-understand metadata" to
                    bech32Raw("u", jumbledItems("u", item(3, orchard) + item(0xE0, ByteArray(4))), BECH32M),
                "shorter than F4Jumble allows" to bech32Raw("u", ByteArray(20) { 1 }, BECH32M),
                "unknown HRP" to bech32Raw("x", jumbledItems("x", item(3, orchard)), BECH32M),
                "random payload" to SyntheticUnifiedAddress.mainnet(seed = 1082),
                "non-zero padding bits" to nonZeroPaddingBits(ZcashReceivers.unifiedAddress("u", listOf(3 to orchard))!!),
                "Sapling of 42 bytes" to bech32("zs", sapling.copyOf(42), BECH32)!!,
                "Sapling with a Bech32m checksum" to bech32("zs", sapling, BECH32M)!!,
                "transparent with a bad checksum" to base58Check(byteArrayOf(0x1c, 0xb8.toByte()) + ByteArray(20)).dropLast(1) + "1",
                "transparent of another prefix" to base58Check(byteArrayOf(0x1c, 0x00) + ByteArray(20)),
                "too long" to "u1" + "q".repeat(3_000),
            )
        cases.forEach { (label, text) ->
            assertNull("$label decoded", ZcashReceivers.of(text))
            assertFalse("$label is within an address", ZcashReceivers.within(text, v.address))
            assertFalse("an address is within $label", ZcashReceivers.within(v.address, text))
        }
        assertNull("encoding with no receiver", ZcashReceivers.unifiedAddress("u", emptyList()))
    }

    private fun item(
        typecode: Int,
        bytes: ByteArray,
    ): ByteArray = byteArrayOf(typecode.toByte(), bytes.size.toByte()) + bytes

    private fun jumbledItems(
        paddingHrp: String,
        items: ByteArray,
    ): ByteArray = F4Jumble.jumble(items + paddingHrp.toByteArray().copyOf(16))!!

    /** [address] with the lowest bit of its last data character flipped and a fresh checksum (the padding bits not zero). */
    private fun nonZeroPaddingBits(address: String): String {
        val separator = address.lastIndexOf('1')
        val hrp = address.substring(0, separator)
        val data =
            address
                .substring(separator + 1)
                .dropLast(6)
                .map { CHARSET.indexOf(it) }
                .toIntArray()
        data[data.size - 1] = data[data.size - 1] xor 1
        return encodeFiveBit(hrp, data, BECH32M)
    }

    private fun bech32(
        hrp: String,
        bytes: ByteArray,
        constant: Int,
    ): String? = bech32Raw(hrp, bytes, constant)

    private fun bech32Raw(
        hrp: String,
        bytes: ByteArray,
        constant: Int,
    ): String = encodeFiveBit(hrp, toFiveBit(bytes), constant)

    private fun encodeFiveBit(
        hrp: String,
        data: IntArray,
        constant: Int,
    ): String {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor constant
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
            acc = acc and 0xff
        }
        if (bits > 0) out += (acc shl (5 - bits)) and 31
        return out.toIntArray()
    }

    private fun hrpExpand(hrp: String): IntArray = (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

    private fun polymod(values: IntArray): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) chk = chk xor generator[i]
            }
        }
        return chk
    }

    private fun base58Check(payload: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val bytes = payload + sha.digest(sha.digest(payload)).copyOf(4)
        var number = BigInteger(1, bytes)
        val out = StringBuilder()
        val radix = BigInteger.valueOf(58)
        while (number.signum() > 0) {
            val (q, r) = number.divideAndRemainder(radix)
            out.append(BASE58[r.toInt()])
            number = q
        }
        bytes.takeWhile { it.toInt() == 0 }.forEach { _ -> out.append('1') }
        return out.reverse().toString()
    }

    /** RFC 7693 Appendix E: a deterministic byte sequence (Fibonacci over 32-bit words). */
    private fun selftestSequence(
        length: Int,
        seed: Int,
    ): ByteArray {
        var a = (0xDEAD4BAD.toInt() * seed)
        var b = 1
        return ByteArray(length) {
            val t = a + b
            a = b
            b = t
            (t ushr 24).toByte()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private companion object {
        const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        const val BECH32 = 1
        const val BECH32M = 0x2bc830a3
    }
}
