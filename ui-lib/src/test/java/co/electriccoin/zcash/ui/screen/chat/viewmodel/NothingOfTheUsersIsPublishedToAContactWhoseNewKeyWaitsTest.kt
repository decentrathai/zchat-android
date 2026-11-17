package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Files

/**
 * T7BH-N1 (new register item, medium; T7B-HANDSHAKE-SPEC rev 2, section 2.2) — while a contact's "Security key changed"
 * warning is up, nothing of the user's is published to that contact over the relays, free or not:
 * - (a) the avatar pass skips the contact, and it stays owed (its delivered version is not set);
 * - (b) a stored waiting Tunnel message with the contact's relay key known: a new view model's flush publishes nothing,
 *   the queue is not claimed and the row still waits; after the decision the same flush publishes it once;
 * - (c) the same queue when the contact's verified ZBOOT arrives with a remembered refused key (the flag stays): nothing
 *   is published;
 * - (d) the status broadcast's contact list, which is also the count its question shows, leaves the contact out.
 * The free signed rotation announce is not in this list (batch ZC-739 lets it go on purpose).
 *
 * A [KeyWaitWorld] phone in a Tunnel chat with a recording relay publisher that acknowledges every publish. Controls: a
 * second contact with no warning still gets the avatar and stays in the status list. Every key and address is synthetic.
 */
