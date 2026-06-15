package co.electriccoin.zcash.ui.screen.chat.model

import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

/**
 * Regression tests for BUG 7: genuine ZCHAT messages were shown as "Unknown sender —
 * This message was not sent using ZCHAT, so we cannot recognize the sender."
 *
 * Two root causes were fixed:
 *   (a) a recognized ZCHAT prefix that just can't resolve the sender hash (cache MISS after
 *       restart, or a legacy version) was mislabeled NOT_ZMSG_FORMAT, and
 *   (b) routing did not clear the parser's reason after resolving the message to a known peer
 *       via the authenticated convId mapping.
 *
 * These tests run as androidTest because the protocol relies on real Base64/SHA-256 that the
 * JVM src/test source set stubs out.
 *
 * IMPORTANT SECURITY INVARIANT (also asserted below): a memo with NO recognized ZCHAT prefix,
 * or a convId that does NOT map to an established peer, MUST still be flagged unknown.
 */
class UnknownSenderRecognitionTest {

    // Mock AddressCache for testing (same shape as the other chat protocol tests).
    private class TestAddressCache : AddressCache {
        private val cache = mutableMapOf<String, String>()
        private val partners = mutableSetOf<String>()

        override fun cacheAddress(hash: String, address: String) {
            cache[hash] = address
        }

        override fun cacheAddressValidated(hash: String, address: String) {
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

    // A real-ish unified address: starts with u1 and is long enough to pass the chat view's
    // address-validity heuristic (> 100 chars). 110-char padded value.
    private fun unifiedAddress(seed: String): String =
        "u1" + seed.padEnd(108, '0')

    // ----------------------------------------------------------------------------------------
    // Mirror of the routing/banner decision so we can exercise it without a full ViewModel.
    // ----------------------------------------------------------------------------------------

    /**
     * Mirrors ChatViewModel.isResolvedToKnownPeer: a resolved peer is "known" only if it is a real
     * Zcash address. A hash, raw convId, or "unknown" sentinel is NOT a real address.
     */
    private fun isResolvedToKnownPeer(resolvedPeerAddress: String): Boolean =
        (resolvedPeerAddress.startsWith("u1") && resolvedPeerAddress.length > 100) ||
            (resolvedPeerAddress.startsWith("zs") && resolvedPeerAddress.length > 70)

    /**
     * Simplified mirror of the ChatViewModel 3-tier routing for the cases under test. Resolves a
     * parsed message to a peer key using ONLY the authenticated convId->peer mapping plus the
     * sender address/hash carried by the message. Returns the resolved key (a real address when
     * recognized, otherwise a hash/convId/"unknown" sentinel).
     */
    private fun resolvePeer(
        parsed: ParsedMessage,
        convIdToPeer: Map<String, String>,
        addressCache: AddressCache,
    ): String {
        val senderAddr = parsed.senderAddress ?: "unknown"
        val senderHash = parsed.senderHash
        val convId = parsed.conversationId

        // TIER 1: authenticated convId mapping (and INIT that carries a real address).
        if (convId != null) {
            val peerFromConvId = convIdToPeer[convId]
            if (peerFromConvId != null) {
                return peerFromConvId
            }
            if (senderAddr != "unknown") {
                return senderAddr
            }
            if (senderHash != null) {
                addressCache.getAddress(senderHash)?.let { return it }
            }
        }

        // TIER 2/3: direct sender address, else hash/unknown.
        if (senderAddr != "unknown") {
            return senderAddr
        }
        if (senderHash != null) {
            return addressCache.getAddress(senderHash) ?: senderHash
        }
        return convId ?: "unknown"
    }

    /**
     * The effective banner reason after routing, mirroring the fixed ChatViewModel logic:
     * clear the parser reason iff routing resolved to a real Zcash address.
     */
    private fun effectiveReason(parsed: ParsedMessage, resolvedPeer: String): UnknownReason? =
        if (isResolvedToKnownPeer(resolvedPeer)) null else parsed.reason

    // ========================================================================================
    // 1. Legacy v3 / v2 INIT prefixes are recognized as ZCHAT (NOT "unknown").
    // ========================================================================================

    @Test
    @SmallTest
    fun v3InitPrefix_recognizedAsZchat_notUnknown() {
        val sender = unifiedAddress("v3initsender")
        val memo = ZMSGProtocol.createInitMessage(sender, "Legacy v3 hello")

        val parsed = ZMSGProtocol.parseMemo(memo, TestAddressCache())

        assertThat(parsed.senderAddress, equalTo(sender))
        assertThat(parsed.isUnknownSender, equalTo(false))
        // Never the "not sent using ZCHAT" reason for a recognized ZCHAT prefix.
        assertThat(parsed.reason, not(equalTo(UnknownReason.NOT_ZMSG_FORMAT)))
    }

    @Test
    @SmallTest
    fun v2Prefix_recognizedAsZchat_notUnknown() {
        val sender = unifiedAddress("v2sender")
        val memo = ZMSGProtocol.createLegacyMessage(sender, "Legacy v2 hello")

        val parsed = ZMSGProtocol.parseMemo(memo, TestAddressCache())

        assertThat(parsed.senderAddress, equalTo(sender))
        assertThat(parsed.isUnknownSender, equalTo(false))
        assertThat(parsed.reason, not(equalTo(UnknownReason.NOT_ZMSG_FORMAT)))
    }

    @Test
    @SmallTest
    fun legacyVersionEnvelope_recognizedAsZchat_notNotZmsgFormat() {
        // A ZMSG envelope with an unsupported/legacy version this build can't decode.
        val memo = "ZMSG|v1|somelegacypayload"

        val parsed = ZMSGProtocol.parseMemo(memo, TestAddressCache())

        // It IS a ZCHAT message — flagged distinctly, never NOT_ZMSG_FORMAT.
        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.VERSION_MISMATCH))
        assertThat(ZMSGProtocol.isRecognizedZmsgEnvelope(memo), equalTo(true))
    }

