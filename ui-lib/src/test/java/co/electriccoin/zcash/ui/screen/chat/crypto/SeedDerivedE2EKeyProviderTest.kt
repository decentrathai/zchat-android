package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 3a/3b of D1-recovery — the WIRED key-generation path (seed-recoverable-e2e-design.md §5
 * Stage 3): [SeedDerivedE2EKeyProvider] is what ChatViewModel/GroupViewModel's five generation
 * sites call instead of bare [E2EEncryption.generateKeyPair].
 *
 * The two load-bearing invariants under test:
 *  1. NEVER regenerate/overwrite a key for a peer that already HAS a stored E2E key — existing
 *     users' established chats (legacy random keys) keep working untouched; a seed-derived key
 *     is never re-randomized either. Enforced on BOTH the gen path and the recovery path.
 *  2. New keys are seed-derived at a monotonically allocated per-peer index (provenance-marked),
 *     byte-reproducible from (seed, index) — the property that makes a wipe+seed-restore able to
 *     re-derive them (Stage 2 proof), with a random fallback ONLY when the seed is unavailable.
 */
class SeedDerivedE2EKeyProviderTest {

    private val seed = ByteArray(64) { (it * 11 + 5).toByte() }
    private val peerA = "u1" + "p".repeat(75)
    private val peerB = "u1" + "q".repeat(75)

    /** Seed supplier contract: a FRESH copy per call (the provider zeroizes what it receives). */
    private fun seedSupplier(): suspend () -> ByteArray? = { seed.copyOf() }

    // ------------------------------------------------------------------
    // INVARIANT 1 — migration safety: a stored legacy random key is NEVER touched
    // ------------------------------------------------------------------

    @Test
    fun stored_legacy_random_key_is_never_regenerated_and_still_round_trips() = runTest {
        val store = FakeE2EKeyGenStore()
        val legacy = E2EEncryption.generateKeyPair() // pre-migration SecureRandom key
        store.setE2EOurKeys(peerA, legacy.publicKey, legacy.privateKey)
        store.setE2EKeyVersion(peerA, E2EKeyVersion.V2.value)

        val provider = SeedDerivedE2EKeyProvider(store, seedSupplier())
        val result = provider.getOrCreateKeyPair(peerA)

        assertEquals(legacy.publicKey, result.publicKey, "gen path must return the STORED key untouched")
        assertEquals(legacy.privateKey, result.privateKey, "stored PRIVATE key must never be replaced")
        assertEquals(legacy.publicKey, store.getE2EOurPublicKey(peerA), "store must be unmodified")
        assertNull(store.getE2EKeyProvenance(peerA), "a legacy key must not gain a provenance marker")
        assertEquals(0, store.getNextE2ESeedKeyIndex(), "no index may be consumed for an existing key")

        // The untouched legacy key still carries a live session: full E2E1 round-trip both ways.
        val peerKeys = E2EEncryption.generateKeyPair()
        val root = { priv: String, pub: String ->
            E2ERatchet.deriveRatchetRoot(
                ecdhSharedSecret = E2EEncryption.deriveSharedSecret(priv, pub, E2EKeyVersion.V2),
                psk = null,
                kexTxid = "kextx".toByteArray(),
                kexAckTxid = "acktx".toByteArray(),
            )
        }
        val us = E2EMessageProcessor(root(result.privateKey, peerKeys.publicKey), "CONVMIGR", result.publicKey < peerKeys.publicKey, InMemoryRatchetStateStore())
        val them = E2EMessageProcessor(root(peerKeys.privateKey, result.publicKey), "CONVMIGR", peerKeys.publicKey < result.publicKey, InMemoryRatchetStateStore())
        assertEquals("hello from legacy key", them.decryptIncoming(us.encryptOutgoing("hello from legacy key")))
        assertEquals("reply to legacy key", us.decryptIncoming(them.encryptOutgoing("reply to legacy key")))
    }

    // ------------------------------------------------------------------
    // Stage 3a — NEW keys are seed-derived, indexed, provenance-marked, reproducible
    // ------------------------------------------------------------------

    @Test
    fun new_peer_key_is_seed_derived_at_allocated_index_and_byte_reproducible() = runTest {
        val store = FakeE2EKeyGenStore()
        val provider = SeedDerivedE2EKeyProvider(store, seedSupplier())

        val kpA = provider.getOrCreateKeyPair(peerA)
        assertEquals(E2ESeedDerivation.deriveKeyPair(seed, 0), kpA, "first peer must get seed index 0")
        assertEquals("${SeedDerivedE2EKeyProvider.PROVENANCE_SEED_V3_PREFIX}0", store.getE2EKeyProvenance(peerA))
        assertEquals(E2EKeyVersion.V2.value, store.versions[peerA], "seed-derived keys are V2 (HKDF)")
        assertEquals(1, store.getNextE2ESeedKeyIndex(), "index counter must advance")

        val kpB = provider.getOrCreateKeyPair(peerB)
        assertEquals(E2ESeedDerivation.deriveKeyPair(seed, 1), kpB, "second peer must get seed index 1")
        assertNotEquals(kpA.publicKey, kpB.publicKey, "per-peer keys must be unlinkable")

        // Idempotent: a repeat call returns the SAME stored key, consuming nothing (invariant 1).
        assertEquals(kpA, provider.getOrCreateKeyPair(peerA))
        assertEquals(2, store.getNextE2ESeedKeyIndex())

        // THE recovery property: a totally fresh device (new store, new provider, same seed)
        // derives the byte-identical key for the same index.
        val rerun = SeedDerivedE2EKeyProvider(FakeE2EKeyGenStore(), seedSupplier()).getOrCreateKeyPair(peerA)
        assertEquals(kpA, rerun, "same (seed, index) must re-derive the byte-identical keypair")
    }

