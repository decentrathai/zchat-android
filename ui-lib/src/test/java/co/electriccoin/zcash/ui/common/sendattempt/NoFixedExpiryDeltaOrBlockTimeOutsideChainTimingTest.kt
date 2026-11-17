package co.electriccoin.zcash.ui.common.sendattempt

import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-114 (fix-all 2.13.0, train T5b; high-items SPECS §1.5 R7): no product code outside ChainTiming.kt holds a number
 * for an expiry delta or a block spacing, so the NU7 change of both (ZIP 218: 40 -> 120 blocks, 75 s -> 25 s) is one
 * table (ChainTiming) and the next user of F4's rules (F8, KI-23) builds on it, not on a new 40/42/50/75/90.
 *
 * Allowed: SendAttemptRules.LEGACY_2124_EXPIRY_DELTA = 40L (ZChat 2.12.4 built every transaction before NU7, at any
 * height). Constants other code and tests read by name (SECONDS_PER_BLOCK, AVERAGE_BLOCK_SECONDS) stay but are defined
 * from ChainTiming.
 *
 * At RED: SendAttemptRules.EXPIRY_DELTA = 40L and BLOCK_SECONDS = 75L, FULFIL_EXPIRY_WINDOW_BLOCKS = 42L,
 * TexRules.UNKNOWN_EXPIRY_BOUND = 50L, SECONDS_PER_BLOCK = 90L, AVERAGE_BLOCK_SECONDS = 75L, and two "* 75".
 */
class NoFixedExpiryDeltaOrBlockTimeOutsideChainTimingTest {
    private companion object {
        const val MAIN = "ui-lib/src/main/java"
        const val TABLE = "ChainTiming.kt"
        val ALLOWED = setOf("const val LEGACY_2124_EXPIRY_DELTA = 40L")

        /** A name for an expiry delta, an expiry window or bound, or a block spacing, given a number. */
        val FIXED_NAMED =
            Regex(
                """(?m)^.*\b(\w*EXPIRY_DELTA|\w*EXPIRY_WINDOW\w*|\w*EXPIRY_BOUND|\w*BLOCK_SECONDS|SECONDS_PER_BLOCK|""" +
                    """\w*BLOCK_INTERVAL\w*)\b(\s*:\s*\w+)?\s*=\s*\d[\d_]*L?\b.*$""",
            )

        /** A count of blocks turned into seconds at a fixed 75 s. */
        val FIXED_SPACING = Regex("""(?m)^.*(\*\s*75L?\b|\b75L?\s*\*).*$""")
    }

    @Test
    fun `no expiry delta or block spacing is a number outside ChainTiming`() {
        val root = File(repoRoot(), MAIN)
        val hits =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != TABLE }
                .flatMap { file ->
                    val text = file.readText()
                    (FIXED_NAMED.findAll(text) + FIXED_SPACING.findAll(text))
                        .map { it.value.trim() }
                        .filterNot { it in ALLOWED }
                        .map { "${file.relativeTo(root)}: $it" }
                }.toList()
        assertTrue(
            hits.isEmpty(),
            "fixed expiry deltas or block spacings outside ChainTiming (NU7 changes both):\n" + hits.joinToString("\n"),
        )
    }

    @Test
    fun `the height table exists`() {
        val tables = File(repoRoot(), MAIN).walkTopDown().filter { it.isFile && it.name == TABLE }.toList()
        assertTrue(tables.size == 1, "there is no single $TABLE in $MAIN (found ${tables.size})")
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) dir = dir.parentFile
        return assertNotNull(dir, "repo root (gradle.properties + README.md) not found above the test working dir")
    }
}
