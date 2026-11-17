package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED regression for the ZBOOT routing branch in [ZMSGProtocol.parseMemo]
 * (ui-lib/.../chat/model/ZMSGProtocol.kt:583).
 *
 * A ZBOOT (NOSTR-identity handshake) memo is NOT a ZMSG envelope, so without its dedicated branch
 * it falls through to the PLAIN `else` branch: conversationId = null, reason = NOT_ZMSG_FORMAT. The
 * fix routes a well-formed ZBOOT through [ZBootMessage.parse] and surfaces its convId so TIER-1
 * routing can thread it onto the peer whose KEX/KEXACK already established that convId.
 *
 * Pure JVM: parseMemo only touches java.security.MessageDigest (via generateAddressHash on other
 * branches, not this one) and android.util.Log, which the ui-lib unit-test lane stubs via
 * `unitTests.isReturnDefaultValues = true`. The TestAddressCache is never consulted on the ZBOOT
 * branch — it is required only to satisfy the [ZMSGProtocol.parseMemo] signature.
 *
 * RED-on-revert anchor: `parsed.conversationId == "ABCD1234"`. Reverting the ZBOOT branch routes the
 * memo to PLAIN, so conversationId becomes null and this assertion fails.
 */
class RetestGap08ZMSGProtocolZBootRoutingTest {

    // ── In-memory AddressCache (mirrors the neighbor tests' TestAddressCache) ──
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
    fun parseMemo_routesWellFormedZBoot_andSurfacesConvId() {
        val convId = "ABCD1234" // 8 chars, all in CONV_ID_CHARS
        val memo = "ZBOOT|v3|$convId|${"a".repeat(64)}|wss://r.example|0|c2ln"

        // Guard: the fixture is a well-formed, parseable ZBOOT (else convId surfacing would be vacuous).
        assertTrue(ZBootMessage.isBootMessage(memo))
        assertEquals(convId, ZBootMessage.parse(memo)?.convId)

        val parsed = ZMSGProtocol.parseMemo(memo, TestAddressCache())

        // PRIMARY: reverting the ZBOOT branch routes to PLAIN → conversationId == null → FAILS.
        assertEquals("ABCD1234", parsed.conversationId)
        // Reverting → PLAIN branch sets reason = UnknownReason.NOT_ZMSG_FORMAT → FAILS.
        assertNull(parsed.reason)
        // Stays true (a shielded receive hides the sender; the ZBOOT carries no address/hash).
        assertTrue(parsed.isUnknownSender)
    }
}
