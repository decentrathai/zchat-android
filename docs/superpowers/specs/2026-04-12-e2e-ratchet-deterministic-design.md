# E2E Deterministic-Root Ratchet — Design Spec

**Status:** DRAFT — awaiting review
**Author:** Opus (Claude Code session, 2026-04-11)
**Supersedes:** none
**Depends on:** Stage A' (JVM test infra, `java.util.Base64` refactor) — complete
**Implements:** Phase 1.5b of the 2026-04-10 cryptographic hardening plan

---

## 1. Purpose

Upgrade ZCHAT's E2E encryption from static-shared-key to a symmetric ratchet that provides **session-level forward secrecy**, **replay protection**, and **out-of-order delivery** — without breaking restore-from-seed.

### Why now

Current E2E (see `ZMSG_PROTOCOL_SPEC.md` §E2E Encryption) derives a single key from ECDH once per KEX and reuses it for the entire conversation lifetime. Compromise of this key **retroactively decrypts every message ever sent in that conversation**. Every comparable messenger (Signal, SimpleX, Wire, Briar, XMTP) has either Double Ratchet or Megolm-style symmetric ratcheting. ZCHAT is the outlier.

### Why NOT Signal Double Ratchet

A Signal-style Double Ratchet requires **stateful persistence of the root key across messages**. If state is lost (e.g., user reinstalls ZCHAT on a new device and restores from BIP39 seed), the receiver cannot decrypt past messages — the ratchet root is gone. This breaks ZCHAT's first-class promise that "any phone with your seed phrase can recover your entire chat history from the blockchain."

### Goal: deterministic root, stateless ratchet

The root key must be **derivable from the BIP39 seed plus on-chain artifacts that survive restore**. Specifically: the KEX and KEXACK transaction IDs. Any device holding the seed can re-derive the root by scanning the blockchain for the conversation's KEX handshake and reconstructing ECDH locally.

Forward secrecy comes from the **symmetric ratchet step function**, not from root rotation. This is a weaker guarantee than Signal PCS but matches **Megolm** (Matrix/Element) and is the established ceiling for chain-based messengers.

---

## 2. Design Overview

### 2.1 Root Key Derivation

For each conversation between Alice and Bob, the **ratchet root** is:

```
ecdh_shared_secret = ECDH(alice_priv, bob_pub)  // classical P-256
                   = ECDH(bob_priv, alice_pub)   // same value both sides

kex_context = sha256(kex_txid || kexack_txid)   // 32 bytes

root_key = HKDF-SHA256(
    ikm   = ecdh_shared_secret || (psk ?: empty),
    salt  = "ZCHAT_RATCHET_ROOT_V1",
    info  = kex_context,
    length = 32,
)
```

**Properties:**
- Both parties can compute `root_key` given only the seed, peer's KEX pubkey (extracted from the KEX memo), and the two txids.
- On restore, walking the blockchain in block order produces both txids deterministically — the root is reconstructible.
- Optional Quantum Shield PSK is mixed in at root derivation only, not per step.

### 2.2 Per-Direction Sending Chains

Each conversation has **two independent sending chains**:

