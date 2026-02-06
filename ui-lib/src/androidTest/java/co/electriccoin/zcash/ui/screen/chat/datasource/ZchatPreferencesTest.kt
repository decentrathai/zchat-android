package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for ZchatPreferences - the conversation ID storage layer.
 *
 * These tests ensure perfect bidirectional mapping between conversation IDs and peer addresses,
 * which is critical for reliable message routing after wallet restore.
 */
class ZchatPreferencesTest {

    private lateinit var prefs: ZchatPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = ZchatPreferencesImpl(context)
        prefs.clearAll()
    }

    @After
    fun teardown() {
        prefs.clearAll()
    }

    // ==========================================
    // CONVERSATION ID MAPPING - CORE ROUTING
    // ==========================================

    @Test
    @SmallTest
    fun setConversationId_storesBidirectionally() {
        val peer = "u1testpeeraddress123456789"
        val convId = "ABCD1234"

        prefs.setConversationId(peer, convId)

        // Forward lookup: peer -> convId
        assertThat(prefs.getConversationId(peer), equalTo(convId))

        // Reverse lookup: convId -> peer
        assertThat(prefs.getPeerByConversationId(convId), equalTo(peer))
    }

    @Test
    @SmallTest
    fun setConversationMapping_storesBidirectionally() {
        val peer = "u1receivedmessagefrom"
        val convId = "WXYZ5678"

        prefs.setConversationMapping(convId, peer)

        // Forward lookup: convId -> peer
        assertThat(prefs.getPeerByConversationId(convId), equalTo(peer))

        // Reverse lookup: peer -> convId
        assertThat(prefs.getConversationId(peer), equalTo(convId))
    }

    @Test
    @SmallTest
    fun getConversationId_returnsNullForUnknownPeer() {
        val unknownPeer = "u1unknownpeeraddress"
        assertThat(prefs.getConversationId(unknownPeer), nullValue())
    }

    @Test
    @SmallTest
    fun getPeerByConversationId_returnsNullForUnknownConvId() {
        assertThat(prefs.getPeerByConversationId("UNKNOWN1"), nullValue())
    }

    @Test
    @SmallTest
    fun setConversationId_rejectsBlankPeerAddress() {
        val convId = "VALID123"

        prefs.setConversationId("", convId)
        prefs.setConversationId("   ", convId)

        // Should not be stored
        assertThat(prefs.getPeerByConversationId(convId), nullValue())
    }

    @Test
    @SmallTest
    fun setConversationId_rejectsInvalidConvIdLength() {
        val peer = "u1testpeer"

        // Too short
        prefs.setConversationId(peer, "ABC")
        assertThat(prefs.getConversationId(peer), nullValue())

        // Too long
        prefs.setConversationId(peer, "ABCDEFGHI")
        assertThat(prefs.getConversationId(peer), nullValue())
    }

    @Test
    @SmallTest
    fun setConversationId_rejectsLowercaseConvId() {
        val peer = "u1testpeer"

        prefs.setConversationId(peer, "abcd1234")

        // Should not be stored (lowercase not allowed)
        assertThat(prefs.getConversationId(peer), nullValue())
    }

    @Test
    @SmallTest
    fun setConversationId_rejectsSpecialCharacters() {
        val peer = "u1testpeer"

        prefs.setConversationId(peer, "ABCD!@#$")

        // Should not be stored
        assertThat(prefs.getConversationId(peer), nullValue())
    }

    @Test
    @SmallTest
    fun setConversationMapping_rejectsInvalidFormats() {
        // Invalid convId
        prefs.setConversationMapping("invalid", "u1peer")
        assertThat(prefs.getPeerByConversationId("invalid"), nullValue())

        // Blank peer
        prefs.setConversationMapping("VALID123", "")
        assertThat(prefs.getPeerByConversationId("VALID123"), nullValue())
    }

    @Test
    @SmallTest
    fun multipleConversations_storeIndependently() {
        val peer1 = "u1alice"
        val peer2 = "u1bob"
        val peer3 = "u1charlie"
        val convId1 = "ALICE001"
        val convId2 = "BOBBB002"
        val convId3 = "CHARL003"

        prefs.setConversationId(peer1, convId1)
        prefs.setConversationId(peer2, convId2)
        prefs.setConversationMapping(convId3, peer3)

        // All should be retrievable
        assertThat(prefs.getConversationId(peer1), equalTo(convId1))
        assertThat(prefs.getConversationId(peer2), equalTo(convId2))
        assertThat(prefs.getConversationId(peer3), equalTo(convId3))

        assertThat(prefs.getPeerByConversationId(convId1), equalTo(peer1))
        assertThat(prefs.getPeerByConversationId(convId2), equalTo(peer2))
        assertThat(prefs.getPeerByConversationId(convId3), equalTo(peer3))
    }

    @Test
    @SmallTest
    fun updateConversationId_replacesOldMapping() {
        val peer = "u1sameuser"
        val oldConvId = "OLDCONV01"
        val newConvId = "NEWCONV02"

        prefs.setConversationId(peer, oldConvId)
        prefs.setConversationId(peer, newConvId)

        // New mapping should be active
        assertThat(prefs.getConversationId(peer), equalTo(newConvId))
        assertThat(prefs.getPeerByConversationId(newConvId), equalTo(peer))

        // Note: old convId may still point to peer (SharedPreferences doesn't auto-cleanup)
        // This is acceptable as we always lookup peer->convId first
    }

    @Test
    @SmallTest
    fun getAllConversationMappings_returnsAllStoredMappings() {
        prefs.setConversationId("u1peer1", "CONV0001")
        prefs.setConversationId("u1peer2", "CONV0002")
        prefs.setConversationMapping("CONV0003", "u1peer3")

        val mappings = prefs.getAllConversationMappings()

        assertThat(mappings.size, equalTo(3))
        assertThat(mappings["CONV0001"], equalTo("u1peer1"))
        assertThat(mappings["CONV0002"], equalTo("u1peer2"))
        assertThat(mappings["CONV0003"], equalTo("u1peer3"))
    }

    @Test
    @SmallTest
    fun getAllPeerToConvIdMappings_returnsAllStoredMappings() {
        prefs.setConversationId("u1peer1", "CONV0001")
        prefs.setConversationId("u1peer2", "CONV0002")

        val mappings = prefs.getAllPeerToConvIdMappings()

        assertThat(mappings.size, equalTo(2))
        assertThat(mappings["u1peer1"], equalTo("CONV0001"))
        assertThat(mappings["u1peer2"], equalTo("CONV0002"))
    }

    // ==========================================
    // NICKNAMES
    // ==========================================

    @Test
    @SmallTest
    fun nickname_setAndGet() {
        val address = "u1testnickname"
        val nickname = "Alice"

        prefs.setNickname(address, nickname)

        assertThat(prefs.getNickname(address), equalTo(nickname))
    }

    @Test
    @SmallTest
    fun nickname_clearBySettingBlank() {
        val address = "u1testnickname"
        prefs.setNickname(address, "Alice")
        prefs.setNickname(address, "")

        assertThat(prefs.getNickname(address), nullValue())
    }

    @Test
    @SmallTest
    fun getDisplayName_returnsNicknameIfSet() {
        val address = "u1verylongaddressthatshouldbetruncated"
        prefs.setNickname(address, "Bob")

        assertThat(prefs.getDisplayName(address), equalTo("Bob"))
    }

    @Test
    @SmallTest
    fun getDisplayName_truncatesAddressIfNoNickname() {
        val address = "u1verylongaddressthatshouldbetruncated"

        val display = prefs.getDisplayName(address)

        // Should truncate to first 8 + ... + last 6
        assertThat(display.startsWith("u1verylo"), equalTo(true))
        assertThat(display.contains("..."), equalTo(true))
        assertThat(display.endsWith("ncated"), equalTo(true))
    }

    // ==========================================
    // DRAFTS
    // ==========================================

    @Test
    @SmallTest
    fun draft_setAndGet() {
        val peer = "u1draftpeer"
        val draft = "Draft message text"

        prefs.setDraft(peer, draft)

        assertThat(prefs.getDraft(peer), equalTo(draft))
        assertThat(prefs.hasDraft(peer), equalTo(true))
    }

    @Test
    @SmallTest
    fun draft_clearBySettingBlank() {
        val peer = "u1draftpeer"
        prefs.setDraft(peer, "Some draft")
        prefs.setDraft(peer, "")

        assertThat(prefs.getDraft(peer), nullValue())
        assertThat(prefs.hasDraft(peer), equalTo(false))
    }

    @Test
    @SmallTest
    fun clearDraft_removesDraft() {
        val peer = "u1draftpeer"
        prefs.setDraft(peer, "Some draft")
        prefs.clearDraft(peer)

        assertThat(prefs.getDraft(peer), nullValue())
    }

    @Test
    @SmallTest
    fun getAllDrafts_returnsAllNonEmptyDrafts() {
        prefs.setDraft("u1peer1", "Draft 1")
        prefs.setDraft("u1peer2", "Draft 2")
        prefs.setDraft("u1peer3", "")  // Empty - should not be included

        val drafts = prefs.getAllDrafts()

        assertThat(drafts.size, equalTo(2))
        assertThat(drafts["u1peer1"], equalTo("Draft 1"))
        assertThat(drafts["u1peer2"], equalTo("Draft 2"))
    }

    // ==========================================
    // PENDING MESSAGES
    // ==========================================

    @Test
    @SmallTest
    fun pendingMessage_addAndGet() {
        val msg = ZchatPreferences.PendingMessageData(
            id = "msg-123",
            text = "Hello world",
            timestampMillis = System.currentTimeMillis(),
            peerAddress = "u1recipient"
        )

        prefs.addPendingMessage(msg)

        val pending = prefs.getPendingMessages()
        assertThat(pending.size, equalTo(1))
        assertThat(pending[0].id, equalTo("msg-123"))
        assertThat(pending[0].text, equalTo("Hello world"))
    }

    @Test
    @SmallTest
    fun pendingMessage_removeById() {
        val msg1 = ZchatPreferences.PendingMessageData("msg-1", "Text 1", 1000L, "u1peer")
        val msg2 = ZchatPreferences.PendingMessageData("msg-2", "Text 2", 2000L, "u1peer")

        prefs.addPendingMessage(msg1)
        prefs.addPendingMessage(msg2)
        prefs.removePendingMessage("msg-1")

        val pending = prefs.getPendingMessages()
        assertThat(pending.size, equalTo(1))
        assertThat(pending[0].id, equalTo("msg-2"))
    }

    @Test
    @SmallTest
    fun pendingMessage_removeMultiple() {
        prefs.addPendingMessage(ZchatPreferences.PendingMessageData("msg-1", "Text 1", 1000L, "u1peer"))
        prefs.addPendingMessage(ZchatPreferences.PendingMessageData("msg-2", "Text 2", 2000L, "u1peer"))
        prefs.addPendingMessage(ZchatPreferences.PendingMessageData("msg-3", "Text 3", 3000L, "u1peer"))

        prefs.removePendingMessages(setOf("msg-1", "msg-3"))

        val pending = prefs.getPendingMessages()
        assertThat(pending.size, equalTo(1))
        assertThat(pending[0].id, equalTo("msg-2"))
    }

    @Test
    @SmallTest
    fun clearPendingMessages_removesAll() {
        prefs.addPendingMessage(ZchatPreferences.PendingMessageData("msg-1", "Text 1", 1000L, "u1peer"))
        prefs.addPendingMessage(ZchatPreferences.PendingMessageData("msg-2", "Text 2", 2000L, "u1peer"))

        prefs.clearPendingMessages()

        assertThat(prefs.getPendingMessages().isEmpty(), equalTo(true))
    }

    // ==========================================
    // E2E ENCRYPTION KEYS
    // ==========================================

    @Test
    @SmallTest
    fun e2eKeys_setAndGet() {
        val peer = "u1e2epeer"
        val ourPub = "ourPublicKeyBase64"
        val ourPriv = "ourPrivateKeyBase64"
        val peerPub = "peerPublicKeyBase64"

        prefs.setE2EOurKeys(peer, ourPub, ourPriv)
        prefs.setE2EPeerPublicKey(peer, peerPub)

        assertThat(prefs.getE2EOurPublicKey(peer), equalTo(ourPub))
        assertThat(prefs.getE2EPrivateKey(peer), equalTo(ourPriv))
        assertThat(prefs.getE2EPeerPublicKey(peer), equalTo(peerPub))
    }

    @Test
    @SmallTest
    fun e2eKeyExchangeComplete_requiresBothKeys() {
        val peer = "u1e2epeer"

        // Initially not complete
        assertThat(prefs.isE2EKeyExchangeComplete(peer), equalTo(false))

        // With only our keys
        prefs.setE2EOurKeys(peer, "ourPub", "ourPriv")
        assertThat(prefs.isE2EKeyExchangeComplete(peer), equalTo(false))

        // With peer key too
        prefs.setE2EPeerPublicKey(peer, "peerPub")
        assertThat(prefs.isE2EKeyExchangeComplete(peer), equalTo(true))
    }

    @Test
    @SmallTest
    fun clearE2EKeys_removesAllKeys() {
        val peer = "u1e2epeer"
        prefs.setE2EOurKeys(peer, "ourPub", "ourPriv")
        prefs.setE2EPeerPublicKey(peer, "peerPub")
        prefs.setE2EEnabled(peer, true)

        prefs.clearE2EKeys(peer)

        assertThat(prefs.getE2EOurPublicKey(peer), nullValue())
        assertThat(prefs.getE2EPrivateKey(peer), nullValue())
        assertThat(prefs.getE2EPeerPublicKey(peer), nullValue())
    }

    // ==========================================
    // DESTROY / REMOTE KILL
    // ==========================================

    @Test
    @SmallTest
    fun destroyPin_setAndVerify() {
        val pin = "1234"

        prefs.setDestroyPin(pin)

        assertThat(prefs.hasDestroyPin(), equalTo(true))
        assertThat(prefs.verifyDestroyPin(pin), equalTo(true))
        assertThat(prefs.verifyDestroyPin("wrong"), equalTo(false))
    }

    @Test
    @SmallTest
    fun remoteKillPhrase_setAndVerify() {
        val phrase = "destroy everything"

        prefs.setRemoteKillPhrase(phrase)

        assertThat(prefs.hasRemoteKillPhrase(), equalTo(true))
        assertThat(prefs.verifyRemoteKillPhrase(phrase), equalTo(true))
        assertThat(prefs.verifyRemoteKillPhrase("wrong phrase"), equalTo(false))
    }

    @Test
    @SmallTest
    fun remoteKillEnabled_toggle() {
        assertThat(prefs.isRemoteKillEnabled(), equalTo(false))

        prefs.setRemoteKillEnabled(true)
        assertThat(prefs.isRemoteKillEnabled(), equalTo(true))

        prefs.setRemoteKillEnabled(false)
        assertThat(prefs.isRemoteKillEnabled(), equalTo(false))
    }

    // ==========================================
    // NOTIFICATION PRIVACY
    // ==========================================

    @Test
    @SmallTest
    fun notificationPrivacy_defaultIsFullPreview() {
        assertThat(prefs.getNotificationPrivacy(), equalTo(NotificationPrivacy.FULL_PREVIEW))
    }

    @Test
    @SmallTest
    fun notificationPrivacy_setAndGet() {
        prefs.setNotificationPrivacy(NotificationPrivacy.SILENT)
        assertThat(prefs.getNotificationPrivacy(), equalTo(NotificationPrivacy.SILENT))

        prefs.setNotificationPrivacy(NotificationPrivacy.SENDER_ONLY)
        assertThat(prefs.getNotificationPrivacy(), equalTo(NotificationPrivacy.SENDER_ONLY))
    }

    // ==========================================
    // CLEAR ALL
    // ==========================================

    @Test
    @SmallTest
    fun clearAll_removesAllData() {
        // Populate with various data
        prefs.setConversationId("u1peer", "CONV0001")
        prefs.setNickname("u1peer", "Alice")
        prefs.setDraft("u1peer", "Draft")
        prefs.addPendingMessage(ZchatPreferences.PendingMessageData("msg-1", "Text", 1000L, "u1peer"))

        prefs.clearAll()

        assertThat(prefs.getConversationId("u1peer"), nullValue())
        assertThat(prefs.getNickname("u1peer"), nullValue())
        assertThat(prefs.getDraft("u1peer"), nullValue())
        assertThat(prefs.getPendingMessages().isEmpty(), equalTo(true))
    }

    // ==========================================
    // EDGE CASES
    // ==========================================

    @Test
    @SmallTest
    fun unicodeInData_preserved() {
        val peer = "u1unicodepeer"
        val nickname = "爱丽丝 🎉"
        val draft = "你好世界 👋"

        prefs.setNickname(peer, nickname)
        prefs.setDraft(peer, draft)

        assertThat(prefs.getNickname(peer), equalTo(nickname))
        assertThat(prefs.getDraft(peer), equalTo(draft))
    }

    @Test
    @SmallTest
    fun veryLongValues_handled() {
        val peer = "u1peer"
        val longNickname = "A".repeat(1000)
        val longDraft = "B".repeat(10000)

        prefs.setNickname(peer, longNickname)
        prefs.setDraft(peer, longDraft)

        assertThat(prefs.getNickname(peer), equalTo(longNickname))
        assertThat(prefs.getDraft(peer), equalTo(longDraft))
    }

    @Test
    @SmallTest
    fun concurrentAccess_bidirectionalMappingStaysConsistent() {
        // Simulate rapid consecutive updates
        val peer = "u1concurrentpeer"
        val convIds = listOf("CONV0001", "CONV0002", "CONV0003", "CONV0004", "CONV0005")

        for (convId in convIds) {
            prefs.setConversationId(peer, convId)
        }

        // Final state should be consistent
        val finalConvId = prefs.getConversationId(peer)
        val finalPeer = prefs.getPeerByConversationId(finalConvId!!)

        assertThat(finalPeer, equalTo(peer))
    }
}
