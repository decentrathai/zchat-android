package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import javax.crypto.AEADBadTagException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2 of D1-recovery: the restore-simulation PROOF TEST (seed-recoverable-e2e-design.md §5
 * Stage 2). Proves, end-to-end in JVM and with production crypto only, that:
 *
 *   "Device A exchanged E2E1 messages with peer B. A fresh device A' holding NOTHING but
 *    A's wallet seed plus the ON-CHAIN artifacts (KEX/KEXACK memo payloads, their txids,
 *    the convId, the E2E1 ciphertext blobs) re-derives A's exact identity keypair,
 *    rebuilds the ratchet root on an EMPTY store, and decrypts the full history in
 *    both directions."
 *
 * The recovery chain under proof (each step is the REAL production function):
 *   1. [E2ESeedDerivation.deriveKeyPair]         — (seed, index) → A's exact P-256 keypair
 *   2. [E2EEncryption.parseKEXPayloadFull] /
 *      [E2EEncryption.parseKEXAckPayloadFull]    — signature-verified pubkey + address recovery
 *                                                   from the on-chain KEX/KEXACK memos (TOFU rescan
 *                                                   path, ChatViewModel.handleKEXMessage)
 *   3. trial-match (design §2)                   — scan self-KEX pubkey against derived indices
 *   4. [E2EEncryption.deriveSharedSecret]        — ECDH + HKDF-V2, as ChatViewModel.getE2ESharedKey
 *   5. [E2ERatchet.canonicalTxidMaterial] +
 *      [E2ERatchet.deriveRatchetRoot]            — root from (shared, psk?, txid sets), exactly as
 *                                                   ChatViewModel.getOrCreateMessageProcessor
 *   6. [E2EMessageProcessor.decryptIncoming]     — E2E1 wire blobs → plaintext on a FRESH store
 *
 * Negatives prove the other half of the security statement: a WRONG seed derives a different
 * key → different ECDH → different root → AEAD rejects (never a silent wrong decrypt), and a
 * QuantumShield-PSK-mixed root is NOT recoverable from the seed alone (the PSK is a second,
 * deliberately non-derivable secret — design §1 row h / §6 R1) but IS recoverable when the PSK
 * is supplied from a simulated backup blob.
 *
 * NO production code is exercised through Android plumbing here — this is the pure crypto chain,
 * same harness style as [co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchetTest].
 */
class E2ESeedRecoveryProofTest {

    // ------------------------------------------------------------------
    // Fixtures — device A is seed-derived at a non-zero index (exercises trial-match),
    // peer B is a plain random JCA keypair (B's device is not ours to restore).
    // ------------------------------------------------------------------

    private val seedA = ByteArray(64) { (it * 3 + 1).toByte() } // stand-in for the BIP-39 seed
    private val wrongSeed = ByteArray(64) { (it * 5 + 2).toByte() }
    private val indexA = 3

    private val aKeys = E2ESeedDerivation.deriveKeyPair(seedA, indexA)
    private val bKeys = E2EEncryption.generateKeyPair()

    // ------------------------------------------------------------------
    // 1. THE PROOF — seed + on-chain artifacts alone recover identity, root, and history
    // ------------------------------------------------------------------

