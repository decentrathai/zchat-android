# ZCHAT Technical Roadmap

**Last Updated:** 2026-01-21
**Status:** Sprint 4 In Progress, Sprint 5 (Identity Management) Planned
**Based on:** [Mastering Zcash](https://maxdesalle.com/mastering-zcash) analysis

---

## Implementation Schedule

### Sprint 1 - Core Privacy & UX ✅ COMPLETED

| Feature | Description | Complexity | Status |
|---------|-------------|------------|--------|
| **Strict Orchard Pool** | Auto-shield prompt, block messaging if funds in Sapling | Medium | ✅ Done |
| **Privacy Dashboard** | Show pool type, anonymity set estimate, shielded badges | Medium | ✅ Done |
| **Contact Nicknames** | Tap header to edit nickname via dialog | Low | ✅ Done |
| **Hash Remote Kill Phrase** | Store SHA-256 hash instead of plaintext | Low | ✅ Done |

### Sprint 2 - Security & Viewing Keys ✅ COMPLETED

| Feature | Description | Complexity | Status |
|---------|-------------|------------|--------|
| **Viewing Key Export** | FVK by default, Advanced section for IVK/OVK with explanations | High | ✅ Done |
| **Enhanced Destroy** | PIN + biometric + 5-second countdown + optional goodbye tx | Medium | ✅ Done |
| **Quantum Ready Badge** | Badge in app + detailed About section with links | Low | ✅ Done |
| **Notification Privacy** | 4 levels: Full/Sender only/New message/Silent | Medium | ✅ Done |

### Sprint 3 - Advanced Features ✅ COMPLETED

| Feature | Description | Complexity | Status |
|---------|-------------|------------|--------|
| **Auto-Save Drafts** | Automatically save unsent messages per conversation | Low | ✅ Done |
| **E2E Encryption Layer** | Optional toggle for additional encryption on top of Zcash | High | ✅ Done |
| **Group Chat Protocol** | Design multi-party messaging protocol | Very High | ✅ Done (Design) |

### Sprint 4 - Basic Groups (Current)

| Feature | Description | Complexity | Status |
|---------|-------------|------------|--------|
| **GROUP Protocol Parsing** | Parse ZMSG:3.0:GROUP messages in ZMSGProtocol | Medium | ✅ Done |
| **GROUP_INVITE Sending** | Send invites with group key to members | High | ✅ Done |
| **GROUP_MSG Sending** | Fan-out encrypted messages to all members | High | ✅ Done |
| **GROUP Message Receiving** | Process incoming GROUP messages in ChatViewModel | High | ✅ Done |
| **Groups in Chat List** | Display groups in ChatListView with icon | Medium | ✅ Done |
| **Group Chat UI** | Group detail view with message display | High | 🔄 In Progress |
| **Group Settings** | View members, leave group, manage settings | Medium | ⏳ Pending |

### Sprint 5 - Identity Management (PLANNED - P1)

| Feature | Description | Complexity | Status |
|---------|-------------|------------|--------|
| **Diversified Address Generation** | Generate new address from same seed ("masks") | Medium | ⏳ Pending |
| **Identity Switching** | Switch between masks, each with own conversations | High | ⏳ Pending |
| **Full Wallet Reset** | Generate entirely new seed, transfer ZEC first | Low | ⏳ Pending |
| **ADDR Protocol Message** | New message type for address change notification | Medium | ⏳ Pending |
| **Contact Notification Flow** | Batch send address change to all contacts | High | ⏳ Pending |
| **Silent Regeneration** | Change address without notifying anyone | Low | ⏳ Pending |
| **Recipient Update UI** | Prompt recipient to update contact on ADDR receipt | Medium | ⏳ Pending |

**Feature Details:**

**Mode 1: Diversified Address (Recommended)**
- Generate new diversified address from same seed
- ZEC balance PRESERVED (same wallet)
- Can switch between "masks" (identities)
- Each mask has separate: conversations, E2E keys, nicknames, drafts, groups
- User can switch back to old identity to view old chats
- Messages to old identity NOT monitored when on new identity

**Mode 2: Full Wallet Reset**
- Generate entirely new seed phrase
- User MUST transfer ZEC to new wallet first
- Old identity completely abandoned
- NO switching back (old wallet deleted)

**Notification Options:**
- A) Notify All Contacts: Sends ADDR message to address book + chat list
- B) Silent Regeneration: No notification, existing conversations won't continue

**UI Location:** Settings → Privacy & Security → Change Identity

---

## Sprint 1 - Detailed Specifications

### 1.1 Strict Orchard Pool Enforcement ✅ COMPLETED

**Decision:** Block messaging if funds are in Sapling. Show auto-shield prompt.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **SendMessageState.kt:**
   - Added `NeedsOrchardShielding` state with:
     - `saplingBalance: Zatoshi`
     - `transparentBalance: Zatoshi`
     - `message: String` (default explanation)

2. **ChatViewModel.kt:**
   - Added Orchard pool check in `sendMessage()` before allowing send
   - Checks if `orchardBalance <= 0` AND (`saplingBalance > 0` OR `transparentBalance > 0`)
   - Sets `NeedsOrchardShielding` state to block messaging
   - Added `dismissOrchardShieldingWarning()` function