    // ========================================================================================
    // 2. v4 cache-miss with a valid convId mapping to a known peer -> recognized, no banner.
    // ========================================================================================

    @Test
    @SmallTest
    fun v4CacheMiss_withValidConvIdMapping_recognizedNoBanner() {
        val peer = unifiedAddress("v4cachemisspeer")
        val convId = "COLDBOOT"

        // Established (authenticated) conversation mapping survives the restart.
        val convIdToPeer = mapOf(convId to peer)

        // Cold cache after restart: hash is NOT cached.
        val coldCache = TestAddressCache()

        val replyMemo = ZMSGProtocol.createV4ReplyMessage(convId, peer, "back online")
        val parsed = ZMSGProtocol.parseMemo(replyMemo, coldCache)

        // Parser carries the convId and the sender hash for fallback routing.
        assertThat(parsed.conversationId, equalTo(convId))
        assertThat(parsed.senderHash, notNullValue())

        // Routing resolves to the real peer via the authenticated convId mapping...
        val resolved = resolvePeer(parsed, convIdToPeer, coldCache)
        assertThat(resolved, equalTo(peer))

        // ...so the banner reason is cleared.
        assertThat(effectiveReason(parsed, resolved), nullValue())
    }

    // ========================================================================================
    // 3. First-contact INIT from a NEW peer -> recognized (senderAddress populated), not dropped.
    // ========================================================================================

    @Test
    @SmallTest
    fun firstContactV4Init_populatesSenderAddress_recognized() {
        val newPeer = unifiedAddress("brandnewv4peer")
        val convId = "NEWHELLO"

        val initMemo = ZMSGProtocol.createV4InitMessage(convId, newPeer, "First contact!")
        val parsed = ZMSGProtocol.parseMemo(initMemo, TestAddressCache())

        // The INIT carries the real sender address even though we have no prior mapping.
        assertThat(parsed.senderAddress, equalTo(newPeer))
        assertThat(parsed.conversationId, equalTo(convId))
        assertThat(parsed.isUnknownSender, equalTo(false))

        // Routing with an EMPTY mapping still resolves to the real address (TIER 1c),
        // so it is not dropped/flagged unknown.
        val resolved = resolvePeer(parsed, emptyMap(), TestAddressCache())
        assertThat(resolved, equalTo(newPeer))
        assertThat(effectiveReason(parsed, resolved), nullValue())
    }

