package co.electriccoin.zcash.ui.screen.chat.model

import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

/**
 * Tests for ZMSGConstants to ensure all protocol constants are consistent
 * and meet the requirements for reliable message routing.
 */
class ZMSGConstantsTest {

    // ==========================================
    // MEMO SIZE
    // ==========================================

    @Test
    @SmallTest
    fun maxMemoSize_is512() {
        // Zcash memo field is always 512 bytes
        assertThat(ZMSGConstants.MAX_MEMO_SIZE, equalTo(512))
    }

    // ==========================================
    // CONVERSATION ID
    // ==========================================

    @Test
    @SmallTest
    fun convIdLength_is8() {
        assertThat(ZMSGConstants.CONV_ID_LENGTH, equalTo(8))
    }

    @Test
    @SmallTest
    fun convIdChars_contains36Characters() {
        // 26 letters + 10 digits = 36
        assertThat(ZMSGConstants.CONV_ID_CHARS.length, equalTo(36))
    }

    @Test
    @SmallTest
    fun convIdChars_isUppercaseAlphanumeric() {
        val chars = ZMSGConstants.CONV_ID_CHARS
        assertThat(chars.all { it.isUpperCase() || it.isDigit() }, equalTo(true))
    }

    @Test
    @SmallTest
    fun convIdChars_containsAllUppercaseLetters() {
        val chars = ZMSGConstants.CONV_ID_CHARS
        for (c in 'A'..'Z') {
            assertThat("Missing letter $c", chars.contains(c), equalTo(true))
        }
    }

    @Test
    @SmallTest
    fun convIdChars_containsAllDigits() {
        val chars = ZMSGConstants.CONV_ID_CHARS
        for (c in '0'..'9') {
            assertThat("Missing digit $c", chars.contains(c), equalTo(true))
        }
    }

    // ==========================================
    // HASH LENGTHS
    // ==========================================

    @Test
    @SmallTest
    fun hashLength_legacy_is12() {
        // 6 bytes = 12 hex characters
        assertThat(ZMSGConstants.HASH_LENGTH, equalTo(12))
    }

    @Test
    @SmallTest
    fun hashLength_new_is16() {
        // 8 bytes = 16 hex characters
        assertThat(ZMSGConstants.HASH_LENGTH_NEW, equalTo(16))
    }

    @Test
    @SmallTest
    fun hashLength_new_greaterThanLegacy() {
        // New hash should be longer for better collision resistance
        assertThat(ZMSGConstants.HASH_LENGTH_NEW > ZMSGConstants.HASH_LENGTH, equalTo(true))
    }

    // ==========================================
    // PROTOCOL PREFIXES
    // ==========================================