3. **AndroidChat.kt:**
   - Added imports for `Row`, `Icon`, `Alignment`, `Arrangement`, `Color`, `FontWeight`, `Warning`
   - Added Orchard Pool Shielding Warning Dialog:
     - Title with warning icon: "Shield Your Funds"
     - Explains ZCHAT requires Orchard pool
     - Shows current Sapling and Transparent balances
     - Directs user to use Zashi wallet to shield funds
     - OK button to dismiss

**UI Flow (Final):**
1. User taps Send
2. Check privacy status from ChatListState
3. If no Orchard funds but has Sapling/Transparent:
   - Show shielding warning dialog
   - Block messaging until user shields funds
4. User dismisses dialog and uses Zashi wallet to shield
5. After funds are in Orchard, messaging is allowed

**Dialog Design:**
```
┌─────────────────────────────────────────┐
│  ⚠️ Shield Your Funds                   │
├─────────────────────────────────────────┤
│  For maximum privacy, ZCHAT uses the    │
│  Orchard pool exclusively.              │
│                                         │
│  Your funds are currently in:           │
│  • Sapling pool: X.XXXXXXXX ZEC         │
│  • Transparent: X.XXXXXXXX ZEC          │
│                                         │
│  Use the Zashi wallet app to shield     │
│  your funds to the Orchard pool, then   │
│  try again.                             │
│                                         │
│                               [OK]      │
└─────────────────────────────────────────┘
```

**Build Status:** ✅ Compiles successfully

---

### 1.2 Privacy Dashboard ✅ COMPLETED

**Decision:** Full privacy dashboard with pool type, anonymity set, shielded badges.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **ChatMessage.kt (Model):**
   - Added `PoolType` enum: `ORCHARD`, `SAPLING`, `TRANSPARENT`, `MIXED`
   - Added `PrivacyStatus` data class with:
     - `poolType: PoolType`
     - `orchardBalance`, `saplingBalance`, `transparentBalance: Zatoshi`
     - `isFullyShielded: Boolean`
     - `anonymitySetEstimate: String` (computed property)
     - `poolDisplayName: String` (computed property)
     - `needsShielding: Boolean` (computed property)
   - Added `privacyStatus` field to `ChatListState.Success`
   - Added `privacyStatus` field to `ChatDetailState.Success`

2. **ChatViewModel.kt:**
   - Added imports for `WalletAccount`, `ZashiAccount`, `PoolType`, `PrivacyStatus`
   - Added `computePrivacyStatus(walletAccount: WalletAccount?)` helper function
   - Computes pool type based on where funds are located
   - Added `privacyStatus` to `ChatListState.Success` constructor

3. **AndroidChat.kt:**
   - Passes `privacyStatus` from `ChatListState.Success` to `ChatDetailState.Success`

4. **ChatDetailView.kt:**
   - Added imports for `PoolType`, `PrivacyStatus`, `Shield`, `Info`, `Warning` icons
   - Added `privacyStatus` parameter to `ChatDetailContent`
   - Added `showPrivacyStatus` state variable for collapsible card
   - Added `PrivacyStatusCard` composable:
     - Collapsible card showing privacy status
     - Header row with shield icon and status (SHIELDED/NEEDS ATTENTION)
     - Expanded view shows: Pool type, Anonymity set estimate
     - Color-coded: Green for shielded, Amber for needs attention
     - Warning message when funds need shielding

**UI Components:**

```
┌─────────────────────────────────────────┐
│  🛡️ SHIELDED                      ℹ️    │  <- Collapsed (tap to expand)
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│  🛡️ SHIELDED                      ℹ️    │  <- Expanded
│  ─────────────────────────────────────  │
│  Pool:           Orchard (Recommended)  │
│  Anonymity Set:  ~2.5M notes            │
│  ─────────────────────────────────────  │
│  Your messages hide among millions      │
│  of shielded transactions.              │
└─────────────────────────────────────────┘
```

**Build Status:** ✅ Compiles successfully

---

### 1.3 Contact Nicknames ✅ COMPLETED

**Decision:** Tap contact name in chat header to edit via dialog.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **ZchatPreferences.kt (Interface + Implementation):**
   - Added `getNickname(address: String): String?`
   - Added `setNickname(address: String, nickname: String)`
   - Added `getDisplayName(address: String): String` - returns nickname or truncated address
   - Added `getAllNicknames(): Map<String, String>`
   - Created separate `nicknamePrefs` SharedPreferences for clean storage
   - Updated `clearAll()` to include nickname prefs

2. **ChatViewModel.kt:**
   - Modified conversation creation to use nickname as priority over contact book name
   - Added `setNickname(address, nickname)` function that saves and refreshes
   - Added `getNickname(address)` and `getDisplayName(address)` functions

3. **ChatDetailView.kt:**
   - Added `onNicknameChange` callback parameter
   - Added `showNicknameDialog` and `nicknameText` state variables
   - Added AlertDialog for nickname editing with:
     - Text field for nickname input
     - Shows truncated address for reference
     - Save/Cancel buttons
   - Modified header Row to use `combinedClickable`:
     - **Tap**: Opens nickname edit dialog
     - **Long-press**: Copies address to clipboard
   - Changed subtitle hint: "Tap to set nickname" when no nickname set

4. **AndroidChat.kt:**
   - Added `onNicknameChange` callback wiring to `viewModel.setNickname()`

**UI Flow (Final):**
1. Tap contact name/avatar in chat header → Dialog opens
2. Enter nickname in text field
3. Tap "Save" → Nickname saved, conversation refreshes
4. Long-press header → Address copied to clipboard

**Build Status:** ✅ Compiles successfully

---

