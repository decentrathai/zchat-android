# ZChat Project Memory
## (Maintained by technical copilot - do NOT delete)

### Last Updated: 2026-02-06 (Sprint Day 1 + Codex fixes)

---

## DECISIONS LOG
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-02-06 | Fix outgoing msg disappearance via multi-layered peer resolution | getRecipient() returns wrong/null address for multi-output txs |
| 2026-02-06 | Only dedup pending msgs against MINED confirmed messages | Prevents premature pending removal when SDK state is transient |
| 2026-02-06 | Use ResolutionSelector instead of deprecated setTargetResolution | CameraX 1.4.1 supports it; fixes foldable device QR scanning |
| 2026-02-06 | Add rotation-aware ZXing strategies (90/180/270) | Foldable devices report non-standard camera rotation |

## BUG PATTERNS
| Pattern | Location | Root Cause | Fix Applied |
|---------|----------|------------|-------------|
| Outgoing messages disappear | ChatViewModel:569-612 | getRecipient() only takes firstOrNull() from Flow, can return platform fee addr or null for multi-output txs | Added resolveOutgoingPeerAddress() with 4 fallback strategies |
| QR scan fails on foldables | ScanView.kt:638 | Hardcoded ROTATION_0 + deprecated 16:9 resolution | Dynamic rotation + ResolutionSelector + camera rebinding on fold/unfold |
| Pending msg removed but confirmed not shown | ChatViewModel:887-944 | Dedup matched against non-mined tx, then tx skipped on next sync | Only dedup against messages with minedHeight != null |
| QR scan freezes after invalid scan | QrCodeAnalyzerImpl + ScanGenericAddressVM | hasScanned one-shot latch never resets; VM flag never resets on failure | Added resetScanLatch() to interface, reset on INVALID validation |
| Reply lands in wrong chat (misrouting) | ChatViewModel resolveOutgoingPeerAddress Strategy 4 | Content-hash pending match has no peer uniqueness constraint | Require unique match or same-peer consensus; skip ambiguous |
| Zip321 invalid address locks scanner | ScanZashiAddressVM:115 | hasBeenScannedSuccessfully=true set unconditionally even on INVALID | Only set true on valid path |

## KNOWN-GOOD FIXES
- Multi-layered peer address resolution: tx.recipient -> allRecipients -> convId lookup -> pending content match
- ResolutionSelector with RATIO_16_9_FALLBACK_AUTO_STRATEGY for camera
- LaunchedEffect(imageAnalysis, cameraProvider) for fold/unfold rebinding
- Adaptive center crop using minOf(width, height) for near-square frames
- QrCodeAnalyzer.resetScanLatch() called when validation INVALID - allows retry without recreating screen
- Pending match Strategy 4: require unique match OR all matches pointing to same peer
- ScanGenericAddressVM: reset hasBeenScannedSuccessfully=false on failure

## KNOWN-BAD APPROACHES
- Using .firstOrNull() on getRecipients() Flow - returns random recipient for multi-output txs
- Hardcoding ROTATION_0 for camera - breaks on foldables
- Using confirmedIds (all tx IDs) to filter pendingMessages - IDs don't match (pending_ vs txhash)
- Removing pending from SharedPreferences based on non-mined tx match - tx may not resolve next sync
- One-shot hasScanned latch with no reset - freezes scanner after first invalid QR
- Setting hasBeenScannedSuccessfully=true before knowing if validation passed
- Content-hash-only pending match without peer constraint - misroutes identical messages ("hi")

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

## SYSTEMIC RISKS
1. ChatViewModel.kt is 2920+ lines (GOD OBJECT) - every chat bug touches it
2. No Room database - all chat derived from blockchain re-scan + SharedPreferences
3. Silent exception swallowing (catch -> return null) throughout protocol layer
4. Legacy protocol versions (v2/v3) still supported, increasing complexity
5. No automated tests for chat/messaging layer
6. SharedPreferences for critical state (convID mappings) - no transactional guarantees
