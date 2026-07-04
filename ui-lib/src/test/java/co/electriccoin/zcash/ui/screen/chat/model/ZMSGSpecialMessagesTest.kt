package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + malformed/null-rejection coverage for [ZMSGSpecialMessages] — the ZCHAT
 * special-message protocol (reactions, read receipts, status, time-locks, unlocks, payment requests).
 *
 * Pure JVM: the only crypto is [ZMSGProtocol.generateAddressHash] (SHA-256 via
 * java.security.MessageDigest), so no Android SDK types are touched by any create/parse path here.
 * The [AddressCache] is seeded so senderAddress resolution can be asserted.
 *
 * ZEXP (DisappearPolicyTest) and ZMODE (ModeChangeControlTest) are covered elsewhere — not duplicated.
 */
class ZMSGSpecialMessagesTest {

    // ── In-memory AddressCache (mirrors the androidTest TestAddressCache) ──
    private class TestAddressCache : AddressCache {
        private val cache = mutableMapOf<String, String>()
        private val partners = mutableSetOf<String>()

        override fun cacheAddress(hash: String, address: String) { cache[hash] = address }
        override fun cacheAddressValidated(hash: String, address: String) { cache[hash] = address }
        override fun getAddress(hash: String): String? = cache[hash]
        override fun hasAddress(hash: String): Boolean = cache.containsKey(hash)
        override fun getAllCachedAddresses(): Map<String, String> = cache.toMap()
        override fun addConversationPartner(address: String) { partners.add(address) }
        override fun getConversationPartners(): Set<String> = partners.toSet()
        override fun isConversationPartner(address: String): Boolean = partners.contains(address)
        override fun findConversationPartnerByHash(hash: String): String? =
            partners.find {
                ZMSGProtocol.generateAddressHash(it) == hash ||
                    ZMSGProtocol.generateLegacyAddressHash(it) == hash
            }
    }

    private val sender = "u1testsenderaddressforzspecialmessages000000000000000000000000000000"
    private val senderHash = ZMSGProtocol.generateAddressHash(sender)
    private val txId = "abc123def456abc123def456abc123def456abc123def456abc123def456abcd"

    /** A cache pre-seeded with [sender] so senderAddress resolution can be asserted. */
    private fun seededCache() = TestAddressCache().apply { cacheAddress(senderHash, sender) }

    // ==========================================
    // REACTIONS (ZREACT)
    // ==========================================

