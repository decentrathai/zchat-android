package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.Ciphertext
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.RatchetStateStore
import java.io.IOException

/**
 * The decisions the chat history scan makes while a restored (or rescanning) wallet is still reading its
 * history. Pure functions, so every rule can be pinned by a JVM test without a ViewModel.
 *
 * After a seed restore every local chat store is empty. The SDK hands the transaction history over in
 * batches while it scans, so a pass can see an old inbound KEX before it sees our own KEXACK that answered
 * it, and can see some of our old encrypted messages but not the newest ones. Acting on a partial view
 * generates a replacement key (the historical one can then never be recovered), pays a KEXACK the old
 * device already paid, or opens the send gate before the counter floor covers every counter already used.
 */
object RestoreKexPolicy {
    /**
     * R3-3 (1): what the pass end does with the send counters a ratchet carries from an older conversation id of
     * the peer (see [co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet.settleLegacyJump]).
     */
    enum class LegacyJumpStep {
        /** Not every one of our own messages has been read yet: leave them unsettled (sends keep waiting). */
        WAIT,

        /** One of our own messages could not be read to its header or conversation: keep them (no reused counter). */
        ADOPT,

        /** Settle them from our own messages under that id. */
        SETTLE,
    }

    /**
     * R4-3 (2): how many pass-ends an older id's counters may still WAIT after the chain scan has finished.
     *
     * Counted only over passes whose chain scan was complete, so a restoring wallet waits for its scan however
     * long that takes. Past it the counters are ADOPTed, which is what the pass end already does whenever the
     * evidence is incomplete: kept for good, never a reused counter.
     */
    const val MAX_LEGACY_SETTLEMENT_WAITS = 5

    /**
     * R3-3 (1): the step for an older conversation id's unsettled send counters at the end of a pass.
     *
     * @param ownHistoryPassComplete [isOwnHistoryPassComplete] for this pass: it read every one of our own memos
     * @param ownEnvelopeHeaderUnknown one of our own encrypted messages (to this peer, under that id, or unattributed)
     *   could not be read to its header or conversation id, so the evidence may be incomplete
     * @param waitedCompletedScans R4-3 (2): pass-ends with a FINISHED chain scan at which these counters already
     *   waited. Past [MAX_LEGACY_SETTLEMENT_WAITS] the wait ends in ADOPT rather than never, because one own memo
     *   this device can never read holds [isOwnHistoryPassComplete] down for good and would refuse every send to
     *   that contact for ever. ADOPT keeps the counters, so no counter is reused; running out of patience NEVER
     *   settles them, which is the step that would drop counters from unread history.
     */
    fun legacyJumpStep(
        ownHistoryPassComplete: Boolean,
        ownEnvelopeHeaderUnknown: Boolean,
        waitedCompletedScans: Int = 0,
    ): LegacyJumpStep =
        when {
            ownHistoryPassComplete && !ownEnvelopeHeaderUnknown -> LegacyJumpStep.SETTLE
            ownHistoryPassComplete -> LegacyJumpStep.ADOPT
            waitedCompletedScans >= MAX_LEGACY_SETTLEMENT_WAITS -> LegacyJumpStep.ADOPT
            else -> LegacyJumpStep.WAIT
        }

    /**
     * R4-3 (2): what a send refused by [maySendBeforeLegacySettlement] tells the user.
     *
     * @param awaitingLegacySettlement the send was refused because the history pass has not settled this
     *   contact's older conversation counters — not because a key is missing or a session is re-establishing
     * @param otherwise the reason for every other refusal on that path
     */
    fun sendRefusedReason(
        awaitingLegacySettlement: Boolean,
        otherwise: String,
    ): String = if (awaitingLegacySettlement) STILL_READING_HISTORY else otherwise

    /**
     * R4-3 (2): the honest wording for a send held by the history settlement. It says what is happening, that
     * nothing was sent or paid, and that the reading ends by itself — which [MAX_LEGACY_SETTLEMENT_WAITS] makes
     * true. Round 7c (R6-4 review finding 18): the message does NOT go by itself afterwards. The refused send is a
     * FAILED row and nothing re-sends a FAILED row, so the notice tells the user to tap the row's Retry button
     * (chat_retry). Byte-equal to chat_send_still_reading_history (HeldSendNoticePromisesNoAutoSendTest).
     */
    const val STILL_READING_HISTORY =
        "ZCHAT is still reading this chat's history from the blockchain. Nothing was sent and nothing was paid. " +
            "This finishes on its own; tap Retry then."