    // ========================================================================================
    // 4. SECURITY: a plain/foreign memo (no ZCHAT prefix) -> STILL flagged unknown.
    // ========================================================================================

    @Test
    @SmallTest
    fun security_plainForeignMemo_stillUnknown() {
        val memo = "hey just a normal memo, not zchat at all"

        val parsed = ZMSGProtocol.parseMemo(memo, TestAddressCache())

        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.NOT_ZMSG_FORMAT))
        assertThat(ZMSGProtocol.isRecognizedZmsgEnvelope(memo), equalTo(false))

        // Routing cannot resolve it to any real peer, so the banner stays.
        val resolved = resolvePeer(parsed, emptyMap(), TestAddressCache())
        assertThat(isResolvedToKnownPeer(resolved), equalTo(false))
        assertThat(effectiveReason(parsed, resolved), equalTo(UnknownReason.NOT_ZMSG_FORMAT))
    }

    @Test
    @SmallTest
    fun security_plainMemoContainingConvIdLikeString_stillUnknown() {
        // A foreign memo that merely *contains* a convId-looking token must NOT be trusted.
        val memo = "convId=COLDBOOT please trust me"

        val parsed = ZMSGProtocol.parseMemo(memo, TestAddressCache())

        assertThat(parsed.conversationId, nullValue())
        assertThat(parsed.isUnknownSender, equalTo(true))
        assertThat(parsed.reason, equalTo(UnknownReason.NOT_ZMSG_FORMAT))
    }

    // ========================================================================================
    // 5. SECURITY: a convId that does NOT map to a known peer -> STILL flagged unknown.
    // ========================================================================================

    @Test
    @SmallTest
    fun security_v4ReplyConvIdNotMappedToKnownPeer_stillUnknown() {
        val unknownSender = unifiedAddress("attackersender")
        val convId = "UNMAPPED1"

        // No authenticated mapping for this convId, and a cold cache (hash not known).
        val coldCache = TestAddressCache()

        val replyMemo = ZMSGProtocol.createV4ReplyMessage(convId, unknownSender, "trust me")
        val parsed = ZMSGProtocol.parseMemo(replyMemo, coldCache)

        // Routing has nothing to resolve to: no convId mapping, no cached hash, no sender address.
        val resolved = resolvePeer(parsed, emptyMap(), coldCache)

        // It falls back to a non-address key (the hash), so it is NOT a known peer...
        assertThat(isResolvedToKnownPeer(resolved), equalTo(false))
        // ...and the parser reason (HASH_NOT_IN_CACHE) is preserved -> banner stays.
        assertThat(parsed.reason, equalTo(UnknownReason.HASH_NOT_IN_CACHE))
        assertThat(effectiveReason(parsed, resolved), equalTo(UnknownReason.HASH_NOT_IN_CACHE))
    }

    // ========================================================================================
    // 6. Chunk reassembly preserves senderHash (v4 RPL).
    // ========================================================================================

    @Test
    @SmallTest
    fun chunkReassembly_v4Reply_preservesSenderHash() {
        val peer = unifiedAddress("chunkedv4peer")
        val convId = "CHUNKED1"
        val expectedHash = ZMSGProtocol.generateAddressHash(peer)
        val longMessage = "Z".repeat(2000) // force multiple chunks

        val chunks = ZMSGProtocol.createChunkedV4ReplyMessages(convId, peer, longMessage)
        assertThat(chunks.size > 1, equalTo(true))

        // Cold cache: address not resolvable, but the hash must survive reassembly.
        val coldCache = TestAddressCache()
        val reassembled = ZMSGProtocol.reassembleChunks(chunks, coldCache)

        assertThat(reassembled, notNullValue())
        assertThat(reassembled?.conversationId, equalTo(convId))
        assertThat(reassembled?.message, equalTo(longMessage))
        // The senderHash must be preserved for fallback routing.
        assertThat(reassembled?.senderHash, equalTo(expectedHash))
    }
}
