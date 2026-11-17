package co.electriccoin.zcash.ui.screen.chat.model

import android.util.Log
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P2b (HS-6) — the automatic setups blocked on the handshake question in this process, shared by every chat screen
 * (it was ChatViewModel's companion) and, since ZC1013-X1, read by Advanced Settings. Memory only, as before: nothing
 * of it is stored, so no stored format changes.
 *
 *  - [queued]: the setups the question quotes together;
 *  - [dismissed]: the set the user last answered "Not now" to (the question is not raised again for a subset of it until
 *    a chat is opened);
 *  - [askAgain] (ZC1013-X1): setups the user had answered "Not now" to when the standing consent was turned on in
 *    Advanced Settings. The consent does not cover them: each still asks (the same question, count and price) and is
 *    paid only on Allow there. Allow ([clearAll]) and a wallet change clear it.
 *
 * ZC1013-X2: every "Not now" ([decline]) also puts the declined setups in [askAgain] and stores them
 * ([ZchatPreferences.setDeclinedAutomaticSetups], the encrypted store, one line per setup), and each process reads them
 * back once ([load]) before it decides anything under the standing consent, so a restart never lets the standing
 * consent pay a declined setup. Allow and a wallet change clear them in memory and on disk ([clearAllStored]).
 * ZC1013-X4: the marks are read under one lock and count as read only after a read that worked, so a check made during
 * the read waits for it; while they cannot be read every setup counts as declined until asked (it asks, the standing
 * consent pays none of it) and nothing is written over them; the next check reads them again. A store that cannot be
 * written keeps them in memory.
 */
object AutomaticSetupQueue {
    val queued: MutableSet<QueuedAutomaticSetup> = ConcurrentHashMap.newKeySet()
    val dismissed: MutableSet<QueuedAutomaticSetup> = ConcurrentHashMap.newKeySet()
    val askAgain: MutableSet<QueuedAutomaticSetup> = ConcurrentHashMap.newKeySet()

    // ZC1013-X2: whether this process read the stored marks into [askAgain] (ZC1013-X4: set only after a read that worked).
    private val loaded = AtomicBoolean(false)

    // ZC1013-X4: the last read of the stored marks failed; until one works every setup asks under the standing consent.
    private val unreadable = AtomicBoolean(false)

    // ZC1013-X4: one read at a time; a check made during the read waits for it.
    private val loadLock = Any()

    private const val TAG = "AutomaticSetupQueue"
    private const val SEPARATOR = "\t"
    private const val MAX_STORED = 256

    /** Whether [setup] must ask although the standing consent is on (ZC1013-X1; ZC1013-X2: also after a restart). */
    fun asksUnderConsent(
        setup: QueuedAutomaticSetup,
        prefs: ZchatPreferences,
    ): Boolean {
        load(prefs)
        return unreadable.get() || setup in askAgain
    }

    /**
     * ZC1013-X2: whether any declined setup still asks under the standing consent. ZC-1096: a direct-chat setup whose chat
     * is Open is not counted (an Open chat never asks or pays); its mark stays and counts again once the chat leaves Open.
     */
    fun anyAskingUnderConsent(prefs: ZchatPreferences): Boolean {
        load(prefs)
        return unreadable.get() || askAgain.any { !inOpenChat(it, prefs) }
    }

    /** ZC-1096: a key exchange, key reply or ZBOOT towards a chat held as Open; a store that cannot say counts as not Open. */
    private fun inOpenChat(
        setup: QueuedAutomaticSetup,
        prefs: ZchatPreferences,
    ): Boolean =
        // Not a stored set: the process-state resets of the tests empty every set of this object.
        when (setup.kind) {
            AutomaticSetupKind.KEX, AutomaticSetupKind.KEXACK, AutomaticSetupKind.ZBOOT -> true
            AutomaticSetupKind.GROUP_ACCEPT, AutomaticSetupKind.GROUP_KEY_GRANT -> false
        } &&
            runCatching { prefs.getConversationMode(setup.target) == ConversationMode.OPEN }.getOrDefault(false)

    /**
     * "Not now" (HS-6): the question is not raised again for these queued setups until a chat is opened, and (ZC1013-X2)
     * they keep asking under the standing consent, also after a restart: they are stored.
     */
    fun decline(prefs: ZchatPreferences) {
        load(prefs)
        dismissed += queued
        askAgain += queued.filter { it.kind != AutomaticSetupKind.GROUP_ACCEPT }
        store(prefs)
    }

    /**
     * ZC1013-X2: reads the stored marks once per process. ZC1013-X4: under [loadLock], marked read only after a read that
     * worked; a read that fails sets [unreadable] and is tried again at the next check.
     */
    fun load(prefs: ZchatPreferences) {
        if (loaded.get()) return
        synchronized(loadLock) {
            if (loaded.get()) return
            try {
                val stored = prefs.getDeclinedAutomaticSetups().mapNotNull(::decode)
                askAgain += stored
                unreadable.set(false)
                loaded.set(true)
            } catch (e: Exception) {
                unreadable.set(true)
                Log.w(TAG, "the stored Not now marks could not be read: every setup asks: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun store(prefs: ZchatPreferences) {
        // ZC1013-X4: marks that were not read are never written over with what this process holds.
        if (!loaded.get()) return
        try {
            val marks = askAgain.take(MAX_STORED).mapTo(HashSet(), ::encode)
            if (!prefs.setDeclinedAutomaticSetups(marks)) Log.w(TAG, "the Not now marks were not saved: kept in memory")
        } catch (e: Exception) {
            Log.w(TAG, "the Not now marks were not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun encode(setup: QueuedAutomaticSetup): String =
        listOf(setup.kind.name, setup.target, setup.scope.orEmpty())
            .joinToString(SEPARATOR)

    /** A stored mark, or null for one this version cannot read (it is left out). */
    private fun decode(mark: String): QueuedAutomaticSetup? {
        val parts = mark.split(SEPARATOR)
        if (parts.size != 3 || parts[1].isEmpty()) return null
        val kind = AutomaticSetupKind.entries.firstOrNull { it.name == parts[0] } ?: return null
        if (kind == AutomaticSetupKind.GROUP_ACCEPT) return null
        return QueuedAutomaticSetup(kind, parts[1], parts[2].ifEmpty { null })
    }

    /**
     * ZC1013-X1: the standing consent is about to be turned on in Advanced Settings. The setups the user answered "Not now"
     * to keep asking. @return the setups added, for [undoKeepAsking] when the consent could not be written.
     */
    fun keepDeclinedAsking(): Set<QueuedAutomaticSetup> {
        val declined = declinedNow()
        val added = declined.filterTo(mutableSetOf()) { it !in askAgain }
        askAgain += declined
        return added
    }

    /** ZC1013-X1: the consent was not written: nothing of [keepDeclinedAsking] stays. */
    fun undoKeepAsking(added: Set<QueuedAutomaticSetup>) {
        askAgain -= added
    }

    /** Nothing is queued, declined or held any more, in this process's memory. */
    fun clearAll() {
        queued.clear()
        dismissed.clear()
        askAgain.clear()
    }

    /** Allow, or a wallet change (ZC1013-X2): [clearAll], and the stored marks go too. */
    fun clearAllStored(prefs: ZchatPreferences) {
        clearAll()
        try {
            if (prefs.setDeclinedAutomaticSetups(emptySet())) {
                // ZC1013-X4: the store now holds none, and this process knows it.
                unreadable.set(false)
                loaded.set(true)
            } else {
                Log.w(TAG, "the stored Not now marks were not cleared")
            }
        } catch (e: Exception) {
            Log.w(TAG, "the stored Not now marks were not cleared: ${e.javaClass.simpleName}")
        }
    }

    /**
     * A setup that went, or never may go (our own address, already delivered): it waits for nothing any more. ZC1013-X4:
     * its stored mark goes too.
     */
    fun done(
        setup: QueuedAutomaticSetup,
        prefs: ZchatPreferences,
    ) {
        queued.remove(setup)
        if (askAgain.remove(setup)) store(prefs)
    }

    /**
     * ZC-1096: an attempt stopped because its chat is Open (ZC-880). It leaves the queue only: a stored "Not now" mark
     * stays, so a setup the user declined is still asked about if the chat later leaves Open.
     */
    fun stoppedInOpenChat(setup: QueuedAutomaticSetup) {
        queued.remove(setup)
    }

    /**
     * The setups (never a group join) the user answered "Not now" to: the queued ones declined in this process and
     * (ZC1013-X2) every stored mark, queued or not yet retried in this process.
     */
    private fun declinedNow(): Set<QueuedAutomaticSetup> =
        (queued.filter { it in dismissed } + askAgain).filterTo(mutableSetOf()) { it.kind != AutomaticSetupKind.GROUP_ACCEPT }

    /**
     * ZC1013-X1 — what turning the standing consent on would let ZChat pay by itself now, with the in-chat question's own
     * quote ([automaticSetupTransactions], [handshakeSpendQuote]): the queued setups the user did not decline (never a
     * group join: Join asks) and the Tunnel peers whose ZBOOT is due; and, apart, the transactions of the declined
     * setups, which keep asking.
     */
    fun waiting(prefs: ZchatPreferences): AutomaticSetupWaiting {
        load(prefs)
        val mayPayOnChain: (String) -> Boolean = { peer -> zbootMayPayOnChain(prefs, peer) }
        val declined = declinedNow()
        // ZC1013-X3: the key replies the store says are owed count too, queued in this process or not (after a restart
        // nothing is queued until a retry runs).
        val paying =
            (queued + owedKeyReplies(prefs)).filterTo(mutableSetOf()) {
                it.kind != AutomaticSetupKind.GROUP_ACCEPT && it !in declined
            }
        val due = zbootDuePeers(prefs) - declined.mapTo(mutableSetOf()) { it.target }
        val transactions = if (paying.isEmpty() && due.isEmpty()) 0 else automaticSetupTransactions(paying, due, mayPayOnChain)
        val declinedTransactions = if (declined.isEmpty()) 0 else automaticSetupTransactions(declined, emptyList(), mayPayOnChain)
        return AutomaticSetupWaiting(
            transactions = transactions,
            cost = if (transactions == 0) Zatoshi(0) else handshakeSpendQuote(transactions),
            declined = declinedTransactions,
        )
    }
}

/**
 * ZC1013-X1 — what the turn-on confirm quotes: [transactions] paid setup transactions waiting now, [cost] their price
 * (the question's floor: at least, including fees), and [declined] transactions the user answered "Not now" to, which
 * keep asking.
 */
data class AutomaticSetupWaiting(
    val transactions: Int,
    val cost: Zatoshi,
    val declined: Int,
)

/**
 * ZC1013-X3 — the key replies (KEXACK) the store says are owed: a contact whose key exchange this phone received and
 * stored as the contact's key, no answer to that key sent, the chat not set up over the relays, not one of our own
 * addresses and not a stranger's request waiting for Accept (Accept is its own question). The facts the responder retry
 * (ChatViewModel.retryKexAckIfResponder) reads; a store that cannot answer counts none.
 */
fun owedKeyReplies(prefs: ZchatPreferences): Set<QueuedAutomaticSetup> =
    runCatching {
        prefs.getAllConversationPeerAddresses().filterTo(HashSet()) { peer ->
            val peerKey = prefs.getE2EPeerPublicKey(peer)
            peerKey != null &&
                prefs.getReceivedKexPubkey(peer) == peerKey &&
                prefs.getSentKexAckPubkey(peer) != peerKey &&
                prefs.getPeerNostrPubkey(peer) == null &&
                prefs.getInboundKexAwaitingAccept(peer) == null &&
                !prefs.isSelfAddress(peer)
        }
    }.getOrDefault(emptySet())
        .mapTo(HashSet()) { QueuedAutomaticSetup(AutomaticSetupKind.KEXACK, it) }

/** Tunnel peers whose key exchange is complete and whose ZBOOT has not gone out (HS-6 quote input). */
fun zbootDuePeers(prefs: ZchatPreferences): Set<String> =
    runCatching {
        prefs.getTunnelModePeers().filterTo(HashSet()) { peer ->
            prefs.getSentNostrBootPubkey(peer) == null &&
                prefs.isE2EKeyExchangeComplete(peer) &&
                !prefs.isAutomaticSetupWithdrawn(peer)
        }
    }.getOrDefault(emptySet())

/**
 * Round 10b R10B-1 — whether [peer]'s ZBOOT may go on-chain: not a Shielded chat, or one whose confirmed first-message
 * question quoted it, as the store says. ChatViewModel's own rule also drops an allowance whose withdrawal was not saved;
 * this one then counts that ZBOOT, so a quote from here is never lower than the question's.
 */
fun zbootMayPayOnChain(
    prefs: ZchatPreferences,
    peer: String,
): Boolean = prefs.getConversationMode(peer) != ConversationMode.VAULT || prefs.isZbootQuoted(peer)
