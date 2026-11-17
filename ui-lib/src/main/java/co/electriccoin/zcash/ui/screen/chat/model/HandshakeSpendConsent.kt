package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences

/** The carrier output every automatic handshake memo pays to the peer (see ChatViewModel.sendAutomaticHandshakeMemo). */
const val HANDSHAKE_CARRIER_ZAT = 1_000L

/**
 * The platform-fee output. `buildZip321Uri` appends this to EVERY send, handshake memos included, so it
 * is part of the real bill even though nothing about a handshake is a "payment". Leaving it out of the
 * quote understated each handshake by 1,000 zatoshi.
 */
const val HANDSHAKE_PLATFORM_FEE_ZAT = 1_000L

/** ZIP 317 fee actually charged for one such single-action shielded transaction, measured on chain. */
const val HANDSHAKE_FEE_ZAT = 15_000L

/**
 * What one automatic handshake really costs the user: both outputs plus the network fee.
 *
 * Pinned against `CreateChunkedMessageProposalUseCase.totalCost` by HandshakeSpendConsentTest, and
 * matching the unrequested transaction observed on the Seeker on 2026-09-07 (1,000 + 1,000 + 15,000).
 */
const val HANDSHAKE_COST_ZAT = HANDSHAKE_CARRIER_ZAT + HANDSHAKE_PLATFORM_FEE_ZAT + HANDSHAKE_FEE_ZAT

/**
 * Whether ZChat may spend the user's ZEC on an AUTOMATIC handshake memo (KEX / KEXACK / ZBOOT /
 * group-accept).
 *
 * These sends carry no user gesture of their own: `retryPendingTunnelZboots` fires on every block, and
 * simply opening a chat sends a KEX. While a wallet is stranded in the sealed Orchard pool they all
 * fail and re-arm, so they queue — and then flush the instant funds arrive. That is exactly what
 * happened on 2026-09-07: a refilled test wallet broadcast 17,000 zatoshi of handshakes on its own, with
 * nothing on screen to say a transaction had been paid for.
 *
 * So the user is asked ONCE, before the first such spend, and told the whole bill. After that these
 * sends proceed untouched — the tunnel cannot bootstrap without them. The decision is taken again
 * before every retry attempt, so consent revoked while a handshake waits for a block (Move Old Balance)
 * stops it (HS-4). User-initiated sends never reach this decision: message sends do not go through the
 * handshake sender, and the time-lock senders (scheduled, block-, payment-locked, conditional) use the
 * user variant of its block-aware retry (HS-7).
 */
sealed interface HandshakeSpendDecision {
    /** The user has agreed to pay for automatic handshakes. */
    data object Allowed : HandshakeSpendDecision

    /** Ask first. [estimatedCost] covers every peer currently queued, not just the imminent one. */
    data class NeedsConsent(
        val pendingPeers: Int,
        val estimatedCost: Zatoshi
    ) : HandshakeSpendDecision
}

/**
 * Decide whether an automatic handshake may spend.
 *
 * [pendingPeers] is coerced to at least one: the caller only asks when it is about to spend for a peer,
 * so a zero count is a bookkeeping slip and must never read as "this is free".
 */
internal fun decideHandshakeSpend(
    consentGranted: Boolean,
    pendingPeers: Int,
): HandshakeSpendDecision =
    if (consentGranted) {
        HandshakeSpendDecision.Allowed
    } else {
        val peers = pendingPeers.coerceAtLeast(1)
        HandshakeSpendDecision.NeedsConsent(peers, handshakeSpendQuote(peers))
    }

/**
 * One kind of automatic chat-setup transaction, and how many paid transactions it can lead to.
 *
 * A KEX (we start the key exchange) or a KEXACK (we answer one) is followed by our ZBOOT, which delivers
 * our relay identity once the other side holds our key, so each counts as two. [retried] says whether the
 * setup is attempted again after consent is given, so it is kept in the queue the prompt quotes. R2-2 (d): a
 * GROUP_ACCEPT held for consent is sent once when consent is granted (ChatViewModel keeps the memo), so it
 * is kept in the queue too.
 */