    @Test
    fun provider_zeroizes_the_seed_bytes_it_receives() = runTest {
        val store = FakeE2EKeyGenStore()
        val handed = mutableListOf<ByteArray>()
        val provider = SeedDerivedE2EKeyProvider(store) { seed.copyOf().also { handed.add(it) } }

        provider.getOrCreateKeyPair(peerA)

        assertTrue(handed.isNotEmpty(), "supplier must have been consulted")
        handed.forEach { buf ->
            assertTrue(buf.all { it == 0.toByte() }, "seed bytes must be zeroized after derivation")
        }
    }

    // ------------------------------------------------------------------
    // Fail-safe: seed unavailable → random fallback, marked, still guarded
    // ------------------------------------------------------------------

    @Test
    fun seed_unavailable_falls_back_to_random_with_provenance_marker() = runTest {
        val store = FakeE2EKeyGenStore()
        val provider = SeedDerivedE2EKeyProvider(store) { null } // wallet/seed not available

        val kp = provider.getOrCreateKeyPair(peerA)
        assertNotEquals(
            E2ESeedDerivation.deriveKeyPair(seed, 0).publicKey,
            kp.publicKey,
            "fallback must not silently pretend to be seed-derived",
        )
        assertEquals(SeedDerivedE2EKeyProvider.PROVENANCE_RANDOM, store.getE2EKeyProvenance(peerA))
        assertEquals(E2EKeyVersion.V2.value, store.versions[peerA])
        assertEquals(0, store.getNextE2ESeedKeyIndex(), "no seed index may be burned by a random fallback")

        // Invariant 1 applies to fallback keys too: never regenerated afterwards.
        assertEquals(kp, provider.getOrCreateKeyPair(peerA))
        assertEquals(kp, SeedDerivedE2EKeyProvider(store, seedSupplier()).getOrCreateKeyPair(peerA))
    }

    // ------------------------------------------------------------------
    // Stage 3b — restore recovery: trial-match the on-chain self-KEX pubkey
    // ------------------------------------------------------------------

    @Test
    fun recovery_rederives_exact_historical_key_and_bumps_index_allocation() = runTest {
        val historicalIndex = 4
        val historical = E2ESeedDerivation.deriveKeyPair(seed, historicalIndex)

        // Fresh device: empty store, index counter at 0 (nothing local survived the wipe).
        val store = FakeE2EKeyGenStore()
        val provider = SeedDerivedE2EKeyProvider(store, seedSupplier())

        assertTrue(
            provider.recoverIfSeedDerived(peerA, historical.publicKey),
            "on-chain self-KEX pubkey at index $historicalIndex must trial-match",
        )
        // R5 — recovery arms the send gate so a send BEFORE the rescan establishes the counter
        // floor can never reuse a burned (root, direction, counter) at counter 0.
        assertTrue(store.isE2ERecoveryFloorPending(peerA), "recovery must arm the R5 send gate")
        assertEquals(historical.publicKey, store.getE2EOurPublicKey(peerA), "recovered pubkey must be byte-identical")
        assertEquals(historical.privateKey, store.getE2EPrivateKey(peerA), "recovered PRIVATE key must be byte-identical")
        assertEquals("${SeedDerivedE2EKeyProvider.PROVENANCE_SEED_V3_PREFIX}$historicalIndex", store.getE2EKeyProvenance(peerA))
        assertEquals(E2EKeyVersion.V2.value, store.versions[peerA])

        // Index allocation must jump PAST the recovered index so a brand-new peer can never be
        // assigned a historical peer's key.
        assertEquals(historicalIndex + 1, store.getNextE2ESeedKeyIndex())
        val kpNew = provider.getOrCreateKeyPair(peerB)
        assertEquals(E2ESeedDerivation.deriveKeyPair(seed, historicalIndex + 1), kpNew)
    }

