# ZChat Project Memory
## (Maintained by technical copilot - do NOT delete)

### Last Updated: 2026-02-12 (Notification System - 5-phase implementation complete)

---

## DECISIONS LOG
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-02-06 | Fix outgoing msg disappearance via multi-layered peer resolution | getRecipient() returns wrong/null address for multi-output txs |
| 2026-02-06 | Only dedup pending msgs against MINED confirmed messages | Prevents premature pending removal when SDK state is transient |
| 2026-02-06 | Use ResolutionSelector instead of deprecated setTargetResolution | CameraX 1.4.1 supports it; fixes foldable device QR scanning |
| 2026-02-06 | Add rotation-aware ZXing strategies (90/180/270) | Foldable devices report non-standard camera rotation |
| 2026-02-07 | Change lastMessage selection from sortedMessages.lastOrNull() to maxByOrNull { timestamp } | Block-height sort biased pending msgs (Long.MAX_VALUE) to always show as latest |
| 2026-02-07 | Dedup pending against txId != null (was minedHeight != null) | Unmined confirmed msgs in mempool weren't matched, causing duplicates |
| 2026-02-07 | Split dedup into mined (permanent remove) vs unmined (UI suppress only) | Safety: if unmined tx fails/disappears, pending resurfaces |
| 2026-02-07 | Add reverseLayout=true to ChatDetailView LazyColumn | Standard messenger UX: newest messages at bottom |
| 2026-02-07 | Classify insufficient-funds as pending-change vs truly-insufficient | 3rd message sends fail because change note is unconfirmed (~75s), misleading UX |
| 2026-02-07 | Establish Claude-Codex intercommunication channel | Append-only markdown file for dual-agent collaboration on project |
| 2026-02-09 | Rename "Deep Cyber" → "Zypherpunk", delete "Cyberpunk" theme | Only 3 themes needed: Light, Dark, Zypherpunk. Old prefs auto-migrated |
| 2026-02-09 | Use wallet birthday for sync block range display | Old formula calculated from block 0, showing misleading range |
| 2026-02-09 | Add reverse convId lookup for diversified address resolution | convId→peer lookup may fail after restore; peer→convId reverse lookup repairs |
| 2026-02-09 | Make chatColors() theme-aware via ZashiColors.Surfaces.bgPrimary check | Was hardcoded to CyberpunkChatColors, ignoring active theme |
| 2026-02-12 | Implement 5-phase notification system | Full notifications: custom sound, channel migration, lock screen privacy, MessagingStyle |
| 2026-02-12 | Reduce WorkManager sync to 15 minutes + AlarmManager fallback | 6-hour interval too slow for timely message notifications |
| 2026-02-12 | Version notification channel ID (chat_messages_v2) | Android channels immutable after creation; must create new channel for sound/vibration changes |
| 2026-02-12 | Add per-conversation mute with SharedPreferences Set<String> | Simpler than Room; consistent with existing preference patterns |
| 2026-02-12 | Navigate More > Notifications to full settings screen | Replaces inline dialog; centralizes all notification controls |
| 2026-02-12 | Add InAppNotificationManager with StateFlow | Foreground app needs banner instead of system notification; auto-dismiss after 4s |

