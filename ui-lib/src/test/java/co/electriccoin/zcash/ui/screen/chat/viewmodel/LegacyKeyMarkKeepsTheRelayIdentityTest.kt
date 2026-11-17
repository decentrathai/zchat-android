package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexWithRelayFields
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.nostr
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v4Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W-72 (register ZC-847), SPEC_F_20261019 rules R3 and R7, RED test 12: a contact key stored before this build (the
 * pre-update mark stands) may have been planted by a stranger while the phone ran 2.12.4, so it must not move this chat's
 * relay identity. While the mark stands a v2/v3 ZBOOT signed with it that would bind where no relay key is bound, or announce
 * another relay key than the bound one, is refused unless the bound relay key sealed it: not recorded, no key-changed flag,
 * no note, no flush of waiting Tunnel messages, and kept as the mark's held ZBOOT. A sealed ZBOOT passes and leaves the mark
 * (the bound relay key may itself be the plant's), and so does an unsealed one that announces the bound key. The relay
 * fields of a key exchange carrying the marked key are not bound. v4 works as today (the downgrade guard refuses it for an
 * address with an E2E key). "Mark as verified" removes the mark and sets the verified flag in one commit, then hands the held
 * ZBOOT on (its reply over the relays only): it binds, and nothing is paid in any chat mode.
 *
 * Today no ZBOOT is ever held for a pre-update key: an unsealed ZBOOT signed with it moves the relay identity. Every
 * address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyKeyMarkKeepsTheRelayIdentityTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(12a) an unsealed ZBOOT announcing a new relay key is held - no flag, no note, no flush, not recorded`() =
        runBlocking {
            val chat = legacyChat(seed = 72121, mode = ConversationMode.TUNNEL, bound = relayKey(71))
            chat.queueTunnelMessage("synthetic waiting message")
            val vm = chat.viewModel()
            val raw = v3Boot(chat.contact, relayKey(72))

            assertFalse("the refused ZBOOT reported a connection", chat.route(vm, raw))
            awaitIdle(vm)

            assertEquals("the pre-update key's ZBOOT moved the relay identity", relayKey(71), chat.maps.nostr[chat.peer])
            assertNull("the announced key routes to this chat", chat.store.findPeerByNostrPubkey(relayKey(72)))
            assertFalse("a refused move raised the key-changed flag", chat.peer in chat.maps.keyChanged)
            assertFalse("a refused move showed the rotated note", SysNotes.rotationNoteId(chat.peer) in chat.maps.rows)
            assertTrue("the refused ZBOOT was recorded as processed", chat.maps.bootSigs.isEmpty())
            assertTrue("the waiting Tunnel message was flushed to the announced key", chat.tunnelMessageWaits())
            assertEquals("the refused ZBOOT is not the mark's held ZBOOT", raw, chat.maps.legacyHeld[chat.peer])
            assertEquals("the mark went", chat.contact.publicKey, chat.maps.legacy[chat.peer])
            assertEquals("a refused ZBOOT paid", 0, chat.proposals())
        }

    @Test
    fun `(12b) with no relay key bound an unsealed ZBOOT binds nothing and is held`() =
        runBlocking {
            val chat = legacyChat(seed = 72122, mode = ConversationMode.OPEN, bound = null)
            val vm = chat.viewModel()
            val raw = v3Boot(chat.contact, relayKey(73))

            chat.route(vm, raw)
            awaitIdle(vm)

            assertTrue("a pre-update key's ZBOOT bound a relay identity: ${chat.maps.binds}", chat.maps.binds.isEmpty())
            assertEquals("the refused ZBOOT is not held", raw, chat.maps.legacyHeld[chat.peer])
        }

    @Test
    fun `(12c) a sealed ZBOOT passes and the mark stays, also when it announces another key`() =
        runBlocking {
            val chat = legacyChat(seed = 72123, mode = ConversationMode.TUNNEL, bound = relayKey(74))
            val vm = chat.viewModel()

            assertTrue(
                "the sealed ZBOOT announcing the bound key was refused",
                chat.route(vm, v3Boot(chat.contact, relayKey(74)), sealed = true)
            )
            assertEquals(
                "a sealed ZBOOT took the mark away (the bound key may be the plant's)",
                chat.contact.publicKey,
                chat.maps.legacy[chat.peer]
            )

            assertTrue("a sealed rotation was refused", chat.route(vm, v3Boot(chat.contact, relayKey(75), epoch = 2L), sealed = true))
            assertEquals(relayKey(75), chat.maps.nostr[chat.peer])
            assertEquals("a sealed rotation took the mark away", chat.contact.publicKey, chat.maps.legacy[chat.peer])
            assertNull("a sealed ZBOOT was held", chat.maps.legacyHeld[chat.peer])
        }

    @Test
    fun `(12d) an unsealed ZBOOT announcing the bound key passes and the mark stays`() =
        runBlocking {
            val chat = legacyChat(seed = 72124, mode = ConversationMode.TUNNEL, bound = relayKey(76))
            val vm = chat.viewModel()

            assertTrue("a ZBOOT that moves nothing was refused", chat.route(vm, v3Boot(chat.contact, relayKey(76))))
            assertEquals(chat.contact.publicKey, chat.maps.legacy[chat.peer])
            assertNull(chat.maps.legacyHeld[chat.peer])
        }

    @Test
    fun `(12e) v4 works as today - the downgrade guard refuses it at an address with an E2E key, mark or not`() =
        runBlocking {
            val chat = legacyChat(seed = 72125, mode = ConversationMode.OPEN, bound = relayKey(77))
            val vm = chat.viewModel()

            assertFalse(
                "a v4 rotation passed the downgrade guard at a marked address",
                chat.route(vm, v4Boot(nostr(77), relayKey(78)), sealed = true)
            )
            assertEquals(relayKey(77), chat.maps.nostr[chat.peer])
        }

    @Test
    fun `(12f) the relay fields of a key exchange carrying the marked key are not bound`() =
        runBlocking {
            val chat = legacyChat(seed = 72126, mode = ConversationMode.TUNNEL, bound = null)
            // Answered long ago: no automatic answer is owed for this key.
            chat.prefs.sentKexAck[chat.peer] = chat.contact.publicKey
            val vm = chat.viewModel()

            handleKexMemo(vm, kexWithRelayFields(chat.peer, chat.contact, "ZC72G601", relayKey(79)), "synthetic-w72-12f-kex")
            awaitIdle(vm)

            assertEquals("fixture: the key is unchanged", chat.contact.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertTrue("the relay fields of a key exchange were bound at a marked address: ${chat.maps.binds}", chat.maps.binds.isEmpty())
            assertNull(chat.maps.nostr[chat.peer])
            assertEquals("the repeat took the mark away", chat.contact.publicKey, chat.maps.legacy[chat.peer])
        }

    @Test
    fun `(12g) Open - Mark as verified removes the mark, sets the flag, binds the held ZBOOT, 0 proposals`() =
        markAsVerified(ConversationMode.OPEN)

    @Test
    fun `(12g) Tunnel - Mark as verified removes the mark, sets the flag, binds the held ZBOOT, 0 proposals`() =
        markAsVerified(ConversationMode.TUNNEL)

    @Test
    fun `(12g) Shielded - Mark as verified removes the mark, sets the flag, binds the held ZBOOT, 0 proposals`() =
        markAsVerified(ConversationMode.VAULT)

    private fun markAsVerified(mode: ConversationMode) =
        runBlocking {
            for (acks in listOf(0, 1)) {
                NostrChatBridge.unregisterPublisher()
                ClaimMarkChat.clearTunnelQueue()
                val chat = legacyChat(seed = 72130 + mode.ordinal * 2 + acks, mode = mode, bound = null)
                if (mode == ConversationMode.VAULT) chat.maps.zbootQuoted += chat.peer
                chat.relays(acks)
                val vm = chat.viewModel()
                val announced = relayKey(80 + acks)
                chat.route(vm, v3Boot(chat.contact, announced))
                awaitIdle(vm)
                val label = "$mode, relays acknowledging $acks"
                assertNotNull("$label: fixture: the ZBOOT is held", chat.maps.legacyHeld[chat.peer])
                assertTrue("$label: before the tap a relay key was bound: ${chat.maps.binds}", chat.maps.binds.isEmpty())

                vm.markE2EVerified(chat.peer)
                awaitIdle(vm)

                assertNull("$label: the tap left the pre-update mark", chat.maps.legacy[chat.peer])
                assertTrue("$label: the tap did not set the verified flag", chat.peer in chat.maps.verified)
                assertEquals("$label: the held ZBOOT was not handed on", announced, chat.maps.nostr[chat.peer])
                assertEquals("$label: the tap paid", 0, chat.proposals())
            }
        }

    @Test
    fun `(12h) Mark as verified with no held ZBOOT removes the mark and binds nothing`() =
        runBlocking {
            val chat = legacyChat(seed = 72140, mode = ConversationMode.TUNNEL, bound = relayKey(82))
            val vm = chat.viewModel()

            vm.markE2EVerified(chat.peer)
            awaitIdle(vm)

            assertNull("the tap left the pre-update mark", chat.maps.legacy[chat.peer])
            assertTrue(chat.peer in chat.maps.verified)
            assertTrue("the tap bound something: ${chat.maps.binds}", chat.maps.binds.isEmpty())
            assertEquals(0, chat.proposals())
        }

    /** A chat whose contact key [LegacyChat.contact] carries the pre-update mark, our key pair made, [bound] bound. */
    private fun legacyChat(
        seed: Int,
        mode: ConversationMode,
        bound: String?,
    ): LegacyChat {
        val chat = LegacyChat(SyntheticUnifiedAddress.mainnet(seed = seed))
        chat.prefs.e2eEnabled += chat.peer
        chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
        chat.prefs.e2ePeerKey[chat.peer] = chat.contact.publicKey
        chat.maps.legacy[chat.peer] = chat.contact.publicKey
        chat.maps.modes[chat.peer] = mode
        if (bound != null) chat.maps.nostr[chat.peer] = bound
        return chat
    }

    private class LegacyChat(
        peer: String,
    ) {
        val contact: E2EKeyPair = E2EEncryption.generateKeyPair()
        private val chat = ClaimMarkChat(peer, firstContact = true)
        val peer get() = chat.peer
        val prefs get() = chat.prefs
        val maps get() = chat.maps
        val store get() = chat.store

        fun relays(acks: Int) = chat.relays(acks)

        fun queueTunnelMessage(text: String) = chat.queueTunnelMessage(text)

        fun tunnelMessageWaits() = chat.tunnelMessageWaits()

        suspend fun viewModel() = chat.viewModel()

        fun route(
            vm: ChatViewModel,
            raw: String,
            sealed: Boolean = false,
        ) = chat.route(vm, raw, sealed)

        fun proposals() = chat.proposals()
    }
}