    @Test
    fun reaction_roundTrip_recoversTargetEmojiSenderAndAddress() {
        val wire = ZMSGSpecialMessages.createReaction(txId, "👍", sender)
        assertTrue(ZMSGSpecialMessages.isReaction(wire))
        val parsed = ZMSGSpecialMessages.parseReaction(wire, seededCache())
        assertEquals(txId, parsed?.targetTxId)
        assertEquals("👍", parsed?.emoji) // unicode emoji survives the wire intact
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun reaction_unknownHash_senderAddressNull_butHashStillRecovered() {
        val wire = ZMSGSpecialMessages.createReaction(txId, "🔥", sender)
        // Empty cache → hash present but address unresolved
        val parsed = ZMSGSpecialMessages.parseReaction(wire, TestAddressCache())
        assertEquals("🔥", parsed?.emoji)
        assertEquals(senderHash, parsed?.senderHash)
        assertNull(parsed?.senderAddress)
    }

    @Test
    fun reaction_exactWireShape() {
        val wire = ZMSGSpecialMessages.createReaction(txId, "😀", sender)
        assertEquals("ZREACT|$txId|😀|$senderHash", wire)
    }

    @Test
    fun reaction_fewerThanTwoParts_returnsNull() {
        // "ZREACT|onlytarget" → content "onlytarget" → 1 part → null
        assertNull(ZMSGSpecialMessages.parseReaction("ZREACT|onlytarget", TestAddressCache()))
    }

    @Test
    fun reaction_nonReactionPrefix_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseReaction("ZRCPT|$txId|$senderHash", TestAddressCache()))
        assertNull(ZMSGSpecialMessages.parseReaction("plain text", TestAddressCache()))
    }

    @Test
    fun isReaction_onlyForReactPrefix() {
        assertTrue(ZMSGSpecialMessages.isReaction("ZREACT|x|y|z"))
        assertFalse(ZMSGSpecialMessages.isReaction("ZRCPT|x|y"))
    }

    // ==========================================
    // READ RECEIPTS (ZRCPT)
    // ==========================================

    @Test
    fun readReceipt_roundTrip_recoversTargetSenderAndAddress() {
        val wire = ZMSGSpecialMessages.createReadReceipt(txId, sender)
        assertTrue(ZMSGSpecialMessages.isReadReceipt(wire))
        val parsed = ZMSGSpecialMessages.parseReadReceipt(wire, seededCache())
        assertEquals(txId, parsed?.targetTxId)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun readReceipt_exactWireShape() {
        val wire = ZMSGSpecialMessages.createReadReceipt(txId, sender)
        assertEquals("ZRCPT|$txId|$senderHash", wire)
    }

    @Test
    fun readReceipt_unknownHash_senderAddressNull() {
        val wire = ZMSGSpecialMessages.createReadReceipt(txId, sender)
        val parsed = ZMSGSpecialMessages.parseReadReceipt(wire, TestAddressCache())
        assertEquals(txId, parsed?.targetTxId)
        assertEquals(senderHash, parsed?.senderHash)
        assertNull(parsed?.senderAddress)
    }

    @Test
    fun readReceipt_nonReceiptPrefix_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseReadReceipt("ZREACT|$txId|👍|$senderHash", TestAddressCache()))
    }

    @Test
    fun isReadReceipt_onlyForReceiptPrefix() {
        assertTrue(ZMSGSpecialMessages.isReadReceipt("ZRCPT|$txId|$senderHash"))
        assertFalse(ZMSGSpecialMessages.isReadReceipt("ZREACT|$txId|👍|$senderHash"))
        assertFalse(ZMSGSpecialMessages.isReadReceipt("ZSTAT|hi|$senderHash"))
    }

    // ==========================================
    // USER STATUS (ZSTAT)
    // ==========================================

    @Test
    fun status_roundTrip_recoversTextSenderAndAddress() {
        val wire = ZMSGSpecialMessages.createStatusMessage("Available now", sender)
        assertTrue(ZMSGSpecialMessages.isStatus(wire))
        val parsed = ZMSGSpecialMessages.parseStatus(wire, seededCache())
        assertEquals("Available now", parsed?.statusText)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun status_textOver100Chars_truncatedTo100OnCreate() {
        val longStatus = "a".repeat(150)
        val wire = ZMSGSpecialMessages.createStatusMessage(longStatus, sender)
        val parsed = ZMSGSpecialMessages.parseStatus(wire, seededCache())
        assertEquals(100, parsed?.statusText?.length)
        assertEquals("a".repeat(100), parsed?.statusText)
    }

    @Test
    fun status_containingPipe_splitsOnLastPipeSoSenderHashIsCorrect() {
        // Status text itself contains '|': parse must use lastIndexOf('|') so the hash still separates.
        val statusWithPipe = "on call | back at 5"
        val wire = ZMSGSpecialMessages.createStatusMessage(statusWithPipe, sender)
        val parsed = ZMSGSpecialMessages.parseStatus(wire, seededCache())
        assertEquals(statusWithPipe, parsed?.statusText)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun status_noPipe_returnsNull() {
        // "ZSTAT|" + content with no pipe → lastIndexOf == -1 → null
        assertNull(ZMSGSpecialMessages.parseStatus("ZSTAT|nopipehere", TestAddressCache()))
    }

    @Test
    fun status_nonStatusPrefix_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseStatus("ZRCPT|$txId|$senderHash", TestAddressCache()))
    }

    // ==========================================
    // TIME-LOCK: SCHEDULED (ZTL|SCH)
    // ==========================================

    @Test
    fun timeLockScheduled_roundTrip() {
        val wire = ZMSGSpecialMessages.createScheduledMessage("see you soon", sender, 1_800_000_000L)
        assertTrue(ZMSGSpecialMessages.isTimeLock(wire))
        val parsed = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())
        assertEquals(TimeLockType.SCHEDULED, parsed?.lockType)
        assertEquals("see you soon", parsed?.message)
        assertEquals(1_800_000_000L, parsed?.unlockTimestamp)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
        assertNull(parsed?.unlockBlockHeight)
        assertNull(parsed?.requiredPayment)
    }

    @Test
    fun timeLockScheduled_messageWithPipes_isRejoined() {
        val wire = ZMSGSpecialMessages.createScheduledMessage("a|b|c", sender, 100L)
        val parsed = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())
        assertEquals("a|b|c", parsed?.message) // drop(3).joinToString("|") restores embedded pipes
    }

    @Test
    fun timeLockScheduled_nonNumericTimestamp_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseTimeLock("ZTL|SCH|soon|$senderHash|hi", TestAddressCache()))
    }

    // ==========================================
    // TIME-LOCK: BLOCK (ZTL|BLK)
    // ==========================================

    @Test
    fun timeLockBlock_roundTrip() {
        val wire = ZMSGSpecialMessages.createBlockLockedMessage("unlock at height", sender, 2_500_000L)
        val parsed = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())
        assertEquals(TimeLockType.BLOCK_HEIGHT, parsed?.lockType)
        assertEquals("unlock at height", parsed?.message)
        assertEquals(2_500_000L, parsed?.unlockBlockHeight)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
        assertNull(parsed?.unlockTimestamp)
    }

    @Test
    fun timeLockBlock_nonNumericHeight_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseTimeLock("ZTL|BLK|soon|$senderHash|hi", TestAddressCache()))
    }

    // ==========================================
    // TIME-LOCK: PAYMENT (ZTL|PAY)
    // ==========================================

    @Test
    fun timeLockPayment_roundTrip() {
        val wire = ZMSGSpecialMessages.createPaymentLockedMessage("pay to reveal", sender, 20_000L)
        val parsed = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())
        assertEquals(TimeLockType.PAYMENT, parsed?.lockType)
        assertEquals("pay to reveal", parsed?.message)
        assertEquals(20_000L, parsed?.requiredPayment)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun timeLockPayment_nonNumericAmount_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseTimeLock("ZTL|PAY|lots|$senderHash|hi", TestAddressCache()))
    }

    // ==========================================
    // TIME-LOCK: CONDITIONAL (ZTL|CND)
    // ==========================================

    @Test
    fun timeLockConditional_roundTrip_recoversHintAnswerHashAndMessage() {
        val wire = ZMSGSpecialMessages.createConditionalMessage(
            message = "secret msg",
            senderAddress = sender,
            answer = "SolarPunk",
            hint = "the theme"
        )
        val parsed = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())
        assertEquals(TimeLockType.CONDITIONAL, parsed?.lockType)
        assertEquals("secret msg", parsed?.message)
        assertEquals("the theme", parsed?.hint)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
        // answerHash is verifiable and NOT the plaintext answer
        assertTrue(ZMSGSpecialMessages.verifyConditionalAnswer("SolarPunk", parsed!!.answerHash!!))
    }

    @Test
    fun timeLockConditional_hintPipesReplacedWithDash() {
        val wire = ZMSGSpecialMessages.createConditionalMessage("m", sender, "ans", "a|b|c")
        val parsed = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())
        assertEquals("a-b-c", parsed?.hint) // pipes in hint become dashes to keep parsing sane
    }

    @Test
    fun timeLockConditional_messageWithPipes_rejoinedViaDrop4() {
        val wire = ZMSGSpecialMessages.createConditionalMessage("x|y|z", sender, "ans", "hint")
        val parsed = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())
        assertEquals("x|y|z", parsed?.message)
    }

    @Test
    fun timeLockConditional_fewerThanFiveParts_returnsNull() {
        // ZTL|CND|answerHash|hint|senderHash|message needs 5 parts after prefix; give only 4.
        assertNull(ZMSGSpecialMessages.parseTimeLock("ZTL|CND|hash|hint|$senderHash", TestAddressCache()))
    }

    @Test
    fun verifyConditionalAnswer_caseAndWhitespaceInsensitive() {
        val wire = ZMSGSpecialMessages.createConditionalMessage("m", sender, "MySecret", "hint")
        val hash = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())!!.answerHash!!
        assertTrue(ZMSGSpecialMessages.verifyConditionalAnswer("mysecret", hash))
        assertTrue(ZMSGSpecialMessages.verifyConditionalAnswer("  MYSECRET  ", hash))
        assertTrue(ZMSGSpecialMessages.verifyConditionalAnswer("MySecret", hash))
    }

    @Test
    fun verifyConditionalAnswer_wrongAnswer_false() {
        val wire = ZMSGSpecialMessages.createConditionalMessage("m", sender, "right", "hint")
        val hash = ZMSGSpecialMessages.parseTimeLock(wire, seededCache())!!.answerHash!!
        assertFalse(ZMSGSpecialMessages.verifyConditionalAnswer("wrong", hash))
    }

    // ==========================================
    // TIME-LOCK: shared malformed handling
    // ==========================================

    @Test
    fun timeLock_nonTimeLockPrefix_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseTimeLock("ZRCPT|$txId|$senderHash", TestAddressCache()))
    }

    @Test
    fun timeLock_fewerThanFourParts_returnsNull() {
        // ZTL|SCH|123 → only 3 parts after prefix → null
        assertNull(ZMSGSpecialMessages.parseTimeLock("ZTL|SCH|123", TestAddressCache()))
    }

    @Test
    fun timeLock_unknownLockType_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseTimeLock("ZTL|XXX|123|$senderHash|hi", TestAddressCache()))
    }

    @Test
    fun isTimeLock_onlyForTimeLockPrefix() {
        assertTrue(ZMSGSpecialMessages.isTimeLock("ZTL|SCH|1|h|m"))
        assertFalse(ZMSGSpecialMessages.isTimeLock("ZUNLOCK|PAY|$txId|$senderHash"))
    }

    // ==========================================
    // UNLOCK (ZUNLOCK)
    // ==========================================

    @Test
    fun unlockPayment_roundTrip() {
        val wire = ZMSGSpecialMessages.createUnlockPayment(txId, sender)
        assertTrue(ZMSGSpecialMessages.isUnlock(wire))
        val parsed = ZMSGSpecialMessages.parseUnlock(wire, seededCache())
        assertEquals(TimeLockType.PAYMENT, parsed?.unlockType)
        assertEquals(txId, parsed?.originalTxId)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
        assertNull(parsed?.answer)
    }

    @Test
    fun unlockAnswer_roundTrip_recoversAnswer() {
        val wire = ZMSGSpecialMessages.createUnlockAnswer(txId, "myanswer", sender)
        val parsed = ZMSGSpecialMessages.parseUnlock(wire, seededCache())
        assertEquals(TimeLockType.CONDITIONAL, parsed?.unlockType)
        assertEquals(txId, parsed?.originalTxId)
        assertEquals("myanswer", parsed?.answer)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun unlock_nonUnlockPrefix_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseUnlock("ZTL|PAY|1|$senderHash|hi", TestAddressCache()))
    }

    @Test
    fun unlockPayment_fewerThanThreeParts_returnsNull() {
        // ZUNLOCK|PAY|txid → only 2 parts after prefix → null
        assertNull(ZMSGSpecialMessages.parseUnlock("ZUNLOCK|PAY|$txId", TestAddressCache()))
    }

    @Test
    fun unlockConditional_fewerThanFourParts_returnsNull() {
        // ZUNLOCK|CND|txid|hash is 3 parts (needs 4 for the answer field) → null
        assertNull(ZMSGSpecialMessages.parseUnlock("ZUNLOCK|CND|$txId|$senderHash", TestAddressCache()))
    }

    @Test
    fun unlock_unknownType_returnsNull() {
        assertNull(ZMSGSpecialMessages.parseUnlock("ZUNLOCK|XXX|$txId|$senderHash", TestAddressCache()))
    }

    @Test
    fun isUnlock_onlyForUnlockPrefix() {
        assertTrue(ZMSGSpecialMessages.isUnlock("ZUNLOCK|PAY|$txId|$senderHash"))
        assertFalse(ZMSGSpecialMessages.isUnlock("ZTL|SCH|1|h|m"))
    }

    // ==========================================
    // PAYMENT REQUESTS (ZREQ)
    // ==========================================

    @Test
    fun paymentRequest_roundTrip_recoversAmountReasonSenderAddress() {
        val wire = ZMSGSpecialMessages.createPaymentRequest(50_000L, sender, "lunch")
        assertTrue(ZMSGSpecialMessages.isPaymentRequest(wire))
        val parsed = ZMSGSpecialMessages.parsePaymentRequest(wire, seededCache())
        assertEquals(50_000L, parsed?.amountZatoshi)
        assertEquals("lunch", parsed?.reason)
        assertEquals(senderHash, parsed?.senderHash)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun paymentRequest_reasonWithPipes_replacedWithSlash() {
        val wire = ZMSGSpecialMessages.createPaymentRequest(1_000L, sender, "a|b|c")
        val parsed = ZMSGSpecialMessages.parsePaymentRequest(wire, seededCache())
        assertEquals("a/b/c", parsed?.reason) // pipes in reason become '/'
    }

    @Test
    fun paymentRequest_reasonOptional_defaultsEmpty() {
        val wire = ZMSGSpecialMessages.createPaymentRequest(1_000L, sender)
        assertEquals("ZREQ|1000|$senderHash|", wire)
        val parsed = ZMSGSpecialMessages.parsePaymentRequest(wire, seededCache())
        assertEquals(1_000L, parsed?.amountZatoshi)
        assertEquals("", parsed?.reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun paymentRequest_createWithZeroAmount_throws() {
        ZMSGSpecialMessages.createPaymentRequest(0L, sender, "nope")
    }

    @Test(expected = IllegalArgumentException::class)
    fun paymentRequest_createWithNegativeAmount_throws() {
        ZMSGSpecialMessages.createPaymentRequest(-5L, sender, "nope")
    }

    @Test
    fun paymentRequest_parseNonPositiveAmount_returnsNull() {
        assertNull(ZMSGSpecialMessages.parsePaymentRequest("ZREQ|0|$senderHash|x", TestAddressCache()))
        assertNull(ZMSGSpecialMessages.parsePaymentRequest("ZREQ|-10|$senderHash|x", TestAddressCache()))
    }

    @Test
    fun paymentRequest_parseNonNumericAmount_returnsNull() {
        assertNull(ZMSGSpecialMessages.parsePaymentRequest("ZREQ|lots|$senderHash|x", TestAddressCache()))
    }

    @Test
    fun paymentRequest_fewerThanTwoParts_returnsNull() {
        assertNull(ZMSGSpecialMessages.parsePaymentRequest("ZREQ|50000", TestAddressCache()))
    }

    @Test
    fun paymentRequest_nonRequestPrefix_returnsNull() {
        assertNull(ZMSGSpecialMessages.parsePaymentRequest("ZRCPT|$txId|$senderHash", TestAddressCache()))
    }

    @Test
    fun isPaymentRequest_onlyForRequestPrefix() {
        assertTrue(ZMSGSpecialMessages.isPaymentRequest("ZREQ|1000|$senderHash|"))
        assertFalse(ZMSGSpecialMessages.isPaymentRequest("ZRCPT|$txId|$senderHash"))
    }
}