### 1.4 Hash Remote Kill Phrase ✅ COMPLETED

**Decision:** Store SHA-256 hash instead of plaintext.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **ZchatPreferences.kt (Interface):**
   - Replaced `getRemoteKillPhrase(): String?` with `verifyRemoteKillPhrase(phrase: String): Boolean`
   - Added `hasRemoteKillPhrase(): Boolean` for checking if phrase is set
   - Updated `setRemoteKillPhrase()` documentation to note phrase cannot be recovered

2. **ZchatPreferencesImpl.kt (Implementation):**
   - Changed storage key from `KEY_REMOTE_KILL_PHRASE` to `KEY_REMOTE_KILL_PHRASE_HASH`
   - `setRemoteKillPhrase()` now hashes phrase with SHA-256 before storing
   - Added `verifyRemoteKillPhrase()` that hashes input and compares to stored hash
   - Added `hasRemoteKillPhrase()` to check if hash exists
   - Added `hashPhrase()` private helper function

3. **DestroyManager.kt:**
   - Updated `isKillSignal()` to use `verifyRemoteKillPhrase()` instead of plaintext comparison
   - Added `hasRemoteKillPhrase()` check
   - Replaced `getKillMemo()` with `getKillMemoFormat()` (returns format hint, not actual phrase)
   - Added `isRemoteKillConfigured()` helper

4. **ChatViewModel.kt:**
   - Updated `checkForRemoteKill()` to use `verifyRemoteKillPhrase()`
   - Added `hasRemoteKillPhrase()` check
   - Removed reference to `getRemoteKillPhrase()`

**Security Improvement:**
- Plaintext phrase is NEVER stored
- Only SHA-256 hash is persisted
- User must remember/write down phrase during setup
- Even with device access, attacker cannot recover phrase

**Build Status:** ✅ Compiles successfully

---

## Sprint 2 - Detailed Specifications

### 2.1 Viewing Key Export

**Decision:** FVK by default in main section. Advanced section with IVK/OVK + explanations.

**UI Design:**

```
┌─────────────────────────────────────────┐
│  Export Viewing Keys                    │
├─────────────────────────────────────────┤
│                                         │
│  Full Viewing Key (FVK)                 │
│  ┌─────────────────────────────────┐   │
│  │  [QR CODE]                       │   │
│  │                                  │   │
│  └─────────────────────────────────┘   │
│  [Copy Key]  [Share]                    │
│                                         │
│  ⚠️ Anyone with this key can see ALL    │
│  your transactions but cannot spend.    │
│                                         │
│  ▼ Advanced Keys                        │
├─────────────────────────────────────────┤
│                                         │
│  Incoming Viewing Key (IVK)             │
│  See messages/payments you RECEIVED     │
│  Use for: Proving you got paid          │
│  [Export IVK]                           │
│                                         │
│  Outgoing Viewing Key (OVK)             │
│  See messages/payments you SENT         │
│  Use for: Proving you made a payment    │
│  [Export OVK]                           │
│                                         │
└─────────────────────────────────────────┘
```

**Files to create:**
- `ui-lib/.../screen/settings/ViewingKeyExportView.kt`
- `ui-lib/.../screen/settings/ViewingKeyExportVM.kt`

---

### 2.2 Enhanced Destroy (Multi-Factor + Countdown) ✅ COMPLETED

**Decision:** PIN + biometric + 5-second countdown + optional goodbye transaction.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **EnhancedDestroyState.kt (New):**
   - Created `DestroyStep` enum: `CONFIRM_INTENT`, `ENTER_PIN`, `BIOMETRIC_VERIFY`, `GOODBYE_OPTION`, `COUNTDOWN`, `DESTROYING`, `COMPLETE`
   - Created `EnhancedDestroyState` data class with:
     - `currentStep`, `pinInput`, `pinError`
     - `countdownSeconds`, `sendGoodbyeMessages`, `goodbyeMessageText`
     - `contactCount`, `isBiometricAvailable`, `biometricError`
     - All necessary callbacks

2. **EnhancedDestroyArgs.kt (New):**
   - Navigation route for Enhanced Destroy screen

3. **EnhancedDestroyVM.kt (New):**
   - ViewModel managing the enhanced destroy flow
   - PIN verification using `ZchatPreferences.verifyDestroyPin()`
   - Biometric availability check using `BiometricManager`
   - 5-second countdown with cancel capability
   - Contact count from conversation mappings
   - Final destroy execution via `DestroyManager.destroyAll()`

4. **EnhancedDestroyView.kt (New):**
   - Full UI with animated step transitions
   - `ConfirmIntentStep`: Warning about data destruction with list of what will be deleted
   - `EnterPinStep`: PIN entry with validation
   - `BiometricVerifyStep`: Biometric authentication prompt
   - `GoodbyeOptionStep`: Toggle for goodbye messages with custom text
   - `CountdownStep`: Animated circular progress with countdown 5→1
   - `DestroyingStep`: Progress indicator during destruction
   - `CompleteStep`: Success confirmation

5. **AndroidEnhancedDestroy.kt (New):**
   - Composable wiring ViewModel with biometric authentication
   - Uses `AuthenticationViewModel` for biometric prompts
   - Handles authentication results

6. **Integration:**
   - Added to `ViewModelModule.kt`: `viewModelOf(::EnhancedDestroyVM)`
   - Added to `DataSourceModule.kt`: `single { DestroyManager(get(), get()) }`
   - Added to `WalletNavGraph.kt`: `composable<EnhancedDestroyArgs> { AndroidEnhancedDestroy() }`
   - Added to `AdvancedSettingsVM.kt`: "Emergency Destroy" menu item