    /**
     * R3-3 (1): may a message be encrypted to the peer now? Not while its ratchet carries an older conversation id's
     * counters the pass end has not settled: the send would make them permanent, and they may be an older root's,
     * beyond what the peer accepts.
     */
    fun maySendBeforeLegacySettlement(unsettledLegacyJumps: Set<String>): Boolean = unsettledLegacyJumps.isEmpty()

    /**
     * True once the wallet has finished scanning its history at least once since it was created, restored
     * or rescanned. The wallet repository stores [WalletRestoringState.SYNCING] at the first SYNCED status
     * and keeps it; RESTORING, INITIATING and NONE all mean that first full scan has not finished. A null
     * state (no synchronizer yet) is not complete either.
     */
    fun isHistoryScanComplete(restoringState: WalletRestoringState?): Boolean =
        restoringState == WalletRestoringState.SYNCING

    /**
     * May the scan act on an inbound handshake memo (a KEX) now: store the key,
     * generate ours, pay a KEXACK and mark the transaction processed? If not, the memo is left untouched so
     * a later pass handles it once our own history (recovered key, our own KEXACK) has been read.
     *
     * @param kexMined the memo's transaction is mined, i.e. it is history rather than a live handshake
     * @param historyScanComplete [isHistoryScanComplete] for this pass
     * @param passStable this pass saw exactly the transactions the previous complete pass saw (the scan passes
     *   [isOwnHistoryPassComplete], so a pass with an unreadable own memo is not stable either)
     *
     * ZC-941 (ZC-599, FIRST-CONTACT-SPEC 5.4 item 7): a key exchange that is not mined is never acted on: a memo that is
     * broadcast and never mined costs its sender nothing, and acting on it drew the automatic answer. It is handled at
     * the pass that reads it mined.
     */
    fun mayActOnInboundHandshake(
        kexMined: Boolean,
        historyScanComplete: Boolean,
        passStable: Boolean,
    ): Boolean = kexMined && historyScanComplete && passStable

    /**
     * May this pass run restore recovery (reinstall our latest own key from the seed) and count as having read our
     * own history? Recovery installs the LATEST own key the pass saw, and a stored key is never replaced, so it may
     * only run on a pass that saw every one of our own transactions.
     *
     * @param historyScanComplete [isHistoryScanComplete] for this pass
     * @param passStable this pass saw exactly the transactions the previous complete pass saw
     * @param ownMemoReadFailures our own (sent) transactions whose memos could not be read in this pass
     */
    fun isOwnHistoryPassComplete(
        historyScanComplete: Boolean,
        passStable: Boolean,
        ownMemoReadFailures: Int,
    ): Boolean = historyScanComplete && passStable && ownMemoReadFailures == 0

    /**
     * Did this pass read the whole history? Only such a pass may open a restored peer's send gate.
     *
     * @param memoReadFailures transactions whose memos could not be read in this pass
     * @param unattributedOwnEnvelopeGaps our own encrypted messages this pass could neither read nor tie to
     *   a conversation
     */
    fun isPassHistoryComplete(
        historyScanComplete: Boolean,
        passStable: Boolean,
        memoReadFailures: Int,
        unattributedOwnEnvelopeGaps: Int,
    ): Boolean = historyScanComplete && passStable && memoReadFailures == 0 && unattributedOwnEnvelopeGaps == 0

