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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * KI-24 part A (fix-all 2.13.0, train T4; SPECS §2.2 A1) — a KEX the processed-KEX store has forgotten is handled again.
 *
 * The wallet re-reads its whole history on every pass, so each KEX transaction reaches handleKEXMessage again and
 * again; only the processed-KEX store ("zchat_kex_seen", #201) stops it. That store keeps 500 txids and drops the
 * oldest. Once a KEX's txid has been dropped, the next pass handles that KEX as new. At 6e24d549f:
 *  - for a contact whose key has changed since (a new install, a Reset), the old key differs from the stored one: the
 *    handler raises "PEER KEY CHANGED" and clears the contact's verified mark, although nothing changed;
 *  - after "Reset encryption" with a contact (their key and our KEXACK marker are cleared until their new KEX or
 *    KEXACK arrives), the old KEX is taken as the contact's key: it is stored and a KEXACK (about 17,000 zatoshi) is
 *    paid for a key the contact no longer uses, and their real answer then shows as a key change.
 *
 * Wanted (KI-24 part A): the store keeps each KEX's mined height and, when it evicts entries, a floor at the highest
 * evicted height (never above the fully scanned height). The history pass hands the mined height to the handler, and a
 * KEX mined at or below the floor counts as processed: nothing is stored, flagged or paid.
 *
 * Fixture: [KexFloorPrefs] over [HandshakePrefs]; the old KEX was mined at 3,400,000, below the floor of 3,450,000.
 * Every key and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvictedKexTxIsNotReprocessedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val OLD_KEX_HEIGHT = 3_400_000L
        const val OLD_KEX_TXID = "synthetic-ki24-evicted-kex-txid"
    }

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
    fun `an evicted old KEX of a contact whose key changed since raises no key-change warning and pays nothing`() =
        runBlocking {
            val peer = "usyntheticki24evictedkex00001"
            val oldKeys = E2EEncryption.generateKeyPair()
            val currentKeys = E2EEncryption.generateKeyPair()
            val base =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = currentKeys.publicKey
                    receivedKex[peer] = currentKeys.publicKey
                    sentKexAck[peer] = currentKeys.publicKey
                }
            val prefs = KexFloorPrefs(base, floorHeight = FLOOR)
            val seam = HandshakeSeam(peer)
            val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam)

            handleScannedKex(vm, kexMemoFrom(peer, oldKeys), OLD_KEX_TXID, minedHeight = OLD_KEX_HEIGHT)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), base.unexpectedCalls)
            assertEquals(
                "a KEX mined below the floor of the processed-KEX store was handled again after its txid was evicted: " +
                    "the contact's old key raised a false \"key changed\" warning",
                emptyList<String>(),
                prefs.keyChangedWrites,
            )
            assertEquals("the contact's verified mark must stay as it was", emptyList<String>(), prefs.verifiedWrites)
            assertEquals("nothing may be paid", 0, seam.proposalRequests.get())
            assertEquals("the contact's current key stays", currentKeys.publicKey, base.e2ePeerKey[peer])
        }

    @Test
    fun `after Reset encryption an evicted old KEX is not taken as the contact's key and no KEXACK is paid for it`() =
        runBlocking {
            val peer = "usyntheticki24evictedkex00002"
            val oldKeys = E2EEncryption.generateKeyPair()
            // Reset encryption cleared the contact's key and our KEXACK marker; their new KEX has not arrived yet.
            val base = HandshakePrefs(peer)
            val prefs = KexFloorPrefs(base, floorHeight = FLOOR)
            val seam = HandshakeSeam(peer)
            val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam)

            handleScannedKex(vm, kexMemoFrom(peer, oldKeys), OLD_KEX_TXID, minedHeight = OLD_KEX_HEIGHT)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), base.unexpectedCalls)
            assertEquals(
                "an evicted KEX mined below the floor paid a KEXACK (about 17,000 zatoshi) for a key the contact no " +
                    "longer uses",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals("no broadcast", emptyList<String>(), seam.broadcasts)
            assertEquals("no KEXACK budget spent", 0, base.budgetConsumed.get())
            assertNull("the old key must not be stored as the contact's key", base.e2ePeerKey[peer])
            assertNull("no KEXACK marker for the old key", base.sentKexAck[peer])
        }

    /** Control, GREEN before and after: the same KEX whose txid is still in the store is skipped by the txid alone. */
    @Test
    fun `control - a KEX whose txid is still in the store is skipped`() =
        runBlocking {
            val peer = "usyntheticki24evictedkex00003"
            val oldKeys = E2EEncryption.generateKeyPair()
            val base = HandshakePrefs(peer).apply { processedKexTx += OLD_KEX_TXID }
            val prefs = KexFloorPrefs(base, floorHeight = FLOOR)
            val seam = HandshakeSeam(peer)
            val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam)

            handleScannedKex(vm, kexMemoFrom(peer, oldKeys), OLD_KEX_TXID, minedHeight = OLD_KEX_HEIGHT)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), base.unexpectedCalls)
            assertEquals(0, seam.proposalRequests.get())
            assertNull(base.e2ePeerKey[peer])
        }
}