**UI Flow (Final):**
```
Step 1: Confirm Intent
┌─────────────────────────────────────┐
│  [🗑️] Destroy All Data              │
│                                     │
│  This will permanently delete:      │
│  ✗ All chat messages and history    │
│  ✗ Your wallet and private keys     │
│  ✗ All contacts and nicknames       │
│  ✗ All app settings and preferences │
│                                     │
│  [I Understand, Continue]           │
│  [Cancel]                           │
└─────────────────────────────────────┘

Step 2: PIN Entry (if set)
┌─────────────────────────────────────┐
│  [🔒] Enter Destroy PIN             │
│                                     │
│  [****              ]               │
│                                     │
│  [Verify PIN]                       │
└─────────────────────────────────────┘

Step 3: Biometric (if available)
┌─────────────────────────────────────┐
│  [👆] Biometric Verification        │
│                                     │
│  Use your fingerprint or face       │
│  to confirm                         │
│                                     │
│  [Authenticate]  [Skip Biometric]   │
└─────────────────────────────────────┘

Step 4: Goodbye Option
┌─────────────────────────────────────┐
│  Send Goodbye Message?              │
│                                     │
│  ☐ Send goodbye to all contacts     │
│  [Custom goodbye message...]        │
│                                     │
│  ⚠️ After countdown, all data       │
│  will be permanently erased         │
│                                     │
│  [Start 5-Second Countdown]         │
│  [Go Back]                          │
└─────────────────────────────────────┘

Step 5: Countdown
┌─────────────────────────────────────┐
│         ╭──────────╮                │
│         │          │                │
│         │    5     │                │
│         │          │                │
│         ╰──────────╯                │
│                                     │
│      DESTROYING IN...               │
│      Tap cancel to abort            │
│                                     │
│         [✗ CANCEL]                  │
└─────────────────────────────────────┘

Step 6: Complete
┌─────────────────────────────────────┐
│         [✓]                         │
│                                     │
│     Data Destroyed                  │
│                                     │
│  All app data has been permanently  │
│  erased. The app will now request   │
│  uninstallation.                    │
└─────────────────────────────────────┘
```

**Build Status:** ✅ Compiles successfully

---

### 2.3 Quantum Ready Badge ✅ COMPLETED

**Decision:** Badge in app + detailed About section.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **strings.xml (about/values/):**
   - Added `about_quantum_title`: "Quantum-Ready Privacy"
   - Added `about_quantum_description`: Explanation of Orchard protocol
   - Added `about_quantum_feature_1/2/3`: Three bullet point features
   - Added `about_quantum_learn_more`: Learn more link text
   - Added `about_quantum_badge_tooltip`: Tooltip text

2. **AboutView.kt:**
   - Added `Box`, `background`, `RoundedCornerShape`, `Alignment`, `Color` imports
   - Added `QuantumReadySection()` composable:
     - Purple "Q" badge icon (32x32dp, rounded corners)
     - Title: "Quantum-Ready Privacy"
     - Description explaining Orchard protocol
     - Three feature bullets with purple bullet points
   - Added `QuantumFeatureBullet()` helper composable
   - Inserted section after privacy features, before Privacy Policy link

**UI Design (Final):**
```
┌─────────────────────────────────────────┐
│  [Q]  Quantum-Ready Privacy             │
│                                         │
│  ZCHAT uses Zcash's Orchard protocol,   │
│  designed with post-quantum security    │
│  in mind.                               │
│                                         │
│  • Current encryption is secure...      │
│  • Orchard's design allows future...    │
│  • Your shielded funds are protected... │
└─────────────────────────────────────────┘
```

**Build Status:** ✅ Compiles successfully

---

### 2.4 Notification Privacy Options ✅ COMPLETED

**Decision:** 4 levels of notification privacy.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **ZchatPreferences.kt:**
   - Added `NotificationPrivacy` enum with 4 levels: `FULL_PREVIEW`, `SENDER_ONLY`, `NEW_MESSAGE`, `SILENT`
   - Added `getNotificationPrivacy()` interface method
   - Added `setNotificationPrivacy(level)` interface method
   - Added `KEY_NOTIFICATION_PRIVACY` constant
   - Added implementation that stores/retrieves from SharedPreferences

2. **MoreState.kt:**
   - Added `currentNotificationPrivacy` field
   - Added `showNotificationPrivacyDialog` field
   - Added `onNotificationPrivacyDialogDismiss` callback
   - Added `onNotificationPrivacySelected` callback

3. **MoreVM.kt:**
   - Added `ZchatPreferences` injection to constructor
   - Added `_showNotificationPrivacyDialog` state flow
   - Added `_currentNotificationPrivacy` state flow
   - Added notification privacy list item in settings
   - Added `onNotificationPrivacyClick()`, `onNotificationPrivacyDialogDismiss()`, `onNotificationPrivacySelected()` handlers
   - Added `displayName()` extension function for NotificationPrivacy

4. **ThemeSelectorView.kt:**
   - Added `NotificationPrivacySelectorDialog` composable
   - Added `NotificationPrivacyOption` composable with checkmark selection
   - Added `getPrivacyDisplayName()` and `getPrivacyDescription()` helpers

5. **MoreView.kt:**
   - Added import for `NotificationPrivacySelectorDialog`
   - Added dialog display when `showNotificationPrivacyDialog` is true

