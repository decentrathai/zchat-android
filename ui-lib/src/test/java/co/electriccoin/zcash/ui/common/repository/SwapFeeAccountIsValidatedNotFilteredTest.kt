package co.electriccoin.zcash.ui.common.repository

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1367 (money-path rule 6): ui-lib/build.gradle.kts reads ZCHAT_SWAP_FEE_ACCOUNT and FILTERS it to NEAR characters.
 * A typo is silently turned into another account: "zchat!.near" becomes "zchat.near", "ZChat.near" becomes "hat.near",
 * "zchat fee.near" becomes "zchatfee.near", and the swap app fee goes to an account that is not ZChat's.
 *
 * Must hold: the value is validated, never rewritten. A value with an uppercase letter, a space, or a character the filter
 * would drop fails the build; a 64-hex implicit account or a named .near account passes as it is. Gradle-free: the test
 * reads the build script text only, never local.properties or the environment.
 *
 * Seam: the script's ZCHAT_SWAP_FEE_ACCOUNT code (from `val zchatSwapFeeAccount` to `androidComponents {`) holds the one
 * `Regex(...)` literal the value must match whole (`matches`/`matchEntire`), and throws for a non-empty value that does not.
 * Every sample is synthetic.
 */
class SwapFeeAccountIsValidatedNotFilteredTest {
    private fun script(): String =
        listOf(File("build.gradle.kts"), File("ui-lib/build.gradle.kts"))
            .firstOrNull { it.isFile && it.readText().contains("ZCHAT_SWAP_FEE_ACCOUNT") }
            ?.readText()
            ?: fail("ui-lib/build.gradle.kts not found from ${File(".").absolutePath}")

    /** The script's swap fee account code: the value, its checks, the release refusal. */
    private fun accountCode(): String {
        val text = script()
        val start = text.indexOf("val zchatSwapFeeAccount")
        if (start < 0) fail("build.gradle.kts has no `val zchatSwapFeeAccount`")
        val end = text.indexOf("androidComponents {", start)
        if (end < 0) fail("build.gradle.kts has no `androidComponents {` after the swap fee account")
        return text.substring(start, end)
    }

    private fun accountRule(): Regex {
        val code = accountCode()
        val raw = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"").find(code)?.groupValues?.get(1)
        val quoted =
            Regex("Regex\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(code)?.groupValues?.get(1)?.let { escaped ->
                escaped.replace("\\$", "$").replace("\\\\", "\\")
            }
        val pattern =
            (raw ?: quoted)?.replace("\${'$'}", "$")
                ?: fail("the swap fee account is not checked against a Regex(...) literal in build.gradle.kts")
        return Regex(pattern)
    }

    @Test
    fun `the account is not rewritten character by character`() {
        val code = accountCode()
        for (rewrite in listOf(".filter {", ".filter(", ".filterNot", ".replace(", ".lowercase(")) {
            assertFalse(code.contains(rewrite), "the swap fee account is rewritten with `$rewrite`: a typo becomes another account")
        }
    }

    @Test
    fun `a valid implicit or named account passes the rule as it is`() {
        val rule = accountRule()
        for (valid in listOf("0123456789abcdef".repeat(4), "zchat.near", "zchat-fee.near", "zchat_fee.near")) {
            assertTrue(rule.matches(valid), "a valid NEAR account fails the build: ${valid.take(10)}…")
        }
    }

    @Test
    fun `an uppercase letter, a space or a character the old filter dropped fails the rule`() {
        val rule = accountRule()
        for (invalid in listOf("ZChat.near", "zchat fee.near", "zchat!.near", "zchat.near,", "zchat..near")) {
            assertFalse(rule.matches(invalid), "an account with a typo passes the build: \"$invalid\"")
        }
    }

    @Test
    fun `a value that does not match fails the build, not only a missing one at release`() {
        val code = accountCode()
        assertTrue(
            code.contains(".matches(") || code.contains("matchEntire("),
            "the swap fee account rule is not applied to the whole value",
        )
        val failures = Regex("throw\\s+GradleException|\\berror\\(|\\brequire\\(").findAll(code).count()
        assertTrue(failures >= 2, "an invalid swap fee account does not fail the build (failure sites: $failures)")
    }
}