    @Test
    fun recovery_never_matches_foreign_keys_and_never_overwrites_stored_keys() = runTest {
        val store = FakeE2EKeyGenStore()
        val provider = SeedDerivedE2EKeyProvider(store, seedSupplier())

        // (a) A foreign/random historical key (a pre-migration user's on-chain KEX) must never
        // match ANY index — no false positive, nothing stored, no index consumed.
        val foreign = E2EEncryption.generateKeyPair()
        assertFalse(provider.recoverIfSeedDerived(peerA, foreign.publicKey))
        assertNull(store.getE2EOurPublicKey(peerA))
        assertEquals(0, store.getNextE2ESeedKeyIndex())

        // (b) INVARIANT 1 on the recovery path: a peer that already HAS a stored key is never
        // touched, even when the historical pubkey WOULD trial-match.
        val stored = E2EEncryption.generateKeyPair()
        store.setE2EOurKeys(peerB, stored.publicKey, stored.privateKey)
        val matchable = E2ESeedDerivation.deriveKeyPair(seed, 2)
        assertFalse(provider.recoverIfSeedDerived(peerB, matchable.publicKey), "must refuse: key already stored")
        assertEquals(stored.publicKey, store.getE2EOurPublicKey(peerB), "stored key must be untouched")
        assertEquals(stored.privateKey, store.getE2EPrivateKey(peerB))

        // (c) Seed unavailable → recovery is a clean no-op, not a crash.
        val noSeed = SeedDerivedE2EKeyProvider(FakeE2EKeyGenStore(), { null })
        assertFalse(noSeed.recoverIfSeedDerived(peerA, matchable.publicKey))
    }

    // ------------------------------------------------------------------
    // R5 SEND GATE — close the pre-scan nonce-reuse window. After recovery but BEFORE the peer's
    // own-outgoing on-chain history is rescanned, the send-counter floor is still 0; a send in that
    // window would reuse a burned (root, direction, counter). The gate defers such sends (fail-closed,
    // never counter 0, never plaintext) until the floor is established — WITHOUT blocking a genuinely
    // fresh peer, or a restored peer that had no own-outgoing history, forever.
    // ------------------------------------------------------------------

    @Test
    fun send_gate_defers_restored_peer_until_floor_established_then_resumes_above_burned() = runTest {
        val genStore = FakeE2EKeyGenStore()
        val provider = SeedDerivedE2EKeyProvider(genStore, seedSupplier())
        val convId = "CONVGATE"
        val root = ByteArray(32) { (it * 3 + 9).toByte() }

        // ORIGINAL device burned own-direction counters 0, 1, 2 on chain.
        val original = E2EMessageProcessor(root, convId, isLower = true, InMemoryRatchetStateStore())
        val onChain = (0 until 3).map { original.encryptOutgoing("burned $it") }

        // RESTORE: recovery armed the gate; fresh EMPTY ratchet store (floor 0).
        genStore.setE2ERecoveryFloorPending(peerA, true)
        val restoreStore = InMemoryRatchetStateStore()
        val restored = E2EMessageProcessor(root, convId, isLower = true, restoreStore)

        // BEFORE the floor-establishing rescan: the gate MUST block the send, so we never call
        // encrypt() and never emit counter 0 (the nonce-reuse catastrophe).
        assertTrue(
            provider.isSendBlockedPendingRecovery(peerA),
            "restored peer must be send-gated before the rescan converges (must NOT emit counter 0)",
        )
        assertTrue(genStore.isE2ERecoveryFloorPending(peerA), "marker must persist while still gated")

        // RESCAN: own-outgoing history decrypts (stateless path) and raises the durable floor to 3.
        // A positive floor is NOT sufficient to open the gate — mid-pass it is only PARTIAL — so the
        // peer STAYS blocked until the pass-end reconcile proves the root has converged.
        onChain.forEach { restored.decryptIncoming(it) }
        assertEquals(3L, restoreStore.loadCounterFloor(convId, 0x00), "own-outgoing decrypt establishes floor at highest+1")
        assertTrue(
            provider.isSendBlockedPendingRecovery(peerA),
            "a positive but mid-pass floor must NOT open the gate — only the pass-end reconcile may",
        )

        // PASS-END reconcile with the converged root (peer not re-recovered this pass) → the floor is
        // now authoritative → gate opens and the marker clears.
        provider.reconcileRecoveryMarker(peerA, recoveredThisPass = false, historyComplete = true)
        assertFalse(provider.isSendBlockedPendingRecovery(peerA))
        assertFalse(genStore.isE2ERecoveryFloorPending(peerA), "reconcile must clear the marker once the pass converged")

        // The deferred send now proceeds ABOVE every burned counter — never 0.
        val sent = CiphertextWireFormat.parse(restored.encryptOutgoing("first post-restore"))!!
        assertEquals(3L, sent.counter, "post-restore send must resume above burned counters, never counter 0")
    }

