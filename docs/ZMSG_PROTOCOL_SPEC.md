# ZMSG Protocol Specification

## Overview

ZMSG (Zcash Message) is a protocol for sending messages via Zcash shielded transaction memos. It enables private, blockchain-based messaging with ~75-second delivery latency (one Zcash block).

Each message costs ~0.0001 ZEC (network fee + platform fee).

---

## Protocol Versions

| Version | Status | Description |
|---------|--------|-------------|
| v2 | Legacy | Full address in every message |
| v3 | Deprecated | Hash-based sender ID with REF threading |
| v4 | **Current** | Conversation ID-based threading with hash fallback |

---

## Security Model

### Sender Identification (NOT Authentication)

ZMSG does **not** cryptographically authenticate senders. Instead it uses layered heuristic routing:

| Layer | Mechanism | Strength |
|-------|-----------|----------|
| 1. Conversation ID | 8-char random ID stored bidirectionally | Primary routing — reliable within established conversations |
| 2. Sender hash | SHA-256 truncated to 8 bytes (v4) or 6 bytes (v3 legacy) | Fallback if convID lookup fails |
| 3. Full address | Included in INIT messages only | Establishes identity at conversation start |
| 4. Out-of-band exchange | Users share addresses via QR scan / paste | UX guard — only contacts' messages are displayed |

**What senderHash IS:** A compact identifier (64 bits) for space-efficient sender tagging in 512-byte memos.

**What senderHash is NOT:** Authentication. Anyone who knows a Zcash address can compute its hash. No private-key proof is involved.

**Practical mitigations:**
- Conversation IDs are never published; they exist only inside encrypted memos
- Injecting a fake message requires spending real ZEC (~0.0001 per message)
- Address cache won't overwrite existing hash→address mappings (collision guard)
- Messages from unknown addresses are not displayed unless the user explicitly adds the contact