class NothingOfTheUsersIsPublishedToAContactWhoseNewKeyWaitsTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        LedgerTests.setUp(sendScopes)
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
        tunnelQueue().clear()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        tunnelQueue().clear()
        LedgerTests.tearDown(sendScopes)
    }

    private fun flaggedTunnel(): KeyWaitWorld =
        KeyWaitWorld(LedgerWorld(mode = ConversationMode.TUNNEL, exchangeComplete = true, consent = true)).also {
            it.flagged += it.w.peer
            it.acks = 1
            it.registerPublisher()
        }

    // ---- (a) the avatar --------------------------------------------------------------------------------------------

    /** A files folder in a temp directory; the avatar store's encrypted index is replaced by an in-memory file. */
    private class AvatarContext : ContextWrapper(null) {
        val dir: File = Files.createTempDirectory("zchat-n1-avatars").toFile().apply { deleteOnExit() }

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = dir
    }

    /** An avatar store whose own photo was removed at [version] (a removal tombstone is what the pass sends). */
    private fun removedAvatar(version: Long): AvatarStore {
        val prefs = MemorySharedPreferences()
        val store = AvatarStore(AvatarContext())
        store.javaClass
            .getDeclaredField("prefs\$delegate")
            .apply { isAccessible = true }
            .set(store, lazyOf<SharedPreferences>(prefs))
        prefs.edit().putLong("self_removed_at", version).commit()
        return store
    }

    @Test
    fun `(a) the avatar pass skips a contact whose new key waits and it stays owed`() =
        runBlocking {
            val k = flaggedTunnel()
            val other = "usyntheticn1avatarother00001"
            k.w.base.session(other, complete = true, mode = ConversationMode.TUNNEL)
            val flaggedRelay = KeyWaitWorld.relayKey(11)
            val otherRelay = KeyWaitWorld.relayKey(12)
            k.w.prefs.peerNostr[k.w.peer] = flaggedRelay
            k.w.prefs.peerNostr[other] = otherRelay
            val vm = k.openVm()
            val avatars = removedAvatar(VERSION)
            AutoSpendVm.setField(vm, "avatarStore", avatars)

            KeyAcceptApi.callSuspend(vm, "deliverSelfAvatar", null)

            assertEquals("fixture: the contact with no warning did not get the avatar", 1, k.publishesTo(otherRelay).size)
            assertEquals("the avatar was published to a contact whose new key waits", 0, k.publishesTo(flaggedRelay).size)
            assertTrue("the held contact is no longer owed the avatar", avatars.getDeliveredSelfVersion(k.w.peer) != VERSION)
        }

    // ---- (b), (c) the queued Tunnel messages ----------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun tunnelQueue(): MutableMap<String, MutableList<Any>> =
        ChatViewModel::class.java.declaredFields
            .first { it.name == "tunnelPendingPayloads" && Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }
            .get(null) as MutableMap<String, MutableList<Any>>

    /** A waiting Tunnel message of [k]'s contact: its row on disk and its payload in the process-wide queue. */
    private fun queueWaitingMessage(k: KeyWaitWorld): String {
        val id = "tunnel-wait-n1-${System.nanoTime()}"
        k.w.prefs.rows[id] =
            ZchatPreferences.PendingMessageData(
                id = id,
                text = WAITING_TEXT,
                timestampMillis = System.currentTimeMillis(),
                peerAddress = k.w.peer,
            )
        val payloadClass =
            ChatViewModel::class.java.declaredClasses
                .flatMap { listOf(it) + it.declaredClasses }
                .first { it.simpleName == "QueuedTunnelPayload" }
        val payload =
            payloadClass.declaredConstructors
                .first { it.parameterCount == 2 }
                .apply { isAccessible = true }
                .newInstance(id, WAITING_TEXT)
        tunnelQueue()[k.w.peer] = mutableListOf(payload)
        return id
    }

    private fun flush(
        vm: ChatViewModel,
        peer: String,
        relayKey: String,
    ) {
        try {
            ChatViewModel::class.java
                .getDeclaredMethod("flushTunnelPendingPayloads", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(vm, peer, relayKey)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun rows(k: KeyWaitWorld): Map<String, ZchatPreferences.PendingMessageData> = k.w.prefs.rows

    private fun publishedText(
        k: KeyWaitWorld,
        relayKey: String,
    ): Int = k.publishesTo(relayKey).count { WAITING_TEXT in it }

    @Test
    fun `(b) a waiting Tunnel message is not published by a new view model while the new key waits, and once after the decision`() =
        runBlocking {
            val k = flaggedTunnel()
            val relayKey = KeyWaitWorld.relayKey(21)
            k.w.prefs.peerNostr[k.w.peer] = relayKey
            val id = queueWaitingMessage(k)
            val vm = k.openVm()

            flush(vm, k.w.peer, relayKey)
            awaitIdle(vm)

            assertEquals("a waiting message was published to a contact whose new key waits", 0, publishedText(k, relayKey))
            assertTrue("the held message left the queue (claimed while held)", tunnelQueue()[k.w.peer]?.isNotEmpty() == true)
            assertTrue("the held message's row is gone", rows(k).containsKey(id))

            k.flagged -= k.w.peer
            flush(vm, k.w.peer, relayKey)
            awaitIdle(vm)
            flush(vm, k.w.peer, relayKey)
            awaitIdle(vm)

            assertEquals("after the decision the waiting message was not published exactly once", 1, publishedText(k, relayKey))
            assertFalse("after the decision the waiting row stayed", rows(k).containsKey(id))
        }

    @Test
    fun `(c) the contact's verified ZBOOT with a remembered refused key publishes no waiting message`() =
        runBlocking {
            val k = flaggedTunnel()
            k.recordKeepsFlag = true
            val contact = E2EEncryption.generateKeyPair()
            k.w.base.peerKeys[k.w.peer] = contact.publicKey
            val relayKey = KeyWaitWorld.relayKey(31)
            queueWaitingMessage(k)
            val vm = k.openVm()

            val taken = k.routeIncomingBoot(vm, k.w.peer, k.contactZboot(k.w.peer, contact, relayKey))
            awaitIdle(vm)

            assertTrue("fixture: the contact's verified ZBOOT was not taken", taken)
            assertTrue("fixture: the warning went down", k.w.peer in k.flagged)
            assertEquals("a waiting message was published while the new key waits", 0, publishedText(k, relayKey))
            assertTrue("the held message left the queue", tunnelQueue()[k.w.peer]?.isNotEmpty() == true)
        }

    // ---- (d) the status broadcast's list and count -------------------------------------------------------------------

    @Test
    fun `(d) the status broadcast leaves a contact whose new key waits out of its list and its count`() =
        runBlocking {
            val k = flaggedTunnel()
            val other = "usyntheticn1statusother00001"
            k.w.base.session(other, complete = true, mode = ConversationMode.TUNNEL)
            val vm = k.openVm()
            AutoSpendVm.setField(
                vm,
                "_chatListState",
                MutableStateFlow<ChatListState>(
                    ChatListState.Success(
                        conversations =
                            listOf(k.w.peer, other).map { Conversation(peerAddress = it, messages = emptyList(), lastMessage = null) },
                        currentUserAddress = HandshakeHarness.SELF,
                        privacyStatus = PrivacyStatus.DEFAULT,
                    ),
                ),
            )

            val contacts =
                ChatViewModel::class.java
                    .getDeclaredMethod("statusContacts")
                    .apply { isAccessible = true }
                    .invoke(vm) as List<*>
            val addresses = contacts.map { it!!.javaClass.getMethod("getAddress").invoke(it) as String }

            assertTrue("fixture: the contact with no warning is not in the status list", other in addresses)
            assertFalse("the status broadcast counts and sends to a contact whose new key waits", k.w.peer in addresses)
        }

    private companion object {
        const val VERSION = 1_760_000_000_000L
        const val WAITING_TEXT = "synthetic waiting tunnel text n1"
    }
}
