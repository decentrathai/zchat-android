package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 8 D10 (DECISIONS_NIGHT §6, p2-08c5d32fc73c) — the two dead ends a failing phone store leaves in a paid chat step.
 *
 * Since R5-2 a paid chat step whose "already paid" record cannot be saved pays nothing, which keeps the money safe, but
 * the user is told nothing. Two of these cases are dead ends:
 * - the automatic-payments consent: when setConsentedToHandshakeSpend() is not saved, grantHandshakeSpendConsent
 *   returns with the question still on screen, so Allow can be tapped for ever with no explanation;
 * - the KEXACK: when its sent-marker is not saved, payKexAckOnce sends nothing, and the responder can never message.
 *
 * Wanted, with no spending decision changed (nothing is paid in either case):
 * - a consent that could not be saved closes the question and shows chat_storage_paused_paid_step;
 * - a KEXACK that was not sent because its marker could not be saved shows chat_storage_paused_paid_step once per
 *   process and contact: a second attempt in the same process shows nothing new.
 * The notice exists in English and Spanish, and the chat's error text filter shows it as it is.
 *
 * All keys are generated per run and every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoragePausedPaidStepNoticeTest {
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
    fun `the notice exists in English and Spanish and reaches the screen as it is`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals(NOTICE_EN, en[NOTICE_KEY])
        assertEquals(NOTICE_ES, es[NOTICE_KEY])
        assertEquals("the chat's error filter must not replace the notice", NOTICE_EN, NOTICE_EN.toZchatUserMessage("fallback"))
    }

    @Test
    fun `a consent that could not be saved closes the question, shows the notice and pays nothing`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticd10consentunsaved1", consent = false)
            val seam = HandshakeSeam(fixture.member)
            val vm = chatViewModelWith(consentNeverSaved(fixture.proxy), seam)
            fixture.scan(vm, fixture.txId(61))
            awaitIdle(vm)
            assertTrue(
                "fixture sanity: without consent the grant asks first",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent,
            )

            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertEquals("a consent that was not saved released a paid step", 0, seam.proposalRequests.get())
            assertEquals(0, seam.broadcasts.size)
            assertFalse("the consent was not saved, so it is not given", fixture.consent)
            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse(
                "the question stays on screen and Allow can be tapped for ever with no explanation",
                state is SendMessageState.NeedsHandshakeSpendConsent,
            )
            assertNotice(state)
        }

    @Test
    fun `a KEXACK whose marker could not be saved sends nothing and shows the notice once per process`() =
        runBlocking {
            val peer = "usyntheticd10kexackunsaved01"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                }
            val stamps = AtomicInteger()
            val store = markerNeverSaved(prefs.proxy, stamps)
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(store, seam)

            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("fixture sanity: the attempt reached the marker", 1, stamps.get())
            assertEquals("nothing may be paid without its marker", 0, seam.proposalRequests.get())
            assertEquals(0, seam.broadcasts.size)
            assertNotice(HandshakeHarness.sendMessageState(vm))

            // The same contact again, on this screen and on another screen of the same process.
            vm.resetSendState()
            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)
            val otherScreen = chatViewModelWith(store, seam)
            otherScreen.retryKexAckIfResponder(peer)
            awaitIdle(otherScreen)

            assertEquals("fixture sanity: both later attempts reached the marker", 3, stamps.get())
            assertEquals("nothing may be paid without its marker", 0, seam.proposalRequests.get())
            assertEquals(
                "a second attempt in the same process shows the notice again",
                SendMessageState.Idle,
                HandshakeHarness.sendMessageState(vm),
            )
            assertFalse(
                "another screen of the same process shows the notice again",
                HandshakeHarness.sendMessageState(otherScreen) is SendMessageState.Error,
            )
        }

    /** Control (passes before and after): a KEXACK whose marker is saved is paid once and shows no notice. */
    @Test
    fun `control - a KEXACK whose marker is saved is paid once and shows no notice`() =
        runBlocking {
            val peer = "usyntheticd10kexacksaved001"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam)

            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)

            assertEquals(1, seam.proposalRequests.get())
            assertFalse(HandshakeHarness.sendMessageState(vm) is SendMessageState.Error)
        }

    private fun assertNotice(state: SendMessageState) {
        assertTrue("the user must be told why the paid step paused: $state", state is SendMessageState.Error)
        val text = (state as SendMessageState.Error).message
        assertEquals(NOTICE_EN, text)
        val id = ChatSendCopy.localizedResource(text)
        assertTrue("the notice needs its resource so a Spanish phone reads it in Spanish", id != null)
        assertEquals(NOTICE_KEY, ChatResourceText.stringName(id!!))
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

    /** [delegate] whose KEXACK sent-marker is never stored: each stamp is counted in [stamps] and answers false. */
    private fun markerNeverSaved(
        delegate: ZchatPreferences,
        stamps: AtomicInteger,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "setSentKexAckPubkey" && args?.getOrNull(1) != null) {
                stamps.incrementAndGet()
                false
            } else {
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    private companion object {
        const val NOTICE_KEY = "chat_storage_paused_paid_step"
        const val NOTICE_EN =
            "ZChat couldn't save its records on this phone, so it paused a paid chat step to make sure you are never " +
                "charged twice. Free up some space or restart your phone, then open the chat again."
        const val NOTICE_ES =
            "ZChat no pudo guardar sus registros en este teléfono, así que pausó un paso de pago del chat para que nunca " +
                "se te cobre dos veces. Libera espacio o reinicia el teléfono y vuelve a abrir el chat."
    }
}
