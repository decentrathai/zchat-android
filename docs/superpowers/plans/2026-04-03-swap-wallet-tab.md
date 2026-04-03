# Swap + Wallet Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the Wallet bottom nav tab with balance display + Receive/Send/Swap buttons, wiring Swap to the existing NEAR Intents flow, and update onboarding to explain swap.

**Architecture:** Create a new WalletTabView composable that reuses existing BalanceWidgetVM, NavigateToReceiveUseCase, NavigateToSwapUseCase, and Send route. Wire bottom nav tabs to real navigation using NavigationRouter.replace(). Update onboarding strings to un-dim the swap card.

**Tech Stack:** Kotlin, Jetpack Compose, Koin DI, CameraX (existing), NEAR Intents API (existing)

**Spec:** `docs/superpowers/specs/2026-04-03-swap-wallet-tab-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `screen/wallettab/WalletTabRoutes.kt` | CREATE | Serializable route object |
| `screen/wallettab/WalletTabVM.kt` | CREATE | ViewModel: balance state + button handlers |
| `screen/wallettab/WalletTabView.kt` | CREATE | Composable UI: Nightwire-themed wallet screen |
| `screen/wallettab/AndroidWalletTab.kt` | CREATE | Koin injection wrapper |
| `WalletNavGraph.kt` | MODIFY | Register WalletTab route |
| `di/ViewModelModule.kt` | MODIFY | Register WalletTabVM |
| `screen/chat/view/ChatListView.kt` | MODIFY | Wire Wallet/More tab navigation |
| `screen/onboarding/view/OnboardingGetZecView.kt` | MODIFY | Un-dim swap card |
| `res/ui/onboarding/values/strings.xml` | MODIFY | Update swap text |

---

### Task 1: Route Object + ViewModel

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/WalletTabRoutes.kt`
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/WalletTabVM.kt`

- [ ] **Step 1: Create route object**

```kotlin
// WalletTabRoutes.kt
package co.electriccoin.zcash.ui.screen.wallettab

import kotlinx.serialization.Serializable

@Serializable
object WalletTab
```

- [ ] **Step 2: Create WalletTabVM**

```kotlin
// WalletTabVM.kt
package co.electriccoin.zcash.ui.screen.wallettab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.NavigateToReceiveUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapUseCase
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.chat.AndroidChat
import kotlinx.coroutines.launch

class WalletTabVM(
    private val navigationRouter: NavigationRouter,
    private val navigateToReceive: NavigateToReceiveUseCase,
    private val navigateToSwap: NavigateToSwapUseCase,
) : ViewModel() {

    fun onReceive() = viewModelScope.launch { navigateToReceive() }

    fun onSend() = navigationRouter.forward(Send())

    fun onSwap() = viewModelScope.launch { navigateToSwap() }

    fun onChatsTab() = navigationRouter.replace(co.electriccoin.zcash.ui.screen.chat.ChatList)

    fun onMoreTab() {
        navigationRouter.forward(
            co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsArgs
        )
    }
}
```

- [ ] **Step 3: Compile check**

Run: `cd /home/yourt/zchat-android && ./gradlew :ui-lib:compileZcashmainnetFossDebugSources`
Expected: BUILD SUCCESSFUL (or errors to fix — the imports for ChatList and AdvancedSettingsArgs may need adjustment based on actual package paths)

- [ ] **Step 4: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/
git commit -m "feat: add WalletTab route and ViewModel"
```

---

### Task 2: Wallet Tab View (Nightwire UI)

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/WalletTabView.kt`

- [ ] **Step 1: Create WalletTabView composable**

The view should:
- Use `NightwireColors.BgBase` as background
- Show `BalanceWidget` at top (from existing `BalanceWidgetVM`)
- Show 3 action buttons: Receive, Send, Swap — styled with NightwireColors
- Show `NightwireBottomNav` with "Wallet" selected
- Include `BackHandler` that navigates to Chats

Reference existing code patterns:
- `HomeView.kt` lines 84-92 for BalanceWidget usage
- `HomeView.kt` lines 157-198 for NavButtons layout
- `ChatListView.kt` lines 355-406 for NightwireBottomNav

The buttons should use `ZashiButton` or custom Nightwire-styled buttons (rounded-lg, 8dp radius) with icons from `Icons.Default`:
- Receive: `Icons.Default.CallReceived` or existing `ic_home_receive`
- Send: `Icons.Default.Send` or existing `ic_home_send`
- Swap: `Icons.Default.SwapHoriz` or existing `ic_home_swap`

- [ ] **Step 2: Compile check**

Run: `./gradlew :ui-lib:compileZcashmainnetFossDebugSources`

- [ ] **Step 3: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/WalletTabView.kt
git commit -m "feat: add WalletTabView with Nightwire theme"
```

