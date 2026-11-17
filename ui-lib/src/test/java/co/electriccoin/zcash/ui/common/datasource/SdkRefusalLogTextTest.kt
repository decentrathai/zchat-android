package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T1 finding 4 / F9 diagnostic (fix-all 2.13.0, train T1b, T1b-3a): the Send All fee probes throw the SDK's reason
 * away.
 *
 * In T1 a New Chat Send All to an older address of a contact stepped down 795,031 / 790,031 / 785,031 zat and each
 * probe failed; the log holds only the SDK's "Caught exception", and `probeZip321Fee` turns the failure into
 * `FeeProbe.Short` without saying what the SDK said. The quoted "have 135000" is in no log. The next step of the
 * investigation is to read the SDK's text on a phone, with no spend.
 *
 * Wanted:
 * - `sdkRefusalLogText(Throwable)`: the class and message of each exception in the cause chain, with every long
 *   token (an address, a key, a memo, a txid) replaced, so amounts stay readable and no address reaches the log;
 * - both probes (`probeFee`, wallet Send all; `probeZip321Fee`, chat Send all) log it with `Twig.debug`, which the
 *   release build strips (spackle's proguard rule), before they decide Short or throw. They build nothing else and
 *   submit nothing.
 *
 * The helper is reached by name, so this file compiles before it exists. All addresses are synthetic.
 */
class SdkRefusalLogTextTest {
    @Test
    fun a_shortfall_keeps_its_amounts() {
        val text = logText(RuntimeException("Insufficient balance (have 582002, need 587002 including fee)"))
        assertTrue("have 582002, need 587002" in text, text)
        assertTrue("RuntimeException" in text, text)
    }

    @Test
    fun a_change_output_refusal_keeps_its_amount() {
        val message = "The transaction requires an additional change output of 5000 zatoshis"
        assertTrue(message in logText(IllegalStateException(message)))
    }

    @Test
    fun no_address_memo_or_key_reaches_the_log() {
        val secrets = listOf(UNIFIED, TRANSPARENT, SAPLING, MEMO_B64, TXID)
        val text = logText(RuntimeException("Could not pay ${secrets.joinToString(" and ")} (have 1000, need 2000)"))
        for (secret in secrets) {
            val leaked = secret.windowed(size = 12).firstOrNull { it in text }
            assertEquals(null, leaked, "a 12-character piece of a ${secret.length}-character token is in the log: $text")
        }
        assertTrue("have 1000, need 2000" in text, text)
    }

    @Test
    fun a_long_type_name_the_sdk_names_is_kept() {
        val message = "TransactionRequiresAdditionalChangeOutput { InsufficientFundsForChange: 5000 }"
        assertTrue(message in logText(IllegalStateException(message)))
    }

    @Test
    fun the_whole_cause_chain_is_named() {
        val root = IllegalArgumentException("Insufficient balance (have 135000, need 795031 including fee)")
        val text = logText(RuntimeException("The attempt to create a new proposal failed due to: wrapped", root))
        assertTrue("RuntimeException" in text && "IllegalArgumentException" in text, text)
        assertTrue("have 135000, need 795031" in text, text)
    }

    @Test
    fun a_long_message_is_cut() {
        val text = logText(RuntimeException("x ".repeat(5_000)))
        assertTrue(text.length < 2_000, "the log line is ${text.length} characters")
    }

    @Test
    fun both_probes_log_it_at_debug_level_before_deciding() {
        val source = ChatResourceText.source(PROPOSAL_DATA_SOURCE)
        for (probe in listOf("probeFee", "probeZip321Fee")) {
            val body = functionBody(source, "override suspend fun $probe(")
            val catchAt = body.indexOf("} catch (e: TransactionEncoderException.")
            assertTrue(catchAt > 0, "$probe has no SDK catch")
            val handler = body.substring(catchAt)
            val logAt = handler.indexOf("Twig.debug")
            assertTrue(logAt > 0 && "sdkRefusalLogText(e)" in handler, "$probe does not log the SDK's reason")
            assertTrue(logAt < handler.indexOf("isShortfall()"), "$probe must log before it decides Short or throw")
            assertFalse("Twig.info" in handler || "Twig.warn" in handler, "$probe: debug level only")
        }
    }

    private fun functionBody(
        source: String,
        signature: String
    ): String {
        val from = source.indexOf(signature)
        check(from >= 0) { "not found: $signature" }
        val next = source.indexOf("override ", from + signature.length)
        return source.substring(from, if (next > 0) next else source.length)
    }

    private fun logText(e: Throwable): String {
        val method =
            runCatching { Class.forName(HELPER_CLASS).getMethod("sdkRefusalLogText", Throwable::class.java) }
                .getOrElse { fail("no sdkRefusalLogText(Throwable) in $HELPER_CLASS: the probes cannot log the SDK's reason") }
        return try {
            method.invoke(null, e) as String
        } catch (ite: InvocationTargetException) {
            throw ite.targetException
        }
    }

    private companion object {
        const val HELPER_CLASS = "co.electriccoin.zcash.ui.common.datasource.SdkRefusalLogTextKt"
        const val PROPOSAL_DATA_SOURCE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/ProposalDataSource.kt"

        // Synthetic, the shape of real ones (not valid checksums).
        val UNIFIED = "u1" + "qpzry9x8gf2tvdw0s3jn54khce6mua7l".repeat(4)
        const val TRANSPARENT = "t1Syntheticaddressxxxxxxxxxxxxxxxxx"
        val SAPLING = "zs1" + "qpzry9x8gf2tvdw0s3jn54khce6mua7l".repeat(2) + "qpzry9"
        const val MEMO_B64 = "WkNIQVQgc2VuZC1hbGwgZmVlIHByb2Jl"
        val TXID = "0123456789abcdef".repeat(4)
    }
}
