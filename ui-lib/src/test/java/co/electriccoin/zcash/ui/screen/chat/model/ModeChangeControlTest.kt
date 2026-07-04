package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZMODE conversation-mode change control — wire-format round-trip + validation + since-clamp.
 * Mirrors the B17 ZEXP (DisappearPolicy) test approach: pure JVM, no Android deps.
 * Wire: ZMODE|v1|<MODE>|<sinceMillis>|<senderHash>
 */
class ModeChangeControlTest {
    private val sender = "u1testsenderaddressforzmodecontrol0000000000000000000000000000000000"
    private val senderHash = ZMSGProtocol.generateAddressHash(sender)
    private val now = 1_750_000_000_000L

    @Test
    fun roundTrip_allModes() {
        for (mode in ConversationMode.entries) {
            val wire = ZMSGSpecialMessages.createModeChange(mode, 1_234_567L, sender)
            assertTrue(ZMSGSpecialMessages.isModeChange(wire), "isModeChange must accept its own output for $mode")
            val parsed = ZMSGSpecialMessages.parseModeChange(wire)
            assertEquals(mode, parsed?.mode)
            assertEquals(1_234_567L, parsed?.sinceMillis)
            assertEquals(senderHash, parsed?.senderHash)
        }
    }

    @Test
    fun createModeChange_exactWireShape() {
        val wire = ZMSGSpecialMessages.createModeChange(ConversationMode.TUNNEL, 42L, sender)
        assertEquals("ZMODE|v1|TUNNEL|42|$senderHash", wire)
    }

    @Test
    fun facade_delegates() {
        val wire = ZMSGProtocol.createModeChange(ConversationMode.OPEN, 99L, sender)
        assertTrue(ZMSGProtocol.isModeChange(wire))
        val parsed = ZMSGProtocol.parseModeChange(wire)
        assertEquals(ConversationMode.OPEN, parsed?.mode)
        assertEquals(99L, parsed?.sinceMillis)
        assertEquals(senderHash, parsed?.senderHash)
    }

    @Test
    fun isModeChange_rejectsOtherControls() {
        assertFalse(ZMSGSpecialMessages.isModeChange("ZEXP|v1|60|1|abc"))
        assertFalse(ZMSGSpecialMessages.isModeChange("ZMSG|v4|whatever"))
        assertFalse(ZMSGSpecialMessages.isModeChange("plain text"))
    }

    @Test
    fun parse_rejectsNonModeMemo() = assertNull(ZMSGSpecialMessages.parseModeChange("ZEXP|v1|60|1|abc"))

    @Test
    fun parse_rejectsUnknownVersion() = assertNull(ZMSGSpecialMessages.parseModeChange("ZMODE|v2|TUNNEL|42|abc"))

    @Test
    fun parse_rejectsUnknownMode() = assertNull(ZMSGSpecialMessages.parseModeChange("ZMODE|v1|PIGEON|42|abc"))

    @Test
    fun parse_rejectsMissingFields() = assertNull(ZMSGSpecialMessages.parseModeChange("ZMODE|v1|TUNNEL|42"))

    @Test
    fun parse_rejectsNonNumericSince() = assertNull(ZMSGSpecialMessages.parseModeChange("ZMODE|v1|TUNNEL|soon|abc"))

    @Test
    fun parse_rejectsNonPositiveSince() {
        assertNull(ZMSGSpecialMessages.parseModeChange("ZMODE|v1|TUNNEL|0|abc"))
        assertNull(ZMSGSpecialMessages.parseModeChange("ZMODE|v1|TUNNEL|-5|abc"))
    }

    @Test
    fun parse_emptySenderHash_becomesNull() {
        val parsed = ZMSGSpecialMessages.parseModeChange("ZMODE|v1|VAULT|42|")
        assertEquals(ConversationMode.VAULT, parsed?.mode)
        assertNull(parsed?.senderHash)
    }

    // ── since-clamp (forged-future defense, mirrors the B17 TTL clamp) ──

    @Test
    fun clamp_pastSince_passesThrough() =
        assertEquals(now - 1_000L, ZMSGSpecialMessages.clampModeChangeSince(now - 1_000L, now))

    @Test
    fun clamp_slightlyFutureSince_withinFiveMinutes_passesThrough() =
        assertEquals(now + 299_999L, ZMSGSpecialMessages.clampModeChangeSince(now + 299_999L, now))

    @Test
    fun clamp_forgedFarFutureSince_clampedToNowPlusFiveMinutes() =
        assertEquals(now + 300_000L, ZMSGSpecialMessages.clampModeChangeSince(now + 999_999_999L, now))

    @Test
    fun clamp_facadeDelegates() =
        assertEquals(now + 300_000L, ZMSGProtocol.clampModeChangeSince(Long.MAX_VALUE, now))
}
