# Enable Swap + Wallet Tab — Design Spec

**Date:** 2026-04-03
**Status:** Final

---

## Problem

ZCHAT users need ZEC to send messages, but most new users don't have ZEC. The app already has a complete NEAR Intents swap system (inherited from Zashi) that supports 20+ tokens → ZEC, but it's unreachable because:

1. The "Wallet" bottom nav tab shows "Coming soon" Toast
2. The onboarding "In-App Swap" card is dimmed with "Coming Soon"
3. No wallet management screen exists in the ZCHAT UI

## Solution

1. Build a Wallet tab screen with balance + Receive/Send/Swap buttons + transaction list
2. Wire Swap to the existing NEAR Intents flow (zero new backend)
3. Wire "More" tab to existing Settings screen
4. Update onboarding to explain swap with step-by-step guide

## Scope

- **In scope:** Wallet tab UI, bottom nav wiring (Wallet + More), onboarding text/styling
- **Out of scope:** New swap backend, Flexa, custom swap UI, new NEAR partner registration (done later)

---

## Architecture

### 1. Wallet Tab Screen

New composable using Nightwire theme, reusing existing Zashi components:

```
┌────────────────────────────┐
│       ● Synced             │  ← sync indicator
│                            │
│     0 ZEC                  │  ← BalanceWidget (shows "0 ZEC" when zero)
│     ≈ $0.00 USD            │
│                            │
│  [Receive] [Send] [Swap]   │  ← 3 action buttons (NightwireColors)
│                            │
│  ── Recent Activity ──     │
│  No transactions yet       │  ← ActivityWidget (empty state or tx list)
│                            │
│  [Chats] [Wallet] [More]   │  ← NightwireBottomNav (Wallet selected)
└────────────────────────────┘
```

**Reused components (no modification needed):**
- `BalanceWidget` — balance display with ZEC + fiat conversion
- `NavigateToReceiveUseCase` — receive button (requests shielded address + navigates)
- `NavigateToSwapUseCase` — swap button (refreshes NEAR assets + navigates to SwapArgs)
- `Send()` route — send button navigation
- `ActivityWidgetVM` / `createActivityWidgets()` — transaction list

**ViewModel:** `WalletTabVM` delegates to existing use cases:
- `balanceState: StateFlow<BalanceWidgetState>` — from `ObserveBalanceUseCase` (existing)
- `onReceive()` — calls `NavigateToReceiveUseCase` (existing)
- `onSend()` — calls `navigationRouter.forward(Send())` (existing pattern from HomeVM)
- `onSwap()` — calls `NavigateToSwapUseCase` (existing)
- `activityWidgets` — from `ActivityWidgetVM` (existing)

No business logic duplication — all use cases already exist.

**Theming:** All UI uses `NightwireColors` — BgBase background, AccentPrimary for buttons, TextPrimary/Secondary for text. Match the chat list visual style.

### 2. Navigation

**Tab switching uses `replace()` not `forward()`:**
- `Chats → Wallet`: `navigationRouter.replace(WalletTab)` — replaces stack, no back-stack growth
- `Wallet → Chats`: `navigationRouter.replace(ChatList)` — same
- `More`: `navigationRouter.forward(AdvancedSettings)` — pushes (back returns to current tab)

**Back button behavior:**
- On Chats tab: back exits app (default)
- On Wallet tab: back goes to Chats tab (intercept via BackHandler)
- On More/Settings: back returns to previous tab

**Bottom nav in both screens:**
Each screen renders its own `NightwireBottomNav` with the correct `selected` state. This is ~10 lines of code per screen — acceptable duplication, avoids complex parent scaffold refactor.

### 3. Swap Flow (Already Built — Zero Changes)

Complete NEAR Intents swap UI exists and works:
```
SwapScreen → SwapAssetPickerScreen → SwapQuoteScreen → ORSwapConfirmationScreen → SwapDetailScreen
```
- Supports: BTC, ETH, SOL, USDC, NEAR, XRP, and 20+ more tokens
- No KYC, decentralized via NEAR solvers
- 50 bps affiliate fee to `electriccoinco.near`
- Error handling, retry, and status tracking all built in