- `chain_alice_to_bob` (derived from root, advanced on Alice's outgoing messages)
- `chain_bob_to_alice` (derived from root, advanced on Bob's outgoing messages)

```
chain_key_0_A2B = HKDF-SHA256(root_key, salt=∅, info="ZCHAT_CHAIN_A2B_V1", len=32)
chain_key_0_B2A = HKDF-SHA256(root_key, salt=∅, info="ZCHAT_CHAIN_B2A_V1", len=32)
```

Direction is determined by `isLower(alice_pubkey, bob_pubkey)` — lexicographic comparison of the 33-byte compressed secp256r1 public keys. Lower is "A". This is symmetric: both parties compute the same assignment independently.

### 2.3 Symmetric Ratchet Step Function

For counter `n`, the message key and chain-advance key are derived from `chain_key_n`:

```
message_key_n = HMAC-SHA256(chain_key_n, 0x01)   // 32 bytes, used ONCE
chain_key_{n+1} = HMAC-SHA256(chain_key_n, 0x02) // 32 bytes
```

After deriving `message_key_n`, `chain_key_n` is **immediately deleted**. `message_key_n` is used for exactly one AEAD encrypt or decrypt and then deleted.

The one-byte domain separation (`0x01` / `0x02`) prevents cross-use of the same HMAC output as both a message key and a chain key. This matches Signal's construction.

### 2.4 Counter-Based GCM Nonce

```
nonce_12[0..3] = 0x00 0x00 0x00 0x00  // reserved / zero
nonce_12[4..11] = counter_u64_big_endian
```

- Deterministic, never reused within a chain (counter is monotone)
- No birthday collision problem (random-12 has ~2^48 collision probability after 2^48 messages)
- Receiver knows the exact nonce to try for counter `n`

### 2.5 AEAD Envelope

```
plaintext  = <user message bytes>
aad        = direction_byte || counter_u64_be || convId_8bytes
ciphertext = AES-256-GCM(
    key       = message_key_n,
    iv        = counter_nonce_12(n),
    aad       = aad,
    plaintext = plaintext,
)
```

`direction_byte`: `0x00` for A→B, `0x01` for B→A. Binds the ciphertext to its direction so a swapped direction can't be replayed in the opposite chain.
`counter_u64_be`: Binds ciphertext to its counter; replay under a different counter fails auth.
`convId_8bytes`: Binds ciphertext to its conversation; cross-conversation replay fails auth.

### 2.6 Wire Format

Inside the existing ZMSG v4 envelope, the encrypted payload becomes:

```
E2E1:<direction_byte_hex>:<counter_hex>:<ciphertext_base64>
```

- `E2E1:` prefix distinguishes from current `E2E:` (unratcheted V2).
- `direction_byte_hex`: 2 hex chars (`00` or `01`)
- `counter_hex`: 16 hex chars (u64 BE)
- `ciphertext_base64`: standard base64 (matches existing format)

Example: `E2E1:00:0000000000000007:<base64...>`

Sender/receiver dispatch on prefix: `E2E:` = V2 (legacy path), `E2E1:` = V3 ratcheted path. Both sides stay backwards-compatible with unratcheted peers.

### 2.7 KEX Capability Negotiation + Downgrade Defense

KEX payload is extended to carry a capability list, **signed as part of the KEX signature**:

```
Current: KEX:<pubkey_b64>:<sig_b64>
New:     KEX:<pubkey_b64>:<sig_b64>:<caps>

where:
  sig = ECDSA-SHA256-low-s(
      key = sender_priv,
      msg = sender_address || pubkey_bytes || caps_bytes,
  )
  caps = comma-separated ascii tokens (e.g., "r1" for ratchet v1)
```

**Capability tokens for this upgrade:**
- `r1` — ratchet v1 (this spec)
- `aad1` — file-wrap AAD binding (companion feature, same upgrade)
- `lows` — low-s ECDSA enforced on KEX signing

**Handshake:**
1. Alice sends `KEX:<pkA>:<sigA>:r1,aad1,lows`.
2. Bob parses; if Bob's own client supports `r1`, Bob sends `KEXACK:<pkB>:<sigB>:r1,aad1,lows` (same caps structure).
3. Intersection of caps is stored per-conversation. Both sides upgrade to `E2E1:` starting from the NEXT message after KEXACK is seen.
4. Unknown caps are ignored by older clients — forward-compatible.

**Downgrade defense:** A MITM stripping the `caps` field from the KEX payload invalidates the signature, because the signed message includes `caps_bytes`. The receiver rejects the KEX. An attacker can at most prevent the upgrade (by blocking the transaction entirely), not silently downgrade.

**Legacy fallback:** If Bob's KEXACK does not include `caps=r1`, Alice's conversation stays on V2. No silent security loss; Alice's UI may surface a "peer using older client" hint later.

### 2.8 Out-of-Order Handling (Skipped-Key Cache)

Receivers process messages in blockchain order (by mined block height). Within a block, multiple messages in the same direction may have counters `n, n+1, n+2`. The receiver advances its chain in order: derive `message_key_n`, decrypt, advance; derive `message_key_{n+1}`, decrypt, advance; etc.

If a message arrives with counter `m > expected_next`, the receiver **speculatively derives keys for counters `expected_next, expected_next+1, ..., m`**, stores the unused ones in a **skipped-key cache** bounded by `MAX_SKIP = 1000`, and decrypts message `m`. Later, when counters `< m` arrive, they're decrypted from the cache and the used key is immediately evicted.

**DoS bound:** If an attacker replays a captured message with an inflated counter (e.g., claims `counter=1_000_000`), the skipped-key derivation would require 1M HMAC steps. Receiver **rejects** any counter > `expected_next + MAX_SKIP` without processing. This caps work at 1000 HMACs (< 1 ms) per incoming message.

**Persistence model:** Skipped-key cache is **in-memory only, bounded per-conversation to MAX_SKIP entries (LRU)**. On app restart:
- Ratchet counter advances are recomputed deterministically by walking the blockchain in block order.
- Skipped-key cache is rebuilt from scratch. If a message is still missing after a full re-scan, it's treated as undeliverable (the recipient may need to request a retransmit through the UI — future work).

### 2.9 Replay Protection

Because each counter corresponds to a unique `message_key_n`, and the key is deleted after single use, **a replay of the same counter fails to decrypt**: either (a) we've already used & deleted the key, or (b) the AEAD auth check fails because the counter in the AAD doesn't match.

**Seen-counter set per direction:** Maintained in conversation state. Any incoming ciphertext whose counter is already in the seen-set is rejected immediately (before attempting decrypt, to save CPU).

**Restore scenario:** On restore, the seen-counter set is initially empty. A replay of an old captured message would be accepted because the counter isn't yet seen. **BUT** the ratchet is deterministic — rebuild walks messages in block order, so genuine messages always arrive first. A replayed message would decrypt successfully (giving the same plaintext the user already received), which is not a security violation; replay visibility is bounded by the recipient not processing the same mined tx twice (already enforced at the ZMSG layer via block scanning idempotence).

### 2.10 Chunked Message Handling (ZMSG v4c)

Each chunk is a separate ZMSG memo in its own transaction. Each chunk gets its own counter and its own AEAD envelope. The plaintext is assembled by the application layer AFTER all chunks decrypt.

Rationale: treating each chunk as its own ratchet step is simpler than chunking ciphertext, and the cost is just a few extra ratchet advances per multi-chunk message — negligible.

---

## 3. State Model

### 3.1 Persisted State (per conversation, per direction)

```
RatchetConversationState {
  conv_id: 8 bytes
  caps: Set<String>  // negotiated from KEX (e.g., {"r1","aad1","lows"})
  direction_A2B: {
    next_counter: u64        // next counter to use when sending
    seen_counters: Set<u64>  // bounded ring buffer, last 10000 received
  }
  direction_B2A: {
    next_counter: u64
    seen_counters: Set<u64>
  }
}
```

Stored as JSON blob in `EncryptedSharedPreferences` keyed by `ratchet_state_<conv_id>`.

**Not persisted:**
- `root_key` — re-derived from seed + KEX context on demand
- `chain_key_*` — re-derived by walking from `chain_key_0` to `next_counter` on demand
- `message_key_*` — derived once per encrypt/decrypt, zeroed immediately after

This keeps persisted state small (~200 bytes per conversation) and avoids the "lost state on reinstall" failure mode.

### 3.2 In-Memory State (per conversation, ephemeral)

```
RatchetConversationRuntime {
  root_key: ByteArray(32)           // cached after first derivation
  current_chain_A2B: ByteArray(32)  // cached at `next_counter_A2B - 1`
  current_chain_B2A: ByteArray(32)  // cached at `next_counter_B2A - 1`
  skipped_keys_A2B: LinkedHashMap<u64, ByteArray>  // bounded MAX_SKIP=1000
  skipped_keys_B2A: LinkedHashMap<u64, ByteArray>
  mutex: Mutex                       // single-writer per conversation
}
```

On app start, runtime is empty. First encrypt/decrypt for a conversation triggers re-derivation of `root_key` and walks the chain to `next_counter`.

### 3.3 Mutex Discipline

All ratchet state mutations are guarded by a per-conversation `Mutex`. This prevents races between concurrent send/receive operations from desyncing counter advances.

---

## 4. Class Design

### 4.1 `E2ERatchet` class

```kotlin
class E2ERatchet(
    private val state: RatchetStateStore,
    private val seedProvider: SeedProvider,
) {
    suspend fun encrypt(
        convId: String,
        peerPubkey: ByteArray,
        kexTxid: ByteArray,
        kexAckTxid: ByteArray,
        psk: ByteArray?,
        plaintext: ByteArray,
    ): Result<Ciphertext, RatchetError>

    suspend fun decrypt(
        convId: String,
        peerPubkey: ByteArray,
        kexTxid: ByteArray,
        kexAckTxid: ByteArray,
        psk: ByteArray?,
        ciphertext: Ciphertext,
    ): Result<ByteArray, RatchetError>
}

data class Ciphertext(
    val direction: Byte,
    val counter: Long,
    val bytes: ByteArray,
)

sealed class RatchetError {
    object CounterOutOfRange       : RatchetError()  // > MAX_SKIP
    object CounterAlreadySeen      : RatchetError()  // replay
    object DecryptionFailed        : RatchetError()  // GCM auth
    object StateCorrupt            : RatchetError()  // internal invariant violation
    data class CryptoError(val cause: Throwable) : RatchetError()
}
```

Note: `RatchetError` is a **sealed class**, NOT `null` returns. Per Stage A' finding: crypto-critical paths must fail LOUD. Callers unwrap via exhaustive `when`.

### 4.2 `RatchetStateStore` interface

```kotlin
interface RatchetStateStore {
    suspend fun load(convId: String): RatchetConversationState?
    suspend fun save(state: RatchetConversationState)
    suspend fun mutexFor(convId: String): Mutex
}
```

Production impl: `EncryptedPrefsRatchetStateStore` backed by `EncryptedSharedPreferences`.
Test impl: `InMemoryRatchetStateStore` for unit tests.

### 4.3 Integration with `ChatViewModel`

`ChatViewModel` gets a new injected `E2ERatchet` dependency. The existing `E2EEncryption.encryptE2E` / `decryptE2E` paths are preserved for V2 backwards compat. When sending a message:

```kotlin
if (conversation.caps.contains("r1")) {
    ratchet.encrypt(...) // returns "E2E1:..." payload
} else {
    E2EEncryption.encryptE2E(...) // existing, returns "E2E:..." payload
}
```

On receive, dispatch on prefix: `E2E1:` → `ratchet.decrypt`, `E2E:` → legacy.

`ChatViewModel` must NOT grow — the ratchet is its own class; `ChatViewModel` just routes.

---

## 5. Test Plan

All tests live in `src/test/java/...crypto/E2ERatchetTest.kt` as pure JVM unit tests (enabled by Stage A'). No androidTest needed. Target: ~25 tests covering all significant state transitions.

### 5.1 Happy-path tests

1. `encrypt_decrypt_single_message_roundtrip`
2. `encrypt_decrypt_three_messages_in_order`
3. `alice_sends_one_bob_sends_one_both_decrypt`

### 5.2 Ratchet-step properties

4. `message_keys_are_deleted_after_use` (attempt to decrypt same counter twice → `CounterAlreadySeen`)
5. `chain_keys_advance_deterministically` (same root → same chain_key_n for any n)
6. `counter_zero_starts_from_chain_key_zero`
7. `root_key_deterministic_from_seed_and_kex_context`
8. `root_key_differs_when_psk_present_vs_absent`
9. `a2b_and_b2a_chains_derive_distinct_keys`

### 5.3 Out-of-order delivery

10. `out_of_order_2_then_1_both_decrypt`
11. `skip_to_counter_5_then_decrypt_1_through_4_from_cache`
12. `skipped_key_evicted_after_single_use`

### 5.4 Bounded skip (DoS resistance)

13. `counter_at_max_skip_boundary_accepted` (counter = next + 999 → accepted)
14. `counter_above_max_skip_rejected` (counter = next + 1000 → `CounterOutOfRange`)
15. `skipped_key_cache_bounded_by_max_skip`

### 5.5 Replay protection

16. `replay_of_seen_counter_rejected` (`CounterAlreadySeen`)
17. `replay_with_modified_aad_fails_decrypt` (`DecryptionFailed`)
18. `cross_conversation_replay_fails_aad_check` (same ciphertext bytes, different convId)
19. `cross_direction_replay_fails_aad_check` (swap direction byte)

### 5.6 Restore/rebuild from deterministic root

20. `restore_simulation_rebuilds_counter_from_blockchain_walk` (fresh state + 10 messages in order → final `next_counter = 10`)
21. `restore_simulation_reaches_same_keys_as_original` (encrypt 5, discard state, re-derive, decrypt all 5)

### 5.7 KEX capability negotiation

22. `kex_caps_negotiated_intersection_stored`
23. `kex_signature_covers_caps_field` (signature verification against payload with stripped caps → fail)
24. `kex_without_r1_cap_falls_back_to_V2_path`

### 5.8 State mutation safety

25. `concurrent_encrypt_and_decrypt_via_mutex_no_desync` (coroutine stress test, 100 iterations)

---

## 6. Out of Scope (Explicitly Deferred)

- **Real post-quantum KEX** (ML-KEM-768 hybrid) — deferred to a future "Real PQ" milestone. Quantum Shield PSK is retained as-is for interim augmentation.
- **Full Signal Double Ratchet with DH chain rotation** — incompatible with restore-from-seed. This Megolm-style symmetric ratchet is the chosen ceiling.
- **Multi-device sync** — running the same seed on two phones will desync counters. Documented limitation in the spec.
- **Group ratcheting** — current group protocol uses its own epoch/seq mechanism. Group hardening is Phase 1.5d.
- **Authenticated Reply Addresses** — waits for ZIP-231 at NU7.

---

## 7. Migration & Rollout

1. **Ship Stage B as v2.11.0** — wire-format break for *new* conversations only. Existing conversations stay on V2.
2. **Capability negotiation is the upgrade path.** On first KEX after both parties upgrade, caps intersection includes `r1` and the conversation auto-upgrades starting from the next message.
3. **No data migration** — root key is re-derived deterministically from the (already on-chain) KEX and seed. Nothing to migrate.
4. **Fallback:** if a user ever needs to revert, removing `r1` from their advertised caps forces new KEXes back to V2. Existing `E2E1:` messages in a conversation will remain decryptable by upgraded clients only; this is an accepted one-way move.

---

## 8. Review Checklist (for plan-document-reviewer subagent)

Before implementation begins, please validate:

- [ ] Root derivation is actually deterministic across restore — is `kex_context = sha256(kex_txid || kexack_txid)` reconstructible from blockchain scan alone? (Both txids are discoverable by scanning memos addressed to / from the user's address.)
- [ ] Is the two-chain derivation symmetric? (Both parties compute `isLower(alice_pk, bob_pk)` identically → agree on which chain is A2B vs B2A.)
- [ ] Does `E2E1:` wire format fit in the 512-byte memo budget? (Prefix ~30 bytes + direction 2 + counter 16 + ciphertext base64 ≈ roughly 60-byte overhead. Remaining budget: ~450 bytes for plaintext. Chunked flow continues to work.)
- [ ] Is `MAX_SKIP=1000` the right bound? Too low → breaks on legitimate long offline periods. Too high → DoS.
- [ ] Is the seen-counter set **per direction** or **shared**? (Per direction — because directional chains are independent, a counter `n` in A2B is unrelated to counter `n` in B2A.)
- [ ] Does the AAD binding prevent all cross-context replay I care about? (convId, direction, counter — missing anything?)
- [ ] Does the KEX caps signature cover the caps field with no trailing ambiguity? (Yes — signature is over `address || pubkey || caps_bytes`, byte-exact.)
- [ ] Is there any code path where a ratchet error could be silently swallowed? (No — `RatchetError` is a sealed class, callers must exhaust; integration in `ChatViewModel` must use `when` not `catch → null`.)
- [ ] Have I covered the test cases for every state transition? (25 listed — any gaps?)
- [ ] Are there any Android/JVM differences that would cause tests to pass in JVM but fail on-device? (secp256k1 JNI handles both via Stage A' setup; AES-GCM is standard JCA; no Android-only APIs used.)
- [ ] Does the Ratchet class have any hidden dependency on `ChatViewModel`? (No — injection goes the other way; Ratchet depends on `RatchetStateStore` and `SeedProvider`, both abstracted.)
- [ ] Is backwards compatibility preserved when one party upgrades and the other doesn't? (Yes — caps negotiation fails → V2 legacy path stays active.)
- [ ] Have I documented Known Gaps and the threat-model ceiling? (See §6 and `ZMSG_PROTOCOL_SPEC.md` Security Properties table.)

---

## 9. Implementation Order (TDD)

Per test-driven-development skill: **tests first, watch RED, implement minimal, watch GREEN, refactor, commit.**

1. Write `RatchetStateStore` interface + `InMemoryRatchetStateStore` (test helper)
2. Write failing tests §5.1 (1 of 3) → run → RED → implement minimal encrypt/decrypt → GREEN → refactor
3. Repeat for §5.1 (2 of 3), (3 of 3)
4. Proceed through §5.2 — §5.8 in order
5. After all 25 tests green: integrate into `ChatViewModel` (one test per integration point)
6. After integration: end-to-end manual test on Honor (two instances, send/receive, observe ratchet advancement via log)
7. Production compile + full JVM test suite green → Stage B ready to ship