    @Test
    fun fresh_device_with_only_seed_and_chain_artifacts_rederives_identity_and_decrypts_history() = runTest {
        val artifacts = SeedRestoreHarness.originalSession(aKeys, bKeys, psk = null)

        // A' starts from NOTHING but (seedA, artifacts) — no stored keys, EMPTY ratchet store.
        val restored = SeedRestoreHarness.restore(seedA, artifacts, psk = null)

        // The crux: the private key comes back from the seed, byte-identical. This is what
        // today's SecureRandom generateKeyPair() can never do after a wipe.
        assertEquals(indexA, restored.matchedIndex, "trial-match must recover the historical peer index")
        assertEquals(aKeys.publicKey, restored.keyPair.publicKey, "re-derived public key must be byte-identical")
        assertEquals(aKeys.privateKey, restored.keyPair.privateKey, "re-derived PRIVATE key must be byte-identical")

        // Peer material and addresses are recovered from the signed on-chain memos alone
        // (design §1 rows b/f) — signature verification included via parseKEX*PayloadFull.
        assertEquals(bKeys.publicKey, restored.peerPublicKey, "peer pubkey must TOFU-restore from the KEXACK memo")
        assertEquals(ADDRESS_B, restored.peerAddress, "peer address must be recovered from the signed KEXACK payload")
        assertEquals(ADDRESS_A, restored.ourAddress, "our address must be recovered from our own signed KEX payload")

        // Root convergence: the restored root must equal the root the LIVE peer B computes
        // independently (ECDH symmetry + identical canonical txid material) — the two-device
        // agreement that makes new sends after restore work under the SAME session.
        val liveBRoot = SeedRestoreHarness.deriveRoot(
            ourPrivateKey = bKeys.privateKey,
            peerPublicKey = aKeys.publicKey,
            psk = null,
            kexTxids = artifacts.kexTxids,
            kexAckTxids = artifacts.kexAckTxids,
        )
        assertContentEquals(liveBRoot, restored.rootKey, "restored root must converge with the live peer's root")

        // Decrypt the full captured history on the FRESH, EMPTY store:
        //  - B→A blobs take the incoming path (counter window seeded from nothing — by design),
        //  - A→B blobs take the stateless own-outgoing path (E2ERatchet re-scan semantics).
        artifacts.wireBtoA.forEachIndexed { i, wire ->
            assertEquals(MESSAGES_B_TO_A[i], restored.processor.decryptIncoming(wire), "B→A message $i must recover")
        }
        artifacts.wireAtoB.forEachIndexed { i, wire ->
            assertEquals(MESSAGES_A_TO_B[i], restored.processor.decryptIncoming(wire), "own A→B message $i must recover")
        }
    }

    // ------------------------------------------------------------------
    // 2. Trial-match — the index recovery step in isolation
    // ------------------------------------------------------------------

    @Test
    fun trial_match_finds_exact_index_from_self_kex_pubkey_and_rejects_foreign_seed() {
        // The self-sent KEX memo carries A's historical pubkey; matching it against derived
        // candidates recovers the index (bounded scan, design §2 — NOSTR #252 pattern).
        for (idx in listOf(0, 1, 5, 11)) {
            val historicalPub = E2ESeedDerivation.deriveKeyPair(seedA, idx).publicKey
            assertEquals(idx, SeedRestoreHarness.trialMatchIndex(seedA, historicalPub))
        }
        // A different seed derives an unrelated key at EVERY index — no match, never a
        // false positive (so restore can never silently pick a wrong key).
        assertNull(
            SeedRestoreHarness.trialMatchIndex(wrongSeed, aKeys.publicKey),
            "foreign seed must not trial-match any index",
        )
    }

    // ------------------------------------------------------------------
    // 3. NEGATIVE — wrong seed: different key → different ECDH/root → AEAD rejects
    // ------------------------------------------------------------------