**Tracked future fix:** [Authenticated Reply Addresses](https://zips.z.cash/draft-ecc-authenticated-reply-addrs) (requires [ZIP-231 memo bundles](https://zips.z.cash/zip-0231) for >512-byte payloads). See [GitHub issue #8](https://github.com/decentrathai/zchat-android/issues/8).

### E2E Encryption (Optional Layer)

An optional end-to-end encryption layer can be negotiated per-conversation via KEX messages:

- **Key Exchange:** ECDH with secp256r1 (P-256)
- **Key Derivation:** HKDF (RFC 5869) with HMAC-SHA256, two versions:
  - V1 (legacy): SHA-256 only
  - V2 (current): Proper HKDF Extract + Expand, with optional Quantum Shield PSK mixing
- **Encryption:** AES-256-GCM (12-byte nonce, 128-bit auth tag)
- **Message format:** `E2E:<nonce_base64>:<ciphertext_base64>`

E2E is layered on top of ZMSG — the encrypted payload replaces the plaintext message content.

### Quantum Shield (Optional Pre-Shared Key)

An optional 32-byte pre-shared key can be exchanged out-of-band (via QR code) between two
parties and mixed into the HKDF extract phase alongside the ECDH shared secret:

```
ikm = ecdh_shared_secret || psk
derived_key = HKDF-SHA256(ikm, salt="ZCHAT_E2E_SALT_V2", info="ZCHAT_E2E_KEY", length=32)
```

**What Quantum Shield IS:** Additional shared entropy that hardens the symmetric key derivation
if the attacker has captured one of the two inputs (ECDH output OR PSK).

**What Quantum Shield is NOT:** A post-quantum key encapsulation mechanism. ECDH over
secp256r1 is still classical and would be broken by a quantum adversary. "Quantum Shield" is
the marketing name; the cryptographic property is "symmetric augmentation of the KDF input."

**Tracked future fix:** Real PQ hybrid via ML-KEM-768 (NIST FIPS 203, CRYSTALS-Kyber) as a
second key encapsulation alongside ECDH, matching Signal PQXDH. Blocked on memo-size
constraints: ML-KEM-768 public key (1184 B) + ciphertext (1088 B) both exceed the 512-byte
memo limit and require chunked KEX. Deferred to a future "Real PQ" milestone.

---

## Security Properties Table

The properties this protocol provides, and the ones it deliberately does NOT. Read this
before making any claim about what ZCHAT protects against.

### What is protected

| Property | Where | How |
|---|---|---|
| **Shielded-pool transport privacy** | Zcash layer | Halo 2 zk-SNARK commitments — network observers see "a shielded tx happened", not sender/recipient/amount |
| **E2E message confidentiality** | E2E layer (when negotiated) | AES-256-GCM with HKDF-derived key from ECDH secp256r1 |
| **E2E message integrity** | E2E layer | GCM 128-bit auth tag on every message |
| **KEX public-key authenticity (to stored address)** | E2E layer | ECDSA signature over `(senderAddress \|\| publicKey)`; verified against the address reported by the transaction sender |
| **Group key confidentiality on distribution** | Group protocol | Per-recipient ECIES wrap of the group key inside `GROUP_INVITE` |
| **Group message integrity** | Group protocol | AES-256-GCM with AAD = `groupId \|\| senderAddress` |
| **Group key rotation support (protocol-level)** | Group protocol | `GROUP_KEY_ROTATE (GY)` message type defined; `epoch` field per message |
| **File content confidentiality (shared via NOSTR)** | File sharing | AES-256-GCM with per-file random key; key wrapped with HKDF-derived key from the E2E shared secret |
| **Collision-resistant sender hash routing** | ZMSG v4 | SHA-256 truncated to 8 bytes / 16 hex = ~64-bit collision resistance |

### What is NOT protected (Known Gaps)

Each item below is a deliberate gap with a named future fix. Do not claim the protocol
provides any of these without citing this table.

| Gap | Why it exists | Canonical future fix |
|---|---|---|
| **Plain-ZMSG sender authentication** | Impossible in 512-byte memos without a crypto signature | [Authenticated Reply Addresses](https://zips.z.cash/draft-ecc-authenticated-reply-addrs) via [ZIP-231 memo bundles](https://zips.z.cash/zip-0231) (NU7) |
| **Forward secrecy within a session** | Current E2E reuses the same derived key for the entire conversation — compromise of the key retroactively decrypts all prior messages | Symmetric ratchet with deterministic root (derivable from BIP39 seed + KEX txids to preserve restore-from-seed semantics). See `docs/superpowers/specs/` for in-progress design. |
| **Post-compromise security (Signal-style healing)** | Deterministic-root design cannot rotate the root without a new KEX | Accepted ceiling: session-level forward secrecy per KEX epoch (Megolm-style). Full PCS would require abandoning restore-from-seed. |
| **Replay protection for ZMSG memos** | Pre-ratchet E2E has no counter; an on-chain memo can be re-broadcast in a new transaction and the recipient decrypts the same plaintext twice | Counter-based GCM nonce + seen-counter window per direction, bundled with the ratchet upgrade above |
| **Post-quantum KEX (HNDL defense)** | secp256r1 ECDH is classical; an adversary capturing memos today could decrypt them after breaking ECDH with a quantum computer | ML-KEM-768 hybrid over chunked KEX. See "Quantum Shield" note above — the current PSK is symmetric augmentation, NOT a PQ KEM. |
| **Native IP-level metadata protection** | No built-in Tor routing. A network observer can see which IP broadcast a Zcash transaction, though the transaction content remains shielded. | Integrate SOCKS5/Tor transport for the lightwalletd gRPC connection. |
| **Device-compromise resistance (hardware-backed keys)** | E2E and identity keys live in Android `EncryptedSharedPreferences` (Tink-wrapped), not in hardware-backed `AndroidKeyStore` | Migrate identity keys to Keystore with key-agreement API. Separate migration story for existing installs. |
| **Multi-device support** | One seed = one ZCHAT identity. Running the same seed on two devices will desync any stateful E2E (ratchet, counters) because both devices advance independently. | Out of scope for now. Documented limitation. |
| **Formal third-party audit** | Repo is currently private; no external review performed | Open-source client + protocol, then commission audit (Cure53 / NCC Group / Trail of Bits have audited SimpleX / Signal / Briar) |

### Threat model summary

ZCHAT is designed for a threat model where:

- **The transport (Zcash network) is adversarial but bounded** — observers see shielded
  transactions, not content or metadata. The SNARK construction is the trust root here.
- **The user's device is trusted** — screen secrecy, biometric unlock, and `FLAG_SECURE` on
  sensitive screens are the local defenses. Rooted or seized devices are explicitly out of
  scope unless the user adopts hardware-backed keys (pending, see Known Gaps).
- **Peer addresses are exchanged out-of-band** — the UX guard (only contacts you've added
  can send you visible messages) catches most unauthenticated injection attempts at the
  application layer, even though ZMSG itself doesn't cryptographically authenticate.
- **Quantum computers capable of breaking ECDH do not exist today** — the "harvest now,
  decrypt later" threat is real but on a 10–15 year horizon. Mitigation is deferred to the
  Real PQ milestone.

Anything outside this threat model must be treated as out of scope. In particular: ZCHAT
is NOT appropriate for protecting messages against a state-level adversary with quantum
capability OR against attackers with physical device access in the absence of hardware-
backed key storage.

---

## ZMSG v4 (Current)

### Conversation ID

- **Length:** 8 characters
- **Charset:** `A-Z, 0-9` (36 symbols)
- **Generation:** `SecureRandom` selection
- **Collision space:** 36^8 ≈ 2.8 trillion combinations
- **Storage:** Bidirectional mapping in EncryptedSharedPreferences:
  - `peer_convid_<address>` → convId (for sending)
  - `conv_<convId>` → peerAddress (for receiving)

### Address Hash

- **Algorithm:** SHA-256, truncated
- **v4 (current):** 8 bytes → 16 hex chars
- **v3 (legacy compat):** 6 bytes → 12 hex chars
- **Purpose:** Fallback sender identification when convID lookup fails

### Message Formats

#### INIT — First message to a new contact
```
ZMSG|v4|<convID>|INIT|<full_sender_address>|<message>
```
Example: `ZMSG|v4|ABC12345|INIT|u1abc...xyz|Hello!`

Available space: ~330 bytes for message content.

#### Reply — Subsequent messages in conversation
```
ZMSG|v4|<convID>|<hash16>|<message>
```
Example: `ZMSG|v4|ABC12345|a1b2c3d4e5f67890|Thanks!`

The 16-char sender hash is included for fallback identification if the receiver's convID mapping is lost. Available space: ~462 bytes for message content.

> **Note:** A legacy reply format without hash (`ZMSG|v4|<convID>|<message>`) is accepted during parsing for backward compatibility but is never generated by current code.

#### KEX — Key Exchange (E2E setup)
```
ZMSG|v4|<convID>|KEX|<hash16>|<kex_payload>
```
Initiates ECDH key exchange. The `kex_payload` contains the public key and signature, created by `E2EEncryption.createKEXPayload()`.

#### KEXACK — Key Exchange Acknowledgment
```
ZMSG|v4|<convID>|KEXACK|<hash16>|<kexack_payload>
```
Sent in response to a valid KEX message to complete the handshake.

#### ADDR — Address Change Notification
```
ZMSG|v4|<convID>|ADDR|<old_hash16>|<new_address>|<signature>
```
Notifies a contact that the sender has changed their address. The signature proves ownership of the new address.

### Chunked Message Formats

For messages exceeding single memo capacity (512 bytes), content is split across multiple transaction outputs.

#### First chunk — INIT
```
ZMSG|v4c|1/N|<convID>|INIT|<address>|<message_part>
```

#### First chunk — Reply
```
ZMSG|v4c|1/N|<convID>|<hash16>|<message_part>
```

#### Continuation chunks (2nd through Nth)
```
ZMSG|v4c|M/N|CONT|<message_part>
```

**Chunk sizes (available bytes for message content):**

| Chunk type | Available space |
|------------|----------------|
| v4 INIT first chunk | 330 bytes |
| v4 Reply first chunk | 462 bytes |
| Continuation chunks | 485 bytes |

**Limits:** Maximum 1000 chunks per message (1000 × ~480 = ~480KB). Enforced to prevent memory exhaustion.

### Message Resolution Flow

**Sending:**
1. Look up existing convID for peer address
2. If none exists, generate new convID via `SecureRandom`, store bidirectionally
3. First message → INIT format (includes full address); subsequent → Reply format (includes hash)
4. If message exceeds 512 bytes, split into chunks

**Receiving:**
1. Parse memo prefix to determine version and type
2. Extract convID
3. Look up peer address via `getPeerByConversationId(convId)`
4. If found → route to existing conversation
5. If not found + INIT → create new mapping, start conversation
6. If not found + Reply → fall back to hash-based address cache lookup

---

## ZMSG v3 (Legacy)

### Message Formats

#### INIT
```
ZMSG|v3|INIT|<full_sender_address>|<message>
```

#### Reply (hash-based)
```
ZMSG|v3|<hash12_or_hash16>|<message>
```

#### REF (transaction-referenced reply)
```
ZMSG|v3|REF|<last_received_txid>|<sender_hash>|<message>
ZMSG|v3|REF|<last_received_txid>|INIT|<sender_address>|<message>
```
Uses the transaction ID of the last received message for conversation threading (solves diversified address problem).

#### RPL (reply to specific message)
```
ZMSG|v3|RPL|<quoted_txid>|INIT|<address>|<message>
ZMSG|v3|RPL|<quoted_txid>|<hash>|<message>
```
Quotes a specific message by its transaction ID.

#### Chunked (v3)
```
ZMSG|v3c|1/N|INIT|<address>|<message_part>
ZMSG|v3c|1/N|<hash>|<message_part>
ZMSG|v3c|M/N|CONT|<message_part>
```

**v3 chunk sizes:** INIT first chunk: 340 bytes, Reply first chunk: 470 bytes, Continuation: 485 bytes.

### Known Issues (Why v4 was created)

1. **Timing:** REF format requires the referenced transaction to already be indexed
2. **Diversified addresses:** Same wallet generates unlinkable addresses, breaking hash routing
3. **Hash collisions:** 12 hex chars (6 bytes) only provides ~2^24 birthday resistance

---

## ZMSG v2 (Legacy)

```
ZMSG|v2|<full_address>|<message>
```
Full address in every message. Wastes ~141 bytes of the 512-byte memo. No longer generated.

---

## Special Message Types

These are standalone memo formats (not wrapped in ZMSG versioned headers).

### Reactions (ZREACT)
```
ZREACT|<target_txid>|<emoji>|<sender_hash>
```

### Read Receipts (ZRCPT)
```
ZRCPT|<target_txid>|<sender_hash>
```

### Payment Requests (ZREQ)
```
ZREQ|<amount_zatoshi>|<sender_hash>|<reason>
```

### User Status (ZSTAT)
```
ZSTAT|<status_text>|<sender_hash>
```

### Time-Locked Messages (ZTL)

| Type | Format |
|------|--------|
| Scheduled | `ZTL\|SCH\|<unlock_timestamp>\|<sender_hash>\|<message>` |
| Block height | `ZTL\|BLK\|<unlock_height>\|<sender_hash>\|<message>` |
| Payment to reveal | `ZTL\|PAY\|<required_zatoshi>\|<sender_hash>\|<message>` |
| Conditional (secret) | `ZTL\|CND\|<answer_hash>\|<hint>\|<sender_hash>\|<message>` |

### Unlock Messages (ZUNLOCK)
```
ZUNLOCK|PAY|<original_txid>|<sender_hash>
ZUNLOCK|CND|<original_txid>|<answer>|<sender_hash>
```

### Remote Kill Signal
```
ZCHAT_DESTROY:<secret_phrase>
```

---

## ZMSG-GROUP Protocol (v3.0)

### Overview

Encrypted group messaging over Zcash. Messages are fan-out (one transaction per member), encrypted with a shared AES-256-GCM group key.

### Protocol Format
```
ZMSG:3.0:GROUP:<type>:<group_id>:<payload>
```

- `<type>` — 2-character message type code
- `<group_id>` — 24-character hex identifier
- `<payload>` — JSON (type-specific)

### Message Types

| Code | Name | Description |
|------|------|-------------|
| GC | GROUP_CREATE | Create new group |
| GI | GROUP_INVITE | Invite member (includes group key) |
| GA | GROUP_ACCEPT | Accept invitation |
| GL | GROUP_LEAVE | Leave group |
| GK | GROUP_KICK | Remove member (admin only) |
| GM | GROUP_MSG | Encrypted group message |
| GY | GROUP_KEY_ROTATE | Rotate encryption key |
| GF | GROUP_INFO | Update group metadata |

### GROUP_INVITE (GI) Payload
```json
{
  "name": "Group Name",
  "inviter": "u1abc...xyz",
  "invitee": "u1def...uvw",
  "members": ["u1abc...", "u1def...", "u1ghi..."],
  "key_epoch": 0,
  "group_key": "<base64_aes256_key>"
}
```

### GROUP_MSG (GM) Payload
```json
{
  "groupId": "abc123...",
  "seq": 1,
  "epoch": 0,
  "sender": "u1abc...xyz",
  "nonce": "<base64_12byte_nonce>",
  "ciphertext": "<base64_aes_gcm_ciphertext>",
  "timestamp": 1705432800
}
```

**Encryption:** AES-256-GCM, 12-byte random nonce, 128-bit auth tag, AAD = `groupId + senderAddress`.

### Fan-Out Transaction Structure
```
Output 1: <amount> ZEC → member1 (memo: GROUP_MSG)
Output 2: <amount> ZEC → member2 (memo: GROUP_MSG)
...
Output N: <amount> ZEC → memberN (memo: GROUP_MSG)
Output N+1: <amount> ZEC → platform_fee_address (no memo)
```

Cost: ~0.0001 ZEC per member per message.

---

## Transaction Structure

### Standard Message (single memo + platform fee)
```
Output 1: <amount> ZEC → recipient (memo: ZMSG message)
Output 2: <amount> ZEC → platform_fee_address (no memo)
```

### Chunked Message (example: 3 chunks)
```
Output 1: <amount> ZEC → recipient (memo: chunk 1/3)
Output 2: <amount> ZEC → recipient (memo: chunk 2/3)
Output 3: <amount> ZEC → recipient (memo: chunk 3/3)
Output 4: <amount> ZEC → platform_fee_address (no memo)
```

### Platform Fee Address
```
u1pm2ju3zua63jtww3zexpahpqlgcu35qqq9hv7689n5luz3pkuefwyk27f4t2r8wf3up8cajkvtelhmnlja4sqk58s6qjavlyf5xv5s2qck6yuc4muee4g86zn8h4uzvdp9q3px2f6clxd46fvcllsphyndl7tvkjzwal68eccq7p4w53
```

---

## Memo Constraints

- **Maximum memo size:** 512 bytes (Zcash protocol limit)
- **Encoding:** UTF-8 (multi-byte characters handled via byte-aware chunking)
- **ZIP-321 URIs:** Multi-output transactions use ZIP-321 payment URI format with Base64 URL-safe encoded memos

---

## Parsing Priority

When parsing incoming memos, formats are checked in this order:

1. GROUP protocol (`ZMSG:3.0:GROUP:`)
2. v4 single (`ZMSG|v4|`) — checks for INIT, KEX, KEXACK, ADDR, Reply+hash, Reply (legacy)
3. v3 INIT (`ZMSG|v3|INIT|`)
4. v3 REF (`ZMSG|v3|REF|`)
5. v3 RPL (`ZMSG|v3|RPL|`)
6. v3 hash-based (`ZMSG|v3|`)
7. v2 legacy (`ZMSG|v2|`)
8. Special types (ZREACT, ZRCPT, ZREQ, ZSTAT, ZTL, ZUNLOCK)
9. Plain text (not ZMSG format)

---

## Source Files

| File | Purpose |
|------|---------|
| `ZMSGProtocol.kt` | Core protocol — parsing, creation, chunking |
| `ZMSGConstants.kt` | All protocol constants (prefixes, markers, sizes) |
| `ZMSGGroupProtocol.kt` | GROUP protocol parsing and creation |
| `ZMSGSpecialMessages.kt` | ZREACT, ZRCPT, ZREQ, ZSTAT, ZTL, ZUNLOCK |
| `GroupModels.kt` | Group-related data classes |
| `GroupViewModel.kt` | Group creation and messaging logic |
| `E2EEncryption.kt` | ECDH key exchange, HKDF, AES-256-GCM |
| `ZchatPreferences.kt` | Conversation ID storage, group storage, settings |
| `AddressCacheImpl.kt` | Address hash → full address mapping with collision guards |
| `ChatViewModel.kt` | Message handling, threading, GROUP receiving |
| `CreateChunkedMessageProposalUseCase.kt` | Multi-output transaction creation |

---

## Version: 5.0
## Last Updated: 2026-03-11