enum class AutomaticSetupKind(
    val paidTransactions: Int,
    val retried: Boolean,
) {
    KEX(2, true),
    KEXACK(2, true),
    ZBOOT(1, true),
    GROUP_ACCEPT(1, true),
    GROUP_KEY_GRANT(1, true),
}

/**
 * One automatic setup blocked on consent: [kind] towards [target] (a peer address or group member).
 *
 * GPT-6 F5 — [scope] is what makes this setup a SEPARATE paid transaction from another of the same kind
 * towards the same target: the group id for a group action, the grant's own id for a group-key grant, and
 * null for a peer setup (a peer has one key exchange and one ZBOOT, so its target already identifies it).
 * Without it, two invites from one person to two groups were one queue entry and the prompt quoted half
 * the bill.
 */
data class QueuedAutomaticSetup(
    val kind: AutomaticSetupKind,
    val target: String,
    val scope: String? = null,
)

/**
 * How many paid transactions the queued automatic setups can still spend (HS-6): every blocked setup plus
 * every Tunnel peer whose ZBOOT is due ([zbootDuePeers]). A peer's ZBOOT is counted once, whether it is
 * due on its own, queued, or implied by that peer's queued KEX / KEXACK.
 *
 * Round 10b R10B-1 (F-R9C-5): a ZBOOT implied by a KEX / KEXACK is counted only when [zbootMayPayOnChain] says it may
 * go on-chain for that peer (ChatViewModel's rule: not a Shielded chat, or one whose confirmed first-message question
 * quoted it). A Shielded chat's ZBOOT is otherwise only ever sent free over the relays, so it is not part of the bill.
 * A queued ZBOOT and a due one are always counted: they are about to be paid.
 *
 * GPT-6 F5: a group setup is counted per [QueuedAutomaticSetup.scope], so two groups towards one person
 * are two transactions while the same group queued twice stays one.
 */
fun automaticSetupTransactions(
    queued: Collection<QueuedAutomaticSetup>,
    zbootDuePeers: Collection<String>,
    zbootMayPayOnChain: (String) -> Boolean = { true },
): Int {
    val peerSetups = setOf(AutomaticSetupKind.KEX, AutomaticSetupKind.KEXACK, AutomaticSetupKind.ZBOOT)
    val byPeer = queued.filter { it.kind in peerSetups }.groupBy { it.target }
    val due = zbootDuePeers.toSet()
    val peerTransactions =
        (byPeer.keys + due).sumOf { peer ->
            val kinds = byPeer[peer].orEmpty().map { it.kind }.toSet()
            val keyLegs = if (AutomaticSetupKind.KEX in kinds || AutomaticSetupKind.KEXACK in kinds) 1 else 0
            // This peer's one ZBOOT, when it can be paid on-chain at all.
            val zbootLeg = if (AutomaticSetupKind.ZBOOT in kinds || peer in due || zbootMayPayOnChain(peer)) 1 else 0
            keyLegs + zbootLeg
        }
    val otherTransactions = queued.filter { it.kind !in peerSetups }.toSet().sumOf { it.kind.paidTransactions }
    return peerTransactions + otherTransactions
}

/**
 * How long a paid chat setup (KEX or ZBOOT) for the same contact and the same key is not paid again,
 * unless the contact answers first. Stated to the user by the Reconnect action.
 */
const val HANDSHAKE_RESEND_COOLDOWN_MS: Long = 60 * 60 * 1000L

/**
 * The durable record of a paid chat setup, stored per slot (`KEX:<peer>` / `ZBOOT:<peer>`) as
 * `<identity>|<epoch millis>`. [identity] is the key the setup carried (our E2E public key for a KEX, our
 * relay public key for a ZBOOT), so a new key after a Reset or a rotation is never held back.
 */
object HandshakePaidRecord {
    fun slot(
        kind: AutomaticSetupKind,
        peer: String,
    ): String = "${kind.name}:$peer"

    fun encode(
        identity: String,
        atMillis: Long,
    ): String = "$identity|$atMillis"

