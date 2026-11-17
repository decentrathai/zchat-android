package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W-72 (SPEC_F_20261019 compat rows, RED test 15b; SPEC_DECISIONS D3), skill compat-2124 rule 1: what a ZChat 2.12.4 phone
 * does with the probe and with the new store entries, modelled with code copied from `v2.12.4` (a1fb4136f), not HEAD.
 *
 * [Frozen2124.resolveInitSender] is TIER 1 of the inbound resolver of `ui-lib/.../chat/viewmodel/ChatViewModel.kt` at
 * v2.12.4, lines 3620-3666 (1a direct convId lookup with the sender-hash check, 1b reverse lookup, 1c a new convId with
 * the sender's address), with its store calls inlined; [Frozen2124.setConversationMapping] is
 * `ZchatPreferencesImpl.setConversationMapping` at v2.12.4 (lines 2444-2470, the format check left out: the ids here are
 * well formed); the three loops are `getAllConversationMappings`, `getAllPeerToConvIdMappings` (lines 2475-2489) and
 * `getAllConversationPeerAddresses` (lines 3069-3075). 2.12.4 has no loop over the key store's entries.
 *
 * Wanted, and pinned: an INIT under a new id from a contact the phone already chats with is filed under that contact's
 * address (TIER 1c maps the id to it; `peer:` keeps the phone's own id), so the probe never splits the chat; the reply
 * id entry "replyconv:<address>" and the key store's new entries are skipped by every 2.12.4 loop. And the same loops of
 * this build skip "replyconv:". Green before and after the fix (a guard). Every address and id is synthetic.
 */
class LegacyProbeFiledBy2124Test {
    @Test
    fun `(15b) a 2_12_4 phone files an INIT under a new id from a known contact in the existing chat`() {
        val store = HashMap<String, String>()
        store["peer:$A"] = OLD_ID
        store["conv:$OLD_ID"] = A
        val messagesByPeer = mapOf(A to listOf("an older message"))

        val resolved = Frozen2124.resolveInitSender(store, messagesByPeer, convId = PROBE_ID, senderAddr = A)

        assertEquals("2.12.4 filed the probe in another chat", A, resolved)
        assertEquals("2.12.4 did not map the probe id to the contact", A, store["conv:$PROBE_ID"])
        assertEquals("2.12.4 moved its own id for the contact", OLD_ID, store["peer:$A"])
    }

    @Test
    fun `(15b) every 2_12_4 loop over the conversation-id store skips the reply id, and so does this build's`() {
        val store = HashMap<String, Any>()
        store["peer:$A"] = OLD_ID
        store["conv:$OLD_ID"] = A
        store["replyconv:$A"] = "1\n$PROBE_ID\n3500000\n0"

        assertEquals(mapOf(OLD_ID to A), Frozen2124.getAllConversationMappings(store))
        assertEquals(mapOf(A to OLD_ID), Frozen2124.getAllPeerToConvIdMappings(store))
        assertEquals(setOf(A), Frozen2124.getAllConversationPeerAddresses(store))

        val fixture = ConversationIdStoreFixture()
        fixture
            .file
            .edit()
            .putString("peer:$A", OLD_ID)
            .putString("conv:$OLD_ID", A)
            .putString("replyconv:$A", "1\n$PROBE_ID\n3500000\n0")
            .commit()
        assertEquals(mapOf(OLD_ID to A), fixture.store.getAllConversationMappings())
        assertEquals(mapOf(A to OLD_ID), fixture.store.getAllPeerToConvIdMappings())
        assertFalse("this build reads the reply id as a conversation", fixture.store.getAllConversationMappings().containsKey("$A"))
        assertTrue(fixture.store.getPeerByConversationId(PROBE_ID) == null)
    }

    /** Code copied from v2.12.4 (see the class KDoc for the lines). */
    private object Frozen2124 {
        fun setConversationMapping(
            prefs: MutableMap<String, String>,
            convId: String,
            peerAddress: String,
        ) {
            prefs["conv:$convId"] = peerAddress
            // Only set peer→convId if no mapping exists yet (don't overwrite our own convId)
            val existingConvId = prefs["peer:$peerAddress"]
            if (existingConvId == null) {
                prefs["peer:$peerAddress"] = convId
            }
        }

        /** TIER 1 with a sender address (an INIT); the sender hash is the address's own, so 1a's check passes. */
        fun resolveInitSender(
            prefs: MutableMap<String, String>,
            messagesByPeer: Map<String, List<String>>,
            convId: String,
            senderAddr: String,
        ): String? {
            // 1a: Direct convId → peer lookup
            val peerFromConvId = prefs["conv:$convId"]
            if (peerFromConvId != null) return peerFromConvId
            // 1b: Reverse lookup - check if any existing peer has this convId
            val existingPeerForConvId =
                messagesByPeer.keys.find { peerAddr ->
                    prefs["peer:$peerAddr"] == convId
                }
            if (existingPeerForConvId != null) {
                setConversationMapping(prefs, convId, existingPeerForConvId)
                return existingPeerForConvId
            }
            // 1c: ConvId not found but we have sender address — new INIT from someone
            if (senderAddr != "unknown") {
                setConversationMapping(prefs, convId, senderAddr)
                return senderAddr
            }
            return null
        }

        fun getAllConversationMappings(all: Map<String, Any>): Map<String, String> =
            all
                .filterKeys { it.startsWith("conv:") }
                .filterValues { it is String }
                .mapKeys { it.key.removePrefix("conv:") }
                .mapValues { it.value as String }

        fun getAllPeerToConvIdMappings(all: Map<String, Any>): Map<String, String> =
            all
                .filterKeys { it.startsWith("peer:") }
                .filterValues { it is String }
                .mapKeys { it.key.removePrefix("peer:") }
                .mapValues { it.value as String }

        fun getAllConversationPeerAddresses(all: Map<String, Any>): Set<String> =
            all.keys
                .filter { it.startsWith("peer:") }
                .map { it.removePrefix("peer:") }
                .toSet()
    }

    private companion object {
        const val A = "usyntheticlegacy2124peer0001"
        const val OLD_ID = "ZC72OLD2"
        const val PROBE_ID = "ZC72PRB4"
    }
}
