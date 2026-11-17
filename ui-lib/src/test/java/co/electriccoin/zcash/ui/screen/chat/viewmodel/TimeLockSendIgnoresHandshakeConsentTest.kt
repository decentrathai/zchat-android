package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * HS-7 — scheduled and locked messages fail with a false "add ZEC" error when handshake consent was never
 * granted.
 *
 * The four time-lock senders reuse the handshake's block-aware retry so a "still confirming" note lock
 * resolves itself. At 8134b88d3 that helper also carries the AUTOMATIC-handshake consent gate, so a user who
 * never granted it taps Send on a scheduled message, the gate refuses, and the sender overwrites the consent
 * prompt with "Couldn't send the locked message yet ... or you need a little more ZEC". Nothing is sent and
 * no prompt appears. A time-lock is a user gesture: it must not pass through the automatic-spend consent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeLockSendIgnoresHandshakeConsentTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a scheduled message is sent when handshake consent was never granted`() =
        assertSentWithoutConsent("usynthetictimelockscheduled1") { vm, peer ->
            vm.sendScheduledMessage(peer, "see you tomorrow", unlockTimestamp = 4_102_444_800L)
        }

    @Test
    fun `a block-locked message is sent when handshake consent was never granted`() =
        assertSentWithoutConsent("usynthetictimelockblock00001") { vm, peer ->
            vm.sendBlockLockedMessage(peer, "after the upgrade", unlockHeight = 3_600_000L)
        }

    @Test
    fun `a payment-locked message is sent when handshake consent was never granted`() =
        assertSentWithoutConsent("usynthetictimelockpayment001") { vm, peer ->
            vm.sendPaymentLockedMessage(peer, "the answer", requiredZatoshi = 50_000L)
        }

    @Test
    fun `a conditional message is sent when handshake consent was never granted`() =
        assertSentWithoutConsent("usynthetictimelockcondition1") { vm, peer ->
            vm.sendConditionalMessage(peer, "the answer", answer = "blue", hint = "sky")
        }

    private fun assertSentWithoutConsent(
        peer: String,
        send: (ChatViewModel, String) -> Unit,
    ) = runBlocking {
        val prefs = HandshakePrefs(peer, consent = false)
        val seam = HandshakeSeam(peer)
        val vm = handshakeViewModel(prefs, seam)

        send(vm, peer)
        awaitIdle(vm)

        assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "the user's time-locked message was refused by the automatic-handshake consent gate and shown " +
                "as a false 'add ZEC' error (state=$state). A user-initiated send must not need that consent.",
            1,
            seam.broadcasts.size,
        )
        assertEquals(SendMessageState.Idle, state)
        assertEquals("consent is not granted as a side effect", false, prefs.consent)
    }
}
