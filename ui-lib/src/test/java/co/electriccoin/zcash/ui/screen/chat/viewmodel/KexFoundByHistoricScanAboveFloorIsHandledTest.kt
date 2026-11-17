package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.KexFloorHarness.FLOOR
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
 * KI-24 part A (fix-all 2.13.0, train T4; SPECS §2.2 A2), a guard against a false drop, GREEN before and after the fix.
 *
 * The processed-KEX floor must drop only what an earlier pass already saw. A KEX mined ABOVE the floor that the store
 * never recorded (a historic scan reaching it only now, or a new KEX) is a real key offer: it is stored and answered
 * with one KEXACK, exactly as without a floor. Fixture: [KexFloorPrefs]; the floor is 3,450,000 and the KEX was mined
 * at 3,460,000. Every key and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexFoundByHistoricScanAboveFloorIsHandledTest {
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
    fun `a KEX mined above the floor that was never processed is stored and answered with one KEXACK`() =
        runBlocking {
            val peer = "usyntheticki24abovefloor00001"
            val keys = E2EEncryption.generateKeyPair()
            val base = HandshakePrefs(peer)
            // W-62 (ZC-599): a key exchange at a chat that still waits for the contact's first key (our key pair, no contact key)
            // now waits for Accept. This case is about the processed-KEX floor, so the contact's key is already held (the key exchange repeats it).
            base.e2ePeerKey[peer] = keys.publicKey
            val prefs = KexFloorPrefs(base, floorHeight = FLOOR)
            val seam = HandshakeSeam(peer)
            val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam)

            handleScannedKex(vm, kexMemoFrom(peer, keys), "synthetic-ki24-above-floor-txid", minedHeight = FLOOR + 10_000)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), base.unexpectedCalls)
            assertEquals("the contact's key is stored", keys.publicKey, base.e2ePeerKey[peer])
            assertEquals("exactly one KEXACK is paid", 1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(keys.publicKey, base.sentKexAck[peer])
            assertEquals("no false key change", emptyList<String>(), prefs.keyChangedWrites)
            assertEquals(
                "the KEX is recorded as processed",
                true,
                "synthetic-ki24-above-floor-txid" in base.processedKexTx,
            )
        }
}