## BUG PATTERNS
| Pattern | Location | Root Cause | Fix Applied |
|---------|----------|------------|-------------|
| Outgoing messages disappear | ChatViewModel:569-612 | getRecipient() only takes firstOrNull() from Flow, can return platform fee addr or null for multi-output txs | Added resolveOutgoingPeerAddress() with 4 fallback strategies |
| QR scan fails on foldables | ScanView.kt:638 | Hardcoded ROTATION_0 + deprecated 16:9 resolution | Dynamic rotation + ResolutionSelector + camera rebinding on fold/unfold |
| Pending msg removed but confirmed not shown | ChatViewModel:887-944 | Dedup matched against non-mined tx, then tx skipped on next sync | Only dedup against messages with minedHeight != null |
| QR scan freezes after invalid scan | QrCodeAnalyzerImpl + ScanGenericAddressVM | hasScanned one-shot latch never resets; VM flag never resets on failure | Added resetScanLatch() to interface, reset on INVALID validation |
| Reply lands in wrong chat (misrouting) | ChatViewModel resolveOutgoingPeerAddress Strategy 4 | Content-hash pending match has no peer uniqueness constraint | Require unique match or same-peer consensus; skip ambiguous |
| Zip321 invalid address locks scanner | ScanZashiAddressVM:115 | hasBeenScannedSuccessfully=true set unconditionally even on INVALID | Only set true on valid path |
| Chat list wrong latest message | ChatViewModel:1035 | lastMessage used sortedMessages.lastOrNull() which sorted pending to end (Long.MAX_VALUE) | Use messages.maxByOrNull { it.timestamp } |
| Message order inverted in chat | ChatDetailView:525 | LazyColumn had no reverseLayout; messages rendered oldest-at-top | Added reverseLayout=true + displayMessages.asReversed() + scroll to item(0) |
| Duplicate sent messages | ChatViewModel:949-1013 | Dedup only matched pending against minedHeight!=null; unmined confirmed msgs not matched | Match against txId!=null; mined→permanent remove, unmined→UI suppress only |
| False "Insufficient ZEC" on 3rd msg | CreateChunkedMessageProposalUseCase | After sending, change note is unconfirmed (~75s); SDK correctly reports insufficient spendable, but UX is misleading | Added hasPendingShieldedBalanceBlockingSpend() classifier; throws specific "Please wait" message |
| Cyberpunk SendSuccessView compile fail | ZchatComposeView.kt | CyberpunkBase is internal to ui-design-lib; OrbitronFontFamily wrong import path | Use inline hex color literals; import from public typography package |
| Sync status shows wrong block range | ChatViewModel:320 | Formula `blockHeight * (1 - progress/100)` calculates from block 0 | Use walletBirthday as start: `birthday + (blockHeight - birthday) * progress / 100` |
| "Sent!" screen after first chat msg | AndroidChat.kt:404 | SendMessageState.Success not handled, Zashi confirmation screen shown | Handle Success state: show Toast + resetSendState() |
| chatColors() returns hardcoded theme | ChatThemeColors.kt:149 | `chatColors()` always returned CyberpunkChatColors | Check ZashiColors.Surfaces.bgPrimary to determine active theme |
| Same-wallet msgs in separate chats | ChatViewModel:710 | convId→peer lookup fails when mapping lost; hash mismatch for diversified addrs | Added reverse peer→convId lookup + diversified addr hash caching |

## KNOWN-GOOD FIXES
- Multi-layered peer address resolution: tx.recipient -> allRecipients -> convId lookup -> pending content match
- ResolutionSelector with RATIO_16_9_FALLBACK_AUTO_STRATEGY for camera
- LaunchedEffect(imageAnalysis, cameraProvider) for fold/unfold rebinding
- Adaptive center crop using minOf(width, height) for near-square frames
- QrCodeAnalyzer.resetScanLatch() called when validation INVALID - allows retry without recreating screen
- Pending match Strategy 4: require unique match OR all matches pointing to same peer
- ScanGenericAddressVM: reset hasBeenScannedSuccessfully=false on failure
- lastMessage by maxByOrNull { it.timestamp } instead of sortedMessages.lastOrNull() - fixes chat list preview
- reverseLayout=true + asReversed() + scroll to item(0) - standard messenger ordering
- Dedup two-tier: match txId!=null, mined→permanent remove from prefs, unmined→UI suppress only (safety)
- hasPendingShieldedBalanceBlockingSpend(): classifies spendable<required AND total>=required AND pending>0
- Inline hex colors (0xFF0D0B1A, 0xFF00FFFF, etc.) when internal palette objects can't be accessed cross-module
- AndroidChat: preserve specific "Please wait" error message before generic insufficient balance fallback
- Wallet birthday from PersistableWalletProvider for accurate sync block range display
- Reverse convId lookup: when convId→peer fails, scan messagesByPeer.keys for peer→convId match
- Diversified address caching: when convId resolves but sender differs, cache hash→storedPeer mapping
- chatColors() checks ZashiColors.Surfaces.bgPrimary to select theme-appropriate ChatColors
- Theme migration: fromString() maps "cyberpunk"/"deep_cyber" → ZYPHERPUNK for backward compat

