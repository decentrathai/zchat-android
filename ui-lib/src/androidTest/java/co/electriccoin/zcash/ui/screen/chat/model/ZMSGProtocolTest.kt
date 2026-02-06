package co.electriccoin.zcash.ui.screen.chat.model

import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.CoreMatchers.startsWith
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

/**
 * Comprehensive tests for ZMSGProtocol - the core message routing protocol.
 *
 * These tests ensure perfect message routing - messages must NEVER go to wrong
 * conversations or create unnecessary new conversations after wallet restore.
 */
class ZMSGProtocolTest {

    // Mock AddressCache for testing
    private class TestAddressCache : AddressCache {
        private val cache = mutableMapOf<String, String>()
        private val partners = mutableSetOf<String>()

        override fun cacheAddress(hash: String, address: String) {
            cache[hash] = address
        }

        override fun getAddress(hash: String): String? = cache[hash]

        override fun hasAddress(hash: String): Boolean = cache.containsKey(hash)

        override fun getAllCachedAddresses(): Map<String, String> = cache.toMap()

        override fun addConversationPartner(address: String) {
            partners.add(address)
        }

        override fun getConversationPartners(): Set<String> = partners.toSet()

        override fun isConversationPartner(address: String): Boolean = partners.contains(address)

        override fun findConversationPartnerByHash(hash: String): String? {
            return partners.find {
                ZMSGProtocol.generateAddressHash(it) == hash ||
                ZMSGProtocol.generateLegacyAddressHash(it) == hash
            }
        }
    }

    // ==========================================
    // CONVERSATION ID GENERATION
    // ==========================================

    @Test
    @SmallTest
    fun generateConversationId_correctLength() {
        val convId = ZMSGProtocol.generateConversationId()
        assertThat(convId.length, equalTo(ZMSGConstants.CONV_ID_LENGTH))
    }

    @Test
    @SmallTest
    fun generateConversationId_validCharacters() {
        val convId = ZMSGProtocol.generateConversationId()
        val validChars = ZMSGConstants.CONV_ID_CHARS
        assertThat(convId.all { it in validChars }, equalTo(true))
    }

    @Test
    @SmallTest
    fun generateConversationId_uniqueValues() {
        val ids = (1..100).map { ZMSGProtocol.generateConversationId() }
        val uniqueIds = ids.toSet()
        // With 36^8 possibilities, collision in 100 IDs is practically impossible
        assertThat(uniqueIds.size, equalTo(100))
    }

    // ==========================================
    // ADDRESS HASH GENERATION
    // ==========================================

    @Test
    @SmallTest
    fun generateAddressHash_correctLength() {
        val address = "u1testaddress123"
        val hash = ZMSGProtocol.generateAddressHash(address)
        assertThat(hash.length, equalTo(ZMSGConstants.HASH_LENGTH_NEW))
    }

    @Test
    @SmallTest
    fun generateAddressHash_hexCharacters() {
        val address = "u1testaddress123"
        val hash = ZMSGProtocol.generateAddressHash(address)
        assertThat(hash.all { it in '0'..'9' || it in 'a'..'f' }, equalTo(true))
    }

    @Test
    @SmallTest
    fun generateAddressHash_deterministic() {
        val address = "u1testaddress123"
        val hash1 = ZMSGProtocol.generateAddressHash(address)
        val hash2 = ZMSGProtocol.generateAddressHash(address)
        assertThat(hash1, equalTo(hash2))
    }

    @Test
    @SmallTest
    fun generateAddressHash_differentAddresses_differentHashes() {
        val hash1 = ZMSGProtocol.generateAddressHash("u1address1")
        val hash2 = ZMSGProtocol.generateAddressHash("u1address2")
        assertThat(hash1 == hash2, equalTo(false))
    }

    @Test
    @SmallTest
    fun generateLegacyAddressHash_correctLength() {
        val address = "u1testaddress123"
        val hash = ZMSGProtocol.generateLegacyAddressHash(address)
        assertThat(hash.length, equalTo(ZMSGConstants.HASH_LENGTH))
    }

    @Test
    @SmallTest
    fun isLegacyHash_recognizesLegacyFormat() {
        val legacyHash = "a1b2c3d4e5f6"  // 12 hex chars
        assertThat(ZMSGProtocol.isLegacyHash(legacyHash), equalTo(true))
    }

    @Test
    @SmallTest
    fun isLegacyHash_rejectsNewFormat() {
        val newHash = "a1b2c3d4e5f6g7h8"  // 16 chars but has invalid chars
        assertThat(ZMSGProtocol.isLegacyHash(newHash), equalTo(false))
    }

