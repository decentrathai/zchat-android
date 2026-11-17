package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.screen.walletbackup.SeedBackupQrData
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * QR-DRAW (fix-all 2.13.0, train T4c): the fixtures and helpers the QR drawing tests share.
 *
 * All three fixtures are synthetic: none is a real address, key or wallet. They were checked with ZXing 3.5.3 (the
 * app's version) at every size used here; ZXing's detector is content-dependent, so do not randomize them, and
 * re-check them if ZXing is ever upgraded. The layout and golden tests do not depend on the detector.
 *
 * No Robolectric here (unit tests return default values), so no Bitmap: the ARGB array is built exactly as
 * AndroidQrCodeImageGenerator builds it (index for index), and `the_bitmap_builder_maps_index_for_index` pins that
 * mapping, so what these tests read is what `Bitmap.createBitmap` shows.
 */
internal object QrDrawFixtures {
    /** F-ADDR: 213 characters (v10, mask 2), "u1" plus random base32. Not a valid Bech32m address. */
    const val F_ADDR =
        "u1rvetday6jkxd0mhq59zsxqrpjeqja64czls2scs28azdwe2lmejx0wt492r7wln2g9yymx4m5uhjrzxqxhupk9rs472ht35lft9dfla3" +
            "p0av6c9ah4x20ungutav803qzwnh7vxdf4yzkkcl2ctfcfvl030n8v2jmq8mc8gzgy3fmf6d0zl7y5y3qg0nt3r8grgq92fcxhnrtwj98au"

    private const val F_CODE_NONCE_FIXTURE_HEX = "7baabae01c5070666348ea25a30d259e8018fe87886f7a58289e35f9a90b2ed5"

    /** F-CODE: 321 characters (v13, mask 2), the real contact-code layout around F-ADDR. */
    const val F_CODE = "zchat:c1?z=$F_ADDR&n=$F_CODE_NONCE_FIXTURE_HEX&r=wss%3A%2F%2Frelay.zsend.xyz"

    /** The public BIP39 test vector ("abandon" x23 + "art"), the one SeedBackupQrDataTest uses. */
    val PUBLIC_TEST_PHRASE: String = (List(23) { "abandon" } + "art").joinToString(", ")

    /** F-SEED: 245 characters (v11, mask 2), the seed-backup QR's JSON for the public test vector. */
    val F_SEED: String by lazy { SeedBackupQrData.encode(PUBLIC_TEST_PHRASE, 419_200L) }

    val F_SEED_EXPECTED: String get() = "{\"v\":1,\"seed\":\"$PUBLIC_TEST_PHRASE\",\"birthday\":419200}"

    // SHA-256 of the row-major "0"/"1" module string (no separators) of ZXing 3.5.3's Encoder at level M; F-ADDR's
    // layout is also confirmed by segno 1.6.6 (an independent encoder).
    private const val ADDR_LAYOUT_SHA256 = "49ae2d68d9ffb7cbfd1e980c802fdd35c4c00ad4bae8492c5f4f1096450dacc5"
    private const val CODE_LAYOUT_SHA256 = "708063e3b12cddc1a7d59b0364b07effc450e3ba84341c447e6014fe8334d498"
    private const val BACKUP_LAYOUT_SHA256 = "2d44104d3e074e39fe206fc0a9d400e52d3f16c36f03a99bd0bf117455564dc4"

    data class Fixture(
        val name: String,
        val text: String,
        val layoutSha256: String
    )

    val all: List<Fixture> by lazy {
        listOf(
            Fixture("F-ADDR", F_ADDR, ADDR_LAYOUT_SHA256),
            Fixture("F-CODE", F_CODE, CODE_LAYOUT_SHA256),
            Fixture("F-SEED", F_SEED, BACKUP_LAYOUT_SHA256),
        )
    }

    fun fixture(name: String): Fixture = all.single { it.name == name }

    /** 200 dp at 2.33 and 2.625, 700, and ShareQRUseCase's 1280. */
    val SIZES = listOf(467, 525, 700, 1280)

    val LOGO_SIZES = listOf(467, 525, 560, 700, 800, 1000, 1280)

    fun drawn(
        text: String,
        size: Int
    ): BooleanArray = JvmQrCodeGenerator.generate(text, size)

    /** Exactly AndroidQrCodeImageGenerator's mapping with black on white. */
    fun argb(bits: BooleanArray): IntArray = IntArray(bits.size) { if (bits[it]) DARK else LIGHT }

    fun standard(text: String): ByteMatrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix

    fun dim(text: String): Int = standard(text).width

    /** The first dark pixel reading bits[y * size + x] (row-major, as Bitmap reads it) and the module size. */
    private fun origin(
        bits: BooleanArray,
        size: Int
    ): Triple<Int, Int, Int> {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (bits[y * size + x]) {
                    var run = 0
                    while (x + run < size && bits[y * size + x + run]) run++
                    return Triple(x, y, run / FINDER_MODULES)
                }
            }
        }
        error("no dark pixel in the drawn QR")
    }

    /** Samples module centres of the drawn picture into a grid read as a Bitmap reads the array. */
    fun grid(
        bits: BooleanArray,
        size: Int,
        dim: Int
    ): BitMatrix {
        val (x0, y0, module) = origin(bits, size)
        val out = BitMatrix(dim)
        for (row in 0 until dim) {
            for (col in 0 until dim) {
                val x = x0 + col * module + module / 2
                val y = y0 + row * module + module / 2
                if (bits[y * size + x]) out.set(col, row)
            }
        }
        return out
    }

    fun differences(
        grid: BitMatrix,
        text: String
    ): Int {
        val reference = standard(text)
        var count = 0
        for (row in 0 until reference.height) {
            for (col in 0 until reference.width) {
                if ((reference.get(col, row).toInt() == 1) != grid.get(col, row)) count++
            }
        }
        return count
    }

    fun layoutSha256(grid: BitMatrix): String {
        val modules = StringBuilder()
        for (row in 0 until grid.height) {
            for (col in 0 until grid.width) modules.append(if (grid.get(col, row)) '1' else '0')
        }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(modules.toString().toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
    }

    /** The smallest light border, in modules, over the four sides (the dark pixels' box is the whole symbol). */
    fun border(
        bits: BooleanArray,
        size: Int
    ): Double {
        val (_, _, module) = origin(bits, size)
        var minX = size
        var minY = size
        var maxX = -1
        var maxY = -1
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (bits[y * size + x]) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        val light = minOf(minOf(minX, minY), minOf(size - 1 - maxX, size - 1 - maxY))
        return light.toDouble() / module
    }

    /**
     * The whole picture read the way the gallery import reads it: ImageUriToQrCodeConverter's exact hints on a
     * HybridBinarizer. Null when nothing is found.
     */
    fun decodeWholePicture(
        pixels: IntArray,
        size: Int
    ): String? =
        try {
            MultiFormatReader()
                .apply {
                    setHints(
                        mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.ALSO_INVERTED to true,
                            DecodeHintType.TRY_HARDER to true,
                            DecodeHintType.CHARACTER_SET to "UTF-8"
                        )
                    )
                }.decodeWithState(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
                .text
        } catch (_: ReaderException) {
            null
        }

    /**
     * A stand-in for the centre logo: ZashiQr's white ring (diameter 0.18 x the QR, `ZashiQr.kt` logo box) with its
     * inner 76 % (12 % padding each side) painted as a 5-px checker, which also covers ShareQRUseCase's 0.09 x width
     * radius icon.
     */
    fun withLogo(
        bits: BooleanArray,
        size: Int
    ): IntArray {
        val pixels = argb(bits)
        val centre = size / 2.0
        val ring = LOGO_RATIO * size / 2
        val inner = ring * LOGO_INNER_RATIO
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + 0.5 - centre
                val dy = y + 0.5 - centre
                val d = sqrt(dx * dx + dy * dy)
                if (d <= ring) {
                    val checker = (x / CHECKER_PX + y / CHECKER_PX) % 2 == 0
                    pixels[y * size + x] = if (d <= inner && checker) DARK else LIGHT
                }
            }
        }
        return pixels
    }

    // Source reading for the guards.

    fun source(path: String): String = AmountReadingProbe.source(path)

    /** Every Kotlin file of ui-lib and ui-design-lib outside the test source sets (main, foss, store, debug, …). */
    val productKotlinFiles: List<Pair<String, String>> by lazy {
        val root = AmountReadingProbe.repoRoot
        listOf("ui-lib/src", "ui-design-lib/src").flatMap { base ->
            (File(root, base).listFiles() ?: emptyArray())
                .filter { it.isDirectory && !it.name.contains("test", ignoreCase = true) }
                .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
                .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
        }
    }

    /** The text between the balanced parentheses of each call that starts with [opening] (which ends with "("). */
    fun callArguments(
        text: String,
        opening: String
    ): List<String> {
        val out = mutableListOf<String>()
        var from = text.indexOf(opening)
        while (from >= 0) {
            val start = from + opening.length
            var depth = 1
            var i = start
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                }
                i++
            }
            out += text.substring(start, i - 1)
            from = text.indexOf(opening, i)
        }
        return out
    }

    /** Splits an argument list at its top-level commas; empty arguments (a trailing comma) are dropped. */
    fun topLevelArguments(arguments: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in arguments) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if (c == ',' && depth == 0) {
                out += current.toString().trim()
                current.clear()
            } else {
                current.append(c)
            }
        }
        out += current.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** The body of the first block `header … { … }` with balanced braces. */
    fun block(
        text: String,
        header: String
    ): String {
        val at = text.indexOf(header)
        check(at >= 0) { "'$header' not found" }
        val open = text.indexOf('{', at)
        var depth = 1
        var i = open + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return text.substring(open + 1, i - 1)
    }

    fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    const val DARK: Int = -0x1000000 // 0xFF000000
    const val LIGHT: Int = -0x1 // 0xFFFFFFFF
    private const val FINDER_MODULES = 7
    private const val LOGO_RATIO = 0.18
    private const val LOGO_INNER_RATIO = 0.76
    private const val CHECKER_PX = 5

    const val ZASHI_QR = "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/component/ZashiQr.kt"
    const val JVM_GENERATOR = "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/util/JvmQrCodeGenerator.kt"
    const val IMAGE_GENERATOR =
        "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/util/AndroidQrCodeImageGenerator.kt"
    const val SHARE_QR = "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ShareQRUseCase.kt"
}