    /**
     * ZC-074: may this pass raise the processed-KEX ceiling (ZchatPreferences.limitKexFloor)? Only a pass that read the
     * whole history and saw exactly the transactions of the pass before. Unlike [isPassHistoryComplete] (the send gate),
     * the ceiling does not wait for our own encrypted messages: one of ours is never an inbound key offer. And a memo the
     * pass could not read holds the ceiling only from its own height: the pass reports an unread inbound transaction
     * mined at H as an unrecorded offer at H (the ceiling stays at H - 1); an unread own one, or an unmined one (the floor
     * never covers a height it does not have), does not hold it. Offers the pass puts off are reported the same way.
     *
     * @param historyScanComplete [isHistoryScanComplete] for this pass
     * @param passStable this pass saw exactly the transactions the previous complete pass saw
     */
    fun isKexFloorPassComplete(
        historyScanComplete: Boolean,
        passStable: Boolean,
    ): Boolean = historyScanComplete && passStable

    /**
     * May the pass-end reconcile clear a restored peer's send gate?
     *
     * @param passHistoryComplete [isPassHistoryComplete] for this pass
     * @param recoveredThisPass the peer's key was re-derived in this pass (its root converges next pass)
     * @param rootChangedThisPass the peer's KEX/KEXACK txid material changed at the end of this pass
     * @param ownEnvelopeGap one of our own encrypted messages to this peer was not read in this pass (no
     *   conversation id, no session yet, or its outgoing peer could not be resolved)
     */
    fun mayReleaseSendGate(
        passHistoryComplete: Boolean,
        recoveredThisPass: Boolean,
        rootChangedThisPass: Boolean,
        ownEnvelopeGap: Boolean,
    ): Boolean = passHistoryComplete && !recoveredThisPass && !rootChangedThisPass && !ownEnvelopeGap

    /**
     * Our own mined KEXACK proves we already answered the peer's key with the key we still hold. Returns
     * the peer key that KEXACK answered (the last mined inbound KEX key for the conversation before it), so
     * the scan can record it as acked and no second KEXACK is paid for it; null when it proves nothing.
     *
     * @param ownKexAckPublicKey our public key, verified, from our own mined KEXACK
     * @param currentOwnPublicKey our public key currently stored for the peer
     * @param lastInboundPeerKexKey the peer key of the last verified mined inbound KEX before that KEXACK
     */
    fun peerKeyAckedOnChain(
        ownKexAckPublicKey: String,
        currentOwnPublicKey: String?,
        lastInboundPeerKexKey: String?,
    ): String? = lastInboundPeerKexKey.takeIf { currentOwnPublicKey != null && ownKexAckPublicKey == currentOwnPublicKey }

    /**
     * P2d: a restored peer's send gate waits for our own encrypted messages to that peer that a pass could not
     * read, and one that is never readable kept it shut for good. Our own mined envelopes carry their direction and
     * counter in clear (E2E1:<dir>:<counter>:…), and only this wallet can create them, so the send-counter floor of
     * [floorKey] can be raised above the unread ones directly:
     * - an envelope in the other direction was sent under an older key pair of ours and can never collide with a
     *   send now (the direction is part of the chain key and the nonce), so it is ignored;
     * - for our direction the floor goes to the highest unread counter + 1 (message key and nonce depend only on
     *   the root, the direction and the counter, so no send can reuse one);
     * - a raise of [E2ERatchet.MAX_SKIP] or more is refused: the peer rejects a counter that far past what it has
     *   seen, so every later message would be unreadable for it. The gate then stays shut, as before.
     *
     * @param floorKey the key the send floor of our current ratchet with the peer is kept under
     *   ([E2ERatchet.floorKey])
     * @param myDirection our direction under the current keys (0 when our public key sorts lower)
     * @return true when the floor now covers every one of [unreadOwnEnvelopes] that could collide with our sends
     */
    suspend fun floorSendCounterAboveUnreadOwnEnvelopes(
        store: RatchetStateStore,
        floorKey: String,
        myDirection: Byte,
        unreadOwnEnvelopes: List<Ciphertext>,
    ): Boolean {
        val highest = unreadOwnEnvelopes.filter { it.direction == myDirection }.maxOfOrNull { it.counter } ?: return true
        val needed = highest + 1
        val current = store.loadCounterFloor(floorKey, myDirection)
        if (needed <= current) return true
        if (needed - current >= E2ERatchet.MAX_SKIP) return false
        return try {
            store.saveCounterFloor(floorKey, myDirection, needed)
            true
        } catch (e: IOException) {
            false
        }
    }
}
