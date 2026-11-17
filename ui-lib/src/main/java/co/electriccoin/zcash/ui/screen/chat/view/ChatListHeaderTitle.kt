package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.toZecString
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import java.util.Locale

/**
 * The largest font scale the Chats header follows. The bar is 64dp high and shares its width with five action icons;
 * at 1.3 the two lines and the whole balance just fit (ChatListHeaderBalanceNotClippedTest). Above it the header
 * stays at the 1.3 size instead of cutting the balance; the Wallet tab shows the balance at full size.
 */
private const val MAX_HEADER_FONT_SCALE = 1.3f

/**
 * The balance the Chats header shows: the SDK formatter's 8 decimals in [locale], with trailing zeros and a trailing
 * separator trimmed ("0.00018 ZEC", "0,01320586 ZEC", "10 ZEC"); an empty wallet reads "0 ZEC". The trim is safe
 * for whole amounts because the formatter always writes the 8 decimals.
 */
internal fun chatListHeaderBalanceText(
    balance: Zatoshi,
    locale: Locale
): String =
    if (balance.value == 0L) {
        "0 ZEC"
    } else {
        val zec =
            balance
                .toZecString(locale)
                .trimEnd('0')
                .trimEnd('.', ',')
        "$zec ZEC"
    }

/**
 * The title of the Chats top bar (Round 8 P11, PHONE_TRIAGE ES-3): [leading] (the self avatar) and the ZChat
 * wordmark on line 1, the balance alone on line 2.
 *
 * The balance used to sit after the wordmark on the same line, in a Text that wraps by default; next to the five
 * action icons a 392dp phone broke "0.01320586 ZEC" after "0.0" and maxLines hid the rest, so the header read as an
 * empty wallet. Line 2 has the whole title width, the balance never wraps (softWrap = false, maxLines = 1), and the
 * USD suffix is the only part that can shrink (weight(1f, fill = false), Ellipsis). Line heights are fixed so both
 * lines fit the 64dp bar; the header follows the phone's font scale up to 1.3 and stays at that size above it.
 *
 * B12: tapping the balance opens the Wallet tab ([onOpenWallet]).
 */
@Composable
internal fun ChatListHeaderTitle(
    balance: Zatoshi,
    zecPriceUsd: Double?,
    onOpenWallet: () -> Unit,
    modifier: Modifier = Modifier,
    // SDK 2.5.2 requires an explicit locale on toZecString. R5-4: the locale comes from LocalConfiguration, not
    // Locale.getDefault(): the activity keeps `locale` in configChanges, so a language change does not recreate it,
    // and Locale.getDefault() is not Compose state.
    formatLocale: Locale = rememberDesiredFormatLocale(),
    leading: @Composable () -> Unit = {},
) {
    val balanceText = chatListHeaderBalanceText(balance, formatLocale)
    val density = LocalDensity.current
    val headerDensity =
        if (density.fontScale > MAX_HEADER_FONT_SCALE) {
            Density(density.density, MAX_HEADER_FONT_SCALE)
        } else {
            density
        }
    CompositionLocalProvider(LocalDensity provides headerDensity) {
        ChatListHeaderLines(
            balanceText = balanceText,
            balance = balance,
            zecPriceUsd = zecPriceUsd,
            onOpenWallet = onOpenWallet,
            modifier = modifier,
            leading = leading,
        )
    }
}

@Composable
private fun ChatListHeaderLines(
    balanceText: String,
    balance: Zatoshi,
    zecPriceUsd: Double?,
    onOpenWallet: () -> Unit,
    modifier: Modifier,
    leading: @Composable () -> Unit,
) {
    val colors = chatColors()
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "ZChat",
                style =
                    TextStyle(
                        fontSize = 24.sp, // Direction-A: 24sp Rajdhani Bold header
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                    ),
                color = colors.primary,
                maxLines = 1,
                softWrap = false,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open wallet", onClick = onOpenWallet)
                    .padding(vertical = 2.dp)
        ) {
            Text(
                text = balanceText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.testTag(CHAT_LIST_HEADER_BALANCE_TAG)
            )
            zecPriceUsd?.let { price ->
                val usdValue = balance.value / 100_000_000.0 * price
                Text(
                    text = " ($${String.format("%.2f", usdValue)})",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .testTag(CHAT_LIST_HEADER_USD_TAG)
                )
            }
        }
    }
}

// Test tags of the balance and the USD suffix (ChatListHeaderBalanceNotClippedTest).
internal const val CHAT_LIST_HEADER_BALANCE_TAG = "chat_list_header_balance"
internal const val CHAT_LIST_HEADER_USD_TAG = "chat_list_header_usd"