    /**
     * The exact mid-rescan send-race the pre-ship audit caught: the send-counter floor climbs
     * INCREMENTALLY during the converging rescan, so a positive-but-PARTIAL floor must never open the
     * gate. If it did, the send would restart at an already-burned counter under the byte-identical
     * re-derived root — catastrophic AES-GCM (root, direction, counter) reuse.
     */
    @Test
    fun send_gate_stays_blocked_on_a_partial_mid_rescan_floor() = runTest {
        val genStore = FakeE2EKeyGenStore()
        val provider = SeedDerivedE2EKeyProvider(genStore, seedSupplier())
        val convId = "CONVPARTIAL"
        val root = ByteArray(32) { (it * 7 + 1).toByte() }

        // ORIGINAL device burned own-direction counters 0..4 (N = 5).
        val original = E2EMessageProcessor(root, convId, isLower = true, InMemoryRatchetStateStore())
        val onChain = (0 until 5).map { original.encryptOutgoing("burned $it") }

        genStore.setE2ERecoveryFloorPending(peerA, true)
        val restoreStore = InMemoryRatchetStateStore()
        val restored = E2EMessageProcessor(root, convId, isLower = true, restoreStore)

        // MID-PASS: only the first two own-outgoing memos have been re-decrypted → PARTIAL floor 2.
        onChain.take(2).forEach { restored.decryptIncoming(it) }
        assertEquals(2L, restoreStore.loadCounterFloor(convId, 0x00), "fixture: partial mid-pass floor")

        // The gate MUST still block. The old bug cleared here and let the send fire at counter 2 —
        // already burned by the pre-restore device under the identical re-derived root.
        assertTrue(
            provider.isSendBlockedPendingRecovery(peerA),
            "a send during an in-flight rescan (partial floor 2) must be deferred, never emitted at burned counter 2",
        )
        assertTrue(genStore.isE2ERecoveryFloorPending(peerA), "marker must persist through the partial pass")

        // Rescan finishes (floor 5) and the pass-end reconcile runs → gate opens; send resumes at 5.
        onChain.drop(2).forEach { restored.decryptIncoming(it) }
        provider.reconcileRecoveryMarker(peerA, recoveredThisPass = false, historyComplete = true)
        assertFalse(provider.isSendBlockedPendingRecovery(peerA))
        val sent = CiphertextWireFormat.parse(restored.encryptOutgoing("first post-restore"))!!
        assertEquals(5L, sent.counter, "post-restore send must resume above ALL burned counters (0..4)")
    }

    @Test
    fun send_gate_never_blocks_a_fresh_peer_with_no_recovery_marker() = runTest {
        val provider = SeedDerivedE2EKeyProvider(FakeE2EKeyGenStore(), seedSupplier())
        // A genuinely fresh outbound peer (no recovery ever happened) must send immediately —
        // counter 0 is its first-ever, unburned message.
        assertFalse(provider.isSendBlockedPendingRecovery(peerA))
    }

    @Test
    fun send_gate_does_not_block_a_restored_peer_with_no_own_history_forever() = runTest {
        val genStore = FakeE2EKeyGenStore()
        val provider = SeedDerivedE2EKeyProvider(genStore, seedSupplier())
        genStore.setE2ERecoveryFloorPending(peerA, true)

        // The pass that RECOVERED the peer keeps the gate armed: the root is only converged by the
        // NEXT full rescan, so the floor can't be trusted yet — still gated.
        provider.reconcileRecoveryMarker(peerA, recoveredThisPass = true, historyComplete = true)
        assertTrue(genStore.isE2ERecoveryFloorPending(peerA), "must stay gated during the convergence pass")
        assertTrue(provider.isSendBlockedPendingRecovery(peerA))

        // A LATER full rescan completes without re-recovering the peer (root now converged). Its
        // floor stayed 0 → the peer had NO own-outgoing history → nothing to collide with → the
        // marker is reconciled away so the peer is never blocked forever (send allowed at 0).
        provider.reconcileRecoveryMarker(peerA, recoveredThisPass = false, historyComplete = true)
        assertFalse(genStore.isE2ERecoveryFloorPending(peerA), "no-own-history peer must not be blocked forever")
        assertFalse(provider.isSendBlockedPendingRecovery(peerA))
    }

    // ------------------------------------------------------------------
    // A5 (KR-3 / GPT U2) — the send gate opens only on a pass that read the whole history
    // ------------------------------------------------------------------

    /**
     * KR-3: the pass-end reconcile cleared the recovery marker on ANY pass after the recovery pass, with no
     * input saying whether that pass saw the whole history. During a restore the SDK is still scanning, so
     * pass B can see our old counters 0..4 but not 5..9; the floor sits at 5, the marker clears, and the next
     * send reuses counter 5 under the byte-identical root, repeating an AES-GCM key and nonce.
     */
    @Test
    fun send_gate_stays_blocked_when_pass_history_is_incomplete() =
        runTest {
            val historical = E2ESeedDerivation.deriveKeyPair(seed, 4)
            val store = FakeE2EKeyGenStore()
            val provider = SeedDerivedE2EKeyProvider(store, seedSupplier())
            assertTrue(provider.recoverIfSeedDerived(peerA, historical.publicKey), "fixture: recovery arms the gate")

            // A LATER pass (not the recovery pass) whose history was incomplete: scan still running, a memo
            // read failed, or one of our own messages to this peer could not be read.
            provider.reconcileRecoveryMarker(peerA, recoveredThisPass = false, historyComplete = false)

            assertTrue(
                provider.isSendBlockedPendingRecovery(peerA),
                "a pass that did not read the whole history must NOT open the send gate: the counter floor may " +
                    "still be below counters the old device already used (AES-GCM key and nonce reuse)",
            )
            assertTrue(store.isE2ERecoveryFloorPending(peerA))

            // The first pass that DID read everything opens it.
            provider.reconcileRecoveryMarker(peerA, recoveredThisPass = false, historyComplete = true)
            assertFalse(provider.isSendBlockedPendingRecovery(peerA))
        }