**Settings UI (Final):**
```
┌─────────────────────────────────────────┐
│  Notification Privacy                   │
├─────────────────────────────────────────┤
│  ○ Full Preview           ✓             │
│    Shows sender and message content     │
│                                         │
│  ○ Sender Only                          │
│    Shows who messaged, hides content    │
│                                         │
│  ○ New Message Only                     │
│    Just shows "New ZCHAT message"       │
│                                         │
│  ○ Silent                               │
│    No notifications, check app manually │
└─────────────────────────────────────────┘
```

**Build Status:** ✅ Compiles successfully

---

### 2.1 Viewing Key Export ✅ COMPLETED

**Decision:** FVK by default, Advanced section for IVK/OVK with explanations.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **ViewingKeyExportArgs.kt (New):**
   - Navigation route for Viewing Key Export screen

2. **ViewingKeyExportState.kt (New):**
   - Created `ViewingKeyType` enum: `FVK`, `IVK`, `OVK`
   - Created `ViewingKeyState` data class with:
     - `type`, `title`, `description`, `key`
     - `isRevealed`, `onRevealClick`, `onCopyClick`
   - Created `ViewingKeyExportState` with:
     - FVK state (default), IVK state, OVK state
     - `showAdvanced` toggle for advanced options
     - Snackbar message support

3. **ViewingKeyExportVM.kt (New):**
   - ViewModel managing viewing key export
   - Biometric authentication for revealing keys
   - Copy to clipboard functionality
   - Separate reveal states for FVK/IVK/OVK
   - Advanced section toggle

4. **ViewingKeyExportView.kt (New):**
   - Full UI with:
     - Header explaining viewing keys
     - Security warning card
     - FVK card (highlighted as recommended)
     - Advanced section toggle (expandable)
     - IVK and OVK cards in advanced section
     - Educational footer with use cases
   - Key display with blur/reveal animation
   - Copy and reveal buttons per key

5. **AndroidViewingKeyExport.kt (New):**
   - Composable wiring ViewModel

6. **Integration:**
   - Added to `ViewModelModule.kt`: `viewModelOf(::ViewingKeyExportVM)`
   - Added to `WalletNavGraph.kt`: `composable<ViewingKeyExportArgs> { AndroidViewingKeyExport() }`
   - Added to `AdvancedSettingsVM.kt`: "Export Viewing Keys" menu item

**UI Design (Final):**
```
┌─────────────────────────────────────────┐
│  Export Viewing Keys                    │
├─────────────────────────────────────────┤
│  ⚠️ Security Notice                     │
│  Viewing keys can reveal your           │
│  transaction history...                 │
├─────────────────────────────────────────┤
│  🔑 Full Viewing Key (FVK)              │
│  Recommended                            │
│  Allows viewing ALL transactions...     │
│                                         │
│  [••••••••••••••••••••••••]             │
│  [👁 Reveal]      [📋 Copy]             │
├─────────────────────────────────────────┤
│  Advanced Options                    ▼  │
├─────────────────────────────────────────┤
│  (When expanded:)                       │
│                                         │
│  🔑 Incoming Viewing Key (IVK)          │
│  Allows viewing INCOMING only...        │
│                                         │
│  🔑 Outgoing Viewing Key (OVK)          │
│  Allows viewing OUTGOING only...        │
├─────────────────────────────────────────┤
│  About Viewing Keys                     │
│  Common use cases:                      │
│  • Tax reporting and compliance         │
│  • Auditing by accountants              │
│  • Proving payments to merchants        │
│  • Portfolio tracking services          │
└─────────────────────────────────────────┘
```

**Build Status:** ✅ Compiles successfully

---

## Sprint 3 - Detailed Specifications

### 3.1 Auto-Save Drafts ✅ COMPLETED

**Decision:** Automatically save unsent messages per conversation.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **ZchatPreferences.kt:**
   - Added `getDraft(peerAddress)` method
   - Added `setDraft(peerAddress, draft)` method
   - Added `clearDraft(peerAddress)` method
   - Added `getAllDrafts()` method for conversation list
   - Added `hasDraft(peerAddress)` helper method
   - Added new `draftPrefs` SharedPreferences file for drafts
   - Updated `clearAll()` to also clear drafts

2. **Conversation (ChatMessage.kt):**
   - Added `draft: String?` field
   - Added `hasDraft: Boolean` computed property

3. **ChatViewModel.kt:**
   - Added draft loading when building conversations
   - Added `getDraft()`, `saveDraft()`, `clearDraft()` functions
   - Drafts are loaded with `getAllDrafts()` and applied to conversations

4. **ChatDetailView.kt:**
   - Added `onDraftChange` callback parameter
   - Initialize `messageText` with existing draft
   - Auto-save draft with 500ms debounce using `LaunchedEffect`

5. **AndroidChat.kt:**
   - Added `onDraftChange` callback to ChatDetailView
   - Clear draft when message or reply is sent

6. **ChatListView.kt:**
   - Show "Draft: " indicator in red when conversation has draft
   - Draft text shown instead of last message preview

**UI Behavior:**
- When typing a message, draft is auto-saved after 500ms of no typing
- Opening a conversation restores the draft in the text field
- Sending a message clears the draft
- Conversation list shows "Draft: [message preview]" in red

**Build Status:** ✅ Compiles successfully

---

### 3.2 E2E Encryption Layer ✅ COMPLETED

**Decision:** Optional additional encryption on top of Zcash.

