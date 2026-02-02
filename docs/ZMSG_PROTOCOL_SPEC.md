# ZMSG Protocol Specification

## Overview

ZMSG (Zcash Message) is a protocol for sending messages via Zcash transaction memos. It enables private, blockchain-based messaging using Zcash's shielded transactions.

## Protocol Versions

### Version History

| Version | Status | Description |
|---------|--------|-------------|
| v2 | Legacy | Full address in every message |
| v3 | Deprecated | Hash-based with REF threading |
| v4 | **Current** | Conversation ID-based threading |

---

## ZMSG v4 (Current - Recommended)

### Overview

v4 uses 8-character conversation IDs for reliable message threading. This eliminates all timing and address-matching issues present in earlier versions.

### Conversation ID

- **Length**: 8 characters
- **Charset**: A-Z, 0-9 (alphanumeric uppercase)
- **Generation**: SecureRandom selection from charset
- **Uniqueness**: ~2.8 trillion possible combinations (36^8)

```kotlin
private const val CONV_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val CONV_ID_LENGTH = 8

fun generateConversationId(): String {
    val random = java.security.SecureRandom()
    return (1..CONV_ID_LENGTH)
        .map { CONV_ID_CHARS[random.nextInt(CONV_ID_CHARS.length)] }
        .joinToString("")
}
```

### Message Formats

#### Single Message - INIT (First message to new contact)
```
ZMSG|v4|<convID>|INIT|<full_sender_address>|<message>
```

**Example:**
```
ZMSG|v4|ABC12345|INIT|u1abc...xyz|Hello, this is my first message!
```

**Components:**
- `ZMSG|v4|` - Protocol identifier
- `<convID>` - 8-char conversation ID (e.g., `ABC12345`)
- `INIT|` - Marker for first message
- `<full_sender_address>` - Full unified address (~141 chars)
- `<message>` - Message content

**Available space:** ~350 characters for message

#### Single Message - Reply (Subsequent messages)
```
ZMSG|v4|<convID>|<message>
```

**Example:**
```
ZMSG|v4|ABC12345|Thanks for your message!
```

**Available space:** ~490 characters for message

### Chunked Message Formats

For messages exceeding single memo capacity (~512 bytes), split across multiple transaction outputs.

#### Chunked INIT (First chunk)
```
ZMSG|v4c|1/N|<convID>|INIT|<address>|<message_part>
```

#### Chunked Reply (First chunk)
```
ZMSG|v4c|1/N|<convID>|<message_part>
```

#### Chunked Continuation (Subsequent chunks)
```
ZMSG|v4c|M/N|CONT|<message_part>
```

**Chunk Sizes:**
| Type | Available Space |
|------|-----------------|
| v4 INIT first chunk | ~330 chars |
| v4 Reply first chunk | ~475 chars |
| Continuation chunks | ~485 chars |

### Conversation ID Storage

IDs are stored bidirectionally in SharedPreferences:

```kotlin
// peerAddress -> convId (for sending)
fun getConversationId(peerAddress: String): String?
fun setConversationId(peerAddress: String, convId: String)

// convId -> peerAddress (for receiving)
fun getPeerByConversationId(convId: String): String?
fun setConversationMapping(convId: String, peerAddress: String)
```

### Message Resolution Flow

1. **Sending:**
   - Check if convID exists for peer
   - If not, generate new convID and store
   - Use INIT format for first message, reply format for subsequent

2. **Receiving:**
   - Parse memo, extract convID
   - Look up peer address by convID
   - If found: route to existing conversation
   - If not found + INIT: create mapping, start new conversation

---

## ZMSG v3 (Legacy - Backward Compatible)

### Overview

v3 uses address hashes for space efficiency and REF format for threading.

### Hash Generation

```kotlin
fun generateAddressHash(address: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(address.toByteArray())
    return hashBytes.take(6).joinToString("") { "%02x".format(it) }
}
```

**Result:** 12-character hex string (first 6 bytes of SHA256)

### Message Formats

#### INIT Message
```
ZMSG|v3|INIT|<full_sender_address>|<message>
```

#### Reply Message (Hash-based)
```
ZMSG|v3|<sender_hash>|<message>
```

#### REF Message (Transaction-referenced)
```
ZMSG|v3|REF|<last_received_txid>|<sender_hash>|<message>
```

### Known Issues (Why v4 was created)

