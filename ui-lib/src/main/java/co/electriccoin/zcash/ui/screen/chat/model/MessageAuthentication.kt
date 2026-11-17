package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat

/**
 * D9 "mark, don't reroute" — per-message authenticity classifier.
 *
 * A crafted PLAINTEXT on-chain memo carrying a contact's public address-hash routes into that
 * contact's trusted thread and would otherwise render indistinguishably from an authenticated
 * message. We deliberately do NOT reroute such messages (that would break first-contact / legacy /
 * ZTL / ZREQ threading); instead the classifier marks them so the UI can show an "unverified"
 * chip on non-authenticated incoming bodies in an established (isE2EReady) thread, except on those mined before the
 * chat's key exchange completed (Round 10b R10B-2: they could not have been signed then), and on the contact's
 * plaintext mined before its first ratcheted message (ZC-1910, [markBeforeContactRatchet]).
 *
 * Authenticated means: our own outgoing message, OR an incoming body that arrived as a ratcheted
 * `E2E1:` wire blob (AEAD-bound to the peer's secure key). An `E2E1:`-prefixed body that fails to
 * decrypt renders a benign placeholder — never attacker-controlled text — so prefix-only
 * classification is acceptable here.
 */
object MessageAuthentication {

    /**
     * @param rawMemoBody the RAW on-chain memo body BEFORE decryption/plaintext passthrough
     *   (i.e. `parsed.message`), never the already-decrypted display text.
     */
    fun isBodyAuthenticated(isOutgoing: Boolean, rawMemoBody: String): Boolean =
        isOutgoing || CiphertextWireFormat.isRatcheted(rawMemoBody)

    /**
     * Round 10b R10B-2 (F-R9C-3) — the chat's session height: the height of its earliest mined KEXACK (either
     * direction), the first block at which the key exchange had completed. It is known only when that earliest record
     * is [trustedHeights] (our own KEXACK, or the contact's signed with the key we hold for them); when the earliest
     * one is untrusted (a rotated key, or a memo anyone could mine), or there is none, it is null, never a later one.
     */
    fun sessionHeight(
        trustedHeights: List<Long>,
        untrustedHeights: List<Long>,
    ): Long? {
        val trusted = trustedHeights.minOrNull() ?: return null
        val untrusted = untrustedHeights.minOrNull()
        return if (untrusted != null && untrusted <= trusted) null else trusted
    }

    /**
     * A message mined at or below [sessionHeight] was sent before the chat's key exchange completed, so it could not
     * have been signed with the contact's secure key. False when either height is unknown (an unmined message, or no
     * trusted session height): the chip then follows the rule as before.
     */
    fun isPreSession(
        minedHeight: Long?,
        sessionHeight: Long?,
    ): Boolean = minedHeight != null && sessionHeight != null && minedHeight <= sessionHeight

    /** [messages] with [ChatMessage.preSession] set from [isPreSession] against [sessionHeight]. */
    fun markPreSession(
        messages: List<ChatMessage>,
        sessionHeight: Long?,
    ): List<ChatMessage> =
        messages.map { m ->
            val pre = isPreSession(m.minedHeight, sessionHeight)
            if (m.preSession == pre) m else m.copy(preSession = pre)
        }

    /**
     * ZC-1910 — the height of the contact's first ratcheted (E2E1) on-chain message in this chat, or null when there is
     * none yet. An incoming mined row is authenticated only when its raw body was an E2E1 blob ([isBodyAuthenticated]).
     */
    fun firstContactRatchetHeight(messages: List<ChatMessage>): Long? =
        messages
            .filter { !it.isOutgoing && it.authenticated }
            .mapNotNull { it.minedHeight }
            .minOrNull()

    /**
     * ZC-1910 — a contact's plaintext body mined above the chat's [sessionHeight] but at or below the height of the
     * contact's first ratcheted message ([firstRatchetHeight]), or with none from them yet at or below
     * [contactPlaintextWindowEnd] (ZC-1943): the contact's app sends
     * plaintext ("Zcash-only") until it has processed our KEXACK, so this one was most likely built before it could be
     * signed. False when either height it needs is unknown (an unmined row, or no trusted session height).
     *
     * What this cannot tell apart (client-side, no wire change): a plaintext memo forged by someone who knows the
     * contact's address hash, mined in that same window (before the contact's first E2E1, or while the contact has sent
     * none), from the contact's genuine one; a contact that never sends a ratcheted message; and, after a Reset or a key
     * change, a pre-KEXACK plaintext of the new session (the first E2E1 is then old history, so it keeps the chip).
     */
    fun isBeforeContactRatchet(
        minedHeight: Long?,
        sessionHeight: Long?,
        firstRatchetHeight: Long?,
    ): Boolean =
        minedHeight != null &&
            sessionHeight != null &&
            minedHeight > sessionHeight &&
            // ZC-1943: only within the window a plaintext built before our KEXACK can be mined in, whether or not a ratcheted
            // row follows (a row counts as ratcheted by its "E2E1:" prefix alone, which anyone can mine).
            minedHeight <= contactPlaintextWindowEnd(sessionHeight).let { end -> minOf(firstRatchetHeight ?: end, end) }