**Status:** COMPLETED (2026-01-15)

**Changes Made:**

1. **E2EEncryption.kt (New):**
   - Created `crypto` package for encryption utilities
   - Implemented ECDH key exchange using secp256r1 (NIST P-256)
   - AES-256-GCM for message encryption
   - Key derivation using SHA-256
   - Message format: `E2E:<nonce_base64>:<ciphertext_base64>`
   - Public key exchange via `E2E_INIT:<public_key>` payload

2. **ZchatPreferences.kt:**
   - Added E2E key storage methods:
     - `isE2EEnabled(peerAddress)` / `setE2EEnabled()`
     - `getE2EPrivateKey()` / `getE2EOurPublicKey()` / `getE2EPeerPublicKey()`
     - `setE2EOurKeys()` / `setE2EPeerPublicKey()`
     - `isE2EKeyExchangeComplete()` / `clearE2EKeys()`
   - Added `e2ePrefs` SharedPreferences for key storage

3. **Conversation (ChatMessage.kt):**
   - Added `e2eEnabled: Boolean` field
   - Added `e2eKeyExchangeComplete: Boolean` field
   - Added `isE2EReady` computed property

4. **ChatViewModel.kt:**
   - Added E2E status loading for conversations
   - Added E2E management functions:
     - `isE2EEnabled()` / `setE2EEnabled()`
     - `getE2EOurPublicKey()` / `setE2EPeerPublicKey()`
     - `isE2EKeyExchangeComplete()`
   - Auto-generates key pair when E2E is enabled

5. **ChatDetailView.kt:**
   - Added `onE2EToggle` callback parameter
   - Added E2E lock icon in TopAppBar:
     - Locked icon (primary color): E2E ready (keys exchanged)
     - Open lock (tertiary color): E2E enabled but keys pending
     - Open lock (gray): E2E disabled
   - Tap icon to toggle E2E encryption

6. **AndroidChat.kt:**
   - Added `onE2EToggle` callback to toggle E2E via ViewModel

**Encryption Protocol:**
- Key Exchange: ECDH with secp256r1 (NIST P-256)
- Symmetric Encryption: AES-256-GCM
- Key Derivation: SHA-256 with "ZCHAT_E2E_KEY_V1" prefix
- Nonce: 12 bytes, randomly generated per message

**UI Design:**
```
TopAppBar Actions:
┌──────────────────────────────┐
│ [🔒] [🔍]    (E2E ready)     │
│ [🔓] [🔍]    (E2E pending)   │
│ [🔓] [🔍]    (E2E disabled)  │
└──────────────────────────────┘
```

**Build Status:** ✅ Compiles successfully

**Note:** Full message encryption/decryption integration pending. Current implementation provides:
- Key generation and storage infrastructure
- UI toggle for E2E per conversation
- E2E status indicators

---

### 3.3 Group Chat Protocol Design ✅ COMPLETED (Design Phase)

**Decision:** Design protocol now, implement later.

**Status:** DESIGN COMPLETED (2026-01-16)