---

### Task 3: Android Wrapper + DI Registration

**Files:**
- Create: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/AndroidWalletTab.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/di/ViewModelModule.kt`

- [ ] **Step 1: Create AndroidWalletTab wrapper**

Follow the `AndroidHome.kt` pattern:

```kotlin
// AndroidWalletTab.kt
package co.electriccoin.zcash.ui.screen.wallettab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetArgs
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetVM
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AndroidWalletTab() {
    val balanceWidgetVM = koinViewModel<BalanceWidgetVM> {
        parametersOf(
            BalanceWidgetArgs(
                isBalanceButtonEnabled = false,
                isExchangeRateButtonEnabled = true,
                showDust = false,
            )
        )
    }
    val walletTabVM = koinViewModel<WalletTabVM>()
    val balanceState by balanceWidgetVM.state.collectAsStateWithLifecycle()

    WalletTabView(
        balanceWidgetState = balanceState,
        onReceive = { walletTabVM.onReceive() },
        onSend = { walletTabVM.onSend() },
        onSwap = { walletTabVM.onSwap() },
        onChatsTab = { walletTabVM.onChatsTab() },
        onMoreTab = { walletTabVM.onMoreTab() },
    )
}
```

- [ ] **Step 2: Register WalletTabVM in ViewModelModule.kt**

Add after the existing `viewModelOf(::InviteFriendVM)` line (~line 196):

```kotlin
viewModelOf(::WalletTabVM)
```

And add the import at the top:
```kotlin
import co.electriccoin.zcash.ui.screen.wallettab.WalletTabVM
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :ui-lib:compileZcashmainnetFossDebugSources`

- [ ] **Step 4: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/wallettab/AndroidWalletTab.kt
git add ui-lib/src/main/java/co/electriccoin/zcash/di/ViewModelModule.kt
git commit -m "feat: add AndroidWalletTab wrapper and register VM"
```

---

### Task 4: Navigation Wiring

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/WalletNavGraph.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt`

- [ ] **Step 1: Register WalletTab in WalletNavGraph.kt**

Add after the `composable<Home>` line (~line 234):

```kotlin
composable<WalletTab> { AndroidWalletTab() }
```

Add imports:
```kotlin
import co.electriccoin.zcash.ui.screen.wallettab.AndroidWalletTab
import co.electriccoin.zcash.ui.screen.wallettab.WalletTab
```

- [ ] **Step 2: Wire bottom nav in ChatListView.kt**

Replace the Wallet tab Toast (line ~387) with:
```kotlin
onClick = {
    navigationRouter.replace(WalletTab)
}
```

Replace the More tab Toast (line ~402) with:
```kotlin
onClick = {
    navigationRouter.forward(
        co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsArgs
    )
}
```

Add import:
```kotlin
import co.electriccoin.zcash.ui.screen.wallettab.WalletTab
```

NOTE: ChatListView must receive `navigationRouter` as a parameter. Check if it already does — look at the function signature. If not, pass it from AndroidChatList.

- [ ] **Step 3: Compile check**

Run: `./gradlew :ui-lib:compileZcashmainnetFossDebugSources`

- [ ] **Step 4: Build full APK and test on device**

Run: `./gradlew :app:assembleZcashmainnetFossDebug`
Install on Honor via ADB. Test:
- Tap "Wallet" tab → wallet screen shows with balance + 3 buttons
- Tap "Chats" → returns to chat list
- Tap "More" → settings opens
- Back from settings → returns
- Tap "Swap" → NEAR Intents asset picker opens

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/WalletNavGraph.kt
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt
git commit -m "feat: wire Wallet and More bottom nav tabs to real screens"
```

