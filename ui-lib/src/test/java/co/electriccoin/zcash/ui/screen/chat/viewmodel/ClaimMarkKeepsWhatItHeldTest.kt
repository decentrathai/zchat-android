package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
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
import org.junit.Before
import org.junit.Test

/**
 * ZC-1070 (cross-family review of ZC-634 part B, items a and c): (a) at b5e7421a5 a key exchange whose contact-key commit
 * is not saved removes the W-62 hold it had just set, although a commit that is not saved may still sit in the store's
 * memory; the key could then be answered with no tap. (c) A ZBOOT is checked against the claim mark and then held in a
 * second call: when the user's "Mark as verified" removes the mark in between, the hold finds no mark and the ZBOOT is
 * dropped (a relay-only one is never delivered again).
 *
 * Wanted: (a) the hold stays when the key's commit is not saved; (c) a ZBOOT whose mark went in between is read on as an
 * unmarked contact's and binds by the ordinary rules. Item (b) of the review (Accept of a leg 1 or 3 answer drops the held
 * ZBOOT) is not reachable: the accept's wipe removes the mark with its held ZBOOT before its adoption runs, and that
 * ZBOOT was signed by the key the accept replaces. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimMarkKeepsWhatItHeldTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(a) a key exchange whose key commit is not saved keeps the W-62 hold it set`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 63510), firstContact = true)
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
            chat.maps.refuseKeyCommits = true
            val vm = chat.viewModel()
            val stranger = E2EEncryption.generateKeyPair()

            handleKexMemo(vm, kexFrom(chat.peer, stranger, "ZC1070A1"), "synthetic-zc1070-a-kex")
            awaitIdle(vm)

            assertEquals("the hold went with the commit that was not saved", stranger.publicKey, chat.prefs.awaitingAccept[chat.peer])
            assertEquals("an answer was paid", 0, chat.proposals())
        }

    @Test
    fun `(c) a ZBOOT whose mark the user's tap removed between the check and the hold binds and is not lost`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 63511), firstContact = true)
            val contact = E2EEncryption.generateKeyPair()
            chat.prefs.e2eEnabled += chat.peer
            chat.prefs.ourKeyStore[chat.peer] = chat.prefs.ourKeys.publicKey to chat.prefs.ourKeys.privateKey
            chat.prefs.e2ePeerKey[chat.peer] = contact.publicKey
            chat.maps.claims[chat.peer] = contact.publicKey
            chat.maps.modes[chat.peer] = ConversationMode.TUNNEL
            val vm = chat.viewModel()
            // The user's tap, landing between the ZBOOT's mark check and its hold.
            chat.maps.beforeHold = {
                chat.maps.claims.remove(chat.peer)
                chat.maps.held.remove(chat.peer)
                chat.maps.verified += chat.peer
            }

            chat.route(vm, v3Boot(contact, relayKey(85)))
            awaitIdle(vm)

            assertEquals("the ZBOOT was dropped although its mark had gone", relayKey(85), chat.maps.nostr[chat.peer])
        }
}
