# ZCHAT Android App - Testing State Summary

## Date: 2026-01-09

## Current Status: DEEP CYBER Theme & UI Enhancements

### Latest Updates (v2.8.0 - DEEP CYBER THEME) - COMPLETE:
- ✅ **DEEP CYBER THEME** - Full cyberpunk visual experience
- ✅ **New Default Theme** - DEEP_CYBER is now the default theme
- ✅ **App Icon Redesign** - Hexagonal cyberpunk frame with neon Z
- ✅ **Transmission Headers** - Messages show "OUTGOING TRANSMISSION" / "TRANSMISSION RECEIVED"
- ✅ **Neon Glow Buttons** - Send and + buttons have cyan/magenta glow effects
- ✅ **Angular Message Bubbles** - Sharp edges with neon border glow
- ✅ **Circuit Background Pattern** - Subtle circuit traces in background
- ✅ **BUILD COMPLETE** - APK: `zchat-v2.8.0-deep-cyber.apk`

#### DEEP CYBER Theme Features:
- **Ultra-dark Background**: Near-black (#050510) for maximum contrast
- **Neon Cyan**: Full bright #00FFFF for primary accents
- **Hot Magenta**: Full bright #FF00FF for secondary accents
- **Transmission Headers**: Green text (#00FF88) above each message bubble
- **Send Button**: Magenta ring with cyan glow when active
- **Plus Button**: Cyan glow effect when enabled
- **Message Bubbles**: Angular shapes with subtle neon borders
- **App Icon**: Hexagonal frame with dual-tone Z inside chat bubble

#### Files Created:
- `ui-design-lib/.../colors/DeepCyberColorPalette.kt` - Color definitions
- `ui-design-lib/.../colors/DeepCyberZashiColors.kt` - Full theme mapping
- `ui-lib/.../chat/drawable/bg_circuit_pattern.xml` - Circuit background
- `ui-lib/.../chat/drawable/bg_deep_cyber_circuit.xml` - Vector circuit pattern
- `ui-lib/.../chat/drawable/bg_bubble_*_cyber.xml` - Message bubble shapes
- `ui-lib/.../chat/drawable/bg_send_button_cyber.xml` - Neon send button

#### Files Modified:
- `ThemePreference.kt` - Added DEEP_CYBER enum, set as default
- `ZcashTheme.kt` - Added DEEP_CYBER to ThemeMode
- `ChatThemeColors.kt` - Added DeepCyberChatColors
- `ChatDetailView.kt` - Transmission headers, neon buttons, angular bubbles
- `ThemeSelectorView.kt` - Added DEEP_CYBER description
- `ic_launcher_foreground_vector.xml` - New hexagonal cyberpunk icon
- `ic_launcher_background.xml` - Dark #050510 background

### Previous Updates (v2.7.5 - TRANSACTION-BASED THREADING) - COMPLETE:
- ✅ **CHAT THREADING PERMANENTLY FIXED** - Uses transaction IDs instead of address hashes
- ✅ **New REF Protocol Format**: `ZMSG|v3|REF|<last_received_txid>|<sender_hash>|<message>`
- ✅ **Why This Works**: Transaction IDs are unique and immutable - no reliance on addresses
- ✅ **BUILD COMPLETE** - APK: `zchat-2.7.5-ref-threading.apk`

#### The Problem (Root Cause Analysis):
- Zcash uses **diversified addresses** - same wallet can generate infinite different addresses
- These addresses are **cryptographically unlinkable** by design (Zcash privacy feature)
- When User B replies using a different diversified address, the hash doesn't match
- **Address-based matching will NEVER work reliably with Zcash**

#### The Solution (Transaction-Based Threading):
1. **Track Last Received TxId**: When receiving a message, store its txid per conversation
2. **REF Format**: When replying, include the txid of the last received message:
   - Single: `ZMSG|v3|REF|<txid>|<hash>|<message>`
   - Chunked: `ZMSG|v3c|1/N|REF|<txid>|<hash>|<message_part>`
3. **TxId Lookup**: Receiver looks up the txid to find which conversation it belongs to
4. **Cache New Hash**: Once conversation is identified, cache the new hash → address mapping

#### Why This Is Reliable:
- Transaction IDs are **unique across the entire blockchain**
- Both sender and receiver see the **same txid** for a transaction
- **No address matching needed** - works with any diversified address
- Automatically handles the "different address" problem forever

#### Files Modified:
- `ZMSGProtocol.kt` - Added REF message format (create, parse, chunked support)
- `CreateChunkedMessageProposalUseCase.kt` - Added lastReceivedTxId parameter
- `ChatViewModel.kt` - Track lastReceivedTxIdByPeer and pass it when sending

### Previous Updates (v2.7.4 - CHAT THREADING FIX) - SUPERSEDED BY v2.7.5:
- Attempted fix using conversation partner tracking
- Did not fully resolve the diversified address problem
- Replaced by transaction-based threading in v2.7.5

### Previous Updates (v2.7.3 - DESTROY PIN ONBOARDING) - COMPLETE:
- ✅ **DESTROY PIN SETUP DURING ONBOARDING** - PIN setup screen now appears before wallet creation
- ✅ **DestroyPinSetupView.kt** - Full composable with PIN entry, confirmation, theming support
- ✅ **DestroyPinSetup.kt** - Navigation route with `isCreatingWallet` parameter
- ✅ **AndroidDestroyPinSetup.kt** - Android wrapper connecting view with ZchatPreferences
- ✅ **OnboardingNavGraph.kt** - Integrated PIN setup before wallet creation
- ✅ **BUILD COMPLETE** - APK: `zchat-2.7.3-onboarding-pin.apk`

#### Onboarding PIN Setup Features:
- **Emergency Data Wipe Explanation** - Clear info about what the feature does
- **Feature Items** - Shield icon, PIN protection, instant wipe, unrecoverable warning
- **PIN Entry** - 4-8 digit PIN with confirmation validation
- **Skip Option** - Users can skip and set up PIN later in Settings
- **Cancel Option** - Can cancel after starting PIN entry

#### Files Created:
- `ui-lib/.../screen/onboarding/view/DestroyPinSetupView.kt` - PIN setup composable (336 lines)
- `ui-lib/.../screen/onboarding/DestroyPinSetup.kt` - Navigation route
- `ui-lib/.../screen/onboarding/AndroidDestroyPinSetup.kt` - Android wrapper

#### Files Modified:
- `ui-lib/.../OnboardingNavGraph.kt` - Added DestroyPinSetup composable and navigation

### Previous Updates (v2.7.2 - SWIPE TO DELETE):
- ✅ **SWIPE-TO-DELETE CHAT** - Swipe left on chat in list reveals DELETE button
- Using Material3 `SwipeToDismissBox` component
- Red DELETE button with trash icon on right side
- Non-dismissing swipe (requires tap on DELETE button)

#### Files Modified:
- `ChatListView.kt` - Added `SwipeableConversationItem` wrapper composable

### Previous Updates (v2.7.1 - CYBERPUNK DEFAULT):
- ✅ **CYBERPUNK DEFAULT THEME** - App now defaults to Cyberpunk theme instead of System
- Changed in `ThemePreference.fromString()` fallback

#### Files Modified:
- `ThemePreference.kt` - Changed default return to CYBERPUNK

### REPORTED BUGS:
1. **Chat Threading Bug** - ✅ FIXED in v2.7.5
   - Root cause: Zcash diversified addresses are cryptographically unlinkable
   - Solution: Transaction-based threading using REF format with txid references
   - No longer relies on address/hash matching

2. **Insufficient Funds Error** - PENDING INVESTIGATION - User has 0.0089 ZEC but getting "insufficient funds" alert
   - 0.0089 ZEC = 890,000 zatoshi
   - This should be enough for multiple messages (each ~1000 zatoshi + fee)
   - Need to check: Balance calculation, fee estimation, pool availability (Orchard vs Sapling)
   - Possible cause: Funds may be in wrong pool or pending confirmation

### Previous Updates (v2.7.0 - THEME SELECTOR):
- ✅ **THEME SELECTOR** - Users can now choose between System, Light, Dark, and Cyberpunk themes
- ✅ **THEME-AWARE COLORS** - Chat views now dynamically adapt to selected theme
- ✅ **ThemePreference.CYBERPUNK** - Added to enum with persistence support
- ✅ **ChatListView** - Updated to use theme colors (title gradient, FAB, sync bar, conversation cards)
- ✅ **ChatDetailView** - Updated to use theme colors (top bar, message bubbles, input area)

### Previous Updates (v2.6.0 - CYBERPUNK THEME):
- ✅ **THEME SYSTEM** - Multi-theme support infrastructure
- ✅ **CYBERPUNK COLORS** - Neon cyan (#00FFFF) + magenta (#FF00FF) on deep purple (#1A0A2E)
- ✅ **THEME ENUM** - ThemeMode: SYSTEM, LIGHT, DARK, CYBERPUNK
- ✅ **DESIGN ASSETS** - 27 SVG + 10 JPEG cyberpunk UI elements created

#### Theme System Files:
- `ThemePreference.kt` - Enum with SYSTEM, LIGHT, DARK, CYBERPUNK options
- `ThemeSelectorView.kt` - Dialog for theme selection
- `CyberpunkColorPalette.kt` - Cyan/Magenta/Purple color scales
- `CyberpunkZashiColors.kt` - Full theme color mapping
- `ChatThemeColors.kt` - Chat-specific theme-aware colors

### Previous Updates (v2.5.2):
- ✅ **FAB POSITION** - Moved back to right side, raised 56dp above SyncStatusBar
- ✅ **MEDIUM TOP BAR** - Uses MediumTopAppBar for more content space
- ✅ **STATUS VISIBILITY FIX** - User status now visible on Honor 90 (fixed height issue)
- ✅ **LANDING PAGE UPDATE** - New Honor 90 screenshot on zsend.xyz

### Previous Updates (v2.5.1):
- ✅ **CONTACT NAME DISPLAY** - Chat headers show contact names from address book
- ✅ **COPY ADDRESS ON TAP** - Tap chat header to copy full address to clipboard
- ✅ **REPLY FIX** - Replies now correctly go to same conversation (not separate chat)
- ✅ **NEW APP LOGO** - Z-node chat bubble design with cyan-green gradient

### Previous Updates (v2.1):
- ✅ **NEW DESIGN** - Futuristic cyan-to-green gradient theme based on new logo
- ✅ **DESTROY ALL** - Emergency wipe button with PIN protection
- ✅ **REMOTE KILL** - Self-destruct via special transaction
- ✅ UI Improvements - Refresh button moved left, single "+" menu for features
- ✅ USD Price Display - Real-time ZEC/USD conversion
- ✅ Font Size Options - 10% bigger fonts in chat list
- ✅ Samsung Fold 3 Fix - Navigation bar padding for input field

### Previous Updates:
- ✅ Time-Locked Messages (scheduled, block-height, payment, conditional)
- ✅ Memo Templates for Quick Payments (coffee $5, lunch $15, etc.)
- ✅ ZEC Payment Requests in Chat (request with tap-to-pay)
- ✅ Reply to Specific Message (quote & reply)
- ✅ Message Search (real-time filtering)
- ✅ Message Reactions (emoji picker)
- ✅ Read Receipt Indicators (checkmarks)
- ✅ Message Status Icons (sending/sent/confirmed/read/failed)

### Build Information
- **Package**: `xyz.zsend.zchat.debug`
- **APK Location**: `/home/yourt/zchat-android/app/build/outputs/apk/zcashmainnetStore/debug/app-zcashmainnet-store-debug.apk`
- **Build Command**: `JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew assembleZcashmainnetStoreDebug`

---

## Completed Features & Fixes

### 1. Chat List View
- Shows conversations with message previews
- Displays balance
- Shows truncated user address with copy button
- QR code icon and Settings icon in header
- Blue FAB (+) button for new messages

### 2. Chat Detail View - Fixed Loading Issue
**Problem**: Conversation detail was stuck on loading forever.
**Root Cause**: `AndroidChatDetail` created a new ViewModel instance which started with empty state.
**Fix**: Changed to observe `chatListState` flow instead of calling synchronous `getConversation()`.
**File**: `/home/yourt/zchat-android/ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt`

### 3. Direct Message Sending from Chat
**Feature**: Send messages directly from chat conversation by tapping send button.
**Implementation**:
- Added `CreateProposalUseCase` dependency to `ChatViewModel`
- Added `sendMessage(peerAddress, message)` function
- Creates ZecSend with minimum amount (1000 zatoshi = 0.00001 ZEC) and memo
- Uses ZMSGv3 protocol format
- Navigates to Review Transaction screen for confirmation

### 4. Address Validation for Unknown Senders
**Problem**: App crashed when trying to reply to "unknown" sender.
**Root Cause**: Original validation `startsWith("u")` passed for "unknown".
**Fix**: Updated validation to check:
- Unified: starts with "u1" AND length > 100
- Sapling: starts with "zs" AND length > 70

**Result**: Shows "Cannot reply - sender address unknown" message and disables input field.

### 5. ZMSGv3 Protocol Implementation (NEW)
**Problem**: Zcash shielded transactions hide sender identity, and 512-byte memo limit wastes space on full addresses (~250 bytes).

**Solution**: ZMSGv3 Protocol with local-only address cache:

#### Protocol Formats:
- **First message (INIT)**: `ZMSG|v3|INIT|<full_sender_address>|<message>`
  - Available space: ~250 characters for message
  - Includes full address so receiver can cache it

- **Reply messages**: `ZMSG|v3|<hash>|<message>`
  - Hash: First 12 chars of SHA256(address) in hex
  - Available space: ~490 characters for message

- **Legacy v2 (compatibility)**: `ZMSG|v2|<full_address>|<message>`

#### Files Created:
- `/home/yourt/zchat-android/ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ZMSGProtocol.kt`
  - Protocol parser and message formatter
  - Hash generation using SHA256
  - Handles v2 and v3 formats

- `/home/yourt/zchat-android/ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/AddressCacheImpl.kt`
  - Local-only SharedPreferences cache
  - No backend server required
  - Maps hash -> full address for lookups

#### Files Modified:
- `ChatViewModel.kt`: Added AddressCache dependency, uses ZMSGv3 for sending
- `ChatMessage.kt`: Added `unknownReason` field
- `ChatDetailView.kt`: Added `UnknownSenderBanner` for explaining unknown senders
- `DateSourceModule.kt`: Registered AddressCacheImpl in Koin DI

### 6. Unknown Sender UI Explanation
Added info banner in chat detail view explaining why messages are from unknown senders:
- **NOT_ZMSG_FORMAT**: Message sent without ZMSG protocol
- **MALFORMED_MESSAGE**: Corrupted or invalid format
- **HASH_NOT_IN_CACHE**: Hash-based message but address not cached yet

### 7. ZCHAT Custom Receive Screen (NEW)
**Feature**: Custom QR code/receive screen that directly shows shielded address instead of showing a choice between shielded/transparent like ZASHI.

**User Flow**:
1. Tap QR icon on main chat screen → Directly shows shielded address QR code
2. "Copy Address" button copies the current address to clipboard
3. "Show Transparent Address" button toggles to transparent address view
4. Info cards explain:
   - **Shielded**: Recommended for ZCHAT messaging, receives private transactions
   - **Transparent**: Only for receiving from exchanges, cannot be used for ZCHAT messaging

#### Files Created:
- `ui-lib/.../screen/chat/model/ZchatReceiveState.kt` - State model
- `ui-lib/.../screen/chat/view/ZchatReceiveView.kt` - UI view with QR, copy, toggle buttons
- `ui-lib/.../screen/chat/viewmodel/ZchatReceiveVM.kt` - ViewModel

#### Files Modified:
- `ChatRoutes.kt`: Added `ZchatReceive` navigation route
- `AndroidChat.kt`: Added `AndroidZchatReceive()` composable, updated navigation
- `ViewModelModule.kt`: Registered `ZchatReceiveVM`
- `WalletNavGraph.kt`: Added navigation composable for `ZchatReceive`

### 8. Multi-Output Chunked Messaging (NEW)
**Feature**: Messages longer than 512 bytes are automatically split across multiple transaction outputs, allowing messages up to ~4500 characters.

**How It Works**:
1. **Sender side**: Long messages are automatically split into chunks
2. **Each chunk** is a separate output in the same transaction
3. **Receiver side**: Chunks are reassembled into the complete message

**Chunk Protocol Format**:
```
First chunk INIT:   ZMSG|v3c|1/N|INIT|<address>|<message_part>
First chunk reply:  ZMSG|v3c|1/N|<hash>|<message_part>
Continuation:       ZMSG|v3c|M/N|CONT|<message_part>
```

**Capacity per chunk**:
- INIT first chunk: ~340 characters
- Reply first chunk: ~470 characters
- Continuation chunks: ~485 characters
- With 10 chunks: ~4500 character messages possible

**Cost**: Each chunk costs the selected amount preset + platform fee (see section 9)

#### Files Created:
- `ui-lib/.../screen/chat/usecase/CreateChunkedMessageProposalUseCase.kt`
  - Handles single and multi-output proposal creation
  - Uses ZIP321 URIs for multi-output transactions
  - Calculates chunk count and total cost

#### Files Modified:
- `ZMSGProtocol.kt`: Added chunked message support
  - `createChunkedInitMessages()` / `createChunkedReplyMessages()`
  - `reassembleChunks()` for receiver-side reconstruction
  - `isChunkedMemo()`, `calculateChunkCount()`, `getMaxChunkedMessageLength()`
- `ChatViewModel.kt`: Updated to use chunked messaging
  - Uses `CreateChunkedMessageProposalUseCase` instead of `CreateProposalUseCase`
  - Message parsing now handles chunked messages on receive
  - Added `getChunkCount()` and `getMessageCost()` helpers
- `UseCaseModule.kt`: Registered `CreateChunkedMessageProposalUseCase`

### 9. Adjustable Message Amount & Platform Fee (NEW)
**Feature**: Users can adjust the ZEC amount sent with each message, with a platform fee going to the ZCHAT development address.

#### Amount Presets:
| Preset | Amount per Output | Description |
|--------|-------------------|-------------|
| ZERO | 0 ZEC | Free but may be delayed by miners |
| MINIMUM | 0.00001 ZEC (1000 zatoshi) | Default - minimal cost |
| STANDARD | 0.0001 ZEC (10000 zatoshi) | Standard priority |
| GENEROUS | 0.001 ZEC (100000 zatoshi) | Support recipient |
| CUSTOM | User-defined | Any amount in ZEC |

#### Platform Fee:
Every message transaction includes an additional output to the platform fee address:
- **Address**: `u1pm2ju3zua63jtww3zexpahpqlgcu35qqq9hv7689n5luz3pkuefwyk27f4t2r8wf3up8cajkvtelhmnlja4sqk58s6qjavlyf5xv5s2qck6yuc4muee4g86zn8h4uzvdp9q3px2f6clxd46fvcllsphyndl7tvkjzwal68eccq7p4w53`
- **Amount**: Same as the selected message amount preset
- **Purpose**: Supports ZCHAT platform development and infrastructure

#### Transaction Structure Example:
For a 1-chunk message with MINIMUM preset:
- Output 1: 0.00001 ZEC → recipient (with ZMSG memo)
- Output 2: 0.00001 ZEC → platform fee address (no memo)
- Total: 0.00002 ZEC + network fee

For a 3-chunk message with STANDARD preset:
- Outputs 1-3: 0.0001 ZEC each → recipient (with message chunks)
- Output 4: 0.0001 ZEC → platform fee address
- Total: 0.0004 ZEC + network fee

#### UI Components:
- **Amount Card**: Shows current total amount and fee, with "Adjust" button
- **Amount Selection Dialog**: Preset options with descriptions, custom input field
- **Zero Amount Warning**: Visual indicator when ZERO is selected (may be delayed)

#### Files Created:
- `ui-lib/.../screen/chat/model/ZchatComposeState.kt`
  - `MessageAmount` enum with presets
  - State fields for amount dialog

#### Files Modified:
- `CreateChunkedMessageProposalUseCase.kt`:
  - Added `PLATFORM_FEE_ADDRESS` constant
  - Updated `buildZip321Uri()` to include platform fee output
  - Updated `getTotalCost()` to include platform fee
- `ZchatComposeVM.kt`: Added amount selection state management
- `ZchatComposeView.kt`: Added amount card and selection dialog UI

### 10. QR Backup/Restore for Seed Phrases (NEW)
**Feature**: Users can backup their seed phrase as a QR code and restore wallets by scanning QR codes from camera or gallery.

#### How It Works:
1. **Backup**: In wallet backup screen, tap "Show QR Code" to display seed phrase as scannable QR
2. **Restore**: In restore screen, tap camera icon to scan QR code or gallery icon to select image
3. **Auto-navigation**: After scanning, automatically navigates with birthday height pre-filled

#### QR Code Format:
```json
{
  "seed": "word1 word2 word3 ... word24",
  "birthday": 2500000
}
```

#### Files Created:
- `ui-lib/.../screen/restore/model/SeedBackupQrData.kt`
  - Kotlinx serializable data class for QR encoding/decoding
  - Contains seed phrase and birthday height

- `ui-lib/.../usecase/PrefillRestoreSeedUseCase.kt`
  - Shared state for passing scanned QR data between screens
  - Consumed by RestoreSeedViewModel after navigation

#### Files Modified:
- `WalletBackupState.kt`: Added `showQrCode` state and QR data generation
- `WalletBackupView.kt`: Added "Show QR Code" button and QR display dialog
- `RestoreSeedView.kt`: Added camera and gallery scan buttons in header
- `RestoreSeedViewModel.kt`: Added QR data processing and seed prefill logic
- `ScanZashiAddressVM.kt`: Added RESTORE_SEED flow handling
- `ScanFlow.kt`: Added RESTORE_SEED enum value
- `OnAddressScannedUseCase.kt`: Added RESTORE_SEED case
- `OnZip321ScannedUseCase.kt`: Added RESTORE_SEED case
- `RestoreBDHeightVM.kt`: Pre-fills birthday from scanned QR
- `UseCaseModule.kt`: Registered PrefillRestoreSeedUseCase

#### UI Flow:
```
Backup:
Settings → Backup → Show QR Code → Display QR with seed + birthday

Restore:
Onboarding → Restore → [Camera/Gallery] → Scan QR →
Seeds pre-filled → Continue → Birthday pre-filled → Complete
```

### 11. Privacy Branding & Explanation (NEW)
**Feature**: Clear privacy messaging throughout the app explaining ZCHAT's unique advantages.

#### Key Messages:
- **No Servers**: Messages live on Zcash blockchain - decentralized and censorship-resistant
- **No Sign-Up**: No phone number, email, or account required
- **Military-Grade Encryption**: Zero-knowledge cryptography
- **No AI/Clouds/Tracking**: Nothing to leak because nothing is collected
- **Open Source**: Built on battle-tested Zcash protocol

#### Empty Chat Screen Privacy Card:
When no conversations exist, displays welcoming privacy explanation:
- 🔐 Large lock emoji header
- "Welcome to ZCHAT" title
- "True Privacy. Zero Compromise." subtitle
- Privacy points with emojis explaining each feature
- "Tap + to send your first private message" call-to-action

#### About Screen Privacy Section:
Dedicated section with expandable privacy features:
- 🔒 No Servers - explanation
- 📱 No Sign-Up Required - explanation
- 🛡️ Military-Grade Encryption - explanation
- 🚫 No AI. No Clouds. No Tracking. - explanation
- 👁️ Open Source & Auditable - explanation
- Footer: "ZCHAT: The messenger that knows nothing about you."

#### Files Modified:
- `ui-lib/.../screen/about/values/strings.xml`: Added privacy section strings (English)
- `ui-lib/.../screen/about/values-es/strings.xml`: Added privacy section strings (Spanish)
- `ui-lib/.../screen/about/view/AboutView.kt`: Added PrivacyFeatureItem composable and privacy section
- `ui-lib/.../screen/chat/view/ChatListView.kt`: Updated EmptyConversationsView with privacy card

#### String Resources Added:
```xml
<string name="about_subtitle">True Privacy. Zero Compromise.</string>
<string name="about_privacy_title">Why ZCHAT is Different</string>
<string name="about_privacy_no_servers">No Servers</string>
<string name="about_privacy_no_signup">No Sign-Up Required</string>
<string name="about_privacy_encryption">Military-Grade Encryption</string>
<string name="about_privacy_no_ai">No AI. No Clouds. No Tracking.</string>
<string name="about_privacy_open_source">Open Source &amp; Auditable</string>
<string name="about_privacy_footer">ZCHAT: The messenger that knows nothing about you.</string>
```

### 12. In-Chat Payment with Split Payment (NEW)
**Feature**: Send ZEC payments directly from chat conversations with optional split payment functionality.

#### How It Works:
1. **PAY Button**: Green dollar icon button next to message input
2. **Payment Dialog**: Opens when PAY is tapped with:
   - Amount input in ZEC with real-time USD conversion
   - Available balance display
   - Split Payment toggle (divide by N people)
   - Per-person amount calculation
   - Optional memo field
3. **Confirmation**: Navigates to transaction progress screen

#### Split Payment Feature:
- Toggle "Split Payment" switch to enable
- Adjust number of people (2-20) with +/- buttons
- Automatically calculates per-person amount
- Shows both ZEC and USD per-person values
- Great for splitting bills, group expenses, etc.

#### UI Components:
```
┌─────────────────────────────────────┐
│  Send Payment                    ✕  │
├─────────────────────────────────────┤
│  To: u1rzev...7x2atk                │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ Ⓩ  Amount (ZEC)             │   │
│  │     0.5                      │   │
│  │     ≈ $15.00 USD             │   │
│  └─────────────────────────────┘   │
│                                     │
│  Available: 0.01 ZEC                │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ Split Payment        [OFF]   │   │
│  │ Divide total among people    │   │
│  │                              │   │
│  │  [-]    5 people    [+]     │   │
│  │                              │   │
│  │  Each person pays:           │   │
│  │  0.1 ZEC ≈ $3.00 USD        │   │
│  └─────────────────────────────┘   │
│                                     │
│  Memo (optional): Dinner split      │
│                                     │
│  [Cancel]      [$ Send 0.5 ZEC]    │
└─────────────────────────────────────┘
```

#### Files Modified:
- `ChatMessage.kt`: Added `PaymentDialogState` data class, updated `ChatDetailState.Success` with balance and zecPriceUsd
- `ChatDetailView.kt`: Added PAY button to MessageInput, created PaymentDialog composable with split payment UI
- `ChatViewModel.kt`: Added `sendPayment()`, `getBalance()`, `getZecPriceUsd()` functions
- `AndroidChat.kt`: Wired up balance, zecPriceUsd, and onSendPayment callback

#### Payment Flow:
```
Chat Screen → Tap $ PAY → Dialog opens → Enter amount →
(Optional) Enable split → Set # people → Add memo →
Tap Send → Transaction Progress Screen → Confirmation
```

### 13. Time-Locked Messages (NEW)
**Feature**: Send messages that unlock based on time, block height, payment, or secret answer.

#### 4 Lock Types:

**⏰ Scheduled (Time-based)**
- Set delay in minutes (30m, 1h, 1d, 1w presets)
- Message auto-unlocks after the specified time
- Shows countdown: "Unlocks in Xh Xm"
- Protocol: `ZTL|TIME|<unlock_timestamp>|<encrypted_message>`

**⛓️ Block Height**
- Unlocks at a specific Zcash block height
- Trustless - based on blockchain, not server time
- Shows target block number
- ~75 seconds per block estimate shown
- Protocol: `ZTL|BLOCK|<unlock_height>|<encrypted_message>`

**💰 Payment to Reveal**
- Recipient must pay ZEC to unlock
- Presets: 0.001, 0.01, 0.1, 1.0 ZEC
- Shows "Pay X ZEC to reveal"
- Payment triggers unlock transaction
- Protocol: `ZTL|PAY|<required_zatoshi>|<encrypted_message>`

**❓ Secret Answer (Conditional)**
- Recipient must answer correctly to unlock
- Sender sets secret answer + optional hint
- Answer hashed for verification
- Hint shown to recipient
- Protocol: `ZTL|COND|<hint>|<answer_hash>|<encrypted_message>`

#### UI Components:
- **🔒 Lock Button**: Purple button in message input bar
- **TimeLockComposerDialog**: 4-tab dialog for creating locked messages
  - Tab selector with emojis (⏰ ⛓️ 💰 ❓)
  - Message input field
  - Lock-type-specific settings
  - Quick presets for each type

- **LockedMessageContent**: Displays locked messages in chat
  - Lock icon with type-specific emoji
  - Description of unlock condition
  - "Tap to unlock" prompt for payment/conditional types
  - Auto-refresh for scheduled messages

#### Protocol: ZTL (Zcash Time Lock)
```
Locked message:   ZTL|<type>|<params>|<encrypted_message>
Unlock message:   ZUNLOCK|<original_txid>|<unlock_data>
```

#### Files Modified:
- `ZMSGProtocol.kt`: Added ZTL and ZUNLOCK parsing/creation
- `ChatMessage.kt`: Added `TimeLockInfo`, `TimeLockType`, `timeLock` field, `isLocked` property
- `ChatDetailView.kt`: Added TimeLockComposerDialog, LockedMessageContent, Lock button
- `ChatViewModel.kt`: Added `sendScheduledMessage()`, `sendBlockLockedMessage()`, `sendPaymentLockedMessage()`, `sendConditionalMessage()`, time-lock parsing
- `AndroidChat.kt`: Wired up time-lock callbacks

### 14. Memo Templates for Quick Payments (NEW)
**Feature**: Pre-defined payment templates with USD amounts for common transactions.

#### Built-in Templates:
| Template | Emoji | Amount | Message |
|----------|-------|--------|---------|
| Coffee | ☕ | $5 | "Thanks for the coffee!" |
| Lunch | 🍔 | $15 | "Lunch is on me!" |
| Dinner | 🍽️ | $30 | "Thanks for dinner!" |
| Birthday | 🎂 | $25 | "Happy Birthday! 🎉" |
| Thanks | 🙏 | $10 | "Thank you so much!" |
| Tip | 💰 | $5 | "Here's a tip for you!" |
| Beer | 🍺 | $8 | "Grab a beer on me!" |
| Gas | ⛽ | $20 | "Thanks for the ride!" |

#### How It Works:
1. Tap ☕ button (orange) in message input bar
2. Template picker slides up showing all templates
3. Each chip shows emoji, name, USD amount, ZEC equivalent
4. Tap template → Payment dialog opens pre-filled
5. Confirm and send

#### USD to ZEC Conversion:
- Templates store amounts in USD
- Real-time conversion using current ZEC price
- Shows both: "$5.00" and "≈ 0.15 ZEC"

#### UI Components:
- **☕ Templates Button**: Orange button in message input
- **TemplatePickerRow**: Horizontal scrollable row of templates
- **TemplateChip**: Individual template with emoji, name, amounts
- **PaymentDialog**: Pre-fills with template amount and memo

#### Data Model:
```kotlin
data class MemoTemplate(
    val id: String,
    val name: String,
    val emoji: String,
    val memo: String,
    val amountUsd: Double?,    // USD amount (converted to ZEC)
    val amountZec: Double?,    // Or direct ZEC amount
    val isBuiltIn: Boolean
)
```

#### Files Modified:
- `ChatMessage.kt`: Added `MemoTemplate` data class with built-in templates
- `ChatDetailView.kt`: Added TemplatePickerRow, TemplateChip, updated MessageInput and PaymentDialog
- `ZchatPreferences.kt`: Added template storage methods for custom templates

### 15. ZEC Payment Requests in Chat (NEW)
**Feature**: Request ZEC payments from contacts with tap-to-pay functionality.

#### How It Works:
1. Tap 💸 button (pink) in message input bar
2. PaymentRequestComposerDialog opens
3. Enter amount (ZEC or USD with toggle)
4. Add optional reason ("Dinner split", "Rent", etc.)
5. Send request → Appears in chat as special message
6. Recipient sees "Pay X ZEC" button
7. Tap to fulfill request instantly

#### Protocol: ZREQ (Zcash Request)
```
Request:  ZREQ|<amount_zatoshi>|<sender_hash>|<reason>
Fulfill:  ZREQ_FULFILL|<original_request_txid>
```

#### UI Components:

**💸 Request Button**: Pink button in message input bar

**PaymentRequestComposerDialog**:
- USD/ZEC toggle switch
- Amount input with currency symbol
- Quick presets: $5/$10/$25/$50 (USD) or 0.01/0.1/1/5 (ZEC)
- Real-time conversion display
- Reason input with examples
- "Request X ZEC" confirm button

**PaymentRequestContent** (in chat bubble):
- 💸 emoji header
- "Payment Requested" / "Payment Request Sent" label
- Amount card showing ZEC and USD equivalent
- Reason in italics (if provided)
- **"Pay X ZEC"** button (incoming only)
- ✓✓ "Paid" indicator when fulfilled

#### Data Model:
```kotlin
data class PaymentRequestInfo(
    val amountZatoshi: Long,
    val reason: String,
    val isPaid: Boolean = false,
    val paidTxId: String? = null
) {
    val amountZec: Double
    fun getFormattedAmount(): String
    fun getAmountUsd(zecPriceUsd: Double?): Double?
    fun getDisplayString(zecPriceUsd: Double?): String
}
```

#### Files Modified:
- `ZMSGProtocol.kt`: Added ZREQ protocol, `createPaymentRequest()`, `parsePaymentRequest()`, `ParsedPaymentRequest` class
- `ChatMessage.kt`: Added `PaymentRequestInfo`, `paymentRequest` field, `isPaymentRequest` property
- `ChatDetailView.kt`: Added PaymentRequestContent, PaymentRequestComposerDialog, 💸 button
- `ChatViewModel.kt`: Added `sendPaymentRequest()`, `fulfillPaymentRequest()`, ZREQ parsing
- `AndroidChat.kt`: Wired up `onSendPaymentRequest` and `onFulfillPaymentRequest` callbacks

### 16. Destroy All & Remote Kill (NEW)
**Feature**: Emergency wipe functionality to clear all app data with optional remote trigger capability.

#### Destroy All Button
Location: Red button (🗑️ DeleteForever icon) in SyncStatusBar on main chat screen.

**Security**:
- PIN protection required (set on first use)
- 6-digit PIN setup with confirmation
- PIN verification required for subsequent use
- Final confirmation dialog before destruction

**Destruction Process**:
1. Clear all SharedPreferences files
2. Clear app cache (internal + external)
3. Clear all databases
4. Clear app files directory
5. Request app uninstallation (system dialog)

**UI Flow**:
```
First Time:
Tap Destroy → PIN Setup Dialog → Enter PIN → Confirm PIN →
Final Confirmation → Destroy All → Uninstall Dialog

Subsequent:
Tap Destroy → PIN Verify Dialog → Enter PIN →
Final Confirmation → Destroy All → Uninstall Dialog
```

#### Remote Kill (Transaction Trigger)
**Feature**: Self-destruct app by sending a special transaction from any Zcash wallet.

**How It Works**:
1. User configures remote kill in settings:
   - Secret phrase (minimum 12 characters)
   - Trigger amount in ZEC (default: 0.00001337 ZEC = 1337 zatoshi)
2. To trigger remote kill, send transaction to the app's address with:
   - Exact configured amount
   - Memo: `ZCHAT_DESTROY:<secret_phrase>`
3. App monitors incoming transactions
4. When kill signal detected → Immediate destruction

**Security Requirements**:
- Secret phrase must be ≥12 characters
- Amount must match exactly (prevents accidental triggers)
- Memo must match exactly: `ZCHAT_DESTROY:<phrase>`

**Configuration**:
```kotlin
// Default kill amount
private const val DEFAULT_REMOTE_KILL_AMOUNT = 1337L // 0.00001337 ZEC

// Kill memo format
const val KILL_MEMO_PREFIX = "ZCHAT_DESTROY:"
```

#### Files Created:
- `ui-lib/.../screen/chat/util/DestroyManager.kt`
  - `isKillSignal()` - validates incoming transactions
  - `destroyAll()` - executes destruction
  - `setupRemoteKill()` - configures remote trigger
  - `getKillMemo()` - returns required memo format

#### Files Modified:
- `ZchatPreferences.kt`: Added destroy settings
  - `getDestroyPin()`, `setDestroyPin()`, `hasDestroyPin()`
  - `isRemoteKillEnabled()`, `setRemoteKillEnabled()`
  - `getRemoteKillPhrase()`, `setRemoteKillPhrase()`
  - `getRemoteKillAmount()`, `setRemoteKillAmount()`
  - `clearAll()` - wipes all preferences

- `ChatListView.kt`: Added destroy button and dialogs
  - Red destroy button in SyncStatusBar
  - PIN setup dialog (first-time)
  - PIN verify dialog (subsequent)
  - Final confirmation dialog

- `ChatViewModel.kt`: Added remote kill monitoring
  - `setRemoteKillCallback()` - registers destruction callback
  - `checkForRemoteKill()` - checks incoming transactions
  - Processes incoming transactions for kill signals

- `AndroidChat.kt`: Wired up destroy functionality
  - Creates DestroyManager instance
  - Sets remote kill callback
  - Handles PIN setup/verify callbacks

#### UI Components:
```kotlin
// Destroy Button (in SyncStatusBar)
Box(modifier = Modifier
    .size(24.dp)
    .clip(CircleShape)
    .background(Color(0xFFFF1744).copy(alpha = 0.15f))
) {
    Icon(Icons.Default.DeleteForever, tint = Color(0xFFFF1744))
}

// PIN Setup Dialog
- Title: "Set Destroy PIN"
- 6-digit PIN input
- Confirm PIN input
- PIN match validation
- "Your PIN will be required to destroy the app" info

// PIN Verify Dialog
- Title: "Enter PIN"
- 6-digit PIN input
- Error message on wrong PIN
- "Forgot PIN?" hint (app reinstall required)

// Final Confirmation
- "⚠️ DESTROY ALL DATA?" warning
- Lists what will be deleted
- "This cannot be undone" warning
- Cancel / DESTROY buttons
```

### 17. Advanced Messaging Features (NEW)
**Features**: Reply to messages, message search, emoji reactions, and read receipt indicators.

#### 17.1 Reply to Specific Message
**Feature**: Quote and reply to any message in the conversation.

**How It Works**:
1. Long-press any message → Select "Reply" from menu
2. Reply preview appears above message input showing quoted text
3. Type your reply and send
4. Message shows quoted preview inside the bubble

**Protocol Format**:
```
ZMSG|v3|RPL|<quoted_txid>|INIT|<address>|<message>  (first message)
ZMSG|v3|RPL|<quoted_txid>|<hash>|<message>          (subsequent)
```

#### 17.2 Message Search
**Feature**: Search through conversation messages with instant filtering.

**How It Works**:
1. Tap search icon (🔍) in chat header
2. Search bar expands in header
3. Type to filter messages in real-time
4. Shows result count ("X results found")
5. Matching messages shown with highlighted text
6. Tap back arrow to close search

**UI**:
- Search icon in top-right of chat header
- Full-width search input when active
- Clear button (X) to reset search
- Result count display

#### 17.3 Message Reactions
**Feature**: React to messages with emoji reactions (like iMessage/WhatsApp).

**How It Works**:
1. Long-press any message → Select "React" from menu
2. Quick emoji picker shows: 👍 ❤️ 😂 😮 😢 🙏
3. Tap emoji to send reaction
4. Reactions display below message bubbles
5. Multiple reactions shown grouped with counts

**Protocol Format**:
```
ZREACT|<target_txid>|<emoji>|<sender_hash>
```

**UI**:
- Reaction picker: horizontal row of emoji buttons
- Reaction display: rounded chips below message
- Grouped by emoji with count if > 1

#### 17.4 Read Receipts
**Feature**: Visual indicators showing message delivery and read status.

**Current Implementation**:
- Single checkmark (✓): Message sent/delivered
- Double checkmark (✓✓): Message read by recipient
- Blue tint on double checkmark when read

**Protocol Format**:
```
ZRCPT|<target_txid>|<sender_hash>
```

**Note**: Read receipts cost ZEC to send. The sender includes extra ZEC in their message to cover the receipt cost (prepaid by sender model).

#### Files Modified:
- `ZMSGProtocol.kt`: Added reaction, read receipt, and reply parsing/creation
- `ChatMessage.kt`: Added `replyToId`, `replyToPreview`, `reactions`, `isRead`, `readAt` fields
- `ChatDetailView.kt`: Added search bar, reply preview, reaction picker, reaction display, read receipt icons
- `ChatViewModel.kt`: Added `sendReply()`, `sendReaction()`, `sendReadReceipt()` functions
- `AndroidChat.kt`: Wired up new callbacks for reply, reactions, and receipts
- `CreateChunkedMessageProposalUseCase.kt`: Added `rawMemo` parameter for pre-formatted memos

#### UI Components Added:
```kotlin
// Reply Preview (above message input)
ReplyPreview(message, onDismiss)

// Quoted Message (inside bubble)
QuotedMessagePreview(previewText, isOutgoing)

// Reaction Picker (dropdown)
QUICK_REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

// Reactions Display (below bubble)
Grouped reactions with emoji + count

// Read Receipt Icons
Icons.Default.Done     // Sent
Icons.Default.DoneAll  // Read (blue tint)
```

---

## Key Code Locations

| Feature | File |
|---------|------|
| Chat List UI | `ui-lib/.../screen/chat/view/ChatListView.kt` |
| Chat Detail UI | `ui-lib/.../screen/chat/view/ChatDetailView.kt` |
| ZCHAT Receive UI | `ui-lib/.../screen/chat/view/ZchatReceiveView.kt` |
| ZCHAT Compose UI | `ui-lib/.../screen/chat/view/ZchatComposeView.kt` |
| Chat ViewModel | `ui-lib/.../screen/chat/viewmodel/ChatViewModel.kt` |
| Receive ViewModel | `ui-lib/.../screen/chat/viewmodel/ZchatReceiveVM.kt` |
| Compose ViewModel | `ui-lib/.../screen/chat/viewmodel/ZchatComposeVM.kt` |
| Android Composables | `ui-lib/.../screen/chat/AndroidChat.kt` |
| ZMSG Protocol | `ui-lib/.../screen/chat/model/ZMSGProtocol.kt` |
| Chunked Messaging & Platform Fee | `ui-lib/.../screen/chat/usecase/CreateChunkedMessageProposalUseCase.kt` |
| Address Cache | `ui-lib/.../screen/chat/datasource/AddressCacheImpl.kt` |
| Chat Models | `ui-lib/.../screen/chat/model/` |
| Chat Message (MemoTemplate, PaymentRequest, TimeLock) | `ui-lib/.../screen/chat/model/ChatMessage.kt` |
| ZCHAT Preferences (Templates, Status) | `ui-lib/.../screen/chat/datasource/ZchatPreferences.kt` |
| Destroy Manager | `ui-lib/.../screen/chat/util/DestroyManager.kt` |
| Compose State (Amount Presets) | `ui-lib/.../screen/chat/model/ZchatComposeState.kt` |
| Navigation | `ui-lib/.../screen/chat/ChatRoutes.kt` |
| DI Module | `ui-lib/.../di/DateSourceModule.kt`, `ViewModelModule.kt`, `UseCaseModule.kt` |
| Seed Backup QR Model | `ui-lib/.../screen/restore/model/SeedBackupQrData.kt` |
| Prefill Restore Seed UseCase | `ui-lib/.../usecase/PrefillRestoreSeedUseCase.kt` |
| Wallet Backup View | `ui-lib/.../screen/backup/view/WalletBackupView.kt` |
| Restore Seed View | `ui-lib/.../screen/restore/view/RestoreSeedView.kt` |
| Restore Seed ViewModel | `ui-lib/.../screen/restore/viewmodel/RestoreSeedViewModel.kt` |
| QR Scanner | `ui-lib/.../screen/scan/viewmodel/ScanZashiAddressVM.kt` |
| About Screen | `ui-lib/.../screen/about/view/AboutView.kt` |
| About Strings (EN) | `ui-lib/.../res/ui/about/values/strings.xml` |
| About Strings (ES) | `ui-lib/.../res/ui/about/values-es/strings.xml` |

---

## Commands to Resume Testing

```bash
# Check emulator status
/mnt/c/Users/yourt/AppData/Local/Android/Sdk/platform-tools/adb.exe devices

# Start emulator if needed
/mnt/c/Users/yourt/AppData/Local/Android/Sdk/emulator/emulator.exe -avd Pixel_7 &

# Launch app
/mnt/c/Users/yourt/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5554 shell monkey -p xyz.zsend.zchat.debug -c android.intent.category.LAUNCHER 1

# Check logs
/mnt/c/Users/yourt/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5554 logcat -d | grep -i "zchat\|error\|exception" | tail -50

# Rebuild if needed
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew assembleZcashmainnetStoreDebug

# Install APK
/mnt/c/Users/yourt/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5554 install -r /home/yourt/zchat-android/app/build/outputs/apk/zcashmainnetStore/debug/app-zcashmainnet-store-debug.apk
```

---

## Pending Tests

### 1. Send Message to Valid Address
- Need a valid Zcash address to test actual message sending
- Should create proposal and navigate to review screen
- First message uses INIT format, subsequent use hash format

### 2. Receive ZMSG Messages
- Test receiving both v2 and v3 format messages
- Verify address caching works correctly
- Test hash-based lookup after INIT message

### 3. QR Code / Receive Address Display ✅ IMPLEMENTED
- Tap QR code icon in header → Shows ZCHAT custom receive screen
- Directly displays shielded address QR code (not a choice like ZASHI)
- Copy Address button
- Toggle to show transparent address with warning message

### 4. Copy Address Functionality
- Tap copy icon next to address
- Should copy to clipboard with toast confirmation

### 5. Compose New Message (+ Button)
- Tap blue FAB button
- Should open Send screen for new message

### 6. Settings Screen
- Tap gear icon
- Should open settings/more screen

### 7. QR Backup/Restore ✅ IMPLEMENTED
- Backup: Settings → Backup → Show QR Code → Display QR with seed + birthday
- Restore: Onboarding → Restore → Camera/Gallery → Scan QR → Seeds pre-filled
- Verify birthday height is pre-filled after seed entry

### 8. Privacy Branding ✅ IMPLEMENTED
- Empty chat screen shows privacy card with explanations
- About screen shows privacy features section
- Verify Spanish translations display correctly

### 9. Delete Messages ✅ IMPLEMENTED
- Long-press any message → Dropdown menu appears
- Tap "Delete Message" → Message hidden from UI
- Messages persist across app restarts (stored in SharedPreferences)
- Deleted messages cannot be recovered in app (but still on blockchain)

### 10. In-Chat Payment ✅ IMPLEMENTED
- Tap green $ PAY button next to message input
- Payment dialog opens with amount input
- Real-time USD conversion based on current ZEC price
- Split payment toggle to divide among 2-20 people
- Per-person amount calculation in ZEC and USD
- Optional memo field for payment notes
- Balance check prevents overspending
- Navigates to transaction progress after send

### 11. Reply to Message ✅ IMPLEMENTED
- Long-press any message → Select "Reply" from dropdown
- Reply preview shows above message input with cancel button
- Send message includes quoted reference (RPL protocol)
- Quoted message preview displays inside reply bubble
- Works with both first messages (INIT) and subsequent (hash)

### 12. Message Search ✅ IMPLEMENTED
- Tap search icon (🔍) in chat header
- Search bar replaces title when active
- Real-time filtering as you type
- Shows "X results found" count
- Back arrow closes search and clears filter
- Clear button (X) in search field to reset

### 13. Message Reactions ✅ IMPLEMENTED
- Long-press any message → Select "React" from dropdown
- Quick emoji picker: 👍 ❤️ 😂 😮 😢 🙏
- Tap emoji to send reaction transaction
- Reactions display below message bubbles
- Multiple same reactions grouped with count

### 14. Read Receipt Indicators ✅ IMPLEMENTED
- Single checkmark (✓) for sent messages
- Double checkmark (✓✓) with blue tint when read
- Icons update based on message.isRead status
- Uses ZRCPT protocol for blockchain-based receipts

### 15. Message Status Icons ✅ IMPLEMENTED
Full message delivery status system with visual indicators:

| Status | Icon | Description |
|--------|------|-------------|
| SENDING | ⏱️ (clock) | Message being created/submitted |
| SENT | ✓ (gray) | Transaction submitted to mempool |
| CONFIRMED | ✓✓ (white) | Transaction confirmed on blockchain |
| READ | ✓✓ (blue) | Recipient sent read receipt |
| FAILED | ❌ (red) | Transaction failed |

**Features**:
- `MessageStatus` enum with 5 states
- `effectiveStatus` computed property in ChatMessage
- Failed messages stay visible with error indicator (not removed)
- Smooth UX: immediate SENDING status, updates as tx progresses

### 16. Time-Locked Messages ✅ IMPLEMENTED
- Tap 🔒 purple lock button in message input
- TimeLockComposerDialog opens with 4 tabs (⏰ ⛓️ 💰 ❓)
- Create scheduled, block-height, payment, or conditional locked messages
- Recipients see lock icon with unlock condition
- Scheduled messages auto-unlock; others require action

### 17. Memo Templates ✅ IMPLEMENTED
- Tap ☕ orange button in message input
- Template picker slides up with 8 built-in templates
- Each shows emoji, name, USD amount, ZEC equivalent
- Tap template → Payment dialog pre-filled with amount and memo
- Templates: Coffee $5, Lunch $15, Dinner $30, Birthday $25, Thanks $10, Tip $5, Beer $8, Gas $20

### 18. ZEC Payment Requests ✅ IMPLEMENTED
- Tap 💸 pink button in message input
- PaymentRequestComposerDialog opens
- Toggle USD/ZEC, quick presets, optional reason
- Send creates ZREQ message in chat
- Recipient sees "Pay X ZEC" button
- Tap to fulfill request instantly

### 19. Destroy All ✅ IMPLEMENTED
- Red destroy button (🗑️) visible in SyncStatusBar
- First tap: PIN setup dialog (6 digits + confirm)
- Subsequent taps: PIN verify dialog
- Wrong PIN shows error, "Forgot PIN?" hint
- Correct PIN → Final confirmation dialog
- Confirm destroys: SharedPrefs, cache, databases, files
- System uninstall dialog opens after destruction

### 20. Remote Kill ✅ IMPLEMENTED
- Configure in settings: secret phrase (≥12 chars) + amount
- Default amount: 0.00001337 ZEC (1337 zatoshi)
- Kill memo format: `ZCHAT_DESTROY:<secret_phrase>`
- App monitors incoming transactions automatically
- Matching tx triggers immediate destruction
- Works from any Zcash wallet (ZASHI, CLI, etc.)

---

## Notes
- App syncs to Zcash mainnet
- Balance: 0.01 ZEC (1,000,000 zatoshi) in Orchard pool
- Privacy-first design: All address data stored locally only, no backend servers