    // ==========================================
    // V4 MESSAGE CREATION
    // ==========================================

    @Test
    @SmallTest
    fun createV4InitMessage_correctFormat() {
        val convId = "ABCD1234"
        val address = "u1sender"
        val message = "Hello"

        val memo = ZMSGProtocol.createV4InitMessage(convId, address, message)

        assertThat(memo, startsWith(ZMSGConstants.Prefixes.V4))
        assertThat(memo.contains(convId), equalTo(true))
        assertThat(memo.contains("INIT|"), equalTo(true))
        assertThat(memo.contains(address), equalTo(true))
        assertThat(memo.endsWith(message), equalTo(true))
    }

    @Test
    @SmallTest
    fun createV4ReplyMessage_correctFormat() {
        val convId = "ABCD1234"
        val address = "u1sender"
        val message = "Reply"

        val memo = ZMSGProtocol.createV4ReplyMessage(convId, address, message)
        val hash = ZMSGProtocol.generateAddressHash(address)

        assertThat(memo, startsWith(ZMSGConstants.Prefixes.V4))
        assertThat(memo.contains(convId), equalTo(true))
        assertThat(memo.contains(hash), equalTo(true))
        assertThat(memo.endsWith(message), equalTo(true))
    }

    @Test(expected = IllegalArgumentException::class)
    @SmallTest
    fun createV4InitMessage_invalidConvIdLength_throws() {
        ZMSGProtocol.createV4InitMessage("ABC", "u1sender", "Hello")
    }

    @Test(expected = IllegalArgumentException::class)
    @SmallTest
    fun createV4InitMessage_invalidConvIdChars_throws() {
        ZMSGProtocol.createV4InitMessage("abcd1234", "u1sender", "Hello")  // lowercase not allowed
    }

    @Test(expected = IllegalArgumentException::class)
    @SmallTest
    fun createV4ReplyMessage_invalidConvId_throws() {
        ZMSGProtocol.createV4ReplyMessage("INVALID!", "u1sender", "Hello")  // special char not allowed
    }

    // ==========================================
    // V4 MESSAGE PARSING
    // ==========================================