**API Token:**
- JWT is in `NearApiProvider.kt` — hardcoded `Authorization: Bearer <token>` for partner `electriccoin`
- **Phase 1 (now):** Use ECC's token as-is. It works, the code is open source.
- **Phase 2 (later):** Add `ZCHAT_NEAR_JWT=` to `gradle.properties`, read via `BuildConfig`, use if non-empty, fallback to ECC's.

Phase 2 is NOT part of this implementation — it requires registering with NEAR which is an external process.

### 4. Onboarding Update

**OnboardingGetZecView.kt:**
- Change `dimmed = true` to `dimmed = false` on the swap card
- Change icon tint from `NightwireColors.TextTertiary` back to `NightwireColors.AccentPrimary`

**strings.xml:**
```xml
<string name="onboarding_getzec_swap_title">Use In-App Swap</string>
<string name="onboarding_getzec_swap_desc">Deposit BTC, ETH, SOL, USDC or 20+ other tokens and swap to ZEC directly in the app. Go to the Wallet tab → Swap. No account needed.</string>
```

---

## File Change Summary

| # | Action | File | Scope |
|---|--------|------|-------|
| 1 | CREATE | `screen/wallettab/WalletTabRoutes.kt` | `@Serializable object WalletTab` route |
| 2 | CREATE | `screen/wallettab/WalletTabVM.kt` | Delegates to existing use cases |
| 3 | CREATE | `screen/wallettab/WalletTabView.kt` | Nightwire-themed wallet UI |
| 4 | CREATE | `screen/wallettab/AndroidWalletTab.kt` | Koin inject + composable wrapper |
| 5 | MODIFY | `WalletNavGraph.kt` | Register `composable<WalletTab>` |
| 6 | MODIFY | `di/ViewModelModule.kt` | Register `WalletTabVM` |
| 7 | MODIFY | `ChatListView.kt` | Wallet onClick → `replace(WalletTab)`, More → `forward(AdvancedSettings)` |
| 8 | MODIFY | `OnboardingGetZecView.kt` | Un-dim swap card: `dimmed = false`, cyan icon |
| 9 | MODIFY | `res/ui/onboarding/values/strings.xml` | Remove "Coming Soon", add swap description |

**Total:** 4 new files, 5 modified files

---

## Risks

| Risk | Mitigation |
|------|-----------|
| ECC revokes JWT | Low probability — code is open source and forked. Register own token in parallel. |
| NEAR API down | SwapScreen already handles errors with retry UI (inherited from Zashi) |
| BalanceWidget theme mismatch | Apply NightwireColors wrapper in WalletTabView |
| Back-stack confusion | Use `replace()` for tab switches, `BackHandler` on Wallet tab |

---

## Verification Plan

### Compile gate
```
./gradlew :ui-lib:compileZcashmainnetFossDebugSources
```
Must pass with zero errors (project uses `-Werror`).

### ADB test scenarios (on Honor device)

**Test 1 — Fresh onboarding:**
- Clear app data → launch → go through onboarding
- On "Getting ZEC" screen: verify swap card is NOT dimmed, shows active text
- Verify icon is cyan, not grey

**Test 2 — Wallet tab navigation:**
- From chat list, tap "Wallet" in bottom nav
- Verify: balance shows, Receive/Send/Swap buttons visible
- Verify: bottom nav shows "Wallet" as selected (cyan underline)

**Test 3 — Tab switching:**
- On Wallet tab, tap "Chats" → verify returns to chat list
- On chat list, tap "Wallet" → verify returns to wallet
- Press back on Wallet → verify goes to Chats (not exit)

**Test 4 — Swap flow:**
- On Wallet tab, tap "Swap"
- Verify: NEAR Intents asset picker loads (shows BTC, ETH, SOL, etc.)
- Select an asset → verify quote screen appears

**Test 5 — More tab:**
- Tap "More" → verify Settings/Advanced settings screen opens
- Press back → verify returns to previous tab

**Test 6 — Regression:**
- Send a message flow still works
- QR scan still works (Restore → Scan QR)
- Chat list loads existing conversations
