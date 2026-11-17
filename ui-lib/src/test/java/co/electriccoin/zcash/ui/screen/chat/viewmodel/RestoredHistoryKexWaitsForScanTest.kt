package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KR-2 (GPT U1) — after a seed restore, an old inbound KEX was acted on before the history scan had read our
 * own history.
 *
 * The SDK hands a restoring wallet its history in batches. At d4fc2bd15 the received-KEX branch of
 * handleKEXMessage marked the transaction processed, generated a fresh key for a peer we hold no key for
 * (the historical key, still derivable from the seed, can then never be recovered) and paid a KEXACK the old
 * device had already paid, all on the first pass that saw the KEX. The scan now passes
 * RestoreKexPolicy.mayActOnInboundHandshake into the handler; when it is false the handler must leave the
 * memo completely untouched so a later pass handles it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoredHistoryKexWaitsForScanTest {
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
    fun `a KEX the scan may not act on yet is not marked, not stored and not acked`() =
        runBlocking {
            val peer = "usyntheticrestorekexwait0001"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(peer)
            val seam = HandshakeSeam(peer)
            val vm = handshakeViewModel(prefs, seam)

            handleScannedKex(vm, kexMemoFrom(peer, peerKeys), "synthetic-historical-kex-txid", mayAct = false)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertTrue(
                "the KEX was marked processed while the scan was still reading our own history; a later pass " +
                    "would skip it for good",
                prefs.processedKexTx.isEmpty(),
            )
            assertEquals("no KEXACK may be built before the scan has read our own KEXACK", 0, seam.proposalRequests.get())
            assertEquals(0, prefs.budgetConsumed.get())
            assertTrue("the peer key must not be stored yet", prefs.e2ePeerKey.isEmpty())
            assertTrue("the responder marker must not be set yet", prefs.receivedKex.isEmpty())
        }

    /** Control, GREEN before and after: once the scan may act, the same KEX is handled and acked once. */
    @Test
    fun `control - a KEX the scan may act on is handled and acked once`() =
        runBlocking {
            val peer = "usyntheticrestorekexwait0002"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(peer)
            // W-62 (ZC-599): a key exchange at a chat that still waits for the contact's first key (our key pair, no contact key)
            // now waits for Accept. This case is about the scan's may-act gate, so the contact's key is already held (the key exchange repeats it).
            prefs.e2ePeerKey[peer] = peerKeys.publicKey
            val seam = HandshakeSeam(peer)
            val vm = handshakeViewModel(prefs, seam)

            handleScannedKex(vm, kexMemoFrom(peer, peerKeys), "synthetic-live-kex-txid", mayAct = true)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(setOf("synthetic-live-kex-txid"), prefs.processedKexTx.toSet())
            assertEquals(peerKeys.publicKey, prefs.e2ePeerKey[peer])
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }

    /** The history scan's call: handleKEXMessage(memo, ourAddress, txId, mayActOnInboundHandshake). */
    private fun handleScannedKex(
        vm: ChatViewModel,
        memo: String,
        txId: String,
        mayAct: Boolean,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(
                "handleKEXMessage",
                String::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            .invoke(vm, memo, HandshakeHarness.SELF, txId, mayAct)
    }
}
