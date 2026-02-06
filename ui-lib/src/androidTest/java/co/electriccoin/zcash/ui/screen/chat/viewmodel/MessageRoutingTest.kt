package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for message routing.
 *
 * These tests verify that messages are correctly routed to conversations
 * using the combination of:
 * - ZMSGProtocol (message parsing/creation)
 * - ZchatPreferences (convId storage)
 * - AddressCache (sender identification)
 *
 * The goal is to ensure messages NEVER go to wrong conversations.
 */
class MessageRoutingTest {

    private lateinit var prefs: ZchatPreferences
    private lateinit var addressCache: TestAddressCache

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

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = ZchatPreferencesImpl(context)
        prefs.clearAll()
        addressCache = TestAddressCache()
    }

    @After
    fun teardown() {
        prefs.clearAll()
    }

    // ==========================================
    // SCENARIO: New Conversation Initiated By Us
    // ==========================================

    @Test
    @SmallTest
    fun scenario_weInitiateNewConversation_messageRoutedCorrectly() {
        // Setup: We're starting a new conversation with Alice
        val ourAddress = "u1ourunifiedaddress1234567890"
        val aliceAddress = "u1aliceunifiedaddress1234567890"

        // Generate a new conversation ID
        val convId = ZMSGProtocol.generateConversationId()

        // Store the mapping before sending
        prefs.setConversationId(aliceAddress, convId)

        // Create the INIT message we would send
        val initMemo = ZMSGProtocol.createV4InitMessage(convId, ourAddress, "Hello Alice!")

        // Later, Alice replies with the same convId
        val replyMemo = ZMSGProtocol.createV4ReplyMessage(convId, aliceAddress, "Hi there!")

        // Parse Alice's reply
        val parsed = ZMSGProtocol.parseMemo(replyMemo, addressCache)

        // The message should have the same convId
        assertThat(parsed.conversationId, equalTo(convId))

        // We can find Alice by the convId
        val foundPeer = prefs.getPeerByConversationId(convId)
        assertThat(foundPeer, equalTo(aliceAddress))
    }

    // ==========================================
    // SCENARIO: New Conversation Received From Peer
    // ==========================================

    @Test
    @SmallTest
    fun scenario_weReceiveInitFromNewPeer_mappingCreated() {
        // Alice sends us an INIT message with a new conversation ID
        val aliceAddress = "u1alicefromnetwork1234567890"
        val convId = "FROMNET1"

        val initMemo = ZMSGProtocol.createV4InitMessage(convId, aliceAddress, "Hello, this is Alice!")

        // Parse the incoming message
        val parsed = ZMSGProtocol.parseMemo(initMemo, addressCache)

        // Should extract all fields
        assertThat(parsed.senderAddress, equalTo(aliceAddress))
        assertThat(parsed.conversationId, equalTo(convId))
        assertThat(parsed.message, equalTo("Hello, this is Alice!"))

        // Store the mapping (simulating what ChatViewModel would do)
        prefs.setConversationMapping(convId, aliceAddress)

        // Now we should be able to look up Alice by convId
        assertThat(prefs.getPeerByConversationId(convId), equalTo(aliceAddress))

        // And convId by Alice
        assertThat(prefs.getConversationId(aliceAddress), equalTo(convId))
    }

    // ==========================================
    // SCENARIO: Reply Without Full Address (Hash Only)
    // ==========================================

    @Test
    @SmallTest
    fun scenario_replyWithHashOnly_routedViaConvId() {
        val bobAddress = "u1bobunifiedaddress1234567890"
        val convId = "HASH0001"

        // We've already established a conversation with Bob
        prefs.setConversationId(bobAddress, convId)

        // Bob sends a reply using v4 with just the hash (not INIT)
        val replyMemo = ZMSGProtocol.createV4ReplyMessage(convId, bobAddress, "Reply message")

        // Parse the message
        val parsed = ZMSGProtocol.parseMemo(replyMemo, addressCache)

        // The message has convId which is the PRIMARY routing key
        assertThat(parsed.conversationId, equalTo(convId))

        // Even without the address being cached, we can find Bob via convId
        val foundPeer = prefs.getPeerByConversationId(convId)
        assertThat(foundPeer, equalTo(bobAddress))
    }

    // ==========================================
    // SCENARIO: Wallet Restore Recovery
    // ==========================================

    @Test
    @SmallTest
    fun scenario_walletRestore_convIdMappingSurvives() {
        val charlieAddress = "u1charlierestore1234567890"
        val convId = "RESTORE1"

        // Before "restore": conversation was established
        prefs.setConversationId(charlieAddress, convId)

        // Verify mapping exists
        assertThat(prefs.getConversationId(charlieAddress), equalTo(convId))
        assertThat(prefs.getPeerByConversationId(convId), equalTo(charlieAddress))

        // Simulate "restore" - clear address cache but keep preferences
        // (In real restore, prefs would be restored from backup or rebuilt from transactions)
        val newAddressCache = TestAddressCache()

        // Charlie sends a new message (we only have the convId, not cached address)
        val messageMemo = ZMSGProtocol.createV4ReplyMessage(convId, charlieAddress, "Post-restore message")
        val parsed = ZMSGProtocol.parseMemo(messageMemo, newAddressCache)

        // ConvId is still present
        assertThat(parsed.conversationId, equalTo(convId))

        // We can still route to Charlie via convId
        assertThat(prefs.getPeerByConversationId(convId), equalTo(charlieAddress))
    }

    // ==========================================
    // SCENARIO: Multiple Conversations Don't Cross
    // ==========================================

    @Test
    @SmallTest
    fun scenario_multipleConversations_noCrossTalk() {
        // Setup three different conversations
        val alice = "u1alice1234"
        val bob = "u1bob5678"
        val charlie = "u1charlie9012"

        val aliceConvId = "ALICE001"
        val bobConvId = "BOBBB002"
        val charlieConvId = "CHARL003"

        prefs.setConversationId(alice, aliceConvId)
        prefs.setConversationId(bob, bobConvId)
        prefs.setConversationId(charlie, charlieConvId)

        // Receive messages from each
        val aliceMsg = ZMSGProtocol.createV4ReplyMessage(aliceConvId, alice, "From Alice")
        val bobMsg = ZMSGProtocol.createV4ReplyMessage(bobConvId, bob, "From Bob")
        val charlieMsg = ZMSGProtocol.createV4ReplyMessage(charlieConvId, charlie, "From Charlie")

        // Parse each
        val parsedAlice = ZMSGProtocol.parseMemo(aliceMsg, addressCache)
        val parsedBob = ZMSGProtocol.parseMemo(bobMsg, addressCache)
        val parsedCharlie = ZMSGProtocol.parseMemo(charlieMsg, addressCache)

        // Each routes to correct conversation
        assertThat(prefs.getPeerByConversationId(parsedAlice.conversationId!!), equalTo(alice))
        assertThat(prefs.getPeerByConversationId(parsedBob.conversationId!!), equalTo(bob))
        assertThat(prefs.getPeerByConversationId(parsedCharlie.conversationId!!), equalTo(charlie))

        // Cross-check: no mix-ups
        assertThat(prefs.getPeerByConversationId(aliceConvId), equalTo(alice))
        assertThat(prefs.getPeerByConversationId(bobConvId), equalTo(bob))
        assertThat(prefs.getPeerByConversationId(charlieConvId), equalTo(charlie))
    }

    // ==========================================
    // SCENARIO: Malformed ConvId Rejection
    // ==========================================

    @Test
    @SmallTest
    fun scenario_malformedConvId_notRouted() {
        // Manually craft a message with invalid convId
        val malformedMemo = "ZMSG|v4|abcd1234|Test"  // lowercase convId

        val parsed = ZMSGProtocol.parseMemo(malformedMemo, addressCache)

        // Should be marked as malformed
        assertThat(parsed.conversationId, nullValue())
        assertThat(parsed.isUnknownSender, equalTo(true))
    }

    @Test
    @SmallTest
    fun scenario_shortConvId_notRouted() {
        val shortConvIdMemo = "ZMSG|v4|ABC|Test"  // Only 3 chars

        val parsed = ZMSGProtocol.parseMemo(shortConvIdMemo, addressCache)

        assertThat(parsed.conversationId, nullValue())
        assertThat(parsed.isUnknownSender, equalTo(true))
    }

    // ==========================================
    // SCENARIO: Unknown ConvId (New Conversation)
    // ==========================================

    @Test
    @SmallTest
    fun scenario_unknownConvId_createsNewConversation() {
        // A completely new convId from an INIT message
        val newPeerAddress = "u1brandnewpeer1234567890"
        val unknownConvId = "NEWPEER1"

        val initMemo = ZMSGProtocol.createV4InitMessage(unknownConvId, newPeerAddress, "First contact!")

        val parsed = ZMSGProtocol.parseMemo(initMemo, addressCache)

        // ConvId should be extracted
        assertThat(parsed.conversationId, equalTo(unknownConvId))

        // Sender address from INIT
        assertThat(parsed.senderAddress, equalTo(newPeerAddress))

        // Before mapping exists, lookup returns null
        assertThat(prefs.getPeerByConversationId(unknownConvId), nullValue())

        // After storing mapping, lookup works
        prefs.setConversationMapping(unknownConvId, newPeerAddress)
        assertThat(prefs.getPeerByConversationId(unknownConvId), equalTo(newPeerAddress))
    }

    // ==========================================
    // SCENARIO: Fallback to Hash When ConvId Fails
    // ==========================================

    @Test
    @SmallTest
    fun scenario_convIdUnknown_fallbackToHash() {
        val senderAddress = "u1fallbacksender1234567890"
        val hash = ZMSGProtocol.generateAddressHash(senderAddress)

        // Pre-populate address cache with sender's hash
        addressCache.cacheAddress(hash, senderAddress)

        // An unknown convId but known hash
        val unknownConvId = "UNKNOWN1"
        val replyMemo = ZMSGProtocol.createV4ReplyMessage(unknownConvId, senderAddress, "Message")

        val parsed = ZMSGProtocol.parseMemo(replyMemo, addressCache)

        // ConvId is extracted (but we don't have mapping)
        assertThat(parsed.conversationId, equalTo(unknownConvId))

        // Primary routing via convId fails (no mapping)
        assertThat(prefs.getPeerByConversationId(unknownConvId), nullValue())

        // Fallback: sender hash is present and can be resolved
        assertThat(parsed.senderHash, notNullValue())
        assertThat(addressCache.getAddress(parsed.senderHash!!), equalTo(senderAddress))
    }

    // ==========================================
    // SCENARIO: v3 Legacy Message Compatibility
    // ==========================================

    @Test
    @SmallTest
    fun scenario_v3Message_routedViaHashOrRef() {
        val legacySender = "u1legacysender1234567890"
        val hash = ZMSGProtocol.generateAddressHash(legacySender)

        // v3 INIT message (no convId)
        val v3InitMemo = ZMSGProtocol.createInitMessage(legacySender, "Legacy hello")

        val parsed = ZMSGProtocol.parseMemo(v3InitMemo, addressCache)

        // No convId in v3
        assertThat(parsed.conversationId, nullValue())

        // But address is extracted from INIT
        assertThat(parsed.senderAddress, equalTo(legacySender))

        // Address is cached for future hash lookups
        assertThat(addressCache.getAddress(hash), equalTo(legacySender))
    }

    @Test
    @SmallTest
    fun scenario_v3RefMessage_routedViaTxId() {
        val sender = "u1refsender1234567890"
        val refTxId = "abc123txid456def"
        val hash = ZMSGProtocol.generateAddressHash(sender)

        // Cache the sender address
        addressCache.cacheAddress(hash, sender)

        // v3 REF message (references a transaction)
        val refMemo = ZMSGProtocol.createRefMessage(sender, "Referencing transaction", refTxId)

        val parsed = ZMSGProtocol.parseMemo(refMemo, addressCache)

        // No convId
        assertThat(parsed.conversationId, nullValue())

        // But has txId reference
        assertThat(parsed.replyToTxId, equalTo(refTxId))

        // Sender can be resolved via hash
        assertThat(parsed.senderAddress, equalTo(sender))
    }

    // ==========================================
    // SCENARIO: Chunked Message Routing
    // ==========================================

    @Test
    @SmallTest
    fun scenario_chunkedMessage_routedCorrectly() {
        val sender = "u1chunkedsender1234567890"
        val convId = "CHUNK001"
        val longMessage = "A".repeat(2000)  // Long enough to require chunking

        // Create chunked messages
        val chunks = ZMSGProtocol.createChunkedV4InitMessages(convId, sender, longMessage)
        assertThat(chunks.size > 1, equalTo(true))

        // Reassemble
        val reassembled = ZMSGProtocol.reassembleChunks(chunks, addressCache)

        // Should have correct convId
        assertThat(reassembled?.conversationId, equalTo(convId))

        // Full message reconstructed
        assertThat(reassembled?.message, equalTo(longMessage))

        // Sender address extracted
        assertThat(reassembled?.senderAddress, equalTo(sender))
    }

    // ==========================================
    // SCENARIO: KEX Message Routing
    // ==========================================

    @Test
    @SmallTest
    fun scenario_kexMessage_routedToConversation() {
        val peer = "u1kexpeer1234567890"
        val convId = "KEXCON01"
        val kexPayload = "publicKey:signature:data"

        // Store conversation mapping
        prefs.setConversationId(peer, convId)

        // Create KEX message
        val kexMemo = ZMSGProtocol.createV4KEXMessage(convId, peer, kexPayload)

        // Should be identifiable as KEX
        assertThat(ZMSGProtocol.isKEXMessage(kexMemo), equalTo(true))

        // Parse KEX message
        val kexResult = ZMSGProtocol.parseKEXMessage(kexMemo)

        // ConvId extracted
        assertThat(kexResult?.first, equalTo(convId))

        // Can route to peer
        assertThat(prefs.getPeerByConversationId(convId), equalTo(peer))
    }

    // ==========================================
    // SCENARIO: Bidirectional Mapping Consistency
    // ==========================================

    @Test
    @SmallTest
    fun scenario_mappingConsistency_verifiedOnRead() {
        val peer = "u1consistencypeer1234567890"
        val convId = "CONSIST1"

        // Set mapping
        prefs.setConversationId(peer, convId)

        // Both directions should work
        assertThat(prefs.getConversationId(peer), equalTo(convId))
        assertThat(prefs.getPeerByConversationId(convId), equalTo(peer))

        // All mappings should be present
        val allMappings = prefs.getAllConversationMappings()
        val allReverse = prefs.getAllPeerToConvIdMappings()

        assertThat(allMappings[convId], equalTo(peer))
        assertThat(allReverse[peer], equalTo(convId))
    }

    // ==========================================
    // SCENARIO: Special Characters in Message
    // ==========================================

    @Test
    @SmallTest
    fun scenario_specialCharactersInMessage_routedCorrectly() {
        val sender = "u1specialcharsender1234567890"
        val convId = "SPECIAL1"
        // Message with pipes, unicode, and special chars
        val message = "Price is \$100|Deal? 你好 🎉 <script>alert('xss')</script>"

        val memo = ZMSGProtocol.createV4InitMessage(convId, sender, message)
        val parsed = ZMSGProtocol.parseMemo(memo, addressCache)

        // ConvId should still be extracted correctly
        assertThat(parsed.conversationId, equalTo(convId))

        // Message preserved (with all special chars)
        assertThat(parsed.message, equalTo(message))

        // Store mapping
        prefs.setConversationMapping(convId, sender)

        // Routing works
        assertThat(prefs.getPeerByConversationId(convId), equalTo(sender))
    }
}
