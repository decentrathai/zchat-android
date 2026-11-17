@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.screen.wallettab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.clip
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.toZecString
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.ZashiIconButton
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.balances.BalanceWidget
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.chat.view.chatColors
import co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem
import co.electriccoin.zcash.ui.screen.chat.view.components.NightwireBottomNav
import co.electriccoin.zcash.ui.screen.transactionhistory.widget.ActivityWidgetState
import co.electriccoin.zcash.ui.screen.transactionhistory.widget.createActivityWidgets
import co.electriccoin.zcash.ui.screen.update.UpdateRequiredBar
import co.electriccoin.zcash.ui.screen.whatsnew.upgrade.UpgradeCatchUpBar

@Composable
fun WalletTabView(
    // K0-3: the selected account's chip, only when the phone holds more than one account.
    accountChip: WalletAccountChipState?,
    // SY-1: the catch-up bar's percentage after an upgrade (the chat list's N), or null when it is not shown.
    catchUpPercent: Int?,
    balanceWidgetState: BalanceWidgetState,
    activityWidgetState: ActivityWidgetState,
    shieldableTransparent: Zatoshi?,
    oldBalanceCard: OldBalanceCardState?,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onSwap: () -> Unit,
    onShield: () -> Unit,
    onMoveOldBalance: () -> Unit,
    onDismissOldBalance: () -> Unit,
    onChatsTab: () -> Unit,
    onAiTab: () -> Unit,
    onMoreTab: () -> Unit,
    updateRequired: Boolean = false,
    // QA2 Q2-06 (ZC-166): the same show/hide balances eye as the other wallet screens, so hidden balances can be
    // shown again from this tab.
    balanceVisibilityButton: IconButtonState? = null,
) {
    BackHandler { onChatsTab() }

    val cc = chatColors()

    Scaffold(
        bottomBar = {
            NightwireBottomNav(
                items = listOf(
                    BottomNavItem(
                        label = stringResource(R.string.tab_chats),
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Message,
                                contentDescription = stringResource(R.string.tab_chats),
                                tint = cc.textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = onChatsTab
                    ),
                    BottomNavItem(
                        label = stringResource(R.string.tab_wallet),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = stringResource(R.string.tab_wallet),
                                tint = cc.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = true,
                        onClick = { /* Already on wallet */ }
                    ),
                    BottomNavItem(
                        label = stringResource(R.string.tab_ai),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = stringResource(R.string.tab_ai),
                                tint = cc.textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = onAiTab
                    ),
                    BottomNavItem(
                        label = stringResource(R.string.home_button_more),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = stringResource(R.string.home_button_more),
                                tint = cc.textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = onMoreTab
                    ),
                )
            )
        },
        containerColor = cc.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tab_wallet),
                    fontSize = 20.sp,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary,
                )
                if (balanceVisibilityButton != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // QA2 ZC-1706: the eye takes the app theme's text colour; the drawable-night stroke follows the
                    // phone's mode and was pale grey on the Nightwire Light page.
                    ZashiIconButton(balanceVisibilityButton, modifier = Modifier.size(40.dp), tint = cc.textPrimary)
                }
            }

            if (accountChip != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AccountChip(accountChip)
            }

            if (updateRequired) {
                Spacer(modifier = Modifier.height(12.dp))
                UpdateRequiredBar(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // SY-1: the catch-up bar, above the balance it warns about (What's New: "on the wallet screen").
            if (catchUpPercent != null) {
                Spacer(modifier = Modifier.height(12.dp))
                UpgradeCatchUpBar(
                    percent = catchUpPercent,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Nightwire-styled balance (replaces Zashi BalanceWidget visually)
            // We still use BalanceWidget underneath for fiat conversion,
            // but overlay our own styled balance on top
            Text(
                text = "ZEC",
                fontSize = 14.sp,
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = cc.textSecondary,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Large balance number — use BalanceWidget's data but our own styling
            // For now show the Zashi widget which has the actual balance logic
            BalanceWidget(
                state = balanceWidgetState,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons row: Receive, Send, Swap
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WalletActionButton(
                    label = stringResource(R.string.home_button_receive),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallReceived,
                            contentDescription = stringResource(R.string.home_button_receive),
                            tint = cc.textOnAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onReceive,
                    modifier = Modifier.weight(1f)
                )
                WalletActionButton(
                    label = stringResource(R.string.home_button_send),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.home_button_send),
                            tint = cc.textOnAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onSend,
                    modifier = Modifier.weight(1f)
                )
                WalletActionButton(
                    label = stringResource(R.string.home_button_swap),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.home_button_swap),
                            tint = cc.textOnAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onSwap,
                    modifier = Modifier.weight(1f)
                )
            }

            // 1-click shield entry point — only when there are transparent funds to shield.
            if (shieldableTransparent != null) {
                Spacer(modifier = Modifier.height(16.dp))
                ShieldTransparentBanner(amount = shieldableTransparent, onShield = onShield)
            }

            // The "Old balance" card: the sealed Orchard pool holds something (sweep spec E1).
            if (oldBalanceCard != null) {
                Spacer(modifier = Modifier.height(16.dp))
                OldBalanceBanner(
                    state = oldBalanceCard,
                    onAction = onMoveOldBalance,
                    onDismiss = onDismissOldBalance,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transaction history from Zashi's ActivityWidget
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp)
            ) {
                createActivityWidgets(state = activityWidgetState)
            }
        }
    }
}

/** K0-3: the selected account's name and icon; a tap opens the account list. */
@Composable
private fun AccountChip(state: WalletAccountChipState) {
    val cc = chatColors()
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(cc.backgroundLight)
                .clickable(
                    onClickLabel = stringResource(R.string.wallet_tab_account_chip_cd),
                    onClick = state.onClick,
                ).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(state.icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = state.name.getValue(),
            color = cc.textPrimary,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = stringResource(R.string.wallet_tab_account_chip_cd),
            tint = cc.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ShieldTransparentBanner(amount: Zatoshi, onShield: () -> Unit) {
    val cc = chatColors()
    val locale = rememberDesiredFormatLocale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cc.backgroundLight)
            .clickable { onShield() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = cc.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Shield transparent funds",
                color = cc.textPrimary,
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = "${amount.toZecString(locale)} ZEC is public — shield it to spend privately.",
                color = cc.textSecondary,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Shield",
            color = cc.primary,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun OldBalanceBanner(
    state: OldBalanceCardState,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cc = chatColors()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.backgroundLight)
                .clickable { onAction() }
                .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = cc.warning,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = state.title.getValue(),
                color = cc.textPrimary,
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.body.getValue(),
            color = cc.textSecondary,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            if (state.isDismissible) {
                Text(
                    text = stringResource(R.string.move_old_balance_card_dismiss),
                    color = cc.textSecondary,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }
            Text(
                text = state.action.getValue(),
                color = cc.primary,
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onAction() },
            )
        }
    }
}

@Composable
private fun WalletActionButton(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cc = chatColors()
    Button(
        onClick = onClick,
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(NightwireColors.RadiusButton),
                ambientColor = cc.accentPrimaryGlow,
                spotColor = cc.accentPrimaryGlow,
            ),
        shape = RoundedCornerShape(NightwireColors.RadiusButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = cc.primary,
            contentColor = cc.textOnAccent,
        ),
    ) {
        icon()
        Spacer(modifier = Modifier.width(6.dp))
        // QA2 Q2-20 (ZC-1702): at 1.3x font scale the Receive label was ellipsized; the label shrinks to fit.
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = LocalContentColor.current,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp, stepSize = 0.5.sp),
        )
    }
}
