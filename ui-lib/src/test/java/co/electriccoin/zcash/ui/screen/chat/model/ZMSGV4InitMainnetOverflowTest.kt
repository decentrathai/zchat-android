package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED regression for the MAINNET v4-INIT first-chunk memo OVERFLOW (HIGH, mainnet-only).
 *
 * Bug: [ZMSGProtocol.createChunkedV4InitMessages] and the single-chunk [ZMSGProtocol.createV4InitMessage]
 * sized the first INIT chunk's message budget with the FIXED constant CHUNK_SIZE_V4_INIT (330), whose
 * comment assumes a ~141-byte address. The INIT first chunk embeds the sender's FULL unified address:
 *
 *   ZMSG|v4c|1/N|<convId8>|INIT|<senderAddress>|<messagePart>
 *
 * Real MAINNET unified addresses are 178–213 bytes, so the first chunk became
 *   ~31 fixed overhead + address(178–213) + messagePart(up to 330)  >  512 (MAX_MEMO_SIZE)
 * and the send was later aborted with MemoTooLongException by the central pre-submit guard in
 * CreateChunkedMessageProposalUseCase. The existing unit fixtures missed it because they use short
 * (~68/145-byte) TESTNET-shaped addresses that never breach 512.
 *
 * Fix: size the INIT first-chunk budget DYNAMICALLY from the real address
 *   MAX_MEMO_SIZE - exactFixedOverhead - senderAddress.toByteArray(UTF-8).size
 * so every emitted memo fits 512 for ANY address length, testnet behaviour included.
 *
 * These assertions FAIL on the pre-fix code (first chunk ~536 / single memo ~526) and PASS after it.
 *
 * Pure JVM: only java.security.MessageDigest crypto; android.util.Log is inert via
 * unitTests.isReturnDefaultValues (same harness as ZMSGChunkingEmojiIntegrityTest).
 */
class ZMSGV4InitMainnetOverflowTest {

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

    private val convId = "TESTCV01" // 8 chars, A-Z0-9 as required by validateConvId

    /** A 178-byte MAINNET-shaped unified address ("u1" + 176 alphanumeric). */
    private val mainnetUA = "u1" + "a".repeat(176)

    /** A ~145-byte TESTNET-shaped address — the size the old fixtures used (must stay green). */
    private val testnetUA = "utest1" + "b".repeat(139)

    private fun byteSize(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun mainnetInit_multiChunk_everyMemoWithinMemoByteLimit() {
        assertEquals("fixture must be 178 mainnet-sized bytes", 178, byteSize(mainnetUA))
        // Long enough to span several first+continuation chunks.
        val message = "a".repeat(700)

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, mainnetUA, message)
        assertTrue("payload must actually chunk", chunks.size > 1)

        // PRE-FIX: first chunk = ~31 overhead + 178 addr + 330 msg = ~536 bytes → this goes RED.
        chunks.forEachIndexed { i, memo ->
            val bytes = byteSize(memo)
            assertTrue(
                "chunk ${i + 1}/${chunks.size} is $bytes bytes, over MAX_MEMO_SIZE=${ZMSGConstants.MAX_MEMO_SIZE}",
                bytes <= ZMSGConstants.MAX_MEMO_SIZE
            )
        }

        // The capped chunks still carry the whole message + the full sender address.
        val parsed = ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())
        assertEquals(message, parsed?.message)
        assertEquals(mainnetUA, parsed?.senderAddress)
        assertEquals(convId, parsed?.conversationId)
    }

    @Test
    fun mainnetInit_singleChunkBoundary_splitsInsteadOfOverflowing() {
        // 325 bytes fits the OLD fixed 330 budget, so the pre-fix code emitted it as ONE non-chunked
        // memo = 23 overhead + 178 addr + 325 msg = 526 bytes > 512 → RED. The fix shrinks the INIT
        // budget for a 178-byte address (~303), so this now splits into >1 chunk that each fit 512.
        val message = "a".repeat(325)

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, mainnetUA, message)
        chunks.forEachIndexed { i, memo ->
            val bytes = byteSize(memo)
            assertTrue(
                "chunk ${i + 1}/${chunks.size} is $bytes bytes, over MAX_MEMO_SIZE=${ZMSGConstants.MAX_MEMO_SIZE}",
                bytes <= ZMSGConstants.MAX_MEMO_SIZE
            )
        }

        val parsed = ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())
        assertEquals(message, parsed?.message)
        assertEquals(mainnetUA, parsed?.senderAddress)
    }

    @Test
    fun mainnetInit_singleMemoThatFits_isWithinLimit() {
        // A message short enough to ride a single on-chain INIT memo for a 178-byte address must
        // itself stay within 512 (23 overhead + 178 addr + msg). 300 bytes → 501, one memo.
        val message = "a".repeat(300)
        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, mainnetUA, message)
        assertEquals("should ride a single memo", 1, chunks.size)
        assertTrue(
            "single INIT memo is ${byteSize(chunks[0])} bytes, over 512",
            byteSize(chunks[0]) <= ZMSGConstants.MAX_MEMO_SIZE
        )
        assertEquals(message, ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())?.message)
    }

    @Test
    fun testnetInit_stillWithinLimit_noRegression() {
        // The short TESTNET-shaped address never breached 512 before and must not after (identical
        // send viability): every memo fits and the message round-trips byte-exactly.
        assertTrue("testnet fixture is short", byteSize(testnetUA) in 120..160)
        val message = "a".repeat(700)

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, testnetUA, message)
        assertTrue(chunks.size > 1)
        chunks.forEachIndexed { i, memo ->
            val bytes = byteSize(memo)
            assertTrue(
                "testnet chunk ${i + 1}/${chunks.size} is $bytes bytes, over 512",
                bytes <= ZMSGConstants.MAX_MEMO_SIZE
            )
        }
        val parsed = ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())
        assertEquals(message, parsed?.message)
        assertEquals(testnetUA, parsed?.senderAddress)
    }
}
