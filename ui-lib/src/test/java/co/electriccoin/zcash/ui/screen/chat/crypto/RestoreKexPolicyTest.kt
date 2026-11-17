package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.Ciphertext
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A5 — the rules the chat history scan follows while a restored wallet is still reading its history
 * (verification-20260915 KR-2, KR-3; GPT U1, U2).
 *
 * At HEAD the scan had no such rules: it acted on every inbound KEX the moment a pass saw it (generating a
 * replacement key before our own KEXACK was read, and paying a KEXACK the old device had already paid), and
 * it opened a restored peer's send gate at the end of any pass, however little of the history it had read.
 */
class RestoreKexPolicyTest {
    private val peerKey = "peer-key-1"
    private val ourKey = "our-key-1"

    @Test
    fun the_history_scan_is_complete_only_after_the_first_full_sync() {
        assertTrue(RestoreKexPolicy.isHistoryScanComplete(WalletRestoringState.SYNCING))
        for (state in listOf(WalletRestoringState.RESTORING, WalletRestoringState.INITIATING, WalletRestoringState.NONE, null)) {
            assertFalse(
                RestoreKexPolicy.isHistoryScanComplete(state),
                "restoring state $state means the first full scan has not finished",
            )
        }
    }

    /**
     * KR-2: a mined inbound KEX seen before the scan has read our own history must not be acted on — no key
     * generation, no KEXACK, no processed-mark — so a later pass handles it after our key is recovered.
     */
    @Test
    fun an_inbound_handshake_is_not_acted_on_before_the_history_scan_completes() {
        for (mined in listOf(true, false)) {
            for (stable in listOf(true, false)) {
                assertFalse(
                    RestoreKexPolicy.mayActOnInboundHandshake(kexMined = mined, historyScanComplete = false, passStable = stable),
                    "mined=$mined stable=$stable: the scan is still running",
                )
            }
        }
    }

    /**
     * The scan can flip to complete while the pass still holds the transaction list from before the last
     * batch. A historical (mined) KEX waits for a pass that saw the same transactions as the previous
     * complete pass; a live (unmined) KEX on a synced wallet is handled at once.
     */
    @Test
    fun a_historical_handshake_needs_a_stable_complete_pass_and_a_live_one_does_not() {
        assertFalse(RestoreKexPolicy.mayActOnInboundHandshake(kexMined = true, historyScanComplete = true, passStable = false))
        assertTrue(RestoreKexPolicy.mayActOnInboundHandshake(kexMined = true, historyScanComplete = true, passStable = true))
        // ZC-941 (ZC-599, FIRST-CONTACT-SPEC 5.4 item 7): a key exchange that is not mined is never acted on, whatever else holds.
        assertFalse(RestoreKexPolicy.mayActOnInboundHandshake(kexMined = false, historyScanComplete = true, passStable = false))
        assertFalse(RestoreKexPolicy.mayActOnInboundHandshake(kexMined = false, historyScanComplete = true, passStable = true))
    }

    /** KR-3: a pass that missed any memo, or any of our own encrypted messages, did not read the whole history. */
    @Test
    fun a_pass_reads_the_whole_history_only_when_nothing_was_missed() {
        assertTrue(RestoreKexPolicy.isPassHistoryComplete(true, true, memoReadFailures = 0, unattributedOwnEnvelopeGaps = 0))
        assertFalse(RestoreKexPolicy.isPassHistoryComplete(false, true, 0, 0), "scan still running")
        assertFalse(RestoreKexPolicy.isPassHistoryComplete(true, false, 0, 0), "transaction list still changing")
        assertFalse(RestoreKexPolicy.isPassHistoryComplete(true, true, memoReadFailures = 1, unattributedOwnEnvelopeGaps = 0))
        assertFalse(RestoreKexPolicy.isPassHistoryComplete(true, true, memoReadFailures = 0, unattributedOwnEnvelopeGaps = 1))
    }

    /**
     * KR-3 / U2: the send gate opens only on a pass that read the whole history, did not re-derive the key,
     * did not change the root material, and read every one of our own messages to that peer.
     */
    @Test
    fun the_send_gate_opens_only_on_a_complete_converged_pass() {
        fun release(
            complete: Boolean = true,
            recovered: Boolean = false,
            rootChanged: Boolean = false,
            gap: Boolean = false,
        ) = RestoreKexPolicy.mayReleaseSendGate(
            passHistoryComplete = complete,
            recoveredThisPass = recovered,
            rootChangedThisPass = rootChanged,
            ownEnvelopeGap = gap,
        )
        assertTrue(release())
        assertFalse(release(complete = false), "history incomplete")
        assertFalse(release(recovered = true), "key re-derived in this pass")
        assertFalse(release(rootChanged = true), "root material changed in this pass")
        assertFalse(release(gap = true), "one of our own messages was not read")
    }