    /**
     * KR-3 persistence boundary: the recovered keypair went to disk through one editor (apply) and the marker
     * through another (commit). A process death between them leaves the key without its marker; the next
     * recovery refuses the existing key, so the gate is never armed again.
     */
    @Test
    fun recovery_writes_key_and_marker_atomically() =
        runTest {
            val historical = E2ESeedDerivation.deriveKeyPair(seed, 2)
            val store = FakeE2EKeyGenStore()
            val provider = SeedDerivedE2EKeyProvider(store, seedSupplier())

            assertTrue(provider.recoverIfSeedDerived(peerA, historical.publicKey))

            val keyBatches = store.writeBatches.filter { "keys" in it }
            assertEquals(1, keyBatches.size, "the recovered key must be written exactly once")
            assertEquals(
                setOf("keys", "version", "provenance", "recoveryPending"),
                keyBatches.single(),
                "the recovered key, its version, its provenance and the send-gate marker must land in ONE write",
            )
            assertTrue(store.writeBatches.none { it == setOf("recoveryPending") }, "no separate marker write")
        }

    // ------------------------------------------------------------------
    // A5 (KR-4 / GPT U5) — never label a key recoverable that restore cannot find
    // ------------------------------------------------------------------

    /**
     * KR-4: generation allocated indices with no ceiling and labelled every key "seed-v3:<index>", but restore
     * only searches 0 until TRIAL_MATCH_CAP. Chats, groups (GroupViewModel.createGroup goes through the same
     * getOrCreateKeyPair) and Reset Encryption all consume indices, so key 257 claimed to be recoverable and
     * never was.
     */
    @Test
    fun every_key_issued_with_seed_provenance_is_recoverable() =
        runTest {
            for (startIndex in listOf(SeedDerivedE2EKeyProvider.TRIAL_MATCH_CAP - 1, SeedDerivedE2EKeyProvider.TRIAL_MATCH_CAP)) {
                val store = FakeE2EKeyGenStore().apply { nextIndex = startIndex }
                val issued = SeedDerivedE2EKeyProvider(store, seedSupplier()).getOrCreateKeyPair(peerA)
                val provenance = store.getE2EKeyProvenance(peerA)!!

                if (provenance.startsWith(SeedDerivedE2EKeyProvider.PROVENANCE_SEED_V3_PREFIX)) {
                    val restored = SeedDerivedE2EKeyProvider(FakeE2EKeyGenStore(), seedSupplier())
                    assertTrue(
                        restored.recoverIfSeedDerived(peerA, issued.publicKey),
                        "a key labelled $provenance must be recoverable from the seed by restore",
                    )
                } else {
                    assertEquals(SeedDerivedE2EKeyProvider.PROVENANCE_RANDOM, provenance)
                    assertTrue(startIndex >= SeedDerivedE2EKeyProvider.TRIAL_MATCH_CAP, "below the cap keys stay seed-derived")
                }
            }
        }

    // ------------------------------------------------------------------
    // A5 (GPT U1 follow-ups) — generation never silently reuses a key already used on chain
    // ------------------------------------------------------------------

    /**
     * Silent re-derivation: after a restore the allocator starts again at 0, so generating a key for a peer
     * can land exactly on that peer's historical index. The key is then the historical one, the root is
     * byte-identical, but no recovery ran, so the send gate was never armed and the first send restarts at a
     * counter the old device already used.
     */
    @Test
    fun a_generated_key_equal_to_this_peers_historical_key_arms_the_send_gate() =
        runTest {
            val ledger = HistoricalOwnKeyLedger()
            val historical = E2ESeedDerivation.deriveKeyPair(seed, 0)
            ledger.note(historical.publicKey, peerA) // the scan saw our own KEX to peerA carrying this key

            val store = FakeE2EKeyGenStore()
            val provider = SeedDerivedE2EKeyProvider(store, seedSupplier(), historicalOwnKeys = ledger)
            val issued = provider.getOrCreateKeyPair(peerA)

            assertEquals(historical, issued, "fixture: the allocator landed on the historical index")
            assertTrue(
                provider.isSendBlockedPendingRecovery(peerA),
                "a key that is this peer's historical key must arm the send gate exactly like a recovery",
            )
        }

