package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * ZC-634 (recheck of the review fixes, items 1 and 2; design 5.5 item 6): at c94569c8e the proof (a leg 1 answer carrying
 * the stored key) releases the claim mark BEFORE it stores the key. (1) When the user's "Mark as verified" lands between
 * the proof's read of the mark and its release, the release finds no mark, the store falls back to "the mark stood" and
 * writes it again, clearing the verified flag of a contact the user verified and the proof proved. (2) When the key's
 * commit is not saved the memo is ignored, but the mark and the relay-only ZBOOT it held are already gone.
 *
 * Wanted: the key is stored first with the mark as it stands at that moment, and only a saved commit releases the mark;
 * a tap that won the race keeps its verified flag. Item 3 (a guard, by design 5.5 item 2 b): an honest contact's key
 * exchange at a chat this phone began whose own key exchange never mined gets the mark; its ZBOOT is held and the banner
 * tells the user to compare the safety number and then tap "Mark as verified". Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProofNeverUndoesTheTapTest {
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
    fun `(1) a Mark as verified that lands while the proof is handled keeps its verified flag and no mark comes back`() =
        runBlocking {
            val p = provable(seed = 63500)
            // The user's tap, landing between the proof's read of the mark and its release.
            p.maps.beforeRelease = {
                p.maps.claims.remove(X)
                p.maps.held.remove(X)
                p.maps.verified += X
            }
            p.fixture.received("zc634-r1-ack", ackFrom(X, p.theirs, p.ownId), minedAt = NEW + 12)
            p.fixture.pass()

            assertTrue("the proof cleared the verified flag the user had just set", X in p.maps.verified)
            assertNull("the proof wrote the mark again after the user's tap", p.maps.claims[X])
        }

    @Test
    fun `(2) a proof whose key commit is not saved leaves the mark and its held ZBOOT`() =
        runBlocking {
            val p = provable(seed = 63501)
            p.maps.refuseKeyCommits = true
            p.fixture.received("zc634-r2-ack", ackFrom(X, p.theirs, p.ownId), minedAt = NEW + 12)
            p.fixture.pass()

            assertEquals("the mark went although the proof's commit was not saved", p.theirs.publicKey, p.maps.claims[X])
            assertEquals("the held ZBOOT went although the proof's commit was not saved", p.held, p.maps.held[X])
        }

    @Test
    fun `(3) guard - an honest contact's exchange at a chat whose own exchange never mined is held, and the banner names the way out`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 63502), firstContact = true)
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()
            handleKexMemo(vm, kexFrom(chat.peer, contact, "ZC634R31"), "synthetic-zc634-r3-kex")
            awaitIdle(vm)
            vm.acceptInboundKexRequest(chat.peer)
            awaitIdle(vm)
            chat.route(vm, v3Boot(contact, relayKey(84)))
            awaitIdle(vm)

            assertTrue("the held-connection banner is not shown for the held ZBOOT", vm.isKeyClaimHeld(chat.peer))
            val body = ChatResourceText.strings("values")["chat_key_unconfirmed_held_body"].orEmpty()
            assertTrue("the banner does not send the user to the safety number", "Verify safety number" in body)
            assertTrue("the banner does not name the tap that connects the chat", "Mark as verified" in body)
            vm.markE2EVerified(chat.peer)
            awaitIdle(vm)
            assertEquals("after the tap the held ZBOOT binds", relayKey(84), chat.maps.nostr[chat.peer])
        }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private class Provable(
        val fixture: HandshakePassFixture,
        val maps: ClaimMarkMaps,
        val theirs: E2EKeyPair,
        val ownId: String,
        val held: String,
    )

    /** X's key stored with a mark, our own mined key exchange to X under our own id, and X's ZBOOT held by the mark. */
    private suspend fun provable(seed: Int): Provable {
        val f = HandshakePassFixture(scheduler, X)
        val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
        f.extra = { method, args -> maps.handle(method, args) }
        val (forX, _) =
            f.ids.store
                .getOrCreateConversationIdForMessage(X)
        f.open()
        f.ownKex("zc634-r-own-kex-$seed", X, forX, minedAt = NEW + 10)
        val theirs = E2EEncryption.generateKeyPair()
        f.prefs.e2eEnabled += X
        f.prefs.e2ePeerKey[X] = theirs.publicKey
        maps.claims[X] = theirs.publicKey
        maps.modes[X] = ConversationMode.TUNNEL
        val raw = v3Boot(theirs, relayKey(seed - 63400), convId = forX)
        route(f, raw)
        assertEquals("fixture: the ZBOOT is held", raw, maps.held[X])
        return Provable(f, maps, theirs, forX, raw)
    }

    private fun route(
        f: HandshakePassFixture,
        raw: String,
    ) {
        val method =
            ChatViewModel::class.java.declaredMethods
                .first { it.name == "routeIncomingBoot" && it.parameterCount == 3 && it.parameterTypes[2] == java.lang.Boolean.TYPE }
                .apply { isAccessible = true }
        try {
            method.invoke(f.vm, X, raw, false)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun ackFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            sender,
            E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey),
        )

    private companion object {
        val X = SyntheticUnifiedAddress.mainnet(seed = 63503)
    }
}
