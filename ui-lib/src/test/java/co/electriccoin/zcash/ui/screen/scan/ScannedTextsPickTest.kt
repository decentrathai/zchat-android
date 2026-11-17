package co.electriccoin.zcash.ui.screen.scan

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * QR-7 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §1.3-§1.4.3 with §9 row 3): when a picture holds several QR
 * codes, the scanner picks one only when they name the same person, and never guesses a recipient.
 *
 * `ScannedTexts.pick(flow, texts)` returns `Pick.One(text)`, `Pick.None` or `Pick.Ambiguous`; `flow` is null for the
 * generic address scanner (Quantum Shield and the generic scan). At the parent the gallery gave whichever single code
 * ZXing found first, in every flow, Wallet Send included. The picker is reached by reflection so this test compiles
 * before it exists. Addresses and keys are synthetic.
 */
class ScannedTextsPickTest {
    private val a = "u1" + "a".repeat(211)
    private val b = "u1" + "b".repeat(211)
    private val keyA = "0a".repeat(32)
    private val keyB = "0b".repeat(32)

    private fun code(
        address: String,
        key: String? = keyA
    ): String = "zchat:c1?z=$address" + (key?.let { "&n=$it&r=wss%3A%2F%2Frelay.zsend.xyz" } ?: "")

    private val seedBackup = "{\"v\":1,\"seed\":\"synthetic words only\",\"birthday\":419200}"

    private fun pick(
        flow: ScanFlow?,
        texts: List<String>
    ): String {
        val type =
            try {
                Class.forName("co.electriccoin.zcash.ui.screen.scan.ScannedTexts")
            } catch (_: ClassNotFoundException) {
                fail("ScannedTexts missing: a picture's codes are not compared, so a two-person picture gives one at random")
            }
        val instance = type.getField("INSTANCE").get(null)
        val method = type.getMethod("pick", ScanFlow::class.java, List::class.java)
        val result =
            try {
                method.invoke(instance, flow, texts)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        val kind = result.javaClass.simpleName
        return if (kind == "One") "One(" + result.javaClass.getMethod("getText").invoke(result) + ")" else kind
    }

    private fun one(text: String) = "One($text)"

    @Test
    fun zchat_address_and_its_code_gives_the_code() {
        assertEquals(one(code(a)), pick(ScanFlow.ZCHAT, listOf(a, code(a))))
        assertEquals(one(code(a)), pick(ScanFlow.ZCHAT, listOf(code(a), a)))
    }

    @Test
    fun zchat_one_address_gives_it() {
        assertEquals(one(a), pick(ScanFlow.ZCHAT, listOf(a)))
    }

    @Test
    fun zchat_two_people_in_one_picture_is_ambiguous() {
        assertEquals("Ambiguous", pick(ScanFlow.ZCHAT, listOf(a, code(b))))
        assertEquals("Ambiguous", pick(ScanFlow.ZCHAT, listOf(code(a), code(b, keyB))))
        assertEquals("Ambiguous", pick(ScanFlow.ZCHAT, listOf(a, b)))
    }

    @Test
    fun zchat_one_address_with_two_different_keys_is_ambiguous() {
        assertEquals("Ambiguous", pick(ScanFlow.ZCHAT, listOf(code(a, keyA), code(a, keyB))))
    }

    @Test
    fun zchat_a_code_next_to_something_that_is_not_a_contact_is_ambiguous() {
        assertEquals("Ambiguous", pick(ScanFlow.ZCHAT, listOf(code(a), "https://example.invalid/page")))
        assertEquals("Ambiguous", pick(ScanFlow.ZCHAT, listOf(code(a), seedBackup)))
    }

    @Test
    fun no_text_gives_none_in_every_flow() {
        for (flow in ScanFlow.values().toList() + listOf(null)) {
            assertEquals("None", pick(flow, emptyList()), "flow $flow")
        }
    }

    @Test
    fun money_flows_take_one_text_and_refuse_two() {
        for (flow in listOf(ScanFlow.SEND, ScanFlow.HOMEPAGE, ScanFlow.ADDRESS_BOOK, null)) {
            assertEquals(one(a), pick(flow, listOf(a)), "flow $flow, one text")
            assertEquals("Ambiguous", pick(flow, listOf(a, b)), "flow $flow, two addresses")
            // Even the same person's address and code: a money flow never chooses between two texts.
            assertEquals("Ambiguous", pick(flow, listOf(a, code(a))), "flow $flow, address and code")
        }
    }

    @Test
    fun the_same_text_twice_is_one() {
        for (flow in ScanFlow.values().toList() + listOf(null)) {
            assertEquals(one(a), pick(flow, listOf(a, a)), "flow $flow")
        }
    }

    @Test
    fun restore_seed_takes_the_backup_and_refuses_two() {
        assertEquals(one(seedBackup), pick(ScanFlow.RESTORE_SEED, listOf(seedBackup)))
        assertEquals("Ambiguous", pick(ScanFlow.RESTORE_SEED, listOf(seedBackup, a)))
    }

    @Test
    fun a_quantum_shield_payload_next_to_another_code_is_ambiguous() {
        val zcpsk = "ZCPSK:" + "QUJD".repeat(20)
        assertEquals(one(zcpsk), pick(null, listOf(zcpsk)))
        assertEquals("Ambiguous", pick(null, listOf(zcpsk, a)))
    }
}