1. **Timing Problem**: REF format requires the referenced transaction to exist before lookup
2. **Diversified Addresses**: Same wallet can generate unlinkable addresses
3. **Hash Collisions**: Unlikely but possible with 12-char hashes

---

## Special Message Types

### Reactions (ZREACT)
```
ZREACT|<target_txid>|<emoji>|<sender_hash>
```

### Read Receipts (ZRCPT)
```
ZRCPT|<target_txid>|<sender_hash>
```

### Reply to Message (RPL)
```
ZMSG|v3|RPL|<quoted_txid>|INIT|<address>|<message>
ZMSG|v3|RPL|<quoted_txid>|<hash>|<message>
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

#### Scheduled (Time-based)
```
ZTL|SCH|<unlock_timestamp>|<sender_hash>|<message>
```

#### Block Height
```
ZTL|BLK|<unlock_height>|<sender_hash>|<message>
```

#### Payment to Reveal
```
ZTL|PAY|<required_zatoshi>|<sender_hash>|<message>
```

#### Conditional (Secret Answer)
```
ZTL|CND|<answer_hash>|<hint>|<sender_hash>|<message>
```

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

ZMSG-GROUP enables encrypted group messaging over Zcash. Messages are fan-out to all group members, encrypted with a shared AES-256-GCM key.

### Protocol Format

```
ZMSG:3.0:GROUP:<type>:<group_id>:<payload>
```

**Components:**
- `ZMSG:3.0:GROUP:` - Protocol identifier
- `<type>` - Message type (2 characters)
- `<group_id>` - 24-character hex group identifier
- `<payload>` - JSON payload (type-specific)

### Message Types

| Type | Name | Description |
|------|------|-------------|
| GC | GROUP_CREATE | Create new group |
| GI | GROUP_INVITE | Invite member to group |
| GA | GROUP_ACCEPT | Accept group invitation |
| GL | GROUP_LEAVE | Leave group |
| GK | GROUP_KICK | Remove member (admin only) |
| GM | GROUP_MSG | Encrypted group message |
| GY | GROUP_KEY_ROTATE | Rotate group encryption key |
| GF | GROUP_INFO | Update group metadata |

### GROUP_INVITE (GI)

Sent to invite a user to join a group.

```
ZMSG:3.0:GROUP:GI:<group_id>:<payload>
```

**Payload (JSON):**
```json
{
  "name": "Group Name",
  "inviter": "u1abc...xyz",
  "invitee": "u1def...uvw",
  "members": ["u1abc...", "u1def...", "u1ghi..."],
  "key_epoch": 0,
  "group_key": "<base64_encoded_aes_key>"
}
```

### GROUP_MSG (GM)

Encrypted message sent to all group members.

```
ZMSG:3.0:GROUP:GM:<group_id>:<payload>
```

**Payload (JSON):**
```json
{
  "groupId": "abc123...",
  "seq": 1,
  "epoch": 0,
  "sender": "u1abc...xyz",
  "nonce": "<base64_nonce>",
  "ciphertext": "<base64_encrypted_message>",
  "timestamp": 1705432800
}
```

**Encryption:**
- Algorithm: AES-256-GCM
- Key: Shared group key (256-bit)
- Nonce: 12-byte random, unique per message
- AAD: groupId + sender address

### GROUP_ACCEPT (GA)

Sent by invitee to confirm joining the group.

```
ZMSG:3.0:GROUP:GA:<group_id>:<payload>
```

**Payload:**
```json
{
  "accepter": "u1def...uvw"
}
```

### GROUP_LEAVE (GL)

Sent when a member leaves the group voluntarily.

```
ZMSG:3.0:GROUP:GL:<group_id>:<payload>
```

**Payload:**
```json
{
  "leaver": "u1def...uvw"
}
```

### Group Key Management

**Key Generation:**
```kotlin
fun generateGroupKey(): ByteArray {
    val keyGenerator = KeyGenerator.getInstance("AES")
    keyGenerator.init(256, SecureRandom())
    return keyGenerator.generateKey().encoded
}
```

**Encryption:**
```kotlin
fun encryptMessage(plaintext: String, groupKey: ByteArray): Pair<String, String> {
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, nonce)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(groupKey, "AES"), spec)
    val ciphertext = cipher.doFinal(plaintext.toByteArray())
    return Pair(
        Base64.encodeToString(nonce, Base64.NO_WRAP),
        Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    )
}
```

### Message Fan-Out

Group messages are sent as individual transactions to each member:

```
Member 1: <amount> ZEC → member1_address (memo: GROUP_MSG)
Member 2: <amount> ZEC → member2_address (memo: GROUP_MSG)
Member 3: <amount> ZEC → member3_address (memo: GROUP_MSG)
...
Platform: <amount> ZEC → platform_fee_address (no memo)
```

**Cost:** ~0.0001 ZEC per member per message

### Group Storage (SharedPreferences)

```kotlin
// Group info storage
fun saveGroupInfo(groupId: String, groupInfoJson: String)
fun getGroupInfo(groupId: String): String?
fun getAllGroupIds(): Set<String>

