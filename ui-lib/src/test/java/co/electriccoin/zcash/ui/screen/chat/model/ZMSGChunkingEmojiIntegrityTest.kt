package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED-style regression tests for emoji / non-BMP integrity of the ZMSG chunking pipeline
 * (ZMSGProtocol.substringByBytes / packChunks — exercised through their public callers).
 *
 * Bugs pinned (both fixed in ZMSGProtocol, previously untested at the JVM level):
 *
 * 1. "Emoji/non-BMP corrupted in chunked messages": the old chunker split by CHAR index, so a
 *    surrogate pair (any emoji / non-BMP code point) straddling a chunk boundary was cut in half.
 *    A lone surrogate UTF-8-encodes to '?' — permanent on-chain corruption ("😀" → "??") — and
 *    counting a lone surrogate as 1 byte could also overflow the 512-byte memo limit.
 *    Fix: substringByBytes advances by whole code points and measures real UTF-8 size.
 *
 * 2. "Chunk-count desync silently truncated emoji": the old count was ARITHMETIC
 *    (1 + ceil((byteLen - firstChunkSize) / CONTINUATION)) while the builder packed via the
 *    code-point-aware splitter. Multi-byte code points that don't fit a budget exactly waste
 *    1..3 bytes per chunk, so real packing can need MORE chunks than the arithmetic predicted —
 *    and the old builder loop, bounded by the arithmetic count, silently dropped the tail.
 *    Fix: calculateChunkCount / calculateV4ChunkCount == packChunks(...).size by construction.
 *
 * Pure JVM: only java.security.MessageDigest crypto; android.util.Log is inert via
 * unitTests.isReturnDefaultValues (same as ZMSGSpecialMessagesTest).
 */
class ZMSGChunkingEmojiIntegrityTest {

    // ── In-memory AddressCache (mirrors ZMSGSpecialMessagesTest / androidTest TestAddressCache) ──
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

    private val sender = "u1testsenderaddressforchunkingemojiintegrity000000000000000000000000"
    private val senderHash = ZMSGProtocol.generateAddressHash(sender)
    private val convId = "TESTCONV" // 8 chars, A-Z0-9 as required by validateConvId

    private fun seededCache() = TestAddressCache().apply { cacheAddress(senderHash, sender) }

