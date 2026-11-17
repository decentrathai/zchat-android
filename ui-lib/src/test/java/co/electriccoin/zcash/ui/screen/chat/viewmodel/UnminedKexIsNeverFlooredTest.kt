package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.KexFloorHarness.handleScannedKex
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
 * KI-24 part A (fix-all 2.13.0, train T4; SPECS §2.2 A3), a guard against a false drop, GREEN before and after the fix.
 *
 * A KEX still in the mempool has no mined height, so no height floor can say an earlier pass saw it. Even with a floor
 * above the chain tip, an unmined KEX that the store never recorded is handled: its key is stored and one KEXACK is
 * paid. Fixture: [KexFloorPrefs] with a floor of 9,999,999. Every key and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnminedKexIsNeverFlooredTest {
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
    fun `an unmined KEX is handled even when the floor is above the tip`() =
        runBlocking {
            val peer = "usyntheticki24unminedkex00001"
            val keys = E2EEncryption.generateKeyPair()
            val base = HandshakePrefs(peer)
            // W-62 (ZC-599): a key exchange at a chat that still waits for the contact's first key (our key pair, no contact key)
            // now waits for Accept. This case is about the processed-KEX floor, so the contact's key is already held (the key exchange repeats it).
            base.e2ePeerKey[peer] = keys.publicKey
            val prefs = KexFloorPrefs(base, floorHeight = 9_999_999L)
            val seam = HandshakeSeam(peer)
            val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam)

            handleScannedKex(vm, kexMemoFrom(peer, keys), "synthetic-ki24-mempool-kex-txid", minedHeight = null)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), base.unexpectedCalls)
            assertEquals("the contact's key is stored", keys.publicKey, base.e2ePeerKey[peer])
            assertEquals("exactly one KEXACK is paid", 1, seam.proposalRequests.get())
            assertEquals(keys.publicKey, base.sentKexAck[peer])
        }
}