// Group members
fun saveGroupMembers(groupId: String, membersJson: String)
fun getGroupMembers(groupId: String): String?

// Group encryption keys
fun saveGroupKey(groupId: String, keyEpoch: Int, encryptedKey: String)
fun getGroupKey(groupId: String, keyEpoch: Int): String?
fun getGroupKeyEpoch(groupId: String): Int
fun setGroupKeyEpoch(groupId: String, epoch: Int)

// Group messages
fun getGroupMessages(groupId: String): String?
fun saveGroupMessages(groupId: String, messagesJson: String)

// Message sequencing
fun getGroupMessageSequence(groupId: String): Long
fun incrementGroupMessageSequence(groupId: String): Long
```

### Implementation Files

| File | Purpose |
|------|---------|
| `ZMSGGroupProtocol.kt` | GROUP protocol parsing and creation |
| `GroupModels.kt` | Data classes (GroupInfo, GroupMember, GroupMessage, etc.) |
| `GroupViewModel.kt` | Group creation, messaging, and key management |
| `ZchatPreferences.kt` | Group data persistence |
| `ChatViewModel.kt` | GROUP message receiving and processing |

---

## Transaction Structure

### Standard Message (with platform fee)

```
Output 1: <amount> ZEC → recipient (memo: ZMSG message)
Output 2: <amount> ZEC → platform fee address (no memo)
```

### Chunked Message (3 chunks)

```
Output 1: <amount> ZEC → recipient (memo: chunk 1/3)
Output 2: <amount> ZEC → recipient (memo: chunk 2/3)
Output 3: <amount> ZEC → recipient (memo: chunk 3/3)
Output 4: <amount> ZEC → platform fee address (no memo)
```

### Platform Fee Address

```
u1pm2ju3zua63jtww3zexpahpqlgcu35qqq9hv7689n5luz3pkuefwyk27f4t2r8wf3up8cajkvtelhmnlja4sqk58s6qjavlyf5xv5s2qck6yuc4muee4g86zn8h4uzvdp9q3px2f6clxd46fvcllsphyndl7tvkjzwal68eccq7p4w53
```

---

## Implementation Notes

### Memo Size Limit

Zcash memos are limited to **512 bytes**. All formats must fit within this constraint.

### ZIP321 URIs

Multi-output transactions use ZIP321 payment URI format:

```
zcash:<addr>?amount=<amt>&memo=<base64url_memo>&address.1=<addr>&amount.1=<amt>&memo.1=<base64url_memo>...
```

Memos are encoded using Base64 URL-safe encoding (no padding).

### Parsing Priority

When parsing incoming memos, check formats in this order:

1. **v4** (ZMSG|v4| or ZMSG|v4c|) - Most reliable
2. **v3 INIT** (ZMSG|v3|INIT|)
3. **v3 REF** (ZMSG|v3|REF|)
4. **v3 RPL** (ZMSG|v3|RPL|)
5. **v3 Hash** (ZMSG|v3|)
6. **v2** (ZMSG|v2|)
7. **Special types** (ZREACT, ZRCPT, ZREQ, ZSTAT, ZTL, ZUNLOCK)
8. **Plain text** (non-ZMSG format)

---

## Files Reference

| File | Purpose |
|------|---------|
| `ZMSGProtocol.kt` | Core protocol parsing and creation |
| `ZMSGGroupProtocol.kt` | GROUP protocol parsing and creation |
| `GroupModels.kt` | Group-related data classes |
| `GroupViewModel.kt` | Group creation and messaging logic |
| `ZchatPreferences.kt` | Conversation ID, settings, and group storage |
| `ChatViewModel.kt` | Message handling, threading, and GROUP receiving |
| `CreateChunkedMessageProposalUseCase.kt` | Multi-output transaction creation |
| `AddressCacheImpl.kt` | Address hash → full address mapping |

---

## Version: 4.1
## Last Updated: 2026-01-16