    @Test
    @SmallTest
    fun prefixV4_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.V4, equalTo("ZMSG|v4|"))
    }

    @Test
    @SmallTest
    fun prefixV4C_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.V4C, equalTo("ZMSG|v4c|"))
    }

    @Test
    @SmallTest
    fun prefixV3_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.V3, equalTo("ZMSG|v3|"))
    }

    @Test
    @SmallTest
    fun prefixV3C_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.V3C, equalTo("ZMSG|v3c|"))
    }

    @Test
    @SmallTest
    fun prefixV2_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.V2, equalTo("ZMSG|v2|"))
    }

    @Test
    @SmallTest
    fun prefixGroup_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.GROUP, equalTo("ZMSG:3.0:GROUP:"))
    }

    @Test
    @SmallTest
    fun allPrefixes_startWithZMSG() {
        assertThat(ZMSGConstants.Prefixes.V4.startsWith("ZMSG"), equalTo(true))
        assertThat(ZMSGConstants.Prefixes.V4C.startsWith("ZMSG"), equalTo(true))
        assertThat(ZMSGConstants.Prefixes.V3.startsWith("ZMSG"), equalTo(true))
        assertThat(ZMSGConstants.Prefixes.V3C.startsWith("ZMSG"), equalTo(true))
        assertThat(ZMSGConstants.Prefixes.V2.startsWith("ZMSG"), equalTo(true))
        assertThat(ZMSGConstants.Prefixes.GROUP.startsWith("ZMSG"), equalTo(true))
    }

    // ==========================================
    // SPECIAL MESSAGE PREFIXES
    // ==========================================

    @Test
    @SmallTest
    fun prefixReaction_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.REACTION, equalTo("ZREACT|"))
    }

    @Test
    @SmallTest
    fun prefixReceipt_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.RECEIPT, equalTo("ZRCPT|"))
    }

    @Test
    @SmallTest
    fun prefixStatus_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.STATUS, equalTo("ZSTAT|"))
    }

    @Test
    @SmallTest
    fun prefixTimeLock_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.TIMELOCK, equalTo("ZTL|"))
    }

    @Test
    @SmallTest
    fun prefixUnlock_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.UNLOCK, equalTo("ZUNLOCK|"))
    }

    @Test
    @SmallTest
    fun prefixRequest_correctFormat() {
        assertThat(ZMSGConstants.Prefixes.REQUEST, equalTo("ZREQ|"))
    }

    // ==========================================
    // MARKERS
    // ==========================================

    @Test
    @SmallTest
    fun markerInit_correctFormat() {
        assertThat(ZMSGConstants.Markers.INIT, equalTo("INIT|"))
    }

    @Test
    @SmallTest
    fun markerCont_correctFormat() {
        assertThat(ZMSGConstants.Markers.CONT, equalTo("CONT|"))
    }

    @Test
    @SmallTest
    fun markerRef_correctFormat() {
        assertThat(ZMSGConstants.Markers.REF, equalTo("REF|"))
    }

    @Test
    @SmallTest
    fun markerReply_correctFormat() {
        assertThat(ZMSGConstants.Markers.REPLY, equalTo("RPL|"))
    }

    @Test
    @SmallTest
    fun markerKex_correctFormat() {
        assertThat(ZMSGConstants.Markers.KEX, equalTo("KEX|"))
    }

    @Test
    @SmallTest
    fun markerKexAck_correctFormat() {
        assertThat(ZMSGConstants.Markers.KEX_ACK, equalTo("KEXACK|"))
    }

    @Test
    @SmallTest
    fun markerAddr_correctFormat() {
        assertThat(ZMSGConstants.Markers.ADDR, equalTo("ADDR|"))
    }

    // ==========================================
    // TIME-LOCK TYPES
    // ==========================================

    @Test
    @SmallTest
    fun timeLockScheduled_correctFormat() {
        assertThat(ZMSGConstants.TimeLockTypes.SCHEDULED, equalTo("SCH"))
    }

    @Test
    @SmallTest
    fun timeLockBlock_correctFormat() {
        assertThat(ZMSGConstants.TimeLockTypes.BLOCK, equalTo("BLK"))
    }

    @Test
    @SmallTest
    fun timeLockPayment_correctFormat() {
        assertThat(ZMSGConstants.TimeLockTypes.PAYMENT, equalTo("PAY"))
    }

    @Test
    @SmallTest
    fun timeLockConditional_correctFormat() {
        assertThat(ZMSGConstants.TimeLockTypes.CONDITIONAL, equalTo("CND"))
    }

    // ==========================================
    // CHUNK SIZES
    // ==========================================

    @Test
    @SmallTest
    fun chunkSizeV3Init_lessThanMaxMemo() {
        assertThat(ZMSGConstants.ChunkSizes.V3_INIT < ZMSGConstants.MAX_MEMO_SIZE, equalTo(true))
    }

    @Test
    @SmallTest
    fun chunkSizeV3ReplyFirst_lessThanMaxMemo() {
        assertThat(ZMSGConstants.ChunkSizes.V3_REPLY_FIRST < ZMSGConstants.MAX_MEMO_SIZE, equalTo(true))
    }

    @Test
    @SmallTest
    fun chunkSizeContinuation_lessThanMaxMemo() {
        assertThat(ZMSGConstants.ChunkSizes.CONTINUATION < ZMSGConstants.MAX_MEMO_SIZE, equalTo(true))
    }

    @Test
    @SmallTest
    fun chunkSizeV4Init_lessThanMaxMemo() {
        assertThat(ZMSGConstants.ChunkSizes.V4_INIT < ZMSGConstants.MAX_MEMO_SIZE, equalTo(true))
    }

    @Test
    @SmallTest
    fun chunkSizeV4ReplyFirst_lessThanMaxMemo() {
        assertThat(ZMSGConstants.ChunkSizes.V4_REPLY_FIRST < ZMSGConstants.MAX_MEMO_SIZE, equalTo(true))
    }

    @Test
    @SmallTest
    fun chunkSizeV4ReplyFirst_accountsForSenderHash() {
        // V4 reply includes 16-char sender hash for fallback, so it should be smaller than V3
        // (which only has 12-char hash)
        // The difference should account for: 4 extra hash chars + 8 convId chars = 12 chars more overhead
        // So V4_REPLY_FIRST should be noticeably smaller than V3_REPLY_FIRST
        assertThat(ZMSGConstants.ChunkSizes.V4_REPLY_FIRST < ZMSGConstants.ChunkSizes.V3_REPLY_FIRST, equalTo(true))
    }

    @Test
    @SmallTest
    fun chunkSizeV4Init_smallerThanV3Init() {
        // V4 INIT adds 8-char convId overhead
        assertThat(ZMSGConstants.ChunkSizes.V4_INIT < ZMSGConstants.ChunkSizes.V3_INIT, equalTo(true))
    }

    // ==========================================
    // REMOTE KILL
    // ==========================================

    @Test
    @SmallTest
    fun remoteKillPrefix_correctFormat() {
        assertThat(ZMSGConstants.REMOTE_KILL_PREFIX, equalTo("ZCHAT_DESTROY:"))
    }

    // ==========================================
    // PLATFORM ADDRESS
    // ==========================================

    @Test
    @SmallTest
    fun platformFeeAddress_isValidLength() {
        // Unified addresses are typically 141+ characters
        assertThat(ZMSGConstants.PLATFORM_FEE_ADDRESS.length > 140, equalTo(true))
    }

    @Test
    @SmallTest
    fun platformFeeAddress_startsWithU() {
        // Unified addresses start with 'u'
        assertThat(ZMSGConstants.PLATFORM_FEE_ADDRESS.startsWith("u"), equalTo(true))
    }

    // ==========================================
    // COMPUTED VALUE CONSISTENCY
    // ==========================================

    @Test
    @SmallTest
    fun v4InitOverhead_matchesComment() {
        // ZMSG|v4c|1/N|<convID8>|INIT|<address~141>| = ~170 bytes overhead
        // So chunk size should be MAX_MEMO_SIZE - ~170 = ~342
        // The constant is 330, which is conservative (good)
        val overhead = ZMSGConstants.MAX_MEMO_SIZE - ZMSGConstants.ChunkSizes.V4_INIT
        assertThat("V4 INIT overhead should be around 170-180", overhead in 170..190, equalTo(true))
    }

    @Test
    @SmallTest
    fun v4ReplyOverhead_matchesComment() {
        // ZMSG|v4c|1/N|<convID8>|<hash16>| = ~41 bytes overhead
        // So chunk size should be MAX_MEMO_SIZE - ~50 = ~462
        val overhead = ZMSGConstants.MAX_MEMO_SIZE - ZMSGConstants.ChunkSizes.V4_REPLY_FIRST
        assertThat("V4 Reply overhead should be around 41-55", overhead in 40..60, equalTo(true))
    }
}
