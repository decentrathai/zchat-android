package co.electriccoin.zcash.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * TURN-CREDS (fix-all 2.13.0, train T5; turn-creds SPEC §2.6, §3.1 T1, finding F5): a call that ends before or
 * during connection says why, honestly.
 *
 * Today a setup timeout AFTER the other phone answered (stage Connecting) shows "No answer", and nothing tells the user
 * that ZChat's call relay was not available (no local relay candidate was gathered), in which case only a direct
 * connection was tried. Wanted: one pure mapper, `CallFailure.reasonFor(kind, stage, relayCandidateSeen)`, used by the
 * three end sites of VoiceCallManager:
 *
 * | end event            | stage              | relay seen | reason                  |
 * |----------------------|--------------------|------------|-------------------------|
 * | setup timeout        | Dialling / Ringing | any        | Timeout (unchanged)     |
 * | setup timeout        | Connecting         | yes        | ConnectFailed (new)     |
 * | setup timeout        | Connecting         | no         | NoRelay (new)           |
 * | ICE failed           | before InCall      | yes        | IceFailed               |
 * | ICE failed           | before InCall      | no         | NoRelay                 |
 * | ICE failed or lost   | InCall             | any        | IceFailed               |
 *
 * Both new reasons carry wireString "timeout". They are sent only from the setup-timeout path, where Timeout is sent
 * today, and the ICE path sends no HANGUP, so the other phone (and ZChat 2.12.4) receives byte-identical signals.
 *
 * The new API is reached by name, so this file compiles before it exists.
 */
class CallFailureReasonTest {
    private val failureClass: Class<*> by lazy {
        runCatching { Class.forName("co.electriccoin.zcash.ui.call.CallFailure") }.getOrElse {
            throw AssertionError("no co.electriccoin.zcash.ui.call.CallFailure: every early call end is still mapped by hand")
        }
    }

    private fun enumValue(
        simpleName: String,
        constant: String,
    ): Any {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.call.CallFailure\$$simpleName") }.getOrElse {
                throw AssertionError("no enum CallFailure.$simpleName")
            }
        return cls.enumConstants?.firstOrNull { (it as Enum<*>).name == constant }
            ?: throw AssertionError("CallFailure.$simpleName has no $constant (found ${cls.enumConstants?.toList()})")
    }

    private fun reasonFor(
        kind: String,
        stage: String,
        relaySeen: Boolean,
    ): CallEndReason {
        val method =
            failureClass.methods.firstOrNull { it.name == "reasonFor" && it.parameterCount == 3 }
                ?: throw AssertionError("CallFailure has no reasonFor(kind, stage, relayCandidateSeen)")
        val receiver =
            if (Modifier.isStatic(method.modifiers)) {
                null
            } else {
                runCatching { failureClass.getField("INSTANCE").get(null) }.getOrNull()
                    ?: throw AssertionError("CallFailure.reasonFor is neither static nor on an object")
            }
        return method.invoke(receiver, enumValue("Kind", kind), enumValue("Stage", stage), relaySeen) as CallEndReason
    }

    private fun reasonObject(simpleName: String): CallEndReason =
        runCatching {
            Class.forName("co.electriccoin.zcash.ui.call.CallEndReason\$$simpleName").getField("INSTANCE").get(null)
        }.getOrNull() as? CallEndReason
            ?: throw AssertionError("CallEndReason.$simpleName does not exist")

    @Test
    fun `the enums name the three end events and the four call stages`() {
        listOf("SETUP_TIMEOUT", "ICE_FAILED", "ICE_LOST").forEach { enumValue("Kind", it) }
        listOf("DIALLING", "RINGING", "CONNECTING", "IN_CALL").forEach { enumValue("Stage", it) }
    }

    @Test
    fun `a setup timeout before the answer is still No answer`() {
        listOf("DIALLING", "RINGING").forEach { stage ->
            listOf(true, false).forEach { seen ->
                assertSame("SETUP_TIMEOUT/$stage/relay=$seen", CallEndReason.Timeout, reasonFor("SETUP_TIMEOUT", stage, seen))
            }
        }
    }

    @Test
    fun `a setup timeout after the answer is not No answer`() {
        assertSame(reasonObject("ConnectFailed"), reasonFor("SETUP_TIMEOUT", "CONNECTING", true))
        assertSame(reasonObject("NoRelay"), reasonFor("SETUP_TIMEOUT", "CONNECTING", false))
    }

    @Test
    fun `ICE failing before the call is up says whether the relay was there`() {
        listOf("DIALLING", "RINGING", "CONNECTING").forEach { stage ->
            assertSame("ICE_FAILED/$stage/relay seen", CallEndReason.IceFailed, reasonFor("ICE_FAILED", stage, true))
            assertSame("ICE_FAILED/$stage/no relay", reasonObject("NoRelay"), reasonFor("ICE_FAILED", stage, false))
        }
    }

    @Test
    fun `ICE failing or lost in a live call is Connection failed`() {
        listOf("ICE_FAILED", "ICE_LOST").forEach { kind ->
            listOf(true, false).forEach { seen ->
                assertSame("$kind/IN_CALL/relay=$seen", CallEndReason.IceFailed, reasonFor(kind, "IN_CALL", seen))
            }
        }
    }

    @Test
    fun `the two new reasons send what Timeout sends today`() {
        assertEquals("timeout", reasonObject("NoRelay").wireString)
        assertEquals("timeout", reasonObject("ConnectFailed").wireString)
        // The old pins (CallEndReasonTest) still hold.
        assertEquals("timeout", CallEndReason.Timeout.wireString)
        assertEquals("ice_failed", CallEndReason.IceFailed.wireString)
    }

    @Test
    fun `every mapped setup-timeout reason sends timeout, so the peer sees the same HANGUP`() {
        listOf("DIALLING", "RINGING", "CONNECTING").forEach { stage ->
            listOf(true, false).forEach { seen ->
                assertEquals("SETUP_TIMEOUT/$stage/relay=$seen", "timeout", reasonFor("SETUP_TIMEOUT", stage, seen).wireString)
            }
        }
    }
}
