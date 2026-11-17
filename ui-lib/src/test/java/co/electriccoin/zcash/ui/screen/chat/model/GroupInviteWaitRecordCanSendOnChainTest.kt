package co.electriccoin.zcash.ui.screen.chat.model

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** ZC-517: eligibility is the decoded memo's UTF-8 bytes, never the address or stored JSON length. */
@RunWith(Parameterized::class)
class GroupInviteWaitRecordCanSendOnChainTest(
    private val label: String,
    private val record: String?,
    private val expected: Boolean,
) {
    @Test
    fun the_record_uses_the_same_byte_limit_as_the_central_memo_guard() {
        val method =
            runCatching {
                GroupInviteWaitRecord::class.java.getDeclaredMethod("canSendOnChain", String::class.java)
            }.getOrNull()
        assertNotNull(method, "ZC-517: GroupInviteWaitRecord has no canSendOnChain(String?) reading")
        val actual =
            try {
                method.invoke(GroupInviteWaitRecord, record) as Boolean
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        assertEquals(expected, actual, label)
    }

    companion object {
        private fun record(memo: String): String = JSONObject().put("memo", memo).put("relay_failed", true).toString()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> =
            listOf(
                arrayOf("ASCII 511 bytes", record("q".repeat(511)), true),
                arrayOf("ASCII exactly 512 bytes", record("q".repeat(512)), true),
                arrayOf("ASCII 513 bytes", record("q".repeat(513)), false),
                arrayOf("256 accented characters exactly 512 bytes", record("é".repeat(256)), true),
                arrayOf("257 accented characters 514 bytes", record("é".repeat(257)), false),
                arrayOf("128 supplementary characters exactly 512 bytes", record("🙂".repeat(128)), true),
                arrayOf("129 supplementary characters 516 bytes", record("🙂".repeat(129)), false),
                arrayOf("JSON escapes do not count toward memo size", record("\"".repeat(512)), true),
                arrayOf("missing record", null, false),
                arrayOf("malformed record", "{", false),
                arrayOf("record without memo", "{\"relay_failed\":true}", false),
                arrayOf("record with a non-string memo", "{\"memo\":123}", false),
            )
    }
}