    /**
     * No paid KEXACK replay after a restore: our own mined KEXACK sent with the key we hold now proves the
     * peer's preceding key was already answered, so the scan records it and no second KEXACK is paid.
     */
    @Test
    fun our_own_mined_kexack_with_the_current_key_proves_the_peer_key_was_acked() {
        assertEquals(peerKey, RestoreKexPolicy.peerKeyAckedOnChain(ourKey, currentOwnPublicKey = ourKey, lastInboundPeerKexKey = peerKey))
    }

    @Test
    fun a_kexack_proves_nothing_for_a_key_we_no_longer_hold_or_without_a_preceding_kex() {
        assertNull(
            RestoreKexPolicy.peerKeyAckedOnChain(ourKey, currentOwnPublicKey = "our-key-after-reset", lastInboundPeerKexKey = peerKey),
            "a KEXACK carrying an older key of ours says nothing about the key the peer must learn now",
        )
        assertNull(RestoreKexPolicy.peerKeyAckedOnChain(ourKey, currentOwnPublicKey = null, lastInboundPeerKexKey = peerKey))
        assertNull(RestoreKexPolicy.peerKeyAckedOnChain(ourKey, currentOwnPublicKey = ourKey, lastInboundPeerKexKey = null))
    }

    // ------------------------------------------------------------------
    // P2d (3) — one unreadable own message never keeps a restored peer's send gate shut
    // ------------------------------------------------------------------

    private val root = ByteArray(32) { (it * 7 + 3).toByte() }
    private val convId = "CONVP2D3"
    private val lowerDirection: Byte = 0x00

    /**
     * P2d (3): the gate stayed shut while ANY of our own encrypted messages to the peer could not be read in a
     * pass, and one that is never readable kept it shut for good ("try again in a moment" until Reset
     * Encryption). Our own mined envelopes carry their direction and counter in clear, so the floor can be raised
     * above the unread one and the gate may open. The next send must use a counter no old message used.
     */
    @Test
    fun an_own_message_that_stays_unreadable_no_longer_keeps_the_send_gate_shut_and_no_counter_is_reused() =
        runTest {
            val oldDevice = E2EMessageProcessor(root, convId, isLower = true, store = InMemoryRatchetStateStore())
            val sent = (0 until 4).map { oldDevice.encryptOutgoing("old message $it") }

            val restoredStore = InMemoryRatchetStateStore()
            val restored = E2EMessageProcessor(root, convId, isLower = true, store = restoredStore)
            sent.take(3).forEach { restored.decryptIncoming(it) } // the scan read counters 0..2
            assertEquals(3L, restoredStore.loadCounterFloor(convId, lowerDirection), "fixture: floor from the read messages")
            val unread = listOf(CiphertextWireFormat.parse(sent[3])!!) // counter 3 is never readable

            assertTrue(
                RestoreKexPolicy.floorSendCounterAboveUnreadOwnEnvelopes(restoredStore, convId, lowerDirection, unread),
                "an unreadable own message whose counter is known must not keep the send gate shut",
            )
            val next = CiphertextWireFormat.parse(restored.encryptOutgoing("first message after the restore"))!!
            val used = sent.map { CiphertextWireFormat.parse(it)!!.counter }.toSet()
            assertFalse(next.counter in used, "counter ${next.counter} was already used on chain: AES-GCM key and nonce reuse")
            assertEquals(4L, next.counter)
        }

    /**
     * P2d (3): an unreadable own message in the OTHER direction was sent under an older key pair of ours (the key
     * order decides the direction), so it can never collide with our sends now. It must not keep the gate shut,
     * and it must not move our send floor.
     */
    @Test
    fun an_unreadable_own_message_in_the_other_direction_does_not_keep_the_gate_shut() =
        runTest {
            val store = InMemoryRatchetStateStore()
            val otherDirection = Ciphertext(direction = 0x01, counter = 7L, bytes = ByteArray(24))

            assertTrue(RestoreKexPolicy.floorSendCounterAboveUnreadOwnEnvelopes(store, convId, lowerDirection, listOf(otherDirection)))
            assertEquals(0L, store.loadCounterFloor(convId, lowerDirection), "our send floor is unchanged")
        }

    /**
     * Control, GREEN before and after: raising the floor further than the peer's receive window (MAX_SKIP) would
     * make every later message undecryptable for the peer, so such an envelope keeps the gate shut as before.
     */
    @Test
    fun an_unreadable_own_message_beyond_the_peers_window_keeps_the_gate_shut() =
        runTest {
            val store = InMemoryRatchetStateStore()
            val farAhead = Ciphertext(direction = lowerDirection, counter = E2ERatchet.MAX_SKIP + 5, bytes = ByteArray(24))

            assertFalse(RestoreKexPolicy.floorSendCounterAboveUnreadOwnEnvelopes(store, convId, lowerDirection, listOf(farAhead)))
            assertEquals(0L, store.loadCounterFloor(convId, lowerDirection))
        }
}