    /** True iff [s] survives a UTF-8 encode/decode round trip, i.e. contains no lone surrogates. */
    private fun utf8Clean(s: String): Boolean = s == String(s.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

    /**
     * Emoji-heavy payload: BMP text + 4-byte non-BMP emoji + ZWJ family sequence + flag
     * (regional-indicator pair) + non-BMP musical/CJK-ext code points. ~2.4KB → several chunks.
     * No '|' anywhere (that is a protocol separator, not part of this regression).
     */
    private val emojiHeavyMessage: String = buildString {
        repeat(40) { i ->
            append("part").append(i)
            append(" 🎉👨‍👩‍👧🚀 emoji integrity 😀🇺🇳 𝄞𠜎 ")
        }
    }

    // ==========================================
    // BUG 1 — surrogate-pair split / corruption
    // ==========================================

    @Test
    fun v4InitChunks_emojiHeavy_roundTripByteExact_noLoneSurrogates() {
        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, sender, emojiHeavyMessage)
        assertTrue("payload must actually chunk", chunks.size > 1)

        // MUTATION (revert to char-index substring in substringByBytes): a surrogate pair gets cut
        // at a chunk boundary → the memo contains a lone surrogate → UTF-8 encode turns it into '?'
        // and the decode below no longer equals the memo → this assertion goes red.
        chunks.forEachIndexed { i, memo ->
            assertTrue("chunk $i contains a lone surrogate (split emoji)", utf8Clean(memo))
        }

        val parsed = ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())
        // MUTATION: with a split surrogate the rejoined string differs from the original
        // (corrupted code point at each bad boundary) → byte-exact equality goes red.
        assertEquals(emojiHeavyMessage, parsed?.message)
        assertArrayEquals(
            emojiHeavyMessage.toByteArray(Charsets.UTF_8),
            parsed?.message?.toByteArray(Charsets.UTF_8)
        )
        assertEquals(sender, parsed?.senderAddress) // INIT carries the full address
        assertEquals(convId, parsed?.conversationId)
    }

    @Test
    fun v4ReplyChunks_emojiHeavy_roundTripByteExact_noLoneSurrogates() {
        val chunks = ZMSGProtocol.createChunkedV4ReplyMessages(convId, sender, emojiHeavyMessage)
        assertTrue("payload must actually chunk", chunks.size > 1)

        chunks.forEachIndexed { i, memo ->
            // MUTATION (char-index split): lone surrogate in some chunk → red.
            assertTrue("chunk $i contains a lone surrogate (split emoji)", utf8Clean(memo))
        }

        val parsed = ZMSGProtocol.reassembleChunks(chunks, seededCache())
        assertEquals(emojiHeavyMessage, parsed?.message)
        assertEquals(senderHash, parsed?.senderHash) // reply first chunk carries hash16
        assertEquals(sender, parsed?.senderAddress)  // resolved via seeded cache
    }

    @Test
    fun v3InitChunks_emojiHeavy_roundTripByteExact() {
        val chunks = ZMSGProtocol.createChunkedInitMessages(sender, emojiHeavyMessage)
        assertTrue("payload must actually chunk", chunks.size > 1)
        chunks.forEachIndexed { i, memo ->
            // MUTATION (char-index split): lone surrogate in some chunk → red.
            assertTrue("chunk $i contains a lone surrogate (split emoji)", utf8Clean(memo))
        }
        val parsed = ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())
        // MUTATION: split surrogate → '?' corruption at chunk boundaries → red.
        assertEquals(emojiHeavyMessage, parsed?.message)
        assertEquals(sender, parsed?.senderAddress)
    }

    @Test
    fun emojiStraddlingFirstChunkBoundary_isPushedWholeToNextChunk() {
        // (budget-1) ASCII bytes, then a 4-byte emoji: the emoji does NOT fit the first chunk
        // ((budget-1) + 4 > budget) and must move WHOLE into chunk 2. The v4 INIT first-chunk budget
        // is DYNAMIC (address-aware) — it embeds `sender` — so derive it rather than hardcoding 330.
        val budget = ZMSGProtocol.v4InitFirstChunkBudget(sender)
        val message = "a".repeat(budget - 1) + "😀" + "b".repeat(10)

        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, sender, message)
        // MUTATION (char-index substring, budget treated as chars): first chunk takes `budget` CHARS =
        // (budget-1) 'a' + the HIGH SURROGATE of 😀 → surrogate split. utf8Clean(first chunk) → false → red.
        assertEquals(2, chunks.size)
        chunks.forEach { memo -> assertTrue("split surrogate at boundary", utf8Clean(memo)) }

        // MUTATION: the split surrogate decodes as '?' on each side → rejoined text becomes
        // "...aaa??bbb..." instead of "...aaa😀bbb..." → red.
        val parsed = ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())
        assertEquals(message, parsed?.message)

        // Count must agree with the packer even at the straddle boundary (same dynamic budget).
        assertEquals(chunks.size, ZMSGProtocol.calculateV4ChunkCount(message, true, sender))
    }

    @Test
    fun v4Chunks_emojiHeavy_everyMemoStaysWithinMemoByteLimit() {
        // 480 four-byte emoji = 1920 payload bytes.
        val message = "😀🎉🚀💜".repeat(120)
        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, sender, message)
        assertTrue(chunks.size > 1)
        chunks.forEachIndexed { i, memo ->
            // MUTATION (budget counted in chars, or lone surrogate counted as 1 byte): a chunk
            // packs up to 330/485 CHARS of 4-byte emoji → memo blows far past 512 UTF-8 bytes → red.
            val bytes = memo.toByteArray(Charsets.UTF_8).size
            assertTrue(
                "chunk $i is $bytes bytes, exceeds MAX_MEMO_SIZE=${ZMSGConstants.MAX_MEMO_SIZE}",
                bytes <= ZMSGConstants.MAX_MEMO_SIZE
            )
        }
        // And the capped chunks still carry the whole message.
        assertEquals(message, ZMSGProtocol.reassembleChunks(chunks, TestAddressCache())?.message)
    }

    // ==========================================
    // BUG 2 — chunk-count desync / silent truncation
    // ==========================================

    /**
     * The ZWJ family emoji 👨‍👩‍👧 is 18 UTF-8 bytes (8 UTF-16 chars): 4+3+4+3+4 (emoji, ZWJ, emoji,
     * ZWJ, emoji). The packer may split BETWEEN code points but never inside one, so whenever the
     * next code point (3 or 4 bytes) doesn't fit the remaining budget, up to 3 bytes per chunk are
     * wasted. At 1527 repeats (27,486 bytes) the accumulated waste exceeds one whole continuation
     * chunk for ALL four first-chunk budgets (v4 330/462, v3 340/470): real packing = 58 chunks,
     * arithmetic prediction 1 + ceil((byteLen - first)/485) = 57 — the exact condition under which
     * the old arithmetic count undercounted and the old builder silently dropped the message tail.
     * (Numbers verified by simulating the code-point packer offline.)
     */
    private val desyncPayload = "👨‍👩‍👧".repeat(1527)

    @Test
    fun v4ChunkCount_matchesBuilder_whereArithmeticCountUndercounts() {
        // INIT: count is computed by simulating the real packer over the SAME dynamic first-chunk
        // budget the builder uses (v4InitFirstChunkBudget(sender)), so it equals the builder's chunk
        // count by construction — exactly what an arithmetic 1 + ceil((byteLen - first)/485) count
        // could not guarantee once multi-byte code points waste 1..3 bytes per chunk.
        val initChunks = ZMSGProtocol.createChunkedV4InitMessages(convId, sender, desyncPayload)
        assertEquals(initChunks.size, ZMSGProtocol.calculateV4ChunkCount(desyncPayload, true, sender))

        // REPLY: real packing = 58 chunks; arithmetic mutation (first budget 462) predicts 57 → red.
        val replyChunks = ZMSGProtocol.createChunkedV4ReplyMessages(convId, sender, desyncPayload)
        assertEquals(replyChunks.size, ZMSGProtocol.calculateV4ChunkCount(desyncPayload, false))

        // MUTATION (old builder loop bounded by the arithmetic count): the last ~1 chunk of family
        // emoji is silently dropped → rejoined message is a truncated prefix → equality goes red.
        assertEquals(desyncPayload, ZMSGProtocol.reassembleChunks(initChunks, TestAddressCache())?.message)
        assertEquals(desyncPayload, ZMSGProtocol.reassembleChunks(replyChunks, seededCache())?.message)
    }

    @Test
    fun v3ChunkCount_matchesBuilder_whereArithmeticCountUndercounts() {
        // v3 INIT: real packing = 58 chunks; arithmetic mutation (1 + ceil((27486-340)/485)) = 57 → red.
        val initChunks = ZMSGProtocol.createChunkedInitMessages(sender, desyncPayload)
        assertEquals(initChunks.size, ZMSGProtocol.calculateChunkCount(desyncPayload, true))

        // v3 REPLY: real packing = 58 chunks; arithmetic mutation (first budget 470) = 57 → red.
        val replyChunks = ZMSGProtocol.createChunkedReplyMessages(sender, desyncPayload)
        assertEquals(replyChunks.size, ZMSGProtocol.calculateChunkCount(desyncPayload, false))

        // No silent tail truncation (see v4 test for the mutation this pins).
        assertEquals(desyncPayload, ZMSGProtocol.reassembleChunks(initChunks, TestAddressCache())?.message)
    }

    @Test
    fun v4ChunkCount_asciiByteBoundaries_exact() {
        // INIT first-chunk budget is DYNAMIC (address-aware, embeds `sender`); REPLY first chunk carries
        // only a hash so its budget is the fixed V4_REPLY_FIRST.
        val initBudget = ZMSGProtocol.v4InitFirstChunkBudget(sender)
        val replyBudget = ZMSGConstants.ChunkSizes.V4_REPLY_FIRST // 462

        // Exactly at the first-chunk budget → 1 chunk; one byte over → 2 chunks.
        // MUTATION (off-by-one budget, or byte/char confusion reintroduced): boundary flips → red.
        assertEquals(1, ZMSGProtocol.calculateV4ChunkCount("a".repeat(initBudget), true, sender))
        assertEquals(2, ZMSGProtocol.calculateV4ChunkCount("a".repeat(initBudget + 1), true, sender))
        assertEquals(1, ZMSGProtocol.calculateV4ChunkCount("a".repeat(replyBudget), false))
        assertEquals(2, ZMSGProtocol.calculateV4ChunkCount("a".repeat(replyBudget + 1), false))

        // A 2-byte-per-char BMP string (é = U+00E9): initBudget CHARS but 2*initBudget BYTES.
        // MUTATION (budget counted in chars): 1 chunk predicted; byte-aware packing needs 2 → red.
        val twoBytePerChar = "é".repeat(initBudget)
        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, sender, twoBytePerChar)
        assertEquals(2, chunks.size)
        assertEquals(chunks.size, ZMSGProtocol.calculateV4ChunkCount(twoBytePerChar, true, sender))
    }
}