    /**
     * No duplicate own keypair: a historical key seen on chain in ANOTHER conversation (or one the scan could
     * not attribute) must never be handed to a second peer. Two chats sharing one identity key link them.
     */
    @Test
    fun generation_never_hands_out_a_key_already_used_in_another_conversation() =
        runTest {
            val ledger = HistoricalOwnKeyLedger()
            ledger.note(E2ESeedDerivation.deriveKeyPair(seed, 0).publicKey, peerB)
            ledger.note(E2ESeedDerivation.deriveKeyPair(seed, 1).publicKey, null)

            val store = FakeE2EKeyGenStore()
            val provider = SeedDerivedE2EKeyProvider(store, seedSupplier(), historicalOwnKeys = ledger)
            val issued = provider.getOrCreateKeyPair(peerA)

            assertEquals(E2ESeedDerivation.deriveKeyPair(seed, 2), issued, "indices 0 and 1 are taken on chain")
            assertEquals("${SeedDerivedE2EKeyProvider.PROVENANCE_SEED_V3_PREFIX}2", store.getE2EKeyProvenance(peerA))
            assertFalse(provider.isSendBlockedPendingRecovery(peerA), "a genuinely new key is not gated")

            // peerB's history still recovers its own key, and the two chats do not share a keypair.
            assertTrue(provider.recoverIfSeedDerived(peerB, E2ESeedDerivation.deriveKeyPair(seed, 0).publicKey))
            assertNotEquals(store.getE2EOurPublicKey(peerA), store.getE2EOurPublicKey(peerB))
        }

    /**
     * While the first history scan is still running, the scan has not yet seen every key this wallet already
     * used, so a seed index cannot be allocated safely. A key generated then (a send or a new group during
     * the restore) is random: it can never collide with a historical key.
     */
    @Test
    fun keys_generated_before_the_history_scan_completes_are_never_seed_indexed() =
        runTest {
            val store = FakeE2EKeyGenStore()
            var seedReads = 0
            val provider =
                SeedDerivedE2EKeyProvider(
                    store,
                    {
                        seedReads++
                        seed.copyOf()
                    },
                    historyScanComplete = { false },
                    historicalOwnKeys = HistoricalOwnKeyLedger(),
                )

            val issued = provider.getOrCreateKeyPair(peerA)

            assertEquals(SeedDerivedE2EKeyProvider.PROVENANCE_RANDOM, store.getE2EKeyProvenance(peerA))
            assertEquals(0, store.getNextE2ESeedKeyIndex(), "no seed index may be allocated mid-scan")
            assertEquals(0, seedReads, "the seed is not even read mid-scan")
            assertNotEquals(E2ESeedDerivation.deriveKeyPair(seed, 0).publicKey, issued.publicKey)
        }

    // ------------------------------------------------------------------
    // P2d — restore never breaks chat history
    // ------------------------------------------------------------------

    /**
     * P2d (1): a send to a peer we hold no key for yet, made while a restored wallet is still reading its
     * history, created a brand-new key for that peer (sendKEXMessage → getOrCreateKeyPair). The scan then found
     * our historical key on chain, but recovery never replaces a stored key, so the whole history with that
     * peer stayed unreadable. A send must wait for the history instead: no key, no seed index.
     */
    @Test
    fun a_send_before_the_history_is_read_creates_no_key_so_restore_still_recovers_the_historical_key() =
        runTest {
            val historical = E2ESeedDerivation.deriveKeyPair(seed, 0)
            val store = FakeE2EKeyGenStore()
            var historyRead = false
            val provider =
                SeedDerivedE2EKeyProvider(
                    store,
                    seedSupplier(),
                    historyScanComplete = { true },
                    historicalOwnKeys = HistoricalOwnKeyLedger(),
                    ownHistoryRead = { historyRead },
                )

            assertNull(
                provider.getOrCreateKeyPairForSend(peerA),
                "a send before the history is read must not create a key: the historical one could never be recovered",
            )
            assertNull(store.getE2EOurPublicKey(peerA), "no key may be stored for the peer yet")
            assertEquals(0, store.getNextE2ESeedKeyIndex(), "no seed index may be used yet")
            assertTrue(provider.isKeyCreationWaitingForHistory(peerA), "the send path must be told to wait")

            // The history scan reads our own KEX to this peer and reinstalls the historical key.
            assertTrue(provider.recoverIfSeedDerived(peerA, historical.publicKey), "restore recovery must still succeed")
            historyRead = true

            assertFalse(provider.isKeyCreationWaitingForHistory(peerA))
            assertEquals(historical, provider.getOrCreateKeyPairForSend(peerA), "the send now uses the recovered key")
            assertNotNull(provider.getOrCreateKeyPairForSend(peerB), "once the history is read a new contact gets a key")
        }

