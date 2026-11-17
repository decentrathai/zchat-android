package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * ZC-1097 (register, low; C2 merge review finding 4) — clearLegacyMarkByAnswer (the contact answered our probe) releases
 * the pre-update mark and gets back the ZBOOT it held, sees a claim mark and hands the ZBOOT to it with holdClaimedBoot.
 * It holds no peerKeyMonitor, so "Mark as verified" (or the proof path) can release the claim between the check and the
 * hold: holdClaimedBoot then returns false, the result is ignored and the ZBOOT is dropped. One that came over the relays
 * is not read again.
 *
 * Must hold: when the hold is refused because no claim stands any more, the ZBOOT is handed on as with no claim (bound,
 * relays-only reply); while the claim stands it keeps the ZBOOT for its own release. Nothing is paid. Every address, key
 * and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyMarkAnswerKeepsTheZbootWhenTheClaimGoesTest {
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

    /** A chat whose contact key carries both marks, the pre-update mark holding a ZBOOT announcing relayKey([seed]). */
    private fun bothMarks(
        address: Int,
        seed: Int,
    ): ClaimMarkChat {
        val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = address), firstContact = true)
        val contact = E2EEncryption.generateKeyPair()
        chat.prefs.e2eEnabled += chat.peer
        chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
        chat.prefs.e2ePeerKey[chat.peer] = contact.publicKey
        chat.maps.legacy[chat.peer] = contact.publicKey
        chat.maps.legacyHeld[chat.peer] = v3Boot(contact, relayKey(seed))
        chat.maps.claims[chat.peer] = contact.publicKey
        chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
        return chat
    }

    private fun clearByAnswer(
        vm: ChatViewModel,
        peer: String,
    ) {
        val method = ChatViewModel::class.java.getDeclaredMethod("clearLegacyMarkByAnswer", String::class.java)
        method.isAccessible = true
        try {
            method.invoke(vm, peer)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `a claim released between the check and the hold does not drop the ZBOOT the pre-update mark held`() =
        runBlocking {
            val chat = bothMarks(address = 10971, seed = 71)
            val vm = chat.viewModel()
            // "Mark as verified" releases the claim (with what it held: nothing) between the claim check and the hold.
            chat.maps.beforeHold = {
                chat.maps.claims.remove(chat.peer)
                chat.maps.held.remove(chat.peer)
                chat.maps.verified += chat.peer
            }

            clearByAnswer(vm, chat.peer)
            awaitIdle(vm)

            assertNull("the answer left the pre-update mark", chat.maps.legacy[chat.peer])
            assertEquals("the ZBOOT the pre-update mark held was dropped", relayKey(71), chat.maps.nostr[chat.peer])
            assertEquals("the answer paid", 0, chat.proposals())
        }

    @Test
    fun `while the claim stands it keeps the ZBOOT for its own release`() =
        runBlocking {
            val chat = bothMarks(address = 10972, seed = 72)
            val boot = chat.maps.legacyHeld[chat.peer]
            val vm = chat.viewModel()

            clearByAnswer(vm, chat.peer)
            awaitIdle(vm)

            assertNull("the answer left the pre-update mark", chat.maps.legacy[chat.peer])
            assertEquals("the claim does not hold the ZBOOT the pre-update mark held", boot, chat.maps.held[chat.peer])
            assertNull("the ZBOOT was bound although a claim stands", chat.maps.nostr[chat.peer])
            assertEquals("the answer paid", 0, chat.proposals())
        }
}