    /** ZC-1943: blocks of 75 s a contact's app may take to scan our mined KEXACK (kept as time from NU7 on). */
    private const val KEXACK_SCAN_MARGIN_PRE_NU7_BLOCKS = 10L

    /**
     * ZC-1943 — the last height at which a plaintext the contact built before it had seen our KEXACK mined at
     * [sessionHeight] can be mined: the session height, plus the margin its app may take to scan that KEXACK, plus the
     * largest transaction expiry delta at that height (40 blocks before NU7, 120 from NU7 on; [ChainTiming]).
     */
    fun contactPlaintextWindowEnd(sessionHeight: Long): Long =
        sessionHeight +
            ChainTiming.timeKeptBlocks(KEXACK_SCAN_MARGIN_PRE_NU7_BLOCKS, sessionHeight) +
            ChainTiming.maxExpiryDelta(sessionHeight)

    /**
     * ZC-1910 — [messages] (one chat) with [ChatMessage.preSession] also set on the contact's plaintext rows
     * [isBeforeContactRatchet], when the session's earliest trusted KEXACK is our own ([sessionIsOurKexAck], ZC-1943): like a pre-session row, it could not have been signed, so it has no "Sender not
     * verified" chip. Own rows, ratcheted rows and rows already pre-session are unchanged; a plain payment request keeps
     * its own chip ([unverifiedChip] ignores pre-session for it).
     */
    fun markBeforeContactRatchet(
        messages: List<ChatMessage>,
        sessionHeight: Long?,
        sessionIsOurKexAck: Boolean,
    ): List<ChatMessage> {
        // ZC-1943: only when our own KEXACK opened the session; a contact whose KEXACK did had the session before it sent.
        if (sessionHeight == null || !sessionIsOurKexAck) return messages
        val firstRatchet = firstContactRatchetHeight(messages)
        return messages.map { m ->
            val exempt =
                !m.isOutgoing &&
                    !m.authenticated &&
                    !m.preSession &&
                    isBeforeContactRatchet(m.minedHeight, sessionHeight, firstRatchet)
            if (exempt) m.copy(preSession = true) else m
        }
    }

    /**
     * The "Sender not verified" chip: an incoming body that was not ratcheted, in a chat that now has a session,
     * mined after that session existed. Never on our own messages, before the chat has a session, or on a message
     * sent before it ([preSession]; plaintext was expected then).
     */
    fun showsUnverifiedChip(
        isOutgoing: Boolean,
        authenticated: Boolean,
        isE2EReady: Boolean,
        preSession: Boolean,
    ): Boolean = isE2EReady && !authenticated && !isOutgoing && !preSession

    /**
     * The chip a bubble shows, or null. An unauthenticated incoming payment request gets [UnverifiedChip.PAYMENT_REQUEST]
     * whenever the chat has a session, pre-session or not: a ZREQ travels as a plain on-chain memo, so anyone who knows
     * the sender's hash can forge one, and paying it moves money. Any other message follows [showsUnverifiedChip].
     */
    fun unverifiedChip(
        isOutgoing: Boolean,
        authenticated: Boolean,
        isE2EReady: Boolean,
        preSession: Boolean,
        isPaymentRequest: Boolean,
    ): UnverifiedChip? =
        when {
            isPaymentRequest && isE2EReady && !authenticated && !isOutgoing -> UnverifiedChip.PAYMENT_REQUEST
            showsUnverifiedChip(isOutgoing, authenticated, isE2EReady, preSession) -> UnverifiedChip.SENDER
            else -> null
        }
}

/** Which unverified chip a bubble shows (chat_unverified_sender / chat_unverified_payment_request). */
enum class UnverifiedChip {
    SENDER,
    PAYMENT_REQUEST,
}
