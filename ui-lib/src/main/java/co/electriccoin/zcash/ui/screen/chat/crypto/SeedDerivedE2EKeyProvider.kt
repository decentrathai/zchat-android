package co.electriccoin.zcash.ui.screen.chat.crypto

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The narrow slice of ZchatPreferences the seed-derived key-generation path needs.
 * ZchatPreferences extends this, so the production prefs object IS an [E2EKeyGenStore];
 * unit tests implement a small in-memory fake instead of the full prefs surface.
 */
interface E2EKeyGenStore {
    /** Our E2E public key for [peerAddress] (Base64), or null if never generated. */
    fun getE2EOurPublicKey(peerAddress: String): String?

    /** Our E2E private key for [peerAddress] (Base64), or null if never generated. */
    fun getE2EPrivateKey(peerAddress: String): String?

    /** Store our E2E keypair for [peerAddress] (both Base64, written atomically). */
    fun setE2EOurKeys(peerAddress: String, ourPublicKey: String, ourPrivateKey: String)

    /** Set the E2E key-derivation version for [peerAddress] (2 = HKDF, all new keys). */
    fun setE2EKeyVersion(peerAddress: String, version: Int)

    /**
     * Key provenance marker (D1-recovery migration guard):
     *  - absent  → pre-migration legacy key (random, not seed-recoverable)
     *  - "random" → post-migration fallback key (seed was unavailable at gen time)
     *  - "seed-v3:<index>" → seed-derived at <index>, recoverable from the seed alone
     * Purely informational for the never-overwrite guard (which keys on EXISTENCE, not
     * provenance); consumed by diagnostics and future recovery stages.
     */
    fun getE2EKeyProvenance(peerAddress: String): String?

    fun setE2EKeyProvenance(peerAddress: String, provenance: String)

    /** Next unallocated per-account seed-derivation index (monotonic, starts at 0). */
    fun getNextE2ESeedKeyIndex(): Int

    fun setNextE2ESeedKeyIndex(value: Int)

    /**
     * R5 send-gate marker (D1-recovery Stage 3, nonce-reuse mitigation). Set true when a peer's
     * key was RE-DERIVED from seed on restore ([SeedDerivedE2EKeyProvider.recoverIfSeedDerived]) —
     * i.e. a peer that may have burned own-outgoing (root, direction, counter) triples on chain.
     * While set AND the send-counter floor is still 0 (rescan hasn't re-established it), sends are
     * deferred so the first post-restore send can never restart at counter 0 and reuse a burned
     * AES-GCM (key, nonce). Cleared once the floor is established, or once a full rescan pass proves
     * the peer had no own-outgoing history. Durable so the gate survives a process death mid-restore.
     */
    fun isE2ERecoveryFloorPending(peerAddress: String): Boolean

    fun setE2ERecoveryFloorPending(peerAddress: String, pending: Boolean)

    /**
     * Install a key re-derived from the seed for [peerAddress] together with its R5 send-gate marker:
     * keypair, key version, provenance and recovery-pending=true. The production store writes all of it in
     * ONE synchronous edit, so a process death can never leave the key on disk without its marker (the
     * next recovery would then refuse the existing key and the gate would never be armed).
     */
    fun setRecoveredE2EKeys(
        peerAddress: String,
        ourPublicKey: String,
        ourPrivateKey: String,
        version: Int,
        provenance: String,
    ) {
        setE2EOurKeys(peerAddress, ourPublicKey, ourPrivateKey)
        setE2EKeyVersion(peerAddress, version)
        setE2EKeyProvenance(peerAddress, provenance)
        setE2ERecoveryFloorPending(peerAddress, true)
    }
}

/**
 * Our own public keys the history scan has seen on chain (verified self-sent KEX/KEXACK payloads), with the
 * conversation each was sent in when the scan could resolve it. Lets key generation recognise a seed index
 * whose key is already in use in this wallet's history. In memory only: the scan re-feeds it every pass.
 */
class HistoricalOwnKeyLedger {
    private val owners = ConcurrentHashMap<String, String>()

