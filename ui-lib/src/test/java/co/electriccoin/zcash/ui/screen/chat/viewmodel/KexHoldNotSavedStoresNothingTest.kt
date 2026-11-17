package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
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

/**
 * GPT-6 F3 (22 Sep) — a stranger's chat request whose hold could not be saved is treated as held.
 *
 * A first-contact KEX writes the hold ([ZchatPreferences.setInboundKexAwaitingAccept]) and then stores the
 * stranger's key, the responder marker and E2E for them. When the hold is not written (a failed commit, or a
 * restart after one), nothing holds the paid KEXACK back: the handler goes on, stores the key and pays the
 * KEXACK at once, with nothing on screen and no Accept.
 *
 * Wanted: when the hold reports that it was not saved, the handler stops. It stores nothing for the stranger
 * and pays nothing; a later copy of the KEX is handled as a new first contact. The fake below answers the
 * hold with false (not saved) and keeps no marker, as the store reads after a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexHoldNotSavedStoresNothingTest {
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

    /** [prefs] with a hold that is never saved: setting one answers false and records nothing. */
    private fun holdNeverSaved(prefs: HandshakePrefs): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "setInboundKexAwaitingAccept" && args?.get(1) != null) {
                false
            } else {
                try {
                    method.invoke(prefs.proxy, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    @Test
    fun `a stranger's KEX whose hold was not saved stores nothing and pays nothing`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 31)
            val prefs = HandshakePrefs(stranger, firstContact = true)
            val seam = HandshakeSeam(stranger)
            val vm = chatViewModelWith(holdNeverSaved(prefs), seam)

            handleKexMemo(vm, kexMemoFrom(stranger, E2EEncryption.generateKeyPair()), txId = "synthetic-unsaved-hold-kex")
            awaitIdle(vm)
            vm.retryKexAckIfResponder(stranger)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "a stranger's KEX was answered with a paid KEXACK although its hold was never saved",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals("no KEXACK budget may be used", 0, prefs.budgetConsumed.get())
            assertFalse(
                "the stranger's key was stored although the hold that keeps its KEXACK back was not saved",
                prefs.e2ePeerKey.containsKey(stranger),
            )
            assertFalse("no responder marker may be stored", prefs.receivedKex.containsKey(stranger))
            assertFalse("E2E may not be turned on for the stranger", stranger in prefs.e2eEnabled)
        }

    /** Control (passes before and after): a saved hold keeps the request for Accept, as before. */
    @Test
    fun `control - a stranger's KEX whose hold was saved waits for Accept`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 32)
            val prefs = HandshakePrefs(stranger, firstContact = true)
            val seam = HandshakeSeam(stranger)
            val vm = chatViewModelWith(prefs.proxy, seam)

            handleKexMemo(vm, kexMemoFrom(stranger, E2EEncryption.generateKeyPair()), txId = "synthetic-saved-hold-kex")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(0, seam.proposalRequests.get())
            assertTrue("the request must wait for Accept", prefs.awaitingAccept.containsKey(stranger))
            assertTrue("the stranger's key is kept for the Accept", prefs.e2ePeerKey.containsKey(stranger))
        }
}
