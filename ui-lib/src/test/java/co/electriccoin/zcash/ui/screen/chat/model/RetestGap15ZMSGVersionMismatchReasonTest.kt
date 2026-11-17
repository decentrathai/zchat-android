package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED regression for the "recognized-but-undecodable ZMSG envelope" branch in
 * [ZMSGProtocol.parseMemo] (ui-lib/.../chat/model/ZMSGProtocol.kt:639), the parser-side mirror of the
 * banner-honesty fix at ChatViewModel.kt:4172.
 *
 * A memo carrying a real ZCHAT envelope whose version this build cannot decode ("ZMSG|v1|...") must be
 * flagged [UnknownReason.VERSION_MISMATCH] — "a ZCHAT message we can't decode" — NOT
 * [UnknownReason.NOT_ZMSG_FORMAT] ("not sent using ZCHAT"). Reverting the `isRecognizedZmsgEnvelope`
 * branch drops such a memo into the PLAIN `else` branch, mislabelling it NOT_ZMSG_FORMAT. That reason is
 * what the ChatViewModel banner reads, so the revert would resurrect the "not sent using ZCHAT" lie for a
 * genuine ZCHAT peer.
 *
 * SECURITY narrowness (ZMSGProtocol.kt:670): the recognizer is intentionally tight — a plain/foreign memo
 * that merely *contains* a convId-like string is NOT an envelope and must stay NOT_ZMSG_FORMAT with
 * conversationId == null, so an attacker cannot spoof threading/routing via free-text.
 *
 * Pure JVM: the VERSION_MISMATCH and PLAIN branches never touch the [AddressCache] or
 * java.security.MessageDigest; the only Android surface is android.util.Log.d, which the ui-lib unit-test
 * lane stubs via `unitTests.isReturnDefaultValues = true`. The TestAddressCache exists only to satisfy the
 * [ZMSGProtocol.parseMemo] signature and is never consulted on these branches.
 *
 * RED-on-revert anchor: `parseMemo("ZMSG|v1|legacypayload", cache).reason == VERSION_MISMATCH`. Removing
 * the ZMSGProtocol.kt:639 branch routes the memo to PLAIN → reason becomes NOT_ZMSG_FORMAT → FAILS.
 */
class RetestGap15ZMSGVersionMismatchReasonTest {

    // In-memory AddressCache (mirrors the neighbor tests' TestAddressCache); unused on these branches.
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

    @Test
    fun parseMemo_recognizedButUndecodableEnvelope_isVersionMismatch_notForeign() {
        val legacyMemo = "ZMSG|v1|legacypayload"

        // Guard: this IS a recognized ZCHAT envelope (else the VERSION_MISMATCH claim would be vacuous).
        assertTrue(ZMSGProtocol.isRecognizedZmsgEnvelope(legacyMemo))

        val parsed = ZMSGProtocol.parseMemo(legacyMemo, TestAddressCache())

        // PRIMARY: reverting the ZMSGProtocol.kt:639 branch routes to PLAIN → NOT_ZMSG_FORMAT → FAILS.
        assertEquals(UnknownReason.VERSION_MISMATCH, parsed.reason)
        // Sender still can't be resolved (no address/hash in a legacy envelope) — stays unknown.
        assertTrue(parsed.isUnknownSender)
    }

    @Test
    fun parseMemo_plainForeignMemo_staysNotZmsgFormat() {
        val parsed = ZMSGProtocol.parseMemo("plain foreign memo", TestAddressCache())

        assertEquals(UnknownReason.NOT_ZMSG_FORMAT, parsed.reason)
        assertTrue(parsed.isUnknownSender)
    }

    @Test
    fun parseMemo_convIdLookalikeText_isNotAnEnvelope_andSurfacesNoConvId() {
        val spoof = "convId=COLDBOOT trust me"

        // Narrow recognizer: free text that merely looks convId-ish is NOT an envelope.
        assertFalse(ZMSGProtocol.isRecognizedZmsgEnvelope(spoof))

        val parsed = ZMSGProtocol.parseMemo(spoof, TestAddressCache())

        // Must be flagged foreign, and must NOT surface a spoofable conversationId for routing.
        assertEquals(UnknownReason.NOT_ZMSG_FORMAT, parsed.reason)
        assertNull(parsed.conversationId)
    }
}