## KNOWN-BAD APPROACHES
- Using .firstOrNull() on getRecipients() Flow - returns random recipient for multi-output txs
- Hardcoding ROTATION_0 for camera - breaks on foldables
- Using confirmedIds (all tx IDs) to filter pendingMessages - IDs don't match (pending_ vs txhash)
- Removing pending from SharedPreferences based on non-mined tx match - tx may not resolve next sync
- One-shot hasScanned latch with no reset - freezes scanner after first invalid QR
- Setting hasBeenScannedSuccessfully=true before knowing if validation passed
- Content-hash-only pending match without peer constraint - misroutes identical messages ("hi")
- Dedup only against minedHeight!=null - misses unmined confirmed msgs in mempool, causes duplicates
- sortedMessages.lastOrNull() for preview - biased by block-height sort putting pending at end
- No reverseLayout on chat LazyColumn - inverts message order (newest at top)
- Generic "Insufficient balance" error for pending-change scenario - misleading when user has enough total balance

## DIAGNOSTIC TAGS (added Day 1)
| Tag | Location | What It Traces |
|-----|----------|----------------|
| ZCHAT_FLOW | ChatViewModel convertToConversations | Every `continue` point with skip reason |
| ZCHAT_DIAG | ChatViewModel convertToConversations | Summary: total/added/skipped counts, per-conversation breakdown |
| ZCHAT_PROTO | ZMSGProtocol.parseMemo() | Which parser branch taken, result status |
| ZCHAT_RECIPIENTS | TransactionRepository | getRecipient/getAllRecipients results + counts |
| ZCHAT_E2E | E2EEncryption | Decrypt failures with key/message metadata |
| ZCHAT_RESOLVE | ChatViewModel resolveOutgoingPeerAddress | Which resolution strategy succeeded |
| ZCHAT_DEDUP | ChatViewModel | Pending dedup matches against mined confirmed |
| ZCHAT_THREADING | ChatViewModel | Incoming message routing decisions |
| ZCHAT_V4 | ChatViewModel | v4 convID-based routing step-by-step |
| ZCHAT_DEDUP_UNMINED | ChatViewModel | pendingSuppressedByUnmined counter for unmined dedup matches |

## SYSTEMIC RISKS
1. ChatViewModel.kt is 2920+ lines (GOD OBJECT) - every chat bug touches it
2. No Room database - all chat derived from blockchain re-scan + SharedPreferences
3. Silent exception swallowing (catch -> return null) throughout protocol layer
4. Legacy protocol versions (v2/v3) still supported, increasing complexity
5. No automated tests for chat/messaging layer
6. SharedPreferences for critical state (convID mappings) - no transactional guarantees
7. ~~Notification system not yet implemented~~ **RESOLVED 2026-02-12** - Full 5-phase notification system implemented
8. No regression tests for dedup transitions, preview selection, or message ordering

## CLAUDE-CODEX INTERCOMMUNICATION CHANNEL
- **File**: `docs/CLAUDE_CODEX_CHANNEL.md`
- **Protocol**: Append-only markdown. Each party reads previous messages, then appends reply at bottom.
- **Format**: `## [SENDER] — [DATE] — [TOPIC]` followed by content.
- **Rules**: Do NOT edit or delete previous messages. Only APPEND new ones.
- **Purpose**: Two AI agents (Claude Opus 4.6 + Codex) collaborating on the project with full context sharing.
- **Workflow**: Human sends task to Claude → Claude researches & writes code → Claude writes Codex prompt → Codex responds with analysis/patches → Claude reviews, integrates, compiles, verifies → Results shared back via channel file.
- **Status**: Active as of 2026-02-07. Both agents have acknowledged the protocol.