    /** Per peer, our key from the newest sighting (mined height) and that height: the key the peer holds now. */
    private val latestByPeer = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Record [publicKey] as ours, sent to [peerAddress] (null when the scan could not resolve the peer).
     * [minedHeight] orders the sightings; at equal heights the later call wins (the scan reads oldest first).
     */
    fun note(publicKey: String, peerAddress: String?, minedHeight: Long = 0L) {
        if (peerAddress != null) {
            owners[publicKey] = peerAddress
            latestByPeer.compute(peerAddress) { _, current ->
                if (current == null || minedHeight >= current.second) publicKey to minedHeight else current
            }
        } else {
            owners.putIfAbsent(publicKey, UNKNOWN_PEER)
        }
    }

    /**
     * Our newest key seen on chain for [peerAddress], or null. After Reset Encryption the peer holds this one, not
     * an older one (P2d).
     */
    fun latestOf(peerAddress: String): String? = latestByPeer[peerAddress]?.first

    /** True when [publicKey] was seen on chain as one of our own keys. */
    fun isHistorical(publicKey: String): Boolean = owners.containsKey(publicKey)

    /** The peer [publicKey] was seen with, or null when unknown or never seen. */
    fun peerOf(publicKey: String): String? = owners[publicKey]?.takeIf { it != UNKNOWN_PEER }

    companion object {
        private const val UNKNOWN_PEER = "\u0000unknown"

        /** The ledger every provider in this process shares (Chat and Group ViewModels alike). */
        val PROCESS = HistoricalOwnKeyLedger()
    }
}

/**
 * Stage 3 of D1-recovery: the single wired path for generating/recovering our per-peer E2E
 * identity keypairs (design: seed-recoverable-e2e-design.md §2/§5 Stage 3; approach proven by
 * [E2ESeedRecoveryProofTest]). Replaces the bare [E2EEncryption.generateKeyPair] calls at the
 * four generation sites (ChatViewModel setE2EEnabled / KEX-received / sendKEXMessage, GroupViewModel
 * createGroup).
 *
 * INVARIANT 1 (load-bearing): a peer that already HAS a stored E2E key is NEVER regenerated or
 * overwritten — not by [getOrCreateKeyPair], not by [recoverIfSeedDerived]. Existing users'
 * established chats (legacy random keys) keep working untouched, and an established seed-derived
 * key is never re-randomized. Both entry points re-check the stored key INSIDE [GEN_MUTEX], so
 * concurrent gen sites (or gen racing recovery) cannot double-generate.
 *
 * New keys are derived via [E2ESeedDerivation.deriveKeyPair] at a monotonically allocated
 * per-account index (design §2 "per-peer index"): peer identity keys stay per-peer unlinkable,
 * "Reset Encryption" (clearE2EKeys) naturally allocates a NEW index on re-gen (generation
 * scoping, D7), and after a wipe the index is re-discovered by trial-matching our on-chain
 * self-KEX pubkey ([recoverIfSeedDerived] — the bounded scan Stage 2 demonstrated, same pattern
 * as NOSTR #252 ourRotationIndexForPubkey). [E2EEncryption.generateKeyPair] remains ONLY as the
 * fail-safe when the seed is unavailable, marked with [PROVENANCE_RANDOM].
 *
 * @param store key/provenance/index persistence (production: ZchatPreferences' e2ePrefs).
 * @param seedProvider returns a FRESH copy of the wallet seed bytes per call, or null when the
 *   wallet isn't available. The provider zeroizes every array it receives (so callers must not
 *   hand out a shared/live buffer).
 * @param historyScanComplete whether the wallet has finished its first full history scan
 *   ([RestoreKexPolicy.isHistoryScanComplete]).
 * @param historicalOwnKeys our own public keys the history scan has seen on chain.
 * @param ownHistoryRead whether a history-scan pass in this process has read the whole history, so that
 *   restore recovery has had its chance to reinstall this wallet's own keys (see [getOrCreateKeyPairForSend]).
 */