    /** Control, GREEN before and after: a peer that already has a key never waits for the history. */
    @Test
    fun a_peer_with_a_stored_key_never_waits_for_the_history() =
        runTest {
            val store = FakeE2EKeyGenStore()
            val existing = E2EEncryption.generateKeyPair()
            store.setE2EOurKeys(peerA, existing.publicKey, existing.privateKey)
            val provider =
                SeedDerivedE2EKeyProvider(store, seedSupplier(), historicalOwnKeys = HistoricalOwnKeyLedger(), ownHistoryRead = { false })

            assertFalse(provider.isKeyCreationWaitingForHistory(peerA))
            assertEquals(existing, provider.getOrCreateKeyPairForSend(peerA))
        }

    /**
     * P2d (2): a peer who used Reset Encryption has several of our keys on chain, one per reset. The peer holds
     * the LATEST; the scan reads oldest first and recovered the first (oldest) key it matched, so the rebuilt
     * root never matched the peer's and the history stayed unreadable.
     */
    @Test
    fun restore_recovers_our_latest_key_for_a_peer_not_the_oldest() =
        runTest {
            val beforeReset = E2ESeedDerivation.deriveKeyPair(seed, 0)
            val afterReset = E2ESeedDerivation.deriveKeyPair(seed, 3)
            val store = FakeE2EKeyGenStore()
            val provider = SeedDerivedE2EKeyProvider(store, seedSupplier(), historicalOwnKeys = HistoricalOwnKeyLedger())

            assertTrue(provider.recoverLatestIfSeedDerived(peerA, listOf(beforeReset.publicKey, afterReset.publicKey)))

            assertEquals(
                afterReset.publicKey,
                store.getE2EOurPublicKey(peerA),
                "restore must reinstall the key the peer holds now (our latest), not the one a Reset Encryption replaced",
            )
            assertEquals(afterReset.privateKey, store.getE2EPrivateKey(peerA))
            assertTrue(provider.isSendBlockedPendingRecovery(peerA), "the recovered key still arms the send gate")
        }

    /**
     * P2d (2): when our latest key for a peer is not recoverable from the seed (it was random), an older key of
     * ours is still the wrong key: the peer does not hold it. Recover nothing, so a fresh handshake can run.
     */
    @Test
    fun restore_never_falls_back_to_an_older_key_when_the_latest_is_not_recoverable() =
        runTest {
            val beforeReset = E2ESeedDerivation.deriveKeyPair(seed, 0)
            val afterResetRandom = E2EEncryption.generateKeyPair()
            val store = FakeE2EKeyGenStore()
            val provider = SeedDerivedE2EKeyProvider(store, seedSupplier(), historicalOwnKeys = HistoricalOwnKeyLedger())

            assertFalse(provider.recoverLatestIfSeedDerived(peerA, listOf(beforeReset.publicKey, afterResetRandom.publicKey)))
            assertNull(store.getE2EOurPublicKey(peerA), "a key the peer no longer holds must not be installed")
            assertFalse(provider.isSendBlockedPendingRecovery(peerA))
        }

    /**
     * P2d (2), generation path: after a restore the allocator restarts at 0 and can land on an OLDER key of this
     * same peer (one a Reset Encryption replaced). Installing it repeats the oldest-key bug; only the peer's latest
     * historical key may be installed, and an older one is skipped like any key already used on chain.
     */
    @Test
    fun generation_never_reinstalls_a_key_the_peer_replaced_with_reset_encryption() =
        runTest {
            val ledger = HistoricalOwnKeyLedger()
            val beforeReset = E2ESeedDerivation.deriveKeyPair(seed, 0)
            val afterReset = E2ESeedDerivation.deriveKeyPair(seed, 1)
            // The scan reads oldest first: our KEX before the reset, then our KEX after it.
            ledger.note(beforeReset.publicKey, peerA)
            ledger.note(afterReset.publicKey, peerA)

            val store = FakeE2EKeyGenStore()
            val provider = SeedDerivedE2EKeyProvider(store, seedSupplier(), historicalOwnKeys = ledger)
            val issued = provider.getOrCreateKeyPair(peerA)

            assertEquals(afterReset, issued, "the older key of this peer must be skipped; its latest key is the one it holds")
            assertTrue(provider.isSendBlockedPendingRecovery(peerA), "the peer's historical key arms the send gate")
        }

    // ------------------------------------------------------------------
    // End-to-end: the WIRED path (provider gen + provider recovery) composes with the
    // Stage-2-proven chain — seed + on-chain artifacts alone recover the full history.
    // ------------------------------------------------------------------