    /** Milliseconds until [identity] may be paid for again; 0 when it may be paid now. */
    fun remainingCooldown(
        record: String?,
        identity: String,
        nowMillis: Long,
    ): Long {
        val at = record?.let { parse(it, identity) } ?: return 0L
        return (at + HANDSHAKE_RESEND_COOLDOWN_MS - nowMillis).coerceIn(0L, HANDSHAKE_RESEND_COOLDOWN_MS)
    }

    /** True when [record] was written for [identity] at exactly [atMillis] (so only its writer removes it). */
    fun isRecord(
        record: String?,
        identity: String,
        atMillis: Long,
    ): Boolean = record != null && parse(record, identity) == atMillis

    /**
     * ZC-626 — true when [record] was written for [identity], at any age: a setup carrying it was submitted (or may
     * have been) and its stamp was never withdrawn.
     */
    fun names(
        record: String?,
        identity: String,
    ): Boolean = record != null && parse(record, identity) != null

    private fun parse(
        record: String,
        identity: String,
    ): Long? {
        val cut = record.lastIndexOf('|')
        if (cut <= 0 || record.substring(0, cut) != identity) return null
        return record.substring(cut + 1).toLongOrNull()
    }
}

/**
 * What [peers] automatic handshakes cost the user. The consent dialog quotes this figure, and a New-Chat
 * Send All to a contact whose handshake has not run yet keeps it back ([sendAllHandshakeReserve]), so the
 * two numbers the user sees are always the same number. [peers] is coerced to at least one.
 */
fun handshakeSpendQuote(peers: Int): Zatoshi = Zatoshi(peers.coerceAtLeast(1) * HANDSHAKE_COST_ZAT)

/**
 * How much a New-Chat Send All must leave in the wallet for the handshake that follows it (C-8, owner
 * decision 2026-09-10).
 *
 * A chat that is not Vault bootstraps its tunnel the moment it opens: AndroidChatDetail calls
 * ensureNostrBootstrapSent, which sends an on-chain KEX until the E2E key exchange is complete. A Send
 * All that spent everything left that KEX nothing to spend, so it failed and re-armed. Such a send now
 * keeps one [handshakeSpendQuote] back. Nothing is kept when no paid handshake follows: a Vault chat,
 * a free Open send (it never touches the chain), or a contact whose key exchange is already complete.
 */
fun sendAllHandshakeReserve(
    mode: ConversationMode,
    isFreeOpenSend: Boolean,
    isKeyExchangeComplete: Boolean,
): Zatoshi =
    if (mode == ConversationMode.VAULT || isFreeOpenSend || isKeyExchangeComplete) {
        Zatoshi(0)
    } else {
        handshakeSpendQuote(peers = 1)
    }

/**
 * How many peers are queued for an automatic handshake right now: the same predicate ChatViewModel quotes
 * the consent dialog from (a Tunnel peer whose key exchange is complete and whose ZBOOT has not gone out).
 * The "Move old balance" sheet uses it to say what the moved funds would otherwise pay for.
 */
fun pendingHandshakePeers(prefs: ZchatPreferences): Int =
    runCatching {
        prefs.getTunnelModePeers().count { peer ->
            prefs.getSentNostrBootPubkey(peer) == null && prefs.isE2EKeyExchangeComplete(peer)
        }
    }.getOrDefault(0)

/**
 * Whether a failed automatic handshake may clear its "already sent" marker so the block-driven retry
 * re-fires on the next block.
 *
 * Cancellation is NOT failure. `viewModelScope` can be torn down AFTER the transaction has already been
 * broadcast — submission is followed by more suspending work (refreshing transactions), so a cancel
 * arriving in that window aborts a send whose money has already left the wallet. Re-arming there
 * discards the success latch, and the next block sends the same handshake again and charges another
 * [HANDSHAKE_COST_ZAT]. Only a genuine failure, where nothing was spent, may re-arm.
 */
fun mayReArmHandshakeMarker(error: Throwable): Boolean = error !is kotlinx.coroutines.CancellationException