class SeedDerivedE2EKeyProvider(
    private val store: E2EKeyGenStore,
    private val seedProvider: suspend () -> ByteArray?,
    private val historyScanComplete: () -> Boolean = { true },
    private val historicalOwnKeys: HistoricalOwnKeyLedger = HistoricalOwnKeyLedger.PROCESS,
    private val ownHistoryRead: () -> Boolean = { true },
) {
    /** Keeps `SeedDerivedE2EKeyProvider(store) { seed }` (a trailing seed lambda) resolving. */
    constructor(store: E2EKeyGenStore, seedProvider: suspend () -> ByteArray?) :
        this(store, seedProvider, { true }, HistoricalOwnKeyLedger.PROCESS, { true })

    /**
     * Memoized trial-match results per historical pubkey ([NO_MATCH] = scanned 0..cap, nothing
     * matched — e.g. a pre-migration random key). Bounds the rescan cost: the full point-multiply
     * scan runs at most once per distinct on-chain pubkey per process.
     */
    private val trialMatchCache = ConcurrentHashMap<String, Int>()

    /**
     * Return the stored keypair for [peerAddress], or generate one: seed-derived at the next
     * allocated index when the seed is available, random fail-safe otherwise. NEVER overwrites
     * an existing key (invariant 1).
     *
     * A5 rules for a new key:
     * - Before the first full history scan has finished ([historyScanComplete] false: a restore, a
     *   rescan or a new wallet's first sync), the key is random. The scan has not yet seen every key this
     *   wallet already used on chain, so no seed index can be allocated safely.
     * - An index whose key the scan has seen on chain in ANOTHER conversation (or one it could not
     *   attribute) is skipped: two chats never share one identity keypair.
     * - An index whose key the scan has seen on chain for THIS peer is that peer's historical key. It is
     *   installed exactly like a recovery, with the R5 send gate armed, because its ratchet root is the
     *   old one and the old device may already have used its low counters.
     * - At [TRIAL_MATCH_CAP] and above the key is random: restore only searches below the cap, so a key
     *   labelled "seed-v3:<index>" there would claim a recoverability it does not have (KR-4).
     */
    suspend fun getOrCreateKeyPair(peerAddress: String): E2EKeyPair = GEN_MUTEX.withLock {
        // INVARIANT 1 — the null-key guard, re-checked under the lock: an existing stored key is
        // returned as-is, never regenerated (legacy random and seed-derived alike).
        existingKeyPair(peerAddress)?.let { return it }

        val seed = if (historyScanComplete()) seedProvider() else null
        if (seed == null || seed.isEmpty()) {
            // Fail-safe: seed unavailable (wallet not yet persisted / provider failure), or the history
            // scan has not finished. A random key keeps E2E working exactly as before this feature; the
            // provenance marker records that this key is NOT seed-recoverable. No seed index is consumed.
            return persistRandom(peerAddress)
        }
        try {
            var index = store.getNextE2ESeedKeyIndex()
            while (index < TRIAL_MATCH_CAP) {
                // Bump BEFORE persisting the key: a crash in between skips an index (harmless — the
                // restore trial scan tolerates gaps), whereas the reverse order could assign the same
                // index — i.e. the same keypair — to two different peers.
                store.setNextE2ESeedKeyIndex(index + 1)
                val keyPair = E2ESeedDerivation.deriveKeyPair(seed, index)
                val provenance = "$PROVENANCE_SEED_V3_PREFIX$index"
                if (!historicalOwnKeys.isHistorical(keyPair.publicKey)) {
                    persist(peerAddress, keyPair, provenance)
                    return keyPair
                }
                if (historicalOwnKeys.peerOf(keyPair.publicKey) == peerAddress &&
                    historicalOwnKeys.latestOf(peerAddress) == keyPair.publicKey
                ) {
                    // This peer's historical key, re-derived by allocation instead of by recovery: same
                    // root, so arm the send gate in the same write as the key. Only its LATEST key (P2d): an
                    // older one was replaced by a Reset Encryption and the peer no longer holds it, so it is
                    // skipped below like any key already used on chain.
                    store.setRecoveredE2EKeys(
                        peerAddress,
                        keyPair.publicKey,
                        keyPair.privateKey,
                        E2EKeyVersion.V2.value,
                        provenance,
                    )
                    return keyPair
                }
                // Another conversation's historical key (never hand it to a second peer), or an older key of this
                // peer that a Reset Encryption replaced.
                index++
            }
            // No recoverable index left below the restore search limit.
            persistRandom(peerAddress)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * The key a send to [peerAddress] (a message, a KEX, turning encryption on) may use: the stored key, or a new
     * one via [getOrCreateKeyPair]. Returns null, creating nothing, while the peer has no key and no history-scan
     * pass has read the whole history yet ([ownHistoryRead]).
     *
     * P2d: after a seed restore every key store is empty until the scan reinstalls our keys from the seed. A key
     * created before that, for a peer we already talked to, is stored first; recovery never replaces a stored key,
     * so the historical key is never reinstalled and that chat's history stays unreadable. The caller waits.
     */
    suspend fun getOrCreateKeyPairForSend(peerAddress: String): E2EKeyPair? {
        GEN_MUTEX.withLock { existingKeyPair(peerAddress) }?.let { return it }
        if (!ownHistoryRead()) return null
        return getOrCreateKeyPair(peerAddress)
    }

    /** True while [getOrCreateKeyPairForSend] would return null for [peerAddress]: no key, history not read yet. */
    fun isKeyCreationWaitingForHistory(peerAddress: String): Boolean =
        store.getE2EOurPublicKey(peerAddress) == null && !ownHistoryRead()

    /**
     * Restore recovery for [peerAddress] from every one of our own verified keys the history scan saw on chain for
     * that peer, oldest first. Only the LATEST is tried (P2d): each Reset Encryption replaced our key and sent the
     * new one, so the peer holds the last. When the latest is not recoverable from the seed (a random key), nothing
     * is installed: an older key is one the peer no longer holds, and the chat would stay broken.
     */
    suspend fun recoverLatestIfSeedDerived(
        peerAddress: String,
        ownKeysOldestFirst: List<String>,
    ): Boolean = ownKeysOldestFirst.lastOrNull()?.let { recoverIfSeedDerived(peerAddress, it) } ?: false

    /**
     * Stage 3b restore recovery: [historicalPublicKey] is OUR pubkey parsed (signature-verified)
     * from an on-chain self-sent KEX/KEXACK memo for [peerAddress], for which we currently hold
     * NO key (fresh restore). Trial-match it against seed-derived indices 0..[TRIAL_MATCH_CAP];
     * on a match, persist the re-derived keypair so the next scan's D7 fold-in accepts our own
     * txids and the convergent root re-derives (Stage 2's proven chain).
     *
     * @return true iff the key was recovered AND stored. Never overwrites an existing stored key
     *   (invariant 1), never stores on a non-match (a foreign/legacy-random pubkey), and is a
     *   clean no-op when the seed is unavailable.
     */
    suspend fun recoverIfSeedDerived(peerAddress: String, historicalPublicKey: String): Boolean =
        GEN_MUTEX.withLock {
            // INVARIANT 1 — recovery must never replace an established key either (whatever won
            // the race — legacy key, fresh gen, earlier recovery — stays).
            if (store.getE2EOurPublicKey(peerAddress) != null) return false

            val matched = trialMatchCache[historicalPublicKey] ?: trialMatch(historicalPublicKey)
            if (matched == NO_MATCH) return false

            val seed = seedProvider() ?: return false
            val keyPair = try {
                if (seed.isEmpty()) return false
                E2ESeedDerivation.deriveKeyPair(seed, matched)
            } finally {
                seed.fill(0)
            }
            // Defense-in-depth: the stored key must be EXACTLY the historical one (the cache/derive
            // path is deterministic, so this can only fail if the caller's pubkey race-changed).
            if (keyPair.publicKey != historicalPublicKey) return false

            // Jump the allocator PAST the recovered index so a brand-new peer can never be
            // assigned a historical peer's keypair.
            if (store.getNextE2ESeedKeyIndex() <= matched) {
                store.setNextE2ESeedKeyIndex(matched + 1)
            }
            // R5 — arm the send gate: this is a RESTORED peer that may have burned own-direction
            // counters on chain. Until the rescan re-establishes the send-counter floor (or proves
            // there was no own-outgoing history), a send here would restart at counter 0 and reuse a
            // burned (root, direction, counter). [isSendBlockedPendingRecovery] defers sends until then.
            // KR-3: the key, its version and provenance and the marker go to disk in ONE write, so a
            // process death can never leave the key installed without its gate.
            store.setRecoveredE2EKeys(
                peerAddress,
                keyPair.publicKey,
                keyPair.privateKey,
                E2EKeyVersion.V2.value,
                "$PROVENANCE_SEED_V3_PREFIX$matched",
            )
            true
        }

    /**
     * R5 SEND GATE (fail-closed). Returns true iff a send to [peerAddress] must be DEFERRED because
     * this is a restored peer whose burned-counter floor is not yet AUTHORITATIVE.
     *
     * The recovery marker is the single source of truth, cleared in exactly ONE place —
     * [reconcileRecoveryMarker], at the END of a fully-converged rescan pass. It is deliberately NOT
     * cleared here on a positive send-counter floor: during the rescan the floor climbs INCREMENTALLY
     * (one step per own-outgoing re-decrypt), so any mid-pass floor K is PARTIAL. A send fired then
     * would restart at a counter in [K, N) that the pre-restore device already BURNED under the
     * byte-identical re-derived root — the exact AES-GCM (root, direction, counter) reuse this gate
     * exists to prevent. Only the pass-end reconcile knows the converged root's floor is complete, so
     * only it may open the gate.
     *
     * - No recovery marker → not a restored-with-history peer → NEVER blocked (a genuinely fresh
     *   outbound peer sends at counter 0 immediately; counter 0 is its unburned first message).
     * - Marker set → BLOCK (caller aborts fail-closed and surfaces "restoring history — try again in a
     *   moment"; it must NOT send at a partial-floor counter or in plaintext) until the first fully
     *   converged rescan pass reconciles the marker away.
     */
    suspend fun isSendBlockedPendingRecovery(peerAddress: String): Boolean = GEN_MUTEX.withLock {
        store.isE2ERecoveryFloorPending(peerAddress)
    }

    /**
     * R5 scan-end reconciliation — the "don't block a no-own-history peer forever" escape. Called
     * for a recovered peer at the END of a rescan pass. If the peer was NOT re-recovered in THIS pass
     * AND the pass read the whole history ([historyComplete], see
     * [RestoreKexPolicy.mayReleaseSendGate]), the pass ran with an already-converged root over every
     * own message, so the send-counter floor is now authoritative: >0 means own history was floored
     * (the gate allows above it), ==0 means the peer had NO own-outgoing history (nothing to collide
     * with — counter 0 is safe). Either way the window is closed, so clear the marker. A peer
     * re-recovered THIS pass keeps the marker (the root only converges by the NEXT full pass), and so
     * does a peer whose pass missed part of the history (KR-3): its floor may still sit below counters
     * the old device already used.
     */
    suspend fun reconcileRecoveryMarker(
        peerAddress: String,
        recoveredThisPass: Boolean,
        historyComplete: Boolean,
    ) {
        if (recoveredThisPass || !historyComplete) return
        // Double-checked: the common case (no marker) skips the lock entirely — this runs per E2E
        // peer at the end of every rescan pass.
        if (!store.isE2ERecoveryFloorPending(peerAddress)) return
        GEN_MUTEX.withLock {
            if (store.isE2ERecoveryFloorPending(peerAddress)) {
                store.setE2ERecoveryFloorPending(peerAddress, false)
            }
        }
    }

    /** Bounded index scan (design §2); caches the result — including a definitive no-match. */
    private suspend fun trialMatch(historicalPublicKey: String): Int {
        val seed = seedProvider() ?: return NO_MATCH // transient: do NOT cache as no-match
        try {
            if (seed.isEmpty()) return NO_MATCH
            var found = NO_MATCH
            for (index in 0 until TRIAL_MATCH_CAP) {
                if (E2ESeedDerivation.deriveKeyPair(seed, index).publicKey == historicalPublicKey) {
                    found = index
                    break
                }
            }
            trialMatchCache[historicalPublicKey] = found
            return found
        } finally {
            seed.fill(0)
        }
    }

    private fun existingKeyPair(peerAddress: String): E2EKeyPair? {
        val publicKey = store.getE2EOurPublicKey(peerAddress) ?: return null
        val privateKey = store.getE2EPrivateKey(peerAddress) ?: return null
        return E2EKeyPair(publicKey, privateKey)
    }

    private fun persistRandom(peerAddress: String): E2EKeyPair {
        val keyPair = E2EEncryption.generateKeyPair()
        persist(peerAddress, keyPair, PROVENANCE_RANDOM)
        return keyPair
    }

    private fun persist(peerAddress: String, keyPair: E2EKeyPair, provenance: String) {
        store.setE2EOurKeys(peerAddress, keyPair.publicKey, keyPair.privateKey)
        store.setE2EKeyVersion(peerAddress, E2EKeyVersion.V2.value)
        store.setE2EKeyProvenance(peerAddress, provenance)
    }

    companion object {
        /** Provenance for a fail-safe random key (seed unavailable at gen time). */
        const val PROVENANCE_RANDOM = "random"

        /** Provenance prefix for a seed-derived key; full form "seed-v3:<index>". */
        const val PROVENANCE_SEED_V3_PREFIX = "seed-v3:"

        /** Bounded restore trial scan (design §2: ≈ peer count + slack, capped). */
        const val TRIAL_MATCH_CAP = 256

        private const val NO_MATCH = -1

        /**
         * PROCESS-WIDE gen lock: multiple ViewModel instances (Chat + Group, plus per-destination
         * ChatViewModel instances) share the same backing prefs, so the check-then-generate must
         * be atomic across ALL of them, not per provider instance.
         */
        private val GEN_MUTEX = Mutex()

        /**
         * Process-wide: has the wallet finished its first full history scan (see
         * [RestoreKexPolicy.isHistoryScanComplete])? Set by the chat history scan. Starts false: until the
         * scan reports, new keys are random (never a seed index that may already be in use on chain).
         */
        val processHistoryScanComplete = AtomicBoolean(false)

        /**
         * Process-wide: has a history-scan pass in this process read the whole history (see [ownHistoryRead])?
         * Set by the chat history scan at the end of a complete, stable pass (the pass in which restore recovery
         * runs), cleared when a restore or rescan starts. Starts false: until then a send never creates a key.
         */
        val processOwnHistoryRead = AtomicBoolean(false)

        /**
         * Production wiring: seed bytes from the persisted wallet, the exact pattern the NOSTR
         * identity already uses (ChatViewModel.getOurNostrPubkey). Any failure — wallet not yet
         * persisted, mnemonic error, even an Error from a native init — degrades to null so key
         * generation falls back to a random key instead of crashing the KEX path. Each call
         * returns a fresh array; the provider zeroizes it after derivation.
         */
        fun create(
            store: E2EKeyGenStore,
            persistableWalletProvider: PersistableWalletProvider,
        ): SeedDerivedE2EKeyProvider =
            SeedDerivedE2EKeyProvider(
                store = store,
                seedProvider = {
                    try {
                        val wallet = persistableWalletProvider.requirePersistableWallet()
                        Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        null
                    }
                },
                historyScanComplete = { processHistoryScanComplete.get() },
                historicalOwnKeys = HistoricalOwnKeyLedger.PROCESS,
                ownHistoryRead = { processOwnHistoryRead.get() },
            )
    }
}
