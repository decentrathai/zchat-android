package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Round 9 R9-2 (REVIEW_R8 F12, device D-F8, LEDGER row 9): "Allow handshake transactions" releases nothing until the
 * chat is opened again.
 *
 * A contact's KEX reached us while the user had not allowed automatic chat setups, so our KEXACK (the reply that
 * carries our key) was held and the consent question was raised. grantHandshakeSpendConsent saves the consent and
 * clears the queued setups, and sends nothing: the queued KEXACK waits for retryKexAckIfResponder, which only a chat
 * open runs. On the phone the KEXACK went 6.5 minutes after Allow, when the chat was opened.
 *
 * Must hold: with one KEXACK queued for the consent and no chat open, Allow pays exactly that one KEXACK. Control: an
 * Allow whose consent could not be saved pays nothing (Round 8 D10).
 *
 * Drives the real ChatViewModel through the real use case and repository ([HandshakeHarness]); the chat screen that
 * queued the KEXACK is cleared before the Allow, which another screen (the chat list) answers. All keys are generated
 * per run and every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentGrantReleasesQueuedKexAckTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `Allow pays the queued KEXACK with no chat open`() =
        runBlocking {
            val peer = "usyntheticconsentkexack00001"
            val prefs = responderPrefs(peer)
            val seam = HandshakeSeam(peer)
            queueKexAckForConsent(prefs.proxy, seam, peer)

            val list = chatViewModelWith(prefs.proxy, seam)
            list.grantHandshakeSpendConsent()
            awaitIdle(list)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "Allow saved the consent but sent nothing: the queued KEXACK waited for the chat to be opened again",
                1,
                seam.proposalRequests.get(),
            )
            assertEquals(1, seam.broadcasts.size)
            assertEquals(prefs.e2ePeerKey[peer], prefs.sentKexAck[peer])

            // Opening the chat afterwards pays nothing more.
            val opened = chatViewModelWith(prefs.proxy, seam)
            opened.retryKexAckIfResponder(peer)
            awaitIdle(opened)
            assertEquals("the KEXACK was paid twice", 1, seam.proposalRequests.get())
        }

    /** Control, green before and after: an Allow whose consent could not be saved releases nothing. */
    @Test
    fun `control - an Allow whose consent was not saved pays nothing`() =
        runBlocking {
            val peer = "usyntheticconsentkexack00002"
            val prefs = responderPrefs(peer)
            val seam = HandshakeSeam(peer)
            queueKexAckForConsent(prefs.proxy, seam, peer)

            val list = chatViewModelWith(consentNeverSaved(prefs.proxy), seam)
            list.grantHandshakeSpendConsent()
            awaitIdle(list)

            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, seam.broadcasts.size)
            assertTrue(HandshakeHarness.sendMessageState(list) is SendMessageState.Error)
        }

    // --------------------------------------------------------------- harness

    /** We received [peer]'s first KEX (we are the responder) and have not allowed automatic chat setups yet. */
    private fun responderPrefs(peer: String): HandshakePrefs {
        val peerKeys = E2EEncryption.generateKeyPair()
        return HandshakePrefs(peer, consent = false).apply {
            e2ePeerKey[peer] = peerKeys.publicKey
            receivedKex[peer] = peerKeys.publicKey
        }
    }

    /** The chat screen's open retries the KEXACK, which is queued for the consent; then the user leaves the chat. */
    private suspend fun queueKexAckForConsent(
        store: ZchatPreferences,
        seam: HandshakeSeam,
        peer: String,
    ) {
        val chat = chatViewModelWith(store, seam)
        chat.retryKexAckIfResponder(peer)
        awaitIdle(chat)
        assertTrue(
            "fixture sanity: the KEXACK waits for the consent",
            HandshakeHarness.sendMessageState(chat) is SendMessageState.NeedsHandshakeSpendConsent,
        )
        assertEquals("fixture sanity: nothing paid yet", 0, seam.proposalRequests.get())
        chat.viewModelScope.coroutineContext.job
            .cancelAndJoin()
    }

    /** [delegate] whose handshake-spend consent is never stored: granting it answers false and keeps nothing. */
    private fun consentNeverSaved(delegate: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "setConsentedToHandshakeSpend") {
                false
            } else {
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences
}