    @Test
    @SmallTest
    fun parseMemo_v4Init_extractsAllFields() {
        val convId = "ABCD1234"
        val address = "u1sender12345"
        val message = "Test message"
        val memo = ZMSGProtocol.createV4InitMessage(convId, address, message)

        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.senderAddress, equalTo(address))
        assertThat(parsed.conversationId, equalTo(convId))
        assertThat(parsed.message, equalTo(message))
        assertThat(parsed.isUnknownSender, equalTo(false))
    }

    @Test
    @SmallTest
    fun parseMemo_v4Reply_extractsConvIdAndHash() {
        val convId = "WXYZ5678"
        val address = "u1replysender"
        val message = "Reply message"
        val memo = ZMSGProtocol.createV4ReplyMessage(convId, address, message)

        val cache = TestAddressCache()
        cache.cacheAddress(ZMSGProtocol.generateAddressHash(address), address)

        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.conversationId, equalTo(convId))
        assertThat(parsed.senderHash, equalTo(ZMSGProtocol.generateAddressHash(address)))
        assertThat(parsed.message, equalTo(message))
    }

    @Test
    @SmallTest
    fun parseMemo_v4InvalidConvId_returnsMalformed() {
        // Manually craft a malformed v4 message with lowercase convId
        val malformed = "ZMSG|v4|abcd1234|Test"  // lowercase not valid

        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(malformed, cache)

        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.MALFORMED_MESSAGE))
    }

    @Test
    @SmallTest
    fun parseMemo_v4ShortConvId_returnsMalformed() {
        val malformed = "ZMSG|v4|ABC|Test"  // Too short

        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(malformed, cache)

        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.MALFORMED_MESSAGE))
    }

    // ==========================================
    // V3 MESSAGE COMPATIBILITY
    // ==========================================

    @Test
    @SmallTest
    fun parseMemo_v3Init_extractsAddress() {
        val address = "u1legacysender"
        val message = "Legacy message"
        val memo = ZMSGProtocol.createInitMessage(address, message)

        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.senderAddress, equalTo(address))
        assertThat(parsed.message, equalTo(message))
        assertThat(parsed.isUnknownSender, equalTo(false))
    }

    @Test
    @SmallTest
    fun parseMemo_v3Reply_withCachedAddress() {
        val address = "u1knownsender"
        val message = "Reply"

        val cache = TestAddressCache()
        val hash = ZMSGProtocol.generateAddressHash(address)
        cache.cacheAddress(hash, address)

        val memo = ZMSGProtocol.createReplyMessage(address, message)
        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.senderAddress, equalTo(address))
        assertThat(parsed.senderHash, equalTo(hash))
        assertThat(parsed.isUnknownSender, equalTo(false))
    }

    @Test
    @SmallTest
    fun parseMemo_v3Reply_unknownHash() {
        val address = "u1unknownsender"
        val message = "Reply"
        val memo = ZMSGProtocol.createReplyMessage(address, message)

        val cache = TestAddressCache()  // Empty cache
        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.senderAddress, nullValue())
        assertThat(parsed.senderHash, notNullValue())
        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.HASH_NOT_IN_CACHE))
    }

    // ==========================================
    // NON-ZMSG FORMAT
    // ==========================================

    @Test
    @SmallTest
    fun parseMemo_plainText_returnsNotZmsgFormat() {
        val plainMemo = "Just a regular transaction memo"

        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(plainMemo, cache)

        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.NOT_ZMSG_FORMAT))
        assertThat(parsed.message, equalTo(plainMemo))
    }

    @Test
    @SmallTest
    fun parseMemo_emptyMemo() {
        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo("", cache)

        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.NOT_ZMSG_FORMAT))
    }

    // ==========================================
    // CHUNKED MESSAGES
    // ==========================================

    @Test
    @SmallTest
    fun createChunkedV4InitMessages_shortMessage_singleChunk() {
        val convId = "CHUNK123"
        val address = "u1sender"
        val message = "Short"

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, address, message)

        assertThat(chunks.size, equalTo(1))
        assertThat(ZMSGProtocol.isV4ChunkedMemo(chunks[0]), equalTo(false))
    }

    @Test
    @SmallTest
    fun createChunkedV4InitMessages_longMessage_multipleChunks() {
        val convId = "CHUNK456"
        val address = "u1sender"
        val message = "A".repeat(1000)  // Long message

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, address, message)

        assertThat(chunks.size > 1, equalTo(true))
        assertThat(ZMSGProtocol.isV4ChunkedMemo(chunks[0]), equalTo(true))
    }

    @Test
    @SmallTest
    fun reassembleChunks_validChunks_reconstructsMessage() {
        val convId = "REASM789"
        val address = "u1sender"
        val originalMessage = "A".repeat(1000)

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, address, originalMessage)

        val cache = TestAddressCache()
        val reassembled = ZMSGProtocol.reassembleChunks(chunks, cache)

        assertThat(reassembled, notNullValue())
        assertThat(reassembled?.message, equalTo(originalMessage))
        assertThat(reassembled?.conversationId, equalTo(convId))
        assertThat(reassembled?.senderAddress, equalTo(address))
    }

    @Test
    @SmallTest
    fun reassembleChunks_missingChunk_returnsNull() {
        val convId = "MISS0000"
        val address = "u1sender"
        val originalMessage = "A".repeat(1000)

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, address, originalMessage)
        val incompleteChunks = chunks.drop(1)  // Remove first chunk

        val cache = TestAddressCache()
        val reassembled = ZMSGProtocol.reassembleChunks(incompleteChunks, cache)

        assertThat(reassembled, nullValue())
    }

    @Test
    @SmallTest
    fun isChunkedMemo_detectsV4Chunked() {
        val chunkedMemo = "ZMSG|v4c|1/2|ABCD1234|Test"
        assertThat(ZMSGProtocol.isChunkedMemo(chunkedMemo), equalTo(true))
    }

    @Test
    @SmallTest
    fun isChunkedMemo_detectsV3Chunked() {
        val chunkedMemo = "ZMSG|v3c|1/2|INIT|u1sender|Test"
        assertThat(ZMSGProtocol.isChunkedMemo(chunkedMemo), equalTo(true))
    }

    @Test
    @SmallTest
    fun isChunkedMemo_rejectsNonChunked() {
        val normalMemo = "ZMSG|v4|ABCD1234|INIT|u1sender|Test"
        assertThat(ZMSGProtocol.isChunkedMemo(normalMemo), equalTo(false))
    }

    // ==========================================
    // CHUNK COUNT VALIDATION (DoS Prevention)
    // ==========================================

    @Test
    @SmallTest
    fun parseChunkInfo_rejectsExcessiveChunkCount() {
        // Manually craft a malicious chunked memo claiming 10001 chunks
        val malicious = "ZMSG|v4c|1/10001|ABCD1234|INIT|u1sender|Test"

        val cache = TestAddressCache()
        // reassembleChunks should handle this gracefully
        val result = ZMSGProtocol.reassembleChunks(listOf(malicious), cache)

        // Should return null due to invalid chunk count
        assertThat(result, nullValue())
    }

    @Test
    @SmallTest
    fun parseChunkInfo_acceptsValidChunkCount() {
        val valid = "ZMSG|v4c|1/100|ABCD1234|INIT|u1sender|Test"

        // This should parse without issues
        assertThat(ZMSGProtocol.isV4ChunkedMemo(valid), equalTo(true))
    }

    // ==========================================
    // KEX (Key Exchange) MESSAGES
    // ==========================================

    @Test
    @SmallTest
    fun createV4KEXMessage_correctFormat() {
        val convId = "KEXTEST1"
        val address = "u1sender"
        val kexPayload = "publickey:signature"

        val memo = ZMSGProtocol.createV4KEXMessage(convId, address, kexPayload)

        assertThat(memo, startsWith(ZMSGConstants.Prefixes.V4))
        assertThat(memo.contains("|KEX|"), equalTo(true))
        assertThat(memo.endsWith(kexPayload), equalTo(true))
    }

    @Test
    @SmallTest
    fun isKEXMessage_detectsKEX() {
        val kexMemo = "ZMSG|v4|ABCD1234|KEX|abcdef12|payload"
        assertThat(ZMSGProtocol.isKEXMessage(kexMemo), equalTo(true))
    }

    @Test
    @SmallTest
    fun isKEXMessage_rejectsNonKEX() {
        val normalMemo = "ZMSG|v4|ABCD1234|INIT|u1sender|Hello"
        assertThat(ZMSGProtocol.isKEXMessage(normalMemo), equalTo(false))
    }

    @Test
    @SmallTest
    fun parseKEXMessage_extractsFields() {
        val convId = "KEXPARSE"
        val address = "u1sender"
        val kexPayload = "test:payload"
        val memo = ZMSGProtocol.createV4KEXMessage(convId, address, kexPayload)

        val result = ZMSGProtocol.parseKEXMessage(memo)

        assertThat(result, notNullValue())
        assertThat(result?.first, equalTo(convId))
        assertThat(result?.second, equalTo(kexPayload))
    }

    // ==========================================
    // VERSION DETECTION
    // ==========================================

    @Test
    @SmallTest
    fun isV4Message_detectsV4() {
        val v4Memo = "ZMSG|v4|ABCD1234|Test"
        assertThat(ZMSGProtocol.isV4Message(v4Memo), equalTo(true))
    }

    @Test
    @SmallTest
    fun isV4Message_detectsV4Chunked() {
        val v4cMemo = "ZMSG|v4c|1/2|ABCD1234|Test"
        assertThat(ZMSGProtocol.isV4Message(v4cMemo), equalTo(true))
    }

    @Test
    @SmallTest
    fun isV4Message_rejectsV3() {
        val v3Memo = "ZMSG|v3|INIT|u1sender|Test"
        assertThat(ZMSGProtocol.isV4Message(v3Memo), equalTo(false))
    }

    // ==========================================
    // REF (Transaction Reference) MESSAGES
    // ==========================================

    @Test
    @SmallTest
    fun createRefMessage_correctFormat() {
        val address = "u1sender"
        val message = "Reply"
        val txId = "abc123txid"

        val memo = ZMSGProtocol.createRefMessage(address, message, txId)

        assertThat(memo, startsWith(ZMSGConstants.Prefixes.V3))
        assertThat(memo.contains("REF|"), equalTo(true))
        assertThat(memo.contains(txId), equalTo(true))
    }

    @Test
    @SmallTest
    fun isRefMessage_detectsRef() {
        val refMemo = "ZMSG|v3|REF|txid123|hash12ab|Message"
        assertThat(ZMSGProtocol.isRefMessage(refMemo), equalTo(true))
    }

    @Test
    @SmallTest
    fun parseMemo_refMessage_extractsTxId() {
        val address = "u1sender"
        val message = "Referenced reply"
        val txId = "reftxid456"
        val memo = ZMSGProtocol.createRefMessage(address, message, txId)

        val cache = TestAddressCache()
        val hash = ZMSGProtocol.generateAddressHash(address)
        cache.cacheAddress(hash, address)

        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.replyToTxId, equalTo(txId))
        assertThat(parsed.message, equalTo(message))
    }

    // ==========================================
    // SPECIAL MESSAGE TYPES
    // ==========================================

    @Test
    @SmallTest
    fun isReaction_detectsReaction() {
        val reaction = ZMSGProtocol.createReaction("txid", "👍", "u1sender")
        assertThat(ZMSGProtocol.isReaction(reaction), equalTo(true))
    }

    @Test
    @SmallTest
    fun isReadReceipt_detectsReceipt() {
        val receipt = ZMSGProtocol.createReadReceipt("txid", "u1sender")
        assertThat(ZMSGProtocol.isReadReceipt(receipt), equalTo(true))
    }

    @Test
    @SmallTest
    fun isStatus_detectsStatus() {
        val status = ZMSGProtocol.createStatusMessage("Online", "u1sender")
        assertThat(ZMSGProtocol.isStatus(status), equalTo(true))
    }

    @Test
    @SmallTest
    fun isTimeLock_detectsTimeLock() {
        val timeLock = ZMSGProtocol.createScheduledMessage("Secret", "u1sender", System.currentTimeMillis() / 1000 + 3600)
        assertThat(ZMSGProtocol.isTimeLock(timeLock), equalTo(true))
    }

    @Test
    @SmallTest
    fun isPaymentRequest_detectsRequest() {
        val request = ZMSGProtocol.createPaymentRequest(100000000L, "u1sender", "Dinner")
        assertThat(ZMSGProtocol.isPaymentRequest(request), equalTo(true))
    }

    // ==========================================
    // EDGE CASES & BOUNDARY CONDITIONS
    // ==========================================

    @Test
    @SmallTest
    fun parseMemo_messageWithPipes_handledCorrectly() {
        val convId = "PIPE1234"
        val address = "u1sender"
        val message = "Message|with|pipes|inside"

        val memo = ZMSGProtocol.createV4InitMessage(convId, address, message)
        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        // The message should be extracted correctly despite containing pipes
        assertThat(parsed.message, equalTo(message))
    }

    @Test
    @SmallTest
    fun parseMemo_unicodeMessage_preserved() {
        val convId = "UNIC0123"
        val address = "u1sender"
        val message = "Hello 你好 🎉 مرحبا"

        val memo = ZMSGProtocol.createV4InitMessage(convId, address, message)
        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.message, equalTo(message))
    }

    @Test
    @SmallTest
    fun parseMemo_emptyMessage_handled() {
        val convId = "EMPTY123"
        val address = "u1sender"
        val message = ""

        val memo = ZMSGProtocol.createV4InitMessage(convId, address, message)
        val cache = TestAddressCache()
        val parsed = ZMSGProtocol.parseMemo(memo, cache)

        assertThat(parsed.message, equalTo(""))
    }

    @Test
    @SmallTest
    fun calculateV4ChunkCount_exactBoundary() {
        // Test at exact boundary of first chunk size
        val firstChunkSize = ZMSGConstants.ChunkSizes.V4_INIT
        val message = "A".repeat(firstChunkSize)

        val count = ZMSGProtocol.calculateV4ChunkCount(message, isInitMessage = true)
        assertThat(count, equalTo(1))
    }

    @Test
    @SmallTest
    fun calculateV4ChunkCount_oneBeyondBoundary() {
        val firstChunkSize = ZMSGConstants.ChunkSizes.V4_INIT
        val message = "A".repeat(firstChunkSize + 1)

        val count = ZMSGProtocol.calculateV4ChunkCount(message, isInitMessage = true)
        assertThat(count, equalTo(2))
    }

    // ==========================================
    // ADDRESS CACHE INTEGRATION
    // ==========================================

    @Test
    @SmallTest
    fun parseMemo_initMessage_cachesAddress() {
        val address = "u1newcontact"
        val memo = ZMSGProtocol.createInitMessage(address, "Hello")

        val cache = TestAddressCache()
        ZMSGProtocol.parseMemo(memo, cache)

        val hash = ZMSGProtocol.generateAddressHash(address)
        assertThat(cache.getAddress(hash), equalTo(address))
    }

    @Test
    @SmallTest
    fun parseMemo_v4InitMessage_cachesAddress() {
        val convId = "CACHE123"
        val address = "u1newv4contact"
        val memo = ZMSGProtocol.createV4InitMessage(convId, address, "Hello")

        val cache = TestAddressCache()
        ZMSGProtocol.parseMemo(memo, cache)

        val hash = ZMSGProtocol.generateAddressHash(address)
        assertThat(cache.getAddress(hash), equalTo(address))
    }
}