    @Test
    fun wrong_seed_fails_to_recover_any_history() = runTest {
        val artifacts = SeedRestoreHarness.originalSession(aKeys, bKeys, psk = null)

        // Step 1 of restore already fails honestly: no derived index matches the on-chain pubkey.
        assertNull(SeedRestoreHarness.trialMatchIndex(wrongSeed, aKeys.publicKey))

        // Even FORCING the historical index with the wrong seed (i.e. skipping the match guard)
        // must not decrypt anything: the re-derived key differs, so ECDH differs, so the root
        // differs, so every AES-GCM tag check fails. Assert the failure MODE is AEAD rejection —
        // cryptographic refusal, not a formatting accident.
        val forgedKeys = E2ESeedDerivation.deriveKeyPair(wrongSeed, indexA)
        assertNotEquals(aKeys.publicKey, forgedKeys.publicKey, "wrong seed must derive a different key")

        val forged = SeedRestoreHarness.restore(seedA = wrongSeed, artifacts = artifacts, psk = null, forcedKeyPair = forgedKeys)
        assertNull(forged.matchedIndex, "forced restore must record that no index matched")
        assertFalse(
            forged.rootKey.contentEquals(
                SeedRestoreHarness.deriveRoot(aKeys.privateKey, bKeys.publicKey, null, artifacts.kexTxids, artifacts.kexAckTxids),
            ),
            "wrong-seed root must differ from the true root",
        )

        for (wire in artifacts.wireBtoA + artifacts.wireAtoB) {
            val attempt = runCatching { forged.processor.decryptIncoming(wire) }
            assertFalse(attempt.isSuccess, "wrong seed must NOT decrypt: got '${attempt.getOrNull()}'")
            assertTrue(
                attempt.exceptionOrNull() is AEADBadTagException,
                "expected AEADBadTagException, got ${attempt.exceptionOrNull()}",
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. NEGATIVE — QuantumShield: a PSK-mixed root is NOT seed-recoverable without the PSK,
    //    and IS recoverable when the PSK arrives from a (simulated) backup blob.
    // ------------------------------------------------------------------

    @Test
    fun quantum_shield_psk_root_requires_the_psk_not_just_the_seed() = runTest {
        // PSK exactly as production derives it (QuantumShield.derivePSK, peer-bound variant):
        // both 32-byte secrets are random QR-exchanged material — deterministic here for the test.
        val psk = QuantumShield.derivePSK(
            ByteArray(32) { (it + 41).toByte() },
            ByteArray(32) { (it + 87).toByte() },
            ADDRESS_A,
            ADDRESS_B,
        )
        val artifacts = SeedRestoreHarness.originalSession(aKeys, bKeys, psk = psk)

        // Restore WITHOUT the PSK: the identity still re-derives (seed covers that), but the
        // root is wrong — every decrypt must fail AEAD. This is design §1 row h / §6 R1: the
        // PSK is a second secret the seed can never recover.
        val withoutPsk = SeedRestoreHarness.restore(seedA, artifacts, psk = null)
        assertEquals(indexA, withoutPsk.matchedIndex, "identity recovery must still work — only the root is PSK-gated")
        for (wire in artifacts.wireBtoA + artifacts.wireAtoB) {
            val attempt = runCatching { withoutPsk.processor.decryptIncoming(wire) }
            assertFalse(attempt.isSuccess, "PSK-mixed history must NOT decrypt without the PSK: got '${attempt.getOrNull()}'")
            assertTrue(
                attempt.exceptionOrNull() is AEADBadTagException,
                "expected AEADBadTagException, got ${attempt.exceptionOrNull()}",
            )
        }

        // Restore WITH the PSK (simulating the design's B1 backup blob supplying it): full recovery.
        val withPsk = SeedRestoreHarness.restore(seedA, artifacts, psk = psk)
        artifacts.wireBtoA.forEachIndexed { i, wire ->
            assertEquals(MESSAGES_B_TO_A[i], withPsk.processor.decryptIncoming(wire))
        }
        artifacts.wireAtoB.forEachIndexed { i, wire ->
            assertEquals(MESSAGES_A_TO_B[i], withPsk.processor.decryptIncoming(wire))
        }
    }
}

// ======================================================================
// Test-only fixtures — the "on-chain" record and the restore harness.
// The harness may ONLY call production crypto (E2ESeedDerivation, E2EEncryption,
// E2ERatchet/E2EMessageProcessor); it re-implements NOTHING.
// ======================================================================

/** Synthetic but shape-faithful fixtures ("u1…" > 60 chars so KEX address recovery engages). */
private val ADDRESS_A = "u1" + "a".repeat(75)
private val ADDRESS_B = "u1" + "b".repeat(75)
private const val CONV_ID = "CONVSEED"
private val KEX_TXIDS = setOf("11aa22bb33cc44dd55ee66ff77008899kexfroma", "99887766554433221100aabbccddeeffkexfromb")
private val KEXACK_TXIDS = setOf("f0e1d2c3b4a5968778695a4b3c2d1e0fackfromb")
private val MESSAGES_A_TO_B = listOf("A→B: first 🚀", "A→B: second — with unicode ünïcødé", "A→B: third")
private val MESSAGES_B_TO_A = listOf("B→A: reply one", "B→A: reply two 🎉", "B→A: reply three")

/**
 * Everything a rescanning wallet recovers from the CHAIN (plus the seed, held separately).
 * Deliberately contains NO private keys, NO shared secrets, NO ratchet state, NO PSK.
 */
private data class OnChainArtifacts(
    /** A's self-sent KEX memo payload (readable on rescan via the OVK, ChatViewModel.kt:3598-3610). */
    val kexMemo: String,
    /** B's KEXACK memo payload (received-tx scan path). */
    val kexAckMemo: String,
    /** Mined KEX txids (both sides' first-contact KEX — the dual-KEX set). */
    val kexTxids: Set<String>,
    /** Mined KEXACK txids. */
    val kexAckTxids: Set<String>,
    /** ZMSG v4 conversation id (rides inside the signed KEX payloads, design §1 row d). */
    val convId: String,
    /** E2E1 wire blobs, A→B direction, in send order. */
    val wireAtoB: List<String>,
    /** E2E1 wire blobs, B→A direction, in send order. */
    val wireBtoA: List<String>,
)

/** What the fresh device A' reconstructs. */
private data class RestoredDevice(
    val matchedIndex: Int?,
    val keyPair: E2EKeyPair,
    val peerPublicKey: String,
    val peerAddress: String?,
    val ourAddress: String?,
    val rootKey: ByteArray,
    val processor: E2EMessageProcessor,
)

private object SeedRestoreHarness {

    /**
     * The production root chain, exactly as ChatViewModel wires it
     * (getE2ESharedKey:4824-4836 + getOrCreateMessageProcessor:4999-5060).
     */
    fun deriveRoot(
        ourPrivateKey: String,
        peerPublicKey: String,
        psk: ByteArray?,
        kexTxids: Set<String>,
        kexAckTxids: Set<String>,
    ): ByteArray {
        // ChatViewModel.getE2ESharedKey — V2 (HKDF) derivation, NO psk at this layer
        // (the PSK is mixed at the ROOT, ChatViewModel.kt:5040-5049).
        val sharedKey = E2EEncryption.deriveSharedSecret(ourPrivateKey, peerPublicKey, E2EKeyVersion.V2)
        // ChatViewModel.getOrCreateMessageProcessor — convergent, order-independent txid material.
        // legacyScalar = null: a seed-derived-era chat stores its txids in the SET form only.
        val kexMaterial = E2ERatchet.canonicalTxidMaterial(kexTxids, legacyScalar = null)
        val kexAckMaterial = E2ERatchet.canonicalTxidMaterial(kexAckTxids, legacyScalar = null)
        return E2ERatchet.deriveRatchetRoot(
            ecdhSharedSecret = sharedKey,
            psk = psk,
            kexTxid = kexMaterial,
            kexAckTxid = kexAckMaterial,
        )
    }

    /** Processor exactly as production builds it: isLower = ourPub < peerPub (ChatViewModel.kt:5026). */
    private fun processor(rootKey: ByteArray, ourPub: String, peerPub: String): E2EMessageProcessor =
        E2EMessageProcessor(
            rootKey = rootKey,
            convId = CONV_ID,
            isLower = ourPub < peerPub,
            store = InMemoryRatchetStateStore(), // FRESH and EMPTY — nothing survives the wipe
        )

    /** Simulate the ORIGINAL session: A (seed-derived) ↔ B exchange KEX + messages; capture the chain. */
    suspend fun originalSession(aKeys: E2EKeyPair, bKeys: E2EKeyPair, psk: ByteArray?): OnChainArtifacts {
        // The on-chain handshake memos, via the production payload builders (signed address||pubkey).
        val kexMemo = E2EEncryption.createKEXPayload(ADDRESS_A, aKeys.publicKey, aKeys.privateKey)
        val kexAckMemo = E2EEncryption.createKEXAckPayload(ADDRESS_B, bKeys.publicKey, bKeys.privateKey)

        // Each device derives its root INDEPENDENTLY from its own private key + the peer's pubkey.
        val aProcessor = processor(deriveRoot(aKeys.privateKey, bKeys.publicKey, psk, KEX_TXIDS, KEXACK_TXIDS), aKeys.publicKey, bKeys.publicKey)
        val bProcessor = processor(deriveRoot(bKeys.privateKey, aKeys.publicKey, psk, KEX_TXIDS, KEXACK_TXIDS), bKeys.publicKey, aKeys.publicKey)

        val wireAtoB = MESSAGES_A_TO_B.map { aProcessor.encryptOutgoing(it) }
        val wireBtoA = MESSAGES_B_TO_A.map { bProcessor.encryptOutgoing(it) }

        // Harness sanity: the LIVE session must work before we simulate the wipe, or the
        // proof would be vacuous (decrypting garbage nobody could ever read).
        check(bProcessor.decryptIncoming(wireAtoB.first()) == MESSAGES_A_TO_B.first()) { "live session A→B broken" }
        check(aProcessor.decryptIncoming(wireBtoA.first()) == MESSAGES_B_TO_A.first()) { "live session B→A broken" }

        return OnChainArtifacts(
            kexMemo = kexMemo,
            kexAckMemo = kexAckMemo,
            kexTxids = KEX_TXIDS,
            kexAckTxids = KEXACK_TXIDS,
            convId = CONV_ID,
            wireAtoB = wireAtoB,
            wireBtoA = wireBtoA,
        )
    }

    /** Bounded index scan (design §2): derive candidates and match the historical pubkey. */
    fun trialMatchIndex(seedA: ByteArray, historicalPublicKey: String, maxIndex: Int = 256): Int? {
        for (idx in 0 until maxIndex) {
            if (E2ESeedDerivation.deriveKeyPair(seedA, idx).publicKey == historicalPublicKey) return idx
        }
        return null
    }

    /**
     * The RESTORE: from (seed, on-chain artifacts, optional PSK) alone, rebuild identity,
     * peer material, root, and a processor over a FRESH EMPTY [InMemoryRatchetStateStore].
     * [forcedKeyPair] bypasses the trial-match guard for the wrong-seed negative.
     */
    suspend fun restore(
        seedA: ByteArray,
        artifacts: OnChainArtifacts,
        psk: ByteArray?,
        forcedKeyPair: E2EKeyPair? = null,
    ): RestoredDevice {
        // (b)+(f): peer pubkey + peer address from the KEXACK memo — senderAddress = null forces
        // recovery from the SIGNED payload itself, the true fresh-install TOFU path (no prior
        // convId mapping exists after a wipe). Signature verification happens inside.
        val peerKex = E2EEncryption.parseKEXAckPayloadFull(artifacts.kexAckMemo, senderAddress = null)
            ?: error("KEXACK memo failed signature verification — cannot restore peer material")

        // Our own historical pubkey + address from our self-sent KEX memo (readable on rescan via
        // the OVK). This is what the trial-match scans against.
        val selfKex = E2EEncryption.parseKEXPayloadFull(artifacts.kexMemo, senderAddress = null)
            ?: error("self-KEX memo failed signature verification — cannot recover historical pubkey")

        // (a): re-derive OUR private key from the seed — the single piece a rescan cannot recover.
        val matchedIndex = trialMatchIndex(seedA, selfKex.publicKey)
        val keyPair = forcedKeyPair
            ?: matchedIndex?.let { E2ESeedDerivation.deriveKeyPair(seedA, it) }
            ?: error("no derived index matches the on-chain pubkey — wrong seed?")

        // (c): rebuild the root from (re-derived priv, TOFU peer pub, on-chain txid sets, psk?).
        val rootKey = deriveRoot(keyPair.privateKey, peerKex.publicKey, psk, artifacts.kexTxids, artifacts.kexAckTxids)

        return RestoredDevice(
            matchedIndex = matchedIndex,
            keyPair = keyPair,
            peerPublicKey = peerKex.publicKey,
            peerAddress = peerKex.senderAddress,
            ourAddress = selfKex.senderAddress,
            rootKey = rootKey,
            processor = processor(rootKey, keyPair.publicKey, peerKex.publicKey),
        )
    }
}