---

### Task 5: Onboarding Update

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/onboarding/view/OnboardingGetZecView.kt`
- Modify: `ui-lib/src/main/res/ui/onboarding/values/strings.xml`

- [ ] **Step 1: Update strings.xml**

Change:
```xml
<string name="onboarding_getzec_swap_title">Use In-App Swap — Coming Soon</string>
<string name="onboarding_getzec_swap_desc">Swap other crypto for ZEC directly in the app. This feature is under development.</string>
```
To:
```xml
<string name="onboarding_getzec_swap_title">Use In-App Swap</string>
<string name="onboarding_getzec_swap_desc">Deposit BTC, ETH, SOL, USDC or 20+ other tokens and swap to ZEC directly in the app. Go to the Wallet tab → Swap. No account needed.</string>
```

- [ ] **Step 2: Un-dim the swap card in OnboardingGetZecView.kt**

Change the swap card from:
```kotlin
GetZecOption(
    icon = { Icon(Icons.Default.Wallet, null, tint = NightwireColors.TextTertiary, ...) },
    title = stringResource(R.string.onboarding_getzec_swap_title),
    description = stringResource(R.string.onboarding_getzec_swap_desc),
    dimmed = true
)
```
To:
```kotlin
GetZecOption(
    icon = { Icon(Icons.Default.Wallet, null, tint = NightwireColors.AccentPrimary, ...) },
    title = stringResource(R.string.onboarding_getzec_swap_title),
    description = stringResource(R.string.onboarding_getzec_swap_desc),
    dimmed = false
)
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :ui-lib:compileZcashmainnetFossDebugSources`

- [ ] **Step 4: Test onboarding on device**

Clear app data, launch, go through onboarding to "Getting ZEC" screen.
Verify:
- Swap card is NOT dimmed
- Icon is cyan (AccentPrimary), not grey
- Text says "Use In-App Swap" (no "Coming Soon")
- Description mentions BTC, ETH, SOL, USDC

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/onboarding/view/OnboardingGetZecView.kt
git add ui-lib/src/main/res/ui/onboarding/values/strings.xml
git commit -m "feat: enable In-App Swap in onboarding, remove Coming Soon"
```

---

### Task 6: Full Regression Test

- [ ] **Step 1: Build final APK**

```bash
./gradlew :app:assembleZcashmainnetFossDebug
```

- [ ] **Step 2: Install on Honor**

```bash
adb install -r app/build/outputs/apk/zcashmainnetFoss/debug/zchat-v2.10.4-zcashmainnetFossDebug.apk
```

- [ ] **Step 3: Test fresh onboarding (clear data first)**

- Clear app data
- Launch → "Start Chatting" → Skip PIN → Identity Ready → How It Works → "I Need ZEC"
- Verify swap card is active (not dimmed)
- Complete onboarding → chat list

- [ ] **Step 4: Test Wallet tab**

- Tap "Wallet" → balance + Receive/Send/Swap visible
- Tap "Chats" → back to chat list
- Tap "Wallet" again → wallet shows
- Press back on Wallet → goes to Chats

- [ ] **Step 5: Test Swap flow**

- On Wallet tab → tap "Swap"
- NEAR Intents asset picker loads
- Select BTC → quote screen appears
- Back out → returns to wallet

- [ ] **Step 6: Test More tab**

- Tap "More" → Settings screen
- Back → returns to previous tab

- [ ] **Step 7: Test regressions**

- QR scan from Restore flow works
- Chat list loads
- Send message flow works (Start a Chat → select contact)

- [ ] **Step 8: Update server APK**

```bash
cp app/build/outputs/apk/zcashmainnetFoss/debug/zchat-*.apk ~/zchat-v2.10.4.apk
cp app/build/outputs/apk/zcashmainnetFoss/debug/zchat-*.apk /mnt/c/Users/yourt/Downloads/zchat-v2.10.4.apk
```

- [ ] **Step 9: Final commit**

```bash
git add -A
git commit -m "feat: enable Wallet tab with Swap, Receive, Send + onboarding update"
```