    @Test
    fun wired_provider_path_recovers_history_across_a_simulated_restore() = runTest {
        // ORIGINAL device: our key comes from the PRODUCTION gen path (provider, index 0),
        // and our on-chain KEX memo is built by the production payload builder.
        val genStore = FakeE2EKeyGenStore()
        val ourAddress = peerA
        val peerAddress = peerB
        val ours = SeedDerivedE2EKeyProvider(genStore, seedSupplier()).getOrCreateKeyPair(peerAddress)
        val theirs = E2EEncryption.generateKeyPair()
        val kexMemo = E2EEncryption.createKEXPayload(ourAddress, ours.publicKey, ours.privateKey)

        val kexTx = "aa11bb22cc33dd44ee55ff667788kexwired0001".toByteArray()
        val ackTx = "99aa88bb77cc66dd55ee44ff3322ackwired0001".toByteArray()
        val root = { priv: String, pub: String ->
            E2ERatchet.deriveRatchetRoot(E2EEncryption.deriveSharedSecret(priv, pub, E2EKeyVersion.V2), null, kexTx, ackTx)
        }
        val convId = "CONVWIRE"
        val a = E2EMessageProcessor(root(ours.privateKey, theirs.publicKey), convId, ours.publicKey < theirs.publicKey, InMemoryRatchetStateStore())
        val b = E2EMessageProcessor(root(theirs.privateKey, ours.publicKey), convId, theirs.publicKey < ours.publicKey, InMemoryRatchetStateStore())
        val wireAtoB = listOf("wired one", "wired two").map { a.encryptOutgoing(it) }
        val wireBtoA = listOf("wired reply").map { b.encryptOutgoing(it) }

        // RESTORE: fresh store + provider (only the seed survives). The historical pubkey is
        // recovered from the SIGNED on-chain KEX memo — the exact scan-path parse.
        val restoredStore = FakeE2EKeyGenStore()
        val restoredProvider = SeedDerivedE2EKeyProvider(restoredStore, seedSupplier())
        val selfKexPub = E2EEncryption.parseKEXPayloadFull(kexMemo, null)?.publicKey
            ?: error("self-KEX memo failed verification")
        assertTrue(restoredProvider.recoverIfSeedDerived(peerAddress, selfKexPub))

        // Rebuild the root from the RECOVERED private key + TOFU peer pubkey + txids: history
        // decrypts on a FRESH empty ratchet store, both directions.
        val restored = E2EMessageProcessor(
            root(restoredStore.getE2EPrivateKey(peerAddress)!!, theirs.publicKey),
            convId,
            restoredStore.getE2EOurPublicKey(peerAddress)!! < theirs.publicKey,
            InMemoryRatchetStateStore(),
        )
        assertEquals("wired reply", restored.decryptIncoming(wireBtoA[0]))
        assertEquals("wired one", restored.decryptIncoming(wireAtoB[0]))
        assertEquals("wired two", restored.decryptIncoming(wireAtoB[1]))
    }
}

/** Minimal in-memory [E2EKeyGenStore] — models the e2ePrefs subset the provider touches. */
private class FakeE2EKeyGenStore : E2EKeyGenStore {
    val pubs = mutableMapOf<String, String>()
    val privs = mutableMapOf<String, String>()
    val versions = mutableMapOf<String, Int>()
    val provenance = mutableMapOf<String, String>()
    val recoveryPending = mutableMapOf<String, Boolean>()
    var nextIndex = 0

    /** One entry per durable write, naming the fields that write carried (models one prefs editor commit). */
    val writeBatches = mutableListOf<Set<String>>()

    override fun getE2EOurPublicKey(peerAddress: String): String? = pubs[peerAddress]

    override fun getE2EPrivateKey(peerAddress: String): String? = privs[peerAddress]

    override fun setE2EOurKeys(peerAddress: String, ourPublicKey: String, ourPrivateKey: String) {
        pubs[peerAddress] = ourPublicKey
        privs[peerAddress] = ourPrivateKey
        writeBatches += setOf("keys")
    }

    override fun setE2EKeyVersion(peerAddress: String, version: Int) {
        versions[peerAddress] = version
        writeBatches += setOf("version")
    }

    override fun getE2EKeyProvenance(peerAddress: String): String? = provenance[peerAddress]

    override fun setE2EKeyProvenance(peerAddress: String, provenance: String) {
        this.provenance[peerAddress] = provenance
        writeBatches += setOf("provenance")
    }

    override fun setRecoveredE2EKeys(
        peerAddress: String,
        ourPublicKey: String,
        ourPrivateKey: String,
        version: Int,
        provenance: String,
    ) {
        pubs[peerAddress] = ourPublicKey
        privs[peerAddress] = ourPrivateKey
        versions[peerAddress] = version
        this.provenance[peerAddress] = provenance
        recoveryPending[peerAddress] = true
        writeBatches += setOf("keys", "version", "provenance", "recoveryPending")
    }

    override fun getNextE2ESeedKeyIndex(): Int = nextIndex

    override fun setNextE2ESeedKeyIndex(value: Int) {
        nextIndex = value
    }

    override fun isE2ERecoveryFloorPending(peerAddress: String): Boolean = recoveryPending[peerAddress] ?: false

    override fun setE2ERecoveryFloorPending(peerAddress: String, pending: Boolean) {
        recoveryPending[peerAddress] = pending
        writeBatches += setOf("recoveryPending")
    }
}
