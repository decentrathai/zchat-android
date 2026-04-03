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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.balances.BalanceWidget
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem
import co.electriccoin.zcash.ui.screen.chat.view.components.NightwireBottomNav

@Composable
fun WalletTabView(
    balanceWidgetState: BalanceWidgetState,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onSwap: () -> Unit,
    onChatsTab: () -> Unit,
    onMoreTab: () -> Unit,
) {
    BackHandler { onChatsTab() }

    Scaffold(
        bottomBar = {
            NightwireBottomNav(
                items = listOf(
                    BottomNavItem(
                        label = "Chats",
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Message,
                                contentDescription = "Chats",
                                tint = NightwireColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = onChatsTab
                    ),
                    BottomNavItem(
                        label = "Wallet",
                        icon = {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "Wallet",
                                tint = NightwireColors.AccentPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = true,
                        onClick = { /* Already on wallet */ }
                    ),
                    BottomNavItem(
                        label = "More",
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "More",
                                tint = NightwireColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        selected = false,
                        onClick = onMoreTab
                    ),
                )
            )
        },
        containerColor = NightwireColors.BgBase
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header
            Text(
                text = "Wallet",
                fontSize = 20.sp,
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.Bold,
                color = NightwireColors.TextPrimary,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Nightwire-styled balance (replaces Zashi BalanceWidget visually)
            // We still use BalanceWidget underneath for fiat conversion,
            // but overlay our own styled balance on top
            Text(
                text = "ZEC",
                fontSize = 14.sp,
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = NightwireColors.TextSecondary,
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
                    label = "Receive",
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallReceived,
                            contentDescription = "Receive",
                            tint = NightwireColors.TextOnAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onReceive,
                    modifier = Modifier.weight(1f)
                )
                WalletActionButton(
                    label = "Send",
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = NightwireColors.TextOnAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onSend,
                    modifier = Modifier.weight(1f)
                )
                WalletActionButton(
                    label = "Swap",
                    icon = {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Swap",
                            tint = NightwireColors.TextOnAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = onSwap,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Empty state with helper
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = NightwireColors.TextTertiary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No transactions yet",
                        fontSize = 16.sp,
                        fontFamily = RajdhaniFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = NightwireColors.TextTertiary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap Swap to deposit crypto and get ZEC",
                        fontSize = 13.sp,
                        color = NightwireColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
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
    Button(
        onClick = onClick,
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(NightwireColors.RadiusButton),
                ambientColor = NightwireColors.AccentPrimaryGlow,
                spotColor = NightwireColors.AccentPrimaryGlow,
            ),
        shape = RoundedCornerShape(NightwireColors.RadiusButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = NightwireColors.AccentPrimary,
            contentColor = NightwireColors.TextOnAccent,
        ),
    ) {
        icon()
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