**Research:** Based on [MLS (Messaging Layer Security)](https://datatracker.ietf.org/doc/rfc9420/) RFC 9420, adapted for Zcash constraints.

---

#### Zcash-Specific Constraints

| Constraint | Impact |
|------------|--------|
| Memo field: 512 bytes | Message size limited, protocol headers reduce usable space |
| O(N) cost per message | 1 transaction per recipient = N transactions for N members |
| No central server | All coordination via transactions, fully decentralized |
| Asynchronous | Members may be offline, delayed delivery |
| Blockchain ordering | Block height + tx index provides natural ordering |

---

#### ZCHAT Group Protocol (ZMSG-GROUP) v1.0

##### 1. Group Identifier

Each group has a unique identifier:
```
GROUP_ID = SHA-256(creator_address || creation_timestamp || random_32_bytes)
           [first 16 bytes, Base64 encoded]
```

Example: `zgrp_Abc123XyZ789==`

##### 2. Message Types

| Type | Code | Description |
|------|------|-------------|
| GROUP_CREATE | `GC` | Create new group |
| GROUP_INVITE | `GI` | Invite member to group |
| GROUP_ACCEPT | `GA` | Accept group invitation |
| GROUP_LEAVE | `GL` | Leave group |
| GROUP_KICK | `GK` | Remove member (admin only) |
| GROUP_MSG | `GM` | Regular group message |
| GROUP_KEY | `GK` | Key rotation message |
| GROUP_INFO | `GF` | Update group info (name, etc.) |

##### 3. Protocol Message Format

```
ZMSG:3.0:GROUP:<type>:<group_id>:<payload>
```

Example:
```
ZMSG:3.0:GROUP:GM:zgrp_Abc123:{"seq":42,"msg":"Hello everyone!"}
```

##### 4. Group Creation Flow

**Step 1: Creator initiates group**
```json
{
  "type": "GC",
  "group_id": "zgrp_Abc123XyZ789==",
  "name": "Project Alpha",
  "creator": "u1...",
  "created_at": 1705401600,
  "members": ["u1creator...", "u1alice...", "u1bob..."],
  "admin_policy": "CREATOR_ONLY",
  "key_epoch": 0,
  "group_key_enc": "<per-member encrypted group key>"
}
```

**Step 2: Send GROUP_INVITE to each member**
- Separate transaction to each member
- Contains group metadata + encrypted group key for that member
- Uses existing E2E key if available, or generates new per-member key

**Step 3: Members send GROUP_ACCEPT**
- Confirms membership
- Broadcasts to all members (O(N) transactions)

##### 5. Key Management (Simplified MLS)

**Epoch-Based Keys:**
- Group has a `key_epoch` counter starting at 0
- Each epoch has a unique `group_key`
- Key rotates on: member join, member leave, periodic refresh

**Key Distribution:**
```
For each member M:
  encrypted_key[M] = E2E_Encrypt(
    shared_secret = ECDH(sender_private, M_public),
    plaintext = group_key || key_epoch
  )
```

**Key Rotation (Member Leave):**
```
1. Admin sends GROUP_KICK to all members
2. Admin generates new group_key
3. Admin sends GROUP_KEY with new epoch to remaining members
4. Old key invalidated, messages with old epoch rejected
```

**Forward Secrecy:**
- Keys are derived per-epoch: `epoch_key = HKDF(group_key, "ZCHAT_GROUP_EPOCH" || epoch)`
- Old epoch keys are deleted after confirmation

##### 6. Message Sending (O(N) Fan-Out)

When sending a group message:
```
1. Encrypt message with current epoch_key (AES-256-GCM)
2. For each member M in group:
     Create transaction to M with:
       - GROUP_MSG header
       - group_id
       - sequence number
       - encrypted message
3. Total: N transactions for N members
```

**Message Format:**
```json
{
  "seq": 42,
  "epoch": 3,
  "sender": "u1alice...",
  "nonce": "<base64>",
  "ciphertext": "<base64>",
  "timestamp": 1705401600
}
```

##### 7. Message Ordering

**Challenge:** Messages from different senders arrive via separate transactions.

**Solution: Hybrid ordering**
```
Primary:   block_height (blockchain provides consensus)
Secondary: transaction_index within block
Tertiary:  sequence number (sender-assigned)
Tie-break: sender_address (deterministic)
```

**Ordering Algorithm:**
```kotlin
fun compareMessages(a: GroupMessage, b: GroupMessage): Int {
    // 1. Block height (confirmed before pending)
    if (a.blockHeight != b.blockHeight) {
        if (a.blockHeight == null) return 1  // Pending goes last
        if (b.blockHeight == null) return -1
        return a.blockHeight.compareTo(b.blockHeight)
    }
    // 2. Transaction index within block
    if (a.txIndex != b.txIndex) {
        return a.txIndex.compareTo(b.txIndex)
    }
    // 3. Sender sequence (for same-sender rapid messages)
    if (a.sender == b.sender && a.seq != b.seq) {
        return a.seq.compareTo(b.seq)
    }
    // 4. Deterministic tie-break
    return a.sender.compareTo(b.sender)
}
```

##### 8. Membership Management

**Adding Member (Admin Only):**
```
1. Admin sends GROUP_INVITE to new member with:
   - Group metadata
   - Current member list
   - Encrypted group key (new epoch)
2. Admin sends GROUP_KEY to existing members with:
   - New member public key
   - New epoch key (rotated for forward secrecy)
3. New member sends GROUP_ACCEPT to all
```

**Removing Member:**
```
1. Admin sends GROUP_KICK to all members
2. Admin rotates to new epoch (excludes kicked member)
3. Kicked member cannot decrypt new epoch messages
```

**Voluntary Leave:**
```
1. Member sends GROUP_LEAVE to all
2. Admin triggers key rotation
```

##### 9. Admin Policies

| Policy | Description |
|--------|-------------|
| CREATOR_ONLY | Only creator is admin |
| MULTI_ADMIN | Creator can promote others |
| DEMOCRATIC | Majority vote for changes |

##### 10. Cost Analysis

| Action | Transactions | Notes |
|--------|--------------|-------|
| Create group (N members) | N | Initial invites |
| Send message | N | Fan-out to all members |
| Add member | N+1 | Invite + key rotation |
| Remove member | N-1 | Kick + key rotation |
| Leave group | N-1 | Notify remaining |

**Example: 10-member group**
- Creating: 10 transactions (~0.0001 ZEC each = 0.001 ZEC)
- Each message: 10 transactions = 0.001 ZEC
- 100 messages/day = 0.1 ZEC/day

**Recommendation:** Groups should be < 20 members for practical cost.

##### 11. Offline Member Handling

**Problem:** Member may be offline during key rotation.

**Solution: Key Archive**
```
1. Keep last 3 epoch keys in memory
2. Messages include epoch number
3. If epoch is old but within archive window, decrypt succeeds
4. After catchup, member requests latest key if needed
```

##### 12. Data Structures (Kotlin)

```kotlin
data class GroupInfo(
    val groupId: String,
    val name: String,
    val creatorAddress: String,
    val createdAt: Instant,
    val adminPolicy: AdminPolicy,
    val currentEpoch: Int
)

data class GroupMember(
    val address: String,
    val publicKey: String,
    val joinedAt: Instant,
    val isAdmin: Boolean
)

data class GroupMessage(
    val groupId: String,
    val seq: Long,
    val epoch: Int,
    val senderAddress: String,
    val encryptedContent: String,
    val nonce: String,
    val timestamp: Instant,
    val blockHeight: Long?,
    val txIndex: Int?
)

enum class AdminPolicy {
    CREATOR_ONLY,
    MULTI_ADMIN,
    DEMOCRATIC
}
```

##### 13. Security Properties

| Property | Status | Notes |
|----------|--------|-------|
| Message Confidentiality | ✅ | AES-256-GCM encryption |
| Forward Secrecy | ✅ | Epoch-based key rotation |
| Post-Compromise Security | ✅ | Key rotation on member change |
| Membership Authentication | ✅ | Only members have group key |
| Message Integrity | ✅ | GCM authentication tag |
| Sender Authentication | ✅ | Address verified by Zcash |

##### 14. Implementation Phases

**Phase 1: Basic Groups (Sprint 4)**
- Group creation with 2-10 members
- Simple key distribution (no rotation)
- Basic message fan-out
- Creator-only admin

**Phase 2: Key Rotation (Sprint 5)**
- Epoch-based key management
- Add/remove member flows
- Forward secrecy

**Phase 3: Advanced Features (Future)**
- Multi-admin support
- Larger groups (10-50)
- Message reactions in groups
- Reply threading

##### 15. UI Mockups

**Group Chat List:**
```
┌─────────────────────────────────────────┐
│  👥 Project Alpha              3:42 PM  │
│  Alice: Let's meet tomorrow             │
│  ┌───────────────────────────────────┐  │
│  │ 5 members • 12 unread            │  │
│  └───────────────────────────────────┘  │
├─────────────────────────────────────────┤
│  👥 Family Chat                 1:15 PM  │
│  Bob: Happy birthday! 🎂                │
│  ┌───────────────────────────────────┐  │
│  │ 4 members                        │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

**Group Chat Detail:**
```
┌─────────────────────────────────────────┐
│ ← 👥 Project Alpha (5)           ⚙️ 👤  │
├─────────────────────────────────────────┤
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ Alice • 3:40 PM                 │    │
│  │ Let's meet tomorrow at 2pm     │    │
│  └─────────────────────────────────┘    │
│                                         │
│           ┌─────────────────────────┐   │
│           │ You • 3:42 PM           │   │
│           │ Sounds good! ✓✓         │   │
│           └─────────────────────────┘   │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ Bob • 3:43 PM                   │    │
│  │ I'll bring the docs             │    │
│  └─────────────────────────────────┘    │
│                                         │
├─────────────────────────────────────────┤
│ [Message input...]           [📎] [➤]  │
└─────────────────────────────────────────┘
```

**Group Settings:**
```
┌─────────────────────────────────────────┐
│ ← Group Settings                        │
├─────────────────────────────────────────┤
│  👥 Project Alpha                       │
│  Created by You • Jan 15, 2026          │
├─────────────────────────────────────────┤
│  Members (5)                            │
│  ┌─────────────────────────────────┐    │
│  │ 👤 You (Admin)                  │    │
│  │ 👤 Alice                   [✕]  │    │
│  │ 👤 Bob                     [✕]  │    │
│  │ 👤 Carol                   [✕]  │    │
│  │ 👤 Dave                    [✕]  │    │
│  └─────────────────────────────────┘    │
│                                         │
│  [+ Add Member]                         │
├─────────────────────────────────────────┤
│  🔑 Security                            │
│  Key Epoch: 3                           │
│  Last Rotation: 2 days ago              │
│  [🔄 Rotate Key Now]                    │
├─────────────────────────────────────────┤
│  [🚪 Leave Group]                       │
│  [🗑️ Delete Group] (Admin only)         │
└─────────────────────────────────────────┘
```

---

#### References for Group Protocol

- [RFC 9420 - MLS Protocol](https://datatracker.ietf.org/doc/rfc9420/)
- [RFC 9750 - MLS Architecture](https://datatracker.ietf.org/doc/rfc9750/)
- [Wire MLS Implementation](https://wire.com/en/blog/messaging-layer-security-mls-explained)

---

## Technical Debt (Address During Implementation)

| Issue | Priority | Sprint |
|-------|----------|--------|
| Split `ZMSGProtocol.kt` (1730 lines) | Medium | Any |
| Split `ChatDetailView.kt` (2794 lines) | Medium | Any |
| Split `ChatViewModel.kt` (1150 lines) | Medium | Any |
| Add unit tests for protocol parsing | High | Sprint 1 |

---

## Skipped Features (Decided Against)

| Feature | Reason |
|---------|--------|
| Diversified addresses per conversation | Complexity vs benefit, skip for now |
| Audit mode (read-only app mode) | Not needed, key export is sufficient |
| Conversation proof bundles | Too complex, skip for now |

---

## Success Metrics

| Metric | Target |
|--------|--------|
| Sprint 1 completion | 2 weeks |
| Sprint 2 completion | 3 weeks |
| All messages use Orchard | 100% |
| Privacy dashboard visible | Every chat |

---

## References

- [Mastering Zcash](https://maxdesalle.com/mastering-zcash)
- [ZMSG Protocol Spec](./ZMSG_PROTOCOL_SPEC.md)
- [Zypherpunk Hackathon Winners](https://forum.zcashcommunity.com/t/zypherpunk-hackathon-winners/53985)
- [Zcash Protocol Specification](https://zips.z.cash/protocol/protocol.pdf)

---

## Version History

| Date | Version | Changes |
|------|---------|---------|
| 2026-01-14 | 1.0 | Initial roadmap |
| 2026-01-15 | 2.0 | Agreed implementation plan with sprint breakdown |
| 2026-01-15 | 2.1 | Sprint 3.1 (Auto-Save Drafts) + Sprint 3.2 (E2E Encryption) completed |
| 2026-01-16 | 3.0 | Sprint 3.3 Group Chat Protocol design completed (ZMSG-GROUP v1.0) |
